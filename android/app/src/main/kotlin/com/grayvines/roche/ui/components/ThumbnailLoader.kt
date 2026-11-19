package com.grayvines.roche.ui.components

import android.graphics.ImageDecoder
import androidx.collection.LruCache
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.grayvines.roche.data.THUMBNAILS_SUBDIR
import com.grayvines.roche.data.diskCacheKey
import com.grayvines.roche.data.isHttpThumbnailUrl
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.resolveSafeChildFile
import com.grayvines.roche.data.settings.SettingsRepository
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

val LocalThumbnailLoader = staticCompositionLocalOf<ThumbnailLoader?> { null }

internal class KeyMutex {
    val mutex = Mutex()
    var waiters: Int = 0
}

private enum class ImageSourcePolicy {
    SERVER_MEDIA_OR_HTTPS,
    EXTERNAL_HTTPS_ONLY,
}

private sealed interface FetchedImagePayload {
    data class FromFile(val file: File) : FetchedImagePayload

    data class FromBytes(val bytes: ByteArray) : FetchedImagePayload
}

class ThumbnailLoader(
    private val apiClient: RocheApiClient,
    private val settings: SettingsRepository,
    private val cacheDir: File? = null,
    private val decoder: (ByteArray) -> ImageBitmap? = ::decodeBitmapBytes,
    private val inlineDecoder: (ByteArray) -> ImageBitmap? =
        if (decoder === ::decodeBitmapBytes) ::decodeInlineBitmapBytes else decoder,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val imageCache: LruCache<String, ImageBitmap> =
        createBitmapLruCache(MAX_IMAGE_CACHE_KIB)

    private val faviconCache: LruCache<String, ImageBitmap> =
        createBitmapLruCache(MAX_FAVICON_CACHE_KIB)

    private val faviconDecoder: (ByteArray) -> ImageBitmap? =
        if (decoder === ::decodeBitmapBytes) ::decodeFaviconBitmapBytes else decoder

    private val thumbFileDecoder: (File) -> ImageBitmap? =
        if (decoder === ::decodeBitmapBytes) {
            { file -> decodeBitmapFileAtSize(file, TARGET_THUMBNAIL_PX) }
        } else {
            { file -> decoder(file.readBytes()) }
        }

    private val inlineFileDecoder: (File) -> ImageBitmap? =
        if (inlineDecoder === ::decodeInlineBitmapBytes) {
            { file -> decodeBitmapFileAtSize(file, TARGET_INLINE_IMAGE_PX) }
        } else {
            { file -> inlineDecoder(file.readBytes()) }
        }

    private val faviconFileDecoder: (File) -> ImageBitmap? =
        if (decoder === ::decodeBitmapBytes) {
            { file -> decodeBitmapFileAtSize(file, TARGET_FAVICON_PX) }
        } else {
            { file -> faviconDecoder(file.readBytes()) }
        }

    private val failedKeys = ConcurrentHashMap<String, Long>()
    private val keyLocksGuard = Any()
    private val keyLocks = HashMap<String, KeyMutex>()
    private val diskWriteLock = Any()
    private var estimatedDiskFileCount: Int = -1
    private var writesSinceLastPrune: Int = 0
    private val loadPermits = Semaphore(MAX_CONCURRENT_LOADS)

    fun getCached(mediaId: String): ImageBitmap? {
        val cleanId = mediaId.trim()
        if (cleanId.isBlank()) return null
        return imageCache["$THUMB_KEY_PREFIX$cleanId"]
    }

    fun getCachedInlineImage(imageUrl: String): ImageBitmap? {
        val cleanId = imageUrl.trim()
        if (cleanId.isBlank()) return null
        return imageCache["$INLINE_KEY_PREFIX$cleanId"]
    }

    internal fun getCachedFavicon(faviconUrl: String): ImageBitmap? {
        val cleanId = faviconUrl.trim()
        if (cleanId.isBlank()) return null
        return faviconCache[cleanId]
    }

    suspend fun loadThumbnail(mediaId: String): ImageBitmap? {
        val cleanId = mediaId.trim()
        if (cleanId.isBlank()) return null
        if (isHttpThumbnailUrl(cleanId) && !isHttpsImageUrl(cleanId)) return null
        val cacheKey = "$THUMB_KEY_PREFIX$cleanId"
        imageCache[cacheKey]?.let {
            return it
        }

        return withContext(Dispatchers.IO) {
            loadOrFetchImage(
                cleanId = cleanId,
                cacheKey = cacheKey,
                targetCache = imageCache,
                policy = ImageSourcePolicy.SERVER_MEDIA_OR_HTTPS,
                decodeBytes = decoder,
                decodeFile = thumbFileDecoder,
            )
        }
    }

    suspend fun loadInlineImage(imageUrl: String): ImageBitmap? {
        val cleanId = imageUrl.trim()
        if (!isHttpsImageUrl(cleanId)) return null
        val cacheKey = "$INLINE_KEY_PREFIX$cleanId"
        imageCache[cacheKey]?.let {
            return it
        }

        return withContext(Dispatchers.IO) {
            loadOrFetchImage(
                cleanId = cleanId,
                cacheKey = cacheKey,
                targetCache = imageCache,
                policy = ImageSourcePolicy.EXTERNAL_HTTPS_ONLY,
                decodeBytes = inlineDecoder,
                decodeFile = inlineFileDecoder,
            )
        }
    }

    internal suspend fun loadFavicon(faviconUrl: String): ImageBitmap? {
        val cleanId = faviconUrl.trim()
        if (!isHttpsImageUrl(cleanId)) return null
        faviconCache[cleanId]?.let {
            return it
        }

        return withContext(Dispatchers.IO) {
            loadOrFetchImage(
                cleanId = cleanId,
                cacheKey = cleanId,
                targetCache = faviconCache,
                policy = ImageSourcePolicy.EXTERNAL_HTTPS_ONLY,
                decodeBytes = faviconDecoder,
                decodeFile = faviconFileDecoder,
            )
        }
    }

    private suspend fun <T> withKeyLock(lockKey: String, block: suspend () -> T): T {
        val entry =
            synchronized(keyLocksGuard) {
                val existing = keyLocks.getOrPut(lockKey) { KeyMutex() }
                existing.waiters++
                existing
            }
        return try {
            entry.mutex.withLock { block() }
        } finally {
            synchronized(keyLocksGuard) {
                entry.waiters--
                if (entry.waiters == 0) {
                    keyLocks.remove(lockKey, entry)
                }
            }
        }
    }

    private suspend fun loadOrFetchImage(
        cleanId: String,
        cacheKey: String,
        targetCache: LruCache<String, ImageBitmap>,
        policy: ImageSourcePolicy,
        decodeBytes: (ByteArray) -> ImageBitmap?,
        decodeFile: (File) -> ImageBitmap?,
    ): ImageBitmap? =
        withKeyLock(diskCacheKey(cleanId)) {
            targetCache[cacheKey]
                ?: loadOrFetchLocked(
                    cleanId = cleanId,
                    cacheKey = cacheKey,
                    targetCache = targetCache,
                    policy = policy,
                    decodeBytes = decodeBytes,
                    decodeFile = decodeFile,
                )
        }

    private suspend fun loadOrFetchLocked(
        cleanId: String,
        cacheKey: String,
        targetCache: LruCache<String, ImageBitmap>,
        policy: ImageSourcePolicy,
        decodeBytes: (ByteArray) -> ImageBitmap?,
        decodeFile: (File) -> ImageBitmap?,
    ): ImageBitmap? = loadPermits.withPermit {
        val diskFile = findDiskCachedFile(cacheDir, cleanId)
        if (diskFile == null && isRecentlyFailed(cleanId)) {
            null
        } else {
            val (payload, attemptedNetwork) =
                if (diskFile != null) {
                    FetchedImagePayload.FromFile(diskFile) to false
                } else {
                    fetchAndCacheRemotePayload(cleanId, policy)
                }
            val bitmap = decodeImagePayload(payload, decodeBytes, decodeFile)
            recordLoadOutcome(
                cleanId = cleanId,
                cacheKey = cacheKey,
                targetCache = targetCache,
                diskFile = diskFile,
                payload = payload,
                attemptedNetwork = attemptedNetwork,
                bitmap = bitmap,
            )
            bitmap
        }
    }

    @Suppress("LongParameterList")
    private fun recordLoadOutcome(
        cleanId: String,
        cacheKey: String,
        targetCache: LruCache<String, ImageBitmap>,
        diskFile: File?,
        payload: FetchedImagePayload?,
        attemptedNetwork: Boolean,
        bitmap: ImageBitmap?,
    ) {
        if (bitmap != null) {
            if (diskFile != null) {
                runCatching { diskFile.setLastModified(clockMs()) }
            }
            targetCache.put(cacheKey, bitmap)
            failedKeys.remove(cleanId)
            return
        }
        if (payload is FetchedImagePayload.FromFile) {
            synchronized(diskWriteLock) {
                val deleted =
                    payload.file.exists() &&
                        runCatching { payload.file.delete() }.getOrDefault(false)
                if (deleted && estimatedDiskFileCount > 0) {
                    estimatedDiskFileCount--
                }
            }
        }
        if (attemptedNetwork || diskFile != null) {
            recordFailedKey(cleanId)
        }
    }

    internal fun isRecentlyFailed(key: String): Boolean {
        val failedAt = failedKeys[key] ?: return false
        val elapsed = clockMs() - failedAt
        return if (elapsed in 0L until FAILED_RETRY_COOLDOWN_MS) {
            true
        } else {
            failedKeys.remove(key, failedAt)
            false
        }
    }

    internal fun recordFailedKey(key: String) {
        val now = clockMs()
        if (failedKeys.size >= MAX_FAILED_THUMBNAILS) {
            failedKeys.entries.removeIf { now - it.value >= FAILED_RETRY_COOLDOWN_MS }
            if (failedKeys.size >= MAX_FAILED_THUMBNAILS) {
                failedKeys.keys.firstOrNull()?.let { failedKeys.remove(it) }
            }
        }
        failedKeys[key] = now
    }

    private suspend fun fetchAndCacheRemotePayload(
        mediaId: String,
        policy: ImageSourcePolicy,
    ): Pair<FetchedImagePayload?, Boolean> {
        val rootDir = cacheDir
        if (rootDir != null) {
            val (file, attempted) = streamRemoteFileToDiskCache(rootDir, mediaId, policy)
            return file?.let { FetchedImagePayload.FromFile(it) } to attempted
        }
        var attempted = false
        val bytes = runCatching {
            val rawBytes =
                when {
                    isHttpsImageUrl(mediaId) -> {
                        attempted = true
                        apiClient.fetchExternalImageBytes(mediaId)
                    }
                    policy == ImageSourcePolicy.EXTERNAL_HTTPS_ONLY ||
                        isHttpThumbnailUrl(mediaId) -> null
                    else -> {
                        val currentSettings = settings.settings.first()
                        val serverUrl = currentSettings.serverUrl
                        val token = currentSettings.bearerToken
                        if (serverUrl.isNotBlank() && token.isNotBlank()) {
                            attempted = true
                            apiClient.fetchMediaBytes(serverUrl, token, mediaId)
                        } else {
                            null
                        }
                    }
                }
            rawBytes?.takeIf { it.size in 1..MAX_THUMBNAIL_BYTES }
        }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        return bytes?.let { FetchedImagePayload.FromBytes(it) } to attempted
    }

    private suspend fun streamRemoteFileToDiskCache(
        rootDir: File,
        mediaId: String,
        policy: ImageSourcePolicy,
    ): Pair<File?, Boolean> {
        var attempted = false
        var tmpFile: File? = null
        val committedFile =
            try {
                val thumbsDir = File(rootDir, THUMBNAILS_SUBDIR).apply { mkdirs() }
                val fileKey = diskCacheKey(mediaId)
                val stagingFile =
                    resolveSafeChildFile(thumbsDir, "$fileKey.${System.nanoTime()}.tmp")
                tmpFile = stagingFile
                val streamed =
                    when {
                        isHttpsImageUrl(mediaId) -> {
                            attempted = true
                            apiClient.streamExternalBinaryToFile(
                                mediaId,
                                stagingFile,
                                MAX_THUMBNAIL_BYTES,
                            )
                        }
                        policy == ImageSourcePolicy.EXTERNAL_HTTPS_ONLY ||
                            isHttpThumbnailUrl(mediaId) -> false
                        else -> {
                            val currentSettings = settings.settings.first()
                            val serverUrl = currentSettings.serverUrl
                            val token = currentSettings.bearerToken
                            if (serverUrl.isNotBlank() && token.isNotBlank()) {
                                attempted = true
                                apiClient.streamMediaToFile(
                                    serverUrl = serverUrl,
                                    token = token,
                                    mediaId = mediaId,
                                    targetFile = stagingFile,
                                    maxBytes = MAX_THUMBNAIL_BYTES,
                                )
                            } else {
                                false
                            }
                        }
                    }
                if (
                    streamed && stagingFile.isFile && stagingFile.length() in 1..MAX_THUMBNAIL_BYTES
                ) {
                    commitStreamedCacheFile(thumbsDir, fileKey, stagingFile)
                } else {
                    null
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null
            } finally {
                tmpFile?.let { file ->
                    if (file.exists()) {
                        runCatching { file.delete() }
                    }
                }
            }
        return committedFile to attempted
    }

    private fun commitStreamedCacheFile(
        thumbsDir: File,
        fileKey: String,
        tmpFile: File,
    ): File? =
        synchronized(diskWriteLock) {
            val targetFile = resolveSafeChildFile(thumbsDir, fileKey)
            val existedBefore = targetFile.isFile
            try {
                runCatching {
                    Files.move(
                        tmpFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                }
                    .onFailure {
                        runCatching {
                            Files.move(
                                tmpFile.toPath(),
                                targetFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                        }
                            .onFailure {
                                tmpFile.copyTo(targetFile, overwrite = true)
                            }
                    }
            } finally {
                if (tmpFile.exists()) {
                    runCatching { tmpFile.delete() }
                }
            }
            targetFile.setLastModified(clockMs())
            if (estimatedDiskFileCount < 0) {
                estimatedDiskFileCount =
                    thumbsDir.listFiles()?.count { it.isFile && !it.name.endsWith(".tmp") } ?: 1
            } else if (!existedBefore) {
                estimatedDiskFileCount++
            }
            writesSinceLastPrune++
            if (
                estimatedDiskFileCount > MAX_DISK_CACHED_FILES ||
                    writesSinceLastPrune >= PRUNE_WRITE_INTERVAL
            ) {
                estimatedDiskFileCount = pruneThumbnailDiskCache(thumbsDir)
                writesSinceLastPrune = 0
            }
            targetFile.takeIf { it.isFile && it.length() in 1..MAX_THUMBNAIL_BYTES }
        }

    internal companion object {
        internal const val MAX_CONCURRENT_LOADS = 4
        internal const val FAILED_RETRY_COOLDOWN_MS = 60_000L
        internal const val MAX_DISK_CACHED_FILES = 512
        internal const val MAX_DISK_CACHE_BYTES = 64L * 1024L * 1024L
        private const val PRUNE_WRITE_INTERVAL = 32
        private const val THUMB_KEY_PREFIX = "thumb:"
        private const val INLINE_KEY_PREFIX = "inline:"
        private const val MAX_IMAGE_CACHE_KIB = 32 * 1024
        private const val MAX_FAVICON_CACHE_KIB = 4 * 1024
        private const val MAX_FAILED_THUMBNAILS = 256
        internal const val MAX_THUMBNAIL_BYTES = 10L * 1024L * 1024L
    }
}

private fun findDiskCachedFile(cacheDir: File?, mediaId: String): File? = runCatching {
    val rootDir = cacheDir ?: return null
    val thumbsDir = File(rootDir, THUMBNAILS_SUBDIR)
    if (!thumbsDir.exists()) return null
    val thumbFile = resolveSafeChildFile(thumbsDir, diskCacheKey(mediaId))
    if (thumbFile.isFile && thumbFile.length() in 1..ThumbnailLoader.MAX_THUMBNAIL_BYTES) {
        thumbFile
    } else {
        null
    }
}
    .getOrNull()

private fun decodeImagePayload(
    payload: FetchedImagePayload?,
    decodeBytes: (ByteArray) -> ImageBitmap?,
    decodeFile: (File) -> ImageBitmap?,
): ImageBitmap? =
    when (payload) {
        is FetchedImagePayload.FromFile ->
            runCatching { decodeFile(payload.file) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
        is FetchedImagePayload.FromBytes ->
            runCatching { decodeBytes(payload.bytes) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
        null -> null
    }

internal fun isHttpsImageUrl(url: String): Boolean =
    url.trim().startsWith("https://", ignoreCase = true)

internal fun pruneThumbnailDiskCache(
    thumbsDir: File,
    maxFiles: Int = ThumbnailLoader.MAX_DISK_CACHED_FILES,
    maxBytes: Long = ThumbnailLoader.MAX_DISK_CACHE_BYTES,
): Int {
    val files = thumbsDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return 0
    var remainingCount = files.size
    var totalBytes = files.sumOf { it.length() }
    if (remainingCount <= maxFiles && totalBytes <= maxBytes) return remainingCount

    val sortedOldestFirst =
        files.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
    for (file in sortedOldestFirst) {
        if (remainingCount <= maxFiles && totalBytes <= maxBytes) break
        val fileLen = file.length()
        if (file.delete() || !file.exists()) {
            remainingCount--
            totalBytes = (totalBytes - fileLen).coerceAtLeast(0L)
        }
    }
    return remainingCount
}

internal fun createBitmapLruCache(maxSizeKiB: Int): LruCache<String, ImageBitmap> =
    object : LruCache<String, ImageBitmap>(maxSizeKiB) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            (value.width.toLong() * value.height.toLong() * BYTES_PER_PIXEL / KIB_BYTES)
                .toInt()
                .coerceAtLeast(1)
    }

internal fun decodeBitmapBytes(bytes: ByteArray): ImageBitmap? =
    decodeBitmapBytesAtSize(bytes, TARGET_THUMBNAIL_PX)

internal fun decodeInlineBitmapBytes(bytes: ByteArray): ImageBitmap? =
    decodeBitmapBytesAtSize(bytes, TARGET_INLINE_IMAGE_PX)

internal fun decodeFaviconBitmapBytes(bytes: ByteArray): ImageBitmap? =
    decodeBitmapBytesAtSize(bytes, TARGET_FAVICON_PX)

private fun decodeBitmapBytesAtSize(bytes: ByteArray, targetPx: Int): ImageBitmap? {
    if (bytes.isEmpty()) return null
    return decodeImageSourceAtSize(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), targetPx)
}

private fun decodeBitmapFileAtSize(file: File, targetPx: Int): ImageBitmap? {
    if (!file.isFile || file.length() <= 0L) return null
    return decodeImageSourceAtSize(ImageDecoder.createSource(file), targetPx)
}

private fun decodeImageSourceAtSize(
    source: ImageDecoder.Source,
    targetPx: Int,
): ImageBitmap? = runCatching {
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val targetDims =
                computeProportionalTargetSize(
                    rawWidth = info.size.width,
                    rawHeight = info.size.height,
                    targetPx = targetPx,
                )
            if (targetDims != null) {
                decoder.setTargetSize(targetDims.first, targetDims.second)
            }
        }
        .asImageBitmap()
}
    .getOrNull()

internal fun computeProportionalTargetSize(
    rawWidth: Int,
    rawHeight: Int,
    targetPx: Int,
): Pair<Int, Int>? {
    if (rawWidth <= 0 || rawHeight <= 0) return null
    val maxEdge = minOf(targetPx, MAX_BITMAP_DIMENSION_PX).coerceAtLeast(1)
    val longerRaw = maxOf(rawWidth, rawHeight)
    if (longerRaw <= maxEdge) return null
    val scale = maxEdge.toDouble() / longerRaw.toDouble()
    val targetW = (rawWidth * scale).roundToInt().coerceIn(1, maxEdge)
    val targetH = (rawHeight * scale).roundToInt().coerceIn(1, maxEdge)
    return targetW to targetH
}

internal const val MAX_BITMAP_DIMENSION_PX = 2048
private const val TARGET_FAVICON_PX = 128
private const val TARGET_THUMBNAIL_PX = 256
private const val TARGET_INLINE_IMAGE_PX = 1440
internal const val BYTES_PER_PIXEL = 4L
internal const val KIB_BYTES = 1024L

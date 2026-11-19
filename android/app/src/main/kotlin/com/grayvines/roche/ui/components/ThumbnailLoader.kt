package com.grayvines.roche.ui.components

import android.graphics.ImageDecoder
import androidx.collection.LruCache
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.grayvines.roche.data.ThumbnailDiskCache
import com.grayvines.roche.data.diskCacheKey
import com.grayvines.roche.data.isHttpThumbnailUrl
import com.grayvines.roche.data.pruneThumbnailDiskCacheFiles
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.SettingsRepository
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

val LocalThumbnailLoader = staticCompositionLocalOf<ThumbnailLoader?> { null }

internal enum class ImageSourcePolicy {
    SERVER_MEDIA_OR_HTTPS,
    EXTERNAL_HTTPS_ONLY,
}

internal sealed interface FetchedImagePayload {
    data class FromFile(val file: File) : FetchedImagePayload

    data class FromBytes(val bytes: ByteArray) : FetchedImagePayload
}

class ThumbnailLoader(
    private val apiClient: RocheApiClient,
    private val settings: SettingsRepository,
    cacheDir: File? = null,
    private val decoder: (ByteArray) -> ImageBitmap? = ::decodeBitmapBytes,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val diskCache: ThumbnailDiskCache = ThumbnailDiskCache(cacheDir, clockMs = clockMs),
) {
    private val imageCache: LruCache<String, ImageBitmap> =
        createBitmapLruCache(MAX_IMAGE_CACHE_KIB)

    private val faviconCache: LruCache<String, ImageBitmap> =
        createBitmapLruCache(MAX_FAVICON_CACHE_KIB)

    private val inlineDecoder: (ByteArray) -> ImageBitmap? =
        if (decoder === ::decodeBitmapBytes) ::decodeInlineBitmapBytes else decoder

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

    private suspend fun loadOrFetchImage(
        cleanId: String,
        cacheKey: String,
        targetCache: LruCache<String, ImageBitmap>,
        policy: ImageSourcePolicy,
        decodeBytes: (ByteArray) -> ImageBitmap?,
        decodeFile: (File) -> ImageBitmap?,
    ): ImageBitmap? =
        diskCache.withKeyLock(diskCacheKey(cleanId)) {
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
        val diskFile = diskCache.findCachedFile(cleanId)
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
                diskCache.touchFile(diskFile, clockMs())
            }
            targetCache.put(cacheKey, bitmap)
            failedKeys.remove(cleanId)
            return
        }
        if (payload is FetchedImagePayload.FromFile) {
            diskCache.deleteCorruptFile(payload.file)
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
        if (diskCache.cacheDir != null) {
            val (file, attempted) = streamRemoteFileToDiskCache(mediaId, policy)
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
                        isHttpThumbnailUrl(mediaId) -> {
                        null
                    }
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
        mediaId: String,
        policy: ImageSourcePolicy,
    ): Pair<File?, Boolean> {
        var attempted = false
        var tmpFile: File? = null
        val committedFile =
            try {
                val fileKey = diskCacheKey(mediaId)
                val stagingFile = diskCache.createStagingFile(fileKey)
                tmpFile = stagingFile
                val (streamed, didAttempt) =
                    if (stagingFile != null) {
                        streamRemoteSourceToFile(apiClient, settings, mediaId, policy, stagingFile)
                    } else {
                        false to false
                    }
                attempted = didAttempt
                val validFile = stagingFile?.takeIf {
                    streamed && it.isFile && it.length() in 1..MAX_THUMBNAIL_BYTES
                }
                validFile?.let { diskCache.commitStagedFile(fileKey, it, clockMs()) }
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

    internal companion object {
        internal const val MAX_CONCURRENT_LOADS = 4
        internal const val FAILED_RETRY_COOLDOWN_MS = 60_000L
        internal const val MAX_DISK_CACHED_FILES = ThumbnailDiskCache.MAX_DISK_CACHED_FILES
        internal const val MAX_DISK_CACHE_BYTES = ThumbnailDiskCache.MAX_DISK_CACHE_BYTES
        private const val THUMB_KEY_PREFIX = "thumb:"
        private const val INLINE_KEY_PREFIX = "inline:"
        private const val MAX_IMAGE_CACHE_KIB = 32 * 1024
        private const val MAX_FAVICON_CACHE_KIB = 4 * 1024
        private const val MAX_FAILED_THUMBNAILS = 256
        internal const val MAX_THUMBNAIL_BYTES = ThumbnailDiskCache.MAX_THUMBNAIL_BYTES
    }
}

internal suspend fun streamRemoteSourceToFile(
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    mediaId: String,
    policy: ImageSourcePolicy,
    stagingFile: File,
): Pair<Boolean, Boolean> =
    when {
        isHttpsImageUrl(mediaId) -> {
            apiClient.streamExternalBinaryToFile(
                mediaId,
                stagingFile,
                ThumbnailLoader.MAX_THUMBNAIL_BYTES,
            ) to true
        }
        policy == ImageSourcePolicy.EXTERNAL_HTTPS_ONLY || isHttpThumbnailUrl(mediaId) -> {
            false to false
        }
        else -> {
            val currentSettings = settings.settings.first()
            val serverUrl = currentSettings.serverUrl
            val token = currentSettings.bearerToken
            if (serverUrl.isNotBlank() && token.isNotBlank()) {
                apiClient.streamMediaToFile(
                    serverUrl = serverUrl,
                    token = token,
                    mediaId = mediaId,
                    targetFile = stagingFile,
                    maxBytes = ThumbnailLoader.MAX_THUMBNAIL_BYTES,
                ) to true
            } else {
                false to false
            }
        }
    }

internal fun decodeImagePayload(
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
): Int = pruneThumbnailDiskCacheFiles(thumbsDir, maxFiles, maxBytes)

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

internal fun decodeBitmapFileAtSize(file: File, targetPx: Int): ImageBitmap? {
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

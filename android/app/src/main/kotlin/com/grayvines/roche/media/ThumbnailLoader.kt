package com.grayvines.roche.media

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.collection.LruCache
import com.grayvines.roche.core.isHttpOrHttpsUrl
import com.grayvines.roche.core.isHttpsUrl
import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.data.remote.PublicWebFetcher
import com.grayvines.roche.data.remote.RocheServerApi
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

@Suppress("LongParameterList")
class ThumbnailLoader(
    serverApi: RocheServerApi,
    settings: SettingsRepository,
    cacheDir: File? = null,
    decoder: (ByteArray) -> Bitmap? = ::decodeBitmapBytes,
    clockMs: () -> Long = System::currentTimeMillis,
    diskCache: ThumbnailDiskCache = ThumbnailDiskCache(cacheDir, clockMs = clockMs),
    publicWebFetcher: PublicWebFetcher = PublicWebFetcher(),
) :
    BitmapLoader<Bitmap>(
        serverApi = serverApi,
        publicWebFetcher = publicWebFetcher,
        settings = settings,
        decoders = createBitmapDecoders(decoder),
        clockMs = clockMs,
        diskCache = diskCache,
    ) {
    suspend fun loadBitmap(url: String): Bitmap? = loadThumbnail(url)

    internal companion object {
        internal const val MAX_CONCURRENT_LOADS = 4
        internal const val FAILED_RETRY_COOLDOWN_MS = 60_000L
        internal const val MAX_DISK_CACHED_FILES = ThumbnailDiskCache.MAX_DISK_CACHED_FILES
        internal const val MAX_DISK_CACHE_BYTES = ThumbnailDiskCache.MAX_DISK_CACHE_BYTES
        internal const val MAX_THUMBNAIL_BYTES = ThumbnailDiskCache.MAX_THUMBNAIL_BYTES
    }
}

internal enum class ImageSourcePolicy {
    SERVER_MEDIA_OR_HTTPS,
    EXTERNAL_HTTPS_ONLY,
}

internal sealed interface FetchedImagePayload {
    data class FromFile(val file: File) : FetchedImagePayload

    class FromBytes(val bytes: ByteArray) : FetchedImagePayload
}

internal data class ImageDecoders<T : Any>(
    val decodeBytes: (ByteArray) -> T?,
    val decodeInlineBytes: (ByteArray) -> T? = decodeBytes,
    val decodeFaviconBytes: (ByteArray) -> T? = decodeBytes,
    val decodeFile: ((File, Int) -> T?)? = null,
    val sizeOfKiB: (T) -> Int,
)

@Suppress("LongParameterList")
open class BitmapLoader<T : Any>
internal constructor(
    private val serverApi: RocheServerApi,
    private val settings: SettingsRepository,
    private val decoders: ImageDecoders<T>,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val diskCache: ThumbnailDiskCache = ThumbnailDiskCache(null, clockMs = clockMs),
    private val publicWebFetcher: PublicWebFetcher = PublicWebFetcher(),
) {
    private val imageCache: LruCache<String, T> =
        createImageLruCache(MAX_IMAGE_CACHE_KIB, decoders.sizeOfKiB)

    private val faviconCache: LruCache<String, T> =
        createImageLruCache(MAX_FAVICON_CACHE_KIB, decoders.sizeOfKiB)

    private val thumbFileDecoder: (File) -> T? =
        decoders.decodeFile?.let { fileDec -> { file -> fileDec(file, TARGET_THUMBNAIL_PX) } }
            ?: { file ->
                decoders.decodeBytes(file.readBytes())
            }

    private val inlineFileDecoder: (File) -> T? =
        decoders.decodeFile?.let { fileDec -> { file -> fileDec(file, TARGET_INLINE_IMAGE_PX) } }
            ?: { file ->
                decoders.decodeInlineBytes(file.readBytes())
            }

    private val faviconFileDecoder: (File) -> T? =
        decoders.decodeFile?.let { fileDec -> { file -> fileDec(file, TARGET_FAVICON_PX) } }
            ?: { file ->
                decoders.decodeFaviconBytes(file.readBytes())
            }

    private val failedKeys = ConcurrentHashMap<String, Long>()
    private val loadPermits = Semaphore(ThumbnailLoader.MAX_CONCURRENT_LOADS)

    fun getCached(mediaId: String): T? {
        val cleanId = mediaId.trim()
        if (cleanId.isBlank()) return null
        return imageCache["$THUMB_KEY_PREFIX$cleanId"]
    }

    fun getCachedInlineImage(imageUrl: String): T? {
        val cleanId = imageUrl.trim()
        if (cleanId.isBlank()) return null
        return imageCache["$INLINE_KEY_PREFIX$cleanId"]
    }

    internal fun getCachedFavicon(faviconUrl: String): T? {
        val cleanId = faviconUrl.trim()
        if (cleanId.isBlank()) return null
        return faviconCache[cleanId]
    }

    fun clearFailedKey(key: String) {
        val cleanId = key.trim()
        if (cleanId.isNotEmpty()) {
            failedKeys.remove(cleanId)
        }
    }

    suspend fun loadThumbnail(mediaId: String): T? {
        val cleanId = mediaId.trim()
        if (cleanId.isBlank() || isHttpOrHttpsUrl(cleanId) && !isHttpsUrl(cleanId)) return null
        val cacheKey = "$THUMB_KEY_PREFIX$cleanId"
        return imageCache[cacheKey]
            ?: withContext(Dispatchers.IO) {
                loadOrFetchImage(
                    cleanId = cleanId,
                    cacheKey = cacheKey,
                    targetCache = imageCache,
                    policy = ImageSourcePolicy.SERVER_MEDIA_OR_HTTPS,
                    decodeBytes = decoders.decodeBytes,
                    decodeFile = thumbFileDecoder,
                )
            }
    }

    suspend fun loadInlineImage(imageUrl: String): T? {
        val cleanId = imageUrl.trim()
        if (!isHttpsUrl(cleanId)) return null
        val cacheKey = "$INLINE_KEY_PREFIX$cleanId"
        return imageCache[cacheKey]
            ?: withContext(Dispatchers.IO) {
                loadOrFetchImage(
                    cleanId = cleanId,
                    cacheKey = cacheKey,
                    targetCache = imageCache,
                    policy = ImageSourcePolicy.EXTERNAL_HTTPS_ONLY,
                    decodeBytes = decoders.decodeInlineBytes,
                    decodeFile = inlineFileDecoder,
                )
            }
    }

    internal suspend fun loadFavicon(faviconUrl: String): T? {
        val cleanId = faviconUrl.trim()
        if (!isHttpsUrl(cleanId)) return null
        return faviconCache[cleanId]
            ?: withContext(Dispatchers.IO) {
                loadOrFetchImage(
                    cleanId = cleanId,
                    cacheKey = cleanId,
                    targetCache = faviconCache,
                    policy = ImageSourcePolicy.EXTERNAL_HTTPS_ONLY,
                    decodeBytes = decoders.decodeFaviconBytes,
                    decodeFile = faviconFileDecoder,
                )
            }
    }

    @Suppress("LongParameterList")
    private suspend fun loadOrFetchImage(
        cleanId: String,
        cacheKey: String,
        targetCache: LruCache<String, T>,
        policy: ImageSourcePolicy,
        decodeBytes: (ByteArray) -> T?,
        decodeFile: (File) -> T?,
    ): T? =
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

    @Suppress("LongParameterList")
    private suspend fun loadOrFetchLocked(
        cleanId: String,
        cacheKey: String,
        targetCache: LruCache<String, T>,
        policy: ImageSourcePolicy,
        decodeBytes: (ByteArray) -> T?,
        decodeFile: (File) -> T?,
    ): T? = loadPermits.withPermit {
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
        targetCache: LruCache<String, T>,
        diskFile: File?,
        payload: FetchedImagePayload?,
        attemptedNetwork: Boolean,
        bitmap: T?,
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
        return if (elapsed in 0L until ThumbnailLoader.FAILED_RETRY_COOLDOWN_MS) {
            true
        } else {
            failedKeys.remove(key, failedAt)
            false
        }
    }

    internal fun recordFailedKey(key: String) {
        val now = clockMs()
        if (failedKeys.size >= MAX_FAILED_THUMBNAILS) {
            failedKeys.entries.removeIf {
                now - it.value >= ThumbnailLoader.FAILED_RETRY_COOLDOWN_MS
            }
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
        val bytes = runSuspendCatching {
            val rawBytes =
                when {
                    isHttpsUrl(mediaId) -> {
                        attempted = true
                        publicWebFetcher.fetchImageBytes(
                            imageUrl = mediaId,
                            maxBytes = ThumbnailLoader.MAX_THUMBNAIL_BYTES.toInt(),
                        )
                    }
                    policy == ImageSourcePolicy.EXTERNAL_HTTPS_ONLY ||
                        isHttpOrHttpsUrl(mediaId) -> {
                        null
                    }
                    else -> {
                        val currentSettings = settings.settings.first()
                        val serverUrl = currentSettings.serverUrl
                        val token = currentSettings.bearerToken
                        if (serverUrl.isNotBlank() && token.isNotBlank()) {
                            attempted = true
                            serverApi.fetchMediaBytes(serverUrl, token, mediaId)
                        } else {
                            null
                        }
                    }
                }
            rawBytes?.takeIf { it.size in 1..ThumbnailLoader.MAX_THUMBNAIL_BYTES }
        }
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
                runSuspendCatching {
                    val fileKey = diskCacheKey(mediaId)
                    val stagingFile = diskCache.createStagingFile(fileKey)
                    tmpFile = stagingFile
                    val (streamed, didAttempt) =
                        if (stagingFile != null) {
                            streamRemoteSourceToFile(
                                serverApi = serverApi,
                                publicWebFetcher = publicWebFetcher,
                                settings = settings,
                                mediaId = mediaId,
                                policy = policy,
                                stagingFile = stagingFile,
                            )
                        } else {
                            false to false
                        }
                    attempted = didAttempt
                    val validFile = stagingFile?.takeIf {
                        streamed &&
                            it.isFile &&
                            it.length() in 1..ThumbnailLoader.MAX_THUMBNAIL_BYTES
                    }
                    validFile?.let { diskCache.commitStagedFile(fileKey, it, clockMs()) }
                }
                    .getOrNull()
            } finally {
                tmpFile?.let { file ->
                    if (file.exists()) {
                        runCatching { file.delete() }
                    }
                }
            }
        return committedFile to attempted
    }

    private companion object {
        private const val THUMB_KEY_PREFIX = "thumb:"
        private const val INLINE_KEY_PREFIX = "inline:"
        private const val MAX_IMAGE_CACHE_KIB = 32 * 1024
        private const val MAX_FAVICON_CACHE_KIB = 4 * 1024
        private const val MAX_FAILED_THUMBNAILS = 256
    }
}

internal fun createBitmapDecoders(decoder: (ByteArray) -> Bitmap?): ImageDecoders<Bitmap> =
    if (decoder === ::decodeBitmapBytes) {
        ImageDecoders(
            decodeBytes = ::decodeBitmapBytes,
            decodeInlineBytes = ::decodeInlineBitmapBytes,
            decodeFaviconBytes = ::decodeFaviconBitmapBytes,
            decodeFile = ::decodeBitmapFileAtSize,
            sizeOfKiB = ::bitmapSizeKiB,
        )
    } else {
        ImageDecoders(
            decodeBytes = decoder,
            sizeOfKiB = ::bitmapSizeKiB,
        )
    }

internal fun bitmapSizeKiB(bitmap: Bitmap): Int =
    (bitmap.width.toLong() * bitmap.height.toLong() * BYTES_PER_PIXEL / KIB_BYTES)
        .toInt()
        .coerceAtLeast(1)

@Suppress("LongParameterList")
internal suspend fun streamRemoteSourceToFile(
    serverApi: RocheServerApi,
    publicWebFetcher: PublicWebFetcher,
    settings: SettingsRepository,
    mediaId: String,
    policy: ImageSourcePolicy,
    stagingFile: File,
): Pair<Boolean, Boolean> =
    when {
        isHttpsUrl(mediaId) -> {
            publicWebFetcher.streamBinaryToFile(
                fileUrl = mediaId,
                targetFile = stagingFile,
                maxBytes = ThumbnailLoader.MAX_THUMBNAIL_BYTES,
            ) to true
        }
        policy == ImageSourcePolicy.EXTERNAL_HTTPS_ONLY || isHttpOrHttpsUrl(mediaId) -> {
            false to false
        }
        else -> {
            val currentSettings = settings.settings.first()
            val serverUrl = currentSettings.serverUrl
            val token = currentSettings.bearerToken
            if (serverUrl.isNotBlank() && token.isNotBlank()) {
                serverApi.streamMediaToFile(
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

internal fun <T : Any> decodeImagePayload(
    payload: FetchedImagePayload?,
    decodeBytes: (ByteArray) -> T?,
    decodeFile: (File) -> T?,
): T? =
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

internal fun pruneThumbnailDiskCache(
    thumbsDir: File,
    maxFiles: Int = ThumbnailLoader.MAX_DISK_CACHED_FILES,
    maxBytes: Long = ThumbnailLoader.MAX_DISK_CACHE_BYTES,
): Int = pruneThumbnailDiskCacheFiles(thumbsDir, maxFiles, maxBytes)

internal fun <T : Any> createImageLruCache(
    maxSizeKiB: Int,
    sizeOfKiB: (T) -> Int,
): LruCache<String, T> =
    object : LruCache<String, T>(maxSizeKiB) {
        override fun sizeOf(key: String, value: T): Int = sizeOfKiB(value).coerceAtLeast(1)
    }

internal fun decodeBitmapBytes(bytes: ByteArray): Bitmap? =
    decodeBitmapBytesAtSize(bytes, TARGET_THUMBNAIL_PX)

internal fun decodeInlineBitmapBytes(bytes: ByteArray): Bitmap? =
    decodeBitmapBytesAtSize(bytes, TARGET_INLINE_IMAGE_PX)

internal fun decodeFaviconBitmapBytes(bytes: ByteArray): Bitmap? =
    decodeBitmapBytesAtSize(bytes, TARGET_FAVICON_PX)

internal fun decodeBitmapBytesAtSize(bytes: ByteArray, targetPx: Int): Bitmap? {
    if (bytes.isEmpty()) return null
    return decodeImageSourceAtSize(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), targetPx)
}

internal fun decodeBitmapFileAtSize(file: File, targetPx: Int): Bitmap? {
    if (!file.isFile || file.length() <= 0L) return null
    return decodeImageSourceAtSize(ImageDecoder.createSource(file), targetPx)
}

internal fun decodeImageSourceAtSize(
    source: ImageDecoder.Source,
    targetPx: Int,
): Bitmap? = runCatching {
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
internal const val TARGET_FAVICON_PX = 128
internal const val TARGET_THUMBNAIL_PX = 256
internal const val TARGET_INLINE_IMAGE_PX = 1440
internal const val BYTES_PER_PIXEL = 4L
internal const val KIB_BYTES = 1024L

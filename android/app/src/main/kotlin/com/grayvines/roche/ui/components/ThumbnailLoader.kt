package com.grayvines.roche.ui.components

import android.graphics.BitmapFactory
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.grayvines.roche.data.THUMBNAILS_SUBDIR
import com.grayvines.roche.data.diskCacheKey
import com.grayvines.roche.data.isHttpThumbnailUrl
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.resolveSafeChildFile
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.data.writeBytesAtomically
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

val LocalThumbnailLoader = staticCompositionLocalOf<ThumbnailLoader?> { null }

class ThumbnailLoader(
    private val apiClient: RocheApiClient,
    private val settings: SettingsRepository,
    private val cacheDir: File? = null,
    private val decoder: (ByteArray) -> ImageBitmap? = ::decodeBitmapBytes,
    private val inlineDecoder: (ByteArray) -> ImageBitmap? =
        if (decoder === ::decodeBitmapBytes) ::decodeInlineBitmapBytes else decoder,
) {
    private val memoryCache =
        object : LinkedHashMap<String, ImageBitmap>(INITIAL_CACHE_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, ImageBitmap>?
            ): Boolean = size > MAX_MEMORY_THUMBNAILS
        }

    private val inlineMemoryCache =
        object : LinkedHashMap<String, ImageBitmap>(INITIAL_CACHE_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, ImageBitmap>?
            ): Boolean = size > MAX_MEMORY_INLINE_IMAGES
        }

    private val failedKeys = LinkedHashSet<String>()

    fun getCached(mediaId: String): ImageBitmap? {
        val cleanId = mediaId.trim()
        if (cleanId.isBlank()) return null
        return synchronized(memoryCache) { memoryCache[cleanId] }
    }

    fun getCachedInlineImage(imageUrl: String): ImageBitmap? {
        val cleanId = imageUrl.trim()
        if (cleanId.isBlank()) return null
        return synchronized(inlineMemoryCache) { inlineMemoryCache[cleanId] }
    }

    suspend fun loadThumbnail(mediaId: String): ImageBitmap? {
        val cleanId = mediaId.trim()
        if (cleanId.isBlank()) return null
        getCached(cleanId)?.let {
            return it
        }

        return withContext(Dispatchers.IO) {
            loadOrFetchImage(cleanId = cleanId, targetCache = memoryCache, decode = decoder)
        }
    }

    suspend fun loadInlineImage(imageUrl: String): ImageBitmap? {
        val cleanId = imageUrl.trim()
        if (cleanId.isBlank()) return null
        getCachedInlineImage(cleanId)?.let {
            return it
        }

        return withContext(Dispatchers.IO) {
            loadOrFetchImage(
                cleanId = cleanId,
                targetCache = inlineMemoryCache,
                decode = inlineDecoder,
            )
        }
    }

    private suspend fun loadOrFetchImage(
        cleanId: String,
        targetCache: LinkedHashMap<String, ImageBitmap>,
        decode: (ByteArray) -> ImageBitmap?,
    ): ImageBitmap? {
        val diskBytes = readDiskCachedBytes(cleanId)
        val alreadyFailed = synchronized(failedKeys) { cleanId in failedKeys }
        if (diskBytes == null && alreadyFailed) return null
        val (bytes, attemptedNetwork) =
            if (diskBytes != null) {
                diskBytes to false
            } else {
                fetchAndCacheRemoteBytes(cleanId)
            }
        val bitmap = bytes?.let { runCatching { decode(it) }.getOrNull() }
        if (bitmap != null) {
            synchronized(targetCache) { targetCache[cleanId] = bitmap }
            synchronized(failedKeys) { failedKeys.remove(cleanId) }
        } else if (attemptedNetwork || diskBytes != null) {
            recordFailedKey(cleanId)
        }
        return bitmap
    }

    private fun recordFailedKey(key: String) {
        synchronized(failedKeys) {
            if (failedKeys.size >= MAX_FAILED_THUMBNAILS && failedKeys.isNotEmpty()) {
                val eldest = failedKeys.first()
                failedKeys.remove(eldest)
            }
            failedKeys.add(key)
        }
    }

    private fun readDiskCachedBytes(mediaId: String): ByteArray? = runCatching {
        val rootDir = cacheDir ?: return null
        val thumbsDir = File(rootDir, THUMBNAILS_SUBDIR)
        if (!thumbsDir.exists()) return null
        val thumbFile = resolveSafeChildFile(thumbsDir, diskCacheKey(mediaId))
        if (thumbFile.isFile && thumbFile.length() in 1..MAX_THUMBNAIL_BYTES) {
            thumbFile.readBytes()
        } else {
            null
        }
    }
        .getOrNull()

    private suspend fun fetchAndCacheRemoteBytes(mediaId: String): Pair<ByteArray?, Boolean> {
        var attempted = false
        val bytes = runCatching {
            val rawBytes =
                if (isHttpThumbnailUrl(mediaId)) {
                    attempted = true
                    apiClient.fetchExternalImageBytes(mediaId)
                } else {
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
            val validBytes = rawBytes?.takeIf { it.size in 1..MAX_THUMBNAIL_BYTES }
            if (validBytes != null) {
                writeDiskCachedBytes(mediaId, validBytes)
            }
            validBytes
        }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        return bytes to attempted
    }

    private fun writeDiskCachedBytes(mediaId: String, bytes: ByteArray) {
        val rootDir = cacheDir ?: return
        runCatching {
            val thumbsDir = File(rootDir, THUMBNAILS_SUBDIR)
            writeBytesAtomically(thumbsDir, diskCacheKey(mediaId), bytes)
        }
    }

    private companion object {
        private const val INITIAL_CACHE_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
        private const val MAX_MEMORY_THUMBNAILS = 64
        private const val MAX_MEMORY_INLINE_IMAGES = 24
        private const val MAX_FAILED_THUMBNAILS = 256
        private const val MAX_THUMBNAIL_BYTES = 10L * 1024L * 1024L
    }
}

private fun decodeBitmapBytes(bytes: ByteArray): ImageBitmap? =
    decodeBitmapBytesAtSize(bytes, TARGET_THUMBNAIL_PX)

private fun decodeInlineBitmapBytes(bytes: ByteArray): ImageBitmap? =
    decodeBitmapBytesAtSize(bytes, TARGET_INLINE_IMAGE_PX)

private fun decodeBitmapBytesAtSize(bytes: ByteArray, targetPx: Int): ImageBitmap? {
    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
    if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

    val decodeOptions =
        BitmapFactory.Options().apply {
            inSampleSize =
                calculateInSampleSize(
                    rawWidth = boundsOptions.outWidth,
                    rawHeight = boundsOptions.outHeight,
                    reqWidth = targetPx,
                    reqHeight = targetPx,
                )
        }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)?.asImageBitmap()
}

internal fun calculateInSampleSize(
    rawWidth: Int,
    rawHeight: Int,
    reqWidth: Int = TARGET_THUMBNAIL_PX,
    reqHeight: Int = TARGET_THUMBNAIL_PX,
): Int {
    var inSampleSize = 1
    if (rawHeight > reqHeight || rawWidth > reqWidth) {
        val halfHeight = rawHeight / SAMPLE_DIVISOR
        val halfWidth = rawWidth / SAMPLE_DIVISOR
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= SAMPLE_DIVISOR
        }
    }
    return inSampleSize
}

private const val TARGET_THUMBNAIL_PX = 256
private const val TARGET_INLINE_IMAGE_PX = 1440
private const val SAMPLE_DIVISOR = 2

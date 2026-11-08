package com.grayvines.roche.ui.components

import android.graphics.BitmapFactory
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.grayvines.roche.data.THUMBNAILS_SUBDIR
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.resolveSafeChildFile
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.data.writeBytesAtomically
import java.io.File
import java.security.MessageDigest
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
) {
    private val memoryCache =
        object : LinkedHashMap<String, ImageBitmap>(INITIAL_CACHE_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, ImageBitmap>?
            ): Boolean = size > MAX_MEMORY_THUMBNAILS
        }

    fun getCached(mediaId: String): ImageBitmap? {
        val cleanId = mediaId.trim()
        if (cleanId.isBlank()) return null
        return synchronized(memoryCache) { memoryCache[cleanId] }
    }

    suspend fun loadThumbnail(mediaId: String): ImageBitmap? {
        val cleanId = mediaId.trim()
        if (cleanId.isBlank()) return null
        getCached(cleanId)?.let {
            return it
        }

        return withContext(Dispatchers.IO) {
            val bytes = readDiskCachedBytes(cleanId) ?: fetchAndCacheRemoteBytes(cleanId)
            val bitmap = bytes?.let { runCatching { decoder(it) }.getOrNull() }
            if (bitmap != null) {
                synchronized(memoryCache) { memoryCache[cleanId] = bitmap }
            }
            bitmap
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

    private suspend fun fetchAndCacheRemoteBytes(mediaId: String): ByteArray? = runCatching {
        val rawBytes =
            if (isHttpThumbnailUrl(mediaId)) {
                apiClient.fetchExternalImageBytes(mediaId)
            } else {
                val currentSettings = settings.settings.first()
                val serverUrl = currentSettings.serverUrl
                val token = currentSettings.bearerToken
                if (serverUrl.isNotBlank() && token.isNotBlank()) {
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
        private const val MAX_THUMBNAIL_BYTES = 10L * 1024L * 1024L
    }
}

private fun decodeBitmapBytes(bytes: ByteArray): ImageBitmap? {
    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
    if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

    val decodeOptions =
        BitmapFactory.Options().apply {
            inSampleSize =
                calculateInSampleSize(
                    rawWidth = boundsOptions.outWidth,
                    rawHeight = boundsOptions.outHeight,
                    reqWidth = TARGET_THUMBNAIL_PX,
                    reqHeight = TARGET_THUMBNAIL_PX,
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
private const val SAMPLE_DIVISOR = 2

internal fun isHttpThumbnailUrl(value: String): Boolean =
    value.startsWith("https://", ignoreCase = true) ||
        value.startsWith("http://", ignoreCase = true)

internal fun diskCacheKey(mediaOrUrl: String): String =
    if (isHttpThumbnailUrl(mediaOrUrl)) {
        val digest =
            MessageDigest.getInstance("SHA-256").digest(mediaOrUrl.toByteArray(Charsets.UTF_8))
        "url_" + digest.joinToString("") { "%02x".format(it) }
    } else {
        mediaOrUrl
    }

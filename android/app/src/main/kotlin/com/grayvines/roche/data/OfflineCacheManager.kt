package com.grayvines.roche.data

import com.grayvines.roche.data.local.ItemEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.remote.RemoteItemDto
import com.grayvines.roche.data.remote.RemoteMediaDto
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.model.extractAllImageUrlsFromHtml
import com.grayvines.roche.model.extractFirstImageUrlFromHtml
import com.grayvines.roche.model.isNonThumbnailUrl
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal class OfflineCacheManager(
    private val dao: RocheDao,
    private val apiClient: RocheApiClient,
    private val settings: SettingsRepository,
    private val cacheDir: File? = null,
) {
    suspend fun setOfflineCacheState(itemId: String, enabled: Boolean) {
        dao.updateItemCachedOffline(itemId, enabled)
        if (!enabled) {
            evictCachedItemFiles(itemId)
        } else {
            populateOfflineCache(itemId)
        }
    }

    suspend fun hydrateItemForReader(itemId: String) {
        val existing = dao.getItemById(itemId) ?: return
        if (!existing.contentHtml.isNullOrBlank()) return
        val currentSettings = settings.settings.first()
        val serverUrl = currentSettings.serverUrl
        val token = currentSettings.bearerToken
        if (serverUrl.isBlank() || token.isBlank()) return
        runCatching {
            val detail = apiClient.fetchItemDetail(serverUrl, token, itemId)
            val latest = dao.getItemById(itemId) ?: return
            dao.upsertItem(mergeHydratedItemDetail(latest, detail))
        }
            .onFailure { if (it is CancellationException) throw it }
    }

    private suspend fun populateOfflineCache(itemId: String) {
        val currentSettings = settings.settings.first()
        val serverUrl = currentSettings.serverUrl
        val token = currentSettings.bearerToken
        if (serverUrl.isBlank() || token.isBlank()) return

        val hydrated = hydrateItemDetailBestEffort(serverUrl, token, itemId)
        val latest = dao.getItemById(itemId)
        if (latest?.isCachedOffline != true) {
            evictCachedItemFiles(itemId)
            return
        }
        val thumbId = hydrated?.thumbnailMediaId ?: latest.thumbnailMediaId
        val htmlContent = hydrated?.contentHtml ?: latest.contentHtml
        val itemUrl = (hydrated?.url ?: latest.url).takeIf { it.isNotBlank() }
        val thumbUrl =
            (hydrated?.thumbnailUrl ?: latest.thumbnailUrl)?.trim()?.takeIf {
                it.isNotBlank() && !isNonThumbnailUrl(it)
            } ?: extractFirstImageUrlFromHtml(htmlContent, baseUrl = itemUrl)
        val externalImageUrls = buildList {
            if (!thumbUrl.isNullOrBlank()) add(thumbUrl)
            addAll(extractAllImageUrlsFromHtml(htmlContent, baseUrl = itemUrl))
        }
            .distinct()
            .take(MAX_OFFLINE_EXTERNAL_IMAGES)
        cacheItemMediaBestEffort(
            serverUrl = serverUrl,
            token = token,
            itemId = itemId,
            thumbnailMediaId = thumbId,
            externalImageUrls = externalImageUrls,
        )
    }

    private suspend fun hydrateItemDetailBestEffort(
        serverUrl: String,
        token: String,
        itemId: String,
    ): ItemEntity? = runCatching {
        val detail = apiClient.fetchItemDetail(serverUrl, token, itemId)
        applyHydratedDetail(itemId, detail)
    }
        .onFailure { if (it is CancellationException) throw it }
        .getOrNull()

    private suspend fun applyHydratedDetail(
        itemId: String,
        detail: RemoteItemDto,
    ): ItemEntity? {
        val current = dao.getItemById(itemId)
        if (current == null || !current.isCachedOffline) return null
        val hydrated = mergeHydratedItemDetail(current, detail)
        dao.upsertItem(hydrated)
        val removedTags =
            parseTagCsv(dao.getPendingMutation(itemId)?.removedTagsCsv.orEmpty()).toSet()
        val allowedTags =
            detail.tagNames.filter { rawTag -> normalizeTagName(rawTag) !in removedTags }
        if (allowedTags.isNotEmpty()) {
            attachTagsLocally(dao = dao, itemId = itemId, tagNames = allowedTags)
        }
        return hydrated
    }

    private suspend fun cacheItemMediaBestEffort(
        serverUrl: String,
        token: String,
        itemId: String,
        thumbnailMediaId: String?,
        externalImageUrls: List<String> = emptyList(),
    ) {
        val rootDir = cacheDir ?: return
        val remoteMedia = runCatching {
            apiClient.fetchItemMedia(serverUrl, token, itemId)
        }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())
        val eligibleIds = collectEligibleMediaIds(remoteMedia, thumbnailMediaId)
        val thumbnailIds =
            remoteMedia
                .filter { it.mediaType.equals("thumbnail", ignoreCase = true) }
                .map { it.id }
                .toMutableSet()
                .apply { thumbnailMediaId?.takeIf { it.isNotBlank() }?.let { add(it) } }

        for (mediaId in eligibleIds) {
            if (
                !downloadAndCacheSingleMedia(
                    rootDir = rootDir,
                    serverUrl = serverUrl,
                    token = token,
                    itemId = itemId,
                    mediaId = mediaId,
                    isThumbnail = mediaId in thumbnailIds,
                )
            ) {
                return
            }
        }
        for (imageUrl in externalImageUrls) {
            cacheExternalThumbBestEffort(
                rootDir = rootDir,
                itemId = itemId,
                thumbnailUrl = imageUrl,
            )
        }
    }

    private suspend fun cacheExternalThumbBestEffort(
        rootDir: File,
        itemId: String,
        thumbnailUrl: String,
    ) {
        if (dao.getItemById(itemId)?.isCachedOffline != true) return
        val bytes = apiClient.fetchExternalImageBytes(thumbnailUrl) ?: return
        if (bytes.isEmpty() || bytes.size > MAX_OFFLINE_THUMBNAIL_BYTES) return
        if (dao.getItemById(itemId)?.isCachedOffline != true) return
        withContext(Dispatchers.IO) {
            runCatching {
                val thumbsDir = File(rootDir, THUMBNAILS_SUBDIR)
                writeBytesAtomically(thumbsDir, diskCacheKey(thumbnailUrl), bytes)
            }
        }
    }

    @Suppress("LongParameterList")
    private suspend fun downloadAndCacheSingleMedia(
        rootDir: File,
        serverUrl: String,
        token: String,
        itemId: String,
        mediaId: String,
        isThumbnail: Boolean,
    ): Boolean {
        if (dao.getItemById(itemId)?.isCachedOffline != true) {
            evictCachedItemFiles(itemId)
            return false
        }
        val bytes = apiClient.fetchMediaBytes(serverUrl, token, mediaId)
        if (dao.getItemById(itemId)?.isCachedOffline != true) {
            evictCachedItemFiles(itemId)
            return false
        }
        if (bytes != null && bytes.isNotEmpty() && bytes.size <= MAX_OFFLINE_MEDIA_BYTES) {
            writeCachedMediaFile(
                rootDir = rootDir,
                itemId = itemId,
                mediaId = mediaId,
                bytes = bytes,
                isThumbnail = isThumbnail,
            )
            if (dao.getItemById(itemId)?.isCachedOffline != true) {
                evictCachedItemFiles(itemId)
                return false
            }
        }
        return true
    }

    private suspend fun writeCachedMediaFile(
        rootDir: File,
        itemId: String,
        mediaId: String,
        bytes: ByteArray,
        isThumbnail: Boolean,
    ) =
        withContext(Dispatchers.IO) {
            runCatching {
                val itemsRoot = File(rootDir, ITEMS_SUBDIR)
                itemsRoot.mkdirs()
                val itemDir = resolveSafeChildFile(itemsRoot, itemId)
                writeBytesAtomically(itemDir, mediaId, bytes)
                if (isThumbnail) {
                    val thumbsDir = File(rootDir, THUMBNAILS_SUBDIR)
                    writeBytesAtomically(thumbsDir, mediaId, bytes)
                }
            }
        }

    internal suspend fun evictCachedItemFiles(itemId: String) {
        val rootDir = cacheDir ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val itemsRoot = File(rootDir, ITEMS_SUBDIR)
                if (itemsRoot.exists()) {
                    resolveSafeChildFile(itemsRoot, itemId).deleteRecursively()
                }
            }
        }
    }
}

internal fun mergeHydratedItemDetail(local: ItemEntity, detail: RemoteItemDto): ItemEntity =
    local.copy(
        title = detail.title.takeIf { it.isNotBlank() } ?: local.title,
        author = detail.author?.takeIf { it.isNotBlank() } ?: local.author,
        summary =
            (detail.summary?.takeIf { it.isNotBlank() } ?: local.summary)?.take(
                MAX_SQLITE_SUMMARY_CHARS
            ),
        contentText =
            (detail.contentText?.takeIf { it.isNotBlank() } ?: local.contentText)?.take(
                MAX_SQLITE_TEXT_CHARS
            ),
        contentHtml =
            (detail.contentHtml?.takeIf { it.isNotBlank() } ?: local.contentHtml)?.take(
                MAX_SQLITE_HTML_CHARS
            ),
        thumbnailMediaId =
            detail.thumbnailMediaId?.takeIf { it.isNotBlank() } ?: local.thumbnailMediaId,
        thumbnailUrl = detail.thumbnailUrl?.takeIf { it.isNotBlank() } ?: local.thumbnailUrl,
        duration = detail.duration?.takeIf { it.isNotBlank() } ?: local.duration,
        status = local.status,
        isFavorite = local.isFavorite,
        readProgress = local.readProgress,
        isCachedOffline = local.isCachedOffline,
    )

internal fun collectEligibleMediaIds(
    remoteMedia: List<RemoteMediaDto>,
    thumbnailMediaId: String?,
): List<String> {
    val result = LinkedHashSet<String>()
    remoteMedia.forEach { media ->
        val sizeOk = media.fileSize == null || media.fileSize in 1..MAX_OFFLINE_MEDIA_BYTES
        if (media.id.isNotBlank() && sizeOk) {
            result.add(media.id)
        }
    }
    thumbnailMediaId?.trim()?.takeIf { it.isNotBlank() }?.let { result.add(it) }
    return result.toList()
}

internal fun sanitizeCacheFileName(raw: String): String {
    val cleaned = raw.trim().replace(UNSAFE_FILENAME_REGEX, "_").trim('_').trim('.')
    require(cleaned.isNotBlank() && cleaned != "." && cleaned != "..") {
        "Invalid cache file name"
    }
    return cleaned
}

internal fun resolveSafeChildFile(parentDir: File, childName: String): File {
    val safeName = sanitizeCacheFileName(childName)
    val parentCanonical = parentDir.canonicalFile
    val childCanonical = File(parentCanonical, safeName).canonicalFile
    check(
        childCanonical.parentFile == parentCanonical &&
            childCanonical.toPath().startsWith(parentCanonical.toPath())
    ) {
        "Resolved path escapes cache directory"
    }
    return childCanonical
}

internal fun writeBytesAtomically(parentDir: File, fileName: String, bytes: ByteArray) {
    parentDir.mkdirs()
    val target = resolveSafeChildFile(parentDir, fileName)
    val tempFile = File(target.parentFile, "${target.name}.${ System.nanoTime() }.tmp")
    try {
        tempFile.writeBytes(bytes)
        if (!tempFile.renameTo(target)) {
            tempFile.copyTo(target, overwrite = true)
        }
    } finally {
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }
}

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

internal const val ITEMS_SUBDIR = "items"
internal const val THUMBNAILS_SUBDIR = "thumbnails"
private const val MAX_OFFLINE_MEDIA_BYTES = 50L * 1024L * 1024L
private const val MAX_OFFLINE_THUMBNAIL_BYTES = 10L * 1024L * 1024L
private const val MAX_OFFLINE_EXTERNAL_IMAGES = 16
private val UNSAFE_FILENAME_REGEX = Regex("""[^a-zA-Z0-9_-]""")

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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal class OfflineCacheManager(
    private val dao: RocheDao,
    private val apiClient: RocheApiClient,
    private val settings: SettingsRepository,
    private val cacheDir: File? = null,
) {
    private val activeCacheTokens = ConcurrentHashMap<String, Long>()
    private val tokenSequence = AtomicLong(0L)

    internal suspend fun setOfflineCacheState(itemId: String, enabled: Boolean): Boolean {
        if (!enabled) {
            activeCacheTokens.remove(itemId)
            dao.updateItemCachedOffline(itemId, false)
            evictCachedItemFiles(itemId)
            return false
        }
        val attemptToken = tokenSequence.incrementAndGet()
        activeCacheTokens[itemId] = attemptToken
        return try {
            populateOfflineCache(itemId = itemId, attemptToken = attemptToken)
        } finally {
            activeCacheTokens.remove(itemId, attemptToken)
        }
    }

    internal suspend fun hydrateItemForReader(itemId: String) {
        val existing = dao.getItemById(itemId) ?: return
        if (!existing.contentHtml.isNullOrBlank()) return
        val currentSettings = settings.settings.first()
        val serverUrl = currentSettings.serverUrl
        val token = currentSettings.bearerToken
        if (serverUrl.isBlank() || token.isBlank()) return
        runSuspendCatching {
            val detail = apiClient.fetchItemDetail(serverUrl, token, itemId)
            val latest = dao.getItemById(itemId) ?: return
            dao.upsertItem(mergeHydratedItemDetail(latest, detail))
        }
    }

    private fun isCacheAttemptActive(itemId: String, attemptToken: Long): Boolean =
        activeCacheTokens[itemId] == attemptToken

    private suspend fun populateOfflineCache(itemId: String, attemptToken: Long): Boolean {
        if (dao.getItemById(itemId) == null) return false
        val currentSettings = settings.settings.first()
        val serverUrl = currentSettings.serverUrl
        val token = currentSettings.bearerToken
        val hydrated =
            if (serverUrl.isNotBlank() && token.isNotBlank()) {
                hydrateItemDetailBestEffort(serverUrl, token, itemId, attemptToken)
            } else {
                null
            }
        val latest = dao.getItemById(itemId)
        if (!isCacheAttemptActive(itemId, attemptToken) || latest == null) {
            return abortOfflineCacheAttempt(itemId)
        }
        val audioUrl = resolveOfflineAudioUrl(hydrated, latest)
        val audioEnclosureCached =
            !audioUrl.isNullOrBlank() && cacheAudioEnclosure(itemId, audioUrl, attemptToken)
        val cachedPrimaryMediaCount =
            cacheItemMediaBestEffort(
                serverUrl = serverUrl,
                token = token,
                itemId = itemId,
                thumbnailMediaId = hydrated?.thumbnailMediaId ?: latest.thumbnailMediaId,
                externalImageUrls = collectExternalImageUrls(hydrated, latest),
                attemptToken = attemptToken,
            )
        val mediaReady =
            isRequiredMediaCached(
                contentType = (hydrated?.contentType ?: latest.contentType).lowercase(),
                hasAudioUrl = !audioUrl.isNullOrBlank(),
                audioEnclosureCached = audioEnclosureCached,
                cachedPrimaryMediaCount = cachedPrimaryMediaCount,
            )
        return finalizeOfflineCacheAttempt(itemId, attemptToken, mediaReady)
    }

    private suspend fun finalizeOfflineCacheAttempt(
        itemId: String,
        attemptToken: Long,
        mediaReady: Boolean,
    ): Boolean {
        val committed =
            mediaReady &&
                isCacheAttemptActive(itemId, attemptToken) &&
                dao.getItemById(itemId) != null
        return if (committed) {
            dao.updateItemCachedOffline(itemId, true)
            true
        } else {
            abortOfflineCacheAttempt(itemId)
        }
    }

    private suspend fun abortOfflineCacheAttempt(itemId: String): Boolean {
        dao.updateItemCachedOffline(itemId, false)
        evictCachedItemFiles(itemId)
        return false
    }

    internal suspend fun autoDownloadNewEpisodes() {
        val autoDownloadFeedIds =
            dao.getAllFeeds()
                .filter { it.isActive && it.autoDownloadEpisodes }
                .map { it.id }
                .toSet()
        if (autoDownloadFeedIds.isEmpty()) return
        val candidates =
            dao.getAllItems().filter { item ->
                item.feedId in autoDownloadFeedIds &&
                    item.status != "archived" &&
                    item.readAt.isNullOrBlank() &&
                    !item.isCachedOffline &&
                    (item.contentType.equals(CONTENT_TYPE_AUDIO, ignoreCase = true) ||
                        !item.audioUrl.isNullOrBlank())
            }
        for (item in candidates) {
            setOfflineCacheState(itemId = item.id, enabled = true)
        }
    }

    private suspend fun cacheAudioEnclosure(
        itemId: String,
        audioUrl: String,
        attemptToken: Long,
    ): Boolean {
        val targetFile =
            cacheDir
                ?.takeIf { isCacheAttemptActive(itemId, attemptToken) }
                ?.let { resolveTargetItemMediaFile(it, itemId, AUDIO_ENCLOSURE_FILE_NAME) }
                ?: return false
        val streamed = apiClient.streamExternalBinaryToFile(audioUrl, targetFile)
        val valid =
            streamed &&
                isCacheAttemptActive(itemId, attemptToken) &&
                withContext(Dispatchers.IO) { targetFile.isFile && targetFile.length() > 0L }
        if (!valid && !isCacheAttemptActive(itemId, attemptToken)) {
            evictCachedItemFiles(itemId)
        }
        return valid
    }

    private suspend fun hydrateItemDetailBestEffort(
        serverUrl: String,
        token: String,
        itemId: String,
        attemptToken: Long,
    ): ItemEntity? = runSuspendCatching {
        if (!isCacheAttemptActive(itemId, attemptToken)) return null
        val detail = apiClient.fetchItemDetail(serverUrl, token, itemId)
        if (!isCacheAttemptActive(itemId, attemptToken)) return null
        applyHydratedDetail(itemId, detail)
    }
        .getOrNull()

    private suspend fun applyHydratedDetail(
        itemId: String,
        detail: RemoteItemDto,
    ): ItemEntity? {
        val current = dao.getItemById(itemId) ?: return null
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

    @Suppress("LongParameterList")
    private suspend fun cacheItemMediaBestEffort(
        serverUrl: String,
        token: String,
        itemId: String,
        thumbnailMediaId: String?,
        externalImageUrls: List<String> = emptyList(),
        attemptToken: Long,
    ): Int {
        val rootDir = cacheDir ?: return 0
        val hasServerAuth = serverUrl.isNotBlank() && token.isNotBlank()
        val remoteMedia =
            if (hasServerAuth) {
                runSuspendCatching { apiClient.fetchItemMedia(serverUrl, token, itemId) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
        val eligibleIds =
            if (hasServerAuth) {
                collectEligibleMediaIds(remoteMedia, thumbnailMediaId)
            } else {
                emptyList()
            }
        val thumbnailIds =
            remoteMedia
                .filter { it.mediaType.equals("thumbnail", ignoreCase = true) }
                .map { it.id }
                .toMutableSet()
                .apply { thumbnailMediaId?.takeIf { it.isNotBlank() }?.let { add(it) } }

        var cachedPrimaryCount = 0
        for (mediaId in eligibleIds) {
            if (!isCacheAttemptActive(itemId, attemptToken)) break
            val isThumbnail = mediaId in thumbnailIds
            val cached =
                downloadAndCacheSingleMedia(
                    rootDir = rootDir,
                    serverUrl = serverUrl,
                    token = token,
                    itemId = itemId,
                    mediaId = mediaId,
                    isThumbnail = isThumbnail,
                    attemptToken = attemptToken,
                )
            if (cached && !isThumbnail) {
                cachedPrimaryCount++
            }
        }
        for (imageUrl in externalImageUrls) {
            cacheExternalThumbBestEffort(
                rootDir = rootDir,
                itemId = itemId,
                thumbnailUrl = imageUrl,
                attemptToken = attemptToken,
            )
        }
        return if (isCacheAttemptActive(itemId, attemptToken)) cachedPrimaryCount else 0
    }

    private suspend fun cacheExternalThumbBestEffort(
        rootDir: File,
        itemId: String,
        thumbnailUrl: String,
        attemptToken: Long,
    ) {
        if (!isCacheAttemptActive(itemId, attemptToken)) return
        val bytes =
            apiClient.fetchExternalImageBytes(thumbnailUrl)?.takeIf {
                it.isNotEmpty() && it.size <= MAX_OFFLINE_THUMBNAIL_BYTES
            } ?: return
        if (isCacheAttemptActive(itemId, attemptToken)) {
            withContext(Dispatchers.IO) {
                runSuspendCatching {
                    val thumbsDir = File(rootDir, THUMBNAILS_SUBDIR)
                    writeBytesAtomically(thumbsDir, diskCacheKey(thumbnailUrl), bytes)
                }
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
        attemptToken: Long,
    ): Boolean {
        val targetFile =
            if (isCacheAttemptActive(itemId, attemptToken)) {
                resolveTargetItemMediaFile(rootDir, itemId, mediaId)
            } else {
                evictCachedItemFiles(itemId)
                null
            } ?: return false
        val streamed =
            apiClient.streamMediaToFile(
                serverUrl = serverUrl,
                token = token,
                mediaId = mediaId,
                targetFile = targetFile,
                maxBytes = MAX_OFFLINE_MEDIA_BYTES,
            )
        val stillActive = isCacheAttemptActive(itemId, attemptToken)
        if (!stillActive) {
            evictCachedItemFiles(itemId)
        } else if (streamed && isThumbnail) {
            copyCachedThumbnailBestEffort(rootDir, targetFile, mediaId)
        }
        return streamed && stillActive
    }

    internal suspend fun evictCachedItemFiles(itemId: String) {
        val rootDir = cacheDir ?: return
        withContext(Dispatchers.IO) {
            runSuspendCatching {
                val itemsRoot = File(rootDir, ITEMS_SUBDIR)
                if (itemsRoot.exists()) {
                    resolveSafeChildFile(itemsRoot, itemId).deleteRecursively()
                }
            }
        }
    }
}

internal fun resolveOfflineAudioUrl(hydrated: ItemEntity?, latest: ItemEntity): String? =
    (hydrated?.audioUrl ?: latest.audioUrl)?.trim()?.takeIf { it.isNotBlank() }
        ?: latest.url.takeIf {
            latest.contentType.equals(CONTENT_TYPE_AUDIO, ignoreCase = true) ||
                latest.contentType.equals("podcast", ignoreCase = true)
        }

internal fun collectExternalImageUrls(hydrated: ItemEntity?, latest: ItemEntity): List<String> {
    val htmlContent = hydrated?.contentHtml ?: latest.contentHtml
    val itemUrl = (hydrated?.url ?: latest.url).takeIf { it.isNotBlank() }
    val thumbUrl =
        (hydrated?.thumbnailUrl ?: latest.thumbnailUrl)?.trim()?.takeIf {
            it.isNotBlank() && !isNonThumbnailUrl(it)
        } ?: extractFirstImageUrlFromHtml(htmlContent, baseUrl = itemUrl)
    return buildList {
            if (!thumbUrl.isNullOrBlank()) add(thumbUrl)
            addAll(extractAllImageUrlsFromHtml(htmlContent, baseUrl = itemUrl))
        }
        .distinct()
        .take(MAX_OFFLINE_EXTERNAL_IMAGES)
}

internal fun isRequiredMediaCached(
    contentType: String,
    hasAudioUrl: Boolean,
    audioEnclosureCached: Boolean,
    cachedPrimaryMediaCount: Int,
): Boolean {
    val requiresAudio = hasAudioUrl || contentType == CONTENT_TYPE_AUDIO || contentType == "podcast"
    val requiresDocumentOrVideo =
        !requiresAudio && (contentType == "pdf" || contentType == "paper" || contentType == "video")
    return when {
        requiresAudio -> audioEnclosureCached || cachedPrimaryMediaCount > 0
        requiresDocumentOrVideo -> cachedPrimaryMediaCount > 0
        else -> true
    }
}

internal suspend fun resolveTargetItemMediaFile(
    rootDir: File,
    itemId: String,
    fileName: String,
): File? =
    withContext(Dispatchers.IO) {
        runSuspendCatching {
            val itemsRoot = File(rootDir, ITEMS_SUBDIR)
            itemsRoot.mkdirs()
            val itemDir = resolveSafeChildFile(itemsRoot, itemId)
            itemDir.mkdirs()
            resolveSafeChildFile(itemDir, fileName)
        }
            .getOrNull()
    }

internal suspend fun copyCachedThumbnailBestEffort(
    rootDir: File,
    sourceFile: File,
    mediaId: String,
) {
    withContext(Dispatchers.IO) {
        runSuspendCatching {
            val thumbsDir = File(rootDir, THUMBNAILS_SUBDIR)
            thumbsDir.mkdirs()
            val target = resolveSafeChildFile(thumbsDir, mediaId)
            val tempFile = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
            try {
                sourceFile.copyTo(tempFile, overwrite = true)
                if (!tempFile.renameTo(target)) {
                    tempFile.copyTo(target, overwrite = true)
                }
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
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
            (detail.contentText?.takeIf { it.isNotBlank() }
                    ?: local.contentText?.takeIf { it.isNotBlank() && it != local.summary })
                ?.take(MAX_SQLITE_TEXT_CHARS),
        contentHtml =
            (detail.contentHtml?.takeIf { it.isNotBlank() } ?: local.contentHtml)?.take(
                MAX_SQLITE_HTML_CHARS
            ),
        thumbnailMediaId =
            detail.thumbnailMediaId?.takeIf { it.isNotBlank() } ?: local.thumbnailMediaId,
        thumbnailUrl = detail.thumbnailUrl?.takeIf { it.isNotBlank() } ?: local.thumbnailUrl,
        duration = detail.duration?.takeIf { it.isNotBlank() } ?: local.duration,
        audioUrl = detail.audioUrl?.takeIf { it.isNotBlank() } ?: local.audioUrl,
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
    val tempFile = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
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

internal fun resolveCachedAudioFile(cacheDir: File?, itemId: String): File? {
    val rootDir = cacheDir ?: return null
    return runCatching {
        val itemsRoot = File(rootDir, ITEMS_SUBDIR)
        if (!itemsRoot.exists()) return null
        val itemDir = resolveSafeChildFile(itemsRoot, itemId)
        val audioFile = resolveSafeChildFile(itemDir, AUDIO_ENCLOSURE_FILE_NAME)
        audioFile.takeIf { it.isFile && it.length() > 0L }
    }
        .getOrNull()
}

internal const val ITEMS_SUBDIR = "items"
internal const val THUMBNAILS_SUBDIR = "thumbnails"
internal const val AUDIO_ENCLOSURE_FILE_NAME = "audio_enclosure"
private const val CONTENT_TYPE_AUDIO = "audio"
private const val MAX_OFFLINE_MEDIA_BYTES = 50L * 1024L * 1024L
private const val MAX_OFFLINE_THUMBNAIL_BYTES = 10L * 1024L * 1024L
private const val MAX_OFFLINE_EXTERNAL_IMAGES = 16
private val UNSAFE_FILENAME_REGEX = Regex("""[^a-zA-Z0-9_-]""")

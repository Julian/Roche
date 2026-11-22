package com.grayvines.roche.media

import com.grayvines.roche.core.RocheLog
import com.grayvines.roche.core.TOMBSTONE_ENTITY_COLLECTION_ENTRY
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.core.collectionEntryNaturalKey
import com.grayvines.roche.core.deterministicTagId
import com.grayvines.roche.core.isDirectAudioUrl
import com.grayvines.roche.core.isHttpOrHttpsUrl
import com.grayvines.roche.core.isLocalPdfUrl
import com.grayvines.roche.core.normalizeTagName
import com.grayvines.roche.core.resolveSafeChildFile
import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.data.local.DbWriteLock
import com.grayvines.roche.data.local.InboxItemRow
import com.grayvines.roche.data.local.MAX_SQLITE_HTML_CHARS
import com.grayvines.roche.data.local.MAX_SQLITE_SUMMARY_CHARS
import com.grayvines.roche.data.local.MAX_SQLITE_TEXT_CHARS
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.attachTagsLocally
import com.grayvines.roche.data.local.upsertItem
import com.grayvines.roche.data.remote.PublicWebFetcher
import com.grayvines.roche.data.remote.RemoteItemDto
import com.grayvines.roche.data.remote.RemoteMediaDto
import com.grayvines.roche.data.remote.RocheServerApi
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.model.extractAllImageUrlsFromHtml
import com.grayvines.roche.model.extractFirstImageUrlFromHtml
import com.grayvines.roche.model.isNonThumbnailUrl
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Suppress("LongParameterList", "TooManyFunctions")
internal class OfflineCacheManager(
    private val dao: RocheDao,
    private val serverApi: RocheServerApi,
    private val publicWebFetcher: PublicWebFetcher = PublicWebFetcher(),
    private val settings: SettingsRepository,
    private val cacheDir: File? = null,
    private val thumbnailDiskCache: ThumbnailDiskCache = ThumbnailDiskCache(cacheDir),
    private val maxOfflineCacheBytes: Long = DEFAULT_MAX_OFFLINE_CACHE_BYTES,
    private val writeLock: DbWriteLock = DbWriteLock(),
) {
    private val activeCacheTokens = ConcurrentHashMap<String, Long>()
    private val tokenSequence = AtomicLong(0L)
    private val quotaMutex = Mutex()
    private val _inFlightEntryIds = MutableStateFlow<Set<String>>(emptySet())
    internal val inFlightEntryIds: StateFlow<Set<String>> = _inFlightEntryIds.asStateFlow()

    private fun publishInFlightIds() {
        _inFlightEntryIds.value = activeCacheTokens.keys.toSet()
    }

    internal suspend fun setOfflineCacheState(itemId: String, enabled: Boolean): Boolean {
        if (!enabled) {
            activeCacheTokens.remove(itemId)
            publishInFlightIds()
            writeLock.withLock { dao.updateItemCachedOffline(itemId, false) }
            evictCachedItemFiles(itemId)
            return false
        }
        val attemptToken = tokenSequence.incrementAndGet()
        activeCacheTokens[itemId] = attemptToken
        publishInFlightIds()
        return try {
            populateOfflineCache(itemId = itemId, attemptToken = attemptToken)
        } finally {
            activeCacheTokens.remove(itemId, attemptToken)
            publishInFlightIds()
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
            val detail = serverApi.fetchItemDetail(serverUrl, token, itemId)
            writeLock.withLock {
                dao.executeInTransaction {
                    val latest = dao.getItemById(itemId)
                    if (latest != null) {
                        dao.upsertItem(mergeHydratedItemDetail(latest, detail), markDirty = false)
                    }
                }
            }
        }
            .onFailure { err ->
                RocheLog.warn(LOG_TAG, "Failed to hydrate reader item $itemId", err)
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
            if (mediaReady && isCacheAttemptActive(itemId, attemptToken)) {
                writeLock.withLock {
                    if (
                        isCacheAttemptActive(itemId, attemptToken) &&
                            dao.getItemById(itemId) != null
                    ) {
                        dao.updateItemCachedOffline(itemId, true)
                        true
                    } else {
                        false
                    }
                }
            } else {
                false
            }
        return if (committed) {
            enforceOfflineDiskQuota(protectedItemId = itemId)
            true
        } else {
            abortOfflineCacheAttempt(itemId)
        }
    }

    private suspend fun abortOfflineCacheAttempt(itemId: String): Boolean {
        writeLock.withLock { dao.updateItemCachedOffline(itemId, false) }
        evictCachedItemFiles(itemId)
        return false
    }

    internal suspend fun autoDownloadNewEpisodes() {
        val autoDownloadFeedIds =
            dao.getAllFeeds().filter { it.isActive && it.autoDownloadEpisodes }.map { it.id }
        if (autoDownloadFeedIds.isEmpty()) return
        val candidateIds =
            autoDownloadFeedIds.chunked(AUTO_DOWNLOAD_FEED_CHUNK_SIZE).flatMap { chunk ->
                dao.getUncachedUnreadAudioItemIds(chunk)
            }
        for (itemId in candidateIds) {
            setOfflineCacheState(itemId = itemId, enabled = true)
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
        val streamed = publicWebFetcher.streamBinaryToFile(audioUrl, targetFile)
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
    ): InboxItemRow? = runSuspendCatching {
        if (!isCacheAttemptActive(itemId, attemptToken)) return null
        val detail = serverApi.fetchItemDetail(serverUrl, token, itemId)
        if (!isCacheAttemptActive(itemId, attemptToken)) return null
        applyHydratedDetail(itemId, detail)
    }
        .getOrNull()

    private suspend fun applyHydratedDetail(
        itemId: String,
        detail: RemoteItemDto,
    ): InboxItemRow? = writeLock.withLock {
        var result: InboxItemRow? = null
        dao.executeInTransaction {
            val current = dao.getItemById(itemId)
            if (current != null) {
                val hydrated = mergeHydratedItemDetail(current, detail)
                dao.upsertItem(hydrated, markDirty = false)
                val tombstones =
                    dao.getAllTombstones()
                        .filter {
                            it.entityType.equals(
                                TOMBSTONE_ENTITY_COLLECTION_ENTRY,
                                ignoreCase = true,
                            )
                        }
                        .mapTo(HashSet()) { it.naturalKey }
                val allowedTags =
                    detail.tagNames.filter { rawTag ->
                        val tagId = deterministicTagId(normalizeTagName(rawTag))
                        collectionEntryNaturalKey(tagId, itemId) !in tombstones
                    }
                if (allowedTags.isNotEmpty()) {
                    attachTagsLocally(
                        dao = dao,
                        itemId = itemId,
                        tagNames = allowedTags,
                        markDirty = false,
                    )
                }
                result = hydrated
            }
        }
        result
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
                runSuspendCatching { serverApi.fetchItemMedia(serverUrl, token, itemId) }
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
                itemId = itemId,
                thumbnailUrl = imageUrl,
                attemptToken = attemptToken,
            )
        }
        return if (isCacheAttemptActive(itemId, attemptToken)) cachedPrimaryCount else 0
    }

    private suspend fun cacheExternalThumbBestEffort(
        itemId: String,
        thumbnailUrl: String,
        attemptToken: Long,
    ) {
        if (!isCacheAttemptActive(itemId, attemptToken)) return
        val fetched =
            publicWebFetcher.fetchImageBytes(
                imageUrl = thumbnailUrl,
                maxBytes = MAX_OFFLINE_THUMBNAIL_BYTES.toInt(),
            )
        val bytes = fetched?.takeIf { it.isNotEmpty() && it.size <= MAX_OFFLINE_THUMBNAIL_BYTES }
        if (bytes == null) return
        if (isCacheAttemptActive(itemId, attemptToken)) {
            runSuspendCatching { thumbnailDiskCache.writeBytes(thumbnailUrl, bytes) }
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
            serverApi.streamMediaToFile(
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
            runSuspendCatching { thumbnailDiskCache.copyFromFile(mediaId, targetFile) }
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
                .onFailure { err ->
                    RocheLog.warn(LOG_TAG, "Failed to evict offline files for $itemId", err)
                }
        }
    }

    internal suspend fun cachedBytesByEntryId(): Map<String, Long> {
        val rootDir = cacheDir ?: return emptyMap()
        return withContext(Dispatchers.IO) {
            scanCachedItemDirectories(File(rootDir, ITEMS_SUBDIR)).associate {
                it.itemId to it.sizeBytes
            }
        }
    }

    internal suspend fun totalOfflineCacheBytes(): Long = cachedBytesByEntryId().values.sum()

    internal suspend fun enforceOfflineDiskQuota(protectedItemId: String? = null) {
        val rootDir = cacheDir ?: return
        if (maxOfflineCacheBytes <= 0L) return
        quotaMutex.withLock {
            val itemsRoot = File(rootDir, ITEMS_SUBDIR)
            val entries = withContext(Dispatchers.IO) { scanCachedItemDirectories(itemsRoot) }
            val totalBytes = entries.sumOf { it.sizeBytes }
            if (totalBytes > maxOfflineCacheBytes) {
                val archivedIds = loadArchivedEntryIds()
                val localPdfIds = loadLocalPdfEntryIds()
                val protectedIds =
                    activeCacheTokens.keys + localPdfIds + listOfNotNull(protectedItemId)
                val candidates = sortQuotaEvictionCandidates(entries, protectedIds, archivedIds)
                evictCandidatesUntilUnderQuota(candidates, totalBytes)
            }
        }
    }

    private suspend fun loadLocalPdfEntryIds(): Set<String> =
        dao.getAllEntries().filter { isLocalPdfUrl(it.url) }.mapTo(HashSet()) { it.id }

    private suspend fun loadArchivedEntryIds(): Set<String> {
        val wantIds =
            dao.getCollectionMemberships(WANT_TO_READ_COLLECTION_ID).mapTo(HashSet()) { it.entryId }
        return dao.getAllEntryStates()
            .filter { state ->
                state.inboxState.equals("dismissed", ignoreCase = true) ||
                    state.inboxState.equals("none", ignoreCase = true) &&
                        !state.readAt.isNullOrBlank() &&
                        state.entryId !in wantIds
            }
            .mapTo(HashSet()) { it.entryId }
    }

    private suspend fun evictCandidatesUntilUnderQuota(
        candidates: List<CachedItemDirInfo>,
        initialBytes: Long,
    ) {
        var remainingBytes = initialBytes
        for (candidate in candidates) {
            if (remainingBytes <= maxOfflineCacheBytes) break
            if (
                !activeCacheTokens.containsKey(candidate.itemId) &&
                    evictSingleQuotaCandidate(candidate)
            ) {
                remainingBytes = (remainingBytes - candidate.sizeBytes).coerceAtLeast(0L)
            }
        }
    }

    private suspend fun evictSingleQuotaCandidate(candidate: CachedItemDirInfo): Boolean =
        writeLock.withLock {
            if (activeCacheTokens.containsKey(candidate.itemId)) {
                false
            } else {
                val deleted =
                    withContext(Dispatchers.IO) {
                        runCatching { candidate.dir.deleteRecursively() }.getOrDefault(false)
                    }
                if (deleted) {
                    dao.updateItemCachedOffline(candidate.itemId, false)
                }
                deleted
            }
        }
}

internal data class CachedItemDirInfo(
    val itemId: String,
    val dir: File,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)

internal fun sortQuotaEvictionCandidates(
    entries: List<CachedItemDirInfo>,
    protectedIds: Set<String>,
    archivedIds: Set<String>,
): List<CachedItemDirInfo> =
    entries
        .filter { it.itemId !in protectedIds }
        .sortedWith(
            compareBy<CachedItemDirInfo> { if (it.itemId in archivedIds) 0 else 1 }
                .thenBy { it.lastModifiedMs }
                .thenBy { it.itemId }
        )

internal fun scanCachedItemDirectories(itemsRoot: File): List<CachedItemDirInfo> {
    if (!itemsRoot.isDirectory) return emptyList()
    return itemsRoot.listFiles().orEmpty().mapNotNull(::measureCachedItemDirectory)
}

private fun measureCachedItemDirectory(child: File): CachedItemDirInfo? {
    if (!child.isDirectory) return null
    var total = 0L
    var latestMod = child.lastModified()
    for (file in child.walkTopDown()) {
        if (file.isFile) {
            total += file.length()
            latestMod = maxOf(latestMod, file.lastModified())
        }
    }
    return if (total > 0L) {
        CachedItemDirInfo(
            itemId = child.name,
            dir = child,
            sizeBytes = total,
            lastModifiedMs = latestMod,
        )
    } else {
        null
    }
}

internal fun resolveOfflineAudioUrl(hydrated: InboxItemRow?, latest: InboxItemRow): String? =
    (hydrated?.audioUrl ?: latest.audioUrl)?.trim()?.takeIf { it.isNotBlank() }
        ?: latest.url.takeIf {
            (latest.contentType.equals(CONTENT_TYPE_AUDIO, ignoreCase = true) ||
                latest.contentType.equals(CONTENT_TYPE_PODCAST, ignoreCase = true)) &&
                isDirectAudioUrl(it)
        }

internal fun collectExternalImageUrls(hydrated: InboxItemRow?, latest: InboxItemRow): List<String> {
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
    val requiresAudio =
        hasAudioUrl || contentType == CONTENT_TYPE_AUDIO || contentType == CONTENT_TYPE_PODCAST
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

private fun String?.nonBlankOr(fallback: String?): String? =
    this?.takeIf { it.isNotBlank() } ?: fallback

internal fun mergeHydratedItemDetail(local: InboxItemRow, detail: RemoteItemDto): InboxItemRow {
    val authoritativeDetail = detail.id.isNotBlank() && detail.id == local.id
    val mergedContentType =
        if (authoritativeDetail) detail.contentType.ifBlank { local.contentType } else local.contentType
    val isAudio =
        mergedContentType.equals(CONTENT_TYPE_AUDIO, ignoreCase = true) ||
            mergedContentType.equals(CONTENT_TYPE_PODCAST, ignoreCase = true)
    val wasAudio =
        local.contentType.equals(CONTENT_TYPE_AUDIO, ignoreCase = true) ||
            local.contentType.equals(CONTENT_TYPE_PODCAST, ignoreCase = true)
    val mergedAudioUrl =
        detail.audioUrl?.trim()?.takeIf { it.isNotEmpty() } ?: local.audioUrl.takeIf { isAudio }
    val mergedDuration =
        detail.duration.nonBlankOr(if (wasAudio && !isAudio) null else local.duration)
    return local.copy(
        title = detail.title.ifBlank { local.title },
        author = detail.author.nonBlankOr(local.author),
        summary = detail.summary.nonBlankOr(local.summary)?.take(MAX_SQLITE_SUMMARY_CHARS),
        contentText =
            detail.contentText
                .nonBlankOr(local.contentText?.takeIf { it.isNotBlank() && it != local.summary })
                ?.take(MAX_SQLITE_TEXT_CHARS),
        contentHtml = detail.contentHtml.nonBlankOr(local.contentHtml)?.take(MAX_SQLITE_HTML_CHARS),
        contentType = mergedContentType,
        thumbnailMediaId = detail.thumbnailMediaId.nonBlankOr(local.thumbnailMediaId),
        duration = mergedDuration,
        thumbnailUrl = detail.thumbnailUrl.nonBlankOr(local.thumbnailUrl),
        audioUrl = mergedAudioUrl,
    )
}

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

internal fun diskCacheKey(mediaOrUrl: String): String =
    if (isHttpOrHttpsUrl(mediaOrUrl)) {
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

internal const val THUMBNAILS_SUBDIR = "thumbnails"
internal const val AUDIO_ENCLOSURE_FILE_NAME = "audio_enclosure"
private const val CONTENT_TYPE_AUDIO = "audio"
private const val CONTENT_TYPE_PODCAST = "podcast"
private const val AUTO_DOWNLOAD_FEED_CHUNK_SIZE = 500
private const val MAX_OFFLINE_MEDIA_BYTES = 50L * 1024L * 1024L
private const val MAX_OFFLINE_THUMBNAIL_BYTES = 10L * 1024L * 1024L
private const val MAX_OFFLINE_EXTERNAL_IMAGES = 16
internal const val DEFAULT_MAX_OFFLINE_CACHE_BYTES = 512L * 1024L * 1024L
private const val LOG_TAG = "OfflineCacheManager"

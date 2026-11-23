package com.grayvines.roche.data

import com.grayvines.roche.core.DEFAULT_LOCAL_HLC_NODE
import com.grayvines.roche.core.ENTRY_KIND_PODCAST_EPISODE
import com.grayvines.roche.core.RocheLog
import com.grayvines.roche.core.TOMBSTONE_ENTITY_COLLECTION_ENTRY
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.core.canonicalizeSyncUrl
import com.grayvines.roche.core.collectionEntryNaturalKey
import com.grayvines.roche.core.deterministicFeedId
import com.grayvines.roche.core.deterministicTagId
import com.grayvines.roche.core.extractHostTitle
import com.grayvines.roche.core.feedNaturalKey
import com.grayvines.roche.core.inferFeedTypeWireValue
import com.grayvines.roche.core.isLikelyFeedUrl
import com.grayvines.roche.core.isLocalPdfUrl
import com.grayvines.roche.core.isSyncableItemUrl
import com.grayvines.roche.core.isSyncablePublicUrl
import com.grayvines.roche.core.itemNaturalKey
import com.grayvines.roche.core.normalizeTagName
import com.grayvines.roche.core.nowUtcIso
import com.grayvines.roche.core.parseTagCsv
import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.data.catalog.CatalogSnapshot
import com.grayvines.roche.data.catalog.CatalogStore
import com.grayvines.roche.data.catalog.CollectionDrawSnapshot
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.attachEntryIdentifiers
import com.grayvines.roche.data.catalog.hasYoutubeAlbumBrowseId
import com.grayvines.roche.data.enrichment.AlbumStreamingResolver
import com.grayvines.roche.data.enrichment.withResolvedStreamingIds
import com.grayvines.roche.data.feeds.LocalFeedPoller
import com.grayvines.roche.data.feeds.ParsedFeedBatch
import com.grayvines.roche.data.feeds.buildLocalOpmlXml
import com.grayvines.roche.data.feeds.canonicalizeFeedUrl
import com.grayvines.roche.data.feeds.copySharedPdfStreamToCache
import com.grayvines.roche.data.feeds.discoverFeedFromUrl
import com.grayvines.roche.data.feeds.enrichWantToReadItemOnDevice
import com.grayvines.roche.data.feeds.normalizeUserSubmittedUrl
import com.grayvines.roche.data.feeds.validateEditedFeedUrl
import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.DbWriteLock
import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.InboxItemRow
import com.grayvines.roche.data.local.ItemTagRow
import com.grayvines.roche.data.local.LocalSyncFlagEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.TagRow
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.data.local.attachTagsLocally
import com.grayvines.roche.data.local.buildSqlContainsPattern
import com.grayvines.roche.data.local.deleteFeedPreservingHistory
import com.grayvines.roche.data.local.deleteItemPreservingHistory
import com.grayvines.roche.data.local.ensureWantToReadSpecExists
import com.grayvines.roche.data.local.extractFtsTokenQueries
import com.grayvines.roche.data.local.insertItemTag
import com.grayvines.roche.data.local.nextLocalHlc
import com.grayvines.roche.data.local.removeItemTag
import com.grayvines.roche.data.local.setItemFavorite
import com.grayvines.roche.data.local.setItemReadAt
import com.grayvines.roche.data.local.setItemReadProgress
import com.grayvines.roche.data.local.setItemStatusAndReadAt
import com.grayvines.roche.data.local.updateFeedActiveGraph
import com.grayvines.roche.data.local.updateFeedTitleGraph
import com.grayvines.roche.data.local.updateFeedTypeGraph
import com.grayvines.roche.data.local.updateFeedUrlGraph
import com.grayvines.roche.data.local.upsertFeed
import com.grayvines.roche.data.local.upsertItem
import com.grayvines.roche.data.local.upsertTag
import com.grayvines.roche.data.remote.HttpStatusException
import com.grayvines.roche.data.remote.NetworkTier
import com.grayvines.roche.data.remote.PeerNodeDto
import com.grayvines.roche.data.remote.ProblemDetailsException
import com.grayvines.roche.data.remote.PublicUrlValidator
import com.grayvines.roche.data.remote.PublicWebFetcher
import com.grayvines.roche.data.remote.RocheServerApi
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.data.settings.clampPlaybackSpeed
import com.grayvines.roche.data.settings.keepTokenOnDiscovery
import com.grayvines.roche.data.settings.normalizeServerUrl
import com.grayvines.roche.data.settings.parsePairingInput
import com.grayvines.roche.data.settings.tailscaleHttpsUpgrades
import com.grayvines.roche.data.sync.NetworkTierProvider
import com.grayvines.roche.data.sync.PeerSyncCoordinator
import com.grayvines.roche.data.sync.pollCooperativeFeedsOnDevice
import com.grayvines.roche.media.DEFAULT_MAX_OFFLINE_CACHE_BYTES
import com.grayvines.roche.media.OfflineCacheManager
import com.grayvines.roche.media.ThumbnailDiskCache
import com.grayvines.roche.media.resolveCachedAudioFile
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.FeedType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.model.TagItem
import com.grayvines.roche.model.inferContentType
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SavedResourceKind {
    ITEM,
    FEED,
}

data class SaveUrlResult(
    val title: String,
    val kind: SavedResourceKind,
) {
    val isFeed: Boolean
        get() = kind == SavedResourceKind.FEED
}

data class ArchiveStateSnapshot(
    val itemId: String,
    val previousStatus: ItemStatus,
    val previousReadAt: String?,
)

data class ReaderItemContent(
    val itemId: String,
    val contentHtml: String?,
    val contentText: String?,
)

data class SyncStatusState(
    val lastSyncedAtIso: String? = null,
    val lastError: String? = null,
    val isAuthError: Boolean = false,
)

data class WorkspaceSearchResult(
    val entries: List<InboxEntry> = emptyList(),
    val snippetsByEntryId: Map<String, String> = emptyMap(),
    val matchedCollectionIds: List<String> = emptyList(),
    val matchedDiaryLogs: List<com.grayvines.roche.data.local.ConsumptionLogEntity> = emptyList(),
)

data class RepositorySnapshot(
    val entries: List<InboxEntry>,
    val feeds: List<FeedItem>,
    val tags: List<TagItem>,
    val activePeers: List<PeerNodeDto> = emptyList(),
    val catalog: CatalogSnapshot = CatalogSnapshot(),
)

@Suppress("TooManyFunctions", "LargeClass", "LongParameterList")
class RocheRepository(
    private val database: RocheDatabase,
    private val serverApi: RocheServerApi,
    private val settings: SettingsRepository,
    private val cacheDir: File? = null,
    private val networkTierProvider: NetworkTierProvider = NetworkTierProvider { NetworkTier.WIFI },
    private val backgroundScope: CoroutineScope? = null,
    private val albumStreamingResolver: AlbumStreamingResolver? = null,
    private val thumbnailDiskCache: ThumbnailDiskCache = ThumbnailDiskCache(cacheDir),
    catalogStore: CatalogStore? = null,
    private val publicWebFetcher: PublicWebFetcher = PublicWebFetcher(),
    maxOfflineCacheBytes: Long = DEFAULT_MAX_OFFLINE_CACHE_BYTES,
) {
    private val dao = database.dao()
    private val syncMutex = Mutex()
    private val backgroundSyncRunning = AtomicBoolean(false)
    private val backgroundSyncPending = AtomicBoolean(false)
    private val activeSyncCount = AtomicInteger(0)
    private val _isSyncActive = MutableStateFlow(false)
    val isSyncActive: StateFlow<Boolean> = _isSyncActive.asStateFlow()
    private val _syncStatus = MutableStateFlow(SyncStatusState())
    val syncStatus: StateFlow<SyncStatusState> = _syncStatus.asStateFlow()
    private val entryCache = InboxEntryCache()
    private val activePeersFlow = MutableStateFlow<List<PeerNodeDto>>(emptyList())
    internal val hasBackgroundScope: Boolean
        get() = backgroundScope != null

    val catalog: CatalogStore =
        (catalogStore
                ?: CatalogStore(
                    dao = dao,
                    localPeerIdProvider = ::currentLocalPeerId,
                    writeLock = database.writeLock,
                ))
            .also { store -> store.setOnLocalMutation(::triggerBackgroundSync) }

    private val itemMutationMutex: DbWriteLock = catalog.writeLock
    @Volatile
    internal var latestSnapshot: RepositorySnapshot? = null
        private set

    private val offlineCacheManager =
        OfflineCacheManager(
            dao = dao,
            serverApi = serverApi,
            publicWebFetcher = publicWebFetcher,
            settings = settings,
            cacheDir = cacheDir,
            thumbnailDiskCache = thumbnailDiskCache,
            maxOfflineCacheBytes = maxOfflineCacheBytes,
            writeLock = database.writeLock,
        )

    val inFlightOfflineEntryIds: StateFlow<Set<String>> = offlineCacheManager.inFlightEntryIds

    internal val unifiedSnapshotLoader =
        UnifiedSnapshotLoader(dao = dao, catalog = catalog, entryCache = entryCache)

    @OptIn(ExperimentalCoroutinesApi::class)
    val snapshot: Flow<RepositorySnapshot> =
        combine(
                dao.observeRepositoryStamp()
                    .onStart { catalog.ensureSystemSeeded() }
                    .distinctUntilChanged(),
                activePeersFlow,
            ) { stamp, _ ->
                stamp to activePeersFlow.value
            }
            .distinctUntilChanged()
            .conflate()
            .mapLatest { (stamp, activePeers) ->
                unifiedSnapshotLoader.loadSnapshot(activePeers, observedStamp = stamp).also {
                    latestSnapshot = it
                }
            }
            .flowOn(Dispatchers.Default)

    private val prewarmedAlbumKeys: MutableSet<String> =
        java.util.Collections.synchronizedSet(HashSet())

    suspend fun prewarm(): RepositorySnapshot {
        loadPersistedSyncStatus()
        return snapshot.first()
    }

    internal suspend fun loadPersistedSyncStatus(): SyncStatusState {
        val loaded =
            SyncStatusState(
                lastSyncedAtIso = dao.getLocalSyncFlag(FLAG_LAST_SYNCED_AT_ISO),
                lastError = dao.getLocalSyncFlag(FLAG_LAST_SYNC_ERROR),
                isAuthError = dao.getLocalSyncFlag(FLAG_LAST_SYNC_AUTH_ERROR) == "1",
            )
        _syncStatus.value = loaded
        return loaded
    }

    suspend fun resolveCollectionDrawStreaming(
        draw: CollectionDrawSnapshot,
        includeWikipedia: Boolean = false,
    ): CollectionDrawSnapshot {
        val resolver = albumStreamingResolver ?: return draw
        if (!draw.isMusicKind) return draw
        val resolved = resolver.resolveStreamingIds(draw, includeWikipedia = includeWikipedia)
        if (resolved.isNotEmpty() && draw.entryId.isNotBlank()) {
            catalog.attachEntryIdentifiers(draw.entryId, resolved)
        }
        return draw.withResolvedStreamingIds(resolved)
    }

    suspend fun prewarmCollectionDraw(draw: CollectionDrawSnapshot) {
        if (albumStreamingResolver == null || draw.kind != EntryKind.ALBUM) return
        if (draw.hasYoutubeAlbumBrowseId && !draw.wikipediaUrl.isNullOrBlank()) return
        val dedupKey = draw.entryId.ifBlank { "${draw.creator}|${draw.title}|${draw.spotifyId}" }
        if (!prewarmedAlbumKeys.add(dedupKey)) return
        resolveCollectionDrawStreaming(draw, includeWikipedia = draw.wikipediaUrl.isNullOrBlank())
    }

    internal suspend fun searchDeepEntries(rawQuery: String): List<InboxEntry> =
        searchWorkspace(rawQuery).entries

    internal suspend fun searchWorkspace(rawQuery: String): WorkspaceSearchResult =
        withContext(Dispatchers.Default) {
            val cleanQuery = rawQuery.replace("\u0000", "").trim().trimStart('#').trim()
            if (cleanQuery.isBlank()) {
                WorkspaceSearchResult()
            } else {
                val likePattern = buildSqlContainsPattern(cleanQuery)
                val tokenQueries = extractFtsTokenQueries(cleanQuery)
                val matchedItems =
                    dao.searchFullItemsByTokens(
                        tokenQueries = tokenQueries,
                        likePattern = likePattern,
                    )
                val entries =
                    if (matchedItems.isEmpty()) {
                        emptyList()
                    } else {
                        val tagNamesByItemId =
                            groupSortedTagNamesByItemId(dao.getAllTags(), dao.getAllItemTags())
                        matchedItems.map { entity ->
                            toInboxEntry(entity, tagNamesByItemId[entity.id].orEmpty())
                        }
                    }
                val snippetsById = buildFtsSnippetsMap(tokenQueries)
                val matchedCollectionIds = dao.searchCollectionIdsByTitle(likePattern)
                val matchedDiaryLogs = dao.searchConsumptionLogsByNotes(likePattern)
                WorkspaceSearchResult(
                    entries = entries,
                    snippetsByEntryId = snippetsById,
                    matchedCollectionIds = matchedCollectionIds,
                    matchedDiaryLogs = matchedDiaryLogs,
                )
            }
        }

    private suspend fun buildFtsSnippetsMap(tokenQueries: List<String>): Map<String, String> {
        val firstToken = tokenQueries.firstOrNull() ?: return emptyMap()
        val combinedMatch = tokenQueries.joinToString(" ")
        val snippets = LinkedHashMap<String, String>()
        querySnippetsWithFallback(combinedMatch, firstToken, tokenQueries.size > 1) { q ->
                dao.searchContentFtsSnippets(q)
            }
            .forEach { row ->
                val clean = sanitizeSnippetExcerpt(row.excerpt)
                if (clean.isNotEmpty() && row.entryId !in snippets) {
                    snippets[row.entryId] = clean
                }
            }
        querySnippetsWithFallback(combinedMatch, firstToken, tokenQueries.size > 1) { q ->
                dao.searchEntryFtsSnippets(q)
            }
            .forEach { row ->
                val clean = sanitizeSnippetExcerpt(row.excerpt)
                if (clean.isNotEmpty() && row.entryId !in snippets) {
                    snippets[row.entryId] = clean
                }
            }
        return snippets
    }

    private suspend fun querySnippetsWithFallback(
        combinedMatch: String,
        firstToken: String,
        hasMultipleTokens: Boolean,
        query: suspend (String) -> List<com.grayvines.roche.data.local.SearchSnippetRow>,
    ): List<com.grayvines.roche.data.local.SearchSnippetRow> {
        val primary = runSuspendCatching { query(combinedMatch) }.getOrNull()
        if (!primary.isNullOrEmpty()) return primary
        return if (primary == null || hasMultipleTokens) {
            runSuspendCatching { query(firstToken) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }

    fun currentNetworkTier(): NetworkTier = networkTierProvider.currentNetworkTier()

    suspend fun setArchiveState(itemId: String, archived: Boolean): ItemStatus? =
        setArchiveStateWithSnapshot(itemId = itemId, archived = archived)?.previousStatus

    internal suspend fun setArchiveStateWithSnapshot(
        itemId: String,
        archived: Boolean,
    ): ArchiveStateSnapshot? {
        var evictAudioCache = false
        val snapshot =
            itemMutationMutex.withLock {
                val (snap, shouldEvict) = archiveWithSnapshotLocked(itemId, archived)
                evictAudioCache = shouldEvict
                snap
            } ?: return null
        if (evictAudioCache) {
            offlineCacheManager.setOfflineCacheState(itemId = itemId, enabled = false)
        }
        triggerBackgroundSync()
        return snapshot
    }

    private suspend fun archiveWithSnapshotLocked(
        itemId: String,
        archived: Boolean,
    ): Pair<ArchiveStateSnapshot?, Boolean> {
        var result: Pair<ArchiveStateSnapshot?, Boolean> = null to false
        dao.executeInTransaction {
            val current = dao.getItemStateById(itemId)
            if (current != null) {
                val transition = buildArchiveTransition(current, archived)
                dao.setItemStatusAndReadAt(
                    itemId,
                    transition.newStatus.wireValue,
                    transition.newReadAt,
                    currentLocalPeerId(),
                )
                if (archived) {
                    maybeAutoLogFeedItemCompletion(itemId)
                }
                result = transition.snapshot to transition.shouldEvictAudio
            }
        }
        return result
    }

    private data class ArchiveTransition(
        val snapshot: ArchiveStateSnapshot,
        val newStatus: ItemStatus,
        val newReadAt: String?,
        val shouldEvictAudio: Boolean,
    )

    private fun buildArchiveTransition(
        current: com.grayvines.roche.data.local.ItemStateRow,
        archived: Boolean,
    ): ArchiveTransition {
        val snapshot =
            ArchiveStateSnapshot(
                itemId = current.id,
                previousStatus = ItemStatus.fromWireValue(current.status),
                previousReadAt = current.readAt,
            )
        val newStatus = if (archived) ItemStatus.ARCHIVED else ItemStatus.UNREAD
        val isAudioItem =
            current.contentType.equals(AUDIO_CONTENT_TYPE, ignoreCase = true) ||
                !current.audioUrl.isNullOrBlank()
        val newReadAt =
            if (!archived) {
                null
            } else if (isAudioItem) {
                nowUtcIso()
            } else {
                current.readAt?.takeIf { it.isNotBlank() } ?: nowUtcIso()
            }
        return ArchiveTransition(
            snapshot = snapshot,
            newStatus = newStatus,
            newReadAt = newReadAt,
            shouldEvictAudio = archived && current.isCachedOffline && isAudioItem,
        )
    }

    internal suspend fun restoreStatus(
        itemId: String,
        status: ItemStatus,
        readAt: String? = null,
    ) {
        val updated = itemMutationMutex.withLock { restoreStatusLocked(itemId, status, readAt) }
        if (updated) {
            triggerBackgroundSync()
        }
    }

    internal suspend fun restoreArchiveSnapshots(snapshots: List<ArchiveStateSnapshot>) {
        var anyUpdated = false
        itemMutationMutex.withLock {
            for (snapshot in snapshots) {
                if (
                    restoreStatusLocked(
                        itemId = snapshot.itemId,
                        status = snapshot.previousStatus,
                        readAt = snapshot.previousReadAt,
                    )
                ) {
                    anyUpdated = true
                }
            }
        }
        if (anyUpdated) {
            triggerBackgroundSync()
        }
    }

    private suspend fun restoreStatusLocked(
        itemId: String,
        status: ItemStatus,
        readAt: String?,
    ): Boolean {
        val current = dao.getItemStateById(itemId) ?: return false
        val targetReadAt =
            if (status == ItemStatus.ARCHIVED) {
                readAt?.takeIf { it.isNotBlank() }
                    ?: current.readAt?.takeIf { it.isNotBlank() }
                    ?: nowUtcIso()
            } else {
                readAt?.takeIf { it.isNotBlank() }
            }
        dao.setItemStatusAndReadAt(itemId, status.wireValue, targetReadAt, currentLocalPeerId())
        return true
    }

    suspend fun setReadState(itemId: String, isRead: Boolean) {
        val updated = itemMutationMutex.withLock {
            val current = dao.getItemStateById(itemId)
            val currentlyRead = current != null && !current.readAt.isNullOrBlank()
            if (current == null || currentlyRead == isRead) {
                false
            } else {
                val nextReadAt = if (isRead) nowUtcIso() else null
                dao.setItemReadAt(itemId, nextReadAt, currentLocalPeerId())
                true
            }
        }
        if (updated) {
            triggerBackgroundSync()
        }
    }

    suspend fun toggleReadState(itemId: String): Boolean {
        var updated = false
        val nextIsRead = itemMutationMutex.withLock {
            val current = dao.getItemStateById(itemId)
            if (current == null) {
                false
            } else {
                val targetRead = current.readAt.isNullOrBlank()
                val nextReadAt = if (targetRead) nowUtcIso() else null
                dao.setItemReadAt(itemId, nextReadAt, currentLocalPeerId())
                updated = true
                targetRead
            }
        }
        if (updated) {
            triggerBackgroundSync()
        }
        return nextIsRead
    }

    suspend fun toggleStar(itemId: String, targetFavorite: Boolean? = null): Boolean =
        itemMutationMutex.withLock {
            val current = dao.getItemStateById(itemId)
            if (current == null) {
                false
            } else {
                val updated = targetFavorite ?: !current.isFavorite
                if (current.isFavorite != updated) {
                    dao.setItemFavorite(itemId, updated, currentLocalPeerId())
                    triggerBackgroundSync()
                }
                updated
            }
        }

    suspend fun toggleOfflineCache(itemId: String): Boolean {
        val current = dao.getItemStateById(itemId) ?: return false
        val updated = !current.isCachedOffline
        return offlineCacheManager.setOfflineCacheState(itemId = itemId, enabled = updated)
    }

    suspend fun cacheEntryOffline(itemId: String): Boolean =
        offlineCacheManager.setOfflineCacheState(itemId = itemId, enabled = true)

    suspend fun removeOfflineCache(itemId: String): Boolean =
        offlineCacheManager.setOfflineCacheState(itemId = itemId, enabled = false)

    internal suspend fun hydrateItemContentIfNeeded(
        itemId: String,
        onLocalLoaded: (ReaderItemContent) -> Unit = {},
    ): ReaderItemContent? {
        dao.getItemById(itemId)?.let { local ->
            onLocalLoaded(ReaderItemContent(local.id, local.contentHtml, local.contentText))
        }
        offlineCacheManager.hydrateItemForReader(itemId)
        val entity = dao.getItemById(itemId) ?: return null
        return ReaderItemContent(
            itemId = entity.id,
            contentHtml = entity.contentHtml,
            contentText = entity.contentText,
        )
    }

    internal suspend fun archiveEntriesBatch(
        entryIds: List<String>,
        feedId: String? = null,
        allUnread: Boolean = false,
    ): Int =
        archiveBatchWithSnapshots(
                entryIds = entryIds,
                feedId = feedId,
                allUnread = allUnread,
            )
            .size

    internal suspend fun archiveBatchWithSnapshots(
        entryIds: List<String>,
        feedId: String? = null,
        allUnread: Boolean = false,
    ): List<ArchiveStateSnapshot> =
        withContext(Dispatchers.Default) { archiveBatchInternal(entryIds, feedId, allUnread) }

    private suspend fun archiveBatchInternal(
        entryIds: List<String>,
        feedId: String?,
        allUnread: Boolean,
    ): List<ArchiveStateSnapshot> {
        val audioIdsToEvict = ArrayList<String>()
        val snapshots = itemMutationMutex.withLock {
            val targetIds = resolveArchiveBatchTargetIds(dao, entryIds, feedId, allUnread)
            val unarchivedItems = dao.getUnarchivedItemStates().filter { it.id in targetIds }
            if (unarchivedItems.isEmpty()) {
                emptyList()
            } else {
                val nowIso = nowUtcIso()
                val nodeId = currentLocalPeerId()
                val batchSnapshots = unarchivedItems.map { item ->
                    val isAudio =
                        item.contentType.equals(AUDIO_CONTENT_TYPE, ignoreCase = true) ||
                            !item.audioUrl.isNullOrBlank()
                    if (item.isCachedOffline && isAudio) {
                        audioIdsToEvict.add(item.id)
                    }
                    ArchiveStateSnapshot(
                        itemId = item.id,
                        previousStatus = ItemStatus.fromWireValue(item.status),
                        previousReadAt = item.readAt,
                    )
                }
                dao.executeInTransaction {
                    for (item in unarchivedItems) {
                        val nextReadAt = item.readAt?.takeIf { it.isNotBlank() } ?: nowIso
                        dao.setItemStatusAndReadAt(
                            item.id,
                            ItemStatus.ARCHIVED.wireValue,
                            nextReadAt,
                            nodeId,
                        )
                    }
                }
                batchSnapshots
            }
        }
        if (snapshots.isEmpty()) return emptyList()
        for (audioItemId in audioIdsToEvict) {
            offlineCacheManager.setOfflineCacheState(itemId = audioItemId, enabled = false)
        }
        triggerBackgroundSync()
        return snapshots
    }

    suspend fun setFeedAutoDownload(feedId: String, enabled: Boolean) {
        itemMutationMutex.withLock {
            dao.updateFeedAutoDownload(feedId = feedId, enabled = enabled)
        }
        if (enabled) {
            offlineCacheManager.autoDownloadNewEpisodes()
        }
    }

    suspend fun setFeedPlaybackSpeed(feedId: String, speed: Float?) {
        val clamped = speed?.takeIf { it.isFinite() }?.let(::clampPlaybackSpeed)
        itemMutationMutex.withLock { dao.updateFeedPlaybackSpeed(feedId = feedId, speed = clamped) }
    }

    fun resolveAudioPlaybackSource(entry: InboxEntry): String =
        resolveCachedAudioFile(cacheDir = cacheDir, itemId = entry.id)?.absolutePath
            ?: entry.audioUrl?.takeIf { it.isNotBlank() }
            ?: entry.url

    internal suspend fun importOpml(opmlXml: String): Result<Int> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val trimmed = opmlXml.trim()
                check(trimmed.isNotBlank()) { "OPML file is empty" }
                val currentSettings = settings.settings.first()
                check(
                    currentSettings.serverUrl.isNotBlank() &&
                        currentSettings.bearerToken.isNotBlank()
                ) {
                    "Configure Server URL in Settings before importing OPML"
                }
                val imported =
                    serverApi.importOpml(
                        serverUrl = currentSettings.serverUrl,
                        token = currentSettings.bearerToken,
                        opmlXml = trimmed,
                    )
                syncWithServer()
                imported
            }
        }

    internal suspend fun exportOpml(): Result<String> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val currentSettings = settings.settings.first()
                if (
                    currentSettings.serverUrl.isNotBlank() &&
                        currentSettings.bearerToken.isNotBlank()
                ) {
                    runSuspendCatching {
                        serverApi.exportOpml(
                            serverUrl = currentSettings.serverUrl,
                            token = currentSettings.bearerToken,
                        )
                    }
                        .onFailure { err ->
                            RocheLog.warn(
                                LOG_TAG,
                                "Server OPML export failed; falling back to local",
                                err,
                            )
                        }
                        .getOrElse { buildLocalOpmlXml(dao.getAllFeeds()) }
                } else {
                    buildLocalOpmlXml(dao.getAllFeeds())
                }
            }
        }

    internal suspend fun updateReadProgress(
        itemId: String,
        progress: Double,
        recordPlayedTimestamp: Boolean = false,
        syncRemote: Boolean = true,
        fromAudioPlayback: Boolean? = null,
    ) {
        if (!progress.isFinite()) return
        val updated = itemMutationMutex.withLock {
            if (dao.getItemStateById(itemId) == null) {
                false
            } else {
                val clamped = progress.coerceIn(MIN_READ_PROGRESS, MAX_READ_PROGRESS)
                val explicitReadAt = if (recordPlayedTimestamp) nowUtcIso() else null
                dao.setItemReadProgress(itemId, clamped, explicitReadAt, currentLocalPeerId())
                maybeAutoLogFeedItemCompletion(itemId, fromAudioPlayback = fromAudioPlayback)
                true
            }
        }
        if (updated && syncRemote) {
            triggerBackgroundSync()
        }
    }

    internal suspend fun recordEpisodePlaybackStarted(itemId: String) {
        val updated = itemMutationMutex.withLock {
            if (dao.getItemStateById(itemId) == null) {
                false
            } else {
                val nowIso = nowUtcIso()
                dao.setItemReadAt(itemId, nowIso, currentLocalPeerId())
                true
            }
        }
        if (updated) {
            triggerBackgroundSync()
        }
    }

    internal suspend fun toggleTagOnItem(itemId: String, tagName: String) {
        val normalized = normalizeTagName(tagName)
        if (normalized.isBlank()) return
        val updated = itemMutationMutex.withLock {
            if (dao.getItemStateById(itemId) == null) {
                false
            } else {
                val nodeId = currentLocalPeerId()
                val existingTag =
                    dao.getTagByName(normalized)
                        ?: TagRow(id = deterministicTagId(normalized), name = normalized).also {
                            dao.upsertTag(it, markDirty = true, nodeId = nodeId)
                        }
                val alreadyAssigned = dao.hasCollectionMembership(existingTag.id, itemId) > 0
                if (alreadyAssigned) {
                    dao.removeItemTag(
                        itemId = itemId,
                        tagId = existingTag.id,
                        markDirty = true,
                        nodeId = nodeId,
                    )
                } else {
                    dao.insertItemTag(
                        ItemTagRow(itemId = itemId, tagId = existingTag.id),
                        markDirty = true,
                        nodeId = nodeId,
                    )
                }
                true
            }
        }
        if (updated) {
            triggerBackgroundSync()
        }
    }

    internal suspend fun renameFeed(feedId: String, newTitle: String): String? {
        val cleanTitle = newTitle.trim()
        if (cleanTitle.isBlank()) return null
        val changed =
            itemMutationMutex.withLock {
                dao.getFeedById(feedId)?.let { existing ->
                    if (existing.title != cleanTitle) {
                        dao.updateFeedTitleGraph(
                            feedId = feedId,
                            cleanTitle = cleanTitle,
                            markDirty = isSyncablePublicUrl(existing.url),
                            nodeId = currentLocalPeerId(),
                        )
                        true
                    } else {
                        false
                    }
                }
            } ?: return null
        if (changed) {
            triggerBackgroundSync()
        }
        return cleanTitle
    }

    internal suspend fun updateFeedUrl(
        feedId: String,
        rawNewUrl: String,
    ): Result<Pair<String, Boolean>> = runSuspendCatching {
        val outcome = itemMutationMutex.withLock {
            val existing = dao.getFeedById(feedId) ?: missingFeedError(feedId)
            val trimmed = rawNewUrl.trim()
            if (trimmed == existing.url) {
                existing.url to false
            } else {
                val feedType = FeedType.fromWireValue(existing.feedType)
                val normalized = validateEditedFeedUrl(trimmed, feedType)
                if (canonicalizeSyncUrl(normalized) == canonicalizeSyncUrl(existing.url)) {
                    existing.url to false
                } else {
                    val didUpdate =
                        dao.updateFeedUrlGraph(
                            feedId = feedId,
                            newFeedUrl = normalized,
                            markDirty = isSyncablePublicUrl(normalized),
                            nodeId = currentLocalPeerId(),
                        )
                    normalized to didUpdate
                }
            }
        }
        if (outcome.second) {
            triggerBackgroundSync()
        }
        outcome
    }

    internal suspend fun setFeedType(feedId: String, targetType: FeedType): Result<FeedType> =
        runSuspendCatching {
            require(targetType == FeedType.RSS || targetType == FeedType.PODCAST) {
                "Feeds can only be switched between RSS and Podcast"
            }
            val changed = itemMutationMutex.withLock {
                val existing = dao.getFeedById(feedId) ?: missingFeedError(feedId)
                val currentType = FeedType.fromWireValue(existing.feedType)
                require(currentType == FeedType.RSS || currentType == FeedType.PODCAST) {
                    "Only RSS and Podcast feeds can switch type"
                }
                if (currentType == targetType) {
                    false
                } else {
                    dao.updateFeedTypeGraph(
                        feedId = feedId,
                        targetFeedType = targetType.wireValue,
                        markDirty = isSyncablePublicUrl(existing.url),
                        nodeId = currentLocalPeerId(),
                    )
                    true
                }
            }
            if (changed) {
                triggerBackgroundSync()
            }
            targetType
        }

    suspend fun toggleFeedArchive(feedId: String): Result<Pair<String, Boolean>> =
        runSuspendCatching {
            val outcome = itemMutationMutex.withLock {
                val existing = dao.getFeedById(feedId) ?: missingFeedError(feedId)
                val nextIsActive = !existing.isActive
                dao.updateFeedActiveGraph(
                    feedId = feedId,
                    isActive = nextIsActive,
                    markDirty = isSyncablePublicUrl(existing.url),
                    nodeId = currentLocalPeerId(),
                )
                existing.title.ifBlank { feedId } to !nextIsActive
            }
            triggerBackgroundSync()
            outcome
        }

    internal suspend fun applyTagToFeedItems(feedId: String, tagName: String): Int {
        val normalized = normalizeTagName(tagName)
        if (normalized.isBlank()) return 0
        val taggedCount = itemMutationMutex.withLock {
            val itemIds = dao.getUnarchivedItemIdsForFeed(feedId)
            if (itemIds.isEmpty()) {
                0
            } else {
                val nodeId = currentLocalPeerId()
                val existingTag =
                    dao.getTagByName(normalized)
                        ?: TagRow(id = deterministicTagId(normalized), name = normalized).also {
                            dao.upsertTag(it, markDirty = true, nodeId = nodeId)
                        }
                var count = 0
                dao.executeInTransaction {
                    for (itemId in itemIds) {
                        if (dao.hasCollectionMembership(existingTag.id, itemId) == 0) {
                            dao.insertItemTag(
                                ItemTagRow(itemId = itemId, tagId = existingTag.id),
                                markDirty = true,
                                nodeId = nodeId,
                            )
                            count++
                        }
                    }
                }
                count
            }
        }
        if (taggedCount > 0) {
            triggerBackgroundSync()
        }
        return taggedCount
    }

    suspend fun refreshFeed(feedId: String): Result<String> = withSyncIndicator {
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                catalog.ensureSystemSeeded()
                val currentSettings = settings.settings.first()
                val localFeed = dao.getFeedById(feedId) ?: missingFeedError(feedId)
                val existingTitle = localFeed.title
                val localPoll =
                    LocalFeedPoller.pollFeed(
                        dao = dao,
                        publicWebFetcher = publicWebFetcher,
                        feed = localFeed,
                        localPeerId = currentSettings.peerId,
                        writeLock = database.writeLock,
                    )
                val hasServer =
                    currentSettings.serverUrl.isNotBlank() &&
                        currentSettings.bearerToken.isNotBlank() &&
                        isServerSyncAllowedByTransport(currentSettings.serverUrl)
                if (hasServer) {
                    val syncOutcome = syncWithServer()
                    if (localPoll.isFailure && syncOutcome.isFailure) {
                        localPoll.getOrThrow()
                    }
                } else {
                    localPoll.getOrThrow()
                }
                offlineCacheManager.autoDownloadNewEpisodes()
                dao.getFeedById(localFeed.id)?.title?.ifBlank { existingTitle.ifBlank { feedId } }
                    ?: existingTitle.ifBlank { feedId }
            }
        }
    }

    suspend fun deleteFeed(feedId: String): Result<String> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val (deletedTitle, evictedIds) =
                    itemMutationMutex.withLock {
                        val existing = dao.getFeedById(feedId)
                        val isLocalOnly = existing != null && !isSyncablePublicUrl(existing.url)
                        val nodeId = currentLocalPeerId()
                        val ids = ArrayList<String>()
                        dao.executeInTransaction {
                            val hlc = nextLocalHlc(dao, nodeId)
                            if (existing != null) {
                                recordDeletedFeedTombstones(existing, hlc, isLocalOnly)
                            }
                            ids.addAll(
                                dao.deleteFeedPreservingHistory(
                                    feedId = feedId,
                                    recordTombstones = !isLocalOnly,
                                    nodeId = nodeId,
                                    hlcOverride = hlc,
                                )
                            )
                        }
                        (existing?.title ?: feedId) to ids
                    }
                for (itemId in evictedIds) {
                    if (dao.getEntryContentById(itemId) == null) {
                        offlineCacheManager.evictCachedItemFiles(itemId)
                    }
                }
                triggerBackgroundSync()
                deletedTitle
            }
        }

    private suspend fun recordDeletedFeedTombstones(
        existing: FeedRow,
        hlc: String,
        isLocalOnly: Boolean,
    ) {
        val deletedAtIso = nowUtcIso()
        dao.upsertTombstone(
            TombstoneEntity(
                entityType = "feed",
                naturalKey = feedNaturalKey(existing.url),
                deletedAt = deletedAtIso,
                hlc = hlc,
                isLocalDirty = !isLocalOnly,
            )
        )
        dao.upsertTombstone(
            TombstoneEntity(
                entityType = "feed",
                naturalKey = existing.id,
                deletedAt = deletedAtIso,
                hlc = hlc,
                isLocalDirty = !isLocalOnly,
            )
        )
    }

    internal suspend fun cachedItemBytesByEntryId(): Map<String, Long> =
        offlineCacheManager.cachedBytesByEntryId()

    internal suspend fun totalOfflineCacheBytes(): Long =
        offlineCacheManager.totalOfflineCacheBytes()

    internal suspend fun enrichCatalogEntryOnline(entryId: String): Result<Boolean> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val cleanId = entryId.trim()
                val currentSettings = settings.settings.first()
                val canEnrich =
                    cleanId.isNotEmpty() &&
                        currentSettings.serverUrl.isNotBlank() &&
                        currentSettings.bearerToken.isNotBlank() &&
                        isServerSyncAllowedByTransport(currentSettings.serverUrl)
                if (canEnrich) {
                    serverApi.enrichCatalogEntry(
                        serverUrl = currentSettings.serverUrl,
                        token = currentSettings.bearerToken,
                        entryId = cleanId,
                    )
                    syncWithServer().getOrThrow()
                    true
                } else {
                    false
                }
            }
        }

    suspend fun deleteItem(itemId: String): Result<String> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val title = itemMutationMutex.withLock {
                    val existing = dao.getItemById(itemId)
                    val nodeId = currentLocalPeerId()
                    dao.executeInTransaction {
                        if (existing != null && existing.url.isNotBlank()) {
                            val feedUrl =
                                existing.feedUrl
                                    ?: existing.feedId?.let { fid -> dao.getFeedById(fid)?.url }
                            val nowIso = nowUtcIso()
                            val hlc = nextLocalHlc(dao, nodeId)
                            dao.upsertTombstone(
                                TombstoneEntity(
                                    entityType = "item",
                                    naturalKey = itemNaturalKey(feedUrl, existing.url),
                                    deletedAt = nowIso,
                                    hlc = hlc,
                                    isLocalDirty = true,
                                )
                            )
                        }
                        dao.deleteItemPreservingHistory(
                            itemId = itemId,
                            recordTombstones = true,
                            nodeId = nodeId,
                        )
                    }
                    existing?.title ?: itemId
                }
                offlineCacheManager.evictCachedItemFiles(itemId)
                triggerBackgroundSync()
                title
            }
        }

    suspend fun saveUrlOrFeed(
        inputUrl: String,
        titleOverride: String?,
        tagCsv: String,
        feedType: FeedType? = null,
    ): SaveUrlResult =
        withContext(Dispatchers.Default) {
            val trimmedUrl = normalizeUserSubmittedUrl(inputUrl)
            if (isLikelyFeedUrl(trimmedUrl)) {
                val feedTitle =
                    subscribeFeedLocal(
                        url = trimmedUrl,
                        titleOverride = titleOverride,
                        requestedFeedType = feedType,
                    )
                triggerBackgroundSync()
                SaveUrlResult(title = feedTitle, kind = SavedResourceKind.FEED)
            } else {
                addUrlToWantToRead(
                    inputUrl = trimmedUrl,
                    titleOverride = titleOverride,
                    tagCsv = tagCsv,
                )
            }
        }

    private data class PersistedWantItem(
        val row: InboxItemRow,
        val wasNewlyInserted: Boolean,
    )

    suspend fun addUrlToWantToRead(
        inputUrl: String,
        titleOverride: String? = null,
        tagCsv: String = "",
        forcedContentType: ContentType? = null,
        pdfInputStream: InputStream? = null,
        awaitPdfCacheCopy: Boolean = false,
        allowOverwriteExistingPdf: Boolean = false,
    ): SaveUrlResult =
        withContext(Dispatchers.Default) {
            val normalizedUrl = normalizeUserSubmittedUrl(inputUrl)
            check(normalizedUrl.isNotBlank()) { "Enter a valid URL" }
            val inferredType = forcedContentType ?: inferContentType(normalizedUrl)
            val isLocalPdf = isLocalPdfUrl(normalizedUrl)
            val hostTitle = if (isLocalPdf) "Local PDF" else extractHostTitle(normalizedUrl)
            val cleanTitleOverride = titleOverride?.trim()?.takeIf { it.isNotBlank() }
            val localEntity =
                createSavedItemEntity(
                    url = normalizedUrl,
                    title = resolveSavedItemTitle(cleanTitleOverride, hostTitle, inferredType),
                    hostTitle = hostTitle,
                    type = inferredType,
                    inboxState = "none",
                )
            val persisted =
                persistWantToReadItemLocally(
                    localEntity = localEntity,
                    normalizedUrl = normalizedUrl,
                    cleanTitleOverride = cleanTitleOverride,
                    tags = parseTagCsv(tagCsv),
                )
            val remainingPdfStream =
                handleSharedPdfCacheAttachment(
                    persisted = persisted,
                    isLocalPdf = isLocalPdf,
                    pdfInputStream = pdfInputStream,
                    awaitPdfCacheCopy = awaitPdfCacheCopy,
                    allowOverwriteExistingPdf = allowOverwriteExistingPdf,
                )
            launchWantToReadEnrichment(
                itemId = persisted.row.id,
                url = normalizedUrl,
                hasUserTitleOverride = cleanTitleOverride != null,
                pdfInputStream = remainingPdfStream,
            )
            SaveUrlResult(title = persisted.row.title, kind = SavedResourceKind.ITEM)
        }

    private fun resolveSavedItemTitle(
        cleanTitleOverride: String?,
        hostTitle: String,
        inferredType: ContentType,
    ): String =
        cleanTitleOverride
            ?: if (inferredType == ContentType.PDF) {
                "$hostTitle — PDF"
            } else {
                "$hostTitle — Saved Entry"
            }

    private suspend fun handleSharedPdfCacheAttachment(
        persisted: PersistedWantItem,
        isLocalPdf: Boolean,
        pdfInputStream: InputStream?,
        awaitPdfCacheCopy: Boolean,
        allowOverwriteExistingPdf: Boolean,
    ): InputStream? {
        if (pdfInputStream == null) return null
        val existingCachedPdfPresent =
            isLocalPdf &&
                cacheDir != null &&
                com.grayvines.roche.media.findCachedPdfFileForEntry(cacheDir, persisted.row.id) !=
                    null
        val targetAlreadyProtected = !isLocalPdf || existingCachedPdfPresent
        val refuseOverwrite =
            !persisted.wasNewlyInserted && !allowOverwriteExistingPdf && targetAlreadyProtected
        if (refuseOverwrite) {
            runCatching { pdfInputStream.close() }
            return null
        }
        if (!awaitPdfCacheCopy) return pdfInputStream
        val copied =
            copySharedPdfStreamToCache(
                dao = dao,
                cacheDir = cacheDir,
                itemId = persisted.row.id,
                stream = pdfInputStream,
                allowOverwriteExisting = allowOverwriteExistingPdf,
            )
        if (!copied && isLocalPdf) {
            rollbackFailedLocalPdfSave(persisted)
            error("Could not save shared PDF to offline cache (limit reached or already exists)")
        }
        return null
    }

    private suspend fun rollbackFailedLocalPdfSave(persisted: PersistedWantItem) {
        if (!persisted.wasNewlyInserted) return
        itemMutationMutex.withLock {
            catalog.withMutationLock {
                val nodeId = currentLocalPeerId()
                dao.executeInTransaction {
                    dao.deleteItemPreservingHistory(
                        itemId = persisted.row.id,
                        recordTombstones = false,
                        detachWantQueues = true,
                        nodeId = nodeId,
                    )
                }
            }
        }
        offlineCacheManager.evictCachedItemFiles(persisted.row.id)
    }

    private suspend fun persistWantToReadItemLocally(
        localEntity: InboxItemRow,
        normalizedUrl: String,
        cleanTitleOverride: String?,
        tags: List<String>,
    ): PersistedWantItem = itemMutationMutex.withLock {
        val nodeId = currentLocalPeerId()
        var result = PersistedWantItem(row = localEntity, wasNewlyInserted = true)
        catalog.withMutationLock {
            dao.executeInTransaction {
                result =
                    persistWantToReadInTx(
                        localEntity = localEntity,
                        normalizedUrl = normalizedUrl,
                        cleanTitleOverride = cleanTitleOverride,
                        tags = tags,
                        nodeId = nodeId,
                    )
            }
        }
        result
    }

    private suspend fun persistWantToReadInTx(
        localEntity: InboxItemRow,
        normalizedUrl: String,
        cleanTitleOverride: String?,
        tags: List<String>,
        nodeId: String,
    ): PersistedWantItem {
        val isSyncable = isSyncableItemUrl(normalizedUrl)
        val nowIso = nowUtcIso()
        val hlc = nextLocalHlc(dao, nodeId)
        val existingItem = findExistingItemForSavedUrl(localEntity.id, normalizedUrl)
        val targetEntity =
            if (existingItem != null) {
                mergeExistingItemOnResave(existingItem, cleanTitleOverride)
            } else {
                localEntity
            }
        dao.ensureWantToReadSpecExists(markDirty = isSyncable, nodeId = nodeId)
        if (isSyncable) {
            dao.getEntryById(WANT_TO_READ_COLLECTION_ID)?.let { wantCol ->
                dao.upsertEntry(wantCol.copy(updatedAt = nowIso, hlc = hlc, isLocalDirty = true))
            }
        }
        dao.deleteTombstone("item", itemNaturalKey(null, normalizedUrl))
        dao.deleteTombstone(
            TOMBSTONE_ENTITY_COLLECTION_ENTRY,
            collectionEntryNaturalKey(WANT_TO_READ_COLLECTION_ID, targetEntity.id),
        )
        dao.upsertItem(targetEntity, markDirty = isSyncable, hlcOverride = hlc, nodeId = nodeId)
        dao.upsertCollectionEntry(
            CollectionEntryEntity(
                collectionId = WANT_TO_READ_COLLECTION_ID,
                entryId = targetEntity.id,
                position = null,
                addedAt = nowIso,
                hlc = hlc,
                isLocalDirty = isSyncable,
            )
        )
        attachTagsLocally(
            dao = dao,
            itemId = targetEntity.id,
            tagNames = tags,
            markDirty = isSyncable,
            nodeId = nodeId,
        )
        return PersistedWantItem(row = targetEntity, wasNewlyInserted = existingItem == null)
    }

    private suspend fun findExistingItemForSavedUrl(
        candidateId: String,
        normalizedUrl: String,
    ): InboxItemRow? {
        val byId = dao.getItemById(candidateId)
        if (byId != null) return byId
        val canonUrl = canonicalizeSyncUrl(normalizedUrl)
        val ownerId =
            canonUrl.takeIf { it.isNotEmpty() }?.let { dao.getEntryIdByIdentifier("url", it) }
        val resolvedId = ownerId?.let { catalog.followMergedId(it) ?: it }
        return resolvedId?.let { dao.getItemById(it) }
    }

    private fun mergeExistingItemOnResave(
        existing: InboxItemRow,
        cleanTitleOverride: String?,
    ): InboxItemRow {
        val resetReadAt =
            if (existing.inboxState.equals("none", ignoreCase = true)) null else existing.readAt
        return existing.copy(
            title = cleanTitleOverride ?: existing.title,
            readAt = resetReadAt,
            interactionUpdatedAt = nowUtcIso(),
        )
    }

    private suspend fun launchWantToReadEnrichment(
        itemId: String,
        url: String,
        hasUserTitleOverride: Boolean,
        pdfInputStream: InputStream?,
    ) {
        val scope = backgroundScope
        if (scope != null) {
            scope.launch {
                if (pdfInputStream != null) {
                    copySharedPdfStreamToCache(dao, cacheDir, itemId, pdfInputStream)
                }
                runSuspendCatching {
                    enrichWantToReadItemOnDevice(
                        dao = dao,
                        publicWebFetcher = publicWebFetcher,
                        itemId = itemId,
                        url = url,
                        hasUserTitleOverride = hasUserTitleOverride,
                        nodeId = currentLocalPeerId(),
                        writeLock = database.writeLock,
                    )
                }
                    .onFailure { err ->
                        RocheLog.warn(LOG_TAG, "Background Want to Read enrichment failed", err)
                    }
                triggerBackgroundSync()
            }
        } else {
            if (pdfInputStream != null) {
                copySharedPdfStreamToCache(dao, cacheDir, itemId, pdfInputStream)
            }
            triggerBackgroundSync()
        }
    }

    suspend fun discoverAndSubscribeFeed(
        inputUrl: String,
        titleOverride: String? = null,
        feedType: FeedType? = null,
    ): Result<SaveUrlResult> = withSyncIndicator {
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val explicitWireType = feedType?.takeIf { it == FeedType.PODCAST }?.wireValue
                val discovered =
                    discoverFeedFromUrl(
                            publicWebFetcher = publicWebFetcher,
                            rawInputUrl = inputUrl,
                            requestedFeedType = explicitWireType,
                        )
                        .getOrThrow()
                val subscribedTitle =
                    subscribeFeedLocal(
                        url = discovered.feedUrl,
                        titleOverride = titleOverride?.trim()?.takeIf { it.isNotBlank() },
                        prefetchedBatch = discovered.prefetchedBatch,
                        discoveredSiteTitle = discovered.siteTitle,
                        requestedFeedType = feedType,
                    )
                triggerBackgroundSync()
                SaveUrlResult(title = subscribedTitle, kind = SavedResourceKind.FEED)
            }
        }
    }

    private suspend fun subscribeFeedLocal(
        url: String,
        titleOverride: String?,
        prefetchedBatch: ParsedFeedBatch? = null,
        discoveredSiteTitle: String? = null,
        requestedFeedType: FeedType? = null,
    ): String {
        val cleanTitleOverride = titleOverride?.trim()?.takeIf { it.isNotBlank() }
        val canonicalUrl = canonicalizeFeedUrl(url)
        val fallbackTitle =
            cleanTitleOverride
                ?: prefetchedBatch?.title?.trim()?.takeIf { it.isNotEmpty() }
                ?: discoveredSiteTitle?.trim()?.takeIf { it.isNotEmpty() }
                ?: extractHostTitle(url)
        val currentSettings = settings.settings.first()
        val nodeId = currentSettings.peerId.ifBlank { DEFAULT_LOCAL_HLC_NODE }
        val prepared =
            upsertOrUnarchiveFeedLocked(
                rawUrl = url,
                canonicalUrl = canonicalUrl,
                cleanTitleOverride = cleanTitleOverride,
                fallbackTitle = fallbackTitle,
                nodeId = nodeId,
                hasPrefetchedBatch = prefetchedBatch != null,
                requestedFeedType = requestedFeedType,
            )
        val subscribedEntity = prepared.first ?: return prepared.second
        LocalFeedPoller.pollFeed(
                dao = dao,
                publicWebFetcher = publicWebFetcher,
                feed = subscribedEntity,
                localPeerId = currentSettings.peerId,
                prefetchedBatch = prefetchedBatch,
                writeLock = database.writeLock,
            )
            .onFailure { err ->
                RocheLog.warn(LOG_TAG, "Initial poll failed for subscribed feed $canonicalUrl", err)
            }
        return dao.getFeedById(subscribedEntity.id)?.title ?: subscribedEntity.title
    }

    @Suppress("LongParameterList")
    private suspend fun upsertOrUnarchiveFeedLocked(
        rawUrl: String,
        canonicalUrl: String,
        cleanTitleOverride: String?,
        fallbackTitle: String,
        nodeId: String,
        hasPrefetchedBatch: Boolean,
        requestedFeedType: FeedType? = null,
    ): Pair<FeedRow?, String> = itemMutationMutex.withLock {
        val isPublic = isSyncablePublicUrl(canonicalUrl)
        val resolvedNewType = resolveNewFeedTypeWire(rawUrl, requestedFeedType)
        val (existingLocal, newFeedId) = resolveFeedSubscriptionTarget(canonicalUrl)
        if (existingLocal != null && !existingLocal.isActive) {
            reactivateArchivedFeedLocked(
                existingLocal = existingLocal,
                cleanTitleOverride = cleanTitleOverride,
                requestedFeedType = requestedFeedType,
                nodeId = nodeId,
                hasPrefetchedBatch = hasPrefetchedBatch,
            )
        } else {
            val entity =
                buildActiveSubscriptionFeedRow(
                    existingLocal = existingLocal,
                    newFeedId = newFeedId,
                    canonicalUrl = canonicalUrl,
                    fallbackTitle = fallbackTitle,
                    cleanTitleOverride = cleanTitleOverride,
                    resolvedNewType = resolvedNewType,
                    requestedFeedType = requestedFeedType,
                    isPublic = isPublic,
                    nodeId = nodeId,
                )
            val hlc = nextLocalHlc(dao, nodeId)
            dao.upsertFeed(
                feed = entity,
                markDirty = isPublic,
                hlcOverride = hlc,
                nodeId = nodeId,
            )
            entity to entity.title
        }
    }

    private suspend fun resolveFeedSubscriptionTarget(
        canonicalUrl: String
    ): Pair<FeedRow?, String> {
        val canonTarget = canonicalizeSyncUrl(canonicalUrl)
        val detId = deterministicFeedId(canonTarget)
        val allFeeds = dao.getAllFeeds()
        val existingByUrl = allFeeds.firstOrNull { canonicalizeSyncUrl(it.url) == canonTarget }
        val existingByDetId = if (existingByUrl == null) dao.getFeedById(detId) else null
        val matchingExisting =
            existingByUrl ?: existingByDetId?.takeIf { canonicalizeSyncUrl(it.url) == canonTarget }
        val newFeedId =
            if (existingByDetId != null && matchingExisting == null) {
                UUID.randomUUID().toString()
            } else {
                detId
            }
        dao.deleteTombstone("feed", feedNaturalKey(canonicalUrl))
        dao.deleteTombstone("feed", matchingExisting?.id ?: newFeedId)
        return matchingExisting to newFeedId
    }

    @Suppress("LongParameterList")
    private suspend fun buildActiveSubscriptionFeedRow(
        existingLocal: FeedRow?,
        newFeedId: String,
        canonicalUrl: String,
        fallbackTitle: String,
        cleanTitleOverride: String?,
        resolvedNewType: String,
        requestedFeedType: FeedType?,
        isPublic: Boolean,
        nodeId: String,
    ): FeedRow {
        if (existingLocal == null) {
            return FeedRow(
                id = newFeedId,
                title = fallbackTitle,
                url = canonicalUrl,
                siteUrl = null,
                feedType = resolvedNewType,
                lastPolled = null,
            )
        }
        val nextFeedType =
            applyFeedTypeIfToggleable(
                existingLocal = existingLocal,
                requestedFeedType = requestedFeedType?.takeIf { it == FeedType.PODCAST },
                markDirty = isPublic,
                nodeId = nodeId,
            )
        return existingLocal.copy(
            title = cleanTitleOverride ?: existingLocal.title,
            feedType = nextFeedType,
            isActive = true,
        )
    }

    private fun resolveNewFeedTypeWire(rawUrl: String, requestedFeedType: FeedType?): String {
        val inferredType = inferFeedTypeWireValue(rawUrl)
        return if (requestedFeedType == FeedType.PODCAST && inferredType == "rss") {
            FeedType.PODCAST.wireValue
        } else {
            inferredType
        }
    }

    private suspend fun reactivateArchivedFeedLocked(
        existingLocal: FeedRow,
        cleanTitleOverride: String?,
        requestedFeedType: FeedType?,
        nodeId: String,
        hasPrefetchedBatch: Boolean,
    ): Pair<FeedRow?, String> {
        val syncable = isSyncablePublicUrl(existingLocal.url)
        dao.updateFeedActiveGraph(
            feedId = existingLocal.id,
            isActive = true,
            markDirty = syncable,
            nodeId = nodeId,
        )
        if (cleanTitleOverride != null && cleanTitleOverride != existingLocal.title) {
            dao.updateFeedTitleGraph(
                feedId = existingLocal.id,
                cleanTitle = cleanTitleOverride,
                markDirty = syncable,
                nodeId = nodeId,
            )
        }
        val nextFeedType =
            applyFeedTypeIfToggleable(
                existingLocal = existingLocal,
                requestedFeedType = requestedFeedType,
                markDirty = syncable,
                nodeId = nodeId,
            )
        val nextTitle = cleanTitleOverride ?: existingLocal.title
        val entityToPoll =
            if (hasPrefetchedBatch) {
                existingLocal.copy(title = nextTitle, feedType = nextFeedType, isActive = true)
            } else {
                null
            }
        return entityToPoll to nextTitle
    }

    private suspend fun applyFeedTypeIfToggleable(
        existingLocal: FeedRow,
        requestedFeedType: FeedType?,
        markDirty: Boolean,
        nodeId: String,
    ): String {
        val currentType = FeedType.fromWireValue(existingLocal.feedType)
        val canToggle =
            requestedFeedType != null &&
                (requestedFeedType == FeedType.RSS || requestedFeedType == FeedType.PODCAST) &&
                (currentType == FeedType.RSS || currentType == FeedType.PODCAST) &&
                currentType != requestedFeedType
        if (!canToggle) return existingLocal.feedType
        dao.updateFeedTypeGraph(
            feedId = existingLocal.id,
            targetFeedType = requestedFeedType.wireValue,
            markDirty = markDirty,
            nodeId = nodeId,
        )
        return requestedFeedType.wireValue
    }

    fun subscribeFeedInBackground(
        inputUrl: String,
        titleOverride: String? = null,
        feedType: FeedType? = null,
        onComplete: (Result<SaveUrlResult>) -> Unit = {},
    ) {
        val scope = backgroundScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val result =
                discoverAndSubscribeFeed(
                    inputUrl = inputUrl,
                    titleOverride = titleOverride,
                    feedType = feedType,
                )
            onComplete(result)
        }
    }

    internal suspend fun discoverServer(
        tailnetDomains: List<String> = emptyList()
    ): Result<String> = runSuspendCatching {
        val savedUrl = settings.settings.first().serverUrl
        val domains = tailnetDomains.ifEmpty { networkTierProvider.activeTailnetDomains() }
        val discovered =
            serverApi.discoverServer(
                tailnetDomains = domains,
                extraCandidates = tailscaleHttpsUpgrades(savedUrl),
            )
                ?: error(
                    "No Roche server found on Tailnet (ensure Tailscale is active and 'roche serve' is running)"
                )
        val preferredUrl = normalizeServerUrl(discovered.preferredUrl)
        check(preferredUrl.isNotBlank()) { "Discovered server returned an invalid URL" }
        settings.update { current ->
            val preservedToken =
                if (keepTokenOnDiscovery(current.serverUrl, preferredUrl)) {
                    current.bearerToken
                } else {
                    ""
                }
            current.copy(serverUrl = preferredUrl, bearerToken = preservedToken)
        }
        preferredUrl
    }

    suspend fun pairWithCode(
        serverUrl: String,
        pairingCode: String,
        deviceName: String,
    ): Result<String> = runSuspendCatching {
        val (parsedUrl, cleanCode) = parsePairingInput(serverUrl, pairingCode)
        check(cleanCode.isNotBlank()) { "Enter a valid pairing code" }
        val resolvedUrl =
            if (parsedUrl.isNotBlank()) {
                parsedUrl
            } else {
                val savedUrl = normalizeServerUrl(settings.settings.first().serverUrl)
                if (
                    savedUrl.startsWith("https://", ignoreCase = true) ||
                        savedUrl.startsWith("http://localhost", ignoreCase = true) ||
                        savedUrl.startsWith("http://127.", ignoreCase = true)
                ) {
                    savedUrl
                } else {
                    serverApi
                        .discoverServer(
                            tailnetDomains = networkTierProvider.activeTailnetDomains(),
                            extraCandidates = tailscaleHttpsUpgrades(savedUrl),
                        )
                        ?.let { normalizeServerUrl(it.preferredUrl) }
                        .orEmpty()
                }
            }
        check(resolvedUrl.isNotBlank()) {
            "Could not auto-discover Roche server on Tailnet; paste the full https://...#code bundle"
        }
        val token =
            serverApi.pairDevice(
                serverUrl = resolvedUrl,
                code = cleanCode,
                deviceName = deviceName.trim().ifBlank { "Roche Android" },
            )
        settings.update { it.copy(serverUrl = resolvedUrl, bearerToken = token) }
        recordSyncStatus(
            lastSyncedAtIso = _syncStatus.value.lastSyncedAtIso,
            lastError = null,
            isAuthError = false,
        )
        token
    }

    private inline fun <T> withSyncIndicator(block: () -> T): T {
        if (activeSyncCount.getAndIncrement() == 0) {
            _isSyncActive.value = true
        }
        return try {
            block()
        } finally {
            if (activeSyncCount.decrementAndGet() <= 0) {
                activeSyncCount.set(0)
                _isSyncActive.value = false
            }
        }
    }

    suspend fun syncWithServer(): Result<Int> = withSyncIndicator {
        withContext(Dispatchers.Default) {
            syncMutex.withLock {
                val result = runSuspendCatching {
                    catalog.ensureSystemSeeded()
                    val currentSettings = settings.settings.first()
                    val currentTier = networkTierProvider.currentNetworkTier()
                    if (currentSettings.serverUrl.isBlank()) {
                        check(currentTier != NetworkTier.OFFLINE) { "Device is currently offline" }
                        val pollOutcome =
                            pollCooperativeFeedsOnDevice(
                                dao = dao,
                                publicWebFetcher = publicWebFetcher,
                                localPeerId = currentSettings.peerId,
                                activePeers = emptyList(),
                                writeLock = database.writeLock,
                            )
                        offlineCacheManager.autoDownloadNewEpisodes()
                        pollOutcome.polledFeedsCount
                    } else {
                        check(currentSettings.bearerToken.isNotBlank()) {
                            "Pair device in Settings before syncing"
                        }
                        val syncedCount =
                            peerSyncCoordinator(currentTier).syncAndCooperativePoll(currentSettings)
                                ?: error("Peer sync endpoint unavailable on server")
                        offlineCacheManager.autoDownloadNewEpisodes()
                        syncedCount
                    }
                }
                result
                    .onSuccess {
                        recordSyncStatus(
                            lastSyncedAtIso = nowUtcIso(),
                            lastError = null,
                            isAuthError = false,
                        )
                    }
                    .onFailure { err ->
                        RocheLog.warn(LOG_TAG, "Sync failed", err)
                        recordSyncStatus(
                            lastSyncedAtIso = _syncStatus.value.lastSyncedAtIso,
                            lastError = err.message ?: "Sync failed",
                            isAuthError = isAuthFailure(err),
                        )
                    }
                result
            }
        }
    }

    private suspend fun recordSyncStatus(
        lastSyncedAtIso: String?,
        lastError: String?,
        isAuthError: Boolean,
    ) {
        val next =
            SyncStatusState(
                lastSyncedAtIso = lastSyncedAtIso,
                lastError = lastError,
                isAuthError = isAuthError,
            )
        _syncStatus.value = next
        runSuspendCatching {
            if (lastSyncedAtIso != null) {
                dao.upsertLocalSyncFlag(
                    LocalSyncFlagEntity(FLAG_LAST_SYNCED_AT_ISO, lastSyncedAtIso)
                )
            }
            if (lastError != null) {
                dao.upsertLocalSyncFlag(LocalSyncFlagEntity(FLAG_LAST_SYNC_ERROR, lastError))
            } else {
                dao.deleteLocalSyncFlag(FLAG_LAST_SYNC_ERROR)
            }
            if (isAuthError) {
                dao.upsertLocalSyncFlag(LocalSyncFlagEntity(FLAG_LAST_SYNC_AUTH_ERROR, "1"))
            } else {
                dao.deleteLocalSyncFlag(FLAG_LAST_SYNC_AUTH_ERROR)
            }
        }
            .onFailure { err -> RocheLog.warn(LOG_TAG, "Failed persisting sync status", err) }
    }

    suspend fun pollDueFeedsOnDeviceNow(): Result<Int> = withSyncIndicator {
        withContext(Dispatchers.Default) {
            syncMutex.withLock {
                runSuspendCatching {
                    catalog.ensureSystemSeeded()
                    val currentTier = networkTierProvider.currentNetworkTier()
                    check(currentTier != NetworkTier.OFFLINE) { "Device is currently offline" }
                    val currentSettings = settings.settings.first()
                    val pollOutcome =
                        pollCooperativeFeedsOnDevice(
                            dao = dao,
                            publicWebFetcher = publicWebFetcher,
                            localPeerId = currentSettings.peerId,
                            activePeers = activePeersFlow.value,
                            writeLock = database.writeLock,
                        )
                    if (
                        pollOutcome.polledFeedsCount > 0 &&
                            currentSettings.serverUrl.isNotBlank() &&
                            currentSettings.bearerToken.isNotBlank()
                    ) {
                        runSuspendCatching {
                            peerSyncCoordinator(currentTier)
                                .syncAndCooperativePoll(
                                    currentSettings = currentSettings,
                                    initialWeightOverride = pollOutcome.measuredWeight,
                                )
                        }
                            .onFailure { err ->
                                RocheLog.warn(LOG_TAG, "Post-poll sync failed", err)
                            }
                    }
                    offlineCacheManager.autoDownloadNewEpisodes()
                    pollOutcome.polledFeedsCount
                }
            }
        }
    }

    suspend fun runBackgroundPeerSyncAndPoll(): Result<Int> {
        val currentTier = networkTierProvider.currentNetworkTier()
        if (currentTier == NetworkTier.OFFLINE) return Result.success(0)
        val currentSettings = settings.settings.first()
        if (
            currentSettings.serverUrl.isNotBlank() &&
                currentSettings.bearerToken.isNotBlank() &&
                isServerSyncAllowedByTransport(currentSettings.serverUrl)
        ) {
            val syncResult = syncWithServer()
            if (syncResult.isSuccess) return syncResult
        }
        return withSyncIndicator {
            syncMutex.withLock {
                runSuspendCatching {
                    catalog.ensureSystemSeeded()
                    pollCooperativeFeedsOnDevice(
                            dao = dao,
                            publicWebFetcher = publicWebFetcher,
                            localPeerId = currentSettings.peerId,
                            activePeers = activePeersFlow.value,
                            writeLock = database.writeLock,
                        )
                        .polledFeedsCount
                }
            }
        }
    }

    private suspend fun currentLocalPeerId(): String =
        settings.settings.first().peerId.ifBlank { DEFAULT_LOCAL_HLC_NODE }

    private fun triggerBackgroundSync() {
        val scope = backgroundScope ?: return
        backgroundSyncPending.set(true)
        if (!backgroundSyncRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (backgroundSyncPending.compareAndSet(true, false)) {
                    if (canRunBackgroundSync()) {
                        syncWithServer()
                    } else {
                        backgroundSyncPending.set(false)
                    }
                }
            } finally {
                backgroundSyncRunning.set(false)
                if (backgroundSyncPending.get()) {
                    triggerBackgroundSync()
                }
            }
        }
    }

    private suspend fun canRunBackgroundSync(): Boolean {
        if (currentNetworkTier() == NetworkTier.OFFLINE) return false
        val current = settings.settings.first()
        return current.serverUrl.isNotBlank() &&
            current.bearerToken.isNotBlank() &&
            isServerSyncAllowedByTransport(current.serverUrl)
    }

    private fun isServerSyncAllowedByTransport(serverUrl: String): Boolean =
        PublicUrlValidator.isServerEndpointAllowed(
            rawServerUrl = serverUrl,
            isVpnActive = networkTierProvider::isVpnTransportActive,
        )

    private fun peerSyncCoordinator(networkTier: NetworkTier): PeerSyncCoordinator =
        PeerSyncCoordinator(
            dao = dao,
            serverApi = serverApi,
            publicWebFetcher = publicWebFetcher,
            networkTier = networkTier,
            catalogStore = catalog,
            writeLock = database.writeLock,
            onPeersUpdated = { activePeersFlow.value = it },
            onSyncCursorUpdated = { cursor ->
                settings.update { it.copy(lastPeerSyncCursor = cursor) }
            },
        )

    internal suspend fun markFeedItemFinished(itemId: String): String? {
        var evictAudioCache = false
        var archivedNow = false
        val result =
            itemMutationMutex.withLock {
                dao.getItemById(itemId)?.let { item ->
                    if (ItemStatus.fromWireValue(item.status) != ItemStatus.ARCHIVED) {
                        val (snap, shouldEvict) =
                            archiveWithSnapshotLocked(itemId = itemId, archived = true)
                        archivedNow = snap != null
                        evictAudioCache = shouldEvict
                    }
                    catalog.logFeedItemCompletion(itemId)
                }
            } ?: return null
        if (evictAudioCache) {
            offlineCacheManager.setOfflineCacheState(itemId = itemId, enabled = false)
        }
        if (archivedNow) {
            triggerBackgroundSync()
        }
        return result
    }

    internal suspend fun prepareFeedItemForRank(itemId: String): Pair<String, EntryKind>? {
        if (dao.getItemById(itemId) == null) return null
        val resolvedId = catalog.followMergedId(itemId) ?: itemId
        val kind =
            dao.getEntryById(resolvedId)?.let { EntryKind.fromWireValue(it.kind) }
                ?: EntryKind.ARTICLE
        return resolvedId to kind
    }

    private suspend fun maybeAutoLogFeedItemCompletion(
        itemId: String,
        fromAudioPlayback: Boolean? = null,
    ) {
        val state = dao.getItemStateById(itemId) ?: return
        val isPodcastOrAudioOnly =
            state.entryKind.equals(ENTRY_KIND_PODCAST_EPISODE, ignoreCase = true) ||
                state.contentType.equals(AUDIO_CONTENT_TYPE, ignoreCase = true)
        val hasAudio = isPodcastOrAudioOnly || !state.audioUrl.isNullOrBlank()
        val isVideo = !hasAudio && state.contentType.equals("video", ignoreCase = true)
        if (isVideo) return
        val isArchived = ItemStatus.fromWireValue(state.status) == ItemStatus.ARCHIVED
        val allowAudioAutoComplete =
            when (fromAudioPlayback) {
                false -> false
                true -> hasAudio
                null -> isPodcastOrAudioOnly
            }
        val shouldLog =
            if (allowAudioAutoComplete) {
                state.readProgress >= AUDIO_COMPLETE_PROGRESS_THRESHOLD ||
                    isArchived && state.readProgress >= ARCHIVE_COMPLETE_PROGRESS_THRESHOLD
            } else {
                isArchived && state.readProgress >= ARCHIVE_COMPLETE_PROGRESS_THRESHOLD
            }
        if (!shouldLog) return
        catalog.logItemCompletionIfUnlogged(itemId)
    }

    companion object {
        fun filterEntries(
            entries: List<InboxEntry>,
            filter: InboxFilter,
            searchQuery: String = "",
            selectedTag: String? = null,
            selectedFeedId: String? = null,
        ): List<InboxEntry> =
            filterInboxEntries(
                entries = entries,
                filter = filter,
                searchQuery = searchQuery,
                selectedTag = selectedTag,
                selectedFeedId = selectedFeedId,
            )
    }
}

private const val LOG_TAG = "RocheRepository"

internal fun missingFeedError(feedId: String): Nothing = error("Feed not found: $feedId")

private const val FLAG_LAST_SYNCED_AT_ISO = "last_synced_at_iso"
private const val FLAG_LAST_SYNC_ERROR = "last_sync_error"
private const val FLAG_LAST_SYNC_AUTH_ERROR = "last_sync_auth_error"
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val AUDIO_CONTENT_TYPE = "audio"
private const val MIN_READ_PROGRESS = 0.0
private const val MAX_READ_PROGRESS = 1.0
private const val ARCHIVE_COMPLETE_PROGRESS_THRESHOLD = 0.9
private const val AUDIO_COMPLETE_PROGRESS_THRESHOLD = 0.98

internal fun isAuthFailure(error: Throwable): Boolean =
    when (error) {
        is ProblemDetailsException ->
            error.statusCode == HTTP_UNAUTHORIZED || error.statusCode == HTTP_FORBIDDEN
        is HttpStatusException ->
            error.statusCode == HTTP_UNAUTHORIZED || error.statusCode == HTTP_FORBIDDEN
        else -> false
    }

internal fun sanitizeSnippetExcerpt(raw: String): String =
    raw.replace(SNIPPET_HTML_TAG_REGEX, " ").replace(SNIPPET_WHITESPACE_REGEX, " ").trim()

private val SNIPPET_HTML_TAG_REGEX = Regex("""<[^>]*>""")
private val SNIPPET_WHITESPACE_REGEX = Regex("""\s+""")

internal suspend fun resolveArchiveBatchTargetIds(
    dao: RocheDao,
    entryIds: List<String>,
    feedId: String?,
    allUnread: Boolean,
): Set<String> {
    if (entryIds.isNotEmpty()) return entryIds.toSet()
    if (!feedId.isNullOrBlank()) return dao.getUnarchivedItemIdsForFeed(feedId).toSet()
    if (allUnread) {
        return dao.getUnarchivedItemStates()
            .filter { it.readAt.isNullOrBlank() }
            .mapTo(HashSet()) { it.id }
    }
    return emptySet()
}

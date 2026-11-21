package com.grayvines.roche.data

import com.grayvines.roche.core.DEFAULT_LOCAL_HLC_NODE
import com.grayvines.roche.core.TOMBSTONE_ENTITY_COLLECTION_ENTRY
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.core.canonicalizeSyncUrl
import com.grayvines.roche.core.collectionEntryNaturalKey
import com.grayvines.roche.core.deterministicTagId
import com.grayvines.roche.core.extractHostTitle
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
import com.grayvines.roche.data.feeds.copySharedPdfStreamToCache
import com.grayvines.roche.data.feeds.discoverFeedFromUrl
import com.grayvines.roche.data.feeds.enrichWantToReadItemOnDevice
import com.grayvines.roche.data.feeds.normalizeUserSubmittedUrl
import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.InboxItemRow
import com.grayvines.roche.data.local.ItemTagRow
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.TagRow
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.data.local.buildSqlContainsPattern
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
import com.grayvines.roche.data.local.upsertItem
import com.grayvines.roche.data.local.upsertTag
import com.grayvines.roche.data.remote.PublicUrlValidator
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.data.settings.clampPlaybackSpeed
import com.grayvines.roche.data.settings.keepTokenOnDiscovery
import com.grayvines.roche.data.settings.normalizeServerUrl
import com.grayvines.roche.data.settings.parsePairingInput
import com.grayvines.roche.data.settings.tailscaleHttpsUpgrades
import com.grayvines.roche.data.sync.NetworkTier
import com.grayvines.roche.data.sync.NetworkTierProvider
import com.grayvines.roche.data.sync.PeerNodeDto
import com.grayvines.roche.data.sync.PeerSyncCoordinator
import com.grayvines.roche.data.sync.buildLocalOpmlXml
import com.grayvines.roche.data.sync.deleteFeedFromServerOrLocal
import com.grayvines.roche.data.sync.pollCooperativeFeedsOnDevice
import com.grayvines.roche.data.sync.refreshFeedOnlineOrOnDevice
import com.grayvines.roche.data.sync.subscribeFeedBestEffort
import com.grayvines.roche.media.OfflineCacheManager
import com.grayvines.roche.media.ThumbnailDiskCache
import com.grayvines.roche.media.resolveCachedAudioFile
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.model.TagItem
import com.grayvines.roche.model.inferContentType
import java.io.File
import java.io.InputStream
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
    private val apiClient: RocheApiClient,
    private val settings: SettingsRepository,
    private val cacheDir: File? = null,
    private val networkTierProvider: NetworkTierProvider = NetworkTierProvider { NetworkTier.WIFI },
    private val backgroundScope: CoroutineScope? = null,
    private val albumStreamingResolver: AlbumStreamingResolver? = null,
    private val thumbnailDiskCache: ThumbnailDiskCache = ThumbnailDiskCache(cacheDir),
    catalogStore: CatalogStore? = null,
) {
    private val dao = database.dao()
    private val syncMutex = Mutex()
    private val itemMutationMutex = Mutex()
    private val backgroundSyncRunning = AtomicBoolean(false)
    private val backgroundSyncPending = AtomicBoolean(false)
    private val activeSyncCount = AtomicInteger(0)
    private val _isSyncActive = MutableStateFlow(false)
    val isSyncActive: StateFlow<Boolean> = _isSyncActive.asStateFlow()
    private val entryCache = InboxEntryCache()
    private val activePeersFlow = MutableStateFlow<List<PeerNodeDto>>(emptyList())
    internal val hasBackgroundScope: Boolean
        get() = backgroundScope != null

    val catalog: CatalogStore =
        (catalogStore ?: CatalogStore(dao = dao, localPeerIdProvider = ::currentLocalPeerId))
            .also { store -> store.setOnLocalMutation(::triggerBackgroundSync) }
    @Volatile
    internal var latestSnapshot: RepositorySnapshot? = null
        private set

    private val offlineCacheManager =
        OfflineCacheManager(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            cacheDir = cacheDir,
            thumbnailDiskCache = thumbnailDiskCache,
        )

    internal val unifiedSnapshotLoader =
        UnifiedSnapshotLoader(dao = dao, catalog = catalog, entryCache = entryCache)

    @OptIn(ExperimentalCoroutinesApi::class)
    val snapshot: Flow<RepositorySnapshot> =
        combine(
                dao.observeRepositoryStamp()
                    .onStart { catalog.ensureSystemSeeded() }
                    .distinctUntilChanged(),
                activePeersFlow,
            ) { stamp, activePeers ->
                stamp to activePeers
            }
            .conflate()
            .mapLatest { (_, activePeers) ->
                unifiedSnapshotLoader.loadSnapshot(activePeers).also { latestSnapshot = it }
            }
            .flowOn(Dispatchers.Default)

    private val prewarmedAlbumKeys: MutableSet<String> =
        java.util.Collections.synchronizedSet(HashSet())

    suspend fun prewarm(): RepositorySnapshot = snapshot.first()

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
        withContext(Dispatchers.Default) {
            val cleanQuery = rawQuery.replace("\u0000", "").trim().trimStart('#').trim()
            if (cleanQuery.isBlank()) {
                emptyList()
            } else {
                val likePattern = buildSqlContainsPattern(cleanQuery)
                val tokenQueries = extractFtsTokenQueries(cleanQuery)
                val matchedItems =
                    dao.searchFullItemsByTokens(
                        tokenQueries = tokenQueries,
                        likePattern = likePattern,
                    )
                if (matchedItems.isEmpty()) {
                    emptyList()
                } else {
                    val tagNamesByItemId =
                        groupSortedTagNamesByItemId(dao.getAllTags(), dao.getAllItemTags())
                    matchedItems.map { entity ->
                        toInboxEntry(entity, tagNamesByItemId[entity.id].orEmpty())
                    }
                }
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
        val current = dao.getItemStateById(itemId) ?: return null to false
        val previous = ItemStatus.fromWireValue(current.status)
        val snapshot =
            ArchiveStateSnapshot(
                itemId = itemId,
                previousStatus = previous,
                previousReadAt = current.readAt,
            )
        val newStatus = if (archived) ItemStatus.ARCHIVED else ItemStatus.UNREAD
        val isAudioItem =
            current.contentType.equals(AUDIO_CONTENT_TYPE, ignoreCase = true) ||
                !current.audioUrl.isNullOrBlank()
        val newReadAt =
            if (archived) {
                if (isAudioItem) {
                    nowUtcIso()
                } else {
                    current.readAt?.takeIf { it.isNotBlank() } ?: nowUtcIso()
                }
            } else {
                null
            }
        dao.setItemStatusAndReadAt(itemId, newStatus.wireValue, newReadAt, currentLocalPeerId())
        if (archived) {
            maybeAutoLogFeedItemCompletion(itemId)
        }
        return snapshot to (archived && current.isCachedOffline && isAudioItem)
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
        dao.updateFeedAutoDownload(feedId = feedId, enabled = enabled)
        if (enabled) {
            offlineCacheManager.autoDownloadNewEpisodes()
        }
    }

    suspend fun setFeedPlaybackSpeed(feedId: String, speed: Float?) {
        val clamped = speed?.takeIf { it.isFinite() }?.let(::clampPlaybackSpeed)
        dao.updateFeedPlaybackSpeed(feedId = feedId, speed = clamped)
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
                    apiClient.importOpml(
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
                        apiClient.exportOpml(
                            serverUrl = currentSettings.serverUrl,
                            token = currentSettings.bearerToken,
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
    ) {
        if (!progress.isFinite()) return
        val updated = itemMutationMutex.withLock {
            if (dao.getItemStateById(itemId) == null) {
                false
            } else {
                val clamped = progress.coerceIn(MIN_READ_PROGRESS, MAX_READ_PROGRESS)
                val explicitReadAt = if (recordPlayedTimestamp) nowUtcIso() else null
                dao.setItemReadProgress(itemId, clamped, explicitReadAt, currentLocalPeerId())
                maybeAutoLogFeedItemCompletion(itemId)
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
        val existing = if (cleanTitle.isNotBlank()) dao.getFeedById(feedId) else null
        if (existing == null) return null
        if (existing.title != cleanTitle) {
            dao.updateFeedTitleGraph(
                feedId = feedId,
                cleanTitle = cleanTitle,
                markDirty = isSyncablePublicUrl(existing.url),
                nodeId = currentLocalPeerId(),
            )
            triggerBackgroundSync()
        }
        return cleanTitle
    }

    suspend fun toggleFeedArchive(feedId: String): Result<Pair<String, Boolean>> =
        runSuspendCatching {
            val existing = dao.getFeedById(feedId) ?: error("Feed not found: $feedId")
            val nextIsActive = !existing.isActive
            dao.updateFeedActiveGraph(
                feedId = feedId,
                isActive = nextIsActive,
                markDirty = isSyncablePublicUrl(existing.url),
                nodeId = currentLocalPeerId(),
            )
            triggerBackgroundSync()
            existing.title.ifBlank { feedId } to !nextIsActive
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
                val title =
                    refreshFeedOnlineOrOnDevice(
                        dao = dao,
                        apiClient = apiClient,
                        settings = settings,
                        feedId = feedId,
                        onSyncWithServer = { syncWithServer() },
                    )
                offlineCacheManager.autoDownloadNewEpisodes()
                title
            }
        }
    }

    suspend fun deleteFeed(feedId: String): Result<String> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val deletedTitle =
                    deleteFeedFromServerOrLocal(
                        dao = dao,
                        apiClient = apiClient,
                        settings = settings,
                        offlineCacheManager = offlineCacheManager,
                        feedId = feedId,
                    )
                triggerBackgroundSync()
                deletedTitle
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
    ): SaveUrlResult =
        withContext(Dispatchers.Default) {
            val trimmedUrl = normalizeUserSubmittedUrl(inputUrl)
            if (isLikelyFeedUrl(trimmedUrl)) {
                val feedTitle =
                    subscribeFeedBestEffort(
                        dao = dao,
                        apiClient = apiClient,
                        settings = settings,
                        url = trimmedUrl,
                        titleOverride = titleOverride,
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
                        apiClient = apiClient,
                        itemId = itemId,
                        url = url,
                        hasUserTitleOverride = hasUserTitleOverride,
                        nodeId = currentLocalPeerId(),
                    )
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
    ): Result<SaveUrlResult> = withSyncIndicator {
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val discovered = discoverFeedFromUrl(apiClient, inputUrl).getOrThrow()
                val subscribedTitle =
                    subscribeFeedBestEffort(
                        dao = dao,
                        apiClient = apiClient,
                        settings = settings,
                        url = discovered.feedUrl,
                        titleOverride = titleOverride?.trim()?.takeIf { it.isNotBlank() },
                        prefetchedBatch = discovered.prefetchedBatch,
                        discoveredSiteTitle = discovered.siteTitle,
                    )
                triggerBackgroundSync()
                SaveUrlResult(title = subscribedTitle, kind = SavedResourceKind.FEED)
            }
        }
    }

    fun subscribeFeedInBackground(
        inputUrl: String,
        titleOverride: String? = null,
        onComplete: (Result<SaveUrlResult>) -> Unit = {},
    ) {
        val scope = backgroundScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val result =
                discoverAndSubscribeFeed(inputUrl = inputUrl, titleOverride = titleOverride)
            onComplete(result)
        }
    }

    internal suspend fun discoverServer(
        tailnetDomains: List<String> = emptyList()
    ): Result<String> = runSuspendCatching {
        val savedUrl = settings.settings.first().serverUrl
        val domains = tailnetDomains.ifEmpty { networkTierProvider.activeTailnetDomains() }
        val discovered =
            apiClient.discoverServer(
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
                    apiClient
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
            apiClient.pairDevice(
                serverUrl = resolvedUrl,
                code = cleanCode,
                deviceName = deviceName.trim().ifBlank { "Roche Android" },
            )
        settings.update { it.copy(serverUrl = resolvedUrl, bearerToken = token) }
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
                runSuspendCatching {
                    val currentSettings = settings.settings.first()
                    val currentTier = networkTierProvider.currentNetworkTier()
                    if (currentSettings.serverUrl.isBlank()) {
                        check(currentTier != NetworkTier.OFFLINE) { "Device is currently offline" }
                        val pollOutcome =
                            pollCooperativeFeedsOnDevice(
                                dao = dao,
                                apiClient = apiClient,
                                localPeerId = currentSettings.peerId,
                                activePeers = emptyList(),
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
            }
        }
    }

    suspend fun pollDueFeedsOnDeviceNow(): Result<Int> = withSyncIndicator {
        withContext(Dispatchers.Default) {
            syncMutex.withLock {
                runSuspendCatching {
                    val currentTier = networkTierProvider.currentNetworkTier()
                    check(currentTier != NetworkTier.OFFLINE) { "Device is currently offline" }
                    val currentSettings = settings.settings.first()
                    val pollOutcome =
                        pollCooperativeFeedsOnDevice(
                            dao = dao,
                            apiClient = apiClient,
                            localPeerId = currentSettings.peerId,
                            activePeers = activePeersFlow.value,
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
                    pollCooperativeFeedsOnDevice(
                            dao = dao,
                            apiClient = apiClient,
                            localPeerId = currentSettings.peerId,
                            activePeers = activePeersFlow.value,
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
            apiClient = apiClient,
            networkTier = networkTier,
            catalogStore = catalog,
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

    private suspend fun maybeAutoLogFeedItemCompletion(itemId: String) {
        val state = dao.getItemStateById(itemId) ?: return
        val isAudio =
            state.contentType.equals(AUDIO_CONTENT_TYPE, ignoreCase = true) ||
                !state.audioUrl.isNullOrBlank()
        val isVideo = !isAudio && state.contentType.equals("video", ignoreCase = true)
        if (isVideo) return
        val isArchived = ItemStatus.fromWireValue(state.status) == ItemStatus.ARCHIVED
        val shouldLog =
            if (isAudio) {
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

private const val AUDIO_CONTENT_TYPE = "audio"
private const val MIN_READ_PROGRESS = 0.0
private const val MAX_READ_PROGRESS = 1.0
private const val ARCHIVE_COMPLETE_PROGRESS_THRESHOLD = 0.9
private const val AUDIO_COMPLETE_PROGRESS_THRESHOLD = 0.98

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

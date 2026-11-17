package com.grayvines.roche.data

import com.grayvines.roche.data.local.InboxItemRow
import com.grayvines.roche.data.local.ItemTagRow
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.TagRow
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.data.local.buildSqlContainsPattern
import com.grayvines.roche.data.local.deleteEntryCascade
import com.grayvines.roche.data.local.deleteItemPreservingHistory
import com.grayvines.roche.data.local.extractFtsTokenQueries
import com.grayvines.roche.data.local.insertItemTag
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
import com.grayvines.roche.data.remote.RemoteItemDto
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.data.settings.keepTokenOnDiscovery
import com.grayvines.roche.data.settings.normalizeServerUrl
import com.grayvines.roche.data.settings.parsePairingInput
import com.grayvines.roche.data.settings.tailscaleHttpsUpgrades
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.model.TagItem
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
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
) {
    private val dao = database.dao()
    private val syncMutex = Mutex()
    private val itemMutationMutex = Mutex()
    private val backgroundSyncRunning = AtomicBoolean(false)
    private val backgroundSyncPending = AtomicBoolean(false)
    private val entryCache = InboxEntryCache()
    private val activePeersFlow = MutableStateFlow<List<PeerNodeDto>>(emptyList())
    val catalog: CatalogStore =
        CatalogStore(
            dao = dao,
            onLocalMutation = ::triggerBackgroundSync,
        )
    @Volatile
    internal var latestSnapshot: RepositorySnapshot? = null
        private set

    private val offlineCacheManager =
        OfflineCacheManager(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            cacheDir = cacheDir,
        )

    private val inboxSnapshotFlow: Flow<RepositorySnapshot> =
        combine(
            dao.observeItems(),
            dao.observeFeeds(),
            dao.observeTags(),
            dao.observeItemTags(),
            activePeersFlow,
        ) { items, feeds, tags, itemTags, activePeers ->
            buildSnapshot(
                items = items,
                feeds = feeds,
                tags = tags,
                itemTags = itemTags,
                activePeers = activePeers,
                entryCache = entryCache,
            )
        }

    val snapshot: Flow<RepositorySnapshot> =
        combine(
                inboxSnapshotFlow,
                catalog.snapshot.onStart { catalog.ensureSystemSeeded() },
            ) { base, catalogSnap ->
                base.copy(catalog = catalogSnap).also { latestSnapshot = it }
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
                    val tagsById = dao.getAllTags().associateBy { it.id }
                    val tagNamesByItemId =
                        dao.getAllItemTags()
                            .groupBy(
                                keySelector = { it.itemId },
                                valueTransform = { tagsById[it.tagId]?.name },
                            )
                            .mapValues { (_, names) ->
                                names
                                    .filterNotNull()
                                    .sortedWith(
                                        compareBy<String, String>(String.CASE_INSENSITIVE_ORDER) {
                                                it
                                            }
                                            .thenBy { it }
                                    )
                            }
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
        val current = dao.getItemStateById(itemId) ?: return null
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
                    Instant.now().toString()
                } else {
                    current.readAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString()
                }
            } else {
                null
            }
        dao.setItemStatusAndReadAt(itemId, newStatus.wireValue, newReadAt, currentLocalPeerId())
        if (archived) {
            maybeAutoLogFeedItemCompletion(itemId)
        }
        if (archived && current.isCachedOffline && isAudioItem) {
            offlineCacheManager.setOfflineCacheState(itemId = itemId, enabled = false)
        }
        triggerBackgroundSync()
        return snapshot
    }

    internal suspend fun restoreStatus(
        itemId: String,
        status: ItemStatus,
        readAt: String? = null,
    ) {
        val current = dao.getItemStateById(itemId) ?: return
        val targetReadAt =
            if (status == ItemStatus.ARCHIVED) {
                readAt?.takeIf { it.isNotBlank() }
                    ?: current.readAt?.takeIf { it.isNotBlank() }
                    ?: Instant.now().toString()
            } else {
                readAt?.takeIf { it.isNotBlank() }
            }
        dao.setItemStatusAndReadAt(itemId, status.wireValue, targetReadAt, currentLocalPeerId())
        triggerBackgroundSync()
    }

    internal suspend fun restoreArchiveSnapshots(snapshots: List<ArchiveStateSnapshot>) {
        for (snapshot in snapshots) {
            restoreStatus(
                itemId = snapshot.itemId,
                status = snapshot.previousStatus,
                readAt = snapshot.previousReadAt,
            )
        }
    }

    suspend fun setReadState(itemId: String, isRead: Boolean) {
        val current = dao.getItemStateById(itemId) ?: return
        val currentlyRead = !current.readAt.isNullOrBlank()
        if (currentlyRead == isRead) return
        val nextReadAt = if (isRead) Instant.now().toString() else null
        dao.setItemReadAt(itemId, nextReadAt, currentLocalPeerId())
        triggerBackgroundSync()
    }

    suspend fun toggleReadState(itemId: String): Boolean {
        val current = dao.getItemStateById(itemId) ?: return false
        val nextIsRead = current.readAt.isNullOrBlank()
        val nextReadAt = if (nextIsRead) Instant.now().toString() else null
        dao.setItemReadAt(itemId, nextReadAt, currentLocalPeerId())
        triggerBackgroundSync()
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
    ): List<ArchiveStateSnapshot> {
        val targetIds = resolveArchiveBatchTargetIds(dao, entryIds, feedId, allUnread)
        val unarchivedItems = dao.getUnarchivedItemStates().filter { it.id in targetIds }
        if (unarchivedItems.isEmpty()) return emptyList()

        val nowIso = Instant.now().toString()
        val nodeId = currentLocalPeerId()
        val snapshots = unarchivedItems.map { item ->
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
        for (snap in snapshots) {
            val state = dao.getItemStateById(snap.itemId) ?: continue
            if (
                state.isCachedOffline &&
                    (state.contentType.equals(AUDIO_CONTENT_TYPE, ignoreCase = true) ||
                        !state.audioUrl.isNullOrBlank())
            ) {
                offlineCacheManager.setOfflineCacheState(itemId = state.id, enabled = false)
            }
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
        val clamped =
            speed
                ?.takeIf { it.isFinite() }
                ?.let {
                    (kotlin.math.round(it * PLAYBACK_SPEED_STEP) / PLAYBACK_SPEED_STEP).coerceIn(
                        MIN_SPEED,
                        MAX_SPEED,
                    )
                }
        dao.updateFeedPlaybackSpeed(feedId = feedId, speed = clamped)
    }

    fun resolveAudioPlaybackSource(entry: InboxEntry): String =
        resolveCachedAudioFile(cacheDir = cacheDir, itemId = entry.id)?.absolutePath
            ?: entry.audioUrl?.takeIf { it.isNotBlank() }
            ?: entry.url

    internal suspend fun importOpml(opmlXml: String): Result<Int> = runSuspendCatching {
        val trimmed = opmlXml.trim()
        check(trimmed.isNotBlank()) { "OPML file is empty" }
        val currentSettings = settings.settings.first()
        check(currentSettings.serverUrl.isNotBlank() && currentSettings.bearerToken.isNotBlank()) {
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

    internal suspend fun exportOpml(): Result<String> = runSuspendCatching {
        val currentSettings = settings.settings.first()
        if (currentSettings.serverUrl.isNotBlank() && currentSettings.bearerToken.isNotBlank()) {
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

    internal suspend fun updateReadProgress(
        itemId: String,
        progress: Double,
        recordPlayedTimestamp: Boolean = false,
        syncRemote: Boolean = true,
    ) {
        if (!progress.isFinite() || dao.getItemStateById(itemId) == null) return
        val clamped = progress.coerceIn(MIN_READ_PROGRESS, MAX_READ_PROGRESS)
        val explicitReadAt = if (recordPlayedTimestamp) Instant.now().toString() else null
        dao.setItemReadProgress(itemId, clamped, explicitReadAt, currentLocalPeerId())
        maybeAutoLogFeedItemCompletion(itemId)
        if (syncRemote) {
            triggerBackgroundSync()
        }
    }

    internal suspend fun recordEpisodePlaybackStarted(itemId: String) {
        if (dao.getItemStateById(itemId) == null) return
        val nowIso = Instant.now().toString()
        dao.setItemReadAt(itemId, nowIso, currentLocalPeerId())
        triggerBackgroundSync()
    }

    internal suspend fun toggleTagOnItem(itemId: String, tagName: String) {
        val normalized = normalizeTagName(tagName)
        if (normalized.isBlank() || dao.getItemStateById(itemId) == null) return
        val nodeId = currentLocalPeerId()
        val existingTag =
            dao.getTagByName(normalized)
                ?: TagRow(id = deterministicTagId(normalized), name = normalized).also {
                    dao.upsertTag(it, markDirty = true)
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
            dao.insertItemTag(ItemTagRow(itemId = itemId, tagId = existingTag.id), markDirty = true)
        }
        triggerBackgroundSync()
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
        val itemIds = dao.getUnarchivedItemIdsForFeed(feedId)
        if (itemIds.isEmpty()) return 0
        val existingTag =
            dao.getTagByName(normalized)
                ?: TagRow(id = deterministicTagId(normalized), name = normalized).also {
                    dao.upsertTag(it, markDirty = true)
                }
        var taggedCount = 0
        dao.executeInTransaction {
            for (itemId in itemIds) {
                if (dao.hasCollectionMembership(existingTag.id, itemId) == 0) {
                    dao.insertItemTag(
                        ItemTagRow(itemId = itemId, tagId = existingTag.id),
                        markDirty = true,
                    )
                    taggedCount++
                }
            }
        }
        if (taggedCount > 0) {
            triggerBackgroundSync()
        }
        return taggedCount
    }

    suspend fun refreshFeed(feedId: String): Result<String> = runSuspendCatching {
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

    suspend fun deleteFeed(feedId: String): Result<String> = runSuspendCatching {
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

    suspend fun deleteItem(itemId: String): Result<String> = runSuspendCatching {
        val existing = dao.getItemById(itemId)
        val nodeId = currentLocalPeerId()
        offlineCacheManager.evictCachedItemFiles(itemId)
        dao.executeInTransaction {
            if (existing != null && existing.url.isNotBlank()) {
                val feedUrl =
                    existing.feedUrl ?: existing.feedId?.let { fid -> dao.getFeedById(fid)?.url }
                val nowIso = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
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
        triggerBackgroundSync()
        existing?.title ?: itemId
    }

    suspend fun saveUrlOrFeed(
        inputUrl: String,
        titleOverride: String?,
        tagCsv: String,
    ): SaveUrlResult {
        val trimmedUrl = inputUrl.trim()
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
            return SaveUrlResult(title = feedTitle, kind = SavedResourceKind.FEED)
        }
        val tags = parseTagCsv(tagCsv)
        val inferredType = inferContentType(trimmedUrl)
        val hostTitle = extractHostTitle(trimmedUrl)
        val cleanTitleOverride = titleOverride?.trim()?.takeIf { it.isNotBlank() }
        val resolvedTitle = cleanTitleOverride ?: "$hostTitle — Saved Entry"
        val localEntity = createSavedItemEntity(trimmedUrl, resolvedTitle, hostTitle, inferredType)

        dao.deleteTombstone("item", itemNaturalKey(null, trimmedUrl))
        dao.upsertItem(localEntity, markDirty = true)
        attachTagsLocally(dao = dao, itemId = localEntity.id, tagNames = tags, markDirty = true)

        val remoteSaved = pushSavedUrlBestEffort(trimmedUrl, cleanTitleOverride, tags)
        val finalEntity =
            if (remoteSaved != null && remoteSaved.id.isNotBlank()) {
                finalizeOnlineSavedItem(
                    dao = dao,
                    localEntity = localEntity,
                    remoteSaved = remoteSaved,
                    initialTags = tags,
                )
            } else {
                localEntity
            }
        triggerBackgroundSync()
        return SaveUrlResult(title = finalEntity.title, kind = SavedResourceKind.ITEM)
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

    suspend fun syncWithServer(): Result<Int> = syncMutex.withLock {
        runSuspendCatching {
            val currentSettings = settings.settings.first()
            val currentTier = networkTierProvider.currentNetworkTier()
            if (currentSettings.serverUrl.isBlank()) {
                if (currentTier != NetworkTier.OFFLINE) {
                    pollCooperativeFeedsOnDevice(
                        dao = dao,
                        apiClient = apiClient,
                        localPeerId = currentSettings.peerId,
                        activePeers = emptyList(),
                    )
                }
                error("Configure Server URL in Settings")
            }
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

    suspend fun pollDueFeedsOnDeviceNow(): Result<Int> = syncMutex.withLock {
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
        return syncMutex.withLock {
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

    internal suspend fun pushSavedUrlBestEffort(
        url: String,
        titleOverride: String?,
        tags: List<String>,
    ): RemoteItemDto? = runSuspendCatching {
        val currentSettings = settings.settings.first()
        if (currentSettings.serverUrl.isBlank() || currentSettings.bearerToken.isBlank()) {
            null
        } else {
            apiClient.saveItemUrl(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
                url = url,
                title = titleOverride,
                tags = tags,
            )
        }
    }
        .getOrNull()

    internal suspend fun markFeedItemFinished(itemId: String): String? {
        val item = dao.getItemById(itemId) ?: return null
        if (ItemStatus.fromWireValue(item.status) != ItemStatus.ARCHIVED) {
            setArchiveStateWithSnapshot(itemId = itemId, archived = true)
        }
        return catalog.logFeedItemCompletion(itemId)
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
        val resolvedTarget = catalog.followMergedId(itemId) ?: itemId
        if (dao.countTerminalLogsForEntry(resolvedTarget) > 0) return
        catalog.logFeedItemCompletion(itemId)
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
private const val PLAYBACK_SPEED_STEP = 20f
private const val MIN_SPEED = 0.50f
private const val MAX_SPEED = 3.50f

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

internal suspend fun finalizeOnlineSavedItem(
    dao: RocheDao,
    localEntity: InboxItemRow,
    remoteSaved: RemoteItemDto,
    initialTags: List<String>,
): InboxItemRow {
    val currentLocal = dao.getItemById(localEntity.id) ?: localEntity
    val currentLocalTags = dao.getTagNamesForItem(localEntity.id)
    val stateChangedInFlight =
        currentLocal.status != localEntity.status ||
            currentLocal.isFavorite != localEntity.isFavorite ||
            currentLocal.readProgress != localEntity.readProgress ||
            currentLocal.readAt != localEntity.readAt ||
            currentLocalTags.sorted() != initialTags.sorted()
    val reconciled = reconcileSavedItemEntity(currentLocal, remoteSaved)
    dao.executeInTransaction {
        if (remoteSaved.id != localEntity.id) {
            dao.deleteEntryCascade(localEntity.id)
        }
        dao.upsertItem(reconciled, markDirty = stateChangedInFlight)
        val tombstones =
            dao.getAllTombstones()
                .filter { it.entityType.equals(COLLECTION_ENTRY_ENTITY, ignoreCase = true) }
                .mapTo(HashSet()) { it.naturalKey }
        val effectiveTags =
            (currentLocalTags + remoteSaved.tagNames).map(::normalizeTagName).distinct().filter {
                norm ->
                norm.isNotBlank() &&
                    collectionEntryNaturalKey(deterministicTagId(norm), localEntity.id) !in
                        tombstones
            }
        attachTagsLocally(
            dao = dao,
            itemId = reconciled.id,
            tagNames = effectiveTags,
            markDirty = stateChangedInFlight,
        )
    }
    return reconciled
}

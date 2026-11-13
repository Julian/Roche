package com.grayvines.roche.data

import com.grayvines.roche.data.local.ItemTagEntity
import com.grayvines.roche.data.local.PendingEntityKind
import com.grayvines.roche.data.local.PendingMutationEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.TagEntity
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.data.local.buildSqlContainsPattern
import com.grayvines.roche.data.remote.RemoteItemDto
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.data.settings.normalizeServerUrl
import com.grayvines.roche.data.settings.parsePairingInput
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.model.TagItem
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
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
)

@Suppress("TooManyFunctions", "LargeClass")
class RocheRepository(
    private val database: RocheDatabase,
    private val apiClient: RocheApiClient,
    private val settings: SettingsRepository,
    private val cacheDir: File? = null,
    private val networkTierProvider: NetworkTierProvider = NetworkTierProvider { NetworkTier.WIFI },
    backgroundScope: CoroutineScope? = null,
) {
    private val dao = database.dao()
    private val syncMutex = Mutex()
    private val itemMutationMutex = Mutex()
    private val mutationDispatcher = BackgroundMutationDispatcher(backgroundScope)
    private val entryCache = InboxEntryCache()
    private val activePeersFlow = MutableStateFlow<List<PeerNodeDto>>(emptyList())
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

    val snapshot: Flow<RepositorySnapshot> =
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
                    .also { latestSnapshot = it }
            }
            .flowOn(Dispatchers.Default)

    suspend fun prewarm(): RepositorySnapshot = snapshot.first()

    internal suspend fun searchDeepEntries(rawQuery: String): List<InboxEntry> =
        withContext(Dispatchers.Default) {
            val cleanQuery = rawQuery.trim().trimStart('#')
            if (cleanQuery.isBlank()) {
                emptyList()
            } else {
                val matchedItems = dao.searchFullItems(buildSqlContainsPattern(cleanQuery))
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
            current.contentType.equals("audio", ignoreCase = true) ||
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
        dao.updateItemStatusAndReadAt(itemId, newStatus.wireValue, newReadAt)
        if (archived && current.isCachedOffline && isAudioItem) {
            offlineCacheManager.setOfflineCacheState(itemId = itemId, enabled = false)
        }
        syncStatusMutation(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            itemId = itemId,
            status = newStatus.wireValue,
            dispatcher = mutationDispatcher,
        )
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
        dao.updateItemStatusAndReadAt(itemId, status.wireValue, targetReadAt)
        syncStatusMutation(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            itemId = itemId,
            status = status.wireValue,
            explicitReadAt = targetReadAt?.takeIf { status == ItemStatus.UNREAD },
            dispatcher = mutationDispatcher,
        )
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
        dao.updateItemReadAt(itemId, nextReadAt)
        syncReadAtMutation(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            itemId = itemId,
            readAt = nextReadAt,
            dispatcher = mutationDispatcher,
        )
    }

    suspend fun toggleReadState(itemId: String): Boolean {
        val current = dao.getItemStateById(itemId) ?: return false
        val nextIsRead = current.readAt.isNullOrBlank()
        val nextReadAt = if (nextIsRead) Instant.now().toString() else null
        dao.updateItemReadAt(itemId, nextReadAt)
        syncReadAtMutation(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            itemId = itemId,
            readAt = nextReadAt,
            dispatcher = mutationDispatcher,
        )
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
                    dao.updateItemFavorite(itemId, updated)
                }
                syncFavoriteMutation(
                    dao = dao,
                    apiClient = apiClient,
                    settings = settings,
                    itemId = itemId,
                    isFavorite = updated,
                    dispatcher = mutationDispatcher,
                )
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
        val snapshots =
            syncArchiveBatchMutation(
                dao = dao,
                apiClient = apiClient,
                settings = settings,
                itemIds = entryIds,
                feedId = feedId,
                allUnread = allUnread,
            )
        for (snap in snapshots) {
            val state = dao.getItemStateById(snap.itemId) ?: continue
            if (
                state.isCachedOffline &&
                    (state.contentType.equals("audio", ignoreCase = true) ||
                        !state.audioUrl.isNullOrBlank())
            ) {
                offlineCacheManager.setOfflineCacheState(itemId = state.id, enabled = false)
            }
        }
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
                ?.let { (kotlin.math.round(it * 20f) / 20f).coerceIn(0.50f, 3.50f) }
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
    ) {
        if (!progress.isFinite() || dao.getItemStateById(itemId) == null) return
        val clamped = progress.coerceIn(MIN_READ_PROGRESS, MAX_READ_PROGRESS)
        dao.updateReadProgress(itemId, clamped)
        if (recordPlayedTimestamp) {
            val nowIso = Instant.now().toString()
            dao.updateItemReadAt(itemId, nowIso)
            syncReadAtMutation(
                dao = dao,
                apiClient = apiClient,
                settings = settings,
                itemId = itemId,
                readAt = nowIso,
                dispatcher = mutationDispatcher,
            )
        }
        syncReadProgressMutation(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            itemId = itemId,
            progress = clamped,
            dispatcher = mutationDispatcher,
        )
    }

    internal suspend fun recordEpisodePlaybackStarted(itemId: String) {
        if (dao.getItemStateById(itemId) == null) return
        val nowIso = Instant.now().toString()
        dao.updateItemReadAt(itemId, nowIso)
        syncReadAtMutation(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            itemId = itemId,
            readAt = nowIso,
            dispatcher = mutationDispatcher,
        )
    }

    internal suspend fun toggleTagOnItem(itemId: String, tagName: String) {
        val normalized = normalizeTagName(tagName)
        if (normalized.isBlank() || dao.getItemStateById(itemId) == null) return
        dao.updateItemInteractionTimestamp(itemId)
        val existingTag =
            dao.getTagByName(normalized)
                ?: TagEntity(id = newTagId(), name = normalized).also { dao.upsertTag(it) }
        val alreadyAssigned = dao.hasItemTag(itemId = itemId, tagId = existingTag.id) > 0
        if (alreadyAssigned) {
            dao.deleteItemTag(itemId = itemId, tagId = existingTag.id)
            syncRemoveTagMutation(
                dao = dao,
                apiClient = apiClient,
                settings = settings,
                itemId = itemId,
                existingTag = existingTag,
                normalizedName = normalized,
            )
        } else {
            dao.insertItemTag(ItemTagEntity(itemId = itemId, tagId = existingTag.id))
            syncAddTagMutation(
                dao = dao,
                apiClient = apiClient,
                settings = settings,
                itemId = itemId,
                existingTag = existingTag,
                normalizedName = normalized,
            )
        }
    }

    internal suspend fun renameFeed(feedId: String, newTitle: String): String? =
        renameFeedInDbAndRemote(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            feedId = feedId,
            newTitle = newTitle,
        )

    suspend fun toggleFeedArchive(feedId: String): Result<Pair<String, Boolean>> =
        runSuspendCatching {
            toggleFeedArchiveInDbAndRemote(
                dao = dao,
                apiClient = apiClient,
                settings = settings,
                feedId = feedId,
            )
        }

    internal suspend fun applyTagToFeedItems(feedId: String, tagName: String): Int =
        applyTagToUnarchivedFeedItems(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            feedId = feedId,
            tagName = tagName,
        )

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
        deleteFeedFromServerOrLocal(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            offlineCacheManager = offlineCacheManager,
            feedId = feedId,
        )
    }

    suspend fun deleteItem(itemId: String): Result<String> = runSuspendCatching {
        val existing = dao.getItemById(itemId)
        if (existing != null) {
            val feedUrl =
                existing.feedUrl ?: existing.feedId?.let { fid -> dao.getFeedById(fid)?.url }
            val nowIso = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
            dao.upsertTombstone(
                TombstoneEntity(
                    entityType = "item",
                    naturalKey = itemNaturalKey(feedUrl, existing.url),
                    deletedAt = nowIso,
                )
            )
        }
        val wasLocalUnpushed = dao.getPendingMutation(itemId)?.kind == PendingEntityKind.LOCAL_ITEM
        offlineCacheManager.evictCachedItemFiles(itemId)
        dao.deleteItemTagsForItem(itemId)
        dao.deletePendingMutation(itemId)
        dao.deleteItemById(itemId)
        if (!wasLocalUnpushed) {
            val deletedOnServer =
                pushDeleteItemBestEffort(
                    apiClient = apiClient,
                    settings = settings,
                    itemId = itemId,
                )
            if (!deletedOnServer) {
                dao.upsertPendingMutation(
                    PendingMutationEntity(
                        entityId = itemId,
                        dirtyStatus = DELETED_STATUS_TOMBSTONE,
                    )
                )
            }
        }
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
            return SaveUrlResult(title = feedTitle, kind = SavedResourceKind.FEED)
        }
        val tags = parseTagCsv(tagCsv)
        val inferredType = inferContentType(trimmedUrl)
        val hostTitle = extractHostTitle(trimmedUrl)
        val cleanTitleOverride = titleOverride?.trim()?.takeIf { it.isNotBlank() }
        val resolvedTitle = cleanTitleOverride ?: "$hostTitle — Saved Entry"
        val localEntity = createSavedItemEntity(trimmedUrl, resolvedTitle, hostTitle, inferredType)

        dao.deleteTombstone("item", itemNaturalKey(null, trimmedUrl))
        dao.upsertItem(localEntity)
        attachTagsLocally(dao = dao, itemId = localEntity.id, tagNames = tags)
        dao.upsertPendingMutation(
            PendingMutationEntity.localItem(
                entityId = localEntity.id,
                titleOverride = cleanTitleOverride,
            )
        )

        val remoteSaved = pushSavedUrlBestEffort(trimmedUrl, cleanTitleOverride, tags)
        val finalEntity =
            if (remoteSaved != null && remoteSaved.id.isNotBlank()) {
                finalizeOnlineSavedItem(
                    dao = dao,
                    apiClient = apiClient,
                    settings = settings,
                    localEntity = localEntity,
                    remoteSaved = remoteSaved,
                    tags = tags,
                )
            } else {
                localEntity
            }
        return SaveUrlResult(title = finalEntity.title, kind = SavedResourceKind.ITEM)
    }

    internal suspend fun discoverServer(
        tailnetDomains: List<String> = emptyList()
    ): Result<String> = runSuspendCatching {
        val discovered =
            apiClient.discoverServer(tailnetDomains = tailnetDomains)
                ?: error("No Roche server found on Tailnet (tried roche:8080 / svc:roche)")
        val preferredUrl = normalizeServerUrl(discovered.preferredUrl)
        check(preferredUrl.isNotBlank()) { "Discovered server returned an invalid URL" }
        settings.update { current ->
            val preservedToken = if (current.serverUrl == preferredUrl) current.bearerToken else ""
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
                if (savedUrl.isNotBlank()) {
                    savedUrl
                } else {
                    apiClient
                        .discoverServer()
                        ?.let { normalizeServerUrl(it.preferredUrl) }
                        .orEmpty()
                }
            }
        check(resolvedUrl.isNotBlank()) {
            "Could not auto-discover Roche server on Tailnet; enter Server URL"
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
            val peerSyncedCount =
                if (currentSettings.bearerToken.isNotBlank()) {
                    peerSyncCoordinator(currentTier).syncAndCooperativePoll(currentSettings)
                } else {
                    null
                }
            val count = peerSyncedCount ?: syncLegacySnapshot(currentSettings)
            offlineCacheManager.autoDownloadNewEpisodes()
            count
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
        if (currentSettings.serverUrl.isNotBlank() && currentSettings.bearerToken.isNotBlank()) {
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

    private fun peerSyncCoordinator(networkTier: NetworkTier): PeerSyncCoordinator =
        PeerSyncCoordinator(
            dao = dao,
            apiClient = apiClient,
            networkTier = networkTier,
            onPeersUpdated = { activePeersFlow.value = it },
            onSyncCursorUpdated = { cursor ->
                settings.update { it.copy(lastPeerSyncCursor = cursor) }
            },
        )

    private suspend fun syncLegacySnapshot(currentSettings: Settings): Int {
        val pushOutcome =
            if (currentSettings.bearerToken.isNotBlank()) {
                pushPendingMutations(
                    dao = dao,
                    apiClient = apiClient,
                    serverUrl = currentSettings.serverUrl,
                    token = currentSettings.bearerToken,
                )
            } else {
                PushMutationsOutcome()
            }
        val preFetchItemsById = dao.getAllItems().associateBy { it.id }
        val preFetchFeedsById = dao.getAllFeeds().associateBy { it.id }
        val remoteFeeds =
            apiClient.fetchFeeds(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
            )
        val remoteItems =
            apiClient.fetchItems(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
            )
        val remoteTags =
            apiClient.fetchTags(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
            )
        val mergedFeeds = mergePushedFeeds(remoteFeeds, pushOutcome.createdFeeds)
        val mergedItems = mergePushedItems(remoteItems, pushOutcome.savedItems)

        reconcileRemoteSnapshot(
            dao = dao,
            snapshot =
                RemoteSnapshotData(
                    feeds = mergedFeeds,
                    items = mergedItems,
                    tags = remoteTags,
                ),
            options =
                ReconcileOptions(
                    appliedMutations = pushOutcome.appliedMutations,
                    preFetchItemsById = preFetchItemsById,
                    preFetchFeedsById = preFetchFeedsById,
                ),
        )
        bootstrapDeltaPeerSyncIfNeeded(
            coordinator = peerSyncCoordinator(networkTierProvider.currentNetworkTier()),
            settings = settings,
            currentSettings = currentSettings,
            hasMergedData = mergedFeeds.isNotEmpty() || mergedItems.isNotEmpty(),
        )
        return dao.countItems()
    }

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

private const val MIN_READ_PROGRESS = 0.0
private const val MAX_READ_PROGRESS = 1.0
private const val BOOTSTRAP_DELTA_LOOKBACK_SEC = 60L

internal suspend fun bootstrapDeltaPeerSyncIfNeeded(
    coordinator: PeerSyncCoordinator,
    settings: SettingsRepository,
    currentSettings: Settings,
    hasMergedData: Boolean,
) {
    val shouldBootstrap =
        currentSettings.bearerToken.isNotBlank() &&
            currentSettings.lastPeerSyncCursor.isBlank() &&
            hasMergedData
    if (!shouldBootstrap) return
    val bootstrapCursor =
        Instant.now()
            .minusSeconds(BOOTSTRAP_DELTA_LOOKBACK_SEC)
            .truncatedTo(ChronoUnit.SECONDS)
            .toString()
    settings.update { it.copy(lastPeerSyncCursor = bootstrapCursor) }
    runSuspendCatching {
        coordinator.syncAndCooperativePoll(
            currentSettings = currentSettings.copy(lastPeerSyncCursor = bootstrapCursor)
        )
    }
}

internal suspend fun renameFeedInDbAndRemote(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    feedId: String,
    newTitle: String,
): String? {
    val cleanTitle = newTitle.trim()
    val existing = if (cleanTitle.isNotBlank()) dao.getFeedById(feedId) else null
    if (existing == null) return null
    if (existing.title != cleanTitle) {
        dao.updateFeedTitle(feedId = feedId, title = cleanTitle)
        dao.updateItemsFeedTitle(feedId = feedId, title = cleanTitle)
        syncFeedRenameMutation(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            feedId = feedId,
            cleanTitle = cleanTitle,
        )
    }
    return cleanTitle
}

internal suspend fun toggleFeedArchiveInDbAndRemote(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    feedId: String,
): Pair<String, Boolean> {
    val existing = dao.getFeedById(feedId) ?: error("Feed not found: $feedId")
    val nextIsActive = !existing.isActive
    dao.updateFeedActive(feedId = feedId, isActive = nextIsActive)
    syncFeedArchiveMutation(
        dao = dao,
        apiClient = apiClient,
        settings = settings,
        feedId = feedId,
        isActive = nextIsActive,
    )
    return existing.title.ifBlank { feedId } to !nextIsActive
}

internal suspend fun applyTagToUnarchivedFeedItems(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    feedId: String,
    tagName: String,
): Int {
    val normalized = normalizeTagName(tagName)
    if (normalized.isBlank()) return 0
    val itemIds = dao.getUnarchivedItemIdsForFeed(feedId)
    if (itemIds.isEmpty()) return 0
    val existingTag =
        dao.getTagByName(normalized)
            ?: TagEntity(id = newTagId(), name = normalized).also { dao.upsertTag(it) }
    var taggedCount = 0
    for (itemId in itemIds) {
        if (dao.hasItemTag(itemId = itemId, tagId = existingTag.id) == 0) {
            dao.updateItemInteractionTimestamp(itemId)
            dao.insertItemTag(ItemTagEntity(itemId = itemId, tagId = existingTag.id))
            syncAddTagMutation(
                dao = dao,
                apiClient = apiClient,
                settings = settings,
                itemId = itemId,
                existingTag = existingTag,
                normalizedName = normalized,
            )
            taggedCount++
        }
    }
    return taggedCount
}

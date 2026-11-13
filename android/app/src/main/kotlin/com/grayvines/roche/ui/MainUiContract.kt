package com.grayvines.roche.ui

import com.grayvines.roche.data.ArchiveStateSnapshot
import com.grayvines.roche.data.NetworkTier
import com.grayvines.roche.data.PeerNodeDto
import com.grayvines.roche.data.ReaderItemContent
import com.grayvines.roche.data.RepositorySnapshot
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.matchesSearchQuery
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.model.TagItem
import com.grayvines.roche.model.ThemeMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

data class UndoArchiveEvent(
    val itemId: String,
    val previousStatus: ItemStatus,
    val message: String,
    val previousReadAt: String? = null,
    val batchSnapshots: List<ArchiveStateSnapshot> = emptyList(),
)

data class MainUiState(
    val activeTab: AppTab = AppTab.INBOX,
    val activeFilter: InboxFilter = InboxFilter.ALL,
    val isSearchOpen: Boolean = false,
    val searchQuery: String = "",
    val selectedTag: String? = null,
    val selectedFeedId: String? = null,
    val scopeOriginTab: AppTab? = null,
    val selectedReaderEntryId: String? = null,
    val readerContent: ReaderItemContent? = null,
    val contextSheetEntryId: String? = null,
    val feedContextSheetId: String? = null,
    val isAddSheetOpen: Boolean = false,
    val prefilledShareUrl: String = "",
    val prefilledShareTitle: String = "",
    val isInitialLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val visibleEntries: List<InboxEntry> = emptyList(),
    val searchResults: List<InboxEntry> = emptyList(),
    val allEntries: List<InboxEntry> = emptyList(),
    val feeds: List<FeedItem> = emptyList(),
    val tags: List<TagItem> = emptyList(),
    val activePeers: List<PeerNodeDto> = emptyList(),
    val localNetworkTier: NetworkTier = NetworkTier.WIFI,
    val settings: Settings = Settings(),
    val queueEntries: List<InboxEntry> = computeQueueEntries(allEntries, settings.queueItemIds),
    val listeningHistoryEntries: List<InboxEntry> = computeListeningHistory(allEntries),
    val activeFeeds: List<FeedItem> = computeActiveFeeds(feeds),
    val archivedFeeds: List<FeedItem> = computeArchivedFeeds(feeds),
    val starredEntries: List<InboxEntry> = computeStarredEntries(allEntries),
    val offlineEntries: List<InboxEntry> = computeOfflineEntries(allEntries),
    val playerState: PodcastPlayerState = PodcastPlayerState(),
    val undoEvent: UndoArchiveEvent? = null,
    val statusBanner: String? = null,
    val messageSequence: Long = 0L,
) {
    val activePlayerEntry: InboxEntry?
        get() {
            val activeId = playerState.currentEntryId ?: return null
            val base = allEntries.firstOrNull { it.id == activeId } ?: return null
            val full = readerContent?.takeIf { it.itemId == base.id } ?: return base
            val mergedHtml = pickLongerReaderText(full.contentHtml, base.contentHtml)
            val mergedText = pickLongerReaderText(full.contentText, base.contentText)
            return if (mergedHtml != base.contentHtml || mergedText != base.contentText) {
                base.copy(
                    contentHtml = mergedHtml,
                    contentText = mergedText,
                )
            } else {
                base
            }
        }

    val activePlayerFeed: FeedItem?
        get() =
            activePlayerEntry?.feedId?.let { fid ->
                feeds.firstOrNull { it.id == fid }
            }

    val nextQueuedAudioEntry: InboxEntry?
        get() {
            val activeAudioId = playerState.currentEntryId
            return queueEntries.firstOrNull { entry ->
                entry.contentType == ContentType.AUDIO && entry.id != activeAudioId
            }
        }

    val nextQueuedAudioEntryId: String?
        get() = nextQueuedAudioEntry?.id

    val activeReaderEntry: InboxEntry?
        get() {
            val base = allEntries.firstOrNull { it.id == selectedReaderEntryId } ?: return null
            val full = readerContent?.takeIf { it.itemId == base.id } ?: return base
            val mergedHtml = pickLongerReaderText(full.contentHtml, base.contentHtml)
            val mergedText = pickLongerReaderText(full.contentText, base.contentText)
            return if (mergedHtml != base.contentHtml || mergedText != base.contentText) {
                base.copy(
                    contentHtml = mergedHtml,
                    contentText = mergedText,
                )
            } else {
                base
            }
        }

    val contextSheetEntry: InboxEntry?
        get() = allEntries.firstOrNull { it.id == contextSheetEntryId }

    val contextSheetFeed: FeedItem?
        get() = feeds.firstOrNull { it.id == feedContextSheetId }

    val selectedFeedTitle: String?
        get() = feeds.firstOrNull { it.id == selectedFeedId }?.title
}

fun MainUiState.isEntryInQueue(entryId: String): Boolean = entryId in settings.queueItemIds

enum class ConfigSaveFeedback {
    SHOW_BANNER,
    SILENT,
}

internal enum class SyncErrorPolicy {
    SHOW_BANNER,
    SILENT,
}

sealed interface MainAction {
    sealed interface Ui : MainAction

    sealed interface Mutation : MainAction

    data class SelectTab(val tab: AppTab) : Ui

    data class SelectFilter(val filter: InboxFilter) : Ui

    data class FilterByTag(val tagName: String?) : Ui

    data class FilterByFeed(val feedId: String?) : Ui

    data object NavigateBackFromScope : Ui

    data object OpenSearch : Ui

    data object CloseSearch : Ui

    data class UpdateSearchQuery(val query: String) : Ui

    data class OpenReader(val entryId: String) : Ui

    data object CloseReader : Ui

    data class OpenContextSheet(val entryId: String) : Ui

    data object CloseContextSheet : Ui

    data class OpenFeedContextSheet(val feedId: String) : Ui

    data object CloseFeedContextSheet : Ui

    data class OpenAddSheet(
        val prefilledUrl: String = "",
        val prefilledTitle: String = "",
    ) : Ui

    data object CloseAddSheet : Ui

    data object DismissMessages : Ui

    data object OpenFullscreenPlayer : Ui

    data object CloseFullscreenPlayer : Ui

    data class SetPlayerSpeedSheetOpen(val open: Boolean) : Ui

    data class SelectPlayerDrawerTab(val tab: PlayerDrawerTab?) : Ui

    data object RequestAudioOutputCast : Ui

    data class PlayAudioEntry(
        val entryId: String,
        val openFullscreen: Boolean = true,
    ) : Mutation

    data object TogglePlayPause : Mutation

    data class SeekPlayerToFraction(val fraction: Float) : Mutation

    data class SeekPlayerToMs(val positionMs: Long) : Mutation

    data class SkipPlayerBySeconds(val deltaSeconds: Int) : Mutation

    data object SkipToNextQueuedAudio : Mutation

    data class SetPlaybackSpeed(
        val speed: Float,
        val applyToCurrentPodcast: Boolean? = null,
    ) : Mutation

    data class SetRememberPodcastSpeed(
        val feedId: String,
        val enabled: Boolean,
    ) : Mutation

    data class SetSkipSilence(val enabled: Boolean) : Mutation

    data class SetSmartRewind(val enabled: Boolean) : Mutation

    data class SetSleepTimerMinutes(val minutes: Int?) : Mutation

    data class SetFeedAutoDownload(
        val feedId: String,
        val enabled: Boolean,
    ) : Mutation

    data class SetFeedPlaybackSpeed(
        val feedId: String,
        val speed: Float?,
    ) : Mutation

    data class ToggleQueueMembership(val entryId: String) : Mutation

    data class AddToQueue(
        val entryId: String,
        val playNext: Boolean = false,
    ) : Mutation

    data class RemoveFromQueue(val entryId: String) : Mutation

    data class MoveQueueItemUp(val entryId: String) : Mutation

    data class MoveQueueItemToTop(val entryId: String) : Mutation

    data class ToggleArchive(val entry: InboxEntry) : Mutation

    data object UndoLastArchive : Mutation

    data object ArchiveVisibleEntries : Mutation

    data class ImportOpml(val opmlXml: String) : Mutation

    data class ToggleStar(val entryId: String) : Mutation

    data class ToggleReadState(val entryId: String) : Mutation

    data class ToggleOfflineCache(val entryId: String) : Mutation

    data class ToggleTagOnEntry(val entryId: String, val tagName: String) : Mutation

    data class UpdateReadProgress(val entryId: String, val progress: Double) : Mutation

    data class SaveUrlOrFeed(val url: String, val titleOverride: String?, val tagCsv: String) :
        Mutation

    data class RefreshFeed(val feedId: String) : Mutation

    data class RenameFeed(val feedId: String, val newTitle: String) : Mutation

    data class ApplyTagToFeed(val feedId: String, val tagName: String) : Mutation

    data class ArchiveFeedUnread(val feedId: String) : Mutation

    data class ToggleFeedArchive(val feedId: String) : Mutation

    data class DeleteFeed(val feedId: String) : Mutation

    data class DeleteEntry(val entryId: String) : Mutation

    data object SyncNow : Mutation

    data object PollFeedsOnDeviceNow : Mutation

    data class SyncWithServerConfig(
        val serverUrl: String,
        val bearerToken: String,
    ) : Mutation

    data object StartupSyncIfConfigured : Mutation

    data class SetThemeMode(val mode: ThemeMode) : Mutation

    data class SetHeadlineFont(val font: HeadlineFont) : Mutation

    data class SetUltraDenseRows(val enabled: Boolean) : Mutation

    data class SetReaderFontScale(val scale: Float) : Mutation

    data class SetSwipeToArchive(val enabled: Boolean) : Mutation

    data class SetBottomNavSlot(val slotIndex: Int, val tab: AppTab) : Mutation

    data object ResetBottomNavTabs : Mutation

    data class UpdateServerConfig(
        val serverUrl: String,
        val bearerToken: String,
        val feedback: ConfigSaveFeedback = ConfigSaveFeedback.SHOW_BANNER,
    ) : Mutation

    data class PairWithServer(
        val serverUrl: String,
        val pairingCode: String,
        val deviceName: String = "Roche Android",
    ) : Mutation

    data object DiscoverServer : Mutation
}

internal data class TransientUiState(
    val activeTab: AppTab = AppTab.INBOX,
    val activeFilter: InboxFilter = InboxFilter.ALL,
    val isSearchOpen: Boolean = false,
    val searchQuery: String = "",
    val deepSearchQuery: String = "",
    val deepSearchResults: List<InboxEntry> = emptyList(),
    val optimisticFavorites: Map<String, Boolean> = emptyMap(),
    val selectedTag: String? = null,
    val selectedFeedId: String? = null,
    val scopeOriginTab: AppTab? = null,
    val selectedReaderEntryId: String? = null,
    val readerContent: ReaderItemContent? = null,
    val contextSheetEntryId: String? = null,
    val feedContextSheetId: String? = null,
    val isAddSheetOpen: Boolean = false,
    val prefilledShareUrl: String = "",
    val prefilledShareTitle: String = "",
    val isSyncing: Boolean = false,
    val localNetworkTier: NetworkTier = NetworkTier.WIFI,
    val undoEvent: UndoArchiveEvent? = null,
    val statusBanner: String? = null,
    val messageSequence: Long = 0L,
)

internal fun reduceUiAction(
    state: TransientUiState,
    action: MainAction.Ui,
): TransientUiState =
    when (action) {
        is MainAction.SelectTab ->
            state.copy(
                activeTab = action.tab,
                isSearchOpen = false,
                selectedReaderEntryId = null,
                readerContent = null,
                selectedFeedId = null,
                selectedTag = null,
                scopeOriginTab = null,
            )
        is MainAction.SelectFilter ->
            state.copy(
                activeFilter = action.filter,
                selectedTag = null,
                selectedFeedId = null,
                scopeOriginTab = null,
            )
        is MainAction.FilterByTag ->
            state.copy(
                activeTab = AppTab.INBOX,
                isSearchOpen = false,
                selectedTag = action.tagName,
                selectedFeedId = null,
                scopeOriginTab = resolveNextScopeOriginTab(state, action.tagName != null),
                selectedReaderEntryId = null,
                readerContent = null,
                contextSheetEntryId = null,
            )
        is MainAction.FilterByFeed ->
            state.copy(
                activeTab = AppTab.INBOX,
                isSearchOpen = false,
                selectedFeedId = action.feedId,
                selectedTag = null,
                scopeOriginTab = resolveNextScopeOriginTab(state, action.feedId != null),
                selectedReaderEntryId = null,
                readerContent = null,
                contextSheetEntryId = null,
                feedContextSheetId = null,
            )
        MainAction.NavigateBackFromScope ->
            state.copy(
                activeTab = state.scopeOriginTab ?: AppTab.INBOX,
                selectedFeedId = null,
                selectedTag = null,
                scopeOriginTab = null,
                selectedReaderEntryId = null,
                readerContent = null,
                contextSheetEntryId = null,
                feedContextSheetId = null,
            )
        else -> reduceOverlayUiAction(state, action)
    }

internal fun pickLongerReaderText(first: String?, second: String?): String? {
    val a = first?.takeIf { it.isNotBlank() }
    val b = second?.takeIf { it.isNotBlank() }
    return when {
        a == null -> b
        b == null -> a
        a.length >= b.length -> a
        else -> b
    }
}

private fun resolveNextScopeOriginTab(state: TransientUiState, hasNewScope: Boolean): AppTab? =
    when {
        !hasNewScope -> null
        state.activeTab != AppTab.INBOX -> state.activeTab
        else -> state.scopeOriginTab
    }

private fun reduceOverlayUiAction(
    state: TransientUiState,
    action: MainAction.Ui,
): TransientUiState =
    when (action) {
        is MainAction.UpdateSearchQuery -> reduceUpdateSearchQuery(state, action.query)
        MainAction.OpenSearch ->
            state.copy(
                isSearchOpen = true,
                contextSheetEntryId = null,
                feedContextSheetId = null,
                isAddSheetOpen = false,
            )
        MainAction.CloseSearch ->
            state.copy(
                isSearchOpen = false,
                searchQuery = "",
                deepSearchQuery = "",
                deepSearchResults = emptyList(),
            )
        is MainAction.OpenReader ->
            state.copy(
                selectedReaderEntryId = action.entryId,
                readerContent = null,
                contextSheetEntryId = null,
            )
        MainAction.CloseReader -> state.copy(selectedReaderEntryId = null, readerContent = null)
        is MainAction.OpenContextSheet -> state.copy(contextSheetEntryId = action.entryId)
        MainAction.CloseContextSheet -> state.copy(contextSheetEntryId = null)
        is MainAction.OpenFeedContextSheet -> state.copy(feedContextSheetId = action.feedId)
        MainAction.CloseFeedContextSheet -> state.copy(feedContextSheetId = null)
        is MainAction.OpenAddSheet ->
            state.copy(
                isAddSheetOpen = true,
                prefilledShareUrl = action.prefilledUrl,
                prefilledShareTitle = action.prefilledTitle,
                contextSheetEntryId = null,
                feedContextSheetId = null,
            )
        MainAction.CloseAddSheet ->
            state.copy(
                isAddSheetOpen = false,
                prefilledShareUrl = "",
                prefilledShareTitle = "",
            )
        MainAction.DismissMessages -> state.copy(undoEvent = null, statusBanner = null)
        else -> state
    }

private fun reduceUpdateSearchQuery(state: TransientUiState, query: String): TransientUiState {
    val clean = query.trim()
    return state.copy(
        searchQuery = query,
        deepSearchQuery = if (clean.isEmpty()) "" else state.deepSearchQuery,
        deepSearchResults = if (clean.isEmpty()) emptyList() else state.deepSearchResults,
    )
}

internal fun updateBottomNavSlot(
    current: List<AppTab>,
    slotIndex: Int,
    newTab: AppTab,
): List<AppTab> {
    if (newTab == AppTab.INBOX || newTab !in AppTab.sideTabCandidates) {
        return AppTab.normalizeSideTabs(current)
    }
    val normalized = AppTab.normalizeSideTabs(current).toMutableList()
    if (slotIndex !in normalized.indices) return normalized
    val existingIndex = normalized.indexOf(newTab)
    val oldTab = normalized[slotIndex]
    normalized[slotIndex] = newTab
    if (existingIndex != -1 && existingIndex != slotIndex) {
        normalized[existingIndex] = oldTab
    }
    return AppTab.normalizeSideTabs(normalized)
}

internal fun buildMainUiState(
    snapshot: RepositorySnapshot,
    settings: Settings,
    local: TransientUiState,
    localNetworkTier: NetworkTier = local.localNetworkTier,
    playerState: PodcastPlayerState = PodcastPlayerState(),
): MainUiState =
    MainUiStateMemoizer()
        .build(
            snapshot = snapshot,
            settings = settings,
            local = local,
            localNetworkTier = localNetworkTier,
            playerState = playerState,
        )

internal class MainUiStateMemoizer {
    private var lastRawEntries: List<InboxEntry>? = null
    private var lastOptimisticFavorites: Map<String, Boolean> = emptyMap()
    private var lastEffectiveEntries: List<InboxEntry> = emptyList()

    private var lastVisibleInput: List<InboxEntry>? = null
    private var lastActiveFilter: InboxFilter? = null
    private var lastSelectedTag: String? = null
    private var lastSelectedFeedId: String? = null
    private var lastVisibleEntries: List<InboxEntry> = emptyList()

    private var lastSearchInput: List<InboxEntry>? = null
    private var lastNormalizedQuery: String? = null
    private var lastDeepSearchQuery: String? = null
    private var lastDeepSearchResults: List<InboxEntry>? = null
    private var lastSearchResults: List<InboxEntry> = emptyList()

    private var lastQueueInput: List<InboxEntry>? = null
    private var lastQueueItemIds: List<String>? = null
    private var lastQueueEntries: List<InboxEntry> = emptyList()

    private var lastDerivedInput: List<InboxEntry>? = null
    private var lastListeningHistory: List<InboxEntry> = emptyList()
    private var lastStarredEntries: List<InboxEntry> = emptyList()
    private var lastOfflineEntries: List<InboxEntry> = emptyList()

    private var lastFeedsInput: List<FeedItem>? = null
    private var lastActiveFeeds: List<FeedItem> = emptyList()
    private var lastArchivedFeeds: List<FeedItem> = emptyList()

    internal fun build(
        snapshot: RepositorySnapshot,
        settings: Settings,
        local: TransientUiState,
        localNetworkTier: NetworkTier = local.localNetworkTier,
        playerState: PodcastPlayerState = PodcastPlayerState(),
    ): MainUiState =
        synchronized(this) {
            val effectiveEntries =
                resolveEffectiveEntries(snapshot.entries, local.optimisticFavorites)
            val feeds = snapshot.feeds
            val filtered =
                resolveVisibleEntries(
                    effectiveEntries = effectiveEntries,
                    activeFilter = local.activeFilter,
                    selectedTag = local.selectedTag,
                    selectedFeedId = local.selectedFeedId,
                )
            val searchResults =
                resolveSearch(
                    effectiveEntries = effectiveEntries,
                    normalizedQuery = local.searchQuery.trim().lowercase(),
                    deepSearchQuery = local.deepSearchQuery,
                    deepSearchResults = local.deepSearchResults,
                )
            val queueEntries = resolveQueue(effectiveEntries, settings.queueItemIds)
            resolveEntryProjections(effectiveEntries)
            resolveFeedProjections(feeds)
            MainUiState(
                activeTab = local.activeTab,
                activeFilter = local.activeFilter,
                isSearchOpen = local.isSearchOpen,
                searchQuery = local.searchQuery,
                selectedTag = local.selectedTag,
                selectedFeedId = local.selectedFeedId,
                scopeOriginTab = local.scopeOriginTab,
                selectedReaderEntryId = local.selectedReaderEntryId,
                readerContent = local.readerContent,
                contextSheetEntryId = local.contextSheetEntryId,
                feedContextSheetId = local.feedContextSheetId,
                isAddSheetOpen = local.isAddSheetOpen,
                prefilledShareUrl = local.prefilledShareUrl,
                prefilledShareTitle = local.prefilledShareTitle,
                isInitialLoading = false,
                isSyncing = local.isSyncing,
                visibleEntries = filtered,
                searchResults = searchResults,
                allEntries = effectiveEntries,
                feeds = feeds,
                tags = snapshot.tags,
                activePeers = snapshot.activePeers,
                localNetworkTier = localNetworkTier,
                settings = settings,
                queueEntries = queueEntries,
                listeningHistoryEntries = lastListeningHistory,
                activeFeeds = lastActiveFeeds,
                archivedFeeds = lastArchivedFeeds,
                starredEntries = lastStarredEntries,
                offlineEntries = lastOfflineEntries,
                playerState = playerState,
                undoEvent = local.undoEvent,
                statusBanner = local.statusBanner,
                messageSequence = local.messageSequence,
            )
        }

    private fun resolveEffectiveEntries(
        rawEntries: List<InboxEntry>,
        optimisticFavorites: Map<String, Boolean>,
    ): List<InboxEntry> {
        if (rawEntries === lastRawEntries && optimisticFavorites == lastOptimisticFavorites) {
            return lastEffectiveEntries
        }
        val computed = applyOptimisticFavorites(rawEntries, optimisticFavorites)
        lastRawEntries = rawEntries
        lastOptimisticFavorites = optimisticFavorites
        lastEffectiveEntries = computed
        return computed
    }

    private fun resolveVisibleEntries(
        effectiveEntries: List<InboxEntry>,
        activeFilter: InboxFilter,
        selectedTag: String?,
        selectedFeedId: String?,
    ): List<InboxEntry> {
        val sameScope =
            activeFilter == lastActiveFilter &&
                selectedTag == lastSelectedTag &&
                selectedFeedId == lastSelectedFeedId
        if (effectiveEntries === lastVisibleInput && sameScope) {
            return lastVisibleEntries
        }
        val computed =
            RocheRepository.filterEntries(
                entries = effectiveEntries,
                filter = activeFilter,
                searchQuery = "",
                selectedTag = selectedTag,
                selectedFeedId = selectedFeedId,
            )
        lastVisibleInput = effectiveEntries
        lastActiveFilter = activeFilter
        lastSelectedTag = selectedTag
        lastSelectedFeedId = selectedFeedId
        lastVisibleEntries = computed
        return computed
    }

    private fun resolveSearch(
        effectiveEntries: List<InboxEntry>,
        normalizedQuery: String,
        deepSearchQuery: String,
        deepSearchResults: List<InboxEntry>,
    ): List<InboxEntry> {
        val sameQuery =
            normalizedQuery == lastNormalizedQuery &&
                deepSearchQuery == lastDeepSearchQuery &&
                deepSearchResults === lastDeepSearchResults
        if (effectiveEntries === lastSearchInput && sameQuery) {
            return lastSearchResults
        }
        val computed =
            resolveMergedSearchResults(
                entries = effectiveEntries,
                normalizedQuery = normalizedQuery,
                deepSearchQuery = deepSearchQuery,
                deepSearchResults = deepSearchResults,
            )
        lastSearchInput = effectiveEntries
        lastNormalizedQuery = normalizedQuery
        lastDeepSearchQuery = deepSearchQuery
        lastDeepSearchResults = deepSearchResults
        lastSearchResults = computed
        return computed
    }

    private fun resolveQueue(
        effectiveEntries: List<InboxEntry>,
        queueItemIds: List<String>,
    ): List<InboxEntry> {
        if (effectiveEntries === lastQueueInput && queueItemIds == lastQueueItemIds) {
            return lastQueueEntries
        }
        val computed = computeQueueEntries(effectiveEntries, queueItemIds)
        lastQueueInput = effectiveEntries
        lastQueueItemIds = queueItemIds
        lastQueueEntries = computed
        return computed
    }

    private fun resolveEntryProjections(effectiveEntries: List<InboxEntry>) {
        if (effectiveEntries === lastDerivedInput) return
        lastDerivedInput = effectiveEntries
        lastListeningHistory = computeListeningHistory(effectiveEntries)
        lastStarredEntries = computeStarredEntries(effectiveEntries)
        lastOfflineEntries = computeOfflineEntries(effectiveEntries)
    }

    private fun resolveFeedProjections(feeds: List<FeedItem>) {
        if (feeds === lastFeedsInput) return
        lastFeedsInput = feeds
        lastActiveFeeds = computeActiveFeeds(feeds)
        lastArchivedFeeds = computeArchivedFeeds(feeds)
    }
}

internal fun applyOptimisticFavorites(
    entries: List<InboxEntry>,
    optimisticFavorites: Map<String, Boolean>,
): List<InboxEntry> {
    if (optimisticFavorites.isEmpty()) return entries
    var anyChanged = false
    val mapped = entries.map { entry ->
        val optimisticFav = optimisticFavorites[entry.id]
        if (optimisticFav != null && optimisticFav != entry.isFavorite) {
            anyChanged = true
            entry.copy(isFavorite = optimisticFav)
        } else {
            entry
        }
    }
    return if (anyChanged) mapped else entries
}

internal fun resolveMergedSearchResults(
    entries: List<InboxEntry>,
    normalizedQuery: String,
    deepSearchQuery: String,
    deepSearchResults: List<InboxEntry>,
): List<InboxEntry> {
    if (normalizedQuery.isBlank()) return entries
    val deepById =
        if (deepSearchQuery == normalizedQuery && deepSearchResults.isNotEmpty()) {
            deepSearchResults.associateBy { it.id }
        } else {
            emptyMap()
        }
    if (deepById.isEmpty()) {
        return entries.filter { entry -> matchesSearchQuery(entry, normalizedQuery) }
    }
    return entries.mapNotNull { entry ->
        val deepMatch = deepById[entry.id]
        when {
            deepMatch != null ->
                deepMatch.copy(
                    isFavorite = entry.isFavorite,
                    status = entry.status,
                    readAt = entry.readAt,
                )
            matchesSearchQuery(entry, normalizedQuery) -> entry
            else -> null
        }
    }
}

internal fun computeQueueEntries(
    allEntries: List<InboxEntry>,
    queueItemIds: List<String>,
): List<InboxEntry> {
    if (queueItemIds.isEmpty() || allEntries.isEmpty()) return emptyList()
    val byId = allEntries.associateBy { it.id }
    return queueItemIds.mapNotNull { id -> byId[id]?.takeIf { !it.isArchived } }
}

internal fun computeListeningHistory(allEntries: List<InboxEntry>): List<InboxEntry> {
    if (allEntries.isEmpty()) return emptyList()
    return allEntries
        .filter { entry ->
            entry.contentType == ContentType.AUDIO &&
                (!entry.readAt.isNullOrBlank() || entry.readProgress > 0.0 || entry.isArchived)
        }
        .sortedByDescending { entry ->
            entry.readAt?.takeIf { it.isNotBlank() }
                ?: entry.publishedAt?.takeIf { it.isNotBlank() }
                ?: entry.createdAt
        }
}

internal fun computeActiveFeeds(feeds: List<FeedItem>): List<FeedItem> =
    if (feeds.isEmpty()) emptyList() else feeds.filter { !it.isArchived }

internal fun computeArchivedFeeds(feeds: List<FeedItem>): List<FeedItem> =
    if (feeds.isEmpty()) emptyList() else feeds.filter { it.isArchived }

internal fun computeStarredEntries(allEntries: List<InboxEntry>): List<InboxEntry> =
    if (allEntries.isEmpty()) emptyList() else allEntries.filter { it.isFavorite }

internal fun computeOfflineEntries(allEntries: List<InboxEntry>): List<InboxEntry> =
    if (allEntries.isEmpty()) emptyList() else allEntries.filter { it.isCachedOffline }

internal fun entryCountNoun(count: Int): String = if (count == 1) "entry" else "entries"

internal fun mergeBaseWithPlayerState(
    baseFlow: Flow<MainUiState>,
    playerFlow: StateFlow<PodcastPlayerState>,
    onBuilt: (MainUiState) -> Unit,
): Flow<MainUiState> = channelFlow {
    var latestBase: MainUiState? = null
    var latestPlayer: PodcastPlayerState = playerFlow.value
    val sendMerged = {
        val base = latestBase
        if (base != null) {
            val merged =
                base.copy(
                    playerState =
                        latestPlayer.copy(smartRewindEnabled = base.settings.smartRewindEnabled)
                )
            onBuilt(merged)
            trySend(merged)
        }
    }
    launch {
        baseFlow.collect { base ->
            latestBase = base
            sendMerged()
        }
    }
    playerFlow.collect { player ->
        latestPlayer = player
        sendMerged()
    }
}
    .buffer(Channel.CONFLATED)

internal const val SNACKBAR_TITLE_LIMIT = 36

package com.grayvines.roche.ui

import com.grayvines.roche.data.ArchiveStateSnapshot
import com.grayvines.roche.data.ReaderItemContent
import com.grayvines.roche.data.RepositorySnapshot
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.catalog.CatalogSnapshot
import com.grayvines.roche.data.catalog.CollectionGeneratorState
import com.grayvines.roche.data.catalog.ConsumptionStatus
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.RankClusterCandidate
import com.grayvines.roche.data.catalog.RankSettlementSummary
import com.grayvines.roche.data.matchesSearchQuery
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.data.sync.NetworkTier
import com.grayvines.roche.data.sync.PeerNodeDto
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.model.TagItem
import com.grayvines.roche.model.ThemeMode
import com.grayvines.roche.playback.PlayerDrawerTab
import com.grayvines.roche.playback.findNextQueuedAudioEntry
import kotlinx.serialization.Serializable

@Serializable
enum class CompanionSubTab(val label: String) {
    LISTS("Lists"),
    DIARY("Diary"),
    WANT("Want"),
}

@Serializable
enum class QueueSubTab(val label: String) {
    UP_NEXT("Up Next"),
    WANT("Want"),
    HISTORY("History"),
}

enum class AddSheetMode {
    WANT_TO_READ,
    SUBSCRIBE_FEED,
}

enum class DrawNoteDialogMode {
    FINISH_AND_RANK,
    ABANDON,
}

data class DrawNoteDialogState(
    val collectionId: String,
    val mode: DrawNoteDialogMode,
)

data class ActiveRankSessionUiState(
    val collectionIdOrSlug: String,
    val collectionTitle: String,
    val scopeCollectionId: String? = null,
    val focusEntryId: String? = null,
    val roundIndex: Int = 0,
    val cluster: List<RankClusterCandidate> = emptyList(),
    val judgedClusters: List<Set<String>> = emptyList(),
    val settlement: RankSettlementSummary? = null,
)

data class UndoArchiveEvent(
    val itemId: String,
    val previousStatus: ItemStatus,
    val message: String,
    val previousReadAt: String? = null,
    val batchSnapshots: List<ArchiveStateSnapshot> = emptyList(),
)

data class MainUiState(
    val activeTab: AppTab = AppTab.HOME,
    val activeFilter: InboxFilter = InboxFilter.ALL,
    val isSearchOpen: Boolean = false,
    val searchQuery: String = "",
    val selectedTag: String? = null,
    val selectedFeedId: String? = null,
    val scopeOriginTab: AppTab? = null,
    val selectedReaderEntryId: String? = null,
    val readerContent: ReaderItemContent? = null,
    val activePlayerNotesContent: ReaderItemContent? = null,
    val hydratingEntryIds: Set<String> = emptySet(),
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
    val catalog: CatalogSnapshot = CatalogSnapshot(),
    val companionSubTab: CompanionSubTab = CompanionSubTab.LISTS,
    val queueSubTab: QueueSubTab = QueueSubTab.UP_NEXT,
    val selectedCatalogCollectionId: String? = null,
    val selectedWantCollectionId: String? = null,
    val isQuickLogSheetOpen: Boolean = false,
    val quickLogDefaultWant: Boolean = false,
    val quickLogTargetCollectionId: String? = null,
    val quickLogTargetCollectionTitle: String? = null,
    val drawNoteDialog: DrawNoteDialogState? = null,
    val rankSession: ActiveRankSessionUiState? = null,
    val isPlayerFullscreenOpen: Boolean = false,
    val isPlayerSpeedSheetOpen: Boolean = false,
    val activePlayerDrawerTab: PlayerDrawerTab? = null,
    val undoEvent: UndoArchiveEvent? = null,
    val statusBanner: String? = null,
    val messageSequence: Long = 0L,
) {
    val enabledGenerators: List<CollectionGeneratorState>
        get() = catalog.generators.filter { it.enabled }

    val activeCollectionDraws: List<CollectionGeneratorState>
        get() = if (settings.showCollectionDrawsOnHome) enabledGenerators else emptyList()

    val activeReaderEntry: InboxEntry?
        get() {
            val base = allEntries.firstOrNull { it.id == selectedReaderEntryId } ?: return null
            val full =
                readerContent?.takeIf { it.itemId == base.id }
                    ?: activePlayerNotesContent?.takeIf { it.itemId == base.id }
            return base.mergeReaderContent(full)
        }

    val contextSheetEntry: InboxEntry?
        get() = allEntries.firstOrNull { it.id == contextSheetEntryId }

    val contextSheetFeed: FeedItem?
        get() = feeds.firstOrNull { it.id == feedContextSheetId }

    val selectedFeedTitle: String?
        get() = feeds.firstOrNull { it.id == selectedFeedId }?.title

    val isReaderHydrating: Boolean
        get() = selectedReaderEntryId != null && selectedReaderEntryId in hydratingEntryIds
}

fun MainUiState.resolveActivePlayerEntry(activeAudioEntryId: String?): InboxEntry? {
    val activeId = activeAudioEntryId ?: return null
    val base = allEntries.firstOrNull { it.id == activeId } ?: return null
    val full =
        activePlayerNotesContent?.takeIf { it.itemId == base.id }
            ?: readerContent?.takeIf { it.itemId == base.id }
    return base.mergeReaderContent(full)
}

fun MainUiState.resolveActivePlayerFeed(activeAudioEntryId: String?): FeedItem? =
    resolveActivePlayerEntry(activeAudioEntryId)?.feedId?.let { fid ->
        feeds.firstOrNull { it.id == fid }
    }

fun MainUiState.resolveNextQueuedAudioEntry(activeAudioEntryId: String?): InboxEntry? =
    findNextQueuedAudioEntry(
        entries = allEntries,
        queueItemIds = settings.queueItemIds,
        excludeEntryId = activeAudioEntryId,
    )

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

    sealed interface NavigationUi : Ui

    sealed interface OverlayUi : Ui

    sealed interface PlayerChromeUi : Ui

    sealed interface CatalogUi : Ui

    sealed interface Mutation : MainAction

    sealed interface InboxReaderMutation : Mutation

    sealed interface EntryMutation : InboxReaderMutation

    sealed interface PlaybackMutation : InboxReaderMutation

    sealed interface QueueFeedAudioMutation : InboxReaderMutation

    sealed interface SyncFeedMutation : Mutation

    sealed interface FeedSyncAction : SyncFeedMutation

    sealed interface SettingsServerAction : SyncFeedMutation

    sealed interface CatalogMutation : Mutation

    data class SelectTab(val tab: AppTab) : NavigationUi

    data class SelectFilter(val filter: InboxFilter) : NavigationUi

    data class FilterByTag(val tagName: String?) : NavigationUi

    data class FilterByFeed(val feedId: String?) : NavigationUi

    data object NavigateBackFromScope : NavigationUi

    data object OpenSearch : OverlayUi

    data object CloseSearch : OverlayUi

    data class UpdateSearchQuery(val query: String) : OverlayUi

    data class OpenReader(
        val entryId: String,
        val forceArticleView: Boolean = false,
    ) : OverlayUi

    data object CloseReader : OverlayUi

    data class OpenContextSheet(val entryId: String) : OverlayUi

    data object CloseContextSheet : OverlayUi

    data class OpenFeedContextSheet(val feedId: String) : OverlayUi

    data object CloseFeedContextSheet : OverlayUi

    data class OpenAddSheet(
        val prefilledUrl: String = "",
        val prefilledTitle: String = "",
    ) : OverlayUi

    data object CloseAddSheet : OverlayUi

    data object DismissMessages : OverlayUi

    data object OpenFullscreenPlayer : PlayerChromeUi

    data object CloseFullscreenPlayer : PlayerChromeUi

    data class SetPlayerSpeedSheetOpen(val open: Boolean) : PlayerChromeUi

    data class SelectPlayerDrawerTab(val tab: PlayerDrawerTab?) : PlayerChromeUi

    data object RequestAudioOutputCast : PlayerChromeUi

    data class PlayAudioEntry(
        val entryId: String,
        val openFullscreen: Boolean = true,
    ) : PlaybackMutation

    data object TogglePlayPause : PlaybackMutation

    data class SeekPlayerToFraction(val fraction: Float) : PlaybackMutation

    data class SeekPlayerToMs(val positionMs: Long) : PlaybackMutation

    data class SkipPlayerBySeconds(val deltaSeconds: Int) : PlaybackMutation

    data object SkipToNextQueuedAudio : PlaybackMutation

    data class SetPlaybackSpeed(
        val speed: Float,
        val applyToCurrentPodcast: Boolean? = null,
    ) : PlaybackMutation

    data class SetRememberPodcastSpeed(
        val feedId: String,
        val enabled: Boolean,
    ) : PlaybackMutation

    data class SetSkipSilence(val enabled: Boolean) : PlaybackMutation

    data class SetSmartRewind(val enabled: Boolean) : PlaybackMutation

    data class SetSleepTimerMinutes(val minutes: Int?) : PlaybackMutation

    data class SetFeedAutoDownload(
        val feedId: String,
        val enabled: Boolean,
    ) : QueueFeedAudioMutation

    data class SetFeedPlaybackSpeed(
        val feedId: String,
        val speed: Float?,
    ) : QueueFeedAudioMutation

    data class ToggleQueueMembership(val entryId: String) : QueueFeedAudioMutation

    data class AddToQueue(
        val entryId: String,
        val playNext: Boolean = false,
    ) : QueueFeedAudioMutation

    data class RemoveFromQueue(val entryId: String) : QueueFeedAudioMutation

    data class MoveQueueItemUp(val entryId: String) : QueueFeedAudioMutation

    data class MoveQueueItemToTop(val entryId: String) : QueueFeedAudioMutation

    data class ToggleArchive(
        val entry: InboxEntry,
        val liveReadProgress: Double? = null,
    ) : EntryMutation

    data object UndoLastArchive : EntryMutation

    data object ArchiveVisibleEntries : EntryMutation

    data class ImportOpml(val opmlXml: String) : FeedSyncAction

    data class ToggleStar(val entryId: String) : EntryMutation

    data class ToggleReadState(val entryId: String) : EntryMutation

    data class ToggleOfflineCache(val entryId: String) : EntryMutation

    data class ToggleTagOnEntry(val entryId: String, val tagName: String) : EntryMutation

    data class UpdateReadProgress(val entryId: String, val progress: Double) : EntryMutation

    data class SaveUrlOrFeed(
        val url: String,
        val titleOverride: String?,
        val tagCsv: String,
        val mode: AddSheetMode? = null,
    ) : EntryMutation

    data class RefreshFeed(val feedId: String) : FeedSyncAction

    data class RenameFeed(val feedId: String, val newTitle: String) : FeedSyncAction

    data class ApplyTagToFeed(val feedId: String, val tagName: String) : FeedSyncAction

    data class ArchiveFeedUnread(val feedId: String) : FeedSyncAction

    data class ToggleFeedArchive(val feedId: String) : FeedSyncAction

    data class DeleteFeed(val feedId: String) : FeedSyncAction

    data class DeleteEntry(val entryId: String) : EntryMutation

    data object SyncNow : FeedSyncAction

    data object PollFeedsOnDeviceNow : FeedSyncAction

    data class SyncWithServerConfig(
        val serverUrl: String,
        val bearerToken: String,
    ) : FeedSyncAction

    data object StartupSyncIfConfigured : FeedSyncAction

    data class SetThemeMode(val mode: ThemeMode) : SettingsServerAction

    data class SetHeadlineFont(val font: HeadlineFont) : SettingsServerAction

    data class SetUltraDenseRows(val enabled: Boolean) : SettingsServerAction

    data class SetReaderFontScale(val scale: Float) : SettingsServerAction

    data class SetSwipeToArchive(val enabled: Boolean) : SettingsServerAction

    data class SetShowCollectionDrawsOnHome(val enabled: Boolean) : SettingsServerAction

    data class SetBottomNavSlot(val slotIndex: Int, val tab: AppTab) : SettingsServerAction

    data object ResetBottomNavTabs : SettingsServerAction

    data class OpenCompanionSection(
        val subTab: CompanionSubTab,
        val collectionId: String? = null,
    ) : CatalogUi

    data class SelectCompanionSubTab(val subTab: CompanionSubTab) : CatalogUi

    data class SelectQueueSubTab(val subTab: QueueSubTab) : CatalogUi

    data class SelectCatalogCollection(val collectionId: String?) : CatalogUi

    data class SelectWantCollection(val collectionId: String) : CatalogUi

    data class OpenQuickLogSheet(
        val defaultToWant: Boolean = false,
        val targetCollectionId: String? = null,
        val targetCollectionTitle: String? = null,
    ) : CatalogUi

    data object CloseQuickLogSheet : CatalogUi

    data class OpenDrawNoteDialog(
        val collectionId: String,
        val mode: DrawNoteDialogMode,
    ) : CatalogUi

    data object CloseDrawNoteDialog : CatalogUi

    data object CloseRankSession : CatalogUi

    data class SetCollectionGeneratorEnabled(
        val collectionIdOrSlug: String,
        val enabled: Boolean,
    ) : CatalogMutation

    data class ConfirmFinishDraw(
        val collectionId: String,
        val notes: String,
    ) : CatalogMutation

    data class ConfirmAbandonDraw(
        val collectionId: String,
        val notes: String,
    ) : CatalogMutation

    data class StartRankingSession(
        val collectionIdOrSlug: String,
        val focusEntryId: String? = null,
    ) : CatalogMutation

    data class SubmitRankCluster(val ordering: List<List<String>>) : CatalogMutation

    data object SkipRankCluster : CatalogMutation

    data class MarkFeedEntryFinished(
        val entryId: String,
        val rankImmediately: Boolean = false,
    ) : CatalogMutation

    data class SubmitQuickLog(
        val kind: EntryKind,
        val title: String,
        val creatorsCsv: String,
        val releaseYear: Int?,
        val status: ConsumptionStatus,
        val notes: String,
        val addToWant: Boolean,
        val rankAfterLog: Boolean,
        val targetCollectionId: String? = null,
        val addToListOnly: Boolean = false,
    ) : CatalogMutation

    data class CreateCustomCatalogList(val title: String) : CatalogMutation

    data class CompleteWantEntry(
        val entryId: String,
        val rankImmediately: Boolean = true,
    ) : CatalogMutation

    data class RemoveWantEntry(
        val collectionId: String,
        val entryId: String,
    ) : CatalogMutation

    data class UpdateServerConfig(
        val serverUrl: String,
        val bearerToken: String,
        val feedback: ConfigSaveFeedback = ConfigSaveFeedback.SHOW_BANNER,
    ) : SettingsServerAction

    data class PairWithServer(
        val serverUrl: String,
        val pairingCode: String,
        val deviceName: String = "Roche Android",
    ) : SettingsServerAction

    data object DiscoverServer : SettingsServerAction
}

internal data class TransientUiState(
    val activeTab: AppTab = AppTab.HOME,
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
    val activePlayerNotesContent: ReaderItemContent? = null,
    val hydratingEntryIds: Set<String> = emptySet(),
    val contextSheetEntryId: String? = null,
    val feedContextSheetId: String? = null,
    val isAddSheetOpen: Boolean = false,
    val prefilledShareUrl: String = "",
    val prefilledShareTitle: String = "",
    val isSyncing: Boolean = false,
    val companionSubTab: CompanionSubTab = CompanionSubTab.LISTS,
    val queueSubTab: QueueSubTab = QueueSubTab.UP_NEXT,
    val selectedCatalogCollectionId: String? = null,
    val selectedWantCollectionId: String? = null,
    val isQuickLogSheetOpen: Boolean = false,
    val quickLogDefaultWant: Boolean = false,
    val quickLogTargetCollectionId: String? = null,
    val quickLogTargetCollectionTitle: String? = null,
    val drawNoteDialog: DrawNoteDialogState? = null,
    val rankSession: ActiveRankSessionUiState? = null,
    val isPlayerFullscreenOpen: Boolean = false,
    val isPlayerSpeedSheetOpen: Boolean = false,
    val activePlayerDrawerTab: PlayerDrawerTab? = null,
    val localNetworkTier: NetworkTier = NetworkTier.WIFI,
    val undoEvent: UndoArchiveEvent? = null,
    val statusBanner: String? = null,
    val messageSequence: Long = 0L,
)

@Serializable
internal data class SavedNavigationUiState(
    val activeTab: AppTab = AppTab.HOME,
    val activeFilter: InboxFilter = InboxFilter.ALL,
    val isSearchOpen: Boolean = false,
    val searchQuery: String = "",
    val selectedTag: String? = null,
    val selectedFeedId: String? = null,
    val scopeOriginTab: AppTab? = null,
    val selectedReaderEntryId: String? = null,
    val companionSubTab: CompanionSubTab = CompanionSubTab.LISTS,
    val queueSubTab: QueueSubTab = QueueSubTab.UP_NEXT,
    val selectedCatalogCollectionId: String? = null,
    val selectedWantCollectionId: String? = null,
)

internal fun TransientUiState.toSavedNavigationUiState(): SavedNavigationUiState =
    SavedNavigationUiState(
        activeTab = activeTab,
        activeFilter = activeFilter,
        isSearchOpen = isSearchOpen,
        searchQuery = searchQuery,
        selectedTag = selectedTag,
        selectedFeedId = selectedFeedId,
        scopeOriginTab = scopeOriginTab,
        selectedReaderEntryId = selectedReaderEntryId,
        companionSubTab = companionSubTab,
        queueSubTab = queueSubTab,
        selectedCatalogCollectionId = selectedCatalogCollectionId,
        selectedWantCollectionId = selectedWantCollectionId,
    )

internal fun SavedNavigationUiState.toInitialTransientUiState(
    localNetworkTier: NetworkTier
): TransientUiState =
    TransientUiState(
        activeTab = activeTab,
        activeFilter = activeFilter,
        isSearchOpen = isSearchOpen,
        searchQuery = searchQuery,
        selectedTag = selectedTag,
        selectedFeedId = selectedFeedId,
        scopeOriginTab = scopeOriginTab,
        selectedReaderEntryId = selectedReaderEntryId,
        hydratingEntryIds = setOfNotNull(selectedReaderEntryId),
        companionSubTab = companionSubTab,
        queueSubTab = queueSubTab,
        selectedCatalogCollectionId = selectedCatalogCollectionId,
        selectedWantCollectionId = selectedWantCollectionId,
        localNetworkTier = localNetworkTier,
    )

internal fun TransientUiState.clearSearch(): TransientUiState =
    copy(
        isSearchOpen = false,
        searchQuery = "",
        deepSearchQuery = "",
        deepSearchResults = emptyList(),
    )

internal fun InboxEntry.mergeReaderContent(full: ReaderItemContent?): InboxEntry {
    if (full == null) return this
    val mergedHtml = pickLongerReaderText(full.contentHtml, contentHtml)
    val mergedText = pickLongerReaderText(full.contentText, contentText)
    return if (mergedHtml != contentHtml || mergedText != contentText) {
        copy(
            contentHtml = mergedHtml,
            contentText = mergedText,
        )
    } else {
        this
    }
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

internal fun updateBottomNavSlot(
    current: List<AppTab>,
    slotIndex: Int,
    newTab: AppTab,
): List<AppTab> {
    if (newTab !in AppTab.bottomTabCandidates) {
        return AppTab.normalizeBottomTabs(current)
    }
    val normalized = AppTab.normalizeBottomTabs(current).toMutableList()
    if (slotIndex !in normalized.indices) return normalized
    val existingIndex = normalized.indexOf(newTab)
    val oldTab = normalized[slotIndex]
    normalized[slotIndex] = newTab
    if (existingIndex != -1 && existingIndex != slotIndex) {
        normalized[existingIndex] = oldTab
    }
    return AppTab.normalizeBottomTabs(normalized)
}

internal fun buildMainUiState(
    snapshot: RepositorySnapshot,
    settings: Settings,
    local: TransientUiState,
    localNetworkTier: NetworkTier = local.localNetworkTier,
): MainUiState =
    MainUiStateMemoizer()
        .build(
            snapshot = snapshot,
            settings = settings,
            local = local,
            localNetworkTier = localNetworkTier,
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
    ): MainUiState =
        synchronized(this) {
            val effectiveEntries =
                resolveEffectiveEntries(snapshot.entries, local.optimisticFavorites)
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
            resolveEntryProjections(effectiveEntries)
            resolveFeedProjections(snapshot.feeds)
            assembleMainUiState(
                snapshot = snapshot,
                settings = settings,
                local = local,
                localNetworkTier = localNetworkTier,
                effectiveEntries = effectiveEntries,
                filtered = filtered,
                searchResults = searchResults,
            )
        }

    @Suppress("LongParameterList")
    private fun assembleMainUiState(
        snapshot: RepositorySnapshot,
        settings: Settings,
        local: TransientUiState,
        localNetworkTier: NetworkTier,
        effectiveEntries: List<InboxEntry>,
        filtered: List<InboxEntry>,
        searchResults: List<InboxEntry>,
    ): MainUiState =
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
            activePlayerNotesContent = local.activePlayerNotesContent,
            hydratingEntryIds = local.hydratingEntryIds,
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
            feeds = snapshot.feeds,
            tags = snapshot.tags,
            activePeers = snapshot.activePeers,
            localNetworkTier = localNetworkTier,
            settings = settings,
            queueEntries = resolveQueue(effectiveEntries, settings.queueItemIds),
            listeningHistoryEntries = lastListeningHistory,
            activeFeeds = lastActiveFeeds,
            archivedFeeds = lastArchivedFeeds,
            starredEntries = lastStarredEntries,
            offlineEntries = lastOfflineEntries,
            catalog = snapshot.catalog,
            companionSubTab = local.companionSubTab,
            queueSubTab = local.queueSubTab,
            selectedCatalogCollectionId = local.selectedCatalogCollectionId,
            selectedWantCollectionId = local.selectedWantCollectionId,
            isQuickLogSheetOpen = local.isQuickLogSheetOpen,
            quickLogDefaultWant = local.quickLogDefaultWant,
            quickLogTargetCollectionId = local.quickLogTargetCollectionId,
            quickLogTargetCollectionTitle = local.quickLogTargetCollectionTitle,
            drawNoteDialog = local.drawNoteDialog,
            rankSession = local.rankSession,
            isPlayerFullscreenOpen = local.isPlayerFullscreenOpen,
            isPlayerSpeedSheetOpen = local.isPlayerSpeedSheetOpen,
            activePlayerDrawerTab = local.activePlayerDrawerTab,
            undoEvent = local.undoEvent,
            statusBanner = local.statusBanner,
            messageSequence = local.messageSequence,
        )

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
                entry.copy(
                    contentText = deepMatch.contentText ?: entry.contentText,
                    contentHtml = deepMatch.contentHtml ?: entry.contentHtml,
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

internal const val SNACKBAR_TITLE_LIMIT = 36

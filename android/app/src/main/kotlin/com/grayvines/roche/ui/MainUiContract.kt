package com.grayvines.roche.ui

import com.grayvines.roche.data.ArchiveStateSnapshot
import com.grayvines.roche.data.ReaderItemContent
import com.grayvines.roche.data.RepositorySnapshot
import com.grayvines.roche.data.SyncStatusState
import com.grayvines.roche.data.WorkspaceSearchResult
import com.grayvines.roche.data.catalog.CatalogSnapshot
import com.grayvines.roche.data.catalog.CollectionDrawSnapshot
import com.grayvines.roche.data.catalog.CollectionGeneratorState
import com.grayvines.roche.data.catalog.ConsumptionStatus
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.RankSettlementSummary
import com.grayvines.roche.data.matchesSearchQuery
import com.grayvines.roche.data.remote.NetworkTier
import com.grayvines.roche.data.remote.PeerNodeDto
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.data.settings.redactedForUi
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.FeedType
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.model.TagItem
import com.grayvines.roche.model.ThemeMode
import com.grayvines.roche.playback.PlayerDrawerTab
import com.grayvines.roche.playback.findNextQueuedAudioEntry
import com.grayvines.roche.ranking.EpRankingEngine
import com.grayvines.roche.ranking.RankClusterCandidate
import java.util.Locale
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

data class RankRoundUndoStep(
    val comparisonId: String,
    val previousRoundIndex: Int,
    val previousCluster: List<RankClusterCandidate>,
    val previousJudgedClusters: List<Set<String>>,
    val previousExcludedOpponents: Set<String> = emptySet(),
    val previousTargetClusterSize: Int = EpRankingEngine.MAX_K_DEFAULT,
    val previousLoadedMaxK: Int = EpRankingEngine.MAX_K_DEFAULT,
)

data class ActiveRankSessionUiState(
    val collectionIdOrSlug: String,
    val collectionTitle: String,
    val scopeCollectionId: String? = null,
    val focusEntryId: String? = null,
    val roundIndex: Int = 0,
    val roundCap: Int = MAX_FOCUSED_RANK_ROUNDS,
    val targetClusterSize: Int = EpRankingEngine.MAX_K_DEFAULT,
    val loadedMaxK: Int = targetClusterSize,
    val collectionMemberCount: Int = EpRankingEngine.MAX_K_DEFAULT,
    val cluster: List<RankClusterCandidate> = emptyList(),
    val judgedClusters: List<Set<String>> = emptyList(),
    val skippedOpponents: Set<String> = emptySet(),
    val excludedOpponents: Set<String> = emptySet(),
    val history: List<RankRoundUndoStep> = emptyList(),
    val settlement: RankSettlementSummary? = null,
)

internal fun ActiveRankSessionUiState.maxSelectableClusterSize(): Int =
    collectionMemberCount.coerceAtLeast(cluster.size).coerceAtMost(EpRankingEngine.MAX_K_DEFAULT)

sealed interface UndoActionKind {
    data class RestoreEntryArchive(
        val itemId: String,
        val previousStatus: ItemStatus,
        val previousReadAt: String? = null,
    ) : UndoActionKind

    data class RestoreBatchArchive(val batchSnapshots: List<ArchiveStateSnapshot>) : UndoActionKind

    data class RestoreCollectionEntry(
        val collectionId: String,
        val entryId: String,
        val label: String,
    ) : UndoActionKind

    data class RestoreQueueItem(
        val entryId: String,
        val previousIndex: Int,
    ) : UndoActionKind

    data class RestoreOfflineCache(val entryId: String) : UndoActionKind
}

data class UndoActionEvent(
    val message: String,
    val kind: UndoActionKind,
) {
    val itemId: String?
        get() =
            when (kind) {
                is UndoActionKind.RestoreEntryArchive -> kind.itemId
                is UndoActionKind.RestoreBatchArchive -> kind.batchSnapshots.firstOrNull()?.itemId
                is UndoActionKind.RestoreCollectionEntry -> kind.entryId
                is UndoActionKind.RestoreQueueItem -> kind.entryId
                is UndoActionKind.RestoreOfflineCache -> kind.entryId
            }
}

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
    val inFlightOfflineEntryIds: Set<String> = emptySet(),
    val refreshingFeedIds: Set<String> = emptySet(),
    val enrichingCatalogEntryIds: Set<String> = emptySet(),
    val contextSheetEntryId: String? = null,
    val feedContextSheetId: String? = null,
    val isAddSheetOpen: Boolean = false,
    val prefilledShareUrl: String = "",
    val prefilledShareTitle: String = "",
    val prefilledShareIsFeed: Boolean = false,
    val isInitialLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val isManualRefreshing: Boolean = false,
    val syncStatus: SyncStatusState = SyncStatusState(),
    val visibleEntries: List<InboxEntry> = emptyList(),
    val unscopedInboxEntries: List<InboxEntry> =
        if (selectedFeedId == null && selectedTag == null) visibleEntries else emptyList(),
    val entriesByFeedId: Map<String, List<InboxEntry>> = emptyMap(),
    val entriesByTag: Map<String, List<InboxEntry>> = emptyMap(),
    val hasPrecomputedBuckets: Boolean = false,
    val searchResults: List<InboxEntry> = emptyList(),
    val workspaceSearch: WorkspaceSearchResult = WorkspaceSearchResult(),
    val allEntries: List<InboxEntry> = emptyList(),
    val entryById: Map<String, InboxEntry> = allEntries.associateBy { it.id },
    val readableEntryIds: Set<String> = entryById.keys,
    val feeds: List<FeedItem> = emptyList(),
    val tags: List<TagItem> = emptyList(),
    val activePeers: List<PeerNodeDto> = emptyList(),
    val localNetworkTier: NetworkTier = NetworkTier.WIFI,
    val settings: Settings = Settings(),
    val hasBearerToken: Boolean = settings.bearerToken.isNotBlank(),
    val queueEntries: List<InboxEntry> = computeQueueEntries(allEntries, settings.queueItemIds),
    val listeningHistoryEntries: List<InboxEntry> = computeListeningHistory(allEntries),
    val activeFeeds: List<FeedItem> = computeActiveFeeds(feeds),
    val archivedFeeds: List<FeedItem> = computeArchivedFeeds(feeds),
    val starredEntries: List<InboxEntry> = computeStarredEntries(allEntries),
    val offlineEntries: List<InboxEntry> = computeOfflineEntries(allEntries),
    val offlineBytesByEntryId: Map<String, Long> = emptyMap(),
    val totalOfflineCacheBytes: Long = 0L,
    val catalog: CatalogSnapshot = CatalogSnapshot(),
    val catalogUrlsById: Map<String, String> = computeCatalogUrlsById(catalog),
    val resolvingMusicEntryId: String? = null,
    val companionSubTab: CompanionSubTab = CompanionSubTab.LISTS,
    val queueSubTab: QueueSubTab = QueueSubTab.UP_NEXT,
    val selectedCatalogCollectionId: String? = null,
    val selectedWantCollectionId: String? = null,
    val catalogDetailEntryId: String? = null,
    val catalogDetailStack: List<String> = listOfNotNull(catalogDetailEntryId),
    val isQuickLogSheetOpen: Boolean = false,
    val quickLogDefaultWant: Boolean = false,
    val quickLogTargetCollectionId: String? = null,
    val quickLogTargetCollectionTitle: String? = null,
    val quickLogPrefillKind: EntryKind? = null,
    val quickLogPrefillTitle: String = "",
    val quickLogPrefillCreators: String = "",
    val quickLogPrefillReleaseYear: Int? = null,
    val quickLogExistingEntryId: String? = null,
    val drawNoteDialog: DrawNoteDialogState? = null,
    val rankSession: ActiveRankSessionUiState? = null,
    val isPlayerFullscreenOpen: Boolean = false,
    val isPlayerSpeedSheetOpen: Boolean = false,
    val activePlayerDrawerTab: PlayerDrawerTab? = null,
    val settingsHubResetTick: Int = 0,
    val undoEvent: UndoActionEvent? = null,
    val statusBanner: String? = null,
    val messageSequence: Long = 0L,
) {
    val enabledGenerators: List<CollectionGeneratorState>
        get() = catalog.generators.filter { it.enabled }

    val activeCollectionDraws: List<CollectionGeneratorState>
        get() = if (settings.showCollectionDrawsOnHome) enabledGenerators else emptyList()

    val activeReaderEntry: InboxEntry?
        get() {
            val id = selectedReaderEntryId ?: return null
            val base = entryById[id] ?: return null
            val full =
                readerContent?.takeIf { it.itemId == base.id }
                    ?: activePlayerNotesContent?.takeIf { it.itemId == base.id }
            return base.mergeReaderContent(full)
        }

    val contextSheetEntry: InboxEntry?
        get() = contextSheetEntryId?.let { entryById[it] }

    val contextSheetFeed: FeedItem?
        get() = feeds.firstOrNull { it.id == feedContextSheetId }

    val selectedFeedTitle: String?
        get() = feeds.firstOrNull { it.id == selectedFeedId }?.title

    val isReaderHydrating: Boolean
        get() = selectedReaderEntryId != null && selectedReaderEntryId in hydratingEntryIds

    val hasOpenModalSheet: Boolean
        get() =
            contextSheetEntryId != null ||
                feedContextSheetId != null ||
                isAddSheetOpen ||
                drawNoteDialog != null ||
                rankSession != null ||
                catalogDetailEntryId != null ||
                isQuickLogSheetOpen ||
                isPlayerSpeedSheetOpen ||
                activePlayerDrawerTab != null
}

fun MainUiState.isUnpinnedDestination(tab: AppTab): Boolean =
    tab != AppTab.HOME && tab !in AppTab.normalizeBottomTabs(settings.bottomNavTabs)

fun MainUiState.entriesForScope(feedId: String?, tag: String?): List<InboxEntry> =
    when {
        tag != null -> resolveScopedTagEntries(tag)
        feedId != null -> resolveScopedFeedEntries(feedId)
        else -> resolveUnscopedFallbackEntries()
    }

private fun MainUiState.resolveScopedTagEntries(tag: String): List<InboxEntry> {
    val normalized = tag.lowercase(Locale.US)
    if (hasPrecomputedBuckets) return entriesByTag[normalized].orEmpty()
    return entriesByTag[normalized]
        ?: if (selectedTag?.equals(tag, ignoreCase = true) == true) {
            visibleEntries
        } else {
            buildInboxScopeBuckets(allEntries, activeFilter).entriesByTag[normalized].orEmpty()
        }
}

private fun MainUiState.resolveScopedFeedEntries(feedId: String): List<InboxEntry> {
    if (hasPrecomputedBuckets) return entriesByFeedId[feedId].orEmpty()
    return entriesByFeedId[feedId]
        ?: if (selectedFeedId == feedId) {
            visibleEntries
        } else {
            buildInboxScopeBuckets(allEntries, activeFilter).entriesByFeedId[feedId].orEmpty()
        }
}

private fun MainUiState.resolveUnscopedFallbackEntries(): List<InboxEntry> {
    if (hasPrecomputedBuckets) return unscopedInboxEntries
    if (unscopedInboxEntries.isNotEmpty()) return unscopedInboxEntries
    return if (selectedFeedId == null && selectedTag == null && visibleEntries.isNotEmpty()) {
        visibleEntries
    } else {
        buildInboxScopeBuckets(allEntries, activeFilter).unscopedEntries
    }
}

fun MainUiState.resolveActivePlayerEntry(activeAudioEntryId: String?): InboxEntry? {
    val activeId = activeAudioEntryId ?: return null
    val base = entryById[activeId] ?: return null
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

    data object ResetSettingsToHub : NavigationUi

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
        val preferSubscribeFeed: Boolean = false,
    ) : OverlayUi

    data object CloseAddSheet : OverlayUi

    data object DismissMessages : OverlayUi

    data object OpenFullscreenPlayer : PlayerChromeUi

    data object CloseFullscreenPlayer : PlayerChromeUi

    data class SetPlayerSpeedSheetOpen(val open: Boolean) : PlayerChromeUi

    data class SelectPlayerDrawerTab(val tab: PlayerDrawerTab?) : PlayerChromeUi

    data class PlayAudioEntry(
        val entryId: String,
        val openFullscreen: Boolean = true,
    ) : PlaybackMutation

    data object TogglePlayPause : PlaybackMutation

    data class SeekPlayerToFraction(val fraction: Float) : PlaybackMutation

    data class SeekPlayerToMs(val positionMs: Long) : PlaybackMutation

    data class SkipPlayerBySeconds(val deltaSeconds: Int) : PlaybackMutation

    data object SkipToNextQueuedAudio : PlaybackMutation

    data object DismissMiniPlayer : PlaybackMutation

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

    data class CacheEntryOffline(val entryId: String) : EntryMutation

    data object EvictPlayedOfflineEntries : EntryMutation

    data class ToggleTagOnEntry(val entryId: String, val tagName: String) : EntryMutation

    data class UpdateReadProgress(val entryId: String, val progress: Double) : EntryMutation

    data class SaveUrlOrFeed(
        val url: String,
        val titleOverride: String?,
        val tagCsv: String,
        val mode: AddSheetMode? = null,
        val feedType: FeedType? = null,
    ) : EntryMutation

    data class RefreshFeed(val feedId: String) : FeedSyncAction

    data class RenameFeed(val feedId: String, val newTitle: String) : FeedSyncAction

    data class SetFeedType(val feedId: String, val feedType: FeedType) : FeedSyncAction

    data class ApplyTagToFeed(val feedId: String, val tagName: String) : FeedSyncAction

    data class ArchiveFeedUnread(val feedId: String) : FeedSyncAction

    data class ToggleFeedArchive(val feedId: String) : FeedSyncAction

    data class DeleteFeed(val feedId: String) : FeedSyncAction

    data class DeleteEntry(val entryId: String) : EntryMutation

    data object SyncNow : FeedSyncAction

    data object ManualRefresh : FeedSyncAction

    data object PollFeedsOnDeviceNow : FeedSyncAction

    data class SyncWithServerConfig(
        val serverUrl: String,
        val bearerToken: String? = null,
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

    data class OpenCatalogDetailSheet(val entryId: String) : CatalogUi

    data object PopCatalogDetailSheet : CatalogUi

    data object CloseCatalogDetailSheet : CatalogUi

    data class LaunchCollectionDraw(val draw: CollectionDrawSnapshot) : CatalogUi

    data class EnrichAndLaunchMusicDraw(val draw: CollectionDrawSnapshot) : CatalogUi

    data class OpenQuickLogSheet(
        val defaultToWant: Boolean = false,
        val targetCollectionId: String? = null,
        val targetCollectionTitle: String? = null,
        val prefillKind: EntryKind? = null,
        val prefillTitle: String = "",
        val prefillCreators: String = "",
        val prefillReleaseYear: Int? = null,
        val existingEntryId: String? = null,
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

    data class SubmitRankCluster(
        val ordering: List<List<String>>,
        val excludedEntryIds: Set<String> = emptySet(),
    ) : CatalogMutation

    data class SetRankClusterSize(
        val clusterSize: Int,
        val reloadCluster: Boolean = false,
        val manualExcludedIds: Set<String> = emptySet(),
    ) : CatalogMutation

    data class SkipRankCluster(
        val activeEntryIds: List<String> = emptyList(),
        val manualExcludedIds: Set<String> = emptySet(),
    ) : CatalogMutation

    data object UndoLastRankRound : CatalogMutation

    data object FinishRankEarly : CatalogMutation

    data object KeepRefiningRank : CatalogMutation

    data class UpdateConsumptionLog(
        val logId: String,
        val status: ConsumptionStatus? = null,
        val notes: String? = null,
        val clearNotes: Boolean = false,
        val tsIso: String? = null,
    ) : CatalogMutation

    data class DeleteConsumptionLog(val logId: String) : CatalogMutation

    data class ToggleCatalogStar(val entryId: String) : CatalogMutation

    data class ToggleCatalogWant(val entryId: String) : CatalogMutation

    data class AddCatalogEntryToList(
        val collectionId: String,
        val entryId: String,
    ) : CatalogMutation

    data class RefreshCatalogEntryMetadata(val entryId: String) : CatalogMutation

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
        val existingEntryId: String? = null,
        val thumbnailUrl: String? = null,
        val identifiers: List<com.grayvines.roche.data.catalog.EntryIdentifierDto> = emptyList(),
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
        val bearerToken: String? = null,
        val feedback: ConfigSaveFeedback = ConfigSaveFeedback.SHOW_BANNER,
    ) : SettingsServerAction

    data object DisconnectServer : SettingsServerAction

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
    val workspaceSearch: WorkspaceSearchResult = WorkspaceSearchResult(),
    val optimisticFavorites: Map<String, Boolean> = emptyMap(),
    val selectedTag: String? = null,
    val selectedFeedId: String? = null,
    val scopeOriginTab: AppTab? = null,
    val selectedReaderEntryId: String? = null,
    val readerContent: ReaderItemContent? = null,
    val activePlayerNotesContent: ReaderItemContent? = null,
    val hydratingEntryIds: Set<String> = emptySet(),
    val refreshingFeedIds: Set<String> = emptySet(),
    val enrichingCatalogEntryIds: Set<String> = emptySet(),
    val contextSheetEntryId: String? = null,
    val feedContextSheetId: String? = null,
    val isAddSheetOpen: Boolean = false,
    val prefilledShareUrl: String = "",
    val prefilledShareTitle: String = "",
    val prefilledShareIsFeed: Boolean = false,
    val isSyncing: Boolean = false,
    val companionSubTab: CompanionSubTab = CompanionSubTab.LISTS,
    val queueSubTab: QueueSubTab = QueueSubTab.UP_NEXT,
    val selectedCatalogCollectionId: String? = null,
    val selectedWantCollectionId: String? = null,
    val catalogDetailStack: List<String> = emptyList(),
    val isQuickLogSheetOpen: Boolean = false,
    val quickLogDefaultWant: Boolean = false,
    val quickLogTargetCollectionId: String? = null,
    val quickLogTargetCollectionTitle: String? = null,
    val quickLogPrefillKind: EntryKind? = null,
    val quickLogPrefillTitle: String = "",
    val quickLogPrefillCreators: String = "",
    val quickLogPrefillReleaseYear: Int? = null,
    val quickLogExistingEntryId: String? = null,
    val drawNoteDialog: DrawNoteDialogState? = null,
    val rankSession: ActiveRankSessionUiState? = null,
    val isPlayerFullscreenOpen: Boolean = false,
    val isPlayerSpeedSheetOpen: Boolean = false,
    val activePlayerDrawerTab: PlayerDrawerTab? = null,
    val localNetworkTier: NetworkTier = NetworkTier.WIFI,
    val undoEvent: UndoActionEvent? = null,
    val statusBanner: String? = null,
    val messageSequence: Long = 0L,
) {
    val catalogDetailEntryId: String?
        get() = catalogDetailStack.lastOrNull()
}

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
        workspaceSearch = WorkspaceSearchResult(),
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
    val baseline = AppTab.normalizeBottomTabs(current)
    if (newTab !in AppTab.bottomTabCandidates || slotIndex !in baseline.indices) {
        return baseline
    }
    val normalized = baseline.toMutableList()
    val existingIndex = normalized.indexOf(newTab)
    val oldTab = normalized[slotIndex]
    normalized[slotIndex] = newTab
    if (existingIndex != -1 && existingIndex != slotIndex) {
        normalized[existingIndex] = oldTab
    }
    return if (AppTab.hasSettingsAccessTab(normalized)) {
        AppTab.normalizeBottomTabs(normalized)
    } else {
        baseline
    }
}

@Suppress("LongMethod", "LongParameterList")
internal fun assembleMainUiState(
    snapshot: RepositorySnapshot,
    inboxDerived: InboxFeedsDerivedState,
    catalogProjections: CatalogProjections,
    searchProjection: SearchProjection,
    settings: Settings,
    local: TransientUiState,
    resolvingMusicEntryId: String?,
    settingsHubResetTick: Int,
): MainUiState {
    val isSyncing = local.isSyncing || inboxDerived.isSyncActive
    val visible =
        inboxDerived.scopeBuckets.resolveVisible(
            selectedFeedId = local.selectedFeedId,
            selectedTag = local.selectedTag,
        )
    return MainUiState(
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
        inFlightOfflineEntryIds = inboxDerived.inFlightOfflineEntryIds,
        refreshingFeedIds = local.refreshingFeedIds,
        enrichingCatalogEntryIds = local.enrichingCatalogEntryIds,
        contextSheetEntryId = local.contextSheetEntryId,
        feedContextSheetId = local.feedContextSheetId,
        isAddSheetOpen = local.isAddSheetOpen,
        prefilledShareUrl = local.prefilledShareUrl,
        prefilledShareTitle = local.prefilledShareTitle,
        prefilledShareIsFeed = local.prefilledShareIsFeed,
        isInitialLoading = false,
        isSyncing = isSyncing,
        isManualRefreshing = inboxDerived.isManualRefreshing,
        syncStatus = inboxDerived.syncStatus,
        visibleEntries = visible,
        unscopedInboxEntries = inboxDerived.scopeBuckets.unscopedEntries,
        entriesByFeedId = inboxDerived.scopeBuckets.entriesByFeedId,
        entriesByTag = inboxDerived.scopeBuckets.entriesByTag,
        hasPrecomputedBuckets = true,
        searchResults = searchProjection.results,
        workspaceSearch = searchProjection.workspaceResult,
        allEntries = inboxDerived.entryProjections.entries,
        entryById = inboxDerived.entryProjections.entryById,
        readableEntryIds = inboxDerived.entryProjections.readableEntryIds,
        feeds = snapshot.feeds,
        tags = snapshot.tags,
        activePeers = snapshot.activePeers,
        localNetworkTier = local.localNetworkTier,
        settings = settings.redactedForUi(),
        hasBearerToken = settings.bearerToken.isNotBlank(),
        queueEntries = inboxDerived.queueEntries,
        listeningHistoryEntries = inboxDerived.entryProjections.listeningHistoryEntries,
        activeFeeds = inboxDerived.feedProjections.activeFeeds,
        archivedFeeds = inboxDerived.feedProjections.archivedFeeds,
        starredEntries = inboxDerived.entryProjections.starredEntries,
        offlineEntries = inboxDerived.entryProjections.offlineEntries,
        offlineBytesByEntryId = inboxDerived.offlineSizes.bytesByEntryId,
        totalOfflineCacheBytes = inboxDerived.offlineSizes.totalBytes,
        catalog = catalogProjections.catalog,
        catalogUrlsById = catalogProjections.catalogUrlsById,
        resolvingMusicEntryId = resolvingMusicEntryId,
        companionSubTab = local.companionSubTab,
        queueSubTab = local.queueSubTab,
        selectedCatalogCollectionId = local.selectedCatalogCollectionId,
        selectedWantCollectionId = local.selectedWantCollectionId,
        catalogDetailEntryId = local.catalogDetailEntryId,
        catalogDetailStack = local.catalogDetailStack,
        isQuickLogSheetOpen = local.isQuickLogSheetOpen,
        quickLogDefaultWant = local.quickLogDefaultWant,
        quickLogTargetCollectionId = local.quickLogTargetCollectionId,
        quickLogTargetCollectionTitle = local.quickLogTargetCollectionTitle,
        quickLogPrefillKind = local.quickLogPrefillKind,
        quickLogPrefillTitle = local.quickLogPrefillTitle,
        quickLogPrefillCreators = local.quickLogPrefillCreators,
        quickLogPrefillReleaseYear = local.quickLogPrefillReleaseYear,
        quickLogExistingEntryId = local.quickLogExistingEntryId,
        drawNoteDialog = local.drawNoteDialog,
        rankSession = local.rankSession,
        isPlayerFullscreenOpen = local.isPlayerFullscreenOpen,
        isPlayerSpeedSheetOpen = local.isPlayerSpeedSheetOpen,
        activePlayerDrawerTab = local.activePlayerDrawerTab,
        settingsHubResetTick = settingsHubResetTick,
        undoEvent = local.undoEvent,
        statusBanner = local.statusBanner,
        messageSequence = local.messageSequence,
    )
}

internal fun buildMainUiState(
    snapshot: RepositorySnapshot,
    settings: Settings,
    local: TransientUiState,
    localNetworkTier: NetworkTier = local.localNetworkTier,
    syncStatus: SyncStatusState = SyncStatusState(),
): MainUiState {
    val effectiveEntries = applyOptimisticFavorites(snapshot.entries, local.optimisticFavorites)
    val entryProjections = buildEntryProjections(effectiveEntries)
    val scopeBuckets = buildInboxScopeBuckets(effectiveEntries, local.activeFilter)
    val feedProjections = buildFeedProjections(snapshot.feeds)
    val normalizedQuery = local.searchQuery.trim().lowercase()
    val searchResults =
        resolveMergedSearchResults(
            entries = effectiveEntries,
            normalizedQuery = normalizedQuery,
            deepSearchQuery = local.deepSearchQuery,
            deepSearchResults = local.workspaceSearch.entries,
        )
    val activeWorkspace =
        if (normalizedQuery.isNotBlank() && local.deepSearchQuery == normalizedQuery) {
            local.workspaceSearch
        } else {
            WorkspaceSearchResult()
        }
    return assembleMainUiState(
        snapshot = snapshot,
        inboxDerived =
            InboxFeedsDerivedState(
                entryProjections = entryProjections,
                scopeBuckets = scopeBuckets,
                queueEntries = computeQueueEntries(effectiveEntries, settings.queueItemIds),
                feedProjections = feedProjections,
                syncStatus = syncStatus,
            ),
        catalogProjections = buildCatalogProjections(snapshot.catalog),
        searchProjection =
            SearchProjection(results = searchResults, workspaceResult = activeWorkspace),
        settings = settings,
        local = local.copy(localNetworkTier = localNetworkTier),
        resolvingMusicEntryId = null,
        settingsHubResetTick = 0,
    )
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
            entry.isPlayableAudio &&
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
internal const val MAX_FOCUSED_RANK_ROUNDS = 5

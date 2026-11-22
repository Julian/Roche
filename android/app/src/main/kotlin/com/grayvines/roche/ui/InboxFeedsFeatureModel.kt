package com.grayvines.roche.ui

import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.data.RepositorySnapshot
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.SyncStatusState
import com.grayvines.roche.data.matchesInboxFilter
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal data class EntryProjections(
    val entries: List<InboxEntry> = emptyList(),
    val entryById: Map<String, InboxEntry> = emptyMap(),
    val readableEntryIds: Set<String> = emptySet(),
    val listeningHistoryEntries: List<InboxEntry> = emptyList(),
    val starredEntries: List<InboxEntry> = emptyList(),
    val offlineEntries: List<InboxEntry> = emptyList(),
)

internal data class InboxScopeBuckets(
    val unscopedEntries: List<InboxEntry> = emptyList(),
    val entriesByFeedId: Map<String, List<InboxEntry>> = emptyMap(),
    val entriesByTag: Map<String, List<InboxEntry>> = emptyMap(),
)

internal fun InboxScopeBuckets.resolveVisible(
    selectedFeedId: String?,
    selectedTag: String?,
): List<InboxEntry> =
    when {
        selectedTag != null -> entriesByTag[selectedTag.lowercase(Locale.US)].orEmpty()
        selectedFeedId != null -> entriesByFeedId[selectedFeedId].orEmpty()
        else -> unscopedEntries
    }

internal data class FeedProjections(
    val activeFeeds: List<FeedItem> = emptyList(),
    val archivedFeeds: List<FeedItem> = emptyList(),
)

internal data class OfflineCacheSizes(
    val bytesByEntryId: Map<String, Long> = emptyMap(),
    val totalBytes: Long = 0L,
)

internal data class SyncAndOfflineMeta(
    val offlineSizes: OfflineCacheSizes = OfflineCacheSizes(),
    val syncStatus: SyncStatusState = SyncStatusState(),
    val isManualRefreshing: Boolean = false,
    val isSyncActive: Boolean = false,
    val inFlightOfflineEntryIds: Set<String> = emptySet(),
)

internal data class InboxFeedsDerivedState(
    val entryProjections: EntryProjections = EntryProjections(),
    val scopeBuckets: InboxScopeBuckets = InboxScopeBuckets(),
    val queueEntries: List<InboxEntry> = emptyList(),
    val feedProjections: FeedProjections = FeedProjections(),
    val offlineSizes: OfflineCacheSizes = OfflineCacheSizes(),
    val syncStatus: SyncStatusState = SyncStatusState(),
    val isManualRefreshing: Boolean = false,
    val isSyncActive: Boolean = false,
    val inFlightOfflineEntryIds: Set<String> = emptySet(),
)

internal class InboxFeedsFeatureModel(
    private val scope: CoroutineScope,
    private val repository: RocheRepository,
) {
    private val _isManualRefreshing = MutableStateFlow(false)
    val isManualRefreshing: StateFlow<Boolean> = _isManualRefreshing.asStateFlow()

    private val _offlineSizes = MutableStateFlow(OfflineCacheSizes())
    val offlineSizes: StateFlow<OfflineCacheSizes> = _offlineSizes.asStateFlow()

    val syncStatus: StateFlow<SyncStatusState> = repository.syncStatus

    val syncAndOfflineMetaFlow: Flow<SyncAndOfflineMeta> =
        combine(
            offlineSizes,
            syncStatus,
            isManualRefreshing,
            repository.isSyncActive,
            repository.inFlightOfflineEntryIds,
        ) { _, _, _, _, _ ->
            currentSyncAndOfflineMeta()
        }

    fun currentSyncAndOfflineMeta(): SyncAndOfflineMeta =
        SyncAndOfflineMeta(
            offlineSizes = offlineSizes.value,
            syncStatus = syncStatus.value,
            isManualRefreshing = isManualRefreshing.value,
            isSyncActive = repository.isSyncActive.value,
            inFlightOfflineEntryIds = repository.inFlightOfflineEntryIds.value,
        )

    fun startObservingOfflineSizes(snapshotFlow: Flow<RepositorySnapshot>) {
        scope.launch {
            snapshotFlow
                .map { snap -> snap.entries.filter { it.isCachedOffline }.map { it.id } }
                .distinctUntilChanged()
                .collect { cachedIds ->
                    if (cachedIds.isEmpty()) {
                        _offlineSizes.value = OfflineCacheSizes()
                    } else {
                        val byId = runSuspendCatching {
                            repository.cachedItemBytesByEntryId()
                        }
                            .getOrDefault(emptyMap())
                        _offlineSizes.value =
                            OfflineCacheSizes(bytesByEntryId = byId, totalBytes = byId.values.sum())
                    }
                }
        }
    }

    fun setManualRefreshing(refreshing: Boolean) {
        _isManualRefreshing.value = refreshing
    }

    fun refreshOfflineSizes() {
        scope.launch {
            val byId = runSuspendCatching {
                repository.cachedItemBytesByEntryId()
            }
                .getOrDefault(emptyMap())
            val total = byId.values.sum()
            _offlineSizes.value = OfflineCacheSizes(bytesByEntryId = byId, totalBytes = total)
        }
    }
}

internal fun buildEntryProjections(effectiveEntries: List<InboxEntry>): EntryProjections {
    if (effectiveEntries.isEmpty()) return EntryProjections()
    val byId = LinkedHashMap<String, InboxEntry>(effectiveEntries.size)
    for (entry in effectiveEntries) {
        byId[entry.id] = entry
    }
    return EntryProjections(
        entries = effectiveEntries,
        entryById = byId,
        readableEntryIds = byId.keys,
        listeningHistoryEntries = computeListeningHistory(effectiveEntries),
        starredEntries = computeStarredEntries(effectiveEntries),
        offlineEntries = computeOfflineEntries(effectiveEntries),
    )
}

internal fun buildInboxScopeBuckets(
    effectiveEntries: List<InboxEntry>,
    activeFilter: InboxFilter,
): InboxScopeBuckets {
    if (effectiveEntries.isEmpty()) return InboxScopeBuckets()
    val unscoped = ArrayList<InboxEntry>()
    val byFeed = LinkedHashMap<String, MutableList<InboxEntry>>()
    val byTag = LinkedHashMap<String, MutableList<InboxEntry>>()
    for (entry in effectiveEntries) {
        if (!matchesInboxFilter(entry, activeFilter)) continue
        if (entry.inFeedInbox) {
            unscoped.add(entry)
        }
        entry.feedId?.let { fid -> byFeed.getOrPut(fid) { mutableListOf() }.add(entry) }
        if (entry.tags.isNotEmpty()) {
            val seenTags = HashSet<String>(entry.tags.size)
            for (tag in entry.tags) {
                val normalizedTag = tag.lowercase(Locale.US)
                if (seenTags.add(normalizedTag)) {
                    byTag.getOrPut(normalizedTag) { mutableListOf() }.add(entry)
                }
            }
        }
    }
    return InboxScopeBuckets(
        unscopedEntries = unscoped,
        entriesByFeedId = byFeed,
        entriesByTag = byTag,
    )
}

internal fun buildFeedProjections(feeds: List<FeedItem>): FeedProjections =
    FeedProjections(
        activeFeeds = computeActiveFeeds(feeds),
        archivedFeeds = computeArchivedFeeds(feeds),
    )

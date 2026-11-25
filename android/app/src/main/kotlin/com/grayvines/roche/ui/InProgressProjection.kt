package com.grayvines.roche.ui

import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.core.parseIsoInstant
import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.data.RepositorySnapshot
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.catalog.DiaryEntryItem
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.playback.PodcastPlaybackOrchestrator
import com.grayvines.roche.playback.PodcastPlayerController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

internal fun computeInProgressItems(
    allEntries: List<InboxEntry>,
    activeStartedEntries: List<DiaryEntryItem>,
): List<InProgressItem> {
    if (allEntries.isEmpty() && activeStartedEntries.isEmpty()) return emptyList()
    val byEntryId = LinkedHashMap<String, InProgressItem>()
    allEntries
        .filter { it.isInProgress }
        .forEach { entry ->
            byEntryId[entry.id] =
                InProgressItem.ReaderItem(
                    entry = entry,
                    activityTs = resolveReaderActivityTs(entry),
                )
        }
    activeStartedEntries.forEach { started -> mergeStartedIntoInProgress(byEntryId, started) }
    return byEntryId.values.sortedWith { a, b ->
        val tsCmp = compareIsoDesc(a.activityTs, b.activityTs)
        if (tsCmp != 0) tsCmp else b.entryId.compareTo(a.entryId)
    }
}

private fun resolveReaderActivityTs(entry: InboxEntry): String =
    entry.interactionUpdatedAt?.takeIf { it.isNotBlank() }
        ?: entry.readAt?.takeIf { it.isNotBlank() }
        ?: entry.publishedAt?.takeIf { it.isNotBlank() }
        ?: entry.createdAt

private fun mergeStartedIntoInProgress(
    byEntryId: MutableMap<String, InProgressItem>,
    started: DiaryEntryItem,
) {
    val existing = byEntryId[started.entryId]
    if (existing is InProgressItem.ReaderItem) {
        val latestTs =
            if (compareIsoDesc(started.ts, existing.activityTs) < 0) {
                started.ts
            } else {
                existing.activityTs
            }
        byEntryId[started.entryId] = existing.copy(activityTs = latestTs)
    } else if (existing == null) {
        byEntryId[started.entryId] =
            InProgressItem.StartedCatalogItem(item = started, activityTs = started.ts)
    }
}

private fun compareIsoDesc(a: String, b: String): Int {
    val instA = parseIsoInstant(a)
    val instB = parseIsoInstant(b)
    return when {
        instA != null && instB != null -> {
            instB.compareTo(instA)
        }
        instA != null -> {
            -1
        }
        instB != null -> {
            1
        }
        else -> {
            b.compareTo(a)
        }
    }
}

internal suspend fun migrateLegacyNonAudioQueue(
    repository: RocheRepository,
    settingsRepository: SettingsRepository,
    playbackOrchestrator: PodcastPlaybackOrchestrator,
    playerController: PodcastPlayerController,
    snapshotFlow: Flow<RepositorySnapshot>,
) {
    val currentSettings = settingsRepository.settings.first()
    if (currentSettings.migratedNonAudioQueueToWantRead) return
    if (currentSettings.queueItemIds.isEmpty()) {
        settingsRepository.update { it.copy(migratedNonAudioQueueToWantRead = true) }
        return
    }
    // Wait until Room has entries (e.g., after initial peer sync completes) before running the
    // one-shot migration so legacy IDs are migrated in-session rather than dropped on cold start.
    val snapshot = snapshotFlow.first { it.entries.isNotEmpty() }

    val outcome =
        migrateLegacyIdsForSnapshot(
            queueItemIds = currentSettings.queueItemIds,
            entries = snapshot.entries,
            repository = repository,
        )
    settingsRepository.update { current ->
        val updatedIds =
            current.queueItemIds
                .asSequence()
                .filterNot { it in outcome.removableIds }
                .map { id -> outcome.rewrites[id] ?: id }
                .distinct()
                .toList()
        current.copy(
            queueItemIds = updatedIds,
            migratedNonAudioQueueToWantRead = !outcome.hadFailure,
        )
    }
    playbackOrchestrator.syncActivePlayerQueueState(playerController)
}

private data class LegacyQueueMigrationOutcome(
    val removableIds: Set<String>,
    val rewrites: Map<String, String>,
    val hadFailure: Boolean,
)

private sealed interface LegacyEntryLookup {
    data class Found(val entry: InboxEntry) : LegacyEntryLookup

    data object Missing : LegacyEntryLookup

    data object Failed : LegacyEntryLookup
}

private suspend fun migrateLegacyIdsForSnapshot(
    queueItemIds: List<String>,
    entries: List<InboxEntry>,
    repository: RocheRepository,
): LegacyQueueMigrationOutcome {
    val byId = entries.associateBy { it.id }
    val removable = HashSet<String>()
    val rewrites = HashMap<String, String>()
    var hadFailure = false
    for (rawId in queueItemIds) {
        when (val lookup = resolveLegacyQueuedEntry(rawId, byId, repository)) {
            LegacyEntryLookup.Missing -> {
                removable.add(rawId)
            }
            LegacyEntryLookup.Failed -> {
                hadFailure = true
            }
            is LegacyEntryLookup.Found -> {
                val ok =
                    applyFoundLegacyQueueEntry(rawId, lookup.entry, repository, removable, rewrites)
                if (!ok) hadFailure = true
            }
        }
    }
    return LegacyQueueMigrationOutcome(
        removableIds = removable,
        rewrites = rewrites,
        hadFailure = hadFailure,
    )
}

private suspend fun applyFoundLegacyQueueEntry(
    rawId: String,
    entry: InboxEntry,
    repository: RocheRepository,
    removable: MutableSet<String>,
    rewrites: MutableMap<String, String>,
): Boolean =
    when {
        entry.isPlayableAudio -> {
            if (entry.id != rawId) rewrites[rawId] = entry.id
            true
        }
        entry.isArchived || entry.isFinished -> {
            removable.add(rawId)
            true
        }
        else -> {
            val added = runSuspendCatching {
                repository.catalog.addCollectionEntry(WANT_TO_READ_COLLECTION_ID, entry.id)
            }
                .isSuccess
            if (added) removable.add(rawId)
            added
        }
    }

private suspend fun resolveLegacyQueuedEntry(
    rawId: String,
    byId: Map<String, InboxEntry>,
    repository: RocheRepository,
): LegacyEntryLookup =
    byId[rawId]?.let { LegacyEntryLookup.Found(it) }
        ?: runSuspendCatching { repository.catalog.followMergedId(rawId) }
            .fold(
                onSuccess = { canonicalId ->
                    canonicalId?.let { byId[it] }?.let { LegacyEntryLookup.Found(it) }
                        ?: LegacyEntryLookup.Missing
                },
                onFailure = { LegacyEntryLookup.Failed },
            )

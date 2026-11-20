package com.grayvines.roche.playback

import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry

internal fun findNextQueuedAudioEntry(
    entries: List<InboxEntry>,
    queueItemIds: List<String>,
    excludeEntryId: String?,
): InboxEntry? {
    if (queueItemIds.isEmpty()) return null
    val entriesById = entries.associateBy { it.id }
    return findNextQueuedAudioEntry(entriesById, queueItemIds, excludeEntryId)
}

internal fun findNextQueuedAudioEntry(
    entriesById: Map<String, InboxEntry>,
    queueItemIds: List<String>,
    excludeEntryId: String?,
): InboxEntry? =
    queueItemIds
        .asSequence()
        .filter { it != excludeEntryId }
        .mapNotNull { entriesById[it] }
        .firstOrNull { !it.isArchived && it.contentType == ContentType.AUDIO }

internal fun hasNextQueuedAudio(
    entries: List<InboxEntry>,
    queueItemIds: List<String>,
    excludeEntryId: String?,
): Boolean = findNextQueuedAudioEntry(entries, queueItemIds, excludeEntryId) != null

internal fun hasNextQueuedAudio(
    entriesById: Map<String, InboxEntry>,
    queueItemIds: List<String>,
    excludeEntryId: String?,
): Boolean = findNextQueuedAudioEntry(entriesById, queueItemIds, excludeEntryId) != null

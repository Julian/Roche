package com.grayvines.roche.data

import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.model.ItemStatus

internal fun filterInboxEntries(
    entries: List<InboxEntry>,
    filter: InboxFilter,
    searchQuery: String = "",
    selectedTag: String? = null,
    selectedFeedId: String? = null,
): List<InboxEntry> {
    val normalizedQuery = searchQuery.trim().lowercase()
    return entries.filter { entry ->
        matchesScope(entry, filter, selectedTag, selectedFeedId) &&
            matchesSearchQuery(entry, normalizedQuery)
    }
}

internal fun matchesScope(
    entry: InboxEntry,
    filter: InboxFilter,
    selectedTag: String?,
    selectedFeedId: String?,
): Boolean =
    when {
        selectedTag != null ->
            !entry.isArchived && entry.tags.any { it.equals(selectedTag, ignoreCase = true) }
        selectedFeedId != null -> !entry.isArchived && entry.feedId == selectedFeedId
        else -> matchesInboxFilter(entry, filter)
    }

internal fun matchesInboxFilter(entry: InboxEntry, filter: InboxFilter): Boolean =
    when (filter) {
        InboxFilter.UNREAD -> entry.status == ItemStatus.UNREAD
        InboxFilter.ARTICLES -> !entry.isArchived && entry.contentType == ContentType.ARTICLE
        InboxFilter.PAPERS -> !entry.isArchived && entry.contentType == ContentType.PDF
        InboxFilter.VIDEOS -> !entry.isArchived && entry.contentType == ContentType.VIDEO
        InboxFilter.STARRED -> entry.isFavorite
        InboxFilter.ARCHIVED -> entry.isArchived
    }

internal fun matchesSearchQuery(entry: InboxEntry, normalizedQuery: String): Boolean {
    if (normalizedQuery.isBlank()) return true
    val cleanQuery = normalizedQuery.trimStart('#')
    if (cleanQuery.isBlank()) return true
    return entry.title.lowercase().contains(cleanQuery) ||
        entry.feedTitle.lowercase().contains(cleanQuery) ||
        entry.author.orEmpty().lowercase().contains(cleanQuery) ||
        entry.url.lowercase().contains(cleanQuery) ||
        entry.summary.orEmpty().lowercase().contains(cleanQuery) ||
        entry.contentText.orEmpty().lowercase().contains(cleanQuery) ||
        entry.contentHtml.orEmpty().lowercase().contains(cleanQuery) ||
        entry.tags.any { it.lowercase().contains(cleanQuery) }
}

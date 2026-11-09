package com.grayvines.roche.data

import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter

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
        InboxFilter.ALL -> !entry.isArchived
        InboxFilter.UNREAD -> entry.isUnread
        InboxFilter.ARTICLES -> !entry.isArchived && entry.contentType == ContentType.ARTICLE
        InboxFilter.PAPERS -> !entry.isArchived && entry.contentType == ContentType.PDF
        InboxFilter.VIDEOS -> !entry.isArchived && entry.contentType == ContentType.VIDEO
        InboxFilter.STARRED -> entry.isFavorite
        InboxFilter.ARCHIVED -> entry.isArchived
    }

internal enum class SearchScopeFilter(val label: String) {
    ALL("All"),
    UNREAD("Unread"),
    STARRED("Starred"),
    ARTICLES("Articles"),
    PAPERS("Papers"),
    VIDEOS("Videos"),
}

internal fun matchesSearchScopeFilter(entry: InboxEntry, scope: SearchScopeFilter): Boolean =
    when (scope) {
        SearchScopeFilter.ALL -> true
        SearchScopeFilter.UNREAD -> entry.isUnread
        SearchScopeFilter.STARRED -> entry.isFavorite
        SearchScopeFilter.ARTICLES -> entry.contentType == ContentType.ARTICLE
        SearchScopeFilter.PAPERS -> entry.contentType == ContentType.PDF
        SearchScopeFilter.VIDEOS -> entry.contentType == ContentType.VIDEO
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

internal fun extractSearchMatchSnippet(entry: InboxEntry, rawQuery: String): String? {
    val cleanQuery = rawQuery.trim().trimStart('#')
    if (cleanQuery.length < MIN_SNIPPET_QUERY_LEN) return null
    val candidates =
        listOfNotNull(
            entry.contentText?.takeIf { it.isNotBlank() },
            entry.summary?.let { stripBasicHtmlTags(it) }?.takeIf { it.isNotBlank() },
            entry.contentHtml?.let { stripBasicHtmlTags(it) }?.takeIf { it.isNotBlank() },
        )
    for (source in candidates) {
        val normalizedSource = source.replace(WHITESPACE_REGEX, " ").trim()
        val matchIndex = normalizedSource.indexOf(cleanQuery, ignoreCase = true)
        if (matchIndex != -1) {
            val start = (matchIndex - SNIPPET_CONTEXT_RADIUS).coerceAtLeast(0)
            val end =
                (matchIndex + cleanQuery.length + SNIPPET_CONTEXT_RADIUS).coerceAtMost(
                    normalizedSource.length
                )
            val prefix = if (start > 0) "…" else ""
            val suffix = if (end < normalizedSource.length) "…" else ""
            return "$prefix${normalizedSource.substring(start, end).trim()}$suffix"
        }
    }
    return null
}

private fun stripBasicHtmlTags(raw: String): String =
    raw.replace(HTML_TAG_STRIP_REGEX, " ").replace("&nbsp;", " ")

private const val MIN_SNIPPET_QUERY_LEN = 2
private const val SNIPPET_CONTEXT_RADIUS = 48
private val HTML_TAG_STRIP_REGEX = Regex("""<[^>]+>""")
private val WHITESPACE_REGEX = Regex("""\s+""")

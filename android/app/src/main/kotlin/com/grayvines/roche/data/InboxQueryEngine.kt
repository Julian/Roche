package com.grayvines.roche.data

import com.grayvines.roche.data.catalog.CatalogEntryItem
import com.grayvines.roche.data.catalog.CatalogSnapshot
import com.grayvines.roche.data.catalog.CollectionMode
import com.grayvines.roche.data.catalog.EntryKind
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
            entry.tags.any { it.equals(selectedTag, ignoreCase = true) } &&
                matchesInboxFilter(entry, filter)
        selectedFeedId != null ->
            entry.feedId == selectedFeedId && matchesInboxFilter(entry, filter)
        else -> entry.inFeedInbox && matchesInboxFilter(entry, filter)
    }

internal fun matchesInboxFilter(entry: InboxEntry, filter: InboxFilter): Boolean =
    when (filter) {
        InboxFilter.UNREAD -> entry.isNew
        InboxFilter.FINISHED -> entry.isFinished
        InboxFilter.ARCHIVED -> entry.isDismissed
        InboxFilter.ALL -> true
        InboxFilter.STARRED -> entry.isFavorite
        else -> entry.isNew && matchesActiveMediaFilter(entry, filter)
    }

private fun matchesActiveMediaFilter(entry: InboxEntry, filter: InboxFilter): Boolean =
    when (filter) {
        InboxFilter.ARTICLES -> entry.contentType == ContentType.ARTICLE
        InboxFilter.PAPERS -> entry.contentType == ContentType.PDF
        InboxFilter.VIDEOS -> entry.contentType == ContentType.VIDEO
        InboxFilter.PODCASTS -> entry.isPodcastEpisode
        InboxFilter.AUDIO -> entry.isPlayableAudio
        else -> false
    }

internal enum class SearchScopeFilter(val label: String) {
    ALL("All"),
    CATALOG("Catalog"),
    LISTS("Lists"),
    DIARY("Diary"),
    UNREAD("New"),
    STARRED("Starred"),
    ARTICLES("Articles"),
    PAPERS("Papers"),
    VIDEOS("Videos"),
    PODCASTS("Podcasts"),
    AUDIO("Audio"),
}

internal data class CatalogSearchMatch(
    val entry: CatalogEntryItem,
    val tier: Char? = null,
)

internal data class CatalogSearchResult(
    val matches: List<CatalogSearchMatch>,
    val totalCount: Int,
)

internal fun matchesSearchScopeFilter(entry: InboxEntry, scope: SearchScopeFilter): Boolean =
    when (scope) {
        SearchScopeFilter.ALL -> true
        SearchScopeFilter.CATALOG,
        SearchScopeFilter.LISTS,
        SearchScopeFilter.DIARY -> false
        SearchScopeFilter.UNREAD -> entry.isNew
        SearchScopeFilter.STARRED -> entry.isFavorite
        SearchScopeFilter.ARTICLES -> entry.contentType == ContentType.ARTICLE
        SearchScopeFilter.PAPERS -> entry.contentType == ContentType.PDF
        SearchScopeFilter.VIDEOS -> entry.contentType == ContentType.VIDEO
        SearchScopeFilter.PODCASTS -> entry.isPodcastEpisode
        SearchScopeFilter.AUDIO -> entry.isPlayableAudio
    }

internal fun searchCatalogEntriesWithTotal(
    catalog: CatalogSnapshot,
    rawQuery: String,
    includeWhenBlank: Boolean = false,
    limit: Int = MAX_CATALOG_SEARCH_RESULTS,
): CatalogSearchResult {
    val cleanQuery = rawQuery.trim().trimStart('#').trim()
    if (cleanQuery.isEmpty() && !includeWhenBlank) {
        return CatalogSearchResult(matches = emptyList(), totalCount = 0)
    }
    val tierById = buildCatalogTierIndex(catalog)
    val filtered =
        collectCatalogSearchEntries(catalog).filter { entry ->
            cleanQuery.isEmpty() || matchesCatalogQuery(entry, cleanQuery)
        }
    val matches =
        filtered.sortedWith(catalogSearchComparator(cleanQuery, tierById)).take(limit).map { entry
            ->
            CatalogSearchMatch(entry = entry, tier = tierById[entry.id])
        }
    return CatalogSearchResult(matches = matches, totalCount = filtered.size)
}

internal fun searchCatalogEntries(
    catalog: CatalogSnapshot,
    rawQuery: String,
    includeWhenBlank: Boolean = false,
    limit: Int = MAX_CATALOG_SEARCH_RESULTS,
): List<CatalogSearchMatch> =
    searchCatalogEntriesWithTotal(
            catalog = catalog,
            rawQuery = rawQuery,
            includeWhenBlank = includeWhenBlank,
            limit = limit,
        )
        .matches

private fun buildCatalogTierIndex(catalog: CatalogSnapshot): Map<String, Char> = buildMap {
    catalog.lists
        .filter { it.mode == CollectionMode.DYNAMIC }
        .forEach { summary ->
            catalog.entriesByCollectionId[summary.collectionId].orEmpty().forEach { ranked ->
                if (ranked.tier != null) put(ranked.entry.id, ranked.tier)
            }
        }
}

private fun collectCatalogSearchEntries(catalog: CatalogSnapshot): List<CatalogEntryItem> {
    val byId = LinkedHashMap<String, CatalogEntryItem>()
    catalog.entriesById.values
        .filter { it.kind in SEARCHABLE_CATALOG_KINDS }
        .forEach { byId[it.id] = it }
    catalog.entriesByCollectionId.values
        .asSequence()
        .flatten()
        .map { it.entry }
        .filter { it.kind in SEARCHABLE_CATALOG_KINDS }
        .forEach { byId.putIfAbsent(it.id, it) }
    return byId.values.toList()
}

private fun matchesCatalogQuery(entry: CatalogEntryItem, cleanQuery: String): Boolean =
    entry.title.contains(cleanQuery, ignoreCase = true) ||
        entry.creators.any { it.contains(cleanQuery, ignoreCase = true) } ||
        entry.releaseYear?.toString()?.contains(cleanQuery) == true ||
        entry.kind.displayLabel.contains(cleanQuery, ignoreCase = true)

private fun catalogSearchComparator(
    cleanQuery: String,
    tierById: Map<String, Char>,
): Comparator<CatalogEntryItem> =
    compareByDescending<CatalogEntryItem> {
            cleanQuery.isNotEmpty() && it.title.startsWith(cleanQuery, ignoreCase = true)
        }
        .thenByDescending { it.id in tierById }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }

private const val MAX_CATALOG_SEARCH_RESULTS = 24
private val SEARCHABLE_CATALOG_KINDS =
    setOf(
        EntryKind.ALBUM,
        EntryKind.ARTIST,
        EntryKind.MOVIE,
        EntryKind.TV,
        EntryKind.BOOK,
        EntryKind.GAME,
        EntryKind.SONG,
    )

internal fun matchesSearchQuery(entry: InboxEntry, normalizedQuery: String): Boolean {
    if (normalizedQuery.isBlank()) return true
    val cleanQuery = normalizedQuery.trimStart('#')
    if (cleanQuery.isBlank()) return true
    return entry.title.contains(cleanQuery, ignoreCase = true) ||
        entry.feedTitle.contains(cleanQuery, ignoreCase = true) ||
        entry.author?.contains(cleanQuery, ignoreCase = true) == true ||
        entry.url.contains(cleanQuery, ignoreCase = true) ||
        entry.summary?.contains(cleanQuery, ignoreCase = true) == true ||
        entry.contentText?.contains(cleanQuery, ignoreCase = true) == true ||
        entry.contentHtml?.contains(cleanQuery, ignoreCase = true) == true ||
        entry.tags.any { it.contains(cleanQuery, ignoreCase = true) }
}

internal fun extractSearchMatchSnippet(entry: InboxEntry, rawQuery: String): String? {
    val cleanQuery = rawQuery.trim().trimStart('#').trim()
    if (cleanQuery.length < MIN_SNIPPET_QUERY_LEN) return null
    val fallbackTokens =
        cleanQuery
            .split(WHITESPACE_REGEX)
            .filter { it.length >= MIN_SNIPPET_QUERY_LEN }
            .sortedByDescending { it.length }
    val candidates =
        listOfNotNull(
            entry.contentText?.takeIf { it.isNotBlank() },
            entry.summary?.let { stripBasicHtmlTags(it) }?.takeIf { it.isNotBlank() },
            entry.contentHtml?.let { stripBasicHtmlTags(it) }?.takeIf { it.isNotBlank() },
        )
    for (source in candidates) {
        val normalizedSource = source.replace(WHITESPACE_REGEX, " ").trim()
        findSnippetNeedle(normalizedSource, cleanQuery, fallbackTokens)?.let { (matchIndex, len) ->
            val start = (matchIndex - SNIPPET_CONTEXT_RADIUS).coerceAtLeast(0)
            val end =
                (matchIndex + len + SNIPPET_CONTEXT_RADIUS).coerceAtMost(normalizedSource.length)
            val prefix = if (start > 0) "…" else ""
            val suffix = if (end < normalizedSource.length) "…" else ""
            return "$prefix${normalizedSource.substring(start, end).trim()}$suffix"
        }
    }
    return null
}

private fun findSnippetNeedle(
    normalizedSource: String,
    cleanQuery: String,
    fallbackTokens: List<String>,
): Pair<Int, Int>? {
    val direct = normalizedSource.indexOf(cleanQuery, ignoreCase = true)
    if (direct != -1) {
        return direct to cleanQuery.length
    }
    for (token in fallbackTokens) {
        val tokenIdx = normalizedSource.indexOf(token, ignoreCase = true)
        if (tokenIdx != -1) {
            return tokenIdx to token.length
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

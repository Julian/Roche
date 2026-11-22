@file:Suppress("TooManyFunctions", "MagicNumber")

package com.grayvines.roche.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.CatalogSearchMatch
import com.grayvines.roche.data.CatalogSearchResult
import com.grayvines.roche.data.SearchScopeFilter
import com.grayvines.roche.data.catalog.CatalogListSummary
import com.grayvines.roche.data.catalog.CollectionRole
import com.grayvines.roche.data.catalog.DiaryEntryItem
import com.grayvines.roche.data.extractSearchMatchSnippet
import com.grayvines.roche.data.matchesSearchScopeFilter
import com.grayvines.roche.data.searchCatalogEntriesWithTotal
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.ui.components.CatalogCoverThumbnail
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheStripTab
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.SearchIcon
import com.grayvines.roche.ui.components.TierBadgePill
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily

private data class SearchOverlayComputed(
    val visibleResults: List<InboxEntry>,
    val shownCatalogMatches: List<CatalogSearchMatch>,
    val catalogTotalCount: Int,
    val shownListMatches: List<CatalogListSummary>,
    val listTotalCount: Int,
    val shownDiaryMatches: List<DiaryEntryItem>,
    val diaryTotalCount: Int,
    val scopeCounts: Map<SearchScopeFilter, Int>,
)

@Composable
private fun rememberSearchOverlayComputed(
    displayState: MainUiState,
    scopeFilter: SearchScopeFilter,
    catalogLimit: Int,
): SearchOverlayComputed =
    remember(
        displayState.searchResults,
        displayState.workspaceSearch,
        displayState.catalog,
        displayState.searchQuery,
        scopeFilter,
        catalogLimit,
    ) {
        val visibleResults =
            displayState.searchResults.filter { matchesSearchScopeFilter(it, scopeFilter) }
        val catalogResult = resolveSearchCatalogResult(displayState, scopeFilter, catalogLimit)
        val allListMatches =
            resolveSearchListMatches(
                state = displayState,
                includeWhenBlank = scopeFilter == SearchScopeFilter.LISTS,
            )
        val allDiaryMatches =
            resolveSearchDiaryMatches(
                state = displayState,
                includeWhenBlank = scopeFilter == SearchScopeFilter.DIARY,
            )
        val shownCatalogMatches =
            if (scopeFilter == SearchScopeFilter.ALL || scopeFilter == SearchScopeFilter.CATALOG) {
                catalogResult.matches
            } else {
                emptyList()
            }
        val shownListMatches =
            if (scopeFilter == SearchScopeFilter.ALL || scopeFilter == SearchScopeFilter.LISTS) {
                allListMatches
            } else {
                emptyList()
            }
        val shownDiaryMatches =
            if (scopeFilter == SearchScopeFilter.ALL || scopeFilter == SearchScopeFilter.DIARY) {
                allDiaryMatches
            } else {
                emptyList()
            }
        val scopeCounts =
            computeSearchScopeCounts(
                searchResults = displayState.searchResults,
                catalogTotalCount = catalogResult.totalCount,
                listTotalCount = allListMatches.size,
                diaryTotalCount = allDiaryMatches.size,
                hasQuery = displayState.searchQuery.isNotBlank(),
            )
        SearchOverlayComputed(
            visibleResults = visibleResults,
            shownCatalogMatches = shownCatalogMatches,
            catalogTotalCount = catalogResult.totalCount,
            shownListMatches = shownListMatches,
            listTotalCount = allListMatches.size,
            shownDiaryMatches = shownDiaryMatches,
            diaryTotalCount = allDiaryMatches.size,
            scopeCounts = scopeCounts,
        )
    }

private fun resolveSearchListMatches(
    state: MainUiState,
    includeWhenBlank: Boolean,
): List<CatalogListSummary> {
    val cleanQuery = state.searchQuery.trim().trimStart('#').trim()
    val allCollections = state.catalog.lists + state.catalog.wantQueues
    val seen = HashSet<String>()
    val deduped = allCollections.filter { seen.add(it.collectionId) }
    if (cleanQuery.isEmpty()) {
        return if (includeWhenBlank) deduped else emptyList()
    }
    val ftsIds = state.workspaceSearch.matchedCollectionIds.toHashSet()
    return deduped.filter { col ->
        col.collectionId in ftsIds ||
            col.title.contains(cleanQuery, ignoreCase = true) ||
            col.slug.contains(cleanQuery, ignoreCase = true)
    }
}

private fun resolveSearchDiaryMatches(
    state: MainUiState,
    includeWhenBlank: Boolean,
): List<DiaryEntryItem> {
    val cleanQuery = state.searchQuery.trim().trimStart('#').trim()
    if (cleanQuery.isEmpty()) {
        return if (includeWhenBlank) state.catalog.diaryEntries else emptyList()
    }
    val ftsIds = state.workspaceSearch.matchedDiaryLogs.mapTo(HashSet()) { it.id }
    return state.catalog.diaryEntries.filter { diary ->
        diary.id in ftsIds ||
            diary.title.contains(cleanQuery, ignoreCase = true) ||
            diary.notes?.contains(cleanQuery, ignoreCase = true) == true ||
            diary.creators.any { it.contains(cleanQuery, ignoreCase = true) }
    }
}

@Composable
fun SearchOverlay(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    isSearchActive: Boolean = state.isSearchOpen,
    activeAudioEntryId: String? = null,
) {
    val colors = LocalRocheColors.current
    val focusRequester = remember { FocusRequester() }
    var scopeFilter by rememberSaveable { mutableStateOf(SearchScopeFilter.ALL) }
    var catalogLimit by
        rememberSaveable(state.searchQuery) {
            mutableIntStateOf(DEFAULT_CATALOG_SEARCH_LIMIT)
        }
    val computed = rememberSearchOverlayComputed(state, scopeFilter, catalogLimit)

    LaunchedEffect(isSearchActive) {
        if (isSearchActive && state.searchQuery.isBlank()) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Column(
        modifier =
            Modifier.fillMaxSize()
                .background(colors.background)
                .statusBarsPadding()
                .testTag("search_overlay")
    ) {
        SearchIntegratedTopBar(
            query = state.searchQuery,
            totalEntryCount = state.allEntries.size,
            canEditQuery = isSearchActive,
            focusRequester = focusRequester,
            onAction = onAction,
        )
        SearchScopeFilterStrip(
            selectedScope = scopeFilter,
            catalogMatchCount = computed.catalogTotalCount,
            scopeCounts = computed.scopeCounts,
            onSelectScope = { scopeFilter = it },
        )
        HorizontalDivider(color = colors.border, thickness = 1.dp)
        SearchResultsBody(
            results = computed.visibleResults,
            catalogMatches = computed.shownCatalogMatches,
            catalogTotalCount = computed.catalogTotalCount,
            listMatches = computed.shownListMatches,
            listTotalCount = computed.listTotalCount,
            diaryMatches = computed.shownDiaryMatches,
            diaryTotalCount = computed.diaryTotalCount,
            onShowMoreCatalog = { catalogLimit += DEFAULT_CATALOG_SEARCH_LIMIT },
            onSelectAllScope = { scopeFilter = SearchScopeFilter.ALL },
            state = state,
            selectedScope = scopeFilter,
            playingEntryId = activeAudioEntryId,
            nextQueuedAudioEntryId = state.resolveNextQueuedAudioEntry(activeAudioEntryId)?.id,
            onAction = onAction,
            listState = listState,
        )
    }
}

private fun resolveSearchCatalogResult(
    state: MainUiState,
    scopeFilter: SearchScopeFilter,
    limit: Int,
): CatalogSearchResult =
    searchCatalogEntriesWithTotal(
        catalog = state.catalog,
        rawQuery = state.searchQuery,
        includeWhenBlank = scopeFilter == SearchScopeFilter.CATALOG,
        limit = limit,
    )

private fun computeSearchScopeCounts(
    searchResults: List<InboxEntry>,
    catalogTotalCount: Int,
    listTotalCount: Int,
    diaryTotalCount: Int,
    hasQuery: Boolean,
): Map<SearchScopeFilter, Int> {
    if (!hasQuery) {
        return buildMap {
            if (catalogTotalCount > 0) put(SearchScopeFilter.CATALOG, catalogTotalCount)
            if (listTotalCount > 0) put(SearchScopeFilter.LISTS, listTotalCount)
            if (diaryTotalCount > 0) put(SearchScopeFilter.DIARY, diaryTotalCount)
        }
    }
    return SearchScopeFilter.entries
        .associateWith { scope ->
            countForSearchScope(
                scope = scope,
                searchResults = searchResults,
                catalogTotalCount = catalogTotalCount,
                listTotalCount = listTotalCount,
                diaryTotalCount = diaryTotalCount,
            )
        }
        .filterValues { it > 0 }
}

private fun countForSearchScope(
    scope: SearchScopeFilter,
    searchResults: List<InboxEntry>,
    catalogTotalCount: Int,
    listTotalCount: Int,
    diaryTotalCount: Int,
): Int =
    when (scope) {
        SearchScopeFilter.CATALOG -> catalogTotalCount
        SearchScopeFilter.LISTS -> listTotalCount
        SearchScopeFilter.DIARY -> diaryTotalCount
        SearchScopeFilter.ALL ->
            searchResults.size + catalogTotalCount + listTotalCount + diaryTotalCount
        else -> searchResults.count { matchesSearchScopeFilter(it, scope) }
    }

@Composable
private fun SearchIntegratedTopBar(
    query: String,
    totalEntryCount: Int,
    canEditQuery: Boolean,
    focusRequester: FocusRequester,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val pillShape = RoundedCornerShape(11.dp)
    Row(
        modifier =
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RocheIconActionButton(
            contentDescription = "Close search",
            outlined = false,
            onClick = { onAction(MainAction.CloseSearch) },
            modifier = Modifier.testTag("close_search_btn"),
        ) {
            Text(text = "←", color = colors.textSecondary, fontSize = 18.sp)
        }
        Row(
            modifier =
                Modifier.weight(1f)
                    .heightIn(min = 42.dp)
                    .clip(pillShape)
                    .background(colors.surface)
                    .border(1.dp, colors.border, pillShape)
                    .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchIcon(tint = colors.textMuted, size = 16.dp)
            SearchPillTextField(
                query = query,
                placeholder = "Search $totalEntryCount entries, albums, #tags…",
                canEditQuery = canEditQuery,
                focusRequester = focusRequester,
                onQueryChange = { onAction(MainAction.UpdateSearchQuery(it)) },
                modifier = Modifier.weight(1f),
            )
            if (query.isNotEmpty()) {
                RocheIconActionButton(
                    contentDescription = "Clear search",
                    outlined = false,
                    onClick = { onAction(MainAction.UpdateSearchQuery("")) },
                    modifier = Modifier.testTag("clear_search_input_btn"),
                ) {
                    Text(
                        text = "✕",
                        color = colors.textMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchPillTextField(
    query: String,
    placeholder: String,
    canEditQuery: Boolean,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val keyboardController = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = query,
        onValueChange = { next ->
            if (canEditQuery) {
                onQueryChange(next)
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
        textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
        cursorBrush = SolidColor(colors.accentBlue),
        modifier = modifier.focusRequester(focusRequester).testTag("search_input"),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = colors.textMuted,
                        fontSize = 13.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
internal fun SearchScopeFilterStrip(
    selectedScope: SearchScopeFilter,
    onSelectScope: (SearchScopeFilter) -> Unit,
    catalogMatchCount: Int = 0,
    scopeCounts: Map<SearchScopeFilter, Int> = emptyMap(),
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchScopeFilter.entries.forEach { scope ->
            val count =
                scopeCounts[scope]
                    ?: if (scope == SearchScopeFilter.CATALOG) catalogMatchCount else 0
            val label = if (count > 0) "${scope.label} ($count)" else scope.label
            RocheStripTab(
                label = label,
                selected = selectedScope == scope,
                onClick = { onSelectScope(scope) },
                modifier = Modifier.testTag("search_scope_${scope.name.lowercase()}"),
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
internal fun SearchResultsBody(
    results: List<InboxEntry>,
    catalogMatches: List<CatalogSearchMatch>,
    state: MainUiState,
    selectedScope: SearchScopeFilter = SearchScopeFilter.ALL,
    catalogTotalCount: Int = catalogMatches.size,
    listMatches: List<CatalogListSummary> = emptyList(),
    listTotalCount: Int = listMatches.size,
    diaryMatches: List<DiaryEntryItem> = emptyList(),
    diaryTotalCount: Int = diaryMatches.size,
    onShowMoreCatalog: (() -> Unit)? = null,
    onSelectAllScope: (() -> Unit)? = null,
    playingEntryId: String? = null,
    nextQueuedAudioEntryId: String? = null,
    onAction: (MainAction) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    val hasAnyResults =
        results.isNotEmpty() ||
            catalogMatches.isNotEmpty() ||
            listMatches.isNotEmpty() ||
            diaryMatches.isNotEmpty()
    if (!hasAnyResults) {
        val totalElsewhere =
            state.searchResults.size + catalogTotalCount + listTotalCount + diaryTotalCount
        val hasOtherScopeMatches = selectedScope != SearchScopeFilter.ALL && totalElsewhere > 0
        SearchEmptyState(
            searchQuery = state.searchQuery,
            selectedScope = selectedScope,
            hasOtherScopeMatches = hasOtherScopeMatches,
            onSelectAllScope = onSelectAllScope,
            state = state,
            onAction = onAction,
        )
        return
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompactWidth = maxWidth < COMPACT_ROW_BREAKPOINT
        SearchResultsList(
            results = results,
            catalogMatches = catalogMatches,
            listMatches = listMatches,
            diaryMatches = diaryMatches,
            state = state,
            catalogTotalCount = catalogTotalCount,
            onShowMoreCatalog = onShowMoreCatalog,
            isCompactWidth = isCompactWidth,
            playingEntryId = playingEntryId,
            nextQueuedAudioEntryId = nextQueuedAudioEntryId,
            onAction = onAction,
            listState = listState,
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun SearchResultsList(
    results: List<InboxEntry>,
    catalogMatches: List<CatalogSearchMatch>,
    listMatches: List<CatalogListSummary>,
    diaryMatches: List<DiaryEntryItem>,
    state: MainUiState,
    catalogTotalCount: Int,
    onShowMoreCatalog: (() -> Unit)?,
    isCompactWidth: Boolean,
    playingEntryId: String?,
    nextQueuedAudioEntryId: String?,
    onAction: (MainAction) -> Unit,
    listState: LazyListState,
) {
    val colors = LocalRocheColors.current
    val hasUpperSections =
        catalogMatches.isNotEmpty() || listMatches.isNotEmpty() || diaryMatches.isNotEmpty()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("search_results_list"),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        if (state.tags.isNotEmpty()) {
            item(key = "search_tags_strip") {
                SearchQuickTagStrip(state = state, onAction = onAction)
            }
        }
        if (listMatches.isNotEmpty()) {
            item(key = "search_lists_section") {
                SearchListMatchesSection(lists = listMatches, onAction = onAction)
            }
        }
        if (catalogMatches.isNotEmpty()) {
            item(key = "search_catalog_section") {
                SearchCatalogMatchesSection(
                    matches = catalogMatches,
                    totalCount = catalogTotalCount,
                    onShowMore = onShowMoreCatalog,
                    onOpenEntry = { onAction(MainAction.OpenCatalogDetailSheet(it)) },
                )
            }
        }
        if (diaryMatches.isNotEmpty()) {
            item(key = "search_diary_section") {
                SearchDiaryMatchesSection(
                    diaryMatches = diaryMatches,
                    state = state,
                    onAction = onAction,
                )
            }
        }
        if (results.isNotEmpty()) {
            if (hasUpperSections) {
                item(key = "search_inbox_section_head") {
                    SearchSectionLabelRow(title = "Inbox & Archive", count = results.size)
                }
            }
            items(items = results, key = { it.id }) { entry ->
                SearchInboxMatchRow(
                    entry = entry,
                    state = state,
                    isCompactWidth = isCompactWidth,
                    playingEntryId = playingEntryId,
                    nextQueuedAudioEntryId = nextQueuedAudioEntryId,
                    onAction = onAction,
                )
                HorizontalDivider(color = colors.border, thickness = 1.dp)
            }
        }
    }
}

@Composable
private fun SearchListMatchesSection(
    lists: List<CatalogListSummary>,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        SearchSectionLabelRow(title = "Lists", count = lists.size, horizontalPadding = 2.dp)
        RocheSurfaceCard {
            lists.forEachIndexed { idx, list ->
                if (idx > 0) HorizontalDivider(color = colors.border, thickness = 1.dp)
                SearchListMatchRow(
                    list = list,
                    onClick = {
                        val subTab =
                            if (list.role == CollectionRole.WANT) {
                                CompanionSubTab.WANT
                            } else {
                                CompanionSubTab.LISTS
                            }
                        onAction(MainAction.OpenCompanionSection(subTab, list.collectionId))
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchListMatchRow(
    list: CatalogListSummary,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onClick)
                .semantics { role = Role.Button }
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .testTag("search_list_match_${list.collectionId}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = list.title,
            color = colors.textPrimary,
            fontFamily = currentHeadlineFontFamily(),
            fontSize = 14.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        Text(
            text = "${list.entryCount} items",
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun SearchDiaryMatchesSection(
    diaryMatches: List<DiaryEntryItem>,
    state: MainUiState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val shown = remember(diaryMatches) { diaryMatches.take(MAX_DIARY_SEARCH_RESULTS) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        SearchSectionLabelRow(
            title = "Diary",
            countLabel =
                if (diaryMatches.size > shown.size) {
                    "Showing ${shown.size} of ${diaryMatches.size}"
                } else {
                    diaryMatches.size.toString()
                },
            horizontalPadding = 2.dp,
        )
        RocheSurfaceCard {
            shown.forEachIndexed { idx, log ->
                if (idx > 0) HorizontalDivider(color = colors.border, thickness = 1.dp)
                SearchDiaryMatchRow(
                    log = log,
                    onClick = {
                        if (log.entryId in state.readableEntryIds) {
                            onAction(MainAction.OpenReader(log.entryId))
                        } else {
                            onAction(MainAction.OpenCatalogDetailSheet(log.entryId))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchDiaryMatchRow(
    log: DiaryEntryItem,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 50.dp)
                .clickable(onClick = onClick)
                .semantics { role = Role.Button }
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .testTag("search_diary_match_${log.id}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = log.dateLabel,
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = log.title,
                color = colors.textPrimary,
                fontFamily = currentHeadlineFontFamily(),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!log.notes.isNullOrBlank()) {
                Text(
                    text = log.notes,
                    color = colors.accentEcru,
                    fontSize = 11.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = log.status.displayLabel,
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun SearchEmptyState(
    searchQuery: String,
    selectedScope: SearchScopeFilter,
    hasOtherScopeMatches: Boolean,
    onSelectAllScope: (() -> Unit)?,
    state: MainUiState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val queryLabel = searchQuery.ifBlank { "current filter" }
    val emptyMessage =
        if (selectedScope == SearchScopeFilter.ALL) {
            "No entries match \"$queryLabel\"."
        } else {
            "No results in ${selectedScope.label} for \"$queryLabel\"."
        }
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.tags.isNotEmpty()) {
            SearchQuickTagStrip(state = state, onAction = onAction)
        }
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = emptyMessage, color = colors.textMuted, fontSize = 13.sp)
                if (hasOtherScopeMatches && onSelectAllScope != null) {
                    HeaderActionButton(
                        label = "Show All Results",
                        onClick = onSelectAllScope,
                        modifier = Modifier.testTag("search_show_all_btn"),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchCatalogMatchesSection(
    matches: List<CatalogSearchMatch>,
    totalCount: Int,
    onShowMore: (() -> Unit)?,
    onOpenEntry: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val isTruncated = totalCount > matches.size
    val countLabel =
        if (isTruncated) {
            "Showing ${matches.size} of $totalCount"
        } else {
            totalCount.toString()
        }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        SearchSectionLabelRow(
            title = "Catalog",
            countLabel = countLabel,
            horizontalPadding = 2.dp,
        )
        RocheSurfaceCard {
            matches.forEachIndexed { idx, match ->
                if (idx > 0) {
                    HorizontalDivider(color = colors.border, thickness = 1.dp)
                }
                SearchCatalogMatchRow(match = match, onClick = { onOpenEntry(match.entry.id) })
            }
            if (isTruncated && onShowMore != null) {
                HorizontalDivider(color = colors.border, thickness = 1.dp)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(onClick = onShowMore)
                            .semantics { role = Role.Button }
                            .testTag("search_catalog_show_more_btn"),
                ) {
                    Text(
                        text = "Show more (${totalCount - matches.size} remaining)",
                        color = colors.accentBlue,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchCatalogMatchRow(
    match: CatalogSearchMatch,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val entry = match.entry
    val sub =
        remember(entry) {
            listOf(entry.kind.displayLabel, entry.subtitleLine)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(onClick = onClick)
                .semantics { role = Role.Button }
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .testTag("search_catalog_match_${entry.id}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CatalogCoverThumbnail(thumbnailUrl = entry.thumbnailUrl, kind = entry.kind, size = 38.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = entry.title,
                color = colors.textPrimary,
                fontFamily = currentHeadlineFontFamily(),
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sub,
                color = colors.textSecondary,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (match.tier != null) {
            TierBadgePill(tier = match.tier)
        }
    }
}

@Composable
private fun SearchSectionLabelRow(
    title: String,
    count: Int = 0,
    countLabel: String = count.toString(),
    horizontalPadding: androidx.compose.ui.unit.Dp = 16.dp,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            color = colors.textMuted,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = countLabel,
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun SearchInboxMatchRow(
    entry: InboxEntry,
    state: MainUiState,
    isCompactWidth: Boolean,
    playingEntryId: String?,
    nextQueuedAudioEntryId: String?,
    onAction: (MainAction) -> Unit,
) {
    InboxEntryRowItem(
        entry = entry,
        ultraDense = state.settings.ultraDenseRows,
        swipeToArchiveEnabled = state.settings.swipeToArchive,
        isCompactWidth = isCompactWidth,
        isDownloadingOffline = entry.id in state.inFlightOfflineEntryIds,
        isPlayingAudio = playingEntryId == entry.id,
        isQueuedNextAudio = nextQueuedAudioEntryId == entry.id && playingEntryId != entry.id,
        onOpen = { onAction(MainAction.OpenReader(entry.id)) },
        onLongPress = { onAction(MainAction.OpenContextSheet(entry.id)) },
        onToggleStar = { onAction(MainAction.ToggleStar(entry.id)) },
        onToggleArchive = { onAction(MainAction.ToggleArchive(entry)) },
        onDownloadOffline = { onAction(MainAction.ToggleOfflineCache(entry.id)) },
    )
    val ftsSnippet = state.workspaceSearch.snippetsByEntryId[entry.id]
    val matchSnippet =
        remember(entry.id, entry.contentText, state.searchQuery, ftsSnippet) {
            ftsSnippet?.takeIf { it.isNotBlank() }
                ?: extractSearchMatchSnippet(entry = entry, rawQuery = state.searchQuery)
        }
    if (matchSnippet != null) {
        SearchMatchContextBanner(
            entryId = entry.id,
            snippet = matchSnippet,
            query = state.searchQuery,
            onClick = { onAction(MainAction.OpenReader(entry.id)) },
        )
    }
}

@Composable
internal fun SearchMatchContextBanner(
    entryId: String,
    snippet: String,
    query: String,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val cleanQuery = query.trim().trimStart('#')
    val highlighted =
        remember(snippet, cleanQuery, colors.accentEcru) {
            buildAnnotatedString {
                val idx =
                    if (cleanQuery.isBlank()) -1 else snippet.indexOf(cleanQuery, ignoreCase = true)
                if (idx == -1) {
                    append(snippet)
                } else {
                    append(snippet.substring(0, idx))
                    withStyle(
                        SpanStyle(color = colors.accentEcru, fontWeight = FontWeight.Medium)
                    ) {
                        append(snippet.substring(idx, idx + cleanQuery.length))
                    }
                    append(snippet.substring(idx + cleanQuery.length))
                }
            }
        }

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onClick)
                .semantics { role = Role.Button }
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(colors.surface)
                .padding(horizontal = 10.dp, vertical = 7.dp)
                .testTag("search_match_snippet_$entryId"),
    ) {
        Text(
            text = highlighted,
            color = colors.textSecondary,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SearchQuickTagStrip(state: MainUiState, onAction: (MainAction) -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.tags.forEach { tag ->
            val tagToken = "#${tag.name}"
            val isSelected = state.searchQuery.trim().equals(tagToken, ignoreCase = true)
            SearchQuickTagChip(
                tagToken = tagToken,
                isSelected = isSelected,
                onClick = {
                    onAction(MainAction.UpdateSearchQuery(if (isSelected) "" else tagToken))
                },
            )
        }
    }
}

@Composable
private fun SearchQuickTagChip(
    tagToken: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(8.dp)
    val bg = if (isSelected) colors.accentBlue.copy(alpha = 0.16f) else colors.surface
    val borderColor = if (isSelected) colors.accentBlue else colors.border
    val textColor = if (isSelected) colors.accentBlue else colors.textSecondary
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.minimumInteractiveComponentSize()
                .clip(shape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Checkbox
                    selected = isSelected
                },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.heightIn(min = 30.dp)
                    .clip(shape)
                    .background(bg)
                    .border(1.dp, borderColor, shape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = tagToken,
                color = textColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}

private const val DEFAULT_CATALOG_SEARCH_LIMIT = 24
private const val MAX_DIARY_SEARCH_RESULTS = 20

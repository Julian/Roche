@file:Suppress("TooManyFunctions", "MagicNumber")

package com.grayvines.roche.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.CatalogSearchMatch
import com.grayvines.roche.data.SearchScopeFilter
import com.grayvines.roche.data.extractSearchMatchSnippet
import com.grayvines.roche.data.matchesSearchScopeFilter
import com.grayvines.roche.data.searchCatalogEntries
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.SearchIcon
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily

internal data class RetainedSearchDisplay(
    val state: MainUiState,
    val canEditQuery: Boolean,
)

internal class SearchDisplayRetentionState(
    var sawActiveSearch: Boolean,
    var retainedState: MainUiState,
)

internal fun computeRetainedSearchDisplay(
    holder: SearchDisplayRetentionState,
    state: MainUiState,
    isSearchActive: Boolean,
): RetainedSearchDisplay {
    val nextSawActive = holder.sawActiveSearch || isSearchActive
    val effectiveState =
        if (nextSawActive && !isSearchActive) {
            holder.retainedState
        } else {
            state
        }
    return RetainedSearchDisplay(
        state = effectiveState,
        canEditQuery = !nextSawActive || isSearchActive,
    )
}

internal fun commitRetainedSearchDisplay(
    holder: SearchDisplayRetentionState,
    state: MainUiState,
    isSearchActive: Boolean,
) {
    if (isSearchActive) {
        holder.sawActiveSearch = true
        holder.retainedState = state
    } else if (!holder.sawActiveSearch) {
        holder.retainedState = state
    }
}

@Composable
private fun rememberSearchDisplayState(
    state: MainUiState,
    isSearchActive: Boolean,
): RetainedSearchDisplay {
    val holder = remember { SearchDisplayRetentionState(isSearchActive, state) }
    val display = computeRetainedSearchDisplay(holder, state, isSearchActive)
    SideEffect { commitRetainedSearchDisplay(holder, state, isSearchActive) }
    return display
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
    val (displayState, canEditQuery) = rememberSearchDisplayState(state, isSearchActive)
    var scopeFilter by rememberSaveable { mutableStateOf(SearchScopeFilter.ALL) }
    val visibleResults =
        remember(displayState.searchResults, scopeFilter) {
            displayState.searchResults.filter { matchesSearchScopeFilter(it, scopeFilter) }
        }
    val catalogMatches =
        remember(displayState.catalog, displayState.searchQuery, scopeFilter) {
            resolveSearchCatalogMatches(displayState, scopeFilter)
        }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive && displayState.searchQuery.isBlank()) {
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
            query = displayState.searchQuery,
            totalEntryCount = displayState.allEntries.size,
            canEditQuery = canEditQuery,
            focusRequester = focusRequester,
            onAction = onAction,
        )
        SearchScopeFilterStrip(
            selectedScope = scopeFilter,
            catalogMatchCount = catalogMatches.size,
            onSelectScope = { scopeFilter = it },
        )
        HorizontalDivider(color = colors.border, thickness = 1.dp)
        SearchResultsBody(
            results = visibleResults,
            catalogMatches = catalogMatches,
            state = displayState,
            selectedScope = scopeFilter,
            playingEntryId = activeAudioEntryId,
            nextQueuedAudioEntryId =
                displayState.resolveNextQueuedAudioEntry(activeAudioEntryId)?.id,
            onAction = onAction,
            listState = listState,
        )
    }
}

private fun resolveSearchCatalogMatches(
    state: MainUiState,
    scopeFilter: SearchScopeFilter,
): List<CatalogSearchMatch> =
    if (scopeFilter == SearchScopeFilter.ALL || scopeFilter == SearchScopeFilter.CATALOG) {
        searchCatalogEntries(
            catalog = state.catalog,
            rawQuery = state.searchQuery,
            includeWhenBlank = scopeFilter == SearchScopeFilter.CATALOG,
        )
    } else {
        emptyList()
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
    BasicTextField(
        value = query,
        onValueChange = { next ->
            if (canEditQuery) {
                onQueryChange(next)
            }
        },
        singleLine = true,
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
            val label =
                if (scope == SearchScopeFilter.CATALOG && catalogMatchCount > 0) {
                    "${scope.label} ($catalogMatchCount)"
                } else {
                    scope.label
                }
            SoftStripTab(
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
    playingEntryId: String? = null,
    nextQueuedAudioEntryId: String? = null,
    onAction: (MainAction) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = LocalRocheColors.current
    val hasAnyResults = results.isNotEmpty() || catalogMatches.isNotEmpty()
    if (!hasAnyResults) {
        SearchEmptyState(
            searchQuery = state.searchQuery,
            selectedScope = selectedScope,
            state = state,
            onAction = onAction,
        )
        return
    }
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
        if (catalogMatches.isNotEmpty()) {
            item(key = "search_catalog_section") {
                SearchCatalogMatchesSection(
                    matches = catalogMatches,
                    onOpenEntry = { onAction(MainAction.OpenCatalogDetailSheet(it)) },
                )
            }
        }
        if (results.isNotEmpty()) {
            if (catalogMatches.isNotEmpty()) {
                item(key = "search_inbox_section_head") {
                    SearchSectionLabelRow(title = "Inbox & Archive", count = results.size)
                }
            }
            items(items = results, key = { it.id }) { entry ->
                SearchInboxMatchRow(
                    entry = entry,
                    state = state,
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
private fun SearchEmptyState(
    searchQuery: String,
    selectedScope: SearchScopeFilter,
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
            Text(text = emptyMessage, color = colors.textMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SearchCatalogMatchesSection(
    matches: List<CatalogSearchMatch>,
    onOpenEntry: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        SearchSectionLabelRow(title = "Catalog", count = matches.size, horizontalPadding = 2.dp)
        RocheSurfaceCard {
            matches.forEachIndexed { idx, match ->
                if (idx > 0) {
                    HorizontalDivider(color = colors.border, thickness = 1.dp)
                }
                SearchCatalogMatchRow(match = match, onClick = { onOpenEntry(match.entry.id) })
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
    count: Int,
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
            text = count.toString(),
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun SearchInboxMatchRow(
    entry: InboxEntry,
    state: MainUiState,
    playingEntryId: String?,
    nextQueuedAudioEntryId: String?,
    onAction: (MainAction) -> Unit,
) {
    InboxEntryRowItem(
        entry = entry,
        ultraDense = state.settings.ultraDenseRows,
        swipeToArchiveEnabled = state.settings.swipeToArchive,
        isPlayingAudio = playingEntryId == entry.id,
        isQueuedNextAudio = nextQueuedAudioEntryId == entry.id && playingEntryId != entry.id,
        onOpen = { onAction(MainAction.OpenReader(entry.id)) },
        onLongPress = { onAction(MainAction.OpenContextSheet(entry.id)) },
        onToggleStar = { onAction(MainAction.ToggleStar(entry.id)) },
        onToggleArchive = { onAction(MainAction.ToggleArchive(entry)) },
        onDownloadOffline = { onAction(MainAction.ToggleOfflineCache(entry.id)) },
    )
    val matchSnippet =
        remember(entry.id, entry.contentText, state.searchQuery) {
            extractSearchMatchSnippet(entry = entry, rawQuery = state.searchQuery)
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
        modifier =
            Modifier.clip(shape)
                .background(bg)
                .border(1.dp, borderColor, shape)
                .clickable(onClick = onClick)
                .semantics { role = Role.Button }
                .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = tagToken,
            color = textColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

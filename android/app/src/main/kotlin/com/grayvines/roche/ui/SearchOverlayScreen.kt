package com.grayvines.roche.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.SearchScopeFilter
import com.grayvines.roche.data.extractSearchMatchSnippet
import com.grayvines.roche.data.matchesSearchScopeFilter
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.RocheTopHeader
import com.grayvines.roche.ui.components.SearchIcon
import com.grayvines.roche.ui.theme.LocalRocheColors

@Composable
fun SearchOverlay(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    BackHandler { onAction(MainAction.CloseSearch) }
    val colors = LocalRocheColors.current
    val focusRequester = remember { FocusRequester() }
    var scopeFilter by rememberSaveable { mutableStateOf(SearchScopeFilter.ALL) }
    val visibleResults =
        remember(state.searchResults, scopeFilter) {
            state.searchResults.filter { matchesSearchScopeFilter(it, scopeFilter) }
        }
    val subtitleText =
        if (state.searchQuery.isBlank() && scopeFilter == SearchScopeFilter.ALL) {
            "Type to search ${state.allEntries.size} entries"
        } else {
            "${visibleResults.size} matches"
        }

    LaunchedEffect(Unit) {
        if (state.searchQuery.isBlank()) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(colors.background).testTag("search_overlay")
    ) {
        RocheTopHeader(
            title = "Search",
            subtitle = subtitleText,
            navigationAction = {
                HeaderActionButton(
                    label = "←",
                    onClick = { onAction(MainAction.CloseSearch) },
                    modifier = Modifier.testTag("close_search_btn"),
                )
            },
        )
        SearchInputHeaderControls(
            state = state,
            scopeFilter = scopeFilter,
            focusRequester = focusRequester,
            onSelectScope = { scopeFilter = it },
            onAction = onAction,
        )
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        SearchResultsBody(
            results = visibleResults,
            searchQuery = state.searchQuery,
            selectedScope = scopeFilter,
            ultraDense = state.settings.ultraDenseRows,
            swipeToArchive = state.settings.swipeToArchive,
            playingEntryId = state.playerState.currentEntryId,
            nextQueuedAudioEntryId = state.nextQueuedAudioEntryId,
            onAction = onAction,
            listState = listState,
        )
    }
}

@Composable
private fun SearchInputHeaderControls(
    state: MainUiState,
    scopeFilter: SearchScopeFilter,
    focusRequester: FocusRequester,
    onSelectScope: (SearchScopeFilter) -> Unit,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { onAction(MainAction.UpdateSearchQuery(it)) },
            leadingIcon = { SearchIcon(tint = colors.textMuted, size = 18.dp) },
            trailingIcon =
                if (state.searchQuery.isNotEmpty()) {
                    {
                        RocheIconActionButton(
                            contentDescription = "Clear search",
                            onClick = { onAction(MainAction.UpdateSearchQuery("")) },
                            outlined = false,
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
                } else {
                    null
                },
            placeholder = {
                Text("Search titles, full text, authors, feeds, #tags…", fontSize = 13.sp)
            },
            singleLine = true,
            modifier =
                Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("search_input"),
        )
        Spacer(modifier = Modifier.height(8.dp))
        SearchScopeFilterStrip(
            selectedScope = scopeFilter,
            onSelectScope = onSelectScope,
        )
        if (state.tags.isNotEmpty() || state.searchQuery.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            SearchQuickTagStrip(state = state, onAction = onAction)
        }
        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
internal fun SearchScopeFilterStrip(
    selectedScope: SearchScopeFilter,
    onSelectScope: (SearchScopeFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SearchScopeFilter.entries.forEach { scope ->
            FilterChipPill(
                label = scope.label,
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
    searchQuery: String,
    selectedScope: SearchScopeFilter = SearchScopeFilter.ALL,
    ultraDense: Boolean,
    swipeToArchive: Boolean,
    playingEntryId: String? = null,
    nextQueuedAudioEntryId: String? = null,
    onAction: (MainAction) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = LocalRocheColors.current
    if (results.isEmpty()) {
        val queryLabel = searchQuery.ifBlank { "current filter" }
        val emptyMessage =
            if (selectedScope == SearchScopeFilter.ALL) {
                "No entries match \"$queryLabel\"."
            } else {
                "No results in ${selectedScope.label} for \"$queryLabel\"."
            }
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = emptyMessage,
                color = colors.textMuted,
                fontSize = 13.sp,
            )
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("search_results_list"),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            items(items = results, key = { it.id }) { entry ->
                InboxEntryRowItem(
                    entry = entry,
                    ultraDense = ultraDense,
                    swipeToArchiveEnabled = swipeToArchive,
                    isPlayingAudio = playingEntryId == entry.id,
                    isQueuedNextAudio =
                        nextQueuedAudioEntryId == entry.id && playingEntryId != entry.id,
                    onOpen = { onAction(MainAction.OpenReader(entry.id)) },
                    onLongPress = { onAction(MainAction.OpenContextSheet(entry.id)) },
                    onToggleStar = { onAction(MainAction.ToggleStar(entry.id)) },
                    onToggleArchive = { onAction(MainAction.ToggleArchive(entry)) },
                    onDownloadOffline = { onAction(MainAction.ToggleOfflineCache(entry.id)) },
                )
                val matchSnippet =
                    remember(entry.id, entry.contentText, searchQuery) {
                        extractSearchMatchSnippet(entry = entry, rawQuery = searchQuery)
                    }
                if (matchSnippet != null) {
                    SearchMatchContextBanner(
                        entryId = entry.id,
                        snippet = matchSnippet,
                        query = searchQuery,
                        onClick = { onAction(MainAction.OpenReader(entry.id)) },
                    )
                }
                HorizontalDivider(color = colors.border, thickness = 1.dp)
            }
        }
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
                if (cleanQuery.isBlank()) {
                    append(snippet)
                } else {
                    val idx = snippet.indexOf(cleanQuery, ignoreCase = true)
                    if (idx == -1) {
                        append(snippet)
                    } else {
                        append(snippet.substring(0, idx))
                        withStyle(
                            SpanStyle(
                                color = colors.accentEcru,
                                fontWeight = FontWeight.SemiBold,
                            )
                        ) {
                            append(snippet.substring(idx, idx + cleanQuery.length))
                        }
                        append(snippet.substring(idx + cleanQuery.length))
                    }
                }
            }
        }

    Box(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .semantics { role = Role.Button }
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surface)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("search_match_snippet_$entryId")
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
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.searchQuery.isNotBlank()) {
            FilterChipPill(
                label = "Clear ✕",
                selected = true,
                onClick = { onAction(MainAction.UpdateSearchQuery("")) },
            )
        }
        state.tags.forEach { tag ->
            val tagToken = "#${tag.name}"
            val isSelected = state.searchQuery.trim().equals(tagToken, ignoreCase = true)
            FilterChipPill(
                label = tagToken,
                selected = isSelected,
                onClick = {
                    val nextQuery = if (isSelected) "" else tagToken
                    onAction(MainAction.UpdateSearchQuery(nextQuery))
                },
            )
        }
    }
}

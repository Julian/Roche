package com.grayvines.roche.ui

import com.grayvines.roche.data.WorkspaceSearchResult
import com.grayvines.roche.model.AppTab

internal fun reduceUiAction(
    state: TransientUiState,
    action: MainAction.Ui,
): TransientUiState =
    when (action) {
        is MainAction.NavigationUi -> reduceNavigationUiAction(state, action)
        is MainAction.OverlayUi -> reduceOverlayUiAction(state, action)
        is MainAction.PlayerChromeUi -> reducePlayerChromeUiAction(state, action)
        is MainAction.CatalogUi -> reduceCatalogUiAction(state, action)
    }

private fun reduceNavigationUiAction(
    state: TransientUiState,
    action: MainAction.NavigationUi,
): TransientUiState =
    when (action) {
        is MainAction.SelectTab ->
            state
                .clearSearch()
                .copy(
                    activeTab = action.tab,
                    selectedCatalogCollectionId =
                        if (action.tab == AppTab.LISTS && state.activeTab == AppTab.LISTS) {
                            null
                        } else {
                            state.selectedCatalogCollectionId
                        },
                    selectedReaderEntryId = null,
                    readerContent = null,
                    catalogDetailStack = emptyList(),
                    selectedFeedId = null,
                    selectedTag = null,
                    scopeOriginTab = null,
                )
        MainAction.ResetSettingsToHub -> state
        is MainAction.SelectFilter -> state.copy(activeFilter = action.filter)
        is MainAction.FilterByTag ->
            state
                .clearSearch()
                .copy(
                    activeTab = AppTab.INBOX,
                    selectedTag = action.tagName,
                    selectedFeedId = null,
                    scopeOriginTab = resolveNextScopeOriginTab(state, action.tagName != null),
                    selectedReaderEntryId = null,
                    readerContent = null,
                    contextSheetEntryId = null,
                )
        is MainAction.FilterByFeed ->
            state
                .clearSearch()
                .copy(
                    activeTab = AppTab.INBOX,
                    selectedFeedId = action.feedId,
                    selectedTag = null,
                    scopeOriginTab = resolveNextScopeOriginTab(state, action.feedId != null),
                    selectedReaderEntryId = null,
                    readerContent = null,
                    contextSheetEntryId = null,
                    feedContextSheetId = null,
                )
        MainAction.NavigateBackFromScope ->
            state.copy(
                activeTab = state.scopeOriginTab ?: AppTab.HOME,
                selectedFeedId = null,
                selectedTag = null,
                scopeOriginTab = null,
                selectedReaderEntryId = null,
                readerContent = null,
                contextSheetEntryId = null,
                feedContextSheetId = null,
            )
    }

internal fun resolveNextScopeOriginTab(state: TransientUiState, hasNewScope: Boolean): AppTab? =
    when {
        !hasNewScope -> null
        state.activeTab != AppTab.INBOX -> state.activeTab
        else -> state.scopeOriginTab ?: AppTab.INBOX
    }

private fun reduceOverlayUiAction(
    state: TransientUiState,
    action: MainAction.OverlayUi,
): TransientUiState =
    when (action) {
        is MainAction.UpdateSearchQuery -> reduceUpdateSearchQuery(state, action.query)
        MainAction.OpenSearch ->
            state.copy(
                isSearchOpen = true,
                contextSheetEntryId = null,
                feedContextSheetId = null,
                catalogDetailStack = emptyList(),
                isAddSheetOpen = false,
            )
        MainAction.CloseSearch -> state.clearSearch()
        is MainAction.OpenReader -> reduceOpenReader(state, action)
        MainAction.CloseReader -> state.copy(selectedReaderEntryId = null, readerContent = null)
        is MainAction.OpenContextSheet -> state.copy(contextSheetEntryId = action.entryId)
        MainAction.CloseContextSheet -> state.copy(contextSheetEntryId = null)
        is MainAction.OpenFeedContextSheet -> state.copy(feedContextSheetId = action.feedId)
        MainAction.CloseFeedContextSheet -> state.copy(feedContextSheetId = null)
        is MainAction.OpenAddSheet ->
            state.copy(
                isAddSheetOpen = true,
                prefilledShareUrl = action.prefilledUrl,
                prefilledShareTitle = action.prefilledTitle,
                prefilledShareIsFeed = action.preferSubscribeFeed,
                contextSheetEntryId = null,
                feedContextSheetId = null,
            )
        MainAction.CloseAddSheet ->
            state.copy(
                isAddSheetOpen = false,
                prefilledShareUrl = "",
                prefilledShareTitle = "",
                prefilledShareIsFeed = false,
            )
        MainAction.DismissMessages -> state.copy(undoEvent = null, statusBanner = null)
    }

private fun reduceOpenReader(
    state: TransientUiState,
    action: MainAction.OpenReader,
): TransientUiState =
    state.copy(
        selectedReaderEntryId = action.entryId,
        readerContent = state.readerContent?.takeIf { it.itemId == action.entryId },
        hydratingEntryIds = state.hydratingEntryIds + action.entryId,
        contextSheetEntryId = null,
        catalogDetailStack = emptyList(),
        isPlayerFullscreenOpen =
            if (action.forceArticleView) false else state.isPlayerFullscreenOpen,
        isPlayerSpeedSheetOpen =
            if (action.forceArticleView) false else state.isPlayerSpeedSheetOpen,
        activePlayerDrawerTab = if (action.forceArticleView) null else state.activePlayerDrawerTab,
    )

private fun reducePlayerChromeUiAction(
    state: TransientUiState,
    action: MainAction.PlayerChromeUi,
): TransientUiState =
    when (action) {
        MainAction.OpenFullscreenPlayer -> state.copy(isPlayerFullscreenOpen = true)
        MainAction.CloseFullscreenPlayer ->
            state.copy(
                isPlayerFullscreenOpen = false,
                isPlayerSpeedSheetOpen = false,
                activePlayerDrawerTab = null,
            )
        is MainAction.SetPlayerSpeedSheetOpen -> state.copy(isPlayerSpeedSheetOpen = action.open)
        is MainAction.SelectPlayerDrawerTab -> state.copy(activePlayerDrawerTab = action.tab)
    }

private fun reduceCatalogUiAction(
    state: TransientUiState,
    action: MainAction.CatalogUi,
): TransientUiState =
    when (action) {
        is MainAction.OpenCompanionSection -> reduceOpenCompanionSection(state, action)
        is MainAction.SelectCompanionSubTab -> state.copy(companionSubTab = action.subTab)
        is MainAction.SelectQueueSubTab -> state.copy(queueSubTab = action.subTab)
        is MainAction.SelectCatalogCollection ->
            state.copy(selectedCatalogCollectionId = action.collectionId)
        is MainAction.SelectWantCollection ->
            state.copy(selectedWantCollectionId = action.collectionId)
        is MainAction.LaunchCollectionDraw,
        is MainAction.EnrichAndLaunchMusicDraw -> state
        else -> reduceCatalogSheetUiAction(state, action)
    }

private fun reduceCatalogSheetUiAction(
    state: TransientUiState,
    action: MainAction.CatalogUi,
): TransientUiState =
    when (action) {
        is MainAction.OpenCatalogDetailSheet ->
            state.copy(
                catalogDetailStack =
                    pushCatalogDetailEntry(state.catalogDetailStack, action.entryId)
            )
        MainAction.PopCatalogDetailSheet ->
            state.copy(catalogDetailStack = state.catalogDetailStack.dropLast(1))
        MainAction.CloseCatalogDetailSheet -> state.copy(catalogDetailStack = emptyList())
        is MainAction.OpenQuickLogSheet ->
            state.copy(
                catalogDetailStack = emptyList(),
                isQuickLogSheetOpen = true,
                quickLogDefaultWant = action.defaultToWant,
                quickLogTargetCollectionId = action.targetCollectionId,
                quickLogTargetCollectionTitle = action.targetCollectionTitle,
                quickLogPrefillKind = action.prefillKind,
                quickLogPrefillTitle = action.prefillTitle,
                quickLogPrefillCreators = action.prefillCreators,
                quickLogPrefillReleaseYear = action.prefillReleaseYear,
                quickLogExistingEntryId = action.existingEntryId,
            )
        MainAction.CloseQuickLogSheet ->
            state.copy(
                isQuickLogSheetOpen = false,
                quickLogTargetCollectionId = null,
                quickLogTargetCollectionTitle = null,
                quickLogPrefillKind = null,
                quickLogPrefillTitle = "",
                quickLogPrefillCreators = "",
                quickLogPrefillReleaseYear = null,
                quickLogExistingEntryId = null,
            )
        is MainAction.OpenDrawNoteDialog ->
            state.copy(drawNoteDialog = DrawNoteDialogState(action.collectionId, action.mode))
        MainAction.CloseDrawNoteDialog -> state.copy(drawNoteDialog = null)
        MainAction.CloseRankSession -> state.copy(rankSession = null)
        else -> state
    }

internal fun pushCatalogDetailEntry(stack: List<String>, entryId: String): List<String> {
    if (entryId.isBlank()) return stack
    if (stack.lastOrNull() == entryId) return stack
    val existingIdx = stack.indexOf(entryId)
    val next =
        if (existingIdx >= 0) {
            stack.subList(0, existingIdx + 1)
        } else {
            (stack + entryId).takeLast(MAX_CATALOG_DETAIL_STACK_DEPTH)
        }
    return next
}

private fun reduceOpenCompanionSection(
    state: TransientUiState,
    action: MainAction.OpenCompanionSection,
): TransientUiState {
    val targetTab =
        when (action.subTab) {
            CompanionSubTab.LISTS -> AppTab.LISTS
            CompanionSubTab.DIARY -> AppTab.DIARY
            CompanionSubTab.WANT -> AppTab.QUEUE
        }
    return state
        .clearSearch()
        .copy(
            activeTab = targetTab,
            companionSubTab = action.subTab,
            queueSubTab =
                if (action.subTab == CompanionSubTab.WANT) {
                    QueueSubTab.WANT
                } else {
                    state.queueSubTab
                },
            selectedCatalogCollectionId =
                if (action.subTab == CompanionSubTab.LISTS) {
                    action.collectionId
                } else {
                    state.selectedCatalogCollectionId
                },
            selectedWantCollectionId =
                if (action.subTab == CompanionSubTab.WANT && action.collectionId != null) {
                    action.collectionId
                } else {
                    state.selectedWantCollectionId
                },
            selectedReaderEntryId = null,
            readerContent = null,
            catalogDetailStack = emptyList(),
            selectedFeedId = null,
            selectedTag = null,
            scopeOriginTab = null,
        )
}

private fun reduceUpdateSearchQuery(state: TransientUiState, query: String): TransientUiState {
    val clean = query.trim()
    return state.copy(
        searchQuery = query,
        deepSearchQuery = if (clean.isEmpty()) "" else state.deepSearchQuery,
        workspaceSearch = if (clean.isEmpty()) WorkspaceSearchResult() else state.workspaceSearch,
    )
}

private const val MAX_CATALOG_DETAIL_STACK_DEPTH = 12

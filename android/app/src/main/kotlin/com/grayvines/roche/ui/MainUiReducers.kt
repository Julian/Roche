package com.grayvines.roche.ui

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
                    catalogDetailEntryId = null,
                    selectedFeedId = null,
                    selectedTag = null,
                    scopeOriginTab = null,
                )
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

private fun resolveNextScopeOriginTab(state: TransientUiState, hasNewScope: Boolean): AppTab? =
    when {
        !hasNewScope -> null
        state.activeTab != AppTab.INBOX -> state.activeTab
        else -> state.scopeOriginTab
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
                catalogDetailEntryId = null,
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
        catalogDetailEntryId = null,
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
        is MainAction.SelectPlayerDrawerTab ->
            state.copy(
                activePlayerDrawerTab =
                    if (state.activePlayerDrawerTab == action.tab) null else action.tab
            )
        MainAction.RequestAudioOutputCast ->
            state.copy(
                undoEvent = null,
                statusBanner = "Use system output switcher for Cast / Bluetooth audio",
                messageSequence = state.messageSequence + 1,
            )
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
        is MainAction.OpenCatalogDetailSheet -> state.copy(catalogDetailEntryId = action.entryId)
        MainAction.CloseCatalogDetailSheet -> state.copy(catalogDetailEntryId = null)
        is MainAction.OpenQuickLogSheet ->
            state.copy(
                catalogDetailEntryId = null,
                isQuickLogSheetOpen = true,
                quickLogDefaultWant = action.defaultToWant,
                quickLogTargetCollectionId = action.targetCollectionId,
                quickLogTargetCollectionTitle = action.targetCollectionTitle,
                quickLogPrefillKind = action.prefillKind,
                quickLogPrefillTitle = action.prefillTitle,
                quickLogPrefillCreators = action.prefillCreators,
                quickLogPrefillReleaseYear = action.prefillReleaseYear,
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
            )
        is MainAction.OpenDrawNoteDialog ->
            state.copy(drawNoteDialog = DrawNoteDialogState(action.collectionId, action.mode))
        MainAction.CloseDrawNoteDialog -> state.copy(drawNoteDialog = null)
        MainAction.CloseRankSession -> state.copy(rankSession = null)
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
            catalogDetailEntryId = null,
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
        deepSearchResults = if (clean.isEmpty()) emptyList() else state.deepSearchResults,
    )
}

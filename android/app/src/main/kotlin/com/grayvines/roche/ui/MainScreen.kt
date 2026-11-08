package com.grayvines.roche.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.ui.components.PlusIcon
import com.grayvines.roche.ui.components.RocheBottomBar
import com.grayvines.roche.ui.components.UndoOrStatusSnackbar
import com.grayvines.roche.ui.theme.LocalRocheColors
import kotlinx.coroutines.delay

@Composable
fun MainScreen(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    onImportOpml: () -> Unit = {},
    onExportOpml: () -> Unit = {},
) {
    val colors = LocalRocheColors.current
    val showChrome = state.activeReaderEntry == null
    val hasFab = showChrome && state.activeTab != AppTab.SETTINGS
    val hasScopeFilter = state.selectedFeedId != null || state.selectedTag != null

    BackHandler(enabled = showChrome && (hasScopeFilter || state.activeTab != AppTab.INBOX)) {
        if (hasScopeFilter) {
            onAction(MainAction.NavigateBackFromScope)
        } else {
            onAction(MainAction.SelectTab(AppTab.INBOX))
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize().background(colors.background),
        containerColor = colors.background,
        floatingActionButton = {
            if (hasFab) {
                SaveFloatingActionButton(onClick = { onAction(MainAction.OpenAddSheet()) })
            }
        },
        bottomBar = {
            if (showChrome) {
                RocheBottomBar(
                    activeTab = state.activeTab,
                    sideTabs = state.settings.bottomNavTabs,
                    onSelectTab = { onAction(MainAction.SelectTab(it)) },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding()
        ) {
            MainBodyContent(
                state = state,
                onAction = onAction,
                onShareEntry = onShareEntry,
                onOpenExternalUrl = onOpenExternalUrl,
                onImportOpml = onImportOpml,
                onExportOpml = onExportOpml,
            )

            MainSnackbarOverlay(
                state = state,
                hasFab = hasFab,
                onAction = onAction,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    MainOverlaySheets(
        state = state,
        onAction = onAction,
        onShareEntry = onShareEntry,
        onOpenExternalUrl = onOpenExternalUrl,
    )
}

@Composable
private fun MainSnackbarOverlay(
    state: MainUiState,
    hasFab: Boolean,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val undo = state.undoEvent
    val banner = state.statusBanner
    LaunchedEffect(undo, banner, state.messageSequence) {
        if (undo != null || banner != null) {
            delay(SNACKBAR_AUTO_DISMISS_MS)
            onAction(MainAction.DismissMessages)
        }
    }

    if (undo != null || banner != null) {
        val endPadding = if (hasFab) 80.dp else 14.dp
        UndoOrStatusSnackbar(
            message = undo?.message ?: banner.orEmpty(),
            showUndo = undo != null,
            onUndo = { onAction(MainAction.UndoLastArchive) },
            onDismiss = { onAction(MainAction.DismissMessages) },
            modifier = modifier.padding(start = 14.dp, end = endPadding, bottom = 14.dp),
        )
    }
}

@Composable
private fun SaveFloatingActionButton(onClick: () -> Unit) {
    val colors = LocalRocheColors.current
    FloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = colors.accentBlue,
        contentColor = Color.White,
        modifier =
            Modifier.size(52.dp)
                .semantics { contentDescription = "Save URL or Feed" }
                .testTag("save_fab"),
    ) {
        PlusIcon(tint = Color.White, size = 20.dp)
    }
}

@Composable
private fun MainOverlaySheets(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val contextEntry = state.contextSheetEntry
    if (contextEntry != null) {
        EntryContextBottomSheet(
            entry = contextEntry,
            availableTags = state.tags.map { it.name },
            onAction = onAction,
            onShareEntry = onShareEntry,
            onOpenExternalUrl = onOpenExternalUrl,
        )
    }

    val contextFeed = state.contextSheetFeed
    if (contextFeed != null) {
        FeedContextBottomSheet(
            feed = contextFeed,
            onAction = onAction,
            onOpenExternalUrl = onOpenExternalUrl,
        )
    }

    if (state.isAddSheetOpen) {
        AddUrlOrFeedBottomSheet(
            initialUrl = state.prefilledShareUrl,
            initialTitle = state.prefilledShareTitle,
            onDismiss = { onAction(MainAction.CloseAddSheet) },
            onSave = { url, title, tags ->
                onAction(MainAction.SaveUrlOrFeed(url = url, titleOverride = title, tagCsv = tags))
            },
        )
    }
}

@Composable
private fun MainBodyContent(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onImportOpml: () -> Unit,
    onExportOpml: () -> Unit,
) {
    val readerEntry = state.activeReaderEntry
    if (readerEntry != null) {
        ReaderView(
            entry = readerEntry,
            onAction = onAction,
            onShareEntry = onShareEntry,
            onOpenExternalUrl = onOpenExternalUrl,
            backLabel = readerBackLabel(state),
        )
        return
    }
    when (state.activeTab) {
        AppTab.INBOX -> InboxTabContent(state = state, onAction = onAction)
        AppTab.STARRED -> StarredTabContent(state = state, onAction = onAction)
        AppTab.SEARCH -> SearchTabContent(state = state, onAction = onAction)
        AppTab.FEEDS -> FeedsAndTagsTabContent(state = state, onAction = onAction)
        AppTab.OFFLINE -> OfflineTabContent(state = state, onAction = onAction)
        AppTab.SETTINGS ->
            SettingsTabContent(
                state = state,
                onAction = onAction,
                onImportOpml = onImportOpml,
                onExportOpml = onExportOpml,
            )
    }
}

private fun readerBackLabel(state: MainUiState): String =
    when (state.activeTab) {
        AppTab.STARRED -> "← Starred"
        AppTab.SEARCH -> "← Search"
        AppTab.OFFLINE -> "← Offline"
        else ->
            when {
                state.selectedFeedId != null -> "← ${state.selectedFeedTitle ?: "Feed"}"
                state.selectedTag != null -> "← #${state.selectedTag}"
                else -> "← Inbox"
            }
    }

private const val SNACKBAR_AUTO_DISMISS_MS = 4_000L

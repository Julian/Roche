package com.grayvines.roche.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
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

@Composable
fun MainScreen(
    state: MainUiState,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val showChrome = state.activeReaderEntry == null

    Scaffold(
        modifier = modifier.fillMaxSize().background(colors.background),
        containerColor = colors.background,
        floatingActionButton = {
            if (showChrome && state.activeTab != AppTab.SETTINGS) {
                SaveFloatingActionButton(onClick = { onAction(MainAction.OpenAddSheet()) })
            }
        },
        bottomBar = {
            if (showChrome) {
                RocheBottomBar(
                    activeTab = state.activeTab,
                    onSelectTab = { onAction(MainAction.SelectTab(it)) },
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            MainBodyContent(
                state = state,
                onAction = onAction,
                onShareEntry = onShareEntry,
            )

            val undo = state.undoEvent
            val banner = state.statusBanner
            if (undo != null || banner != null) {
                UndoOrStatusSnackbar(
                    message = undo?.message ?: banner.orEmpty(),
                    showUndo = undo != null,
                    onUndo = { onAction(MainAction.UndoLastArchive) },
                    onDismiss = { onAction(MainAction.DismissMessages) },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(14.dp),
                )
            }
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

    if (state.isAddSheetOpen) {
        AddUrlOrFeedBottomSheet(
            initialUrl = state.prefilledShareUrl,
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
) {
    val readerEntry = state.activeReaderEntry
    if (readerEntry != null) {
        ReaderView(
            entry = readerEntry,
            onAction = onAction,
            onShareEntry = onShareEntry,
        )
        return
    }
    when (state.activeTab) {
        AppTab.INBOX -> InboxTabContent(state = state, onAction = onAction)
        AppTab.SEARCH -> SearchTabContent(state = state, onAction = onAction)
        AppTab.FEEDS -> FeedsAndTagsTabContent(state = state, onAction = onAction)
        AppTab.OFFLINE -> OfflineTabContent(state = state, onAction = onAction)
        AppTab.SETTINGS -> SettingsTabContent(state = state, onAction = onAction)
    }
}

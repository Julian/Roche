package com.grayvines.roche.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.navigation3.runtime.NavKey
import com.grayvines.roche.model.AppTab
import kotlinx.serialization.Serializable

@Serializable
sealed interface RocheNavKey : NavKey {
    @Serializable data class Tab(val tab: AppTab) : RocheNavKey

    @Serializable
    data class ScopedInbox(
        val feedId: String?,
        val tag: String?,
    ) : RocheNavKey

    @Serializable data object Search : RocheNavKey

    @Serializable data class Reader(val entryId: String) : RocheNavKey

    @Serializable data class FullscreenPlayer(val entryId: String) : RocheNavKey
}

@Serializable
sealed interface SettingsNavKey : NavKey {
    @Serializable data object Hub : SettingsNavKey

    @Serializable data object Server : SettingsNavKey

    @Serializable data object Navigation : SettingsNavKey
}

internal fun deriveRocheBackStack(
    state: MainUiState,
    activeAudioEntryId: String? = null,
    activeReaderEntryId: String? =
        state.activeReaderEntry?.id
            ?: state.selectedReaderEntryId?.takeIf { state.isInitialLoading },
    activeFullscreenPlayerEntryId: String? =
        state.resolveActivePlayerEntry(activeAudioEntryId)?.id?.takeIf {
            state.isPlayerFullscreenOpen
        },
): List<RocheNavKey> = buildList {
    add(RocheNavKey.Tab(AppTab.HOME))
    val isScopedInbox =
        state.activeTab == AppTab.INBOX &&
            (state.selectedFeedId != null || state.selectedTag != null)
    val originOrActiveTab =
        if (isScopedInbox) {
            state.scopeOriginTab ?: AppTab.HOME
        } else {
            state.activeTab
        }
    if (originOrActiveTab != AppTab.HOME) {
        add(RocheNavKey.Tab(originOrActiveTab))
    }
    if (isScopedInbox) {
        add(
            RocheNavKey.ScopedInbox(
                feedId = state.selectedFeedId,
                tag = state.selectedTag,
            )
        )
    }
    if (state.isSearchOpen) {
        add(RocheNavKey.Search)
    }
    if (activeReaderEntryId != null) {
        add(RocheNavKey.Reader(activeReaderEntryId))
    }
    if (activeFullscreenPlayerEntryId != null) {
        add(RocheNavKey.FullscreenPlayer(activeFullscreenPlayerEntryId))
    }
}

internal fun popActionForTopKey(topKey: RocheNavKey): MainAction? =
    when (topKey) {
        is RocheNavKey.FullscreenPlayer -> MainAction.CloseFullscreenPlayer
        is RocheNavKey.Reader -> MainAction.CloseReader
        RocheNavKey.Search -> MainAction.CloseSearch
        is RocheNavKey.ScopedInbox -> MainAction.NavigateBackFromScope
        is RocheNavKey.Tab ->
            if (topKey.tab != AppTab.HOME) {
                MainAction.SelectTab(AppTab.HOME)
            } else {
                null
            }
    }

internal fun syncRocheNavBackStack(
    navBackStack: MutableList<NavKey>,
    desired: List<RocheNavKey>,
) {
    if (desired.isEmpty()) return
    var commonPrefix = 0
    val minSize = minOf(navBackStack.size, desired.size)
    while (commonPrefix < minSize && navBackStack[commonPrefix] == desired[commonPrefix]) {
        commonPrefix++
    }
    if (commonPrefix == navBackStack.size && commonPrefix == desired.size) {
        return
    }
    var writeStart = commonPrefix
    if (writeStart == 0) {
        if (navBackStack.isEmpty()) {
            navBackStack.add(desired[0])
        } else {
            navBackStack[0] = desired[0]
        }
        writeStart = 1
    }
    for (index in navBackStack.lastIndex downTo writeStart) {
        navBackStack.removeAt(index)
    }
    for (index in writeStart until desired.size) {
        navBackStack.add(desired[index])
    }
}

internal fun Modifier.predictiveBackDrawerGraphics(progress: Float): Modifier {
    val clamped = progress.coerceIn(MIN_PROGRESS, FULL_SCALE)
    if (clamped <= MIN_PROGRESS) return this
    return graphicsLayer {
        val scale = FULL_SCALE - clamped * DRAWER_BACK_MAX_SHRINK
        scaleX = scale
        scaleY = scale
        alpha = FULL_SCALE - clamped * DRAWER_BACK_MAX_FADE
        transformOrigin = TransformOrigin(DRAWER_ORIGIN_CENTER_X, MIN_PROGRESS)
    }
}

private const val MIN_PROGRESS = 0f
private const val FULL_SCALE = 1f
private const val DRAWER_ORIGIN_CENTER_X = 0.5f
private const val DRAWER_BACK_MAX_SHRINK = 0.08f
private const val DRAWER_BACK_MAX_FADE = 0.35f

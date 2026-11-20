package com.grayvines.roche.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.grayvines.roche.ui.predictiveBackDrawerGraphics
import com.grayvines.roche.ui.theme.LocalRocheColors
import kotlinx.coroutines.CancellationException

@Composable
internal fun SyncHeaderActionButton(
    isSyncing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "Sync",
) {
    val colors = LocalRocheColors.current
    RocheIconActionButton(
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
    ) {
        RefreshIcon(
            tint = if (isSyncing) colors.accentBlue else colors.textSecondary,
            size = 17.dp,
            isSpinning = isSyncing,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RocheRefreshableTabScaffold(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    pullToRefreshTestTag: String,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subHeader: (@Composable () -> Unit)? = null,
    drawer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(modifier = modifier.fillMaxSize()) {
        header()
        subHeader?.invoke()
        HorizontalDivider(color = colors.border, thickness = 1.dp)
        drawer?.invoke()
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().testTag(pullToRefreshTestTag),
        ) {
            content()
        }
    }
}

@Composable
internal fun PredictiveBackDrawerContainer(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    var backProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(visible) {
        if (!visible) {
            backProgress = 0f
        }
    }
    PredictiveBackHandler(enabled = visible) { progress ->
        try {
            progress.collect { backEvent -> backProgress = backEvent.progress }
            backProgress = 0f
            onDismiss()
        } catch (cancelled: CancellationException) {
            backProgress = 0f
            throw cancelled
        }
    }
    if (!visible) return
    content(Modifier.predictiveBackDrawerGraphics(backProgress))
}

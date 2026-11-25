@file:Suppress("TooManyFunctions", "MagicNumber")

package com.grayvines.roche.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.grayvines.roche.data.catalog.ProvisionalFocusRank
import com.grayvines.roche.data.catalog.RankSettlementSummary
import com.grayvines.roche.data.catalog.RankedCatalogEntry
import com.grayvines.roche.ranking.EpRankingEngine
import com.grayvines.roche.ranking.RankBoardState
import com.grayvines.roche.ranking.RankClusterCandidate
import com.grayvines.roche.ranking.RankDropTarget
import com.grayvines.roche.ranking.RankDropZone
import com.grayvines.roche.ranking.resolveRankDropTarget
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.TierBadgePill
import com.grayvines.roche.ui.components.rememberEntryThumbnailBitmap
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.RocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily

private data class CardRootBounds(
    val left: Float,
    val top: Float,
    val widthPx: Int,
    val heightPx: Int,
)

private data class ActiveRankDragState(
    val candidate: RankClusterCandidate,
    val sourceBucketIndex: Int,
    val startLeftInRoot: Float,
    val startTopInRoot: Float,
    val widthPx: Int,
    val heightPx: Int,
    val frozenBucketBounds: List<Pair<Float, Float>> = emptyList(),
    val deltaX: Float = 0f,
    val deltaY: Float = 0f,
    val dropTarget: RankDropTarget? = null,
)

private data class RankCardDragHandlers(
    val onStart: (RankClusterCandidate, Int) -> Unit,
    val onDrag: (Offset) -> Unit,
    val onEnd: () -> Unit,
    val onCancel: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FocusedRankBottomSheet(
    session: ActiveRankSessionUiState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val sheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
    var isDraggingCard by remember { mutableStateOf(false) }
    val settlement = session.settlement
    LaunchedEffect(settlement != null) {
        if (settlement != null) isDraggingCard = false
    }

    ModalBottomSheet(
        onDismissRequest = { onAction(MainAction.CloseRankSession) },
        sheetState = sheetState,
        sheetGesturesEnabled = !isDraggingCard,
        containerColor = colors.surface,
    ) {
        if (settlement != null) {
            RankSettlementSheetBody(
                session = session,
                settlement = settlement,
                onAction = onAction,
            )
        } else {
            ActiveClusterRankBody(
                session = session,
                onDragActiveChange = { isDraggingCard = it },
                onAction = onAction,
            )
        }
    }
}

@Composable
private fun RankSettlementSheetBody(
    session: ActiveRankSessionUiState,
    settlement: RankSettlementSummary,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val neighbors = settlement.neighbors
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .testTag("rank_settlement_card"),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "PLACEMENT SETTLED · ${settlement.collectionTitle.uppercase()}",
            color = colors.accentVerdigris,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            letterSpacing = 0.8.sp,
        )
        SettlementHeroRow(
            title = settlement.title,
            subtitle = settlement.subtitle,
            thumbnailUrl = settlement.thumbnailUrl,
        )
        SettlementSummaryCard(settlement = settlement, roundCount = session.roundIndex)
        if (neighbors.isNotEmpty()) {
            SettlementNeighborsSection(
                collectionTitle = settlement.collectionTitle,
                focusEntryId = settlement.entryId,
                neighbors = neighbors,
            )
        }
        SettlementFooterRow(
            canUndoLast = session.history.isNotEmpty(),
            canKeepRefining = settlement.totalCount >= 2,
            onUndoLast = { onAction(MainAction.UndoLastRankRound) },
            onKeepRefining = {
                if (session.focusEntryId != null) {
                    onAction(MainAction.KeepRefiningRank)
                } else {
                    onAction(MainAction.StartRankingSession(session.collectionIdOrSlug, null))
                }
            },
            onDone = { onAction(MainAction.CloseRankSession) },
        )
        Spacer(modifier = Modifier.height(14.dp))
    }
}

@Composable
private fun SettlementHeroRow(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RankCoverThumb(thumbnailUrl = thumbnailUrl, fallbackLabel = title, size = 64.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                color = colors.textPrimary,
                fontFamily = currentHeadlineFontFamily(),
                fontSize = 21.sp,
                lineHeight = 25.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SettlementSummaryCard(
    settlement: RankSettlementSummary,
    roundCount: Int,
) {
    val colors = LocalRocheColors.current
    val rankLine =
        remember(settlement.rank, settlement.totalCount, colors) {
            buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        color = colors.textPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Normal,
                    )
                ) {
                    append("#${settlement.rank}")
                }
                withStyle(
                    SpanStyle(
                        color = colors.textMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                ) {
                    append(" of ${settlement.totalCount}")
                }
            }
        }
    val subText =
        if (roundCount > 0) {
            val noun = if (roundCount == 1) "round" else "rounds"
            "Settled after $roundCount comparison $noun"
        } else {
            "Placed in ${settlement.collectionTitle}"
        }
    RocheSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(text = rankLine)
                Text(text = subText, color = colors.textSecondary, fontSize = 12.5.sp)
            }
            if (settlement.tier != null) {
                TierBadgePill(tier = settlement.tier)
            }
        }
    }
}

@Composable
private fun SettlementNeighborsSection(
    collectionTitle: String,
    focusEntryId: String,
    neighbors: List<RankedCatalogEntry>,
) {
    val colors = LocalRocheColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "NEIGHBORS IN ${collectionTitle.uppercase()}",
            color = colors.textMuted,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(horizontal = 2.dp).semantics { heading() },
        )
        RocheSurfaceCard {
            neighbors.forEachIndexed { idx, row ->
                if (idx > 0) {
                    HorizontalDivider(color = colors.border, thickness = 1.dp)
                }
                SettlementNeighborRow(row = row, isFocus = row.entry.id == focusEntryId)
            }
        }
    }
}

@Composable
private fun SettlementNeighborRow(row: RankedCatalogEntry, isFocus: Boolean) {
    val colors = LocalRocheColors.current
    val bg = if (isFocus) colors.accentBlue.copy(alpha = 0.11f) else Color.Transparent
    Row(
        modifier =
            Modifier.fillMaxWidth().background(bg).padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "#${row.rank}",
            color = if (isFocus) colors.accentBlue else colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            modifier = Modifier.width(30.dp),
        )
        Text(
            text = row.entry.title,
            color = colors.textPrimary,
            fontFamily = currentHeadlineFontFamily(),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (row.tier != null) {
            TierBadgePill(tier = row.tier)
        }
    }
}

@Composable
private fun SettlementFooterRow(
    canUndoLast: Boolean,
    canKeepRefining: Boolean,
    onUndoLast: () -> Unit,
    onKeepRefining: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canUndoLast) {
            HeaderActionButton(
                label = "Undo",
                onClick = onUndoLast,
                modifier = Modifier.testTag("rank_settlement_undo_btn"),
            )
        }
        if (canKeepRefining) {
            HeaderActionButton(
                label = "Keep Refining",
                onClick = onKeepRefining,
                modifier = Modifier.testTag("rank_keep_comparing_btn"),
            )
        }
        HeaderActionButton(
            label = "Done",
            style = RocheButtonStyle.PRIMARY,
            onClick = onDone,
            modifier = Modifier.testTag("rank_settlement_done_btn"),
        )
    }
}

private fun rankBoardStateSaver(
    candidates: List<RankClusterCandidate>
): Saver<RankBoardState, ArrayList<ArrayList<String>>> =
    Saver(
        save = { state ->
            val encoded = ArrayList<ArrayList<String>>(state.buckets.size + 2)
            encoded.add(ArrayList(state.excluded.map { it.entryId }))
            encoded.add(ArrayList(state.manuallyExcludedIds))
            for (bucket in state.buckets) {
                encoded.add(ArrayList(bucket.map { it.entryId }))
            }
            encoded
        },
        restore = { saved ->
            val byId = candidates.associateBy { it.entryId }
            if (saved.size < 3) {
                RankBoardState.fromCandidates(candidates)
            } else {
                val excluded = saved[0].mapNotNull { byId[it] }
                val manualIds = saved[1].filterTo(HashSet()) { it in byId }
                val restoredBuckets =
                    saved
                        .drop(2)
                        .map { bucketIds -> bucketIds.mapNotNull { byId[it] } }
                        .filter { it.isNotEmpty() }
                val activeIds = restoredBuckets.flatten().map { it.entryId }.toSet()
                val excludedIds = excluded.map { it.entryId }.toSet()
                val allAccounted =
                    activeIds + excludedIds == byId.keys &&
                        (activeIds intersect excludedIds).isEmpty() &&
                        activeIds.size >= EpRankingEngine.MIN_K
                if (allAccounted) {
                    RankBoardState(
                        buckets = restoredBuckets,
                        excluded = excluded,
                        manuallyExcludedIds = manualIds intersect excludedIds,
                    )
                } else {
                    RankBoardState.fromCandidates(candidates)
                }
            }
        },
    )

@Composable
private fun ActiveClusterRankBody(
    session: ActiveRankSessionUiState,
    onDragActiveChange: (Boolean) -> Unit,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val clusterKey =
        remember(session.roundIndex, session.cluster) { session.cluster.map { it.entryId } }
    val boardSaver = remember(session.cluster) { rankBoardStateSaver(session.cluster) }
    var board by
        rememberSaveable(session.roundIndex, clusterKey, stateSaver = boardSaver) {
            val init = RankBoardState.fromCandidates(session.cluster)
            mutableStateOf(init.resizeTo(session.targetClusterSize, session.focusEntryId))
        }
    var activeDrag by
        remember(session.roundIndex, clusterKey) { mutableStateOf<ActiveRankDragState?>(null) }
    LaunchedEffect(activeDrag != null) { onDragActiveChange(activeDrag != null) }
    val provisionalRank = session.provisionalRank

    BoxWithConstraints(modifier = Modifier.fillMaxWidth().testTag("focused_rank_sheet")) {
        val isWide = maxWidth >= RANK_WIDE_BREAKPOINT
        val hPad = if (isWide) 20.dp else 16.dp
        Column(modifier = Modifier.fillMaxWidth()) {
            ClusterRankHeader(session, provisionalRank, hPad) {
                dispatchDonePlacingAction(session, onAction)
            }
            HorizontalDivider(color = colors.border, thickness = 1.dp)
            ClusterRankBoardScrollArea(
                session = session,
                board = board,
                isWide = isWide,
                horizontalPad = hPad,
                activeDrag = activeDrag,
                onActiveDragChange = { next ->
                    activeDrag = next
                    onDragActiveChange(next != null)
                },
                onBoardChange = {
                    applyBoardChangeWithSizeSync(session, board, it, { b -> board = b }, onAction)
                },
                onSelectClusterSize = { targetK ->
                    applyClusterSizeSelection(
                        session,
                        board,
                        targetK,
                        { board = board.resizeTo(it, session.focusEntryId) },
                        onAction,
                    )
                },
            )
            HorizontalDivider(color = colors.border, thickness = 1.dp)
            ClusterRankFooter(
                board = board,
                canUndo = session.history.isNotEmpty(),
                horizontalPad = hPad,
                onToggleTieAll = { board = board.toggleTieAll() },
                onAction = onAction,
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun ClusterRankBoardScrollArea(
    session: ActiveRankSessionUiState,
    board: RankBoardState,
    isWide: Boolean,
    horizontalPad: Dp,
    activeDrag: ActiveRankDragState?,
    onActiveDragChange: (ActiveRankDragState?) -> Unit,
    onBoardChange: (RankBoardState) -> Unit,
    onSelectClusterSize: (Int) -> Unit,
) {
    val maxSelectableK = session.maxSelectableClusterSize()
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState(), enabled = activeDrag == null)
                .padding(horizontal = horizontalPad, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ClusterRankProgressAndHint(session = session)
        if (maxSelectableK > EpRankingEngine.MIN_K) {
            ClusterSizeSelectorRow(
                activeSize =
                    session.targetClusterSize.coerceIn(EpRankingEngine.MIN_K, maxSelectableK),
                maxSelectableK = maxSelectableK,
                board = board,
                onSelectSize = onSelectClusterSize,
            )
        }
        DraggableRankBoard(
            board = board,
            focusEntryId = session.focusEntryId,
            isWide = isWide,
            activeDrag = activeDrag,
            onActiveDragChange = onActiveDragChange,
            onBoardChange = onBoardChange,
        )
        if (board.excluded.isNotEmpty()) {
            ExcludedCandidatesTray(
                excluded = board.excluded,
                manualExcludedIds = board.manuallyExcludedIds,
                onRestore = { id -> onBoardChange(board.restore(id)) },
                onRestoreAll = { onBoardChange(board.restoreAll()) },
            )
        }
    }
}

private fun applyBoardChangeWithSizeSync(
    session: ActiveRankSessionUiState,
    previous: RankBoardState,
    next: RankBoardState,
    onSetBoard: (RankBoardState) -> Unit,
    onAction: (MainAction) -> Unit,
) {
    val grewAboveTarget = next.size > previous.size && next.size > session.targetClusterSize
    onSetBoard(next)
    if (grewAboveTarget) {
        onAction(
            MainAction.SetRankClusterSize(
                clusterSize = next.size.coerceAtMost(session.maxSelectableClusterSize()),
                reloadCluster = false,
            )
        )
    }
}

private fun dispatchDonePlacingAction(
    session: ActiveRankSessionUiState,
    onAction: (MainAction) -> Unit,
) {
    if (session.focusEntryId != null && session.roundIndex > 0) {
        onAction(MainAction.FinishRankEarly)
    } else {
        onAction(MainAction.CloseRankSession)
    }
}

private fun applyClusterSizeSelection(
    session: ActiveRankSessionUiState,
    board: RankBoardState,
    targetK: Int,
    onResizeInPlace: (Int) -> Unit,
    onAction: (MainAction) -> Unit,
) {
    val maxSelectableK = session.maxSelectableClusterSize()
    val needsReload =
        targetK > board.totalCandidateCount &&
            targetK > session.loadedMaxK &&
            targetK <= maxSelectableK
    if (needsReload) {
        onAction(
            MainAction.SetRankClusterSize(
                clusterSize = targetK,
                reloadCluster = true,
                manualExcludedIds = board.manuallyExcludedIds,
            )
        )
    } else {
        onResizeInPlace(targetK)
        onAction(MainAction.SetRankClusterSize(targetK, reloadCluster = false))
    }
}

@Composable
private fun ClusterSizeSelectorRow(
    activeSize: Int,
    maxSelectableK: Int,
    board: RankBoardState,
    onSelectSize: (Int) -> Unit,
) {
    val colors = LocalRocheColors.current
    val comparingCount = board.size
    val totalLoadedCount = board.totalCandidateCount
    val manualExcludedCount = board.manuallyExcludedIds.size
    val countNote =
        when {
            comparingCount >= totalLoadedCount -> "$comparingCount items"
            manualExcludedCount > 0 ->
                "$comparingCount of $totalLoadedCount ($manualExcludedCount excluded)"
            else -> "$comparingCount of $totalLoadedCount shown"
        }
    FlowRow(
        modifier = Modifier.fillMaxWidth().testTag("rank_size_selector"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "COMPARE",
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 0.7.sp,
                modifier = Modifier.padding(end = 4.dp),
            )
            for (k in EpRankingEngine.MIN_K..maxSelectableK) {
                ClusterSizeOptionPill(
                    sizeOption = k,
                    isActive = k == activeSize,
                    onClick = { onSelectSize(k) },
                )
            }
        }
        Text(
            text = countNote,
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ClusterSizeOptionPill(
    sizeOption: Int,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(999.dp)
    val bg = if (isActive) colors.accentBlue.copy(alpha = 0.16f) else colors.background
    val borderColor = if (isActive) colors.accentBlue else colors.border
    val fg = if (isActive) colors.accentBlue else colors.textSecondary
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.minimumInteractiveComponentSize()
                .clip(shape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    selected = isActive
                    contentDescription = "Compare $sizeOption items"
                }
                .testTag("rank_size_$sizeOption"),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(width = 34.dp, height = 28.dp)
                    .clip(shape)
                    .background(bg)
                    .border(1.dp, borderColor, shape),
        ) {
            Text(
                text = sizeOption.toString(),
                color = fg,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun ExcludedCandidatesTray(
    excluded: List<RankClusterCandidate>,
    manualExcludedIds: Set<String>,
    onRestore: (String) -> Unit,
    onRestoreAll: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(11.dp)
    val manualCount = excluded.count { it.entryId in manualExcludedIds }
    val headingText =
        when {
            manualCount == 0 -> "SET ASIDE AT THIS SIZE (${excluded.size})"
            manualCount == excluded.size -> "EXCLUDED FROM COMPARISON (${excluded.size})"
            else -> "LEFT OUT OF COMPARISON (${excluded.size})"
        }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clip(shape)
                .background(colors.background)
                .dashedRoundBorder(colors.border, 11.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("rank_excluded_tray"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ExcludedTrayHeader(
            headingText = headingText,
            showRestoreAll = excluded.size > 1,
            onRestoreAll = onRestoreAll,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            for (candidate in excluded) {
                ExcludedCandidateChip(
                    candidate = candidate,
                    isManualExcluded = candidate.entryId in manualExcludedIds,
                    onClick = { onRestore(candidate.entryId) },
                )
            }
        }
    }
}

@Composable
private fun ExcludedTrayHeader(
    headingText: String,
    showRestoreAll: Boolean,
    onRestoreAll: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = headingText,
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            letterSpacing = 0.7.sp,
        )
        if (showRestoreAll) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onRestoreAll)
                        .semantics { role = Role.Button }
                        .testTag("rank_restore_all_btn"),
            ) {
                Text(
                    text = "Restore all",
                    color = colors.accentBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ExcludedCandidateChip(
    candidate: RankClusterCandidate,
    isManualExcluded: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val chipShape = RoundedCornerShape(999.dp)
    val borderMod =
        if (isManualExcluded) {
            Modifier.dashedRoundBorder(colors.accentEcru.copy(alpha = 0.65f), 999.dp)
        } else {
            Modifier.border(1.dp, colors.border, chipShape)
        }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.minimumInteractiveComponentSize()
                .clip(chipShape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = "Restore ${candidate.title}"
                }
                .testTag("rank_restore_${candidate.entryId}"),
    ) {
        Row(
            modifier =
                Modifier.clip(chipShape)
                    .background(colors.surface)
                    .then(borderMod)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "+",
                color = colors.accentBlue,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Text(
                text = candidate.title,
                color = colors.textPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun ClusterRankHeader(
    session: ActiveRankSessionUiState,
    provisionalRank: ProvisionalFocusRank?,
    horizontalPad: Dp,
    onDonePlacing: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val heading =
        if (session.scopeCollectionId != null) {
            "Order next up to later"
        } else {
            "Order best to worst"
        }
    val doneLabel =
        if (session.focusEntryId != null && session.roundIndex > 0) "Done Placing" else "Done"
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(start = horizontalPad, end = horizontalPad, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ClusterRankKickerRow(session = session)
            Text(
                text = heading,
                color = colors.textPrimary,
                fontFamily = currentHeadlineFontFamily(),
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
            )
            if (provisionalRank != null) {
                ProvisionalRankBadgeRow(provisional = provisionalRank)
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDonePlacing)
                    .semantics { role = Role.Button }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("rank_done_placing_btn"),
        ) {
            Text(
                text = doneLabel,
                color =
                    if (session.focusEntryId != null && session.roundIndex > 0) {
                        colors.accentBlue
                    } else {
                        colors.textSecondary
                    },
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ProvisionalRankBadgeRow(provisional: ProvisionalFocusRank) {
    val colors = LocalRocheColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag("rank_provisional_badge"),
    ) {
        Text(
            text = "Provisional #${provisional.rank} of ${provisional.totalCount}",
            color = colors.textSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        if (provisional.tier != null) {
            TierBadgePill(tier = provisional.tier)
        }
    }
}

@Composable
private fun ClusterRankKickerRow(session: ActiveRankSessionUiState) {
    val colors = LocalRocheColors.current
    val kicker =
        if (session.scopeCollectionId != null) {
            "PRIORITIZE · ${session.collectionTitle.uppercase()}"
        } else {
            session.collectionTitle.uppercase()
        }
    val roundLabel =
        if (session.focusEntryId != null) {
            "Round ${session.roundIndex + 1} of ${session.roundCap}"
        } else {
            "Round ${session.roundIndex + 1}"
        }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = kicker,
            color = colors.accentVerdigris,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            letterSpacing = 0.8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Box(
            modifier =
                Modifier.clip(RoundedCornerShape(999.dp))
                    .background(colors.background)
                    .border(1.dp, colors.border, RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = roundLabel,
                color = colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun ClusterRankProgressAndHint(session: ActiveRankSessionUiState) {
    val colors = LocalRocheColors.current
    if (session.focusEntryId != null) {
        val cap = session.roundCap.coerceAtLeast(1)
        val fraction = ((session.roundIndex + 1).toFloat() / cap.toFloat()).coerceIn(0.15f, 1f)
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.border)
        ) {
            Box(
                modifier =
                    Modifier.fillMaxWidth(fraction)
                        .height(3.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.accentBlue)
            )
        }
    }
    Text(
        text = "Drag cards to reorder — drop onto another card to tie, or tap × to exclude.",
        color = colors.textMuted,
        fontSize = 12.5.sp,
    )
}

@Composable
private fun DraggableRankBoard(
    board: RankBoardState,
    focusEntryId: String?,
    isWide: Boolean,
    activeDrag: ActiveRankDragState?,
    onActiveDragChange: (ActiveRankDragState?) -> Unit,
    onBoardChange: (RankBoardState) -> Unit,
) {
    val bucketBounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val cardBounds = remember { mutableStateMapOf<String, CardRootBounds>() }
    var boardOrigin by remember { mutableStateOf(Offset.Zero) }
    val dragHandlers =
        rememberRankDragHandlers(
            board = board,
            activeDrag = activeDrag,
            bucketBounds = bucketBounds,
            cardBounds = cardBounds,
            onActiveDragChange = onActiveDragChange,
            onBoardChange = onBoardChange,
        )

    Box(
        modifier =
            Modifier.fillMaxWidth().onGloballyPositioned { coords ->
                boardOrigin = coords.positionInRoot()
            }
    ) {
        RankBucketsColumn(
            board = board,
            focusEntryId = focusEntryId,
            isWide = isWide,
            activeDrag = activeDrag,
            onBucketPositioned = { bIdx, top, bottom -> bucketBounds[bIdx] = top to bottom },
            onCardPositioned = { entryId, bounds -> cardBounds[entryId] = bounds },
            dragHandlers = dragHandlers,
            onBoardChange = onBoardChange,
        )
        if (activeDrag != null) {
            FloatingDraggedRankCard(
                drag = activeDrag,
                boardOrigin = boardOrigin,
                isFocused = activeDrag.candidate.entryId == focusEntryId,
            )
        }
    }
}

@Composable
private fun rememberRankDragHandlers(
    board: RankBoardState,
    activeDrag: ActiveRankDragState?,
    bucketBounds: Map<Int, Pair<Float, Float>>,
    cardBounds: Map<String, CardRootBounds>,
    onActiveDragChange: (ActiveRankDragState?) -> Unit,
    onBoardChange: (RankBoardState) -> Unit,
): RankCardDragHandlers {
    val haptic = LocalHapticFeedback.current
    val currentBoard by rememberUpdatedState(board)
    val currentDrag by rememberUpdatedState(activeDrag)
    return remember(haptic) {
        RankCardDragHandlers(
            onStart = { candidate, bIdx ->
                cardBounds[candidate.entryId]?.let { bounds ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val frozenBounds =
                        currentBoard.buckets.indices.mapNotNull { idx -> bucketBounds[idx] }
                    onActiveDragChange(
                        ActiveRankDragState(
                            candidate = candidate,
                            sourceBucketIndex = bIdx,
                            startLeftInRoot = bounds.left,
                            startTopInRoot = bounds.top,
                            widthPx = bounds.widthPx,
                            heightPx = bounds.heightPx,
                            frozenBucketBounds = frozenBounds,
                        )
                    )
                }
            },
            onDrag = { delta ->
                currentDrag?.let { drag ->
                    val next = advanceRankDragState(drag, delta, currentBoard, bucketBounds)
                    if (next.dropTarget != drag.dropTarget && next.dropTarget != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    onActiveDragChange(next)
                }
            },
            onEnd = {
                val drag = currentDrag
                onActiveDragChange(null)
                drag?.dropTarget?.let { target ->
                    onBoardChange(currentBoard.dropOnto(drag.candidate.entryId, target))
                }
            },
            onCancel = { onActiveDragChange(null) },
        )
    }
}

private fun advanceRankDragState(
    drag: ActiveRankDragState,
    delta: Offset,
    board: RankBoardState,
    bucketBounds: Map<Int, Pair<Float, Float>>,
): ActiveRankDragState {
    val nextDeltaY = drag.deltaY + delta.y
    val centerY = drag.startTopInRoot + drag.heightPx * 0.5f + nextDeltaY
    val orderedBounds =
        if (drag.frozenBucketBounds.size == board.buckets.size) {
            drag.frozenBucketBounds
        } else {
            board.buckets.indices.mapNotNull { idx -> bucketBounds[idx] }
        }
    val nextTarget =
        if (orderedBounds.size == board.buckets.size) {
            resolveRankDropTarget(centerY, drag.sourceBucketIndex, orderedBounds)
        } else {
            null
        }
    return drag.copy(
        deltaX = drag.deltaX + delta.x,
        deltaY = nextDeltaY,
        dropTarget = nextTarget,
    )
}

@Composable
private fun RankBucketsColumn(
    board: RankBoardState,
    focusEntryId: String?,
    isWide: Boolean,
    activeDrag: ActiveRankDragState?,
    onBucketPositioned: (Int, Float, Float) -> Unit,
    onCardPositioned: (String, CardRootBounds) -> Unit,
    dragHandlers: RankCardDragHandlers,
    onBoardChange: (RankBoardState) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        var runningRank = 1
        board.buckets.forEachIndexed { bIdx, bucket ->
            val displayRank = runningRank
            val startFlatIndex = runningRank - 1
            runningRank += bucket.size
            RankBucketRow(
                bucketIndex = bIdx,
                displayRank = displayRank,
                startFlatIndex = startFlatIndex,
                bucket = bucket,
                totalBuckets = board.buckets.size,
                canExcludeAny = board.size > EpRankingEngine.MIN_K,
                focusEntryId = focusEntryId,
                isWide = isWide,
                draggedEntryId = activeDrag?.candidate?.entryId,
                dropZone = activeDrag?.dropTarget?.takeIf { it.bucketIndex == bIdx }?.zone,
                onBucketPositioned = onBucketPositioned,
                onCardPositioned = onCardPositioned,
                dragHandlers = dragHandlers,
                onMoveUp = { id -> onBoardChange(board.moveUp(id)) },
                onMoveDown = { id -> onBoardChange(board.moveDown(id)) },
                onToggleTie = { id -> onBoardChange(board.toggleTieWithAbove(id)) },
                onExclude = { id -> onBoardChange(board.exclude(id, focusEntryId)) },
            )
        }
    }
}

@Composable
private fun RankBucketRow(
    bucketIndex: Int,
    displayRank: Int,
    startFlatIndex: Int,
    bucket: List<RankClusterCandidate>,
    totalBuckets: Int,
    canExcludeAny: Boolean,
    focusEntryId: String?,
    isWide: Boolean,
    draggedEntryId: String?,
    dropZone: RankDropZone?,
    onBucketPositioned: (Int, Float, Float) -> Unit,
    onCardPositioned: (String, CardRootBounds) -> Unit,
    dragHandlers: RankCardDragHandlers,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onToggleTie: (String) -> Unit,
    onExclude: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val isTiedBucket = bucket.size > 1
    val isDropTie = dropZone == RankDropZone.TIE
    val showDashed = isTiedBucket || isDropTie
    Box(
        modifier =
            Modifier.fillMaxWidth().onGloballyPositioned { coords ->
                val top = coords.positionInRoot().y
                onBucketPositioned(bucketIndex, top, top + coords.size.height)
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().bucketChrome(isTiedBucket, isDropTie, colors),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RankBucketGutter(displayRank = displayRank, isTied = showDashed)
            RankBucketCardsArea(
                bucketIndex,
                startFlatIndex,
                bucket,
                totalBuckets,
                canExcludeAny,
                focusEntryId,
                isWide,
                draggedEntryId,
                onCardPositioned,
                dragHandlers,
                onMoveUp,
                onMoveDown,
                onToggleTie,
                onExclude,
                Modifier.weight(1f),
            )
        }
        if (dropZone == RankDropZone.BEFORE) {
            DropInsertionBar(modifier = Modifier.align(Alignment.TopCenter).zIndex(5f))
        }
        if (isDropTie) {
            DropTieHintBadge(
                modifier =
                    Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 6.dp).zIndex(5f)
            )
        }
        if (dropZone == RankDropZone.AFTER) {
            DropInsertionBar(modifier = Modifier.align(Alignment.BottomCenter).zIndex(5f))
        }
    }
}

private fun Modifier.bucketChrome(
    isTiedBucket: Boolean,
    isDropTie: Boolean,
    colors: RocheColors,
): Modifier {
    val showDashed = isTiedBucket || isDropTie
    val bg =
        when {
            isDropTie -> colors.accentEcru.copy(alpha = 0.14f)
            isTiedBucket -> colors.accentEcru.copy(alpha = 0.06f)
            else -> Color.Transparent
        }
    val dashColor = if (isDropTie) colors.accentEcru else colors.accentEcru.copy(alpha = 0.55f)
    return clip(RoundedCornerShape(12.dp))
        .background(bg)
        .then(if (showDashed) Modifier.dashedRoundBorder(dashColor, 12.dp) else Modifier)
        .padding(
            horizontal = if (isTiedBucket) 6.dp else 0.dp,
            vertical = if (isTiedBucket) 7.dp else 0.dp,
        )
}

@Composable
private fun RankBucketCardsArea(
    bucketIndex: Int,
    startFlatIndex: Int,
    bucket: List<RankClusterCandidate>,
    totalBuckets: Int,
    canExcludeAny: Boolean,
    focusEntryId: String?,
    isWide: Boolean,
    draggedEntryId: String?,
    onCardPositioned: (String, CardRootBounds) -> Unit,
    dragHandlers: RankCardDragHandlers,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onToggleTie: (String) -> Unit,
    onExclude: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isWide && bucket.size > 1) {
        SideBySideBucketCards(
            bucketIndex,
            startFlatIndex,
            bucket,
            totalBuckets,
            canExcludeAny,
            focusEntryId,
            draggedEntryId,
            onCardPositioned,
            dragHandlers,
            onMoveUp,
            onMoveDown,
            onToggleTie,
            onExclude,
            modifier,
        )
    } else {
        StackedBucketCards(
            bucketIndex,
            startFlatIndex,
            bucket,
            totalBuckets,
            canExcludeAny,
            focusEntryId,
            draggedEntryId,
            onCardPositioned,
            dragHandlers,
            onMoveUp,
            onMoveDown,
            onToggleTie,
            onExclude,
            modifier,
        )
    }
}

@Composable
private fun SideBySideBucketCards(
    bucketIndex: Int,
    startFlatIndex: Int,
    bucket: List<RankClusterCandidate>,
    totalBuckets: Int,
    canExcludeAny: Boolean,
    focusEntryId: String?,
    draggedEntryId: String?,
    onCardPositioned: (String, CardRootBounds) -> Unit,
    dragHandlers: RankCardDragHandlers,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onToggleTie: (String) -> Unit,
    onExclude: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chunked = remember(bucket) { bucket.withIndex().toList().chunked(2) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chunked.forEach { rowPair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowPair.forEach { (iIdx, candidate) ->
                    val isFocus = candidate.entryId == focusEntryId
                    RankCandidateCard(
                        candidate,
                        bucketIndex,
                        iIdx,
                        startFlatIndex + iIdx,
                        bucket.size,
                        totalBuckets,
                        isFocus,
                        canExcludeAny && !isFocus,
                        true,
                        candidate.entryId == draggedEntryId,
                        onCardPositioned,
                        dragHandlers,
                        { onMoveUp(candidate.entryId) },
                        { onMoveDown(candidate.entryId) },
                        { onToggleTie(candidate.entryId) },
                        { onExclude(candidate.entryId) },
                        Modifier.weight(1f),
                    )
                }
                if (rowPair.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StackedBucketCards(
    bucketIndex: Int,
    startFlatIndex: Int,
    bucket: List<RankClusterCandidate>,
    totalBuckets: Int,
    canExcludeAny: Boolean,
    focusEntryId: String?,
    draggedEntryId: String?,
    onCardPositioned: (String, CardRootBounds) -> Unit,
    dragHandlers: RankCardDragHandlers,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onToggleTie: (String) -> Unit,
    onExclude: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        bucket.forEachIndexed { iIdx, candidate ->
            val isFocus = candidate.entryId == focusEntryId
            RankCandidateCard(
                candidate,
                bucketIndex,
                iIdx,
                startFlatIndex + iIdx,
                bucket.size,
                totalBuckets,
                isFocus,
                canExcludeAny && !isFocus,
                false,
                candidate.entryId == draggedEntryId,
                onCardPositioned,
                dragHandlers,
                { onMoveUp(candidate.entryId) },
                { onMoveDown(candidate.entryId) },
                { onToggleTie(candidate.entryId) },
                { onExclude(candidate.entryId) },
                Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RankBucketGutter(displayRank: Int, isTied: Boolean) {
    val colors = LocalRocheColors.current
    Column(
        modifier = Modifier.width(30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "#$displayRank",
            color = if (isTied) colors.accentEcru else colors.textSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.5.sp,
        )
        if (isTied) {
            Box(
                modifier =
                    Modifier.clip(RoundedCornerShape(999.dp))
                        .background(colors.accentEcru.copy(alpha = 0.14f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "TIE",
                    color = colors.accentEcru,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.5.sp,
                )
            }
        }
    }
}

@Composable
private fun RankCandidateCard(
    candidate: RankClusterCandidate,
    bucketIndex: Int,
    itemIndexInBucket: Int,
    flatIndex: Int,
    bucketSize: Int,
    totalBuckets: Int,
    isFocused: Boolean,
    canExclude: Boolean,
    isCompactTied: Boolean,
    isDraggingSource: Boolean,
    onCardPositioned: (String, CardRootBounds) -> Unit,
    dragHandlers: RankCardDragHandlers,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleTie: () -> Unit,
    onExclude: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val currentBucketIndex by rememberUpdatedState(bucketIndex)
    val currentHandlers by rememberUpdatedState(dragHandlers)
    val canTie = itemIndexInBucket > 0 || bucketIndex > 0
    val isTiedWithAbove = itemIndexInBucket > 0
    val cardA11yActions =
        rankCardA11yActions(
            canMoveUp = bucketSize > 1 || bucketIndex > 0,
            canMoveDown = bucketSize > 1 || bucketIndex + 1 < totalBuckets,
            canTie = canTie,
            isTiedWithAbove = isTiedWithAbove,
            canExclude = canExclude,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onToggleTie = onToggleTie,
            onExclude = onExclude,
        )
    BoxWithConstraints(
        modifier =
            modifier
                .rankCardChrome(isFocused, isDraggingSource, isCompactTied, colors)
                .reportCardBounds(candidate.entryId, onCardPositioned)
                .longPressCardDrag(candidate, { currentBucketIndex }, { currentHandlers })
                .semantics { customActions = cardA11yActions }
                .testTag("rank_candidate_$flatIndex")
    ) {
        val stackControls = (canTie || canExclude) && (isCompactTied || maxWidth < 340.dp)
        RankCandidateCardBody(
            candidate = candidate,
            isFocused = isFocused,
            isCompactTied = isCompactTied,
            stackControls = stackControls,
            dragHandle = {
                RankDragGripHandle(
                    entryId = candidate.entryId,
                    onDragStart = { currentHandlers.onStart(candidate, currentBucketIndex) },
                    onDrag = { currentHandlers.onDrag(it) },
                    onDragEnd = { currentHandlers.onEnd() },
                    onDragCancel = { currentHandlers.onCancel() },
                )
            },
            trailingControls = {
                RankCardTrailingControls(
                    title = candidate.title,
                    flatIndex = flatIndex,
                    canTie = canTie,
                    isTiedWithAbove = isTiedWithAbove,
                    canExclude = canExclude,
                    onToggleTie = onToggleTie,
                    onExclude = onExclude,
                )
            },
        )
    }
}

@Composable
private fun RankCandidateCardBody(
    candidate: RankClusterCandidate,
    isFocused: Boolean,
    isCompactTied: Boolean,
    stackControls: Boolean,
    dragHandle: @Composable () -> Unit,
    trailingControls: @Composable () -> Unit,
) {
    val gap = if (isCompactTied) 7.dp else 9.dp
    val thumbSize = if (isCompactTied) 38.dp else 44.dp
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            dragHandle()
            RankCoverThumb(candidate.thumbnailUrl, candidate.title, thumbSize)
            RankCandidateTextBlock(candidate, isFocused, isCompactTied, Modifier.weight(1f))
            if (!stackControls) {
                trailingControls()
            }
        }
        if (stackControls) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                trailingControls()
            }
        }
    }
}

@Composable
private fun RankCardTrailingControls(
    title: String,
    flatIndex: Int,
    canTie: Boolean,
    isTiedWithAbove: Boolean,
    canExclude: Boolean,
    onToggleTie: () -> Unit,
    onExclude: () -> Unit,
) {
    if (!canTie && !canExclude) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canTie) {
            RankQuickTieButton(isTiedWithAbove, title, onToggleTie, "rank_tie_toggle_$flatIndex")
        }
        if (canExclude) {
            RankQuickExcludeButton(
                title = title,
                onClick = onExclude,
                testTag = "rank_exclude_$flatIndex",
            )
        }
    }
}

private fun Modifier.rankCardChrome(
    isFocused: Boolean,
    isDraggingSource: Boolean,
    isCompactTied: Boolean,
    colors: RocheColors,
): Modifier {
    val shape = RoundedCornerShape(11.dp)
    val borderColor =
        when {
            isDraggingSource -> colors.accentBlue
            isFocused -> colors.accentBlue.copy(alpha = 0.65f)
            else -> colors.border
        }
    val startPad = if (isCompactTied) 5.dp else 7.dp
    val endPad = if (isCompactTied) 7.dp else 9.dp
    return alpha(if (isDraggingSource) 0.25f else 1f)
        .clip(shape)
        .background(colors.background)
        .border(1.dp, borderColor, shape)
        .focusLeftAccentBar(enabled = isFocused && !isDraggingSource, color = colors.accentBlue)
        .padding(start = startPad, end = endPad, top = 9.dp, bottom = 9.dp)
}

@Suppress("LongParameterList")
private fun rankCardA11yActions(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canTie: Boolean,
    isTiedWithAbove: Boolean,
    canExclude: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleTie: () -> Unit,
    onExclude: () -> Unit,
): List<CustomAccessibilityAction> = buildList {
    if (canMoveUp) {
        add(
            CustomAccessibilityAction("Move up") {
                onMoveUp()
                true
            }
        )
    }
    if (canMoveDown) {
        add(
            CustomAccessibilityAction("Move down") {
                onMoveDown()
                true
            }
        )
    }
    if (canTie) {
        val label = if (isTiedWithAbove) "Untie" else "Tie with above"
        add(
            CustomAccessibilityAction(label) {
                onToggleTie()
                true
            }
        )
    }
    if (canExclude) {
        add(
            CustomAccessibilityAction("Exclude from comparison") {
                onExclude()
                true
            }
        )
    }
}

private fun Modifier.focusLeftAccentBar(enabled: Boolean, color: Color): Modifier =
    if (enabled) {
        drawBehind {
            drawRect(
                color = color,
                topLeft = Offset.Zero,
                size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
            )
        }
    } else {
        this
    }

private fun Modifier.reportCardBounds(
    entryId: String,
    onCardPositioned: (String, CardRootBounds) -> Unit,
): Modifier = onGloballyPositioned { coords ->
    val pos = coords.positionInRoot()
    onCardPositioned(entryId, CardRootBounds(pos.x, pos.y, coords.size.width, coords.size.height))
}

private fun Modifier.longPressCardDrag(
    candidate: RankClusterCandidate,
    bucketIndexProvider: () -> Int,
    handlersProvider: () -> RankCardDragHandlers,
): Modifier =
    pointerInput(candidate.entryId) {
        detectDragGesturesAfterLongPress(
            onDragStart = { handlersProvider().onStart(candidate, bucketIndexProvider()) },
            onDrag = { change, amount ->
                change.consume()
                handlersProvider().onDrag(amount)
            },
            onDragEnd = { handlersProvider().onEnd() },
            onDragCancel = { handlersProvider().onCancel() },
        )
    }

@Composable
private fun RankDragGripHandle(
    entryId: String,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val curStart by rememberUpdatedState(onDragStart)
    val curDrag by rememberUpdatedState(onDrag)
    val curEnd by rememberUpdatedState(onDragEnd)
    val curCancel by rememberUpdatedState(onDragCancel)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.minimumInteractiveComponentSize()
                .pointerInput(entryId) {
                    detectDragGestures(
                        onDragStart = { curStart() },
                        onDrag = { change, amount ->
                            change.consume()
                            curDrag(amount)
                        },
                        onDragEnd = { curEnd() },
                        onDragCancel = { curCancel() },
                    )
                }
                .clearAndSetSemantics {},
    ) {
        Canvas(modifier = Modifier.size(width = 10.dp, height = 16.dp)) {
            val r = 1.25.dp.toPx()
            val xs = listOf(size.width * 0.25f, size.width * 0.75f)
            val ys = listOf(size.height * 0.2f, size.height * 0.5f, size.height * 0.8f)
            val dotColor = colors.textMuted.copy(alpha = 0.7f)
            for (y in ys) {
                for (x in xs) {
                    drawCircle(color = dotColor, radius = r, center = Offset(x, y))
                }
            }
        }
    }
}

@Composable
private fun RankCandidateTextBlock(
    candidate: RankClusterCandidate,
    isFocused: Boolean,
    isCompactTied: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val titleSize = if (isCompactTied) 13.5.sp else 14.5.sp
    val titleLineHeight = if (isCompactTied) 17.sp else 19.sp
    val titleMaxLines = 2
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = candidate.title,
                color = colors.textPrimary,
                fontFamily = currentHeadlineFontFamily(),
                fontSize = titleSize,
                lineHeight = titleLineHeight,
                fontWeight = FontWeight.Normal,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isFocused) {
                PlacingPillBadge()
            }
        }
        if (candidate.subtitle.isNotBlank()) {
            Text(
                text = candidate.subtitle,
                color = colors.textSecondary,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlacingPillBadge() {
    val colors = LocalRocheColors.current
    Box(
        modifier =
            Modifier.clip(RoundedCornerShape(999.dp))
                .background(colors.accentBlue.copy(alpha = 0.16f))
                .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text = "Placing",
            color = colors.accentBlue,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
        )
    }
}

@Composable
private fun RankQuickTieButton(
    isTied: Boolean,
    title: String,
    onClick: () -> Unit,
    testTag: String,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(8.dp)
    val bg = if (isTied) colors.accentEcru.copy(alpha = 0.16f) else Color.Transparent
    val border = if (isTied) colors.accentEcru.copy(alpha = 0.55f) else Color.Transparent
    val tint = if (isTied) colors.accentEcru else colors.textMuted
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.minimumInteractiveComponentSize()
                .clip(shape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = if (isTied) "Untie $title" else "Tie $title with above"
                }
                .testTag(testTag),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(34.dp).clip(shape).background(bg).border(1.dp, border, shape),
        ) {
            Text(
                text = "=",
                color = tint,
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun RankQuickExcludeButton(
    title: String,
    onClick: () -> Unit,
    testTag: String,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.minimumInteractiveComponentSize()
                .clip(shape)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = "Exclude $title from comparison"
                }
                .testTag(testTag),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(32.dp).clip(shape),
        ) {
            Text(
                text = "×",
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun FloatingDraggedRankCard(
    drag: ActiveRankDragState,
    boardOrigin: Offset,
    isFocused: Boolean,
) {
    val colors = LocalRocheColors.current
    val density = LocalDensity.current
    val cardWidthDp = with(density) { drag.widthPx.toDp() }
    val shape = RoundedCornerShape(11.dp)
    Row(
        modifier =
            Modifier.zIndex(10f)
                .width(cardWidthDp)
                .graphicsLayer {
                    translationX = drag.startLeftInRoot - boardOrigin.x + drag.deltaX
                    translationY = drag.startTopInRoot - boardOrigin.y + drag.deltaY
                    scaleX = 1.02f
                    scaleY = 1.02f
                    shadowElevation = 20f
                    alpha = 0.96f
                }
                .clip(shape)
                .background(colors.surfaceElevated)
                .border(1.5.dp, colors.accentBlue, shape)
                .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        RankCoverThumb(
            thumbnailUrl = drag.candidate.thumbnailUrl,
            fallbackLabel = drag.candidate.title,
            size = 44.dp,
        )
        RankCandidateTextBlock(
            candidate = drag.candidate,
            isFocused = isFocused,
            isCompactTied = false,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RankCoverThumb(
    thumbnailUrl: String?,
    fallbackLabel: String,
    size: Dp,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(7.dp)
    val bitmap = rememberEntryThumbnailBitmap(primaryKey = null, fallbackKey = thumbnailUrl)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(size)
                .clip(shape)
                .background(colors.surfaceElevated)
                .border(1.dp, colors.border, shape),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = fallbackLabel.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "▪",
                color = colors.textMuted,
                fontFamily = currentHeadlineFontFamily(),
                fontSize = 15.sp,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun DropInsertionBar(modifier: Modifier = Modifier) {
    val colors = LocalRocheColors.current
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 38.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.accentBlue)
    )
}

@Composable
private fun DropTieHintBadge(modifier: Modifier = Modifier) {
    val colors = LocalRocheColors.current
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(999.dp))
                .background(colors.surface)
                .border(1.dp, colors.accentEcru, RoundedCornerShape(999.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = "= Drop to tie",
            color = colors.accentEcru,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun ClusterRankFooter(
    board: RankBoardState,
    canUndo: Boolean,
    horizontalPad: Dp,
    onToggleTieAll: () -> Unit,
    onAction: (MainAction) -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(start = horizontalPad, end = horizontalPad, top = 12.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RankTieAllButton(active = board.isAllTied, onClick = onToggleTieAll)
            if (canUndo) {
                HeaderActionButton(
                    label = "Undo",
                    horizontalPadding = 10.dp,
                    onClick = { onAction(MainAction.UndoLastRankRound) },
                    modifier = Modifier.testTag("rank_undo_btn"),
                )
            }
            HeaderActionButton(
                label = "Skip",
                horizontalPadding = 10.dp,
                onClick = {
                    onAction(
                        MainAction.SkipRankCluster(
                            activeEntryIds = board.rows().map { it.candidate.entryId },
                            manualExcludedIds = board.manuallyExcludedIds,
                        )
                    )
                },
                modifier = Modifier.testTag("rank_skip_btn"),
            )
        }
        HeaderActionButton(
            label = "Submit",
            style = RocheButtonStyle.PRIMARY,
            horizontalPadding = 14.dp,
            onClick = {
                onAction(
                    MainAction.SubmitRankCluster(
                        ordering = board.toOrderingGroups(),
                        excludedEntryIds = board.manuallyExcludedIds,
                    )
                )
            },
            modifier = Modifier.testTag("rank_submit_btn"),
        )
    }
}

@Composable
private fun RankTieAllButton(active: Boolean, onClick: () -> Unit) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(9.dp)
    val bg = if (active) colors.accentEcru.copy(alpha = 0.16f) else Color.Transparent
    val border = if (active) colors.accentEcru.copy(alpha = 0.65f) else colors.border
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.heightIn(min = 38.dp)
                .clip(shape)
                .background(bg)
                .border(1.dp, border, shape)
                .clickable(onClick = onClick)
                .semantics { role = Role.Button }
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .testTag("rank_tie_all_btn"),
    ) {
        Text(
            text = "= Tie",
            color = if (active) colors.accentEcru else colors.textSecondary,
            fontSize = 12.sp,
        )
    }
}

private fun Modifier.dashedRoundBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val px = radius.toPx()
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(px, px),
        style =
            Stroke(
                width = 1.2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f)),
            ),
    )
}

internal fun computeDisplayGroupRanks(
    orderedItems: List<RankClusterCandidate>,
    tiedWithAboveIds: Set<String>,
): List<Int> = RankBoardState.fromCandidates(orderedItems, tiedWithAboveIds).displayRanks()

internal fun buildOrderingGroups(
    orderedItems: List<RankClusterCandidate>,
    tiedWithAboveIds: Set<String>,
): List<List<String>> =
    RankBoardState.fromCandidates(orderedItems, tiedWithAboveIds).toOrderingGroups()

private val RANK_WIDE_BREAKPOINT = 600.dp

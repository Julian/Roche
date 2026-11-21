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
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
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
import com.grayvines.roche.data.catalog.CatalogSnapshot
import com.grayvines.roche.data.catalog.RankSettlementSummary
import com.grayvines.roche.data.catalog.RankedCatalogEntry
import com.grayvines.roche.ranking.RankBoardState
import com.grayvines.roche.ranking.RankClusterCandidate
import com.grayvines.roche.ranking.RankDropTarget
import com.grayvines.roche.ranking.RankDropZone
import com.grayvines.roche.ranking.resolveRankDropTarget
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheSurfaceCard
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

@Composable
internal fun TierBadgePill(
    tier: Char,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val badgeColor = resolveTierColor(tier, colors)
    val shape = RoundedCornerShape(5.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .clip(shape)
                .background(badgeColor.copy(alpha = 0.16f))
                .border(1.dp, badgeColor.copy(alpha = 0.55f), shape)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = tier.toString(),
            color = badgeColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

internal fun resolveTierColor(tier: Char, colors: RocheColors): Color =
    when (tier.uppercaseChar()) {
        'S' -> colors.tierSGold
        'A' -> colors.accentVerdigris
        'B' -> colors.accentBlue
        'C' -> colors.accentEcru
        else -> colors.accentIndianRed
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FocusedRankBottomSheet(
    session: ActiveRankSessionUiState,
    onAction: (MainAction) -> Unit,
    catalog: CatalogSnapshot = CatalogSnapshot(),
) {
    val colors = LocalRocheColors.current
    ModalBottomSheet(
        onDismissRequest = { onAction(MainAction.CloseRankSession) },
        containerColor = colors.surface,
    ) {
        val settlement = session.settlement
        if (settlement != null) {
            RankSettlementSheetBody(
                session = session,
                settlement = settlement,
                catalog = catalog,
                onAction = onAction,
            )
        } else {
            ActiveClusterRankBody(
                session = session,
                onAction = onAction,
            )
        }
    }
}

@Composable
private fun RankSettlementSheetBody(
    session: ActiveRankSessionUiState,
    settlement: RankSettlementSummary,
    catalog: CatalogSnapshot,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val entry = remember(settlement.entryId, catalog) { catalog.entriesById[settlement.entryId] }
    val neighbors =
        remember(session.collectionIdOrSlug, settlement.entryId, catalog) {
            resolveSettlementNeighbors(session.collectionIdOrSlug, settlement.entryId, catalog)
        }
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
            subtitle = entry?.subtitleLine.orEmpty(),
            thumbnailUrl = entry?.thumbnailUrl,
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
            canKeepComparing = settlement.totalCount >= 2,
            onKeepComparing = {
                onAction(MainAction.StartRankingSession(session.collectionIdOrSlug, null))
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
    canKeepComparing: Boolean,
    onKeepComparing: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canKeepComparing) {
            HeaderActionButton(
                label = "Keep Comparing",
                onClick = onKeepComparing,
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
            ArrayList(state.buckets.map { bucket -> ArrayList(bucket.map { it.entryId }) })
        },
        restore = { savedBuckets ->
            val byId = candidates.associateBy { it.entryId }
            val restored =
                savedBuckets
                    .map { bucketIds -> bucketIds.mapNotNull { byId[it] } }
                    .filter { it.isNotEmpty() }
            val restoredIds = restored.flatten().map { it.entryId }.toSet()
            if (restoredIds == byId.keys && restored.isNotEmpty()) {
                RankBoardState(restored)
            } else {
                RankBoardState.fromCandidates(candidates)
            }
        },
    )

@Composable
private fun ActiveClusterRankBody(
    session: ActiveRankSessionUiState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val clusterKey =
        remember(session.roundIndex, session.cluster) { session.cluster.map { it.entryId } }
    val boardSaver = remember(session.cluster) { rankBoardStateSaver(session.cluster) }
    var board by
        rememberSaveable(session.roundIndex, clusterKey, stateSaver = boardSaver) {
            mutableStateOf(RankBoardState.fromCandidates(session.cluster))
        }
    var activeDrag by
        remember(session.roundIndex, clusterKey) { mutableStateOf<ActiveRankDragState?>(null) }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth().testTag("focused_rank_sheet")) {
        val isWide = maxWidth >= RANK_WIDE_BREAKPOINT
        val hPad = if (isWide) 20.dp else 16.dp
        Column(modifier = Modifier.fillMaxWidth()) {
            ClusterRankHeader(
                session = session,
                horizontalPad = hPad,
                onClose = { onAction(MainAction.CloseRankSession) },
            )
            HorizontalDivider(color = colors.border, thickness = 1.dp)
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .verticalScroll(rememberScrollState(), enabled = activeDrag == null)
                        .padding(horizontal = hPad, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ClusterRankProgressAndHint(session = session)
                DraggableRankBoard(
                    board = board,
                    focusEntryId = session.focusEntryId,
                    isWide = isWide,
                    activeDrag = activeDrag,
                    onActiveDragChange = { activeDrag = it },
                    onBoardChange = { board = it },
                )
            }
            HorizontalDivider(color = colors.border, thickness = 1.dp)
            ClusterRankFooter(
                board = board,
                horizontalPad = hPad,
                onToggleTieAll = { board = board.toggleTieAll() },
                onAction = onAction,
            )
        }
    }
}

@Composable
private fun ClusterRankHeader(
    session: ActiveRankSessionUiState,
    horizontalPad: Dp,
    onClose: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val heading =
        if (session.scopeCollectionId != null) {
            "Order next up to later"
        } else {
            "Order best to worst"
        }
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
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClose)
                    .semantics { role = Role.Button }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                text = "Done",
                color = colors.textSecondary,
                fontSize = 13.5.sp,
            )
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
            "Round ${session.roundIndex + 1} of $FOCUSED_ROUND_CAP"
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
        val fraction =
            ((session.roundIndex + 1).toFloat() / FOCUSED_ROUND_CAP.toFloat()).coerceIn(0.15f, 1f)
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
        text = "Drag cards to reorder — drop onto another card to tie.",
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
                    onActiveDragChange(
                        ActiveRankDragState(
                            candidate = candidate,
                            sourceBucketIndex = bIdx,
                            startLeftInRoot = bounds.left,
                            startTopInRoot = bounds.top,
                            widthPx = bounds.widthPx,
                            heightPx = bounds.heightPx,
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
    val orderedBounds = board.buckets.indices.mapNotNull { idx -> bucketBounds[idx] }
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
) {
    val colors = LocalRocheColors.current
    val isTiedBucket = bucket.size > 1
    val isDropTie = dropZone == RankDropZone.TIE
    val showDashed = isTiedBucket || isDropTie
    Column(
        modifier =
            Modifier.fillMaxWidth().onGloballyPositioned { coords ->
                val top = coords.positionInRoot().y
                onBucketPositioned(bucketIndex, top, top + coords.size.height)
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (dropZone == RankDropZone.BEFORE) DropInsertionBar()
        if (isDropTie) DropTieHintBadge()
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
                focusEntryId,
                isWide,
                draggedEntryId,
                onCardPositioned,
                dragHandlers,
                onMoveUp,
                onMoveDown,
                onToggleTie,
                Modifier.weight(1f),
            )
        }
        if (dropZone == RankDropZone.AFTER) DropInsertionBar()
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
            horizontal = if (showDashed) 6.dp else 0.dp,
            vertical = if (showDashed) 7.dp else 0.dp,
        )
}

@Composable
private fun RankBucketCardsArea(
    bucketIndex: Int,
    startFlatIndex: Int,
    bucket: List<RankClusterCandidate>,
    totalBuckets: Int,
    focusEntryId: String?,
    isWide: Boolean,
    draggedEntryId: String?,
    onCardPositioned: (String, CardRootBounds) -> Unit,
    dragHandlers: RankCardDragHandlers,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onToggleTie: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isWide && bucket.size > 1) {
        SideBySideBucketCards(
            bucketIndex,
            startFlatIndex,
            bucket,
            totalBuckets,
            focusEntryId,
            draggedEntryId,
            onCardPositioned,
            dragHandlers,
            onMoveUp,
            onMoveDown,
            onToggleTie,
            modifier,
        )
    } else {
        StackedBucketCards(
            bucketIndex,
            startFlatIndex,
            bucket,
            totalBuckets,
            focusEntryId,
            draggedEntryId,
            onCardPositioned,
            dragHandlers,
            onMoveUp,
            onMoveDown,
            onToggleTie,
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
    focusEntryId: String?,
    draggedEntryId: String?,
    onCardPositioned: (String, CardRootBounds) -> Unit,
    dragHandlers: RankCardDragHandlers,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onToggleTie: (String) -> Unit,
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
                    RankCandidateCard(
                        candidate,
                        bucketIndex,
                        iIdx,
                        startFlatIndex + iIdx,
                        bucket.size,
                        totalBuckets,
                        candidate.entryId == focusEntryId,
                        true,
                        candidate.entryId == draggedEntryId,
                        onCardPositioned,
                        dragHandlers,
                        { onMoveUp(candidate.entryId) },
                        { onMoveDown(candidate.entryId) },
                        { onToggleTie(candidate.entryId) },
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
    focusEntryId: String?,
    draggedEntryId: String?,
    onCardPositioned: (String, CardRootBounds) -> Unit,
    dragHandlers: RankCardDragHandlers,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onToggleTie: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        bucket.forEachIndexed { iIdx, candidate ->
            RankCandidateCard(
                candidate,
                bucketIndex,
                iIdx,
                startFlatIndex + iIdx,
                bucket.size,
                totalBuckets,
                candidate.entryId == focusEntryId,
                false,
                candidate.entryId == draggedEntryId,
                onCardPositioned,
                dragHandlers,
                { onMoveUp(candidate.entryId) },
                { onMoveDown(candidate.entryId) },
                { onToggleTie(candidate.entryId) },
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
    isCompactTied: Boolean,
    isDraggingSource: Boolean,
    onCardPositioned: (String, CardRootBounds) -> Unit,
    dragHandlers: RankCardDragHandlers,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleTie: () -> Unit,
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
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onToggleTie = onToggleTie,
        )
    Row(
        modifier =
            modifier
                .rankCardChrome(isFocused, isDraggingSource, isCompactTied, colors)
                .reportCardBounds(candidate.entryId, onCardPositioned)
                .longPressCardDrag(candidate, { currentBucketIndex }, { currentHandlers })
                .semantics { customActions = cardA11yActions }
                .testTag("rank_candidate_$flatIndex"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (isCompactTied) 7.dp else 9.dp),
    ) {
        RankDragGripHandle(
            entryId = candidate.entryId,
            onDragStart = { currentHandlers.onStart(candidate, currentBucketIndex) },
            onDrag = { currentHandlers.onDrag(it) },
            onDragEnd = { currentHandlers.onEnd() },
            onDragCancel = { currentHandlers.onCancel() },
        )
        RankCoverThumb(candidate.thumbnailUrl, candidate.title, if (isCompactTied) 38.dp else 44.dp)
        RankCandidateTextBlock(candidate, isFocused, isCompactTied, Modifier.weight(1f))
        if (canTie) {
            RankQuickTieButton(
                isTiedWithAbove,
                candidate.title,
                onToggleTie,
                "rank_tie_toggle_$flatIndex",
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
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleTie: () -> Unit,
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
    val titleMaxLines = if (isCompactTied) 2 else 1
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
private fun DropInsertionBar() {
    val colors = LocalRocheColors.current
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .padding(start = 38.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.accentBlue)
    )
}

@Composable
private fun DropTieHintBadge() {
    val colors = LocalRocheColors.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier =
                Modifier.clip(RoundedCornerShape(999.dp))
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
}

@Composable
private fun ClusterRankFooter(
    board: RankBoardState,
    horizontalPad: Dp,
    onToggleTieAll: () -> Unit,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val tieAllActive = board.isAllTied
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(start = horizontalPad, end = horizontalPad, top = 12.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val tieShape = RoundedCornerShape(9.dp)
            val tieBg =
                if (tieAllActive) colors.accentEcru.copy(alpha = 0.16f) else Color.Transparent
            val tieBorder =
                if (tieAllActive) colors.accentEcru.copy(alpha = 0.65f) else colors.border
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.heightIn(min = 38.dp)
                        .clip(tieShape)
                        .background(tieBg)
                        .border(1.dp, tieBorder, tieShape)
                        .clickable(onClick = onToggleTieAll)
                        .semantics { role = Role.Button }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("rank_tie_all_btn"),
            ) {
                Text(
                    text = "= Tie All",
                    color = if (tieAllActive) colors.accentEcru else colors.textSecondary,
                    fontSize = 12.5.sp,
                )
            }
            HeaderActionButton(
                label = "Skip",
                onClick = { onAction(MainAction.SkipRankCluster) },
                modifier = Modifier.testTag("rank_skip_btn"),
            )
        }
        HeaderActionButton(
            label = "Submit Order",
            style = RocheButtonStyle.PRIMARY,
            onClick = { onAction(MainAction.SubmitRankCluster(board.toOrderingGroups())) },
            modifier = Modifier.testTag("rank_submit_btn"),
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

private fun resolveSettlementNeighbors(
    collectionIdOrSlug: String,
    focusEntryId: String,
    catalog: CatalogSnapshot,
): List<RankedCatalogEntry> {
    val colId =
        catalog.lists
            .firstOrNull {
                it.collectionId == collectionIdOrSlug || it.slug == collectionIdOrSlug
            }
            ?.collectionId
            ?: catalog.wantQueues
                .firstOrNull {
                    it.collectionId == collectionIdOrSlug || it.slug == collectionIdOrSlug
                }
                ?.collectionId
            ?: collectionIdOrSlug
    val entries = catalog.entriesByCollectionId[colId].orEmpty()
    val idx = entries.indexOfFirst { it.entry.id == focusEntryId }
    if (idx < 0 || entries.size < 2) return emptyList()
    val start = (idx - 1).coerceAtLeast(0)
    val end = (idx + 1).coerceAtMost(entries.lastIndex)
    return entries.subList(start, end + 1)
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
private const val FOCUSED_ROUND_CAP = 8

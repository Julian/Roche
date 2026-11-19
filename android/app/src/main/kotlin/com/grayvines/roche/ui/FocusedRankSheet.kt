@file:Suppress("TooManyFunctions", "MagicNumber")

package com.grayvines.roche.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.RankClusterCandidate
import com.grayvines.roche.data.RankSettlementSummary
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.RocheColors

@Suppress("DataClassContainsFunctions")
internal data class RankBoardState(val buckets: List<List<RankClusterCandidate>>) {
    val isEmpty: Boolean
        get() = buckets.isEmpty()

    val size: Int
        get() = buckets.sumOf { it.size }

    val isAllTied: Boolean
        get() = buckets.size == 1 && size >= 2

    fun rows(): List<RankBoardRowState> {
        val result = ArrayList<RankBoardRowState>(size)
        var flatIdx = 0
        val totalBuckets = buckets.size
        buckets.forEachIndexed { bIdx, bucket ->
            val displayRank = flatIdx + 1
            val bSize = bucket.size
            bucket.forEachIndexed { iIdx, candidate ->
                result.add(
                    RankBoardRowState(
                        candidate = candidate,
                        flatIndex = flatIdx,
                        displayRank = displayRank,
                        isTiedWithAbove = iIdx > 0,
                        canMoveUp = bSize > 1 || bIdx > 0,
                        canMoveDown = bSize > 1 || bIdx + 1 < totalBuckets,
                    )
                )
                flatIdx++
            }
        }
        return result
    }

    fun displayRanks(): List<Int> = rows().map { it.displayRank }

    fun toOrderingGroups(): List<List<String>> =
        buckets.filter { it.isNotEmpty() }.map { bucket -> bucket.map { it.entryId } }

    fun moveUp(entryId: String): RankBoardState {
        val (bIdx, iIdx) = findCoords(entryId) ?: return this
        val next = buckets.map { it.toMutableList() }.toMutableList()
        if (next[bIdx].size > 1) {
            val item = next[bIdx].removeAt(iIdx)
            next.add(bIdx, mutableListOf(item))
        } else if (bIdx > 0) {
            val prev = next[bIdx - 1]
            next[bIdx - 1] = next[bIdx]
            next[bIdx] = prev
        }
        return RankBoardState(next)
    }

    fun moveDown(entryId: String): RankBoardState {
        val (bIdx, iIdx) = findCoords(entryId) ?: return this
        val next = buckets.map { it.toMutableList() }.toMutableList()
        if (next[bIdx].size > 1) {
            val item = next[bIdx].removeAt(iIdx)
            next.add(bIdx + 1, mutableListOf(item))
        } else if (bIdx + 1 < next.size) {
            val below = next[bIdx + 1]
            next[bIdx + 1] = next[bIdx]
            next[bIdx] = below
        }
        return RankBoardState(next)
    }

    fun toggleTieWithAbove(entryId: String): RankBoardState {
        val (bIdx, iIdx) = findCoords(entryId) ?: return this
        val next = buckets.map { it.toMutableList() }.toMutableList()
        if (iIdx > 0) {
            val head = next[bIdx].subList(0, iIdx).toMutableList()
            val tail = next[bIdx].subList(iIdx, next[bIdx].size).toMutableList()
            next[bIdx] = head
            next.add(bIdx + 1, tail)
        } else if (bIdx > 0) {
            val merged = next.removeAt(bIdx)
            next[bIdx - 1].addAll(merged)
        }
        return RankBoardState(next)
    }

    fun tieAll(): RankBoardState {
        val flat = buckets.flatten()
        return if (flat.isEmpty()) RankBoardState(emptyList()) else RankBoardState(listOf(flat))
    }

    private fun findCoords(entryId: String): Pair<Int, Int>? {
        buckets.forEachIndexed { bIdx, bucket ->
            val iIdx = bucket.indexOfFirst { it.entryId == entryId }
            if (iIdx >= 0) return bIdx to iIdx
        }
        return null
    }

    internal companion object {
        fun fromCandidates(
            items: List<RankClusterCandidate>,
            tiedWithAboveIds: Set<String> = emptySet(),
        ): RankBoardState {
            if (items.isEmpty()) return RankBoardState(emptyList())
            val buckets = mutableListOf<MutableList<RankClusterCandidate>>()
            items.forEachIndexed { idx, item ->
                if (idx == 0 || item.entryId !in tiedWithAboveIds) {
                    buckets.add(mutableListOf(item))
                } else {
                    buckets.last().add(item)
                }
            }
            return RankBoardState(buckets)
        }
    }
}

internal data class RankBoardRowState(
    val candidate: RankClusterCandidate,
    val flatIndex: Int,
    val displayRank: Int,
    val isTiedWithAbove: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
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
            fontWeight = FontWeight.Bold,
        )
    }
}

internal fun resolveTierColor(tier: Char, colors: RocheColors): Color =
    when (tier.uppercaseChar()) {
        'S' -> Color(0xFFD4A037)
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
) {
    val colors = LocalRocheColors.current
    ModalBottomSheet(
        onDismissRequest = { onAction(MainAction.CloseRankSession) },
        containerColor = colors.surface,
    ) {
        val settlement = session.settlement
        if (settlement != null) {
            RankSettlementSheetBody(
                settlement = settlement,
                onDone = { onAction(MainAction.CloseRankSession) },
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
    settlement: RankSettlementSummary,
    onDone: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .testTag("rank_settlement_card"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "RANKING SETTLED · ${settlement.collectionTitle.uppercase()}",
            color = colors.accentVerdigris,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = settlement.title,
            color = colors.textPrimary,
            fontFamily = FontFamily.Serif,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        SettlementSummaryCard(settlement = settlement)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            HeaderActionButton(
                label = "Done",
                style = RocheButtonStyle.PRIMARY,
                onClick = onDone,
                modifier = Modifier.testTag("rank_settlement_done_btn"),
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
    }
}

@Composable
private fun SettlementSummaryCard(settlement: RankSettlementSummary) {
    val colors = LocalRocheColors.current
    RocheSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "#${settlement.rank} of ${settlement.totalCount}",
                    color = colors.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Placed in ${settlement.collectionTitle}",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                )
            }
            if (settlement.tier != null) {
                TierBadgePill(tier = settlement.tier)
            }
        }
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
    val rows = remember(board) { board.rows() }

    Column(
        modifier =
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .testTag("focused_rank_sheet"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ClusterRankHeader(session = session, onClose = { onAction(MainAction.CloseRankSession) })
        ClusterRankCandidateList(
            rows = rows,
            focusEntryId = session.focusEntryId,
            onToggleTie = { entryId -> board = board.toggleTieWithAbove(entryId) },
            onMoveUp = { entryId -> board = board.moveUp(entryId) },
            onMoveDown = { entryId -> board = board.moveDown(entryId) },
        )
        HorizontalDivider(color = colors.border, thickness = 1.dp)
        ClusterRankFooter(
            board = board,
            onAction = onAction,
        )
        Spacer(modifier = Modifier.height(14.dp))
    }
}

@Composable
private fun ClusterRankHeader(
    session: ActiveRankSessionUiState,
    onClose: () -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text =
                    if (session.scopeCollectionId != null) {
                        "PRIORITIZE · ${session.collectionTitle.uppercase()}"
                    } else {
                        "COMPARE · ${session.collectionTitle.uppercase()}"
                    },
                color = colors.accentVerdigris,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Order best to worst (tap = to tie)",
                color = colors.textPrimary,
                fontFamily = FontFamily.Serif,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        HeaderActionButton(label = "Done", onClick = onClose)
    }
}

@Composable
private fun ClusterRankCandidateList(
    rows: List<RankBoardRowState>,
    focusEntryId: String?,
    onToggleTie: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
) {
    rows.forEach { row ->
        if (row.flatIndex > 0) {
            TieConnectorToggle(
                isTied = row.isTiedWithAbove,
                onToggle = { onToggleTie(row.candidate.entryId) },
                testTag = "rank_tie_toggle_${row.flatIndex}",
            )
        }
        ClusterCandidateCard(
            row = row,
            isFocused = row.candidate.entryId == focusEntryId,
            onMoveUp = { onMoveUp(row.candidate.entryId) },
            onMoveDown = { onMoveDown(row.candidate.entryId) },
        )
    }
}

@Composable
private fun ClusterRankFooter(
    board: RankBoardState,
    onAction: (MainAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HeaderActionButton(
                label = "Tie All",
                onClick = {
                    onAction(MainAction.SubmitRankCluster(board.tieAll().toOrderingGroups()))
                },
                modifier = Modifier.testTag("rank_tie_all_btn"),
            )
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

@Composable
private fun TieConnectorToggle(
    isTied: Boolean,
    onToggle: () -> Unit,
    testTag: String,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(999.dp)
    val bg = if (isTied) colors.accentEcru.copy(alpha = 0.20f) else Color.Transparent
    val border = if (isTied) colors.accentEcru else colors.border
    val textColor = if (isTied) colors.accentEcru else colors.textMuted

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.minimumInteractiveComponentSize()
                    .clip(shape)
                    .background(bg)
                    .border(1.dp, border, shape)
                    .clickable(onClick = onToggle)
                    .semantics { role = Role.Button }
                    .padding(horizontal = 10.dp, vertical = 2.dp)
                    .testTag(testTag),
        ) {
            Text(
                text = if (isTied) "= Tied with above" else "= Tap to tie",
                color = textColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ClusterCandidateCard(
    row: RankBoardRowState,
    isFocused: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(10.dp)
    val borderColor = if (isFocused) colors.accentBlue else colors.border
    val candidate = row.candidate
    val idx = row.flatIndex
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(shape)
                .background(colors.surfaceElevated)
                .border(1.dp, borderColor, shape)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("rank_candidate_$idx"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "#${row.displayRank}",
            color = if (isFocused) colors.accentBlue else colors.accentEcru,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        ClusterCandidateMetaColumn(candidate = candidate, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            SmallReorderPill(
                "↑",
                "Move ${candidate.title} up",
                row.canMoveUp,
                onMoveUp,
                "rank_up_$idx",
            )
            SmallReorderPill(
                "↓",
                "Move ${candidate.title} down",
                row.canMoveDown,
                onMoveDown,
                "rank_down_$idx",
            )
        }
    }
}

@Composable
private fun ClusterCandidateMetaColumn(
    candidate: RankClusterCandidate,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    Column(modifier = modifier) {
        Text(
            text = candidate.title,
            color = colors.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (candidate.subtitle.isNotBlank()) {
            Text(
                text = candidate.subtitle,
                color = colors.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SmallReorderPill(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.minimumInteractiveComponentSize()
                .let { mod -> if (enabled) mod.clickable(onClick = onClick) else mod }
                .semantics {
                    role = Role.Button
                    contentDescription = description
                    if (!enabled) {
                        disabled()
                    }
                }
                .testTag(testTag),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(30.dp)
                    .clip(shape)
                    .background(colors.surface)
                    .border(1.dp, colors.border, shape),
        ) {
            Text(
                text = label,
                color = if (enabled) colors.textPrimary else colors.textMuted.copy(alpha = 0.4f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
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

@file:Suppress("TooManyFunctions", "MagicNumber")

package com.grayvines.roche.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.ConsumptionStatus
import com.grayvines.roche.data.DailyAlbumSnapshot
import com.grayvines.roche.data.EntryKind
import com.grayvines.roche.data.RankClusterCandidate
import com.grayvines.roche.data.RankSettlementSummary
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.SettingToggleRow
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.RocheColors

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
internal fun AlbumNoteModalSheet(
    album: DailyAlbumSnapshot?,
    mode: AlbumNoteDialogMode,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    var notes by rememberSaveable(album?.entryId, mode) { mutableStateOf("") }
    val isAbandon = mode == AlbumNoteDialogMode.ABANDON

    ModalBottomSheet(
        onDismissRequest = { onAction(MainAction.CloseAlbumNoteDialog) },
        containerColor = colors.surface,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .testTag("album_note_modal_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AlbumNoteSheetHeader(album = album, isAbandon = isAbandon)
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Diary notes (optional)") },
                placeholder = { Text("Standout tracks, impressions…") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().testTag("album_notes_input"),
            )
            AlbumNoteSheetFooter(isAbandon = isAbandon, notes = notes, onAction = onAction)
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@Composable
private fun AlbumNoteSheetHeader(
    album: DailyAlbumSnapshot?,
    isAbandon: Boolean,
) {
    val colors = LocalRocheColors.current
    Text(
        text = if (isAbandon) "Abandon 1001 Album" else "Finish & Rank Album",
        color = colors.textPrimary,
        fontFamily = FontFamily.Serif,
        fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold,
    )
    if (album != null) {
        Text(
            text = "${album.name} — ${album.artist}",
            color = colors.textSecondary,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
    Text(
        text =
            if (isAbandon) {
                "Records this album as abandoned in your Diary and draws your next 1001 Album."
            } else {
                "Logs this album as finished, settles its ranking against your other albums, and draws your next 1001 Album."
            },
        color = colors.textMuted,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    )
}

@Composable
private fun AlbumNoteSheetFooter(
    isAbandon: Boolean,
    notes: String,
    onAction: (MainAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderActionButton(
            label = "Cancel",
            onClick = { onAction(MainAction.CloseAlbumNoteDialog) },
        )
        Spacer(modifier = Modifier.size(8.dp))
        HeaderActionButton(
            label = if (isAbandon) "Abandon & Draw Next" else "Finish & Rank",
            style = RocheButtonStyle.PRIMARY,
            onClick = {
                val action =
                    if (isAbandon) {
                        MainAction.ConfirmAbandon1001Album(notes)
                    } else {
                        MainAction.ConfirmFinish1001Album(notes)
                    }
                onAction(action)
            },
            modifier = Modifier.testTag("album_confirm_action_btn"),
        )
    }
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

@Composable
private fun ActiveClusterRankBody(
    session: ActiveRankSessionUiState,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val clusterKey =
        remember(session.roundIndex, session.cluster) { session.cluster.map { it.entryId } }
    var orderedItems by remember(clusterKey) { mutableStateOf(session.cluster) }
    var tiedWithAboveIds by remember(clusterKey) { mutableStateOf(emptySet<String>()) }
    val rankNumbers =
        remember(orderedItems, tiedWithAboveIds) {
            computeDisplayGroupRanks(orderedItems, tiedWithAboveIds)
        }

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
            orderedItems = orderedItems,
            tiedWithAboveIds = tiedWithAboveIds,
            rankNumbers = rankNumbers,
            focusEntryId = session.focusEntryId,
            onToggleTie = { entryId, wasTied ->
                tiedWithAboveIds =
                    if (wasTied) tiedWithAboveIds - entryId else tiedWithAboveIds + entryId
            },
            onSwap = { idxA, idxB ->
                val swapped = orderedItems.toMutableList()
                val itemA = swapped[idxA]
                val itemB = swapped[idxB]
                swapped[idxA] = itemB
                swapped[idxB] = itemA
                orderedItems = swapped
                tiedWithAboveIds = tiedWithAboveIds - itemA.entryId - itemB.entryId
            },
        )
        HorizontalDivider(color = colors.border, thickness = 1.dp)
        ClusterRankFooter(
            orderedItems = orderedItems,
            tiedWithAboveIds = tiedWithAboveIds,
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

@Suppress("LongParameterList")
@Composable
private fun ClusterRankCandidateList(
    orderedItems: List<RankClusterCandidate>,
    tiedWithAboveIds: Set<String>,
    rankNumbers: List<Int>,
    focusEntryId: String?,
    onToggleTie: (String, Boolean) -> Unit,
    onSwap: (Int, Int) -> Unit,
) {
    orderedItems.forEachIndexed { idx, candidate ->
        if (idx > 0) {
            val isTied = candidate.entryId in tiedWithAboveIds
            TieConnectorToggle(
                isTied = isTied,
                onToggle = { onToggleTie(candidate.entryId, isTied) },
                testTag = "rank_tie_toggle_$idx",
            )
        }
        ClusterCandidateCard(
            candidate = candidate,
            displayRank = rankNumbers[idx],
            isFocused = candidate.entryId == focusEntryId,
            canMoveUp = idx > 0,
            canMoveDown = idx < orderedItems.lastIndex,
            onMoveUp = { onSwap(idx - 1, idx) },
            onMoveDown = { onSwap(idx, idx + 1) },
            index = idx,
        )
    }
}

@Composable
private fun ClusterRankFooter(
    orderedItems: List<RankClusterCandidate>,
    tiedWithAboveIds: Set<String>,
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
                    val allBelowFirst = orderedItems.drop(1).map { it.entryId }.toSet()
                    onAction(
                        MainAction.SubmitRankCluster(
                            buildOrderingGroups(orderedItems, allBelowFirst)
                        )
                    )
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
            onClick = {
                onAction(
                    MainAction.SubmitRankCluster(
                        buildOrderingGroups(orderedItems, tiedWithAboveIds)
                    )
                )
            },
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
            modifier =
                Modifier.clip(shape)
                    .background(bg)
                    .border(1.dp, border, shape)
                    .clickable(onClick = onToggle)
                    .semantics { role = Role.Button }
                    .padding(horizontal = 10.dp, vertical = 2.dp)
                    .testTag(testTag)
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
@Suppress("LongParameterList")
private fun ClusterCandidateCard(
    candidate: RankClusterCandidate,
    displayRank: Int,
    isFocused: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    index: Int,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(10.dp)
    val borderColor = if (isFocused) colors.accentBlue else colors.border
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(shape)
                .background(colors.surfaceElevated)
                .border(1.dp, borderColor, shape)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("rank_candidate_$index"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "#$displayRank",
            color = if (isFocused) colors.accentBlue else colors.accentEcru,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Column(modifier = Modifier.weight(1f)) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallReorderPill(
                label = "↑",
                enabled = canMoveUp,
                onClick = onMoveUp,
                testTag = "rank_up_$index",
            )
            SmallReorderPill(
                label = "↓",
                enabled = canMoveDown,
                onClick = onMoveDown,
                testTag = "rank_down_$index",
            )
        }
    }
}

@Composable
private fun SmallReorderPill(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(30.dp)
                .clip(shape)
                .background(colors.surface)
                .border(1.dp, colors.border, shape)
                .let { mod -> if (enabled) mod.clickable(onClick = onClick) else mod }
                .testTag(testTag),
    ) {
        Text(
            text = label,
            color = if (enabled) colors.textPrimary else colors.textMuted.copy(alpha = 0.4f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

internal fun computeDisplayGroupRanks(
    orderedItems: List<RankClusterCandidate>,
    tiedWithAboveIds: Set<String>,
): List<Int> {
    if (orderedItems.isEmpty()) return emptyList()
    val ranks = ArrayList<Int>(orderedItems.size)
    var currentRank = 1
    orderedItems.forEachIndexed { index, item ->
        if (index > 0 && item.entryId !in tiedWithAboveIds) {
            currentRank = index + 1
        }
        ranks.add(currentRank)
    }
    return ranks
}

internal fun buildOrderingGroups(
    orderedItems: List<RankClusterCandidate>,
    tiedWithAboveIds: Set<String>,
): List<List<String>> {
    val groups = mutableListOf<MutableList<String>>()
    orderedItems.forEachIndexed { idx, item ->
        if (idx == 0 || item.entryId !in tiedWithAboveIds) {
            groups.add(mutableListOf(item.entryId))
        } else {
            groups.last().add(item.entryId)
        }
    }
    return groups
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickLogBottomSheet(
    defaultToWant: Boolean,
    targetCollectionId: String? = null,
    targetCollectionTitle: String? = null,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    ModalBottomSheet(
        onDismissRequest = { onAction(MainAction.CloseQuickLogSheet) },
        containerColor = colors.surface,
    ) {
        QuickLogSheetBody(
            defaultToWant = defaultToWant,
            targetCollectionId = targetCollectionId,
            targetCollectionTitle = targetCollectionTitle,
            onAction = onAction,
        )
    }
}

@Composable
private fun QuickLogSheetBody(
    defaultToWant: Boolean,
    targetCollectionId: String?,
    targetCollectionTitle: String?,
    onAction: (MainAction) -> Unit,
) {
    var addToWant by rememberSaveable(defaultToWant) { mutableStateOf(defaultToWant) }
    var kind by rememberSaveable { mutableStateOf(EntryKind.MOVIE) }
    var status by rememberSaveable { mutableStateOf(ConsumptionStatus.CONSUMED) }
    var title by rememberSaveable { mutableStateOf("") }
    var creators by rememberSaveable { mutableStateOf("") }
    var yearText by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var rankAfterLog by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier =
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .testTag("quick_log_sheet"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QuickLogSelectorsSection(
            addToWant = addToWant,
            targetCollectionTitle = targetCollectionTitle,
            selectedKind = kind,
            selectedStatus = status,
            onSelectWantMode = { addToWant = it },
            onSelectKind = { kind = it },
            onSelectStatus = { status = it },
        )
        QuickLogMetadataFields(
            title = title,
            creators = creators,
            yearText = yearText,
            onTitleChange = { title = it },
            onCreatorsChange = { creators = it },
            onYearChange = { yearText = it.filter(Char::isDigit).take(4) },
        )
        if (!addToWant) {
            QuickLogDiaryOptions(notes, kind, status, rankAfterLog, { notes = it }) {
                rankAfterLog = it
            }
        }
        val submitAction =
            buildQuickLogAction(
                kind,
                title,
                creators,
                yearText,
                status,
                notes,
                addToWant,
                rankAfterLog,
                targetCollectionId,
            )
        QuickLogFooterRow(addToWant, { onAction(MainAction.CloseQuickLogSheet) }) {
            onAction(submitAction)
        }
        Spacer(modifier = Modifier.height(14.dp))
    }
}

@Suppress("LongParameterList")
private fun buildQuickLogAction(
    kind: EntryKind,
    title: String,
    creators: String,
    yearText: String,
    status: ConsumptionStatus,
    notes: String,
    addToWant: Boolean,
    rankAfterLog: Boolean,
    targetCollectionId: String?,
): MainAction =
    MainAction.SubmitQuickLog(
        kind = kind,
        title = title,
        creatorsCsv = creators,
        releaseYear = yearText.toIntOrNull(),
        status = status,
        notes = notes,
        addToWant = addToWant,
        rankAfterLog = rankAfterLog && status == ConsumptionStatus.CONSUMED,
        targetCollectionId = targetCollectionId,
    )

@Suppress("LongParameterList")
@Composable
private fun QuickLogSelectorsSection(
    addToWant: Boolean,
    targetCollectionTitle: String?,
    selectedKind: EntryKind,
    selectedStatus: ConsumptionStatus,
    onSelectWantMode: (Boolean) -> Unit,
    onSelectKind: (EntryKind) -> Unit,
    onSelectStatus: (ConsumptionStatus) -> Unit,
) {
    val colors = LocalRocheColors.current
    val loggableKinds = remember { QUICK_LOG_KINDS }
    val heading =
        when {
            addToWant -> "Add to Want Queue"
            !targetCollectionTitle.isNullOrBlank() -> "Log & Add to $targetCollectionTitle"
            else -> "Log to Diary"
        }
    Text(
        text = heading,
        color = colors.textPrimary,
        fontFamily = FontFamily.Serif,
        fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChipPill(
            label = "Log Diary",
            selected = !addToWant,
            onClick = { onSelectWantMode(false) },
        )
        FilterChipPill(
            label = "Want Queue",
            selected = addToWant,
            onClick = { onSelectWantMode(true) },
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        loggableKinds.forEach { kind ->
            FilterChipPill(
                label = kind.displayLabel,
                selected = selectedKind == kind,
                onClick = { onSelectKind(kind) },
            )
        }
    }
    if (!addToWant) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ConsumptionStatus.entries.forEach { status ->
                FilterChipPill(
                    label = status.displayLabel,
                    selected = selectedStatus == status,
                    onClick = { onSelectStatus(status) },
                )
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun QuickLogMetadataFields(
    title: String,
    creators: String,
    yearText: String,
    onTitleChange: (String) -> Unit,
    onCreatorsChange: (String) -> Unit,
    onYearChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = title,
        onValueChange = onTitleChange,
        label = { Text("Title") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("quick_log_title_input"),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = creators,
            onValueChange = onCreatorsChange,
            label = { Text("Creator / Artist") },
            singleLine = true,
            modifier = Modifier.weight(1.4f).testTag("quick_log_creator_input"),
        )
        OutlinedTextField(
            value = yearText,
            onValueChange = onYearChange,
            label = { Text("Year") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(0.6f),
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun QuickLogDiaryOptions(
    notes: String,
    selectedKind: EntryKind,
    selectedStatus: ConsumptionStatus,
    rankAfterLog: Boolean,
    onNotesChange: (String) -> Unit,
    onRankToggle: (Boolean) -> Unit,
) {
    OutlinedTextField(
        value = notes,
        onValueChange = onNotesChange,
        label = { Text("Notes (optional)") },
        minLines = 2,
        maxLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
    if (selectedStatus == ConsumptionStatus.CONSUMED) {
        SettingToggleRow(
            title = "Rank immediately after logging",
            subtitle = "Compare against entries in ${selectedKind.systemListTitle}",
            checked = rankAfterLog,
            onCheckedChange = onRankToggle,
        )
    }
}

@Composable
private fun QuickLogFooterRow(
    addToWant: Boolean,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        HeaderActionButton(label = "Cancel", onClick = onCancel)
        Spacer(modifier = Modifier.size(8.dp))
        HeaderActionButton(
            label = if (addToWant) "Add to Want" else "Save Log",
            style = RocheButtonStyle.PRIMARY,
            onClick = onSubmit,
            modifier = Modifier.testTag("quick_log_submit_btn"),
        )
    }
}

private val QUICK_LOG_KINDS =
    listOf(
        EntryKind.MOVIE,
        EntryKind.ALBUM,
        EntryKind.BOOK,
        EntryKind.TV,
        EntryKind.GAME,
        EntryKind.SONG,
        EntryKind.ARTICLE,
        EntryKind.PAPER,
        EntryKind.VIDEO,
    )

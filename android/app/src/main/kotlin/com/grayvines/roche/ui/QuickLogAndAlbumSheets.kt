@file:Suppress("TooManyFunctions")

package com.grayvines.roche.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.CollectionGeneratorState
import com.grayvines.roche.data.ConsumptionStatus
import com.grayvines.roche.data.EntryKind
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.SettingToggleRow
import com.grayvines.roche.ui.theme.LocalRocheColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectionDrawNoteModalSheet(
    dialog: DrawNoteDialogState,
    generator: CollectionGeneratorState?,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val draw = generator?.currentDraw
    var notes by
        rememberSaveable(dialog.collectionId, draw?.entryId, dialog.mode) { mutableStateOf("") }
    val isAbandon = dialog.mode == DrawNoteDialogMode.ABANDON

    ModalBottomSheet(
        onDismissRequest = { onAction(MainAction.CloseDrawNoteDialog) },
        containerColor = colors.surface,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .testTag("draw_note_modal_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DrawNoteSheetHeader(generator = generator, isAbandon = isAbandon)
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Diary notes (optional)") },
                placeholder = { Text("Standout moments, impressions…") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().testTag("draw_notes_input"),
            )
            DrawNoteSheetFooter(
                collectionId = dialog.collectionId,
                isAbandon = isAbandon,
                notes = notes,
                onAction = onAction,
            )
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@Composable
private fun DrawNoteSheetHeader(
    generator: CollectionGeneratorState?,
    isAbandon: Boolean,
) {
    val colors = LocalRocheColors.current
    val draw = generator?.currentDraw
    val listTitle = generator?.collectionTitle ?: "List"
    Text(
        text = if (isAbandon) "Abandon Draw" else "Finish & Rank",
        color = colors.textPrimary,
        fontFamily = FontFamily.Serif,
        fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold,
    )
    if (draw != null) {
        val subtitle =
            if (draw.creator.isNotBlank()) "${draw.title} — ${draw.creator}" else draw.title
        Text(
            text = subtitle,
            color = colors.textSecondary,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
    Text(
        text =
            if (isAbandon) {
                "Records this entry as abandoned in your Diary and draws your next item from $listTitle."
            } else {
                "Logs this entry as finished, settles its ranking, and draws your next item from $listTitle."
            },
        color = colors.textMuted,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    )
}

@Composable
private fun DrawNoteSheetFooter(
    collectionId: String,
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
            onClick = { onAction(MainAction.CloseDrawNoteDialog) },
        )
        Spacer(modifier = Modifier.size(8.dp))
        HeaderActionButton(
            label = if (isAbandon) "Abandon & Draw Next" else "Finish & Rank",
            style = RocheButtonStyle.PRIMARY,
            onClick = {
                val action =
                    if (isAbandon) {
                        MainAction.ConfirmAbandonDraw(collectionId, notes)
                    } else {
                        MainAction.ConfirmFinishDraw(collectionId, notes)
                    }
                onAction(action)
            },
            modifier = Modifier.testTag("draw_confirm_action_btn"),
        )
    }
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
                .imePadding()
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
            onYearChange = { yearText = it.filter(Char::isDigit).take(MAX_YEAR_DIGITS) },
        )
        if (!addToWant) {
            QuickLogDiaryOptions(notes, kind, status, rankAfterLog, { notes = it }) {
                rankAfterLog = it
            }
        }
        val submitAction =
            buildQuickLogAction(
                kind = kind,
                title = title,
                creators = creators,
                yearText = yearText,
                status = status,
                notes = notes,
                addToWant = addToWant,
                rankAfterLog = rankAfterLog,
                targetCollectionId = targetCollectionId,
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
            modifier = Modifier.weight(CREATOR_FIELD_WEIGHT).testTag("quick_log_creator_input"),
        )
        OutlinedTextField(
            value = yearText,
            onValueChange = onYearChange,
            label = { Text("Year") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(YEAR_FIELD_WEIGHT),
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

private const val MAX_YEAR_DIGITS = 4
private const val CREATOR_FIELD_WEIGHT = 1.4f
private const val YEAR_FIELD_WEIGHT = 0.6f

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

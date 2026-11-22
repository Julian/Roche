@file:Suppress("TooManyFunctions")

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.catalog.CollectionGeneratorState
import com.grayvines.roche.data.catalog.ConsumptionStatus
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.ui.components.RocheStripTab
import com.grayvines.roche.ui.components.SettingToggleRow
import com.grayvines.roche.ui.components.SheetActionFooter
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily

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
                canConfirm = draw != null,
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
        text = if (isAbandon) "Skip Draw" else "Finish & Rank",
        color = colors.textPrimary,
        fontFamily = currentHeadlineFontFamily(),
        fontSize = 20.sp,
        fontWeight = FontWeight.Normal,
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
                "Records this entry as skipped in your Diary and draws your next item from $listTitle."
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
    canConfirm: Boolean,
    onAction: (MainAction) -> Unit,
) {
    SheetActionFooter(
        confirmLabel = if (isAbandon) "Skip & Draw Next" else "Finish & Rank",
        confirmEnabled = canConfirm,
        confirmTestTag = "draw_confirm_action_btn",
        onCancel = { onAction(MainAction.CloseDrawNoteDialog) },
        onConfirm = {
            val action =
                if (isAbandon) {
                    MainAction.ConfirmAbandonDraw(collectionId, notes)
                } else {
                    MainAction.ConfirmFinishDraw(collectionId, notes)
                }
            onAction(action)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList")
@Composable
internal fun QuickLogBottomSheet(
    defaultToWant: Boolean,
    targetCollectionId: String? = null,
    targetCollectionTitle: String? = null,
    prefillKind: EntryKind? = null,
    prefillTitle: String = "",
    prefillCreators: String = "",
    prefillReleaseYear: Int? = null,
    existingEntryId: String? = null,
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
            prefillKind = prefillKind,
            prefillTitle = prefillTitle,
            prefillCreators = prefillCreators,
            prefillReleaseYear = prefillReleaseYear,
            existingEntryId = existingEntryId,
            onAction = onAction,
        )
    }
}

private const val DEFAULT_LIST_LABEL = "List"

private fun formatAddToListLabel(targetCollectionTitle: String?): String =
    "Add to ${targetCollectionTitle ?: DEFAULT_LIST_LABEL}"

@Suppress("LongMethod", "LongParameterList")
@Composable
private fun QuickLogSheetBody(
    defaultToWant: Boolean,
    targetCollectionId: String?,
    targetCollectionTitle: String?,
    prefillKind: EntryKind?,
    prefillTitle: String,
    prefillCreators: String,
    prefillReleaseYear: Int?,
    existingEntryId: String?,
    onAction: (MainAction) -> Unit,
) {
    val hasTargetList = !targetCollectionId.isNullOrBlank()
    var addToListOnly by
        rememberSaveable(defaultToWant, hasTargetList) {
            mutableStateOf(hasTargetList && !defaultToWant)
        }
    var addToWant by rememberSaveable(defaultToWant) { mutableStateOf(defaultToWant) }
    var kind by rememberSaveable(prefillKind) { mutableStateOf(prefillKind ?: EntryKind.ALBUM) }
    var status by rememberSaveable { mutableStateOf(ConsumptionStatus.CONSUMED) }
    var title by rememberSaveable(prefillTitle) { mutableStateOf(prefillTitle) }
    var creators by rememberSaveable(prefillCreators) { mutableStateOf(prefillCreators) }
    var yearText by
        rememberSaveable(prefillReleaseYear) {
            mutableStateOf(prefillReleaseYear?.takeIf { it > 0 }?.toString().orEmpty())
        }
    var notes by rememberSaveable { mutableStateOf("") }
    var rankAfterLog by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier =
            Modifier.fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .testTag("quick_log_sheet"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        QuickLogSelectorsSection(
            addToListOnly = addToListOnly,
            addToWant = addToWant,
            hasTargetList = hasTargetList,
            targetCollectionTitle = targetCollectionTitle,
            selectedKind = kind,
            selectedStatus = status,
            onSelectMode = { listOnly, want ->
                addToListOnly = listOnly
                addToWant = want
            },
            onSelectKind = { kind = it },
            onSelectStatus = { status = it },
        )
        QuickLogMetadataFields(
            selectedKind = kind,
            title = title,
            creators = creators,
            yearText = yearText,
            onTitleChange = { title = it },
            onCreatorsChange = { creators = it },
            onYearChange = { yearText = it.filter(Char::isDigit).take(MAX_YEAR_DIGITS) },
        )
        if (!addToWant && !addToListOnly) {
            QuickLogDiaryOptions(
                notes,
                targetCollectionTitle ?: kind.systemListTitle,
                status,
                rankAfterLog,
                { notes = it },
            ) {
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
                addToListOnly = addToListOnly,
                rankAfterLog = rankAfterLog,
                targetCollectionId = targetCollectionId,
                existingEntryId = existingEntryId,
            )
        val submitLabel =
            when {
                addToListOnly -> formatAddToListLabel(targetCollectionTitle)
                addToWant -> "Add to Want"
                else -> "Save Log"
            }
        SheetActionFooter(
            confirmLabel = submitLabel,
            confirmEnabled = title.isNotBlank(),
            confirmTestTag = "quick_log_submit_btn",
            onCancel = { onAction(MainAction.CloseQuickLogSheet) },
            onConfirm = { onAction(submitAction) },
        )
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
    addToListOnly: Boolean,
    rankAfterLog: Boolean,
    targetCollectionId: String?,
    existingEntryId: String?,
): MainAction =
    MainAction.SubmitQuickLog(
        kind = kind,
        title = title,
        creatorsCsv = creators,
        releaseYear = yearText.toIntOrNull(),
        status = status,
        notes = notes,
        addToWant = addToWant,
        rankAfterLog = rankAfterLog && !addToListOnly && status == ConsumptionStatus.CONSUMED,
        targetCollectionId = targetCollectionId,
        addToListOnly = addToListOnly,
        existingEntryId = existingEntryId,
    )

@Suppress("LongParameterList")
@Composable
private fun QuickLogSelectorsSection(
    addToListOnly: Boolean,
    addToWant: Boolean,
    hasTargetList: Boolean,
    targetCollectionTitle: String?,
    selectedKind: EntryKind,
    selectedStatus: ConsumptionStatus,
    onSelectMode: (listOnly: Boolean, want: Boolean) -> Unit,
    onSelectKind: (EntryKind) -> Unit,
    onSelectStatus: (ConsumptionStatus) -> Unit,
) {
    val colors = LocalRocheColors.current
    val loggableKinds = remember { EntryKind.quickLogKinds }
    val heading =
        when {
            addToListOnly -> formatAddToListLabel(targetCollectionTitle)
            addToWant && !targetCollectionTitle.isNullOrBlank() ->
                "Want & Add to $targetCollectionTitle"
            addToWant -> "Add to Want Queue"
            !targetCollectionTitle.isNullOrBlank() -> "Log & Add to $targetCollectionTitle"
            else -> "Log to Diary"
        }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = heading,
            color = colors.textPrimary,
            fontFamily = currentHeadlineFontFamily(),
            fontSize = 20.sp,
            fontWeight = FontWeight.Normal,
        )
        QuickLogModeChipsRow(
            hasTargetList = hasTargetList,
            addToListOnly = addToListOnly,
            addToWant = addToWant,
            onSelectMode = onSelectMode,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            loggableKinds.forEach { kind ->
                RocheStripTab(
                    label = kind.displayLabel,
                    selected = selectedKind == kind,
                    onClick = { onSelectKind(kind) },
                )
            }
        }
        if (!addToWant && !addToListOnly) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ConsumptionStatus.entries.forEach { status ->
                    RocheStripTab(
                        label = status.displayLabel,
                        selected = selectedStatus == status,
                        onClick = { onSelectStatus(status) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickLogModeChipsRow(
    hasTargetList: Boolean,
    addToListOnly: Boolean,
    addToWant: Boolean,
    onSelectMode: (listOnly: Boolean, want: Boolean) -> Unit,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(shape)
                .background(colors.background)
                .border(1.dp, colors.border, shape)
                .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (hasTargetList) {
            QuickLogModeSegment(
                label = "List Only",
                selected = addToListOnly,
                onClick = { onSelectMode(true, false) },
                modifier = Modifier.weight(1f),
            )
            QuickLogModeSegment(
                label = "Log & Add",
                selected = !addToListOnly && !addToWant,
                onClick = { onSelectMode(false, false) },
                modifier = Modifier.weight(1f),
            )
            QuickLogModeSegment(
                label = "Want & Add",
                selected = !addToListOnly && addToWant,
                onClick = { onSelectMode(false, true) },
                modifier = Modifier.weight(1f),
            )
        } else {
            QuickLogModeSegment(
                label = "Log Diary",
                selected = !addToWant,
                onClick = { onSelectMode(false, false) },
                modifier = Modifier.weight(1f),
            )
            QuickLogModeSegment(
                label = "Want Queue",
                selected = addToWant,
                onClick = { onSelectMode(false, true) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun QuickLogModeSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(7.dp)
    val bg = if (selected) colors.accentBlue.copy(alpha = 0.16f) else Color.Transparent
    val textColor = if (selected) colors.accentBlue else colors.textSecondary
    val weight = if (selected) FontWeight.Medium else FontWeight.Normal
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .clip(shape)
                .background(bg)
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.RadioButton
                    this.selected = selected
                }
                .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = weight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun creatorFieldLabelForKind(kind: EntryKind): String =
    when (kind) {
        EntryKind.MOVIE -> "Director"
        EntryKind.BOOK -> "Author"
        EntryKind.GAME -> "Developer / Studio"
        EntryKind.ALBUM,
        EntryKind.ARTIST,
        EntryKind.SONG -> "Artist"
        EntryKind.TV -> "Creator / Showrunner"
        EntryKind.PODCAST,
        EntryKind.PODCAST_EPISODE -> "Host / Show"
        else -> "Creator"
    }

@Suppress("LongParameterList")
@Composable
private fun QuickLogMetadataFields(
    selectedKind: EntryKind,
    title: String,
    creators: String,
    yearText: String,
    onTitleChange: (String) -> Unit,
    onCreatorsChange: (String) -> Unit,
    onYearChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("quick_log_title_input"),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = creators,
                onValueChange = onCreatorsChange,
                label = { Text(creatorFieldLabelForKind(selectedKind)) },
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
}

@Suppress("LongParameterList")
@Composable
private fun QuickLogDiaryOptions(
    notes: String,
    rankTargetTitle: String,
    selectedStatus: ConsumptionStatus,
    rankAfterLog: Boolean,
    onNotesChange: (String) -> Unit,
    onRankToggle: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                subtitle = "Compare against entries in $rankTargetTitle",
                checked = rankAfterLog,
                onCheckedChange = onRankToggle,
            )
        }
    }
}

private const val MAX_YEAR_DIGITS = 4
private const val CREATOR_FIELD_WEIGHT = 1.4f
private const val YEAR_FIELD_WEIGHT = 0.6f

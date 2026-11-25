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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.core.SCHEME_ARXIV
import com.grayvines.roche.core.SCHEME_DOI
import com.grayvines.roche.core.SCHEME_ISBN
import com.grayvines.roche.core.extractArxivIdentifier
import com.grayvines.roche.core.isHttpOrHttpsUrl
import com.grayvines.roche.core.isLocalPdfUrl
import com.grayvines.roche.core.normalizeDoi
import com.grayvines.roche.core.normalizeIsbn
import com.grayvines.roche.core.parsePublicationDateStr
import com.grayvines.roche.data.catalog.CatalogEntryItem
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.UpdateCatalogEntryInput
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEntryBottomSheet(
    entryId: String,
    state: MainUiState,
    onAction: (MainAction) -> Unit,
) {
    val entry = remember(entryId, state) { resolveEditableEntry(entryId, state) } ?: return
    val colors = LocalRocheColors.current
    ModalBottomSheet(
        onDismissRequest = { onAction(MainAction.CloseEditEntrySheet) },
        containerColor = colors.surface,
    ) {
        EditEntrySheetBody(
            entry = entry,
            onDismiss = { onAction(MainAction.CloseEditEntrySheet) },
            onSubmit = { input -> onAction(MainAction.UpdateCatalogEntry(entry.id, input)) },
        )
    }
}

private class EditEntryFormState(
    val title: MutableState<String>,
    val kindWire: MutableState<String>,
    val creators: MutableState<String>,
    val date: MutableState<String>,
    val year: MutableState<String>,
    val initialYear: String,
    val url: MutableState<String>,
    val summary: MutableState<String>,
    val doi: MutableState<String>,
    val arxiv: MutableState<String>,
    val isbn: MutableState<String>,
    val error: MutableState<String?>,
)

@Composable
private fun rememberEditEntryFormState(entry: CatalogEntryItem): EditEntryFormState {
    val initialDate =
        remember(entry.id, entry.publishedAt) { extractIsoDatePrefix(entry.publishedAt) }
    val initialYear =
        remember(entry.id, entry.releaseYear) { entry.releaseYear?.toString().orEmpty() }
    val initialDoi = remember(entry.id, entry.identifiers) { findIdentVal(entry, SCHEME_DOI) }
    val initialArxiv = remember(entry.id, entry.identifiers) { findIdentVal(entry, SCHEME_ARXIV) }
    val initialIsbn = remember(entry.id, entry.identifiers) { findIdentVal(entry, SCHEME_ISBN) }
    return EditEntryFormState(
        title = rememberSaveable(entry.id) { mutableStateOf(entry.title) },
        kindWire = rememberSaveable(entry.id) { mutableStateOf(entry.kind.wireValue) },
        creators = rememberSaveable(entry.id) { mutableStateOf(entry.creators.joinToString(", ")) },
        date = rememberSaveable(entry.id) { mutableStateOf(initialDate) },
        year = rememberSaveable(entry.id) { mutableStateOf(initialYear) },
        initialYear = initialYear,
        url = rememberSaveable(entry.id) { mutableStateOf(entry.url.orEmpty()) },
        summary = rememberSaveable(entry.id) { mutableStateOf(entry.summary.orEmpty()) },
        doi = rememberSaveable(entry.id) { mutableStateOf(initialDoi) },
        arxiv = rememberSaveable(entry.id) { mutableStateOf(initialArxiv) },
        isbn = rememberSaveable(entry.id) { mutableStateOf(initialIsbn) },
        error = rememberSaveable(entry.id) { mutableStateOf<String?>(null) },
    )
}

@Composable
private fun EditEntrySheetBody(
    entry: CatalogEntryItem,
    onDismiss: () -> Unit,
    onSubmit: (UpdateCatalogEntryInput) -> Unit,
) {
    val colors = LocalRocheColors.current
    val form = rememberEditEntryFormState(entry)
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .testTag("edit_entry_bottom_sheet"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Edit Entry Details",
            color = colors.textPrimary,
            fontFamily = currentHeadlineFontFamily(),
            fontSize = 19.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.semantics { heading() },
        )
        EditEntryCoreFields(form)
        EditEntryDateAndUrlFields(form)
        EditEntryIdentifierFields(form)
        form.error.value?.let { message ->
            Text(
                text = message,
                color = colors.accentIndianRed,
                fontSize = 12.sp,
                modifier = Modifier.testTag("edit_entry_error_text"),
            )
        }
        EditEntryActionButtons(
            onDismiss = onDismiss,
            onSave = {
                buildValidatedUpdateInput(form).onSuccess(onSubmit).onFailure { err ->
                    form.error.value = err.message ?: "Invalid input"
                }
            },
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun EditEntryCoreFields(form: EditEntryFormState) {
    OutlinedTextField(
        value = form.title.value,
        onValueChange = {
            form.title.value = it
            form.error.value = null
        },
        label = { Text("Title") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("edit_entry_title_input"),
    )
    EditEntryKindStrip(
        selected = EntryKind.fromWireValue(form.kindWire.value),
        onSelect = { form.kindWire.value = it.wireValue },
    )
    OutlinedTextField(
        value = form.creators.value,
        onValueChange = {
            form.creators.value = it
            form.error.value = null
        },
        label = { Text("Authors / Creators") },
        placeholder = { Text("Comma-separated names") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("edit_entry_creators_input"),
    )
}

@Composable
private fun EditEntryDateAndUrlFields(form: EditEntryFormState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = form.date.value,
            onValueChange = {
                form.date.value = it
                form.error.value = null
            },
            label = { Text("Published Date") },
            placeholder = { Text("YYYY-MM-DD") },
            singleLine = true,
            modifier = Modifier.weight(1.3f).testTag("edit_entry_date_input"),
        )
        OutlinedTextField(
            value = form.year.value,
            onValueChange = {
                form.year.value = it.filter(Char::isDigit).take(YEAR_MAX_DIGITS)
                form.error.value = null
            },
            label = { Text("Year") },
            placeholder = { Text("YYYY") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(0.9f).testTag("edit_entry_year_input"),
        )
    }
    OutlinedTextField(
        value = form.url.value,
        onValueChange = {
            form.url.value = it
            form.error.value = null
        },
        label = { Text("Source URL") },
        placeholder = { Text("https://…") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().testTag("edit_entry_url_input"),
    )
    OutlinedTextField(
        value = form.summary.value,
        onValueChange = {
            form.summary.value = it
            form.error.value = null
        },
        label = { Text("Summary / Abstract") },
        minLines = 2,
        maxLines = 5,
        modifier = Modifier.fillMaxWidth().testTag("edit_entry_summary_input"),
    )
}

@Composable
private fun EditEntryIdentifierFields(form: EditEntryFormState) {
    OutlinedTextField(
        value = form.doi.value,
        onValueChange = {
            form.doi.value = it
            form.error.value = null
        },
        label = { Text("DOI") },
        placeholder = { Text("10.xxxx/…") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("edit_entry_doi_input"),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = form.arxiv.value,
            onValueChange = {
                form.arxiv.value = it
                form.error.value = null
            },
            label = { Text("arXiv ID") },
            placeholder = { Text("2610.12345") },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("edit_entry_arxiv_input"),
        )
        OutlinedTextField(
            value = form.isbn.value,
            onValueChange = {
                form.isbn.value = it
                form.error.value = null
            },
            label = { Text("ISBN") },
            placeholder = { Text("978…") },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("edit_entry_isbn_input"),
        )
    }
}

@Composable
private fun EditEntryActionButtons(
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderActionButton(
            label = "Cancel",
            style = RocheButtonStyle.SECONDARY,
            onClick = onDismiss,
            modifier = Modifier.testTag("edit_entry_cancel_btn"),
        )
        HeaderActionButton(
            label = "Save Details",
            style = RocheButtonStyle.PRIMARY,
            onClick = onSave,
            modifier = Modifier.testTag("edit_entry_save_btn"),
        )
    }
}

@Composable
private fun EditEntryKindStrip(
    selected: EntryKind,
    onSelect: (EntryKind) -> Unit,
) {
    val colors = LocalRocheColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "KIND",
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            letterSpacing = 0.8.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EntryKind.systemDynamicKinds.forEach { kind ->
                FilterChipPill(
                    label = kind.displayLabel,
                    selected = kind == selected,
                    onClick = { onSelect(kind) },
                    modifier = Modifier.testTag("edit_entry_kind_${kind.wireValue}"),
                )
            }
        }
    }
}

private fun buildValidatedUpdateInput(form: EditEntryFormState): Result<UpdateCatalogEntryInput> =
    runCatching {
        val cleanTitle = form.title.value.trim()
        require(cleanTitle.isNotEmpty()) { "Title cannot be empty" }
        val creators =
            form.creators.value
                .split(',', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        val parsedDate = parseOptionalEditDate(form.date.value)
        val explicitYear = parseOptionalEditYear(form.year.value)
        val parsedYear =
            if (
                parsedDate != null &&
                    parsedDate.publishedAt == null &&
                    form.year.value.trim() == form.initialYear
            ) {
                parsedDate.releaseYear ?: explicitYear
            } else {
                explicitYear ?: parsedDate?.releaseYear
            }
        val cleanUrl = parseOptionalEditUrl(form.url.value)
        val normDoi = parseOptionalIdent(form.doi.value, ::normalizeDoi, "Invalid DOI format")
        val normArxiv =
            parseOptionalIdent(form.arxiv.value, ::extractArxivIdentifier, "Invalid arXiv ID")
        val normIsbn = parseOptionalIdent(form.isbn.value, ::normalizeIsbn, "Invalid ISBN")
        val cleanSummary = form.summary.value.trim()
        UpdateCatalogEntryInput(
            kind = EntryKind.fromWireValue(form.kindWire.value),
            title = cleanTitle,
            creators = creators,
            url = cleanUrl,
            clearUrl = cleanUrl == null,
            publishedAt = parsedDate?.publishedAt,
            clearPublishedAt = parsedDate?.publishedAt == null,
            releaseYear = parsedYear,
            clearReleaseYear = parsedYear == null,
            summary = cleanSummary.takeIf { it.isNotEmpty() },
            clearSummary = cleanSummary.isEmpty(),
            doi = normDoi,
            clearDoi = normDoi == null,
            arxivId = normArxiv,
            clearArxivId = normArxiv == null,
            isbn = normIsbn,
            clearIsbn = normIsbn == null,
        )
    }

private fun parseOptionalEditDate(raw: String): com.grayvines.roche.core.ParsedPublicationDate? {
    val clean = raw.trim()
    if (clean.isEmpty()) return null
    return parsePublicationDateStr(clean)
        ?: throw IllegalArgumentException("Invalid published date (use YYYY-MM-DD)")
}

private fun parseOptionalEditYear(raw: String): Int? {
    val clean = raw.trim()
    if (clean.isEmpty()) return null
    val yr = clean.toIntOrNull()
    require(yr != null && yr in EDIT_YEAR_MIN..EDIT_YEAR_MAX) {
        "Year must be between $EDIT_YEAR_MIN and $EDIT_YEAR_MAX"
    }
    return yr
}

private fun parseOptionalEditUrl(raw: String): String? {
    val clean = raw.trim()
    if (clean.isEmpty()) return null
    require(isHttpOrHttpsUrl(clean) || isLocalPdfUrl(clean)) {
        "URL must start with http://, https://, or local-pdf://"
    }
    return clean
}

private fun parseOptionalIdent(
    raw: String,
    normalizer: (String) -> String?,
    errorMsg: String,
): String? {
    val clean = raw.trim()
    if (clean.isEmpty()) return null
    return normalizer(clean) ?: throw IllegalArgumentException(errorMsg)
}

private fun findIdentVal(entry: CatalogEntryItem, scheme: String): String =
    entry.identifiers.firstOrNull { it.scheme.equals(scheme, ignoreCase = true) }?.value.orEmpty()

private fun resolveEditableEntry(entryId: String, state: MainUiState): CatalogEntryItem? =
    state.editingCatalogEntry?.takeIf { it.id == entryId }
        ?: state.catalogDetail?.entry?.takeIf { it.id == entryId }
        ?: state.activeCollection.entries.firstOrNull { it.entry.id == entryId }?.entry
        ?: state.activeWantQueue.entries.firstOrNull { it.entry.id == entryId }?.entry
        ?: state.starredCollection.entries.firstOrNull { it.entry.id == entryId }?.entry

private fun extractIsoDatePrefix(publishedAt: String?): String {
    val clean = publishedAt?.trim().orEmpty()
    val isIso =
        clean.length >= ISO_DATE_PREFIX_LEN &&
            clean[ISO_YEAR_DASH_IDX] == '-' &&
            clean[ISO_MONTH_DASH_IDX] == '-'
    return if (isIso) clean.substring(0, ISO_DATE_PREFIX_LEN) else ""
}

private const val YEAR_MAX_DIGITS = 4
private const val ISO_YEAR_DASH_IDX = 4
private const val ISO_MONTH_DASH_IDX = 7
private const val ISO_DATE_PREFIX_LEN = 10
private const val EDIT_YEAR_MIN = 1000
private const val EDIT_YEAR_MAX = 2100

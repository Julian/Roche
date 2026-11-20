package com.grayvines.roche.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.core.normalizeTagName
import com.grayvines.roche.data.settings.PLAYBACK_SPEED_PRESETS
import com.grayvines.roche.playback.formatPlaybackSpeedLabel
import com.grayvines.roche.ui.theme.LocalRocheColors
import kotlin.math.abs

private const val SPEED_MATCH_EPSILON = 0.02f

@Composable
internal fun TagChipsEditor(
    headerLabel: String,
    availableTags: List<String>,
    inputPlaceholder: String,
    submitButtonLabel: String,
    onChipClick: (String) -> Unit,
    onSubmitCustomTag: (String) -> Unit,
    modifier: Modifier = Modifier,
    draftKey: Any = Unit,
    chipLabel: (String) -> String = { "#$it" },
    isChipSelected: (String) -> Boolean = { false },
    inputTestTag: String? = null,
    submitTestTag: String? = null,
) {
    val colors = LocalRocheColors.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = headerLabel,
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        if (availableTags.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                availableTags.forEach { tag ->
                    FilterChipPill(
                        label = chipLabel(tag),
                        selected = isChipSelected(tag),
                        onClick = { onChipClick(tag) },
                    )
                }
            }
        }
        CustomTagInputRow(
            draftKey = draftKey,
            inputPlaceholder = inputPlaceholder,
            submitButtonLabel = submitButtonLabel,
            inputTestTag = inputTestTag,
            submitTestTag = submitTestTag,
            onSubmitCustomTag = onSubmitCustomTag,
        )
    }
}

@Composable
private fun CustomTagInputRow(
    draftKey: Any,
    inputPlaceholder: String,
    submitButtonLabel: String,
    inputTestTag: String?,
    submitTestTag: String?,
    onSubmitCustomTag: (String) -> Unit,
) {
    var customTag by rememberSaveable(draftKey) { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fieldMod =
            Modifier.weight(1f).let { base ->
                if (inputTestTag != null) base.testTag(inputTestTag) else base
            }
        val buttonMod = if (submitTestTag != null) Modifier.testTag(submitTestTag) else Modifier
        OutlinedTextField(
            value = customTag,
            onValueChange = { customTag = it },
            placeholder = { Text(inputPlaceholder, fontSize = 12.sp) },
            singleLine = true,
            modifier = fieldMod,
        )
        HeaderActionButton(
            label = submitButtonLabel,
            onClick = {
                val clean = normalizeTagName(customTag)
                if (clean.isNotBlank()) {
                    onSubmitCustomTag(clean)
                    customTag = ""
                }
            },
            modifier = buttonMod,
        )
    }
}

@Composable
internal fun PodcastSpeedStrip(
    selectedSpeed: Float?,
    onSelectSpeed: (Float) -> Unit,
    modifier: Modifier = Modifier,
    includeDefaultChip: Boolean = false,
    onSelectDefault: (() -> Unit)? = null,
    chipTestTagPrefix: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (includeDefaultChip && onSelectDefault != null) {
            FilterChipPill(
                label = "Default",
                selected = selectedSpeed == null,
                onClick = onSelectDefault,
            )
        }
        PLAYBACK_SPEED_PRESETS.forEach { preset ->
            val label = formatPlaybackSpeedLabel(preset)
            val isSelected =
                selectedSpeed != null && abs(selectedSpeed - preset) < SPEED_MATCH_EPSILON
            val chipMod =
                if (chipTestTagPrefix != null) {
                    Modifier.testTag("$chipTestTagPrefix$label")
                } else {
                    Modifier
                }
            FilterChipPill(
                label = label,
                selected = isSelected,
                onClick = { onSelectSpeed(preset) },
                modifier = chipMod,
            )
        }
    }
}

@Composable
internal fun SheetActionFooter(
    confirmLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    cancelLabel: String = "Cancel",
    confirmEnabled: Boolean = true,
    confirmTestTag: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderActionButton(label = cancelLabel, onClick = onCancel)
        Spacer(modifier = Modifier.size(8.dp))
        val confirmMod = if (confirmTestTag != null) Modifier.testTag(confirmTestTag) else Modifier
        HeaderActionButton(
            label = confirmLabel,
            style = RocheButtonStyle.PRIMARY,
            enabled = confirmEnabled,
            onClick = {
                if (confirmEnabled) {
                    onConfirm()
                }
            },
            modifier = confirmMod,
        )
    }
}

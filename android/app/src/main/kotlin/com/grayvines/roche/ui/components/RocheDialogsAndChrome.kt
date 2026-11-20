package com.grayvines.roche.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily

@Composable
internal fun RocheAlertDialog(
    title: String,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val colors = LocalRocheColors.current
    val headlineFamily = currentHeadlineFontFamily()
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        title = {
            Text(
                text = title,
                color = colors.textPrimary,
                fontFamily = headlineFamily,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
            )
        },
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
    )
}

@Suppress("LongParameterList")
internal fun resolveCatalogEntryOpenAction(
    entryId: String,
    externalUrl: String?,
    readableIds: Set<String>,
    onOpenReader: (String) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onFallback: (() -> Unit)? = null,
): (() -> Unit)? {
    val cleanUrl = externalUrl?.takeIf { it.isNotBlank() }
    return when {
        entryId in readableIds -> {
            { onOpenReader(entryId) }
        }
        cleanUrl != null -> {
            { onOpenExternalUrl(cleanUrl) }
        }
        else -> onFallback
    }
}

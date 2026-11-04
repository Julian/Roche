package com.grayvines.roche.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.ui.components.FilingCabinetIcon
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.StarIcon
import com.grayvines.roche.ui.theme.LocalHeadlineFont
import com.grayvines.roche.ui.theme.LocalRocheColors

@Composable
fun ReaderView(
    entry: InboxEntry,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
) {
    BackHandler { onAction(MainAction.CloseReader) }
    val colors = LocalRocheColors.current
    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        ReaderTopToolbar(
            entry = entry,
            onAction = onAction,
            onShare = { onShareEntry(entry) },
        )

        Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(colors.border)) {
            val fraction = entry.readProgress.toFloat().coerceIn(MIN_PROGRESS, 1f)
            Box(
                modifier =
                    Modifier.fillMaxWidth(fraction).height(2.dp).background(colors.accentBlue)
            )
        }

        ReaderArticleBody(entry = entry, onSelectTag = { onAction(MainAction.FilterByTag(it)) })
    }
}

@Composable
private fun ReaderTopToolbar(
    entry: InboxEntry,
    onAction: (MainAction) -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        HeaderActionButton(label = "← Inbox", onClick = { onAction(MainAction.CloseReader) })
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompactStarAndArchiveButtons(
                entry = entry,
                tagPrefix = "reader",
                onToggleStar = { onAction(MainAction.ToggleStar(entry.id)) },
                onToggleArchive = { onAction(MainAction.ToggleArchive(entry)) },
            )
            HeaderActionButton(label = "Share", onClick = onShare)
            HeaderActionButton(
                label = "•••",
                onClick = { onAction(MainAction.OpenContextSheet(entry.id)) },
            )
        }
    }
}

@Composable
private fun CompactStarAndArchiveButtons(
    entry: InboxEntry,
    tagPrefix: String,
    onToggleStar: () -> Unit,
    onToggleArchive: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(7.dp)
    val starTint = if (entry.isFavorite) colors.accentEcru else colors.textSecondary
    val starDesc = if (entry.isFavorite) "Remove Star" else "Star Entry"
    val archiveDesc = if (entry.isArchived) "Unarchive to Inbox" else "Archive Entry"

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier =
                Modifier.size(32.dp)
                    .clip(shape)
                    .background(colors.surfaceElevated)
                    .border(1.dp, colors.border, shape)
                    .clickable(onClick = onToggleStar)
                    .semantics { contentDescription = starDesc }
                    .testTag("${tagPrefix}_star_btn"),
            contentAlignment = Alignment.Center,
        ) {
            StarIcon(tint = starTint, isStarred = entry.isFavorite, size = 16.dp)
        }
        Box(
            modifier =
                Modifier.size(32.dp)
                    .clip(shape)
                    .background(colors.surfaceElevated)
                    .border(1.dp, colors.border, shape)
                    .clickable(onClick = onToggleArchive)
                    .semantics { contentDescription = archiveDesc }
                    .testTag("${tagPrefix}_archive_btn"),
            contentAlignment = Alignment.Center,
        ) {
            FilingCabinetIcon(
                tint = colors.textSecondary,
                isArchived = entry.isArchived,
                size = 16.dp,
            )
        }
    }
}

@Composable
private fun ReaderArticleBody(entry: InboxEntry, onSelectTag: (String) -> Unit) {
    val colors = LocalRocheColors.current
    val headlineFamily =
        if (LocalHeadlineFont.current == HeadlineFont.SERIF) {
            FontFamily.Serif
        } else {
            FontFamily.SansSerif
        }

    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Text(
            text =
                "${entry.feedTitle} · ${entry.contentType.wireValue.uppercase()} · ${entry.readEstimateLabel}",
            color = colors.accentBlue,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = entry.title,
            color = colors.textPrimary,
            fontFamily = headlineFamily,
            fontSize = 23.sp,
            lineHeight = 29.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = formatReaderByline(entry),
            color = colors.textSecondary,
            fontSize = 12.sp,
        )

        if (entry.tags.isNotEmpty()) {
            ReaderTagStrip(tags = entry.tags, onSelectTag = onSelectTag)
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = colors.border, thickness = 1.dp)
        Spacer(modifier = Modifier.height(16.dp))

        val bodyParagraphs =
            (entry.contentText?.takeIf { it.isNotBlank() } ?: entry.summary.orEmpty())
                .split("\n\n")
                .filter { it.isNotBlank() }

        bodyParagraphs.forEach { paragraph ->
            Text(
                text = paragraph.trim(),
                color = colors.textPrimary,
                fontFamily = FontFamily.Serif,
                fontSize = 16.sp,
                lineHeight = 26.sp,
            )
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@Composable
private fun ReaderTagStrip(tags: List<String>, onSelectTag: (String) -> Unit) {
    val colors = LocalRocheColors.current
    Spacer(modifier = Modifier.height(10.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        tags.forEach { tag ->
            Text(
                text = "#$tag",
                color = colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier =
                    Modifier.clip(RoundedCornerShape(5.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(5.dp))
                        .clickable { onSelectTag(tag) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

private fun formatReaderByline(entry: InboxEntry): String = buildString {
    entry.author?.let { append("$it · ") }
    append(entry.publishedAt ?: "Saved")
    if (entry.isCachedOffline) {
        append(" · Cached offline")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryContextBottomSheet(
    entry: InboxEntry,
    availableTags: List<String>,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    val allTags = remember(availableTags, entry.tags) { (availableTags + entry.tags).distinct() }

    ModalBottomSheet(
        onDismissRequest = { onAction(MainAction.CloseContextSheet) },
        containerColor = colors.surface,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .testTag("context_bottom_sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ContextSheetHeaderAndTags(
                entry = entry,
                allTags = allTags,
                onAction = onAction,
            )
            HorizontalDivider(color = colors.border, thickness = 1.dp)
            ContextSheetActionsList(
                entry = entry,
                onAction = onAction,
                onShare = {
                    onAction(MainAction.CloseContextSheet)
                    onShareEntry(entry)
                },
                onOpenExternalUrl = {
                    onAction(MainAction.CloseContextSheet)
                    onOpenExternalUrl(entry.url)
                },
            )
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@Composable
private fun ContextSheetHeaderAndTags(
    entry: InboxEntry,
    allTags: List<String>,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = entry.title,
                color = colors.textPrimary,
                fontFamily = FontFamily.Serif,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "${entry.feedTitle} · ${entry.url}",
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CompactStarAndArchiveButtons(
            entry = entry,
            tagPrefix = "sheet",
            onToggleStar = { onAction(MainAction.ToggleStar(entry.id)) },
            onToggleArchive = { onAction(MainAction.ToggleArchive(entry)) },
        )
    }
    Text(
        text = "TAGS (TAP TO TOGGLE)",
        color = colors.textMuted,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        allTags.forEach { tag ->
            val assigned = entry.tags.any { it.equals(tag, ignoreCase = true) }
            FilterChipPill(
                label = "#$tag",
                selected = assigned,
                onClick = { onAction(MainAction.ToggleTagOnEntry(entry.id, tag)) },
            )
        }
    }
}

@Composable
private fun ContextSheetActionsList(
    entry: InboxEntry,
    onAction: (MainAction) -> Unit,
    onShare: () -> Unit,
    onOpenExternalUrl: () -> Unit,
) {
    val offlineLabel =
        if (entry.isCachedOffline) {
            "Remove Local Offline Copy"
        } else {
            "Download Local Offline Copy"
        }

    ContextActionItem(label = "Share…", onClick = onShare)
    ContextActionItem(
        label = offlineLabel,
        onClick = { onAction(MainAction.ToggleOfflineCache(entry.id)) },
    )
    if (entry.feedId != null) {
        ContextActionItem(
            label = "Filter Inbox by ${entry.feedTitle}",
            onClick = { onAction(MainAction.FilterByFeed(entry.feedId)) },
        )
    }
    ContextActionItem(label = "Open Original URL", onClick = onOpenExternalUrl)
}

@Composable
private fun ContextActionItem(label: String, onClick: () -> Unit) {
    val colors = LocalRocheColors.current
    Text(
        text = label,
        color = colors.textPrimary,
        fontSize = 14.sp,
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp, horizontal = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddUrlOrFeedBottomSheet(
    initialUrl: String,
    onDismiss: () -> Unit,
    onSave: (String, String?, String) -> Unit,
) {
    val colors = LocalRocheColors.current
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var title by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable { mutableStateOf("systems") }

    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotBlank()) {
            url = initialUrl
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Save URL or Subscribe Feed",
                color = colors.textPrimary,
                fontFamily = FontFamily.Serif,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Article, arXiv PDF, YouTube, or RSS URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Optional Title Override") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it },
                label = { Text("Tags (comma-separated)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onSave(url, title.takeIf { it.isNotBlank() }, tags) },
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = colors.accentBlue,
                        contentColor = Color.White,
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save to Roche Inbox")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private const val MIN_PROGRESS = 0.08f

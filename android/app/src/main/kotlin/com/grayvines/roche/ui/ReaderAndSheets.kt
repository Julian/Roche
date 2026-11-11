package com.grayvines.roche.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.normalizeTagName
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.ThemeMode
import com.grayvines.roche.ui.components.FilingCabinetIcon
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheIconActionButton
import com.grayvines.roche.ui.components.SiteFaviconBadge
import com.grayvines.roche.ui.components.StarIcon
import com.grayvines.roche.ui.theme.LocalHeadlineFont
import com.grayvines.roche.ui.theme.LocalReaderFontScale
import com.grayvines.roche.ui.theme.LocalRocheColors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun ReaderView(
    entry: InboxEntry,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    backLabel: String = "← Inbox",
    settings: Settings = Settings(),
) {
    BackHandler { onAction(MainAction.CloseReader) }
    val colors = LocalRocheColors.current
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var showTypographyDrawer by rememberSaveable { mutableStateOf(false) }
    val scrollFraction by
        remember(scrollState, entry.readProgress) {
            derivedStateOf {
                if (scrollState.maxValue > 0) {
                    (scrollState.value.toFloat() / scrollState.maxValue.toFloat()).coerceIn(
                        MIN_PROGRESS,
                        1f,
                    )
                } else {
                    entry.readProgress.toFloat().coerceIn(MIN_PROGRESS, 1f)
                }
            }
        }

    ReaderScrollProgressEffect(
        entry = entry,
        scrollState = scrollState,
        onAction = onAction,
    )

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        ReaderTopToolbar(
            entry = entry,
            backLabel = backLabel,
            showTypographyDrawer = showTypographyDrawer,
            onToggleTypography = { showTypographyDrawer = !showTypographyDrawer },
            onAction = onAction,
            onShare = { onShareEntry(entry) },
        )

        Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(colors.border)) {
            Box(
                modifier =
                    Modifier.fillMaxWidth(scrollFraction).height(2.dp).background(colors.accentBlue)
            )
        }

        if (showTypographyDrawer) {
            ReaderTypographyDrawer(
                entry = entry,
                settings = settings,
                onAction = onAction,
                onJumpToFraction = { fraction ->
                    coroutineScope.launch {
                        val target = (fraction * scrollState.maxValue).roundToInt()
                        scrollState.animateScrollTo(target)
                    }
                },
            )
        }

        ReaderArticleBody(
            entry = entry,
            scrollState = scrollState,
            onSelectTag = { onAction(MainAction.FilterByTag(it)) },
            onOpenExternalUrl = { onOpenExternalUrl(entry.url) },
        )
    }
}

@Composable
internal fun ReaderScrollProgressEffect(
    entry: InboxEntry,
    scrollState: androidx.compose.foundation.ScrollState,
    onAction: (MainAction) -> Unit,
) {
    val bodyLengthBucket = (entry.contentHtml?.length ?: 0) + (entry.contentText?.length ?: 0)
    var restoredBodyBucket by remember(entry.id) { mutableStateOf(-1) }
    var hasUserScrolled by remember(entry.id) { mutableStateOf(false) }

    LaunchedEffect(entry.id, bodyLengthBucket, scrollState.maxValue) {
        if (
            scrollState.maxValue > 0 && !hasUserScrolled && restoredBodyBucket != bodyLengthBucket
        ) {
            restoredBodyBucket = bodyLengthBucket
            val targetScroll =
                (entry.readProgress.coerceIn(0.0, 1.0) * scrollState.maxValue).toInt()
            scrollState.scrollTo(targetScroll)
        }
    }

    LaunchedEffect(
        entry.id,
        scrollState.isScrollInProgress,
        scrollState.maxValue,
    ) {
        if (scrollState.isScrollInProgress) {
            hasUserScrolled = true
        } else if (hasUserScrolled && scrollState.maxValue > 0) {
            val rawProgress =
                (scrollState.value.toDouble() / scrollState.maxValue.toDouble()).coerceIn(0.0, 1.0)
            if (kotlin.math.abs(rawProgress - entry.readProgress) >= READ_PROGRESS_MIN_DELTA) {
                onAction(MainAction.UpdateReadProgress(entry.id, rawProgress))
            }
        }
    }
}

@Composable
private fun ReaderTopToolbar(
    entry: InboxEntry,
    backLabel: String,
    showTypographyDrawer: Boolean,
    onToggleTypography: () -> Unit,
    onAction: (MainAction) -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        HeaderActionButton(
            label = backLabel,
            onClick = { onAction(MainAction.CloseReader) },
            modifier = Modifier.testTag("reader_back_btn"),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HeaderActionButton(
                label = "Aa",
                style =
                    if (showTypographyDrawer) {
                        RocheButtonStyle.PRIMARY
                    } else {
                        RocheButtonStyle.SECONDARY
                    },
                onClick = onToggleTypography,
                modifier = Modifier.testTag("reader_typography_btn"),
            )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderTypographyDrawer(
    entry: InboxEntry,
    settings: Settings,
    onAction: (MainAction) -> Unit,
    onJumpToFraction: (Float) -> Unit,
) {
    val colors = LocalRocheColors.current
    val scale = settings.readerFontScale
    val headings =
        remember(entry.contentHtml, entry.summary, entry.url) {
            val html = entry.contentHtml ?: entry.summary.orEmpty()
            extractPlainTextHeadings(parseHtmlArticleBlocks(rawHtml = html, baseUrl = entry.url))
        }

    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("reader_typography_drawer"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "TEXT SIZE (${(scale * PERCENT_MULTIPLIER).roundToInt()}%)",
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
        )
        ReaderFontScaleControlsRow(currentScale = scale, onAction = onAction)
        ReaderAppearanceQuickToggleRow(settings = settings, onAction = onAction)

        if (headings.isNotEmpty()) {
            ReaderSectionsOutlineStrip(headings = headings, onJumpToFraction = onJumpToFraction)
        }
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
}

@Composable
internal fun ReaderAppearanceQuickToggleRow(
    settings: Settings,
    onAction: (MainAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChipPill(
            label = if (settings.themeMode == ThemeMode.LIGHT) "☀ Paper" else "☾ Dark Ink",
            selected = false,
            onClick = {
                val next =
                    if (settings.themeMode == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK
                onAction(MainAction.SetThemeMode(next))
            },
        )
        FilterChipPill(
            label = if (settings.headlineFont == HeadlineFont.SERIF) "Aa Serif" else "Aa Sans",
            selected = false,
            onClick = {
                val next =
                    if (settings.headlineFont == HeadlineFont.SERIF) {
                        HeadlineFont.SANS
                    } else {
                        HeadlineFont.SERIF
                    }
                onAction(MainAction.SetHeadlineFont(next))
            },
        )
    }
}

@Composable
internal fun ReaderFontScaleControlsRow(
    currentScale: Float,
    onAction: (MainAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderActionButton(
            label = "A−",
            onClick = {
                onAction(
                    MainAction.SetReaderFontScale(
                        (currentScale - READER_SCALE_STEP).coerceAtLeast(
                            Settings.MIN_READER_FONT_SCALE
                        )
                    )
                )
            },
            modifier = Modifier.testTag("reader_font_smaller_btn"),
        )
        READER_FONT_PRESETS.forEach { preset ->
            val pct = (preset * PERCENT_MULTIPLIER).roundToInt()
            FilterChipPill(
                label = "$pct%",
                selected = abs(currentScale - preset) < READER_SCALE_EPSILON,
                onClick = { onAction(MainAction.SetReaderFontScale(preset)) },
            )
        }
        HeaderActionButton(
            label = "A+",
            onClick = {
                onAction(
                    MainAction.SetReaderFontScale(
                        (currentScale + READER_SCALE_STEP).coerceAtMost(
                            Settings.MAX_READER_FONT_SCALE
                        )
                    )
                )
            },
            modifier = Modifier.testTag("reader_font_larger_btn"),
        )
    }
}

@Composable
internal fun ReaderSectionsOutlineStrip(
    headings: List<String>,
    onJumpToFraction: (Float) -> Unit,
) {
    val colors = LocalRocheColors.current
    Text(
        text = "ARTICLE SECTIONS (${headings.size})",
        color = colors.textMuted,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.5.sp,
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        headings.forEachIndexed { index, heading ->
            val fraction =
                if (headings.size <= 1) {
                    0f
                } else {
                    index.toFloat() / headings.size.toFloat()
                }
            FilterChipPill(
                label = "§ ${heading.take(MAX_SECTION_LABEL_CHARS)}",
                selected = false,
                onClick = { onJumpToFraction(fraction) },
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
    val starTint = if (entry.isFavorite) colors.accentEcru else colors.textSecondary
    val starDesc = if (entry.isFavorite) "Remove Star" else "Star Entry"
    val archiveDesc = if (entry.isArchived) "Unarchive to Inbox" else "Archive Entry"

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RocheIconActionButton(
            contentDescription = starDesc,
            onClick = onToggleStar,
            modifier = Modifier.testTag("${tagPrefix}_star_btn"),
        ) {
            StarIcon(tint = starTint, isStarred = entry.isFavorite, size = 16.dp)
        }
        RocheIconActionButton(
            contentDescription = archiveDesc,
            onClick = onToggleArchive,
            modifier = Modifier.testTag("${tagPrefix}_archive_btn"),
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
private fun ReaderArticleBody(
    entry: InboxEntry,
    scrollState: androidx.compose.foundation.ScrollState,
    onSelectTag: (String) -> Unit,
    onOpenExternalUrl: () -> Unit,
) {
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
                .verticalScroll(scrollState)
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
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                entry.tags.forEach { tag ->
                    FilterChipPill(
                        label = "#$tag",
                        selected = false,
                        onClick = { onSelectTag(tag) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = colors.border, thickness = 1.dp)
        Spacer(modifier = Modifier.height(16.dp))

        ReaderBodyContent(entry = entry, onOpenExternalUrl = onOpenExternalUrl)
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
internal fun ReaderBodyContent(entry: InboxEntry, onOpenExternalUrl: () -> Unit = {}) {
    val colors = LocalRocheColors.current
    val scale = LocalReaderFontScale.current
    val htmlBody =
        entry.contentHtml?.takeIf { it.isNotBlank() }
            ?: entry.summary?.takeIf { "<" in it || "&" in it }
    val htmlBlocks =
        remember(htmlBody, entry.url) {
            htmlBody?.let { parseHtmlArticleBlocks(rawHtml = it, baseUrl = entry.url) }.orEmpty()
        }
    if (htmlBlocks.isNotEmpty()) {
        StructuredHtmlArticleContent(blocks = htmlBlocks)
    } else {
        val bodyParagraphs =
            (entry.contentText?.takeIf { it.isNotBlank() } ?: entry.summary.orEmpty())
                .split("\n\n")
                .filter { it.isNotBlank() }

        if (bodyParagraphs.isEmpty()) {
            Text(
                text = "Full article text is not cached locally for this item.",
                color = colors.textMuted,
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(12.dp))
        } else {
            bodyParagraphs.forEach { paragraph ->
                Text(
                    text = paragraph.trim(),
                    color = colors.textPrimary,
                    fontFamily = FontFamily.Serif,
                    fontSize = (16f * scale).sp,
                    lineHeight = (26f * scale).sp,
                )
                Spacer(modifier = Modifier.height(14.dp))
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    HeaderActionButton(label = "Open Original Link ↗", onClick = onOpenExternalUrl)
}

private fun formatReaderByline(entry: InboxEntry): String = buildString {
    entry.author?.let { append("$it · ") }
    append(entry.formattedPublishedLabel)
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
    ContextSheetTagsEditor(
        entry = entry,
        allTags = allTags,
        onAction = onAction,
    )
}

@Composable
internal fun ContextSheetTagsEditor(
    entry: InboxEntry,
    allTags: List<String>,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    var customTag by rememberSaveable { mutableStateOf("") }

    Text(
        text = "TAGS (TAP TO TOGGLE)",
        color = colors.textMuted,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
    )
    if (allTags.isNotEmpty()) {
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = customTag,
            onValueChange = { customTag = it },
            placeholder = { Text("Add new #tag…", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        HeaderActionButton(
            label = "Add Tag",
            onClick = {
                val clean = normalizeTagName(customTag)
                if (clean.isNotBlank()) {
                    onAction(MainAction.ToggleTagOnEntry(entry.id, clean))
                    customTag = ""
                }
            },
        )
    }
}

@Composable
private fun ContextSheetActionsList(
    entry: InboxEntry,
    onAction: (MainAction) -> Unit,
    onShare: () -> Unit,
    onOpenExternalUrl: () -> Unit,
) {
    val readToggleLabel = if (entry.isRead) "Mark as Unread" else "Mark as Read"
    val offlineLabel =
        if (entry.isCachedOffline) {
            "Remove Local Offline Copy"
        } else {
            "Download Local Offline Copy"
        }

    ContextActionItem(
        label = readToggleLabel,
        onClick = { onAction(MainAction.ToggleReadState(entry.id)) },
    )
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
    ContextActionItem(
        label = "Delete Entry",
        textColor = LocalRocheColors.current.accentIndianRed,
        onClick = { onAction(MainAction.DeleteEntry(entry.id)) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedContextBottomSheet(
    feed: FeedItem,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    availableTags: List<String> = emptyList(),
) {
    val colors = LocalRocheColors.current
    ModalBottomSheet(
        onDismissRequest = { onAction(MainAction.CloseFeedContextSheet) },
        containerColor = colors.surface,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .testTag("feed_context_bottom_sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FeedContextHeaderRow(feed = feed)
            FeedRenameRow(feed = feed, onAction = onAction)
            FeedBulkTagRow(feed = feed, availableTags = availableTags, onAction = onAction)
            HorizontalDivider(color = colors.border, thickness = 1.dp)
            FeedContextActionsList(
                feed = feed,
                onAction = onAction,
                onOpenExternalUrl = onOpenExternalUrl,
            )
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@Composable
internal fun FeedContextHeaderRow(feed: FeedItem) {
    val colors = LocalRocheColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        SiteFaviconBadge(
            primaryFaviconUrl = feed.faviconUrl,
            fallbackFaviconUrl = feed.fallbackFaviconUrl,
            domain = feed.displayDomain,
            title = feed.title,
            feedType = feed.feedType,
            size = 28.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = feed.title,
                color = colors.textPrimary,
                fontFamily = FontFamily.Serif,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${feed.feedType.wireValue.uppercase()} · ${feed.displayDomain}",
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun FeedRenameRow(
    feed: FeedItem,
    onAction: (MainAction) -> Unit,
) {
    var titleDraft by rememberSaveable(feed.id) { mutableStateOf(feed.title) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = titleDraft,
            onValueChange = { titleDraft = it },
            label = { Text("Display Title", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("feed_rename_input"),
        )
        HeaderActionButton(
            label = "Rename",
            onClick = {
                val trimmed = titleDraft.trim()
                if (trimmed.isNotBlank()) {
                    onAction(MainAction.RenameFeed(feedId = feed.id, newTitle = trimmed))
                }
            },
            modifier = Modifier.testTag("feed_rename_save_btn"),
        )
    }
}

@Composable
internal fun FeedBulkTagRow(
    feed: FeedItem,
    availableTags: List<String>,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    var customFeedTag by rememberSaveable(feed.id) { mutableStateOf("") }
    Text(
        text = "TAG ACTIVE FEED ITEMS",
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
                    label = "+ #$tag",
                    selected = false,
                    onClick = {
                        onAction(MainAction.ApplyTagToFeed(feedId = feed.id, tagName = tag))
                    },
                )
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = customFeedTag,
            onValueChange = { customFeedTag = it },
            placeholder = { Text("Apply #tag to feed items…", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("feed_tag_input"),
        )
        HeaderActionButton(
            label = "Tag Feed",
            onClick = {
                val clean = normalizeTagName(customFeedTag)
                if (clean.isNotBlank()) {
                    onAction(MainAction.ApplyTagToFeed(feedId = feed.id, tagName = clean))
                    customFeedTag = ""
                }
            },
            modifier = Modifier.testTag("feed_tag_apply_btn"),
        )
    }
}

@Composable
internal fun FeedContextActionsList(
    feed: FeedItem,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    ContextActionItem(
        label = "View Feed Items (${feed.unreadCount} unread)",
        onClick = { onAction(MainAction.FilterByFeed(feed.id)) },
    )
    if (feed.unreadCount > 0) {
        ContextActionItem(
            label = "Archive All Unread in Feed (${feed.unreadCount})",
            onClick = { onAction(MainAction.ArchiveFeedUnread(feed.id)) },
            modifier = Modifier.testTag("feed_archive_unread_btn"),
        )
    }
    if (!feed.isArchived) {
        ContextActionItem(
            label = "Refresh Feed Now",
            onClick = {
                onAction(MainAction.CloseFeedContextSheet)
                onAction(MainAction.RefreshFeed(feed.id))
            },
        )
    }
    ContextActionItem(
        label = "Open Feed / Site URL",
        onClick = {
            onAction(MainAction.CloseFeedContextSheet)
            onOpenExternalUrl(feed.siteUrl ?: feed.url)
        },
    )
    ContextActionItem(
        label = if (feed.isArchived) "Unarchive Feed" else "Archive Feed",
        onClick = { onAction(MainAction.ToggleFeedArchive(feed.id)) },
        modifier = Modifier.testTag("toggle_archive_feed_${feed.id}"),
    )
    ContextActionItem(
        label = "Unsubscribe from Feed",
        textColor = colors.accentIndianRed,
        onClick = { onAction(MainAction.DeleteFeed(feed.id)) },
        modifier = Modifier.testTag("delete_feed_${feed.id}"),
    )
}

@Composable
private fun ContextActionItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: androidx.compose.ui.graphics.Color = LocalRocheColors.current.textPrimary,
) {
    Text(
        text = label,
        color = textColor,
        fontSize = 14.sp,
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp, horizontal = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddUrlOrFeedBottomSheet(
    initialUrl: String,
    initialTitle: String = "",
    onDismiss: () -> Unit,
    onSave: (String, String?, String) -> Unit,
) {
    val colors = LocalRocheColors.current
    var url by rememberSaveable(initialUrl, initialTitle) { mutableStateOf(initialUrl) }
    var title by rememberSaveable(initialUrl, initialTitle) { mutableStateOf(initialTitle) }
    var tags by rememberSaveable(initialUrl, initialTitle) { mutableStateOf("") }

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
            HeaderActionButton(
                label = "Save to Roche Inbox",
                style = RocheButtonStyle.PRIMARY,
                onClick = { onSave(url, title.takeIf { it.isNotBlank() }, tags) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private const val MIN_PROGRESS = 0.08f
private const val READ_PROGRESS_MIN_DELTA = 0.05
private const val PERCENT_MULTIPLIER = 100
private const val READER_SCALE_STEP = 0.10f
private const val READER_SCALE_EPSILON = 0.03f
private const val MAX_SECTION_LABEL_CHARS = 32
private val READER_FONT_PRESETS = listOf(0.88f, 1.0f, 1.15f, 1.30f)

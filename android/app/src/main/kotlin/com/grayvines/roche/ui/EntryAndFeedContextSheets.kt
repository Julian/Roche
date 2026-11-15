@file:Suppress("TooManyFunctions")

package com.grayvines.roche.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.data.normalizeTagName
import com.grayvines.roche.data.settings.PLAYBACK_SPEED_PRESETS
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.FeedType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.SettingToggleRow
import com.grayvines.roche.ui.components.SiteFaviconBadge
import com.grayvines.roche.ui.theme.LocalRocheColors
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryContextBottomSheet(
    entry: InboxEntry,
    availableTags: List<String>,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    isInQueue: Boolean = false,
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
                isInQueue = isInQueue,
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
    isInQueue: Boolean,
    onAction: (MainAction) -> Unit,
    onShare: () -> Unit,
    onOpenExternalUrl: () -> Unit,
) {
    val isAudio = entry.contentType == ContentType.AUDIO
    val readToggleLabel = if (entry.isRead) "Mark as Unread" else "Mark as Read"
    val offlineLabel =
        when {
            isAudio && entry.isCachedOffline -> "Delete Local Episode Download"
            isAudio -> "Download Episode for Offline Playback"
            entry.isCachedOffline -> "Remove Local Offline Copy"
            else -> "Download Local Offline Copy"
        }

    if (isAudio) {
        ContextActionItem(
            label = "▶ Play Episode Now",
            textColor = LocalRocheColors.current.accentBlue,
            onClick = { onAction(MainAction.PlayAudioEntry(entry.id, openFullscreen = true)) },
        )
    }
    ContextActionItem(
        label = "✓ Finished & Log to Diary",
        textColor = LocalRocheColors.current.accentVerdigris,
        onClick = {
            onAction(MainAction.MarkFeedEntryFinished(entryId = entry.id, rankImmediately = false))
        },
    )
    ContextActionItem(
        label = "★ Finish & Rank",
        onClick = {
            onAction(MainAction.MarkFeedEntryFinished(entryId = entry.id, rankImmediately = true))
        },
    )
    ContextActionItem(
        label = "Play Next in Queue",
        onClick = { onAction(MainAction.AddToQueue(entryId = entry.id, playNext = true)) },
    )
    ContextActionItem(
        label = if (isInQueue) "Remove from Queue" else "Add to Queue",
        onClick = { onAction(MainAction.ToggleQueueMembership(entry.id)) },
    )
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
            FeedPodcastSettingsSection(feed = feed, onAction = onAction)
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
internal fun FeedPodcastSettingsSection(
    feed: FeedItem,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    val isPodcast =
        feed.feedType == FeedType.PODCAST ||
            feed.autoDownloadEpisodes ||
            feed.customPlaybackSpeed != null
    if (!isPodcast) return

    HorizontalDivider(color = colors.border, thickness = 1.dp)
    SettingToggleRow(
        title = "Always download new episodes of this podcast",
        subtitle = "Automatically cache new episodes from ${feed.title} on this device",
        checked = feed.autoDownloadEpisodes,
        onCheckedChange = { enabled ->
            onAction(MainAction.SetFeedAutoDownload(feedId = feed.id, enabled = enabled))
        },
    )
    Text(
        text = "PODCAST PLAYBACK SPEED OVERRIDE",
        color = colors.textMuted,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChipPill(
            label = "Default",
            selected = feed.customPlaybackSpeed == null,
            onClick = { onAction(MainAction.SetFeedPlaybackSpeed(feedId = feed.id, speed = null)) },
        )
        PLAYBACK_SPEED_PRESETS.forEach { preset ->
            val label = formatPlaybackSpeedLabel(preset)
            val isSelected =
                feed.customPlaybackSpeed != null &&
                    abs(feed.customPlaybackSpeed - preset) < SPEED_EPSILON
            FilterChipPill(
                label = label,
                selected = isSelected,
                onClick = {
                    onAction(MainAction.SetFeedPlaybackSpeed(feedId = feed.id, speed = preset))
                },
            )
        }
    }
    HorizontalDivider(color = colors.border, thickness = 1.dp)
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
    textColor: Color = LocalRocheColors.current.textPrimary,
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
                .semantics { role = Role.Button }
                .padding(vertical = 12.dp, horizontal = 8.dp),
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

private const val SPEED_EPSILON = 0.02f

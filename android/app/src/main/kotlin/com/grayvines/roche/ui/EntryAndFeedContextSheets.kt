@file:Suppress("TooManyFunctions")

package com.grayvines.roche.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.grayvines.roche.core.isLikelyFeedUrl
import com.grayvines.roche.core.isLocalPdfUrl
import com.grayvines.roche.core.parseIsoInstant
import com.grayvines.roche.data.feeds.normalizeUserSubmittedUrl
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.FeedType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.ui.components.CompactStarAndArchiveButtons
import com.grayvines.roche.ui.components.FilterChipPill
import com.grayvines.roche.ui.components.HeaderActionButton
import com.grayvines.roche.ui.components.PodcastSpeedStrip
import com.grayvines.roche.ui.components.RocheAlertDialog
import com.grayvines.roche.ui.components.RocheButtonStyle
import com.grayvines.roche.ui.components.RocheSurfaceCard
import com.grayvines.roche.ui.components.SettingToggleRow
import com.grayvines.roche.ui.components.SiteFaviconBadge
import com.grayvines.roche.ui.components.TagChipsEditor
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList")
@Composable
fun EntryContextBottomSheet(
    entry: InboxEntry,
    availableTags: List<String>,
    onAction: (MainAction) -> Unit,
    onShareEntry: (InboxEntry) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    isInQueue: Boolean = false,
    isDownloadingOffline: Boolean = false,
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
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .testTag("context_bottom_sheet"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ContextSheetHeaderAndTags(
                entry = entry,
                allTags = allTags,
                onAction = onAction,
            )
            ContextSheetActionsList(
                entry = entry,
                isInQueue = isInQueue,
                isDownloadingOffline = isDownloadingOffline,
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
            Spacer(modifier = Modifier.height(16.dp))
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
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = entry.title,
                    color = colors.textPrimary,
                    fontFamily = currentHeadlineFontFamily(),
                    fontSize = 17.5.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.Normal,
                )
                Spacer(modifier = Modifier.height(4.dp))
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
}

@Composable
internal fun ContextSheetTagsEditor(
    entry: InboxEntry,
    allTags: List<String>,
    onAction: (MainAction) -> Unit,
) {
    TagChipsEditor(
        headerLabel = "TAGS (TAP TO TOGGLE)",
        availableTags = allTags,
        inputPlaceholder = "Add new #tag…",
        submitButtonLabel = "Add Tag",
        draftKey = entry.id,
        isChipSelected = { tag -> entry.tags.any { it.equals(tag, ignoreCase = true) } },
        onChipClick = { tag -> onAction(MainAction.ToggleTagOnEntry(entry.id, tag)) },
        onSubmitCustomTag = { tag -> onAction(MainAction.ToggleTagOnEntry(entry.id, tag)) },
    )
}

private data class ContextActionSpec(
    val label: String,
    val onClick: () -> Unit,
    val textColor: Color? = null,
    val isLoading: Boolean = false,
    val modifier: Modifier = Modifier,
)

@Composable
private fun ContextActionCardGroup(actions: List<ContextActionSpec>) {
    if (actions.isEmpty()) return
    val colors = LocalRocheColors.current
    RocheSurfaceCard {
        actions.forEachIndexed { idx, spec ->
            if (idx > 0) {
                HorizontalDivider(color = colors.border, thickness = 1.dp)
            }
            ContextActionItem(
                label = spec.label,
                textColor = spec.textColor ?: colors.textPrimary,
                isLoading = spec.isLoading,
                onClick = spec.onClick,
                modifier = spec.modifier,
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun ContextSheetActionsList(
    entry: InboxEntry,
    isInQueue: Boolean,
    isDownloadingOffline: Boolean,
    onAction: (MainAction) -> Unit,
    onShare: () -> Unit,
    onOpenExternalUrl: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val isPlayableAudio = entry.isPlayableAudio
    val isAudioOnly = entry.contentType == ContentType.AUDIO
    val isLocalPdf = isLocalPdfUrl(entry.url)
    var confirmDeleteEntry by rememberSaveable(entry.id) { mutableStateOf(false) }

    val primaryGroup =
        buildEntryPrimaryActions(
            entry = entry,
            isPlayableAudio = isPlayableAudio,
            isAudioOnly = isAudioOnly,
            isInQueue = isInQueue,
            isDownloadingOffline = isDownloadingOffline,
            accentBlue = colors.accentBlue,
            accentVerdigris = colors.accentVerdigris,
            onAction = onAction,
        )
    val secondaryGroup = buildList {
        if (entry.feedId != null) {
            add(
                ContextActionSpec(
                    label = "Filter Inbox by ${entry.feedTitle}",
                    onClick = { onAction(MainAction.FilterByFeed(entry.feedId)) },
                )
            )
        }
        if (!isLocalPdf) {
            add(ContextActionSpec(label = "Share…", onClick = onShare))
        }
        if (!isLocalPdf && entry.url.isNotBlank()) {
            add(ContextActionSpec(label = "Open Original URL ↗", onClick = onOpenExternalUrl))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ContextActionCardGroup(primaryGroup)
        ContextActionCardGroup(secondaryGroup)
        ContextActionCardGroup(
            listOf(
                ContextActionSpec(
                    label = "Delete Entry",
                    textColor = colors.accentIndianRed,
                    onClick = { confirmDeleteEntry = true },
                )
            )
        )
    }
    if (confirmDeleteEntry) {
        DestructiveConfirmDialog(
            title = "Delete entry?",
            message = "Permanently delete \"${entry.title}\" from your library?",
            confirmLabel = "Delete",
            onDismiss = { confirmDeleteEntry = false },
            onConfirm = {
                confirmDeleteEntry = false
                onAction(MainAction.DeleteEntry(entry.id))
            },
        )
    }
}

@Suppress("LongParameterList")
private fun buildEntryPrimaryActions(
    entry: InboxEntry,
    isPlayableAudio: Boolean,
    isAudioOnly: Boolean,
    isInQueue: Boolean,
    isDownloadingOffline: Boolean,
    accentBlue: Color,
    accentVerdigris: Color,
    onAction: (MainAction) -> Unit,
): List<ContextActionSpec> = buildList {
    if (isPlayableAudio) {
        val playLabel = if (entry.isPodcastEpisode) "Play Episode Now" else "Play Audio Now"
        add(
            ContextActionSpec(
                playLabel,
                { onAction(MainAction.PlayAudioEntry(entry.id, true)) },
                accentBlue,
            )
        )
    }
    if (isAudioOnly) {
        add(
            ContextActionSpec(
                "Open Full Show Notes in Reader",
                { onAction(MainAction.OpenReader(entry.id, forceArticleView = true)) },
            )
        )
    }
    add(
        ContextActionSpec(
            "Finish & Log to Diary",
            { onAction(MainAction.MarkFeedEntryFinished(entry.id, rankImmediately = false)) },
            accentVerdigris,
        )
    )
    add(
        ContextActionSpec(
            "Finish & Rank",
            { onAction(MainAction.MarkFeedEntryFinished(entry.id, rankImmediately = true)) },
        )
    )
    add(
        ContextActionSpec(
            "Play Next in Queue",
            { onAction(MainAction.AddToQueue(entry.id, playNext = true)) },
        )
    )
    add(
        ContextActionSpec(
            if (isInQueue) "Remove from Queue" else "Add to Queue",
            { onAction(MainAction.ToggleQueueMembership(entry.id)) },
        )
    )
    add(
        ContextActionSpec(
            if (entry.isRead) "Mark as Unread" else "Mark as Read",
            { onAction(MainAction.ToggleReadState(entry.id)) },
        )
    )
    add(
        buildOfflineActionSpec(
            entry = entry,
            isAudio = isPlayableAudio,
            isDownloadingOffline = isDownloadingOffline,
            accentBlue = accentBlue,
            onAction = onAction,
        )
    )
}

private fun buildOfflineActionSpec(
    entry: InboxEntry,
    isAudio: Boolean,
    isDownloadingOffline: Boolean,
    accentBlue: Color,
    onAction: (MainAction) -> Unit,
): ContextActionSpec {
    val label =
        if (isDownloadingOffline) {
            if (isAudio) "Downloading Audio…" else "Saving Offline Copy…"
        } else {
            resolveOfflineActionLabel(entry, isAudio)
        }
    return ContextActionSpec(
        label = label,
        onClick = {
            if (!isDownloadingOffline) {
                onAction(MainAction.CloseContextSheet)
                onAction(MainAction.ToggleOfflineCache(entry.id))
            }
        },
        textColor = if (isDownloadingOffline) accentBlue else null,
        isLoading = isDownloadingOffline,
    )
}

private fun resolveOfflineActionLabel(entry: InboxEntry, isAudio: Boolean): String =
    when {
        isAudio && entry.isCachedOffline -> "Delete Local Audio Download"
        isAudio -> "Download Audio for Offline Playback"
        entry.isCachedOffline -> "Remove Offline Copy"
        else -> "Save Offline Copy"
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
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .testTag("feed_context_bottom_sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FeedContextHeaderRow(feed = feed)
            FeedHealthDetailsCard(feed = feed, onAction = onAction)
            FeedRenameRow(feed = feed, onAction = onAction)
            FeedTypeSelectorSection(feed = feed, onAction = onAction)
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
                fontFamily = currentHeadlineFontFamily(),
                fontSize = 17.5.sp,
                fontWeight = FontWeight.Normal,
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
internal fun FeedTypeSelectorSection(
    feed: FeedItem,
    onAction: (MainAction) -> Unit,
) {
    if (feed.feedType != FeedType.RSS && feed.feedType != FeedType.PODCAST) return
    val colors = LocalRocheColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "FEED TYPE",
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChipPill(
                label = "RSS / Newsletter",
                selected = feed.feedType == FeedType.RSS,
                onClick = {
                    if (feed.feedType != FeedType.RSS) {
                        onAction(MainAction.SetFeedType(feedId = feed.id, feedType = FeedType.RSS))
                    }
                },
                modifier = Modifier.testTag("feed_type_rss_chip"),
            )
            FilterChipPill(
                label = "Podcast",
                selected = feed.feedType == FeedType.PODCAST,
                onClick = {
                    if (feed.feedType != FeedType.PODCAST) {
                        onAction(
                            MainAction.SetFeedType(feedId = feed.id, feedType = FeedType.PODCAST)
                        )
                    }
                },
                modifier = Modifier.testTag("feed_type_podcast_chip"),
            )
        }
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
    PodcastSpeedStrip(
        selectedSpeed = feed.customPlaybackSpeed,
        includeDefaultChip = true,
        onSelectDefault = {
            onAction(MainAction.SetFeedPlaybackSpeed(feedId = feed.id, speed = null))
        },
        onSelectSpeed = { preset ->
            onAction(MainAction.SetFeedPlaybackSpeed(feedId = feed.id, speed = preset))
        },
    )
    HorizontalDivider(color = colors.border, thickness = 1.dp)
}

@Composable
internal fun FeedBulkTagRow(
    feed: FeedItem,
    availableTags: List<String>,
    onAction: (MainAction) -> Unit,
) {
    TagChipsEditor(
        headerLabel = "TAG ACTIVE FEED ITEMS",
        availableTags = availableTags,
        inputPlaceholder = "Apply #tag to feed items…",
        submitButtonLabel = "Tag Feed",
        draftKey = feed.id,
        chipLabel = { "+ #$it" },
        onChipClick = { tag ->
            onAction(MainAction.ApplyTagToFeed(feedId = feed.id, tagName = tag))
        },
        onSubmitCustomTag = { tag ->
            onAction(MainAction.ApplyTagToFeed(feedId = feed.id, tagName = tag))
        },
        inputTestTag = "feed_tag_input",
        submitTestTag = "feed_tag_apply_btn",
    )
}

@Composable
internal fun FeedContextActionsList(
    feed: FeedItem,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    val colors = LocalRocheColors.current
    var confirmDeleteFeed by rememberSaveable(feed.id) { mutableStateOf(false) }
    val feedActions = buildFeedPrimaryActions(feed, onAction, onOpenExternalUrl)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ContextActionCardGroup(feedActions)
        ContextActionCardGroup(
            listOf(
                ContextActionSpec(
                    label = "Unsubscribe from Feed",
                    textColor = colors.accentIndianRed,
                    onClick = { confirmDeleteFeed = true },
                    modifier = Modifier.testTag("delete_feed_${feed.id}"),
                )
            )
        )
    }
    if (confirmDeleteFeed) {
        DestructiveConfirmDialog(
            title = "Unsubscribe from feed?",
            message = "Unsubscribe from \"${feed.title}\" and remove its entries from your Inbox?",
            confirmLabel = "Unsubscribe",
            confirmTestTag = "confirm_delete_feed_${feed.id}",
            onDismiss = { confirmDeleteFeed = false },
            onConfirm = {
                confirmDeleteFeed = false
                onAction(MainAction.DeleteFeed(feed.id))
            },
        )
    }
}

private fun buildFeedPrimaryActions(
    feed: FeedItem,
    onAction: (MainAction) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
): List<ContextActionSpec> = buildList {
    add(
        ContextActionSpec(
            label = "View Feed Items (${feed.unreadCount} unread)",
            onClick = { onAction(MainAction.FilterByFeed(feed.id)) },
        )
    )
    if (feed.unreadCount > 0) {
        add(
            ContextActionSpec(
                label = "Archive All Unread in Feed (${feed.unreadCount})",
                onClick = { onAction(MainAction.ArchiveFeedUnread(feed.id)) },
                modifier = Modifier.testTag("feed_archive_unread_btn"),
            )
        )
    }
    if (!feed.isArchived) {
        add(
            ContextActionSpec(
                label = "Refresh Feed Now",
                onClick = {
                    onAction(MainAction.CloseFeedContextSheet)
                    onAction(MainAction.RefreshFeed(feed.id))
                },
            )
        )
    }
    add(
        ContextActionSpec(
            label = "Open Feed / Site URL ↗",
            onClick = {
                onAction(MainAction.CloseFeedContextSheet)
                onOpenExternalUrl(feed.siteUrl ?: feed.url)
            },
        )
    )
    add(
        ContextActionSpec(
            label = if (feed.isArchived) "Unarchive Feed" else "Archive Feed",
            onClick = { onAction(MainAction.ToggleFeedArchive(feed.id)) },
            modifier = Modifier.testTag("toggle_archive_feed_${feed.id}"),
        )
    )
}

@Composable
private fun DestructiveConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmTestTag: String? = null,
) {
    val colors = LocalRocheColors.current
    val confirmModifier = if (confirmTestTag != null) Modifier.testTag(confirmTestTag) else Modifier
    RocheAlertDialog(
        title = title,
        onDismissRequest = onDismiss,
        text = { Text(text = message, color = colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = confirmModifier) {
                Text(confirmLabel, color = colors.accentIndianRed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) }
        },
    )
}

@Composable
private fun FeedHealthDetailsCard(
    feed: FeedItem,
    onAction: (MainAction) -> Unit,
) {
    val lastPolledLabel = remember(feed.lastPolled) { formatFeedPolledLabel(feed.lastPolled) }
    val errorText = feed.lastError?.trim()?.takeIf { it.isNotEmpty() }
    if (errorText == null && feed.lastPolled.isNullOrBlank()) return

    RocheSurfaceCard {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp)
                    .testTag("feed_health_card_${feed.id}"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FeedHealthHeaderRow(
                lastPolledLabel = lastPolledLabel,
                consecutiveFailures = feed.consecutiveFailures,
            )
            if (errorText != null) {
                FeedHealthErrorBlock(feed = feed, errorText = errorText, onAction = onAction)
            }
        }
    }
}

@Composable
private fun FeedHealthHeaderRow(
    lastPolledLabel: String,
    consecutiveFailures: Int,
) {
    val colors = LocalRocheColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Last polled: $lastPolledLabel",
            color = colors.textMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        if (consecutiveFailures > 0) {
            val failWord = if (consecutiveFailures == 1) "failure" else "failures"
            Text(
                text = "$consecutiveFailures $failWord",
                color = colors.accentIndianRed,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun FeedHealthErrorBlock(
    feed: FeedItem,
    errorText: String,
    onAction: (MainAction) -> Unit,
) {
    val colors = LocalRocheColors.current
    Text(
        text = errorText,
        color = colors.accentIndianRed,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = Modifier.testTag("feed_full_error_text"),
    )
    if (!feed.isArchived) {
        HeaderActionButton(
            label = "Retry Now",
            style = RocheButtonStyle.PRIMARY,
            onClick = {
                onAction(MainAction.CloseFeedContextSheet)
                onAction(MainAction.RefreshFeed(feed.id))
            },
            modifier = Modifier.testTag("feed_retry_now_btn"),
        )
    }
}

private fun formatFeedPolledLabel(lastPolledIso: String?): String {
    val parsed = parseIsoInstant(lastPolledIso) ?: return "Never"
    val elapsedSecs =
        java.time.Duration.between(parsed, java.time.Instant.now()).seconds.coerceAtLeast(0L)
    return when {
        elapsedSecs < SECONDS_PER_MINUTE -> "just now"
        elapsedSecs < SECONDS_PER_HOUR -> "${elapsedSecs / SECONDS_PER_MINUTE}m ago"
        elapsedSecs < SECONDS_PER_DAY -> "${elapsedSecs / SECONDS_PER_HOUR}h ago"
        else -> "${elapsedSecs / SECONDS_PER_DAY}d ago"
    }
}

@Composable
private fun ContextActionItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = LocalRocheColors.current.textPrimary,
    isLoading: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(enabled = !isLoading, onClick = onClick)
                .semantics { role = Role.Button }
                .padding(vertical = 13.dp, horizontal = 16.dp),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = textColor,
            )
        }
        Text(text = label, color = textColor, fontSize = 14.sp)
    }
}

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_DAY = 86_400L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddUrlOrFeedBottomSheet(
    initialUrl: String,
    initialTitle: String = "",
    defaultMode: AddSheetMode = AddSheetMode.WANT_TO_READ,
    onDismiss: () -> Unit,
    onSave: (String, String?, String, AddSheetMode, FeedType?) -> Unit,
) {
    val colors = LocalRocheColors.current
    var url by rememberSaveable(initialUrl, initialTitle) { mutableStateOf(initialUrl) }
    var title by rememberSaveable(initialUrl, initialTitle) { mutableStateOf(initialTitle) }
    var tags by rememberSaveable(initialUrl, initialTitle) { mutableStateOf("") }
    var mode by
        rememberSaveable(initialUrl, defaultMode) {
            mutableStateOf(resolveInitialAddMode(initialUrl, defaultMode))
        }
    var feedType by rememberSaveable(initialUrl, defaultMode) { mutableStateOf(FeedType.RSS) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        AddSheetFormBody(
            mode = mode,
            feedType = feedType,
            url = url,
            title = title,
            tags = tags,
            onModeChange = { mode = it },
            onFeedTypeChange = { feedType = it },
            onUrlChange = { next ->
                url = next
                if (isLikelyFeedUrl(next)) {
                    mode = AddSheetMode.SUBSCRIBE_FEED
                }
            },
            onTitleChange = { title = it },
            onTagsChange = { tags = it },
            onSubmit = {
                onSave(
                    url,
                    title.takeIf { it.isNotBlank() },
                    tags,
                    mode,
                    if (mode == AddSheetMode.SUBSCRIBE_FEED) feedType else null,
                )
            },
        )
    }
}

private fun resolveInitialAddMode(initialUrl: String, defaultMode: AddSheetMode): AddSheetMode =
    if (isLikelyFeedUrl(initialUrl)) AddSheetMode.SUBSCRIBE_FEED else defaultMode

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun AddSheetFormBody(
    mode: AddSheetMode,
    feedType: FeedType,
    url: String,
    title: String,
    tags: String,
    onModeChange: (AddSheetMode) -> Unit,
    onFeedTypeChange: (FeedType) -> Unit,
    onUrlChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onTagsChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = LocalRocheColors.current
    val isFeedMode = mode == AddSheetMode.SUBSCRIBE_FEED
    val urlHint =
        if (isFeedMode) {
            "Blog Homepage, Podcast, YouTube, or RSS/Atom URL"
        } else {
            "Article, PDF, Video, or Media URL"
        }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = if (isFeedMode) "Subscribe to Feed" else "Add to Want to Read",
            color = colors.textPrimary,
            fontFamily = currentHeadlineFontFamily(),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )
        AddSheetModeSelectorRow(selectedMode = mode, onSelectMode = onModeChange)
        if (isFeedMode) {
            AddSheetFeedTypeRow(selectedFeedType = feedType, onSelectFeedType = onFeedTypeChange)
        }
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text(urlHint) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!isFeedMode && isBareHomepageCandidate(url)) {
            FilterChipPill(
                label = "Site homepage? Switch to Subscribe Feed →",
                selected = false,
                onClick = { onModeChange(AddSheetMode.SUBSCRIBE_FEED) },
                modifier = Modifier.testTag("add_sheet_homepage_hint"),
            )
        }
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            label = { Text("Optional Title Override") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!isFeedMode) {
            OutlinedTextField(
                value = tags,
                onValueChange = onTagsChange,
                label = { Text("Tags (comma-separated)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        HeaderActionButton(
            label = if (isFeedMode) "Find & Subscribe Feed" else "Add to Want to Read",
            style = RocheButtonStyle.PRIMARY,
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth().testTag("add_sheet_submit_btn"),
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun AddSheetFeedTypeRow(
    selectedFeedType: FeedType,
    onSelectFeedType: (FeedType) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChipPill(
            label = "RSS / Blog",
            selected = selectedFeedType == FeedType.RSS,
            onClick = { onSelectFeedType(FeedType.RSS) },
            modifier = Modifier.testTag("add_feed_type_rss"),
        )
        FilterChipPill(
            label = "Podcast",
            selected = selectedFeedType == FeedType.PODCAST,
            onClick = { onSelectFeedType(FeedType.PODCAST) },
            modifier = Modifier.testTag("add_feed_type_podcast"),
        )
    }
}

@Composable
private fun AddSheetModeSelectorRow(
    selectedMode: AddSheetMode,
    onSelectMode: (AddSheetMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChipPill(
            label = "Want to Read",
            selected = selectedMode == AddSheetMode.WANT_TO_READ,
            onClick = { onSelectMode(AddSheetMode.WANT_TO_READ) },
            modifier = Modifier.testTag("add_mode_want_to_read"),
        )
        FilterChipPill(
            label = "Subscribe Feed",
            selected = selectedMode == AddSheetMode.SUBSCRIBE_FEED,
            onClick = { onSelectMode(AddSheetMode.SUBSCRIBE_FEED) },
            modifier = Modifier.testTag("add_mode_subscribe_feed"),
        )
    }
}

private fun isBareHomepageCandidate(rawInput: String): Boolean {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty() || isLikelyFeedUrl(trimmed)) return false
    val normalized = normalizeUserSubmittedUrl(trimmed)
    val parsed = runCatching { URI.create(normalized) }.getOrNull() ?: return false
    val host = parsed.host.orEmpty()
    val hasValidHost = '.' in host && !host.startsWith('.') && !host.endsWith('.')
    val isRootPath = parsed.path.isNullOrEmpty() || parsed.path == "/"
    return hasValidHost && isRootPath && parsed.query.isNullOrEmpty()
}

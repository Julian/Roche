package com.grayvines.roche.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.ui.theme.LocalRocheColors

private val PodcastArtworkFallbackBg = Color(0xFF1A242D)

@Composable
internal fun EntrySourceMetadataRow(
    entry: InboxEntry,
    modifier: Modifier = Modifier,
    showUnreadDot: Boolean = false,
    unreadDotTestTag: String? = null,
    faviconSize: Dp = 14.dp,
    feedLabelFontSize: TextUnit = 11.sp,
    emphasizeFeedTitleWhenUnread: Boolean = false,
    trailingMetaText: String? = null,
    trailingMetaFontSize: TextUnit = 11.sp,
    trailingBadge: String? = null,
    showOfflineIndicator: Boolean = false,
) {
    val colors = LocalRocheColors.current
    val hasBadge = trailingBadge != null
    Row(
        modifier = if (hasBadge) modifier.fillMaxWidth() else modifier,
        horizontalArrangement =
            if (hasBadge) Arrangement.SpaceBetween else Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntrySourceLeadingCluster(
            entry = entry,
            showUnreadDot = showUnreadDot,
            unreadDotTestTag = unreadDotTestTag,
            faviconSize = faviconSize,
            feedLabelFontSize = feedLabelFontSize,
            emphasizeFeedTitleWhenUnread = emphasizeFeedTitleWhenUnread,
            trailingMetaText = trailingMetaText,
            trailingMetaFontSize = trailingMetaFontSize,
            showOfflineIndicator = showOfflineIndicator,
            modifier = if (hasBadge) Modifier.weight(1f).padding(end = 8.dp) else Modifier,
        )
        if (trailingBadge != null) {
            Text(
                text = trailingBadge,
                color = colors.accentVerdigris,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun EntrySourceLeadingCluster(
    entry: InboxEntry,
    showUnreadDot: Boolean,
    unreadDotTestTag: String?,
    faviconSize: Dp,
    feedLabelFontSize: TextUnit,
    emphasizeFeedTitleWhenUnread: Boolean,
    trailingMetaText: String?,
    trailingMetaFontSize: TextUnit,
    showOfflineIndicator: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRocheColors.current
    val highlightFeed = emphasizeFeedTitleWhenUnread && entry.isUnread
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showUnreadDot && entry.isUnread) {
            EntryUnreadDot(testTag = unreadDotTestTag)
        }
        SiteFaviconBadge(
            primaryFaviconUrl = entry.faviconUrl,
            fallbackFaviconUrl = entry.fallbackFaviconUrl,
            domain = entry.displayDomain,
            title = entry.feedTitle,
            size = faviconSize,
        )
        Text(
            text = entry.feedTitle,
            color = if (highlightFeed) colors.textPrimary else colors.textSecondary,
            fontSize = feedLabelFontSize,
            fontWeight = if (highlightFeed) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (trailingMetaText != null) {
            Text(
                text = trailingMetaText,
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = trailingMetaFontSize,
                maxLines = 1,
            )
        }
        if (showOfflineIndicator && entry.isCachedOffline) {
            Text(
                text = "↓",
                color = colors.accentVerdigris,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.semantics { contentDescription = "Cached offline" },
            )
        }
    }
}

@Composable
private fun EntryUnreadDot(testTag: String?) {
    val colors = LocalRocheColors.current
    val dotMod =
        Modifier.size(6.dp).clip(CircleShape).background(colors.accentBlue).let { base ->
            if (testTag != null) base.testTag(testTag) else base
        }
    Box(modifier = dotMod)
}

@Composable
internal fun PodcastArtworkBox(
    entry: InboxEntry,
    size: Dp,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(cornerRadius)
    val bitmap =
        rememberEntryThumbnailBitmap(
            primaryKey = entry.thumbnailMediaId,
            fallbackKey = entry.effectiveThumbnailUrl ?: entry.faviconUrl,
        )
    Box(
        modifier =
            modifier
                .size(size)
                .clip(shape)
                .background(PodcastArtworkFallbackBg)
                .border(width = 1.dp, color = colors.border, shape = shape)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Canvas(modifier = Modifier.matchParentSize()) { drawPodcastArtworkCanvas(colors) }
        }
        content()
    }
}

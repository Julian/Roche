package com.grayvines.roche.model

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ContentType(val wireValue: String) {
    ARTICLE("article"),
    PDF("pdf"),
    VIDEO("video");

    companion object {
        fun fromWireValue(value: String): ContentType =
            when {
                value.equals("paper", ignoreCase = true) ||
                    value.equals("pdf", ignoreCase = true) -> PDF
                value.equals("video", ignoreCase = true) -> VIDEO
                else -> ARTICLE
            }
    }
}

enum class ItemStatus(val wireValue: String) {
    UNREAD("unread"),
    READ("read"),
    ARCHIVED("archived");

    companion object {
        fun fromWireValue(value: String): ItemStatus =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: UNREAD
    }
}

enum class FeedType(val wireValue: String) {
    RSS("rss"),
    ARXIV("arxiv"),
    YOUTUBE("youtube"),
    SAVED("saved");

    companion object {
        fun fromWireValue(value: String): FeedType =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: RSS
    }
}

enum class ThemeMode {
    DARK,
    LIGHT,
}

enum class HeadlineFont {
    SERIF,
    SANS,
}

enum class InboxFilter(val label: String) {
    UNREAD("Unread"),
    ARTICLES("Articles"),
    PAPERS("Papers"),
    VIDEOS("Videos"),
    STARRED("Starred"),
    ARCHIVED("Archived"),
}

enum class AppTab(val label: String) {
    INBOX("Inbox"),
    SEARCH("Search"),
    FEEDS("Feeds"),
    OFFLINE("Offline"),
    SETTINGS("Settings"),
}

data class FeedItem(
    val id: String,
    val title: String,
    val url: String,
    val siteUrl: String?,
    val feedType: FeedType,
    val unreadCount: Int,
)

data class TagItem(val id: String, val name: String, val unreadCount: Int)

data class InboxEntry(
    val id: String,
    val feedId: String?,
    val feedTitle: String,
    val title: String,
    val url: String,
    val author: String?,
    val summary: String?,
    val contentText: String?,
    val contentHtml: String?,
    val contentType: ContentType,
    val status: ItemStatus,
    val isFavorite: Boolean,
    val isCachedOffline: Boolean,
    val publishedAt: String?,
    val createdAt: String,
    val readProgress: Double,
    val thumbnailMediaId: String?,
    val duration: String?,
    val tags: List<String>,
) {
    val isArchived: Boolean
        get() = status == ItemStatus.ARCHIVED

    val isUnread: Boolean
        get() = status == ItemStatus.UNREAD

    val showsThumbnailPreview: Boolean
        get() = contentType != ContentType.ARTICLE

    val formattedPublishedLabel: String
        get() = formatRelativeTimestamp(publishedAt ?: createdAt)

    val readEstimateLabel: String
        get() =
            when (contentType) {
                ContentType.VIDEO -> duration ?: "Video"
                ContentType.PDF -> duration ?: "PDF"
                ContentType.ARTICLE ->
                    formatArticleMinutes(contentText ?: contentHtml ?: summary.orEmpty())
            }
}

internal fun formatRelativeTimestamp(raw: String?, now: Instant = Instant.now()): String {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isBlank()) return "recent"
    val instant =
        runCatching { Instant.parse(trimmed) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(trimmed).toInstant() }.getOrNull()
            ?: return trimmed
    val seconds = Duration.between(instant, now).seconds.coerceAtLeast(0L)
    return when {
        seconds < SECONDS_PER_MIN -> "now"
        seconds < SECONDS_PER_HOUR -> "${seconds / SECONDS_PER_MIN}m"
        seconds < SECONDS_PER_DAY -> "${seconds / SECONDS_PER_HOUR}h"
        seconds < SECONDS_PER_MONTH -> "${seconds / SECONDS_PER_DAY}d"
        else -> SHORT_DATE_FORMATTER.format(instant.atOffset(ZoneOffset.UTC))
    }
}

internal fun formatArticleMinutes(text: String): String {
    val wordCount = text.split(WHITESPACE_REGEX_TOP).count { it.isNotBlank() }
    val minutes = (wordCount / ARTICLE_WORDS_PER_MINUTE).coerceAtLeast(1)
    return "${minutes}m"
}

private const val SECONDS_PER_MIN = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_DAY = 86_400L
private const val SECONDS_PER_MONTH = 2_592_000L
private const val ARTICLE_WORDS_PER_MINUTE = 180
private val WHITESPACE_REGEX_TOP = Regex("""\s+""")
private val SHORT_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d", Locale.US)

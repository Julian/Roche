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
    ALL("All"),
    UNREAD("Unread"),
    ARTICLES("Articles"),
    PAPERS("Papers"),
    VIDEOS("Videos"),
    STARRED("Starred"),
    ARCHIVED("Archived"),
}

enum class AppTab(val label: String) {
    FEEDS("Feeds"),
    STARRED("Starred"),
    INBOX("Inbox"),
    SEARCH("Search"),
    OFFLINE("Offline"),
    SETTINGS("Settings");

    companion object {
        const val SIDE_TAB_COUNT = 4
        val sideTabCandidates: List<AppTab> = listOf(FEEDS, STARRED, SEARCH, OFFLINE, SETTINGS)
        val defaultSideTabs: List<AppTab> = listOf(FEEDS, STARRED, SEARCH, SETTINGS)

        fun normalizeSideTabs(tabs: List<AppTab>): List<AppTab> {
            val distinctValid = tabs.filter { it != INBOX && it in sideTabCandidates }.distinct()
            if (distinctValid.size == SIDE_TAB_COUNT) return distinctValid
            val result = distinctValid.toMutableList()
            for (fallback in defaultSideTabs + sideTabCandidates) {
                if (result.size >= SIDE_TAB_COUNT) break
                if (fallback !in result) {
                    result.add(fallback)
                }
            }
            return result.take(SIDE_TAB_COUNT)
        }
    }
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
    val readAt: String? = null,
    val thumbnailUrl: String? = null,
) {
    val isRead: Boolean
        get() = !readAt.isNullOrBlank()

    val isArchived: Boolean
        get() = status == ItemStatus.ARCHIVED

    val isUnread: Boolean
        get() = status == ItemStatus.UNREAD && !isRead

    val effectiveThumbnailUrl: String?
        get() =
            thumbnailUrl?.trim()?.takeIf { it.isNotBlank() }
                ?: extractFirstImageUrlFromHtml(contentHtml)

    val showsThumbnailPreview: Boolean
        get() =
            when (contentType) {
                ContentType.ARTICLE ->
                    !thumbnailMediaId.isNullOrBlank() || !effectiveThumbnailUrl.isNullOrBlank()
                ContentType.PDF,
                ContentType.VIDEO -> true
            }

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

internal fun extractFirstImageUrlFromHtml(html: String?): String? {
    if (html.isNullOrBlank() || !html.contains("<img", ignoreCase = true)) return null
    val match = IMG_SRC_REGEX.find(html) ?: return null
    val rawUrl = match.groupValues[1].trim().replace("&amp;", "&")
    return rawUrl.takeIf {
        it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
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
    val plainText = text.replace(HTML_TAG_STRIP_REGEX, " ")
    val wordCount = plainText.split(WHITESPACE_REGEX_TOP).count { it.isNotBlank() }
    val minutes = (wordCount / ARTICLE_WORDS_PER_MINUTE).coerceAtLeast(1)
    return "${minutes}m"
}

private const val SECONDS_PER_MIN = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_DAY = 86_400L
private const val SECONDS_PER_MONTH = 2_592_000L
private const val ARTICLE_WORDS_PER_MINUTE = 180
private val HTML_TAG_STRIP_REGEX = Regex("""<[^>]+>""")
private val IMG_SRC_REGEX =
    Regex("""<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
private val WHITESPACE_REGEX_TOP = Regex("""\s+""")
private val SHORT_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d", Locale.US)

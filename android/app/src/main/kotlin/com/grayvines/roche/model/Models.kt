package com.grayvines.roche.model

import java.net.URI
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

    val effectiveThumbnailUrl: String? =
        thumbnailUrl?.trim()?.takeIf { it.isNotBlank() && !isNonThumbnailUrl(it) }
            ?: extractFirstImageUrlFromHtml(contentHtml, baseUrl = url)

    val showsThumbnailPreview: Boolean =
        when (contentType) {
            ContentType.ARTICLE ->
                !thumbnailMediaId.isNullOrBlank() || !effectiveThumbnailUrl.isNullOrBlank()
            ContentType.PDF,
            ContentType.VIDEO -> true
        }

    val formattedPublishedLabel: String = formatRelativeTimestamp(publishedAt ?: createdAt)

    val readEstimateLabel: String =
        when (contentType) {
            ContentType.VIDEO -> duration ?: "Video"
            ContentType.PDF -> duration ?: "PDF"
            ContentType.ARTICLE ->
                formatArticleMinutes(contentText ?: contentHtml ?: summary.orEmpty())
        }
}

internal fun isNonThumbnailUrl(url: String): Boolean {
    val pathPart = url.substringBefore('#').substringBefore('?').lowercase(Locale.US)
    if (pathPart.endsWith(".svg") || pathPart.endsWith(".svgz") || pathPart.endsWith(".ico")) {
        return true
    }
    val lower = url.lowercase(Locale.US)
    return lower.contains("/images/core/emoji/") ||
        lower.contains("/wp-includes/images/smilies/") ||
        lower.contains("/wpcom-smileys/") ||
        lower.contains("twemoji.maxcdn.com/") ||
        lower.contains("/twemoji/") ||
        lower.contains("feeds.feedburner.com/~") ||
        lower.contains("/spacer.gif") ||
        lower.contains("/1x1.gif") ||
        lower.contains("/pixel.gif")
}

internal fun resolveArticleImageUrl(rawUrl: String, baseUrl: String? = null): String? {
    val candidate = rawUrl.trim().replace("&amp;", "&")
    if (candidate.isBlank() || hasDisallowedImageScheme(candidate)) return null
    val resolved =
        if (isHttpImageScheme(candidate)) {
            candidate
        } else if (!baseUrl.isNullOrBlank()) {
            runCatching { URI.create(baseUrl.trim()).resolve(candidate).toString() }.getOrNull()
        } else {
            null
        }
    return resolved?.takeIf { isHttpImageScheme(it) && !isNonThumbnailUrl(it) }
}

private fun isHttpImageScheme(url: String): Boolean =
    url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

private fun hasDisallowedImageScheme(url: String): Boolean =
    url.startsWith("data:", ignoreCase = true) ||
        url.startsWith("javascript:", ignoreCase = true) ||
        url.startsWith("mailto:", ignoreCase = true) ||
        url.startsWith("blob:", ignoreCase = true)

internal fun extractFirstImageUrlFromHtml(html: String?, baseUrl: String? = null): String? {
    if (html.isNullOrBlank() || !html.contains("<img", ignoreCase = true)) return null
    for (match in IMG_SRC_REGEX.findAll(html)) {
        resolveArticleImageUrl(match.groupValues[1], baseUrl)?.let {
            return it
        }
    }
    return null
}

internal fun extractAllImageUrlsFromHtml(html: String?, baseUrl: String? = null): List<String> {
    if (html.isNullOrBlank() || !html.contains("<img", ignoreCase = true)) return emptyList()
    return IMG_SRC_REGEX.findAll(html)
        .mapNotNull { match -> resolveArticleImageUrl(match.groupValues[1], baseUrl) }
        .distinct()
        .toList()
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

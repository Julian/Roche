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
    VIDEO("video"),
    AUDIO("audio");

    companion object {
        fun fromWireValue(value: String): ContentType =
            when {
                value.equals("paper", ignoreCase = true) ||
                    value.equals("pdf", ignoreCase = true) -> PDF
                value.equals("video", ignoreCase = true) -> VIDEO
                value.equals("audio", ignoreCase = true) ||
                    value.equals("podcast", ignoreCase = true) -> AUDIO
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
    PODCAST("podcast"),
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
    PODCASTS("Podcasts"),
    STARRED("Starred"),
    ARCHIVED("Archived"),
}

enum class AppTab(val label: String) {
    FEEDS("Feeds"),
    QUEUE("Queue"),
    STARRED("Starred"),
    INBOX("Inbox"),
    SEARCH("Search"),
    OFFLINE("Offline"),
    SETTINGS("Settings");

    companion object {
        const val SIDE_TAB_COUNT = 4
        val sideTabCandidates: List<AppTab> =
            listOf(FEEDS, QUEUE, STARRED, SEARCH, OFFLINE, SETTINGS)
        val defaultSideTabs: List<AppTab> = listOf(FEEDS, QUEUE, SEARCH, SETTINGS)

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

enum class FeedSortOrder(val label: String) {
    ALPHABETICAL("Sort: A–Z"),
    UNREAD_FIRST("Sort: Unread First"),
}

data class FeedItem(
    val id: String,
    val title: String,
    val url: String,
    val siteUrl: String?,
    val feedType: FeedType,
    val unreadCount: Int,
    val lastPolled: String? = null,
    val lastPolledBy: String? = null,
    val lastError: String? = null,
    val lastErrorBy: String? = null,
    val isActive: Boolean = true,
    val autoDownloadEpisodes: Boolean = false,
    val customPlaybackSpeed: Float? = null,
) {
    val isArchived: Boolean = !isActive

    val displayDomain: String = extractCleanDomain(siteUrl?.takeIf { it.isNotBlank() } ?: url)

    val faviconUrl: String? = resolveOriginFaviconUrl(siteUrl?.takeIf { it.isNotBlank() } ?: url)

    val fallbackFaviconUrl: String? =
        resolveFallbackFaviconUrl(siteUrl?.takeIf { it.isNotBlank() } ?: url)

    val hasPollError: Boolean = !lastError.isNullOrBlank()
}

fun FeedItem.isCrossPeerRescueError(localPeerId: String): Boolean =
    hasPollError && !lastErrorBy.isNullOrBlank() && lastErrorBy != localPeerId

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
    val audioUrl: String? = null,
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
            ContentType.VIDEO,
            ContentType.AUDIO -> true
        }

    val formattedPublishedLabel: String = formatRelativeTimestamp(publishedAt ?: createdAt)

    val formattedReadAtLabel: String? =
        readAt?.trim()?.takeIf { it.isNotBlank() }?.let { formatRelativeTimestamp(it) }

    val formattedListeningHistoryLabel: String?
        get() {
            if (contentType != ContentType.AUDIO) return null
            val hasPlayed = !readAt.isNullOrBlank() || readProgress > 0.0 || isArchived
            if (!hasPlayed) return null
            val whenLabel = formattedReadAtLabel
            val pct = (readProgress * PERCENT_SCALE).toInt().coerceIn(1, MAX_PARTIAL_PCT)
            return when {
                readProgress >= COMPLETED_PROGRESS_THRESHOLD || isArchived ->
                    whenLabel?.let { "Played $it · Completed" } ?: "Completed"
                readProgress > 0.0 ->
                    whenLabel?.let { "Played $it · $pct% listened" } ?: "$pct% listened"
                whenLabel != null -> "Played $whenLabel"
                else -> null
            }
        }

    val displayDomain: String = extractCleanDomain(url)

    val faviconUrl: String? = resolveOriginFaviconUrl(url)

    val fallbackFaviconUrl: String? = resolveFallbackFaviconUrl(url)

    val readEstimateLabel: String =
        when (contentType) {
            ContentType.VIDEO -> duration ?: "Video"
            ContentType.PDF -> duration ?: "PDF"
            ContentType.AUDIO -> formatAudioDurationEstimate(duration) ?: "Audio"
            ContentType.ARTICLE ->
                formatArticleMinutes(contentText ?: contentHtml ?: summary.orEmpty())
        }
}

internal fun formatAudioDurationEstimate(raw: String?): String? {
    val clean = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val totalSeconds =
        clean.toLongOrNull()
            ?: run {
                val parts = clean.split(':').map { it.trim().toLongOrNull() ?: return clean }
                when (parts.size) {
                    2 -> parts[0] * 60L + parts[1]
                    3 -> parts[0] * 3_600L + parts[1] * 60L + parts[2]
                    else -> return clean
                }
            }
    if (totalSeconds <= 0L) return clean
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MIN
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
        hours > 0L -> "${hours}h"
        minutes > 0L -> "${minutes}m"
        else -> "${totalSeconds}s"
    }
}

internal fun extractCleanDomain(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return ""
    val host = extractOriginUri(trimmed)?.host?.lowercase(Locale.US)?.removePrefix("www.")
    return host?.takeIf { it.isNotBlank() } ?: trimmed
}

internal fun resolveOriginFaviconUrl(rawUrl: String): String? {
    val uri = extractOriginUri(rawUrl) ?: return null
    val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
    val host = uri.host?.lowercase(Locale.US) ?: return null
    if (scheme != "https" && scheme != "http" || host.isBlank()) return null
    val portPart = if (uri.port != -1) ":${uri.port}" else ""
    return "$scheme://$host$portPart/apple-touch-icon.png"
}

internal fun resolveFallbackFaviconUrl(rawUrl: String): String? {
    val host =
        extractOriginUri(rawUrl)?.host?.lowercase(Locale.US)?.removePrefix("www.") ?: return null
    if (host.isBlank() || !host.contains('.')) return null
    return "https://icons.duckduckgo.com/ip3/$host.ico"
}

private fun extractOriginUri(rawUrl: String): URI? {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return null
    val withoutQueryOrFragment = trimmed.substringBefore('#').substringBefore('?')
    val schemeSeparatorIndex = withoutQueryOrFragment.indexOf("://")
    val originCandidate =
        if (schemeSeparatorIndex != -1) {
            val slashIndex = withoutQueryOrFragment.indexOf('/', schemeSeparatorIndex + 3)
            if (slashIndex != -1) {
                withoutQueryOrFragment.substring(0, slashIndex)
            } else {
                withoutQueryOrFragment
            }
        } else {
            withoutQueryOrFragment
        }
    return runCatching { URI.create(originCandidate) }.getOrNull()
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
    val wordCount = countPlainTextWords(text)
    val minutes = (wordCount / ARTICLE_WORDS_PER_MINUTE).coerceAtLeast(1)
    return "${minutes}m"
}

private fun countPlainTextWords(text: String): Int {
    var wordCount = 0
    var inWord = false
    var i = 0
    val len = text.length
    while (i < len) {
        val ch = text[i]
        val tagEnd = findClosingHtmlTagIndex(text, i, len)
        if (tagEnd != -1) {
            inWord = false
            i = tagEnd + 1
        } else {
            if (ch.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                wordCount++
            }
            i++
        }
    }
    return wordCount
}

private fun findClosingHtmlTagIndex(text: String, start: Int, len: Int): Int {
    if (text[start] != '<' || start + 1 >= len) return -1
    val next = text[start + 1]
    val isTagStart = next.isLetter() || next == '/' || next == '!' || next == '?'
    if (!isTagStart) return -1
    val minCloseIdx = start + 2
    val maxCloseIdx = start + MAX_HTML_TAG_SCAN_CHARS
    val closeIdx = text.indexOf('>', minCloseIdx)
    return if (closeIdx in minCloseIdx..maxCloseIdx) closeIdx else -1
}

private const val MAX_HTML_TAG_SCAN_CHARS = 2_048
private const val SECONDS_PER_MIN = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_DAY = 86_400L
private const val SECONDS_PER_MONTH = 2_592_000L
private const val ARTICLE_WORDS_PER_MINUTE = 180
private const val COMPLETED_PROGRESS_THRESHOLD = 0.98
private const val PERCENT_SCALE = 100
private const val MAX_PARTIAL_PCT = 99
private val IMG_SRC_REGEX =
    Regex("""<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
private val SHORT_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d", Locale.US)

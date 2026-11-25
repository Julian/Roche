package com.grayvines.roche.model

import com.grayvines.roche.core.DiscussionLink
import com.grayvines.roche.core.formatArticleMinutes
import com.grayvines.roche.core.formatAudioDurationEstimate
import com.grayvines.roche.core.inferContentTypeWireValue
import com.grayvines.roche.core.isHttpOrHttpsUrl
import com.grayvines.roche.core.isPublicHost
import com.grayvines.roche.core.isPublicIpAddress
import com.grayvines.roche.core.parseDurationToSecondsLong
import com.grayvines.roche.core.parseIsoInstant
import com.grayvines.roche.data.catalog.EntryKind
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun inferContentType(url: String): ContentType =
    ContentType.fromWireValue(inferContentTypeWireValue(url))

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

@kotlinx.serialization.Serializable
enum class InboxFilter(val label: String) {
    ALL("All"),
    UNREAD("New"),
    FINISHED("Finished"),
    ARTICLES("Articles"),
    PAPERS("Papers"),
    VIDEOS("Videos"),
    PODCASTS("Podcasts"),
    AUDIO("Audio"),
    STARRED("Starred"),
    ARCHIVED("Dismissed");

    companion object {
        /** Filters shown in the Inbox strip (Starred lives on the dedicated Stars tab). */
        val inboxStripFilters: List<InboxFilter> =
            listOf(UNREAD, FINISHED, ARCHIVED, ALL, ARTICLES, PAPERS, VIDEOS, PODCASTS, AUDIO)
    }
}

@kotlinx.serialization.Serializable
enum class AppTab(val label: String) {
    HOME("Home"),
    FEEDS("Feeds"),
    BOARDS("Boards"),
    LISTS("Lists"),
    DIARY("Diary"),
    QUEUE("Queue"),
    STARRED("Stars"),
    INBOX("Inbox"),
    OFFLINE("Offline"),
    SETTINGS("Settings");

    companion object {
        const val BOTTOM_TAB_COUNT = 5
        const val SIDE_TAB_COUNT = BOTTOM_TAB_COUNT
        private const val LEGACY_OUTER_SLOT_COUNT = 4
        private const val CENTER_SLOT_INDEX = 2

        val bottomTabCandidates: List<AppTab> =
            listOf(HOME, INBOX, BOARDS, LISTS, FEEDS, STARRED, DIARY, QUEUE, OFFLINE, SETTINGS)
        val sideTabCandidates: List<AppTab> = bottomTabCandidates

        val defaultBottomTabs: List<AppTab> = listOf(HOME, INBOX, LISTS, FEEDS, STARRED)
        val defaultSideTabs: List<AppTab> = defaultBottomTabs

        private val settingsReachableTabs: Set<AppTab> = setOf(HOME, SETTINGS)

        fun hasSettingsAccessTab(tabs: List<AppTab>): Boolean = tabs.any {
            it in settingsReachableTabs
        }

        fun normalizeBottomTabs(tabs: List<AppTab>): List<AppTab> {
            val distinctValid = tabs.filter { it in bottomTabCandidates }.distinct()
            val sized =
                when {
                    distinctValid.size >= BOTTOM_TAB_COUNT -> {
                        distinctValid.take(BOTTOM_TAB_COUNT).toMutableList()
                    }
                    distinctValid.size == LEGACY_OUTER_SLOT_COUNT -> {
                        val centerFill =
                            (listOf(LISTS) + defaultBottomTabs + bottomTabCandidates).first {
                                it !in distinctValid
                            }
                        distinctValid.toMutableList().apply { add(CENTER_SLOT_INDEX, centerFill) }
                    }
                    else -> {
                        val result = distinctValid.toMutableList()
                        for (fallback in defaultBottomTabs + bottomTabCandidates) {
                            if (result.size >= BOTTOM_TAB_COUNT) break
                            if (fallback !in result) {
                                result.add(fallback)
                            }
                        }
                        result.take(BOTTOM_TAB_COUNT).toMutableList()
                    }
                }
            if (!hasSettingsAccessTab(sized) && sized.isNotEmpty()) {
                sized[sized.lastIndex] = SETTINGS
            }
            return sized
        }

        fun normalizeSideTabs(tabs: List<AppTab>): List<AppTab> = normalizeBottomTabs(tabs)
    }
}

enum class FeedSortOrder(val label: String) {
    ALPHABETICAL("Sort: A–Z"),
    UNREAD_FIRST("Sort: New First"),
}

enum class FeedListFilter(val label: String) {
    ALL("All"),
    UNREAD("New"),
    ERRORS("Errors"),
    RSS("RSS"),
    PODCAST("Podcasts"),
    YOUTUBE("YouTube"),
    ARXIV("arXiv"),
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
    val consecutiveFailures: Int = 0,
    val isActive: Boolean = true,
    val autoDownloadEpisodes: Boolean = false,
    val customPlaybackSpeed: Float? = null,
) {
    val isPaused: Boolean = !isActive
    val isArchived: Boolean = !isActive

    val displayDomain: String by
        lazy(LazyThreadSafetyMode.PUBLICATION) {
            extractCleanDomain(siteUrl?.takeIf { it.isNotBlank() } ?: url)
        }

    val faviconUrl: String? by
        lazy(LazyThreadSafetyMode.PUBLICATION) {
            resolveOriginFaviconUrl(siteUrl?.takeIf { it.isNotBlank() } ?: url)
        }

    val fallbackFaviconUrl: String? by
        lazy(LazyThreadSafetyMode.PUBLICATION) {
            resolveFallbackFaviconUrl(siteUrl?.takeIf { it.isNotBlank() } ?: url)
        }

    val hasPollError: Boolean = !lastError.isNullOrBlank()
}

fun FeedItem.isCrossPeerRescueError(localPeerId: String): Boolean =
    hasPollError &&
        consecutiveFailures <= 1 &&
        !lastErrorBy.isNullOrBlank() &&
        lastErrorBy != localPeerId

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
    val inFeedInbox: Boolean = feedId != null,
    val entryKind: EntryKind = EntryKind.ARTICLE,
    val isFinished: Boolean = false,
    val discussions: List<DiscussionLink> = emptyList(),
    val releaseYear: Int? = null,
) {
    val isPlayableAudio: Boolean
        get() = contentType == ContentType.AUDIO || !audioUrl.isNullOrBlank()

    val isPodcastEpisode: Boolean
        get() = entryKind == EntryKind.PODCAST_EPISODE

    val isRead: Boolean
        get() = !readAt.isNullOrBlank()

    val isArchived: Boolean
        get() = status == ItemStatus.ARCHIVED

    val isDismissed: Boolean
        get() = isArchived && !isFinished

    val isNew: Boolean
        get() = !isArchived && !isFinished

    val isUnread: Boolean
        get() = status == ItemStatus.UNREAD && !isRead && !isFinished

    val effectiveThumbnailUrl: String? by
        lazy(LazyThreadSafetyMode.PUBLICATION) {
            thumbnailUrl?.trim()?.takeIf { it.isNotBlank() && !isNonThumbnailUrl(it) }
                ?: extractFirstImageUrlFromHtml(contentHtml, baseUrl = url)
        }

    val showsThumbnailPreview: Boolean by
        lazy(LazyThreadSafetyMode.PUBLICATION) {
            when (contentType) {
                ContentType.ARTICLE ->
                    !thumbnailMediaId.isNullOrBlank() || !effectiveThumbnailUrl.isNullOrBlank()
                ContentType.PDF,
                ContentType.VIDEO,
                ContentType.AUDIO -> true
            }
        }

    val formattedPublishedLabel: String by
        lazy(LazyThreadSafetyMode.PUBLICATION) { formatRelativeTimestamp(publishedAt ?: createdAt) }

    val formattedReadAtLabel: String? by
        lazy(LazyThreadSafetyMode.PUBLICATION) {
            readAt?.trim()?.takeIf { it.isNotBlank() }?.let { formatRelativeTimestamp(it) }
        }

    val formattedListeningHistoryLabel: String?
        get() {
            if (!isPlayableAudio) return null
            val hasPlayed = !readAt.isNullOrBlank() || readProgress > 0.0 || isFinished
            if (!hasPlayed) return null
            val whenLabel = formattedReadAtLabel
            val pct = (readProgress * PERCENT_SCALE).toInt().coerceIn(1, MAX_PARTIAL_PCT)
            return when {
                isFinished || readProgress >= COMPLETED_PROGRESS_THRESHOLD ->
                    whenLabel?.let { "Played $it · Finished" } ?: "Finished"
                readProgress > 0.0 ->
                    whenLabel?.let { "Played $it · $pct% listened" } ?: "$pct% listened"
                whenLabel != null -> "Played $whenLabel"
                else -> null
            }
        }

    val displayDomain: String by lazy(LazyThreadSafetyMode.PUBLICATION) { extractCleanDomain(url) }

    val faviconUrl: String? by
        lazy(LazyThreadSafetyMode.PUBLICATION) { resolveOriginFaviconUrl(url) }

    val fallbackFaviconUrl: String? by
        lazy(LazyThreadSafetyMode.PUBLICATION) { resolveFallbackFaviconUrl(url) }

    val readEstimateLabel: String by
        lazy(LazyThreadSafetyMode.PUBLICATION) {
            when (contentType) {
                ContentType.VIDEO -> duration ?: "Video"
                ContentType.PDF -> duration ?: "PDF"
                ContentType.AUDIO -> formatAudioDurationEstimate(duration) ?: "Audio"
                ContentType.ARTICLE -> formatArticleReadEstimate(this)
            }
        }
}

internal fun formatArticleReadEstimate(entry: InboxEntry): String {
    val rawDuration = entry.duration?.takeIf { it.isNotBlank() }
    return when {
        rawDuration != null &&
            entry.isPlayableAudio &&
            parseDurationToSecondsLong(rawDuration) != null ->
            formatAudioDurationEstimate(rawDuration) ?: rawDuration
        rawDuration != null -> rawDuration
        else ->
            formatArticleMinutes(entry.contentText ?: entry.contentHtml ?: entry.summary.orEmpty())
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
        extractOriginUri(rawUrl)?.host?.lowercase(Locale.US)?.trimEnd('.')?.removePrefix("www.")
            ?: return null
    val isPublicDomain =
        host.isNotBlank() &&
            host.contains('.') &&
            isPublicHost(host) &&
            !isPublicIpAddress(host) &&
            !host.startsWith("[")
    return if (isPublicDomain) "https://icons.duckduckgo.com/ip3/$host.ico" else null
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
        if (isHttpOrHttpsUrl(candidate)) {
            candidate
        } else if (!baseUrl.isNullOrBlank()) {
            runCatching { URI.create(baseUrl.trim()).resolve(candidate).toString() }.getOrNull()
        } else {
            null
        }
    return resolved?.takeIf { isHttpOrHttpsUrl(it) && !isNonThumbnailUrl(it) }
}

private fun hasDisallowedImageScheme(url: String): Boolean =
    url.startsWith("data:", ignoreCase = true) ||
        url.startsWith("javascript:", ignoreCase = true) ||
        url.startsWith("mailto:", ignoreCase = true) ||
        url.startsWith("blob:", ignoreCase = true)

internal fun extractFirstImageUrlFromHtml(html: String?, baseUrl: String? = null): String? {
    if (html.isNullOrBlank() || !html.contains("<img", ignoreCase = true)) return null
    for (match in IMG_SRC_REGEX.findAll(html)) {
        val rawSrc = match.groupValues[1].ifEmpty { match.groupValues[2] }
        resolveArticleImageUrl(rawSrc, baseUrl)?.let {
            return it
        }
    }
    return null
}

internal fun extractAllImageUrlsFromHtml(html: String?, baseUrl: String? = null): List<String> {
    if (html.isNullOrBlank() || !html.contains("<img", ignoreCase = true)) return emptyList()
    return IMG_SRC_REGEX.findAll(html)
        .mapNotNull { match ->
            val rawSrc = match.groupValues[1].ifEmpty { match.groupValues[2] }
            resolveArticleImageUrl(rawSrc, baseUrl)
        }
        .distinct()
        .toList()
}

internal fun formatRelativeTimestamp(raw: String?, now: Instant = Instant.now()): String {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isBlank()) return "recent"
    val instant = parseIsoInstant(trimmed) ?: return trimmed
    val seconds = Duration.between(instant, now).seconds.coerceAtLeast(0L)
    return when {
        seconds < SECONDS_PER_MIN -> "now"
        seconds < SECONDS_PER_HOUR -> "${seconds / SECONDS_PER_MIN}m"
        seconds < SECONDS_PER_DAY -> "${seconds / SECONDS_PER_HOUR}h"
        seconds < SECONDS_PER_MONTH -> "${seconds / SECONDS_PER_DAY}d"
        else -> SHORT_DATE_FORMATTER.format(instant.atOffset(ZoneOffset.UTC))
    }
}

private const val SECONDS_PER_MIN = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_DAY = 86_400L
private const val SECONDS_PER_MONTH = 2_592_000L
private const val COMPLETED_PROGRESS_THRESHOLD = 0.98
private const val PERCENT_SCALE = 100
private const val MAX_PARTIAL_PCT = 99
private val IMG_SRC_REGEX =
    Regex(
        """<img\b[^<>]{0,4096}?\bsrc\s*=\s*(?:"([^"<>]{1,4096})"|'([^'<>]{1,4096})')""",
        RegexOption.IGNORE_CASE,
    )
private val SHORT_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d", Locale.US)

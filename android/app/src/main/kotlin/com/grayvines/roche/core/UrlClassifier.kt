package com.grayvines.roche.core

import java.net.URI
import java.util.Locale

internal const val DEFAULT_SAVED_HOST = "Saved Link"
private const val LOCAL_TAG_PREFIX = "tag-"
private const val SERVER_TAG_PREFIX = "tag-srv-"
private const val YOUTUBE_CHANNEL_ID_LENGTH = 24
private const val YOUTUBE_CHANNEL_PREFIX = "UC"
private const val ARXIV_RSS_HOST = "rss.arxiv.org"
private const val ARXIV_EXPORT_HOST = "export.arxiv.org"
private const val ARXIV_API_QUERY_PATH = "/api/query"
private const val ARXIV_RSS_PATH_PREFIX = "/rss/"
private const val YOUTUBE_FEED_PATH = "/feeds/videos.xml"
private const val YOUTUBE_FEED_URL_PREFIX = "https://www.youtube.com/feeds/videos.xml?channel_id="
private val ARXIV_HOSTS = setOf("arxiv.org", "www.arxiv.org", ARXIV_RSS_HOST, ARXIV_EXPORT_HOST)
private val ARXIV_RSS_PATH_HOSTS = setOf("arxiv.org", "www.arxiv.org", ARXIV_EXPORT_HOST)
private val YOUTUBE_WEB_HOSTS =
    setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
private val YOUTUBE_HOSTS = YOUTUBE_WEB_HOSTS + setOf("youtu.be", "www.youtu.be")
private val FEED_PATH_SUFFIXES = listOf(".xml", ".rss", ".atom", "/rss", "/feed", "/atom")
private val AUDIO_EXTENSIONS =
    listOf(".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wav", ".flac")
private val YOUTUBE_CHANNEL_URL_REGEX =
    Regex(
        """^https?://(?:www\.|m\.)?youtube\.com/channel/([A-Za-z0-9_-]{24})(?:[/?#].*)?$""",
        RegexOption.IGNORE_CASE,
    )
private val YOUTUBE_CHANNEL_PATH_REGEX =
    Regex("""/channel/([A-Za-z0-9_-]{24})""", RegexOption.IGNORE_CASE)

internal fun isLocalTagId(id: String): Boolean =
    id.startsWith(LOCAL_TAG_PREFIX) && !id.startsWith(SERVER_TAG_PREFIX)

internal fun normalizeTagName(raw: String): String =
    raw.replace(",", "").trim().lowercase(Locale.ROOT).trimStart('#')

internal fun parseTagCsv(tagCsv: String): List<String> =
    tagCsv.split(',').map { normalizeTagName(it) }.filter { it.isNotBlank() }.distinct()

internal fun isYoutubeChannelId(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.length == YOUTUBE_CHANNEL_ID_LENGTH &&
        trimmed.startsWith(YOUTUBE_CHANNEL_PREFIX) &&
        trimmed.all { it.isLetterOrDigit() || it == '_' || it == '-' }
}

internal fun extractYoutubeChannelId(rawInput: String, matchPathOnly: Boolean = false): String? {
    val trimmed = rawInput.trim()
    if (isYoutubeChannelId(trimmed)) return trimmed
    val match =
        if (matchPathOnly) {
            YOUTUBE_CHANNEL_PATH_REGEX.find(trimmed)
        } else {
            YOUTUBE_CHANNEL_URL_REGEX.matchEntire(trimmed)
        }
    return match?.groupValues?.getOrNull(1)?.takeIf(::isYoutubeChannelId)
}

internal fun buildYoutubeChannelFeedUrl(channelId: String): String =
    "$YOUTUBE_FEED_URL_PREFIX${channelId.trim()}"

internal fun isYoutubeChannelFeedInput(url: String): Boolean = extractYoutubeChannelId(url) != null

internal fun isArxivUrl(url: String): Boolean = isArxivHost(parseUrlParts(url))

internal fun isYoutubeUrl(url: String): Boolean = isYoutubeHost(parseUrlParts(url))

internal fun isLikelyFeedUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (isYoutubeChannelFeedInput(trimmed)) return true
    val parts = parseUrlParts(trimmed)
    val cleanPath = parts?.second ?: fallbackCleanPath(trimmed)
    return isYoutubeFeedUrl(parts) ||
        isArxivFeedUrl(parts) ||
        FEED_PATH_SUFFIXES.any { cleanPath.endsWith(it) }
}

internal fun inferFeedTypeWireValue(url: String): String {
    val parts = parseUrlParts(url)
    return when {
        isArxivHost(parts) -> "arxiv"
        isYoutubeChannelFeedInput(url) || isYoutubeHost(parts) -> "youtube"
        else -> "rss"
    }
}

internal fun isDirectAudioUrl(url: String): Boolean {
    val cleanPath = parseUrlParts(url)?.second ?: fallbackCleanPath(url)
    return AUDIO_EXTENSIONS.any { ext -> cleanPath.endsWith(ext) }
}

internal fun inferContentTypeWireValue(url: String): String {
    val parts = parseUrlParts(url)
    val cleanPath = parts?.second ?: fallbackCleanPath(url)
    return when {
        isArxivHost(parts) || cleanPath.endsWith(".pdf") || cleanPath.endsWith("/pdf") -> "pdf"
        isYoutubeHost(parts) -> "video"
        AUDIO_EXTENSIONS.any { ext -> cleanPath.endsWith(ext) } -> "audio"
        else -> "article"
    }
}

internal fun extractHostTitle(url: String): String {
    val trimmed = url.trim()
    val parts = parseUrlParts(trimmed)
    if (isYoutubeChannelFeedInput(trimmed) || isYoutubeFeedUrl(parts)) {
        return "YouTube Channel"
    }
    val parsedHost =
        parts?.first?.removePrefix("www.")
            ?: parseUriOrNull(trimmed)
                ?.host
                ?.lowercase(Locale.ROOT)
                ?.trimEnd('.')
                ?.removePrefix("www.")
    return if (!parsedHost.isNullOrBlank()) parsedHost else DEFAULT_SAVED_HOST
}

private fun parseUrlParts(rawUrl: String): Pair<String, String>? {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return null
    val uri =
        parseUriOrNull(trimmed)
            ?: parseUriOrNull(trimmed.substringBefore('#').substringBefore('?'))
            ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme != SCHEME_HTTP && scheme != SCHEME_HTTPS) return null
    val host = uri.host?.trim()?.lowercase(Locale.ROOT)?.trimEnd('.')
    if (host.isNullOrEmpty()) return null
    val normalizedPath = uri.path?.lowercase(Locale.ROOT)?.trimEnd('/').orEmpty()
    return host to normalizedPath
}

private fun parseUriOrNull(raw: String): URI? = runCatching {
    URI(raw.replace(" ", "%20"))
}
    .getOrNull()

private fun fallbackCleanPath(url: String): String =
    url.trim().substringBefore('#').substringBefore('?').trimEnd('/').lowercase(Locale.ROOT)

private fun isArxivHost(parts: Pair<String, String>?): Boolean =
    parts != null && parts.first in ARXIV_HOSTS

private fun isYoutubeHost(parts: Pair<String, String>?): Boolean =
    parts != null && parts.first in YOUTUBE_HOSTS

private fun isYoutubeFeedUrl(parts: Pair<String, String>?): Boolean =
    parts != null && parts.first in YOUTUBE_WEB_HOSTS && parts.second == YOUTUBE_FEED_PATH

private fun isArxivFeedUrl(parts: Pair<String, String>?): Boolean =
    when {
        parts == null -> false
        parts.first == ARXIV_RSS_HOST -> true
        parts.first == ARXIV_EXPORT_HOST && parts.second == ARXIV_API_QUERY_PATH -> true
        parts.first in ARXIV_RSS_PATH_HOSTS -> parts.second.startsWith(ARXIV_RSS_PATH_PREFIX)
        else -> false
    }

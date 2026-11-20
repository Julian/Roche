package com.grayvines.roche.core

import java.net.URI
import java.util.Locale

internal const val DEFAULT_SAVED_HOST = "Saved Link"
private const val LOCAL_TAG_PREFIX = "tag-"
private const val SERVER_TAG_PREFIX = "tag-srv-"
private const val YOUTUBE_CHANNEL_ID_LENGTH = 24
private const val YOUTUBE_CHANNEL_PREFIX = "UC"
private const val MAX_YOUTUBE_VIDEO_ID_LEN = 64
private const val ARXIV_RSS_HOST = "rss.arxiv.org"
private const val ARXIV_EXPORT_HOST = "export.arxiv.org"
private const val ARXIV_API_QUERY_PATH = "/api/query"
private const val ARXIV_RSS_PATH_PREFIX = "/rss/"
private const val ARXIV_ABS_PATH_PREFIX = "/abs/"
private const val ARXIV_PDF_PATH_PREFIX = "/pdf/"
private const val YOUTUBE_FEED_PATH = "/feeds/videos.xml"
private const val YOUTUBE_FEED_URL_PREFIX = "https://www.youtube.com/feeds/videos.xml?channel_id="
private val ARXIV_HOSTS = setOf("arxiv.org", "www.arxiv.org", ARXIV_RSS_HOST, ARXIV_EXPORT_HOST)
private val ARXIV_RSS_PATH_HOSTS = setOf("arxiv.org", "www.arxiv.org", ARXIV_EXPORT_HOST)
private val YOUTUBE_WEB_HOSTS =
    setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
private val YOUTUBE_SHORT_HOSTS = setOf("youtu.be", "www.youtu.be")
private val YOUTUBE_HOSTS = YOUTUBE_WEB_HOSTS + YOUTUBE_SHORT_HOSTS
private val YOUTUBE_VIDEO_PREFIX_SEGMENTS = setOf("shorts", "embed", "v")
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
private val YOUTUBE_VIDEO_ID_REGEX = Regex("""^[A-Za-z0-9_-]{1,$MAX_YOUTUBE_VIDEO_ID_LEN}$""")
private val ARXIV_ID_REGEX = Regex("""^[A-Za-z0-9._/-]+$""")

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

internal fun isArxivPaperUrl(url: String): Boolean {
    val parts = parseUrlParts(url)
    if (parts == null || !isArxivPaperPath(parts)) return false
    val arxivId = extractArxivIdentifier(url)
    return arxivId != null && isValidArxivId(arxivId)
}

internal fun isYoutubeUrl(url: String): Boolean = isYoutubeHost(parseUrlParts(url))

internal fun isYoutubeFeedUrl(url: String): Boolean = isYoutubeFeedParts(parseUrlParts(url))

internal fun isValidYoutubeVideoId(candidate: String): Boolean =
    YOUTUBE_VIDEO_ID_REGEX.matches(candidate)

internal fun extractYoutubeVideoId(url: String): String? {
    val uri = parseHttpUriOrNull(url) ?: return null
    val host = uri.host?.trim()?.lowercase(Locale.ROOT)?.trimEnd('.').orEmpty()
    val pathSegments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
    return when {
        host in YOUTUBE_WEB_HOSTS -> extractYoutubeWebVideoId(uri, pathSegments)
        host in YOUTUBE_SHORT_HOSTS -> pathSegments.firstOrNull()?.takeIf(::isValidYoutubeVideoId)
        else -> null
    }
}

internal fun isLikelyFeedUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (isYoutubeChannelFeedInput(trimmed)) return true
    val parts = parseUrlParts(trimmed)
    val cleanPath = parts?.second ?: fallbackCleanPath(trimmed)
    return isYoutubeFeedParts(parts) ||
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
        isArxivPaperUrl(url) -> "paper"
        extractYoutubeVideoId(url) != null -> "video"
        cleanPath.endsWith(".pdf") || cleanPath.endsWith("/pdf") -> "pdf"
        AUDIO_EXTENSIONS.any { ext -> cleanPath.endsWith(ext) } -> "audio"
        else -> "article"
    }
}

internal fun extractHostTitle(url: String): String {
    val trimmed = url.trim()
    val parts = parseUrlParts(trimmed)
    if (isYoutubeChannelFeedInput(trimmed) || isYoutubeFeedParts(parts)) {
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

private fun parseHttpUriOrNull(rawUrl: String): URI? {
    val trimmed = rawUrl.trim()
    val uri = if (trimmed.isEmpty()) null else parseUriOrNull(trimmed)
    val scheme = uri?.scheme?.lowercase(Locale.ROOT)
    return if (scheme == SCHEME_HTTP || scheme == SCHEME_HTTPS) uri else null
}

private fun parseUriOrNull(raw: String): URI? = runCatching {
    URI(raw.replace(" ", "%20"))
}
    .getOrNull()

private fun fallbackCleanPath(url: String): String =
    url.trim().substringBefore('#').substringBefore('?').trimEnd('/').lowercase(Locale.ROOT)

private fun isArxivHost(parts: Pair<String, String>?): Boolean =
    parts != null && parts.first in ARXIV_HOSTS

private fun isArxivPaperPath(parts: Pair<String, String>): Boolean =
    isArxivHost(parts) &&
        (parts.second.startsWith(ARXIV_ABS_PATH_PREFIX) ||
            parts.second.startsWith(ARXIV_PDF_PATH_PREFIX))

private fun isValidArxivId(arxivId: String): Boolean =
    ".." !in arxivId && arxivId.count { it == '/' } <= 1 && ARXIV_ID_REGEX.matches(arxivId)

private fun isYoutubeHost(parts: Pair<String, String>?): Boolean =
    parts != null && parts.first in YOUTUBE_HOSTS

private fun isYoutubeFeedParts(parts: Pair<String, String>?): Boolean =
    parts != null && parts.first in YOUTUBE_WEB_HOSTS && parts.second == YOUTUBE_FEED_PATH

private fun extractYoutubeWebVideoId(uri: URI, pathSegments: List<String>): String? {
    val rawPath = uri.path.orEmpty()
    return when {
        rawPath == "/watch" -> extractQueryParam(uri.rawQuery, "v")?.takeIf(::isValidYoutubeVideoId)
        pathSegments.size >= 2 && pathSegments[0] in YOUTUBE_VIDEO_PREFIX_SEGMENTS ->
            pathSegments[1].takeIf(::isValidYoutubeVideoId)
        else -> null
    }
}

private fun isArxivFeedUrl(parts: Pair<String, String>?): Boolean =
    when {
        parts == null -> false
        parts.first == ARXIV_RSS_HOST -> true
        parts.first == ARXIV_EXPORT_HOST && parts.second == ARXIV_API_QUERY_PATH -> true
        parts.first in ARXIV_RSS_PATH_HOSTS -> parts.second.startsWith(ARXIV_RSS_PATH_PREFIX)
        else -> false
    }

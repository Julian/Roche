package com.grayvines.roche.data

import com.grayvines.roche.model.ContentType
import java.net.URI
import java.util.UUID

internal const val DEFAULT_SAVED_HOST = "Saved Link"
private const val LOCAL_TAG_PREFIX = "tag-"
private const val SERVER_TAG_PREFIX = "tag-srv-"
private const val YOUTUBE_CHANNEL_ID_LENGTH = 24
private const val YOUTUBE_CHANNEL_PREFIX = "UC"
private val YOUTUBE_CHANNEL_URL_REGEX =
    Regex(
        """^https?://(?:www\.|m\.)?youtube\.com/channel/([A-Za-z0-9_-]{24})(?:[/?#].*)?$""",
        RegexOption.IGNORE_CASE,
    )

internal fun newTagId(): String = "$LOCAL_TAG_PREFIX${UUID.randomUUID()}"

internal fun isLocalTagId(id: String): Boolean =
    id.startsWith(LOCAL_TAG_PREFIX) && !id.startsWith(SERVER_TAG_PREFIX)

internal fun normalizeTagName(raw: String): String =
    raw.replace(",", "").trim().lowercase().trimStart('#')

internal fun parseTagCsv(tagCsv: String): List<String> =
    tagCsv.split(',').map { normalizeTagName(it) }.filter { it.isNotBlank() }.distinct()

internal fun isYoutubeChannelId(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.length == YOUTUBE_CHANNEL_ID_LENGTH &&
        trimmed.startsWith(YOUTUBE_CHANNEL_PREFIX) &&
        trimmed.all { it.isLetterOrDigit() || it == '_' || it == '-' }
}

internal fun isYoutubeChannelFeedInput(url: String): Boolean {
    val trimmed = url.trim()
    if (isYoutubeChannelId(trimmed)) return true
    val channelId = YOUTUBE_CHANNEL_URL_REGEX.matchEntire(trimmed)?.groupValues?.getOrNull(1)
    return channelId != null && isYoutubeChannelId(channelId)
}

internal fun isLikelyFeedUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (isYoutubeChannelFeedInput(trimmed)) return true
    val lowerWithQuery = trimmed.lowercase().substringBefore('#')
    val cleanPath = lowerWithQuery.substringBefore('?').trimEnd('/')
    return cleanPath.endsWith(".xml") ||
        cleanPath.endsWith(".rss") ||
        cleanPath.endsWith(".atom") ||
        cleanPath.endsWith("/rss") ||
        cleanPath.endsWith("/feed") ||
        cleanPath.endsWith("/atom") ||
        cleanPath.contains("rss.arxiv.org") ||
        cleanPath.contains("arxiv.org/rss/") ||
        lowerWithQuery.contains("export.arxiv.org/api/query") ||
        cleanPath.contains("feeds/videos.xml")
}

internal fun inferFeedTypeWireValue(url: String): String =
    when {
        url.contains("arxiv.org", ignoreCase = true) -> "arxiv"
        isYoutubeChannelFeedInput(url) ||
            url.contains("youtube.com", ignoreCase = true) ||
            url.contains("youtu.be", ignoreCase = true) -> "youtube"
        else -> "rss"
    }

internal fun isDirectAudioUrl(url: String): Boolean {
    val cleanPath = url.trim().substringBefore('#').substringBefore('?').trimEnd('/').lowercase()
    return AUDIO_EXTENSIONS.any { ext -> cleanPath.endsWith(ext) }
}

private val AUDIO_EXTENSIONS =
    listOf(".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wav", ".flac")

internal fun inferContentType(url: String): ContentType {
    val cleanPath = url.trim().substringBefore('#').substringBefore('?').trimEnd('/')
    return when {
        url.contains("arxiv.org", ignoreCase = true) ||
            cleanPath.endsWith(".pdf", ignoreCase = true) ||
            cleanPath.endsWith("/pdf", ignoreCase = true) -> ContentType.PDF
        url.contains("youtube.com", ignoreCase = true) ||
            url.contains("youtu.be", ignoreCase = true) -> ContentType.VIDEO
        isDirectAudioUrl(url) -> ContentType.AUDIO
        else -> ContentType.ARTICLE
    }
}

internal fun extractHostTitle(url: String): String {
    val trimmed = url.trim()
    if (
        isYoutubeChannelFeedInput(trimmed) ||
            trimmed.contains("feeds/videos.xml", ignoreCase = true)
    ) {
        return "YouTube Channel"
    }
    val parsedHost = runCatching {
        URI.create(trimmed).host?.lowercase()?.removePrefix("www.")
    }
        .getOrNull()
    return if (!parsedHost.isNullOrBlank()) parsedHost else DEFAULT_SAVED_HOST
}

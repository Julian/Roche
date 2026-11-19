package com.grayvines.roche.core

import java.util.Locale
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

private const val MAX_HTML_TAG_SCAN_CHARS = 2_048
private const val ARTICLE_WORDS_PER_MINUTE = 180
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val MM_SS_PART_COUNT = 2
private const val HH_MM_SS_PART_COUNT = 3
private const val INVALID_DURATION = -1L

private val HTML_TAG_REGEX = Regex("<[^>]+>")
private val MULTI_WHITESPACE_REGEX = Regex("\\s+")
private val SYNTHETIC_ARTICLE_DURATION_REGEX = Regex("""^\d+m$""")

internal val coreJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

internal fun encodeCreatorsJson(creators: List<String>): String =
    coreJson.encodeToString(creators.map { it.trim() }.filter { it.isNotEmpty() })

internal fun decodeCreatorsJson(raw: String?): List<String> =
    raw?.trim()
        ?.takeIf { it.isNotEmpty() && it != "[]" }
        ?.let { text -> runCatching { coreJson.decodeFromString<List<String>>(text) }.getOrNull() }
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()

internal fun decodeHtmlEntities(raw: String): String = Parser.unescapeEntities(raw, false)

internal fun decodeHtmlToPlainText(raw: String): String = Jsoup.parse(raw).text()

internal fun stripHtmlTags(raw: String): String =
    decodeHtmlEntities(raw.replace(HTML_TAG_REGEX, " ")).replace(MULTI_WHITESPACE_REGEX, " ").trim()

internal fun formatArticleMinutes(text: String): String {
    val wordCount = countPlainTextWords(text)
    val minutes = (wordCount / ARTICLE_WORDS_PER_MINUTE).coerceAtLeast(1)
    return "${minutes}m"
}

internal fun resolveArticleDuration(
    existingDuration: String?,
    isArticle: Boolean,
    hasExtendedContent: Boolean,
    fallbackTextSource: String,
): String? {
    if (!isArticle) return existingDuration
    val existing = existingDuration?.trim()?.takeIf { it.isNotEmpty() }
    val hasExplicitUpstreamDuration =
        existing != null && !SYNTHETIC_ARTICLE_DURATION_REGEX.matches(existing)
    if (hasExplicitUpstreamDuration) return existingDuration
    if (!hasExtendedContent) return null
    return if (fallbackTextSource.isNotBlank()) formatArticleMinutes(fallbackTextSource) else null
}

internal fun parseDurationToSecondsLong(raw: String?): Long? {
    val clean = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val seconds =
        clean.toLongOrNull()
            ?: run {
                val parts = clean.split(':').map { it.trim().toLongOrNull() ?: INVALID_DURATION }
                when {
                    parts.any { it < 0L } -> INVALID_DURATION
                    parts.size == MM_SS_PART_COUNT -> parts[0] * SECONDS_PER_MINUTE + parts[1]
                    parts.size == HH_MM_SS_PART_COUNT ->
                        parts[0] * SECONDS_PER_HOUR + parts[1] * SECONDS_PER_MINUTE + parts[2]
                    else -> INVALID_DURATION
                }
            }
    return seconds.takeIf { it > 0L }
}

internal fun formatDurationSecondsLabel(seconds: Long): String? {
    if (seconds <= 0L) return null
    val minutes = seconds / SECONDS_PER_MINUTE
    val remSeconds = seconds % SECONDS_PER_MINUTE
    return "%d:%02d".format(Locale.US, minutes, remSeconds)
}

internal fun formatAudioDurationEstimate(raw: String?): String? {
    val clean = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val totalSeconds = parseDurationToSecondsLong(clean) ?: return clean
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
        hours > 0L -> "${hours}h"
        minutes > 0L -> "${minutes}m"
        else -> "${totalSeconds}s"
    }
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

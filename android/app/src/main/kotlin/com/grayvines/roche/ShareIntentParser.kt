package com.grayvines.roche

import android.content.Intent
import java.net.URI

data class SharedLinkPayload(
    val url: String,
    val title: String? = null,
)

internal data class MatchedHttpUrl(
    val url: String,
    val startIndex: Int,
)

fun extractSharedLink(
    action: String?,
    mimeType: String?,
    extraText: String?,
    extraSubject: String? = null,
    extraTitle: String? = null,
): SharedLinkPayload? {
    if (!isSupportedShareIntent(action, mimeType) || extraText.isNullOrBlank()) {
        return null
    }
    val match = findFirstHttpUrl(extraText) ?: return null
    val rawTitle =
        extraSubject?.trim()?.takeIf { it.isNotBlank() }
            ?: extraTitle?.trim()?.takeIf { it.isNotBlank() }
            ?: extractPrefixTitle(extraText, match.startIndex)
    return SharedLinkPayload(
        url = match.url,
        title = sanitizeCandidateTitle(rawTitle, match.url),
    )
}

private fun findFirstHttpUrl(extraText: String): MatchedHttpUrl? =
    HTTP_URL_CANDIDATE_REGEX.findAll(extraText)
        .mapNotNull { candidate ->
            val cleaned = cleanUrlToken(candidate.value)
            if (isValidHttpUrl(cleaned)) {
                MatchedHttpUrl(url = cleaned, startIndex = candidate.range.first)
            } else {
                null
            }
        }
        .firstOrNull()

private fun isSupportedShareIntent(action: String?, mimeType: String?): Boolean =
    action == Intent.ACTION_SEND &&
        mimeType != null &&
        mimeType.trim().lowercase().startsWith(TEXT_PLAIN_MIME_PREFIX)

internal fun cleanUrlToken(rawToken: String): String {
    var current = rawToken.trim().trimStart { it in LEADING_URL_WRAPPERS }
    while (current.isNotEmpty() && shouldStripTrailingChar(current)) {
        current = current.dropLast(1)
    }
    return current
}

private fun shouldStripTrailingChar(candidate: String): Boolean {
    val last = candidate.last()
    return if (last == ')') {
        candidate.count { it == ')' } > candidate.count { it == '(' }
    } else {
        last in TRAILING_URL_PUNCTUATION
    }
}

private fun isValidHttpUrl(candidate: String): Boolean {
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase()
    val host = uri.host
    return scheme in ALLOWED_HTTP_SCHEMES && !host.isNullOrBlank()
}

private fun extractPrefixTitle(extraText: String, urlStartIndex: Int): String? {
    val trimmedPrefix =
        extraText
            .substring(0, urlStartIndex)
            .trimEnd { it in LEADING_URL_WRAPPERS }
            .trim()
            .trimEnd { it.isWhitespace() || it in TITLE_TRAILING_SEPARATORS }
            .trim()
    val unwrapped = unwrapSurroundingDelimiters(trimmedPrefix)
    return unwrapped.takeIf { candidate -> candidate.any { it.isLetterOrDigit() } }
}

private fun unwrapSurroundingDelimiters(text: String): String {
    val matchingPair = SURROUNDING_TITLE_DELIMITERS.firstOrNull { (open, close) ->
        text.length >= MIN_DELIMITED_LENGTH &&
            text.startsWith(open) &&
            text.endsWith(close) &&
            close !in text.substring(1, text.length - 1)
    }
    return if (matchingPair != null) {
        text.substring(1, text.length - 1).trim()
    } else {
        text
    }
}

private fun sanitizeCandidateTitle(candidate: String?, extractedUrl: String): String? {
    val normalized = candidate?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val isDuplicateOrRawUrl =
        normalized.equals(extractedUrl, ignoreCase = true) ||
            isValidHttpUrl(cleanUrlToken(normalized))
    return if (isDuplicateOrRawUrl) null else normalized
}

private const val TEXT_PLAIN_MIME_PREFIX = "text/plain"
private const val MIN_DELIMITED_LENGTH = 2
private val ALLOWED_HTTP_SCHEMES = setOf("http", "https")
private val HTTP_URL_CANDIDATE_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
private val LEADING_URL_WRAPPERS =
    charArrayOf('(', '<', '[', '"', '\'', '\u2018', '\u2019', '\u201C', '\u201D')
private val TRAILING_URL_PUNCTUATION =
    charArrayOf(
        '.',
        ',',
        ';',
        ':',
        '!',
        '?',
        '>',
        ']',
        '"',
        '\'',
        '\u2018',
        '\u2019',
        '\u201C',
        '\u201D',
        '\u2026',
    )
private val TITLE_TRAILING_SEPARATORS = charArrayOf('-', '–', '—', ':', '|', '(', '<', '[')
private val SURROUNDING_TITLE_DELIMITERS =
    listOf(
        '[' to ']',
        '"' to '"',
        '\'' to '\'',
        '\u201C' to '\u201D',
        '\u2018' to '\u2019',
    )

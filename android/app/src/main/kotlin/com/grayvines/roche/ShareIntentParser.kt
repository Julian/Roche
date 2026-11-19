package com.grayvines.roche

import android.content.Intent
import android.net.Uri
import com.grayvines.roche.model.ContentType
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

data class SharedContentPayload(
    val url: String,
    val title: String? = null,
    val forcedContentType: ContentType? = null,
    val pdfStreamUri: String? = null,
)

internal data class MatchedHttpUrl(
    val url: String,
    val startIndex: Int,
)

@Suppress("LongParameterList")
fun extractSharedContent(
    action: String?,
    mimeType: String?,
    extraText: String? = null,
    extraHtmlText: String? = null,
    extraSubject: String? = null,
    extraTitle: String? = null,
    clipText: String? = null,
    clipUri: String? = null,
    originatingUri: String? = null,
    dataUri: String? = null,
    streamUri: String? = null,
    streamDisplayName: String? = null,
): SharedContentPayload? {
    if (!isSupportedShareMime(action, mimeType)) return null
    val isPdfMime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) == PDF_MIME_TYPE
    val httpCandidate =
        findHttpMatchAcrossSources(
            extraText = extraText,
            extraHtmlText = extraHtmlText,
            clipText = clipText,
            clipUri = clipUri,
            originatingUri = originatingUri,
            dataUri = dataUri,
        )
    if (httpCandidate != null) {
        val (matchedUrl, inlinePrefixTitle) = httpCandidate
        val rawTitle =
            listOf(
                    extraSubject,
                    extraTitle,
                    inlinePrefixTitle,
                    stripPdfExtension(streamDisplayName),
                )
                .firstOrNull { !it.isNullOrBlank() }
                ?.trim()
        val forcedType = if (isPdfMime) ContentType.PDF else null
        return SharedContentPayload(
            url = matchedUrl,
            title = sanitizeCandidateTitle(rawTitle, matchedUrl),
            forcedContentType = forcedType,
            pdfStreamUri = streamUri?.takeIf { isPdfMime && it.isNotBlank() },
        )
    }
    if (!isPdfMime || streamUri.isNullOrBlank()) return null
    return buildPdfStreamPayload(
        streamUri = streamUri,
        streamDisplayName = streamDisplayName,
        extraSubject = extraSubject,
        extraTitle = extraTitle,
    )
}

fun extractSharedContentFromIntent(
    intent: Intent?,
    displayNameResolver: (Uri) -> String? = { null },
): SharedContentPayload? {
    val current = intent ?: return null
    val clipItems =
        current.clipData?.let { clip -> (0 until clip.itemCount).map(clip::getItemAt) }.orEmpty()
    val firstClipUri = clipItems.firstNotNullOfOrNull { it.uri }
    val bestClipText =
        clipItems
            .mapNotNull { it.text?.toString()?.takeIf(String::isNotBlank) }
            .firstOrNull { findFirstHttpUrl(it) != null }
            ?: clipItems.firstNotNullOfOrNull { it.text?.toString()?.takeIf(String::isNotBlank) }
    val streamUriObj = readParcelableUriExtra(current, Intent.EXTRA_STREAM) ?: firstClipUri
    val originatingUriObj = readParcelableUriExtra(current, EXTRA_ORIGINATING_URI)
    val displayName = streamUriObj?.let(displayNameResolver)
    return extractSharedContent(
        action = current.action,
        mimeType = current.type,
        extraText = current.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
        extraHtmlText = current.getStringExtra(Intent.EXTRA_HTML_TEXT),
        extraSubject = current.getStringExtra(Intent.EXTRA_SUBJECT),
        extraTitle = current.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString(),
        clipText = bestClipText,
        clipUri = firstClipUri?.toString(),
        originatingUri = originatingUriObj?.toString(),
        dataUri = current.dataString,
        streamUri = streamUriObj?.toString(),
        streamDisplayName = displayName,
    )
}

internal fun readParcelableUriExtra(intent: Intent, key: String): Uri? =
    intent.getParcelableExtra(key, Uri::class.java)

@Suppress("LongParameterList")
private fun findHttpMatchAcrossSources(
    extraText: String?,
    extraHtmlText: String?,
    clipText: String?,
    clipUri: String?,
    originatingUri: String?,
    dataUri: String?,
): Pair<String, String?>? {
    val textMatch =
        listOf(extraText to true, clipText to true, extraHtmlText to false).firstNotNullOfOrNull {
            (source, allowPrefix) ->
            source
                ?.takeIf { it.isNotBlank() }
                ?.let(::findFirstHttpUrl)
                ?.let { match ->
                    val prefix =
                        if (allowPrefix) extractPrefixTitle(source, match.startIndex) else null
                    match.url to prefix
                }
        }
    return textMatch
        ?: listOf(originatingUri, clipUri, dataUri)
            .mapNotNull { candidate -> candidate?.let(::cleanUrlToken)?.takeIf(::isValidHttpUrl) }
            .firstOrNull()
            ?.let { it to null }
}

private fun buildPdfStreamPayload(
    streamUri: String,
    streamDisplayName: String?,
    extraSubject: String?,
    extraTitle: String?,
): SharedContentPayload {
    val lastSeg = streamUri.substringBefore('?').substringAfterLast('/')
    val arxivId =
        listOfNotNull(streamDisplayName, extraTitle, extraSubject, lastSeg).firstNotNullOfOrNull {
            ARXIV_PDF_FILENAME_REGEX.find(it)?.groupValues?.getOrNull(1)
        }
    val rawLabel =
        listOf(
                stripPdfExtension(extraSubject),
                stripPdfExtension(extraTitle),
                stripPdfExtension(streamDisplayName),
                stripPdfExtension(lastSeg),
            )
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
    if (arxivId != null) {
        val arxivUrl = "https://arxiv.org/pdf/$arxivId"
        val cleanLabel =
            rawLabel?.takeUnless { it.equals(arxivId, ignoreCase = true) } ?: "arXiv:$arxivId"
        return SharedContentPayload(
            url = arxivUrl,
            title = cleanLabel,
            forcedContentType = ContentType.PDF,
            pdfStreamUri = streamUri,
        )
    }
    val streamKey = UUID.nameUUIDFromBytes(streamUri.toByteArray(StandardCharsets.UTF_8)).toString()
    val slug =
        (rawLabel ?: "document")
            .lowercase(Locale.ROOT)
            .replace(NON_SLUG_CHARS_REGEX, "-")
            .trim('-')
            .take(MAX_PDF_SLUG_LEN)
            .ifEmpty { "document" }
    return SharedContentPayload(
        url = "localpdf://$streamKey/$slug.pdf",
        title = rawLabel ?: "Shared PDF",
        forcedContentType = ContentType.PDF,
        pdfStreamUri = streamUri,
    )
}

private fun stripPdfExtension(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (trimmed.endsWith(".pdf", ignoreCase = true) && trimmed.length > PDF_EXT_LEN) {
        trimmed.dropLast(PDF_EXT_LEN).trim()
    } else {
        trimmed
    }
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

private fun isSupportedShareMime(action: String?, mimeType: String?): Boolean {
    if (action != Intent.ACTION_SEND) return false
    val base = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
    return base.startsWith(TEXT_MIME_PREFIX) || base == PDF_MIME_TYPE
}

internal fun cleanUrlToken(rawToken: String): String {
    var current = rawToken.trim().trimStart { it in LEADING_URL_WRAPPERS }
    while (
        current.isNotEmpty() &&
            if (current.last() == ')') {
                current.count { it == ')' } > current.count { it == '(' }
            } else {
                current.last() in TRAILING_URL_PUNCTUATION
            }
    ) {
        current = current.dropLast(1)
    }
    return current
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
    val matchingPair = SURROUNDING_TITLE_DELIMITERS.firstOrNull { (open, close) ->
        trimmedPrefix.length >= MIN_DELIMITED_LENGTH &&
            trimmedPrefix.startsWith(open) &&
            trimmedPrefix.endsWith(close) &&
            close !in trimmedPrefix.substring(1, trimmedPrefix.length - 1)
    }
    val unwrapped =
        if (matchingPair != null) {
            trimmedPrefix.substring(1, trimmedPrefix.length - 1).trim()
        } else {
            trimmedPrefix
        }
    return unwrapped.takeIf { candidate -> candidate.any { it.isLetterOrDigit() } }
}

private fun sanitizeCandidateTitle(candidate: String?, extractedUrl: String): String? {
    val normalized = candidate?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val isDuplicateOrRawUrl =
        normalized.equals(extractedUrl, ignoreCase = true) ||
            isValidHttpUrl(cleanUrlToken(normalized))
    return if (isDuplicateOrRawUrl) null else normalized
}

private const val TEXT_MIME_PREFIX = "text/"
private const val PDF_MIME_TYPE = "application/pdf"
private const val EXTRA_ORIGINATING_URI = "android.intent.extra.ORIGINATING_URI"
private const val PDF_EXT_LEN = 4
private const val MAX_PDF_SLUG_LEN = 48
private const val MIN_DELIMITED_LENGTH = 2
private val ALLOWED_HTTP_SCHEMES = setOf("http", "https")
private val HTTP_URL_CANDIDATE_REGEX = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
private val ARXIV_PDF_FILENAME_REGEX =
    Regex("""(?:arxiv[_:\s-]*)?(\d{4}\.\d{4,5}(?:v\d+)?)(?:\.pdf)?\b""", RegexOption.IGNORE_CASE)
private val NON_SLUG_CHARS_REGEX = Regex("""[^a-z0-9]+""")
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

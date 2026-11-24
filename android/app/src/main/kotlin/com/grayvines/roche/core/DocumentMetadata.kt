@file:Suppress("TooManyFunctions")

package com.grayvines.roche.core

import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal data class ParsedPublicationDate(
    val publishedAt: String?,
    val releaseYear: Int?,
)

internal data class ExtractedDocumentMetadata(
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val publishedAt: String? = null,
    val releaseYear: Int? = null,
    val summary: String? = null,
    val doi: String? = null,
    val arxivId: String? = null,
    val isbn: String? = null,
)

internal fun ExtractedDocumentMetadata.isEmpty(): Boolean =
    title == null &&
        authors.isEmpty() &&
        publishedAt == null &&
        releaseYear == null &&
        summary == null &&
        doi == null &&
        arxivId == null &&
        isbn == null

internal fun ExtractedDocumentMetadata.fillMissingFrom(
    fallback: ExtractedDocumentMetadata
): ExtractedDocumentMetadata {
    val nextPub = publishedAt ?: fallback.publishedAt
    val nextYear = releaseYear ?: fallback.releaseYear ?: extractYearPrefix(nextPub)
    return ExtractedDocumentMetadata(
        title = title ?: fallback.title,
        authors = authors.ifEmpty { fallback.authors },
        publishedAt = nextPub,
        releaseYear = nextYear,
        summary = summary ?: fallback.summary,
        doi = doi ?: fallback.doi,
        arxivId = arxivId ?: fallback.arxivId,
        isbn = isbn ?: fallback.isbn,
    )
}

internal fun normalizeDoi(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val candidate = extractRawDoiCandidate(trimmed)
    val cleaned = trimDoiToken(candidate)
    return cleaned.lowercase(Locale.ROOT).takeIf { isValidDoi(cleaned) }
}

private fun extractRawDoiCandidate(trimmed: String): String {
    val parsed = runCatching { URI(trimmed.replace(" ", "%20")) }.getOrNull()
    val host = parsed?.host?.trimEnd('.')?.lowercase(Locale.ROOT)
    return when {
        host in DOI_HOSTS -> decodePercentUtf8(parsed?.rawPath.orEmpty().trimStart('/'))
        trimmed.startsWith(PREFIX_DOI_COLON, ignoreCase = true) ->
            trimmed.substring(PREFIX_DOI_COLON.length).trim()
        trimmed.startsWith(PREFIX_DOI_SPACE, ignoreCase = true) ->
            trimmed.substring(PREFIX_DOI_SPACE.length).trim()
        else -> trimmed
    }
}

private fun trimDoiToken(raw: String): String {
    var cur = raw.trim().trimStart('(', '[', '{', '<', '"', '\'')
    while (cur.isNotEmpty() && shouldStripDoiTrailingChar(cur, cur.last())) {
        cur = cur.substring(0, cur.length - 1)
    }
    return cur.trim()
}

private fun shouldStripDoiTrailingChar(cur: String, last: Char): Boolean =
    when (last) {
        '.',
        ',',
        ';',
        ':',
        '>',
        '"',
        '\'',
        '!',
        '?' -> true
        ')' -> cur.count { it == ')' } > cur.count { it == '(' }
        ']' -> cur.count { it == ']' } > cur.count { it == '[' }
        '}' -> cur.count { it == '}' } > cur.count { it == '{' }
        else -> false
    }

private fun isValidDoi(candidate: String): Boolean {
    if (candidate.length !in MIN_DOI_LEN..MAX_DOI_LEN) return false
    val slashIdx = candidate.indexOf('/')
    if (slashIdx <= 0 || slashIdx >= candidate.lastIndex) return false
    val prefix = candidate.substring(0, slashIdx)
    val suffix = candidate.substring(slashIdx + 1)
    val registrant =
        if (prefix.startsWith(DOI_REGISTRANT_PREFIX)) {
            prefix.substring(DOI_REGISTRANT_PREFIX.length)
        } else {
            return false
        }
    val firstSeg = registrant.substringBefore('.')
    val regValid =
        registrant.length in MIN_REGISTRANT_LEN..MAX_REGISTRANT_LEN &&
            registrant.all { it in '0'..'9' || it == '.' } &&
            firstSeg.length in MIN_REGISTRANT_LEN..MAX_REGISTRANT_FIRST_LEN
    val charsValid =
        ".." !in candidate &&
            "://" !in candidate &&
            candidate.none { it.isWhitespace() || it.isISOControl() }
    return regValid && suffix.isNotEmpty() && charsValid
}

internal fun normalizeIsbn(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val stripped = stripIsbnPrefix(trimmed)
    val compact =
        buildString(stripped.length) {
            for (ch in stripped) {
                if (ch !in ISBN_STRIP_CHARS) {
                    append(ch.uppercaseChar())
                }
            }
        }
    val isIsbn13 =
        compact.length == ISBN13_LEN &&
            (compact.startsWith("978") || compact.startsWith("979")) &&
            compact.all { it in '0'..'9' }
    val isIsbn10 =
        compact.length == ISBN10_LEN &&
            compact.substring(0, ISBN10_DIGIT_COUNT).all { it in '0'..'9' } &&
            (compact[ISBN10_DIGIT_COUNT] in '0'..'9' || compact[ISBN10_DIGIT_COUNT] == 'X')
    return compact.takeIf { isIsbn13 || isIsbn10 }
}

private fun stripIsbnPrefix(trimmed: String): String {
    for (prefix in ISBN_PREFIXES) {
        if (trimmed.startsWith(prefix, ignoreCase = true)) {
            return trimmed.substring(prefix.length)
        }
    }
    return trimmed
}

internal fun extractDoiFromText(text: String): String? {
    var i = 0
    while (i + MIN_DOI_LEN <= text.length) {
        val startsTenDot =
            text[i] == '1' &&
                text[i + 1] == '0' &&
                text[i + 2] == '.' &&
                (i == 0 || !text[i - 1].isLetterOrDigit())
        if (!startsTenDot) {
            i++
            continue
        }
        var end = i + DOI_REGISTRANT_PREFIX.length
        while (end < text.length && !text[end].isWhitespace() && text[end] !in DOI_STOP_CHARS) {
            end++
        }
        val doi = normalizeDoi(text.substring(i, end))
        if (doi != null) return doi
        i = end
    }
    return null
}

internal fun extractArxivIdFromText(text: String): String? {
    val lower = text.lowercase(Locale.ROOT)
    for (needle in ARXIV_TEXT_NEEDLES) {
        val found = scanArxivNeedle(text, lower, needle)
        if (found != null) return found
    }
    return null
}

private fun scanArxivNeedle(text: String, lower: String, needle: String): String? {
    var searchStart = 0
    var found: String? = null
    while (found == null && searchStart < lower.length) {
        val idx = lower.indexOf(needle, startIndex = searchStart)
        if (idx < 0) break
        val after = idx + needle.length
        val rest = text.substring(after).trimStart()
        val tokenEnd = rest.indexOfFirst { it.isWhitespace() || it in ARXIV_TOKEN_STOP_CHARS }
        val rawToken = if (tokenEnd >= 0) rest.substring(0, tokenEnd) else rest
        val token = rawToken.trimEnd('.')
        found = if (looksLikeArxivToken(token)) extractArxivIdentifier(token) else null
        searchStart = after
    }
    return found
}

internal fun looksLikeArxivToken(token: String): Boolean {
    val clean = token.removeSuffix(".pdf")
    return isModernArxivToken(clean) || isLegacyArxivToken(clean)
}

private fun isModernArxivToken(clean: String): Boolean {
    val dotIdx = clean.indexOf('.')
    if (dotIdx != ARXIV_YYMM_LEN) return false
    val yymm = clean.substring(0, dotIdx)
    if (!yymm.all { it in '0'..'9' }) return false
    val rest = clean.substring(dotIdx + 1)
    val numPart = rest.substringBefore('v')
    return numPart.length in ARXIV_MIN_NUM_LEN..ARXIV_MAX_NUM_LEN && numPart.all { it in '0'..'9' }
}

private fun isLegacyArxivToken(clean: String): Boolean {
    val slashIdx = clean.indexOf('/')
    if (slashIdx !in 1..ARXIV_MAX_ARCH_LEN) return false
    val arch = clean.substring(0, slashIdx)
    val archValid = arch.all { it in 'a'..'z' || it in 'A'..'Z' || it == '-' || it == '.' }
    val numPart = clean.substring(slashIdx + 1).substringBefore('v')
    return archValid && numPart.length == ARXIV_LEGACY_NUM_LEN && numPart.all { it in '0'..'9' }
}

internal fun isPlaceholderEntryTitle(title: String, url: String?): Boolean {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return true
    val cleanUrl = url?.trim()?.takeIf { it.isNotEmpty() }
    val matchesKnownPlaceholder =
        cleanUrl != null && trimmed.equals(cleanUrl, ignoreCase = true) ||
            isGenericPlaceholderLabel(trimmed) ||
            isArxivBareTitle(trimmed) ||
            isPdfFilenamePlaceholder(trimmed, cleanUrl)
    return matchesKnownPlaceholder
}

private fun isGenericPlaceholderLabel(trimmed: String): Boolean {
    val lower = trimmed.lowercase(Locale.ROOT)
    return lower in PLACEHOLDER_EXACT_TITLES ||
        lower.endsWith(" — pdf") ||
        lower.endsWith(" — saved entry") ||
        lower.endsWith(" - pdf")
}

private fun isArxivBareTitle(trimmed: String): Boolean =
    trimmed.startsWith(PREFIX_ARXIV_COLON, ignoreCase = true) &&
        looksLikeArxivToken(trimmed.substring(PREFIX_ARXIV_COLON.length).trim())

private fun isPdfFilenamePlaceholder(trimmed: String, cleanUrl: String?): Boolean {
    val hasNoSpace = trimmed.none(Char::isWhitespace)
    val endsPdf = trimmed.length > PDF_EXT_LEN && trimmed.endsWith(".pdf", ignoreCase = true)
    if (endsPdf && hasNoSpace) return true
    if (cleanUrl == null) return false
    val lastSeg = extractDecodedUrlLastSegment(cleanUrl) ?: return false
    val exactSegMatch = endsPdf && lastSeg.equals(trimmed, ignoreCase = true)
    val stemMatch =
        hasNoSpace &&
            lastSeg.length > PDF_EXT_LEN &&
            lastSeg.endsWith(".pdf", ignoreCase = true) &&
            lastSeg.substring(0, lastSeg.length - PDF_EXT_LEN).equals(trimmed, ignoreCase = true)
    return exactSegMatch || stemMatch
}

private fun extractDecodedUrlLastSegment(rawUrl: String): String? {
    val uri = runCatching { URI(rawUrl.trim().replace(" ", "%20")) }.getOrNull() ?: return null
    val last = uri.rawPath.orEmpty().split('/').lastOrNull { it.isNotEmpty() } ?: return null
    return decodePercentUtf8(last).trim().takeIf { it.isNotEmpty() }
}

internal fun parsePdfDate(raw: String): ParsedPublicationDate? {
    val trimmed = raw.trim()
    val cleaned =
        if (trimmed.startsWith("D:", ignoreCase = true)) {
            trimmed.substring(PDF_DATE_PREFIX_LEN)
        } else {
            trimmed
        }
    val digits = cleaned.takeWhile { it in '0'..'9' }
    if (digits.length < YEAR_DIGIT_LEN) return null
    val year = digits.substring(0, YEAR_DIGIT_LEN).toIntOrNull() ?: return null
    if (year !in MIN_PDF_YEAR..MAX_PLAUSIBLE_YEAR) return null
    val month = parseDigitSlice(digits, MONTH_SLICE_START, 1).coerceIn(1, MAX_MONTH)
    val day = parseDigitSlice(digits, DAY_SLICE_START, 1).coerceIn(1, MAX_DAY)
    val hour = parseDigitSlice(digits, HOUR_SLICE_START, NOON_HOUR).coerceIn(0, MAX_HOUR)
    val minute = parseDigitSlice(digits, MINUTE_SLICE_START, 0).coerceIn(0, MAX_MINUTE_SEC)
    val second = parseDigitSlice(digits, SECOND_SLICE_START, 0).coerceIn(0, MAX_MINUTE_SEC)
    val date = runCatching {
        LocalDate.of(year, month, day)
    }
        .getOrElse { LocalDate.of(year, month, 1) }
    val offset =
        parsePdfTimezoneOffset(cleaned.substring(digits.length.coerceAtMost(cleaned.length)))
    val instant = date.atTime(hour, minute, second).toInstant(offset)
    val utcYear = instant.atOffset(ZoneOffset.UTC).year
    return ParsedPublicationDate(publishedAt = formatUtcIsoSeconds(instant), releaseYear = utcYear)
}

private fun parseDigitSlice(digits: String, start: Int, defaultVal: Int): Int {
    if (digits.length < start + TWO_DIGITS) return defaultVal
    return digits.substring(start, start + TWO_DIGITS).toIntOrNull() ?: defaultVal
}

private fun parsePdfTimezoneOffset(rest: String): ZoneOffset {
    val signChar = rest.firstOrNull()?.takeIf { it == '+' || it == '-' } ?: return ZoneOffset.UTC
    val tzDigits = rest.substring(1).filter { it in '0'..'9' }.take(YEAR_DIGIT_LEN)
    if (tzDigits.length < TWO_DIGITS) return ZoneOffset.UTC
    val tzH =
        tzDigits.substring(0, TWO_DIGITS).toIntOrNull()?.coerceAtMost(MAX_HOUR)
            ?: return ZoneOffset.UTC
    val tzM =
        if (tzDigits.length >= YEAR_DIGIT_LEN) {
            val parsedMin = tzDigits.substring(TWO_DIGITS, YEAR_DIGIT_LEN).toIntOrNull()
            parsedMin?.coerceAtMost(MAX_MINUTE_SEC) ?: 0
        } else {
            0
        }
    val sign = if (signChar == '-') -1 else 1
    return runCatching {
            ZoneOffset.ofTotalSeconds(sign * (tzH * SECS_PER_HOUR + tzM * SECS_PER_MIN))
        }
        .getOrDefault(ZoneOffset.UTC)
}

internal fun parsePublicationDateStr(raw: String): ParsedPublicationDate? {
    val trimmed = raw.trim().trim('(', ')', '[', ']', '.', ',')
    if (trimmed.isEmpty() || trimmed.length > MAX_DATE_STR_LEN) return null
    return parseIsoOrRfcDate(trimmed)
        ?: trimmed.takeIf { it.startsWith("D:", ignoreCase = true) }?.let(::parsePdfDate)
        ?: parseNumericYmdDate(trimmed)
        ?: parseBareYear(trimmed)
        ?: parseNaturalMonthDate(trimmed)
}

private fun parseIsoOrRfcDate(trimmed: String): ParsedPublicationDate? {
    val instant =
        runCatching { OffsetDateTime.parse(trimmed).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(trimmed) }.getOrNull()
            ?: runCatching {
                OffsetDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            }
                .getOrNull()
            ?: return null
    val year = instant.atOffset(ZoneOffset.UTC).year
    return if (year in MIN_PLAUSIBLE_YEAR..MAX_PLAUSIBLE_YEAR) {
        ParsedPublicationDate(formatUtcIsoSeconds(instant), year)
    } else {
        null
    }
}

private fun parseBareYear(trimmed: String): ParsedPublicationDate? {
    if (trimmed.length != YEAR_DIGIT_LEN || !trimmed.all { it in '0'..'9' }) return null
    val yr = trimmed.toIntOrNull() ?: return null
    return if (yr in MIN_PLAUSIBLE_YEAR..MAX_PLAUSIBLE_YEAR) {
        ParsedPublicationDate(publishedAt = null, releaseYear = yr)
    } else {
        null
    }
}

private fun parseNumericYmdDate(raw: String): ParsedPublicationDate? {
    val datePart = raw.substringBefore('T').substringBefore(' ').trim()
    val sep =
        when {
            '-' in datePart -> '-'
            '/' in datePart -> '/'
            else -> return null
        }
    val parts = datePart.split(sep)
    if (parts.size !in TWO_DIGITS..YMD_PART_COUNT || parts[0].length != YEAR_DIGIT_LEN) return null
    val year =
        parts[0].toIntOrNull()?.takeIf { it in MIN_PLAUSIBLE_YEAR..MAX_PLAUSIBLE_YEAR }
            ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val day = if (parts.size == YMD_PART_COUNT) parts[2].toIntOrNull() ?: return null else 1
    val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
    return ParsedPublicationDate(formatUtcNoonIso(date), year)
}

private class NaturalDateParts(
    var month: Int? = null,
    var day: Int? = null,
    var year: Int? = null,
)

private fun parseNaturalMonthDate(raw: String): ParsedPublicationDate? {
    val tokens =
        stripDateLabelPrefix(raw)
            .split(NATURAL_DATE_SPLIT_REGEX)
            .map { it.trim('.') }
            .filter { it.isNotEmpty() }
    if (tokens.isEmpty() || tokens.size > MAX_NATURAL_DATE_TOKENS) return null
    val parts = NaturalDateParts()
    if (!tokens.all { consumeNaturalDateToken(it, parts) }) return null
    val y = parts.year
    val m = parts.month
    val date =
        if (y != null && m != null) {
            runCatching { LocalDate.of(y, m, parts.day ?: 1) }.getOrNull()
        } else {
            null
        }
    return date?.let { ParsedPublicationDate(formatUtcNoonIso(it), y) }
}

private fun consumeNaturalDateToken(tok: String, parts: NaturalDateParts): Boolean {
    val m = if (parts.month == null) parseMonthName(tok) else null
    val y = if (m == null && parts.year == null) parsePlausibleYearToken(tok) else null
    val d = if (m == null && y == null && parts.day == null) parseOrdinalDay(tok) else null
    return when {
        m != null -> {
            parts.month = m
            true
        }
        y != null -> {
            parts.year = y
            true
        }
        d != null -> {
            parts.day = d
            true
        }
        else -> {
            isIgnorableDateToken(tok)
        }
    }
}

private fun stripDateLabelPrefix(raw: String): String {
    val trimmed = raw.trim()
    for (prefix in DATE_LABEL_PREFIXES) {
        if (trimmed.startsWith(prefix, ignoreCase = true)) {
            return trimmed.substring(prefix.length).trim()
        }
    }
    return trimmed
}

private fun parsePlausibleYearToken(tok: String): Int? =
    if (tok.length == YEAR_DIGIT_LEN && tok.all { it in '0'..'9' }) {
        tok.toIntOrNull()?.takeIf { it in MIN_PLAUSIBLE_YEAR..MAX_PLAUSIBLE_YEAR }
    } else {
        null
    }

private fun parseOrdinalDay(tok: String): Int? {
    val lower = tok.lowercase(Locale.ROOT)
    val numStr = lower.removeSuffix("st").removeSuffix("nd").removeSuffix("rd").removeSuffix("th")
    if (numStr.isEmpty() || numStr.length > TWO_DIGITS || !numStr.all { it in '0'..'9' }) {
        return null
    }
    return numStr.toIntOrNull()?.takeIf { it in 1..MAX_DAY }
}

private fun parseMonthName(tok: String): Int? = MONTH_NAMES[tok.trim('.').lowercase(Locale.ROOT)]

private fun isIgnorableDateToken(tok: String): Boolean =
    tok.trim('.').lowercase(Locale.ROOT) in IGNORABLE_DATE_TOKENS

internal fun formatUtcNoonIso(date: LocalDate): String =
    formatUtcIsoSeconds(date.atTime(NOON_HOUR, 0, 0).toInstant(ZoneOffset.UTC))

internal fun formatUtcIsoSeconds(instant: Instant): String =
    DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))

internal fun parseCrossrefWorkJson(rawJson: String): ExtractedDocumentMetadata? {
    val root =
        runCatching { coreJson.parseToJsonElement(rawJson) as? JsonObject }.getOrNull()
            ?: return null
    val msg = root["message"] as? JsonObject ?: root
    val title = extractCrossrefTitle(msg)
    val authors = extractCrossrefAuthors(msg)
    val parsedDate = extractCrossrefDate(msg)
    val summary =
        (msg["abstract"] as? JsonPrimitive)?.contentOrNull?.let(::stripSimpleXmlTags)?.takeIf {
            it.isNotEmpty()
        }
    val doi = (msg["DOI"] as? JsonPrimitive)?.contentOrNull?.let(::normalizeDoi)
    val isbn = extractCrossrefIsbn(msg)
    val result =
        ExtractedDocumentMetadata(
            title = title,
            authors = authors,
            publishedAt = parsedDate?.publishedAt,
            releaseYear = parsedDate?.releaseYear,
            summary = summary,
            doi = doi,
            isbn = isbn,
        )
    return result.takeUnless { it.isEmpty() }
}

private fun extractCrossrefTitle(msg: JsonObject): String? {
    val arr = msg["title"] as? JsonArray ?: return null
    val raw = (arr.firstOrNull() as? JsonPrimitive)?.contentOrNull ?: return null
    return normalizeWhitespace(stripSimpleXmlTags(raw)).takeIf { it.isNotEmpty() }
}

private fun extractCrossrefAuthors(msg: JsonObject): List<String> {
    val arr = msg["author"] as? JsonArray ?: return emptyList()
    return arr.mapNotNull(::parseSingleCrossrefAuthor)
}

private fun parseSingleCrossrefAuthor(el: kotlinx.serialization.json.JsonElement): String? {
    val obj = el as? JsonObject ?: return null
    val given = (obj["given"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    val family = (obj["family"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    return when {
        given.isNotEmpty() && family.isNotEmpty() -> "$given $family"
        family.isNotEmpty() -> family
        name.isNotEmpty() -> name
        else -> null
    }
}

private fun extractCrossrefDate(msg: JsonObject): ParsedPublicationDate? =
    CROSSREF_DATE_KEYS.firstNotNullOfOrNull { key ->
        parseCrossrefDateObj(msg[key] as? JsonObject)
    }

private fun parseCrossrefDateObj(obj: JsonObject?): ParsedPublicationDate? {
    val parts = (obj?.get("date-parts") as? JsonArray)?.firstOrNull() as? JsonArray ?: return null
    val year =
        (parts.getOrNull(0) as? JsonPrimitive)?.intOrNull?.takeIf {
            it in MIN_PLAUSIBLE_YEAR..MAX_PLAUSIBLE_YEAR
        } ?: return null
    val month = (parts.getOrNull(1) as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..MAX_MONTH }
    val day = (parts.getOrNull(2) as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..MAX_DAY }
    val pubIso = month?.let { m ->
        runCatching { LocalDate.of(year, m, day ?: 1) }.getOrNull()?.let(::formatUtcNoonIso)
    }
    return ParsedPublicationDate(publishedAt = pubIso, releaseYear = year)
}

private fun extractCrossrefIsbn(msg: JsonObject): String? {
    val arr = msg["ISBN"] as? JsonArray ?: return null
    return arr.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.let(::normalizeIsbn) }
}

private fun stripSimpleXmlTags(raw: String): String {
    val out = StringBuilder(raw.length)
    var inTag = false
    for (ch in raw) {
        if (ch == '<') {
            inTag = true
            out.append(' ')
        } else if (ch == '>') {
            inTag = false
        } else if (!inTag) {
            out.append(ch)
        }
    }
    return normalizeWhitespace(decodeHtmlEntities(out.toString()))
}

internal fun normalizeWhitespace(raw: String): String = raw.trim().replace(MULTI_SPACE_REGEX, " ")

private const val PREFIX_DOI_COLON = "doi:"
private const val PREFIX_DOI_SPACE = "doi "
private const val PREFIX_ARXIV_COLON = "arxiv:"
private const val DOI_REGISTRANT_PREFIX = "10."
private const val MIN_DOI_LEN = 7
private const val MAX_DOI_LEN = 256
private const val MIN_REGISTRANT_LEN = 4
private const val MAX_REGISTRANT_LEN = 20
private const val MAX_REGISTRANT_FIRST_LEN = 9
private const val ISBN13_LEN = 13
private const val ISBN10_LEN = 10
private const val ISBN10_DIGIT_COUNT = 9
private const val ARXIV_YYMM_LEN = 4
private const val ARXIV_MIN_NUM_LEN = 4
private const val ARXIV_MAX_NUM_LEN = 5
private const val ARXIV_MAX_ARCH_LEN = 24
private const val ARXIV_LEGACY_NUM_LEN = 7
private const val PDF_EXT_LEN = 4
private const val PDF_DATE_PREFIX_LEN = 2
private const val YEAR_DIGIT_LEN = 4
private const val TWO_DIGITS = 2
private const val YMD_PART_COUNT = 3
private const val MONTH_SLICE_START = 4
private const val DAY_SLICE_START = 6
private const val HOUR_SLICE_START = 8
private const val MINUTE_SLICE_START = 10
private const val SECOND_SLICE_START = 12
private const val MIN_PDF_YEAR = 1900
private const val MIN_PLAUSIBLE_YEAR = 1450
private const val MAX_PLAUSIBLE_YEAR = 2100
private const val MAX_MONTH = 12
private const val MAX_DAY = 31
private const val NOON_HOUR = 12
private const val MAX_HOUR = 23
private const val MAX_MINUTE_SEC = 59
private const val SECS_PER_HOUR = 3600
private const val SECS_PER_MIN = 60
private const val MAX_DATE_STR_LEN = 80
private const val MAX_NATURAL_DATE_TOKENS = 5

private val DOI_HOSTS = setOf("doi.org", "dx.doi.org", "www.doi.org")
private val DOI_STOP_CHARS = setOf('<', '>', '"', '\'')
private val ISBN_STRIP_CHARS = setOf('-', ' ', '\t', '\u2010', '\u2013')
private val ISBN_PREFIXES = listOf("isbn-13:", "isbn-10:", "isbn:", "isbn ")
private val ARXIV_TEXT_NEEDLES = listOf("arxiv:", "arxiv.org/abs/", "arxiv.org/pdf/")
private val ARXIV_TOKEN_STOP_CHARS = setOf('[', ']', '(', ')', '<', '>', ',', ';')
private val PLACEHOLDER_EXACT_TITLES =
    setOf("untitled", "shared pdf", "local pdf", "local pdf — pdf", "document", "document.pdf")
private val NATURAL_DATE_SPLIT_REGEX = Regex("""[\s,;]+""")
private val MULTI_SPACE_REGEX = Regex("""\s+""")
private val DATE_LABEL_PREFIXES =
    listOf(
        "date:",
        "published:",
        "published on",
        "posted:",
        "updated:",
        "last revised:",
        "revised:",
    )
private val CROSSREF_DATE_KEYS =
    listOf("published-print", "published-online", "published", "issued", "created")
private val IGNORABLE_DATE_TOKENS =
    setOf(
        "of",
        "on",
        "mon",
        "monday",
        "tue",
        "tues",
        "tuesday",
        "wed",
        "wednesday",
        "thu",
        "thur",
        "thurs",
        "thursday",
        "fri",
        "friday",
        "sat",
        "saturday",
        "sun",
        "sunday",
    )
private val MONTH_NAMES =
    mapOf(
        "jan" to 1,
        "january" to 1,
        "feb" to 2,
        "february" to 2,
        "mar" to 3,
        "march" to 3,
        "apr" to 4,
        "april" to 4,
        "may" to 5,
        "jun" to 6,
        "june" to 6,
        "jul" to 7,
        "july" to 7,
        "aug" to 8,
        "august" to 8,
        "sep" to 9,
        "sept" to 9,
        "september" to 9,
        "oct" to 10,
        "october" to 10,
        "nov" to 11,
        "november" to 11,
        "dec" to 12,
        "december" to 12,
    )

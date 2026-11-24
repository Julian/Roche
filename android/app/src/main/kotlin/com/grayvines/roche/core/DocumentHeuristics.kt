@file:Suppress("TooManyFunctions", "MatchingDeclarationName")

package com.grayvines.roche.core

import java.util.Locale

internal data class PdfLayoutLine(
    val text: String,
    val fontSize: Float = DEFAULT_LINE_FONT_SIZE,
    val yPos: Float = 0f,
)

internal fun isJunkPdfTitle(title: String, sourceUrl: String? = null): Boolean {
    val trimmed = title.trim()
    val wordCount = trimmed.split(WS_SPLIT).count { it.isNotEmpty() }
    val tooShort = trimmed.length < MIN_TITLE_LEN || trimmed.length > MAX_TITLE_LEN
    val lower = trimmed.lowercase(Locale.ROOT)
    val junkExact =
        lower in JUNK_PDF_TITLES ||
            lower.startsWith("microsoft word -") ||
            lower.startsWith("microsoft powerpoint -") ||
            lower.startsWith("latex") ||
            lower.endsWith(".dvi") ||
            lower.endsWith(".tex") ||
            lower.endsWith(".docx") ||
            lower.endsWith(".doc")
    return tooShort ||
        wordCount > MAX_TITLE_WORDS ||
        junkExact ||
        isPlaceholderEntryTitle(trimmed, sourceUrl)
}

internal fun isJunkPdfAuthor(author: String): Boolean {
    val trimmed = author.trim()
    if (trimmed.length !in MIN_AUTHOR_LEN..MAX_AUTHOR_LEN) return true
    val lower = trimmed.lowercase(Locale.ROOT)
    return lower in JUNK_PDF_AUTHORS ||
        '@' in trimmed ||
        "://" in trimmed ||
        trimmed.any { it in '0'..'9' }
}

internal fun splitAuthorNames(raw: String): List<String> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return emptyList()
    val chunks =
        trimmed
            .replace(AND_DELIMITER_REGEX, ";")
            .split(';', ',', '\n')
            .map { cleanSingleAuthor(it) }
            .filter { it.isNotEmpty() && !isJunkPdfAuthor(it) }
    return chunks.distinctBy { it.lowercase(Locale.ROOT) }
}

private fun cleanSingleAuthor(raw: String): String =
    normalizeWhitespace(
        raw.trim()
            .removeSuffix("*")
            .trim()
            .replace(TRAILING_FOOTNOTE_MARKS_REGEX, "")
            .replace(EMAIL_ANGLE_REGEX, "")
    )

internal fun extractMetadataFromPlainText(
    text: String,
    sourceUrl: String? = null,
): ExtractedDocumentMetadata {
    val lines =
        text
            .lineSequence()
            .map { normalizeWhitespace(it) }
            .filter { it.isNotEmpty() }
            .take(MAX_SCAN_LINES)
            .map { PdfLayoutLine(text = it) }
            .toList()
    return extractMetadataFromLayoutLines(lines, fullText = text, sourceUrl = sourceUrl)
}

internal fun extractMetadataFromLayoutLines(
    lines: List<PdfLayoutLine>,
    fullText: String? = null,
    sourceUrl: String? = null,
): ExtractedDocumentMetadata {
    if (lines.isEmpty()) return ExtractedDocumentMetadata()
    val joinedText = fullText ?: lines.joinToString("\n") { it.text }
    val doi = extractDoiFromText(joinedText)
    val arxivId = extractArxivIdFromText(joinedText)
    val headerStop = findAbstractOrSectionIndex(lines)
    val headerLines = lines.take(headerStop.coerceAtMost(MAX_HEADER_LINES))
    val titleRange = selectTitleRange(headerLines, sourceUrl)
    val title = titleRange?.let { range ->
        normalizeWhitespace(
                headerLines.subList(range.first, range.last + 1).joinToString(" ") { it.text }
            )
            .takeIf { !isJunkPdfTitle(it, sourceUrl) }
    }
    val afterTitle =
        if (titleRange != null) {
            headerLines.drop(titleRange.last + 1)
        } else {
            headerLines
        }
    val (authors, parsedDate) = extractAuthorsAndDate(afterTitle)
    val summary = extractAbstractSummary(lines, headerStop)
    return ExtractedDocumentMetadata(
        title = title,
        authors = authors,
        publishedAt = parsedDate?.publishedAt,
        releaseYear = parsedDate?.releaseYear,
        summary = summary,
        doi = doi,
        arxivId = arxivId,
    )
}

private fun findAbstractOrSectionIndex(lines: List<PdfLayoutLine>): Int {
    for (idx in lines.indices) {
        val lower = lines[idx].text.lowercase(Locale.ROOT)
        val isBoundary =
            lower == ABSTRACT_WORD ||
                lower.startsWith("$ABSTRACT_WORD.") ||
                lower.startsWith("$ABSTRACT_WORD:") ||
                lower.startsWith("$ABSTRACT_WORD ") ||
                lower == "1 introduction" ||
                lower == "1. introduction" ||
                lower == "i. introduction"
        if (isBoundary) return idx
    }
    return lines.size
}

private fun selectTitleRange(
    headerLines: List<PdfLayoutLine>,
    sourceUrl: String?,
): IntRange? {
    val candidates =
        headerLines.withIndex().filter { (_, line) -> !isSkippableHeaderLine(line.text) }
    if (candidates.isEmpty()) return null
    val hasFontVariation = candidates.map { it.value.fontSize }.distinct().size > 1
    val anchor =
        if (hasFontVariation) {
            candidates.maxByOrNull { it.value.fontSize } ?: return null
        } else {
            candidates.first()
        }
    val startIdx = anchor.index
    var endIdx = startIdx
    val baseSize = anchor.value.fontSize
    while (endIdx + 1 < headerLines.size && endIdx - startIdx + 1 < MAX_TITLE_LINES) {
        val next = headerLines[endIdx + 1]
        val sameSize = !hasFontVariation || next.fontSize >= baseSize * TITLE_SIZE_RATIO
        val stopOnMeta =
            isSkippableHeaderLine(next.text) ||
                parsePublicationDateStr(next.text) != null ||
                looksLikeAffiliationLine(next.text) ||
                looksLikeAuthorLine(next.text)
        if (!sameSize || stopOnMeta) break
        endIdx++
    }
    val combined =
        normalizeWhitespace(headerLines.subList(startIdx, endIdx + 1).joinToString(" ") { it.text })
    return if (isJunkPdfTitle(combined, sourceUrl)) null else startIdx..endIdx
}

private fun isSkippableHeaderLine(line: String): Boolean {
    val lower = line.lowercase(Locale.ROOT)
    return lower.startsWith("arxiv:") ||
        lower.startsWith("preprint") ||
        lower.startsWith("draft") ||
        lower.startsWith("submitted to") ||
        lower.startsWith("published in") ||
        lower.startsWith("published as") ||
        lower.startsWith("proceedings of") ||
        lower.startsWith("doi:") ||
        lower.startsWith("http://") ||
        lower.startsWith("https://") ||
        line.length < MIN_HEADER_LINE_LEN
}

private fun extractAuthorsAndDate(
    afterTitle: List<PdfLayoutLine>
): Pair<List<String>, ParsedPublicationDate?> {
    val authors = ArrayList<String>()
    var foundDate: ParsedPublicationDate? = null
    for (line in afterTitle.take(MAX_POST_TITLE_LINES)) {
        val text = line.text
        if (foundDate == null) {
            val maybeDate = parsePublicationDateStr(text)
            if (maybeDate != null) {
                foundDate = maybeDate
                continue
            }
        }
        if (authors.isEmpty() && looksLikeAuthorLine(text)) {
            authors.addAll(splitAuthorNames(text))
        }
    }
    return authors.distinctBy { it.lowercase(Locale.ROOT) } to foundDate
}

internal fun looksLikeAuthorLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.length !in MIN_AUTHOR_LEN..MAX_AUTHOR_LINE_LEN) return false
    if (looksLikeAffiliationLine(trimmed) || parsePublicationDateStr(trimmed) != null) return false
    if ('@' in trimmed || "://" in trimmed) return false
    val cleaned = trimmed.replace(TRAILING_FOOTNOTE_MARKS_REGEX, "").trim()
    val words = cleaned.split(WS_SPLIT).filter { it.isNotEmpty() }
    if (words.size !in MIN_AUTHOR_WORDS..MAX_AUTHOR_LINE_WORDS) return false
    val capCount = words.count { word -> isPersonNameWord(word) }
    val digitsCount = cleaned.count { it in '0'..'9' }
    return digitsCount == 0 && capCount * AUTHOR_CAP_DENOM >= words.size * AUTHOR_CAP_NUM
}

private fun isPersonNameWord(word: String): Boolean {
    val clean = word.trim(',', ';', '.', '*')
    if (clean.isEmpty()) return false
    val lower = clean.lowercase(Locale.ROOT)
    return clean[0].isUpperCase() || lower in AUTHOR_CONNECTOR_WORDS
}

private fun looksLikeAffiliationLine(line: String): Boolean {
    val lower = line.lowercase(Locale.ROOT)
    return AFFILIATION_KEYWORDS.any { it in lower }
}

private fun extractAbstractSummary(lines: List<PdfLayoutLine>, abstractIdx: Int): String? {
    if (abstractIdx >= lines.size) return null
    val firstLine = lines[abstractIdx].text
    val lower = firstLine.lowercase(Locale.ROOT)
    if (!lower.startsWith(ABSTRACT_WORD)) return null
    val out = ArrayList<String>()
    val inlineRest = firstLine.substring(ABSTRACT_WORD.length).trimStart('.', ':', '-', '—', ' ')
    if (inlineRest.isNotEmpty()) {
        out.add(inlineRest)
    }
    for (idx in abstractIdx + 1 until minOf(lines.size, abstractIdx + MAX_ABSTRACT_LINES)) {
        val txt = lines[idx].text
        val low = txt.lowercase(Locale.ROOT)
        val isStop =
            low.startsWith("1 ") ||
                low.startsWith("1.") ||
                low.startsWith("i.") ||
                low.startsWith("keywords") ||
                low.startsWith("index terms") ||
                low.startsWith("categories")
        if (isStop) break
        out.add(txt)
    }
    val joined = normalizeWhitespace(out.joinToString(" "))
    return joined.takeIf { it.length >= MIN_ABSTRACT_LEN }?.take(MAX_ABSTRACT_CHARS)
}

private const val ABSTRACT_WORD = "abstract"
private const val DEFAULT_LINE_FONT_SIZE = 12f
private const val MIN_TITLE_LEN = 4
private const val MAX_TITLE_LEN = 260
private const val MAX_TITLE_WORDS = 36
private const val MIN_AUTHOR_LEN = 3
private const val MAX_AUTHOR_LEN = 80
private const val MAX_AUTHOR_LINE_LEN = 180
private const val MAX_SCAN_LINES = 80
private const val MAX_HEADER_LINES = 24
private const val MAX_TITLE_LINES = 3
private const val TITLE_SIZE_RATIO = 0.92f
private const val MIN_HEADER_LINE_LEN = 3
private const val MAX_POST_TITLE_LINES = 10
private const val MIN_AUTHOR_WORDS = 2
private const val MAX_AUTHOR_LINE_WORDS = 16
private const val AUTHOR_CAP_NUM = 2
private const val AUTHOR_CAP_DENOM = 3
private const val MAX_ABSTRACT_LINES = 14
private const val MIN_ABSTRACT_LEN = 24
private const val MAX_ABSTRACT_CHARS = 1200

private val WS_SPLIT = Regex("""\s+""")
private val AND_DELIMITER_REGEX = Regex("""\s+(?:and|&)\s+""", RegexOption.IGNORE_CASE)
private val TRAILING_FOOTNOTE_MARKS_REGEX = Regex("""[\d*†‡§¶]+$""")
private val EMAIL_ANGLE_REGEX = Regex("""<[^>]+>""")
private val JUNK_PDF_TITLES =
    setOf("untitled", "document", "slide 1", "presentation", "paper", "manuscript", "article")
private val JUNK_PDF_AUTHORS =
    setOf("unknown", "user", "admin", "administrator", "author", "owner", "root", "latex", "pdftex")
private val AUTHOR_CONNECTOR_WORDS =
    setOf("and", "&", "de", "del", "van", "von", "der", "la", "le", "di")
private val AFFILIATION_KEYWORDS =
    listOf(
        "university",
        "institute",
        "department",
        "school of",
        "laboratory",
        "college",
        "faculty",
        "center for",
        "centre for",
        "research",
        "corporation",
        "google",
        "microsoft",
        "meta",
        "mit",
        "stanford",
        "eth zurich",
        "unsw",
        "sydney",
        "australia",
        "email",
        "correspondence",
    )

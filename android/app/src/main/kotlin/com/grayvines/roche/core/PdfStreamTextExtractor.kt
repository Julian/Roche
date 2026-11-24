@file:Suppress("TooManyFunctions")

package com.grayvines.roche.core

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Pure-Kotlin PDF bibliographic extractor (Info dictionary + XMP metadata + FlateDecode stream text
 * fallback). Mirrors `roche_core::document_meta::pdf` so both on-device and pure-JVM tests extract
 * metadata even when Android's `PdfRenderer` is unavailable.
 */
internal fun extractPdfMetadataFromBytes(
    bytes: ByteArray,
    sourceUrl: String? = null,
    page1PlainText: String? = null,
): ExtractedDocumentMetadata {
    if (bytes.size < MIN_PDF_BYTES || !hasPdfHeader(bytes)) return ExtractedDocumentMetadata()
    val infoMeta = extractPdfInfoMetadata(bytes, sourceUrl)
    val xmpMeta = extractPdfXmpMetadata(bytes, sourceUrl)
    val merged = xmpMeta.fillMissingFrom(infoMeta)
    val textToAnalyze =
        page1PlainText?.takeIf { it.isNotBlank() } ?: extractFirstPageStreamText(bytes)
    val textMeta =
        textToAnalyze?.let { extractMetadataFromPlainText(it, sourceUrl = sourceUrl) }
            ?: ExtractedDocumentMetadata()
    val combined = merged.fillMissingFrom(textMeta)
    val preferredPublishedAt =
        when {
            textMeta.publishedAt != null -> textMeta.publishedAt
            textMeta.releaseYear != null &&
                combined.publishedAt != null &&
                extractYearPrefix(combined.publishedAt) != textMeta.releaseYear -> null
            else -> combined.publishedAt
        }
    return combined.copy(
        publishedAt = preferredPublishedAt,
        releaseYear = textMeta.releaseYear ?: combined.releaseYear,
        summary = textMeta.summary ?: combined.summary,
    )
}

private fun hasPdfHeader(bytes: ByteArray): Boolean {
    val scanLen = bytes.size.coerceAtMost(PDF_HEADER_SCAN_BYTES)
    return "%PDF-" in String(bytes, 0, scanLen, Charsets.ISO_8859_1)
}

internal fun extractPdfInfoMetadata(
    bytes: ByteArray,
    sourceUrl: String? = null,
): ExtractedDocumentMetadata {
    val latin = String(bytes, Charsets.ISO_8859_1)
    val title = extractPdfDictString(latin, "/Title")?.takeIf { !isJunkPdfTitle(it, sourceUrl) }
    val authors = extractPdfDictString(latin, "/Author")?.let(::splitAuthorNames).orEmpty()
    val subject = extractPdfDictString(latin, "/Subject")?.takeIf { it.length >= MIN_SUBJECT_LEN }
    val rawDate =
        extractPdfDictString(latin, "/CreationDate") ?: extractPdfDictString(latin, "/ModDate")
    val parsedDate = rawDate?.let(::parsePdfDate)
    val doi =
        subject?.let(::extractDoiFromText)
            ?: extractPdfDictString(latin, "/DOI")?.let(::normalizeDoi)
    return ExtractedDocumentMetadata(
        title = title,
        authors = authors,
        publishedAt = parsedDate?.publishedAt,
        releaseYear = parsedDate?.releaseYear,
        summary = subject,
        doi = doi,
    )
}

internal fun extractPdfXmpMetadata(
    bytes: ByteArray,
    sourceUrl: String? = null,
): ExtractedDocumentMetadata {
    val latin = String(bytes, Charsets.ISO_8859_1)
    val start = latin.indexOf(XMP_OPEN_TAG)
    if (start < 0) return ExtractedDocumentMetadata()
    val end = latin.indexOf(XMP_CLOSE_TAG, startIndex = start)
    val slice =
        if (end > start) {
            latin.substring(start, (end + XMP_CLOSE_TAG.length).coerceAtMost(latin.length))
        } else {
            latin.substring(start, (start + MAX_XMP_SCAN_CHARS).coerceAtMost(latin.length))
        }
    val title = extractXmpTagValue(slice, "dc:title")?.takeIf { !isJunkPdfTitle(it, sourceUrl) }
    val authors = extractXmpListValues(slice, "dc:creator").flatMap(::splitAuthorNames)
    val desc = extractXmpTagValue(slice, "dc:description")?.takeIf { it.length >= MIN_SUBJECT_LEN }
    val rawDate =
        extractXmpTagValue(slice, "xmp:CreateDate")
            ?: extractXmpTagValue(slice, "dc:date")
            ?: extractXmpTagValue(slice, "pdf:CreationDate")
    val parsedDate = rawDate?.let(::parsePublicationDateStr)
    val doi =
        extractXmpTagValue(slice, "prism:doi")?.let(::normalizeDoi)
            ?: extractXmpTagValue(slice, "pdfx:doi")?.let(::normalizeDoi)
            ?: extractDoiFromText(slice)
    return ExtractedDocumentMetadata(
        title = title,
        authors = authors,
        publishedAt = parsedDate?.publishedAt,
        releaseYear = parsedDate?.releaseYear,
        summary = desc,
        doi = doi,
    )
}

private fun extractXmpTagValue(xmp: String, tag: String): String? {
    val regex =
        Regex(
            """<$tag\b[^>]*>(.*?)</$tag>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
    val inner = regex.find(xmp)?.groupValues?.getOrNull(1) ?: return null
    val liMatch = XMP_LI_REGEX.find(inner)?.groupValues?.getOrNull(1) ?: inner
    val clean = normalizeWhitespace(decodeHtmlEntities(liMatch.replace(XML_TAG_REGEX, " ")))
    return clean.takeIf { it.isNotEmpty() }
}

private fun extractXmpListValues(xmp: String, tag: String): List<String> {
    val regex =
        Regex(
            """<$tag\b[^>]*>(.*?)</$tag>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
    val inner = regex.find(xmp)?.groupValues?.getOrNull(1) ?: return emptyList()
    val items =
        XMP_LI_REGEX.findAll(inner)
            .map {
                normalizeWhitespace(
                    decodeHtmlEntities(it.groupValues[1].replace(XML_TAG_REGEX, " "))
                )
            }
            .filter { it.isNotEmpty() }
            .toList()
    return items.ifEmpty {
        listOfNotNull(
            normalizeWhitespace(decodeHtmlEntities(inner.replace(XML_TAG_REGEX, " "))).takeIf {
                it.isNotEmpty()
            }
        )
    }
}

private fun extractPdfDictString(latin: String, key: String): String? {
    var searchFrom = 0
    var found: String? = null
    var hasMore = true
    while (found == null && hasMore && searchFrom < latin.length) {
        val idx = latin.indexOf(key, startIndex = searchFrom)
        if (idx < 0) {
            hasMore = false
        } else {
            val afterKey = idx + key.length
            searchFrom = afterKey
            val exactKeyBoundary = afterKey >= latin.length || !latin[afterKey].isLetterOrDigit()
            if (exactKeyBoundary) {
                found = parsePdfDictValueAt(latin, skipPdfWhitespace(latin, afterKey))
            }
        }
    }
    return found
}

private fun parsePdfDictValueAt(latin: String, pos: Int): String? {
    if (pos >= latin.length) return null
    val parsed =
        when (latin[pos]) {
            '(' -> readPdfLiteralString(latin, pos)?.first
            '<' -> readPdfHexString(latin, pos)?.first
            else -> null
        }
    return parsed?.let(::normalizeWhitespace)?.takeIf { it.isNotEmpty() }
}

private fun skipPdfWhitespace(s: String, start: Int): Int {
    var i = start
    while (i < s.length && s[i] in PDF_WS_CHARS) i++
    return i
}

private fun readPdfLiteralString(s: String, openIdx: Int): Pair<String, Int>? {
    if (openIdx >= s.length || s[openIdx] != '(') return null
    val buf = ByteArrayOutputStream()
    var i = openIdx + 1
    var depth = 1
    while (i < s.length && depth > 0) {
        val ch = s[i]
        when {
            ch == '\\' && i + 1 < s.length -> {
                i = decodePdfEscape(s, i + 1, buf)
            }
            ch == '(' -> {
                depth++
                buf.write('('.code)
                i++
            }
            ch == ')' -> {
                depth--
                if (depth > 0) buf.write(')'.code)
                i++
            }
            else -> {
                buf.write(ch.code and BYTE_MASK)
                i++
            }
        }
    }
    return decodePdfStringBytes(buf.toByteArray()) to i
}

private fun decodePdfEscape(s: String, escIdx: Int, out: ByteArrayOutputStream): Int {
    val ch = s[escIdx]
    val simple = PDF_SIMPLE_ESCAPES[ch]
    return when {
        simple != null -> {
            out.write(simple)
            escIdx + 1
        }
        ch in '0'..'7' -> {
            decodePdfOctal(s, escIdx, out)
        }
        ch == '\r' || ch == '\n' -> {
            escIdx + 1
        }
        else -> {
            out.write(ch.code and BYTE_MASK)
            escIdx + 1
        }
    }
}

private fun decodePdfOctal(s: String, start: Int, out: ByteArrayOutputStream): Int {
    var valAcc = 0
    var i = start
    var count = 0
    while (i < s.length && count < OCTAL_MAX_DIGITS && s[i] in '0'..'7') {
        valAcc = valAcc * OCTAL_RADIX + (s[i] - '0')
        i++
        count++
    }
    out.write(valAcc and BYTE_MASK)
    return i
}

private fun readPdfHexString(s: String, openIdx: Int): Pair<String, Int>? {
    if (openIdx + 1 >= s.length || s[openIdx] != '<' || s[openIdx + 1] == '<') return null
    val closeIdx = s.indexOf('>', startIndex = openIdx + 1)
    if (closeIdx < 0) return null
    val hex = s.substring(openIdx + 1, closeIdx).filter { !it.isWhitespace() }
    val buf = ByteArrayOutputStream()
    var i = 0
    while (i < hex.length) {
        val hi = hex[i].digitToIntOrNull(HEX_RADIX) ?: return null
        val lo =
            if (i + 1 < hex.length) {
                hex[i + 1].digitToIntOrNull(HEX_RADIX) ?: return null
            } else {
                0
            }
        buf.write(hi shl HEX_NIBBLE_SHIFT or lo)
        i += HEX_PAIR_STEP
    }
    return decodePdfStringBytes(buf.toByteArray()) to closeIdx + 1
}

private fun decodePdfStringBytes(raw: ByteArray): String =
    when {
        raw.size >= BOM_LEN && raw[0] == UTF16_BE_BOM_0 && raw[1] == UTF16_BE_BOM_1 ->
            String(raw, BOM_LEN, raw.size - BOM_LEN, Charsets.UTF_16BE)
        raw.size >= BOM_LEN && raw[0] == UTF16_LE_BOM_0 && raw[1] == UTF16_LE_BOM_1 ->
            String(raw, BOM_LEN, raw.size - BOM_LEN, Charsets.UTF_16LE)
        else -> decodeTexLigatures(String(raw, Charsets.ISO_8859_1))
    }

private fun decodeTexLigatures(text: String): String =
    text
        .replace("\u000B", "ff")
        .replace("\u000C", "fi")
        .replace("\u000D", "fl")
        .replace("\u000E", "ffi")
        .replace("\u000F", "ffl")
        .replace("\uFB00", "ff")
        .replace("\uFB01", "fi")
        .replace("\uFB02", "fl")
        .replace("\uFB03", "ffi")
        .replace("\uFB04", "ffl")

internal fun extractFirstPageStreamText(bytes: ByteArray): String? {
    val latin = String(bytes, Charsets.ISO_8859_1)
    var searchFrom = 0
    var streamsChecked = 0
    var streamsScanned = 0
    val collected = StringBuilder()
    var keepGoing = true
    while (keepGoing && hasStreamBudget(searchFrom, latin.length, streamsChecked, streamsScanned)) {
        val streamPos = latin.indexOf(STREAM_KEYWORD, startIndex = searchFrom)
        val dataStart =
            if (streamPos >= 0) {
                advancePastStreamNewline(bytes, streamPos + STREAM_KEYWORD.length)
            } else {
                -1
            }
        val endPos =
            if (dataStart >= 0) latin.indexOf(ENDSTREAM_KEYWORD, startIndex = dataStart) else -1
        if (streamPos < 0 || endPos < 0) {
            keepGoing = false
        } else {
            val headerStart = (streamPos - HEADER_LOOKBACK_CHARS).coerceAtLeast(0)
            val rawHeader = latin.substring(headerStart, streamPos)
            val objBoundary =
                maxOf(rawHeader.lastIndexOf("endobj"), rawHeader.lastIndexOf("endstream"), 0)
            val header = rawHeader.substring(objBoundary)
            searchFrom = endPos + ENDSTREAM_KEYWORD.length
            streamsScanned++
            if (!isNonContentStreamHeader(header)) {
                val isFlate = "/FlateDecode" in header
                streamsChecked++
                appendDecodedStreamText(bytes.copyOfRange(dataStart, endPos), isFlate, collected)
                keepGoing = collected.length < TARGET_STREAM_TEXT_CHARS
            }
        }
    }
    return collected.toString().trim().takeIf { it.isNotEmpty() }
}

private fun hasStreamBudget(
    searchFrom: Int,
    latinLength: Int,
    streamsChecked: Int,
    streamsScanned: Int,
): Boolean =
    searchFrom < latinLength &&
        streamsChecked < MAX_STREAMS_TO_INSPECT &&
        streamsScanned < MAX_STREAMS_SCANNED

private fun isNonContentStreamHeader(header: String): Boolean = NON_CONTENT_STREAM_MARKERS.any {
    it in header
}

private fun appendDecodedStreamText(
    rawSlice: ByteArray,
    isFlate: Boolean,
    collected: StringBuilder,
) {
    val content = if (isFlate) inflateBounded(rawSlice) else rawSlice
    val extracted = content?.let { extractTextFromContentStream(String(it, Charsets.ISO_8859_1)) }
    if (!extracted.isNullOrBlank()) {
        collected.append(extracted).append('\n')
    }
}

private fun advancePastStreamNewline(bytes: ByteArray, start: Int): Int {
    var pos = start
    if (pos < bytes.size && bytes[pos] == '\r'.code.toByte()) pos++
    if (pos < bytes.size && bytes[pos] == '\n'.code.toByte()) pos++
    return pos
}

private fun inflateBounded(compressed: ByteArray): ByteArray? {
    val inflater = Inflater()
    return runCatching {
        inflater.setInput(compressed)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(INFLATE_CHUNK_BYTES)
        while (!inflater.finished() && out.size() < MAX_INFLATED_BYTES) {
            val count = inflater.inflate(buf)
            if (count == 0) break
            out.write(buf, 0, count)
        }
        out.toByteArray().takeIf { it.isNotEmpty() }
    }
        .getOrNull()
        .also { inflater.end() }
}

private fun extractTextFromContentStream(stream: String): String {
    if ("BT" !in stream || "Tj" !in stream && "TJ" !in stream) return ""
    val lines = ArrayList<String>()
    val cur = StringBuilder()
    var i = 0
    while (i < stream.length && lines.size < MAX_STREAM_LINES) {
        i = consumeStreamToken(stream, i, cur, lines)
    }
    flushStreamLine(cur, lines)
    return lines.joinToString("\n")
}

private fun consumeStreamToken(
    stream: String,
    index: Int,
    cur: StringBuilder,
    lines: MutableList<String>,
): Int {
    val ch = stream[index]
    return when {
        ch == '(' -> {
            appendStreamString(readPdfLiteralString(stream, index), index, cur)
        }
        ch == '<' && isHexStringStart(stream, index) -> {
            appendStreamString(readPdfHexString(stream, index), index, cur)
        }
        ch == 'T' && isLineBreakOp(stream, index) -> {
            flushStreamLine(cur, lines)
            index + TWO_CHAR_OP_LEN
        }
        ch == 'E' && stream.startsWith("ET", startIndex = index) -> {
            flushStreamLine(cur, lines)
            index + TWO_CHAR_OP_LEN
        }
        ch == '-' && isNegativeNumberStart(stream, index) -> {
            consumeTjKerning(stream, index, cur)
        }
        else -> {
            index + 1
        }
    }
}

private fun isHexStringStart(stream: String, index: Int): Boolean =
    index + 1 < stream.length && stream[index + 1] != '<'

private fun isNegativeNumberStart(stream: String, index: Int): Boolean =
    index + 1 < stream.length && stream[index + 1] in '0'..'9'

private fun appendStreamString(
    parsed: Pair<String, Int>?,
    fallbackIdx: Int,
    cur: StringBuilder,
): Int =
    if (parsed != null) {
        cur.append(parsed.first)
        parsed.second
    } else {
        fallbackIdx + 1
    }

private fun consumeTjKerning(stream: String, index: Int, cur: StringBuilder): Int {
    val endNum = readSignedNumberEnd(stream, index)
    val kern = stream.substring(index, endNum).toFloatOrNull() ?: 0f
    if (kern <= TJ_SPACE_KERN_THRESHOLD && cur.isNotEmpty() && cur.last() != ' ') {
        cur.append(' ')
    }
    return endNum
}

private fun isLineBreakOp(stream: String, idx: Int): Boolean {
    if (idx + 1 >= stream.length) return false
    val next = stream[idx + 1]
    val afterOk =
        idx + TWO_CHAR_OP_LEN >= stream.length || stream[idx + TWO_CHAR_OP_LEN].isWhitespace()
    return (next == 'd' || next == 'D' || next == '*' || next == 'm') && afterOk
}

private fun readSignedNumberEnd(stream: String, start: Int): Int {
    var i = start + 1
    while (i < stream.length && (stream[i] in '0'..'9' || stream[i] == '.')) i++
    return i
}

private fun flushStreamLine(cur: StringBuilder, lines: MutableList<String>) {
    val clean = normalizeWhitespace(cur.toString())
    if (clean.isNotEmpty()) {
        lines.add(clean)
    }
    cur.setLength(0)
}

private const val MIN_PDF_BYTES = 8
private const val PDF_HEADER_SCAN_BYTES = 1_024
private const val MIN_SUBJECT_LEN = 12
private const val XMP_OPEN_TAG = "<x:xmpmeta"
private const val XMP_CLOSE_TAG = "</x:xmpmeta>"
private const val MAX_XMP_SCAN_CHARS = 32_768
private const val BYTE_MASK = 0xFF
private const val OCTAL_MAX_DIGITS = 3
private const val OCTAL_RADIX = 8
private const val HEX_RADIX = 16
private const val HEX_NIBBLE_SHIFT = 4
private const val HEX_PAIR_STEP = 2
private const val BOM_LEN = 2
private const val UTF16_BE_BOM_0 = 0xFE.toByte()
private const val UTF16_BE_BOM_1 = 0xFF.toByte()
private const val UTF16_LE_BOM_0 = 0xFF.toByte()
private const val UTF16_LE_BOM_1 = 0xFE.toByte()
private const val STREAM_KEYWORD = "stream"
private const val ENDSTREAM_KEYWORD = "endstream"
private const val HEADER_LOOKBACK_CHARS = 256
private const val MAX_STREAMS_TO_INSPECT = 12
private const val MAX_STREAMS_SCANNED = 96
private const val TARGET_STREAM_TEXT_CHARS = 4_000
private const val INFLATE_CHUNK_BYTES = 4_096
private const val MAX_INFLATED_BYTES = 512 * 1024
private const val MAX_STREAM_LINES = 80
private const val TWO_CHAR_OP_LEN = 2
private const val TJ_SPACE_KERN_THRESHOLD = -140f

private val NON_CONTENT_STREAM_MARKERS =
    listOf("/Image", "/FontFile", "/DCTDecode", "/ObjStm", "/XRef")

private val PDF_SIMPLE_ESCAPES =
    mapOf(
        'n' to '\n'.code,
        'r' to '\r'.code,
        't' to '\t'.code,
        'b' to '\b'.code,
        'f' to '\u000C'.code,
    )
private val PDF_WS_CHARS = setOf(' ', '\t', '\r', '\n', '\u000C', '\u0000')
private val XMP_LI_REGEX =
    Regex(
        """<rdf:li\b[^>]*>(.*?)</rdf:li>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
private val XML_TAG_REGEX = Regex("""<[^>]+>""")

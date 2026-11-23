@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package com.grayvines.roche.reader

import com.grayvines.roche.model.resolveArticleImageUrl
import java.net.URI
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

internal data class ValidatedArticleImg(
    val url: String,
    val alt: String?,
    val width: Int?,
    val height: Int?,
)

internal fun parsePreprocessedHtmlBody(rawHtml: String, baseUrl: String? = null): Element {
    val doc = Jsoup.parseBodyFragment(rawHtml, baseUrl.orEmpty())
    doc.outputSettings().prettyPrint(false)
    val body = doc.body()
    body.select(STRIPPED_MEDIA_QUERY).remove()
    liftSemanticStylesAndClasses(body)
    synthesizeFootnoteIds(body, baseUrl)
    val knownIds = collectLocalAnchorIds(body)
    transformInlineImages(body, baseUrl)
    removeOrphanBacklinks(body, baseUrl, knownIds)
    sanitizeAnchorLinks(body, baseUrl, knownIds)
    unwrapLineNumberTables(body)
    transformDetailsElements(body)
    return body
}

internal data class NormalizedInlineContent(
    val html: String,
    val plainText: String,
    val maxLineLength: Int = 0,
)

internal fun prepareInlineHtmlForCompose(
    htmlFragment: String,
    baseUrl: String? = null,
    markBackgroundHex: String? = null,
): String {
    val hasMark = markBackgroundHex != null && htmlFragment.contains("<mark", ignoreCase = true)
    if (!hasMark && !containsCustomInlineTags(htmlFragment)) return htmlFragment
    val doc = Jsoup.parseBodyFragment(htmlFragment, baseUrl.orEmpty())
    doc.outputSettings().prettyPrint(false)
    val body = doc.body()
    sanitizeAnchorLinks(body, baseUrl)
    normalizeComposeInlineTags(body)
    if (markBackgroundHex != null) {
        rewriteMarkTags(body, markBackgroundHex)
    }
    return body.html()
}

internal fun applyMarkBackgroundHex(html: String, markBackgroundHex: String): String {
    if (!html.contains("<mark", ignoreCase = true)) return html
    val doc = Jsoup.parseBodyFragment(html)
    doc.outputSettings().prettyPrint(false)
    rewriteMarkTags(doc.body(), markBackgroundHex)
    return doc.body().html()
}

private fun rewriteMarkTags(root: Element, markBackgroundHex: String) {
    for (mark in root.select("mark").toList()) {
        mark.tagName("span")
        mark.attr("style", "background-color:$markBackgroundHex")
    }
}

internal fun normalizeComposeInlineTags(root: Element) {
    root.select("code, kbd, samp").forEach { it.tagName(TAG_TT) }
    root.select("var, dfn").forEach { it.tagName(TAG_EM) }
    root.select("ins").forEach { it.tagName(TAG_U) }
    for (quote in root.select("q").toList()) {
        if (hasVisibleDomText(quote)) {
            quote.prependText("“")
            quote.appendText("”")
            quote.unwrap()
        } else {
            quote.remove()
        }
    }
}

private fun containsCustomInlineTags(html: String): Boolean =
    html.contains("<a", ignoreCase = true) ||
        html.contains("<code", ignoreCase = true) ||
        html.contains("<kbd", ignoreCase = true) ||
        html.contains("<samp", ignoreCase = true) ||
        html.contains("<var", ignoreCase = true) ||
        html.contains("<dfn", ignoreCase = true) ||
        html.contains("<ins", ignoreCase = true) ||
        html.contains("<q", ignoreCase = true)

internal fun sanitizeAnchorLinks(
    root: Element,
    baseUrl: String?,
    knownIds: Map<String, String> = emptyMap(),
) {
    for (anchor in root.select("a").toList()) {
        val rawHref = anchor.attr("href")
        val localTarget = resolveLocalAnchorTarget(rawHref, baseUrl, knownIds)
        if (localTarget != null) {
            anchor.attr("href", "#$localTarget")
        } else if (isSamePageFragmentHref(rawHref, baseUrl)) {
            anchor.unwrap()
        } else {
            val safeHref = resolveSafeAnchorHref(rawHref, baseUrl)
            if (safeHref != null) {
                anchor.attr("href", safeHref)
            } else {
                anchor.unwrap()
            }
        }
    }
}

internal fun extractSamePageFragment(rawHref: String, baseUrl: String?): String? {
    val trimmed = rawHref.trim()
    val result =
        when {
            trimmed.isEmpty() -> null
            trimmed.startsWith("#") -> trimmed.substring(1).trim()
            else -> extractResolvedSamePageFrag(trimmed, baseUrl)
        }
    return result?.takeIf { it.isNotEmpty() }
}

private fun extractResolvedSamePageFrag(trimmedHref: String, baseUrl: String?): String? {
    val cleanBase = baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val resolved = runCatching {
        URI(cleanBase).resolve(trimmedHref).toString()
    }
        .getOrElse { trimmedHref }
    val hashIdx = resolved.indexOf('#')
    if (hashIdx <= 0) return null
    val baseWithoutFrag = cleanBase.substringBefore('#').trimEnd('/')
    val hrefWithoutFrag = resolved.substring(0, hashIdx).trimEnd('/')
    return if (
        baseWithoutFrag.isNotEmpty() && hrefWithoutFrag.equals(baseWithoutFrag, ignoreCase = true)
    ) {
        resolved.substring(hashIdx + 1).trim()
    } else {
        null
    }
}

internal fun isSamePageFragmentHref(rawHref: String, baseUrl: String?): Boolean =
    extractSamePageFragment(rawHref, baseUrl) != null

internal fun collectLocalAnchorIds(root: Element): Map<String, String> {
    val known = LinkedHashMap<String, String>()
    for (el in root.select("[id]")) {
        val id = el.attr("id").trim()
        if (id.isNotEmpty()) {
            known.putIfAbsent(id, id)
            known.putIfAbsent(id.removePrefix(PROSE_ID_PREFIX_STR), id)
        }
    }
    return known
}

internal fun resolveLocalAnchorTarget(
    rawHref: String,
    baseUrl: String?,
    knownIds: Map<String, String>,
): String? {
    if (knownIds.isEmpty()) return null
    val frag = extractSamePageFragment(rawHref, baseUrl) ?: return null
    return knownIds[frag] ?: knownIds[frag.removePrefix(PROSE_ID_PREFIX_STR)]
}

internal fun synthesizeFootnoteIds(root: Element, baseUrl: String?) {
    val existingIds = collectLocalAnchorIds(root).toMutableMap()
    val anchors = root.select("a").toList()
    val referencedFrags = HashSet<String>()
    for (anchor in anchors) {
        extractSamePageFragment(anchor.attr("href"), baseUrl)
            ?.removePrefix(PROSE_ID_PREFIX_STR)
            ?.takeIf { it.isNotEmpty() }
            ?.let(referencedFrags::add)
    }
    if (referencedFrags.isEmpty()) return
    for (anchor in anchors) {
        assignSyntheticFootnoteId(anchor, baseUrl, referencedFrags, existingIds)
    }
}

private fun assignSyntheticFootnoteId(
    anchor: Element,
    baseUrl: String?,
    referencedFrags: Set<String>,
    existingIds: MutableMap<String, String>,
) {
    val rawFrag = extractSamePageFragment(anchor.attr("href"), baseUrl) ?: return
    val normFrag = rawFrag.removePrefix(PROSE_ID_PREFIX_STR)
    val recip =
        reciprocalFootnoteIds(normFrag).firstOrNull { candidate ->
            candidate in referencedFrags && candidate !in existingIds
        } ?: return
    val synthId = "$PROSE_ID_PREFIX_STR$recip"
    val hostLi = anchor.parents().firstOrNull { it.normalName() == "li" }
    val targetEl =
        if (isFootnoteBacklink(anchor) && hostLi != null && hostLi.attr("id").isBlank()) {
            hostLi
        } else if (anchor.attr("id").isBlank()) {
            anchor
        } else {
            null
        }
    if (targetEl != null) {
        targetEl.attr("id", synthId)
        existingIds[synthId] = synthId
        existingIds[recip] = synthId
    }
}

internal fun reciprocalFootnoteIds(rawFrag: String): List<String> {
    val frag = rawFrag.removePrefix(PROSE_ID_PREFIX_STR)
    val linkCandidate = "$frag$FOOTNOTE_LINK_SUFFIX"
    return when {
        frag.endsWith(FOOTNOTE_LINK_SUFFIX) -> listOf(frag.removeSuffix(FOOTNOTE_LINK_SUFFIX))
        frag.startsWith("footnote_") -> listOf("identifier_${frag.removePrefix("footnote_")}")
        frag.startsWith("identifier_") -> listOf("footnote_${frag.removePrefix("identifier_")}")
        frag.startsWith("fnref") -> listOf("fn${frag.removePrefix("fnref")}")
        frag.startsWith("fn") -> listOf("fnref${frag.removePrefix("fn")}", linkCandidate)
        frag.startsWith("footnote-anchor-") ->
            listOf("footnote-${frag.removePrefix("footnote-anchor-")}")
        frag.startsWith("footnote-ref-") -> listOf("footnote-${frag.removePrefix("footnote-ref-")}")
        frag.startsWith("footnote-") ->
            listOf(
                "footnote-anchor-${frag.removePrefix("footnote-")}",
                "footnote-ref-${frag.removePrefix("footnote-")}",
                linkCandidate,
            )
        else -> reciprocalWikiOrNoteIds(frag, linkCandidate)
    }
}

private fun reciprocalWikiOrNoteIds(frag: String, linkCandidate: String): List<String> =
    when {
        frag.startsWith("cite_note-") -> listOf("cite_ref-${frag.removePrefix("cite_note-")}")
        frag.startsWith("cite_ref-") -> listOf("cite_note-${frag.removePrefix("cite_ref-")}")
        frag.startsWith("noteref-") -> listOf("note-${frag.removePrefix("noteref-")}")
        frag.startsWith("note-") -> listOf("noteref-${frag.removePrefix("note-")}", linkCandidate)
        else -> listOf(linkCandidate)
    }

internal fun resolveSafeAnchorHref(rawHref: String, baseUrl: String?): String? {
    val trimmed = rawHref.trim()
    if (trimmed.isEmpty()) return null
    val resolved =
        if (!baseUrl.isNullOrBlank()) {
            runCatching { URI(baseUrl.trim()).resolve(trimmed).toString() }.getOrElse { trimmed }
        } else {
            trimmed
        }
    return resolved.takeIf(::isAllowedAnchorUrl)
}

internal fun isAllowedAnchorUrl(url: String): Boolean {
    val lower = url.lowercase(Locale.US)
    return lower.startsWith("https://") && url.length > HTTPS_SCHEME_PREFIX_LEN ||
        lower.startsWith("http://") && url.length > HTTP_SCHEME_PREFIX_LEN ||
        lower.startsWith("mailto:") && url.length > MAILTO_SCHEME_PREFIX_LEN
}

internal fun hasVisibleDomText(element: Element): Boolean =
    element.text().replace('\u00A0', ' ').isNotBlank()

internal fun hasVisibleHtmlText(htmlFragment: String): Boolean {
    if (htmlFragment.isBlank()) return false
    if ('<' !in htmlFragment && '&' !in htmlFragment) {
        return htmlFragment.replace('\u00A0', ' ').isNotBlank()
    }
    return scanVisibleInlineHtmlText(htmlFragment)
}

internal fun stripInlineHtmlToPlainText(htmlFragment: String): String {
    if (htmlFragment.isBlank()) return ""
    if ('<' !in htmlFragment && '&' !in htmlFragment) {
        return htmlFragment.replace('\u00A0', ' ').trim()
    }
    return Jsoup.parseBodyFragment(htmlFragment).text().replace('\u00A0', ' ').trim()
}

internal fun computeDomCellMaxLineLength(cell: Element): Int {
    val lines = StringBuilder()
    appendDomTextWithLineBreaks(cell, lines)
    return lines.toString().lines().maxOfOrNull { it.replace('\u00A0', ' ').trim().length } ?: 0
}

private fun appendDomTextWithLineBreaks(node: Node, out: StringBuilder) {
    when (node) {
        is TextNode -> {
            out.append(node.text())
        }
        is Element -> {
            if (node.normalName() == TAG_BR) {
                out.append('\n')
            } else {
                node.childNodes().forEach { child -> appendDomTextWithLineBreaks(child, out) }
            }
        }
    }
}

private fun scanVisibleInlineHtmlText(html: String): Boolean {
    var index = 0
    val len = html.length
    while (index < len) {
        val next = stepPastInvisibleToken(html, index)
        if (next < 0) return true
        index = next
    }
    return false
}

private fun stepPastInvisibleToken(html: String, index: Int): Int =
    when (val ch = html[index]) {
        '<' -> advancePastHtmlTag(html, index)
        '&' -> advancePastInvisibleEntity(html, index)
        else -> if (ch.isWhitespace() || ch == '\u00A0') index + 1 else -1
    }

private fun advancePastHtmlTag(html: String, startIndex: Int): Int {
    val nextChar = html.getOrNull(startIndex + 1)
    val looksLikeTag =
        nextChar != null &&
            (nextChar.isLetter() || nextChar == '/' || nextChar == '!' || nextChar == '?')
    if (!looksLikeTag) return -1
    var cursor = startIndex + 1
    var quoteChar: Char? = null
    while (cursor < html.length) {
        val ch = html[cursor]
        if (quoteChar != null) {
            if (ch == quoteChar) quoteChar = null
        } else if (ch == '"' || ch == '\'') {
            quoteChar = ch
        } else if (ch == '>') {
            return cursor + 1
        }
        cursor++
    }
    return html.length
}

private fun advancePastInvisibleEntity(html: String, startIndex: Int): Int {
    val semi = html.indexOf(';', startIndex = startIndex + 1)
    if (semi < 0 || semi - startIndex > MAX_HTML_ENTITY_LEN) return -1
    val entity = html.substring(startIndex + 1, semi).lowercase(Locale.US)
    return if (isWhitespaceHtmlEntity(entity)) semi + 1 else -1
}

private fun isWhitespaceHtmlEntity(entity: String): Boolean =
    entity in WHITESPACE_NAMED_ENTITIES ||
        parseNumericEntityCodePoint(entity)?.let(::isWhitespaceCodePoint) == true

private fun parseNumericEntityCodePoint(entity: String): Int? =
    when {
        !entity.startsWith("#") -> null
        entity.startsWith("#x") -> entity.substring(2).toIntOrNull(HEX_RADIX)
        else -> entity.substring(1).toIntOrNull()
    }

private fun isWhitespaceCodePoint(codePoint: Int): Boolean =
    Character.isWhitespace(codePoint) ||
        codePoint == NBSP_CODE_POINT ||
        codePoint == ZERO_WIDTH_SPACE_CODE_POINT ||
        codePoint == BOM_CODE_POINT

internal fun extractPreCodeText(preElement: Element): String {
    val clone = preElement.clone()
    clone.select("br").forEach { it.replaceWith(TextNode("\n")) }
    return clone.wholeText().trim('\r', '\n')
}

internal fun ensureStrongHtml(html: String): String =
    if (html.startsWith("<strong>") && html.endsWith("</strong>")) {
        html
    } else {
        "<strong>$html</strong>"
    }

internal fun isFootnoteBackarrowAlt(alt: String?): Boolean =
    stripVariationChars(alt.orEmpty()).trim() == "↩"

internal fun parseHtmlPixelDimension(raw: String): Int? {
    val trimmed = raw.trim()
    if (trimmed.endsWith("%")) return null
    return trimmed.removeSuffix("px").removeSuffix("PX").trim().toIntOrNull()?.takeIf { it > 0 }
}

internal fun isSmallIconDimension(width: Int?, height: Int?): Boolean =
    width != null && width <= MIN_INLINE_IMG_DIMENSION_PX ||
        height != null && height <= MIN_INLINE_IMG_DIMENSION_PX

internal fun isInlineEmojiSource(src: String, classAttr: String?): Boolean {
    val lowerSrc = src.lowercase(Locale.US)
    val lowerClass = classAttr?.lowercase(Locale.US).orEmpty()
    return lowerSrc.contains("/images/core/emoji/") ||
        lowerSrc.contains("/wpcom-smileys/") ||
        lowerSrc.contains("/twemoji/") ||
        lowerClass.contains("wp-smiley") ||
        lowerClass.contains("emoji") ||
        lowerClass.contains("smiley")
}

internal fun validateArticleImgElement(img: Element, baseUrl: String?): ValidatedArticleImg? {
    val rawSrc = img.attr("src").trim().takeIf { it.isNotEmpty() }
    val width = img.attr("width").takeIf { it.isNotBlank() }?.let(::parseHtmlPixelDimension)
    val height = img.attr("height").takeIf { it.isNotBlank() }?.let(::parseHtmlPixelDimension)
    val classAttr = img.attr("class")
    val alt = img.attr("alt").ifBlank { img.attr("title") }.trim().takeIf { it.isNotEmpty() }

    val resolvedUrl = rawSrc?.let { resolveArticleImageUrl(it, baseUrl) }
    val isAllowedImage =
        !isSmallIconDimension(width, height) &&
            !isInlineEmojiSource(rawSrc.orEmpty(), classAttr) &&
            !isFootnoteBackarrowAlt(alt)

    return if (resolvedUrl != null && isAllowedImage) {
        ValidatedArticleImg(url = resolvedUrl, alt = alt, width = width, height = height)
    } else {
        null
    }
}

internal fun isFootnoteBackarrowSrc(src: String): Boolean {
    val lower = src.lowercase(Locale.US)
    return lower.endsWith("/21a9.png") || lower.endsWith("/21a9.svg")
}

internal fun removeOrphanBacklinks(
    root: Element,
    baseUrl: String? = null,
    knownIds: Map<String, String> = emptyMap(),
) {
    for (anchor in root.select("a").toList()) {
        val href = anchor.attr("href")
        if (!isFootnoteBacklink(anchor)) continue
        if (resolveLocalAnchorTarget(href, baseUrl, knownIds) != null) {
            normalizeBacklinkArrowText(anchor)
        } else if (isSamePageFragmentHref(href, baseUrl)) {
            stripLeadingVariationChars(anchor.nextSibling())
            anchor.remove()
        }
    }
}

private fun normalizeBacklinkArrowText(anchor: Element) {
    if (anchor.select(TAG_IMG).isEmpty() && stripVariationChars(anchor.text()).trim() == "↩") {
        anchor.text(FOOTNOTE_BACKARROW_TEXT)
    }
}

internal fun isFootnoteBacklink(anchor: Element): Boolean {
    val imgs = anchor.select(TAG_IMG)
    val cleanText = stripVariationChars(anchor.text()).trim()
    val hasOnlyArrowImgs =
        imgs.isNotEmpty() &&
            imgs.all {
                isFootnoteBackarrowAlt(it.attr("alt").trim()) ||
                    isFootnoteBackarrowSrc(it.attr("src").trim())
            }
    val isBacklinkText =
        cleanText in FOOTNOTE_BACKLINK_TEXTS || cleanText.equals("[return]", ignoreCase = true)
    return cleanText.isEmpty() && hasOnlyArrowImgs || imgs.isEmpty() && isBacklinkText
}

internal fun stripLeadingVariationChars(node: Node?) {
    val textNode = node as? TextNode ?: return
    val updated = textNode.wholeText.trimStart('\uFE0E', '\uFE0F')
    if (updated != textNode.wholeText) {
        textNode.text(updated)
    }
}

internal fun stripVariationChars(text: String): String =
    text.replace("\uFE0E", "").replace("\uFE0F", "")

internal fun transformInlineImages(root: Element, baseUrl: String?) {
    for (img in root.select(TAG_IMG).toList()) {
        val src = img.attr("src").lowercase(Locale.US)
        val classAttr = img.attr("class").lowercase(Locale.US)
        val alt = img.attr("alt").ifBlank { img.attr("title") }.trim()
        when {
            isFootnoteBackarrowAlt(alt) || isFootnoteBackarrowSrc(src) -> {
                stripLeadingVariationChars(img.nextSibling())
                img.replaceWith(TextNode(FOOTNOTE_BACKARROW_TEXT))
            }
            isInlineLatexSource(src, classAttr) -> {
                replaceLatexImageNode(img, alt)
            }
            isInlineEmojiSource(src, classAttr) -> {
                img.replaceWith(TextNode(alt))
            }
            validateArticleImgElement(img, baseUrl) == null -> {
                removeNodeAndEmptyAnchor(img)
            }
        }
    }
}

internal fun replaceLatexImageNode(img: Element, alt: String) {
    if (alt.isNotEmpty()) {
        img.replaceWith(Element(TAG_EM).html(formatLatexAltToHtml(alt)))
    } else {
        removeNodeAndEmptyAnchor(img)
    }
}

internal fun removeNodeAndEmptyAnchor(node: Node) {
    val parent = node.parent() as? Element
    node.remove()
    if (
        parent?.normalName() == "a" &&
            !hasVisibleDomText(parent) &&
            parent.selectFirst(TAG_IMG) == null
    ) {
        parent.remove()
    }
}

internal fun isInlineLatexSource(src: String, classAttr: String): Boolean =
    src.contains("/latex.php") || classAttr.contains("latex")

internal fun formatLatexAltToHtml(rawLatex: String): String {
    val trimmed = rawLatex.trim().removeSurrounding("$")
    val formatted =
        LATEX_COMMAND_REGEX.replace(trimmed) { match ->
            val cmd = match.value
            if (cmd in LATEX_FONT_COMMANDS) {
                ""
            } else {
                LATEX_SYMBOL_REPLACEMENTS[cmd] ?: cmd
            }
        }
    return convertLatexScripts(formatted)
}

internal fun convertLatexScripts(text: String): String {
    val sb = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val ch = text[index]
        val consumed =
            if ((ch == '^' || ch == '_') && index + 1 < text.length) {
                appendLatexScriptToken(text, index, sb)
            } else {
                0
            }
        if (consumed > 0) {
            index += consumed
        } else {
            if (ch != '{' && ch != '}') {
                appendHtmlEscapedChar(sb, ch)
            }
            index++
        }
    }
    return collapseSpaces(sb.toString()).trim()
}

internal fun appendLatexScriptToken(text: String, index: Int, out: StringBuilder): Int {
    val tag = if (text[index] == '^') "sup" else "sub"
    val next = text[index + 1]
    val closeIdx =
        if (next == '{') text.indexOf('}', startIndex = index + LATEX_SCRIPT_BRACE_OFFSET) else -1
    return when {
        closeIdx > index + LATEX_SCRIPT_BRACE_OFFSET -> {
            val inner = escapeHtmlText(text.substring(index + LATEX_SCRIPT_BRACE_OFFSET, closeIdx))
            out.append("<$tag>$inner</$tag>")
            closeIdx - index + 1
        }
        isSingleLatexScriptChar(next) -> {
            out.append("<$tag>$next</$tag>")
            LATEX_SCRIPT_BRACE_OFFSET
        }
        else -> {
            0
        }
    }
}

internal fun isSingleLatexScriptChar(ch: Char): Boolean =
    ch.isLetterOrDigit() || ch == '+' || ch == '-' || ch == '='

internal fun escapeHtmlText(raw: String): String {
    val sb = StringBuilder(raw.length)
    for (ch in raw) {
        appendHtmlEscapedChar(sb, ch)
    }
    return sb.toString()
}

internal fun appendHtmlEscapedChar(out: StringBuilder, ch: Char) {
    when (ch) {
        '&' -> out.append("&amp;")
        '<' -> out.append("&lt;")
        '>' -> out.append("&gt;")
        else -> out.append(ch)
    }
}

internal fun collapseSpaces(text: String): String {
    val sb = StringBuilder(text.length)
    var prevWhitespace = false
    for (ch in text) {
        if (ch.isWhitespace()) {
            if (!prevWhitespace) {
                sb.append(' ')
                prevWhitespace = true
            }
        } else {
            sb.append(ch)
            prevWhitespace = false
        }
    }
    return sb.toString()
}

internal fun unwrapLineNumberTables(root: Element) {
    for (table in root.select(TAG_TABLE).toList()) {
        extractLineNumberTablePre(table)?.let { codePre -> table.replaceWith(codePre) }
    }
}

internal fun extractLineNumberTablePre(table: Element): Element? {
    val pres = table.select(TAG_PRE)
    val isSingleTableWithTwoPres =
        table.select(TAG_TABLE).size == 1 && pres.size == LINE_NUMBER_TABLE_PRE_COUNT
    if (!isSingleTableWithTwoPres) return null
    val firstPreText = extractPreCodeText(pres[0]).trim()
    return if (isLineNumbersOnlyText(firstPreText)) pres[1].clone() else null
}

internal fun isLineNumbersOnlyText(text: String): Boolean =
    text.isNotEmpty() && text.any { it.isDigit() } && text.all { it.isDigit() || it.isWhitespace() }

internal fun transformDetailsElements(root: Element) {
    for (details in root.select("details").toList()) {
        for (summary in details.children().filter { it.normalName() == "summary" }) {
            val strong = Element(TAG_STRONG).html("▾ ${summary.html().trim()}")
            val paragraph = Element(TAG_P).appendChild(strong)
            summary.replaceWith(paragraph)
        }
        details.unwrap()
    }
}

internal fun normalizeDomElementInlineHtml(
    element: Element,
    isInsideList: Boolean = false,
): String = normalizeDomInlineContent(element, isInsideList).html

internal fun normalizeDomInlineContent(
    element: Element,
    isInsideList: Boolean = false,
): NormalizedInlineContent {
    val clone = element.clone()
    clone.select(STRIPPED_INLINE_MEDIA_QUERY).remove()
    flattenNestedQuotesInDom(clone)
    flattenInlinePreAndTables(clone)
    flattenListsInDom(clone, rootIsListItem = isInsideList)
    unwrapParagraphsInDom(clone)
    unwrapContainerTagsInDom(clone)
    normalizeComposeInlineTags(clone)
    normalizeTextNodesInDom(clone)
    trimEdgeBrNodes(clone)
    val plainText = clone.text().replace('\u00A0', ' ').trim()
    if (plainText.isEmpty()) {
        return NormalizedInlineContent(html = "", plainText = "", maxLineLength = 0)
    }
    val html = collapseExcessBrTags(clone.html().replace("<br>", BR_XHTML)).trim()
    val maxLineLength = computeDomCellMaxLineLength(clone)
    return NormalizedInlineContent(
        html = html,
        plainText = plainText,
        maxLineLength = maxLineLength,
    )
}

internal fun flattenNestedQuotesInDom(root: Element) {
    for (nested in root.select(TAG_BLOCKQUOTE).toList()) {
        if (nested != root) {
            nested.tagName(TAG_P)
        }
    }
}

internal fun flattenInlinePreAndTables(root: Element) {
    for (pre in root.select(TAG_PRE).toList()) {
        if (pre != root) {
            val codeText = extractPreCodeText(pre)
            if (codeText.isNotEmpty()) {
                val formattedLines =
                    codeText.lines().joinToString(BR_HTML) { line ->
                        val leadingSpaces = line.takeWhile { it == ' ' }.length
                        val indent = "&nbsp;".repeat(leadingSpaces)
                        "$indent<tt>${escapeHtmlText(line.trimStart(' '))}</tt>"
                    }
                pre.before("$BR_HTML$formattedLines$BR_HTML")
            }
            pre.remove()
        }
    }
    for (table in root.select(TAG_TABLE).toList()) {
        if (table != root) {
            val rowHtmls =
                table.select("tr").mapNotNull { tr ->
                    val cells =
                        tr.children()
                            .filter { it.normalName() == "th" || it.normalName() == "td" }
                            .map { it.text().trim() }
                            .filter { it.isNotEmpty() }
                    cells.takeIf { it.isNotEmpty() }?.joinToString(" · ") { escapeHtmlText(it) }
                }
            if (rowHtmls.isNotEmpty()) {
                table.before(BR_HTML + rowHtmls.joinToString(BR_HTML) + BR_HTML)
            }
            table.remove()
        }
    }
}

internal fun flattenListsInDom(root: Element, rootIsListItem: Boolean) {
    var pass = 0
    while (pass < MAX_NESTING_PASSES) {
        val leafLists = root.select("ul, ol").filter { it != root && it.select("ul, ol").size == 1 }
        if (leafLists.isEmpty()) break
        leafLists.forEach { leaf -> flattenSingleLeafList(leaf, root, rootIsListItem) }
        pass++
    }
}

private fun flattenSingleLeafList(leaf: Element, root: Element, rootIsListItem: Boolean) {
    val nestedInList =
        rootIsListItem ||
            leaf.parents().any {
                it != root && (it.normalName() == "ul" || it.normalName() == "ol")
            }
    val ordered = leaf.normalName() == "ol"
    val indent = if (nestedInList) "&nbsp;&nbsp;" else ""
    val bullet = if (nestedInList) "◦" else "•"
    val inlineListHtml = formatDomListItemsInline(leaf, ordered, indent, bullet)
    trimWhitespaceBeforeNode(leaf)
    if (inlineListHtml.isNotEmpty()) {
        leaf.before(inlineListHtml)
    }
    leaf.remove()
}

internal fun formatDomListItemsInline(
    listElement: Element,
    ordered: Boolean,
    indent: String,
    bulletSymbol: String,
): String {
    val items =
        selectOwnListItems(listElement)
            .map { formatListItemElementHtml(it, listElement) }
            .filter(::hasVisibleHtmlText)
    if (items.isEmpty()) return ""
    val startIdx = listElement.attr("start").toIntOrNull()?.takeIf { it > 0 } ?: 1
    return items
        .mapIndexed { idx, item ->
            val marker = if (ordered) "${startIdx + idx}." else bulletSymbol
            "$BR_HTML$indent$marker $item"
        }
        .joinToString("")
}

internal fun selectOwnListItems(listElement: Element): List<Element> =
    listElement.select("li").filter { li ->
        li.parents().firstOrNull { it.normalName() == "ul" || it.normalName() == "ol" } ==
            listElement
    }

internal fun formatListItemElementHtml(li: Element, listElement: Element): String {
    val normalized = normalizeDomElementInlineHtml(li, isInsideList = true)
    val wrapperTag =
        li.parent()
            ?.takeIf { it != listElement }
            ?.normalName()
            ?.takeIf { it in INLINE_LIST_WRAPPER_TAGS }
    return if (wrapperTag != null && hasVisibleHtmlText(normalized)) {
        "<$wrapperTag>$normalized</$wrapperTag>"
    } else {
        normalized
    }
}

internal fun unwrapParagraphsInDom(root: Element) {
    for (p in root.select(TAG_P).toList()) {
        if (p != root) {
            trimWhitespaceBeforeNode(p)
            trimWhitespaceAfterNode(p)
            if (hasVisibleSiblingAfter(p)) {
                p.after(DOUBLE_BR_HTML)
            }
            p.unwrap()
        }
    }
}

internal fun unwrapContainerTagsInDom(root: Element) {
    for (container in root.select(INLINE_UNWRAP_CONTAINERS_QUERY).toList()) {
        if (container != root) {
            container.unwrap()
        }
    }
}

internal fun normalizeTextNodesInDom(root: Element) {
    val textNodes = mutableListOf<TextNode>()
    collectTextNodes(root, textNodes)
    for (textNode in textNodes) {
        val raw = textNode.wholeText.replace("\r\n", "\n").replace('\r', '\n')
        if ('\n' in raw) {
            replaceNewlinesInTextNode(textNode, raw)
        }
    }
}

internal fun collectTextNodes(node: Node, out: MutableList<TextNode>) {
    for (child in node.childNodes()) {
        if (child is TextNode) {
            out.add(child)
        } else {
            collectTextNodes(child, out)
        }
    }
}

internal fun replaceNewlinesInTextNode(textNode: TextNode, raw: String) {
    val core = raw.trim(' ', '\t', '\n')
    if (core.isEmpty()) {
        replaceBlankTextNode(textNode, raw)
        return
    }
    val nodes = buildNodesFromMultilineText(raw, core)
    if (nodes.size == 1 && nodes[0] is TextNode) {
        textNode.text((nodes[0] as TextNode).wholeText)
    } else {
        for (node in nodes) {
            textNode.before(node)
        }
        textNode.remove()
    }
}

internal fun replaceBlankTextNode(textNode: TextNode, raw: String) {
    if (raw.count { it == '\n' } >= BLANK_LINE_NEWLINES) {
        textNode.before(Element(TAG_BR))
        textNode.before(Element(TAG_BR))
        textNode.remove()
    } else {
        textNode.text(" ")
    }
}

internal fun buildNodesFromMultilineText(raw: String, core: String): List<Node> {
    val leadingWs = raw.takeWhile { it.isWhitespace() }
    val trailingWs = raw.takeLastWhile { it.isWhitespace() }
    val leadingBreak = leadingWs.count { it == '\n' } >= BLANK_LINE_NEWLINES
    val trailingBreak = trailingWs.count { it == '\n' } >= BLANK_LINE_NEWLINES
    val nodes = mutableListOf<Node>()
    if (leadingBreak) {
        appendDoubleBrNodes(nodes)
    }
    val currentText = StringBuilder(if (!leadingBreak && leadingWs.isNotEmpty()) " " else "")
    appendMultilineCoreNodes(core, currentText, nodes)
    if (!trailingBreak && trailingWs.isNotEmpty()) {
        currentText.append(' ')
    }
    flushTextBuilderToNodes(currentText, nodes)
    if (trailingBreak) {
        appendDoubleBrNodes(nodes)
    }
    return nodes
}

internal fun appendMultilineCoreNodes(
    core: String,
    currentText: StringBuilder,
    nodes: MutableList<Node>,
) {
    var blankLinePending = false
    for ((idx, line) in core.split('\n').withIndex()) {
        val clean = line.trim(' ', '\t')
        if (clean.isEmpty()) {
            blankLinePending = true
        } else if (idx == 0) {
            currentText.append(clean)
        } else if (clean.startsWith(PLAIN_BULLET_PREFIX)) {
            flushTextBuilderToNodes(currentText, nodes)
            nodes.add(Element(TAG_BR))
            currentText.append("• ").append(clean.removePrefix(PLAIN_BULLET_PREFIX))
            blankLinePending = false
        } else if (blankLinePending) {
            flushTextBuilderToNodes(currentText, nodes)
            appendDoubleBrNodes(nodes)
            currentText.append(clean)
            blankLinePending = false
        } else {
            currentText.append(' ').append(clean)
        }
    }
}

internal fun appendDoubleBrNodes(out: MutableList<Node>) {
    out.add(Element(TAG_BR))
    out.add(Element(TAG_BR))
}

internal fun flushTextBuilderToNodes(sb: StringBuilder, out: MutableList<Node>) {
    if (sb.isNotEmpty()) {
        out.add(TextNode(sb.toString()))
        sb.clear()
    }
}

internal fun trimEdgeBrNodes(root: Element) {
    trimLeadingBrAndBlankText(root)
    trimTrailingBrAndBlankText(root)
}

private fun trimLeadingBrAndBlankText(root: Element) {
    while (root.childNodeSize() > 0) {
        val first = root.childNode(0)
        if (isRemovableEdgeNode(first)) {
            first.remove()
        } else {
            (first as? TextNode)?.let { it.text(it.wholeText.trimStart()) }
            break
        }
    }
}

private fun trimTrailingBrAndBlankText(root: Element) {
    while (root.childNodeSize() > 0) {
        val last = root.childNode(root.childNodeSize() - 1)
        if (isRemovableEdgeNode(last)) {
            last.remove()
        } else {
            (last as? TextNode)?.let { it.text(it.wholeText.trimEnd()) }
            break
        }
    }
}

private fun isRemovableEdgeNode(node: Node): Boolean =
    node is TextNode && node.wholeText.isBlank() || node is Element && node.normalName() == TAG_BR

private fun trimWhitespaceBeforeNode(node: Node) {
    val prev = node.previousSibling() as? TextNode ?: return
    val trimmed = prev.wholeText.trimEnd()
    if (trimmed.isEmpty()) {
        prev.remove()
    } else if (trimmed != prev.wholeText) {
        prev.text(trimmed)
    }
}

private fun trimWhitespaceAfterNode(node: Node) {
    val next = node.nextSibling() as? TextNode ?: return
    val trimmed = next.wholeText.trimStart()
    if (trimmed.isEmpty()) {
        next.remove()
    } else if (trimmed != next.wholeText) {
        next.text(trimmed)
    }
}

private fun hasVisibleSiblingAfter(node: Node): Boolean {
    var sibling = node.nextSibling()
    var found = false
    while (sibling != null && !found) {
        found =
            sibling is TextNode && sibling.wholeText.isNotBlank() ||
                sibling is Element && sibling.normalName() != TAG_BR && hasVisibleDomText(sibling)
        sibling = sibling.nextSibling()
    }
    return found
}

internal fun collapseExcessBrTags(html: String): String {
    var current = html
    while ("<br/><br/><br/>" in current) {
        current = current.replace("<br/><br/><br/>", DOUBLE_BR_XHTML)
    }
    return current
}

private const val MIN_INLINE_IMG_DIMENSION_PX = 32
private const val MAX_NESTING_PASSES = 4
private const val LINE_NUMBER_TABLE_PRE_COUNT = 2
private const val LATEX_SCRIPT_BRACE_OFFSET = 2
private const val BLANK_LINE_NEWLINES = 2
private const val HTTPS_SCHEME_PREFIX_LEN = 8
private const val HTTP_SCHEME_PREFIX_LEN = 7
private const val MAILTO_SCHEME_PREFIX_LEN = 7
private const val PLAIN_BULLET_PREFIX = "- "
private const val MAX_HTML_ENTITY_LEN = 12
private const val HEX_RADIX = 16
private const val NBSP_CODE_POINT = 0x00A0
private const val ZERO_WIDTH_SPACE_CODE_POINT = 0x200B
private const val BOM_CODE_POINT = 0xFEFF
private val WHITESPACE_NAMED_ENTITIES =
    setOf("nbsp", "ensp", "emsp", "thinsp", "tab", "newline", "zwnj", "zwj")

private const val TAG_IMG = "img"
private const val TAG_PRE = "pre"
private const val TAG_TABLE = "table"
private const val TAG_BLOCKQUOTE = "blockquote"
private const val TAG_P = "p"
private const val TAG_BR = "br"
private const val TAG_EM = "em"
private const val TAG_STRONG = "strong"
private const val TAG_TT = "tt"
private const val TAG_U = "u"
private const val PROSE_ID_PREFIX_STR = "prose-"
private const val FOOTNOTE_LINK_SUFFIX = "-link"
private const val FOOTNOTE_BACKARROW_TEXT = "↩\uFE0E"
private val FOOTNOTE_BACKLINK_TEXTS = setOf("↩", "⤴", "↑", "↵")

private const val BR_HTML = "<br>"
private const val DOUBLE_BR_HTML = "<br><br>"
internal const val BR_XHTML = "<br/>"
internal const val DOUBLE_BR_XHTML = "<br/><br/>"

private const val STRIPPED_MEDIA_QUERY =
    "svg, iframe, script, style, noscript, template, object, embed"
private const val STRIPPED_INLINE_MEDIA_QUERY =
    "img, figure, svg, iframe, script, style, noscript, template, object, embed"
private const val INLINE_UNWRAP_CONTAINERS_QUERY =
    "div, section, article, header, footer, main, aside, details, summary, center, span"

private val INLINE_LIST_WRAPPER_TAGS = setOf("b", "strong", "i", "em")

private val LATEX_COMMAND_REGEX = Regex("""\\[A-Za-z]+""")

private val LATEX_FONT_COMMANDS =
    setOf(
        """\mathrm""",
        """\mathbf""",
        """\mathit""",
        """\mathsf""",
        """\mathtt""",
        """\mathcal""",
        """\mathbb""",
        """\text""",
        """\textbf""",
        """\textit""",
        """\textrm""",
        """\texttt""",
        """\operatorname""",
    )

private val LATEX_SYMBOL_REPLACEMENTS =
    mapOf(
        """\geq""" to " ≥ ",
        """\ge""" to " ≥ ",
        """\leq""" to " ≤ ",
        """\le""" to " ≤ ",
        """\neq""" to " ≠ ",
        """\ne""" to " ≠ ",
        """\approx""" to " ≈ ",
        """\equiv""" to " ≡ ",
        """\sim""" to " ∼ ",
        """\times""" to " × ",
        """\cdot""" to " · ",
        """\pm""" to " ± ",
        """\infty""" to "∞",
        """\notin""" to " ∉ ",
        """\in""" to " ∈ ",
        """\subseteq""" to " ⊆ ",
        """\subset""" to " ⊂ ",
        """\supseteq""" to " ⊇ ",
        """\supset""" to " ⊃ ",
        """\cup""" to " ∪ ",
        """\cap""" to " ∩ ",
        """\forall""" to "∀",
        """\exists""" to "∃",
        """\emptyset""" to "∅",
        """\rightarrow""" to " → ",
        """\to""" to " → ",
        """\leftarrow""" to " ← ",
        """\mapsto""" to " ↦ ",
        """\Rightarrow""" to " ⇒ ",
        """\iff""" to " ⇔ ",
        """\ell""" to "ℓ",
        """\alpha""" to "α",
        """\beta""" to "β",
        """\gamma""" to "γ",
        """\delta""" to "δ",
        """\epsilon""" to "ε",
        """\varepsilon""" to "ε",
        """\zeta""" to "ζ",
        """\eta""" to "η",
        """\theta""" to "θ",
        """\lambda""" to "λ",
        """\mu""" to "μ",
        """\nu""" to "ν",
        """\pi""" to "π",
        """\rho""" to "ρ",
        """\sigma""" to "σ",
        """\tau""" to "τ",
        """\phi""" to "φ",
        """\varphi""" to "φ",
        """\chi""" to "χ",
        """\psi""" to "ψ",
        """\omega""" to "ω",
        """\Gamma""" to "Γ",
        """\Delta""" to "Δ",
        """\Theta""" to "Θ",
        """\Lambda""" to "Λ",
        """\Pi""" to "Π",
        """\Sigma""" to "Σ",
        """\Phi""" to "Φ",
        """\Psi""" to "Ψ",
        """\Omega""" to "Ω",
    )

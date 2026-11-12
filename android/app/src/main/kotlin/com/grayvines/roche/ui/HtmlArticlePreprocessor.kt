@file:Suppress("MatchingDeclarationName")

package com.grayvines.roche.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.em
import java.net.URI
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

internal fun parsePreprocessedHtmlBody(rawHtml: String, baseUrl: String? = null): Element {
    val doc = Jsoup.parseBodyFragment(rawHtml, baseUrl.orEmpty())
    doc.outputSettings().prettyPrint(false)
    val body = doc.body()
    body.select(STRIPPED_MEDIA_QUERY).remove()
    removeFootnoteBacklinks(body)
    transformInlineImages(body, baseUrl)
    sanitizeAnchorLinks(body, baseUrl)
    unwrapLineNumberTables(body)
    transformDetailsElements(body)
    return body
}

internal fun prepareInlineHtmlForCompose(htmlFragment: String, baseUrl: String? = null): String {
    if (!containsCustomInlineTags(htmlFragment)) return htmlFragment
    val doc = Jsoup.parseBodyFragment(htmlFragment, baseUrl.orEmpty())
    doc.outputSettings().prettyPrint(false)
    val body = doc.body()
    sanitizeAnchorLinks(body, baseUrl)
    body.select("code, kbd, samp").forEach { it.tagName(TAG_TT) }
    body.select("var").forEach { it.tagName(TAG_EM) }
    return body.html()
}

private fun containsCustomInlineTags(html: String): Boolean =
    html.contains("<a", ignoreCase = true) ||
        html.contains("<code", ignoreCase = true) ||
        html.contains("<kbd", ignoreCase = true) ||
        html.contains("<samp", ignoreCase = true) ||
        html.contains("<var", ignoreCase = true)

internal fun sanitizeAnchorLinks(root: Element, baseUrl: String?) {
    for (anchor in root.select("a").toList()) {
        val safeHref = resolveSafeAnchorHref(anchor.attr("href"), baseUrl)
        if (safeHref != null) {
            anchor.attr("href", safeHref)
        } else {
            anchor.unwrap()
        }
    }
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

internal fun htmlToComposeAnnotatedString(
    html: String,
    linkStyles: TextLinkStyles,
    codeBackground: Color,
    baseUrl: String? = null,
    linkInteractionListener: LinkInteractionListener? = null,
): AnnotatedString {
    val prepared = prepareInlineHtmlForCompose(html, baseUrl)
    val base =
        AnnotatedString.fromHtml(
            htmlString = prepared,
            linkStyles = linkStyles,
            linkInteractionListener = linkInteractionListener,
        )
    val needsCustomSpans =
        base.spanStyles.any {
            it.item.fontFamily == FontFamily.Monospace || isSubOrSuperscript(it.item)
        }
    return if (!needsCustomSpans) {
        base
    } else {
        applyRichInlineSpanStyles(base, codeBackground)
    }
}

@Composable
internal fun rememberRichHtmlText(
    html: String,
    linkStyles: TextLinkStyles,
    codeBackground: Color,
    baseUrl: String? = null,
    onOpenUrl: ((String) -> Unit)? = null,
): AnnotatedString {
    val uriHandler = LocalUriHandler.current
    val currentOnOpenUrl by rememberUpdatedState(onOpenUrl)
    val currentUriHandler by rememberUpdatedState(uriHandler)
    val linkListener =
        remember(baseUrl) {
            LinkInteractionListener { link ->
                handleRichHtmlLinkClick(
                    link = link,
                    baseUrl = baseUrl,
                    onOpenUrl = currentOnOpenUrl,
                    fallbackOpenUri = { url -> currentUriHandler.openUri(url) },
                )
            }
        }
    return remember(html, linkStyles, codeBackground, baseUrl, linkListener) {
        htmlToComposeAnnotatedString(
            html = html,
            linkStyles = linkStyles,
            codeBackground = codeBackground,
            baseUrl = baseUrl,
            linkInteractionListener = linkListener,
        )
    }
}

private fun handleRichHtmlLinkClick(
    link: LinkAnnotation,
    baseUrl: String?,
    onOpenUrl: ((String) -> Unit)?,
    fallbackOpenUri: (String) -> Unit,
) {
    val rawUrl = (link as? LinkAnnotation.Url)?.url ?: return
    val safeUrl = resolveSafeAnchorHref(rawUrl, baseUrl) ?: return
    if (onOpenUrl != null) {
        runCatching { onOpenUrl(safeUrl) }
    } else {
        runCatching { fallbackOpenUri(safeUrl) }
    }
}

private fun isSubOrSuperscript(style: SpanStyle): Boolean =
    style.baselineShift == BaselineShift.Superscript ||
        style.baselineShift == BaselineShift.Subscript

private fun applyRichInlineSpanStyles(
    base: AnnotatedString,
    codeBackground: Color,
): AnnotatedString = buildAnnotatedString {
    append(base)
    for (span in base.spanStyles) {
        if (span.item.fontFamily == FontFamily.Monospace) {
            addStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBackground,
                    fontSize = 0.92.em,
                ),
                span.start,
                span.end,
            )
        }
        if (isSubOrSuperscript(span.item)) {
            addStyle(SpanStyle(fontSize = 0.75.em), span.start, span.end)
        }
    }
}

internal fun removeFootnoteBacklinks(root: Element) {
    for (anchor in root.select("a").toList()) {
        if (anchor.attr("href").trim().startsWith("#") && isFootnoteBacklink(anchor)) {
            stripLeadingVariationChars(anchor.nextSibling())
            anchor.remove()
        }
    }
}

internal fun isFootnoteBacklink(anchor: Element): Boolean {
    val imgs = anchor.select(TAG_IMG)
    val cleanText = stripVariationChars(anchor.text()).trim()
    val hasOnlyArrowImgs =
        imgs.isNotEmpty() && imgs.all { isFootnoteBackarrowAlt(it.attr("alt").trim()) }
    return cleanText.isEmpty() && hasOnlyArrowImgs || imgs.isEmpty() && cleanText == "↩"
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
            isFootnoteBackarrowAlt(alt) -> {
                stripLeadingVariationChars(img.nextSibling())
                removeNodeAndEmptyAnchor(img)
            }
            isInlineLatexSource(src, classAttr) -> {
                replaceLatexImageNode(img, alt)
            }
            isInlineEmojiSource(src, classAttr) -> {
                img.replaceWith(TextNode(alt))
            }
            parseImgElement(img, baseUrl) == null -> {
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

internal fun isInlineEmojiSource(src: String, classAttr: String): Boolean =
    src.contains("/images/core/emoji/") ||
        src.contains("/wpcom-smileys/") ||
        src.contains("/twemoji/") ||
        classAttr.contains("wp-smiley") ||
        classAttr.contains("emoji") ||
        classAttr.contains("smiley")

internal fun formatLatexAltToHtml(rawLatex: String): String {
    var formatted = rawLatex.trim().removeSurrounding("$")
    for (cmd in LATEX_FONT_COMMANDS) {
        formatted = formatted.replace(cmd, "")
    }
    for ((command, symbol) in LATEX_SYMBOL_REPLACEMENTS) {
        formatted = formatted.replace(command, symbol)
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
): String {
    val clone = element.clone()
    clone.select(STRIPPED_INLINE_MEDIA_QUERY).remove()
    flattenNestedQuotesInDom(clone)
    flattenInlinePreAndTables(clone)
    flattenListsInDom(clone, rootIsListItem = isInsideList)
    unwrapParagraphsInDom(clone)
    unwrapContainerTagsInDom(clone)
    normalizeTextNodesInDom(clone)
    trimEdgeBrNodes(clone)
    return collapseExcessBrTags(clone.html().replace("<br>", BR_XHTML)).trim()
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

internal fun parseTableElement(table: Element): ArticleBlock.TableBlock? {
    val rows =
        table.select("tr").filter { tr ->
            tr.parents().firstOrNull { it.normalName() == TAG_TABLE } == table
        }
    if (rows.isEmpty()) return null

    val headerRows = mutableListOf<List<String>>()
    val dataRows = mutableListOf<List<String>>()

    rows.forEachIndexed { rowIndex, tr ->
        val cellElements =
            tr.children().filter { it.normalName() == "th" || it.normalName() == "td" }
        val isHeader = isHeaderTableRow(tr, cellElements, rowIndex, headerRows.isEmpty())
        val cells = expandRowCells(cellElements, isHeaderRow = isHeader)
        if (cells.any(::hasVisibleHtmlText)) {
            if (isHeader) {
                headerRows.add(cells)
            } else {
                dataRows.add(cells)
            }
        }
    }

    val mergedHeaders = mergeTableHeaderRows(headerRows)
    return if (mergedHeaders.isEmpty() && dataRows.isEmpty()) {
        null
    } else {
        ArticleBlock.TableBlock(headers = mergedHeaders, rows = dataRows)
    }
}

internal fun expandRowCells(cellElements: List<Element>, isHeaderRow: Boolean): List<String> {
    val expanded = mutableListOf<String>()
    for (cell in cellElements) {
        val rawHtml = normalizeDomElementInlineHtml(cell)
        val styledHtml =
            if (!isHeaderRow && cell.normalName() == "th" && hasVisibleHtmlText(rawHtml)) {
                ensureStrongHtml(rawHtml)
            } else {
                rawHtml
            }
        val colspan = cell.attr("colspan").trim().toIntOrNull()?.coerceIn(1, MAX_TABLE_COLSPAN) ?: 1
        expanded.add(styledHtml)
        repeat(colspan - 1) { expanded.add("") }
    }
    return expanded
}

internal fun ensureStrongHtml(html: String): String =
    if (html.startsWith("<strong>") && html.endsWith("</strong>")) {
        html
    } else {
        "<strong>$html</strong>"
    }

internal fun mergeTableHeaderRows(headerRows: List<List<String>>): List<String> {
    if (headerRows.isEmpty()) return emptyList()
    if (headerRows.size == 1) return headerRows[0]
    val maxCols = headerRows.maxOf { it.size }
    return (0 until maxCols).map { colIdx ->
        headerRows
            .mapNotNull { row -> row.getOrNull(colIdx)?.takeIf { it.isNotBlank() } }
            .distinct()
            .joinToString(BR_XHTML)
    }
}

internal fun isHeaderTableRow(
    tr: Element,
    cellElements: List<Element>,
    rowIndex: Int,
    noHeadersYet: Boolean,
): Boolean {
    if (cellElements.isEmpty()) return false
    val inThead = tr.parents().any { it.normalName() == "thead" }
    val allTh = cellElements.all { it.normalName() == "th" }
    return inThead || rowIndex == 0 && noHeadersYet && allTh
}

private const val MAX_NESTING_PASSES = 4
private const val LINE_NUMBER_TABLE_PRE_COUNT = 2
private const val LATEX_SCRIPT_BRACE_OFFSET = 2
private const val BLANK_LINE_NEWLINES = 2
private const val MAX_TABLE_COLSPAN = 8
private const val HTTPS_SCHEME_PREFIX_LEN = 8
private const val HTTP_SCHEME_PREFIX_LEN = 7
private const val MAILTO_SCHEME_PREFIX_LEN = 7
private const val PLAIN_BULLET_PREFIX = "- "

private const val TAG_IMG = "img"
private const val TAG_PRE = "pre"
private const val TAG_TABLE = "table"
private const val TAG_BLOCKQUOTE = "blockquote"
private const val TAG_P = "p"
private const val TAG_BR = "br"
private const val TAG_EM = "em"
private const val TAG_STRONG = "strong"
private const val TAG_TT = "tt"

private const val BR_HTML = "<br>"
private const val DOUBLE_BR_HTML = "<br><br>"
private const val BR_XHTML = "<br/>"
private const val DOUBLE_BR_XHTML = "<br/><br/>"

private const val STRIPPED_MEDIA_QUERY = "svg, iframe, script, style"
private const val STRIPPED_INLINE_MEDIA_QUERY = "img, figure, svg, iframe, script, style"
private const val INLINE_UNWRAP_CONTAINERS_QUERY =
    "div, section, article, header, footer, main, aside, details, summary, center, span"

private val INLINE_LIST_WRAPPER_TAGS = setOf("b", "strong", "i", "em")

private val LATEX_FONT_COMMANDS =
    listOf(
        """\mathrm""",
        """\mathbf""",
        """\mathit""",
        """\mathsf""",
        """\mathtt""",
        """\mathcal""",
        """\mathbb""",
        """\text""",
        """\operatorname""",
    )

private val LATEX_SYMBOL_REPLACEMENTS =
    listOf(
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

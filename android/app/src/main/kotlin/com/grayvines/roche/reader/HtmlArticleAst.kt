@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package com.grayvines.roche.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

internal sealed interface ArticleBlock {
    data class Heading(val level: Int, val html: String) : ArticleBlock

    data class CodeBlock(val code: String) : ArticleBlock

    data class Blockquote(val html: String, val attributionHtml: String? = null) : ArticleBlock

    data class ListBlock(
        val ordered: Boolean,
        val itemsHtml: List<String>,
        val startIndex: Int = 1,
    ) : ArticleBlock

    data object HorizontalRule : ArticleBlock

    data class Paragraph(val html: String) : ArticleBlock

    data class TableBlock(
        val headers: List<String>,
        val rows: List<List<String>>,
    ) : ArticleBlock

    data class ImageBlock(
        val url: String,
        val alt: String? = null,
        val captionHtml: String? = null,
        val width: Int? = null,
        val height: Int? = null,
    ) : ArticleBlock
}

internal fun extractInboxSnippet(rawSummary: String): String =
    if ('<' !in rawSummary && '&' !in rawSummary) {
        rawSummary.replace('\n', ' ').trim()
    } else {
        Jsoup.parseBodyFragment(rawSummary).text().trim()
    }

internal fun parseHtmlArticleBlocks(
    rawHtml: String,
    baseUrl: String? = null,
): List<ArticleBlock> {
    if (rawHtml.isBlank()) return emptyList()
    val body = parsePreprocessedHtmlBody(rawHtml, baseUrl)
    val blocks = mutableListOf<ArticleBlock>()
    walkDomContainer(body, baseUrl, blocks)
    return blocks
}

internal fun walkDomContainer(
    container: Element,
    baseUrl: String?,
    out: MutableList<ArticleBlock>,
) {
    val inlineNodes = mutableListOf<Node>()
    for (node in container.childNodes()) {
        if (node is Element && isBlockLevelTag(node.normalName())) {
            flushInlineNodes(
                nodes = inlineNodes,
                baseUrl = baseUrl,
                out = out,
                splitDoubleBrIntoParagraphs = true,
            )
            inlineNodes.clear()
            appendBlockElement(node, baseUrl, out)
        } else {
            inlineNodes.add(node)
        }
    }
    flushInlineNodes(
        nodes = inlineNodes,
        baseUrl = baseUrl,
        out = out,
        splitDoubleBrIntoParagraphs = true,
    )
}

private fun isBlockLevelTag(tag: String): Boolean = tag in BLOCK_LEVEL_TAGS

private fun appendBlockElement(
    element: Element,
    baseUrl: String?,
    out: MutableList<ArticleBlock>,
) {
    when (val tag = element.normalName()) {
        "pre" -> appendPreBlock(element, out)
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6" -> parseHeadingElement(element, tag)?.let { out.add(it) }
        TAG_BLOCKQUOTE -> appendQuoteBlocks(element, baseUrl, out)
        "ul",
        "ol" -> appendListBlocks(element, ordered = tag == "ol", baseUrl = baseUrl, out = out)
        "dl" -> appendDefinitionListBlocks(element, baseUrl, out)
        "dt" -> appendStandaloneTermBlock(element, out)
        "hr" -> out.add(ArticleBlock.HorizontalRule)
        TAG_FIGURE -> parseFigureElement(element, baseUrl, out)
        "p" ->
            flushInlineNodes(
                nodes = element.childNodes(),
                baseUrl = baseUrl,
                out = out,
                splitDoubleBrIntoParagraphs = false,
            )
        TAG_TABLE -> appendTableBlocks(element, out)
        else -> walkDomContainer(element, baseUrl, out)
    }
}

private fun appendPreBlock(element: Element, out: MutableList<ArticleBlock>) {
    extractPreCodeText(element)
        .takeIf { it.isNotBlank() }
        ?.let { out.add(ArticleBlock.CodeBlock(it)) }
}

private fun parseHeadingElement(element: Element, tag: String): ArticleBlock.Heading? {
    val cleanHtml = normalizeDomElementInlineHtml(element)
    if (!hasVisibleHtmlText(cleanHtml)) return null
    val level = tag.substring(1).toIntOrNull()?.coerceIn(MIN_HEADING, MAX_HEADING) ?: 2
    return ArticleBlock.Heading(level = level, html = cleanHtml)
}

internal fun appendQuoteBlocks(
    quoteElement: Element,
    baseUrl: String?,
    out: MutableList<ArticleBlock>,
    fallbackAttributionHtml: String? = null,
) {
    val clone = quoteElement.clone()
    val extractedAttribution = extractQuoteAttribution(clone)
    val attribution =
        (extractedAttribution ?: fallbackAttributionHtml)?.takeIf(::hasVisibleHtmlText)
    if (clone.selectFirst(EMBEDDED_QUOTE_BLOCKS_QUERY) == null) {
        val cleanBody = normalizeDomElementInlineHtml(clone)
        if (hasVisibleHtmlText(cleanBody)) {
            out.add(ArticleBlock.Blockquote(html = cleanBody, attributionHtml = attribution))
        }
    } else {
        appendComplexQuoteBlocks(clone, attribution, baseUrl, out)
    }
}

internal fun appendComplexQuoteBlocks(
    quoteClone: Element,
    attribution: String?,
    baseUrl: String?,
    out: MutableList<ArticleBlock>,
) {
    val innerBlocks = mutableListOf<ArticleBlock>()
    walkDomContainer(quoteClone, baseUrl, innerBlocks)
    val coalesced = coalesceQuoteInnerBlocks(innerBlocks)
    if (attribution != null) {
        attachAttributionToQuote(coalesced, attribution)
    }
    out.addAll(coalesced)
}

internal fun coalesceQuoteInnerBlocks(innerBlocks: List<ArticleBlock>): MutableList<ArticleBlock> {
    val result = mutableListOf<ArticleBlock>()
    val pendingTextSegments = mutableListOf<String>()
    for (block in innerBlocks) {
        val fragment = quoteTextFragment(block)
        if (fragment != null) {
            pendingTextSegments.add(fragment)
        } else {
            flushQuoteTextSegments(pendingTextSegments, result)
            result.add(block)
        }
    }
    flushQuoteTextSegments(pendingTextSegments, result)
    return result
}

internal fun flushQuoteTextSegments(
    segments: MutableList<String>,
    out: MutableList<ArticleBlock>,
) {
    if (segments.isNotEmpty()) {
        val joined = segments.joinToString(DOUBLE_BR_XHTML)
        segments.clear()
        if (hasVisibleHtmlText(joined)) {
            out.add(ArticleBlock.Blockquote(html = joined))
        }
    }
}

internal fun quoteTextFragment(block: ArticleBlock): String? =
    when (block) {
        is ArticleBlock.Paragraph -> block.html
        is ArticleBlock.Heading -> ensureStrongHtml(block.html)
        is ArticleBlock.Blockquote ->
            if (block.attributionHtml != null) {
                "${block.html}$BR_XHTML${block.attributionHtml}"
            } else {
                block.html
            }
        is ArticleBlock.ListBlock ->
            block.itemsHtml
                .mapIndexed { idx, item ->
                    val marker = if (block.ordered) "${block.startIndex + idx}." else "•"
                    "$marker $item"
                }
                .joinToString(BR_XHTML)
        else -> null
    }

internal fun attachAttributionToQuote(
    blocks: MutableList<ArticleBlock>,
    attributionHtml: String,
) {
    val lastQuoteIndex = blocks.indexOfLast { it is ArticleBlock.Blockquote }
    if (lastQuoteIndex >= 0) {
        val quote = blocks[lastQuoteIndex] as ArticleBlock.Blockquote
        blocks[lastQuoteIndex] = quote.copy(attributionHtml = attributionHtml)
    } else {
        blocks.add(ArticleBlock.Blockquote(html = attributionHtml))
    }
}

internal fun extractQuoteAttribution(quoteClone: Element): String? {
    val lastElement = quoteClone.children().lastOrNull() ?: return null
    val bodyProbe = quoteClone.clone()
    bodyProbe.children().lastOrNull()?.remove()
    val canExtract = !hasTrailingVisibleTextAfter(lastElement) && hasVisibleDomText(bodyProbe)
    return if (canExtract) {
        extractTrailingAttributionHtml(lastElement, bodyProbe.text())
    } else {
        null
    }
}

internal fun extractTrailingAttributionHtml(
    lastElement: Element,
    precedingBodyPlainText: String,
): String? {
    val tag = lastElement.normalName()
    val isCitationTag = tag == TAG_CITE || tag == "footer" || tag == TAG_FIGCAPTION
    val isCiteParagraph = tag == "p" && startsWithCiteElement(lastElement)
    val isDashParagraph =
        tag == "p" &&
            startsWithAttributionDashText(lastElement.text()) &&
            !startsWithAttributionDashText(precedingBodyPlainText)

    return if (isCitationTag || isCiteParagraph || isDashParagraph) {
        lastElement.remove()
        val attrClone = lastElement.clone()
        if (isCitationTag || isCiteParagraph) {
            attrClone.select(TAG_CITE).forEach {
                if (it != attrClone) {
                    it.unwrap()
                }
            }
        }
        normalizeDomElementInlineHtml(attrClone)
    } else {
        null
    }
}

internal fun hasTrailingVisibleTextAfter(child: Element): Boolean {
    var sibling = child.nextSibling()
    var hasText = false
    while (sibling != null && !hasText) {
        hasText = sibling is TextNode && sibling.wholeText.isNotBlank()
        sibling = sibling.nextSibling()
    }
    return hasText
}

internal fun startsWithCiteElement(element: Element): Boolean {
    val firstMeaningfulNode =
        element.childNodes().firstOrNull { node ->
            when (node) {
                is TextNode -> stripAttributionDashPrefix(node.wholeText).trim().isNotEmpty()
                is Element -> true
                else -> false
            }
        }
    return firstMeaningfulNode is Element && firstMeaningfulNode.normalName() == TAG_CITE
}

internal fun startsWithAttributionDashText(plainText: String): Boolean {
    val trimmed = plainText.replace('\u00A0', ' ').trimStart()
    return trimmed.startsWith("—") || trimmed.startsWith("–") || trimmed.startsWith("-- ")
}

internal fun stripAttributionDashPrefix(text: String): String =
    text.replace('\u00A0', ' ').trimStart().removePrefix("—").removePrefix("–").removePrefix("--")

internal fun appendListBlocks(
    listElement: Element,
    ordered: Boolean,
    baseUrl: String?,
    out: MutableList<ArticleBlock>,
) {
    val liElements = selectOwnListItems(listElement)
    if (liElements.isEmpty()) {
        walkDomContainer(listElement, baseUrl, out)
        return
    }
    var nextIndex = listElement.attr("start").toIntOrNull()?.takeIf { it > 0 } ?: 1
    val currentItems = mutableListOf<String>()
    for (li in liElements) {
        val itemHtml = formatListItemElementHtml(li, listElement)
        if (hasVisibleHtmlText(itemHtml)) {
            currentItems.add(itemHtml)
        }
        val liImages = li.select(TAG_IMG).mapNotNull { parseImgElement(it, baseUrl) }
        if (liImages.isNotEmpty()) {
            nextIndex = flushListItems(ordered, nextIndex, currentItems, out)
            out.addAll(liImages)
        }
    }
    flushListItems(ordered, nextIndex, currentItems, out)
}

internal fun flushListItems(
    ordered: Boolean,
    startIndex: Int,
    currentItems: MutableList<String>,
    out: MutableList<ArticleBlock>,
): Int {
    if (currentItems.isEmpty()) return startIndex
    val nextIndex = startIndex + currentItems.size
    out.add(
        ArticleBlock.ListBlock(
            ordered = ordered,
            itemsHtml = currentItems.toList(),
            startIndex = startIndex,
        )
    )
    currentItems.clear()
    return nextIndex
}

internal fun appendDefinitionListBlocks(
    dlElement: Element,
    baseUrl: String?,
    out: MutableList<ArticleBlock>,
) {
    val dlClone = dlElement.clone()
    for (wrapper in dlClone.children().filter { it.normalName() == TAG_DIV }) {
        wrapper.unwrap()
    }
    var pendingTermHtml: String? = null
    for (child in dlClone.children()) {
        when (child.normalName()) {
            "dt" -> {
                if (pendingTermHtml != null) {
                    out.add(ArticleBlock.Paragraph(ensureStrongHtml(pendingTermHtml)))
                }
                pendingTermHtml = normalizeDomElementInlineHtml(child).takeIf(::hasVisibleHtmlText)
            }
            "dd" -> {
                appendDefinitionDescBlocks(child, pendingTermHtml, baseUrl, out)
                pendingTermHtml = null
            }
            else -> {
                walkDomContainer(child, baseUrl, out)
            }
        }
    }
    if (pendingTermHtml != null) {
        out.add(ArticleBlock.Paragraph(ensureStrongHtml(pendingTermHtml)))
    }
}

private fun appendDefinitionDescBlocks(
    ddElement: Element,
    pendingTermHtml: String?,
    baseUrl: String?,
    out: MutableList<ArticleBlock>,
) {
    val ddBlocks = mutableListOf<ArticleBlock>()
    walkDomContainer(ddElement, baseUrl, ddBlocks)
    if (pendingTermHtml != null) {
        val termHtml = ensureStrongHtml(pendingTermHtml)
        val firstBlock = ddBlocks.firstOrNull()
        if (firstBlock is ArticleBlock.Paragraph) {
            ddBlocks[0] = ArticleBlock.Paragraph("$termHtml$BR_XHTML${firstBlock.html}")
        } else {
            ddBlocks.add(0, ArticleBlock.Paragraph(termHtml))
        }
    }
    out.addAll(ddBlocks)
}

private fun appendStandaloneTermBlock(element: Element, out: MutableList<ArticleBlock>) {
    normalizeDomElementInlineHtml(element).takeIf(::hasVisibleHtmlText)?.let {
        out.add(ArticleBlock.Paragraph(ensureStrongHtml(it)))
    }
}

private fun appendTableBlocks(element: Element, out: MutableList<ArticleBlock>) {
    val tableBlock = parseTableElement(element) ?: return
    val caption =
        element
            .children()
            .firstOrNull { it.normalName() == "caption" }
            ?.let { normalizeDomElementInlineHtml(it) }
            ?.takeIf(::hasVisibleHtmlText)
    if (caption != null) {
        out.add(ArticleBlock.Paragraph(ensureStrongHtml(caption)))
    }
    out.add(tableBlock)
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

internal fun parseFigureElement(
    figure: Element,
    baseUrl: String?,
    out: MutableList<ArticleBlock>,
) {
    val figcaption = figure.selectFirst(TAG_FIGCAPTION)
    val (explicitCaption, trailingCaptionBlocks) = splitFigcaptionContent(figcaption, baseUrl)
    val withoutCaption = figure.clone()
    withoutCaption.select(TAG_FIGCAPTION).remove()

    val images = withoutCaption.select(TAG_IMG).mapNotNull { parseImgElement(it, baseUrl) }
    val hasCompositeBlocks = withoutCaption.selectFirst(COMPOSITE_FIGURE_QUERY) != null
    if (images.isNotEmpty() && !hasCompositeBlocks) {
        val fallbackCaption =
            if (explicitCaption == null) {
                normalizeDomElementInlineHtml(withoutCaption).takeIf(::hasVisibleHtmlText)
            } else {
                null
            }
        val effectiveCaption = explicitCaption ?: fallbackCaption
        images.forEachIndexed { index, img ->
            val attachedCaption = if (index == images.lastIndex) effectiveCaption else null
            out.add(img.copy(captionHtml = attachedCaption))
        }
    } else {
        appendCompositeFigureBlocks(withoutCaption, explicitCaption, baseUrl, out)
    }
    out.addAll(trailingCaptionBlocks)
}

internal fun splitFigcaptionContent(
    figcaption: Element?,
    baseUrl: String?,
): Pair<String?, List<ArticleBlock>> {
    if (figcaption == null) return null to emptyList()
    if (figcaption.selectFirst(COMPOSITE_FIGURE_QUERY) == null) {
        val caption = normalizeDomElementInlineHtml(figcaption).takeIf(::hasVisibleHtmlText)
        return caption to emptyList()
    }
    val captionBox = Element(TAG_DIV)
    val remainderBox = Element(TAG_DIV)
    var inRemainder = false
    for (node in figcaption.childNodes()) {
        val hasCaptionAlready = hasVisibleDomText(captionBox)
        if (!inRemainder && shouldBreakFigcaption(node, hasCaptionAlready)) {
            inRemainder = true
        }
        if (inRemainder) {
            remainderBox.appendChild(node.clone())
        } else {
            captionBox.appendChild(node.clone())
        }
    }
    val caption = normalizeDomElementInlineHtml(captionBox).takeIf(::hasVisibleHtmlText)
    val trailingBlocks = mutableListOf<ArticleBlock>()
    walkDomContainer(remainderBox, baseUrl, trailingBlocks)
    return caption to trailingBlocks
}

internal fun shouldBreakFigcaption(node: Node, hasCaptionAlready: Boolean): Boolean {
    if (node !is Element) return false
    val tag = node.normalName()
    val hasEmbeddedBlock = node.selectFirst(COMPOSITE_FIGURE_QUERY) != null
    return hasEmbeddedBlock ||
        tag in COMPOSITE_CAPTION_BREAK_TAGS ||
        tag == "p" && hasCaptionAlready
}

internal fun appendCompositeFigureBlocks(
    withoutCaption: Element,
    explicitCaption: String?,
    baseUrl: String?,
    out: MutableList<ArticleBlock>,
) {
    val figureBlocks = mutableListOf<ArticleBlock>()
    walkDomContainer(withoutCaption, baseUrl, figureBlocks)
    if (figureBlocks.isNotEmpty()) {
        if (explicitCaption != null) {
            attachFigureCaption(figureBlocks, explicitCaption)
        }
        out.addAll(figureBlocks)
    } else if (explicitCaption != null) {
        out.add(italicCaptionParagraph(explicitCaption))
    }
}

private fun italicCaptionParagraph(captionHtml: String): ArticleBlock.Paragraph =
    ArticleBlock.Paragraph("<em>$captionHtml</em>")

internal fun attachFigureCaption(blocks: MutableList<ArticleBlock>, captionHtml: String) {
    val lastIdx = blocks.lastIndex
    when (val last = blocks[lastIdx]) {
        is ArticleBlock.ImageBlock -> {
            if (last.captionHtml == null) {
                blocks[lastIdx] = last.copy(captionHtml = captionHtml)
            } else {
                blocks.add(italicCaptionParagraph(captionHtml))
            }
        }
        is ArticleBlock.Blockquote -> {
            if (last.attributionHtml == null) {
                blocks[lastIdx] = last.copy(attributionHtml = captionHtml)
            } else {
                blocks.add(italicCaptionParagraph(captionHtml))
            }
        }
        else -> {
            blocks.add(italicCaptionParagraph(captionHtml))
        }
    }
}

internal fun flushInlineNodes(
    nodes: List<Node>,
    baseUrl: String?,
    out: MutableList<ArticleBlock>,
    splitDoubleBrIntoParagraphs: Boolean = false,
) {
    if (nodes.isEmpty()) return
    val scratchDoc = Jsoup.parseBodyFragment("")
    scratchDoc.outputSettings().prettyPrint(false)
    val buffer = scratchDoc.body()

    for (node in nodes) {
        if (node is Element && node.normalName() == TAG_IMG) {
            flushInlineScratchBuffer(buffer, out, splitDoubleBrIntoParagraphs)
            parseImgElement(node, baseUrl)?.let { out.add(it) }
        } else if (node is Element && node.selectFirst(TAG_IMG) != null) {
            appendElementWithImages(
                element = node,
                buffer = buffer,
                baseUrl = baseUrl,
                out = out,
                splitDoubleBrIntoParagraphs = splitDoubleBrIntoParagraphs,
            )
        } else {
            buffer.appendChild(node.clone())
        }
    }
    flushInlineScratchBuffer(buffer, out, splitDoubleBrIntoParagraphs)
}

internal fun appendElementWithImages(
    element: Element,
    buffer: Element,
    baseUrl: String?,
    out: MutableList<ArticleBlock>,
    splitDoubleBrIntoParagraphs: Boolean,
) {
    val images = element.select(TAG_IMG).mapNotNull { parseImgElement(it, baseUrl) }
    if (hasVisibleDomText(element)) {
        val textClone = element.clone()
        textClone.select(TAG_IMG).remove()
        buffer.appendChild(textClone)
    }
    flushInlineScratchBuffer(buffer, out, splitDoubleBrIntoParagraphs)
    out.addAll(images)
}

internal fun flushInlineScratchBuffer(
    buffer: Element,
    out: MutableList<ArticleBlock>,
    splitDoubleBrIntoParagraphs: Boolean,
) {
    if (buffer.childNodeSize() == 0) return
    val normalized = normalizeDomElementInlineHtml(buffer)
    buffer.empty()
    if (!hasVisibleHtmlText(normalized)) return

    if (splitDoubleBrIntoParagraphs && DOUBLE_BR_XHTML in normalized) {
        for (segment in normalized.split(DOUBLE_BR_XHTML)) {
            val trimmed = segment.trim()
            if (hasVisibleHtmlText(trimmed)) {
                out.add(ArticleBlock.Paragraph(trimmed))
            }
        }
    } else {
        out.add(ArticleBlock.Paragraph(normalized))
    }
}

internal fun parseImgElement(img: Element, baseUrl: String?): ArticleBlock.ImageBlock? =
    validateArticleImgElement(img, baseUrl)?.let { valid ->
        ArticleBlock.ImageBlock(
            url = valid.url,
            alt = valid.alt,
            captionHtml = null,
            width = valid.width,
            height = valid.height,
        )
    }

internal fun computeTableColumnWidths(block: ArticleBlock.TableBlock): List<Int> {
    val columnCount =
        maxOf(
            block.headers.size,
            block.rows.maxOfOrNull { it.size } ?: 0,
        )
    if (columnCount == 0) return emptyList()
    return (0 until columnCount).map { colIndex ->
        val headerLen = plainCellLength(block.headers.getOrNull(colIndex))
        val maxRowLen = block.rows.maxOfOrNull { plainCellLength(it.getOrNull(colIndex)) } ?: 0
        val maxChars = maxOf(headerLen, maxRowLen)
        (maxChars * TABLE_CHAR_WIDTH_DP + TABLE_CELL_PADDING_DP).coerceIn(
            MIN_TABLE_COL_WIDTH_DP,
            MAX_TABLE_COL_WIDTH_DP,
        )
    }
}

internal fun plainCellLength(cellHtml: String?): Int {
    if (cellHtml.isNullOrBlank()) return 0
    val doc = Jsoup.parseBodyFragment(cellHtml)
    doc.select("br").forEach { it.replaceWith(TextNode("\n")) }
    return doc.body().wholeText().lines().maxOfOrNull { it.trim().length } ?: 0
}

internal fun extractPlainTextHeadings(blocks: List<ArticleBlock>): List<String> =
    blocks
        .filterIsInstance<ArticleBlock.Heading>()
        .map { heading -> Jsoup.parseBodyFragment(heading.html).text().trim() }
        .filter { it.isNotEmpty() }
        .distinct()

internal fun mapBlockHeadingIndices(blocks: List<ArticleBlock>): Map<Int, Int> {
    val seenTexts = HashSet<String>()
    val indices = HashMap<Int, Int>()
    var nextHeadingIndex = 0
    blocks.forEachIndexed { blockIndex, block ->
        if (block is ArticleBlock.Heading) {
            val plainText = Jsoup.parseBodyFragment(block.html).text().trim()
            if (plainText.isNotEmpty() && seenTexts.add(plainText)) {
                indices[blockIndex] = nextHeadingIndex
                nextHeadingIndex++
            }
        }
    }
    return indices
}

private const val MIN_HEADING = 1
private const val MAX_HEADING = 6
private const val MAX_TABLE_COLSPAN = 8
private const val TABLE_CHAR_WIDTH_DP = 7
private const val TABLE_CELL_PADDING_DP = 24
private const val MIN_TABLE_COL_WIDTH_DP = 72
private const val MAX_TABLE_COL_WIDTH_DP = 240
private const val TAG_CITE = "cite"
private const val TAG_BLOCKQUOTE = "blockquote"
private const val TAG_FIGURE = "figure"
private const val TAG_FIGCAPTION = "figcaption"
private const val TAG_IMG = "img"
private const val TAG_DIV = "div"
private const val TAG_TABLE = "table"
private const val EMBEDDED_QUOTE_BLOCKS_QUERY = "pre, img, figure, table"
private const val COMPOSITE_FIGURE_QUERY = "blockquote, pre, table"
private val COMPOSITE_CAPTION_BREAK_TAGS =
    setOf(TAG_BLOCKQUOTE, "pre", TAG_TABLE, "ul", "ol", "hr", TAG_FIGURE, "div")

private val BLOCK_LEVEL_TAGS =
    setOf(
        "pre",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        TAG_BLOCKQUOTE,
        "ul",
        "ol",
        "hr",
        TAG_FIGURE,
        "p",
        TAG_TABLE,
        "div",
        "section",
        "article",
        "header",
        "footer",
        "main",
        "aside",
        "center",
        "nav",
        "dl",
        "dd",
        "dt",
    )

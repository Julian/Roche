@file:Suppress("MatchingDeclarationName")

package com.grayvines.roche.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.ui.theme.LocalHeadlineFont
import com.grayvines.roche.ui.theme.LocalReaderFontScale
import com.grayvines.roche.ui.theme.LocalRocheColors
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
        "blockquote" -> appendQuoteBlocks(element, baseUrl, out)
        "ul",
        "ol" -> appendListBlocks(element, ordered = tag == "ol", baseUrl = baseUrl, out = out)
        "dl" -> appendDefinitionListBlocks(element, baseUrl, out)
        "dt" -> appendStandaloneTermBlock(element, out)
        "hr" -> out.add(ArticleBlock.HorizontalRule)
        "figure" -> parseFigureElement(element, baseUrl, out)
        "p" ->
            flushInlineNodes(
                nodes = element.childNodes(),
                baseUrl = baseUrl,
                out = out,
                splitDoubleBrIntoParagraphs = false,
            )
        "table" -> appendTableBlocks(element, out)
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
    val isCitationTag = tag == TAG_CITE || tag == "footer" || tag == "figcaption"
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
        val liImages = li.select("img").mapNotNull { parseImgElement(it, baseUrl) }
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
    for (wrapper in dlClone.children().filter { it.normalName() == "div" }) {
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

internal fun hasVisibleDomText(element: Element): Boolean =
    element.text().replace('\u00A0', ' ').isNotBlank()

internal fun hasVisibleHtmlText(htmlFragment: String): Boolean =
    htmlFragment.isNotBlank() && hasVisibleDomText(Jsoup.parseBodyFragment(htmlFragment).body())

internal fun extractPreCodeText(preElement: Element): String {
    val clone = preElement.clone()
    clone.select("br").forEach { it.replaceWith(TextNode("\n")) }
    return clone.wholeText().trim('\r', '\n')
}

@Composable
internal fun StructuredHtmlArticleContent(
    blocks: List<ArticleBlock>,
    baseUrl: String? = null,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val colors = LocalRocheColors.current
    val linkStyles =
        remember(colors.accentBlue) {
            TextLinkStyles(
                style =
                    SpanStyle(
                        color = colors.accentBlue,
                        textDecoration = TextDecoration.Underline,
                    )
            )
        }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        blocks.forEach { block ->
            RenderArticleBlock(
                block = block,
                linkStyles = linkStyles,
                baseUrl = baseUrl,
                onOpenUrl = onOpenUrl,
            )
        }
    }
}

@Composable
private fun RenderArticleBlock(
    block: ArticleBlock,
    linkStyles: TextLinkStyles,
    baseUrl: String?,
    onOpenUrl: ((String) -> Unit)?,
) {
    when (block) {
        is ArticleBlock.Heading ->
            RenderHeadingBlock(
                block = block,
                linkStyles = linkStyles,
                baseUrl = baseUrl,
                onOpenUrl = onOpenUrl,
            )
        is ArticleBlock.CodeBlock -> RenderCodeBlock(code = block.code)
        is ArticleBlock.Blockquote ->
            RenderBlockquoteBlock(
                block = block,
                linkStyles = linkStyles,
                baseUrl = baseUrl,
                onOpenUrl = onOpenUrl,
            )
        is ArticleBlock.ListBlock ->
            RenderListBlock(
                block = block,
                linkStyles = linkStyles,
                baseUrl = baseUrl,
                onOpenUrl = onOpenUrl,
            )
        ArticleBlock.HorizontalRule ->
            HorizontalDivider(
                color = LocalRocheColors.current.border,
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        is ArticleBlock.Paragraph ->
            RenderParagraphBlock(
                html = block.html,
                linkStyles = linkStyles,
                baseUrl = baseUrl,
                onOpenUrl = onOpenUrl,
            )
        is ArticleBlock.TableBlock ->
            RenderTableBlock(
                block = block,
                linkStyles = linkStyles,
                baseUrl = baseUrl,
                onOpenUrl = onOpenUrl,
            )
        is ArticleBlock.ImageBlock ->
            RenderImageBlock(
                block = block,
                linkStyles = linkStyles,
                baseUrl = baseUrl,
                onOpenUrl = onOpenUrl,
            )
    }
}

@Composable
internal fun RenderHeadingBlock(
    block: ArticleBlock.Heading,
    linkStyles: TextLinkStyles,
    baseUrl: String? = null,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val colors = LocalRocheColors.current
    val scale = LocalReaderFontScale.current
    val headlineFamily =
        if (LocalHeadlineFont.current == HeadlineFont.SERIF) {
            FontFamily.Serif
        } else {
            FontFamily.SansSerif
        }
    val annotated =
        rememberRichHtmlText(
            html = block.html,
            linkStyles = linkStyles,
            codeBackground = colors.surfaceElevated,
            baseUrl = baseUrl,
            onOpenUrl = onOpenUrl,
        )
    val baseFontSize =
        when (block.level) {
            1 -> 20.5f
            2 -> 18.5f
            3 -> 17f
            else -> 16f
        }
    val baseLineHeight =
        when (block.level) {
            1 -> 27f
            2 -> 25f
            else -> 23f
        }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = annotated,
        color = colors.textPrimary,
        fontFamily = headlineFamily,
        fontSize = (baseFontSize * scale).sp,
        lineHeight = (baseLineHeight * scale).sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
internal fun RenderCodeBlock(code: String) {
    val colors = LocalRocheColors.current
    val scale = LocalReaderFontScale.current
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = code,
            color = colors.textPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = (12.5f * scale).sp,
            lineHeight = (18.5f * scale).sp,
        )
    }
}

@Composable
internal fun RenderBlockquoteBlock(
    block: ArticleBlock.Blockquote,
    linkStyles: TextLinkStyles,
    baseUrl: String? = null,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val colors = LocalRocheColors.current
    val scale = LocalReaderFontScale.current
    val annotated =
        rememberRichHtmlText(
            html = block.html,
            linkStyles = linkStyles,
            codeBackground = colors.surfaceElevated,
            baseUrl = baseUrl,
            onOpenUrl = onOpenUrl,
        )
    val attributionAnnotated =
        block.attributionHtml
            ?.takeIf { it.isNotBlank() }
            ?.let {
                rememberRichHtmlText(
                    html = it,
                    linkStyles = linkStyles,
                    codeBackground = colors.surfaceElevated,
                    baseUrl = baseUrl,
                    onOpenUrl = onOpenUrl,
                )
            }
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            modifier =
                Modifier.width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.accentVerdigris)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = annotated,
                color = colors.textSecondary,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = (15.5f * scale).sp,
                lineHeight = (24.5f * scale).sp,
            )
            if (attributionAnnotated != null) {
                Text(
                    text = attributionAnnotated,
                    color = colors.textMuted,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Normal,
                    fontSize = (13.5f * scale).sp,
                    lineHeight = (20f * scale).sp,
                )
            }
        }
    }
}

@Composable
internal fun RenderListBlock(
    block: ArticleBlock.ListBlock,
    linkStyles: TextLinkStyles,
    baseUrl: String? = null,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val colors = LocalRocheColors.current
    val scale = LocalReaderFontScale.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        block.itemsHtml.forEachIndexed { index, itemHtml ->
            val marker = if (block.ordered) "${block.startIndex + index}." else "•"
            val annotated =
                rememberRichHtmlText(
                    html = itemHtml,
                    linkStyles = linkStyles,
                    codeBackground = colors.surfaceElevated,
                    baseUrl = baseUrl,
                    onOpenUrl = onOpenUrl,
                )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = marker,
                    color = colors.accentVerdigris,
                    fontFamily = FontFamily.Monospace,
                    fontSize = (13.5f * scale).sp,
                    lineHeight = (25f * scale).sp,
                    modifier = Modifier.width(24.dp),
                )
                Text(
                    text = annotated,
                    color = colors.textPrimary,
                    fontFamily = FontFamily.Serif,
                    fontSize = (16f * scale).sp,
                    lineHeight = (25f * scale).sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun RenderParagraphBlock(
    html: String,
    linkStyles: TextLinkStyles,
    baseUrl: String? = null,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val colors = LocalRocheColors.current
    val scale = LocalReaderFontScale.current
    val annotated =
        rememberRichHtmlText(
            html = html,
            linkStyles = linkStyles,
            codeBackground = colors.surfaceElevated,
            baseUrl = baseUrl,
            onOpenUrl = onOpenUrl,
        )
    Text(
        text = annotated,
        color = colors.textPrimary,
        fontFamily = FontFamily.Serif,
        fontSize = (16f * scale).sp,
        lineHeight = (26f * scale).sp,
    )
}

@Composable
internal fun RenderTableBlock(
    block: ArticleBlock.TableBlock,
    linkStyles: TextLinkStyles,
    baseUrl: String? = null,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val colors = LocalRocheColors.current
    val scale = LocalReaderFontScale.current
    val colWidths = remember(block) { computeTableColumnWidths(block) }
    if (colWidths.isEmpty()) return

    Box(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                .horizontalScroll(rememberScrollState())
    ) {
        Column {
            if (block.headers.isNotEmpty()) {
                RenderTableRow(
                    cells = block.headers,
                    colWidths = colWidths,
                    isHeader = true,
                    scale = scale,
                    linkStyles = linkStyles,
                    baseUrl = baseUrl,
                    onOpenUrl = onOpenUrl,
                )
                if (block.rows.isNotEmpty()) {
                    HorizontalDivider(color = colors.border, thickness = 1.dp)
                }
            }
            block.rows.forEachIndexed { index, row ->
                RenderTableRow(
                    cells = row,
                    colWidths = colWidths,
                    isHeader = false,
                    scale = scale,
                    linkStyles = linkStyles,
                    baseUrl = baseUrl,
                    onOpenUrl = onOpenUrl,
                )
                if (index < block.rows.lastIndex) {
                    HorizontalDivider(color = colors.border, thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun RenderTableRow(
    cells: List<String>,
    colWidths: List<Int>,
    isHeader: Boolean,
    scale: Float,
    linkStyles: TextLinkStyles,
    baseUrl: String?,
    onOpenUrl: ((String) -> Unit)?,
) {
    val colors = LocalRocheColors.current
    val style = resolveTableRowStyle(isHeader, colors)
    Row(
        modifier =
            Modifier.background(style.background)
                .padding(horizontal = 10.dp, vertical = style.verticalPad),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        colWidths.forEachIndexed { colIndex, widthDp ->
            val cellHtml = cells.getOrNull(colIndex).orEmpty()
            val annotated =
                rememberRichHtmlText(
                    html = cellHtml,
                    linkStyles = linkStyles,
                    codeBackground = colors.surfaceElevated,
                    baseUrl = baseUrl,
                    onOpenUrl = onOpenUrl,
                )
            Text(
                text = annotated,
                color = style.textColor,
                fontFamily = style.fontFamily,
                fontWeight = style.fontWeight,
                fontSize = (13.5f * scale).sp,
                lineHeight = (20f * scale).sp,
                modifier = Modifier.width(widthDp.dp),
            )
        }
    }
}

private data class TableRowStyle(
    val background: androidx.compose.ui.graphics.Color,
    val verticalPad: androidx.compose.ui.unit.Dp,
    val textColor: androidx.compose.ui.graphics.Color,
    val fontFamily: FontFamily,
    val fontWeight: FontWeight,
)

private fun resolveTableRowStyle(
    isHeader: Boolean,
    colors: com.grayvines.roche.ui.theme.RocheColors,
): TableRowStyle =
    if (isHeader) {
        TableRowStyle(
            background = colors.surfaceElevated,
            verticalPad = 8.dp,
            textColor = colors.textPrimary,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
        )
    } else {
        TableRowStyle(
            background = colors.surface,
            verticalPad = 7.dp,
            textColor = colors.textSecondary,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Normal,
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

private const val MIN_HEADING = 1
private const val MAX_HEADING = 6
private const val TABLE_CHAR_WIDTH_DP = 7
private const val TABLE_CELL_PADDING_DP = 24
private const val MIN_TABLE_COL_WIDTH_DP = 72
private const val MAX_TABLE_COL_WIDTH_DP = 240
private const val TAG_CITE = "cite"
private const val BR_XHTML = "<br/>"
private const val DOUBLE_BR_XHTML = "<br/><br/>"
private const val EMBEDDED_QUOTE_BLOCKS_QUERY = "pre, img, figure, table"

private val BLOCK_LEVEL_TAGS =
    setOf(
        "pre",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        "blockquote",
        "ul",
        "ol",
        "hr",
        "figure",
        "p",
        "table",
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

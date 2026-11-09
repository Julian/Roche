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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.ui.theme.LocalHeadlineFont
import com.grayvines.roche.ui.theme.LocalReaderFontScale
import com.grayvines.roche.ui.theme.LocalRocheColors

internal sealed interface ArticleBlock {
    data class Heading(val level: Int, val html: String) : ArticleBlock

    data class CodeBlock(val code: String) : ArticleBlock

    data class Blockquote(val html: String) : ArticleBlock

    data class ListBlock(val ordered: Boolean, val itemsHtml: List<String>) : ArticleBlock

    data object HorizontalRule : ArticleBlock

    data class Paragraph(val html: String) : ArticleBlock

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
        AnnotatedString.fromHtml(rawSummary).text.replace('\n', ' ').trim()
    }

internal fun parseHtmlArticleBlocks(
    rawHtml: String,
    baseUrl: String? = null,
): List<ArticleBlock> {
    val trimmed = rawHtml.trim()
    if (trimmed.isEmpty()) return emptyList()

    val blocks = mutableListOf<ArticleBlock>()
    var cursor = 0
    for (match in TOP_BLOCK_REGEX.findAll(trimmed)) {
        if (match.range.first > cursor) {
            appendGapParagraphs(trimmed.substring(cursor, match.range.first), baseUrl, blocks)
        }
        appendMatchedBlocks(match, baseUrl, blocks)
        cursor = match.range.last + 1
    }
    if (cursor < trimmed.length) {
        appendGapParagraphs(trimmed.substring(cursor), baseUrl, blocks)
    }
    if (blocks.isEmpty()) {
        parseParagraphWithImages(trimmed, baseUrl, blocks)
    }
    return blocks
}

internal fun appendMatchedBlocks(
    match: MatchResult,
    baseUrl: String?,
    out: MutableList<ArticleBlock>,
) {
    val preInner = match.groups[GROUP_PRE_INNER]?.value
    if (preInner != null) {
        val code = decodeHtmlCodeBlock(preInner)
        if (code.isNotBlank()) {
            out.add(ArticleBlock.CodeBlock(code))
        }
        return
    }
    if (match.groups[GROUP_HR]?.value != null) {
        out.add(ArticleBlock.HorizontalRule)
        return
    }
    val figureInner = match.groups[GROUP_FIGURE_INNER]?.value
    if (figureInner != null) {
        parseFigureBlocks(figureInner, baseUrl, out)
        return
    }
    val paraInner = match.groups[GROUP_PARA_INNER]?.value
    if (paraInner != null) {
        parseParagraphWithImages(paraInner, baseUrl, out)
        return
    }
    (parseHeadingOrQuoteBlock(match) ?: parseListBlock(match))?.let { out.add(it) }
}

internal fun parseHeadingOrQuoteBlock(match: MatchResult): ArticleBlock? {
    val headingLevel = match.groups[GROUP_HEADING_LEVEL]?.value
    val headingInner = match.groups[GROUP_HEADING_INNER]?.value
    if (headingLevel != null && headingInner != null) {
        val clean = stripMediaTags(headingInner).trim()
        return clean
            .takeIf { hasVisibleHtmlText(it) }
            ?.let {
                ArticleBlock.Heading(
                    level = headingLevel.toIntOrNull()?.coerceIn(MIN_HEADING, MAX_HEADING) ?: 2,
                    html = it,
                )
            }
    }
    val quoteInner = match.groups[GROUP_QUOTE_INNER]?.value ?: return null
    val clean = normalizeInlineBlockHtml(quoteInner)
    return clean.takeIf { hasVisibleHtmlText(it) }?.let { ArticleBlock.Blockquote(it) }
}

internal fun parseListBlock(match: MatchResult): ArticleBlock? {
    val listTag = match.groups[GROUP_LIST_TAG]?.value
    val listInner = match.groups[GROUP_LIST_INNER]?.value
    if (listTag != null && listInner != null) {
        val items =
            LI_ITEM_REGEX.findAll(listInner)
                .map { normalizeInlineBlockHtml(it.groupValues[1]) }
                .filter { hasVisibleHtmlText(it) }
                .toList()
        return items
            .takeIf { it.isNotEmpty() }
            ?.let {
                ArticleBlock.ListBlock(
                    ordered = listTag.equals("ol", ignoreCase = true),
                    itemsHtml = it,
                )
            }
    }
    return null
}

internal fun normalizeInlineBlockHtml(htmlFragment: String): String =
    stripMediaTags(htmlFragment)
        .replace(ADJACENT_P_TAGS_REGEX, "<br/><br/>")
        .replace(SINGLE_P_TAG_REGEX, "")
        .trim()

internal fun appendGapParagraphs(
    gapHtml: String,
    baseUrl: String?,
    out: MutableList<ArticleBlock>,
) {
    val cleanedGap = stripNonImageMediaTags(gapHtml).replace(CONTAINER_TAG_REGEX, "\n\n")
    for (segment in cleanedGap.split(PARAGRAPH_BREAK_REGEX)) {
        val trimmed = segment.trim()
        if (trimmed.isNotEmpty()) {
            parseParagraphWithImages(trimmed, baseUrl, out)
        }
    }
}

internal fun hasVisibleHtmlText(htmlFragment: String): Boolean {
    if (htmlFragment.isBlank()) return false
    val stripped = htmlFragment.replace(ANY_HTML_TAG_REGEX, "").replace("&nbsp;", " ").trim()
    return stripped.isNotEmpty()
}

internal fun stripMediaTags(htmlFragment: String): String =
    htmlFragment.replace(MEDIA_TAG_REGEX, "")

internal fun decodeHtmlCodeBlock(rawPreInner: String): String {
    val withNewlines = rawPreInner.replace(BR_TAG_REGEX, "\n")
    val withoutTags = withNewlines.replace(ANY_HTML_TAG_REGEX, "")
    return decodeBasicHtmlEntities(withoutTags).trim('\r', '\n')
}

internal fun decodeBasicHtmlEntities(text: String): String {
    if ('&' !in text) return text
    return ENTITY_REGEX.replace(text) { match ->
        when (val entity = match.groupValues[1]) {
            "lt" -> "<"
            "gt" -> ">"
            "amp" -> "&"
            "quot" -> "\""
            "apos",
            "#39",
            "#x27",
            "#X27" -> "'"
            "nbsp" -> " "
            "mdash" -> "—"
            "ndash" -> "–"
            else -> decodeNumericEntity(entity) ?: match.value
        }
    }
}

private fun decodeNumericEntity(entity: String): String? =
    when {
        entity.startsWith("#x", ignoreCase = true) ->
            entity
                .substring(HEX_PREFIX_LEN)
                .toIntOrNull(HEX_RADIX)
                ?.takeIf { it in MIN_CODEPOINT..MAX_CODEPOINT }
                ?.let { String(Character.toChars(it)) }
        entity.startsWith("#") ->
            entity
                .substring(1)
                .toIntOrNull()
                ?.takeIf { it in MIN_CODEPOINT..MAX_CODEPOINT }
                ?.let { String(Character.toChars(it)) }
        else -> null
    }

@Composable
internal fun StructuredHtmlArticleContent(blocks: List<ArticleBlock>) {
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
            RenderArticleBlock(block = block, linkStyles = linkStyles)
        }
    }
}

@Composable
private fun RenderArticleBlock(block: ArticleBlock, linkStyles: TextLinkStyles) {
    when (block) {
        is ArticleBlock.Heading -> RenderHeadingBlock(block = block, linkStyles = linkStyles)
        is ArticleBlock.CodeBlock -> RenderCodeBlock(code = block.code)
        is ArticleBlock.Blockquote -> RenderBlockquoteBlock(block = block, linkStyles = linkStyles)
        is ArticleBlock.ListBlock -> RenderListBlock(block = block, linkStyles = linkStyles)
        ArticleBlock.HorizontalRule ->
            HorizontalDivider(
                color = LocalRocheColors.current.border,
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        is ArticleBlock.Paragraph ->
            RenderParagraphBlock(html = block.html, linkStyles = linkStyles)
        is ArticleBlock.ImageBlock -> RenderImageBlock(block = block, linkStyles = linkStyles)
    }
}

@Composable
internal fun RenderHeadingBlock(block: ArticleBlock.Heading, linkStyles: TextLinkStyles) {
    val colors = LocalRocheColors.current
    val scale = LocalReaderFontScale.current
    val headlineFamily =
        if (LocalHeadlineFont.current == HeadlineFont.SERIF) {
            FontFamily.Serif
        } else {
            FontFamily.SansSerif
        }
    val annotated =
        remember(block.html, linkStyles) {
            AnnotatedString.fromHtml(htmlString = block.html, linkStyles = linkStyles)
        }
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
internal fun RenderBlockquoteBlock(block: ArticleBlock.Blockquote, linkStyles: TextLinkStyles) {
    val colors = LocalRocheColors.current
    val scale = LocalReaderFontScale.current
    val annotated =
        remember(block.html, linkStyles) {
            AnnotatedString.fromHtml(htmlString = block.html, linkStyles = linkStyles)
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
        Text(
            text = annotated,
            color = colors.textSecondary,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = (15.5f * scale).sp,
            lineHeight = (24.5f * scale).sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun RenderListBlock(block: ArticleBlock.ListBlock, linkStyles: TextLinkStyles) {
    val colors = LocalRocheColors.current
    val scale = LocalReaderFontScale.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        block.itemsHtml.forEachIndexed { index, itemHtml ->
            val marker = if (block.ordered) "${index + 1}." else "•"
            val annotated =
                remember(itemHtml, linkStyles) {
                    AnnotatedString.fromHtml(htmlString = itemHtml, linkStyles = linkStyles)
                }
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
internal fun RenderParagraphBlock(html: String, linkStyles: TextLinkStyles) {
    val colors = LocalRocheColors.current
    val scale = LocalReaderFontScale.current
    val annotated =
        remember(html, linkStyles) {
            AnnotatedString.fromHtml(htmlString = html, linkStyles = linkStyles)
        }
    Text(
        text = annotated,
        color = colors.textPrimary,
        fontFamily = FontFamily.Serif,
        fontSize = (16f * scale).sp,
        lineHeight = (26f * scale).sp,
    )
}

internal fun extractPlainTextHeadings(blocks: List<ArticleBlock>): List<String> =
    blocks
        .filterIsInstance<ArticleBlock.Heading>()
        .map { heading ->
            decodeBasicHtmlEntities(heading.html.replace(ANY_HTML_TAG_REGEX, " "))
                .replace(Regex("""\s+"""), " ")
                .trim()
        }
        .filter { it.isNotEmpty() }
        .distinct()

private const val GROUP_PRE_INNER = 1
private const val GROUP_HEADING_LEVEL = 2
private const val GROUP_HEADING_INNER = 3
private const val GROUP_QUOTE_INNER = 4
private const val GROUP_LIST_TAG = 5
private const val GROUP_LIST_INNER = 6
private const val GROUP_HR = 7
private const val GROUP_FIGURE_INNER = 8
private const val GROUP_PARA_INNER = 9

private const val MIN_HEADING = 1
private const val MAX_HEADING = 6
private const val HEX_PREFIX_LEN = 2
private const val HEX_RADIX = 16
private const val MIN_CODEPOINT = 1
private const val MAX_CODEPOINT = 0x10FFFF

private val TOP_BLOCK_REGEX =
    Regex(
        """<pre\b[^>]*>(.*?)</pre>|<h([1-6])\b[^>]*>(.*?)</h\2>|<blockquote\b[^>]*>(.*?)</blockquote>|<(ul|ol)\b[^>]*>(.*?)</\5>|(<hr\b[^>]*/?>)|<figure\b[^>]*>(.*?)</figure>|<p\b[^>]*>(.*?)</p>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

private val LI_ITEM_REGEX =
    Regex("""<li\b[^>]*>(.*?)</li>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

private val BR_TAG_REGEX = Regex("""<br\b[^>]*/?>""", RegexOption.IGNORE_CASE)

private val MEDIA_TAG_REGEX =
    Regex(
        """<(?:figure\b[^>]*>.*?</figure>|img\b[^>]*/?>|svg\b[^>]*>.*?</svg>|iframe\b[^>]*>.*?</iframe>)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

private val CONTAINER_TAG_REGEX =
    Regex(
        """</?(?:div|section|article|header|footer|main|aside|details|summary|center|span)\b[^>]*>""",
        RegexOption.IGNORE_CASE,
    )

private val PARAGRAPH_BREAK_REGEX =
    Regex("""(?:<br\b[^>]*/?>\s*){2,}|\n\s*\n""", RegexOption.IGNORE_CASE)

private val ANY_HTML_TAG_REGEX = Regex("""<[^>]+>""")

private val ADJACENT_P_TAGS_REGEX = Regex("""</p>\s*<p\b[^>]*>""", RegexOption.IGNORE_CASE)

private val SINGLE_P_TAG_REGEX = Regex("""</?p\b[^>]*>""", RegexOption.IGNORE_CASE)

private val ENTITY_REGEX = Regex("""&([a-zA-Z0-9#]+);""")

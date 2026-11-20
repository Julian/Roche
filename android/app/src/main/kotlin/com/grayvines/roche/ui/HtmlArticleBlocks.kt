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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.grayvines.roche.reader.ArticleBlock
import com.grayvines.roche.reader.computeTableColumnWidths
import com.grayvines.roche.reader.prepareInlineHtmlForCompose
import com.grayvines.roche.reader.resolveSafeAnchorHref
import com.grayvines.roche.ui.theme.LocalReaderFontScale
import com.grayvines.roche.ui.theme.LocalRocheColors
import com.grayvines.roche.ui.theme.currentHeadlineFontFamily

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
                    fontSize = INLINE_CODE_FONT_EM.em,
                ),
                span.start,
                span.end,
            )
        }
        if (isSubOrSuperscript(span.item)) {
            addStyle(SpanStyle(fontSize = SUB_SUPER_FONT_EM.em), span.start, span.end)
        }
    }
}

private const val INLINE_CODE_FONT_EM = 0.92f
private const val SUB_SUPER_FONT_EM = 0.75f

@Composable
internal fun rememberReaderLinkStyles(): TextLinkStyles {
    val accentBlue = LocalRocheColors.current.accentBlue
    return remember(accentBlue) {
        TextLinkStyles(
            style =
                SpanStyle(
                    color = accentBlue,
                    textDecoration = TextDecoration.Underline,
                )
        )
    }
}

@Composable
internal fun StructuredHtmlArticleContent(
    blocks: List<ArticleBlock>,
    baseUrl: String? = null,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val linkStyles = rememberReaderLinkStyles()

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
internal fun RenderArticleBlock(
    block: ArticleBlock,
    linkStyles: TextLinkStyles,
    baseUrl: String?,
    onOpenUrl: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    when (block) {
        is ArticleBlock.Heading ->
            RenderHeadingBlock(
                block = block,
                linkStyles = linkStyles,
                modifier = modifier,
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
    modifier: Modifier = Modifier,
    baseUrl: String? = null,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val colors = LocalRocheColors.current
    val scale = LocalReaderFontScale.current
    val headlineFamily = currentHeadlineFontFamily()
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
    Text(
        text = annotated,
        color = colors.textPrimary,
        fontFamily = headlineFamily,
        fontSize = (baseFontSize * scale).sp,
        lineHeight = (baseLineHeight * scale).sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 4.dp).semantics { heading() },
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
                    softWrap = false,
                    modifier = Modifier.widthIn(min = (24f * scale).dp).padding(end = 6.dp),
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
                modifier = Modifier.width((widthDp * scale).dp),
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

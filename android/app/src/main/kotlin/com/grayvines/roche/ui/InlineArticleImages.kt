@file:Suppress("MatchingDeclarationName")

package com.grayvines.roche.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.model.resolveArticleImageUrl
import com.grayvines.roche.ui.components.LocalThumbnailLoader
import com.grayvines.roche.ui.components.drawArticleImagePlaceholder
import com.grayvines.roche.ui.theme.LocalReaderFontScale
import com.grayvines.roche.ui.theme.LocalRocheColors
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node

internal sealed interface InlineImageLoadState {
    data object Loading : InlineImageLoadState

    data class Success(val bitmap: ImageBitmap) : InlineImageLoadState

    data object Error : InlineImageLoadState
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

internal fun parseImgElement(img: Element, baseUrl: String?): ArticleBlock.ImageBlock? {
    val rawSrc = img.attr("src").trim().takeIf { it.isNotEmpty() }
    val width = img.attr("width").takeIf { it.isNotBlank() }?.let(::parseHtmlPixelDimension)
    val height = img.attr("height").takeIf { it.isNotBlank() }?.let(::parseHtmlPixelDimension)
    val classAttr = img.attr("class").lowercase(Locale.US)
    val alt = img.attr("alt").ifBlank { img.attr("title") }.trim().takeIf { it.isNotEmpty() }

    val resolvedUrl = rawSrc?.let { resolveArticleImageUrl(it, baseUrl) }
    val isAllowedImage =
        !isSmallIconDimension(width, height) &&
            !isEmojiOrSmileyClass(classAttr) &&
            !isFootnoteBackarrowAlt(alt)

    return if (resolvedUrl != null && isAllowedImage) {
        ArticleBlock.ImageBlock(
            url = resolvedUrl,
            alt = alt,
            captionHtml = null,
            width = width,
            height = height,
        )
    } else {
        null
    }
}

private fun isSmallIconDimension(width: Int?, height: Int?): Boolean =
    width != null && width <= MIN_INLINE_IMG_DIMENSION_PX ||
        height != null && height <= MIN_INLINE_IMG_DIMENSION_PX

private fun isEmojiOrSmileyClass(classAttr: String?): Boolean =
    classAttr != null &&
        (classAttr.contains("wp-smiley") ||
            classAttr.contains("emoji") ||
            classAttr.contains("smiley"))

internal fun isFootnoteBackarrowAlt(alt: String?): Boolean = alt == "↩" || alt == "↩︎"

internal fun parseHtmlPixelDimension(raw: String): Int? {
    val trimmed = raw.trim()
    if (trimmed.endsWith("%")) return null
    return trimmed.removeSuffix("px").removeSuffix("PX").trim().toIntOrNull()?.takeIf { it > 0 }
}

@Composable
internal fun RenderImageBlock(block: ArticleBlock.ImageBlock, linkStyles: TextLinkStyles) {
    when (val state = rememberInlineImageState(block.url)) {
        is InlineImageLoadState.Success ->
            RenderLoadedInlineImage(bitmap = state.bitmap, block = block, linkStyles = linkStyles)
        InlineImageLoadState.Loading ->
            RenderLoadingInlineImage(block = block, linkStyles = linkStyles)
        InlineImageLoadState.Error -> RenderErrorInlineImage(block = block, linkStyles = linkStyles)
    }
}

@Composable
private fun RenderLoadedInlineImage(
    bitmap: ImageBitmap,
    block: ArticleBlock.ImageBlock,
    linkStyles: TextLinkStyles,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(8.dp)
    val aspectRatio =
        (bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)).coerceIn(
            MIN_INLINE_ASPECT_RATIO,
            MAX_INLINE_ASPECT_RATIO,
        )
    Column(
        modifier = Modifier.fillMaxWidth().testTag(READER_INLINE_IMAGE_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .aspectRatio(aspectRatio)
                    .clip(shape)
                    .background(colors.surfaceElevated)
                    .border(width = 1.dp, color = colors.border, shape = shape)
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = block.alt,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!block.captionHtml.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            RenderImageCaption(captionHtml = block.captionHtml, linkStyles = linkStyles)
        }
    }
}

@Composable
private fun RenderLoadingInlineImage(
    block: ArticleBlock.ImageBlock,
    linkStyles: TextLinkStyles,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(8.dp)
    val placeholderRatio = resolvePlaceholderAspectRatio(block.width, block.height)
    Column(
        modifier = Modifier.fillMaxWidth().testTag(READER_INLINE_IMAGE_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .aspectRatio(placeholderRatio)
                    .clip(shape)
                    .background(colors.surfaceElevated)
                    .border(width = 1.dp, color = colors.border, shape = shape)
        ) {
            Canvas(modifier = Modifier.matchParentSize()) { drawArticleImagePlaceholder(colors) }
        }
        if (!block.captionHtml.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            RenderImageCaption(captionHtml = block.captionHtml, linkStyles = linkStyles)
        }
    }
}

@Composable
private fun RenderErrorInlineImage(
    block: ArticleBlock.ImageBlock,
    linkStyles: TextLinkStyles,
) {
    if (block.captionHtml.isNullOrBlank() && block.alt.isNullOrBlank()) return
    val colors = LocalRocheColors.current
    val scale = LocalReaderFontScale.current
    val shape = RoundedCornerShape(8.dp)
    Column(modifier = Modifier.fillMaxWidth().testTag(READER_INLINE_IMAGE_TAG)) {
        if (!block.alt.isNullOrBlank()) {
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .clip(shape)
                        .background(colors.surfaceElevated)
                        .border(width = 1.dp, color = colors.border, shape = shape)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = block.alt,
                    color = colors.textMuted,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = (12.5f * scale).sp,
                    lineHeight = (18f * scale).sp,
                )
            }
        }
        if (!block.captionHtml.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            RenderImageCaption(captionHtml = block.captionHtml, linkStyles = linkStyles)
        }
    }
}

private fun resolvePlaceholderAspectRatio(width: Int?, height: Int?): Float {
    val w = width?.takeIf { it > 0 }
    val h = height?.takeIf { it > 0 }
    return if (w != null && h != null) {
        (w.toFloat() / h.toFloat()).coerceIn(MIN_INLINE_ASPECT_RATIO, MAX_INLINE_ASPECT_RATIO)
    } else {
        DEFAULT_INLINE_ASPECT_RATIO
    }
}

@Composable
private fun RenderImageCaption(captionHtml: String, linkStyles: TextLinkStyles) {
    val colors = LocalRocheColors.current
    val scale = LocalReaderFontScale.current
    val annotated =
        rememberRichHtmlText(
            html = captionHtml,
            linkStyles = linkStyles,
            codeBackground = colors.surfaceElevated,
        )
    Text(
        text = annotated,
        color = colors.textSecondary,
        fontFamily = FontFamily.Serif,
        fontStyle = FontStyle.Italic,
        fontSize = (13f * scale).sp,
        lineHeight = (19f * scale).sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    )
}

@Composable
private fun rememberInlineImageState(imageUrl: String): InlineImageLoadState {
    val cleanUrl = imageUrl.trim()
    val loader = LocalThumbnailLoader.current ?: return InlineImageLoadState.Loading
    val initialState =
        loader.getCachedInlineImage(cleanUrl)?.let { InlineImageLoadState.Success(it) }
            ?: InlineImageLoadState.Loading
    val state by
        produceState<InlineImageLoadState>(
            initialValue = initialState,
            key1 = cleanUrl,
            key2 = loader,
        ) {
            if (value !is InlineImageLoadState.Success) {
                val loaded = loader.loadInlineImage(cleanUrl)
                value =
                    if (loaded != null) {
                        InlineImageLoadState.Success(loaded)
                    } else {
                        InlineImageLoadState.Error
                    }
            }
        }
    return state
}

private const val MIN_INLINE_IMG_DIMENSION_PX = 32
private const val MIN_INLINE_ASPECT_RATIO = 0.25f
private const val MAX_INLINE_ASPECT_RATIO = 5.0f
private const val DEFAULT_INLINE_ASPECT_RATIO = 1.777f
private const val READER_INLINE_IMAGE_TAG = "reader_inline_image"
private const val TAG_FIGCAPTION = "figcaption"
private const val TAG_IMG = "img"
private const val TAG_DIV = "div"
private const val DOUBLE_BR_XHTML = "<br/><br/>"
private const val COMPOSITE_FIGURE_QUERY = "blockquote, pre, table"
private val COMPOSITE_CAPTION_BREAK_TAGS =
    setOf("blockquote", "pre", "table", "ul", "ol", "hr", "figure", "div")

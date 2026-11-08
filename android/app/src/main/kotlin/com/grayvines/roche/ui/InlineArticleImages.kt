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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.model.resolveArticleImageUrl
import com.grayvines.roche.ui.components.LocalThumbnailLoader
import com.grayvines.roche.ui.components.drawArticleImagePlaceholder
import com.grayvines.roche.ui.theme.LocalRocheColors
import java.util.Locale

internal sealed interface InlineImageLoadState {
    data object Loading : InlineImageLoadState

    data class Success(val bitmap: ImageBitmap) : InlineImageLoadState

    data object Error : InlineImageLoadState
}

internal fun parseFigureBlocks(
    figureInner: String,
    baseUrl: String?,
    out: MutableList<ArticleBlock>,
) {
    val captionMatch = FIGCAPTION_REGEX.find(figureInner)
    val explicitCaption =
        captionMatch
            ?.groupValues
            ?.get(1)
            ?.let(::normalizeInlineBlockHtml)
            ?.takeIf(::hasVisibleHtmlText)
    val withoutCaption =
        if (captionMatch != null) {
            figureInner.removeRange(captionMatch.range)
        } else {
            figureInner
        }

    val images =
        IMG_TAG_REGEX.findAll(withoutCaption).mapNotNull { parseImgTag(it.value, baseUrl) }.toList()

    if (images.isNotEmpty()) {
        val fallbackCaption =
            if (explicitCaption == null) {
                normalizeInlineBlockHtml(withoutCaption).takeIf(::hasVisibleHtmlText)
            } else {
                null
            }
        val effectiveCaption = explicitCaption ?: fallbackCaption
        images.forEachIndexed { index, img ->
            val attachedCaption = if (index == images.lastIndex) effectiveCaption else null
            out.add(img.copy(captionHtml = attachedCaption))
        }
    } else {
        val textFallback = normalizeInlineBlockHtml(figureInner)
        if (hasVisibleHtmlText(textFallback)) {
            out.add(ArticleBlock.Paragraph(textFallback))
        }
    }
}

internal fun parseParagraphWithImages(
    htmlFragment: String,
    baseUrl: String?,
    out: MutableList<ArticleBlock>,
) {
    if (!htmlFragment.contains("<img", ignoreCase = true)) {
        val clean = stripMediaTags(htmlFragment).trim()
        if (hasVisibleHtmlText(clean)) {
            out.add(ArticleBlock.Paragraph(clean))
        }
        return
    }

    var cursor = 0
    for (match in INLINE_IMG_MATCH_REGEX.findAll(htmlFragment)) {
        if (match.range.first > cursor) {
            val textSlice =
                cleanInlineTextSegment(htmlFragment.substring(cursor, match.range.first))
            if (hasVisibleHtmlText(textSlice)) {
                out.add(ArticleBlock.Paragraph(textSlice))
            }
        }
        val imgTag = match.groupValues[1]
        parseImgTag(imgTag, baseUrl)?.let { out.add(it) }
        cursor = match.range.last + 1
    }
    if (cursor < htmlFragment.length) {
        val tailSlice = cleanInlineTextSegment(htmlFragment.substring(cursor))
        if (hasVisibleHtmlText(tailSlice)) {
            out.add(ArticleBlock.Paragraph(tailSlice))
        }
    }
}

internal fun parseImgTag(imgTagHtml: String, baseUrl: String?): ArticleBlock.ImageBlock? {
    val rawSrc = extractHtmlAttribute(imgTagHtml, "src")?.let(::decodeBasicHtmlEntities)
    val width = extractHtmlAttribute(imgTagHtml, "width")?.let(::parseHtmlPixelDimension)
    val height = extractHtmlAttribute(imgTagHtml, "height")?.let(::parseHtmlPixelDimension)
    val classAttr = extractHtmlAttribute(imgTagHtml, "class")?.lowercase(Locale.US)
    val alt =
        (extractHtmlAttribute(imgTagHtml, "alt") ?: extractHtmlAttribute(imgTagHtml, "title"))
            ?.let(::decodeBasicHtmlEntities)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

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

private fun isFootnoteBackarrowAlt(alt: String?): Boolean = alt == "↩" || alt == "↩︎"

internal fun extractHtmlAttribute(tagHtml: String, attrName: String): String? {
    val pattern =
        Regex(
            """\b$attrName\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>"']+))""",
            RegexOption.IGNORE_CASE,
        )
    val match = pattern.find(tagHtml) ?: return null
    return match.groups[ATTR_GROUP_DOUBLE_QUOTED]?.value
        ?: match.groups[ATTR_GROUP_SINGLE_QUOTED]?.value
        ?: match.groups[ATTR_GROUP_UNQUOTED]?.value
}

internal fun parseHtmlPixelDimension(raw: String): Int? {
    val trimmed = raw.trim()
    if (trimmed.endsWith("%")) return null
    return trimmed.removeSuffix("px").removeSuffix("PX").trim().toIntOrNull()?.takeIf { it > 0 }
}

private fun cleanInlineTextSegment(segment: String): String =
    stripNonImageMediaTags(segment).replace(LEADING_TRAILING_BR_REGEX, "").trim()

internal fun stripNonImageMediaTags(htmlFragment: String): String =
    htmlFragment.replace(NON_IMG_MEDIA_TAG_REGEX, "")

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
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
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
    val w = width?.takeIf { it > 0 } ?: return DEFAULT_INLINE_ASPECT_RATIO
    val h = height?.takeIf { it > 0 } ?: return DEFAULT_INLINE_ASPECT_RATIO
    return (w.toFloat() / h.toFloat()).coerceIn(MIN_INLINE_ASPECT_RATIO, MAX_INLINE_ASPECT_RATIO)
}

@Composable
private fun RenderImageCaption(captionHtml: String, linkStyles: TextLinkStyles) {
    val colors = LocalRocheColors.current
    val annotated =
        remember(captionHtml, linkStyles) {
            AnnotatedString.fromHtml(htmlString = captionHtml, linkStyles = linkStyles)
        }
    Text(
        text = annotated,
        color = colors.textSecondary,
        fontFamily = FontFamily.Serif,
        fontStyle = FontStyle.Italic,
        fontSize = 13.sp,
        lineHeight = 19.sp,
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

private const val ATTR_GROUP_DOUBLE_QUOTED = 1
private const val ATTR_GROUP_SINGLE_QUOTED = 2
private const val ATTR_GROUP_UNQUOTED = 3
private const val MIN_INLINE_IMG_DIMENSION_PX = 32
private const val MIN_INLINE_ASPECT_RATIO = 0.25f
private const val MAX_INLINE_ASPECT_RATIO = 5.0f
private const val DEFAULT_INLINE_ASPECT_RATIO = 1.777f
private const val READER_INLINE_IMAGE_TAG = "reader_inline_image"

private val FIGCAPTION_REGEX =
    Regex(
        """<figcaption\b[^>]*>(.*?)</figcaption>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

private val IMG_TAG_REGEX = Regex("""<img\b[^>]*/?>""", RegexOption.IGNORE_CASE)

private val INLINE_IMG_MATCH_REGEX =
    Regex(
        """(?:<a\b[^>]*>\s*)?(<img\b[^>]*/?>)(?:\s*</a>)?""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

private val NON_IMG_MEDIA_TAG_REGEX =
    Regex(
        """<(?:svg\b[^>]*>.*?</svg>|iframe\b[^>]*>.*?</iframe>)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

private val LEADING_TRAILING_BR_REGEX =
    Regex("""^(?:\s*<br\b[^>]*/?>\s*)+|(?:\s*<br\b[^>]*/?>\s*)+$""", RegexOption.IGNORE_CASE)

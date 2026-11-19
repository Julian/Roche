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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.roche.reader.ArticleBlock
import com.grayvines.roche.ui.components.LocalThumbnailLoader
import com.grayvines.roche.ui.components.drawArticleImagePlaceholder
import com.grayvines.roche.ui.theme.LocalReaderFontScale
import com.grayvines.roche.ui.theme.LocalRocheColors

internal sealed interface InlineImageLoadState {
    data object Loading : InlineImageLoadState

    data class Success(val bitmap: ImageBitmap) : InlineImageLoadState

    data object Error : InlineImageLoadState
}

@Composable
internal fun RenderImageBlock(
    block: ArticleBlock.ImageBlock,
    linkStyles: TextLinkStyles,
    baseUrl: String? = null,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    when (val state = rememberInlineImageState(block.url)) {
        is InlineImageLoadState.Success ->
            RenderLoadedInlineImage(
                bitmap = state.bitmap,
                block = block,
                linkStyles = linkStyles,
                baseUrl = baseUrl,
                onOpenUrl = onOpenUrl,
            )
        InlineImageLoadState.Loading ->
            RenderLoadingInlineImage(
                block = block,
                linkStyles = linkStyles,
                baseUrl = baseUrl,
                onOpenUrl = onOpenUrl,
            )
        InlineImageLoadState.Error ->
            RenderErrorInlineImage(
                block = block,
                linkStyles = linkStyles,
                baseUrl = baseUrl,
                onOpenUrl = onOpenUrl,
            )
    }
}

@Composable
private fun RenderLoadedInlineImage(
    bitmap: ImageBitmap,
    block: ArticleBlock.ImageBlock,
    linkStyles: TextLinkStyles,
    baseUrl: String?,
    onOpenUrl: ((String) -> Unit)?,
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
            RenderImageCaption(
                captionHtml = block.captionHtml,
                linkStyles = linkStyles,
                baseUrl = baseUrl,
                onOpenUrl = onOpenUrl,
            )
        }
    }
}

@Composable
private fun RenderLoadingInlineImage(
    block: ArticleBlock.ImageBlock,
    linkStyles: TextLinkStyles,
    baseUrl: String?,
    onOpenUrl: ((String) -> Unit)?,
) {
    val colors = LocalRocheColors.current
    val shape = RoundedCornerShape(8.dp)
    val landscapePath = remember { Path() }
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
            Canvas(modifier = Modifier.matchParentSize()) {
                drawArticleImagePlaceholder(colors = colors, landscape = landscapePath)
            }
        }
        if (!block.captionHtml.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            RenderImageCaption(
                captionHtml = block.captionHtml,
                linkStyles = linkStyles,
                baseUrl = baseUrl,
                onOpenUrl = onOpenUrl,
            )
        }
    }
}

@Composable
private fun RenderErrorInlineImage(
    block: ArticleBlock.ImageBlock,
    linkStyles: TextLinkStyles,
    baseUrl: String?,
    onOpenUrl: ((String) -> Unit)?,
) {
    val colors = LocalRocheColors.current
    val scale = LocalReaderFontScale.current
    val shape = RoundedCornerShape(8.dp)
    val fallbackLabel = block.alt?.takeIf { it.isNotBlank() } ?: "Image unavailable"
    Column(modifier = Modifier.fillMaxWidth().testTag(READER_INLINE_IMAGE_TAG)) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .clip(shape)
                    .background(colors.surfaceElevated)
                    .border(width = 1.dp, color = colors.border, shape = shape)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = fallbackLabel,
                color = colors.textMuted,
                fontFamily = FontFamily.SansSerif,
                fontSize = (12.5f * scale).sp,
                lineHeight = (18f * scale).sp,
            )
        }
        if (!block.captionHtml.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            RenderImageCaption(
                captionHtml = block.captionHtml,
                linkStyles = linkStyles,
                baseUrl = baseUrl,
                onOpenUrl = onOpenUrl,
            )
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
private fun RenderImageCaption(
    captionHtml: String,
    linkStyles: TextLinkStyles,
    baseUrl: String?,
    onOpenUrl: ((String) -> Unit)?,
) {
    val colors = LocalRocheColors.current
    val scale = LocalReaderFontScale.current
    val annotated =
        rememberRichHtmlText(
            html = captionHtml,
            linkStyles = linkStyles,
            codeBackground = colors.surfaceElevated,
            baseUrl = baseUrl,
            onOpenUrl = onOpenUrl,
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
    val loader = LocalThumbnailLoader.current ?: return InlineImageLoadState.Error
    val initialState =
        remember(cleanUrl, loader) {
            loader.getCachedInlineImage(cleanUrl)?.let {
                InlineImageLoadState.Success(it.asImageBitmap())
            } ?: InlineImageLoadState.Loading
        }
    val state by
        produceState<InlineImageLoadState>(
            initialValue = initialState,
            key1 = cleanUrl,
            key2 = loader,
        ) {
            value = initialState
            if (value !is InlineImageLoadState.Success) {
                val loaded = loader.loadInlineImage(cleanUrl)
                value =
                    if (loaded != null) {
                        InlineImageLoadState.Success(loaded.asImageBitmap())
                    } else {
                        InlineImageLoadState.Error
                    }
            }
        }
    return state
}

private const val MIN_INLINE_ASPECT_RATIO = 0.25f
private const val MAX_INLINE_ASPECT_RATIO = 5.0f
private const val DEFAULT_INLINE_ASPECT_RATIO = 1.777f
private const val READER_INLINE_IMAGE_TAG = "reader_inline_image"

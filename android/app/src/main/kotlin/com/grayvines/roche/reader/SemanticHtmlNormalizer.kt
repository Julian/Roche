@file:Suppress("TooManyFunctions", "MatchingDeclarationName")

package com.grayvines.roche.reader

import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Safelist

internal data class TypographicFlags(
    val italic: Boolean? = null,
    val bold: Boolean? = null,
    val underline: Boolean? = null,
    val strikethrough: Boolean? = null,
    val monospace: Boolean? = null,
    val superscript: Boolean? = null,
    val subscript: Boolean? = null,
    val small: Boolean? = null,
    val hidden: Boolean? = null,
)

private data class AncestorFormattingContext(
    val inPre: Boolean = false,
    val inEm: Boolean = false,
    val inStrong: Boolean = false,
    val inU: Boolean = false,
    val inS: Boolean = false,
    val inCode: Boolean = false,
    val inSup: Boolean = false,
    val inSub: Boolean = false,
    val inSmall: Boolean = false,
)

private fun TypographicFlags.mergeOver(other: TypographicFlags): TypographicFlags =
    TypographicFlags(
        italic = other.italic ?: italic,
        bold = other.bold ?: bold,
        underline = other.underline ?: underline,
        strikethrough = other.strikethrough ?: strikethrough,
        monospace = other.monospace ?: monospace,
        superscript = other.superscript ?: superscript,
        subscript = other.subscript ?: subscript,
        small = other.small ?: small,
        hidden = other.hidden ?: hidden,
    )

private fun TypographicFlags.isEmpty(): Boolean =
    italic == null &&
        bold == null &&
        underline == null &&
        strikethrough == null &&
        monospace == null &&
        superscript == null &&
        subscript == null &&
        small == null &&
        hidden == null

private fun AncestorFormattingContext.forTag(tag: String): AncestorFormattingContext =
    when (tag) {
        TAG_PRE -> copy(inPre = true)
        in ITALIC_ANCESTOR_TAGS -> copy(inEm = true)
        in BOLD_ANCESTOR_TAGS -> copy(inStrong = true)
        in UNDERLINE_ANCESTOR_TAGS -> copy(inU = true)
        in STRIKE_ANCESTOR_TAGS -> copy(inS = true)
        in CODE_ANCESTOR_TAGS -> copy(inCode = true)
        TAG_SUP -> copy(inSup = true)
        TAG_SUB -> copy(inSub = true)
        TAG_SMALL -> copy(inSmall = true)
        else -> this
    }

private fun AncestorFormattingContext.withFlags(
    flags: TypographicFlags,
    hostTag: String,
): AncestorFormattingContext =
    copy(
        inEm = inEm || flags.italic == true,
        inStrong = inStrong || flags.bold == true,
        inU = inU || flags.underline == true && hostTag != TAG_A,
        inS = inS || flags.strikethrough == true,
        inCode = inCode || flags.monospace == true,
        inSup = inSup || flags.superscript == true,
        inSub = inSub || flags.subscript == true,
        inSmall = inSmall || flags.small == true,
    )

internal fun normalizeProseId(raw: String): String? {
    val trimmed = raw.trim()
    val validChars =
        trimmed.isNotEmpty() &&
            trimmed.length <= MAX_PROSE_ID_LEN &&
            trimmed.all(::isValidProseIdChar)
    return when {
        !validChars -> null
        trimmed.startsWith(PROSE_ID_PREFIX, ignoreCase = true) -> trimmed
        else -> "$PROSE_ID_PREFIX$trimmed"
    }
}

private fun isValidProseIdChar(ch: Char): Boolean =
    ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in PROSE_ID_PUNCTUATION

internal fun parseStylesheetRules(doc: Document): Map<String, TypographicFlags> {
    val rules = LinkedHashMap<String, TypographicFlags>()
    for (styleEl in doc.select("style")) {
        parseCssStylesheetInto(styleEl.data(), insideMedia = false, out = rules)
    }
    return rules
}

private data class CssRuleSlice(val header: String, val body: String, val nextIndex: Int)

private fun parseCssStylesheetInto(
    rawCss: String,
    insideMedia: Boolean,
    out: MutableMap<String, TypographicFlags>,
) {
    val cleaned = CSS_COMMENT_REGEX.replace(rawCss, "")
    var cursor = skipCssSeparators(cleaned, 0)
    while (cursor < cleaned.length) {
        val slice = readNextCssRule(cleaned, cursor)
        if (slice == null) {
            cursor = cleaned.length
        } else {
            if (slice.header.isNotEmpty()) {
                applyCssRuleBlock(slice.header, slice.body, insideMedia, out)
            }
            cursor = skipCssSeparators(cleaned, slice.nextIndex)
        }
    }
}

private fun skipCssSeparators(text: String, start: Int): Int {
    var idx = start
    while (idx < text.length && (text[idx].isWhitespace() || text[idx] == ';')) {
        idx++
    }
    return idx
}

private fun readNextCssRule(text: String, start: Int): CssRuleSlice? {
    var cursor = start
    while (cursor < text.length && text[cursor] != '{' && text[cursor] != ';') {
        cursor++
    }
    return when {
        cursor >= text.length -> null
        text[cursor] == ';' -> CssRuleSlice(header = "", body = "", nextIndex = cursor + 1)
        else -> sliceCssRuleBlock(text, start, cursor)
    }
}

private fun sliceCssRuleBlock(text: String, start: Int, openBraceIdx: Int): CssRuleSlice? {
    val bodyEnd = findMatchingCssBrace(text, openBraceIdx)
    return if (bodyEnd < 0) {
        null
    } else {
        CssRuleSlice(
            header = text.substring(start, openBraceIdx).trim(),
            body = text.substring(openBraceIdx + 1, bodyEnd),
            nextIndex = bodyEnd + 1,
        )
    }
}

private fun applyCssRuleBlock(
    header: String,
    body: String,
    insideMedia: Boolean,
    out: MutableMap<String, TypographicFlags>,
) {
    if (header.startsWith("@")) {
        val lowerHeader = header.lowercase(Locale.US)
        if (lowerHeader.startsWith("@media") && "print" !in lowerHeader) {
            parseCssStylesheetInto(body, insideMedia = true, out = out)
        }
        return
    }
    val parsedFlags = parseCssDeclarations(body)
    val flags = if (insideMedia) parsedFlags.copy(hidden = null) else parsedFlags
    if (flags.isEmpty()) return
    for (selector in header.split(',')) {
        val cls = extractTargetClassFromSelector(selector) ?: continue
        val existing = out[cls] ?: TypographicFlags()
        out[cls] = existing.mergeOver(flags)
    }
}

private fun findMatchingCssBrace(text: String, openIdx: Int): Int {
    var depth = 0
    var idx = openIdx
    var matchedIdx = -1
    while (idx < text.length && matchedIdx < 0) {
        val ch = text[idx]
        if (ch == '"' || ch == '\'') {
            idx = skipQuotedCssLiteral(text, idx, ch)
        } else {
            if (ch == '{') {
                depth++
            } else if (ch == '}') {
                depth--
                if (depth == 0) matchedIdx = idx
            }
            idx++
        }
    }
    return matchedIdx
}

private fun skipQuotedCssLiteral(text: String, openQuoteIdx: Int, quote: Char): Int {
    var idx = openQuoteIdx + 1
    while (idx < text.length) {
        val ch = text[idx]
        if (ch == '\\') {
            idx += 2
        } else if (ch == quote) {
            return idx + 1
        } else {
            idx++
        }
    }
    return text.length
}

private fun extractTargetClassFromSelector(rawSelector: String): String? {
    val trimmed = rawSelector.trim()
    val rejected =
        trimmed.isEmpty() ||
            ':' in trimmed ||
            '[' in trimmed ||
            SKIPPED_SELECTOR_CONTEXTS.any { it in trimmed.lowercase(Locale.US) }
    if (rejected) return null
    val lastCompound = trimmed.split(CSS_COMBINATOR_SPLIT_REGEX).lastOrNull()?.trim().orEmpty()
    val dotIdx = lastCompound.indexOf('.')
    if (dotIdx < 0) return null
    val prefix = lastCompound.substring(0, dotIdx)
    val classPart = lastCompound.substring(dotIdx + 1)
    return if (isValidSelectorPrefix(prefix) && isValidCssClassName(classPart)) {
        classPart.lowercase(Locale.US)
    } else {
        null
    }
}

private fun isValidSelectorPrefix(prefix: String): Boolean =
    prefix.isEmpty() || prefix.all { it.isLetterOrDigit() || it == '-' }

private fun isValidCssClassName(classPart: String): Boolean =
    classPart.isNotEmpty() && classPart.all { it.isLetterOrDigit() || it == '-' || it == '_' }

internal fun parseCssDeclarations(decls: String): TypographicFlags {
    var flags = TypographicFlags()
    for (part in decls.split(';')) {
        val colonIdx = part.indexOf(':')
        if (colonIdx <= 0) continue
        val prop = part.substring(0, colonIdx).trim().lowercase(Locale.US)
        val rawVal = part.substring(colonIdx + 1).trim()
        val value = rawVal.removeSuffix("!important").trim().lowercase(Locale.US)
        if (value.isNotEmpty()) {
            flags = applyCssProperty(flags, prop, value)
        }
    }
    return flags
}

private fun applyCssProperty(
    flags: TypographicFlags,
    prop: String,
    value: String,
): TypographicFlags =
    when (prop) {
        "font-style" -> applyFontStyleProperty(flags, value)
        "font-weight" -> applyFontWeightProperty(flags, value)
        "text-decoration",
        "text-decoration-line" -> applyTextDecorationProperty(flags, value)
        "font-family" -> applyFontFamilyProperty(flags, value)
        "vertical-align" -> applyVerticalAlignProperty(flags, value)
        "font-size" -> if (isSmallCssFontSize(value)) flags.copy(small = true) else flags
        "display" -> if (value == "none") flags.copy(hidden = true) else flags
        "visibility" -> if (value == CSS_HIDDEN) flags.copy(hidden = true) else flags
        else -> flags
    }

private fun applyFontFamilyProperty(flags: TypographicFlags, value: String): TypographicFlags =
    if (MONOSPACE_FONT_TOKENS.any { it in value }) flags.copy(monospace = true) else flags

private fun applyVerticalAlignProperty(flags: TypographicFlags, value: String): TypographicFlags =
    when (value) {
        "super" -> flags.copy(superscript = true)
        TAG_SUB -> flags.copy(subscript = true)
        "baseline",
        CSS_NORMAL -> flags.copy(superscript = false, subscript = false)
        else -> flags
    }

private fun applyFontStyleProperty(flags: TypographicFlags, value: String): TypographicFlags =
    when {
        value.startsWith("italic") || value.startsWith("oblique") -> flags.copy(italic = true)
        value == CSS_NORMAL || value == "initial" || value == "unset" -> flags.copy(italic = false)
        else -> flags
    }

private fun applyFontWeightProperty(flags: TypographicFlags, value: String): TypographicFlags =
    when {
        value == "bold" || value == "bolder" -> flags.copy(bold = true)
        value == CSS_NORMAL || value == "lighter" || value == "initial" || value == "unset" ->
            flags.copy(bold = false)
        else -> value.toIntOrNull()?.let { flags.copy(bold = it >= BOLD_FONT_WEIGHT_MIN) } ?: flags
    }

private fun applyTextDecorationProperty(flags: TypographicFlags, value: String): TypographicFlags {
    if (value == "none") return flags.copy(underline = false, strikethrough = false)
    val tokens = value.split(WHITESPACE_SPLIT_REGEX)
    return flags.copy(
        strikethrough = if ("line-through" in tokens) true else flags.strikethrough,
        underline = if ("underline" in tokens) true else flags.underline,
    )
}

private fun isSmallCssFontSize(value: String): Boolean {
    if (value in SMALL_FONT_SIZE_KEYWORDS) return true
    if (value.endsWith("em") && !value.endsWith("rem")) {
        val parsed = value.removeSuffix("em").trim().toFloatOrNull()
        return parsed != null && parsed > 0f && parsed <= MAX_SMALL_FONT_EM
    }
    if (value.endsWith("%")) {
        val parsed = value.removeSuffix("%").trim().toFloatOrNull()
        return parsed != null && parsed > 0f && parsed <= MAX_SMALL_FONT_PCT
    }
    return false
}

private fun builtinClassFlags(classAttr: String): TypographicFlags {
    var flags = TypographicFlags()
    for (rawTok in classAttr.split(WHITESPACE_SPLIT_REGEX)) {
        val tok = rawTok.lowercase(Locale.US)
        when (tok) {
            in HIDDEN_CLASSES -> flags = flags.copy(hidden = true)
            in ITALIC_SEMANTIC_CLASSES -> flags = flags.copy(italic = true)
            in BOLD_SEMANTIC_CLASSES -> flags = flags.copy(bold = true)
            in UNDERLINE_SEMANTIC_CLASSES -> flags = flags.copy(underline = true)
            in STRIKE_SEMANTIC_CLASSES -> flags = flags.copy(strikethrough = true)
            in MONO_SEMANTIC_CLASSES -> flags = flags.copy(monospace = true)
            in SMALL_NOTE_CLASSES -> flags = flags.copy(small = true)
        }
    }
    return flags
}

internal fun liftSemanticStylesAndClasses(
    root: Element,
    cssClassRules: Map<String, TypographicFlags> = emptyMap(),
) {
    transformElementChildren(
        parent = root,
        cssClassRules = cssClassRules,
        inherited = TypographicFlags(),
        ctx = AncestorFormattingContext(),
    )
}

private fun transformElementChildren(
    parent: Element,
    cssClassRules: Map<String, TypographicFlags>,
    inherited: TypographicFlags,
    ctx: AncestorFormattingContext,
) {
    for (child in parent.children().toList()) {
        transformSingleElement(child, cssClassRules, inherited, ctx)
    }
}

private fun transformSingleElement(
    element: Element,
    cssClassRules: Map<String, TypographicFlags>,
    inherited: TypographicFlags,
    ctx: AncestorFormattingContext,
) {
    val own = resolveElementFlags(element, cssClassRules)
    if (own.hidden == true) {
        element.remove()
        return
    }
    val effective = inherited.mergeOver(own)
    val tag = element.normalName()
    val nextCtx = ctx.forTag(tag)
    if (nextCtx.inPre) return

    val hasBlockChild = tag in SEMANTIC_BLOCK_TAGS && hasBlockDescendant(element)
    if (hasBlockChild) {
        transformElementChildren(element, cssClassRules, effective, nextCtx)
        return
    }

    val childCtx = nextCtx.withFlags(effective, tag)
    transformElementChildren(element, cssClassRules, TypographicFlags(), childCtx)
    if (hasVisibleDomText(element)) {
        wrapElementInlineChildren(element, effective, nextCtx, tag)
    }
    if (tag == "span" && element.attr("id").isBlank() && !effective.isEmpty()) {
        element.unwrap()
    }
}

private fun resolveElementFlags(
    element: Element,
    cssClassRules: Map<String, TypographicFlags>,
): TypographicFlags {
    var flags = TypographicFlags()
    if (element.hasAttr(CSS_HIDDEN)) {
        flags = flags.copy(hidden = true)
    }
    val classAttr = element.attr("class")
    if (classAttr.isNotBlank()) {
        for (rawTok in classAttr.split(WHITESPACE_SPLIT_REGEX)) {
            cssClassRules[rawTok.lowercase(Locale.US)]?.let { flags = flags.mergeOver(it) }
        }
        flags = flags.mergeOver(builtinClassFlags(classAttr))
    }
    val styleAttr = element.attr("style")
    if (styleAttr.isNotBlank()) {
        flags = flags.mergeOver(parseCssDeclarations(styleAttr))
    }
    return flags
}

private fun hasBlockDescendant(element: Element): Boolean =
    element.children().any { child ->
        child.normalName() in SEMANTIC_BLOCK_TAGS || hasBlockDescendant(child)
    }

private fun soleMeaningfulChildTag(element: Element): String? {
    var found: String? = null
    var valid = true
    for (node in element.childNodes()) {
        if (!valid) break
        when (node) {
            is TextNode -> {
                if (node.wholeText.replace('\u00A0', ' ').isNotBlank()) valid = false
            }
            is Element -> {
                if (found != null) {
                    valid = false
                } else {
                    found = node.normalName()
                }
            }
        }
    }
    return if (valid) found else null
}

private fun wrapAllChildrenIn(element: Element, wrapperTag: String) {
    val wrapper = Element(wrapperTag)
    val children: List<Node> = element.childNodes().toList()
    for (child in children) {
        wrapper.appendChild(child)
    }
    element.appendChild(wrapper)
}

private fun wrapIfNeeded(
    element: Element,
    shouldWrap: Boolean,
    alreadyInContext: Boolean,
    wrapperTag: String,
    existingTags: Set<String>,
) {
    if (shouldWrap && !alreadyInContext && soleMeaningfulChildTag(element) !in existingTags) {
        wrapAllChildrenIn(element, wrapperTag)
    }
}

private fun wrapElementInlineChildren(
    element: Element,
    flags: TypographicFlags,
    ctx: AncestorFormattingContext,
    hostTag: String,
) {
    wrapIfNeeded(element, flags.monospace == true, ctx.inCode, TAG_CODE, CODE_ONLY_TAG_SET)
    wrapIfNeeded(element, flags.italic == true, ctx.inEm, TAG_EM, ITALIC_WRAPPER_SKIP_TAGS)
    wrapIfNeeded(element, flags.bold == true, ctx.inStrong, TAG_STRONG, BOLD_ANCESTOR_TAGS)
    wrapIfNeeded(
        element,
        flags.underline == true && hostTag != TAG_A,
        ctx.inU,
        TAG_U,
        UNDERLINE_ANCESTOR_TAGS,
    )
    wrapIfNeeded(element, flags.strikethrough == true, ctx.inS, TAG_S, STRIKE_ANCESTOR_TAGS)
    if (flags.superscript == true) {
        wrapIfNeeded(element, true, ctx.inSup, TAG_SUP, SUP_ONLY_TAG_SET)
    } else if (flags.subscript == true) {
        wrapIfNeeded(element, true, ctx.inSub, TAG_SUB, SUB_ONLY_TAG_SET)
    }
    wrapIfNeeded(element, flags.small == true, ctx.inSmall, TAG_SMALL, SMALL_ONLY_TAG_SET)
}

internal fun sanitizeRocheHtml(
    rawHtml: String,
    baseUrl: String,
    cssClassRules: Map<String, TypographicFlags> = emptyMap(),
): String {
    val cleanBase = baseUrl.trim()
    val workingDoc = Jsoup.parseBodyFragment(rawHtml, cleanBase)
    workingDoc.outputSettings().prettyPrint(false)
    val body = workingDoc.body()
    body.select(DANGEROUS_PRE_STRIP_QUERY).remove()
    liftSemanticStylesAndClasses(body, cssClassRules)
    val cleaned = Jsoup.clean(body.html(), cleanBase, ROCHE_HTML_SAFELIST).trim()
    if ("id=" !in cleaned && "ID=" !in cleaned) return cleaned
    val idDoc = Jsoup.parseBodyFragment(cleaned, cleanBase)
    idDoc.outputSettings().prettyPrint(false)
    for (el in idDoc.body().select("[id]")) {
        val normalized = normalizeProseId(el.attr("id"))
        if (normalized != null) {
            el.attr("id", normalized)
        } else {
            el.removeAttr("id")
        }
    }
    return idDoc.body().html().trim()
}

internal fun selectArticleContainer(doc: Document): Element {
    doc.select(WEBPAGE_BOILERPLATE_QUERY).remove()
    for (el in doc.select("header, footer").toList()) {
        if (el.parents().none { it.normalName() == TAG_BLOCKQUOTE }) {
            val cls = el.attr("class").lowercase(Locale.US)
            val isArticleHeader =
                "entry-header" in cls || "post-header" in cls || "article-header" in cls
            if (!isArticleHeader) {
                el.remove()
            }
        }
    }
    return pickRichestElement(doc.select(EXPLICIT_ARTICLE_BODY_QUERY))
        ?: pickRichestElement(doc.select(TAG_ARTICLE))
        ?: doc.selectFirst("main")
        ?: doc.selectFirst("[role=main]")
        ?: doc.body()
}

private fun pickRichestElement(candidates: List<Element>): Element? =
    candidates.maxByOrNull { it.text().length }?.takeIf { it.text().isNotBlank() }

private fun buildRocheHtmlSafelist(): Safelist =
    Safelist.relaxed()
        .addTags(
            TAG_ARTICLE,
            "aside",
            "abbr",
            TAG_DEL,
            "details",
            TAG_DFN,
            TAG_FIGCAPTION,
            "figure",
            TAG_FOOTER,
            TAG_HEADER,
            "hr",
            TAG_INS,
            "kbd",
            "main",
            "mark",
            TAG_S,
            "samp",
            "section",
            "summary",
            "time",
            "var",
        )
        .addAttributes(":all", "id")
        .addAttributes("ol", "start")
        .addAttributes("abbr", "title")
        .addAttributes("td", "colspan", "rowspan")
        .addAttributes("th", "colspan", "rowspan")

private val ROCHE_HTML_SAFELIST: Safelist = buildRocheHtmlSafelist()

private const val MAX_PROSE_ID_LEN = 128
private const val PROSE_ID_PREFIX = "prose-"
private const val PROSE_ID_PUNCTUATION = "-_.:"
private const val BOLD_FONT_WEIGHT_MIN = 600
private const val MAX_SMALL_FONT_EM = 0.88f
private const val MAX_SMALL_FONT_PCT = 88f

private const val CSS_NORMAL = "normal"
private const val CSS_HIDDEN = "hidden"

private const val TAG_A = "a"
private const val TAG_ARTICLE = "article"
private const val TAG_BLOCKQUOTE = "blockquote"
private const val TAG_CODE = "code"
private const val TAG_DEL = "del"
private const val TAG_DFN = "dfn"
private const val TAG_EM = "em"
private const val TAG_FIGCAPTION = "figcaption"
private const val TAG_FOOTER = "footer"
private const val TAG_HEADER = "header"
private const val TAG_INS = "ins"
private const val TAG_PRE = "pre"
private const val TAG_S = "s"
private const val TAG_SMALL = "small"
private const val TAG_STRIKE = "strike"
private const val TAG_STRONG = "strong"
private const val TAG_SUB = "sub"
private const val TAG_SUP = "sup"
private const val TAG_U = "u"

private val ITALIC_ANCESTOR_TAGS =
    setOf(TAG_EM, "i", "cite", TAG_DFN, "var", TAG_BLOCKQUOTE, TAG_FIGCAPTION)
private val ITALIC_WRAPPER_SKIP_TAGS = setOf(TAG_EM, "i", "cite", TAG_DFN)
private val BOLD_ANCESTOR_TAGS = setOf(TAG_STRONG, "b")
private val UNDERLINE_ANCESTOR_TAGS = setOf(TAG_U, TAG_INS)
private val STRIKE_ANCESTOR_TAGS = setOf(TAG_S, TAG_DEL, TAG_STRIKE)
private val CODE_ANCESTOR_TAGS = setOf(TAG_CODE, "tt", "kbd", "samp")
private val CODE_ONLY_TAG_SET = setOf(TAG_CODE)
private val SUP_ONLY_TAG_SET = setOf(TAG_SUP)
private val SUB_ONLY_TAG_SET = setOf(TAG_SUB)
private val SMALL_ONLY_TAG_SET = setOf(TAG_SMALL)

private val CSS_COMMENT_REGEX = Regex("""/\*[\s\S]*?\*/""")
private val CSS_COMBINATOR_SPLIT_REGEX = Regex("""[\s>+~]+""")
private val WHITESPACE_SPLIT_REGEX = Regex("""\s+""")

private const val DANGEROUS_PRE_STRIP_QUERY =
    "script, style, noscript, template, svg, math, iframe, object, embed"

private const val WEBPAGE_BOILERPLATE_QUERY =
    "script, style, noscript, template, svg, nav, aside, form, iframe, object, embed, " +
        "#comments, #respond, #sidebar, #likes-other-gravatars, " +
        ".comments-area, .comment-list, .comment-respond, .wp-block-comments, " +
        ".wp-block-latest-comments, .wp-block-post-comments-form, " +
        ".sharedaddy, .sd-sharing, .jetpack-likes-widget-wrapper, " +
        ".jp-relatedposts, .jp-relatedposts-i2, .pp-read-next, .related-posts, " +
        ".share-buttons, .social-share, .site-header, .site-footer, " +
        ".sidebar, .widget-area, .breadcrumb, .breadcrumbs, [role=navigation], " +
        "[role=banner], [role=contentinfo], [role=complementary], [role=search], [role=dialog]"

private const val EXPLICIT_ARTICLE_BODY_QUERY =
    ".entry-content, .post-content, .wp-block-post-content, .article-content, " +
        ".article-body, .post-body, .story-body, [itemprop=articleBody]"

private val SKIPPED_SELECTOR_CONTEXTS =
    setOf(
        ".home",
        ".page-id-",
        TAG_HEADER,
        TAG_FOOTER,
        "nav",
        ".sidebar",
        ".comment",
        ".wp-block-comment",
        ".wp-block-latest-comments",
        ".wp-block-navigation",
        ".wp-block-site-",
        ".pp-brand",
        ".pp-summary",
        ".pp-terms",
        ".pp-intro",
    )

private val MONOSPACE_FONT_TOKENS = setOf("monospace", "courier", "menlo", "consolas", "monaco")

private val SMALL_FONT_SIZE_KEYWORDS = setOf("smaller", TAG_SMALL, "x-small", "xx-small")

private val HIDDEN_CLASSES =
    setOf(
        "sr-only",
        "screen-reader-text",
        "visually-hidden",
        "pp-visually-hidden",
        "d-none",
        "u-hidden",
        CSS_HIDDEN,
        "sharedaddy",
        "jp-relatedposts",
        "jetpack-likes-widget-wrapper",
    )

private val ITALIC_SEMANTIC_CLASSES =
    setOf(
        "author-line",
        "byline",
        "post-byline",
        "article-byline",
        "entry-byline",
        "author-bio",
        "author-credit",
        "author-affiliation",
        "subtitle",
        "post-subtitle",
        "article-subtitle",
        "entry-subtitle",
        "subhead",
        "deck",
        "dek",
        "standfirst",
        "epigraph",
        "caption",
        "wp-caption-text",
        "wp-element-caption",
        "image-caption",
        "photo-caption",
        "figure-caption",
        "credit",
        "photo-credit",
        "attribution",
        "italic",
        "italics",
        "font-italic",
        "is-italic",
        "oblique",
    )

private val BOLD_SEMANTIC_CLASSES = setOf("bold", "font-bold", "font-semibold", "is-bold")
private val UNDERLINE_SEMANTIC_CLASSES = setOf("underline", "is-underlined")
private val STRIKE_SEMANTIC_CLASSES =
    setOf("line-through", TAG_STRIKE, "strikethrough", "is-strikethrough")
private val MONO_SEMANTIC_CLASSES = setOf("monospace", "font-mono")
private val SMALL_NOTE_CLASSES =
    setOf("pp-in-reply-to", "pp-series-note", "series-note", "editors-note", "editor-note")

private val SEMANTIC_BLOCK_TAGS =
    setOf(
        "address",
        TAG_ARTICLE,
        "aside",
        TAG_BLOCKQUOTE,
        "caption",
        "center",
        "dd",
        "details",
        "div",
        "dl",
        "dt",
        TAG_FIGCAPTION,
        "figure",
        TAG_FOOTER,
        "form",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        TAG_HEADER,
        "hgroup",
        "hr",
        "li",
        "main",
        "nav",
        "ol",
        "p",
        TAG_PRE,
        "section",
        "summary",
        "table",
        "tbody",
        "td",
        "tfoot",
        "th",
        "thead",
        "tr",
        "ul",
    )

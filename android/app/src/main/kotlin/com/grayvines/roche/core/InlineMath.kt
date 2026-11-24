@file:Suppress("TooManyFunctions")

package com.grayvines.roche.core

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.em

private const val MAX_INLINE_MATH_LEN = 256
private const val SCRIPT_FONT_SCALE = 0.78f
private const val CMD_MATHBB = "mathbb"
private const val CMD_SQRT = "sqrt"
private const val CMD_QUAD = "quad"
private const val CMD_QQUAD = "qquad"

private val FRAC_COMMANDS: Set<String> = setOf("frac", "dfrac", "tfrac")
private val UPRIGHT_TEXT_COMMANDS: Set<String> =
    setOf("mathrm", "mathbf", "text", "textrm", "textbf", "operatorname")
private val ITALIC_TEXT_COMMANDS: Set<String> = setOf("mathit", "textit")
private val SPACING_DELIM_COMMANDS: Set<String> =
    setOf("left", "right", "bigl", "bigr", "Bigl", "Bigr", CMD_QUAD, CMD_QQUAD)
private val ESCAPED_SPACE_CHARS: Set<Char> = setOf(',', ';', ':', ' ')
private val ESCAPED_LITERAL_CHARS: Set<Char> = setOf('{', '}', '$', '%', '&', '#', '_')
private val MATH_OP_INDICATORS: Set<Char> =
    setOf('+', '-', '=', '<', '>', '^', '_', '*', '/', '(', ')', '[', ']')
private val OPEN_GROUP_CHARS: Set<Char> = setOf('(', '[', '{')

private val LATEX_SYMBOLS: Map<String, String> =
    mapOf(
        "alpha" to "α",
        "beta" to "β",
        "gamma" to "γ",
        "delta" to "δ",
        "epsilon" to "ϵ",
        "varepsilon" to "ε",
        "zeta" to "ζ",
        "eta" to "η",
        "theta" to "θ",
        "vartheta" to "ϑ",
        "iota" to "ι",
        "kappa" to "κ",
        "lambda" to "λ",
        "mu" to "μ",
        "nu" to "ν",
        "xi" to "ξ",
        "pi" to "π",
        "rho" to "ρ",
        "sigma" to "σ",
        "tau" to "τ",
        "upsilon" to "υ",
        "phi" to "ϕ",
        "varphi" to "φ",
        "chi" to "χ",
        "psi" to "ψ",
        "omega" to "ω",
        "Gamma" to "Γ",
        "Delta" to "Δ",
        "Theta" to "Θ",
        "Lambda" to "Λ",
        "Xi" to "Ξ",
        "Pi" to "Π",
        "Sigma" to "Σ",
        "Phi" to "Φ",
        "Psi" to "Ψ",
        "Omega" to "Ω",
        "ell" to "ℓ",
        "infty" to "∞",
        "partial" to "∂",
        "nabla" to "∇",
        "forall" to "∀",
        "exists" to "∃",
        "emptyset" to "∅",
        "varnothing" to "∅",
        "leq" to " ≤ ",
        "le" to " ≤ ",
        "geq" to " ≥ ",
        "ge" to " ≥ ",
        "neq" to " ≠ ",
        "ne" to " ≠ ",
        "approx" to " ≈ ",
        "equiv" to " ≡ ",
        "sim" to " ∼ ",
        "simeq" to " ≃ ",
        "cong" to " ≅ ",
        "ll" to " ≪ ",
        "gg" to " ≫ ",
        "times" to " × ",
        "cdot" to " · ",
        "cdots" to "⋯",
        "ldots" to "…",
        "dots" to "…",
        "pm" to " ± ",
        "mp" to " ∓ ",
        "in" to " ∈ ",
        "notin" to " ∉ ",
        "subset" to " ⊂ ",
        "subseteq" to " ⊆ ",
        "supset" to " ⊃ ",
        "supseteq" to " ⊇ ",
        "cup" to " ∪ ",
        "cap" to " ∩ ",
        "to" to " → ",
        "rightarrow" to " → ",
        "leftarrow" to " ← ",
        "mapsto" to " ↦ ",
        "Rightarrow" to " ⇒ ",
        "Leftarrow" to " ⇐ ",
        "iff" to " ⇔ ",
        "Leftrightarrow" to " ⇔ ",
        "langle" to "⟨",
        "rangle" to "⟩",
        "lfloor" to "⌊",
        "rfloor" to "⌋",
        "lceil" to "⌈",
        "rceil" to "⌉",
        "perp" to " ⊥ ",
        "ast" to " ∗ ",
        "star" to " ⋆ ",
        "circ" to "∘",
        "oplus" to " ⊕ ",
        "otimes" to " ⊗ ",
        "sum" to "∑",
        "prod" to "∏",
        "int" to "∫",
    )

private val ACCENT_COMBINING_MARKS: Map<String, String> =
    mapOf(
        "tilde" to "\u0303",
        "widetilde" to "\u0303",
        "hat" to "\u0302",
        "widehat" to "\u0302",
        "bar" to "\u0304",
        "overline" to "\u0304",
        "vec" to "\u20D7",
    )

private val LATEX_OPERATORS: Set<String> =
    setOf(
        "log",
        "ln",
        "lg",
        "exp",
        "sin",
        "cos",
        "tan",
        "cot",
        "sec",
        "csc",
        "arcsin",
        "arccos",
        "arctan",
        "sinh",
        "cosh",
        "tanh",
        "min",
        "max",
        "inf",
        "sup",
        "lim",
        "limsup",
        "liminf",
        "arg",
        "det",
        "dim",
        "gcd",
        "hom",
        "ker",
        "deg",
        "Pr",
        "mod",
        "bmod",
        "pmod",
        "poly",
        "polylog",
        "diag",
        "wt",
        "sgn",
        "supp",
        "rank",
        "tr",
    )

private val BLACKBOARD_CHARS: Map<Char, String> =
    mapOf(
        'C' to "ℂ",
        'F' to "𝔽",
        'H' to "ℍ",
        'N' to "ℕ",
        'P' to "ℙ",
        'Q' to "ℚ",
        'R' to "ℝ",
        'Z' to "ℤ",
    )

private val COLLAPSE_SPACES_REGEX = Regex(""" {2,}""")

private interface MathSink {
    fun appendPlain(text: String)

    fun appendItalic(text: String)

    fun appendScript(isSuper: Boolean, block: () -> Unit)

    fun lastChar(): Char?
}

private class PlainMathSink(private val out: StringBuilder) : MathSink {
    override fun appendPlain(text: String) {
        out.append(text)
    }

    override fun appendItalic(text: String) {
        out.append(text)
    }

    override fun appendScript(isSuper: Boolean, block: () -> Unit) {
        out.append(if (isSuper) '^' else '_')
        block()
    }

    override fun lastChar(): Char? = out.lastOrNull()
}

private class AnnotatedMathSink(private val builder: AnnotatedString.Builder) : MathSink {
    private var lastEmitted: Char? = null

    override fun appendPlain(text: String) {
        if (text.isNotEmpty()) {
            builder.append(text)
            lastEmitted = text.last()
        }
    }

    override fun appendItalic(text: String) {
        if (text.isNotEmpty()) {
            val start = builder.length
            builder.append(text)
            builder.addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, builder.length)
            lastEmitted = text.last()
        }
    }

    override fun appendScript(isSuper: Boolean, block: () -> Unit) {
        val start = builder.length
        block()
        val shift = if (isSuper) BaselineShift.Superscript else BaselineShift.Subscript
        builder.addStyle(
            SpanStyle(fontSize = SCRIPT_FONT_SCALE.em, baselineShift = shift),
            start,
            builder.length,
        )
    }

    override fun lastChar(): Char? = lastEmitted
}

/**
 * Strips inline `$...$` LaTeX delimiters into clean Unicode text (safe for currency like `$5 and
 * $10`).
 */
internal fun stripInlineMathDelimiters(text: String): String {
    if ('$' !in text) return text
    val out = StringBuilder(text.length)
    scanMathSegments(text) { segment, isMath ->
        if (isMath) {
            out.append(latexExprToPlainText(segment))
        } else {
            out.append(segment)
        }
    }
    return out.toString()
}

/**
 * Renders inline `$...$` LaTeX segments into a styled Compose [AnnotatedString] with italic math
 * variables, upright operators, Unicode symbols, and super/subscripts.
 */
internal fun formatInlineMathAnnotated(text: String): AnnotatedString {
    if ('$' !in text) return AnnotatedString(text)
    return buildAnnotatedString {
        val sink = AnnotatedMathSink(this)
        scanMathSegments(text) { segment, isMath ->
            if (isMath) {
                emitMathExpr(segment, sink)
            } else {
                append(segment)
            }
        }
    }
}

internal fun latexExprToPlainText(expr: String): String {
    val out = StringBuilder(expr.length)
    emitMathExpr(expr, PlainMathSink(out))
    return COLLAPSE_SPACES_REGEX.replace(out.toString(), " ").trim()
}

private inline fun scanMathSegments(
    text: String,
    emit: (segment: String, isMath: Boolean) -> Unit,
) {
    var cursor = 0
    while (cursor < text.length) {
        val openIdx = text.indexOf('$', cursor)
        if (openIdx < 0) {
            emit(text.substring(cursor), false)
            cursor = text.length
        } else if (isEscapedDollar(text, openIdx, cursor)) {
            if (openIdx - 1 > cursor) {
                emit(text.substring(cursor, openIdx - 1), false)
            }
            emit("$", false)
            cursor = openIdx + 1
        } else {
            if (openIdx > cursor) {
                emit(text.substring(cursor, openIdx), false)
            }
            cursor = consumeDollarToken(text, openIdx, emit)
        }
    }
}

private fun isEscapedDollar(text: String, dollarIdx: Int, minIdx: Int): Boolean {
    var backslashes = 0
    var pos = dollarIdx - 1
    while (pos >= minIdx && text[pos] == '\\') {
        backslashes++
        pos--
    }
    return backslashes % 2 == 1
}

private inline fun consumeDollarToken(
    text: String,
    openIdx: Int,
    emit: (segment: String, isMath: Boolean) -> Unit,
): Int {
    val delimLen = if (openIdx + 1 < text.length && text[openIdx + 1] == '$') 2 else 1
    val closeIdx = findClosingMathDollar(text, openIdx + delimLen, delimLen)
    val expr = closeIdx?.let { text.substring(openIdx + delimLen, it).trim() }
    val validExpr = expr != null && expr.isNotEmpty() && expr.length <= MAX_INLINE_MATH_LEN
    return if (validExpr) {
        emit(expr, true)
        closeIdx + delimLen
    } else {
        emit(text.substring(openIdx, openIdx + delimLen), false)
        openIdx + delimLen
    }
}

private fun findClosingMathDollar(text: String, bodyStart: Int, delimLen: Int): Int? {
    val invalidStart = bodyStart >= text.length || delimLen == 1 && text[bodyStart].isWhitespace()
    if (invalidStart) return null
    var idx = bodyStart
    var result: Int? = null
    while (idx < text.length && result == null) {
        val ch = text[idx]
        if (ch == '\\' && idx + 1 < text.length) {
            idx += 2
        } else if (isClosingDelimAt(text, idx, delimLen)) {
            result = if (isValidMathClosing(text, bodyStart, idx, delimLen)) idx else -1
        } else {
            idx++
        }
    }
    return result?.takeIf { it >= 0 }
}

private fun isClosingDelimAt(text: String, idx: Int, delimLen: Int): Boolean =
    text[idx] == '$' && (delimLen == 1 || idx + 1 < text.length && text[idx + 1] == '$')

private fun isValidMathClosing(
    text: String,
    bodyStart: Int,
    closeIdx: Int,
    delimLen: Int,
): Boolean {
    if (closeIdx <= bodyStart) return false
    if (delimLen == 2) return true
    val prevSpace = text[closeIdx - 1].isWhitespace()
    val nextDigit = closeIdx + 1 < text.length && text[closeIdx + 1].isDigit()
    val inner = text.substring(bodyStart, closeIdx)
    val looksLikeCurrencySpan =
        inner.first().isDigit() &&
            inner.any { it.isWhitespace() } &&
            '\\' !in inner &&
            inner.none { it in MATH_OP_INDICATORS }
    return !prevSpace && !nextDigit && !looksLikeCurrencySpan
}

private fun emitMathExpr(expr: String, sink: MathSink) {
    var i = 0
    while (i < expr.length) {
        val ch = expr[i]
        i =
            when {
                ch == '\\' -> {
                    emitLatexCommand(expr, i, sink)
                }
                ch == '^' || ch == '_' -> {
                    val (arg, next) = readLatexGroupOrChar(expr, i + 1)
                    sink.appendScript(ch == '^') { emitMathExpr(arg, sink) }
                    next
                }
                ch == '{' || ch == '}' -> {
                    i + 1
                }
                ch.isLetter() -> {
                    emitMathLetters(expr, i, sink)
                }
                ch == '-' -> {
                    sink.appendPlain("−")
                    i + 1
                }
                else -> {
                    sink.appendPlain(ch.toString())
                    i + 1
                }
            }
    }
}

private fun emitMathLetters(expr: String, start: Int, sink: MathSink): Int {
    var end = start + 1
    if (expr[start].isAsciiLetter()) {
        while (end < expr.length && expr[end].isAsciiLetter()) end++
    }
    val word = expr.substring(start, end)
    if (word.length > 1 && word in LATEX_OPERATORS) {
        emitNamedOperator(expr, word, end, sink)
    } else {
        sink.appendItalic(word)
    }
    return end
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

private fun emitLatexCommand(expr: String, slashIdx: Int, sink: MathSink): Int {
    val start = slashIdx + 1
    if (start >= expr.length) return expr.length
    if (!expr[start].isLetter()) {
        emitEscapedPunct(expr[start], sink)
        return start + 1
    }
    var end = start
    while (end < expr.length && expr[end].isLetter()) end++
    return dispatchNamedCommand(expr, expr.substring(start, end), end, sink)
}

private fun emitEscapedPunct(esc: Char, sink: MathSink) {
    if (esc in ESCAPED_SPACE_CHARS) {
        sink.appendPlain(" ")
    } else if (esc in ESCAPED_LITERAL_CHARS) {
        sink.appendPlain(esc.toString())
    }
}

private fun dispatchNamedCommand(expr: String, cmd: String, end: Int, sink: MathSink): Int =
    when {
        cmd == CMD_MATHBB -> {
            val (arg, next) = readLatexGroupOrChar(expr, end)
            sink.appendPlain(arg.map { BLACKBOARD_CHARS[it] ?: it.toString() }.joinToString(""))
            next
        }
        cmd in FRAC_COMMANDS -> {
            emitFraction(expr, end, sink)
        }
        cmd == CMD_SQRT -> {
            val (arg, next) = readLatexGroupOrChar(expr, end)
            sink.appendPlain("√(")
            emitMathExpr(arg, sink)
            sink.appendPlain(")")
            next
        }
        ACCENT_COMBINING_MARKS.containsKey(cmd) -> {
            val (arg, next) = readLatexGroupOrChar(expr, end)
            emitMathExpr(arg, sink)
            sink.appendPlain(ACCENT_COMBINING_MARKS[cmd].orEmpty())
            next
        }
        cmd in LATEX_OPERATORS -> {
            emitNamedOperator(expr, cmd, end, sink)
            end
        }
        LATEX_SYMBOLS.containsKey(cmd) -> {
            sink.appendPlain(LATEX_SYMBOLS[cmd].orEmpty())
            end
        }
        else -> {
            emitFormattingOrSpacingCommand(expr, cmd, end, sink)
        }
    }

private fun emitFraction(expr: String, end: Int, sink: MathSink): Int {
    val (num, afterNum) = readLatexGroupOrChar(expr, end)
    val (den, afterDen) = readLatexGroupOrChar(expr, afterNum)
    emitMathExpr(num, sink)
    sink.appendPlain("/")
    emitMathExpr(den, sink)
    return afterDen
}

private fun emitNamedOperator(expr: String, cmd: String, end: Int, sink: MathSink) {
    val prev = sink.lastChar()
    if (prev != null && !prev.isWhitespace() && prev !in OPEN_GROUP_CHARS) {
        sink.appendPlain(" ")
    }
    sink.appendPlain(cmd)
    if (end < expr.length && (expr[end].isLetterOrDigit() || expr[end] == '\\')) {
        sink.appendPlain(" ")
    }
}

private fun emitFormattingOrSpacingCommand(
    expr: String,
    cmd: String,
    end: Int,
    sink: MathSink,
): Int =
    when {
        cmd in UPRIGHT_TEXT_COMMANDS -> {
            val (arg, next) = readLatexGroupOrChar(expr, end)
            sink.appendPlain(latexExprToPlainText(arg))
            next
        }
        cmd in ITALIC_TEXT_COMMANDS -> {
            val (arg, next) = readLatexGroupOrChar(expr, end)
            sink.appendItalic(latexExprToPlainText(arg))
            next
        }
        cmd in SPACING_DELIM_COMMANDS -> {
            if (cmd == CMD_QUAD || cmd == CMD_QQUAD) {
                sink.appendPlain(" ")
            }
            end
        }
        else -> {
            end
        }
    }

private fun readLatexGroupOrChar(expr: String, startIdx: Int): Pair<String, Int> {
    var i = startIdx
    while (i < expr.length && expr[i].isWhitespace()) i++
    return when {
        i >= expr.length -> {
            "" to i
        }
        expr[i] != '{' -> {
            expr[i].toString() to i + 1
        }
        else -> {
            readBracedGroup(expr, i)
        }
    }
}

private fun readBracedGroup(expr: String, openBraceIdx: Int): Pair<String, Int> {
    var i = openBraceIdx
    var depth = 0
    val contentStart = openBraceIdx + 1
    var closeIdx = -1
    while (i < expr.length && closeIdx < 0) {
        if (expr[i] == '{') {
            depth++
        } else if (expr[i] == '}') {
            depth--
            if (depth == 0) {
                closeIdx = i
            }
        }
        i++
    }
    return if (closeIdx >= 0) {
        expr.substring(contentStart, closeIdx) to closeIdx + 1
    } else {
        expr.substring(contentStart) to expr.length
    }
}

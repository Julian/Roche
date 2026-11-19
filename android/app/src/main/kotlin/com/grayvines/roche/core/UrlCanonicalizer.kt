@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package com.grayvines.roche.core

import java.net.IDN
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale

private const val DEFAULT_HTTP_PORT = 80
private const val DEFAULT_HTTPS_PORT = 443
private const val MAX_URL_PORT = 65_535
private const val HEX_UPPER = "0123456789ABCDEF"
private const val URL_USERINFO_SAFE = "-._~!$&'()*+,;=:"
private const val URL_PATH_SAFE = "-._~!$&'()*+,;=:@"
private const val URL_QUERY_FRAGMENT_SAFE = "-._~!$&'()*+,;=:@/?%"
private const val MISSING_DELIM = "\u0000"
private const val URL_SCHEME_SEP_LEN = 3
private const val PERCENT_TRIPLET_LEN = 3
private const val ASCII_PRINTABLE_MIN = 0x21
private const val ASCII_PRINTABLE_MAX = 0x7E
private const val BYTE_MASK = 0xFF
private const val HEX_NIBBLE_SHIFT = 4
private const val HEX_LOW_NIBBLE_MASK = 0x0F

internal data class SplitHierarchicalUrl(
    val scheme: String,
    val userInfo: String?,
    val host: String,
    val port: Int,
    val rawPath: String,
    val rawQuery: String?,
    val rawFragment: String?,
)

internal fun canonicalizeSyncUrl(raw: String): String {
    val trimmed = raw.trim().filterNot { it == '\t' || it == '\r' || it == '\n' }
    if (trimmed.isEmpty()) return ""
    return canonicalizeHierarchicalUrl(trimmed) ?: trimmed.lowercase(Locale.ROOT)
}

internal fun splitHierarchicalUrl(trimmed: String): SplitHierarchicalUrl? {
    val sepIdx = trimmed.indexOf("://")
    if (sepIdx <= 0) return null
    val scheme = trimmed.substring(0, sepIdx).lowercase(Locale.ROOT)
    val validScheme =
        scheme[0] in 'a'..'z' &&
            scheme.all { it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '-' || it == '.' }
    if (!validScheme) return null

    val afterScheme = trimmed.substring(sepIdx + URL_SCHEME_SEP_LEN)
    val withoutFrag = afterScheme.substringBefore('#')
    val rawFragment =
        afterScheme.substringAfter('#', missingDelimiterValue = MISSING_DELIM).takeIf {
            it != MISSING_DELIM
        }
    val withoutQuery = withoutFrag.substringBefore('?')
    val rawQuery =
        withoutFrag.substringAfter('?', missingDelimiterValue = MISSING_DELIM).takeIf {
            it != MISSING_DELIM
        }
    val slashIdx = withoutQuery.indexOf('/')
    val authority = if (slashIdx == -1) withoutQuery else withoutQuery.substring(0, slashIdx)
    val rawPath = if (slashIdx == -1) "" else withoutQuery.substring(slashIdx)
    val (userInfo, host, port) = parseUrlAuthority(authority) ?: return null
    return SplitHierarchicalUrl(scheme, userInfo, host, port, rawPath, rawQuery, rawFragment)
}

private fun canonicalizeHierarchicalUrl(trimmed: String): String? {
    val split = splitHierarchicalUrl(trimmed) ?: return null
    val isWebScheme =
        split.scheme == SCHEME_HTTP ||
            split.scheme == SCHEME_HTTPS ||
            split.scheme == "ws" ||
            split.scheme == "wss"
    val defaultPort =
        when (split.scheme) {
            SCHEME_HTTP,
            "ws" -> DEFAULT_HTTP_PORT
            SCHEME_HTTPS,
            "wss" -> DEFAULT_HTTPS_PORT
            else -> -1
        }
    val portPart = if (split.port != -1 && split.port != defaultPort) ":${split.port}" else ""
    val userInfoPart =
        split.userInfo?.let { "${percentEncodeUrlText(it, URL_USERINFO_SAFE)}@" }.orEmpty()
    val normalizedPath = normalizeUrlPath(split.rawPath, defaultSlashIfEmpty = isWebScheme)
    val queryPart =
        split.rawQuery?.let { "?${percentEncodeUrlText(it, URL_QUERY_FRAGMENT_SAFE)}" }.orEmpty()
    val fragmentPart =
        split.rawFragment?.let { "#${percentEncodeUrlText(it, URL_QUERY_FRAGMENT_SAFE)}" }.orEmpty()
    return "${split.scheme}://$userInfoPart${split.host}$portPart$normalizedPath$queryPart$fragmentPart"
}

private fun parseUrlAuthority(authority: String): Triple<String?, String, Int>? {
    if (authority.isEmpty()) return null
    val atIdx = authority.lastIndexOf('@')
    val userInfo = if (atIdx != -1) authority.substring(0, atIdx) else null
    val hostPort = if (atIdx != -1) authority.substring(atIdx + 1) else authority
    val (rawHost, portStr) = splitHostAndPort(hostPort) ?: return null
    val port =
        if (portStr == null) {
            -1
        } else {
            portStr.toIntOrNull()?.takeIf { it in 1..MAX_URL_PORT } ?: return null
        }
    val asciiHost = canonicalizeAuthorityHost(rawHost) ?: return null
    return Triple(userInfo, asciiHost, port)
}

private fun splitHostAndPort(hostPort: String): Pair<String, String?>? {
    if (hostPort.isEmpty()) return null
    return if (hostPort.startsWith("[")) {
        val closeIdx = hostPort.indexOf(']')
        val rest = if (closeIdx > 1) hostPort.substring(closeIdx + 1) else null
        when {
            rest == null -> null
            rest.isEmpty() -> hostPort.substring(0, closeIdx + 1) to null
            rest.startsWith(":") && rest.length > 1 ->
                hostPort.substring(0, closeIdx + 1) to rest.substring(1)
            else -> null
        }
    } else {
        val colonIdx = hostPort.lastIndexOf(':')
        if (colonIdx == -1) {
            hostPort to null
        } else {
            hostPort.substring(0, colonIdx) to hostPort.substring(colonIdx + 1)
        }
    }
}

private fun canonicalizeAuthorityHost(rawHost: String): String? =
    when {
        rawHost.isEmpty() -> {
            null
        }
        rawHost.startsWith("[") && rawHost.endsWith("]") -> {
            val lower = rawHost.lowercase(Locale.ROOT)
            runCatching { URI("http://$lower").host?.lowercase(Locale.ROOT) }.getOrNull()
        }
        else -> {
            runCatching { IDN.toASCII(rawHost, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() && it.none { ch -> ch <= ' ' || ch in "/?#@\\[]" } }
        }
    }

private fun normalizeUrlPath(rawPath: String, defaultSlashIfEmpty: Boolean): String {
    if (rawPath.isEmpty()) return if (defaultSlashIfEmpty) "/" else ""
    val out = ArrayList<String>()
    val rawSegments = rawPath.removePrefix("/").split('/')
    var trailingSlash = rawPath.endsWith("/")
    for ((idx, seg) in rawSegments.withIndex()) {
        trailingSlash = applyUrlPathSegment(out, seg, idx == rawSegments.lastIndex, trailingSlash)
    }
    return formatNormalizedPath(out, trailingSlash)
}

private fun applyUrlPathSegment(
    out: MutableList<String>,
    seg: String,
    isLast: Boolean,
    currentTrailingSlash: Boolean,
): Boolean =
    when {
        seg.isEmpty() -> {
            currentTrailingSlash
        }
        isSingleDotSegment(seg) -> {
            currentTrailingSlash || isLast
        }
        isDoubleDotSegment(seg) -> {
            if (out.isNotEmpty()) out.removeAt(out.lastIndex)
            currentTrailingSlash || isLast
        }
        else -> {
            out.add(percentEncodeUrlText(seg, URL_PATH_SAFE))
            currentTrailingSlash
        }
    }

private fun formatNormalizedPath(segments: List<String>, trailingSlash: Boolean): String =
    if (segments.isEmpty()) {
        "/"
    } else {
        buildString {
            append('/')
            append(segments.joinToString("/"))
            if (trailingSlash) append('/')
        }
    }

private fun isSingleDotSegment(seg: String): Boolean =
    seg == "." || seg.equals("%2e", ignoreCase = true)

private fun isDoubleDotSegment(seg: String): Boolean =
    seg == ".." ||
        seg.equals(".%2e", ignoreCase = true) ||
        seg.equals("%2e.", ignoreCase = true) ||
        seg.equals("%2e%2e", ignoreCase = true)

private fun percentEncodeUrlText(input: String, safeAsciiChars: String): String = buildString {
    var i = 0
    while (i < input.length) {
        if (isPercentTripletAt(input, i)) {
            append(input, i, i + PERCENT_TRIPLET_LEN)
            i += PERCENT_TRIPLET_LEN
        } else {
            val cp = input.codePointAt(i)
            appendEncodedCodePoint(cp, safeAsciiChars)
            i += Character.charCount(cp)
        }
    }
}

private fun StringBuilder.appendEncodedCodePoint(cp: Int, safeAsciiChars: String) {
    val ch = cp.toChar()
    if (
        cp in ASCII_PRINTABLE_MIN..ASCII_PRINTABLE_MAX &&
            (ch.isLetterOrDigit() || ch in safeAsciiChars)
    ) {
        append(ch)
        return
    }
    for (b in String(Character.toChars(cp)).toByteArray(StandardCharsets.UTF_8)) {
        val unsigned = b.toInt() and BYTE_MASK
        append('%')
        append(HEX_UPPER[unsigned ushr HEX_NIBBLE_SHIFT])
        append(HEX_UPPER[unsigned and HEX_LOW_NIBBLE_MASK])
    }
}

private fun isPercentTripletAt(text: String, idx: Int): Boolean =
    text[idx] == '%' &&
        idx + 2 < text.length &&
        isAsciiHexDigit(text[idx + 1]) &&
        isAsciiHexDigit(text[idx + 2])

private fun isAsciiHexDigit(ch: Char): Boolean = ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

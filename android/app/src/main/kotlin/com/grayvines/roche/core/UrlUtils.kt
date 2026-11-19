package com.grayvines.roche.core

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal const val SCHEME_HTTP = "http"
internal const val SCHEME_HTTPS = "https"
internal const val HTTP_URL_PREFIX = "http://"
internal const val HTTPS_URL_PREFIX = "https://"
internal const val LOCAL_PDF_SCHEME = "localpdf://"

internal const val ROCHE_USER_AGENT = "Roche/1.0 (+https://github.com/Julian/Roche)"
internal const val EXTERNAL_BROWSER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36 Roche/1.0"

internal val PDF_MAGIC_BYTES: ByteArray = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)

private const val PERCENT_TRIPLET_BYTES = 3
private const val HEX_NIBBLE_BITS = 4
private const val HEX_RADIX = 16

internal fun isHttpOrHttpsUrl(raw: String?): Boolean {
    val trimmed = raw?.trim().orEmpty()
    return trimmed.startsWith(HTTPS_URL_PREFIX, ignoreCase = true) ||
        trimmed.startsWith(HTTP_URL_PREFIX, ignoreCase = true)
}

internal fun isHttpsUrl(raw: String?): Boolean =
    raw?.trim()?.startsWith(HTTPS_URL_PREFIX, ignoreCase = true) == true

internal fun sanitizeHttpUrl(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    val lower = trimmed.lowercase(Locale.ROOT)
    return if (lower.startsWith(HTTPS_URL_PREFIX) || lower.startsWith(HTTP_URL_PREFIX)) {
        trimmed
    } else {
        null
    }
}

internal fun isLocalPdfUrl(raw: String?): Boolean =
    raw?.trim()?.startsWith(LOCAL_PDF_SCHEME, ignoreCase = true) == true

internal fun hasPdfMagicHeader(bytes: ByteArray, length: Int = bytes.size): Boolean {
    if (length < PDF_MAGIC_BYTES.size || bytes.size < PDF_MAGIC_BYTES.size) return false
    for (i in PDF_MAGIC_BYTES.indices) {
        if (bytes[i] != PDF_MAGIC_BYTES[i]) return false
    }
    return true
}

internal fun encodeQueryValue(raw: String): String =
    URLEncoder.encode(raw, StandardCharsets.UTF_8.name())

internal fun extractQueryParam(rawQuery: String?, key: String): String? {
    if (rawQuery.isNullOrBlank()) return null
    val prefix = "$key="
    return rawQuery
        .split('&')
        .firstOrNull { it.startsWith(prefix) }
        ?.removePrefix(prefix)
        ?.let(::decodePercentUtf8)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
}

internal fun decodePercentUtf8(input: String): String {
    if ('%' !in input) return input
    val rawBytes = input.toByteArray(StandardCharsets.UTF_8)
    val out = ByteArrayOutputStream(rawBytes.size)
    var i = 0
    while (i < rawBytes.size) {
        val hi =
            if (rawBytes[i] == '%'.code.toByte() && i + 2 < rawBytes.size) {
                Character.digit(rawBytes[i + 1].toInt().toChar(), HEX_RADIX)
            } else {
                -1
            }
        val lo = if (hi >= 0) Character.digit(rawBytes[i + 2].toInt().toChar(), HEX_RADIX) else -1
        if (hi >= 0 && lo >= 0) {
            out.write(hi shl HEX_NIBBLE_BITS or lo)
            i += PERCENT_TRIPLET_BYTES
        } else {
            out.write(rawBytes[i].toInt())
            i += 1
        }
    }
    return out.toByteArray().toString(StandardCharsets.UTF_8)
}

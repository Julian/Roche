package com.grayvines.roche.data.remote

import com.grayvines.roche.core.EXTERNAL_BROWSER_USER_AGENT
import com.grayvines.roche.core.runSuspendCatching
import java.io.File

/**
 * Dedicated client for all untrusted public-internet requests (RSS/Atom feeds, article HTML,
 * external thumbnails/favicons, podcast enclosures, and public catalog metadata).
 *
 * Uses [PinnedPublicHttpTransport] by default so every hop validates DNS and pins the socket
 * directly to the validated public IP without ever sharing the Tailnet server [HttpEngine] path.
 */
class PublicWebFetcher(internal val transport: HttpTransport = PinnedPublicHttpTransport()) {
    internal suspend fun fetchFeedXml(feedUrl: String): Result<String> = runSuspendCatching {
        val trimmed = feedUrl.trim()
        PublicUrlValidator.ensurePublicUrlSyntax(trimmed)
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_GET_PUBLIC,
                    url = trimmed,
                    headers =
                        mapOf(
                            HEADER_USER_AGENT to EXTERNAL_BROWSER_USER_AGENT,
                            HEADER_ACCEPT to PUBLIC_FEED_ACCEPT,
                        ),
                    requirePublicIp = true,
                    maxResponseBytes = MAX_PUBLIC_FEED_BYTES,
                )
            )
        ensureExternalSuccess(response)
        response.body
    }

    internal suspend fun fetchHtml(pageUrl: String): Result<String> = runSuspendCatching {
        val trimmed = pageUrl.trim()
        PublicUrlValidator.ensurePublicUrlSyntax(trimmed)
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_GET_PUBLIC,
                    url = trimmed,
                    headers =
                        mapOf(
                            HEADER_USER_AGENT to EXTERNAL_BROWSER_USER_AGENT,
                            HEADER_ACCEPT to PUBLIC_HTML_ACCEPT,
                        ),
                    requirePublicIp = true,
                    maxResponseBytes = MAX_PUBLIC_HTML_BYTES,
                )
            )
        ensureExternalSuccess(response)
        response.body
    }

    internal suspend fun fetchJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Result<String> = runSuspendCatching {
        val trimmed = url.trim()
        PublicUrlValidator.ensurePublicUrlSyntax(trimmed)
        val mergedHeaders = buildMap {
            put(HEADER_USER_AGENT, EXTERNAL_BROWSER_USER_AGENT)
            put(HEADER_ACCEPT, PUBLIC_JSON_ACCEPT)
            putAll(headers)
        }
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_GET_PUBLIC,
                    url = trimmed,
                    headers = mergedHeaders,
                    requirePublicIp = true,
                    maxResponseBytes = MAX_PUBLIC_JSON_BYTES,
                )
            )
        ensureExternalSuccess(response)
        response.body
    }

    internal suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): Result<String> = runSuspendCatching {
        val trimmed = url.trim()
        PublicUrlValidator.ensurePublicUrlSyntax(trimmed)
        val mergedHeaders = buildMap {
            put(HEADER_USER_AGENT, EXTERNAL_BROWSER_USER_AGENT)
            put(HEADER_ACCEPT, PUBLIC_JSON_ACCEPT)
            putAll(headers)
        }
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_POST_PUBLIC,
                    url = trimmed,
                    headers = mergedHeaders,
                    body = body,
                    requirePublicIp = true,
                    maxResponseBytes = MAX_PUBLIC_JSON_BYTES,
                )
            )
        ensureExternalSuccess(response)
        response.body
    }

    internal suspend fun fetchImageBytes(
        imageUrl: String,
        maxBytes: Int = MAX_MEDIA_BYTES,
    ): ByteArray? = runSuspendCatching {
        val trimmed = imageUrl.trim()
        PublicUrlValidator.ensurePublicUrlSyntax(trimmed)
        val boundedMax = maxBytes.coerceIn(1, MAX_MEDIA_BYTES)
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_GET_PUBLIC,
                    url = trimmed,
                    headers =
                        mapOf(
                            HEADER_USER_AGENT to EXTERNAL_BROWSER_USER_AGENT,
                            HEADER_ACCEPT to PUBLIC_IMAGE_ACCEPT,
                        ),
                    expectBinary = true,
                    requirePublicIp = true,
                    maxResponseBytes = boundedMax,
                )
            )
        if (response.statusCode !in HTTP_OK_START..HTTP_OK_END) return null
        val bytes = response.bodyBytes ?: response.body.toByteArray(Charsets.UTF_8)
        bytes.takeIf { it.size in 1..boundedMax }
    }
        .getOrNull()

    internal suspend fun fetchPdfBytes(
        pdfUrl: String,
        maxBytes: Int = MAX_PUBLIC_PDF_BYTES,
    ): ByteArray? = runSuspendCatching {
        val trimmed = pdfUrl.trim()
        PublicUrlValidator.ensurePublicUrlSyntax(trimmed)
        val boundedMax = maxBytes.coerceIn(1, MAX_PUBLIC_PDF_BYTES)
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_GET_PUBLIC,
                    url = trimmed,
                    headers =
                        mapOf(
                            HEADER_USER_AGENT to EXTERNAL_BROWSER_USER_AGENT,
                            HEADER_ACCEPT to PUBLIC_PDF_ACCEPT,
                        ),
                    expectBinary = true,
                    requirePublicIp = true,
                    maxResponseBytes = boundedMax,
                )
            )
        val bytes =
            if (response.statusCode in HTTP_OK_START..HTTP_OK_END) {
                response.bodyBytes ?: response.body.toByteArray(Charsets.ISO_8859_1)
            } else {
                null
            }
        bytes?.takeIf { it.size in MIN_PDF_BYTES..boundedMax && hasPdfMagicHeader(it) }
    }
        .getOrNull()

    internal suspend fun streamBinaryToFile(
        fileUrl: String,
        targetFile: File,
        maxBytes: Long = MAX_AUDIO_ENCLOSURE_BYTES,
    ): Boolean = runSuspendCatching {
        val trimmed = fileUrl.trim()
        PublicUrlValidator.ensurePublicUrlSyntax(trimmed)
        transport.streamToFile(
            request =
                HttpRequest(
                    method = METHOD_GET_PUBLIC,
                    url = trimmed,
                    headers = mapOf(HEADER_USER_AGENT to EXTERNAL_BROWSER_USER_AGENT),
                    expectBinary = true,
                    requirePublicIp = true,
                ),
            targetFile = targetFile,
            maxBytes = maxBytes,
        )
    }
        .getOrDefault(false)
}

internal fun hasPdfMagicHeader(bytes: ByteArray): Boolean {
    val limit = minOf(bytes.size - PDF_MAGIC.size, PDF_MAGIC_SCAN_LIMIT)
    if (limit < 0) return false
    for (offset in 0..limit) {
        var matched = true
        for (idx in PDF_MAGIC.indices) {
            if (bytes[offset + idx] != PDF_MAGIC[idx]) {
                matched = false
                break
            }
        }
        if (matched) return true
    }
    return false
}

private const val METHOD_GET_PUBLIC = "GET"
private const val METHOD_POST_PUBLIC = "POST"
internal const val MAX_PUBLIC_FEED_BYTES = 10 * 1024 * 1024
internal const val MAX_PUBLIC_HTML_BYTES = 5 * 1024 * 1024
internal const val MAX_PUBLIC_JSON_BYTES = 5 * 1024 * 1024
internal const val MAX_PUBLIC_PDF_BYTES = 25 * 1024 * 1024
private const val MIN_PDF_BYTES = 5
private const val PDF_MAGIC_SCAN_LIMIT = 1024
private val PDF_MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)
private const val PUBLIC_FEED_ACCEPT =
    "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.5"
private const val PUBLIC_HTML_ACCEPT =
    "text/html, application/xhtml+xml, application/xml;q=0.9, */*;q=0.8"
private const val PUBLIC_JSON_ACCEPT = "application/json, */*;q=0.5"
private const val PUBLIC_IMAGE_ACCEPT =
    "image/avif,image/webp,image/png,image/jpeg,image/*,*/*;q=0.8"
private const val PUBLIC_PDF_ACCEPT = "application/pdf, application/octet-stream;q=0.8, */*;q=0.5"

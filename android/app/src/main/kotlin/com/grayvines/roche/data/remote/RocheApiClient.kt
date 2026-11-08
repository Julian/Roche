package com.grayvines.roche.data.remote

import com.grayvines.roche.data.settings.tailscaleDiscoveryUrls
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val expectBinary: Boolean = false,
)

@Suppress("ArrayInDataClass")
data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
    val bodyBytes: ByteArray? = null,
)

fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

class HttpUrlConnectionTransport : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse =
        withContext(Dispatchers.IO) { executeFollowingRedirects(request) }

    private fun executeFollowingRedirects(initialRequest: HttpRequest): HttpResponse {
        var currentRequest = initialRequest
        repeat(MAX_REDIRECTS + 1) {
            val (response, redirectLocation) = executeSingleRequest(currentRequest)
            val nextUrl = redirectLocation?.let { resolveRedirectUrl(currentRequest.url, it) }
            if (nextUrl == null) {
                return response
            }
            currentRequest = buildRedirectedRequest(currentRequest, response.statusCode, nextUrl)
        }
        return executeSingleRequest(currentRequest).first
    }

    private fun buildRedirectedRequest(
        currentRequest: HttpRequest,
        statusCode: Int,
        nextUrl: String,
    ): HttpRequest {
        val switchToGet =
            statusCode in REDIRECT_SWITCH_TO_GET_CODES &&
                currentRequest.method != "GET" &&
                currentRequest.method != "HEAD"
        val nextHeaders =
            if (isSameOriginHost(currentRequest.url, nextUrl)) {
                currentRequest.headers
            } else {
                currentRequest.headers.filterKeys { !it.equals("Authorization", ignoreCase = true) }
            }
        return currentRequest.copy(
            method = if (switchToGet) "GET" else currentRequest.method,
            url = nextUrl,
            headers = nextHeaders,
            body = if (switchToGet) null else currentRequest.body,
        )
    }

    private fun executeSingleRequest(request: HttpRequest): Pair<HttpResponse, String?> {
        val connection =
            (URI.create(request.url).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = request.method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                request.headers.forEach { (key, value) -> setRequestProperty(key, value) }
                if (request.body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { out ->
                        out.write(request.body.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        try {
            val status = connection.responseCode
            if (status in REDIRECT_STATUS_CODES) {
                val location =
                    connection.getHeaderField("Location")?.trim()?.takeIf { it.isNotBlank() }
                if (location != null) {
                    return HttpResponse(statusCode = status, body = "") to location
                }
            }
            val isSuccess = status in HTTP_OK_START..HTTP_OK_END
            val stream = if (isSuccess) connection.inputStream else connection.errorStream
            val maxBytes =
                if (request.expectBinary && isSuccess) MAX_MEDIA_BYTES else MAX_RESPONSE_CHARS
            val bytes = stream?.let { input -> readBoundedBytes(input, maxBytes) } ?: ByteArray(0)
            val text =
                if (request.expectBinary && isSuccess) {
                    ""
                } else {
                    bytes.toString(Charsets.UTF_8)
                }
            return HttpResponse(statusCode = status, body = text, bodyBytes = bytes) to null
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        private val REDIRECT_SWITCH_TO_GET_CODES = setOf(301, 302, 303)
    }
}

internal fun resolveRedirectUrl(baseUrl: String, location: String): String? = runCatching {
    val resolved = URI.create(baseUrl).resolve(location.trim()).toString()
    resolved.takeIf {
        it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
    }
}
    .getOrNull()

private fun isSameOriginHost(firstUrl: String, secondUrl: String): Boolean = runCatching {
    val u1 = URI.create(firstUrl)
    val u2 = URI.create(secondUrl)
    u1.host.equals(u2.host, ignoreCase = true) && u1.port == u2.port
}
    .getOrDefault(false)

internal fun readBoundedBytes(
    input: java.io.InputStream,
    maxBytes: Int = MAX_MEDIA_BYTES,
): ByteArray = input.use { stream ->
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_CHARS)
    var total = 0
    while (true) {
        val remainingAllowed = maxBytes - total + 1
        val read = stream.read(buffer, 0, minOf(buffer.size, remainingAllowed))
        if (read == -1) break
        total += read
        if (total > maxBytes) {
            throw IOException("Response exceeds maximum allowed size of $maxBytes bytes")
        }
        out.write(buffer, 0, read)
    }
    out.toByteArray()
}

internal fun readBoundedText(input: java.io.InputStream): String =
    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
        val builder = StringBuilder()
        val buffer = CharArray(READ_BUFFER_CHARS)
        var total = 0
        while (total < MAX_RESPONSE_CHARS) {
            val read = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS - total))
            if (read == -1) break
            builder.append(buffer, 0, read)
            total += read
        }
        builder.toString()
    }

private const val READ_BUFFER_CHARS = 8_192
private const val MAX_RESPONSE_CHARS = 16 * 1024 * 1024
private const val MAX_MEDIA_BYTES = 50 * 1024 * 1024

class ProblemDetailsException(val statusCode: Int, val title: String, val detail: String) :
    Exception("$title ($statusCode): $detail")

data class RemoteFeedDto(
    val id: String,
    val title: String,
    val url: String,
    val siteUrl: String?,
    val feedType: String,
    val unreadCount: Int,
)

data class RemoteItemDto(
    val id: String,
    val feedId: String?,
    val feedTitle: String?,
    val title: String,
    val url: String,
    val author: String?,
    val summary: String?,
    val contentText: String?,
    val contentHtml: String?,
    val contentType: String,
    val status: String,
    val isFavorite: Boolean,
    val publishedAt: String?,
    val createdAt: String,
    val readProgress: Double,
    val thumbnailMediaId: String?,
    val duration: String?,
    val tagNames: List<String> = emptyList(),
    val readAt: String? = null,
    val thumbnailUrl: String? = null,
)

data class RemoteTagDto(val id: String, val name: String, val unreadCount: Int)

data class RemoteMediaDto(
    val id: String,
    val itemId: String,
    val mediaType: String,
    val fileSize: Long? = null,
    val mimeType: String? = null,
)

data class DiscoveredServerDto(
    val probedUrl: String,
    val canonicalUrl: String? = null,
    val version: String? = null,
) {
    val preferredUrl: String
        get() = canonicalUrl?.takeIf { it.isNotBlank() } ?: probedUrl
}

class RocheApiClient(private val transport: HttpTransport) {

    internal suspend fun probeServerHealth(candidateUrl: String): DiscoveredServerDto? =
        runCatching {
            val base = candidateUrl.trim().trimEnd('/')
            if (base.isBlank()) return null
            val response =
                transport.execute(
                    HttpRequest(
                        method = METHOD_GET,
                        url = "$base/health",
                    )
                )
            if (response.statusCode !in HTTP_OK_START..HTTP_OK_END) return null
            parseHealthDiscoveryJson(raw = response.body, probedBaseUrl = base)
        }
        .getOrNull()

    suspend fun discoverServer(
        tailnetDomains: List<String> = emptyList(),
        extraCandidates: List<String> = emptyList(),
    ): DiscoveredServerDto? {
        val candidates =
            (extraCandidates + tailscaleDiscoveryUrls(tailnetDomains = tailnetDomains))
                .map { it.trim().trimEnd('/') }
                .filter { it.isNotBlank() }
                .distinct()
        for (candidate in candidates) {
            val discovered = probeServerHealth(candidate)
            if (discovered != null) return discovered
        }
        return null
    }

    suspend fun pairDevice(serverUrl: String, code: String, deviceName: String): String {
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_POST,
                    url = "${serverUrl.trimEnd('/')}/auth/pair",
                    body = buildPairDevicePayload(code = code, deviceName = deviceName),
                )
            )
        ensureSuccess(response)
        return extractTokenFromPairResponse(response.body)
    }

    suspend fun fetchFeeds(serverUrl: String, token: String): List<RemoteFeedDto> {
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_GET,
                    url = "${serverUrl.trimEnd('/')}/api/v1/feeds",
                    headers = authHeaders(token),
                )
            )
        ensureSuccess(response)
        return parseFeedsJsonArray(response.body)
    }

    internal suspend fun createFeed(
        serverUrl: String,
        token: String,
        url: String,
        title: String? = null,
    ): RemoteFeedDto {
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_POST,
                    url = "${serverUrl.trimEnd('/')}/api/v1/feeds",
                    headers = authHeaders(token),
                    body = buildCreateFeedPayload(url = url, title = title),
                )
            )
        ensureSuccess(response)
        return parseFeedJson(response.body)
    }

    suspend fun refreshFeed(
        serverUrl: String,
        token: String,
        feedId: String,
    ): RemoteFeedDto {
        val encodedId = encodePathSegment(feedId)
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_POST,
                    url = "${serverUrl.trimEnd('/')}/api/v1/feeds/$encodedId/refresh",
                    headers = authHeaders(token),
                )
            )
        ensureSuccess(response)
        return parseFeedJson(response.body)
    }

    suspend fun deleteFeed(
        serverUrl: String,
        token: String,
        feedId: String,
    ) {
        val encodedId = encodePathSegment(feedId)
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_DELETE,
                    url = "${serverUrl.trimEnd('/')}/api/v1/feeds/$encodedId",
                    headers = authHeaders(token),
                )
            )
        ensureSuccess(response)
    }

    suspend fun fetchItems(serverUrl: String, token: String): List<RemoteItemDto> {
        val baseUrl = serverUrl.trimEnd('/')
        val headers = authHeaders(token)
        val collected = LinkedHashMap<String, RemoteItemDto>()
        for (page in 1..MAX_SYNC_PAGES) {
            val url =
                if (page == 1) {
                    "$baseUrl/api/v1/items?per_page=$ITEMS_PER_PAGE"
                } else {
                    "$baseUrl/api/v1/items?per_page=$ITEMS_PER_PAGE&page=$page"
                }
            val response =
                transport.execute(
                    HttpRequest(
                        method = METHOD_GET,
                        url = url,
                        headers = headers,
                    )
                )
            ensureSuccess(response)
            val pageItems = parseItemsEnvelopeJson(response.body)
            val beforeSize = collected.size
            pageItems.forEach { item ->
                if (item.id.isNotBlank() && item.id !in collected) {
                    collected[item.id] = item
                }
            }
            if (pageItems.size < ITEMS_PER_PAGE || collected.size == beforeSize) break
        }
        return collected.values.toList()
    }

    internal suspend fun fetchItemDetail(
        serverUrl: String,
        token: String,
        itemId: String,
    ): RemoteItemDto {
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_GET,
                    url = itemEndpointUrl(serverUrl, itemId),
                    headers = authHeaders(token),
                )
            )
        ensureSuccess(response)
        return parseItemJson(response.body)
    }

    internal suspend fun fetchItemMedia(
        serverUrl: String,
        token: String,
        itemId: String,
    ): List<RemoteMediaDto> {
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_GET,
                    url = "${itemEndpointUrl(serverUrl, itemId)}/media",
                    headers = authHeaders(token),
                )
            )
        ensureSuccess(response)
        return parseMediaJsonArray(response.body)
    }

    internal suspend fun fetchMediaBytes(
        serverUrl: String,
        token: String,
        mediaId: String,
    ): ByteArray? = runCatching {
        val encodedId = encodePathSegment(mediaId)
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_GET,
                    url = "${serverUrl.trimEnd('/')}/api/v1/media/$encodedId",
                    headers = authHeaders(token),
                    expectBinary = true,
                )
            )
        if (response.statusCode !in HTTP_OK_START..HTTP_OK_END) return null
        val bytes = response.bodyBytes ?: response.body.toByteArray(Charsets.UTF_8)
        bytes.takeIf { it.size in 1..MAX_MEDIA_BYTES }
    }
        .onFailure { if (it is CancellationException) throw it }
        .getOrNull()

    internal suspend fun fetchExternalImageBytes(imageUrl: String): ByteArray? = runCatching {
        val trimmed = imageUrl.trim()
        if (
            !trimmed.startsWith("https://", ignoreCase = true) &&
                !trimmed.startsWith("http://", ignoreCase = true)
        ) {
            return null
        }
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_GET,
                    url = trimmed,
                    headers =
                        mapOf(
                            "User-Agent" to EXTERNAL_IMAGE_USER_AGENT,
                            "Accept" to EXTERNAL_IMAGE_ACCEPT,
                        ),
                    expectBinary = true,
                )
            )
        if (response.statusCode !in HTTP_OK_START..HTTP_OK_END) return null
        val bytes = response.bodyBytes ?: response.body.toByteArray(Charsets.UTF_8)
        bytes.takeIf { it.size in 1..MAX_MEDIA_BYTES }
    }
        .onFailure { if (it is CancellationException) throw it }
        .getOrNull()

    suspend fun saveItemUrl(
        serverUrl: String,
        token: String,
        url: String,
        title: String?,
        tags: List<String>,
    ): RemoteItemDto {
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_POST,
                    url = "${serverUrl.trimEnd('/')}/api/v1/items/save",
                    headers = authHeaders(token),
                    body = buildSaveItemPayload(url = url, title = title, tags = tags),
                )
            )
        ensureSuccess(response)
        return parseItemJson(response.body)
    }

    suspend fun deleteItem(
        serverUrl: String,
        token: String,
        itemId: String,
    ) {
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_DELETE,
                    url = itemEndpointUrl(serverUrl, itemId),
                    headers = authHeaders(token),
                )
            )
        ensureSuccess(response)
    }

    internal suspend fun updateItem(
        serverUrl: String,
        token: String,
        itemId: String,
        status: String? = null,
        isFavorite: Boolean? = null,
        readProgress: Double? = null,
        readAt: String? = null,
    ): RemoteItemDto {
        val response =
            transport.execute(
                HttpRequest(
                    method = "PATCH",
                    url = itemEndpointUrl(serverUrl, itemId),
                    headers = authHeaders(token),
                    body =
                        buildUpdateItemPayload(
                            status = status,
                            isFavorite = isFavorite,
                            readProgress = readProgress,
                            readAt = readAt,
                        ),
                )
            )
        ensureSuccess(response)
        return parseItemJson(response.body)
    }

    suspend fun fetchTags(serverUrl: String, token: String): List<RemoteTagDto> {
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_GET,
                    url = "${serverUrl.trimEnd('/')}/api/v1/tags",
                    headers = authHeaders(token),
                )
            )
        ensureSuccess(response)
        return parseTagsJsonArray(response.body)
    }

    internal suspend fun addTagToItem(
        serverUrl: String,
        token: String,
        itemId: String,
        tagName: String,
    ): List<RemoteTagDto> {
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_POST,
                    url = "${itemEndpointUrl(serverUrl, itemId)}/tags",
                    headers = authHeaders(token),
                    body = buildAddItemTagPayload(tagName),
                )
            )
        ensureSuccess(response)
        return parseTagsJsonArray(response.body)
    }

    internal suspend fun removeTagFromItem(
        serverUrl: String,
        token: String,
        itemId: String,
        tagId: String,
    ) {
        val encodedTagId = encodePathSegment(tagId)
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_DELETE,
                    url = "${itemEndpointUrl(serverUrl, itemId)}/tags/$encodedTagId",
                    headers = authHeaders(token),
                )
            )
        ensureSuccess(response)
    }

    internal suspend fun archiveBatch(
        serverUrl: String,
        token: String,
        itemIds: List<String> = emptyList(),
        feedId: String? = null,
        allUnread: Boolean = false,
    ): Int {
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_POST,
                    url = "${serverUrl.trimEnd('/')}/api/v1/items/archive-batch",
                    headers = authHeaders(token),
                    body =
                        buildArchiveBatchPayload(
                            itemIds = itemIds,
                            feedId = feedId,
                            allUnread = allUnread,
                        ),
                )
            )
        ensureSuccess(response)
        return parseArchivedCountJson(response.body)
    }

    internal suspend fun importOpml(
        serverUrl: String,
        token: String,
        opmlXml: String,
    ): Int {
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_POST,
                    url = "${serverUrl.trimEnd('/')}/api/v1/import/opml",
                    headers = authHeaders(token),
                    body = buildImportOpmlPayload(opmlXml),
                )
            )
        ensureSuccess(response)
        return parseImportedCountJson(response.body)
    }

    internal suspend fun exportOpml(
        serverUrl: String,
        token: String,
    ): String {
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_GET,
                    url = "${serverUrl.trimEnd('/')}/api/v1/export/feeds",
                    headers = authHeaders(token),
                )
            )
        ensureSuccess(response)
        return response.body
    }

    private companion object {
        private const val METHOD_GET = "GET"
        private const val METHOD_POST = "POST"
        private const val METHOD_DELETE = "DELETE"
        private const val ITEMS_PER_PAGE = 200
        private const val MAX_SYNC_PAGES = 25
        private const val EXTERNAL_IMAGE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
        private const val EXTERNAL_IMAGE_ACCEPT =
            "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
    }
}

private const val HTTP_OK_START = 200
private const val HTTP_OK_END = 299

internal fun itemEndpointUrl(serverUrl: String, itemId: String): String =
    "${serverUrl.trimEnd('/')}/api/v1/items/${encodePathSegment(itemId)}"

internal fun encodePathSegment(raw: String): String =
    URLEncoder.encode(raw, StandardCharsets.UTF_8.name()).replace("+", "%20")

internal fun authHeaders(token: String): Map<String, String> =
    if (token.isBlank()) {
        emptyMap()
    } else {
        mapOf("Authorization" to "Bearer $token")
    }

internal fun ensureSuccess(response: HttpResponse) {
    if (response.statusCode in HTTP_OK_START..HTTP_OK_END) return
    val (title, detail) = parseProblemDetails(response.body, response.statusCode)
    throw ProblemDetailsException(statusCode = response.statusCode, title = title, detail = detail)
}

package com.grayvines.roche.data.remote

import com.grayvines.roche.data.NetworkTier
import com.grayvines.roche.data.PeerNodeDto
import com.grayvines.roche.data.SyncTombstoneDto
import com.grayvines.roche.data.local.FeedEntity
import com.grayvines.roche.data.local.TagEntity
import com.grayvines.roche.data.settings.tailscaleDiscoveryUrls
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
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
        val connection = openConfiguredConnection(request)
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
            val stream = openResponseStream(connection, isSuccess)
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

    private fun openConfiguredConnection(request: HttpRequest): HttpURLConnection {
        val sanitizedUrl = request.url.trim().replace(" ", "%20")
        return (URI.create(sanitizedUrl).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = request.method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            if (request.headers.keys.none { it.equals("Accept-Encoding", ignoreCase = true) }) {
                setRequestProperty("Accept-Encoding", "gzip")
            }
            request.headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (request.body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { out ->
                    out.write(request.body.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    private fun openResponseStream(
        connection: HttpURLConnection,
        isSuccess: Boolean,
    ): java.io.InputStream? {
        val rawStream = if (isSuccess) connection.inputStream else connection.errorStream
        val isGzip = connection.contentEncoding?.equals("gzip", ignoreCase = true) == true
        return if (rawStream != null && isGzip) GZIPInputStream(rawStream) else rawStream
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
    val resolved =
        URI.create(baseUrl.trim().replace(" ", "%20"))
            .resolve(location.trim().replace(" ", "%20"))
            .toString()
    resolved.takeIf {
        it.startsWith(HTTP_SCHEME_HTTPS, ignoreCase = true) ||
            it.startsWith(HTTP_SCHEME_HTTP, ignoreCase = true)
    }
}
    .getOrNull()

private const val HTTP_SCHEME_HTTPS = "https://"
private const val HTTP_SCHEME_HTTP = "http://"

internal fun isSameOriginHost(firstUrl: String, secondUrl: String): Boolean = runCatching {
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
private const val MAX_RESPONSE_CHARS = 64 * 1024 * 1024
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
    val description: String? = null,
    val language: String? = null,
    val pollInterval: Int = 30,
    val lastPolled: String? = null,
    val lastPolledBy: String? = null,
    val lastError: String? = null,
    val lastErrorBy: String? = null,
    val isActive: Boolean = true,
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
    val feedUrl: String? = null,
    val interactionUpdatedAt: String? = null,
    val audioUrl: String? = null,
)

data class RemoteTagDto(
    val id: String,
    val name: String,
    val unreadCount: Int,
    val color: String? = null,
)

data class PeerSyncItemPayload(
    val id: String,
    val feedId: String?,
    val feedUrl: String?,
    val title: String,
    val url: String,
    val author: String?,
    val publishedAt: String?,
    val createdAt: String?,
    val summary: String?,
    val contentHtml: String?,
    val thumbnailUrl: String?,
    val contentType: String,
    val status: String,
    val isFavorite: Boolean,
    val readAt: String?,
    val readProgress: Double,
    val interactionUpdatedAt: String?,
    val tagNames: List<String>,
    val audioUrl: String? = null,
    val duration: Long? = null,
)

data class PeerSyncRequestDto(
    val peerId: String,
    val deviceName: String? = null,
    val networkTier: String = NetworkTier.WIFI.wireValue,
    val weight: Double? = null,
    val updatedAfter: String? = null,
    val feeds: List<FeedEntity> = emptyList(),
    val items: List<PeerSyncItemPayload> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val tombstones: List<SyncTombstoneDto> = emptyList(),
)

data class PeerSyncResponseDto(
    val serverPeerId: String,
    val syncTimestamp: String,
    val activePeers: List<PeerNodeDto>,
    val feeds: List<RemoteFeedDto>,
    val items: List<RemoteItemDto>,
    val tags: List<RemoteTagDto>,
    val tombstones: List<SyncTombstoneDto>,
)

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

    internal suspend fun updateFeed(
        serverUrl: String,
        token: String,
        feedId: String,
        title: String? = null,
        pollInterval: Int? = null,
        isActive: Boolean? = null,
    ): RemoteFeedDto {
        val encodedId = encodePathSegment(feedId)
        val response =
            transport.execute(
                HttpRequest(
                    method = "PATCH",
                    url = "${serverUrl.trimEnd('/')}/api/v1/feeds/$encodedId",
                    headers = authHeaders(token),
                    body =
                        buildUpdateFeedPayload(
                            title = title,
                            pollInterval = pollInterval,
                            isActive = isActive,
                        ),
                )
            )
        ensureSuccess(response)
        return parseFeedJson(response.body)
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
            !trimmed.startsWith(HTTP_SCHEME_HTTPS, ignoreCase = true) &&
                !trimmed.startsWith(HTTP_SCHEME_HTTP, ignoreCase = true)
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
                            HEADER_USER_AGENT to EXTERNAL_IMAGE_USER_AGENT,
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

    internal suspend fun streamExternalBinaryToFile(
        fileUrl: String,
        targetFile: java.io.File,
        maxBytes: Long = MAX_AUDIO_ENCLOSURE_BYTES,
    ): Boolean = runCatching {
        val trimmed = fileUrl.trim()
        val validScheme =
            trimmed.startsWith(HTTP_SCHEME_HTTPS, ignoreCase = true) ||
                trimmed.startsWith(HTTP_SCHEME_HTTP, ignoreCase = true)
        when {
            !validScheme -> false
            transport !is HttpUrlConnectionTransport ->
                streamFakeTransportToFile(trimmed, targetFile, maxBytes)
            else ->
                withContext(Dispatchers.IO) {
                    streamUrlConnectionToFile(trimmed, targetFile, maxBytes)
                }
        }
    }
        .onFailure { if (it is CancellationException) throw it }
        .getOrDefault(false)

    private suspend fun streamFakeTransportToFile(
        trimmedUrl: String,
        targetFile: java.io.File,
        maxBytes: Long,
    ): Boolean {
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_GET,
                    url = trimmedUrl,
                    headers = mapOf(HEADER_USER_AGENT to EXTERNAL_IMAGE_USER_AGENT),
                    expectBinary = true,
                )
            )
        val bytes = response.bodyBytes ?: response.body.toByteArray(Charsets.UTF_8)
        val valid =
            response.statusCode in HTTP_OK_START..HTTP_OK_END &&
                bytes.isNotEmpty() &&
                bytes.size <= maxBytes
        return if (!valid) {
            false
        } else {
            withContext(Dispatchers.IO) {
                writeBytesAtomicallyToFile(bytes, targetFile)
            }
        }
    }

    internal suspend fun fetchExternalFeedXml(feedUrl: String): Result<String> = runCatching {
        val trimmed = feedUrl.trim()
        require(
            trimmed.startsWith(HTTP_SCHEME_HTTPS, ignoreCase = true) ||
                trimmed.startsWith(HTTP_SCHEME_HTTP, ignoreCase = true)
        ) {
            "Feed URL must use http:// or https://"
        }
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_GET,
                    url = trimmed,
                    headers =
                        mapOf(
                            HEADER_USER_AGENT to EXTERNAL_IMAGE_USER_AGENT,
                            "Accept" to EXTERNAL_FEED_ACCEPT,
                        ),
                )
            )
        ensureSuccess(response)
        response.body
    }
        .onFailure { if (it is CancellationException) throw it }

    internal suspend fun syncPeer(
        serverUrl: String,
        token: String,
        request: PeerSyncRequestDto,
    ): PeerSyncResponseDto? {
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_POST,
                    url = "${serverUrl.trimEnd('/')}/api/v1/sync/peer",
                    headers = authHeaders(token),
                    body = buildPeerSyncRequestPayload(request),
                )
            )
        if (
            response.statusCode == HTTP_NOT_FOUND_CODE ||
                response.statusCode == HTTP_METHOD_NOT_ALLOWED_CODE
        ) {
            return null
        }
        ensureSuccess(response)
        return parsePeerSyncResponseJson(response.body)
    }

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
        private const val EXTERNAL_FEED_ACCEPT =
            "application/rss+xml, application/atom+xml, application/xml, text/xml, */*;q=0.8"
        private const val HTTP_NOT_FOUND_CODE = 404
        private const val HTTP_METHOD_NOT_ALLOWED_CODE = 405
    }
}

private const val HEADER_USER_AGENT = "User-Agent"
private const val HTTP_OK_START = 200
private const val HTTP_OK_END = 299
internal const val MAX_AUDIO_ENCLOSURE_BYTES = 500L * 1024L * 1024L
private const val AUDIO_CONNECT_TIMEOUT_MS = 15_000
private const val AUDIO_READ_TIMEOUT_MS = 60_000
private const val AUDIO_MAX_REDIRECTS = 5
private val AUDIO_REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

internal fun writeBytesAtomicallyToFile(bytes: ByteArray, targetFile: java.io.File): Boolean {
    targetFile.parentFile?.mkdirs()
    val temp = java.io.File(targetFile.parentFile, "${targetFile.name}.${System.nanoTime()}.tmp")
    return try {
        temp.writeBytes(bytes)
        if (!temp.renameTo(targetFile)) {
            temp.copyTo(targetFile, overwrite = true)
        }
        true
    } finally {
        if (temp.exists()) temp.delete()
    }
}

internal fun streamUrlConnectionToFile(
    initialUrl: String,
    targetFile: java.io.File,
    maxBytes: Long = MAX_AUDIO_ENCLOSURE_BYTES,
): Boolean {
    var currentUrl: String? = initialUrl.trim()
    var step = 0
    var success = false
    while (currentUrl != null && step <= AUDIO_MAX_REDIRECTS && !success) {
        val nextOrDone =
            executeAudioStreamAttempt(
                url = currentUrl,
                targetFile = targetFile,
                maxBytes = maxBytes,
            )
        when (nextOrDone) {
            is AudioStreamStepResult.Redirect -> currentUrl = nextOrDone.nextUrl
            AudioStreamStepResult.Success -> success = true
            AudioStreamStepResult.Failed -> currentUrl = null
        }
        step++
    }
    return success
}

private sealed interface AudioStreamStepResult {
    data class Redirect(val nextUrl: String) : AudioStreamStepResult

    data object Success : AudioStreamStepResult

    data object Failed : AudioStreamStepResult
}

private fun executeAudioStreamAttempt(
    url: String,
    targetFile: java.io.File,
    maxBytes: Long,
): AudioStreamStepResult {
    val connection =
        (URI.create(url.replace(" ", "%20")).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "GET"
            connectTimeout = AUDIO_CONNECT_TIMEOUT_MS
            readTimeout = AUDIO_READ_TIMEOUT_MS
            setRequestProperty(HEADER_USER_AGENT, "Roche-Android/1.0")
        }
    return try {
        val status = connection.responseCode
        when {
            status in AUDIO_REDIRECT_CODES -> {
                val loc = connection.getHeaderField("Location")?.trim()?.takeIf { it.isNotBlank() }
                val next = loc?.let { resolveRedirectUrl(url, it) }
                if (next != null) {
                    AudioStreamStepResult.Redirect(next)
                } else {
                    AudioStreamStepResult.Failed
                }
            }
            status in HTTP_OK_START..HTTP_OK_END && connection.contentLengthLong <= maxBytes -> {
                if (copyStreamAtomicallyToFile(connection, targetFile, maxBytes)) {
                    AudioStreamStepResult.Success
                } else {
                    AudioStreamStepResult.Failed
                }
            }
            else -> {
                AudioStreamStepResult.Failed
            }
        }
    } finally {
        connection.disconnect()
    }
}

private fun copyStreamAtomicallyToFile(
    connection: HttpURLConnection,
    targetFile: java.io.File,
    maxBytes: Long,
): Boolean {
    targetFile.parentFile?.mkdirs()
    val tempFile =
        java.io.File(targetFile.parentFile, "${targetFile.name}.${System.nanoTime()}.tmp")
    return try {
        val total =
            connection.inputStream.use { input ->
                tempFile.outputStream().use { out ->
                    copyBoundStreamBytes(input, out, maxBytes)
                }
            }
        val wroteBytes = total > 0L
        if (wroteBytes && !tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
        }
        wroteBytes
    } finally {
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }
}

private fun copyBoundStreamBytes(
    input: java.io.InputStream,
    out: java.io.OutputStream,
    maxBytes: Long,
): Long {
    var total = 0L
    val buffer = ByteArray(READ_BUFFER_CHARS)
    var read = input.read(buffer)
    while (read != -1) {
        total += read
        if (total > maxBytes) {
            throw IOException("Audio stream exceeds maximum allowed size")
        }
        out.write(buffer, 0, read)
        read = input.read(buffer)
    }
    return total
}

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

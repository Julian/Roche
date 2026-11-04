package com.grayvines.roche.data.remote

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

data class HttpResponse(val statusCode: Int, val body: String)

fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

class HttpUrlConnectionTransport : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse =
        withContext(Dispatchers.IO) {
            val connection =
                (URI.create(request.url).toURL().openConnection() as HttpURLConnection).apply {
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
                val stream =
                    if (status in HTTP_OK_START..HTTP_OK_END) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }
                val text = stream?.let { input -> readBoundedText(input) } ?: ""
                HttpResponse(statusCode = status, body = text)
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
    }
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
private const val MAX_RESPONSE_CHARS = 4 * 1024 * 1024

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
)

data class RemoteTagDto(val id: String, val name: String, val unreadCount: Int)

class RocheApiClient(private val transport: HttpTransport) {

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

    suspend fun createFeed(
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

    suspend fun fetchItems(serverUrl: String, token: String): List<RemoteItemDto> {
        val response =
            transport.execute(
                HttpRequest(
                    method = METHOD_GET,
                    url = "${serverUrl.trimEnd('/')}/api/v1/items?per_page=200",
                    headers = authHeaders(token),
                )
            )
        ensureSuccess(response)
        return parseItemsEnvelopeJson(response.body)
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

    suspend fun updateItem(
        serverUrl: String,
        token: String,
        itemId: String,
        status: String? = null,
        isFavorite: Boolean? = null,
        readProgress: Double? = null,
    ): RemoteItemDto {
        val response =
            transport.execute(
                HttpRequest(
                    method = "PATCH",
                    url = "${serverUrl.trimEnd('/')}/api/v1/items/$itemId",
                    headers = authHeaders(token),
                    body =
                        buildUpdateItemPayload(
                            status = status,
                            isFavorite = isFavorite,
                            readProgress = readProgress,
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
                    url = "${serverUrl.trimEnd('/')}/api/v1/items/$itemId/tags",
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
        val response =
            transport.execute(
                HttpRequest(
                    method = "DELETE",
                    url = "${serverUrl.trimEnd('/')}/api/v1/items/$itemId/tags/$tagId",
                    headers = authHeaders(token),
                )
            )
        ensureSuccess(response)
    }

    private companion object {
        private const val METHOD_GET = "GET"
        private const val METHOD_POST = "POST"
    }
}

private const val HTTP_OK_START = 200
private const val HTTP_OK_END = 299

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

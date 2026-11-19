package com.grayvines.roche.ui

import android.content.Context
import android.net.Uri
import android.net.http.HttpEngine
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpUtil
import com.grayvines.roche.data.remote.MAX_REDIRECTS
import com.grayvines.roche.data.remote.PublicUrlValidator
import com.grayvines.roche.data.remote.filterRedirectHeaders
import com.grayvines.roche.data.remote.isSameOriginHost
import com.grayvines.roche.data.remote.resolveRedirectUrl
import com.grayvines.roche.data.remote.shouldRewriteRedirectToGet
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL

@OptIn(UnstableApi::class)
internal fun createAudioDataSourceFactory(
    context: Context,
    httpEngine: HttpEngine,
): DataSource.Factory {
    val safeHttpFactory = DataSource.Factory {
        SafeRedirectHttpDataSource(
            openConnection = { url -> httpEngine.openConnection(url) as HttpURLConnection },
            userAgent = ROCHE_AUDIO_USER_AGENT,
            connectTimeoutMs = AUDIO_HTTP_CONNECT_TIMEOUT_MS,
            readTimeoutMs = AUDIO_HTTP_READ_TIMEOUT_MS,
        )
    }
    return DefaultDataSource.Factory(context, safeHttpFactory)
}

@OptIn(UnstableApi::class)
internal class SafeRedirectHttpDataSource(
    private val openConnection: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
    private val validateUrl: (String) -> String = { resolveSafeHttpPlaybackUrl(it) },
    private val userAgent: String = ROCHE_AUDIO_USER_AGENT,
    private val connectTimeoutMs: Int = AUDIO_HTTP_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = AUDIO_HTTP_READ_TIMEOUT_MS,
    private val maxRedirects: Int = MAX_REDIRECTS,
) : BaseDataSource(/* isNetwork= */ true) {
    private var connection: HttpURLConnection? = null
    private var inputStream: InputStream? = null
    private var opened = false
    private var currentUri: Uri? = null
    private var latestResponseHeaders: Map<String, List<String>> = emptyMap()
    private var bytesToRead: Long = C.LENGTH_UNSET.toLong()
    private var bytesRead: Long = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val (resolvedBytesToRead, finalUrl) =
            openStreamForUrl(
                rawUrl = dataSpec.uri.toString().trim(),
                httpMethod = dataSpec.httpMethodString,
                httpBody = dataSpec.httpBody,
                requestHeaders = dataSpec.httpRequestHeaders,
                position = dataSpec.position,
                length = dataSpec.length,
                dataSpecForError = dataSpec,
            )
        currentUri = finalUrl.toUri()
        opened = true
        transferStarted(dataSpec)
        return resolvedBytesToRead
    }

    @Suppress("LongParameterList")
    internal fun openStreamForUrl(
        rawUrl: String,
        httpMethod: String = "GET",
        httpBody: ByteArray? = null,
        requestHeaders: Map<String, String> = emptyMap(),
        position: Long = 0L,
        length: Long = C.LENGTH_UNSET.toLong(),
        dataSpecForError: DataSpec? = null,
    ): Pair<Long, String> {
        bytesRead = 0L
        bytesToRead = C.LENGTH_UNSET.toLong()
        latestResponseHeaders = emptyMap()

        val (conn, finalUrl) =
            openValidatedWithCleanup(
                rawUrl = rawUrl,
                httpMethod = httpMethod,
                httpBody = httpBody,
                requestHeaders = requestHeaders,
                position = position,
                length = length,
            )
        connection = conn
        val meta = readResponseMetadata(conn, ::closeConnectionQuietly)
        latestResponseHeaders = meta.headers

        if (meta.responseCode !in HTTP_OK..HTTP_SUCCESS_MAX) {
            return handleNonSuccessResponse(meta, position, finalUrl, dataSpecForError)
        }

        val bytesToSkip = if (meta.responseCode == HTTP_OK && position != 0L) position else 0L
        bytesToRead = computeBytesToRead(length, meta, bytesToSkip)
        openAndSkipStream(conn, bytesToSkip)
        return bytesToRead to finalUrl
    }

    @Suppress("LongParameterList")
    private fun openValidatedWithCleanup(
        rawUrl: String,
        httpMethod: String,
        httpBody: ByteArray?,
        requestHeaders: Map<String, String>,
        position: Long,
        length: Long,
    ): Pair<HttpURLConnection, String> =
        try {
            openValidatedUrl(
                rawUrl = rawUrl.trim(),
                httpMethod = httpMethod,
                httpBody = httpBody,
                requestHeaders = requestHeaders,
                position = position,
                length = length,
            )
        } catch (err: IOException) {
            closeConnectionQuietly()
            throw err
        } catch (err: IllegalArgumentException) {
            closeConnectionQuietly()
            throw IOException("Blocked unsafe audio target", err)
        }

    private fun handleNonSuccessResponse(
        meta: ResponseMetadata,
        position: Long,
        finalUrl: String,
        dataSpecForError: DataSpec?,
    ): Pair<Long, String> {
        closeConnectionQuietly()
        if (meta.responseCode == HTTP_RANGE_NOT_SATISFIABLE) {
            if (position == parseDocumentSize(meta.contentRangeHeader)) {
                bytesToRead = 0L
                return 0L to finalUrl
            }
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        val error: IOException =
            if (dataSpecForError != null) {
                HttpDataSource.InvalidResponseCodeException(
                    meta.responseCode,
                    meta.responseMessage,
                    null,
                    meta.headers,
                    dataSpecForError,
                    ByteArray(0),
                )
            } else {
                val message = meta.responseMessage.orEmpty()
                IOException("Unexpected HTTP response code ${meta.responseCode}: $message")
            }
        throw error
    }

    private fun openAndSkipStream(conn: HttpURLConnection, bytesToSkip: Long) {
        try {
            val stream = conn.inputStream
            skipFully(stream, bytesToSkip)
            inputStream = stream
        } catch (err: IOException) {
            closeConnectionQuietly()
            throw err
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val toRead =
            if (bytesToRead != C.LENGTH_UNSET.toLong()) {
                val remaining = bytesToRead - bytesRead
                if (remaining <= 0L) return C.RESULT_END_OF_INPUT
                minOf(length.toLong(), remaining).toInt()
            } else {
                length
            }
        val readCount = inputStream?.read(buffer, offset, toRead) ?: C.RESULT_END_OF_INPUT
        val result =
            if (readCount == -1) {
                C.RESULT_END_OF_INPUT
            } else {
                bytesRead += readCount
                bytesTransferred(readCount)
                readCount
            }
        return result
    }

    override fun getUri(): Uri? = currentUri

    override fun getResponseHeaders(): Map<String, List<String>> = latestResponseHeaders

    override fun close() {
        try {
            inputStream?.close()
        } finally {
            inputStream = null
            latestResponseHeaders = emptyMap()
            closeConnectionQuietly()
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    internal fun openValidatedUrl(
        rawUrl: String,
        httpMethod: String = "GET",
        httpBody: ByteArray? = null,
        requestHeaders: Map<String, String> = emptyMap(),
        position: Long = 0L,
        length: Long = C.LENGTH_UNSET.toLong(),
    ): Pair<HttpURLConnection, String> {
        var currentUrl = validateUrl(rawUrl.trim())
        var method = httpMethod
        var body = httpBody
        var currentHeaders = requestHeaders

        for (hop in 0..maxRedirects) {
            val conn =
                openSingleConnection(
                    url = currentUrl,
                    httpMethod = method,
                    httpBody = body,
                    requestHeaders = currentHeaders,
                    position = position,
                    length = length,
                )
            var keepConnectionOpen = false
            var disconnected = false
            try {
                val responseCode = conn.responseCode
                if (responseCode !in REDIRECT_STATUS_CODES) {
                    keepConnectionOpen = true
                    return conn to currentUrl
                }
                val location = conn.getHeaderField("Location")
                disconnected = true
                runCatching { conn.disconnect() }
                if (hop >= maxRedirects) break
                val nextRaw =
                    location?.let { resolveRedirectUrl(currentUrl, it) }
                        ?: throw IOException("Blocked or invalid audio redirect URL")
                val nextUrl = validateUrl(nextRaw)
                val switchToGet = shouldRewriteRedirectToGet(method, responseCode)
                currentHeaders =
                    filterRedirectHeaders(
                        headers = currentHeaders,
                        sameOrigin = isSameOriginHost(currentUrl, nextUrl),
                        dropPayloadHeaders = switchToGet,
                    )
                currentUrl = nextUrl
                if (switchToGet) {
                    method = "GET"
                    body = null
                }
            } finally {
                if (!keepConnectionOpen && !disconnected) {
                    runCatching { conn.disconnect() }
                }
            }
        }
        throw IOException("Too many audio redirects")
    }

    private fun openSingleConnection(
        url: String,
        httpMethod: String,
        httpBody: ByteArray?,
        requestHeaders: Map<String, String>,
        position: Long,
        length: Long,
    ): HttpURLConnection {
        val conn = openConnection(URI.create(url.trim().replace(" ", "%20")).toURL())
        var handedOff = false
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = httpMethod
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept-Encoding", "identity")
            for ((key, value) in requestHeaders) {
                conn.setRequestProperty(key, value)
            }
            val rangeHeader = HttpUtil.buildRangeRequestHeader(position, length)
            if (rangeHeader != null) {
                conn.setRequestProperty("Range", rangeHeader)
            }
            if (httpBody != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(httpBody.size)
                conn.connect()
                conn.outputStream.use { out -> out.write(httpBody) }
            } else {
                conn.connect()
            }
            handedOff = true
            return conn
        } finally {
            if (!handedOff) {
                runCatching { conn.disconnect() }
            }
        }
    }

    private fun skipFully(stream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val skipBuffer = ByteArray(SKIP_BUFFER_BYTES)
        while (remaining > 0L) {
            val read =
                stream.read(skipBuffer, 0, minOf(remaining, skipBuffer.size.toLong()).toInt())
            if (read == -1) {
                throw EOFException("Unexpected end of stream while skipping audio bytes")
            }
            remaining -= read
            bytesTransferred(read)
        }
    }

    private fun closeConnectionQuietly() {
        connection?.let {
            runCatching { it.disconnect() }
            connection = null
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_SUCCESS_MAX = 299
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val SKIP_BUFFER_BYTES = 4096
        val REDIRECT_STATUS_CODES = setOf(300, 301, 302, 303, 307, 308)
    }
}

internal data class ResponseMetadata(
    val responseCode: Int,
    val responseMessage: String?,
    val headers: Map<String, List<String>>,
    val contentLengthHeader: String?,
    val contentRangeHeader: String?,
)

private val CONTENT_RANGE_WITH_SIZE_REGEX = Regex("""bytes (?:\d+-\d+|\*)/(\d+)""")

internal fun readResponseMetadata(
    conn: HttpURLConnection,
    onError: () -> Unit,
): ResponseMetadata =
    try {
        ResponseMetadata(
            responseCode = conn.responseCode,
            responseMessage = conn.responseMessage,
            headers = conn.headerFields ?: emptyMap(),
            contentLengthHeader = conn.getHeaderField("Content-Length"),
            contentRangeHeader = conn.getHeaderField("Content-Range"),
        )
    } catch (err: IOException) {
        onError()
        throw err
    }

@OptIn(UnstableApi::class)
internal fun computeBytesToRead(
    requestedLength: Long,
    meta: ResponseMetadata,
    bytesToSkip: Long,
): Long {
    if (requestedLength != C.LENGTH_UNSET.toLong()) return requestedLength
    val contentLength = HttpUtil.getContentLength(meta.contentLengthHeader, meta.contentRangeHeader)
    return if (contentLength != C.LENGTH_UNSET.toLong()) {
        (contentLength - bytesToSkip).coerceAtLeast(0L)
    } else {
        C.LENGTH_UNSET.toLong()
    }
}

internal fun parseDocumentSize(contentRangeHeader: String?): Long {
    if (contentRangeHeader.isNullOrBlank()) return C.LENGTH_UNSET.toLong()
    val match = CONTENT_RANGE_WITH_SIZE_REGEX.matchEntire(contentRangeHeader.trim())
    return match?.groupValues?.getOrNull(1)?.toLongOrNull() ?: C.LENGTH_UNSET.toLong()
}

internal fun resolveSafeHttpPlaybackUrl(
    rawUrl: String,
    resolveAddresses: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    isCleartextPermitted: ((String) -> Boolean)? = null,
): String {
    val trimmed = rawUrl.trim()
    val upgradedUrl =
        if (isCleartextPermitted != null) {
            PublicUrlValidator.upgradePublicHttp(
                rawUrl = trimmed,
                isCleartextPermitted = isCleartextPermitted,
            )
        } else {
            PublicUrlValidator.upgradePublicHttp(trimmed)
        }
    PublicUrlValidator.ensurePublicTarget(
        rawUrl = upgradedUrl,
        resolveAddresses = resolveAddresses,
    )
    return upgradedUrl
}

internal const val ROCHE_AUDIO_USER_AGENT = "Roche-Android/1.0"
internal const val AUDIO_HTTP_CONNECT_TIMEOUT_MS = 15_000
internal const val AUDIO_HTTP_READ_TIMEOUT_MS = 30_000

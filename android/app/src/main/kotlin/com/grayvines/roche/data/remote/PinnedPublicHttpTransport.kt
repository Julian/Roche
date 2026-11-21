@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data.remote

import com.grayvines.roche.core.isPublicInetAddress
import com.grayvines.roche.core.moveFileAtomically
import com.grayvines.roche.core.runSuspendCatching
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * SSLSocketFactory pinned to a single pre-validated public [InetAddress] while preserving SNI and
 * strict RFC 2818 hostname verification against [expectedHost]. Eliminates DNS rebinding TOCTOU
 * between URL validation and socket connect.
 *
 * Implements [equals] / [hashCode] on `(expectedHost, targetPort, pinnedAddress)` so Android's
 * platform connection pool never shares TLS sessions across virtual hosts that share a CDN IP.
 */
internal class PinnedIpSslSocketFactory(
    private val delegate: SSLSocketFactory,
    internal val pinnedAddress: InetAddress,
    internal val expectedHost: String,
    internal val targetPort: Int,
    private val connectTimeoutMs: Int,
) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        if (!isPublicInetAddress(pinnedAddress)) {
            runCatching { s.close() }
            throw IOException("Blocked non-public pinned address for $expectedHost")
        }
        val connectedAddr = s.inetAddress
        if (connectedAddr != null && connectedAddr == pinnedAddress && s.port == targetPort) {
            return configureSslSocket(
                delegate.createSocket(s, expectedHost, targetPort, autoClose) as SSLSocket
            )
        }
        runCatching { s.close() }
        return connectPinnedSslSocket()
    }

    override fun createSocket(): Socket = connectPinnedSslSocket()

    override fun createSocket(host: String, port: Int): Socket = connectPinnedSslSocket()

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket = connectPinnedSslSocket(localHost, localPort)

    override fun createSocket(host: InetAddress, port: Int): Socket = connectPinnedSslSocket()

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = connectPinnedSslSocket(localAddress, localPort)

    private fun connectPinnedSslSocket(
        localAddress: InetAddress? = null,
        localPort: Int = 0,
    ): SSLSocket {
        if (!isPublicInetAddress(pinnedAddress)) {
            throw IOException("Blocked non-public pinned address for $expectedHost")
        }
        val rawSocket = Socket()
        var wrapped = false
        try {
            if (localAddress != null) {
                rawSocket.bind(InetSocketAddress(localAddress, localPort))
            }
            rawSocket.connect(InetSocketAddress(pinnedAddress, targetPort), connectTimeoutMs)
            val sslSocket =
                configureSslSocket(
                    delegate.createSocket(rawSocket, expectedHost, targetPort, true) as SSLSocket
                )
            wrapped = true
            return sslSocket
        } finally {
            if (!wrapped) {
                runCatching { rawSocket.close() }
            }
        }
    }

    private fun configureSslSocket(socket: SSLSocket): SSLSocket =
        PinnedSniSslSocket(delegate = socket, expectedHost = expectedHost)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PinnedIpSslSocketFactory) return false
        return expectedHost == other.expectedHost &&
            targetPort == other.targetPort &&
            pinnedAddress == other.pinnedAddress
    }

    override fun hashCode(): Int {
        var result = expectedHost.hashCode()
        result = HASH_MULTIPLIER * result + targetPort
        result = HASH_MULTIPLIER * result + pinnedAddress.hashCode()
        return result
    }
}

internal class PinnedHostnameVerifier(
    internal val expectedHost: String,
    private val delegate: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
) : HostnameVerifier {
    override fun verify(hostname: String, session: SSLSession): Boolean =
        delegate.verify(expectedHost, session)

    override fun equals(other: Any?): Boolean =
        this === other || other is PinnedHostnameVerifier && expectedHost == other.expectedHost

    override fun hashCode(): Int = expectedHost.hashCode()
}

internal fun buildPinnedIpConnectionUrl(
    target: ResolvedPublicTarget,
    address: InetAddress,
): URL {
    val uri = parseHttpUriOrThrow(target.canonicalUrl)
    val ipHost = formatAddressForUrlHost(address)
    val defaultPort = if (target.scheme == HTTPS_SCHEME) HTTPS_DEFAULT_PORT else HTTP_DEFAULT_PORT
    val portPart = if (target.port != defaultPort) ":${target.port}" else ""
    val pathPart = uri.rawPath.orEmpty()
    val queryPart = uri.rawQuery?.let { "?$it" }.orEmpty()
    return URI.create("${target.scheme}://$ipHost$portPart$pathPart$queryPart").toURL()
}

internal fun buildHostHeaderValue(target: ResolvedPublicTarget): String {
    val defaultPort = if (target.scheme == HTTPS_SCHEME) HTTPS_DEFAULT_PORT else HTTP_DEFAULT_PORT
    return if (target.port != defaultPort) {
        "${target.asciiHost}:${target.port}"
    } else {
        target.asciiHost
    }
}

internal fun openPinnedPublicConnection(
    target: ResolvedPublicTarget,
    address: InetAddress,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    baseSslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
): HttpURLConnection {
    if (!isPublicInetAddress(address)) {
        throw IOException("Blocked non-public pinned address for ${target.asciiHost}")
    }
    val pinnedUrl = buildPinnedIpConnectionUrl(target, address)
    val conn = pinnedUrl.openConnection(Proxy.NO_PROXY) as HttpURLConnection
    conn.instanceFollowRedirects = false
    conn.connectTimeout = connectTimeoutMs
    conn.readTimeout = readTimeoutMs
    conn.setRequestProperty(HEADER_HOST, buildHostHeaderValue(target))
    if (conn is HttpsURLConnection) {
        conn.sslSocketFactory =
            PinnedIpSslSocketFactory(
                delegate = baseSslSocketFactory,
                pinnedAddress = address,
                expectedHost = target.asciiHost,
                targetPort = target.port,
                connectTimeoutMs = connectTimeoutMs,
            )
        conn.hostnameVerifier = PinnedHostnameVerifier(target.asciiHost)
    }
    return conn
}

internal class PinnedPublicHttpTransport(
    private val resolveTarget: (String) -> ResolvedPublicTarget = { rawUrl ->
        PublicUrlValidator.resolvePublicTarget(rawUrl = rawUrl, upgradeCleartext = true)
    },
    private val openConnection: (ResolvedPublicTarget, InetAddress, Int, Int) -> HttpURLConnection =
        { target, address, connectMs, readMs ->
            openPinnedPublicConnection(target, address, connectMs, readMs)
        },
    private val connectTimeoutMs: Int = PUBLIC_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = PUBLIC_READ_TIMEOUT_MS,
    private val streamReadTimeoutMs: Int = PUBLIC_STREAM_READ_TIMEOUT_MS,
    private val maxRedirects: Int = MAX_REDIRECTS,
) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse =
        withContext(Dispatchers.IO) { executeFollowingRedirects(request) }

    override suspend fun streamToFile(
        request: HttpRequest,
        targetFile: File,
        maxBytes: Long,
    ): Boolean =
        if (maxBytes <= 0L) {
            false
        } else {
            withContext(Dispatchers.IO) {
                streamFollowingRedirects(request, targetFile, maxBytes)
            }
        }

    private suspend fun executeFollowingRedirects(initialRequest: HttpRequest): HttpResponse {
        var current = initialRequest.copy(requirePublicIp = true)
        for (hop in 0..maxRedirects) {
            currentCoroutineContext().ensureActive()
            val target = resolveTarget(current.url)
            val effective = current.copy(url = target.canonicalUrl)
            val hopOutcome = executeBufferedHop(target, effective)
            val redirectLocation = hopOutcome.redirectLocation ?: return hopOutcome.response
            current =
                resolveNextRedirectHop(
                    effectiveRequest = effective,
                    statusCode = hopOutcome.response.statusCode,
                    location = redirectLocation,
                    hop = hop,
                    maxRedirects = maxRedirects,
                )
                    ?: throw IOException(
                        if (hop >= maxRedirects) "Too many redirects" else "Invalid redirect target"
                    )
        }
        throw IOException("Too many redirects")
    }

    private suspend fun executeBufferedHop(
        target: ResolvedPublicTarget,
        request: HttpRequest,
    ): BufferedHopOutcome {
        val conn = connectAcrossAddresses(target, request, readTimeoutMs)
        try {
            val statusCode = conn.responseCode
            val headers = flattenUrlConnectionHeaders(conn)
            if (statusCode in REDIRECT_STATUS_CODES) {
                val location =
                    conn.getHeaderField(HEADER_LOCATION)?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw IOException(REDIRECT_MISSING_LOCATION_MESSAGE)
                return BufferedHopOutcome(
                    response = HttpResponse(statusCode = statusCode, body = "", headers = headers),
                    redirectLocation = location,
                )
            }
            val bytes = readBoundedResponseBytes(conn, request, statusCode, headers)
            val isSuccess = statusCode in HTTP_OK_START..HTTP_OK_END
            val text = if (request.expectBinary && isSuccess) "" else bytes.toString(Charsets.UTF_8)
            return BufferedHopOutcome(
                response =
                    HttpResponse(
                        statusCode = statusCode,
                        body = text,
                        headers = headers,
                        bodyBytes = bytes.takeIf { request.expectBinary },
                    ),
                redirectLocation = null,
            )
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private suspend fun streamFollowingRedirects(
        initialRequest: HttpRequest,
        targetFile: File,
        maxBytes: Long,
    ): Boolean {
        var current = initialRequest.copy(requirePublicIp = true, expectBinary = true)
        var succeeded = false
        for (hop in 0..maxRedirects) {
            currentCoroutineContext().ensureActive()
            val target = resolveTarget(current.url)
            val effective = current.copy(url = target.canonicalUrl)
            val outcome = executeStreamHop(target, effective, targetFile, maxBytes)
            val nextRequest =
                when (outcome) {
                    StreamHopResult.Success -> {
                        succeeded = true
                        null
                    }
                    StreamHopResult.Failed -> {
                        null
                    }
                    is StreamHopResult.Redirect -> {
                        if (hop >= maxRedirects) {
                            null
                        } else {
                            resolveNextRedirectHop(
                                effectiveRequest = effective,
                                statusCode = outcome.statusCode,
                                location = outcome.location,
                                hop = hop,
                                maxRedirects = maxRedirects,
                            )
                        }
                    }
                }
            if (nextRequest == null) break
            current = nextRequest
        }
        return succeeded
    }

    private suspend fun executeStreamHop(
        target: ResolvedPublicTarget,
        request: HttpRequest,
        targetFile: File,
        maxBytes: Long,
    ): StreamHopResult {
        val connResult = runSuspendCatching {
            connectAcrossAddresses(target, request, streamReadTimeoutMs)
        }
        val conn = connResult.getOrNull() ?: return StreamHopResult.Failed
        try {
            val statusCode = conn.responseCode
            if (statusCode in REDIRECT_STATUS_CODES) {
                val location =
                    conn.getHeaderField(HEADER_LOCATION)?.trim()?.takeIf { it.isNotEmpty() }
                        ?: return StreamHopResult.Failed
                return StreamHopResult.Redirect(statusCode = statusCode, location = location)
            }
            val declaredLength = conn.contentLengthLong
            if (statusCode !in HTTP_OK_START..HTTP_OK_END || declaredLength > maxBytes) {
                return StreamHopResult.Failed
            }
            return copyStreamToTargetFile(conn.inputStream, targetFile, maxBytes)
        } catch (_: IOException) {
            return StreamHopResult.Failed
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private suspend fun copyStreamToTargetFile(
        input: InputStream,
        targetFile: File,
        maxBytes: Long,
    ): StreamHopResult {
        val opened = openPartFileChannel(targetFile) ?: return StreamHopResult.Failed
        val (partFile, channel) = opened
        var committed = false
        try {
            val written = writeBoundedStreamToChannel(input, channel, maxBytes)
            channel.close()
            if (written != null && written in 1L..maxBytes) {
                moveFileAtomically(partFile, targetFile)
                committed = true
            }
        } catch (_: IOException) {
            committed = false
        } finally {
            runCatching { channel.close() }
            if (!committed) {
                runCatching { Files.deleteIfExists(partFile.toPath()) }
            }
        }
        return if (committed) StreamHopResult.Success else StreamHopResult.Failed
    }

    private suspend fun writeBoundedStreamToChannel(
        input: InputStream,
        channel: java.nio.channels.FileChannel,
        maxBytes: Long,
    ): Long? {
        var written = 0L
        val buffer = ByteArray(STREAM_COPY_BUFFER_BYTES)
        input.use { stream ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(buffer)
                if (count == -1) break
                if (exceedsByteBudget(written, count.toLong(), maxBytes)) return null
                written += writeBufferFully(channel, ByteBuffer.wrap(buffer, 0, count))
            }
        }
        return written
    }

    private fun writeBufferFully(channel: java.nio.channels.FileChannel, bb: ByteBuffer): Long {
        var wrote = 0L
        while (bb.hasRemaining()) {
            wrote += channel.write(bb)
        }
        return wrote
    }

    private suspend fun connectAcrossAddresses(
        target: ResolvedPublicTarget,
        request: HttpRequest,
        effectiveReadTimeoutMs: Int,
    ): HttpURLConnection {
        val candidates = target.addresses.take(MAX_PINNED_ADDRESS_ATTEMPTS)
        val bodyBytes = request.body?.toByteArray(Charsets.UTF_8)
        var lastError: IOException? = null
        for (address in candidates) {
            currentCoroutineContext().ensureActive()
            val conn = openConnection(target, address, connectTimeoutMs, effectiveReadTimeoutMs)
            var handedOff = false
            try {
                configureRequestOnConnection(conn, request, bodyBytes)
                if (bodyBytes != null) {
                    conn.outputStream.use { it.write(bodyBytes) }
                } else {
                    conn.connect()
                }
                handedOff = true
                return conn
            } catch (err: IOException) {
                lastError = err
            } finally {
                if (!handedOff) {
                    runCatching { conn.disconnect() }
                }
            }
        }
        throw lastError ?: IOException("Failed to connect to ${target.asciiHost}")
    }

    private fun configureRequestOnConnection(
        conn: HttpURLConnection,
        request: HttpRequest,
        bodyBytes: ByteArray?,
    ) {
        conn.requestMethod = request.method
        val hasAcceptEncoding =
            request.headers.keys.any { it.equals(HEADER_ACCEPT_ENCODING, ignoreCase = true) }
        if (!hasAcceptEncoding) {
            conn.setRequestProperty(
                HEADER_ACCEPT_ENCODING,
                if (request.expectBinary) ENCODING_IDENTITY else ENCODING_GZIP,
            )
        }
        request.headers.forEach { (key, value) ->
            if (!key.equals(HEADER_HOST, ignoreCase = true)) {
                conn.setRequestProperty(key, value)
            }
        }
        if (bodyBytes != null) {
            if (
                request.headers.keys.none {
                    it.equals(HEADER_CONTENT_TYPE_LOCAL, ignoreCase = true)
                }
            ) {
                conn.setRequestProperty(HEADER_CONTENT_TYPE_LOCAL, CONTENT_TYPE_JSON_LOCAL)
            }
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(bodyBytes.size)
        }
    }

    private suspend fun readBoundedResponseBytes(
        conn: HttpURLConnection,
        request: HttpRequest,
        statusCode: Int,
        headers: Map<String, String>,
    ): ByteArray {
        val maxBytes = resolveResponseByteBudget(request, statusCode)
        val isGzipped = !request.expectBinary && isGzipContentEncoding(headers)
        val declaredLength = conn.contentLengthLong
        if (hasEmptyResponseBody(statusCode, declaredLength)) {
            return ByteArray(0)
        }
        if (!isGzipped && declaredLength > maxBytes.toLong()) {
            throw ResponseTooLargeException(maxBytes = maxBytes.toLong())
        }
        val rawStream = selectResponseStream(conn, statusCode) ?: return ByteArray(0)
        val boundedWireStream = BoundedInputStream(rawStream, maxBytes.toLong())
        val decodedStream =
            if (isGzipped) {
                try {
                    GZIPInputStream(boundedWireStream)
                } catch (err: IOException) {
                    runCatching { boundedWireStream.close() }
                    throw err
                }
            } else {
                boundedWireStream
            }
        return drainStreamWithinBudget(decodedStream, maxBytes)
    }

    private suspend fun drainStreamWithinBudget(stream: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(READ_BUFFER_CHARS)
        stream.use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buf)
                if (read == -1) break
                if (read > maxBytes - out.size()) {
                    throw ResponseTooLargeException(maxBytes = maxBytes.toLong())
                }
                out.write(buf, 0, read)
            }
        }
        return out.toByteArray()
    }
}

internal fun resolveResponseByteBudget(request: HttpRequest, statusCode: Int): Int {
    val isSuccess = statusCode in HTTP_OK_START..HTTP_OK_END
    val defaultBound =
        if (request.expectBinary && isSuccess) {
            MAX_MEDIA_BYTES
        } else {
            DEFAULT_PUBLIC_TEXT_MAX_BYTES
        }
    return request.maxResponseBytes?.coerceIn(1, defaultBound) ?: defaultBound
}

internal class BoundedInputStream(
    private val delegate: InputStream,
    private val maxBytes: Long,
) : InputStream() {
    private var bytesRead: Long = 0L

    override fun read(): Int {
        val next = delegate.read()
        if (next != -1) {
            bytesRead += 1L
            if (bytesRead > maxBytes) {
                throw ResponseTooLargeException(maxBytes = maxBytes)
            }
        }
        return next
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val count = delegate.read(b, off, len)
        if (count > 0) {
            bytesRead += count.toLong()
            if (bytesRead > maxBytes) {
                throw ResponseTooLargeException(maxBytes = maxBytes)
            }
        }
        return count
    }

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()
}

internal fun isGzipContentEncoding(headers: Map<String, String>): Boolean =
    headers.entries.any { (k, v) ->
        k.equals(HEADER_CONTENT_ENCODING, ignoreCase = true) &&
            (v.trim().equals(ENCODING_GZIP, ignoreCase = true) ||
                v.trim().equals(ENCODING_X_GZIP, ignoreCase = true))
    }

internal fun hasEmptyResponseBody(statusCode: Int, declaredLength: Long): Boolean =
    statusCode == HTTP_NO_CONTENT || statusCode == HTTP_NOT_MODIFIED || declaredLength == 0L

internal fun selectResponseStream(conn: HttpURLConnection, statusCode: Int): InputStream? =
    if (statusCode in HTTP_OK_START..HTTP_OK_END) conn.inputStream else conn.errorStream

internal fun flattenUrlConnectionHeaders(conn: HttpURLConnection): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for ((key, values) in conn.headerFields.orEmpty()) {
        if (key != null && !values.isNullOrEmpty()) {
            result[key] = values.last()
        }
    }
    return result
}

private fun formatAddressForUrlHost(address: InetAddress): String {
    val raw = address.hostAddress?.substringBefore('%').orEmpty()
    return if (address is Inet6Address || ':' in raw) "[$raw]" else raw
}

internal fun isSniEligibleHostname(asciiHost: String): Boolean {
    val clean = asciiHost.trim('[', ']')
    if (clean.isEmpty() || ':' in clean) return false
    return !IPV4_LITERAL_REGEX.matches(clean)
}

private val IPV4_LITERAL_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
private const val HTTPS_SCHEME = "https"
private const val HTTP_DEFAULT_PORT = 80
private const val HTTPS_DEFAULT_PORT = 443
private const val HTTP_NO_CONTENT = 204
private const val HTTP_NOT_MODIFIED = 304
private const val HEADER_HOST = "Host"
private const val HEADER_LOCATION = "Location"
private const val HEADER_ACCEPT_ENCODING = "Accept-Encoding"
private const val HEADER_CONTENT_ENCODING = "Content-Encoding"
private const val HEADER_CONTENT_TYPE_LOCAL = "Content-Type"
private const val CONTENT_TYPE_JSON_LOCAL = "application/json"
private const val ENCODING_GZIP = "gzip"
private const val ENCODING_X_GZIP = "x-gzip"
private const val ENCODING_IDENTITY = "identity"
private const val HASH_MULTIPLIER = 31
private const val MAX_PINNED_ADDRESS_ATTEMPTS = 4
private const val STREAM_COPY_BUFFER_BYTES = 16_384
private const val PUBLIC_CONNECT_TIMEOUT_MS = 10_000
private const val PUBLIC_READ_TIMEOUT_MS = 15_000
private const val PUBLIC_STREAM_READ_TIMEOUT_MS = 60_000
internal const val DEFAULT_PUBLIC_TEXT_MAX_BYTES = 10 * 1024 * 1024

package com.grayvines.roche.data.remote

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val expectBinary: Boolean = false,
    val requirePublicIp: Boolean = false,
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

fun interface VpnTransportCheck {
    fun isActive(): Boolean
}

class HttpUrlConnectionTransport(
    private val vpnCheck: VpnTransportCheck = VpnTransportCheck { true }
) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse =
        withContext(Dispatchers.IO) { executeFollowingRedirects(request, coroutineContext[Job]) }

    private fun executeFollowingRedirects(
        initialRequest: HttpRequest,
        job: Job?,
    ): HttpResponse {
        var currentRequest = initialRequest
        repeat(MAX_REDIRECTS + 1) {
            job?.ensureActive()
            val (response, redirectLocation) = executeSingleRequest(currentRequest, job)
            val nextUrl = redirectLocation?.let { resolveRedirectUrl(currentRequest.url, it) }
            if (nextUrl == null) {
                return response
            }
            currentRequest = buildRedirectedRequest(currentRequest, response.statusCode, nextUrl)
        }
        job?.ensureActive()
        return executeSingleRequest(currentRequest, job).first
    }

    private fun executeSingleRequest(
        request: HttpRequest,
        job: Job?,
    ): Pair<HttpResponse, String?> {
        val connection = openConfiguredConnection(request)
        val cancelHandle = job?.invokeOnCompletion { cause ->
            if (cause != null) runCatching { connection.disconnect() }
        }
        try {
            val status = connection.responseCode
            val redirectLocation =
                if (status in REDIRECT_STATUS_CODES) {
                    connection.getHeaderField("Location")?.trim()?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            if (redirectLocation != null) {
                return HttpResponse(statusCode = status, body = "") to redirectLocation
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
            cancelHandle?.dispose()
            connection.disconnect()
        }
    }

    private fun openConfiguredConnection(request: HttpRequest): HttpURLConnection {
        val targetUrl =
            if (request.requirePublicIp) {
                PublicUrlValidator.upgradePublicHttp(request.url).also {
                    PublicUrlValidator.ensurePublicTarget(it)
                }
            } else {
                PublicUrlValidator.ensureSafeServerEndpoint(request.url, vpnCheck::isActive)
                PublicUrlValidator.ensureResolvedServerTarget(request.url)
                request.url
            }
        val sanitizedUrl = targetUrl.trim().replace(" ", "%20")
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
    ): InputStream? {
        val rawStream = if (isSuccess) connection.inputStream else connection.errorStream
        val isGzip = connection.contentEncoding?.equals("gzip", ignoreCase = true) == true
        return if (rawStream != null && isGzip) GZIPInputStream(rawStream) else rawStream
    }

    private companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }
}

private val REDIRECT_SWITCH_TO_GET_CODES = setOf(301, 302, 303)
internal const val HTTP_SCHEME_HTTPS = "https://"
internal const val HTTP_SCHEME_HTTP = "http://"
internal const val HEADER_USER_AGENT = "User-Agent"
internal const val HEADER_ACCEPT = "Accept"
internal const val HEADER_AUTHORIZATION = "Authorization"
internal const val HTTP_OK_START = 200
internal const val HTTP_OK_END = 299
private const val READ_BUFFER_CHARS = 8_192
private const val MAX_RESPONSE_CHARS = 64 * 1024 * 1024
internal const val MAX_MEDIA_BYTES = 50 * 1024 * 1024
internal const val MAX_AUDIO_ENCLOSURE_BYTES = 500L * 1024L * 1024L
private const val AUDIO_CONNECT_TIMEOUT_MS = 15_000
private const val AUDIO_READ_TIMEOUT_MS = 60_000
private const val AUDIO_MAX_REDIRECTS = 5
private val AUDIO_REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

internal fun buildRedirectedRequest(
    currentRequest: HttpRequest,
    statusCode: Int,
    nextUrl: String,
): HttpRequest {
    val switchToGet =
        statusCode in REDIRECT_SWITCH_TO_GET_CODES &&
            currentRequest.method != "GET" &&
            currentRequest.method != "HEAD"
    val sameOrigin = isSameOriginHost(currentRequest.url, nextUrl)
    val nextHeaders =
        if (sameOrigin) {
            currentRequest.headers
        } else {
            currentRequest.headers.filterKeys {
                !it.equals(HEADER_AUTHORIZATION, ignoreCase = true)
            }
        }
    return currentRequest.copy(
        method = if (switchToGet) "GET" else currentRequest.method,
        url = nextUrl,
        headers = nextHeaders,
        body = if (switchToGet) null else currentRequest.body,
        requirePublicIp = currentRequest.requirePublicIp || !sameOrigin,
    )
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

internal fun isSameOriginHost(firstUrl: String, secondUrl: String): Boolean = runCatching {
    val origin = URI.create(firstUrl.trim()).toURL()
    val target = URI.create(secondUrl.trim()).toURL()
    val originHost = origin.host
    val targetHost = target.host
    val sameProtocol = origin.protocol.equals(target.protocol, ignoreCase = true)
    val originPort = origin.port.takeIf { it != -1 } ?: origin.defaultPort
    val targetPort = target.port.takeIf { it != -1 } ?: target.defaultPort
    originHost.isNotBlank() &&
        originHost.equals(targetHost, ignoreCase = true) &&
        sameProtocol &&
        originPort != -1 &&
        originPort == targetPort
}
    .getOrDefault(false)

internal fun readBoundedBytes(
    input: InputStream,
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

internal fun readBoundedText(input: InputStream): String =
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

internal fun writeBytesAtomicallyToFile(bytes: ByteArray, targetFile: File): Boolean {
    targetFile.parentFile?.mkdirs()
    val temp = File(targetFile.parentFile, "${targetFile.name}.${System.nanoTime()}.tmp")
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
    targetFile: File,
    maxBytes: Long = MAX_AUDIO_ENCLOSURE_BYTES,
    initialHeaders: Map<String, String> = emptyMap(),
    requirePublicIp: Boolean = true,
    vpnCheck: VpnTransportCheck = VpnTransportCheck { true },
    job: Job? = null,
): Boolean {
    var currentUrl: String? = initialUrl.trim()
    var currentHeaders = initialHeaders
    var currentRequirePublicIp = requirePublicIp
    var step = 0
    var success = false
    while (currentUrl != null && step <= AUDIO_MAX_REDIRECTS && !success) {
        job?.ensureActive()
        val activeUrl =
            if (currentRequirePublicIp) {
                PublicUrlValidator.upgradePublicHttp(currentUrl)
            } else {
                currentUrl
            }
        val nextOrDone =
            executeAudioStreamAttempt(
                url = activeUrl,
                targetFile = targetFile,
                maxBytes = maxBytes,
                headers = currentHeaders,
                requirePublicIp = currentRequirePublicIp,
                vpnCheck = vpnCheck,
                job = job,
            )
        when (nextOrDone) {
            is AudioStreamStepResult.Redirect -> {
                if (!isSameOriginHost(activeUrl, nextOrDone.nextUrl)) {
                    currentHeaders = currentHeaders.filterKeys {
                        !it.equals(HEADER_AUTHORIZATION, ignoreCase = true)
                    }
                    currentRequirePublicIp = true
                }
                currentUrl = nextOrDone.nextUrl
            }
            AudioStreamStepResult.Success -> {
                success = true
            }
            AudioStreamStepResult.Failed -> {
                currentUrl = null
            }
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

@Suppress("LongParameterList")
private fun executeAudioStreamAttempt(
    url: String,
    targetFile: File,
    maxBytes: Long,
    headers: Map<String, String>,
    requirePublicIp: Boolean,
    vpnCheck: VpnTransportCheck,
    job: Job?,
): AudioStreamStepResult {
    val validated = runCatching {
        if (requirePublicIp) {
            PublicUrlValidator.ensurePublicTarget(url)
        } else {
            PublicUrlValidator.ensureSafeServerEndpoint(url, vpnCheck::isActive)
            PublicUrlValidator.ensureResolvedServerTarget(url)
        }
    }
        .isSuccess
    if (!validated) return AudioStreamStepResult.Failed
    val connection =
        (URI.create(url.replace(" ", "%20")).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "GET"
            connectTimeout = AUDIO_CONNECT_TIMEOUT_MS
            readTimeout = AUDIO_READ_TIMEOUT_MS
            setRequestProperty(HEADER_USER_AGENT, "Roche-Android/1.0")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
    val cancelHandle = job?.invokeOnCompletion { cause ->
        if (cause != null) runCatching { connection.disconnect() }
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
                if (copyStreamAtomicallyToFile(connection, targetFile, maxBytes, job)) {
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
        cancelHandle?.dispose()
        connection.disconnect()
    }
}

private fun copyStreamAtomicallyToFile(
    connection: HttpURLConnection,
    targetFile: File,
    maxBytes: Long,
    job: Job?,
): Boolean {
    targetFile.parentFile?.mkdirs()
    val tempFile = File(targetFile.parentFile, "${targetFile.name}.${System.nanoTime()}.tmp")
    return try {
        val rawStream = connection.inputStream
        val stream =
            if (connection.contentEncoding?.equals("gzip", ignoreCase = true) == true) {
                GZIPInputStream(rawStream)
            } else {
                rawStream
            }
        val total = stream.use { input ->
            tempFile.outputStream().use { out ->
                copyBoundStreamBytes(input, out, maxBytes, job)
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
    input: InputStream,
    out: OutputStream,
    maxBytes: Long,
    job: Job?,
): Long {
    var total = 0L
    val buffer = ByteArray(READ_BUFFER_CHARS)
    var read = input.read(buffer)
    while (read != -1) {
        job?.ensureActive()
        total += read
        if (total > maxBytes) {
            throw IOException("Audio stream exceeds maximum allowed size")
        }
        out.write(buffer, 0, read)
        read = input.read(buffer)
    }
    return total
}

internal suspend fun streamTransportResponseToFile(
    transport: HttpTransport,
    trimmedUrl: String,
    targetFile: File,
    maxBytes: Long,
    headers: Map<String, String>,
    requirePublicIp: Boolean = false,
): Boolean {
    val response =
        transport.execute(
            HttpRequest(
                method = "GET",
                url = trimmedUrl,
                headers = headers,
                expectBinary = true,
                requirePublicIp = requirePublicIp,
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

internal fun itemEndpointUrl(serverUrl: String, itemId: String): String =
    "${serverUrl.trimEnd('/')}/api/v1/items/${encodePathSegment(itemId)}"

internal fun encodePathSegment(raw: String): String =
    URLEncoder.encode(raw, StandardCharsets.UTF_8.name()).replace("+", "%20")

internal fun authHeaders(token: String): Map<String, String> =
    if (token.isBlank()) {
        emptyMap()
    } else {
        mapOf(HEADER_AUTHORIZATION to "Bearer $token")
    }

internal fun ensureSuccess(response: HttpResponse) {
    if (response.statusCode in HTTP_OK_START..HTTP_OK_END) return
    val (title, detail) = parseProblemDetails(response.body, response.statusCode)
    throw ProblemDetailsException(statusCode = response.statusCode, title = title, detail = detail)
}

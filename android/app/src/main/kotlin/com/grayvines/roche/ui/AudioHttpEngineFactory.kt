package com.grayvines.roche.ui

import android.content.Context
import android.net.Uri
import android.net.http.HttpEngine
import android.net.http.HttpException
import android.net.http.UrlRequest
import android.net.http.UrlResponseInfo
import android.os.Build
import android.os.ext.SdkExtensions
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
import androidx.media3.datasource.TransferListener
import com.grayvines.roche.data.remote.MAX_REDIRECTS
import com.grayvines.roche.data.remote.PublicUrlValidator
import com.grayvines.roche.data.remote.resolveRedirectUrl
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal sealed interface AudioHopOutcome {
    data object Final : AudioHopOutcome

    data class Redirect(val location: String) : AudioHopOutcome
}

internal class AudioRedirectCache(
    private val ttlNanos: Long = AUDIO_REDIRECT_TTL_NANOS,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    internal data class Entry(val finalUrl: String, val expiresAtNanos: Long)

    private val entries =
        object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Entry>?
            ): Boolean = size > MAX_AUDIO_REDIRECT_CACHE
        }

    @Synchronized
    fun getFresh(url: String): String? {
        val entry = entries[url] ?: return null
        return if (clockNanos() < entry.expiresAtNanos) {
            entry.finalUrl
        } else {
            entries.remove(url)
            null
        }
    }

    @Synchronized
    fun put(url: String, finalUrl: String) {
        val expiresAt = clockNanos() + ttlNanos
        entries[url] = Entry(finalUrl = finalUrl, expiresAtNanos = expiresAt)
        entries[finalUrl] = Entry(finalUrl = finalUrl, expiresAtNanos = expiresAt)
    }
}

@OptIn(UnstableApi::class)
internal fun createAudioDataSourceFactory(
    context: Context,
    httpEngine: HttpEngine,
    @Suppress("UNUSED_PARAMETER") callbackExecutor: Executor,
): DataSource.Factory {
    val useHttpEngine =
        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= MIN_S_EXT_HTTP_ENGINE
    val connectionOpener: (URL) -> HttpURLConnection =
        if (useHttpEngine) {
            { url -> httpEngine.openConnection(url) as HttpURLConnection }
        } else {
            { url -> url.openConnection() as HttpURLConnection }
        }
    val safeHttpFactory = DataSource.Factory {
        SafeRedirectHttpDataSource(
            openConnection = connectionOpener,
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
    private var bytesToRead: Long = C.LENGTH_UNSET.toLong()
    private var bytesRead: Long = 0L

    override fun open(dataSpec: DataSpec): Long {
        bytesRead = 0L
        bytesToRead = C.LENGTH_UNSET.toLong()
        transferInitializing(dataSpec)

        val conn =
            try {
                openValidatedConnection(dataSpec)
            } catch (err: IOException) {
                closeConnectionQuietly()
                throw err
            } catch (err: IllegalArgumentException) {
                closeConnectionQuietly()
                throw IOException("Blocked unsafe audio target", err)
            }

        connection = conn
        val responseCode = conn.responseCode
        val responseMessage = conn.responseMessage
        val headers = conn.headerFields ?: emptyMap()

        if (responseCode < 200 || responseCode > 299) {
            closeConnectionQuietly()
            if (responseCode == 416) {
                val documentSize = HttpUtil.getDocumentSize(conn.getHeaderField("Content-Range"))
                if (dataSpec.position == documentSize) {
                    opened = true
                    transferStarted(dataSpec)
                    return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else 0L
                }
                throw DataSourceException(
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                )
            }
            throw HttpDataSource.InvalidResponseCodeException(
                responseCode,
                responseMessage,
                null,
                headers,
                dataSpec,
                ByteArray(0),
            )
        }

        val bytesToSkip =
            if (responseCode == 200 && dataSpec.position != 0L) dataSpec.position else 0L
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            bytesToRead = dataSpec.length
        } else {
            val contentLength =
                HttpUtil.getContentLength(
                    conn.getHeaderField("Content-Length"),
                    conn.getHeaderField("Content-Range"),
                )
            bytesToRead =
                if (contentLength != C.LENGTH_UNSET.toLong()) {
                    (contentLength - bytesToSkip).coerceAtLeast(0L)
                } else {
                    C.LENGTH_UNSET.toLong()
                }
        }

        try {
            val stream = conn.inputStream
            skipFully(stream, bytesToSkip)
            inputStream = stream
        } catch (err: IOException) {
            closeConnectionQuietly()
            throw err
        }

        opened = true
        transferStarted(dataSpec)
        return bytesToRead
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val remaining = bytesToRead - bytesRead
            if (remaining <= 0L) return C.RESULT_END_OF_INPUT
            val toRead = minOf(length.toLong(), remaining).toInt()
            val readCount = inputStream?.read(buffer, offset, toRead) ?: C.RESULT_END_OF_INPUT
            if (readCount == -1) return C.RESULT_END_OF_INPUT
            bytesRead += readCount
            bytesTransferred(readCount)
            return readCount
        }
        val readCount = inputStream?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        if (readCount == -1) return C.RESULT_END_OF_INPUT
        bytesRead += readCount
        bytesTransferred(readCount)
        return readCount
    }

    override fun getUri(): Uri? = currentUri

    override fun getResponseHeaders(): Map<String, List<String>> =
        connection?.headerFields ?: emptyMap()

    override fun close() {
        try {
            inputStream?.close()
        } finally {
            inputStream = null
            closeConnectionQuietly()
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private fun openValidatedConnection(dataSpec: DataSpec): HttpURLConnection {
        val (conn, finalUrl) =
            openValidatedUrl(
                rawUrl = dataSpec.uri.toString().trim(),
                httpMethod = dataSpec.httpMethodString,
                httpBody = dataSpec.httpBody,
                requestHeaders = dataSpec.httpRequestHeaders,
                position = dataSpec.position,
                length = dataSpec.length,
            )
        currentUri = finalUrl.toUri()
        return conn
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

        for (hop in 0..maxRedirects) {
            val conn =
                openSingleConnection(
                    url = currentUrl,
                    httpMethod = method,
                    httpBody = body,
                    requestHeaders = requestHeaders,
                    position = position,
                    length = length,
                )
            val responseCode = conn.responseCode
            if (responseCode in REDIRECT_STATUS_CODES) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (hop >= maxRedirects) {
                    throw IOException("Too many audio redirects")
                }
                val nextRaw =
                    resolveRedirectUrl(currentUrl, location)
                        ?: throw IOException("Blocked or invalid audio redirect URL")
                currentUrl = validateUrl(nextRaw)
                if (responseCode in 301..303 && method == "POST") {
                    method = "GET"
                    body = null
                }
                continue
            }
            return conn to currentUrl
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
        val conn = openConnection(URI.create(url).toURL())
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
        return conn
    }

    private fun skipFully(stream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val skipBuffer = ByteArray(4096)
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
        val REDIRECT_STATUS_CODES = setOf(300, 301, 302, 303, 307, 308)
    }
}

@OptIn(UnstableApi::class)
internal fun wrapWithPostRedirectCheck(
    delegateFactory: DataSource.Factory,
    validateFinalUrl: (String) -> Unit = { PublicUrlValidator.ensurePublicTarget(it) },
): DataSource.Factory = DataSource.Factory {
    val delegate = delegateFactory.createDataSource()
    object : DataSource {
        override fun addTransferListener(transferListener: TransferListener) {
            delegate.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val length = delegate.open(dataSpec)
            val openedUri = delegate.uri?.toString()?.trim()
            if (!openedUri.isNullOrEmpty()) {
                try {
                    validateFinalUrl(openedUri)
                } catch (err: IOException) {
                    runCatching { delegate.close() }
                    throw err
                } catch (err: IllegalArgumentException) {
                    runCatching { delegate.close() }
                    throw IOException("Blocked unsafe redirected audio target", err)
                }
            }
            return length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)

        override fun getUri(): Uri? = delegate.uri

        override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

        override fun close() {
            delegate.close()
        }
    }
}

@OptIn(UnstableApi::class)
internal fun resolveSafeHttpDataSpec(
    dataSpec: DataSpec,
    resolveAddresses: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    redirectResolver: ((String) -> String)? = null,
): DataSpec {
    val rawUrl = dataSpec.uri.toString().trim()
    val resolvedUrl =
        redirectResolver?.invoke(rawUrl)
            ?: resolveSafeHttpPlaybackUrl(
                rawUrl = rawUrl,
                resolveAddresses = resolveAddresses,
            )
    return if (resolvedUrl != rawUrl) {
        dataSpec.buildUpon().setUri(resolvedUrl.toUri()).build()
    } else {
        dataSpec
    }
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

internal fun resolveCachedAudioUrl(
    rawUrl: String,
    cache: AudioRedirectCache,
    validateUrl: (String) -> String = { resolveSafeHttpPlaybackUrl(it) },
    probeHop: (String) -> AudioHopOutcome,
): String {
    val startUrl = validateUrl(rawUrl)
    val cached = cache.getFresh(startUrl)
    if (cached != null) {
        return validateUrl(cached)
    }
    val resolved =
        resolveAudioRedirectChain(
            initialUrl = startUrl,
            maxRedirects = MAX_REDIRECTS,
            validateUrl = validateUrl,
            probeHop = probeHop,
            initialAlreadyValidated = true,
        )
    cache.put(startUrl, resolved)
    return resolved
}

internal fun resolveAudioRedirectChain(
    initialUrl: String,
    maxRedirects: Int = MAX_REDIRECTS,
    validateUrl: (String) -> String = { resolveSafeHttpPlaybackUrl(it) },
    probeHop: (String) -> AudioHopOutcome,
    initialAlreadyValidated: Boolean = false,
): String {
    var currentUrl = if (initialAlreadyValidated) initialUrl else validateUrl(initialUrl)
    for (hop in 0..maxRedirects) {
        when (val outcome = probeHop(currentUrl)) {
            AudioHopOutcome.Final -> {
                return currentUrl
            }
            is AudioHopOutcome.Redirect -> {
                if (hop < maxRedirects) {
                    val nextRaw =
                        resolveRedirectUrl(currentUrl, outcome.location)
                            ?: throw IOException("Blocked or invalid audio redirect URL")
                    currentUrl = validateUrl(nextRaw)
                }
            }
        }
    }
    throw IOException("Too many audio redirects")
}

internal fun probeAudioRedirectHop(
    url: String,
    httpEngine: HttpEngine,
    executor: Executor,
): AudioHopOutcome {
    val future = CompletableFuture<AudioHopOutcome>()
    val request =
        httpEngine
            .newUrlRequestBuilder(url, executor, AudioHopProbeCallback(future))
            .setHttpMethod("GET")
            .addHeader("User-Agent", ROCHE_AUDIO_USER_AGENT)
            .addHeader("Range", "bytes=0-0")
            .build()
    request.start()
    val failure: IOException =
        try {
            return future.get(AUDIO_HTTP_CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            request.cancel()
            Thread.currentThread().interrupt()
            InterruptedIOException("Audio redirect check interrupted").apply {
                initCause(interrupted)
            }
        } catch (timeout: TimeoutException) {
            request.cancel()
            SocketTimeoutException("Audio redirect check timed out").apply { initCause(timeout) }
        } catch (exec: ExecutionException) {
            IOException("Audio redirect check failed", exec.cause ?: exec)
        }
    throw failure
}

internal class AudioHopProbeCallback(private val future: CompletableFuture<AudioHopOutcome>) :
    UrlRequest.Callback {
    override fun onRedirectReceived(
        request: UrlRequest,
        info: UrlResponseInfo,
        newLocationUrl: String,
    ) {
        future.complete(AudioHopOutcome.Redirect(newLocationUrl))
        request.cancel()
    }

    override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
        future.complete(AudioHopOutcome.Final)
        request.cancel()
    }

    override fun onReadCompleted(
        request: UrlRequest,
        info: UrlResponseInfo,
        byteBuffer: ByteBuffer,
    ) {
        request.cancel()
    }

    override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
        future.complete(AudioHopOutcome.Final)
    }

    override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: HttpException) {
        future.completeExceptionally(error)
    }

    override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
        if (!future.isDone) {
            future.completeExceptionally(IOException("Audio redirect probe cancelled"))
        }
    }
}

internal const val ROCHE_AUDIO_USER_AGENT = "Roche-Android/1.0"
internal const val MIN_S_EXT_HTTP_ENGINE = 7
internal const val AUDIO_HTTP_CONNECT_TIMEOUT_MS = 15_000
internal const val AUDIO_HTTP_READ_TIMEOUT_MS = 30_000
private const val MAX_AUDIO_REDIRECT_CACHE = 64
private const val AUDIO_REDIRECT_TTL_NANOS = 120L * 1_000_000_000L

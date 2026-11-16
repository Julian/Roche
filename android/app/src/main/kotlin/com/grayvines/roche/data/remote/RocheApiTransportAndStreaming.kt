@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data.remote

import android.content.Context
import android.net.http.HttpEngine
import android.net.http.HttpException
import android.net.http.UploadDataProvider
import android.net.http.UploadDataSink
import android.net.http.UrlRequest
import android.net.http.UrlResponseInfo
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
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

    suspend fun streamToFile(
        request: HttpRequest,
        targetFile: File,
        maxBytes: Long,
    ): Boolean =
        streamTransportResponseToFile(
            this,
            request.url,
            targetFile,
            maxBytes,
            request.headers,
            request.requirePublicIp,
        )
}

fun interface VpnTransportCheck {
    fun isActive(): Boolean
}

class HttpEngineTransport(
    context: Context,
    private val vpnCheck: VpnTransportCheck = VpnTransportCheck { true },
) : HttpTransport, AutoCloseable {
    private var lockedStoragePath: String? = null

    private val httpEngine: HttpEngine = buildConfiguredHttpEngine(context)

    private val executor: ScheduledExecutorService =
        Executors.newScheduledThreadPool(HTTP_ENGINE_CALLBACK_THREADS) { runnable ->
            Thread(runnable, "roche-http-engine").apply { isDaemon = true }
        }

    internal val sharedHttpEngine: HttpEngine
        get() = httpEngine

    internal val sharedCallbackExecutor: Executor
        get() = executor

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

    override fun close() {
        runCatching { httpEngine.shutdown() }
        lockedStoragePath?.let(::releaseHttpEngineStoragePath)
        lockedStoragePath = null
        executor.shutdown()
    }

    private fun buildConfiguredHttpEngine(context: Context): HttpEngine {
        val appContext = context.applicationContext ?: context
        val storagePath = prepareHttpEngineStoragePath(appContext.cacheDir)
        if (storagePath != null) {
            val persistentEngine = runCatching {
                createBaseHttpEngineBuilder(appContext)
                    .setStoragePath(storagePath)
                    .setEnableHttpCache(
                        HttpEngine.Builder.HTTP_CACHE_DISK_NO_HTTP,
                        HTTP_ENGINE_DISK_CACHE_BYTES,
                    )
                    .build()
            }
                .getOrNull()
            if (persistentEngine != null) {
                lockedStoragePath = storagePath
                return persistentEngine
            }
            clearHttpEngineStorageDir(storagePath)
            releaseHttpEngineStoragePath(storagePath)
        }
        return createBaseHttpEngineBuilder(appContext)
            .setEnableHttpCache(
                HttpEngine.Builder.HTTP_CACHE_IN_MEMORY,
                HTTP_ENGINE_IN_MEMORY_CACHE_BYTES,
            )
            .build()
    }

    private suspend fun executeFollowingRedirects(initialRequest: HttpRequest): HttpResponse {
        var currentRequest = initialRequest
        var finalResponse: HttpResponse? = null
        var hop = 0
        while (hop <= MAX_REDIRECTS && finalResponse == null) {
            currentCoroutineContext().ensureActive()
            val sanitizedUrl = validateAndSanitizeRequestUrl(currentRequest, vpnCheck)
            val effectiveRequest = currentRequest.copy(url = sanitizedUrl)
            val outcome = executeSingleBufferedHop(sanitizedUrl, effectiveRequest)
            val nextRequest =
                resolveNextRedirectHop(
                    effectiveRequest = effectiveRequest,
                    statusCode = outcome.response.statusCode,
                    location = outcome.redirectLocation,
                    hop = hop,
                    maxRedirects = MAX_REDIRECTS,
                )
            if (nextRequest == null) {
                finalResponse = outcome.response
            } else {
                currentRequest = nextRequest
            }
            hop++
        }
        return checkNotNull(finalResponse)
    }

    private suspend fun executeSingleBufferedHop(
        sanitizedUrl: String,
        request: HttpRequest,
    ): BufferedHopOutcome = suspendCancellableCoroutine { continuation ->
        val callbackRef = AtomicReference<BufferedUrlRequestCallback?>(null)
        val watchdog = HopWatchdog(executor) { callbackRef.get()?.abortWithTimeout() }
        val callback =
            BufferedUrlRequestCallback(
                httpRequest = request,
                watchdog = watchdog,
                continuation = continuation,
            )
        callbackRef.set(callback)
        continuation.invokeOnCancellation { callback.cancelFromCoroutine() }
        if (continuation.isActive) {
            runCatching { buildConfiguredUrlRequest(sanitizedUrl, request, callback) }
                .onSuccess { urlRequest ->
                    if (continuation.isActive) {
                        watchdog.arm(CONNECT_TIMEOUT_MS)
                        runCatching { urlRequest.start() }
                            .onSuccess { callback.markRequestStarted(urlRequest) }
                            .onFailure { error ->
                                watchdog.cancel()
                                callback.failStart(error)
                            }
                    }
                }
                .onFailure { error -> callback.failStart(error) }
        }
    }

    private suspend fun streamFollowingRedirects(
        initialRequest: HttpRequest,
        targetFile: File,
        maxBytes: Long,
    ): Boolean {
        var currentRequest: HttpRequest? = initialRequest
        var hop = 0
        var succeeded = false
        while (currentRequest != null && hop <= AUDIO_MAX_REDIRECTS && !succeeded) {
            currentCoroutineContext().ensureActive()
            val activeRequest = currentRequest
            val sanitizedUrl = runCatching {
                validateAndSanitizeRequestUrl(activeRequest, vpnCheck)
            }
                .getOrNull()
            val effectiveRequest = sanitizedUrl?.let { activeRequest.copy(url = it) }
            val hopResult =
                if (sanitizedUrl == null || effectiveRequest == null) {
                    StreamHopResult.Failed
                } else {
                    executeSingleStreamHop(sanitizedUrl, effectiveRequest, targetFile, maxBytes)
                }
            when (hopResult) {
                StreamHopResult.Success -> {
                    succeeded = true
                }
                is StreamHopResult.Redirect -> {
                    currentRequest = effectiveRequest?.let { req ->
                        resolveNextRedirectHop(
                            effectiveRequest = req,
                            statusCode = hopResult.statusCode,
                            location = hopResult.location,
                            hop = hop,
                            maxRedirects = AUDIO_MAX_REDIRECTS,
                        )
                    }
                }
                StreamHopResult.Failed -> {
                    currentRequest = null
                }
            }
            hop++
        }
        return succeeded
    }

    private suspend fun executeSingleStreamHop(
        sanitizedUrl: String,
        request: HttpRequest,
        targetFile: File,
        maxBytes: Long,
    ): StreamHopResult {
        val opened = openPartFileChannel(targetFile) ?: return StreamHopResult.Failed
        val sink =
            StreamFileSink(
                partFile = opened.first,
                targetFile = targetFile,
                channel = opened.second,
                maxBytes = maxBytes,
            )
        return suspendCancellableCoroutine { continuation ->
            val callbackRef = AtomicReference<StreamingUrlRequestCallback?>(null)
            val watchdog = HopWatchdog(executor) { callbackRef.get()?.abortRequest() }
            val callback =
                StreamingUrlRequestCallback(
                    sink = sink,
                    watchdog = watchdog,
                    continuation = continuation,
                )
            callbackRef.set(callback)
            continuation.invokeOnCancellation { callback.cancelFromCoroutine() }
            if (!continuation.isActive) {
                callback.closeAndDeletePartFile()
            } else {
                runCatching { buildConfiguredUrlRequest(sanitizedUrl, request, callback) }
                    .onSuccess { urlRequest ->
                        if (!continuation.isActive) {
                            callback.closeAndDeletePartFile()
                        } else {
                            watchdog.arm(AUDIO_CONNECT_TIMEOUT_MS)
                            runCatching { urlRequest.start() }
                                .onSuccess { callback.markRequestStarted(urlRequest) }
                                .onFailure {
                                    watchdog.cancel()
                                    callback.cleanupAndFail()
                                }
                        }
                    }
                    .onFailure { callback.cleanupAndFail() }
            }
        }
    }

    private fun buildConfiguredUrlRequest(
        sanitizedUrl: String,
        request: HttpRequest,
        callback: UrlRequest.Callback,
    ): UrlRequest {
        val builder =
            httpEngine
                .newUrlRequestBuilder(sanitizedUrl, executor, callback)
                .setHttpMethod(request.method)
        request.headers.forEach { (key, value) -> builder.addHeader(key, value) }
        if (request.body != null) {
            if (request.headers.keys.none { it.equals(HEADER_CONTENT_TYPE, ignoreCase = true) }) {
                builder.addHeader(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
            }
            val payload = request.body.toByteArray(Charsets.UTF_8)
            builder.setUploadDataProvider(ByteArrayUploadDataProvider(payload), executor)
        }
        return builder.build()
    }
}

internal data class BufferedHopOutcome(
    val response: HttpResponse,
    val redirectLocation: String?,
)

internal sealed interface StreamHopResult {
    data class Redirect(val statusCode: Int, val location: String) : StreamHopResult

    data object Success : StreamHopResult

    data object Failed : StreamHopResult
}

internal data class StreamFileSink(
    val partFile: File,
    val targetFile: File,
    val channel: FileChannel,
    val maxBytes: Long,
)

internal class HopWatchdog(
    private val scheduler: ScheduledExecutorService,
    private val onTimeout: () -> Unit,
) {
    private val pendingFuture = AtomicReference<ScheduledFuture<*>?>(null)

    fun arm(timeoutMs: Long) {
        val next = runCatching {
            scheduler.schedule(onTimeout, timeoutMs, TimeUnit.MILLISECONDS)
        }
            .getOrNull()
        pendingFuture.getAndSet(next)?.cancel(false)
    }

    fun cancel() {
        pendingFuture.getAndSet(null)?.cancel(false)
    }
}

internal class ByteArrayUploadDataProvider(private val bytes: ByteArray) : UploadDataProvider() {
    private var offset: Int = 0

    override fun getLength(): Long = bytes.size.toLong()

    override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
        val count = minOf(byteBuffer.remaining(), bytes.size - offset)
        if (count > 0) {
            byteBuffer.put(bytes, offset, count)
            offset += count
        }
        uploadDataSink.onReadSucceeded(false)
    }

    override fun rewind(uploadDataSink: UploadDataSink) {
        offset = 0
        uploadDataSink.onRewindSucceeded()
    }
}

internal class BufferedUrlRequestCallback(
    private val httpRequest: HttpRequest,
    private val watchdog: HopWatchdog,
    private val continuation: CancellableContinuation<BufferedHopOutcome>,
) : UrlRequest.Callback {
    private val readBuffer: ByteBuffer = ByteBuffer.allocateDirect(DIRECT_READ_BUFFER_BYTES)
    private val scratch = ByteArray(READ_BUFFER_CHARS)
    private val output = ByteArrayOutputStream()
    private val settled = AtomicBoolean(false)
    private val abortError = AtomicReference<IOException?>(null)
    private val startedRequestRef = AtomicReference<UrlRequest?>(null)
    @Volatile private var maxAllowedBytes: Int = MAX_RESPONSE_CHARS
    @Volatile private var redirectStatus: Int = 0
    @Volatile private var redirectLocation: String? = null
    @Volatile private var redirectHeaders: Map<String, String> = emptyMap()

    fun markRequestStarted(urlRequest: UrlRequest) {
        startedRequestRef.set(urlRequest)
        if (settled.get() || abortError.get() != null || !continuation.isActive) {
            runCatching { urlRequest.cancel() }
        }
    }

    fun cancelFromCoroutine() {
        settled.set(true)
        watchdog.cancel()
        runCatching { startedRequestRef.get()?.cancel() }
    }

    fun abortWithTimeout() {
        val timeout = SocketTimeoutException("HTTP request timed out")
        abortError.compareAndSet(null, timeout)
        watchdog.cancel()
        runCatching { startedRequestRef.get()?.cancel() }
        if (settled.compareAndSet(false, true) && continuation.isActive) {
            continuation.resumeWithException(abortError.get() ?: timeout)
        }
    }

    fun failStart(error: Throwable) {
        watchdog.cancel()
        if (settled.compareAndSet(false, true) && continuation.isActive) {
            val ioError =
                error as? IOException ?: IOException("Failed to start HTTP request", error)
            continuation.resumeWithException(ioError)
        }
    }

    override fun onRedirectReceived(
        request: UrlRequest,
        info: UrlResponseInfo,
        newLocationUrl: String,
    ) {
        recordRedirectReceived(
            statusCode = info.httpStatusCode,
            headers = flattenResponseHeaders(info),
            newLocationUrl = newLocationUrl,
        )
        request.cancel()
    }

    internal fun recordRedirectReceived(
        statusCode: Int,
        headers: Map<String, String>,
        newLocationUrl: String,
    ) {
        watchdog.cancel()
        val cleanLocation = newLocationUrl.trim().takeIf { it.isNotBlank() }
        if (cleanLocation == null) {
            abortError.compareAndSet(null, IOException(REDIRECT_MISSING_LOCATION_MESSAGE))
        } else {
            redirectStatus = statusCode
            redirectHeaders = headers
            redirectLocation = cleanLocation
        }
    }

    override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
        val status = info.httpStatusCode
        val isSuccess = status in HTTP_OK_START..HTTP_OK_END
        val bound =
            if (httpRequest.expectBinary && isSuccess) MAX_MEDIA_BYTES else MAX_RESPONSE_CHARS
        maxAllowedBytes = bound
        val declaredLength = parseResponseContentLength(info)
        if (declaredLength != null && declaredLength > bound.toLong()) {
            abortError.compareAndSet(
                null,
                IOException("Response exceeds maximum allowed size of $bound bytes"),
            )
            watchdog.cancel()
            request.cancel()
        } else {
            watchdog.arm(READ_TIMEOUT_MS)
            readBuffer.clear()
            request.read(readBuffer)
        }
    }

    override fun onReadCompleted(
        request: UrlRequest,
        info: UrlResponseInfo,
        byteBuffer: ByteBuffer,
    ) {
        if (!appendReadChunk(byteBuffer)) {
            watchdog.cancel()
            request.cancel()
        } else {
            watchdog.arm(READ_TIMEOUT_MS)
            byteBuffer.clear()
            request.read(byteBuffer)
        }
    }

    internal fun appendReadChunk(byteBuffer: ByteBuffer): Boolean {
        byteBuffer.flip()
        val incoming = byteBuffer.remaining()
        val bound = maxAllowedBytes
        return if (incoming > bound - output.size()) {
            abortError.compareAndSet(
                null,
                IOException("Response exceeds maximum allowed size of $bound bytes"),
            )
            false
        } else {
            while (byteBuffer.hasRemaining()) {
                val count = minOf(byteBuffer.remaining(), scratch.size)
                byteBuffer.get(scratch, 0, count)
                output.write(scratch, 0, count)
            }
            true
        }
    }

    override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
        handleSucceeded(statusCode = info.httpStatusCode, headers = flattenResponseHeaders(info))
    }

    internal fun handleSucceeded(statusCode: Int, headers: Map<String, String>) {
        watchdog.cancel()
        if (!continuation.isActive || !settled.compareAndSet(false, true)) {
            return
        }
        val isSuccess = statusCode in HTTP_OK_START..HTTP_OK_END
        val bytes = output.toByteArray()
        val text =
            if (httpRequest.expectBinary && isSuccess) {
                ""
            } else {
                bytes.toString(Charsets.UTF_8)
            }
        continuation.resume(
            BufferedHopOutcome(
                response =
                    HttpResponse(
                        statusCode = statusCode,
                        body = text,
                        headers = headers,
                        bodyBytes = bytes.takeIf { httpRequest.expectBinary },
                    ),
                redirectLocation = null,
            )
        )
    }

    override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: HttpException) {
        watchdog.cancel()
        if (continuation.isActive && settled.compareAndSet(false, true)) {
            continuation.resumeWithException(abortError.get() ?: error)
        }
    }

    override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
        handleCanceled()
    }

    internal fun handleCanceled() {
        watchdog.cancel()
        if (!continuation.isActive || !settled.compareAndSet(false, true)) {
            return
        }
        val recordedRedirect = redirectLocation
        val recordedError = abortError.get()
        when {
            recordedRedirect != null ->
                continuation.resume(
                    BufferedHopOutcome(
                        response =
                            HttpResponse(
                                statusCode = redirectStatus,
                                body = "",
                                headers = redirectHeaders,
                            ),
                        redirectLocation = recordedRedirect,
                    )
                )
            recordedError != null -> continuation.resumeWithException(recordedError)
            else -> continuation.resumeWithException(IOException("HTTP request canceled"))
        }
    }
}

internal class StreamingUrlRequestCallback(
    private val sink: StreamFileSink,
    private val watchdog: HopWatchdog,
    private val continuation: CancellableContinuation<StreamHopResult>,
) : UrlRequest.Callback {
    private val readBuffer: ByteBuffer = ByteBuffer.allocateDirect(DIRECT_READ_BUFFER_BYTES)
    private val settled = AtomicBoolean(false)
    private val startedRequestRef = AtomicReference<UrlRequest?>(null)
    private val abortError = AtomicReference<IOException?>(null)
    @Volatile private var aborted: Boolean = false
    @Volatile private var bytesWritten: Long = 0L
    @Volatile private var redirectStatus: Int = 0
    @Volatile private var redirectLocation: String? = null

    fun markRequestStarted(urlRequest: UrlRequest) {
        startedRequestRef.set(urlRequest)
        if (settled.get() || aborted || !continuation.isActive) {
            runCatching { urlRequest.cancel() }
        }
    }

    fun cancelFromCoroutine() {
        aborted = true
        settled.set(true)
        watchdog.cancel()
        closeAndDeletePartFile()
        runCatching { startedRequestRef.get()?.cancel() }
    }

    fun abortRequest() {
        aborted = true
        watchdog.cancel()
        closeAndDeletePartFile()
        runCatching { startedRequestRef.get()?.cancel() }
        if (settled.compareAndSet(false, true) && continuation.isActive) {
            continuation.resume(StreamHopResult.Failed)
        }
    }

    fun cleanupAndFail() {
        aborted = true
        watchdog.cancel()
        closeAndDeletePartFile()
        if (settled.compareAndSet(false, true) && continuation.isActive) {
            continuation.resume(StreamHopResult.Failed)
        }
    }

    override fun onRedirectReceived(
        request: UrlRequest,
        info: UrlResponseInfo,
        newLocationUrl: String,
    ) {
        recordRedirectReceived(statusCode = info.httpStatusCode, newLocationUrl = newLocationUrl)
        request.cancel()
    }

    internal fun recordRedirectReceived(statusCode: Int, newLocationUrl: String) {
        watchdog.cancel()
        val cleanLocation = newLocationUrl.trim().takeIf { it.isNotBlank() }
        if (cleanLocation == null) {
            aborted = true
            abortError.compareAndSet(null, IOException(REDIRECT_MISSING_LOCATION_MESSAGE))
        } else {
            redirectStatus = statusCode
            redirectLocation = cleanLocation
        }
    }

    internal fun recordedAbortError(): IOException? = abortError.get()

    override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
        val status = info.httpStatusCode
        val declaredLength = parseResponseContentLength(info)
        val invalidResponse =
            status !in HTTP_OK_START..HTTP_OK_END ||
                declaredLength != null && declaredLength > sink.maxBytes
        if (invalidResponse) {
            aborted = true
            watchdog.cancel()
            request.cancel()
        } else {
            watchdog.arm(AUDIO_READ_TIMEOUT_MS)
            readBuffer.clear()
            request.read(readBuffer)
        }
    }

    override fun onReadCompleted(
        request: UrlRequest,
        info: UrlResponseInfo,
        byteBuffer: ByteBuffer,
    ) {
        if (!appendStreamChunk(byteBuffer)) {
            aborted = true
            watchdog.cancel()
            request.cancel()
        } else {
            watchdog.arm(AUDIO_READ_TIMEOUT_MS)
            byteBuffer.clear()
            request.read(byteBuffer)
        }
    }

    internal fun appendStreamChunk(byteBuffer: ByteBuffer): Boolean {
        byteBuffer.flip()
        val incoming = byteBuffer.remaining().toLong()
        if (
            exceedsByteBudget(
                bytesWritten = bytesWritten,
                incoming = incoming,
                maxBytes = sink.maxBytes,
            )
        ) {
            return false
        }
        return runCatching {
            while (byteBuffer.hasRemaining()) {
                bytesWritten += sink.channel.write(byteBuffer)
            }
        }
            .isSuccess
    }

    override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
        handleSucceeded(statusCode = info.httpStatusCode)
    }

    internal fun handleSucceeded(statusCode: Int) {
        watchdog.cancel()
        val closedCleanly = runCatching { sink.channel.close() }.isSuccess
        if (!continuation.isActive || !settled.compareAndSet(false, true)) {
            closeAndDeletePartFile()
            return
        }
        val valid =
            closedCleanly &&
                !aborted &&
                statusCode in HTTP_OK_START..HTTP_OK_END &&
                bytesWritten in 1L..sink.maxBytes
        val moved =
            valid &&
                runCatching {
                        moveFileAtomically(sink.partFile, sink.targetFile)
                        true
                    }
                    .getOrDefault(false)
        if (!moved) {
            closeAndDeletePartFile()
        }
        if (continuation.isActive) {
            continuation.resume(if (moved) StreamHopResult.Success else StreamHopResult.Failed)
        }
    }

    override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: HttpException) {
        cleanupAndFail()
    }

    override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
        handleCanceled()
    }

    internal fun handleCanceled() {
        watchdog.cancel()
        closeAndDeletePartFile()
        if (!continuation.isActive || !settled.compareAndSet(false, true)) {
            return
        }
        val next = redirectLocation
        continuation.resume(
            if (!aborted && next != null) {
                StreamHopResult.Redirect(statusCode = redirectStatus, location = next)
            } else {
                StreamHopResult.Failed
            }
        )
    }

    internal fun closeAndDeletePartFile() {
        runCatching { sink.channel.close() }
        runCatching { Files.deleteIfExists(sink.partFile.toPath()) }
    }
}

internal fun exceedsByteBudget(bytesWritten: Long, incoming: Long, maxBytes: Long): Boolean =
    maxBytes <= 0L || incoming > maxBytes - bytesWritten

internal fun validateAndSanitizeRequestUrl(
    request: HttpRequest,
    vpnCheck: VpnTransportCheck,
): String {
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
    return targetUrl.trim().replace(" ", "%20")
}

internal fun resolveNextRedirectHop(
    effectiveRequest: HttpRequest,
    statusCode: Int,
    location: String?,
    hop: Int,
    maxRedirects: Int,
): HttpRequest? =
    if (hop >= maxRedirects || location == null) {
        null
    } else {
        resolveRedirectUrl(effectiveRequest.url, location)?.let { nextUrl ->
            buildRedirectedRequest(
                currentRequest = effectiveRequest,
                statusCode = statusCode,
                nextUrl = nextUrl,
            )
        }
    }

internal fun openPartFileChannel(targetFile: File): Pair<File, FileChannel>? = runCatching {
    (targetFile.parentFile ?: targetFile.absoluteFile.parentFile)?.let { parent ->
        Files.createDirectories(parent.toPath())
        val partPath = Files.createTempFile(parent.toPath(), "${targetFile.name}.", ".part")
        val channel = runCatching {
            FileChannel.open(
                partPath,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        }
            .getOrElse { error ->
                runCatching { Files.deleteIfExists(partPath) }
                throw error
            }
        partPath.toFile() to channel
    }
}
    .getOrNull()

internal fun flattenResponseHeaders(info: UrlResponseInfo): Map<String, String> {
    val flattened = LinkedHashMap<String, String>()
    for ((key, value) in info.headers.asList) {
        flattened[key] = value
    }
    return flattened
}

internal fun parseResponseContentLength(info: UrlResponseInfo): Long? =
    info.headers.asList
        .firstOrNull { (name, _) -> name.equals(HEADER_CONTENT_LENGTH, ignoreCase = true) }
        ?.value
        ?.trim()
        ?.toLongOrNull()

private const val HTTP_MOVED_PERMANENTLY = 301
private const val HTTP_FOUND = 302
private const val HTTP_SEE_OTHER = 303
internal const val REDIRECT_MISSING_LOCATION_MESSAGE = "Redirect missing Location header"
internal const val HTTP_SCHEME_HTTPS = "https://"
internal const val HTTP_SCHEME_HTTP = "http://"
internal const val HEADER_USER_AGENT = "User-Agent"
internal const val HEADER_ACCEPT = "Accept"
internal const val HEADER_AUTHORIZATION = "Authorization"
private const val HEADER_COOKIE = "Cookie"
private const val HEADER_PROXY_AUTHORIZATION = "Proxy-Authorization"
private const val HEADER_CONTENT_TYPE = "Content-Type"
private const val HEADER_CONTENT_LENGTH = "Content-Length"
private const val CONTENT_TYPE_JSON = "application/json"
private val CROSS_ORIGIN_SENSITIVE_HEADERS =
    listOf(HEADER_AUTHORIZATION, HEADER_COOKIE, HEADER_PROXY_AUTHORIZATION)
private val PAYLOAD_HEADERS = listOf(HEADER_CONTENT_TYPE, HEADER_CONTENT_LENGTH)
internal const val HTTP_OK_START = 200
internal const val HTTP_OK_END = 299
private const val HTTP_ENGINE_CALLBACK_THREADS = 2
internal const val HTTP_ENGINE_STORAGE_DIR_NAME = "http_engine"
internal const val HTTP_ENGINE_DISK_CACHE_BYTES = 1L * 1024L * 1024L
private const val HTTP_ENGINE_IN_MEMORY_CACHE_BYTES = 256L * 1024L
private val ACTIVE_HTTP_ENGINE_STORAGE_PATHS: MutableSet<String> = ConcurrentHashMap.newKeySet()

internal fun createBaseHttpEngineBuilder(appContext: Context): HttpEngine.Builder =
    HttpEngine.Builder(appContext).setEnableHttp2(true).setEnableQuic(true).setEnableBrotli(true)

internal fun prepareHttpEngineStoragePath(
    cacheDir: File?,
    activePaths: MutableSet<String> = ACTIVE_HTTP_ENGINE_STORAGE_PATHS,
): String? {
    if (cacheDir == null) return null
    return runCatching {
        val storageDir = File(cacheDir, HTTP_ENGINE_STORAGE_DIR_NAME)
        Files.createDirectories(storageDir.toPath())
        val canonical = storageDir.canonicalPath
        canonical.takeIf { activePaths.add(it) }
    }
        .getOrNull()
}

internal fun releaseHttpEngineStoragePath(
    storagePath: String,
    activePaths: MutableSet<String> = ACTIVE_HTTP_ENGINE_STORAGE_PATHS,
) {
    activePaths.remove(storagePath)
}

internal fun clearHttpEngineStorageDir(storagePath: String) {
    runCatching {
        val dir = File(storagePath)
        dir.listFiles()?.forEach { child -> child.deleteRecursively() }
    }
}

internal const val DIRECT_READ_BUFFER_BYTES = 64 * 1024
internal const val READ_BUFFER_CHARS = 8_192
internal const val MAX_RESPONSE_CHARS = 64 * 1024 * 1024
internal const val MAX_MEDIA_BYTES = 50 * 1024 * 1024
internal const val MAX_AUDIO_ENCLOSURE_BYTES = 500L * 1024L * 1024L
internal const val MAX_REDIRECTS = 5
private const val AUDIO_MAX_REDIRECTS = 5
private const val CONNECT_TIMEOUT_MS = 10_000L
internal const val READ_TIMEOUT_MS = 15_000L
private const val AUDIO_CONNECT_TIMEOUT_MS = 15_000L
internal const val AUDIO_READ_TIMEOUT_MS = 60_000L

internal fun shouldRewriteRedirectToGet(method: String, statusCode: Int): Boolean =
    when (statusCode) {
        HTTP_MOVED_PERMANENTLY,
        HTTP_FOUND -> method.equals("POST", ignoreCase = true)
        HTTP_SEE_OTHER ->
            !method.equals("GET", ignoreCase = true) && !method.equals("HEAD", ignoreCase = true)
        else -> false
    }

internal fun buildRedirectedRequest(
    currentRequest: HttpRequest,
    statusCode: Int,
    nextUrl: String,
): HttpRequest {
    val switchToGet = shouldRewriteRedirectToGet(currentRequest.method, statusCode)
    val sameOrigin = isSameOriginHost(currentRequest.url, nextUrl)
    val nextHeaders =
        filterRedirectHeaders(
            headers = currentRequest.headers,
            sameOrigin = sameOrigin,
            dropPayloadHeaders = switchToGet,
        )
    return currentRequest.copy(
        method = if (switchToGet) "GET" else currentRequest.method,
        url = nextUrl,
        headers = nextHeaders,
        body = if (switchToGet) null else currentRequest.body,
        requirePublicIp = currentRequest.requirePublicIp || !sameOrigin,
    )
}

private fun filterRedirectHeaders(
    headers: Map<String, String>,
    sameOrigin: Boolean,
    dropPayloadHeaders: Boolean,
): Map<String, String> {
    if (sameOrigin && !dropPayloadHeaders) return headers
    return headers.filterKeys { key ->
        val allowedOrigin =
            sameOrigin || CROSS_ORIGIN_SENSITIVE_HEADERS.none { it.equals(key, ignoreCase = true) }
        val allowedPayload =
            !dropPayloadHeaders || PAYLOAD_HEADERS.none { it.equals(key, ignoreCase = true) }
        allowedOrigin && allowedPayload
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
    require(maxBytes >= 0) { "maxBytes must be non-negative" }
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_CHARS)
    var total = 0
    while (true) {
        val remainingToLimit = maxBytes - total
        val maxReadLen = if (remainingToLimit >= buffer.size) buffer.size else remainingToLimit + 1
        val read = stream.read(buffer, 0, maxReadLen)
        if (read == -1) break
        if (read > maxBytes - total) {
            throw IOException("Response exceeds maximum allowed size of $maxBytes bytes")
        }
        total += read
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

internal fun moveFileAtomically(tempFile: File, targetFile: File) {
    runCatching {
        Files.move(
            tempFile.toPath(),
            targetFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
        .getOrElse {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
}

internal fun writeBytesAtomicallyToFile(bytes: ByteArray, targetFile: File): Boolean {
    targetFile.parentFile?.mkdirs()
    val temp = File(targetFile.parentFile, "${targetFile.name}.${System.nanoTime()}.tmp")
    return try {
        temp.writeBytes(bytes)
        moveFileAtomically(temp, targetFile)
        true
    } finally {
        if (temp.exists()) temp.delete()
    }
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

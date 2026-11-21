@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data.remote

import android.content.Context
import android.net.http.HttpEngine
import android.net.http.HttpException
import android.net.http.UploadDataProvider
import android.net.http.UploadDataSink
import android.net.http.UrlRequest
import android.net.http.UrlResponseInfo
import com.grayvines.roche.core.isHttpOrHttpsUrl
import com.grayvines.roche.core.moveFileAtomically
import com.grayvines.roche.core.writeBytesAtomicallyToFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
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
    val maxResponseBytes: Int? = null,
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
    private val vpnCheck: VpnTransportCheck = VpnTransportCheck { false },
) : HttpTransport, AutoCloseable {
    private val appContext: Context = context.applicationContext ?: context
    private var lockedStoragePath: String? = null

    private val httpEngineDelegate = lazy { buildConfiguredHttpEngine(appContext) }
    private val httpEngine: HttpEngine
        get() = httpEngineDelegate.value

    private val executor: ScheduledExecutorService =
        Executors.newScheduledThreadPool(HTTP_ENGINE_CALLBACK_THREADS) { runnable ->
            Thread(runnable, "roche-http-engine").apply { isDaemon = true }
        }

    internal val sharedHttpEngine: HttpEngine
        get() = httpEngine

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
        if (httpEngineDelegate.isInitialized()) {
            runCatching { httpEngineDelegate.value.shutdown() }
        }
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
            val isRedirect =
                outcome.redirectLocation != null &&
                    outcome.response.statusCode in REDIRECT_STATUS_CODES
            if (isRedirect && hop >= MAX_REDIRECTS) {
                throw IOException("Too many redirects")
            }
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

internal abstract class BaseUrlRequestCallback<T>(
    protected val watchdog: HopWatchdog,
    protected val continuation: CancellableContinuation<T>,
) : UrlRequest.Callback {
    protected val readBuffer: ByteBuffer = ByteBuffer.allocateDirect(DIRECT_READ_BUFFER_BYTES)
    protected val settled = AtomicBoolean(false)
    protected val abortError = AtomicReference<IOException?>(null)
    private val startedRequestRef = AtomicReference<UrlRequest?>(null)
    @Volatile protected var redirectStatus: Int = 0
    @Volatile protected var redirectLocation: String? = null
    @Volatile protected var redirectHeaders: Map<String, String> = emptyMap()

    protected open fun shouldCancelOnStart(): Boolean =
        settled.get() || abortError.get() != null || !continuation.isActive

    fun markRequestStarted(urlRequest: UrlRequest) {
        startedRequestRef.set(urlRequest)
        if (shouldCancelOnStart()) {
            runCatching { urlRequest.cancel() }
        }
    }

    open fun cancelFromCoroutine() {
        settled.set(true)
        watchdog.cancel()
        cancelStartedRequest()
    }

    protected fun cancelStartedRequest() {
        runCatching { startedRequestRef.get()?.cancel() }
    }

    protected fun trySettle(): Boolean = continuation.isActive && settled.compareAndSet(false, true)

    protected fun recordRedirectTarget(
        statusCode: Int,
        headers: Map<String, String>,
        newLocationUrl: String,
    ): Boolean {
        watchdog.cancel()
        val cleanLocation = newLocationUrl.trim().takeIf { it.isNotBlank() }
        return if (cleanLocation == null) {
            abortError.compareAndSet(null, IOException(REDIRECT_MISSING_LOCATION_MESSAGE))
            false
        } else {
            redirectStatus = statusCode
            redirectHeaders = headers
            redirectLocation = cleanLocation
            true
        }
    }

    protected fun armReadAndContinue(
        request: UrlRequest,
        buffer: ByteBuffer,
        timeoutMs: Long,
    ) {
        watchdog.arm(timeoutMs)
        buffer.clear()
        request.read(buffer)
    }
}

internal class BufferedUrlRequestCallback(
    private val httpRequest: HttpRequest,
    watchdog: HopWatchdog,
    continuation: CancellableContinuation<BufferedHopOutcome>,
    initialMaxAllowedBytes: Int = MAX_RESPONSE_CHARS,
) : BaseUrlRequestCallback<BufferedHopOutcome>(watchdog, continuation) {
    private val scratch = ByteArray(READ_BUFFER_CHARS)
    private val output = ByteArrayOutputStream()
    @Volatile private var maxAllowedBytes: Int = initialMaxAllowedBytes

    fun abortWithTimeout() {
        val timeout = SocketTimeoutException("HTTP request timed out")
        abortError.compareAndSet(null, timeout)
        watchdog.cancel()
        cancelStartedRequest()
        if (trySettle()) {
            continuation.resumeWithException(abortError.get() ?: timeout)
        }
    }

    fun failStart(error: Throwable) {
        watchdog.cancel()
        if (trySettle()) {
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
        recordRedirectTarget(statusCode, headers, newLocationUrl)
    }

    override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
        val status = info.httpStatusCode
        val isSuccess = status in HTTP_OK_START..HTTP_OK_END
        val defaultBound =
            if (httpRequest.expectBinary && isSuccess) MAX_MEDIA_BYTES else MAX_RESPONSE_CHARS
        val bound = httpRequest.maxResponseBytes?.coerceIn(1, defaultBound) ?: defaultBound
        maxAllowedBytes = bound
        val declaredLength = parseResponseContentLength(info)
        if (declaredLength != null && declaredLength > bound.toLong()) {
            abortError.compareAndSet(null, ResponseTooLargeException(maxBytes = bound.toLong()))
            watchdog.cancel()
            request.cancel()
        } else {
            armReadAndContinue(request, readBuffer, READ_TIMEOUT_MS)
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
            armReadAndContinue(request, byteBuffer, READ_TIMEOUT_MS)
        }
    }

    internal fun appendReadChunk(byteBuffer: ByteBuffer): Boolean {
        byteBuffer.flip()
        val incoming = byteBuffer.remaining()
        val bound = maxAllowedBytes
        return if (incoming > bound - output.size()) {
            abortError.compareAndSet(null, ResponseTooLargeException(maxBytes = bound.toLong()))
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
        if (!trySettle()) return
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
        if (trySettle()) {
            continuation.resumeWithException(abortError.get() ?: error)
        }
    }

    override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
        handleCanceled()
    }

    internal fun handleCanceled() {
        watchdog.cancel()
        if (!trySettle()) return
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
    watchdog: HopWatchdog,
    continuation: CancellableContinuation<StreamHopResult>,
) : BaseUrlRequestCallback<StreamHopResult>(watchdog, continuation) {
    @Volatile private var aborted: Boolean = false
    @Volatile private var bytesWritten: Long = 0L

    override fun shouldCancelOnStart(): Boolean = settled.get() || aborted || !continuation.isActive

    override fun cancelFromCoroutine() {
        aborted = true
        closeAndDeletePartFile()
        super.cancelFromCoroutine()
    }

    fun abortRequest() {
        aborted = true
        watchdog.cancel()
        closeAndDeletePartFile()
        cancelStartedRequest()
        if (trySettle()) {
            continuation.resume(StreamHopResult.Failed)
        }
    }

    fun cleanupAndFail() {
        aborted = true
        watchdog.cancel()
        closeAndDeletePartFile()
        if (trySettle()) {
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
        if (!recordRedirectTarget(statusCode, emptyMap(), newLocationUrl)) {
            aborted = true
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
            armReadAndContinue(request, readBuffer, AUDIO_READ_TIMEOUT_MS)
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
            armReadAndContinue(request, byteBuffer, AUDIO_READ_TIMEOUT_MS)
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
        if (!trySettle()) {
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
        if (!trySettle()) return
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
): String =
    if (request.requirePublicIp) {
        val upgraded = PublicUrlValidator.upgradePublicHttp(request.url)
        PublicUrlValidator.ensurePublicTarget(upgraded)
    } else {
        PublicUrlValidator.ensureSafeServerEndpoint(request.url, vpnCheck::isActive)
        PublicUrlValidator.ensureResolvedServerTarget(request.url)
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
internal val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
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
internal const val HEADER_RETRY_AFTER = "Retry-After"
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

internal fun filterRedirectHeaders(
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
    resolved.takeIf(::isHttpOrHttpsUrl)
}
    .getOrNull()

internal fun isSameOriginHost(firstUrl: String, secondUrl: String): Boolean = runCatching {
    val origin = URI.create(firstUrl.trim().replace(" ", "%20")).toURL()
    val target = URI.create(secondUrl.trim().replace(" ", "%20")).toURL()
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
    val problem = parseProblemDetails(response.body, response.statusCode, response.headers)
    throw ProblemDetailsException(
        statusCode = response.statusCode,
        title = problem.title,
        detail = problem.detail,
        problemType = problem.problemType,
        retryAfterSeconds = problem.retryAfterSeconds,
        maxBytes = problem.maxBytes,
        candidates = problem.candidates,
    )
}

internal fun ensureExternalSuccess(response: HttpResponse) {
    if (response.statusCode !in HTTP_OK_START..HTTP_OK_END) {
        throw HttpStatusException(response.statusCode)
    }
}

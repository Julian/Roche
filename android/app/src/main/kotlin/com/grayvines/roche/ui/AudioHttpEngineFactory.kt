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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpEngineDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import com.grayvines.roche.data.remote.MAX_REDIRECTS
import com.grayvines.roche.data.remote.PublicUrlValidator
import com.grayvines.roche.data.remote.resolveRedirectUrl
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.SocketTimeoutException
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
    callbackExecutor: Executor,
): DataSource.Factory {
    val redirectCache = AudioRedirectCache()
    val useHttpEngine =
        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= MIN_S_EXT_HTTP_ENGINE
    val baseHttpFactory: DataSource.Factory =
        if (useHttpEngine) {
            HttpEngineDataSource.Factory(httpEngine, callbackExecutor)
                .setUserAgent(ROCHE_AUDIO_USER_AGENT)
                .setConnectionTimeoutMs(AUDIO_HTTP_CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(AUDIO_HTTP_READ_TIMEOUT_MS)
                .setResetTimeoutOnRedirects(true)
        } else {
            DefaultHttpDataSource.Factory()
                .setUserAgent(ROCHE_AUDIO_USER_AGENT)
                .setConnectTimeoutMs(AUDIO_HTTP_CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(AUDIO_HTTP_READ_TIMEOUT_MS)
                .setAllowCrossProtocolRedirects(false)
        }
    val guardedHttpFactory = wrapWithPostRedirectCheck(baseHttpFactory)
    val resolvingHttpFactory =
        ResolvingDataSource.Factory(guardedHttpFactory) { dataSpec ->
            if (useHttpEngine) {
                resolveSafeHttpDataSpec(
                    dataSpec = dataSpec,
                    redirectResolver = { rawUrl ->
                        resolveCachedAudioUrl(
                            rawUrl = rawUrl,
                            cache = redirectCache,
                            probeHop = { hopUrl ->
                                probeAudioRedirectHop(hopUrl, httpEngine, callbackExecutor)
                            },
                        )
                    },
                )
            } else {
                resolveSafeHttpDataSpec(dataSpec)
            }
        }
    return DefaultDataSource.Factory(context, resolvingHttpFactory)
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
        )
    cache.put(startUrl, resolved)
    return resolved
}

internal fun resolveAudioRedirectChain(
    initialUrl: String,
    maxRedirects: Int = MAX_REDIRECTS,
    validateUrl: (String) -> String = { resolveSafeHttpPlaybackUrl(it) },
    probeHop: (String) -> AudioHopOutcome,
): String {
    var currentUrl = validateUrl(initialUrl)
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

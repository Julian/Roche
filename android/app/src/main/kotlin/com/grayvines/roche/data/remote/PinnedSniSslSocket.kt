package com.grayvines.roche.data.remote

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.SocketAddress
import java.nio.channels.SocketChannel
import java.util.function.BiFunction
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

/**
 * Delegating [SSLSocket] that pins TLS SNI and RFC 2818 endpoint identification strictly to
 * [expectedHost].
 *
 * Android's platform `HttpsURLConnection` (`com.android.okhttp`) reflectively invokes Conscrypt's
 * hidden `setHostname(String)` with the URL's host right before `startHandshake()`. When the URL
 * host is a pinned IP literal, that call strips SNI and breaks certificate hostname validation.
 * Wrapping the underlying Conscrypt socket in this class hides `setHostname(String)` from
 * reflection and re-applies [expectedHost] before handshake.
 */
@Suppress("TooManyFunctions")
internal class PinnedSniSslSocket(
    internal val delegate: SSLSocket,
    internal val expectedHost: String,
) : SSLSocket() {
    init {
        applyPinnedTlsParameters(delegate, expectedHost)
    }

    override fun startHandshake() {
        applyPinnedTlsParameters(delegate, expectedHost)
        delegate.startHandshake()
    }

    override fun getSSLParameters(): SSLParameters = delegate.sslParameters

    override fun setSSLParameters(params: SSLParameters) {
        enforcePinnedParameters(params, expectedHost)
        delegate.sslParameters = params
    }

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun getEnabledCipherSuites(): Array<String> = delegate.enabledCipherSuites

    override fun setEnabledCipherSuites(suites: Array<out String>?) {
        delegate.enabledCipherSuites = suites
    }

    override fun getSupportedProtocols(): Array<String> = delegate.supportedProtocols

    override fun getEnabledProtocols(): Array<String> = delegate.enabledProtocols

    override fun setEnabledProtocols(protocols: Array<out String>?) {
        delegate.enabledProtocols = protocols
    }

    override fun getSession(): SSLSession = delegate.session

    override fun getHandshakeSession(): SSLSession? = delegate.handshakeSession

    override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener) =
        delegate.addHandshakeCompletedListener(listener)

    override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener) =
        delegate.removeHandshakeCompletedListener(listener)

    override fun setUseClientMode(mode: Boolean) {
        delegate.useClientMode = mode
    }

    override fun getUseClientMode(): Boolean = delegate.useClientMode

    override fun setNeedClientAuth(need: Boolean) {
        delegate.needClientAuth = need
    }

    override fun getNeedClientAuth(): Boolean = delegate.needClientAuth

    override fun setWantClientAuth(want: Boolean) {
        delegate.wantClientAuth = want
    }

    override fun getWantClientAuth(): Boolean = delegate.wantClientAuth

    override fun setEnableSessionCreation(flag: Boolean) {
        delegate.enableSessionCreation = flag
    }

    override fun getEnableSessionCreation(): Boolean = delegate.enableSessionCreation

    override fun getApplicationProtocol(): String? = delegate.applicationProtocol

    override fun getHandshakeApplicationProtocol(): String? = delegate.handshakeApplicationProtocol

    override fun setHandshakeApplicationProtocolSelector(
        selector: BiFunction<SSLSocket, MutableList<String>, String>?
    ) {
        delegate.handshakeApplicationProtocolSelector = selector
    }

    override fun getHandshakeApplicationProtocolSelector():
        BiFunction<SSLSocket, MutableList<String>, String>? =
        delegate.handshakeApplicationProtocolSelector

    override fun connect(endpoint: SocketAddress) = delegate.connect(endpoint)

    override fun connect(endpoint: SocketAddress, timeout: Int) =
        delegate.connect(endpoint, timeout)

    override fun bind(bindpoint: SocketAddress?) = delegate.bind(bindpoint)

    override fun getInetAddress(): InetAddress? = delegate.inetAddress

    override fun getLocalAddress(): InetAddress = delegate.localAddress

    override fun getPort(): Int = delegate.port

    override fun getLocalPort(): Int = delegate.localPort

    override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress

    override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress

    override fun getChannel(): SocketChannel? = delegate.channel

    override fun getInputStream(): InputStream = delegate.inputStream

    override fun getOutputStream(): OutputStream = delegate.outputStream

    override fun setTcpNoDelay(on: Boolean) {
        delegate.tcpNoDelay = on
    }

    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay

    override fun setSoLinger(on: Boolean, linger: Int) = delegate.setSoLinger(on, linger)

    override fun getSoLinger(): Int = delegate.soLinger

    override fun sendUrgentData(data: Int) = delegate.sendUrgentData(data)

    override fun setOOBInline(on: Boolean) {
        delegate.oobInline = on
    }

    override fun getOOBInline(): Boolean = delegate.oobInline

    override fun setSoTimeout(timeout: Int) {
        delegate.soTimeout = timeout
    }

    override fun getSoTimeout(): Int = delegate.soTimeout

    override fun setSendBufferSize(size: Int) {
        delegate.sendBufferSize = size
    }

    override fun getSendBufferSize(): Int = delegate.sendBufferSize

    override fun setReceiveBufferSize(size: Int) {
        delegate.receiveBufferSize = size
    }

    override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize

    override fun setKeepAlive(on: Boolean) {
        delegate.keepAlive = on
    }

    override fun getKeepAlive(): Boolean = delegate.keepAlive

    override fun setTrafficClass(tc: Int) {
        delegate.trafficClass = tc
    }

    override fun getTrafficClass(): Int = delegate.trafficClass

    override fun setReuseAddress(on: Boolean) {
        delegate.reuseAddress = on
    }

    override fun getReuseAddress(): Boolean = delegate.reuseAddress

    override fun close() = delegate.close()

    override fun shutdownInput() = delegate.shutdownInput()

    override fun shutdownOutput() = delegate.shutdownOutput()

    override fun isConnected(): Boolean = delegate.isConnected

    override fun isBound(): Boolean = delegate.isBound

    override fun isClosed(): Boolean = delegate.isClosed

    override fun isInputShutdown(): Boolean = delegate.isInputShutdown

    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown

    override fun setPerformancePreferences(connectionTime: Int, latency: Int, bandwidth: Int) =
        delegate.setPerformancePreferences(connectionTime, latency, bandwidth)
}

internal fun applyPinnedTlsParameters(socket: SSLSocket, expectedHost: String) {
    val params = socket.sslParameters
    enforcePinnedParameters(params, expectedHost)
    socket.sslParameters = params
}

internal fun enforcePinnedParameters(params: SSLParameters, expectedHost: String) {
    params.endpointIdentificationAlgorithm = PINNED_HTTPS_ENDPOINT_ALGORITHM
    val sniHost = expectedHost.trimEnd('.')
    if (isSniEligibleHostname(sniHost)) {
        params.serverNames = listOf(SNIHostName(sniHost))
    }
}

private const val PINNED_HTTPS_ENDPOINT_ALGORITHM = "HTTPS"

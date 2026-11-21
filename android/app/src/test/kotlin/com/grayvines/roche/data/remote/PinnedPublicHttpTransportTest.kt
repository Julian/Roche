@file:Suppress("TooManyFunctions", "LongMethod", "MagicNumber")

package com.grayvines.roche.data.remote

import com.grayvines.roche.data.sync.NetworkTierProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.zip.GZIPOutputStream
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PinnedPublicHttpTransportTest {
    @TempDir lateinit var tempDir: File

    @Test
    fun `PinnedIpSslSocketFactory equality separates virtual hosts sharing a CDN IP and blocks private IPs`() {
        val delegate = SSLSocketFactory.getDefault() as SSLSocketFactory
        val cdnIp = InetAddress.getByName("93.184.216.34")
        val otherIp = InetAddress.getByName("93.184.216.35")

        val hostA1 = PinnedIpSslSocketFactory(delegate, cdnIp, "alpha.example.com", 443, 5_000)
        val hostA2 = PinnedIpSslSocketFactory(delegate, cdnIp, "alpha.example.com", 443, 10_000)
        val hostB = PinnedIpSslSocketFactory(delegate, cdnIp, "beta.example.com", 443, 5_000)
        val hostAPort = PinnedIpSslSocketFactory(delegate, cdnIp, "alpha.example.com", 8443, 5_000)
        val hostAIp2 = PinnedIpSslSocketFactory(delegate, otherIp, "alpha.example.com", 443, 5_000)

        assertEquals(hostA1, hostA2)
        assertEquals(hostA1.hashCode(), hostA2.hashCode())
        assertNotEquals(hostA1, hostB)
        assertNotEquals(hostA1, hostAPort)
        assertNotEquals(hostA1, hostAIp2)

        val privateFactory =
            PinnedIpSslSocketFactory(
                delegate = delegate,
                pinnedAddress = InetAddress.getByName("127.0.0.1"),
                expectedHost = "evil.example.com",
                targetPort = 443,
                connectTimeoutMs = 1_000,
            )
        assertThrows(IOException::class.java) { privateFactory.createSocket() }
    }

    @Test
    fun `PinnedHostnameVerifier verifies against expectedHost instead of IP literal`() {
        var verifiedHost: String? = null
        val verifier =
            PinnedHostnameVerifier(expectedHost = "feeds.example.com") { hostname, _ ->
                verifiedHost = hostname
                hostname == "feeds.example.com"
            }
        val fakeSession =
            java.lang.reflect.Proxy.newProxyInstance(
                SSLSession::class.java.classLoader,
                arrayOf(SSLSession::class.java),
            ) { _, _, _ ->
                null
            } as SSLSession

        assertTrue(verifier.verify("93.184.216.34", fakeSession))
        assertEquals("feeds.example.com", verifiedHost)
        assertEquals(
            PinnedHostnameVerifier("feeds.example.com"),
            PinnedHostnameVerifier("feeds.example.com"),
        )
        assertNotEquals(
            PinnedHostnameVerifier("feeds.example.com"),
            PinnedHostnameVerifier("other.example.com"),
        )
    }

    @Test
    fun `buildPinnedIpConnectionUrl and buildHostHeaderValue handle IPv4 IPv6 ports and percent encoding`() {
        val ipv4 = InetAddress.getByName("93.184.216.34")
        val ipv6 = InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")

        val defaultHttps =
            ResolvedPublicTarget(
                canonicalUrl = "https://feeds.example.com/a%20b/rss.xml?x=1&y=2",
                scheme = "https",
                asciiHost = "feeds.example.com",
                port = 443,
                addresses = listOf(ipv4, ipv6),
            )
        assertEquals(
            "https://93.184.216.34/a%20b/rss.xml?x=1&y=2",
            buildPinnedIpConnectionUrl(defaultHttps, ipv4).toString(),
        )
        assertEquals(
            "https://[2606:2800:220:1:248:1893:25c8:1946]/a%20b/rss.xml?x=1&y=2",
            buildPinnedIpConnectionUrl(defaultHttps, ipv6).toString(),
        )
        assertEquals("feeds.example.com", buildHostHeaderValue(defaultHttps))

        val customPort =
            ResolvedPublicTarget(
                canonicalUrl = "https://feeds.example.com:8443/feed",
                scheme = "https",
                asciiHost = "feeds.example.com",
                port = 8443,
                addresses = listOf(ipv4),
            )
        assertEquals(
            "https://93.184.216.34:8443/feed",
            buildPinnedIpConnectionUrl(customPort, ipv4).toString(),
        )
        assertEquals("feeds.example.com:8443", buildHostHeaderValue(customPort))
    }

    @Test
    fun `PinnedPublicHttpTransport follows redirects strips cross-origin auth fails over IPs and throws on redirect overflow`() =
        runTest {
            val badIp = InetAddress.getByName("93.184.216.34")
            val goodIp = InetAddress.getByName("93.184.216.35")
            val cdnIp = InetAddress.getByName("93.184.216.36")
            val attemptedConnections = mutableListOf<String>()
            val seenHeadersByUrl = mutableMapOf<String, Map<String, String>>()

            val transport =
                PinnedPublicHttpTransport(
                    resolveTarget = { rawUrl ->
                        when (rawUrl) {
                            "https://origin.example.com/old.xml" ->
                                ResolvedPublicTarget(
                                    canonicalUrl = rawUrl,
                                    scheme = "https",
                                    asciiHost = "origin.example.com",
                                    port = 443,
                                    addresses = listOf(badIp, goodIp),
                                )
                            "https://origin.example.com/step2.xml" ->
                                ResolvedPublicTarget(
                                    canonicalUrl = rawUrl,
                                    scheme = "https",
                                    asciiHost = "origin.example.com",
                                    port = 443,
                                    addresses = listOf(goodIp),
                                )
                            "https://cdn.other.example/final.xml" ->
                                ResolvedPublicTarget(
                                    canonicalUrl = rawUrl,
                                    scheme = "https",
                                    asciiHost = "cdn.other.example",
                                    port = 443,
                                    addresses = listOf(cdnIp),
                                )
                            else -> error("Unexpected URL: $rawUrl")
                        }
                    },
                    openConnection = { target, address, _, _ ->
                        attemptedConnections.add("${target.canonicalUrl}@${address.hostAddress}")
                        if (address == badIp) {
                            FakeHttpUrlConnection(
                                url = URL(target.canonicalUrl),
                                connectError = IOException("Connection refused"),
                            )
                        } else {
                            when (target.canonicalUrl) {
                                "https://origin.example.com/old.xml" ->
                                    FakeHttpUrlConnection(
                                        url = URL(target.canonicalUrl),
                                        status = 302,
                                        responseHeaders = mapOf("Location" to listOf("/step2.xml")),
                                        onConnect = { headers ->
                                            seenHeadersByUrl[target.canonicalUrl] = headers
                                        },
                                    )
                                "https://origin.example.com/step2.xml" ->
                                    FakeHttpUrlConnection(
                                        url = URL(target.canonicalUrl),
                                        status = 302,
                                        responseHeaders =
                                            mapOf(
                                                "Location" to
                                                    listOf("https://cdn.other.example/final.xml")
                                            ),
                                        onConnect = { headers ->
                                            seenHeadersByUrl[target.canonicalUrl] = headers
                                        },
                                    )
                                else ->
                                    FakeHttpUrlConnection(
                                        url = URL(target.canonicalUrl),
                                        status = 200,
                                        bodyBytes = "<rss/>".toByteArray(),
                                        onConnect = { headers ->
                                            seenHeadersByUrl[target.canonicalUrl] = headers
                                        },
                                    )
                            }
                        }
                    },
                )

            val response =
                transport.execute(
                    HttpRequest(
                        method = "GET",
                        url = "https://origin.example.com/old.xml",
                        headers =
                            mapOf(
                                "Authorization" to "Bearer top-secret",
                                "Cookie" to "session=abc",
                                "Accept" to "application/rss+xml",
                            ),
                    )
                )
            assertEquals(200, response.statusCode)
            assertEquals("<rss/>", response.body)
            assertEquals(
                listOf(
                    "https://origin.example.com/old.xml@93.184.216.34",
                    "https://origin.example.com/old.xml@93.184.216.35",
                    "https://origin.example.com/step2.xml@93.184.216.35",
                    "https://cdn.other.example/final.xml@93.184.216.36",
                ),
                attemptedConnections,
            )
            // Same-origin relative redirect retains auth; cross-origin strips auth & cookie
            assertEquals(
                "Bearer top-secret",
                seenHeadersByUrl["https://origin.example.com/step2.xml"]?.get("Authorization"),
            )
            val finalHeaders = seenHeadersByUrl["https://cdn.other.example/final.xml"].orEmpty()
            assertFalse(finalHeaders.keys.any { it.equals("Authorization", ignoreCase = true) })
            assertFalse(finalHeaders.keys.any { it.equals("Cookie", ignoreCase = true) })
            assertEquals("application/rss+xml", finalHeaders["Accept"])

            // Redirect overflow throws IOException("Too many redirects")
            val loopTransport =
                PinnedPublicHttpTransport(
                    resolveTarget = { rawUrl ->
                        ResolvedPublicTarget(
                            canonicalUrl = rawUrl,
                            scheme = "https",
                            asciiHost = "loop.example.com",
                            port = 443,
                            addresses = listOf(goodIp),
                        )
                    },
                    openConnection = { target, _, _, _ ->
                        FakeHttpUrlConnection(
                            url = URL(target.canonicalUrl),
                            status = 302,
                            responseHeaders = mapOf("Location" to listOf("/next")),
                        )
                    },
                    maxRedirects = 2,
                )
            val overflowErr = runCatching {
                loopTransport.execute(HttpRequest("GET", "https://loop.example.com/start"))
            }
                .exceptionOrNull()
            assertTrue(overflowErr is IOException)
            assertEquals("Too many redirects", overflowErr?.message)
        }

    @Test
    fun `PinnedPublicHttpTransport enforces decompressed gzip byte budgets handles 204 and streams atomically`() =
        runTest {
            val publicIp = InetAddress.getByName("93.184.216.34")
            val smallPlain = "hello gzip world"
            val smallGzipped = gzipCompress(smallPlain.toByteArray())
            val bombGzipped = gzipCompress(ByteArray(4_096) { 'A'.code.toByte() })
            val binaryPayload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

            val transport =
                PinnedPublicHttpTransport(
                    resolveTarget = { rawUrl ->
                        ResolvedPublicTarget(
                            canonicalUrl = rawUrl,
                            scheme = "https",
                            asciiHost = "cdn.example.com",
                            port = 443,
                            addresses = listOf(publicIp),
                        )
                    },
                    openConnection = { target, _, _, _ ->
                        when (target.canonicalUrl) {
                            "https://cdn.example.com/ok.gz" ->
                                FakeHttpUrlConnection(
                                    url = URL(target.canonicalUrl),
                                    status = 200,
                                    bodyBytes = smallGzipped,
                                    responseHeaders = mapOf("Content-Encoding" to listOf("gzip")),
                                )
                            "https://cdn.example.com/bomb.gz" ->
                                FakeHttpUrlConnection(
                                    url = URL(target.canonicalUrl),
                                    status = 200,
                                    bodyBytes = bombGzipped,
                                    responseHeaders = mapOf("Content-Encoding" to listOf("gzip")),
                                )
                            "https://cdn.example.com/empty204" ->
                                FakeHttpUrlConnection(
                                    url = URL(target.canonicalUrl),
                                    status = 204,
                                    bodyBytes = ByteArray(0),
                                    responseHeaders = mapOf("Content-Encoding" to listOf("gzip")),
                                )
                            else ->
                                FakeHttpUrlConnection(
                                    url = URL(target.canonicalUrl),
                                    status = 200,
                                    bodyBytes = binaryPayload,
                                )
                        }
                    },
                )

            val gzipOk =
                transport.execute(
                    HttpRequest(
                        method = "GET",
                        url = "https://cdn.example.com/ok.gz",
                        maxResponseBytes = 1_024,
                    )
                )
            assertEquals(smallPlain, gzipOk.body)

            // Compressed payload is tiny (~40 bytes) but inflates to 4096 bytes > 256 byte cap!
            val bombErr = runCatching {
                transport.execute(
                    HttpRequest(
                        method = "GET",
                        url = "https://cdn.example.com/bomb.gz",
                        maxResponseBytes = 256,
                    )
                )
            }
                .exceptionOrNull()
            assertTrue(bombErr is ResponseTooLargeException)
            assertEquals(256L, (bombErr as ResponseTooLargeException).maxBytes)

            val empty204 = transport.execute(HttpRequest("GET", "https://cdn.example.com/empty204"))
            assertEquals(204, empty204.statusCode)
            assertEquals("", empty204.body)

            val targetFile = File(tempDir, "streamed.bin")
            assertTrue(
                transport.streamToFile(
                    request = HttpRequest("GET", "https://cdn.example.com/audio.mp3"),
                    targetFile = targetFile,
                    maxBytes = 16L,
                )
            )
            assertArrayEquals(binaryPayload, targetFile.readBytes())

            // Overflow leaves existing file intact and deletes temporary .part files
            assertFalse(
                transport.streamToFile(
                    request = HttpRequest("GET", "https://cdn.example.com/audio.mp3"),
                    targetFile = targetFile,
                    maxBytes = 4L,
                )
            )
            assertArrayEquals(binaryPayload, targetFile.readBytes())
            assertTrue(tempDir.listFiles().orEmpty().none { it.name.endsWith(".part") })
        }

    @Test
    fun `PublicUrlValidator resolvePublicTarget upgrades cleartext rejects mixed DNS and defaults fail closed`() {
        val publicIp = InetAddress.getByName("93.184.216.34")
        val privateIp = InetAddress.getByName("10.0.0.5")

        val upgraded =
            PublicUrlValidator.resolvePublicTarget(
                rawUrl = "http://feeds.example.com/rss.xml",
                resolveAddresses = { arrayOf(publicIp) },
                upgradeCleartext = true,
            )
        assertEquals("https://feeds.example.com/rss.xml", upgraded.canonicalUrl)
        assertEquals("https", upgraded.scheme)
        assertEquals(443, upgraded.port)
        assertEquals(listOf(publicIp), upgraded.addresses)

        // Mixed public + private DNS response must be rejected completely
        assertThrows(IOException::class.java) {
            PublicUrlValidator.resolvePublicTarget(
                rawUrl = "https://rebind.example.com/rss.xml",
                resolveAddresses = { arrayOf(publicIp, privateIp) },
            )
        }

        // Default NetworkTierProvider fails closed on VPN status
        val defaultTierProvider = NetworkTierProvider { NetworkTier.WIFI }
        assertFalse(defaultTierProvider.isVpnTransportActive())
    }

    @Test
    fun `PinnedSniSslSocket hides Conscrypt setHostname and enforces SNI and HTTPS verification before handshake`() {
        val fakeConscrypt = FakeConscryptSslSocket()
        val wrapped =
            PinnedSniSslSocket(delegate = fakeConscrypt, expectedHost = "feeds.example.com")

        // com.android.okhttp reflects for setHostname(String); wrapper MUST NOT expose it
        val hasSetHostname =
            wrapped.javaClass.methods.any {
                it.name == "setHostname" &&
                    it.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
        assertFalse(hasSetHostname)

        // Attempting to clobber SSLParameters with blank endpoint algo / different SNI is
        // overridden
        val hostileParams = SSLParameters()
        hostileParams.endpointIdentificationAlgorithm = ""
        hostileParams.serverNames = listOf(SNIHostName("wrong.example.com"))
        wrapped.sslParameters = hostileParams
        assertEquals("HTTPS", fakeConscrypt.sslParameters.endpointIdentificationAlgorithm)
        assertEquals(
            listOf(SNIHostName("feeds.example.com")),
            fakeConscrypt.sslParameters.serverNames,
        )

        // Even if delegate's SSLParameters were mutated directly, startHandshake re-enforces
        // expectedHost
        fakeConscrypt.sslParameters = SSLParameters()
        wrapped.startHandshake()
        assertTrue(fakeConscrypt.handshakeStarted)
        assertEquals("HTTPS", fakeConscrypt.endpointAlgoAtHandshake)
        assertEquals(listOf(SNIHostName("feeds.example.com")), fakeConscrypt.sniAtHandshake)

        // BoundedInputStream rejects oversized gzip wire headers (e.g. huge FNAME field)
        val oversizedHeaderStream = ByteArrayInputStream(ByteArray(512) { 0x1f.toByte() })
        val bounded = BoundedInputStream(oversizedHeaderStream, maxBytes = 64L)
        assertThrows(ResponseTooLargeException::class.java) { bounded.readBytes() }
        assertTrue(isGzipContentEncoding(mapOf("Content-Encoding" to "x-gzip")))

        // Trailing-dot FQDN is normalized for SNIHostName without throwing IllegalArgumentException
        val trailingDotSocket = FakeConscryptSslSocket()
        val trailingWrapped =
            PinnedSniSslSocket(delegate = trailingDotSocket, expectedHost = "feeds.example.com.")
        trailingWrapped.startHandshake()
        assertEquals(
            listOf(SNIHostName("feeds.example.com")),
            trailingDotSocket.sniAtHandshake,
        )
    }

    @Test
    fun `PinnedPublicHttpTransport streamToFile propagates CancellationException during connect`() =
        runTest {
            val publicIp = InetAddress.getByName("93.184.216.34")
            val cancellingTransport =
                PinnedPublicHttpTransport(
                    resolveTarget = { rawUrl ->
                        ResolvedPublicTarget(
                            canonicalUrl = rawUrl,
                            scheme = "https",
                            asciiHost = "cdn.example.com",
                            port = 443,
                            addresses = listOf(publicIp),
                        )
                    },
                    openConnection = { _, _, _, _ ->
                        throw kotlinx.coroutines.CancellationException("scroll cancelled")
                    },
                )
            val err = runCatching {
                cancellingTransport.streamToFile(
                    request = HttpRequest("GET", "https://cdn.example.com/thumb.png"),
                    targetFile = File(tempDir, "cancelled.png"),
                    maxBytes = 1024L,
                )
            }
                .exceptionOrNull()
            assertTrue(err is kotlinx.coroutines.CancellationException)
        }
}

private class FakeConscryptSslSocket : SSLSocket() {
    private var params: SSLParameters = SSLParameters()
    var handshakeStarted: Boolean = false
    var endpointAlgoAtHandshake: String? = null
    var sniAtHandshake: List<javax.net.ssl.SNIServerName>? = null

    @Suppress("unused")
    fun setHostname(host: String) {
        error("setHostname($host) must never be reachable through PinnedSniSslSocket")
    }

    override fun getSSLParameters(): SSLParameters = params

    override fun setSSLParameters(params: SSLParameters) {
        this.params = params
    }

    override fun startHandshake() {
        handshakeStarted = true
        endpointAlgoAtHandshake = params.endpointIdentificationAlgorithm
        sniAtHandshake = params.serverNames
    }

    override fun getSupportedCipherSuites(): Array<String> = emptyArray()

    override fun getEnabledCipherSuites(): Array<String> = emptyArray()

    override fun setEnabledCipherSuites(suites: Array<out String>?) = Unit

    override fun getSupportedProtocols(): Array<String> = emptyArray()

    override fun getEnabledProtocols(): Array<String> = emptyArray()

    override fun setEnabledProtocols(protocols: Array<out String>?) = Unit

    override fun getSession(): SSLSession = error("unused")

    override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) = Unit

    override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) = Unit

    override fun setUseClientMode(mode: Boolean) = Unit

    override fun getUseClientMode(): Boolean = true

    override fun setNeedClientAuth(need: Boolean) = Unit

    override fun getNeedClientAuth(): Boolean = false

    override fun setWantClientAuth(want: Boolean) = Unit

    override fun getWantClientAuth(): Boolean = false

    override fun setEnableSessionCreation(flag: Boolean) = Unit

    override fun getEnableSessionCreation(): Boolean = true
}

private fun gzipCompress(raw: ByteArray): ByteArray {
    val bos = ByteArrayOutputStream()
    GZIPOutputStream(bos).use { it.write(raw) }
    return bos.toByteArray()
}

private class FakeHttpUrlConnection(
    url: URL,
    private val status: Int = 200,
    private val bodyBytes: ByteArray = ByteArray(0),
    private val responseHeaders: Map<String, List<String>> = emptyMap(),
    private val connectError: IOException? = null,
    private val onConnect: (Map<String, String>) -> Unit = {},
) : HttpURLConnection(url) {
    private val requestProps = LinkedHashMap<String, String>()

    override fun setRequestProperty(key: String?, value: String?) {
        if (key != null && value != null) {
            requestProps[key] = value
        }
    }

    override fun connect() {
        connectError?.let { throw it }
        onConnect(LinkedHashMap(requestProps))
        connected = true
    }

    override fun getResponseCode(): Int = status

    override fun getContentLengthLong(): Long = bodyBytes.size.toLong()

    override fun getHeaderFields(): Map<String, List<String>> = responseHeaders

    override fun getHeaderField(name: String?): String? =
        responseHeaders.entries
            .firstOrNull { (k, _) -> k.equals(name, ignoreCase = true) }
            ?.value
            ?.lastOrNull()

    override fun getInputStream(): InputStream = ByteArrayInputStream(bodyBytes)

    override fun getErrorStream(): InputStream = ByteArrayInputStream(bodyBytes)

    override fun disconnect() = Unit

    override fun usingProxy(): Boolean = false
}

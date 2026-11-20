package com.grayvines.roche.data.remote

import com.grayvines.roche.core.isPublicInetAddress
import com.grayvines.roche.core.isSyncablePublicUrl
import com.grayvines.roche.data.sync.PeerSyncScheduler
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicUrlValidatorTest {
    @Test
    fun `VPN gate covers https ts_net and scheduler requires VPN for Tailnet hosts`() = runTest {
        val vpnOffClient =
            RocheApiClient(
                vpnCheck = VpnTransportCheck { false },
                transport = { HttpResponse(statusCode = 200, body = "<opml/>") },
            )
        assertThrows(IOException::class.java) {
            runBlocking { vpnOffClient.exportOpml("https://roche.tail9876.ts.net", "tok") }
        }
        assertThrows(IOException::class.java) {
            runBlocking { vpnOffClient.exportOpml("https://roche", "tok") }
        }
        assertEquals(
            "<opml/>",
            vpnOffClient.exportOpml("http://localhost:8080", "tok"),
        )

        val vpnOnClient =
            RocheApiClient(
                vpnCheck = VpnTransportCheck { true },
                transport = { HttpResponse(statusCode = 200, body = "<opml/>") },
            )
        assertThrows(IOException::class.java) {
            runBlocking { vpnOnClient.exportOpml("http://mac-mini.tail9876.ts.net:8080", "tok") }
        }
        assertThrows(IOException::class.java) {
            runBlocking { vpnOnClient.exportOpml("http://100.64.0.9:8080", "tok") }
        }

        assertTrue(PeerSyncScheduler.requiresVpnForServerUrl("https://roche.tail9876.ts.net"))
        assertTrue(PeerSyncScheduler.requiresVpnForServerUrl("https://100.64.0.9:8443"))
        assertFalse(PeerSyncScheduler.requiresVpnForServerUrl("http://localhost:8080"))
        assertFalse(PeerSyncScheduler.requiresVpnForServerUrl(""))
    }

    @Test
    fun `cross-origin server redirects enforce public IP SSRF checks and strip Authorization`() {
        val serverReq =
            HttpRequest(
                method = "GET",
                url = "https://roche.tail9876.ts.net/api/v1/media/m1",
                headers = mapOf("Authorization" to "Bearer secret"),
                requirePublicIp = false,
            )
        val sameOriginRedirect =
            buildRedirectedRequest(
                currentRequest = serverReq,
                statusCode = 307,
                nextUrl = "https://roche.tail9876.ts.net/api/v1/media/m2",
            )
        assertFalse(sameOriginRedirect.requirePublicIp)
        assertEquals("Bearer secret", sameOriginRedirect.headers["Authorization"])

        val crossOriginRedirect =
            buildRedirectedRequest(
                currentRequest = serverReq,
                statusCode = 303,
                nextUrl = "https://192.168.1.1/admin",
            )
        assertTrue(crossOriginRedirect.requirePublicIp)
        assertFalse(crossOriginRedirect.headers.containsKey("Authorization"))
        assertThrows(IOException::class.java) {
            PublicUrlValidator.ensurePublicTarget(crossOriginRedirect.url)
        }
    }

    @Test
    fun `server DNS check blocks local poisoning and public http upgrades when cleartext blocked`() {
        assertThrows(IOException::class.java) {
            PublicUrlValidator.ensureResolvedServerTarget(
                rawUrl = "https://roche",
                resolveAddresses = { arrayOf(InetAddress.getByName("192.168.1.1")) },
            )
        }
        assertThrows(IOException::class.java) {
            PublicUrlValidator.ensureResolvedServerTarget(
                rawUrl = "http://100.64.0.9:8080/health",
                resolveAddresses = { arrayOf(InetAddress.getByName("100.64.0.9")) },
            )
        }
        assertThrows(IOException::class.java) {
            PublicUrlValidator.ensurePublicUrlSyntax("https://user@bad_host/feed.xml")
        }
        assertThrows(IOException::class.java) {
            PublicUrlValidator.ensureSafeServerEndpoint("https://user@bad_host/health") { true }
        }
        assertEquals(
            "https://export.arxiv.org/rss/cs.LG",
            PublicUrlValidator.upgradePublicHttp(
                rawUrl = "http://export.arxiv.org/rss/cs.LG",
                isCleartextPermitted = { false },
            ),
        )
        assertEquals(
            "http://127.0.0.1:8080/rss.xml",
            PublicUrlValidator.upgradePublicHttp(
                rawUrl = "http://127.0.0.1:8080/rss.xml",
                isCleartextPermitted = { false },
            ),
        )
    }

    @Test
    fun `SSRF IP checks block special IPv6 ranges embedded private IPv4 and IPv4 doc or benchmark blocks`() {
        BLOCKED_SPECIAL_IPS.forEach { ip ->
            val addr = InetAddress.getByName(ip)
            assertFalse(isPublicInetAddress(addr), "Expected non-public IP: $ip")
            assertThrows(
                IOException::class.java,
                {
                    PublicUrlValidator.ensurePublicTarget(
                        rawUrl = "https://example.com/feed.xml",
                        resolveAddresses = { arrayOf(addr) },
                    )
                },
                "Expected ensurePublicTarget to block resolved IP: $ip",
            )
        }

        assertFalse(isPublicInetAddress(ipv4MappedV6(127, 0, 0, 1)))
        assertFalse(isPublicInetAddress(ipv4MappedV6(192, 0, 2, 1)))
        assertTrue(isPublicInetAddress(ipv4MappedV6(93, 184, 216, 34)))

        assertTrue(isPublicInetAddress(InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")))
        assertTrue(isPublicInetAddress(InetAddress.getByName("2001:4860:4860::8888")))
        assertTrue(isPublicInetAddress(InetAddress.getByName("93.184.216.34")))
    }

    @Test
    fun `public URL validators allow ports 80 443 8080 8443 and block other ports and special IPv6 literals`() {
        val allowedUrls =
            listOf(
                "https://example.com/rss.xml",
                "http://example.com:80/rss.xml",
                "https://example.com:443/rss.xml",
                "http://example.com:8080/rss.xml",
                "https://example.com:8443/rss.xml",
            )
        allowedUrls.forEach { url ->
            assertEquals("example.com", PublicUrlValidator.ensurePublicUrlSyntax(url), url)
            assertTrue(isSyncablePublicUrl(url), url)
        }

        val blockedUrls =
            listOf(
                "https://example.com:22/rss.xml",
                "http://example.com:6379/rss.xml",
                "https://example.com:9000/rss.xml",
                "https://example.com:8444/rss.xml",
                "https://example.com:0/rss.xml",
                "https://[2001:db8::1]/rss.xml",
                "https://[64:ff9b::1.2.3.4]/rss.xml",
                "https://[2002:7f00:1::1]/rss.xml",
                "https://[2001:20::1]/rss.xml",
                "https://[100::1]/rss.xml",
                "https://[2001::1]/rss.xml",
                "http://192.0.2.1/rss.xml",
                "http://198.18.0.1/rss.xml",
            )
        blockedUrls.forEach { url ->
            assertThrows(
                IOException::class.java,
                { PublicUrlValidator.ensurePublicUrlSyntax(url) },
                "Expected ensurePublicUrlSyntax to reject: $url",
            )
            assertFalse(isSyncablePublicUrl(url), "Expected isSyncablePublicUrl to reject: $url")
        }
    }
}

private val BLOCKED_SPECIAL_IPS =
    listOf(
        "64:ff9b::1.2.3.4",
        "64:ff9b::127.0.0.1",
        "64:ff9b:1::1",
        "100::1",
        "2001:db8::1",
        "2001:20::1",
        "2001:2f::ffff",
        "2001:2::1",
        "2001::1",
        "2002:7f00:1::1",
        "2002:5db8:d822::1",
        "::127.0.0.1",
        "::10.0.0.1",
        "192.0.2.1",
        "198.51.100.1",
        "203.0.113.1",
        "198.18.0.1",
        "198.19.255.254",
        "240.0.0.1",
        "255.255.255.255",
        "0.0.0.1",
    )

private fun ipv4MappedV6(b0: Int, b1: Int, b2: Int, b3: Int): Inet6Address =
    Inet6Address.getByAddress(
        "mapped.example",
        byteArrayOf(
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            -1,
            -1,
            b0.toByte(),
            b1.toByte(),
            b2.toByte(),
            b3.toByte(),
        ),
        -1,
    )

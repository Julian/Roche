package com.grayvines.roche.data.remote

import com.grayvines.roche.data.PeerSyncScheduler
import java.io.IOException
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
                transport = { HttpResponse(statusCode = 200, body = """{"feeds":[]}""") },
            )
        assertThrows(IOException::class.java) {
            runBlocking { vpnOffClient.fetchFeeds("https://roche.tail9876.ts.net", "tok") }
        }
        assertThrows(IOException::class.java) {
            runBlocking { vpnOffClient.fetchFeeds("https://roche", "tok") }
        }
        assertEquals(
            emptyList<RemoteFeedDto>(),
            vpnOffClient.fetchFeeds("http://localhost:8080", "tok"),
        )

        assertTrue(PeerSyncScheduler.requiresVpnForServerUrl("https://roche.tail9876.ts.net"))
        assertTrue(PeerSyncScheduler.requiresVpnForServerUrl("http://100.64.0.9:8080"))
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
                isCleartextPermitted = { false },
            )
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
}

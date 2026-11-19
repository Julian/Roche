package com.grayvines.roche.data.remote

import com.grayvines.roche.data.SyncCollectionEntryDto
import com.grayvines.roche.data.SyncEntryContentDto
import com.grayvines.roche.data.SyncEntryRecordDto
import com.grayvines.roche.data.SyncEntryStateDto
import com.grayvines.roche.data.SyncFeedSourceDto
import com.grayvines.roche.data.SyncTombstoneDto
import com.grayvines.roche.model.ContentType
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Suppress("LargeClass")
class RocheApiClientTest {
    @TempDir lateinit var tempDir: File

    @Test
    fun `fetchItems parses enriched item fields from server`() = runTest {
        val body = buildJsonObject {
            putJsonArray("items") {
                addJsonObject {
                    put("id", "item-42")
                    put("feed_id", "feed-1")
                    put("feed_title", "arXiv cs.LG")
                    put("title", "Test Paper")
                    put("url", "https://arxiv.org/abs/2603.00001")
                    put("author", "A. Researcher")
                    put("summary", "Summary text")
                    put("content_text", "Full text")
                    put("content_html", "<p>Full text</p>")
                    put("content_type", "paper")
                    put("status", "unread")
                    put("is_favorite", true)
                    put("published_at", "2026-03-29T10:00:00Z")
                    put("created_at", "2026-03-29T10:00:00Z")
                    put("read_progress", 0.5)
                    put("thumbnail_media_id", "media-1")
                    put("duration", 1694)
                    putJsonArray("tag_names") {
                        add(kotlinx.serialization.json.JsonPrimitive("systems"))
                        add(kotlinx.serialization.json.JsonPrimitive("llm-inference"))
                    }
                }
            }
            put("total", 1)
        }
            .toString()

        val client = RocheApiClient { request ->
            assertEquals("http://localhost:8080/api/v1/items?per_page=200", request.url)
            assertEquals("Bearer token-123", request.headers["Authorization"])
            HttpResponse(statusCode = 200, body = body)
        }

        val items = client.fetchItems(serverUrl = "http://localhost:8080", token = "token-123")
        assertEquals(1, items.size)
        val first = items.first()
        assertEquals("item-42", first.id)
        assertEquals("arXiv cs.LG", first.feedTitle)
        assertEquals("media-1", first.thumbnailMediaId)
        assertEquals("28:14", first.duration)
        assertEquals(listOf("systems", "llm-inference"), first.tagNames)
        assertEquals(ContentType.PDF, ContentType.fromWireValue(first.contentType))
    }

    @Test
    fun `fetchFeeds fetchTags pairDevice and saveItemUrl match server wire envelopes`() = runTest {
        val client = RocheApiClient { request ->
            when (request.url) {
                "http://localhost:8080/api/v1/feeds" -> {
                    HttpResponse(
                        statusCode = 200,
                        body =
                            """{"feeds":[{"id":"f-1","title":"LWN","url":"https://lwn.net/rss","feed_type":"rss","unread_count":3}]}""",
                    )
                }
                "http://localhost:8080/api/v1/tags" -> {
                    HttpResponse(
                        statusCode = 200,
                        body = """{"tags":[{"id":"t-1","name":"systems","unread_count":2}]}""",
                    )
                }
                "http://localhost:8080/auth/pair" -> {
                    assertEquals(
                        true,
                        request.body?.contains(""""pairing_code":"123456"""") == true,
                    )
                    HttpResponse(statusCode = 200, body = """{"token":"tok-abc"}""")
                }
                "http://localhost:8080/api/v1/items/save" -> {
                    HttpResponse(
                        statusCode = 201,
                        body =
                            """{"id":"i-9","title":"Saved","url":"https://example.com","content_type":"article","status":"unread","is_favorite":false,"created_at":"2026-03-29T10:00:00Z"}""",
                    )
                }
                else -> {
                    HttpResponse(statusCode = 404, body = "{}")
                }
            }
        }

        val feeds = client.fetchFeeds("http://localhost:8080", "tok")
        assertEquals(1, feeds.size)
        assertEquals("LWN", feeds.first().title)

        val tags = client.fetchTags("http://localhost:8080", "tok")
        assertEquals(1, tags.size)
        assertEquals("systems", tags.first().name)

        val token = client.pairDevice("http://localhost:8080", "123456", "Pixel")
        assertEquals("tok-abc", token)

        val saved =
            client.saveItemUrl(
                "http://localhost:8080",
                "tok",
                "https://example.com",
                "Saved",
                listOf("systems"),
            )
        assertEquals("i-9", saved.id)
    }

    @Test
    fun `error responses throw ProblemDetailsException with RFC 9457 fields`() {
        val problemBody = buildJsonObject {
            put("type", "about:blank")
            put("title", "Unauthorized")
            put("status", 401)
            put("detail", "Invalid or expired bearer token")
        }
            .toString()

        val client = RocheApiClient { HttpResponse(statusCode = 401, body = problemBody) }

        val error =
            assertThrows(ProblemDetailsException::class.java) {
                runTest { client.fetchFeeds(serverUrl = "http://localhost:8080", token = "bad") }
            }
        assertEquals(401, error.statusCode)
        assertEquals("Unauthorized", error.title)
        assertEquals("Invalid or expired bearer token", error.detail)
    }

    @Test
    fun `discoverServer probes Tailscale candidates and adopts canonical_url from health endpoint`() =
        runTest {
            val client = RocheApiClient { request ->
                if (request.url == "https://roche/health") {
                    HttpResponse(
                        statusCode = 200,
                        body =
                            """{"status":"ok","service":"roche","version":"0.1.0","canonical_url":"https://mac-mini.tail9876.ts.net"}""",
                    )
                } else {
                    HttpResponse(statusCode = 404, body = "{}")
                }
            }

            val discovered = client.discoverServer()
            assertEquals("https://roche", discovered?.probedUrl)
            assertEquals("https://mac-mini.tail9876.ts.net", discovered?.preferredUrl)
            assertEquals("0.1.0", discovered?.version)
        }

    @Test
    fun `discoverServer ignores non-roche services and rejects untrusted or cleartext canonical_url redirects`() =
        runTest {
            val client = RocheApiClient { request ->
                when (request.url) {
                    "https://roche.tail9876.ts.net/health" ->
                        // Non-Roche service returning generic {"status":"ok"} without
                        // "service":"roche"
                        HttpResponse(statusCode = 200, body = """{"status":"ok"}""")
                    "https://roche/health" ->
                        // Compromised/misconfigured response trying to downgrade to cleartext http
                        HttpResponse(
                            statusCode = 200,
                            body =
                                """{"status":"ok","service":"roche","canonical_url":"http://roche.tail9876.ts.net:8080"}""",
                        )
                    else -> HttpResponse(statusCode = 404, body = "{}")
                }
            }

            val discovered = client.discoverServer(tailnetDomains = listOf("tail9876.ts.net"))
            assertEquals("https://roche", discovered?.probedUrl)
            assertEquals("https://roche", discovered?.preferredUrl)
            assertEquals(null, discovered?.canonicalUrl)
        }

    @Test
    fun `RocheApiClient rejects cleartext non-Tailscale server URLs VPN-off cleartext and SSRF targets`() =
        runTest {
            val vpnOffClient =
                RocheApiClient(
                    vpnCheck = VpnTransportCheck { false },
                    transport = { HttpResponse(statusCode = 200, body = """{"feeds":[]}""") },
                )
            val tailscaleVpnOffErr = runCatching {
                vpnOffClient.fetchFeeds(
                    serverUrl = "http://100.64.0.9:8080",
                    token = "secret",
                )
            }
                .exceptionOrNull()
            assertEquals(true, tailscaleVpnOffErr is java.io.IOException)

            val lanCleartextErr = runCatching {
                vpnOffClient.fetchFeeds(
                    serverUrl = "http://192.168.1.50:8080",
                    token = "secret",
                )
            }
                .exceptionOrNull()
            assertEquals(true, lanCleartextErr is java.io.IOException)

            val loopbackFeedErr = runCatching {
                vpnOffClient.fetchExternalFeedXml("http://127.0.0.1:8080/feed.xml").getOrThrow()
            }
                .exceptionOrNull()
            assertEquals(true, loopbackFeedErr is java.io.IOException)

            assertEquals(
                null,
                vpnOffClient.fetchExternalImageBytes("http://100.64.0.9:8080/private.png"),
            )
        }

    @Test
    internal fun `updateItem sends PATCH with status is_favorite and read_progress`() = runTest {
        var capturedRequest: HttpRequest? = null
        val client = RocheApiClient { request ->
            capturedRequest = request
            HttpResponse(
                statusCode = 200,
                body =
                    """{"id":"item-42","title":"Test","url":"https://example.com","content_type":"article","status":"archived","is_favorite":true,"read_progress":0.75,"created_at":"2026-03-29T10:00:00Z"}""",
            )
        }

        val updated =
            client.updateItem(
                serverUrl = "http://localhost:8080",
                token = "tok-123",
                itemId = "item-42",
                status = "archived",
                isFavorite = true,
                readProgress = 0.75,
            )

        assertEquals("PATCH", capturedRequest?.method)
        assertEquals("http://localhost:8080/api/v1/items/item-42", capturedRequest?.url)
        assertEquals(
            """{"status":"archived","is_favorite":true,"read_progress":0.75}""",
            capturedRequest?.body,
        )
        assertEquals("archived", updated.status)
        assertEquals(true, updated.isFavorite)
        assertEquals(0.75, updated.readProgress)
    }

    @Test
    internal fun `fetchItems paginates across pages when page 1 returns 200 items`() = runTest {
        val page1Body = buildJsonObject {
            putJsonArray("items") {
                for (index in 1..200) {
                    addJsonObject {
                        put("id", "item-$index")
                        put("title", "Entry $index")
                        put("url", "https://example.com/$index")
                        put("content_type", "article")
                        put("status", "unread")
                        put("created_at", "2026-03-29T10:00:00Z")
                    }
                }
            }
        }
            .toString()
        val page2Body = buildJsonObject {
            putJsonArray("items") {
                addJsonObject {
                    put("id", "item-201")
                    put("title", "Entry 201")
                    put("url", "https://example.com/201")
                    put("content_type", "article")
                    put("status", "unread")
                    put("created_at", "2026-03-29T09:00:00Z")
                }
            }
        }
            .toString()

        val requestedUrls = mutableListOf<String>()
        val client = RocheApiClient { request ->
            requestedUrls.add(request.url)
            when (request.url) {
                "http://localhost:8080/api/v1/items?per_page=200" ->
                    HttpResponse(statusCode = 200, body = page1Body)
                "http://localhost:8080/api/v1/items?per_page=200&page=2" ->
                    HttpResponse(statusCode = 200, body = page2Body)
                else -> HttpResponse(statusCode = 404, body = "{}")
            }
        }

        val items = client.fetchItems(serverUrl = "http://localhost:8080", token = "tok-123")
        assertEquals(201, items.size)
        assertEquals("item-1", items.first().id)
        assertEquals("item-201", items.last().id)
        assertEquals(
            listOf(
                "http://localhost:8080/api/v1/items?per_page=200",
                "http://localhost:8080/api/v1/items?per_page=200&page=2",
            ),
            requestedUrls,
        )
    }

    @Test
    internal fun `fetchItemDetail fetchItemMedia and fetchMediaBytes parse item details and binary media`() =
        runTest {
            val expectedBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            val client = RocheApiClient { request ->
                when (request.url) {
                    "http://localhost:8080/api/v1/items/item-42" ->
                        HttpResponse(
                            statusCode = 200,
                            body =
                                """{"id":"item-42","title":"Full Paper","url":"https://arxiv.org/abs/2603.00001","content_html":"<p>Hydrated article body</p>","content_type":"paper","status":"unread","is_favorite":false,"created_at":"2026-03-29T10:00:00Z","thumbnail_media_id":"thumb-42"}""",
                        )
                    "http://localhost:8080/api/v1/items/item-42/media" ->
                        HttpResponse(
                            statusCode = 200,
                            body =
                                """{"media":[{"id":"thumb-42","item_id":"item-42","media_type":"thumbnail","file_size":8},{"id":"pdf-42","item_id":"item-42","media_type":"pdf","file_size":4096}]}""",
                        )
                    "http://localhost:8080/api/v1/media/thumb-42" ->
                        HttpResponse(
                            statusCode = 200,
                            body = "",
                            bodyBytes = expectedBytes,
                        )
                    else -> HttpResponse(statusCode = 404, body = "{}")
                }
            }

            val detail = client.fetchItemDetail("http://localhost:8080", "tok-123", "item-42")
            assertEquals("item-42", detail.id)
            assertEquals("<p>Hydrated article body</p>", detail.contentHtml)
            assertEquals("thumb-42", detail.thumbnailMediaId)

            val mediaList = client.fetchItemMedia("http://localhost:8080", "tok-123", "item-42")
            assertEquals(2, mediaList.size)
            assertEquals("thumb-42", mediaList[0].id)
            assertEquals("thumbnail", mediaList[0].mediaType)
            assertEquals(8L, mediaList[0].fileSize)
            assertEquals("pdf-42", mediaList[1].id)
            assertEquals("pdf", mediaList[1].mediaType)

            val bytes = client.fetchMediaBytes("http://localhost:8080", "tok-123", "thumb-42")
            org.junit.jupiter.api.Assertions.assertArrayEquals(expectedBytes, bytes)

            val missingBytes = client.fetchMediaBytes("http://localhost:8080", "tok-123", "missing")
            assertEquals(null, missingBytes)

            val exactLimit =
                readBoundedBytes(java.io.ByteArrayInputStream(ByteArray(16)), maxBytes = 16)
            assertEquals(16, exactLimit.size)
            assertThrows(java.io.IOException::class.java) {
                readBoundedBytes(java.io.ByteArrayInputStream(ByteArray(17)), maxBytes = 16)
            }
            assertEquals("..%2F..%2Fsecret", encodePathSegment("../../secret"))
        }

    @Test
    internal fun `refreshFeed deleteFeed and deleteItem send expected endpoints and methods`() =
        runTest {
            val recorded = mutableListOf<String>()
            val client = RocheApiClient { request ->
                recorded.add("${request.method} ${request.url}")
                when {
                    request.method == "POST" &&
                        request.url == "http://localhost:8080/api/v1/feeds/feed-1/refresh" ->
                        HttpResponse(
                            statusCode = 200,
                            body =
                                """{"id":"feed-1","title":"Refreshed Feed","url":"https://example.com/rss","feed_type":"rss","unread_count":4}""",
                        )
                    request.method == "DELETE" &&
                        request.url == "http://localhost:8080/api/v1/feeds/feed-1" ->
                        HttpResponse(statusCode = 204, body = "")
                    request.method == "DELETE" &&
                        request.url == "http://localhost:8080/api/v1/items/item-99" ->
                        HttpResponse(statusCode = 204, body = "")
                    else -> HttpResponse(statusCode = 404, body = "{}")
                }
            }

            val refreshed = client.refreshFeed("http://localhost:8080", "tok-123", "feed-1")
            assertEquals("feed-1", refreshed.id)
            assertEquals("Refreshed Feed", refreshed.title)
            assertEquals(4, refreshed.unreadCount)

            client.deleteFeed("http://localhost:8080", "tok-123", "feed-1")
            client.deleteItem("http://localhost:8080", "tok-123", "item-99")

            assertEquals(
                listOf(
                    "POST http://localhost:8080/api/v1/feeds/feed-1/refresh",
                    "DELETE http://localhost:8080/api/v1/feeds/feed-1",
                    "DELETE http://localhost:8080/api/v1/items/item-99",
                ),
                recorded,
            )
        }

    @Test
    internal fun `archiveBatch importOpml and exportOpml send expected requests and parse responses`() =
        runTest {
            val recorded = mutableListOf<Pair<String, String?>>()
            val client = RocheApiClient { request ->
                recorded.add("${request.method} ${request.url}" to request.body)
                when {
                    request.method == "POST" &&
                        request.url == "http://localhost:8080/api/v1/items/archive-batch" ->
                        HttpResponse(statusCode = 200, body = """{"archived_count":3}""")
                    request.method == "POST" &&
                        request.url == "http://localhost:8080/api/v1/import/opml" ->
                        HttpResponse(
                            statusCode = 200,
                            body = """{"imported_count":70,"feeds":[]}""",
                        )
                    request.method == "GET" &&
                        request.url == "http://localhost:8080/api/v1/export/feeds" ->
                        HttpResponse(
                            statusCode = 200,
                            body = """<?xml version="1.0"?><opml version="2.0"><body/></opml>""",
                        )
                    else -> HttpResponse(statusCode = 404, body = "{}")
                }
            }

            val archived =
                client.archiveBatch(
                    serverUrl = "http://localhost:8080",
                    token = "tok-123",
                    itemIds = listOf("i-1", "i-2", "i-3"),
                    feedId = "feed-1",
                    allUnread = false,
                )
            assertEquals(3, archived)

            val imported =
                client.importOpml(
                    serverUrl = "http://localhost:8080",
                    token = "tok-123",
                    opmlXml = "<opml/>",
                )
            assertEquals(70, imported)

            val exported = client.exportOpml(serverUrl = "http://localhost:8080", token = "tok-123")
            assertEquals("""<?xml version="1.0"?><opml version="2.0"><body/></opml>""", exported)
            assertEquals(3, recorded.size)
        }

    @Test
    internal fun `redirect strips Authorization header on https to http downgrade port change and cross host`() {
        val httpsOrigin = "https://roche.example.com/api/v1/feeds"
        assertFalse(isSameOriginHost(httpsOrigin, "http://roche.example.com/a"))
        assertFalse(isSameOriginHost(httpsOrigin, "https://roche.example.com:8443/a"))
        assertFalse(isSameOriginHost(httpsOrigin, "https://cdn.example.com/a"))
        assertTrue(isSameOriginHost(httpsOrigin, "https://roche.example.com:443/a"))

        val authed =
            HttpRequest(
                method = "POST",
                url = "https://roche.example.com/api/v1/sync/peer",
                headers =
                    mapOf("Authorization" to "Bearer secret-tok", "Accept" to "application/json"),
                body = """{"peer_id":"p1"}""",
            )
        val downgraded =
            buildRedirectedRequest(
                currentRequest = authed,
                statusCode = 302,
                nextUrl = "http://roche.example.com/api/v1/sync/peer",
            )
        assertFalse("Authorization" in downgraded.headers)
        assertEquals("GET", downgraded.method)
        assertEquals(null, downgraded.body)

        val crossPort =
            buildRedirectedRequest(
                currentRequest = authed,
                statusCode = 307,
                nextUrl = "https://roche.example.com:8443/api/v1/sync/peer",
            )
        assertFalse("Authorization" in crossPort.headers)

        val sameOrigin =
            buildRedirectedRequest(
                currentRequest = authed,
                statusCode = 307,
                nextUrl = "https://roche.example.com:443/api/v1/sync/peer?v=2",
            )
        assertEquals("Bearer secret-tok", sameOrigin.headers["Authorization"])
        assertEquals("POST", sameOrigin.method)
    }

    @Test
    internal fun `updateFeedTitle sends PATCH with title payload`() = runTest {
        var capturedPatch: HttpRequest? = null
        val client = RocheApiClient { request ->
            capturedPatch = request
            HttpResponse(
                statusCode = 200,
                body =
                    """{"id":"feed-7","title":"Renamed Title","url":"https://example.com/rss","feed_type":"rss","unread_count":1}""",
            )
        }
        val updated =
            client.updateFeedTitle(
                serverUrl = "http://localhost:8080",
                bearerToken = "tok-rename",
                feedId = "feed-7",
                title = "Renamed Title",
            )
        assertEquals("Renamed Title", updated.title)
        assertEquals("PATCH", capturedPatch?.method)
        assertEquals("http://localhost:8080/api/v1/feeds/feed-7", capturedPatch?.url)
        assertEquals("Bearer tok-rename", capturedPatch?.headers?.get("Authorization"))
        assertEquals("""{"title":"Renamed Title"}""", capturedPatch?.body)
    }

    @Test
    internal fun `streamMediaToFile streams authenticated bytes and cleans up on error or overflow`() =
        runTest {
            val mediaPayload = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x01, 0x02)
            var capturedMediaAuth: String? = null
            val client = RocheApiClient { request ->
                if (request.url == "http://localhost:8080/api/v1/media/audio-7") {
                    capturedMediaAuth = request.headers["Authorization"]
                    HttpResponse(statusCode = 200, body = "", bodyBytes = mediaPayload)
                } else {
                    HttpResponse(statusCode = 503, body = "{}")
                }
            }

            val targetFile = File(tempDir, "streamed_audio.mp3")
            assertTrue(
                client.streamMediaToFile(
                    "http://localhost:8080",
                    "tok-media",
                    "audio-7",
                    targetFile,
                )
            )
            assertEquals("Bearer tok-media", capturedMediaAuth)
            assertArrayEquals(mediaPayload, targetFile.readBytes())

            val oversizedFile = File(tempDir, "oversized_audio.mp3")
            assertFalse(
                client.streamMediaToFile(
                    serverUrl = "http://localhost:8080",
                    token = "tok-media",
                    mediaId = "audio-7",
                    targetFile = oversizedFile,
                    maxBytes = 4L,
                )
            )
            assertFalse(oversizedFile.exists())

            val failedFile = File(tempDir, "failed_audio.mp3")
            assertFalse(
                client.streamMediaToFile(
                    serverUrl = "http://localhost:8080",
                    token = "tok-media",
                    mediaId = "missing-audio",
                    targetFile = failedFile,
                )
            )
            assertFalse(failedFile.exists())

            verifyCustomStreamToFileOverride(tempDir, mediaPayload)
        }

    @Test
    internal fun `RocheJsonCodec handles unread tombstone blank IDs polymorphic tags and peer sync`() {
        assertEquals(
            """{"read_at":null}""",
            buildUpdateItemPayload(
                status = null,
                isFavorite = null,
                readProgress = null,
                readAt = UNREAD_READ_AT_TOMBSTONE,
            ),
        )
        assertEquals(
            """{"status":"unread"}""",
            buildUpdateItemPayload(
                status = "unread",
                isFavorite = null,
                readProgress = null,
                readAt = null,
            ),
        )

        val feeds =
            parseFeedsJsonArray(
                """[{"id":"","title":"Skip"},{"id":"f-ok","title":"Keep","url":"https://a.com/rss"}]"""
            )
        assertEquals(listOf("f-ok"), feeds.map { it.id })

        val objTagsItem =
            parseItemJson(
                """{"id":"i-1","title":"T","url":"https://a.com/1",""" +
                    """"duration":"14:30","tags":[{"id":"t1","name":" Systems "},""]}"""
            )
        assertEquals(listOf("systems"), objTagsItem.tagNames)
        assertEquals("14:30", objTagsItem.duration)

        val strTagsItems =
            parseItemsEnvelopeJson(
                """{"items":[{"id":" ","title":"Skip"},""" +
                    """{"id":"i-2","title":"T2","url":"https://a.com/2","tags":["Rust"," EBPF "]}]}"""
            )
        assertEquals(listOf("i-2"), strTagsItems.map { it.id })
        assertEquals(listOf("rust", "ebpf"), strTagsItems.single().tagNames)

        verifyPeerSyncWireRoundTrip()
    }

    @Test
    internal fun `RFC 9110 redirects preserve non-POST on 301 and 302 and rewrite to GET on 303`() {
        val baseHeaders =
            mapOf(
                "Authorization" to "Bearer tok",
                "content-type" to "application/json",
                "Content-Length" to "15",
            )
        for (method in listOf("DELETE", "PATCH", "PUT")) {
            val req =
                HttpRequest(
                    method = method,
                    url = "https://roche.example.com/api/v1/feeds/f1",
                    headers = baseHeaders,
                    body = """{"force":true}""",
                )
            for (code in listOf(TEST_HTTP_301, TEST_HTTP_302)) {
                val redirected =
                    buildRedirectedRequest(req, code, "https://roche.example.com/api/v2/feeds/f1")
                assertEquals(method, redirected.method)
                assertEquals("""{"force":true}""", redirected.body)
                assertEquals("application/json", redirected.headers["content-type"])
                assertEquals("15", redirected.headers["Content-Length"])
            }
            val seeOther =
                buildRedirectedRequest(
                    req,
                    TEST_HTTP_303,
                    "https://roche.example.com/api/v2/feeds/f1",
                )
            assertPayloadStrippedGetRedirect(seeOther)
        }

        val postReq =
            HttpRequest(
                method = "POST",
                url = "https://roche.example.com/api/v1/items",
                headers = baseHeaders,
                body = """{"url":"x"}""",
            )
        assertPayloadStrippedGetRedirect(
            buildRedirectedRequest(postReq, TEST_HTTP_301, "https://roche.example.com/v2")
        )
        assertPayloadStrippedGetRedirect(
            buildRedirectedRequest(postReq, TEST_HTTP_302, "https://roche.example.com/v2")
        )

        val headReq = HttpRequest(method = "HEAD", url = "https://roche.example.com/v1")
        assertEquals(
            "HEAD",
            buildRedirectedRequest(headReq, TEST_HTTP_303, "https://roche.example.com/v2").method,
        )
    }

    @Test
    internal fun `cross-origin redirect strips sensitive headers and upgraded URL stays https`() {
        val req =
            HttpRequest(
                method = "GET",
                url = "http://feeds.example.com/old.xml",
                headers =
                    mapOf(
                        "authorization" to "Bearer secret",
                        "cookie" to "sid=abc",
                        "Proxy-Authorization" to "Basic xyz",
                        "Accept" to "application/rss+xml",
                    ),
                requirePublicIp = true,
            )
        val effective =
            req.copy(
                url =
                    PublicUrlValidator.upgradePublicHttp(
                        req.url,
                        isCleartextPermitted = { false },
                    )
            )
        assertEquals("https://feeds.example.com/old.xml", effective.url)

        val sameOriginRelative =
            resolveNextRedirectHop(
                effectiveRequest = effective,
                statusCode = TEST_HTTP_301,
                location = "/new/index.xml",
                hop = 0,
                maxRedirects = MAX_REDIRECTS,
            )!!
        assertEquals("https://feeds.example.com/new/index.xml", sameOriginRelative.url)
        assertEquals("Bearer secret", sameOriginRelative.headers["authorization"])
        assertEquals("sid=abc", sameOriginRelative.headers["cookie"])

        val crossOrigin =
            resolveNextRedirectHop(
                effectiveRequest = effective,
                statusCode = TEST_HTTP_302,
                location = "https://cdn.other-domain.example/feed.xml",
                hop = 0,
                maxRedirects = MAX_REDIRECTS,
            )!!
        assertFalse(crossOrigin.headers.keys.any { it.equals("Authorization", ignoreCase = true) })
        assertFalse(crossOrigin.headers.keys.any { it.equals("Cookie", ignoreCase = true) })
        assertFalse(
            crossOrigin.headers.keys.any { it.equals("Proxy-Authorization", ignoreCase = true) }
        )
        assertEquals("application/rss+xml", crossOrigin.headers["Accept"])
    }

    @Test
    internal fun `callbacks isolate part files protect targetFile on cancel drop text bodyBytes and report blank Location`() =
        runTest {
            val target = File(tempDir, "episode.mp3")
            target.writeBytes("keep-existing".toByteArray())
            val firstOpened = openPartFileChannel(target)!!
            val secondOpened = openPartFileChannel(target)!!
            assertNotEquals(firstOpened.first, secondOpened.first)
            secondOpened.second.write(ByteBuffer.wrap("retry-bytes".toByteArray()))
            firstOpened.second.close()
            firstOpened.first.delete()
            assertTrue(secondOpened.first.exists())
            secondOpened.second.close()
            secondOpened.first.delete()

            verifyCancelledStreamingNeverOverwritesTarget(target)
            verifyBufferedCallbackHeapAndBlankLocation()
            assertTrue(
                exceedsByteBudget(
                    bytesWritten = TEST_WRITTEN_BYTES,
                    incoming = Long.MAX_VALUE,
                    maxBytes = TEST_MAX_BUDGET_BYTES,
                )
            )
            assertFalse(
                exceedsByteBudget(
                    bytesWritten = TEST_WRITTEN_BYTES,
                    incoming = TEST_SAFE_INCOMING_BYTES,
                    maxBytes = TEST_MAX_BUDGET_BYTES,
                )
            )

            val activePaths = mutableSetOf<String>()
            val storagePath = prepareHttpEngineStoragePath(tempDir, activePaths)
            assertNotNull(storagePath)
            assertTrue(File(storagePath!!).isDirectory)
            assertTrue(storagePath.endsWith(HTTP_ENGINE_STORAGE_DIR_NAME))
            // Second concurrent engine on the same cacheDir avoids disk-lock collision
            assertNull(prepareHttpEngineStoragePath(tempDir, activePaths))
            releaseHttpEngineStoragePath(storagePath, activePaths)
            // After close/release, cold-start or reopened engine reuses persistent QUIC/TLS cache
            // dir
            assertEquals(storagePath, prepareHttpEngineStoragePath(tempDir, activePaths))
            releaseHttpEngineStoragePath(storagePath, activePaths)
        }

    @Test
    internal fun `RFC 9457 ProblemDetails parses type retry_after_seconds Retry-After header max_bytes and candidates`() =
        runTest {
            val rateLimitedClient = RocheApiClient {
                HttpResponse(
                    statusCode = TEST_HTTP_429,
                    body =
                        """{"type":"urn:roche:problem:pairing-rate-limited","title":"Too Many Requests","status":429,"detail":"Slow down","retry_after_seconds":42}""",
                    headers = mapOf("Retry-After" to "99"),
                )
            }
            val rateErr = runCatching {
                rateLimitedClient.pairDevice("http://localhost:8080", "123456", "P")
            }
                .exceptionOrNull()
            assertTrue(rateErr is ProblemDetailsException)
            val rateProblem = rateErr as ProblemDetailsException
            assertEquals(TEST_HTTP_429, rateProblem.statusCode)
            assertEquals(RocheProblemTypes.PAIRING_RATE_LIMITED, rateProblem.problemType)
            assertEquals(TEST_RETRY_AFTER_JSON, rateProblem.retryAfterSeconds)

            val headerFallback =
                parseProblemDetails(
                    raw =
                        """{"type":"urn:roche:problem:pairing-rate-limited","title":"Rate Limited","status":429,"detail":"Wait"}""",
                    statusCode = TEST_HTTP_429,
                    headers = mapOf("Retry-After" to "17"),
                )
            assertEquals(TEST_RETRY_AFTER_HEADER, headerFallback.retryAfterSeconds)
            val lowerFallback =
                parseProblemDetails(
                    raw = "rate limited",
                    statusCode = TEST_HTTP_429,
                    headers = mapOf("retry-after" to "23"),
                )
            assertEquals(RocheProblemTypes.BLANK, lowerFallback.problemType)
            assertEquals(TEST_RETRY_AFTER_LOWER, lowerFallback.retryAfterSeconds)

            val tooLarge =
                parseProblemDetails(
                    raw =
                        """{"type":"urn:roche:problem:payload-too-large","title":"Payload Too Large","status":413,"detail":"Too big","max_bytes":67108864}""",
                    statusCode = TEST_HTTP_413,
                )
            assertEquals(RocheProblemTypes.PAYLOAD_TOO_LARGE, tooLarge.problemType)
            assertEquals(TEST_MAX_PAYLOAD_BYTES, tooLarge.maxBytes)

            val ambiguous =
                parseProblemDetails(
                    raw =
                        """{"type":"urn:roche:problem:ambiguous-lookup","title":"Ambiguous","status":409,"detail":"Multiple matches","candidates":["entry-a","entry-b"]}""",
                    statusCode = TEST_HTTP_409,
                )
            assertEquals(RocheProblemTypes.AMBIGUOUS_LOOKUP, ambiguous.problemType)
            assertEquals(listOf("entry-a", "entry-b"), ambiguous.candidates)
        }

    @Test
    internal fun `malformed peer sync 200 throws ApiProtocolException and external HTTP 500 throws HttpStatusException`() =
        runTest {
            val malformedClient = RocheApiClient {
                HttpResponse(statusCode = TEST_HTTP_200, body = "{not valid json")
            }
            val malformedErr = runCatching {
                malformedClient.syncPeer(
                    serverUrl = "http://localhost:8080",
                    token = "tok",
                    request = PeerSyncRequestDto(peerId = "p1"),
                )
            }
                .exceptionOrNull()
            assertTrue(malformedErr is ApiProtocolException)
            assertFalse(malformedErr is ProblemDetailsException)

            val missingPeerIdErr = runCatching {
                parsePeerSyncResponseJson("""{"sync_seq":1}""")
            }
                .exceptionOrNull()
            assertTrue(missingPeerIdErr is ApiProtocolException)
            assertFalse(missingPeerIdErr is ProblemDetailsException)

            val external500Client = RocheApiClient {
                HttpResponse(statusCode = TEST_HTTP_500, body = "<html>500 Error</html>")
            }
            val feedErr =
                external500Client
                    .fetchExternalFeedXml("https://feeds.example.com/rss.xml")
                    .exceptionOrNull()
            assertTrue(feedErr is HttpStatusException)
            assertFalse(feedErr is ProblemDetailsException)
            assertEquals(TEST_HTTP_500, (feedErr as HttpStatusException).statusCode)

            val overflowErr = runCatching {
                readBoundedBytes(
                    java.io.ByteArrayInputStream(ByteArray(TEST_BOUNDED_OVERFLOW)),
                    maxBytes = TEST_BOUNDED_LIMIT,
                )
            }
                .exceptionOrNull()
            assertTrue(overflowErr is ResponseTooLargeException)
            assertEquals(
                TEST_BOUNDED_LIMIT.toLong(),
                (overflowErr as ResponseTooLargeException).maxBytes,
            )
        }

    @Test
    internal fun `formatProblemDetailsMessage formats token-invalid rate-limited pairing-code-invalid and ssrf-blocked`() {
        val revoked = formatProblem(TEST_HTTP_401, "Token revoked", RocheProblemTypes.TOKEN_INVALID)
        assertTrue(revoked.contains("re-pair", ignoreCase = true) && revoked.contains("Settings"))

        val limited =
            formatProblem(
                TEST_HTTP_429,
                "Slow down",
                RocheProblemTypes.PAIRING_RATE_LIMITED,
                retryAfter = TEST_RETRY_AFTER_JSON,
            )
        assertTrue(limited.contains("Try again in 42s"))

        val limitedNoRetry =
            formatProblem(TEST_HTTP_429, "Slow down", RocheProblemTypes.PAIRING_RATE_LIMITED)
        assertTrue(limitedNoRetry.contains("Try again shortly"))

        val badCode =
            formatProblem(TEST_HTTP_401, "Bad code", RocheProblemTypes.PAIRING_CODE_INVALID)
        assertTrue(badCode.contains("invalid or expired", ignoreCase = true))

        val ssrf =
            formatProblem(TEST_HTTP_400, "Private IP blocked", RocheProblemTypes.SSRF_BLOCKED)
        assertEquals("Private IP blocked", ssrf)

        val generic = formatProblem(TEST_HTTP_500, "Upstream timeout", RocheProblemTypes.BLANK)
        assertEquals("Upstream timeout", generic)
    }
}

private const val TEST_HTTP_200 = 200
private const val TEST_HTTP_301 = 301
private const val TEST_HTTP_302 = 302
private const val TEST_HTTP_303 = 303
private const val TEST_HTTP_400 = 400
private const val TEST_HTTP_401 = 401
private const val TEST_HTTP_409 = 409
private const val TEST_HTTP_413 = 413
private const val TEST_HTTP_429 = 429
private const val TEST_HTTP_500 = 500
private const val TEST_RETRY_AFTER_JSON = 42L
private const val TEST_RETRY_AFTER_HEADER = 17L
private const val TEST_RETRY_AFTER_LOWER = 23L
private const val TEST_MAX_PAYLOAD_BYTES = 67_108_864L
private const val TEST_BOUNDED_LIMIT = 16
private const val TEST_BOUNDED_OVERFLOW = 17
private const val TEST_WRITTEN_BYTES = 16L
private const val TEST_SAFE_INCOMING_BYTES = 84L
private const val TEST_MAX_BUDGET_BYTES = 100L

internal fun formatProblem(
    statusCode: Int,
    detail: String,
    problemType: String,
    retryAfter: Long? = null,
): String =
    formatProblemDetailsMessage(
        ProblemDetailsException(
            statusCode = statusCode,
            title = "Error",
            detail = detail,
            problemType = problemType,
            retryAfterSeconds = retryAfter,
        )
    )

private fun assertPayloadStrippedGetRedirect(redirected: HttpRequest) {
    assertEquals("GET", redirected.method)
    assertNull(redirected.body)
    assertFalse(redirected.headers.keys.any { it.equals("Content-Type", ignoreCase = true) })
    assertFalse(redirected.headers.keys.any { it.equals("Content-Length", ignoreCase = true) })
}

private suspend fun verifyCancelledStreamingNeverOverwritesTarget(target: File) {
    val scheduler = Executors.newSingleThreadScheduledExecutor()
    try {
        val opened = openPartFileChannel(target)!!
        val sink =
            StreamFileSink(
                partFile = opened.first,
                targetFile = target,
                channel = opened.second,
                maxBytes = TEST_MAX_BUDGET_BYTES,
            )
        var capturedCallback: StreamingUrlRequestCallback? = null
        val job =
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).async {
                suspendCancellableCoroutine { cont ->
                    val cb = StreamingUrlRequestCallback(sink, HopWatchdog(scheduler) {}, cont)
                    capturedCallback = cb
                    cont.invokeOnCancellation { cb.cancelFromCoroutine() }
                    val chunk = ByteBuffer.allocate(TEST_NEW_PAYLOAD_BYTES.size)
                    chunk.put(TEST_NEW_PAYLOAD_BYTES)
                    assertTrue(cb.appendStreamChunk(chunk))
                }
            }
        job.cancel(CancellationException("user cancelled"))
        capturedCallback!!.handleSucceeded(TEST_HTTP_200)
        assertFalse(sink.partFile.exists())
        assertEquals("keep-existing", target.readText())
    } finally {
        scheduler.shutdownNow()
    }
}

private val TEST_NEW_PAYLOAD_BYTES = "new-bytes".toByteArray()

private suspend fun verifyBufferedCallbackHeapAndBlankLocation() {
    val scheduler = Executors.newSingleThreadScheduledExecutor()
    try {
        val jsonOutcome =
            suspendCancellableCoroutine<BufferedHopOutcome> { cont ->
                val cb =
                    BufferedUrlRequestCallback(
                        httpRequest = HttpRequest("GET", "https://example.com/a.json"),
                        watchdog = HopWatchdog(scheduler) {},
                        continuation = cont,
                    )
                val buf = ByteBuffer.allocate(TEST_JSON_BYTES.size)
                buf.put(TEST_JSON_BYTES)
                assertTrue(cb.appendReadChunk(buf))
                cb.handleSucceeded(TEST_HTTP_200, emptyMap())
            }
        assertEquals("""{"ok":true}""", jsonOutcome.response.body)
        assertNull(jsonOutcome.response.bodyBytes)

        val binOutcome =
            suspendCancellableCoroutine<BufferedHopOutcome> { cont ->
                val cb =
                    BufferedUrlRequestCallback(
                        httpRequest =
                            HttpRequest(
                                method = "GET",
                                url = "https://example.com/a.bin",
                                expectBinary = true,
                            ),
                        watchdog = HopWatchdog(scheduler) {},
                        continuation = cont,
                    )
                val buf = ByteBuffer.allocate(TEST_NEW_PAYLOAD_BYTES.size)
                buf.put(TEST_NEW_PAYLOAD_BYTES)
                assertTrue(cb.appendReadChunk(buf))
                cb.handleSucceeded(TEST_HTTP_200, emptyMap())
            }
        assertEquals("", binOutcome.response.body)
        assertArrayEquals(TEST_NEW_PAYLOAD_BYTES, binOutcome.response.bodyBytes)

        verifyBlankLocationRedirectError(scheduler)
    } finally {
        scheduler.shutdownNow()
    }
}

private suspend fun verifyBlankLocationRedirectError(
    scheduler: java.util.concurrent.ScheduledExecutorService
) {
    val err = runCatching {
        suspendCancellableCoroutine<BufferedHopOutcome> { cont ->
            val cb =
                BufferedUrlRequestCallback(
                    httpRequest = HttpRequest("GET", "https://example.com/redir"),
                    watchdog = HopWatchdog(scheduler) {},
                    continuation = cont,
                )
            cb.recordRedirectReceived(TEST_HTTP_302, emptyMap(), "   ")
            cb.handleCanceled()
        }
    }
        .exceptionOrNull()
    assertTrue(err is IOException)
    assertEquals(REDIRECT_MISSING_LOCATION_MESSAGE, err?.message)
}

private val TEST_JSON_BYTES = """{"ok":true}""".toByteArray()

internal fun verifyPeerSyncWireRoundTrip() {
    val payload = buildPeerSyncRequestPayload(buildSamplePeerSyncRequest())
    assertTrue(payload.contains(""""since_seq":42"""))
    assertTrue(payload.contains(""""source_type":"podcast""""))
    assertTrue(payload.contains(""""inbox_state":"unread""""))
    val resp =
        parsePeerSyncResponseJson(
            """{"server_peer_id":"server","sync_seq":43,"server_hlc":"001780000001000-0000-server",""" +
                """"sync_timestamp":"2026-06-01T00:00:00Z","reset_required":true,""" +
                """"active_peers":[{"peer_id":""},{"peer_id":"server","device_name":"Srv"}],""" +
                """"entries":[{"id":"f-pod","kind":"podcast","title":"Pod","hlc":"001780000001000-0000-server"}],""" +
                """"collection_entries":[],"collection_generators":[],"consumption_log":[],"comparisons":[],""" +
                """"tombstones":[{"entity_type":"","natural_key":"x"},{"entity_type":"tag","natural_key":"old"}]}"""
        )
    assertEquals(43L, resp.syncSeq)
    assertEquals("001780000001000-0000-server", resp.serverHlc)
    assertTrue(resp.resetRequired)
    assertEquals(listOf("server"), resp.activePeers.map { it.peerId })
    assertEquals(listOf("f-pod"), resp.entries.map { it.id })
    assertEquals(listOf("old"), resp.tombstones.map { it.naturalKey })
}

private fun buildSamplePeerSyncRequest(): PeerSyncRequestDto =
    PeerSyncRequestDto(
        peerId = "android-1",
        deviceName = "Pixel",
        sinceSeq = 42L,
        clientHlc = "001780000000000-0001-android-1",
        entries = buildSampleSyncEntries(),
        collectionEntries =
            listOf(
                SyncCollectionEntryDto(
                    collectionId = "f-pod",
                    entryId = "i-unread",
                    addedAt = "2026-06-01T00:00:00Z",
                    hlc = "001780000000000-0002-android-1",
                )
            ),
        tombstones =
            listOf(
                SyncTombstoneDto(
                    entityType = "tag",
                    naturalKey = "old",
                    deletedAt = "2026-06-01T00:00:00Z",
                    hlc = "001780000000000-0003-android-1",
                )
            ),
    )

private fun buildSampleSyncEntries(): List<SyncEntryRecordDto> =
    listOf(
        SyncEntryRecordDto(
            id = "f-pod",
            kind = "podcast",
            title = "Pod",
            url = "https://pod.example.com/rss",
            hlc = "001780000000000-0001-android-1",
            feedSource =
                SyncFeedSourceDto(
                    feedUrl = "https://pod.example.com/rss",
                    sourceType = "podcast",
                    feedTitle = "Pod",
                    hlc = "001780000000000-0001-android-1",
                ),
        ),
        SyncEntryRecordDto(
            id = "i-unread",
            kind = "podcast_episode",
            title = "Ep 1",
            url = "https://pod.example.com/1",
            createdAt = "2026-06-01T00:00:00Z",
            hlc = "001780000000000-0002-android-1",
            content =
                SyncEntryContentDto(
                    contentType = "audio",
                    audioUrl = "https://pod.example.com/1.mp3",
                    duration = 125L,
                ),
            state =
                SyncEntryStateDto(
                    inboxState = "unread",
                    readAt = null,
                    progress = 0.25,
                    hlc = "001780000000000-0002-android-1",
                ),
        ),
    )

internal suspend fun verifyCustomStreamToFileOverride(
    tempDir: File,
    mediaPayload: ByteArray,
) {
    var customStreamCalls = 0
    val directStreamTransport =
        object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse =
                error("streamToFile override should bypass execute()")

            override suspend fun streamToFile(
                request: HttpRequest,
                targetFile: File,
                maxBytes: Long,
            ): Boolean {
                customStreamCalls++
                return writeBytesAtomicallyToFile(mediaPayload, targetFile)
            }
        }
    val directClient = RocheApiClient(directStreamTransport)
    val directTarget = File(tempDir, "direct_stream.mp3")
    assertTrue(
        directClient.streamMediaToFile(
            "http://localhost:8080",
            "tok-media",
            "audio-7",
            directTarget,
        )
    )
    assertTrue(
        directClient.streamExternalBinaryToFile(
            "https://cdn.example.com/episode.mp3",
            directTarget,
        )
    )
    assertEquals(2, customStreamCalls)
    assertArrayEquals(mediaPayload, directTarget.readBytes())
}

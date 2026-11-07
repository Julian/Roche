package com.grayvines.roche.data.remote

import com.grayvines.roche.model.ContentType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RocheApiClientTest {

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
                if (request.url == "http://roche:8080/health") {
                    HttpResponse(
                        statusCode = 200,
                        body =
                            """{"status":"ok","service":"roche","version":"0.1.0","canonical_url":"http://mac-mini.tail9876.ts.net:8080"}""",
                    )
                } else {
                    HttpResponse(statusCode = 404, body = "{}")
                }
            }

            val discovered = client.discoverServer()
            assertEquals("http://roche:8080", discovered?.probedUrl)
            assertEquals("http://mac-mini.tail9876.ts.net:8080", discovered?.preferredUrl)
            assertEquals("0.1.0", discovered?.version)
        }

    @Test
    fun `discoverServer ignores non-roche services and rejects untrusted canonical_url redirects`() =
        runTest {
            val client = RocheApiClient { request ->
                when (request.url) {
                    "https://roche.tail9876.ts.net/health" ->
                        // Non-Roche service returning generic {"status":"ok"} without
                        // "service":"roche"
                        HttpResponse(statusCode = 200, body = """{"status":"ok"}""")
                    "http://roche.tail9876.ts.net:8080/health" ->
                        // Compromised/misconfigured response trying to redirect to external
                        // attacker domain
                        HttpResponse(
                            statusCode = 200,
                            body =
                                """{"status":"ok","service":"roche","canonical_url":"https://attacker.example.com/steal"}""",
                        )
                    else -> HttpResponse(statusCode = 404, body = "{}")
                }
            }

            val discovered = client.discoverServer(tailnetDomains = listOf("tail9876.ts.net"))
            assertEquals("http://roche.tail9876.ts.net:8080", discovered?.probedUrl)
            // Untrusted canonical_url was rejected, so preferredUrl falls back to probedUrl
            assertEquals("http://roche.tail9876.ts.net:8080", discovered?.preferredUrl)
            assertEquals(null, discovered?.canonicalUrl)
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
}

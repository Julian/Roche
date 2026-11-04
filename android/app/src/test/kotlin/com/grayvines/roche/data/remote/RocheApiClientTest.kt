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
                }
            }
            put("total", 1)
        }
            .toString()

        val client = RocheApiClient { request ->
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
}

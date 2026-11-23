package com.grayvines.roche.data

import com.grayvines.roche.core.PREFERRED_IDENTIFIER_SCHEMES
import com.grayvines.roche.core.SCHEME_HACKER_NEWS
import com.grayvines.roche.core.boardNaturalKey
import com.grayvines.roche.core.canonicalArxivAbstractUrl
import com.grayvines.roche.core.chooseDeterministicEntryIdRaw
import com.grayvines.roche.core.deterministicBoardId
import com.grayvines.roche.core.deterministicEntityUuid
import com.grayvines.roche.core.externalIdentifierLabel
import com.grayvines.roche.core.externalIdentifierUrl
import com.grayvines.roche.core.extractHackerNewsItemId
import com.grayvines.roche.core.normalizeIdentifierValueRaw
import com.grayvines.roche.data.boards.HackerNewsBoardAdapter
import com.grayvines.roche.data.boards.HackerNewsSection
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.PublicWebFetcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HackerNewsBoardAdapterTest {
    @Test
    fun `deterministic board id matches cross-stack natural key contract`() {
        assertEquals("hacker_news\u0000top", boardNaturalKey(" Hacker_News ", " TOP "))
        assertEquals(
            deterministicEntityUuid("board", "hacker_news\u0000top"),
            deterministicBoardId("hacker_news", "top"),
        )
        assertEquals(
            deterministicBoardId("HACKER_NEWS", "TOP"),
            deterministicBoardId("hacker_news", "top"),
        )
        assertEquals(
            "https://arxiv.org/abs/1706.03762",
            canonicalArxivAbstractUrl("https://arxiv.org/pdf/1706.03762v5.pdf"),
        )
        assertEquals(
            "https://arxiv.org/abs/1706.03762",
            canonicalArxivAbstractUrl("https://arxiv.org/abs/1706.03762v2"),
        )
    }

    @Test
    fun `hacker_news identifier normalizes formats rejects leading zeros and root path`() {
        assertEquals(
            "42123456",
            normalizeIdentifierValueRaw(
                "article",
                SCHEME_HACKER_NEWS,
                "https://news.ycombinator.com/item?id=42123456",
            ),
        )
        assertEquals(
            "42123456",
            normalizeIdentifierValueRaw("article", SCHEME_HACKER_NEWS, " 42123456 "),
        )
        assertNull(extractHackerNewsItemId("0123"))
        assertNull(extractHackerNewsItemId("https://news.ycombinator.com/?id=123"))
        assertNull(extractHackerNewsItemId("https://news.ycombinator.com/item?id=0123"))
        assertNull(externalIdentifierUrl(SCHEME_HACKER_NEWS, "0123"))
        assertNull(
            externalIdentifierUrl(SCHEME_HACKER_NEWS, "https://news.ycombinator.com/?id=123")
        )
        assertEquals(
            "https://news.ycombinator.com/item?id=42123456",
            externalIdentifierUrl(SCHEME_HACKER_NEWS, "42123456"),
        )
        assertNull(
            externalIdentifierUrl(SCHEME_HACKER_NEWS, "https://evil.example.com/item?id=42123456")
        )
        assertNull(
            externalIdentifierUrl(
                SCHEME_HACKER_NEWS,
                "http://news.ycombinator.com/item?id=42123456",
            )
        )
        assertEquals("Hacker News", externalIdentifierLabel(SCHEME_HACKER_NEWS))
    }

    @Test
    fun `hacker_news identifier never supersedes url or arxiv in deterministic entry ids`() {
        assertFalse(SCHEME_HACKER_NEWS in PREFERRED_IDENTIFIER_SCHEMES)
        val urlOnlyId =
            chooseDeterministicEntryIdRaw(
                explicitId = null,
                kindWireValue = "article",
                title = "Post",
                creators = emptyList(),
                identifiers = listOf("url" to "https://example.com/post"),
            )
        val urlWithHnId =
            chooseDeterministicEntryIdRaw(
                explicitId = null,
                kindWireValue = "article",
                title = "Post",
                creators = emptyList(),
                identifiers =
                    listOf(
                        SCHEME_HACKER_NEWS to "42123456",
                        "url" to "https://example.com/post",
                    ),
            )
        assertEquals(urlOnlyId, urlWithHnId)
    }

    @Test
    fun `HackerNewsSection parses bare tokens and canonical HN section URLs`() {
        assertEquals(HackerNewsSection.TOP, HackerNewsSection.fromSectionOrUrl("top"))
        assertEquals(
            HackerNewsSection.TOP,
            HackerNewsSection.fromSectionOrUrl("https://news.ycombinator.com/"),
        )
        assertEquals(
            HackerNewsSection.TOP,
            HackerNewsSection.fromSectionOrUrl("https://news.ycombinator.com/news"),
        )
        assertEquals(
            HackerNewsSection.BEST,
            HackerNewsSection.fromSectionOrUrl("https://news.ycombinator.com/best"),
        )
        assertEquals(
            HackerNewsSection.ASK,
            HackerNewsSection.fromSectionOrUrl("news.ycombinator.com/ask"),
        )
        assertEquals(
            HackerNewsSection.SHOW,
            HackerNewsSection.fromSectionOrUrl("https://news.ycombinator.com/show"),
        )
        assertEquals(
            HackerNewsSection.NEW,
            HackerNewsSection.fromSectionOrUrl("https://news.ycombinator.com/newest"),
        )
        assertEquals("""{"section":"ask"}""", HackerNewsSection.ASK.sourceConfigJson)

        assertNull(HackerNewsSection.fromSectionOrUrl("https://evil.example.com/best"))
        assertNull(HackerNewsSection.fromSectionOrUrl("https://news.ycombinator.com/item?id=12345"))
        assertNull(HackerNewsSection.fromSectionOrUrl("unknown_section"))
    }

    @Test
    fun `parseStoryIds dedupes preserving order and parseStoryItem leaves self-post domain null`() {
        assertEquals(
            listOf(101L, 102L, 103L),
            HackerNewsBoardAdapter.parseStoryIds("""[101, 0, -5, "bad", 102, 101, 103, 102]"""),
        )
        val externalJson =
            """{"id":42001,"type":"story","by":"pg","time":1730000000,""" +
                """"title":"Writing &amp; Thinking","url":"https://paulgraham.com/read.html",""" +
                """"score":312,"descendants":144}"""
        val external = HackerNewsBoardAdapter.parseStoryItem(externalJson, rank = 1)
        assertNotNull(external)
        assertEquals("42001", external!!.externalId)
        assertEquals("Writing & Thinking", external.title)
        assertEquals("https://paulgraham.com/read.html", external.url)
        assertEquals("https://news.ycombinator.com/item?id=42001", external.discussionUrl)
        assertEquals("paulgraham.com", external.domain)
        assertEquals("pg", external.author)
        assertEquals(312, external.score)
        assertEquals(144, external.commentCount)
        assertNull(external.selfTextHtml)

        val askJson =
            """{"id":42002,"type":"story","by":"alice","time":1730000100,""" +
                """"title":"Ask HN: Favorite systems papers?",""" +
                """"text":"<p>Looking for <b>classic</b> papers.<script>alert(1)</script></p>",""" +
                """"score":88,"descendants":52}"""
        val askHn = HackerNewsBoardAdapter.parseStoryItem(askJson, rank = 2)
        assertNotNull(askHn)
        assertEquals("https://news.ycombinator.com/item?id=42002", askHn!!.url)
        assertEquals("https://news.ycombinator.com/item?id=42002", askHn.discussionUrl)
        assertNull(askHn.domain)
        assertTrue(askHn.selfTextHtml.orEmpty().contains("classic"))
        assertFalse(askHn.selfTextHtml.orEmpty().contains("script"))
        assertTrue(askHn.summary.orEmpty().contains("classic"))
    }

    @Test
    fun `parseStoryItem rejects dead deleted untitled and SSRF URLs`() {
        assertNull(HackerNewsBoardAdapter.parseStoryItem("null"))
        val rejectedPayloads =
            listOf(
                """{"id": 1, "title": "Gone", "deleted": true, "url": "https://example.com"}""",
                """{"id": 2, "title": "Dead", "dead": true, "url": "https://example.com"}""",
                """{"id": 3, "type": "job", "url": "https://example.com"}""",
                """{"id": 4, "title": "Loopback", "url": "http://127.0.0.1:8080/secret"}""",
                """{"id": 5, "title": "Private IP", "url": "http://192.168.1.1/router"}""",
                """{"id": 6, "title": "Tailnet", "url": "https://roche.example.ts.net/api"}""",
                """{"id": 7, "title": "XSS", "url": "javascript:alert(1)"}""",
            )
        for (payload in rejectedPayloads) {
            assertNull(HackerNewsBoardAdapter.parseStoryItem(payload))
        }
    }

    @Test
    fun `fetchSnapshot over-fetches past dead items and fetchPage resumes from raw nextOffset`() =
        runTest {
            val responses =
                mapOf(
                    "https://hacker-news.firebaseio.com/v0/topstories.json" to
                        "[10, 11, 12, 13, 14]",
                    "https://hacker-news.firebaseio.com/v0/item/10.json" to
                        """{"id":10,"title":"Story 10","url":"https://a.example.com/10","score":50,"descendants":12}""",
                    "https://hacker-news.firebaseio.com/v0/item/11.json" to
                        """{"id":11,"title":"Dead 11","dead":true,"url":"https://a.example.com/11"}""",
                    "https://hacker-news.firebaseio.com/v0/item/12.json" to
                        """{"id":12,"title":"Ask HN: 12","text":"<p>Hello</p>","score":20,"descendants":5}""",
                    "https://hacker-news.firebaseio.com/v0/item/13.json" to
                        """{"id":13,"title":"Story 13","url":"https://b.example.com/13","score":99,"descendants":30}""",
                    "https://hacker-news.firebaseio.com/v0/item/14.json" to
                        """{"id":14,"title":"Story 14","url":"https://c.example.com/14","score":15,"descendants":3}""",
                )
            val fetcher = PublicWebFetcher { req ->
                val body = responses[req.url]
                if (body != null) {
                    HttpResponse(statusCode = 200, body = body)
                } else {
                    HttpResponse(statusCode = 404, body = "not found")
                }
            }
            val adapter = HackerNewsBoardAdapter(fetcher)

            val snap = adapter.fetchSnapshot("top", """{"section":"top"}""", limit = 3).getOrThrow()
            assertEquals(5, snap.totalAvailable)
            assertEquals(4, snap.nextOffset)
            assertEquals(listOf("10", "12", "13"), snap.items.map { it.externalId })
            assertEquals(listOf(1, 2, 3), snap.items.map { it.rank })

            val page2 =
                adapter
                    .fetchPage("top", """{"section":"top"}""", offset = snap.nextOffset, limit = 2)
                    .getOrThrow()
            assertEquals(5, page2.totalAvailable)
            assertEquals(5, page2.nextOffset)
            assertEquals(listOf("14"), page2.items.map { it.externalId })
            assertEquals(listOf(5), page2.items.map { it.rank })

            val single = adapter.fetchSingleItem("top", "{}", "13").getOrThrow()
            assertEquals("Story 13", single?.title)
        }
}

package com.grayvines.roche

import android.content.Intent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ShareIntentParserTest {

    @Test
    fun `extractSharedLink parses plain url and prefers subject or title extras`() {
        val withSubject =
            extractSharedLink(
                action = Intent.ACTION_SEND,
                mimeType = "text/plain; charset=utf-8",
                extraText = "https://example.com/articles/42",
                extraSubject = "  Systems Programming in 2026  ",
                extraTitle = "Fallback Title",
            )
        assertEquals(
            SharedLinkPayload(
                url = "https://example.com/articles/42",
                title = "Systems Programming in 2026",
            ),
            withSubject,
        )

        val withTitleExtra =
            extractSharedLink(
                action = Intent.ACTION_SEND,
                mimeType = "TEXT/PLAIN",
                extraText = "https://example.com/articles/42",
                extraSubject = "   ",
                extraTitle = "  Fallback Title  ",
            )
        assertEquals(
            SharedLinkPayload(
                url = "https://example.com/articles/42",
                title = "Fallback Title",
            ),
            withTitleExtra,
        )
    }

    @Test
    fun `extractSharedLink extracts inline title prefix and markdown links`() {
        val newlineSeparated =
            extractSharedLink(
                action = Intent.ACTION_SEND,
                mimeType = "text/plain",
                extraText = "Designing a Local-First Reader\nhttps://example.com/local-first",
            )
        assertEquals(
            SharedLinkPayload(
                url = "https://example.com/local-first",
                title = "Designing a Local-First Reader",
            ),
            newlineSeparated,
        )

        val dashSeparated =
            extractSharedLink(
                action = Intent.ACTION_SEND,
                mimeType = "text/plain",
                extraText = "Memory Bandwidth Deep Dive — https://example.com/memory",
            )
        assertEquals(
            SharedLinkPayload(
                url = "https://example.com/memory",
                title = "Memory Bandwidth Deep Dive",
            ),
            dashSeparated,
        )

        val markdownLink =
            extractSharedLink(
                action = Intent.ACTION_SEND,
                mimeType = "text/plain",
                extraText = "[Designing a Reader](https://example.com/reader)",
            )
        assertEquals(
            SharedLinkPayload(
                url = "https://example.com/reader",
                title = "Designing a Reader",
            ),
            markdownLink,
        )
    }

    @Test
    fun `extractSharedLink unwraps surrounding quotes while preserving inner quotes and brackets`() {
        val fullyQuotedTitle =
            extractSharedLink(
                action = Intent.ACTION_SEND,
                mimeType = "text/plain",
                extraText = "\"Designing a Reader\" — https://example.com/quoted",
            )
        assertEquals(
            SharedLinkPayload(
                url = "https://example.com/quoted",
                title = "Designing a Reader",
            ),
            fullyQuotedTitle,
        )

        val titleEndingInQuotedWord =
            extractSharedLink(
                action = Intent.ACTION_SEND,
                mimeType = "text/plain",
                extraText = "Why We Built \"Roche\" - https://example.com/roche",
            )
        assertEquals(
            SharedLinkPayload(
                url = "https://example.com/roche",
                title = "Why We Built \"Roche\"",
            ),
            titleEndingInQuotedWord,
        )

        val multipleBracketedSegments =
            extractSharedLink(
                action = Intent.ACTION_SEND,
                mimeType = "text/plain",
                extraText = "[2026] Systems Programming [Part 1] — https://example.com/sys",
            )
        assertEquals(
            SharedLinkPayload(
                url = "https://example.com/sys",
                title = "[2026] Systems Programming [Part 1]",
            ),
            multipleBracketedSegments,
        )
    }

    @Test
    fun `extractSharedLink strips wrappers curly quotes and trailing punctuation`() {
        val wrappedInParens =
            extractSharedLink(
                action = Intent.ACTION_SEND,
                mimeType = "text/plain",
                extraText = "Check this out (https://example.com/a).",
            )
        assertEquals(
            SharedLinkPayload(
                url = "https://example.com/a",
                title = "Check this out",
            ),
            wrappedInParens,
        )

        val wrappedInAngleBrackets =
            extractSharedLink(
                action = Intent.ACTION_SEND,
                mimeType = "text/plain",
                extraText = "<https://example.com/b>,",
            )
        assertEquals(
            SharedLinkPayload(
                url = "https://example.com/b",
                title = null,
            ),
            wrappedInAngleBrackets,
        )

        val curlyQuotesAndEllipsis =
            extractSharedLink(
                action = Intent.ACTION_SEND,
                mimeType = "text/plain",
                extraText = "“https://example.com/local-first…”",
            )
        assertEquals(
            SharedLinkPayload(
                url = "https://example.com/local-first",
                title = null,
            ),
            curlyQuotesAndEllipsis,
        )
    }

    @Test
    fun `extractSharedLink preserves balanced parentheses inside wikipedia style urls`() {
        val wikipediaBalanced =
            extractSharedLink(
                action = Intent.ACTION_SEND,
                mimeType = "text/plain",
                extraText = "https://en.wikipedia.org/wiki/Rust_(programming_language)",
            )
        assertEquals(
            SharedLinkPayload(
                url = "https://en.wikipedia.org/wiki/Rust_(programming_language)",
                title = null,
            ),
            wikipediaBalanced,
        )

        val wikipediaWrappedInOuterParens =
            extractSharedLink(
                action = Intent.ACTION_SEND,
                mimeType = "text/plain",
                extraText = "(https://en.wikipedia.org/wiki/Rust_(programming_language)).",
            )
        assertEquals(
            SharedLinkPayload(
                url = "https://en.wikipedia.org/wiki/Rust_(programming_language)",
                title = null,
            ),
            wikipediaWrappedInOuterParens,
        )
    }

    @Test
    fun `extractSharedLink deduplicates title when subject matches url or is another raw url`() {
        val duplicateSubject =
            extractSharedLink(
                action = Intent.ACTION_SEND,
                mimeType = "text/plain",
                extraText = "https://example.com/post",
                extraSubject = "HTTPS://EXAMPLE.COM/POST",
            )
        assertEquals(
            SharedLinkPayload(url = "https://example.com/post", title = null),
            duplicateSubject,
        )

        val rawUrlSubject =
            extractSharedLink(
                action = Intent.ACTION_SEND,
                mimeType = "text/plain",
                extraText = "https://example.com/post",
                extraSubject = "https://example.com/other",
            )
        assertEquals(
            SharedLinkPayload(url = "https://example.com/post", title = null),
            rawUrlSubject,
        )
    }

    @Test
    fun `extractSharedLink rejects non-http schemes wrong intent metadata and blank text`() {
        assertNull(
            extractSharedLink(
                action = Intent.ACTION_SEND,
                mimeType = "text/plain",
                extraText = "javascript:alert(1) file:///etc/passwd mailto:user@example.com",
            )
        )
        assertNull(
            extractSharedLink(
                action = Intent.ACTION_VIEW,
                mimeType = "text/plain",
                extraText = "https://example.com",
            )
        )
        assertNull(
            extractSharedLink(
                action = Intent.ACTION_SEND,
                mimeType = "image/png",
                extraText = "https://example.com",
            )
        )
        assertNull(
            extractSharedLink(
                action = Intent.ACTION_SEND,
                mimeType = "text/plain",
                extraText = "   ",
            )
        )
    }
}

package com.grayvines.roche.data

import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.HttpTransport
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ArtistMetadataResolverTest {

    @Test
    fun `selectBestArtistWikiSlug disambiguates band and rejects self titled album`() {
        val nirvanaHits =
            listOf(
                WikipediaSearchHit(
                    title = "Nirvana",
                    snippet = "In Indian religions, nirvana is the liberation from samsara.",
                ),
                WikipediaSearchHit(
                    title = "Nirvana (UK band)",
                    snippet = "British progressive rock band formed in London in 1967.",
                ),
                WikipediaSearchHit(
                    title = "Nirvana (band)",
                    snippet =
                        "American rock band formed in Aberdeen. Released <span>Nevermind</span>.",
                ),
            )
        assertEquals(
            "Nirvana_(band)",
            selectBestArtistWikiSlug(nirvanaHits, "Nirvana", "Nevermind"),
        )

        val elvisHits =
            listOf(
                WikipediaSearchHit(
                    title = "Elvis Presley (album)",
                    snippet = "Debut studio album by American singer Elvis Presley.",
                ),
                WikipediaSearchHit(
                    title = "Elvis Presley",
                    snippet =
                        "American singer and actor (1935-1977). Moving step by step to stardom.",
                ),
            )
        assertEquals("Elvis_Presley", selectBestArtistWikiSlug(elvisHits, "Elvis Presley"))
        assertNull(selectBestArtistWikiSlug(elvisHits, "Various Artists"))
    }

    @Test
    fun `extractSpotifyArtistUri handles scripts numeric entities and multi artist guard`() {
        val collabHtml =
            listOf(
                    """<script>if (x<a && y>z) { console.log("ok"); }</script>""",
                    """<meta name="music:musician" content="https://open.spotify.com/artist/1111111111111111111111"/>""",
                    """<meta name="music:musician" content="https://open.spotify.com/artist/2222222222222222222222"/>""",
                    """<a href="/artist/1111111111111111111111">Guns N&#8217; Roses</a>""",
                    """<a href="/artist/2222222222222222222222">Brian Eno</a>""",
                )
                .joinToString("\n")
        assertEquals(
            "spotify:artist:1111111111111111111111",
            extractSpotifyArtistUri(collabHtml, "Guns N' Roses", allowSingleFallback = false),
        )
        assertEquals(
            "spotify:artist:2222222222222222222222",
            extractSpotifyArtistUri(collabHtml, "Brian Eno", allowSingleFallback = false),
        )
        assertNull(extractSpotifyArtistUri(collabHtml, "Unknown", allowSingleFallback = true))
    }

    @Test
    fun `resolveStreamingIds enriches artist and cleans up inFlight on cancellation`() = runTest {
        val calls = AtomicInteger(0)
        val transport = HttpTransport { req ->
            if (calls.incrementAndGet() == 1) {
                delay(5_000)
            }
            when {
                req.url.contains("wikipedia.org") -> {
                    HttpResponse(statusCode = 200, body = ADELE_WIKI_SEARCH_JSON)
                }
                req.url.contains("open.spotify.com/album/5duyQokC4FMcWPYTV9Gpf9") -> {
                    HttpResponse(statusCode = 200, body = ADELE_SPOTIFY_ALBUM_HTML)
                }
                else -> {
                    HttpResponse(statusCode = 404, body = "")
                }
            }
        }
        val capturedCallbacks = ArrayList<Pair<String, Map<String, String>>>()
        val resolver =
            AlbumStreamingResolver(
                transport = transport,
                onArtistIdsResolved = { artistId, ids -> capturedCallbacks.add(artistId to ids) },
            )
        val draw =
            CollectionDrawSnapshot(
                entryId = "album-21",
                title = "21",
                creator = "Adele",
                spotifyId = "5duyQokC4FMcWPYTV9Gpf9",
                youtubeMusicId = "MPREb_adele_21",
            )

        // First call times out / cancels mid-flight; must not deadlock subsequent call
        val timedOut = withTimeoutOrNull(100) { resolver.resolveStreamingIds(draw, true) }
        assertNull(timedOut)

        val second = resolver.resolveStreamingIds(draw, true)
        assertEquals("Adele", second["wikipedia"] ?: capturedCallbacks.single().second["wikipedia"])
        assertEquals(1, capturedCallbacks.size)
        val (artistId, resolved) = capturedCallbacks.single()
        assertEquals(deterministicTitleEntryId("artist", "adele"), artistId)
        assertEquals("Adele", resolved["wikipedia"])
        assertEquals("spotify:artist:4dpARuHxo51G3z768sgnrY", resolved["spotify"])
    }
}

private const val ADELE_WIKI_SEARCH_JSON =
    """{"query":{"search":[{"title":"Adele","snippet":"English singer-songwriter (born 1988)."}]}}"""

private const val ADELE_SPOTIFY_ALBUM_HTML =
    """<html><head><meta name="music:musician" content="https://open.spotify.com/artist/4dpARuHxo51G3z768sgnrY"/></head><body><a href="/artist/4dpARuHxo51G3z768sgnrY">Adele</a></body></html>"""

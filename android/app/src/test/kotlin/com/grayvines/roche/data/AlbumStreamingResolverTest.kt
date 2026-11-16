package com.grayvines.roche.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.HttpTransport
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AlbumStreamingResolverTest {

    @TempDir lateinit var tempDir: File

    @Test
    fun `Odesli resolution extracts OLAK5uy playlist ID and cross-platform album links`() =
        runTest {
            val transport = HttpTransport { req ->
                assertTrue(req.url.startsWith("https://api.song.link/v1-alpha.1/links?url="))
                HttpResponse(statusCode = 200, body = SAMPLE_ODESLI_JSON)
            }
            val resolver = AlbumStreamingResolver(transport)
            val album =
                CollectionDrawSnapshot(
                    entryId = "entry-1",
                    title = "In The Wee Small Hours",
                    creator = "Frank Sinatra",
                    spotifyId = "3GmwKB1tgPZgXeRJZSm9WX",
                )

            val resolved = resolver.resolveStreamingIds(album)
            assertEquals("OLAK5uy_sinatra_wee", resolved["youtube_music"])
            assertEquals(
                "https://music.apple.com/us/album/in-the-wee-small-hours/1440858069",
                resolved["apple_music"],
            )
            assertEquals("https://listen.tidal.com/album/77610519", resolved["tidal"])

            val enriched = album.withResolvedStreamingIds(resolved)
            assertEquals(
                "https://music.youtube.com/playlist?list=OLAK5uy_sinatra_wee",
                enriched.youtubeMusicUrl,
            )
            assertFalse(enriched.candidateStreamingUrls().any { "/search?" in it })
        }

    @Test
    fun `Innertube search and browse fallback resolves OLAK5uy playlist ID when Odesli misses`() =
        runTest {
            val transport = HttpTransport { req ->
                when {
                    req.url.startsWith("https://api.song.link/") -> {
                        HttpResponse(statusCode = 404, body = "{}")
                    }
                    req.url.startsWith("https://music.youtube.com/youtubei/v1/search") -> {
                        HttpResponse(statusCode = 200, body = SAMPLE_INNERTUBE_SEARCH_JSON)
                    }
                    req.url.startsWith("https://music.youtube.com/youtubei/v1/browse") -> {
                        assertTrue(req.body.orEmpty().contains("MPREb_duke"))
                        HttpResponse(statusCode = 200, body = SAMPLE_INNERTUBE_BROWSE_JSON)
                    }
                    else -> {
                        HttpResponse(statusCode = 500, body = "")
                    }
                }
            }
            val resolver = AlbumStreamingResolver(transport)
            val album =
                CollectionDrawSnapshot(
                    entryId = "entry-2",
                    title = "Ellington at Newport (Remastered)",
                    creator = "Duke Ellington",
                    spotifyId = "0t41BkcZayaAsa0FdRelfz",
                )

            val resolved = resolver.resolveStreamingIds(album)
            assertEquals("OLAK5uy_duke_newport", resolved["youtube_music"])
        }

    @Test
    fun `selectBestWikipediaSlug prefers album disambiguator over biography film or song`() {
        val elvisHits =
            listOf(
                WikipediaSearchHit(
                    title = "Elvis Presley",
                    snippet = "American singer and actor (1935-1977).",
                ),
                WikipediaSearchHit(
                    title = "Elvis Presley (album)",
                    snippet =
                        "<span>Elvis Presley</span> is the debut studio album by American rock singer Elvis Presley, released in 1956.",
                ),
            )
        val elvisAlbum =
            CollectionDrawSnapshot(
                title = "Elvis Presley",
                creator = "Elvis Presley",
                releaseDate = "1956",
            )
        assertEquals("Elvis_Presley_(album)", selectBestWikipediaSlug(elvisHits, elvisAlbum))

        val purpleRainHits =
            listOf(
                WikipediaSearchHit(
                    title = "Purple Rain (film)",
                    snippet = "1984 American rock musical drama film starring Prince.",
                ),
                WikipediaSearchHit(
                    title = "Purple Rain (song)",
                    snippet = "1984 power ballad by Prince and the Revolution.",
                ),
                WikipediaSearchHit(
                    title = "Purple Rain (album)",
                    snippet = "Sixth studio album by American musician Prince, released in 1984.",
                ),
            )
        val purpleRainAlbum =
            CollectionDrawSnapshot(title = "Purple Rain", creator = "Prince", releaseDate = "1984")
        assertEquals(
            "Purple_Rain_(album)",
            selectBestWikipediaSlug(purpleRainHits, purpleRainAlbum),
        )

        assertEquals(
            "https://en.wikipedia.org/wiki/%231_Record",
            externalIdentifierUrl("wikipedia", "#1_Record"),
        )
    }

    @Test
    fun `prewarmCollectionDraw persists YouTube Music and Wikipedia IDs and marks entry dirty for sync`() =
        runTest {
            val db =
                Room.inMemoryDatabaseBuilder<RocheDatabase>()
                    .setDriver(BundledSQLiteDriver())
                    .build()
            val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val settingsStore =
                    PreferenceDataStoreFactory.create(
                        scope = dataStoreScope,
                        produceFile = { File(tempDir, "streaming_resolve.preferences_pb") },
                    )
                val transport = HttpTransport { req ->
                    if (req.url.contains("wikipedia.org")) {
                        HttpResponse(statusCode = 200, body = SAMPLE_WIKIPEDIA_SEARCH_JSON)
                    } else {
                        HttpResponse(statusCode = 200, body = SAMPLE_PERSIST_ODESLI_JSON)
                    }
                }
                val repo =
                    RocheRepository(
                        database = db,
                        apiClient = RocheApiClient(transport = transport),
                        settings = SettingsRepository(settingsStore),
                        albumStreamingResolver = AlbumStreamingResolver(transport),
                    )
                val listId = repo.catalog.createCustomList("Jazz Canon")
                val entryId =
                    repo.catalog.upsertEntry(
                        UpsertCatalogEntryInput(
                            id = "83f8410d-71bc-5ea4-911e-06869fe1b039",
                            kind = EntryKind.ALBUM,
                            title = "In The Wee Small Hours",
                            creators = listOf("Frank Sinatra"),
                            releaseYear = 1955,
                            identifiers =
                                listOf(EntryIdentifierDto("spotify", "3GmwKB1tgPZgXeRJZSm9WX")),
                        ),
                        markDirty = false,
                    )
                repo.catalog.addCollectionEntry(listId, entryId, position = 1.0)

                val drawn = checkNotNull(repo.catalog.setCollectionGeneratorEnabled(listId, true))
                repo.prewarmCollectionDraw(drawn)

                val updated =
                    repo.snapshot.first {
                        val cur = it.catalog.generatorFor(listId)?.currentDraw
                        cur?.youtubeMusicId == "OLAK5uy_persisted_1" &&
                            cur.wikipediaUrl ==
                                "https://en.wikipedia.org/wiki/In_the_Wee_Small_Hours"
                    }
                val active = checkNotNull(updated.catalog.generatorFor(listId)?.currentDraw)
                assertResolvedSyncAndIdempotency(db, repo, active)
            } finally {
                db.close()
                dataStoreScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }
}

private suspend fun assertResolvedSyncAndIdempotency(
    db: RocheDatabase,
    repo: RocheRepository,
    active: CollectionDrawSnapshot,
) {
    assertEquals(
        "https://music.youtube.com/playlist?list=OLAK5uy_persisted_1",
        active.youtubeMusicUrl,
    )
    assertEquals("https://en.wikipedia.org/wiki/In_the_Wee_Small_Hours", active.wikipediaUrl)

    val outgoing = buildOutgoingCatalogSyncBatch(db.dao(), inclusiveSinceInstant = null)
    val syncedEntry = checkNotNull(outgoing.catalogEntries.firstOrNull { it.id == active.entryId })
    val syncedSchemes = syncedEntry.identifiers.associate { it.scheme to it.value }
    assertEquals("OLAK5uy_persisted_1", syncedSchemes["youtube_music"])
    assertEquals("In_the_Wee_Small_Hours", syncedSchemes["wikipedia"])

    val rawClean =
        checkNotNull(db.dao().getCatalogEntryById(active.entryId)).copy(isLocalDirty = false)
    db.dao().upsertCatalogEntry(rawClean)
    val attachedAgain =
        repo.catalog.attachEntryIdentifiers(
            active.entryId,
            mapOf("youtube_music" to "OLAK5uy_persisted_1"),
        )
    assertFalse(attachedAgain)
    assertFalse(checkNotNull(db.dao().getCatalogEntryById(active.entryId)).isLocalDirty)
}

private const val SAMPLE_ODESLI_JSON =
    """{
  "entitiesByUniqueId": {
    "YOUTUBE_ALBUM_OLAK5uy_sinatra_wee": {
      "id": "OLAK5uy_sinatra_wee",
      "type": "album"
    }
  },
  "linksByPlatform": {
    "youtube": {"url": "https://www.youtube.com/playlist?list=OLAK5uy_sinatra_wee"},
    "youtubeMusic": {"url": "https://music.youtube.com/browse/MPREb_sinatra_123"},
    "appleMusic": {"url": "https://music.apple.com/us/album/in-the-wee-small-hours/1440858069"},
    "tidal": {"url": "https://listen.tidal.com/album/77610519"}
  }
}"""

private const val SAMPLE_INNERTUBE_SEARCH_JSON =
    """{
  "contents": {
    "tabbedSearchResultsRenderer": {
      "tabs": [{
        "tabRenderer": {
          "content": {
            "sectionListRenderer": {
              "contents": [{
                "musicShelfRenderer": {
                  "contents": [
                    {
                      "musicResponsiveListItemRenderer": {
                        "flexColumns": [{"runs": [{"text": "Different Album"}, {"text": "Other Artist"}]}],
                        "navigationEndpoint": {"browseEndpoint": {"browseId": "MPREb_wrong"}}
                      }
                    },
                    {
                      "musicResponsiveListItemRenderer": {
                        "flexColumns": [{"runs": [{"text": "Ellington at Newport"}, {"text": "Duke Ellington"}]}],
                        "navigationEndpoint": {"browseEndpoint": {"browseId": "MPREb_duke"}}
                      }
                    }
                  ]
                }
              }]
            }
          }
        }
      }]
    }
  }
}"""

private const val SAMPLE_INNERTUBE_BROWSE_JSON =
    """{
  "playButton": {
    "watchPlaylistEndpoint": {
      "playlistId": "OLAK5uy_duke_newport"
    }
  }
}"""

private const val SAMPLE_PERSIST_ODESLI_JSON =
    """{
  "linksByPlatform": {
    "youtubeMusic": {
      "url": "https://music.youtube.com/playlist?list=OLAK5uy_persisted_1"
    }
  }
}"""

private const val SAMPLE_WIKIPEDIA_SEARCH_JSON =
    """{
  "query": {
    "search": [
      {
        "title": "Frank Sinatra",
        "snippet": "American singer and actor (1915-1998)."
      },
      {
        "title": "In the Wee Small Hours",
        "snippet": "<span>In the Wee Small Hours</span> is the ninth studio album by American vocalist Frank Sinatra, released in April 1955."
      }
    ]
  }
}"""

package com.grayvines.roche.ui

import com.grayvines.roche.data.catalog.CatalogEntryItem
import com.grayvines.roche.data.catalog.CatalogSnapshot
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.extractAlbumTracks
import com.grayvines.roche.data.catalog.extractArtistDiscography
import com.grayvines.roche.data.searchCatalogEntries
import com.grayvines.roche.ui.components.resolveCatalogEntryOpenAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CatalogDetailAndSheetsTest {
    @Test
    fun `extractAlbumTracks and extractArtistDiscography parse enriched metadata json`() {
        val albumMeta =
            """{"tracks":[{"disc":1,"number":1,"title":"Rolling in the Deep","duration_ms":228000,"artists":["Adele"],"spotify_uri":"spotify:track:4OSBTYWVwsQhGLF9NHvIbR"},{"disc":1,"number":2,"title":"Rumour Has It","duration_ms":223000}]}"""
        val tracks = extractAlbumTracks(albumMeta)
        assertEquals(2, tracks.size)
        assertEquals("Rolling in the Deep", tracks[0].title)
        assertEquals(228_000L, tracks[0].durationMs)
        assertEquals("spotify:track:4OSBTYWVwsQhGLF9NHvIbR", tracks[0].spotifyUri)
        assertEquals(2, tracks[1].number)

        val artistMeta =
            """{"discography":[{"title":"In Rainbows","release_year":2007,"thumbnail_url":"https://cdn.example.com/ir.jpg","apple_music_id":"1109714933"}]}"""
        val discog = extractArtistDiscography(artistMeta)
        assertEquals(1, discog.size)
        assertEquals("In Rainbows", discog[0].title)
        assertEquals(2007, discog[0].releaseYear)
        assertEquals("https://cdn.example.com/ir.jpg", discog[0].thumbnailUrl)
    }

    @Test
    fun `catalog detail sheet reducer and open action routing handle media vs reader entries`() {
        val opened = reduceUiAction(TransientUiState(), MainAction.OpenCatalogDetailSheet("alb-1"))
        assertEquals("alb-1", opened.catalogDetailEntryId)

        val prefilled =
            reduceUiAction(
                opened,
                MainAction.OpenQuickLogSheet(
                    prefillKind = EntryKind.ALBUM,
                    prefillTitle = "21",
                    prefillCreators = "Adele",
                    prefillReleaseYear = 2011,
                ),
            )
        assertNull(prefilled.catalogDetailEntryId)
        assertTrue(prefilled.isQuickLogSheetOpen)
        assertEquals(EntryKind.ALBUM, prefilled.quickLogPrefillKind)
        assertEquals("21", prefilled.quickLogPrefillTitle)
        assertEquals("Adele", prefilled.quickLogPrefillCreators)
        assertEquals(2011, prefilled.quickLogPrefillReleaseYear)

        val closedQuick = reduceUiAction(prefilled, MainAction.CloseQuickLogSheet)
        assertFalse(closedQuick.isQuickLogSheetOpen)
        assertNull(closedQuick.quickLogPrefillKind)
        assertEquals("", closedQuick.quickLogPrefillTitle)

        val openedEdit = reduceUiAction(closedQuick, MainAction.OpenEditEntrySheet("paper-1"))
        assertEquals("paper-1", openedEdit.editingCatalogEntryId)
        val closedEdit = reduceUiAction(openedEdit, MainAction.CloseEditEntrySheet)
        assertNull(closedEdit.editingCatalogEntryId)

        var target = ""
        resolveCatalogEntryOpenAction(
                entryId = "alb-1",
                externalUrl = "https://open.spotify.com/album/xyz",
                readableIds = setOf("art-1"),
                onOpenReader = { target = "reader:$it" },
                onOpenExternalUrl = { target = "url:$it" },
                entryKind = EntryKind.ALBUM,
                onOpenCatalogDetail = { target = "detail:$it" },
            )
            ?.invoke()
        assertEquals("detail:alb-1", target)

        resolveCatalogEntryOpenAction(
                entryId = "art-1",
                externalUrl = "https://example.com/post",
                readableIds = setOf("art-1"),
                onOpenReader = { target = "reader:$it" },
                onOpenExternalUrl = { target = "url:$it" },
                entryKind = EntryKind.ARTICLE,
                onOpenCatalogDetail = { target = "detail:$it" },
            )
            ?.invoke()
        assertEquals("reader:art-1", target)
    }

    @Test
    fun `searchCatalogEntries matches catalog media and resolveHoveredDockSlot hits non-source slots`() {
        val album =
            CatalogEntryItem(
                id = "alb-ir",
                kind = EntryKind.ALBUM,
                title = "In Rainbows",
                creators = listOf("Radiohead"),
                url = null,
                publishedAt = null,
                releaseYear = 2007,
                summary = null,
                thumbnailUrl = null,
                identifiers = emptyList(),
            )
        val snapshot = CatalogSnapshot(entriesById = mapOf(album.id to album))
        assertTrue(searchCatalogEntries(snapshot, "").isEmpty())
        assertEquals(1, searchCatalogEntries(snapshot, "", includeWhenBlank = true).size)
        assertEquals("alb-ir", searchCatalogEntries(snapshot, "radiohead").single().entry.id)
        assertEquals("alb-ir", searchCatalogEntries(snapshot, "2007").single().entry.id)

        val bounds =
            mapOf(
                0 to DockSlotBounds(0f, 100f, 100f, 180f),
                1 to DockSlotBounds(110f, 100f, 210f, 180f),
                2 to DockSlotBounds(220f, 100f, 320f, 180f),
            )
        assertEquals(
            2,
            resolveHoveredDockSlot(250f, 140f, sourceSlotIndex = 0, slotBounds = bounds),
        )
        assertNull(resolveHoveredDockSlot(50f, 140f, sourceSlotIndex = 0, slotBounds = bounds))
        assertEquals(
            1,
            resolveHoveredDockSlot(150f, 140f, sourceSlotIndex = null, slotBounds = bounds),
        )
        assertNull(resolveHoveredDockSlot(150f, 300f, sourceSlotIndex = null, slotBounds = bounds))
    }
}

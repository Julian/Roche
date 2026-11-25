package com.grayvines.roche.ui

import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.data.TestRocheEnv
import com.grayvines.roche.data.catalog.CatalogListSummary
import com.grayvines.roche.data.catalog.CatalogSnapshot
import com.grayvines.roche.data.catalog.CollectionRole
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.UpsertCatalogEntryInput
import com.grayvines.roche.data.matchesScope
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.model.ItemStatus
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReaderInboxRankUxTest {
    @TempDir lateinit var tempDir: File

    @Test
    fun `matchesScope applies active InboxFilter within feed and tag scopes`() {
        val newPaper =
            sampleEntry(
                id = "p-unread",
                feedId = "f-1",
                contentType = ContentType.PDF,
                status = ItemStatus.UNREAD,
                tags = listOf("ml"),
            )
        val inProgressPaper =
            sampleEntry(
                id = "p-progress",
                feedId = "f-1",
                contentType = ContentType.PDF,
                status = ItemStatus.UNREAD,
                readAt = "2026-11-20T00:00:00Z",
                tags = listOf("ml"),
            )
        val finishedPaper =
            sampleEntry(
                    id = "p-finished",
                    feedId = "f-1",
                    contentType = ContentType.PDF,
                    status = ItemStatus.ARCHIVED,
                    tags = listOf("ml"),
                )
                .copy(isFinished = true)
        val dismissedPaper =
            sampleEntry(
                id = "p-archived",
                feedId = "f-1",
                contentType = ContentType.PDF,
                status = ItemStatus.ARCHIVED,
                tags = listOf("ml"),
            )

        assertTrue(matchesScope(newPaper, InboxFilter.UNREAD, null, "f-1"))
        assertTrue(matchesScope(inProgressPaper, InboxFilter.UNREAD, null, "f-1"))
        assertFalse(matchesScope(finishedPaper, InboxFilter.UNREAD, null, "f-1"))
        assertFalse(matchesScope(dismissedPaper, InboxFilter.UNREAD, null, "f-1"))

        assertTrue(matchesScope(finishedPaper, InboxFilter.FINISHED, null, "f-1"))
        assertFalse(matchesScope(dismissedPaper, InboxFilter.FINISHED, null, "f-1"))

        assertTrue(matchesScope(dismissedPaper, InboxFilter.ARCHIVED, null, "f-1"))
        assertFalse(matchesScope(finishedPaper, InboxFilter.ARCHIVED, null, "f-1"))

        assertTrue(matchesScope(newPaper, InboxFilter.ALL, null, "f-1"))
        assertTrue(matchesScope(finishedPaper, InboxFilter.ALL, null, "f-1"))
        assertTrue(matchesScope(dismissedPaper, InboxFilter.ALL, null, "f-1"))

        assertTrue(matchesScope(newPaper, InboxFilter.PAPERS, "ml", null))
        assertFalse(matchesScope(newPaper, InboxFilter.PODCASTS, "ml", null))
        assertTrue(matchesScope(dismissedPaper, InboxFilter.ARCHIVED, "ml", null))
    }

    @Test
    fun `resolveNextScopeOriginTab returns Inbox when filtering by tag from unscoped Inbox`() {
        val fromInbox = TransientUiState(activeTab = AppTab.INBOX, scopeOriginTab = null)
        assertEquals(AppTab.INBOX, resolveNextScopeOriginTab(fromInbox, hasNewScope = true))

        val fromFeeds = TransientUiState(activeTab = AppTab.FEEDS, scopeOriginTab = null)
        assertEquals(AppTab.FEEDS, resolveNextScopeOriginTab(fromFeeds, hasNewScope = true))

        val alreadyFromFeeds =
            TransientUiState(activeTab = AppTab.INBOX, scopeOriginTab = AppTab.FEEDS)
        assertEquals(AppTab.FEEDS, resolveNextScopeOriginTab(alreadyFromFeeds, hasNewScope = true))
        assertNull(resolveNextScopeOriginTab(fromInbox, hasNewScope = false))
    }

    @Test
    fun `resolveInboxStripFilters hides irrelevant format tabs on single-type feeds`() {
        val podOnlyState =
            MainUiState(
                selectedFeedId = "pod-1",
                activeFilter = InboxFilter.ALL,
                allEntries =
                    listOf(
                        sampleEntry(id = "e1", feedId = "pod-1", contentType = ContentType.AUDIO),
                        sampleEntry(id = "e2", feedId = "pod-1", contentType = ContentType.AUDIO),
                    ),
            )
        assertEquals(
            listOf(InboxFilter.UNREAD, InboxFilter.FINISHED, InboxFilter.ARCHIVED, InboxFilter.ALL),
            resolveInboxStripFilters(podOnlyState),
        )

        val mixedFeedState =
            podOnlyState.copy(
                allEntries =
                    podOnlyState.allEntries +
                        sampleEntry(id = "e3", feedId = "pod-1", contentType = ContentType.ARTICLE)
            )
        assertEquals(InboxFilter.inboxStripFilters, resolveInboxStripFilters(mixedFeedState))
    }

    @Test
    fun `EntryKind inferForCollection maps system lists and want queues accurately`() {
        assertEquals(
            EntryKind.MOVIE,
            EntryKind.inferForCollection(sampleList("c1", "movies", "Movies")),
        )
        assertEquals(
            EntryKind.MOVIE,
            EntryKind.inferForCollection(sampleList("c2", "want:movies", "Want to Watch")),
        )
        assertEquals(
            EntryKind.ALBUM,
            EntryKind.inferForCollection(sampleList("c3", "want_listen", "Want to Listen")),
        )
        assertEquals(
            EntryKind.BOOK,
            EntryKind.inferForCollection(sampleList("c4", "want_read_books", "Want to Read Books")),
        )
        assertEquals(
            EntryKind.GAME,
            EntryKind.inferForCollection(sampleList("c5", "want_play", "Want to Play")),
        )
        assertEquals(
            EntryKind.MOVIE,
            EntryKind.inferForCollection(sampleList("c2b", "custom-slug", "Want: Movies")),
        )
        assertNull(EntryKind.inferForCollection(sampleList("c2c", "podcasts", "Podcasts")))
        assertNull(EntryKind.inferForCollection(sampleList("c6", "favorites-2026", "My Favorites")))
    }

    @Test
    fun `resolveAddFabAction in Queue Want tab respects auto-selected non-empty want queue`() {
        val wantToRead =
            sampleList(
                id = WANT_TO_READ_COLLECTION_ID,
                slug = "want_to_read",
                title = "Want to Read",
                entryCount = 0,
                role = CollectionRole.WANT,
            )
        val wantMovies =
            sampleList(
                id = "want-movies-id",
                slug = "want:movies",
                title = "Want to Watch",
                entryCount = 3,
                role = CollectionRole.WANT,
            )
        val state =
            MainUiState(
                activeTab = AppTab.QUEUE,
                selectedWantCollectionId = null,
                catalog = CatalogSnapshot(wantQueues = listOf(wantToRead, wantMovies)),
            )

        val action = resolveAddFabAction(state)
        assertTrue(action is MainAction.OpenQuickLogSheet)
        val quickLog = action as MainAction.OpenQuickLogSheet
        assertTrue(quickLog.defaultToWant)
        assertEquals(EntryKind.MOVIE, quickLog.prefillKind)

        val explicitWantRead = state.copy(selectedWantCollectionId = WANT_TO_READ_COLLECTION_ID)
        assertTrue(resolveAddFabAction(explicitWantRead) is MainAction.OpenAddSheet)
    }

    @Test
    fun `creatorFieldLabelForKind returns kind-specific metadata labels`() {
        assertEquals("Director", creatorFieldLabelForKind(EntryKind.MOVIE))
        assertEquals("Author", creatorFieldLabelForKind(EntryKind.BOOK))
        assertEquals("Developer / Studio", creatorFieldLabelForKind(EntryKind.GAME))
        assertEquals("Artist", creatorFieldLabelForKind(EntryKind.ALBUM))
        assertEquals("Creator / Showrunner", creatorFieldLabelForKind(EntryKind.TV))
    }

    @Test
    fun `shouldRestoreReaderScroll skips archived, top, and completed items`() {
        assertFalse(shouldRestoreReaderScroll(sampleEntry(id = "a", readProgress = 0.0)))
        assertFalse(shouldRestoreReaderScroll(sampleEntry(id = "b", readProgress = 0.01)))
        assertTrue(shouldRestoreReaderScroll(sampleEntry(id = "c", readProgress = 0.42)))
        assertFalse(shouldRestoreReaderScroll(sampleEntry(id = "d", readProgress = 0.98)))
        assertFalse(
            shouldRestoreReaderScroll(
                sampleEntry(id = "e", readProgress = 0.50, status = ItemStatus.ARCHIVED)
            )
        )
    }

    @Test
    fun `searchCatalogEntries reports full totalCount beyond pagination limit`() = runTest {
        val env = TestRocheEnv(tempDir, "reader_inbox_search_total.preferences_pb")
        try {
            for (idx in 1..30) {
                env.repository.catalog.upsertEntry(
                    UpsertCatalogEntryInput(
                        id = "album-$idx",
                        kind = EntryKind.ALBUM,
                        title = "Ambient Vol $idx",
                        creators = listOf("Brian Eno"),
                        releaseYear = 1978,
                    )
                )
            }
            val result =
                env.repository.catalog.searchCatalogEntries(rawQuery = "ambient", limit = 10)
            assertEquals(10, result.matches.size)
            assertEquals(30, result.totalCount)
            assertTrue(result.hasMore)
        } finally {
            env.close()
        }
    }

    @Test
    fun `bottom navigation dock guard prevents removing last Settings-accessible tab`() {
        val noSettingsAccess =
            listOf(AppTab.INBOX, AppTab.LISTS, AppTab.FEEDS, AppTab.STARRED, AppTab.DIARY)
        val normalized = AppTab.normalizeBottomTabs(noSettingsAccess)
        assertTrue(AppTab.hasSettingsAccessTab(normalized))
        assertEquals(AppTab.SETTINGS, normalized.last())

        val defaultTabs = AppTab.defaultBottomTabs
        val blocked = updateBottomNavSlot(defaultTabs, slotIndex = 0, newTab = AppTab.DIARY)
        assertEquals(defaultTabs, blocked)

        val withSettings = updateBottomNavSlot(defaultTabs, slotIndex = 4, newTab = AppTab.SETTINGS)
        assertTrue(AppTab.SETTINGS in withSettings)
        val allowedReplaceHome =
            updateBottomNavSlot(withSettings, slotIndex = 0, newTab = AppTab.DIARY)
        assertEquals(AppTab.DIARY, allowedReplaceHome[0])
        assertTrue(AppTab.SETTINGS in allowedReplaceHome)
    }

    private fun sampleEntry(
        id: String,
        feedId: String = "f-1",
        contentType: ContentType = ContentType.ARTICLE,
        status: ItemStatus = ItemStatus.UNREAD,
        readAt: String? = null,
        readProgress: Double = 0.0,
        tags: List<String> = emptyList(),
    ): InboxEntry =
        InboxEntry(
            id = id,
            title = "Entry $id",
            url = "https://example.com/$id",
            feedId = feedId,
            feedTitle = "Feed",
            author = null,
            summary = null,
            contentText = null,
            contentHtml = null,
            contentType = contentType,
            status = status,
            isFavorite = false,
            isCachedOffline = false,
            thumbnailMediaId = null,
            duration = null,
            publishedAt = "2026-11-20T00:00:00Z",
            createdAt = "2026-11-20T00:00:00Z",
            readAt = readAt,
            readProgress = readProgress,
            tags = tags,
        )

    private fun sampleList(
        id: String,
        slug: String,
        title: String,
        entryCount: Int = 1,
        role: CollectionRole = CollectionRole.SYSTEM,
    ): CatalogListSummary =
        CatalogListSummary(
            collectionId = id,
            slug = slug,
            title = title,
            isDynamic = true,
            role = role,
            entryCount = entryCount,
            isPriorityScoped = role == CollectionRole.WANT,
        )
}

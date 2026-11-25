package com.grayvines.roche.ui

import androidx.lifecycle.viewModelScope
import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.data.SyncStatusState
import com.grayvines.roche.data.TestRocheEnv
import com.grayvines.roche.data.catalog.CollectionDrawSnapshot
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.UpsertCatalogEntryInput
import com.grayvines.roche.data.collectInBackground
import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.InboxItemRow
import com.grayvines.roche.data.local.upsertFeed
import com.grayvines.roche.data.local.upsertItem
import com.grayvines.roche.data.remote.NetworkTier
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.ui.theme.DarkRocheColors
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class FeatureModelsAndShellStateTest {
    @TempDir lateinit var tempDir: File

    private var envOrNull: TestRocheEnv? = null
    private var viewModelOrNull: MainViewModel? = null

    private val env: TestRocheEnv
        get() =
            envOrNull
                ?: TestRocheEnv(tempDir, "feature_models_test.preferences_pb").also {
                    envOrNull = it
                }

    private val viewModel: MainViewModel
        get() =
            viewModelOrNull
                ?: MainViewModel(repository = env.repository, settingsRepository = env.settings)
                    .also { viewModelOrNull = it }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        viewModelOrNull?.let { vm ->
            vm.playerController.releaseController()
            runBlocking { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        }
        viewModelOrNull = null
        envOrNull?.close()
        envOrNull = null
        Dispatchers.resetMain()
    }

    @Test
    fun `buildInboxScopeBuckets buckets tags case-insensitively and prioritizes tag over feed`() {
        val e1 = sampleEntry(id = "e1", feedId = "f1", tags = listOf("Systems", "systems"))
        val e2 = sampleEntry(id = "e2", feedId = "f2", tags = listOf("Rust"))
        val e3 =
            sampleEntry(id = "e3", feedId = "f1", inFeedInbox = false, tags = listOf("Systems"))

        val buckets = buildInboxScopeBuckets(listOf(e1, e2, e3), InboxFilter.ALL)
        assertEquals(listOf("e1", "e2"), buckets.unscopedEntries.map { it.id })
        assertEquals(listOf("e1", "e3"), buckets.entriesByFeedId["f1"]?.map { it.id })
        assertEquals(listOf("e1", "e3"), buckets.entriesByTag["systems"]?.map { it.id })
        assertEquals(
            listOf("e1", "e3"),
            buckets.resolveVisible(selectedFeedId = "f2", selectedTag = "SYSTEMS").map { it.id },
        )
        assertEquals(
            listOf("e2"),
            buckets.resolveVisible(selectedFeedId = "f2", selectedTag = null).map { it.id },
        )
    }

    @Test
    fun `resolveInboxRowLayout adapts star and archive chrome by width and swipe setting`() {
        val compactSwipeOn =
            resolveInboxRowLayout(isCompactWidth = true, swipeToArchiveEnabled = true)
        assertFalse(compactSwipeOn.showLeadingStarButton)
        assertTrue(compactSwipeOn.showCompactStarDot)
        assertFalse(compactSwipeOn.showTrailingArchiveButton)

        val compactSwipeOff =
            resolveInboxRowLayout(isCompactWidth = true, swipeToArchiveEnabled = false)
        assertFalse(compactSwipeOff.showLeadingStarButton)
        assertTrue(compactSwipeOff.showCompactStarDot)
        assertTrue(compactSwipeOff.showTrailingArchiveButton)

        val wideSwipeOn =
            resolveInboxRowLayout(isCompactWidth = false, swipeToArchiveEnabled = true)
        assertTrue(wideSwipeOn.showLeadingStarButton)
        assertFalse(wideSwipeOn.showCompactStarDot)
        assertTrue(wideSwipeOn.showTrailingArchiveButton)
    }

    @Test
    fun `resolveSyncHealthPresentation reports auth error offline sync error and relative sync time`() {
        val now = Instant.parse("2026-11-22T12:30:00Z")
        val cfg = Settings(serverUrl = "https://roche.example.ts.net", bearerToken = "tok-1")

        val unconfigured = resolveSyncHealthPresentation(MainUiState(), false, DarkRocheColors, now)
        assertEquals("Server Not Yet Paired", unconfigured.statusTitle)

        val authErr =
            MainUiState(
                settings = cfg,
                hasBearerToken = true,
                syncStatus = SyncStatusState(lastError = "401 Unauthorized", isAuthError = true),
            )
        val authRes = resolveSyncHealthPresentation(authErr, true, DarkRocheColors, now)
        assertEquals("Auth expired — Re-pair", authRes.statusTitle)
        assertEquals(DarkRocheColors.accentIndianRed, authRes.dotColor)

        val offlineState =
            MainUiState(
                settings = cfg,
                hasBearerToken = true,
                localNetworkTier = NetworkTier.OFFLINE,
            )
        assertEquals(
            "Offline · Server Paired",
            resolveSyncHealthPresentation(offlineState, true, DarkRocheColors, now).statusTitle,
        )

        val issueState =
            MainUiState(
                settings = cfg,
                hasBearerToken = true,
                syncStatus = SyncStatusState(lastError = "Connection timed out"),
            )
        val issueRes = resolveSyncHealthPresentation(issueState, true, DarkRocheColors, now)
        assertEquals("Sync issue", issueRes.statusTitle)
        assertEquals(DarkRocheColors.accentEcru, issueRes.dotColor)

        val syncedState =
            MainUiState(
                settings = cfg,
                hasBearerToken = true,
                syncStatus = SyncStatusState(lastSyncedAtIso = "2026-11-22T12:18:00Z"),
            )
        val syncedRes = resolveSyncHealthPresentation(syncedState, true, DarkRocheColors, now)
        assertEquals("Synced 12m ago", syncedRes.statusTitle)
        assertEquals(DarkRocheColors.accentVerdigris, syncedRes.dotColor)
    }

    @Test
    fun `UndoActionEvent restores removed queue item at original index`() = runTest {
        seedAudioFeedItems("f-q", listOf("q-1", "q-2", "q-3"))
        env.settings.update { it.copy(queueItemIds = listOf("q-1", "q-2", "q-3")) }
        val collectJob = collectInBackground(viewModel.uiState)
        viewModel.uiState.first { it.queueEntries.size == 3 }

        viewModel.dispatch(MainAction.RemoveFromQueue("q-2"))
        val afterRemove =
            viewModel.uiState.first {
                it.queueEntries.map(InboxEntry::id) == listOf("q-1", "q-3") &&
                    it.undoEvent?.kind is UndoActionKind.RestoreQueueItem
            }
        assertEquals("q-2", afterRemove.undoEvent?.itemId)

        viewModel.dispatch(MainAction.UndoLastArchive)
        val restored =
            viewModel.uiState.first {
                it.queueEntries.map(InboxEntry::id) == listOf("q-1", "q-2", "q-3")
            }
        assertNull(restored.undoEvent)
        collectJob.cancel()
    }

    @Test
    fun `UndoActionEvent restores removed catalog star membership`() = runTest {
        val albumId =
            env.repository.catalog.upsertEntry(
                UpsertCatalogEntryInput(
                    kind = EntryKind.ALBUM,
                    title = "In Rainbows",
                    creators = listOf("Radiohead"),
                    releaseYear = 2007,
                )
            )
        env.repository.catalog.addCollectionEntry(STARRED_COLLECTION_ID, albumId)
        val collectJob = collectInBackground(viewModel.uiState)
        viewModel.uiState.first { albumId in it.catalog.starredEntryIds }

        viewModel.dispatch(MainAction.RemoveWantEntry(STARRED_COLLECTION_ID, albumId))
        val afterRemove =
            viewModel.uiState.first {
                albumId !in it.catalog.starredEntryIds &&
                    it.undoEvent?.kind is UndoActionKind.RestoreCollectionEntry
            }
        assertTrue(afterRemove.undoEvent?.message?.contains("Stars") == true)

        val isRestored: (MainUiState) -> Boolean = { st -> albumId in st.catalog.starredEntryIds }
        val postUndoStates = CopyOnWriteArrayList<MainUiState>()
        val restoredDeferred = CompletableDeferred<MainUiState>()
        val undoTraceJob =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect { st ->
                    postUndoStates.add(st)
                    if (isRestored(st)) {
                        restoredDeferred.complete(st)
                    }
                }
            }
        viewModel.dispatch(MainAction.UndoLastArchive)
        val restored = restoredDeferred.await()
        undoTraceJob.cancel()
        assertNull(restored.undoEvent)
        assertTrue(postUndoStates.any(isRestored))
        assertTrue(postUndoStates.all { st -> !isRestored(st) || st.undoEvent == null })
        collectJob.cancel()
    }

    @Test
    fun `LaunchCollectionDraw routes music effects and non-music reader or external links`() =
        runTest {
            seedFeedItems("f-d", listOf("art-draw-1"))
            val effects = mutableListOf<CollectionDrawLaunchEffect>()
            val uiJob = collectInBackground(viewModel.uiState)
            val effectJob =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.drawLaunchEffects.collect { effects.add(it) }
                }
            viewModel.uiState.first { "art-draw-1" in it.readableEntryIds }

            val albumDraw =
                CollectionDrawSnapshot(
                    collectionId = "col-albums",
                    entryId = "alb-1",
                    kind = EntryKind.ALBUM,
                    title = "Kind of Blue",
                    creator = "Miles Davis",
                    youtubeMusicId = "MPREb_123456",
                )
            viewModel.dispatch(MainAction.LaunchCollectionDraw(albumDraw))
            assertEquals(
                listOf(CollectionDrawLaunchEffect.ProbeOrLaunchMusicAlbum(albumDraw)),
                effects,
            )

            val externalBookDraw =
                CollectionDrawSnapshot(
                    collectionId = "col-books",
                    entryId = "book-1",
                    kind = EntryKind.BOOK,
                    title = "Designing Data-Intensive Applications",
                    url = "https://dataintensive.net",
                )
            viewModel.dispatch(MainAction.LaunchCollectionDraw(externalBookDraw))
            assertEquals(
                CollectionDrawLaunchEffect.OpenExternalUrl("https://dataintensive.net"),
                effects.last(),
            )

            val readableArticleDraw =
                CollectionDrawSnapshot(
                    collectionId = "col-articles",
                    entryId = "art-draw-1",
                    kind = EntryKind.ARTICLE,
                    title = "Readable Essay",
                )
            viewModel.dispatch(MainAction.LaunchCollectionDraw(readableArticleDraw))
            val readerOpened = viewModel.uiState.first { it.selectedReaderEntryId == "art-draw-1" }
            assertEquals("art-draw-1", readerOpened.selectedReaderEntryId)

            effectJob.cancel()
            uiJob.cancel()
        }

    @Test
    fun `EnrichAndLaunchMusicDraw uses fallback resolve key and clears resolving spinner`() =
        runTest {
            val effects = mutableListOf<CollectionDrawLaunchEffect>()
            val uiJob = collectInBackground(viewModel.uiState)
            val effectJob =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.drawLaunchEffects.collect { effects.add(it) }
                }
            val blankIdDraw =
                CollectionDrawSnapshot(
                    collectionId = "col-albums",
                    entryId = "",
                    kind = EntryKind.ALBUM,
                    title = "A Love Supreme",
                    creator = "John Coltrane",
                )
            assertEquals("John Coltrane|A Love Supreme", blankIdDraw.musicResolveKey())
            viewModel.dispatch(MainAction.EnrichAndLaunchMusicDraw(blankIdDraw))
            assertEquals(
                listOf(CollectionDrawLaunchEffect.LaunchEnrichedMusicAlbum(blankIdDraw)),
                effects,
            )
            assertNull(
                viewModel.uiState.first { it.resolvingMusicEntryId == null }.resolvingMusicEntryId
            )
            effectJob.cancel()
            uiJob.cancel()
        }

    @Test
    fun `CatalogRankFeatureModel cancels pending music resolve when another draw launches`() =
        runTest {
            val feature = CatalogRankFeatureModel(scope = this, repository = env.repository)
            val effects = mutableListOf<CollectionDrawLaunchEffect>()
            val collectJob =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    feature.drawLaunchEffects.collect { effects.add(it) }
                }
            val drawA =
                CollectionDrawSnapshot(
                    collectionId = "col-albums",
                    entryId = "alb-a",
                    kind = EntryKind.ALBUM,
                    title = "Album A",
                    creator = "Artist A",
                )
            val drawB =
                CollectionDrawSnapshot(
                    collectionId = "col-books",
                    entryId = "book-b",
                    kind = EntryKind.BOOK,
                    title = "Book B",
                    url = "https://example.com/book-b",
                )

            feature.enrichAndLaunchMusicDraw(drawA)
            assertEquals("alb-a", feature.resolvingMusicEntryId.value)

            feature.launchCollectionDraw(drawA, emptySet(), {}, {})
            assertTrue(effects.isEmpty())

            feature.launchCollectionDraw(drawB, emptySet(), {}, {})
            testScheduler.advanceUntilIdle()
            assertNull(feature.resolvingMusicEntryId.value)
            assertEquals(
                listOf(CollectionDrawLaunchEffect.OpenExternalUrl("https://example.com/book-b")),
                effects,
            )
            collectJob.cancel()
        }

    @Test
    fun `ResetSettingsToHub and catalog detail stack transitions update state cleanly`() = runTest {
        val uiJob = collectInBackground(viewModel.uiState)
        viewModel.uiState.first { !it.isInitialLoading }
        viewModel.dispatch(MainAction.ResetSettingsToHub)
        assertEquals(
            1,
            viewModel.uiState.first { it.settingsHubResetTick == 1 }.settingsHubResetTick,
        )

        val stacked =
            reduceUiAction(
                reduceUiAction(TransientUiState(), MainAction.OpenCatalogDetailSheet("alb-1")),
                MainAction.OpenCatalogDetailSheet("artist-1"),
            )
        assertEquals(listOf("alb-1", "artist-1"), stacked.catalogDetailStack)
        assertEquals("artist-1", stacked.catalogDetailEntryId)
        val popped = reduceUiAction(stacked, MainAction.PopCatalogDetailSheet)
        assertEquals(listOf("alb-1"), popped.catalogDetailStack)
        val closed = reduceUiAction(popped, MainAction.CloseCatalogDetailSheet)
        assertTrue(closed.catalogDetailStack.isEmpty())
        assertNull(closed.catalogDetailEntryId)
        uiJob.cancel()
    }

    @Test
    fun `isUnpinnedDestination identifies tabs not in bottom nav while excluding Home`() {
        val state =
            MainUiState(
                settings =
                    Settings(
                        bottomNavTabs =
                            listOf(
                                AppTab.HOME,
                                AppTab.INBOX,
                                AppTab.LISTS,
                                AppTab.DIARY,
                                AppTab.SETTINGS,
                            )
                    )
            )
        assertFalse(state.isUnpinnedDestination(AppTab.HOME))
        assertFalse(state.isUnpinnedDestination(AppTab.INBOX))
        assertFalse(state.isUnpinnedDestination(AppTab.SETTINGS))
        assertTrue(state.isUnpinnedDestination(AppTab.OFFLINE))
        assertTrue(state.isUnpinnedDestination(AppTab.QUEUE))
        assertTrue(state.isUnpinnedDestination(AppTab.FEEDS))
    }

    @Test
    fun `entriesForScope respects hasPrecomputedBuckets and falls back to allEntries when unset`() {
        val e1 = sampleEntry(id = "e1", feedId = "f1", tags = listOf("Rust"))
        val rawState = MainUiState(allEntries = listOf(e1))
        assertEquals(
            listOf("e1"),
            rawState.entriesForScope(feedId = "f1", tag = null).map { it.id },
        )
        assertEquals(
            listOf("e1"),
            rawState.entriesForScope(feedId = null, tag = "rust").map { it.id },
        )

        val emptyPrecomputed =
            MainUiState(
                allEntries = listOf(e1),
                unscopedInboxEntries = emptyList(),
                entriesByFeedId = emptyMap(),
                entriesByTag = emptyMap(),
                hasPrecomputedBuckets = true,
            )
        assertTrue(emptyPrecomputed.entriesForScope(feedId = null, tag = null).isEmpty())
        assertTrue(emptyPrecomputed.entriesForScope(feedId = "f1", tag = null).isEmpty())
        assertTrue(emptyPrecomputed.entriesForScope(feedId = null, tag = "rust").isEmpty())
    }

    @Test
    fun `ManualRefresh finishes cleanly without leaving spinner stuck when unconfigured`() =
        runTest {
            val uiJob = collectInBackground(viewModel.uiState)
            viewModel.uiState.first { !it.isInitialLoading }
            viewModel.dispatch(MainAction.ManualRefresh)
            val settled = viewModel.uiState.first { !it.isManualRefreshing && !it.isSyncing }
            assertFalse(settled.isManualRefreshing)
            uiJob.cancel()
        }

    private suspend fun seedFeedItems(feedId: String, itemIds: List<String>) {
        val dao = env.database.dao()
        dao.upsertFeed(
            FeedRow(
                id = feedId,
                title = "Feed $feedId",
                url = "https://example.com/$feedId.xml",
                siteUrl = "https://example.com",
            )
        )
        itemIds.forEach { id ->
            dao.upsertItem(
                InboxItemRow(
                    id = id,
                    feedId = feedId,
                    feedTitle = "Feed $feedId",
                    title = "Entry $id",
                    url = "https://example.com/$id",
                    author = null,
                    summary = null,
                    contentText = null,
                    contentHtml = "<p>Body</p>",
                    contentType = "article",
                    status = "unread",
                    isFavorite = false,
                    isCachedOffline = false,
                    publishedAt = "2026-01-01T00:00:00Z",
                    createdAt = "2026-01-01T00:00:00Z",
                    readProgress = 0.0,
                    thumbnailMediaId = null,
                    duration = null,
                )
            )
        }
    }

    private suspend fun seedAudioFeedItems(feedId: String, ids: List<String>) {
        val dao = env.database.dao()
        dao.upsertFeed(
            FeedRow(
                id = feedId,
                title = "Podcast $feedId",
                url = "https://example.com/$feedId.xml",
                siteUrl = "https://example.com",
                feedType = "podcast",
            )
        )
        ids.forEach { id ->
            dao.upsertItem(
                InboxItemRow(
                    id = id,
                    feedId = feedId,
                    feedTitle = "Podcast $feedId",
                    title = "Episode $id",
                    url = "https://example.com/$id",
                    author = null,
                    summary = null,
                    contentText = null,
                    contentHtml = "<p>Notes</p>",
                    contentType = "audio",
                    status = "unread",
                    isFavorite = false,
                    isCachedOffline = false,
                    publishedAt = "2026-01-01T00:00:00Z",
                    createdAt = "2026-01-01T00:00:00Z",
                    readProgress = 0.0,
                    thumbnailMediaId = null,
                    duration = "30:00",
                    audioUrl = "https://example.com/$id.mp3",
                )
            )
        }
    }

    private fun sampleEntry(
        id: String,
        feedId: String,
        inFeedInbox: Boolean = true,
        tags: List<String> = emptyList(),
    ): InboxEntry =
        InboxEntry(
            id = id,
            feedId = feedId,
            feedTitle = "Sample Feed",
            title = "Entry $id",
            url = "https://example.com/$id",
            author = null,
            summary = null,
            contentText = null,
            contentHtml = null,
            contentType = ContentType.ARTICLE,
            status = ItemStatus.UNREAD,
            isFavorite = false,
            isCachedOffline = false,
            publishedAt = "2026-11-20T10:00:00Z",
            createdAt = "2026-11-20T10:00:00Z",
            readProgress = 0.0,
            thumbnailMediaId = null,
            duration = null,
            tags = tags,
            inFeedInbox = inFeedInbox,
        )
}

package com.grayvines.roche.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.grayvines.roche.core.DiscussionLink
import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.data.RepositorySnapshot
import com.grayvines.roche.data.RocheRepository
import com.grayvines.roche.data.TestRocheEnv
import com.grayvines.roche.data.boards.BOARD_DEFAULT_PAGE_SIZE
import com.grayvines.roche.data.boards.BOARD_SNAPSHOT_BUFFER_LIMIT
import com.grayvines.roche.data.boards.BoardAdapterType
import com.grayvines.roche.data.boards.BoardItemView
import com.grayvines.roche.data.boards.BoardOrderMode
import com.grayvines.roche.data.boards.BoardSummary
import com.grayvines.roche.data.boards.HackerNewsSection
import com.grayvines.roche.data.catalog.CatalogEntryItem
import com.grayvines.roche.data.catalog.CatalogSnapshot
import com.grayvines.roche.data.catalog.ConsumptionStatus
import com.grayvines.roche.data.catalog.DiaryEntryItem
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.RankedCatalogEntry
import com.grayvines.roche.data.collectInBackground
import com.grayvines.roche.data.local.BoardEntity
import com.grayvines.roche.data.local.BoardItemEntity
import com.grayvines.roche.data.mockRepository
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.HttpTransport
import com.grayvines.roche.data.remote.NetworkTier
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.ItemStatus
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class BoardsUiAndNavigationTest {
    @Test
    fun `AppTab BOARDS is available in bottomTabCandidates without changing defaultBottomTabs`() {
        assertTrue(AppTab.BOARDS in AppTab.bottomTabCandidates)
        assertFalse(AppTab.BOARDS in AppTab.defaultBottomTabs)
        assertEquals(
            listOf(AppTab.HOME, AppTab.INBOX, AppTab.LISTS, AppTab.FEEDS, AppTab.STARRED),
            AppTab.defaultBottomTabs,
        )

        val defaultState = MainUiState()
        assertTrue(defaultState.isUnpinnedDestination(AppTab.BOARDS))

        val pinnedTabs = updateBottomNavSlot(AppTab.defaultBottomTabs, 3, AppTab.BOARDS)
        assertEquals(AppTab.BOARDS, pinnedTabs[3])
        assertTrue(AppTab.hasSettingsAccessTab(pinnedTabs))
        assertFalse(
            MainUiState(settings = Settings(bottomNavTabs = pinnedTabs))
                .isUnpinnedDestination(AppTab.BOARDS)
        )

        assertEquals(
            MainAction.OpenAddBoardSheet,
            resolveAddFabAction(MainUiState(activeTab = AppTab.BOARDS)),
        )
        assertEquals("← Boards", readerBackLabel(MainUiState(activeTab = AppTab.BOARDS)))
    }

    @Test
    fun `deriveRocheBackStack popActionForTopKey and computeChromeFlags handle BoardReader`() {
        val boardTabState = MainUiState(activeTab = AppTab.BOARDS, selectedBoardId = "b-hn")
        assertEquals(
            listOf(RocheNavKey.Tab(AppTab.HOME), RocheNavKey.Tab(AppTab.BOARDS)),
            deriveRocheBackStack(boardTabState),
        )
        val boardTabChrome = computeChromeFlags(RocheNavKey.Tab(AppTab.BOARDS))
        assertTrue(boardTabChrome.showChrome)
        assertFalse(boardTabChrome.showAddFab)
        assertFalse(boardTabChrome.showSearchFab)
        assertFalse(boardTabChrome.hasFab)

        val readerState =
            boardTabState.copy(
                selectedBoardReaderBoardId = "b-hn",
                selectedBoardReaderExternalId = "42001",
            )
        assertEquals(
            listOf(
                RocheNavKey.Tab(AppTab.HOME),
                RocheNavKey.Tab(AppTab.BOARDS),
                RocheNavKey.BoardReader("b-hn", "42001"),
            ),
            deriveRocheBackStack(readerState),
        )
        assertEquals(
            MainAction.CloseBoardReader,
            popActionForTopKey(RocheNavKey.BoardReader("b-hn", "42001")),
        )

        val readerChrome = computeChromeFlags(RocheNavKey.BoardReader("b-hn", "42001"))
        assertFalse(readerChrome.showChrome)
        assertFalse(readerChrome.hasFab)
    }

    @Test
    fun `MainUiReducers handle board selection reader sheets and saved navigation round-trip`() {
        val item = sampleBoardItem(boardId = "b-1", externalId = "101", rank = 1)
        var state = reduceUiAction(TransientUiState(), MainAction.SelectTab(AppTab.BOARDS))
        state = reduceUiAction(state, MainAction.SelectBoard("b-1"))
        state =
            reduceUiAction(state, MainAction.SelectBoardStreamFilter(BoardStreamFilter.DISMISSED))
        state = reduceUiAction(state, MainAction.UpdateBoardFilterQuery("sqlite"))
        assertEquals("b-1", state.selectedBoardId)
        assertEquals(BoardStreamFilter.DISMISSED, state.boardStreamFilter)
        assertEquals("sqlite", state.boardFilterQuery)

        state = reduceUiAction(state, MainAction.OpenAddBoardSheet)
        assertTrue(state.isAddBoardSheetOpen)
        state = reduceUiAction(state, MainAction.OpenEditBoardSheet("b-1"))
        assertFalse(state.isAddBoardSheetOpen)
        assertEquals("b-1", state.editBoardSheetId)

        state = reduceUiAction(state, MainAction.OpenBoardReader("b-1", "101", item))
        assertNull(state.editBoardSheetId)
        assertEquals("b-1", state.selectedBoardReaderBoardId)
        assertEquals("101", state.selectedBoardReaderExternalId)
        assertTrue(state.boardReaderState?.isLoading == true)

        val savedJson =
            Json.encodeToString(
                SavedNavigationUiState.serializer(),
                state.toSavedNavigationUiState(),
            )
        val restored =
            Json.decodeFromString(SavedNavigationUiState.serializer(), savedJson)
                .toInitialTransientUiState(NetworkTier.WIFI)
        assertEquals(AppTab.BOARDS, restored.activeTab)
        assertEquals("b-1", restored.selectedBoardId)
        assertEquals("101", restored.selectedBoardReaderExternalId)

        val pruned =
            pruneMissingRestoredState(
                current = restored,
                missingReaderId = null,
                missingFeedId = null,
                missingBoardId = "b-1",
                missingBoardReaderId = "b-1",
            )
        assertNull(pruned.selectedBoardId)
        assertNull(pruned.selectedBoardReaderBoardId)
        assertNull(pruned.boardReaderState)

        val closed = reduceUiAction(state, MainAction.CloseBoardReader)
        assertNull(closed.selectedBoardReaderBoardId)
    }

    @Test
    fun `computeVisibleBoardStream enforces 30-item window and instant backfill on dismiss`() {
        val buffered =
            (1..BOARD_SNAPSHOT_BUFFER_LIMIT).map { rank ->
                sampleBoardItem(boardId = "b-hn", externalId = rank.toString(), rank = rank)
            }
        val initial =
            computeVisibleBoardStream(
                bufferedItems = buffered,
                pagination = BoardPaginationState(),
                filter = BoardStreamFilter.ACTIVE,
                filterQuery = "",
            )
        assertEquals(BOARD_DEFAULT_PAGE_SIZE, initial.items.size)
        assertEquals(1, initial.items.first().rank)
        assertEquals(30, initial.items.last().rank)
        assertTrue(initial.hasMoreLocalBuffer)
        assertFalse(initial.canLoadMoreLive)

        val withDismissed = buffered.map { item ->
            if (item.rank == 2) item.copy(dismissedAt = "2026-11-23T19:00:00Z") else item
        }
        val backfilled =
            computeVisibleBoardStream(
                bufferedItems = withDismissed,
                pagination = BoardPaginationState(),
                filter = BoardStreamFilter.ACTIVE,
                filterQuery = "",
            )
        assertEquals(BOARD_DEFAULT_PAGE_SIZE, backfilled.items.size)
        assertFalse(backfilled.items.any { it.rank == 2 })
        assertEquals(31, backfilled.items.last().rank)
        assertEquals(1, backfilled.dismissedCount)
    }

    @Test
    fun `computeVisibleBoardStream supports dismissed view inline search and live paging`() {
        val buffered =
            (1..BOARD_SNAPSHOT_BUFFER_LIMIT).map { rank ->
                sampleBoardItem(
                        boardId = "b-hn",
                        externalId = rank.toString(),
                        rank = rank,
                        title = if (rank == 15) "SQLite Deep Dive" else "Story #$rank",
                    )
                    .let { if (rank == 2) it.copy(dismissedAt = "2026-11-23T19:00:00Z") else it }
            }
        val dismissedView =
            computeVisibleBoardStream(
                bufferedItems = buffered,
                pagination = BoardPaginationState(),
                filter = BoardStreamFilter.DISMISSED,
                filterQuery = "",
            )
        assertEquals(listOf("2"), dismissedView.items.map { it.externalId })

        val filtered =
            computeVisibleBoardStream(
                bufferedItems = buffered,
                pagination = BoardPaginationState(),
                filter = BoardStreamFilter.ACTIVE,
                filterQuery = "sqlite",
            )
        assertEquals(listOf("15"), filtered.items.map { it.externalId })

        val fullBuffer =
            computeVisibleBoardStream(
                bufferedItems = buffered,
                pagination = BoardPaginationState(displayLimit = BOARD_SNAPSHOT_BUFFER_LIMIT),
                filter = BoardStreamFilter.ACTIVE,
                filterQuery = "",
            )
        assertEquals(BOARD_SNAPSHOT_BUFFER_LIMIT - 1, fullBuffer.items.size)
        assertFalse(fullBuffer.hasMoreLocalBuffer)
        assertTrue(fullBuffer.canLoadMoreLive)
    }

    @Test
    fun `hasReadableBoardReaderBody blocks auto-finish on loading or failed fallback cards`() {
        assertFalse(
            hasReadableBoardReaderBody(
                isLoading = true,
                isParsing = false,
                extractionFailed = false,
                blocksCount = 3,
                plainParagraphsCount = 0,
                errorMessage = null,
            )
        )
        assertFalse(
            hasReadableBoardReaderBody(
                isLoading = false,
                isParsing = false,
                extractionFailed = true,
                blocksCount = 0,
                plainParagraphsCount = 1,
                errorMessage = null,
            )
        )
        assertFalse(
            hasReadableBoardReaderBody(
                isLoading = false,
                isParsing = false,
                extractionFailed = false,
                blocksCount = 0,
                plainParagraphsCount = 0,
                errorMessage = "timeout",
            )
        )
        assertTrue(
            hasReadableBoardReaderBody(
                isLoading = false,
                isParsing = false,
                extractionFailed = false,
                blocksCount = 2,
                plainParagraphsCount = 0,
                errorMessage = null,
            )
        )
    }

    @Test
    fun `board story badges metadata and freshness formatting handle self posts and paused state`() {
        val askHn =
            sampleBoardItem(
                boardId = "b-1",
                externalId = "200",
                rank = 1,
                title = "Ask HN: Favorite CLI tools?",
                url = "https://news.ycombinator.com/item?id=200",
                discussionUrl = "https://news.ycombinator.com/item?id=200",
                domain = null,
                selfTextHtml = "<p>Tell me yours.</p>",
            )
        assertEquals("Ask HN", resolveBoardStoryKindBadge(askHn))
        val askMeta = formatBoardStoryMetadata(askHn)
        assertTrue(askMeta.startsWith("Ask HN"))
        assertFalse(askMeta.contains("news.ycombinator.com"))

        val arxivAbs =
            sampleBoardItem(
                boardId = "b-1",
                externalId = "201",
                rank = 2,
                url = "https://arxiv.org/abs/2401.12345",
                domain = "arxiv.org",
            )
        val arxivPdf =
            sampleBoardItem(
                boardId = "b-1",
                externalId = "202",
                rank = 3,
                url = "https://arxiv.org/pdf/1706.03762.pdf",
                domain = "arxiv.org",
            )
        val plainPdf =
            sampleBoardItem(
                boardId = "b-1",
                externalId = "203",
                rank = 4,
                url = "https://example.com/whitepaper.pdf",
                domain = "example.com",
            )
        assertEquals("Paper", resolveBoardStoryKindBadge(arxivAbs))
        assertEquals("Paper", resolveBoardStoryKindBadge(arxivPdf))
        assertEquals("PDF", resolveBoardStoryKindBadge(plainPdf))
        assertTrue(formatBoardStoryMetadata(arxivAbs).startsWith("Paper · arxiv.org"))
        assertEquals("142", formatBoardCompactComments(142))
        assertNull(formatBoardCompactComments(0))
        assertNull(formatBoardCompactComments(null))

        val board = sampleBoardSummary(id = "b-1", snapshotFetchedAt = "2026-11-23T19:50:00Z")
        assertEquals(
            "Updated 10m ago",
            formatBoardFreshnessLabel(board, now = Instant.parse("2026-11-23T20:00:00Z")),
        )
        assertEquals("Paused", formatBoardFreshnessLabel(board.copy(isActive = false)))
    }

    @Test
    fun `computeVisibleBoardStream relaxes canLoadMoreLive for under-90 buffer and all-dismissed state`() {
        val underFullBuffer =
            (1..89).map { rank ->
                sampleBoardItem(boardId = "b-hn", externalId = rank.toString(), rank = rank)
            }
        val stream89 =
            computeVisibleBoardStream(
                bufferedItems = underFullBuffer,
                pagination = BoardPaginationState(displayLimit = BOARD_SNAPSHOT_BUFFER_LIMIT),
                filter = BoardStreamFilter.ACTIVE,
                filterQuery = "",
            )
        assertEquals(89, stream89.items.size)
        assertFalse(stream89.hasMoreLocalBuffer)
        assertTrue(stream89.canLoadMoreLive)

        val exhausted =
            computeVisibleBoardStream(
                bufferedItems = underFullBuffer,
                pagination =
                    BoardPaginationState(
                        displayLimit = BOARD_SNAPSHOT_BUFFER_LIMIT,
                        liveExhausted = true,
                    ),
                filter = BoardStreamFilter.ACTIVE,
                filterQuery = "",
            )
        assertFalse(exhausted.canLoadMoreLive)

        val allDismissed =
            (1..10).map { rank ->
                sampleBoardItem(boardId = "b-hn", externalId = rank.toString(), rank = rank)
                    .copy(dismissedAt = "2026-11-23T19:00:00Z")
            }
        val emptyActive =
            computeVisibleBoardStream(
                bufferedItems = allDismissed,
                pagination = BoardPaginationState(),
                filter = BoardStreamFilter.ACTIVE,
                filterQuery = "",
            )
        assertTrue(emptyActive.items.isEmpty())
        assertEquals(10, emptyActive.dismissedCount)
        assertTrue(emptyActive.canLoadMoreLive)
        val (heading, _) =
            resolveBoardEmptyCopy(
                board = sampleBoardSummary(id = "b-hn"),
                streamFilter = BoardStreamFilter.ACTIVE,
                filterQuery = "",
                dismissedCount = emptyActive.dismissedCount,
            )
        assertEquals("All caught up", heading)
    }

    @Test
    fun `MainUiReducers reset board filters on SelectBoard and clear overlays on jump actions`() {
        val item = sampleBoardItem(boardId = "b-1", externalId = "101", rank = 1)
        var state = reduceUiAction(TransientUiState(), MainAction.SelectTab(AppTab.BOARDS))
        state = reduceUiAction(state, MainAction.SelectBoard("b-1"))
        state =
            reduceUiAction(state, MainAction.SelectBoardStreamFilter(BoardStreamFilter.DISMISSED))
        state = reduceUiAction(state, MainAction.UpdateBoardFilterQuery("rust"))
        state = reduceUiAction(state, MainAction.OpenBoardStoryContextSheet("b-1", "101", item))
        assertNotNull(state.boardContextSheetTarget)

        val switched = reduceUiAction(state, MainAction.SelectBoard("b-2"))
        assertEquals("b-2", switched.selectedBoardId)
        assertEquals(BoardStreamFilter.ACTIVE, switched.boardStreamFilter)
        assertEquals("", switched.boardFilterQuery)
        assertNull(switched.boardContextSheetTarget)

        val readerOpen = reduceUiAction(switched, MainAction.OpenBoardReader("b-2", "101", item))
        assertNotNull(readerOpen.boardReaderState)
        assertNull(reduceUiAction(readerOpen, MainAction.FilterByTag("ai")).boardReaderState)
        assertNull(reduceUiAction(readerOpen, MainAction.FilterByFeed("f-1")).boardReaderState)
        val companionJump =
            reduceUiAction(readerOpen, MainAction.OpenCompanionSection(CompanionSubTab.DIARY))
        assertNull(companionJump.selectedBoardReaderBoardId)
        assertNull(companionJump.boardReaderState)
    }

    @Test
    fun `resolveBoardFallbackModel distinguishes fetchError bodyless Ask HN and external pages`() {
        val externalItem = sampleBoardItem(boardId = "b-1", externalId = "301", rank = 1)
        val errModel = resolveBoardFallbackModel(externalItem, fetchError = "HTTP 503")
        assertEquals(BoardFallbackKind.FETCH_ERROR, errModel.kind)
        assertEquals("Couldn't load article", errModel.heading)
        assertEquals("HTTP 503", errModel.explanation)
        assertTrue(errModel.showRetryPrimary)
        assertTrue(errModel.showOriginalLink)
        assertFalse(errModel.showDiscussionPrimary)

        val bodylessAskHn =
            sampleBoardItem(
                boardId = "b-1",
                externalId = "302",
                rank = 2,
                title = "Ask HN: Best books on compilers?",
                url = "https://news.ycombinator.com/item?id=302",
                discussionUrl = "https://news.ycombinator.com/item?id=302",
                domain = null,
                selfTextHtml = null,
            )
        val selfModel = resolveBoardFallbackModel(bodylessAskHn, fetchError = null)
        assertEquals(BoardFallbackKind.SELF_POST, selfModel.kind)
        assertEquals("Discussion post", selfModel.heading)
        assertFalse(selfModel.showRetryPrimary)
        assertFalse(selfModel.showOriginalLink)
        assertTrue(selfModel.showDiscussionPrimary)

        val paywallModel = resolveBoardFallbackModel(externalItem, fetchError = null)
        assertEquals(BoardFallbackKind.UNEXTRACTABLE, paywallModel.kind)
        assertEquals("Readable text unavailable", paywallModel.heading)
        assertFalse(paywallModel.showRetryPrimary)
        assertTrue(paywallModel.showOriginalLink)
        assertFalse(paywallModel.showDiscussionPrimary)
    }

    @Test
    fun `visibleExternalDiscussions subtitles and sync messages handle polish edge cases`() {
        val hnDiscussion =
            DiscussionLink(
                source = "hacker_news",
                url = "https://news.ycombinator.com/item?id=555",
                externalId = "555",
                label = "Hacker News",
            )
        val askEntry =
            sampleInboxEntry(
                id = "e-ask",
                url = "https://news.ycombinator.com/item?id=555",
                discussions = listOf(hnDiscussion),
            )
        assertTrue(visibleExternalDiscussions(askEntry).isEmpty())

        val extEntry =
            sampleInboxEntry(
                id = "e-ext",
                url = "https://example.com/deep-dive",
                discussions = listOf(hnDiscussion),
            )
        assertEquals(1, visibleExternalDiscussions(extEntry).size)

        val paused = sampleBoardSummary(id = "b-1", isActive = false)
        assertEquals("Live front pages & Hacker News", formatBoardsUnpinnedSubtitle(MainUiState()))
        assertEquals(
            "1 paused board",
            formatBoardsUnpinnedSubtitle(MainUiState(boards = listOf(paused))),
        )
        assertEquals(
            "2 paused boards",
            formatBoardsUnpinnedSubtitle(
                MainUiState(boards = listOf(paused, paused.copy(id = "b-2")))
            ),
        )
        assertEquals(
            "Sources are up to date",
            formatLocalSyncSuccessMessage(hasServer = false, hasActiveBoards = true, count = 0),
        )
        assertEquals(
            "Refreshed 2 sources",
            formatLocalSyncSuccessMessage(hasServer = false, hasActiveBoards = true, count = 2),
        )
    }

    @Test
    fun `reconcileBoardPaginationMap links ephemeral items by canonical URL`() {
        val extEntry = sampleInboxEntry(id = "e-ext", url = "https://example.com/deep-dive")
        val ephemeral =
            sampleBoardItem(
                boardId = "b-hn",
                externalId = "901",
                rank = 91,
                url = "HTTPS://EXAMPLE.COM:443/deep-dive",
            )
        val snapshot =
            RepositorySnapshot(
                entries = listOf(extEntry),
                feeds = emptyList(),
                tags = emptyList(),
                catalog =
                    CatalogSnapshot(
                        entriesByCollectionId =
                            mapOf(
                                STARRED_COLLECTION_ID to listOf(sampleRankedCatalogEntry("e-ext"))
                            ),
                        recentDiaryEntries = listOf(sampleConsumedDiaryItem("e-ext")),
                    ),
            )
        val reconciled =
            reconcileBoardPaginationMap(
                paginationByBoardId =
                    mapOf("b-hn" to BoardPaginationState(ephemeralItems = listOf(ephemeral))),
                snapshot = snapshot,
            )
        val syncedItem = reconciled["b-hn"]!!.ephemeralItems.single()
        assertEquals("e-ext", syncedItem.linkedEntryId)
        assertTrue(syncedItem.isStarred)
        assertTrue(syncedItem.isFinished)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BoardsViewModelIntegrationTest {
    @TempDir lateinit var tempDir: File

    private var envOrNull: TestRocheEnv? = null
    private val viewModels = mutableListOf<MainViewModel>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        viewModels.forEach { vm ->
            vm.playerController.releaseController()
            runBlocking { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        }
        viewModels.clear()
        envOrNull?.close()
        envOrNull = null
        Dispatchers.resetMain()
    }

    @Test
    fun `MainViewModel board dismiss undo backfill and local loadMore work end to end`() = runTest {
        val vm = createSeededBoardViewModel("boards_dismiss_vm.preferences_pb", storyCount = 35)
        val uiJob = collectInBackground(vm.uiState)
        val loaded = vm.uiState.first { !it.isInitialLoading && it.boards.isNotEmpty() }
        assertEquals("board-hn", loaded.activeBoardId)
        val story1 = loaded.boardItemsByBoardId["board-hn"]!!.first()

        vm.dispatch(MainAction.DismissBoardItem("board-hn", story1.externalId, story1.title))
        val afterDismiss =
            vm.uiState.first { state ->
                state.boardItemsByBoardId["board-hn"]
                    ?.firstOrNull { it.externalId == story1.externalId }
                    ?.isDismissed == true &&
                    state.undoEvent?.kind is UndoActionKind.RestoreBoardDismiss
            }
        val stream =
            computeVisibleBoardStream(
                bufferedItems = afterDismiss.boardItemsByBoardId["board-hn"].orEmpty(),
                pagination =
                    afterDismiss.boardPaginationByBoardId["board-hn"] ?: BoardPaginationState(),
                filter = BoardStreamFilter.ACTIVE,
                filterQuery = "",
            )
        assertEquals(30, stream.items.size)
        assertEquals(31, stream.items.last().rank)

        vm.dispatch(MainAction.UndoLastArchive)
        val afterUndo =
            vm.uiState.first { state ->
                state.boardItemsByBoardId["board-hn"]
                    ?.firstOrNull { it.externalId == story1.externalId }
                    ?.isDismissed == false
            }
        assertNull(afterUndo.undoEvent)

        vm.dispatch(MainAction.LoadMoreBoardItems("board-hn"))
        val afterLoadMore =
            vm.uiState.first { state ->
                val limit = state.boardPaginationByBoardId["board-hn"]?.displayLimit ?: 0
                limit == 60
            }
        assertEquals(60, afterLoadMore.boardPaginationByBoardId["board-hn"]?.displayLimit)
        uiJob.cancel()
    }

    @Test
    fun `MainViewModel board star queue finish and on-demand reader flows work end to end`() =
        runTest {
            val vm = createSeededBoardViewModel("boards_promote_vm.preferences_pb", storyCount = 5)
            val uiJob = collectInBackground(vm.uiState)
            val loaded = vm.uiState.first { !it.isInitialLoading && it.boards.isNotEmpty() }
            val story1 = loaded.boardItemsByBoardId["board-hn"]!!.first()

            vm.dispatch(MainAction.ToggleBoardItemStar("board-hn", story1.externalId, story1))
            val afterStar = waitForStoryFlag(vm, story1.externalId) { it.isStarred }
            val starredItem =
                afterStar.boardItemsByBoardId["board-hn"]!!.first {
                    it.externalId == story1.externalId
                }
            val promotedEntry = afterStar.entryById[starredItem.linkedEntryId]
            assertNotNull(promotedEntry)
            assertFalse(promotedEntry!!.inFeedInbox)
            assertTrue(promotedEntry.discussions.isNotEmpty())

            vm.dispatch(MainAction.ToggleBoardItemStar("board-hn", story1.externalId, starredItem))
            waitForStoryFlag(vm, story1.externalId) { !it.isStarred }

            vm.dispatch(MainAction.ToggleBoardItemQueue("board-hn", story1.externalId, story1))
            val afterQueue = waitForStoryFlag(vm, story1.externalId) { it.isInQueue }
            val queuedItem =
                afterQueue.boardItemsByBoardId["board-hn"]!!.first {
                    it.externalId == story1.externalId
                }
            vm.dispatch(MainAction.ToggleBoardItemQueue("board-hn", story1.externalId, queuedItem))
            waitForStoryFlag(vm, story1.externalId) { !it.isInQueue }

            vm.dispatch(MainAction.OpenBoardReader("board-hn", story1.externalId, story1))
            val readerLoaded =
                vm.uiState.first { state ->
                    val reader = state.boardReaderState
                    reader?.externalId == story1.externalId && !reader.isLoading
                }
            assertTrue(
                readerLoaded.boardReaderState?.content?.contentHtml.orEmpty().contains("Readable")
            )

            vm.dispatch(MainAction.FinishBoardItem("board-hn", story1.externalId, story1))
            waitForStoryFlag(vm, story1.externalId) { it.isFinished }

            vm.dispatch(
                MainAction.DismissBoardItem("board-hn", story1.externalId, story1.title, true)
            )
            val afterDismiss =
                vm.uiState.first { state ->
                    state.selectedBoardReaderExternalId == null &&
                        state.undoEvent?.kind is UndoActionKind.RestoreBoardDismiss
                }
            assertNull(afterDismiss.boardReaderState)
            uiJob.cancel()
        }

    @Test
    fun `MainViewModel UnfinishBoardItem clears finished without re-adding to Queue`() = runTest {
        val (vm, _) = createSeededBoardHarness("boards_unfinish_vm.preferences_pb", storyCount = 5)
        val uiJob = collectInBackground(vm.uiState)
        val loaded = vm.uiState.first { !it.isInitialLoading && it.boards.isNotEmpty() }
        val story1 = loaded.boardItemsByBoardId["board-hn"]!!.first()

        vm.dispatch(MainAction.ToggleBoardItemQueue("board-hn", story1.externalId, story1))
        waitForStoryFlag(vm, story1.externalId) { it.isInQueue }
        vm.dispatch(MainAction.DismissMessages)

        vm.dispatch(
            MainAction.FinishBoardItem("board-hn", story1.externalId, story1, silent = true)
        )
        val afterFinish = waitForStoryFlag(vm, story1.externalId) { it.isFinished && !it.isInQueue }
        assertNull(afterFinish.statusBanner)
        val linkedId =
            requireNotNull(
                afterFinish.boardItemsByBoardId["board-hn"]!!
                    .first { it.externalId == story1.externalId }
                    .linkedEntryId
            )

        vm.dispatch(MainAction.UnfinishBoardItem("board-hn", story1.externalId, linkedId))
        val afterUnfinish = waitForStoryFlag(vm, story1.externalId) { !it.isFinished }
        val unfinishedItem =
            afterUnfinish.boardItemsByBoardId["board-hn"]!!.first {
                it.externalId == story1.externalId
            }
        assertFalse(unfinishedItem.isInQueue)
        uiJob.cancel()
    }

    @Test
    fun `MainViewModel UpdateBoard orderMode and PromoteBoardItemToList work end to end`() =
        runTest {
            val (vm, repo) =
                createSeededBoardHarness("boards_update_list_vm.preferences_pb", storyCount = 5)
            val uiJob = collectInBackground(vm.uiState)
            val loaded = vm.uiState.first { !it.isInitialLoading && it.boards.isNotEmpty() }
            val story1 = loaded.boardItemsByBoardId["board-hn"]!!.first()

            vm.dispatch(
                MainAction.UpdateBoard(
                    boardId = "board-hn",
                    title = "HN Chronological",
                    orderMode = BoardOrderMode.CHRONOLOGICAL,
                )
            )
            val afterUpdate =
                vm.uiState.first { state ->
                    val b = state.boards.firstOrNull { it.id == "board-hn" }
                    b?.orderMode == BoardOrderMode.CHRONOLOGICAL && b.title == "HN Chronological"
                }
            assertEquals(BoardOrderMode.CHRONOLOGICAL, afterUpdate.boards.single().orderMode)

            val listId = repo.catalog.createCustomList("Weekend Reads")
            vm.dispatch(
                MainAction.PromoteBoardItemToList(
                    boardId = "board-hn",
                    externalId = story1.externalId,
                    listId = listId,
                    listTitle = "Weekend Reads",
                    item = story1,
                )
            )
            val afterList =
                vm.uiState.first { state ->
                    state.catalog.entriesByCollectionId[listId].orEmpty().isNotEmpty()
                }
            assertEquals(1, afterList.catalog.entriesByCollectionId[listId].orEmpty().size)
            uiJob.cancel()
        }

    private suspend fun waitForStoryFlag(
        vm: MainViewModel,
        externalId: String,
        predicate: (BoardItemView) -> Boolean,
    ): MainUiState =
        vm.uiState.first { state ->
            val item =
                state.boardItemsByBoardId["board-hn"]?.firstOrNull { it.externalId == externalId }
            item != null && predicate(item)
        }

    private suspend fun createSeededBoardViewModel(
        prefsName: String,
        storyCount: Int,
    ): MainViewModel = createSeededBoardHarness(prefsName, storyCount).first

    private suspend fun createSeededBoardHarness(
        prefsName: String,
        storyCount: Int,
    ): Pair<MainViewModel, RocheRepository> {
        val env = TestRocheEnv(tempDir, prefsName).also { envOrNull = it }
        val fakeTransport = HttpTransport { req ->
            if (req.url == "https://blog.example.com/story-1") {
                HttpResponse(
                    200,
                    "<html><body><article><h1>Story 1</h1><p>Readable body text.</p></article></body></html>",
                )
            } else {
                HttpResponse(404, "not found")
            }
        }
        val repo = mockRepository(env.database, env.settings, transport = fakeTransport)
        seedBoardWithStories(env, boardId = "board-hn", count = storyCount)
        val vm =
            MainViewModel(
                    repository = repo,
                    settingsRepository = env.settings,
                    savedStateHandle = SavedStateHandle(),
                )
                .also { viewModels.add(it) }
        return vm to repo
    }
}

private fun sampleBoardSummary(
    id: String,
    isActive: Boolean = true,
    snapshotFetchedAt: String? = "2026-11-23T19:50:00Z",
): BoardSummary =
    BoardSummary(
        id = id,
        adapterType = BoardAdapterType.HACKER_NEWS,
        sourceKey = "top",
        sourceUrl = "https://news.ycombinator.com/",
        sourceConfigJson = """{"section":"top"}""",
        title = "Hacker News",
        orderMode = BoardOrderMode.RANKED,
        pollInterval = 30,
        isActive = isActive,
        snapshotFetchedAt = snapshotFetchedAt,
    )

private fun sampleInboxEntry(
    id: String,
    url: String,
    discussions: List<DiscussionLink> = emptyList(),
): InboxEntry =
    InboxEntry(
        id = id,
        feedId = null,
        feedTitle = "Boards",
        title = "Promoted Story",
        url = url,
        author = "pg",
        summary = null,
        contentText = null,
        contentHtml = null,
        contentType = ContentType.ARTICLE,
        status = ItemStatus.UNREAD,
        isFavorite = false,
        isCachedOffline = false,
        publishedAt = "2026-11-23T18:00:00Z",
        createdAt = "2026-11-23T18:00:00Z",
        readProgress = 0.0,
        thumbnailMediaId = null,
        duration = null,
        tags = emptyList(),
        inFeedInbox = false,
        discussions = discussions,
    )

private fun sampleRankedCatalogEntry(entryId: String): RankedCatalogEntry =
    RankedCatalogEntry(
        entry =
            CatalogEntryItem(
                id = entryId,
                kind = EntryKind.ARTICLE,
                title = "Promoted Story",
                creators = emptyList(),
                url = "https://example.com/deep-dive",
                publishedAt = null,
                releaseYear = null,
                summary = null,
                thumbnailUrl = null,
                identifiers = emptyList(),
            ),
        rank = 1,
        mean = 0.0,
        variance = 1.0,
        scoreOutOfTen = 5.0,
        tier = null,
        latestStatus = null,
        latestNotes = null,
    )

private fun sampleConsumedDiaryItem(entryId: String): DiaryEntryItem =
    DiaryEntryItem(
        id = "log-$entryId",
        entryId = entryId,
        kind = EntryKind.ARTICLE,
        title = "Promoted Story",
        creators = emptyList(),
        releaseYear = null,
        ts = "2026-11-23T19:00:00Z",
        monthKey = "2026-11",
        dateLabel = "Nov 23",
        status = ConsumptionStatus.CONSUMED,
        notes = null,
        tier = null,
    )

private suspend fun seedBoardWithStories(env: TestRocheEnv, boardId: String, count: Int) {
    val dao = env.database.dao()
    dao.upsertBoard(
        BoardEntity(
            id = boardId,
            adapterType = BoardAdapterType.HACKER_NEWS.wireValue,
            sourceKey = HackerNewsSection.TOP.sourceKey,
            sourceUrl = HackerNewsSection.TOP.sourceUrl,
            sourceConfigJson = HackerNewsSection.TOP.sourceConfigJson,
            title = "Hacker News",
            orderMode = BoardOrderMode.RANKED.wireValue,
            pollInterval = 30,
            isActive = true,
            snapshotFetchedAt = "2026-11-23T19:30:00Z",
            createdAt = "2026-11-23T18:00:00Z",
            updatedAt = "2026-11-23T19:30:00Z",
        )
    )
    val items =
        (1..count).map { rank ->
            BoardItemEntity(
                boardId = boardId,
                externalId = "${1000 + rank}",
                rank = rank,
                title = "HN Story #$rank",
                url = "https://blog.example.com/story-$rank",
                discussionUrl = "https://news.ycombinator.com/item?id=${1000 + rank}",
                author = "author$rank",
                domain = "blog.example.com",
                publishedAt = "2026-11-23T18:00:00Z",
                score = 100 + rank,
                commentCount = 20 + rank,
                summary = null,
                selfTextHtml = null,
                metadataJson = "{}",
                fetchedAt = "2026-11-23T19:30:00Z",
            )
        }
    dao.replaceBoardItemsInTx(boardId, items, "2026-11-23T19:30:00Z")
}

private fun sampleBoardItem(
    boardId: String,
    externalId: String,
    rank: Int,
    title: String = "Sample Story #$rank",
    url: String = "https://example.com/post-$rank",
    discussionUrl: String? = "https://news.ycombinator.com/item?id=$externalId",
    domain: String? = "example.com",
    selfTextHtml: String? = null,
): BoardItemView =
    BoardItemView(
        boardId = boardId,
        externalId = externalId,
        rank = rank,
        title = title,
        url = url,
        discussionUrl = discussionUrl,
        author = "pg",
        domain = domain,
        publishedAt = "2026-11-23T18:00:00Z",
        score = 245,
        commentCount = 142,
        summary = null,
        selfTextHtml = selfTextHtml,
        fetchedAt = "2026-11-23T19:00:00Z",
    )

package com.grayvines.roche.ui

import androidx.lifecycle.viewModelScope
import com.grayvines.roche.core.TOMBSTONE_ENTITY_COMPARISON
import com.grayvines.roche.data.SearchScopeFilter
import com.grayvines.roche.data.TestRocheEnv
import com.grayvines.roche.data.catalog.ConsumptionStatus
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.UpsertCatalogEntryInput
import com.grayvines.roche.data.collectInBackground
import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.InboxItemRow
import com.grayvines.roche.data.local.upsertFeed
import com.grayvines.roche.data.local.upsertItem
import com.grayvines.roche.data.matchesSearchScopeFilter
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.ui.components.formatHumanByteSize
import java.io.File
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogRankAndDiaryFlowsTest {
    @TempDir lateinit var tempDir: File

    private var envOrNull: TestRocheEnv? = null
    private var viewModelOrNull: MainViewModel? = null

    private val env: TestRocheEnv
        get() =
            envOrNull
                ?: TestRocheEnv(tempDir, "catalog_rank_diary_test.preferences_pb").also {
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
    fun `ConsumptionStatus uses Finished Started Skipped labels with unchanged wire values`() {
        assertEquals("Finished", ConsumptionStatus.CONSUMED.displayLabel)
        assertEquals("consumed", ConsumptionStatus.CONSUMED.wireValue)
        assertEquals("Started", ConsumptionStatus.STARTED.displayLabel)
        assertEquals("started", ConsumptionStatus.STARTED.wireValue)
        assertEquals("Skipped", ConsumptionStatus.ABANDONED.displayLabel)
        assertEquals("abandoned", ConsumptionStatus.ABANDONED.wireValue)
    }

    @Test
    fun `focused rank skip keeps round and focus while undo deletes comparison tombstone`() =
        runTest {
            val albumIds = seedAlbums(6)
            val focusId = albumIds.first()
            val uiJob = collectInBackground(viewModel.uiState)
            viewModel.uiState.first {
                it.catalog.lists.any { list -> list.entryCount >= 6 }
            }

            viewModel.dispatch(MainAction.StartRankingSession("albums", focusId))
            val initial = viewModel.uiState.first { it.rankSession?.cluster?.isNotEmpty() == true }
            val session0 = requireNotNull(initial.rankSession)
            assertEquals(0, session0.roundIndex)
            assertEquals(MAX_FOCUSED_RANK_ROUNDS, session0.roundCap)
            assertEquals(focusId, session0.focusEntryId)
            val initialClusterIds = session0.cluster.map { it.entryId }.toSet()

            viewModel.dispatch(MainAction.SkipRankCluster())
            val afterSkip =
                viewModel.uiState.first {
                    val s = it.rankSession
                    s != null && s.cluster.map { c -> c.entryId }.toSet() != initialClusterIds
                }
            val skippedSession = requireNotNull(afterSkip.rankSession)
            assertEquals(0, skippedSession.roundIndex)
            assertEquals(focusId, skippedSession.focusEntryId)
            assertTrue(skippedSession.cluster.any { it.entryId == focusId })
            val skipped1Ids = skippedSession.cluster.map { it.entryId }.toSet()

            viewModel.dispatch(MainAction.SkipRankCluster())
            val afterSkip2 =
                viewModel.uiState.first {
                    val s = it.rankSession
                    s != null && s.cluster.map { c -> c.entryId }.toSet() != skipped1Ids
                }
            val skippedSession2 = requireNotNull(afterSkip2.rankSession)
            assertEquals(0, skippedSession2.roundIndex)
            assertEquals(focusId, skippedSession2.focusEntryId)
            assertTrue(skippedSession2.skippedOpponents.size < albumIds.size - 1)

            val ordering = skippedSession2.cluster.map { listOf(it.entryId) }
            viewModel.dispatch(MainAction.SubmitRankCluster(ordering))
            val afterRound1 = viewModel.uiState.first { it.rankSession?.roundIndex == 1 }
            val compId = afterRound1.rankSession!!.history.last().comparisonId
            assertNotNull(env.database.dao().getComparisonById(compId))

            viewModel.dispatch(MainAction.UndoLastRankRound)
            val afterUndo = viewModel.uiState.first { it.rankSession?.roundIndex == 0 }
            assertTrue(afterUndo.rankSession!!.history.isEmpty())
            assertNull(env.database.dao().getComparisonById(compId))
            assertTrue(
                env.database.dao().getAllTombstones().any {
                    it.entityType == TOMBSTONE_ENTITY_COMPARISON && it.naturalKey == compId
                }
            )
            uiJob.cancel()
        }

    @Test
    fun `FinishRankEarly settles immediately and KeepRefiningRank extends roundCap by five`() =
        runTest {
            val albumIds = seedAlbums(8)
            val focusId = albumIds.first()
            val uiJob = collectInBackground(viewModel.uiState)
            viewModel.uiState.first {
                it.catalog.lists.any { list -> list.entryCount >= 8 }
            }

            viewModel.dispatch(MainAction.StartRankingSession("albums", focusId))
            val started = viewModel.uiState.first { it.rankSession?.cluster?.isNotEmpty() == true }
            val ordering = listOf(started.rankSession!!.cluster.map { it.entryId })
            viewModel.dispatch(MainAction.SubmitRankCluster(ordering))
            viewModel.uiState.first { it.rankSession?.roundIndex == 1 }

            viewModel.dispatch(MainAction.FinishRankEarly)
            val settled = viewModel.uiState.first { it.rankSession?.settlement != null }
            assertEquals(focusId, settled.rankSession!!.settlement!!.entryId)

            viewModel.dispatch(MainAction.KeepRefiningRank)
            val refined =
                viewModel.uiState.first {
                    it.rankSession?.settlement == null &&
                        it.rankSession?.cluster?.isNotEmpty() == true
                }
            val refinedSession = requireNotNull(refined.rankSession)
            assertEquals(1 + MAX_FOCUSED_RANK_ROUNDS, refinedSession.roundCap)
            assertEquals(focusId, refinedSession.focusEntryId)
            assertTrue(refinedSession.judgedClusters.isEmpty())
            val followUpOrdering = listOf(refinedSession.cluster.map { it.entryId })
            viewModel.dispatch(MainAction.SubmitRankCluster(followUpOrdering))
            val afterRefineSubmit = viewModel.uiState.first { it.rankSession?.roundIndex == 2 }
            assertNull(afterRefineSubmit.rankSession?.settlement)
            uiJob.cancel()
        }

    @Test
    fun `k2 focused session persists cluster size and avoids premature settlement after tie`() =
        runTest {
            val albumIds = seedAlbums(8)
            val focusId = albumIds.first()
            val uiJob = collectInBackground(viewModel.uiState)
            viewModel.uiState.first {
                it.catalog.lists.any { list -> list.entryCount >= 8 }
            }

            viewModel.dispatch(MainAction.StartRankingSession("albums", focusId))
            viewModel.uiState.first { it.rankSession?.cluster?.size == 5 }

            viewModel.dispatch(MainAction.SetRankClusterSize(2, reloadCluster = true))
            val resized =
                viewModel.uiState.first {
                    val s = it.rankSession
                    s != null &&
                        s.targetClusterSize == 2 &&
                        s.cluster.size == 2 &&
                        it.settings.rankClusterSize == 2
                }
            val firstOpponent =
                requireNotNull(resized.rankSession).cluster.first { it.entryId != focusId }.entryId

            viewModel.dispatch(
                MainAction.SubmitRankCluster(ordering = listOf(listOf(focusId, firstOpponent)))
            )
            val round1 = viewModel.uiState.first { it.rankSession?.roundIndex == 1 }
            val round1Session = requireNotNull(round1.rankSession)
            assertNull(round1Session.settlement)
            assertEquals(2, round1Session.cluster.size)
            assertTrue(round1Session.cluster.any { it.entryId == focusId })
            assertTrue(round1Session.cluster.none { it.entryId == firstOpponent })

            viewModel.dispatch(MainAction.SetRankClusterSize(3, reloadCluster = false))
            viewModel.uiState.first { it.settings.rankClusterSize == 3 }
            viewModel.dispatch(MainAction.CloseRankSession)
            viewModel.uiState.first { it.rankSession == null }

            viewModel.dispatch(MainAction.StartRankingSession("albums", null))
            val reopened = viewModel.uiState.first { it.rankSession?.cluster?.isNotEmpty() == true }
            val reopenedSession = requireNotNull(reopened.rankSession)
            assertEquals(3, reopenedSession.targetClusterSize)
            assertEquals(3, reopenedSession.cluster.size)
            uiJob.cancel()
        }

    @Test
    fun `partial subset exclusion cools down excluded items and undo restores them`() = runTest {
        val albumIds = seedAlbums(8)
        val focusId = albumIds.first()
        val uiJob = collectInBackground(viewModel.uiState)
        viewModel.uiState.first {
            it.catalog.lists.any { list -> list.entryCount >= 8 }
        }

        viewModel.dispatch(MainAction.StartRankingSession("albums", focusId))
        val started = viewModel.uiState.first { it.rankSession?.cluster?.size == 5 }
        val nonFocus = requireNotNull(started.rankSession).cluster.filter { it.entryId != focusId }
        val keptIds = listOf(focusId, nonFocus[0].entryId, nonFocus[1].entryId)
        val excludedIds = setOf(nonFocus[2].entryId, nonFocus[3].entryId)

        viewModel.dispatch(
            MainAction.SubmitRankCluster(
                ordering = keptIds.map(::listOf),
                excludedEntryIds = excludedIds,
            )
        )
        val round1 = viewModel.uiState.first { it.rankSession?.roundIndex == 1 }
        val round1Session = requireNotNull(round1.rankSession)
        assertEquals(keptIds.toSet(), round1Session.judgedClusters.last())
        assertTrue(excludedIds.all { it in round1Session.excludedOpponents })
        assertTrue(round1Session.cluster.none { it.entryId in excludedIds })

        viewModel.dispatch(MainAction.UndoLastRankRound)
        val afterUndo = viewModel.uiState.first { it.rankSession?.roundIndex == 0 }
        assertTrue(requireNotNull(afterUndo.rankSession).excludedOpponents.isEmpty())
        uiJob.cancel()
    }

    @Test
    fun `non-focused exclusion cooldown falls back instead of settling on covered pair`() =
        runTest {
            seedAlbums(5)
            val uiJob = collectInBackground(viewModel.uiState)
            viewModel.uiState.first {
                it.catalog.lists.any { list -> list.entryCount >= 5 }
            }

            viewModel.dispatch(MainAction.StartRankingSession("albums", null))
            val started = viewModel.uiState.first { it.rankSession?.cluster?.size == 5 }
            val allFive = requireNotNull(started.rankSession).cluster.map { it.entryId }
            val keptPair = allFive.take(2)
            val excludedThree = allFive.drop(2).toSet()

            viewModel.dispatch(
                MainAction.SubmitRankCluster(
                    ordering = keptPair.map(::listOf),
                    excludedEntryIds = excludedThree,
                )
            )
            val round1 = viewModel.uiState.first { it.rankSession?.roundIndex == 1 }
            val round1Session = requireNotNull(round1.rankSession)
            assertNull(round1Session.settlement)
            assertTrue(round1Session.cluster.size >= 2)
            assertTrue(round1Session.cluster.any { it.entryId in excludedThree })
            uiJob.cancel()
        }

    @Test
    fun `skip after in-place shrink draws a different pair and undo restores target size`() =
        runTest {
            seedAlbums(5)
            val uiJob = collectInBackground(viewModel.uiState)
            viewModel.uiState.first {
                it.catalog.lists.any { list -> list.entryCount >= 5 }
            }

            viewModel.dispatch(MainAction.StartRankingSession("albums", null))
            val started = viewModel.uiState.first { it.rankSession?.cluster?.size == 5 }
            val activeTwo = requireNotNull(started.rankSession).cluster.take(2).map { it.entryId }

            viewModel.dispatch(MainAction.SetRankClusterSize(2, reloadCluster = false))
            viewModel.uiState.first { it.rankSession?.targetClusterSize == 2 }

            viewModel.dispatch(MainAction.SkipRankCluster(activeEntryIds = activeTwo))
            val skipped =
                viewModel.uiState.first {
                    val s = it.rankSession
                    s != null &&
                        s.cluster.size == 2 &&
                        s.cluster.map { c -> c.entryId }.toSet() != activeTwo.toSet()
                }
            val skippedCluster = requireNotNull(skipped.rankSession).cluster
            assertEquals(2, skippedCluster.size)

            viewModel.dispatch(
                MainAction.SubmitRankCluster(ordering = skippedCluster.map { listOf(it.entryId) })
            )
            viewModel.uiState.first { it.rankSession?.roundIndex == 1 }
            viewModel.dispatch(MainAction.SetRankClusterSize(4, reloadCluster = false))
            viewModel.uiState.first { it.rankSession?.targetClusterSize == 4 }

            viewModel.dispatch(MainAction.UndoLastRankRound)
            val afterUndo = viewModel.uiState.first { it.rankSession?.roundIndex == 0 }
            val undone = requireNotNull(afterUndo.rankSession)
            assertEquals(2, undone.targetClusterSize)
            assertEquals(2, undone.loadedMaxK)
            uiJob.cancel()
        }

    @Test
    fun `diary edit and delete actions update and remove consumption logs`() = runTest {
        val entryId =
            env.repository.catalog.logEntry(
                input = UpsertCatalogEntryInput(kind = EntryKind.BOOK, title = "Dune"),
                status = ConsumptionStatus.CONSUMED,
                notes = "First read",
            )
        val uiJob = collectInBackground(viewModel.uiState)
        val loaded =
            viewModel.uiState.first { st -> st.diary.entries.any { it.entryId == entryId } }
        val logId = loaded.diary.entries.first { it.entryId == entryId }.id

        viewModel.dispatch(
            MainAction.UpdateConsumptionLog(
                logId = logId,
                status = ConsumptionStatus.ABANDONED,
                notes = "Updated note",
                clearNotes = false,
                tsIso = "2025-06-15T12:00:00Z",
            )
        )
        // Updating status to ABANDONED hides it from the default Finished filter; switch to All.
        viewModel.dispatch(MainAction.SetDiaryStatusFilter(null))
        val updated =
            viewModel.uiState.first { st ->
                st.diary.statusFilter == null &&
                    st.diary.entries.any {
                        it.id == logId &&
                            it.status == ConsumptionStatus.ABANDONED &&
                            it.notes == "Updated note"
                    }
            }
        assertTrue(updated.diary.entries.first { it.id == logId }.ts.startsWith("2025-06-15"))

        viewModel.dispatch(MainAction.DeleteConsumptionLog(logId))
        val deleted = viewModel.uiState.first { st -> st.diary.entries.none { it.id == logId } }
        assertTrue(deleted.diary.entries.none { it.id == logId })
        uiJob.cancel()
    }

    @Test
    fun `ToggleCatalogStar ToggleCatalogWant and OpenQuickLogSheet preserve exact entry id`() =
        runTest {
            val albumId =
                env.repository.catalog.upsertEntry(
                    UpsertCatalogEntryInput(
                        kind = EntryKind.ALBUM,
                        title = "Kid A",
                        creators = listOf("Radiohead"),
                        releaseYear = 2000,
                    )
                )
            val uiJob = collectInBackground(viewModel.uiState)
            viewModel.uiState.first { it.catalog.lists.isNotEmpty() }

            viewModel.dispatch(MainAction.ToggleCatalogStar(albumId))
            viewModel.uiState.first { st -> albumId in st.catalog.starredEntryIds }
            viewModel.dispatch(MainAction.ToggleCatalogStar(albumId))
            val unstarred =
                viewModel.uiState.first { st ->
                    albumId !in st.catalog.starredEntryIds &&
                        st.undoEvent?.kind is UndoActionKind.RestoreCollectionEntry
                }
            assertNotNull(unstarred.undoEvent)

            viewModel.dispatch(MainAction.ToggleCatalogWant(albumId))
            viewModel.uiState.first { st -> albumId in st.catalog.wantEntryIds }

            val quickLogState =
                reduceUiAction(
                    TransientUiState(),
                    MainAction.OpenQuickLogSheet(
                        prefillKind = EntryKind.ALBUM,
                        prefillTitle = "Kid A",
                        existingEntryId = albumId,
                    ),
                )
            assertEquals(albumId, quickLogState.quickLogExistingEntryId)
            uiJob.cancel()
        }

    @Test
    fun `offline helpers format bytes identify played entries and filter Lists and Diary scopes`() =
        runTest {
            assertEquals("0 B", formatHumanByteSize(0L))
            assertEquals("512 B", formatHumanByteSize(512L))
            assertEquals("2 KB", formatHumanByteSize(2_048L))
            assertEquals("1.5 MB", formatHumanByteSize(1_572_864L))

            val playedPodcast =
                sampleInboxEntry(id = "p1", contentType = ContentType.AUDIO, readProgress = 0.95)
            val activePodcast =
                sampleInboxEntry(id = "p2", contentType = ContentType.AUDIO, readProgress = 0.25)
            val readArticle =
                sampleInboxEntry(
                    id = "a1",
                    contentType = ContentType.ARTICLE,
                    status = ItemStatus.UNREAD,
                    readAt = "2026-11-20T00:00:00Z",
                )
            assertTrue(isPlayedOrReadOfflineEntry(playedPodcast))
            assertFalse(isPlayedOrReadOfflineEntry(activePodcast))
            assertTrue(isPlayedOrReadOfflineEntry(readArticle))
            assertFalse(matchesSearchScopeFilter(readArticle, SearchScopeFilter.LISTS))
            assertFalse(matchesSearchScopeFilter(readArticle, SearchScopeFilter.DIARY))
            assertNotEquals(SearchScopeFilter.LISTS, SearchScopeFilter.DIARY)

            val dao = env.database.dao()
            dao.upsertFeed(
                FeedRow("f-off", "Pod", "https://example.com/pod.xml", "https://example.com")
            )
            dao.upsertItem(
                InboxItemRow(
                    id = "off-1",
                    feedId = "f-off",
                    feedTitle = "Pod",
                    title = "Done Episode",
                    url = "https://example.com/ep1",
                    author = null,
                    summary = null,
                    contentText = null,
                    contentHtml = null,
                    contentType = "audio",
                    status = "archived",
                    isFavorite = false,
                    isCachedOffline = true,
                    publishedAt = "2026-01-01T00:00:00Z",
                    createdAt = "2026-01-01T00:00:00Z",
                    readProgress = 0.96,
                    thumbnailMediaId = null,
                    duration = "20:00",
                )
            )
            val uiJob = collectInBackground(viewModel.uiState)
            viewModel.uiState.first { st -> st.offlineEntries.any { it.id == "off-1" } }
            viewModel.dispatch(MainAction.EvictPlayedOfflineEntries)
            viewModel.uiState.first { st -> st.offlineEntries.none { it.id == "off-1" } }
            uiJob.cancel()
        }

    private suspend fun seedAlbums(count: Int): List<String> =
        (1..count).map { idx ->
            env.repository.catalog.logEntry(
                input =
                    UpsertCatalogEntryInput(
                        kind = EntryKind.ALBUM,
                        title = "Album $idx",
                        creators = listOf("Artist $idx"),
                        releaseYear = 2000 + idx,
                    ),
                status = ConsumptionStatus.CONSUMED,
            )
        }

    private fun sampleInboxEntry(
        id: String,
        contentType: ContentType,
        status: ItemStatus = ItemStatus.UNREAD,
        readProgress: Double = 0.0,
        readAt: String? = null,
    ): InboxEntry =
        InboxEntry(
            id = id,
            feedId = "f1",
            feedTitle = "Feed",
            title = "Title $id",
            url = "https://example.com/$id",
            author = null,
            summary = null,
            contentText = null,
            contentHtml = null,
            contentType = contentType,
            status = status,
            isFavorite = false,
            isCachedOffline = true,
            publishedAt = "2026-11-20T00:00:00Z",
            createdAt = "2026-11-20T00:00:00Z",
            readProgress = readProgress,
            thumbnailMediaId = null,
            duration = null,
            tags = emptyList(),
            readAt = readAt,
        )
}

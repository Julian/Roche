package com.grayvines.roche.ui

import androidx.lifecycle.viewModelScope
import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.data.TestRocheEnv
import com.grayvines.roche.data.catalog.CatalogStore
import com.grayvines.roche.data.catalog.DEFAULT_COLLECTION_PAGE_SIZE
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.ConsumptionLogEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.ranking.EpRankingEngine
import java.io.File
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogManualScaleAndStarredTest {
    @TempDir lateinit var tempDir: File

    private lateinit var env: TestRocheEnv
    private lateinit var viewModel: MainViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        env = TestRocheEnv(tempDir, "catalog_manual_scale_starred_test.preferences_pb")
        viewModel = MainViewModel(repository = env.repository, settingsRepository = env.settings)
    }

    @AfterEach
    fun tearDown() {
        viewModel.playerController.releaseController()
        runBlocking { viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        env.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `large manual starred collection paginates via SQL filters by kind and settles with neighbors`() =
        runTest {
            seedLargeStarredFixture(songCount = 540, bookCount = 15)
            val catalog = env.repository.catalog
            val uiJob = launch(UnconfinedTestDispatcher()) { viewModel.uiState.collect {} }

            verifyStarredTabPaginationAndKindFilter()
            verifyStarredListDetailAndSettlement(catalog)

            uiJob.cancel()
        }

    private suspend fun verifyStarredTabPaginationAndKindFilter() {
        viewModel.dispatch(MainAction.SelectTab(AppTab.STARRED))
        val starredPage1 =
            viewModel.uiState.first {
                it.starredCollection.isLoaded &&
                    it.starredCollection.totalCount == 555 &&
                    it.starredCollection.entries.size == DEFAULT_COLLECTION_PAGE_SIZE
            }
        assertTrue(starredPage1.starredCollection.hasMore)
        assertEquals(
            listOf(EntryKind.SONG to 540, EntryKind.BOOK to 15),
            starredPage1.starredCollection.kindCounts,
        )

        viewModel.dispatch(MainAction.LoadMoreStarredCollection)
        val starredPage2 =
            viewModel.uiState.first {
                it.starredCollection.entries.size == DEFAULT_COLLECTION_PAGE_SIZE * 2
            }
        assertTrue(starredPage2.starredCollection.hasMore)

        viewModel.dispatch(MainAction.SetStarredKindFilter(EntryKind.BOOK))
        val starredBooks =
            viewModel.uiState.first {
                it.starredCollection.selectedKind == EntryKind.BOOK &&
                    it.starredCollection.filteredTotalCount == 15 &&
                    it.starredCollection.entries.size == 15
            }
        assertEquals(555, starredBooks.starredCollection.totalCount)
        assertTrue(starredBooks.starredCollection.entries.all { it.entry.kind == EntryKind.BOOK })
        assertEquals((1..15).toList(), starredBooks.starredCollection.entries.map { it.rank })
        assertEquals(false, starredBooks.starredCollection.hasMore)
    }

    private suspend fun verifyStarredListDetailAndSettlement(catalog: CatalogStore) {
        viewModel.dispatch(
            MainAction.OpenCompanionSection(CompanionSubTab.LISTS, STARRED_COLLECTION_ID)
        )
        val listPage =
            viewModel.uiState.first {
                it.activeCollection.collectionId == STARRED_COLLECTION_ID &&
                    it.activeCollection.isLoaded &&
                    it.activeCollection.totalCount == 555
            }
        assertEquals(DEFAULT_COLLECTION_PAGE_SIZE, listPage.activeCollection.entries.size)

        viewModel.dispatch(
            MainAction.SetCatalogCollectionKindFilter(STARRED_COLLECTION_ID, EntryKind.BOOK)
        )
        val listBooks =
            viewModel.uiState.first {
                it.activeCollection.selectedKind == EntryKind.BOOK &&
                    it.activeCollection.entries.size == 15
            }
        assertEquals(15, listBooks.activeCollection.filteredTotalCount)

        val cluster =
            catalog.selectNextRankCluster(STARRED_COLLECTION_ID, focusEntryId = "song-001")
        assertTrue(cluster.size >= EpRankingEngine.MIN_K)
        assertTrue(cluster.any { it.entryId == "song-001" })

        val settlement = catalog.resolveEntrySettlement("star-book-07", STARRED_COLLECTION_ID)
        assertNotNull(settlement)
        assertEquals(555, settlement!!.totalCount)
        assertEquals(3, settlement.neighbors.size)
        assertEquals("star-book-07", settlement.neighbors[1].entry.id)
    }

    @Test
    fun `large want queue over 500 items loads without OOM and starred filter survives tab switch`() =
        runTest {
            seedLargeStarredFixture(songCount = 510, bookCount = 1)
            val dao = env.database.dao()
            val channel =
                EntryEntity(
                    id = "star-chan-01",
                    kind = "channel",
                    title = "Great Channel",
                    createdAt = "2026-01-01T00:00:00Z",
                    updatedAt = "2026-01-01T00:00:00Z",
                )
            dao.upsertEntry(channel)
            dao.upsertCollectionEntry(
                CollectionEntryEntity(
                    collectionId = STARRED_COLLECTION_ID,
                    entryId = channel.id,
                    addedAt = "2026-01-01T00:00:00Z",
                )
            )
            val wantBooks = (1..515).map(::buildTestBookEntry)
            dao.upsertEntries(wantBooks)
            dao.upsertCollectionEntries(
                wantBooks.map { b ->
                    CollectionEntryEntity(
                        collectionId = WANT_TO_READ_COLLECTION_ID,
                        entryId = b.id,
                        addedAt = "2026-01-01T00:00:00Z",
                    )
                }
            )
            // Int.MAX_VALUE limit must never OOM or overflow on large manual collections
            val fullPage =
                env.repository.catalog.loadCollectionPage(
                    collectionIdOrSlug = WANT_TO_READ_COLLECTION_ID,
                    limit = Int.MAX_VALUE,
                    offset = 5,
                )
            assertEquals(515, fullPage.totalCount)
            assertEquals(510, fullPage.entries.size)

            val uiJob = launch(UnconfinedTestDispatcher()) { viewModel.uiState.collect {} }
            verifyStarredTabSwitchAndMultiKindFilter(channel.id)
            uiJob.cancel()
        }

    private suspend fun verifyStarredTabSwitchAndMultiKindFilter(channelId: String) {
        viewModel.dispatch(MainAction.SelectTab(AppTab.STARRED))
        viewModel.dispatch(
            MainAction.SetStarredKindFilter(
                kind = null,
                filterKey = "videos",
                kinds = setOf(EntryKind.VIDEO, EntryKind.CHANNEL),
            )
        )
        val videosFiltered =
            viewModel.uiState.first {
                it.starredCollection.selectedFilterKey == "videos" &&
                    it.starredCollection.entries.any { row -> row.entry.id == channelId }
            }
        assertEquals(1, videosFiltered.starredCollection.filteredTotalCount)

        // Switching away and back preserves the selected Starred filter cleanly
        viewModel.dispatch(MainAction.SelectTab(AppTab.HOME))
        viewModel.dispatch(MainAction.SelectTab(AppTab.STARRED))
        val returned = viewModel.uiState.first { it.activeTab == AppTab.STARRED }
        assertEquals("videos", returned.starredCollection.requestedFilterKey)
        assertEquals("videos", returned.starredCollection.selectedFilterKey)

        // Unstarring the last book while dynamic kind_book is active auto-resets to "all"
        viewModel.dispatch(MainAction.SetStarredKindFilter(EntryKind.BOOK))
        viewModel.uiState.first {
            it.starredCollection.selectedFilterKey == "kind_book" &&
                it.starredCollection.filteredTotalCount == 1
        }
        env.repository.catalog.removeCollectionEntry(STARRED_COLLECTION_ID, "star-book-01")
        val autoReset =
            viewModel.uiState.first {
                it.starredCollection.selectedFilterKey == "all" &&
                    it.starredCollection.entries.size == DEFAULT_COLLECTION_PAGE_SIZE
            }
        assertEquals("all", autoReset.starredCollection.requestedFilterKey)
    }

    private suspend fun seedLargeStarredFixture(songCount: Int, bookCount: Int) {
        val dao = env.database.dao()
        env.repository.catalog.ensureSystemSeeded()
        val songs = (1..songCount).map(::buildTestSongEntry)
        val logs = (1..songCount).map(::buildTestSongLog)
        val books = (1..bookCount).map(::buildTestBookEntry)
        dao.upsertEntries(songs + books)
        dao.insertConsumptionLogsIgnore(logs)
        env.repository.catalog.recordComparison(
            listOf(listOf("song-001"), listOf("song-002"), listOf("song-003"))
        )
        env.repository.catalog.recordComparison(
            listOf(listOf("song-002"), listOf("song-004"), listOf("song-005"))
        )
        val memberships =
            (songs + books).map { e ->
                CollectionEntryEntity(
                    collectionId = STARRED_COLLECTION_ID,
                    entryId = e.id,
                    addedAt = "2026-01-01T00:00:00Z",
                )
            }
        dao.upsertCollectionEntries(memberships)
    }

    private fun buildTestSongEntry(i: Int): EntryEntity {
        val padded = "%03d".format(i)
        return EntryEntity(
            id = "song-$padded",
            kind = "song",
            title = "Track $padded",
            creatorsJson = """["Artist $padded"]""",
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
        )
    }

    private fun buildTestSongLog(i: Int): ConsumptionLogEntity {
        val padded = "%03d".format(i)
        return ConsumptionLogEntity(
            id = "play-$padded",
            entryId = "song-$padded",
            ts = "2026-05-%02dT10:%02d:00Z".format((i - 1) % 28 + 1, i % 60),
            status = "consumed",
            createdAt = "2026-05-01T00:00:00Z",
        )
    }

    private fun buildTestBookEntry(i: Int): EntryEntity =
        EntryEntity(
            id = "star-book-%02d".format(i),
            kind = "book",
            title = "Zebra Book %02d".format(i),
            creatorsJson = """["Author $i"]""",
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
        )
}

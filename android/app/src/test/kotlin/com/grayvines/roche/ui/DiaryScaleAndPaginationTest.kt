package com.grayvines.roche.ui

import androidx.lifecycle.viewModelScope
import com.grayvines.roche.data.TestRocheEnv
import com.grayvines.roche.data.catalog.ConsumptionStatus
import com.grayvines.roche.data.catalog.DEFAULT_DIARY_PAGE_SIZE
import com.grayvines.roche.data.catalog.DEFAULT_ENTRY_DETAIL_LOGS_LIMIT
import com.grayvines.roche.data.catalog.DiaryKindFilter
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.RECENT_DIARY_PREVIEW_LIMIT
import com.grayvines.roche.data.catalog.SyncConsumptionLogDto
import com.grayvines.roche.data.catalog.SyncEntryRecordDto
import com.grayvines.roche.data.catalog.UpsertCatalogEntryInput
import com.grayvines.roche.data.local.ConsumptionLogEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.sync.reconcilePeerSyncResponse
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryScaleAndPaginationTest {
    @TempDir lateinit var tempDir: File

    private lateinit var env: TestRocheEnv
    private lateinit var viewModel: MainViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        env = TestRocheEnv(tempDir, "diary_scale_pagination_test.preferences_pb")
        viewModel =
            MainViewModel(
                repository = env.repository,
                settingsRepository = env.settings,
            )
    }

    @AfterEach
    fun tearDown() {
        viewModel.playerController.releaseController()
        runBlocking { viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        env.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `snapshot bounds recent diary preview and hides individual song plays by default`() =
        runTest {
            seedScaleFixture()

            val snap = env.repository.catalog.getSnapshot()
            assertEquals(RECENT_DIARY_PREVIEW_LIMIT, snap.recentDiaryEntries.size)
            // 80 non-song book logs (70 song plays hidden by default)
            assertEquals(80, snap.totalDiaryCount)
            assertTrue(snap.recentDiaryEntries.none { it.kind == EntryKind.SONG })
            // 1 consumed song + 70 consumed books = 71 distinct diary-visible consumed entries
            assertEquals(71, snap.consumedCount)
            assertTrue(snap.recentDiaryEntries.all { it.url != null })
        }

    @Test
    fun `diary tab hides songs by default and paginates past 500 rows across kind status and year`() =
        runTest {
            seedScaleFixture(songPlayCount = 520, bookCount = 40)
            val uiJob = launch(UnconfinedTestDispatcher()) { viewModel.uiState.collect {} }

            // Default view filters to Finished (CONSUMED) and excludes 520 individual song plays,
            // showing only the 30 finished books (excluding the 10 skipped books).
            val defaultNonSong =
                viewModel.uiState.first {
                    it.diary.isLoaded &&
                        it.diary.statusFilter == ConsumptionStatus.CONSUMED &&
                        it.diary.kindFilter == DiaryKindFilter.DefaultNonSong &&
                        it.diary.totalFilteredCount == 30
                }
            assertEquals(30, defaultNonSong.diary.entries.size)
            assertTrue(defaultNonSong.diary.entries.all { it.kind == EntryKind.BOOK })
            assertTrue(defaultNonSong.diary.hasSongLogs)
            assertEquals(listOf(EntryKind.BOOK to 30), defaultNonSong.diary.nonSongKindCounts)
            assertEquals(listOf("2026"), defaultNonSong.diary.availableYears)

            // Switching to Songs isolates the 520 song plays and paginates cleanly.
            viewModel.dispatch(MainAction.SetDiaryKindFilter(DiaryKindFilter.Only(EntryKind.SONG)))
            val songsOnly =
                viewModel.uiState.first {
                    it.diary.kindFilter == DiaryKindFilter.Only(EntryKind.SONG) &&
                        it.diary.totalFilteredCount == 520
                }
            assertEquals(DEFAULT_DIARY_PAGE_SIZE, songsOnly.diary.entries.size)
            assertTrue(songsOnly.diary.entries.all { it.kind == EntryKind.SONG })
            assertEquals(listOf("2025"), songsOnly.diary.availableYears)

            // Switching to All statuses + All kinds includes all 560 logs across 2026 & 2025.
            viewModel.dispatch(MainAction.SetDiaryStatusFilter(null))
            viewModel.dispatch(MainAction.SetDiaryKindFilter(DiaryKindFilter.All))
            assertPaginatesAllLogsAcross560Rows()

            viewModel.dispatch(MainAction.SetDiaryStatusFilter(ConsumptionStatus.ABANDONED))
            val skippedOnly =
                viewModel.uiState.first {
                    it.diary.statusFilter == ConsumptionStatus.ABANDONED &&
                        it.diary.totalFilteredCount == 10
                }
            assertEquals(10, skippedOnly.diary.entries.size)
            assertFalse(skippedOnly.diary.hasMore)

            viewModel.dispatch(MainAction.SetDiaryStatusFilter(null))
            viewModel.dispatch(MainAction.SetDiaryYearFilter("2025"))
            val year2025 =
                viewModel.uiState.first {
                    it.diary.statusFilter == null &&
                        it.diary.yearFilter == "2025" &&
                        it.diary.totalFilteredCount == 520
                }
            assertEquals(DEFAULT_DIARY_PAGE_SIZE, year2025.diary.entries.size)
            assertTrue(year2025.diary.entries.all { it.entryId == REPEAT_SONG_ID })

            uiJob.cancel()
        }

    private suspend fun assertPaginatesAllLogsAcross560Rows() {
        val initial =
            viewModel.uiState.first {
                it.diary.statusFilter == null &&
                    it.diary.kindFilter == DiaryKindFilter.All &&
                    it.diary.entries.size == DEFAULT_DIARY_PAGE_SIZE &&
                    it.diary.totalFilteredCount == 560
            }
        assertTrue(initial.diary.hasMore)
        assertEquals(listOf("2026", "2025"), initial.diary.availableYears)

        repeat(5) { step ->
            val targetSize = minOf(560, DEFAULT_DIARY_PAGE_SIZE * (step + 2))
            viewModel.dispatch(MainAction.LoadMoreDiary)
            val page = viewModel.uiState.first { it.diary.entries.size == targetSize }
            assertEquals(targetSize < 560, page.diary.hasMore)
        }
    }

    @Test
    fun `entry detail sheet paginates past 500 logs and refreshes edits outside top 12`() =
        runTest {
            seedScaleFixture(songPlayCount = 520, bookCount = 40)
            val uiJob = launch(UnconfinedTestDispatcher()) { viewModel.uiState.collect {} }

            // Open detail sheet for song whose logs are all in 2025 (outside the top 12 recent
            // logs!)
            viewModel.dispatch(MainAction.OpenCatalogDetailSheet(REPEAT_SONG_ID))
            val detailInitial =
                viewModel.uiState.first {
                    it.catalogDetailLogs?.entryId == REPEAT_SONG_ID &&
                        it.catalogDetailLogs.logs.size == DEFAULT_ENTRY_DETAIL_LOGS_LIMIT
                }
            assertEquals(520, requireNotNull(detailInitial.catalogDetailLogs).totalCount)
            assertTrue(detailInitial.catalogDetailLogs.hasMore)

            // Edit notes AND date on a 2025 log far outside the top-12 recentDiaryEntries window
            val targetLogId = "song-play-520"
            env.repository.catalog.updateConsumptionLog(
                logId = targetLogId,
                notes = "Updated deep note",
                tsIso = "2025-04-28T23:59:00.000Z",
            )
            val detailAfterEdit =
                viewModel.uiState.first { st ->
                    st.catalogDetailLogs?.logs?.any {
                        it.id == targetLogId &&
                            it.notes == "Updated deep note" &&
                            it.ts == "2025-04-28T23:59:00.000Z"
                    } == true
                }
            assertNotNull(detailAfterEdit.catalogDetailLogs)

            // Paginate detail sheet past 500 all the way to 520
            repeat(10) { step ->
                val targetSize = minOf(520, DEFAULT_ENTRY_DETAIL_LOGS_LIMIT * (step + 2))
                viewModel.dispatch(MainAction.LoadMoreCatalogDetailLogs)
                val next =
                    viewModel.uiState.first { it.catalogDetailLogs?.logs?.size == targetSize }
                assertEquals(targetSize < 520, requireNotNull(next.catalogDetailLogs).hasMore)
            }

            uiJob.cancel()
        }

    @Test
    fun `deleting last log of selected year auto-clears year filter`() = runTest {
        seedScaleFixture(songPlayCount = 1, bookCount = 5)
        val uiJob = launch(UnconfinedTestDispatcher()) { viewModel.uiState.collect {} }

        viewModel.dispatch(MainAction.SetDiaryStatusFilter(null))
        viewModel.dispatch(MainAction.SetDiaryKindFilter(DiaryKindFilter.All))
        viewModel.dispatch(MainAction.SetDiaryYearFilter("2025"))
        viewModel.uiState.first {
            it.diary.statusFilter == null &&
                it.diary.kindFilter == DiaryKindFilter.All &&
                it.diary.yearFilter == "2025" &&
                it.diary.entries.size == 1
        }

        env.repository.catalog.deleteConsumptionLog("song-play-1")
        val cleared =
            viewModel.uiState.first { it.diary.yearFilter == null && it.diary.entries.size == 5 }
        assertNull(cleared.diary.yearFilter)
        assertEquals(listOf("2026"), cleared.diary.availableYears)

        uiJob.cancel()
    }

    @Test
    fun `workspace search scopes FTS to title creators notes and escapes SQL wildcards`() =
        runTest {
            seedScaleFixture()
            val dao = env.database.dao()
            dao.upsertEntry(
                EntryEntity(
                    id = "book-special",
                    kind = "book",
                    title = "100% Pure_Escapes",
                    creatorsJson = """["O'Connor"]""",
                    url = "https://unique-domain-should-not-match.example/x",
                    createdAt = "2026-07-01T00:00:00.000Z",
                    updatedAt = "2026-07-01T00:00:00.000Z",
                )
            )
            dao.insertConsumptionLogIgnore(
                ConsumptionLogEntity(
                    id = "special-log",
                    entryId = "book-special",
                    ts = "2026-07-02T10:00:00.000Z",
                    status = "consumed",
                    notes = "50% off_line \\note",
                    createdAt = "2026-07-02T10:00:00.000Z",
                )
            )

            // Works even before repository.snapshot has emitted (tier fallback)
            val byTitle = env.repository.searchWorkspace("Weird Fishes")
            assertEquals(50, byTitle.matchedDiaryLogs.size)
            assertTrue(byTitle.matchedDiaryLogs.all { it.entryId == REPEAT_SONG_ID })

            // URL-only tokens must not flood diary matches
            val byUrlOnly = env.repository.searchWorkspace("unique-domain-should-not-match")
            assertTrue(byUrlOnly.matchedDiaryLogs.isEmpty())

            // Literal wildcard chars in notes must match only the target log
            val byEscapedNote = env.repository.searchWorkspace("50% off_line \\note")
            assertEquals(listOf("special-log"), byEscapedNote.matchedDiaryLogs.map { it.id })

            val uiJob = launch(UnconfinedTestDispatcher()) { viewModel.uiState.collect {} }
            // Default diary filters to Finished (CONSUMED) and hides 70 song plays ->
            // 70 finished books + 1 special finished book = 71 (10 skipped books excluded)
            viewModel.uiState.first { it.diary.totalFilteredCount == 71 }
            val freshBookId =
                env.repository.catalog.logEntry(
                    UpsertCatalogEntryInput(kind = EntryKind.BOOK, title = "Piranesi"),
                    ConsumptionStatus.CONSUMED,
                )
            val afterNewLog =
                viewModel.uiState.first { st ->
                    st.diary.entries.any { it.entryId == freshBookId }
                }
            assertEquals(72, afterNewLog.diary.totalFilteredCount)
            uiJob.cancel()
        }

    @Test
    fun `peer sync merges more than 450 logs across chunk boundary and normalizes UTC timestamps`() =
        runTest {
            env.repository.catalog.ensureSystemSeeded()
            val dao = env.database.dao()
            val incomingEntries =
                (1..500).map { i ->
                    SyncEntryRecordDto(
                        id = "sync-song-$i",
                        kind = "song",
                        title = "Track $i",
                        createdAt = "2025-12-01T00:00:00Z",
                        updatedAt = "2025-12-01T00:00:00Z",
                    )
                }
            val incomingLogs =
                (1..500).map { i ->
                    val ts =
                        if (i == 1) {
                            "2025-12-31T23:30:00-05:00"
                        } else {
                            "2025-11-%02dT10:%02d:00Z".format((i - 1) % 28 + 1, i % 60)
                        }
                    SyncConsumptionLogDto(
                        id = "sync-log-$i",
                        entryId = "sync-song-$i",
                        ts = ts,
                        status = "consumed",
                        createdAt = ts,
                    )
                }
            reconcilePeerSyncResponse(
                dao = dao,
                response =
                    PeerSyncResponseDto(
                        serverPeerId = "server-test",
                        syncTimestamp = "2026-01-02T00:00:00Z",
                        entries = incomingEntries,
                        consumptionLog = incomingLogs,
                    ),
                catalogStore = env.repository.catalog,
            )

            assertEquals(500, dao.countDiaryLogs())
            val tzRow = dao.getDiaryLogsForEntry("sync-song-1", limit = 5, offset = 0).single()
            assertEquals("2026-01-01T04:30:00Z", tzRow.ts)
            val years = dao.getDiaryYearCounts().associate { it.year to it.count }
            assertEquals(1, years["2026"])
            assertEquals(499, years["2025"])
        }

    private suspend fun seedScaleFixture(songPlayCount: Int = 70, bookCount: Int = 80) {
        val dao = env.database.dao()
        env.repository.catalog.ensureSystemSeeded()
        dao.upsertEntries(buildScaleFixtureEntries(bookCount))
        dao.insertConsumptionLogsIgnore(buildScaleFixtureLogs(songPlayCount, bookCount))
    }

    private fun buildScaleFixtureEntries(bookCount: Int): List<EntryEntity> =
        listOf(
            EntryEntity(
                id = REPEAT_SONG_ID,
                kind = "song",
                title = "Weird Fishes",
                creatorsJson = """["Radiohead"]""",
                url = "https://open.spotify.com/track/4wajJ1o7jWIg62YqpkHC7S",
                releaseYear = 2007,
                createdAt = "2025-01-01T00:00:00.000Z",
                updatedAt = "2025-01-01T00:00:00.000Z",
            ),
            EntryEntity(
                id = HIDDEN_ARTIST_ID,
                kind = "artist",
                title = "Radiohead",
                createdAt = "2025-01-01T00:00:00.000Z",
                updatedAt = "2025-01-01T00:00:00.000Z",
            ),
        ) +
            (1..bookCount).map { i ->
                EntryEntity(
                    id = "book-$i",
                    kind = "book",
                    title = "Book $i",
                    creatorsJson = """["Author $i"]""",
                    url = "https://example.com/book/$i",
                    createdAt = "2026-01-01T00:00:00.000Z",
                    updatedAt = "2026-01-01T00:00:00.000Z",
                )
            }

    private fun buildScaleFixtureLogs(
        songPlayCount: Int,
        bookCount: Int,
    ): List<ConsumptionLogEntity> {
        val artistLog =
            ConsumptionLogEntity(
                id = "artist-auto-log",
                entryId = HIDDEN_ARTIST_ID,
                ts = "2026-08-01T00:00:00.000Z",
                status = "consumed",
                createdAt = "2026-08-01T00:00:00.000Z",
            )
        val songLogs =
            (1..songPlayCount).map { i ->
                val day = (i - 1) % 28 + 1
                val month = if (i <= songPlayCount / 2) 3 else 4
                val hour = i / 60 % 24
                val ts = "2025-%02d-%02dT%02d:%02d:00.000Z".format(month, day, hour, i % 60)
                ConsumptionLogEntity(
                    id = "song-play-$i",
                    entryId = REPEAT_SONG_ID,
                    ts = ts,
                    status = "consumed",
                    notes = if (i == songPlayCount) "Late night favorite" else null,
                    createdAt = ts,
                )
            }
        val bookLogs =
            (1..bookCount).map { i ->
                val day = (i - 1) % 28 + 1
                val month = if (i <= bookCount / 2) 5 else 6
                val status = if (i <= 10) "abandoned" else "consumed"
                val ts = "2026-%02d-%02dT12:%02d:00.000Z".format(month, day, i % 60)
                ConsumptionLogEntity(
                    id = "book-log-$i",
                    entryId = "book-$i",
                    ts = ts,
                    status = status,
                    createdAt = ts,
                )
            }
        return listOf(artistLog) + songLogs + bookLogs
    }

    @Test
    fun `song only diary keeps hasAnyEntries and hasSongLogs under empty status filters`() =
        runTest {
            seedScaleFixture(songPlayCount = 40, bookCount = 0)
            val uiJob = launch(UnconfinedTestDispatcher()) { viewModel.uiState.collect {} }

            val defaultView = viewModel.uiState.first { it.diary.isLoaded }
            assertEquals(0, defaultView.diary.totalFilteredCount)
            assertTrue(defaultView.diary.hasAnyEntries)
            assertTrue(defaultView.diary.hasSongLogs)

            viewModel.dispatch(MainAction.SetDiaryStatusFilter(ConsumptionStatus.STARTED))
            val startedView =
                viewModel.uiState.first {
                    it.diary.isLoaded && it.diary.statusFilter == ConsumptionStatus.STARTED
                }
            assertEquals(0, startedView.diary.totalFilteredCount)
            assertTrue(startedView.diary.hasAnyEntries)
            assertTrue(startedView.diary.hasSongLogs)

            uiJob.cancel()
        }

    private companion object {
        const val REPEAT_SONG_ID = "song-repeat"
        const val HIDDEN_ARTIST_ID = "artist-hidden"
    }
}

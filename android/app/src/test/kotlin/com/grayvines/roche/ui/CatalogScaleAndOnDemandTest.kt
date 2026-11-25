package com.grayvines.roche.ui

import androidx.lifecycle.viewModelScope
import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.UP_NEXT_COLLECTION_ID
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.data.TestRocheEnv
import com.grayvines.roche.data.boards.BoardDedupKey
import com.grayvines.roche.data.boards.BoardItemView
import com.grayvines.roche.data.boards.loadScopedBoardDedupLookup
import com.grayvines.roche.data.boards.resolveLinkedEntryId
import com.grayvines.roche.data.catalog.CatalogEntryItem
import com.grayvines.roche.data.catalog.DEFAULT_COLLECTION_PAGE_SIZE
import com.grayvines.roche.data.catalog.EntryIdentifierDto
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.UpsertCatalogEntryInput
import com.grayvines.roche.data.catalog.computeQuantilesWithUncompared
import com.grayvines.roche.data.catalog.splitComparedBands
import com.grayvines.roche.data.local.BoardEntity
import com.grayvines.roche.data.local.BoardItemEntity
import com.grayvines.roche.data.local.ConsumptionLogEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryIdentifierEntity
import com.grayvines.roche.data.local.EntryStateEntity
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogScaleAndOnDemandTest {
    @TempDir lateinit var tempDir: File

    private lateinit var env: TestRocheEnv
    private lateinit var viewModel: MainViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        env = TestRocheEnv(tempDir, "catalog_scale_on_demand_test.preferences_pb")
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
    fun `computeQuantilesWithUncompared matches brute-force rescaleToQuantiles bit-for-bit`() {
        val comparedScores =
            mapOf(
                "s1" to EpRankingEngine.Score(mean = 1.8, variance = 0.2),
                "s2" to EpRankingEngine.Score(mean = 0.7, variance = 0.3),
                "s3" to EpRankingEngine.Score(mean = 0.0, variance = 0.4),
                "s4" to EpRankingEngine.Score(mean = -0.6, variance = 0.3),
                "s5" to EpRankingEngine.Score(mean = -1.5, variance = 0.2),
            )
        val uncomparedCount = 645
        val virtual = computeQuantilesWithUncompared(comparedScores, uncomparedCount)

        val bruteScores =
            LinkedHashMap<String, EpRankingEngine.Score>(comparedScores.size + uncomparedCount)
        bruteScores.putAll(comparedScores)
        for (i in 1..uncomparedCount) {
            bruteScores["u-$i"] = EpRankingEngine.Score(mean = 0.0, variance = 1.0)
        }
        val bruteBuckets = EpRankingEngine.rescaleToQuantiles(bruteScores)

        for ((id, expectedBucket) in bruteBuckets) {
            val actualBucket = virtual.bucketByComparedId[id] ?: virtual.defaultBucket
            assertEquals(expectedBucket, actualBucket, "Mismatch for $id")
        }
        assertEquals(650, virtual.tierCounts.values.sum())
    }

    @Test
    fun `large dynamic list paginates via 3-band SQL filters tiers and settles with neighbors`() =
        runTest {
            seedLargeSongsCatalog(songCount = 620)
            val catalog = env.repository.catalog
            val songsSpec = env.database.dao().getCollectionSpecBySlug("songs")!!
            val songsId = songsSpec.collectionId

            val snap = env.repository.snapshot.first()
            assertEquals(0, env.repository.unifiedSnapshotLoader.entriesTableLoads)
            val songsSummary = snap.catalog.lists.first { it.collectionId == songsId }
            assertEquals(620, songsSummary.entryCount)
            assertTrue(songsSummary.previewThumbnails.size <= 4)
            assertTrue(songsSummary.previewThumbnails.isNotEmpty())

            // Open Songs list via ViewModel and paginate all the way past 500 to 620
            val uiJob = launch(UnconfinedTestDispatcher()) { viewModel.uiState.collect {} }
            viewModel.dispatch(MainAction.OpenCompanionSection(CompanionSubTab.LISTS, songsId))
            val page1 =
                viewModel.uiState.first {
                    it.activeCollection.collectionId == songsId &&
                        it.activeCollection.isLoaded &&
                        it.activeCollection.entries.size == DEFAULT_COLLECTION_PAGE_SIZE
                }
            assertEquals(620, page1.activeCollection.totalCount)
            assertEquals(620, page1.activeCollection.tierCounts.values.sum())
            assertTrue(page1.activeCollection.hasMore)
            assertEquals("song-001", page1.activeCollection.entries.first().entry.id)

            repeat(6) { step ->
                val expected = minOf(620, DEFAULT_COLLECTION_PAGE_SIZE * (step + 2))
                viewModel.dispatch(MainAction.LoadMoreCatalogCollection)
                val next = viewModel.uiState.first { it.activeCollection.entries.size == expected }
                assertEquals(expected < 620, next.activeCollection.hasMore)
            }
            val fullUiEntries = viewModel.uiState.value.activeCollection.entries
            assertEquals((1..620).toList(), fullUiEntries.map { it.rank })
            assertEquals(
                catalog.loadCollectionEntries(songsId).map { it.entry.id },
                fullUiEntries.map { it.entry.id },
            )

            // Filter by S tier on 620-item dynamic list
            viewModel.dispatch(MainAction.SetCatalogCollectionTierFilter(songsId, 'S'))
            val sTier =
                viewModel.uiState.first {
                    it.activeCollection.selectedTier == 'S' && it.activeCollection.isLoaded
                }
            assertTrue(sTier.activeCollection.entries.isNotEmpty())
            assertTrue(sTier.activeCollection.entries.all { it.tier == 'S' })

            // Sampled cluster selection + settlement with neighbors on >500-item list
            val cluster = catalog.selectNextRankCluster(songsId, focusEntryId = "song-001")
            assertTrue(cluster.size >= EpRankingEngine.MIN_K)
            assertTrue(cluster.any { it.entryId == "song-001" })

            val topSettlement = catalog.resolveEntrySettlement("song-001", songsId)
            assertNotNull(topSettlement)
            assertEquals(1, topSettlement!!.rank)
            assertEquals(620, topSettlement.totalCount)
            assertEquals(2, topSettlement.neighbors.size)

            val midSettlement = catalog.resolveEntrySettlement("song-300", songsId)
            assertNotNull(midSettlement)
            assertEquals(620, midSettlement!!.totalCount)
            assertEquals(3, midSettlement.neighbors.size)
            assertEquals("song-300", midSettlement.neighbors[1].entry.id)

            uiJob.cancel()
        }

    @Test
    fun `returning to Lists overview clears stale collection tier filter`() = runTest {
        seedLargeSongsCatalog(songCount = 520)
        val songsId = env.database.dao().getCollectionSpecBySlug("songs")!!.collectionId
        val uiJob = launch(UnconfinedTestDispatcher()) { viewModel.uiState.collect {} }
        viewModel.dispatch(MainAction.OpenCompanionSection(CompanionSubTab.LISTS, songsId))
        viewModel.uiState.first {
            it.activeCollection.collectionId == songsId && it.activeCollection.isLoaded
        }
        viewModel.dispatch(MainAction.SetCatalogCollectionTierFilter(songsId, 'S'))
        viewModel.uiState.first { it.activeCollection.selectedTier == 'S' }
        viewModel.dispatch(MainAction.SelectCatalogCollection(null))
        viewModel.uiState.first { it.activeCollection.collectionId == null }
        viewModel.dispatch(MainAction.SelectCatalogCollection(songsId))
        val reopened =
            viewModel.uiState.first {
                it.activeCollection.collectionId == songsId && it.activeCollection.isLoaded
            }
        assertEquals(null, reopened.activeCollection.selectedTier)
        uiJob.cancel()
    }

    @Test
    fun `unicode titles and near-zero tied scores stay consistent in large dynamic lists`() =
        runTest {
            seedLargeSongsCatalog(songCount = 520)
            val dao = env.database.dao()
            val songsId = dao.getCollectionSpecBySlug("songs")!!.collectionId
            dao.upsertEntry(
                EntryEntity(
                    id = "song-ete",
                    kind = "song",
                    title = "Été",
                    creatorsJson = """["French Artist"]""",
                    createdAt = "2026-01-01T00:00:00Z",
                    updatedAt = "2026-01-01T00:00:00Z",
                )
            )
            dao.insertConsumptionLogsIgnore(
                listOf(
                    ConsumptionLogEntity(
                        id = "play-ete",
                        entryId = "song-ete",
                        ts = "2026-05-20T10:00:00Z",
                        status = "consumed",
                        createdAt = "2026-05-20T10:00:00Z",
                    )
                )
            )
            val settlement =
                checkNotNull(env.repository.catalog.resolveEntrySettlement("song-ete", songsId))
            assertTrue(settlement.rank in 1..settlement.totalCount)
            assertEquals(1, settlement.neighbors.count { it.entry.id == "song-ete" })

            // Near-zero ascending tie-group parity (counterexample where descending diverged)
            val items =
                listOf("c00", "c01", "c02").map { id ->
                    CatalogEntryItem(
                        id = id,
                        kind = EntryKind.SONG,
                        title = id,
                        creators = emptyList(),
                        url = null,
                        publishedAt = null,
                        releaseYear = null,
                        summary = null,
                        thumbnailUrl = null,
                        identifiers = emptyList(),
                    )
                }
            val scores =
                mapOf(
                    "c00" to EpRankingEngine.Score(mean = 0.0, variance = 0.5),
                    "c01" to EpRankingEngine.Score(mean = 5.77e-6, variance = 0.5),
                    "c02" to EpRankingEngine.Score(mean = -9.30e-6, variance = 0.5),
                )
            val quantiles = computeQuantilesWithUncompared(scores, uncomparedCount = 10)
            val bands = splitComparedBands(items, scores, hasUncomparedEntries = true)
            for (zeroItem in bands.bandZeroCompared) {
                assertEquals(quantiles.defaultBucket, quantiles.bucketByComparedId[zeroItem.id])
            }
        }

    @Test
    fun `FTS catalog search handles NOT operator prioritizes prefix matches and stabilizes kind counts`() =
        runTest {
            val catalog = env.repository.catalog
            catalog.ensureSystemSeeded()
            val dao = env.database.dao()
            val extra =
                (1..80).map { i ->
                    EntryEntity(
                        id = "s-love-$i",
                        kind = "song",
                        title = "A %03d Love Ballad".format(i),
                        creatorsJson = """["Band"]""",
                        createdAt = "2026-01-01T00:00:00Z",
                        updatedAt = "2026-01-01T00:00:00Z",
                    )
                } +
                    listOf(
                        EntryEntity(
                            id = "s-exact-love",
                            kind = "song",
                            title = "Love",
                            creatorsJson = """["Kendrick"]""",
                            createdAt = "2026-01-01T00:00:00Z",
                            updatedAt = "2026-01-01T00:00:00Z",
                        ),
                        EntryEntity(
                            id = "s-not-like-us",
                            kind = "song",
                            title = "Not Like Us",
                            creatorsJson = """["Kendrick"]""",
                            createdAt = "2026-01-01T00:00:00Z",
                            updatedAt = "2026-01-01T00:00:00Z",
                        ),
                    )
            dao.upsertEntries(extra)

            val loveRes = catalog.searchCatalogEntries("Love", limit = 24)
            assertEquals("s-exact-love", loveRes.matches.first().entry.id)

            val notRes = catalog.searchCatalogEntries("NOT kendrick", limit = 24)
            assertTrue(notRes.matches.any { it.entry.id == "s-not-like-us" })

            val kindPage1 = catalog.searchCatalogEntries("song", limit = 24)
            val kindPage2 = catalog.searchCatalogEntries("song", limit = 48)
            assertEquals(82, kindPage1.totalCount)
            assertEquals(82, kindPage2.totalCount)
        }

    @Test
    fun `scoped board dedup resolves reverse-merged aliases want badges and preserves ephemeral isFinished`() =
        runTest {
            val canonId = "art-canon"
            seedMergedBoardAliasScenario(canonId = canonId, aliasId = "art-alias")
            val lookup =
                loadScopedBoardDedupLookup(
                    env.database.dao(),
                    listOf(BoardDedupKey("hacker_news", "https://example.com/canon", "99")),
                )
            assertEquals(
                canonId,
                lookup.resolveLinkedEntryId("hacker_news", "https://example.com/canon", "99"),
            )
            assertTrue(canonId in lookup.inQueueEntryIds)
            assertTrue(canonId in lookup.finishedEntryIds)
            // Legacy UP_NEXT_COLLECTION_ID alone must NOT show up as an active want badge
            env.repository.catalog.removeCollectionEntry(WANT_TO_READ_COLLECTION_ID, canonId)
            env.repository.catalog.addCollectionEntry(UP_NEXT_COLLECTION_ID, canonId)
            val afterLegacyOnly =
                loadScopedBoardDedupLookup(
                    env.database.dao(),
                    listOf(BoardDedupKey("hacker_news", "https://example.com/canon", "99")),
                )
            assertFalse(canonId in afterLegacyOnly.inQueueEntryIds)

            val snapshot = env.repository.snapshot.first()
            val ephemeral =
                BoardItemView(
                    boardId = "board-hn",
                    externalId = "99",
                    rank = 1,
                    title = "Canonical",
                    url = "https://example.com/canon",
                    discussionUrl = null,
                    author = null,
                    domain = "example.com",
                    publishedAt = null,
                    score = null,
                    commentCount = null,
                    summary = null,
                    selfTextHtml = null,
                    fetchedAt = "2026-01-01T00:00:00Z",
                    linkedEntryId = canonId,
                    isFinished = true,
                )
            val reconciled =
                reconcileBoardPaginationMap(
                    mapOf("board-hn" to BoardPaginationState(ephemeralItems = listOf(ephemeral))),
                    snapshot,
                )
            assertTrue(reconciled["board-hn"]!!.ephemeralItems.single().isFinished)
        }

    private suspend fun seedMergedBoardAliasScenario(canonId: String, aliasId: String) {
        val catalog = env.repository.catalog
        catalog.ensureSystemSeeded()
        val dao = env.database.dao()
        dao.upsertEntries(
            listOf(
                EntryEntity(
                    id = canonId,
                    kind = "article",
                    title = "Canonical",
                    url = "https://example.com/canon",
                    createdAt = "2026-01-01T00:00:00Z",
                    updatedAt = "2026-01-01T00:00:00Z",
                ),
                EntryEntity(
                    id = aliasId,
                    kind = "article",
                    title = "Alias",
                    mergedIntoId = canonId,
                    createdAt = "2026-01-01T00:00:00Z",
                    updatedAt = "2026-01-01T00:00:00Z",
                ),
            )
        )
        dao.insertEntryIdentifierIgnore(
            EntryIdentifierEntity("url", "https://example.com/canon", canonId)
        )
        catalog.addCollectionEntry(WANT_TO_READ_COLLECTION_ID, aliasId)
        dao.insertConsumptionLogsIgnore(
            listOf(
                ConsumptionLogEntity(
                    id = "log-alias",
                    entryId = aliasId,
                    ts = "2025-01-01T00:00:00Z",
                    status = "consumed",
                    createdAt = "2025-01-01T00:00:00Z",
                )
            )
        )
    }

    @Test
    fun `edit entry loads full identifiers and untruncated summary for inbox items`() = runTest {
        val dao = env.database.dao()
        env.repository.catalog.ensureSystemSeeded()
        val longSummary = "A".repeat(1_500)
        dao.upsertEntry(
            EntryEntity(
                id = "paper-long",
                kind = "paper",
                title = "Long Abstract Paper",
                url = "https://arxiv.org/abs/2601.12345",
                summary = longSummary,
                createdAt = "2026-01-01T00:00:00Z",
                updatedAt = "2026-01-01T00:00:00Z",
            )
        )
        dao.upsertEntryState(
            EntryStateEntity(
                entryId = "paper-long",
                inboxState = "unread",
                updatedAt = "2026-01-01T00:00:00Z",
            )
        )
        dao.insertEntryIdentifiersIgnore(
            listOf(
                EntryIdentifierEntity("doi", "10.1000/test", "paper-long"),
                EntryIdentifierEntity("arxiv", "2601.12345", "paper-long"),
            )
        )
        val uiJob = launch(UnconfinedTestDispatcher()) { viewModel.uiState.collect {} }
        viewModel.uiState.first { "paper-long" in it.entryById }
        viewModel.dispatch(MainAction.OpenEditEntrySheet("paper-long"))
        val hydrated =
            viewModel.uiState
                .first { it.editingCatalogEntry?.id == "paper-long" }
                .editingCatalogEntry!!
        assertEquals(1_500, hydrated.summary?.length)
        assertTrue(hydrated.identifiers.any { it.scheme == "doi" && it.value == "10.1000/test" })
        assertTrue(hydrated.identifiers.any { it.scheme == "arxiv" && it.value == "2601.12345" })
        uiJob.cancel()
    }

    @Test
    fun `on-demand detail sheet edit sheet FTS catalog search and scoped board dedup work without entriesById`() =
        runTest {
            val catalog = env.repository.catalog
            catalog.ensureSystemSeeded()
            val (artistId, albumId, articleId) = seedDetailAndBoardScenario()
            val uiJob = launch(UnconfinedTestDispatcher()) { viewModel.uiState.collect {} }

            val withBoard =
                viewModel.uiState.first { st ->
                    st.boardItemsByBoardId["board-hn"]?.singleOrNull()?.linkedEntryId == articleId
                }
            val story = withBoard.boardItemsByBoardId["board-hn"]!!.single()
            assertTrue(story.isStarred)
            assertEquals(2026, withBoard.entryById[articleId]?.releaseYear)

            viewModel.dispatch(MainAction.OpenCatalogDetailSheet(albumId))
            val albumDetail =
                viewModel.uiState.first { it.catalogDetail?.entry?.id == albumId }.catalogDetail!!
            assertTrue(albumDetail.isStarred)
            assertEquals(artistId, albumDetail.matchingArtistId)
            assertTrue(albumDetail.appearances.any { it.title == "Albums" })

            viewModel.dispatch(MainAction.OpenCatalogDetailSheet(artistId))
            val artistDetail =
                viewModel.uiState.first { it.catalogDetail?.entry?.id == artistId }.catalogDetail!!
            assertEquals("In Rainbows", artistDetail.previousEntryTitle)
            assertEquals(listOf(albumId), artistDetail.artistLibraryAlbums.map { it.entry.id })

            viewModel.dispatch(MainAction.OpenEditEntrySheet(albumId))
            val editing =
                viewModel.uiState
                    .first { it.editingCatalogEntry?.id == albumId }
                    .editingCatalogEntry!!
            assertTrue(editing.identifiers.any { it.scheme == "isbn" })

            val searchRes = env.repository.searchWorkspace("Radiohead")
            assertTrue(searchRes.matchedCatalogEntries.any { it.entry.id == albumId })
            assertTrue(searchRes.catalogTotalCount >= 2)
            assertEquals(0, env.repository.unifiedSnapshotLoader.entriesTableLoads)

            uiJob.cancel()
        }

    private suspend fun seedDetailAndBoardScenario(): Triple<String, String, String> {
        val catalog = env.repository.catalog
        val artistId =
            catalog.upsertEntry(
                UpsertCatalogEntryInput(kind = EntryKind.ARTIST, title = "Radiohead")
            )
        val albumId =
            catalog.logEntry(
                UpsertCatalogEntryInput(
                    kind = EntryKind.ALBUM,
                    title = "In Rainbows",
                    creators = listOf("Radiohead"),
                    releaseYear = 2007,
                    identifiers = listOf(EntryIdentifierDto("isbn", "9780000000001")),
                ),
                com.grayvines.roche.data.catalog.ConsumptionStatus.CONSUMED,
            )
        catalog.addCollectionEntry(STARRED_COLLECTION_ID, albumId)
        val articleId = seedHnBoardArticleScenario()
        return Triple(artistId, albumId, articleId)
    }

    private suspend fun seedHnBoardArticleScenario(): String {
        val catalog = env.repository.catalog
        val dao = env.database.dao()
        val articleId = "art-hn-scoped"
        dao.upsertEntry(
            EntryEntity(
                id = articleId,
                kind = "article",
                title = "SQLite Indexing",
                url = "https://example.com/sqlite-indexing",
                releaseYear = 2026,
                createdAt = "2026-11-20T00:00:00Z",
                updatedAt = "2026-11-20T00:00:00Z",
            )
        )
        dao.upsertEntryState(
            EntryStateEntity(
                entryId = articleId,
                inboxState = "unread",
                updatedAt = "2026-11-20T00:00:00Z",
            )
        )
        dao.insertEntryIdentifierIgnore(
            EntryIdentifierEntity("url", "https://example.com/sqlite-indexing", articleId)
        )
        catalog.addCollectionEntry(STARRED_COLLECTION_ID, articleId)
        dao.upsertBoard(
            BoardEntity(
                id = "board-hn",
                adapterType = "hacker_news",
                sourceKey = "top",
                sourceUrl = "https://news.ycombinator.com",
                title = "Hacker News",
                createdAt = "2026-11-20T00:00:00Z",
                updatedAt = "2026-11-20T00:00:00Z",
            )
        )
        dao.insertBoardItems(
            listOf(
                BoardItemEntity(
                    boardId = "board-hn",
                    externalId = "4242",
                    rank = 1,
                    title = "SQLite Indexing",
                    url = "https://example.com/sqlite-indexing",
                    fetchedAt = "2026-11-20T00:00:00Z",
                )
            )
        )
        return articleId
    }

    private suspend fun seedLargeSongsCatalog(songCount: Int) {
        val dao = env.database.dao()
        env.repository.catalog.ensureSystemSeeded()
        val entries =
            (1..songCount).map { i ->
                val padded = "%03d".format(i)
                EntryEntity(
                    id = "song-$padded",
                    kind = "song",
                    title = "Track $padded",
                    creatorsJson = """["Artist $padded"]""",
                    thumbnailUrl = if (i <= 10) "https://img.example.com/$padded.jpg" else null,
                    createdAt = "2026-01-01T00:00:00Z",
                    updatedAt = "2026-01-01T00:00:00Z",
                )
            }
        val logs =
            (1..songCount).map { i ->
                val padded = "%03d".format(i)
                ConsumptionLogEntity(
                    id = "play-$padded",
                    entryId = "song-$padded",
                    ts = "2026-05-%02dT10:%02d:00Z".format((i - 1) % 28 + 1, i % 60),
                    status = "consumed",
                    createdAt = "2026-05-01T00:00:00Z",
                )
            }
        dao.upsertEntries(entries)
        dao.insertConsumptionLogsIgnore(logs)
        // Rank song-001 > song-002 > song-003 and song-004 > song-005 to populate above/below bands
        env.repository.catalog.recordComparison(
            listOf(listOf("song-001"), listOf("song-002"), listOf("song-003"))
        )
        env.repository.catalog.recordComparison(
            listOf(listOf("song-002"), listOf("song-004"), listOf("song-005"))
        )
    }
}

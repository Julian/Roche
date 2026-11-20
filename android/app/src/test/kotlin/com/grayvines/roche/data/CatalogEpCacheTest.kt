package com.grayvines.roche.data

import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.data.catalog.CatalogSnapshot
import com.grayvines.roche.data.catalog.CatalogStore
import com.grayvines.roche.data.catalog.CollectionRole
import com.grayvines.roche.data.catalog.ConsumptionStatus
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.UpsertCatalogEntryInput
import com.grayvines.roche.data.catalog.buildCatalogSnapshot
import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.EntryContentEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryStateEntity
import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.upsertFeed
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.ranking.EpRankingEngine
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CatalogEpCacheTest {
    @TempDir lateinit var tempDir: File

    private lateinit var testEnv: TestRocheEnv
    private val dao: RocheDao
        get() = testEnv.database.dao()

    private val repository: RocheRepository
        get() = testEnv.repository

    private val catalog: CatalogStore
        get() = testEnv.repository.catalog

    @BeforeEach
    fun setUp() {
        testEnv = TestRocheEnv(tempDir, "ep_cache_test.preferences_pb")
    }

    @AfterEach
    fun tearDown() {
        testEnv.close()
    }

    @Test
    fun `unrelated catalog writes reuse cached global and local EP scores with zero recomputes`() =
        runTest {
            val fixture = seedEpCacheFixture(catalog)
            catalog.snapshot.first()
            val baseline = catalog.epCacheStats
            assertEquals(1, baseline.globalRecomputes)
            assertEquals(2, baseline.localRecomputes)

            // 1. Log consumption in diary
            catalog.logEntry(
                UpsertCatalogEntryInput(kind = EntryKind.BOOK, title = "Roadside Picnic"),
                ConsumptionStatus.CONSUMED,
                notes = "Finished",
            )
            catalog.snapshot.first()

            // 2. Toggle and advance a generator draw
            val initialDraw =
                catalog.setCollectionGeneratorEnabled(fixture.albumListId, enabled = true)
            assertNotNull(initialDraw)
            catalog.finishActiveDraw(fixture.albumListId, notes = "Great listen")
            catalog.snapshot.first()

            // 3. Edit entry metadata
            catalog.upsertEntry(
                UpsertCatalogEntryInput(
                    id = fixture.movie1,
                    kind = EntryKind.MOVIE,
                    title = "Solaris Remastered",
                    summary = "Updated synopsis",
                )
            )
            catalog.snapshot.first()

            // 4. Add an entry to a list
            val extraAlbum =
                catalog.upsertEntry(
                    UpsertCatalogEntryInput(kind = EntryKind.ALBUM, title = "Vespertine")
                )
            catalog.addCollectionEntry(fixture.albumListId, extraAlbum)
            catalog.snapshot.first()

            val after = catalog.epCacheStats
            assertEquals(baseline.globalRecomputes, after.globalRecomputes)
            assertEquals(baseline.localRecomputes, after.localRecomputes)
            assertTrue(after.globalHits > baseline.globalHits)
            assertTrue(after.localHits > baseline.localHits)
        }

    @Test
    fun `local want comparison recomputes only that want queue and evicts deleted scopes`() =
        runTest {
            val fixture = seedEpCacheFixture(catalog)
            catalog.snapshot.first()
            val before = catalog.epCacheStats

            val book3 =
                catalog.wantEntry(
                    UpsertCatalogEntryInput(kind = EntryKind.BOOK, title = "Neuromancer")
                )
            catalog.recordComparison(
                ordering = listOf(listOf(fixture.book2), listOf(book3)),
                scopeCollectionId = fixture.wantBooksId,
            )
            val warmSnap = catalog.snapshot.first()
            val after = catalog.epCacheStats

            assertEquals(before.globalRecomputes, after.globalRecomputes)
            assertEquals(before.localRecomputes + 1, after.localRecomputes)
            assertEquals(
                (before.localRecomputesByScope[fixture.wantBooksId] ?: 0) + 1,
                after.localRecomputesByScope[fixture.wantBooksId],
            )
            assertEquals(
                before.localRecomputesByScope[fixture.wantGamesId],
                after.localRecomputesByScope[fixture.wantGamesId],
            )
            val coldSnap = buildCatalogSnapshot(catalog.loadRawCatalogTables(), epScoreCache = null)
            assertRankedCollectionParity(warmSnap, coldSnap, fixture.wantBooksId)
            assertRankedCollectionParity(warmSnap, coldSnap, fixture.wantGamesId)

            dao.deleteCollectionSpec(fixture.wantGamesId)
            catalog.snapshot.first()
            assertEquals(after.cachedScopeCount - 1, catalog.epCacheStats.cachedScopeCount)
        }

    @Test
    fun `global comparison recomputes global EP with exact 1e-12 parity against cold engine`() =
        runTest {
            val fixture = seedEpCacheFixture(catalog)
            val movie4 =
                catalog.logEntry(
                    UpsertCatalogEntryInput(kind = EntryKind.MOVIE, title = "Mirror"),
                    ConsumptionStatus.CONSUMED,
                )
            catalog.snapshot.first()
            val before = catalog.epCacheStats

            catalog.recordComparison(
                ordering = listOf(listOf(fixture.movie2), listOf(fixture.movie3, movie4))
            )
            val warmSnap = catalog.snapshot.first()
            val after = catalog.epCacheStats
            assertEquals(before.globalRecomputes + 1, after.globalRecomputes)
            assertEquals(before.localRecomputes, after.localRecomputes)

            val coldSnap = buildCatalogSnapshot(catalog.loadRawCatalogTables(), epScoreCache = null)
            val moviesSpecId = dao.getCollectionSpecBySlug(MOVIES_SLUG)!!.collectionId
            assertRankedCollectionParity(warmSnap, coldSnap, moviesSpecId)

            val directOrderings =
                listOf(
                    listOf(listOf(fixture.movie1), listOf(fixture.movie2)),
                    listOf(listOf(fixture.movie2), listOf(fixture.movie3, movie4).sorted()),
                )
            val directScores =
                EpRankingEngine.inferScores(
                    listOf(fixture.movie1, fixture.movie2, fixture.movie3, movie4),
                    directOrderings,
                )
            for (ranked in warmSnap.entriesByCollectionId[moviesSpecId].orEmpty()) {
                val expected = directScores.getValue(ranked.entry.id)
                assertTrue(
                    abs(ranked.mean - expected.mean) <= EP_PARITY_TOLERANCE,
                    "mean mismatch for ${ranked.entry.title}: ${ranked.mean} vs ${expected.mean}",
                )
                assertTrue(
                    abs(ranked.variance - expected.variance) <= EP_PARITY_TOLERANCE,
                    "var mismatch for ${ranked.entry.title}: ${ranked.variance} vs ${expected.variance}",
                )
            }
        }

    @Test
    fun `merging an entry invalidates EP caches and matches cold recomputation`() = runTest {
        val canon =
            catalog.logEntry(
                UpsertCatalogEntryInput(kind = EntryKind.MOVIE, title = "Stalker"),
                ConsumptionStatus.CONSUMED,
            )
        val dup =
            catalog.logEntry(
                UpsertCatalogEntryInput(kind = EntryKind.MOVIE, title = "Stalker (Duplicate)"),
                ConsumptionStatus.CONSUMED,
            )
        val other =
            catalog.logEntry(
                UpsertCatalogEntryInput(kind = EntryKind.MOVIE, title = "Nostalghia"),
                ConsumptionStatus.CONSUMED,
            )
        catalog.recordComparison(listOf(listOf(dup), listOf(other)))
        catalog.snapshot.first()
        val before = catalog.epCacheStats

        val dupEntity = dao.getEntryById(dup)!!
        dao.upsertEntry(dupEntity.copy(mergedIntoId = canon))

        val warmSnap = catalog.snapshot.first()
        val coldSnap = buildCatalogSnapshot(catalog.loadRawCatalogTables(), epScoreCache = null)
        assertTrue(catalog.epCacheStats.globalRecomputes > before.globalRecomputes)

        val moviesSpecId = dao.getCollectionSpecBySlug(MOVIES_SLUG)!!.collectionId
        val ranked = warmSnap.entriesByCollectionId[moviesSpecId].orEmpty()
        assertFalse(ranked.any { it.entry.id == dup })
        val canonRanked = ranked.first { it.entry.id == canon }
        val otherRanked = ranked.first { it.entry.id == other }
        assertTrue(canonRanked.mean > otherRanked.mean)
        assertRankedCollectionParity(warmSnap, coldSnap, moviesSpecId)
    }

    @Test
    fun `rank cluster settlement and draw snapshot reuse cached snapshot and EP scores`() =
        runTest {
            val fixture = seedEpCacheFixture(catalog)
            val draw = catalog.setCollectionGeneratorEnabled(fixture.albumListId, enabled = true)!!
            catalog.snapshot.first()

            val buildsBefore = catalog.catalogSnapshotBuilds
            val reloadsBefore = catalog.catalogTableReloads
            val hitsBefore = catalog.catalogSnapshotCacheHits
            val epBefore = catalog.epCacheStats

            val cluster1 = catalog.selectNextRankCluster(MOVIES_SLUG)
            val cluster2 = catalog.selectNextRankCluster(MOVIES_SLUG)
            val settle1 = catalog.resolveEntrySettlement(fixture.movie1, MOVIES_SLUG)
            val settle2 = catalog.resolveEntrySettlement(fixture.movie1, MOVIES_SLUG)
            val draw1 = catalog.resolveDrawSnapshot(fixture.albumListId, draw.entryId)
            val draw2 = catalog.resolveDrawSnapshot(fixture.albumListId, draw.entryId)

            assertTrue(cluster1.size >= EpRankingEngine.MIN_K)
            assertTrue(cluster2.size >= EpRankingEngine.MIN_K)
            assertNotNull(settle1)
            assertEquals(settle1, settle2)
            assertEquals(draw.entryId, draw1?.entryId)
            assertEquals(draw1, draw2)
            assertEquals(buildsBefore, catalog.catalogSnapshotBuilds)
            assertEquals(reloadsBefore, catalog.catalogTableReloads)
            assertTrue(catalog.catalogSnapshotCacheHits >= hitsBefore + REPEATED_READ_CALLS)
            assertEquals(epBefore.globalRecomputes, catalog.epCacheStats.globalRecomputes)

            // Unrelated write reloads only changed tables and still hits EpScoreCache
            catalog.logEntry(
                UpsertCatalogEntryInput(kind = EntryKind.BOOK, title = "Dune"),
                ConsumptionStatus.CONSUMED,
            )
            assertNotNull(catalog.resolveEntrySettlement(fixture.movie1, MOVIES_SLUG))
            assertTrue(catalog.selectNextRankCluster(MOVIES_SLUG).size >= EpRankingEngine.MIN_K)
            assertEquals(buildsBefore + 1, catalog.catalogSnapshotBuilds)
            assertTrue(catalog.catalogTableReloads - reloadsBefore < ALL_CATALOG_TABLES_COUNT)
            assertEquals(epBefore.globalRecomputes, catalog.epCacheStats.globalRecomputes)
            assertEquals(epBefore.localRecomputes, catalog.epCacheStats.localRecomputes)
        }

    @Test
    fun `live reader projection aligns with Rust IS_LIVE_ITEM_SQL without entry_state`() = runTest {
        catalog.ensureSystemSeeded()
        val listId = catalog.createCustomList("Reading List", role = CollectionRole.LIST)
        val feedId = "feed-stateless"
        dao.upsertFeed(FeedRow(id = feedId, title = "Tech Feed", url = "https://ex.com/rss"))
        seedStatelessReaderFixture(dao, listId, feedId)

        val expectedIds =
            setOf(STATELESS_ART_ID, STATELESS_PAPER_ID, STATELESS_VIDEO_ID, STATELESS_POD_ID)
        assertEquals(expectedIds.size, dao.countItems())
        assertEquals(expectedIds, dao.getAllItems().map { it.id }.toSet())
        assertEquals(expectedIds, dao.observeItems().first().map { it.id }.toSet())

        val repoSnap = repository.snapshot.first()
        val entriesById = repoSnap.entries.associateBy { it.id }
        assertEquals(expectedIds, entriesById.keys)
        assertEquals(ContentType.ARTICLE, entriesById.getValue(STATELESS_ART_ID).contentType)
        assertEquals(ContentType.PDF, entriesById.getValue(STATELESS_PAPER_ID).contentType)
        assertEquals(ContentType.VIDEO, entriesById.getValue(STATELESS_VIDEO_ID).contentType)
        assertEquals(ContentType.AUDIO, entriesById.getValue(STATELESS_POD_ID).contentType)
        for (entry in entriesById.values) {
            assertFalse(entry.inFeedInbox)
            assertEquals(ItemStatus.UNREAD, entry.status)
            assertEquals(0.0, entry.readProgress)
            assertFalse(entry.isCachedOffline)
        }

        // Interacting with a stateless reader item persists entry_state cleanly
        repository.updateReadProgress(
            itemId = STATELESS_ART_ID,
            progress = PROGRESS_HALF,
            syncRemote = false,
        )
        val afterProgress = repository.snapshot.first().entries.first { it.id == STATELESS_ART_ID }
        assertEquals(PROGRESS_HALF, afterProgress.readProgress, EP_PARITY_TOLERANCE)
        assertEquals("none", dao.getEntryStateById(STATELESS_ART_ID)?.inboxState)
    }

    @Test
    fun `unified pipeline shares loaded tables avoids redundant rebuilds and matches DAO queries`() =
        runTest {
            testEnv.seedDefaultData()
            val fixture = seedEpCacheFixture(catalog)
            val snap1 = repository.snapshot.first()

            val expectedBase =
                buildSnapshot(
                    items = dao.observeItems().first(),
                    feeds = dao.observeFeeds().first(),
                    tags = dao.observeTags().first(),
                    itemTags = dao.observeItemTags().first(),
                )
            assertEquals(expectedBase.entries, snap1.entries)
            assertEquals(expectedBase.feeds, snap1.feeds)
            assertEquals(expectedBase.tags, snap1.tags)

            val loader = repository.unifiedSnapshotLoader
            val inboxRebuilds1 = loader.inboxProjectionRebuilds
            val entriesLoads1 = loader.entriesTableLoads

            // 1. Comparison-only write skips inbox rebuild and entries reload
            catalog.recordComparison(listOf(listOf(fixture.movie2), listOf(fixture.movie3)))
            val snap2 = repository.snapshot.first()
            assertEquals(snap1.catalog.comparisonCount + 1, snap2.catalog.comparisonCount)
            assertEquals(inboxRebuilds1, loader.inboxProjectionRebuilds)
            assertEquals(entriesLoads1, loader.entriesTableLoads)

            // 2. Entry-state-only write skips catalog rebuild, EP recompute, and entries reload
            val catalogBuildsBeforeState = catalog.catalogSnapshotBuilds
            val epGlobalBeforeState = catalog.epCacheStats.globalRecomputes
            val targetItemId = snap2.entries.first().id
            repository.updateReadProgress(
                itemId = targetItemId,
                progress = PROGRESS_SAMPLE,
                syncRemote = false,
            )
            val snap3 = repository.snapshot.first()
            val updatedEntry = snap3.entries.first { it.id == targetItemId }
            assertEquals(PROGRESS_SAMPLE, updatedEntry.readProgress, EP_PARITY_TOLERANCE)
            assertEquals(catalogBuildsBeforeState, catalog.catalogSnapshotBuilds)
            assertEquals(epGlobalBeforeState, catalog.epCacheStats.globalRecomputes)
            assertEquals(entriesLoads1, loader.entriesTableLoads)

            // 3. Entry write loads entries table ONCE for both inbox and catalog with 0 EP work
            catalog.upsertEntry(
                UpsertCatalogEntryInput(kind = EntryKind.MOVIE, title = "Andrei Rublev")
            )
            repository.snapshot.first()
            assertEquals(entriesLoads1 + 1, loader.entriesTableLoads)
            assertEquals(epGlobalBeforeState, catalog.epCacheStats.globalRecomputes)
        }
}

@Suppress("LongParameterList")
internal data class EpCacheFixture(
    val movie1: String,
    val movie2: String,
    val movie3: String,
    val book2: String,
    val wantBooksId: String,
    val wantGamesId: String,
    val albumListId: String,
)

internal suspend fun seedEpCacheFixture(catalog: CatalogStore): EpCacheFixture {
    catalog.ensureSystemSeeded()
    val movie1 =
        catalog.logEntry(
            UpsertCatalogEntryInput(kind = EntryKind.MOVIE, title = "Solaris"),
            ConsumptionStatus.CONSUMED,
        )
    val movie2 =
        catalog.logEntry(
            UpsertCatalogEntryInput(kind = EntryKind.MOVIE, title = "Stalker"),
            ConsumptionStatus.CONSUMED,
        )
    val movie3 =
        catalog.logEntry(
            UpsertCatalogEntryInput(kind = EntryKind.MOVIE, title = "Persona"),
            ConsumptionStatus.CONSUMED,
        )
    catalog.recordComparison(listOf(listOf(movie1), listOf(movie2)))

    val book1 =
        catalog.wantEntry(UpsertCatalogEntryInput(kind = EntryKind.BOOK, title = "Hyperion"))
    val book2 =
        catalog.wantEntry(UpsertCatalogEntryInput(kind = EntryKind.BOOK, title = "Blindsight"))
    val wantBooksId = catalog.resolveCollectionRankMeta("want:books")!!.collectionId
    catalog.recordComparison(listOf(listOf(book1), listOf(book2)), scopeCollectionId = wantBooksId)

    val game1 =
        catalog.wantEntry(UpsertCatalogEntryInput(kind = EntryKind.GAME, title = "Outer Wilds"))
    val game2 =
        catalog.wantEntry(UpsertCatalogEntryInput(kind = EntryKind.GAME, title = "Disco Elysium"))
    val wantGamesId = catalog.resolveCollectionRankMeta("want:games")!!.collectionId
    catalog.recordComparison(listOf(listOf(game1), listOf(game2)), scopeCollectionId = wantGamesId)

    val albumListId = catalog.createCustomList("1001 Albums")
    val album1 =
        catalog.upsertEntry(UpsertCatalogEntryInput(kind = EntryKind.ALBUM, title = "Homogenic"))
    val album2 =
        catalog.upsertEntry(UpsertCatalogEntryInput(kind = EntryKind.ALBUM, title = "Post"))
    catalog.addCollectionEntry(albumListId, album1)
    catalog.addCollectionEntry(albumListId, album2)

    return EpCacheFixture(
        movie1 = movie1,
        movie2 = movie2,
        movie3 = movie3,
        book2 = book2,
        wantBooksId = wantBooksId,
        wantGamesId = wantGamesId,
        albumListId = albumListId,
    )
}

internal suspend fun seedStatelessReaderFixture(dao: RocheDao, listId: String, feedId: String) {
    seedStatelessCollectionEntry(dao, STATELESS_ART_ID, KIND_ARTICLE, WANT_TO_READ_COLLECTION_ID)
    seedStatelessCollectionEntry(dao, STATELESS_PAPER_ID, KIND_PAPER, listId)
    seedStatelessCollectionEntry(dao, STATELESS_VIDEO_ID, KIND_VIDEO, WANT_TO_READ_COLLECTION_ID)
    seedStatelessCollectionEntry(dao, STATELESS_POD_ID, KIND_PODCAST_EPISODE, listId)

    seedStatelessCollectionEntry(dao, "nonreader-album", "album", listId)
    seedStatelessCollectionEntry(dao, "nonreader-book", "book", WANT_TO_READ_COLLECTION_ID)
    seedStatelessCollectionEntry(dao, "nonreader-movie", "movie", listId)

    seedStatelessCollectionEntry(dao, DELETED_WANT_ID, KIND_ARTICLE, WANT_TO_READ_COLLECTION_ID)
    dao.upsertEntryState(
        EntryStateEntity(
            entryId = DELETED_WANT_ID,
            inboxState = STATE_DELETED,
            updatedAt = FIXTURE_NOW_ISO,
            hlc = "h-del-1",
        )
    )
    seedStatelessCollectionEntry(dao, DELETED_CONTENT_ID, KIND_ARTICLE, listId)
    dao.upsertEntryContent(EntryContentEntity(entryId = DELETED_CONTENT_ID))
    dao.upsertEntryState(
        EntryStateEntity(
            entryId = DELETED_CONTENT_ID,
            inboxState = STATE_DELETED,
            updatedAt = FIXTURE_NOW_ISO,
            hlc = "h-del-2",
        )
    )
    seedStatelessCollectionEntry(dao, "stateless-feed-art", KIND_ARTICLE, feedId)
}

internal suspend fun seedStatelessCollectionEntry(
    dao: RocheDao,
    entryId: String,
    kind: String,
    collectionId: String,
) {
    dao.upsertEntry(
        EntryEntity(
            id = entryId,
            kind = kind,
            title = "Title $entryId",
            url = "https://example.com/$entryId",
            createdAt = FIXTURE_NOW_ISO,
            updatedAt = FIXTURE_NOW_ISO,
            hlc = "h-$entryId",
        )
    )
    dao.upsertCollectionEntry(
        CollectionEntryEntity(
            collectionId = collectionId,
            entryId = entryId,
            addedAt = FIXTURE_NOW_ISO,
            hlc = "h-mem-$entryId",
        )
    )
}

internal fun assertRankedCollectionParity(
    warm: CatalogSnapshot,
    cold: CatalogSnapshot,
    collectionId: String,
) {
    val warmList = warm.entriesByCollectionId[collectionId].orEmpty()
    val coldList = cold.entriesByCollectionId[collectionId].orEmpty()
    assertEquals(coldList.size, warmList.size)
    for (idx in coldList.indices) {
        assertEquals(coldList[idx].entry.id, warmList[idx].entry.id)
        assertEquals(coldList[idx].rank, warmList[idx].rank)
        assertEquals(coldList[idx].tier, warmList[idx].tier)
        assertTrue(abs(coldList[idx].mean - warmList[idx].mean) <= EP_PARITY_TOLERANCE)
        assertTrue(abs(coldList[idx].variance - warmList[idx].variance) <= EP_PARITY_TOLERANCE)
    }
}

private const val MOVIES_SLUG = "movies"
private const val FIXTURE_NOW_ISO = "2026-11-20T00:00:00Z"
private const val EP_PARITY_TOLERANCE = 1e-12
private const val PROGRESS_HALF = 0.5
private const val PROGRESS_SAMPLE = 0.42
private const val REPEATED_READ_CALLS = 6
private const val ALL_CATALOG_TABLES_COUNT = 7
private const val STATELESS_ART_ID = "stateless-art"
private const val STATELESS_PAPER_ID = "stateless-paper"
private const val STATELESS_VIDEO_ID = "stateless-video"
private const val STATELESS_POD_ID = "stateless-pod"
private const val DELETED_WANT_ID = "deleted-want-art"
private const val DELETED_CONTENT_ID = "deleted-content-art"
private const val KIND_ARTICLE = "article"
private const val KIND_PAPER = "paper"
private const val KIND_VIDEO = "video"
private const val KIND_PODCAST_EPISODE = "podcast_episode"
private const val STATE_DELETED = "deleted"

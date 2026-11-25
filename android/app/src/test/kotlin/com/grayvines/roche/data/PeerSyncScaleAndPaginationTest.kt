@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data

import com.grayvines.roche.core.HlcTimestamp
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.core.itemNaturalKey
import com.grayvines.roche.data.catalog.CatalogStore
import com.grayvines.roche.data.catalog.SyncCollectionEntryDto
import com.grayvines.roche.data.catalog.SyncConsumptionLogDto
import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.ComparisonEntity
import com.grayvines.roche.data.local.ConsumptionLogEntity
import com.grayvines.roche.data.local.EntryContentEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryIdentifierEntity
import com.grayvines.roche.data.local.EntryStateEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.remote.SyncTombstoneDto
import com.grayvines.roche.data.sync.MAX_DELTA_CONTINUATION_PAGES
import com.grayvines.roche.data.sync.PeerSyncCoordinator
import com.grayvines.roche.data.sync.PeerSyncRequestConfig
import com.grayvines.roche.data.sync.buildTrackedPeerSyncRequest
import com.grayvines.roche.data.sync.foldMergedDuplicateInDao
import com.grayvines.roche.data.sync.reconcilePeerSyncResponse
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PeerSyncScaleAndPaginationTest {
    @TempDir lateinit var tempDir: File

    private lateinit var env: TestRocheEnv

    @BeforeEach
    fun setUp() {
        env = TestRocheEnv(tempDir, "peer_sync_scale_test.preferences_pb")
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    @Test
    fun `PeerSyncCoordinator drains more than 25 delta continuation pages in one sync cycle`() =
        runTest {
            assertTrue(MAX_DELTA_CONTINUATION_PAGES >= 200)
            val totalPages = 30
            val seenSinceSeqs = ArrayList<Long>()
            val coordinator =
                PeerSyncCoordinator(
                    dao = env.database.dao(),
                    serverApi =
                        tailnetServerApi { req ->
                            val sinceSeq = extractSinceSeq(req.body)
                            seenSinceSeqs.add(sinceSeq)
                            val nextSeq = sinceSeq + 1L
                            val hasMore = nextSeq < totalPages.toLong()
                            val hlc =
                                HlcTimestamp.create(1_795_100_000_000L + nextSeq, 0, "server")
                                    .encode()
                            val songId = "song-page-$nextSeq"
                            val logId = "log-page-$nextSeq"
                            HttpResponse(
                                statusCode = 200,
                                body =
                                    """{"server_peer_id":"server","sync_seq":$nextSeq,"server_hlc":"$hlc","sync_timestamp":"2026-11-25T10:00:00Z","has_more":$hasMore,"active_peers":[],"entries":[{"id":"$songId","kind":"song","title":"Track $nextSeq","created_at":"2026-11-25T10:00:00Z","hlc":"$hlc"}],"collection_entries":[],"collection_generators":[],"consumption_log":[{"id":"$logId","entry_id":"$songId","ts":"2026-11-25T10:00:00Z","status":"consumed","hlc":"$hlc"}],"comparisons":[],"tombstones":[]}""",
                            )
                        },
                    catalogStore = env.repository.catalog,
                    onSyncCursorUpdated = { cursor ->
                        env.settings.update { it.copy(lastPeerSyncCursor = cursor) }
                    },
                )
            configureTestServer(cursor = "0")

            coordinator.syncAndCooperativePoll(env.settings.settings.first())

            assertEquals(totalPages, seenSinceSeqs.size)
            assertEquals((0L until totalPages.toLong()).toList(), seenSinceSeqs)
            assertEquals(totalPages.toString(), env.settings.settings.first().lastPeerSyncCursor)
            assertEquals(totalPages, env.database.dao().countConsumedEntriesForKind("song"))
        }

    @Test
    fun `mid-pagination reset_required resets cursor and completes one bounded restart`() =
        runTest {
            var requestCount = 0
            val seenSinceSeqs = ArrayList<Long>()
            val coordinator =
                PeerSyncCoordinator(
                    dao = env.database.dao(),
                    serverApi =
                        tailnetServerApi { req ->
                            requestCount += 1
                            seenSinceSeqs.add(extractSinceSeq(req.body))
                            val body =
                                when (requestCount) {
                                    1 ->
                                        """{"server_peer_id":"server","sync_seq":11,"server_hlc":"001795100000011-0000-server","sync_timestamp":"2026-11-25T10:00:00Z","has_more":true,"active_peers":[]}"""
                                    2 ->
                                        """{"server_peer_id":"server","sync_seq":0,"server_hlc":"001795100000012-0000-server","sync_timestamp":"2026-11-25T10:00:00Z","reset_required":true,"active_peers":[]}"""
                                    else ->
                                        """{"server_peer_id":"server","sync_seq":5,"server_hlc":"001795100000020-0000-server","sync_timestamp":"2026-11-25T10:00:00Z","has_more":false,"active_peers":[],"entries":[{"id":"song-restarted","kind":"song","title":"Restarted","created_at":"2026-11-25T10:00:00Z","hlc":"001795100000020-0000-server"}]}"""
                                }
                            HttpResponse(statusCode = 200, body = body)
                        },
                    catalogStore = env.repository.catalog,
                    onSyncCursorUpdated = { cursor ->
                        env.settings.update { it.copy(lastPeerSyncCursor = cursor) }
                    },
                )
            configureTestServer(cursor = "10")

            coordinator.syncAndCooperativePoll(env.settings.settings.first())

            assertEquals(listOf(10L, 11L, 0L), seenSinceSeqs)
            assertEquals("5", env.settings.settings.first().lastPeerSyncCursor)
            assertNotNull(env.database.dao().getEntryById("song-restarted"))
        }

    @Test
    fun `reset_required on large clean catalog does not dirty or re-upload clean rows`() = runTest {
        val dao = env.database.dao()
        seedLargeCleanCatalog(dao, cleanCount = 2_200)
        val requestBodies = ArrayList<String>()
        val coordinator =
            PeerSyncCoordinator(
                dao = dao,
                serverApi =
                    tailnetServerApi { req ->
                        val body = req.body.orEmpty()
                        requestBodies.add(body)
                        val respBody =
                            if (requestBodies.size == 1) {
                                """{"server_peer_id":"server","sync_seq":0,"server_hlc":"001795100000050-0000-server","sync_timestamp":"2026-11-25T10:00:00Z","reset_required":true,"active_peers":[]}"""
                            } else {
                                """{"server_peer_id":"server","sync_seq":55,"server_hlc":"001795100000055-0000-server","sync_timestamp":"2026-11-25T10:00:00Z","has_more":false,"active_peers":[]}"""
                            }
                        HttpResponse(statusCode = 200, body = respBody)
                    },
                catalogStore = env.repository.catalog,
                onSyncCursorUpdated = { cursor ->
                    env.settings.update { it.copy(lastPeerSyncCursor = cursor) }
                },
            )
        configureTestServer(cursor = "50")

        coordinator.syncAndCooperativePoll(env.settings.settings.first())

        assertEquals(2, requestBodies.size)
        assertEquals(0L, extractSinceSeq(requestBodies[1]))
        assertTrue(requestBodies[1].contains("song-dirty-1"))
        assertFalse(requestBodies[1].contains("song-clean-0001"))
        assertTrue(dao.getDirtyEntries().isEmpty())
        assertTrue(dao.getDirtyCollectionEntries().isEmpty())
        assertEquals("55", env.settings.settings.first().lastPeerSyncCursor)
    }

    @Test
    fun `overflow drain step resets persisted cursor when server returns reset_required`() =
        runTest {
            val dao = env.database.dao()
            seedOversizedDirtyArticles(dao)
            var callCount = 0
            val coordinator =
                PeerSyncCoordinator(
                    dao = dao,
                    serverApi =
                        tailnetServerApi {
                            callCount += 1
                            val body =
                                if (callCount == 1) {
                                    """{"server_peer_id":"server","sync_seq":42,"server_hlc":"001795100000042-0000-server","sync_timestamp":"2026-11-25T10:00:00Z","has_more":false,"active_peers":[]}"""
                                } else {
                                    """{"server_peer_id":"server","sync_seq":0,"server_hlc":"001795100000043-0000-server","sync_timestamp":"2026-11-25T10:00:00Z","reset_required":true,"active_peers":[]}"""
                                }
                            HttpResponse(statusCode = 200, body = body)
                        },
                    catalogStore = env.repository.catalog,
                    onSyncCursorUpdated = { cursor ->
                        env.settings.update { it.copy(lastPeerSyncCursor = cursor) }
                    },
                )
            configureTestServer(cursor = "10")

            coordinator.syncAndCooperativePoll(env.settings.settings.first())

            assertEquals(2, callCount)
            assertEquals("0", env.settings.settings.first().lastPeerSyncCursor)
        }

    @Test
    fun `scoped sync graph rejects multi-hop cycles and enforces item tombstones`() = runTest {
        val dao = env.database.dao()
        val catalog = CatalogStore(dao = dao, localPeerIdProvider = { "android-peer" })
        catalog.ensureSystemSeeded()
        seedCycleAndTombstoneFixture()
        val hlc = HlcTimestamp.create(1_795_200_000_000L, 0, "server").encode()

        reconcilePeerSyncResponse(
            dao = dao,
            response = buildGraphReconcileResponse(hlc),
            localPeerId = "android-peer",
            catalogStore = catalog,
        )

        assertNull(dao.getCollectionEntry("col-b", "col-a"))
        assertNotNull(dao.getCollectionEntry("col-b", "col-c"))
        assertNull(dao.getCollectionEntry("col-c", "col-a"))
        assertNotNull(dao.getCollectionEntry("col-b", "song-01"))
        assertNull(dao.getCollectionEntry(WANT_TO_READ_COLLECTION_ID, "song-01"))
        assertNull(dao.getEntryById("art-del"))
        assertNull(dao.getEntryStateById("art-del"))
    }

    @Test
    fun `duplicate fold preserves prefix and case colliding comparisons and identifiers`() =
        runTest {
            val dao = env.database.dao()
            val catalog = CatalogStore(dao = dao, localPeerIdProvider = { "android-peer" })
            catalog.ensureSystemSeeded()
            seedCycleAndTombstoneFixture()
            seedCollidingComparisonFixture(dao)

            foldMergedDuplicateInDao(
                dao = dao,
                dupId = "song-dup",
                canonId = "song-01",
                nowIso = "2026-11-25T10:01:00Z",
            )
            assertNotNull(dao.getCollectionEntry("col-a", "song-01"))
            assertNull(dao.getCollectionEntry("col-a", "song-dup"))
            assertNotNull(dao.getComparisonById("cmp-legacy-prefix"))
            assertNotNull(dao.getComparisonById("cmp-legacy-case"))
            assertTrue(dao.getAllComparisons().any { it.orderingJson.contains("song-01") })

            val outgoing =
                buildTrackedPeerSyncRequest(
                    dao = dao,
                    config =
                        PeerSyncRequestConfig(
                            peerId = "android-peer",
                            deviceName = "Pixel",
                            sinceSeq = 0L,
                        ),
                )
            val songOut = outgoing.request.entries.first { it.id == "song-01" }
            assertTrue(songOut.identifiers.any { it.scheme == "spotify" && it.value == "track:1" })
        }

    @Test
    fun `full snapshot prioritizes entries referenced by dirty logs ahead of clean entries`() =
        runTest {
            val dao = env.database.dao()
            val oldHlc = HlcTimestamp.create(1_795_000_000_000L, 0, "android-peer").encode()
            val cleanSongs =
                (1..2_050).map { i ->
                    fixtureEntry(
                        id = "song-new-${i.toString().padStart(4, '0')}",
                        kind = "song",
                        title = "New Song $i",
                        hlc = oldHlc,
                        updatedAt = "2026-11-20T00:00:00Z",
                    )
                }
            val oldSong =
                fixtureEntry(
                    id = "song-old-logged",
                    kind = "song",
                    title = "Old Logged Song",
                    hlc = oldHlc,
                    updatedAt = "2020-01-01T00:00:00Z",
                )
            dao.upsertEntries(cleanSongs + oldSong)
            dao.insertConsumptionLogIgnore(
                ConsumptionLogEntity(
                    id = "log-dirty-old",
                    entryId = "song-old-logged",
                    ts = "2026-11-25T09:00:00Z",
                    status = "consumed",
                    notes = null,
                    createdAt = "2026-11-25T09:00:00Z",
                    hlc = HlcTimestamp.create(1_795_300_000_000L, 0, "android-peer").encode(),
                    isLocalDirty = true,
                )
            )

            val outgoing =
                buildTrackedPeerSyncRequest(
                    dao = dao,
                    config =
                        PeerSyncRequestConfig(
                            peerId = "android-peer",
                            deviceName = "Pixel",
                            sinceSeq = 0L,
                        ),
                )
            assertTrue(outgoing.request.entries.any { it.id == "song-old-logged" })
            assertTrue(outgoing.request.consumptionLog.any { it.id == "log-dirty-old" })
        }

    private suspend fun configureTestServer(cursor: String) {
        env.settings.update {
            it.copy(
                serverUrl = "https://100.64.0.12:8080",
                bearerToken = "tok-scale",
                lastPeerSyncCursor = cursor,
            )
        }
    }

    private fun extractSinceSeq(body: String?): Long {
        val match = Regex(""""since_seq":(\d+)""").find(body.orEmpty())
        return match?.groupValues?.get(1)?.toLong() ?: 0L
    }

    private fun buildGraphReconcileResponse(hlc: String): PeerSyncResponseDto =
        PeerSyncResponseDto(
            serverPeerId = "server",
            serverHlc = hlc,
            syncTimestamp = "2026-11-25T10:00:00Z",
            collectionEntries =
                listOf(
                    SyncCollectionEntryDto(
                        "col-b",
                        "col-a",
                        addedAt = "2026-11-25T10:00:00Z",
                        hlc = hlc,
                    ),
                    SyncCollectionEntryDto(
                        "col-b",
                        "col-c",
                        addedAt = "2026-11-25T10:00:00Z",
                        hlc = hlc,
                    ),
                    SyncCollectionEntryDto(
                        "col-c",
                        "col-a",
                        addedAt = "2026-11-25T10:00:00Z",
                        hlc = hlc,
                    ),
                    SyncCollectionEntryDto(
                        "col-b",
                        "song-01",
                        addedAt = "2026-11-25T10:00:00Z",
                        hlc = hlc,
                    ),
                ),
            consumptionLog =
                listOf(
                    SyncConsumptionLogDto(
                        "log-wtr-song",
                        "song-01",
                        "2026-11-25T10:00:00Z",
                        "consumed",
                        hlc = hlc,
                    )
                ),
            tombstones =
                listOf(
                    SyncTombstoneDto(
                        "item",
                        itemNaturalKey(null, "https://example.com/del"),
                        "2026-11-25T10:00:00Z",
                        hlc,
                    )
                ),
        )

    private fun fixtureEntry(
        id: String,
        kind: String,
        title: String,
        hlc: String,
        url: String? = null,
        updatedAt: String = "2026-01-01T00:00:00Z",
        isLocalDirty: Boolean = false,
    ): EntryEntity =
        EntryEntity(
            id = id,
            kind = kind,
            title = title,
            url = url,
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = updatedAt,
            hlc = hlc,
            isLocalDirty = isLocalDirty,
        )

    private suspend fun seedLargeCleanCatalog(dao: RocheDao, cleanCount: Int) {
        val cleanHlc = HlcTimestamp.create(1_795_000_000_000L, 0, "server").encode()
        val dirtyHlc = HlcTimestamp.create(1_795_200_000_000L, 0, "android-peer").encode()
        val playlist = fixtureEntry("pl-clean", "collection", "Spotify Likes", cleanHlc)
        val cleanSongs =
            (1..cleanCount).map { i ->
                fixtureEntry(
                    id = "song-clean-${i.toString().padStart(4, '0')}",
                    kind = "song",
                    title = "Clean Song $i",
                    hlc = cleanHlc,
                    updatedAt = "2026-06-01T00:00:00Z",
                )
            }
        val dirtySong =
            fixtureEntry(
                id = "song-dirty-1",
                kind = "song",
                title = "New Local Song",
                hlc = dirtyHlc,
                updatedAt = "2026-11-25T09:00:00Z",
                isLocalDirty = true,
            )
        dao.upsertEntries(listOf(playlist, dirtySong) + cleanSongs)
        dao.upsertCollectionSpec(CollectionSpecEntity("pl-clean", "pl-clean", "list"))
        dao.upsertCollectionEntries(
            cleanSongs.map { s ->
                CollectionEntryEntity(
                    "pl-clean",
                    s.id,
                    null,
                    "2026-06-01T00:00:00Z",
                    cleanHlc,
                    false,
                )
            }
        )
        dao.insertConsumptionLogsIgnore(
            cleanSongs.map { s ->
                ConsumptionLogEntity(
                    "log-${s.id}",
                    s.id,
                    "2026-06-01T00:00:00Z",
                    "consumed",
                    null,
                    "2026-06-01T00:00:00Z",
                    cleanHlc,
                    false,
                )
            }
        )
    }

    private suspend fun seedOversizedDirtyArticles(dao: RocheDao) {
        val dirtyHlc = HlcTimestamp.create(1_795_200_000_000L, 0, "android-peer").encode()
        val bigHtml = "<p>" + "x".repeat(1_500_000) + "</p>"
        val ids = listOf("art-big-1", "art-big-2")
        dao.upsertEntries(
            ids.mapIndexed { i, id ->
                fixtureEntry(
                    id = id,
                    kind = "article",
                    title = "Big $i",
                    hlc = dirtyHlc,
                    url = "https://example.com/$id",
                    updatedAt = "2026-11-25T0${i + 1}:00:00Z",
                    isLocalDirty = true,
                )
            }
        )
        for (id in ids) {
            dao.upsertEntryContent(EntryContentEntity(id, "article", contentHtml = bigHtml))
            dao.upsertEntryState(
                EntryStateEntity(
                    id,
                    "unread",
                    updatedAt = "2026-11-25T01:00:00Z",
                    hlc = dirtyHlc,
                    isLocalDirty = true,
                )
            )
        }
    }

    private suspend fun seedCycleAndTombstoneFixture() {
        val dao = env.database.dao()
        val oldHlc = HlcTimestamp.create(1_795_000_000_000L, 0, "android-peer").encode()
        seedFixtureEntriesAndSpecs(dao, oldHlc)
        seedFixtureGraphRows(dao, oldHlc)
    }

    private suspend fun seedFixtureEntriesAndSpecs(dao: RocheDao, oldHlc: String) {
        dao.upsertEntries(
            listOf(
                fixtureEntry("col-a", "collection", "Col A", oldHlc),
                fixtureEntry("col-b", "collection", "Col B", oldHlc),
                fixtureEntry("col-c", "collection", "Col C", oldHlc),
                fixtureEntry("song-01", "song", "Song One", oldHlc),
                fixtureEntry("song-dup", "song", "Song Dup", oldHlc),
                fixtureEntry("song-02", "song", "Song Two", oldHlc),
                fixtureEntry("art-del", "article", "To Delete", oldHlc, "https://example.com/del"),
            )
        )
        dao.upsertCollectionSpecs(
            listOf(
                CollectionSpecEntity("col-a", "col-a", "list"),
                CollectionSpecEntity("col-b", "col-b", "list"),
                CollectionSpecEntity("col-c", "col-c", "list"),
            )
        )
        dao.upsertEntryState(
            EntryStateEntity("art-del", "unread", updatedAt = "2026-01-01T00:00:00Z", hlc = oldHlc)
        )
        dao.insertEntryIdentifierIgnore(EntryIdentifierEntity("spotify", "track:1", "song-01"))
    }

    private suspend fun seedFixtureGraphRows(dao: RocheDao, oldHlc: String) {
        val ts = "2026-01-01T00:00:00Z"
        dao.upsertCollectionEntries(
            listOf(
                CollectionEntryEntity("col-a", "col-b", null, ts, oldHlc, false),
                CollectionEntryEntity("col-a", "song-dup", null, ts, oldHlc, false),
                CollectionEntryEntity(
                    WANT_TO_READ_COLLECTION_ID,
                    "song-01",
                    null,
                    ts,
                    oldHlc,
                    false,
                ),
            )
        )
        dao.insertComparisonIgnore(
            ComparisonEntity(
                "cmp-dup",
                "2026-01-02T00:00:00Z",
                null,
                """[["song-dup"],["song-02"]]""",
                "2026-01-02T00:00:00Z",
                oldHlc,
                false,
            )
        )
        dao.insertConsumptionLogIgnore(
            ConsumptionLogEntity("log-s2", "song-02", ts, "consumed", null, ts, oldHlc, false)
        )
    }

    private suspend fun seedCollidingComparisonFixture(dao: RocheDao) {
        val oldHlc = HlcTimestamp.create(1_795_000_000_000L, 0, "android-peer").encode()
        dao.upsertEntries(
            listOf(
                fixtureEntry("song-dup-extra", "song", "Song Dup Extra", oldHlc),
                fixtureEntry("SONG-DUP", "song", "Song Dup Upper", oldHlc),
            )
        )
        dao.insertComparisonIgnore(
            ComparisonEntity(
                "cmp-legacy-prefix",
                "2026-01-03T00:00:00Z",
                null,
                """[["song-dup-extra"],["song-02"]]""",
                "2026-01-03T00:00:00Z",
                oldHlc,
                false,
            )
        )
        dao.insertComparisonIgnore(
            ComparisonEntity(
                "cmp-legacy-case",
                "2026-01-04T00:00:00Z",
                null,
                """[["SONG-DUP"],["song-02"]]""",
                "2026-01-04T00:00:00Z",
                oldHlc,
                false,
            )
        )
    }
}

@file:Suppress("StringLiteralDuplication")

package com.grayvines.roche.data

import com.grayvines.roche.core.TOMBSTONE_ENTITY_ENTRY
import com.grayvines.roche.core.deterministicBoardId
import com.grayvines.roche.core.deterministicItemId
import com.grayvines.roche.core.mergeEntryMetadataJson
import com.grayvines.roche.core.parseDiscussionLinks
import com.grayvines.roche.core.parseMetadataJsonObject
import com.grayvines.roche.data.boards.BoardAdapterRegistry
import com.grayvines.roche.data.boards.BoardAdapterType
import com.grayvines.roche.data.boards.BoardItemView
import com.grayvines.roche.data.boards.BoardOrderMode
import com.grayvines.roche.data.boards.BoardPromoteAction
import com.grayvines.roche.data.boards.BoardReaderHtmlCache
import com.grayvines.roche.data.boards.HackerNewsSection
import com.grayvines.roche.data.boards.loadBoardItemReaderContent
import com.grayvines.roche.data.local.BoardEntity
import com.grayvines.roche.data.local.BoardItemEntity
import com.grayvines.roche.data.local.BoardItemStateEntity
import com.grayvines.roche.data.local.BoardPromoteParams
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.data.local.promoteBoardItemInTx
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.HttpTransport
import com.grayvines.roche.data.remote.NetworkTier
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.remote.PublicWebFetcher
import com.grayvines.roche.data.remote.SyncBoardDto
import com.grayvines.roche.data.remote.SyncBoardItemStateDto
import com.grayvines.roche.data.sync.NetworkTierProvider
import com.grayvines.roche.data.sync.PeerSyncRequestConfig
import com.grayvines.roche.data.sync.buildTrackedPeerSyncRequest
import com.grayvines.roche.data.sync.reconcilePeerSyncResponse
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BoardFixesRegressionTest {
    @TempDir lateinit var tempDir: File

    private class FlakyReaderStub(var failFlaky: Boolean = true) {
        var flakyFetches = 0

        val fetcher = PublicWebFetcher { req -> handle(req.url) }

        private fun handle(url: String): HttpResponse =
            when (url) {
                "https://blog.example.com/flaky" -> serveFlaky()
                "https://arxiv.org/abs/1706.03762" ->
                    HttpResponse(
                        200,
                        "<html><body><article><h1>Attention</h1><p>Abstract HTML text.</p></article></body></html>",
                    )
                else -> HttpResponse(404, "not found")
            }

        private fun serveFlaky(): HttpResponse {
            flakyFetches++
            return if (failFlaky) {
                HttpResponse(503, "unavailable")
            } else {
                HttpResponse(
                    200,
                    "<html><body><article><p>Recovered body $flakyFetches.</p></article></body></html>",
                )
            }
        }
    }

    @Test
    fun `BoardReaderService preserves fetchError skips caching failures and evicts on forceRefresh`() =
        runTest {
            ManagedTestDatabase().use { managed ->
                val dao = managed.database.dao()
                val board = seedSampleBoard(dao)
                seedReaderTestItems(dao, board.id)
                val stub = FlakyReaderStub(failFlaky = true)
                val cache = BoardReaderHtmlCache()
                val registry = BoardAdapterRegistry.default(stub.fetcher)

                val err =
                    loadBoardItemReaderContent(
                        dao,
                        stub.fetcher,
                        registry,
                        cache,
                        board,
                        "2001",
                        null,
                    )!!
                assertTrue(err.extractionFailed)
                assertEquals("HTTP 503", err.fetchError)
                assertNull(cache.get("https://blog.example.com/flaky"))

                stub.failFlaky = false
                val ok =
                    loadBoardItemReaderContent(
                        dao,
                        stub.fetcher,
                        registry,
                        cache,
                        board,
                        "2001",
                        null,
                    )!!
                assertFalse(ok.extractionFailed)
                assertNull(ok.fetchError)
                assertTrue(ok.contentHtml.orEmpty().contains("Recovered body 2"))

                val forced =
                    loadBoardItemReaderContent(
                        dao,
                        stub.fetcher,
                        registry,
                        cache,
                        board,
                        "2001",
                        null,
                        forceRefresh = true,
                    )!!
                assertEquals(3, stub.flakyFetches)
                assertTrue(forced.contentHtml.orEmpty().contains("Recovered body 3"))
            }
        }

    @Test
    fun `BoardReaderService extracts arxiv abstract HTML`() = runTest {
        ManagedTestDatabase().use { managed ->
            val dao = managed.database.dao()
            val board = seedSampleBoard(dao)
            seedReaderTestItems(dao, board.id)
            val stub = FlakyReaderStub(failFlaky = false)
            val arxiv =
                loadBoardItemReaderContent(
                    dao,
                    stub.fetcher,
                    BoardAdapterRegistry.default(stub.fetcher),
                    BoardReaderHtmlCache(),
                    board,
                    "2002",
                    null,
                )!!
            assertFalse(arxiv.extractionFailed)
            assertNull(arxiv.fetchError)
            assertTrue(arxiv.contentHtml.orEmpty().contains("Abstract HTML text"))
        }
    }

    @Test
    fun `BoardReaderService skips binary media without fetchError and does not resurrect dismissedAt`() =
        runTest {
            ManagedTestDatabase().use { managed ->
                val dao = managed.database.dao()
                val board = seedSampleBoard(dao)
                dao.upsertBoardItemState(
                    BoardItemStateEntity(
                        boardId = board.id,
                        externalId = "3001",
                        seenAt = "2026-11-23T19:01:00Z",
                        dismissedAt = null,
                        lastSeenInSnapshotAt = "2026-11-23T19:00:00Z",
                        updatedAt = "2026-11-23T19:02:00Z",
                        hlc = "001795460500000-0000-peer-a",
                    )
                )
                val staleFallback =
                    sampleItemView(
                            boardId = board.id,
                            externalId = "3001",
                            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                        )
                        .copy(seenAt = "2026-11-23T19:00:00Z", dismissedAt = "2026-11-23T19:00:30Z")
                val fetcher = PublicWebFetcher { HttpResponse(500, "should not fetch") }
                val content =
                    loadBoardItemReaderContent(
                        dao,
                        fetcher,
                        BoardAdapterRegistry.default(fetcher),
                        BoardReaderHtmlCache(),
                        board,
                        "3001",
                        staleFallback,
                    )!!
                assertTrue(content.extractionFailed)
                assertNull(content.fetchError)
                assertNull(content.item.dismissedAt)
                assertEquals("2026-11-23T19:01:00Z", content.item.seenAt)
            }
        }

    @Test
    fun `metadata discussions CRDT handles JsonNull and drops non-HTTP or non-object entries`() {
        val existingJson = """{"discussions":null,"keep":"yes"}"""
        val incomingJson =
            """{"discussions":["junk",{"url":"javascript:alert(1)","source":"evil"},""" +
                """{"source":"hacker_news","url":"https://news.ycombinator.com/item?id=555",""" +
                """"external_id":"555","label":"Hacker News"}]}"""
        val merged =
            mergeEntryMetadataJson(
                existingJson = existingJson,
                incomingMeta = parseMetadataJsonObject(incomingJson),
                incomingWins = false,
            )
        val links = parseDiscussionLinks(merged)
        assertEquals(1, links.size)
        assertEquals("https://news.ycombinator.com/item?id=555", links.single().url)
        assertEquals("Hacker News", links.single().label)
        assertFalse(merged.contains("javascript:"))
        assertFalse(merged.contains("junk"))
    }

    @Test
    fun `offline refreshBoard avoids persisting lastError re-add preserves title and updateBoard edits orderMode`() =
        runTest {
            val env = TestRocheEnv(tempDir, "board_offline_test.preferences_pb")
            var currentTier = NetworkTier.WIFI
            val repo =
                mockRepository(
                    database = env.database,
                    settings = env.settings,
                    transport = singleStoryTransport(),
                    networkTierProvider = NetworkTierProvider { currentTier },
                )
            try {
                val created =
                    repo
                        .createBoard(BoardAdapterType.HACKER_NEWS, "top", "My Custom HN")
                        .getOrThrow()
                assertEquals("My Custom HN", created.title)

                val readded =
                    repo
                        .createBoard(BoardAdapterType.HACKER_NEWS, "https://news.ycombinator.com/")
                        .getOrThrow()
                assertEquals("My Custom HN", readded.title)

                val updated =
                    repo
                        .updateBoard(created.id, orderMode = BoardOrderMode.CHRONOLOGICAL)
                        .getOrThrow()
                assertEquals(BoardOrderMode.CHRONOLOGICAL, updated.orderMode)
                assertEquals("top", updated.sourceKey)

                val dao = env.database.dao()
                val beforeOffline = dao.getBoardById(created.id)!!
                dao.clearBoardDirtyIfSameHlc(
                    id = beforeOffline.id,
                    expectedHlc = beforeOffline.hlc,
                    expectedSnapshotHlc = beforeOffline.snapshotHlc,
                    expectedLastPolledAt = beforeOffline.lastPolledAt.orEmpty(),
                )
                currentTier = NetworkTier.OFFLINE

                assertTrue(repo.refreshBoard(created.id).isFailure)
                assertTrue(repo.refreshAllBoards().isFailure)
                val afterOffline = dao.getBoardById(created.id)!!
                assertNull(afterOffline.lastError)
                assertEquals(0, afterOffline.consecutiveFailures)
                assertFalse(afterOffline.isLocalDirty)
            } finally {
                env.close()
            }
        }

    @Test
    fun `promoteBoardItemInTx clears entry tombstone and gates html enrichment by URL type`() =
        runTest {
            ManagedTestDatabase().use { managed ->
                val dao = managed.database.dao()
                val board = seedSampleBoard(dao)
                val videoUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
                val expectedVideoId = deterministicItemId(null, videoUrl)
                dao.upsertTombstone(
                    TombstoneEntity(
                        entityType = TOMBSTONE_ENTITY_ENTRY,
                        naturalKey = expectedVideoId,
                        deletedAt = "2026-11-23T18:00:00Z",
                        hlc = "001795460100000-0000-server",
                    )
                )
                val videoCommit =
                    dao.promoteBoardItemInTx(
                        BoardPromoteParams(
                            board = board,
                            item = sampleItemView(board.id, "4001", videoUrl),
                            action = BoardPromoteAction.QUEUE,
                        )
                    )
                assertEquals(expectedVideoId, videoCommit.result.entryId)
                assertFalse(videoCommit.needsHtmlEnrichment)
                assertNull(dao.getTombstone(TOMBSTONE_ENTITY_ENTRY, expectedVideoId))

                val arxivCommit =
                    dao.promoteBoardItemInTx(
                        BoardPromoteParams(
                            board = board,
                            item =
                                sampleItemView(
                                    board.id,
                                    "4002",
                                    "https://arxiv.org/pdf/2301.00001v2.pdf",
                                ),
                            action = BoardPromoteAction.QUEUE,
                        )
                    )
                assertEquals(
                    deterministicItemId(null, "https://arxiv.org/abs/2301.00001"),
                    arxivCommit.result.entryId,
                )
                assertTrue(arxivCommit.needsHtmlEnrichment)
            }
        }

    @Test
    fun `PeerSyncInBoards matches natural key first and dirty ack guards concurrent poll`() =
        runTest {
            ManagedTestDatabase().use { managed ->
                val dao = managed.database.dao()
                val legacyId = "legacy-local-board-id"
                val seeded = seedSampleBoard(dao)
                dao.deleteBoardById(seeded.id)
                dao.upsertBoard(seeded.copy(id = legacyId, title = "Old Top"))
                val remoteId = deterministicBoardId("hacker_news", "top")
                val incomingBoard =
                    SyncBoardDto(
                        id = remoteId,
                        adapterType = "hacker_news",
                        sourceKey = "top",
                        sourceUrl = "https://news.ycombinator.com/",
                        title = "Synced Top",
                        hlc = "001795460500000-0000-server",
                    )
                val incomingState =
                    SyncBoardItemStateDto(
                        boardId = remoteId,
                        externalId = "777",
                        seenAt = "2026-11-23T18:55:00Z",
                        lastSeenInSnapshotAt = "2026-11-23T18:55:00Z",
                        updatedAt = "2026-11-23T18:55:00Z",
                        hlc = "001795460500001-0000-server",
                    )
                reconcilePeerSyncResponse(
                    dao = dao,
                    response =
                        PeerSyncResponseDto(
                            serverPeerId = "server",
                            syncSeq = 10L,
                            serverHlc = "001795460500000-0000-server",
                            syncTimestamp = "2026-11-23T19:00:00Z",
                            boards = listOf(incomingBoard),
                            boardItemStates = listOf(incomingState),
                        ),
                    localPeerId = "peer-b",
                )
                assertEquals(1, dao.getAllBoards().size)
                assertEquals("Synced Top", dao.getBoardById(legacyId)?.title)
                assertNotNull(dao.getBoardItemState(legacyId, "777"))

                verifyDirtyAckRaceGuard(dao, legacyId)
            }
        }

    private suspend fun verifyDirtyAckRaceGuard(dao: RocheDao, boardId: String) {
        val base = dao.getBoardById(boardId)!!
        dao.upsertBoard(
            base.copy(
                lastPolledAt = "2026-11-23T19:00:00Z",
                snapshotHlc = "001795460600000-0000-peer-b",
                isLocalDirty = true,
                isSnapshotDirty = true,
            )
        )
        val outgoing =
            buildTrackedPeerSyncRequest(
                dao = dao,
                config =
                    PeerSyncRequestConfig(
                        peerId = "peer-b",
                        deviceName = "Pixel",
                        networkTier = NetworkTier.WIFI,
                        sinceSeq = 10L,
                    ),
            )
        val duringFlight = dao.getBoardById(boardId)!!
        dao.upsertBoard(
            duringFlight.copy(
                lastPolledAt = "2026-11-23T19:05:00Z",
                snapshotHlc = "001795460900000-0000-peer-b",
                isLocalDirty = true,
                isSnapshotDirty = true,
            )
        )
        reconcilePeerSyncResponse(
            dao = dao,
            response =
                PeerSyncResponseDto(
                    serverPeerId = "server",
                    syncSeq = 11L,
                    serverHlc = "001795460600001-0000-server",
                    syncTimestamp = "2026-11-23T19:01:00Z",
                ),
            localPeerId = "peer-b",
            sentBatch = outgoing,
        )
        val afterAck = dao.getBoardById(boardId)!!
        assertTrue(afterAck.isLocalDirty, "concurrent poll must preserve isLocalDirty")
        assertTrue(afterAck.isSnapshotDirty, "concurrent poll must preserve isSnapshotDirty")
    }

    private suspend fun seedSampleBoard(dao: RocheDao): BoardEntity {
        val board =
            BoardEntity(
                id = deterministicBoardId("hacker_news", "top"),
                adapterType = "hacker_news",
                sourceKey = "top",
                sourceUrl = "https://news.ycombinator.com/",
                sourceConfigJson = """{"section":"top"}""",
                title = "Hacker News",
                orderMode = "ranked",
                pollInterval = 30,
                isActive = true,
                createdAt = "2026-11-23T18:00:00Z",
                updatedAt = "2026-11-23T18:00:00Z",
                hlc = "001795460000000-0000-peer-a",
            )
        dao.upsertBoard(board)
        return board
    }

    private suspend fun seedReaderTestItems(dao: RocheDao, boardId: String) {
        dao.insertBoardItems(
            listOf(
                BoardItemEntity(
                    boardId = boardId,
                    externalId = "2001",
                    rank = 1,
                    title = "Flaky Article",
                    url = "https://blog.example.com/flaky",
                    discussionUrl = "https://news.ycombinator.com/item?id=2001",
                    fetchedAt = "2026-11-23T19:00:00Z",
                ),
                BoardItemEntity(
                    boardId = boardId,
                    externalId = "2002",
                    rank = 2,
                    title = "Attention Paper",
                    url = "https://arxiv.org/abs/1706.03762",
                    discussionUrl = "https://news.ycombinator.com/item?id=2002",
                    fetchedAt = "2026-11-23T19:00:00Z",
                ),
            )
        )
    }

    private fun sampleItemView(boardId: String, externalId: String, url: String): BoardItemView =
        BoardItemView(
            boardId = boardId,
            externalId = externalId,
            rank = 1,
            title = "Sample $externalId",
            url = url,
            discussionUrl = "https://news.ycombinator.com/item?id=$externalId",
            author = "alice",
            domain = null,
            publishedAt = null,
            score = 40,
            commentCount = 10,
            summary = null,
            selfTextHtml = null,
        )

    private fun singleStoryTransport(): HttpTransport = HttpTransport { req ->
        when (req.url) {
            HackerNewsSection.TOP.apiStoriesUrl -> HttpResponse(200, "[1001]")
            "https://hacker-news.firebaseio.com/v0/item/1001.json" ->
                HttpResponse(
                    200,
                    """{"id":1001,"title":"Story","url":"https://example.com/1","score":10,"descendants":2}""",
                )
            else -> HttpResponse(404, "not found")
        }
    }
}

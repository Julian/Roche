@file:Suppress("StringLiteralDuplication", "LargeClass")

package com.grayvines.roche.data

import com.grayvines.roche.core.DiscussionLink
import com.grayvines.roche.core.SCHEME_HACKER_NEWS
import com.grayvines.roche.core.TOMBSTONE_ENTITY_COLLECTION_ENTRY
import com.grayvines.roche.core.TOMBSTONE_ENTITY_CONSUMPTION_LOG
import com.grayvines.roche.core.TOMBSTONE_TYPE_BOARD
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.core.boardNaturalKey
import com.grayvines.roche.core.collectionEntryNaturalKey
import com.grayvines.roche.core.coreJson
import com.grayvines.roche.core.deterministicBoardId
import com.grayvines.roche.core.deterministicItemId
import com.grayvines.roche.core.mergeDiscussionIntoMetadata
import com.grayvines.roche.core.mergeEntryMetadataJson
import com.grayvines.roche.core.parseDiscussionLinks
import com.grayvines.roche.core.parseMetadataJsonObject
import com.grayvines.roche.data.boards.BoardAdapterType
import com.grayvines.roche.data.boards.BoardPromoteAction
import com.grayvines.roche.data.boards.HackerNewsSection
import com.grayvines.roche.data.local.BoardEntity
import com.grayvines.roche.data.local.BoardItemEntity
import com.grayvines.roche.data.local.BoardItemStateEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.pruneExpiredBoardStatesInDao
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.HttpTransport
import com.grayvines.roche.data.remote.NetworkTier
import com.grayvines.roche.data.remote.PeerNodeDto
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.remote.SyncBoardDto
import com.grayvines.roche.data.remote.SyncBoardItemDto
import com.grayvines.roche.data.remote.SyncBoardItemStateDto
import com.grayvines.roche.data.remote.SyncTombstoneDto
import com.grayvines.roche.data.remote.buildPeerSyncRequestPayload
import com.grayvines.roche.data.remote.parsePeerSyncResponseJson
import com.grayvines.roche.data.sync.PeerSyncRequestConfig
import com.grayvines.roche.data.sync.buildTrackedPeerSyncRequest
import com.grayvines.roche.data.sync.reconcilePeerSyncResponse
import com.grayvines.roche.data.sync.shouldPeerPollBoard
import com.grayvines.roche.data.sync.stripEntriesFor413Retry
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BoardSyncAndStorageTest {
    @TempDir lateinit var tempDir: File

    private class FakeBoardTransport {
        var articleFetches = 0

        val transport = HttpTransport { req -> handleUrl(req.url) }

        private fun handleUrl(url: String): HttpResponse =
            when (url) {
                HackerNewsSection.TOP.apiStoriesUrl -> HttpResponse(200, "[1001, 1002, 1003, 1004]")
                "https://hacker-news.firebaseio.com/v0/item/1001.json" ->
                    HttpResponse(
                        200,
                        """{"id":1001,"title":"Deep Dive Post","url":"https://blog.example.com/deep","by":"alice","score":210,"descendants":84,"time":1730000000}""",
                    )
                "https://hacker-news.firebaseio.com/v0/item/1002.json" ->
                    HttpResponse(
                        200,
                        """{"id":1002,"title":"Ask HN: Best SQLite tricks?","by":"bob","text":"<p>Share your favorite pragmas.</p>","score":150,"descendants":60,"time":1730000100}""",
                    )
                "https://hacker-news.firebaseio.com/v0/item/1003.json" ->
                    HttpResponse(
                        200,
                        """{"id":1003,"title":"Attention Paper","url":"https://arxiv.org/abs/1706.03762","by":"carol","score":500,"descendants":200,"time":1730000200}""",
                    )
                "https://hacker-news.firebaseio.com/v0/item/1004.json" ->
                    HttpResponse(
                        200,
                        """{"id":1004,"title":"Page 2 Story","url":"https://page2.example.com/post","by":"dave","score":45,"descendants":9,"time":1730000300}""",
                    )
                "https://blog.example.com/deep" -> serveArticle()
                else -> HttpResponse(404, "not found")
            }

        private fun serveArticle(): HttpResponse {
            articleFetches++
            return HttpResponse(
                200,
                "<html><head><title>Deep Dive</title></head><body><article><h1>Deep Dive</h1>" +
                    "<p>Full extracted article body text for testing.</p></article></body></html>",
            )
        }
    }

    @Test
    fun `board create reader cache dismiss undismiss and live page invariants hold`() = runTest {
        val env = TestRocheEnv(tempDir, "board_reader_test.preferences_pb")
        val fake = FakeBoardTransport()
        val repo = mockRepository(env.database, env.settings, transport = fake.transport)
        try {
            val created =
                repo
                    .createBoard(BoardAdapterType.HACKER_NEWS, "https://news.ycombinator.com/")
                    .getOrThrow()
            assertEquals("Hacker News", created.title)
            assertEquals(4, created.visibleItemCount)
            assertFalse(env.database.dao().getAllEntries().any { it.title == "Deep Dive Post" })

            val reader1 = repo.loadBoardItemReader(created.id, "1001").getOrThrow()
            assertFalse(reader1.isSelfPost)
            assertTrue(reader1.contentHtml.orEmpty().contains("Full extracted article body"))
            assertEquals(1, fake.articleFetches)
            assertEquals(
                listOf("https://news.ycombinator.com/item?id=1001"),
                reader1.discussions.map { it.url },
            )
            assertNotNull(env.database.dao().getBoardItemState(created.id, "1001")?.seenAt)
            assertFalse(env.database.dao().getAllEntries().any { it.title == "Deep Dive Post" })

            repo.loadBoardItemReader(created.id, "1001").getOrThrow()
            assertEquals(1, fake.articleFetches)

            val askReader = repo.loadBoardItemReader(created.id, "1002").getOrThrow()
            assertTrue(askReader.isSelfPost)
            assertTrue(askReader.contentHtml.orEmpty().contains("favorite pragmas"))

            assertTrue(repo.dismissBoardItem(created.id, "1001"))
            val snapDismissed = repo.snapshot.first()
            assertEquals(3, snapDismissed.boards.single().visibleItemCount)
            assertEquals(1, snapDismissed.boards.single().dismissedItemCount)
            assertTrue(repo.undismissBoardItem(created.id, "1001"))
            assertEquals(4, repo.snapshot.first().boards.single().visibleItemCount)

            val livePage = repo.fetchLiveBoardPage(created.id, offset = 3, limit = 2).getOrThrow()
            assertEquals(listOf("1004"), livePage.items.map { it.externalId })
            assertTrue(livePage.items.single().isEphemeralPageItem)
            assertFalse(livePage.hasMore)
        } finally {
            env.close()
        }
    }

    @Test
    fun `board promotion creates permanent entry with inboxState none and canonical dedup`() =
        runTest {
            val env = TestRocheEnv(tempDir, "board_promote_test.preferences_pb")
            val fake = FakeBoardTransport()
            val repo = mockRepository(env.database, env.settings, transport = fake.transport)
            try {
                val created = repo.createBoard(BoardAdapterType.HACKER_NEWS, "top").getOrThrow()
                val dao = env.database.dao()

                val queuePromote =
                    repo.promoteBoardItem(created.id, "1001", BoardPromoteAction.QUEUE).getOrThrow()
                assertFalse(queuePromote.wasExistingEntry)
                assertEquals(
                    deterministicItemId(null, "https://blog.example.com/deep"),
                    queuePromote.entryId,
                )
                assertEquals("none", dao.getEntryStateById(queuePromote.entryId)?.inboxState)
                assertEquals(
                    queuePromote.entryId,
                    dao.getEntryIdByIdentifier(SCHEME_HACKER_NEWS, "1001"),
                )
                val snapQueued = repo.snapshot.first()
                val entry = snapQueued.entries.first { it.id == queuePromote.entryId }
                assertFalse(entry.inFeedInbox)
                assertEquals(
                    listOf("https://news.ycombinator.com/item?id=1001"),
                    entry.discussions.map { it.url },
                )
                val itemView =
                    snapQueued.boardItems.getValue(created.id).first { it.externalId == "1001" }
                assertEquals(queuePromote.entryId, itemView.linkedEntryId)
                assertTrue(itemView.isInQueue && !itemView.isStarred)

                val starPromote =
                    repo.promoteBoardItem(created.id, "1001", BoardPromoteAction.STAR).getOrThrow()
                assertTrue(starPromote.wasExistingEntry)
                assertEquals(queuePromote.entryId, starPromote.entryId)
                verifyArxivCanonicalDedupPromote(repo, dao, created.id)
            } finally {
                env.close()
            }
        }

    private suspend fun verifyArxivCanonicalDedupPromote(
        repo: RocheRepository,
        dao: RocheDao,
        boardId: String,
    ) {
        repo.addUrlToWantToRead(
            inputUrl = "https://arxiv.org/pdf/1706.03762.pdf",
            titleOverride = "Attention Is All You Need",
        )
        val arxivView =
            repo.snapshot.first().boardItems.getValue(boardId).first { it.externalId == "1003" }
        assertNotNull(arxivView.linkedEntryId)
        assertTrue(arxivView.isInQueue)
        val finishArxiv =
            repo
                .promoteBoardItem(
                    boardId = boardId,
                    externalId = "1003",
                    action = BoardPromoteAction.FINISH,
                    notes = "Classic transformer paper",
                )
                .getOrThrow()
        assertTrue(finishArxiv.wasExistingEntry)
        assertEquals(arxivView.linkedEntryId, finishArxiv.entryId)
        val finishedView =
            repo.snapshot.first().boardItems.getValue(boardId).first { it.externalId == "1003" }
        assertTrue(finishedView.isFinished)
        assertFalse(finishedView.isInQueue)
        assertNotNull(
            dao.getTombstone(
                TOMBSTONE_ENTITY_COLLECTION_ENTRY,
                collectionEntryNaturalKey(WANT_TO_READ_COLLECTION_ID, finishArxiv.entryId),
            )
        )

        repo.unfinishBoardItem(boardId, "1003", finishArxiv.entryId).getOrThrow()
        val unfinishedView =
            repo.snapshot.first().boardItems.getValue(boardId).first { it.externalId == "1003" }
        assertFalse(unfinishedView.isFinished)
        assertFalse(unfinishedView.isInQueue)
        assertNull(dao.getEntryStateById(finishArxiv.entryId)?.readAt)
        assertTrue(dao.getAllTombstones().any { it.entityType == TOMBSTONE_ENTITY_CONSUMPTION_LOG })
    }

    @Test
    fun `pruneExpiredBoardStates keeps in-snapshot and within-48h states without tombstones`() =
        runTest {
            ManagedTestDatabase().use { managed ->
                val dao = managed.database.dao()
                val boardId = seedBoardForPruning(dao)
                val now = Instant.parse("2026-11-23T19:00:00Z")

                val pruned = pruneExpiredBoardStatesInDao(dao, now)
                assertEquals(1, pruned)
                assertNotNull(dao.getBoardItemState(boardId, "in-snap"))
                assertNotNull(dao.getBoardItemState(boardId, "within-grace"))
                assertNotNull(dao.getBoardItemState(boardId, "recently-updated"))
                assertNull(dao.getBoardItemState(boardId, "expired"))
                assertTrue(dao.getAllTombstones().isEmpty(), "pruning must not record tombstones")
            }
        }

    @Test
    fun `metadata discussions CRDT union-merges by canonical URL regardless of HLC winner`() {
        val existing =
            mergeDiscussionIntoMetadata(
                "{}",
                DiscussionLink(
                    source = "hacker_news",
                    url = "https://news.ycombinator.com/item?id=111",
                    externalId = "111",
                    label = "",
                ),
            )
        val incomingJson =
            """{"discussions":[{"source":"hacker_news",""" +
                """"url":"https://NEWS.YCOMBINATOR.COM/item?id=111",""" +
                """"external_id":"111","label":"Hacker News"},""" +
                """{"source":"lobsters","url":"https://lobste.rs/s/abc123",""" +
                """"external_id":"abc123","label":"Lobsters"}],"extra":"from-remote"}"""
        val incoming = parseMetadataJsonObject(incomingJson)
        val mergedPassive = mergeEntryMetadataJson(existing, incoming, incomingWins = false)
        val links = parseDiscussionLinks(mergedPassive)
        assertEquals(2, links.size)
        assertEquals("https://news.ycombinator.com/item?id=111", links[0].url)
        assertEquals("Hacker News", links[0].label)
        assertEquals("https://lobste.rs/s/abc123", links[1].url)
        assertEquals("Lobsters", links[1].label)
        assertEquals(
            "from-remote",
            parseMetadataJsonObject(mergedPassive)?.get("extra")?.toString()?.trim('"'),
        )
    }

    @Test
    fun `shouldPeerPollBoard enforces HRW single winner phase schedule and rescue rules`() {
        val board =
            BoardEntity(
                id = deterministicBoardId("hacker_news", "top"),
                adapterType = "hacker_news",
                sourceKey = "top",
                sourceUrl = "https://news.ycombinator.com/",
                title = "Hacker News",
                pollInterval = 30,
                isActive = true,
                createdAt = "2026-11-23T10:00:00Z",
                updatedAt = "2026-11-23T10:00:00Z",
            )
        val nowSecs = Instant.parse("2026-11-23T19:00:00Z").epochSecond
        val peers =
            listOf(
                PeerNodeDto("server", "Server", "wired", 1.0, "2026-11-23T18:59:30Z"),
                PeerNodeDto("android-1", "Pixel", "wifi", 1.0, "2026-11-23T18:59:30Z"),
                PeerNodeDto("android-2", "Tablet", "wifi", 1.0, "2026-11-23T18:59:30Z"),
            )

        assertFalse(shouldPeerPollBoard("android-1", peers, board.copy(isActive = false), nowSecs))
        val winners = peers.filter { shouldPeerPollBoard(it.peerId, peers, board, nowSecs) }
        assertEquals(1, winners.size)

        val recentlyPolled =
            board.copy(lastPolledAt = "2026-11-23T18:59:00Z", lastPolledBy = "server")
        assertTrue(peers.none { shouldPeerPollBoard(it.peerId, peers, recentlyPolled, nowSecs) })

        val failedOnce =
            recentlyPolled.copy(
                lastError = "HTTP 503",
                lastErrorBy = "server",
                consecutiveFailures = 1,
            )
        assertTrue(
            listOf("android-1", "android-2").any {
                shouldPeerPollBoard(it, peers, failedOnce, nowSecs)
            }
        )
    }

    @Test
    fun `bidirectional PeerSyncOut and PeerSyncIn round-trip boards snapshots states and acks`() =
        runTest {
            ManagedTestDatabase().use { peerADb ->
                ManagedTestDatabase().use { peerBDb ->
                    val daoA = peerADb.database.dao()
                    val daoB = peerBDb.database.dao()
                    val boardId = seedDirtyBoardOnPeerA(daoA)

                    val batchA =
                        buildTrackedPeerSyncRequest(
                            dao = daoA,
                            config =
                                PeerSyncRequestConfig(
                                    peerId = "peer-a",
                                    deviceName = "Pixel A",
                                    networkTier = NetworkTier.WIFI,
                                    sinceSeq = 5L,
                                ),
                        )
                    assertEquals(1, batchA.request.boards.size)
                    assertEquals(1, batchA.request.boards.single().snapshotItems?.size)
                    assertEquals(1, batchA.request.boardItemStates.size)
                    val wirePayload = buildPeerSyncRequestPayload(batchA.request)
                    assertTrue(wirePayload.contains(""""boards""""))
                    assertTrue(wirePayload.contains(""""board_item_states""""))
                    assertTrue(wirePayload.contains(""""snapshot_items""""))
                    assertNull(
                        stripEntriesFor413Retry(batchA.request).boards.single().snapshotItems
                    )

                    val boardsJson = coreJson.encodeToString(batchA.request.boards)
                    val statesJson = coreJson.encodeToString(batchA.request.boardItemStates)
                    val simJson =
                        """{"server_peer_id":"server","sync_seq":6,""" +
                            """"server_hlc":"001795460800000-0000-server",""" +
                            """"sync_timestamp":"2026-11-23T19:06:00Z",""" +
                            """"boards":$boardsJson,"board_item_states":$statesJson}"""
                    reconcilePeerSyncResponse(daoB, parsePeerSyncResponseJson(simJson), "peer-b")

                    val boardOnB = daoB.getBoardById(boardId)!!
                    assertEquals("HN Front Page", boardOnB.title)
                    assertFalse(boardOnB.isLocalDirty || boardOnB.isSnapshotDirty)
                    assertEquals(1, daoB.countBoardItems(boardId))
                    assertEquals(
                        "2026-11-23T19:05:00Z",
                        daoB.getBoardItemState(boardId, "42123456")?.seenAt,
                    )

                    val ackResp =
                        PeerSyncResponseDto(
                            serverPeerId = "server",
                            syncSeq = 6L,
                            serverHlc = "001795460800000-0000-server",
                            syncTimestamp = "2026-11-23T19:06:00Z",
                        )
                    reconcilePeerSyncResponse(daoA, ackResp, "peer-a", sentBatch = batchA)
                    val ackedA = daoA.getBoardById(boardId)!!
                    assertFalse(ackedA.isLocalDirty || ackedA.isSnapshotDirty)
                    assertFalse(daoA.getBoardItemState(boardId, "42123456")!!.isLocalDirty)
                }
            }
        }

    @Test
    fun `PeerSyncIn handles equal-HLC state join expired state filtering and board tombstones`() =
        runTest {
            ManagedTestDatabase().use { peerBDb ->
                val daoB = peerBDb.database.dao()
                val boardId = seedDirtyBoardOnPeerA(daoB)

                reconcilePeerSyncResponse(
                    dao = daoB,
                    response =
                        PeerSyncResponseDto(
                            serverPeerId = "server",
                            syncSeq = 7L,
                            serverHlc = "001795460900000-0000-server",
                            syncTimestamp = "2026-11-23T19:10:00Z",
                            boardItemStates =
                                listOf(
                                    SyncBoardItemStateDto(
                                        boardId = boardId,
                                        externalId = "42123456",
                                        seenAt = null,
                                        dismissedAt = "2026-11-23T19:08:00Z",
                                        lastSeenInSnapshotAt = "2026-11-23T19:00:00Z",
                                        updatedAt = "2026-11-23T19:08:00Z",
                                        hlc = "001795460700000-0000-peer-a",
                                    ),
                                    SyncBoardItemStateDto(
                                        boardId = boardId,
                                        externalId = "ancient-999",
                                        seenAt = "2020-01-01T00:00:00Z",
                                        dismissedAt = "2020-01-01T00:00:00Z",
                                        lastSeenInSnapshotAt = "2020-01-01T00:00:00Z",
                                        updatedAt = "2020-01-01T00:00:00Z",
                                        hlc = "001577836800000-0000-server",
                                    ),
                                ),
                        ),
                    localPeerId = "peer-b",
                )
                val joined = daoB.getBoardItemState(boardId, "42123456")!!
                assertEquals("2026-11-23T19:05:00Z", joined.seenAt)
                assertEquals("2026-11-23T19:08:00Z", joined.dismissedAt)
                assertNull(daoB.getBoardItemState(boardId, "ancient-999"))

                verifyBoardTombstoneAndResurrection(daoB, boardId)
            }
        }

    private suspend fun verifyBoardTombstoneAndResurrection(daoB: RocheDao, boardId: String) {
        verifyBoardTombstoneSuppressesStale(daoB, boardId)
        verifyBoardResurrectionClearsTombstone(daoB, boardId)
    }

    private suspend fun verifyBoardTombstoneSuppressesStale(daoB: RocheDao, boardId: String) {
        val tombHlc = "001795461000000-0000-server"
        reconcilePeerSyncResponse(
            dao = daoB,
            response =
                PeerSyncResponseDto(
                    serverPeerId = "server",
                    syncSeq = 8L,
                    serverHlc = tombHlc,
                    syncTimestamp = "2026-11-23T19:15:00Z",
                    tombstones =
                        listOf(
                            SyncTombstoneDto(
                                entityType = TOMBSTONE_TYPE_BOARD,
                                naturalKey = boardNaturalKey("hacker_news", "top"),
                                deletedAt = "2026-11-23T19:15:00Z",
                                hlc = tombHlc,
                            )
                        ),
                    boards =
                        listOf(
                            SyncBoardDto(
                                id = boardId,
                                adapterType = "hacker_news",
                                sourceKey = "top",
                                sourceUrl = "https://news.ycombinator.com/",
                                title = "Stale Resurrection",
                                hlc = "001795460400000-0000-peer-a",
                            )
                        ),
                ),
            localPeerId = "peer-b",
        )
        assertNull(daoB.getBoardById(boardId))
        assertTrue(daoB.getBoardItemsForBoard(boardId).isEmpty())
        assertTrue(daoB.getBoardItemStatesForBoard(boardId).isEmpty())
    }

    private suspend fun verifyBoardResurrectionClearsTombstone(daoB: RocheDao, boardId: String) {
        val recreateHlc = "001795462000000-0000-server"
        reconcilePeerSyncResponse(
            dao = daoB,
            response =
                PeerSyncResponseDto(
                    serverPeerId = "server",
                    syncSeq = 9L,
                    serverHlc = recreateHlc,
                    syncTimestamp = "2026-11-23T19:30:00Z",
                    boards =
                        listOf(
                            SyncBoardDto(
                                id = boardId,
                                adapterType = "hacker_news",
                                sourceKey = "top",
                                sourceUrl = "https://news.ycombinator.com/",
                                title = "Reborn HN",
                                snapshotFetchedAt = "2026-11-23T19:30:00Z",
                                snapshotHlc = recreateHlc,
                                hlc = recreateHlc,
                                snapshotItems =
                                    listOf(
                                        SyncBoardItemDto(
                                            externalId = "555",
                                            rank = 1,
                                            title = "Reborn Item",
                                            url = "https://reborn.example.com",
                                            fetchedAt = "2026-11-23T19:30:00Z",
                                        )
                                    ),
                            )
                        ),
                ),
            localPeerId = "peer-b",
        )
        assertEquals("Reborn HN", daoB.getBoardById(boardId)?.title)
        assertEquals(1, daoB.countBoardItems(boardId))
        assertNull(daoB.getTombstone(TOMBSTONE_TYPE_BOARD, boardNaturalKey("hacker_news", "top")))
    }

    private suspend fun seedBoardForPruning(dao: RocheDao): String {
        val boardId = deterministicBoardId("hacker_news", "top")
        dao.upsertBoard(
            BoardEntity(
                id = boardId,
                adapterType = "hacker_news",
                sourceKey = "top",
                sourceUrl = "https://news.ycombinator.com/",
                title = "Hacker News",
                createdAt = "2026-11-01T00:00:00Z",
                updatedAt = "2026-11-01T00:00:00Z",
                hlc = "001",
            )
        )
        dao.insertBoardItems(
            listOf(
                BoardItemEntity(
                    boardId = boardId,
                    externalId = "in-snap",
                    rank = 1,
                    title = "Still Here",
                    url = "https://example.com/1",
                    fetchedAt = "2026-11-23T18:00:00Z",
                )
            )
        )
        dao.upsertBoardItemStates(
            listOf(
                testBoardState(
                    boardId,
                    "in-snap",
                    "2026-11-10T00:00:00Z",
                    "2026-11-10T00:00:00Z",
                    "001",
                ),
                testBoardState(
                    boardId,
                    "within-grace",
                    "2026-11-21T20:00:00Z",
                    "2026-11-21T20:00:00Z",
                    "002",
                ),
                testBoardState(
                    boardId,
                    "recently-updated",
                    "2026-11-21T07:00:00Z",
                    "2026-11-23T09:00:00Z",
                    "003",
                ),
                testBoardState(
                    boardId,
                    "expired",
                    "2026-11-21T18:00:00Z",
                    "2026-11-21T18:00:00Z",
                    "004",
                ),
            )
        )
        return boardId
    }

    private fun testBoardState(
        boardId: String,
        externalId: String,
        lastSeen: String,
        updated: String,
        hlc: String,
    ): BoardItemStateEntity =
        BoardItemStateEntity(
            boardId = boardId,
            externalId = externalId,
            seenAt = updated,
            lastSeenInSnapshotAt = lastSeen,
            updatedAt = updated,
            hlc = hlc,
        )

    private suspend fun seedDirtyBoardOnPeerA(dao: RocheDao): String {
        val boardId = deterministicBoardId("hacker_news", "top")
        dao.upsertBoard(
            BoardEntity(
                id = boardId,
                adapterType = "hacker_news",
                sourceKey = "top",
                sourceUrl = "https://news.ycombinator.com/",
                sourceConfigJson = """{"section":"top"}""",
                title = "HN Front Page",
                orderMode = "ranked",
                pollInterval = 30,
                isActive = true,
                lastPolledAt = "2026-11-23T19:00:00Z",
                lastPolledBy = "peer-a",
                snapshotFetchedAt = "2026-11-23T19:00:00Z",
                snapshotHlc = "001795460400000-0001-peer-a",
                createdAt = "2026-11-23T18:00:00Z",
                updatedAt = "2026-11-23T19:00:00Z",
                hlc = "001795460400000-0000-peer-a",
                isLocalDirty = true,
                isSnapshotDirty = true,
            )
        )
        dao.insertBoardItems(
            listOf(
                BoardItemEntity(
                    boardId = boardId,
                    externalId = "42123456",
                    rank = 1,
                    title = "Synced Story",
                    url = "https://example.com/synced",
                    discussionUrl = "https://news.ycombinator.com/item?id=42123456",
                    author = "pg",
                    domain = "example.com",
                    score = 250,
                    commentCount = 142,
                    fetchedAt = "2026-11-23T19:00:00Z",
                )
            )
        )
        dao.upsertBoardItemState(
            BoardItemStateEntity(
                boardId = boardId,
                externalId = "42123456",
                seenAt = "2026-11-23T19:05:00Z",
                dismissedAt = null,
                lastSeenInSnapshotAt = "2026-11-23T19:00:00Z",
                updatedAt = "2026-11-23T19:05:00Z",
                hlc = "001795460700000-0000-peer-a",
                isLocalDirty = true,
            )
        )
        return boardId
    }
}

package com.grayvines.roche.data

import com.grayvines.roche.core.deterministicBoardId
import com.grayvines.roche.data.boards.LocalBoardPoller
import com.grayvines.roche.data.local.BoardEntity
import com.grayvines.roche.data.local.BoardItemStateEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.PublicWebFetcher
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalBoardPollerTest {
    private class StubHnServer(var failNext: Boolean = false, var storySet: Int = 1) {
        val fetcher = PublicWebFetcher { req ->
            when {
                failNext -> HttpResponse(statusCode = 503, body = "unavailable")
                req.url.endsWith("topstories.json") ->
                    HttpResponse(200, if (storySet == 1) "[100, 200]" else "[200, 300]")
                req.url.endsWith("100.json") ->
                    HttpResponse(
                        200,
                        """{"id":100,"title":"Story 100","url":"https://a.example.com/100","score":42,"descendants":10}""",
                    )
                req.url.endsWith("200.json") ->
                    HttpResponse(
                        200,
                        """{"id":200,"title":"Story 200","url":"https://b.example.com/200","score":99,"descendants":25}""",
                    )
                else ->
                    HttpResponse(
                        200,
                        """{"id":300,"title":"Ask HN: 300","text":"<p>Body</p>","score":12,"descendants":4}""",
                    )
            }
        }
    }

    @Test
    fun `pollBoard replaces snapshot atomically and touches in-snapshot states`() = runTest {
        ManagedTestDatabase().use { managed ->
            val dao = managed.database.dao()
            val board = seedBoardWithStates(dao)
            val server = StubHnServer()

            val res1 =
                LocalBoardPoller.pollBoard(
                    dao = dao,
                    publicWebFetcher = server.fetcher,
                    board = board,
                    localPeerId = "peer-a",
                    nowInstant = Instant.parse("2026-11-23T19:00:00Z"),
                    writeLock = managed.database.writeLock,
                )
            assertEquals(2, res1.getOrThrow())

            val afterPoll1 = dao.getBoardById(board.id)!!
            assertEquals(board.hlc, afterPoll1.hlc)
            assertTrue(afterPoll1.snapshotHlc.isNotBlank())
            assertTrue(afterPoll1.isLocalDirty && afterPoll1.isSnapshotDirty)
            assertEquals("peer-a", afterPoll1.lastPolledBy)
            assertEquals(0, afterPoll1.consecutiveFailures)
            assertNull(afterPoll1.lastError)
            assertEquals(
                listOf("100", "200"),
                dao.getBoardItemsForBoard(board.id).map { it.externalId },
            )
            assertEquals(
                "2026-11-23T19:00:00.000Z",
                dao.getBoardItemState(board.id, "100")?.lastSeenInSnapshotAt,
            )
            assertEquals(
                "2026-11-23T18:00:00Z",
                dao.getBoardItemState(board.id, "999")?.lastSeenInSnapshotAt,
            )

            server.storySet = 2
            val res2 =
                LocalBoardPoller.pollBoard(
                    dao = dao,
                    publicWebFetcher = server.fetcher,
                    board = afterPoll1,
                    localPeerId = "peer-a",
                    nowInstant = Instant.parse("2026-11-23T20:00:00Z"),
                    writeLock = managed.database.writeLock,
                )
            assertEquals(2, res2.getOrThrow())
            val afterPoll2 = dao.getBoardById(board.id)!!
            assertTrue(afterPoll2.snapshotHlc > afterPoll1.snapshotHlc)
            assertEquals(
                listOf("200", "300"),
                dao.getBoardItemsForBoard(board.id).map { it.externalId },
            )
            assertFalse(dao.getAllEntries().any { it.title == "Story 200" })
        }
    }

    @Test
    fun `pollBoard records failure backoff without wiping existing snapshot`() = runTest {
        ManagedTestDatabase().use { managed ->
            val dao = managed.database.dao()
            val board = seedBoardWithStates(dao)
            val server = StubHnServer()

            LocalBoardPoller.pollBoard(
                    dao = dao,
                    publicWebFetcher = server.fetcher,
                    board = board,
                    localPeerId = "peer-a",
                    nowInstant = Instant.parse("2026-11-23T19:00:00Z"),
                    writeLock = managed.database.writeLock,
                )
                .getOrThrow()
            val afterPoll1 = dao.getBoardById(board.id)!!

            server.failNext = true
            val resFail =
                LocalBoardPoller.pollBoard(
                    dao = dao,
                    publicWebFetcher = server.fetcher,
                    board = afterPoll1,
                    localPeerId = "peer-a",
                    nowInstant = Instant.parse("2026-11-23T19:30:00Z"),
                    writeLock = managed.database.writeLock,
                )
            assertTrue(resFail.isFailure)
            val afterFail = dao.getBoardById(board.id)!!
            assertEquals(1, afterFail.consecutiveFailures)
            assertEquals("HTTP 503", afterFail.lastError)
            assertEquals("peer-a", afterFail.lastErrorBy)
            assertEquals(2, dao.countBoardItems(board.id))

            server.failNext = false
            LocalBoardPoller.pollBoard(
                    dao = dao,
                    publicWebFetcher = server.fetcher,
                    board = afterFail,
                    localPeerId = "peer-a",
                    nowInstant = Instant.parse("2026-11-23T20:00:00Z"),
                    writeLock = managed.database.writeLock,
                )
                .getOrThrow()
            val recovered = dao.getBoardById(board.id)!!
            assertEquals(0, recovered.consecutiveFailures)
            assertNull(recovered.lastError)
        }
    }

    @Test
    fun `touchBoardStatesInSnapshot compares ISO timestamps chronologically across Z and millis`() =
        runTest {
            ManagedTestDatabase().use { managed ->
                val dao = managed.database.dao()
                val board = seedBoardWithStates(dao)
                dao.upsertBoardItemState(
                    BoardItemStateEntity(
                        boardId = board.id,
                        externalId = "100",
                        seenAt = "2026-11-23T19:00:00Z",
                        dismissedAt = null,
                        lastSeenInSnapshotAt = "2026-11-23T19:00:00Z",
                        updatedAt = "2026-11-23T19:00:00Z",
                        hlc = "001795460400000-0000-peer-a",
                    )
                )
                val server = StubHnServer()
                LocalBoardPoller.pollBoard(
                        dao = dao,
                        publicWebFetcher = server.fetcher,
                        board = board,
                        localPeerId = "peer-a",
                        nowInstant = Instant.parse("2026-11-23T19:00:00.500Z"),
                        writeLock = managed.database.writeLock,
                    )
                    .getOrThrow()
                assertEquals(
                    "2026-11-23T19:00:00.500Z",
                    dao.getBoardItemState(board.id, "100")?.lastSeenInSnapshotAt,
                )
            }
        }

    private suspend fun seedBoardWithStates(dao: RocheDao): BoardEntity {
        val boardId = deterministicBoardId("hacker_news", "top")
        val board =
            BoardEntity(
                id = boardId,
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
        dao.upsertBoardItemStates(
            listOf(
                BoardItemStateEntity(
                    boardId = boardId,
                    externalId = "100",
                    seenAt = "2026-11-23T18:10:00Z",
                    dismissedAt = null,
                    lastSeenInSnapshotAt = "2026-11-23T18:00:00Z",
                    updatedAt = "2026-11-23T18:10:00Z",
                    hlc = "001795460100000-0000-peer-a",
                ),
                BoardItemStateEntity(
                    boardId = boardId,
                    externalId = "999",
                    seenAt = "2026-11-23T18:10:00Z",
                    dismissedAt = "2026-11-23T18:11:00Z",
                    lastSeenInSnapshotAt = "2026-11-23T18:00:00Z",
                    updatedAt = "2026-11-23T18:11:00Z",
                    hlc = "001795460100001-0000-peer-a",
                ),
            )
        )
        return board
    }
}

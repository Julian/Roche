package com.grayvines.roche.data.boards

import com.grayvines.roche.core.DEFAULT_LOCAL_HLC_NODE
import com.grayvines.roche.core.MAX_CONSECUTIVE_FAILURES
import com.grayvines.roche.core.formatUtcMillisIso
import com.grayvines.roche.core.isSyncablePublicUrl
import com.grayvines.roche.core.normalizeConsecutiveFailures
import com.grayvines.roche.data.local.BoardEntity
import com.grayvines.roche.data.local.BoardItemEntity
import com.grayvines.roche.data.local.DbWriteLock
import com.grayvines.roche.data.local.MAX_SQLITE_HTML_CHARS
import com.grayvines.roche.data.local.MAX_SQLITE_SUMMARY_CHARS
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.nextLocalHlc
import com.grayvines.roche.data.remote.HttpStatusException
import com.grayvines.roche.data.remote.ProblemDetailsException
import com.grayvines.roche.data.remote.PublicWebFetcher
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_BOARD_ERROR_CHARS = 240

internal object LocalBoardPoller {
    @Suppress("LongParameterList")
    suspend fun pollBoard(
        dao: RocheDao,
        publicWebFetcher: PublicWebFetcher,
        board: BoardEntity,
        localPeerId: String,
        adapterRegistry: BoardAdapterRegistry = BoardAdapterRegistry.default(publicWebFetcher),
        nowInstant: Instant? = null,
        clock: () -> Instant = { Instant.now().truncatedTo(ChronoUnit.SECONDS) },
        writeLock: DbWriteLock = DbWriteLock(),
    ): Result<Int> =
        withContext(Dispatchers.Default) {
            pollBoardInternal(
                dao = dao,
                adapterRegistry = adapterRegistry,
                board = board,
                localPeerId = localPeerId,
                resolveNow = { nowInstant ?: clock() },
                writeLock = writeLock,
            )
        }

    @Suppress("LongParameterList")
    private suspend fun pollBoardInternal(
        dao: RocheDao,
        adapterRegistry: BoardAdapterRegistry,
        board: BoardEntity,
        localPeerId: String,
        resolveNow: () -> Instant,
        writeLock: DbWriteLock,
    ): Result<Int> {
        val effectiveNodeId = localPeerId.ifBlank { DEFAULT_LOCAL_HLC_NODE }
        val syncableBoard = isSyncablePublicUrl(board.sourceUrl)
        val adapter =
            adapterRegistry.resolve(board.adapterType)
                ?: return Result.failure(
                    IllegalArgumentException("Unsupported board adapter: ${board.adapterType}")
                )

        val batch =
            adapter
                .fetchSnapshot(
                    sourceKey = board.sourceKey,
                    sourceConfigJson = board.sourceConfigJson,
                    limit = BOARD_SNAPSHOT_BUFFER_LIMIT,
                )
                .getOrElse { error ->
                    recordBoardPollFailure(
                        dao = dao,
                        boardId = board.id,
                        localPeerId = localPeerId,
                        completedIso = formatUtcMillisIso(resolveNow()),
                        errorMessage = sanitizeBoardFetchError(error),
                        syncableBoard = syncableBoard,
                        writeLock = writeLock,
                    )
                    return Result.failure(error)
                }

        val nowIso = formatUtcMillisIso(resolveNow())
        val storedCount =
            persistPolledSnapshotLocked(
                dao = dao,
                writeLock = writeLock,
                boardId = board.id,
                batch = batch,
                nowIso = nowIso,
                localPeerId = localPeerId,
                syncableBoard = syncableBoard,
                nodeId = effectiveNodeId,
            )
        return Result.success(storedCount)
    }

    @Suppress("LongParameterList")
    private suspend fun persistPolledSnapshotLocked(
        dao: RocheDao,
        writeLock: DbWriteLock,
        boardId: String,
        batch: BoardPollBatch,
        nowIso: String,
        localPeerId: String,
        syncableBoard: Boolean,
        nodeId: String,
    ): Int = writeLock.withLock {
        var count = 0
        dao.executeInTransaction {
            val fresh = dao.getBoardById(boardId)
            if (fresh != null) {
                val snapshotHlc = nextLocalHlc(dao, nodeId)
                val items =
                    batch.items.mapIndexed { index, candidate ->
                        BoardItemEntity(
                            boardId = fresh.id,
                            externalId = candidate.externalId,
                            rank = index + 1,
                            title = candidate.title,
                            url = candidate.url,
                            discussionUrl = candidate.discussionUrl,
                            author = candidate.author,
                            domain = candidate.domain,
                            publishedAt = candidate.publishedAt,
                            score = candidate.score,
                            commentCount = candidate.commentCount,
                            summary = candidate.summary?.take(MAX_SQLITE_SUMMARY_CHARS),
                            selfTextHtml = candidate.selfTextHtml?.take(MAX_SQLITE_HTML_CHARS),
                            metadataJson = candidate.metadataJson.ifBlank { "{}" },
                            fetchedAt = nowIso,
                        )
                    }
                dao.replaceBoardItemsInTx(fresh.id, items, nowIso)
                dao.upsertBoard(
                    fresh.copy(
                        lastPolledAt = nowIso,
                        lastPolledBy = localPeerId,
                        lastError = null,
                        lastErrorBy = null,
                        consecutiveFailures = 0,
                        snapshotFetchedAt = nowIso,
                        snapshotHlc = snapshotHlc,
                        updatedAt = nowIso,
                        isLocalDirty = syncableBoard || fresh.isLocalDirty,
                        isSnapshotDirty = syncableBoard || fresh.isSnapshotDirty,
                    )
                )
                count = items.size
            }
        }
        count
    }

    @Suppress("LongParameterList")
    private suspend fun recordBoardPollFailure(
        dao: RocheDao,
        boardId: String,
        localPeerId: String,
        completedIso: String,
        errorMessage: String,
        syncableBoard: Boolean,
        writeLock: DbWriteLock,
    ) {
        writeLock.withLock {
            dao.executeInTransaction {
                val fresh = dao.getBoardById(boardId)
                if (fresh != null) {
                    val prevFailures =
                        normalizeConsecutiveFailures(fresh.lastError, fresh.consecutiveFailures)
                    val nextFailures = (prevFailures + 1).coerceAtMost(MAX_CONSECUTIVE_FAILURES)
                    dao.upsertBoard(
                        fresh.copy(
                            lastPolledAt = completedIso,
                            lastError = errorMessage,
                            lastErrorBy = localPeerId,
                            consecutiveFailures = nextFailures,
                            isLocalDirty = syncableBoard || fresh.isLocalDirty,
                        )
                    )
                }
            }
        }
    }

    private fun sanitizeBoardFetchError(error: Throwable): String =
        when (error) {
            is HttpStatusException -> {
                "HTTP ${error.statusCode}"
            }
            is ProblemDetailsException -> {
                "HTTP ${error.statusCode}"
            }
            else -> {
                val firstLine =
                    error.message
                        ?.lineSequence()
                        ?.firstOrNull()
                        ?.trim()
                        ?.take(MAX_BOARD_ERROR_CHARS)
                        ?.takeIf { it.isNotEmpty() }
                firstLine ?: "Board fetch failed"
            }
        }
}

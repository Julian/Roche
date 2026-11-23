@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data.sync

import com.grayvines.roche.core.TOMBSTONE_TYPE_BOARD
import com.grayvines.roche.core.boardNaturalKey
import com.grayvines.roche.core.deterministicBoardId
import com.grayvines.roche.core.isHttpOrHttpsUrl
import com.grayvines.roche.core.isLocalPollNewer
import com.grayvines.roche.core.newerIsoTimestamp
import com.grayvines.roche.core.normalizeConsecutiveFailures
import com.grayvines.roche.core.parseIsoInstant
import com.grayvines.roche.data.boards.BOARD_STATE_GRACE_HOURS
import com.grayvines.roche.data.boards.BoardAdapterType
import com.grayvines.roche.data.boards.BoardOrderMode
import com.grayvines.roche.data.local.BoardEntity
import com.grayvines.roche.data.local.BoardItemEntity
import com.grayvines.roche.data.local.BoardItemStateEntity
import com.grayvines.roche.data.local.MAX_SQLITE_HTML_CHARS
import com.grayvines.roche.data.local.MAX_SQLITE_SUMMARY_CHARS
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.remote.SyncBoardDto
import com.grayvines.roche.data.remote.SyncBoardItemDto
import com.grayvines.roche.data.remote.SyncBoardItemStateDto
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

internal suspend fun applyBoardTombstoneInTx(
    dao: RocheDao,
    rawKey: String,
    deletedAt: String,
    tombHlc: String,
) {
    val (canonKey, match) = resolveBoardTombstoneTarget(dao, rawKey)
    if (match != null) {
        if (compareHlc(match.hlc, tombHlc) > 0) return
        dao.deleteBoardById(match.id)
    }
    upsertCleanTombstone(dao, TOMBSTONE_TYPE_BOARD, canonKey, deletedAt, tombHlc)
}

private suspend fun resolveBoardTombstoneTarget(
    dao: RocheDao,
    rawKey: String,
): Pair<String, BoardEntity?> {
    val parts = rawKey.split('\u0000')
    return if (parts.size == 2) {
        val adapter = parts[0].trim().lowercase(Locale.ROOT)
        val sourceKey = parts[1].trim().lowercase(Locale.ROOT)
        val canonKey = boardNaturalKey(adapter, sourceKey)
        val match =
            dao.getBoardBySourceKey(adapter, sourceKey)
                ?: dao.getBoardById(deterministicBoardId(adapter, sourceKey))
        canonKey to match
    } else {
        rawKey to dao.getBoardById(rawKey)
    }
}

internal suspend fun mergeIncomingBoardsInTx(
    dao: RocheDao,
    response: PeerSyncResponseDto,
    serverNode: String,
    nowIso: String,
    nowMs: Long,
) {
    if (response.boards.isEmpty() && response.boardItemStates.isEmpty()) return
    val boardIdRemap = mergeBoardListInTx(dao, response.boards, serverNode, nowIso, nowMs)
    if (response.boardItemStates.isNotEmpty()) {
        mergeBoardItemStatesInTx(
            dao = dao,
            incoming = response.boardItemStates,
            boardIdRemap = boardIdRemap,
            serverNode = serverNode,
            nowIso = nowIso,
            nowMs = nowMs,
        )
    }
}

private suspend fun mergeBoardListInTx(
    dao: RocheDao,
    incoming: List<SyncBoardDto>,
    serverNode: String,
    nowIso: String,
    nowMs: Long,
): Map<String, String> {
    if (incoming.isEmpty()) return emptyMap()
    val boardTombstones =
        dao.getAllTombstones()
            .filter { it.entityType.equals(TOMBSTONE_TYPE_BOARD, ignoreCase = true) }
            .associateByTo(HashMap()) { it.naturalKey }
    val existingBoards = dao.getAllBoards()
    val existingById = existingBoards.associateByTo(HashMap()) { it.id }
    val existingByNatKey =
        existingBoards.associateByTo(HashMap()) { boardNaturalKey(it.adapterType, it.sourceKey) }
    val remap = HashMap<String, String>()

    for (dto in incoming) {
        mergeSingleIncomingBoardInTx(
            dao = dao,
            dto = dto,
            boardTombstones = boardTombstones,
            existingById = existingById,
            existingByNatKey = existingByNatKey,
            remap = remap,
            serverNode = serverNode,
            nowIso = nowIso,
            nowMs = nowMs,
        )
    }
    return remap
}

@Suppress("LongParameterList")
private suspend fun mergeSingleIncomingBoardInTx(
    dao: RocheDao,
    dto: SyncBoardDto,
    boardTombstones: MutableMap<String, TombstoneEntity>,
    existingById: MutableMap<String, BoardEntity>,
    existingByNatKey: MutableMap<String, BoardEntity>,
    remap: MutableMap<String, String>,
    serverNode: String,
    nowIso: String,
    nowMs: Long,
) {
    val adapterType = BoardAdapterType.fromWireValue(dto.adapterType)?.wireValue ?: return
    val sourceKey = dto.sourceKey.trim().lowercase(Locale.ROOT)
    val sourceUrl = dto.sourceUrl.trim()
    if (sourceKey.isEmpty() || sourceUrl.isEmpty() || !isHttpOrHttpsUrl(sourceUrl)) return

    val natKey = boardNaturalKey(adapterType, sourceKey)
    val incomingId = dto.id.trim().ifEmpty { deterministicBoardId(adapterType, sourceKey) }
    val configHlc =
        resolveOrSynthesizeHlc(dto.hlc, dto.updatedAt ?: dto.createdAt, nowIso, serverNode, nowMs)
    if (isSuppressedByBoardTombstone(dao, boardTombstones, natKey, incomingId, configHlc)) {
        return
    }

    val existing = existingByNatKey[natKey] ?: existingById[incomingId]
    val targetId = existing?.id ?: incomingId
    if (targetId != incomingId) {
        remap[incomingId] = targetId
    }
    val incomingSnapHlc =
        dto.snapshotHlc
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { raw ->
                resolveOrSynthesizeHlc(raw, dto.snapshotFetchedAt, nowIso, serverNode, nowMs)
            }
            .orEmpty()
    val shouldReplaceSnapshot =
        shouldReplaceBoardSnapshot(dao, targetId, existing, dto.snapshotItems, incomingSnapHlc)
    val mergedBoard =
        buildMergedBoardEntity(
            targetId = targetId,
            adapterType = adapterType,
            sourceKey = sourceKey,
            sourceUrl = sourceUrl,
            dto = dto,
            existing = existing,
            configHlc = configHlc,
            incomingSnapHlc = incomingSnapHlc,
            shouldReplaceSnapshot = shouldReplaceSnapshot,
            nowIso = nowIso,
        )
    dao.upsertBoard(mergedBoard)
    existingById[targetId] = mergedBoard
    existingByNatKey[boardNaturalKey(mergedBoard.adapterType, mergedBoard.sourceKey)] = mergedBoard

    if (shouldReplaceSnapshot && dto.snapshotItems != null) {
        val fetchedAt = mergedBoard.snapshotFetchedAt?.takeIf { it.isNotBlank() } ?: nowIso
        val itemEntities = mapIncomingBoardItems(targetId, dto.snapshotItems, fetchedAt)
        dao.replaceBoardItemsInTx(targetId, itemEntities, fetchedAt)
    }
}

private suspend fun isSuppressedByBoardTombstone(
    dao: RocheDao,
    boardTombstones: MutableMap<String, TombstoneEntity>,
    natKey: String,
    incomingId: String,
    configHlc: String,
): Boolean {
    val keys = listOf(natKey, incomingId).distinct()
    val staleKeys = ArrayList<String>(keys.size)
    for (key in keys) {
        val tomb = boardTombstones[key] ?: continue
        if (compareHlc(tomb.hlc, configHlc) >= 0) return true
        staleKeys.add(key)
    }
    for (staleKey in staleKeys) {
        dao.deleteTombstone(TOMBSTONE_TYPE_BOARD, staleKey)
        boardTombstones.remove(staleKey)
    }
    return false
}

private suspend fun shouldReplaceBoardSnapshot(
    dao: RocheDao,
    targetId: String,
    existing: BoardEntity?,
    incomingItems: List<SyncBoardItemDto>?,
    incomingSnapHlc: String,
): Boolean {
    if (incomingItems == null || incomingSnapHlc.isEmpty()) return false
    val existingSnapHlc = existing?.snapshotHlc.orEmpty()
    if (existingSnapHlc.isBlank()) return true
    val cmp = compareHlc(incomingSnapHlc, existingSnapHlc)
    return cmp > 0 || cmp == 0 && dao.countBoardItems(targetId) == 0
}

private data class MergedBoardTelemetry(
    val lastPolledAt: String?,
    val lastPolledBy: String?,
    val lastError: String?,
    val lastErrorBy: String?,
    val consecutiveFailures: Int,
)

private fun resolveNewBoardTelemetry(dto: SyncBoardDto): MergedBoardTelemetry {
    val incomingError = dto.lastError?.trim()?.takeIf { it.isNotEmpty() }
    val errorBy = incomingError?.let { dto.lastErrorBy?.trim()?.takeIf { s -> s.isNotEmpty() } }
    return MergedBoardTelemetry(
        lastPolledAt = dto.lastPolledAt?.trim()?.takeIf { it.isNotEmpty() },
        lastPolledBy = dto.lastPolledBy?.trim()?.takeIf { it.isNotEmpty() },
        lastError = incomingError,
        lastErrorBy = errorBy,
        consecutiveFailures = normalizeConsecutiveFailures(incomingError, dto.consecutiveFailures),
    )
}

private fun resolveExistingBoardTelemetry(
    existing: BoardEntity,
    dto: SyncBoardDto,
    configWins: Boolean,
): MergedBoardTelemetry {
    val incomingError = dto.lastError?.trim()?.takeIf { it.isNotEmpty() }
    val localPollNewer = isLocalPollNewer(existing.lastPolledAt, dto.lastPolledAt)
    val incomingPollNewer = isLocalPollNewer(dto.lastPolledAt, existing.lastPolledAt)
    val localPollWins = localPollNewer || !incomingPollNewer && !configWins
    val lastPolledAt =
        if (localPollWins) {
            existing.lastPolledAt
        } else {
            dto.lastPolledAt?.trim()?.takeIf { it.isNotEmpty() }
        }
    val lastPolledBy =
        if (localPollWins) {
            existing.lastPolledBy
        } else {
            dto.lastPolledBy?.trim()?.takeIf { it.isNotEmpty() }
        }
    val isRemoteUnarchive = !incomingPollNewer && configWins && !existing.isActive && dto.isActive
    if (isRemoteUnarchive && incomingError == null) {
        return MergedBoardTelemetry(lastPolledAt, lastPolledBy, null, null, 0)
    }
    val cleanError =
        if (localPollWins) {
            existing.lastError?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            incomingError
        }
    val rawErrorBy = if (localPollWins) existing.lastErrorBy else dto.lastErrorBy
    val rawFailures = if (localPollWins) existing.consecutiveFailures else dto.consecutiveFailures
    return MergedBoardTelemetry(
        lastPolledAt = lastPolledAt,
        lastPolledBy = lastPolledBy,
        lastError = cleanError,
        lastErrorBy = cleanError?.let { rawErrorBy?.trim()?.takeIf { s -> s.isNotEmpty() } },
        consecutiveFailures = normalizeConsecutiveFailures(cleanError, rawFailures),
    )
}

@Suppress("LongParameterList")
private fun buildMergedBoardEntity(
    targetId: String,
    adapterType: String,
    sourceKey: String,
    sourceUrl: String,
    dto: SyncBoardDto,
    existing: BoardEntity?,
    configHlc: String,
    incomingSnapHlc: String,
    shouldReplaceSnapshot: Boolean,
    nowIso: String,
): BoardEntity =
    if (existing == null) {
        buildNewBoardEntity(
            targetId = targetId,
            adapterType = adapterType,
            sourceKey = sourceKey,
            sourceUrl = sourceUrl,
            dto = dto,
            configHlc = configHlc,
            incomingSnapHlc = incomingSnapHlc,
            shouldReplaceSnapshot = shouldReplaceSnapshot,
            nowIso = nowIso,
        )
    } else {
        mergeExistingBoardEntity(
            existing = existing,
            adapterType = adapterType,
            sourceKey = sourceKey,
            sourceUrl = sourceUrl,
            dto = dto,
            configHlc = configHlc,
            incomingSnapHlc = incomingSnapHlc,
            shouldReplaceSnapshot = shouldReplaceSnapshot,
            nowIso = nowIso,
        )
    }

@Suppress("LongParameterList")
private fun buildNewBoardEntity(
    targetId: String,
    adapterType: String,
    sourceKey: String,
    sourceUrl: String,
    dto: SyncBoardDto,
    configHlc: String,
    incomingSnapHlc: String,
    shouldReplaceSnapshot: Boolean,
    nowIso: String,
): BoardEntity {
    val telemetry = resolveNewBoardTelemetry(dto)
    val snapshotFetchedAt =
        if (shouldReplaceSnapshot) {
            dto.snapshotFetchedAt?.trim()?.takeIf { it.isNotEmpty() } ?: nowIso
        } else {
            null
        }
    return BoardEntity(
        id = targetId,
        adapterType = adapterType,
        sourceKey = sourceKey,
        sourceUrl = sourceUrl,
        sourceConfigJson = dto.sourceConfigJson.trim().ifEmpty { "{}" },
        title = dto.title.trim().ifEmpty { sourceKey },
        orderMode = BoardOrderMode.fromWireValue(dto.orderMode).wireValue,
        pollInterval = dto.pollInterval.coerceAtLeast(1),
        isActive = dto.isActive,
        lastPolledAt = telemetry.lastPolledAt,
        lastPolledBy = telemetry.lastPolledBy,
        lastError = telemetry.lastError,
        lastErrorBy = telemetry.lastErrorBy,
        consecutiveFailures = telemetry.consecutiveFailures,
        snapshotFetchedAt = snapshotFetchedAt,
        snapshotHlc = if (shouldReplaceSnapshot) incomingSnapHlc else "",
        createdAt = dto.createdAt?.trim()?.takeIf { it.isNotEmpty() } ?: nowIso,
        updatedAt = dto.updatedAt?.trim()?.takeIf { it.isNotEmpty() } ?: nowIso,
        hlc = configHlc,
        isLocalDirty = false,
        isSnapshotDirty = false,
    )
}

@Suppress("LongParameterList")
private fun mergeExistingBoardEntity(
    existing: BoardEntity,
    adapterType: String,
    sourceKey: String,
    sourceUrl: String,
    dto: SyncBoardDto,
    configHlc: String,
    incomingSnapHlc: String,
    shouldReplaceSnapshot: Boolean,
    nowIso: String,
): BoardEntity {
    val configWins = shouldIncomingWinHlc(configHlc, existing.hlc, existing.isLocalDirty)
    val telemetry = resolveExistingBoardTelemetry(existing, dto, configWins)
    val baseConfig =
        if (configWins) {
            mergeBoardConfigFields(
                existing = existing,
                sameSourceIdentity =
                    existing.adapterType == adapterType && existing.sourceKey == sourceKey,
                sourceUrl = sourceUrl,
                dto = dto,
                nowIso = nowIso,
            )
        } else {
            existing
        }
    val nextSnapFetchedAt =
        if (shouldReplaceSnapshot) {
            dto.snapshotFetchedAt?.trim()?.takeIf { it.isNotEmpty() } ?: nowIso
        } else {
            existing.snapshotFetchedAt
        }
    return baseConfig.copy(
        lastPolledAt = telemetry.lastPolledAt,
        lastPolledBy = telemetry.lastPolledBy,
        lastError = telemetry.lastError,
        lastErrorBy = telemetry.lastErrorBy,
        consecutiveFailures = telemetry.consecutiveFailures,
        snapshotFetchedAt = nextSnapFetchedAt,
        snapshotHlc = if (shouldReplaceSnapshot) incomingSnapHlc else existing.snapshotHlc,
        hlc = maxHlc(configHlc, existing.hlc),
        isSnapshotDirty = !shouldReplaceSnapshot && existing.isSnapshotDirty,
    )
}

private fun mergeBoardConfigFields(
    existing: BoardEntity,
    sameSourceIdentity: Boolean,
    sourceUrl: String,
    dto: SyncBoardDto,
    nowIso: String,
): BoardEntity {
    val nextSourceUrl = if (sameSourceIdentity) sourceUrl else existing.sourceUrl
    val nextConfigJson =
        if (sameSourceIdentity) {
            dto.sourceConfigJson.trim().ifEmpty { "{}" }
        } else {
            existing.sourceConfigJson
        }
    return existing.copy(
        sourceUrl = nextSourceUrl,
        sourceConfigJson = nextConfigJson,
        title = dto.title.trim().ifEmpty { existing.title },
        orderMode = BoardOrderMode.fromWireValue(dto.orderMode).wireValue,
        pollInterval = dto.pollInterval.coerceAtLeast(1),
        isActive = dto.isActive,
        updatedAt = dto.updatedAt?.trim()?.takeIf { it.isNotEmpty() } ?: nowIso,
        isLocalDirty = false,
    )
}

private fun mapIncomingBoardItems(
    boardId: String,
    incoming: List<SyncBoardItemDto>,
    defaultFetchedAt: String,
): List<BoardItemEntity> =
    incoming
        .asSequence()
        .filter { item ->
            item.externalId.isNotBlank() && item.title.isNotBlank() && isHttpOrHttpsUrl(item.url)
        }
        .sortedBy { it.rank.coerceAtLeast(1) }
        .distinctBy { it.externalId.trim() }
        .mapIndexed { idx, item ->
            BoardItemEntity(
                boardId = boardId,
                externalId = item.externalId.trim(),
                rank = idx + 1,
                title = item.title.trim(),
                url = item.url.trim(),
                discussionUrl = item.discussionUrl?.trim()?.takeIf(::isHttpOrHttpsUrl),
                author = item.author?.trim()?.takeIf { it.isNotEmpty() },
                domain = item.domain?.trim()?.takeIf { it.isNotEmpty() },
                publishedAt = item.publishedAt?.trim()?.takeIf { it.isNotEmpty() },
                score = item.score,
                commentCount = item.commentCount,
                summary = item.summary?.take(MAX_SQLITE_SUMMARY_CHARS),
                selfTextHtml = item.selfTextHtml?.take(MAX_SQLITE_HTML_CHARS),
                metadataJson = item.metadataJson.ifBlank { "{}" },
                fetchedAt = item.fetchedAt.trim().ifEmpty { defaultFetchedAt },
            )
        }
        .toList()

@Suppress("LongParameterList")
private suspend fun mergeBoardItemStatesInTx(
    dao: RocheDao,
    incoming: List<SyncBoardItemStateDto>,
    boardIdRemap: Map<String, String>,
    serverNode: String,
    nowIso: String,
    nowMs: Long,
) {
    val knownBoardIds = dao.getAllBoards().mapTo(HashSet()) { it.id }
    if (knownBoardIds.isEmpty()) return
    val liveItemKeys = dao.getAllBoardItems().mapTo(HashSet()) { it.boardId to it.externalId }
    val existingByKey =
        dao.getAllBoardItemStates().associateByTo(HashMap()) { it.boardId to it.externalId }
    val cutoffInstant = Instant.ofEpochMilli(nowMs).minus(BOARD_STATE_GRACE_HOURS, ChronoUnit.HOURS)
    val toUpsert = ArrayList<BoardItemStateEntity>()

    for (dto in incoming) {
        val merged =
            resolveMergedIncomingState(
                dto = dto,
                boardIdRemap = boardIdRemap,
                knownBoardIds = knownBoardIds,
                liveItemKeys = liveItemKeys,
                existingByKey = existingByKey,
                cutoffInstant = cutoffInstant,
                serverNode = serverNode,
                nowIso = nowIso,
                nowMs = nowMs,
            )
        if (merged != null) {
            existingByKey[merged.boardId to merged.externalId] = merged
            toUpsert.add(merged)
        }
    }
    if (toUpsert.isNotEmpty()) {
        dao.upsertBoardItemStates(toUpsert)
    }
}

@Suppress("LongParameterList")
private fun resolveMergedIncomingState(
    dto: SyncBoardItemStateDto,
    boardIdRemap: Map<String, String>,
    knownBoardIds: Set<String>,
    liveItemKeys: Set<Pair<String, String>>,
    existingByKey: Map<Pair<String, String>, BoardItemStateEntity>,
    cutoffInstant: Instant,
    serverNode: String,
    nowIso: String,
    nowMs: Long,
): BoardItemStateEntity? {
    val rawBoardId = dto.boardId.trim()
    val boardId = boardIdRemap[rawBoardId] ?: rawBoardId
    val externalId = dto.externalId.trim()
    if (boardId.isEmpty() || externalId.isEmpty() || boardId !in knownBoardIds) return null

    val key = boardId to externalId
    val lastSeenIso =
        dto.lastSeenInSnapshotAt.trim().ifEmpty { dto.updatedAt.trim().ifEmpty { nowIso } }
    val updatedIso = dto.updatedAt.trim().ifEmpty { lastSeenIso }
    if (key !in liveItemKeys && isExpiredBoardItemState(lastSeenIso, updatedIso, cutoffInstant)) {
        return null
    }
    val stateHlc = resolveOrSynthesizeHlc(dto.hlc, updatedIso, nowIso, serverNode, nowMs)
    return mergeSingleBoardItemState(
        boardId = boardId,
        externalId = externalId,
        dto = dto,
        existing = existingByKey[key],
        stateHlc = stateHlc,
        lastSeenIso = lastSeenIso,
        updatedIso = updatedIso,
        nowIso = nowIso,
    )
}

private fun isExpiredBoardItemState(
    lastSeenIso: String,
    updatedIso: String,
    cutoffInstant: Instant,
): Boolean {
    val latest = newerIsoTimestamp(lastSeenIso, updatedIso)?.let(::parseIsoInstant) ?: return false
    return latest.isBefore(cutoffInstant)
}

@Suppress("LongParameterList")
private fun mergeSingleBoardItemState(
    boardId: String,
    externalId: String,
    dto: SyncBoardItemStateDto,
    existing: BoardItemStateEntity?,
    stateHlc: String,
    lastSeenIso: String,
    updatedIso: String,
    nowIso: String,
): BoardItemStateEntity? {
    val incomingSeen = dto.seenAt?.trim()?.takeIf { it.isNotEmpty() }
    val incomingDismissed = dto.dismissedAt?.trim()?.takeIf { it.isNotEmpty() }
    val mergedLastSeen = newerIsoTimestamp(existing?.lastSeenInSnapshotAt, lastSeenIso) ?: nowIso
    if (existing == null) {
        return BoardItemStateEntity(
            boardId = boardId,
            externalId = externalId,
            seenAt = incomingSeen,
            dismissedAt = incomingDismissed,
            lastSeenInSnapshotAt = mergedLastSeen,
            updatedAt = updatedIso,
            hlc = stateHlc,
            isLocalDirty = false,
        )
    }
    val cmp = if (existing.hlc.isBlank()) 1 else compareHlc(stateHlc, existing.hlc)
    return when {
        cmp > 0 ->
            BoardItemStateEntity(
                boardId = boardId,
                externalId = externalId,
                seenAt = incomingSeen,
                dismissedAt = incomingDismissed,
                lastSeenInSnapshotAt = mergedLastSeen,
                updatedAt = updatedIso,
                hlc = stateHlc,
                isLocalDirty = false,
            )
        cmp == 0 ->
            mergeEqualHlcBoardItemState(
                existing = existing,
                incomingSeen = incomingSeen,
                incomingDismissed = incomingDismissed,
                mergedLastSeen = mergedLastSeen,
                updatedIso = updatedIso,
            )
        mergedLastSeen != existing.lastSeenInSnapshotAt ->
            existing.copy(lastSeenInSnapshotAt = mergedLastSeen)
        else -> null
    }
}

private fun mergeEqualHlcBoardItemState(
    existing: BoardItemStateEntity,
    incomingSeen: String?,
    incomingDismissed: String?,
    mergedLastSeen: String,
    updatedIso: String,
): BoardItemStateEntity {
    val joinedSeen = newerIsoTimestamp(existing.seenAt, incomingSeen)
    val joinedDismissed = newerIsoTimestamp(existing.dismissedAt, incomingDismissed)
    val joinedUpdated = newerIsoTimestamp(existing.updatedAt, updatedIso) ?: updatedIso
    val matchesIncoming =
        sameBoardOptTs(joinedSeen, incomingSeen) &&
            sameBoardOptTs(joinedDismissed, incomingDismissed)
    return BoardItemStateEntity(
        boardId = existing.boardId,
        externalId = existing.externalId,
        seenAt = joinedSeen,
        dismissedAt = joinedDismissed,
        lastSeenInSnapshotAt = mergedLastSeen,
        updatedAt = joinedUpdated,
        hlc = existing.hlc,
        isLocalDirty = existing.isLocalDirty || !matchesIncoming,
    )
}

private fun sameBoardOptTs(a: String?, b: String?): Boolean {
    val cleanA = a?.trim()?.takeIf { it.isNotEmpty() }
    val cleanB = b?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanA == cleanB) return true
    if (cleanA == null || cleanB == null) return false
    val instA = parseIsoInstant(cleanA)
    val instB = parseIsoInstant(cleanB)
    return instA != null && instB != null && instA == instB
}

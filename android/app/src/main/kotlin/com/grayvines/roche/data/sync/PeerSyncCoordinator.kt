package com.grayvines.roche.data.sync

import com.grayvines.roche.core.FLAG_LOCAL_HLC
import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.data.boards.BoardAdapterRegistry
import com.grayvines.roche.data.boards.LocalBoardPoller
import com.grayvines.roche.data.catalog.CatalogStore
import com.grayvines.roche.data.feeds.LocalFeedPoller
import com.grayvines.roche.data.local.BoardEntity
import com.grayvines.roche.data.local.DbWriteLock
import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.LocalSyncFlagEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.pruneExpiredBoardStatesInDao
import com.grayvines.roche.data.remote.NetworkTier
import com.grayvines.roche.data.remote.PeerNodeDto
import com.grayvines.roche.data.remote.PeerSyncRequestDto
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.remote.ProblemDetailsException
import com.grayvines.roche.data.remote.PublicWebFetcher
import com.grayvines.roche.data.remote.ResponseTooLargeException
import com.grayvines.roche.data.remote.RocheProblemTypes
import com.grayvines.roche.data.remote.RocheServerApi
import com.grayvines.roche.data.settings.Settings
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

internal const val MAX_DELTA_CONTINUATION_PAGES = 20
internal const val FLAG_LOCAL_THROUGHPUT_WEIGHT = "local_throughput_weight"
internal const val MIN_SERVER_PAYLOAD_BYTES = 1_024
private const val MAX_OVERFLOW_PUSH_ROUNDS = 25
private const val HTTP_PAYLOAD_TOO_LARGE = 413
private const val COOPERATIVE_POLL_SLICE_SIZE = 4
private const val NANOS_PER_SECOND = 1_000_000_000.0

internal data class FeedPollSample(
    val itemsProcessed: Int,
    val elapsedSeconds: Double,
)

internal data class CooperativePollOutcome(
    val polledFeedsCount: Int,
    val attemptedFeedsCount: Int,
    val measuredWeight: Double?,
)

internal data class SinglePeerSyncResult(
    val response: PeerSyncResponseDto,
    val sentEntryIds: Set<String> = emptySet(),
    val byteOverflowEntryIds: Set<String> = emptySet(),
    val droppedEntryIdsOn413: Set<String> = emptySet(),
    val serverMaxPayloadBytes: Long? = null,
) {
    val droppedEntriesOn413: Boolean
        get() = droppedEntryIdsOn413.isNotEmpty()
}

internal fun parseSyncSeqCursor(rawCursor: String?): Long =
    rawCursor?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

internal fun shouldAdvanceSyncSeq(previousCursor: String?, candidateSeq: Long): Boolean {
    if (candidateSeq < 0L) return false
    val prevParsed = previousCursor?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()
    return prevParsed == null || candidateSeq >= prevParsed
}

@Suppress("LongParameterList")
internal class PeerSyncCoordinator(
    private val dao: RocheDao,
    private val serverApi: RocheServerApi,
    private val publicWebFetcher: PublicWebFetcher = PublicWebFetcher(),
    private val networkTier: NetworkTier = NetworkTier.WIFI,
    private val catalogStore: CatalogStore? = null,
    private val writeLock: DbWriteLock = catalogStore?.writeLock ?: DbWriteLock(),
    private val onPeersUpdated: (List<PeerNodeDto>) -> Unit = {},
    private val onSyncCursorUpdated: suspend (String) -> Unit = {},
) {
    internal suspend fun syncAndCooperativePoll(
        currentSettings: Settings,
        initialWeightOverride: Double? = null,
        sinceSeqOverride: Long? = null,
    ): Int? =
        withContext(Dispatchers.Default) {
            syncAndPollInternal(currentSettings, initialWeightOverride, sinceSeqOverride)
        }

    private data class SyncCycleOutcome(
        val settings: Settings,
        val latestPeers: List<PeerNodeDto>,
    )

    private data class SyncRoundCallResult(
        val response: PeerSyncResponseDto,
        val retriedRequestAfter413: PeerSyncRequestDto? = null,
        val serverMaxPayloadBytes: Long? = null,
    )

    private suspend fun syncAndPollInternal(
        currentSettings: Settings,
        initialWeightOverride: Double?,
        sinceSeqOverride: Long?,
    ): Int? {
        catalogStore?.ensureSystemSeeded()
        val initialCycle =
            runFullSyncCycle(
                currentSettings = currentSettings,
                weightOverride = initialWeightOverride,
                sinceSeqOverride = sinceSeqOverride,
            ) ?: return null

        writeLock.withLock {
            dao.executeInTransaction { pruneExpiredBoardStatesInDao(dao) }
        }

        val feedPollOutcome =
            if (networkTier != NetworkTier.OFFLINE) {
                pollCooperativeFeedsOnDevice(
                    dao = dao,
                    publicWebFetcher = publicWebFetcher,
                    localPeerId = initialCycle.settings.peerId,
                    activePeers = initialCycle.latestPeers,
                    writeLock = writeLock,
                )
            } else {
                null
            }
        val boardPollOutcome =
            if (networkTier != NetworkTier.OFFLINE) {
                pollCooperativeBoardsOnDevice(
                    dao = dao,
                    publicWebFetcher = publicWebFetcher,
                    localPeerId = initialCycle.settings.peerId,
                    activePeers = initialCycle.latestPeers,
                    writeLock = writeLock,
                )
            } else {
                null
            }
        val attemptedFeeds = feedPollOutcome?.attemptedFeedsCount ?: 0
        val attemptedBoards = boardPollOutcome?.attemptedFeedsCount ?: 0
        if (attemptedFeeds > 0 || attemptedBoards > 0) {
            val measuredWeight = boardPollOutcome?.measuredWeight ?: feedPollOutcome?.measuredWeight
            runPostPollPeerSync(initialCycle.settings, measuredWeight)
        }
        return dao.countItems()
    }

    private suspend fun runPostPollPeerSync(
        currentSettings: Settings,
        measuredWeight: Double?,
    ) {
        runFullSyncCycle(
            currentSettings = currentSettings,
            weightOverride = measuredWeight,
        )
    }

    private suspend fun runFullSyncCycle(
        currentSettings: Settings,
        weightOverride: Double?,
        sinceSeqOverride: Long? = null,
    ): SyncCycleOutcome? {
        var workingSettings = currentSettings
        var initialRound =
            executeSinglePeerSyncRound(
                currentSettings = workingSettings,
                weightOverride = weightOverride,
                sinceSeqOverride = sinceSeqOverride,
            ) ?: return null

        if (initialRound.response.resetRequired) {
            onSyncCursorUpdated("0")
            workingSettings = workingSettings.copy(lastPeerSyncCursor = "0")
            initialRound =
                executeSinglePeerSyncRound(
                    currentSettings = workingSettings,
                    weightOverride = weightOverride,
                    sinceSeqOverride = 0L,
                ) ?: return null
        }

        var latestPeers = initialRound.response.activePeers
        onPeersUpdated(latestPeers)
        if (initialRound.response.resetRequired) {
            return SyncCycleOutcome(settings = workingSettings, latestPeers = latestPeers)
        }
        workingSettings =
            applyCursorAndContinuePages(
                baseSettings = workingSettings,
                round = initialRound,
                weightOverride = weightOverride,
            ) { peers ->
                latestPeers = peers
                onPeersUpdated(peers)
            }
        workingSettings =
            drainOverflowEntryPushes(
                initialSettings = workingSettings,
                initialRound = initialRound,
                weightOverride = weightOverride,
            ) { peers ->
                latestPeers = peers
                onPeersUpdated(peers)
            }
        return SyncCycleOutcome(settings = workingSettings, latestPeers = latestPeers)
    }

    private suspend fun applyCursorAndContinuePages(
        baseSettings: Settings,
        round: SinglePeerSyncResult,
        weightOverride: Double?,
        onPagePeers: (List<PeerNodeDto>) -> Unit = onPeersUpdated,
    ): Settings {
        val firstResp = round.response
        var updatedSettings = advancePersistedSyncSeq(baseSettings, firstResp.syncSeq)
        var pageSeq = firstResp.syncSeq
        var hasMore = firstResp.hasMore && pageSeq > 0L
        var page = 0
        while (hasMore && page < MAX_DELTA_CONTINUATION_PAGES) {
            val pageResp =
                fetchContinuationPage(
                    currentSettings = updatedSettings,
                    sinceSeq = pageSeq,
                    weightOverride = weightOverride,
                )
            if (pageResp != null && !pageResp.resetRequired && pageResp.syncSeq > pageSeq) {
                onPagePeers(pageResp.activePeers)
                updatedSettings = advancePersistedSyncSeq(updatedSettings, pageResp.syncSeq)
                pageSeq = pageResp.syncSeq
                hasMore = pageResp.hasMore
            } else {
                hasMore = false
            }
            page++
        }
        return updatedSettings
    }

    private suspend fun advancePersistedSyncSeq(
        settings: Settings,
        candidateSeq: Long,
    ): Settings {
        if (!shouldAdvanceSyncSeq(settings.lastPeerSyncCursor, candidateSeq)) {
            return settings
        }
        val nextCursor = candidateSeq.toString()
        if (settings.lastPeerSyncCursor != nextCursor) {
            onSyncCursorUpdated(nextCursor)
        }
        return settings.copy(lastPeerSyncCursor = nextCursor)
    }

    private suspend fun fetchContinuationPage(
        currentSettings: Settings,
        sinceSeq: Long,
        weightOverride: Double?,
    ): PeerSyncResponseDto? {
        val effectiveWeight = if (networkTier == NetworkTier.OFFLINE) 0.0 else weightOverride
        val clientHlc = dao.getLocalSyncFlag(FLAG_LOCAL_HLC)
        val request =
            PeerSyncRequestDto(
                peerId = currentSettings.peerId,
                deviceName = currentSettings.deviceName,
                networkTier = networkTier.wireValue,
                weight = effectiveWeight,
                sinceSeq = sinceSeq,
                clientHlc = clientHlc,
            )
        val response =
            serverApi.syncPeer(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
                request = request,
            ) ?: return null
        if (!response.resetRequired) {
            reconcilePeerSyncResponse(
                dao = dao,
                response = response,
                localPeerId = currentSettings.peerId,
                sentBatch = TrackedOutgoingSyncBatch(request = request),
                catalogStore = catalogStore,
            )
        }
        return response
    }

    @Suppress("LongParameterList")
    private class OverflowDrainState(
        var settings: Settings,
        val alreadySentIds: MutableSet<String>,
        val quarantinedIds: MutableSet<String>,
        var remainingIds: Set<String>,
        var maxPayloadBytes: Int,
        var maxEntries: Int,
        var serverMaxPayloadBytes: Int?,
    ) {
        fun updateAfterRound(
            nextRound: SinglePeerSyncResult,
            prevCount: Int,
            prevMaxPayloadBytes: Int,
            prevMaxEntries: Int,
        ): Boolean {
            updateServerPayloadCap(nextRound.serverMaxPayloadBytes)
            val dropped = nextRound.droppedEntryIdsOn413
            return when {
                dropped.size == 1 -> {
                    quarantinedIds.addAll(dropped)
                    maxPayloadBytes = serverMaxPayloadBytes ?: MAX_PEER_SYNC_PAYLOAD_BYTES
                    maxEntries = MAX_PEER_SYNC_ENTRIES
                    remainingIds = nextRound.byteOverflowEntryIds - alreadySentIds - quarantinedIds
                    remainingIds.isNotEmpty()
                }
                dropped.size > 1 -> {
                    maxPayloadBytes =
                        compute413RetryPayloadBudget(
                            currentBudget = maxPayloadBytes,
                            serverMaxBytes = nextRound.serverMaxPayloadBytes,
                        )
                    maxEntries = minOf(maxEntries, dropped.size / 2).coerceAtLeast(1)
                    remainingIds = nextRound.byteOverflowEntryIds - alreadySentIds - quarantinedIds
                    remainingIds.isNotEmpty() && (prevMaxPayloadBytes > 1 || prevMaxEntries > 1)
                }
                else -> {
                    remainingIds = nextRound.byteOverflowEntryIds - alreadySentIds - quarantinedIds
                    remainingIds.isNotEmpty() && remainingIds.size < prevCount
                }
            }
        }

        private fun updateServerPayloadCap(reportedMaxBytes: Long?) {
            val normalized = clampServerMaxPayloadBytes(reportedMaxBytes) ?: return
            serverMaxPayloadBytes =
                serverMaxPayloadBytes?.let { existing -> minOf(existing, normalized) } ?: normalized
        }

        companion object {
            fun create(
                initialSettings: Settings,
                initialRound: SinglePeerSyncResult,
            ): OverflowDrainState {
                val alreadySentIds = initialRound.sentEntryIds.toMutableSet()
                val quarantinedIds = LinkedHashSet<String>()
                val initialServerCap =
                    clampServerMaxPayloadBytes(initialRound.serverMaxPayloadBytes)
                var maxPayloadBytes = initialServerCap ?: MAX_PEER_SYNC_PAYLOAD_BYTES
                var maxEntries = MAX_PEER_SYNC_ENTRIES
                val initialDropped = initialRound.droppedEntryIdsOn413
                when {
                    initialDropped.size == 1 -> {
                        quarantinedIds.addAll(initialDropped)
                    }
                    initialDropped.size > 1 -> {
                        maxPayloadBytes =
                            compute413RetryPayloadBudget(
                                currentBudget = MAX_PEER_SYNC_PAYLOAD_BYTES,
                                serverMaxBytes = initialRound.serverMaxPayloadBytes,
                            )
                        maxEntries = (initialDropped.size / 2).coerceAtLeast(1)
                    }
                }
                val remainingIds =
                    initialRound.byteOverflowEntryIds - alreadySentIds - quarantinedIds
                return OverflowDrainState(
                    settings = initialSettings,
                    alreadySentIds = alreadySentIds,
                    quarantinedIds = quarantinedIds,
                    remainingIds = remainingIds,
                    maxPayloadBytes = maxPayloadBytes,
                    maxEntries = maxEntries,
                    serverMaxPayloadBytes = initialServerCap,
                )
            }
        }
    }

    private suspend fun drainOverflowEntryPushes(
        initialSettings: Settings,
        initialRound: SinglePeerSyncResult,
        weightOverride: Double?,
        onPagePeers: (List<PeerNodeDto>) -> Unit,
    ): Settings {
        val state = OverflowDrainState.create(initialSettings, initialRound)
        var roundIdx = 0
        var keepDraining = state.remainingIds.isNotEmpty()
        while (keepDraining && roundIdx < MAX_OVERFLOW_PUSH_ROUNDS) {
            keepDraining = executeOverflowDrainStep(state, weightOverride, onPagePeers)
            roundIdx++
        }
        return state.settings
    }

    private suspend fun executeOverflowDrainStep(
        state: OverflowDrainState,
        weightOverride: Double?,
        onPagePeers: (List<PeerNodeDto>) -> Unit,
    ): Boolean {
        val prevCount = state.remainingIds.size
        val prevMaxPayloadBytes = state.maxPayloadBytes
        val prevMaxEntries = state.maxEntries
        val nextRound =
            executeSinglePeerSyncRound(
                currentSettings = state.settings,
                weightOverride = weightOverride,
                forcedEntryIds = state.remainingIds,
                excludeEntryIds = state.alreadySentIds,
                quarantinedEntryIds = state.quarantinedIds,
                maxPayloadBytes = state.maxPayloadBytes,
                maxEntries = state.maxEntries,
            )
        if (nextRound == null || nextRound.response.resetRequired) return false
        state.alreadySentIds.addAll(nextRound.sentEntryIds)
        onPagePeers(nextRound.response.activePeers)
        state.settings =
            applyCursorAndContinuePages(
                baseSettings = state.settings,
                round = nextRound,
                weightOverride = weightOverride,
                onPagePeers = onPagePeers,
            )
        return state.updateAfterRound(nextRound, prevCount, prevMaxPayloadBytes, prevMaxEntries)
    }

    @Suppress("LongParameterList")
    private suspend fun executeSinglePeerSyncRound(
        currentSettings: Settings,
        weightOverride: Double? = null,
        sinceSeqOverride: Long? = null,
        forcedEntryIds: Set<String> = emptySet(),
        excludeEntryIds: Set<String> = emptySet(),
        quarantinedEntryIds: Set<String> = emptySet(),
        maxPayloadBytes: Int = MAX_PEER_SYNC_PAYLOAD_BYTES,
        maxEntries: Int = MAX_PEER_SYNC_ENTRIES,
    ): SinglePeerSyncResult? {
        val effectiveSinceSeq =
            sinceSeqOverride ?: parseSyncSeqCursor(currentSettings.lastPeerSyncCursor)
        val tracked =
            buildTrackedPeerSyncRequest(
                dao = dao,
                config =
                    PeerSyncRequestConfig(
                        peerId = currentSettings.peerId,
                        deviceName = currentSettings.deviceName,
                        networkTier = networkTier,
                        sinceSeq = effectiveSinceSeq,
                        weightOverride = weightOverride,
                        forcedEntryIds = forcedEntryIds,
                        excludeEntryIds = excludeEntryIds,
                        quarantinedEntryIds = quarantinedEntryIds,
                        maxPayloadBytes = maxPayloadBytes,
                        maxEntries = maxEntries,
                    ),
            )
        val roundCall = callSyncPeerRound(currentSettings, tracked.request) ?: return null
        val response = roundCall.response
        val retriedRequestAfter413 = roundCall.retriedRequestAfter413
        if (response.resetRequired) {
            return SinglePeerSyncResult(response = response)
        }
        val droppedEntryIdsOn413 =
            if (retriedRequestAfter413 != null) tracked.sentEntryIds else emptySet()
        val effectiveTracked =
            if (retriedRequestAfter413 != null) {
                buildRetriedTrackedRequest(tracked, retriedRequestAfter413)
            } else {
                tracked
            }
        reconcilePeerSyncResponse(
            dao = dao,
            response = response,
            localPeerId = currentSettings.peerId,
            sentBatch = effectiveTracked,
            catalogStore = catalogStore,
        )
        return SinglePeerSyncResult(
            response = response,
            sentEntryIds = effectiveTracked.sentEntryIds,
            byteOverflowEntryIds = effectiveTracked.byteOverflowEntryIds,
            droppedEntryIdsOn413 = droppedEntryIdsOn413,
            serverMaxPayloadBytes = roundCall.serverMaxPayloadBytes,
        )
    }

    private fun buildRetriedTrackedRequest(
        tracked: TrackedOutgoingSyncBatch,
        retriedRequestAfter413: PeerSyncRequestDto,
    ): TrackedOutgoingSyncBatch {
        val droppedEntryIds = tracked.sentEntryIds
        val keptGenIds =
            retriedRequestAfter413.collectionGenerators.mapTo(HashSet()) { it.collectionId }
        val keptLogIds = retriedRequestAfter413.consumptionLog.mapTo(HashSet()) { it.id }
        val keptCompIds = retriedRequestAfter413.comparisons.mapTo(HashSet()) { it.id }
        return tracked.copy(
            request = retriedRequestAfter413,
            sentEntryHlcById = emptyMap(),
            sentStateHlcById = emptyMap(),
            sentFeedSourceHlcById = emptyMap(),
            sentMembershipHlcByKey =
                tracked.sentMembershipHlcByKey.filterKeys { (colId, entryId) ->
                    colId !in droppedEntryIds && entryId !in droppedEntryIds
                },
            sentGeneratorHlcById = tracked.sentGeneratorHlcById.filterKeys { it in keptGenIds },
            sentLogHlcById = tracked.sentLogHlcById.filterKeys { it in keptLogIds },
            sentComparisonHlcById = tracked.sentComparisonHlcById.filterKeys { it in keptCompIds },
            sentBoardSnapshotHlcById = emptyMap(),
            byteOverflowEntryIds = droppedEntryIds + tracked.byteOverflowEntryIds,
        )
    }

    private suspend fun callSyncPeerRound(
        currentSettings: Settings,
        request: PeerSyncRequestDto,
    ): SyncRoundCallResult? {
        val primary = runSuspendCatching {
            serverApi.syncPeer(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
                request = request,
            )
        }
        val error = primary.exceptionOrNull()
        val payloadProblem = (error as? ProblemDetailsException)?.takeIf(::isPayloadTooLargeProblem)
        val hasStrippablePayload =
            request.entries.isNotEmpty() || request.boards.any { !it.snapshotItems.isNullOrEmpty() }
        return when {
            error == null -> {
                primary.getOrNull()?.let { SyncRoundCallResult(response = it) }
            }
            payloadProblem != null && hasStrippablePayload -> {
                val strippedRequest = stripEntriesFor413Retry(request)
                serverApi
                    .syncPeer(
                        serverUrl = currentSettings.serverUrl,
                        token = currentSettings.bearerToken,
                        request = strippedRequest,
                    )
                    ?.let {
                        SyncRoundCallResult(
                            response = it,
                            retriedRequestAfter413 = strippedRequest,
                            serverMaxPayloadBytes = payloadProblem.maxBytes,
                        )
                    }
            }
            request.sinceSeq == 0L && isOversizedResponseError(error) -> {
                null
            }
            else -> {
                throw error
            }
        }
    }
}

internal fun stripEntriesFor413Retry(request: PeerSyncRequestDto): PeerSyncRequestDto {
    val droppedEntryIds = request.entries.mapTo(HashSet()) { it.id }
    return request.copy(
        entries = emptyList(),
        collectionEntries =
            request.collectionEntries.filter {
                it.collectionId !in droppedEntryIds && it.entryId !in droppedEntryIds
            },
        collectionGenerators =
            request.collectionGenerators.filter {
                it.collectionId !in droppedEntryIds && it.currentEntryId !in droppedEntryIds
            },
        consumptionLog = request.consumptionLog.filter { it.entryId !in droppedEntryIds },
        comparisons =
            request.comparisons.filter { comp ->
                comp.scopeCollectionId !in droppedEntryIds &&
                    comp.ordering.flatten().none { it in droppedEntryIds }
            },
        boards = request.boards.map { it.copy(snapshotItems = null) },
    )
}

private suspend fun loadInitialThroughputWeight(
    dao: RocheDao,
    localPeerId: String,
    activePeers: List<PeerNodeDto>,
): Double {
    val stored =
        dao.getLocalSyncFlag(FLAG_LOCAL_THROUGHPUT_WEIGHT)?.trim()?.toDoubleOrNull()?.takeIf {
            it.isFinite() && it > 0.0
        }
    if (stored != null) return stored.coerceIn(MIN_PEER_WEIGHT, MAX_PEER_WEIGHT)
    val fromPeers =
        activePeers
            .firstOrNull { it.peerId == localPeerId }
            ?.weight
            ?.takeIf { it.isFinite() && it > 0.0 }
    return fromPeers?.coerceIn(MIN_PEER_WEIGHT, MAX_PEER_WEIGHT) ?: DEFAULT_ONLINE_PEER_WEIGHT
}

internal fun foldThroughputSamplesIntoEwma(
    currentWeight: Double,
    samples: List<FeedPollSample>,
): Double {
    var weight = currentWeight
    for (sample in samples) {
        val sampleWeight =
            computeThroughputWeight(
                feedsCompleted = 1,
                itemsProcessed = sample.itemsProcessed,
                elapsedSeconds = sample.elapsedSeconds,
            )
        weight = updateThroughputEwma(weight, sampleWeight)
    }
    return weight
}

@Suppress("LongParameterList")
internal suspend fun pollCooperativeFeedsOnDevice(
    dao: RocheDao,
    publicWebFetcher: PublicWebFetcher,
    localPeerId: String,
    activePeers: List<PeerNodeDto>,
    nowInstant: Instant? = null,
    clock: () -> Instant = { Instant.now().truncatedTo(ChronoUnit.SECONDS) },
    writeLock: DbWriteLock = DbWriteLock(),
): CooperativePollOutcome {
    val effectiveClock: () -> Instant = nowInstant?.let { fixed -> { fixed } } ?: clock
    val attemptedIds = mutableSetOf<String>()
    var currentPeers = activePeers
    var polledFeedsCount = 0
    var ewmaWeight = loadInitialThroughputWeight(dao, localPeerId, activePeers)
    var measuredWeight: Double? = null

    var keepPolling = true
    while (keepPolling) {
        val sliceNow = effectiveClock()
        val slice =
            dao.getAllFeeds()
                .filter {
                    it.id !in attemptedIds &&
                        shouldPeerPollFeed(localPeerId, currentPeers, it, sliceNow)
                }
                .take(COOPERATIVE_POLL_SLICE_SIZE)
        if (slice.isEmpty()) {
            keepPolling = false
        } else {
            slice.forEach { attemptedIds.add(it.id) }
            val succeededSamples =
                pollCooperativeSlice(
                    dao = dao,
                    publicWebFetcher = publicWebFetcher,
                    slice = slice,
                    localPeerId = localPeerId,
                    clock = effectiveClock,
                    writeLock = writeLock,
                )
            if (succeededSamples.isNotEmpty()) {
                polledFeedsCount += succeededSamples.size
                ewmaWeight = foldThroughputSamplesIntoEwma(ewmaWeight, succeededSamples)
                measuredWeight = ewmaWeight
                currentPeers = currentPeers.map { peer ->
                    if (peer.peerId == localPeerId) peer.copy(weight = ewmaWeight) else peer
                }
            }
        }
    }
    if (measuredWeight != null) {
        writeLock.withLock {
            dao.upsertLocalSyncFlag(
                LocalSyncFlagEntity(
                    flagKey = FLAG_LOCAL_THROUGHPUT_WEIGHT,
                    flagValue = measuredWeight.toString(),
                )
            )
        }
    }
    return CooperativePollOutcome(
        polledFeedsCount = polledFeedsCount,
        attemptedFeedsCount = attemptedIds.size,
        measuredWeight = measuredWeight,
    )
}

@Suppress("LongParameterList")
internal suspend fun pollCooperativeSlice(
    dao: RocheDao,
    publicWebFetcher: PublicWebFetcher,
    slice: List<FeedRow>,
    localPeerId: String,
    clock: () -> Instant = { Instant.now().truncatedTo(ChronoUnit.SECONDS) },
    writeLock: DbWriteLock = DbWriteLock(),
): List<FeedPollSample> = coroutineScope {
    slice
        .map { feed ->
            async {
                val startedNanos = System.nanoTime()
                val outcome =
                    LocalFeedPoller.pollFeed(
                        dao = dao,
                        publicWebFetcher = publicWebFetcher,
                        feed = feed,
                        localPeerId = localPeerId,
                        clock = clock,
                        writeLock = writeLock,
                    )
                val elapsedSeconds =
                    (System.nanoTime() - startedNanos).toDouble() / NANOS_PER_SECOND
                outcome.getOrNull()?.let { itemsProcessed ->
                    FeedPollSample(
                        itemsProcessed = itemsProcessed,
                        elapsedSeconds = elapsedSeconds,
                    )
                }
            }
        }
        .awaitAll()
        .filterNotNull()
}

@Suppress("LongParameterList")
internal suspend fun pollCooperativeBoardsOnDevice(
    dao: RocheDao,
    publicWebFetcher: PublicWebFetcher,
    localPeerId: String,
    activePeers: List<PeerNodeDto>,
    adapterRegistry: BoardAdapterRegistry = BoardAdapterRegistry.default(publicWebFetcher),
    nowInstant: Instant? = null,
    clock: () -> Instant = { Instant.now().truncatedTo(ChronoUnit.SECONDS) },
    writeLock: DbWriteLock = DbWriteLock(),
): CooperativePollOutcome {
    val effectiveClock: () -> Instant = nowInstant?.let { fixed -> { fixed } } ?: clock
    val attemptedIds = mutableSetOf<String>()
    var currentPeers = activePeers
    var polledBoardsCount = 0
    var ewmaWeight = loadInitialThroughputWeight(dao, localPeerId, activePeers)
    var measuredWeight: Double? = null

    var keepPolling = true
    while (keepPolling) {
        val sliceNow = effectiveClock()
        val slice =
            dao.getActiveBoards()
                .filter {
                    it.id !in attemptedIds &&
                        shouldPeerPollBoard(localPeerId, currentPeers, it, sliceNow.epochSecond)
                }
                .take(COOPERATIVE_POLL_SLICE_SIZE)
        if (slice.isEmpty()) {
            keepPolling = false
        } else {
            slice.forEach { attemptedIds.add(it.id) }
            val succeededSamples =
                pollCooperativeBoardSlice(
                    dao = dao,
                    publicWebFetcher = publicWebFetcher,
                    adapterRegistry = adapterRegistry,
                    slice = slice,
                    localPeerId = localPeerId,
                    clock = effectiveClock,
                    writeLock = writeLock,
                )
            if (succeededSamples.isNotEmpty()) {
                polledBoardsCount += succeededSamples.size
                ewmaWeight = foldThroughputSamplesIntoEwma(ewmaWeight, succeededSamples)
                measuredWeight = ewmaWeight
                currentPeers = currentPeers.map { peer ->
                    if (peer.peerId == localPeerId) peer.copy(weight = ewmaWeight) else peer
                }
            }
        }
    }
    if (measuredWeight != null) {
        writeLock.withLock {
            dao.upsertLocalSyncFlag(
                LocalSyncFlagEntity(
                    flagKey = FLAG_LOCAL_THROUGHPUT_WEIGHT,
                    flagValue = measuredWeight.toString(),
                )
            )
        }
    }
    return CooperativePollOutcome(
        polledFeedsCount = polledBoardsCount,
        attemptedFeedsCount = attemptedIds.size,
        measuredWeight = measuredWeight,
    )
}

@Suppress("LongParameterList")
internal suspend fun pollCooperativeBoardSlice(
    dao: RocheDao,
    publicWebFetcher: PublicWebFetcher,
    adapterRegistry: BoardAdapterRegistry,
    slice: List<BoardEntity>,
    localPeerId: String,
    clock: () -> Instant = { Instant.now().truncatedTo(ChronoUnit.SECONDS) },
    writeLock: DbWriteLock = DbWriteLock(),
): List<FeedPollSample> = coroutineScope {
    slice
        .map { board ->
            async {
                val startedNanos = System.nanoTime()
                val outcome =
                    LocalBoardPoller.pollBoard(
                        dao = dao,
                        publicWebFetcher = publicWebFetcher,
                        board = board,
                        localPeerId = localPeerId,
                        adapterRegistry = adapterRegistry,
                        clock = clock,
                        writeLock = writeLock,
                    )
                val elapsedSeconds =
                    (System.nanoTime() - startedNanos).toDouble() / NANOS_PER_SECOND
                outcome.getOrNull()?.let { itemsProcessed ->
                    FeedPollSample(
                        itemsProcessed = itemsProcessed,
                        elapsedSeconds = elapsedSeconds,
                    )
                }
            }
        }
        .awaitAll()
        .filterNotNull()
}

internal fun isPayloadTooLargeProblem(error: ProblemDetailsException): Boolean =
    error.problemType == RocheProblemTypes.PAYLOAD_TOO_LARGE ||
        error.statusCode == HTTP_PAYLOAD_TOO_LARGE

internal fun clampServerMaxPayloadBytes(serverMaxBytes: Long?): Int? =
    serverMaxBytes
        ?.takeIf { it > 0L }
        ?.coerceIn(MIN_SERVER_PAYLOAD_BYTES.toLong(), MAX_PEER_SYNC_PAYLOAD_BYTES.toLong())
        ?.toInt()

internal fun compute413RetryPayloadBudget(currentBudget: Int, serverMaxBytes: Long?): Int {
    val halved = (currentBudget / 2).coerceAtLeast(1)
    return clampServerMaxPayloadBytes(serverMaxBytes)?.let { cap ->
        minOf(halved, cap).coerceAtLeast(1)
    } ?: halved
}

internal fun isOversizedResponseError(error: Throwable): Boolean =
    error is ResponseTooLargeException

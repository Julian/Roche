package com.grayvines.roche.data

import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.remote.PeerSyncRequestDto
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.remote.ProblemDetailsException
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.Settings
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

internal const val MAX_DELTA_CONTINUATION_PAGES = 20
private const val MAX_OVERFLOW_PUSH_ROUNDS = 25
private const val HTTP_PAYLOAD_TOO_LARGE = 413
private const val COOPERATIVE_POLL_SLICE_SIZE = 4
private const val NANOS_PER_SECOND = 1_000_000_000.0

internal data class CooperativePollOutcome(
    val polledFeedsCount: Int,
    val attemptedFeedsCount: Int,
    val measuredWeight: Double?,
)

internal data class SinglePeerSyncResult(
    val response: PeerSyncResponseDto,
    val sentEntryIds: Set<String> = emptySet(),
    val byteOverflowEntryIds: Set<String> = emptySet(),
)

internal fun parseSyncSeqCursor(rawCursor: String?): Long =
    rawCursor?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

internal fun shouldAdvanceSyncSeq(previousCursor: String?, candidateSeq: Long): Boolean {
    if (candidateSeq < 0L) return false
    val prevParsed = previousCursor?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()
    return prevParsed == null || candidateSeq >= prevParsed
}

internal class PeerSyncCoordinator(
    private val dao: RocheDao,
    private val apiClient: RocheApiClient,
    private val networkTier: NetworkTier = NetworkTier.WIFI,
    private val catalogStore: CatalogStore? = null,
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

    private suspend fun syncAndPollInternal(
        currentSettings: Settings,
        initialWeightOverride: Double?,
        sinceSeqOverride: Long?,
    ): Int? {
        var workingSettings = currentSettings
        var initialRound =
            executeSinglePeerSyncRound(
                currentSettings = workingSettings,
                weightOverride = initialWeightOverride,
                sinceSeqOverride = sinceSeqOverride,
            ) ?: return null

        if (initialRound.response.resetRequired) {
            onSyncCursorUpdated("0")
            workingSettings = workingSettings.copy(lastPeerSyncCursor = "0")
            initialRound =
                executeSinglePeerSyncRound(
                    currentSettings = workingSettings,
                    weightOverride = initialWeightOverride,
                    sinceSeqOverride = 0L,
                ) ?: return null
        }

        var latestPeers = initialRound.response.activePeers
        onPeersUpdated(latestPeers)
        workingSettings =
            applyCursorAndContinuePages(
                baseSettings = workingSettings,
                round = initialRound,
                weightOverride = initialWeightOverride,
            ) { peers ->
                latestPeers = peers
                onPeersUpdated(peers)
            }
        workingSettings =
            drainOverflowEntryPushes(
                initialSettings = workingSettings,
                initialSentIds = initialRound.sentEntryIds,
                initialOverflowIds = initialRound.byteOverflowEntryIds,
                weightOverride = initialWeightOverride,
            ) { peers ->
                latestPeers = peers
                onPeersUpdated(peers)
            }

        val pollOutcome =
            if (networkTier != NetworkTier.OFFLINE) {
                pollCooperativeFeedsOnDevice(
                    dao = dao,
                    apiClient = apiClient,
                    localPeerId = workingSettings.peerId,
                    activePeers = latestPeers,
                )
            } else {
                null
            }
        if (pollOutcome != null && pollOutcome.attemptedFeedsCount > 0) {
            runPostPollPeerSync(workingSettings, pollOutcome.measuredWeight)
        }
        return dao.countItems()
    }

    private suspend fun runPostPollPeerSync(
        currentSettings: Settings,
        measuredWeight: Double?,
    ) {
        val followUpRound =
            executeSinglePeerSyncRound(
                currentSettings = currentSettings,
                weightOverride = measuredWeight,
            ) ?: return
        onPeersUpdated(followUpRound.response.activePeers)
        applyCursorAndContinuePages(
            baseSettings = currentSettings,
            round = followUpRound,
            weightOverride = measuredWeight,
        )
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
            apiClient.syncPeer(
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

    private suspend fun drainOverflowEntryPushes(
        initialSettings: Settings,
        initialSentIds: Set<String>,
        initialOverflowIds: Set<String>,
        weightOverride: Double?,
        onPagePeers: (List<PeerNodeDto>) -> Unit,
    ): Settings {
        var settings = initialSettings
        var remainingIds = initialOverflowIds
        val alreadySentIds = initialSentIds.toMutableSet()
        var roundIdx = 0
        var keepDraining = remainingIds.isNotEmpty()
        while (keepDraining && roundIdx < MAX_OVERFLOW_PUSH_ROUNDS) {
            val prevCount = remainingIds.size
            val nextRound =
                executeSinglePeerSyncRound(
                    currentSettings = settings,
                    weightOverride = weightOverride,
                    forcedEntryIds = remainingIds,
                    excludeEntryIds = alreadySentIds,
                )
            if (nextRound == null || nextRound.response.resetRequired) {
                keepDraining = false
            } else {
                alreadySentIds.addAll(nextRound.sentEntryIds)
                onPagePeers(nextRound.response.activePeers)
                settings =
                    applyCursorAndContinuePages(
                        baseSettings = settings,
                        round = nextRound,
                        weightOverride = weightOverride,
                        onPagePeers = onPagePeers,
                    )
                remainingIds = nextRound.byteOverflowEntryIds - alreadySentIds
                keepDraining = remainingIds.isNotEmpty() && remainingIds.size < prevCount
                roundIdx++
            }
        }
        return settings
    }

    @Suppress("LongParameterList")
    private suspend fun executeSinglePeerSyncRound(
        currentSettings: Settings,
        weightOverride: Double? = null,
        sinceSeqOverride: Long? = null,
        forcedEntryIds: Set<String> = emptySet(),
        excludeEntryIds: Set<String> = emptySet(),
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
                    ),
            )
        val (response, droppedEntriesOn413) =
            callSyncPeerRound(currentSettings, tracked.request) ?: return null
        if (response.resetRequired) {
            return SinglePeerSyncResult(response = response)
        }
        val effectiveTracked =
            if (droppedEntriesOn413) {
                tracked.copy(
                    sentEntryHlcById = emptyMap(),
                    sentStateHlcById = emptyMap(),
                    sentFeedSourceHlcById = emptyMap(),
                    byteOverflowEntryIds = tracked.sentEntryIds + tracked.byteOverflowEntryIds,
                )
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
        )
    }

    private suspend fun callSyncPeerRound(
        currentSettings: Settings,
        request: PeerSyncRequestDto,
    ): Pair<PeerSyncResponseDto, Boolean>? {
        val primary = runSuspendCatching {
            apiClient.syncPeer(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
                request = request,
            )
        }
        val error = primary.exceptionOrNull()
        return when {
            error == null -> primary.getOrNull()?.let { it to false }
            (error as? ProblemDetailsException)?.statusCode == HTTP_PAYLOAD_TOO_LARGE &&
                request.entries.isNotEmpty() ->
                apiClient
                    .syncPeer(
                        serverUrl = currentSettings.serverUrl,
                        token = currentSettings.bearerToken,
                        request = request.copy(entries = emptyList()),
                    )
                    ?.let { it to true }
            request.sinceSeq == 0L && isOversizedResponseError(error) -> null
            else -> throw error
        }
    }
}

internal suspend fun pollCooperativeFeedsOnDevice(
    dao: RocheDao,
    apiClient: RocheApiClient,
    localPeerId: String,
    activePeers: List<PeerNodeDto>,
    nowInstant: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS),
): CooperativePollOutcome {
    val attemptedIds = mutableSetOf<String>()
    var currentPeers = activePeers
    var polledFeedsCount = 0
    var totalItemsProcessed = 0
    var measuredWeight: Double? = null
    val startedNanos = System.nanoTime()

    var keepPolling = true
    while (keepPolling) {
        val slice =
            dao.getAllFeeds()
                .filter {
                    it.id !in attemptedIds &&
                        shouldPeerPollFeed(localPeerId, currentPeers, it, nowInstant)
                }
                .take(COOPERATIVE_POLL_SLICE_SIZE)
        if (slice.isEmpty()) {
            keepPolling = false
        } else {
            slice.forEach { attemptedIds.add(it.id) }
            val succeededCounts =
                pollCooperativeSlice(dao, apiClient, slice, localPeerId, nowInstant)
            if (succeededCounts.isNotEmpty()) {
                polledFeedsCount += succeededCounts.size
                totalItemsProcessed += succeededCounts.sum()
                val elapsedSeconds =
                    (System.nanoTime() - startedNanos).toDouble() / NANOS_PER_SECOND
                val updatedWeight =
                    computeThroughputWeight(
                        feedsCompleted = polledFeedsCount,
                        itemsProcessed = totalItemsProcessed,
                        elapsedSeconds = elapsedSeconds,
                    )
                measuredWeight = updatedWeight
                currentPeers = currentPeers.map { peer ->
                    if (peer.peerId == localPeerId) peer.copy(weight = updatedWeight) else peer
                }
            }
        }
    }
    return CooperativePollOutcome(
        polledFeedsCount = polledFeedsCount,
        attemptedFeedsCount = attemptedIds.size,
        measuredWeight = measuredWeight,
    )
}

internal suspend fun pollCooperativeSlice(
    dao: RocheDao,
    apiClient: RocheApiClient,
    slice: List<FeedRow>,
    localPeerId: String,
    nowInstant: Instant,
): List<Int> = coroutineScope {
    slice
        .map { feed ->
            async {
                LocalFeedPoller.pollFeed(
                    dao = dao,
                    apiClient = apiClient,
                    feed = feed,
                    localPeerId = localPeerId,
                    nowInstant = nowInstant,
                )
            }
        }
        .awaitAll()
        .mapNotNull { it.getOrNull() }
}

internal fun isOversizedResponseError(error: Throwable): Boolean =
    error is IOException &&
        error.message?.contains("exceeds maximum allowed size", ignoreCase = true) == true

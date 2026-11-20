@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data.sync

import com.grayvines.roche.core.canonicalizeSyncUrl
import com.grayvines.roche.core.isSyncablePublicUrl
import com.grayvines.roche.core.normalizeConsecutiveFailures
import com.grayvines.roche.core.parseIsoEpochSecs
import com.grayvines.roche.data.local.FeedRow
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ln
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val SERVER_PEER_ID = "server"
internal const val DEFAULT_POLL_INTERVAL_MINUTES = 30

enum class NetworkTier(val wireValue: String, val defaultWeight: Double, val displayLabel: String) {
    WIRED("wired", WIRED_DEFAULT_WEIGHT, "Wired"),
    WIFI("wifi", WIFI_DEFAULT_WEIGHT, "Wi-Fi"),
    CELLULAR("cellular", CELLULAR_DEFAULT_WEIGHT, "Cellular"),
    OFFLINE("offline", OFFLINE_DEFAULT_WEIGHT, "Offline");

    companion object {
        fun fromWireValue(raw: String?): NetworkTier =
            entries.firstOrNull { it.wireValue.equals(raw?.trim(), ignoreCase = true) } ?: WIFI
    }
}

fun interface NetworkTierProvider {
    fun currentNetworkTier(): NetworkTier

    fun isVpnTransportActive(): Boolean = true

    fun activeTailnetDomains(): List<String> = emptyList()
}

@Serializable
data class PeerNodeDto(
    @SerialName("peer_id") val peerId: String = "",
    @SerialName("device_name") val deviceName: String = "",
    @SerialName("network_tier") val networkTier: String = NetworkTier.WIFI.wireValue,
    val weight: Double = NetworkTier.WIFI.defaultWeight,
    @SerialName("last_seen_at") val lastSeenAt: String = "",
)

internal fun PeerNodeDto.normalized(): PeerNodeDto =
    copy(
        peerId = peerId.takeIf { it.isNotBlank() }.orEmpty(),
        deviceName = deviceName.takeIf { it.isNotBlank() }.orEmpty(),
        networkTier = networkTier.takeIf { it.isNotBlank() } ?: NetworkTier.WIFI.wireValue,
        lastSeenAt = lastSeenAt.takeIf { it.isNotBlank() }.orEmpty(),
    )

@Serializable
data class SyncTombstoneDto(
    @SerialName("entity_type") val entityType: String = "",
    @SerialName("natural_key") val naturalKey: String = "",
    @SerialName("deleted_at") val deletedAt: String = "",
    val hlc: String = "",
)

/**
 * Maps `(peerId, feedUrl, epoch)` deterministically to a uniform value in `(0.0, 1.0)` using the
 * top 53 bits of SHA-256. Bit-for-bit identical across Rust and Kotlin/JVM.
 */
internal fun rendezvousUniformUnit(peerId: String, feedUrl: String, epoch: Long): Double {
    val digest =
        MessageDigest.getInstance(DIGEST_ALGORITHM_SHA256).run {
            update("roche:hrw:v1:".toByteArray(StandardCharsets.UTF_8))
            update(peerId.toByteArray(StandardCharsets.UTF_8))
            update(byteArrayOf(0))
            update(feedUrl.toByteArray(StandardCharsets.UTF_8))
            update(byteArrayOf(0))
            update(epoch.toString().toByteArray(StandardCharsets.UTF_8))
            digest()
        }
    val raw = ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long
    val top53 = raw ushr HRW_SHIFT_BITS
    return (top53.toDouble() + HRW_HALF_OFFSET) / (1L shl IEEE754_MANTISSA_BITS).toDouble()
}

/**
 * Deterministic per-feed phase offset in `[0, intervalSecs)` derived from SHA-256 of the
 * canonicalized feed URL. Bit-for-bit identical across Rust and Kotlin/JVM.
 */
internal fun feedPhaseOffsetSecs(feedUrl: String, pollIntervalMinutes: Int): Long {
    val intervalSecs =
        pollIntervalMinutes.coerceAtLeast(MIN_POLL_INTERVAL_MINUTES).toLong() * SECONDS_PER_MINUTE
    val canonicalUrl = canonicalizeSyncUrl(feedUrl)
    val digest =
        MessageDigest.getInstance(DIGEST_ALGORITHM_SHA256).run {
            update("roche:phase:v1:".toByteArray(StandardCharsets.UTF_8))
            update(canonicalUrl.toByteArray(StandardCharsets.UTF_8))
            digest()
        }
    val raw = ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long
    return java.lang.Long.remainderUnsigned(raw, intervalSecs)
}

internal fun nextPhaseSlotAtOrAfter(
    earliestEpochSeconds: Long,
    phaseOffsetSecs: Long,
    intervalSecs: Long,
): Long {
    val safeInterval = intervalSecs.coerceAtLeast(1L)
    val safePhase = Math.floorMod(phaseOffsetSecs, safeInterval)
    val slot = Math.floorDiv(earliestEpochSeconds - safePhase + safeInterval - 1L, safeInterval)
    return slot * safeInterval + safePhase
}

internal fun targetPollIntervalSecs(pollIntervalMinutes: Int, consecutiveFailures: Int): Long {
    val intervalSecs =
        pollIntervalMinutes.coerceAtLeast(MIN_POLL_INTERVAL_MINUTES).toLong() * SECONDS_PER_MINUTE
    if (consecutiveFailures <= 0) return intervalSecs
    val fourX =
        if (intervalSecs > Long.MAX_VALUE / LARGE_INTERVAL_BACKOFF_MULTIPLIER) {
            Long.MAX_VALUE
        } else {
            intervalSecs * LARGE_INTERVAL_BACKOFF_MULTIPLIER
        }
    val capSecs = minOf(maxOf(DEFAULT_MAX_BACKOFF_SECS, fourX), ABSOLUTE_MAX_BACKOFF_SECS)
    val shift = consecutiveFailures.coerceIn(1, MAX_BACKOFF_SHIFT)
    val multiplier = 1L shl shift
    val scaled =
        if (intervalSecs > Long.MAX_VALUE / multiplier) {
            Long.MAX_VALUE
        } else {
            intervalSecs * multiplier
        }
    return maxOf(intervalSecs, minOf(scaled, capSecs))
}

internal fun nextScheduledPollEpochSecs(
    feedUrl: String,
    pollIntervalMinutes: Int,
    lastPolledEpochSeconds: Long,
    consecutiveFailures: Int,
): Long {
    val intervalSecs =
        pollIntervalMinutes.coerceAtLeast(MIN_POLL_INTERVAL_MINUTES).toLong() * SECONDS_PER_MINUTE
    val phaseSecs = feedPhaseOffsetSecs(feedUrl, pollIntervalMinutes)
    val earliest =
        if (consecutiveFailures <= 0) {
            val minHealthyGap =
                minOf(intervalSecs / MIN_HEALTHY_GAP_DIVISOR, MAX_MIN_HEALTHY_GAP_SECS)
            lastPolledEpochSeconds + minHealthyGap
        } else {
            val targetDelay = targetPollIntervalSecs(pollIntervalMinutes, consecutiveFailures)
            val snapMargin = intervalSecs / BACKOFF_SNAP_MARGIN_DIVISOR
            lastPolledEpochSeconds + targetDelay - snapMargin
        }
    return nextPhaseSlotAtOrAfter(earliest, phaseSecs, intervalSecs)
}

internal fun computeThroughputWeight(
    feedsCompleted: Int,
    itemsProcessed: Int,
    elapsedSeconds: Double,
): Double {
    if (feedsCompleted <= 0) return DEFAULT_ONLINE_PEER_WEIGHT
    val safeSecs = elapsedSeconds.coerceAtLeast(MIN_MEASURED_ELAPSED_SECS)
    val workUnits = feedsCompleted.toDouble() + itemsProcessed.toDouble() / ITEMS_PER_WORK_UNIT
    val unitsPerSec = workUnits / safeSecs
    return unitsPerSec.coerceIn(MIN_PEER_WEIGHT, MAX_PEER_WEIGHT)
}

internal fun updateThroughputEwma(currentWeight: Double, sampleWeight: Double): Double {
    val base =
        if (currentWeight.isFinite() && currentWeight > 0.0) {
            currentWeight.coerceIn(MIN_PEER_WEIGHT, MAX_PEER_WEIGHT)
        } else {
            DEFAULT_ONLINE_PEER_WEIGHT
        }
    val sample =
        if (sampleWeight.isFinite()) {
            sampleWeight.coerceIn(MIN_PEER_WEIGHT, MAX_PEER_WEIGHT)
        } else {
            base
        }
    return (THROUGHPUT_EWMA_ALPHA * sample + (1.0 - THROUGHPUT_EWMA_ALPHA) * base).coerceIn(
        MIN_PEER_WEIGHT,
        MAX_PEER_WEIGHT,
    )
}

private fun peerLivenessFactor(lastSeenAtIso: String, nowEpochSeconds: Long): Double {
    val seenEpoch = parseIsoEpochSecs(lastSeenAtIso) ?: return 1.0
    val staleSecs = nowEpochSeconds - seenEpoch
    return if (staleSecs <= LIVENESS_GRACE_SECONDS) {
        1.0
    } else {
        1.0 / (1.0 + (staleSecs - LIVENESS_GRACE_SECONDS).toDouble() / LIVENESS_DECAY_SECONDS)
    }
}

internal fun effectivePeerWeight(
    peer: PeerNodeDto,
    lastPolledBy: String?,
    lastError: String?,
    lastErrorBy: String?,
): Double {
    val base = peer.weight.coerceIn(0.0, MAX_PEER_WEIGHT)
    if (base <= 0.0 || peer.networkTier.equals(NetworkTier.OFFLINE.wireValue, ignoreCase = true)) {
        return 0.0
    }
    var factor = 1.0
    val hasError = !lastError.isNullOrBlank()
    val cleanErrorBy = lastErrorBy?.trim()?.takeIf { it.isNotEmpty() }
    val cleanPolledBy = lastPolledBy?.trim()?.takeIf { it.isNotEmpty() }
    if (hasError) {
        factor *=
            if (cleanErrorBy == peer.peerId) {
                FAILING_PEER_PENALTY_FACTOR
            } else {
                RESCUE_PEER_BOOST_FACTOR
            }
    } else if (cleanPolledBy == peer.peerId) {
        factor *= HEALTHY_AFFINITY_FACTOR
    }
    return base * factor
}

private val HRW_SCORE_EPSILON: Double = Math.ulp(1.0)

@Suppress("LongParameterList")
internal fun selectFeedPoller(
    activePeers: List<PeerNodeDto>,
    feedUrl: String,
    pollIntervalMinutes: Int,
    nowEpochSeconds: Long,
    lastPolledBy: String?,
    lastError: String?,
    lastErrorBy: String?,
    localBoostPeerId: String? = null,
    localBoostFactor: Double = 1.0,
): String? {
    if (activePeers.isEmpty()) return null
    val intervalSecs =
        pollIntervalMinutes.coerceAtLeast(MIN_POLL_INTERVAL_MINUTES).toLong() * SECONDS_PER_MINUTE
    val phaseSecs = feedPhaseOffsetSecs(feedUrl, pollIntervalMinutes)
    val epoch = Math.floorDiv(nowEpochSeconds - phaseSecs, intervalSecs)

    var bestPeer: String? = null
    var bestScore = Double.NEGATIVE_INFINITY
    for (peer in activePeers) {
        var weight =
            effectivePeerWeight(peer, lastPolledBy, lastError, lastErrorBy) *
                peerLivenessFactor(peer.lastSeenAt, nowEpochSeconds)
        if (localBoostPeerId != null && peer.peerId == localBoostPeerId) {
            weight *= localBoostFactor
        }
        if (weight <= 0.0) continue
        val u = rendezvousUniformUnit(peer.peerId, feedUrl, epoch)
        val score = -weight / ln(u)
        val isBetter =
            bestPeer == null ||
                score > bestScore ||
                abs(score - bestScore) <= HRW_SCORE_EPSILON && peer.peerId < bestPeer
        if (isBetter) {
            bestScore = score
            bestPeer = peer.peerId
        }
    }
    return bestPeer
}

private data class FeedPollScheduleState(
    val isCrossPeerRescue: Boolean,
    val lastPolledEpochSecs: Long?,
    val dueAtSecs: Long,
    val intervalSecs: Long,
)

private fun evaluateFeedPollSchedule(
    localPeerId: String,
    feed: FeedRow,
    nowEpochSeconds: Long,
): FeedPollScheduleState {
    val hasError = !feed.lastError.isNullOrBlank()
    val effectiveFailures = normalizeConsecutiveFailures(feed.lastError, feed.consecutiveFailures)
    val cleanErrorBy = feed.lastErrorBy?.trim()?.takeIf { it.isNotEmpty() }
    val isCrossPeerRescue =
        hasError && effectiveFailures == 1 && cleanErrorBy != null && cleanErrorBy != localPeerId
    val lastPolledEpochSecs = parseIsoEpochSecs(feed.lastPolled)
    val intervalSecs =
        feed.pollInterval.coerceAtLeast(MIN_POLL_INTERVAL_MINUTES).toLong() * SECONDS_PER_MINUTE
    val dueAtSecs =
        if (lastPolledEpochSecs == null) {
            nowEpochSeconds
        } else {
            nextScheduledPollEpochSecs(
                feedUrl = feed.url,
                pollIntervalMinutes = feed.pollInterval,
                lastPolledEpochSeconds = lastPolledEpochSecs,
                consecutiveFailures = effectiveFailures,
            )
        }
    return FeedPollScheduleState(
        isCrossPeerRescue = isCrossPeerRescue,
        lastPolledEpochSecs = lastPolledEpochSecs,
        dueAtSecs = dueAtSecs,
        intervalSecs = intervalSecs,
    )
}

internal fun isFeedDueOrRescueForPeer(
    localPeerId: String,
    feed: FeedRow,
    nowInstant: Instant,
): Boolean {
    if (!feed.isActive) return false
    val nowSecs = nowInstant.epochSecond
    val schedule = evaluateFeedPollSchedule(localPeerId, feed, nowSecs)
    return schedule.isCrossPeerRescue || nowSecs >= schedule.dueAtSecs
}

internal fun shouldPeerPollFeed(
    localPeerId: String,
    activePeers: List<PeerNodeDto>,
    feed: FeedRow,
    nowInstant: Instant = Instant.now(),
): Boolean {
    if (!feed.isActive) return false
    val nowSecs = nowInstant.epochSecond
    val schedule = evaluateFeedPollSchedule(localPeerId, feed, nowSecs)
    if (!schedule.isCrossPeerRescue && nowSecs < schedule.dueAtSecs) return false

    val overdueSecs = (nowSecs - schedule.dueAtSecs).coerceAtLeast(0L)
    val isOverdueRescue =
        schedule.lastPolledEpochSecs != null && overdueSecs >= schedule.intervalSecs
    if (isOverdueRescue || !isSyncablePublicUrl(feed.url) || activePeers.size <= 1) {
        return true
    }

    val localStealBoost =
        if (overdueSecs > 0L) {
            val overdueRatio = overdueSecs.toDouble() / schedule.intervalSecs.toDouble()
            1.0 + OVERDUE_STEAL_SCALE * overdueRatio * overdueRatio
        } else {
            1.0
        }
    return selectFeedPoller(
        activePeers = activePeers,
        feedUrl = feed.url,
        pollIntervalMinutes = feed.pollInterval,
        nowEpochSeconds = nowSecs,
        lastPolledBy = feed.lastPolledBy,
        lastError = feed.lastError,
        lastErrorBy = feed.lastErrorBy,
        localBoostPeerId = localPeerId,
        localBoostFactor = localStealBoost,
    ) == localPeerId
}

private const val DIGEST_ALGORITHM_SHA256 = "SHA-256"
internal const val DEFAULT_ONLINE_PEER_WEIGHT = 1.0
private const val WIRED_DEFAULT_WEIGHT = DEFAULT_ONLINE_PEER_WEIGHT
private const val WIFI_DEFAULT_WEIGHT = DEFAULT_ONLINE_PEER_WEIGHT
private const val CELLULAR_DEFAULT_WEIGHT = DEFAULT_ONLINE_PEER_WEIGHT
private const val OFFLINE_DEFAULT_WEIGHT = 0.0
private const val HRW_SHIFT_BITS = 11
private const val HRW_HALF_OFFSET = 0.5
private const val IEEE754_MANTISSA_BITS = 53
internal const val MIN_PEER_WEIGHT = 0.2
internal const val MAX_PEER_WEIGHT = 10.0
internal const val THROUGHPUT_EWMA_ALPHA = 0.2
private const val MIN_MEASURED_ELAPSED_SECS = 0.05
private const val ITEMS_PER_WORK_UNIT = 25.0
private const val LIVENESS_GRACE_SECONDS = 120L
private const val LIVENESS_DECAY_SECONDS = 180.0
private const val OVERDUE_STEAL_SCALE = 4.0
private const val FAILING_PEER_PENALTY_FACTOR = 0.05
private const val RESCUE_PEER_BOOST_FACTOR = 2.0
private const val HEALTHY_AFFINITY_FACTOR = 1.1
private const val MIN_POLL_INTERVAL_MINUTES = 1
private const val SECONDS_PER_MINUTE = 60L
private const val DEFAULT_MAX_BACKOFF_SECS = 24L * 3_600L
private const val ABSOLUTE_MAX_BACKOFF_SECS = 7L * 24L * 3_600L
private const val LARGE_INTERVAL_BACKOFF_MULTIPLIER = 4L
private const val MAX_BACKOFF_SHIFT = 16
private const val MIN_HEALTHY_GAP_DIVISOR = 10L
private const val MAX_MIN_HEALTHY_GAP_SECS = 120L
private const val BACKOFF_SNAP_MARGIN_DIVISOR = 2L

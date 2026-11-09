@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data

import com.grayvines.roche.data.local.FeedEntity
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.ln

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
}

@Suppress("LongParameterList")
internal fun classifyNetworkCapabilities(
    hasInternet: Boolean,
    isValidated: Boolean,
    hasEthernet: Boolean,
    hasWifi: Boolean,
    hasCellular: Boolean,
    isMetered: Boolean,
): NetworkTier =
    when {
        !hasInternet || !isValidated -> NetworkTier.OFFLINE
        hasEthernet -> NetworkTier.WIRED
        hasWifi && !isMetered -> NetworkTier.WIFI
        hasWifi || hasCellular -> NetworkTier.CELLULAR
        !isMetered -> NetworkTier.WIFI
        else -> NetworkTier.CELLULAR
    }

data class PeerNodeDto(
    val peerId: String,
    val deviceName: String,
    val networkTier: String = NetworkTier.WIFI.wireValue,
    val weight: Double = NetworkTier.WIFI.defaultWeight,
    val lastSeenAt: String = "",
)

data class SyncTombstoneDto(
    val entityType: String,
    val naturalKey: String,
    val deletedAt: String,
)

/**
 * Generates a deterministic RFC 4122 v5-shaped UUID from `(kind, naturalKey)` using SHA-256 so
 * every peer (Rust, Android, CLI, Web) derives the exact same UUID for the same entity.
 */
internal fun deterministicEntityUuid(kind: String, naturalKey: String): String {
    val digest =
        MessageDigest.getInstance("SHA-256").run {
            update("roche:entity:v1:".toByteArray(StandardCharsets.UTF_8))
            update(kind.toByteArray(StandardCharsets.UTF_8))
            update(":".toByteArray(StandardCharsets.UTF_8))
            update(naturalKey.toByteArray(StandardCharsets.UTF_8))
            digest()
        }
    val bytes = digest.copyOfRange(0, UUID_BYTE_LENGTH)
    bytes[UUID_VERSION_BYTE_INDEX] =
        (bytes[UUID_VERSION_BYTE_INDEX].toInt() and UUID_VERSION_MASK or UUID_VERSION_5_BITS)
            .toByte()
    bytes[UUID_VARIANT_BYTE_INDEX] =
        (bytes[UUID_VARIANT_BYTE_INDEX].toInt() and UUID_VARIANT_MASK or UUID_VARIANT_RFC4122_BITS)
            .toByte()
    val buffer = ByteBuffer.wrap(bytes)
    val msb = buffer.long
    val lsb = buffer.long
    return UUID(msb, lsb).toString()
}

internal fun feedNaturalKey(url: String): String = url.trim()

internal fun itemNaturalKey(feedUrl: String?, itemUrl: String): String {
    val cleanFeedUrl = feedUrl?.trim()?.takeIf { it.isNotEmpty() }
    return if (cleanFeedUrl != null) {
        "$cleanFeedUrl\u0000${itemUrl.trim()}"
    } else {
        "manual\u0000${itemUrl.trim()}"
    }
}

internal fun tagNaturalKey(name: String): String = name.trim().lowercase()

internal fun deterministicFeedId(url: String): String =
    deterministicEntityUuid("feed", feedNaturalKey(url))

internal fun deterministicItemId(feedUrl: String?, itemUrl: String): String =
    deterministicEntityUuid("item", itemNaturalKey(feedUrl, itemUrl))

internal fun deterministicTagId(name: String): String =
    deterministicEntityUuid("tag", tagNaturalKey(name))

/**
 * Maps `(peerId, feedUrl, epoch)` deterministically to a uniform value in `(0.0, 1.0)` using the
 * top 53 bits of SHA-256. Bit-for-bit identical across Rust and Kotlin/JVM.
 */
internal fun rendezvousUniformUnit(peerId: String, feedUrl: String, epoch: Long): Double {
    val digest =
        MessageDigest.getInstance("SHA-256").run {
            update("roche:hrw:v1:".toByteArray(StandardCharsets.UTF_8))
            update(peerId.toByteArray(StandardCharsets.UTF_8))
            update("\u0000".toByteArray(StandardCharsets.UTF_8))
            update(feedUrl.toByteArray(StandardCharsets.UTF_8))
            update("\u0000".toByteArray(StandardCharsets.UTF_8))
            update(epoch.toString().toByteArray(StandardCharsets.UTF_8))
            digest()
        }
    val raw = ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long
    val top53 = raw ushr HRW_SHIFT_BITS
    return (top53.toDouble() + HRW_HALF_OFFSET) / (1L shl IEEE754_MANTISSA_BITS).toDouble()
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
    if (hasError) {
        factor *=
            if (lastErrorBy == peer.peerId) {
                FAILING_PEER_PENALTY_FACTOR
            } else {
                RESCUE_PEER_BOOST_FACTOR
            }
    } else if (lastPolledBy == peer.peerId) {
        factor *= HEALTHY_AFFINITY_FACTOR
    }
    return base * factor
}

internal fun selectFeedPoller(
    activePeers: List<PeerNodeDto>,
    feedUrl: String,
    pollIntervalMinutes: Int,
    nowEpochSeconds: Long,
    lastPolledBy: String?,
    lastError: String?,
    lastErrorBy: String?,
): String? {
    if (activePeers.isEmpty()) return null
    val intervalSecs = pollIntervalMinutes.coerceAtLeast(MIN_POLL_INTERVAL_MINUTES) * 60L
    val epoch = Math.floorDiv(nowEpochSeconds, intervalSecs)

    var bestPeer: String? = null
    var bestScore = Double.NEGATIVE_INFINITY
    for (peer in activePeers) {
        val weight = effectivePeerWeight(peer, lastPolledBy, lastError, lastErrorBy)
        if (weight <= 0.0) continue
        val u = rendezvousUniformUnit(peer.peerId, feedUrl, epoch)
        val score = -weight / ln(u)
        val isBetter =
            bestPeer == null || score > bestScore || score == bestScore && peer.peerId < bestPeer
        if (isBetter) {
            bestScore = score
            bestPeer = peer.peerId
        }
    }
    return bestPeer
}

internal fun isFeedDueOrRescueForPeer(
    localPeerId: String,
    feed: FeedEntity,
    nowInstant: Instant,
): Boolean {
    if (!feed.isActive) return false
    val hasCrossPeerError =
        !feed.lastError.isNullOrBlank() &&
            !feed.lastErrorBy.isNullOrBlank() &&
            feed.lastErrorBy != localPeerId
    if (hasCrossPeerError) return true
    val lastPolledIso = feed.lastPolled?.trim()?.takeIf { it.isNotEmpty() } ?: return true
    val lastPolledInstant = runCatching { Instant.parse(lastPolledIso) }.getOrNull() ?: return true
    val intervalMinutes = feed.pollInterval.coerceAtLeast(MIN_POLL_INTERVAL_MINUTES).toLong()
    return Duration.between(lastPolledInstant, nowInstant) >= Duration.ofMinutes(intervalMinutes)
}

internal fun shouldPeerPollFeed(
    localPeerId: String,
    activePeers: List<PeerNodeDto>,
    feed: FeedEntity,
    nowInstant: Instant = Instant.now(),
): Boolean {
    if (!isFeedDueOrRescueForPeer(localPeerId, feed, nowInstant)) return false
    val intervalMinutes = feed.pollInterval.coerceAtLeast(MIN_POLL_INTERVAL_MINUTES).toLong()
    val lastPolledInstant =
        feed.lastPolled
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                runCatching { Instant.parse(it) }.getOrNull()
            }
    if (
        lastPolledInstant != null &&
            Duration.between(lastPolledInstant, nowInstant) >=
                Duration.ofMinutes(intervalMinutes * OVERDUE_RESCUE_MULTIPLIER)
    ) {
        return true
    }
    if (activePeers.size <= 1) return true
    return selectFeedPoller(
        activePeers = activePeers,
        feedUrl = feed.url,
        pollIntervalMinutes = feed.pollInterval,
        nowEpochSeconds = nowInstant.epochSecond,
        lastPolledBy = feed.lastPolledBy,
        lastError = feed.lastError,
        lastErrorBy = feed.lastErrorBy,
    ) == localPeerId
}

internal fun isSyncablePublicUrl(rawUrl: String): Boolean {
    val uri = runCatching { java.net.URI(rawUrl.trim()) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase()
    val host = uri.host?.lowercase()?.trim('[', ']')
    return (scheme == "http" || scheme == "https") && !host.isNullOrEmpty() && isPublicHost(host)
}

private fun isPublicHost(host: String): Boolean =
    host !in DISALLOWED_SYNC_HOSTS &&
        !host.endsWith(".local") &&
        DISALLOWED_SYNC_HOST_PREFIXES.none { host.startsWith(it) }

private val DISALLOWED_SYNC_HOSTS = setOf("localhost", "127.0.0.1", "0.0.0.0", "::1")
private val DISALLOWED_SYNC_HOST_PREFIXES = listOf("10.", "192.168.", "169.254.")
private const val WIRED_DEFAULT_WEIGHT = 1.0
private const val WIFI_DEFAULT_WEIGHT = 0.6
private const val CELLULAR_DEFAULT_WEIGHT = 0.25
private const val OFFLINE_DEFAULT_WEIGHT = 0.0
private const val UUID_BYTE_LENGTH = 16
private const val UUID_VERSION_BYTE_INDEX = 6
private const val UUID_VARIANT_BYTE_INDEX = 8
private const val UUID_VERSION_MASK = 0x0f
private const val UUID_VERSION_5_BITS = 0x50
private const val UUID_VARIANT_MASK = 0x3f
private const val UUID_VARIANT_RFC4122_BITS = 0x80
private const val HRW_SHIFT_BITS = 11
private const val HRW_HALF_OFFSET = 0.5
private const val IEEE754_MANTISSA_BITS = 53
private const val MAX_PEER_WEIGHT = 10.0
private const val FAILING_PEER_PENALTY_FACTOR = 0.05
private const val RESCUE_PEER_BOOST_FACTOR = 2.0
private const val HEALTHY_AFFINITY_FACTOR = 1.1
private const val MIN_POLL_INTERVAL_MINUTES = 1
private const val OVERDUE_RESCUE_MULTIPLIER = 2L

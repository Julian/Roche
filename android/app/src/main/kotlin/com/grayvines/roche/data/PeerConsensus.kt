@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data

import com.grayvines.roche.data.local.FeedEntity
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
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

internal fun canonicalizeSyncUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val canonical = runCatching {
        val uri = URI(trimmed)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if (scheme.isNullOrBlank() || host.isNullOrBlank()) {
            null
        } else {
            buildCanonicalHttpUriString(uri = uri, scheme = scheme, host = host)
        }
    }
        .getOrNull()
    return canonical ?: trimmed.lowercase()
}

private fun buildCanonicalHttpUriString(uri: URI, scheme: String, host: String): String {
    val isHttpOrHttps = scheme == SCHEME_HTTP || scheme == SCHEME_HTTPS
    val defaultPort =
        when (scheme) {
            SCHEME_HTTP -> DEFAULT_HTTP_PORT
            SCHEME_HTTPS -> DEFAULT_HTTPS_PORT
            else -> -1
        }
    val portPart =
        if (uri.port != -1 && uri.port != defaultPort) {
            ":${uri.port}"
        } else {
            ""
        }
    val userInfoPart = uri.rawUserInfo?.let { "$it@" }.orEmpty()
    val rawPath = uri.rawPath.orEmpty()
    val normalizedPath = if (isHttpOrHttps && rawPath.isEmpty()) "/" else rawPath
    val queryPart = uri.rawQuery?.let { "?$it" }.orEmpty()
    val fragmentPart = uri.rawFragment?.let { "#$it" }.orEmpty()
    return "$scheme://$userInfoPart$host$portPart$normalizedPath$queryPart$fragmentPart"
}

internal fun feedNaturalKey(url: String): String = canonicalizeSyncUrl(url)

internal fun itemNaturalKey(feedUrl: String?, itemUrl: String): String {
    val cleanFeedUrl = feedUrl?.trim()?.takeIf { it.isNotEmpty() }?.let(::canonicalizeSyncUrl)
    val cleanItemUrl = canonicalizeSyncUrl(itemUrl)
    return if (cleanFeedUrl != null) {
        "$cleanFeedUrl\u0000$cleanItemUrl"
    } else {
        "manual\u0000$cleanItemUrl"
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

private fun peerLivenessFactor(lastSeenAtIso: String, nowEpochSeconds: Long): Double {
    if (lastSeenAtIso.isBlank()) return 1.0
    val seenEpoch =
        runCatching { Instant.parse(lastSeenAtIso.trim()).epochSecond }.getOrNull() ?: return 1.0
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
    val intervalSecs = pollIntervalMinutes.coerceAtLeast(MIN_POLL_INTERVAL_MINUTES) * 60L
    val epoch = Math.floorDiv(nowEpochSeconds, intervalSecs)

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
    val intervalSecs = intervalMinutes * 60L
    val lastPolledInstant =
        feed.lastPolled
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                runCatching { Instant.parse(it) }.getOrNull()
            }
    val elapsedSecs = lastPolledInstant?.let { Duration.between(it, nowInstant).seconds }
    if (elapsedSecs != null && elapsedSecs >= intervalSecs * OVERDUE_RESCUE_MULTIPLIER) {
        return true
    }
    if (!isSyncablePublicUrl(feed.url) || activePeers.size <= 1) return true
    val overdueSecs = elapsedSecs?.let { (it - intervalSecs).coerceAtLeast(0L) } ?: 0L
    val localStealBoost =
        if (overdueSecs > 0L) {
            val overdueRatio = overdueSecs.toDouble() / intervalSecs.toDouble()
            1.0 + OVERDUE_STEAL_SCALE * overdueRatio * overdueRatio
        } else {
            1.0
        }
    return selectFeedPoller(
        activePeers = activePeers,
        feedUrl = feed.url,
        pollIntervalMinutes = feed.pollInterval,
        nowEpochSeconds = nowInstant.epochSecond,
        lastPolledBy = feed.lastPolledBy,
        lastError = feed.lastError,
        lastErrorBy = feed.lastErrorBy,
        localBoostPeerId = localPeerId,
        localBoostFactor = localStealBoost,
    ) == localPeerId
}

internal fun isSyncablePublicUrl(rawUrl: String): Boolean {
    val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase()
    val rawHost = uri.host?.lowercase()
    val allowedScheme = scheme == SCHEME_HTTP || scheme == SCHEME_HTTPS
    val allowedPort =
        uri.port == -1 || uri.port == DEFAULT_HTTP_PORT || uri.port == DEFAULT_HTTPS_PORT
    return allowedScheme &&
        uri.rawUserInfo == null &&
        allowedPort &&
        !rawHost.isNullOrBlank() &&
        isPublicHost(rawHost)
}

internal fun isSyncableItemUrl(rawUrl: String): Boolean {
    val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase()
    val host = uri.host?.trim('[', ']')
    return (scheme == SCHEME_HTTP || scheme == SCHEME_HTTPS) &&
        uri.rawUserInfo == null &&
        !host.isNullOrBlank()
}

private fun isPublicHost(rawHost: String): Boolean {
    if (rawHost.startsWith("[") && rawHost.endsWith("]")) {
        return isPublicIpv6Literal(rawHost)
    }
    val normalized = rawHost.trimEnd('.')
    val labels = normalized.split('.')
    return when {
        normalized.isEmpty() || '.' !in normalized || labels.any { it.isEmpty() } -> false
        normalized.all { it.isDigit() || it == '.' } -> isPublicIpv4Literal(normalized)
        labels.last().all { it.isDigit() } -> false
        else -> DISALLOWED_DOMAIN_SUFFIXES.none { normalized.endsWith(it) }
    }
}

private fun isPublicIpv4Literal(host: String): Boolean {
    val octets =
        host.split('.').mapNotNull { part ->
            part.toIntOrNull()?.takeIf { it in 0..IPV4_OCTET_MAX }
        }
    return octets.size == IPV4_OCTET_COUNT && isPublicIpv4Octets(octets[0], octets[1])
}

private fun isPublicIpv4Bytes(bytes: ByteArray): Boolean =
    bytes.size == IPV4_OCTET_COUNT &&
        isPublicIpv4Octets(
            bytes[0].toInt() and IPV4_OCTET_MAX,
            bytes[1].toInt() and IPV4_OCTET_MAX,
        )

private fun isPublicIpv4Octets(first: Int, second: Int): Boolean {
    val isRfc1918 =
        first == IPV4_PRIVATE_10 ||
            first == IPV4_PRIVATE_192 && second == IPV4_PRIVATE_168 ||
            first == IPV4_PRIVATE_172 && second in IPV4_PRIVATE_172_MIN..IPV4_PRIVATE_172_MAX
    val isReservedOrLocal =
        first == 0 ||
            first == IPV4_LOOPBACK_127 ||
            first >= IPV4_MULTICAST_START ||
            first == IPV4_LINK_LOCAL_169 && second == IPV4_LINK_LOCAL_254 ||
            first == IPV4_CGNAT_100 && second in IPV4_CGNAT_MIN..IPV4_CGNAT_MAX
    return !isRfc1918 && !isReservedOrLocal
}

private fun isPublicIpv6Literal(bracketedHost: String): Boolean = runCatching {
    val addr = InetAddress.getByName(bracketedHost)
    if (addr is Inet4Address) {
        isPublicIpv4Bytes(addr.address)
    } else {
        isPublicIpv6Address(addr)
    }
}
    .getOrDefault(false)

private fun isPublicIpv6Address(addr: InetAddress): Boolean {
    val bytes = addr.address
    val firstByte = bytes[0].toInt() and IPV4_OCTET_MAX
    val isUniqueLocal = firstByte and IPV6_ULA_MASK == IPV6_ULA_PREFIX
    return !isUniqueLocal &&
        !addr.isLoopbackAddress &&
        !addr.isAnyLocalAddress &&
        !addr.isLinkLocalAddress &&
        !addr.isSiteLocalAddress &&
        !addr.isMulticastAddress
}

private const val SCHEME_HTTP = "http"
private const val SCHEME_HTTPS = "https"
private const val DEFAULT_HTTP_PORT = 80
private const val DEFAULT_HTTPS_PORT = 443
private const val IPV4_OCTET_COUNT = 4
private const val IPV4_OCTET_MAX = 255
private const val IPV4_PRIVATE_10 = 10
private const val IPV4_PRIVATE_172 = 172
private const val IPV4_PRIVATE_172_MIN = 16
private const val IPV4_PRIVATE_172_MAX = 31
private const val IPV4_PRIVATE_192 = 192
private const val IPV4_PRIVATE_168 = 168
private const val IPV4_LOOPBACK_127 = 127
private const val IPV4_LINK_LOCAL_169 = 169
private const val IPV4_LINK_LOCAL_254 = 254
private const val IPV4_CGNAT_100 = 100
private const val IPV4_CGNAT_MIN = 64
private const val IPV4_CGNAT_MAX = 127
private const val IPV4_MULTICAST_START = 224
private const val IPV6_ULA_MASK = 0xfe
private const val IPV6_ULA_PREFIX = 0xfc
private val DISALLOWED_DOMAIN_SUFFIXES =
    listOf(".local", ".lan", ".internal", ".home.arpa", ".ts.net", ".localhost", ".home")
internal const val DEFAULT_ONLINE_PEER_WEIGHT = 1.0
private const val WIRED_DEFAULT_WEIGHT = DEFAULT_ONLINE_PEER_WEIGHT
private const val WIFI_DEFAULT_WEIGHT = DEFAULT_ONLINE_PEER_WEIGHT
private const val CELLULAR_DEFAULT_WEIGHT = DEFAULT_ONLINE_PEER_WEIGHT
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
private const val MIN_PEER_WEIGHT = 0.2
private const val MAX_PEER_WEIGHT = 10.0
private const val MIN_MEASURED_ELAPSED_SECS = 0.05
private const val ITEMS_PER_WORK_UNIT = 25.0
private const val LIVENESS_GRACE_SECONDS = 120L
private const val LIVENESS_DECAY_SECONDS = 180.0
private const val OVERDUE_STEAL_SCALE = 4.0
private const val FAILING_PEER_PENALTY_FACTOR = 0.05
private const val RESCUE_PEER_BOOST_FACTOR = 2.0
private const val HEALTHY_AFFINITY_FACTOR = 1.1
private const val MIN_POLL_INTERVAL_MINUTES = 1
private const val OVERDUE_RESCUE_MULTIPLIER = 2L

@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data

import com.grayvines.roche.data.local.FeedRow
import java.net.IDN
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
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

@Serializable
data class SyncTombstoneDto(
    @SerialName("entity_type") val entityType: String = "",
    @SerialName("natural_key") val naturalKey: String = "",
    @SerialName("deleted_at") val deletedAt: String = "",
    val hlc: String = "",
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
    val trimmed = raw.trim().filterNot { it == '\t' || it == '\r' || it == '\n' }
    if (trimmed.isEmpty()) return ""
    return canonicalizeHierarchicalUrl(trimmed) ?: trimmed.lowercase(Locale.ROOT)
}

private data class SplitHierarchicalUrl(
    val scheme: String,
    val userInfo: String?,
    val host: String,
    val port: Int,
    val rawPath: String,
    val rawQuery: String?,
    val rawFragment: String?,
)

private fun canonicalizeHierarchicalUrl(trimmed: String): String? {
    val split = splitHierarchicalUrl(trimmed) ?: return null
    val isWebScheme =
        split.scheme == SCHEME_HTTP ||
            split.scheme == SCHEME_HTTPS ||
            split.scheme == "ws" ||
            split.scheme == "wss"
    val defaultPort =
        when (split.scheme) {
            SCHEME_HTTP,
            "ws" -> DEFAULT_HTTP_PORT
            SCHEME_HTTPS,
            "wss" -> DEFAULT_HTTPS_PORT
            else -> -1
        }
    val portPart = if (split.port != -1 && split.port != defaultPort) ":${split.port}" else ""
    val userInfoPart =
        split.userInfo?.let { "${percentEncodeUrlText(it, URL_USERINFO_SAFE)}@" }.orEmpty()
    val normalizedPath = normalizeUrlPath(split.rawPath, defaultSlashIfEmpty = isWebScheme)
    val queryPart =
        split.rawQuery?.let { "?${percentEncodeUrlText(it, URL_QUERY_FRAGMENT_SAFE)}" }.orEmpty()
    val fragmentPart =
        split.rawFragment?.let { "#${percentEncodeUrlText(it, URL_QUERY_FRAGMENT_SAFE)}" }.orEmpty()
    return "${split.scheme}://$userInfoPart${split.host}$portPart$normalizedPath$queryPart$fragmentPart"
}

private fun splitHierarchicalUrl(trimmed: String): SplitHierarchicalUrl? {
    val sepIdx = trimmed.indexOf("://")
    if (sepIdx <= 0) return null
    val scheme = trimmed.substring(0, sepIdx).lowercase(Locale.ROOT)
    val validScheme =
        scheme[0] in 'a'..'z' &&
            scheme.all { it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '-' || it == '.' }
    if (!validScheme) return null

    val afterScheme = trimmed.substring(sepIdx + URL_SCHEME_SEP_LEN)
    val withoutFrag = afterScheme.substringBefore('#')
    val rawFragment =
        afterScheme.substringAfter('#', missingDelimiterValue = MISSING_DELIM).takeIf {
            it != MISSING_DELIM
        }
    val withoutQuery = withoutFrag.substringBefore('?')
    val rawQuery =
        withoutFrag.substringAfter('?', missingDelimiterValue = MISSING_DELIM).takeIf {
            it != MISSING_DELIM
        }
    val slashIdx = withoutQuery.indexOf('/')
    val authority = if (slashIdx == -1) withoutQuery else withoutQuery.substring(0, slashIdx)
    val rawPath = if (slashIdx == -1) "" else withoutQuery.substring(slashIdx)
    val (userInfo, host, port) = parseUrlAuthority(authority) ?: return null
    return SplitHierarchicalUrl(scheme, userInfo, host, port, rawPath, rawQuery, rawFragment)
}

private fun parseUrlAuthority(authority: String): Triple<String?, String, Int>? {
    if (authority.isEmpty()) return null
    val atIdx = authority.lastIndexOf('@')
    val userInfo = if (atIdx != -1) authority.substring(0, atIdx) else null
    val hostPort = if (atIdx != -1) authority.substring(atIdx + 1) else authority
    val (rawHost, portStr) = splitHostAndPort(hostPort) ?: return null
    val port =
        if (portStr == null) {
            -1
        } else {
            portStr.toIntOrNull()?.takeIf { it in 1..MAX_URL_PORT } ?: return null
        }
    val asciiHost = canonicalizeAuthorityHost(rawHost) ?: return null
    return Triple(userInfo, asciiHost, port)
}

private fun splitHostAndPort(hostPort: String): Pair<String, String?>? {
    if (hostPort.isEmpty()) return null
    return if (hostPort.startsWith("[")) {
        val closeIdx = hostPort.indexOf(']')
        val rest = if (closeIdx > 1) hostPort.substring(closeIdx + 1) else null
        when {
            rest == null -> null
            rest.isEmpty() -> hostPort.substring(0, closeIdx + 1) to null
            rest.startsWith(":") && rest.length > 1 ->
                hostPort.substring(0, closeIdx + 1) to rest.substring(1)
            else -> null
        }
    } else {
        val colonIdx = hostPort.lastIndexOf(':')
        if (colonIdx == -1) {
            hostPort to null
        } else {
            hostPort.substring(0, colonIdx) to hostPort.substring(colonIdx + 1)
        }
    }
}

private fun canonicalizeAuthorityHost(rawHost: String): String? {
    if (rawHost.isEmpty()) return null
    if (rawHost.startsWith("[") && rawHost.endsWith("]")) {
        val lower = rawHost.lowercase(Locale.ROOT)
        return runCatching { URI("http://$lower").host?.lowercase(Locale.ROOT) }.getOrNull()
    }
    val ascii = runCatching {
        IDN.toASCII(rawHost, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
    }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() && it.none { ch -> ch <= ' ' || ch in "/?#@\\[]" } }
    return ascii
}

private fun normalizeUrlPath(rawPath: String, defaultSlashIfEmpty: Boolean): String {
    if (rawPath.isEmpty()) return if (defaultSlashIfEmpty) "/" else ""
    val out = ArrayList<String>()
    val rawSegments = rawPath.removePrefix("/").split('/')
    var trailingSlash = rawPath.endsWith("/")
    for ((idx, seg) in rawSegments.withIndex()) {
        trailingSlash = applyUrlPathSegment(out, seg, idx == rawSegments.lastIndex, trailingSlash)
    }
    return formatNormalizedPath(out, trailingSlash)
}

private fun applyUrlPathSegment(
    out: MutableList<String>,
    seg: String,
    isLast: Boolean,
    currentTrailingSlash: Boolean,
): Boolean =
    when {
        seg.isEmpty() -> {
            currentTrailingSlash
        }
        isSingleDotSegment(seg) -> {
            currentTrailingSlash || isLast
        }
        isDoubleDotSegment(seg) -> {
            if (out.isNotEmpty()) out.removeAt(out.lastIndex)
            currentTrailingSlash || isLast
        }
        else -> {
            out.add(percentEncodeUrlText(seg, URL_PATH_SAFE))
            currentTrailingSlash
        }
    }

private fun formatNormalizedPath(segments: List<String>, trailingSlash: Boolean): String =
    if (segments.isEmpty()) {
        "/"
    } else {
        buildString {
            append('/')
            append(segments.joinToString("/"))
            if (trailingSlash) append('/')
        }
    }

private fun isSingleDotSegment(seg: String): Boolean =
    seg == "." || seg.equals("%2e", ignoreCase = true)

private fun isDoubleDotSegment(seg: String): Boolean =
    seg == ".." ||
        seg.equals(".%2e", ignoreCase = true) ||
        seg.equals("%2e.", ignoreCase = true) ||
        seg.equals("%2e%2e", ignoreCase = true)

private fun percentEncodeUrlText(input: String, safeAsciiChars: String): String = buildString {
    var i = 0
    while (i < input.length) {
        if (isPercentTripletAt(input, i)) {
            append(input, i, i + PERCENT_TRIPLET_LEN)
            i += PERCENT_TRIPLET_LEN
        } else {
            val cp = input.codePointAt(i)
            appendEncodedCodePoint(cp, safeAsciiChars)
            i += Character.charCount(cp)
        }
    }
}

private fun StringBuilder.appendEncodedCodePoint(cp: Int, safeAsciiChars: String) {
    val ch = cp.toChar()
    if (
        cp in ASCII_PRINTABLE_MIN..ASCII_PRINTABLE_MAX &&
            (ch.isLetterOrDigit() || ch in safeAsciiChars)
    ) {
        append(ch)
        return
    }
    for (b in String(Character.toChars(cp)).toByteArray(StandardCharsets.UTF_8)) {
        val unsigned = b.toInt() and IPV4_OCTET_MAX
        append('%')
        append(HEX_UPPER[unsigned ushr HEX_NIBBLE_SHIFT])
        append(HEX_UPPER[unsigned and HEX_LOW_NIBBLE_MASK])
    }
}

private fun isPercentTripletAt(text: String, idx: Int): Boolean =
    text[idx] == '%' &&
        idx + 2 < text.length &&
        isAsciiHexDigit(text[idx + 1]) &&
        isAsciiHexDigit(text[idx + 2])

private fun isAsciiHexDigit(ch: Char): Boolean = ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

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

internal const val STARRED_COLLECTION_ID = "533b1315-6e2e-57d3-ba85-dc88531c5a0b"
internal const val UP_NEXT_COLLECTION_ID = "3864ff37-5ed3-5256-9380-f85ff8054f1c"
const val WANT_TO_READ_SLUG = "want:read"
const val WANT_TO_READ_TITLE = "Want to Read"
val WANT_TO_READ_COLLECTION_ID: String = systemCollectionUuid(WANT_TO_READ_SLUG)

internal fun tagNaturalKey(name: String): String = name.trim().lowercase()

internal fun deterministicFeedId(url: String): String =
    deterministicEntityUuid("feed", feedNaturalKey(url))

internal fun deterministicItemId(feedUrl: String?, itemUrl: String): String =
    deterministicEntityUuid("item", itemNaturalKey(feedUrl, itemUrl))

internal fun deterministicTagId(name: String): String =
    deterministicEntityUuid("tag", tagNaturalKey(name))

internal fun systemCollectionUuid(slug: String): String =
    deterministicEntityUuid("system_collection", slug.trim().lowercase())

internal fun deterministicCatalogEntryId(kind: String, scheme: String, value: String): String =
    deterministicEntityUuid(
        "entry",
        "${kind.trim().lowercase()}\u0000${scheme.trim().lowercase()}\u0000${value.trim()}",
    )

internal fun deterministicTitleEntryId(kind: String, title: String): String =
    deterministicEntityUuid(
        "entry_title",
        "${kind.trim().lowercase()}\u0000${title.trim().lowercase()}",
    )

internal fun deterministicConsumptionLogId(
    entryId: String,
    tsMillis: Long,
    status: String,
): String =
    deterministicEntityUuid(
        "consumption_log",
        "${entryId.trim()}\u0000$tsMillis\u0000${status.trim().lowercase()}",
    )

internal fun deterministicAutoLogId(entryId: String): String =
    deterministicEntityUuid("auto_consumption_log", entryId.trim())

internal fun canonicalOrderingJson(ordering: List<List<String>>): String =
    ordering.joinToString(prefix = "[", postfix = "]", separator = ",") { group ->
        group
            .map { it.trim() }
            .sorted()
            .joinToString(prefix = "[", postfix = "]", separator = ",") { id ->
                buildString(id.length + 2) {
                    append('"')
                    for (ch in id) {
                        when (ch) {
                            '\\' -> append("\\\\")
                            '"' -> append("\\\"")
                            else -> append(ch)
                        }
                    }
                    append('"')
                }
            }
    }

internal fun deterministicComparisonId(
    scopeCollectionId: String?,
    tsMillis: Long,
    ordering: List<List<String>>,
): String {
    val scopeKey = scopeCollectionId?.trim()?.takeIf { it.isNotEmpty() } ?: "global"
    val ordJson = canonicalOrderingJson(ordering)
    return deterministicEntityUuid("comparison", "$scopeKey\u0000$tsMillis\u0000$ordJson")
}

internal fun collectionEntryNaturalKey(collectionId: String, entryId: String): String =
    "${collectionId.trim()}\u0000${entryId.trim()}"

/**
 * Maps `(peerId, feedUrl, epoch)` deterministically to a uniform value in `(0.0, 1.0)` using the
 * top 53 bits of SHA-256. Bit-for-bit identical across Rust and Kotlin/JVM.
 */
internal fun rendezvousUniformUnit(peerId: String, feedUrl: String, epoch: Long): Double {
    val digest =
        MessageDigest.getInstance("SHA-256").run {
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
    feed: FeedRow,
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
    feed: FeedRow,
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
    val split = splitHierarchicalUrl(rawUrl.trim()) ?: return false
    val allowedScheme = split.scheme == SCHEME_HTTP || split.scheme == SCHEME_HTTPS
    val allowedPort =
        split.port == -1 || split.port == DEFAULT_HTTP_PORT || split.port == DEFAULT_HTTPS_PORT
    return allowedScheme &&
        split.userInfo == null &&
        allowedPort &&
        split.host.isNotBlank() &&
        isPublicHost(split.host)
}

internal fun isSyncableItemUrl(rawUrl: String): Boolean {
    val split = splitHierarchicalUrl(rawUrl.trim()) ?: return false
    val host = split.host.trim('[', ']')
    return (split.scheme == SCHEME_HTTP || split.scheme == SCHEME_HTTPS) &&
        split.userInfo == null &&
        host.isNotBlank()
}

internal fun isPublicHost(rawHost: String): Boolean {
    val lower = rawHost.trim().lowercase(Locale.ROOT)
    if (lower.startsWith("[") && lower.endsWith("]")) {
        return isPublicIpv6Literal(lower)
    }
    val normalized = lower.trimEnd('.')
    val labels = normalized.split('.')
    return when {
        normalized.isEmpty() || '.' !in normalized || labels.any { it.isEmpty() } -> false
        normalized.all { it.isDigit() || it == '.' } -> isPublicIpv4Literal(normalized)
        labels.last().all { it.isDigit() } -> false
        else -> DISALLOWED_DOMAIN_SUFFIXES.none { normalized.endsWith(it) }
    }
}

internal fun isPublicInetAddress(addr: InetAddress): Boolean =
    if (addr is Inet4Address) {
        isPublicIpv4Bytes(addr.address)
    } else {
        isPublicIpv6Address(addr)
    }

internal fun isLoopbackHost(rawHost: String): Boolean {
    val lower = rawHost.trim().lowercase(Locale.ROOT).trimEnd('.')
    return lower == "localhost" ||
        lower == "10.0.2.2" ||
        lower == "[::1]" ||
        lower == "::1" ||
        lower.startsWith("127.") &&
            lower.split('.').let { parts ->
                parts.size == IPV4_OCTET_COUNT &&
                    parts.all { it.toIntOrNull() in 0..IPV4_OCTET_MAX }
            }
}

internal fun isTailnetOnlyHost(rawHost: String): Boolean {
    val lower = rawHost.trim().lowercase(Locale.ROOT).trimEnd('.')
    return lower == "roche" || isTailscaleHostOrIp(lower)
}

internal fun isTailscaleHostOrIp(rawHost: String): Boolean {
    val lower = rawHost.trim().lowercase(Locale.ROOT).trimEnd('.')
    if (lower.endsWith(TS_NET_SUFFIX) && lower.length > TS_NET_SUFFIX.length) return true
    if (lower.startsWith("[") && lower.endsWith("]")) {
        return runCatching { isTailscaleInetAddress(InetAddress.getByName(lower)) }
            .getOrDefault(false)
    }
    val octets =
        lower.split('.').mapNotNull { part ->
            part.toIntOrNull()?.takeIf { it in 0..IPV4_OCTET_MAX }
        }
    return octets.size == IPV4_OCTET_COUNT &&
        octets[0] == IPV4_CGNAT_100 &&
        octets[1] in IPV4_CGNAT_MIN..IPV4_CGNAT_MAX
}

internal fun isTailscaleInetAddress(addr: InetAddress): Boolean {
    val bytes = addr.address
    return if (addr is Inet4Address) {
        bytes.size == IPV4_OCTET_COUNT &&
            bytes[0].toInt() and IPV4_OCTET_MAX == IPV4_CGNAT_100 &&
            bytes[1].toInt() and IPV4_OCTET_MAX in IPV4_CGNAT_MIN..IPV4_CGNAT_MAX
    } else {
        bytes.size == IPV6_BYTE_COUNT &&
            TAILSCALE_ULA_PREFIX.indices.all { idx ->
                bytes[idx].toInt() and IPV4_OCTET_MAX == TAILSCALE_ULA_PREFIX[idx]
            }
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
    isPublicInetAddress(InetAddress.getByName(bracketedHost))
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
private const val MAX_URL_PORT = 65_535
private const val HEX_UPPER = "0123456789ABCDEF"
private const val URL_USERINFO_SAFE = "-._~!$&'()*+,;=:"
private const val URL_PATH_SAFE = "-._~!$&'()*+,;=:@"
private const val URL_QUERY_FRAGMENT_SAFE = "-._~!$&'()*+,;=:@/?%"
private const val IPV4_OCTET_COUNT = 4
private const val IPV6_BYTE_COUNT = 16
private const val IPV4_OCTET_MAX = 255
private const val TAILSCALE_ULA_B0 = 0xfd
private const val TAILSCALE_ULA_B1 = 0x7a
private const val TAILSCALE_ULA_B2 = 0x11
private const val TAILSCALE_ULA_B3 = 0x5c
private const val TAILSCALE_ULA_B4 = 0xa1
private const val TAILSCALE_ULA_B5 = 0xe0
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
private const val TS_NET_SUFFIX = ".ts.net"
private const val MISSING_DELIM = "\u0000"
private const val URL_SCHEME_SEP_LEN = 3
private const val PERCENT_TRIPLET_LEN = 3
private const val ASCII_PRINTABLE_MIN = 0x21
private const val ASCII_PRINTABLE_MAX = 0x7E
private const val HEX_NIBBLE_SHIFT = 4
private const val HEX_LOW_NIBBLE_MASK = 0x0F
private val TAILSCALE_ULA_PREFIX =
    intArrayOf(
        TAILSCALE_ULA_B0,
        TAILSCALE_ULA_B1,
        TAILSCALE_ULA_B2,
        TAILSCALE_ULA_B3,
        TAILSCALE_ULA_B4,
        TAILSCALE_ULA_B5,
    )
private val DISALLOWED_DOMAIN_SUFFIXES =
    listOf(".local", ".lan", ".internal", ".home.arpa", TS_NET_SUFFIX, ".localhost", ".home")
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

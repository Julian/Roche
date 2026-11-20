package com.grayvines.roche.data.settings

import com.grayvines.roche.core.HTTPS_URL_PREFIX
import com.grayvines.roche.core.HTTP_URL_PREFIX
import com.grayvines.roche.core.isHttpOrHttpsUrl
import com.grayvines.roche.core.isLoopbackHost
import com.grayvines.roche.core.isTailscaleCgnatIpv4
import com.grayvines.roche.model.AppTab
import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.ThemeMode
import java.net.URI

data class Settings(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val bearerToken: String = "",
    val peerId: String = DEFAULT_PEER_ID,
    val deviceName: String = DEFAULT_DEVICE_NAME,
    val swipeToArchive: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val headlineFont: HeadlineFont = HeadlineFont.SERIF,
    val ultraDenseRows: Boolean = false,
    val readerFontScale: Float = DEFAULT_READER_FONT_SCALE,
    val bottomNavTabs: List<AppTab> = AppTab.defaultSideTabs,
    val lastPeerSyncCursor: String = "",
    val queueItemIds: List<String> = emptyList(),
    val defaultPlaybackSpeed: Float = DEFAULT_PLAYBACK_SPEED,
    val skipSilenceEnabled: Boolean = false,
    val smartRewindEnabled: Boolean = true,
    val showCollectionDrawsOnHome: Boolean = true,
    val hasUndecryptableToken: Boolean = false,
) {
    companion object {
        const val DEFAULT_SERVER_URL = ""
        const val DEFAULT_PEER_ID = "android-peer"
        const val DEFAULT_DEVICE_NAME = "Roche Android"
        const val DEFAULT_READER_FONT_SCALE = 1.0f
        const val MIN_READER_FONT_SCALE = 0.85f
        const val MAX_READER_FONT_SCALE = 1.35f
        const val DEFAULT_PLAYBACK_SPEED = 1.0f
        const val MIN_PLAYBACK_SPEED = 0.50f
        const val MAX_PLAYBACK_SPEED = 3.50f
        const val PLAYBACK_SPEED_STEP = 0.05f
    }
}

fun clampPlaybackSpeed(raw: Float): Float =
    if (!raw.isFinite()) {
        Settings.DEFAULT_PLAYBACK_SPEED
    } else {
        (kotlin.math.round(raw * PLAYBACK_SPEED_STEPS_PER_UNIT) / PLAYBACK_SPEED_STEPS_PER_UNIT)
            .coerceIn(
                Settings.MIN_PLAYBACK_SPEED,
                Settings.MAX_PLAYBACK_SPEED,
            )
    }

val PLAYBACK_SPEED_PRESETS: List<Float> = listOf(1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f)

private const val PLAYBACK_SPEED_STEPS_PER_UNIT = 20f

fun Settings.clamped(): Settings =
    copy(
        serverUrl = normalizeServerUrl(serverUrl),
        bearerToken = bearerToken.trim(),
        peerId = peerId.trim().ifBlank { Settings.DEFAULT_PEER_ID },
        deviceName = deviceName.trim().ifBlank { Settings.DEFAULT_DEVICE_NAME },
        readerFontScale =
            readerFontScale
                .takeIf { it.isFinite() }
                ?.coerceIn(Settings.MIN_READER_FONT_SCALE, Settings.MAX_READER_FONT_SCALE)
                ?: Settings.DEFAULT_READER_FONT_SCALE,
        bottomNavTabs = AppTab.normalizeSideTabs(bottomNavTabs),
        lastPeerSyncCursor = lastPeerSyncCursor.trim(),
        queueItemIds = queueItemIds.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
        defaultPlaybackSpeed = clampPlaybackSpeed(defaultPlaybackSpeed),
    )

internal fun normalizeServerUrl(rawUrl: String): String {
    val trimmedUrl = rawUrl.trim().trimEnd('/')
    if (trimmedUrl.isBlank()) return ""
    val withScheme = resolveCandidateWithScheme(trimmedUrl) ?: return ""
    return parseCleanOriginUri(withScheme).orEmpty()
}

private fun resolveCandidateWithScheme(trimmedUrl: String): String? {
    val lower = trimmedUrl.lowercase()
    if (isHttpOrHttpsUrl(trimmedUrl)) {
        return trimmedUrl
    }
    if ("://" in lower || lower.startsWith("javascript:") || lower.startsWith("file:")) {
        return null
    }
    val hostPart = extractHostWithoutPort(trimmedUrl).lowercase()
    val prefix =
        if (isLoopbackHost(hostPart)) {
            HTTP_URL_PREFIX
        } else {
            HTTPS_URL_PREFIX
        }
    return "$prefix$trimmedUrl"
}

private fun extractHostWithoutPort(authority: String): String =
    if (authority.startsWith("[")) {
        authority.substringBefore(']') + "]"
    } else {
        authority.substringBefore(':')
    }

private fun parseCleanOriginUri(candidate: String): String? = runCatching {
    val uri = URI(candidate)
    val rawScheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    if (!isValidOriginUri(uri, rawScheme, host)) {
        null
    } else if (uri.port == -1) {
        "$rawScheme://$host"
    } else {
        "$rawScheme://$host:${uri.port}"
    }
}
    .getOrNull()

internal fun isValidOriginUri(uri: URI, rawScheme: String, host: String): Boolean {
    val validScheme = rawScheme == "http" || rawScheme == "https"
    val validPort = uri.port == -1 || uri.port in 1..MAX_TCP_PORT
    return validScheme && hasNoUriExtras(uri) && validPort && host.isNotBlank()
}

private fun hasNoUriExtras(uri: URI): Boolean =
    uri.rawUserInfo == null &&
        (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
        uri.rawQuery == null &&
        uri.rawFragment == null

internal fun parsePairingInput(
    rawServerUrl: String,
    rawPairingCode: String,
): Pair<String, String> {
    val trimmedCode = rawPairingCode.trim()
    val hashCount = trimmedCode.count { it == '#' }
    if (hashCount > 1) return "" to ""
    if (hashCount == 1) {
        val urlPart = normalizeServerUrl(trimmedCode.substringBefore('#'))
        val codePart = trimmedCode.substringAfter('#').trim()
        val validCode =
            codePart.isNotBlank() && codePart.none { it.isWhitespace() || it.isISOControl() }
        return if (urlPart.isNotBlank() && validCode) urlPart to codePart else "" to ""
    }
    return normalizeServerUrl(rawServerUrl) to trimmedCode
}

internal fun tailscaleDiscoveryUrls(tailnetDomains: List<String> = emptyList()): List<String> {
    val candidates = mutableListOf<String>()
    tailnetDomains
        .map { it.trim().trim('.').lowercase() }
        .filter { isValidDnsName(it) }
        .distinct()
        .forEach { domain ->
            val fqdnSuffix = if (domain.endsWith(TS_NET_SUFFIX)) domain else null
            if (fqdnSuffix != null) {
                candidates.add("$SCHEME_HTTPS_PREFIX$HOST_ROCHE.$fqdnSuffix")
            } else {
                candidates.add("$SCHEME_HTTPS_PREFIX$HOST_ROCHE.$domain")
            }
        }
    candidates.add("$SCHEME_HTTPS_PREFIX$HOST_ROCHE")
    return candidates.distinct()
}

internal fun tailscaleHttpsUpgrades(rawServerUrl: String): List<String> {
    val clean = parseCleanOriginUri(rawServerUrl.trim().trimEnd('/')) ?: return emptyList()
    val host = runCatching { URI(clean).host?.lowercase() }.getOrNull() ?: return emptyList()
    if (!isValidDnsName(host) || !host.endsWith(TS_NET_SUFFIX)) return emptyList()
    val suffix = extractTsNetSuffix(host)?.let { "$it$TS_NET_SUFFIX" }
    return buildList {
        if (suffix != null) {
            add("$SCHEME_HTTPS_PREFIX$HOST_ROCHE.$suffix")
        }
        add("$SCHEME_HTTPS_PREFIX$host")
        add("$SCHEME_HTTPS_PREFIX$host:$FALLBACK_ROCHE_HTTPS_PORT")
    }
        .distinct()
}

internal fun keepTokenOnDiscovery(previousUrl: String, discoveredUrl: String): Boolean {
    if (previousUrl == discoveredUrl) return true
    if (!discoveredUrl.startsWith(SCHEME_HTTPS_PREFIX)) return false
    val prevHost = runCatching { URI(previousUrl).host?.lowercase() }.getOrNull().orEmpty()
    val nextHost = runCatching { URI(discoveredUrl).host?.lowercase() }.getOrNull().orEmpty()
    return prevHost.isNotEmpty() && prevHost == nextHost
}

internal fun extractTailnetDomains(rawDomainsList: Iterable<String>): List<String> =
    rawDomainsList
        .asSequence()
        .flatMap { raw -> raw.split(',', ' ', '\t', '\n', '\r') }
        .map { it.trim().trim('.').lowercase() }
        .filter { it.endsWith(TS_NET_SUFFIX) && isValidDnsName(it) }
        .distinct()
        .toList()

internal fun validateCanonicalDiscoveryUrl(
    rawCanonical: String,
    probedBaseUrl: String,
): String? {
    val trimmed = rawCanonical.trim().trimEnd('/')
    val lower = trimmed.lowercase()
    if (!lower.startsWith(SCHEME_HTTP_PREFIX) && !lower.startsWith(SCHEME_HTTPS_PREFIX)) {
        return null
    }
    val cleanCanonical = parseCleanOriginUri(trimmed) ?: return null
    val cleanProbed = parseCleanOriginUri(probedBaseUrl.trim().trimEnd('/')) ?: return null
    val probedHost = runCatching { URI(cleanProbed).host?.lowercase() }.getOrNull() ?: return null
    val isLocalProbe = probedHost in LOCAL_HTTP_PROBE_HOSTS
    val requiresHttps = cleanProbed.startsWith(SCHEME_HTTPS_PREFIX) || !isLocalProbe
    if (requiresHttps && !cleanCanonical.startsWith(SCHEME_HTTPS_PREFIX)) return null
    val canonicalHost =
        runCatching { URI(cleanCanonical).host?.lowercase() }.getOrNull() ?: return null
    return if (isTrustedCanonicalHost(canonicalHost, probedHost)) cleanCanonical else null
}

private fun isTrustedCanonicalHost(canonicalHost: String, probedHost: String): Boolean {
    if (canonicalHost == probedHost) return true
    if (!isValidDnsName(canonicalHost) || !canonicalHost.endsWith(TS_NET_SUFFIX)) return false
    if (probedHost in TRUSTED_LOCAL_PROBE_HOSTS || isTailscaleCgnatIpv4(probedHost)) {
        return true
    }
    val canonicalTailnet = extractTsNetSuffix(canonicalHost)
    val probedTailnet = extractTsNetSuffix(probedHost)
    return if (canonicalTailnet != null && probedTailnet != null) {
        canonicalTailnet == probedTailnet
    } else {
        canonicalHost.startsWith("$probedHost.")
    }
}

private fun extractTsNetSuffix(host: String): String? {
    if (!host.endsWith(TS_NET_SUFFIX)) return null
    val withoutTs = host.removeSuffix(TS_NET_SUFFIX)
    return withoutTs.substringAfterLast('.').takeIf { it.isNotBlank() }
}

internal fun isValidDnsName(name: String): Boolean =
    name.isNotEmpty() &&
        name.length <= MAX_DNS_NAME_LEN &&
        name.split('.').all { isValidDnsLabel(it) }

internal fun isValidDnsLabel(label: String): Boolean =
    label.isNotEmpty() &&
        label.length <= MAX_DNS_LABEL_LEN &&
        !label.startsWith('-') &&
        !label.endsWith('-') &&
        label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

internal const val DEFAULT_ROCHE_PORT = 8080
private const val FALLBACK_ROCHE_HTTPS_PORT = 8443
private const val HOST_ROCHE = "roche"
private const val HOST_LOCALHOST = "localhost"
private const val HOST_LOOPBACK_V4 = "127.0.0.1"
private const val HOST_LOOPBACK_V6 = "[::1]"
private const val HOST_EMULATOR = "10.0.2.2"
private const val SCHEME_HTTP_PREFIX = HTTP_URL_PREFIX
private const val SCHEME_HTTPS_PREFIX = HTTPS_URL_PREFIX
private const val TS_NET_SUFFIX = ".ts.net"
private val LOCAL_HTTP_PROBE_HOSTS =
    setOf(HOST_LOOPBACK_V4, HOST_LOCALHOST, HOST_EMULATOR, HOST_LOOPBACK_V6)
private val TRUSTED_LOCAL_PROBE_HOSTS = setOf(HOST_ROCHE, HOST_LOOPBACK_V4, HOST_LOCALHOST)
private const val MAX_TCP_PORT = 65_535
private const val MAX_DNS_NAME_LEN = 253
private const val MAX_DNS_LABEL_LEN = 63

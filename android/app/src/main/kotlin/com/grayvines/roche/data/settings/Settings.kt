package com.grayvines.roche.data.settings

import com.grayvines.roche.model.HeadlineFont
import com.grayvines.roche.model.ThemeMode
import java.net.URI

data class Settings(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val bearerToken: String = "",
    val swipeToArchive: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val headlineFont: HeadlineFont = HeadlineFont.SERIF,
    val ultraDenseRows: Boolean = false,
) {
    companion object {
        const val DEFAULT_SERVER_URL = ""
    }
}

fun Settings.clamped(): Settings =
    copy(
        serverUrl = normalizeServerUrl(serverUrl),
        bearerToken = bearerToken.trim(),
    )

internal fun normalizeServerUrl(rawUrl: String): String {
    val trimmedUrl = rawUrl.trim().trimEnd('/')
    if (trimmedUrl.isBlank()) return ""
    val withScheme = resolveCandidateWithScheme(trimmedUrl) ?: return ""
    return parseCleanOriginUri(withScheme).orEmpty()
}

private fun resolveCandidateWithScheme(trimmedUrl: String): String? {
    val lower = trimmedUrl.lowercase()
    if (lower == HOST_ROCHE) return "$SCHEME_HTTP_PREFIX$HOST_ROCHE:$DEFAULT_ROCHE_PORT"
    if (lower.startsWith(SCHEME_HTTP_PREFIX) || lower.startsWith(SCHEME_HTTPS_PREFIX)) {
        return trimmedUrl
    }
    if ("://" in lower || lower.startsWith("javascript:") || lower.startsWith("file:")) {
        return null
    }
    val hostPart = extractHostWithoutPort(trimmedUrl).lowercase()
    val explicitPort = extractExplicitPort(trimmedUrl)
    val prefix =
        if (shouldUseCleartextHttp(hostPart, explicitPort)) {
            SCHEME_HTTP_PREFIX
        } else {
            SCHEME_HTTPS_PREFIX
        }
    return "$prefix$trimmedUrl"
}

private fun shouldUseCleartextHttp(hostPart: String, explicitPort: String): Boolean {
    val isCleartextHost =
        hostPart in CLEARTEXT_EXACT_HOSTS ||
            hostPart.startsWith("127.") ||
            hostPart.startsWith("100.") ||
            hostPart.startsWith("[")
    val hasNonTlsPort = explicitPort.isNotBlank() && explicitPort != "443"
    return isCleartextHost || hasNonTlsPort
}

private fun extractHostWithoutPort(authority: String): String =
    if (authority.startsWith("[")) {
        authority.substringBefore(']') + "]"
    } else {
        authority.substringBefore(':')
    }

private fun extractExplicitPort(authority: String): String =
    if (authority.startsWith("[")) {
        authority.substringAfter("]:", missingDelimiterValue = "")
    } else {
        authority.substringAfter(':', missingDelimiterValue = "")
    }

private fun parseCleanOriginUri(candidate: String): String? = runCatching {
    val uri = URI(candidate)
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    val validOrigin =
        (scheme == "http" || scheme == "https") &&
            hasNoUriExtras(uri) &&
            (uri.port == -1 || uri.port in 1..MAX_TCP_PORT) &&
            host.isNotBlank()
    if (!validOrigin) {
        null
    } else if (uri.port == -1) {
        "$scheme://$host"
    } else {
        "$scheme://$host:${uri.port}"
    }
}
    .getOrNull()

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

internal fun tailscaleDiscoveryUrls(
    tailnetDomains: List<String> = emptyList(),
    defaultPort: Int = DEFAULT_ROCHE_PORT,
): List<String> {
    val candidates = mutableListOf<String>()
    tailnetDomains
        .map { it.trim().trim('.').lowercase() }
        .filter { isValidDnsName(it) }
        .distinct()
        .forEach { domain ->
            candidates.add("$SCHEME_HTTPS_PREFIX$HOST_ROCHE.$domain")
            candidates.add("$SCHEME_HTTP_PREFIX$HOST_ROCHE.$domain:$defaultPort")
        }
    candidates.add("$SCHEME_HTTP_PREFIX$HOST_ROCHE:$defaultPort")
    candidates.add("$SCHEME_HTTPS_PREFIX$HOST_ROCHE")
    return candidates.distinct()
}

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
    val canonicalHost =
        runCatching { URI(cleanCanonical).host?.lowercase() }.getOrNull() ?: return null
    val probedHost = runCatching { URI(cleanProbed).host?.lowercase() }.getOrNull() ?: return null
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

private fun isTailscaleCgnatIpv4(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != IPV4_OCTET_COUNT) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    return octets[0] == CGNAT_FIRST_OCTET &&
        octets[1] in CGNAT_SECOND_OCTET_MIN..CGNAT_SECOND_OCTET_MAX &&
        octets[SECOND_SUBNET_OCTET_INDEX] in 0..MAX_IPV4_OCTET &&
        octets[LAST_IPV4_OCTET_INDEX] in 0..MAX_IPV4_OCTET
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
private const val HOST_ROCHE = "roche"
private const val SCHEME_HTTP_PREFIX = "http://"
private const val SCHEME_HTTPS_PREFIX = "https://"
private const val TS_NET_SUFFIX = ".ts.net"
private val CLEARTEXT_EXACT_HOSTS = setOf("localhost", HOST_ROCHE, "10.0.2.2")
private val TRUSTED_LOCAL_PROBE_HOSTS = setOf(HOST_ROCHE, "127.0.0.1", "localhost")
private const val MAX_TCP_PORT = 65_535
private const val MAX_DNS_NAME_LEN = 253
private const val MAX_DNS_LABEL_LEN = 63
private const val IPV4_OCTET_COUNT = 4
private const val SECOND_SUBNET_OCTET_INDEX = 2
private const val LAST_IPV4_OCTET_INDEX = 3
private const val CGNAT_FIRST_OCTET = 100
private const val CGNAT_SECOND_OCTET_MIN = 64
private const val CGNAT_SECOND_OCTET_MAX = 127
private const val MAX_IPV4_OCTET = 255

package com.grayvines.roche.data.remote

import android.security.NetworkSecurityPolicy
import com.grayvines.roche.core.isAllowedPublicPort
import com.grayvines.roche.core.isLoopbackHost
import com.grayvines.roche.core.isPublicHost
import com.grayvines.roche.core.isPublicInetAddress
import com.grayvines.roche.core.isTailnetOnlyHost
import com.grayvines.roche.core.isTailscaleHostOrIp
import com.grayvines.roche.core.isTailscaleInetAddress
import java.io.IOException
import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.util.Locale

private const val SCHEME_HTTP = "http"
private const val SCHEME_HTTPS = "https"

internal object PublicUrlValidator {
    fun ensurePublicUrlSyntax(rawUrl: String): String {
        val uri = parseHttpUriOrThrow(rawUrl)
        val asciiHost = extractAsciiHostOrThrow(uri)
        if (!isPublicHost(asciiHost)) {
            throw IOException("Blocked non-public upstream host: $asciiHost")
        }
        val port = extractPortOrThrow(uri)
        if (!isAllowedPublicPort(port)) {
            throw IOException("Blocked disallowed upstream port: $port")
        }
        return asciiHost
    }

    fun ensurePublicTarget(
        rawUrl: String,
        resolveAddresses: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    ) {
        val asciiHost = ensurePublicUrlSyntax(rawUrl)
        val lookupHost = asciiHost.trim('[', ']')
        val resolved = runCatching {
            resolveAddresses(lookupHost)
        }
            .getOrElse { err ->
                throw IOException("Failed to resolve upstream host: $asciiHost", err)
            }
        if (resolved.isEmpty() || resolved.any { !isPublicInetAddress(it) }) {
            throw IOException("Blocked private or local IP for upstream host: $asciiHost")
        }
    }

    fun isServerEndpointAllowed(
        rawServerUrl: String,
        isVpnActive: () -> Boolean,
    ): Boolean = runCatching { ensureSafeServerEndpoint(rawServerUrl, isVpnActive) }.isSuccess

    fun ensureSafeServerEndpoint(
        rawServerUrl: String,
        isVpnActive: () -> Boolean,
    ) {
        val uri = parseHttpUriOrThrow(rawServerUrl)
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        val asciiHost = extractAsciiHostOrThrow(uri)
        val violation = serverEndpointViolation(scheme, asciiHost, isVpnActive)
        if (violation != null) {
            throw IOException(violation)
        }
    }

    fun ensureResolvedServerTarget(
        rawUrl: String,
        resolveAddresses: (String) -> Array<InetAddress> = InetAddress::getAllByName,
        isCleartextPermitted: (String) -> Boolean = ::isCleartextAllowed,
    ) {
        val uri = parseHttpUriOrThrow(rawUrl)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return
        val asciiHost = extractAsciiHostOrThrow(uri)
        if (isLoopbackHost(asciiHost)) return
        val lookupHost = asciiHost.trim('[', ']')
        checkCleartextServerPolicy(scheme, asciiHost, lookupHost, isCleartextPermitted)
        val resolved = runCatching {
            resolveAddresses(lookupHost)
        }
            .getOrElse { err ->
                throw IOException("Failed to resolve server host: $asciiHost", err)
            }
        val addrViolation = serverAddrViolation(scheme, asciiHost, resolved)
        if (addrViolation != null) {
            throw IOException(addrViolation)
        }
    }

    fun upgradePublicHttp(
        rawUrl: String,
        isCleartextPermitted: (String) -> Boolean = ::isCleartextAllowed,
    ): String {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith(HTTP_SCHEME_HTTP, ignoreCase = true)) {
            return trimmed
        }
        val uri = runCatching { parseHttpUriOrThrow(trimmed) }.getOrNull() ?: return trimmed
        val asciiHost = runCatching { extractAsciiHostOrThrow(uri) }.getOrNull() ?: return trimmed
        val lookupHost = asciiHost.trim('[', ']')
        return if (
            !isLoopbackHost(asciiHost) &&
                isPublicHost(asciiHost) &&
                !isCleartextPermitted(lookupHost)
        ) {
            "$HTTP_SCHEME_HTTPS${trimmed.substring(HTTP_SCHEME_HTTP.length)}"
        } else {
            trimmed
        }
    }
}

internal fun isCleartextAllowed(lookupHost: String): Boolean = runCatching {
    NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(lookupHost)
}
    .getOrDefault(false)

internal fun serverEndpointViolation(
    scheme: String,
    asciiHost: String,
    isVpnActive: () -> Boolean,
): String? {
    if (isLoopbackHost(asciiHost)) return null
    if (isTailnetOnlyHost(asciiHost) && !isVpnActive()) {
        return "Tailscale VPN must be active for Tailnet server requests ($asciiHost)"
    }
    return if (scheme != SCHEME_HTTPS) {
        "HTTPS required for $asciiHost; run 'roche serve' and tap Discover on Tailnet"
    } else {
        null
    }
}

internal fun checkCleartextServerPolicy(
    scheme: String,
    asciiHost: String,
    lookupHost: String,
    isCleartextPermitted: (String) -> Boolean,
) {
    if (scheme != SCHEME_HTTP) return
    if (!isLoopbackHost(asciiHost) || !isCleartextPermitted(lookupHost)) {
        throw IOException(
            "HTTPS required for $asciiHost; run 'roche serve' and tap Discover on Tailnet"
        )
    }
}

internal fun serverAddrViolation(
    scheme: String,
    asciiHost: String,
    resolved: Array<InetAddress>,
): String? =
    when {
        resolved.isEmpty() -> "Server host $asciiHost resolved to no addresses"
        scheme == SCHEME_HTTP || asciiHost == "roche" ->
            if (resolved.any { !isTailscaleInetAddress(it) }) {
                "Server host $asciiHost did not resolve to a Tailscale IP"
            } else {
                null
            }
        isTailscaleHostOrIp(asciiHost) ->
            if (resolved.any { !isTailscaleInetAddress(it) && !isPublicInetAddress(it) }) {
                "Tailscale server host $asciiHost resolved to an untrusted private/local IP"
            } else {
                null
            }
        resolved.any(::isSpecialServerAddr) ->
            "Server host $asciiHost resolved to a disallowed local/link-local IP"
        else -> null
    }

private fun isSpecialServerAddr(addr: InetAddress): Boolean =
    addr.isLoopbackAddress ||
        addr.isAnyLocalAddress ||
        addr.isLinkLocalAddress ||
        addr.isMulticastAddress

internal fun parseHttpUriOrThrow(rawUrl: String): URI {
    val sanitized = rawUrl.trim().replace(" ", "%20")
    val uri = runCatching {
        URI(sanitized)
    }
        .getOrElse { err -> throw IOException("Malformed URL", err) }
    validateHttpUriInvariants(uri)
    return uri
}

private fun validateHttpUriInvariants(uri: URI) {
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme != SCHEME_HTTP && scheme != SCHEME_HTTPS) {
        throw IOException("URL must use http:// or https://")
    }
    if (uri.rawUserInfo != null) {
        throw IOException("URL must not contain embedded credentials")
    }
}

internal fun extractAsciiHostOrThrow(uri: URI): String {
    val directHost = uri.host?.trim()?.takeIf { it.isNotEmpty() }
    if (directHost != null) {
        return if (directHost.startsWith("[")) {
            directHost.lowercase(Locale.ROOT)
        } else {
            IDN.toASCII(directHost, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
        }
    }
    val authority =
        uri.rawAuthority?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IOException("URL is missing host")
    val rawHost =
        if (authority.startsWith("[")) {
            authority.substringBefore(']') + "]"
        } else {
            authority.substringBefore(':')
        }
    val ascii = runCatching {
        IDN.toASCII(rawHost, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
    }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
    return ascii ?: throw IOException("URL has invalid host")
}

internal fun extractPortOrThrow(uri: URI): Int {
    if (uri.host != null) return uri.port
    val authority = uri.rawAuthority?.trim().orEmpty()
    val hostAndPort = authority.substringAfterLast('@')
    val portPart =
        when {
            hostAndPort.startsWith("[") -> hostAndPort.substringAfter(']', "").removePrefix(":")
            ':' in hostAndPort -> hostAndPort.substringAfterLast(':')
            else -> ""
        }
    if (portPart.isEmpty()) return -1
    return portPart.toIntOrNull()?.takeIf { it > 0 } ?: throw IOException("URL has invalid port")
}

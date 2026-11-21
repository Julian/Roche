package com.grayvines.roche.data.remote

import android.security.NetworkSecurityPolicy
import com.grayvines.roche.BuildConfig
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

internal data class ResolvedPublicTarget(
    val canonicalUrl: String,
    val scheme: String,
    val asciiHost: String,
    val port: Int,
    val addresses: List<InetAddress>,
)

internal object PublicUrlValidator {
    fun ensurePublicUrlSyntax(rawUrl: String): String = validatePublicHttpTarget(rawUrl).asciiHost

    fun resolvePublicTarget(
        rawUrl: String,
        resolveAddresses: (String) -> Array<InetAddress> = InetAddress::getAllByName,
        isCleartextPermitted: (String) -> Boolean = ::isCleartextAllowed,
        upgradeCleartext: Boolean = false,
    ): ResolvedPublicTarget {
        val candidateUrl =
            if (upgradeCleartext) {
                upgradePublicHttp(rawUrl, isCleartextPermitted)
            } else {
                rawUrl
            }
        val validated = validatePublicHttpTarget(candidateUrl)
        val lookupHost = validated.asciiHost.trim('[', ']')
        val resolved = runCatching {
            resolveAddresses(lookupHost)
        }
            .getOrElse { err ->
                throw IOException("Failed to resolve upstream host: ${validated.asciiHost}", err)
            }
        if (resolved.isEmpty() || resolved.any { !isPublicInetAddress(it) }) {
            throw IOException(
                "Blocked private or local IP for upstream host: ${validated.asciiHost}"
            )
        }
        val effectivePort =
            if (validated.port > 0) {
                validated.port
            } else if (validated.scheme == SCHEME_HTTPS) {
                DEFAULT_HTTPS_PORT
            } else {
                DEFAULT_HTTP_PORT
            }
        return ResolvedPublicTarget(
            canonicalUrl = validated.canonicalUrl,
            scheme = validated.scheme,
            asciiHost = validated.asciiHost,
            port = effectivePort,
            addresses = resolved.toList(),
        )
    }

    fun ensurePublicTarget(
        rawUrl: String,
        resolveAddresses: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    ): String =
        resolvePublicTarget(
                rawUrl = rawUrl,
                resolveAddresses = resolveAddresses,
                upgradeCleartext = false,
            )
            .canonicalUrl

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
        allowCleartextLoopback: Boolean = BuildConfig.DEBUG,
    ): String {
        val uri = parseHttpUriOrThrow(rawUrl)
        val scheme = uri.scheme.orEmpty().lowercase(Locale.ROOT)
        val asciiHost = extractAsciiHostOrThrow(uri)
        val port = extractPortOrThrow(uri)
        val canonicalUrl = reconstructCanonicalHttpUrl(uri, scheme, asciiHost, port)
        if (isLoopbackHost(asciiHost)) {
            if (scheme != SCHEME_HTTPS && !allowCleartextLoopback) {
                checkCleartextServerPolicy(scheme, asciiHost)
            }
            return canonicalUrl
        }
        val lookupHost = asciiHost.trim('[', ']')
        checkCleartextServerPolicy(scheme, asciiHost)
        val resolved = runCatching {
            resolveAddresses(lookupHost)
        }
            .getOrElse { err ->
                throw IOException("Failed to resolve server host: $asciiHost", err)
            }
        val addrViolation = serverAddrViolation(asciiHost, resolved)
        if (addrViolation != null) {
            throw IOException(addrViolation)
        }
        return canonicalUrl
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
        val port = runCatching { extractPortOrThrow(uri) }.getOrNull() ?: return trimmed
        val lookupHost = asciiHost.trim('[', ']')
        return if (
            !isLoopbackHost(asciiHost) &&
                isPublicHost(asciiHost) &&
                !isCleartextPermitted(lookupHost)
        ) {
            reconstructCanonicalHttpUrl(uri, SCHEME_HTTPS, asciiHost, port)
        } else {
            reconstructCanonicalHttpUrl(uri, SCHEME_HTTP, asciiHost, port)
        }
    }
}

internal data class ValidatedHttpTarget(
    val scheme: String,
    val asciiHost: String,
    val port: Int,
    val canonicalUrl: String,
)

internal fun validatePublicHttpTarget(rawUrl: String): ValidatedHttpTarget {
    val uri = parseHttpUriOrThrow(rawUrl)
    val scheme = uri.scheme.orEmpty().lowercase(Locale.ROOT)
    val asciiHost = extractAsciiHostOrThrow(uri)
    val port = extractPortOrThrow(uri)
    val violation =
        when {
            !isPublicHost(asciiHost) -> "Blocked non-public upstream host: $asciiHost"
            !isAllowedPublicPort(port) -> "Blocked disallowed upstream port: $port"
            else -> null
        }
    if (violation != null) {
        throw IOException(violation)
    }
    val canonicalUrl = reconstructCanonicalHttpUrl(uri, scheme, asciiHost, port)
    return ValidatedHttpTarget(
        scheme = scheme,
        asciiHost = asciiHost,
        port = port,
        canonicalUrl = canonicalUrl,
    )
}

internal fun reconstructCanonicalHttpUrl(
    uri: URI,
    scheme: String,
    asciiHost: String,
    port: Int,
): String {
    val portPart = if (port > 0) ":$port" else ""
    val pathPart = uri.rawPath.orEmpty()
    val queryPart = uri.rawQuery?.let { "?$it" }.orEmpty()
    return "$scheme://$asciiHost$portPart$pathPart$queryPart"
}

internal fun isCleartextAllowed(lookupHost: String): Boolean = runCatching {
    NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(lookupHost)
}
    .getOrDefault(false)

internal fun serverEndpointViolation(
    scheme: String,
    asciiHost: String,
    isVpnActive: () -> Boolean,
    allowCleartextLoopback: Boolean = BuildConfig.DEBUG,
): String? {
    if (isLoopbackHost(asciiHost)) {
        return if (scheme == SCHEME_HTTPS || allowCleartextLoopback) {
            null
        } else {
            "HTTPS required for $asciiHost; cleartext loopback is disabled in release builds"
        }
    }
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
) {
    if (scheme != SCHEME_HTTPS) {
        throw IOException(
            "HTTPS required for $asciiHost; run 'roche serve' and tap Discover on Tailnet"
        )
    }
}

internal fun serverAddrViolation(
    asciiHost: String,
    resolved: Array<InetAddress>,
): String? =
    when {
        resolved.isEmpty() -> "Server host $asciiHost resolved to no addresses"
        asciiHost == "roche" ->
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
    val rawAuthority = uri.rawAuthority
    val violation =
        when {
            scheme != SCHEME_HTTP && scheme != SCHEME_HTTPS -> "URL must use http:// or https://"
            uri.rawUserInfo != null || rawAuthority?.contains('@') == true ->
                "URL must not contain embedded credentials"
            rawAuthority.isNullOrBlank() || uri.host.isNullOrBlank() ->
                "URL has missing or invalid host"
            '%' in rawAuthority || '\\' in rawAuthority ->
                "URL authority contains disallowed characters"
            else -> null
        }
    if (violation != null) {
        throw IOException(violation)
    }
}

internal fun extractAsciiHostOrThrow(uri: URI): String {
    val directHost =
        uri.host?.trim()?.takeIf { it.isNotEmpty() } ?: throw IOException("URL is missing host")
    if (directHost.startsWith("[")) {
        return directHost.lowercase(Locale.ROOT)
    }
    val ascii = runCatching {
        IDN.toASCII(directHost, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
    }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
    return ascii ?: throw IOException("URL has invalid host")
}

internal fun extractPortOrThrow(uri: URI): Int {
    val port = uri.port
    if (port == -1) return -1
    return port.takeIf { it in 1..MAX_VALID_TCP_PORT } ?: throw IOException("URL has invalid port")
}

private const val MAX_VALID_TCP_PORT = 65_535
private const val DEFAULT_HTTP_PORT = 80
private const val DEFAULT_HTTPS_PORT = 443

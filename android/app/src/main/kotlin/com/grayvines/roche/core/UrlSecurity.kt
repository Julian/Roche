@file:Suppress("TooManyFunctions")

package com.grayvines.roche.core

import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale

private const val DEFAULT_HTTP_PORT = 80
private const val DEFAULT_HTTPS_PORT = 443
private const val ALT_HTTP_PORT = 8080
private const val ALT_HTTPS_PORT = 8443
private val ALLOWED_PUBLIC_PORTS =
    setOf(DEFAULT_HTTP_PORT, DEFAULT_HTTPS_PORT, ALT_HTTP_PORT, ALT_HTTPS_PORT)
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
private const val IPV4_BENCHMARK_198 = 198
private const val IPV4_BENCHMARK_MIN = 18
private const val IPV4_BENCHMARK_MAX = 19
private const val IPV4_DOC_TEST_NET_1_B2 = 2
private const val IPV4_DOC_TEST_NET_2_B1 = 51
private const val IPV4_DOC_TEST_NET_2_B2 = 100
private const val IPV4_DOC_TEST_NET_3_B0 = 203
private const val IPV4_DOC_TEST_NET_3_B2 = 113
private const val IPV4_MULTICAST_START = 224
private const val IPV6_SEGMENT_SHIFT = 8
private const val IPV6_ULA_SEG_MASK = 0xfe00
private const val IPV6_ULA_SEG_PREFIX = 0xfc00
private const val IPV6_EMBEDDED_LEAD_ZERO_SEGMENTS = 5
private const val IPV6_MAPPED_SEG_INDEX = 5
private const val IPV6_MAPPED_SEG_VALUE = 0xffff
private const val IPV6_EMBEDDED_IPV4_OFFSET = 12
private const val IPV6_NAT64_SEG0 = 0x0064
private const val IPV6_NAT64_SEG1 = 0xff9b
private const val IPV6_NAT64_LOCAL_SEG2 = 0x0001
private const val IPV6_NAT64_WELL_KNOWN_ZERO_END = 6
private const val IPV6_DISCARD_SEG0 = 0x0100
private const val IPV6_DISCARD_ZERO_END = 4
private const val IPV6_2001_SEG0 = 0x2001
private const val IPV6_DOC_SEG1 = 0x0db8
private const val IPV6_BENCHMARK_SEG1 = 0x0002
private const val IPV6_ORCHID_MASK = 0xfff0
private const val IPV6_ORCHID_V2_PREFIX = 0x0020
private const val IPV6_6TO4_SEG0 = 0x2002
private const val TS_NET_SUFFIX = ".ts.net"
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

internal fun isAllowedPublicPort(port: Int): Boolean = port == -1 || port in ALLOWED_PUBLIC_PORTS

internal fun isSyncablePublicUrl(rawUrl: String): Boolean {
    val split = splitHierarchicalUrl(rawUrl.trim()) ?: return false
    val allowedScheme = split.scheme == SCHEME_HTTP || split.scheme == SCHEME_HTTPS
    return allowedScheme &&
        split.userInfo == null &&
        isAllowedPublicPort(split.port) &&
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

internal fun isTailscaleCgnatIpv4(rawHost: String): Boolean {
    val lower = rawHost.trim().lowercase(Locale.ROOT).trimEnd('.')
    val octets =
        lower.split('.').mapNotNull { part ->
            part.toIntOrNull()?.takeIf { it in 0..IPV4_OCTET_MAX }
        }
    return octets.size == IPV4_OCTET_COUNT &&
        octets[0] == IPV4_CGNAT_100 &&
        octets[1] in IPV4_CGNAT_MIN..IPV4_CGNAT_MAX
}

internal fun isTailnetOnlyHost(rawHost: String): Boolean {
    val lower = rawHost.trim().lowercase(Locale.ROOT).trimEnd('.')
    return lower == "roche" || isTailscaleHostOrIp(lower)
}

internal fun isTailscaleHostOrIp(rawHost: String): Boolean {
    val lower = rawHost.trim().lowercase(Locale.ROOT).trimEnd('.')
    return when {
        lower.endsWith(TS_NET_SUFFIX) && lower.length > TS_NET_SUFFIX.length -> true
        lower.startsWith("[") && lower.endsWith("]") ->
            runCatching { isTailscaleInetAddress(InetAddress.getByName(lower)) }.getOrDefault(false)
        else -> isTailscaleCgnatIpv4(lower)
    }
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
    return octets.size == IPV4_OCTET_COUNT && isPublicIpv4Octets(octets[0], octets[1], octets[2])
}

private fun isPublicIpv4Bytes(bytes: ByteArray, offset: Int = 0): Boolean =
    bytes.size >= offset + IPV4_OCTET_COUNT &&
        isPublicIpv4Octets(
            bytes[offset].toInt() and IPV4_OCTET_MAX,
            bytes[offset + 1].toInt() and IPV4_OCTET_MAX,
            bytes[offset + 2].toInt() and IPV4_OCTET_MAX,
        )

private fun isPublicIpv4Octets(first: Int, second: Int, third: Int): Boolean =
    !isIpv4PrivateOrLocal(first, second) && !isIpv4DocumentationOrBenchmark(first, second, third)

private fun isIpv4PrivateOrLocal(first: Int, second: Int): Boolean =
    when {
        first == 0 || first == IPV4_LOOPBACK_127 || first == IPV4_PRIVATE_10 -> true
        first >= IPV4_MULTICAST_START -> true
        first == IPV4_PRIVATE_192 && second == IPV4_PRIVATE_168 -> true
        first == IPV4_PRIVATE_172 && second in IPV4_PRIVATE_172_MIN..IPV4_PRIVATE_172_MAX -> true
        first == IPV4_LINK_LOCAL_169 && second == IPV4_LINK_LOCAL_254 -> true
        first == IPV4_CGNAT_100 && second in IPV4_CGNAT_MIN..IPV4_CGNAT_MAX -> true
        else -> false
    }

private fun isIpv4DocumentationOrBenchmark(first: Int, second: Int, third: Int): Boolean =
    isIpv4TestNet1OrIetf(first, second, third) || isIpv4TestNet2Or3OrBenchmark(first, second, third)

private fun isIpv4TestNet1OrIetf(first: Int, second: Int, third: Int): Boolean =
    first == IPV4_PRIVATE_192 && second == 0 && (third == 0 || third == IPV4_DOC_TEST_NET_1_B2)

private fun isIpv4TestNet2Or3OrBenchmark(first: Int, second: Int, third: Int): Boolean {
    val isTestNet2 =
        first == IPV4_BENCHMARK_198 &&
            second == IPV4_DOC_TEST_NET_2_B1 &&
            third == IPV4_DOC_TEST_NET_2_B2
    val isTestNet3 =
        first == IPV4_DOC_TEST_NET_3_B0 && second == 0 && third == IPV4_DOC_TEST_NET_3_B2
    val isBenchmark =
        first == IPV4_BENCHMARK_198 && second in IPV4_BENCHMARK_MIN..IPV4_BENCHMARK_MAX
    return isTestNet2 || isTestNet3 || isBenchmark
}

private fun isPublicIpv6Literal(bracketedHost: String): Boolean = runCatching {
    isPublicInetAddress(InetAddress.getByName(bracketedHost))
}
    .getOrDefault(false)

private fun ipv6Segment(bytes: ByteArray, index: Int): Int {
    val byteOffset = index * 2
    val hi = bytes[byteOffset].toInt() and IPV4_OCTET_MAX
    val lo = bytes[byteOffset + 1].toInt() and IPV4_OCTET_MAX
    return hi shl IPV6_SEGMENT_SHIFT or lo
}

private fun isPublicIpv6Address(addr: InetAddress): Boolean {
    val bytes = addr.address
    return when {
        bytes.size != IPV6_BYTE_COUNT || isStandardNonGlobalIpv6(addr, bytes) -> false
        hasEmbeddedIpv4Prefix(bytes) -> isPublicIpv4Bytes(bytes, offset = IPV6_EMBEDDED_IPV4_OFFSET)
        else -> !isSpecialPurposeIpv6(bytes)
    }
}

private fun isStandardNonGlobalIpv6(addr: InetAddress, bytes: ByteArray): Boolean {
    val firstSeg = ipv6Segment(bytes, 0)
    return when {
        firstSeg and IPV6_ULA_SEG_MASK == IPV6_ULA_SEG_PREFIX -> true
        addr.isLoopbackAddress || addr.isAnyLocalAddress -> true
        addr.isLinkLocalAddress || addr.isSiteLocalAddress -> true
        addr.isMulticastAddress -> true
        else -> false
    }
}

private fun hasEmbeddedIpv4Prefix(bytes: ByteArray): Boolean {
    val leadZero =
        (0 until IPV6_EMBEDDED_LEAD_ZERO_SEGMENTS).all { idx -> ipv6Segment(bytes, idx) == 0 }
    if (!leadZero) return false
    val sixthSeg = ipv6Segment(bytes, IPV6_MAPPED_SEG_INDEX)
    return sixthSeg == 0 || sixthSeg == IPV6_MAPPED_SEG_VALUE
}

private fun isSpecialPurposeIpv6(bytes: ByteArray): Boolean {
    val s0 = ipv6Segment(bytes, 0)
    val s1 = ipv6Segment(bytes, 1)
    val s2 = ipv6Segment(bytes, 2)
    return when {
        isNat64Ipv6(bytes, s0, s1, s2) -> true
        isDiscardIpv6(bytes, s0) -> true
        is2001SpecialIpv6(s0, s1, s2) -> true
        s0 == IPV6_6TO4_SEG0 -> true
        else -> false
    }
}

private fun isNat64Ipv6(bytes: ByteArray, s0: Int, s1: Int, s2: Int): Boolean {
    if (s0 != IPV6_NAT64_SEG0 || s1 != IPV6_NAT64_SEG1) return false
    if (s2 == IPV6_NAT64_LOCAL_SEG2) return true
    return (2 until IPV6_NAT64_WELL_KNOWN_ZERO_END).all { idx -> ipv6Segment(bytes, idx) == 0 }
}

private fun isDiscardIpv6(bytes: ByteArray, s0: Int): Boolean =
    s0 == IPV6_DISCARD_SEG0 &&
        (1 until IPV6_DISCARD_ZERO_END).all { idx -> ipv6Segment(bytes, idx) == 0 }

private fun is2001SpecialIpv6(s0: Int, s1: Int, s2: Int): Boolean {
    if (s0 != IPV6_2001_SEG0) return false
    return when {
        s1 == 0 -> true
        s1 == IPV6_DOC_SEG1 -> true
        s1 == IPV6_BENCHMARK_SEG1 && s2 == 0 -> true
        s1 and IPV6_ORCHID_MASK == IPV6_ORCHID_V2_PREFIX -> true
        else -> false
    }
}

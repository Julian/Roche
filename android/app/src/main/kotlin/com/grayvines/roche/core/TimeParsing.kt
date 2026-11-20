package com.grayvines.roche.core

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val YEAR_PREFIX_LEN = 4
private const val MIN_YEAR = 1_000
private const val MAX_YEAR = 2_999
private val FIXED_UTC_MILLIS_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).withZone(ZoneOffset.UTC)

internal fun formatUtcMillisIso(instant: Instant): String =
    FIXED_UTC_MILLIS_FORMATTER.format(instant)

internal fun nowUtcIso(): String = formatUtcMillisIso(Instant.now())

internal fun parseIsoInstant(raw: String?): Instant? {
    val clean = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { Instant.parse(clean) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(clean).toInstant() }.getOrNull()
}

internal fun parseIsoEpochSecs(raw: String?): Long? = parseIsoInstant(raw)?.epochSecond

internal fun extractYearPrefix(rawDate: String?): Int? =
    rawDate
        ?.trim()
        ?.take(YEAR_PREFIX_LEN)
        ?.takeIf { it.length == YEAR_PREFIX_LEN && it.all(Char::isDigit) }
        ?.toIntOrNull()
        ?.takeIf { it in MIN_YEAR..MAX_YEAR }

internal fun newerIsoTimestamp(a: String?, b: String?): String? {
    val cleanA = a?.trim()?.takeIf { it.isNotEmpty() }
    val cleanB = b?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        cleanA == null -> cleanB
        cleanB == null -> cleanA
        else -> pickNewerIso(cleanA, cleanB)
    }
}

internal fun earlierIsoTimestamp(a: String?, b: String?): String? {
    val cleanA = a?.trim()?.takeIf { it.isNotEmpty() }
    val cleanB = b?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        cleanA == null -> cleanB
        cleanB == null -> cleanA
        else -> pickEarlierIso(cleanA, cleanB)
    }
}

private fun pickNewerIso(a: String, b: String): String {
    val instA = parseIsoInstant(a)
    val instB = parseIsoInstant(b)
    return if (instA != null && instB != null) {
        if (instA >= instB) a else b
    } else if (a >= b) {
        a
    } else {
        b
    }
}

private fun pickEarlierIso(a: String, b: String): String {
    val instA = parseIsoInstant(a)
    val instB = parseIsoInstant(b)
    return if (instA != null && instB != null) {
        if (instA <= instB) a else b
    } else if (a <= b) {
        a
    } else {
        b
    }
}

internal fun isLocalPollNewer(localIso: String?, remoteIso: String?): Boolean {
    val cleanLocal = localIso?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val cleanRemote = remoteIso?.trim()?.takeIf { it.isNotEmpty() } ?: return true
    val localInst = parseIsoInstant(cleanLocal)
    val remoteInst = parseIsoInstant(cleanRemote)
    return if (localInst != null && remoteInst != null) {
        localInst.isAfter(remoteInst)
    } else {
        cleanLocal > cleanRemote
    }
}

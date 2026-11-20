package com.grayvines.roche.core

import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

private const val YEAR_PREFIX_LEN = 4
private const val MIN_YEAR = 1_000
private const val MAX_YEAR = 2_999

internal fun nowUtcIso(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

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

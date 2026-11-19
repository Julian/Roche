package com.grayvines.roche.data

import com.grayvines.roche.data.local.RocheDao
import java.time.Instant
import java.util.Locale
import java.util.WeakHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val MAX_HLC_WALL_MS = 999_999_999_999_999L
internal const val MAX_HLC_COUNTER = 0xFFFF
internal const val MAX_CLOCK_DRIFT_MS = 3_600_000L
internal const val FLAG_LOCAL_HLC = "local_hlc"
internal const val DEFAULT_LOCAL_HLC_NODE = "android"

private const val WALL_WIDTH = 15
private const val COUNTER_WIDTH = 4
private const val MIN_HLC_LENGTH = WALL_WIDTH + 1 + COUNTER_WIDTH + 2
private const val SECOND_DASH_INDEX = WALL_WIDTH + 1 + COUNTER_WIDTH
private const val MAX_NODE_ID_CHARS = 64
private const val HEX_RADIX = 16

@Suppress("DataClassContainsFunctions")
data class HlcTimestamp(
    val wallMs: Long,
    val counter: Int,
    val nodeId: String,
) : Comparable<HlcTimestamp> {

    fun encode(): String = "%015d-%04x-%s".format(Locale.US, wallMs, counter, nodeId)

    override fun toString(): String = encode()

    override fun compareTo(other: HlcTimestamp): Int {
        val wallCmp = wallMs.compareTo(other.wallMs)
        if (wallCmp != 0) return wallCmp
        val counterCmp = counter.compareTo(other.counter)
        return if (counterCmp != 0) counterCmp else nodeId.compareTo(other.nodeId)
    }

    companion object {
        fun create(wallMs: Long, counter: Int, rawNodeId: String): HlcTimestamp =
            HlcTimestamp(
                wallMs = wallMs.coerceIn(0L, MAX_HLC_WALL_MS),
                counter = counter.coerceIn(0, MAX_HLC_COUNTER),
                nodeId = sanitizeHlcNodeId(rawNodeId),
            )

        fun fromInstant(instant: Instant, nodeId: String): HlcTimestamp =
            create(instant.toEpochMilli(), 0, nodeId)

        fun fromIsoOrEpoch(rawIso: String?, nodeId: String): HlcTimestamp {
            val trimmed = rawIso?.trim()?.takeIf { it.isNotEmpty() }
            val ms =
                if (trimmed == null) {
                    0L
                } else {
                    runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrDefault(0L)
                }
            return create(ms, 0, nodeId)
        }

        fun parse(raw: String?): HlcTimestamp? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.length < MIN_HLC_LENGTH) return null
            val hasSeparators = trimmed[WALL_WIDTH] == '-' && trimmed[SECOND_DASH_INDEX] == '-'
            if (!hasSeparators) return null

            val wallStr = trimmed.substring(0, WALL_WIDTH)
            val wallMs = if (wallStr.all { it in '0'..'9' }) wallStr.toLongOrNull() else null
            val counterStr = trimmed.substring(WALL_WIDTH + 1, SECOND_DASH_INDEX)
            val counter =
                if (counterStr.all { it in '0'..'9' || it in 'a'..'f' }) {
                    counterStr.toIntOrNull(HEX_RADIX)
                } else {
                    null
                }
            val nodeId = trimmed.substring(SECOND_DASH_INDEX + 1).trim()
            return if (wallMs != null && counter != null && nodeId.isNotEmpty()) {
                HlcTimestamp(wallMs = wallMs, counter = counter, nodeId = nodeId)
            } else {
                null
            }
        }
    }
}

internal fun sanitizeHlcNodeId(raw: String): String {
    val filtered = buildString {
        for (ch in raw.trim()) {
            val allowed =
                ch in 'a'..'z' ||
                    ch in 'A'..'Z' ||
                    ch in '0'..'9' ||
                    ch == '-' ||
                    ch == '_' ||
                    ch == '.' ||
                    ch == ':'
            if (allowed) {
                append(ch)
                if (length >= MAX_NODE_ID_CHARS) break
            }
        }
    }
    return filtered.ifEmpty { "peer" }
}

internal class HlcClock(
    rawNodeId: String,
    initial: String? = null,
) {
    private val nodeId: String = sanitizeHlcNodeId(rawNodeId)
    private var last: HlcTimestamp? = HlcTimestamp.parse(initial)

    fun current(): HlcTimestamp? = last

    fun tick(nowMs: Long = System.currentTimeMillis()): HlcTimestamp {
        val phys = nowMs.coerceIn(0L, MAX_HLC_WALL_MS)
        val prev = last
        val next =
            when {
                prev == null || phys > prev.wallMs -> HlcTimestamp.create(phys, 0, nodeId)
                prev.counter < MAX_HLC_COUNTER ->
                    HlcTimestamp.create(prev.wallMs, prev.counter + 1, nodeId)
                prev.wallMs < MAX_HLC_WALL_MS -> HlcTimestamp.create(prev.wallMs + 1L, 0, nodeId)
                else -> HlcTimestamp.create(MAX_HLC_WALL_MS, MAX_HLC_COUNTER, nodeId)
            }
        last = next
        return next
    }

    fun witness(
        remote: HlcTimestamp,
        nowMs: Long = System.currentTimeMillis(),
    ): HlcTimestamp {
        val phys = nowMs.coerceIn(0L, MAX_HLC_WALL_MS)
        val maxAllowedWall =
            if (phys > MAX_HLC_WALL_MS - MAX_CLOCK_DRIFT_MS) {
                MAX_HLC_WALL_MS
            } else {
                phys + MAX_CLOCK_DRIFT_MS
            }
        val rawRemMs = remote.wallMs.coerceIn(0L, MAX_HLC_WALL_MS)
        val clamped = rawRemMs > maxAllowedWall
        val remMs = if (clamped) maxAllowedWall else rawRemMs
        val remCounter = if (clamped) 0 else remote.counter.coerceIn(0, MAX_HLC_COUNTER)
        val prev = last
        val next =
            if (prev == null) {
                witnessWithoutPrevious(phys, remMs, remCounter)
            } else {
                witnessWithPrevious(phys, prev, remMs, remCounter)
            }
        last = next
        return next
    }

    fun witnessStr(
        remoteRaw: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): HlcTimestamp {
        val parsed = HlcTimestamp.parse(remoteRaw)
        return if (parsed != null) witness(parsed, nowMs) else tick(nowMs)
    }

    private fun witnessWithoutPrevious(
        phys: Long,
        remMs: Long,
        remCounter: Int,
    ): HlcTimestamp =
        when {
            phys > remMs -> HlcTimestamp.create(phys, 0, nodeId)
            remCounter < MAX_HLC_COUNTER -> HlcTimestamp.create(remMs, remCounter + 1, nodeId)
            remMs < MAX_HLC_WALL_MS -> HlcTimestamp.create(remMs + 1L, 0, nodeId)
            else -> HlcTimestamp.create(MAX_HLC_WALL_MS, MAX_HLC_COUNTER, nodeId)
        }

    private fun witnessWithPrevious(
        phys: Long,
        prev: HlcTimestamp,
        remMs: Long,
        remCounter: Int,
    ): HlcTimestamp {
        val maxWall = maxOf(phys, prev.wallMs, remMs)
        val rawCounter =
            when {
                maxWall == prev.wallMs && maxWall == remMs -> maxOf(prev.counter, remCounter) + 1
                maxWall == prev.wallMs -> prev.counter + 1
                maxWall == remMs -> remCounter + 1
                else -> 0
            }
        return when {
            rawCounter <= MAX_HLC_COUNTER -> HlcTimestamp.create(maxWall, rawCounter, nodeId)
            maxWall < MAX_HLC_WALL_MS -> HlcTimestamp.create(maxWall + 1L, 0, nodeId)
            else -> HlcTimestamp.create(MAX_HLC_WALL_MS, MAX_HLC_COUNTER, nodeId)
        }
    }
}

internal fun resolveOrSynthesizeHlc(
    rawHlc: String?,
    fallbackIso: String?,
    nowIso: String,
    peerId: String,
    nowMs: Long = System.currentTimeMillis(),
): String {
    val phys = nowMs.coerceIn(0L, MAX_HLC_WALL_MS)
    val maxAllowedWall =
        if (phys > MAX_HLC_WALL_MS - MAX_CLOCK_DRIFT_MS) {
            MAX_HLC_WALL_MS
        } else {
            phys + MAX_CLOCK_DRIFT_MS
        }
    val ts =
        HlcTimestamp.parse(rawHlc) ?: HlcTimestamp.fromIsoOrEpoch(fallbackIso ?: nowIso, peerId)
    return if (ts.wallMs > maxAllowedWall) {
        HlcTimestamp.create(maxAllowedWall, 0, ts.nodeId).encode()
    } else {
        ts.encode()
    }
}

private val hlcClockMutex = Mutex()
private val lastHlcByDao = WeakHashMap<RocheDao, String>()

private fun maxHlcRaw(first: String?, second: String?): String? {
    val parsedFirst = HlcTimestamp.parse(first)
    val parsedSecond = HlcTimestamp.parse(second)
    return when {
        parsedFirst == null -> parsedSecond?.encode()
        parsedSecond == null -> parsedFirst.encode()
        parsedFirst >= parsedSecond -> parsedFirst.encode()
        else -> parsedSecond.encode()
    }
}

internal suspend fun nextLocalHlc(
    dao: RocheDao,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
    nowMs: Long = System.currentTimeMillis(),
): String {
    val dbPrev = dao.getLocalSyncFlag(FLAG_LOCAL_HLC)
    val next = hlcClockMutex.withLock {
        val seed = maxHlcRaw(dbPrev, lastHlcByDao[dao])
        val ticked = HlcClock(nodeId, seed).tick(nowMs).encode()
        lastHlcByDao[dao] = ticked
        ticked
    }
    dao.upsertLocalSyncFlagIfGreater(FLAG_LOCAL_HLC, next)
    return next
}

internal suspend fun witnessRemoteHlcInDao(
    dao: RocheDao,
    nodeId: String,
    remoteHlcValues: Iterable<String?>,
    nowMs: Long = System.currentTimeMillis(),
): String {
    val dbPrev = dao.getLocalSyncFlag(FLAG_LOCAL_HLC)
    val next = hlcClockMutex.withLock {
        val seed = maxHlcRaw(dbPrev, lastHlcByDao[dao])
        val clock = HlcClock(nodeId, seed)
        var witnessedAny = false
        for (raw in remoteHlcValues) {
            val parsed = HlcTimestamp.parse(raw) ?: continue
            clock.witness(parsed, nowMs)
            witnessedAny = true
        }
        val advanced =
            (if (witnessedAny) clock.current() else null)?.encode() ?: clock.tick(nowMs).encode()
        lastHlcByDao[dao] = advanced
        advanced
    }
    dao.upsertLocalSyncFlagIfGreater(FLAG_LOCAL_HLC, next)
    return next
}

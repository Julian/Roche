package com.grayvines.roche.data.sync

import com.grayvines.roche.core.FLAG_LOCAL_HLC
import com.grayvines.roche.core.HlcClock
import com.grayvines.roche.core.HlcTimestamp
import com.grayvines.roche.core.MAX_CLOCK_DRIFT_MS
import com.grayvines.roche.core.MAX_HLC_WALL_MS
import com.grayvines.roche.data.local.RocheDao

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

internal suspend fun witnessRemoteHlcInDao(
    dao: RocheDao,
    nodeId: String,
    remoteHlcValues: Iterable<String?>,
    nowMs: Long = System.currentTimeMillis(),
): String {
    var next = ""
    dao.executeInTransaction {
        val dbPrev = dao.getLocalSyncFlag(FLAG_LOCAL_HLC)
        val clock = HlcClock(nodeId, dbPrev)
        var witnessedAny = false
        for (raw in remoteHlcValues) {
            val parsed = HlcTimestamp.parse(raw) ?: continue
            clock.witness(parsed, nowMs)
            witnessedAny = true
        }
        next = (if (witnessedAny) clock.current() else null)?.encode() ?: clock.tick(nowMs).encode()
        dao.upsertLocalSyncFlagIfGreater(FLAG_LOCAL_HLC, next)
    }
    return next
}

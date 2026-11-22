@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data.sync

import com.grayvines.roche.core.DEFAULT_LOCAL_HLC_NODE
import com.grayvines.roche.core.PROTECTED_SYSTEM_COLLECTION_IDS
import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.STARRED_SLUG
import com.grayvines.roche.core.TOMBSTONE_ENTITY_COLLECTION_ENTRY
import com.grayvines.roche.core.TOMBSTONE_ENTITY_COLLECTION_GENERATOR
import com.grayvines.roche.core.TOMBSTONE_ENTITY_COMPARISON
import com.grayvines.roche.core.TOMBSTONE_ENTITY_CONSUMPTION_LOG
import com.grayvines.roche.core.TOMBSTONE_ENTITY_ENTRY
import com.grayvines.roche.core.TOMBSTONE_ENTITY_FEED
import com.grayvines.roche.core.TOMBSTONE_ENTITY_ITEM
import com.grayvines.roche.core.TOMBSTONE_ENTITY_TAG
import com.grayvines.roche.core.UP_NEXT_COLLECTION_ID
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.core.WANT_TO_READ_SLUG
import com.grayvines.roche.core.canonicalizeSyncUrl
import com.grayvines.roche.core.collectionEntryNaturalKey
import com.grayvines.roche.core.deterministicFeedId
import com.grayvines.roche.core.deterministicItemId
import com.grayvines.roche.core.deterministicTagId
import com.grayvines.roche.core.encodeCreatorsJson
import com.grayvines.roche.core.extractArxivIdentifier
import com.grayvines.roche.core.feedNaturalKey
import com.grayvines.roche.core.formatDurationSecondsLabel
import com.grayvines.roche.core.isLocalPollNewer
import com.grayvines.roche.core.itemNaturalKey
import com.grayvines.roche.core.newerIsoTimestamp
import com.grayvines.roche.core.normalizeConsecutiveFailures
import com.grayvines.roche.core.normalizeIdentifierScheme
import com.grayvines.roche.core.normalizeTagName
import com.grayvines.roche.core.nowUtcIso
import com.grayvines.roche.core.parseIsoInstant
import com.grayvines.roche.core.resolveArticleDuration
import com.grayvines.roche.core.sanitizeHttpUrl
import com.grayvines.roche.core.tagNaturalKey
import com.grayvines.roche.data.catalog.CatalogStore
import com.grayvines.roche.data.catalog.CollectionMode
import com.grayvines.roche.data.catalog.CollectionRole
import com.grayvines.roche.data.catalog.EntryIdentifierDto
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.SyncCollectionEntryDto
import com.grayvines.roche.data.catalog.SyncCollectionSpecDto
import com.grayvines.roche.data.catalog.SyncEntryContentDto
import com.grayvines.roche.data.catalog.SyncEntryRecordDto
import com.grayvines.roche.data.catalog.SyncEntryStateDto
import com.grayvines.roche.data.catalog.SyncFeedSourceDto
import com.grayvines.roche.data.catalog.chooseDeterministicEntryId
import com.grayvines.roche.data.catalog.followMergedIdInDao
import com.grayvines.roche.data.catalog.normalizeIdentifierValue
import com.grayvines.roche.data.feeds.pickRicherString
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.EntryContentEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryIdentifierEntity
import com.grayvines.roche.data.local.EntryStateEntity
import com.grayvines.roche.data.local.FeedSourceEntity
import com.grayvines.roche.data.local.LIST_PREVIEW_HTML_CHARS
import com.grayvines.roche.data.local.LIST_PREVIEW_SUMMARY_CHARS
import com.grayvines.roche.data.local.LIST_PREVIEW_TEXT_CHARS
import com.grayvines.roche.data.local.MAX_SQLITE_HTML_CHARS
import com.grayvines.roche.data.local.MAX_SQLITE_SUMMARY_CHARS
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.SQLITE_DELETE_CHUNK_SIZE
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.data.local.deleteEntryCascade
import com.grayvines.roche.data.local.deleteFeedPreservingHistory
import com.grayvines.roche.data.local.deleteItemPreservingHistory
import com.grayvines.roche.data.local.ensureStarredSpecExists
import com.grayvines.roche.data.local.entryHasCatalogHistory
import com.grayvines.roche.data.local.entryKindForFeedTypeWire
import com.grayvines.roche.data.remote.PeerSyncRequestDto
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.remote.SyncTombstoneDto
import com.grayvines.roche.model.ContentType

internal suspend fun reconcilePeerSyncResponse(
    dao: RocheDao,
    response: PeerSyncResponseDto,
    localPeerId: String = DEFAULT_LOCAL_HLC_NODE,
    sentBatch: TrackedOutgoingSyncBatch = TrackedOutgoingSyncBatch(PeerSyncRequestDto(peerId = "")),
    catalogStore: CatalogStore? = null,
) {
    catalogStore?.ensureSystemSeeded()
    val serverNode = response.serverPeerId.ifBlank { SERVER_PEER_ID }
    val nowIso = response.syncTimestamp.ifBlank { nowUtcIso() }
    val syncNowMs = System.currentTimeMillis()
    val allRemoteHlcs = collectResponseHlcs(response)

    val runTx: suspend () -> Unit = {
        dao.executeInTransaction {
            dao.ensureStarredSpecExists()
            witnessRemoteHlcInDao(
                dao = dao,
                nodeId = localPeerId.ifBlank { DEFAULT_LOCAL_HLC_NODE },
                remoteHlcValues = allRemoteHlcs,
                nowMs = syncNowMs,
            )
            acknowledgeSentDirtyRows(dao, sentBatch)
            applyIncomingTombstonesInTx(dao, response.tombstones, serverNode, nowIso, syncNowMs)
            val mergedEntries =
                mergeIncomingEntriesInTx(dao, response, serverNode, nowIso, syncNowMs)
            mergeIncomingGraphEdgesInTx(
                dao = dao,
                catalogStore = catalogStore,
                response = response,
                redirects = mergedEntries.redirects,
                suppressedEntryIds = mergedEntries.suppressedEntryIds,
                serverNode = serverNode,
                localPeerId = localPeerId.ifBlank { DEFAULT_LOCAL_HLC_NODE },
                nowIso = nowIso,
                nowMs = syncNowMs,
            )
        }
    }
    if (catalogStore != null) {
        catalogStore.withMutationLock { runTx() }
    } else {
        runTx()
    }
}

private fun collectResponseHlcs(response: PeerSyncResponseDto): List<String?> = buildList {
    add(response.serverHlc)
    response.entries.forEach { rec ->
        add(rec.hlc)
        add(rec.state?.hlc)
        add(rec.feedSource?.hlc)
    }
    response.collectionEntries.forEach { add(it.hlc) }
    response.collectionGenerators.forEach { add(it.hlc) }
    response.consumptionLog.forEach { add(it.hlc) }
    response.comparisons.forEach { add(it.hlc) }
    response.tombstones.forEach { add(it.hlc) }
}

private suspend fun acknowledgeSentDirtyRows(
    dao: RocheDao,
    sentBatch: TrackedOutgoingSyncBatch,
) {
    for ((id, hlc) in sentBatch.sentEntryHlcById) {
        dao.clearEntryDirtyIfSameHlc(id, hlc)
    }
    for ((id, hlc) in sentBatch.sentStateHlcById) {
        dao.clearEntryStateDirtyIfSameHlc(id, hlc)
    }
    for ((id, hlc) in sentBatch.sentFeedSourceHlcById) {
        dao.clearFeedSourceDirtyIfSameHlc(id, hlc)
    }
    for ((key, hlc) in sentBatch.sentMembershipHlcByKey) {
        dao.clearMemberDirtyIfSameHlc(key.first, key.second, hlc)
    }
    for ((colId, hlc) in sentBatch.sentGeneratorHlcById) {
        dao.clearGeneratorDirtyIfSameHlc(colId, hlc)
    }
    for ((id, hlc) in sentBatch.sentLogHlcById) {
        dao.clearLogDirtyIfSameHlc(id, hlc)
    }
    for ((id, hlc) in sentBatch.sentComparisonHlcById) {
        dao.clearComparisonDirtyIfSameHlc(id, hlc)
    }
    for ((key, hlc) in sentBatch.sentTombstoneHlcByKey) {
        dao.clearTombstoneDirtyIfSameHlc(key.first, key.second, hlc)
    }
    if (sentBatch.countOverflowEntryIds.isNotEmpty()) {
        sentBatch.countOverflowEntryIds.chunked(SQLITE_DELETE_CHUNK_SIZE).forEach {
            dao.markEntriesDirty(it)
            dao.markMembershipsDirtyForEntries(it)
        }
    }
}

private const val INBOX_STATE_UNREAD = "unread"
private const val INBOX_STATE_DISMISSED = "dismissed"
private const val INBOX_STATE_NONE = "none"

private class TombstoneBatchCache(private val dao: RocheDao) {
    private var feedsById: MutableMap<String, FeedSourceEntity>? = null
    private var feedIdByKey: MutableMap<String, String>? = null
    private var itemIndexLoaded = false
    private val feedUrlsByColId = HashMap<String, String>()
    private val membershipsByEntry = HashMap<String, MutableList<String>>()
    private val statesById = HashMap<String, EntryStateEntity>()
    private val entriesById = HashMap<String, EntryEntity>()
    private val entriesByCanonUrl = HashMap<String, MutableList<EntryEntity>>()
    private var tagEntries: MutableList<Pair<CollectionSpecEntity, EntryEntity>>? = null

    suspend fun findFeed(canonKey: String, detId: String): FeedSourceEntity? {
        val byId =
            feedsById
                ?: HashMap<String, FeedSourceEntity>().also { map ->
                    val keyMap = HashMap<String, String>()
                    for (src in dao.getAllFeedSources()) {
                        map[src.collectionId] = src
                        keyMap[feedNaturalKey(src.feedUrl)] = src.collectionId
                    }
                    feedsById = map
                    feedIdByKey = keyMap
                }
        val keyMatchedId = feedIdByKey?.get(canonKey)
        return keyMatchedId?.let { byId[it] } ?: byId[detId]
    }

    fun onFeedDeleted(feedId: String, canonKey: String) {
        feedsById?.remove(feedId)
        feedIdByKey?.remove(canonKey)
        itemIndexLoaded = false
    }

    suspend fun findItemCandidates(
        detId: String,
        canonFeed: String?,
        canonItem: String,
    ): List<EntryEntity> {
        ensureItemIndexLoaded()
        val seenIds = LinkedHashSet<String>()
        val candidates = ArrayList<EntryEntity>()
        entriesById[detId]?.let { entry ->
            if (seenIds.add(entry.id) && matchesItemTombstone(entry, detId, canonFeed, canonItem)) {
                candidates.add(entry)
            }
        }
        for (entry in entriesByCanonUrl[canonItem].orEmpty()) {
            if (seenIds.add(entry.id) && matchesItemTombstone(entry, detId, canonFeed, canonItem)) {
                candidates.add(entry)
            }
        }
        return candidates
    }

    fun stateHlcFor(entryId: String): String? = statesById[entryId]?.hlc

    suspend fun refreshItemAfterDelete(entryId: String) {
        if (!itemIndexLoaded) return
        val prevEntry = entriesById.remove(entryId)
        prevEntry
            ?.url
            ?.takeIf { it.isNotBlank() }
            ?.let { rawUrl ->
                entriesByCanonUrl[canonicalizeSyncUrl(rawUrl)]?.removeAll { it.id == entryId }
            }
        val updatedEntry = dao.getEntryById(entryId)
        if (updatedEntry != null) {
            entriesById[entryId] = updatedEntry
            updatedEntry.url
                ?.takeIf { it.isNotBlank() }
                ?.let { rawUrl ->
                    entriesByCanonUrl
                        .getOrPut(canonicalizeSyncUrl(rawUrl)) { ArrayList() }
                        .add(updatedEntry)
                }
        }
        val updatedState = dao.getEntryStateById(entryId)
        if (updatedState != null) {
            statesById[entryId] = updatedState
        } else {
            statesById.remove(entryId)
        }
        val updatedCols =
            dao.getCollectionEntriesForEntry(entryId).mapTo(ArrayList()) { it.collectionId }
        if (updatedCols.isEmpty()) {
            membershipsByEntry.remove(entryId)
        } else {
            membershipsByEntry[entryId] = updatedCols
        }
    }

    suspend fun findTagEntries(wantSlug: String, canonKey: String): List<EntryEntity> {
        val loaded = tagEntries ?: loadTagEntriesFromDao().also { tagEntries = it }
        return loaded.mapNotNull { (spec, entry) ->
            entry.takeIf {
                spec.slug?.lowercase() == wantSlug || entry.title.trim().lowercase() == canonKey
            }
        }
    }

    fun onTagDeleted(entryId: String) {
        tagEntries?.removeAll { it.second.id == entryId }
        entriesById.remove(entryId)
    }

    private suspend fun loadTagEntriesFromDao():
        MutableList<Pair<CollectionSpecEntity, EntryEntity>> {
        val specs =
            dao.getAllCollectionSpecs().filter {
                CollectionRole.fromWireValue(it.role) == CollectionRole.TAG
            }
        if (specs.isEmpty()) return ArrayList()
        val entriesMap = HashMap<String, EntryEntity>(specs.size)
        for (chunk in specs.map { it.collectionId }.chunked(SQLITE_DELETE_CHUNK_SIZE)) {
            for (entry in dao.getEntriesByIds(chunk)) {
                entriesMap[entry.id] = entry
            }
        }
        return specs.mapNotNullTo(ArrayList(specs.size)) { spec ->
            entriesMap[spec.collectionId]?.let { spec to it }
        }
    }

    private suspend fun ensureItemIndexLoaded() {
        if (itemIndexLoaded) return
        feedUrlsByColId.clear()
        membershipsByEntry.clear()
        statesById.clear()
        entriesById.clear()
        entriesByCanonUrl.clear()
        for (src in dao.getAllFeedSources()) {
            feedUrlsByColId[src.collectionId] = canonicalizeSyncUrl(src.feedUrl)
        }
        for (mem in dao.getAllCollectionEntries()) {
            membershipsByEntry.getOrPut(mem.entryId) { ArrayList() }.add(mem.collectionId)
        }
        for (state in dao.getAllEntryStates()) {
            statesById[state.entryId] = state
        }
        for (entry in dao.getAllEntries()) {
            entriesById[entry.id] = entry
            val canonUrl = canonicalizeSyncUrl(entry.url.orEmpty())
            if (canonUrl.isNotEmpty()) {
                entriesByCanonUrl.getOrPut(canonUrl) { ArrayList() }.add(entry)
            }
        }
        itemIndexLoaded = true
    }

    private fun matchesItemTombstone(
        entry: EntryEntity,
        detId: String,
        canonFeed: String?,
        canonItem: String,
    ): Boolean {
        val state = statesById[entry.id]
        val validInboxState =
            state != null &&
                (state.inboxState == INBOX_STATE_UNREAD ||
                    state.inboxState == INBOX_STATE_DISMISSED ||
                    canonFeed == null && state.inboxState == INBOX_STATE_NONE)
        if (!validInboxState) return false
        val entryFeeds = membershipsByEntry[entry.id].orEmpty().mapNotNull { feedUrlsByColId[it] }
        if (entry.id == detId) {
            return if (canonFeed == null) entryFeeds.isEmpty() else true
        }
        if (canonicalizeSyncUrl(entry.url.orEmpty()) != canonItem) return false
        return if (canonFeed == null) entryFeeds.isEmpty() else canonFeed in entryFeeds
    }
}

private suspend fun applyIncomingTombstonesInTx(
    dao: RocheDao,
    tombstones: List<SyncTombstoneDto>,
    serverNode: String,
    nowIso: String,
    nowMs: Long,
) {
    if (tombstones.isEmpty()) return
    val cache = TombstoneBatchCache(dao)
    for (tomb in tombstones) {
        val rawKey = tomb.naturalKey.trim()
        if (rawKey.isEmpty()) continue
        val tombHlc = resolveOrSynthesizeHlc(tomb.hlc, tomb.deletedAt, nowIso, serverNode, nowMs)
        val deletedAt = tomb.deletedAt.ifBlank { nowIso }
        when (tomb.entityType.trim().lowercase()) {
            TOMBSTONE_ENTITY_FEED -> applyFeedTombstone(dao, cache, rawKey, deletedAt, tombHlc)
            TOMBSTONE_ENTITY_ITEM -> applyItemTombstone(dao, cache, rawKey, deletedAt, tombHlc)
            TOMBSTONE_ENTITY_TAG -> applyTagTombstone(dao, cache, rawKey, deletedAt, tombHlc)
            TOMBSTONE_ENTITY_COLLECTION_ENTRY ->
                applyCollectionEntryTombstone(dao, rawKey, deletedAt, tombHlc)
            TOMBSTONE_ENTITY_COLLECTION_GENERATOR ->
                applyGeneratorTombstone(dao, rawKey, deletedAt, tombHlc)
            TOMBSTONE_ENTITY_CONSUMPTION_LOG ->
                applyConsumptionLogTombstone(dao, rawKey, deletedAt, tombHlc)
            TOMBSTONE_ENTITY_COMPARISON -> applyComparisonTombstone(dao, rawKey, deletedAt, tombHlc)
            TOMBSTONE_ENTITY_ENTRY -> applyEntryTombstone(dao, rawKey, deletedAt, tombHlc)
        }
    }
}

private suspend fun applyFeedTombstone(
    dao: RocheDao,
    cache: TombstoneBatchCache,
    rawKey: String,
    deletedAt: String,
    tombHlc: String,
) {
    val canonKey = feedNaturalKey(rawKey)
    val detId = deterministicFeedId(canonKey)
    val match = cache.findFeed(canonKey, detId)
    if (match != null) {
        val entryHlc = dao.getEntryById(match.collectionId)?.hlc
        val liveHlc = maxHlc(match.hlc, entryHlc)
        if (compareHlc(liveHlc, tombHlc) > 0) return
        dao.deleteFeedPreservingHistory(
            feedId = match.collectionId,
            recordTombstones = false,
            hlcOverride = tombHlc,
        )
        cache.onFeedDeleted(match.collectionId, canonKey)
    }
    upsertCleanTombstone(dao, TOMBSTONE_ENTITY_FEED, canonKey, deletedAt, tombHlc)
}

private suspend fun applyItemTombstone(
    dao: RocheDao,
    cache: TombstoneBatchCache,
    rawKey: String,
    deletedAt: String,
    tombHlc: String,
) {
    val parts = rawKey.split('\u0000')
    if (parts.size != 2) {
        upsertCleanTombstone(dao, TOMBSTONE_ENTITY_ITEM, rawKey, deletedAt, tombHlc)
        return
    }
    val canonFeed = if (parts[0] == "manual") null else canonicalizeSyncUrl(parts[0])
    val canonItem = canonicalizeSyncUrl(parts[1])
    val canonKey = itemNaturalKey(canonFeed, canonItem)
    val detId = deterministicItemId(canonFeed, canonItem)

    val candidates = cache.findItemCandidates(detId, canonFeed, canonItem)
    if (candidates.any { compareHlc(cache.stateHlcFor(it.id), tombHlc) > 0 }) return
    for (cand in candidates) {
        dao.deleteItemPreservingHistory(
            itemId = cand.id,
            recordTombstones = false,
            hlcOverride = tombHlc,
        )
        cache.refreshItemAfterDelete(cand.id)
    }
    upsertCleanTombstone(dao, TOMBSTONE_ENTITY_ITEM, canonKey, deletedAt, tombHlc)
}

private suspend fun applyTagTombstone(
    dao: RocheDao,
    cache: TombstoneBatchCache,
    rawKey: String,
    deletedAt: String,
    tombHlc: String,
) {
    val canonKey = tagNaturalKey(rawKey)
    val wantSlug = "tag:$canonKey"
    val matched = cache.findTagEntries(wantSlug, canonKey)
    if (matched.any { compareHlc(it.hlc, tombHlc) > 0 }) return
    for (entry in matched) {
        dao.deleteEntryCascade(entry.id)
        cache.onTagDeleted(entry.id)
    }
    upsertCleanTombstone(dao, TOMBSTONE_ENTITY_TAG, canonKey, deletedAt, tombHlc)
}

private suspend fun applyCollectionEntryTombstone(
    dao: RocheDao,
    rawKey: String,
    deletedAt: String,
    tombHlc: String,
) {
    val parts = rawKey.split('\u0000')
    if (parts.size != 2) return
    val colId = followMergedIdInDao(dao, parts[0]) ?: parts[0].trim()
    val entryId = followMergedIdInDao(dao, parts[1]) ?: parts[1].trim()
    val canonKey = collectionEntryNaturalKey(colId, entryId)
    val existing = dao.getCollectionEntry(colId, entryId)
    if (existing != null && compareHlc(existing.hlc, tombHlc) > 0) return
    dao.deleteCollectionEntry(colId, entryId)
    upsertCleanTombstone(dao, TOMBSTONE_ENTITY_COLLECTION_ENTRY, canonKey, deletedAt, tombHlc)
}

private suspend fun applyGeneratorTombstone(
    dao: RocheDao,
    rawKey: String,
    deletedAt: String,
    tombHlc: String,
) {
    val colId = followMergedIdInDao(dao, rawKey) ?: rawKey.trim()
    if (colId.isEmpty()) return
    val existing = dao.getCollectionGenerator(colId)
    if (existing != null && compareHlc(existing.hlc, tombHlc) > 0) return
    dao.deleteCollectionGenerator(colId)
    upsertCleanTombstone(dao, TOMBSTONE_ENTITY_COLLECTION_GENERATOR, colId, deletedAt, tombHlc)
}

private suspend fun applyConsumptionLogTombstone(
    dao: RocheDao,
    rawKey: String,
    deletedAt: String,
    tombHlc: String,
) {
    val logId = rawKey.trim()
    if (logId.isEmpty()) return
    val existing = dao.getConsumptionLogById(logId)
    if (existing != null && compareHlc(existing.hlc, tombHlc) > 0) return
    dao.deleteConsumptionLogById(logId)
    upsertCleanTombstone(dao, TOMBSTONE_ENTITY_CONSUMPTION_LOG, logId, deletedAt, tombHlc)
}

private suspend fun applyComparisonTombstone(
    dao: RocheDao,
    rawKey: String,
    deletedAt: String,
    tombHlc: String,
) {
    val compId = rawKey.trim()
    if (compId.isEmpty()) return
    val existing = dao.getComparisonById(compId)
    if (existing != null && compareHlc(existing.hlc, tombHlc) > 0) return
    dao.deleteComparisonById(compId)
    upsertCleanTombstone(dao, TOMBSTONE_ENTITY_COMPARISON, compId, deletedAt, tombHlc)
}

private suspend fun isProtectedTombstoneTarget(dao: RocheDao, entryId: String): Boolean {
    if (entryId in PROTECTED_SYSTEM_COLLECTION_IDS) return true
    val spec = dao.getCollectionSpecById(entryId)
    return spec != null && (spec.mode == "dynamic" || spec.role in PROTECTED_TOMBSTONE_ROLES)
}

private suspend fun applyEntryTombstone(
    dao: RocheDao,
    rawKey: String,
    deletedAt: String,
    tombHlc: String,
) {
    if (isProtectedTombstoneTarget(dao, rawKey)) return
    val existing = dao.getEntryById(rawKey)
    val shouldRecordTombstone =
        if (existing == null) {
            true
        } else if (compareHlc(existing.hlc, tombHlc) > 0) {
            false
        } else if (dao.entryHasCatalogHistory(rawKey)) {
            dao.getEntryStateById(rawKey)?.let { st ->
                dao.upsertEntryState(
                    st.copy(inboxState = "none", readAt = null, hlc = tombHlc, isLocalDirty = false)
                )
            }
            dao.deleteEntryContentById(rawKey)
            false
        } else {
            dao.deleteEntryCascade(rawKey)
            true
        }
    if (shouldRecordTombstone) {
        upsertCleanTombstone(dao, TOMBSTONE_ENTITY_ENTRY, rawKey, deletedAt, tombHlc)
    }
}

private val PROTECTED_TOMBSTONE_ROLES = setOf("system", "feed", "tag")

internal suspend fun upsertCleanTombstone(
    dao: RocheDao,
    entityType: String,
    naturalKey: String,
    deletedAt: String,
    hlc: String,
) {
    val current = dao.getTombstone(entityType, naturalKey)
    if (current != null && compareHlc(current.hlc, hlc) > 0) return
    dao.upsertTombstone(
        TombstoneEntity(
            entityType = entityType,
            naturalKey = naturalKey,
            deletedAt = deletedAt,
            hlc = hlc,
            isLocalDirty = false,
        )
    )
}

private enum class ItemTombstoneAction {
    ALLOW,
    SUPPRESS_INBOX_ONLY,
    SUPPRESS_ENTRY,
}

private data class MergedIncomingEntriesResult(
    val redirects: Map<String, String>,
    val suppressedEntryIds: Set<String>,
)

@Suppress("LongParameterList")
private data class IncomingEntriesMergeCtx(
    val tombstonesByKey: MutableMap<Pair<String, String>, TombstoneEntity>,
    val itemDetIdToNatKey: Map<String, String>,
    val feedSourceIdByCanonUrl: MutableMap<String, String>,
    val parentFeedsByEntryId: Map<String, Set<String>>,
    val incomingWtrEntryIds: Set<String>,
    val incomingCatalogReferencedIds: Set<String>,
    val idRemap: MutableMap<String, String>,
    val suppressedEntryIds: MutableSet<String>,
    val serverNode: String,
    val nowIso: String,
    val nowMs: Long,
)

private suspend fun mergeIncomingEntriesInTx(
    dao: RocheDao,
    response: PeerSyncResponseDto,
    serverNode: String,
    nowIso: String,
    nowMs: Long,
): MergedIncomingEntriesResult {
    val ctx = buildIncomingEntriesMergeCtx(dao, response, serverNode, nowIso, nowMs)
    val explicitMerges = ArrayList<Pair<String, String>>()

    for (rec in response.entries) {
        val mergedPair = mergeSingleIncomingEntry(dao, rec, ctx)
        if (mergedPair != null) {
            explicitMerges.add(mergedPair)
        }
    }
    val mergeRedirects = buildMergeRedirectMap(dao.getMergedEntries())
    val combinedRedirects = ctx.idRemap + mergeRedirects
    val arrivedIds = HashSet<String>(response.entries.size + ctx.idRemap.size)
    for (rec in response.entries) {
        val rawId = rec.id.trim()
        if (rawId.isNotEmpty()) {
            arrivedIds.add(rawId)
        }
    }
    arrivedIds.addAll(ctx.idRemap.values)
    foldIncomingAndDeferredMerges(
        dao = dao,
        explicitMerges = explicitMerges,
        mergeRedirects = mergeRedirects,
        combinedRedirects = combinedRedirects,
        arrivedIds = arrivedIds,
        nowIso = nowIso,
    )
    return MergedIncomingEntriesResult(
        redirects = combinedRedirects,
        suppressedEntryIds = ctx.suppressedEntryIds,
    )
}

@Suppress("LongParameterList")
private suspend fun foldIncomingAndDeferredMerges(
    dao: RocheDao,
    explicitMerges: List<Pair<String, String>>,
    mergeRedirects: Map<String, String>,
    combinedRedirects: Map<String, String>,
    arrivedIds: Set<String>,
    nowIso: String,
) {
    val pendingFolds =
        collectPendingMergeFolds(
            explicitMerges = explicitMerges,
            mergeRedirects = mergeRedirects,
            combinedRedirects = combinedRedirects,
            arrivedIds = arrivedIds,
        )
    if (pendingFolds.isEmpty()) return
    val foldCache = DuplicateFoldBatchCache(dao)
    for ((dupId, canonId) in pendingFolds) {
        if (dao.getEntryById(canonId) != null) {
            foldMergedDuplicateInDao(dao, dupId, canonId, nowIso, foldCache)
        }
    }
}

private fun collectPendingMergeFolds(
    explicitMerges: List<Pair<String, String>>,
    mergeRedirects: Map<String, String>,
    combinedRedirects: Map<String, String>,
    arrivedIds: Set<String>,
): Map<String, String> {
    val pending = LinkedHashMap<String, String>()
    for ((dupId, rawCanonId) in explicitMerges) {
        val canonId = followMergedInMap(rawCanonId, combinedRedirects)
        if (canonId.isNotEmpty() && canonId != dupId) {
            pending[dupId] = canonId
        }
    }
    if (arrivedIds.isNotEmpty()) {
        for ((dupId, rawCanonId) in mergeRedirects) {
            val canonId = followMergedInMap(rawCanonId, combinedRedirects)
            val touchesArrived = canonId in arrivedIds || rawCanonId in arrivedIds
            if (touchesArrived && canonId != dupId && dupId !in pending) {
                pending[dupId] = canonId
            }
        }
    }
    return pending
}

private suspend fun buildIncomingEntriesMergeCtx(
    dao: RocheDao,
    response: PeerSyncResponseDto,
    serverNode: String,
    nowIso: String,
    nowMs: Long,
): IncomingEntriesMergeCtx {
    val tombstonesByKey =
        dao.getAllTombstones()
            .associateBy { it.entityType.lowercase() to it.naturalKey }
            .toMutableMap()
    val existingFeedSources = dao.getAllFeedSources()
    val feedSourceIdByCanonUrl = HashMap<String, String>(existingFeedSources.size)
    for (src in existingFeedSources) {
        val canonUrl = canonicalizeSyncUrl(src.feedUrl)
        if (canonUrl.isNotEmpty()) {
            feedSourceIdByCanonUrl.putIfAbsent(canonUrl, src.collectionId)
        }
    }
    val feedUrlsByColId = buildFeedUrlLookup(existingFeedSources, response.entries)
    val parentFeedsByEntryId =
        buildParentFeedUrlsByEntry(dao, response.collectionEntries, feedUrlsByColId)
    val incomingWtrEntryIds =
        response.collectionEntries
            .filter { it.collectionId.trim() == WANT_TO_READ_COLLECTION_ID }
            .mapTo(HashSet()) { it.entryId.trim() }
    return IncomingEntriesMergeCtx(
        tombstonesByKey = tombstonesByKey,
        itemDetIdToNatKey = buildItemTombstoneDetIdMap(tombstonesByKey),
        feedSourceIdByCanonUrl = feedSourceIdByCanonUrl,
        parentFeedsByEntryId = parentFeedsByEntryId,
        incomingWtrEntryIds = incomingWtrEntryIds,
        incomingCatalogReferencedIds = collectIncomingCatalogRefIds(response),
        idRemap = HashMap(),
        suppressedEntryIds = HashSet(),
        serverNode = serverNode,
        nowIso = nowIso,
        nowMs = nowMs,
    )
}

private fun buildItemTombstoneDetIdMap(
    tombstonesByKey: Map<Pair<String, String>, TombstoneEntity>
): Map<String, String> = buildMap {
    for ((type, natKey) in tombstonesByKey.keys) {
        if (type != TOMBSTONE_ENTITY_ITEM) continue
        val parts = natKey.split('\u0000')
        if (parts.size == 2) {
            val canonFeed = if (parts[0] == "manual") null else canonicalizeSyncUrl(parts[0])
            val canonItem = canonicalizeSyncUrl(parts[1])
            put(deterministicItemId(canonFeed, canonItem), natKey)
        }
    }
}

private fun buildFeedUrlLookup(
    existingFeedSources: List<FeedSourceEntity>,
    incomingEntries: List<SyncEntryRecordDto>,
): Map<String, String> = buildMap {
    for (src in existingFeedSources) {
        put(src.collectionId, canonicalizeSyncUrl(src.feedUrl))
    }
    for (rec in incomingEntries) {
        val fs = rec.feedSource ?: continue
        val cleanId = rec.id.trim()
        val canonUrl = canonicalizeSyncUrl(fs.feedUrl)
        if (cleanId.isNotEmpty() && canonUrl.isNotEmpty()) {
            put(cleanId, canonUrl)
        }
    }
}

private suspend fun buildParentFeedUrlsByEntry(
    dao: RocheDao,
    incomingMemberships: List<SyncCollectionEntryDto>,
    feedUrlsByColId: Map<String, String>,
): Map<String, Set<String>> {
    val result = HashMap<String, MutableSet<String>>()
    for (mem in dao.getAllCollectionEntries()) {
        val feedUrl = feedUrlsByColId[mem.collectionId] ?: continue
        result.getOrPut(mem.entryId) { HashSet() }.add(feedUrl)
    }
    for (ce in incomingMemberships) {
        val feedUrl = feedUrlsByColId[ce.collectionId.trim()] ?: continue
        val entryId = ce.entryId.trim()
        if (entryId.isNotEmpty()) {
            result.getOrPut(entryId) { HashSet() }.add(feedUrl)
        }
    }
    return result
}

private fun collectIncomingCatalogRefIds(response: PeerSyncResponseDto): Set<String> = buildSet {
    response.consumptionLog.forEach { log ->
        log.entryId.trim().takeIf { it.isNotEmpty() }?.let(::add)
    }
    response.collectionGenerators.forEach { gen ->
        gen.currentEntryId?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
    }
    response.comparisons.forEach { comp ->
        comp.scopeCollectionId?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        comp.ordering.flatten().forEach { id -> id.trim().takeIf { it.isNotEmpty() }?.let(::add) }
    }
}

private suspend fun mergeSingleIncomingEntry(
    dao: RocheDao,
    rec: SyncEntryRecordDto,
    ctx: IncomingEntriesMergeCtx,
): Pair<String, String>? {
    val cleanTitle = rec.title.trim()
    val resolvedKind = EntryKind.fromWireValue(rec.kind)
    if (cleanTitle.isEmpty() && rec.feedSource == null) return null

    val entryHlc =
        resolveOrSynthesizeHlc(
            rec.hlc,
            rec.updatedAt ?: rec.createdAt,
            ctx.nowIso,
            ctx.serverNode,
            ctx.nowMs,
        )
    val itemAction = resolveEntrySuppression(dao, rec, entryHlc, ctx)
    if (itemAction == ItemTombstoneAction.SUPPRESS_ENTRY) {
        rec.id.trim().takeIf { it.isNotEmpty() }?.let { ctx.suppressedEntryIds.add(it) }
        return null
    }
    val targetId =
        resolveIncomingTargetEntryId(
            dao = dao,
            rec = rec,
            resolvedKind = resolvedKind,
            cleanTitle = cleanTitle,
            feedSourceIdByCanonUrl = ctx.feedSourceIdByCanonUrl,
        )
    val rawId = rec.id.trim()
    if (rawId.isNotEmpty() && rawId != targetId) {
        ctx.idRemap[rawId] = targetId
    }
    val mergedInto = rec.mergedIntoId?.trim()?.takeIf { it.isNotEmpty() && it != targetId }

    val existingEntry = dao.getEntryById(targetId)
    val entryIncomingWins =
        shouldIncomingWinHlc(entryHlc, existingEntry?.hlc, existingEntry?.isLocalDirty == true)
    val updatedEntry =
        buildMergedEntryEntity(
            rec,
            targetId,
            resolvedKind,
            cleanTitle,
            mergedInto,
            entryHlc,
            existingEntry,
            ctx.nowIso,
        )
    dao.upsertEntry(updatedEntry)
    mergeIncomingEntrySubtables(
        dao = dao,
        targetId = targetId,
        mergedInto = mergedInto,
        resolvedKind = resolvedKind,
        rec = rec,
        updatedEntry = updatedEntry,
        entryHlc = entryHlc,
        entryIncomingWins = entryIncomingWins,
        itemAction = itemAction,
        ctx = ctx,
    )
    return mergedInto?.let { targetId to it }
}

@Suppress("LongParameterList")
private suspend fun mergeIncomingEntrySubtables(
    dao: RocheDao,
    targetId: String,
    mergedInto: String?,
    resolvedKind: EntryKind,
    rec: SyncEntryRecordDto,
    updatedEntry: EntryEntity,
    entryHlc: String,
    entryIncomingWins: Boolean,
    itemAction: ItemTombstoneAction,
    ctx: IncomingEntriesMergeCtx,
) {
    rec.collectionSpec?.let { specDto ->
        mergeIncomingSpecInDao(dao, targetId, specDto, resolvedKind)
    }
    rec.feedSource?.let { fsDto ->
        mergeIncomingFeedSourceInDao(
            dao = dao,
            targetId = targetId,
            fsDto = fsDto,
            entryHlc = entryHlc,
            ctx = ctx,
        )
    }
    if (itemAction != ItemTombstoneAction.SUPPRESS_INBOX_ONLY) {
        mergeContentAndStateInDao(
            dao,
            targetId,
            rec,
            updatedEntry,
            entryHlc,
            entryIncomingWins,
            ctx.serverNode,
            ctx.nowIso,
            ctx.nowMs,
        )
    }
    mergeIncomingIdentifiersInDao(dao, targetId, mergedInto, resolvedKind, rec)
}

private suspend fun resolveEntrySuppression(
    dao: RocheDao,
    rec: SyncEntryRecordDto,
    entryHlc: String,
    ctx: IncomingEntriesMergeCtx,
): ItemTombstoneAction =
    if (isSuppressedByTombstone(dao, rec, entryHlc, ctx)) {
        ItemTombstoneAction.SUPPRESS_ENTRY
    } else {
        resolveItemTombstoneAction(dao, rec, entryHlc, ctx)
    }

private suspend fun resolveItemTombstoneAction(
    dao: RocheDao,
    rec: SyncEntryRecordDto,
    entryHlc: String,
    ctx: IncomingEntriesMergeCtx,
): ItemTombstoneAction {
    if (rec.feedSource != null || rec.collectionSpec != null) return ItemTombstoneAction.ALLOW
    if (rec.state == null && rec.content == null) return ItemTombstoneAction.ALLOW
    val rawId = rec.id.trim()
    val parentFeeds = ctx.parentFeedsByEntryId[rawId].orEmpty()
    val inboxState = rec.state?.inboxState?.trim()?.lowercase()
    val eligibleForCheck =
        parentFeeds.isEmpty() ||
            inboxState == INBOX_STATE_UNREAD ||
            inboxState == INBOX_STATE_DISMISSED ||
            rec.content != null
    val matchingTombs =
        if (eligibleForCheck) {
            findMatchingItemTombstones(rec, rawId, parentFeeds, ctx)
        } else {
            emptyList()
        }
    return if (matchingTombs.isEmpty()) {
        ItemTombstoneAction.ALLOW
    } else {
        applyItemTombstoneDecision(dao, rec, entryHlc, matchingTombs, ctx)
    }
}

private fun findMatchingItemTombstones(
    rec: SyncEntryRecordDto,
    rawId: String,
    parentFeeds: Set<String>,
    ctx: IncomingEntriesMergeCtx,
): List<TombstoneEntity> {
    val canonUrl = rec.url?.trim()?.takeIf { it.isNotEmpty() }?.let(::canonicalizeSyncUrl)
    val keys = LinkedHashSet<String>()
    if (canonUrl != null) {
        if (parentFeeds.isEmpty()) {
            keys.add(itemNaturalKey(null, canonUrl))
        } else {
            parentFeeds.forEach { feedUrl -> keys.add(itemNaturalKey(feedUrl, canonUrl)) }
        }
    }
    ctx.itemDetIdToNatKey[rawId]?.let { keys.add(it) }
    return keys.mapNotNull { key -> ctx.tombstonesByKey[TOMBSTONE_ENTITY_ITEM to key] }
}

private suspend fun applyItemTombstoneDecision(
    dao: RocheDao,
    rec: SyncEntryRecordDto,
    entryHlc: String,
    matchingTombs: List<TombstoneEntity>,
    ctx: IncomingEntriesMergeCtx,
): ItemTombstoneAction {
    val rawId = rec.id.trim()
    val stateHlc =
        rec.state?.let { st ->
            resolveOrSynthesizeHlc(
                st.hlc.ifBlank { entryHlc },
                st.interactionUpdatedAt ?: rec.updatedAt,
                ctx.nowIso,
                ctx.serverNode,
                ctx.nowMs,
            )
        }
    val maxIncHlc = maxHlc(entryHlc, stateHlc)
    val inboxState = rec.state?.inboxState?.trim()?.lowercase()
    val carriesLiveInbox =
        inboxState == INBOX_STATE_UNREAD ||
            inboxState == INBOX_STATE_DISMISSED ||
            rec.content != null ||
            rawId in ctx.incomingWtrEntryIds
    var suppressed = false
    for (tomb in matchingTombs) {
        if (compareHlc(maxIncHlc, tomb.hlc) <= 0) {
            suppressed = true
        } else if (carriesLiveInbox) {
            dao.deleteTombstone(TOMBSTONE_ENTITY_ITEM, tomb.naturalKey)
            ctx.tombstonesByKey.remove(TOMBSTONE_ENTITY_ITEM to tomb.naturalKey)
        }
    }
    return when {
        !suppressed -> ItemTombstoneAction.ALLOW
        isPureInboxRecord(dao, rec, rawId, ctx.incomingCatalogReferencedIds) ->
            ItemTombstoneAction.SUPPRESS_ENTRY
        else -> ItemTombstoneAction.SUPPRESS_INBOX_ONLY
    }
}

private suspend fun isPureInboxRecord(
    dao: RocheDao,
    rec: SyncEntryRecordDto,
    rawId: String,
    incomingCatalogReferencedIds: Set<String>,
): Boolean =
    rec.releaseYear == null &&
        rec.metadata == null &&
        rec.identifiers.all {
            val scheme = normalizeIdentifierScheme(it.scheme)
            scheme == "url" || scheme == "arxiv"
        } &&
        rawId !in incomingCatalogReferencedIds &&
        (rawId.isEmpty() || !dao.entryHasCatalogHistory(rawId))

private suspend fun isSuppressedByTombstone(
    dao: RocheDao,
    rec: SyncEntryRecordDto,
    entryHlc: String,
    ctx: IncomingEntriesMergeCtx,
): Boolean {
    val rawId = rec.id.trim()
    val entryTomb =
        rawId.takeIf { it.isNotEmpty() }?.let { ctx.tombstonesByKey[TOMBSTONE_ENTITY_ENTRY to it] }
    val suppressedById =
        if (entryTomb != null) {
            if (compareHlc(entryTomb.hlc, entryHlc) >= 0) {
                true
            } else {
                dao.deleteTombstone(TOMBSTONE_ENTITY_ENTRY, rawId)
                ctx.tombstonesByKey.remove(TOMBSTONE_ENTITY_ENTRY to rawId)
                false
            }
        } else {
            false
        }
    return suppressedById ||
        isSuppressedByFeedTombstone(dao, rec, entryHlc, ctx) ||
        isSuppressedByTagTombstone(dao, rec, entryHlc, ctx.tombstonesByKey)
}

private suspend fun isSuppressedByFeedTombstone(
    dao: RocheDao,
    rec: SyncEntryRecordDto,
    entryHlc: String,
    ctx: IncomingEntriesMergeCtx,
): Boolean {
    val fs = rec.feedSource ?: return false
    val feedKey = feedNaturalKey(fs.feedUrl)
    val fsHlc = resolveOrSynthesizeHlc(fs.hlc, rec.updatedAt, ctx.nowIso, ctx.serverNode, ctx.nowMs)
    val liveHlc = maxHlc(entryHlc, fsHlc)
    val tomb = ctx.tombstonesByKey[TOMBSTONE_ENTITY_FEED to feedKey] ?: return false
    if (compareHlc(tomb.hlc, liveHlc) >= 0) return true
    dao.deleteTombstone(TOMBSTONE_ENTITY_FEED, feedKey)
    ctx.tombstonesByKey.remove(TOMBSTONE_ENTITY_FEED to feedKey)
    return false
}

private suspend fun isSuppressedByTagTombstone(
    dao: RocheDao,
    rec: SyncEntryRecordDto,
    entryHlc: String,
    tombstonesByKey: MutableMap<Pair<String, String>, TombstoneEntity>,
): Boolean {
    val spec = rec.collectionSpec
    if (spec == null || CollectionRole.fromWireValue(spec.role) != CollectionRole.TAG) {
        return false
    }
    val tagKey = tagNaturalKey(rec.title)
    val tomb = tombstonesByKey[TOMBSTONE_ENTITY_TAG to tagKey] ?: return false
    if (compareHlc(tomb.hlc, entryHlc) >= 0) return true
    dao.deleteTombstone(TOMBSTONE_ENTITY_TAG, tagKey)
    tombstonesByKey.remove(TOMBSTONE_ENTITY_TAG to tagKey)
    return false
}

private suspend fun resolveIncomingTargetEntryId(
    dao: RocheDao,
    rec: SyncEntryRecordDto,
    resolvedKind: EntryKind,
    cleanTitle: String,
    feedSourceIdByCanonUrl: Map<String, String>,
): String {
    val rawId = rec.id.trim()
    val existingById = rawId.takeIf { it.isNotEmpty() && dao.getEntryById(it) != null }
    return existingById
        ?: matchFeedOrSpecOwnerId(dao, rec, rawId, cleanTitle, feedSourceIdByCanonUrl)
        ?: matchIdentifierOwnerId(dao, rec.identifiers, resolvedKind)
        ?: matchCanonicalUrlOwnerId(dao, rec.url, resolvedKind)
        ?: rawId.ifEmpty {
            chooseDeterministicEntryId(
                explicitId = null,
                kind = resolvedKind,
                title = cleanTitle.ifEmpty { rec.url.orEmpty() },
                creators = rec.creators,
                identifiers = rec.identifiers,
            )
        }
}

private suspend fun matchFeedOrSpecOwnerId(
    dao: RocheDao,
    rec: SyncEntryRecordDto,
    rawId: String,
    cleanTitle: String,
    feedSourceIdByCanonUrl: Map<String, String>,
): String? {
    val fs = rec.feedSource
    if (fs != null) {
        val canonUrl = canonicalizeSyncUrl(fs.feedUrl)
        val existingId = feedSourceIdByCanonUrl[canonUrl]
        return existingId ?: rawId.ifEmpty { deterministicFeedId(canonUrl) }
    }
    val spec = rec.collectionSpec ?: return null
    return if (CollectionRole.fromWireValue(spec.role) == CollectionRole.TAG) {
        val normTag = normalizeTagName(cleanTitle)
        dao.getTagByName(normTag)?.id ?: rawId.ifEmpty { deterministicTagId(normTag) }
    } else {
        val slug = spec.slug?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        slug?.let { dao.getCollectionSpecBySlug(it) }?.takeIf { !isProtectedSpec(it) }?.collectionId
    }
}

private suspend fun matchIdentifierOwnerId(
    dao: RocheDao,
    identifiers: List<EntryIdentifierDto>,
    resolvedKind: EntryKind,
): String? {
    for (ident in identifiers) {
        val matched = matchSingleIdentifierOwner(dao, ident, resolvedKind)
        if (matched != null) return matched
    }
    return null
}

private suspend fun matchSingleIdentifierOwner(
    dao: RocheDao,
    ident: EntryIdentifierDto,
    resolvedKind: EntryKind,
): String? {
    val s = normalizeIdentifierScheme(ident.scheme)
    val v = normalizeIdentifierValue(resolvedKind, s, ident.value)
    if (s.isEmpty() || v.isEmpty()) return null
    val owner = dao.getEntryIdByIdentifier(s, v)
    val resolved = owner?.let { followMergedIdInDao(dao, it) ?: it }
    val compatible =
        resolved != null &&
            (s != "url" && s != "feed_url" ||
                dao.getEntryById(resolved)?.let {
                    EntryKind.fromWireValue(it.kind) == resolvedKind
                } != false)
    return resolved.takeIf { compatible }
}

private suspend fun matchCanonicalUrlOwnerId(
    dao: RocheDao,
    rawUrl: String?,
    resolvedKind: EntryKind,
): String? {
    val cleanUrl = rawUrl?.trim()?.takeIf { it.isNotEmpty() }?.let(::canonicalizeSyncUrl)
    val urlOwner = cleanUrl?.let { dao.getEntryIdByIdentifier("url", it) }
    val resolved = urlOwner?.let { followMergedIdInDao(dao, it) ?: it }
    return resolved?.takeIf { dao.getEntryById(it)?.kind == resolvedKind.wireValue }
}

@Suppress("LongParameterList")
private fun buildMergedEntryEntity(
    rec: SyncEntryRecordDto,
    targetId: String,
    resolvedKind: EntryKind,
    cleanTitle: String,
    mergedInto: String?,
    entryHlc: String,
    existing: EntryEntity?,
    nowIso: String,
): EntryEntity {
    val incomingWins = shouldIncomingWinHlc(entryHlc, existing?.hlc, existing?.isLocalDirty == true)
    val richerSummary =
        pickRicherString(existing?.summary, rec.summary)?.take(MAX_SQLITE_SUMMARY_CHARS)
    val metadataJson = rec.metadata?.toString()?.takeIf { it.isNotBlank() && it != "{}" }
    if (existing != null && !incomingWins) {
        return mergePassiveEntryFields(existing, rec, richerSummary, metadataJson, mergedInto)
    }
    return buildWinningEntryEntity(
        rec = rec,
        targetId = targetId,
        resolvedKind = resolvedKind,
        cleanTitle = cleanTitle,
        mergedInto = mergedInto,
        entryHlc = entryHlc,
        existing = existing,
        richerSummary = richerSummary,
        metadataJson = metadataJson,
        incomingWins = incomingWins,
        nowIso = nowIso,
    )
}

private fun mergePassiveEntryFields(
    existing: EntryEntity,
    rec: SyncEntryRecordDto,
    richerSummary: String?,
    metadataJson: String?,
    mergedInto: String?,
): EntryEntity {
    val creatorsJson =
        when {
            existing.creatorsJson != "[]" -> existing.creatorsJson
            rec.creators.isNotEmpty() -> encodeCreatorsJson(rec.creators)
            else -> "[]"
        }
    val cleanUrl = rec.url?.trim()?.takeIf { it.isNotEmpty() }
    val cleanPub = rec.publishedAt?.trim()?.takeIf { it.isNotEmpty() }
    return existing.copy(
        creatorsJson = creatorsJson,
        url = existing.url ?: cleanUrl,
        publishedAt = existing.publishedAt ?: cleanPub,
        releaseYear = existing.releaseYear ?: rec.releaseYear,
        summary = richerSummary,
        thumbnailUrl = existing.thumbnailUrl ?: sanitizeHttpUrl(rec.thumbnailUrl),
        metadataJson =
            if (existing.metadataJson != "{}") existing.metadataJson else metadataJson ?: "{}",
        mergedIntoId = mergedInto ?: existing.mergedIntoId,
    )
}

@Suppress("LongParameterList")
private fun buildWinningEntryEntity(
    rec: SyncEntryRecordDto,
    targetId: String,
    resolvedKind: EntryKind,
    cleanTitle: String,
    mergedInto: String?,
    entryHlc: String,
    existing: EntryEntity?,
    richerSummary: String?,
    metadataJson: String?,
    incomingWins: Boolean,
    nowIso: String,
): EntryEntity {
    val effectiveTitle = cleanTitle.ifEmpty {
        existing?.title ?: rec.feedSource?.feedUrl ?: targetId
    }
    val creatorsJson =
        if (rec.creators.isNotEmpty()) {
            encodeCreatorsJson(rec.creators)
        } else {
            existing?.creatorsJson ?: "[]"
        }
    return EntryEntity(
        id = targetId,
        kind = resolvedKind.wireValue,
        title = effectiveTitle,
        creatorsJson = creatorsJson,
        url = nonBlankOrFallback(rec.url, existing?.url),
        publishedAt = nonBlankOrFallback(rec.publishedAt, existing?.publishedAt),
        releaseYear = rec.releaseYear ?: existing?.releaseYear,
        summary = richerSummary,
        thumbnailUrl = sanitizeHttpUrl(rec.thumbnailUrl) ?: existing?.thumbnailUrl,
        metadataJson = metadataJson ?: existing?.metadataJson ?: "{}",
        mergedIntoId = mergedInto ?: existing?.mergedIntoId,
        createdAt = nonBlankOrFallback(rec.createdAt, existing?.createdAt) ?: nowIso,
        updatedAt = nonBlankOrFallback(rec.updatedAt, existing?.updatedAt) ?: nowIso,
        hlc = maxHlc(entryHlc, existing?.hlc),
        isLocalDirty = existing?.isLocalDirty == true && !incomingWins,
    )
}

private fun nonBlankOrFallback(primary: String?, fallback: String?): String? =
    primary?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

private suspend fun mergeIncomingSpecInDao(
    dao: RocheDao,
    targetId: String,
    specDto: SyncCollectionSpecDto,
    resolvedKind: EntryKind,
) {
    val existing = dao.getCollectionSpecById(targetId)
    val cleanColor = specDto.color?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.color
    if (mergeProtectedSystemSpecInDao(dao, targetId, cleanColor)) return
    if (existing != null && isProtectedSpec(existing)) return
    val role = CollectionRole.fromWireValue(specDto.role)
    val mode = CollectionMode.fromWireValue(specDto.mode)
    if (role == CollectionRole.SYSTEM && targetId != UP_NEXT_COLLECTION_ID) return
    val cleanSlug =
        if (role == CollectionRole.TAG) {
            val entryTitle = dao.getEntryById(targetId)?.title.orEmpty()
            "tag:${normalizeTagName(entryTitle)}"
        } else {
            specDto.slug?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: existing?.slug
        }
    dao.upsertCollectionSpec(
        CollectionSpecEntity(
            collectionId = targetId,
            slug = cleanSlug,
            mode = mode.wireValue,
            role = role.wireValue,
            ruleJson = specDto.ruleJson ?: existing?.ruleJson,
            showInLists = specDto.showInLists,
            color = cleanColor,
        )
    )
    insertIncomingSpecIdentifier(
        dao = dao,
        targetId = targetId,
        role = role,
        resolvedKind = resolvedKind,
        cleanSlug = cleanSlug,
    )
}

private suspend fun insertIncomingSpecIdentifier(
    dao: RocheDao,
    targetId: String,
    role: CollectionRole,
    resolvedKind: EntryKind,
    cleanSlug: String?,
) {
    if (cleanSlug.isNullOrEmpty()) return
    if (dao.getCollectionSpecById(targetId)?.slug != cleanSlug) return
    if (role == CollectionRole.TAG) {
        val tagVal = cleanSlug.removePrefix("tag:")
        if (tagVal.isNotEmpty()) {
            dao.insertEntryIdentifierIgnore(EntryIdentifierEntity("tag", tagVal, targetId))
        }
    } else if (resolvedKind == EntryKind.COLLECTION) {
        dao.insertEntryIdentifierIgnore(EntryIdentifierEntity("slug", cleanSlug, targetId))
    }
}

private suspend fun mergeProtectedSystemSpecInDao(
    dao: RocheDao,
    targetId: String,
    cleanColor: String?,
): Boolean {
    if (targetId != STARRED_COLLECTION_ID && targetId != WANT_TO_READ_COLLECTION_ID) return false
    val isStarred = targetId == STARRED_COLLECTION_ID
    val slug = if (isStarred) STARRED_SLUG else WANT_TO_READ_SLUG
    dao.upsertCollectionSpec(
        CollectionSpecEntity(
            collectionId = targetId,
            slug = slug,
            mode = CollectionMode.MANUAL.wireValue,
            role = if (isStarred) CollectionRole.LIST.wireValue else CollectionRole.WANT.wireValue,
            ruleJson = null,
            showInLists = isStarred,
            color = cleanColor,
        )
    )
    dao.insertEntryIdentifierIgnore(EntryIdentifierEntity("slug", slug, targetId))
    return true
}

private suspend fun mergeIncomingFeedSourceInDao(
    dao: RocheDao,
    targetId: String,
    fsDto: SyncFeedSourceDto,
    entryHlc: String,
    ctx: IncomingEntriesMergeCtx,
) {
    val existing = dao.getFeedSourceById(targetId)
    val fsHlc =
        resolveOrSynthesizeHlc(
            fsDto.hlc.ifBlank { entryHlc },
            fsDto.lastPolledAt,
            ctx.nowIso,
            ctx.serverNode,
            ctx.nowMs,
        )
    val merged = buildMergedFeedSourceEntity(targetId, fsDto, existing, fsHlc)
    if (dao.getCollectionSpecById(targetId) == null) {
        dao.upsertCollectionSpec(
            CollectionSpecEntity(
                collectionId = targetId,
                slug = null,
                mode = TOMBSTONE_ENTITY_FEED,
                role = TOMBSTONE_ENTITY_FEED,
                showInLists = false,
            )
        )
    }
    dao.upsertFeedSource(merged)
    val expectedFeedKind = entryKindForFeedTypeWire(merged.sourceType)
    dao.getEntryById(targetId)?.let { currentEntry ->
        if (currentEntry.kind != expectedFeedKind) {
            dao.upsertEntry(
                currentEntry.copy(
                    kind = expectedFeedKind,
                    hlc = maxHlc(currentEntry.hlc, merged.hlc),
                    isLocalDirty = currentEntry.isLocalDirty || merged.isLocalDirty,
                )
            )
        }
    }
    val canonFeedUrl = canonicalizeSyncUrl(merged.feedUrl)
    if (canonFeedUrl.isNotEmpty()) {
        ctx.feedSourceIdByCanonUrl[canonFeedUrl] = targetId
        dao.insertEntryIdentifierIgnore(EntryIdentifierEntity("feed_url", canonFeedUrl, targetId))
    }
    dao.clearFallbackFeedTitleForFeed(targetId)
}

private data class MergedFeedPollTelemetry(
    val lastPolledAt: String?,
    val lastPolledBy: String?,
    val lastError: String?,
    val lastErrorBy: String?,
    val consecutiveFailures: Int,
)

private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun resolveMergedFeedPollTelemetry(
    existing: FeedSourceEntity,
    fsDto: SyncFeedSourceDto,
    configWins: Boolean,
): MergedFeedPollTelemetry {
    val incomingError = fsDto.lastError.trimmedOrNull()
    val localPollNewer = isLocalPollNewer(existing.lastPolledAt, fsDto.lastPolledAt)
    val incomingPollNewer = isLocalPollNewer(fsDto.lastPolledAt, existing.lastPolledAt)
    val localPollWins = localPollNewer || !incomingPollNewer && !configWins
    val lastPolledAt = if (localPollWins) existing.lastPolledAt else fsDto.lastPolledAt
    val lastPolledBy = if (localPollWins) existing.lastPolledBy else fsDto.lastPolledBy
    val isRemoteUnarchive = configWins && !existing.isActive && fsDto.isActive
    if (isRemoteUnarchive && incomingError == null) {
        return MergedFeedPollTelemetry(lastPolledAt, lastPolledBy, null, null, 0)
    }
    val cleanError = if (localPollWins) existing.lastError.trimmedOrNull() else incomingError
    val rawErrorBy = if (localPollWins) existing.lastErrorBy else fsDto.lastErrorBy
    val rawFailures = if (localPollWins) existing.consecutiveFailures else fsDto.consecutiveFailures
    return MergedFeedPollTelemetry(
        lastPolledAt = lastPolledAt,
        lastPolledBy = lastPolledBy,
        lastError = cleanError,
        lastErrorBy = cleanError?.let { rawErrorBy.trimmedOrNull() },
        consecutiveFailures = normalizeConsecutiveFailures(cleanError, rawFailures),
    )
}

private fun buildMergedFeedSourceEntity(
    targetId: String,
    fsDto: SyncFeedSourceDto,
    existing: FeedSourceEntity?,
    fsHlc: String,
): FeedSourceEntity {
    if (existing == null) {
        val incomingError = fsDto.lastError.trimmedOrNull()
        val incomingErrorBy = incomingError?.let { fsDto.lastErrorBy.trimmedOrNull() }
        return FeedSourceEntity(
            collectionId = targetId,
            feedUrl = fsDto.feedUrl,
            siteUrl = null,
            sourceType = fsDto.sourceType.ifBlank { "rss" },
            feedTitle = fsDto.feedTitle?.takeIf { it.isNotBlank() },
            language = fsDto.language?.takeIf { it.isNotBlank() },
            pollInterval = fsDto.pollInterval,
            isActive = fsDto.isActive,
            lastPolledAt = fsDto.lastPolledAt,
            lastPolledBy = fsDto.lastPolledBy,
            lastError = incomingError,
            lastErrorBy = incomingErrorBy,
            consecutiveFailures =
                normalizeConsecutiveFailures(incomingError, fsDto.consecutiveFailures),
            autoDownloadEpisodes = false,
            customPlaybackSpeed = null,
            hlc = fsHlc,
            isLocalDirty = false,
        )
    }
    val configWins = shouldIncomingWinHlc(fsHlc, existing.hlc, existing.isLocalDirty)
    val telemetry = resolveMergedFeedPollTelemetry(existing, fsDto, configWins)
    val resolvedType = resolveMergedFeedSourceType(existing, fsDto, configWins)
    val resolvedTitle =
        if (configWins) {
            fsDto.feedTitle?.takeIf { it.isNotBlank() } ?: existing.feedTitle
        } else {
            existing.feedTitle
        }
    return FeedSourceEntity(
        collectionId = targetId,
        feedUrl = fsDto.feedUrl.ifBlank { existing.feedUrl },
        siteUrl = existing.siteUrl,
        sourceType = resolvedType,
        feedTitle = resolvedTitle,
        language = fsDto.language?.takeIf { it.isNotBlank() } ?: existing.language,
        pollInterval = if (configWins) fsDto.pollInterval else existing.pollInterval,
        isActive = if (configWins) fsDto.isActive else existing.isActive,
        lastPolledAt = telemetry.lastPolledAt,
        lastPolledBy = telemetry.lastPolledBy,
        lastError = telemetry.lastError,
        lastErrorBy = telemetry.lastErrorBy,
        consecutiveFailures = telemetry.consecutiveFailures,
        autoDownloadEpisodes = existing.autoDownloadEpisodes,
        customPlaybackSpeed = existing.customPlaybackSpeed,
        hlc = maxHlc(fsHlc, existing.hlc),
        isLocalDirty = existing.isLocalDirty && !configWins,
    )
}

private fun resolveMergedFeedSourceType(
    existing: FeedSourceEntity?,
    fsDto: SyncFeedSourceDto,
    configWins: Boolean,
): String =
    if (existing == null || configWins) {
        fsDto.sourceType.ifBlank { "rss" }
    } else {
        existing.sourceType
    }

@Suppress("LongParameterList")
private suspend fun mergeContentAndStateInDao(
    dao: RocheDao,
    targetId: String,
    rec: SyncEntryRecordDto,
    mergedEntry: EntryEntity,
    entryHlc: String,
    entryIncomingWins: Boolean,
    serverNode: String,
    nowIso: String,
    nowMs: Long,
) {
    mergeEntryContentRowInDao(dao, targetId, rec.content, mergedEntry.summary, entryIncomingWins)
    rec.state?.let { stateDto ->
        mergeEntryStateRowInDao(
            dao,
            targetId,
            rec,
            stateDto,
            mergedEntry,
            entryHlc,
            serverNode,
            nowIso,
            nowMs,
        )
    }
}

private suspend fun mergeEntryContentRowInDao(
    dao: RocheDao,
    targetId: String,
    contentDto: SyncEntryContentDto?,
    summary: String?,
    entryIncomingWins: Boolean,
) {
    val existingContent = dao.getEntryContentById(targetId)
    if (contentDto == null && existingContent == null) return
    val richerHtml =
        pickRicherString(existingContent?.contentHtml, contentDto?.contentHtml)
            ?.take(MAX_SQLITE_HTML_CHARS)
    val mergedAudio = resolveMergedAudioUrl(existingContent, contentDto, entryIncomingWins)
    val rawType =
        resolveMergedContentType(existingContent, contentDto, mergedAudio, entryIncomingWins)
    val revertedFromAudio =
        entryIncomingWins &&
            contentDto != null &&
            isAudioContentWire(existingContent?.contentType) &&
            !isAudioContentWire(rawType)
    val effectiveWireDuration =
        contentDto?.duration
            ?.takeIf {
                isAudioContentWire(rawType) ||
                    rawType.equals(ContentType.VIDEO.wireValue, ignoreCase = true)
            }
            ?.let(::formatDurationSecondsLabel)
    val mergedDuration =
        resolveSyncedContentDuration(
            contentType = rawType,
            wireDuration = effectiveWireDuration,
            existingDuration = if (revertedFromAudio) null else existingContent?.duration,
            summary = summary,
            contentText = existingContent?.contentText,
            contentHtml = richerHtml,
        )
    dao.upsertEntryContent(
        EntryContentEntity(
            entryId = targetId,
            contentType = rawType,
            contentText = existingContent?.contentText,
            contentHtml = richerHtml,
            audioUrl = mergedAudio,
            duration = mergedDuration,
            thumbnailMediaId = existingContent?.thumbnailMediaId,
            fallbackFeedTitle = existingContent?.fallbackFeedTitle,
        )
    )
}

private fun isAudioContentWire(raw: String?): Boolean =
    raw.equals(ContentType.AUDIO.wireValue, ignoreCase = true) ||
        raw.equals(EntryKind.PODCAST.wireValue, ignoreCase = true)

private fun resolveMergedAudioUrl(
    existingContent: EntryContentEntity?,
    contentDto: SyncEntryContentDto?,
    entryIncomingWins: Boolean,
): String? {
    if (contentDto == null) return existingContent?.audioUrl
    val incomingAudio = contentDto.audioUrl?.trim()?.takeIf { it.isNotEmpty() }
    val existingAudio = existingContent?.audioUrl
    val isExistingAudioType = isAudioContentWire(existingContent?.contentType)
    return when {
        existingContent == null || entryIncomingWins -> incomingAudio
        !existingAudio.isNullOrBlank() -> existingAudio
        isExistingAudioType -> incomingAudio
        else -> null
    }
}

private fun resolveMergedContentType(
    existingContent: EntryContentEntity?,
    contentDto: SyncEntryContentDto?,
    mergedAudio: String?,
    entryIncomingWins: Boolean,
): String {
    if (!mergedAudio.isNullOrBlank()) return ContentType.AUDIO.wireValue
    val incomingType = contentDto?.contentType?.takeIf { it.isNotBlank() }
    val existingType = existingContent?.contentType?.takeIf { it.isNotBlank() }
    return if (existingContent == null || entryIncomingWins) {
        incomingType ?: existingType ?: "article"
    } else {
        existingType ?: incomingType ?: "article"
    }
}

@Suppress("LongParameterList")
private suspend fun mergeEntryStateRowInDao(
    dao: RocheDao,
    targetId: String,
    rec: SyncEntryRecordDto,
    stateDto: SyncEntryStateDto,
    mergedEntry: EntryEntity,
    entryHlc: String,
    serverNode: String,
    nowIso: String,
    nowMs: Long,
) {
    val existingState = dao.getEntryStateById(targetId)
    val stateHlc =
        resolveOrSynthesizeHlc(
            stateDto.hlc.ifBlank { entryHlc },
            stateDto.interactionUpdatedAt ?: rec.updatedAt,
            nowIso,
            serverNode,
            nowMs,
        )
    val incProgress = clampFiniteProgress(stateDto.progress)
    val incomingInbox = normalizeInboxState(stateDto.inboxState, fallback = INBOX_STATE_UNREAD)
    val hlcCmp =
        if (existingState == null || existingState.hlc.isBlank()) {
            1
        } else {
            compareHlc(stateHlc, existingState.hlc)
        }
    if (existingState != null && hlcCmp < 0) return

    val fallbackCreatedAt = mergedEntry.createdAt.ifBlank { nowIso }
    val nextState =
        if (existingState != null && hlcCmp == 0) {
            mergeEqualHlcEntryState(
                targetId = targetId,
                existingState = existingState,
                stateDto = stateDto,
                incomingInbox = incomingInbox,
                incProgress = incProgress,
                mergedUpdatedAt =
                    stateDto.interactionUpdatedAt ?: mergedEntry.updatedAt.ifBlank { nowIso },
                fallbackCreatedAt = fallbackCreatedAt,
            )
        } else {
            buildIncomingEntryState(
                targetId = targetId,
                existingState = existingState,
                stateDto = stateDto,
                incomingInbox = incomingInbox,
                incProgress = incProgress,
                stateHlc = stateHlc,
                mergedUpdatedAt =
                    stateDto.interactionUpdatedAt ?: mergedEntry.updatedAt.ifBlank { nowIso },
                fallbackCreatedAt = fallbackCreatedAt,
            )
        }
    dao.upsertEntryState(nextState)
}

@Suppress("LongParameterList")
private fun buildIncomingEntryState(
    targetId: String,
    existingState: EntryStateEntity?,
    stateDto: SyncEntryStateDto,
    incomingInbox: String,
    incProgress: Double,
    stateHlc: String,
    mergedUpdatedAt: String,
    fallbackCreatedAt: String,
): EntryStateEntity {
    val mergedInteraction =
        newerIsoTimestamp(stateDto.interactionUpdatedAt, existingState?.interactionUpdatedAt)
    val resolvedReadAt =
        resolveStateReadAt(
            inboxState = incomingInbox,
            primaryReadAt = stateDto.readAt?.takeIf { it.isNotBlank() },
            existingReadAt = existingState?.readAt,
            incomingReadAt = stateDto.readAt,
            interactionUpdatedAt = mergedInteraction,
            fallbackCreatedAt = fallbackCreatedAt,
        )
    return EntryStateEntity(
        entryId = targetId,
        inboxState = incomingInbox,
        readAt = resolvedReadAt,
        progress = incProgress,
        interactionUpdatedAt = mergedInteraction,
        updatedAt = mergedUpdatedAt,
        isCachedOffline = existingState?.isCachedOffline ?: false,
        hlc = maxHlc(stateHlc, existingState?.hlc),
        isLocalDirty = false,
    )
}

@Suppress("LongParameterList")
private fun mergeEqualHlcEntryState(
    targetId: String,
    existingState: EntryStateEntity,
    stateDto: SyncEntryStateDto,
    incomingInbox: String,
    incProgress: Double,
    mergedUpdatedAt: String,
    fallbackCreatedAt: String,
): EntryStateEntity {
    val existingInbox = normalizeInboxState(existingState.inboxState, fallback = INBOX_STATE_NONE)
    val mergedInbox = joinInboxState(existingInbox, incomingInbox)
    val mergedProgress = maxOf(clampFiniteProgress(existingState.progress), incProgress)
    val mergedRawReadAt = newerIsoTimestamp(existingState.readAt, stateDto.readAt)
    val mergedInteraction =
        newerIsoTimestamp(existingState.interactionUpdatedAt, stateDto.interactionUpdatedAt)
    val resolvedReadAt =
        resolveStateReadAt(
            inboxState = mergedInbox,
            primaryReadAt = mergedRawReadAt,
            existingReadAt = existingState.readAt,
            incomingReadAt = stateDto.readAt,
            interactionUpdatedAt = mergedInteraction,
            fallbackCreatedAt = fallbackCreatedAt,
        )
    val incomingEffectiveReadAt =
        resolveStateReadAt(
            inboxState = incomingInbox,
            primaryReadAt = stateDto.readAt?.takeIf { it.isNotBlank() },
            existingReadAt = null,
            incomingReadAt = stateDto.readAt,
            interactionUpdatedAt = stateDto.interactionUpdatedAt,
            fallbackCreatedAt = fallbackCreatedAt,
        )
    val mergedMatchesIncoming =
        mergedInbox == incomingInbox &&
            mergedProgress == incProgress &&
            sameOptionalTimestamp(resolvedReadAt, incomingEffectiveReadAt)
    return EntryStateEntity(
        entryId = targetId,
        inboxState = mergedInbox,
        readAt = resolvedReadAt,
        progress = mergedProgress,
        interactionUpdatedAt = mergedInteraction,
        updatedAt = mergedUpdatedAt,
        isCachedOffline = existingState.isCachedOffline,
        hlc = existingState.hlc,
        isLocalDirty = !mergedMatchesIncoming,
    )
}

internal fun clampFiniteProgress(raw: Double): Double =
    if (raw.isFinite()) raw.coerceIn(0.0, 1.0) else 0.0

internal fun normalizeInboxState(raw: String?, fallback: String): String {
    val clean = raw?.trim()?.lowercase().orEmpty()
    return when (clean) {
        INBOX_STATE_DISMISSED -> INBOX_STATE_DISMISSED
        INBOX_STATE_UNREAD -> INBOX_STATE_UNREAD
        INBOX_STATE_NONE -> INBOX_STATE_NONE
        else -> fallback
    }
}

internal fun joinInboxState(a: String, b: String): String =
    when {
        a == INBOX_STATE_DISMISSED || b == INBOX_STATE_DISMISSED -> INBOX_STATE_DISMISSED
        a == INBOX_STATE_UNREAD || b == INBOX_STATE_UNREAD -> INBOX_STATE_UNREAD
        else -> INBOX_STATE_NONE
    }

@Suppress("LongParameterList")
private fun resolveStateReadAt(
    inboxState: String,
    primaryReadAt: String?,
    existingReadAt: String?,
    incomingReadAt: String?,
    interactionUpdatedAt: String?,
    fallbackCreatedAt: String,
): String? =
    if (inboxState == INBOX_STATE_DISMISSED) {
        primaryReadAt?.takeIf { it.isNotBlank() }
            ?: existingReadAt?.takeIf { it.isNotBlank() }
            ?: incomingReadAt?.takeIf { it.isNotBlank() }
            ?: interactionUpdatedAt?.takeIf { it.isNotBlank() }
            ?: fallbackCreatedAt
    } else {
        primaryReadAt?.takeIf { it.isNotBlank() }
    }

private fun sameOptionalTimestamp(a: String?, b: String?): Boolean {
    val cleanA = a?.trim()?.takeIf { it.isNotEmpty() }
    val cleanB = b?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanA == cleanB) return true
    if (cleanA == null || cleanB == null) return false
    val instA = parseIsoInstant(cleanA)
    val instB = parseIsoInstant(cleanB)
    return instA != null && instB != null && instA == instB
}

@Suppress("LongParameterList")
private fun resolveSyncedContentDuration(
    contentType: String,
    wireDuration: String?,
    existingDuration: String?,
    summary: String?,
    contentText: String?,
    contentHtml: String?,
): String? =
    resolveArticleDuration(
        existingDuration = wireDuration ?: existingDuration,
        isArticle = ContentType.fromWireValue(contentType) == ContentType.ARTICLE,
        hasExtendedContent =
            summary.orEmpty().length > LIST_PREVIEW_SUMMARY_CHARS ||
                contentText.orEmpty().length > LIST_PREVIEW_TEXT_CHARS ||
                contentHtml.orEmpty().length > LIST_PREVIEW_HTML_CHARS,
        fallbackTextSource = contentText ?: contentHtml ?: summary.orEmpty(),
    )

private suspend fun mergeIncomingIdentifiersInDao(
    dao: RocheDao,
    targetId: String,
    mergedInto: String?,
    resolvedKind: EntryKind,
    rec: SyncEntryRecordDto,
) {
    val identOwner = mergedInto?.takeIf { dao.getEntryById(it) != null } ?: targetId
    val rows = ArrayList<EntryIdentifierEntity>()
    for (ident in rec.identifiers) {
        val s = normalizeIdentifierScheme(ident.scheme)
        val v = normalizeIdentifierValue(resolvedKind, s, ident.value)
        if (s.isNotEmpty() && v.isNotEmpty()) {
            rows.add(EntryIdentifierEntity(s, v, identOwner))
        }
    }
    val cleanUrl = rec.url?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanUrl != null && rec.feedSource == null && rec.collectionSpec == null) {
        val canon = canonicalizeSyncUrl(cleanUrl)
        if (canon.isNotEmpty()) {
            rows.add(EntryIdentifierEntity("url", canon, identOwner))
        }
        extractArxivIdentifier(cleanUrl)?.let { arxiv ->
            rows.add(EntryIdentifierEntity("arxiv", arxiv, identOwner))
        }
    }
    if (rows.isNotEmpty()) {
        dao.insertEntryIdentifiersIgnore(rows.distinctBy { it.scheme to it.value })
    }
}

internal fun shouldIncomingWinHlc(
    incomingHlc: String,
    localHlc: String?,
    localDirty: Boolean,
): Boolean {
    if (localHlc.isNullOrBlank()) return true
    val cmp = compareHlc(incomingHlc, localHlc)
    return if (localDirty) cmp > 0 else cmp >= 0
}

internal fun isProtectedSpec(spec: CollectionSpecEntity): Boolean =
    spec.collectionId in PROTECTED_SYSTEM_COLLECTION_IDS ||
        CollectionRole.fromWireValue(spec.role) == CollectionRole.SYSTEM ||
        CollectionMode.fromWireValue(spec.mode) == CollectionMode.DYNAMIC

private fun buildMergeRedirectMap(entries: Collection<EntryEntity>): Map<String, String> =
    buildMap {
        for (entry in entries) {
            val target = entry.mergedIntoId?.trim()?.takeIf { it.isNotEmpty() && it != entry.id }
            if (target != null) {
                put(entry.id, target)
            }
        }
    }

internal fun followMergedInMap(id: String, redirects: Map<String, String>): String {
    var current = id.trim()
    if (current.isEmpty() || redirects.isEmpty()) return current
    val visited = HashSet<String>(4)
    while (visited.add(current)) {
        val next = redirects[current] ?: break
        current = next
    }
    return current
}

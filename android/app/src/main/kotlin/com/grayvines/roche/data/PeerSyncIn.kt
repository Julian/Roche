@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data

import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.EntryContentEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryIdentifierEntity
import com.grayvines.roche.data.local.EntryStateEntity
import com.grayvines.roche.data.local.FeedSourceEntity
import com.grayvines.roche.data.local.LIST_PREVIEW_HTML_CHARS
import com.grayvines.roche.data.local.LIST_PREVIEW_SUMMARY_CHARS
import com.grayvines.roche.data.local.LIST_PREVIEW_TEXT_CHARS
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.SQLITE_DELETE_CHUNK_SIZE
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.data.local.deleteEntryCascade
import com.grayvines.roche.data.local.deleteFeedPreservingHistory
import com.grayvines.roche.data.local.deleteItemPreservingHistory
import com.grayvines.roche.data.local.ensureStarredSpecExists
import com.grayvines.roche.data.local.entryHasCatalogHistory
import com.grayvines.roche.data.remote.PeerSyncRequestDto
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.remote.formatDurationSecondsLabel
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.formatArticleMinutes

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
    val allRemoteHlcs = collectResponseHlcs(response)

    val runTx: suspend () -> Unit = {
        dao.executeInTransaction {
            dao.ensureStarredSpecExists()
            witnessRemoteHlcInDao(
                dao = dao,
                nodeId = localPeerId.ifBlank { DEFAULT_LOCAL_HLC_NODE },
                remoteHlcValues = allRemoteHlcs,
            )
            acknowledgeSentDirtyRows(dao, sentBatch)
            applyIncomingTombstonesInTx(dao, response.tombstones, serverNode, nowIso)
            val redirects = mergeIncomingEntriesInTx(dao, response.entries, serverNode, nowIso)
            mergeIncomingGraphEdgesInTx(
                dao = dao,
                catalogStore = catalogStore,
                response = response,
                redirects = redirects,
                serverNode = serverNode,
                nowIso = nowIso,
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
    if (sentBatch.sentLogIds.isNotEmpty()) {
        sentBatch.sentLogIds.chunked(SQLITE_DELETE_CHUNK_SIZE).forEach {
            dao.clearConsumptionLogsDirty(it)
        }
    }
    if (sentBatch.sentComparisonIds.isNotEmpty()) {
        sentBatch.sentComparisonIds.chunked(SQLITE_DELETE_CHUNK_SIZE).forEach {
            dao.clearComparisonsDirty(it)
        }
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

private const val TOMBSTONE_ENTITY_ENTRY = "entry"
private const val TOMBSTONE_ENTITY_FEED = "feed"
private const val TOMBSTONE_ENTITY_ITEM = "item"
private const val TOMBSTONE_ENTITY_TAG = "tag"

private suspend fun applyIncomingTombstonesInTx(
    dao: RocheDao,
    tombstones: List<SyncTombstoneDto>,
    serverNode: String,
    nowIso: String,
) {
    for (tomb in tombstones) {
        val rawKey = tomb.naturalKey.trim()
        if (rawKey.isEmpty()) continue
        val tombHlc = resolveOrSynthesizeHlc(tomb.hlc, tomb.deletedAt, nowIso, serverNode)
        val deletedAt = tomb.deletedAt.ifBlank { nowIso }
        when (tomb.entityType.trim().lowercase()) {
            TOMBSTONE_ENTITY_FEED -> applyFeedTombstone(dao, rawKey, deletedAt, tombHlc)
            TOMBSTONE_ENTITY_ITEM -> applyItemTombstone(dao, rawKey, deletedAt, tombHlc)
            TOMBSTONE_ENTITY_TAG -> applyTagTombstone(dao, rawKey, deletedAt, tombHlc)
            "collection" -> applyCollectionTombstone(dao, rawKey, deletedAt, tombHlc)
            COLLECTION_ENTRY_ENTITY ->
                applyCollectionEntryTombstone(dao, rawKey, deletedAt, tombHlc)
            TOMBSTONE_ENTITY_ENTRY -> applyEntryTombstone(dao, rawKey, deletedAt, tombHlc)
        }
    }
}

private suspend fun applyFeedTombstone(
    dao: RocheDao,
    rawKey: String,
    deletedAt: String,
    tombHlc: String,
) {
    val canonKey = feedNaturalKey(rawKey)
    val detId = deterministicFeedId(canonKey)
    val match =
        dao.getAllFeedSources().firstOrNull {
            feedNaturalKey(it.feedUrl) == canonKey || it.collectionId == detId
        }
    if (match != null) {
        val entryHlc = dao.getEntryById(match.collectionId)?.hlc.orEmpty()
        val liveHlc = maxOf(match.hlc, entryHlc)
        if (liveHlc > tombHlc) return
        dao.deleteFeedPreservingHistory(match.collectionId, recordTombstones = false)
    }
    upsertCleanTombstone(dao, TOMBSTONE_ENTITY_FEED, canonKey, deletedAt, tombHlc)
}

private suspend fun applyItemTombstone(
    dao: RocheDao,
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

    val feedUrlsByColId =
        dao.getAllFeedSources().associate { it.collectionId to canonicalizeSyncUrl(it.feedUrl) }
    val membershipsByEntry =
        dao.getAllCollectionEntries().groupBy({ it.entryId }, { it.collectionId })
    val statesById = dao.getAllEntryStates().associateBy { it.entryId }

    val candidates =
        dao.getAllEntries().filter { entry ->
            doesInboxEntryMatchTombstone(
                entry = entry,
                state = statesById[entry.id],
                detId = detId,
                canonFeed = canonFeed,
                canonItem = canonItem,
                entryColIds = membershipsByEntry[entry.id].orEmpty(),
                feedUrlsByColId = feedUrlsByColId,
            )
        }

    if (candidates.any { statesById[it.id]?.hlc.orEmpty() > tombHlc }) return
    for (cand in candidates) {
        dao.deleteItemPreservingHistory(cand.id, recordTombstones = false)
    }
    upsertCleanTombstone(dao, TOMBSTONE_ENTITY_ITEM, canonKey, deletedAt, tombHlc)
}

@Suppress("LongParameterList")
private fun doesInboxEntryMatchTombstone(
    entry: EntryEntity,
    state: EntryStateEntity?,
    detId: String,
    canonFeed: String?,
    canonItem: String,
    entryColIds: List<String>,
    feedUrlsByColId: Map<String, String>,
): Boolean {
    val inInbox = state != null && (state.inboxState == "unread" || state.inboxState == "dismissed")
    if (!inInbox) return false
    if (entry.id == detId) return true
    if (canonicalizeSyncUrl(entry.url.orEmpty()) != canonItem) return false
    val entryFeeds = entryColIds.mapNotNull { feedUrlsByColId[it] }
    return if (canonFeed == null) entryFeeds.isEmpty() else canonFeed in entryFeeds
}

private suspend fun applyTagTombstone(
    dao: RocheDao,
    rawKey: String,
    deletedAt: String,
    tombHlc: String,
) {
    val canonKey = tagNaturalKey(rawKey)
    val wantSlug = "tag:$canonKey"
    val tagSpecs =
        dao.getAllCollectionSpecs().filter {
            CollectionRole.fromWireValue(it.role) == CollectionRole.TAG
        }
    val matched = tagSpecs.mapNotNull { spec ->
        dao.getEntryById(spec.collectionId)?.takeIf { entry ->
            spec.slug?.lowercase() == wantSlug || entry.title.trim().lowercase() == canonKey
        }
    }
    if (matched.any { it.hlc > tombHlc }) return
    for (entry in matched) {
        dao.deleteEntryCascade(entry.id)
    }
    upsertCleanTombstone(dao, TOMBSTONE_ENTITY_TAG, canonKey, deletedAt, tombHlc)
}

private suspend fun applyCollectionTombstone(
    dao: RocheDao,
    rawKey: String,
    deletedAt: String,
    tombHlc: String,
) {
    val canonSlug = rawKey.lowercase()
    val spec = dao.getCollectionSpecBySlug(canonSlug)
    if (spec != null && !isProtectedSpec(spec)) {
        val entry = dao.getEntryById(spec.collectionId)
        if (entry != null && entry.hlc > tombHlc) return
        dao.deleteEntryCascade(spec.collectionId)
    }
    upsertCleanTombstone(dao, "collection", canonSlug, deletedAt, tombHlc)
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
    if (existing != null && existing.hlc > tombHlc) return
    dao.deleteCollectionEntry(colId, entryId)
    upsertCleanTombstone(dao, COLLECTION_ENTRY_ENTITY, canonKey, deletedAt, tombHlc)
}

private suspend fun applyEntryTombstone(
    dao: RocheDao,
    rawKey: String,
    deletedAt: String,
    tombHlc: String,
) {
    val existing = dao.getEntryById(rawKey)
    if (existing != null) {
        if (existing.hlc > tombHlc) return
        if (dao.entryHasCatalogHistory(rawKey)) {
            dao.getEntryStateById(rawKey)?.let { st ->
                dao.upsertEntryState(
                    st.copy(inboxState = "none", readAt = null, hlc = tombHlc, isLocalDirty = false)
                )
            }
            dao.deleteEntryContentById(rawKey)
        } else {
            dao.deleteEntryCascade(rawKey)
        }
    }
    upsertCleanTombstone(dao, TOMBSTONE_ENTITY_ENTRY, rawKey, deletedAt, tombHlc)
}

internal suspend fun upsertCleanTombstone(
    dao: RocheDao,
    entityType: String,
    naturalKey: String,
    deletedAt: String,
    hlc: String,
) {
    val current = dao.getTombstone(entityType, naturalKey)
    if (current != null && current.hlc > hlc) return
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

private suspend fun mergeIncomingEntriesInTx(
    dao: RocheDao,
    incoming: List<SyncEntryRecordDto>,
    serverNode: String,
    nowIso: String,
): Map<String, String> {
    val idRemap = HashMap<String, String>()
    val explicitMerges = ArrayList<Pair<String, String>>()
    val tombstonesByKey =
        dao.getAllTombstones()
            .associateBy { it.entityType.lowercase() to it.naturalKey }
            .toMutableMap()

    for (rec in incoming) {
        val mergedPair =
            mergeSingleIncomingEntry(dao, rec, tombstonesByKey, idRemap, serverNode, nowIso)
        if (mergedPair != null) {
            explicitMerges.add(mergedPair)
        }
    }
    val allEntries = dao.getAllEntries()
    val mergeRedirects = buildMergeRedirectMap(allEntries)
    val combinedRedirects = idRemap + mergeRedirects
    for ((dupId, rawCanonId) in explicitMerges) {
        val canonId = followMergedInMap(rawCanonId, combinedRedirects)
        if (canonId.isNotEmpty() && canonId != dupId) {
            foldMergedDuplicateInDao(dao, dupId, canonId, nowIso)
        }
    }
    return combinedRedirects
}

private suspend fun mergeSingleIncomingEntry(
    dao: RocheDao,
    rec: SyncEntryRecordDto,
    tombstonesByKey: MutableMap<Pair<String, String>, TombstoneEntity>,
    idRemap: MutableMap<String, String>,
    serverNode: String,
    nowIso: String,
): Pair<String, String>? {
    val cleanTitle = rec.title.trim()
    val resolvedKind = EntryKind.fromWireValue(rec.kind)
    if (cleanTitle.isEmpty() && rec.feedSource == null) return null

    val entryHlc =
        resolveOrSynthesizeHlc(rec.hlc, rec.updatedAt ?: rec.createdAt, nowIso, serverNode)
    if (isSuppressedByTombstone(dao, rec, entryHlc, serverNode, nowIso, tombstonesByKey)) {
        return null
    }
    val targetId = resolveIncomingTargetEntryId(dao, rec, resolvedKind, cleanTitle)
    val rawId = rec.id.trim()
    if (rawId.isNotEmpty() && rawId != targetId) {
        idRemap[rawId] = targetId
    }
    val mergedInto = rec.mergedIntoId?.trim()?.takeIf { it.isNotEmpty() && it != targetId }

    val existingEntry = dao.getEntryById(targetId)
    val updatedEntry =
        buildMergedEntryEntity(
            rec,
            targetId,
            resolvedKind,
            cleanTitle,
            mergedInto,
            entryHlc,
            existingEntry,
            nowIso,
        )
    dao.upsertEntry(updatedEntry)

    rec.collectionSpec?.let { specDto ->
        mergeIncomingSpecInDao(dao, targetId, specDto, resolvedKind)
    }
    rec.feedSource?.let { fsDto ->
        mergeIncomingFeedSourceInDao(dao, targetId, fsDto, entryHlc, serverNode, nowIso)
    }
    mergeContentAndStateInDao(
        dao,
        targetId,
        rec,
        updatedEntry,
        entryHlc,
        serverNode,
        nowIso,
    )
    mergeIncomingIdentifiersInDao(dao, targetId, mergedInto, resolvedKind, rec)

    return mergedInto?.let { targetId to it }
}

private suspend fun isSuppressedByTombstone(
    dao: RocheDao,
    rec: SyncEntryRecordDto,
    entryHlc: String,
    serverNode: String,
    nowIso: String,
    tombstonesByKey: MutableMap<Pair<String, String>, TombstoneEntity>,
): Boolean {
    val rawId = rec.id.trim()
    val suppressedById =
        rawId.isNotEmpty() &&
            tombstonesByKey[TOMBSTONE_ENTITY_ENTRY to rawId]?.let { it.hlc >= entryHlc } == true
    return suppressedById ||
        isSuppressedByFeedTombstone(dao, rec, entryHlc, serverNode, nowIso, tombstonesByKey) ||
        isSuppressedByTagTombstone(dao, rec, entryHlc, tombstonesByKey)
}

private suspend fun isSuppressedByFeedTombstone(
    dao: RocheDao,
    rec: SyncEntryRecordDto,
    entryHlc: String,
    serverNode: String,
    nowIso: String,
    tombstonesByKey: MutableMap<Pair<String, String>, TombstoneEntity>,
): Boolean {
    val fs = rec.feedSource ?: return false
    val feedKey = feedNaturalKey(fs.feedUrl)
    val fsHlc = resolveOrSynthesizeHlc(fs.hlc, rec.updatedAt, nowIso, serverNode)
    val liveHlc = maxOf(entryHlc, fsHlc)
    val tomb = tombstonesByKey[TOMBSTONE_ENTITY_FEED to feedKey] ?: return false
    if (tomb.hlc >= liveHlc) return true
    dao.deleteTombstone(TOMBSTONE_ENTITY_FEED, feedKey)
    tombstonesByKey.remove(TOMBSTONE_ENTITY_FEED to feedKey)
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
    if (tomb.hlc >= entryHlc) return true
    dao.deleteTombstone(TOMBSTONE_ENTITY_TAG, tagKey)
    tombstonesByKey.remove(TOMBSTONE_ENTITY_TAG to tagKey)
    return false
}

private suspend fun resolveIncomingTargetEntryId(
    dao: RocheDao,
    rec: SyncEntryRecordDto,
    resolvedKind: EntryKind,
    cleanTitle: String,
): String {
    val rawId = rec.id.trim()
    val existingById = rawId.takeIf { it.isNotEmpty() && dao.getEntryById(it) != null }
    return existingById
        ?: matchFeedOrSpecOwnerId(dao, rec, rawId, cleanTitle)
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
): String? {
    val fs = rec.feedSource
    if (fs != null) {
        val canonUrl = canonicalizeSyncUrl(fs.feedUrl)
        val existingSource =
            dao.getAllFeedSources().firstOrNull { canonicalizeSyncUrl(it.feedUrl) == canonUrl }
        return existingSource?.collectionId ?: rawId.ifEmpty { deterministicFeedId(canonUrl) }
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
        hlc = maxOf(entryHlc, existing?.hlc.orEmpty()),
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
    if (existing != null && isProtectedSpec(existing)) return
    val role = CollectionRole.fromWireValue(specDto.role)
    val mode = CollectionMode.fromWireValue(specDto.mode)
    if (
        role == CollectionRole.SYSTEM &&
            targetId != STARRED_COLLECTION_ID &&
            targetId != UP_NEXT_COLLECTION_ID
    ) {
        return
    }
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
            color = specDto.color?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.color,
        )
    )
    if (role == CollectionRole.TAG) {
        val tagVal = cleanSlug?.removePrefix("tag:").orEmpty()
        if (tagVal.isNotEmpty()) {
            dao.insertEntryIdentifierIgnore(EntryIdentifierEntity("tag", tagVal, targetId))
        }
    } else if (!cleanSlug.isNullOrEmpty() && resolvedKind == EntryKind.COLLECTION) {
        dao.insertEntryIdentifierIgnore(EntryIdentifierEntity("slug", cleanSlug, targetId))
    }
}

private suspend fun mergeIncomingFeedSourceInDao(
    dao: RocheDao,
    targetId: String,
    fsDto: SyncFeedSourceDto,
    entryHlc: String,
    serverNode: String,
    nowIso: String,
) {
    val existing = dao.getFeedSourceById(targetId)
    val fsHlc =
        resolveOrSynthesizeHlc(
            fsDto.hlc.ifBlank { entryHlc },
            fsDto.lastPolledAt,
            nowIso,
            serverNode,
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
    val canonFeedUrl = canonicalizeSyncUrl(merged.feedUrl)
    if (canonFeedUrl.isNotEmpty()) {
        dao.insertEntryIdentifierIgnore(EntryIdentifierEntity("feed_url", canonFeedUrl, targetId))
    }
    dao.clearFallbackFeedTitleForFeed(targetId)
}

private fun buildMergedFeedSourceEntity(
    targetId: String,
    fsDto: SyncFeedSourceDto,
    existing: FeedSourceEntity?,
    fsHlc: String,
): FeedSourceEntity {
    if (existing == null) {
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
            lastError = fsDto.lastError,
            lastErrorBy = fsDto.lastErrorBy,
            autoDownloadEpisodes = false,
            customPlaybackSpeed = null,
            hlc = fsHlc,
            isLocalDirty = false,
        )
    }
    val configWins = shouldIncomingWinHlc(fsHlc, existing.hlc, existing.isLocalDirty)
    val pollSrc = existing.takeIf { isLocalPollNewer(it.lastPolledAt, fsDto.lastPolledAt) }
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
        lastPolledAt = pollSrc?.lastPolledAt ?: fsDto.lastPolledAt,
        lastPolledBy = pollSrc?.lastPolledBy ?: fsDto.lastPolledBy,
        lastError = pollSrc?.lastError ?: fsDto.lastError,
        lastErrorBy = pollSrc?.lastErrorBy ?: fsDto.lastErrorBy,
        autoDownloadEpisodes = existing.autoDownloadEpisodes,
        customPlaybackSpeed = existing.customPlaybackSpeed,
        hlc = maxOf(fsHlc, existing.hlc),
        isLocalDirty = existing.isLocalDirty && !configWins,
    )
}

private fun isLocalPollNewer(localPolledAt: String?, incomingPolledAt: String?): Boolean =
    localPolledAt != null && (incomingPolledAt == null || localPolledAt > incomingPolledAt)

private fun resolveMergedFeedSourceType(
    existing: FeedSourceEntity?,
    fsDto: SyncFeedSourceDto,
    configWins: Boolean,
): String {
    val preservePodcast =
        existing?.sourceType?.equals("podcast", ignoreCase = true) == true &&
            fsDto.sourceType.equals("rss", ignoreCase = true)
    return when {
        preservePodcast -> "podcast"
        existing == null || configWins -> fsDto.sourceType.ifBlank { "rss" }
        else -> existing.sourceType
    }
}

@Suppress("LongParameterList")
private suspend fun mergeContentAndStateInDao(
    dao: RocheDao,
    targetId: String,
    rec: SyncEntryRecordDto,
    mergedEntry: EntryEntity,
    entryHlc: String,
    serverNode: String,
    nowIso: String,
) {
    mergeEntryContentRowInDao(dao, targetId, rec.content, mergedEntry.summary)
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
        )
    }
}

private suspend fun mergeEntryContentRowInDao(
    dao: RocheDao,
    targetId: String,
    contentDto: SyncEntryContentDto?,
    summary: String?,
) {
    val existingContent = dao.getEntryContentById(targetId)
    if (contentDto == null && existingContent == null) return
    val richerHtml =
        pickRicherString(existingContent?.contentHtml, contentDto?.contentHtml)
            ?.take(MAX_SQLITE_HTML_CHARS)
    val mergedAudio =
        contentDto?.audioUrl?.trim()?.takeIf { it.isNotEmpty() } ?: existingContent?.audioUrl
    val rawType =
        if (!mergedAudio.isNullOrBlank()) {
            ContentType.AUDIO.wireValue
        } else {
            contentDto?.contentType?.takeIf { it.isNotBlank() }
                ?: existingContent?.contentType
                ?: "article"
        }
    val mergedDuration =
        resolveSyncedContentDuration(
            contentType = rawType,
            wireDuration = contentDto?.duration?.let(::formatDurationSecondsLabel),
            existingDuration = existingContent?.duration,
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
) {
    val existingState = dao.getEntryStateById(targetId)
    val stateHlc =
        resolveOrSynthesizeHlc(
            stateDto.hlc.ifBlank { entryHlc },
            stateDto.interactionUpdatedAt ?: rec.updatedAt,
            nowIso,
            serverNode,
        )
    val stateWins =
        shouldIncomingWinHlc(stateHlc, existingState?.hlc, existingState?.isLocalDirty == true)
    if (existingState != null && !stateWins) return

    val resolvedReadAt =
        if (stateDto.inboxState.equals("dismissed", ignoreCase = true)) {
            stateDto.readAt?.takeIf { it.isNotBlank() }
                ?: existingState?.readAt?.takeIf { it.isNotBlank() }
                ?: mergedEntry.createdAt.ifBlank { nowIso }
        } else {
            stateDto.readAt?.takeIf { it.isNotBlank() }
        }
    dao.upsertEntryState(
        EntryStateEntity(
            entryId = targetId,
            inboxState = stateDto.inboxState.ifBlank { "unread" },
            readAt = resolvedReadAt,
            progress = stateDto.progress,
            interactionUpdatedAt =
                newerIsoTimestamp(
                    stateDto.interactionUpdatedAt,
                    existingState?.interactionUpdatedAt,
                ),
            updatedAt = stateDto.interactionUpdatedAt ?: mergedEntry.updatedAt.ifBlank { nowIso },
            isCachedOffline = existingState?.isCachedOffline ?: false,
            hlc = maxOf(stateHlc, existingState?.hlc.orEmpty()),
            isLocalDirty = false,
        )
    )
}

@Suppress("LongParameterList")
private fun resolveSyncedContentDuration(
    contentType: String,
    wireDuration: String?,
    existingDuration: String?,
    summary: String?,
    contentText: String?,
    contentHtml: String?,
): String? {
    val explicit = wireDuration ?: existingDuration
    if (ContentType.fromWireValue(contentType) != ContentType.ARTICLE) {
        return explicit
    }
    if (explicit != null && !explicit.matches(Regex("""^\d+m$"""))) {
        return explicit
    }
    val hasExtended =
        summary.orEmpty().length > LIST_PREVIEW_SUMMARY_CHARS ||
            contentText.orEmpty().length > LIST_PREVIEW_TEXT_CHARS ||
            contentHtml.orEmpty().length > LIST_PREVIEW_HTML_CHARS
    if (!hasExtended) return null
    val source = contentText ?: contentHtml ?: summary.orEmpty()
    return if (source.isNotBlank()) formatArticleMinutes(source) else null
}

private suspend fun mergeIncomingIdentifiersInDao(
    dao: RocheDao,
    targetId: String,
    mergedInto: String?,
    resolvedKind: EntryKind,
    rec: SyncEntryRecordDto,
) {
    val identOwner = mergedInto ?: targetId
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
    return if (localDirty) incomingHlc > localHlc else incomingHlc >= localHlc
}

internal fun isProtectedSpec(spec: CollectionSpecEntity): Boolean =
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

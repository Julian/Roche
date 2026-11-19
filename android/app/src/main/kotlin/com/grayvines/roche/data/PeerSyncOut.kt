@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data

import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.CollectionGeneratorEntity
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.ComparisonEntity
import com.grayvines.roche.data.local.ConsumptionLogEntity
import com.grayvines.roche.data.local.EntryContentEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryStateEntity
import com.grayvines.roche.data.local.FeedSourceEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.data.remote.PeerSyncRequestDto
import com.grayvines.roche.data.remote.rocheJson
import kotlinx.serialization.json.JsonObject

internal const val MAX_PEER_SYNC_PAYLOAD_BYTES = 4 * 1024 * 1024
private const val MAX_PEER_SYNC_ENTRIES = 2_000
private const val MAX_SYNC_COLLECTION_ENTRIES = 8_000
private const val MAX_SYNC_CONSUMPTION_LOG = 8_000
private const val MAX_SYNC_COMPARISONS = 8_000
private const val ENTRY_JSON_BASE_BYTES = 512
private const val UTF8_JSON_ESCAPE_FACTOR = 2
private const val INVALID_DURATION = -1L
private const val MM_SS_PART_COUNT = 2
private const val HH_MM_SS_PART_COUNT = 3
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

internal data class PeerSyncRequestConfig(
    val peerId: String,
    val deviceName: String,
    val networkTier: NetworkTier = NetworkTier.WIFI,
    val sinceSeq: Long = 0L,
    val weightOverride: Double? = null,
    val forcedEntryIds: Set<String> = emptySet(),
    val excludeEntryIds: Set<String> = emptySet(),
    val maxPayloadBytes: Int = MAX_PEER_SYNC_PAYLOAD_BYTES,
)

@Suppress("LongParameterList")
internal data class TrackedOutgoingSyncBatch(
    val request: PeerSyncRequestDto,
    val sentEntryHlcById: Map<String, String> = emptyMap(),
    val sentStateHlcById: Map<String, String> = emptyMap(),
    val sentFeedSourceHlcById: Map<String, String> = emptyMap(),
    val sentMembershipHlcByKey: Map<Pair<String, String>, String> = emptyMap(),
    val sentGeneratorHlcById: Map<String, String> = emptyMap(),
    val sentLogIds: Set<String> = emptySet(),
    val sentComparisonIds: Set<String> = emptySet(),
    val sentTombstoneHlcByKey: Map<Pair<String, String>, String> = emptyMap(),
    val byteOverflowEntryIds: Set<String> = emptySet(),
    val countOverflowEntryIds: Set<String> = emptySet(),
) {
    val sentEntryIds: Set<String>
        get() = sentEntryHlcById.keys
}

@Suppress("LongParameterList")
private data class OutgoingGraphSnapshot(
    val entries: List<EntryEntity>,
    val statesById: Map<String, EntryStateEntity>,
    val identsByEntryId: Map<String, List<EntryIdentifierDto>>,
    val specsById: Map<String, CollectionSpecEntity>,
    val feedSourcesById: Map<String, FeedSourceEntity>,
    val memberships: List<CollectionEntryEntity>,
    val generators: List<CollectionGeneratorEntity>,
    val logs: List<ConsumptionLogEntity>,
    val comparisons: List<ComparisonEntity>,
    val tombstones: List<TombstoneEntity>,
    val clientHlc: String?,
)

internal suspend fun buildPeerSyncRequest(
    dao: RocheDao,
    config: PeerSyncRequestConfig,
): PeerSyncRequestDto = buildTrackedPeerSyncRequest(dao, config).request

internal suspend fun buildTrackedPeerSyncRequest(
    dao: RocheDao,
    config: PeerSyncRequestConfig,
): TrackedOutgoingSyncBatch {
    val snap = loadOutgoingGraphSnapshot(dao, config.peerId)
    val slices = prepareOutgoingSlices(snap, config)
    val cappedIds = slices.eligibleEntries.take(MAX_PEER_SYNC_ENTRIES).map { it.id }
    val contentsById = loadEntryContentsByIds(dao, cappedIds, slices.unsyncedFeedIds)
    val part =
        partitionOutgoingEntries(
            slices.eligibleEntries,
            snap,
            contentsById,
            slices.unsyncedFeedIds,
            config.maxPayloadBytes,
        )
    val allOverflowIds = part.countOverflowIds + part.byteOverflowIds
    val nonOverflowMemberships =
        slices.sentMemberships.filter {
            it.collectionId !in allOverflowIds && it.entryId !in allOverflowIds
        }
    val nonOverflowGenerators =
        if (allOverflowIds.isEmpty()) {
            slices.sentGenerators
        } else {
            slices.sentGenerators.filter {
                it.collectionId !in allOverflowIds && it.currentEntryId !in allOverflowIds
            }
        }
    val nonOverflowLogs =
        if (allOverflowIds.isEmpty()) {
            slices.sentLogs
        } else {
            slices.sentLogs.filter { it.entryId !in allOverflowIds }
        }
    val nonOverflowComparisons =
        if (allOverflowIds.isEmpty()) {
            slices.sentComparisons
        } else {
            slices.sentComparisons.filter { comp ->
                comp.scopeCollectionId !in allOverflowIds &&
                    decodeComparisonOrdering(comp.orderingJson)?.flatten()?.none {
                        it.trim() in allOverflowIds
                    } != false
            }
        }
    return assembleTrackedBatch(
        config,
        snap,
        part.wireEntries,
        part.budgetEntries,
        slices.unsyncedFeedIds,
        nonOverflowMemberships,
        nonOverflowGenerators,
        nonOverflowLogs,
        nonOverflowComparisons,
        slices.sentTombstones,
        part.byteOverflowIds,
        part.countOverflowIds,
    )
}

private data class PreparedOutgoingSlices(
    val unsyncedFeedIds: Set<String>,
    val sentMemberships: List<CollectionEntryEntity>,
    val sentLogs: List<ConsumptionLogEntity>,
    val sentComparisons: List<ComparisonEntity>,
    val sentGenerators: List<CollectionGeneratorEntity>,
    val sentTombstones: List<TombstoneEntity>,
    val eligibleEntries: List<EntryEntity>,
)

private fun prepareOutgoingSlices(
    snap: OutgoingGraphSnapshot,
    config: PeerSyncRequestConfig,
): PreparedOutgoingSlices {
    val isInitial = config.sinceSeq <= 0L
    val unsyncedFeedIds =
        snap.feedSourcesById.values
            .filterNot { isSyncablePublicUrl(it.feedUrl) }
            .mapTo(HashSet()) { it.collectionId }
    val localOnlyPdfIds = collectLocalOnlyPdfIds(snap)
    val catalogRefIds = collectCatalogHistoryIds(snap) - localOnlyPdfIds
    val unsyncedInboxOnlyIds =
        collectUnsyncedInboxOnlyIds(snap, unsyncedFeedIds, catalogRefIds) + localOnlyPdfIds
    val sentMemberships =
        selectOutgoingMemberships(snap, unsyncedFeedIds, unsyncedInboxOnlyIds, isInitial)
            .take(MAX_SYNC_COLLECTION_ENTRIES)
    val sentLogs = selectOutgoingLogs(snap.logs, unsyncedInboxOnlyIds, isInitial)
    val sentComparisons =
        selectOutgoingComparisons(snap.comparisons, unsyncedInboxOnlyIds, isInitial)
    val sentGenerators = selectOutgoingGenerators(snap, isInitial)
    val sentTombstones = selectOutgoingTombstones(snap.tombstones, isInitial)
    val outgoingChildRefs =
        resolveOutgoingChildRefs(
            isInitial,
            sentMemberships,
            sentGenerators,
            sentLogs,
            sentComparisons,
        )
    val eligibleEntries =
        selectEligibleOutgoingEntries(
            snap,
            config,
            isInitial,
            unsyncedFeedIds,
            unsyncedInboxOnlyIds,
            catalogRefIds,
            outgoingChildRefs,
        )
    return PreparedOutgoingSlices(
        unsyncedFeedIds = unsyncedFeedIds,
        sentMemberships = sentMemberships,
        sentLogs = sentLogs,
        sentComparisons = sentComparisons,
        sentGenerators = sentGenerators,
        sentTombstones = sentTombstones,
        eligibleEntries = eligibleEntries,
    )
}

private fun collectLocalOnlyPdfIds(snap: OutgoingGraphSnapshot): Set<String> =
    snap.entries
        .filter { it.url?.startsWith("localpdf://", ignoreCase = true) == true }
        .mapTo(HashSet()) { it.id }

private fun resolveOutgoingChildRefs(
    isInitial: Boolean,
    sentMemberships: List<CollectionEntryEntity>,
    sentGenerators: List<CollectionGeneratorEntity>,
    sentLogs: List<ConsumptionLogEntity>,
    sentComparisons: List<ComparisonEntity>,
): Set<String> {
    val refs =
        collectReferencedIdsFromSlices(sentMemberships, sentGenerators, sentLogs, sentComparisons)
    return if (isInitial) refs else refs - PROTECTED_SYSTEM_COLLECTION_IDS
}

private data class PartitionedOutgoingEntries(
    val budgetEntries: List<EntryEntity>,
    val wireEntries: List<SyncEntryRecordDto>,
    val countOverflowIds: Set<String>,
    val byteOverflowIds: Set<String>,
)

private const val SQLITE_CONTENT_CHUNK_SIZE = 500

private suspend fun loadEntryContentsByIds(
    dao: RocheDao,
    entryIds: List<String>,
    unsyncedFeedIds: Set<String>,
): Map<String, EntryContentEntity> {
    val fetchIds = entryIds.filter { it !in unsyncedFeedIds }
    if (fetchIds.isEmpty()) return emptyMap()
    val result = HashMap<String, EntryContentEntity>(fetchIds.size)
    for (chunk in fetchIds.chunked(SQLITE_CONTENT_CHUNK_SIZE)) {
        for (row in dao.getEntryContentsByIds(chunk)) {
            result[row.entryId] = row
        }
    }
    return result
}

private fun partitionOutgoingEntries(
    eligibleEntries: List<EntryEntity>,
    snap: OutgoingGraphSnapshot,
    contentsById: Map<String, EntryContentEntity>,
    unsyncedFeedIds: Set<String>,
    maxPayloadBytes: Int,
): PartitionedOutgoingEntries {
    val cappedEntries = eligibleEntries.take(MAX_PEER_SYNC_ENTRIES)
    val countOverflowIds =
        eligibleEntries.drop(MAX_PEER_SYNC_ENTRIES).mapTo(LinkedHashSet()) { it.id }
    val (budgetEntries, byteOverflowIds) =
        splitEntriesByByteBudget(cappedEntries, contentsById, unsyncedFeedIds, maxPayloadBytes)
    val wireEntries = budgetEntries.map {
        toSyncEntryRecordDto(it, snap, contentsById, unsyncedFeedIds)
    }
    return PartitionedOutgoingEntries(budgetEntries, wireEntries, countOverflowIds, byteOverflowIds)
}

private fun selectOutgoingLogs(
    logs: List<ConsumptionLogEntity>,
    unsyncedInboxOnlyIds: Set<String>,
    isInitial: Boolean,
): List<ConsumptionLogEntity> =
    logs
        .filter { (isInitial || it.isLocalDirty) && it.entryId !in unsyncedInboxOnlyIds }
        .sortedWith(compareByDescending<ConsumptionLogEntity> { it.isLocalDirty }.thenBy { it.hlc })
        .take(MAX_SYNC_CONSUMPTION_LOG)

private fun selectOutgoingComparisons(
    comparisons: List<ComparisonEntity>,
    unsyncedInboxOnlyIds: Set<String>,
    isInitial: Boolean,
): List<ComparisonEntity> =
    comparisons
        .filter { comp ->
            (isInitial || comp.isLocalDirty) &&
                decodeComparisonOrdering(comp.orderingJson)?.flatten()?.none {
                    it.trim() in unsyncedInboxOnlyIds
                } != false
        }
        .sortedWith(compareByDescending<ComparisonEntity> { it.isLocalDirty }.thenBy { it.hlc })
        .take(MAX_SYNC_COMPARISONS)

private suspend fun loadOutgoingGraphSnapshot(
    dao: RocheDao,
    peerId: String,
): OutgoingGraphSnapshot {
    val entries = dao.getAllEntries()
    val statesById = dao.getAllEntryStates().associateBy { it.entryId }
    val identsByEntryId =
        dao.getAllEntryIdentifiers()
            .groupBy { it.entryId }
            .mapValues { (_, list) -> list.map { EntryIdentifierDto(it.scheme, it.value) } }
    val specsById = dao.getAllCollectionSpecs().associateBy { it.collectionId }
    val feedSourcesById = dao.getAllFeedSources().associateBy { it.collectionId }
    val storedHlc = dao.getLocalSyncFlag(FLAG_LOCAL_HLC)
    val clientHlc = storedHlc ?: HlcClock(peerId.ifBlank { DEFAULT_LOCAL_HLC_NODE }).tick().encode()
    return OutgoingGraphSnapshot(
        entries = entries,
        statesById = statesById,
        identsByEntryId = identsByEntryId,
        specsById = specsById,
        feedSourcesById = feedSourcesById,
        memberships = dao.getAllCollectionEntries(),
        generators = dao.getAllCollectionGenerators(),
        logs = dao.getAllConsumptionLogs(),
        comparisons = dao.getAllComparisons(),
        tombstones = dao.getAllTombstones(),
        clientHlc = clientHlc,
    )
}

private fun collectCatalogHistoryIds(snap: OutgoingGraphSnapshot): Set<String> = buildSet {
    snap.logs.forEach { add(it.entryId) }
    snap.generators.mapNotNullTo(this) { it.currentEntryId }
    snap.memberships.forEach { mem ->
        val role = snap.specsById[mem.collectionId]?.let { CollectionRole.fromWireValue(it.role) }
        if (role == CollectionRole.LIST || role == CollectionRole.WANT) {
            add(mem.entryId)
        }
    }
    snap.comparisons.forEach { comp ->
        comp.scopeCollectionId?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        decodeComparisonOrdering(comp.orderingJson)?.flatten()?.forEach { id ->
            id.trim().takeIf { it.isNotEmpty() }?.let(::add)
        }
    }
}

private fun collectUnsyncedInboxOnlyIds(
    snap: OutgoingGraphSnapshot,
    unsyncedFeedIds: Set<String>,
    catalogRefIds: Set<String>,
): Set<String> {
    val itemsInUnsyncedFeeds =
        snap.memberships
            .filter { it.collectionId in unsyncedFeedIds }
            .mapTo(HashSet()) { it.entryId }
    return snap.entries
        .filter { entry ->
            val state = snap.statesById[entry.id]
            val isInbox = state != null && state.inboxState != "none"
            val isLocalPdf = entry.url?.startsWith("localpdf://", ignoreCase = true) == true
            val badItemUrl = (isInbox || isLocalPdf) && !isSyncableItemUrl(entry.url.orEmpty())
            val isUnsynced = entry.id in itemsInUnsyncedFeeds || badItemUrl
            isUnsynced && entry.id !in catalogRefIds
        }
        .mapTo(HashSet()) { it.id }
}

private fun selectOutgoingMemberships(
    snap: OutgoingGraphSnapshot,
    unsyncedFeedIds: Set<String>,
    unsyncedInboxOnlyIds: Set<String>,
    isInitial: Boolean,
): List<CollectionEntryEntity> =
    snap.memberships
        .filter { mem ->
            mem.collectionId !in unsyncedFeedIds &&
                mem.entryId !in unsyncedInboxOnlyIds &&
                (isInitial || mem.isLocalDirty)
        }
        .sortedWith(
            compareByDescending<CollectionEntryEntity> { it.isLocalDirty }.thenBy { it.hlc }
        )

private fun selectOutgoingGenerators(
    snap: OutgoingGraphSnapshot,
    isInitial: Boolean,
): List<CollectionGeneratorEntity> =
    snap.generators.filter { gen ->
        val spec = snap.specsById[gen.collectionId]
        val isManualList =
            gen.collectionId != STARRED_COLLECTION_ID &&
                spec != null &&
                CollectionMode.fromWireValue(spec.mode) == CollectionMode.MANUAL &&
                CollectionRole.fromWireValue(spec.role) == CollectionRole.LIST
        val includeOnInitial = isInitial && (gen.enabled || !gen.currentEntryId.isNullOrBlank())
        isManualList && (gen.isLocalDirty || includeOnInitial)
    }

private fun selectOutgoingTombstones(
    tombstones: List<TombstoneEntity>,
    isInitial: Boolean,
): List<TombstoneEntity> = tombstones.filter { isInitial || it.isLocalDirty }

private fun collectReferencedIdsFromSlices(
    memberships: List<CollectionEntryEntity>,
    generators: List<CollectionGeneratorEntity>,
    logs: List<ConsumptionLogEntity>,
    comparisons: List<ComparisonEntity>,
): Set<String> = buildSet {
    memberships.forEach {
        add(it.collectionId)
        add(it.entryId)
    }
    generators.forEach {
        add(it.collectionId)
        it.currentEntryId?.takeIf(String::isNotBlank)?.let(::add)
    }
    logs.forEach { add(it.entryId) }
    comparisons.forEach { comp ->
        comp.scopeCollectionId?.takeIf(String::isNotBlank)?.let(::add)
        decodeComparisonOrdering(comp.orderingJson)?.flatten()?.forEach { id ->
            id.trim().takeIf(String::isNotEmpty)?.let(::add)
        }
    }
}

@Suppress("LongParameterList")
private fun selectEligibleOutgoingEntries(
    snap: OutgoingGraphSnapshot,
    config: PeerSyncRequestConfig,
    isInitial: Boolean,
    unsyncedFeedIds: Set<String>,
    unsyncedInboxOnlyIds: Set<String>,
    catalogRefIds: Set<String>,
    outgoingChildRefs: Set<String>,
): List<EntryEntity> =
    snap.entries
        .filter { entry ->
            isEntryEligibleForPush(
                entry = entry,
                snap = snap,
                config = config,
                isInitial = isInitial,
                unsyncedFeedIds = unsyncedFeedIds,
                unsyncedInboxOnlyIds = unsyncedInboxOnlyIds,
                catalogRefIds = catalogRefIds,
                outgoingChildRefs = outgoingChildRefs,
            )
        }
        .sortedWith(
            compareByDescending<EntryEntity> { entry ->
                    isAnyPartDirty(entry, snap)
                }
                .thenByDescending { snap.statesById[it.id]?.interactionUpdatedAt ?: it.updatedAt }
        )

@Suppress("LongParameterList")
private fun isEntryEligibleForPush(
    entry: EntryEntity,
    snap: OutgoingGraphSnapshot,
    config: PeerSyncRequestConfig,
    isInitial: Boolean,
    unsyncedFeedIds: Set<String>,
    unsyncedInboxOnlyIds: Set<String>,
    catalogRefIds: Set<String>,
    outgoingChildRefs: Set<String>,
): Boolean {
    if (entry.id in config.excludeEntryIds || entry.id in unsyncedInboxOnlyIds) return false
    val spec = snap.specsById[entry.id]
    val isSeededSystemOrDynamic =
        spec != null &&
            (CollectionRole.fromWireValue(spec.role) == CollectionRole.SYSTEM ||
                CollectionMode.fromWireValue(spec.mode) == CollectionMode.DYNAMIC)
    if (isSeededSystemOrDynamic) return false
    if (entry.id in PROTECTED_SYSTEM_COLLECTION_IDS) {
        return entry.id in config.forcedEntryIds || isAnyPartDirty(entry, snap)
    }
    if (entry.id in unsyncedFeedIds && entry.id !in catalogRefIds) return false

    return isInitial ||
        entry.id in config.forcedEntryIds ||
        isAnyPartDirty(entry, snap) ||
        entry.id in outgoingChildRefs
}

private fun isAnyPartDirty(entry: EntryEntity, snap: OutgoingGraphSnapshot): Boolean =
    entry.isLocalDirty ||
        snap.statesById[entry.id]?.isLocalDirty == true ||
        snap.feedSourcesById[entry.id]?.isLocalDirty == true

private fun splitEntriesByByteBudget(
    cappedEntries: List<EntryEntity>,
    contentsById: Map<String, EntryContentEntity>,
    unsyncedFeedIds: Set<String>,
    maxBytes: Int,
): Pair<List<EntryEntity>, Set<String>> {
    val sent = ArrayList<EntryEntity>(cappedEntries.size)
    val overflow = LinkedHashSet<String>()
    var usedBytes = 0
    for (entry in cappedEntries) {
        val estBytes = estimateSyncEntryBytes(entry, contentsById, unsyncedFeedIds)
        val fits = sent.isEmpty() || usedBytes + estBytes <= maxBytes
        if (fits) {
            sent.add(entry)
            usedBytes += estBytes
        } else {
            overflow.add(entry.id)
        }
    }
    return sent to overflow
}

internal fun estimateSyncEntryBytes(
    entry: EntryEntity,
    contentHtml: String? = null,
    audioUrl: String? = null,
): Int {
    val chars =
        entry.title.length +
            (entry.url?.length ?: 0) +
            entry.creatorsJson.length +
            (entry.summary?.length ?: 0) +
            (contentHtml?.length ?: 0) +
            (entry.thumbnailUrl?.length ?: 0) +
            (audioUrl?.length ?: 0)
    return ENTRY_JSON_BASE_BYTES + chars * UTF8_JSON_ESCAPE_FACTOR
}

private fun estimateSyncEntryBytes(
    entry: EntryEntity,
    contentsById: Map<String, EntryContentEntity>,
    unsyncedFeedIds: Set<String>,
): Int {
    val content = if (entry.id in unsyncedFeedIds) null else contentsById[entry.id]
    return estimateSyncEntryBytes(entry, content?.contentHtml, content?.audioUrl)
}

private fun toSyncEntryRecordDto(
    entry: EntryEntity,
    snap: OutgoingGraphSnapshot,
    contentsById: Map<String, EntryContentEntity>,
    unsyncedFeedIds: Set<String>,
): SyncEntryRecordDto {
    val isUnsyncedFeed = entry.id in unsyncedFeedIds
    val belongsToUnsyncedFeed =
        snap.memberships.any { it.entryId == entry.id && it.collectionId in unsyncedFeedIds }
    val stripInboxPayload =
        isUnsyncedFeed ||
            belongsToUnsyncedFeed ||
            snap.statesById[entry.id] != null && !isSyncableItemUrl(entry.url.orEmpty())

    val rawContent = if (stripInboxPayload) null else contentsById[entry.id]
    val rawState = if (stripInboxPayload) null else snap.statesById[entry.id]
    val rawSpec = if (isUnsyncedFeed) null else snap.specsById[entry.id]
    val rawFeedSource = if (isUnsyncedFeed) null else snap.feedSourcesById[entry.id]
    val safeUrl = resolveSafeOutgoingEntryUrl(entry.url, stripInboxPayload)
    val safeIdents =
        filterSyncableIdentifiers(snap.identsByEntryId[entry.id].orEmpty(), stripInboxPayload)

    return SyncEntryRecordDto(
        id = entry.id,
        kind = entry.kind,
        title = entry.title,
        creators = decodeCreatorsJson(entry.creatorsJson),
        url = safeUrl,
        publishedAt = entry.publishedAt,
        releaseYear = entry.releaseYear,
        summary = entry.summary,
        thumbnailUrl = entry.thumbnailUrl?.takeIf { isSyncableItemUrl(it) },
        metadata = decodeEntryMetadataObject(entry.metadataJson),
        identifiers = safeIdents,
        mergedIntoId = entry.mergedIntoId,
        createdAt = entry.createdAt,
        updatedAt = entry.updatedAt,
        hlc = entry.hlc,
        content = rawContent?.let(::toSyncEntryContentDto),
        state = rawState?.let(::toSyncEntryStateDto),
        collectionSpec = rawSpec?.let(::toSyncCollectionSpecDto),
        feedSource = rawFeedSource?.let(::toSyncFeedSourceDto),
    )
}

private fun resolveSafeOutgoingEntryUrl(rawUrl: String?, stripInboxPayload: Boolean): String? =
    if (stripInboxPayload) {
        rawUrl?.takeIf { isSyncablePublicUrl(it) }
    } else {
        rawUrl?.takeIf { isSyncableItemUrl(it) }
    }

private fun filterSyncableIdentifiers(
    idents: List<EntryIdentifierDto>,
    stripInboxPayload: Boolean,
): List<EntryIdentifierDto> = idents.filter { ident ->
    when (ident.scheme.lowercase()) {
        "feed_url" -> isSyncablePublicUrl(ident.value)
        "url" ->
            if (stripInboxPayload) {
                isSyncablePublicUrl(ident.value)
            } else {
                isSyncableItemUrl(ident.value)
            }
        else -> true
    }
}

private fun decodeEntryMetadataObject(metadataJson: String): JsonObject? =
    metadataJson
        .takeIf { it.isNotBlank() && it != "{}" }
        ?.let { raw ->
            runCatching { rocheJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        }
        ?.takeIf { it.isNotEmpty() }

private fun toSyncEntryContentDto(content: EntryContentEntity): SyncEntryContentDto =
    SyncEntryContentDto(
        contentType = content.contentType,
        contentHtml = content.contentHtml,
        audioUrl = content.audioUrl?.takeIf { isSyncableItemUrl(it) },
        duration = parseDurationToSecondsLong(content.duration),
    )

private fun toSyncEntryStateDto(state: EntryStateEntity): SyncEntryStateDto =
    SyncEntryStateDto(
        inboxState = state.inboxState,
        readAt = state.readAt?.takeIf { it.isNotBlank() },
        progress = state.progress,
        interactionUpdatedAt = state.interactionUpdatedAt?.takeIf { it.isNotBlank() },
        hlc = state.hlc,
    )

private fun toSyncCollectionSpecDto(spec: CollectionSpecEntity): SyncCollectionSpecDto =
    SyncCollectionSpecDto(
        slug = spec.slug,
        mode = spec.mode,
        role = spec.role,
        ruleJson = spec.ruleJson,
        showInLists = spec.showInLists,
        color = spec.color,
    )

private fun toSyncFeedSourceDto(source: FeedSourceEntity): SyncFeedSourceDto =
    SyncFeedSourceDto(
        feedUrl = source.feedUrl,
        sourceType = source.sourceType,
        feedTitle = source.feedTitle,
        language = source.language,
        pollInterval = source.pollInterval,
        isActive = source.isActive,
        lastPolledAt = source.lastPolledAt,
        lastPolledBy = source.lastPolledBy,
        lastError = source.lastError,
        lastErrorBy = source.lastErrorBy,
        hlc = source.hlc,
    )

@Suppress("LongParameterList")
private fun assembleTrackedBatch(
    config: PeerSyncRequestConfig,
    snap: OutgoingGraphSnapshot,
    wireEntries: List<SyncEntryRecordDto>,
    budgetEntries: List<EntryEntity>,
    unsyncedFeedIds: Set<String>,
    sentMemberships: List<CollectionEntryEntity>,
    sentGenerators: List<CollectionGeneratorEntity>,
    sentLogs: List<ConsumptionLogEntity>,
    sentComparisons: List<ComparisonEntity>,
    sentTombstones: List<TombstoneEntity>,
    byteOverflowIds: Set<String>,
    countOverflowIds: Set<String>,
): TrackedOutgoingSyncBatch {
    val effectiveWeight =
        if (config.networkTier == NetworkTier.OFFLINE) 0.0 else config.weightOverride
    val request =
        PeerSyncRequestDto(
            peerId = config.peerId,
            deviceName = config.deviceName,
            networkTier = config.networkTier.wireValue,
            weight = effectiveWeight,
            sinceSeq = config.sinceSeq.coerceAtLeast(0L),
            clientHlc = snap.clientHlc,
            entries = wireEntries,
            collectionEntries = toWireCollectionEntryDtos(sentMemberships),
            collectionGenerators = toWireGeneratorDtos(sentGenerators),
            consumptionLog = toWireConsumptionLogDtos(sentLogs),
            comparisons = toWireComparisonDtos(sentComparisons),
            tombstones = toWireTombstoneDtos(sentTombstones),
        )
    val wireStateIds = wireEntries.filter { it.state != null }.mapTo(HashSet()) { it.id }
    return TrackedOutgoingSyncBatch(
        request = request,
        sentEntryHlcById = budgetEntries.associate { it.id to it.hlc },
        sentStateHlcById =
            budgetEntries
                .filter { it.id in wireStateIds }
                .mapNotNull { e -> snap.statesById[e.id]?.let { e.id to it.hlc } }
                .toMap(),
        sentFeedSourceHlcById =
            budgetEntries
                .filter { it.id !in unsyncedFeedIds }
                .mapNotNull { e -> snap.feedSourcesById[e.id]?.let { e.id to it.hlc } }
                .toMap(),
        sentMembershipHlcByKey =
            sentMemberships.associate { Pair(it.collectionId, it.entryId) to it.hlc },
        sentGeneratorHlcById = sentGenerators.associate { it.collectionId to it.hlc },
        sentLogIds = sentLogs.mapTo(HashSet()) { it.id },
        sentComparisonIds = sentComparisons.mapTo(HashSet()) { it.id },
        sentTombstoneHlcByKey =
            sentTombstones.associate { Pair(it.entityType, it.naturalKey) to it.hlc },
        byteOverflowEntryIds = byteOverflowIds,
        countOverflowEntryIds = countOverflowIds,
    )
}

private fun toWireCollectionEntryDtos(
    rows: List<CollectionEntryEntity>
): List<SyncCollectionEntryDto> = rows.map {
    SyncCollectionEntryDto(
        collectionId = it.collectionId,
        entryId = it.entryId,
        position = it.position,
        addedAt = it.addedAt,
        hlc = it.hlc,
    )
}

private fun toWireGeneratorDtos(
    rows: List<CollectionGeneratorEntity>
): List<SyncCollectionGeneratorDto> = rows.map {
    SyncCollectionGeneratorDto(
        collectionId = it.collectionId,
        enabled = it.enabled,
        currentEntryId = it.currentEntryId,
        drawnAt = it.drawnAt,
        updatedAt = it.updatedAt,
        hlc = it.hlc,
    )
}

private fun toWireConsumptionLogDtos(
    rows: List<ConsumptionLogEntity>
): List<SyncConsumptionLogDto> = rows.map {
    SyncConsumptionLogDto(
        id = it.id,
        entryId = it.entryId,
        ts = it.ts,
        status = it.status,
        notes = it.notes,
        createdAt = it.createdAt,
        hlc = it.hlc,
    )
}

private fun toWireComparisonDtos(rows: List<ComparisonEntity>): List<SyncComparisonDto> =
    rows.mapNotNull { comp ->
        decodeComparisonOrdering(comp.orderingJson)?.let { ordering ->
            SyncComparisonDto(
                id = comp.id,
                ts = comp.ts,
                scopeCollectionId = comp.scopeCollectionId,
                ordering = ordering,
                createdAt = comp.createdAt,
                hlc = comp.hlc,
            )
        }
    }

private fun toWireTombstoneDtos(rows: List<TombstoneEntity>): List<SyncTombstoneDto> = rows.map {
    SyncTombstoneDto(
        entityType = it.entityType,
        naturalKey = it.naturalKey,
        deletedAt = it.deletedAt,
        hlc = it.hlc,
    )
}

internal fun parseDurationToSecondsLong(raw: String?): Long? {
    val clean = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val seconds =
        clean.toLongOrNull()
            ?: run {
                val parts = clean.split(':').map { it.trim().toLongOrNull() ?: INVALID_DURATION }
                when {
                    parts.any { it < 0L } -> INVALID_DURATION
                    parts.size == MM_SS_PART_COUNT -> parts[0] * SECONDS_PER_MINUTE + parts[1]
                    parts.size == HH_MM_SS_PART_COUNT ->
                        parts[0] * SECONDS_PER_HOUR + parts[1] * SECONDS_PER_MINUTE + parts[2]
                    else -> INVALID_DURATION
                }
            }
    return seconds.takeIf { it > 0L }
}

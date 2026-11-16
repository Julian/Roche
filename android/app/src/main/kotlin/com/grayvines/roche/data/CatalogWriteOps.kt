@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data

import com.grayvines.roche.data.local.CatalogEntryEntity
import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.ConsumptionLogEntity
import com.grayvines.roche.data.local.EntryIdentifierEntity
import com.grayvines.roche.data.local.FeedEntity
import com.grayvines.roche.data.local.ItemEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.SQLITE_DELETE_CHUNK_SIZE
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.model.ContentType
import java.time.Instant

internal const val COLLECTION_ENTRY_ENTITY = "collection_entry"

suspend fun CatalogStore.attachEntryIdentifiers(
    entryId: String,
    identifiers: Map<String, String>,
) {
    if (entryId.isBlank() || identifiers.isEmpty()) return
    withMutationLock { attachIdentifiersInDao(dao, entryId, identifiers) }
}

internal suspend fun attachIdentifiersInDao(
    dao: RocheDao,
    entryId: String,
    identifiers: Map<String, String>,
) {
    val resolvedId = followMergedIdInDao(dao, entryId) ?: return
    val entry = dao.getCatalogEntryById(resolvedId) ?: return
    val kind = EntryKind.fromWireValue(entry.kind)
    val rows = identifiers.mapNotNull { (rawScheme, rawVal) ->
        val scheme = normalizeIdentifierScheme(rawScheme)
        val normVal = normalizeIdentifierValue(kind, scheme, rawVal)
        if (scheme.isEmpty() || normVal.isEmpty()) {
            null
        } else {
            EntryIdentifierEntity(scheme, normVal, resolvedId)
        }
    }
    if (rows.isNotEmpty()) {
        dao.insertEntryIdentifiersIgnore(rows)
    }
}

internal suspend fun materializeFeedsForIds(
    dao: RocheDao,
    referencedIds: Set<String>,
    nowIso: String,
) {
    if (referencedIds.isEmpty()) return
    val existingEntryIds = dao.getAllCatalogEntries().map { it.id }.toHashSet()
    val missingIds = referencedIds.filter { it.isNotBlank() && it !in existingEntryIds }.toSet()
    if (missingIds.isEmpty()) return

    val feedsById = dao.getAllFeeds().associateBy { it.id }
    val itemsById =
        missingIds
            .chunked(SQLITE_DELETE_CHUNK_SIZE)
            .flatMap { chunk -> dao.getPreviewItemsByIds(chunk) }
            .associateBy { it.id }
    for (missingId in missingIds) {
        val item = itemsById[missingId]
        if (item != null) {
            materializeFeedItemInDao(
                dao = dao,
                item = item,
                feed = item.feedId?.let { feedsById[it] },
                logConsumed = false,
                markDirty = false,
                nowIso = nowIso,
            )
        } else {
            feedsById[missingId]?.let { feed ->
                materializeParentFeedInDao(
                    dao = dao,
                    feed = feed,
                    isAudio = feed.feedType.equals("podcast", ignoreCase = true),
                    nowIso = nowIso,
                )
            }
        }
    }
}

@Suppress("LongParameterList")
internal suspend fun materializeFeedItemInDao(
    dao: RocheDao,
    item: ItemEntity,
    feed: FeedEntity?,
    logConsumed: Boolean,
    markDirty: Boolean,
    nowIso: String,
): String {
    val contentType = ContentType.fromWireValue(item.contentType)
    val isAudio = contentType == ContentType.AUDIO || !item.audioUrl.isNullOrBlank()
    val needsPrivateSync = logConsumed && !isSyncablePublicUrl(item.url)
    val resolvedItemId =
        upsertEntryInDao(
            dao = dao,
            input = buildFeedItemUpsertInput(item, contentType, isAudio),
            nowIso = nowIso,
            markDirty = markDirty || needsPrivateSync,
        )
    val parentId = feed?.let {
        materializeParentFeedInDao(
            dao = dao,
            feed = it,
            isAudio = isAudio,
            nowIso = nowIso,
            markDirty = logConsumed && !isSyncablePublicUrl(it.url),
        )
    }
    if (parentId != null) {
        dao.upsertCollectionEntry(
            CollectionEntryEntity(
                collectionId = parentId,
                entryId = resolvedItemId,
                position = null,
                addedAt = nowIso,
                isLocalDirty = false,
            )
        )
    }
    if (logConsumed) {
        ensureConsumedOnceInDao(dao, resolvedItemId, nowIso)
        parentId?.let { ensureConsumedOnceInDao(dao, it, nowIso) }
    }
    return resolvedItemId
}

private suspend fun materializeParentFeedInDao(
    dao: RocheDao,
    feed: FeedEntity,
    isAudio: Boolean,
    nowIso: String,
    markDirty: Boolean = false,
): String? {
    if (feed.feedType.equals("arxiv", ignoreCase = true)) return null
    val parentKind =
        when {
            feed.feedType.equals("youtube", ignoreCase = true) -> EntryKind.CHANNEL
            isAudio || feed.feedType.equals("podcast", ignoreCase = true) -> EntryKind.PODCAST
            else -> EntryKind.PUBLICATION
        }
    val cleanFeedUrl = feed.url.trim().takeIf { it.isNotEmpty() }?.let(::canonicalizeSyncUrl)
    val idents = buildList {
        if (!cleanFeedUrl.isNullOrBlank()) {
            add(EntryIdentifierDto("feed_url", cleanFeedUrl))
        }
    }
    return upsertEntryInDao(
        dao = dao,
        input =
            UpsertCatalogEntryInput(
                id = feed.id,
                kind = parentKind,
                title = feed.title.ifBlank { feed.url },
                url = feed.siteUrl ?: feed.url,
                summary = feed.description,
                identifiers = idents,
            ),
        nowIso = nowIso,
        markDirty = markDirty,
    )
}

private suspend fun ensureConsumedOnceInDao(dao: RocheDao, entryId: String, nowIso: String) {
    val alreadyConsumed =
        dao.getConsumptionLogsForEntry(entryId).any {
            ConsumptionStatus.fromWireValue(it.status) == ConsumptionStatus.CONSUMED
        }
    if (!alreadyConsumed) {
        appendConsumptionInDao(
            dao = dao,
            entryId = entryId,
            status = ConsumptionStatus.CONSUMED,
            notes = null,
            tsIso = nowIso,
            nowIso = nowIso,
            markDirty = true,
            explicitLogId = deterministicAutoLogId(entryId),
        )
    }
}

internal suspend fun upsertEntryInDao(
    dao: RocheDao,
    input: UpsertCatalogEntryInput,
    nowIso: String,
    markDirty: Boolean,
): String {
    val cleanTitle = input.title.trim()
    require(cleanTitle.isNotEmpty()) { "Entry title cannot be empty" }
    val normalizedTitle =
        if (input.kind == EntryKind.ALBUM) stripAlbumEditionSuffix(cleanTitle) else cleanTitle
    val cleanIdentifiers = normalizeEntryIdentifiers(input)
    val resolvedId = resolveEntryIdInDao(dao, input, normalizedTitle, cleanIdentifiers)
    val existing = dao.getCatalogEntryById(resolvedId)
    val entity =
        mergeCatalogEntryEntity(
            resolvedId = resolvedId,
            normalizedTitle = normalizedTitle,
            input = input,
            existing = existing,
            nowIso = nowIso,
            markDirty = markDirty,
        )
    dao.upsertCatalogEntry(entity)
    if (cleanIdentifiers.isNotEmpty()) {
        dao.insertEntryIdentifiersIgnore(
            cleanIdentifiers.map { EntryIdentifierEntity(it.scheme, it.value, resolvedId) }
        )
    }
    if (input.kind == EntryKind.ALBUM || input.kind == EntryKind.SONG) {
        synthesizeArtistsInDao(dao, resolvedId, input.creators, nowIso, markDirty)
    }
    return resolvedId
}

private suspend fun resolveEntryIdInDao(
    dao: RocheDao,
    input: UpsertCatalogEntryInput,
    normalizedTitle: String,
    cleanIdentifiers: List<EntryIdentifierDto>,
): String {
    val explicitMerged =
        input.id?.trim()?.takeIf { it.isNotEmpty() }?.let { followMergedIdInDao(dao, it) }
    val identifierMatch =
        explicitMerged ?: findEntryIdByIdentifiers(dao, input.kind, cleanIdentifiers)
    val matchedId =
        identifierMatch
            ?: if (input.kind == EntryKind.ALBUM) {
                findAlbumByTitleArtist(dao, normalizedTitle, input.creators)
            } else {
                null
            }
    if (matchedId != null) return matchedId
    val fallbackId =
        chooseDeterministicEntryId(
            explicitId = input.id,
            kind = input.kind,
            title = normalizedTitle,
            creators = input.creators,
            identifiers = cleanIdentifiers,
        )
    return followMergedIdInDao(dao, fallbackId) ?: fallbackId
}

private suspend fun findEntryIdByIdentifiers(
    dao: RocheDao,
    expectedKind: EntryKind,
    identifiers: List<EntryIdentifierDto>,
): String? {
    for (ident in identifiers) {
        val found = dao.getEntryIdByIdentifier(ident.scheme, ident.value) ?: continue
        val resolved = followMergedIdInDao(dao, found) ?: found
        val isCompatible =
            if (ident.scheme == "url" || ident.scheme == "feed_url") {
                dao.getCatalogEntryById(resolved)?.let {
                    EntryKind.fromWireValue(it.kind) == expectedKind
                } != false
            } else {
                true
            }
        if (isCompatible) {
            return resolved
        }
    }
    return null
}

private suspend fun synthesizeArtistsInDao(
    dao: RocheDao,
    childEntryId: String,
    creators: List<String>,
    nowIso: String,
    markDirty: Boolean,
) {
    val allArtists =
        dao.getAllCatalogEntries().filter {
            it.mergedIntoId.isNullOrBlank() && EntryKind.fromWireValue(it.kind) == EntryKind.ARTIST
        }
    for (rawArtist in creators) {
        val clean = rawArtist.trim()
        if (clean.isEmpty()) continue
        val existing = allArtists.firstOrNull { it.title.equals(clean, ignoreCase = true) }
        val artistId = existing?.id ?: ensureSynthesizedArtist(dao, clean, nowIso, markDirty)
        dao.upsertCollectionEntry(
            CollectionEntryEntity(
                collectionId = artistId,
                entryId = childEntryId,
                position = null,
                addedAt = nowIso,
                isLocalDirty = markDirty,
            )
        )
    }
}

private suspend fun ensureSynthesizedArtist(
    dao: RocheDao,
    cleanArtistName: String,
    nowIso: String,
    markDirty: Boolean,
): String {
    val detId = deterministicTitleEntryId("artist", cleanArtistName.lowercase())
    val mergedTarget = followMergedIdInDao(dao, detId)
    if (mergedTarget != null) {
        return mergedTarget
    }
    dao.upsertCatalogEntry(
        CatalogEntryEntity(
            id = detId,
            kind = EntryKind.ARTIST.wireValue,
            title = cleanArtistName,
            createdAt = nowIso,
            updatedAt = nowIso,
            isLocalDirty = markDirty,
        )
    )
    return detId
}

private suspend fun findAlbumByTitleArtist(
    dao: RocheDao,
    normalizedTitle: String,
    creators: List<String>,
): String? {
    val primaryCreator = creators.map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
    return dao.getAllCatalogEntries()
        .firstOrNull { cand ->
            cand.mergedIntoId.isNullOrBlank() &&
                EntryKind.fromWireValue(cand.kind) == EntryKind.ALBUM &&
                stripAlbumEditionSuffix(cand.title).equals(normalizedTitle, ignoreCase = true) &&
                decodeCreatorsJson(cand.creatorsJson).any {
                    it.equals(primaryCreator, ignoreCase = true)
                }
        }
        ?.id
}

@Suppress("LongParameterList")
internal suspend fun appendConsumptionInDao(
    dao: RocheDao,
    entryId: String,
    status: ConsumptionStatus,
    notes: String?,
    tsIso: String,
    nowIso: String,
    markDirty: Boolean,
    explicitLogId: String? = null,
): String {
    val tsMillis = runCatching {
        Instant.parse(tsIso).toEpochMilli()
    }
        .getOrElse { System.currentTimeMillis() }
    val logId =
        explicitLogId?.trim()?.takeIf { it.isNotEmpty() }
            ?: deterministicConsumptionLogId(entryId, tsMillis, status.wireValue)
    dao.insertConsumptionLogIgnore(
        ConsumptionLogEntity(
            id = logId,
            entryId = entryId,
            ts = tsIso,
            status = status.wireValue,
            notes = notes?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = nowIso,
            isLocalDirty = markDirty,
        )
    )
    if (status == ConsumptionStatus.CONSUMED) {
        removeFromWantQueuesInDao(dao, entryId, nowIso)
        engageParentArtistsInDao(dao, entryId, tsIso, nowIso, markDirty)
    }
    return logId
}

private suspend fun engageParentArtistsInDao(
    dao: RocheDao,
    entryId: String,
    tsIso: String,
    nowIso: String,
    markDirty: Boolean,
) {
    val entry = dao.getCatalogEntryById(entryId) ?: return
    val kind = EntryKind.fromWireValue(entry.kind)
    if (kind != EntryKind.ALBUM && kind != EntryKind.SONG) return
    val creators = decodeCreatorsJson(entry.creatorsJson)
    if (creators.isNotEmpty()) {
        synthesizeArtistsInDao(dao, entryId, creators, nowIso, markDirty)
    }
    val parentIds = dao.getCollectionEntriesForEntry(entryId).map { it.collectionId }
    for (pid in parentIds) {
        val parent = dao.getCatalogEntryById(pid) ?: continue
        val isActiveArtist =
            parent.mergedIntoId.isNullOrBlank() &&
                EntryKind.fromWireValue(parent.kind) == EntryKind.ARTIST
        if (isActiveArtist) {
            engageSingleArtistInDao(dao, pid, tsIso, nowIso, markDirty)
        }
    }
}

private suspend fun engageSingleArtistInDao(
    dao: RocheDao,
    artistId: String,
    tsIso: String,
    nowIso: String,
    markDirty: Boolean,
) {
    val alreadyLogged =
        dao.getConsumptionLogsForEntry(artistId).any {
            ConsumptionStatus.fromWireValue(it.status) == ConsumptionStatus.CONSUMED
        }
    if (alreadyLogged) return
    val logId = deterministicAutoLogId(artistId)
    dao.insertConsumptionLogIgnore(
        ConsumptionLogEntity(
            id = logId,
            entryId = artistId,
            ts = tsIso,
            status = ConsumptionStatus.CONSUMED.wireValue,
            notes = null,
            createdAt = nowIso,
            isLocalDirty = markDirty,
        )
    )
}

private suspend fun removeFromWantQueuesInDao(dao: RocheDao, entryId: String, nowIso: String) {
    val specsById = dao.getAllCollectionSpecs().associateBy { it.collectionId }
    val memberships = dao.getCollectionEntriesForEntry(entryId)
    for (mem in memberships) {
        val spec = specsById[mem.collectionId] ?: continue
        val isWant =
            CollectionRole.fromWireValue(spec.role) == CollectionRole.WANT ||
                spec.collectionId == UP_NEXT_COLLECTION_ID
        if (isWant) {
            dao.deleteCollectionEntry(spec.collectionId, entryId)
            dao.upsertTombstone(
                TombstoneEntity(
                    entityType = COLLECTION_ENTRY_ENTITY,
                    naturalKey = collectionEntryNaturalKey(spec.collectionId, entryId),
                    deletedAt = nowIso,
                )
            )
        }
    }
}

internal suspend fun followMergedIdInDao(dao: RocheDao, entryId: String): String? {
    var current = entryId.trim()
    if (current.isEmpty()) return null
    val visited = HashSet<String>()
    var result: String? = null
    var keepWalking = true
    while (keepWalking && visited.add(current)) {
        val row = dao.getCatalogEntryById(current)
        if (row == null) {
            keepWalking = false
        } else {
            val next = row.mergedIntoId?.trim()?.takeIf { it.isNotEmpty() }
            if (next == null) {
                result = row.id
                keepWalking = false
            } else {
                current = next
            }
        }
    }
    return result
}

internal suspend fun ensureWantCollectionInDao(
    dao: RocheDao,
    kind: EntryKind,
    targetQueueIdOrSlug: String?,
    nowIso: String,
): CollectionSpecEntity {
    val explicit = targetQueueIdOrSlug?.trim()?.takeIf { it.isNotEmpty() }
    val rawBase =
        explicit?.removePrefix("want:")?.lowercase()?.replace('-', '_') ?: kind.systemListSlug
    val baseSlug = EntryKind.fromFlexibleAlias(rawBase)?.systemListSlug ?: rawBase
    val wantSlug = "want:$baseSlug"
    val existing =
        explicit?.let { dao.getCollectionSpecById(it) ?: dao.getCollectionSpecBySlug(it) }
            ?: dao.getCollectionSpecBySlug(wantSlug)
    if (existing != null) {
        if (dao.getCatalogEntryById(existing.collectionId) == null) {
            dao.upsertCatalogEntry(buildCollectionCatalogEntry(existing))
        }
        return existing
    }

    val id = systemCollectionUuid(wantSlug)
    val spec =
        CollectionSpecEntity(
            collectionId = id,
            title = "Want: ${kind.systemListTitle}",
            slug = wantSlug,
            mode = CollectionMode.MANUAL.wireValue,
            role = CollectionRole.WANT.wireValue,
            showInLists = false,
            createdAt = nowIso,
            updatedAt = nowIso,
            isLocalDirty = true,
        )
    dao.upsertCollectionSpec(spec)
    dao.upsertCatalogEntry(buildCollectionCatalogEntry(spec))
    dao.insertEntryIdentifierIgnore(EntryIdentifierEntity("slug", wantSlug, id))
    return spec
}

internal fun buildCollectionCatalogEntry(spec: CollectionSpecEntity): CatalogEntryEntity =
    CatalogEntryEntity(
        id = spec.collectionId,
        kind = EntryKind.COLLECTION.wireValue,
        title = spec.title,
        summary = spec.summary,
        createdAt = spec.createdAt,
        updatedAt = spec.updatedAt,
        isLocalDirty = false,
    )

@Suppress("LongParameterList")
internal suspend fun addCollectionMemberInDao(
    dao: RocheDao,
    spec: CollectionSpecEntity,
    entryId: String,
    position: Double?,
    nowIso: String,
    markDirty: Boolean,
) {
    val mode = CollectionMode.fromWireValue(spec.mode)
    require(mode == CollectionMode.MANUAL) {
        "Cannot manually modify entries of ${mode.wireValue} collection '${spec.title}'"
    }
    require(spec.collectionId != entryId) { "A collection cannot contain itself" }
    val childrenByParent =
        dao.getAllCollectionEntries().groupBy({ it.collectionId }, { it.entryId })
    require(!wouldIntroduceCycleInGraph(childrenByParent, spec.collectionId, entryId)) {
        "Adding '$entryId' to '${spec.title}' would create a collection cycle"
    }
    dao.deleteTombstone(
        COLLECTION_ENTRY_ENTITY,
        collectionEntryNaturalKey(spec.collectionId, entryId),
    )
    dao.upsertCollectionEntry(
        CollectionEntryEntity(
            collectionId = spec.collectionId,
            entryId = entryId,
            position = position,
            addedAt = nowIso,
            isLocalDirty = markDirty,
        )
    )
}

internal fun wouldIntroduceCycleInGraph(
    childrenByParent: Map<String, Collection<String>>,
    parentCollectionId: String,
    childEntryId: String,
): Boolean {
    if (parentCollectionId == childEntryId) return true
    val queue = ArrayDeque<String>()
    val visited = HashSet<String>()
    queue.add(childEntryId)
    visited.add(childEntryId)
    var hasCycle = false
    while (queue.isNotEmpty() && !hasCycle) {
        val current = queue.removeFirst()
        for (next in childrenByParent[current].orEmpty()) {
            if (next == parentCollectionId) {
                hasCycle = true
                break
            }
            if (visited.add(next)) {
                queue.add(next)
            }
        }
    }
    return hasCycle
}

private fun buildFeedItemUpsertInput(
    item: ItemEntity,
    contentType: ContentType,
    isAudio: Boolean,
): UpsertCatalogEntryInput {
    val entryKind =
        when {
            isAudio -> EntryKind.PODCAST_EPISODE
            contentType == ContentType.PDF -> EntryKind.PAPER
            contentType == ContentType.VIDEO -> EntryKind.VIDEO
            else -> EntryKind.ARTICLE
        }
    val creators = item.author?.trim()?.takeIf { it.isNotEmpty() }?.let(::listOf).orEmpty()
    val canonicalUrl = item.url.trim().takeIf { it.isNotEmpty() }?.let(::canonicalizeSyncUrl)
    val identifiers = buildList {
        if (!canonicalUrl.isNullOrBlank()) {
            add(EntryIdentifierDto("url", canonicalUrl))
        }
    }
    return UpsertCatalogEntryInput(
        id = item.id,
        kind = entryKind,
        title = item.title,
        creators = creators,
        url = item.url.takeIf { it.isNotBlank() },
        publishedAt = item.publishedAt,
        releaseYear = extractYearPrefix(item.publishedAt),
        summary = item.summary,
        thumbnailUrl = item.thumbnailUrl,
        identifiers = identifiers,
    )
}

private fun normalizeEntryIdentifiers(input: UpsertCatalogEntryInput): List<EntryIdentifierDto> {
    val explicit = input.identifiers.mapNotNull { cleanSingleIdentifier(input.kind, it) }
    val trimmedUrl = input.url?.trim()?.takeIf { it.isNotEmpty() }
    val urlIdent =
        trimmedUrl
            ?.let(::canonicalizeSyncUrl)
            ?.takeIf { it.isNotEmpty() }
            ?.let { EntryIdentifierDto("url", it) }
    val arxivIdent =
        trimmedUrl
            ?.takeIf {
                it.startsWith("http://", ignoreCase = true) ||
                    it.startsWith("https://", ignoreCase = true)
            }
            ?.let(::extractArxivIdentifier)
            ?.let { EntryIdentifierDto("arxiv", it) }
    return (explicit + listOfNotNull(arxivIdent, urlIdent)).distinct()
}

private fun cleanSingleIdentifier(
    kind: EntryKind,
    ident: EntryIdentifierDto,
): EntryIdentifierDto? {
    val scheme = normalizeIdentifierScheme(ident.scheme)
    val value = normalizeIdentifierValue(kind, scheme, ident.value)
    return if (scheme.isNotEmpty() && value.isNotEmpty()) {
        EntryIdentifierDto(scheme, value)
    } else {
        null
    }
}

@Suppress("LongParameterList")
private fun mergeCatalogEntryEntity(
    resolvedId: String,
    normalizedTitle: String,
    input: UpsertCatalogEntryInput,
    existing: CatalogEntryEntity?,
    nowIso: String,
    markDirty: Boolean,
): CatalogEntryEntity {
    val mergedCreators =
        if (input.creators.isNotEmpty()) {
            encodeCreatorsJson(input.creators)
        } else {
            existing?.creatorsJson ?: "[]"
        }
    val metadataJson =
        if (input.metadataJson != "{}" && input.metadataJson.isNotBlank()) {
            input.metadataJson
        } else {
            existing?.metadataJson ?: "{}"
        }
    return CatalogEntryEntity(
        id = resolvedId,
        kind = existing?.kind ?: input.kind.wireValue,
        title = nonBlankOrKeep(normalizedTitle, existing?.title) ?: normalizedTitle,
        creatorsJson = mergedCreators,
        url = nonBlankOrKeep(input.url, existing?.url),
        publishedAt = nonBlankOrKeep(input.publishedAt, existing?.publishedAt),
        releaseYear = input.releaseYear ?: existing?.releaseYear,
        summary = nonBlankOrKeep(input.summary, existing?.summary),
        thumbnailUrl = sanitizeHttpUrl(input.thumbnailUrl) ?: existing?.thumbnailUrl,
        metadataJson = metadataJson,
        mergedIntoId = null,
        createdAt = existing?.createdAt ?: nowIso,
        updatedAt = nowIso,
        isLocalDirty = markDirty || existing?.isLocalDirty == true,
    )
}

private fun nonBlankOrKeep(candidate: String?, fallback: String?): String? =
    candidate?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

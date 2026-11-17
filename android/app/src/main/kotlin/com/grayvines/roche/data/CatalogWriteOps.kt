@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data

import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.ConsumptionLogEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryIdentifierEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.TombstoneEntity
import java.time.Instant

internal const val COLLECTION_ENTRY_ENTITY = "collection_entry"

suspend fun CatalogStore.attachEntryIdentifiers(
    entryId: String,
    identifiers: Map<String, String>,
): Boolean {
    if (entryId.isBlank() || identifiers.isEmpty()) return false
    val nowIso = nowUtcIso()
    var attachedNew = false
    withMutationLock { attachedNew = attachIdentifiersInDao(dao, entryId, identifiers, nowIso) }
    if (attachedNew) {
        notifyLocalMutation()
    }
    return attachedNew
}

internal suspend fun attachIdentifiersInDao(
    dao: RocheDao,
    entryId: String,
    identifiers: Map<String, String>,
    nowIso: String = nowUtcIso(),
): Boolean {
    val resolvedId = followMergedIdInDao(dao, entryId) ?: return false
    val entry = dao.getEntryById(resolvedId) ?: return false
    val kind = EntryKind.fromWireValue(entry.kind)
    val existingPairs =
        dao.getIdentifiersForEntry(resolvedId).map { it.scheme to it.value }.toHashSet()
    val newRows =
        identifiers
            .mapNotNull { (rawScheme, rawVal) ->
                val scheme = normalizeIdentifierScheme(rawScheme)
                val normVal = normalizeIdentifierValue(kind, scheme, rawVal)
                if (scheme.isEmpty() || normVal.isEmpty()) {
                    null
                } else {
                    EntryIdentifierEntity(scheme, normVal, resolvedId)
                }
            }
            .distinctBy { it.scheme to it.value }
            .filter { it.scheme to it.value !in existingPairs }
    if (newRows.isEmpty()) return false
    dao.insertEntryIdentifiersIgnore(newRows)
    val attached = dao.getIdentifiersForEntry(resolvedId).size > existingPairs.size
    if (attached) {
        val hlc = nextLocalHlc(dao)
        dao.upsertEntry(entry.copy(updatedAt = nowIso, hlc = hlc, isLocalDirty = true))
    }
    return attached
}

internal suspend fun logFeedItemConsumedInDao(
    dao: RocheDao,
    itemId: String,
    nowIso: String,
): String {
    val resolvedId = followMergedIdInDao(dao, itemId) ?: itemId
    val itemEntry = dao.getEntryById(resolvedId)
    if (itemEntry != null && !isSyncablePublicUrl(itemEntry.url.orEmpty())) {
        val hlc = nextLocalHlc(dao)
        dao.upsertEntry(itemEntry.copy(updatedAt = nowIso, hlc = hlc, isLocalDirty = true))
    }
    ensureConsumedOnceInDao(dao, resolvedId, nowIso)

    val feedSourcesById = dao.getAllFeedSources().associateBy { it.collectionId }
    val parentFeedIds =
        dao.getCollectionEntriesForEntry(resolvedId)
            .map { it.collectionId }
            .filter { it in feedSourcesById }
    for (parentId in parentFeedIds) {
        val parentEntry = dao.getEntryById(parentId) ?: continue
        val parentKind = EntryKind.fromWireValue(parentEntry.kind)
        if (parentKind != EntryKind.COLLECTION) {
            val feedUrl = feedSourcesById[parentId]?.feedUrl.orEmpty()
            if (!isSyncablePublicUrl(feedUrl)) {
                val hlc = nextLocalHlc(dao)
                dao.upsertEntry(
                    parentEntry.copy(updatedAt = nowIso, hlc = hlc, isLocalDirty = true)
                )
            }
            ensureConsumedOnceInDao(dao, parentId, nowIso)
        }
    }
    return resolvedId
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
    val existing = dao.getEntryById(resolvedId)
    val hlc =
        if (markDirty) {
            nextLocalHlc(dao)
        } else {
            existing?.hlc?.takeIf { it.isNotBlank() }
                ?: HlcTimestamp.fromIsoOrEpoch(nowIso, DEFAULT_LOCAL_HLC_NODE).encode()
        }
    val entity =
        mergeEntryEntity(
            resolvedId = resolvedId,
            normalizedTitle = normalizedTitle,
            input = input,
            existing = existing,
            nowIso = nowIso,
            hlc = hlc,
            markDirty = markDirty,
        )
    dao.upsertEntry(entity)
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
                dao.getEntryById(resolved)?.let {
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
        dao.getAllEntries().filter {
            it.mergedIntoId.isNullOrBlank() && EntryKind.fromWireValue(it.kind) == EntryKind.ARTIST
        }
    for (rawArtist in creators) {
        val clean = rawArtist.trim()
        if (clean.isEmpty()) continue
        val existing = allArtists.firstOrNull { it.title.equals(clean, ignoreCase = true) }
        val artistId = existing?.id ?: ensureSynthesizedArtist(dao, clean, nowIso, markDirty)
        val hlc =
            if (markDirty) {
                nextLocalHlc(dao)
            } else {
                HlcTimestamp.fromIsoOrEpoch(nowIso, DEFAULT_LOCAL_HLC_NODE).encode()
            }
        dao.upsertCollectionEntry(
            CollectionEntryEntity(
                collectionId = artistId,
                entryId = childEntryId,
                position = null,
                addedAt = nowIso,
                hlc = hlc,
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
    val hlc =
        if (markDirty) {
            nextLocalHlc(dao)
        } else {
            HlcTimestamp.fromIsoOrEpoch(nowIso, DEFAULT_LOCAL_HLC_NODE).encode()
        }
    dao.upsertEntry(
        EntryEntity(
            id = detId,
            kind = EntryKind.ARTIST.wireValue,
            title = cleanArtistName,
            createdAt = nowIso,
            updatedAt = nowIso,
            hlc = hlc,
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
    return dao.getAllEntries()
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
    val hlc =
        if (markDirty) {
            nextLocalHlc(dao)
        } else {
            HlcTimestamp.fromIsoOrEpoch(tsIso, DEFAULT_LOCAL_HLC_NODE).encode()
        }
    dao.insertConsumptionLogIgnore(
        ConsumptionLogEntity(
            id = logId,
            entryId = entryId,
            ts = tsIso,
            status = status.wireValue,
            notes = notes?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = nowIso,
            hlc = hlc,
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
    val entry = dao.getEntryById(entryId) ?: return
    val kind = EntryKind.fromWireValue(entry.kind)
    if (kind != EntryKind.ALBUM && kind != EntryKind.SONG) return
    val creators = decodeCreatorsJson(entry.creatorsJson)
    if (creators.isNotEmpty()) {
        synthesizeArtistsInDao(dao, entryId, creators, nowIso, markDirty)
    }
    val parentIds = dao.getCollectionEntriesForEntry(entryId).map { it.collectionId }
    for (pid in parentIds) {
        val parent = dao.getEntryById(pid) ?: continue
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
    val hlc =
        if (markDirty) {
            nextLocalHlc(dao)
        } else {
            HlcTimestamp.fromIsoOrEpoch(tsIso, DEFAULT_LOCAL_HLC_NODE).encode()
        }
    dao.insertConsumptionLogIgnore(
        ConsumptionLogEntity(
            id = logId,
            entryId = artistId,
            ts = tsIso,
            status = ConsumptionStatus.CONSUMED.wireValue,
            notes = null,
            createdAt = nowIso,
            hlc = hlc,
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
            val hlc = nextLocalHlc(dao)
            dao.deleteCollectionEntry(spec.collectionId, entryId)
            dao.upsertTombstone(
                TombstoneEntity(
                    entityType = COLLECTION_ENTRY_ENTITY,
                    naturalKey = collectionEntryNaturalKey(spec.collectionId, entryId),
                    deletedAt = nowIso,
                    hlc = hlc,
                    isLocalDirty = true,
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
        val row = dao.getEntryById(current)
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
    val title = "Want: ${kind.systemListTitle}"
    val existing =
        explicit?.let { dao.getCollectionSpecById(it) ?: dao.getCollectionSpecBySlug(it) }
            ?: dao.getCollectionSpecBySlug(wantSlug)
    if (existing != null) {
        if (dao.getEntryById(existing.collectionId) == null) {
            val hlc = nextLocalHlc(dao)
            dao.upsertEntry(
                EntryEntity(
                    id = existing.collectionId,
                    kind = EntryKind.COLLECTION.wireValue,
                    title = title,
                    createdAt = nowIso,
                    updatedAt = nowIso,
                    hlc = hlc,
                    isLocalDirty = true,
                )
            )
        }
        return existing
    }

    val id = systemCollectionUuid(wantSlug)
    val hlc = nextLocalHlc(dao)
    val spec =
        CollectionSpecEntity(
            collectionId = id,
            slug = wantSlug,
            mode = CollectionMode.MANUAL.wireValue,
            role = CollectionRole.WANT.wireValue,
            showInLists = false,
        )
    dao.upsertEntry(
        EntryEntity(
            id = id,
            kind = EntryKind.COLLECTION.wireValue,
            title = title,
            createdAt = nowIso,
            updatedAt = nowIso,
            hlc = hlc,
            isLocalDirty = true,
        )
    )
    dao.upsertCollectionSpec(spec)
    dao.insertEntryIdentifierIgnore(EntryIdentifierEntity("slug", wantSlug, id))
    return spec
}

@Suppress("LongParameterList")
internal suspend fun addCollectionMemberInDao(
    dao: RocheDao,
    spec: CollectionSpecEntity,
    entryId: String,
    position: Double?,
    nowIso: String,
    markDirty: Boolean,
) {
    val specTitle = dao.getEntryById(spec.collectionId)?.title ?: spec.slug ?: spec.collectionId
    val mode = CollectionMode.fromWireValue(spec.mode)
    require(mode == CollectionMode.MANUAL) {
        "Cannot manually modify entries of ${mode.wireValue} collection '$specTitle'"
    }
    require(spec.collectionId != entryId) { "A collection cannot contain itself" }
    val childrenByParent =
        dao.getAllCollectionEntries().groupBy({ it.collectionId }, { it.entryId })
    require(!wouldIntroduceCycleInGraph(childrenByParent, spec.collectionId, entryId)) {
        "Adding '$entryId' to '$specTitle' would create a collection cycle"
    }
    dao.deleteTombstone(
        COLLECTION_ENTRY_ENTITY,
        collectionEntryNaturalKey(spec.collectionId, entryId),
    )
    val hlc =
        if (markDirty) {
            nextLocalHlc(dao)
        } else {
            HlcTimestamp.fromIsoOrEpoch(nowIso, DEFAULT_LOCAL_HLC_NODE).encode()
        }
    dao.upsertCollectionEntry(
        CollectionEntryEntity(
            collectionId = spec.collectionId,
            entryId = entryId,
            position = position,
            addedAt = nowIso,
            hlc = hlc,
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
private fun mergeEntryEntity(
    resolvedId: String,
    normalizedTitle: String,
    input: UpsertCatalogEntryInput,
    existing: EntryEntity?,
    nowIso: String,
    hlc: String,
    markDirty: Boolean,
): EntryEntity {
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
    return EntryEntity(
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
        hlc = hlc,
        isLocalDirty = markDirty || existing?.isLocalDirty == true,
    )
}

private fun nonBlankOrKeep(candidate: String?, fallback: String?): String? =
    candidate?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

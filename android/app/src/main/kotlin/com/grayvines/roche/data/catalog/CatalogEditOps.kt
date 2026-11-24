@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data.catalog

import com.grayvines.roche.core.DEFAULT_LOCAL_HLC_NODE
import com.grayvines.roche.core.PROTECTED_SYSTEM_COLLECTION_IDS
import com.grayvines.roche.core.SCHEME_ARXIV
import com.grayvines.roche.core.SCHEME_DOI
import com.grayvines.roche.core.SCHEME_ISBN
import com.grayvines.roche.core.canonicalizeSyncUrl
import com.grayvines.roche.core.decodeCreatorsJson
import com.grayvines.roche.core.encodeCreatorsJson
import com.grayvines.roche.core.extractArxivIdentifier
import com.grayvines.roche.core.extractYearPrefix
import com.grayvines.roche.core.isHttpOrHttpsUrl
import com.grayvines.roche.core.isLocalPdfUrl
import com.grayvines.roche.core.isSyncableItemUrl
import com.grayvines.roche.core.normalizeDoi
import com.grayvines.roche.core.normalizeIsbn
import com.grayvines.roche.core.nowUtcIso
import com.grayvines.roche.core.parsePublicationDateStr
import com.grayvines.roche.core.stampEntryEditedHlc
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryIdentifierEntity
import com.grayvines.roche.data.local.MAX_SQLITE_SUMMARY_CHARS
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.nextLocalHlc

internal suspend fun updateEntryInDao(
    dao: RocheDao,
    entryId: String,
    input: UpdateCatalogEntryInput,
    nowIso: String = nowUtcIso(),
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
): CatalogEntryItem {
    val resolvedId = followMergedIdInDao(dao, entryId) ?: error("Entry '$entryId' not found")
    val existing = dao.getEntryById(resolvedId) ?: error("Entry '$resolvedId' not found")
    ensureEntryIsEditable(dao, existing)

    val nextKind = resolveEditedKind(existing, input.kind)
    val nextTitle = resolveEditedTitle(existing.title, input.title)
    val nextCreators = resolveEditedCreators(existing.creatorsJson, input.creators)
    val nextUrl = resolveEditedUrl(existing.url, input.url, input.clearUrl)
    val (nextPublishedAt, nextReleaseYear) = harmonizeEditedDates(existing, input)
    val nextSummary = resolveEditedSummary(existing.summary, input.summary, input.clearSummary)
    val identPatches = buildIdentPatches(nextKind, existing.url, nextUrl, input)
    ensureNoIdentifierConflicts(dao, resolvedId, nextKind, identPatches)

    val hlc = nextLocalHlc(dao, nodeId)
    val nextMetadataJson = stampEntryEditedHlc(existing.metadataJson, hlc)
    val isSyncable = nextUrl?.let(::isSyncableItemUrl) != false
    val updatedEntity =
        existing.copy(
            kind = nextKind.wireValue,
            title = nextTitle,
            creatorsJson = encodeCreatorsJson(nextCreators),
            url = nextUrl,
            publishedAt = nextPublishedAt,
            releaseYear = nextReleaseYear,
            summary = nextSummary,
            metadataJson = nextMetadataJson,
            updatedAt = nowIso,
            hlc = hlc,
            isLocalDirty = isSyncable || existing.isLocalDirty,
        )
    dao.upsertEntry(updatedEntity)
    applyEditedIdentifiersInTx(dao, resolvedId, existing.url, nextUrl, identPatches)
    syncContentTypeAfterKindEdit(dao, resolvedId, nextKind)
    if ((nextKind == EntryKind.ALBUM || nextKind == EntryKind.SONG) && nextCreators.isNotEmpty()) {
        synthesizeArtistsInDao(dao, resolvedId, nextCreators, nowIso, isSyncable, nodeId)
    }
    val finalIdents =
        dao.getIdentifiersForEntry(resolvedId).map { EntryIdentifierDto(it.scheme, it.value) }
    return updatedEntity.toCatalogEntryItem(finalIdents)
}

private suspend fun ensureEntryIsEditable(dao: RocheDao, entry: EntryEntity) {
    val currentKind = EntryKind.fromWireValue(entry.kind)
    require(entry.id !in PROTECTED_SYSTEM_COLLECTION_IDS && currentKind != EntryKind.COLLECTION) {
        "Collections cannot be edited from the entry editor"
    }
    require(
        dao.getCollectionSpecById(entry.id) == null && dao.getFeedSourceById(entry.id) == null
    ) {
        "Feeds and collections must be edited from their dedicated settings"
    }
}

private fun resolveEditedKind(existing: EntryEntity, requested: EntryKind?): EntryKind {
    if (requested == null) return EntryKind.fromWireValue(existing.kind)
    require(requested != EntryKind.COLLECTION) { "Cannot change an entry to a collection" }
    return requested
}

private fun resolveEditedTitle(existingTitle: String, patchTitle: String?): String {
    if (patchTitle == null) return existingTitle
    val clean = patchTitle.trim()
    require(clean.isNotEmpty()) { "Title cannot be empty" }
    return clean
}

private fun resolveEditedCreators(
    existingJson: String,
    patchCreators: List<String>?,
): List<String> {
    if (patchCreators == null) return decodeCreatorsJson(existingJson)
    return patchCreators.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

private fun resolveEditedUrl(existingUrl: String?, patchUrl: String?, clearUrl: Boolean): String? {
    if (clearUrl) return null
    if (patchUrl == null) return existingUrl
    val clean = patchUrl.trim()
    if (clean.isEmpty()) return null
    require(isHttpOrHttpsUrl(clean) || isLocalPdfUrl(clean)) {
        "URL must start with http://, https://, or local-pdf://"
    }
    return clean
}

private fun resolveEditedSummary(
    existingSummary: String?,
    patchSummary: String?,
    clearSummary: Boolean,
): String? {
    if (clearSummary) return null
    if (patchSummary == null) return existingSummary
    return patchSummary.trim().takeIf { it.isNotEmpty() }?.take(MAX_SQLITE_SUMMARY_CHARS)
}

internal fun harmonizeEditedDates(
    existing: EntryEntity,
    input: UpdateCatalogEntryInput,
): Pair<String?, Int?> {
    val validatedPatchYear = input.releaseYear?.also { validateYearRange(it) }
    val parsedPatchDate = parsePatchedPublishedAt(input.publishedAt)
    return when {
        !input.clearPublishedAt && parsedPatchDate != null -> {
            reconcileExplicitDateAndYear(existing, parsedPatchDate, validatedPatchYear)
        }
        input.clearPublishedAt -> {
            null to
                resolveYearAfterDateClear(
                    existing.releaseYear,
                    validatedPatchYear,
                    input.clearReleaseYear,
                )
        }
        input.clearReleaseYear -> {
            null to null
        }
        validatedPatchYear != null -> {
            existing.publishedAt?.takeIf { extractYearPrefix(it) == validatedPatchYear } to
                validatedPatchYear
        }
        else -> {
            existing.publishedAt to
                (existing.releaseYear ?: extractYearPrefix(existing.publishedAt))
        }
    }
}

private fun reconcileExplicitDateAndYear(
    existing: EntryEntity,
    parsedPatchDate: com.grayvines.roche.core.ParsedPublicationDate,
    validatedPatchYear: Int?,
): Pair<String?, Int?> {
    val dt = parsedPatchDate.publishedAt
    if (dt == null) {
        return null to (validatedPatchYear ?: parsedPatchDate.releaseYear)
    }
    val dateYear = extractYearPrefix(dt) ?: parsedPatchDate.releaseYear
    return if (validatedPatchYear != null && dateYear != null && validatedPatchYear != dateYear) {
        val sameDateAsExisting =
            existing.publishedAt?.take(ISO_DATE_PREFIX_LEN) == dt.take(ISO_DATE_PREFIX_LEN)
        val yearChanged = existing.releaseYear != validatedPatchYear
        if (sameDateAsExisting && yearChanged) {
            null to validatedPatchYear
        } else {
            dt to dateYear
        }
    } else {
        dt to (validatedPatchYear ?: dateYear)
    }
}

private fun resolveYearAfterDateClear(
    existingYear: Int?,
    validatedPatchYear: Int?,
    clearReleaseYear: Boolean,
): Int? =
    when {
        clearReleaseYear -> null
        validatedPatchYear != null -> validatedPatchYear
        else -> existingYear
    }

private fun parsePatchedPublishedAt(raw: String?): com.grayvines.roche.core.ParsedPublicationDate? {
    val clean = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return parsePublicationDateStr(clean)
        ?: throw IllegalArgumentException("Invalid publication date: $clean")
}

private fun validateYearRange(year: Int) {
    require(year in MIN_EDIT_YEAR..MAX_EDIT_YEAR) {
        "Year must be between $MIN_EDIT_YEAR and $MAX_EDIT_YEAR"
    }
}

private data class EditedIdentPatch(
    val scheme: String,
    val value: String?,
)

private fun buildIdentPatches(
    kind: EntryKind,
    existingUrl: String?,
    nextUrl: String?,
    input: UpdateCatalogEntryInput,
): List<EditedIdentPatch> = buildList {
    parseIdentPatch(SCHEME_DOI, input.doi, input.clearDoi, ::normalizeDoi, "DOI")?.let(::add)
    parseIdentPatch(
            SCHEME_ARXIV,
            input.arxivId,
            input.clearArxivId,
            ::extractArxivIdentifier,
            "arXiv ID",
        )
        ?.let(::add)
    parseIdentPatch(SCHEME_ISBN, input.isbn, input.clearIsbn, ::normalizeIsbn, "ISBN")?.let(::add)
    if (nextUrl != null && !input.clearUrl && nextUrl != existingUrl) {
        val canonUrl = canonicalizeSyncUrl(nextUrl)
        if (canonUrl.isNotEmpty()) {
            add(EditedIdentPatch("url", normalizeIdentifierValue(kind, "url", canonUrl)))
        }
    }
}

private fun parseIdentPatch(
    scheme: String,
    raw: String?,
    clear: Boolean,
    normalizer: (String) -> String?,
    label: String,
): EditedIdentPatch? {
    if (clear) return EditedIdentPatch(scheme, null)
    val rawVal = raw ?: return null
    val clean = rawVal.trim()
    val norm =
        if (clean.isEmpty()) {
            null
        } else {
            normalizer(clean) ?: throw IllegalArgumentException("Invalid $label: $clean")
        }
    return EditedIdentPatch(scheme, norm)
}

private suspend fun ensureNoIdentifierConflicts(
    dao: RocheDao,
    entryId: String,
    kind: EntryKind,
    patches: List<EditedIdentPatch>,
) {
    for (patch in patches) {
        val value = patch.value
        val owner = value?.let { dao.getEntryIdByIdentifier(patch.scheme, it) }
        val resolvedOwner = owner?.let { followMergedIdInDao(dao, it) ?: it }
        if (value != null && resolvedOwner != null && resolvedOwner != entryId) {
            val sameKindConflict =
                patch.scheme != "url" ||
                    dao.getEntryById(resolvedOwner)?.let {
                        EntryKind.fromWireValue(it.kind) == kind
                    } != false
            require(!sameKindConflict) {
                "Another entry already has ${patch.scheme.uppercase()} '$value'"
            }
        }
    }
}

private suspend fun applyEditedIdentifiersInTx(
    dao: RocheDao,
    entryId: String,
    oldUrl: String?,
    nextUrl: String?,
    patches: List<EditedIdentPatch>,
) {
    if (oldUrl != nextUrl) {
        dao.deleteEntryIdentifiersByScheme("url", entryId)
        val cleanNext = nextUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanNext != null) {
            val canon = canonicalizeSyncUrl(cleanNext)
            if (canon.isNotEmpty()) {
                dao.insertEntryIdentifierIgnore(EntryIdentifierEntity("url", canon, entryId))
            }
            extractArxivIdentifier(cleanNext)?.let { arxiv ->
                dao.insertEntryIdentifierIgnore(EntryIdentifierEntity(SCHEME_ARXIV, arxiv, entryId))
            }
        }
    }
    for (patch in patches) {
        if (patch.scheme == "url") continue
        dao.deleteEntryIdentifiersByScheme(patch.scheme, entryId)
        patch.value?.let { v ->
            dao.insertEntryIdentifierIgnore(EntryIdentifierEntity(patch.scheme, v, entryId))
        }
    }
}

private suspend fun syncContentTypeAfterKindEdit(
    dao: RocheDao,
    entryId: String,
    nextKind: EntryKind,
) {
    val existingContent = dao.getEntryContentById(entryId)
    if (existingContent == null || existingContent.contentType == "pdf") return
    val nextContentType =
        when (nextKind) {
            EntryKind.PAPER -> if (existingContent.contentType == "article") "paper" else null
            EntryKind.ARTICLE -> if (existingContent.contentType == "paper") "article" else null
            EntryKind.VIDEO -> "video"
            EntryKind.PODCAST_EPISODE -> "audio"
            else -> null
        }
    if (nextContentType != null && existingContent.contentType != nextContentType) {
        dao.upsertEntryContent(existingContent.copy(contentType = nextContentType))
    }
}

private fun EntryEntity.toCatalogEntryItem(
    identifiers: List<EntryIdentifierDto>
): CatalogEntryItem =
    CatalogEntryItem(
        id = id,
        kind = EntryKind.fromWireValue(kind),
        title = title,
        creators = decodeCreatorsJson(creatorsJson),
        url = url,
        publishedAt = publishedAt,
        releaseYear = releaseYear,
        summary = summary,
        thumbnailUrl = thumbnailUrl,
        metadataJson = metadataJson,
        identifiers = identifiers,
    )

private const val MIN_EDIT_YEAR = 1000
private const val MAX_EDIT_YEAR = 2100
private const val ISO_DATE_PREFIX_LEN = 10

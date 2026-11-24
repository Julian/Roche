@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package com.grayvines.roche.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal const val METADATA_KEY_DISCUSSIONS = "discussions"
internal const val METADATA_KEY_EDITED_HLC = "edited_hlc"
private const val DISCUSSION_FIELD_SOURCE = "source"
private const val DISCUSSION_FIELD_URL = "url"
private const val DISCUSSION_FIELD_EXTERNAL_ID = "external_id"
private const val DISCUSSION_FIELD_LABEL = "label"

@Serializable
data class DiscussionLink(
    val source: String,
    val url: String,
    @SerialName(DISCUSSION_FIELD_EXTERNAL_ID) val externalId: String? = null,
    val label: String? = null,
) {
    val displayLabel: String
        get() = label?.trim()?.takeIf { it.isNotEmpty() } ?: externalIdentifierLabel(source)
}

internal fun parseMetadataJsonObject(rawMetadataJson: String?): JsonObject? {
    val trimmed = rawMetadataJson?.trim().orEmpty()
    if (trimmed.isEmpty() || trimmed == "{}") return null
    return runCatching { coreJson.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
}

internal fun parseDiscussionLinks(metadataJson: String?): List<DiscussionLink> {
    val root = parseMetadataJsonObject(metadataJson) ?: return emptyList()
    val rawArray = root[METADATA_KEY_DISCUSSIONS] as? JsonArray ?: return emptyList()
    val parsed = rawArray.mapNotNull(::parseSingleDiscussionLink)
    return deduplicateDiscussionLinks(parsed)
}

internal fun extractDiscussionsForEntry(
    metadataJson: String?,
    hackerNewsId: String? = null,
): List<DiscussionLink> {
    val base = parseDiscussionLinks(metadataJson)
    val hnDigits = hackerNewsId?.let(::extractHackerNewsItemId) ?: return base
    val hnUrl = "$HACKER_NEWS_ITEM_URL_PREFIX$hnDigits"
    val synthetic =
        DiscussionLink(
            source = SCHEME_HACKER_NEWS,
            url = hnUrl,
            externalId = hnDigits,
            label = externalIdentifierLabel(SCHEME_HACKER_NEWS),
        )
    return mergeDiscussionLinkLists(base, listOf(synthetic))
}

internal fun mergeDiscussionLinkLists(
    existing: List<DiscussionLink>,
    incoming: List<DiscussionLink>,
): List<DiscussionLink> {
    if (existing.isEmpty() && incoming.isEmpty()) return emptyList()
    val mergedByUrl = LinkedHashMap<String, DiscussionLink>()
    for (item in existing) {
        addOrMergeDiscussionLink(mergedByUrl, item)
    }
    for (item in incoming) {
        addOrMergeDiscussionLink(mergedByUrl, item)
    }
    return mergedByUrl.values.toList()
}

internal fun mergeDiscussionIntoMetadata(
    existingMetadataJson: String?,
    link: DiscussionLink,
): String {
    val cleanUrl =
        sanitizeHttpUrl(link.url) ?: return existingMetadataJson?.ifBlank { "{}" } ?: "{}"
    val cleanLink =
        link.copy(
            source = link.source.trim().lowercase().ifEmpty { SCHEME_HACKER_NEWS },
            url = cleanUrl,
            externalId = link.externalId?.trim()?.takeIf { it.isNotEmpty() },
            label = link.label?.trim()?.takeIf { it.isNotEmpty() },
        )
    val existingObj = parseMetadataJsonObject(existingMetadataJson) ?: JsonObject(emptyMap())
    val updatedMap = LinkedHashMap<String, JsonElement>(existingObj)
    val existingArr = existingObj[METADATA_KEY_DISCUSSIONS] as? JsonArray ?: JsonArray(emptyList())
    val incomingArr = JsonArray(listOf(discussionLinkToJsonObject(cleanLink)))
    updatedMap[METADATA_KEY_DISCUSSIONS] = mergeDiscussionsJsonArrays(existingArr, incomingArr)
    return JsonObject(updatedMap).toString()
}

internal fun extractEntryEditedHlc(metaObj: JsonObject?): String? =
    metaObj?.stringFieldOrNull(METADATA_KEY_EDITED_HLC)

internal fun extractEntryEditedHlc(metadataJson: String?): String? =
    extractEntryEditedHlc(parseMetadataJsonObject(metadataJson))

internal fun isEntryUserEdited(metadataJson: String?): Boolean =
    extractEntryEditedHlc(metadataJson) != null

internal fun stampEntryEditedHlc(metadataJson: String?, hlc: String): String {
    val cleanHlc = hlc.trim()
    val existingObj = parseMetadataJsonObject(metadataJson) ?: JsonObject(emptyMap())
    if (cleanHlc.isEmpty()) return JsonObject(existingObj).toString()
    val updated = LinkedHashMap<String, JsonElement>(existingObj)
    val prev = extractEntryEditedHlc(existingObj)
    val winner = if (prev != null && prev >= cleanHlc) prev else cleanHlc
    updated[METADATA_KEY_EDITED_HLC] = JsonPrimitive(winner)
    return JsonObject(updated).toString()
}

/**
 * CRDT metadata JSON merge matching Rust `core/src/crdt.rs::merge_entry_metadata_json`:
 * - For key `"discussions"` when both existing and incoming are JSON arrays, performs a
 *   deduplicated union by canonicalized `url` (preserving existing order first, appending unseen
 *   incoming items, and filling blank `label`/`external_id`/`source` from incoming).
 * - For key `"edited_hlc"`, always keeps the lexicographically greater non-empty HLC string
 *   regardless of [incomingWins].
 * - For all other keys, overwrites existing keys when [incomingWins] is true and backfills missing
 *   keys regardless.
 */
internal fun mergeEntryMetadataJson(
    existingJson: String,
    incomingMeta: JsonObject?,
    incomingWins: Boolean,
): String {
    val fallback = existingJson.trim().ifEmpty { "{}" }
    if (incomingMeta == null || incomingMeta.isEmpty()) return fallback
    val existingObj = parseMetadataJsonObject(fallback) ?: JsonObject(emptyMap())
    val merged = LinkedHashMap<String, JsonElement>(existingObj)
    for ((key, incomingVal) in incomingMeta) {
        mergeSingleMetadataEntry(merged, key, incomingVal, incomingWins)
    }
    return JsonObject(merged).toString()
}

private fun mergeSingleMetadataEntry(
    merged: MutableMap<String, JsonElement>,
    key: String,
    incomingVal: JsonElement,
    incomingWins: Boolean,
) {
    when {
        key == METADATA_KEY_EDITED_HLC -> {
            mergeEditedHlcKey(merged, incomingVal)
        }
        key == METADATA_KEY_DISCUSSIONS && incomingVal is JsonArray -> {
            val existingArr = merged[key] as? JsonArray ?: JsonArray(emptyList())
            val union = mergeDiscussionsJsonArrays(existingArr, incomingVal)
            if (union.isNotEmpty() || key !in merged) {
                merged[key] = union
            }
        }
        incomingWins || key !in merged -> {
            merged[key] = incomingVal
        }
    }
}

private fun mergeEditedHlcKey(
    merged: MutableMap<String, JsonElement>,
    incomingVal: JsonElement,
) {
    val incStr = (incomingVal as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    val curStr =
        (merged[METADATA_KEY_EDITED_HLC] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    if (incStr.isNotEmpty() && (curStr.isEmpty() || incStr > curStr)) {
        merged[METADATA_KEY_EDITED_HLC] = JsonPrimitive(incStr)
    }
}

internal fun mergeDiscussionsJsonArrays(existing: JsonArray, incoming: JsonArray): JsonArray {
    val mergedByCanonUrl = LinkedHashMap<String, JsonObject>()
    for (element in existing) {
        insertDiscussionJsonElement(mergedByCanonUrl, element)
    }
    for (element in incoming) {
        insertDiscussionJsonElement(mergedByCanonUrl, element)
    }
    return JsonArray(mergedByCanonUrl.values.toList())
}

private fun insertDiscussionJsonElement(
    mergedByCanonUrl: MutableMap<String, JsonObject>,
    element: JsonElement,
) {
    val obj = element as? JsonObject ?: return
    val cleanUrl = obj.stringFieldOrNull(DISCUSSION_FIELD_URL)?.let(::sanitizeHttpUrl) ?: return
    val canonUrl = canonicalizeSyncUrl(cleanUrl)
    if (canonUrl.isEmpty()) return
    val normalizedObj =
        JsonObject(
            LinkedHashMap<String, JsonElement>(obj).apply {
                put(DISCUSSION_FIELD_URL, JsonPrimitive(cleanUrl))
            }
        )
    val prev = mergedByCanonUrl[canonUrl]
    mergedByCanonUrl[canonUrl] =
        if (prev == null) normalizedObj else mergeDiscussionJsonObjects(prev, normalizedObj)
}

private fun mergeDiscussionJsonObjects(existing: JsonObject, incoming: JsonObject): JsonObject {
    val out = LinkedHashMap<String, JsonElement>(existing)
    fillBlankStringField(out, incoming, DISCUSSION_FIELD_SOURCE)
    fillBlankStringField(out, incoming, DISCUSSION_FIELD_EXTERNAL_ID)
    fillBlankStringField(out, incoming, DISCUSSION_FIELD_LABEL)
    for ((key, value) in incoming) {
        if (key !in out) {
            out[key] = value
        }
    }
    return JsonObject(out)
}

private fun fillBlankStringField(
    target: MutableMap<String, JsonElement>,
    incoming: JsonObject,
    field: String,
) {
    val currentVal = (target[field] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    val incomingVal = incoming.stringFieldOrNull(field)
    if (currentVal.isEmpty() && incomingVal != null) {
        target[field] = JsonPrimitive(incomingVal)
    }
}

private fun parseSingleDiscussionLink(element: JsonElement): DiscussionLink? {
    val obj = element as? JsonObject ?: return null
    val url = obj.stringFieldOrNull(DISCUSSION_FIELD_URL)?.let(::sanitizeHttpUrl) ?: return null
    val source = obj.stringFieldOrNull(DISCUSSION_FIELD_SOURCE)?.lowercase() ?: "discussion"
    val externalId = obj.stringFieldOrNull(DISCUSSION_FIELD_EXTERNAL_ID)
    val label = obj.stringFieldOrNull(DISCUSSION_FIELD_LABEL)
    return DiscussionLink(
        source = source,
        url = url,
        externalId = externalId,
        label = label,
    )
}

private fun deduplicateDiscussionLinks(links: List<DiscussionLink>): List<DiscussionLink> {
    if (links.size <= 1) return links
    val byCanonUrl = LinkedHashMap<String, DiscussionLink>(links.size)
    for (link in links) {
        addOrMergeDiscussionLink(byCanonUrl, link)
    }
    return byCanonUrl.values.toList()
}

private fun addOrMergeDiscussionLink(
    target: MutableMap<String, DiscussionLink>,
    link: DiscussionLink,
) {
    val cleanUrl = sanitizeHttpUrl(link.url) ?: return
    val canon = canonicalizeSyncUrl(cleanUrl)
    if (canon.isEmpty()) return
    val normalized =
        link.copy(
            source = link.source.trim().lowercase(),
            url = cleanUrl,
            externalId = link.externalId?.trim()?.takeIf { it.isNotEmpty() },
            label = link.label?.trim()?.takeIf { it.isNotEmpty() },
        )
    val prev = target[canon]
    target[canon] =
        if (prev == null) {
            normalized
        } else {
            prev.copy(
                source = prev.source.ifBlank { normalized.source },
                externalId = prev.externalId?.takeIf { it.isNotBlank() } ?: normalized.externalId,
                label = prev.label?.takeIf { it.isNotBlank() } ?: normalized.label,
            )
        }
}

private fun discussionLinkToJsonObject(link: DiscussionLink): JsonObject = buildJsonObject {
    put(DISCUSSION_FIELD_SOURCE, JsonPrimitive(link.source))
    put(DISCUSSION_FIELD_URL, JsonPrimitive(link.url))
    link.externalId
        ?.takeIf { it.isNotBlank() }
        ?.let {
            put(DISCUSSION_FIELD_EXTERNAL_ID, JsonPrimitive(it))
        }
    link.label?.takeIf { it.isNotBlank() }?.let { put(DISCUSSION_FIELD_LABEL, JsonPrimitive(it)) }
}

private fun JsonObject.stringFieldOrNull(field: String): String? = runCatching {
    this[field]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
    .getOrNull()

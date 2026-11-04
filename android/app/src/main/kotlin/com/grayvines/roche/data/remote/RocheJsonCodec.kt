package com.grayvines.roche.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val rocheJson = Json { ignoreUnknownKeys = true }

internal fun buildPairDevicePayload(code: String, deviceName: String): String = buildJsonObject {
    put("pairing_code", code)
    put("device_name", deviceName)
}
    .toString()

internal fun extractTokenFromPairResponse(raw: String): String =
    rocheJson.parseToJsonElement(raw).jsonObject.stringOrEmpty("token")

internal fun buildCreateFeedPayload(url: String, title: String? = null): String = buildJsonObject {
    put(KEY_URL, url)
    put("feed_type", inferFeedTypeWireValue(url))
    if (!title.isNullOrBlank()) {
        put(KEY_TITLE, title)
    }
}
    .toString()

internal fun buildAddItemTagPayload(tagName: String): String = buildJsonObject {
    put("name", tagName)
}
    .toString()

internal fun inferFeedTypeWireValue(url: String): String =
    when {
        url.contains("arxiv.org", ignoreCase = true) -> "arxiv"
        url.contains("youtube.com", ignoreCase = true) ||
            url.contains("youtu.be", ignoreCase = true) -> "youtube"
        else -> "rss"
    }

internal fun buildSaveItemPayload(url: String, title: String?, tags: List<String>): String =
    buildJsonObject {
        put(KEY_URL, url)
        if (!title.isNullOrBlank()) {
            put(KEY_TITLE, title)
        }
        if (tags.isNotEmpty()) {
            put(
                "tags",
                buildJsonArray { tags.forEach { tag -> add(JsonPrimitive(tag)) } },
            )
        }
    }
    .toString()

internal fun buildUpdateItemPayload(
    status: String?,
    isFavorite: Boolean?,
    readProgress: Double?,
): String = buildJsonObject {
    if (status != null) {
        put(KEY_STATUS, status)
    }
    if (isFavorite != null) {
        put(KEY_IS_FAVORITE, isFavorite)
    }
    if (readProgress != null) {
        put(KEY_READ_PROGRESS, readProgress)
    }
}
    .toString()

internal fun parseFeedsJsonArray(raw: String): List<RemoteFeedDto> {
    val root = rocheJson.parseToJsonElement(raw)
    val feedsArray =
        when (root) {
            is JsonArray -> root
            is JsonObject -> root["feeds"] as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
    return feedsArray.map { parseFeedJsonObject(it.jsonObject) }
}

internal fun parseFeedJson(raw: String): RemoteFeedDto =
    parseFeedJsonObject(rocheJson.parseToJsonElement(raw).jsonObject)

internal fun parseItemsEnvelopeJson(raw: String): List<RemoteItemDto> {
    val items =
        rocheJson.parseToJsonElement(raw).jsonObject["items"] as? JsonArray ?: return emptyList()
    return items.map { parseItemJsonObject(it.jsonObject) }
}

internal fun parseItemJson(raw: String): RemoteItemDto =
    parseItemJsonObject(rocheJson.parseToJsonElement(raw).jsonObject)

internal fun parseTagsJsonArray(raw: String): List<RemoteTagDto> {
    val root = rocheJson.parseToJsonElement(raw)
    val tagsArray =
        when (root) {
            is JsonArray -> root
            is JsonObject -> root["tags"] as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
    return tagsArray.map { element ->
        val obj = element.jsonObject
        RemoteTagDto(
            id = obj.stringOrEmpty(KEY_ID),
            name = obj.stringOrEmpty("name"),
            unreadCount = obj.intOrDefault(KEY_UNREAD_COUNT, 0),
        )
    }
}

internal fun parseProblemDetails(raw: String, statusCode: Int): Pair<String, String> = runCatching {
    val obj = rocheJson.parseToJsonElement(raw).jsonObject
    val parsedTitle = obj.nullableString(KEY_TITLE) ?: DEFAULT_ERROR_TITLE
    val parsedDetail = obj.nullableString("detail") ?: raw.ifBlank { "Request failed" }
    parsedTitle to parsedDetail
}
    .getOrElse { DEFAULT_ERROR_TITLE to raw.ifBlank { "Status $statusCode" } }

private fun parseFeedJsonObject(obj: JsonObject): RemoteFeedDto =
    RemoteFeedDto(
        id = obj.stringOrEmpty(KEY_ID),
        title = obj.stringOrEmpty(KEY_TITLE),
        url = obj.stringOrEmpty(KEY_URL),
        siteUrl = obj.nullableString("site_url"),
        feedType = obj.nullableString("feed_type") ?: "rss",
        unreadCount = obj.intOrDefault(KEY_UNREAD_COUNT, 0),
    )

private fun parseItemJsonObject(obj: JsonObject): RemoteItemDto =
    RemoteItemDto(
        id = obj.stringOrEmpty(KEY_ID),
        feedId = obj.nullableString("feed_id"),
        feedTitle = obj.nullableString("feed_title"),
        title = obj.stringOrEmpty(KEY_TITLE),
        url = obj.stringOrEmpty(KEY_URL),
        author = obj.nullableString("author"),
        summary = obj.nullableString("summary"),
        contentText = obj.nullableString("content_text"),
        contentHtml = obj.nullableString("content_html"),
        contentType = obj.nullableString("content_type") ?: "article",
        status = obj.nullableString(KEY_STATUS) ?: "unread",
        isFavorite = obj.booleanOrDefault(KEY_IS_FAVORITE, false),
        publishedAt = obj.nullableString("published_at"),
        createdAt = obj.nullableString("created_at") ?: obj.stringOrEmpty("published_at"),
        readProgress = obj.doubleOrDefault(KEY_READ_PROGRESS, 0.0),
        thumbnailMediaId = obj.nullableString("thumbnail_media_id"),
        duration = obj.parseDurationLabel(),
        tagNames = obj.parseItemTagNames(),
    )

private fun JsonObject.parseItemTagNames(): List<String> {
    val fromTagNames =
        (this["tag_names"] as? JsonArray)?.mapNotNull { elem ->
            (elem as? JsonPrimitive)?.content?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        }
    if (!fromTagNames.isNullOrEmpty()) return fromTagNames
    val fromTags =
        (this["tags"] as? JsonArray)?.mapNotNull { elem ->
            when (elem) {
                is JsonPrimitive -> elem.content.trim().lowercase().takeIf { it.isNotBlank() }
                is JsonObject -> elem.nullableString("name")?.trim()?.lowercase()
                else -> null
            }
        }
    return fromTags.orEmpty()
}

private fun JsonObject.parseDurationLabel(): String? {
    val raw = nullableString("duration") ?: return null
    val seconds = raw.toLongOrNull() ?: return raw
    val minutes = seconds / SECONDS_PER_MINUTE
    val remSeconds = seconds % SECONDS_PER_MINUTE
    return "%d:%02d".format(java.util.Locale.US, minutes, remSeconds)
}

private const val SECONDS_PER_MINUTE = 60L

private fun JsonObject.nullableString(key: String): String? {
    val element: JsonElement = this[key] ?: return null
    if (element is JsonNull) return null
    return element.jsonPrimitive.content.takeIf { it.isNotBlank() }
}

private fun JsonObject.stringOrEmpty(key: String): String = nullableString(key).orEmpty()

private fun JsonObject.intOrDefault(key: String, default: Int): Int =
    (this[key] as? JsonPrimitive)?.intOrNull ?: default

private fun JsonObject.booleanOrDefault(key: String, default: Boolean): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

private fun JsonObject.doubleOrDefault(key: String, default: Double): Double =
    (this[key] as? JsonPrimitive)?.doubleOrNull ?: default

private const val KEY_ID = "id"
private const val KEY_TITLE = "title"
private const val KEY_URL = "url"
private const val KEY_STATUS = "status"
private const val KEY_IS_FAVORITE = "is_favorite"
private const val KEY_READ_PROGRESS = "read_progress"
private const val KEY_UNREAD_COUNT = "unread_count"
private const val DEFAULT_ERROR_TITLE = "HTTP Error"

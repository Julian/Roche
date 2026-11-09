@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data.remote

import com.grayvines.roche.data.NetworkTier
import com.grayvines.roche.data.PeerNodeDto
import com.grayvines.roche.data.SyncTombstoneDto
import com.grayvines.roche.data.inferFeedTypeWireValue
import com.grayvines.roche.data.local.FeedEntity
import com.grayvines.roche.data.local.TagEntity
import com.grayvines.roche.data.settings.validateCanonicalDiscoveryUrl
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private val rocheJson = Json { ignoreUnknownKeys = true }

internal fun buildPairDevicePayload(code: String, deviceName: String): String = buildJsonObject {
    put("pairing_code", code)
    put(KEY_DEVICE_NAME, deviceName)
}
    .toString()

internal fun extractTokenFromPairResponse(raw: String): String =
    rocheJson.parseToJsonElement(raw).jsonObject.stringOrEmpty("token")

internal fun buildCreateFeedPayload(url: String, title: String? = null): String = buildJsonObject {
    put(KEY_URL, url)
    put(KEY_FEED_TYPE, inferFeedTypeWireValue(url))
    if (!title.isNullOrBlank()) {
        put(KEY_TITLE, title)
    }
}
    .toString()

internal fun buildAddItemTagPayload(tagName: String): String = buildJsonObject {
    put("name", tagName)
}
    .toString()

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

internal const val UNREAD_READ_AT_TOMBSTONE = "__unread__"

internal fun buildUpdateItemPayload(
    status: String?,
    isFavorite: Boolean?,
    readProgress: Double?,
    readAt: String? = null,
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
    if (readAt != null) {
        if (readAt == UNREAD_READ_AT_TOMBSTONE) {
            put(KEY_READ_AT, JsonNull)
        } else {
            put(KEY_READ_AT, readAt)
        }
    }
}
    .toString()

internal fun buildArchiveBatchPayload(
    itemIds: List<String>,
    feedId: String?,
    allUnread: Boolean,
): String = buildJsonObject {
    if (itemIds.isNotEmpty()) {
        put(
            "item_ids",
            buildJsonArray { itemIds.forEach { id -> add(JsonPrimitive(id)) } },
        )
    }
    if (!feedId.isNullOrBlank()) {
        put(KEY_FEED_ID, feedId)
    }
    if (allUnread) {
        put("all_unread", true)
    }
}
    .toString()

internal fun parseArchivedCountJson(raw: String): Int {
    val obj = rocheJson.parseToJsonElement(raw).jsonObject
    return obj["archived_count"]?.jsonPrimitive?.intOrNull ?: obj.intOrDefault("archived", 0)
}

internal fun buildImportOpmlPayload(opmlXml: String): String = buildJsonObject {
    put("opml", opmlXml)
}
    .toString()

internal fun parseImportedCountJson(raw: String): Int {
    val obj = rocheJson.parseToJsonElement(raw).jsonObject
    return obj["imported_count"]?.jsonPrimitive?.intOrNull ?: obj.intOrDefault("imported", 0)
}

internal fun parseFeedsJsonArray(raw: String): List<RemoteFeedDto> {
    val root = rocheJson.parseToJsonElement(raw)
    val feedsArray =
        when (root) {
            is JsonArray -> root
            is JsonObject -> root[KEY_FEEDS] as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
    return feedsArray.map { parseFeedJsonObject(it.jsonObject) }
}

internal fun parseFeedJson(raw: String): RemoteFeedDto =
    parseFeedJsonObject(rocheJson.parseToJsonElement(raw).jsonObject)

internal fun parseItemsEnvelopeJson(raw: String): List<RemoteItemDto> {
    val items =
        rocheJson.parseToJsonElement(raw).jsonObject[KEY_ITEMS] as? JsonArray ?: return emptyList()
    return items.map { parseItemJsonObject(it.jsonObject) }
}

internal fun parseItemJson(raw: String): RemoteItemDto =
    parseItemJsonObject(rocheJson.parseToJsonElement(raw).jsonObject)

internal fun parseTagsJsonArray(raw: String): List<RemoteTagDto> {
    val root = rocheJson.parseToJsonElement(raw)
    val tagsArray =
        when (root) {
            is JsonArray -> root
            is JsonObject -> root[KEY_TAGS] as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
    return tagsArray.map { element ->
        val obj = element.jsonObject
        RemoteTagDto(
            id = obj.stringOrEmpty(KEY_ID),
            name = obj.stringOrEmpty(KEY_NAME),
            unreadCount = obj.intOrDefault(KEY_UNREAD_COUNT, 0),
            color = obj.nullableString(KEY_COLOR),
        )
    }
}

internal fun buildPeerSyncRequestPayload(request: PeerSyncRequestDto): String = buildJsonObject {
    put(KEY_PEER_ID, request.peerId)
    request.deviceName?.takeIf { it.isNotBlank() }?.let { put(KEY_DEVICE_NAME, it) }
    put(KEY_NETWORK_TIER, request.networkTier)
    request.weight?.let { put(KEY_WEIGHT, it) }
    request.updatedAfter?.takeIf { it.isNotBlank() }?.let { put("updated_after", it) }
    put(
        KEY_FEEDS,
        buildJsonArray { request.feeds.forEach { add(buildPeerFeedJsonObject(it)) } },
    )
    put(
        KEY_ITEMS,
        buildJsonArray { request.items.forEach { add(buildPeerItemJsonObject(it)) } },
    )
    put(
        KEY_TAGS,
        buildJsonArray { request.tags.forEach { add(buildPeerTagJsonObject(it)) } },
    )
    put(
        KEY_TOMBSTONES,
        buildJsonArray { request.tombstones.forEach { add(buildPeerTombstoneJsonObject(it)) } },
    )
}
    .toString()

private fun buildPeerFeedJsonObject(feed: FeedEntity): JsonObject = buildJsonObject {
    put(KEY_ID, feed.id)
    put(KEY_FEED_TYPE, feed.feedType)
    put(KEY_URL, feed.url)
    feed.title.takeIf { it.isNotBlank() }?.let { put(KEY_TITLE, it) }
    feed.description?.takeIf { it.isNotBlank() }?.let { put(KEY_DESCRIPTION, it) }
    feed.language?.takeIf { it.isNotBlank() }?.let { put(KEY_LANGUAGE, it) }
    put(KEY_POLL_INTERVAL, feed.pollInterval)
    feed.lastPolled?.takeIf { it.isNotBlank() }?.let { put(KEY_LAST_POLLED, it) }
    feed.lastPolledBy?.takeIf { it.isNotBlank() }?.let { put(KEY_LAST_POLLED_BY, it) }
    feed.lastError?.takeIf { it.isNotBlank() }?.let { put(KEY_LAST_ERROR, it) }
    feed.lastErrorBy?.takeIf { it.isNotBlank() }?.let { put(KEY_LAST_ERROR_BY, it) }
    put(KEY_IS_ACTIVE, feed.isActive)
}

private fun buildPeerItemJsonObject(item: PeerSyncItemPayload): JsonObject = buildJsonObject {
    put(KEY_ID, item.id)
    item.feedId?.takeIf { it.isNotBlank() }?.let { put(KEY_FEED_ID, it) }
    item.feedUrl?.takeIf { it.isNotBlank() }?.let { put(KEY_FEED_URL, it) }
    put(KEY_TITLE, item.title)
    put(KEY_URL, item.url)
    item.author?.takeIf { it.isNotBlank() }?.let { put(KEY_AUTHOR, it) }
    item.publishedAt?.takeIf { it.isNotBlank() }?.let { put(KEY_PUBLISHED_AT, it) }
    item.createdAt?.takeIf { it.isNotBlank() }?.let { put(KEY_CREATED_AT, it) }
    item.summary?.takeIf { it.isNotBlank() }?.let { put(KEY_SUMMARY, it) }
    item.contentHtml?.takeIf { it.isNotBlank() }?.let { put(KEY_CONTENT_HTML, it) }
    item.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { put(KEY_THUMBNAIL_URL, it) }
    put(KEY_CONTENT_TYPE, item.contentType)
    put(KEY_STATUS, item.status)
    put(KEY_IS_FAVORITE, item.isFavorite)
    if (item.readAt != null) {
        put(KEY_READ_AT, item.readAt)
    } else {
        put(KEY_READ_AT, JsonNull)
    }
    put(KEY_READ_PROGRESS, item.readProgress)
    item.interactionUpdatedAt
        ?.takeIf { it.isNotBlank() }
        ?.let { put(KEY_INTERACTION_UPDATED_AT, it) }
    put(
        KEY_TAG_NAMES,
        buildJsonArray { item.tagNames.forEach { tag -> add(JsonPrimitive(tag)) } },
    )
}

private fun buildPeerTagJsonObject(tag: TagEntity): JsonObject = buildJsonObject {
    put(KEY_ID, tag.id)
    put(KEY_NAME, tag.name)
    tag.color?.takeIf { it.isNotBlank() }?.let { put(KEY_COLOR, it) }
}

private fun buildPeerTombstoneJsonObject(tomb: SyncTombstoneDto): JsonObject = buildJsonObject {
    put(KEY_ENTITY_TYPE, tomb.entityType)
    put(KEY_NATURAL_KEY, tomb.naturalKey)
    put(KEY_DELETED_AT, tomb.deletedAt)
}

internal fun parsePeerSyncResponseJson(raw: String): PeerSyncResponseDto? = runCatching {
    val obj = rocheJson.parseToJsonElement(raw).jsonObject
    val serverPeerId = obj.nullableString("server_peer_id") ?: return null
    val syncTimestamp = obj.stringOrEmpty("sync_timestamp")
    val peersArray = obj["active_peers"] as? JsonArray ?: JsonArray(emptyList())
    val activePeers = peersArray.map { el ->
        val pObj = el.jsonObject
        PeerNodeDto(
            peerId = pObj.stringOrEmpty(KEY_PEER_ID),
            deviceName = pObj.stringOrEmpty(KEY_DEVICE_NAME),
            networkTier = pObj.nullableString(KEY_NETWORK_TIER) ?: NetworkTier.WIFI.wireValue,
            weight = pObj.doubleOrDefault(KEY_WEIGHT, NetworkTier.WIFI.defaultWeight),
            lastSeenAt = pObj.stringOrEmpty("last_seen_at"),
        )
    }
    val feedsArray = obj[KEY_FEEDS] as? JsonArray ?: JsonArray(emptyList())
    val feeds = feedsArray.map { parseFeedJsonObject(it.jsonObject) }
    val itemsArray = obj[KEY_ITEMS] as? JsonArray ?: JsonArray(emptyList())
    val items = itemsArray.map { parseItemJsonObject(it.jsonObject) }
    val tagsArray = obj[KEY_TAGS] as? JsonArray ?: JsonArray(emptyList())
    val tags = tagsArray.map { el ->
        val tObj = el.jsonObject
        RemoteTagDto(
            id = tObj.stringOrEmpty(KEY_ID),
            name = tObj.stringOrEmpty(KEY_NAME),
            unreadCount = tObj.intOrDefault(KEY_UNREAD_COUNT, 0),
            color = tObj.nullableString(KEY_COLOR),
        )
    }
    val tombsArray = obj[KEY_TOMBSTONES] as? JsonArray ?: JsonArray(emptyList())
    val tombstones = tombsArray.map { el ->
        val tb = el.jsonObject
        SyncTombstoneDto(
            entityType = tb.stringOrEmpty(KEY_ENTITY_TYPE),
            naturalKey = tb.stringOrEmpty(KEY_NATURAL_KEY),
            deletedAt = tb.stringOrEmpty(KEY_DELETED_AT),
        )
    }
    PeerSyncResponseDto(
        serverPeerId = serverPeerId,
        syncTimestamp = syncTimestamp,
        activePeers = activePeers,
        feeds = feeds,
        items = items,
        tags = tags,
        tombstones = tombstones,
    )
}
    .getOrNull()

internal fun parseMediaJsonArray(raw: String): List<RemoteMediaDto> {
    val root = rocheJson.parseToJsonElement(raw)
    val mediaArray =
        when (root) {
            is JsonArray -> root
            is JsonObject -> root["media"] as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
    return mediaArray.map { element ->
        val obj = element.jsonObject
        RemoteMediaDto(
            id = obj.stringOrEmpty(KEY_ID),
            itemId = obj.stringOrEmpty("item_id"),
            mediaType = obj.nullableString("media_type") ?: "thumbnail",
            fileSize = obj.longOrNull("file_size"),
            mimeType = obj.nullableString("mime_type"),
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

internal fun parseHealthDiscoveryJson(
    raw: String,
    probedBaseUrl: String,
): DiscoveredServerDto? = runCatching {
    val obj = rocheJson.parseToJsonElement(raw).jsonObject
    val status = obj.stringOrEmpty(KEY_STATUS)
    val service = obj.nullableString("service")
    if (status != "ok" || service != "roche") {
        null
    } else {
        val cleanProbed = probedBaseUrl.trim().trimEnd('/')
        val validatedCanonical =
            obj.nullableString("canonical_url")?.let { rawCanonical ->
                validateCanonicalDiscoveryUrl(rawCanonical, cleanProbed)
            }
        DiscoveredServerDto(
            probedUrl = cleanProbed,
            canonicalUrl = validatedCanonical,
            version = obj.nullableString("version"),
        )
    }
}
    .getOrNull()

private fun parseFeedJsonObject(obj: JsonObject): RemoteFeedDto =
    RemoteFeedDto(
        id = obj.stringOrEmpty(KEY_ID),
        title = obj.stringOrEmpty(KEY_TITLE),
        url = obj.stringOrEmpty(KEY_URL),
        siteUrl = obj.nullableString("site_url"),
        feedType = obj.nullableString(KEY_FEED_TYPE) ?: "rss",
        unreadCount = obj.intOrDefault(KEY_UNREAD_COUNT, 0),
        description = obj.nullableString(KEY_DESCRIPTION),
        language = obj.nullableString(KEY_LANGUAGE),
        pollInterval = obj.intOrDefault(KEY_POLL_INTERVAL, DEFAULT_POLL_INTERVAL),
        lastPolled = obj.nullableString(KEY_LAST_POLLED),
        lastPolledBy = obj.nullableString(KEY_LAST_POLLED_BY),
        lastError = obj.nullableString(KEY_LAST_ERROR),
        lastErrorBy = obj.nullableString(KEY_LAST_ERROR_BY),
        isActive = obj.booleanOrDefault(KEY_IS_ACTIVE, true),
    )

private fun parseItemJsonObject(obj: JsonObject): RemoteItemDto =
    RemoteItemDto(
        id = obj.stringOrEmpty(KEY_ID),
        feedId = obj.nullableString(KEY_FEED_ID),
        feedTitle = obj.nullableString("feed_title"),
        title = obj.stringOrEmpty(KEY_TITLE),
        url = obj.stringOrEmpty(KEY_URL),
        author = obj.nullableString(KEY_AUTHOR),
        summary = obj.nullableString(KEY_SUMMARY),
        contentText = obj.nullableString("content_text"),
        contentHtml = obj.nullableString(KEY_CONTENT_HTML),
        contentType = obj.nullableString(KEY_CONTENT_TYPE) ?: "article",
        status = obj.nullableString(KEY_STATUS) ?: "unread",
        isFavorite = obj.booleanOrDefault(KEY_IS_FAVORITE, false),
        publishedAt = obj.nullableString(KEY_PUBLISHED_AT),
        createdAt = obj.nullableString(KEY_CREATED_AT) ?: obj.stringOrEmpty(KEY_PUBLISHED_AT),
        readProgress = obj.doubleOrDefault(KEY_READ_PROGRESS, 0.0),
        thumbnailMediaId = obj.nullableString("thumbnail_media_id"),
        duration = obj.parseDurationLabel(),
        tagNames = obj.parseItemTagNames(),
        readAt = obj.parseItemReadAt(),
        thumbnailUrl = obj.nullableString(KEY_THUMBNAIL_URL),
        feedUrl = obj.nullableString(KEY_FEED_URL),
        interactionUpdatedAt = obj.nullableString(KEY_INTERACTION_UPDATED_AT),
    )

private fun JsonObject.parseItemReadAt(): String? =
    nullableString(KEY_READ_AT)
        ?: if (booleanOrDefault("is_read", false) || nullableString(KEY_STATUS) == "archived") {
            nullableString("updated_at")
                ?: nullableString(KEY_CREATED_AT)
                ?: nullableString(KEY_PUBLISHED_AT)
                ?: "1970-01-01T00:00:00Z"
        } else {
            null
        }

private fun JsonObject.parseItemTagNames(): List<String> {
    val fromTagNames =
        (this[KEY_TAG_NAMES] as? JsonArray)?.mapNotNull { elem ->
            (elem as? JsonPrimitive)?.content?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        }
    if (!fromTagNames.isNullOrEmpty()) return fromTagNames
    val fromTags =
        (this[KEY_TAGS] as? JsonArray)?.mapNotNull { elem ->
            when (elem) {
                is JsonPrimitive -> elem.content.trim().lowercase().takeIf { it.isNotBlank() }
                is JsonObject -> elem.nullableString(KEY_NAME)?.trim()?.lowercase()
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

private fun JsonObject.longOrNull(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private const val KEY_ID = "id"
private const val KEY_TITLE = "title"
private const val KEY_URL = "url"
private const val KEY_STATUS = "status"
private const val KEY_IS_FAVORITE = "is_favorite"
private const val KEY_READ_PROGRESS = "read_progress"
private const val KEY_READ_AT = "read_at"
private const val KEY_CREATED_AT = "created_at"
private const val KEY_PUBLISHED_AT = "published_at"
private const val KEY_UNREAD_COUNT = "unread_count"
private const val KEY_PEER_ID = "peer_id"
private const val KEY_DEVICE_NAME = "device_name"
private const val KEY_NETWORK_TIER = "network_tier"
private const val KEY_WEIGHT = "weight"
private const val KEY_FEEDS = "feeds"
private const val KEY_ITEMS = "items"
private const val KEY_TAGS = "tags"
private const val KEY_TOMBSTONES = "tombstones"
private const val KEY_FEED_TYPE = "feed_type"
private const val KEY_FEED_ID = "feed_id"
private const val KEY_FEED_URL = "feed_url"
private const val KEY_NAME = "name"
private const val KEY_COLOR = "color"
private const val KEY_DESCRIPTION = "description"
private const val KEY_LANGUAGE = "language"
private const val KEY_POLL_INTERVAL = "poll_interval"
private const val KEY_LAST_POLLED = "last_polled"
private const val KEY_LAST_POLLED_BY = "last_polled_by"
private const val KEY_LAST_ERROR = "last_error"
private const val KEY_LAST_ERROR_BY = "last_error_by"
private const val KEY_IS_ACTIVE = "is_active"
private const val KEY_AUTHOR = "author"
private const val KEY_SUMMARY = "summary"
private const val KEY_CONTENT_HTML = "content_html"
private const val KEY_THUMBNAIL_URL = "thumbnail_url"
private const val KEY_CONTENT_TYPE = "content_type"
private const val KEY_INTERACTION_UPDATED_AT = "interaction_updated_at"
private const val KEY_TAG_NAMES = "tag_names"
private const val KEY_ENTITY_TYPE = "entity_type"
private const val KEY_NATURAL_KEY = "natural_key"
private const val KEY_DELETED_AT = "deleted_at"
private const val DEFAULT_POLL_INTERVAL = 30
private const val DEFAULT_ERROR_TITLE = "HTTP Error"

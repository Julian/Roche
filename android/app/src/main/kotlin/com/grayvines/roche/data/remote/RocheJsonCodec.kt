@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data.remote

import com.grayvines.roche.data.NetworkTier
import com.grayvines.roche.data.PeerNodeDto
import com.grayvines.roche.data.SyncTombstoneDto
import com.grayvines.roche.data.inferFeedTypeWireValue
import com.grayvines.roche.data.local.FeedEntity
import com.grayvines.roche.data.local.TagEntity
import com.grayvines.roche.data.settings.validateCanonicalDiscoveryUrl
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal val rocheJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

internal const val UNREAD_READ_AT_TOMBSTONE = "__unread__"
internal const val DEFAULT_POLL_INTERVAL = 30
internal const val DEFAULT_ERROR_TITLE = "HTTP Error"
internal const val DEFAULT_FEED_TYPE = "rss"
internal const val DEFAULT_CONTENT_TYPE = "article"
internal const val DEFAULT_ITEM_STATUS = "unread"
internal const val ARCHIVED_ITEM_STATUS = "archived"
internal const val DEFAULT_MEDIA_TYPE = "thumbnail"
internal const val EPOCH_ISO_TIMESTAMP = "1970-01-01T00:00:00Z"
internal const val SECONDS_PER_MINUTE = 60L

@Serializable
internal data class PairDeviceWireRequest(
    @SerialName("pairing_code") val pairingCode: String,
    @SerialName("device_name") val deviceName: String,
)

@Serializable internal data class PairDeviceWireResponse(val token: String? = null)

@Serializable
internal data class CreateFeedWireRequest(
    val url: String,
    @SerialName("feed_type") val feedType: String,
    val title: String? = null,
)

@Serializable internal data class AddItemTagWireRequest(val name: String)

@Serializable
internal data class SaveItemWireRequest(
    val url: String,
    val title: String? = null,
    val tags: List<String>? = null,
)

@Serializable
internal data class UpdateFeedWireRequest(
    val title: String? = null,
    @SerialName("poll_interval") val pollInterval: Int? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
)

@Serializable
internal data class ArchiveBatchWireRequest(
    @SerialName("item_ids") val itemIds: List<String>? = null,
    @SerialName("feed_id") val feedId: String? = null,
    @SerialName("all_unread") val allUnread: Boolean? = null,
)

@Serializable
internal data class ArchiveBatchWireResponse(
    @SerialName("archived_count") val archivedCount: Int? = null,
    val archived: Int = 0,
)

@Serializable internal data class ImportOpmlWireRequest(val opml: String)

@Serializable
internal data class ImportOpmlWireResponse(
    @SerialName("imported_count") val importedCount: Int? = null,
    val imported: Int = 0,
)

@Serializable
internal data class WireFeedDto(
    val id: String = "",
    val title: String = "",
    val url: String = "",
    @SerialName("site_url") val siteUrl: String? = null,
    @SerialName("feed_type") val feedType: String? = null,
    @SerialName("unread_count") val unreadCount: Int = 0,
    val description: String? = null,
    val language: String? = null,
    @SerialName("poll_interval") val pollInterval: Int = DEFAULT_POLL_INTERVAL,
    @SerialName("last_polled") val lastPolled: String? = null,
    @SerialName("last_polled_by") val lastPolledBy: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("last_error_by") val lastErrorBy: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
) {
    fun toRemoteFeedDto(): RemoteFeedDto =
        RemoteFeedDto(
            id = id.nullIfBlank().orEmpty(),
            title = title.nullIfBlank().orEmpty(),
            url = url.nullIfBlank().orEmpty(),
            siteUrl = siteUrl.nullIfBlank(),
            feedType = feedType.nullIfBlank() ?: DEFAULT_FEED_TYPE,
            unreadCount = unreadCount,
            description = description.nullIfBlank(),
            language = language.nullIfBlank(),
            pollInterval = pollInterval,
            lastPolled = lastPolled.nullIfBlank(),
            lastPolledBy = lastPolledBy.nullIfBlank(),
            lastError = lastError.nullIfBlank(),
            lastErrorBy = lastErrorBy.nullIfBlank(),
            isActive = isActive,
        )
}

@Serializable
internal data class FeedsEnvelopeWireResponse(val feeds: List<WireFeedDto> = emptyList())

@Serializable
internal data class WireItemDto(
    val id: String = "",
    @SerialName("feed_id") val feedId: String? = null,
    @SerialName("feed_title") val feedTitle: String? = null,
    val title: String = "",
    val url: String = "",
    val author: String? = null,
    val summary: String? = null,
    @SerialName("content_text") val contentText: String? = null,
    @SerialName("content_html") val contentHtml: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    val status: String? = null,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("is_read") val isRead: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("read_progress") val readProgress: Double = 0.0,
    @SerialName("thumbnail_media_id") val thumbnailMediaId: String? = null,
    val duration: JsonPrimitive? = null,
    @SerialName("tag_names") val tagNames: List<String?> = emptyList(),
    val tags: List<JsonElement> = emptyList(),
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("feed_url") val feedUrl: String? = null,
    @SerialName("interaction_updated_at") val interactionUpdatedAt: String? = null,
    @SerialName("audio_url") val audioUrl: String? = null,
) {
    fun toRemoteItemDto(): RemoteItemDto {
        val cleanPublishedAt = publishedAt.nullIfBlank()
        val cleanCreatedAt = createdAt.nullIfBlank() ?: cleanPublishedAt.orEmpty()
        val cleanStatus = status.nullIfBlank() ?: DEFAULT_ITEM_STATUS
        return RemoteItemDto(
            id = id.nullIfBlank().orEmpty(),
            feedId = feedId.nullIfBlank(),
            feedTitle = feedTitle.nullIfBlank(),
            title = title.nullIfBlank().orEmpty(),
            url = url.nullIfBlank().orEmpty(),
            author = author.nullIfBlank(),
            summary = summary.nullIfBlank(),
            contentText = contentText.nullIfBlank(),
            contentHtml = contentHtml.nullIfBlank(),
            contentType = contentType.nullIfBlank() ?: DEFAULT_CONTENT_TYPE,
            status = cleanStatus,
            isFavorite = isFavorite,
            publishedAt = cleanPublishedAt,
            createdAt = cleanCreatedAt,
            readProgress = readProgress,
            thumbnailMediaId = thumbnailMediaId.nullIfBlank(),
            duration = formatWireDuration(duration),
            tagNames = resolveWireItemTags(),
            readAt = resolveWireItemReadAt(cleanStatus, cleanPublishedAt),
            thumbnailUrl = thumbnailUrl.nullIfBlank(),
            feedUrl = feedUrl.nullIfBlank(),
            interactionUpdatedAt = interactionUpdatedAt.nullIfBlank(),
            audioUrl = audioUrl.nullIfBlank(),
        )
    }
}

internal fun WireItemDto.resolveWireItemReadAt(
    cleanStatus: String,
    cleanPublishedAt: String?,
): String? =
    readAt.nullIfBlank()
        ?: if (isRead || cleanStatus == ARCHIVED_ITEM_STATUS) {
            updatedAt.nullIfBlank()
                ?: createdAt.nullIfBlank()
                ?: cleanPublishedAt
                ?: EPOCH_ISO_TIMESTAMP
        } else {
            null
        }

internal fun WireItemDto.resolveWireItemTags(): List<String> {
    val directNames = tagNames.mapNotNull { it?.trim()?.lowercase()?.nullIfBlank() }
    if (directNames.isNotEmpty()) return directNames
    return tags.mapNotNull { elem ->
        when (elem) {
            is JsonPrimitive -> elem.contentOrNull?.trim()?.lowercase()?.nullIfBlank()
            is JsonObject ->
                (elem["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.nullIfBlank()
            else -> null
        }
    }
}

@Serializable
internal data class ItemsEnvelopeWireResponse(val items: List<WireItemDto> = emptyList())

@Serializable
internal data class WireTagDto(
    val id: String = "",
    val name: String = "",
    @SerialName("unread_count") val unreadCount: Int = 0,
    val color: String? = null,
) {
    fun toRemoteTagDto(): RemoteTagDto =
        RemoteTagDto(
            id = id.nullIfBlank().orEmpty(),
            name = name.nullIfBlank().orEmpty(),
            unreadCount = unreadCount,
            color = color.nullIfBlank(),
        )
}

@Serializable internal data class TagsEnvelopeWireResponse(val tags: List<WireTagDto> = emptyList())

@Serializable
internal data class WireMediaDto(
    val id: String = "",
    @SerialName("item_id") val itemId: String = "",
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("mime_type") val mimeType: String? = null,
) {
    fun toRemoteMediaDto(): RemoteMediaDto =
        RemoteMediaDto(
            id = id.nullIfBlank().orEmpty(),
            itemId = itemId.nullIfBlank().orEmpty(),
            mediaType = mediaType.nullIfBlank() ?: DEFAULT_MEDIA_TYPE,
            fileSize = fileSize,
            mimeType = mimeType.nullIfBlank(),
        )
}

@Serializable
internal data class MediaEnvelopeWireResponse(val media: List<WireMediaDto> = emptyList())

@Serializable
internal data class ProblemDetailsWireResponse(
    val title: String? = null,
    val detail: String? = null,
)

@Serializable
internal data class HealthDiscoveryWireResponse(
    val status: String = "",
    val service: String? = null,
    @SerialName("canonical_url") val canonicalUrl: String? = null,
    val version: String? = null,
)

@Serializable
internal data class PeerSyncWireFeed(
    val id: String,
    @SerialName("feed_type") val feedType: String,
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val language: String? = null,
    @SerialName("poll_interval") val pollInterval: Int,
    @SerialName("last_polled") val lastPolled: String? = null,
    @SerialName("last_polled_by") val lastPolledBy: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("last_error_by") val lastErrorBy: String? = null,
    @SerialName("is_active") val isActive: Boolean,
)

@Serializable
internal data class PeerSyncWireItem(
    val id: String,
    @SerialName("feed_id") val feedId: String? = null,
    @SerialName("feed_url") val feedUrl: String? = null,
    val title: String,
    val url: String,
    val author: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val summary: String? = null,
    @SerialName("content_html") val contentHtml: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("audio_url") val audioUrl: String? = null,
    val duration: Long? = null,
    @SerialName("content_type") val contentType: String,
    val status: String,
    @SerialName("is_favorite") val isFavorite: Boolean,
    @SerialName("read_at") val readAt: JsonElement,
    @SerialName("read_progress") val readProgress: Double,
    @SerialName("interaction_updated_at") val interactionUpdatedAt: String? = null,
    @SerialName("tag_names") val tagNames: List<String>,
)

@Serializable
internal data class PeerSyncWireTag(
    val id: String,
    val name: String,
    val color: String? = null,
)

@Serializable
internal data class PeerSyncWireTombstone(
    @SerialName("entity_type") val entityType: String,
    @SerialName("natural_key") val naturalKey: String,
    @SerialName("deleted_at") val deletedAt: String,
)

@Serializable
internal data class PeerSyncWireRequest(
    @SerialName("peer_id") val peerId: String,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("network_tier") val networkTier: String,
    val weight: Double? = null,
    @SerialName("updated_after") val updatedAfter: String? = null,
    val feeds: List<PeerSyncWireFeed>,
    val items: List<PeerSyncWireItem>,
    val tags: List<PeerSyncWireTag>,
    val tombstones: List<PeerSyncWireTombstone>,
)

@Serializable
internal data class WirePeerNodeDto(
    @SerialName("peer_id") val peerId: String = "",
    @SerialName("device_name") val deviceName: String = "",
    @SerialName("network_tier") val networkTier: String? = null,
    val weight: Double = NetworkTier.WIFI.defaultWeight,
    @SerialName("last_seen_at") val lastSeenAt: String = "",
) {
    fun toPeerNodeDto(): PeerNodeDto =
        PeerNodeDto(
            peerId = peerId.nullIfBlank().orEmpty(),
            deviceName = deviceName.nullIfBlank().orEmpty(),
            networkTier = networkTier.nullIfBlank() ?: NetworkTier.WIFI.wireValue,
            weight = weight,
            lastSeenAt = lastSeenAt.nullIfBlank().orEmpty(),
        )
}

@Serializable
internal data class WireTombstoneResponseDto(
    @SerialName("entity_type") val entityType: String = "",
    @SerialName("natural_key") val naturalKey: String = "",
    @SerialName("deleted_at") val deletedAt: String = "",
) {
    fun toSyncTombstoneDto(): SyncTombstoneDto =
        SyncTombstoneDto(
            entityType = entityType.nullIfBlank().orEmpty(),
            naturalKey = naturalKey.nullIfBlank().orEmpty(),
            deletedAt = deletedAt.nullIfBlank().orEmpty(),
        )
}

@Serializable
internal data class PeerSyncWireResponse(
    @SerialName("server_peer_id") val serverPeerId: String? = null,
    @SerialName("sync_timestamp") val syncTimestamp: String = "",
    @SerialName("active_peers") val activePeers: List<WirePeerNodeDto> = emptyList(),
    val feeds: List<WireFeedDto> = emptyList(),
    val items: List<WireItemDto> = emptyList(),
    val tags: List<WireTagDto> = emptyList(),
    val tombstones: List<WireTombstoneResponseDto> = emptyList(),
)

internal fun buildPairDevicePayload(code: String, deviceName: String): String =
    rocheJson.encodeToString(
        PairDeviceWireRequest.serializer(),
        PairDeviceWireRequest(pairingCode = code, deviceName = deviceName),
    )

internal fun extractTokenFromPairResponse(raw: String): String =
    rocheJson
        .decodeFromString(PairDeviceWireResponse.serializer(), raw)
        .token
        .nullIfBlank()
        .orEmpty()

internal fun buildCreateFeedPayload(url: String, title: String? = null): String =
    rocheJson.encodeToString(
        CreateFeedWireRequest.serializer(),
        CreateFeedWireRequest(
            url = url,
            feedType = inferFeedTypeWireValue(url),
            title = title.nullIfBlank(),
        ),
    )

internal fun buildAddItemTagPayload(tagName: String): String =
    rocheJson.encodeToString(
        AddItemTagWireRequest.serializer(),
        AddItemTagWireRequest(name = tagName),
    )

internal fun buildSaveItemPayload(url: String, title: String?, tags: List<String>): String =
    rocheJson.encodeToString(
        SaveItemWireRequest.serializer(),
        SaveItemWireRequest(
            url = url,
            title = title.nullIfBlank(),
            tags = tags.takeIf { it.isNotEmpty() },
        ),
    )

internal fun buildUpdateItemPayload(
    status: String?,
    isFavorite: Boolean?,
    readProgress: Double?,
    readAt: String? = null,
): String = buildJsonObject {
    if (status != null) {
        put("status", status)
    }
    if (isFavorite != null) {
        put("is_favorite", isFavorite)
    }
    if (readProgress != null) {
        put("read_progress", readProgress)
    }
    if (readAt != null) {
        val wireValue = if (readAt == UNREAD_READ_AT_TOMBSTONE) JsonNull else JsonPrimitive(readAt)
        put("read_at", wireValue)
    }
}
    .toString()

internal fun buildUpdateFeedPayload(
    title: String? = null,
    pollInterval: Int? = null,
    isActive: Boolean? = null,
): String =
    rocheJson.encodeToString(
        UpdateFeedWireRequest.serializer(),
        UpdateFeedWireRequest(title = title, pollInterval = pollInterval, isActive = isActive),
    )

internal fun buildArchiveBatchPayload(
    itemIds: List<String>,
    feedId: String?,
    allUnread: Boolean,
): String =
    rocheJson.encodeToString(
        ArchiveBatchWireRequest.serializer(),
        ArchiveBatchWireRequest(
            itemIds = itemIds.takeIf { it.isNotEmpty() },
            feedId = feedId.nullIfBlank(),
            allUnread = true.takeIf { allUnread },
        ),
    )

internal fun parseArchivedCountJson(raw: String): Int {
    val parsed = rocheJson.decodeFromString(ArchiveBatchWireResponse.serializer(), raw)
    return parsed.archivedCount ?: parsed.archived
}

internal fun buildImportOpmlPayload(opmlXml: String): String =
    rocheJson.encodeToString(
        ImportOpmlWireRequest.serializer(),
        ImportOpmlWireRequest(opml = opmlXml),
    )

internal fun parseImportedCountJson(raw: String): Int {
    val parsed = rocheJson.decodeFromString(ImportOpmlWireResponse.serializer(), raw)
    return parsed.importedCount ?: parsed.imported
}

internal fun parseFeedsJsonArray(raw: String): List<RemoteFeedDto> {
    val trimmed = raw.trimStart()
    val wireFeeds =
        when {
            trimmed.startsWith('[') ->
                rocheJson.decodeFromString(ListSerializer(WireFeedDto.serializer()), raw)
            trimmed.startsWith('{') ->
                rocheJson.decodeFromString(FeedsEnvelopeWireResponse.serializer(), raw).feeds
            else -> emptyList()
        }
    return wireFeeds.map { it.toRemoteFeedDto() }.filter { it.id.isNotBlank() }
}

internal fun parseFeedJson(raw: String): RemoteFeedDto =
    rocheJson.decodeFromString(WireFeedDto.serializer(), raw).toRemoteFeedDto()

internal fun parseItemsEnvelopeJson(raw: String): List<RemoteItemDto> {
    val trimmed = raw.trimStart()
    val wireItems =
        when {
            trimmed.startsWith('[') ->
                rocheJson.decodeFromString(ListSerializer(WireItemDto.serializer()), raw)
            trimmed.startsWith('{') ->
                rocheJson.decodeFromString(ItemsEnvelopeWireResponse.serializer(), raw).items
            else -> emptyList()
        }
    return wireItems.map { it.toRemoteItemDto() }.filter { it.id.isNotBlank() }
}

internal fun parseItemJson(raw: String): RemoteItemDto =
    rocheJson.decodeFromString(WireItemDto.serializer(), raw).toRemoteItemDto()

internal fun parseTagsJsonArray(raw: String): List<RemoteTagDto> {
    val trimmed = raw.trimStart()
    val wireTags =
        when {
            trimmed.startsWith('[') ->
                rocheJson.decodeFromString(ListSerializer(WireTagDto.serializer()), raw)
            trimmed.startsWith('{') ->
                rocheJson.decodeFromString(TagsEnvelopeWireResponse.serializer(), raw).tags
            else -> emptyList()
        }
    return wireTags.map { it.toRemoteTagDto() }.filter { it.id.isNotBlank() }
}

internal fun buildPeerSyncRequestPayload(request: PeerSyncRequestDto): String =
    rocheJson.encodeToString(
        PeerSyncWireRequest.serializer(),
        PeerSyncWireRequest(
            peerId = request.peerId,
            deviceName = request.deviceName.nullIfBlank(),
            networkTier = request.networkTier,
            weight = request.weight,
            updatedAfter = request.updatedAfter.nullIfBlank(),
            feeds = request.feeds.map { toPeerWireFeed(it) },
            items = request.items.map { toPeerWireItem(it) },
            tags = request.tags.map { toPeerWireTag(it) },
            tombstones = request.tombstones.map { toPeerWireTombstone(it) },
        ),
    )

internal fun toPeerWireFeed(feed: FeedEntity): PeerSyncWireFeed =
    PeerSyncWireFeed(
        id = feed.id,
        feedType =
            if (feed.feedType.equals("podcast", ignoreCase = true)) {
                DEFAULT_FEED_TYPE
            } else {
                feed.feedType
            },
        url = feed.url,
        title = feed.title.nullIfBlank(),
        description = feed.description.nullIfBlank(),
        language = feed.language.nullIfBlank(),
        pollInterval = feed.pollInterval,
        lastPolled = feed.lastPolled.nullIfBlank(),
        lastPolledBy = feed.lastPolledBy.nullIfBlank(),
        lastError = feed.lastError.nullIfBlank(),
        lastErrorBy = feed.lastErrorBy.nullIfBlank(),
        isActive = feed.isActive,
    )

internal fun toPeerWireItem(item: PeerSyncItemPayload): PeerSyncWireItem =
    PeerSyncWireItem(
        id = item.id,
        feedId = item.feedId.nullIfBlank(),
        feedUrl = item.feedUrl.nullIfBlank(),
        title = item.title,
        url = item.url,
        author = item.author.nullIfBlank(),
        publishedAt = item.publishedAt.nullIfBlank(),
        createdAt = item.createdAt.nullIfBlank(),
        summary = item.summary.nullIfBlank(),
        contentHtml = item.contentHtml.nullIfBlank(),
        thumbnailUrl = item.thumbnailUrl.nullIfBlank(),
        audioUrl = item.audioUrl.nullIfBlank(),
        duration = item.duration?.takeIf { it > 0L },
        contentType = item.contentType,
        status = item.status,
        isFavorite = item.isFavorite,
        readAt = item.readAt?.let { JsonPrimitive(it) } ?: JsonNull,
        readProgress = item.readProgress,
        interactionUpdatedAt = item.interactionUpdatedAt.nullIfBlank(),
        tagNames = item.tagNames,
    )

internal fun toPeerWireTag(tag: TagEntity): PeerSyncWireTag =
    PeerSyncWireTag(
        id = tag.id,
        name = tag.name,
        color = tag.color.nullIfBlank(),
    )

internal fun toPeerWireTombstone(tomb: SyncTombstoneDto): PeerSyncWireTombstone =
    PeerSyncWireTombstone(
        entityType = tomb.entityType,
        naturalKey = tomb.naturalKey,
        deletedAt = tomb.deletedAt,
    )

internal fun parsePeerSyncResponseJson(raw: String): PeerSyncResponseDto? = runCatching {
    val wire = rocheJson.decodeFromString(PeerSyncWireResponse.serializer(), raw)
    wire.serverPeerId.nullIfBlank()?.let { serverPeerId ->
        PeerSyncResponseDto(
            serverPeerId = serverPeerId,
            syncTimestamp = wire.syncTimestamp.nullIfBlank().orEmpty(),
            activePeers =
                wire.activePeers.map { it.toPeerNodeDto() }.filter { it.peerId.isNotBlank() },
            feeds = wire.feeds.map { it.toRemoteFeedDto() }.filter { it.id.isNotBlank() },
            items = wire.items.map { it.toRemoteItemDto() }.filter { it.id.isNotBlank() },
            tags = wire.tags.map { it.toRemoteTagDto() }.filter { it.id.isNotBlank() },
            tombstones =
                wire.tombstones
                    .map { it.toSyncTombstoneDto() }
                    .filter { it.entityType.isNotBlank() && it.naturalKey.isNotBlank() },
        )
    }
}
    .getOrNull()

internal fun parseMediaJsonArray(raw: String): List<RemoteMediaDto> {
    val trimmed = raw.trimStart()
    val wireMedia =
        when {
            trimmed.startsWith('[') ->
                rocheJson.decodeFromString(ListSerializer(WireMediaDto.serializer()), raw)
            trimmed.startsWith('{') ->
                rocheJson.decodeFromString(MediaEnvelopeWireResponse.serializer(), raw).media
            else -> emptyList()
        }
    return wireMedia.map { it.toRemoteMediaDto() }.filter { it.id.isNotBlank() }
}

internal fun parseProblemDetails(raw: String, statusCode: Int): Pair<String, String> = runCatching {
    val parsed = rocheJson.decodeFromString(ProblemDetailsWireResponse.serializer(), raw)
    val parsedTitle = parsed.title.nullIfBlank() ?: DEFAULT_ERROR_TITLE
    val parsedDetail = parsed.detail.nullIfBlank() ?: raw.ifBlank { "Request failed" }
    parsedTitle to parsedDetail
}
    .getOrElse { DEFAULT_ERROR_TITLE to raw.ifBlank { "Status $statusCode" } }

internal fun parseHealthDiscoveryJson(
    raw: String,
    probedBaseUrl: String,
): DiscoveredServerDto? = runCatching {
    val parsed = rocheJson.decodeFromString(HealthDiscoveryWireResponse.serializer(), raw)
    if (parsed.status != "ok" || parsed.service.nullIfBlank() != "roche") {
        null
    } else {
        val cleanProbed = probedBaseUrl.trim().trimEnd('/')
        val validatedCanonical =
            parsed.canonicalUrl.nullIfBlank()?.let { rawCanonical ->
                validateCanonicalDiscoveryUrl(rawCanonical, cleanProbed)
            }
        DiscoveredServerDto(
            probedUrl = cleanProbed,
            canonicalUrl = validatedCanonical,
            version = parsed.version.nullIfBlank(),
        )
    }
}
    .getOrNull()

internal fun formatWireDuration(duration: JsonPrimitive?): String? {
    val raw = duration?.contentOrNull?.nullIfBlank() ?: return null
    val seconds = raw.toLongOrNull() ?: return raw
    val minutes = seconds / SECONDS_PER_MINUTE
    val remSeconds = seconds % SECONDS_PER_MINUTE
    return "%d:%02d".format(Locale.US, minutes, remSeconds)
}

internal fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

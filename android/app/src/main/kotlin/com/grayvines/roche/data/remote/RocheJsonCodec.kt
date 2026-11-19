@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data.remote

import com.grayvines.roche.data.NetworkTier
import com.grayvines.roche.data.PeerNodeDto
import com.grayvines.roche.data.SyncCollectionEntryDto
import com.grayvines.roche.data.SyncCollectionGeneratorDto
import com.grayvines.roche.data.SyncComparisonDto
import com.grayvines.roche.data.SyncConsumptionLogDto
import com.grayvines.roche.data.SyncEntryRecordDto
import com.grayvines.roche.data.SyncTombstoneDto
import com.grayvines.roche.data.inferFeedTypeWireValue
import com.grayvines.roche.data.normalizeConsecutiveFailures
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
    @SerialName("consecutive_failures") val consecutiveFailures: Int = 0,
    @SerialName("is_active") val isActive: Boolean = true,
) {
    fun toRemoteFeedDto(): RemoteFeedDto {
        val cleanLastError = lastError.nullIfBlank()
        return RemoteFeedDto(
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
            lastError = cleanLastError,
            lastErrorBy = cleanLastError?.let { lastErrorBy.nullIfBlank() },
            consecutiveFailures = normalizeConsecutiveFailures(cleanLastError, consecutiveFailures),
            isActive = isActive,
        )
    }
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

@Suppress("LongParameterList")
@Serializable
internal data class ProblemDetailsWireResponse(
    val type: String? = null,
    val title: String? = null,
    val detail: String? = null,
    val status: Int? = null,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Long? = null,
    @SerialName("max_bytes") val maxBytes: Long? = null,
    val candidates: List<String>? = null,
)

@Suppress("LongParameterList")
internal data class ParsedProblemDetails(
    val problemType: String,
    val title: String,
    val detail: String,
    val retryAfterSeconds: Long? = null,
    val maxBytes: Long? = null,
    val candidates: List<String> = emptyList(),
)

@Serializable
internal data class HealthDiscoveryWireResponse(
    val status: String = "",
    val service: String? = null,
    @SerialName("canonical_url") val canonicalUrl: String? = null,
    val version: String? = null,
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
internal data class PeerSyncWireRequest(
    @SerialName("peer_id") val peerId: String,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("network_tier") val networkTier: String,
    val weight: Double? = null,
    @SerialName("since_seq") val sinceSeq: Long = 0L,
    @SerialName("client_hlc") val clientHlc: String? = null,
    val entries: List<SyncEntryRecordDto> = emptyList(),
    @SerialName("collection_entries")
    val collectionEntries: List<SyncCollectionEntryDto> = emptyList(),
    @SerialName("collection_generators")
    val collectionGenerators: List<SyncCollectionGeneratorDto> = emptyList(),
    @SerialName("consumption_log") val consumptionLog: List<SyncConsumptionLogDto> = emptyList(),
    val comparisons: List<SyncComparisonDto> = emptyList(),
    val tombstones: List<SyncTombstoneDto> = emptyList(),
)

@Serializable
internal data class PeerSyncWireResponse(
    @SerialName("server_peer_id") val serverPeerId: String? = null,
    @SerialName("sync_seq") val syncSeq: Long = 0L,
    @SerialName("server_hlc") val serverHlc: String = "",
    @SerialName("sync_timestamp") val syncTimestamp: String = "",
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("reset_required") val resetRequired: Boolean = false,
    @SerialName("active_peers") val activePeers: List<WirePeerNodeDto> = emptyList(),
    val entries: List<SyncEntryRecordDto> = emptyList(),
    @SerialName("collection_entries")
    val collectionEntries: List<SyncCollectionEntryDto> = emptyList(),
    @SerialName("collection_generators")
    val collectionGenerators: List<SyncCollectionGeneratorDto> = emptyList(),
    @SerialName("consumption_log") val consumptionLog: List<SyncConsumptionLogDto> = emptyList(),
    val comparisons: List<SyncComparisonDto> = emptyList(),
    val tombstones: List<SyncTombstoneDto> = emptyList(),
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
            sinceSeq = request.sinceSeq,
            clientHlc = request.clientHlc.nullIfBlank(),
            entries = request.entries,
            collectionEntries = request.collectionEntries,
            collectionGenerators = request.collectionGenerators,
            consumptionLog = request.consumptionLog,
            comparisons = request.comparisons,
            tombstones = request.tombstones,
        ),
    )

internal fun parsePeerSyncResponseJson(raw: String): PeerSyncResponseDto {
    val wire = runCatching {
        rocheJson.decodeFromString(PeerSyncWireResponse.serializer(), raw)
    }
        .getOrElse { err ->
            throw ApiProtocolException(
                message = err.message ?: "Malformed JSON in /api/v1/sync/peer response",
                cause = err,
            )
        }
    val serverPeerId =
        wire.serverPeerId.nullIfBlank()
            ?: throw ApiProtocolException("Missing server_peer_id in /api/v1/sync/peer response")
    return PeerSyncResponseDto(
        serverPeerId = serverPeerId,
        syncSeq = wire.syncSeq,
        serverHlc = wire.serverHlc.nullIfBlank().orEmpty(),
        syncTimestamp = wire.syncTimestamp.nullIfBlank().orEmpty(),
        hasMore = wire.hasMore,
        resetRequired = wire.resetRequired,
        activePeers = wire.activePeers.map { it.toPeerNodeDto() }.filter { it.peerId.isNotBlank() },
        entries = wire.entries,
        collectionEntries = wire.collectionEntries,
        collectionGenerators = wire.collectionGenerators,
        consumptionLog = wire.consumptionLog,
        comparisons = wire.comparisons,
        tombstones =
            wire.tombstones.filter {
                it.entityType.isNotBlank() && it.naturalKey.isNotBlank()
            },
    )
}

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

internal fun parseRetryAfterHeader(headers: Map<String, String>): Long? =
    headers.entries
        .firstOrNull { (name, _) -> name.equals(HEADER_RETRY_AFTER, ignoreCase = true) }
        ?.value
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it >= 0L }

internal fun parseProblemDetails(
    raw: String,
    statusCode: Int,
    headers: Map<String, String> = emptyMap(),
): ParsedProblemDetails {
    val headerRetryAfter = parseRetryAfterHeader(headers)
    return runCatching {
        val parsed = rocheJson.decodeFromString(ProblemDetailsWireResponse.serializer(), raw)
        val parsedType = parsed.type.nullIfBlank() ?: RocheProblemTypes.BLANK
        val parsedTitle = parsed.title.nullIfBlank() ?: DEFAULT_ERROR_TITLE
        val parsedDetail = parsed.detail.nullIfBlank() ?: raw.ifBlank { "Request failed" }
        val parsedRetryAfter = parsed.retryAfterSeconds ?: headerRetryAfter
        ParsedProblemDetails(
            problemType = parsedType,
            title = parsedTitle,
            detail = parsedDetail,
            retryAfterSeconds = parsedRetryAfter,
            maxBytes = parsed.maxBytes,
            candidates = parsed.candidates.orEmpty().filter { it.isNotBlank() },
        )
    }
        .getOrElse {
            ParsedProblemDetails(
                problemType = RocheProblemTypes.BLANK,
                title = DEFAULT_ERROR_TITLE,
                detail = raw.ifBlank { "Status $statusCode" },
                retryAfterSeconds = headerRetryAfter,
            )
        }
}

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
    return formatDurationSecondsLabel(seconds)
}

internal fun formatDurationSecondsLabel(seconds: Long): String? {
    if (seconds <= 0L) return null
    val minutes = seconds / SECONDS_PER_MINUTE
    val remSeconds = seconds % SECONDS_PER_MINUTE
    return "%d:%02d".format(Locale.US, minutes, remSeconds)
}

internal fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data.remote

import com.grayvines.roche.data.inferFeedTypeWireValue
import com.grayvines.roche.data.normalized
import com.grayvines.roche.data.settings.validateCanonicalDiscoveryUrl
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal val rocheJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

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

@Serializable internal data class ImportOpmlWireRequest(val opml: String)

@Serializable
internal data class ImportOpmlWireResponse(
    @SerialName("imported_count") val importedCount: Int? = null,
    val imported: Int = 0,
)

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
internal data class MediaEnvelopeWireResponse(val media: List<RemoteMediaDto> = emptyList())

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

internal fun buildImportOpmlPayload(opmlXml: String): String =
    rocheJson.encodeToString(
        ImportOpmlWireRequest.serializer(),
        ImportOpmlWireRequest(opml = opmlXml),
    )

internal fun parseImportedCountJson(raw: String): Int {
    val parsed = rocheJson.decodeFromString(ImportOpmlWireResponse.serializer(), raw)
    return parsed.importedCount ?: parsed.imported
}

internal fun parseFeedJson(raw: String): RemoteFeedDto =
    rocheJson.decodeFromString(RemoteFeedDto.serializer(), raw).normalized()

internal fun parseItemJson(raw: String): RemoteItemDto =
    rocheJson.decodeFromString(WireItemDto.serializer(), raw).toRemoteItemDto()

internal fun buildPeerSyncRequestPayload(request: PeerSyncRequestDto): String =
    rocheJson.encodeToString(
        PeerSyncRequestDto.serializer(),
        request.copy(
            deviceName = request.deviceName.nullIfBlank(),
            clientHlc = request.clientHlc.nullIfBlank(),
        ),
    )

internal fun parsePeerSyncResponseJson(raw: String): PeerSyncResponseDto {
    val decoded = runCatching {
        rocheJson.decodeFromString(PeerSyncResponseDto.serializer(), raw)
    }
        .getOrElse { err ->
            throw ApiProtocolException(
                message = err.message ?: "Malformed JSON in /api/v1/sync/peer response",
                cause = err,
            )
        }
    val serverPeerId =
        decoded.serverPeerId.nullIfBlank()
            ?: throw ApiProtocolException("Missing server_peer_id in /api/v1/sync/peer response")
    return decoded.copy(
        serverPeerId = serverPeerId,
        serverHlc = decoded.serverHlc.nullIfBlank().orEmpty(),
        syncTimestamp = decoded.syncTimestamp.nullIfBlank().orEmpty(),
        activePeers = decoded.activePeers.map { it.normalized() }.filter { it.peerId.isNotBlank() },
        tombstones =
            decoded.tombstones.filter {
                it.entityType.isNotBlank() && it.naturalKey.isNotBlank()
            },
    )
}

internal fun parseMediaJsonArray(raw: String): List<RemoteMediaDto> {
    val trimmed = raw.trimStart()
    val wireMedia =
        when {
            trimmed.startsWith('[') ->
                rocheJson.decodeFromString(ListSerializer(RemoteMediaDto.serializer()), raw)
            trimmed.startsWith('{') ->
                rocheJson.decodeFromString(MediaEnvelopeWireResponse.serializer(), raw).media
            else -> emptyList()
        }
    return wireMedia.map { it.normalized() }.filter { it.id.isNotBlank() }
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

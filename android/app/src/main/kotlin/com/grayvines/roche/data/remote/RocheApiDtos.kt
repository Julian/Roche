package com.grayvines.roche.data.remote

import com.grayvines.roche.data.catalog.SyncCollectionEntryDto
import com.grayvines.roche.data.catalog.SyncCollectionGeneratorDto
import com.grayvines.roche.data.catalog.SyncComparisonDto
import com.grayvines.roche.data.catalog.SyncConsumptionLogDto
import com.grayvines.roche.data.catalog.SyncEntryRecordDto
import java.io.IOException
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val DEFAULT_ONLINE_TIER_WEIGHT = 1.0
private const val OFFLINE_TIER_WEIGHT = 0.0

enum class NetworkTier(val wireValue: String, val defaultWeight: Double, val displayLabel: String) {
    WIRED("wired", DEFAULT_ONLINE_TIER_WEIGHT, "Wired"),
    WIFI("wifi", DEFAULT_ONLINE_TIER_WEIGHT, "Wi-Fi"),
    CELLULAR("cellular", DEFAULT_ONLINE_TIER_WEIGHT, "Cellular"),
    OFFLINE("offline", OFFLINE_TIER_WEIGHT, "Offline");

    companion object {
        fun fromWireValue(raw: String?): NetworkTier =
            entries.firstOrNull { it.wireValue.equals(raw?.trim(), ignoreCase = true) } ?: WIFI
    }
}

@Serializable
data class PeerNodeDto(
    @SerialName("peer_id") val peerId: String = "",
    @SerialName("device_name") val deviceName: String = "",
    @SerialName("network_tier") val networkTier: String = NetworkTier.WIFI.wireValue,
    val weight: Double = NetworkTier.WIFI.defaultWeight,
    @SerialName("last_seen_at") val lastSeenAt: String = "",
)

internal fun PeerNodeDto.normalized(): PeerNodeDto =
    copy(
        peerId = peerId.takeIf { it.isNotBlank() }.orEmpty(),
        deviceName = deviceName.takeIf { it.isNotBlank() }.orEmpty(),
        networkTier = networkTier.takeIf { it.isNotBlank() } ?: NetworkTier.WIFI.wireValue,
        lastSeenAt = lastSeenAt.takeIf { it.isNotBlank() }.orEmpty(),
    )

@Serializable
data class SyncTombstoneDto(
    @SerialName("entity_type") val entityType: String = "",
    @SerialName("natural_key") val naturalKey: String = "",
    @SerialName("deleted_at") val deletedAt: String = "",
    val hlc: String = "",
)

object RocheProblemTypes {
    const val BLANK = "about:blank"
    const val TOKEN_INVALID = "urn:roche:problem:token-invalid"
    const val PAIRING_CODE_INVALID = "urn:roche:problem:pairing-code-invalid"
    const val PAIRING_RATE_LIMITED = "urn:roche:problem:pairing-rate-limited"
    const val PAYLOAD_TOO_LARGE = "urn:roche:problem:payload-too-large"
    const val AMBIGUOUS_LOOKUP = "urn:roche:problem:ambiguous-lookup"
    const val IMMUTABLE_COLLECTION = "urn:roche:problem:immutable-collection"
    const val SSRF_BLOCKED = "urn:roche:problem:ssrf-blocked"
    const val CONFLICT = "urn:roche:problem:conflict"
}

@Suppress("LongParameterList")
class ProblemDetailsException(
    val statusCode: Int,
    val title: String,
    val detail: String,
    val problemType: String = RocheProblemTypes.BLANK,
    val retryAfterSeconds: Long? = null,
    val maxBytes: Long? = null,
    val candidates: List<String> = emptyList(),
) : Exception("$title ($statusCode): $detail")

class ApiProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

class HttpStatusException(val statusCode: Int, message: String = "HTTP $statusCode") :
    IOException(message)

class ResponseTooLargeException(
    val maxBytes: Long,
    message: String = "Response exceeds maximum allowed size of $maxBytes bytes",
) : IOException(message)

fun formatProblemDetailsMessage(error: ProblemDetailsException): String =
    when (error.problemType) {
        RocheProblemTypes.TOKEN_INVALID -> "Device token revoked or expired — re-pair in Settings"
        RocheProblemTypes.PAIRING_RATE_LIMITED ->
            error.retryAfterSeconds
                ?.takeIf { it > 0L }
                ?.let { seconds -> "Too many pairing attempts. Try again in ${seconds}s" }
                ?: "Too many pairing attempts. Try again shortly"
        RocheProblemTypes.PAIRING_CODE_INVALID ->
            "Pairing code is invalid or expired — generate a new code on the server"
        RocheProblemTypes.SSRF_BLOCKED ->
            error.detail.ifBlank { error.message ?: "URL blocked by server security policy" }
        else -> error.detail.ifBlank { error.message ?: "${error.title} (${error.statusCode})" }
    }

@Suppress("LongParameterList")
data class RemoteItemDto(
    val id: String,
    val feedId: String?,
    val feedTitle: String?,
    val title: String,
    val url: String,
    val author: String?,
    val summary: String?,
    val contentText: String?,
    val contentHtml: String?,
    val contentType: String,
    val status: String,
    val isFavorite: Boolean,
    val publishedAt: String?,
    val createdAt: String,
    val readProgress: Double,
    val thumbnailMediaId: String?,
    val duration: String?,
    val tagNames: List<String> = emptyList(),
    val readAt: String? = null,
    val thumbnailUrl: String? = null,
    val feedUrl: String? = null,
    val interactionUpdatedAt: String? = null,
    val audioUrl: String? = null,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@Suppress("LongParameterList")
data class SyncBoardItemDto(
    @SerialName("external_id") val externalId: String,
    val rank: Int,
    val title: String,
    val url: String,
    @SerialName("discussion_url") val discussionUrl: String? = null,
    val author: String? = null,
    val domain: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val score: Int? = null,
    @SerialName("comment_count") val commentCount: Int? = null,
    val summary: String? = null,
    @SerialName("self_text_html") val selfTextHtml: String? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("metadata_json")
    val metadataJson: String = "{}",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) @SerialName("fetched_at") val fetchedAt: String = "",
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@Suppress("LongParameterList")
data class SyncBoardDto(
    val id: String = "",
    @SerialName("adapter_type") val adapterType: String,
    @SerialName("source_key") val sourceKey: String,
    @SerialName("source_url") val sourceUrl: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("source_config_json")
    val sourceConfigJson: String = "{}",
    val title: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("order_mode")
    val orderMode: String = "ranked",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("poll_interval")
    val pollInterval: Int = 30,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("last_polled_at") val lastPolledAt: String? = null,
    @SerialName("last_polled_by") val lastPolledBy: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("last_error_by") val lastErrorBy: String? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("consecutive_failures")
    val consecutiveFailures: Int = 0,
    @SerialName("snapshot_fetched_at") val snapshotFetchedAt: String? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("snapshot_hlc")
    val snapshotHlc: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val hlc: String = "",
    @SerialName("snapshot_items") val snapshotItems: List<SyncBoardItemDto>? = null,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class SyncBoardItemStateDto(
    @SerialName("board_id") val boardId: String,
    @SerialName("external_id") val externalId: String,
    @SerialName("seen_at") val seenAt: String? = null,
    @SerialName("dismissed_at") val dismissedAt: String? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("last_seen_in_snapshot_at")
    val lastSeenInSnapshotAt: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) @SerialName("updated_at") val updatedAt: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val hlc: String = "",
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@Suppress("LongParameterList")
data class PeerSyncRequestDto(
    @SerialName("peer_id") val peerId: String,
    @SerialName("device_name") val deviceName: String? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("network_tier")
    val networkTier: String = NetworkTier.WIFI.wireValue,
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
    val boards: List<SyncBoardDto> = emptyList(),
    @SerialName("board_item_states") val boardItemStates: List<SyncBoardItemStateDto> = emptyList(),
)

@Serializable
@Suppress("LongParameterList")
data class PeerSyncResponseDto(
    @SerialName("server_peer_id") val serverPeerId: String = "",
    @SerialName("sync_seq") val syncSeq: Long = 0L,
    @SerialName("server_hlc") val serverHlc: String = "",
    @SerialName("sync_timestamp") val syncTimestamp: String = "",
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("reset_required") val resetRequired: Boolean = false,
    @SerialName("active_peers") val activePeers: List<PeerNodeDto> = emptyList(),
    val entries: List<SyncEntryRecordDto> = emptyList(),
    @SerialName("collection_entries")
    val collectionEntries: List<SyncCollectionEntryDto> = emptyList(),
    @SerialName("collection_generators")
    val collectionGenerators: List<SyncCollectionGeneratorDto> = emptyList(),
    @SerialName("consumption_log") val consumptionLog: List<SyncConsumptionLogDto> = emptyList(),
    val comparisons: List<SyncComparisonDto> = emptyList(),
    val tombstones: List<SyncTombstoneDto> = emptyList(),
    val boards: List<SyncBoardDto> = emptyList(),
    @SerialName("board_item_states") val boardItemStates: List<SyncBoardItemStateDto> = emptyList(),
)

@Serializable
data class RemoteMediaDto(
    val id: String = "",
    @SerialName("item_id") val itemId: String = "",
    @SerialName("media_type") val mediaType: String = DEFAULT_MEDIA_TYPE,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("mime_type") val mimeType: String? = null,
)

internal fun RemoteMediaDto.normalized(): RemoteMediaDto =
    copy(
        id = id.nullIfBlank().orEmpty(),
        itemId = itemId.nullIfBlank().orEmpty(),
        mediaType = mediaType.nullIfBlank() ?: DEFAULT_MEDIA_TYPE,
        mimeType = mimeType.nullIfBlank(),
    )

data class DiscoveredServerDto(
    val probedUrl: String,
    val canonicalUrl: String? = null,
    val version: String? = null,
) {
    val preferredUrl: String
        get() = canonicalUrl?.takeIf { it.isNotBlank() } ?: probedUrl
}

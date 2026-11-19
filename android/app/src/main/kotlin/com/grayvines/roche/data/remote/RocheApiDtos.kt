package com.grayvines.roche.data.remote

import com.grayvines.roche.data.NetworkTier
import com.grayvines.roche.data.PeerNodeDto
import com.grayvines.roche.data.SyncCollectionEntryDto
import com.grayvines.roche.data.SyncCollectionGeneratorDto
import com.grayvines.roche.data.SyncComparisonDto
import com.grayvines.roche.data.SyncConsumptionLogDto
import com.grayvines.roche.data.SyncEntryRecordDto
import com.grayvines.roche.data.SyncTombstoneDto
import java.io.IOException

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
data class RemoteFeedDto(
    val id: String,
    val title: String,
    val url: String,
    val siteUrl: String?,
    val feedType: String,
    val unreadCount: Int,
    val description: String? = null,
    val language: String? = null,
    val pollInterval: Int = 30,
    val lastPolled: String? = null,
    val lastPolledBy: String? = null,
    val lastError: String? = null,
    val lastErrorBy: String? = null,
    val consecutiveFailures: Int = 0,
    val isActive: Boolean = true,
)

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

data class RemoteTagDto(
    val id: String,
    val name: String,
    val unreadCount: Int,
    val color: String? = null,
)

@Suppress("LongParameterList")
data class PeerSyncRequestDto(
    val peerId: String,
    val deviceName: String? = null,
    val networkTier: String = NetworkTier.WIFI.wireValue,
    val weight: Double? = null,
    val sinceSeq: Long = 0L,
    val clientHlc: String? = null,
    val entries: List<SyncEntryRecordDto> = emptyList(),
    val collectionEntries: List<SyncCollectionEntryDto> = emptyList(),
    val collectionGenerators: List<SyncCollectionGeneratorDto> = emptyList(),
    val consumptionLog: List<SyncConsumptionLogDto> = emptyList(),
    val comparisons: List<SyncComparisonDto> = emptyList(),
    val tombstones: List<SyncTombstoneDto> = emptyList(),
)

@Suppress("LongParameterList")
data class PeerSyncResponseDto(
    val serverPeerId: String,
    val syncSeq: Long = 0L,
    val serverHlc: String = "",
    val syncTimestamp: String = "",
    val hasMore: Boolean = false,
    val resetRequired: Boolean = false,
    val activePeers: List<PeerNodeDto> = emptyList(),
    val entries: List<SyncEntryRecordDto> = emptyList(),
    val collectionEntries: List<SyncCollectionEntryDto> = emptyList(),
    val collectionGenerators: List<SyncCollectionGeneratorDto> = emptyList(),
    val consumptionLog: List<SyncConsumptionLogDto> = emptyList(),
    val comparisons: List<SyncComparisonDto> = emptyList(),
    val tombstones: List<SyncTombstoneDto> = emptyList(),
)

data class RemoteMediaDto(
    val id: String,
    val itemId: String,
    val mediaType: String,
    val fileSize: Long? = null,
    val mimeType: String? = null,
)

data class DiscoveredServerDto(
    val probedUrl: String,
    val canonicalUrl: String? = null,
    val version: String? = null,
) {
    val preferredUrl: String
        get() = canonicalUrl?.takeIf { it.isNotBlank() } ?: probedUrl
}

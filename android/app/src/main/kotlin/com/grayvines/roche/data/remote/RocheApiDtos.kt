package com.grayvines.roche.data.remote

import com.grayvines.roche.data.NetworkTier
import com.grayvines.roche.data.PeerCatalogEntryPayload
import com.grayvines.roche.data.PeerCollectionEntryPayload
import com.grayvines.roche.data.PeerCollectionGeneratorPayload
import com.grayvines.roche.data.PeerCollectionSpecPayload
import com.grayvines.roche.data.PeerComparisonPayload
import com.grayvines.roche.data.PeerConsumptionLogPayload
import com.grayvines.roche.data.PeerNodeDto
import com.grayvines.roche.data.SyncTombstoneDto
import com.grayvines.roche.data.local.FeedEntity
import com.grayvines.roche.data.local.TagEntity

class ProblemDetailsException(val statusCode: Int, val title: String, val detail: String) :
    Exception("$title ($statusCode): $detail")

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
    val isActive: Boolean = true,
)

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

data class PeerSyncItemPayload(
    val id: String,
    val feedId: String?,
    val feedUrl: String?,
    val title: String,
    val url: String,
    val author: String?,
    val publishedAt: String?,
    val createdAt: String?,
    val summary: String?,
    val contentHtml: String?,
    val thumbnailUrl: String?,
    val contentType: String,
    val status: String,
    val isFavorite: Boolean,
    val readAt: String?,
    val readProgress: Double,
    val interactionUpdatedAt: String?,
    val tagNames: List<String>,
    val audioUrl: String? = null,
    val duration: Long? = null,
)

data class PeerSyncRequestDto(
    val peerId: String,
    val deviceName: String? = null,
    val networkTier: String = NetworkTier.WIFI.wireValue,
    val weight: Double? = null,
    val updatedAfter: String? = null,
    val afterItemId: String? = null,
    val feeds: List<FeedEntity> = emptyList(),
    val items: List<PeerSyncItemPayload> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val tombstones: List<SyncTombstoneDto> = emptyList(),
    val collections: List<PeerCollectionSpecPayload> = emptyList(),
    val catalogEntries: List<PeerCatalogEntryPayload> = emptyList(),
    val collectionEntries: List<PeerCollectionEntryPayload> = emptyList(),
    val collectionGenerators: List<PeerCollectionGeneratorPayload> = emptyList(),
    val consumptionLog: List<PeerConsumptionLogPayload> = emptyList(),
    val comparisons: List<PeerComparisonPayload> = emptyList(),
)

data class PeerSyncResponseDto(
    val serverPeerId: String,
    val syncTimestamp: String,
    val activePeers: List<PeerNodeDto>,
    val feeds: List<RemoteFeedDto>,
    val items: List<RemoteItemDto>,
    val tags: List<RemoteTagDto>,
    val tombstones: List<SyncTombstoneDto>,
    val hasMore: Boolean = false,
    val nextAfterItemId: String? = null,
    val collections: List<PeerCollectionSpecPayload> = emptyList(),
    val catalogEntries: List<PeerCatalogEntryPayload> = emptyList(),
    val collectionEntries: List<PeerCollectionEntryPayload> = emptyList(),
    val collectionGenerators: List<PeerCollectionGeneratorPayload> = emptyList(),
    val consumptionLog: List<PeerConsumptionLogPayload> = emptyList(),
    val comparisons: List<PeerComparisonPayload> = emptyList(),
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

package com.grayvines.roche.data.local

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "feeds", indices = [Index(value = ["url"], unique = true)])
data class FeedEntity(
    @PrimaryKey val id: String,
    val title: String,
    val url: String,
    val siteUrl: String?,
    val feedType: String,
    val description: String? = null,
    val language: String? = null,
    val pollInterval: Int = 30,
    val lastPolled: String? = null,
    val lastPolledBy: String? = null,
    val lastError: String? = null,
    val lastErrorBy: String? = null,
    val isActive: Boolean = true,
    val autoDownloadEpisodes: Boolean = false,
    val customPlaybackSpeed: Float? = null,
)

@Entity(
    tableName = "items",
    indices =
        [
            Index(value = ["feedId"]),
            Index(value = ["status"]),
            Index(value = ["contentType"]),
            Index(value = ["isFavorite"]),
            Index(value = ["publishedAt", "createdAt"]),
        ],
)
data class ItemEntity(
    @PrimaryKey val id: String,
    val feedId: String?,
    val feedTitle: String,
    val title: String,
    val url: String,
    val author: String?,
    val summary: String?,
    val contentText: String?,
    val contentHtml: String?,
    val contentType: String,
    val status: String,
    val isFavorite: Boolean,
    val isCachedOffline: Boolean,
    val publishedAt: String?,
    val createdAt: String,
    val readProgress: Double,
    val thumbnailMediaId: String?,
    val duration: String?,
    val readAt: String? = null,
    val thumbnailUrl: String? = null,
    val feedUrl: String? = null,
    val interactionUpdatedAt: String? = null,
    val audioUrl: String? = null,
)

@Entity(
    tableName = "item_contents",
    foreignKeys =
        [
            ForeignKey(
                entity = ItemEntity::class,
                parentColumns = ["id"],
                childColumns = ["itemId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class ItemContentEntity(
    @PrimaryKey val itemId: String,
    val summary: String? = null,
    val contentText: String? = null,
    val contentHtml: String? = null,
)

data class ItemStateRow(
    val id: String,
    val status: String,
    val isFavorite: Boolean,
    val isCachedOffline: Boolean,
    val contentType: String,
    val readAt: String?,
    val readProgress: Double,
    val audioUrl: String?,
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(@PrimaryKey val id: String, val name: String, val color: String? = null)

@Entity(
    tableName = "sync_tombstones",
    primaryKeys = ["entityType", "naturalKey"],
)
data class TombstoneEntity(
    val entityType: String,
    val naturalKey: String,
    val deletedAt: String,
)

@Entity(
    tableName = "item_tags",
    primaryKeys = ["itemId", "tagId"],
    indices = [Index(value = ["itemId"]), Index(value = ["tagId"])],
)
data class ItemTagEntity(val itemId: String, val tagId: String)

enum class PendingEntityKind {
    LOCAL_FEED,
    LOCAL_ITEM,
    REMOTE_ITEM,
}

@Entity(tableName = "pending_mutations")
data class PendingMutationEntity(
    @PrimaryKey val entityId: String,
    val isLocalFeed: Boolean = false,
    val isLocalItem: Boolean = false,
    val titleOverride: String? = null,
    val dirtyStatus: String? = null,
    val dirtyFavorite: Boolean? = null,
    val dirtyReadProgress: Double? = null,
    val dirtyReadAt: String? = null,
    val addedTagsCsv: String = "",
    val removedTagsCsv: String = "",
) {
    val kind: PendingEntityKind
        get() =
            when {
                isLocalFeed -> PendingEntityKind.LOCAL_FEED
                isLocalItem -> PendingEntityKind.LOCAL_ITEM
                else -> PendingEntityKind.REMOTE_ITEM
            }

    companion object {
        fun localFeed(entityId: String, titleOverride: String? = null): PendingMutationEntity =
            PendingMutationEntity(
                entityId = entityId,
                isLocalFeed = true,
                titleOverride = titleOverride,
            )

        fun localItem(entityId: String, titleOverride: String? = null): PendingMutationEntity =
            PendingMutationEntity(
                entityId = entityId,
                isLocalItem = true,
                titleOverride = titleOverride,
            )
    }
}

@Entity(
    tableName = "catalog_entries",
    indices =
        [
            Index(value = ["kind"]),
            Index(value = ["mergedIntoId"]),
            Index(value = ["updatedAt"]),
        ],
)
data class CatalogEntryEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val creatorsJson: String = "[]",
    val url: String? = null,
    val publishedAt: String? = null,
    val releaseYear: Int? = null,
    val summary: String? = null,
    val thumbnailUrl: String? = null,
    val metadataJson: String = "{}",
    val mergedIntoId: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val isLocalDirty: Boolean = false,
)

@Entity(
    tableName = "entry_identifiers",
    primaryKeys = ["scheme", "value"],
    indices = [Index(value = ["entryId"])],
)
data class EntryIdentifierEntity(
    val scheme: String,
    val value: String,
    val entryId: String,
)

@Entity(
    tableName = "collection_specs",
    indices =
        [
            Index(value = ["slug"]),
            Index(value = ["role"]),
        ],
)
data class CollectionSpecEntity(
    @PrimaryKey val collectionId: String,
    val title: String,
    val summary: String? = null,
    val slug: String? = null,
    val mode: String = "manual",
    val role: String = "list",
    val ruleJson: String? = null,
    val showInLists: Boolean = true,
    val color: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val isLocalDirty: Boolean = false,
)

@Entity(
    tableName = "collection_entries",
    primaryKeys = ["collectionId", "entryId"],
    indices = [Index(value = ["entryId"])],
)
data class CollectionEntryEntity(
    val collectionId: String,
    val entryId: String,
    val position: Double? = null,
    val addedAt: String,
    val isLocalDirty: Boolean = false,
)

@Entity(tableName = "collection_generators")
data class CollectionGeneratorEntity(
    @PrimaryKey val collectionId: String,
    val enabled: Boolean = false,
    val currentEntryId: String? = null,
    val drawnAt: String? = null,
    val updatedAt: String,
    val isLocalDirty: Boolean = false,
)

@Entity(
    tableName = "consumption_log",
    indices =
        [
            Index(value = ["entryId"]),
            Index(value = ["ts"]),
        ],
)
data class ConsumptionLogEntity(
    @PrimaryKey val id: String,
    val entryId: String,
    val ts: String,
    val status: String,
    val notes: String? = null,
    val createdAt: String,
    val isLocalDirty: Boolean = false,
)

@Entity(
    tableName = "comparisons",
    indices =
        [
            Index(value = ["scopeCollectionId"]),
            Index(value = ["ts"]),
        ],
)
data class ComparisonEntity(
    @PrimaryKey val id: String,
    val ts: String,
    val scopeCollectionId: String? = null,
    val orderingJson: String,
    val createdAt: String,
    val isLocalDirty: Boolean = false,
)

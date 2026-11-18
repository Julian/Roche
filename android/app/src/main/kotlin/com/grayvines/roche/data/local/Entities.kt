package com.grayvines.roche.data.local

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Fts5
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "entries",
    indices =
        [
            Index(value = ["kind"]),
            Index(value = ["mergedIntoId"]),
            Index(value = ["updatedAt"]),
            Index(value = ["publishedAt", "createdAt"]),
            Index(value = ["createdAt"]),
            Index(value = ["isLocalDirty"]),
        ],
)
data class EntryEntity(
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
    val hlc: String = "",
    val isLocalDirty: Boolean = false,
)

@Entity(
    tableName = "entry_content",
    foreignKeys =
        [
            ForeignKey(
                entity = EntryEntity::class,
                parentColumns = ["id"],
                childColumns = ["entryId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["contentType"])],
)
data class EntryContentEntity(
    @PrimaryKey val entryId: String,
    val contentType: String = "article",
    val contentText: String? = null,
    val contentHtml: String? = null,
    val audioUrl: String? = null,
    val duration: String? = null,
    val thumbnailMediaId: String? = null,
    val fallbackFeedTitle: String? = null,
)

@Entity(
    tableName = "entry_state",
    foreignKeys =
        [
            ForeignKey(
                entity = EntryEntity::class,
                parentColumns = ["id"],
                childColumns = ["entryId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices =
        [
            Index(value = ["inboxState"]),
            Index(value = ["interactionUpdatedAt"]),
            Index(value = ["isCachedOffline"]),
            Index(value = ["isLocalDirty"]),
        ],
)
data class EntryStateEntity(
    @PrimaryKey val entryId: String,
    val inboxState: String = DEFAULT_INBOX_STATE,
    val readAt: String? = null,
    val progress: Double = 0.0,
    val interactionUpdatedAt: String? = null,
    val updatedAt: String,
    val isCachedOffline: Boolean = false,
    val hlc: String = "",
    val isLocalDirty: Boolean = false,
)

@Entity(tableName = "entries_fts")
@Fts5(contentEntity = EntryEntity::class)
data class EntryFtsEntity(
    val title: String,
    val creatorsJson: String,
    val url: String?,
    val summary: String?,
)

@Entity(tableName = "entry_content_fts")
@Fts5(contentEntity = EntryContentEntity::class)
data class EntryContentFtsEntity(
    val contentText: String?,
    val contentHtml: String?,
    val fallbackFeedTitle: String?,
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
    tableName = "feed_sources",
    indices =
        [
            Index(value = ["feedUrl"], unique = true),
            Index(value = ["isLocalDirty"]),
        ],
)
data class FeedSourceEntity(
    @PrimaryKey val collectionId: String,
    val feedUrl: String,
    val siteUrl: String? = null,
    val sourceType: String = "rss",
    val feedTitle: String? = null,
    val language: String? = null,
    val pollInterval: Int = 30,
    val isActive: Boolean = true,
    val lastPolledAt: String? = null,
    val lastPolledBy: String? = null,
    val lastError: String? = null,
    val lastErrorBy: String? = null,
    val autoDownloadEpisodes: Boolean = false,
    val customPlaybackSpeed: Float? = null,
    val hlc: String = "",
    val isLocalDirty: Boolean = false,
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
    val slug: String? = null,
    val mode: String = "manual",
    val role: String = "list",
    val ruleJson: String? = null,
    val showInLists: Boolean = true,
    val color: String? = null,
)

@Entity(
    tableName = "collection_entries",
    primaryKeys = ["collectionId", "entryId"],
    indices =
        [
            Index(value = ["entryId"]),
            Index(value = ["addedAt"]),
            Index(value = ["isLocalDirty"]),
        ],
)
data class CollectionEntryEntity(
    val collectionId: String,
    val entryId: String,
    val position: Double? = null,
    val addedAt: String,
    val hlc: String = "",
    val isLocalDirty: Boolean = false,
)

@Entity(tableName = "collection_generators")
data class CollectionGeneratorEntity(
    @PrimaryKey val collectionId: String,
    val enabled: Boolean = false,
    val currentEntryId: String? = null,
    val drawnAt: String? = null,
    val updatedAt: String,
    val hlc: String = "",
    val isLocalDirty: Boolean = false,
)

@Entity(
    tableName = "consumption_log",
    indices =
        [
            Index(value = ["entryId"]),
            Index(value = ["ts"]),
            Index(value = ["createdAt"]),
            Index(value = ["isLocalDirty"]),
        ],
)
data class ConsumptionLogEntity(
    @PrimaryKey val id: String,
    val entryId: String,
    val ts: String,
    val status: String,
    val notes: String? = null,
    val createdAt: String,
    val hlc: String = "",
    val isLocalDirty: Boolean = false,
)

@Entity(
    tableName = "comparisons",
    indices =
        [
            Index(value = ["scopeCollectionId"]),
            Index(value = ["ts"]),
            Index(value = ["createdAt"]),
            Index(value = ["isLocalDirty"]),
        ],
)
data class ComparisonEntity(
    @PrimaryKey val id: String,
    val ts: String,
    val scopeCollectionId: String? = null,
    val orderingJson: String,
    val createdAt: String,
    val hlc: String = "",
    val isLocalDirty: Boolean = false,
)

@Entity(
    tableName = "sync_tombstones",
    primaryKeys = ["entityType", "naturalKey"],
)
data class TombstoneEntity(
    val entityType: String,
    val naturalKey: String,
    val deletedAt: String,
    val hlc: String = "",
    val isLocalDirty: Boolean = false,
)

@Entity(tableName = "local_sync_flags")
data class LocalSyncFlagEntity(
    @PrimaryKey val flagKey: String,
    val flagValue: String,
)

@Suppress("LongParameterList")
data class FeedRow(
    val id: String,
    val title: String,
    val url: String,
    val siteUrl: String? = null,
    val feedType: String = "rss",
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
    val hlc: String = "",
    val isLocalDirty: Boolean = false,
)

@Suppress("LongParameterList")
data class InboxItemRow(
    val id: String,
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
    val inboxState: String = DEFAULT_INBOX_STATE,
)

@Suppress("LongParameterList")
data class ItemStateRow(
    val id: String,
    val status: String,
    val isFavorite: Boolean,
    val isCachedOffline: Boolean,
    val contentType: String,
    val readAt: String?,
    val readProgress: Double,
    val audioUrl: String?,
    val inboxState: String = DEFAULT_INBOX_STATE,
)

data class TagRow(
    val id: String,
    val name: String,
    val color: String? = null,
)

data class ItemTagRow(
    val itemId: String,
    val tagId: String,
)

internal const val DEFAULT_INBOX_STATE = "unread"

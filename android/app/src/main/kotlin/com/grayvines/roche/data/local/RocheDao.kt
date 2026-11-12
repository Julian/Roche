package com.grayvines.roche.data.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.grayvines.roche.data.MAX_SQLITE_HTML_CHARS
import com.grayvines.roche.data.MAX_SQLITE_SUMMARY_CHARS
import com.grayvines.roche.data.MAX_SQLITE_TEXT_CHARS
import kotlinx.coroutines.flow.Flow

@Dao
interface RocheDao {
    @Query("SELECT * FROM feeds ORDER BY title COLLATE NOCASE ASC, title ASC")
    fun observeFeeds(): Flow<List<FeedEntity>>

    @Query(
        "SELECT id, feedId, feedTitle, title, url, author, " +
            "SUBSTR(summary, 1, 16384) AS summary, " +
            "SUBSTR(contentText, 1, 65536) AS contentText, " +
            "SUBSTR(contentHtml, 1, 65536) AS contentHtml, " +
            "contentType, status, isFavorite, isCachedOffline, publishedAt, createdAt, " +
            "readProgress, thumbnailMediaId, duration, readAt, thumbnailUrl, feedUrl, " +
            "interactionUpdatedAt, audioUrl FROM items ORDER BY COALESCE(publishedAt, createdAt) DESC"
    )
    fun observeItems(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE ASC, name ASC")
    fun observeTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM item_tags") fun observeItemTags(): Flow<List<ItemTagEntity>>

    @Query("SELECT COUNT(*) FROM items") suspend fun countItems(): Int

    @Query("SELECT * FROM feeds") suspend fun getAllFeeds(): List<FeedEntity>

    @Query("SELECT * FROM feeds WHERE id = :id LIMIT 1")
    suspend fun getFeedById(id: String): FeedEntity?

    @Query("SELECT * FROM feeds WHERE url = :url LIMIT 1")
    suspend fun getFeedByUrl(url: String): FeedEntity?

    @Query("DELETE FROM feeds WHERE id = :id") suspend fun deleteFeedById(id: String)

    @Query("UPDATE feeds SET title = :title WHERE id = :feedId")
    suspend fun updateFeedTitle(feedId: String, title: String)

    @Query("UPDATE feeds SET isActive = :isActive WHERE id = :feedId")
    suspend fun updateFeedActive(feedId: String, isActive: Boolean)

    @Query("UPDATE feeds SET autoDownloadEpisodes = :enabled WHERE id = :feedId")
    suspend fun updateFeedAutoDownload(feedId: String, enabled: Boolean)

    @Query("UPDATE feeds SET customPlaybackSpeed = :speed WHERE id = :feedId")
    suspend fun updateFeedPlaybackSpeed(feedId: String, speed: Float?)

    @Query("UPDATE items SET feedTitle = :title WHERE feedId = :feedId")
    suspend fun updateItemsFeedTitle(feedId: String, title: String)

    @Query("SELECT id FROM items WHERE feedId = :feedId AND status != 'archived'")
    suspend fun getUnarchivedItemIdsForFeed(feedId: String): List<String>

    @Query("SELECT * FROM sync_tombstones") suspend fun getAllTombstones(): List<TombstoneEntity>

    @Query(
        "SELECT * FROM sync_tombstones WHERE entityType = :entityType AND naturalKey = :naturalKey LIMIT 1"
    )
    suspend fun getTombstone(entityType: String, naturalKey: String): TombstoneEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTombstone(tombstone: TombstoneEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTombstones(tombstones: List<TombstoneEntity>)

    @Query(
        "DELETE FROM sync_tombstones WHERE entityType = :entityType AND naturalKey = :naturalKey"
    )
    suspend fun deleteTombstone(entityType: String, naturalKey: String)

    @Query("DELETE FROM sync_tombstones") suspend fun clearAllTombstones()

    @Query("SELECT * FROM tags") suspend fun getAllTags(): List<TagEntity>

    @Query("SELECT * FROM item_tags") suspend fun getAllItemTags(): List<ItemTagEntity>

    @Query(
        "SELECT t.name FROM tags t INNER JOIN item_tags it ON t.id = it.tagId " +
            "WHERE it.itemId = :itemId ORDER BY t.name COLLATE NOCASE ASC, t.name ASC"
    )
    suspend fun getTagNamesForItem(itemId: String): List<String>

    @Query("UPDATE OR IGNORE item_tags SET tagId = :newTagId WHERE tagId = :oldTagId")
    suspend fun migrateItemTagIds(oldTagId: String, newTagId: String)

    @Query("SELECT * FROM pending_mutations")
    suspend fun getAllPendingMutations(): List<PendingMutationEntity>

    @Query("SELECT * FROM pending_mutations WHERE entityId = :entityId LIMIT 1")
    suspend fun getPendingMutation(entityId: String): PendingMutationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingMutation(mutation: PendingMutationEntity)

    @Query("DELETE FROM pending_mutations WHERE entityId = :entityId")
    suspend fun deletePendingMutation(entityId: String)

    @Query(
        "SELECT * FROM items WHERE feedId = :feedId " +
            "OR (feedUrl IS NOT NULL AND LOWER(feedUrl) = LOWER(:feedUrl))"
    )
    suspend fun getItemsForFeed(feedId: String, feedUrl: String): List<ItemEntity>

    @Query(
        "SELECT id, feedId, feedTitle, title, url, author, " +
            "SUBSTR(summary, 1, 16384) AS summary, " +
            "SUBSTR(contentText, 1, 262144) AS contentText, " +
            "SUBSTR(contentHtml, 1, 512000) AS contentHtml, " +
            "contentType, status, isFavorite, isCachedOffline, publishedAt, createdAt, " +
            "readProgress, thumbnailMediaId, duration, readAt, thumbnailUrl, feedUrl, " +
            "interactionUpdatedAt, audioUrl FROM items"
    )
    suspend fun getAllItems(): List<ItemEntity>

    @Query(
        "SELECT id, feedId, feedTitle, title, url, author, " +
            "SUBSTR(summary, 1, 16384) AS summary, " +
            "SUBSTR(contentText, 1, 262144) AS contentText, " +
            "SUBSTR(contentHtml, 1, 512000) AS contentHtml, " +
            "contentType, status, isFavorite, isCachedOffline, publishedAt, createdAt, " +
            "readProgress, thumbnailMediaId, duration, readAt, thumbnailUrl, feedUrl, " +
            "interactionUpdatedAt, audioUrl FROM items WHERE id = :id LIMIT 1"
    )
    suspend fun getItemById(id: String): ItemEntity?

    @Query("DELETE FROM items WHERE id = :id") suspend fun deleteItemById(id: String)

    @Query("DELETE FROM tags WHERE id = :id") suspend fun deleteTagById(id: String)

    @Query("DELETE FROM item_tags WHERE itemId = :itemId")
    suspend fun deleteItemTagsForItem(itemId: String)

    @Query("DELETE FROM feeds WHERE id IN (:ids)") suspend fun deleteFeedsByIds(ids: List<String>)

    @Query("DELETE FROM tags WHERE id IN (:ids)") suspend fun deleteTagsByIds(ids: List<String>)

    @Query("DELETE FROM items WHERE id IN (:ids)") suspend fun deleteItemsByIds(ids: List<String>)

    @Query("DELETE FROM item_tags") suspend fun clearAllItemTags()

    @Query("DELETE FROM items") suspend fun clearAllItems()

    @Query("DELETE FROM feeds") suspend fun clearAllFeeds()

    @Query("DELETE FROM tags") suspend fun clearAllTags()

    @Transaction
    suspend fun reconcileSnapshotDiff(
        feeds: List<FeedEntity>,
        tags: List<TagEntity>,
        items: List<ItemEntity>,
        itemTags: List<ItemTagEntity>,
    ) {
        val existingFeedsById = getAllFeeds().associateBy { it.id }
        val existingTagsById = getAllTags().associateBy { it.id }
        val existingItemsById = getAllItems().associateBy { it.id }
        val existingItemTags = getAllItemTags().toSet()

        val targetFeedsById = feeds.associateBy { it.id }
        val targetTagsById = tags.associateBy { it.id }
        val targetItemsById = items.map { truncateItemForStorage(it) }.associateBy { it.id }
        val targetItemTags = itemTags.toSet()

        (existingItemTags - targetItemTags).forEach { removed ->
            deleteItemTag(itemId = removed.itemId, tagId = removed.tagId)
        }
        deleteRemovedIdsInChunks(existingItemsById.keys, targetItemsById.keys) { ids ->
            deleteItemsByIds(ids)
        }
        deleteRemovedIdsInChunks(existingFeedsById.keys, targetFeedsById.keys) { ids ->
            deleteFeedsByIds(ids)
        }
        deleteRemovedIdsInChunks(existingTagsById.keys, targetTagsById.keys) { ids ->
            deleteTagsByIds(ids)
        }

        val changedFeeds = collectChangedEntities(existingFeedsById, targetFeedsById)
        if (changedFeeds.isNotEmpty()) {
            upsertFeeds(changedFeeds)
        }
        val changedTags = collectChangedEntities(existingTagsById, targetTagsById)
        if (changedTags.isNotEmpty()) {
            upsertTags(changedTags)
        }
        val changedItems = collectChangedEntities(existingItemsById, targetItemsById)
        if (changedItems.isNotEmpty()) {
            upsertItems(changedItems)
        }
        val addedItemTags = (targetItemTags - existingItemTags).toList()
        if (addedItemTags.isNotEmpty()) {
            insertItemTags(addedItemTags)
        }
    }

    @Transaction
    suspend fun replaceSnapshotAtomically(
        feeds: List<FeedEntity>,
        tags: List<TagEntity>,
        items: List<ItemEntity>,
        itemTags: List<ItemTagEntity>,
    ) {
        reconcileSnapshotDiff(
            feeds = feeds,
            tags = tags,
            items = items,
            itemTags = itemTags,
        )
    }

    @Query("SELECT * FROM tags WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun getTagByName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFeeds(feeds: List<FeedEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFeed(feed: FeedEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<ItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertItem(item: ItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTags(tags: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTag(tag: TagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItemTag(itemTag: ItemTagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItemTags(itemTags: List<ItemTagEntity>)

    @Query("DELETE FROM item_tags WHERE itemId = :itemId AND tagId = :tagId")
    suspend fun deleteItemTag(itemId: String, tagId: String)

    @Query("SELECT COUNT(*) FROM item_tags WHERE itemId = :itemId AND tagId = :tagId")
    suspend fun hasItemTag(itemId: String, tagId: String): Int

    @Query(
        "UPDATE items SET status = :status, interactionUpdatedAt = :updatedAt WHERE id = :itemId"
    )
    suspend fun updateItemStatus(
        itemId: String,
        status: String,
        updatedAt: String =
            java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(),
    )

    @Query(
        "UPDATE items SET isFavorite = :isFavorite, interactionUpdatedAt = :updatedAt WHERE id = :itemId"
    )
    suspend fun updateItemFavorite(
        itemId: String,
        isFavorite: Boolean,
        updatedAt: String =
            java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(),
    )

    @Query("UPDATE items SET isCachedOffline = :isCachedOffline WHERE id = :itemId")
    suspend fun updateItemCachedOffline(itemId: String, isCachedOffline: Boolean)

    @Query(
        "UPDATE items SET readProgress = :progress, interactionUpdatedAt = :updatedAt WHERE id = :itemId"
    )
    suspend fun updateReadProgress(
        itemId: String,
        progress: Double,
        updatedAt: String =
            java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(),
    )

    @Query(
        "UPDATE items SET readAt = :readAt, interactionUpdatedAt = :updatedAt WHERE id = :itemId"
    )
    suspend fun updateItemReadAt(
        itemId: String,
        readAt: String?,
        updatedAt: String =
            java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(),
    )

    @Query(
        "UPDATE items SET status = :status, readAt = :readAt, interactionUpdatedAt = :updatedAt WHERE id = :itemId"
    )
    suspend fun updateItemStatusAndReadAt(
        itemId: String,
        status: String,
        readAt: String?,
        updatedAt: String =
            java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(),
    )

    @Query("UPDATE items SET interactionUpdatedAt = :updatedAt WHERE id = :itemId")
    suspend fun updateItemInteractionTimestamp(
        itemId: String,
        updatedAt: String =
            java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(),
    )
}

internal const val SQLITE_DELETE_CHUNK_SIZE = 500

internal fun truncateItemForStorage(item: ItemEntity): ItemEntity {
    val summaryLen = item.summary?.length ?: 0
    val textLen = item.contentText?.length ?: 0
    val htmlLen = item.contentHtml?.length ?: 0
    val withinBounds =
        summaryLen <= MAX_SQLITE_SUMMARY_CHARS &&
            textLen <= MAX_SQLITE_TEXT_CHARS &&
            htmlLen <= MAX_SQLITE_HTML_CHARS
    return if (withinBounds) {
        item
    } else {
        item.copy(
            summary = item.summary?.take(MAX_SQLITE_SUMMARY_CHARS),
            contentText = item.contentText?.take(MAX_SQLITE_TEXT_CHARS),
            contentHtml = item.contentHtml?.take(MAX_SQLITE_HTML_CHARS),
        )
    }
}

internal suspend fun deleteRemovedIdsInChunks(
    existingIds: Set<String>,
    targetIds: Set<String>,
    deleteChunk: suspend (List<String>) -> Unit,
) {
    val removedIds = existingIds - targetIds
    if (removedIds.isEmpty()) return
    removedIds.chunked(SQLITE_DELETE_CHUNK_SIZE).forEach { chunk -> deleteChunk(chunk) }
}

internal fun <T> collectChangedEntities(
    existingById: Map<String, T>,
    targetById: Map<String, T>,
): List<T> = targetById.mapNotNull { (id, target) -> target.takeIf { existingById[id] != it } }

package com.grayvines.roche.data.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.grayvines.roche.data.MAX_SQLITE_HTML_CHARS
import com.grayvines.roche.data.MAX_SQLITE_SUMMARY_CHARS
import com.grayvines.roche.data.MAX_SQLITE_TEXT_CHARS
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.formatArticleMinutes
import kotlinx.coroutines.flow.Flow

@Dao
interface RocheDao {
    @Query("SELECT * FROM feeds ORDER BY title COLLATE NOCASE ASC, title ASC")
    fun observeFeeds(): Flow<List<FeedEntity>>

    @Query("SELECT * FROM items ORDER BY COALESCE(publishedAt, createdAt) DESC")
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
        "SELECT items.id, items.feedId, items.feedTitle, items.title, items.url, items.author, " +
            "SUBSTR(COALESCE(ic.summary, items.summary), 1, 16384) AS summary, " +
            "SUBSTR(COALESCE(ic.contentText, items.contentText), 1, 262144) AS contentText, " +
            "SUBSTR(COALESCE(ic.contentHtml, items.contentHtml), 1, 512000) AS contentHtml, " +
            "items.contentType, items.status, items.isFavorite, items.isCachedOffline, " +
            "items.publishedAt, items.createdAt, items.readProgress, items.thumbnailMediaId, " +
            "items.duration, items.readAt, items.thumbnailUrl, items.feedUrl, " +
            "items.interactionUpdatedAt, items.audioUrl " +
            "FROM items LEFT JOIN item_contents ic ON items.id = ic.itemId " +
            "WHERE items.feedId = :feedId " +
            "OR (items.feedUrl IS NOT NULL AND LOWER(items.feedUrl) = LOWER(:feedUrl))"
    )
    suspend fun getItemsForFeed(feedId: String, feedUrl: String): List<ItemEntity>

    @Query(
        "SELECT items.id, items.feedId, items.feedTitle, items.title, items.url, items.author, " +
            "SUBSTR(COALESCE(ic.summary, items.summary), 1, 16384) AS summary, " +
            "SUBSTR(COALESCE(ic.contentText, items.contentText), 1, 262144) AS contentText, " +
            "SUBSTR(COALESCE(ic.contentHtml, items.contentHtml), 1, 512000) AS contentHtml, " +
            "items.contentType, items.status, items.isFavorite, items.isCachedOffline, " +
            "items.publishedAt, items.createdAt, items.readProgress, items.thumbnailMediaId, " +
            "items.duration, items.readAt, items.thumbnailUrl, items.feedUrl, " +
            "items.interactionUpdatedAt, items.audioUrl " +
            "FROM items LEFT JOIN item_contents ic ON items.id = ic.itemId"
    )
    suspend fun getAllItems(): List<ItemEntity>

    @Query(
        "SELECT items.id, items.feedId, items.feedTitle, items.title, items.url, items.author, " +
            "SUBSTR(COALESCE(ic.summary, items.summary), 1, 16384) AS summary, " +
            "SUBSTR(COALESCE(ic.contentText, items.contentText), 1, 262144) AS contentText, " +
            "SUBSTR(COALESCE(ic.contentHtml, items.contentHtml), 1, 512000) AS contentHtml, " +
            "items.contentType, items.status, items.isFavorite, items.isCachedOffline, " +
            "items.publishedAt, items.createdAt, items.readProgress, items.thumbnailMediaId, " +
            "items.duration, items.readAt, items.thumbnailUrl, items.feedUrl, " +
            "items.interactionUpdatedAt, items.audioUrl " +
            "FROM items LEFT JOIN item_contents ic ON items.id = ic.itemId " +
            "WHERE items.id = :id LIMIT 1"
    )
    suspend fun getItemById(id: String): ItemEntity?

    @Query(
        "SELECT id, status, isFavorite, isCachedOffline, contentType, readAt, readProgress, audioUrl " +
            "FROM items WHERE id = :id LIMIT 1"
    )
    suspend fun getItemStateById(id: String): ItemStateRow?

    @Query(
        "SELECT id, status, isFavorite, isCachedOffline, contentType, readAt, readProgress, audioUrl " +
            "FROM items WHERE status != 'archived'"
    )
    suspend fun getUnarchivedItemStates(): List<ItemStateRow>

    @Query(
        """SELECT items.id, items.feedId, items.feedTitle, items.title, items.url, items.author, """ +
            """SUBSTR(COALESCE(ic.summary, items.summary), 1, 16384) AS summary, """ +
            """SUBSTR(COALESCE(ic.contentText, items.contentText), 1, 65536) AS contentText, """ +
            """SUBSTR(COALESCE(ic.contentHtml, items.contentHtml), 1, 65536) AS contentHtml, """ +
            """items.contentType, items.status, items.isFavorite, items.isCachedOffline, """ +
            """items.publishedAt, items.createdAt, items.readProgress, items.thumbnailMediaId, """ +
            """items.duration, items.readAt, items.thumbnailUrl, items.feedUrl, """ +
            """items.interactionUpdatedAt, items.audioUrl """ +
            """FROM items LEFT JOIN item_contents ic ON items.id = ic.itemId """ +
            """WHERE items.title LIKE :likePattern ESCAPE '\' """ +
            """OR items.feedTitle LIKE :likePattern ESCAPE '\' """ +
            """OR items.author LIKE :likePattern ESCAPE '\' """ +
            """OR items.url LIKE :likePattern ESCAPE '\' """ +
            """OR COALESCE(ic.summary, items.summary) LIKE :likePattern ESCAPE '\' """ +
            """OR COALESCE(ic.contentText, items.contentText) LIKE :likePattern ESCAPE '\' """ +
            """OR COALESCE(ic.contentHtml, items.contentHtml) LIKE :likePattern ESCAPE '\' """ +
            """OR EXISTS (""" +
            """SELECT 1 FROM item_tags it INNER JOIN tags t ON t.id = it.tagId """ +
            """WHERE it.itemId = items.id AND t.name LIKE :likePattern ESCAPE '\'""" +
            """) """ +
            """ORDER BY COALESCE(items.publishedAt, items.createdAt) DESC"""
    )
    suspend fun searchFullItems(likePattern: String): List<ItemEntity>

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
    suspend fun insertRawItems(items: List<ItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertRawItem(item: ItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItemContents(contents: List<ItemContentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItemContent(content: ItemContentEntity)

    @Transaction
    suspend fun upsertItems(items: List<ItemEntity>) {
        if (items.isEmpty()) return
        val bounded = items.map(::truncateItemForStorage)
        insertRawItems(bounded.map(::truncateItemForInlinePreview))
        val extended = bounded.filter(::hasExtendedItemContent).map(::toItemContentEntity)
        if (extended.isNotEmpty()) {
            upsertItemContents(extended)
        }
    }

    @Transaction
    suspend fun upsertItem(item: ItemEntity) {
        val bounded = truncateItemForStorage(item)
        insertRawItem(truncateItemForInlinePreview(bounded))
        if (hasExtendedItemContent(bounded)) {
            upsertItemContent(toItemContentEntity(bounded))
        }
    }

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
internal const val LIST_PREVIEW_SUMMARY_CHARS = 1_024
internal const val LIST_PREVIEW_TEXT_CHARS = 2_048
internal const val LIST_PREVIEW_HTML_CHARS = 2_048

internal fun truncateItemForStorage(item: ItemEntity): ItemEntity {
    val boundedSummary = item.summary?.take(MAX_SQLITE_SUMMARY_CHARS)
    val boundedText = item.contentText?.take(MAX_SQLITE_TEXT_CHARS)
    val boundedHtml = item.contentHtml?.take(MAX_SQLITE_HTML_CHARS)
    val resolvedDuration =
        resolveStoredArticleDuration(item, boundedSummary, boundedText, boundedHtml)
    val unchanged =
        boundedSummary == item.summary &&
            boundedText == item.contentText &&
            boundedHtml == item.contentHtml &&
            resolvedDuration == item.duration
    return if (unchanged) {
        item
    } else {
        item.copy(
            summary = boundedSummary,
            contentText = boundedText,
            contentHtml = boundedHtml,
            duration = resolvedDuration,
        )
    }
}

private fun resolveStoredArticleDuration(
    item: ItemEntity,
    boundedSummary: String?,
    boundedText: String?,
    boundedHtml: String?,
): String? =
    if (
        ContentType.fromWireValue(item.contentType) == ContentType.ARTICLE &&
            hasExtendedItemContent(item)
    ) {
        formatArticleMinutes(boundedText ?: boundedHtml ?: boundedSummary.orEmpty())
    } else {
        item.duration
    }

internal fun hasExtendedItemContent(item: ItemEntity): Boolean =
    item.summary.orEmpty().length > LIST_PREVIEW_SUMMARY_CHARS ||
        item.contentText.orEmpty().length > LIST_PREVIEW_TEXT_CHARS ||
        item.contentHtml.orEmpty().length > LIST_PREVIEW_HTML_CHARS

internal fun truncateItemForInlinePreview(item: ItemEntity): ItemEntity =
    if (!hasExtendedItemContent(item)) {
        item
    } else {
        item.copy(
            summary = item.summary?.take(LIST_PREVIEW_SUMMARY_CHARS),
            contentText = item.contentText?.take(LIST_PREVIEW_TEXT_CHARS),
            contentHtml = item.contentHtml?.take(LIST_PREVIEW_HTML_CHARS),
        )
    }

internal fun toItemContentEntity(item: ItemEntity): ItemContentEntity =
    ItemContentEntity(
        itemId = item.id,
        summary = item.summary,
        contentText = item.contentText,
        contentHtml = item.contentHtml,
    )

internal fun buildSqlContainsPattern(rawQuery: String): String {
    val escaped =
        buildString(rawQuery.length + 2) {
            append('%')
            for (ch in rawQuery) {
                if (ch == '\\' || ch == '%' || ch == '_') {
                    append('\\')
                }
                append(ch)
            }
            append('%')
        }
    return escaped
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

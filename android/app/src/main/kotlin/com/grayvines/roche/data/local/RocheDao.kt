package com.grayvines.roche.data.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RocheDao {
    @Query("SELECT * FROM feeds ORDER BY title ASC") fun observeFeeds(): Flow<List<FeedEntity>>

    @Query("SELECT * FROM items ORDER BY COALESCE(publishedAt, createdAt) DESC")
    fun observeItems(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM tags ORDER BY name ASC") fun observeTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM item_tags") fun observeItemTags(): Flow<List<ItemTagEntity>>

    @Query("SELECT COUNT(*) FROM items") suspend fun countItems(): Int

    @Query("SELECT * FROM items") suspend fun getAllItems(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE id = :id LIMIT 1")
    suspend fun getItemById(id: String): ItemEntity?

    @Query("DELETE FROM items WHERE id = :id") suspend fun deleteItemById(id: String)

    @Query("DELETE FROM tags WHERE id = :id") suspend fun deleteTagById(id: String)

    @Query("DELETE FROM item_tags WHERE itemId = :itemId")
    suspend fun deleteItemTagsForItem(itemId: String)

    @Query("DELETE FROM item_tags") suspend fun clearAllItemTags()

    @Query("DELETE FROM items") suspend fun clearAllItems()

    @Query("DELETE FROM feeds") suspend fun clearAllFeeds()

    @Query("DELETE FROM tags") suspend fun clearAllTags()

    @Transaction
    suspend fun replaceSnapshotAtomically(
        feeds: List<FeedEntity>,
        tags: List<TagEntity>,
        items: List<ItemEntity>,
        itemTags: List<ItemTagEntity>,
    ) {
        clearAllItemTags()
        clearAllItems()
        clearAllFeeds()
        clearAllTags()
        upsertFeeds(feeds)
        upsertTags(tags)
        upsertItems(items)
        insertItemTags(itemTags)
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

    @Query("UPDATE items SET status = :status WHERE id = :itemId")
    suspend fun updateItemStatus(itemId: String, status: String)

    @Query("UPDATE items SET isFavorite = :isFavorite WHERE id = :itemId")
    suspend fun updateItemFavorite(itemId: String, isFavorite: Boolean)

    @Query("UPDATE items SET isCachedOffline = :isCachedOffline WHERE id = :itemId")
    suspend fun updateItemCachedOffline(itemId: String, isCachedOffline: Boolean)

    @Query("UPDATE items SET readProgress = :progress WHERE id = :itemId")
    suspend fun updateReadProgress(itemId: String, progress: Double)
}

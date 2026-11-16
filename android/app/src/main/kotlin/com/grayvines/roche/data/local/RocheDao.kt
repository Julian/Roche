@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
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

    @Query("SELECT id FROM items WHERE feedId = :feedId")
    suspend fun getItemIdsForFeed(feedId: String): List<String>

    @Query(
        "SELECT id FROM items WHERE feedId IN (:feedIds) AND status != 'archived' " +
            "AND (readAt IS NULL OR readAt = '') AND isCachedOffline = 0 " +
            "AND (LOWER(contentType) = 'audio' OR (audioUrl IS NOT NULL AND audioUrl != ''))"
    )
    suspend fun getUncachedUnreadAudioItemIds(feedIds: List<String>): List<String>

    @Query("SELECT * FROM items WHERE id IN (:ids)")
    suspend fun getPreviewItemsByIds(ids: List<String>): List<ItemEntity>

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
        "SELECT items.id, items.feedId, items.feedTitle, items.title, items.url, items.author, " +
            "SUBSTR(COALESCE(ic.summary, items.summary), 1, 16384) AS summary, " +
            "SUBSTR(COALESCE(ic.contentText, items.contentText), 1, 65536) AS contentText, " +
            "SUBSTR(COALESCE(ic.contentHtml, items.contentHtml), 1, 65536) AS contentHtml, " +
            "items.contentType, items.status, items.isFavorite, items.isCachedOffline, " +
            "items.publishedAt, items.createdAt, items.readProgress, items.thumbnailMediaId, " +
            "items.duration, items.readAt, items.thumbnailUrl, items.feedUrl, " +
            "items.interactionUpdatedAt, items.audioUrl " +
            "FROM items LEFT JOIN item_contents ic ON items.id = ic.itemId " +
            "WHERE items.rowid IN (" +
            "SELECT rowid FROM items_fts WHERE items_fts MATCH :tokenMatchQuery" +
            ") " +
            "OR items.id IN (" +
            "SELECT ic2.itemId FROM item_contents ic2 " +
            "INNER JOIN item_contents_fts ON ic2.rowid = item_contents_fts.rowid " +
            "WHERE item_contents_fts MATCH :tokenMatchQuery" +
            ") " +
            "OR EXISTS (" +
            "SELECT 1 FROM item_tags it INNER JOIN tags t ON t.id = it.tagId " +
            "WHERE it.itemId = items.id AND t.name LIKE :likePattern ESCAPE '\\'" +
            ") " +
            "ORDER BY COALESCE(items.publishedAt, items.createdAt) DESC"
    )
    suspend fun searchSingleTokenFullItems(
        tokenMatchQuery: String,
        likePattern: String,
    ): List<ItemEntity>

    @Query(
        "SELECT items.id FROM items " +
            "INNER JOIN items_fts ON items.rowid = items_fts.rowid " +
            "WHERE items_fts MATCH :tokenMatchQuery " +
            "UNION " +
            "SELECT ic.itemId FROM item_contents ic " +
            "INNER JOIN item_contents_fts ON ic.rowid = item_contents_fts.rowid " +
            "WHERE item_contents_fts MATCH :tokenMatchQuery"
    )
    suspend fun matchItemIdsForFtsToken(tokenMatchQuery: String): List<String>

    @Query(
        "SELECT DISTINCT it.itemId FROM item_tags it " +
            "INNER JOIN tags t ON t.id = it.tagId " +
            "WHERE t.name LIKE :likePattern ESCAPE '\\'"
    )
    suspend fun matchItemIdsByTagLike(likePattern: String): List<String>

    @Query(
        "SELECT items.id, items.feedId, items.feedTitle, items.title, items.url, items.author, " +
            "SUBSTR(COALESCE(ic.summary, items.summary), 1, 16384) AS summary, " +
            "SUBSTR(COALESCE(ic.contentText, items.contentText), 1, 65536) AS contentText, " +
            "SUBSTR(COALESCE(ic.contentHtml, items.contentHtml), 1, 65536) AS contentHtml, " +
            "items.contentType, items.status, items.isFavorite, items.isCachedOffline, " +
            "items.publishedAt, items.createdAt, items.readProgress, items.thumbnailMediaId, " +
            "items.duration, items.readAt, items.thumbnailUrl, items.feedUrl, " +
            "items.interactionUpdatedAt, items.audioUrl " +
            "FROM items LEFT JOIN item_contents ic ON items.id = ic.itemId " +
            "WHERE items.id IN (:ids) " +
            "ORDER BY COALESCE(items.publishedAt, items.createdAt) DESC"
    )
    suspend fun getSearchItemsByIdsChunk(ids: List<String>): List<ItemEntity>

    @Transaction
    suspend fun searchFullItemsByTokens(
        tokenQueries: List<String>,
        likePattern: String,
    ): List<ItemEntity> =
        when (tokenQueries.size) {
            0 -> searchItemsByTagOnly(likePattern)
            1 -> searchSingleTokenFullItems(tokenQueries.single(), likePattern)
            else -> executeMultiTokenSearch(tokenQueries, likePattern)
        }

    @Query(
        "SELECT items.id, items.feedId, items.feedTitle, items.title, items.url, items.author, " +
            "SUBSTR(COALESCE(ic.summary, items.summary), 1, 16384) AS summary, " +
            "SUBSTR(COALESCE(ic.contentText, items.contentText), 1, 65536) AS contentText, " +
            "SUBSTR(COALESCE(ic.contentHtml, items.contentHtml), 1, 65536) AS contentHtml, " +
            "items.contentType, items.status, items.isFavorite, items.isCachedOffline, " +
            "items.publishedAt, items.createdAt, items.readProgress, items.thumbnailMediaId, " +
            "items.duration, items.readAt, items.thumbnailUrl, items.feedUrl, " +
            "items.interactionUpdatedAt, items.audioUrl " +
            "FROM items LEFT JOIN item_contents ic ON items.id = ic.itemId " +
            "WHERE EXISTS (" +
            "SELECT 1 FROM item_tags it INNER JOIN tags t ON t.id = it.tagId " +
            "WHERE it.itemId = items.id AND t.name LIKE :likePattern ESCAPE '\\'" +
            ") " +
            "ORDER BY COALESCE(items.publishedAt, items.createdAt) DESC"
    )
    suspend fun searchItemsByTagOnly(likePattern: String): List<ItemEntity>

    @Query("DELETE FROM items WHERE id = :id") suspend fun deleteItemById(id: String)

    @Query("DELETE FROM tags WHERE id = :id") suspend fun deleteTagById(id: String)

    @Query("DELETE FROM item_tags WHERE itemId = :itemId")
    suspend fun deleteItemTagsForItem(itemId: String)

    @Query("DELETE FROM feeds WHERE id IN (:ids)")
    suspend fun deleteRawFeedsByIds(ids: List<String>)

    @Transaction
    suspend fun deleteFeedsByIds(ids: List<String>) {
        if (ids.isEmpty()) return
        ids.chunked(SQLITE_DELETE_CHUNK_SIZE).forEach { chunk -> deleteRawFeedsByIds(chunk) }
    }

    @Query("DELETE FROM tags WHERE id IN (:ids)") suspend fun deleteTagsByIds(ids: List<String>)

    @Query("DELETE FROM items WHERE id IN (:ids)")
    suspend fun deleteRawItemsByIds(ids: List<String>)

    @Transaction
    suspend fun deleteItemsByIds(ids: List<String>) {
        if (ids.isEmpty()) return
        ids.chunked(SQLITE_DELETE_CHUNK_SIZE).forEach { chunk -> deleteRawItemsByIds(chunk) }
    }

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
    ) = executeReconcileSnapshotDiff(feeds, tags, items, itemTags)

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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRawItemsIgnore(items: List<ItemEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRawItemIgnore(item: ItemEntity): Long

    @Update suspend fun updateRawItems(items: List<ItemEntity>)

    @Update suspend fun updateRawItem(item: ItemEntity)

    @Transaction
    suspend fun insertRawItems(items: List<ItemEntity>) {
        if (items.isEmpty()) return
        val rowIds = insertRawItemsIgnore(items)
        val existing = items.filterIndexed { index, _ -> rowIds[index] == -1L }
        if (existing.isNotEmpty()) {
            updateRawItems(existing)
        }
    }

    @Transaction
    suspend fun insertRawItem(item: ItemEntity) {
        if (insertRawItemIgnore(item) == -1L) {
            updateRawItem(item)
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItemContentsIgnore(contents: List<ItemContentEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItemContentIgnore(content: ItemContentEntity): Long

    @Update suspend fun updateItemContents(contents: List<ItemContentEntity>)

    @Update suspend fun updateItemContent(content: ItemContentEntity)

    @Transaction
    suspend fun upsertItemContents(contents: List<ItemContentEntity>) {
        if (contents.isEmpty()) return
        val rowIds = insertItemContentsIgnore(contents)
        val existing = contents.filterIndexed { index, _ -> rowIds[index] == -1L }
        if (existing.isNotEmpty()) {
            updateItemContents(existing)
        }
    }

    @Transaction
    suspend fun upsertItemContent(content: ItemContentEntity) {
        if (insertItemContentIgnore(content) == -1L) {
            updateItemContent(content)
        }
    }

    @Query("DELETE FROM item_contents WHERE itemId IN (:itemIds)")
    suspend fun deleteItemContentsByIds(itemIds: List<String>)

    @Query("DELETE FROM item_contents WHERE itemId = :itemId")
    suspend fun deleteItemContentById(itemId: String)

    @Transaction
    suspend fun executeInTransaction(block: suspend () -> Unit) {
        block()
    }

    @Transaction suspend fun upsertItems(items: List<ItemEntity>) = executeUpsertItems(items)

    @Transaction
    suspend fun upsertItem(item: ItemEntity) {
        val bounded = truncateItemForStorage(item)
        insertRawItem(truncateItemForInlinePreview(bounded))
        if (hasExtendedItemContent(bounded)) {
            upsertItemContent(toItemContentEntity(bounded))
        } else {
            deleteItemContentById(bounded.id)
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

    @Query("UPDATE items SET interactionUpdatedAt = :updatedAt WHERE id IN (:itemIds)")
    suspend fun updateItemsInteractionAt(itemIds: List<String>, updatedAt: String)

    @Query("SELECT * FROM catalog_entries")
    fun observeCatalogEntries(): Flow<List<CatalogEntryEntity>>

    @Query("SELECT * FROM entry_identifiers")
    fun observeEntryIdentifiers(): Flow<List<EntryIdentifierEntity>>

    @Query("SELECT * FROM collection_specs")
    fun observeCollectionSpecs(): Flow<List<CollectionSpecEntity>>

    @Query("SELECT * FROM collection_entries")
    fun observeCollectionEntries(): Flow<List<CollectionEntryEntity>>

    @Query("SELECT * FROM collection_generators")
    fun observeCollectionGenerators(): Flow<List<CollectionGeneratorEntity>>

    @Query("SELECT * FROM consumption_log ORDER BY ts DESC")
    fun observeConsumptionLogs(): Flow<List<ConsumptionLogEntity>>

    @Query("SELECT * FROM comparisons ORDER BY ts ASC")
    fun observeComparisons(): Flow<List<ComparisonEntity>>

    @Query("SELECT * FROM catalog_entries")
    suspend fun getAllCatalogEntries(): List<CatalogEntryEntity>

    @Query("SELECT * FROM catalog_entries WHERE id = :id LIMIT 1")
    suspend fun getCatalogEntryById(id: String): CatalogEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCatalogEntry(entry: CatalogEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCatalogEntries(entries: List<CatalogEntryEntity>)

    @Query("UPDATE catalog_entries SET isLocalDirty = 0 WHERE id IN (:ids)")
    suspend fun clearCatalogEntriesDirty(ids: List<String>)

    @Query("UPDATE catalog_entries SET isLocalDirty = 1 WHERE id IN (:ids)")
    suspend fun markCatalogEntriesDirty(ids: List<String>)

    @Query(
        "UPDATE catalog_entries SET isLocalDirty = 0 WHERE id = :id AND updatedAt = :expectedUpdatedAt"
    )
    suspend fun clearEntryDirtyIfSameTs(id: String, expectedUpdatedAt: String)

    @Query("SELECT * FROM entry_identifiers")
    suspend fun getAllEntryIdentifiers(): List<EntryIdentifierEntity>

    @Query(
        "SELECT entryId FROM entry_identifiers " +
            "WHERE scheme = :scheme AND value = :value LIMIT 1"
    )
    suspend fun getEntryIdByIdentifier(scheme: String, value: String): String?

    @Query(
        "SELECT * FROM entry_identifiers WHERE entryId = :entryId ORDER BY scheme ASC, value ASC"
    )
    suspend fun getIdentifiersForEntry(entryId: String): List<EntryIdentifierEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntryIdentifierIgnore(identifier: EntryIdentifierEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntryIdentifiersIgnore(identifiers: List<EntryIdentifierEntity>)

    @Query("UPDATE entry_identifiers SET entryId = :newEntryId WHERE entryId = :oldEntryId")
    suspend fun repointIdentifiersToEntry(oldEntryId: String, newEntryId: String)

    @Query("SELECT * FROM collection_specs ORDER BY title COLLATE NOCASE ASC")
    suspend fun getAllCollectionSpecs(): List<CollectionSpecEntity>

    @Query("SELECT * FROM collection_specs WHERE collectionId = :id LIMIT 1")
    suspend fun getCollectionSpecById(id: String): CollectionSpecEntity?

    @Query("SELECT * FROM collection_specs WHERE LOWER(slug) = LOWER(:slug) LIMIT 1")
    suspend fun getCollectionSpecBySlug(slug: String): CollectionSpecEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollectionSpec(spec: CollectionSpecEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollectionSpecs(specs: List<CollectionSpecEntity>)

    @Query("UPDATE collection_specs SET isLocalDirty = 0 WHERE collectionId IN (:ids)")
    suspend fun clearCollectionSpecsDirty(ids: List<String>)

    @Query("UPDATE collection_specs SET isLocalDirty = 1 WHERE collectionId IN (:ids)")
    suspend fun markCollectionSpecsDirty(ids: List<String>)

    @Query(
        "UPDATE collection_specs SET isLocalDirty = 0 " +
            "WHERE collectionId = :id AND updatedAt = :expectedUpdatedAt"
    )
    suspend fun clearSpecDirtyIfSameTs(id: String, expectedUpdatedAt: String)

    @Query("SELECT * FROM collection_entries")
    suspend fun getAllCollectionEntries(): List<CollectionEntryEntity>

    @Query(
        "SELECT * FROM collection_entries WHERE collectionId = :collectionId " +
            "ORDER BY COALESCE(position, 1e12) ASC, addedAt ASC"
    )
    suspend fun getCollectionMemberships(collectionId: String): List<CollectionEntryEntity>

    @Query("SELECT * FROM collection_entries WHERE entryId = :entryId")
    suspend fun getCollectionEntriesForEntry(entryId: String): List<CollectionEntryEntity>

    @Query("SELECT COUNT(*) FROM collection_entries WHERE collectionId = :collectionId")
    suspend fun countCollectionEntries(collectionId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollectionEntry(entry: CollectionEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollectionEntries(entries: List<CollectionEntryEntity>)

    @Query(
        "DELETE FROM collection_entries " +
            "WHERE collectionId = :collectionId AND entryId = :entryId"
    )
    suspend fun deleteCollectionEntry(collectionId: String, entryId: String)

    @Query(
        "UPDATE collection_entries SET isLocalDirty = 0 " +
            "WHERE collectionId = :collectionId AND entryId = :entryId"
    )
    suspend fun clearCollectionEntryDirty(collectionId: String, entryId: String)

    @Query(
        "UPDATE collection_entries SET isLocalDirty = 1 " +
            "WHERE collectionId = :collectionId AND entryId = :entryId"
    )
    suspend fun markCollectionEntryDirty(collectionId: String, entryId: String)

    @Query(
        "UPDATE collection_entries SET isLocalDirty = 0 " +
            "WHERE collectionId = :collectionId AND entryId = :entryId " +
            "AND addedAt = :expectedAddedAt"
    )
    suspend fun clearMemberDirtyIfSameTs(
        collectionId: String,
        entryId: String,
        expectedAddedAt: String,
    )

    @Query("SELECT * FROM collection_generators")
    suspend fun getAllCollectionGenerators(): List<CollectionGeneratorEntity>

    @Query("SELECT * FROM collection_generators WHERE collectionId = :collectionId LIMIT 1")
    suspend fun getCollectionGenerator(collectionId: String): CollectionGeneratorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollectionGenerator(generator: CollectionGeneratorEntity)

    @Query("UPDATE collection_generators SET isLocalDirty = 0 WHERE collectionId = :collectionId")
    suspend fun clearCollectionGeneratorDirty(collectionId: String)

    @Query(
        "UPDATE collection_generators SET isLocalDirty = 0 " +
            "WHERE collectionId = :collectionId AND updatedAt = :expectedUpdatedAt"
    )
    suspend fun clearGeneratorDirtyIfSameTs(
        collectionId: String,
        expectedUpdatedAt: String,
    )

    @Query("SELECT * FROM consumption_log ORDER BY ts DESC")
    suspend fun getAllConsumptionLogs(): List<ConsumptionLogEntity>

    @Query("SELECT * FROM consumption_log WHERE entryId = :entryId ORDER BY ts DESC")
    suspend fun getConsumptionLogsForEntry(entryId: String): List<ConsumptionLogEntity>

    @Query(
        "SELECT COUNT(*) FROM consumption_log " +
            "WHERE entryId = :entryId AND status IN ('consumed', 'abandoned')"
    )
    suspend fun countTerminalLogsForEntry(entryId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConsumptionLogIgnore(log: ConsumptionLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConsumptionLog(log: ConsumptionLogEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConsumptionLogsIgnore(logs: List<ConsumptionLogEntity>)

    @Query("UPDATE consumption_log SET isLocalDirty = 0 WHERE id IN (:ids)")
    suspend fun clearConsumptionLogsDirty(ids: List<String>)

    @Query("UPDATE consumption_log SET isLocalDirty = 1 WHERE id IN (:ids)")
    suspend fun markConsumptionLogsDirty(ids: List<String>)

    @Query("DELETE FROM consumption_log WHERE id = :id")
    suspend fun deleteConsumptionLogById(id: String)

    @Query("UPDATE consumption_log SET entryId = :canonicalId WHERE entryId = :dupId")
    suspend fun repointConsumptionLogsToEntry(dupId: String, canonicalId: String)

    @Query("DELETE FROM collection_entries WHERE entryId = :dupId OR collectionId = :dupId")
    suspend fun deleteMergedDupMemberships(dupId: String)

    @Query("SELECT * FROM comparisons ORDER BY ts ASC")
    suspend fun getAllComparisons(): List<ComparisonEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertComparisonIgnore(comparison: ComparisonEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertComparisonsIgnore(comparisons: List<ComparisonEntity>)

    @Query("UPDATE comparisons SET isLocalDirty = 0 WHERE id IN (:ids)")
    suspend fun clearComparisonsDirty(ids: List<String>)

    @Query("UPDATE comparisons SET isLocalDirty = 1 WHERE id IN (:ids)")
    suspend fun markComparisonsDirty(ids: List<String>)

    @Query(
        "UPDATE comparisons SET scopeCollectionId = :canonicalId WHERE scopeCollectionId = :dupId"
    )
    suspend fun repointComparisonScope(dupId: String, canonicalId: String)

    @Query("UPDATE comparisons SET orderingJson = :orderingJson WHERE id = :id")
    suspend fun updateComparisonOrdering(id: String, orderingJson: String)

    @Query("DELETE FROM comparisons WHERE id = :id") suspend fun deleteComparisonById(id: String)

    @Query("SELECT flagValue FROM local_sync_flags WHERE flagKey = :key LIMIT 1")
    suspend fun getLocalSyncFlag(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocalSyncFlag(flag: LocalSyncFlagEntity)

    @Query("DELETE FROM local_sync_flags WHERE flagKey = :key")
    suspend fun deleteLocalSyncFlag(key: String)
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

private val SYNTHETIC_ARTICLE_DURATION_REGEX = Regex("""^\d+m$""")

private fun resolveStoredArticleDuration(
    item: ItemEntity,
    boundedSummary: String?,
    boundedText: String?,
    boundedHtml: String?,
): String? {
    if (ContentType.fromWireValue(item.contentType) != ContentType.ARTICLE) {
        return item.duration
    }
    val existing = item.duration?.trim()?.takeIf { it.isNotEmpty() }
    val hasExplicitUpstreamDuration =
        existing != null && !SYNTHETIC_ARTICLE_DURATION_REGEX.matches(existing)
    if (hasExplicitUpstreamDuration) {
        return item.duration
    }
    if (!hasExtendedItemContent(item)) {
        return null
    }
    val textSource = boundedText ?: boundedHtml ?: boundedSummary.orEmpty()
    return if (textSource.isNotBlank()) formatArticleMinutes(textSource) else null
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

internal const val MAX_FTS_QUERY_TOKENS = 16
private const val FTS_SEARCHABLE_COLUMNS_PREFIX =
    "{title feedTitle author url summary contentText contentHtml} : "

internal fun extractFtsTokenQueries(rawQuery: String): List<String> {
    val sanitized = rawQuery.replace("\u0000", "").trim()
    if (sanitized.isBlank()) return emptyList()
    val tokens = ArrayList<String>()
    val current = StringBuilder()
    var hasCoreAlphaNum = false
    var index = 0
    while (index < sanitized.length) {
        val cp = sanitized.codePointAt(index)
        if (isFtsTokenCodePoint(cp)) {
            current.appendCodePoint(cp)
            hasCoreAlphaNum = hasCoreAlphaNum || isCoreFtsAlphaNum(cp)
        } else {
            flushFtsToken(current, hasCoreAlphaNum, tokens)
            hasCoreAlphaNum = false
        }
        index += Character.charCount(cp)
    }
    flushFtsToken(current, hasCoreAlphaNum, tokens)
    return tokens.take(MAX_FTS_QUERY_TOKENS).map { token ->
        val escaped = token.replace("\"", "\"\"")
        "\"$escaped\"*"
    }
}

internal fun buildFts5MatchQuery(rawQuery: String): String? {
    val tokenQueries = extractFtsTokenQueries(rawQuery)
    return if (tokenQueries.isEmpty()) {
        null
    } else {
        "$FTS_SEARCHABLE_COLUMNS_PREFIX(${tokenQueries.joinToString(separator = " ")})"
    }
}

private fun flushFtsToken(
    current: StringBuilder,
    hasCoreAlphaNum: Boolean,
    tokens: MutableList<String>,
) {
    if (current.isNotEmpty()) {
        if (hasCoreAlphaNum) {
            tokens.add(current.toString())
        }
        current.setLength(0)
    }
}

private fun isCoreFtsAlphaNum(cp: Int): Boolean =
    Character.isLetterOrDigit(cp) ||
        when (Character.getType(cp).toByte()) {
            Character.LETTER_NUMBER,
            Character.OTHER_NUMBER -> true
            else -> false
        }

private fun isFtsTokenCodePoint(cp: Int): Boolean =
    isCoreFtsAlphaNum(cp) ||
        when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK,
            Character.COMBINING_SPACING_MARK,
            Character.ENCLOSING_MARK -> true
            else -> false
        }

internal fun buildSqlContainsPattern(rawQuery: String): String {
    val sanitized = rawQuery.replace("\u0000", "").trim()
    val escaped =
        buildString(sanitized.length + 2) {
            append('%')
            for (ch in sanitized) {
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

internal suspend fun RocheDao.executeMultiTokenSearch(
    tokenQueries: List<String>,
    likePattern: String,
): List<ItemEntity> {
    val orderedTokens = tokenQueries.distinct().sortedByDescending { it.length }
    val matchedIds = matchItemIdsForFtsToken(orderedTokens.first()).toMutableSet()
    for (index in 1 until orderedTokens.size) {
        if (matchedIds.isEmpty()) break
        matchedIds.retainAll(matchItemIdsForFtsToken(orderedTokens[index]).toHashSet())
    }
    matchedIds.addAll(matchItemIdsByTagLike(likePattern))
    if (matchedIds.isEmpty()) return emptyList()
    val chunks = matchedIds.chunked(SQLITE_DELETE_CHUNK_SIZE)
    return if (chunks.size == 1) {
        getSearchItemsByIdsChunk(chunks.single())
    } else {
        chunks
            .flatMap { chunk -> getSearchItemsByIdsChunk(chunk) }
            .sortedByDescending { it.publishedAt ?: it.createdAt }
    }
}

internal suspend fun RocheDao.executeUpsertItems(items: List<ItemEntity>) {
    if (items.isEmpty()) return
    val bounded = items.associateBy { it.id }.values.map(::truncateItemForStorage)
    insertRawItems(bounded.map(::truncateItemForInlinePreview))
    val (extended, inlineOnly) = bounded.partition(::hasExtendedItemContent)
    if (extended.isNotEmpty()) {
        upsertItemContents(extended.map(::toItemContentEntity))
    }
    if (inlineOnly.isNotEmpty()) {
        inlineOnly
            .map { it.id }
            .chunked(SQLITE_DELETE_CHUNK_SIZE)
            .forEach { chunk -> deleteItemContentsByIds(chunk) }
    }
}

internal suspend fun RocheDao.executeReconcileSnapshotDiff(
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
    val targetItemsById = items.map(::truncateItemForStorage).associateBy { it.id }
    val targetItemTags = itemTags.toSet()

    (existingItemTags - targetItemTags).forEach { removed ->
        deleteItemTag(itemId = removed.itemId, tagId = removed.tagId)
    }
    deleteRemovedIdsInChunks(existingItemsById.keys, targetItemsById.keys, ::deleteItemsByIds)
    deleteRemovedIdsInChunks(existingFeedsById.keys, targetFeedsById.keys, ::deleteFeedsByIds)
    deleteRemovedIdsInChunks(existingTagsById.keys, targetTagsById.keys, ::deleteTagsByIds)

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

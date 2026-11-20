@file:Suppress("TooManyFunctions", "StringLiteralDuplication")

package com.grayvines.roche.data.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.UP_NEXT_COLLECTION_ID
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import kotlinx.coroutines.flow.Flow

private const val FEED_ID_SUBQUERY =
    "(SELECT ce_f.collectionId FROM collection_entries ce_f " +
        "LEFT JOIN collection_specs cs_f ON cs_f.collectionId = ce_f.collectionId " +
        "WHERE ce_f.entryId = e.id " +
        "AND ce_f.collectionId NOT IN (" +
        "'$STARRED_COLLECTION_ID', " +
        "'$UP_NEXT_COLLECTION_ID', " +
        "'$WANT_TO_READ_COLLECTION_ID'" +
        ") " +
        "AND (EXISTS (SELECT 1 FROM feed_sources fs_x WHERE fs_x.collectionId = ce_f.collectionId) " +
        "OR cs_f.role = 'feed' " +
        "OR (cs_f.collectionId IS NULL AND NOT EXISTS (" +
        "SELECT 1 FROM entries e_f WHERE e_f.id = ce_f.collectionId " +
        "AND e_f.kind NOT IN ('publication', 'podcast', 'channel', 'collection')" +
        "))) " +
        "ORDER BY ce_f.addedAt ASC, ce_f.collectionId ASC LIMIT 1)"

private const val FEED_URL_SUBQUERY =
    "(SELECT fs_f.feedUrl FROM collection_entries ce_f " +
        "INNER JOIN feed_sources fs_f ON fs_f.collectionId = ce_f.collectionId " +
        "WHERE ce_f.entryId = e.id " +
        "ORDER BY ce_f.addedAt ASC, ce_f.collectionId ASC LIMIT 1)"

private const val FEED_TITLE_EXPR =
    "COALESCE((" +
        "SELECT COALESCE(NULLIF(fe.title, ''), NULLIF(fs_f.feedTitle, ''), fs_f.feedUrl) " +
        "FROM collection_entries ce_f " +
        "INNER JOIN feed_sources fs_f ON fs_f.collectionId = ce_f.collectionId " +
        "INNER JOIN entries fe ON fe.id = fs_f.collectionId " +
        "WHERE ce_f.entryId = e.id " +
        "ORDER BY ce_f.addedAt ASC, ce_f.collectionId ASC LIMIT 1" +
        "), NULLIF(ec.fallbackFeedTitle, ''), 'Saved Link')"

private const val AUTHOR_SUBQUERY =
    "(SELECT GROUP_CONCAT(value, ', ') FROM json_each(" +
        "CASE WHEN json_valid(e.creatorsJson) THEN e.creatorsJson ELSE '[]' END))"

private const val IS_FAVORITE_EXPR =
    "EXISTS (SELECT 1 FROM collection_entries ce_fav " +
        "WHERE ce_fav.collectionId = '$STARRED_COLLECTION_ID' " +
        "AND ce_fav.entryId = e.id)"

private const val STATUS_EXPR =
    "CASE WHEN es.inboxState = 'dismissed' " +
        "OR (COALESCE(es.inboxState, 'none') = 'none' AND es.readAt IS NOT NULL AND NOT EXISTS (" +
        "SELECT 1 FROM collection_entries ce_wtr " +
        "WHERE ce_wtr.collectionId = '$WANT_TO_READ_COLLECTION_ID' " +
        "AND ce_wtr.entryId = e.id)) " +
        "THEN 'archived' ELSE 'unread' END"

private const val CONTENT_TYPE_EXPR =
    "COALESCE(ec.contentType, CASE e.kind " +
        "WHEN 'video' THEN 'video' " +
        "WHEN 'paper' THEN 'paper' " +
        "WHEN 'podcast_episode' THEN 'audio' " +
        "ELSE 'article' END)"

private const val IS_LIVE_ITEM_WHERE =
    "e.mergedIntoId IS NULL " +
        "AND COALESCE(es.inboxState, 'none') != 'deleted' " +
        "AND (es.inboxState IN ('unread', 'dismissed') " +
        "OR ec.entryId IS NOT NULL " +
        "OR (e.kind IN ('article', 'paper', 'video', 'podcast_episode') " +
        "AND EXISTS (SELECT 1 FROM collection_entries ce_li " +
        "INNER JOIN collection_specs cs_li ON cs_li.collectionId = ce_li.collectionId " +
        "WHERE ce_li.entryId = e.id AND cs_li.role IN ('want', 'list'))))"

private const val INBOX_FROM_WHERE =
    "FROM entries e " +
        "LEFT JOIN entry_state es ON es.entryId = e.id " +
        "LEFT JOIN entry_content ec ON ec.entryId = e.id " +
        "WHERE $IS_LIVE_ITEM_WHERE"

private const val INBOX_SELECT_LIST_PREFIX =
    "SELECT e.id AS id, " +
        "$FEED_ID_SUBQUERY AS feedId, " +
        "$FEED_TITLE_EXPR AS feedTitle, " +
        "e.title AS title, " +
        "COALESCE(e.url, '') AS url, " +
        "$AUTHOR_SUBQUERY AS author, " +
        "SUBSTR(e.summary, 1, 1024) AS summary, " +
        "SUBSTR(ec.contentText, 1, 2048) AS contentText, " +
        "SUBSTR(ec.contentHtml, 1, 2048) AS contentHtml, " +
        "$CONTENT_TYPE_EXPR AS contentType, " +
        "$STATUS_EXPR AS status, " +
        "$IS_FAVORITE_EXPR AS isFavorite, " +
        "COALESCE(es.isCachedOffline, 0) AS isCachedOffline, " +
        "e.publishedAt AS publishedAt, " +
        "e.createdAt AS createdAt, " +
        "COALESCE(es.progress, 0.0) AS readProgress, " +
        "ec.thumbnailMediaId AS thumbnailMediaId, " +
        "ec.duration AS duration, " +
        "es.readAt AS readAt, " +
        "e.thumbnailUrl AS thumbnailUrl, " +
        "$FEED_URL_SUBQUERY AS feedUrl, " +
        "es.interactionUpdatedAt AS interactionUpdatedAt, " +
        "ec.audioUrl AS audioUrl, " +
        "COALESCE(es.inboxState, 'none') AS inboxState " +
        INBOX_FROM_WHERE

private const val INBOX_SELECT_FULL_PREFIX =
    "SELECT e.id AS id, " +
        "$FEED_ID_SUBQUERY AS feedId, " +
        "$FEED_TITLE_EXPR AS feedTitle, " +
        "e.title AS title, " +
        "COALESCE(e.url, '') AS url, " +
        "$AUTHOR_SUBQUERY AS author, " +
        "SUBSTR(e.summary, 1, 16384) AS summary, " +
        "SUBSTR(ec.contentText, 1, 262144) AS contentText, " +
        "SUBSTR(ec.contentHtml, 1, 512000) AS contentHtml, " +
        "$CONTENT_TYPE_EXPR AS contentType, " +
        "$STATUS_EXPR AS status, " +
        "$IS_FAVORITE_EXPR AS isFavorite, " +
        "COALESCE(es.isCachedOffline, 0) AS isCachedOffline, " +
        "e.publishedAt AS publishedAt, " +
        "e.createdAt AS createdAt, " +
        "COALESCE(es.progress, 0.0) AS readProgress, " +
        "ec.thumbnailMediaId AS thumbnailMediaId, " +
        "ec.duration AS duration, " +
        "es.readAt AS readAt, " +
        "e.thumbnailUrl AS thumbnailUrl, " +
        "$FEED_URL_SUBQUERY AS feedUrl, " +
        "es.interactionUpdatedAt AS interactionUpdatedAt, " +
        "ec.audioUrl AS audioUrl, " +
        "COALESCE(es.inboxState, 'none') AS inboxState " +
        INBOX_FROM_WHERE

private const val INBOX_SELECT_SEARCH_PREFIX =
    "SELECT e.id AS id, " +
        "$FEED_ID_SUBQUERY AS feedId, " +
        "$FEED_TITLE_EXPR AS feedTitle, " +
        "e.title AS title, " +
        "COALESCE(e.url, '') AS url, " +
        "$AUTHOR_SUBQUERY AS author, " +
        "SUBSTR(e.summary, 1, 16384) AS summary, " +
        "SUBSTR(ec.contentText, 1, 65536) AS contentText, " +
        "SUBSTR(ec.contentHtml, 1, 65536) AS contentHtml, " +
        "$CONTENT_TYPE_EXPR AS contentType, " +
        "$STATUS_EXPR AS status, " +
        "$IS_FAVORITE_EXPR AS isFavorite, " +
        "COALESCE(es.isCachedOffline, 0) AS isCachedOffline, " +
        "e.publishedAt AS publishedAt, " +
        "e.createdAt AS createdAt, " +
        "COALESCE(es.progress, 0.0) AS readProgress, " +
        "ec.thumbnailMediaId AS thumbnailMediaId, " +
        "ec.duration AS duration, " +
        "es.readAt AS readAt, " +
        "e.thumbnailUrl AS thumbnailUrl, " +
        "$FEED_URL_SUBQUERY AS feedUrl, " +
        "es.interactionUpdatedAt AS interactionUpdatedAt, " +
        "ec.audioUrl AS audioUrl, " +
        "COALESCE(es.inboxState, 'none') AS inboxState " +
        INBOX_FROM_WHERE

private const val ENTRIES_STAMP_SQL =
    "SELECT COUNT(*) || ':' || COALESCE(MAX(hlc), '') || ':' || " +
        "COALESCE(MAX(updatedAt), '') || ':' || " +
        "COALESCE(SUM(CASE WHEN mergedIntoId IS NOT NULL AND mergedIntoId != '' " +
        "THEN 1 ELSE 0 END), 0) || ':' || " +
        "COALESCE(SUM(LENGTH(title) * 31 + LENGTH(COALESCE(url, '')) * 7 + " +
        "LENGTH(COALESCE(summary, '')) * 13 + LENGTH(creatorsJson) * 17 + " +
        "LENGTH(COALESCE(mergedIntoId, '')) * 23 + LENGTH(COALESCE(thumbnailUrl, '')) * 29 + " +
        "LENGTH(metadataJson) * 37 + COALESCE(releaseYear, 0) * 41 + " +
        "LENGTH(COALESCE(publishedAt, '')) * 43 + isLocalDirty * 47 + " +
        "COALESCE(UNICODE(title), 0) * 3 + " +
        "COALESCE(UNICODE(SUBSTR(title, -1, 1)), 0) * 5), 0) " +
        "FROM entries"

private const val IDENTS_STAMP_SQL =
    "SELECT COUNT(*) || ':' || " +
        "COALESCE(SUM(LENGTH(entryId) * 31 + LENGTH(scheme) * 7 + LENGTH(value)), 0) || ':' || " +
        "COALESCE(SUM(COALESCE(UNICODE(entryId), 0) * 17 + " +
        "COALESCE(UNICODE(SUBSTR(entryId, -1, 1)), 0) * 31 + " +
        "COALESCE(UNICODE(SUBSTR(entryId, LENGTH(entryId) / 2 + 1, 1)), 0) * 13 + " +
        "COALESCE(UNICODE(value), 0) * 7 + " +
        "COALESCE(UNICODE(SUBSTR(value, -1, 1)), 0)), 0) || ':' || " +
        "COALESCE(MIN(entryId), '') || ':' || COALESCE(MAX(entryId), '') || ':' || " +
        "COALESCE(MIN(value), '') || ':' || COALESCE(MAX(value), '') " +
        "FROM entry_identifiers"

private const val SPECS_STAMP_SQL =
    "SELECT COUNT(*) || ':' || COALESCE(SUM(showInLists), 0) || ':' || " +
        "COALESCE(SUM(LENGTH(COALESCE(slug, '')) * 31 + LENGTH(role) * 7 + " +
        "LENGTH(mode) * 13 + LENGTH(COALESCE(ruleJson, '')) * 17 + " +
        "LENGTH(COALESCE(color, '')) * 23 + " +
        "COALESCE(UNICODE(COALESCE(slug, '')), 0) * 3 + " +
        "COALESCE(UNICODE(SUBSTR(COALESCE(slug, ''), -1, 1)), 0) * 5 + " +
        "COALESCE(UNICODE(role), 0) * 11 + COALESCE(UNICODE(mode), 0) * 19 + " +
        "COALESCE(UNICODE(COALESCE(ruleJson, '')), 0) * 29 + " +
        "COALESCE(UNICODE(SUBSTR(COALESCE(ruleJson, ''), -1, 1)), 0) * 37 + " +
        "COALESCE(UNICODE(COALESCE(color, '')), 0) * 41 + " +
        "COALESCE(UNICODE(SUBSTR(COALESCE(color, ''), 2, 1)), 0) * 47 + " +
        "COALESCE(UNICODE(SUBSTR(COALESCE(color, ''), -1, 1)), 0) * 43), 0) || ':' || " +
        "COALESCE(MIN(COALESCE(slug, '')), '') || ':' || " +
        "COALESCE(MAX(COALESCE(slug, '')), '') || ':' || " +
        "COALESCE(MIN(COALESCE(color, '')), '') || ':' || " +
        "COALESCE(MAX(COALESCE(color, '')), '') " +
        "FROM collection_specs"

private const val MEMBERS_STAMP_SQL =
    "SELECT COUNT(*) || ':' || COALESCE(MAX(hlc), '') || ':' || " +
        "COALESCE(MAX(addedAt), '') || ':' || COALESCE(SUM(COALESCE(position, 0.0)), 0.0) || ':' || " +
        "COALESCE(SUM(LENGTH(collectionId) * 7 + LENGTH(entryId) * 13 + " +
        "COALESCE(UNICODE(collectionId), 0) * 3 + COALESCE(UNICODE(entryId), 0) * 5 + " +
        "COALESCE(UNICODE(SUBSTR(entryId, -1, 1)), 0) * 11), 0) || ':' || " +
        "COALESCE(MIN(entryId), '') || ':' || COALESCE(MAX(entryId), '') " +
        "FROM collection_entries"

private const val GENERATORS_STAMP_SQL =
    "SELECT COUNT(*) || ':' || COALESCE(SUM(enabled), 0) || ':' || " +
        "COALESCE(MAX(hlc), '') || ':' || COALESCE(MAX(updatedAt), '') || ':' || " +
        "COALESCE(MIN(COALESCE(currentEntryId, '')), '') || ':' || " +
        "COALESCE(MAX(COALESCE(currentEntryId, '')), '') || ':' || " +
        "COALESCE(SUM(LENGTH(COALESCE(currentEntryId, '')) * 7 + " +
        "COALESCE(UNICODE(COALESCE(currentEntryId, '')), 0) * 13 + " +
        "COALESCE(UNICODE(SUBSTR(COALESCE(currentEntryId, ''), -1, 1)), 0) * 17), 0) " +
        "FROM collection_generators"

private const val LOGS_STAMP_SQL =
    "SELECT COUNT(*) || ':' || COALESCE(MAX(hlc), '') || ':' || " +
        "COALESCE(MAX(ts), '') || ':' || COALESCE(MIN(entryId), '') || ':' || " +
        "COALESCE(MAX(entryId), '') || ':' || " +
        "COALESCE(SUM(LENGTH(status) * 7 + LENGTH(COALESCE(notes, '')) * 13 + " +
        "COALESCE(UNICODE(status), 0) * 3 + " +
        "COALESCE(UNICODE(COALESCE(notes, '')), 0) * 5), 0) " +
        "FROM consumption_log"

private const val COMPARISONS_STAMP_SQL =
    "SELECT COUNT(*) || ':' || COALESCE(MAX(hlc), '') || ':' || " +
        "COALESCE(MIN(id), '') || ':' || COALESCE(MAX(id), '') || ':' || " +
        "COALESCE(SUM(LENGTH(orderingJson) * 7 + " +
        "LENGTH(COALESCE(scopeCollectionId, '')) * 13 + " +
        "COALESCE(UNICODE(orderingJson), 0) * 3 + " +
        "COALESCE(UNICODE(SUBSTR(orderingJson, -2, 1)), 0) * 5), 0) " +
        "FROM comparisons"

private const val STATES_STAMP_SQL =
    "SELECT COUNT(*) || ':' || COALESCE(MAX(hlc), '') || ':' || " +
        "COALESCE(MAX(updatedAt), '') || ':' || " +
        "COALESCE(MAX(COALESCE(interactionUpdatedAt, '')), '') || ':' || " +
        "COALESCE(MAX(COALESCE(readAt, '')), '') || ':' || " +
        "COALESCE(SUM(CASE WHEN readAt IS NOT NULL THEN 1 ELSE 0 END), 0) || ':' || " +
        "COALESCE(SUM(isCachedOffline), 0) || ':' || " +
        "COALESCE(SUM(CASE WHEN inboxState = 'unread' THEN 1 " +
        "WHEN inboxState = 'dismissed' THEN 2 " +
        "WHEN inboxState = 'deleted' THEN 4 ELSE 0 END), 0) || ':' || " +
        "COALESCE(SUM(progress), 0.0) " +
        "FROM entry_state"

private const val CONTENTS_STAMP_SQL =
    "SELECT COUNT(*) || ':' || " +
        "COALESCE(SUM(LENGTH(COALESCE(contentHtml, '')) * 31 + " +
        "LENGTH(COALESCE(contentText, '')) * 17 + " +
        "LENGTH(COALESCE(audioUrl, '')) * 7 + LENGTH(COALESCE(duration, '')) * 13 + " +
        "LENGTH(COALESCE(thumbnailMediaId, '')) * 19 + " +
        "LENGTH(COALESCE(fallbackFeedTitle, '')) * 23 + LENGTH(contentType) * 3 + " +
        "COALESCE(UNICODE(contentType), 0) * 5 + " +
        "COALESCE(UNICODE(SUBSTR(COALESCE(contentHtml, ''), 1, 1)), 0) * 11 + " +
        "COALESCE(UNICODE(SUBSTR(COALESCE(contentText, ''), 1, 1)), 0) * 29), 0) || ':' || " +
        "COALESCE(MIN(COALESCE(fallbackFeedTitle, '')), '') || ':' || " +
        "COALESCE(MAX(COALESCE(fallbackFeedTitle, '')), '') || ':' || " +
        "COALESCE(MIN(COALESCE(duration, '')), '') || ':' || " +
        "COALESCE(MAX(COALESCE(duration, '')), '') " +
        "FROM entry_content"

private const val FEEDS_STAMP_SQL =
    "SELECT COUNT(*) || ':' || COALESCE(MAX(hlc), '') || ':' || " +
        "COALESCE(SUM(isActive), 0) || ':' || COALESCE(SUM(autoDownloadEpisodes), 0) || ':' || " +
        "COALESCE(SUM(consecutiveFailures), 0) || ':' || " +
        "COALESCE(SUM(COALESCE(customPlaybackSpeed, 0.0)), 0.0) || ':' || " +
        "COALESCE(MAX(COALESCE(lastPolledAt, '')), '') || ':' || " +
        "COALESCE(MAX(COALESCE(lastError, '')), '') || ':' || " +
        "COALESCE(SUM(LENGTH(COALESCE(feedTitle, '')) * 7 + LENGTH(sourceType) * 13 + " +
        "LENGTH(COALESCE(lastError, '')) * 17 + " +
        "COALESCE(UNICODE(COALESCE(feedTitle, '')), 0) * 3 + isLocalDirty * 19), 0) " +
        "FROM feed_sources"

private const val CATALOG_STAMP_SELECT =
    "SELECT ($ENTRIES_STAMP_SQL) AS entriesStamp, " +
        "($IDENTS_STAMP_SQL) AS identsStamp, " +
        "($SPECS_STAMP_SQL) AS specsStamp, " +
        "($MEMBERS_STAMP_SQL) AS membersStamp, " +
        "($GENERATORS_STAMP_SQL) AS generatorsStamp, " +
        "($LOGS_STAMP_SQL) AS logsStamp, " +
        "($COMPARISONS_STAMP_SQL) AS comparisonsStamp"

private const val REPOSITORY_STAMP_SELECT =
    "$CATALOG_STAMP_SELECT, " +
        "($STATES_STAMP_SQL) AS statesStamp, " +
        "($CONTENTS_STAMP_SQL) AS contentsStamp, " +
        "($FEEDS_STAMP_SQL) AS feedsStamp"

private const val FEED_ROW_SELECT =
    "SELECT fs.collectionId AS id, " +
        "COALESCE(NULLIF(e.title, ''), NULLIF(fs.feedTitle, ''), fs.feedUrl) AS title, " +
        "fs.feedUrl AS url, " +
        "fs.siteUrl AS siteUrl, " +
        "fs.sourceType AS feedType, " +
        "e.summary AS description, " +
        "fs.language AS language, " +
        "fs.pollInterval AS pollInterval, " +
        "fs.lastPolledAt AS lastPolled, " +
        "fs.lastPolledBy AS lastPolledBy, " +
        "fs.lastError AS lastError, " +
        "fs.lastErrorBy AS lastErrorBy, " +
        "fs.consecutiveFailures AS consecutiveFailures, " +
        "fs.isActive AS isActive, " +
        "fs.autoDownloadEpisodes AS autoDownloadEpisodes, " +
        "fs.customPlaybackSpeed AS customPlaybackSpeed, " +
        "fs.hlc AS hlc, " +
        "(fs.isLocalDirty OR e.isLocalDirty) AS isLocalDirty " +
        "FROM feed_sources fs " +
        "INNER JOIN entries e ON e.id = fs.collectionId " +
        "WHERE e.mergedIntoId IS NULL"

@Dao
interface RocheDao {
    // --- Reactive UI Observers ---

    @Query("$FEED_ROW_SELECT ORDER BY title COLLATE NOCASE ASC, title ASC")
    fun observeFeeds(): Flow<List<FeedRow>>

    @Query("$INBOX_SELECT_LIST_PREFIX ORDER BY COALESCE(e.publishedAt, e.createdAt) DESC")
    fun observeItems(): Flow<List<InboxItemRow>>

    @Query(
        "SELECT e.id AS id, e.title AS name, cs.color AS color " +
            "FROM collection_specs cs " +
            "INNER JOIN entries e ON e.id = cs.collectionId " +
            "WHERE cs.role = 'tag' AND e.mergedIntoId IS NULL " +
            "ORDER BY e.title COLLATE NOCASE ASC, e.title ASC"
    )
    fun observeTags(): Flow<List<TagRow>>

    @Query(
        "SELECT ce.entryId AS itemId, ce.collectionId AS tagId " +
            "FROM collection_entries ce " +
            "INNER JOIN collection_specs cs ON cs.collectionId = ce.collectionId " +
            "INNER JOIN entries e ON e.id = cs.collectionId " +
            "WHERE cs.role = 'tag' AND e.mergedIntoId IS NULL"
    )
    fun observeItemTags(): Flow<List<ItemTagRow>>

    @Query(ENTRIES_STAMP_SQL) fun observeEntriesStamp(): Flow<String>

    @Query(IDENTS_STAMP_SQL) fun observeIdentsStamp(): Flow<String>

    @Query(SPECS_STAMP_SQL) fun observeSpecsStamp(): Flow<String>

    @Query(MEMBERS_STAMP_SQL) fun observeMembersStamp(): Flow<String>

    @Query(GENERATORS_STAMP_SQL) fun observeGeneratorsStamp(): Flow<String>

    @Query(LOGS_STAMP_SQL) fun observeLogsStamp(): Flow<String>

    @Query(COMPARISONS_STAMP_SQL) fun observeComparisonsStamp(): Flow<String>

    @Query(CATALOG_STAMP_SELECT) fun observeCatalogStamp(): Flow<CatalogStampRow>

    @Query(CATALOG_STAMP_SELECT) suspend fun getCatalogStamp(): CatalogStampRow

    @Query(REPOSITORY_STAMP_SELECT) fun observeRepositoryStamp(): Flow<RepositoryStampRow>

    @Query(REPOSITORY_STAMP_SELECT) suspend fun getRepositoryStamp(): RepositoryStampRow

    // --- Core Entries (`entries` + FTS-safe upserts) ---

    @Query("SELECT * FROM entries") suspend fun getAllEntries(): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE id IN (:ids)")
    suspend fun getEntriesByIds(ids: List<String>): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE mergedIntoId IS NOT NULL AND mergedIntoId != ''")
    suspend fun getMergedEntries(): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE id = :id LIMIT 1")
    suspend fun getEntryById(id: String): EntryEntity?

    @Query("SELECT * FROM entries WHERE isLocalDirty = 1")
    suspend fun getDirtyEntries(): List<EntryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntryIgnore(entry: EntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntriesIgnore(entries: List<EntryEntity>): List<Long>

    @Update suspend fun updateEntry(entry: EntryEntity)

    @Update suspend fun updateEntries(entries: List<EntryEntity>)

    @Transaction
    suspend fun upsertEntry(entry: EntryEntity) {
        if (insertEntryIgnore(entry) == -1L) {
            updateEntry(entry)
        }
    }

    @Transaction
    suspend fun upsertEntries(entries: List<EntryEntity>) {
        if (entries.isEmpty()) return
        val deduped = entries.associateBy { it.id }.values.toList()
        val rowIds = insertEntriesIgnore(deduped)
        val existing = deduped.filterIndexed { index, _ -> rowIds[index] == -1L }
        if (existing.isNotEmpty()) {
            updateEntries(existing)
        }
    }

    @Query("DELETE FROM entries WHERE id = :id") suspend fun deleteRawEntryById(id: String)

    @Query(
        "UPDATE entries SET mergedIntoId = :canonId, updatedAt = :updatedAt " +
            "WHERE mergedIntoId = :dupId AND id != :canonId"
    )
    suspend fun repointMergedIntoEntries(dupId: String, canonId: String, updatedAt: String)

    @Query("UPDATE entries SET isLocalDirty = 0 WHERE id = :id AND hlc = :expectedHlc")
    suspend fun clearEntryDirtyIfSameHlc(id: String, expectedHlc: String)

    @Query("UPDATE entries SET isLocalDirty = 1 WHERE id IN (:ids)")
    suspend fun markEntriesDirty(ids: List<String>)

    @Query(
        "UPDATE collection_entries SET isLocalDirty = 1 " +
            "WHERE entryId IN (:ids) OR collectionId IN (:ids)"
    )
    suspend fun markMembershipsDirtyForEntries(ids: List<String>)

    // --- Satellite `entry_content` (FTS-safe upserts) ---

    @Query(
        "SELECT entryId, contentType, " +
            "SUBSTR(contentText, 1, 2048) AS contentText, " +
            "SUBSTR(contentHtml, 1, 2048) AS contentHtml, " +
            "audioUrl, duration, thumbnailMediaId, fallbackFeedTitle " +
            "FROM entry_content"
    )
    suspend fun getAllEntryContentPreviews(): List<EntryContentPreviewRow>

    @Query("SELECT * FROM entry_content WHERE entryId IN (:ids)")
    suspend fun getEntryContentsByIds(ids: List<String>): List<EntryContentEntity>

    @Query("SELECT * FROM entry_content WHERE entryId = :entryId LIMIT 1")
    suspend fun getEntryContentById(entryId: String): EntryContentEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntryContentIgnore(content: EntryContentEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntryContentsIgnore(contents: List<EntryContentEntity>): List<Long>

    @Update suspend fun updateEntryContent(content: EntryContentEntity)

    @Update suspend fun updateEntryContents(contents: List<EntryContentEntity>)

    @Transaction
    suspend fun upsertEntryContent(content: EntryContentEntity) {
        if (insertEntryContentIgnore(content) == -1L) {
            updateEntryContent(content)
        }
    }

    @Transaction
    suspend fun upsertEntryContents(contents: List<EntryContentEntity>) {
        if (contents.isEmpty()) return
        val deduped = contents.associateBy { it.entryId }.values.toList()
        val rowIds = insertEntryContentsIgnore(deduped)
        val existing = deduped.filterIndexed { index, _ -> rowIds[index] == -1L }
        if (existing.isNotEmpty()) {
            updateEntryContents(existing)
        }
    }

    @Query("DELETE FROM entry_content WHERE entryId = :entryId")
    suspend fun deleteEntryContentById(entryId: String)

    @Query(
        "UPDATE entry_content SET fallbackFeedTitle = NULL " +
            "WHERE entryId IN (SELECT entryId FROM collection_entries WHERE collectionId = :feedId)"
    )
    suspend fun clearFallbackFeedTitleForFeed(feedId: String)

    // --- Satellite `entry_state` ---

    @Query("SELECT * FROM entry_state") suspend fun getAllEntryStates(): List<EntryStateEntity>

    @Query("SELECT * FROM entry_state WHERE entryId IN (:ids)")
    suspend fun getEntryStatesByIds(ids: List<String>): List<EntryStateEntity>

    @Query("SELECT * FROM entry_state WHERE isLocalDirty = 1")
    suspend fun getDirtyEntryStates(): List<EntryStateEntity>

    @Query("SELECT * FROM entry_state WHERE entryId = :entryId LIMIT 1")
    suspend fun getEntryStateById(entryId: String): EntryStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntryState(state: EntryStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntryStates(states: List<EntryStateEntity>)

    @Query("DELETE FROM entry_state WHERE entryId = :entryId")
    suspend fun deleteEntryStateById(entryId: String)

    @Query("UPDATE entry_state SET isCachedOffline = :isCachedOffline WHERE entryId = :itemId")
    suspend fun updateItemCachedOffline(itemId: String, isCachedOffline: Boolean)

    @Query("UPDATE entry_state SET isLocalDirty = 0 WHERE entryId = :id AND hlc = :expectedHlc")
    suspend fun clearEntryStateDirtyIfSameHlc(id: String, expectedHlc: String)

    // --- Inbox Projections & Search ---

    @Query("SELECT COUNT(*) $INBOX_FROM_WHERE") suspend fun countItems(): Int

    @Query("$INBOX_SELECT_FULL_PREFIX AND e.id = :id LIMIT 1")
    suspend fun getItemById(id: String): InboxItemRow?

    @Query(INBOX_SELECT_FULL_PREFIX) suspend fun getAllItems(): List<InboxItemRow>

    @Query("$INBOX_SELECT_LIST_PREFIX AND e.id IN (:ids)")
    suspend fun getPreviewItemsByIds(ids: List<String>): List<InboxItemRow>

    @Query(
        "$INBOX_SELECT_FULL_PREFIX AND (" +
            "EXISTS (SELECT 1 FROM collection_entries ce_m " +
            "WHERE ce_m.entryId = e.id AND ce_m.collectionId = :feedId) " +
            "OR EXISTS (SELECT 1 FROM collection_entries ce_u " +
            "INNER JOIN feed_sources fs_u ON fs_u.collectionId = ce_u.collectionId " +
            "WHERE ce_u.entryId = e.id AND LOWER(fs_u.feedUrl) = LOWER(:feedUrl)))"
    )
    suspend fun getItemsForFeed(feedId: String, feedUrl: String): List<InboxItemRow>

    @Query(
        "SELECT e.id AS id, " +
            "$STATUS_EXPR AS status, " +
            "$IS_FAVORITE_EXPR AS isFavorite, " +
            "COALESCE(es.isCachedOffline, 0) AS isCachedOffline, " +
            "$CONTENT_TYPE_EXPR AS contentType, " +
            "es.readAt AS readAt, " +
            "COALESCE(es.progress, 0.0) AS readProgress, " +
            "ec.audioUrl AS audioUrl, " +
            "COALESCE(es.inboxState, 'none') AS inboxState " +
            "$INBOX_FROM_WHERE AND e.id = :id LIMIT 1"
    )
    suspend fun getItemStateById(id: String): ItemStateRow?

    @Query(
        "SELECT e.id AS id, " +
            "$STATUS_EXPR AS status, " +
            "$IS_FAVORITE_EXPR AS isFavorite, " +
            "es.isCachedOffline AS isCachedOffline, " +
            "$CONTENT_TYPE_EXPR AS contentType, " +
            "es.readAt AS readAt, " +
            "es.progress AS readProgress, " +
            "ec.audioUrl AS audioUrl, " +
            "es.inboxState AS inboxState " +
            "FROM entries e " +
            "INNER JOIN entry_state es ON es.entryId = e.id " +
            "LEFT JOIN entry_content ec ON ec.entryId = e.id " +
            "WHERE e.mergedIntoId IS NULL AND es.inboxState = 'unread'"
    )
    suspend fun getUnarchivedItemStates(): List<ItemStateRow>

    @Query(
        "SELECT ce.entryId FROM collection_entries ce " +
            "INNER JOIN entry_state es ON es.entryId = ce.entryId " +
            "INNER JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :feedId AND e.mergedIntoId IS NULL " +
            "AND es.inboxState = 'unread'"
    )
    suspend fun getUnarchivedItemIdsForFeed(feedId: String): List<String>

    @Query("SELECT entryId FROM collection_entries WHERE collectionId = :feedId")
    suspend fun getItemIdsForFeed(feedId: String): List<String>

    @Query(
        "SELECT e.id FROM entries e " +
            "INNER JOIN entry_state es ON es.entryId = e.id " +
            "LEFT JOIN entry_content ec ON ec.entryId = e.id " +
            "WHERE e.mergedIntoId IS NULL AND es.inboxState = 'unread' " +
            "AND (es.readAt IS NULL OR es.readAt = '') AND es.isCachedOffline = 0 " +
            "AND (LOWER(COALESCE(ec.contentType, '')) = 'audio' " +
            "OR (ec.audioUrl IS NOT NULL AND ec.audioUrl != '')) " +
            "AND EXISTS (SELECT 1 FROM collection_entries ce " +
            "WHERE ce.entryId = e.id AND ce.collectionId IN (:feedIds))"
    )
    suspend fun getUncachedUnreadAudioItemIds(feedIds: List<String>): List<String>

    @Query(
        "$INBOX_SELECT_SEARCH_PREFIX AND (" +
            "e.rowid IN (SELECT rowid FROM entries_fts WHERE entries_fts MATCH :tokenMatchQuery) " +
            "OR e.id IN (SELECT ec2.entryId FROM entry_content ec2 " +
            "INNER JOIN entry_content_fts ON ec2.rowid = entry_content_fts.rowid " +
            "WHERE entry_content_fts MATCH :tokenMatchQuery) " +
            "OR EXISTS (SELECT 1 FROM collection_entries ce_ft " +
            "INNER JOIN feed_sources fs_ft ON fs_ft.collectionId = ce_ft.collectionId " +
            "INNER JOIN entries fe_ft ON fe_ft.id = fs_ft.collectionId " +
            "INNER JOIN entries_fts ON fe_ft.rowid = entries_fts.rowid " +
            "WHERE ce_ft.entryId = e.id AND entries_fts MATCH :tokenMatchQuery) " +
            "OR EXISTS (SELECT 1 FROM collection_entries ce_tg " +
            "INNER JOIN collection_specs cs_tg ON cs_tg.collectionId = ce_tg.collectionId " +
            "INNER JOIN entries te_tg ON te_tg.id = cs_tg.collectionId " +
            "WHERE ce_tg.entryId = e.id AND cs_tg.role = 'tag' AND te_tg.mergedIntoId IS NULL " +
            "AND te_tg.title LIKE :likePattern ESCAPE '\\')" +
            ") ORDER BY COALESCE(e.publishedAt, e.createdAt) DESC"
    )
    suspend fun searchSingleTokenFullItems(
        tokenMatchQuery: String,
        likePattern: String,
    ): List<InboxItemRow>

    @Query(
        "SELECT e.id FROM entries e " +
            "LEFT JOIN entry_state es ON es.entryId = e.id " +
            "LEFT JOIN entry_content ec ON ec.entryId = e.id " +
            "INNER JOIN entries_fts ON e.rowid = entries_fts.rowid " +
            "WHERE $IS_LIVE_ITEM_WHERE " +
            "AND entries_fts MATCH :tokenMatchQuery " +
            "UNION " +
            "SELECT ec.entryId FROM entry_content ec " +
            "LEFT JOIN entry_state es2 ON es2.entryId = ec.entryId " +
            "INNER JOIN entries e2 ON e2.id = ec.entryId " +
            "INNER JOIN entry_content_fts ON ec.rowid = entry_content_fts.rowid " +
            "WHERE e2.mergedIntoId IS NULL " +
            "AND COALESCE(es2.inboxState, 'none') != 'deleted' " +
            "AND entry_content_fts MATCH :tokenMatchQuery " +
            "UNION " +
            "SELECT ce.entryId FROM collection_entries ce " +
            "INNER JOIN entry_state es3 ON es3.entryId = ce.entryId " +
            "INNER JOIN entries e3 ON e3.id = ce.entryId " +
            "INNER JOIN feed_sources fs ON fs.collectionId = ce.collectionId " +
            "INNER JOIN entries fe ON fe.id = fs.collectionId " +
            "INNER JOIN entries_fts ON fe.rowid = entries_fts.rowid " +
            "WHERE e3.mergedIntoId IS NULL AND es3.inboxState IN ('unread', 'dismissed') " +
            "AND entries_fts MATCH :tokenMatchQuery"
    )
    suspend fun matchItemIdsForFtsToken(tokenMatchQuery: String): List<String>

    @Query(
        "SELECT DISTINCT ce.entryId FROM collection_entries ce " +
            "INNER JOIN collection_specs cs ON cs.collectionId = ce.collectionId " +
            "INNER JOIN entries te ON te.id = cs.collectionId " +
            "INNER JOIN entries e ON e.id = ce.entryId " +
            "LEFT JOIN entry_state es ON es.entryId = e.id " +
            "LEFT JOIN entry_content ec ON ec.entryId = e.id " +
            "WHERE cs.role = 'tag' AND te.mergedIntoId IS NULL " +
            "AND $IS_LIVE_ITEM_WHERE " +
            "AND te.title LIKE :likePattern ESCAPE '\\'"
    )
    suspend fun matchItemIdsByTagLike(likePattern: String): List<String>

    @Query(
        "$INBOX_SELECT_SEARCH_PREFIX AND e.id IN (:ids) " +
            "ORDER BY COALESCE(e.publishedAt, e.createdAt) DESC"
    )
    suspend fun getSearchItemsByIdsChunk(ids: List<String>): List<InboxItemRow>

    @Query(
        "$INBOX_SELECT_SEARCH_PREFIX AND EXISTS (" +
            "SELECT 1 FROM collection_entries ce_tg " +
            "INNER JOIN collection_specs cs_tg ON cs_tg.collectionId = ce_tg.collectionId " +
            "INNER JOIN entries te_tg ON te_tg.id = cs_tg.collectionId " +
            "WHERE ce_tg.entryId = e.id AND cs_tg.role = 'tag' AND te_tg.mergedIntoId IS NULL " +
            "AND te_tg.title LIKE :likePattern ESCAPE '\\'" +
            ") ORDER BY COALESCE(e.publishedAt, e.createdAt) DESC"
    )
    suspend fun searchItemsByTagOnly(likePattern: String): List<InboxItemRow>

    @Transaction
    suspend fun searchFullItemsByTokens(
        tokenQueries: List<String>,
        likePattern: String,
    ): List<InboxItemRow> =
        when (tokenQueries.size) {
            0 -> searchItemsByTagOnly(likePattern)
            1 -> searchSingleTokenFullItems(tokenQueries.single(), likePattern)
            else -> executeMultiTokenSearch(tokenQueries, likePattern)
        }

    // --- Feeds (`feed_sources` + `entries` + `collection_specs`) ---

    @Query("SELECT * FROM feed_sources") suspend fun getAllFeedSources(): List<FeedSourceEntity>

    @Query("SELECT * FROM feed_sources WHERE isLocalDirty = 1")
    suspend fun getDirtyFeedSources(): List<FeedSourceEntity>

    @Query("SELECT * FROM feed_sources WHERE collectionId = :id LIMIT 1")
    suspend fun getFeedSourceById(id: String): FeedSourceEntity?

    @Query("SELECT * FROM feed_sources WHERE LOWER(feedUrl) = LOWER(:url) LIMIT 1")
    suspend fun getFeedSourceByUrl(url: String): FeedSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFeedSource(source: FeedSourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFeedSources(sources: List<FeedSourceEntity>)

    @Query("DELETE FROM feed_sources WHERE collectionId = :id")
    suspend fun deleteFeedSourceById(id: String)

    @Query(
        "UPDATE feed_sources SET isLocalDirty = 0 WHERE collectionId = :id AND hlc = :expectedHlc"
    )
    suspend fun clearFeedSourceDirtyIfSameHlc(id: String, expectedHlc: String)

    @Query(FEED_ROW_SELECT) suspend fun getAllFeeds(): List<FeedRow>

    @Query("$FEED_ROW_SELECT AND fs.collectionId = :id LIMIT 1")
    suspend fun getFeedById(id: String): FeedRow?

    @Query("$FEED_ROW_SELECT AND LOWER(fs.feedUrl) = LOWER(:url) LIMIT 1")
    suspend fun getFeedByUrl(url: String): FeedRow?

    @Query("UPDATE feed_sources SET autoDownloadEpisodes = :enabled WHERE collectionId = :feedId")
    suspend fun updateFeedAutoDownload(feedId: String, enabled: Boolean)

    @Query("UPDATE feed_sources SET customPlaybackSpeed = :speed WHERE collectionId = :feedId")
    suspend fun updateFeedPlaybackSpeed(feedId: String, speed: Float?)

    // --- Tags (`collection_specs.role = 'tag'`) ---

    @Query(
        "SELECT e.id AS id, e.title AS name, cs.color AS color " +
            "FROM collection_specs cs " +
            "INNER JOIN entries e ON e.id = cs.collectionId " +
            "WHERE cs.role = 'tag' AND e.mergedIntoId IS NULL"
    )
    suspend fun getAllTags(): List<TagRow>

    @Query(
        "SELECT e.id AS id, e.title AS name, cs.color AS color " +
            "FROM collection_specs cs " +
            "INNER JOIN entries e ON e.id = cs.collectionId " +
            "WHERE cs.role = 'tag' AND e.mergedIntoId IS NULL " +
            "AND LOWER(e.title) = LOWER(:name) LIMIT 1"
    )
    suspend fun getTagByName(name: String): TagRow?

    @Query(
        "SELECT ce.entryId AS itemId, ce.collectionId AS tagId " +
            "FROM collection_entries ce " +
            "INNER JOIN collection_specs cs ON cs.collectionId = ce.collectionId " +
            "INNER JOIN entries e ON e.id = cs.collectionId " +
            "WHERE cs.role = 'tag' AND e.mergedIntoId IS NULL"
    )
    suspend fun getAllItemTags(): List<ItemTagRow>

    @Query(
        "SELECT e.title FROM collection_entries ce " +
            "INNER JOIN collection_specs cs ON cs.collectionId = ce.collectionId " +
            "INNER JOIN entries e ON e.id = cs.collectionId " +
            "WHERE ce.entryId = :itemId AND cs.role = 'tag' AND e.mergedIntoId IS NULL " +
            "ORDER BY e.title COLLATE NOCASE ASC, e.title ASC"
    )
    suspend fun getTagNamesForItem(itemId: String): List<String>

    @Query(
        "SELECT COUNT(*) FROM collection_entries " +
            "WHERE collectionId = :collectionId AND entryId = :entryId"
    )
    suspend fun hasCollectionMembership(collectionId: String, entryId: String): Int

    // --- Entry Identifiers ---

    @Query("SELECT * FROM entry_identifiers")
    suspend fun getAllEntryIdentifiers(): List<EntryIdentifierEntity>

    @Query("SELECT * FROM entry_identifiers WHERE entryId IN (:entryIds)")
    suspend fun getEntryIdentsByEntryIds(entryIds: List<String>): List<EntryIdentifierEntity>

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

    @Query("DELETE FROM entry_identifiers WHERE entryId = :entryId")
    suspend fun deleteIdentifiersForEntry(entryId: String)

    // --- Collection Specs, Entries & Generators ---

    @Query("SELECT * FROM collection_specs")
    suspend fun getAllCollectionSpecs(): List<CollectionSpecEntity>

    @Query("SELECT * FROM collection_specs WHERE collectionId = :id LIMIT 1")
    suspend fun getCollectionSpecById(id: String): CollectionSpecEntity?

    @Query("SELECT * FROM collection_specs WHERE LOWER(slug) = LOWER(:slug) LIMIT 1")
    suspend fun getCollectionSpecBySlug(slug: String): CollectionSpecEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollectionSpec(spec: CollectionSpecEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollectionSpecs(specs: List<CollectionSpecEntity>)

    @Query("DELETE FROM collection_specs WHERE collectionId = :collectionId")
    suspend fun deleteCollectionSpec(collectionId: String)

    @Query(
        "DELETE FROM collection_specs " +
            "WHERE collectionId = :dupId AND mode = 'manual' AND role IN ('list', 'want')"
    )
    suspend fun deleteManualCollectionSpec(dupId: String)

    @Query("SELECT * FROM collection_entries")
    suspend fun getAllCollectionEntries(): List<CollectionEntryEntity>

    @Query("SELECT * FROM collection_entries WHERE isLocalDirty = 1")
    suspend fun getDirtyCollectionEntries(): List<CollectionEntryEntity>

    @Query("SELECT * FROM collection_entries WHERE collectionId IN (:collectionIds)")
    suspend fun getMembershipsByColIds(collectionIds: List<String>): List<CollectionEntryEntity>

    @Query(
        "SELECT * FROM collection_entries WHERE collectionId = :collectionId " +
            "ORDER BY COALESCE(position, 1e12) ASC, addedAt ASC"
    )
    suspend fun getCollectionMemberships(collectionId: String): List<CollectionEntryEntity>

    @Query(
        "SELECT * FROM collection_entries " +
            "WHERE collectionId = :collectionId AND entryId = :entryId LIMIT 1"
    )
    suspend fun getCollectionEntry(collectionId: String, entryId: String): CollectionEntryEntity?

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

    @Query("DELETE FROM collection_entries WHERE entryId = :dupId OR collectionId = :dupId")
    suspend fun deleteMergedDupMemberships(dupId: String)

    @Query(
        "UPDATE collection_entries SET isLocalDirty = 0 " +
            "WHERE collectionId = :collectionId AND entryId = :entryId AND hlc = :expectedHlc"
    )
    suspend fun clearMemberDirtyIfSameHlc(
        collectionId: String,
        entryId: String,
        expectedHlc: String,
    )

    @Query("SELECT * FROM collection_generators")
    suspend fun getAllCollectionGenerators(): List<CollectionGeneratorEntity>

    @Query("SELECT * FROM collection_generators WHERE isLocalDirty = 1")
    suspend fun getDirtyCollectionGenerators(): List<CollectionGeneratorEntity>

    @Query("SELECT * FROM collection_generators WHERE collectionId = :collectionId LIMIT 1")
    suspend fun getCollectionGenerator(collectionId: String): CollectionGeneratorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollectionGenerator(generator: CollectionGeneratorEntity)

    @Query("DELETE FROM collection_generators WHERE collectionId = :collectionId")
    suspend fun deleteCollectionGenerator(collectionId: String)

    @Query(
        "UPDATE collection_generators SET isLocalDirty = 0 " +
            "WHERE collectionId = :collectionId AND hlc = :expectedHlc"
    )
    suspend fun clearGeneratorDirtyIfSameHlc(collectionId: String, expectedHlc: String)

    // --- Consumption Log & Comparisons ---

    @Query("SELECT * FROM consumption_log ORDER BY ts DESC")
    suspend fun getAllConsumptionLogs(): List<ConsumptionLogEntity>

    @Query("SELECT * FROM consumption_log WHERE isLocalDirty = 1")
    suspend fun getDirtyConsumptionLogs(): List<ConsumptionLogEntity>

    @Query("SELECT * FROM consumption_log WHERE id = :id LIMIT 1")
    suspend fun getConsumptionLogById(id: String): ConsumptionLogEntity?

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

    @Query("DELETE FROM consumption_log WHERE id = :id")
    suspend fun deleteConsumptionLogById(id: String)

    @Query("UPDATE consumption_log SET entryId = :canonicalId WHERE entryId = :dupId")
    suspend fun repointConsumptionLogsToEntry(dupId: String, canonicalId: String)

    @Query("SELECT * FROM comparisons ORDER BY ts ASC")
    suspend fun getAllComparisons(): List<ComparisonEntity>

    @Query("SELECT * FROM comparisons WHERE isLocalDirty = 1")
    suspend fun getDirtyComparisons(): List<ComparisonEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertComparisonIgnore(comparison: ComparisonEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertComparisonsIgnore(comparisons: List<ComparisonEntity>)

    @Query("UPDATE comparisons SET isLocalDirty = 0 WHERE id IN (:ids)")
    suspend fun clearComparisonsDirty(ids: List<String>)

    @Query("DELETE FROM comparisons WHERE id = :id") suspend fun deleteComparisonById(id: String)

    // --- Tombstones & Local Sync Flags ---

    @Query("SELECT * FROM sync_tombstones") suspend fun getAllTombstones(): List<TombstoneEntity>

    @Query("SELECT * FROM sync_tombstones WHERE isLocalDirty = 1")
    suspend fun getDirtyTombstones(): List<TombstoneEntity>

    @Query(
        "SELECT * FROM sync_tombstones " +
            "WHERE entityType = :entityType AND naturalKey = :naturalKey LIMIT 1"
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

    @Query(
        "UPDATE sync_tombstones SET isLocalDirty = 0 " +
            "WHERE entityType = :entityType AND naturalKey = :naturalKey AND hlc = :expectedHlc"
    )
    suspend fun clearTombstoneDirtyIfSameHlc(
        entityType: String,
        naturalKey: String,
        expectedHlc: String,
    )

    @Query("SELECT flagValue FROM local_sync_flags WHERE flagKey = :key LIMIT 1")
    suspend fun getLocalSyncFlag(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocalSyncFlag(flag: LocalSyncFlagEntity)

    @Query(
        "INSERT INTO local_sync_flags(flagKey, flagValue) VALUES (:key, :value) " +
            "ON CONFLICT(flagKey) DO UPDATE SET flagValue = " +
            "CASE WHEN excluded.flagValue > local_sync_flags.flagValue " +
            "THEN excluded.flagValue ELSE local_sync_flags.flagValue END"
    )
    suspend fun upsertLocalSyncFlagIfGreater(key: String, value: String)

    // --- Catalog History Check ---

    @Query(
        "SELECT CASE WHEN " +
            "EXISTS (SELECT 1 FROM consumption_log WHERE entryId = :entryId) " +
            "OR EXISTS (SELECT 1 FROM comparisons WHERE orderingJson LIKE :quotedPattern) " +
            "OR EXISTS (SELECT 1 FROM collection_entries ce " +
            "INNER JOIN collection_specs cs ON cs.collectionId = ce.collectionId " +
            "WHERE ce.entryId = :entryId AND cs.role IN ('list', 'want')) " +
            "OR EXISTS (SELECT 1 FROM collection_generators WHERE currentEntryId = :entryId) " +
            "THEN 1 ELSE 0 END"
    )
    suspend fun hasCatalogHistoryRaw(entryId: String, quotedPattern: String): Int

    @Query(
        "SELECT CASE WHEN EXISTS (" +
            "SELECT 1 FROM comparisons WHERE scopeCollectionId = :entryId" +
            ") THEN 1 ELSE 0 END"
    )
    suspend fun hasComparisonScopeRaw(entryId: String): Int

    @Transaction
    suspend fun executeInTransaction(block: suspend () -> Unit) {
        block()
    }
}

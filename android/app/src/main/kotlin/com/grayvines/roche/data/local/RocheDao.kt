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

private const val IS_FINISHED_EXPR =
    "EXISTS (SELECT 1 FROM consumption_log cl_fin " +
        "WHERE cl_fin.entryId = e.id AND cl_fin.status = 'consumed')"

private const val HACKER_NEWS_ID_SUBQUERY =
    "(SELECT ei_hn.value FROM entry_identifiers ei_hn " +
        "WHERE ei_hn.entryId = e.id AND ei_hn.scheme = 'hacker_news' " +
        "ORDER BY ei_hn.value ASC LIMIT 1)"

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

private const val LIVE_INBOX_IDS_CTE =
    "WITH live_inbox_ids(id) AS (" +
        "SELECT entryId FROM entry_state WHERE inboxState IN ('unread', 'dismissed') " +
        "UNION " +
        "SELECT entryId FROM entry_content " +
        "UNION " +
        "SELECT e_r.id FROM entries e_r " +
        "WHERE e_r.kind IN ('article', 'paper', 'video', 'podcast_episode') " +
        "AND e_r.mergedIntoId IS NULL " +
        "AND EXISTS (SELECT 1 FROM collection_entries ce_li " +
        "INNER JOIN collection_specs cs_li ON cs_li.collectionId = ce_li.collectionId " +
        "WHERE ce_li.entryId = e_r.id AND cs_li.role IN ('want', 'list'))" +
        ") "

private const val INBOX_CTE_FROM_WHERE =
    "FROM live_inbox_ids l " +
        "CROSS JOIN entries e ON e.id = l.id " +
        "LEFT JOIN entry_state es ON es.entryId = e.id " +
        "LEFT JOIN entry_content ec ON ec.entryId = e.id " +
        "WHERE e.mergedIntoId IS NULL AND COALESCE(es.inboxState, 'none') != 'deleted'"

private const val INBOX_LIST_COLUMNS =
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
        "COALESCE(es.inboxState, 'none') AS inboxState, " +
        "e.kind AS entryKind, " +
        "$IS_FINISHED_EXPR AS isFinished, " +
        "e.metadataJson AS metadataJson, " +
        "$HACKER_NEWS_ID_SUBQUERY AS hackerNewsId, " +
        "e.releaseYear AS releaseYear "

private const val INBOX_FULL_COLUMNS =
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
        "COALESCE(es.inboxState, 'none') AS inboxState, " +
        "e.kind AS entryKind, " +
        "$IS_FINISHED_EXPR AS isFinished, " +
        "e.metadataJson AS metadataJson, " +
        "$HACKER_NEWS_ID_SUBQUERY AS hackerNewsId, " +
        "e.releaseYear AS releaseYear "

private const val INBOX_SELECT_LIST_PREFIX = "$INBOX_LIST_COLUMNS$INBOX_FROM_WHERE"

private const val INBOX_SELECT_FULL_PREFIX = "$INBOX_FULL_COLUMNS$INBOX_FROM_WHERE"

private const val INBOX_SELECT_LIST_CTE =
    "$LIVE_INBOX_IDS_CTE$INBOX_LIST_COLUMNS$INBOX_CTE_FROM_WHERE"

private const val INBOX_SELECT_FULL_CTE =
    "$LIVE_INBOX_IDS_CTE$INBOX_FULL_COLUMNS$INBOX_CTE_FROM_WHERE"

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
        "COALESCE(es.inboxState, 'none') AS inboxState, " +
        "e.kind AS entryKind, " +
        "$IS_FINISHED_EXPR AS isFinished, " +
        "e.metadataJson AS metadataJson, " +
        "$HACKER_NEWS_ID_SUBQUERY AS hackerNewsId, " +
        "e.releaseYear AS releaseYear " +
        INBOX_FROM_WHERE

private const val CATALOG_STAMP_SELECT =
    "SELECT " +
        "COALESCE((SELECT revision FROM table_revisions WHERE tableName = 'entries'), 0) AS entriesStamp, " +
        "COALESCE((SELECT revision FROM table_revisions WHERE tableName = 'entry_identifiers'), 0) AS identsStamp, " +
        "COALESCE((SELECT revision FROM table_revisions WHERE tableName = 'collection_specs'), 0) AS specsStamp, " +
        "COALESCE((SELECT revision FROM table_revisions WHERE tableName = 'collection_entries'), 0) AS membersStamp, " +
        "COALESCE((SELECT revision FROM table_revisions WHERE tableName = 'collection_generators'), 0) AS generatorsStamp, " +
        "COALESCE((SELECT revision FROM table_revisions WHERE tableName = 'consumption_log'), 0) AS logsStamp, " +
        "COALESCE((SELECT revision FROM table_revisions WHERE tableName = 'comparisons'), 0) AS comparisonsStamp"

private const val REPOSITORY_STAMP_SELECT =
    "$CATALOG_STAMP_SELECT, " +
        "COALESCE((SELECT revision FROM table_revisions WHERE tableName = 'entry_state'), 0) AS statesStamp, " +
        "COALESCE((SELECT revision FROM table_revisions WHERE tableName = 'entry_content'), 0) AS contentsStamp, " +
        "COALESCE((SELECT revision FROM table_revisions WHERE tableName = 'feed_sources'), 0) AS feedsStamp, " +
        "COALESCE((SELECT revision FROM table_revisions WHERE tableName = 'boards'), 0) AS boardsStamp, " +
        "COALESCE((SELECT revision FROM table_revisions WHERE tableName = 'board_items'), 0) AS boardItemsStamp, " +
        "COALESCE((SELECT revision FROM table_revisions WHERE tableName = 'board_item_states'), 0) AS boardItemStatesStamp"

private const val DEFAULT_FTS_SEARCH_LIMIT = 150
private const val DEFAULT_FTS_TOKEN_ID_LIMIT = 600
private const val DEFAULT_EXTRA_SEARCH_LIMIT = 50

private const val DIARY_ACTIVE_ENTRY_WHERE = "(e.mergedIntoId IS NULL OR e.mergedIntoId = '')"

private const val DIARY_NON_SONG_KIND_IN =
    "('article', 'paper', 'video', 'podcast_episode', " +
        "'movie', 'book', 'game', 'album', 'tv', 'collection')"

private const val DIARY_VISIBLE_ENTRY_WHERE =
    "$DIARY_ACTIVE_ENTRY_WHERE " +
        "AND e.kind NOT IN ('artist', 'podcast', 'publication', 'channel')"

private const val DIARY_LOG_ROW_COLUMNS =
    "cl.id AS id, " +
        "cl.entryId AS entryId, " +
        "e.kind AS kind, " +
        "e.title AS title, " +
        "e.creatorsJson AS creatorsJson, " +
        "e.releaseYear AS releaseYear, " +
        "e.url AS url, " +
        "e.thumbnailUrl AS thumbnailUrl, " +
        "cl.ts AS ts, " +
        "cl.status AS status, " +
        "cl.notes AS notes"

private const val DIARY_LOG_ROW_SELECT =
    "SELECT $DIARY_LOG_ROW_COLUMNS " +
        "FROM consumption_log cl " +
        "INNER JOIN entries e ON e.id = cl.entryId " +
        "WHERE $DIARY_VISIBLE_ENTRY_WHERE"

private const val DIARY_LOG_ROW_CROSS_SELECT =
    "SELECT $DIARY_LOG_ROW_COLUMNS " +
        "FROM consumption_log cl " +
        "CROSS JOIN entries e ON e.id = cl.entryId " +
        "WHERE $DIARY_VISIBLE_ENTRY_WHERE"

private const val DIARY_LOG_ROW_ENTRIES_CROSS_SELECT =
    "SELECT $DIARY_LOG_ROW_COLUMNS " +
        "FROM entries e " +
        "CROSS JOIN consumption_log cl ON cl.entryId = e.id " +
        "WHERE $DIARY_ACTIVE_ENTRY_WHERE"

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

@Suppress("LargeClass")
@Dao
interface RocheDao {
    // --- Reactive O(1) Table-Revision Observer ---

    @Query(CATALOG_STAMP_SELECT) suspend fun getCatalogStamp(): CatalogStampRow

    @Query(REPOSITORY_STAMP_SELECT) fun observeRepositoryStamp(): Flow<RepositoryStampRow>

    @Query(REPOSITORY_STAMP_SELECT) suspend fun getRepositoryStamp(): RepositoryStampRow

    // --- Core Entries (`entries` + FTS-safe upserts) ---

    @Query("SELECT * FROM entries") suspend fun getAllEntries(): List<EntryEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM entries)") suspend fun hasAnyEntries(): Boolean

    @Query("SELECT id FROM entries") suspend fun getAllEntryIds(): List<String>

    @Query("SELECT id FROM entries WHERE id IN (:ids)")
    suspend fun getExistingEntryIdsIn(ids: List<String>): List<String>

    @Query(
        "SELECT e.* FROM entry_state es " +
            "CROSS JOIN entries e ON e.id = es.entryId " +
            "WHERE es.inboxState IN ('unread', 'dismissed', 'none')"
    )
    suspend fun getEntriesWithInboxState(): List<EntryEntity>

    @Query(
        "SELECT e.* FROM entry_state es " +
            "CROSS JOIN entries e ON e.id = es.entryId " +
            "WHERE es.inboxState IN ('unread', 'dismissed', 'none') " +
            "AND e.url IS NOT NULL AND e.url != ''"
    )
    suspend fun getInboxStateEntriesWithUrl(): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE isLocalDirty = 1 ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getDirtyEntriesPage(limit: Int): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE +isLocalDirty = 0 ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getCleanEntriesByUpdatedPage(limit: Int): List<EntryEntity>

    @Query("SELECT id, mergedIntoId FROM entries WHERE mergedIntoId > ''")
    suspend fun getMergedEntryRedirects(): List<MergeRedirectRow>

    @Query("SELECT id, title FROM entries WHERE id IN (:ids)")
    suspend fun getEntryTitlesByIds(ids: List<String>): List<EntryTitleRow>

    @Query("SELECT id, kind, thumbnailUrl FROM entries WHERE id IN (:ids)")
    suspend fun getEntryThumbnailsByIds(ids: List<String>): List<EntryThumbnailRow>

    @Query(
        "SELECT * FROM entries " +
            "WHERE kind = 'album' AND (mergedIntoId IS NULL OR mergedIntoId = '') " +
            "AND creatorsJson LIKE :likePattern ESCAPE '\\'"
    )
    suspend fun getActiveAlbumsByCreatorLike(likePattern: String): List<EntryEntity>

    @Query(
        "SELECT * FROM entries e " +
            "WHERE e.kind = :kind AND (e.mergedIntoId IS NULL OR e.mergedIntoId = '') " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC"
    )
    suspend fun getActiveConsumedEntriesByKind(kind: String): List<EntryEntity>

    @Query(
        "SELECT COUNT(*) FROM entries e " +
            "WHERE e.kind = :kind AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed')"
    )
    suspend fun countConsumedEntriesForKind(kind: String): Int

    @Query(
        "SELECT e.* FROM entries e " +
            "WHERE e.kind = :kind AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getConsumedKindEntriesPage(
        kind: String,
        limit: Int,
        offset: Int,
    ): List<EntryEntity>

    @Query(
        "SELECT e.* FROM entries e " +
            "WHERE e.kind = :kind AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.id NOT IN (:excludedIds) " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getConsumedKindPageExcluding(
        kind: String,
        excludedIds: List<String>,
        limit: Int,
        offset: Int,
    ): List<EntryEntity>

    @Query(
        "SELECT COUNT(*) FROM entries e " +
            "WHERE e.kind = :kind AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND (LOWER(e.title) < LOWER(:title) OR (LOWER(e.title) = LOWER(:title) AND e.id < :id)) " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed')"
    )
    suspend fun countConsumedKindBefore(
        kind: String,
        title: String,
        id: String,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM entries e " +
            "WHERE e.kind = :kind AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.id NOT IN (:excludedIds) " +
            "AND (LOWER(e.title) < LOWER(:title) OR (LOWER(e.title) = LOWER(:title) AND e.id < :id)) " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed')"
    )
    suspend fun countConsumedKindBeforeExcl(
        kind: String,
        title: String,
        id: String,
        excludedIds: List<String>,
    ): Int

    @Query(
        "SELECT e.* FROM entries e " +
            "WHERE e.kind = :kind AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.id NOT IN (:excludedIds) " +
            "AND (LOWER(e.title) < LOWER(:title) OR (LOWER(e.title) = LOWER(:title) AND e.id < :id)) " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "ORDER BY LOWER(e.title) DESC, e.id DESC LIMIT 1"
    )
    suspend fun getConsumedKindPrevExcl(
        kind: String,
        title: String,
        id: String,
        excludedIds: List<String>,
    ): EntryEntity?

    @Query(
        "SELECT e.* FROM entries e " +
            "WHERE e.kind = :kind AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND (LOWER(e.title) < LOWER(:title) OR (LOWER(e.title) = LOWER(:title) AND e.id < :id)) " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "ORDER BY LOWER(e.title) DESC, e.id DESC LIMIT 1"
    )
    suspend fun getConsumedKindPrev(
        kind: String,
        title: String,
        id: String,
    ): EntryEntity?

    @Query(
        "SELECT e.* FROM entries e " +
            "WHERE e.kind = :kind AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND (LOWER(e.title) < LOWER(:title) OR (LOWER(e.title) = LOWER(:title) AND e.id < :id)) " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "ORDER BY LOWER(e.title) DESC, e.id DESC LIMIT :limit"
    )
    suspend fun getConsumedKindPrevBatch(
        kind: String,
        title: String,
        id: String,
        limit: Int,
    ): List<EntryEntity>

    @Query(
        "SELECT e.* FROM entries e " +
            "WHERE e.kind = :kind AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.id NOT IN (:excludedIds) " +
            "AND (LOWER(e.title) > LOWER(:title) OR (LOWER(e.title) = LOWER(:title) AND e.id > :id)) " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT 1"
    )
    suspend fun getConsumedKindNextExcl(
        kind: String,
        title: String,
        id: String,
        excludedIds: List<String>,
    ): EntryEntity?

    @Query(
        "SELECT e.* FROM entries e " +
            "WHERE e.kind = :kind AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND (LOWER(e.title) > LOWER(:title) OR (LOWER(e.title) = LOWER(:title) AND e.id > :id)) " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT 1"
    )
    suspend fun getConsumedKindNext(
        kind: String,
        title: String,
        id: String,
    ): EntryEntity?

    @Query(
        "SELECT e.* FROM entries e " +
            "WHERE e.kind = :kind AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND (LOWER(e.title) > LOWER(:title) OR (LOWER(e.title) = LOWER(:title) AND e.id > :id)) " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT :limit"
    )
    suspend fun getConsumedKindNextBatch(
        kind: String,
        title: String,
        id: String,
        limit: Int,
    ): List<EntryEntity>

    @Query(
        "SELECT e.* FROM entries e " +
            "WHERE e.kind = :kind AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "ORDER BY RANDOM() LIMIT :limit"
    )
    suspend fun getRandomConsumedKindEntries(kind: String, limit: Int): List<EntryEntity>

    @Query(
        "SELECT e.id AS id, e.kind AS kind, e.thumbnailUrl AS thumbnailUrl FROM entries e " +
            "WHERE e.kind = :kind AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.thumbnailUrl IS NOT NULL AND e.thumbnailUrl != '' " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "LIMIT :limit"
    )
    suspend fun getDynamicThumbnailPreviews(kind: String, limit: Int): List<EntryThumbnailRow>

    @Query(
        "SELECT e.* FROM entries_fts " +
            "INNER JOIN entries e ON e.rowid = entries_fts.rowid " +
            "WHERE entries_fts MATCH :tokenMatchQuery " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.kind IN ('album', 'artist', 'movie', 'tv', 'book', 'game', 'song') " +
            "ORDER BY CASE WHEN LOWER(e.title) LIKE :prefixPattern ESCAPE '\\' THEN 0 ELSE 1 END, " +
            "LOWER(e.title) ASC, e.id ASC LIMIT :limit"
    )
    suspend fun searchCatalogEntriesByFts(
        tokenMatchQuery: String,
        prefixPattern: String,
        limit: Int,
    ): List<EntryEntity>

    @Query(
        "SELECT COUNT(*) FROM entries_fts " +
            "INNER JOIN entries e ON e.rowid = entries_fts.rowid " +
            "WHERE entries_fts MATCH :tokenMatchQuery " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.kind IN ('album', 'artist', 'movie', 'tv', 'book', 'game', 'song')"
    )
    suspend fun countCatalogEntriesByFts(tokenMatchQuery: String): Int

    @Query(
        """
        SELECT e.* FROM entries e
        WHERE COALESCE(e.mergedIntoId, '') = ''
          AND e.kind IN ('album', 'artist', 'movie', 'tv', 'book', 'game', 'song')
          AND (
              e.title LIKE :likePattern ESCAPE '\'
              OR e.creatorsJson LIKE :likePattern ESCAPE '\'
              OR CAST(e.releaseYear AS TEXT) LIKE :likePattern ESCAPE '\'
              OR e.kind LIKE :likePattern ESCAPE '\'
          )
        ORDER BY CASE WHEN LOWER(e.title) LIKE :prefixPattern ESCAPE '\' THEN 0 ELSE 1 END,
                 LOWER(e.title) ASC, e.id ASC
        LIMIT :limit
        """
    )
    suspend fun searchCatalogEntriesByLike(
        likePattern: String,
        prefixPattern: String,
        limit: Int,
    ): List<EntryEntity>

    @Query(
        """
        SELECT COUNT(*) FROM entries e
        WHERE COALESCE(e.mergedIntoId, '') = ''
          AND e.kind IN ('album', 'artist', 'movie', 'tv', 'book', 'game', 'song')
          AND (
              e.title LIKE :likePattern ESCAPE '\'
              OR e.creatorsJson LIKE :likePattern ESCAPE '\'
              OR CAST(e.releaseYear AS TEXT) LIKE :likePattern ESCAPE '\'
              OR e.kind LIKE :likePattern ESCAPE '\'
          )
        """
    )
    suspend fun countCatalogEntriesByLike(likePattern: String): Int

    @Query(
        "SELECT e.* FROM entries e " +
            "WHERE COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.kind IN ('album', 'artist', 'movie', 'tv', 'book', 'game', 'song') " +
            "AND e.releaseYear = :year " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT :limit"
    )
    suspend fun searchCatalogEntriesByYear(year: Int, limit: Int): List<EntryEntity>

    @Query(
        "SELECT COUNT(*) FROM entries e " +
            "WHERE COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.kind IN ('album', 'artist', 'movie', 'tv', 'book', 'game', 'song') " +
            "AND e.releaseYear = :year"
    )
    suspend fun countCatalogEntriesByYear(year: Int): Int

    @Query(
        "SELECT e.* FROM entries e " +
            "WHERE COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.kind IN (:kinds) " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT :limit"
    )
    suspend fun searchCatalogEntriesByKinds(kinds: List<String>, limit: Int): List<EntryEntity>

    @Query(
        "SELECT COUNT(*) FROM entries e " +
            "WHERE COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.kind IN (:kinds)"
    )
    suspend fun countCatalogEntriesByKinds(kinds: List<String>): Int

    @Query(
        "SELECT e.* FROM entries e " +
            "WHERE COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.kind IN ('album', 'artist', 'movie', 'tv', 'book', 'game', 'song') " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getBrowseCatalogEntriesPage(limit: Int, offset: Int): List<EntryEntity>

    @Query(
        "SELECT COUNT(*) FROM entries e " +
            "WHERE COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.kind IN ('album', 'artist', 'movie', 'tv', 'book', 'game', 'song')"
    )
    suspend fun countBrowseCatalogEntries(): Int

    @Query(
        "SELECT * FROM entries " +
            "WHERE kind = 'artist' AND (mergedIntoId IS NULL OR mergedIntoId = '') " +
            "AND LOWER(title) = LOWER(:title) LIMIT 1"
    )
    suspend fun findActiveArtistByTitle(title: String): EntryEntity?

    @Query(
        "SELECT * FROM entries " +
            "WHERE kind = :kind AND (mergedIntoId IS NULL OR mergedIntoId = '')"
    )
    suspend fun getActiveEntriesByKind(kind: String): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE id IN (:ids)")
    suspend fun getEntriesByIds(ids: List<String>): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE mergedIntoId > ''")
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

    @Query("UPDATE collection_entries SET isLocalDirty = 1 WHERE entryId IN (:ids)")
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

    @Query("SELECT * FROM entry_state ORDER BY interactionUpdatedAt DESC LIMIT :limit")
    suspend fun getRecentEntryStatesPage(limit: Int): List<EntryStateEntity>

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

    @Query("$LIVE_INBOX_IDS_CTE SELECT COUNT(*) $INBOX_CTE_FROM_WHERE")
    suspend fun countItems(): Int

    @Query("$INBOX_SELECT_FULL_PREFIX AND e.id = :id LIMIT 1")
    suspend fun getItemById(id: String): InboxItemRow?

    @Query(INBOX_SELECT_FULL_CTE) suspend fun getAllItems(): List<InboxItemRow>

    @Query("$INBOX_SELECT_LIST_CTE ORDER BY COALESCE(e.publishedAt, e.createdAt) DESC")
    suspend fun getAllPreviewItems(): List<InboxItemRow>

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
            "COALESCE(es.inboxState, 'none') AS inboxState, " +
            "e.kind AS entryKind, " +
            "$IS_FINISHED_EXPR AS isFinished " +
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
            "es.inboxState AS inboxState, " +
            "e.kind AS entryKind, " +
            "$IS_FINISHED_EXPR AS isFinished " +
            "FROM entries e " +
            "INNER JOIN entry_state es ON es.entryId = e.id " +
            "LEFT JOIN entry_content ec ON ec.entryId = e.id " +
            "WHERE e.mergedIntoId IS NULL AND es.inboxState = 'unread' AND NOT $IS_FINISHED_EXPR"
    )
    suspend fun getUnarchivedItemStates(): List<ItemStateRow>

    @Query(
        "SELECT ce.entryId FROM collection_entries ce " +
            "INNER JOIN entry_state es ON es.entryId = ce.entryId " +
            "INNER JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :feedId AND e.mergedIntoId IS NULL " +
            "AND es.inboxState = 'unread' AND NOT $IS_FINISHED_EXPR"
    )
    suspend fun getUnarchivedItemIdsForFeed(feedId: String): List<String>

    @Query("SELECT entryId FROM collection_entries WHERE collectionId = :feedId")
    suspend fun getItemIdsForFeed(feedId: String): List<String>

    @Query(
        "SELECT e.id FROM entries e " +
            "INNER JOIN entry_state es ON es.entryId = e.id " +
            "LEFT JOIN entry_content ec ON ec.entryId = e.id " +
            "WHERE e.mergedIntoId IS NULL AND es.inboxState = 'unread' " +
            "AND NOT $IS_FINISHED_EXPR " +
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
            ") ORDER BY COALESCE(e.publishedAt, e.createdAt) DESC LIMIT :limit"
    )
    suspend fun searchSingleTokenFullItems(
        tokenMatchQuery: String,
        likePattern: String,
        limit: Int = DEFAULT_FTS_SEARCH_LIMIT,
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
            "AND entries_fts MATCH :tokenMatchQuery " +
            "LIMIT :limit"
    )
    suspend fun matchItemIdsForFtsToken(
        tokenMatchQuery: String,
        limit: Int = DEFAULT_FTS_TOKEN_ID_LIMIT,
    ): List<String>

    @Query(
        "SELECT DISTINCT ce.entryId FROM collection_entries ce " +
            "INNER JOIN collection_specs cs ON cs.collectionId = ce.collectionId " +
            "INNER JOIN entries te ON te.id = cs.collectionId " +
            "INNER JOIN entries e ON e.id = ce.entryId " +
            "LEFT JOIN entry_state es ON es.entryId = e.id " +
            "LEFT JOIN entry_content ec ON ec.entryId = e.id " +
            "WHERE cs.role = 'tag' AND te.mergedIntoId IS NULL " +
            "AND $IS_LIVE_ITEM_WHERE " +
            "AND te.title LIKE :likePattern ESCAPE '\\' " +
            "LIMIT :limit"
    )
    suspend fun matchItemIdsByTagLike(
        likePattern: String,
        limit: Int = DEFAULT_FTS_TOKEN_ID_LIMIT,
    ): List<String>

    @Query(
        "$INBOX_SELECT_SEARCH_PREFIX AND e.id IN (:ids) " +
            "ORDER BY COALESCE(e.publishedAt, e.createdAt) DESC LIMIT :limit"
    )
    suspend fun getSearchItemsByIdsChunk(
        ids: List<String>,
        limit: Int = DEFAULT_FTS_SEARCH_LIMIT,
    ): List<InboxItemRow>

    @Query(
        "$INBOX_SELECT_SEARCH_PREFIX AND EXISTS (" +
            "SELECT 1 FROM collection_entries ce_tg " +
            "INNER JOIN collection_specs cs_tg ON cs_tg.collectionId = ce_tg.collectionId " +
            "INNER JOIN entries te_tg ON te_tg.id = cs_tg.collectionId " +
            "WHERE ce_tg.entryId = e.id AND cs_tg.role = 'tag' AND te_tg.mergedIntoId IS NULL " +
            "AND te_tg.title LIKE :likePattern ESCAPE '\\'" +
            ") ORDER BY COALESCE(e.publishedAt, e.createdAt) DESC LIMIT :limit"
    )
    suspend fun searchItemsByTagOnly(
        likePattern: String,
        limit: Int = DEFAULT_FTS_SEARCH_LIMIT,
    ): List<InboxItemRow>

    @Query(
        "SELECT e.id AS entryId, " +
            "snippet(entries_fts, -1, '', '', '…', 14) AS excerpt " +
            "FROM entries e " +
            "INNER JOIN entries_fts ON e.rowid = entries_fts.rowid " +
            "WHERE e.mergedIntoId IS NULL AND entries_fts MATCH :tokenMatchQuery " +
            "LIMIT :limit"
    )
    suspend fun searchEntryFtsSnippets(
        tokenMatchQuery: String,
        limit: Int = DEFAULT_FTS_SEARCH_LIMIT,
    ): List<SearchSnippetRow>

    @Query(
        "SELECT ec.entryId AS entryId, " +
            "snippet(entry_content_fts, -1, '', '', '…', 14) AS excerpt " +
            "FROM entry_content ec " +
            "INNER JOIN entries e ON e.id = ec.entryId " +
            "INNER JOIN entry_content_fts ON ec.rowid = entry_content_fts.rowid " +
            "WHERE e.mergedIntoId IS NULL AND entry_content_fts MATCH :tokenMatchQuery " +
            "LIMIT :limit"
    )
    suspend fun searchContentFtsSnippets(
        tokenMatchQuery: String,
        limit: Int = DEFAULT_FTS_SEARCH_LIMIT,
    ): List<SearchSnippetRow>

    @Query(
        "SELECT e.id FROM collection_specs cs " +
            "INNER JOIN entries e ON e.id = cs.collectionId " +
            "WHERE e.mergedIntoId IS NULL AND cs.role IN ('list', 'want', 'system') " +
            "AND (e.title LIKE :likePattern ESCAPE '\\' " +
            "OR COALESCE(cs.slug, '') LIKE :likePattern ESCAPE '\\') " +
            "ORDER BY e.title COLLATE NOCASE ASC LIMIT :limit"
    )
    suspend fun searchCollectionIdsByTitle(
        likePattern: String,
        limit: Int = DEFAULT_EXTRA_SEARCH_LIMIT,
    ): List<String>

    @Query(
        """
        SELECT $DIARY_LOG_ROW_COLUMNS
        FROM (
            SELECT cl.rowid AS logRowId
            FROM entries_fts
            INNER JOIN entries e ON e.rowid = entries_fts.rowid
            INNER JOIN consumption_log cl ON cl.entryId = e.id
            WHERE entries_fts MATCH :tokenMatchQuery
              AND $DIARY_VISIBLE_ENTRY_WHERE
            UNION
            SELECT cl.rowid AS logRowId
            FROM consumption_log cl
            INNER JOIN entries e ON e.id = cl.entryId
            WHERE cl.notes IS NOT NULL
              AND cl.notes LIKE :likePattern ESCAPE '\'
              AND $DIARY_VISIBLE_ENTRY_WHERE
        ) matched
        INNER JOIN consumption_log cl ON cl.rowid = matched.logRowId
        INNER JOIN entries e ON e.id = cl.entryId
        ORDER BY cl.ts DESC, cl.id DESC
        LIMIT :limit
        """
    )
    suspend fun searchDiaryLogsWithFts(
        tokenMatchQuery: String,
        likePattern: String,
        limit: Int = DEFAULT_EXTRA_SEARCH_LIMIT,
    ): List<DiaryLogRow>

    @Query(
        """
        $DIARY_LOG_ROW_SELECT AND (
            e.title LIKE :likePattern ESCAPE '\'
            OR e.creatorsJson LIKE :likePattern ESCAPE '\'
            OR (cl.notes IS NOT NULL AND cl.notes LIKE :likePattern ESCAPE '\')
        ) ORDER BY cl.ts DESC, cl.id DESC LIMIT :limit
        """
    )
    suspend fun searchDiaryLogsByLike(
        likePattern: String,
        limit: Int = DEFAULT_EXTRA_SEARCH_LIMIT,
    ): List<DiaryLogRow>

    @Transaction
    suspend fun searchFullItemsByTokens(
        tokenQueries: List<String>,
        likePattern: String,
        limit: Int = DEFAULT_FTS_SEARCH_LIMIT,
    ): List<InboxItemRow> =
        when (tokenQueries.size) {
            0 -> searchItemsByTagOnly(likePattern, limit)
            1 -> searchSingleTokenFullItems(tokenQueries.single(), likePattern, limit)
            else -> executeMultiTokenSearch(tokenQueries, likePattern, limit)
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

    @Query(
        "SELECT * FROM entry_identifiers WHERE entryId IN (:entryIds) " +
            "ORDER BY entryId ASC, scheme ASC, value ASC"
    )
    suspend fun getIdentifiersForEntries(entryIds: List<String>): List<EntryIdentifierEntity>

    @Query("SELECT * FROM entry_identifiers WHERE scheme = :scheme AND value IN (:values)")
    suspend fun getIdentifiersBySchemeValues(
        scheme: String,
        values: List<String>,
    ): List<EntryIdentifierEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntryIdentifierIgnore(identifier: EntryIdentifierEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntryIdentifiersIgnore(identifiers: List<EntryIdentifierEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntryIdentifier(identifier: EntryIdentifierEntity)

    @Query("DELETE FROM entry_identifiers WHERE scheme = :scheme AND entryId = :entryId")
    suspend fun deleteEntryIdentifiersByScheme(scheme: String, entryId: String)

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

    @Query(
        "SELECT collectionId FROM collection_specs " +
            "WHERE (slug = :slug OR LOWER(slug) = LOWER(:slug)) " +
            "AND collectionId != :excludeId LIMIT 1"
    )
    suspend fun findOtherCollectionIdBySlug(slug: String, excludeId: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCollectionSpecIgnore(spec: CollectionSpecEntity): Long

    @Update suspend fun updateCollectionSpec(spec: CollectionSpecEntity)

    @Transaction
    suspend fun upsertCollectionSpec(spec: CollectionSpecEntity) {
        val cleanSlug = spec.slug?.trim()?.takeIf { it.isNotEmpty() }
        val cleanRule = spec.ruleJson?.trim()?.takeIf { it.isNotEmpty() }
        val hasConflictingOwner =
            cleanSlug != null && findOtherCollectionIdBySlug(cleanSlug, spec.collectionId) != null
        val safeSlug = if (hasConflictingOwner) null else cleanSlug
        val safeSpec = spec.copy(slug = safeSlug, ruleJson = cleanRule)
        if (insertCollectionSpecIgnore(safeSpec) == -1L) {
            updateCollectionSpec(safeSpec)
        }
    }

    @Transaction
    suspend fun upsertCollectionSpecs(specs: List<CollectionSpecEntity>) {
        for (spec in specs) {
            upsertCollectionSpec(spec)
        }
    }

    @Query("DELETE FROM collection_specs WHERE collectionId = :collectionId")
    suspend fun deleteCollectionSpec(collectionId: String)

    @Query(
        "DELETE FROM collection_specs " +
            "WHERE collectionId = :dupId AND ruleJson IS NULL AND role IN ('list', 'want')"
    )
    suspend fun deleteManualCollectionSpec(dupId: String)

    @Query("SELECT * FROM collection_entries")
    suspend fun getAllCollectionEntries(): List<CollectionEntryEntity>

    @Query("SELECT * FROM collection_entries WHERE isLocalDirty = 1")
    suspend fun getDirtyCollectionEntries(): List<CollectionEntryEntity>

    @Query("SELECT * FROM collection_entries WHERE isLocalDirty = 1 ORDER BY hlc ASC LIMIT :limit")
    suspend fun getDirtyMembershipsPage(limit: Int): List<CollectionEntryEntity>

    @Query(
        "SELECT * FROM collection_entries " +
            "WHERE entryId IN (:entryIds) AND +isLocalDirty = 0 LIMIT :limit"
    )
    suspend fun getCleanMembershipsForEntries(
        entryIds: List<String>,
        limit: Int,
    ): List<CollectionEntryEntity>

    @Query("SELECT * FROM collection_entries WHERE collectionId IN (:collectionIds)")
    suspend fun getMembershipsByColIds(collectionIds: List<String>): List<CollectionEntryEntity>

    @Query("SELECT * FROM collection_entries WHERE entryId IN (:entryIds)")
    suspend fun getMembershipsByEntryIds(entryIds: List<String>): List<CollectionEntryEntity>

    @Query("SELECT DISTINCT collectionId FROM collection_entries")
    suspend fun getDistinctCollectionParents(): List<String>

    @Query(
        "SELECT * FROM collection_entries WHERE collectionId = :entryId " +
            "UNION " +
            "SELECT * FROM collection_entries WHERE entryId = :entryId"
    )
    suspend fun getMembershipsInvolvingEntry(entryId: String): List<CollectionEntryEntity>

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

    @Query(
        "SELECT ce.collectionId AS collectionId, " +
            "COUNT(*) AS totalCount, " +
            "SUM(CASE WHEN EXISTS (" +
            "SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = ce.entryId AND cl.status = 'consumed'" +
            ") THEN 1 ELSE 0 END) AS consumedCount, " +
            "SUM(CASE WHEN NOT EXISTS (" +
            "SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = ce.entryId AND cl.status = 'consumed'" +
            ") AND EXISTS (" +
            "SELECT 1 FROM consumption_log cl2 " +
            "WHERE cl2.entryId = ce.entryId AND cl2.status = 'abandoned'" +
            ") THEN 1 ELSE 0 END) AS abandonedCount " +
            "FROM collection_specs cs " +
            "CROSS JOIN collection_entries ce ON ce.collectionId = cs.collectionId " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE cs.role NOT IN ('feed', 'tag') " +
            "AND (cs.ruleJson IS NULL OR TRIM(cs.ruleJson) = '') " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "GROUP BY ce.collectionId"
    )
    suspend fun getManualCollectionAggregates(): List<CollectionAggregateRow>

    @Query(
        "SELECT e.* FROM collection_entries ce " +
            "INNER JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND (e.mergedIntoId IS NULL OR e.mergedIntoId = '') " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC"
    )
    suspend fun getActiveEntriesForCollection(collectionId: String): List<EntryEntity>

    @Query(
        "SELECT e.* FROM collection_entries ce " +
            "INNER JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND (e.mergedIntoId IS NULL OR e.mergedIntoId = '') " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC"
    )
    suspend fun getConsumedCollectionEntries(collectionId: String): List<EntryEntity>

    @Query(
        "SELECT e.kind AS kind, COUNT(*) AS count " +
            "FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "GROUP BY e.kind ORDER BY count DESC, e.kind ASC"
    )
    suspend fun getManualCollectionKindCounts(collectionId: String): List<KindCountRow>

    @Query(
        "SELECT e.kind AS kind, COUNT(*) AS count " +
            "FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "GROUP BY e.kind ORDER BY count DESC, e.kind ASC"
    )
    suspend fun getGenColKindCounts(collectionId: String): List<KindCountRow>

    @Query(
        "SELECT e.* FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND ce.entryId IN (:ids) " +
            "AND COALESCE(e.mergedIntoId, '') = ''"
    )
    suspend fun getManualColMembersIn(
        collectionId: String,
        ids: List<String>,
    ): List<EntryEntity>

    @Query(
        "SELECT e.* FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND ce.entryId IN (:ids) " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed')"
    )
    suspend fun getGenColMembersIn(
        collectionId: String,
        ids: List<String>,
    ): List<EntryEntity>

    @Query(
        "SELECT COUNT(*) FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.kind IN (SELECT value FROM json_each(:kindsJson))"
    )
    suspend fun countManualColByKinds(collectionId: String, kindsJson: String): Int

    @Query(
        "SELECT COUNT(*) FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.kind IN (SELECT value FROM json_each(:kindsJson)) " +
            "AND e.id NOT IN (SELECT value FROM json_each(:excludedJson))"
    )
    suspend fun countManualColKindsExcl(
        collectionId: String,
        kindsJson: String,
        excludedJson: String,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.kind IN (SELECT value FROM json_each(:kindsJson)) " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed')"
    )
    suspend fun countGenColByKinds(collectionId: String, kindsJson: String): Int

    @Query(
        "SELECT COUNT(*) FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.kind IN (SELECT value FROM json_each(:kindsJson)) " +
            "AND e.id NOT IN (SELECT value FROM json_each(:excludedJson)) " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed')"
    )
    suspend fun countGenColKindsExcl(
        collectionId: String,
        kindsJson: String,
        excludedJson: String,
    ): Int

    @Query(
        "SELECT e.* FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getManualCollectionPage(
        collectionId: String,
        limit: Int,
        offset: Int,
    ): List<EntryEntity>

    @Query(
        "SELECT e.* FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.id NOT IN (SELECT value FROM json_each(:excludedJson)) " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getManualColPageExcl(
        collectionId: String,
        excludedJson: String,
        limit: Int,
        offset: Int,
    ): List<EntryEntity>

    @Query(
        "SELECT e.* FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.kind IN (SELECT value FROM json_each(:kindsJson)) " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getManualColKindsPage(
        collectionId: String,
        kindsJson: String,
        limit: Int,
        offset: Int,
    ): List<EntryEntity>

    @Query(
        "SELECT e.* FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.kind IN (SELECT value FROM json_each(:kindsJson)) " +
            "AND e.id NOT IN (SELECT value FROM json_each(:excludedJson)) " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getManualColKindsPageExcl(
        collectionId: String,
        kindsJson: String,
        excludedJson: String,
        limit: Int,
        offset: Int,
    ): List<EntryEntity>

    @Query(
        "SELECT e.* FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getGeneratorCollectionPage(
        collectionId: String,
        limit: Int,
        offset: Int,
    ): List<EntryEntity>

    @Query(
        "SELECT e.* FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.id NOT IN (SELECT value FROM json_each(:excludedJson)) " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getGenColPageExcl(
        collectionId: String,
        excludedJson: String,
        limit: Int,
        offset: Int,
    ): List<EntryEntity>

    @Query(
        "SELECT e.* FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.kind IN (SELECT value FROM json_each(:kindsJson)) " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getGenColKindsPage(
        collectionId: String,
        kindsJson: String,
        limit: Int,
        offset: Int,
    ): List<EntryEntity>

    @Query(
        "SELECT e.* FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.kind IN (SELECT value FROM json_each(:kindsJson)) " +
            "AND e.id NOT IN (SELECT value FROM json_each(:excludedJson)) " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getGenColKindsPageExcl(
        collectionId: String,
        kindsJson: String,
        excludedJson: String,
        limit: Int,
        offset: Int,
    ): List<EntryEntity>

    @Query(
        "SELECT COUNT(*) FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND (LOWER(e.title) < LOWER(:title) OR (LOWER(e.title) = LOWER(:title) AND e.id < :id))"
    )
    suspend fun countManualCollectionBefore(collectionId: String, title: String, id: String): Int

    @Query(
        "SELECT COUNT(*) FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND (LOWER(e.title) < LOWER(:title) OR (LOWER(e.title) = LOWER(:title) AND e.id < :id)) " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed')"
    )
    suspend fun countGeneratorCollectionBefore(collectionId: String, title: String, id: String): Int

    @Query(
        "SELECT e.* FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND (LOWER(e.title) < LOWER(:title) OR (LOWER(e.title) = LOWER(:title) AND e.id < :id)) " +
            "ORDER BY LOWER(e.title) DESC, e.id DESC LIMIT 1"
    )
    suspend fun getManualCollectionPrev(
        collectionId: String,
        title: String,
        id: String,
    ): EntryEntity?

    @Query(
        "SELECT e.* FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.id NOT IN (SELECT value FROM json_each(:excludedJson)) " +
            "AND (LOWER(e.title) < LOWER(:title) OR (LOWER(e.title) = LOWER(:title) AND e.id < :id)) " +
            "ORDER BY LOWER(e.title) DESC, e.id DESC LIMIT 1"
    )
    suspend fun getManualColPrevExcl(
        collectionId: String,
        title: String,
        id: String,
        excludedJson: String,
    ): EntryEntity?

    @Query(
        "SELECT e.* FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND (LOWER(e.title) > LOWER(:title) OR (LOWER(e.title) = LOWER(:title) AND e.id > :id)) " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT 1"
    )
    suspend fun getManualCollectionNext(
        collectionId: String,
        title: String,
        id: String,
    ): EntryEntity?

    @Query(
        "SELECT e.* FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.id NOT IN (SELECT value FROM json_each(:excludedJson)) " +
            "AND (LOWER(e.title) > LOWER(:title) OR (LOWER(e.title) = LOWER(:title) AND e.id > :id)) " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT 1"
    )
    suspend fun getManualColNextExcl(
        collectionId: String,
        title: String,
        id: String,
        excludedJson: String,
    ): EntryEntity?

    @Query(
        "SELECT e.* FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "AND (LOWER(e.title) < LOWER(:title) OR (LOWER(e.title) = LOWER(:title) AND e.id < :id)) " +
            "ORDER BY LOWER(e.title) DESC, e.id DESC LIMIT 1"
    )
    suspend fun getGeneratorCollectionPrev(
        collectionId: String,
        title: String,
        id: String,
    ): EntryEntity?

    @Query(
        "SELECT e.* FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.id NOT IN (SELECT value FROM json_each(:excludedJson)) " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "AND (LOWER(e.title) < LOWER(:title) OR (LOWER(e.title) = LOWER(:title) AND e.id < :id)) " +
            "ORDER BY LOWER(e.title) DESC, e.id DESC LIMIT 1"
    )
    suspend fun getGenColPrevExcl(
        collectionId: String,
        title: String,
        id: String,
        excludedJson: String,
    ): EntryEntity?

    @Query(
        "SELECT e.* FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "AND (LOWER(e.title) > LOWER(:title) OR (LOWER(e.title) = LOWER(:title) AND e.id > :id)) " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT 1"
    )
    suspend fun getGeneratorCollectionNext(
        collectionId: String,
        title: String,
        id: String,
    ): EntryEntity?

    @Query(
        "SELECT e.* FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND e.id NOT IN (SELECT value FROM json_each(:excludedJson)) " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "AND (LOWER(e.title) > LOWER(:title) OR (LOWER(e.title) = LOWER(:title) AND e.id > :id)) " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT 1"
    )
    suspend fun getGenColNextExcl(
        collectionId: String,
        title: String,
        id: String,
        excludedJson: String,
    ): EntryEntity?

    @Query(
        "SELECT e.* FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "ORDER BY RANDOM() LIMIT :limit"
    )
    suspend fun getRandomManualColEntries(
        collectionId: String,
        limit: Int,
    ): List<EntryEntity>

    @Query(
        "SELECT e.* FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "ORDER BY RANDOM() LIMIT :limit"
    )
    suspend fun getRandomGenColEntries(
        collectionId: String,
        limit: Int,
    ): List<EntryEntity>

    @Query(
        "SELECT e.id AS id, e.kind AS kind, e.thumbnailUrl AS thumbnailUrl " +
            "FROM collection_entries ce " +
            "INNER JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND (e.mergedIntoId IS NULL OR e.mergedIntoId = '') " +
            "AND e.thumbnailUrl IS NOT NULL AND e.thumbnailUrl != '' " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT :limit"
    )
    suspend fun getManualThumbPreviews(
        collectionId: String,
        limit: Int,
    ): List<EntryThumbnailRow>

    @Query(
        "SELECT e.id AS id, e.kind AS kind, e.thumbnailUrl AS thumbnailUrl " +
            "FROM collection_entries ce " +
            "INNER JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND (e.mergedIntoId IS NULL OR e.mergedIntoId = '') " +
            "AND e.thumbnailUrl IS NOT NULL AND e.thumbnailUrl != '' " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed') " +
            "ORDER BY LOWER(e.title) ASC, e.id ASC LIMIT :limit"
    )
    suspend fun getGeneratorThumbPreviews(
        collectionId: String,
        limit: Int,
    ): List<EntryThumbnailRow>

    @Query(
        "SELECT ce.entryId FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = '$STARRED_COLLECTION_ID' " +
            "AND COALESCE(e.mergedIntoId, '') = ''"
    )
    suspend fun getAllStarredEntryIds(): List<String>

    @Query(
        "SELECT DISTINCT ce.entryId FROM (" +
            "SELECT collectionId FROM collection_specs WHERE LOWER(role) = 'want' " +
            "UNION SELECT '$WANT_TO_READ_COLLECTION_ID'" +
            ") wc " +
            "CROSS JOIN collection_entries ce ON ce.collectionId = wc.collectionId " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE COALESCE(e.mergedIntoId, '') = ''"
    )
    suspend fun getAllWantEntryIds(): List<String>

    @Query(
        "SELECT ce.entryId FROM collection_entries ce " +
            "CROSS JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND COALESCE(e.mergedIntoId, '') = ''"
    )
    suspend fun getActiveCollectionEntryIds(collectionId: String): List<String>

    @Query(
        "SELECT DISTINCT ce.entryId FROM collection_entries ce " +
            "LEFT JOIN collection_specs cs ON cs.collectionId = ce.collectionId " +
            "WHERE ce.entryId IN (:entryIds) " +
            "AND (ce.collectionId = '$WANT_TO_READ_COLLECTION_ID' " +
            "OR LOWER(COALESCE(cs.role, '')) = 'want')"
    )
    suspend fun getWantEntryIdsIn(entryIds: List<String>): List<String>

    @Query(
        "SELECT entryId FROM collection_entries " +
            "WHERE collectionId = '$STARRED_COLLECTION_ID' AND entryId IN (:entryIds)"
    )
    suspend fun getStarredEntryIdsIn(entryIds: List<String>): List<String>

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

    @Query(
        "WITH RECURSIVE descendants(id, depth) AS (" +
            "SELECT :childId, 0 " +
            "UNION " +
            "SELECT ce.entryId, d.depth + 1 " +
            "FROM collection_entries ce " +
            "INNER JOIN descendants d ON ce.collectionId = d.id " +
            "WHERE d.depth < 64" +
            ") " +
            "SELECT CASE WHEN EXISTS (" +
            "SELECT 1 FROM descendants WHERE id = :parentId OR depth >= 64" +
            ") THEN 1 ELSE 0 END"
    )
    suspend fun hasCollectionCycleRaw(parentId: String, childId: String): Int

    @Query(
        "SELECT DISTINCT ce.entryId FROM collection_entries ce " +
            "INNER JOIN entries e ON e.id = ce.entryId " +
            "WHERE ce.collectionId = :collectionId " +
            "AND (e.mergedIntoId IS NULL OR e.mergedIntoId = '') " +
            "AND NOT EXISTS (" +
            "SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = ce.entryId AND cl.status IN ('consumed', 'abandoned')" +
            ") ORDER BY ce.entryId ASC"
    )
    suspend fun getEligibleDrawEntryIds(collectionId: String): List<String>

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

    @Query("SELECT * FROM consumption_log WHERE isLocalDirty = 1 ORDER BY hlc ASC LIMIT :limit")
    suspend fun getDirtyLogsPage(limit: Int): List<ConsumptionLogEntity>

    @Query(
        "SELECT * FROM consumption_log " +
            "WHERE entryId IN (:entryIds) AND +isLocalDirty = 0 LIMIT :limit"
    )
    suspend fun getCleanLogsForEntries(
        entryIds: List<String>,
        limit: Int,
    ): List<ConsumptionLogEntity>

    @Query("SELECT DISTINCT entryId, status FROM consumption_log")
    suspend fun getDistinctEntryLogStatuses(): List<EntryStatusRow>

    @Query(
        "SELECT cl.entryId AS entryId, cl.status AS status, cl.notes AS notes " +
            "FROM (SELECT DISTINCT entryId FROM consumption_log) d " +
            "INNER JOIN consumption_log cl ON cl.rowid = (" +
            "SELECT cl2.rowid FROM consumption_log cl2 " +
            "WHERE cl2.entryId = d.entryId " +
            "ORDER BY cl2.ts DESC, cl2.id DESC LIMIT 1" +
            ")"
    )
    suspend fun getLatestLogsPerEntry(): List<LatestEntryLogRow>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM consumption_log " +
            "WHERE entryId = :entryId AND status = 'consumed')"
    )
    suspend fun hasConsumedLogForEntry(entryId: String): Boolean

    @Query("SELECT DISTINCT status FROM consumption_log WHERE entryId = :entryId")
    suspend fun getStatusesForEntry(entryId: String): List<String>

    @Query("SELECT DISTINCT entryId, status FROM consumption_log WHERE entryId IN (:entryIds)")
    suspend fun getEntryStatusesByIds(entryIds: List<String>): List<EntryStatusRow>

    @Query(
        "SELECT DISTINCT entryId FROM consumption_log " +
            "WHERE entryId IN (:entryIds) AND status = 'consumed'"
    )
    suspend fun getConsumedEntryIdsIn(entryIds: List<String>): List<String>

    @Query(
        "SELECT e.kind AS kind, COUNT(*) AS count " +
            "FROM (SELECT DISTINCT entryId FROM consumption_log WHERE status = 'consumed') c " +
            "CROSS JOIN entries e ON e.id = c.entryId " +
            "WHERE COALESCE(e.mergedIntoId, '') = '' " +
            "GROUP BY e.kind"
    )
    suspend fun getConsumedEntryCountsByKind(): List<KindCountRow>

    @Query(
        "SELECT e.id AS id, e.kind AS kind FROM entries e " +
            "WHERE e.id IN (:ids) AND COALESCE(e.mergedIntoId, '') = '' " +
            "AND EXISTS (SELECT 1 FROM consumption_log cl " +
            "WHERE cl.entryId = e.id AND cl.status = 'consumed')"
    )
    suspend fun getConsumedEntryKindsByIds(ids: List<String>): List<EntryKindRow>

    @Query(
        "SELECT COUNT(*) FROM consumption_log cl " +
            "INNER JOIN entries e ON e.id = cl.entryId " +
            "WHERE $DIARY_VISIBLE_ENTRY_WHERE"
    )
    suspend fun countDiaryLogs(): Int

    @Query(
        "SELECT COUNT(*) FROM entries e " +
            "CROSS JOIN consumption_log cl ON cl.entryId = e.id " +
            "WHERE e.kind IN $DIARY_NON_SONG_KIND_IN AND $DIARY_ACTIVE_ENTRY_WHERE"
    )
    suspend fun countDiaryLogsNonSong(): Int

    @Query(
        "SELECT SUBSTR(cl.ts, 1, 4) AS year, COUNT(*) AS count " +
            "FROM consumption_log cl " +
            "CROSS JOIN entries e ON e.id = cl.entryId " +
            "WHERE $DIARY_VISIBLE_ENTRY_WHERE AND cl.ts GLOB '[0-9][0-9][0-9][0-9]*' " +
            "GROUP BY year ORDER BY year DESC"
    )
    suspend fun getDiaryYearCounts(): List<DiaryYearCountRow>

    @Query(
        "SELECT SUBSTR(cl.ts, 1, 4) AS year, COUNT(*) AS count " +
            "FROM entries e " +
            "CROSS JOIN consumption_log cl ON cl.entryId = e.id " +
            "WHERE e.kind IN $DIARY_NON_SONG_KIND_IN AND $DIARY_ACTIVE_ENTRY_WHERE " +
            "AND cl.ts GLOB '[0-9][0-9][0-9][0-9]*' " +
            "GROUP BY year ORDER BY year DESC"
    )
    suspend fun getDiaryYearCountsNonSong(): List<DiaryYearCountRow>

    @Query(
        "SELECT SUBSTR(cl.ts, 1, 4) AS year, COUNT(*) AS count " +
            "FROM entries e " +
            "CROSS JOIN consumption_log cl ON cl.entryId = e.id " +
            "WHERE e.kind = :kind AND $DIARY_ACTIVE_ENTRY_WHERE " +
            "AND cl.ts GLOB '[0-9][0-9][0-9][0-9]*' " +
            "GROUP BY year ORDER BY year DESC"
    )
    suspend fun getDiaryYearCountsForKind(kind: String): List<DiaryYearCountRow>

    @Query(
        "SELECT SUBSTR(cl.ts, 1, 7) AS monthKey, COUNT(*) AS count " +
            "FROM consumption_log cl " +
            "CROSS JOIN entries e ON e.id = cl.entryId " +
            "WHERE $DIARY_VISIBLE_ENTRY_WHERE " +
            "AND cl.ts >= :minTs AND cl.ts < :maxTs " +
            "GROUP BY monthKey ORDER BY monthKey DESC"
    )
    suspend fun getDiaryMonthCounts(minTs: String, maxTs: String): List<DiaryMonthCountRow>

    @Query(
        "SELECT SUBSTR(cl.ts, 1, 7) AS monthKey, COUNT(*) AS count " +
            "FROM consumption_log cl " +
            "CROSS JOIN entries e ON e.id = cl.entryId " +
            "WHERE $DIARY_VISIBLE_ENTRY_WHERE " +
            "AND cl.status = :status " +
            "AND cl.ts >= :minTs AND cl.ts < :maxTs " +
            "GROUP BY monthKey ORDER BY monthKey DESC"
    )
    suspend fun getDiaryMonthCountsByStatus(
        status: String,
        minTs: String,
        maxTs: String,
    ): List<DiaryMonthCountRow>

    @Query(
        "SELECT SUBSTR(cl.ts, 1, 7) AS monthKey, COUNT(*) AS count " +
            "FROM consumption_log cl " +
            "CROSS JOIN entries e ON e.id = cl.entryId " +
            "WHERE $DIARY_VISIBLE_ENTRY_WHERE " +
            "AND +cl.status = 'consumed' " +
            "AND cl.ts >= :minTs AND cl.ts < :maxTs " +
            "GROUP BY monthKey ORDER BY monthKey DESC"
    )
    suspend fun getDiaryMonthCountsConsumed(minTs: String, maxTs: String): List<DiaryMonthCountRow>

    @Query(
        "SELECT SUBSTR(cl.ts, 1, 7) AS monthKey, COUNT(*) AS count " +
            "FROM entries e " +
            "CROSS JOIN consumption_log cl ON cl.entryId = e.id " +
            "WHERE e.kind IN $DIARY_NON_SONG_KIND_IN AND $DIARY_ACTIVE_ENTRY_WHERE " +
            "AND cl.ts >= :minTs AND cl.ts < :maxTs " +
            "GROUP BY monthKey ORDER BY monthKey DESC"
    )
    suspend fun getDiaryMonthCountsNonSong(minTs: String, maxTs: String): List<DiaryMonthCountRow>

    @Query(
        "SELECT SUBSTR(cl.ts, 1, 7) AS monthKey, COUNT(*) AS count " +
            "FROM entries e " +
            "CROSS JOIN consumption_log cl ON cl.entryId = e.id " +
            "WHERE e.kind IN $DIARY_NON_SONG_KIND_IN AND $DIARY_ACTIVE_ENTRY_WHERE " +
            "AND +cl.status = :status " +
            "AND cl.ts >= :minTs AND cl.ts < :maxTs " +
            "GROUP BY monthKey ORDER BY monthKey DESC"
    )
    suspend fun getDiaryMonthNonSongStatus(
        status: String,
        minTs: String,
        maxTs: String,
    ): List<DiaryMonthCountRow>

    @Query(
        "SELECT SUBSTR(cl.ts, 1, 7) AS monthKey, COUNT(*) AS count " +
            "FROM entries e " +
            "CROSS JOIN consumption_log cl ON cl.entryId = e.id " +
            "WHERE e.kind = :kind AND $DIARY_ACTIVE_ENTRY_WHERE " +
            "AND cl.ts >= :minTs AND cl.ts < :maxTs " +
            "GROUP BY monthKey ORDER BY monthKey DESC"
    )
    suspend fun getDiaryMonthCountsForKind(
        kind: String,
        minTs: String,
        maxTs: String,
    ): List<DiaryMonthCountRow>

    @Query(
        "SELECT SUBSTR(cl.ts, 1, 7) AS monthKey, COUNT(*) AS count " +
            "FROM entries e " +
            "CROSS JOIN consumption_log cl ON cl.entryId = e.id " +
            "WHERE e.kind = :kind AND $DIARY_ACTIVE_ENTRY_WHERE " +
            "AND +cl.status = :status " +
            "AND cl.ts >= :minTs AND cl.ts < :maxTs " +
            "GROUP BY monthKey ORDER BY monthKey DESC"
    )
    suspend fun getDiaryMonthKindStatus(
        kind: String,
        status: String,
        minTs: String,
        maxTs: String,
    ): List<DiaryMonthCountRow>

    @Query(
        "SELECT SUBSTR(cl.ts, 1, 7) AS monthKey, COUNT(*) AS count " +
            "FROM consumption_log cl " +
            "CROSS JOIN entries e ON e.id = cl.entryId " +
            "WHERE e.kind = 'song' AND $DIARY_ACTIVE_ENTRY_WHERE " +
            "AND cl.status = :status " +
            "AND cl.ts >= :minTs AND cl.ts < :maxTs " +
            "GROUP BY monthKey ORDER BY monthKey DESC"
    )
    suspend fun getDiaryMonthSongStatus(
        status: String,
        minTs: String,
        maxTs: String,
    ): List<DiaryMonthCountRow>

    @Query(
        "SELECT e.kind AS kind, COUNT(*) AS count " +
            "FROM entries e " +
            "CROSS JOIN consumption_log cl ON cl.entryId = e.id " +
            "WHERE e.kind IN $DIARY_NON_SONG_KIND_IN AND $DIARY_ACTIVE_ENTRY_WHERE " +
            "AND cl.ts >= :minTs AND cl.ts < :maxTs " +
            "GROUP BY e.kind ORDER BY count DESC, e.kind ASC"
    )
    suspend fun getDiaryNonSongKindCounts(minTs: String, maxTs: String): List<KindCountRow>

    @Query(
        "SELECT e.kind AS kind, COUNT(*) AS count " +
            "FROM entries e " +
            "CROSS JOIN consumption_log cl ON cl.entryId = e.id " +
            "WHERE e.kind IN $DIARY_NON_SONG_KIND_IN AND $DIARY_ACTIVE_ENTRY_WHERE " +
            "AND +cl.status = :status " +
            "AND cl.ts >= :minTs AND cl.ts < :maxTs " +
            "GROUP BY e.kind ORDER BY count DESC, e.kind ASC"
    )
    suspend fun getDiaryNonSongKindsStatus(
        status: String,
        minTs: String,
        maxTs: String,
    ): List<KindCountRow>

    @Query(
        "SELECT EXISTS(" +
            "SELECT 1 FROM entries e " +
            "CROSS JOIN consumption_log cl ON cl.entryId = e.id " +
            "WHERE e.kind = 'song' AND $DIARY_ACTIVE_ENTRY_WHERE)"
    )
    suspend fun hasAnyDiarySongLogs(): Boolean

    @Query(
        "SELECT EXISTS(" +
            "SELECT 1 FROM consumption_log cl " +
            "CROSS JOIN entries e ON e.id = cl.entryId " +
            "WHERE $DIARY_VISIBLE_ENTRY_WHERE)"
    )
    suspend fun hasAnyDiaryLogs(): Boolean

    @Query(
        "$DIARY_LOG_ROW_CROSS_SELECT " +
            "AND cl.ts >= :minTs AND cl.ts < :maxTs " +
            "ORDER BY cl.ts DESC, cl.id DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun getDiaryLogPage(
        minTs: String,
        maxTs: String,
        limit: Int,
        offset: Int,
    ): List<DiaryLogRow>

    @Query(
        "$DIARY_LOG_ROW_CROSS_SELECT " +
            "AND cl.status = :status " +
            "AND cl.ts >= :minTs AND cl.ts < :maxTs " +
            "ORDER BY cl.ts DESC, cl.id DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun getDiaryLogPageByStatus(
        status: String,
        minTs: String,
        maxTs: String,
        limit: Int,
        offset: Int,
    ): List<DiaryLogRow>

    @Query(
        "$DIARY_LOG_ROW_ENTRIES_CROSS_SELECT " +
            "AND e.kind IN $DIARY_NON_SONG_KIND_IN " +
            "AND cl.ts >= :minTs AND cl.ts < :maxTs " +
            "ORDER BY cl.ts DESC, cl.id DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun getDiaryLogPageNonSong(
        minTs: String,
        maxTs: String,
        limit: Int,
        offset: Int,
    ): List<DiaryLogRow>

    @Query(
        "$DIARY_LOG_ROW_ENTRIES_CROSS_SELECT " +
            "AND e.kind IN $DIARY_NON_SONG_KIND_IN " +
            "AND +cl.status = :status " +
            "AND cl.ts >= :minTs AND cl.ts < :maxTs " +
            "ORDER BY cl.ts DESC, cl.id DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun getDiaryLogPageNonSongByStatus(
        status: String,
        minTs: String,
        maxTs: String,
        limit: Int,
        offset: Int,
    ): List<DiaryLogRow>

    @Query(
        "WITH started_candidates AS (" +
            "SELECT cl.id, cl.entryId, cl.ts, cl.status, cl.notes, cl.createdAt, " +
            "ROW_NUMBER() OVER (" +
            "PARTITION BY cl.entryId " +
            "ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', cl.ts) DESC, " +
            "strftime('%Y-%m-%dT%H:%M:%fZ', cl.createdAt) DESC, " +
            "cl.id DESC" +
            ") AS rn " +
            "FROM consumption_log cl " +
            "INNER JOIN entries e ON e.id = cl.entryId " +
            "WHERE cl.status = 'started' " +
            "AND $DIARY_VISIBLE_ENTRY_WHERE " +
            ") " +
            "SELECT sc.id AS id, " +
            "sc.entryId AS entryId, " +
            "e.kind AS kind, " +
            "e.title AS title, " +
            "e.creatorsJson AS creatorsJson, " +
            "e.releaseYear AS releaseYear, " +
            "e.url AS url, " +
            "e.thumbnailUrl AS thumbnailUrl, " +
            "sc.ts AS ts, " +
            "sc.status AS status, " +
            "sc.notes AS notes " +
            "FROM started_candidates sc " +
            "INNER JOIN entries e ON e.id = sc.entryId " +
            "WHERE sc.rn = 1 " +
            "AND NOT EXISTS (" +
            "SELECT 1 FROM consumption_log cl_later " +
            "WHERE cl_later.entryId = sc.entryId " +
            "AND cl_later.status IN ('consumed', 'abandoned') " +
            "AND strftime('%Y-%m-%dT%H:%M:%fZ', cl_later.ts) >= " +
            "strftime('%Y-%m-%dT%H:%M:%fZ', sc.ts)" +
            ") " +
            "AND NOT EXISTS (" +
            "SELECT 1 FROM entry_state es " +
            "WHERE es.entryId = sc.entryId " +
            "AND (" +
            "es.inboxState = 'deleted' " +
            "OR (" +
            "(" +
            "es.inboxState = 'dismissed' " +
            "OR (" +
            "COALESCE(es.inboxState, 'none') = 'none' " +
            "AND es.readAt IS NOT NULL " +
            "AND NOT EXISTS (" +
            "SELECT 1 FROM collection_entries ce_w " +
            "WHERE ce_w.collectionId = '$WANT_TO_READ_COLLECTION_ID' " +
            "AND ce_w.entryId = sc.entryId" +
            ")" +
            ")" +
            ") " +
            "AND COALESCE(" +
            "strftime('%Y-%m-%dT%H:%M:%fZ', es.interactionUpdatedAt), " +
            "strftime('%Y-%m-%dT%H:%M:%fZ', es.readAt), " +
            "strftime('%Y-%m-%dT%H:%M:%fZ', es.updatedAt)" +
            ") >= strftime('%Y-%m-%dT%H:%M:%fZ', sc.ts)" +
            ")" +
            ")" +
            ") " +
            "ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', sc.ts) DESC, sc.id DESC " +
            "LIMIT :limit"
    )
    suspend fun getActiveStartedEntries(limit: Int = 100): List<DiaryLogRow>

    @Query(
        "$DIARY_LOG_ROW_ENTRIES_CROSS_SELECT " +
            "AND e.kind = :kind " +
            "AND cl.ts >= :minTs AND cl.ts < :maxTs " +
            "ORDER BY cl.ts DESC, cl.id DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun getDiaryLogPageForKind(
        kind: String,
        minTs: String,
        maxTs: String,
        limit: Int,
        offset: Int,
    ): List<DiaryLogRow>

    @Query(
        "$DIARY_LOG_ROW_ENTRIES_CROSS_SELECT " +
            "AND e.kind = :kind " +
            "AND +cl.status = :status " +
            "AND cl.ts >= :minTs AND cl.ts < :maxTs " +
            "ORDER BY cl.ts DESC, cl.id DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun getDiaryLogPageForKindByStatus(
        kind: String,
        status: String,
        minTs: String,
        maxTs: String,
        limit: Int,
        offset: Int,
    ): List<DiaryLogRow>

    @Query(
        "SELECT $DIARY_LOG_ROW_COLUMNS " +
            "FROM consumption_log cl " +
            "CROSS JOIN entries e ON e.id = cl.entryId " +
            "WHERE e.kind = 'song' AND $DIARY_ACTIVE_ENTRY_WHERE " +
            "AND cl.ts >= :minTs AND cl.ts < :maxTs " +
            "ORDER BY cl.ts DESC, cl.id DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun getDiaryLogPageForSong(
        minTs: String,
        maxTs: String,
        limit: Int,
        offset: Int,
    ): List<DiaryLogRow>

    @Query(
        "SELECT $DIARY_LOG_ROW_COLUMNS " +
            "FROM consumption_log cl " +
            "CROSS JOIN entries e ON e.id = cl.entryId " +
            "WHERE e.kind = 'song' AND $DIARY_ACTIVE_ENTRY_WHERE " +
            "AND cl.status = :status " +
            "AND cl.ts >= :minTs AND cl.ts < :maxTs " +
            "ORDER BY cl.ts DESC, cl.id DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun getDiaryLogPageForSongByStatus(
        status: String,
        minTs: String,
        maxTs: String,
        limit: Int,
        offset: Int,
    ): List<DiaryLogRow>

    @Query(
        "$DIARY_LOG_ROW_SELECT " +
            "AND cl.entryId = :entryId " +
            "ORDER BY cl.ts DESC, cl.id DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun getDiaryLogsForEntry(entryId: String, limit: Int, offset: Int): List<DiaryLogRow>

    @Query(
        "SELECT COUNT(*) FROM consumption_log cl " +
            "INNER JOIN entries e ON e.id = cl.entryId " +
            "WHERE $DIARY_VISIBLE_ENTRY_WHERE AND cl.entryId = :entryId"
    )
    suspend fun countDiaryLogsForEntry(entryId: String): Int

    @Query("SELECT * FROM consumption_log WHERE id IN (:ids)")
    suspend fun getConsumptionLogsByIds(ids: List<String>): List<ConsumptionLogEntity>

    @Query(
        "SELECT * FROM consumption_log " + "WHERE entryId IN (:entryIds) AND status = 'consumed'"
    )
    suspend fun getConsumedLogsForEntries(entryIds: List<String>): List<ConsumptionLogEntity>

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

    @Query("UPDATE consumption_log SET isLocalDirty = 0 WHERE id = :id AND hlc = :expectedHlc")
    suspend fun clearLogDirtyIfSameHlc(id: String, expectedHlc: String)

    @Query("DELETE FROM consumption_log WHERE id = :id")
    suspend fun deleteConsumptionLogById(id: String)

    @Query("UPDATE consumption_log SET entryId = :canonicalId WHERE entryId = :dupId")
    suspend fun repointConsumptionLogsToEntry(dupId: String, canonicalId: String)

    @Query("SELECT * FROM comparisons ORDER BY ts ASC")
    suspend fun getAllComparisons(): List<ComparisonEntity>

    @Query(
        "SELECT * FROM comparisons " +
            "WHERE scopeCollectionId IS NULL OR TRIM(scopeCollectionId) = '' " +
            "ORDER BY ts ASC"
    )
    suspend fun getGlobalComparisons(): List<ComparisonEntity>

    @Query("SELECT * FROM comparisons WHERE scopeCollectionId IN (:scopeIds) ORDER BY ts ASC")
    suspend fun getScopedComparisonsIn(scopeIds: List<String>): List<ComparisonEntity>

    @Query("SELECT COUNT(*) FROM comparisons") suspend fun countComparisons(): Int

    @Query("SELECT * FROM comparisons WHERE isLocalDirty = 1")
    suspend fun getDirtyComparisons(): List<ComparisonEntity>

    @Query("SELECT * FROM comparisons WHERE isLocalDirty = 1 ORDER BY hlc ASC LIMIT :limit")
    suspend fun getDirtyComparisonsPage(limit: Int): List<ComparisonEntity>

    @Query("SELECT * FROM comparisons WHERE +isLocalDirty = 0 ORDER BY ts DESC LIMIT :limit")
    suspend fun getCleanComparisonsByTsPage(limit: Int): List<ComparisonEntity>

    @Query(
        "SELECT * FROM comparisons " +
            "WHERE scopeCollectionId = :entryId OR orderingJson LIKE :likePattern ESCAPE '\\'"
    )
    suspend fun getComparisonsInvolvingEntry(
        entryId: String,
        likePattern: String,
    ): List<ComparisonEntity>

    @Query("SELECT * FROM comparisons WHERE id = :id LIMIT 1")
    suspend fun getComparisonById(id: String): ComparisonEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertComparisonIgnore(comparison: ComparisonEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertComparisonsIgnore(comparisons: List<ComparisonEntity>)

    @Query("UPDATE comparisons SET isLocalDirty = 0 WHERE id = :id AND hlc = :expectedHlc")
    suspend fun clearComparisonDirtyIfSameHlc(id: String, expectedHlc: String)

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

    @Query("DELETE FROM local_sync_flags WHERE flagKey = :key")
    suspend fun deleteLocalSyncFlag(key: String)

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
            "OR EXISTS (SELECT 1 FROM comparisons " +
            "WHERE orderingJson LIKE :quotedPattern ESCAPE '\\') " +
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

    // --- Boards (`boards`, `board_items`, `board_item_states`) ---

    @Query("SELECT * FROM boards ORDER BY title COLLATE NOCASE ASC, id ASC")
    suspend fun getAllBoards(): List<BoardEntity>

    @Query("SELECT * FROM boards WHERE isActive = 1 ORDER BY title COLLATE NOCASE ASC, id ASC")
    suspend fun getActiveBoards(): List<BoardEntity>

    @Query("SELECT * FROM boards WHERE isLocalDirty = 1 OR isSnapshotDirty = 1")
    suspend fun getDirtyBoards(): List<BoardEntity>

    @Query("SELECT * FROM boards WHERE id = :id LIMIT 1")
    suspend fun getBoardById(id: String): BoardEntity?

    @Query(
        "SELECT * FROM boards " +
            "WHERE LOWER(adapterType) = LOWER(:adapterType) " +
            "AND LOWER(sourceKey) = LOWER(:sourceKey) LIMIT 1"
    )
    suspend fun getBoardBySourceKey(adapterType: String, sourceKey: String): BoardEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBoardIgnore(board: BoardEntity): Long

    @Update suspend fun updateBoard(board: BoardEntity)

    @Transaction
    suspend fun upsertBoard(board: BoardEntity) {
        if (insertBoardIgnore(board) == -1L) {
            updateBoard(board)
        }
    }

    @Query("DELETE FROM boards WHERE id = :id") suspend fun deleteBoardById(id: String)

    @Query(
        "UPDATE boards SET isLocalDirty = 0 " +
            "WHERE id = :id AND hlc = :expectedHlc " +
            "AND snapshotHlc = :expectedSnapshotHlc " +
            "AND COALESCE(lastPolledAt, '') = :expectedLastPolledAt"
    )
    suspend fun clearBoardDirtyIfSameHlc(
        id: String,
        expectedHlc: String,
        expectedSnapshotHlc: String,
        expectedLastPolledAt: String,
    )

    @Suppress("FunctionNameMaxLength")
    @Query(
        "UPDATE boards SET isSnapshotDirty = 0 " +
            "WHERE id = :id AND snapshotHlc = :expectedSnapshotHlc"
    )
    suspend fun clearBoardSnapshotDirtyIfSameHlc(id: String, expectedSnapshotHlc: String)

    @Query("SELECT * FROM board_items ORDER BY boardId ASC, rank ASC")
    suspend fun getAllBoardItems(): List<BoardItemEntity>

    @Query("SELECT * FROM board_items WHERE boardId = :boardId ORDER BY rank ASC")
    suspend fun getBoardItemsForBoard(boardId: String): List<BoardItemEntity>

    @Query("SELECT * FROM board_items WHERE boardId IN (:boardIds) ORDER BY boardId ASC, rank ASC")
    suspend fun getBoardItemsByBoardIds(boardIds: List<String>): List<BoardItemEntity>

    @Query(
        "SELECT * FROM board_items WHERE boardId = :boardId AND externalId = :externalId LIMIT 1"
    )
    suspend fun getBoardItem(boardId: String, externalId: String): BoardItemEntity?

    @Query("SELECT COUNT(*) FROM board_items WHERE boardId = :boardId")
    suspend fun countBoardItems(boardId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBoardItems(items: List<BoardItemEntity>)

    @Query("DELETE FROM board_items WHERE boardId = :boardId")
    suspend fun deleteBoardItemsForBoard(boardId: String)

    @Transaction
    suspend fun replaceBoardItemsInTx(
        boardId: String,
        items: List<BoardItemEntity>,
        snapshotFetchedAt: String,
    ) {
        deleteBoardItemsForBoard(boardId)
        if (items.isNotEmpty()) {
            insertBoardItems(items)
            val externalIds = items.map { it.externalId }.distinct()
            for (chunk in externalIds.chunked(SQLITE_DELETE_CHUNK_SIZE)) {
                touchBoardStatesInSnapshot(boardId, chunk, snapshotFetchedAt)
            }
        }
    }

    @Query("SELECT * FROM board_item_states")
    suspend fun getAllBoardItemStates(): List<BoardItemStateEntity>

    @Query("SELECT * FROM board_item_states WHERE isLocalDirty = 1")
    suspend fun getDirtyBoardItemStates(): List<BoardItemStateEntity>

    @Query("SELECT * FROM board_item_states WHERE boardId = :boardId")
    suspend fun getBoardItemStatesForBoard(boardId: String): List<BoardItemStateEntity>

    @Query(
        "SELECT * FROM board_item_states " +
            "WHERE boardId = :boardId AND externalId = :externalId LIMIT 1"
    )
    suspend fun getBoardItemState(boardId: String, externalId: String): BoardItemStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBoardItemState(state: BoardItemStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBoardItemStates(states: List<BoardItemStateEntity>)

    @Query("DELETE FROM board_item_states WHERE boardId = :boardId AND externalId = :externalId")
    suspend fun deleteBoardItemState(boardId: String, externalId: String)

    @Suppress("FunctionNameMaxLength")
    @Query(
        "UPDATE board_item_states SET isLocalDirty = 0 " +
            "WHERE boardId = :boardId AND externalId = :externalId AND hlc = :expectedHlc"
    )
    suspend fun clearBoardItemStateDirtyIfSameHlc(
        boardId: String,
        externalId: String,
        expectedHlc: String,
    )

    @Query(
        "UPDATE board_item_states " +
            "SET lastSeenInSnapshotAt = CASE " +
            "WHEN julianday(lastSeenInSnapshotAt) IS NULL " +
            "OR julianday(lastSeenInSnapshotAt) < julianday(:fetchedAt) " +
            "THEN :fetchedAt ELSE lastSeenInSnapshotAt END " +
            "WHERE boardId = :boardId AND externalId IN (:externalIds)"
    )
    suspend fun touchBoardStatesInSnapshot(
        boardId: String,
        externalIds: List<String>,
        fetchedAt: String,
    )

    @Query(
        "DELETE FROM board_item_states " +
            "WHERE julianday(lastSeenInSnapshotAt) < julianday(:cutoffIso) " +
            "AND julianday(updatedAt) < julianday(:cutoffIso) " +
            "AND NOT EXISTS (" +
            "SELECT 1 FROM board_items bi " +
            "WHERE bi.boardId = board_item_states.boardId " +
            "AND bi.externalId = board_item_states.externalId)"
    )
    suspend fun pruneExpiredBoardItemStates(cutoffIso: String): Int

    @Transaction
    suspend fun executeInTransaction(block: suspend () -> Unit) {
        block()
    }
}

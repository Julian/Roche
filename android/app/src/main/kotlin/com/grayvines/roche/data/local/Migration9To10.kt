@file:Suppress("StringLiteralDuplication", "TooManyFunctions")

package com.grayvines.roche.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import com.grayvines.roche.core.DEFAULT_LOCAL_HLC_NODE
import com.grayvines.roche.core.ENTRY_KIND_ARTICLE
import com.grayvines.roche.core.ENTRY_KIND_CHANNEL
import com.grayvines.roche.core.ENTRY_KIND_COLLECTION
import com.grayvines.roche.core.ENTRY_KIND_PAPER
import com.grayvines.roche.core.ENTRY_KIND_PODCAST
import com.grayvines.roche.core.ENTRY_KIND_PODCAST_EPISODE
import com.grayvines.roche.core.ENTRY_KIND_PUBLICATION
import com.grayvines.roche.core.ENTRY_KIND_VIDEO
import com.grayvines.roche.core.HlcTimestamp
import com.grayvines.roche.core.SEED_EPOCH_ISO
import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.UP_NEXT_COLLECTION_ID
import com.grayvines.roche.core.canonicalizeSyncUrl
import com.grayvines.roche.core.collectionEntryNaturalKey
import com.grayvines.roche.core.deterministicTagId
import com.grayvines.roche.core.encodeCreatorsJson
import com.grayvines.roche.core.extractArxivIdentifier
import com.grayvines.roche.core.extractYearPrefix
import com.grayvines.roche.core.isLocalTagId
import com.grayvines.roche.core.itemNaturalKey
import com.grayvines.roche.core.normalizeTagName
import com.grayvines.roche.core.parseTagCsv

private const val MIGRATED_DELETED_STATUS = "__deleted__"
private const val MIGRATED_UNREAD_READ_AT = "__unread__"
private const val MIGRATED_FEED_ACTIVE = "__feed_active__"
private const val MIGRATED_FEED_ARCHIVED = "__feed_archived__"
private const val MIGRATED_FEED_RENAME = "rename_feed"

private const val COL_0 = 0
private const val COL_1 = 1
private const val COL_2 = 2
private const val COL_3 = 3
private const val COL_4 = 4
private const val COL_5 = 5
private const val COL_6 = 6
private const val COL_7 = 7
private const val COL_8 = 8
private const val COL_9 = 9
private const val COL_10 = 10
private const val COL_11 = 11
private const val COL_12 = 12
private const val COL_13 = 13
private const val COL_14 = 14
private const val COL_15 = 15
private const val COL_16 = 16
private const val COL_17 = 17
private const val COL_18 = 18
private const val COL_19 = 19
private const val COL_20 = 20
private const val COL_21 = 21
private const val COL_22 = 22

private const val BIND_1 = 1
private const val BIND_2 = 2
private const val BIND_3 = 3
private const val BIND_4 = 4
private const val BIND_5 = 5
private const val BIND_6 = 6
private const val BIND_7 = 7
private const val BIND_8 = 8
private const val BIND_9 = 9
private const val BIND_10 = 10
private const val BIND_11 = 11
private const val BIND_12 = 12
private const val BIND_13 = 13
private const val BIND_14 = 14
private const val BIND_15 = 15
private const val BIND_16 = 16

internal fun migrateUnifiedEntriesSchemaV10(connection: SQLiteConnection) {
    dropLegacyV9FtsObjects(connection)
    createUnifiedV10Tables(connection)

    val mutationsById = loadV9PendingMutations(connection)
    migrateV9CatalogAndSpecs(connection)
    seedV10SystemManualSpecs(connection)
    val feeds = migrateV9Feeds(connection, mutationsById)
    val tagIdsByNormName = migrateV9Tags(connection, mutationsById)
    val itemTagNamesByItem = loadV9ItemTagNames(connection)
    migrateV9ItemsAndMemberships(
        connection = connection,
        mutationsById = mutationsById,
        feeds = feeds,
        tagIdsByNormName = tagIdsByNormName,
        itemTagNamesByItem = itemTagNamesByItem,
    )
    migrateV9ActivityAndTombs(connection, mutationsById)
    swapAndIndexUnifiedV10Tables(connection)
    createUnifiedV10FtsObjects(connection)
}

private data class V9Mutation(
    val entityId: String,
    val isLocalFeed: Boolean,
    val isLocalItem: Boolean,
    val titleOverride: String?,
    val dirtyStatus: String?,
    val dirtyFavorite: Boolean?,
    val dirtyReadProgress: Double?,
    val dirtyReadAt: String?,
    val addedTagsCsv: String,
    val removedTagsCsv: String,
) {
    val isDeletedItem: Boolean
        get() = dirtyStatus == MIGRATED_DELETED_STATUS

    val dirtyFeedIsActive: Boolean?
        get() =
            when (dirtyStatus) {
                MIGRATED_FEED_ACTIVE -> true
                MIGRATED_FEED_ARCHIVED -> false
                else -> null
            }

    val isRemoteFeedMutation: Boolean
        get() =
            !isLocalFeed &&
                !isLocalItem &&
                (dirtyFeedIsActive != null || dirtyStatus == MIGRATED_FEED_RENAME)
}

private data class V9FeedSnapshot(
    val id: String,
    val url: String,
)

private fun dropLegacyV9FtsObjects(connection: SQLiteConnection) {
    val legacyTriggers =
        listOf(
            "room_fts_content_sync_items_fts_BEFORE_UPDATE",
            "room_fts_content_sync_items_fts_BEFORE_DELETE",
            "room_fts_content_sync_items_fts_AFTER_UPDATE",
            "room_fts_content_sync_items_fts_AFTER_INSERT",
            "room_fts_content_sync_item_contents_fts_BEFORE_UPDATE",
            "room_fts_content_sync_item_contents_fts_BEFORE_DELETE",
            "room_fts_content_sync_item_contents_fts_AFTER_UPDATE",
            "room_fts_content_sync_item_contents_fts_AFTER_INSERT",
        )
    for (trig in legacyTriggers) {
        connection.execSQL("DROP TRIGGER IF EXISTS `$trig`")
    }
    connection.execSQL("DROP TABLE IF EXISTS `items_fts`")
    connection.execSQL("DROP TABLE IF EXISTS `item_contents_fts`")
}

private fun createUnifiedV10Tables(connection: SQLiteConnection) {
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `entries` (" +
            "`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `title` TEXT NOT NULL, " +
            "`creatorsJson` TEXT NOT NULL, `url` TEXT, `publishedAt` TEXT, " +
            "`releaseYear` INTEGER, `summary` TEXT, `thumbnailUrl` TEXT, " +
            "`metadataJson` TEXT NOT NULL, `mergedIntoId` TEXT, " +
            "`createdAt` TEXT NOT NULL, `updatedAt` TEXT NOT NULL, " +
            "`hlc` TEXT NOT NULL, `isLocalDirty` INTEGER NOT NULL, PRIMARY KEY(`id`))"
    )
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `entry_content` (" +
            "`entryId` TEXT NOT NULL, `contentType` TEXT NOT NULL, " +
            "`contentText` TEXT, `contentHtml` TEXT, `audioUrl` TEXT, " +
            "`duration` TEXT, `thumbnailMediaId` TEXT, `fallbackFeedTitle` TEXT, " +
            "PRIMARY KEY(`entryId`), " +
            "FOREIGN KEY(`entryId`) REFERENCES `entries`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE)"
    )
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `entry_state` (" +
            "`entryId` TEXT NOT NULL, `inboxState` TEXT NOT NULL, `readAt` TEXT, " +
            "`progress` REAL NOT NULL, `interactionUpdatedAt` TEXT, " +
            "`updatedAt` TEXT NOT NULL, `isCachedOffline` INTEGER NOT NULL, " +
            "`hlc` TEXT NOT NULL, `isLocalDirty` INTEGER NOT NULL, " +
            "PRIMARY KEY(`entryId`), " +
            "FOREIGN KEY(`entryId`) REFERENCES `entries`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE)"
    )
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `feed_sources` (" +
            "`collectionId` TEXT NOT NULL, `feedUrl` TEXT NOT NULL, `siteUrl` TEXT, " +
            "`sourceType` TEXT NOT NULL, `feedTitle` TEXT, `language` TEXT, " +
            "`pollInterval` INTEGER NOT NULL, `isActive` INTEGER NOT NULL, " +
            "`lastPolledAt` TEXT, `lastPolledBy` TEXT, `lastError` TEXT, `lastErrorBy` TEXT, " +
            "`autoDownloadEpisodes` INTEGER NOT NULL, `customPlaybackSpeed` REAL, " +
            "`hlc` TEXT NOT NULL, `isLocalDirty` INTEGER NOT NULL, PRIMARY KEY(`collectionId`))"
    )
    createUnifiedV10StagingTables(connection)
}

private fun createUnifiedV10StagingTables(connection: SQLiteConnection) {
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `collection_specs_new` (" +
            "`collectionId` TEXT NOT NULL, `slug` TEXT, `mode` TEXT NOT NULL, " +
            "`role` TEXT NOT NULL, `ruleJson` TEXT, `showInLists` INTEGER NOT NULL, " +
            "`color` TEXT, PRIMARY KEY(`collectionId`))"
    )
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `collection_entries_new` (" +
            "`collectionId` TEXT NOT NULL, `entryId` TEXT NOT NULL, `position` REAL, " +
            "`addedAt` TEXT NOT NULL, `hlc` TEXT NOT NULL, `isLocalDirty` INTEGER NOT NULL, " +
            "PRIMARY KEY(`collectionId`, `entryId`))"
    )
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `collection_generators_new` (" +
            "`collectionId` TEXT NOT NULL, `enabled` INTEGER NOT NULL, " +
            "`currentEntryId` TEXT, `drawnAt` TEXT, `updatedAt` TEXT NOT NULL, " +
            "`hlc` TEXT NOT NULL, `isLocalDirty` INTEGER NOT NULL, PRIMARY KEY(`collectionId`))"
    )
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `consumption_log_new` (" +
            "`id` TEXT NOT NULL, `entryId` TEXT NOT NULL, `ts` TEXT NOT NULL, " +
            "`status` TEXT NOT NULL, `notes` TEXT, `createdAt` TEXT NOT NULL, " +
            "`hlc` TEXT NOT NULL, `isLocalDirty` INTEGER NOT NULL, PRIMARY KEY(`id`))"
    )
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `comparisons_new` (" +
            "`id` TEXT NOT NULL, `ts` TEXT NOT NULL, `scopeCollectionId` TEXT, " +
            "`orderingJson` TEXT NOT NULL, `createdAt` TEXT NOT NULL, " +
            "`hlc` TEXT NOT NULL, `isLocalDirty` INTEGER NOT NULL, PRIMARY KEY(`id`))"
    )
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `sync_tombstones_new` (" +
            "`entityType` TEXT NOT NULL, `naturalKey` TEXT NOT NULL, " +
            "`deletedAt` TEXT NOT NULL, `hlc` TEXT NOT NULL, " +
            "`isLocalDirty` INTEGER NOT NULL, PRIMARY KEY(`entityType`, `naturalKey`))"
    )
}

private fun loadV9PendingMutations(connection: SQLiteConnection): Map<String, V9Mutation> {
    val result = LinkedHashMap<String, V9Mutation>()
    connection
        .prepare(
            "SELECT `entityId`, `isLocalFeed`, `isLocalItem`, `titleOverride`, `dirtyStatus`, " +
                "`dirtyFavorite`, `dirtyReadProgress`, `dirtyReadAt`, " +
                "`addedTagsCsv`, `removedTagsCsv` FROM `pending_mutations`"
        )
        .use { stmt ->
            while (stmt.step()) {
                val id = stmt.getText(COL_0)
                result[id] =
                    V9Mutation(
                        entityId = id,
                        isLocalFeed = stmt.getLong(COL_1) != 0L,
                        isLocalItem = stmt.getLong(COL_2) != 0L,
                        titleOverride = stmt.textOrNull(COL_3),
                        dirtyStatus = stmt.textOrNull(COL_4),
                        dirtyFavorite = stmt.longOrNull(COL_5)?.let { it != 0L },
                        dirtyReadProgress = stmt.doubleOrNull(COL_6),
                        dirtyReadAt = stmt.textOrNull(COL_7),
                        addedTagsCsv = stmt.getText(COL_8),
                        removedTagsCsv = stmt.getText(COL_9),
                    )
            }
        }
    return result
}

private fun migrateV9CatalogAndSpecs(connection: SQLiteConnection) {
    connection
        .prepare(
            "SELECT `id`, `kind`, `title`, `creatorsJson`, `url`, `publishedAt`, `releaseYear`, " +
                "`summary`, `thumbnailUrl`, `metadataJson`, `mergedIntoId`, `createdAt`, " +
                "`updatedAt`, `isLocalDirty` FROM `catalog_entries`"
        )
        .use { stmt ->
            while (stmt.step()) {
                val updatedAt = stmt.getText(COL_12)
                upsertMigratedEntryRow(
                    connection = connection,
                    row =
                        EntryEntity(
                            id = stmt.getText(COL_0),
                            kind = stmt.getText(COL_1),
                            title = stmt.getText(COL_2),
                            creatorsJson = stmt.getText(COL_3),
                            url = stmt.textOrNull(COL_4),
                            publishedAt = stmt.textOrNull(COL_5),
                            releaseYear = stmt.longOrNull(COL_6)?.toInt(),
                            summary = stmt.textOrNull(COL_7),
                            thumbnailUrl = stmt.textOrNull(COL_8),
                            metadataJson = stmt.getText(COL_9),
                            mergedIntoId = stmt.textOrNull(COL_10),
                            createdAt = stmt.getText(COL_11),
                            updatedAt = updatedAt,
                            hlc = migratedHlc(updatedAt),
                            isLocalDirty = stmt.getLong(COL_13) != 0L,
                        ),
                )
            }
        }
    migrateV9CollectionSpecRows(connection)
}

private fun migrateV9CollectionSpecRows(connection: SQLiteConnection) {
    connection
        .prepare(
            "SELECT `collectionId`, `title`, `summary`, `slug`, `mode`, `role`, `ruleJson`, " +
                "`showInLists`, `color`, `createdAt`, `updatedAt`, `isLocalDirty` " +
                "FROM `collection_specs`"
        )
        .use { stmt ->
            while (stmt.step()) {
                val colId = stmt.getText(COL_0)
                val title = stmt.getText(COL_1)
                val summary = stmt.textOrNull(COL_2)
                val createdAt = stmt.getText(COL_9)
                val updatedAt = stmt.getText(COL_10)
                val specDirty = stmt.getLong(COL_11) != 0L
                insertCollectionSpecNewRow(
                    connection = connection,
                    spec =
                        CollectionSpecEntity(
                            collectionId = colId,
                            slug = stmt.textOrNull(COL_3),
                            mode = stmt.getText(COL_4),
                            role = stmt.getText(COL_5),
                            ruleJson = stmt.textOrNull(COL_6),
                            showInLists = stmt.getLong(COL_7) != 0L,
                            color = stmt.textOrNull(COL_8),
                        ),
                )
                ensureCollectionParentEntry(
                    connection,
                    colId,
                    title,
                    summary,
                    createdAt,
                    updatedAt,
                    specDirty,
                )
            }
        }
}

@Suppress("LongParameterList")
private fun ensureCollectionParentEntry(
    connection: SQLiteConnection,
    colId: String,
    title: String,
    summary: String?,
    createdAt: String,
    updatedAt: String,
    specDirty: Boolean,
) {
    val existingDirty = readExistingEntryDirty(connection, colId)
    if (existingDirty == null) {
        upsertMigratedEntryRow(
            connection = connection,
            row =
                EntryEntity(
                    id = colId,
                    kind = ENTRY_KIND_COLLECTION,
                    title = title,
                    summary = summary,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    hlc = migratedHlc(updatedAt),
                    isLocalDirty = specDirty,
                ),
        )
    } else if (specDirty && !existingDirty) {
        connection.prepare("UPDATE `entries` SET `isLocalDirty` = 1 WHERE `id` = ?").use { upd ->
            upd.bindText(BIND_1, colId)
            upd.step()
        }
    }
}

private fun seedV10SystemManualSpecs(connection: SQLiteConnection) {
    val systems =
        listOf(
            Triple(STARRED_COLLECTION_ID, "starred", "Starred"),
            Triple(UP_NEXT_COLLECTION_ID, "up_next", "Up Next"),
        )
    for ((id, slug, title) in systems) {
        if (readExistingEntryDirty(connection, id) == null) {
            upsertMigratedEntryRow(
                connection = connection,
                row =
                    EntryEntity(
                        id = id,
                        kind = ENTRY_KIND_COLLECTION,
                        title = title,
                        createdAt = SEED_EPOCH_ISO,
                        updatedAt = SEED_EPOCH_ISO,
                        hlc = migratedHlc(SEED_EPOCH_ISO),
                        isLocalDirty = false,
                    ),
            )
        }
        insertCollectionSpecIgnoreRow(
            connection = connection,
            spec =
                CollectionSpecEntity(
                    collectionId = id,
                    slug = slug,
                    mode = "manual",
                    role = "system",
                    ruleJson = null,
                    showInLists = false,
                    color = null,
                ),
        )
        insertEntryIdentifierIgnoreRow(connection, "slug", slug, id)
    }
}

private fun migrateV9Feeds(
    connection: SQLiteConnection,
    mutationsById: Map<String, V9Mutation>,
): List<V9FeedSnapshot> {
    val migratedFeeds = ArrayList<V9FeedSnapshot>()
    connection
        .prepare(
            "SELECT `id`, `title`, `url`, `siteUrl`, `feedType`, `description`, `language`, " +
                "`pollInterval`, `lastPolled`, `lastPolledBy`, `lastError`, `lastErrorBy`, " +
                "`isActive`, `autoDownloadEpisodes`, `customPlaybackSpeed` FROM `feeds`"
        )
        .use { stmt ->
            while (stmt.step()) {
                val id = stmt.getText(COL_0)
                val url = stmt.getText(COL_2)
                migratedFeeds.add(V9FeedSnapshot(id = id, url = url))
                migrateSingleV9Feed(connection, stmt, id, url, mutationsById[id])
            }
        }
    return migratedFeeds
}

private fun migrateSingleV9Feed(
    connection: SQLiteConnection,
    stmt: SQLiteStatement,
    id: String,
    url: String,
    mut: V9Mutation?,
) {
    val cleanOverride = mut?.titleOverride?.trim()?.takeIf { it.isNotEmpty() }
    val resolvedTitle = cleanOverride ?: stmt.getText(COL_1).ifBlank { url }
    val feedType = stmt.getText(COL_4)
    val siteUrl = stmt.textOrNull(COL_3)
    val lastPolled = stmt.textOrNull(COL_8)
    val isActive = mut?.dirtyFeedIsActive ?: (stmt.getLong(COL_12) != 0L)
    val isDirty = mut?.isLocalFeed == true || mut?.isRemoteFeedMutation == true
    val ts = lastPolled ?: SEED_EPOCH_ISO
    val hlc = migratedHlc(ts)

    val existingEntry = readExistingMigratedEntry(connection, id)
    upsertMigratedEntryRow(
        connection = connection,
        row =
            buildMigratedFeedEntry(
                id = id,
                url = url,
                siteUrl = siteUrl,
                feedType = feedType,
                resolvedTitle = resolvedTitle,
                description = stmt.textOrNull(COL_5),
                ts = ts,
                hlc = hlc,
                isDirty = isDirty,
                existingEntry = existingEntry,
            ),
    )
    insertCollectionSpecNewRow(
        connection = connection,
        spec = CollectionSpecEntity(collectionId = id, slug = null, mode = "feed", role = "feed"),
    )
    insertFeedSourceRow(
        connection = connection,
        source =
            buildMigratedFeedSource(
                stmt = stmt,
                id = id,
                url = url,
                siteUrl = siteUrl,
                feedType = feedType,
                resolvedTitle = resolvedTitle,
                isActive = isActive,
                lastPolled = lastPolled,
                hlc = hlc,
                isDirty = isDirty,
            ),
    )
    val canonFeedUrl = canonicalizeSyncUrl(url)
    if (canonFeedUrl.isNotEmpty()) {
        insertEntryIdentifierIgnoreRow(connection, "feed_url", canonFeedUrl, id)
    }
}

@Suppress("LongParameterList")
private fun buildMigratedFeedSource(
    stmt: SQLiteStatement,
    id: String,
    url: String,
    siteUrl: String?,
    feedType: String,
    resolvedTitle: String,
    isActive: Boolean,
    lastPolled: String?,
    hlc: String,
    isDirty: Boolean,
): FeedSourceEntity =
    FeedSourceEntity(
        collectionId = id,
        feedUrl = url,
        siteUrl = siteUrl,
        sourceType = feedType,
        feedTitle = resolvedTitle,
        language = stmt.textOrNull(COL_6),
        pollInterval = stmt.getLong(COL_7).toInt(),
        isActive = isActive,
        lastPolledAt = lastPolled,
        lastPolledBy = stmt.textOrNull(COL_9),
        lastError = stmt.textOrNull(COL_10),
        lastErrorBy = stmt.textOrNull(COL_11),
        autoDownloadEpisodes = stmt.getLong(COL_13) != 0L,
        customPlaybackSpeed = stmt.doubleOrNull(COL_14)?.toFloat(),
        hlc = hlc,
        isLocalDirty = isDirty,
    )

@Suppress("LongParameterList")
private fun buildMigratedFeedEntry(
    id: String,
    url: String,
    siteUrl: String?,
    feedType: String,
    resolvedTitle: String,
    description: String?,
    ts: String,
    hlc: String,
    isDirty: Boolean,
    existingEntry: EntryEntity?,
): EntryEntity =
    EntryEntity(
        id = id,
        kind = existingEntry?.kind ?: entryKindForFeedTypeWire(feedType),
        title = resolvedTitle,
        creatorsJson = existingEntry?.creatorsJson ?: "[]",
        url = siteUrl ?: existingEntry?.url ?: url,
        publishedAt = existingEntry?.publishedAt,
        releaseYear = existingEntry?.releaseYear,
        summary = description ?: existingEntry?.summary,
        thumbnailUrl = existingEntry?.thumbnailUrl,
        metadataJson = existingEntry?.metadataJson ?: "{}",
        mergedIntoId = existingEntry?.mergedIntoId,
        createdAt = existingEntry?.createdAt ?: ts,
        updatedAt = existingEntry?.updatedAt ?: ts,
        hlc = existingEntry?.hlc?.takeIf { it.isNotEmpty() } ?: hlc,
        isLocalDirty = isDirty || existingEntry?.isLocalDirty == true,
    )

private fun migrateV9Tags(
    connection: SQLiteConnection,
    mutationsById: Map<String, V9Mutation>,
): Map<String, String> {
    val tagIdsByNormName = LinkedHashMap<String, String>()
    connection.prepare("SELECT `id`, `name`, `color` FROM `tags`").use { stmt ->
        while (stmt.step()) {
            val id = stmt.getText(COL_0)
            val normName = normalizeTagName(stmt.getText(COL_1))
            val color = stmt.textOrNull(COL_2)
            if (normName.isNotEmpty() && normName !in tagIdsByNormName) {
                tagIdsByNormName[normName] = id
                upsertMigratedTagGraph(connection, id, normName, color, isLocalTagId(id))
            }
        }
    }
    val extraNames =
        mutationsById.values
            .flatMap { parseTagCsv(it.addedTagsCsv) + parseTagCsv(it.removedTagsCsv) }
            .distinct()
    for (normName in extraNames) {
        if (normName.isNotEmpty() && normName !in tagIdsByNormName) {
            val id = deterministicTagId(normName)
            tagIdsByNormName[normName] = id
            upsertMigratedTagGraph(connection, id, normName, color = null, isDirty = true)
        }
    }
    return tagIdsByNormName
}

private fun upsertMigratedTagGraph(
    connection: SQLiteConnection,
    id: String,
    normName: String,
    color: String?,
    isDirty: Boolean,
) {
    val slug = "tag:$normName"
    upsertMigratedEntryRow(
        connection = connection,
        row =
            EntryEntity(
                id = id,
                kind = ENTRY_KIND_COLLECTION,
                title = normName,
                createdAt = SEED_EPOCH_ISO,
                updatedAt = SEED_EPOCH_ISO,
                hlc = migratedHlc(SEED_EPOCH_ISO),
                isLocalDirty = isDirty,
            ),
    )
    insertCollectionSpecNewRow(
        connection = connection,
        spec =
            CollectionSpecEntity(
                collectionId = id,
                slug = slug,
                mode = "manual",
                role = "tag",
                ruleJson = null,
                showInLists = false,
                color = color,
            ),
    )
    insertEntryIdentifierIgnoreRow(connection, "tag", normName, id)
}

private fun loadV9ItemTagNames(connection: SQLiteConnection): Map<String, List<String>> {
    val rawTagsById = HashMap<String, String>()
    connection.prepare("SELECT `id`, `name` FROM `tags`").use { stmt ->
        while (stmt.step()) {
            rawTagsById[stmt.getText(COL_0)] = normalizeTagName(stmt.getText(COL_1))
        }
    }
    val byItem = HashMap<String, MutableList<String>>()
    connection.prepare("SELECT `itemId`, `tagId` FROM `item_tags`").use { stmt ->
        while (stmt.step()) {
            val itemId = stmt.getText(COL_0)
            val normName = rawTagsById[stmt.getText(COL_1)].orEmpty()
            if (normName.isNotEmpty()) {
                byItem.getOrPut(itemId) { ArrayList() }.add(normName)
            }
        }
    }
    return byItem
}

private fun migrateV9ItemsAndMemberships(
    connection: SQLiteConnection,
    mutationsById: Map<String, V9Mutation>,
    feeds: List<V9FeedSnapshot>,
    tagIdsByNormName: Map<String, String>,
    itemTagNamesByItem: Map<String, List<String>>,
) {
    val feedsById = feeds.associateBy { it.id }
    val feedsByCanonUrl = feeds.associateBy { canonicalizeSyncUrl(it.url) }
    val ctx =
        V9ItemMigrationContext(
            mutationsById = mutationsById,
            feedsById = feedsById,
            feedsByCanonUrl = feedsByCanonUrl,
            tagIdsByNormName = tagIdsByNormName,
            itemTagNamesByItem = itemTagNamesByItem,
        )
    connection
        .prepare(
            "SELECT i.`id`, i.`feedId`, i.`feedTitle`, i.`title`, i.`url`, i.`author`, " +
                "COALESCE(ic.`summary`, i.`summary`), " +
                "COALESCE(ic.`contentText`, i.`contentText`), " +
                "COALESCE(ic.`contentHtml`, i.`contentHtml`), " +
                "i.`contentType`, i.`status`, i.`isFavorite`, i.`isCachedOffline`, " +
                "i.`publishedAt`, i.`createdAt`, i.`readProgress`, i.`thumbnailMediaId`, " +
                "i.`duration`, i.`readAt`, i.`thumbnailUrl`, i.`feedUrl`, " +
                "i.`interactionUpdatedAt`, i.`audioUrl` " +
                "FROM `items` i LEFT JOIN `item_contents` ic ON i.`id` = ic.`itemId`"
        )
        .use { stmt ->
            while (stmt.step()) {
                migrateSingleV9Item(connection, stmt, ctx)
            }
        }
}

private data class V9ItemMigrationContext(
    val mutationsById: Map<String, V9Mutation>,
    val feedsById: Map<String, V9FeedSnapshot>,
    val feedsByCanonUrl: Map<String, V9FeedSnapshot>,
    val tagIdsByNormName: Map<String, String>,
    val itemTagNamesByItem: Map<String, List<String>>,
)

private fun migrateSingleV9Item(
    connection: SQLiteConnection,
    stmt: SQLiteStatement,
    ctx: V9ItemMigrationContext,
) {
    val id = stmt.getText(COL_0)
    val mut = ctx.mutationsById[id]
    val url = stmt.getText(COL_4)
    val rawFeedId = stmt.textOrNull(COL_1)
    val rawFeedUrl = stmt.textOrNull(COL_20)
    val resolvedFeed =
        rawFeedId?.let { ctx.feedsById[it] }
            ?: rawFeedUrl?.let { ctx.feedsByCanonUrl[canonicalizeSyncUrl(it)] }
    if (mut?.isDeletedItem == true) {
        val createdAt = stmt.getText(COL_14)
        upsertTombstoneNewRow(
            connection = connection,
            tomb =
                TombstoneEntity(
                    entityType = "item",
                    naturalKey = itemNaturalKey(resolvedFeed?.url ?: rawFeedUrl, url),
                    deletedAt = createdAt,
                    hlc = migratedHlc(createdAt),
                    isLocalDirty = true,
                ),
        )
        return
    }
    writeMigratedV9ItemGraph(connection, stmt, id, url, resolvedFeed, rawFeedId, mut, ctx)
}

@Suppress("LongParameterList")
private fun writeMigratedV9ItemGraph(
    connection: SQLiteConnection,
    stmt: SQLiteStatement,
    id: String,
    url: String,
    resolvedFeed: V9FeedSnapshot?,
    rawFeedId: String?,
    mut: V9Mutation?,
    ctx: V9ItemMigrationContext,
) {
    val contentType = stmt.getText(COL_9)
    val audioUrl = stmt.textOrNull(COL_22)
    val createdAt = stmt.getText(COL_14)
    val interAt = stmt.textOrNull(COL_21)
    val effectiveTs = interAt ?: createdAt
    val hlc = migratedHlc(effectiveTs)
    val hasMut = mut != null

    val existing = readExistingMigratedEntry(connection, id)
    upsertMigratedEntryRow(
        connection = connection,
        row =
            buildMigratedItemEntry(
                stmt,
                id,
                url,
                contentType,
                audioUrl,
                effectiveTs,
                hlc,
                mut,
                existing,
            ),
    )
    insertEntryContentRow(
        connection = connection,
        content =
            EntryContentEntity(
                entryId = id,
                contentType = contentType,
                contentText = stmt.textOrNull(COL_7),
                contentHtml = stmt.textOrNull(COL_8),
                audioUrl = audioUrl,
                duration = stmt.textOrNull(COL_17),
                thumbnailMediaId = stmt.textOrNull(COL_16),
                fallbackFeedTitle = if (resolvedFeed == null) stmt.getText(COL_2) else null,
            ),
    )
    insertEntryStateRow(
        connection = connection,
        state = buildMigratedItemState(stmt, id, createdAt, interAt, effectiveTs, hlc, mut),
    )
    writeMigratedItemLinks(
        connection = connection,
        id = id,
        url = url,
        feedCollectionId = resolvedFeed?.id ?: rawFeedId,
        isFavorite = mut?.dirtyFavorite ?: (stmt.getLong(COL_11) != 0L),
        createdAt = createdAt,
        effectiveTs = effectiveTs,
        hlc = hlc,
        mut = mut,
        ctx = ctx,
    )
}

@Suppress("LongParameterList")
private fun buildMigratedItemState(
    stmt: SQLiteStatement,
    id: String,
    createdAt: String,
    interAt: String?,
    effectiveTs: String,
    hlc: String,
    mut: V9Mutation?,
): EntryStateEntity {
    val hasMut = mut != null
    val status = mut?.dirtyStatus ?: stmt.getText(COL_10)
    val inboxState = if (status.equals("archived", ignoreCase = true)) "dismissed" else "unread"
    return EntryStateEntity(
        entryId = id,
        inboxState = inboxState,
        readAt = resolveMigratedReadAt(mut, status, stmt.textOrNull(COL_18)),
        progress = mut?.dirtyReadProgress ?: stmt.getDouble(COL_15),
        interactionUpdatedAt = interAt ?: createdAt.takeIf { hasMut },
        updatedAt = effectiveTs,
        isCachedOffline = stmt.getLong(COL_12) != 0L,
        hlc = hlc,
        isLocalDirty = hasMut,
    )
}

@Suppress("LongParameterList")
private fun buildMigratedItemEntry(
    stmt: SQLiteStatement,
    id: String,
    url: String,
    contentType: String,
    audioUrl: String?,
    effectiveTs: String,
    hlc: String,
    mut: V9Mutation?,
    existing: EntryEntity?,
): EntryEntity {
    val title = mut?.titleOverride?.trim()?.takeIf { it.isNotEmpty() } ?: stmt.getText(COL_3)
    val author = stmt.textOrNull(COL_5)
    val publishedAt = stmt.textOrNull(COL_13)
    val createdAt = stmt.getText(COL_14)
    val creatorsJson =
        if (existing != null && existing.creatorsJson != "[]") {
            existing.creatorsJson
        } else {
            encodeCreatorsJson(listOfNotNull(author?.trim()?.takeIf { it.isNotEmpty() }))
        }
    return EntryEntity(
        id = id,
        kind = existing?.kind ?: entryKindForItemWire(contentType, audioUrl),
        title = title,
        creatorsJson = creatorsJson,
        url = url.ifBlank { existing?.url },
        publishedAt = publishedAt ?: existing?.publishedAt,
        releaseYear = existing?.releaseYear ?: extractYearPrefix(publishedAt),
        summary = stmt.textOrNull(COL_6) ?: existing?.summary,
        thumbnailUrl = stmt.textOrNull(COL_19) ?: existing?.thumbnailUrl,
        metadataJson = existing?.metadataJson ?: "{}",
        mergedIntoId = existing?.mergedIntoId,
        createdAt = existing?.createdAt ?: createdAt,
        updatedAt = effectiveTs,
        hlc = hlc,
        isLocalDirty = mut != null || existing?.isLocalDirty == true,
    )
}

@Suppress("LongParameterList")
private fun writeMigratedItemLinks(
    connection: SQLiteConnection,
    id: String,
    url: String,
    feedCollectionId: String?,
    isFavorite: Boolean,
    createdAt: String,
    effectiveTs: String,
    hlc: String,
    mut: V9Mutation?,
    ctx: V9ItemMigrationContext,
) {
    val canonUrl = canonicalizeSyncUrl(url)
    if (canonUrl.isNotEmpty()) {
        insertEntryIdentifierIgnoreRow(connection, "url", canonUrl, id)
    }
    extractArxivIdentifier(url)?.let { arxiv ->
        insertEntryIdentifierIgnoreRow(connection, "arxiv", arxiv, id)
    }
    if (!feedCollectionId.isNullOrBlank()) {
        insertCollectionEntryNewRow(
            connection = connection,
            row =
                CollectionEntryEntity(
                    collectionId = feedCollectionId,
                    entryId = id,
                    position = null,
                    addedAt = createdAt,
                    hlc = hlc,
                    isLocalDirty = mut?.isLocalItem == true,
                ),
        )
    }
    if (isFavorite) {
        insertCollectionEntryNewRow(
            connection = connection,
            row =
                CollectionEntryEntity(
                    collectionId = STARRED_COLLECTION_ID,
                    entryId = id,
                    position = null,
                    addedAt = effectiveTs,
                    hlc = hlc,
                    isLocalDirty = mut?.dirtyFavorite != null || mut?.isLocalItem == true,
                ),
        )
    } else if (mut?.dirtyFavorite == false) {
        upsertTombstoneNewRow(
            connection = connection,
            tomb =
                TombstoneEntity(
                    entityType = "collection_entry",
                    naturalKey = collectionEntryNaturalKey(STARRED_COLLECTION_ID, id),
                    deletedAt = effectiveTs,
                    hlc = hlc,
                    isLocalDirty = true,
                ),
        )
    }
    migrateItemTagMemberships(connection, id, effectiveTs, hlc, mut, ctx)
}

private fun migrateItemTagMemberships(
    connection: SQLiteConnection,
    itemId: String,
    effectiveTs: String,
    hlc: String,
    mut: V9Mutation?,
    ctx: V9ItemMigrationContext,
) {
    val added = parseTagCsv(mut?.addedTagsCsv.orEmpty()).toSet()
    val removed = parseTagCsv(mut?.removedTagsCsv.orEmpty()).toSet()
    val activeTagNames =
        (ctx.itemTagNamesByItem[itemId].orEmpty() + added).distinct().filter { it !in removed }
    for (normName in activeTagNames) {
        val tagId = ctx.tagIdsByNormName[normName] ?: continue
        insertCollectionEntryNewRow(
            connection = connection,
            row =
                CollectionEntryEntity(
                    collectionId = tagId,
                    entryId = itemId,
                    position = null,
                    addedAt = effectiveTs,
                    hlc = hlc,
                    isLocalDirty = normName in added || mut?.isLocalItem == true,
                ),
        )
    }
    for (removedName in removed) {
        val tagId = ctx.tagIdsByNormName[removedName] ?: continue
        upsertTombstoneNewRow(
            connection = connection,
            tomb =
                TombstoneEntity(
                    entityType = "collection_entry",
                    naturalKey = collectionEntryNaturalKey(tagId, itemId),
                    deletedAt = effectiveTs,
                    hlc = hlc,
                    isLocalDirty = true,
                ),
        )
    }
}

private fun resolveMigratedReadAt(
    mut: V9Mutation?,
    effectiveStatus: String,
    storedReadAt: String?,
): String? =
    when (val dirtyRead = mut?.dirtyReadAt) {
        null ->
            if (
                mut?.dirtyStatus != null && !effectiveStatus.equals("archived", ignoreCase = true)
            ) {
                null
            } else {
                storedReadAt
            }
        MIGRATED_UNREAD_READ_AT -> null
        else -> dirtyRead
    }

private fun migrateV9ActivityAndTombs(
    connection: SQLiteConnection,
    mutationsById: Map<String, V9Mutation>,
) {
    connection
        .prepare(
            "SELECT `collectionId`, `entryId`, `position`, `addedAt`, `isLocalDirty` " +
                "FROM `collection_entries`"
        )
        .use { stmt ->
            while (stmt.step()) {
                val addedAt = stmt.getText(COL_3)
                insertCollectionEntryIgnoreRow(
                    connection = connection,
                    row =
                        CollectionEntryEntity(
                            collectionId = stmt.getText(COL_0),
                            entryId = stmt.getText(COL_1),
                            position = stmt.doubleOrNull(COL_2),
                            addedAt = addedAt,
                            hlc = migratedHlc(addedAt),
                            isLocalDirty = stmt.getLong(COL_4) != 0L,
                        ),
                )
            }
        }
    migrateV9Generators(connection)
    migrateV9LogsAndComparisons(connection)
    migrateV9Tombstones(connection, mutationsById)
}

private fun migrateV9Generators(connection: SQLiteConnection) {
    connection
        .prepare(
            "SELECT `collectionId`, `enabled`, `currentEntryId`, `drawnAt`, `updatedAt`, " +
                "`isLocalDirty` FROM `collection_generators`"
        )
        .use { stmt ->
            while (stmt.step()) {
                val updatedAt = stmt.getText(COL_4)
                insertGeneratorNewRow(
                    connection = connection,
                    gen =
                        CollectionGeneratorEntity(
                            collectionId = stmt.getText(COL_0),
                            enabled = stmt.getLong(COL_1) != 0L,
                            currentEntryId = stmt.textOrNull(COL_2),
                            drawnAt = stmt.textOrNull(COL_3),
                            updatedAt = updatedAt,
                            hlc = migratedHlc(updatedAt),
                            isLocalDirty = stmt.getLong(COL_5) != 0L,
                        ),
                )
            }
        }
}

private fun migrateV9LogsAndComparisons(connection: SQLiteConnection) {
    connection
        .prepare(
            "SELECT `id`, `entryId`, `ts`, `status`, `notes`, `createdAt`, `isLocalDirty` " +
                "FROM `consumption_log`"
        )
        .use { stmt ->
            while (stmt.step()) {
                val ts = stmt.getText(COL_2)
                insertLogNewRow(
                    connection = connection,
                    log =
                        ConsumptionLogEntity(
                            id = stmt.getText(COL_0),
                            entryId = stmt.getText(COL_1),
                            ts = ts,
                            status = stmt.getText(COL_3),
                            notes = stmt.textOrNull(COL_4),
                            createdAt = stmt.getText(COL_5),
                            hlc = migratedHlc(ts),
                            isLocalDirty = stmt.getLong(COL_6) != 0L,
                        ),
                )
            }
        }
    connection
        .prepare(
            "SELECT `id`, `ts`, `scopeCollectionId`, `orderingJson`, `createdAt`, `isLocalDirty` " +
                "FROM `comparisons`"
        )
        .use { stmt ->
            while (stmt.step()) {
                val ts = stmt.getText(COL_1)
                insertComparisonNewRow(
                    connection = connection,
                    comp =
                        ComparisonEntity(
                            id = stmt.getText(COL_0),
                            ts = ts,
                            scopeCollectionId = stmt.textOrNull(COL_2),
                            orderingJson = stmt.getText(COL_3),
                            createdAt = stmt.getText(COL_4),
                            hlc = migratedHlc(ts),
                            isLocalDirty = stmt.getLong(COL_5) != 0L,
                        ),
                )
            }
        }
}

private fun migrateV9Tombstones(
    connection: SQLiteConnection,
    mutationsById: Map<String, V9Mutation>,
) {
    val hasDeletedPending = mutationsById.values.any { it.isDeletedItem }
    connection
        .prepare("SELECT `entityType`, `naturalKey`, `deletedAt` FROM `sync_tombstones`")
        .use { stmt ->
            while (stmt.step()) {
                val deletedAt = stmt.getText(COL_2)
                upsertTombstoneNewRow(
                    connection = connection,
                    tomb =
                        TombstoneEntity(
                            entityType = stmt.getText(COL_0),
                            naturalKey = stmt.getText(COL_1),
                            deletedAt = deletedAt,
                            hlc = migratedHlc(deletedAt),
                            isLocalDirty = true,
                        ),
                )
            }
        }
    if (hasDeletedPending) {
        for (mut in mutationsById.values.filter { it.isDeletedItem }) {
            upsertTombstoneNewIgnoreRow(
                connection = connection,
                tomb =
                    TombstoneEntity(
                        entityType = "entry",
                        naturalKey = mut.entityId,
                        deletedAt = SEED_EPOCH_ISO,
                        hlc = migratedHlc(SEED_EPOCH_ISO),
                        isLocalDirty = true,
                    ),
            )
        }
    }
}

private fun swapAndIndexUnifiedV10Tables(connection: SQLiteConnection) {
    val legacyDrops =
        listOf(
            "item_contents",
            "items",
            "item_tags",
            "feeds",
            "tags",
            "pending_mutations",
            "catalog_entries",
            "collection_specs",
            "collection_entries",
            "collection_generators",
            "consumption_log",
            "comparisons",
            "sync_tombstones",
        )
    for (tbl in legacyDrops) {
        connection.execSQL("DROP TABLE IF EXISTS `$tbl`")
    }
    connection.execSQL("ALTER TABLE `collection_specs_new` RENAME TO `collection_specs`")
    connection.execSQL("ALTER TABLE `collection_entries_new` RENAME TO `collection_entries`")
    connection.execSQL("ALTER TABLE `collection_generators_new` RENAME TO `collection_generators`")
    connection.execSQL("ALTER TABLE `consumption_log_new` RENAME TO `consumption_log`")
    connection.execSQL("ALTER TABLE `comparisons_new` RENAME TO `comparisons`")
    connection.execSQL("ALTER TABLE `sync_tombstones_new` RENAME TO `sync_tombstones`")
    connection.execSQL(
        "DELETE FROM `local_sync_flags` WHERE `flagKey` = '$FLAG_CATALOG_BACKFILL_NEEDED'"
    )
    createUnifiedV10Indices(connection)
}

private fun createUnifiedV10Indices(connection: SQLiteConnection) {
    val ddl =
        listOf(
            "CREATE INDEX IF NOT EXISTS `index_entries_kind` ON `entries` (`kind`)",
            "CREATE INDEX IF NOT EXISTS `index_entries_mergedIntoId` ON `entries` (`mergedIntoId`)",
            "CREATE INDEX IF NOT EXISTS `index_entries_updatedAt` ON `entries` (`updatedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_entries_publishedAt_createdAt` " +
                "ON `entries` (`publishedAt`, `createdAt`)",
            "CREATE INDEX IF NOT EXISTS `index_entries_createdAt` ON `entries` (`createdAt`)",
            "CREATE INDEX IF NOT EXISTS `index_entries_isLocalDirty` ON `entries` (`isLocalDirty`)",
            "CREATE INDEX IF NOT EXISTS `index_entry_content_contentType` " +
                "ON `entry_content` (`contentType`)",
            "CREATE INDEX IF NOT EXISTS `index_entry_state_inboxState` " +
                "ON `entry_state` (`inboxState`)",
            "CREATE INDEX IF NOT EXISTS `index_entry_state_interactionUpdatedAt` " +
                "ON `entry_state` (`interactionUpdatedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_entry_state_isCachedOffline` " +
                "ON `entry_state` (`isCachedOffline`)",
            "CREATE INDEX IF NOT EXISTS `index_entry_state_isLocalDirty` " +
                "ON `entry_state` (`isLocalDirty`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_feed_sources_feedUrl` " +
                "ON `feed_sources` (`feedUrl`)",
            "CREATE INDEX IF NOT EXISTS `index_feed_sources_isLocalDirty` " +
                "ON `feed_sources` (`isLocalDirty`)",
            "CREATE INDEX IF NOT EXISTS `index_collection_specs_slug` " +
                "ON `collection_specs` (`slug`)",
            "CREATE INDEX IF NOT EXISTS `index_collection_specs_role` " +
                "ON `collection_specs` (`role`)",
            "CREATE INDEX IF NOT EXISTS `index_collection_entries_entryId` " +
                "ON `collection_entries` (`entryId`)",
            "CREATE INDEX IF NOT EXISTS `index_collection_entries_addedAt` " +
                "ON `collection_entries` (`addedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_collection_entries_isLocalDirty` " +
                "ON `collection_entries` (`isLocalDirty`)",
            "CREATE INDEX IF NOT EXISTS `index_consumption_log_entryId` " +
                "ON `consumption_log` (`entryId`)",
            "CREATE INDEX IF NOT EXISTS `index_consumption_log_ts` ON `consumption_log` (`ts`)",
            "CREATE INDEX IF NOT EXISTS `index_consumption_log_createdAt` " +
                "ON `consumption_log` (`createdAt`)",
            "CREATE INDEX IF NOT EXISTS `index_consumption_log_isLocalDirty` " +
                "ON `consumption_log` (`isLocalDirty`)",
            "CREATE INDEX IF NOT EXISTS `index_comparisons_scopeCollectionId` " +
                "ON `comparisons` (`scopeCollectionId`)",
            "CREATE INDEX IF NOT EXISTS `index_comparisons_ts` ON `comparisons` (`ts`)",
            "CREATE INDEX IF NOT EXISTS `index_comparisons_createdAt` " +
                "ON `comparisons` (`createdAt`)",
            "CREATE INDEX IF NOT EXISTS `index_comparisons_isLocalDirty` " +
                "ON `comparisons` (`isLocalDirty`)",
        )
    for (sql in ddl) {
        connection.execSQL(sql)
    }
}

internal fun createUnifiedV10FtsObjects(connection: SQLiteConnection) {
    connection.execSQL(
        "CREATE VIRTUAL TABLE IF NOT EXISTS `entries_fts` USING FTS5(" +
            "`title`, `creatorsJson`, `url`, `summary`, " +
            "tokenize=`unicode61`, content=`entries`)"
    )
    connection.execSQL(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_entries_fts_BEFORE_UPDATE " +
            "BEFORE UPDATE ON `entries` BEGIN " +
            "DELETE FROM `entries_fts` WHERE `rowid`=OLD.`rowid`; END"
    )
    connection.execSQL(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_entries_fts_BEFORE_DELETE " +
            "BEFORE DELETE ON `entries` BEGIN " +
            "DELETE FROM `entries_fts` WHERE `rowid`=OLD.`rowid`; END"
    )
    connection.execSQL(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_entries_fts_AFTER_UPDATE " +
            "AFTER UPDATE ON `entries` BEGIN " +
            "INSERT INTO `entries_fts`(`rowid`, `title`, `creatorsJson`, `url`, `summary`) " +
            "VALUES (NEW.`rowid`, NEW.`title`, NEW.`creatorsJson`, NEW.`url`, NEW.`summary`); END"
    )
    connection.execSQL(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_entries_fts_AFTER_INSERT " +
            "AFTER INSERT ON `entries` BEGIN " +
            "INSERT INTO `entries_fts`(`rowid`, `title`, `creatorsJson`, `url`, `summary`) " +
            "VALUES (NEW.`rowid`, NEW.`title`, NEW.`creatorsJson`, NEW.`url`, NEW.`summary`); END"
    )

    connection.execSQL(
        "CREATE VIRTUAL TABLE IF NOT EXISTS `entry_content_fts` USING FTS5(" +
            "`contentText`, `contentHtml`, `fallbackFeedTitle`, " +
            "tokenize=`unicode61`, content=`entry_content`)"
    )
    connection.execSQL(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_entry_content_fts_BEFORE_UPDATE " +
            "BEFORE UPDATE ON `entry_content` BEGIN " +
            "DELETE FROM `entry_content_fts` WHERE `rowid`=OLD.`rowid`; END"
    )
    connection.execSQL(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_entry_content_fts_BEFORE_DELETE " +
            "BEFORE DELETE ON `entry_content` BEGIN " +
            "DELETE FROM `entry_content_fts` WHERE `rowid`=OLD.`rowid`; END"
    )
    connection.execSQL(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_entry_content_fts_AFTER_UPDATE " +
            "AFTER UPDATE ON `entry_content` BEGIN " +
            "INSERT INTO `entry_content_fts`(`rowid`, `contentText`, `contentHtml`, " +
            "`fallbackFeedTitle`) VALUES (NEW.`rowid`, NEW.`contentText`, NEW.`contentHtml`, " +
            "NEW.`fallbackFeedTitle`); END"
    )
    connection.execSQL(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_entry_content_fts_AFTER_INSERT " +
            "AFTER INSERT ON `entry_content` BEGIN " +
            "INSERT INTO `entry_content_fts`(`rowid`, `contentText`, `contentHtml`, " +
            "`fallbackFeedTitle`) VALUES (NEW.`rowid`, NEW.`contentText`, NEW.`contentHtml`, " +
            "NEW.`fallbackFeedTitle`); END"
    )
    connection.execSQL("INSERT INTO `entries_fts`(`entries_fts`) VALUES('rebuild')")
    connection.execSQL("INSERT INTO `entry_content_fts`(`entry_content_fts`) VALUES('rebuild')")
}

internal fun entryKindForFeedTypeWire(feedType: String): String =
    when {
        feedType.equals("arxiv", ignoreCase = true) -> ENTRY_KIND_COLLECTION
        feedType.equals("youtube", ignoreCase = true) -> ENTRY_KIND_CHANNEL
        feedType.equals("podcast", ignoreCase = true) -> ENTRY_KIND_PODCAST
        else -> ENTRY_KIND_PUBLICATION
    }

internal fun entryKindForItemWire(contentType: String, audioUrl: String?): String =
    when {
        !audioUrl.isNullOrBlank() ||
            contentType.equals("audio", ignoreCase = true) ||
            contentType.equals("podcast", ignoreCase = true) -> ENTRY_KIND_PODCAST_EPISODE
        contentType.equals("pdf", ignoreCase = true) ||
            contentType.equals("paper", ignoreCase = true) -> ENTRY_KIND_PAPER
        contentType.equals("video", ignoreCase = true) -> ENTRY_KIND_VIDEO
        else -> ENTRY_KIND_ARTICLE
    }

private fun migratedHlc(rawIso: String?): String =
    HlcTimestamp.fromIsoOrEpoch(rawIso, DEFAULT_LOCAL_HLC_NODE).encode()

private fun readExistingEntryDirty(connection: SQLiteConnection, id: String): Boolean? =
    connection.prepare("SELECT `isLocalDirty` FROM `entries` WHERE `id` = ? LIMIT 1").use { stmt ->
        stmt.bindText(BIND_1, id)
        if (stmt.step()) stmt.getLong(COL_0) != 0L else null
    }

private fun readExistingMigratedEntry(connection: SQLiteConnection, id: String): EntryEntity? =
    connection
        .prepare(
            "SELECT `id`, `kind`, `title`, `creatorsJson`, `url`, `publishedAt`, `releaseYear`, " +
                "`summary`, `thumbnailUrl`, `metadataJson`, `mergedIntoId`, `createdAt`, " +
                "`updatedAt`, `hlc`, `isLocalDirty` FROM `entries` WHERE `id` = ? LIMIT 1"
        )
        .use { stmt ->
            stmt.bindText(BIND_1, id)
            if (!stmt.step()) {
                null
            } else {
                EntryEntity(
                    id = stmt.getText(COL_0),
                    kind = stmt.getText(COL_1),
                    title = stmt.getText(COL_2),
                    creatorsJson = stmt.getText(COL_3),
                    url = stmt.textOrNull(COL_4),
                    publishedAt = stmt.textOrNull(COL_5),
                    releaseYear = stmt.longOrNull(COL_6)?.toInt(),
                    summary = stmt.textOrNull(COL_7),
                    thumbnailUrl = stmt.textOrNull(COL_8),
                    metadataJson = stmt.getText(COL_9),
                    mergedIntoId = stmt.textOrNull(COL_10),
                    createdAt = stmt.getText(COL_11),
                    updatedAt = stmt.getText(COL_12),
                    hlc = stmt.getText(COL_13),
                    isLocalDirty = stmt.getLong(COL_14) != 0L,
                )
            }
        }

private fun upsertMigratedEntryRow(connection: SQLiteConnection, row: EntryEntity) {
    connection
        .prepare(
            "INSERT OR REPLACE INTO `entries` (`id`, `kind`, `title`, `creatorsJson`, `url`, " +
                "`publishedAt`, `releaseYear`, `summary`, `thumbnailUrl`, `metadataJson`, " +
                "`mergedIntoId`, `createdAt`, `updatedAt`, `hlc`, `isLocalDirty`) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )
        .use { stmt ->
            stmt.bindText(BIND_1, row.id)
            stmt.bindText(BIND_2, row.kind)
            stmt.bindText(BIND_3, row.title)
            stmt.bindText(BIND_4, row.creatorsJson)
            stmt.bindNullableText(BIND_5, row.url)
            stmt.bindNullableText(BIND_6, row.publishedAt)
            stmt.bindNullableLong(BIND_7, row.releaseYear?.toLong())
            stmt.bindNullableText(BIND_8, row.summary)
            stmt.bindNullableText(BIND_9, row.thumbnailUrl)
            stmt.bindText(BIND_10, row.metadataJson)
            stmt.bindNullableText(BIND_11, row.mergedIntoId)
            stmt.bindText(BIND_12, row.createdAt)
            stmt.bindText(BIND_13, row.updatedAt)
            stmt.bindText(BIND_14, row.hlc)
            stmt.bindLong(BIND_15, if (row.isLocalDirty) 1L else 0L)
            stmt.step()
        }
}

private fun insertEntryContentRow(connection: SQLiteConnection, content: EntryContentEntity) {
    connection
        .prepare(
            "INSERT OR REPLACE INTO `entry_content` (`entryId`, `contentType`, `contentText`, " +
                "`contentHtml`, `audioUrl`, `duration`, `thumbnailMediaId`, `fallbackFeedTitle`) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        )
        .use { stmt ->
            stmt.bindText(BIND_1, content.entryId)
            stmt.bindText(BIND_2, content.contentType)
            stmt.bindNullableText(BIND_3, content.contentText)
            stmt.bindNullableText(BIND_4, content.contentHtml)
            stmt.bindNullableText(BIND_5, content.audioUrl)
            stmt.bindNullableText(BIND_6, content.duration)
            stmt.bindNullableText(BIND_7, content.thumbnailMediaId)
            stmt.bindNullableText(BIND_8, content.fallbackFeedTitle)
            stmt.step()
        }
}

private fun insertEntryStateRow(connection: SQLiteConnection, state: EntryStateEntity) {
    connection
        .prepare(
            "INSERT OR REPLACE INTO `entry_state` (`entryId`, `inboxState`, `readAt`, " +
                "`progress`, `interactionUpdatedAt`, `updatedAt`, `isCachedOffline`, " +
                "`hlc`, `isLocalDirty`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )
        .use { stmt ->
            stmt.bindText(BIND_1, state.entryId)
            stmt.bindText(BIND_2, state.inboxState)
            stmt.bindNullableText(BIND_3, state.readAt)
            stmt.bindDouble(BIND_4, state.progress)
            stmt.bindNullableText(BIND_5, state.interactionUpdatedAt)
            stmt.bindText(BIND_6, state.updatedAt)
            stmt.bindLong(BIND_7, if (state.isCachedOffline) 1L else 0L)
            stmt.bindText(BIND_8, state.hlc)
            stmt.bindLong(BIND_9, if (state.isLocalDirty) 1L else 0L)
            stmt.step()
        }
}

private fun insertFeedSourceRow(connection: SQLiteConnection, source: FeedSourceEntity) {
    connection
        .prepare(
            "INSERT OR REPLACE INTO `feed_sources` (`collectionId`, `feedUrl`, `siteUrl`, " +
                "`sourceType`, `feedTitle`, `language`, `pollInterval`, `isActive`, " +
                "`lastPolledAt`, `lastPolledBy`, `lastError`, `lastErrorBy`, " +
                "`autoDownloadEpisodes`, `customPlaybackSpeed`, `hlc`, `isLocalDirty`) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )
        .use { stmt ->
            stmt.bindText(BIND_1, source.collectionId)
            stmt.bindText(BIND_2, source.feedUrl)
            stmt.bindNullableText(BIND_3, source.siteUrl)
            stmt.bindText(BIND_4, source.sourceType)
            stmt.bindNullableText(BIND_5, source.feedTitle)
            stmt.bindNullableText(BIND_6, source.language)
            stmt.bindLong(BIND_7, source.pollInterval.toLong())
            stmt.bindLong(BIND_8, if (source.isActive) 1L else 0L)
            stmt.bindNullableText(BIND_9, source.lastPolledAt)
            stmt.bindNullableText(BIND_10, source.lastPolledBy)
            stmt.bindNullableText(BIND_11, source.lastError)
            stmt.bindNullableText(BIND_12, source.lastErrorBy)
            stmt.bindLong(BIND_13, if (source.autoDownloadEpisodes) 1L else 0L)
            stmt.bindNullableDouble(BIND_14, source.customPlaybackSpeed?.toDouble())
            stmt.bindText(BIND_15, source.hlc)
            stmt.bindLong(BIND_16, if (source.isLocalDirty) 1L else 0L)
            stmt.step()
        }
}

private fun insertCollectionSpecNewRow(connection: SQLiteConnection, spec: CollectionSpecEntity) {
    connection
        .prepare(
            "INSERT OR REPLACE INTO `collection_specs_new` (`collectionId`, `slug`, `mode`, " +
                "`role`, `ruleJson`, `showInLists`, `color`) VALUES (?, ?, ?, ?, ?, ?, ?)"
        )
        .use { stmt ->
            bindCollectionSpecStmt(stmt, spec)
            stmt.step()
        }
}

private fun insertCollectionSpecIgnoreRow(
    connection: SQLiteConnection,
    spec: CollectionSpecEntity,
) {
    connection
        .prepare(
            "INSERT OR IGNORE INTO `collection_specs_new` (`collectionId`, `slug`, `mode`, " +
                "`role`, `ruleJson`, `showInLists`, `color`) VALUES (?, ?, ?, ?, ?, ?, ?)"
        )
        .use { stmt ->
            bindCollectionSpecStmt(stmt, spec)
            stmt.step()
        }
}

private fun bindCollectionSpecStmt(stmt: SQLiteStatement, spec: CollectionSpecEntity) {
    stmt.bindText(BIND_1, spec.collectionId)
    stmt.bindNullableText(BIND_2, spec.slug)
    stmt.bindText(BIND_3, spec.mode)
    stmt.bindText(BIND_4, spec.role)
    stmt.bindNullableText(BIND_5, spec.ruleJson)
    stmt.bindLong(BIND_6, if (spec.showInLists) 1L else 0L)
    stmt.bindNullableText(BIND_7, spec.color)
}

private fun insertEntryIdentifierIgnoreRow(
    connection: SQLiteConnection,
    scheme: String,
    value: String,
    entryId: String,
) {
    connection
        .prepare(
            "INSERT OR IGNORE INTO `entry_identifiers` (`scheme`, `value`, `entryId`) " +
                "VALUES (?, ?, ?)"
        )
        .use { stmt ->
            stmt.bindText(BIND_1, scheme)
            stmt.bindText(BIND_2, value)
            stmt.bindText(BIND_3, entryId)
            stmt.step()
        }
}

private fun insertCollectionEntryNewRow(connection: SQLiteConnection, row: CollectionEntryEntity) {
    connection
        .prepare(
            "INSERT OR REPLACE INTO `collection_entries_new` (`collectionId`, `entryId`, " +
                "`position`, `addedAt`, `hlc`, `isLocalDirty`) VALUES (?, ?, ?, ?, ?, ?)"
        )
        .use { stmt ->
            bindCollectionEntryStmt(stmt, row)
            stmt.step()
        }
}

private fun insertCollectionEntryIgnoreRow(
    connection: SQLiteConnection,
    row: CollectionEntryEntity,
) {
    connection
        .prepare(
            "INSERT OR IGNORE INTO `collection_entries_new` (`collectionId`, `entryId`, " +
                "`position`, `addedAt`, `hlc`, `isLocalDirty`) VALUES (?, ?, ?, ?, ?, ?)"
        )
        .use { stmt ->
            bindCollectionEntryStmt(stmt, row)
            stmt.step()
        }
}

private fun bindCollectionEntryStmt(stmt: SQLiteStatement, row: CollectionEntryEntity) {
    stmt.bindText(BIND_1, row.collectionId)
    stmt.bindText(BIND_2, row.entryId)
    stmt.bindNullableDouble(BIND_3, row.position)
    stmt.bindText(BIND_4, row.addedAt)
    stmt.bindText(BIND_5, row.hlc)
    stmt.bindLong(BIND_6, if (row.isLocalDirty) 1L else 0L)
}

private fun insertGeneratorNewRow(connection: SQLiteConnection, gen: CollectionGeneratorEntity) {
    connection
        .prepare(
            "INSERT OR REPLACE INTO `collection_generators_new` (`collectionId`, `enabled`, " +
                "`currentEntryId`, `drawnAt`, `updatedAt`, `hlc`, `isLocalDirty`) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)"
        )
        .use { stmt ->
            stmt.bindText(BIND_1, gen.collectionId)
            stmt.bindLong(BIND_2, if (gen.enabled) 1L else 0L)
            stmt.bindNullableText(BIND_3, gen.currentEntryId)
            stmt.bindNullableText(BIND_4, gen.drawnAt)
            stmt.bindText(BIND_5, gen.updatedAt)
            stmt.bindText(BIND_6, gen.hlc)
            stmt.bindLong(BIND_7, if (gen.isLocalDirty) 1L else 0L)
            stmt.step()
        }
}

private fun insertLogNewRow(connection: SQLiteConnection, log: ConsumptionLogEntity) {
    connection
        .prepare(
            "INSERT OR IGNORE INTO `consumption_log_new` (`id`, `entryId`, `ts`, `status`, " +
                "`notes`, `createdAt`, `hlc`, `isLocalDirty`) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        )
        .use { stmt ->
            stmt.bindText(BIND_1, log.id)
            stmt.bindText(BIND_2, log.entryId)
            stmt.bindText(BIND_3, log.ts)
            stmt.bindText(BIND_4, log.status)
            stmt.bindNullableText(BIND_5, log.notes)
            stmt.bindText(BIND_6, log.createdAt)
            stmt.bindText(BIND_7, log.hlc)
            stmt.bindLong(BIND_8, if (log.isLocalDirty) 1L else 0L)
            stmt.step()
        }
}

private fun insertComparisonNewRow(connection: SQLiteConnection, comp: ComparisonEntity) {
    connection
        .prepare(
            "INSERT OR IGNORE INTO `comparisons_new` (`id`, `ts`, `scopeCollectionId`, " +
                "`orderingJson`, `createdAt`, `hlc`, `isLocalDirty`) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)"
        )
        .use { stmt ->
            stmt.bindText(BIND_1, comp.id)
            stmt.bindText(BIND_2, comp.ts)
            stmt.bindNullableText(BIND_3, comp.scopeCollectionId)
            stmt.bindText(BIND_4, comp.orderingJson)
            stmt.bindText(BIND_5, comp.createdAt)
            stmt.bindText(BIND_6, comp.hlc)
            stmt.bindLong(BIND_7, if (comp.isLocalDirty) 1L else 0L)
            stmt.step()
        }
}

private fun upsertTombstoneNewRow(connection: SQLiteConnection, tomb: TombstoneEntity) {
    connection
        .prepare(
            "INSERT OR REPLACE INTO `sync_tombstones_new` (`entityType`, `naturalKey`, " +
                "`deletedAt`, `hlc`, `isLocalDirty`) VALUES (?, ?, ?, ?, ?)"
        )
        .use { stmt ->
            bindTombstoneStmt(stmt, tomb)
            stmt.step()
        }
}

private fun upsertTombstoneNewIgnoreRow(connection: SQLiteConnection, tomb: TombstoneEntity) {
    connection
        .prepare(
            "INSERT OR IGNORE INTO `sync_tombstones_new` (`entityType`, `naturalKey`, " +
                "`deletedAt`, `hlc`, `isLocalDirty`) VALUES (?, ?, ?, ?, ?)"
        )
        .use { stmt ->
            bindTombstoneStmt(stmt, tomb)
            stmt.step()
        }
}

private fun bindTombstoneStmt(stmt: SQLiteStatement, tomb: TombstoneEntity) {
    stmt.bindText(BIND_1, tomb.entityType)
    stmt.bindText(BIND_2, tomb.naturalKey)
    stmt.bindText(BIND_3, tomb.deletedAt)
    stmt.bindText(BIND_4, tomb.hlc)
    stmt.bindLong(BIND_5, if (tomb.isLocalDirty) 1L else 0L)
}

private fun SQLiteStatement.textOrNull(index: Int): String? =
    if (isNull(index)) null else getText(index)

private fun SQLiteStatement.longOrNull(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

private fun SQLiteStatement.doubleOrNull(index: Int): Double? =
    if (isNull(index)) null else getDouble(index)

private fun SQLiteStatement.bindNullableText(index: Int, value: String?) {
    if (value == null) bindNull(index) else bindText(index, value)
}

private fun SQLiteStatement.bindNullableLong(index: Int, value: Long?) {
    if (value == null) bindNull(index) else bindLong(index, value)
}

private fun SQLiteStatement.bindNullableDouble(index: Int, value: Double?) {
    if (value == null) bindNull(index) else bindDouble(index, value)
}

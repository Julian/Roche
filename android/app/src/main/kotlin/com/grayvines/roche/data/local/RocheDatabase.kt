@file:Suppress("StringLiteralDuplication")

package com.grayvines.roche.data.local

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL

@Database(
    entities =
        [
            EntryEntity::class,
            EntryContentEntity::class,
            EntryStateEntity::class,
            EntryFtsEntity::class,
            EntryContentFtsEntity::class,
            EntryIdentifierEntity::class,
            FeedSourceEntity::class,
            CollectionSpecEntity::class,
            CollectionEntryEntity::class,
            CollectionGeneratorEntity::class,
            ConsumptionLogEntity::class,
            ComparisonEntity::class,
            TombstoneEntity::class,
            LocalSyncFlagEntity::class,
        ],
    version = 10,
    exportSchema = true,
)
abstract class RocheDatabase : RoomDatabase() {
    abstract fun dao(): RocheDao

    companion object {
        private const val DATABASE_NAME = "roche.db"

        internal val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "CREATE TABLE IF NOT EXISTS `pending_mutations` (" +
                            "`entityId` TEXT NOT NULL, " +
                            "`isLocalFeed` INTEGER NOT NULL, " +
                            "`isLocalItem` INTEGER NOT NULL, " +
                            "`titleOverride` TEXT, " +
                            "`dirtyStatus` TEXT, " +
                            "`dirtyFavorite` INTEGER, " +
                            "`dirtyReadProgress` REAL, " +
                            "`addedTagsCsv` TEXT NOT NULL, " +
                            "`removedTagsCsv` TEXT NOT NULL, " +
                            "PRIMARY KEY(`entityId`))"
                    )
                }
            }

        internal val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL("ALTER TABLE `items` ADD COLUMN `readAt` TEXT")
                    connection.execSQL("ALTER TABLE `items` ADD COLUMN `thumbnailUrl` TEXT")
                    connection.execSQL(
                        "ALTER TABLE `pending_mutations` ADD COLUMN `dirtyReadAt` TEXT"
                    )
                }
            }

        internal val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL("ALTER TABLE `feeds` ADD COLUMN `description` TEXT")
                    connection.execSQL("ALTER TABLE `feeds` ADD COLUMN `language` TEXT")
                    connection.execSQL(
                        "ALTER TABLE `feeds` ADD COLUMN `pollInterval` INTEGER NOT NULL DEFAULT 30"
                    )
                    connection.execSQL("ALTER TABLE `feeds` ADD COLUMN `lastPolled` TEXT")
                    connection.execSQL("ALTER TABLE `feeds` ADD COLUMN `lastPolledBy` TEXT")
                    connection.execSQL("ALTER TABLE `feeds` ADD COLUMN `lastError` TEXT")
                    connection.execSQL("ALTER TABLE `feeds` ADD COLUMN `lastErrorBy` TEXT")
                    connection.execSQL(
                        "ALTER TABLE `feeds` ADD COLUMN `isActive` INTEGER NOT NULL DEFAULT 1"
                    )
                    connection.execSQL("ALTER TABLE `items` ADD COLUMN `feedUrl` TEXT")
                    connection.execSQL("ALTER TABLE `items` ADD COLUMN `interactionUpdatedAt` TEXT")
                    connection.execSQL("ALTER TABLE `tags` ADD COLUMN `color` TEXT")
                    connection.execSQL(
                        "CREATE TABLE IF NOT EXISTS `sync_tombstones` (" +
                            "`entityType` TEXT NOT NULL, " +
                            "`naturalKey` TEXT NOT NULL, " +
                            "`deletedAt` TEXT NOT NULL, " +
                            "PRIMARY KEY(`entityType`, `naturalKey`))"
                    )
                }
            }

        internal val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "ALTER TABLE `feeds` ADD COLUMN `autoDownloadEpisodes` INTEGER NOT NULL DEFAULT 0"
                    )
                    connection.execSQL("ALTER TABLE `feeds` ADD COLUMN `customPlaybackSpeed` REAL")
                    connection.execSQL("ALTER TABLE `items` ADD COLUMN `audioUrl` TEXT")
                }
            }

        internal val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "CREATE TABLE IF NOT EXISTS `item_contents` (" +
                            "`itemId` TEXT NOT NULL, " +
                            "`summary` TEXT, " +
                            "`contentText` TEXT, " +
                            "`contentHtml` TEXT, " +
                            "PRIMARY KEY(`itemId`), " +
                            "FOREIGN KEY(`itemId`) REFERENCES `items`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE)"
                    )
                    connection.execSQL(
                        "INSERT OR REPLACE INTO `item_contents` " +
                            "(`itemId`, `summary`, `contentText`, `contentHtml`) " +
                            "SELECT `id`, `summary`, `contentText`, `contentHtml` FROM `items` " +
                            "WHERE LENGTH(`summary`) > $LIST_PREVIEW_SUMMARY_CHARS " +
                            "OR LENGTH(`contentText`) > $LIST_PREVIEW_TEXT_CHARS " +
                            "OR LENGTH(`contentHtml`) > $LIST_PREVIEW_HTML_CHARS"
                    )
                    connection.execSQL(
                        "UPDATE `items` SET " +
                            "`summary` = SUBSTR(`summary`, 1, $LIST_PREVIEW_SUMMARY_CHARS), " +
                            "`contentText` = SUBSTR(`contentText`, 1, $LIST_PREVIEW_TEXT_CHARS), " +
                            "`contentHtml` = SUBSTR(`contentHtml`, 1, $LIST_PREVIEW_HTML_CHARS) " +
                            "WHERE LENGTH(`summary`) > $LIST_PREVIEW_SUMMARY_CHARS " +
                            "OR LENGTH(`contentText`) > $LIST_PREVIEW_TEXT_CHARS " +
                            "OR LENGTH(`contentHtml`) > $LIST_PREVIEW_HTML_CHARS"
                    )
                    connection.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_items_publishedAt_createdAt` " +
                            "ON `items` (`publishedAt`, `createdAt`)"
                    )
                }
            }

        internal val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    migrateCatalogSchemaV7(connection)
                }
            }

        internal val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    migrateCatalogAndSyncSchemaV8(connection)
                }
            }

        internal val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    migrateItemsFtsSchemaV9(connection)
                }
            }

        internal val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    migrateUnifiedEntriesSchemaV10(connection)
                }
            }

        fun create(context: Context): RocheDatabase =
            Room.databaseBuilder<RocheDatabase>(context.applicationContext, DATABASE_NAME)
                .setDriver(BundledSQLiteDriver())
                .addCallback(
                    object : Callback() {
                        override suspend fun onOpen(connection: SQLiteConnection) {
                            connection.execSQL("PRAGMA recursive_triggers = ON")
                        }
                    }
                )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}

internal fun migrateItemsFtsSchemaV9(connection: SQLiteConnection) {
    connection.execSQL("DROP TABLE IF EXISTS `items_fts`")
    connection.execSQL("DROP TABLE IF EXISTS `item_contents_fts`")
    createItemsFtsV9(connection)
    createItemContentsFtsV9(connection)
    connection.execSQL("INSERT INTO `items_fts`(`items_fts`) VALUES('rebuild')")
    connection.execSQL("INSERT INTO `item_contents_fts`(`item_contents_fts`) VALUES('rebuild')")
}

private fun createItemsFtsV9(connection: SQLiteConnection) {
    connection.execSQL(
        "CREATE VIRTUAL TABLE IF NOT EXISTS `items_fts` USING FTS5(" +
            "`title`, `feedTitle`, `author`, `url`, " +
            "`summary`, `contentText`, `contentHtml`, " +
            "tokenize=`unicode61`, content=`items`)"
    )
    connection.execSQL(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_items_fts_BEFORE_UPDATE " +
            "BEFORE UPDATE ON `items` BEGIN " +
            "DELETE FROM `items_fts` WHERE `rowid`=OLD.`rowid`; END"
    )
    connection.execSQL(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_items_fts_BEFORE_DELETE " +
            "BEFORE DELETE ON `items` BEGIN " +
            "DELETE FROM `items_fts` WHERE `rowid`=OLD.`rowid`; END"
    )
    connection.execSQL(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_items_fts_AFTER_UPDATE " +
            "AFTER UPDATE ON `items` BEGIN " +
            "INSERT INTO `items_fts`(`rowid`, `title`, `feedTitle`, `author`, `url`, " +
            "`summary`, `contentText`, `contentHtml`) VALUES (" +
            "NEW.`rowid`, NEW.`title`, NEW.`feedTitle`, NEW.`author`, NEW.`url`, " +
            "NEW.`summary`, NEW.`contentText`, NEW.`contentHtml`); END"
    )
    connection.execSQL(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_items_fts_AFTER_INSERT " +
            "AFTER INSERT ON `items` BEGIN " +
            "INSERT INTO `items_fts`(`rowid`, `title`, `feedTitle`, `author`, `url`, " +
            "`summary`, `contentText`, `contentHtml`) VALUES (" +
            "NEW.`rowid`, NEW.`title`, NEW.`feedTitle`, NEW.`author`, NEW.`url`, " +
            "NEW.`summary`, NEW.`contentText`, NEW.`contentHtml`); END"
    )
}

private fun createItemContentsFtsV9(connection: SQLiteConnection) {
    connection.execSQL(
        "CREATE VIRTUAL TABLE IF NOT EXISTS `item_contents_fts` USING FTS5(" +
            "`summary`, `contentText`, `contentHtml`, " +
            "tokenize=`unicode61`, content=`item_contents`)"
    )
    connection.execSQL(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_item_contents_fts_BEFORE_UPDATE " +
            "BEFORE UPDATE ON `item_contents` BEGIN " +
            "DELETE FROM `item_contents_fts` WHERE `rowid`=OLD.`rowid`; END"
    )
    connection.execSQL(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_item_contents_fts_BEFORE_DELETE " +
            "BEFORE DELETE ON `item_contents` BEGIN " +
            "DELETE FROM `item_contents_fts` WHERE `rowid`=OLD.`rowid`; END"
    )
    connection.execSQL(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_item_contents_fts_AFTER_UPDATE " +
            "AFTER UPDATE ON `item_contents` BEGIN " +
            "INSERT INTO `item_contents_fts`(`rowid`, `summary`, `contentText`, `contentHtml`) " +
            "VALUES (NEW.`rowid`, NEW.`summary`, NEW.`contentText`, NEW.`contentHtml`); END"
    )
    connection.execSQL(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_item_contents_fts_AFTER_INSERT " +
            "AFTER INSERT ON `item_contents` BEGIN " +
            "INSERT INTO `item_contents_fts`(`rowid`, `summary`, `contentText`, `contentHtml`) " +
            "VALUES (NEW.`rowid`, NEW.`summary`, NEW.`contentText`, NEW.`contentHtml`); END"
    )
}

internal fun migrateCatalogSchemaV7(connection: SQLiteConnection) {
    migrateCatalogEntriesV7(connection)
    migrateCatalogCollectionsV7(connection)
    migrateCatalogActivityV7(connection)
}

private fun migrateCatalogEntriesV7(connection: SQLiteConnection) {
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `catalog_entries` (" +
            "`id` TEXT NOT NULL, " +
            "`kind` TEXT NOT NULL, " +
            "`title` TEXT NOT NULL, " +
            "`creatorsJson` TEXT NOT NULL, " +
            "`url` TEXT, " +
            "`publishedAt` TEXT, " +
            "`releaseYear` INTEGER, " +
            "`summary` TEXT, " +
            "`thumbnailUrl` TEXT, " +
            "`metadataJson` TEXT NOT NULL, " +
            "`mergedIntoId` TEXT, " +
            "`createdAt` TEXT NOT NULL, " +
            "`updatedAt` TEXT NOT NULL, " +
            "`isLocalDirty` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_catalog_entries_kind` ON `catalog_entries` (`kind`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_catalog_entries_mergedIntoId` " +
            "ON `catalog_entries` (`mergedIntoId`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_catalog_entries_updatedAt` " +
            "ON `catalog_entries` (`updatedAt`)"
    )
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `entry_identifiers` (" +
            "`scheme` TEXT NOT NULL, " +
            "`value` TEXT NOT NULL, " +
            "`entryId` TEXT NOT NULL, " +
            "PRIMARY KEY(`scheme`, `value`))"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_entry_identifiers_entryId` " +
            "ON `entry_identifiers` (`entryId`)"
    )
}

private fun migrateCatalogCollectionsV7(connection: SQLiteConnection) {
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `collection_specs` (" +
            "`collectionId` TEXT NOT NULL, " +
            "`title` TEXT NOT NULL, " +
            "`summary` TEXT, " +
            "`slug` TEXT, " +
            "`mode` TEXT NOT NULL, " +
            "`role` TEXT NOT NULL, " +
            "`ruleJson` TEXT, " +
            "`showInLists` INTEGER NOT NULL, " +
            "`color` TEXT, " +
            "`createdAt` TEXT NOT NULL, " +
            "`updatedAt` TEXT NOT NULL, " +
            "`isLocalDirty` INTEGER NOT NULL, " +
            "PRIMARY KEY(`collectionId`))"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_collection_specs_slug` ON `collection_specs` (`slug`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_collection_specs_role` ON `collection_specs` (`role`)"
    )
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `collection_entries` (" +
            "`collectionId` TEXT NOT NULL, " +
            "`entryId` TEXT NOT NULL, " +
            "`position` REAL, " +
            "`addedAt` TEXT NOT NULL, " +
            "`isLocalDirty` INTEGER NOT NULL, " +
            "PRIMARY KEY(`collectionId`, `entryId`))"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_collection_entries_entryId` " +
            "ON `collection_entries` (`entryId`)"
    )
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `collection_generators` (" +
            "`collectionId` TEXT NOT NULL, " +
            "`enabled` INTEGER NOT NULL, " +
            "`currentEntryId` TEXT, " +
            "`drawnAt` TEXT, " +
            "`updatedAt` TEXT NOT NULL, " +
            "`isLocalDirty` INTEGER NOT NULL, " +
            "PRIMARY KEY(`collectionId`))"
    )
}

private fun migrateCatalogActivityV7(connection: SQLiteConnection) {
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `consumption_log` (" +
            "`id` TEXT NOT NULL, " +
            "`entryId` TEXT NOT NULL, " +
            "`ts` TEXT NOT NULL, " +
            "`status` TEXT NOT NULL, " +
            "`notes` TEXT, " +
            "`createdAt` TEXT NOT NULL, " +
            "`isLocalDirty` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_consumption_log_entryId` " +
            "ON `consumption_log` (`entryId`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_consumption_log_ts` ON `consumption_log` (`ts`)"
    )
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `comparisons` (" +
            "`id` TEXT NOT NULL, " +
            "`ts` TEXT NOT NULL, " +
            "`scopeCollectionId` TEXT, " +
            "`orderingJson` TEXT NOT NULL, " +
            "`createdAt` TEXT NOT NULL, " +
            "`isLocalDirty` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_comparisons_scopeCollectionId` " +
            "ON `comparisons` (`scopeCollectionId`)"
    )
    connection.execSQL("CREATE INDEX IF NOT EXISTS `index_comparisons_ts` ON `comparisons` (`ts`)")
}

internal const val FLAG_CATALOG_BACKFILL_NEEDED = "catalog_backfill_needed"
private const val APPROX_ARTICLE_CHARS_PER_MINUTE = 1_080

internal fun migrateCatalogAndSyncSchemaV8(connection: SQLiteConnection) {
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `local_sync_flags` (" +
            "`flagKey` TEXT NOT NULL, " +
            "`flagValue` TEXT NOT NULL, " +
            "PRIMARY KEY(`flagKey`))"
    )
    connection.execSQL(
        "INSERT OR REPLACE INTO `local_sync_flags` (`flagKey`, `flagValue`) " +
            "VALUES ('$FLAG_CATALOG_BACKFILL_NEEDED', '1')"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_items_interactionUpdatedAt` " +
            "ON `items` (`interactionUpdatedAt`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_items_createdAt` ON `items` (`createdAt`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_collection_entries_addedAt` " +
            "ON `collection_entries` (`addedAt`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_consumption_log_createdAt` " +
            "ON `consumption_log` (`createdAt`)"
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_comparisons_createdAt` ON `comparisons` (`createdAt`)"
    )
    connection.execSQL(
        "UPDATE `items` SET `duration` = (" +
            "SELECT CAST(MAX(1, LENGTH(COALESCE(ic.`contentText`, ic.`contentHtml`, ic.`summary`, '')) / " +
            "$APPROX_ARTICLE_CHARS_PER_MINUTE) AS TEXT) || 'm' " +
            "FROM `item_contents` ic WHERE ic.`itemId` = `items`.`id`" +
            ") WHERE LOWER(`contentType`) = 'article' AND (`duration` IS NULL OR `duration` = '') " +
            "AND EXISTS (SELECT 1 FROM `item_contents` ic WHERE ic.`itemId` = `items`.`id` " +
            "AND LENGTH(TRIM(COALESCE(ic.`contentText`, ic.`contentHtml`, ic.`summary`, ''))) > 0)"
    )
    normalizeV7EntryIdentifiers(connection)
}

private fun normalizeV7EntryIdentifiers(connection: SQLiteConnection) {
    connection.execSQL(
        "INSERT OR IGNORE INTO `entry_identifiers` (`scheme`, `value`, `entryId`) " +
            "SELECT `scheme`, LOWER(TRIM(`value`)), `entryId` FROM `entry_identifiers` " +
            "WHERE `scheme` IN ('slug', 'tag', '1001albums') AND `value` != LOWER(TRIM(`value`))"
    )
    connection.execSQL(
        "DELETE FROM `entry_identifiers` " +
            "WHERE `scheme` IN ('slug', 'tag', '1001albums') AND `value` != LOWER(TRIM(`value`))"
    )
    connection.execSQL(
        "INSERT OR IGNORE INTO `entry_identifiers` (`scheme`, `value`, `entryId`) " +
            "SELECT 'spotify', " +
            "CASE (SELECT `kind` FROM `catalog_entries` ce WHERE ce.`id` = ei.`entryId`) " +
            "WHEN 'song' THEN 'spotify:track:' " +
            "WHEN 'artist' THEN 'spotify:artist:' " +
            "WHEN 'podcast' THEN 'spotify:show:' " +
            "WHEN 'podcast_episode' THEN 'spotify:episode:' " +
            "ELSE 'spotify:album:' END || TRIM(ei.`value`), " +
            "ei.`entryId` " +
            "FROM `entry_identifiers` ei " +
            "WHERE ei.`scheme` = 'spotify' AND INSTR(ei.`value`, ':') = 0 " +
            "AND INSTR(ei.`value`, '/') = 0 AND LENGTH(TRIM(ei.`value`)) = 22 " +
            "AND EXISTS (SELECT 1 FROM `catalog_entries` ce WHERE ce.`id` = ei.`entryId` " +
            "AND ce.`kind` IN ('album', 'song', 'artist', 'podcast', 'podcast_episode'))"
    )
    connection.execSQL(
        "DELETE FROM `entry_identifiers` " +
            "WHERE `scheme` = 'spotify' AND INSTR(`value`, ':') = 0 " +
            "AND INSTR(`value`, '/') = 0 AND LENGTH(TRIM(`value`)) = 22 " +
            "AND EXISTS (SELECT 1 FROM `catalog_entries` ce " +
            "WHERE ce.`id` = `entry_identifiers`.`entryId` " +
            "AND ce.`kind` IN ('album', 'song', 'artist', 'podcast', 'podcast_episode'))"
    )
}

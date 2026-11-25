@file:Suppress("StringLiteralDuplication")

package com.grayvines.roche.data

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.migrateCatalogAndSyncSchemaV8
import com.grayvines.roche.data.local.migrateCatalogSchemaV7
import com.grayvines.roche.data.local.migrateSchemaV11ToV12
import com.grayvines.roche.data.local.migrateSchemaV12ToV13
import com.grayvines.roche.data.local.migrateSchemaV13ToV14
import com.grayvines.roche.data.local.migrateSchemaV14ToV15
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Migration15To16Test {
    @TempDir lateinit var tempDir: File

    @Test
    fun `Room MIGRATION_15_16 replaces single indices with composite covering indices and preserves data`() =
        runTest {
            val dbFile = File(tempDir, "roche_v15_to_v16.db")
            BundledSQLiteDriver().open(dbFile.absolutePath).use { conn ->
                createV15Schema(conn)
                seedV15Rows(conn)
            }

            val migratedDb =
                Room.databaseBuilder<RocheDatabase>(name = dbFile.absolutePath)
                    .setDriver(BundledSQLiteDriver())
                    .addCallback(RocheDatabase.schemaCallback())
                    .addMigrations(*RocheDatabase.allMigrations())
                    .build()
            try {
                val dao = migratedDb.dao()
                val previewItems = dao.getAllPreviewItems()
                assertEquals(1, previewItems.size)
                assertEquals("art-v15", previewItems.single().id)
                assertEquals(2025, previewItems.single().releaseYear)

                val kindCounts =
                    dao.getConsumedEntryCountsByKind().associate { it.kind to it.count }
                assertEquals(1, kindCounts["song"])
                assertEquals(1, dao.countComparisons())
            } finally {
                migratedDb.close()
            }

            verifyV16Indices(dbFile)
        }

    private fun verifyV16Indices(dbFile: File) {
        BundledSQLiteDriver().open(dbFile.absolutePath).use { conn ->
            val allIndexes = readAllIndexNames(conn)
            assertFalse("index_entries_kind" in allIndexes)
            assertFalse("index_entry_identifiers_entryId" in allIndexes)
            assertFalse("index_collection_entries_entryId" in allIndexes)
            assertFalse("index_comparisons_scopeCollectionId" in allIndexes)

            assertEquals(
                listOf("kind", "mergedIntoId"),
                readIndexColumns(conn, "index_entries_kind_mergedIntoId"),
            )
            assertEquals(
                listOf("entryId", "scheme", "value"),
                readIndexColumns(conn, "index_entry_identifiers_entryId_scheme_value"),
            )
            assertEquals(
                listOf("entryId", "addedAt", "collectionId"),
                readIndexColumns(conn, "index_collection_entries_entryId_addedAt_collectionId"),
            )
            assertEquals(
                listOf("scopeCollectionId", "ts"),
                readIndexColumns(conn, "index_comparisons_scopeCollectionId_ts"),
            )
            assertEquals(
                listOf("status", "entryId"),
                readIndexColumns(conn, "index_consumption_log_status_entryId"),
            )
            assertTrue("index_consumption_log_entryId_status" in allIndexes)
        }
    }

    private fun readAllIndexNames(conn: SQLiteConnection): Set<String> {
        val names = HashSet<String>()
        conn.prepare("SELECT `name` FROM `sqlite_master` WHERE `type` = 'index'").use { stmt ->
            while (stmt.step()) {
                names.add(stmt.getText(0))
            }
        }
        return names
    }

    private fun readIndexColumns(conn: SQLiteConnection, indexName: String): List<String> {
        val cols = ArrayList<String>()
        conn.prepare("PRAGMA index_info(`$indexName`)").use { stmt ->
            while (stmt.step()) {
                cols.add(stmt.getText(2))
            }
        }
        return cols
    }

    private suspend fun createV15Schema(conn: SQLiteConnection) {
        createV6BaseTables(conn)
        migrateCatalogSchemaV7(conn)
        migrateCatalogAndSyncSchemaV8(conn)
        RocheDatabase.MIGRATION_8_9.migrate(conn)
        RocheDatabase.MIGRATION_9_10.migrate(conn)
        RocheDatabase.MIGRATION_10_11.migrate(conn)
        migrateSchemaV11ToV12(conn)
        migrateSchemaV12ToV13(conn)
        migrateSchemaV13ToV14(conn)
        migrateSchemaV14ToV15(conn)
        conn.execSQL("PRAGMA user_version = 15")
    }

    private fun createV6BaseTables(conn: SQLiteConnection) {
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS `feeds` (" +
                "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `url` TEXT NOT NULL, " +
                "`siteUrl` TEXT, `feedType` TEXT NOT NULL, `description` TEXT, " +
                "`language` TEXT, `pollInterval` INTEGER NOT NULL DEFAULT 30, " +
                "`lastPolled` TEXT, `lastPolledBy` TEXT, `lastError` TEXT, " +
                "`lastErrorBy` TEXT, `isActive` INTEGER NOT NULL DEFAULT 1, " +
                "`autoDownloadEpisodes` INTEGER NOT NULL DEFAULT 0, " +
                "`customPlaybackSpeed` REAL, PRIMARY KEY(`id`))"
        )
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS `items` (" +
                "`id` TEXT NOT NULL, `feedId` TEXT, `feedTitle` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `url` TEXT NOT NULL, `author` TEXT, `summary` TEXT, " +
                "`contentText` TEXT, `contentHtml` TEXT, `contentType` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL, " +
                "`isCachedOffline` INTEGER NOT NULL, `publishedAt` TEXT, " +
                "`createdAt` TEXT NOT NULL, `readProgress` REAL NOT NULL, " +
                "`thumbnailMediaId` TEXT, `duration` TEXT, `readAt` TEXT, " +
                "`thumbnailUrl` TEXT, `feedUrl` TEXT, `interactionUpdatedAt` TEXT, " +
                "`audioUrl` TEXT, PRIMARY KEY(`id`))"
        )
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS `item_contents` (`itemId` TEXT NOT NULL, `summary` TEXT, " +
                "`contentText` TEXT, `contentHtml` TEXT, PRIMARY KEY(`itemId`), " +
                "FOREIGN KEY(`itemId`) REFERENCES `items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS `tags` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `color` TEXT, PRIMARY KEY(`id`))"
        )
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS `item_tags` (`itemId` TEXT NOT NULL, `tagId` TEXT NOT NULL, PRIMARY KEY(`itemId`, `tagId`))"
        )
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_mutations` (`entityId` TEXT NOT NULL, " +
                "`isLocalFeed` INTEGER NOT NULL, `isLocalItem` INTEGER NOT NULL, " +
                "`titleOverride` TEXT, `dirtyStatus` TEXT, `dirtyFavorite` INTEGER, " +
                "`dirtyReadProgress` REAL, `dirtyReadAt` TEXT, `addedTagsCsv` TEXT NOT NULL, " +
                "`removedTagsCsv` TEXT NOT NULL, PRIMARY KEY(`entityId`))"
        )
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_tombstones` (`entityType` TEXT NOT NULL, " +
                "`naturalKey` TEXT NOT NULL, `deletedAt` TEXT NOT NULL, " +
                "PRIMARY KEY(`entityType`, `naturalKey`))"
        )
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)"
        )
    }

    private fun seedV15Rows(conn: SQLiteConnection) {
        conn.execSQL(
            """INSERT INTO `entries` (`id`, `kind`, `title`, `creatorsJson`, `url`, `releaseYear`, """ +
                """`metadataJson`, `createdAt`, `updatedAt`, `hlc`, `isLocalDirty`) VALUES """ +
                """('art-v15', 'article', 'Deep Essay', '["Author"]', 'https://example.com/essay', 2025, """ +
                """'{}', '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z', '001', 0), """ +
                """('song-v15', 'song', 'Pyramid Song', '["Radiohead"]', NULL, 2001, """ +
                """'{}', '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z', '002', 0)"""
        )
        conn.execSQL(
            "INSERT INTO `entry_state` (`entryId`, `inboxState`, `progress`, `updatedAt`, `isCachedOffline`, `hlc`, `isLocalDirty`) VALUES " +
                "('art-v15', 'unread', 0.0, '2026-06-01T00:00:00Z', 0, '003', 0)"
        )
        conn.execSQL(
            "INSERT INTO `consumption_log` (`id`, `entryId`, `ts`, `status`, `createdAt`, `hlc`, `isLocalDirty`) VALUES " +
                "('log-song-v15', 'song-v15', '2026-06-02T12:00:00Z', 'consumed', '2026-06-02T12:00:00Z', '004', 0)"
        )
        conn.execSQL(
            """INSERT INTO `comparisons` (`id`, `ts`, `scopeCollectionId`, `orderingJson`, `createdAt`, `hlc`, `isLocalDirty`) VALUES """ +
                """('cmp-v15', '2026-06-03T12:00:00Z', NULL, '[["song-v15"],["art-v15"]]', '2026-06-03T12:00:00Z', '005', 0)"""
        )
    }
}

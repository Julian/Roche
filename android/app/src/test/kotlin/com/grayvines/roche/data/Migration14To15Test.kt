@file:Suppress("StringLiteralDuplication")

package com.grayvines.roche.data

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.grayvines.roche.data.local.ConsumptionLogEntity
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.migrateCatalogAndSyncSchemaV8
import com.grayvines.roche.data.local.migrateCatalogSchemaV7
import com.grayvines.roche.data.local.migrateSchemaV11ToV12
import com.grayvines.roche.data.local.migrateSchemaV12ToV13
import com.grayvines.roche.data.local.migrateSchemaV13ToV14
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Migration14To15Test {
    @TempDir lateinit var tempDir: File

    @Test
    fun `Room MIGRATION_14_15 adds composite indices normalizes status and keeps triggers active`() =
        runTest {
            val dbFile = File(tempDir, "roche_v14_to_v15.db")
            BundledSQLiteDriver().open(dbFile.absolutePath).use { conn ->
                createV14Schema(conn)
                seedV14Rows(conn)
            }

            val migratedDb =
                Room.databaseBuilder<RocheDatabase>(name = dbFile.absolutePath)
                    .setDriver(BundledSQLiteDriver())
                    .addCallback(RocheDatabase.schemaCallback())
                    .addMigrations(*RocheDatabase.allMigrations())
                    .build()
            try {
                val dao = migratedDb.dao()
                assertEquals(2, dao.countDiaryLogs())
                val statuses = dao.getDistinctEntryLogStatuses()
                assertEquals(1, statuses.size)
                assertEquals("e-v14", statuses.single().entryId)
                assertEquals("consumed", statuses.single().status)

                // Tie-breaking at identical ts picks deterministic highest id
                val latest = dao.getLatestLogsPerEntry()
                assertEquals(1, latest.size)
                assertEquals("Tie winner note", latest.single().notes)

                // table_revisions trigger increments logsStamp on post-migration UPDATE
                val beforeStamp = dao.getRepositoryStamp().logsStamp
                dao.upsertConsumptionLog(
                    ConsumptionLogEntity(
                        id = "log-v14-2",
                        entryId = "e-v14",
                        ts = "2026-06-02T12:00:00.000Z",
                        status = "consumed",
                        notes = "Edited post-migration",
                        createdAt = "2026-06-02T12:00:00.000Z",
                    )
                )
                val afterStamp = dao.getRepositoryStamp().logsStamp
                assertTrue(afterStamp > beforeStamp)
            } finally {
                migratedDb.close()
            }

            verifyMigratedIndices(dbFile)
        }

    private fun verifyMigratedIndices(dbFile: File) {
        BundledSQLiteDriver().open(dbFile.absolutePath).use { conn ->
            val indexNames = HashSet<String>()
            conn
                .prepare(
                    "SELECT `name` FROM `sqlite_master` " +
                        "WHERE `type` = 'index' AND `tbl_name` = 'consumption_log'"
                )
                .use { stmt ->
                    while (stmt.step()) {
                        indexNames.add(stmt.getText(0))
                    }
                }
            assertFalse("index_consumption_log_entryId" in indexNames)
            assertEquals(
                listOf("entryId", "status"),
                readIndexColumns(conn, "index_consumption_log_entryId_status"),
            )
            assertEquals(
                listOf("entryId", "ts"),
                readIndexColumns(conn, "index_consumption_log_entryId_ts"),
            )
            assertEquals(
                listOf("status", "ts"),
                readIndexColumns(conn, "index_consumption_log_status_ts"),
            )
        }
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

    private suspend fun createV14Schema(conn: SQLiteConnection) {
        createV6LegacyTables(conn)
        migrateCatalogSchemaV7(conn)
        migrateCatalogAndSyncSchemaV8(conn)
        RocheDatabase.MIGRATION_8_9.migrate(conn)
        RocheDatabase.MIGRATION_9_10.migrate(conn)
        RocheDatabase.MIGRATION_10_11.migrate(conn)
        migrateSchemaV11ToV12(conn)
        migrateSchemaV12ToV13(conn)
        migrateSchemaV13ToV14(conn)
        conn.execSQL("PRAGMA user_version = 14")
    }

    private fun createV6LegacyTables(conn: SQLiteConnection) {
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

    private fun seedV14Rows(conn: SQLiteConnection) {
        conn.execSQL(
            "INSERT INTO `entries` (`id`, `kind`, `title`, `creatorsJson`, `url`, " +
                "`metadataJson`, `createdAt`, `updatedAt`, `hlc`, `isLocalDirty`) VALUES " +
                "('e-v14', 'album', 'In Rainbows', '[\"Radiohead\"]', " +
                "'https://open.spotify.com/album/5vkqYmiPBYLaalcmjujWxK', '{}', " +
                "'2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z', '001', 0)"
        )
        conn.execSQL(
            "INSERT INTO `consumption_log` (`id`, `entryId`, `ts`, `status`, `notes`, " +
                "`createdAt`, `hlc`, `isLocalDirty`) VALUES " +
                "('log-v14-1', 'e-v14', '2026-06-02T12:00:00.000Z', 'CONSUMED', 'Earlier id note', " +
                "'2026-06-02T12:00:00.000Z', '002', 0), " +
                "('log-v14-2', 'e-v14', '2026-06-02T12:00:00.000Z', 'consumed', 'Tie winner note', " +
                "'2026-06-02T12:00:00.000Z', '003', 0)"
        )
    }
}

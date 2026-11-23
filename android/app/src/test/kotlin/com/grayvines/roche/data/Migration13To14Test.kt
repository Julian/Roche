@file:Suppress("StringLiteralDuplication")

package com.grayvines.roche.data

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.grayvines.roche.core.deterministicBoardId
import com.grayvines.roche.data.local.BoardEntity
import com.grayvines.roche.data.local.BoardItemEntity
import com.grayvines.roche.data.local.BoardItemStateEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.migrateCatalogAndSyncSchemaV8
import com.grayvines.roche.data.local.migrateCatalogSchemaV7
import com.grayvines.roche.data.local.migrateSchemaV11ToV12
import com.grayvines.roche.data.local.migrateSchemaV12ToV13
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Migration13To14Test {
    @TempDir lateinit var tempDir: File

    @Test
    fun `Room MIGRATION_13_14 creates board tables enforces cascades and installs revision triggers`() =
        runTest {
            val dbFile = File(tempDir, "roche_v13_to_v14.db")
            BundledSQLiteDriver().open(dbFile.absolutePath).use { conn ->
                createV13Schema(conn)
                seedV13Rows(conn)
            }

            val migratedDb =
                Room.databaseBuilder<RocheDatabase>(name = dbFile.absolutePath)
                    .setDriver(BundledSQLiteDriver())
                    .addCallback(RocheDatabase.schemaCallback())
                    .addMigrations(*RocheDatabase.allMigrations())
                    .build()
            try {
                val dao = migratedDb.dao()
                assertEquals("Existing Article", dao.getEntryById("e-v13")?.title)
                assertEquals("custom_list", dao.getCollectionSpecById("col-v13")?.slug)
                verifyBoardTriggersAndCascade(dao)
            } finally {
                migratedDb.close()
            }

            verifyBoardUniqueConstraint(dbFile)
        }

    private suspend fun verifyBoardTriggersAndCascade(dao: RocheDao) {
        val boardId = deterministicBoardId("hacker_news", "top")
        val stamp0 = dao.getRepositoryStamp()
        dao.upsertBoard(
            BoardEntity(
                id = boardId,
                adapterType = "hacker_news",
                sourceKey = "top",
                sourceUrl = "https://news.ycombinator.com/",
                sourceConfigJson = """{"section":"top"}""",
                title = "Hacker News",
                orderMode = "ranked",
                pollInterval = 30,
                isActive = true,
                createdAt = "2026-11-23T18:00:00Z",
                updatedAt = "2026-11-23T18:00:00Z",
                hlc = "001795460400000-0000-android",
            )
        )
        val stamp1 = dao.getRepositoryStamp()
        assertTrue(stamp1.boardsStamp > stamp0.boardsStamp, "boards trigger must fire")

        dao.insertBoardItems(
            listOf(
                BoardItemEntity(
                    boardId = boardId,
                    externalId = "42123456",
                    rank = 1,
                    title = "Example Story",
                    url = "https://example.com/post",
                    discussionUrl = "https://news.ycombinator.com/item?id=42123456",
                    fetchedAt = "2026-11-23T19:00:00Z",
                )
            )
        )
        val stamp2 = dao.getRepositoryStamp()
        assertTrue(stamp2.boardItemsStamp > stamp1.boardItemsStamp, "board_items trigger fires")

        dao.upsertBoardItemState(
            BoardItemStateEntity(
                boardId = boardId,
                externalId = "42123456",
                seenAt = "2026-11-23T19:05:00Z",
                dismissedAt = null,
                lastSeenInSnapshotAt = "2026-11-23T19:00:00Z",
                updatedAt = "2026-11-23T19:05:00Z",
                hlc = "001795460700000-0000-android",
            )
        )
        val stamp3 = dao.getRepositoryStamp()
        assertTrue(stamp3.boardItemStatesStamp > stamp2.boardItemStatesStamp)
        assertNotNull(dao.getBoardItem(boardId, "42123456"))
        assertNotNull(dao.getBoardItemState(boardId, "42123456"))

        dao.deleteBoardById(boardId)
        assertNull(dao.getBoardById(boardId))
        assertTrue(dao.getBoardItemsForBoard(boardId).isEmpty())
        assertTrue(dao.getBoardItemStatesForBoard(boardId).isEmpty())
        val stamp4 = dao.getRepositoryStamp()
        assertTrue(stamp4.boardsStamp > stamp3.boardsStamp)
        assertTrue(stamp4.boardItemsStamp > stamp3.boardItemsStamp)
        assertTrue(stamp4.boardItemStatesStamp > stamp3.boardItemStatesStamp)
    }

    private fun verifyBoardUniqueConstraint(dbFile: File) {
        val columns =
            "`id`, `adapterType`, `sourceKey`, `sourceUrl`, `sourceConfigJson`, `title`, " +
                "`orderMode`, `pollInterval`, `isActive`, `consecutiveFailures`, `snapshotHlc`, " +
                "`createdAt`, `updatedAt`, `hlc`, `isLocalDirty`, `isSnapshotDirty`"
        val cfg = """'{"section":"top"}'"""
        val insertFirst =
            "INSERT INTO `boards` ($columns) VALUES " +
                "('b-1', 'hacker_news', 'top', 'https://news.ycombinator.com/', " +
                "$cfg, 'HN', 'ranked', 30, 1, 0, '', " +
                "'2026-11-23T00:00:00Z', '2026-11-23T00:00:00Z', '001', 0, 0)"
        val insertDup =
            "INSERT INTO `boards` ($columns) VALUES " +
                "('b-2', 'hacker_news', 'top', 'https://news.ycombinator.com/', " +
                "$cfg, 'HN Dup', 'ranked', 30, 1, 0, '', " +
                "'2026-11-23T00:00:00Z', '2026-11-23T00:00:00Z', '002', 0, 0)"
        BundledSQLiteDriver().open(dbFile.absolutePath).use { conn ->
            conn.execSQL(insertFirst)
            assertThrows(Exception::class.java) { conn.execSQL(insertDup) }
        }
    }

    private suspend fun createV13Schema(conn: SQLiteConnection) {
        createV6LegacyTables(conn)
        migrateCatalogSchemaV7(conn)
        migrateCatalogAndSyncSchemaV8(conn)
        RocheDatabase.MIGRATION_8_9.migrate(conn)
        RocheDatabase.MIGRATION_9_10.migrate(conn)
        RocheDatabase.MIGRATION_10_11.migrate(conn)
        migrateSchemaV11ToV12(conn)
        migrateSchemaV12ToV13(conn)
        conn.execSQL("PRAGMA user_version = 13")
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

    private fun seedV13Rows(conn: SQLiteConnection) {
        conn.execSQL(
            "INSERT INTO `entries` (`id`, `kind`, `title`, `creatorsJson`, `url`, " +
                "`metadataJson`, `createdAt`, `updatedAt`, `hlc`, `isLocalDirty`) VALUES " +
                "('e-v13', 'article', 'Existing Article', '[]', 'https://example.com/a', '{}', " +
                "'2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z', '001', 0), " +
                "('col-v13', 'collection', 'Custom', '[]', NULL, '{}', " +
                "'2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z', '001', 0)"
        )
        conn.execSQL(
            "INSERT INTO `collection_specs` (`collectionId`, `slug`, `role`, `ruleJson`, `color`) " +
                "VALUES ('col-v13', 'custom_list', 'list', NULL, '#3b82f6')"
        )
    }
}

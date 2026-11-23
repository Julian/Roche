@file:Suppress("StringLiteralDuplication")

package com.grayvines.roche.data

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.grayvines.roche.core.coreJson
import com.grayvines.roche.data.catalog.SyncCollectionSpecDto
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.isDynamic
import com.grayvines.roche.data.local.migrateCatalogAndSyncSchemaV8
import com.grayvines.roche.data.local.migrateCatalogSchemaV7
import com.grayvines.roche.data.local.migrateSchemaV11ToV12
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Migration12To13Test {
    @TempDir lateinit var tempDir: File

    @Test
    fun `Room MIGRATION_12_13 drops mode and showInLists normalizes rules and restores revision triggers`() =
        runTest {
            val dbFile = File(tempDir, "roche_v12_to_v13.db")
            BundledSQLiteDriver().open(dbFile.absolutePath).use { conn ->
                createV12Schema(conn)
                seedV12Rows(conn)
            }

            val migratedDb =
                Room.databaseBuilder<RocheDatabase>(name = dbFile.absolutePath)
                    .setDriver(BundledSQLiteDriver())
                    .addCallback(RocheDatabase.schemaCallback())
                    .addMigrations(*RocheDatabase.allMigrations())
                    .build()
            try {
                val dao = migratedDb.dao()
                val dynamicSpec = dao.getCollectionSpecById("col-dyn")!!
                assertTrue(dynamicSpec.isDynamic)
                assertEquals("""{"kind":"book","consumed":true}""", dynamicSpec.ruleJson)
                assertEquals("list", dynamicSpec.role)

                val manualSpec = dao.getCollectionSpecById("col-manual")!!
                assertFalse(manualSpec.isDynamic)
                assertNull(manualSpec.ruleJson)
                assertEquals("#3b82f6", manualSpec.color)

                val feedSpec = dao.getCollectionSpecById("col-feed")!!
                assertFalse(feedSpec.isDynamic)
                assertNull(feedSpec.ruleJson)
                assertEquals("feed", feedSpec.role)

                val stampBefore = dao.getRepositoryStamp().specsStamp
                dao.upsertCollectionSpec(
                    CollectionSpecEntity(
                        collectionId = "col-manual",
                        slug = "custom_list",
                        role = "list",
                        color = "#10b981",
                    )
                )
                val stampAfter = dao.getRepositoryStamp().specsStamp
                assertTrue(stampAfter > stampBefore, "collection_specs revision trigger must fire")
            } finally {
                migratedDb.close()
            }

            BundledSQLiteDriver().open(dbFile.absolutePath).use { conn ->
                val cols = mutableListOf<String>()
                conn.prepare("PRAGMA table_info(`collection_specs`)").use { stmt ->
                    while (stmt.step()) {
                        cols.add(stmt.getText(1))
                    }
                }
                assertEquals(listOf("collectionId", "slug", "role", "ruleJson", "color"), cols)
            }
        }

    @Test
    fun `SyncCollectionSpecDto ignores legacy mode and show_in_lists fields on decode`() {
        val raw =
            """{"slug":"legacy","mode":"manual","role":"list","show_in_lists":true,"color":"#abcdef"}"""
        val decoded = coreJson.decodeFromString(SyncCollectionSpecDto.serializer(), raw)
        assertEquals("legacy", decoded.slug)
        assertEquals("list", decoded.role)
        assertNull(decoded.ruleJson)
        assertEquals("#abcdef", decoded.color)
    }

    private suspend fun createV12Schema(conn: SQLiteConnection) {
        createV6LegacyTables(conn)
        migrateCatalogSchemaV7(conn)
        migrateCatalogAndSyncSchemaV8(conn)
        RocheDatabase.MIGRATION_8_9.migrate(conn)
        RocheDatabase.MIGRATION_9_10.migrate(conn)
        RocheDatabase.MIGRATION_10_11.migrate(conn)
        migrateSchemaV11ToV12(conn)
        conn.execSQL("PRAGMA user_version = 12")
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

    private fun seedV12Rows(conn: SQLiteConnection) {
        conn.execSQL(
            "INSERT INTO `entries` (`id`, `kind`, `title`, `creatorsJson`, `metadataJson`, " +
                "`createdAt`, `updatedAt`, `hlc`, `isLocalDirty`) VALUES " +
                "('col-dyn', 'collection', 'Books', '[]', '{}', '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z', '001', 0), " +
                "('col-manual', 'collection', 'Custom', '[]', '{}', '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z', '001', 0), " +
                "('col-feed', 'publication', 'Blog', '[]', '{}', '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z', '001', 0)"
        )
        val dynRule = """ {"kind":"book","consumed":true} """
        conn.execSQL(
            "INSERT INTO `collection_specs` " +
                "(`collectionId`, `slug`, `mode`, `role`, `ruleJson`, `showInLists`, `color`) VALUES " +
                "('col-dyn', 'books', 'dynamic', 'list', '$dynRule', 1, NULL), " +
                "('col-manual', 'custom_list', 'manual', 'list', 'stray-rule', 1, '#3b82f6'), " +
                "('col-feed', NULL, 'feed', 'feed', NULL, 0, NULL)"
        )
    }
}

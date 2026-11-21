package com.grayvines.roche.data

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.grayvines.roche.data.catalog.SyncCollectionEntryDto
import com.grayvines.roche.data.catalog.SyncComparisonDto
import com.grayvines.roche.data.catalog.SyncConsumptionLogDto
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.migrateCatalogAndSyncSchemaV8
import com.grayvines.roche.data.local.migrateCatalogSchemaV7
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.sync.reconcilePeerSyncResponse
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Migration11To12Test {
    @TempDir lateinit var tempDir: File

    @Test
    fun `Room MIGRATION_11_12 cleans orphans deduplicates slugs enforces cascades and tracks table revisions`() =
        runTest {
            val dbFile = File(tempDir, "roche_v11_dirty_to_v12.db")
            BundledSQLiteDriver().open(dbFile.absolutePath).use { conn ->
                createV11Schema(conn)
                seedDirtyV11Rows(conn)
            }

            val migratedDb =
                Room.databaseBuilder<RocheDatabase>(name = dbFile.absolutePath)
                    .setDriver(BundledSQLiteDriver())
                    .addCallback(RocheDatabase.schemaCallback())
                    .addMigrations(*RocheDatabase.allMigrations())
                    .build()
            try {
                val dao = migratedDb.dao()
                assertOrphansCleanedAndSlugsDeduped(dao)
                assertRevisionsAndCascades(dao)
            } finally {
                migratedDb.close()
            }

            // Direct SQL verifies UNIQUE(slug) constraint rejects duplicate non-null slugs
            BundledSQLiteDriver().open(dbFile.absolutePath).use { conn ->
                assertThrows(Exception::class.java) {
                    conn.execSQL(
                        "UPDATE `collection_specs` SET `slug` = 'favorites' " +
                            "WHERE `collectionId` = 'col-blank'"
                    )
                }
            }
        }

    @Test
    fun `PeerSyncIn ignores partial-page edges referencing missing entries without FK failure`() =
        runTest {
            val db = RocheDatabase.createInMemory()
            try {
                val dao = db.dao()
                dao.upsertEntry(
                    EntryEntity(
                        id = "e-present",
                        kind = "book",
                        title = "Dune",
                        createdAt = "2026-06-01T00:00:00Z",
                        updatedAt = "2026-06-01T00:00:00Z",
                        hlc = "001",
                    )
                )
                reconcilePeerSyncResponse(dao = dao, response = partialPageMissingParentResponse())
                assertNotNull(dao.getEntryById("e-present"))
                assertTrue(dao.getAllCollectionEntries().isEmpty())
                assertTrue(dao.getAllConsumptionLogs().isEmpty())
                assertTrue(dao.getAllComparisons().isEmpty())
            } finally {
                db.close()
            }
        }

    private suspend fun assertOrphansCleanedAndSlugsDeduped(dao: RocheDao) {
        val isbnOwners =
            dao.getAllEntryIdentifiers().filter { it.scheme == "isbn" }.map { it.entryId }
        assertEquals(listOf("e-book"), isbnOwners)
        assertEquals(listOf("f-valid"), dao.getAllFeedSources().map { it.collectionId })
        val customSpecIds =
            dao.getAllCollectionSpecs()
                .map { it.collectionId }
                .filter { it.startsWith("col-") }
                .toSet()
        assertEquals(setOf("col-keep", "col-dup", "col-blank"), customSpecIds)
        assertEquals(
            listOf("col-keep" to "e-book"),
            dao.getAllCollectionEntries().map { it.collectionId to it.entryId },
        )
        assertEquals(listOf("col-keep"), dao.getAllCollectionGenerators().map { it.collectionId })
        assertEquals(listOf("log-ok"), dao.getAllConsumptionLogs().map { it.id })
        val comparisons = dao.getAllComparisons().associateBy { it.id }
        assertEquals(setOf("cmp-ok"), comparisons.keys)
        assertEquals("col-keep", comparisons.getValue("cmp-ok").scopeCollectionId)
        // Among equal timestamps, lexicographically smallest collectionId ('col-dup') keeps slug
        assertEquals("favorites", dao.getCollectionSpecById("col-dup")?.slug)
        assertNull(dao.getCollectionSpecById("col-keep")?.slug)
        assertNull(dao.getCollectionSpecById("col-blank")?.slug)
    }

    private suspend fun assertRevisionsAndCascades(dao: RocheDao) {
        val stampBefore = dao.getRepositoryStamp()
        dao.upsertEntry(
            EntryEntity(
                id = "e-new",
                kind = "movie",
                title = "Mirror",
                createdAt = "2026-06-02T00:00:00Z",
                updatedAt = "2026-06-02T00:00:00Z",
                hlc = "002",
            )
        )
        val stampAfterEntry = dao.getRepositoryStamp()
        assertTrue(stampAfterEntry.entriesStamp > stampBefore.entriesStamp)
        assertEquals(stampBefore.logsStamp, stampAfterEntry.logsStamp)

        dao.deleteRawEntryById("e-book")
        assertTrue(dao.getAllEntryIdentifiers().none { it.entryId == "e-book" })
        assertTrue(dao.getAllConsumptionLogs().isEmpty())
        assertTrue(dao.getAllCollectionEntries().isEmpty())
        val stampAfterCascade = dao.getRepositoryStamp()
        assertTrue(stampAfterCascade.identsStamp > stampAfterEntry.identsStamp)
        assertTrue(stampAfterCascade.logsStamp > stampAfterEntry.logsStamp)
        assertTrue(stampAfterCascade.membersStamp > stampAfterEntry.membersStamp)
    }

    private fun partialPageMissingParentResponse(): PeerSyncResponseDto =
        PeerSyncResponseDto(
            serverPeerId = "server",
            syncSeq = 1,
            serverHlc = "001780000001000-0000-server",
            syncTimestamp = "2026-06-01T00:00:00Z",
            collectionEntries =
                listOf(
                    SyncCollectionEntryDto(
                        collectionId = "missing-col",
                        entryId = "e-present",
                        addedAt = "2026-06-01T00:00:00Z",
                        hlc = "001780000001000-0000-server",
                    ),
                    SyncCollectionEntryDto(
                        collectionId = "e-present",
                        entryId = "missing-child",
                        addedAt = "2026-06-01T00:00:00Z",
                        hlc = "001780000001000-0000-server",
                    ),
                ),
            consumptionLog =
                listOf(
                    SyncConsumptionLogDto(
                        id = "log-missing",
                        entryId = "missing-entry",
                        ts = "2026-06-01T00:00:00Z",
                        status = "consumed",
                        hlc = "001780000001000-0000-server",
                    )
                ),
            comparisons =
                listOf(
                    SyncComparisonDto(
                        id = "cmp-missing-scope",
                        ts = "2026-06-01T00:00:00Z",
                        scopeCollectionId = "missing-scope",
                        ordering = listOf(listOf("e-present"), listOf("other-id")),
                        hlc = "001780000001000-0000-server",
                    )
                ),
        )

    private suspend fun createV11Schema(conn: SQLiteConnection) {
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
                "FOREIGN KEY(`itemId`) REFERENCES `items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
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
        migrateCatalogSchemaV7(conn)
        migrateCatalogAndSyncSchemaV8(conn)
        RocheDatabase.MIGRATION_8_9.migrate(conn)
        RocheDatabase.MIGRATION_9_10.migrate(conn)
        RocheDatabase.MIGRATION_10_11.migrate(conn)
    }

    private fun seedDirtyV11Rows(conn: SQLiteConnection) {
        conn.execSQL(
            "INSERT INTO `entries` (`id`, `kind`, `title`, `creatorsJson`, `url`, " +
                "`metadataJson`, `createdAt`, `updatedAt`, `hlc`, `isLocalDirty`) VALUES " +
                "('e-book', 'book', 'Solaris', '[]', NULL, '{}', '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z', '001', 0), " +
                "('f-valid', 'publication', 'Feed', '[]', 'https://ex.com', '{}', '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z', '001', 0), " +
                "('col-keep', 'collection', 'Favs', '[]', NULL, '{}', '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z', '001', 0), " +
                "('col-dup', 'collection', 'Dup', '[]', NULL, '{}', '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z', '001', 0), " +
                "('col-blank', 'collection', 'Blank', '[]', NULL, '{}', '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z', '001', 0)"
        )
        conn.execSQL(
            "INSERT INTO `entry_identifiers` (`scheme`, `value`, `entryId`) VALUES " +
                "('isbn', '123', 'e-book'), ('isbn', '999', 'orphan-entry')"
        )
        conn.execSQL(
            "INSERT INTO `feed_sources` (`collectionId`, `feedUrl`, `sourceType`, `feedTitle`, " +
                "`pollInterval`, `isActive`, `consecutiveFailures`, `autoDownloadEpisodes`, `hlc`, `isLocalDirty`) VALUES " +
                "('f-valid', 'https://ex.com/rss', 'rss', 'Feed', 30, 1, 0, 0, '001', 0), " +
                "('f-orphan', 'https://bad.com/rss', 'rss', 'Orphan', 30, 1, 0, 0, '001', 0)"
        )
        conn.execSQL(
            "INSERT INTO `collection_specs` (`collectionId`, `slug`, `mode`, `role`, `showInLists`) VALUES " +
                "('col-keep', 'favorites', 'explicit', 'list', 1), " +
                "('col-dup', 'favorites', 'explicit', 'list', 1), " +
                "('col-blank', '   ', 'explicit', 'list', 1), " +
                "('col-orphan', 'orphan-slug', 'explicit', 'list', 1)"
        )
        conn.execSQL(
            "INSERT INTO `collection_entries` (`collectionId`, `entryId`, `addedAt`, `hlc`, `isLocalDirty`) VALUES " +
                "('col-keep', 'e-book', '2026-06-01T00:00:00Z', '001', 0), " +
                "('col-orphan', 'e-book', '2026-06-01T00:00:00Z', '001', 0), " +
                "('col-keep', 'orphan-entry', '2026-06-01T00:00:00Z', '001', 0)"
        )
        conn.execSQL(
            "INSERT INTO `collection_generators` (`collectionId`, `enabled`, `currentEntryId`, `updatedAt`, `hlc`, `isLocalDirty`) VALUES " +
                "('col-keep', 1, 'e-book', '2026-06-01T00:00:00Z', '001', 0), " +
                "('col-orphan', 1, 'e-book', '2026-06-01T00:00:00Z', '001', 0)"
        )
        conn.execSQL(
            "INSERT INTO `consumption_log` (`id`, `entryId`, `ts`, `status`, `createdAt`, `hlc`, `isLocalDirty`) VALUES " +
                "('log-ok', 'e-book', '2026-06-01T00:00:00Z', 'consumed', '2026-06-01T00:00:00Z', '001', 0), " +
                "('log-orphan', 'orphan-entry', '2026-06-01T00:00:00Z', 'consumed', '2026-06-01T00:00:00Z', '001', 0)"
        )
        val ordering = """[["e-book"],["other"]]"""
        conn.execSQL(
            "INSERT INTO `comparisons` (`id`, `ts`, `scopeCollectionId`, `orderingJson`, `createdAt`, `hlc`, `isLocalDirty`) VALUES " +
                "('cmp-ok', '2026-06-01T00:00:00Z', 'col-keep', '$ordering', '2026-06-01T00:00:00Z', '001', 0), " +
                "('cmp-orphan-scope', '2026-06-01T00:00:00Z', 'col-orphan', '$ordering', '2026-06-01T00:00:00Z', '001', 0)"
        )
        conn.execSQL("PRAGMA user_version = 11")
    }
}

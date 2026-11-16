@file:Suppress("TooManyFunctions", "LongMethod", "MagicNumber", "LargeClass")

package com.grayvines.roche.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.grayvines.roche.data.local.FeedEntity
import com.grayvines.roche.data.local.ItemEntity
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.remote.RemoteFeedDto
import com.grayvines.roche.data.remote.RemoteItemDto
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.remote.buildPeerSyncRequestPayload
import com.grayvines.roche.data.remote.parsePeerSyncResponseJson
import com.grayvines.roche.data.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RocheCatalogAndSyncTest {
    @TempDir lateinit var tempDir: File

    private lateinit var testEnv: TestRocheEnv
    private val database: RocheDatabase
        get() = testEnv.database

    private val settings: SettingsRepository
        get() = testEnv.settings

    private val repository: RocheRepository
        get() = testEnv.repository

    @BeforeEach
    fun setUp() {
        testEnv = TestRocheEnv(tempDir, "catalog_test.preferences_pb")
    }

    @AfterEach
    fun tearDown() {
        testEnv.close()
    }

    @Test
    fun `Room MIGRATION_6_7 upgrades existing v6 database without losing feeds or items`() =
        runTest {
            val dbFile = File(tempDir, "roche_v6_upgrade.db")
            val driver = BundledSQLiteDriver()
            driver.open(dbFile.absolutePath).use { conn ->
                conn.execSQL(
                    "CREATE TABLE IF NOT EXISTS `feeds` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `url` TEXT NOT NULL, `siteUrl` TEXT, `feedType` TEXT NOT NULL, `description` TEXT, `language` TEXT, `pollInterval` INTEGER NOT NULL, `lastPolled` TEXT, `lastPolledBy` TEXT, `lastError` TEXT, `lastErrorBy` TEXT, `isActive` INTEGER NOT NULL, `autoDownloadEpisodes` INTEGER NOT NULL, `customPlaybackSpeed` REAL, PRIMARY KEY(`id`))"
                )
                conn.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_feeds_url` ON `feeds` (`url`)"
                )
                conn.execSQL(
                    "CREATE TABLE IF NOT EXISTS `items` (`id` TEXT NOT NULL, `feedId` TEXT, `feedTitle` TEXT NOT NULL, `title` TEXT NOT NULL, `url` TEXT NOT NULL, `author` TEXT, `summary` TEXT, `contentText` TEXT, `contentHtml` TEXT, `contentType` TEXT NOT NULL, `status` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL, `isCachedOffline` INTEGER NOT NULL, `publishedAt` TEXT, `createdAt` TEXT NOT NULL, `readProgress` REAL NOT NULL, `thumbnailMediaId` TEXT, `duration` TEXT, `readAt` TEXT, `thumbnailUrl` TEXT, `feedUrl` TEXT, `interactionUpdatedAt` TEXT, `audioUrl` TEXT, PRIMARY KEY(`id`))"
                )
                conn.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_items_feedId` ON `items` (`feedId`)"
                )
                conn.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_items_status` ON `items` (`status`)"
                )
                conn.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_items_contentType` ON `items` (`contentType`)"
                )
                conn.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_items_isFavorite` ON `items` (`isFavorite`)"
                )
                conn.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_items_publishedAt_createdAt` ON `items` (`publishedAt`, `createdAt`)"
                )
                conn.execSQL(
                    "CREATE TABLE IF NOT EXISTS `item_contents` (`itemId` TEXT NOT NULL, `summary` TEXT, `contentText` TEXT, `contentHtml` TEXT, PRIMARY KEY(`itemId`), FOREIGN KEY(`itemId`) REFERENCES `items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                conn.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tags` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `color` TEXT, PRIMARY KEY(`id`))"
                )
                conn.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` ON `tags` (`name`)"
                )
                conn.execSQL(
                    "CREATE TABLE IF NOT EXISTS `item_tags` (`itemId` TEXT NOT NULL, `tagId` TEXT NOT NULL, PRIMARY KEY(`itemId`, `tagId`))"
                )
                conn.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_item_tags_itemId` ON `item_tags` (`itemId`)"
                )
                conn.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_item_tags_tagId` ON `item_tags` (`tagId`)"
                )
                conn.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_mutations` (`entityId` TEXT NOT NULL, `isLocalFeed` INTEGER NOT NULL, `isLocalItem` INTEGER NOT NULL, `titleOverride` TEXT, `dirtyStatus` TEXT, `dirtyFavorite` INTEGER, `dirtyReadProgress` REAL, `dirtyReadAt` TEXT, `addedTagsCsv` TEXT NOT NULL, `removedTagsCsv` TEXT NOT NULL, PRIMARY KEY(`entityId`))"
                )
                conn.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_tombstones` (`entityType` TEXT NOT NULL, `naturalKey` TEXT NOT NULL, `deletedAt` TEXT NOT NULL, PRIMARY KEY(`entityType`, `naturalKey`))"
                )
                conn.execSQL(
                    "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)"
                )
                conn.execSQL(
                    "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'e91cefce5542eb98a929a68d65fe9af3')"
                )
                conn.execSQL(
                    "INSERT INTO `feeds` (`id`, `title`, `url`, `feedType`, `pollInterval`, `isActive`, `autoDownloadEpisodes`) VALUES ('f-1', 'Existing Blog', 'https://blog.example.com/rss', 'rss', 30, 1, 0)"
                )
                conn.execSQL("PRAGMA user_version = 6")
            }

            val migratedDb =
                Room.databaseBuilder<RocheDatabase>(name = dbFile.absolutePath)
                    .setDriver(BundledSQLiteDriver())
                    .addMigrations(
                        RocheDatabase.MIGRATION_2_3,
                        RocheDatabase.MIGRATION_3_4,
                        RocheDatabase.MIGRATION_4_5,
                        RocheDatabase.MIGRATION_5_6,
                        RocheDatabase.MIGRATION_6_7,
                        RocheDatabase.MIGRATION_7_8,
                        RocheDatabase.MIGRATION_8_9,
                    )
                    .build()
            try {
                assertEquals(1, migratedDb.dao().getAllFeeds().size)
                assertEquals(0, migratedDb.dao().getAllCatalogEntries().size)
            } finally {
                migratedDb.close()
            }
        }

    @Test
    fun `collection modes reject manual edits on dynamic lists and isolate want priority from global quality`() =
        runTest {
            val catalog = repository.catalog
            catalog.ensureSystemSeeded()

            val movieA =
                catalog.logEntry(
                    UpsertCatalogEntryInput(
                        kind = EntryKind.MOVIE,
                        title = "Solaris",
                        creators = listOf("Andrei Tarkovsky"),
                        releaseYear = 1972,
                    ),
                    ConsumptionStatus.CONSUMED,
                    "Hypnotic",
                )
            val movieB =
                catalog.logEntry(
                    UpsertCatalogEntryInput(
                        kind = EntryKind.MOVIE,
                        title = "Stalker",
                        creators = listOf("Andrei Tarkovsky"),
                        releaseYear = 1979,
                    ),
                    ConsumptionStatus.CONSUMED,
                    null,
                )

            // Dynamic list `movies` rejects manual add/remove
            val moviesSpec = database.dao().getCollectionSpecBySlug("movies")!!
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { catalog.addCollectionEntry(moviesSpec.collectionId, movieA) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { catalog.removeCollectionEntry(moviesSpec.collectionId, movieA) }
            }

            // Global quality comparison ranks Stalker > Solaris
            catalog.recordComparison(listOf(listOf(movieB), listOf(movieA)), null)
            val snap1 = repository.snapshot.first().catalog
            val rankedMovies = snap1.entriesByCollectionId[moviesSpec.collectionId].orEmpty()
            assertEquals(listOf(movieB, movieA), rankedMovies.map { it.entry.id })

            // Want queue priority comparison is strictly local and never bleeds into global quality
            val wantC =
                catalog.wantEntry(
                    UpsertCatalogEntryInput(
                        kind = EntryKind.MOVIE,
                        title = "Mirror",
                        creators = listOf("Andrei Tarkovsky"),
                        releaseYear = 1975,
                    )
                )
            val wantD =
                catalog.wantEntry(
                    UpsertCatalogEntryInput(
                        kind = EntryKind.MOVIE,
                        title = "Nostalghia",
                        creators = listOf("Andrei Tarkovsky"),
                        releaseYear = 1983,
                    )
                )
            val wantMoviesSpec = database.dao().getCollectionSpecBySlug("want:movies")!!
            catalog.recordComparison(
                ordering = listOf(listOf(wantD), listOf(wantC)),
                scopeCollectionId = wantMoviesSpec.collectionId,
            )
            val snap2 = repository.snapshot.first().catalog
            val wantRanked = snap2.entriesByCollectionId[wantMoviesSpec.collectionId].orEmpty()
            assertEquals(listOf(wantD, wantC), wantRanked.map { it.entry.id })
            assertTrue(wantRanked.all { it.tier == null })

            // Completing a Want item promotes it out of Want Queue into global Movies list
            catalog.logExistingEntry(wantD, ConsumptionStatus.CONSUMED, "Watched tonight")
            val snap3 = repository.snapshot.first().catalog
            assertEquals(
                listOf(wantC),
                snap3.entriesByCollectionId[wantMoviesSpec.collectionId].orEmpty().map {
                    it.entry.id
                },
            )
            assertTrue(
                snap3.entriesByCollectionId[moviesSpec.collectionId].orEmpty().any {
                    it.entry.id == wantD
                }
            )
        }

    @Test
    fun `feed completion auto-logs podcast episodes read articles papers and explicit videos while ignoring unread swipes and bulk archive`() =
        runTest {
            val dao = database.dao()
            dao.upsertFeeds(
                listOf(
                    FeedEntity(
                        id = "feed-pod",
                        title = "Systems Radio",
                        url = "https://example.com/podcast.xml",
                        siteUrl = "https://example.com",
                        feedType = "podcast",
                    ),
                    FeedEntity(
                        id = "feed-blog",
                        title = "Compiler Notes",
                        url = "https://example.com/blog.xml",
                        siteUrl = "https://example.com/blog",
                        feedType = "rss",
                    ),
                    FeedEntity(
                        id = "feed-yt",
                        title = "Graphics Lab",
                        url = "https://www.youtube.com/feeds/videos.xml?channel_id=UC123",
                        siteUrl = "https://www.youtube.com",
                        feedType = "youtube",
                    ),
                )
            )
            dao.upsertItems(
                listOf(
                    ItemEntity(
                        id = "ep-1",
                        feedId = "feed-pod",
                        feedTitle = "Systems Radio",
                        title = "Episode 42: WAL Internals",
                        url = "https://example.com/ep42",
                        author = "Ada",
                        summary = null,
                        contentText = null,
                        contentHtml = null,
                        contentType = "audio",
                        status = "unread",
                        isFavorite = false,
                        publishedAt = "2026-05-01T10:00:00Z",
                        createdAt = "2026-05-01T10:00:00Z",
                        readProgress = 0.0,
                        thumbnailMediaId = null,
                        duration = "1800",
                        isCachedOffline = false,
                        audioUrl = "https://example.com/ep42.mp3",
                    ),
                    ItemEntity(
                        id = "art-read",
                        feedId = "feed-blog",
                        feedTitle = "Compiler Notes",
                        title = "SSA Construction",
                        url = "https://example.com/ssa",
                        author = "Grace",
                        summary = null,
                        contentText = "Body",
                        contentHtml = "<p>Body</p>",
                        contentType = "article",
                        status = "unread",
                        isFavorite = false,
                        publishedAt = "2026-05-02T10:00:00Z",
                        createdAt = "2026-05-02T10:00:00Z",
                        readProgress = 0.0,
                        thumbnailMediaId = null,
                        duration = null,
                        isCachedOffline = false,
                    ),
                    ItemEntity(
                        id = "art-skimmed",
                        feedId = "feed-blog",
                        feedTitle = "Compiler Notes",
                        title = "Unread Skim",
                        url = "https://example.com/skim",
                        author = null,
                        summary = null,
                        contentText = "Body",
                        contentHtml = "<p>Body</p>",
                        contentType = "article",
                        status = "unread",
                        isFavorite = false,
                        publishedAt = "2026-05-03T10:00:00Z",
                        createdAt = "2026-05-03T10:00:00Z",
                        readProgress = 0.25,
                        thumbnailMediaId = null,
                        duration = null,
                        isCachedOffline = false,
                    ),
                    ItemEntity(
                        id = "art-bulk",
                        feedId = "feed-blog",
                        feedTitle = "Compiler Notes",
                        title = "Bulk Archived Unread",
                        url = "https://example.com/bulk",
                        author = null,
                        summary = null,
                        contentText = "Body",
                        contentHtml = "<p>Body</p>",
                        contentType = "article",
                        status = "unread",
                        isFavorite = false,
                        publishedAt = "2026-05-04T10:00:00Z",
                        createdAt = "2026-05-04T10:00:00Z",
                        readProgress = 0.95,
                        thumbnailMediaId = null,
                        duration = null,
                        isCachedOffline = false,
                    ),
                    ItemEntity(
                        id = "vid-1",
                        feedId = "feed-yt",
                        feedTitle = "Graphics Lab",
                        title = "Path Tracing Talk",
                        url = "https://www.youtube.com/watch?v=abc",
                        author = null,
                        summary = null,
                        contentText = null,
                        contentHtml = null,
                        contentType = "video",
                        status = "unread",
                        isFavorite = false,
                        publishedAt = "2026-05-05T10:00:00Z",
                        createdAt = "2026-05-05T10:00:00Z",
                        readProgress = 1.0,
                        thumbnailMediaId = null,
                        duration = null,
                        isCachedOffline = false,
                    ),
                )
            )

            // 1. Podcast playback reaching >= 0.98 auto-logs episode + parent podcast
            repository.updateReadProgress("ep-1", 0.99)
            val loggedAfterPod = dao.getAllConsumptionLogs().map { it.entryId }.toSet()
            assertTrue("ep-1" in loggedAfterPod)
            assertTrue("feed-pod" in loggedAfterPod)

            // 2. Article read to >= 0.9 + archived auto-logs article + parent publication
            repository.updateReadProgress("art-read", 0.92)
            assertFalse(dao.getAllConsumptionLogs().any { it.entryId == "art-read" })
            repository.setArchiveStateWithSnapshot("art-read", archived = true)
            val loggedAfterArt = dao.getAllConsumptionLogs().map { it.entryId }.toSet()
            assertTrue("art-read" in loggedAfterArt)
            assertTrue("feed-blog" in loggedAfterArt)

            // 3. Swiping/archiving at < 0.9 and bulk-archiving NEVER log consumption
            repository.setArchiveStateWithSnapshot("art-skimmed", archived = true)
            repository.archiveBatchWithSnapshots(entryIds = listOf("art-bulk"))
            val loggedAfterBulk = dao.getAllConsumptionLogs().map { it.entryId }.toSet()
            assertFalse("art-skimmed" in loggedAfterBulk)
            assertFalse("art-bulk" in loggedAfterBulk)

            // 4. Video archiving NEVER auto-logs; explicit markFeedItemFinished logs video +
            // channel
            repository.setArchiveStateWithSnapshot("vid-1", archived = true)
            assertFalse(dao.getAllConsumptionLogs().any { it.entryId == "vid-1" })
            repository.markFeedItemFinished("vid-1")
            val loggedAfterVid = dao.getAllConsumptionLogs().map { it.entryId }.toSet()
            assertTrue("vid-1" in loggedAfterVid)
            assertTrue("feed-yt" in loggedAfterVid)

            // 5. Deleting an inbox item or feed NEVER erases consumed history
            repository.deleteItem("art-read")
            repository.deleteFeed("feed-pod")
            val survivingLogs = dao.getAllConsumptionLogs().map { it.entryId }.toSet()
            assertTrue("art-read" in survivingLogs)
            assertTrue("ep-1" in survivingLogs)
            assertTrue("feed-pod" in survivingLogs)
        }

    @Test
    fun `peer sync wire codec and reconciler round-trip catalog collections logs comparisons and remote completions`() =
        runTest {
            val dao = database.dao()
            repository.catalog.ensureSystemSeeded()

            val customListId = repository.catalog.createCustomList("Sci-Fi Essentials")
            val bookA =
                repository.catalog.logEntry(
                    UpsertCatalogEntryInput(
                        kind = EntryKind.BOOK,
                        title = "Book of the New Sun",
                        creators = listOf("Gene Wolfe"),
                        releaseYear = 1980,
                    ),
                    ConsumptionStatus.CONSUMED,
                    "Masterwork",
                )
            val bookB =
                repository.catalog.logEntry(
                    UpsertCatalogEntryInput(
                        kind = EntryKind.BOOK,
                        title = "Dune",
                        creators = listOf("Frank Herbert"),
                        releaseYear = 1965,
                    ),
                    ConsumptionStatus.CONSUMED,
                    null,
                )
            repository.catalog.addCollectionEntry(customListId, bookA)
            repository.catalog.recordComparison(listOf(listOf(bookA), listOf(bookB)), null)

            val built =
                buildTrackedPeerSyncRequest(
                    dao = dao,
                    config = PeerSyncRequestConfig(peerId = "peer-android", deviceName = "Pixel"),
                )
            val wireJson = buildPeerSyncRequestPayload(built.request)
            assertTrue(wireJson.contains("Sci-Fi Essentials"))
            assertTrue(wireJson.contains("\"show_in_lists\":true"))
            assertTrue(wireJson.contains("Book of the New Sun"))

            // Simulate a second fresh peer receiving that catalog state + a server-completed
            // article
            val peerDb =
                Room.inMemoryDatabaseBuilder<RocheDatabase>()
                    .setDriver(BundledSQLiteDriver())
                    .build()
            try {
                val peerCatalog = CatalogStore(peerDb.dao())
                val simulatedServerResponse =
                    PeerSyncResponseDto(
                        serverPeerId = "server-1",
                        syncTimestamp = "2026-06-01T12:00:00Z",
                        activePeers = emptyList(),
                        feeds =
                            listOf(
                                RemoteFeedDto(
                                    id = "remote-feed-1",
                                    title = "Server Essayists",
                                    url = "https://essays.example.com/rss",
                                    siteUrl = "https://essays.example.com",
                                    feedType = "rss",
                                    unreadCount = 0,
                                )
                            ),
                        items =
                            listOf(
                                RemoteItemDto(
                                    id = "remote-art-1",
                                    feedId = "remote-feed-1",
                                    feedTitle = "Server Essayists",
                                    title = "Finished On Web Client",
                                    url = "https://essays.example.com/1",
                                    author = "Montaigne",
                                    summary = null,
                                    contentText = "Essay",
                                    contentHtml = "<p>Essay</p>",
                                    contentType = "article",
                                    status = "archived",
                                    isFavorite = false,
                                    publishedAt = "2026-06-01T10:00:00Z",
                                    createdAt = "2026-06-01T10:00:00Z",
                                    readProgress = 1.0,
                                    thumbnailMediaId = null,
                                    duration = null,
                                )
                            ),
                        tags = emptyList(),
                        tombstones = emptyList(),
                        collections = built.request.collections,
                        catalogEntries = built.request.catalogEntries,
                        collectionEntries = built.request.collectionEntries,
                        collectionGenerators = built.request.collectionGenerators,
                        consumptionLog =
                            built.request.consumptionLog +
                                PeerConsumptionLogPayload(
                                    id = "remote-log-1",
                                    entryId = "remote-art-1",
                                    ts = "2026-06-01T11:30:00Z",
                                    status = "consumed",
                                ),
                        comparisons = built.request.comparisons,
                    )

                // Verify response JSON codec round-trips catalog fields too
                val respRaw =
                    """{"server_peer_id":"server-1","sync_timestamp":"2026-06-01T12:00:00Z",""" +
                        """"active_peers":[],"feeds":[],"items":[],"tags":[],"tombstones":[],""" +
                        """"comparisons":[{"id":"c1","ts":"2026-06-01T11:00:00Z",""" +
                        """"ordering":[["$bookA"],["$bookB"]]}]}"""
                val parsedResp = parsePeerSyncResponseJson(respRaw)
                assertEquals(1, parsedResp.comparisons.size)

                reconcilePeerSyncResponse(
                    dao = peerDb.dao(),
                    response = simulatedServerResponse,
                    input =
                        PeerSyncReconcileInput(
                            preloaded = PeerSyncPreloadedState(emptyList(), emptyList()),
                            catalogStore = peerCatalog,
                        ),
                )

                val peerSnap = peerCatalog.snapshot.first()
                assertTrue(peerSnap.lists.any { it.title == "Sci-Fi Essentials" })
                val booksSpec = peerSnap.lists.first { it.slug == "books" }
                assertEquals(
                    listOf(bookA, bookB),
                    peerSnap.entriesByCollectionId[booksSpec.collectionId].orEmpty().map {
                        it.entry.id
                    },
                )
                val articlesSpec = peerSnap.lists.first { it.slug == "articles" }
                assertTrue(
                    peerSnap.entriesByCollectionId[articlesSpec.collectionId].orEmpty().any {
                        it.entry.id == "remote-art-1"
                    }
                )
            } finally {
                peerDb.close()
            }
        }

    @Test
    fun `generator-enabled manual list excludes unconsumed pool items and diary excludes container kinds`() =
        runTest {
            val catalog = repository.catalog
            val listId = catalog.createCustomList("Essential Jazz")
            val albA =
                catalog.upsertEntry(
                    UpsertCatalogEntryInput(
                        kind = EntryKind.ALBUM,
                        title = "Kind of Blue",
                        creators = listOf("Miles Davis"),
                        releaseYear = 1959,
                    )
                )
            val albB =
                catalog.upsertEntry(
                    UpsertCatalogEntryInput(
                        kind = EntryKind.ALBUM,
                        title = "A Love Supreme",
                        creators = listOf("John Coltrane"),
                        releaseYear = 1965,
                    )
                )
            catalog.addCollectionEntry(listId, albA, position = 1.0)
            catalog.addCollectionEntry(listId, albB, position = 2.0)
            catalog.setCollectionGeneratorEnabled(listId, true)

            val initialSnap = catalog.snapshot.first()
            val listBefore = initialSnap.lists.first { it.collectionId == listId }
            assertEquals(0, listBefore.entryCount)
            assertEquals(2, listBefore.poolTotalCount)
            assertEquals(2, listBefore.poolRemainingCount)
            assertTrue(catalog.selectNextRankCluster(listId).isEmpty())

            val finished1 = catalog.finishActiveDraw(listId, "First listen")
            val afterOneSnap = catalog.snapshot.first()
            val listAfterOne = afterOneSnap.lists.first { it.collectionId == listId }
            assertEquals(1, listAfterOne.entryCount)
            assertEquals(
                listOf(finished1.entryId),
                afterOneSnap.entriesByCollectionId[listId].orEmpty().map { it.entry.id },
            )
            // Synthesized ARTIST row is present in dynamic `artists` list, NOT in Diary or
            // consumedCount
            val artistsList = afterOneSnap.lists.first { it.slug == "artists" }
            assertTrue(artistsList.entryCount >= 1)
            assertTrue(afterOneSnap.diaryEntries.none { it.kind == EntryKind.ARTIST })
            assertEquals(1, afterOneSnap.consumedCount)
        }

    @Test
    fun `incoming mergedIntoId folds local consumption logs memberships comparisons and active draw`() =
        runTest {
            val catalog = repository.catalog
            val dupId =
                catalog.logEntry(
                    UpsertCatalogEntryInput(
                        id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                        kind = EntryKind.MOVIE,
                        title = "Blade Runner (US Theatrical)",
                        creators = listOf("Ridley Scott"),
                        releaseYear = 1982,
                    ),
                    ConsumptionStatus.CONSUMED,
                    "Noir atmosphere",
                )
            val canonId =
                catalog.upsertEntry(
                    UpsertCatalogEntryInput(
                        id = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                        kind = EntryKind.MOVIE,
                        title = "Blade Runner",
                        creators = listOf("Ridley Scott"),
                        releaseYear = 1982,
                    )
                )
            val otherId =
                catalog.logEntry(
                    UpsertCatalogEntryInput(
                        id = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                        kind = EntryKind.MOVIE,
                        title = "Alien",
                        creators = listOf("Ridley Scott"),
                        releaseYear = 1979,
                    ),
                    ConsumptionStatus.CONSUMED,
                    null,
                )
            val customListId = catalog.createCustomList("Cyberpunk")
            catalog.addCollectionEntry(customListId, dupId)
            catalog.recordComparison(listOf(listOf(dupId), listOf(otherId)))

            val mergeResponse =
                PeerSyncResponseDto(
                    serverPeerId = "server-1",
                    syncTimestamp = "2026-07-01T12:00:00Z",
                    activePeers = emptyList(),
                    feeds = emptyList(),
                    items = emptyList(),
                    tags = emptyList(),
                    tombstones = emptyList(),
                    catalogEntries =
                        listOf(
                            PeerCatalogEntryPayload(
                                id = dupId,
                                kind = "movie",
                                title = "Blade Runner (US Theatrical)",
                                creators = listOf("Ridley Scott"),
                                releaseYear = 1982,
                                mergedIntoId = canonId,
                                updatedAt = "2026-07-01T12:00:00Z",
                            )
                        ),
                )
            reconcilePeerSyncResponse(
                dao = database.dao(),
                response = mergeResponse,
                input =
                    PeerSyncReconcileInput(
                        preloaded = PeerSyncPreloadedState(emptyList(), emptyList()),
                        catalogStore = catalog,
                    ),
            )

            val snap = catalog.snapshot.first()
            assertTrue(
                snap.diaryEntries.any { it.entryId == canonId && it.notes == "Noir atmosphere" }
            )
            assertTrue(snap.diaryEntries.none { it.entryId == dupId })
            val customMembers =
                snap.entriesByCollectionId[customListId].orEmpty().map { it.entry.id }
            assertEquals(listOf(canonId), customMembers)
            val moviesSpec = snap.lists.first { it.slug == "movies" }
            val rankedMovies =
                snap.entriesByCollectionId[moviesSpec.collectionId].orEmpty().map { it.entry.id }
            assertEquals(listOf(canonId, otherId), rankedMovies)
        }

    @Test
    fun `Room MIGRATION_7_8 normalizes v7 identifiers backfills article duration and triggers one-time catalog backfill`() =
        runTest {
            val dbFile = File(tempDir, "roche_v7_upgrade.db")
            val driver = BundledSQLiteDriver()
            driver.open(dbFile.absolutePath).use { conn ->
                conn.execSQL(
                    "CREATE TABLE IF NOT EXISTS `feeds` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `url` TEXT NOT NULL, `siteUrl` TEXT, `feedType` TEXT NOT NULL, `description` TEXT, `language` TEXT, `pollInterval` INTEGER NOT NULL, `lastPolled` TEXT, `lastPolledBy` TEXT, `lastError` TEXT, `lastErrorBy` TEXT, `isActive` INTEGER NOT NULL, `autoDownloadEpisodes` INTEGER NOT NULL, `customPlaybackSpeed` REAL, PRIMARY KEY(`id`))"
                )
                conn.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_feeds_url` ON `feeds` (`url`)"
                )
                conn.execSQL(
                    "CREATE TABLE IF NOT EXISTS `items` (`id` TEXT NOT NULL, `feedId` TEXT, `feedTitle` TEXT NOT NULL, `title` TEXT NOT NULL, `url` TEXT NOT NULL, `author` TEXT, `summary` TEXT, `contentText` TEXT, `contentHtml` TEXT, `contentType` TEXT NOT NULL, `status` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL, `isCachedOffline` INTEGER NOT NULL, `publishedAt` TEXT, `createdAt` TEXT NOT NULL, `readProgress` REAL NOT NULL, `thumbnailMediaId` TEXT, `duration` TEXT, `readAt` TEXT, `thumbnailUrl` TEXT, `feedUrl` TEXT, `interactionUpdatedAt` TEXT, `audioUrl` TEXT, PRIMARY KEY(`id`))"
                )
                conn.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_items_feedId` ON `items` (`feedId`)"
                )
                conn.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_items_status` ON `items` (`status`)"
                )
                conn.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_items_contentType` ON `items` (`contentType`)"
                )
                conn.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_items_isFavorite` ON `items` (`isFavorite`)"
                )
                conn.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_items_publishedAt_createdAt` ON `items` (`publishedAt`, `createdAt`)"
                )
                conn.execSQL(
                    "CREATE TABLE IF NOT EXISTS `item_contents` (`itemId` TEXT NOT NULL, `summary` TEXT, `contentText` TEXT, `contentHtml` TEXT, PRIMARY KEY(`itemId`), FOREIGN KEY(`itemId`) REFERENCES `items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                conn.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tags` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `color` TEXT, PRIMARY KEY(`id`))"
                )
                conn.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` ON `tags` (`name`)"
                )
                conn.execSQL(
                    "CREATE TABLE IF NOT EXISTS `item_tags` (`itemId` TEXT NOT NULL, `tagId` TEXT NOT NULL, PRIMARY KEY(`itemId`, `tagId`))"
                )
                conn.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_item_tags_itemId` ON `item_tags` (`itemId`)"
                )
                conn.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_item_tags_tagId` ON `item_tags` (`tagId`)"
                )
                conn.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_mutations` (`entityId` TEXT NOT NULL, `isLocalFeed` INTEGER NOT NULL, `isLocalItem` INTEGER NOT NULL, `titleOverride` TEXT, `dirtyStatus` TEXT, `dirtyFavorite` INTEGER, `dirtyReadProgress` REAL, `dirtyReadAt` TEXT, `addedTagsCsv` TEXT NOT NULL, `removedTagsCsv` TEXT NOT NULL, PRIMARY KEY(`entityId`))"
                )
                conn.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_tombstones` (`entityType` TEXT NOT NULL, `naturalKey` TEXT NOT NULL, `deletedAt` TEXT NOT NULL, PRIMARY KEY(`entityType`, `naturalKey`))"
                )
                conn.execSQL(
                    "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)"
                )
                conn.execSQL(
                    "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'd1c26f2f8fb02af999da276c321c99f0')"
                )
                com.grayvines.roche.data.local.migrateCatalogSchemaV7(conn)
                conn.execSQL(
                    "INSERT INTO `items` (`id`, `feedId`, `feedTitle`, `title`, `url`, `contentType`, `status`, `isFavorite`, `isCachedOffline`, `createdAt`, `readProgress`) VALUES ('art-v7', NULL, 'Saved', 'Long Essay', 'https://example.com/long', 'article', 'unread', 0, 0, '2026-05-01T00:00:00Z', 0.0)"
                )
                val body = "a".repeat(5_400)
                conn.execSQL(
                    "INSERT INTO `item_contents` (`itemId`, `contentText`) VALUES ('art-v7', '$body')"
                )
                conn.execSQL(
                    "INSERT INTO `catalog_entries` (`id`, `kind`, `title`, `creatorsJson`, `metadataJson`, `createdAt`, `updatedAt`, `isLocalDirty`) VALUES ('alb-v7', 'album', 'Dummy', '[]', '{}', '2026-05-01T00:00:00Z', '2026-05-01T00:00:00Z', 0)"
                )
                conn.execSQL(
                    "INSERT INTO `entry_identifiers` (`scheme`, `value`, `entryId`) VALUES ('spotify', '4sW8Eql2e2kdRP1A1R1C9t', 'alb-v7')"
                )
                conn.execSQL(
                    "INSERT INTO `entry_identifiers` (`scheme`, `value`, `entryId`) VALUES ('slug', 'MixedCaseSlug', 'alb-v7')"
                )
                conn.execSQL("PRAGMA user_version = 7")
            }

            val migratedDb =
                Room.databaseBuilder<RocheDatabase>(name = dbFile.absolutePath)
                    .setDriver(BundledSQLiteDriver())
                    .addMigrations(
                        RocheDatabase.MIGRATION_7_8,
                        RocheDatabase.MIGRATION_8_9,
                    )
                    .build()
            try {
                val dao = migratedDb.dao()
                assertEquals("5m", dao.getItemById("art-v7")?.duration)
                val idents =
                    dao.getIdentifiersForEntry("alb-v7").associate { it.scheme to it.value }
                assertEquals("spotify:album:4sW8Eql2e2kdRP1A1R1C9t", idents["spotify"])
                assertEquals("mixedcaseslug", idents["slug"])
                assertEquals(
                    "1",
                    dao.getLocalSyncFlag(
                        com.grayvines.roche.data.local.FLAG_CATALOG_BACKFILL_NEEDED
                    ),
                )

                // First syncWithServer after v7->v8 upgrade omits updated_after once to backfill
                // catalog, then clears flag
                settings.update {
                    it.copy(
                        serverUrl = "https://100.64.0.12:8080",
                        bearerToken = "tok-v8",
                        lastPeerSyncCursor = "2026-06-01T00:00:00Z",
                    )
                }
                val syncBodies = mutableListOf<String>()
                val upgradeClient = RocheApiClient { request ->
                    if (request.method == "POST" && request.url.endsWith("/api/v1/sync/peer")) {
                        syncBodies.add(request.body.orEmpty())
                        HttpResponse(
                            statusCode = 200,
                            body =
                                """{"server_peer_id":"server","sync_timestamp":"2026-06-02T00:00:00Z","active_peers":[],"feeds":[],"items":[],"tags":[],"tombstones":[]}""",
                        )
                    } else {
                        HttpResponse(statusCode = 404, body = "{}")
                    }
                }
                val upgradedRepo =
                    RocheRepository(
                        database = migratedDb,
                        apiClient = upgradeClient,
                        settings = settings,
                    )
                upgradedRepo.syncWithServer().getOrThrow()
                assertFalse(syncBodies.single().contains("updated_after"))
                assertEquals(
                    null,
                    dao.getLocalSyncFlag(
                        com.grayvines.roche.data.local.FLAG_CATALOG_BACKFILL_NEEDED
                    ),
                )
            } finally {
                migratedDb.close()
            }
        }

    @Test
    fun `peer sync deduplicates incoming auto-completion logs preserves concurrent dirty edits and enforces LWW updatedAt`() =
        runTest {
            val dao = database.dao()
            val entryId =
                repository.catalog.logEntry(
                    UpsertCatalogEntryInput(
                        id = "11111111-1111-4111-8111-111111111111",
                        kind = EntryKind.ARTICLE,
                        title = "Fresh Local Title",
                        url = "https://arxiv.org/pdf/2603.11842.pdf",
                    ),
                    ConsumptionStatus.CONSUMED,
                    "Manual note",
                )
            // Verify arXiv identifier was extracted from URL
            assertEquals(
                "11111111-1111-4111-8111-111111111111",
                dao.getEntryIdByIdentifier("arxiv", "2603.11842"),
            )

            val built =
                buildTrackedPeerSyncRequest(
                    dao = dao,
                    config = PeerSyncRequestConfig(peerId = "peer-android", deviceName = "Pixel"),
                )
            // Simulate concurrent user edit while sync request is in flight (newer updatedAt)
            dao.upsertCatalogEntry(
                dao.getCatalogEntryById(entryId)!!.copy(
                    title = "Concurrent Newer Title",
                    updatedAt = "2099-01-01T00:00:00Z",
                    isLocalDirty = true,
                )
            )

            // Server responds with stale updatedAt on entry AND a deterministic auto-completion log
            // for the same entry (which already has a manual consumed log!)
            val autoLogId = deterministicAutoLogId(entryId)
            val serverResp =
                PeerSyncResponseDto(
                    serverPeerId = "server-1",
                    syncTimestamp = "2026-08-01T00:00:00Z",
                    activePeers = emptyList(),
                    feeds = emptyList(),
                    items = emptyList(),
                    tags = emptyList(),
                    tombstones = emptyList(),
                    catalogEntries =
                        listOf(
                            PeerCatalogEntryPayload(
                                id = entryId,
                                kind = "article",
                                title = "Stale Server Title",
                                updatedAt = "2020-01-01T00:00:00Z",
                            )
                        ),
                    consumptionLog =
                        listOf(
                            PeerConsumptionLogPayload(
                                id = autoLogId,
                                entryId = entryId,
                                ts = "2026-08-01T00:00:00Z",
                                status = "consumed",
                            )
                        ),
                )

            reconcilePeerSyncResponse(
                dao = dao,
                response = serverResp,
                input =
                    PeerSyncReconcileInput(
                        preloaded = PeerSyncPreloadedState(emptyList(), emptyList()),
                        sentCatalogBatch = built.sentCatalogBatch,
                        catalogStore = repository.catalog,
                    ),
            )

            val afterEntry = dao.getCatalogEntryById(entryId)!!
            assertEquals("Concurrent Newer Title", afterEntry.title)
            assertTrue(afterEntry.isLocalDirty)
            val logs = dao.getConsumptionLogsForEntry(entryId)
            assertEquals(1, logs.size)
            assertEquals("Manual note", logs.single().notes)
        }

    @Test
    fun `nested collections reject cycles slug collisions remap cleanly and LocalFeedPoller strips non-http schemes`() =
        runTest {
            val dao = database.dao()
            val colA = repository.catalog.createCustomList("Parent List")
            val colB = repository.catalog.createCustomList("Child List")
            repository.catalog.addCollectionEntry(colA, colB)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.catalog.addCollectionEntry(colB, colA) }
            }

            // Slug collision from peer remaps incoming memberships & comparisons to colA
            val itemId =
                repository.catalog.upsertEntry(
                    UpsertCatalogEntryInput(kind = EntryKind.BOOK, title = "Dune")
                )
            val item2Id =
                repository.catalog.upsertEntry(
                    UpsertCatalogEntryInput(kind = EntryKind.BOOK, title = "Hyperion")
                )
            val remoteCollidingId = "remote-parent-list-uuid"
            val tsIso = "2026-08-05T10:00:00Z"
            val resp =
                PeerSyncResponseDto(
                    serverPeerId = "server-1",
                    syncTimestamp = tsIso,
                    activePeers = emptyList(),
                    feeds = emptyList(),
                    items = emptyList(),
                    tags = emptyList(),
                    tombstones = emptyList(),
                    collections =
                        listOf(
                            PeerCollectionSpecPayload(
                                collectionId = remoteCollidingId,
                                title = "Parent List",
                                slug = "parent_list",
                                mode = "manual",
                                role = "list",
                                updatedAt = tsIso,
                            )
                        ),
                    collectionEntries =
                        listOf(
                            PeerCollectionEntryPayload(
                                collectionId = remoteCollidingId,
                                entryId = itemId,
                                addedAt = tsIso,
                            )
                        ),
                    comparisons =
                        listOf(
                            PeerComparisonPayload(
                                id = "stale-comp-id",
                                ts = tsIso,
                                scopeCollectionId = remoteCollidingId,
                                ordering = listOf(listOf(itemId), listOf(item2Id)),
                            )
                        ),
                )
            reconcileIncomingCatalogSync(
                dao = dao,
                catalogStore = repository.catalog,
                response = resp,
                sentBatch = OutgoingCatalogSyncBatch(),
            )
            assertTrue(dao.getCollectionMemberships(colA).any { it.entryId == itemId })
            val savedComp = dao.getAllComparisons().single()
            assertEquals(colA, savedComp.scopeCollectionId)
            assertEquals(
                deterministicComparisonId(
                    colA,
                    java.time.Instant.parse(tsIso).toEpochMilli(),
                    listOf(listOf(itemId), listOf(item2Id)),
                ),
                savedComp.id,
            )

            // LocalFeedPoller sanitizes upstream HTTP error detail and drops non-HTTP enclosure
            // URLs
            val errFeed =
                FeedEntity(
                    id = "f-err",
                    title = "Err Feed",
                    url = "https://feeds.example.com/rss.xml",
                    siteUrl = null,
                    feedType = "podcast",
                )
            dao.upsertFeed(errFeed)
            val failingClient = RocheApiClient {
                HttpResponse(
                    statusCode = 502,
                    body = """{"title":"Bad Gateway","detail":"INTERNAL_SECRET_TOKEN_LEAK"}""",
                )
            }
            LocalFeedPoller.pollFeed(dao, failingClient, errFeed, "peer-1")
            assertEquals("HTTP 502", dao.getFeedById("f-err")?.lastError)

            val schemeXml =
                """<rss version="2.0"><channel><title>Pod</title>""" +
                    """<item><title>BadLinkOnly</title><link>javascript:alert(1)</link>""" +
                    """<enclosure url="file:///data/data/com.grayvines.roche/secret" type="audio/mpeg"/></item>""" +
                    """<item><title>ValidLinkBadAudio</title><link>https://feeds.example.com/ep1</link>""" +
                    """<enclosure url="file:///data/data/com.grayvines.roche/secret" type="audio/mpeg"/></item>""" +
                    """</channel></rss>"""
            val schemeClient = RocheApiClient { HttpResponse(statusCode = 200, body = schemeXml) }
            LocalFeedPoller.pollFeed(dao, schemeClient, errFeed, "peer-1")
            val parsedItem = dao.getAllItems().single { it.feedId == "f-err" }
            assertEquals("ValidLinkBadAudio", parsedItem.title)
            assertEquals(null, parsedItem.audioUrl)
            assertEquals("https://feeds.example.com/ep1", parsedItem.url)
        }

    @Test
    fun `Room MIGRATION_8_9 creates items_fts and backfills v8 items and item_contents`() =
        runTest {
            val dbFile = File(tempDir, "roche_v8_upgrade.db")
            val driver = BundledSQLiteDriver()
            driver.open(dbFile.absolutePath).use { conn ->
                createBaseV6TablesForMigrationTest(conn)
                com.grayvines.roche.data.local.migrateCatalogSchemaV7(conn)
                com.grayvines.roche.data.local.migrateCatalogAndSyncSchemaV8(conn)
                conn.execSQL(
                    "INSERT INTO `items` (`id`, `feedId`, `feedTitle`, `title`, `url`, `author`, " +
                        "`summary`, `contentText`, `contentHtml`, `contentType`, `status`, " +
                        "`isFavorite`, `isCachedOffline`, `createdAt`, `readProgress`) VALUES " +
                        "('v8-inline', NULL, 'Systems Weekly', 'Brotli Transport Notes', " +
                        "'https://example.com/brotli', 'Ada Lovelace', " +
                        "'Inline summary AlphaInlineToken88', 'Short inline text', " +
                        "'<p>Short inline text</p>', 'article', 'unread', 0, 0, " +
                        "'2026-06-01T00:00:00Z', 0.0)"
                )
                val deepBody = "Intro. " + "w".repeat(4_000) + " DeepBackfilledToken77 tail."
                val inlinePreview = deepBody.take(2_048)
                conn.execSQL(
                    "INSERT INTO `items` (`id`, `feedId`, `feedTitle`, `title`, `url`, `author`, " +
                        "`summary`, `contentText`, `contentHtml`, `contentType`, `status`, " +
                        "`isFavorite`, `isCachedOffline`, `createdAt`, `readProgress`) VALUES " +
                        "('v8-deep', NULL, 'Kernel Digest', 'Extended Essay', " +
                        "'https://example.com/deep', 'Grace Hopper', 'Preview summary', " +
                        "'$inlinePreview', '<p>$inlinePreview</p>', 'article', 'unread', 0, 0, " +
                        "'2026-06-02T00:00:00Z', 0.0)"
                )
                conn.execSQL(
                    "INSERT INTO `item_contents` " +
                        "(`itemId`, `summary`, `contentText`, `contentHtml`) " +
                        "VALUES ('v8-deep', 'Extended summary', '$deepBody', '<p>$deepBody</p>')"
                )
                conn.execSQL("PRAGMA user_version = 8")
            }

            val migratedDb =
                Room.databaseBuilder<RocheDatabase>(name = dbFile.absolutePath)
                    .setDriver(BundledSQLiteDriver())
                    .addMigrations(RocheDatabase.MIGRATION_8_9)
                    .build()
            try {
                val upgradedRepo =
                    RocheRepository(
                        database = migratedDb,
                        apiClient = RocheApiClient { HttpResponse(statusCode = 200, body = "{}") },
                        settings = settings,
                    )
                val inlineHits = upgradedRepo.searchDeepEntries("AlphaInlineToken88")
                assertEquals(listOf("v8-inline"), inlineHits.map { it.id })

                val deepHits = upgradedRepo.searchDeepEntries("DeepBackfilledToken77")
                assertEquals(listOf("v8-deep"), deepHits.map { it.id })

                val syntaxEscapedHits =
                    upgradedRepo.searchDeepEntries("\"Intro: (DeepBackfilled)* -tail")
                assertEquals(listOf("v8-deep"), syntaxEscapedHits.map { it.id })
                // Cross-table multi-token search: "Extended" is in items.title,
                // "DeepBackfilledToken77" is beyond inline preview in item_contents.contentText
                val crossTableHits =
                    upgradedRepo.searchDeepEntries("Extended DeepBackfilledToken77")
                assertEquals(listOf("v8-deep"), crossTableHits.map { it.id })
                assertTrue(
                    upgradedRepo.searchDeepEntries("\"unbalanced: (DeepBackfilled)* -AND").isEmpty()
                )

                upgradedRepo.deleteItem("v8-inline")
                assertTrue(upgradedRepo.searchDeepEntries("AlphaInlineToken88").isEmpty())
                upgradedRepo.deleteItem("v8-deep")
                assertTrue(upgradedRepo.searchDeepEntries("DeepBackfilledToken77").isEmpty())
            } finally {
                migratedDb.close()
            }

            // Verify external-content FTS5 schema, triggers, and SQLite FTS5 integrity-check
            driver.open(dbFile.absolutePath).use { conn ->
                conn.execSQL("INSERT INTO `items_fts`(`items_fts`) VALUES('integrity-check')")
                conn.execSQL(
                    "INSERT INTO `item_contents_fts`(`item_contents_fts`) VALUES('integrity-check')"
                )
                conn
                    .prepare(
                        "SELECT COUNT(*) FROM sqlite_master " +
                            "WHERE type = 'trigger' AND name LIKE 'room_fts_content_sync_%'"
                    )
                    .use { stmt ->
                        assertTrue(stmt.step())
                        assertEquals(8L, stmt.getLong(0))
                    }
            }
        }

    @Test
    fun `parseFeedXml handles RDF YouTube Atom iTunes arXiv v1 CDATA and rejects XXE or HTML`() {
        // 1. RDF 1.0 feed with sibling <item> elements, dc:creator, dc:date, and arXiv v1 link
        val rdfXml =
            listOf(
                    """<?xml version="1.0" encoding="UTF-8"?>""",
                    """<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"""",
                    """         xmlns="http://purl.org/rss/1.0/"""",
                    """         xmlns:dc="http://purl.org/dc/elements/1.1/">""",
                    """  <channel rdf:about="https://arxiv.org/rss/cs.LG">""",
                    """    <title>cs.LG updates on arXiv.org</title>""",
                    """    <link>http://arxiv.org/list/cs.LG/recent</link>""",
                    """    <description>Machine Learning papers</description>""",
                    """  </channel>""",
                    """  <item rdf:about="http://arxiv.org/abs/2603.11842v1">""",
                    """    <title>Speculative Decoding at Scale</title>""",
                    """    <link>http://arxiv.org/abs/2603.11842v1</link>""",
                    """    <description><![CDATA[<p>Abstract line 1</p>""",
                    """<pre><code>fn step() {""",
                    """    decode();""",
                    """}</code></pre>]]></description>""",
                    """    <dc:creator>A. Researcher</dc:creator>""",
                    """    <dc:date>2026-06-10T14:30:00Z</dc:date>""",
                    """  </item>""",
                    """</rdf:RDF>""",
                )
                .joinToString("\n")
        val rdfBatch =
            LocalFeedPoller.parseFeedXml(
                xml = rdfXml,
                feedUrl = "https://arxiv.org/rss/cs.LG",
                feedType = "arxiv",
            )
        assertEquals("cs.LG updates on arXiv.org", rdfBatch.title)
        assertEquals(1, rdfBatch.items.size)
        val arxivItem = rdfBatch.items.single()
        assertEquals("https://arxiv.org/abs/2603.11842", arxivItem.url)
        assertEquals("paper", arxivItem.contentType)
        assertEquals("A. Researcher", arxivItem.author)
        assertEquals("2026-06-10T14:30:00Z", arxivItem.publishedAt)
        assertTrue(arxivItem.contentHtml?.contains("fn step() {\n    decode();\n}") == true)

        // 2. YouTube Atom feed with yt:videoId and media:group
        val ytAtomXml =
            listOf(
                    """<?xml version="1.0" encoding="UTF-8"?>""",
                    """<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015"""",
                    """      xmlns:media="http://search.yahoo.com/mrss/"""",
                    """      xmlns="http://www.w3.org/2005/Atom">""",
                    """  <title>Systems Conf</title>""",
                    """  <link rel="alternate" href="https://www.youtube.com/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw"/>""",
                    """  <entry>""",
                    """    <id>yt:video:dQw4w9WgXcQ</id>""",
                    """    <yt:videoId>dQw4w9WgXcQ</yt:videoId>""",
                    """    <title>Zero-Copy Networking</title>""",
                    """    <link rel="alternate" href="https://www.youtube.com/watch?v=dQw4w9WgXcQ"/>""",
                    """    <author><name>Systems Conf</name></author>""",
                    """    <published>2026-06-11T09:00:00+00:00</published>""",
                    """    <media:group>""",
                    """      <media:description>Talk abstract from the systems track.</media:description>""",
                    """      <media:thumbnail url="https://i1.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"/>""",
                    """    </media:group>""",
                    """  </entry>""",
                    """</feed>""",
                )
                .joinToString("\n")
        val ytBatch =
            LocalFeedPoller.parseFeedXml(
                xml = ytAtomXml,
                feedUrl =
                    "https://www.youtube.com/feeds/videos.xml?channel_id=UC_x5XG1OV2P6uZZ5FSM9Ttw",
                feedType = "youtube",
            )
        assertEquals("Systems Conf", ytBatch.title)
        val ytItem = ytBatch.items.single()
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", ytItem.url)
        assertEquals("video", ytItem.contentType)
        assertEquals("https://i1.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", ytItem.thumbnailUrl)
        assertTrue(ytItem.summary.contains("Talk abstract from the systems track."))

        // 3. iTunes podcast RSS with itunes:duration and itunes:image
        val itunesXml =
            listOf(
                    """<?xml version="1.0" encoding="UTF-8"?>""",
                    """<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">""",
                    """  <channel>""",
                    """    <title>Kernel Radio</title>""",
                    """    <link>https://pod.example.com</link>""",
                    """    <itunes:author>Linus &amp; Friends</itunes:author>""",
                    """    <itunes:image href="https://pod.example.com/channel-art.jpg"/>""",
                    """    <item>""",
                    """      <title>Episode 9: io_uring</title>""",
                    """      <link>https://pod.example.com/ep9</link>""",
                    """      <enclosure url="https://pod.example.com/ep9.mp3" type="audio/mpeg" length="999"/>""",
                    """      <itunes:duration>01:05:30</itunes:duration>""",
                    """      <itunes:image href="/ep9-art.jpg"/>""",
                    """    </item>""",
                    """  </channel>""",
                    """</rss>""",
                )
                .joinToString("\n")
        val podBatch =
            LocalFeedPoller.parseFeedXml(
                xml = itunesXml,
                feedUrl = "https://pod.example.com/feed.xml",
                feedType = "rss",
            )
        val podItem = podBatch.items.single()
        assertEquals("audio", podItem.contentType)
        assertEquals("https://pod.example.com/ep9.mp3", podItem.audioUrl)
        assertEquals("65:30", podItem.duration)
        assertEquals("https://pod.example.com/ep9-art.jpg", podItem.thumbnailUrl)
        assertEquals("Linus & Friends", podItem.author)

        // 4. XXE external entity rejection (SYSTEM & PUBLIC, case-insensitive)
        val xxeSystemXml =
            listOf(
                    """<?xml version="1.0"?>""",
                    """<!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>""",
                    """<rss version="2.0"><channel><title>&xxe;</title></channel></rss>""",
                )
                .joinToString("\n")
        assertThrows(IllegalArgumentException::class.java) {
            LocalFeedPoller.parseFeedXml(xxeSystemXml, "https://example.com/rss.xml")
        }
        val xxePublicXml =
            listOf(
                    """<!DOCTYPE feed [<!entity ext public "-//W3C//ENTITIES//EN" "http://127.0.0.1/x">]>""",
                    """<feed xmlns="http://www.w3.org/2005/Atom"><title>Bad</title></feed>""",
                )
                .joinToString("\n")
        assertThrows(IllegalArgumentException::class.java) {
            LocalFeedPoller.parseFeedXml(xxePublicXml, "https://example.com/atom.xml")
        }

        // 5. Non-feed HTML error page and rootless garbage rejection
        assertThrows(IllegalArgumentException::class.java) {
            LocalFeedPoller.parseFeedXml(
                "<!DOCTYPE html><html><body><h1>502 Bad Gateway</h1></body></html>",
                "https://example.com/rss.xml",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalFeedPoller.parseFeedXml(
                "upstream proxy timeout plain text",
                "https://example.com/rss.xml",
            )
        }
    }

    @Test
    fun `parseFeedXml preserves Atom xhtml tags allows CDATA ENTITY examples and single-decodes entities`() {
        val atomXhtml =
            listOf(
                    """<?xml version="1.0" encoding="UTF-8"?>""",
                    """<feed xmlns="http://www.w3.org/2005/Atom">""",
                    """  <title>XHTML &amp;lt;Feed&amp;gt;</title>""",
                    """  <entry>""",
                    """    <id>urn:uuid:1</id>""",
                    """    <title>XHTML Post</title>""",
                    """    <link rel="alternate" href="https://blog.example.com/posts/1"/>""",
                    """    <content type="xhtml">""",
                    """      <div xmlns="http://www.w3.org/1999/xhtml">""",
                    """        <p>Intro paragraph.</p>""",
                    """        <pre><code>fn a() {""",
                    """    b();""",
                    """}</code></pre>""",
                    """        <p><a href="/docs">Docs</a><img src="/img/pic.png"/></p>""",
                    """      </div>""",
                    """    </content>""",
                    """  </entry>""",
                    """</feed>""",
                )
                .joinToString("\n")
        val batch = LocalFeedPoller.parseFeedXml(atomXhtml, "https://blog.example.com/atom.xml")
        assertEquals("XHTML &lt;Feed&gt;", batch.title)
        val html = batch.items.single().contentHtml.orEmpty()
        assertTrue(html.contains("<p>Intro paragraph.</p>"))
        assertTrue(html.contains("<pre><code>fn a() {\n    b();\n}</code></pre>"))
        assertTrue(html.contains("""<a href="/docs">Docs</a>"""))
        assertTrue(html.contains("""src="https://blog.example.com/img/pic.png""""))

        val cdataEntityRss =
            listOf(
                    """<?xml version="1.0"?>""",
                    """<rss version="2.0"><channel><title>Sec Blog</title><item>""",
                    """<title>XXE Writeup</title><link>https://sec.example.com/xxe</link>""",
                    """<description><![CDATA[Example payload: <!ENTITY xxe SYSTEM "file:///etc/passwd">]]></description>""",
                    """</item></channel></rss>""",
                )
                .joinToString("\n")
        val secItem =
            LocalFeedPoller.parseFeedXml(cdataEntityRss, "https://sec.example.com/rss")
                .items
                .single()
        assertEquals("Example payload:", secItem.summary)
        assertTrue(secItem.contentHtml.orEmpty().contains("<!ENTITY xxe SYSTEM"))
        assertEquals("&lt;tag&gt; <ok>", decodeHtmlText("&amp;lt;tag&amp;gt;&nbsp;&lt;ok&gt;"))
    }

    @Test
    fun `parseFeedXml supports RSS media_group YouTube videoId fallback and strict resolveRelativeUrl`() {
        val mrssXml =
            listOf(
                    """<?xml version="1.0"?>""",
                    """<rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel>""",
                    """<title>Media Feed</title><item><title>Clip 1</title>""",
                    """<media:group>""",
                    """  <media:content url="https://cdn.example.com/clip1.mp4" type="video/mp4"/>""",
                    """  <media:description>Grouped media summary</media:description>""",
                    """  <media:thumbnail url="/thumbs/clip1.jpg"/>""",
                    """</media:group></item></channel></rss>""",
                )
                .joinToString("\n")
        val mrssItem =
            LocalFeedPoller.parseFeedXml(mrssXml, "https://media.example.com/rss.xml")
                .items
                .single()
        assertEquals("https://cdn.example.com/clip1.mp4", mrssItem.url)
        assertEquals("Grouped media summary", mrssItem.summary)
        assertEquals("https://media.example.com/thumbs/clip1.jpg", mrssItem.thumbnailUrl)

        val ytLinklessXml =
            listOf(
                    """<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">""",
                    """  <title>YT</title>""",
                    """  <entry><id>yt:video:dQw4w9WgXcQ</id><yt:videoId>dQw4w9WgXcQ</yt:videoId><title>Ok</title></entry>""",
                    """  <entry><id>yt:video:bad</id><yt:videoId>../evil</yt:videoId><title>Bad</title></entry>""",
                    """</feed>""",
                )
                .joinToString("\n")
        val ytItems =
            LocalFeedPoller.parseFeedXml(
                    ytLinklessXml,
                    "https://www.youtube.com/feeds/videos.xml?channel_id=UC_x5XG1OV2P6uZZ5FSM9Ttw",
                    "youtube",
                )
                .items
        assertEquals(listOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), ytItems.map { it.url })

        assertEquals(
            null,
            resolveRelativeUrl("https://example.com/a", "https://"),
        )
        assertEquals(
            null,
            resolveRelativeUrl("https://example.com/a", "http:///missing-host"),
        )
        assertEquals(
            null,
            resolveRelativeUrl("https://example.com/a", "https://bad host/x"),
        )
        assertEquals(
            "https://example.com/ok",
            resolveRelativeUrl("https://example.com/a", "https://example.com/ok"),
        )
    }

    @Test
    fun `LocalFeedPoller pollFeed promoting placeholder feed title updates existing items and FTS`() =
        runTest {
            val dao = database.dao()
            dao.upsertFeed(
                FeedEntity(
                    id = "feed-placeholder",
                    title = "blog.example.com",
                    url = "https://blog.example.com/rss.xml",
                    siteUrl = null,
                    feedType = "rss",
                )
            )
            dao.upsertItem(
                ItemEntity(
                    id = "older-item-1",
                    feedId = "feed-placeholder",
                    feedUrl = "https://blog.example.com/rss.xml",
                    feedTitle = "blog.example.com",
                    title = "Older Post",
                    url = "https://blog.example.com/post-0",
                    author = null,
                    summary = "Summary",
                    contentText = "Body",
                    contentHtml = "<p>Body</p>",
                    contentType = "article",
                    status = "unread",
                    isFavorite = false,
                    isCachedOffline = false,
                    publishedAt = "2026-06-01T00:00:00Z",
                    createdAt = "2026-06-01T00:00:00Z",
                    readProgress = 0.0,
                    thumbnailMediaId = null,
                    duration = null,
                )
            )
            val rssXml =
                listOf(
                        """<?xml version="1.0"?>""",
                        """<rss version="2.0"><channel><title>Promoted Feed Title99</title>""",
                        """<item><title>New Post</title><link>https://blog.example.com/post-1</link></item>""",
                        """</channel></rss>""",
                    )
                    .joinToString("\n")
            val pollClient = RocheApiClient { HttpResponse(statusCode = 200, body = rssXml) }
            val feed = dao.getFeedById("feed-placeholder")!!
            LocalFeedPoller.pollFeed(dao, pollClient, feed, "peer-local").getOrThrow()

            assertEquals("Promoted Feed Title99", dao.getItemById("older-item-1")?.feedTitle)
            assertTrue(
                repository.searchDeepEntries("Promoted Feed Title99").any {
                    it.id == "older-item-1"
                }
            )
        }
}

private fun createBaseV6TablesForMigrationTest(conn: androidx.sqlite.SQLiteConnection) {
    conn.execSQL(
        "CREATE TABLE IF NOT EXISTS `feeds` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
            "`url` TEXT NOT NULL, `siteUrl` TEXT, `feedType` TEXT NOT NULL, `description` TEXT, " +
            "`language` TEXT, `pollInterval` INTEGER NOT NULL, `lastPolled` TEXT, " +
            "`lastPolledBy` TEXT, `lastError` TEXT, `lastErrorBy` TEXT, " +
            "`isActive` INTEGER NOT NULL, `autoDownloadEpisodes` INTEGER NOT NULL, " +
            "`customPlaybackSpeed` REAL, PRIMARY KEY(`id`))"
    )
    conn.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_feeds_url` ON `feeds` (`url`)")
    conn.execSQL(
        "CREATE TABLE IF NOT EXISTS `items` (`id` TEXT NOT NULL, `feedId` TEXT, " +
            "`feedTitle` TEXT NOT NULL, `title` TEXT NOT NULL, `url` TEXT NOT NULL, " +
            "`author` TEXT, `summary` TEXT, `contentText` TEXT, `contentHtml` TEXT, " +
            "`contentType` TEXT NOT NULL, `status` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL, " +
            "`isCachedOffline` INTEGER NOT NULL, `publishedAt` TEXT, `createdAt` TEXT NOT NULL, " +
            "`readProgress` REAL NOT NULL, `thumbnailMediaId` TEXT, `duration` TEXT, " +
            "`readAt` TEXT, `thumbnailUrl` TEXT, `feedUrl` TEXT, `interactionUpdatedAt` TEXT, " +
            "`audioUrl` TEXT, PRIMARY KEY(`id`))"
    )
    conn.execSQL("CREATE INDEX IF NOT EXISTS `index_items_feedId` ON `items` (`feedId`)")
    conn.execSQL("CREATE INDEX IF NOT EXISTS `index_items_status` ON `items` (`status`)")
    conn.execSQL("CREATE INDEX IF NOT EXISTS `index_items_contentType` ON `items` (`contentType`)")
    conn.execSQL("CREATE INDEX IF NOT EXISTS `index_items_isFavorite` ON `items` (`isFavorite`)")
    conn.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_items_publishedAt_createdAt` " +
            "ON `items` (`publishedAt`, `createdAt`)"
    )
    conn.execSQL(
        "CREATE TABLE IF NOT EXISTS `item_contents` (`itemId` TEXT NOT NULL, `summary` TEXT, " +
            "`contentText` TEXT, `contentHtml` TEXT, PRIMARY KEY(`itemId`), " +
            "FOREIGN KEY(`itemId`) REFERENCES `items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
    )
    conn.execSQL(
        "CREATE TABLE IF NOT EXISTS `tags` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`color` TEXT, PRIMARY KEY(`id`))"
    )
    conn.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` ON `tags` (`name`)")
    conn.execSQL(
        "CREATE TABLE IF NOT EXISTS `item_tags` (`itemId` TEXT NOT NULL, `tagId` TEXT NOT NULL, " +
            "PRIMARY KEY(`itemId`, `tagId`))"
    )
    conn.execSQL("CREATE INDEX IF NOT EXISTS `index_item_tags_itemId` ON `item_tags` (`itemId`)")
    conn.execSQL("CREATE INDEX IF NOT EXISTS `index_item_tags_tagId` ON `item_tags` (`tagId`)")
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

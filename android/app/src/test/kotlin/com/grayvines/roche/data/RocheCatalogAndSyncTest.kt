@file:Suppress("TooManyFunctions", "LongMethod", "MagicNumber")

package com.grayvines.roche.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RocheCatalogAndSyncTest {
    @TempDir lateinit var tempDir: File

    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var database: RocheDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repository: RocheRepository

    @BeforeEach
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder<RocheDatabase>().setDriver(BundledSQLiteDriver()).build()
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { File(tempDir, "catalog_test.preferences_pb") },
            )
        settings = SettingsRepository(dataStore)
        val apiClient = RocheApiClient { HttpResponse(statusCode = 200, body = "{}") }
        repository =
            RocheRepository(database = database, apiClient = apiClient, settings = settings)
    }

    @AfterEach
    fun tearDown() {
        database.close()
        runBlocking { dataStoreScope.coroutineContext[Job]?.cancelAndJoin() }
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
                assertNotNull(parsedResp)
                assertEquals(1, parsedResp!!.comparisons.size)

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
    fun `1001 Albums list excludes unheard pool albums and diary excludes container kinds`() =
        runTest {
            val catalog = repository.catalog
            catalog.setAlbumGeneratorEnabled(true)

            val initialSnap = catalog.snapshot.first()
            val list1001Before =
                initialSnap.lists.first { it.collectionId == ALBUMS_1001_COLLECTION_ID }
            assertEquals(0, list1001Before.entryCount)
            assertTrue(catalog.selectNextRankCluster(ALBUMS_1001_COLLECTION_ID).isEmpty())

            val finished1 = catalog.finishCurrent1001Album("First listen")
            val afterOneSnap = catalog.snapshot.first()
            val list1001AfterOne =
                afterOneSnap.lists.first { it.collectionId == ALBUMS_1001_COLLECTION_ID }
            assertEquals(1, list1001AfterOne.entryCount)
            assertEquals(
                listOf(finished1.entryId),
                afterOneSnap.entriesByCollectionId[ALBUMS_1001_COLLECTION_ID].orEmpty().map {
                    it.entry.id
                },
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
}

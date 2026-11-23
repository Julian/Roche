@file:Suppress("TooManyFunctions", "LongMethod", "MagicNumber", "LargeClass")

package com.grayvines.roche.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.grayvines.roche.core.MAX_CONSECUTIVE_FAILURES
import com.grayvines.roche.core.deterministicAutoLogId
import com.grayvines.roche.core.deterministicComparisonId
import com.grayvines.roche.core.deterministicFeedId
import com.grayvines.roche.core.isLocalPollNewer
import com.grayvines.roche.core.systemCollectionUuid
import com.grayvines.roche.data.catalog.CatalogSnapshot
import com.grayvines.roche.data.catalog.CatalogStore
import com.grayvines.roche.data.catalog.CollectionRole
import com.grayvines.roche.data.catalog.ConsumptionStatus
import com.grayvines.roche.data.catalog.EntryIdentifierDto
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.SyncCollectionEntryDto
import com.grayvines.roche.data.catalog.SyncCollectionSpecDto
import com.grayvines.roche.data.catalog.SyncComparisonDto
import com.grayvines.roche.data.catalog.SyncConsumptionLogDto
import com.grayvines.roche.data.catalog.SyncEntryContentDto
import com.grayvines.roche.data.catalog.SyncEntryRecordDto
import com.grayvines.roche.data.catalog.SyncEntryStateDto
import com.grayvines.roche.data.catalog.SyncFeedSourceDto
import com.grayvines.roche.data.catalog.UpsertCatalogEntryInput
import com.grayvines.roche.data.feeds.LocalFeedPoller
import com.grayvines.roche.data.feeds.decodeHtmlText
import com.grayvines.roche.data.feeds.resolveRelativeUrl
import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.InboxItemRow
import com.grayvines.roche.data.local.LocalSyncFlagEntity
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.nextLocalHlc
import com.grayvines.roche.data.local.updateFeedActiveGraph
import com.grayvines.roche.data.local.upsertFeed
import com.grayvines.roche.data.local.upsertFeeds
import com.grayvines.roche.data.local.upsertItem
import com.grayvines.roche.data.local.upsertItems
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.PeerNodeDto
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.remote.PublicWebFetcher
import com.grayvines.roche.data.remote.buildPeerSyncRequestPayload
import com.grayvines.roche.data.remote.parsePeerSyncResponseJson
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.data.sync.FLAG_LOCAL_THROUGHPUT_WEIGHT
import com.grayvines.roche.data.sync.PeerSyncRequestConfig
import com.grayvines.roche.data.sync.buildTrackedPeerSyncRequest
import com.grayvines.roche.data.sync.feedPhaseOffsetSecs
import com.grayvines.roche.data.sync.nextScheduledPollEpochSecs
import com.grayvines.roche.data.sync.pollCooperativeFeedsOnDevice
import com.grayvines.roche.data.sync.reconcilePeerSyncResponse
import com.grayvines.roche.data.sync.selectFeedPoller
import com.grayvines.roche.data.sync.shouldPeerPollFeed
import com.grayvines.roche.data.sync.targetPollIntervalSecs
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.FeedType
import com.grayvines.roche.model.isCrossPeerRescueError
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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
                    .addCallback(RocheDatabase.schemaCallback())
                    .addMigrations(*RocheDatabase.allMigrations())
                    .build()
            try {
                assertEquals(1, migratedDb.dao().getAllFeeds().size)
                assertEquals(0, migratedDb.dao().getAllItems().size)
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
                    FeedRow(
                        id = "feed-pod",
                        title = "Systems Radio",
                        url = "https://example.com/podcast.xml",
                        siteUrl = "https://example.com",
                        feedType = "podcast",
                    ),
                    FeedRow(
                        id = "feed-blog",
                        title = "Compiler Notes",
                        url = "https://example.com/blog.xml",
                        siteUrl = "https://example.com/blog",
                        feedType = "rss",
                    ),
                    FeedRow(
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
                    InboxItemRow(
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
                    InboxItemRow(
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
                    InboxItemRow(
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
                    InboxItemRow(
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
                    InboxItemRow(
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

            // 1. Podcast playback reaching >= 0.98 auto-Finishes episode + parent podcast
            repository.updateReadProgress("ep-1", 0.99)
            val loggedAfterPod = dao.getAllConsumptionLogs().map { it.entryId }.toSet()
            assertTrue("ep-1" in loggedAfterPod)
            assertTrue("feed-pod" in loggedAfterPod)

            // 2. Article scrolled to 0.92 stays New; scrolling to >= 0.98 auto-Finishes article +
            // parent publication
            repository.updateReadProgress("art-read", 0.92, fromAudioPlayback = false)
            assertFalse(dao.getAllConsumptionLogs().any { it.entryId == "art-read" })
            repository.updateReadProgress("art-read", 0.98, fromAudioPlayback = false)
            val loggedAfterArt = dao.getAllConsumptionLogs().map { it.entryId }.toSet()
            assertTrue("art-read" in loggedAfterArt)
            assertTrue("feed-blog" in loggedAfterArt)
            assertTrue(dao.getItemStateById("art-read")!!.isFinished)

            // 3. Dismissing (even at 95% progress) and batch Dismiss NEVER log consumption
            repository.setArchiveStateWithSnapshot("art-skimmed", archived = true)
            repository.archiveBatchWithSnapshots(entryIds = listOf("art-bulk"))
            val loggedAfterBulk = dao.getAllConsumptionLogs().map { it.entryId }.toSet()
            assertFalse("art-skimmed" in loggedAfterBulk)
            assertFalse("art-bulk" in loggedAfterBulk)

            // 4. Video progress & Dismiss NEVER auto-Finish; explicit finish & unfinish toggle
            // cleanly with tombstones
            repository.updateReadProgress("vid-1", 1.0, fromAudioPlayback = false)
            assertFalse(dao.getAllConsumptionLogs().any { it.entryId == "vid-1" })
            repository.setArchiveStateWithSnapshot("vid-1", archived = true)
            assertFalse(dao.getAllConsumptionLogs().any { it.entryId == "vid-1" })
            repository.markFeedItemFinished("vid-1")
            val loggedAfterVid = dao.getAllConsumptionLogs().map { it.entryId }.toSet()
            assertTrue("vid-1" in loggedAfterVid)
            assertTrue("feed-yt" in loggedAfterVid)
            assertTrue(repository.unfinishFeedItem("vid-1"))
            assertFalse(dao.getAllConsumptionLogs().any { it.entryId == "vid-1" })
            assertTrue(dao.getAllTombstones().any { it.entityType == "consumption_log" })
            assertTrue(dao.getItemStateById("vid-1")!!.status == "unread")

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
                    config =
                        PeerSyncRequestConfig(
                            peerId = "peer-android",
                            deviceName = "Pixel",
                            sinceSeq = 0L,
                        ),
                )
            val wireJson = buildPeerSyncRequestPayload(built.request)
            assertTrue(wireJson.contains("Sci-Fi Essentials"))
            assertTrue(wireJson.contains("\"show_in_lists\":true"))
            assertTrue(wireJson.contains("Book of the New Sun"))

            // Simulate a second fresh peer receiving that catalog state + a server-completed
            // article
            val managedPeerDb = ManagedTestDatabase()
            val peerDb = managedPeerDb.database
            try {
                val peerCatalog = CatalogStore(peerDb.dao())
                val serverHlc = "001780000001000-0000-server-1"
                val simulatedServerResponse =
                    PeerSyncResponseDto(
                        serverPeerId = "server-1",
                        syncSeq = 1L,
                        serverHlc = serverHlc,
                        syncTimestamp = "2026-06-01T12:00:00Z",
                        activePeers = emptyList(),
                        entries =
                            built.request.entries +
                                listOf(
                                    SyncEntryRecordDto(
                                        id = "remote-feed-1",
                                        kind = "feed",
                                        title = "Server Essayists",
                                        url = "https://essays.example.com",
                                        hlc = serverHlc,
                                        feedSource =
                                            SyncFeedSourceDto(
                                                feedUrl = "https://essays.example.com/rss",
                                                sourceType = "rss",
                                                feedTitle = "Server Essayists",
                                                hlc = serverHlc,
                                            ),
                                    ),
                                    SyncEntryRecordDto(
                                        id = "remote-art-1",
                                        kind = "article",
                                        title = "Finished On Web Client",
                                        creators = listOf("Montaigne"),
                                        url = "https://essays.example.com/1",
                                        publishedAt = "2026-06-01T10:00:00Z",
                                        createdAt = "2026-06-01T10:00:00Z",
                                        hlc = serverHlc,
                                        content =
                                            SyncEntryContentDto(
                                                contentType = "article",
                                                contentHtml = "<p>Essay</p>",
                                            ),
                                        state =
                                            SyncEntryStateDto(
                                                inboxState = "dismissed",
                                                progress = 1.0,
                                                hlc = serverHlc,
                                            ),
                                    ),
                                ),
                        collectionEntries =
                            built.request.collectionEntries +
                                SyncCollectionEntryDto(
                                    collectionId = "remote-feed-1",
                                    entryId = "remote-art-1",
                                    addedAt = "2026-06-01T10:00:00Z",
                                    hlc = serverHlc,
                                ),
                        collectionGenerators = built.request.collectionGenerators,
                        consumptionLog =
                            built.request.consumptionLog +
                                SyncConsumptionLogDto(
                                    id = "remote-log-1",
                                    entryId = "remote-art-1",
                                    ts = "2026-06-01T11:30:00Z",
                                    status = "consumed",
                                    hlc = serverHlc,
                                ),
                        comparisons = built.request.comparisons,
                        tombstones = emptyList(),
                    )

                // Verify response JSON codec round-trips catalog fields too
                val respRaw =
                    """{"server_peer_id":"server-1","sync_seq":1,"server_hlc":"$serverHlc",""" +
                        """"sync_timestamp":"2026-06-01T12:00:00Z","active_peers":[],""" +
                        """"entries":[],"collection_entries":[],"collection_generators":[],"consumption_log":[],"tombstones":[],""" +
                        """"comparisons":[{"id":"c1","ts":"2026-06-01T11:00:00Z","hlc":"$serverHlc",""" +
                        """"ordering":[["$bookA"],["$bookB"]]}]}"""
                val parsedResp = parsePeerSyncResponseJson(respRaw)
                assertEquals(1, parsedResp.comparisons.size)

                reconcilePeerSyncResponse(
                    dao = peerDb.dao(),
                    response = simulatedServerResponse,
                    localPeerId = "peer-2",
                    catalogStore = peerCatalog,
                )

                val peerSnap = peerCatalog.getSnapshot()
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
                managedPeerDb.close()
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

            val initialSnap = catalog.getSnapshot()
            val listBefore = initialSnap.lists.first { it.collectionId == listId }
            assertEquals(0, listBefore.entryCount)
            assertEquals(2, listBefore.poolTotalCount)
            assertEquals(2, listBefore.poolRemainingCount)
            assertTrue(catalog.selectNextRankCluster(listId).isEmpty())

            val finished1 = catalog.finishActiveDraw(listId, "First listen")
            val afterOneSnap = catalog.getSnapshot()
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
                    syncSeq = 5L,
                    serverHlc = "099999999999999-0000-server-1",
                    syncTimestamp = "2026-07-01T12:00:00Z",
                    activePeers = emptyList(),
                    entries =
                        listOf(
                            SyncEntryRecordDto(
                                id = dupId,
                                kind = "movie",
                                title = "Blade Runner (US Theatrical)",
                                creators = listOf("Ridley Scott"),
                                releaseYear = 1982,
                                mergedIntoId = canonId,
                                updatedAt = "2026-07-01T12:00:00Z",
                                hlc = "099999999999999-0000-server-1",
                            )
                        ),
                )
            reconcilePeerSyncResponse(
                dao = database.dao(),
                response = mergeResponse,
                localPeerId = "peer-1",
                catalogStore = catalog,
            )

            val snap = catalog.getSnapshot()
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
                    .addCallback(RocheDatabase.schemaCallback())
                    .addMigrations(*RocheDatabase.allMigrations())
                    .build()
            try {
                val dao = migratedDb.dao()
                assertEquals("5m", dao.getItemById("art-v7")?.duration)
                val idents =
                    dao.getIdentifiersForEntry("alb-v7").associate { it.scheme to it.value }
                assertEquals("spotify:album:4sW8Eql2e2kdRP1A1R1C9t", idents["spotify"])
                assertEquals("mixedcaseslug", idents["slug"])
                assertEquals(
                    null,
                    dao.getLocalSyncFlag(
                        com.grayvines.roche.data.local.FLAG_CATALOG_BACKFILL_NEEDED
                    ),
                )

                // First syncWithServer after upgrade converts legacy ISO cursor to since_seq=0
                // and advances to monotonic sync_seq
                settings.update {
                    it.copy(
                        serverUrl = "https://100.64.0.12:8080",
                        bearerToken = "tok-v8",
                        lastPeerSyncCursor = "2026-06-01T00:00:00Z",
                    )
                }
                val syncBodies = mutableListOf<String>()
                val upgradedRepo =
                    mockRepository(
                        database = migratedDb,
                        settings = settings,
                        transport = { request ->
                            if (
                                request.method == "POST" &&
                                    request.url.endsWith("/api/v1/sync/peer")
                            ) {
                                syncBodies.add(request.body.orEmpty())
                                HttpResponse(
                                    statusCode = 200,
                                    body =
                                        """{"server_peer_id":"server","sync_seq":7,"server_hlc":"001780000001000-0000-server","sync_timestamp":"2026-06-02T00:00:00Z","active_peers":[],"entries":[],"collection_entries":[],"collection_generators":[],"consumption_log":[],"comparisons":[],"tombstones":[]}""",
                                )
                            } else {
                                HttpResponse(statusCode = 404, body = "{}")
                            }
                        },
                    )
                upgradedRepo.syncWithServer().getOrThrow()
                assertFalse(syncBodies.single().contains("since_seq"))
                assertEquals("7", settings.settings.first().lastPeerSyncCursor)
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
            // Simulate concurrent user edit while sync request is in flight (newer HLC)
            val newerHlc = nextLocalHlc(dao, "peer-android")
            dao.upsertEntry(
                dao.getEntryById(entryId)!!.copy(
                    title = "Concurrent Newer Title",
                    updatedAt = "2099-01-01T00:00:00Z",
                    hlc = newerHlc,
                    isLocalDirty = true,
                )
            )

            // Server responds with stale HLC on entry AND a deterministic auto-completion log
            // for the same entry (which already has a manual consumed log!)
            val autoLogId = deterministicAutoLogId(entryId)
            val staleHlc = "000000000001000-0000-server-1"
            val serverResp =
                PeerSyncResponseDto(
                    serverPeerId = "server-1",
                    syncSeq = 2L,
                    serverHlc = "001780000001000-0000-server-1",
                    syncTimestamp = "2026-08-01T00:00:00Z",
                    activePeers = emptyList(),
                    entries =
                        listOf(
                            SyncEntryRecordDto(
                                id = entryId,
                                kind = "article",
                                title = "Stale Server Title",
                                updatedAt = "2020-01-01T00:00:00Z",
                                hlc = staleHlc,
                            )
                        ),
                    consumptionLog =
                        listOf(
                            SyncConsumptionLogDto(
                                id = autoLogId,
                                entryId = entryId,
                                ts = "2026-08-01T00:00:00Z",
                                status = "consumed",
                                hlc = "001780000001000-0000-server-1",
                            )
                        ),
                )

            reconcilePeerSyncResponse(
                dao = dao,
                response = serverResp,
                localPeerId = "peer-android",
                sentBatch = built,
                catalogStore = repository.catalog,
            )

            val afterEntry = dao.getEntryById(entryId)!!
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
            val remoteHlc = "001785924000000-0001-server-1"
            val resp =
                PeerSyncResponseDto(
                    serverPeerId = "server-1",
                    syncSeq = 3L,
                    serverHlc = remoteHlc,
                    syncTimestamp = tsIso,
                    activePeers = emptyList(),
                    entries =
                        listOf(
                            SyncEntryRecordDto(
                                id = remoteCollidingId,
                                kind = "collection",
                                title = "Parent List",
                                updatedAt = tsIso,
                                hlc = remoteHlc,
                                collectionSpec =
                                    SyncCollectionSpecDto(
                                        slug = "parent_list",
                                        mode = "manual",
                                        role = "list",
                                        showInLists = true,
                                    ),
                            )
                        ),
                    collectionEntries =
                        listOf(
                            SyncCollectionEntryDto(
                                collectionId = remoteCollidingId,
                                entryId = itemId,
                                addedAt = tsIso,
                                hlc = remoteHlc,
                            )
                        ),
                    comparisons =
                        listOf(
                            SyncComparisonDto(
                                id = "stale-comp-id",
                                ts = tsIso,
                                scopeCollectionId = remoteCollidingId,
                                ordering = listOf(listOf(itemId), listOf(item2Id)),
                                hlc = remoteHlc,
                            )
                        ),
                )
            reconcilePeerSyncResponse(
                dao = dao,
                response = resp,
                localPeerId = "peer-1",
                catalogStore = repository.catalog,
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
                FeedRow(
                    id = "f-err",
                    title = "Err Feed",
                    url = "https://feeds.example.com/rss.xml",
                    siteUrl = null,
                    feedType = "podcast",
                )
            dao.upsertFeed(errFeed)
            val failingFetcher = PublicWebFetcher {
                HttpResponse(
                    statusCode = 502,
                    body = """{"title":"Bad Gateway","detail":"INTERNAL_SECRET_TOKEN_LEAK"}""",
                )
            }
            LocalFeedPoller.pollFeed(dao, failingFetcher, errFeed, "peer-1")
            assertEquals("HTTP 502", dao.getFeedById("f-err")?.lastError)

            val schemeXml =
                """<rss version="2.0"><channel><title>Pod</title>""" +
                    """<item><title>BadLinkOnly</title><link>javascript:alert(1)</link>""" +
                    """<enclosure url="file:///data/data/com.grayvines.roche/secret" type="audio/mpeg"/></item>""" +
                    """<item><title>ValidLinkBadAudio</title><link>https://feeds.example.com/ep1</link>""" +
                    """<enclosure url="file:///data/data/com.grayvines.roche/secret" type="audio/mpeg"/></item>""" +
                    """</channel></rss>"""
            val schemeFetcher = PublicWebFetcher {
                HttpResponse(statusCode = 200, body = schemeXml)
            }
            LocalFeedPoller.pollFeed(dao, schemeFetcher, errFeed, "peer-1")
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
                    .addCallback(RocheDatabase.schemaCallback())
                    .addMigrations(*RocheDatabase.allMigrations())
                    .build()
            try {
                val upgradedRepo =
                    mockRepository(
                        database = migratedDb,
                        settings = settings,
                        transport = { HttpResponse(statusCode = 200, body = "{}") },
                    )
                val inlineHits = upgradedRepo.searchDeepEntries("AlphaInlineToken88")
                assertEquals(listOf("v8-inline"), inlineHits.map { it.id })

                val deepHits = upgradedRepo.searchDeepEntries("DeepBackfilledToken77")
                assertEquals(listOf("v8-deep"), deepHits.map { it.id })

                val syntaxEscapedHits =
                    upgradedRepo.searchDeepEntries("\"Intro: (DeepBackfilled)* -tail")
                assertEquals(listOf("v8-deep"), syntaxEscapedHits.map { it.id })
                // Cross-table multi-token search: "Extended" is in entries.title,
                // "DeepBackfilledToken77" is beyond inline preview in entry_content.contentText
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
                conn.execSQL("INSERT INTO `entries_fts`(`entries_fts`) VALUES('integrity-check')")
                conn.execSQL(
                    "INSERT INTO `entry_content_fts`(`entry_content_fts`) VALUES('integrity-check')"
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
    fun `Room MIGRATION_9_10 losslessly unifies v9 catalog_entries feeds items tags and pending_mutations`() =
        runTest {
            val dbFile = File(tempDir, "roche_v9_to_v10_lossless.db")
            val driver = BundledSQLiteDriver()
            val longBody = "Lead paragraph. " + "z".repeat(4_200) + " UnifiedV10Token42."
            driver.open(dbFile.absolutePath).use { conn ->
                createBaseV6TablesForMigrationTest(conn)
                com.grayvines.roche.data.local.migrateCatalogSchemaV7(conn)
                com.grayvines.roche.data.local.migrateCatalogAndSyncSchemaV8(conn)
                RocheDatabase.MIGRATION_8_9.migrate(conn)
                conn.execSQL(
                    "INSERT INTO `feeds` (`id`, `title`, `url`, `siteUrl`, `feedType`, " +
                        "`pollInterval`, `isActive`, `autoDownloadEpisodes`, `customPlaybackSpeed`) VALUES " +
                        "('f-pod-v9', 'Old Pod Title', 'https://pod.example.com/rss.xml', " +
                        "'https://pod.example.com', 'podcast', 45, 1, 1, 1.75)"
                )
                conn.execSQL(
                    "INSERT INTO `tags` (`id`, `name`, `color`) VALUES " +
                        "('t-sys', 'systems', '#ff6600')"
                )
                conn.execSQL(
                    "INSERT INTO `items` (`id`, `feedId`, `feedTitle`, `title`, `url`, `author`, " +
                        "`summary`, `contentText`, `contentHtml`, `contentType`, `status`, " +
                        "`isFavorite`, `isCachedOffline`, `publishedAt`, `createdAt`, `readProgress`, " +
                        "`thumbnailMediaId`, `duration`, `audioUrl`) VALUES " +
                        "('ep-v9', 'f-pod-v9', 'Old Pod Title', 'Episode 9', " +
                        "'https://pod.example.com/ep9', 'Ada', 'Short summary', " +
                        "'${longBody.take(2_048)}', '<p>Preview</p>', 'audio', 'unread', " +
                        "0, 1, '2026-06-01T10:00:00Z', '2026-06-01T10:00:00Z', 0.2, " +
                        "'thumb-9', '12:30', 'https://pod.example.com/ep9.mp3')"
                )
                conn.execSQL(
                    "INSERT INTO `item_contents` (`itemId`, `summary`, `contentText`, `contentHtml`) " +
                        "VALUES ('ep-v9', 'Full summary', '$longBody', '<p>$longBody</p>')"
                )
                conn.execSQL(
                    "INSERT INTO `item_tags` (`itemId`, `tagId`) VALUES ('ep-v9', 't-sys')"
                )
                conn.execSQL(
                    "INSERT INTO `catalog_entries` (`id`, `kind`, `title`, `creatorsJson`, " +
                        "`releaseYear`, `metadataJson`, `createdAt`, `updatedAt`, `isLocalDirty`) VALUES " +
                        "('mov-v9', 'movie', 'Stalker', '[\"Andrei Tarkovsky\"]', 1979, " +
                        "'{}', '2026-05-01T00:00:00Z', '2026-05-02T00:00:00Z', 0)"
                )
                conn.execSQL(
                    "INSERT INTO `pending_mutations` (`entityId`, `isLocalFeed`, `isLocalItem`, " +
                        "`titleOverride`, `dirtyStatus`, `dirtyFavorite`, `dirtyReadProgress`, " +
                        "`dirtyReadAt`, `addedTagsCsv`, `removedTagsCsv`) VALUES " +
                        "('f-pod-v9', 0, 0, 'Renamed Pod V9', '__feed_archived__', NULL, NULL, NULL, '', ''), " +
                        "('ep-v9', 0, 0, NULL, 'archived', 1, 0.85, '2026-06-02T12:00:00Z', 'rust', 'systems')"
                )
                conn.execSQL("PRAGMA user_version = 9")
            }

            val migratedDb =
                Room.databaseBuilder<RocheDatabase>(name = dbFile.absolutePath)
                    .setDriver(BundledSQLiteDriver())
                    .addCallback(RocheDatabase.schemaCallback())
                    .addMigrations(*RocheDatabase.allMigrations())
                    .build()
            try {
                val dao = migratedDb.dao()
                val feed = dao.getFeedById("f-pod-v9")!!
                assertEquals("Renamed Pod V9", feed.title)
                assertEquals("https://pod.example.com", feed.siteUrl)
                assertFalse(feed.isActive)
                assertTrue(feed.autoDownloadEpisodes)
                assertEquals(1.75f, feed.customPlaybackSpeed)

                val item = dao.getItemById("ep-v9")!!
                assertEquals("archived", item.status)
                assertTrue(item.isFavorite)
                assertTrue(item.isCachedOffline)
                assertEquals(0.85, item.readProgress, 1e-6)
                assertEquals("thumb-9", item.thumbnailMediaId)
                assertEquals("12:30", item.duration)
                assertEquals("https://pod.example.com/ep9.mp3", item.audioUrl)
                assertTrue(item.contentText?.contains("UnifiedV10Token42") == true)
                assertEquals(listOf("rust"), dao.getTagNamesForItem("ep-v9"))
                assertEquals("Stalker", dao.getEntryById("mov-v9")?.title)

                val upgradedRepo =
                    mockRepository(
                        database = migratedDb,
                        settings = settings,
                        transport = { HttpResponse(statusCode = 200, body = "{}") },
                    )
                assertEquals(
                    listOf("ep-v9"),
                    upgradedRepo.searchDeepEntries("UnifiedV10Token42").map { it.id },
                )
            } finally {
                migratedDb.close()
            }

            driver.open(dbFile.absolutePath).use { conn ->
                conn
                    .prepare(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN " +
                            "('feeds','items','item_contents','items_fts','item_contents_fts'," +
                            "'tags','item_tags','pending_mutations','catalog_entries')"
                    )
                    .use { stmt ->
                        assertTrue(stmt.step())
                        assertEquals(0L, stmt.getLong(0))
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
        val rssNarratedBatch =
            LocalFeedPoller.parseFeedXml(
                xml = itunesXml,
                feedUrl = "https://pod.example.com/feed.xml",
                feedType = "rss",
            )
        val rssNarratedItem = rssNarratedBatch.items.single()
        assertEquals("article", rssNarratedItem.contentType)
        assertEquals("https://pod.example.com/ep9.mp3", rssNarratedItem.audioUrl)
        assertEquals("65:30", rssNarratedItem.duration)
        assertEquals("https://pod.example.com/ep9-art.jpg", rssNarratedItem.thumbnailUrl)
        assertEquals("Linus & Friends", rssNarratedItem.author)

        val podBatch =
            LocalFeedPoller.parseFeedXml(
                xml = itunesXml,
                feedUrl = "https://pod.example.com/feed.xml",
                feedType = "podcast",
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
        assertTrue(html.contains("""<a href="https://blog.example.com/docs">Docs</a>"""))
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
        assertTrue(secItem.contentHtml.orEmpty().contains("Example payload:"))
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
                FeedRow(
                    id = "feed-placeholder",
                    title = "blog.example.com",
                    url = "https://blog.example.com/rss.xml",
                    siteUrl = null,
                    feedType = "rss",
                )
            )
            dao.upsertItem(
                InboxItemRow(
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
            val pollClient = PublicWebFetcher { HttpResponse(statusCode = 200, body = rssXml) }
            val feed = dao.getFeedById("feed-placeholder")!!
            LocalFeedPoller.pollFeed(dao, pollClient, feed, "peer-local").getOrThrow()

            assertEquals("Promoted Feed Title99", dao.getItemById("older-item-1")?.feedTitle)
            assertTrue(
                repository.searchDeepEntries("Promoted Feed Title99").any {
                    it.id == "older-item-1"
                }
            )
        }

    @Test
    fun `Room MIGRATION_10_11 adds consecutiveFailures and backfills failing rows only`() =
        runTest {
            val dbFile = File(tempDir, "roche_v10_to_v11.db")
            val driver = BundledSQLiteDriver()
            driver.open(dbFile.absolutePath).use { conn ->
                createBaseV6TablesForMigrationTest(conn)
                com.grayvines.roche.data.local.migrateCatalogSchemaV7(conn)
                com.grayvines.roche.data.local.migrateCatalogAndSyncSchemaV8(conn)
                RocheDatabase.MIGRATION_8_9.migrate(conn)
                RocheDatabase.MIGRATION_9_10.migrate(conn)
                conn.execSQL(
                    "INSERT INTO `entries` (`id`, `kind`, `title`, `creatorsJson`, `url`, " +
                        "`metadataJson`, `createdAt`, `updatedAt`, `hlc`, `isLocalDirty`) VALUES " +
                        "('f-ok', 'publication', 'Healthy Feed', '[]', 'https://ok.example.com', '{}', '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z', '001', 0), " +
                        "('f-fail', 'publication', 'Failing Feed', '[]', 'https://fail.example.com', '{}', '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z', '001', 0), " +
                        "('f-blank', 'publication', 'Blank Err Feed', '[]', 'https://blank.example.com', '{}', '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z', '001', 0)"
                )
                conn.execSQL(
                    "INSERT INTO `feed_sources` (`collectionId`, `feedUrl`, `sourceType`, `feedTitle`, " +
                        "`pollInterval`, `isActive`, `lastError`, `lastErrorBy`, `autoDownloadEpisodes`, `hlc`, `isLocalDirty`) VALUES " +
                        "('f-ok', 'https://ok.example.com/rss.xml', 'rss', 'Healthy Feed', 30, 1, NULL, NULL, 0, '001', 0), " +
                        "('f-fail', 'https://fail.example.com/rss.xml', 'rss', 'Failing Feed', 30, 1, 'HTTP 503', 'server', 0, '001', 0), " +
                        "('f-blank', 'https://blank.example.com/rss.xml', 'rss', 'Blank Err Feed', 30, 1, '   ', 'server', 0, '001', 0)"
                )
                conn.execSQL("PRAGMA user_version = 10")
            }

            val migratedDb =
                Room.databaseBuilder<RocheDatabase>(name = dbFile.absolutePath)
                    .setDriver(BundledSQLiteDriver())
                    .addCallback(RocheDatabase.schemaCallback())
                    .addMigrations(*RocheDatabase.allMigrations())
                    .build()
            try {
                val dao = migratedDb.dao()
                assertEquals(0, dao.getFeedById("f-ok")?.consecutiveFailures)
                assertEquals(1, dao.getFeedById("f-fail")?.consecutiveFailures)
                assertEquals(0, dao.getFeedById("f-blank")?.consecutiveFailures)
                assertEquals(1, dao.getFeedSourceById("f-fail")?.consecutiveFailures)
                assertEquals(0, dao.getFeedSourceById("f-ok")?.consecutiveFailures)
            } finally {
                migratedDb.close()
            }
        }

    @Test
    fun `staggered scheduling phase-aligned HRW backoff caps and cross-peer rescue prevent ping-pong`() {
        val url = "https://blog.rust-lang.org/feed.xml"
        val intervalSecs = 1_800L
        val phase = feedPhaseOffsetSecs(url, 30)
        assertTrue(phase in 0L until intervalSecs)

        // 1. Staggering spreads 100 feeds across the 30-minute window (not bunched at :00/:30)
        val phases =
            (0 until 100).map { i ->
                feedPhaseOffsetSecs("https://feed-$i.example.com/rss.xml", 30)
            }
        assertTrue(phases.distinct().size >= 85)
        assertTrue(
            phases.any { it < 450L } &&
                phases.any { it in 450L..1_350L } &&
                phases.any { it > 1_350L }
        )

        // 2. Phase-aligned HRW stays constant across the feed's entire [slot, slot + interval)
        // window, even across wall-clock :00/:30 boundaries
        val peers =
            listOf(
                PeerNodeDto("server", "Headless", "wired", 1.0, "2026-06-01T12:00:00Z"),
                PeerNodeDto("android-1", "Pixel", "wifi", 1.0, "2026-06-01T12:00:00Z"),
            )
        val slotStart = 1_780_315_200L + phase // 2026-06-01T12:00:00Z + phase
        val winnerAtStart = selectFeedPoller(peers, url, 30, slotStart, null, null, null)
        val winnerAcrossWallBoundary =
            selectFeedPoller(peers, url, 30, slotStart + intervalSecs - 1L, null, null, null)
        assertEquals(winnerAtStart, winnerAcrossWallBoundary)

        // 3. Steady-state healthy poll at slot + 5s schedules next poll at exact slot + interval
        assertEquals(
            slotStart + intervalSecs,
            nextScheduledPollEpochSecs(url, 30, slotStart + 5L, 0),
        )
        // Post-Refresh-All at T spreads feeds across [T + 120s, T + 120s + 1800s) on phase slots
        val refreshAllEpoch = 1_780_315_200L
        val nextSlots =
            (0 until 40).map { i ->
                nextScheduledPollEpochSecs(
                    "https://feed-$i.example.com/rss.xml",
                    30,
                    refreshAllEpoch,
                    0,
                )
            }
        val windowStart = refreshAllEpoch + 120L
        val windowEnd = windowStart + intervalSecs
        assertTrue(nextSlots.all { it in windowStart..windowEnd })
        assertTrue(nextSlots.distinct().size >= 30)

        // 4. Exponential backoff progression & caps (30m -> 24h cap, 24h -> 4x cap, 48h -> 7d
        // ceiling)
        assertEquals(
            listOf(1_800L, 3_600L, 7_200L, 14_400L, 28_800L, 57_600L, 86_400L, 86_400L),
            listOf(0, 1, 2, 3, 4, 5, 6, 20).map { targetPollIntervalSecs(30, it) },
        )
        assertEquals(172_800L, targetPollIntervalSecs(1_440, 1))
        assertEquals(345_600L, targetPollIntervalSecs(1_440, 2))
        assertEquals(345_600L, targetPollIntervalSecs(1_440, 5))
        assertEquals(604_800L, targetPollIntervalSecs(2_880, 2))

        // 5. Cross-peer rescue: failure #1 allows immediate rescue by another peer;
        // failure #2 stops immediate rescue for ALL peers (no ping-pong!) until backoff due_at
        val failedOnce =
            FeedRow(
                id = "f-rescue",
                title = "Protected",
                url = url,
                siteUrl = null,
                feedType = "rss",
                pollInterval = 30,
                lastPolled = Instant.ofEpochSecond(slotStart).toString(),
                lastPolledBy = "server",
                lastError = "HTTP 403",
                lastErrorBy = "server",
                consecutiveFailures = 1,
                isActive = true,
            )
        val oneMinLater = Instant.ofEpochSecond(slotStart + 60L)
        assertFalse(shouldPeerPollFeed("server", peers, failedOnce, oneMinLater))
        assertTrue(shouldPeerPollFeed("android-1", peers, failedOnce, oneMinLater))

        val failedOnceItem =
            FeedItem(
                id = "f-rescue",
                title = "Protected",
                url = url,
                siteUrl = null,
                feedType = FeedType.RSS,
                unreadCount = 0,
                lastError = "HTTP 403",
                lastErrorBy = "server",
                consecutiveFailures = 1,
            )
        assertTrue(failedOnceItem.isCrossPeerRescueError("android-1"))
        assertFalse(failedOnceItem.isCrossPeerRescueError("server"))

        // Second failure by android-1 -> consecutiveFailures == 2 -> neither peer immediately polls
        val failedTwice =
            failedOnce.copy(
                lastPolled = oneMinLater.toString(),
                lastError = "HTTP 500",
                lastErrorBy = "android-1",
                consecutiveFailures = 2,
            )
        val twoMinsLater = Instant.ofEpochSecond(slotStart + 120L)
        assertFalse(shouldPeerPollFeed("server", peers, failedTwice, twoMinsLater))
        assertFalse(shouldPeerPollFeed("android-1", peers, failedTwice, twoMinsLater))
        assertFalse(
            failedOnceItem
                .copy(lastErrorBy = "android-1", consecutiveFailures = 2)
                .isCrossPeerRescueError("server")
        )

        // Once backoff dueAt + 1 full interval has passed, any peer can rescue overdue feed
        val dueAfterTwoFailures = nextScheduledPollEpochSecs(url, 30, slotStart + 60L, 2)
        assertFalse(
            shouldPeerPollFeed(
                "server",
                peers,
                failedTwice,
                Instant.ofEpochSecond(dueAfterTwoFailures - 1L),
            )
        )
        assertTrue(
            shouldPeerPollFeed(
                "server",
                peers,
                failedTwice,
                Instant.ofEpochSecond(dueAfterTwoFailures + intervalSecs),
            )
        )
    }

    @Test
    fun `LocalFeedPoller increments caps resets consecutiveFailures and unarchive resets backoff`() =
        runTest {
            val dao = database.dao()
            val feed =
                FeedRow(
                    id = "f-backoff",
                    title = "Flaky Feed",
                    url = "https://flaky.example.com/rss.xml",
                    siteUrl = null,
                    feedType = "rss",
                )
            dao.upsertFeed(feed)

            val httpFailClient = PublicWebFetcher { HttpResponse(statusCode = 503, body = "err") }
            val badXmlClient = PublicWebFetcher { HttpResponse(statusCode = 200, body = "not xml") }
            val okClient = PublicWebFetcher {
                HttpResponse(
                    statusCode = 200,
                    body =
                        """<rss version="2.0"><channel><title>Flaky Feed</title>""" +
                            """<item><title>Recovered</title><link>https://flaky.example.com/1</link></item>""" +
                            """</channel></rss>""",
                )
            }

            // Failure #1 (HTTP) -> 1, Failure #2 (XML parse) -> 2
            LocalFeedPoller.pollFeed(dao, httpFailClient, dao.getFeedById("f-backoff")!!, "peer-a")
            assertEquals(1, dao.getFeedById("f-backoff")?.consecutiveFailures)
            LocalFeedPoller.pollFeed(dao, badXmlClient, dao.getFeedById("f-backoff")!!, "peer-b")
            val afterTwo = dao.getFeedById("f-backoff")!!
            assertEquals(2, afterTwo.consecutiveFailures)
            assertEquals("Feed XML parse failed", afterTwo.lastError)
            assertEquals("peer-b", afterTwo.lastErrorBy)

            // Cap at MAX_CONSECUTIVE_FAILURES (20)
            dao.upsertFeed(afterTwo.copy(consecutiveFailures = MAX_CONSECUTIVE_FAILURES))
            LocalFeedPoller.pollFeed(dao, httpFailClient, dao.getFeedById("f-backoff")!!, "peer-a")
            assertEquals(
                MAX_CONSECUTIVE_FAILURES,
                dao.getFeedById("f-backoff")?.consecutiveFailures,
            )

            // Unarchive resets consecutiveFailures, lastError, lastErrorBy
            dao.updateFeedActiveGraph("f-backoff", isActive = false, markDirty = false)
            assertEquals(
                MAX_CONSECUTIVE_FAILURES,
                dao.getFeedById("f-backoff")?.consecutiveFailures,
            )
            dao.updateFeedActiveGraph("f-backoff", isActive = true, markDirty = true)
            val afterUnarchive = dao.getFeedById("f-backoff")!!
            assertTrue(afterUnarchive.isActive)
            assertEquals(0, afterUnarchive.consecutiveFailures)
            assertEquals(null, afterUnarchive.lastError)
            assertEquals(null, afterUnarchive.lastErrorBy)

            // Poll failure followed by poll success resets consecutiveFailures to 0
            LocalFeedPoller.pollFeed(dao, httpFailClient, afterUnarchive, "peer-a")
            assertEquals(1, dao.getFeedById("f-backoff")?.consecutiveFailures)
            LocalFeedPoller.pollFeed(dao, okClient, dao.getFeedById("f-backoff")!!, "peer-a")
                .getOrThrow()
            val afterSuccess = dao.getFeedById("f-backoff")!!
            assertEquals(0, afterSuccess.consecutiveFailures)
            assertEquals(null, afterSuccess.lastError)
            assertEquals(null, afterSuccess.lastErrorBy)
        }

    @Test
    fun `pollCooperativeFeedsOnDevice persists per-feed EWMA ignores failures and uses live per-feed completion clock`() =
        runTest {
            val dao = database.dao()
            val rssBody =
                """<rss version="2.0"><channel><title>Fast</title>""" +
                    """<item><title>Post</title><link>https://fast.example.com/1</link></item>""" +
                    """</channel></rss>"""
            dao.upsertFeeds(
                listOf(
                    FeedRow(
                        id = "f-ewma-1",
                        title = "Fast 1",
                        url = "https://fast1.example.com/rss.xml",
                        siteUrl = null,
                        feedType = "rss",
                        lastPolled = "2026-06-01T10:00:00Z",
                    ),
                    FeedRow(
                        id = "f-ewma-2",
                        title = "Fast 2",
                        url = "https://fast2.example.com/rss.xml",
                        siteUrl = null,
                        feedType = "rss",
                        lastPolled = "2026-06-01T10:00:00Z",
                    ),
                    FeedRow(
                        id = "f-ewma-fail",
                        title = "Broken",
                        url = "https://broken.example.com/rss.xml",
                        siteUrl = null,
                        feedType = "rss",
                        lastPolled = "2026-06-01T10:00:00Z",
                    ),
                )
            )
            val tickCounter = AtomicLong(0L)
            val baseEpoch = Instant.parse("2026-06-01T12:00:00Z").epochSecond
            val advancingClock: () -> Instant = {
                Instant.ofEpochSecond(baseEpoch + tickCounter.getAndIncrement() * 5L)
            }
            val mixedFetcher = PublicWebFetcher { req ->
                if (req.url.contains("broken.example.com")) {
                    HttpResponse(statusCode = 500, body = "fail")
                } else {
                    HttpResponse(statusCode = 200, body = rssBody)
                }
            }

            // Seed from activePeers (weight = 0.8) on first run
            val outcome1 =
                pollCooperativeFeedsOnDevice(
                    dao = dao,
                    publicWebFetcher = mixedFetcher,
                    localPeerId = "android-1",
                    activePeers = listOf(PeerNodeDto("android-1", "Pixel", "wifi", 0.8)),
                    clock = advancingClock,
                )
            assertEquals(2, outcome1.polledFeedsCount)
            assertEquals(3, outcome1.attemptedFeedsCount)
            val w1 = outcome1.measuredWeight!!
            assertTrue(w1 > 0.8 && w1 < 10.0, "Expected smoothed EWMA in (0.8, 10.0), got $w1")
            assertEquals(w1, dao.getLocalSyncFlag(FLAG_LOCAL_THROUGHPUT_WEIGHT)?.toDouble())
            assertEquals(1, dao.getFeedById("f-ewma-fail")?.consecutiveFailures)

            // Distinct completion timestamps stamped per feed from advancingClock
            val polledTimestamps =
                listOf("f-ewma-1", "f-ewma-2", "f-ewma-fail")
                    .mapNotNull { dao.getFeedById(it)?.lastPolled }
                    .distinct()
            assertEquals(3, polledTimestamps.size)

            // Failed-only poll preserves EWMA flag and returns null measuredWeight
            dao.upsertLocalSyncFlag(LocalSyncFlagEntity(FLAG_LOCAL_THROUGHPUT_WEIGHT, "2.5"))
            val failOnlyFetcher = PublicWebFetcher { HttpResponse(statusCode = 503, body = "down") }
            val outcomeFail =
                pollCooperativeFeedsOnDevice(
                    dao = dao,
                    publicWebFetcher = failOnlyFetcher,
                    localPeerId = "android-1",
                    activePeers = listOf(PeerNodeDto("android-1", "Pixel", "wifi", 0.5)),
                    nowInstant = Instant.parse("2026-06-02T12:00:00Z"),
                )
            assertEquals(0, outcomeFail.polledFeedsCount)
            assertEquals(3, outcomeFail.attemptedFeedsCount)
            assertEquals(null, outcomeFail.measuredWeight)
            assertEquals("2.5", dao.getLocalSyncFlag(FLAG_LOCAL_THROUGHPUT_WEIGHT))

            // Next successful run seeds from local_sync_flags ("2.5"), not activePeers (0.5)
            val outcome2 =
                pollCooperativeFeedsOnDevice(
                    dao = dao,
                    publicWebFetcher =
                        PublicWebFetcher { HttpResponse(statusCode = 200, body = rssBody) },
                    localPeerId = "android-1",
                    activePeers = listOf(PeerNodeDto("android-1", "Pixel", "wifi", 0.5)),
                    nowInstant = Instant.parse("2026-06-04T12:00:00Z"),
                )
            val w2 = outcome2.measuredWeight!!
            assertTrue(w2 > 2.5, "Expected EWMA seeded from persisted 2.5 to rise, got $w2")
        }

    @Test
    fun `isLocalPollNewer handles fractional seconds and PeerSyncIn syncs and normalizes consecutiveFailures`() =
        runTest {
            assertFalse(isLocalPollNewer("2026-06-01T12:00:00Z", "2026-06-01T12:00:00.500Z"))
            assertTrue(isLocalPollNewer("2026-06-01T12:00:00.500Z", "2026-06-01T12:00:00Z"))
            assertFalse(isLocalPollNewer("2026-06-01T12:00:00Z", "2026-06-01T12:00:00+00:00"))
            assertTrue(isLocalPollNewer("2026-06-01T12:00:00Z", null))
            assertFalse(isLocalPollNewer(null, "2026-06-01T12:00:00Z"))

            val dao = database.dao()
            val feedId = deterministicFeedId("https://frac.example.com/rss.xml")
            dao.upsertFeed(
                FeedRow(
                    id = feedId,
                    title = "Fractional Feed",
                    url = "https://frac.example.com/rss.xml",
                    siteUrl = null,
                    feedType = "rss",
                    lastPolled = "2026-06-01T12:00:00Z",
                    lastPolledBy = "android-1",
                    lastError = null,
                    lastErrorBy = null,
                    consecutiveFailures = 0,
                    hlc = "001780000001000-0000-android-1",
                )
            )

            // Incoming poll at 12:00:00.500Z is 500ms newer than local 12:00:00Z, with legacy
            // consecutive_failures=0 and non-blank last_error -> normalized to 1!
            val serverHlc = "001780000002000-0000-server"
            reconcilePeerSyncResponse(
                dao = dao,
                response =
                    PeerSyncResponseDto(
                        serverPeerId = "server",
                        syncSeq = 1L,
                        serverHlc = serverHlc,
                        syncTimestamp = "2026-06-01T12:00:01Z",
                        entries =
                            listOf(
                                SyncEntryRecordDto(
                                    id = feedId,
                                    kind = "publication",
                                    title = "Fractional Feed",
                                    hlc = serverHlc,
                                    feedSource =
                                        SyncFeedSourceDto(
                                            feedUrl = "https://frac.example.com/rss.xml",
                                            lastPolledAt = "2026-06-01T12:00:00.500Z",
                                            lastPolledBy = "server",
                                            lastError = "HTTP 502",
                                            lastErrorBy = "server",
                                            consecutiveFailures = 0,
                                            hlc = serverHlc,
                                        ),
                                )
                            ),
                    ),
                localPeerId = "android-1",
            )
            val afterLegacyError = dao.getFeedById(feedId)!!
            assertEquals("2026-06-01T12:00:00.500Z", afterLegacyError.lastPolled)
            assertEquals("HTTP 502", afterLegacyError.lastError)
            assertEquals(1, afterLegacyError.consecutiveFailures)

            // Local newer poll succeeds (lastError = null); stale incoming error must NOT resurrect
            dao.upsertFeed(
                afterLegacyError.copy(
                    lastPolled = "2026-06-01T12:05:00Z",
                    lastPolledBy = "android-1",
                    lastError = null,
                    lastErrorBy = null,
                    consecutiveFailures = 0,
                )
            )
            reconcilePeerSyncResponse(
                dao = dao,
                response =
                    PeerSyncResponseDto(
                        serverPeerId = "server",
                        syncSeq = 2L,
                        serverHlc = serverHlc,
                        syncTimestamp = "2026-06-01T12:05:01Z",
                        entries =
                            listOf(
                                SyncEntryRecordDto(
                                    id = feedId,
                                    kind = "publication",
                                    title = "Fractional Feed",
                                    hlc = serverHlc,
                                    feedSource =
                                        SyncFeedSourceDto(
                                            feedUrl = "https://frac.example.com/rss.xml",
                                            lastPolledAt = "2026-06-01T12:00:00.500Z",
                                            lastPolledBy = "server",
                                            lastError = "HTTP 502",
                                            lastErrorBy = "server",
                                            consecutiveFailures = 4,
                                            hlc = serverHlc,
                                        ),
                                )
                            ),
                    ),
                localPeerId = "android-1",
            )
            val afterStaleRemote = dao.getFeedById(feedId)!!
            assertEquals("2026-06-01T12:05:00Z", afterStaleRemote.lastPolled)
            assertEquals(null, afterStaleRemote.lastError)
            assertEquals(0, afterStaleRemote.consecutiveFailures)

            // Remote unarchive (newer HLC, isActive false -> true, incomingError == null)
            // clears stale local failure state even if local had an error
            dao.upsertFeed(
                afterStaleRemote.copy(
                    isActive = false,
                    lastPolled = "2026-06-01T12:06:00Z",
                    lastError = "HTTP 503",
                    lastErrorBy = "android-1",
                    consecutiveFailures = 5,
                    hlc = "001780000003000-0000-android-1",
                    isLocalDirty = false,
                )
            )
            val remoteUnarchiveHlc = "001780000004000-0000-server"
            reconcilePeerSyncResponse(
                dao = dao,
                response =
                    PeerSyncResponseDto(
                        serverPeerId = "server",
                        syncSeq = 3L,
                        serverHlc = remoteUnarchiveHlc,
                        syncTimestamp = "2026-06-01T12:07:00Z",
                        entries =
                            listOf(
                                SyncEntryRecordDto(
                                    id = feedId,
                                    kind = "publication",
                                    title = "Fractional Feed",
                                    hlc = remoteUnarchiveHlc,
                                    feedSource =
                                        SyncFeedSourceDto(
                                            feedUrl = "https://frac.example.com/rss.xml",
                                            isActive = true,
                                            lastPolledAt = "2026-06-01T12:05:00Z",
                                            lastPolledBy = "server",
                                            lastError = null,
                                            lastErrorBy = null,
                                            consecutiveFailures = 0,
                                            hlc = remoteUnarchiveHlc,
                                        ),
                                )
                            ),
                    ),
                localPeerId = "android-1",
            )
            val afterRemoteUnarchive = dao.getFeedById(feedId)!!
            assertTrue(afterRemoteUnarchive.isActive)
            assertEquals(null, afterRemoteUnarchive.lastError)
            assertEquals(null, afterRemoteUnarchive.lastErrorBy)
            assertEquals(0, afterRemoteUnarchive.consecutiveFailures)

            // Local unarchive clears error and bumps HLC while keeping lastPolledAt unchanged;
            // a stale pre-unarchive remote DTO with EQUAL lastPolledAt must NOT resurrect error
            val stalePreUnarchiveHlc = remoteUnarchiveHlc
            dao.upsertFeed(
                afterRemoteUnarchive.copy(
                    isActive = false,
                    lastPolled = "2026-06-01T12:08:00Z",
                    lastError = "HTTP 500",
                    lastErrorBy = "server",
                    consecutiveFailures = 4,
                    hlc = stalePreUnarchiveHlc,
                    isLocalDirty = false,
                )
            )
            dao.updateFeedActiveGraph(feedId, isActive = true, markDirty = true)
            reconcilePeerSyncResponse(
                dao = dao,
                response =
                    PeerSyncResponseDto(
                        serverPeerId = "server",
                        syncSeq = 4L,
                        serverHlc = stalePreUnarchiveHlc,
                        syncTimestamp = "2026-06-01T12:09:00Z",
                        entries =
                            listOf(
                                SyncEntryRecordDto(
                                    id = feedId,
                                    kind = "publication",
                                    title = "Fractional Feed",
                                    hlc = stalePreUnarchiveHlc,
                                    feedSource =
                                        SyncFeedSourceDto(
                                            feedUrl = "https://frac.example.com/rss.xml",
                                            isActive = false,
                                            lastPolledAt = "2026-06-01T12:08:00Z",
                                            lastPolledBy = "server",
                                            lastError = "HTTP 500",
                                            lastErrorBy = "server",
                                            consecutiveFailures = 4,
                                            hlc = stalePreUnarchiveHlc,
                                        ),
                                )
                            ),
                    ),
                localPeerId = "android-1",
            )
            val afterLocalUnarchiveSync = dao.getFeedById(feedId)!!
            assertTrue(afterLocalUnarchiveSync.isActive)
            assertEquals("2026-06-01T12:08:00Z", afterLocalUnarchiveSync.lastPolled)
            assertEquals(null, afterLocalUnarchiveSync.lastError)
            assertEquals(null, afterLocalUnarchiveSync.lastErrorBy)
            assertEquals(0, afterLocalUnarchiveSync.consecutiveFailures)
        }

    @Test
    fun `createCustomList normalizes want prefix and rejects empty symbol-only slugs`() = runTest {
        val catalog = repository.catalog
        val wantId = catalog.createCustomList("Weekend Watch", role = CollectionRole.WANT)
        assertEquals(systemCollectionUuid("want:weekend_watch"), wantId)
        assertEquals("want:weekend_watch", database.dao().getCollectionSpecById(wantId)?.slug)

        val prefixedLower =
            catalog.createCustomList("want:Weekend Watch", role = CollectionRole.WANT)
        val prefixedUpper =
            catalog.createCustomList("WANT:Weekend Watch", role = CollectionRole.WANT)
        assertEquals(wantId, prefixedLower)
        assertEquals(wantId, prefixedUpper)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { catalog.createCustomList("!!!") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { catalog.createCustomList("!!!", role = CollectionRole.WANT) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { catalog.createCustomList("want:!!!", role = CollectionRole.WANT) }
        }
    }

    @Test
    fun `repointIdentifiersToEntry and collection_specs metadata edits emit fresh CatalogStore snapshots`() =
        runTest {
            val dao = database.dao()
            val catalog = repository.catalog
            val listId = catalog.createCustomList("Stamp Watch")
            val entryA =
                catalog.upsertEntry(
                    UpsertCatalogEntryInput(
                        kind = EntryKind.MOVIE,
                        title = "Solaris",
                        identifiers = listOf(EntryIdentifierDto("imdb", "tt0069293")),
                    )
                )
            val entryB =
                catalog.upsertEntry(
                    UpsertCatalogEntryInput(
                        kind = EntryKind.MOVIE,
                        title = "Stalker",
                    )
                )
            catalog.addCollectionEntry(listId, entryA)
            catalog.addCollectionEntry(listId, entryB)
            val initialSpec = dao.getCollectionSpecById(listId)!!
            dao.upsertCollectionSpec(initialSpec.copy(color = "#112233"))

            val emissions = Channel<CatalogSnapshot>(Channel.UNLIMITED)
            val collectJob =
                launch(Dispatchers.Default) {
                    repository.snapshot.collect { emissions.send(it.catalog) }
                }
            try {
                awaitCatalogSnapshot(emissions) { snap ->
                    snap.entriesByCollectionId[listId].orEmpty().any {
                        it.entry.id == entryA && it.entry.identifiers.isNotEmpty()
                    }
                }

                // 1. Repoint identifier in-place bumps entry_identifiers revision trigger
                val identsBefore = dao.getRepositoryStamp().identsStamp
                dao.repointIdentifiersToEntry(entryA, entryB)
                assertNotEquals(identsBefore, dao.getRepositoryStamp().identsStamp)
                awaitCatalogSnapshot(emissions) { snap ->
                    snap.entriesByCollectionId[listId]
                        .orEmpty()
                        .firstOrNull { it.entry.id == entryB }
                        ?.entry
                        ?.identifiers
                        ?.any { it.value == "tt0069293" } == true
                }

                // 2. Update collection_specs.color bumps collection_specs revision trigger
                val specsBeforeColor = dao.getRepositoryStamp().specsStamp
                val updatedColorSpec = dao.getCollectionSpecById(listId)!!.copy(color = "#112234")
                dao.upsertCollectionSpec(updatedColorSpec)
                assertNotEquals(specsBeforeColor, dao.getRepositoryStamp().specsStamp)
                val afterColorSnap =
                    withContext(Dispatchers.Default) { withTimeout(5_000L) { emissions.receive() } }
                assertTrue(afterColorSnap.lists.any { it.collectionId == listId })

                // 3. Update collection_specs.slug bumps collection_specs revision trigger
                val specsBeforeSlug = dao.getRepositoryStamp().specsStamp
                dao.upsertCollectionSpec(updatedColorSpec.copy(slug = "stamp_watch_v2"))
                assertNotEquals(specsBeforeSlug, dao.getRepositoryStamp().specsStamp)
                awaitCatalogSnapshot(emissions) { snap ->
                    snap.lists.any { it.collectionId == listId && it.slug == "stamp_watch_v2" }
                }
            } finally {
                collectJob.cancel()
            }
        }

    private suspend fun awaitCatalogSnapshot(
        emissions: Channel<CatalogSnapshot>,
        predicate: (CatalogSnapshot) -> Boolean,
    ): CatalogSnapshot =
        withContext(Dispatchers.Default) {
            withTimeout(5_000L) {
                var matched: CatalogSnapshot? = null
                while (matched == null) {
                    val snap = emissions.receive()
                    if (predicate(snap)) {
                        matched = snap
                    }
                }
                matched
            }
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

@file:Suppress("TooManyFunctions", "LongMethod", "MagicNumber")

package com.grayvines.roche.data

import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.STARRED_SLUG
import com.grayvines.roche.core.TOMBSTONE_ENTITY_CONSUMPTION_LOG
import com.grayvines.roche.data.catalog.CollectionRole
import com.grayvines.roche.data.catalog.ConsumptionStatus
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.SyncCollectionSpecDto
import com.grayvines.roche.data.catalog.SyncConsumptionLogDto
import com.grayvines.roche.data.catalog.SyncEntryRecordDto
import com.grayvines.roche.data.catalog.UpsertCatalogEntryInput
import com.grayvines.roche.data.feeds.LocalFeedPoller
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.InboxItemRow
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.deleteEntryCascade
import com.grayvines.roche.data.local.upsertFeed
import com.grayvines.roche.data.local.upsertItem
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.remote.PublicWebFetcher
import com.grayvines.roche.data.remote.SyncTombstoneDto
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.data.sync.reconcilePeerSyncResponse
import com.grayvines.roche.media.OfflineCacheManager
import com.grayvines.roche.model.ItemStatus
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CatalogDiaryAndWorkspaceSearchTest {
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
        testEnv = TestRocheEnv(tempDir, "diary_workspace_settings.preferences_pb")
    }

    @AfterEach
    fun tearDown() {
        testEnv.close()
    }

    @Test
    fun `CatalogStore updateConsumptionLog and deleteConsumptionLog sync via HLC LWW and tombstones`() =
        runTest {
            val dao = database.dao()
            val entryId =
                repository.catalog.logEntry(
                    input =
                        UpsertCatalogEntryInput(
                            kind = EntryKind.ALBUM,
                            title = "Remain in Light",
                            creators = listOf("Talking Heads"),
                            releaseYear = 1980,
                        ),
                    status = ConsumptionStatus.STARTED,
                    notes = "First listen",
                )
            val initialLog = dao.getAllConsumptionLogs().single { it.entryId == entryId }
            assertEquals("started", initialLog.status)
            assertEquals("First listen", initialLog.notes)
            assertTrue(initialLog.isLocalDirty)

            // Update status, notes, and timestamp locally
            val updated =
                repository.catalog.updateConsumptionLog(
                    logId = initialLog.id,
                    status = ConsumptionStatus.CONSUMED,
                    notes = "Poly-rhythmic classic",
                    tsIso = "2026-08-15T20:00:00Z",
                )
            assertTrue(updated)
            val afterUpdate = dao.getConsumptionLogById(initialLog.id)!!
            assertEquals("consumed", afterUpdate.status)
            assertEquals("Poly-rhythmic classic", afterUpdate.notes)
            assertEquals("2026-08-15T20:00:00Z", afterUpdate.ts)
            assertTrue(afterUpdate.hlc > initialLog.hlc)
            assertTrue(afterUpdate.isLocalDirty)

            // Stale remote HLC must NOT overwrite our newer local diary edit
            reconcilePeerSyncResponse(
                dao = dao,
                response =
                    PeerSyncResponseDto(
                        serverPeerId = "server",
                        syncSeq = 1L,
                        serverHlc = "000000000000100-0000-server",
                        syncTimestamp = "2026-08-15T20:01:00Z",
                        consumptionLog =
                            listOf(
                                SyncConsumptionLogDto(
                                    id = initialLog.id,
                                    entryId = entryId,
                                    ts = "2026-08-15T19:00:00Z",
                                    status = "started",
                                    notes = "Stale remote note",
                                    hlc = "000000000000100-0000-server",
                                )
                            ),
                    ),
            )
            assertEquals("Poly-rhythmic classic", dao.getConsumptionLogById(initialLog.id)?.notes)

            // Newer remote HLC DOES update the existing consumption_log row (not ignored!)
            val newerRemoteHlc = "999999999999900-0000-server"
            reconcilePeerSyncResponse(
                dao = dao,
                response =
                    PeerSyncResponseDto(
                        serverPeerId = "server",
                        syncSeq = 2L,
                        serverHlc = newerRemoteHlc,
                        syncTimestamp = "2026-08-16T09:00:00Z",
                        consumptionLog =
                            listOf(
                                SyncConsumptionLogDto(
                                    id = initialLog.id,
                                    entryId = entryId,
                                    ts = "2026-08-16T08:00:00Z",
                                    status = "consumed",
                                    notes = "Edited on web",
                                    hlc = newerRemoteHlc,
                                )
                            ),
                    ),
            )
            val afterRemoteEdit = dao.getConsumptionLogById(initialLog.id)!!
            assertEquals("Edited on web", afterRemoteEdit.notes)
            assertEquals("2026-08-16T08:00:00Z", afterRemoteEdit.ts)
            assertFalse(afterRemoteEdit.isLocalDirty)

            // clearNotes wipes notes to null
            assertTrue(
                repository.catalog.updateConsumptionLog(logId = initialLog.id, clearNotes = true)
            )
            assertNull(dao.getConsumptionLogById(initialLog.id)?.notes)

            // Local delete records a dirty consumption_log tombstone
            assertTrue(repository.catalog.deleteConsumptionLog(initialLog.id))
            assertNull(dao.getConsumptionLogById(initialLog.id))
            val tombstone = dao.getTombstone(TOMBSTONE_ENTITY_CONSUMPTION_LOG, initialLog.id)
            assertNotNull(tombstone)
            assertTrue(tombstone!!.isLocalDirty)

            // Incoming remote consumption_log tombstone deletes a second log
            val secondEntryId =
                repository.catalog.logEntry(
                    input = UpsertCatalogEntryInput(kind = EntryKind.BOOK, title = "Dune"),
                    status = ConsumptionStatus.CONSUMED,
                    notes = "Desert planet",
                )
            val secondLog = dao.getAllConsumptionLogs().single { it.entryId == secondEntryId }
            reconcilePeerSyncResponse(
                dao = dao,
                response =
                    PeerSyncResponseDto(
                        serverPeerId = "server",
                        syncSeq = 3L,
                        serverHlc = "999999999999950-0000-server",
                        syncTimestamp = "2026-08-17T00:00:00Z",
                        tombstones =
                            listOf(
                                SyncTombstoneDto(
                                    entityType = TOMBSTONE_ENTITY_CONSUMPTION_LOG,
                                    naturalKey = secondLog.id,
                                    deletedAt = "2026-08-17T00:00:00Z",
                                    hlc = "999999999999950-0000-server",
                                )
                            ),
                    ),
            )
            assertNull(dao.getConsumptionLogById(secondLog.id))
        }

    @Test
    fun `OfflineCacheManager enforces global disk quota archived-first oldest-next and tracks inFlightEntryIds`() =
        runTest {
            val dao = database.dao()
            val cacheDir = File(tempDir, "quota_cache")
            val itemsDir = File(cacheDir, "items").apply { mkdirs() }

            fun seedCachedItem(id: String, status: ItemStatus, sizeBytes: Int, mtimeMs: Long) {
                kotlinx.coroutines.runBlocking {
                    dao.upsertItem(
                        InboxItemRow(
                            id = id,
                            feedId = null,
                            feedTitle = "Saved",
                            title = "Item $id",
                            url = "https://example.com/$id",
                            author = null,
                            summary = null,
                            contentText = "Body $id",
                            contentHtml = "<p>Body $id</p>",
                            contentType = "article",
                            status = status.wireValue,
                            isFavorite = false,
                            isCachedOffline = true,
                            publishedAt = "2026-06-01T10:00:00Z",
                            createdAt = "2026-06-01T10:00:00Z",
                            readProgress = 0.0,
                            thumbnailMediaId = null,
                            duration = null,
                        )
                    )
                }
                val dir = File(itemsDir, id).apply { mkdirs() }
                val file = File(dir, "blob.bin")
                file.writeBytes(ByteArray(sizeBytes) { 7 })
                file.setLastModified(mtimeMs)
                dir.setLastModified(mtimeMs)
            }

            // 4 items * 100 bytes = 400 bytes total; quota = 220 bytes -> must evict 2 items
            seedCachedItem("item-archived-new", ItemStatus.ARCHIVED, 100, 4_000L)
            seedCachedItem("item-unread-oldest", ItemStatus.UNREAD, 100, 1_000L)
            seedCachedItem("item-unread-mid", ItemStatus.UNREAD, 100, 2_000L)
            seedCachedItem("item-protected", ItemStatus.ARCHIVED, 100, 500L)

            val transportStarted = CountDownLatch(1)
            val allowDownloadFinish = CountDownLatch(1)
            val slowTransport =
                com.grayvines.roche.data.remote.HttpTransport {
                    transportStarted.countDown()
                    allowDownloadFinish.await(5, TimeUnit.SECONDS)
                    HttpResponse(statusCode = 200, body = "", bodyBytes = byteArrayOf(1, 2, 3, 4))
                }
            val manager =
                OfflineCacheManager(
                    dao = dao,
                    serverApi = tailnetServerApi(slowTransport),
                    publicWebFetcher = PublicWebFetcher(slowTransport),
                    settings = settings,
                    cacheDir = cacheDir,
                    maxOfflineCacheBytes = 220L,
                )

            assertEquals(400L, manager.totalOfflineCacheBytes())
            assertEquals(100L, manager.cachedBytesByEntryId()["item-unread-oldest"])

            manager.enforceOfflineDiskQuota(protectedItemId = "item-protected")

            // Evicts archived first (even though newer!), then oldest unarchived; keeps protected!
            assertFalse(File(itemsDir, "item-archived-new").exists())
            assertFalse(File(itemsDir, "item-unread-oldest").exists())
            assertTrue(File(itemsDir, "item-unread-mid").exists())
            assertTrue(File(itemsDir, "item-protected").exists())
            assertEquals(false, dao.getItemStateById("item-archived-new")?.isCachedOffline)
            assertEquals(false, dao.getItemStateById("item-unread-oldest")?.isCachedOffline)
            assertEquals(true, dao.getItemStateById("item-unread-mid")?.isCachedOffline)
            assertEquals(true, dao.getItemStateById("item-protected")?.isCachedOffline)
            assertEquals(200L, manager.totalOfflineCacheBytes())

            // Verify inFlightEntryIds tracks active download and clears on completion
            dao.upsertItem(
                InboxItemRow(
                    id = "item-inflight",
                    feedId = null,
                    feedTitle = "Saved",
                    title = "In Flight",
                    url = "https://example.com/inflight",
                    author = null,
                    summary = null,
                    contentText = "Body",
                    contentHtml = "<p>Body</p>",
                    contentType = "article",
                    status = "unread",
                    isFavorite = false,
                    isCachedOffline = false,
                    publishedAt = "2026-06-01T10:00:00Z",
                    createdAt = "2026-06-01T10:00:00Z",
                    readProgress = 0.0,
                    thumbnailMediaId = null,
                    duration = null,
                    thumbnailUrl = "https://cdn.example.com/hero.png",
                )
            )
            val downloadJob =
                async(Dispatchers.IO) {
                    manager.setOfflineCacheState("item-inflight", enabled = true)
                }
            assertTrue(transportStarted.await(5, TimeUnit.SECONDS))
            assertTrue("item-inflight" in manager.inFlightEntryIds.value)
            allowDownloadFinish.countDown()
            assertTrue(downloadJob.await())
            assertFalse("item-inflight" in manager.inFlightEntryIds.value)
        }

    @Test
    fun `searchWorkspace returns FTS snippets matched collections and matched diary notes`() =
        runTest {
            val dao = database.dao()
            dao.upsertItem(
                InboxItemRow(
                    id = "item-fts-1",
                    feedId = null,
                    feedTitle = "Saved",
                    title = "Consensus Notes",
                    url = "https://example.com/consensus",
                    author = "A. Author",
                    summary = "Intro",
                    contentText =
                        "Preface text. " +
                            "x".repeat(4_000) +
                            " HyperbolicQuorumToken42 makes replication fast.",
                    contentHtml = "<p>Preface text.</p>",
                    contentType = "article",
                    status = "unread",
                    isFavorite = false,
                    isCachedOffline = false,
                    publishedAt = "2026-06-01T10:00:00Z",
                    createdAt = "2026-06-01T10:00:00Z",
                    readProgress = 0.0,
                    thumbnailMediaId = null,
                    duration = null,
                )
            )
            val listId =
                repository.catalog.createCustomList(
                    title = "HyperbolicQuorumToken42 Reading List",
                    role = CollectionRole.LIST,
                )
            repository.catalog.logEntry(
                input = UpsertCatalogEntryInput(kind = EntryKind.BOOK, title = "Replication Guide"),
                status = ConsumptionStatus.CONSUMED,
                notes = "Mentions HyperbolicQuorumToken42 in chapter 4",
            )

            val result = repository.searchWorkspace("HyperbolicQuorumToken42")
            assertEquals(listOf("item-fts-1"), result.entries.map { it.id })
            assertTrue(
                result.snippetsByEntryId["item-fts-1"].orEmpty().contains("HyperbolicQuorumToken42")
            )
            assertTrue(listId in result.matchedCollectionIds)
            assertEquals(1, result.matchedDiaryLogs.size)
            assertTrue(
                result.matchedDiaryLogs.single().notes.orEmpty().contains("HyperbolicQuorumToken42")
            )

            assertEquals(WorkspaceSearchResult(), repository.searchWorkspace("   \u0000  "))
        }

    @Test
    fun `syncStatus persists across repository restarts flags auth errors and enrichCatalogEntryOnline syncs bio`() =
        runTest {
            settings.update {
                it.copy(serverUrl = "https://100.64.0.12:8080", bearerToken = "tok-initial")
            }
            var responseMode = "ok"
            val calledUrls = mutableListOf<String>()
            val transport =
                com.grayvines.roche.data.remote.HttpTransport { request ->
                    calledUrls.add("${request.method} ${request.url}")
                    when {
                        request.url.endsWith("/auth/pair") ->
                            HttpResponse(statusCode = 200, body = """{"token":"tok-repared"}""")
                        request.url.endsWith("/api/v1/catalog/entries/artist-1/enrich") ->
                            HttpResponse(statusCode = 200, body = """{"enriched":true}""")
                        request.url.endsWith("/api/v1/sync/peer") ->
                            if (responseMode == "auth_error") {
                                HttpResponse(
                                    statusCode = 401,
                                    body =
                                        """{"type":"urn:roche:problem:token-invalid","title":"Unauthorized","status":401,"detail":"Token revoked"}""",
                                )
                            } else {
                                HttpResponse(
                                    statusCode = 200,
                                    body =
                                        """{"server_peer_id":"server","sync_seq":1,"server_hlc":"001780000001000-0000-server","sync_timestamp":"2026-09-01T12:00:00Z","active_peers":[],""" +
                                            """"entries":[{"id":"artist-1","kind":"artist","title":"Talking Heads","summary":"American rock band formed in 1975.","hlc":"001780000001000-0000-server"}],""" +
                                            """"collection_entries":[],"collection_generators":[],"consumption_log":[],"comparisons":[],"tombstones":[]}""",
                                )
                            }
                        else -> HttpResponse(statusCode = 404, body = "{}")
                    }
                }

            val repo =
                mockRepository(
                    database = database,
                    settings = settings,
                    transport = transport,
                    networkTierProvider =
                        tailnetNetworkTierProvider {
                            com.grayvines.roche.data.remote.NetworkTier.OFFLINE
                        },
                )

            // 1. Successful enrichCatalogEntryOnline calls POST /enrich then syncWithServer
            assertTrue(repo.enrichCatalogEntryOnline("artist-1").getOrThrow())
            assertTrue(
                "POST https://100.64.0.12:8080/api/v1/catalog/entries/artist-1/enrich" in calledUrls
            )
            assertEquals(
                "American rock band formed in 1975.",
                database.dao().getEntryById("artist-1")?.summary,
            )
            val statusAfterOk = repo.syncStatus.value
            assertNotNull(statusAfterOk.lastSyncedAtIso)
            assertNull(statusAfterOk.lastError)
            assertFalse(statusAfterOk.isAuthError)

            // 2. Fresh repository hydrates persisted lastSyncedAtIso on prewarm
            val restartedRepo =
                mockRepository(
                    database = database,
                    settings = settings,
                    transport = transport,
                    networkTierProvider =
                        tailnetNetworkTierProvider {
                            com.grayvines.roche.data.remote.NetworkTier.OFFLINE
                        },
                )
            restartedRepo.prewarm()
            assertEquals(
                statusAfterOk.lastSyncedAtIso,
                restartedRepo.syncStatus.value.lastSyncedAtIso,
            )

            // 3. 401 marks isAuthError = true; pairWithCode clears it cleanly
            responseMode = "auth_error"
            assertTrue(restartedRepo.syncWithServer().isFailure)
            assertTrue(restartedRepo.syncStatus.value.isAuthError)
            assertNotNull(restartedRepo.syncStatus.value.lastError)

            restartedRepo.pairWithCode("https://100.64.0.12:8080", "123456", "Pixel").getOrThrow()
            assertFalse(restartedRepo.syncStatus.value.isAuthError)
            assertNull(restartedRepo.syncStatus.value.lastError)
        }

    @Test
    fun `partial-page mergedIntoId folds safely when canon arrives later and slug collisions never destroy existing specs`() =
        runTest {
            val dao = database.dao()
            repository.catalog.ensureSystemSeeded()

            // Page 1 delivers merged duplicate before canonical entry exists: MUST NOT crash on FK
            reconcilePeerSyncResponse(
                dao = dao,
                response =
                    PeerSyncResponseDto(
                        serverPeerId = "server",
                        syncSeq = 1L,
                        serverHlc = "001780000001000-0000-server",
                        syncTimestamp = "2026-09-01T10:00:00Z",
                        entries =
                            listOf(
                                SyncEntryRecordDto(
                                    id = "dup-album-1",
                                    kind = "album",
                                    title = "Remain in Light",
                                    mergedIntoId = "canon-album-1",
                                    hlc = "001780000001000-0000-server",
                                )
                            ),
                        consumptionLog =
                            listOf(
                                SyncConsumptionLogDto(
                                    id = "log-on-dup-1",
                                    entryId = "dup-album-1",
                                    ts = "2026-09-01T09:00:00Z",
                                    status = "consumed",
                                    notes = "Carried over",
                                    hlc = "001780000001000-0000-server",
                                )
                            ),
                    ),
            )
            assertNotNull(dao.getEntryById("dup-album-1"))
            assertEquals("dup-album-1", dao.getConsumptionLogById("log-on-dup-1")?.entryId)

            // Page 2 delivers canonical entry: deferred fold repoints log-on-dup-1 to canon-album-1
            reconcilePeerSyncResponse(
                dao = dao,
                response =
                    PeerSyncResponseDto(
                        serverPeerId = "server",
                        syncSeq = 2L,
                        serverHlc = "001780000002000-0000-server",
                        syncTimestamp = "2026-09-01T10:01:00Z",
                        entries =
                            listOf(
                                SyncEntryRecordDto(
                                    id = "canon-album-1",
                                    kind = "album",
                                    title = "Remain in Light",
                                    hlc = "001780000002000-0000-server",
                                )
                            ),
                    ),
            )
            assertEquals("canon-album-1", dao.getConsumptionLogById("log-on-dup-1")?.entryId)

            // Remote collection with colliding protected slug ("starred") must NOT delete STARRED
            // spec
            reconcilePeerSyncResponse(
                dao = dao,
                response =
                    PeerSyncResponseDto(
                        serverPeerId = "server",
                        syncSeq = 3L,
                        serverHlc = "001780000003000-0000-server",
                        syncTimestamp = "2026-09-01T10:02:00Z",
                        entries =
                            listOf(
                                SyncEntryRecordDto(
                                    id = "rogue-starred-id",
                                    kind = "collection",
                                    title = "Rogue Starred",
                                    hlc = "001780000003000-0000-server",
                                    collectionSpec =
                                        SyncCollectionSpecDto(
                                            slug = STARRED_SLUG,
                                            role = "list",
                                        ),
                                )
                            ),
                    ),
            )
            assertEquals(STARRED_SLUG, dao.getCollectionSpecById(STARRED_COLLECTION_ID)?.slug)
            assertNull(dao.getCollectionSpecById("rogue-starred-id")?.slug)

            // Direct upsertCollectionSpec with colliding slug keeps original owner intact
            dao.upsertEntry(
                EntryEntity(
                    id = "col-extra",
                    kind = "collection",
                    title = "Extra",
                    createdAt = "2026-09-01T10:00:00Z",
                    updatedAt = "2026-09-01T10:00:00Z",
                    hlc = "001780000003000-0000-local",
                )
            )
            dao.upsertCollectionSpec(
                CollectionSpecEntity(
                    collectionId = "col-extra",
                    slug = STARRED_SLUG,
                    role = "list",
                )
            )
            assertEquals(STARRED_SLUG, dao.getCollectionSpecById(STARRED_COLLECTION_ID)?.slug)
            assertNull(dao.getCollectionSpecById("col-extra")?.slug)

            // Case-insensitive slug collision excludes self cleanly without
            // SQLiteConstraintException
            dao.upsertEntry(
                EntryEntity(
                    id = "col-case-a",
                    kind = "collection",
                    title = "Case A",
                    createdAt = "2026-09-01T10:00:00Z",
                    updatedAt = "2026-09-01T10:00:00Z",
                    hlc = "001780000003000-0000-local",
                )
            )
            dao.upsertCollectionSpec(
                CollectionSpecEntity(
                    collectionId = "col-case-a",
                    slug = "Favorites",
                    role = "list",
                )
            )
            // Self-updating casing succeeds cleanly
            dao.upsertCollectionSpec(
                CollectionSpecEntity(
                    collectionId = "col-case-a",
                    slug = "favorites",
                    role = "list",
                )
            )
            assertEquals("favorites", dao.getCollectionSpecById("col-case-a")?.slug)
            // Colliding other collection clears only its own slug
            dao.upsertCollectionSpec(
                CollectionSpecEntity(
                    collectionId = "col-extra",
                    slug = "FAVORITES",
                    role = "list",
                )
            )
            assertEquals("favorites", dao.getCollectionSpecById("col-case-a")?.slug)
            assertNull(dao.getCollectionSpecById("col-extra")?.slug)
        }

    @Test
    fun `LocalFeedPoller does not resurrect deleted feed and quota never evicts localpdf entries`() =
        runTest {
            val dao = database.dao()
            val feed =
                FeedRow(
                    id = "feed-deleted-midpoll",
                    title = "Ephemeral Feed",
                    url = "https://feeds.example.com/ephemeral.xml",
                    siteUrl = null,
                    feedType = "rss",
                )
            dao.upsertFeed(feed)
            val fetcherDeletingFeedMidRequest = PublicWebFetcher {
                kotlinx.coroutines.runBlocking { dao.deleteEntryCascade("feed-deleted-midpoll") }
                HttpResponse(
                    statusCode = 200,
                    body =
                        """<rss version="2.0"><channel><title>Ephemeral</title>""" +
                            """<item><title>Should Not Resurrect</title><link>https://feeds.example.com/p1</link></item>""" +
                            """</channel></rss>""",
                )
            }
            LocalFeedPoller.pollFeed(
                dao = dao,
                publicWebFetcher = fetcherDeletingFeedMidRequest,
                feed = feed,
                localPeerId = "peer-1",
                writeLock = database.writeLock,
            )
            assertNull(dao.getFeedById("feed-deleted-midpoll"))
            assertTrue(dao.getAllItems().none { it.feedId == "feed-deleted-midpoll" })

            // Local-only shared PDFs (`localpdf://...`) are never evicted by global disk quota
            val cacheDir = File(tempDir, "localpdf_quota")
            val itemsDir = File(cacheDir, "items").apply { mkdirs() }
            dao.upsertItem(
                InboxItemRow(
                    id = "item-local-pdf",
                    feedId = null,
                    feedTitle = "Saved",
                    title = "Private Local PDF",
                    url = "localpdf://12345678-1234-1234-1234-1234567890ab/book.pdf",
                    author = null,
                    summary = null,
                    contentText = null,
                    contentHtml = null,
                    contentType = "pdf",
                    status = "archived",
                    isFavorite = false,
                    isCachedOffline = true,
                    publishedAt = "2026-06-01T10:00:00Z",
                    createdAt = "2026-06-01T10:00:00Z",
                    readProgress = 0.0,
                    thumbnailMediaId = null,
                    duration = null,
                )
            )
            val pdfFile =
                File(File(itemsDir, "item-local-pdf").apply { mkdirs() }, "shared_document.pdf")
            pdfFile.writeBytes(ByteArray(300) { 1 })

            val manager =
                OfflineCacheManager(
                    dao = dao,
                    serverApi = tailnetServerApi { HttpResponse(statusCode = 200, body = "{}") },
                    publicWebFetcher =
                        PublicWebFetcher { HttpResponse(statusCode = 200, body = "{}") },
                    settings = settings,
                    cacheDir = cacheDir,
                    maxOfflineCacheBytes = 50L,
                    writeLock = database.writeLock,
                )
            manager.enforceOfflineDiskQuota()
            assertTrue(pdfFile.isFile)
            assertEquals(true, dao.getItemStateById("item-local-pdf")?.isCachedOffline)
        }
}

@file:Suppress(
    "TooManyFunctions",
    "LongMethod",
    "MagicNumber",
    "LargeClass",
    "StringLiteralDuplication",
)

package com.grayvines.roche.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.grayvines.roche.core.HlcTimestamp
import com.grayvines.roche.core.MAX_CLOCK_DRIFT_MS
import com.grayvines.roche.core.TOMBSTONE_ENTITY_CONSUMPTION_LOG
import com.grayvines.roche.core.TOMBSTONE_ENTITY_ENTRY
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.core.canonicalizeSyncUrl
import com.grayvines.roche.core.deterministicFeedId
import com.grayvines.roche.core.deterministicItemId
import com.grayvines.roche.core.deterministicTagId
import com.grayvines.roche.core.formatUtcMillisIso
import com.grayvines.roche.core.itemNaturalKey
import com.grayvines.roche.data.catalog.CatalogStore
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.SyncCollectionEntryDto
import com.grayvines.roche.data.catalog.SyncConsumptionLogDto
import com.grayvines.roche.data.catalog.SyncEntryContentDto
import com.grayvines.roche.data.catalog.SyncEntryRecordDto
import com.grayvines.roche.data.catalog.SyncEntryStateDto
import com.grayvines.roche.data.catalog.UpsertCatalogEntryInput
import com.grayvines.roche.data.feeds.LocalFeedPoller
import com.grayvines.roche.data.feeds.ParsedFeedBatch
import com.grayvines.roche.data.feeds.ParsedFeedCandidateItem
import com.grayvines.roche.data.feeds.canonicalizeEntryUrl
import com.grayvines.roche.data.feeds.enrichWantToReadItemOnDevice
import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.CollectionGeneratorEntity
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.ConsumptionLogEntity
import com.grayvines.roche.data.local.EntryContentEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryStateEntity
import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.data.local.nextLocalHlc
import com.grayvines.roche.data.local.upsertFeed
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.remote.ProblemDetailsException
import com.grayvines.roche.data.remote.ResponseTooLargeException
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.remote.RocheProblemTypes
import com.grayvines.roche.data.remote.rocheJson
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.data.sync.MAX_PEER_SYNC_PAYLOAD_BYTES
import com.grayvines.roche.data.sync.MIN_SERVER_PAYLOAD_BYTES
import com.grayvines.roche.data.sync.NetworkTier
import com.grayvines.roche.data.sync.PeerSyncCoordinator
import com.grayvines.roche.data.sync.PeerSyncJobTracker
import com.grayvines.roche.data.sync.PeerSyncScheduler
import com.grayvines.roche.data.sync.SyncTombstoneDto
import com.grayvines.roche.data.sync.compute413RetryPayloadBudget
import com.grayvines.roche.data.sync.isOversizedResponseError
import com.grayvines.roche.data.sync.isPayloadTooLargeProblem
import com.grayvines.roche.data.sync.reconcilePeerSyncResponse
import com.grayvines.roche.data.sync.resolveOrSynthesizeHlc
import com.grayvines.roche.isAllowedSharedContentUri
import com.grayvines.roche.model.ContentType
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DataSyncRound2FixesTest {
    private lateinit var tempDir: File
    private lateinit var database: RocheDatabase

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("roche-r2-test").toFile()
        database =
            Room.inMemoryDatabaseBuilder<RocheDatabase>().setDriver(BundledSQLiteDriver()).build()
    }

    @AfterEach
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `isAllowedSharedContentUri blocks userInfo port and percent-encoded authority bypasses`() {
        val ownPkg = "com.grayvines.roche"
        assertFalse(isAllowedSharedContentUri("content", "0@$ownPkg.fileprovider", ownPkg))
        assertFalse(isAllowedSharedContentUri("content", "0@$ownPkg", ownPkg))
        assertFalse(isAllowedSharedContentUri("content", "10@org.other.alias", ownPkg) { ownPkg })
        assertFalse(
            isAllowedSharedContentUri("content", "0@com.android.providers.downloads", ownPkg) {
                "com.android.providers.downloads"
            }
        )
        assertFalse(isAllowedSharedContentUri("content", "$ownPkg.fileprovider:443", ownPkg))
        assertFalse(isAllowedSharedContentUri("content", "0%40$ownPkg.fileprovider", ownPkg))
        assertFalse(
            isAllowedSharedContentUri("content", "org.other.alias", ownPkg) { ownPkg.uppercase() }
        )
        assertTrue(
            isAllowedSharedContentUri(
                "content",
                "com.android.providers.downloads.documents",
                ownPkg,
            ) {
                "com.android.providers.downloads"
            }
        )
    }

    @Test
    fun `resolveOrSynthesizeHlc clamps far-future remote HLC and fallback timestamps`() = runTest {
        val nowMs = 1_750_000_000_000L
        val maxAllowed = nowMs + MAX_CLOCK_DRIFT_MS

        val withinDrift = HlcTimestamp.create(maxAllowed, 0x0007, "server").encode()
        assertEquals(
            withinDrift,
            resolveOrSynthesizeHlc(
                rawHlc = withinDrift,
                fallbackIso = null,
                nowIso = "2025-06-15T00:00:00Z",
                peerId = "server",
                nowMs = nowMs,
            ),
        )

        val farFuture = HlcTimestamp.create(maxAllowed + 86_400_000L, 0x0042, "rogue").encode()
        val clamped =
            HlcTimestamp.parse(
                resolveOrSynthesizeHlc(
                    rawHlc = farFuture,
                    fallbackIso = null,
                    nowIso = "2025-06-15T00:00:00Z",
                    peerId = "server",
                    nowMs = nowMs,
                )
            )!!
        assertEquals(maxAllowed, clamped.wallMs)
        assertEquals(0, clamped.counter)
        assertEquals("rogue", clamped.nodeId)

        val fallbackClamped =
            HlcTimestamp.parse(
                resolveOrSynthesizeHlc(
                    rawHlc = "",
                    fallbackIso = "2099-01-01T00:00:00Z",
                    nowIso = "2025-06-15T00:00:00Z",
                    peerId = "server",
                    nowMs = nowMs,
                )
            )!!
        assertEquals(maxAllowed, fallbackClamped.wallMs)
        assertEquals(0, fallbackClamped.counter)

        // End-to-end: a far-future remote entry HLC is clamped so local writes can beat it
        val dao = database.dao()
        val beforeMs = System.currentTimeMillis()
        val rogueHlc = HlcTimestamp.create(beforeMs + 100L * 86_400_000L, 0x002a, "rogue").encode()
        reconcilePeerSyncResponse(
            dao = dao,
            response =
                PeerSyncResponseDto(
                    serverPeerId = "rogue",
                    serverHlc = rogueHlc,
                    syncTimestamp = Instant.ofEpochMilli(beforeMs).toString(),
                    entries =
                        listOf(
                            SyncEntryRecordDto(
                                id = "entry-drift",
                                kind = "article",
                                title = "Future Entry",
                                url = "https://example.com/future",
                                hlc = rogueHlc,
                            )
                        ),
                ),
            localPeerId = "android-peer-1",
        )
        val storedHlc = HlcTimestamp.parse(dao.getEntryById("entry-drift")!!.hlc)!!
        assertEquals(0, storedHlc.counter)
        assertTrue(storedHlc.wallMs <= System.currentTimeMillis() + MAX_CLOCK_DRIFT_MS + 1_000L)
        val localNext = nextLocalHlc(dao, "android-peer-1")
        assertTrue(localNext > storedHlc.encode())
    }

    @Test
    fun `consumption_log tombstones delete LWW suppress stale resurrection and clear on newer`() =
        runTest {
            val dao = database.dao()
            val catalog = CatalogStore(dao = dao, localPeerIdProvider = { "android-peer-1" })
            catalog.ensureSystemSeeded()
            val nowIso = "2026-11-18T12:00:00Z"
            val baseMs = System.currentTimeMillis() - 60_000L
            val hlcOld = HlcTimestamp.create(baseMs, 0, "android-peer-1").encode()
            val hlcTomb = HlcTimestamp.create(baseMs + 10_000L, 0, "server").encode()
            val hlcNewer = HlcTimestamp.create(baseMs + 20_000L, 0, "android-peer-1").encode()
            val hlcResurrect = HlcTimestamp.create(baseMs + 30_000L, 0, "server").encode()

            val bookId =
                catalog.upsertEntry(UpsertCatalogEntryInput(kind = EntryKind.BOOK, title = "Dune"))
            dao.upsertConsumptionLog(
                ConsumptionLogEntity(
                    id = "log-old",
                    entryId = bookId,
                    ts = nowIso,
                    status = "consumed",
                    createdAt = nowIso,
                    hlc = hlcOld,
                )
            )
            dao.upsertConsumptionLog(
                ConsumptionLogEntity(
                    id = "log-newer",
                    entryId = bookId,
                    ts = nowIso,
                    status = "consumed",
                    createdAt = nowIso,
                    hlc = hlcNewer,
                )
            )

            reconcilePeerSyncResponse(
                dao = dao,
                response =
                    PeerSyncResponseDto(
                        serverPeerId = "server",
                        syncTimestamp = nowIso,
                        tombstones =
                            listOf(
                                SyncTombstoneDto(
                                    entityType = TOMBSTONE_ENTITY_CONSUMPTION_LOG,
                                    naturalKey = "log-old",
                                    deletedAt = nowIso,
                                    hlc = hlcTomb,
                                ),
                                SyncTombstoneDto(
                                    entityType = TOMBSTONE_ENTITY_CONSUMPTION_LOG,
                                    naturalKey = "log-newer",
                                    deletedAt = nowIso,
                                    hlc = hlcTomb,
                                ),
                            ),
                    ),
                localPeerId = "android-peer-1",
                catalogStore = catalog,
            )

            assertNull(dao.getConsumptionLogById("log-old"))
            assertNotNull(dao.getTombstone(TOMBSTONE_ENTITY_CONSUMPTION_LOG, "log-old"))
            assertNotNull(dao.getConsumptionLogById("log-newer"))
            assertNull(dao.getTombstone(TOMBSTONE_ENTITY_CONSUMPTION_LOG, "log-newer"))

            // Stale incoming log-old does not resurrect
            reconcilePeerSyncResponse(
                dao = dao,
                response =
                    PeerSyncResponseDto(
                        serverPeerId = "server",
                        syncTimestamp = nowIso,
                        consumptionLog =
                            listOf(
                                SyncConsumptionLogDto(
                                    id = "log-old",
                                    entryId = bookId,
                                    ts = nowIso,
                                    status = "consumed",
                                    hlc = hlcOld,
                                )
                            ),
                    ),
                localPeerId = "android-peer-1",
                catalogStore = catalog,
            )
            assertNull(dao.getConsumptionLogById("log-old"))
            assertNotNull(dao.getTombstone(TOMBSTONE_ENTITY_CONSUMPTION_LOG, "log-old"))

            // Newer incoming log-old resurrects and clears tombstone
            reconcilePeerSyncResponse(
                dao = dao,
                response =
                    PeerSyncResponseDto(
                        serverPeerId = "server",
                        syncTimestamp = nowIso,
                        consumptionLog =
                            listOf(
                                SyncConsumptionLogDto(
                                    id = "log-old",
                                    entryId = bookId,
                                    ts = nowIso,
                                    status = "consumed",
                                    hlc = hlcResurrect,
                                )
                            ),
                    ),
                localPeerId = "android-peer-1",
                catalogStore = catalog,
            )
            assertNotNull(dao.getConsumptionLogById("log-old"))
            assertNull(dao.getTombstone(TOMBSTONE_ENTITY_CONSUMPTION_LOG, "log-old"))
        }

    @Test
    fun `stale manual Want-to-Read items cannot resurrect across pure and catalog-history entries`() =
        runTest {
            val dao = database.dao()
            val catalog = CatalogStore(dao = dao, localPeerIdProvider = { "android-peer-1" })
            catalog.ensureSystemSeeded()
            val nowIso = "2026-11-18T12:00:00Z"
            val baseMs = System.currentTimeMillis() - 60_000L
            val hlcStale = HlcTimestamp.create(baseMs, 0, "server").encode()
            val hlcTomb = HlcTimestamp.create(baseMs + 10_000L, 0, "android-peer-1").encode()
            val hlcResave = HlcTimestamp.create(baseMs + 20_000L, 0, "server").encode()

            val pureUrl = "https://example.com/pure-wtr"
            val pureId = deterministicItemId(null, canonicalizeSyncUrl(pureUrl))
            dao.upsertTombstone(
                TombstoneEntity(
                    entityType = "item",
                    naturalKey = itemNaturalKey(null, pureUrl),
                    deletedAt = nowIso,
                    hlc = hlcTomb,
                    isLocalDirty = false,
                )
            )

            // Entry with catalog history that was previously deleted from reader
            val histUrl = "https://example.com/hist-wtr"
            val histId = deterministicItemId(null, canonicalizeSyncUrl(histUrl))
            dao.upsertEntry(
                EntryEntity(
                    id = histId,
                    kind = EntryKind.ARTICLE.wireValue,
                    title = "History Article",
                    url = histUrl,
                    createdAt = nowIso,
                    updatedAt = nowIso,
                    hlc = hlcStale,
                )
            )
            dao.upsertEntryContent(EntryContentEntity(entryId = histId, contentType = "article"))
            dao.upsertEntryState(
                EntryStateEntity(
                    entryId = histId,
                    inboxState = "none",
                    updatedAt = nowIso,
                    hlc = hlcStale,
                )
            )
            dao.upsertConsumptionLog(
                ConsumptionLogEntity(
                    id = "log-hist-1",
                    entryId = histId,
                    ts = nowIso,
                    status = "consumed",
                    createdAt = nowIso,
                    hlc = hlcStale,
                )
            )

            // Apply remote item tombstone to histId, then send stale resurrection for both
            reconcilePeerSyncResponse(
                dao = dao,
                response =
                    PeerSyncResponseDto(
                        serverPeerId = "server",
                        syncTimestamp = nowIso,
                        tombstones =
                            listOf(
                                SyncTombstoneDto(
                                    entityType = "item",
                                    naturalKey = itemNaturalKey(null, histUrl),
                                    deletedAt = nowIso,
                                    hlc = hlcTomb,
                                )
                            ),
                    ),
                localPeerId = "android-peer-1",
                catalogStore = catalog,
            )
            assertNull(dao.getEntryContentById(histId))
            assertEquals(hlcTomb, dao.getEntryStateById(histId)!!.hlc)

            reconcilePeerSyncResponse(
                dao = dao,
                response =
                    PeerSyncResponseDto(
                        serverPeerId = "server",
                        syncTimestamp = nowIso,
                        entries =
                            listOf(
                                SyncEntryRecordDto(
                                    id = pureId,
                                    kind = "article",
                                    title = "Pure WTR",
                                    url = pureUrl,
                                    hlc = hlcStale,
                                    content = SyncEntryContentDto(contentType = "article"),
                                    state = SyncEntryStateDto(inboxState = "none", hlc = hlcStale),
                                ),
                                SyncEntryRecordDto(
                                    id = histId,
                                    kind = "article",
                                    title = "History Article",
                                    url = histUrl,
                                    hlc = hlcStale,
                                    content = SyncEntryContentDto(contentType = "article"),
                                    state = SyncEntryStateDto(inboxState = "none", hlc = hlcStale),
                                ),
                            ),
                        collectionEntries =
                            listOf(
                                SyncCollectionEntryDto(
                                    collectionId = WANT_TO_READ_COLLECTION_ID,
                                    entryId = pureId,
                                    addedAt = nowIso,
                                    hlc = hlcStale,
                                ),
                                SyncCollectionEntryDto(
                                    collectionId = WANT_TO_READ_COLLECTION_ID,
                                    entryId = histId,
                                    addedAt = nowIso,
                                    hlc = hlcStale,
                                ),
                            ),
                    ),
                localPeerId = "android-peer-1",
                catalogStore = catalog,
            )

            assertNull(dao.getEntryById(pureId))
            assertNull(dao.getCollectionEntry(WANT_TO_READ_COLLECTION_ID, pureId))
            assertNotNull(dao.getEntryById(histId))
            assertNull(dao.getEntryContentById(histId))
            assertNull(dao.getCollectionEntry(WANT_TO_READ_COLLECTION_ID, histId))
            assertNotNull(dao.getTombstone("item", itemNaturalKey(null, histUrl)))

            // Newer re-save clears tombstone and resurrects cleanly
            reconcilePeerSyncResponse(
                dao = dao,
                response =
                    PeerSyncResponseDto(
                        serverPeerId = "server",
                        syncTimestamp = nowIso,
                        entries =
                            listOf(
                                SyncEntryRecordDto(
                                    id = pureId,
                                    kind = "article",
                                    title = "Pure WTR Resaved",
                                    url = pureUrl,
                                    hlc = hlcResave,
                                    content = SyncEntryContentDto(contentType = "article"),
                                    state = SyncEntryStateDto(inboxState = "none", hlc = hlcResave),
                                )
                            ),
                        collectionEntries =
                            listOf(
                                SyncCollectionEntryDto(
                                    collectionId = WANT_TO_READ_COLLECTION_ID,
                                    entryId = pureId,
                                    addedAt = nowIso,
                                    hlc = hlcResave,
                                )
                            ),
                    ),
                localPeerId = "android-peer-1",
                catalogStore = catalog,
            )
            assertNotNull(dao.getEntryById(pureId))
            assertNull(dao.getTombstone("item", itemNaturalKey(null, pureUrl)))
        }

    @Test
    fun `PeerSyncCoordinator quarantines 1-entry 413 poison pill and drains remaining entries`() =
        runTest {
            val dao = database.dao()
            val catalog = CatalogStore(dao)
            catalog.ensureSystemSeeded()
            val listId = catalog.createCustomList("Poison Pill List")
            val nowIso = "2026-11-18T12:00:00Z"

            val poisonId = "entry-poison"
            val okIds = listOf("entry-ok-1", "entry-ok-2", "entry-ok-3")
            for (id in listOf(poisonId) + okIds) {
                val hlc = nextLocalHlc(dao, "android-test")
                dao.upsertEntry(
                    EntryEntity(
                        id = id,
                        kind = EntryKind.ARTICLE.wireValue,
                        title = "Title $id",
                        url = "https://example.com/$id",
                        summary = if (id == poisonId) "z".repeat(400_000) else "small body",
                        createdAt = nowIso,
                        updatedAt = nowIso,
                        hlc = hlc,
                        isLocalDirty = true,
                    )
                )
            }
            val edgeHlc = nextLocalHlc(dao, "android-test")
            dao.upsertCollectionEntry(
                CollectionEntryEntity(
                    collectionId = listId,
                    entryId = poisonId,
                    position = null,
                    addedAt = nowIso,
                    hlc = edgeHlc,
                    isLocalDirty = true,
                )
            )
            dao.upsertConsumptionLog(
                ConsumptionLogEntity(
                    id = "log-poison",
                    entryId = poisonId,
                    ts = nowIso,
                    status = "consumed",
                    createdAt = nowIso,
                    hlc = edgeHlc,
                    isLocalDirty = true,
                )
            )

            val receivedRequests = CopyOnWriteArrayList<JsonObject>()
            val uploadedIds = LinkedHashSet<String>()
            var seq = 20L
            val apiClient = RocheApiClient { req ->
                val parsed = rocheJson.parseToJsonElement(req.body.orEmpty()).jsonObject
                receivedRequests.add(parsed)
                val entriesArr = parsed["entries"]?.jsonArray.orEmpty()
                val containsPoison = entriesArr.any {
                    it.jsonObject["id"]?.jsonPrimitive?.content == poisonId
                }
                if (containsPoison) {
                    HttpResponse(
                        statusCode = 413,
                        body = """{"title":"Payload Too Large","status":413}""",
                    )
                } else {
                    entriesArr.forEach { el ->
                        uploadedIds.add(el.jsonObject["id"]!!.jsonPrimitive.content)
                    }
                    seq += 1L
                    HttpResponse(
                        statusCode = 200,
                        body =
                            """{"server_peer_id":"server","sync_timestamp":"$nowIso","sync_seq":$seq,"has_more":false,"reset_required":false}""",
                    )
                }
            }

            val coordinator =
                PeerSyncCoordinator(
                    dao = dao,
                    apiClient = apiClient,
                    networkTier = NetworkTier.WIFI,
                    catalogStore = catalog,
                )
            coordinator.syncAndCooperativePoll(
                currentSettings =
                    Settings(
                        serverUrl = "http://127.0.0.1:8080",
                        bearerToken = "tok",
                        peerId = "android-test",
                        deviceName = "Test",
                        lastPeerSyncCursor = "10",
                    )
            )

            assertTrue(
                receivedRequests.size < 12,
                "Expected bounded quarantine instead of ~44 retry requests, got ${receivedRequests.size}",
            )
            assertTrue(uploadedIds.containsAll(okIds))
            assertFalse(poisonId in uploadedIds)
            for (id in okIds) {
                assertFalse(dao.getEntryById(id)!!.isLocalDirty)
            }
            assertTrue(dao.getEntryById(poisonId)!!.isLocalDirty)
            assertTrue(dao.getCollectionEntry(listId, poisonId)!!.isLocalDirty)
            assertTrue(dao.getConsumptionLogById("log-poison")!!.isLocalDirty)
        }

    @Test
    fun `runPostPollPeerSync handles resetRequired and drains overflow entries`() = runTest {
        val dao = database.dao()
        val nowIso = "2026-11-18T12:00:00Z"
        val feedUrl = "https://blog.example.com/feed.xml"
        val feedId = deterministicFeedId(feedUrl)
        dao.upsertFeed(
            FeedRow(
                id = feedId,
                title = "Blog",
                url = feedUrl,
                feedType = "rss",
                pollInterval = 15,
                lastPolled = "2020-01-01T00:00:00Z",
                isActive = true,
            ),
            markDirty = false,
        )

        val rssXml =
            """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<rss version="2.0"><channel><title>Blog</title>""" +
                """<item><title>Post 1</title><link>https://blog.example.com/1</link><description>A</description></item>""" +
                """<item><title>Post 2</title><link>https://blog.example.com/2</link><description>B</description></item>""" +
                """</channel></rss>"""

        val cursorUpdates = CopyOnWriteArrayList<String>()
        val postPollSyncBodies = CopyOnWriteArrayList<JsonObject>()
        val uploadedPostUrls = LinkedHashSet<String>()
        val apiClient =
            buildPostPollResetTestClient(
                feedUrl = feedUrl,
                rssXml = rssXml,
                nowIso = nowIso,
                postPollSyncBodies = postPollSyncBodies,
                uploadedPostUrls = uploadedPostUrls,
            )

        val coordinator =
            PeerSyncCoordinator(
                dao = dao,
                apiClient = apiClient,
                networkTier = NetworkTier.WIFI,
                onSyncCursorUpdated = { cursorUpdates.add(it) },
            )
        coordinator.syncAndCooperativePoll(
            currentSettings =
                Settings(
                    serverUrl = "http://127.0.0.1:8080",
                    bearerToken = "tok",
                    peerId = "android-peer-1",
                    deviceName = "Pixel",
                    lastPeerSyncCursor = "10",
                )
        )

        assertTrue("0" in cursorUpdates, "Expected post-poll resetRequired to reset cursor to 0")
        assertTrue(
            postPollSyncBodies.drop(1).any {
                !it.containsKey("since_seq") || it["since_seq"]?.jsonPrimitive?.content == "0"
            }
        )
        assertTrue("https://blog.example.com/1" in uploadedPostUrls)
        assertTrue("https://blog.example.com/2" in uploadedPostUrls)
    }

    private fun buildPostPollResetTestClient(
        feedUrl: String,
        rssXml: String,
        nowIso: String,
        postPollSyncBodies: CopyOnWriteArrayList<JsonObject>,
        uploadedPostUrls: MutableSet<String>,
    ): RocheApiClient {
        var syncCallCount = 0
        var seq = 10L
        var postPollResetSent = false
        return RocheApiClient { req ->
            if (req.url == feedUrl) {
                HttpResponse(statusCode = 200, body = rssXml)
            } else {
                syncCallCount++
                val parsed = rocheJson.parseToJsonElement(req.body.orEmpty()).jsonObject
                when {
                    syncCallCount == 1 -> {
                        HttpResponse(
                            statusCode = 200,
                            body =
                                """{"server_peer_id":"server","sync_timestamp":"$nowIso","sync_seq":11,"has_more":false,"reset_required":false,"active_peers":[{"peer_id":"android-peer-1","weight":1.0,"network_tier":"wifi"}]}""",
                        )
                    }
                    !postPollResetSent -> {
                        postPollSyncBodies.add(parsed)
                        postPollResetSent = true
                        HttpResponse(
                            statusCode = 200,
                            body =
                                """{"server_peer_id":"server","sync_timestamp":"$nowIso","sync_seq":0,"has_more":false,"reset_required":true}""",
                        )
                    }
                    else -> {
                        postPollSyncBodies.add(parsed)
                        val entriesArr = parsed["entries"]?.jsonArray.orEmpty()
                        if (entriesArr.size > 1) {
                            HttpResponse(
                                statusCode = 413,
                                body = """{"title":"Payload Too Large","status":413}""",
                            )
                        } else {
                            entriesArr.forEach { el ->
                                el.jsonObject["url"]
                                    ?.jsonPrimitive
                                    ?.content
                                    ?.let(uploadedPostUrls::add)
                            }
                            seq += 1L
                            HttpResponse(
                                statusCode = 200,
                                body =
                                    """{"server_peer_id":"server","sync_timestamp":"$nowIso","sync_seq":$seq,"has_more":false,"reset_required":false}""",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `exhausted enabled generator does not bump HLC or mark dirty on sync`() = runTest {
        val dao = database.dao()
        val catalog = CatalogStore(dao = dao, localPeerIdProvider = { "android-peer-1" })
        catalog.ensureSystemSeeded()
        val listId = catalog.createCustomList("Empty Pool")
        val initialHlc =
            HlcTimestamp.create(System.currentTimeMillis() - 60_000L, 0, "server").encode()
        val initialUpdatedAt = "2026-11-18T10:00:00Z"
        dao.upsertCollectionGenerator(
            CollectionGeneratorEntity(
                collectionId = listId,
                enabled = true,
                currentEntryId = null,
                drawnAt = null,
                updatedAt = initialUpdatedAt,
                hlc = initialHlc,
                isLocalDirty = false,
            )
        )

        reconcilePeerSyncResponse(
            dao = dao,
            response =
                PeerSyncResponseDto(
                    serverPeerId = "server",
                    syncTimestamp = "2026-11-18T12:00:00Z",
                    hasMore = false,
                ),
            localPeerId = "android-peer-1",
            catalogStore = catalog,
        )

        val after = dao.getCollectionGenerator(listId)!!
        assertNull(after.currentEntryId)
        assertFalse(after.isLocalDirty)
        assertEquals(initialHlc, after.hlc)
        assertEquals(initialUpdatedAt, after.updatedAt)
    }

    @Test
    fun `addUrlToWantToRead rolls back localpdf entry and throws when PDF cache copy fails`() =
        runTest {
            val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val settings =
                    SettingsRepository(
                        PreferenceDataStoreFactory.create(
                            scope = dataStoreScope,
                            produceFile = { File(tempDir, "pdf_fail.preferences_pb") },
                        )
                    )
                settings.update { it.copy(peerId = "android-peer-pdf") }
                val repo =
                    RocheRepository(
                        database = database,
                        apiClient = RocheApiClient { HttpResponse(statusCode = 200, body = "{}") },
                        settings = settings,
                        cacheDir = tempDir,
                    )
                val badUrl = "localpdf://broken-doc-123"
                assertThrows(IllegalStateException::class.java) {
                    runBlocking {
                        repo.addUrlToWantToRead(
                            inputUrl = badUrl,
                            titleOverride = "Broken PDF",
                            forcedContentType = ContentType.PDF,
                            pdfInputStream =
                                ByteArrayInputStream("NOT-A-PDF".toByteArray(Charsets.UTF_8)),
                            awaitPdfCacheCopy = true,
                        )
                    }
                }
                val detId = deterministicItemId(null, badUrl)
                assertNull(database.dao().getEntryById(detId))
                assertNull(database.dao().getEntryStateById(detId))
                assertNull(database.dao().getEntryContentById(detId))
                assertNull(database.dao().getCollectionEntry(WANT_TO_READ_COLLECTION_ID, detId))
                assertTrue(database.dao().getAllTombstones().isEmpty())
            } finally {
                dataStoreScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }

    @Test
    fun `addUrlToWantToRead preserves existing cached localpdf when re-shared`() = runTest {
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val settings =
                SettingsRepository(
                    PreferenceDataStoreFactory.create(
                        scope = dataStoreScope,
                        produceFile = { File(tempDir, "pdf_reshare.preferences_pb") },
                    )
                )
            settings.update { it.copy(peerId = "android-peer-pdf-reshare") }
            val repo =
                RocheRepository(
                    database = database,
                    apiClient = RocheApiClient { HttpResponse(statusCode = 200, body = "{}") },
                    settings = settings,
                    cacheDir = tempDir,
                )
            val localUrl = "localpdf://existing-doc-456"
            val firstBytes =
                listOf("%PDF-1.7", "1 0 obj", "<<>>", "endobj", "").joinToString("\n").toByteArray()
            val secondBytes =
                listOf("%PDF-1.7", "2 0 obj", "<<>>", "endobj", "").joinToString("\n").toByteArray()
            repo.addUrlToWantToRead(
                inputUrl = localUrl,
                titleOverride = "Original Paper",
                forcedContentType = ContentType.PDF,
                pdfInputStream = ByteArrayInputStream(firstBytes),
                awaitPdfCacheCopy = true,
            )
            // Re-sharing the same localpdf URL must neither overwrite nor evict the cached PDF.
            repo.addUrlToWantToRead(
                inputUrl = localUrl,
                titleOverride = "Tampered Paper",
                forcedContentType = ContentType.PDF,
                pdfInputStream = ByteArrayInputStream(secondBytes),
                awaitPdfCacheCopy = true,
            )
            val detId = deterministicItemId(null, localUrl)
            val cachedFile = com.grayvines.roche.media.findCachedPdfFileForEntry(tempDir, detId)
            assertNotNull(cachedFile)
            assertEquals(String(firstBytes), cachedFile?.readText())
        } finally {
            dataStoreScope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `resolveFeedEffectiveHlc generates fresh peer HLC on dirty feed config update`() = runTest {
        val dao = database.dao()
        val feedUrl = "https://feeds.example.com/show.xml"
        val feedId = deterministicFeedId(feedUrl)
        val staleServerHlc =
            HlcTimestamp.create(System.currentTimeMillis() - 120_000L, 0, "server").encode()
        dao.upsertFeed(
            FeedRow(
                id = feedId,
                title = "feeds.example.com",
                url = feedUrl,
                feedType = "rss",
                hlc = staleServerHlc,
            ),
            markDirty = false,
            hlcOverride = staleServerHlc,
        )

        val storedBefore = dao.getFeedById(feedId)!!
        assertEquals(staleServerHlc, storedBefore.hlc)

        LocalFeedPoller.pollFeed(
                dao = dao,
                apiClient = RocheApiClient { HttpResponse(statusCode = 200, body = "") },
                feed = storedBefore,
                localPeerId = "android-peer-88",
                prefetchedBatch =
                    ParsedFeedBatch(
                        title = "Great Podcast",
                        description = "Weekly show",
                        language = "en",
                        siteUrl = "https://feeds.example.com",
                        items =
                            listOf(
                                ParsedFeedCandidateItem(
                                    title = "Ep 1",
                                    url = "https://feeds.example.com/ep1",
                                    author = "Host",
                                    publishedAt = "2026-11-18T10:00:00Z",
                                    summary = "Episode 1",
                                    contentHtml = "<p>Episode 1</p>",
                                    thumbnailUrl = null,
                                    contentType = "audio",
                                    audioUrl = "https://feeds.example.com/ep1.mp3",
                                    duration = "10:00",
                                )
                            ),
                    ),
            )
            .getOrThrow()

        val updatedFeed = dao.getFeedById(feedId)!!
        assertEquals("Great Podcast", updatedFeed.title)
        assertEquals("podcast", updatedFeed.feedType)
        assertTrue(updatedFeed.hlc > staleServerHlc)
        assertTrue(updatedFeed.hlc.endsWith("-android-peer-88"))
        assertTrue(dao.getEntryById(feedId)!!.hlc.endsWith("-android-peer-88"))
        val itemId = deterministicItemId(feedUrl, "https://feeds.example.com/ep1")
        assertTrue(dao.getEntryById(itemId)!!.hlc.endsWith("-android-peer-88"))
    }

    @Test
    fun `PeerSyncJobTracker suppresses onJobFinished for superseded same-ID job`() = runTest {
        val finishedTokens = CopyOnWriteArrayList<String>()
        val tracker =
            PeerSyncJobTracker<Pair<Int, String>>(
                jobIdOf = { it.first },
                onJobFinished = { pair, _ -> finishedTokens.add(pair.second) },
            )
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()

        tracker.start(this, PeerSyncScheduler.PEER_SYNC_IMMEDIATE_JOB_ID to "first") {
            firstGate.await()
        }
        testScheduler.runCurrent()

        tracker.start(this, PeerSyncScheduler.PEER_SYNC_IMMEDIATE_JOB_ID to "second") {
            secondGate.await()
        }
        testScheduler.runCurrent()
        assertTrue(finishedTokens.isEmpty())

        secondGate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("second"), finishedTokens)
    }

    @Test
    fun `local tag and enrichment write paths stamp configured peerId into HLCs`() = runTest {
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val settings =
                SettingsRepository(
                    PreferenceDataStoreFactory.create(
                        scope = dataStoreScope,
                        produceFile = { File(tempDir, "peer_id.preferences_pb") },
                    )
                )
            settings.update { it.copy(peerId = "android-peer-42") }
            val htmlClient = RocheApiClient {
                HttpResponse(
                    statusCode = 200,
                    body =
                        "<html><head><title>Enriched Title</title></head><body><article><p>Body text</p></article></body></html>",
                )
            }
            val repo =
                RocheRepository(
                    database = database,
                    apiClient = htmlClient,
                    settings = settings,
                    cacheDir = tempDir,
                )
            val dao = database.dao()

            repo.addUrlToWantToRead(
                inputUrl = "https://example.com/tagged-article",
                tagCsv = "systems",
            )
            val itemId = deterministicItemId(null, "https://example.com/tagged-article")
            val systemsTagId = deterministicTagId("systems")
            assertTrue(dao.getEntryById(systemsTagId)!!.hlc.endsWith("-android-peer-42"))
            assertTrue(
                dao.getCollectionEntry(systemsTagId, itemId)!!.hlc.endsWith("-android-peer-42")
            )

            repo.toggleTagOnItem(itemId, "databases")
            val dbTagId = deterministicTagId("databases")
            assertTrue(dao.getEntryById(dbTagId)!!.hlc.endsWith("-android-peer-42"))
            assertTrue(dao.getCollectionEntry(dbTagId, itemId)!!.hlc.endsWith("-android-peer-42"))

            val feedUrl = "https://feedtag.example.com/rss.xml"
            val feedId = deterministicFeedId(feedUrl)
            dao.upsertFeed(
                FeedRow(id = feedId, title = "Feed", url = feedUrl, feedType = "rss"),
                markDirty = false,
            )
            LocalFeedPoller.pollFeed(
                    dao = dao,
                    apiClient = htmlClient,
                    feed = dao.getFeedById(feedId)!!,
                    localPeerId = "android-peer-42",
                    prefetchedBatch =
                        ParsedFeedBatch(
                            title = "Feed",
                            description = null,
                            language = null,
                            siteUrl = null,
                            items =
                                listOf(
                                    ParsedFeedCandidateItem(
                                        title = "Feed Item",
                                        url = "https://feedtag.example.com/post-1",
                                        author = null,
                                        publishedAt = "2026-11-18T12:00:00Z",
                                        summary = "Summary",
                                        contentHtml = null,
                                        thumbnailUrl = null,
                                        contentType = "article",
                                    )
                                ),
                        ),
                )
                .getOrThrow()
            assertEquals(1, repo.applyTagToFeedItems(feedId, "feedbulk"))
            val feedBulkTagId = deterministicTagId("feedbulk")
            val feedItemId = deterministicItemId(feedUrl, "https://feedtag.example.com/post-1")
            assertTrue(dao.getEntryById(feedBulkTagId)!!.hlc.endsWith("-android-peer-42"))
            assertTrue(
                dao.getCollectionEntry(feedBulkTagId, feedItemId)!!.hlc.endsWith("-android-peer-42")
            )

            enrichWantToReadItemOnDevice(
                dao = dao,
                apiClient = htmlClient,
                itemId = itemId,
                url = "https://example.com/tagged-article",
                hasUserTitleOverride = false,
                nodeId = "android-peer-42",
            )
            assertEquals("Enriched Title", dao.getEntryById(itemId)!!.title)
            assertTrue(dao.getEntryById(itemId)!!.hlc.endsWith("-android-peer-42"))
        } finally {
            dataStoreScope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `PeerSyncCoordinator caps 413 retry budget from maxBytes and handles ResponseTooLargeException`() =
        runTest {
            assertTrue(
                isPayloadTooLargeProblem(
                    ProblemDetailsException(
                        statusCode = 400,
                        title = "Too Big",
                        detail = "Payload exceeds limit",
                        problemType = RocheProblemTypes.PAYLOAD_TOO_LARGE,
                    )
                )
            )
            assertTrue(
                isPayloadTooLargeProblem(
                    ProblemDetailsException(statusCode = 413, title = "Too Big", detail = "Legacy")
                )
            )
            assertEquals(
                60_000,
                compute413RetryPayloadBudget(MAX_PEER_SYNC_PAYLOAD_BYTES, 60_000L),
            )
            assertEquals(
                MIN_SERVER_PAYLOAD_BYTES,
                compute413RetryPayloadBudget(MAX_PEER_SYNC_PAYLOAD_BYTES, 128L),
            )
            assertEquals(30_000, compute413RetryPayloadBudget(60_000, 60_000L))
            assertEquals(
                MAX_PEER_SYNC_PAYLOAD_BYTES / 2,
                compute413RetryPayloadBudget(MAX_PEER_SYNC_PAYLOAD_BYTES, null),
            )
            assertTrue(isOversizedResponseError(ResponseTooLargeException(maxBytes = 1024L)))
            assertFalse(isOversizedResponseError(IOException("exceeds maximum allowed size")))

            val dao = database.dao()
            val nowIso = "2026-11-19T12:00:00Z"
            val entryIds = (0 until 8).map { "entry-maxbytes-$it" }
            for ((idx, id) in entryIds.withIndex()) {
                val hlc = nextLocalHlc(dao, "android-test")
                dao.upsertEntry(
                    EntryEntity(
                        id = id,
                        kind = EntryKind.ARTICLE.wireValue,
                        title = "Entry $idx",
                        url = "https://example.com/maxbytes/$idx",
                        summary = "a".repeat(25_000),
                        createdAt = nowIso,
                        updatedAt = nowIso,
                        hlc = hlc,
                        isLocalDirty = true,
                    )
                )
            }

            var server413Count = 0
            var seq = 30L
            val uploadedIds = LinkedHashSet<String>()
            val serverLimitBytes = 60_000L
            val cappedClient = RocheApiClient { req ->
                val rawBody = req.body.orEmpty()
                if (rawBody.toByteArray().size > serverLimitBytes) {
                    server413Count++
                    HttpResponse(
                        statusCode = 413,
                        body =
                            """{"type":"urn:roche:problem:payload-too-large","title":"Payload Too Large","status":413,"detail":"Body exceeds 60000 bytes","max_bytes":$serverLimitBytes}""",
                    )
                } else {
                    val parsed = rocheJson.parseToJsonElement(rawBody).jsonObject
                    parsed["entries"]?.jsonArray.orEmpty().forEach { el ->
                        uploadedIds.add(el.jsonObject["id"]!!.jsonPrimitive.content)
                    }
                    seq += 1L
                    HttpResponse(
                        statusCode = 200,
                        body =
                            """{"server_peer_id":"server","sync_timestamp":"$nowIso","sync_seq":$seq,"has_more":false,"reset_required":false}""",
                    )
                }
            }

            val coordinator =
                PeerSyncCoordinator(
                    dao = dao,
                    apiClient = cappedClient,
                    networkTier = NetworkTier.WIFI,
                )
            val baseSettings =
                Settings(
                    serverUrl = "http://127.0.0.1:8080",
                    bearerToken = "tok",
                    peerId = "android-test",
                    deviceName = "Pixel",
                    lastPeerSyncCursor = "20",
                )
            coordinator.syncAndCooperativePoll(currentSettings = baseSettings)
            assertEquals(1, server413Count)
            assertTrue(uploadedIds.containsAll(entryIds))
            for (id in entryIds) {
                assertFalse(dao.getEntryById(id)!!.isLocalDirty)
            }

            val oversizedCoordinator =
                PeerSyncCoordinator(
                    dao = dao,
                    apiClient =
                        RocheApiClient { throw ResponseTooLargeException(maxBytes = 1024L) },
                    networkTier = NetworkTier.WIFI,
                )
            assertNull(
                oversizedCoordinator.syncAndCooperativePoll(
                    currentSettings = baseSettings.copy(lastPeerSyncCursor = "0"),
                    sinceSeqOverride = 0L,
                )
            )
            val genericIoCoordinator =
                PeerSyncCoordinator(
                    dao = dao,
                    apiClient =
                        RocheApiClient {
                            throw IOException("Response exceeds maximum allowed size")
                        },
                    networkTier = NetworkTier.WIFI,
                )
            val genericIoErr = runCatching {
                genericIoCoordinator.syncAndCooperativePoll(
                    currentSettings = baseSettings.copy(lastPeerSyncCursor = "0"),
                    sinceSeqOverride = 0L,
                )
            }
                .exceptionOrNull()
            assertTrue(genericIoErr is IOException)
        }

    @Test
    fun `equal-HLC entry_state uses monotonic join and clamps progress`() = runTest {
        val dao = database.dao()
        val entryId = "entry-eq-hlc"
        val sharedHlc = "001780000001000-0000-server"
        val nowIso = "2026-06-15T12:00:00.000Z"

        dao.upsertEntry(
            EntryEntity(
                id = entryId,
                kind = "article",
                title = "Monotonic State",
                url = "https://example.com/monotonic",
                createdAt = nowIso,
                updatedAt = nowIso,
                hlc = sharedHlc,
            )
        )
        dao.upsertEntryState(
            EntryStateEntity(
                entryId = entryId,
                inboxState = "dismissed",
                readAt = "2026-06-15T11:50:00.000Z",
                progress = 0.8,
                interactionUpdatedAt = "2026-06-15T11:50:00.000Z",
                updatedAt = nowIso,
                hlc = sharedHlc,
                isLocalDirty = false,
            )
        )

        // Incoming at EQUAL HLC has weaker inboxState ('unread') and lower progress (0.2),
        // but later readAt. Join must keep 'dismissed', progress 0.8, later readAt, and stay dirty.
        reconcilePeerSyncResponse(
            dao = dao,
            response =
                PeerSyncResponseDto(
                    serverPeerId = "server",
                    syncTimestamp = nowIso,
                    entries =
                        listOf(
                            SyncEntryRecordDto(
                                id = entryId,
                                kind = "article",
                                title = "Monotonic State",
                                url = "https://example.com/monotonic",
                                createdAt = nowIso,
                                updatedAt = nowIso,
                                hlc = sharedHlc,
                                state =
                                    SyncEntryStateDto(
                                        inboxState = "unread",
                                        readAt = "2026-06-15T11:55:00.000Z",
                                        progress = 0.2,
                                        interactionUpdatedAt = "2026-06-15T11:55:00.000Z",
                                        hlc = sharedHlc,
                                    ),
                            )
                        ),
                ),
        )
        val joined = checkNotNull(dao.getEntryStateById(entryId))
        assertEquals("dismissed", joined.inboxState)
        assertEquals(0.8, joined.progress, 1e-6)
        assertEquals("2026-06-15T11:55:00.000Z", joined.readAt)
        assertTrue(joined.isLocalDirty)

        // Incoming at newer HLC with out-of-range progress (>1.0 / NaN) must clamp to [0.0, 1.0]
        val newerHlc = "001780000002000-0000-server"
        reconcilePeerSyncResponse(
            dao = dao,
            response =
                PeerSyncResponseDto(
                    serverPeerId = "server",
                    syncTimestamp = nowIso,
                    entries =
                        listOf(
                            SyncEntryRecordDto(
                                id = entryId,
                                kind = "article",
                                title = "Monotonic State",
                                url = "https://example.com/monotonic",
                                createdAt = nowIso,
                                updatedAt = nowIso,
                                hlc = newerHlc,
                                state =
                                    SyncEntryStateDto(
                                        inboxState = "unread",
                                        progress = 2.5,
                                        hlc = newerHlc,
                                    ),
                            )
                        ),
                ),
        )
        val clamped = checkNotNull(dao.getEntryStateById(entryId))
        assertEquals(1.0, clamped.progress, 1e-6)
        assertFalse(clamped.isLocalDirty)
    }

    @Test
    fun `foldMergedDuplicateInDao compresses chains backfills metadata and merges content and state`() =
        runTest {
            val dao = database.dao()
            val nowIso = "2026-06-15T12:00:00.000Z"
            val hlc = "001780000001000-0000-server"
            val canonId = "entry-canon"
            val dupId = "entry-dup"
            val grandDupId = "entry-grand-dup"

            dao.upsertEntry(
                EntryEntity(
                    id = canonId,
                    kind = "article",
                    title = "Canonical",
                    creatorsJson = "[]",
                    metadataJson = "{}",
                    createdAt = nowIso,
                    updatedAt = nowIso,
                    hlc = hlc,
                )
            )
            dao.upsertEntry(
                EntryEntity(
                    id = dupId,
                    kind = "article",
                    title = "Duplicate",
                    creatorsJson = """["Ada Lovelace"]""",
                    url = "https://example.com/canon",
                    releaseYear = 2025,
                    summary = "Backfilled summary",
                    thumbnailUrl = "https://example.com/thumb.jpg",
                    metadataJson = """{"source":"dup"}""",
                    createdAt = nowIso,
                    updatedAt = nowIso,
                    hlc = hlc,
                )
            )
            dao.upsertEntry(
                EntryEntity(
                    id = grandDupId,
                    kind = "article",
                    title = "Grandchild Dup",
                    mergedIntoId = dupId,
                    createdAt = nowIso,
                    updatedAt = nowIso,
                    hlc = hlc,
                )
            )
            dao.upsertCollectionSpec(
                CollectionSpecEntity(
                    collectionId = dupId,
                    slug = "dup_list",
                    mode = "manual",
                    role = "list",
                )
            )
            dao.upsertEntryContent(
                EntryContentEntity(
                    entryId = dupId,
                    contentType = "article",
                    contentHtml = "<p>Rich body from dup</p>",
                    audioUrl = "https://example.com/ep.mp3",
                )
            )
            dao.upsertEntryState(
                EntryStateEntity(
                    entryId = canonId,
                    inboxState = "unread",
                    readAt = "2026-06-15T11:30:00.000Z",
                    progress = 0.25,
                    updatedAt = nowIso,
                    hlc = hlc,
                )
            )
            dao.upsertEntryState(
                EntryStateEntity(
                    entryId = dupId,
                    inboxState = "dismissed",
                    readAt = "2026-06-15T11:00:00.000Z",
                    progress = 0.9,
                    updatedAt = nowIso,
                    isCachedOffline = true,
                    hlc = "001780000002000-0000-server",
                )
            )

            val mergeHlc = "001780000003000-0000-server"
            reconcilePeerSyncResponse(
                dao = dao,
                response =
                    PeerSyncResponseDto(
                        serverPeerId = "server",
                        syncTimestamp = nowIso,
                        entries =
                            listOf(
                                SyncEntryRecordDto(
                                    id = dupId,
                                    kind = "article",
                                    title = "Duplicate",
                                    mergedIntoId = canonId,
                                    createdAt = nowIso,
                                    updatedAt = nowIso,
                                    hlc = mergeHlc,
                                )
                            ),
                    ),
            )

            // Chain compression
            assertEquals(canonId, dao.getEntryById(grandDupId)!!.mergedIntoId)
            // Canonical metadata backfill
            val canon = checkNotNull(dao.getEntryById(canonId))
            assertEquals("""["Ada Lovelace"]""", canon.creatorsJson)
            assertEquals("https://example.com/canon", canon.url)
            assertEquals(2025, canon.releaseYear)
            assertEquals("Backfilled summary", canon.summary)
            assertEquals("https://example.com/thumb.jpg", canon.thumbnailUrl)
            assertEquals("""{"source":"dup"}""", canon.metadataJson)
            // Duplicate manual collection_spec deleted
            assertNull(dao.getCollectionSpecById(dupId))
            // Content merged onto canonical & deleted from dup
            assertNull(dao.getEntryContentById(dupId))
            val canonContent = checkNotNull(dao.getEntryContentById(canonId))
            assertEquals("<p>Rich body from dup</p>", canonContent.contentHtml)
            assertEquals("https://example.com/ep.mp3", canonContent.audioUrl)
            // State merged onto canonical & deleted from dup
            assertNull(dao.getEntryStateById(dupId))
            val canonState = checkNotNull(dao.getEntryStateById(canonId))
            assertEquals("dismissed", canonState.inboxState)
            assertEquals("2026-06-15T11:00:00.000Z", canonState.readAt)
            assertEquals(0.9, canonState.progress, 1e-6)
            assertTrue(canonState.isCachedOffline)
        }

    @Test
    fun `formatUtcMillisIso sorts lexicographically across whole and fractional seconds and arXiv canonicalizer handles old-style IDs`() {
        val wholeSec = formatUtcMillisIso(java.time.Instant.parse("2026-06-15T12:00:00Z"))
        val halfSec = formatUtcMillisIso(java.time.Instant.parse("2026-06-15T12:00:00.500Z"))
        assertEquals("2026-06-15T12:00:00.000Z", wholeSec)
        assertEquals("2026-06-15T12:00:00.500Z", halfSec)
        assertTrue(wholeSec < halfSec)

        assertEquals(
            "https://arxiv.org/abs/hep-th/9901001",
            canonicalizeEntryUrl("http://arxiv.org/abs/hep-th/9901001v2", "arxiv"),
        )
        assertEquals(
            "https://arxiv.org/abs/2603.11842",
            canonicalizeEntryUrl("http://export.arxiv.org/abs/2603.11842v1", "rss"),
        )
    }

    @Test
    fun `entry tombstones protect dynamic system feed tag collections and catalog history`() =
        runTest {
            val dao = database.dao()
            val catalog = CatalogStore(dao = dao, localPeerIdProvider = { "android-peer-1" })
            catalog.ensureSystemSeeded()
            val nowMs = System.currentTimeMillis()
            val nowIso = formatUtcMillisIso(java.time.Instant.ofEpochMilli(nowMs))
            val futureHlc = HlcTimestamp.create(nowMs + 30_000L, 1, "server").encode()

            val bookId =
                catalog.upsertEntry(UpsertCatalogEntryInput(kind = EntryKind.BOOK, title = "Dune"))
            dao.upsertConsumptionLog(
                ConsumptionLogEntity(
                    id = "log-dune-1",
                    entryId = bookId,
                    ts = nowIso,
                    status = "consumed",
                    createdAt = nowIso,
                    hlc = HlcTimestamp.create(nowMs, 1, "android-peer-1").encode(),
                )
            )
            val moviesColId = checkNotNull(dao.getCollectionSpecBySlug("movies")).collectionId

            reconcilePeerSyncResponse(
                dao = dao,
                response =
                    PeerSyncResponseDto(
                        serverPeerId = "server",
                        serverHlc = futureHlc,
                        syncTimestamp = nowIso,
                        tombstones =
                            listOf(
                                SyncTombstoneDto(
                                    entityType = TOMBSTONE_ENTITY_ENTRY,
                                    naturalKey = moviesColId,
                                    deletedAt = nowIso,
                                    hlc = futureHlc,
                                ),
                                SyncTombstoneDto(
                                    entityType = TOMBSTONE_ENTITY_ENTRY,
                                    naturalKey = bookId,
                                    deletedAt = nowIso,
                                    hlc = futureHlc,
                                ),
                            ),
                    ),
                localPeerId = "android-peer-1",
            )

            assertNotNull(dao.getCollectionSpecById(moviesColId))
            assertNull(dao.getTombstone(TOMBSTONE_ENTITY_ENTRY, moviesColId))
            assertNotNull(dao.getEntryById(bookId))
            assertNull(dao.getTombstone(TOMBSTONE_ENTITY_ENTRY, bookId))
        }
}

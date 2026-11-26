package com.grayvines.roche.data

import com.grayvines.roche.core.FLAG_LOCAL_HLC
import com.grayvines.roche.core.HlcClock
import com.grayvines.roche.core.HlcTimestamp
import com.grayvines.roche.core.MAX_CLOCK_DRIFT_MS
import com.grayvines.roche.core.MAX_HLC_COUNTER
import com.grayvines.roche.core.MAX_HLC_WALL_MS
import com.grayvines.roche.core.TOMBSTONE_ENTITY_COLLECTION_GENERATOR
import com.grayvines.roche.core.TOMBSTONE_ENTITY_COMPARISON
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.core.canonicalizeSyncUrl
import com.grayvines.roche.core.deterministicItemId
import com.grayvines.roche.core.itemNaturalKey
import com.grayvines.roche.data.catalog.CatalogStore
import com.grayvines.roche.data.catalog.ConsumptionStatus
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.SyncCollectionGeneratorDto
import com.grayvines.roche.data.catalog.SyncComparisonDto
import com.grayvines.roche.data.catalog.SyncConsumptionLogDto
import com.grayvines.roche.data.catalog.UpsertCatalogEntryInput
import com.grayvines.roche.data.feeds.sanitizeFeedHtml
import com.grayvines.roche.data.feeds.writeSharedPdfToItemDir
import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.ComparisonEntity
import com.grayvines.roche.data.local.ConsumptionLogEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryStateEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.nextLocalHlc
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.NetworkTier
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.remote.RocheServerApi
import com.grayvines.roche.data.remote.SyncTombstoneDto
import com.grayvines.roche.data.remote.isCleartextAllowed
import com.grayvines.roche.data.remote.rocheJson
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.data.settings.isPermanentKeyInvalidation
import com.grayvines.roche.data.sync.PeerSyncCoordinator
import com.grayvines.roche.data.sync.PeerSyncJobTracker
import com.grayvines.roche.data.sync.PeerSyncRequestConfig
import com.grayvines.roche.data.sync.PeerSyncScheduler
import com.grayvines.roche.data.sync.buildPeerSyncRequest
import com.grayvines.roche.data.sync.reconcilePeerSyncResponse
import com.grayvines.roche.isAllowedSharedContentUri
import com.grayvines.roche.openValidatedPdfStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.UnrecoverableKeyException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DataSyncRemediationTest {
    private lateinit var tempDir: File
    private var managedDbOrNull: ManagedTestDatabase? = null
    private val database: RocheDatabase
        get() = (managedDbOrNull ?: ManagedTestDatabase().also { managedDbOrNull = it }).database

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("roche-remediation-test").toFile()
    }

    @AfterEach
    fun tearDown() {
        managedDbOrNull?.close()
        managedDbOrNull = null
        tempDir.deleteRecursively()
    }

    @Test
    fun `shared PDF URI guard rejects file scheme and own authorities`() {
        val ownPkg = "com.grayvines.roche"
        assertFalse(isAllowedSharedContentUri("file", "", ownPkg))
        assertFalse(isAllowedSharedContentUri("file", "com.external.app", ownPkg))
        assertFalse(isAllowedSharedContentUri("content", ownPkg, ownPkg))
        assertFalse(isAllowedSharedContentUri("content", "$ownPkg.fileprovider", ownPkg))
        assertFalse(isAllowedSharedContentUri("content", "org.other.alias", ownPkg) { ownPkg })
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
    fun `openValidatedPdfStream accepts PDF header and rejects non-PDF streams`() {
        val pdfPayload =
            listOf("%PDF-1.7", "1 0 obj", "<<>>", "endobj", "")
                .joinToString("\n")
                .toByteArray(Charsets.UTF_8)
        val valid = openValidatedPdfStream(ByteArrayInputStream(pdfPayload))
        assertNotNull(valid)
        assertEquals(
            String(pdfPayload, Charsets.UTF_8),
            String(valid!!.readBytes(), Charsets.UTF_8),
        )

        val sqliteHeader = "SQLite format 3\u0000more bytes".toByteArray(Charsets.UTF_8)
        assertNull(openValidatedPdfStream(ByteArrayInputStream(sqliteHeader)))
        assertNull(openValidatedPdfStream(ByteArrayInputStream("%PDF".toByteArray(Charsets.UTF_8))))
    }

    @Test
    fun `writeSharedPdfToItemDir confines path cleans temp file and handles zero-byte reads`() =
        runTest {
            val pdfBytes = "%PDF-1.4\nhello world\n".toByteArray(Charsets.UTF_8)
            assertFalse(
                writeSharedPdfToItemDir(tempDir, "../escape", ByteArrayInputStream(pdfBytes))
            )

            val invalidResult =
                writeSharedPdfToItemDir(
                    tempDir,
                    "item-bad",
                    ByteArrayInputStream("NOT-A-PDF-DATA".toByteArray(Charsets.UTF_8)),
                )
            assertFalse(invalidResult)
            val badDir = File(File(tempDir, "items"), "item-bad")
            assertTrue(badDir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })

            val zeroReturningStream =
                object : InputStream() {
                    private var pos = 0
                    private var emittedZero = false

                    override fun read(): Int =
                        if (pos < pdfBytes.size) pdfBytes[pos++].toInt() and 0xFF else -1

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (pos >= pdfBytes.size) return -1
                        if (!emittedZero && pos >= 5) {
                            emittedZero = true
                            return 0
                        }
                        val count = minOf(len, 3, pdfBytes.size - pos)
                        System.arraycopy(pdfBytes, pos, b, off, count)
                        pos += count
                        return count
                    }
                }
            assertTrue(writeSharedPdfToItemDir(tempDir, "item-good", zeroReturningStream))
            val saved = File(File(File(tempDir, "items"), "item-good"), "shared_document.pdf")
            assertTrue(saved.exists())
            assertEquals(String(pdfBytes, Charsets.UTF_8), saved.readText(Charsets.UTF_8))
        }

    @Test
    fun `PeerSyncCoordinator halves payload on HTTP 413 and preserves dropped child edges`() =
        runTest {
            val dao = database.dao()
            val catalog = CatalogStore(dao)
            catalog.ensureSystemSeeded()
            val listId = catalog.createCustomList("413 Test List")
            val nowIso = "2026-11-18T12:00:00Z"
            val entryIds = seedPayloadTooLargeFixture(dao, listId, nowIso)

            val receivedRequests = CopyOnWriteArrayList<JsonObject>()
            val uploadedEntryIds = LinkedHashSet<String>()
            val apiClient = buildHalving413ApiClient(nowIso, receivedRequests, uploadedEntryIds)

            val coordinator =
                PeerSyncCoordinator(
                    dao = dao,
                    serverApi = apiClient,
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
                        lastPeerSyncCursor = "5",
                    )
            )

            // Verify the immediate 413 fallback stripped child edges referencing dropped entries
            val firstRetry = receivedRequests[1]
            assertTrue(firstRetry["entries"]?.jsonArray.orEmpty().isEmpty())
            assertTrue(
                firstRetry["collection_entries"]?.jsonArray.orEmpty().none {
                    it.jsonObject["entry_id"]?.jsonPrimitive?.content == entryIds[0]
                }
            )
            assertTrue(
                firstRetry["consumption_log"]?.jsonArray.orEmpty().none {
                    it.jsonObject["entry_id"]?.jsonPrimitive?.content == entryIds[0]
                }
            )
            assertTrue(
                firstRetry["comparisons"]?.jsonArray.orEmpty().none {
                    it.jsonObject["id"]?.jsonPrimitive?.content == "comp-413-0"
                }
            )

            // Verify halving eventually drained all 4 entries instead of stalling
            assertTrue(uploadedEntryIds.containsAll(entryIds))
            for (id in entryIds) {
                assertFalse(
                    dao.getEntryById(id)!!.isLocalDirty,
                    "Entry $id should be clean after drain",
                )
            }
        }

    @Test
    fun `PeerSyncJobTracker isolates concurrent job IDs 4107 and 4108`() = runTest {
        val finishedIds = CopyOnWriteArrayList<Int>()
        val tracker =
            PeerSyncJobTracker<Int>(
                jobIdOf = { it },
                onJobFinished = { id, _ -> finishedIds.add(id) },
            )
        val periodicGate = CompletableDeferred<Unit>()
        val immediateGate = CompletableDeferred<Unit>()

        tracker.start(this, PeerSyncScheduler.PEER_SYNC_JOB_ID) { periodicGate.await() }
        tracker.start(this, PeerSyncScheduler.PEER_SYNC_IMMEDIATE_JOB_ID) { immediateGate.await() }
        testScheduler.runCurrent()

        // System stops periodic job 4107: immediate job 4108 must stay alive and finish cleanly
        tracker.stop(PeerSyncScheduler.PEER_SYNC_JOB_ID)
        immediateGate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(PeerSyncScheduler.PEER_SYNC_IMMEDIATE_JOB_ID), finishedIds)
    }

    @Test
    fun `HLC clamps future drift saturates at max wall and serializes concurrent ticks`() =
        runTest {
            val nowMs = 1_700_000_000_000L
            val farFuture =
                HlcTimestamp.create(nowMs + MAX_CLOCK_DRIFT_MS + 99_999_999L, 42, "rogue")
            val clock = HlcClock("android-dev-1")
            val witnessed = clock.witness(farFuture, nowMs = nowMs)
            assertEquals(nowMs + MAX_CLOCK_DRIFT_MS, witnessed.wallMs)
            assertEquals(1, witnessed.counter)

            // Saturation at MAX_HLC_WALL_MS: counter stays 0xFFFF instead of wrapping to 0
            val maxInitial =
                HlcTimestamp.create(MAX_HLC_WALL_MS, MAX_HLC_COUNTER, "node-a").encode()
            val satClock = HlcClock("node-a", initial = maxInitial)
            val ticked = satClock.tick(nowMs = MAX_HLC_WALL_MS)
            assertEquals(MAX_HLC_WALL_MS, ticked.wallMs)
            assertEquals(MAX_HLC_COUNTER, ticked.counter)

            // Concurrent nextLocalHlc calls emit strictly unique values with device peerId
            val dao = database.dao()
            val emitted = coroutineScope {
                (1..20)
                    .map {
                        async(Dispatchers.Default) {
                            nextLocalHlc(dao, nodeId = "android-device-99", nowMs = nowMs)
                        }
                    }
                    .awaitAll()
            }
            assertEquals(20, emitted.toSet().size)
            assertTrue(emitted.all { it.endsWith("-android-device-99") })

            // CatalogStore threads localPeerIdProvider into HLCs
            val catalog = CatalogStore(dao = dao, localPeerIdProvider = { "android-device-99" })
            val listId = catalog.createCustomList("My Custom List")
            val entry = dao.getEntryById(listId)!!
            assertTrue(
                entry.hlc.endsWith("-android-device-99"),
                "Expected HLC '${entry.hlc}' to end with -android-device-99",
            )
        }

    @Test
    fun `manual Want-to-Read tombstone deletes inboxState none item and generator tombstones sync`() =
        runTest {
            val dao = database.dao()
            val catalog = CatalogStore(dao = dao, localPeerIdProvider = { "android-peer-1" })
            catalog.ensureSystemSeeded()
            val nowIso = "2026-11-18T12:00:00Z"

            // 1. Manual Want-to-Read item with inboxState == "none"
            val itemUrl = "https://example.com/want-to-read-doc"
            val itemId = deterministicItemId(null, canonicalizeSyncUrl(itemUrl))
            val oldHlc = HlcTimestamp.create(1_700_000_000_000L, 0, "android-peer-1").encode()
            seedManualWantToReadItem(dao, itemId, itemUrl, nowIso, oldHlc)

            // 2. Collection generator lifecycle + tombstone
            val listId = catalog.createCustomList("Gen List")
            catalog.setCollectionGeneratorEnabled(listId, enabled = true)
            assertNotNull(dao.getCollectionGenerator(listId))
            assertTrue(catalog.removeCollectionGenerator(listId))
            assertNull(dao.getCollectionGenerator(listId))
            val localGenTomb = dao.getTombstone(TOMBSTONE_ENTITY_COLLECTION_GENERATOR, listId)
            assertNotNull(localGenTomb)
            assertTrue(localGenTomb!!.isLocalDirty)

            // Outgoing sync includes collection_generator tombstone
            val outgoing =
                buildPeerSyncRequest(
                    dao = dao,
                    config = PeerSyncRequestConfig("android-peer-1", "P", sinceSeq = 1L),
                )
            assertTrue(
                outgoing.tombstones.any {
                    it.entityType == TOMBSTONE_ENTITY_COLLECTION_GENERATOR &&
                        it.naturalKey == listId
                }
            )

            // Incoming manual item tombstone deletes the inboxState="none" Want-to-Read item,
            // and an older incoming generator does NOT resurrect the tombstoned generator.
            val newerHlc = HlcTimestamp.create(1_800_000_000_000L, 0, "server").encode()
            val olderGenHlc = HlcTimestamp.create(1_600_000_000_000L, 0, "server").encode()
            val itemTombstone =
                SyncTombstoneDto("item", itemNaturalKey(null, itemUrl), nowIso, newerHlc)
            reconcileGeneratorSync(dao, listId, nowIso, olderGenHlc, listOf(itemTombstone))
            assertNull(dao.getEntryById(itemId))
            assertNull(dao.getCollectionGenerator(listId))

            // A newer incoming generator clears the tombstone and resurrects cleanly
            val resurrectHlc = HlcTimestamp.create(1_900_000_000_000L, 0, "server").encode()
            reconcileGeneratorSync(dao, listId, nowIso, resurrectHlc)
            assertNotNull(dao.getCollectionGenerator(listId))
            assertNull(dao.getTombstone(TOMBSTONE_ENTITY_COLLECTION_GENERATOR, listId))
        }

    @Test
    fun `sanitizeFeedHtml strips XSS vectors via Jsoup safelist`() {
        val malicious =
            listOf(
                    "<p>Hello</p>",
                    """<svg onload="alert(1)"></svg>""",
                    """<object data="evil.swf"></object>""",
                    """<embed src="evil.swf">""",
                    """<meta http-equiv="refresh" content="0;url=javascript:alert(1)">""",
                    """<a href="&#106;avascript:alert(1)">bad1</a>""",
                    "<a href=javascript:alert(2)>bad2</a>",
                    "<script>alert(3)</script >",
                    """<img src="/images/pic.png">""",
                )
                .joinToString("\n")
        val cleaned = sanitizeFeedHtml(malicious, "https://blog.example.com/posts/1")
        assertTrue(cleaned.contains("<p>Hello</p>"))
        assertTrue(cleaned.contains("https://blog.example.com/images/pic.png"))
        assertFalse(cleaned.contains("onload", ignoreCase = true))
        assertFalse(cleaned.contains("<svg", ignoreCase = true))
        assertFalse(cleaned.contains("<object", ignoreCase = true))
        assertFalse(cleaned.contains("<embed", ignoreCase = true))
        assertFalse(cleaned.contains("<meta", ignoreCase = true))
        assertFalse(cleaned.contains("javascript:", ignoreCase = true))
        assertFalse(cleaned.contains("<script", ignoreCase = true))
    }

    @Test
    fun `TokenCipher permanent invalidation check and PublicUrlValidator fail-closed cleartext`() {
        assertTrue(isPermanentKeyInvalidation(UnrecoverableKeyException("wiped")))
        assertTrue(
            isPermanentKeyInvalidation(
                RuntimeException("wrapped", UnrecoverableKeyException("wiped"))
            )
        )
        assertFalse(isPermanentKeyInvalidation(IllegalStateException("device locked")))
        assertFalse(isCleartextAllowed("example.com"))
    }

    private suspend fun seedAlbumsGeneratorFixture(
        catalog: CatalogStore,
        nowIso: String,
        initialHlc: String,
    ): List<String> {
        val listId = catalog.createCustomList("1001 Albums")
        val albums =
            listOf("Album A", "Album B", "Album C").map { title ->
                catalog
                    .upsertEntry(UpsertCatalogEntryInput(kind = EntryKind.ALBUM, title = title))
                    .also { catalog.addCollectionEntry(listId, it) }
            }
        database
            .dao()
            .upsertCollectionGenerator(
                com.grayvines.roche.data.local.CollectionGeneratorEntity(
                    collectionId = listId,
                    enabled = true,
                    currentEntryId = albums[0],
                    drawnAt = nowIso,
                    updatedAt = nowIso,
                    hlc = initialHlc,
                    isLocalDirty = false,
                )
            )
        return listOf(listId) + albums
    }

    @Test
    fun `paginated sync defers generator auto-advance until final page`() = runTest {
        val dao = database.dao()
        val catalog = CatalogStore(dao = dao, localPeerIdProvider = { "android-peer-1" })
        catalog.ensureSystemSeeded()
        val nowIso = "2026-11-18T19:46:10Z"
        val initialHlc = HlcTimestamp.create(1_795_031_000_000L, 0, "server").encode()
        val (listId, albumA, albumB) = seedAlbumsGeneratorFixture(catalog, nowIso, initialHlc)
        val serverLogHlc = HlcTimestamp.create(1_795_031_100_000L, 0, "server").encode()
        val serverGenHlc = HlcTimestamp.create(1_795_031_100_001L, 0, "server").encode()

        val page1 =
            com.grayvines.roche.data.remote.PeerSyncResponseDto(
                serverPeerId = "server",
                serverHlc = serverGenHlc,
                syncTimestamp = nowIso,
                hasMore = true,
                consumptionLog =
                    listOf(
                        SyncConsumptionLogDto(
                            "log-a-done",
                            albumA,
                            nowIso,
                            "consumed",
                            hlc = serverLogHlc,
                        )
                    ),
            )
        reconcilePeerSyncResponse(dao, page1, "android-peer-1", catalogStore = catalog)
        val afterPage1 = dao.getCollectionGenerator(listId)!!
        assertEquals(albumA, afterPage1.currentEntryId)
        assertFalse(afterPage1.isLocalDirty)

        val page2 =
            com.grayvines.roche.data.remote.PeerSyncResponseDto(
                serverPeerId = "server",
                serverHlc = serverGenHlc,
                syncTimestamp = nowIso,
                hasMore = false,
                collectionGenerators =
                    listOf(
                        SyncCollectionGeneratorDto(
                            listId,
                            true,
                            albumB,
                            nowIso,
                            nowIso,
                            serverGenHlc,
                        )
                    ),
            )
        reconcilePeerSyncResponse(dao, page2, "android-peer-1", catalogStore = catalog)
        val afterPage2 = dao.getCollectionGenerator(listId)!!
        assertEquals(albumB, afterPage2.currentEntryId)
        assertEquals(serverGenHlc, afterPage2.hlc)
        assertFalse(afterPage2.isLocalDirty)
    }

    @Test
    fun `stale differing remote generator marks newer local generator dirty`() = runTest {
        val dao = database.dao()
        val catalog = CatalogStore(dao = dao, localPeerIdProvider = { "android-peer-1" })
        catalog.ensureSystemSeeded()
        val nowIso = "2026-11-18T19:46:10Z"
        val newerLocalHlc = HlcTimestamp.create(1_795_031_100_001L, 0, "android-peer-1").encode()
        val staleServerHlc = HlcTimestamp.create(1_795_031_050_000L, 0, "server").encode()
        val (listId, albumA, albumB) = seedAlbumsGeneratorFixture(catalog, nowIso, newerLocalHlc)

        reconcilePeerSyncResponse(
            dao = dao,
            response =
                com.grayvines.roche.data.remote.PeerSyncResponseDto(
                    serverPeerId = "server",
                    serverHlc = newerLocalHlc,
                    syncTimestamp = nowIso,
                    hasMore = false,
                    collectionGenerators =
                        listOf(
                            SyncCollectionGeneratorDto(
                                collectionId = listId,
                                enabled = true,
                                currentEntryId = albumB,
                                drawnAt = nowIso,
                                updatedAt = nowIso,
                                hlc = staleServerHlc,
                            )
                        ),
                ),
            localPeerId = "android-peer-1",
            catalogStore = catalog,
        )

        val afterStaleRemote = dao.getCollectionGenerator(listId)!!
        assertEquals(albumA, afterStaleRemote.currentEntryId)
        assertTrue(afterStaleRemote.isLocalDirty)
    }

    @Test
    fun `remote generator draw is preserved when drawn entry or membership is absent locally without tombstone`() =
        runTest {
            val dao = database.dao()
            val catalog = CatalogStore(dao = dao, localPeerIdProvider = { "android-peer-1" })
            catalog.ensureSystemSeeded()
            val nowIso = "2026-11-21T10:00:07.479Z"
            val initialHlc = HlcTimestamp.create(1_795_100_023_564L, 0, "android-peer-1").encode()
            val serverGenHlc = HlcTimestamp.create(1_795_255_207_479L, 0, "server").encode()
            val (listId, albumA, albumB) = seedAlbumsGeneratorFixture(catalog, nowIso, initialHlc)

            dao.deleteCollectionEntry(listId, albumB)
            reconcileRemoteGeneratorDraw(
                dao = dao,
                catalog = catalog,
                listId = listId,
                entryId = albumB,
                drawnAt = nowIso,
                syncTs = "2026-11-21T12:42:39.816Z",
                hlc = serverGenHlc,
            )
            val afterMissingMembership = dao.getCollectionGenerator(listId)!!
            assertEquals(albumB, afterMissingMembership.currentEntryId)
            assertEquals(serverGenHlc, afterMissingMembership.hlc)
            assertFalse(afterMissingMembership.isLocalDirty)

            val serverGenHlc2 = HlcTimestamp.create(1_795_255_300_000L, 0, "server").encode()
            val unknownRemoteAlbumId = "album-not-cached-locally"
            reconcileRemoteGeneratorDraw(
                dao = dao,
                catalog = catalog,
                listId = listId,
                entryId = unknownRemoteAlbumId,
                drawnAt = nowIso,
                syncTs = "2026-11-21T12:45:00.000Z",
                hlc = serverGenHlc2,
            )
            val afterMissingEntry = dao.getCollectionGenerator(listId)!!
            assertEquals(unknownRemoteAlbumId, afterMissingEntry.currentEntryId)
            assertEquals(serverGenHlc2, afterMissingEntry.hlc)
            assertFalse(afterMissingEntry.isLocalDirty)
            assertEquals(albumA, dao.getEntryById(albumA)?.id)
        }

    private suspend fun reconcileRemoteGeneratorDraw(
        dao: com.grayvines.roche.data.local.RocheDao,
        catalog: CatalogStore,
        listId: String,
        entryId: String,
        drawnAt: String,
        syncTs: String,
        hlc: String,
    ) {
        reconcilePeerSyncResponse(
            dao = dao,
            response =
                com.grayvines.roche.data.remote.PeerSyncResponseDto(
                    serverPeerId = "server",
                    serverHlc = hlc,
                    syncTimestamp = syncTs,
                    hasMore = false,
                    collectionGenerators =
                        listOf(
                            SyncCollectionGeneratorDto(
                                collectionId = listId,
                                enabled = true,
                                currentEntryId = entryId,
                                drawnAt = drawnAt,
                                updatedAt = drawnAt,
                                hlc = hlc,
                            )
                        ),
                ),
            localPeerId = "android-peer-1",
            catalogStore = catalog,
        )
    }

    @Test
    internal fun `rolled back transaction calling nextLocalHlc does not permanently advance FLAG_LOCAL_HLC`() =
        runTest {
            val dao = database.dao()
            val baseMs = 1_700_000_000_000L
            val futureMs = baseMs + 60_000L
            val beforeHlc = nextLocalHlc(dao, nodeId = "android-tx-1", nowMs = baseMs)
            assertEquals(beforeHlc, dao.getLocalSyncFlag(FLAG_LOCAL_HLC))

            val failure = runCatching {
                dao.executeInTransaction {
                    val transient = nextLocalHlc(dao, nodeId = "android-tx-1", nowMs = futureMs)
                    assertTrue(transient > beforeHlc)
                    error("Simulated transaction rollback")
                }
            }
                .exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(beforeHlc, dao.getLocalSyncFlag(FLAG_LOCAL_HLC))

            val nextAfterRollback = nextLocalHlc(dao, nodeId = "android-tx-1", nowMs = baseMs)
            val parsedNext = HlcTimestamp.parse(nextAfterRollback)!!
            assertEquals(baseMs, parsedNext.wallMs)
            assertEquals(1, parsedNext.counter)
            assertEquals(nextAfterRollback, dao.getLocalSyncFlag(FLAG_LOCAL_HLC))
        }

    @Test
    fun `incoming comparison tombstone deletes local comparison and blocks stale resurrection`() =
        runTest {
            val dao = database.dao()
            val catalog = CatalogStore(dao)
            val nowIso = "2026-11-20T12:00:00Z"
            val olderHlc = HlcTimestamp.create(1_700_000_000_000L, 0, "server").encode()
            val newerTombHlc = HlcTimestamp.create(1_800_000_000_000L, 0, "server").encode()
            val aId =
                catalog.upsertEntry(
                    UpsertCatalogEntryInput(kind = EntryKind.MOVIE, title = "Film A")
                )
            val bId =
                catalog.upsertEntry(
                    UpsertCatalogEntryInput(kind = EntryKind.MOVIE, title = "Film B")
                )
            val compId = "comp-test-1"
            dao.insertComparisonIgnore(
                ComparisonEntity(
                    id = compId,
                    ts = nowIso,
                    scopeCollectionId = null,
                    orderingJson = """[["$aId"],["$bId"]]""",
                    createdAt = nowIso,
                    hlc = olderHlc,
                    isLocalDirty = false,
                )
            )
            assertNotNull(dao.getComparisonById(compId))

            reconcilePeerSyncResponse(
                dao = dao,
                response =
                    PeerSyncResponseDto(
                        serverPeerId = "server",
                        syncTimestamp = nowIso,
                        tombstones =
                            listOf(
                                SyncTombstoneDto(
                                    TOMBSTONE_ENTITY_COMPARISON,
                                    compId,
                                    nowIso,
                                    newerTombHlc,
                                )
                            ),
                        comparisons =
                            listOf(
                                SyncComparisonDto(
                                    id = compId,
                                    ts = nowIso,
                                    scopeCollectionId = null,
                                    ordering = listOf(listOf(aId), listOf(bId)),
                                    createdAt = nowIso,
                                    hlc = olderHlc,
                                )
                            ),
                    ),
                localPeerId = "android-peer-1",
                catalogStore = catalog,
            )

            assertNull(dao.getComparisonById(compId))
            assertNotNull(dao.getTombstone(TOMBSTONE_ENTITY_COMPARISON, compId))
        }
}

private suspend fun seedPayloadTooLargeFixture(
    dao: RocheDao,
    listId: String,
    nowIso: String,
): List<String> {
    val entryIds = (0 until 4).map { "entry-413-$it" }
    val body = "x".repeat(400_000)
    for ((idx, id) in entryIds.withIndex()) {
        val hlc = nextLocalHlc(dao, "android-test")
        dao.upsertEntry(
            EntryEntity(
                id = id,
                kind = EntryKind.ARTICLE.wireValue,
                title = "Entry $idx",
                url = "https://example.com/413/$idx",
                summary = body,
                createdAt = nowIso,
                updatedAt = nowIso,
                hlc = hlc,
                isLocalDirty = true,
            )
        )
        dao.upsertEntryState(
            EntryStateEntity(id, "unread", updatedAt = nowIso, hlc = hlc, isLocalDirty = true)
        )
    }
    val edgeHlc = nextLocalHlc(dao, "android-test")
    dao.upsertCollectionEntry(
        CollectionEntryEntity(listId, entryIds[0], null, nowIso, edgeHlc, isLocalDirty = true)
    )
    dao.insertConsumptionLogIgnore(
        ConsumptionLogEntity(
            id = "log-413-0",
            entryId = entryIds[0],
            ts = nowIso,
            status = ConsumptionStatus.CONSUMED.wireValue,
            createdAt = nowIso,
            hlc = edgeHlc,
            isLocalDirty = true,
        )
    )
    dao.insertComparisonIgnore(
        ComparisonEntity(
            id = "comp-413-0",
            ts = nowIso,
            scopeCollectionId = listId,
            orderingJson = """[["${entryIds[0]}"],["${entryIds[1]}"]]""",
            createdAt = nowIso,
            hlc = edgeHlc,
            isLocalDirty = true,
        )
    )
    return entryIds
}

private fun buildHalving413ApiClient(
    nowIso: String,
    receivedRequests: CopyOnWriteArrayList<JsonObject>,
    uploadedEntryIds: LinkedHashSet<String>,
): RocheServerApi {
    var seq = 10L
    return tailnetServerApi { req ->
        val parsed = rocheJson.parseToJsonElement(req.body.orEmpty()).jsonObject
        receivedRequests.add(parsed)
        val entriesArr = parsed["entries"]?.jsonArray.orEmpty()
        if (entriesArr.size > 1) {
            HttpResponse(statusCode = 413, body = """{"title":"Payload Too Large","status":413}""")
        } else {
            entriesArr.forEach { el ->
                uploadedEntryIds.add(el.jsonObject["id"]!!.jsonPrimitive.content)
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

private suspend fun seedManualWantToReadItem(
    dao: RocheDao,
    itemId: String,
    itemUrl: String,
    nowIso: String,
    hlc: String,
) {
    dao.upsertEntry(
        EntryEntity(
            id = itemId,
            kind = EntryKind.ARTICLE.wireValue,
            title = "WTR Article",
            url = itemUrl,
            createdAt = nowIso,
            updatedAt = nowIso,
            hlc = hlc,
        )
    )
    dao.upsertEntryState(
        EntryStateEntity(entryId = itemId, inboxState = "none", updatedAt = nowIso, hlc = hlc)
    )
    dao.upsertCollectionEntry(
        CollectionEntryEntity(WANT_TO_READ_COLLECTION_ID, itemId, null, nowIso, hlc)
    )
}

private suspend fun reconcileGeneratorSync(
    dao: RocheDao,
    listId: String,
    nowIso: String,
    generatorHlc: String,
    tombstones: List<SyncTombstoneDto> = emptyList(),
) {
    reconcilePeerSyncResponse(
        dao = dao,
        response =
            PeerSyncResponseDto(
                serverPeerId = "server",
                syncTimestamp = nowIso,
                tombstones = tombstones,
                collectionGenerators =
                    listOf(
                        SyncCollectionGeneratorDto(
                            collectionId = listId,
                            enabled = true,
                            currentEntryId = null,
                            drawnAt = null,
                            updatedAt = nowIso,
                            hlc = generatorHlc,
                        )
                    ),
            ),
        localPeerId = "android-peer-1",
        catalogStore = CatalogStore(dao),
    )
}

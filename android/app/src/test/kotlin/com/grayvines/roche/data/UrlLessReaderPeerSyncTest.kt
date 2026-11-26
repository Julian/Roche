package com.grayvines.roche.data

import com.grayvines.roche.core.LOCAL_PDF_SCHEME
import com.grayvines.roche.data.catalog.ConsumptionStatus
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.local.ConsumptionLogEntity
import com.grayvines.roche.data.local.EntryContentEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryStateEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.nextLocalHlc
import com.grayvines.roche.data.remote.NetworkTier
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.sync.PeerSyncRequestConfig
import com.grayvines.roche.data.sync.buildTrackedPeerSyncRequest
import com.grayvines.roche.data.sync.reconcilePeerSyncResponse
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val SYNC_TEST_NOW_ISO = "2026-11-25T12:00:00Z"
private const val SYNC_TEST_PEER_ID = "android-peer-1"

class UrlLessReaderPeerSyncTest {
    private var managedDbOrNull: ManagedTestDatabase? = null
    private val database: RocheDatabase
        get() = (managedDbOrNull ?: ManagedTestDatabase().also { managedDbOrNull = it }).database

    @AfterEach
    fun tearDown() {
        managedDbOrNull?.close()
        managedDbOrNull = null
    }

    @Test
    fun `url-less catalog reader entry syncs state without infinite dirty loop`() = runTest {
        val dao = database.dao()
        val hlc = nextLocalHlc(dao, SYNC_TEST_PEER_ID)
        val docId = "doc-urlless-1"
        seedDirtyReaderEntry(
            dao = dao,
            hlc = hlc,
            spec =
                DirtyReaderSpec(
                    id = docId,
                    kind = EntryKind.PAPER,
                    title = "Internal Technical Report",
                    url = null,
                    inboxState = "none",
                ),
            progress = 0.65,
            contentType = "pdf",
        )

        val batch = buildInitialSyncBatch(dao)
        val wireDoc = batch.request.entries.single { it.id == docId }
        assertNull(wireDoc.url)
        assertNotNull(wireDoc.content)
        val wireState = checkNotNull(wireDoc.state)
        assertEquals("none", wireState.inboxState)
        assertEquals(0.65, wireState.progress, 1e-6)
        assertTrue(
            docId in batch.sentStateHlcById,
            "URL-less catalog reader entry must be tracked in sentStateHlcById so dirty clears",
        )

        reconcilePeerSyncResponse(
            dao = dao,
            response =
                PeerSyncResponseDto(serverPeerId = "server", syncTimestamp = SYNC_TEST_NOW_ISO),
            localPeerId = SYNC_TEST_PEER_ID,
            sentBatch = batch,
        )
        assertEquals(false, dao.getEntryStateById(docId)?.isLocalDirty)
        assertEquals(false, dao.getEntryById(docId)?.isLocalDirty)
    }

    @Test
    fun `localpdf entries stay excluded and unsyncable item urls stay stripped`() = runTest {
        val dao = database.dao()
        val hlc = nextLocalHlc(dao, SYNC_TEST_PEER_ID)
        val localPdfId = "doc-local-pdf-scheme"
        val unsyncableUrlId = "doc-unsyncable-url"

        seedDirtyReaderEntry(
            dao = dao,
            hlc = hlc,
            spec =
                DirtyReaderSpec(
                    id = localPdfId,
                    kind = EntryKind.PAPER,
                    title = "Local Scheme PDF",
                    url = "${LOCAL_PDF_SCHEME}abc",
                    inboxState = "none",
                ),
            progress = 0.2,
        )
        seedDirtyReaderEntry(
            dao = dao,
            hlc = hlc,
            spec =
                DirtyReaderSpec(
                    id = unsyncableUrlId,
                    kind = EntryKind.PAPER,
                    title = "Credentialed URL Doc",
                    url = "https://user:pass@example.com/private",
                    inboxState = "none",
                ),
            progress = 0.3,
            contentType = "pdf",
        )

        val batch = buildInitialSyncBatch(dao)
        assertTrue(
            batch.request.entries.none { it.id == localPdfId },
            "localpdf:// entries must stay excluded from peer sync",
        )
        val wireUnsyncableUrl = batch.request.entries.single { it.id == unsyncableUrlId }
        assertNull(wireUnsyncableUrl.url, "unsyncable item URL must be stripped")
        assertNull(wireUnsyncableUrl.content, "unsyncable URL content must stay stripped")
        assertNull(wireUnsyncableUrl.state, "unsyncable URL state must stay stripped")
    }

    @Test
    fun `blank-url unread inbox entry with catalog history keeps inbox payload stripped`() =
        runTest {
            val dao = database.dao()
            val hlc = nextLocalHlc(dao, SYNC_TEST_PEER_ID)
            val blankUnreadId = "doc-blank-unread"

            seedDirtyReaderEntry(
                dao = dao,
                hlc = hlc,
                spec =
                    DirtyReaderSpec(
                        id = blankUnreadId,
                        kind = EntryKind.ARTICLE,
                        title = "Blank Unread",
                        url = null,
                        inboxState = "unread",
                    ),
                progress = 0.0,
                contentType = "article",
            )
            // Attach catalog history so blankUnreadId enters catalogRefIds and reaches
            // toSyncEntryRecordDto.
            dao.insertConsumptionLogIgnore(
                ConsumptionLogEntity(
                    id = "log-blank-unread",
                    entryId = blankUnreadId,
                    ts = SYNC_TEST_NOW_ISO,
                    status = ConsumptionStatus.CONSUMED.wireValue,
                    createdAt = SYNC_TEST_NOW_ISO,
                    hlc = hlc,
                    isLocalDirty = true,
                )
            )

            val batch = buildInitialSyncBatch(dao)
            val wireBlankUnread = batch.request.entries.single { it.id == blankUnreadId }
            assertNull(wireBlankUnread.content, "blank-URL unread inbox content must stay stripped")
            assertNull(wireBlankUnread.state, "blank-URL unread inbox state must remain stripped")
        }
}

private data class DirtyReaderSpec(
    val id: String,
    val kind: EntryKind,
    val title: String,
    val url: String?,
    val inboxState: String,
)

private suspend fun buildInitialSyncBatch(dao: RocheDao) =
    buildTrackedPeerSyncRequest(
        dao = dao,
        config =
            PeerSyncRequestConfig(
                peerId = SYNC_TEST_PEER_ID,
                deviceName = "Pixel",
                networkTier = NetworkTier.WIFI,
                sinceSeq = 0L,
            ),
    )

private suspend fun seedDirtyReaderEntry(
    dao: RocheDao,
    hlc: String,
    spec: DirtyReaderSpec,
    progress: Double,
    contentType: String? = null,
) {
    dao.upsertEntry(
        EntryEntity(
            id = spec.id,
            kind = spec.kind.wireValue,
            title = spec.title,
            url = spec.url,
            createdAt = SYNC_TEST_NOW_ISO,
            updatedAt = SYNC_TEST_NOW_ISO,
            hlc = hlc,
            isLocalDirty = true,
        )
    )
    if (contentType != null) {
        dao.upsertEntryContent(
            EntryContentEntity(
                entryId = spec.id,
                contentType = contentType,
                contentHtml = "<p>${spec.title} body</p>",
            )
        )
    }
    dao.upsertEntryState(
        EntryStateEntity(
            entryId = spec.id,
            inboxState = spec.inboxState,
            progress = progress,
            updatedAt = SYNC_TEST_NOW_ISO,
            hlc = hlc,
            isLocalDirty = true,
        )
    )
}

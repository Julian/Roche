@file:Suppress("LongMethod", "TooManyFunctions", "MagicNumber", "StringLiteralDuplication")

package com.grayvines.roche.data

import com.grayvines.roche.core.HlcTimestamp
import com.grayvines.roche.core.SCHEME_ARXIV
import com.grayvines.roche.core.SCHEME_DOI
import com.grayvines.roche.core.SCHEME_ISBN
import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.extractEntryEditedHlc
import com.grayvines.roche.core.extractPdfMetadataFromBytes
import com.grayvines.roche.core.isEntryUserEdited
import com.grayvines.roche.core.stampEntryEditedHlc
import com.grayvines.roche.data.catalog.CatalogStore
import com.grayvines.roche.data.catalog.EntryIdentifierDto
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.SyncEntryRecordDto
import com.grayvines.roche.data.catalog.UpdateCatalogEntryInput
import com.grayvines.roche.data.catalog.UpsertCatalogEntryInput
import com.grayvines.roche.data.feeds.enrichDocumentEntryOnDevice
import com.grayvines.roche.data.feeds.extractWebPageSnapshot
import com.grayvines.roche.data.local.EntryContentEntity
import com.grayvines.roche.data.remote.HttpResponse
import com.grayvines.roche.data.remote.HttpTransport
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.remote.PublicWebFetcher
import com.grayvines.roche.data.sync.reconcilePeerSyncResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EntryEditAndSyncTest {
    @TempDir lateinit var tempDir: File

    @Test
    fun `catalog updateEntry edits metadata, harmonizes dates, validates identifiers, and protects collections`() =
        runTest {
            val env = TestRocheEnv(tempDir, "entry_edit_store.preferences_pb")
            try {
                val dao = env.database.dao()
                val store = CatalogStore(dao = dao, writeLock = env.database.writeLock)
                store.ensureSystemSeeded()

                val paperUrl = "https://web.maths.unsw.edu.au/~davidharvey/research/subnlogn.pdf"
                val paperId =
                    store.upsertEntry(
                        UpsertCatalogEntryInput(
                            kind = EntryKind.PAPER,
                            title = "subnlogn.pdf",
                            url = paperUrl,
                        )
                    )
                val otherId =
                    store.upsertEntry(
                        UpsertCatalogEntryInput(
                            kind = EntryKind.PAPER,
                            title = "Other Paper",
                            identifiers =
                                listOf(EntryIdentifierDto(SCHEME_DOI, "10.1000/already-taken")),
                        )
                    )
                assertNotNull(otherId)

                val edited =
                    store.updateEntry(
                        entryId = paperId,
                        input =
                            UpdateCatalogEntryInput(
                                title = "Integer multiplication in time O(n log n)",
                                creators = listOf("David Harvey", "Joris van der Hoeven"),
                                publishedAt = "2026-10-20",
                                summary = "Sub-n log n multiplication note.",
                                doi = "https://doi.org/10.4007/ANNALS.2021.193.2.4",
                                arxivId = "arXiv:2301.07041v2",
                            ),
                    )
                assertEquals("Integer multiplication in time O(n log n)", edited.title)
                assertEquals(listOf("David Harvey", "Joris van der Hoeven"), edited.creators)
                assertEquals(2026, edited.releaseYear)
                assertEquals("2026-10-20T12:00:00Z", edited.publishedAt)
                assertEquals("Sub-n log n multiplication note.", edited.summary)
                assertEquals(
                    "10.4007/annals.2021.193.2.4",
                    edited.identifiers.first { it.scheme == SCHEME_DOI }.value,
                )
                assertEquals(
                    "2301.07041v2",
                    edited.identifiers.first { it.scheme == SCHEME_ARXIV }.value,
                )
                assertTrue(isEntryUserEdited(dao.getEntryById(paperId)!!.metadataJson))

                // Clear summary + DOI, switch to year-only 2021 -> publishedAt cleared because year
                // differs
                val cleared =
                    store.updateEntry(
                        entryId = paperId,
                        input =
                            UpdateCatalogEntryInput(
                                releaseYear = 2021,
                                clearSummary = true,
                                clearDoi = true,
                                isbn = "978-0-306-40615-7",
                            ),
                    )
                assertEquals(2021, cleared.releaseYear)
                assertNull(cleared.publishedAt)
                assertNull(cleared.summary)
                assertTrue(cleared.identifiers.none { it.scheme == SCHEME_DOI })
                assertEquals(
                    "9780306406157",
                    cleared.identifiers.first { it.scheme == SCHEME_ISBN }.value,
                )

                // Rejects duplicate DOI owned by another entry
                val conflictErr = runCatching {
                    store.updateEntry(
                        entryId = paperId,
                        input = UpdateCatalogEntryInput(doi = "10.1000/already-taken"),
                    )
                }
                assertTrue(conflictErr.isFailure)

                // Rejects editing system collections
                val colErr = runCatching {
                    store.updateEntry(
                        entryId = STARRED_COLLECTION_ID,
                        input = UpdateCatalogEntryInput(title = "Hacked"),
                    )
                }
                assertTrue(colErr.isFailure)
            } finally {
                env.close()
            }
        }

    @Test
    fun `peer sync edited_hlc LWW clears fields, reconciles identifiers, protects edits, and upgrades placeholders`() =
        runTest {
            val env = TestRocheEnv(tempDir, "entry_edit_sync.preferences_pb")
            try {
                val dao = env.database.dao()
                val store = CatalogStore(dao = dao, writeLock = env.database.writeLock)
                store.ensureSystemSeeded()

                val paperUrl = "https://web.maths.unsw.edu.au/~davidharvey/research/subnlogn.pdf"
                val paperId =
                    store.upsertEntry(
                        UpsertCatalogEntryInput(
                            kind = EntryKind.PAPER,
                            title = "subnlogn.pdf",
                            url = paperUrl,
                        )
                    )

                // 1. Unedited placeholder title upgrades when incoming non-placeholder title
                // arrives
                val hlc1 = HlcTimestamp.create(1_780_000_010_000L, 1, "server").encode()
                reconcilePeerSyncResponse(
                    dao = dao,
                    response =
                        PeerSyncResponseDto(
                            serverPeerId = "server",
                            syncSeq = 1,
                            serverHlc = hlc1,
                            syncTimestamp = "2026-11-20T00:00:10Z",
                            entries =
                                listOf(
                                    SyncEntryRecordDto(
                                        id = paperId,
                                        kind = "paper",
                                        title = "Auto Discovered Title",
                                        creators = listOf("David Harvey"),
                                        url = paperUrl,
                                        publishedAt = "2026-10-20T12:00:00Z",
                                        releaseYear = 2026,
                                        summary = "Auto abstract",
                                        identifiers =
                                            listOf(
                                                EntryIdentifierDto(
                                                    SCHEME_DOI,
                                                    "10.4007/annals.2021.193.2.4",
                                                )
                                            ),
                                        hlc = hlc1,
                                    )
                                ),
                        ),
                )
                val afterAuto = dao.getEntryById(paperId)!!
                assertEquals("Auto Discovered Title", afterAuto.title)
                assertEquals(2026, afterAuto.releaseYear)
                assertEquals("Auto abstract", afterAuto.summary)

                // 2. Incoming explicit edit (newer edited_hlc) clears summary + DOI and updates
                // title
                val hlcEdit = HlcTimestamp.create(1_780_000_060_000L, 1, "server").encode()
                val editedMetaJson = stampEntryEditedHlc("{}", hlcEdit)
                reconcilePeerSyncResponse(
                    dao = dao,
                    response =
                        PeerSyncResponseDto(
                            serverPeerId = "server",
                            syncSeq = 2,
                            serverHlc = hlcEdit,
                            syncTimestamp = "2026-11-20T00:01:00Z",
                            entries =
                                listOf(
                                    SyncEntryRecordDto(
                                        id = paperId,
                                        kind = "paper",
                                        title = "Curated Paper Title",
                                        creators = listOf("David Harvey", "Joris van der Hoeven"),
                                        url = paperUrl,
                                        publishedAt = null,
                                        releaseYear = 2021,
                                        summary = null,
                                        metadata = Json.parseToJsonElement(editedMetaJson),
                                        identifiers =
                                            listOf(EntryIdentifierDto(SCHEME_ARXIV, "2301.07041")),
                                        hlc = hlcEdit,
                                    )
                                ),
                        ),
                )
                val afterEdit = dao.getEntryById(paperId)!!
                assertEquals("Curated Paper Title", afterEdit.title)
                assertNull(afterEdit.summary)
                assertNull(afterEdit.publishedAt)
                assertEquals(2021, afterEdit.releaseYear)
                assertEquals(hlcEdit, extractEntryEditedHlc(afterEdit.metadataJson))
                val identsAfterEdit = dao.getIdentifiersForEntry(paperId)
                assertTrue(identsAfterEdit.none { it.scheme == SCHEME_DOI })
                assertTrue(
                    identsAfterEdit.any { it.scheme == SCHEME_ARXIV && it.value == "2301.07041" }
                )

                // 3. Stale unedited incoming sync with a later generic HLC must NOT clobber curated
                // edit
                val staleGenericHlc = HlcTimestamp.create(1_780_000_120_000L, 1, "other").encode()
                reconcilePeerSyncResponse(
                    dao = dao,
                    response =
                        PeerSyncResponseDto(
                            serverPeerId = "other",
                            syncSeq = 3,
                            serverHlc = staleGenericHlc,
                            syncTimestamp = "2026-11-20T00:02:00Z",
                            entries =
                                listOf(
                                    SyncEntryRecordDto(
                                        id = paperId,
                                        kind = "paper",
                                        title = "Stale Scraper Overwrite",
                                        creators = listOf("Wrong Author"),
                                        url = paperUrl,
                                        summary = "Should not reappear",
                                        hlc = staleGenericHlc,
                                    )
                                ),
                        ),
                )
                val protected = dao.getEntryById(paperId)!!
                assertEquals("Curated Paper Title", protected.title)
                assertNull(protected.summary)
                assertEquals(2021, protected.releaseYear)

                // 4. Equal edited_hlc re-sync must NOT revert to unedited heuristics or resurrect
                //    identifiers
                val equalHlcMeta = stampEntryEditedHlc("{}", hlcEdit)
                val laterGenericHlc = HlcTimestamp.create(1_780_000_180_000L, 1, "peer-b").encode()
                reconcilePeerSyncResponse(
                    dao = dao,
                    response =
                        PeerSyncResponseDto(
                            serverPeerId = "peer-b",
                            syncSeq = 4,
                            serverHlc = laterGenericHlc,
                            syncTimestamp = "2026-11-20T00:03:00Z",
                            entries =
                                listOf(
                                    SyncEntryRecordDto(
                                        id = paperId,
                                        kind = "paper",
                                        title = "Sneaky Same-HLC Title",
                                        creators = listOf("Sneaky Author"),
                                        url = paperUrl,
                                        summary = "Should not reappear",
                                        metadata = Json.parseToJsonElement(equalHlcMeta),
                                        identifiers =
                                            listOf(
                                                EntryIdentifierDto(SCHEME_DOI, "10.1000/sneaky")
                                            ),
                                        hlc = laterGenericHlc,
                                    )
                                ),
                        ),
                )
                val afterEqual = dao.getEntryById(paperId)!!
                assertEquals("Curated Paper Title", afterEqual.title)
                assertNull(afterEqual.summary)
                assertTrue(dao.getIdentifiersForEntry(paperId).none { it.scheme == SCHEME_DOI })

                // 5. IncomingEdit with arXiv URL and empty identifiers must NOT resurrect deleted
                //    arXiv identifier
                val arxivUrl = "https://arxiv.org/abs/2301.07041"
                val newerEditHlc = HlcTimestamp.create(1_780_000_240_000L, 1, "server").encode()
                reconcilePeerSyncResponse(
                    dao = dao,
                    response =
                        PeerSyncResponseDto(
                            serverPeerId = "server",
                            syncSeq = 5,
                            serverHlc = newerEditHlc,
                            syncTimestamp = "2026-11-20T00:04:00Z",
                            entries =
                                listOf(
                                    SyncEntryRecordDto(
                                        id = paperId,
                                        kind = "paper",
                                        title = "Curated Paper Without Arxiv",
                                        creators = listOf("David Harvey"),
                                        url = arxivUrl,
                                        metadata =
                                            Json.parseToJsonElement(
                                                stampEntryEditedHlc("{}", newerEditHlc)
                                            ),
                                        identifiers = emptyList(),
                                        hlc = newerEditHlc,
                                    )
                                ),
                        ),
                )
                assertTrue(
                    dao.getIdentifiersForEntry(paperId).none { it.scheme == SCHEME_ARXIV },
                    "deleted arxiv identifier must not be resurrected from URL on IncomingEdit",
                )
            } finally {
                env.close()
            }
        }

    @Test
    fun `date harmonization, pdf content_type preservation, and pdf stream extraction edge cases`() =
        runTest {
            val env = TestRocheEnv(tempDir, "edit_edge_cases.preferences_pb")
            try {
                val dao = env.database.dao()
                val store = CatalogStore(dao = dao, writeLock = env.database.writeLock)
                store.ensureSystemSeeded()

                val paperId =
                    store.upsertEntry(
                        UpsertCatalogEntryInput(
                            kind = EntryKind.PAPER,
                            title = "Pre-filled Paper",
                            publishedAt = "2025-05-10T12:00:00Z",
                            releaseYear = 2025,
                        )
                    )
                dao.upsertEntryContent(EntryContentEntity(entryId = paperId, contentType = "pdf"))

                // Changing only pre-filled Year while Date stays identical clears mismatched Date
                val yearOnly =
                    store.updateEntry(
                        entryId = paperId,
                        input =
                            UpdateCatalogEntryInput(
                                publishedAt = "2025-05-10",
                                releaseYear = 2021,
                            ),
                    )
                assertEquals(2021, yearOnly.releaseYear)
                assertNull(yearOnly.publishedAt)

                // Changing Date while stale Year stays pre-filled updates Year to match Date
                val newDate =
                    store.updateEntry(
                        entryId = paperId,
                        input =
                            UpdateCatalogEntryInput(
                                publishedAt = "2026-08-14",
                                releaseYear = 2021,
                            ),
                    )
                assertEquals(2026, newDate.releaseYear)
                assertEquals("2026-08-14T12:00:00Z", newDate.publishedAt)

                // Changing kind from Paper to Article preserves "pdf" contentType
                store.updateEntry(
                    entryId = paperId,
                    input = UpdateCatalogEntryInput(kind = EntryKind.ARTICLE),
                )
                assertEquals("pdf", dao.getEntryContentById(paperId)!!.contentType)

                // Non-PDF bytes are rejected before scanning
                val junkMeta =
                    extractPdfMetadataFromBytes(
                        "<html><title>/Title (Fake)</title></html>".toByteArray()
                    )
                assertNull(junkMeta.title)

                // Skips image streams and prefers page-1 date over /CreationDate
                val imgPlusTextPdf = buildPdfWithLeadingImageStreams()
                val extracted = extractPdfMetadataFromBytes(imgPlusTextPdf)
                assertEquals("Fast Modular Multiplication", extracted.title)
                assertEquals(2024, extracted.releaseYear)
                assertEquals("2024-03-10T12:00:00Z", extracted.publishedAt)
            } finally {
                env.close()
            }
        }

    private fun buildPdfWithLeadingImageStreams(): ByteArray {
        val streamText =
            listOf(
                    "BT",
                    "/F1 17 Tf",
                    "100 720 Td (Fast Modular Multiplication) Tj",
                    "/F1 12 Tf",
                    "0 -24 Td (Grace Hopper) Tj",
                    "0 -20 Td (March 10, 2024) Tj",
                    "ET",
                )
                .joinToString("\n")
        val raw = streamText.toByteArray(Charsets.ISO_8859_1)
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        deflater.end()
        val compressed = out.toByteArray()

        val pdf = ByteArrayOutputStream()
        val headLines = buildList {
            add("%PDF-1.5")
            // 15 image streams exceed MAX_STREAMS_TO_INSPECT (12) if not skipped
            repeat(15) { idx ->
                add("${idx + 1} 0 obj << /Subtype /Image /Filter /DCTDecode /Length 4 >>")
                add("stream")
                add("ABCD")
                add("endstream")
                add("endobj")
            }
            add("20 0 obj << /Length ${compressed.size} /Filter /FlateDecode >>")
            add("stream")
            add("")
        }
        val tailLines =
            listOf(
                "",
                "endstream",
                "endobj",
                "21 0 obj << /CreationDate (D:20261101120000Z) >> endobj",
                "%%EOF",
                "",
            )
        pdf.write(headLines.joinToString("\n").toByteArray(Charsets.ISO_8859_1))
        pdf.write(compressed)
        pdf.write(tailLines.joinToString("\n").toByteArray(Charsets.ISO_8859_1))
        return pdf.toByteArray()
    }

    @Test
    fun `extractWebPageSnapshot and on-device PDF discovery enrich shared subnlogn pdf`() =
        runTest {
            val env = TestRocheEnv(tempDir, "doc_discovery.preferences_pb")
            try {
                // 1. HTML citation + JSON-LD extraction
                val html =
                    listOf(
                            "<html><head>",
                            """<meta name="citation_title" content="Integer multiplication in time O(n log n)" />""",
                            """<meta name="citation_author" content="David Harvey" />""",
                            """<meta name="citation_author" content="Joris van der Hoeven" />""",
                            """<meta name="citation_publication_date" content="2021/03/15" />""",
                            """<meta name="citation_doi" content="10.4007/ANNALS.2021.193.2.4" />""",
                            """<meta name="citation_isbn" content="978-0-306-40615-7" />""",
                            """<meta name="citation_abstract" content="An O(n log n) multiplication bound." />""",
                            "</head><body><article><p>Body paragraph.</p></article></body></html>",
                        )
                        .joinToString("\n")
                val snap = extractWebPageSnapshot(html, "https://journal.example.org/paper")
                assertEquals("Integer multiplication in time O(n log n)", snap.title)
                assertEquals(listOf("David Harvey", "Joris van der Hoeven"), snap.authors)
                assertEquals(2021, snap.releaseYear)
                assertEquals("2021-03-15T12:00:00Z", snap.publishedAt)
                assertEquals("10.4007/annals.2021.193.2.4", snap.doi)
                assertEquals("9780306406157", snap.isbn)

                // 2. Shared PDF with placeholder filename "subnlogn.pdf" + cached PDF enriches on
                // device
                val streamText =
                    listOf(
                            "BT",
                            "/F1 17 Tf",
                            """100 720 Td (Integer multiplication in time O\(n log n\)) Tj""",
                            "/F1 12 Tf",
                            "0 -28 Td (David Harvey and Joris van der Hoeven) Tj",
                            "0 -20 Td (October 20, 2026) Tj",
                            "0 -28 Td (Abstract) Tj",
                            """0 -14 Td (We prove that n-bit integers can be multiplied in O\(n log n\) bit operations.) Tj""",
                            "ET",
                        )
                        .joinToString("\n")
                val pdfBytes = buildSyntheticPdf(streamText)
                val cacheDir = File(tempDir, "cache").apply { mkdirs() }
                val transport = HttpTransport {
                    HttpResponse(
                        statusCode = 200,
                        body = "",
                        bodyBytes = pdfBytes,
                        headers = mapOf("Content-Type" to "application/pdf"),
                    )
                }
                val repo =
                    mockRepository(
                        database = env.database,
                        settings = env.settings,
                        transport = transport,
                        cacheDir = cacheDir,
                    )
                val pdfUrl = "https://web.maths.unsw.edu.au/~davidharvey/research/subnlogn.pdf"
                repo.addUrlToWantToRead(
                    inputUrl = pdfUrl,
                    titleOverride = "subnlogn.pdf",
                    pdfInputStream = ByteArrayInputStream(pdfBytes),
                    awaitPdfCacheCopy = true,
                )
                val saved = env.database.dao().getAllItems().single { it.url == pdfUrl }
                assertEquals("subnlogn.pdf", saved.title)

                // Run on-device document enrichment (same routine used by background save +
                // Refresh)
                val enriched =
                    enrichDocumentEntryOnDevice(
                        dao = env.database.dao(),
                        publicWebFetcher = PublicWebFetcher(transport = transport),
                        entryId = saved.id,
                        cacheDir = cacheDir,
                        forceRefresh = false,
                        writeLock = env.database.writeLock,
                    )
                assertTrue(enriched)

                val entryAfter = env.database.dao().getEntryById(saved.id)!!
                assertEquals("Integer multiplication in time O(n log n)", entryAfter.title)
                assertEquals(2026, entryAfter.releaseYear)
                assertTrue(entryAfter.creatorsJson.contains("David Harvey"))
                assertTrue(entryAfter.creatorsJson.contains("Joris van der Hoeven"))
                assertTrue(entryAfter.summary.orEmpty().contains("O(n log n)"))
            } finally {
                env.close()
            }
        }

    private fun buildSyntheticPdf(contentStream: String): ByteArray {
        val raw = contentStream.toByteArray(Charsets.ISO_8859_1)
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        deflater.end()
        val compressed = out.toByteArray()

        val pdf = ByteArrayOutputStream()
        val head =
            listOf(
                    "%PDF-1.5",
                    "1 0 obj << /Length ${compressed.size} /Filter /FlateDecode >>",
                    "stream",
                    "",
                )
                .joinToString("\n")
        val tail =
            listOf(
                    "",
                    "endstream",
                    "endobj",
                    "2 0 obj << /Title () /Author () /Producer (pdfTeX) >> endobj",
                    "%%EOF",
                    "",
                )
                .joinToString("\n")
        pdf.write(head.toByteArray(Charsets.ISO_8859_1))
        pdf.write(compressed)
        pdf.write(tail.toByteArray(Charsets.ISO_8859_1))
        return pdf.toByteArray()
    }
}

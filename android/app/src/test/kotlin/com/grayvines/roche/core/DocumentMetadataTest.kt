@file:Suppress("LongMethod", "TooManyFunctions", "MagicNumber", "StringLiteralDuplication")

package com.grayvines.roche.core

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DocumentMetadataTest {
    @Test
    fun `normalizeDoi and normalizeIsbn canonicalize valid inputs and reject junk`() {
        assertEquals(
            "10.1145/359545.359563",
            normalizeDoi("https://doi.org/10.1145/359545.359563."),
        )
        assertEquals(
            "10.4007/annals.2021.193.2.4",
            normalizeDoi("DOI: 10.4007/ANNALS.2021.193.2.4"),
        )
        assertNull(normalizeDoi("not-a-doi"))
        assertNull(normalizeDoi("10.1/short"))

        assertEquals("9780306406157", normalizeIsbn("ISBN-13: 978-0-306-40615-7"))
        assertEquals("0306406152", normalizeIsbn("0-306-40615-2"))
        assertEquals("080442957X", normalizeIsbn("0-8044-2957-x"))
        assertNull(normalizeIsbn("12345"))
    }

    @Test
    fun `extractDoiFromText and extractArxivIdFromText locate embedded identifiers`() {
        assertEquals(
            "10.48550/arxiv.2301.07041",
            extractDoiFromText(
                "Published version available at doi:10.48550/arXiv.2301.07041, 2023."
            ),
        )
        assertEquals(
            "2301.07041v2",
            extractArxivIdFromText("Preprint arXiv:2301.07041v2 [cs.DS] 18 Jan 2023"),
        )
        assertNull(extractArxivIdFromText("No identifiers on this page"))
    }

    @Test
    fun `parsePdfDate and parsePublicationDateStr parse pdf, iso, ymd, bare year, and natural dates`() {
        val pdfDate = parsePdfDate("D:20261020103249+11'00'")!!
        assertEquals(2026, pdfDate.releaseYear)
        assertEquals("2026-10-19T23:32:49Z", pdfDate.publishedAt)

        val iso = parsePublicationDateStr("2021-03-15")!!
        assertEquals(2021, iso.releaseYear)
        assertEquals("2021-03-15T12:00:00Z", iso.publishedAt)

        val natural = parsePublicationDateStr("October 20, 2026")!!
        assertEquals(2026, natural.releaseYear)
        assertEquals("2026-10-20T12:00:00Z", natural.publishedAt)

        val bareYear = parsePublicationDateStr("1999")!!
        assertEquals(1999, bareYear.releaseYear)
        assertNull(bareYear.publishedAt)

        assertNull(parsePublicationDateStr("not a date"))
    }

    @Test
    fun `parseCrossrefWorkJson extracts title, authors, publication date, doi, and clean abstract`() {
        val json =
            listOf(
                    """{"message":{""",
                    """"title":["Integer multiplication in time O(n log n)"],""",
                    """"author":[{"given":"David","family":"Harvey"},{"given":"Joris","family":"van der Hoeven"}],""",
                    """"published-print":{"date-parts":[[2021,3,1]]},""",
                    """"DOI":"10.4007/ANNALS.2021.193.2.4",""",
                    """"abstract":"<jats:p>We prove an <jats:italic>O(n log n)</jats:italic> bound.</jats:p>"""",
                    """}}""",
                )
                .joinToString("")
        val parsed = parseCrossrefWorkJson(json)!!
        assertEquals("Integer multiplication in time O(n log n)", parsed.title)
        assertEquals(listOf("David Harvey", "Joris van der Hoeven"), parsed.authors)
        assertEquals(2021, parsed.releaseYear)
        assertEquals("2021-03-01T12:00:00Z", parsed.publishedAt)
        assertEquals("10.4007/annals.2021.193.2.4", parsed.doi)
        assertEquals("We prove an O(n log n) bound.", parsed.summary)
    }

    @Test
    fun `extractMetadataFromPlainText and extractPdfMetadataFromBytes parse blank-Info LaTeX PDFs`() {
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
        val pdfBytes = buildSyntheticFlatePdf(streamText)
        val discovered =
            extractPdfMetadataFromBytes(
                bytes = pdfBytes,
                sourceUrl = "https://web.maths.unsw.edu.au/~davidharvey/research/subnlogn.pdf",
            )
        assertEquals("Integer multiplication in time O(n log n)", discovered.title)
        assertEquals(listOf("David Harvey", "Joris van der Hoeven"), discovered.authors)
        assertEquals(2026, discovered.releaseYear)
        assertTrue(discovered.summary.orEmpty().contains("O(n log n)"))

        // Info + XMP extraction prefers XMP when both present and skips junk Info titles
        val infoAndXmp =
            listOf(
                    "%PDF-1.5",
                    "1 0 obj << /Title (Untitled) /Author (Ada Lovelace) /CreationDate (D:20240501120000Z) >> endobj",
                    """<x:xmpmeta xmlns:x="adobe:ns:meta/"><dc:title><rdf:Alt><rdf:li>Notes on the Analytical Engine</rdf:li></rdf:Alt></dc:title><prism:doi>10.1000/analytical.1843</prism:doi></x:xmpmeta>""",
                    "%%EOF",
                )
                .joinToString("\n")
                .toByteArray(Charsets.ISO_8859_1)
        val xmpMeta = extractPdfMetadataFromBytes(infoAndXmp)
        assertEquals("Notes on the Analytical Engine", xmpMeta.title)
        assertEquals(listOf("Ada Lovelace"), xmpMeta.authors)
        assertEquals(2024, xmpMeta.releaseYear)
        assertEquals("10.1000/analytical.1843", xmpMeta.doi)
    }

    @Test
    fun `mergeEntryMetadataJson always retains lexicographical max edited_hlc`() {
        val olderHlc = HlcTimestamp.create(1_780_000_000_000L, 1, "a").encode()
        val newerHlc = HlcTimestamp.create(1_780_000_060_000L, 1, "b").encode()
        val existing = stampEntryEditedHlc("""{"foo":"old"}""", newerHlc)
        val incoming = stampEntryEditedHlc("""{"foo":"new"}""", olderHlc)

        assertTrue(isEntryUserEdited(existing))
        assertFalse(isEntryUserEdited("{}"))

        // Even when incoming HLC is treated as newer for generic keys, edited_hlc keeps the max.
        val incomingObj = parseMetadataJsonObject(incoming)
        val merged = mergeEntryMetadataJson(existing, incomingObj, incomingWins = true)
        assertEquals(newerHlc, extractEntryEditedHlc(merged))
    }

    private fun buildSyntheticFlatePdf(contentStream: String): ByteArray {
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
                    "2 0 obj << /Title () /Author () /Producer (pdfTeX-1.40.25) /CreationDate (D:20261020103249+11'00') >> endobj",
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

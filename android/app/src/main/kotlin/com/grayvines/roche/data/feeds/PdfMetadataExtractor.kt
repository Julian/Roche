package com.grayvines.roche.data.feeds

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.grayvines.roche.core.ExtractedDocumentMetadata
import com.grayvines.roche.core.encodeQueryValue
import com.grayvines.roche.core.extractArxivIdFromText
import com.grayvines.roche.core.extractArxivIdentifier
import com.grayvines.roche.core.extractDoiFromText
import com.grayvines.roche.core.extractPdfMetadataFromBytes
import com.grayvines.roche.core.fillMissingFrom
import com.grayvines.roche.core.isValidArxivId
import com.grayvines.roche.core.normalizeDoi
import com.grayvines.roche.core.normalizeWhitespace
import com.grayvines.roche.core.parseCrossrefWorkJson
import com.grayvines.roche.core.splitAuthorNames
import com.grayvines.roche.data.remote.PublicWebFetcher
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Layered PDF bibliographic metadata discovery for Android:
 * 1. PDF `/Info` dictionary + embedded XMP packet
 * 2. Page-1 text via Android `PdfRenderer.Page.getTextContents()` (API 35+, minSdk 36), falling
 *    back cleanly to pure-Kotlin FlateDecode stream text extraction on JVM tests
 * 3. Authoritative arXiv / Crossref API lookup when an arXiv ID or DOI is found
 */
internal suspend fun extractPdfMetadataOnDevice(
    pdfFile: File?,
    pdfBytes: ByteArray?,
    sourceUrl: String?,
    publicWebFetcher: PublicWebFetcher?,
): ExtractedDocumentMetadata =
    withContext(Dispatchers.Default) {
        val rawBytes = pdfBytes ?: readBoundedPdfBytes(pdfFile)
        val page1Text = extractPage1ViaPdfRenderer(pdfFile, rawBytes)
        val localMeta =
            if (rawBytes != null) {
                extractPdfMetadataFromBytes(
                    bytes = rawBytes,
                    sourceUrl = sourceUrl,
                    page1PlainText = page1Text,
                )
            } else {
                ExtractedDocumentMetadata()
            }
        val seeded = seedIdentifiersFromSourceUrl(localMeta, sourceUrl)
        if (publicWebFetcher != null) {
            lookupExternalDocumentMetadata(seeded, publicWebFetcher)
        } else {
            seeded
        }
    }

internal suspend fun lookupExternalDocumentMetadata(
    initial: ExtractedDocumentMetadata,
    publicWebFetcher: PublicWebFetcher,
): ExtractedDocumentMetadata {
    var result = initial
    val arxivId = result.arxivId?.takeIf(::isValidArxivId)
    if (arxivId != null) {
        fetchArxivMetadata(arxivId, publicWebFetcher)?.let { arxivMeta ->
            result = arxivMeta.fillMissingFrom(result)
        }
    }
    val doi = result.doi?.let(::normalizeDoi)
    if (doi != null) {
        fetchCrossrefMetadata(doi, publicWebFetcher)?.let { crossrefMeta ->
            result = crossrefMeta.fillMissingFrom(result)
        }
    }
    return result
}

private suspend fun fetchArxivMetadata(
    arxivId: String,
    publicWebFetcher: PublicWebFetcher,
): ExtractedDocumentMetadata? {
    val queryUrl = "https://export.arxiv.org/api/query?id_list=${encodeQueryValue(arxivId)}"
    val xml = publicWebFetcher.fetchFeedXml(queryUrl).getOrNull() ?: return null
    val parsed = runCatching { LocalFeedPoller.parseFeedXml(xml, queryUrl, "arxiv") }.getOrNull()
    val candidate = parsed?.items?.firstOrNull() ?: return null
    val cleanTitle = normalizeWhitespace(candidate.title).takeIf { it.isNotEmpty() }
    val authors = candidate.author?.let(::splitAuthorNames).orEmpty()
    val cleanSummary = candidate.summary.trim().takeIf { it.isNotEmpty() && it != cleanTitle }
    return ExtractedDocumentMetadata(
        title = cleanTitle,
        authors = authors,
        publishedAt = candidate.publishedAt,
        summary = cleanSummary,
        arxivId = arxivId,
    )
}

private suspend fun fetchCrossrefMetadata(
    doi: String,
    publicWebFetcher: PublicWebFetcher,
): ExtractedDocumentMetadata? {
    val url = "https://api.crossref.org/works/${encodeDoiPath(doi)}"
    val json = publicWebFetcher.fetchJson(url).getOrNull() ?: return null
    return parseCrossrefWorkJson(json)
}

private fun encodeDoiPath(doi: String): String =
    doi.split('/').joinToString("/") { segment -> encodeQueryValue(segment) }

private fun seedIdentifiersFromSourceUrl(
    meta: ExtractedDocumentMetadata,
    sourceUrl: String?,
): ExtractedDocumentMetadata {
    val cleanUrl = sourceUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return meta
    val nextArxiv =
        meta.arxivId ?: extractArxivIdentifier(cleanUrl) ?: extractArxivIdFromText(cleanUrl)
    val nextDoi = meta.doi ?: normalizeDoi(cleanUrl) ?: extractDoiFromText(cleanUrl)
    return if (nextArxiv == meta.arxivId && nextDoi == meta.doi) {
        meta
    } else {
        meta.copy(arxivId = nextArxiv, doi = nextDoi)
    }
}

private fun readBoundedPdfBytes(file: File?): ByteArray? {
    if (file == null || !file.isFile) return null
    val len = file.length()
    if (len !in 1L..MAX_LOCAL_PDF_READ_BYTES) return null
    return runCatching { file.readBytes() }.getOrNull()
}

private fun extractPage1ViaPdfRenderer(pdfFile: File?, rawBytes: ByteArray?): String? {
    var tempFile: File? = null
    return try {
        val targetFile =
            pdfFile?.takeIf { it.isFile }
                ?: rawBytes?.let { bytes ->
                    File.createTempFile("roche_pdf_meta_", ".pdf").also {
                        it.writeBytes(bytes)
                        tempFile = it
                    }
                }
                ?: return null
        renderPage1ViaPdfium(targetFile)
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { tempFile?.delete() }
    }
}

private fun renderPage1ViaPdfium(targetFile: File): String? = runCatching {
    ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        PdfRenderer(pfd).use { renderer ->
            if (renderer.pageCount > 0) {
                renderer.openPage(0).use { page ->
                    page
                        .getTextContents()
                        .mapNotNull { it.text.trim().takeIf { s -> s.isNotEmpty() } }
                        .joinToString("\n")
                        .takeIf { it.isNotBlank() }
                }
            } else {
                null
            }
        }
    }
}
    .getOrNull()

private const val MAX_LOCAL_PDF_READ_BYTES = 25L * 1024L * 1024L

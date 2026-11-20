package com.grayvines.roche.data.feeds

import com.grayvines.roche.core.DEFAULT_LOCAL_HLC_NODE
import com.grayvines.roche.core.PDF_MAGIC_BYTES
import com.grayvines.roche.core.buildYoutubeChannelFeedUrl
import com.grayvines.roche.core.extractArxivIdentifier
import com.grayvines.roche.core.extractHostTitle
import com.grayvines.roche.core.extractYoutubeChannelId
import com.grayvines.roche.core.hasPdfMagicHeader
import com.grayvines.roche.core.isHttpOrHttpsUrl
import com.grayvines.roche.core.isLikelyFeedUrl
import com.grayvines.roche.core.isLocalPdfUrl
import com.grayvines.roche.core.isYoutubeChannelId
import com.grayvines.roche.core.moveFileAtomically
import com.grayvines.roche.core.resolveSafeChildFile
import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.core.sanitizeCacheFileName
import com.grayvines.roche.data.local.MAX_SQLITE_HTML_CHARS
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.enrichEntryMetadataIfPresent
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.media.ITEMS_SUBDIR
import com.grayvines.roche.media.SHARED_PDF_FILENAME
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.inferContentType
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

internal data class DiscoveredFeedResult(
    val feedUrl: String,
    val siteTitle: String?,
    val prefetchedBatch: ParsedFeedBatch? = null,
)

internal data class ExtractedWebPageSnapshot(
    val title: String?,
    val author: String?,
    val summary: String?,
    val contentHtml: String?,
    val thumbnailUrl: String?,
)

internal fun normalizeUserSubmittedUrl(rawInput: String): String {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty() || isYoutubeChannelId(trimmed)) return trimmed
    return when {
        isHttpOrHttpsUrl(trimmed) || isLocalPdfUrl(trimmed) -> trimmed
        "://" in trimmed -> trimmed
        else -> "https://$trimmed"
    }
}

internal suspend fun discoverFeedFromUrl(
    apiClient: RocheApiClient,
    rawInputUrl: String,
): Result<DiscoveredFeedResult> =
    withContext(Dispatchers.Default) {
        runSuspendCatching {
            val normalizedUrl = canonicalizeFeedUrl(normalizeUserSubmittedUrl(rawInputUrl))
            check(normalizedUrl.isNotBlank()) { "Enter a valid site or feed URL" }
            probeSiteForRssFeed(apiClient, normalizedUrl)
        }
    }

private suspend fun probeSiteForRssFeed(
    apiClient: RocheApiClient,
    siteUrl: String,
): DiscoveredFeedResult {
    val pageBody =
        apiClient.fetchExternalFeedXml(siteUrl).getOrElse { error ->
            if (isLikelyFeedUrl(siteUrl)) {
                return DiscoveredFeedResult(feedUrl = siteUrl, siteTitle = null)
            }
            throw IllegalArgumentException(
                error.message ?: "Could not reach ${extractHostTitle(siteUrl)}",
                error,
            )
        }
    val directFeed = runCatching {
        LocalFeedPoller.parseFeedXml(xml = pageBody, feedUrl = siteUrl)
    }
        .getOrNull()
    if (directFeed != null) {
        return DiscoveredFeedResult(
            feedUrl = siteUrl,
            siteTitle = directFeed.title,
            prefetchedBatch = directFeed,
        )
    }

    val doc = Jsoup.parse(pageBody, siteUrl)
    val pageTitle = extractCleanDocumentTitle(doc)
    val candidates = extractDocFeedCandidates(doc, siteUrl)
    for (candidateUrl in candidates.take(MAX_DISCOVERY_PROBES)) {
        val probed =
            apiClient.fetchExternalFeedXml(candidateUrl).getOrNull()?.let { xml ->
                runCatching { LocalFeedPoller.parseFeedXml(xml = xml, feedUrl = candidateUrl) }
                    .getOrNull()
            }
        if (probed != null) {
            return DiscoveredFeedResult(
                feedUrl = candidateUrl,
                siteTitle = probed.title ?: pageTitle,
                prefetchedBatch = probed,
            )
        }
    }
    error("No RSS or Atom feed found at ${extractHostTitle(siteUrl)}")
}

internal fun extractFeedCandidatesFromHtml(html: String, baseUrl: String): List<String> =
    extractDocFeedCandidates(Jsoup.parse(html, baseUrl), baseUrl)

private fun extractDocFeedCandidates(doc: Document, baseUrl: String): List<String> {
    val primaryAlternates = ArrayList<String>()
    val commentAlternates = ArrayList<String>()
    collectAlternateFeedLinks(doc, primaryAlternates, commentAlternates)
    val youtubeFeed = extractYoutubeChannelFeed(doc)
    val anchorFeeds =
        doc.select("a[href]")
            .mapNotNull { anchor -> anchor.absUrl("href").trim().takeIf { isLikelyFeedUrl(it) } }
            .distinct()
    val fallbackPaths = buildOriginFallbackFeedUrls(baseUrl)

    return buildList {
        addAll(primaryAlternates)
        if (youtubeFeed != null) add(youtubeFeed)
        addAll(anchorFeeds)
        addAll(commentAlternates)
        addAll(fallbackPaths)
    }
        .map { it.trim() }
        .filter { isHttpOrHttpsUrl(it) && !it.equals(baseUrl, ignoreCase = true) }
        .distinct()
}

private fun collectAlternateFeedLinks(
    doc: Document,
    primaryOut: MutableList<String>,
    commentsOut: MutableList<String>,
) {
    for (link in doc.select("link[href]")) {
        val relTokens = link.attr("rel").lowercase(Locale.ROOT).split(WHITESPACE_SPLIT_REGEX)
        val type = link.attr("type").lowercase(Locale.ROOT).substringBefore(';').trim()
        val isAlternateFeed =
            ("alternate" in relTokens || "feed" in relTokens) && type in FEED_LINK_MIME_TYPES
        val resolved = if (isAlternateFeed) link.absUrl("href").trim() else ""
        if (resolved.isNotEmpty()) {
            val titleAttr = link.attr("title").lowercase(Locale.ROOT)
            val isCommentsFeed =
                resolved.contains("comments/feed", ignoreCase = true) || "comments" in titleAttr
            if (isCommentsFeed) {
                commentsOut.add(resolved)
            } else {
                primaryOut.add(resolved)
            }
        }
    }
}

private fun extractYoutubeChannelFeed(doc: Document): String? {
    val metaChannelId =
        doc.selectFirst("meta[itemprop=channelId]")?.attr(ATTR_CONTENT)?.trim()?.takeIf {
            isYoutubeChannelId(it)
        }
    val canonicalHref = doc.selectFirst("link[rel=canonical]")?.absUrl("href").orEmpty()
    val canonicalId = extractYoutubeChannelId(canonicalHref, matchPathOnly = true)
    val channelId = metaChannelId ?: canonicalId ?: return null
    return buildYoutubeChannelFeedUrl(channelId)
}

private fun buildOriginFallbackFeedUrls(baseUrl: String): List<String> {
    val uri = runCatching { URI.create(baseUrl.trim()) }.getOrNull() ?: return emptyList()
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return emptyList()
    val host = uri.host?.lowercase(Locale.ROOT) ?: return emptyList()
    if (scheme !in HTTP_SCHEMES || host.isBlank()) return emptyList()
    val portPart = if (uri.port != -1) ":${uri.port}" else ""
    val origin = "$scheme://$host$portPart"
    return COMMON_FEED_PATHS.map { path -> "$origin$path" }
}

@Suppress("LongParameterList")
internal suspend fun enrichWantToReadItemOnDevice(
    dao: RocheDao,
    apiClient: RocheApiClient,
    itemId: String,
    url: String,
    hasUserTitleOverride: Boolean,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
) {
    if (!isHttpOrHttpsUrl(url)) return
    val arxivId = extractArxivIdentifier(url)
    if (arxivId != null) {
        enrichArxivItemOnDevice(dao, apiClient, itemId, arxivId, hasUserTitleOverride, nodeId)
        return
    }
    if (inferContentType(url) != ContentType.ARTICLE) return
    val html = apiClient.fetchExternalFeedXml(url).getOrNull() ?: return
    val snapshot = withContext(Dispatchers.Default) { extractWebPageSnapshot(html, url) }
    applyWebPageSnapshotToItem(dao, itemId, snapshot, hasUserTitleOverride, nodeId)
}

@Suppress("LongParameterList")
private suspend fun enrichArxivItemOnDevice(
    dao: RocheDao,
    apiClient: RocheApiClient,
    itemId: String,
    arxivId: String,
    hasUserTitleOverride: Boolean,
    nodeId: String,
) {
    val queryUrl = "https://export.arxiv.org/api/query?id_list=$arxivId"
    val xml = apiClient.fetchExternalFeedXml(queryUrl).getOrNull() ?: return
    val candidate =
        withContext(Dispatchers.Default) {
            runCatching { LocalFeedPoller.parseFeedXml(xml, queryUrl, "arxiv") }
                .getOrNull()
                ?.items
                ?.firstOrNull()
        } ?: return
    val cleanTitle =
        candidate.title.replace(WHITESPACE_SPLIT_REGEX, " ").trim().takeIf {
            it.isNotEmpty()
        }
    dao.enrichEntryMetadataIfPresent(
        itemId = itemId,
        title = cleanTitle,
        hasUserTitleOverride = hasUserTitleOverride,
        author = candidate.author,
        summary = candidate.summary,
        publishedAt = candidate.publishedAt,
        nodeId = nodeId,
    )
}

internal fun extractWebPageSnapshot(rawHtml: String, pageUrl: String): ExtractedWebPageSnapshot {
    val doc = Jsoup.parse(rawHtml, pageUrl)
    doc.outputSettings().prettyPrint(false)
    val title = extractCleanDocumentTitle(doc)
    val author =
        doc.selectFirst("meta[name=author], meta[property=article:author]")
            ?.attr(ATTR_CONTENT)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    val summary =
        doc.selectFirst(
                "meta[property=og:description], meta[name=description], meta[name=twitter:description]"
            )
            ?.attr(ATTR_CONTENT)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    val thumb =
        doc.selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.absUrl(ATTR_CONTENT)
            ?.trim()
            ?.takeIf { isHttpOrHttpsUrl(it) }
    doc.select("script, style, noscript, nav, header, footer, aside, form, iframe").remove()
    val container =
        doc.selectFirst("article")
            ?: doc.selectFirst("main")
            ?: doc.selectFirst("[role=main]")
            ?: doc.body()
    val bodyHtml =
        sanitizeFeedHtml(container.html(), pageUrl)
            .takeIf { it.isNotBlank() }
            ?.take(MAX_SQLITE_HTML_CHARS)
    return ExtractedWebPageSnapshot(
        title = title,
        author = author,
        summary = summary,
        contentHtml = bodyHtml,
        thumbnailUrl = thumb,
    )
}

private suspend fun applyWebPageSnapshotToItem(
    dao: RocheDao,
    itemId: String,
    snapshot: ExtractedWebPageSnapshot,
    hasUserTitleOverride: Boolean,
    nodeId: String,
) {
    dao.enrichEntryMetadataIfPresent(
        itemId = itemId,
        title = snapshot.title,
        hasUserTitleOverride = hasUserTitleOverride,
        author = snapshot.author,
        summary = snapshot.summary,
        thumbnailUrl = snapshot.thumbnailUrl,
        contentHtml = snapshot.contentHtml,
        nodeId = nodeId,
    )
}

internal suspend fun copySharedPdfStreamToCache(
    dao: RocheDao,
    cacheDir: File?,
    itemId: String,
    stream: InputStream,
): Boolean =
    withContext(Dispatchers.IO) {
        val copied =
            if (cacheDir == null) {
                runCatching { stream.close() }
                false
            } else {
                writeSharedPdfToItemDir(cacheDir, itemId, stream)
            }
        if (copied) {
            dao.updateItemCachedOffline(itemId, true)
        }
        copied
    }

internal suspend fun writeSharedPdfToItemDir(
    rootDir: File,
    itemId: String,
    stream: InputStream,
): Boolean = runSuspendCatching {
    stream.use { input ->
        val safeItemId = sanitizeCacheFileName(itemId)
        if (safeItemId != itemId.trim()) {
            false
        } else {
            val itemsRoot = File(rootDir, ITEMS_SUBDIR).apply { mkdirs() }
            val itemDir = resolveSafeChildFile(itemsRoot, safeItemId).apply { mkdirs() }
            val targetFile = File(itemDir, SHARED_PDF_FILENAME).canonicalFile
            check(targetFile.parentFile == itemDir.canonicalFile) {
                "Resolved PDF path escapes item directory"
            }
            val tempFile = File(itemDir, "$SHARED_PDF_FILENAME.${System.nanoTime()}.tmp")
            try {
                val totalBytes = copyBoundedStreamToFile(input, tempFile)
                if (totalBytes <= 0L) {
                    false
                } else {
                    moveFileAtomically(tempFile, targetFile)
                    true
                }
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }
    }
}
    .getOrDefault(false)

private fun copyBoundedStreamToFile(input: InputStream, target: File): Long {
    var totalBytes = 0L
    val header = ByteArray(PDF_MAGIC_BYTES.size)
    var headerRead = 0
    target.outputStream().use { output ->
        val buffer = ByteArray(PDF_COPY_BUFFER_BYTES)
        var read = input.read(buffer)
        while (read != -1) {
            if (read > 0) {
                headerRead = accumulateAndVerifyPdfHeader(buffer, read, header, headerRead)
                totalBytes += read
                check(totalBytes <= MAX_SHARED_PDF_BYTES) { "Shared PDF exceeds maximum size" }
                output.write(buffer, 0, read)
            }
            read = input.read(buffer)
        }
    }
    check(hasPdfMagicHeader(header, headerRead)) {
        "Shared stream is not a valid PDF document"
    }
    return totalBytes
}

private fun accumulateAndVerifyPdfHeader(
    buffer: ByteArray,
    read: Int,
    header: ByteArray,
    headerRead: Int,
): Int {
    if (headerRead >= PDF_MAGIC_BYTES.size) return headerRead
    val take = minOf(read, PDF_MAGIC_BYTES.size - headerRead)
    System.arraycopy(buffer, 0, header, headerRead, take)
    val nextHeaderRead = headerRead + take
    if (nextHeaderRead == PDF_MAGIC_BYTES.size) {
        check(hasPdfMagicHeader(header, nextHeaderRead)) {
            "Shared stream is not a valid PDF document"
        }
    }
    return nextHeaderRead
}

private fun extractCleanDocumentTitle(doc: Document): String? =
    doc.selectFirst("meta[property=og:title]")?.attr(ATTR_CONTENT)?.trim()?.takeIf {
        it.isNotEmpty()
    } ?: doc.title().trim().takeIf { it.isNotEmpty() }

private const val ATTR_CONTENT = "content"
private const val MAX_DISCOVERY_PROBES = 6
private const val PDF_COPY_BUFFER_BYTES = 16 * 1024
private const val MAX_SHARED_PDF_BYTES = 50L * 1024L * 1024L
private val HTTP_SCHEMES = setOf("http", "https")
private val WHITESPACE_SPLIT_REGEX = Regex("""\s+""")
private val FEED_LINK_MIME_TYPES =
    setOf(
        "application/rss+xml",
        "application/atom+xml",
        "application/rdf+xml",
        "application/feed+json",
        "application/xml",
        "text/xml",
    )
private val COMMON_FEED_PATHS =
    listOf("/feed", "/rss.xml", "/atom.xml", "/feed.xml", "/index.xml", "/rss")

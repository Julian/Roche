package com.grayvines.roche.data

import com.grayvines.roche.data.local.InboxItemRow
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.upsertItem
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.remote.moveFileAtomically
import com.grayvines.roche.model.ContentType
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
    val lower = trimmed.lowercase(Locale.ROOT)
    return when {
        lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("localpdf://") -> trimmed
        "://" in lower -> trimmed
        else -> "https://$trimmed"
    }
}

internal suspend fun discoverFeedFromUrl(
    apiClient: RocheApiClient,
    rawInputUrl: String,
): Result<DiscoveredFeedResult> =
    withContext(Dispatchers.Default) {
        runSuspendCatching {
            val normalizedUrl = normalizeUserSubmittedUrl(rawInputUrl)
            check(normalizedUrl.isNotBlank()) { "Enter a valid site or feed URL" }
            if (isLikelyFeedUrl(normalizedUrl)) {
                DiscoveredFeedResult(
                    feedUrl = canonicalizeFeedUrl(normalizedUrl),
                    siteTitle = null,
                )
            } else {
                probeSiteForRssFeed(apiClient, normalizedUrl)
            }
        }
    }

private suspend fun probeSiteForRssFeed(
    apiClient: RocheApiClient,
    siteUrl: String,
): DiscoveredFeedResult {
    val pageBody =
        apiClient.fetchExternalFeedXml(siteUrl).getOrElse { error ->
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
        .filter { isHttpOrHttpsCandidate(it) && !it.equals(baseUrl, ignoreCase = true) }
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
    val canonicalId =
        YOUTUBE_CANONICAL_CHANNEL_REGEX.find(canonicalHref)?.groupValues?.getOrNull(1)?.takeIf {
            isYoutubeChannelId(it)
        }
    val channelId = metaChannelId ?: canonicalId ?: return null
    return "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
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

internal suspend fun enrichWantToReadItemOnDevice(
    dao: RocheDao,
    apiClient: RocheApiClient,
    itemId: String,
    url: String,
    hasUserTitleOverride: Boolean,
) {
    if (!isHttpOrHttpsCandidate(url)) return
    val arxivId = extractArxivIdentifier(url)
    if (arxivId != null) {
        enrichArxivItemOnDevice(dao, apiClient, itemId, arxivId, hasUserTitleOverride)
        return
    }
    if (inferContentType(url) != ContentType.ARTICLE) return
    val html = apiClient.fetchExternalFeedXml(url).getOrNull() ?: return
    val snapshot = withContext(Dispatchers.Default) { extractWebPageSnapshot(html, url) }
    applyWebPageSnapshotToItem(dao, itemId, snapshot, hasUserTitleOverride)
}

private suspend fun enrichArxivItemOnDevice(
    dao: RocheDao,
    apiClient: RocheApiClient,
    itemId: String,
    arxivId: String,
    hasUserTitleOverride: Boolean,
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
    val existing = dao.getItemById(itemId) ?: return
    val nextTitle =
        if (hasUserTitleOverride) {
            existing.title
        } else {
            candidate.title.replace(WHITESPACE_SPLIT_REGEX, " ").trim().ifBlank { existing.title }
        }
    dao.upsertItem(
        existing.copy(
            title = nextTitle,
            author = existing.author?.takeIf { it.isNotBlank() } ?: candidate.author,
            summary = existing.summary?.takeIf { it.isNotBlank() } ?: candidate.summary,
            publishedAt = existing.publishedAt ?: candidate.publishedAt,
            inboxState = "none",
        ),
        markDirty = true,
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
            ?.takeIf { isHttpOrHttpsCandidate(it) }
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
) {
    val existing: InboxItemRow = dao.getItemById(itemId) ?: return
    val resolvedTitle =
        if (hasUserTitleOverride) {
            existing.title
        } else {
            snapshot.title?.takeIf { it.isNotBlank() } ?: existing.title
        }
    dao.upsertItem(
        existing.copy(
            title = resolvedTitle,
            author = existing.author?.takeIf { it.isNotBlank() } ?: snapshot.author,
            summary = existing.summary?.takeIf { it.isNotBlank() } ?: snapshot.summary,
            contentHtml = existing.contentHtml?.takeIf { it.isNotBlank() } ?: snapshot.contentHtml,
            thumbnailUrl =
                existing.thumbnailUrl?.takeIf { it.isNotBlank() } ?: snapshot.thumbnailUrl,
            inboxState = "none",
        ),
        markDirty = isSyncableItemUrl(existing.url),
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

private fun writeSharedPdfToItemDir(
    rootDir: File,
    itemId: String,
    stream: InputStream,
): Boolean = runCatching {
    stream.use { input ->
        val itemDir = File(File(rootDir, "items"), itemId).apply { mkdirs() }
        val targetFile = File(itemDir, "shared_document.pdf")
        val tempFile = File(itemDir, "shared_document.pdf.tmp")
        val totalBytes = copyBoundedStreamToFile(input, tempFile)
        if (totalBytes <= 0L) {
            tempFile.delete()
            false
        } else {
            moveFileAtomically(tempFile, targetFile)
            true
        }
    }
}
    .getOrDefault(false)

private fun copyBoundedStreamToFile(input: InputStream, target: File): Long {
    var totalBytes = 0L
    target.outputStream().use { output ->
        val buffer = ByteArray(PDF_COPY_BUFFER_BYTES)
        var read = input.read(buffer)
        while (read > 0) {
            totalBytes += read
            check(totalBytes <= MAX_SHARED_PDF_BYTES) { "Shared PDF exceeds maximum size" }
            output.write(buffer, 0, read)
            read = input.read(buffer)
        }
    }
    return totalBytes
}

private fun extractCleanDocumentTitle(doc: Document): String? =
    doc.selectFirst("meta[property=og:title]")?.attr(ATTR_CONTENT)?.trim()?.takeIf {
        it.isNotEmpty()
    } ?: doc.title().trim().takeIf { it.isNotEmpty() }

private fun isHttpOrHttpsCandidate(url: String): Boolean =
    url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

private const val ATTR_CONTENT = "content"

private const val MAX_DISCOVERY_PROBES = 6
private const val PDF_COPY_BUFFER_BYTES = 16 * 1024
private const val MAX_SHARED_PDF_BYTES = 50L * 1024L * 1024L
private val HTTP_SCHEMES = setOf("http", "https")
private val WHITESPACE_SPLIT_REGEX = Regex("""\s+""")
private val YOUTUBE_CANONICAL_CHANNEL_REGEX =
    Regex("""youtube\.com/channel/([A-Za-z0-9_-]{24})""", RegexOption.IGNORE_CASE)
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

@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data.feeds

import com.grayvines.roche.core.DEFAULT_LOCAL_HLC_NODE
import com.grayvines.roche.core.ExtractedDocumentMetadata
import com.grayvines.roche.core.ParsedPublicationDate
import com.grayvines.roche.core.SCHEME_ARXIV
import com.grayvines.roche.core.SCHEME_DOI
import com.grayvines.roche.core.SCHEME_ISBN
import com.grayvines.roche.core.buildYoutubeChannelFeedUrl
import com.grayvines.roche.core.canonicalizeSyncUrl
import com.grayvines.roche.core.coreJson
import com.grayvines.roche.core.encodeQueryValue
import com.grayvines.roche.core.extractArxivIdFromText
import com.grayvines.roche.core.extractArxivIdentifier
import com.grayvines.roche.core.extractDoiFromText
import com.grayvines.roche.core.extractHostTitle
import com.grayvines.roche.core.extractQueryParam
import com.grayvines.roche.core.extractYoutubeChannelId
import com.grayvines.roche.core.fillMissingFrom
import com.grayvines.roche.core.inferFeedTypeWireValue
import com.grayvines.roche.core.isArxivPaperUrl
import com.grayvines.roche.core.isArxivUrl
import com.grayvines.roche.core.isEmpty
import com.grayvines.roche.core.isHttpOrHttpsUrl
import com.grayvines.roche.core.isLikelyFeedUrl
import com.grayvines.roche.core.isLocalPdfUrl
import com.grayvines.roche.core.isSyncablePublicUrl
import com.grayvines.roche.core.isValidArxivId
import com.grayvines.roche.core.isYoutubeChannelFeedInput
import com.grayvines.roche.core.isYoutubeChannelId
import com.grayvines.roche.core.isYoutubeFeedUrl
import com.grayvines.roche.core.isYoutubeUrl
import com.grayvines.roche.core.normalizeDoi
import com.grayvines.roche.core.normalizeIsbn
import com.grayvines.roche.core.normalizeWhitespace
import com.grayvines.roche.core.parsePublicationDateStr
import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.core.splitAuthorNames
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.local.DbWriteLock
import com.grayvines.roche.data.local.DocumentEnrichmentPatch
import com.grayvines.roche.data.local.MAX_SQLITE_HTML_CHARS
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.applyDocumentEnrichment
import com.grayvines.roche.data.remote.PublicWebFetcher
import com.grayvines.roche.media.findCachedPdfFileForEntry
import com.grayvines.roche.model.ContentType
import com.grayvines.roche.model.FeedType
import com.grayvines.roche.model.inferContentType
import com.grayvines.roche.reader.parseStylesheetRules
import com.grayvines.roche.reader.sanitizeRocheHtml
import com.grayvines.roche.reader.selectArticleContainer
import java.io.File
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
    val authors: List<String> = listOfNotNull(author),
    val publishedAt: String? = null,
    val releaseYear: Int? = null,
    val doi: String? = null,
    val isbn: String? = null,
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

internal fun validateEditedFeedUrl(rawInput: String, currentFeedType: FeedType): String {
    val trimmed = rawInput.trim()
    require(trimmed.isNotEmpty()) { "Feed URL cannot be empty" }
    val preCanonical = canonicalizeFeedUrl(normalizeUserSubmittedUrl(trimmed))
    val normalized = canonicalizeSyncUrl(preCanonical)
    require(normalized.none(Char::isWhitespace) && isSyncablePublicUrl(normalized)) {
        "Enter a valid public http:// or https:// feed URL"
    }
    validateEditedFeedUrlForType(trimmed, normalized, currentFeedType)
    return normalized
}

private fun validateEditedFeedUrlForType(
    rawTrimmed: String,
    normalizedUrl: String,
    currentFeedType: FeedType,
) {
    when (currentFeedType) {
        FeedType.YOUTUBE -> {
            require(isYoutubeFeedUrl(normalizedUrl)) {
                "YouTube feed URL must be a channel ID, /channel/UC... URL, or youtube.com/feeds/videos.xml URL"
            }
        }
        FeedType.ARXIV -> {
            require(isArxivUrl(normalizedUrl)) { "arXiv feed URL must point to arxiv.org" }
            val path = runCatching { URI.create(normalizedUrl).path.orEmpty() }.getOrDefault("")
            val isSinglePaper =
                isArxivPaperUrl(normalizedUrl) ||
                    path.startsWith("/abs/") ||
                    path.startsWith("/pdf/") ||
                    path.endsWith(".pdf", ignoreCase = true)
            require(!isSinglePaper) { "URL points to a single arXiv paper, not an arXiv feed" }
        }
        FeedType.RSS,
        FeedType.PODCAST,
        FeedType.SAVED -> {
            val isYoutubeTarget = isYoutubeFeedOrChannelTarget(rawTrimmed, normalizedUrl)
            require(!isYoutubeTarget && !isArxivUrl(normalizedUrl)) {
                "Feed URL points to a different source kind than this feed"
            }
        }
    }
}

private fun isYoutubeFeedOrChannelTarget(rawTrimmed: String, normalizedUrl: String): Boolean {
    if (isYoutubeChannelFeedInput(rawTrimmed) || isYoutubeFeedUrl(normalizedUrl)) return true
    if (!isYoutubeUrl(normalizedUrl)) return false
    if (extractYoutubeChannelId(normalizedUrl, matchPathOnly = true) != null) return true
    val query = runCatching { URI.create(normalizedUrl).rawQuery }.getOrNull()
    val chanParam = extractQueryParam(query, "channel_id")
    return chanParam != null && isYoutubeChannelId(chanParam)
}

internal suspend fun discoverFeedFromUrl(
    publicWebFetcher: PublicWebFetcher,
    rawInputUrl: String,
    requestedFeedType: String? = null,
): Result<DiscoveredFeedResult> =
    withContext(Dispatchers.Default) {
        runSuspendCatching {
            val normalizedUrl = canonicalizeFeedUrl(normalizeUserSubmittedUrl(rawInputUrl))
            check(normalizedUrl.isNotBlank()) { "Enter a valid site or feed URL" }
            probeSiteForRssFeed(publicWebFetcher, normalizedUrl, requestedFeedType)
        }
    }

private suspend fun probeSiteForRssFeed(
    publicWebFetcher: PublicWebFetcher,
    siteUrl: String,
    requestedFeedType: String? = null,
): DiscoveredFeedResult {
    val pageBody =
        publicWebFetcher.fetchFeedXml(siteUrl).getOrElse { error ->
            if (isLikelyFeedUrl(siteUrl)) {
                return DiscoveredFeedResult(feedUrl = siteUrl, siteTitle = null)
            }
            throw IllegalArgumentException(
                error.message ?: "Could not reach ${extractHostTitle(siteUrl)}",
                error,
            )
        }
    val directFeed = runCatching {
        LocalFeedPoller.parseFeedXml(
            xml = pageBody,
            feedUrl = siteUrl,
            feedType = requestedFeedType ?: inferFeedTypeWireValue(siteUrl),
        )
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
            publicWebFetcher.fetchFeedXml(candidateUrl).getOrNull()?.let { xml ->
                runCatching {
                    LocalFeedPoller.parseFeedXml(
                        xml = xml,
                        feedUrl = candidateUrl,
                        feedType = requestedFeedType ?: inferFeedTypeWireValue(candidateUrl),
                    )
                }
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
    publicWebFetcher: PublicWebFetcher,
    itemId: String,
    url: String,
    hasUserTitleOverride: Boolean,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
    writeLock: DbWriteLock = DbWriteLock(),
    cacheDir: File? = null,
    forceRefresh: Boolean = false,
): Boolean {
    val isLocalPdf = isLocalPdfUrl(url)
    if (!isLocalPdf && !isHttpOrHttpsUrl(url)) return false
    val cachedPdf = cacheDir?.let { findCachedPdfFileForEntry(it, itemId) }
    val arxivId = if (!isLocalPdf) extractArxivIdentifier(url) else null
    return when {
        arxivId != null ->
            enrichArxivItemOnDevice(
                dao = dao,
                publicWebFetcher = publicWebFetcher,
                itemId = itemId,
                arxivId = arxivId,
                hasUserTitleOverride = hasUserTitleOverride,
                nodeId = nodeId,
                writeLock = writeLock,
                forceRefresh = forceRefresh,
            )
        isLocalPdf || inferContentType(url) == ContentType.PDF ->
            enrichPdfItemOnDevice(
                dao = dao,
                publicWebFetcher = publicWebFetcher,
                itemId = itemId,
                url = url,
                cachedPdf = cachedPdf,
                hasUserTitleOverride = hasUserTitleOverride,
                nodeId = nodeId,
                writeLock = writeLock,
                forceRefresh = forceRefresh,
            )
        inferContentType(url) == ContentType.ARTICLE ->
            enrichHtmlItemOnDevice(
                dao = dao,
                publicWebFetcher = publicWebFetcher,
                itemId = itemId,
                url = url,
                hasUserTitleOverride = hasUserTitleOverride,
                nodeId = nodeId,
                writeLock = writeLock,
                forceRefresh = forceRefresh,
            )
        else -> false
    }
}

@Suppress("LongParameterList")
internal suspend fun enrichDocumentEntryOnDevice(
    dao: RocheDao,
    publicWebFetcher: PublicWebFetcher,
    entryId: String,
    cacheDir: File? = null,
    forceRefresh: Boolean = true,
    nodeId: String = DEFAULT_LOCAL_HLC_NODE,
    writeLock: DbWriteLock = DbWriteLock(),
): Boolean {
    val entry = dao.getEntryById(entryId) ?: return false
    val kind = EntryKind.fromWireValue(entry.kind)
    val isDocKind = kind == EntryKind.PAPER || kind == EntryKind.ARTICLE || kind == EntryKind.BOOK
    if (!isDocKind) return false
    val url = entry.url?.trim().orEmpty()
    val enrichedByUrl =
        if (url.isNotEmpty() && (isLocalPdfUrl(url) || isHttpOrHttpsUrl(url))) {
            enrichWantToReadItemOnDevice(
                dao = dao,
                publicWebFetcher = publicWebFetcher,
                itemId = entryId,
                url = url,
                hasUserTitleOverride = false,
                nodeId = nodeId,
                writeLock = writeLock,
                cacheDir = cacheDir,
                forceRefresh = forceRefresh,
            )
        } else {
            false
        }
    return enrichedByUrl ||
        enrichDocumentFromStoredIdents(
            dao = dao,
            publicWebFetcher = publicWebFetcher,
            entryId = entryId,
            entryUrl = entry.url,
            cacheDir = cacheDir,
            forceRefresh = forceRefresh,
            nodeId = nodeId,
            writeLock = writeLock,
        )
}

@Suppress("LongParameterList")
private suspend fun enrichDocumentFromStoredIdents(
    dao: RocheDao,
    publicWebFetcher: PublicWebFetcher,
    entryId: String,
    entryUrl: String?,
    cacheDir: File?,
    forceRefresh: Boolean,
    nodeId: String,
    writeLock: DbWriteLock,
): Boolean {
    val idents = dao.getIdentifiersForEntry(entryId)
    val seedMeta =
        ExtractedDocumentMetadata(
            doi = idents.firstOrNull { it.scheme == SCHEME_DOI }?.value?.let(::normalizeDoi),
            arxivId = idents.firstOrNull { it.scheme == SCHEME_ARXIV }?.value,
            isbn = idents.firstOrNull { it.scheme == SCHEME_ISBN }?.value,
        )
    val cachedPdf = cacheDir?.let { findCachedPdfFileForEntry(it, entryId) }
    val discovered =
        when {
            cachedPdf != null ->
                extractPdfMetadataOnDevice(cachedPdf, null, entryUrl, publicWebFetcher)
                    .fillMissingFrom(seedMeta)
            seedMeta.doi != null || seedMeta.arxivId != null ->
                lookupExternalDocumentMetadata(seedMeta, publicWebFetcher)
            else -> ExtractedDocumentMetadata()
        }
    if (discovered.isEmpty()) return false
    return writeLock.withLock {
        dao.applyDocumentEnrichment(
            DocumentEnrichmentPatch(
                entryId = entryId,
                title = discovered.title,
                hasUserTitleOverride = false,
                authors = discovered.authors,
                summary = discovered.summary,
                publishedAt = discovered.publishedAt,
                releaseYear = discovered.releaseYear,
                doi = discovered.doi,
                arxivId = discovered.arxivId,
                isbn = discovered.isbn,
                forceRefresh = forceRefresh,
                nodeId = nodeId,
            )
        )
    }
}

@Suppress("LongParameterList")
private suspend fun enrichPdfItemOnDevice(
    dao: RocheDao,
    publicWebFetcher: PublicWebFetcher,
    itemId: String,
    url: String,
    cachedPdf: File?,
    hasUserTitleOverride: Boolean,
    nodeId: String,
    writeLock: DbWriteLock,
    forceRefresh: Boolean,
): Boolean {
    val pdfBytes =
        if (cachedPdf != null) {
            null
        } else if (isHttpOrHttpsUrl(url)) {
            publicWebFetcher.fetchPdfBytes(url)
        } else {
            null
        }
    if (cachedPdf == null && pdfBytes == null) return false
    val discovered =
        extractPdfMetadataOnDevice(
            pdfFile = cachedPdf,
            pdfBytes = pdfBytes,
            sourceUrl = url,
            publicWebFetcher = publicWebFetcher,
        )
    if (discovered.isEmpty()) return false
    return writeLock.withLock {
        dao.applyDocumentEnrichment(
            DocumentEnrichmentPatch(
                entryId = itemId,
                title = discovered.title,
                hasUserTitleOverride = hasUserTitleOverride,
                authors = discovered.authors,
                summary = discovered.summary,
                publishedAt = discovered.publishedAt,
                releaseYear = discovered.releaseYear,
                doi = discovered.doi,
                arxivId = discovered.arxivId,
                isbn = discovered.isbn,
                forceRefresh = forceRefresh,
                nodeId = nodeId,
            )
        )
    }
}

@Suppress("LongParameterList")
private suspend fun enrichHtmlItemOnDevice(
    dao: RocheDao,
    publicWebFetcher: PublicWebFetcher,
    itemId: String,
    url: String,
    hasUserTitleOverride: Boolean,
    nodeId: String,
    writeLock: DbWriteLock,
    forceRefresh: Boolean,
): Boolean {
    val html = publicWebFetcher.fetchHtml(url).getOrNull() ?: return false
    val snapshot = withContext(Dispatchers.Default) { extractWebPageSnapshot(html, url) }
    var docMeta =
        ExtractedDocumentMetadata(
            title = snapshot.title,
            authors = snapshot.authors,
            publishedAt = snapshot.publishedAt,
            releaseYear = snapshot.releaseYear,
            summary = snapshot.summary,
            doi = snapshot.doi,
            arxivId = extractArxivIdentifier(url) ?: extractArxivIdFromText(url),
            isbn = snapshot.isbn,
        )
    if (docMeta.doi != null || docMeta.arxivId != null) {
        docMeta = lookupExternalDocumentMetadata(docMeta, publicWebFetcher)
    }
    return writeLock.withLock {
        dao.applyDocumentEnrichment(
            DocumentEnrichmentPatch(
                entryId = itemId,
                title = docMeta.title,
                hasUserTitleOverride = hasUserTitleOverride,
                authors = docMeta.authors,
                summary = docMeta.summary,
                publishedAt = docMeta.publishedAt,
                releaseYear = docMeta.releaseYear,
                thumbnailUrl = snapshot.thumbnailUrl,
                contentHtml = snapshot.contentHtml,
                doi = docMeta.doi,
                arxivId = docMeta.arxivId,
                isbn = docMeta.isbn,
                forceRefresh = forceRefresh,
                nodeId = nodeId,
            )
        )
    }
}

@Suppress("LongParameterList")
private suspend fun enrichArxivItemOnDevice(
    dao: RocheDao,
    publicWebFetcher: PublicWebFetcher,
    itemId: String,
    arxivId: String,
    hasUserTitleOverride: Boolean,
    nodeId: String,
    writeLock: DbWriteLock,
    forceRefresh: Boolean,
): Boolean {
    if (!isValidArxivId(arxivId)) return false
    val queryUrl = "https://export.arxiv.org/api/query?id_list=${encodeQueryValue(arxivId)}"
    val xml = publicWebFetcher.fetchFeedXml(queryUrl).getOrNull() ?: return false
    val candidate =
        withContext(Dispatchers.Default) {
            runCatching { LocalFeedPoller.parseFeedXml(xml, queryUrl, "arxiv") }
                .getOrNull()
                ?.items
                ?.firstOrNull()
        } ?: return false
    val cleanTitle =
        candidate.title.replace(WHITESPACE_SPLIT_REGEX, " ").trim().takeIf {
            it.isNotEmpty()
        }
    val authors = candidate.author?.let(::splitAuthorNames).orEmpty()
    return writeLock.withLock {
        dao.applyDocumentEnrichment(
            DocumentEnrichmentPatch(
                entryId = itemId,
                title = cleanTitle,
                hasUserTitleOverride = hasUserTitleOverride,
                authors = authors,
                summary = candidate.summary,
                publishedAt = candidate.publishedAt,
                arxivId = arxivId,
                forceRefresh = forceRefresh,
                nodeId = nodeId,
            )
        )
    }
}

internal fun extractWebPageSnapshot(rawHtml: String, pageUrl: String): ExtractedWebPageSnapshot {
    val doc = Jsoup.parse(rawHtml, pageUrl)
    doc.outputSettings().prettyPrint(false)
    val ld = extractJsonLdMetadata(doc)
    val title = extractCleanDocumentTitle(doc) ?: ld.title
    val authors = extractDocumentAuthors(doc, ld.authors)
    val parsedDate = extractDocumentPublishedDate(doc) ?: ld.date
    val doi = extractDocumentDoi(doc, pageUrl) ?: ld.doi
    val isbn = extractDocumentIsbn(doc) ?: ld.isbn
    val summary =
        doc.selectFirst(
                "meta[name=citation_abstract], meta[property=og:description], meta[name=description], meta[name=twitter:description]"
            )
            ?.attr(ATTR_CONTENT)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    val thumb =
        doc.selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.absUrl(ATTR_CONTENT)
            ?.trim()
            ?.takeIf { isHttpOrHttpsUrl(it) }
    val cssRules = parseStylesheetRules(doc)
    val container = selectArticleContainer(doc)
    val bodyHtml =
        sanitizeRocheHtml(container.html(), pageUrl, cssRules)
            .takeIf { it.isNotBlank() }
            ?.take(MAX_SQLITE_HTML_CHARS)
    return ExtractedWebPageSnapshot(
        title = title,
        author = authors.firstOrNull(),
        summary = summary,
        contentHtml = bodyHtml,
        thumbnailUrl = thumb,
        authors = authors,
        publishedAt = parsedDate?.publishedAt,
        releaseYear = parsedDate?.releaseYear,
        doi = doi,
        isbn = isbn,
    )
}

private data class JsonLdDocMeta(
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val date: ParsedPublicationDate? = null,
    val doi: String? = null,
    val isbn: String? = null,
)

private fun extractDocumentAuthors(doc: Document, ldAuthors: List<String>): List<String> {
    val citationAuthors =
        doc.select("meta[name=citation_author], meta[name=dc.creator], meta[name=DC.creator]")
            .mapNotNull { el -> el.attr(ATTR_CONTENT).trim().takeIf { it.isNotEmpty() } }
            .flatMap(::splitAuthorNames)
    if (citationAuthors.isNotEmpty()) {
        return citationAuthors.distinctBy { it.lowercase(Locale.ROOT) }
    }
    val metaAuthor =
        doc.selectFirst("meta[name=author], meta[property=article:author]")
            ?.attr(ATTR_CONTENT)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !isHttpOrHttpsUrl(it) }
            ?.let(::splitAuthorNames)
            .orEmpty()
    return metaAuthor.ifEmpty { ldAuthors }.distinctBy { it.lowercase(Locale.ROOT) }
}

private fun extractDocumentPublishedDate(doc: Document): ParsedPublicationDate? {
    val selectors =
        listOf(
            "meta[name=citation_publication_date]",
            "meta[name=citation_date]",
            "meta[name=citation_online_date]",
            "meta[property=article:published_time]",
            "meta[property=og:article:published_time]",
            "meta[name=pubdate]",
            "meta[name=publishdate]",
            "meta[name=dc.date]",
            "meta[name=DC.date]",
            "meta[name=dcterms.issued]",
            "meta[name=dcterms.created]",
            "meta[name=date]",
        )
    for (selector in selectors) {
        val content = doc.selectFirst(selector)?.attr(ATTR_CONTENT)?.trim().orEmpty()
        if (content.isNotEmpty()) {
            parsePublicationDateStr(content)?.let {
                return it
            }
        }
    }
    val timeAttr = doc.selectFirst("time[datetime]")?.attr("datetime")?.trim().orEmpty()
    return timeAttr.takeIf { it.isNotEmpty() }?.let(::parsePublicationDateStr)
}

private fun extractDocumentDoi(doc: Document, pageUrl: String): String? {
    for (el in
        doc.select(
            "meta[name=citation_doi], meta[name=dc.identifier], meta[name=DC.identifier], meta[name=prism.doi]"
        )) {
        val raw = el.attr(ATTR_CONTENT).trim()
        normalizeDoi(raw)?.let {
            return it
        }
    }
    return normalizeDoi(pageUrl) ?: extractDoiFromText(pageUrl)
}

private fun extractDocumentIsbn(doc: Document): String? {
    for (el in
        doc.select(
            "meta[name=citation_isbn], meta[property=book:isbn], meta[property=books:isbn]"
        )) {
        normalizeIsbn(el.attr(ATTR_CONTENT))?.let {
            return it
        }
    }
    return null
}

private fun extractJsonLdMetadata(doc: Document): JsonLdDocMeta {
    val objects =
        doc.select("script[type=application/ld+json]")
            .take(MAX_JSON_LD_SCRIPTS)
            .map { it.data().trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_JSON_LD_CHARS }
            .mapNotNull { runCatching { coreJson.parseToJsonElement(it) }.getOrNull() }
            .flatMap(::collectJsonLdObjects)
    return objects.fold(JsonLdDocMeta(), ::mergeJsonLdObject)
}

private fun mergeJsonLdObject(acc: JsonLdDocMeta, obj: JsonObject): JsonLdDocMeta {
    val title =
        acc.title
            ?: (obj.strField("headline") ?: obj.strField("name"))
                ?.let(::normalizeWhitespace)
                ?.takeIf { it.isNotEmpty() }
    val authors =
        acc.authors.ifEmpty {
            extractJsonLdAuthors(obj["author"]).distinctBy { it.lowercase(Locale.ROOT) }
        }
    val date =
        acc.date
            ?: (obj.strField("datePublished") ?: obj.strField("dateCreated"))?.let(
                ::parsePublicationDateStr
            )
    return JsonLdDocMeta(
        title = title,
        authors = authors,
        date = date,
        doi = acc.doi ?: extractJsonLdDoi(obj),
        isbn = acc.isbn ?: obj.strField("isbn")?.let(::normalizeIsbn),
    )
}

private fun collectJsonLdObjects(root: JsonElement): List<JsonObject> =
    when (root) {
        is JsonObject -> {
            val graph = (root["@graph"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            listOf(root) + graph
        }
        is JsonArray -> {
            root.mapNotNull { it as? JsonObject }
        }
        else -> {
            emptyList()
        }
    }

private fun extractJsonLdAuthors(element: JsonElement?): List<String> =
    when (element) {
        is JsonPrimitive -> element.contentOrNull?.let(::splitAuthorNames).orEmpty()
        is JsonObject -> element.strField("name")?.let(::splitAuthorNames).orEmpty()
        is JsonArray -> element.flatMap(::extractJsonLdAuthors)
        else -> emptyList()
    }

private fun extractJsonLdDoi(obj: JsonObject): String? {
    val rawCandidates = buildList {
        obj.strField("doi")?.let(::add)
        obj.strField("sameAs")?.let(::add)
        when (val ident = obj["identifier"]) {
            is JsonPrimitive -> ident.contentOrNull?.let(::add)
            is JsonObject -> ident.strField("value")?.let(::add)
            is JsonArray ->
                ident.forEach { el ->
                    when (el) {
                        is JsonPrimitive -> el.contentOrNull?.let(::add)
                        is JsonObject -> el.strField("value")?.let(::add)
                        else -> Unit
                    }
                }
            else -> Unit
        }
    }
    return rawCandidates.firstNotNullOfOrNull { normalizeDoi(it) ?: extractDoiFromText(it) }
}

private fun JsonObject.strField(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun extractCleanDocumentTitle(doc: Document): String? =
    doc.selectFirst("meta[name=citation_title]")?.attr(ATTR_CONTENT)?.trim()?.takeIf {
        it.isNotEmpty()
    }
        ?: doc.selectFirst("meta[property=og:title]")?.attr(ATTR_CONTENT)?.trim()?.takeIf {
            it.isNotEmpty()
        }
        ?: doc.title().trim().takeIf { it.isNotEmpty() }

private const val MAX_JSON_LD_SCRIPTS = 8
private const val MAX_JSON_LD_CHARS = 128_000

private const val ATTR_CONTENT = "content"
private const val MAX_DISCOVERY_PROBES = 6
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

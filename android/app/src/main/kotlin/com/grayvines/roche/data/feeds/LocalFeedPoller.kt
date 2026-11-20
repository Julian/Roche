@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data.feeds

import com.grayvines.roche.core.DEFAULT_LOCAL_HLC_NODE
import com.grayvines.roche.core.DEFAULT_SAVED_HOST
import com.grayvines.roche.core.MAX_CONSECUTIVE_FAILURES
import com.grayvines.roche.core.buildYoutubeChannelFeedUrl
import com.grayvines.roche.core.canonicalizeSyncUrl
import com.grayvines.roche.core.decodeHtmlEntities
import com.grayvines.roche.core.deterministicItemId
import com.grayvines.roche.core.extractArxivIdentifier
import com.grayvines.roche.core.extractHostTitle
import com.grayvines.roche.core.extractYoutubeChannelId
import com.grayvines.roche.core.formatDurationSecondsLabel
import com.grayvines.roche.core.formatUtcMillisIso
import com.grayvines.roche.core.inferFeedTypeWireValue
import com.grayvines.roche.core.isArxivUrl
import com.grayvines.roche.core.isDirectAudioUrl
import com.grayvines.roche.core.isHttpOrHttpsUrl
import com.grayvines.roche.core.isSyncablePublicUrl
import com.grayvines.roche.core.isYoutubeUrl
import com.grayvines.roche.core.itemNaturalKey
import com.grayvines.roche.core.normalizeConsecutiveFailures
import com.grayvines.roche.core.parseDurationToSecondsLong
import com.grayvines.roche.core.parseIsoInstant
import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.InboxItemRow
import com.grayvines.roche.data.local.MAX_SQLITE_HTML_CHARS
import com.grayvines.roche.data.local.MAX_SQLITE_SUMMARY_CHARS
import com.grayvines.roche.data.local.MAX_SQLITE_TEXT_CHARS
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.upsertFeed
import com.grayvines.roche.data.local.upsertItems
import com.grayvines.roche.data.remote.HttpStatusException
import com.grayvines.roche.data.remote.ProblemDetailsException
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.model.ItemStatus
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.jsoup.safety.Safelist

internal data class ParsedFeedCandidateItem(
    val title: String,
    val url: String,
    val author: String?,
    val publishedAt: String?,
    val summary: String,
    val contentHtml: String?,
    val thumbnailUrl: String?,
    val contentType: String,
    val audioUrl: String? = null,
    val duration: String? = null,
)

internal data class ParsedFeedBatch(
    val title: String?,
    val description: String?,
    val language: String?,
    val siteUrl: String?,
    val items: List<ParsedFeedCandidateItem>,
)

internal fun canonicalizeFeedUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    val channelId = extractYoutubeChannelId(trimmed)
    return if (channelId != null) buildYoutubeChannelFeedUrl(channelId) else trimmed
}

internal object LocalFeedPoller {

    @Suppress("LongParameterList")
    suspend fun pollFeed(
        dao: RocheDao,
        apiClient: RocheApiClient,
        feed: FeedRow,
        localPeerId: String,
        nowInstant: Instant? = null,
        prefetchedBatch: ParsedFeedBatch? = null,
        clock: () -> Instant = { Instant.now().truncatedTo(ChronoUnit.SECONDS) },
    ): Result<Int> =
        withContext(Dispatchers.Default) {
            pollFeedInternal(
                dao = dao,
                apiClient = apiClient,
                feed = feed,
                localPeerId = localPeerId,
                resolveNow = { nowInstant ?: clock() },
                prefetchedBatch = prefetchedBatch,
            )
        }

    @Suppress("LongParameterList")
    private suspend fun pollFeedInternal(
        dao: RocheDao,
        apiClient: RocheApiClient,
        feed: FeedRow,
        localPeerId: String,
        resolveNow: () -> Instant,
        prefetchedBatch: ParsedFeedBatch?,
    ): Result<Int> {
        val fetchUrl = canonicalizeFeedUrl(feed.url)
        val syncableFeed = isSyncablePublicUrl(feed.url)
        val effectiveNodeId = localPeerId.ifBlank { DEFAULT_LOCAL_HLC_NODE }
        val batch =
            if (prefetchedBatch != null) {
                prefetchedBatch
            } else {
                val xml =
                    apiClient.fetchExternalFeedXml(fetchUrl).getOrElse { error ->
                        recordPollFailure(
                            dao = dao,
                            feed = feed,
                            localPeerId = localPeerId,
                            completedIso = formatUtcMillisIso(resolveNow()),
                            errorMessage = sanitizeFeedFetchError(error),
                            syncableFeed = syncableFeed,
                            nodeId = effectiveNodeId,
                        )
                        return Result.failure(error)
                    }
                runSuspendCatching {
                    parseFeedXml(xml = xml, feedUrl = fetchUrl, feedType = feed.feedType)
                }
                    .getOrElse { error ->
                        recordPollFailure(
                            dao = dao,
                            feed = feed,
                            localPeerId = localPeerId,
                            completedIso = formatUtcMillisIso(resolveNow()),
                            errorMessage = "Feed XML parse failed",
                            syncableFeed = syncableFeed,
                            nodeId = effectiveNodeId,
                        )
                        return Result.failure(error)
                    }
            }

        val nowIso = formatUtcMillisIso(resolveNow())
        val updatedFeed = buildUpdatedPolledFeed(feed, batch, nowIso, localPeerId, syncableFeed)
        val configChanged = updatedFeed.title != feed.title || updatedFeed.feedType != feed.feedType
        dao.upsertFeed(
            feed = updatedFeed,
            markDirty = configChanged && syncableFeed,
            nodeId = effectiveNodeId,
        )

        val insertedOrUpdatedCount =
            upsertPolledItems(
                dao = dao,
                feed = updatedFeed,
                candidates = batch.items,
                nowIso = nowIso,
                markDirty = syncableFeed,
                nodeId = effectiveNodeId,
            )
        return Result.success(maxOf(insertedOrUpdatedCount, batch.items.size))
    }

    @Suppress("LongParameterList")
    private suspend fun recordPollFailure(
        dao: RocheDao,
        feed: FeedRow,
        localPeerId: String,
        completedIso: String,
        errorMessage: String,
        syncableFeed: Boolean,
        nodeId: String,
    ) {
        val previousFailures =
            normalizeConsecutiveFailures(feed.lastError, feed.consecutiveFailures)
        val nextFailures = (previousFailures + 1).coerceAtMost(MAX_CONSECUTIVE_FAILURES)
        dao.upsertFeed(
            feed =
                feed.copy(
                    lastPolled = completedIso,
                    lastError = errorMessage,
                    lastErrorBy = localPeerId,
                    consecutiveFailures = nextFailures,
                    isLocalDirty = syncableFeed || feed.isLocalDirty,
                ),
            nodeId = nodeId,
        )
    }

    private fun sanitizeFeedFetchError(error: Throwable): String =
        when (error) {
            is HttpStatusException -> {
                "HTTP ${error.statusCode}"
            }
            is ProblemDetailsException -> {
                "HTTP ${error.statusCode}"
            }
            else -> {
                val firstLine =
                    error.message
                        ?.lineSequence()
                        ?.firstOrNull()
                        ?.trim()
                        ?.take(MAX_FEED_ERROR_CHARS)
                        ?.takeIf { it.isNotEmpty() }
                firstLine ?: "Feed fetch failed"
            }
        }

    private fun buildUpdatedPolledFeed(
        feed: FeedRow,
        batch: ParsedFeedBatch,
        nowIso: String,
        localPeerId: String,
        syncableFeed: Boolean,
    ): FeedRow {
        val resolvedTitle =
            feed.title.takeIf {
                it.isNotBlank() &&
                    !it.equals(extractHostTitle(feed.url), ignoreCase = true) &&
                    it != "YouTube Channel"
            } ?: batch.title?.takeIf { it.isNotBlank() } ?: feed.title
        val hasAudio =
            batch.items.any { it.contentType == CONTENT_TYPE_AUDIO || !it.audioUrl.isNullOrBlank() }
        val resolvedFeedType =
            if (hasAudio && feed.feedType.equals("rss", ignoreCase = true)) {
                "podcast"
            } else {
                feed.feedType
            }
        return feed.copy(
            title = resolvedTitle,
            siteUrl = feed.siteUrl ?: batch.siteUrl,
            feedType = resolvedFeedType,
            description = feed.description ?: batch.description,
            language = feed.language ?: batch.language,
            lastPolled = nowIso,
            lastPolledBy = localPeerId,
            lastError = null,
            lastErrorBy = null,
            consecutiveFailures = 0,
            isLocalDirty = syncableFeed || feed.isLocalDirty,
        )
    }

    @Suppress("LongParameterList")
    private suspend fun upsertPolledItems(
        dao: RocheDao,
        feed: FeedRow,
        candidates: List<ParsedFeedCandidateItem>,
        nowIso: String,
        markDirty: Boolean,
        nodeId: String,
    ): Int {
        val existingItems = dao.getItemsForFeed(feedId = feed.id, feedUrl = feed.url)
        val tombstones =
            dao.getAllTombstones()
                .filter { it.entityType.equals("item", ignoreCase = true) }
                .map { it.naturalKey }
                .toSet()
        val byNaturalUrl = existingItems.associateBy { canonicalizeSyncUrl(it.url) }.toMutableMap()
        val byId = existingItems.associateBy { it.id }.toMutableMap()

        var newCount = 0
        val toUpsert = ArrayList<InboxItemRow>()
        val validCandidates = candidates.filter { candidate ->
            val cleanUrl = candidate.url.trim()
            cleanUrl.isNotBlank() && itemNaturalKey(feed.url, cleanUrl) !in tombstones
        }
        for (candidate in validCandidates) {
            val cleanUrl = candidate.url.trim()
            val canonicalUrlKey = canonicalizeSyncUrl(cleanUrl)
            val detId = deterministicItemId(feed.url, cleanUrl)
            val existing = byNaturalUrl[canonicalUrlKey] ?: byId[detId]
            if (existing != null) {
                val merged = mergePolledCandidate(existing, feed, candidate)
                if (merged != existing) {
                    toUpsert.add(merged)
                }
            } else {
                val created = buildNewPolledItemEntity(detId, feed, candidate, cleanUrl, nowIso)
                toUpsert.add(created)
                byNaturalUrl[canonicalUrlKey] = created
                byId[detId] = created
                newCount++
            }
        }
        if (toUpsert.isNotEmpty()) {
            dao.upsertItems(toUpsert, markDirty = markDirty, nodeId = nodeId)
        }
        return newCount
    }

    private fun buildNewPolledItemEntity(
        detId: String,
        feed: FeedRow,
        candidate: ParsedFeedCandidateItem,
        cleanUrl: String,
        nowIso: String,
    ): InboxItemRow =
        InboxItemRow(
            id = detId,
            feedId = feed.id,
            feedUrl = feed.url,
            feedTitle = feed.title,
            title = candidate.title.ifBlank { cleanUrl },
            url = cleanUrl,
            author = candidate.author,
            summary = candidate.summary.takeIf { it.isNotBlank() }?.take(MAX_SQLITE_SUMMARY_CHARS),
            contentText = null,
            contentHtml = candidate.contentHtml?.take(MAX_SQLITE_HTML_CHARS),
            contentType = candidate.contentType,
            status = ItemStatus.UNREAD.wireValue,
            isFavorite = false,
            isCachedOffline = false,
            publishedAt = candidate.publishedAt,
            createdAt = nowIso,
            readProgress = 0.0,
            thumbnailMediaId = null,
            duration = candidate.duration,
            readAt = null,
            thumbnailUrl = candidate.thumbnailUrl,
            interactionUpdatedAt = null,
            audioUrl = candidate.audioUrl,
        )

    private fun mergePolledCandidate(
        existing: InboxItemRow,
        feed: FeedRow,
        candidate: ParsedFeedCandidateItem,
    ): InboxItemRow {
        val richerSummary =
            pickRicherString(existing.summary, candidate.summary)?.take(MAX_SQLITE_SUMMARY_CHARS)
        val richerText =
            existing.contentText
                ?.takeIf { it.isNotBlank() && it != existing.summary }
                ?.take(MAX_SQLITE_TEXT_CHARS)
        val richerHtml =
            pickRicherString(existing.contentHtml, candidate.contentHtml)
                ?.take(MAX_SQLITE_HTML_CHARS)
        val mergedAudioUrl = existing.audioUrl?.takeIf { it.isNotBlank() } ?: candidate.audioUrl
        val mergedContentType =
            if (!mergedAudioUrl.isNullOrBlank()) "audio" else existing.contentType
        return existing.copy(
            feedId = existing.feedId ?: feed.id,
            feedUrl = existing.feedUrl ?: feed.url,
            feedTitle =
                existing.feedTitle.takeIf {
                    it.isNotBlank() &&
                        it != DEFAULT_SAVED_HOST &&
                        !it.equals(extractHostTitle(feed.url), ignoreCase = true) &&
                        it != "YouTube Channel"
                } ?: feed.title,
            title = existing.title.ifBlank { candidate.title },
            author = existing.author?.takeIf { it.isNotBlank() } ?: candidate.author,
            publishedAt = existing.publishedAt?.takeIf { it.isNotBlank() } ?: candidate.publishedAt,
            summary = richerSummary,
            contentText = richerText,
            contentHtml = richerHtml,
            thumbnailUrl =
                existing.thumbnailUrl?.takeIf { it.isNotBlank() } ?: candidate.thumbnailUrl,
            audioUrl = mergedAudioUrl,
            duration = existing.duration?.takeIf { it.isNotBlank() } ?: candidate.duration,
            contentType = mergedContentType,
        )
    }

    internal fun parseFeedXml(
        xml: String,
        feedUrl: String,
        feedType: String = inferFeedTypeWireValue(feedUrl),
    ): ParsedFeedBatch {
        val cleanXml = stripLeadingBom(xml).trim()
        require(cleanXml.isNotEmpty()) { "Feed XML is empty" }
        val xmlProlog = extractXmlPrologBeforeRoot(cleanXml)
        require(!EXTERNAL_ENTITY_REGEX.containsMatchIn(xmlProlog)) {
            "External XML entities are not allowed"
        }
        val doc = Jsoup.parse(cleanXml, feedUrl, Parser.xmlParser())
        doc.outputSettings().prettyPrint(false)
        val root = requireNotNull(doc.firstElementChild()) { "Missing XML root element" }
        val rootName = root.localOrTagName().lowercase(Locale.ROOT)
        require(rootName in ALLOWED_FEED_ROOT_NAMES) {
            "Unsupported feed root element: $rootName"
        }

        return if (rootName == "feed") {
            parseAtomFeed(root, feedUrl, feedType)
        } else {
            parseRssOrRdfFeed(root, feedUrl, feedType)
        }
    }

    private fun parseAtomFeed(
        root: Element,
        feedUrl: String,
        feedType: String,
    ): ParsedFeedBatch {
        val title = root.firstDirectAtomText(XML_TAG_TITLE)
        val description =
            root.firstDirectAtomText("subtitle") ?: root.firstDirectAtomText("tagline")
        val language =
            root.attr("xml:lang").takeIf { it.isNotBlank() }
                ?: root.firstDirectChildText(XML_TAG_LANGUAGE)
        val siteUrl = extractAtomLink(root, feedUrl, preferAlternate = true)

        val entries = root.directChildElements("entry")
        val items = entries.mapNotNull { entry ->
            parseAtomEntry(entry = entry, feedUrl = feedUrl, feedType = feedType)
        }
        return ParsedFeedBatch(
            title = title,
            description = description,
            language = language,
            siteUrl = siteUrl,
            items = items,
        )
    }

    private fun parseAtomEntry(
        entry: Element,
        feedUrl: String,
        feedType: String,
    ): ParsedFeedCandidateItem? {
        val mediaGroup = entry.firstDirectChildElement(XML_TAG_GROUP)
        val audioUrl = extractAudioEnclosureUrl(entry, feedUrl, mediaGroup)
        val rawUrl = extractAtomEntryUrl(entry, feedUrl) ?: audioUrl ?: return null
        val resolvedUrl = canonicalizeEntryUrl(rawUrl, feedType)
        val rawTitle = entry.firstDirectAtomText(XML_TAG_TITLE).orEmpty()
        val author = extractAtomAuthor(entry)
        val publishedIso = extractElementPublishedIso(entry)
        val rawContent =
            entry.firstDirectChildRichContent(XML_TAG_CONTENT)
                ?: entry.firstDirectChildRichContent("summary")
                ?: mediaGroup?.firstDirectChildRichContent(XML_TAG_DESCRIPTION)
        val sanitizedHtml = sanitizeCandidateHtml(rawContent, resolvedUrl)
        val summarySource =
            entry.firstDirectChildRichContent("summary")?.takeIf { it.isNotBlank() } ?: rawContent
        val summaryText = summarySource?.let(::plainTextExcerpt).orEmpty().ifBlank { rawTitle }
        val thumbnailUrl =
            extractItunesImageHref(entry, feedUrl)
                ?: extractAtomThumbnail(entry, mediaGroup, sanitizedHtml, feedUrl, resolvedUrl)
        val duration = extractPodcastDurationLabel(entry, mediaGroup)
        val effectiveAudioUrl = audioUrl ?: resolvedUrl.takeIf(::isDirectAudioUrl)
        val effectiveType =
            if (effectiveAudioUrl != null) {
                CONTENT_TYPE_AUDIO
            } else {
                resolveCandidateContentType(feedType, resolvedUrl)
            }

        return ParsedFeedCandidateItem(
            title = rawTitle.ifBlank { resolvedUrl },
            url = resolvedUrl,
            author = author,
            publishedAt = publishedIso,
            summary = summaryText,
            contentHtml = sanitizedHtml,
            thumbnailUrl = thumbnailUrl,
            contentType = effectiveType,
            audioUrl = effectiveAudioUrl,
            duration = duration,
        )
    }

    private fun parseRssOrRdfFeed(
        root: Element,
        feedUrl: String,
        feedType: String,
    ): ParsedFeedBatch {
        val channel = root.firstDirectChildElement("channel") ?: root
        val title = channel.firstDirectChildText(XML_TAG_TITLE)?.let(::decodeHtmlText)
        val description = channel.firstDirectChildText(XML_TAG_DESCRIPTION)?.let(::decodeHtmlText)
        val language =
            channel.firstDirectChildText(XML_TAG_LANGUAGE) ?: channel.firstDirectChildText("lang")
        val siteUrl =
            channel.firstDirectChildText(XML_TAG_LINK)?.trim()?.let {
                resolveRelativeUrl(feedUrl, it)
            }
        val channelArtworkUrl = extractChannelArtworkUrl(channel, siteUrl ?: feedUrl)
        val channelAuthor = extractRssAuthor(channel)

        val itemElements =
            if (channel === root) {
                root.directChildElements("item")
            } else {
                channel.directChildElements("item") + root.directChildElements("item")
            }
        val items = itemElements.mapNotNull { item ->
            parseRssItem(
                item = item,
                feedUrl = feedUrl,
                feedType = feedType,
                channelArtworkUrl = channelArtworkUrl,
                channelAuthor = channelAuthor,
            )
        }
        return ParsedFeedBatch(
            title = title,
            description = description,
            language = language,
            siteUrl = siteUrl,
            items = items,
        )
    }

    private fun parseRssItem(
        item: Element,
        feedUrl: String,
        feedType: String,
        channelArtworkUrl: String? = null,
        channelAuthor: String? = null,
    ): ParsedFeedCandidateItem? {
        val mediaGroup = item.firstDirectChildElement(XML_TAG_GROUP)
        val audioUrl = extractAudioEnclosureUrl(item, feedUrl, mediaGroup)
        val linkText = extractRssItemUrl(item, feedUrl, mediaGroup) ?: audioUrl ?: return null
        val httpItemUrl = resolveRelativeUrl(feedUrl, linkText) ?: audioUrl ?: return null
        val resolvedUrl = canonicalizeEntryUrl(httpItemUrl, feedType)
        val rawTitle =
            item.firstDirectChildText(XML_TAG_TITLE)?.let(::decodeHtmlText)?.trim().orEmpty()
        val author = extractRssAuthor(item) ?: channelAuthor?.takeIf { audioUrl != null }
        val publishedIso = extractElementPublishedIso(item)

        val primaryHtml = extractRssPrimaryHtml(item, mediaGroup)
        val sanitizedHtml = sanitizeCandidateHtml(primaryHtml, resolvedUrl)
        val summarySource = extractRssSummarySource(item, mediaGroup)
        val summaryText = summarySource?.let(::plainTextExcerpt).orEmpty().ifBlank { rawTitle }
        val duration = extractPodcastDurationLabel(item, mediaGroup)
        val effectiveAudioUrl = audioUrl ?: resolvedUrl.takeIf(::isDirectAudioUrl)
        val effectiveType =
            if (effectiveAudioUrl != null) {
                CONTENT_TYPE_AUDIO
            } else {
                resolveCandidateContentType(feedType, resolvedUrl)
            }
        val thumbnailUrl =
            extractRssThumbnailUrl(
                item = item,
                mediaGroup = mediaGroup,
                feedUrl = feedUrl,
                resolvedUrl = resolvedUrl,
                sanitizedHtml = sanitizedHtml,
                channelArtworkUrl = channelArtworkUrl,
                isAudio = effectiveType == CONTENT_TYPE_AUDIO,
            )

        return ParsedFeedCandidateItem(
            title = rawTitle.ifBlank { resolvedUrl },
            url = resolvedUrl,
            author = author,
            publishedAt = publishedIso,
            summary = summaryText,
            contentHtml = sanitizedHtml,
            thumbnailUrl = thumbnailUrl,
            contentType = effectiveType,
            audioUrl = effectiveAudioUrl,
            duration = duration,
        )
    }

    @Suppress("LongParameterList")
    private fun extractRssThumbnailUrl(
        item: Element,
        mediaGroup: Element?,
        feedUrl: String,
        resolvedUrl: String,
        sanitizedHtml: String?,
        channelArtworkUrl: String?,
        isAudio: Boolean,
    ): String? =
        extractItunesImageHref(item, feedUrl)
            ?: extractMediaThumbnail(item, feedUrl)
            ?: mediaGroup?.let { extractMediaThumbnail(it, feedUrl) }
            ?: extractEnclosureImage(item, feedUrl)
            ?: sanitizedHtml?.let { extractFirstImgUrl(it, resolvedUrl) }
            ?: channelArtworkUrl?.takeIf { isAudio }
}

internal fun extractRssPrimaryHtml(item: Element, mediaGroup: Element?): String? =
    item.firstDirectChildRichContent("encoded")?.takeIf { it.isNotBlank() }
        ?: item.firstDirectChildRichContent(XML_TAG_DESCRIPTION)?.takeIf { it.isNotBlank() }
        ?: mediaGroup?.firstDirectChildRichContent(XML_TAG_DESCRIPTION)

internal fun extractRssSummarySource(item: Element, mediaGroup: Element?): String? =
    item.firstDirectChildRichContent(XML_TAG_DESCRIPTION)?.takeIf { it.isNotBlank() }
        ?: mediaGroup?.firstDirectChildRichContent(XML_TAG_DESCRIPTION)?.takeIf { it.isNotBlank() }
        ?: item.firstDirectChildRichContent("encoded")

internal fun extractXmlPrologBeforeRoot(cleanXml: String): String {
    var match = FEED_ROOT_START_TAG_REGEX.find(cleanXml)
    while (match != null) {
        val candidateProlog = cleanXml.substring(0, match.range.first)
        val openBrackets = candidateProlog.count { it == '[' }
        val closeBrackets = candidateProlog.count { it == ']' }
        if (openBrackets <= closeBrackets) {
            return candidateProlog
        }
        match = match.next()
    }
    return cleanXml
}

internal fun extractValidYoutubeVideoId(entry: Element): String? =
    entry.firstDirectChildText("videoId")?.trim()?.takeIf { YOUTUBE_VIDEO_ID_REGEX.matches(it) }

internal fun extractAtomEntryUrl(entry: Element, feedUrl: String): String? =
    extractAtomLink(entry, feedUrl, preferAlternate = true)
        ?: entry.firstDirectChildText("id")?.trim()?.takeIf(::isHttpOrHttpsUrl)?.let {
            resolveRelativeUrl(feedUrl, it)
        }
        ?: extractValidYoutubeVideoId(entry)?.let { "https://www.youtube.com/watch?v=$it" }

internal fun extractRssItemUrl(
    item: Element,
    feedUrl: String,
    mediaGroup: Element? = item.firstDirectChildElement(XML_TAG_GROUP),
): String? =
    item.firstDirectChildText(XML_TAG_LINK)?.trim()?.takeIf { it.isNotBlank() }
        ?: extractAtomLink(item, feedUrl, preferAlternate = true)
        ?: item.firstDirectChildText("guid")?.trim()?.takeIf(::isHttpOrHttpsUrl)
        ?: findNonImageMediaContentUrl(item)
        ?: mediaGroup?.let(::findNonImageMediaContentUrl)

internal fun extractRssAuthor(item: Element): String? =
    (item.firstDirectChildText("creator") ?: item.firstDirectChildText(XML_TAG_AUTHOR))
        ?.let(::decodeHtmlText)
        ?.trim()
        ?.takeIf { it.isNotBlank() }

internal fun extractElementPublishedIso(el: Element): String? {
    val raw =
        el.firstDirectChildText("pubDate")
            ?: el.firstDirectChildText("published")
            ?: el.firstDirectChildText("updated")
            ?: el.firstDirectChildText("date")
    return normalizeFeedTimestamp(raw)
}

internal fun sanitizeCandidateHtml(rawHtml: String?, baseUrl: String): String? =
    rawHtml?.let { sanitizeFeedHtml(it, baseUrl = baseUrl) }?.takeIf { it.isNotBlank() }

internal fun extractAtomThumbnail(
    entry: Element,
    mediaGroup: Element?,
    sanitizedHtml: String?,
    feedUrl: String,
    resolvedUrl: String,
): String? {
    val videoId = extractValidYoutubeVideoId(entry)
    return extractMediaThumbnail(entry, feedUrl)
        ?: mediaGroup?.let { extractMediaThumbnail(it, feedUrl) }
        ?: videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
        ?: sanitizedHtml?.let { extractFirstImgUrl(it, resolvedUrl) }
}

internal fun pickRicherString(first: String?, second: String?): String? {
    val a = first?.trim()?.takeIf { it.isNotEmpty() }
    val b = second?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        a != null && b != null -> if (b.length > a.length) b else a
        a != null -> a
        else -> b
    }
}

internal fun resolveCandidateContentType(feedType: String, itemUrl: String): String =
    when {
        feedType.equals("arxiv", ignoreCase = true) || isArxivUrl(itemUrl) -> "paper"
        feedType.equals("youtube", ignoreCase = true) || isYoutubeUrl(itemUrl) -> "video"
        feedType.equals("podcast", ignoreCase = true) || isDirectAudioUrl(itemUrl) ->
            CONTENT_TYPE_AUDIO
        else -> "article"
    }

internal fun extractAudioEnclosureUrl(
    item: Element,
    baseUrl: String,
    mediaGroup: Element? = item.firstDirectChildElement(XML_TAG_GROUP),
): String? {
    val rawCandidate =
        findEnclosureTagAudioUrl(item)
            ?: findMediaContentAudioUrl(item)
            ?: mediaGroup?.let(::findMediaContentAudioUrl)
            ?: findAtomLinkEnclosureAudioUrl(item)
    return rawCandidate?.let { raw -> resolveRelativeUrl(baseUrl, raw) }
}

private fun findEnclosureTagAudioUrl(item: Element): String? =
    item
        .directChildElements(XML_TAG_ENCLOSURE)
        .mapNotNull { enc ->
            val url = enc.attr(ATTR_URL).ifBlank { enc.attr(ATTR_HREF) }.trim()
            val type = enc.attr(ATTR_TYPE).lowercase()
            url.takeIf {
                it.isNotBlank() && (type.startsWith(MIME_AUDIO_PREFIX) || isDirectAudioUrl(it))
            }
        }
        .firstOrNull()

private fun findMediaContentAudioUrl(parent: Element): String? =
    parent
        .directChildElements(XML_TAG_CONTENT)
        .mapNotNull { media ->
            val url = media.attr(ATTR_URL).trim()
            val medium = media.attr(ATTR_MEDIUM).lowercase()
            val type = media.attr(ATTR_TYPE).lowercase()
            url.takeIf {
                it.isNotBlank() &&
                    (medium == CONTENT_TYPE_AUDIO ||
                        type.startsWith(MIME_AUDIO_PREFIX) ||
                        isDirectAudioUrl(it))
            }
        }
        .firstOrNull()

private fun findNonImageMediaContentUrl(parent: Element): String? =
    parent
        .directChildElements(XML_TAG_CONTENT)
        .mapNotNull { media ->
            val url = media.attr(ATTR_URL).trim()
            val medium = media.attr(ATTR_MEDIUM).lowercase()
            val type = media.attr(ATTR_TYPE).lowercase()
            url.takeIf {
                it.isNotBlank() && medium != XML_TAG_IMAGE && !type.startsWith(MIME_IMAGE_PREFIX)
            }
        }
        .firstOrNull()

private fun findAtomLinkEnclosureAudioUrl(item: Element): String? =
    item
        .directChildElements(XML_TAG_LINK)
        .mapNotNull { link ->
            val rel = link.attr("rel").trim().lowercase()
            val href = link.attr(ATTR_HREF).trim()
            val type = link.attr(ATTR_TYPE).lowercase()
            href.takeIf {
                rel == XML_TAG_ENCLOSURE &&
                    it.isNotBlank() &&
                    (type.startsWith(MIME_AUDIO_PREFIX) || isDirectAudioUrl(it))
            }
        }
        .firstOrNull()

internal fun extractPodcastDurationLabel(
    item: Element,
    mediaGroup: Element? = item.firstDirectChildElement(XML_TAG_GROUP),
): String? {
    val raw =
        item.firstDirectChildText("duration")
            ?: findMediaContentDuration(item)
            ?: mediaGroup?.let(::findMediaContentDuration)
            ?: return null
    val seconds = parseDurationToSecondsLong(raw) ?: return raw
    return formatDurationSecondsLabel(seconds) ?: raw
}

private fun findMediaContentDuration(parent: Element): String? =
    parent
        .directChildElements(XML_TAG_CONTENT)
        .map { it.attr("duration").trim() }
        .firstOrNull { it.isNotBlank() }

internal fun extractItunesImageHref(parent: Element, baseUrl: String): String? {
    val imgEl =
        parent.directChildElements(XML_TAG_IMAGE).firstOrNull {
            it.attr(ATTR_HREF).isNotBlank()
        } ?: return null
    val href = imgEl.attr(ATTR_HREF).trim()
    return resolveRelativeUrl(baseUrl, href)
}

internal fun extractChannelArtworkUrl(channel: Element, baseUrl: String): String? {
    val itunesImage = extractItunesImageHref(channel, baseUrl)
    val rssImage =
        channel
            .directChildElements(XML_TAG_IMAGE)
            .mapNotNull { it.firstDirectChildText(ATTR_URL) }
            .firstOrNull { it.isNotBlank() }
            ?.let { resolveRelativeUrl(baseUrl, it) }
    return itunesImage ?: rssImage
}

internal fun canonicalizeEntryUrl(rawUrl: String, feedType: String): String {
    val trimmed = rawUrl.trim()
    val shouldCheckArxiv =
        feedType.equals("arxiv", ignoreCase = true) ||
            trimmed.contains("arxiv.org/abs/", ignoreCase = true)
    if (shouldCheckArxiv && trimmed.contains("/abs/", ignoreCase = true)) {
        val arxivId = extractArxivIdentifier(trimmed)
        if (arxivId != null) {
            val versionless = stripArxivVersionSuffix(arxivId)
            if (isSafeArxivId(versionless)) {
                return "https://arxiv.org/abs/$versionless"
            }
        }
    }
    return trimmed
}

private fun stripArxivVersionSuffix(arxivId: String): String {
    val clean = arxivId.trim()
    val vIdx = clean.lastIndexOf('v')
    val hasVersionDigits =
        vIdx > 0 && vIdx < clean.lastIndex && clean.substring(vIdx + 1).all(Char::isDigit)
    return if (hasVersionDigits) clean.substring(0, vIdx) else clean
}

private fun isSafeArxivId(id: String): Boolean =
    id.isNotEmpty() &&
        ".." !in id &&
        id.count { it == '/' } <= 1 &&
        id.all { it.isLetterOrDigit() || it in "._-/" }

internal fun extractAtomLink(
    parent: Element,
    baseUrl: String,
    preferAlternate: Boolean,
): String? {
    val links = parent.directChildElements(XML_TAG_LINK)
    if (links.isEmpty()) return null
    val chosen =
        if (preferAlternate) {
            links.firstOrNull {
                val rel = it.attr("rel").trim().lowercase()
                (rel.isEmpty() || rel == "alternate") && it.attr(ATTR_HREF).isNotBlank()
            }
                ?: links.firstOrNull {
                    val rel = it.attr("rel").trim().lowercase()
                    rel != "enclosure" && it.attr(ATTR_HREF).isNotBlank()
                }
        } else {
            links.firstOrNull { it.attr(ATTR_HREF).isNotBlank() }
        }
    val href = chosen?.attr(ATTR_HREF)?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return resolveRelativeUrl(baseUrl, href)
}

internal fun extractAtomAuthor(entry: Element): String? {
    val authorEl = entry.firstDirectChildElement(XML_TAG_AUTHOR)
    if (authorEl != null) {
        val name = authorEl.firstDirectChildText("name")?.let(::decodeHtmlText)?.trim()
        if (!name.isNullOrBlank()) return name
    }
    return entry.firstDirectChildText(XML_TAG_AUTHOR)?.let(::decodeHtmlText)?.trim()?.takeIf {
        it.isNotBlank()
    }
}

internal fun extractMediaThumbnail(parent: Element, baseUrl: String): String? {
    val fromThumbTag =
        parent
            .directChildElements("thumbnail")
            .map { it.attr(ATTR_URL).trim() }
            .firstOrNull { it.isNotBlank() }
            ?.let { resolveRelativeUrl(baseUrl, it) }
    val fromMediaContent =
        parent
            .directChildElements(XML_TAG_CONTENT)
            .firstOrNull { media ->
                val medium = media.attr(ATTR_MEDIUM).lowercase()
                val type = media.attr(ATTR_TYPE).lowercase()
                val url = media.attr(ATTR_URL).trim()
                url.isNotBlank() && (medium == XML_TAG_IMAGE || type.startsWith(MIME_IMAGE_PREFIX))
            }
            ?.let { resolveRelativeUrl(baseUrl, it.attr(ATTR_URL).trim()) }
    return fromThumbTag ?: fromMediaContent
}

internal fun extractEnclosureImage(item: Element, baseUrl: String): String? =
    item
        .directChildElements(XML_TAG_ENCLOSURE)
        .firstOrNull { enclosure ->
            val type = enclosure.attr(ATTR_TYPE).lowercase()
            val url = enclosure.attr(ATTR_URL).trim()
            url.isNotBlank() && type.startsWith(MIME_IMAGE_PREFIX)
        }
        ?.let { resolveRelativeUrl(baseUrl, it.attr(ATTR_URL).trim()) }

internal fun sanitizeFeedHtml(rawHtml: String, baseUrl: String): String =
    Jsoup.clean(rawHtml, baseUrl.trim(), Safelist.relaxed()).trim()

internal fun extractFirstImgUrl(html: String, baseUrl: String): String? =
    IMG_TAG_REGEX.findAll(html)
        .mapNotNull { match ->
            val rawSrc =
                IMG_SRC_VALUE_REGEX.find(match.value)?.groupValues?.getOrNull(2)?.trim()?.takeIf {
                    it.isNotBlank()
                }
            rawSrc?.let { resolveRelativeUrl(baseUrl, it) }
        }
        .firstOrNull { resolved ->
            val lower = resolved.lowercase()
            !lower.endsWith(".svg") &&
                !lower.contains("1x1") &&
                !lower.contains("pixel") &&
                !lower.contains("avatar") &&
                !lower.contains("emoji")
        }

internal fun plainTextExcerpt(rawHtml: String): String {
    val stripped =
        decodeHtmlText(
                rawHtml
                    .replace(SCRIPT_BLOCK_REGEX, " ")
                    .replace(STYLE_BLOCK_REGEX, " ")
                    .replace(HTML_TAG_REGEX, " ")
            )
            .replace(WHITESPACE_RUN_REGEX, " ")
            .trim()
    return if (stripped.length <= MAX_SUMMARY_EXCERPT_CHARS) {
        stripped
    } else {
        stripped.take(MAX_SUMMARY_EXCERPT_CHARS).trimEnd() + "…"
    }
}

internal fun decodeHtmlText(raw: String): String =
    decodeHtmlEntities(raw).replace('\u00A0', ' ').replace("\u0000", "")

internal fun normalizeFeedTimestamp(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parsed =
        parseIsoInstant(trimmed)
            ?: runCatching {
                ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            }
                .getOrNull()
    return parsed?.truncatedTo(ChronoUnit.SECONDS)?.toString()
}

internal fun resolveRelativeUrl(baseUrl: String, candidate: String): String? = runCatching {
    val trimmed = candidate.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("data:", ignoreCase = true)) return null
    val resolved =
        if (isHttpOrHttpsUrl(trimmed)) {
            trimmed
        } else if (trimmed.startsWith("//")) {
            val scheme = URI(baseUrl.trim()).scheme ?: "https"
            "$scheme:$trimmed"
        } else {
            URI(baseUrl.trim()).resolve(trimmed).toString()
        }
    val parsed = URI(resolved)
    val validScheme =
        parsed.scheme.equals("http", ignoreCase = true) ||
            parsed.scheme.equals("https", ignoreCase = true)
    resolved.takeIf { validScheme && !parsed.host.isNullOrBlank() }
}
    .getOrNull()

internal fun stripLeadingBom(text: String): String = text.removePrefix("\uFEFF")

internal fun Element.localOrTagName(): String = tagName().substringAfter(':')

internal fun Element.directChildElements(localTarget: String): List<Element> =
    children().filter { it.localOrTagName().equals(localTarget, ignoreCase = true) }

internal fun Element.firstDirectChildElement(localTarget: String): Element? =
    children().firstOrNull { it.localOrTagName().equals(localTarget, ignoreCase = true) }

internal fun Element.firstDirectChildText(localTarget: String): String? =
    firstDirectChildElement(localTarget)?.wholeText()?.trim()?.takeIf { it.isNotEmpty() }

internal fun Element.firstDirectAtomText(localTarget: String): String? {
    val el = firstDirectChildElement(localTarget) ?: return null
    val decoded =
        when (el.attr(ATTR_TYPE).trim().lowercase(Locale.ROOT)) {
            "html" -> decodeHtmlText(el.wholeText())
            ATTR_VALUE_XHTML -> plainTextExcerpt(el.html())
            else -> el.wholeText().replace('\u00A0', ' ').replace("\u0000", "")
        }
    return decoded.trim().takeIf { it.isNotEmpty() }
}

internal fun Element.firstDirectChildRichContent(localTarget: String): String? {
    val el = firstDirectChildElement(localTarget) ?: return null
    val isXhtml = el.attr(ATTR_TYPE).equals(ATTR_VALUE_XHTML, ignoreCase = true)
    val raw =
        if (isXhtml || el.childrenSize() > 0) {
            el.html()
        } else {
            el.wholeText()
        }
    return raw.trim().takeIf { it.isNotEmpty() }
}

private val ALLOWED_FEED_ROOT_NAMES = setOf("feed", "rss", "rdf", "channel")
private val FEED_ROOT_START_TAG_REGEX =
    Regex("""<(?:[A-Za-z0-9_.-]+:)?(?:rss|feed|rdf|channel)(?=[\s/>])""", RegexOption.IGNORE_CASE)
private val EXTERNAL_ENTITY_REGEX =
    Regex("""<!ENTITY\b[^>]*\b(?:SYSTEM|PUBLIC)\b""", RegexOption.IGNORE_CASE)
private val YOUTUBE_VIDEO_ID_REGEX = Regex("""^[A-Za-z0-9_-]{6,32}$""")
private const val MAX_SUMMARY_EXCERPT_CHARS = 500
private const val MAX_FEED_ERROR_CHARS = 120
private const val XML_TAG_TITLE = "title"
private const val XML_TAG_DESCRIPTION = "description"
private const val XML_TAG_CONTENT = "content"
private const val XML_TAG_GROUP = "group"
private const val XML_TAG_LANGUAGE = "language"
private const val XML_TAG_LINK = "link"
private const val XML_TAG_AUTHOR = "author"
private const val XML_TAG_ENCLOSURE = "enclosure"
private const val XML_TAG_IMAGE = "image"
private const val CONTENT_TYPE_AUDIO = "audio"
private const val MIME_AUDIO_PREFIX = "audio/"
private const val MIME_IMAGE_PREFIX = "image/"
private const val ATTR_URL = "url"
private const val ATTR_HREF = "href"
private const val ATTR_TYPE = "type"
private const val ATTR_MEDIUM = "medium"
private const val ATTR_VALUE_XHTML = "xhtml"
private val SCRIPT_BLOCK_REGEX =
    Regex("""<script\b[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE)
private val STYLE_BLOCK_REGEX = Regex("""<style\b[^>]*>[\s\S]*?</style>""", RegexOption.IGNORE_CASE)
private val IMG_TAG_REGEX = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
private val IMG_SRC_VALUE_REGEX = Regex("""\bsrc\s*=\s*(["'])([^"']+)\1""", RegexOption.IGNORE_CASE)
private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
private val WHITESPACE_RUN_REGEX = Regex("""\s+""")

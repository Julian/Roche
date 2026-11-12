@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data

import com.grayvines.roche.data.local.FeedEntity
import com.grayvines.roche.data.local.ItemEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.model.ItemStatus
import java.io.StringReader
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

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
    if (isYoutubeChannelId(trimmed)) {
        return "https://www.youtube.com/feeds/videos.xml?channel_id=$trimmed"
    }
    val channelMatch = YOUTUBE_CHANNEL_EXTRACT_REGEX.matchEntire(trimmed)
    val channelId = channelMatch?.groupValues?.getOrNull(1)
    if (channelId != null && isYoutubeChannelId(channelId)) {
        return "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
    }
    return trimmed
}

internal object LocalFeedPoller {

    suspend fun pollFeed(
        dao: RocheDao,
        apiClient: RocheApiClient,
        feed: FeedEntity,
        localPeerId: String,
        nowInstant: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS),
    ): Result<Int> {
        val nowIso = nowInstant.toString()
        val fetchUrl = canonicalizeFeedUrl(feed.url)
        val xmlResult = apiClient.fetchExternalFeedXml(fetchUrl)
        val xml = xmlResult.getOrElse { error ->
            dao.upsertFeed(
                feed.copy(
                    lastPolled = nowIso,
                    lastError = error.message ?: "Feed fetch failed",
                    lastErrorBy = localPeerId,
                )
            )
            return Result.failure(error)
        }
        val batch = runSuspendCatching {
            parseFeedXml(xml = xml, feedUrl = fetchUrl, feedType = feed.feedType)
        }
            .getOrElse { error ->
                dao.upsertFeed(
                    feed.copy(
                        lastPolled = nowIso,
                        lastError = error.message ?: "Feed XML parse failed",
                        lastErrorBy = localPeerId,
                    )
                )
                return Result.failure(error)
            }

        val updatedFeed = buildUpdatedPolledFeed(feed, batch, nowIso, localPeerId)
        dao.upsertFeed(updatedFeed)

        val insertedOrUpdatedCount =
            upsertPolledItems(
                dao = dao,
                feed = updatedFeed,
                candidates = batch.items,
                nowIso = nowIso,
            )
        return Result.success(maxOf(insertedOrUpdatedCount, batch.items.size))
    }

    private fun buildUpdatedPolledFeed(
        feed: FeedEntity,
        batch: ParsedFeedBatch,
        nowIso: String,
        localPeerId: String,
    ): FeedEntity {
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
        )
    }

    private suspend fun upsertPolledItems(
        dao: RocheDao,
        feed: FeedEntity,
        candidates: List<ParsedFeedCandidateItem>,
        nowIso: String,
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
                    dao.upsertItem(merged)
                }
            } else {
                val created = buildNewPolledItemEntity(detId, feed, candidate, cleanUrl, nowIso)
                dao.upsertItem(created)
                byNaturalUrl[canonicalUrlKey] = created
                byId[detId] = created
                newCount++
            }
        }
        return newCount
    }

    private fun buildNewPolledItemEntity(
        detId: String,
        feed: FeedEntity,
        candidate: ParsedFeedCandidateItem,
        cleanUrl: String,
        nowIso: String,
    ): ItemEntity =
        ItemEntity(
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
        existing: ItemEntity,
        feed: FeedEntity,
        candidate: ParsedFeedCandidateItem,
    ): ItemEntity {
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
                existing.feedTitle.takeIf { it.isNotBlank() && it != DEFAULT_SAVED_HOST }
                    ?: feed.title,
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
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                runCatching {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
                runCatching {
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                }
                runCatching {
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                }
            }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(cleanXml)))
        val root = doc.documentElement ?: error("Missing XML root element")
        val rootName = root.localOrTagName().lowercase()

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
        val title = root.firstDirectChildText(XML_TAG_TITLE)?.let(::decodeHtmlText)
        val description =
            (root.firstDirectChildText("subtitle") ?: root.firstDirectChildText("tagline"))?.let(
                ::decodeHtmlText
            )
        val language =
            root.getAttribute("xml:lang").takeIf { it.isNotBlank() }
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
        val audioUrl = extractAudioEnclosureUrl(entry, feedUrl)
        val rawUrl = extractAtomEntryUrl(entry, feedUrl) ?: audioUrl ?: return null
        val resolvedUrl = canonicalizeEntryUrl(rawUrl, feedType)
        val rawTitle =
            entry.firstDirectChildText(XML_TAG_TITLE)?.let(::decodeHtmlText)?.trim().orEmpty()
        val author = extractAtomAuthor(entry)
        val publishedIso = extractElementPublishedIso(entry)
        val mediaGroup = entry.firstDirectChildElement("group")
        val rawContent =
            entry.firstDirectChildText(XML_TAG_CONTENT)
                ?: entry.firstDirectChildText("summary")
                ?: mediaGroup?.firstDirectChildText(XML_TAG_DESCRIPTION)
        val sanitizedHtml = sanitizeCandidateHtml(rawContent, resolvedUrl)
        val summaryText = rawContent?.let(::plainTextExcerpt).orEmpty().ifBlank { rawTitle }
        val thumbnailUrl =
            extractItunesImageHref(entry, resolvedUrl)
                ?: extractAtomThumbnail(entry, mediaGroup, sanitizedHtml, resolvedUrl)
        val duration = extractPodcastDurationLabel(entry)
        val effectiveType =
            if (audioUrl != null) {
                CONTENT_TYPE_AUDIO
            } else {
                resolveCandidateContentType(feedType, resolvedUrl)
            }
        val effectiveAudioUrl =
            audioUrl ?: resolvedUrl.takeIf { effectiveType == CONTENT_TYPE_AUDIO }

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
            (channel.directChildElements("item") + root.directChildElements("item")).distinct()
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
        val audioUrl = extractAudioEnclosureUrl(item, feedUrl)
        val linkText = extractRssItemUrl(item, feedUrl) ?: audioUrl ?: return null
        val resolvedUrl =
            canonicalizeEntryUrl(resolveRelativeUrl(feedUrl, linkText) ?: linkText, feedType)
        val rawTitle =
            item.firstDirectChildText(XML_TAG_TITLE)?.let(::decodeHtmlText)?.trim().orEmpty()
        val author = extractRssAuthor(item) ?: channelAuthor?.takeIf { audioUrl != null }
        val publishedIso = extractElementPublishedIso(item)

        val encodedContent = item.firstDirectChildText("encoded")
        val description = item.firstDirectChildText(XML_TAG_DESCRIPTION)
        val primaryHtml = encodedContent?.takeIf { it.isNotBlank() } ?: description
        val sanitizedHtml = sanitizeCandidateHtml(primaryHtml, resolvedUrl)
        val summarySource = description?.takeIf { it.isNotBlank() } ?: encodedContent
        val summaryText = summarySource?.let(::plainTextExcerpt).orEmpty().ifBlank { rawTitle }
        val duration = extractPodcastDurationLabel(item)
        val effectiveType =
            if (audioUrl != null) {
                CONTENT_TYPE_AUDIO
            } else {
                resolveCandidateContentType(feedType, resolvedUrl)
            }
        val effectiveAudioUrl =
            audioUrl ?: resolvedUrl.takeIf { effectiveType == CONTENT_TYPE_AUDIO }
        val thumbnailUrl =
            extractItunesImageHref(item, resolvedUrl)
                ?: extractMediaThumbnail(item, resolvedUrl)
                ?: extractEnclosureImage(item, resolvedUrl)
                ?: sanitizedHtml?.let { extractFirstImgUrl(it, resolvedUrl) }
                ?: channelArtworkUrl?.takeIf { effectiveType == CONTENT_TYPE_AUDIO }

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
}

internal fun extractAtomEntryUrl(entry: Element, feedUrl: String): String? =
    extractAtomLink(entry, feedUrl, preferAlternate = true)
        ?: entry.firstDirectChildText("id")?.trim()?.takeIf(::isHttpOrHttpsUrl)

internal fun extractRssItemUrl(item: Element, feedUrl: String): String? =
    item.firstDirectChildText(XML_TAG_LINK)?.trim()?.takeIf { it.isNotBlank() }
        ?: extractAtomLink(item, feedUrl, preferAlternate = true)
        ?: item.firstDirectChildText("guid")?.trim()?.takeIf(::isHttpOrHttpsUrl)

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
    resolvedUrl: String,
): String? {
    val videoId = entry.firstDirectChildText("videoId")?.trim()?.takeIf { it.isNotBlank() }
    return extractMediaThumbnail(entry, resolvedUrl)
        ?: mediaGroup?.let { extractMediaThumbnail(it, resolvedUrl) }
        ?: videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
        ?: sanitizedHtml?.let { extractFirstImgUrl(it, resolvedUrl) }
}

private fun isHttpOrHttpsUrl(value: String): Boolean =
    value.startsWith(HTTP_PREFIX, ignoreCase = true) ||
        value.startsWith(HTTPS_PREFIX, ignoreCase = true)

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
        feedType.equals("arxiv", ignoreCase = true) ||
            itemUrl.contains("arxiv.org", ignoreCase = true) -> "paper"
        feedType.equals("youtube", ignoreCase = true) ||
            itemUrl.contains("youtube.com", ignoreCase = true) ||
            itemUrl.contains("youtu.be", ignoreCase = true) -> "video"
        feedType.equals("podcast", ignoreCase = true) || isDirectAudioUrl(itemUrl) ->
            CONTENT_TYPE_AUDIO
        else -> "article"
    }

internal fun extractAudioEnclosureUrl(item: Element, baseUrl: String): String? {
    val rawCandidate =
        findEnclosureTagAudioUrl(item)
            ?: findMediaContentAudioUrl(item)
            ?: findAtomLinkEnclosureAudioUrl(item)
    return rawCandidate?.let { raw -> resolveRelativeUrl(baseUrl, raw) ?: raw }
}

private fun findEnclosureTagAudioUrl(item: Element): String? =
    item
        .directChildElements(XML_TAG_ENCLOSURE)
        .mapNotNull { enc ->
            val url = enc.getAttribute(ATTR_URL).ifBlank { enc.getAttribute("href") }.trim()
            val type = enc.getAttribute("type").lowercase()
            url.takeIf {
                it.isNotBlank() && (type.startsWith(MIME_AUDIO_PREFIX) || isDirectAudioUrl(it))
            }
        }
        .firstOrNull()

private fun findMediaContentAudioUrl(item: Element): String? =
    item
        .directChildElements(XML_TAG_CONTENT)
        .mapNotNull { media ->
            val url = media.getAttribute(ATTR_URL).trim()
            val medium = media.getAttribute("medium").lowercase()
            val type = media.getAttribute("type").lowercase()
            url.takeIf {
                it.isNotBlank() &&
                    (medium == CONTENT_TYPE_AUDIO ||
                        type.startsWith(MIME_AUDIO_PREFIX) ||
                        isDirectAudioUrl(it))
            }
        }
        .firstOrNull()

private fun findAtomLinkEnclosureAudioUrl(item: Element): String? =
    item
        .directChildElements(XML_TAG_LINK)
        .mapNotNull { link ->
            val rel = link.getAttribute("rel").trim().lowercase()
            val href = link.getAttribute("href").trim()
            val type = link.getAttribute("type").lowercase()
            href.takeIf {
                rel == XML_TAG_ENCLOSURE &&
                    it.isNotBlank() &&
                    (type.startsWith(MIME_AUDIO_PREFIX) || isDirectAudioUrl(it))
            }
        }
        .firstOrNull()

internal fun extractPodcastDurationLabel(item: Element): String? {
    val raw =
        item.firstDirectChildText("duration")
            ?: item
                .directChildElements(XML_TAG_CONTENT)
                .map { it.getAttribute("duration").trim() }
                .firstOrNull { it.isNotBlank() }
            ?: return null
    val seconds = parseDurationToSecondsLong(raw) ?: return raw
    val minutes = seconds / SECONDS_PER_MIN
    val remSeconds = seconds % SECONDS_PER_MIN
    return "%d:%02d".format(java.util.Locale.US, minutes, remSeconds)
}

internal fun extractItunesImageHref(parent: Element, baseUrl: String): String? {
    val imgEl =
        parent.directChildElements(XML_TAG_IMAGE).firstOrNull {
            it.getAttribute("href").isNotBlank()
        } ?: return null
    val href = imgEl.getAttribute("href").trim()
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
    if (feedType.equals("arxiv", ignoreCase = true) || trimmed.contains("arxiv.org/abs/")) {
        val withoutVersion = trimmed.replace(Regex("""(arxiv\.org/abs/\d{4}\.\d{4,5})v\d+"""), "$1")
        return withoutVersion.replace("http://arxiv.org/", "https://arxiv.org/")
    }
    return trimmed
}

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
                val rel = it.getAttribute("rel").trim().lowercase()
                (rel.isEmpty() || rel == "alternate") && it.getAttribute("href").isNotBlank()
            }
                ?: links.firstOrNull {
                    val rel = it.getAttribute("rel").trim().lowercase()
                    rel != "enclosure" && it.getAttribute("href").isNotBlank()
                }
        } else {
            links.firstOrNull { it.getAttribute("href").isNotBlank() }
        }
    val href = chosen?.getAttribute("href")?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return resolveRelativeUrl(baseUrl, href) ?: href
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
            .map { it.getAttribute(ATTR_URL).trim() }
            .firstOrNull { it.isNotBlank() }
            ?.let { resolveRelativeUrl(baseUrl, it) }
    val fromMediaContent =
        parent
            .directChildElements(XML_TAG_CONTENT)
            .firstOrNull { media ->
                val medium = media.getAttribute("medium").lowercase()
                val type = media.getAttribute("type").lowercase()
                val url = media.getAttribute(ATTR_URL).trim()
                url.isNotBlank() && (medium == XML_TAG_IMAGE || type.startsWith("image/"))
            }
            ?.let { resolveRelativeUrl(baseUrl, it.getAttribute(ATTR_URL).trim()) }
    return fromThumbTag ?: fromMediaContent
}

internal fun extractEnclosureImage(item: Element, baseUrl: String): String? =
    item
        .directChildElements(XML_TAG_ENCLOSURE)
        .firstOrNull { enclosure ->
            val type = enclosure.getAttribute("type").lowercase()
            val url = enclosure.getAttribute(ATTR_URL).trim()
            url.isNotBlank() && type.startsWith("image/")
        }
        ?.let { resolveRelativeUrl(baseUrl, it.getAttribute(ATTR_URL).trim()) }

internal fun sanitizeFeedHtml(rawHtml: String, baseUrl: String): String {
    val withoutDangerousBlocks =
        rawHtml
            .replace(SCRIPT_BLOCK_REGEX, "")
            .replace(STYLE_BLOCK_REGEX, "")
            .replace(IFRAME_BLOCK_REGEX, "")
            .replace(ON_EVENT_ATTR_REGEX, "")
            .replace(JAVASCRIPT_HREF_REGEX, """$1="#"""")
    return IMG_SRC_ATTR_REGEX.replace(withoutDangerousBlocks) { match ->
            val quote = match.groupValues[1]
            val rawSrc = match.groupValues[2].trim()
            val resolved = resolveRelativeUrl(baseUrl, rawSrc) ?: rawSrc
            """src=$quote$resolved$quote"""
        }
        .trim()
}

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
    raw.replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace(NUMERIC_ENTITY_DEC_REGEX) { m ->
            decodeCodepointOrDefault(m.groupValues[1].toIntOrNull(), m.value)
        }
        .replace(NUMERIC_ENTITY_HEX_REGEX) { m ->
            decodeCodepointOrDefault(m.groupValues[1].toIntOrNull(HEX_RADIX), m.value)
        }

private fun decodeCodepointOrDefault(codepoint: Int?, fallback: String): String =
    if (codepoint != null && codepoint in MIN_PRINTABLE_CODEPOINT..MAX_UNICODE_CODEPOINT) {
        String(Character.toChars(codepoint))
    } else {
        fallback
    }

internal fun normalizeFeedTimestamp(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parsed =
        runCatching { Instant.parse(trimmed) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(trimmed).toInstant() }.getOrNull()
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
            val scheme = URI.create(baseUrl).scheme ?: "https"
            "$scheme:$trimmed"
        } else {
            URI.create(baseUrl.trim()).resolve(trimmed).toString()
        }
    resolved.takeIf(::isHttpOrHttpsUrl)
}
    .getOrNull()

internal fun stripLeadingBom(text: String): String = text.removePrefix("\uFEFF")

internal fun Element.localOrTagName(): String =
    localName?.takeIf { it.isNotBlank() } ?: tagName.substringAfter(':')

internal fun Element.directChildElements(localTarget: String): List<Element> {
    val list = mutableListOf<Element>()
    val children = childNodes
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node.nodeType == Node.ELEMENT_NODE) {
            val el = node as Element
            if (el.localOrTagName().equals(localTarget, ignoreCase = true)) {
                list.add(el)
            }
        }
    }
    return list
}

internal fun Element.firstDirectChildElement(localTarget: String): Element? =
    directChildElements(localTarget).firstOrNull()

internal fun Element.firstDirectChildText(localTarget: String): String? =
    firstDirectChildElement(localTarget)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

private const val MAX_SUMMARY_EXCERPT_CHARS = 500
private const val MIN_PRINTABLE_CODEPOINT = 32
private const val MAX_UNICODE_CODEPOINT = 0x10FFFF
private const val HEX_RADIX = 16
private const val XML_TAG_TITLE = "title"
private const val XML_TAG_DESCRIPTION = "description"
private const val XML_TAG_CONTENT = "content"
private const val XML_TAG_LANGUAGE = "language"
private const val XML_TAG_LINK = "link"
private const val XML_TAG_AUTHOR = "author"
private const val XML_TAG_ENCLOSURE = "enclosure"
private const val XML_TAG_IMAGE = "image"
private const val CONTENT_TYPE_AUDIO = "audio"
private const val MIME_AUDIO_PREFIX = "audio/"
private const val SECONDS_PER_MIN = 60L
private const val ATTR_URL = "url"
private const val HTTP_PREFIX = "http://"
private const val HTTPS_PREFIX = "https://"
private val YOUTUBE_CHANNEL_EXTRACT_REGEX =
    Regex(
        """^https?://(?:www\.|m\.)?youtube\.com/channel/([A-Za-z0-9_-]{24})(?:[/?#].*)?$""",
        RegexOption.IGNORE_CASE,
    )
private val SCRIPT_BLOCK_REGEX =
    Regex("""<script\b[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE)
private val STYLE_BLOCK_REGEX = Regex("""<style\b[^>]*>[\s\S]*?</style>""", RegexOption.IGNORE_CASE)
private val IFRAME_BLOCK_REGEX =
    Regex("""<iframe\b[^>]*>[\s\S]*?</iframe>""", RegexOption.IGNORE_CASE)
private val ON_EVENT_ATTR_REGEX =
    Regex("""\s+on[a-z]+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)""", RegexOption.IGNORE_CASE)
private val JAVASCRIPT_HREF_REGEX =
    Regex("""(href|src)\s*=\s*["']\s*javascript:[^"']*["']""", RegexOption.IGNORE_CASE)
private val IMG_SRC_ATTR_REGEX = Regex("""\bsrc\s*=\s*(["'])([^"']+)\1""", RegexOption.IGNORE_CASE)
private val IMG_TAG_REGEX = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
private val IMG_SRC_VALUE_REGEX = Regex("""\bsrc\s*=\s*(["'])([^"']+)\1""", RegexOption.IGNORE_CASE)
private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
private val WHITESPACE_RUN_REGEX = Regex("""\s+""")
private val NUMERIC_ENTITY_DEC_REGEX = Regex("""&#(\d{1,7});""")
private val NUMERIC_ENTITY_HEX_REGEX = Regex("""&#x([0-9a-fA-F]{1,6});""")

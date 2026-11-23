package com.grayvines.roche.data.boards

import com.grayvines.roche.core.canonicalArxivAbstractUrl
import com.grayvines.roche.core.coreJson
import com.grayvines.roche.core.decodeHtmlEntities
import com.grayvines.roche.core.extractHackerNewsItemId
import com.grayvines.roche.core.formatUtcMillisIso
import com.grayvines.roche.core.isHttpOrHttpsUrl
import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.core.stripHtmlTags
import com.grayvines.roche.data.local.MAX_SQLITE_HTML_CHARS
import com.grayvines.roche.data.remote.PublicUrlValidator
import com.grayvines.roche.data.remote.PublicWebFetcher
import com.grayvines.roche.model.extractCleanDomain
import com.grayvines.roche.reader.sanitizeRocheHtml
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val HN_FETCH_CONCURRENCY = 16
private const val HN_OVERFETCH_MIN_EXTRA = 40
private const val MAX_BOARD_SUMMARY_CHARS = 512
private val SKIPPED_HN_ITEM_TYPES = setOf("comment", "pollopt")

class HackerNewsBoardAdapter(private val publicWebFetcher: PublicWebFetcher) : BoardAdapter {
    override val adapterType: BoardAdapterType = BoardAdapterType.HACKER_NEWS

    override fun orderMode(sourceKey: String, sourceConfigJson: String): BoardOrderMode =
        resolveSection(sourceKey, sourceConfigJson).orderMode

    override fun defaultTitle(sourceKey: String, sourceConfigJson: String): String =
        resolveSection(sourceKey, sourceConfigJson).defaultTitle

    override suspend fun fetchSnapshot(
        sourceKey: String,
        sourceConfigJson: String,
        limit: Int,
    ): Result<BoardPollBatch> =
        fetchSlice(
            section = resolveSection(sourceKey, sourceConfigJson),
            offset = 0,
            limit = limit.coerceAtLeast(1),
        )

    override suspend fun fetchPage(
        sourceKey: String,
        sourceConfigJson: String,
        offset: Int,
        limit: Int,
    ): Result<BoardPollBatch> =
        fetchSlice(
            section = resolveSection(sourceKey, sourceConfigJson),
            offset = offset.coerceAtLeast(0),
            limit = limit.coerceAtLeast(1),
        )

    override suspend fun fetchSingleItem(
        sourceKey: String,
        sourceConfigJson: String,
        externalId: String,
    ): Result<BoardPollCandidate?> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val digits =
                    extractHackerNewsItemId(externalId)
                        ?: throw IllegalArgumentException(
                            "Invalid Hacker News item id: $externalId"
                        )
                val raw = publicWebFetcher.fetchJson(buildHackerNewsItemApiUrl(digits)).getOrThrow()
                parseStoryItem(rawJson = raw, fallbackId = digits.toLongOrNull(), rank = 1)
            }
        }

    private suspend fun fetchSlice(
        section: HackerNewsSection,
        offset: Int,
        limit: Int,
    ): Result<BoardPollBatch> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val rawIds = publicWebFetcher.fetchJson(section.apiStoriesUrl).getOrThrow()
                val allIds = parseStoryIds(rawIds)
                val safeOffset = offset.coerceAtLeast(0).coerceAtMost(allIds.size)
                val maxScanEnd =
                    minOf(
                        allIds.size,
                        safeOffset + maxOf(limit * 2, limit + HN_OVERFETCH_MIN_EXTRA),
                    )
                var cursor = safeOffset
                val collected = ArrayList<BoardPollCandidate>(limit)
                while (collected.size < limit && cursor < maxScanEnd) {
                    val remaining = limit - collected.size
                    val chunkEnd = minOf(maxScanEnd, cursor + remaining)
                    collected.addAll(fetchItemsConcurrently(allIds.subList(cursor, chunkEnd)))
                    cursor = chunkEnd
                }
                val ranked = collected.mapIndexed { idx, candidate ->
                    candidate.copy(rank = safeOffset + idx + 1)
                }
                BoardPollBatch(
                    items = ranked,
                    totalAvailable = allIds.size,
                    nextOffset = cursor,
                )
            }
        }

    private suspend fun fetchItemsConcurrently(storyIds: List<Long>): List<BoardPollCandidate> {
        if (storyIds.isEmpty()) return emptyList()
        val semaphore = Semaphore(HN_FETCH_CONCURRENCY)
        return coroutineScope {
            storyIds
                .map { storyId ->
                    async {
                        semaphore.withPermit {
                            val itemUrl = buildHackerNewsItemApiUrl(storyId.toString())
                            val raw = publicWebFetcher.fetchJson(itemUrl).getOrNull()
                            raw?.let {
                                parseStoryItem(rawJson = it, fallbackId = storyId, rank = 1)
                            }
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
    }

    private fun resolveSection(sourceKey: String, sourceConfigJson: String): HackerNewsSection =
        HackerNewsSection.fromSourceConfigJson(sourceConfigJson, sourceKey) ?: HackerNewsSection.TOP

    companion object {
        internal fun parseStoryIds(rawJson: String): List<Long> {
            val trimmed = rawJson.trim()
            if (trimmed.isEmpty()) return emptyList()
            val arr =
                runCatching { coreJson.parseToJsonElement(trimmed) as? JsonArray }.getOrNull()
                    ?: return emptyList()
            val seen = HashSet<Long>(arr.size)
            val out = ArrayList<Long>(arr.size)
            for (el in arr) {
                val id = runCatching { el.jsonPrimitive.longOrNull }.getOrNull()
                if (id != null && id > 0L && seen.add(id)) {
                    out.add(id)
                }
            }
            return out
        }

        internal fun parseStoryItem(
            rawJson: String,
            fallbackId: Long? = null,
            rank: Int = 1,
        ): BoardPollCandidate? {
            val trimmed = rawJson.trim()
            if (trimmed.isEmpty() || trimmed == "null") return null
            val obj =
                runCatching { coreJson.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
                    ?: return null
            return parseStoryObject(obj, fallbackId, rank)
        }

        private fun parseStoryObject(
            obj: JsonObject,
            fallbackId: Long?,
            rank: Int,
        ): BoardPollCandidate? {
            if (obj.boolOrFalse("deleted") || obj.boolOrFalse("dead")) return null
            val itemType = obj.stringOrNull("type")?.lowercase()
            if (itemType != null && itemType in SKIPPED_HN_ITEM_TYPES) return null
            val id = obj.longOrNull("id")?.takeIf { it > 0L } ?: fallbackId?.takeIf { it > 0L }
            val title =
                obj.stringOrNull("title")?.let(::decodeHtmlEntities)?.trim()?.takeIf {
                    it.isNotEmpty()
                }
            if (id == null || title == null) return null

            val externalId = id.toString()
            val discussionUrl = buildHackerNewsDiscussionUrl(externalId)
            val (resolvedUrl, domain) =
                resolveStoryUrlAndDomain(obj.stringOrNull("url"), discussionUrl) ?: return null
            val selfTextHtml =
                obj.stringOrNull("text")
                    ?.let { sanitizeRocheHtml(it, discussionUrl) }
                    ?.takeIf { it.isNotBlank() }
                    ?.take(MAX_SQLITE_HTML_CHARS)
            val summary =
                selfTextHtml
                    ?.let { stripHtmlTags(it).take(MAX_BOARD_SUMMARY_CHARS) }
                    ?.takeIf { it.isNotBlank() }
            val publishedAt =
                obj.longOrNull("time")
                    ?.takeIf { it > 0L }
                    ?.let { epochSecs -> formatUtcMillisIso(Instant.ofEpochSecond(epochSecs)) }

            return BoardPollCandidate(
                externalId = externalId,
                rank = rank.coerceAtLeast(1),
                title = title,
                url = resolvedUrl,
                discussionUrl = discussionUrl,
                author = obj.stringOrNull("by"),
                domain = domain,
                publishedAt = publishedAt,
                score = obj.intOrNull("score")?.takeIf { it >= 0 },
                commentCount = obj.intOrNull("descendants")?.takeIf { it >= 0 },
                summary = summary,
                selfTextHtml = selfTextHtml,
            )
        }

        private fun resolveStoryUrlAndDomain(
            rawUrl: String?,
            discussionUrl: String,
        ): Pair<String, String?>? {
            val clean = rawUrl?.trim().orEmpty()
            if (clean.isEmpty()) return discussionUrl to null
            val validPublic =
                isHttpOrHttpsUrl(clean) &&
                    runCatching { PublicUrlValidator.ensurePublicUrlSyntax(clean) }.isSuccess
            return if (validPublic) {
                val canonical = canonicalArxivAbstractUrl(clean) ?: clean
                canonical to extractCleanDomain(canonical).takeIf { it.isNotBlank() }
            } else {
                null
            }
        }
    }
}

private fun JsonObject.stringOrNull(key: String): String? = runCatching {
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
    .getOrNull()

private fun JsonObject.boolOrFalse(key: String): Boolean = runCatching {
    this[key]?.jsonPrimitive?.booleanOrNull == true
}
    .getOrDefault(false)

private fun JsonObject.longOrNull(key: String): Long? = runCatching {
    this[key]?.jsonPrimitive?.longOrNull
}
    .getOrNull()

private fun JsonObject.intOrNull(key: String): Int? = runCatching {
    this[key]?.jsonPrimitive?.intOrNull
}
    .getOrNull()

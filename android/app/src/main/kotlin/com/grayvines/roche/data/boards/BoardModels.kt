@file:Suppress("MatchingDeclarationName")

package com.grayvines.roche.data.boards

import com.grayvines.roche.core.DiscussionLink
import com.grayvines.roche.core.HACKER_NEWS_ITEM_URL_PREFIX
import com.grayvines.roche.core.SCHEME_HACKER_NEWS
import com.grayvines.roche.core.coreJson
import com.grayvines.roche.core.isHttpOrHttpsUrl
import com.grayvines.roche.core.nowUtcIso
import com.grayvines.roche.model.extractCleanDomain
import com.grayvines.roche.model.formatRelativeTimestamp
import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

const val BOARD_SNAPSHOT_BUFFER_LIMIT = 90
const val BOARD_DEFAULT_PAGE_SIZE = 30
const val BOARD_STATE_GRACE_HOURS = 48L
const val DEFAULT_BOARD_POLL_INTERVAL_MINUTES = 30

private const val HN_FIREBASE_BASE_URL = "https://hacker-news.firebaseio.com/v0"
private const val HN_WEB_HOST = "news.ycombinator.com"
private val HN_ALLOWED_WEB_HOSTS = setOf(HN_WEB_HOST, "www.$HN_WEB_HOST")

enum class BoardAdapterType(
    val wireValue: String,
    val displayLabel: String,
) {
    HACKER_NEWS(SCHEME_HACKER_NEWS, "Hacker News");

    companion object {
        fun fromWireValue(raw: String?): BoardAdapterType? {
            val clean = raw?.trim()?.lowercase(Locale.ROOT)?.replace('-', '_') ?: return null
            return entries.firstOrNull { it.wireValue == clean }
        }
    }
}

enum class BoardOrderMode(
    val wireValue: String,
    val displayLabel: String,
) {
    RANKED("ranked", "Ranked"),
    CHRONOLOGICAL("chronological", "Newest");

    companion object {
        fun fromWireValue(raw: String?): BoardOrderMode =
            entries.firstOrNull { it.wireValue.equals(raw?.trim(), ignoreCase = true) } ?: RANKED
    }
}

enum class HackerNewsSection(
    val sourceKey: String,
    val sourceUrl: String,
    val apiStoriesUrl: String,
    val defaultTitle: String,
    val orderMode: BoardOrderMode,
) {
    TOP(
        sourceKey = "top",
        sourceUrl = "https://news.ycombinator.com/",
        apiStoriesUrl = "$HN_FIREBASE_BASE_URL/topstories.json",
        defaultTitle = "Hacker News",
        orderMode = BoardOrderMode.RANKED,
    ),
    BEST(
        sourceKey = "best",
        sourceUrl = "https://news.ycombinator.com/best",
        apiStoriesUrl = "$HN_FIREBASE_BASE_URL/beststories.json",
        defaultTitle = "Hacker News · Best",
        orderMode = BoardOrderMode.RANKED,
    ),
    ASK(
        sourceKey = "ask",
        sourceUrl = "https://news.ycombinator.com/ask",
        apiStoriesUrl = "$HN_FIREBASE_BASE_URL/askstories.json",
        defaultTitle = "Ask HN",
        orderMode = BoardOrderMode.RANKED,
    ),
    SHOW(
        sourceKey = "show",
        sourceUrl = "https://news.ycombinator.com/show",
        apiStoriesUrl = "$HN_FIREBASE_BASE_URL/showstories.json",
        defaultTitle = "Show HN",
        orderMode = BoardOrderMode.RANKED,
    ),
    NEW(
        sourceKey = "new",
        sourceUrl = "https://news.ycombinator.com/newest",
        apiStoriesUrl = "$HN_FIREBASE_BASE_URL/newstories.json",
        defaultTitle = "Hacker News · New",
        orderMode = BoardOrderMode.CHRONOLOGICAL,
    );

    val sourceConfigJson: String
        get() = """{"section":"$sourceKey"}"""

    companion object {
        fun fromSectionKey(raw: String?): HackerNewsSection? {
            val norm = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
            return when (norm) {
                "top",
                "front",
                "news",
                "topstories" -> TOP
                "best",
                "beststories" -> BEST
                "ask",
                "askhn",
                "ask_hn",
                "askstories" -> ASK
                "show",
                "showhn",
                "show_hn",
                "shownew",
                "showstories" -> SHOW
                "new",
                "newest",
                "newstories" -> NEW
                else -> null
            }
        }

        fun fromSectionOrUrl(raw: String?): HackerNewsSection? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val direct = fromSectionKey(trimmed)
            val lower = trimmed.lowercase(Locale.ROOT)
            val urlCandidate =
                when {
                    direct != null -> null
                    isHttpOrHttpsUrl(trimmed) -> trimmed
                    "://" in trimmed -> null
                    lower.startsWith(HN_WEB_HOST) || lower.startsWith("www.$HN_WEB_HOST") ->
                        "https://$trimmed"
                    else -> null
                }
            return direct ?: urlCandidate?.let(::parseHackerNewsSectionUrl)
        }

        fun fromSourceConfigJson(
            sourceConfigJson: String?,
            fallbackSourceKey: String? = null,
        ): HackerNewsSection? {
            val fromConfig =
                sourceConfigJson
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it != "{}" }
                    ?.let { raw ->
                        runCatching { coreJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                    }
                    ?.get("section")
                    ?.let { el -> runCatching { el.jsonPrimitive.contentOrNull }.getOrNull() }
                    ?.let(::fromSectionKey)
            return fromConfig ?: fromSectionKey(fallbackSourceKey)
        }

        private fun parseHackerNewsSectionUrl(url: String): HackerNewsSection? {
            val uri = runCatching { URI(url.replace(" ", "%20")) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.trim()?.trimEnd('.')?.lowercase(Locale.ROOT)
            val validScheme = scheme == "http" || scheme == "https"
            if (!validScheme || uri.rawUserInfo != null || host !in HN_ALLOWED_WEB_HOSTS) {
                return null
            }
            val path = uri.rawPath.orEmpty().trim('/').lowercase(Locale.ROOT)
            return when (path) {
                "",
                "news",
                "front",
                "active" -> TOP
                "best" -> BEST
                "ask" -> ASK
                "show",
                "shownew" -> SHOW
                "newest",
                "new" -> NEW
                else -> null
            }
        }
    }
}

@Suppress("LongParameterList")
data class BoardPollCandidate(
    val externalId: String,
    val rank: Int,
    val title: String,
    val url: String,
    val discussionUrl: String?,
    val author: String?,
    val domain: String?,
    val publishedAt: String?,
    val score: Int?,
    val commentCount: Int?,
    val summary: String?,
    val selfTextHtml: String?,
    val metadataJson: String = "{}",
)

data class BoardPollBatch(
    val items: List<BoardPollCandidate>,
    val totalAvailable: Int = items.size,
    val nextOffset: Int = items.size,
    val fetchedAtIso: String = nowUtcIso(),
)

@Suppress("LongParameterList")
data class BoardSummary(
    val id: String,
    val adapterType: BoardAdapterType,
    val sourceKey: String,
    val sourceUrl: String,
    val sourceConfigJson: String,
    val title: String,
    val orderMode: BoardOrderMode,
    val pollInterval: Int,
    val isActive: Boolean,
    val lastPolledAt: String? = null,
    val lastPolledBy: String? = null,
    val lastError: String? = null,
    val lastErrorBy: String? = null,
    val consecutiveFailures: Int = 0,
    val snapshotFetchedAt: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val visibleItemCount: Int = 0,
    val unseenItemCount: Int = 0,
    val dismissedItemCount: Int = 0,
    val bufferedItemCount: Int = 0,
) {
    val isPaused: Boolean
        get() = !isActive

    val hasPollError: Boolean
        get() = !lastError.isNullOrBlank()

    val hnSection: HackerNewsSection?
        get() =
            if (adapterType == BoardAdapterType.HACKER_NEWS) {
                HackerNewsSection.fromSourceConfigJson(sourceConfigJson, sourceKey)
            } else {
                null
            }
}

fun BoardSummary.isCrossPeerRescueError(localPeerId: String): Boolean =
    hasPollError &&
        consecutiveFailures <= 1 &&
        !lastErrorBy.isNullOrBlank() &&
        lastErrorBy != localPeerId

@Suppress("LongParameterList")
data class BoardItemView(
    val boardId: String,
    val externalId: String,
    val rank: Int,
    val title: String,
    val url: String,
    val discussionUrl: String?,
    val author: String?,
    val domain: String?,
    val publishedAt: String?,
    val score: Int?,
    val commentCount: Int?,
    val summary: String?,
    val selfTextHtml: String?,
    val metadataJson: String = "{}",
    val fetchedAt: String = "",
    val seenAt: String? = null,
    val dismissedAt: String? = null,
    val linkedEntryId: String? = null,
    val isInQueue: Boolean = false,
    val isStarred: Boolean = false,
    val isFinished: Boolean = false,
    val isEphemeralPageItem: Boolean = false,
) {
    val isSeen: Boolean
        get() = !seenAt.isNullOrBlank()

    val isDismissed: Boolean
        get() = !dismissedAt.isNullOrBlank()

    val isSelfPost: Boolean
        get() =
            !selfTextHtml.isNullOrBlank() ||
                !discussionUrl.isNullOrBlank() && url.equals(discussionUrl, ignoreCase = true)

    val displayDomain: String by
        lazy(LazyThreadSafetyMode.PUBLICATION) {
            domain?.takeIf { it.isNotBlank() } ?: extractCleanDomain(url)
        }

    val formattedPublishedLabel: String by
        lazy(LazyThreadSafetyMode.PUBLICATION) {
            formatRelativeTimestamp(publishedAt ?: fetchedAt)
        }
}

data class BoardPageResult(
    val items: List<BoardItemView>,
    val offset: Int,
    val nextOffset: Int = offset + items.size,
    val totalAvailable: Int,
    val hasMore: Boolean,
)

@Suppress("LongParameterList")
data class BoardReaderContent(
    val boardId: String,
    val externalId: String,
    val item: BoardItemView,
    val contentHtml: String?,
    val contentText: String?,
    val extractedTitle: String? = null,
    val extractedAuthor: String? = null,
    val extractedSummary: String? = null,
    val extractedThumbnailUrl: String? = null,
    val isSelfPost: Boolean = item.isSelfPost,
    val extractionFailed: Boolean = false,
    val fetchError: String? = null,
    val linkedEntryId: String? = item.linkedEntryId,
    val discussions: List<DiscussionLink> = emptyList(),
)

enum class BoardPromoteAction(val wireValue: String) {
    QUEUE("queue"),
    STAR("star"),
    FINISH("finish"),
    TAG("tag"),
    LIST("list"),
}

data class BoardPromoteResult(
    val entryId: String,
    val action: BoardPromoteAction,
    val title: String,
    val wasExistingEntry: Boolean,
)

internal fun buildHackerNewsItemApiUrl(itemId: String): String =
    "$HN_FIREBASE_BASE_URL/item/${itemId.trim()}.json"

internal fun buildHackerNewsDiscussionUrl(itemId: String): String =
    "$HACKER_NEWS_ITEM_URL_PREFIX${itemId.trim()}"

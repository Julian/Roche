package com.grayvines.roche.data.catalog

import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.chooseDeterministicEntryIdRaw
import com.grayvines.roche.core.normalizeIdentifierValueRaw
import com.grayvines.roche.data.local.CollectionSpecEntity
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

enum class EntryKind(
    val wireValue: String,
    val displayLabel: String,
    val systemListSlug: String,
    val systemListTitle: String,
) {
    MOVIE("movie", "Movie", "movies", "Movies"),
    TV("tv", "TV", "tv", "TV"),
    ALBUM("album", "Album", "albums", "Albums"),
    ARTIST("artist", "Artist", "artists", "Artists"),
    SONG("song", "Song", "songs", "Songs"),
    BOOK("book", "Book", "books", "Books"),
    PODCAST("podcast", "Podcast", "podcasts", "Podcasts"),
    PODCAST_EPISODE(
        "podcast_episode",
        "Podcast Episode",
        "podcast_episodes",
        "Podcast Episodes",
    ),
    GAME("game", "Game", "games", "Games"),
    ARTICLE("article", "Article", "articles", "Articles"),
    PAPER("paper", "Paper", "papers", "Papers"),
    VIDEO("video", "Video", "videos", "Videos"),
    CHANNEL("channel", "Channel", "channels", "Channels"),
    PUBLICATION("publication", "Publication", "publications", "Publications"),
    COLLECTION("collection", "Collection", "collections", "Collections");

    val isDiaryVisibleKind: Boolean
        get() = this != ARTIST && this != PODCAST && this != PUBLICATION && this != CHANNEL

    companion object {
        val loggableKinds: List<EntryKind> =
            listOf(
                ALBUM,
                MOVIE,
                BOOK,
                TV,
                GAME,
                SONG,
                ARTICLE,
                PAPER,
                VIDEO,
                PODCAST_EPISODE,
                PODCAST,
            )

        val systemDynamicKinds: List<EntryKind> =
            listOf(
                MOVIE,
                TV,
                ARTIST,
                ALBUM,
                SONG,
                BOOK,
                PODCAST,
                PODCAST_EPISODE,
                GAME,
                ARTICLE,
                PAPER,
                VIDEO,
                CHANNEL,
                PUBLICATION,
            )

        fun fromWireValue(raw: String?): EntryKind = fromWireValueOrNull(raw) ?: ARTICLE

        fun fromWireValueOrNull(raw: String?): EntryKind? {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.wireValue.equals(trimmed, ignoreCase = true) }
        }

        fun fromFlexibleAlias(raw: String?): EntryKind? {
            val norm =
                raw?.trim()?.lowercase()?.replace('-', '_')?.takeIf { it.isNotEmpty() }
                    ?: return null
            val direct = entries.firstOrNull {
                it.wireValue == norm ||
                    it.systemListSlug == norm ||
                    it.displayLabel.lowercase().replace(' ', '_') == norm
            }
            return direct
                ?: when (norm) {
                    "film",
                    "films" -> MOVIE
                    "show",
                    "shows",
                    "series" -> TV
                    "track",
                    "tracks" -> SONG
                    "episode",
                    "episodes" -> PODCAST_EPISODE
                    "blog",
                    "blogs" -> PUBLICATION
                    "list",
                    "lists" -> COLLECTION
                    else -> null
                }
        }
    }
}

enum class CollectionMode(val wireValue: String) {
    MANUAL("manual"),
    FEED("feed"),
    DYNAMIC("dynamic");

    companion object {
        fun fromWireValue(raw: String?): CollectionMode =
            entries.firstOrNull { it.wireValue.equals(raw?.trim(), ignoreCase = true) } ?: MANUAL
    }
}

enum class CollectionRole(val wireValue: String) {
    LIST("list"),
    WANT("want"),
    SYSTEM("system"),
    TAG("tag"),
    FEED("feed");

    companion object {
        fun fromWireValue(raw: String?): CollectionRole =
            entries.firstOrNull { it.wireValue.equals(raw?.trim(), ignoreCase = true) } ?: LIST
    }
}

enum class ConsumptionStatus(val wireValue: String, val displayLabel: String) {
    STARTED("started", "Started"),
    CONSUMED("consumed", "Consumed"),
    ABANDONED("abandoned", "Abandoned");

    companion object {
        fun fromWireValue(raw: String?): ConsumptionStatus = fromWireValueOrNull(raw) ?: CONSUMED

        fun fromWireValueOrNull(raw: String?): ConsumptionStatus? {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.wireValue.equals(trimmed, ignoreCase = true) }
        }
    }
}

@Serializable
data class EntryIdentifierDto(
    val scheme: String,
    val value: String,
)

@Serializable
internal data class DynamicCollectionRuleDto(
    val kind: String? = null,
    val kinds: List<String> = emptyList(),
    val status: String? = null,
    val consumed: Boolean? = null,
    @SerialName("release_year") val releaseYear: Int? = null,
    val creator: String? = null,
    @SerialName("creator_contains") val creatorContains: String? = null,
)

data class CatalogEntryItem(
    val id: String,
    val kind: EntryKind,
    val title: String,
    val creators: List<String>,
    val url: String?,
    val publishedAt: String?,
    val releaseYear: Int?,
    val summary: String?,
    val thumbnailUrl: String?,
    val identifiers: List<EntryIdentifierDto>,
) {
    val primaryCreator: String
        get() = creators.firstOrNull().orEmpty()

    val subtitleLine: String
        get() = buildList {
            if (creators.isNotEmpty()) add(creators.joinToString(", "))
            if (releaseYear != null && releaseYear > 0) add(releaseYear.toString())
        }
            .joinToString(" · ")
}

data class RankedCatalogEntry(
    val entry: CatalogEntryItem,
    val rank: Int,
    val mean: Double,
    val variance: Double,
    val scoreOutOfTen: Double,
    val tier: Char?,
    val latestStatus: ConsumptionStatus?,
    val latestNotes: String?,
)

data class CatalogListSummary(
    val collectionId: String,
    val slug: String,
    val title: String,
    val mode: CollectionMode,
    val role: CollectionRole,
    val entryCount: Int,
    val isPriorityScoped: Boolean,
    val hasGenerator: Boolean = false,
    val generatorEnabled: Boolean = false,
    val poolTotalCount: Int = 0,
    val poolRemainingCount: Int = 0,
)

data class DiaryEntryItem(
    val id: String,
    val entryId: String,
    val kind: EntryKind,
    val title: String,
    val creators: List<String>,
    val releaseYear: Int?,
    val ts: String,
    val monthKey: String,
    val dateLabel: String,
    val status: ConsumptionStatus,
    val notes: String?,
    val tier: Char?,
)

data class CollectionGeneratorState(
    val collectionId: String,
    val collectionSlug: String,
    val collectionTitle: String,
    val enabled: Boolean = false,
    val currentDraw: CollectionDrawSnapshot? = null,
    val consumedCount: Int = 0,
    val abandonedCount: Int = 0,
    val totalCount: Int = 0,
) {
    val completedCount: Int
        get() = consumedCount + abandonedCount

    val remainingCount: Int
        get() = (totalCount - completedCount).coerceAtLeast(0)
}

data class CatalogSnapshot(
    val lists: List<CatalogListSummary> = emptyList(),
    val wantQueues: List<CatalogListSummary> = emptyList(),
    val entriesByCollectionId: Map<String, List<RankedCatalogEntry>> = emptyMap(),
    val diaryEntries: List<DiaryEntryItem> = emptyList(),
    val generators: List<CollectionGeneratorState> = emptyList(),
    val comparisonCount: Int = 0,
) {
    val consumedCount: Int
        get() =
            diaryEntries
                .filter { it.status == ConsumptionStatus.CONSUMED }
                .map { it.entryId }
                .distinct()
                .size

    val wantItemCount: Int
        get() = wantQueues.sumOf { it.entryCount }
}

fun CatalogSnapshot.generatorFor(collectionId: String): CollectionGeneratorState? =
    generators.firstOrNull {
        it.collectionId == collectionId
    }

data class UpsertCatalogEntryInput(
    val id: String? = null,
    val kind: EntryKind,
    val title: String,
    val creators: List<String> = emptyList(),
    val url: String? = null,
    val publishedAt: String? = null,
    val releaseYear: Int? = null,
    val summary: String? = null,
    val thumbnailUrl: String? = null,
    val metadataJson: String = "{}",
    val identifiers: List<EntryIdentifierDto> = emptyList(),
)

data class RankClusterCandidate(
    val entryId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val mean: Double,
    val variance: Double,
)

data class RankSettlementSummary(
    val entryId: String,
    val title: String,
    val collectionTitle: String,
    val rank: Int,
    val totalCount: Int,
    val tier: Char?,
)

data class CollectionDrawStepResult(
    val entryId: String,
    val collectionId: String,
    val collectionTitle: String,
    val nextDraw: CollectionDrawSnapshot?,
)

data class CollectionRankMeta(
    val collectionId: String,
    val slug: String,
    val title: String,
    val scopeCollectionId: String?,
)

@Serializable
data class SyncEntryContentDto(
    @SerialName("content_type") val contentType: String = "article",
    @SerialName("content_html") val contentHtml: String? = null,
    @SerialName("audio_url") val audioUrl: String? = null,
    val duration: Long? = null,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class SyncEntryStateDto(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("inbox_state")
    val inboxState: String = "none",
    @SerialName("read_at") val readAt: String? = null,
    val progress: Double = 0.0,
    @SerialName("interaction_updated_at") val interactionUpdatedAt: String? = null,
    val hlc: String = "",
)

@Serializable
data class SyncCollectionSpecDto(
    val slug: String? = null,
    val mode: String = "manual",
    val role: String = "list",
    @SerialName("rule_json") val ruleJson: String? = null,
    @SerialName("show_in_lists") val showInLists: Boolean = false,
    val color: String? = null,
)

@Serializable
@Suppress("LongParameterList")
data class SyncFeedSourceDto(
    @SerialName("feed_url") val feedUrl: String,
    @SerialName("source_type") val sourceType: String = "rss",
    @SerialName("feed_title") val feedTitle: String? = null,
    val language: String? = null,
    @SerialName("poll_interval") val pollInterval: Int = 30,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("last_polled_at") val lastPolledAt: String? = null,
    @SerialName("last_polled_by") val lastPolledBy: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("last_error_by") val lastErrorBy: String? = null,
    @SerialName("consecutive_failures") val consecutiveFailures: Int = 0,
    val hlc: String = "",
)

@Serializable
@Suppress("LongParameterList")
data class SyncEntryRecordDto(
    val id: String = "",
    val kind: String,
    val title: String,
    val creators: List<String> = emptyList(),
    val url: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("release_year") val releaseYear: Int? = null,
    val summary: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    val metadata: JsonElement? = null,
    val identifiers: List<EntryIdentifierDto> = emptyList(),
    @SerialName("merged_into_id") val mergedIntoId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val hlc: String = "",
    val content: SyncEntryContentDto? = null,
    val state: SyncEntryStateDto? = null,
    @SerialName("collection_spec") val collectionSpec: SyncCollectionSpecDto? = null,
    @SerialName("feed_source") val feedSource: SyncFeedSourceDto? = null,
)

@Serializable
data class SyncCollectionEntryDto(
    @SerialName("collection_id") val collectionId: String,
    @SerialName("entry_id") val entryId: String,
    val position: Double? = null,
    @SerialName("added_at") val addedAt: String,
    val hlc: String = "",
)

@Serializable
data class SyncCollectionGeneratorDto(
    @SerialName("collection_id") val collectionId: String,
    val enabled: Boolean,
    @SerialName("current_entry_id") val currentEntryId: String? = null,
    @SerialName("drawn_at") val drawnAt: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    val hlc: String = "",
)

@Serializable
data class SyncConsumptionLogDto(
    val id: String = "",
    @SerialName("entry_id") val entryId: String,
    val ts: String,
    val status: String,
    val notes: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val hlc: String = "",
)

@Serializable
data class SyncComparisonDto(
    val id: String = "",
    val ts: String,
    @SerialName("scope_collection_id") val scopeCollectionId: String? = null,
    val ordering: List<List<String>>,
    @SerialName("created_at") val createdAt: String? = null,
    val hlc: String = "",
)

internal fun isGeneratorEligible(spec: CollectionSpecEntity): Boolean =
    spec.collectionId != STARRED_COLLECTION_ID &&
        CollectionMode.fromWireValue(spec.mode) == CollectionMode.MANUAL &&
        CollectionRole.fromWireValue(spec.role) == CollectionRole.LIST

internal fun normalizeIdentifierValue(
    kind: EntryKind,
    scheme: String,
    rawValue: String,
): String = normalizeIdentifierValueRaw(kind.wireValue, scheme, rawValue)

internal fun chooseDeterministicEntryId(
    explicitId: String?,
    kind: EntryKind,
    title: String,
    creators: List<String>,
    identifiers: List<EntryIdentifierDto>,
): String =
    chooseDeterministicEntryIdRaw(
        explicitId = explicitId,
        kindWireValue = kind.wireValue,
        title = title,
        creators = creators,
        identifiers = identifiers.map { it.scheme to it.value },
    )

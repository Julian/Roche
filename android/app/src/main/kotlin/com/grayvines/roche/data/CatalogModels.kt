package com.grayvines.roche.data

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

@Serializable
internal data class BundledAlbumPoolItem(
    val id: String,
    @SerialName("spotify_id") val spotifyId: String = "",
    val slug: String = "",
    val title: String,
    val artist: String,
    val year: Int? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    val genres: List<String> = emptyList(),
    val styles: List<String> = emptyList(),
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

data class AlbumGeneratorState(
    val enabled: Boolean = false,
    val currentAlbum: DailyAlbumSnapshot? = null,
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
    val albumGenerator: AlbumGeneratorState = AlbumGeneratorState(),
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

data class Album1001StepResult(
    val entryId: String,
    val nextAlbum: DailyAlbumSnapshot?,
)

data class CollectionRankMeta(
    val collectionId: String,
    val slug: String,
    val title: String,
    val scopeCollectionId: String?,
)

@Serializable
data class PeerCatalogEntryPayload(
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
)

@Serializable
data class PeerCollectionSpecPayload(
    @SerialName("collection_id") val collectionId: String = "",
    val title: String,
    val summary: String? = null,
    val slug: String? = null,
    val mode: String = "manual",
    val role: String = "list",
    @SerialName("rule_json") val ruleJson: String? = null,
    @SerialName("show_in_lists") val showInLists: Boolean = false,
    val color: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class PeerCollectionEntryPayload(
    @SerialName("collection_id") val collectionId: String,
    @SerialName("entry_id") val entryId: String,
    val position: Double? = null,
    @SerialName("added_at") val addedAt: String,
)

@Serializable
data class PeerCollectionGeneratorPayload(
    @SerialName("collection_id") val collectionId: String,
    val enabled: Boolean,
    @SerialName("current_entry_id") val currentEntryId: String? = null,
    @SerialName("drawn_at") val drawnAt: String? = null,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class PeerConsumptionLogPayload(
    val id: String = "",
    @SerialName("entry_id") val entryId: String,
    val ts: String,
    val status: String,
    val notes: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class PeerComparisonPayload(
    val id: String = "",
    val ts: String,
    @SerialName("scope_collection_id") val scopeCollectionId: String? = null,
    val ordering: List<List<String>>,
    @SerialName("created_at") val createdAt: String? = null,
)

@file:Suppress("MatchingDeclarationName")

package com.grayvines.roche.data

import com.grayvines.roche.data.remote.HttpRequest
import com.grayvines.roche.data.remote.HttpTransport
import com.grayvines.roche.data.remote.rocheJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class WikipediaSearchHit(
    val title: String,
    val snippet: String,
)

internal suspend fun resolveAlbumWikipediaSlug(
    transport: HttpTransport,
    album: CollectionDrawSnapshot,
): String? {
    val cleanTitle = stripAlbumEditionSuffix(album.title).trim()
    if (cleanTitle.isEmpty()) return null
    val query = "$cleanTitle ${album.creator} album".trim()
    val url = "$WIKIPEDIA_SEARCH_ENDPOINT&srsearch=${encodeQueryValue(query)}"
    val response = runSuspendCatching {
        transport.execute(
            HttpRequest(
                method = "GET",
                url = url,
                headers = WIKIPEDIA_HEADERS,
                requirePublicIp = true,
            )
        )
    }
        .getOrNull()
    if (response == null || response.statusCode !in WIKI_HTTP_OK_START..WIKI_HTTP_OK_END) {
        return null
    }
    return selectBestWikipediaSlug(parseWikipediaSearchPayload(response.body), album)
}

internal fun parseWikipediaSearchPayload(rawJson: String): List<WikipediaSearchHit> {
    val root =
        runCatching { rocheJson.parseToJsonElement(rawJson) as? JsonObject }.getOrNull()
            ?: return emptyList()
    val queryObj = root["query"] as? JsonObject ?: return emptyList()
    val searchArr = queryObj["search"] as? JsonArray ?: return emptyList()
    return searchArr.mapNotNull(::toWikipediaSearchHit)
}

private fun toWikipediaSearchHit(
    item: kotlinx.serialization.json.JsonElement
): WikipediaSearchHit? {
    val obj = item as? JsonObject ?: return null
    val title = (obj["title"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    val snippet = (obj["snippet"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    return if (title.isEmpty()) null else WikipediaSearchHit(title = title, snippet = snippet)
}

internal fun selectBestWikipediaSlug(
    hits: List<WikipediaSearchHit>,
    album: CollectionDrawSnapshot,
): String? {
    val normTitle = normalizeMatchTokens(album.title)
    if (normTitle.isEmpty() || hits.isEmpty()) return null
    val normArtist = normalizeMatchTokens(album.creator)
    val scored =
        hits
            .map { hit -> hit to scoreWikipediaHit(hit, normTitle, normArtist, album.releaseDate) }
            .filter { (_, score) -> score > 0 }
    val best = scored.maxByOrNull { (_, score) -> score }?.first ?: return null
    return best.title.trim().replace(' ', '_').takeIf(String::isNotEmpty)
}

private fun scoreWikipediaHit(
    hit: WikipediaSearchHit,
    normTitle: String,
    normArtist: String,
    releaseYear: String,
): Int {
    val (baseTitle, disambiguator) = splitWikipediaDisambiguator(hit.title)
    val normBase = normalizeMatchTokens(baseTitle)
    val normDisambig = normalizeMatchTokens(disambiguator)
    val normSnippet = normalizeMatchTokens(stripWikipediaSnippetHtml(hit.snippet))

    val titleMatches =
        normBase == normTitle ||
            normBase.isNotEmpty() && (normTitle in normBase || normBase in normTitle)
    val selfTitledBiography =
        normTitle == normArtist && disambiguator.isBlank() && !containsWord(normSnippet, WORD_ALBUM)
    val hasContext = hasWikipediaAlbumContext(normArtist, normDisambig, normSnippet, releaseYear)

    if (!titleMatches || selfTitledBiography || !hasContext) {
        return 0
    }
    return computePositiveWikiScore(
        normBase = normBase,
        normTitle = normTitle,
        normArtist = normArtist,
        normDisambig = normDisambig,
        normSnippet = normSnippet,
        releaseYear = releaseYear,
    )
}

private fun hasWikipediaAlbumContext(
    normArtist: String,
    normDisambig: String,
    normSnippet: String,
    releaseYear: String,
): Boolean {
    val artistMatch =
        normArtist.isNotEmpty() && (normArtist in normDisambig || normArtist in normSnippet)
    val yearMatch = releaseYear.isNotBlank() && releaseYear in normSnippet
    return artistMatch || containsWord(normDisambig, WORD_ALBUM) || yearMatch
}

@Suppress("LongParameterList")
private fun computePositiveWikiScore(
    normBase: String,
    normTitle: String,
    normArtist: String,
    normDisambig: String,
    normSnippet: String,
    releaseYear: String,
): Int {
    var score = if (normBase == normTitle) EXACT_TITLE_SCORE else PARTIAL_TITLE_SCORE
    val disambigHasAlbum = containsWord(normDisambig, WORD_ALBUM)
    if (disambigHasAlbum) score += DISAMBIG_ALBUM_SCORE
    if (normArtist.isNotEmpty() && (normArtist in normDisambig || normArtist in normSnippet)) {
        score += WIKI_ARTIST_SCORE
    }
    if (containsWord(normSnippet, WORD_ALBUM)) score += SNIPPET_ALBUM_SCORE
    if (releaseYear.isNotBlank() && releaseYear in normSnippet) score += WIKI_YEAR_SCORE
    if (!disambigHasAlbum && NON_ALBUM_DISAMBIG_WORDS.any { containsWord(normDisambig, it) }) {
        score -= NON_ALBUM_PENALTY
    }
    return score
}

internal fun splitWikipediaDisambiguator(rawTitle: String): Pair<String, String> {
    val trimmed = rawTitle.trim()
    if (!trimmed.endsWith(')')) return trimmed to ""
    val openIdx = trimmed.lastIndexOf(" (")
    return if (openIdx <= 0) {
        trimmed to ""
    } else {
        trimmed.substring(0, openIdx).trim() to
            trimmed.substring(openIdx + DISAMBIG_PREFIX_LEN, trimmed.length - 1).trim()
    }
}

internal fun stripWikipediaSnippetHtml(raw: String): String =
    decodeHtmlEntities(raw.replace(WIKI_HTML_TAG_REGEX, " "))

internal fun containsWord(haystack: String, word: String): Boolean =
    haystack.split(' ').any { it == word }

private const val WORD_ALBUM = "album"
private const val DISAMBIG_PREFIX_LEN = 2
internal const val WIKI_HTTP_OK_START = 200
internal const val WIKI_HTTP_OK_END = 299
private const val EXACT_TITLE_SCORE = 20
private const val PARTIAL_TITLE_SCORE = 10
private const val DISAMBIG_ALBUM_SCORE = 14
private const val WIKI_ARTIST_SCORE = 10
private const val SNIPPET_ALBUM_SCORE = 6
private const val WIKI_YEAR_SCORE = 4
private const val NON_ALBUM_PENALTY = 25
private val NON_ALBUM_DISAMBIG_WORDS = setOf("song", "single", "film", "tour", "band", "musician")
internal val WIKI_HTML_TAG_REGEX = Regex("<[^>]+>")
internal const val WIKIPEDIA_SEARCH_ENDPOINT =
    "https://en.wikipedia.org/w/api.php?action=query&list=search&srlimit=5&utf8=1&format=json"
internal val WIKIPEDIA_HEADERS =
    mapOf(
        "Accept" to "application/json",
        "User-Agent" to "Roche/1.0 (+https://github.com/Julian/Roche)",
    )

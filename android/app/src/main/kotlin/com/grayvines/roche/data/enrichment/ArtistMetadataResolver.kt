@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package com.grayvines.roche.data.enrichment

import com.grayvines.roche.core.EXTERNAL_BROWSER_USER_AGENT
import com.grayvines.roche.core.encodeQueryValue
import com.grayvines.roche.core.extractSpotifyEntityAndId
import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.core.stripAlbumEditionSuffix
import com.grayvines.roche.core.stripHtmlTags
import com.grayvines.roche.data.remote.HttpRequest
import com.grayvines.roche.data.remote.HttpTransport

internal suspend fun resolveArtistWikiSlug(
    transport: HttpTransport,
    artistName: String,
    albumTitle: String = "",
): String? {
    val cleanArtist =
        artistName
            .replace('"', ' ')
            .replace('(', ' ')
            .replace(')', ' ')
            .trim()
            .replace(WHITESPACE_REGEX, " ")
    if (cleanArtist.isEmpty() || cleanArtist.equals(VARIOUS_ARTISTS_NAME, ignoreCase = true)) {
        return null
    }
    val srsearch = "\"$cleanArtist\" $ARTIST_WIKI_QUERY_FILTER"
    val url = "$ARTIST_WIKI_SEARCH_ENDPOINT&srsearch=${encodeQueryValue(srsearch)}"
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
    return selectBestArtistWikiSlug(
        hits = parseWikipediaSearchPayload(response.body),
        artistName = artistName,
        albumTitle = albumTitle,
    )
}

internal fun selectBestArtistWikiSlug(
    hits: List<WikipediaSearchHit>,
    artistName: String,
    albumTitle: String = "",
): String? =
    selectBestArtistWikiSlug(
        hits = hits,
        artistName = artistName,
        childTitles = if (albumTitle.isBlank()) emptyList() else listOf(albumTitle),
    )

internal fun selectBestArtistWikiSlug(
    hits: List<WikipediaSearchHit>,
    artistName: String,
    childTitles: List<String>,
): String? {
    val normArtist = normalizeArtistTokens(artistName)
    val ineligible =
        artistName.trim().equals(VARIOUS_ARTISTS_NAME, ignoreCase = true) ||
            normArtist.isEmpty() ||
            hits.isEmpty()
    if (ineligible) return null
    val normChildren =
        childTitles
            .map { normalizeArtistTokens(stripAlbumEditionSuffix(it)) }
            .filter { it.length >= MIN_CHILD_TITLE_LEN }
    val best =
        hits
            .map { hit -> hit to scoreArtistWikiHit(hit, normArtist, normChildren) }
            .filter { (_, score) -> score > 0 }
            .maxByOrNull { (_, score) -> score }
            ?.first
    return best?.title?.trim()?.replace(' ', '_')?.takeIf(String::isNotEmpty)
}

internal fun scoreArtistWikiCandidate(
    hit: WikipediaSearchHit,
    artistName: String,
    childTitles: List<String> = emptyList(),
): Int {
    val normArtist = normalizeArtistTokens(artistName)
    if (artistName.trim().equals(VARIOUS_ARTISTS_NAME, ignoreCase = true) || normArtist.isEmpty()) {
        return 0
    }
    val normChildren =
        childTitles
            .map { normalizeArtistTokens(stripAlbumEditionSuffix(it)) }
            .filter { it.length >= MIN_CHILD_TITLE_LEN }
    return scoreArtistWikiHit(hit, normArtist, normChildren)
}

private fun scoreArtistWikiHit(
    hit: WikipediaSearchHit,
    normArtist: String,
    normChildren: List<String>,
): Int {
    val rawTitle = hit.title.trim()
    val (baseTitle, disambiguator) = splitWikipediaDisambiguator(rawTitle)
    val normBase = normalizeArtistTokens(baseTitle)
    val normDisambig = normalizeArtistTokens(disambiguator)
    val cleanSnippet = stripWikipediaSnippetHtml(hit.snippet)
    val normSnippet = normalizeArtistTokens(cleanSnippet)
    val baseMatches = matchesArtistBaseTitle(rawTitle, baseTitle, normBase, normArtist)
    val validContext =
        isEligibleArtistContext(disambiguator, normDisambig, cleanSnippet, normSnippet)
    if (!baseMatches || !validContext) {
        return 0
    }
    return computeArtistWikiScore(normBase, normArtist, normDisambig, normSnippet, normChildren)
}

private fun matchesArtistBaseTitle(
    rawTitle: String,
    baseTitle: String,
    normBase: String,
    normArtist: String,
): Boolean {
    if (EXCLUDED_TITLE_PREFIXES.any { rawTitle.startsWith(it) }) return false
    if (baseTitle.lowercase().endsWith(" discography")) return false
    val strippedBase = normBase.removePrefix("the ").trim()
    val strippedArtist = normArtist.removePrefix("the ").trim()
    return normBase == normArtist || strippedBase.isNotEmpty() && strippedBase == strippedArtist
}

private fun isEligibleArtistContext(
    rawDisambig: String,
    normDisambig: String,
    cleanSnippet: String,
    normSnippet: String,
): Boolean {
    if (REJECTED_ARTIST_DISAMBIG.any { containsWord(normDisambig, it) }) return false
    if (rawDisambig.isEmpty() && isWorkOpeningClause(cleanSnippet)) return false
    val hasDisambig = ARTIST_DISAMBIG_WORDS.any { containsWord(normDisambig, it) }
    val hasMusicSnippet =
        MUSIC_BIO_WORDS.any { containsWord(normSnippet, it) } || "hip hop" in normSnippet
    return hasDisambig || hasMusicSnippet
}

private fun isWorkOpeningClause(cleanText: String): Boolean {
    val firstSentence = cleanText.substringBefore(". ").take(MAX_OPENING_CLAUSE_CHARS)
    val words = normalizeArtistTokens(firstSentence).split(' ').filter { it.isNotEmpty() }
    return WORK_OPENING_PHRASES.any { phrase -> containsWordSequence(words, phrase) }
}

private fun containsWordSequence(haystack: List<String>, needle: List<String>): Boolean =
    needle.isNotEmpty() &&
        haystack.size >= needle.size &&
        haystack.windowed(size = needle.size, step = 1).any { it == needle }

private fun computeArtistWikiScore(
    normBase: String,
    normArtist: String,
    normDisambig: String,
    normSnippet: String,
    normChildren: List<String>,
): Int {
    var score = if (normBase == normArtist) EXACT_ARTIST_BASE_SCORE else ALT_ARTIST_BASE_SCORE
    if (ARTIST_DISAMBIG_WORDS.any { containsWord(normDisambig, it) }) {
        score += ARTIST_DISAMBIG_BONUS
    }
    if (MUSIC_BIO_WORDS.any { containsWord(normSnippet, it) } || "hip hop" in normSnippet) {
        score += ARTIST_SNIPPET_BONUS
    }
    if (normChildren.any { it in normSnippet }) {
        score += ARTIST_ALBUM_BONUS
    }
    return score
}

internal suspend fun resolveSpotifyArtistUri(
    transport: HttpTransport,
    albumSpotifyId: String?,
    artistName: String,
): String? {
    if (artistName.trim().equals(VARIOUS_ARTISTS_NAME, ignoreCase = true)) return null
    val albumUrl = toValidSpotifyWorkUrl(albumSpotifyId) ?: return null
    val response = runSuspendCatching {
        transport.execute(
            HttpRequest(
                method = "GET",
                url = albumUrl,
                headers = SPOTIFY_HTML_HEADERS,
                requirePublicIp = true,
            )
        )
    }
        .getOrNull()
    if (response == null || response.statusCode !in WIKI_HTTP_OK_START..WIKI_HTTP_OK_END) {
        return null
    }
    return extractSpotifyArtistUri(
        html = response.body,
        artistName = artistName,
        allowSingleFallback = true,
    )
}

private fun toValidSpotifyWorkUrl(albumSpotifyId: String?): String? {
    val raw = albumSpotifyId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val entityAndId = extractSpotifyEntityAndId(raw)
    return if (
        entityAndId != null &&
            entityAndId.first in SPOTIFY_WORK_ENTITIES &&
            isValidSpotifyToken(entityAndId.second)
    ) {
        "https://open.spotify.com/${entityAndId.first}/${entityAndId.second}"
    } else if (!raw.contains(':') && !raw.contains('/') && isValidSpotifyToken(raw)) {
        "https://open.spotify.com/album/$raw"
    } else {
        null
    }
}

internal fun extractSpotifyArtistUri(
    html: String,
    artistName: String,
    allowSingleFallback: Boolean,
): String? {
    if (artistName.trim().equals(VARIOUS_ARTISTS_NAME, ignoreCase = true)) return null
    val strippedScripts = html.replace(SCRIPT_STYLE_BLOCK_REGEX, " ")
    val targetNorm = normalizeArtistTokens(artistName)
    val anchorMatch =
        targetNorm.takeIf(String::isNotEmpty)?.let { norm ->
            ANCHOR_ARTIST_REGEX.findAll(strippedScripts).firstNotNullOfOrNull { match ->
                val id = match.groupValues[1]
                val inner = stripHtmlTags(match.groupValues[2])
                id.takeIf { isValidSpotifyToken(it) && normalizeArtistTokens(inner) == norm }
            }
        }
    val fallbackMatch =
        if (anchorMatch == null && allowSingleFallback) {
            SPOTIFY_ARTIST_PATH_REGEX.findAll(strippedScripts)
                .map { it.groupValues[1] }
                .filter(::isValidSpotifyToken)
                .toSet()
                .singleOrNull()
        } else {
            null
        }
    return (anchorMatch ?: fallbackMatch)?.let { "spotify:artist:$it" }
}

private fun isValidSpotifyToken(token: String): Boolean =
    token.length in MIN_SPOTIFY_ID_LEN..MAX_SPOTIFY_ID_LEN &&
        token.all { it.isLetterOrDigit() && it.code < ASCII_UPPER_BOUND }

private fun normalizeArtistTokens(raw: String): String =
    raw.lowercase().replace(APOSTROPHE_REGEX, "").replace(NON_UNICODE_ALNUM_REGEX, " ").trim()

internal const val VARIOUS_ARTISTS_NAME = "various artists"
private const val MIN_CHILD_TITLE_LEN = 3
private const val MAX_OPENING_CLAUSE_CHARS = 180
private const val EXACT_ARTIST_BASE_SCORE = 24
private const val ALT_ARTIST_BASE_SCORE = 20
private const val ARTIST_DISAMBIG_BONUS = 18
private const val ARTIST_SNIPPET_BONUS = 8
private const val ARTIST_ALBUM_BONUS = 8
private const val MIN_SPOTIFY_ID_LEN = 15
private const val MAX_SPOTIFY_ID_LEN = 30
private const val ASCII_UPPER_BOUND = 128
private const val ARTIST_WIKI_SEARCH_ENDPOINT =
    "https://en.wikipedia.org/w/api.php?action=query&list=search&srlimit=8&utf8=1&format=json"
private const val ARTIST_WIKI_QUERY_FILTER =
    "(band OR musician OR singer OR rapper OR composer OR songwriter OR vocalist OR duo OR group OR orchestra)"
private const val TOKEN_ALBUM = "album"
private const val TOKEN_SONG = "song"
private const val TOKEN_SINGLE = "single"
private val SPOTIFY_WORK_ENTITIES = setOf(TOKEN_ALBUM, "track")
private val WHITESPACE_REGEX = Regex("""\s+""")
private val APOSTROPHE_REGEX = Regex("['\u2019]+")
private val NON_UNICODE_ALNUM_REGEX = Regex("""[^\p{L}\p{N}]+""")
private val SCRIPT_STYLE_BLOCK_REGEX =
    Regex(
        """<(script|style)\b[^>]*>.*?</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
private val ANCHOR_ARTIST_REGEX =
    Regex(
        """<a\s[^>]*href=["'][^"']*/artist/([A-Za-z0-9]+)[^"']*["'][^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
private val SPOTIFY_ARTIST_PATH_REGEX =
    Regex("""(?:open\.spotify\.com/artist/|href=["']/artist/)([A-Za-z0-9]+)""")
private val EXCLUDED_TITLE_PREFIXES = listOf("List of ", "Category:", "Draft:")
private val SPOTIFY_HTML_HEADERS =
    mapOf(
        "Accept" to "text/html,application/xhtml+xml",
        "User-Agent" to EXTERNAL_BROWSER_USER_AGENT,
    )
private val ARTIST_DISAMBIG_WORDS =
    setOf(
        "band",
        "musician",
        "musicians",
        "singer",
        "rapper",
        "group",
        "duo",
        "trio",
        "quartet",
        "ensemble",
        "orchestra",
        "composer",
        "dj",
        "producer",
        "vocalist",
        "guitarist",
        "pianist",
        "saxophonist",
        "trumpeter",
        "drummer",
        "bassist",
        "songwriter",
        "artist",
        "collective",
        "choir",
        "act",
    )
private val REJECTED_ARTIST_DISAMBIG =
    setOf(
        TOKEN_ALBUM,
        TOKEN_SONG,
        TOKEN_SINGLE,
        "ep",
        "film",
        "movie",
        "novel",
        "book",
        "television",
        "tv",
        "series",
        "episode",
        "tour",
        "soundtrack",
        "video",
        "game",
        "company",
        "magazine",
        "journal",
        "surname",
        "mythology",
        "planet",
        "asteroid",
        "painting",
        "disambiguation",
        "footballer",
        "politician",
        "municipality",
    )
private val MUSIC_BIO_WORDS =
    setOf(
        "band",
        "musician",
        "musicians",
        "singer",
        "songwriter",
        "rapper",
        "vocalist",
        "composer",
        "guitarist",
        "pianist",
        "saxophonist",
        "trumpeter",
        "drummer",
        "bassist",
        "producer",
        "dj",
        "duo",
        "trio",
        "quartet",
        "orchestra",
        "ensemble",
        "musical",
        "recording",
        "albums",
        "grammy",
        "discography",
        "rock",
        "jazz",
        "pop",
        "folk",
        "electronic",
        "metal",
        "punk",
        "country",
        "soul",
        "blues",
        "classical",
        "artist",
        "lyricist",
        "conductor",
        "collective",
    )
private val WORK_OPENING_PHRASES =
    listOf(
        listOf("studio", TOKEN_ALBUM),
        listOf("live", TOKEN_ALBUM),
        listOf("compilation", TOKEN_ALBUM),
        listOf("debut", TOKEN_ALBUM),
        listOf("soundtrack", TOKEN_ALBUM),
        listOf("remix", TOKEN_ALBUM),
        listOf("double", TOKEN_ALBUM),
        listOf("concept", TOKEN_ALBUM),
        listOf("extended", "play"),
        listOf("is", "an", TOKEN_ALBUM),
        listOf("is", "a", TOKEN_SONG),
        listOf("is", "a", TOKEN_SINGLE),
        listOf("is", "an", "ep"),
        listOf(TOKEN_SONG, "written"),
        listOf(TOKEN_SONG, "recorded"),
        listOf(TOKEN_SINGLE, "recorded"),
    )

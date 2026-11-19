@file:Suppress("TooManyFunctions")

package com.grayvines.roche.core

import java.net.URI

internal val EDITION_KEYWORDS: List<String> =
    listOf(
        "deluxe",
        "standard",
        "remaster",
        "remastered",
        "expanded",
        "special",
        "limited",
        "anniversary",
        "bonus",
        "edition",
        "version",
        "reissue",
        "explicit",
        "clean",
        "remasters",
    )

internal const val SCHEME_SPOTIFY = "spotify"
internal const val SCHEME_WIKIPEDIA = "wikipedia"
internal const val SCHEME_YOUTUBE_MUSIC = "youtube_music"
internal const val SCHEME_APPLE_MUSIC = "apple_music"
internal const val SCHEME_TIDAL = "tidal"
internal const val SCHEME_DEEZER = "deezer"
internal const val SCHEME_QOBUZ = "qobuz"
internal const val SCHEME_AMAZON_MUSIC = "amazon_music"
internal const val SCHEME_BANDCAMP = "bandcamp"

internal const val YT_MUSIC_ORIGIN = "https://music.youtube.com"
internal const val YT_MUSIC_BROWSE_PREFIX = "MPREb"

private const val SCHEME_ARXIV = "arxiv"
private const val ENTITY_ALBUM = "album"
private const val ENTITY_ARTIST = "artist"
private const val SPOTIFY_URI_PREFIX = "spotify:"
private const val SPOTIFY_PLAYLIST = "playlist"
private const val ARXIV_ABS_MARKER = "/abs/"
private const val ARXIV_PDF_MARKER = "/pdf/"
private const val YT_OLAK_TOKEN_PREFIX = "OLAK5uy_"
private const val YT_OLAK_PREFIX_LEN = 8
private const val YT_PL_TOKEN_PREFIX = "PL"
private const val YT_PL_PREFIX_LEN = 2

internal val PREFERRED_IDENTIFIER_SCHEMES: List<String> =
    listOf(
        SCHEME_SPOTIFY,
        "musicbrainz_release",
        "musicbrainz_recording",
        "musicbrainz_artist",
        "imdb",
        "tmdb_movie",
        "tmdb_tv",
        "isbn",
        "openlibrary",
        "igdb",
        SCHEME_ARXIV,
        SCHEME_WIKIPEDIA,
        "feed_url",
        "url",
    )

private val SPOTIFY_ENTITY_TYPES: Set<String> =
    setOf(ENTITY_ALBUM, "track", ENTITY_ARTIST, "episode", "show", SPOTIFY_PLAYLIST)

private val ARXIV_HOSTS: Set<String> = setOf("arxiv.org", "rss.arxiv.org", "export.arxiv.org")

private val EXTERNAL_ID_URL_PREFIXES: Map<String, String> =
    mapOf(
        "tmdb_movie" to "https://www.themoviedb.org/movie/",
        "tmdb_tv" to "https://www.themoviedb.org/tv/",
        "isbn" to "https://openlibrary.org/isbn/",
        "openlibrary" to "https://openlibrary.org/works/",
        "goodreads" to "https://www.goodreads.com/book/show/",
        "igdb" to "https://www.igdb.com/games/",
        "discogs" to "https://www.discogs.com/release/",
        SCHEME_ARXIV to "https://arxiv.org/abs/",
        "musicbrainz_release" to "https://musicbrainz.org/release/",
        "musicbrainz_artist" to "https://musicbrainz.org/artist/",
        "musicbrainz_recording" to "https://musicbrainz.org/recording/",
        SCHEME_APPLE_MUSIC to "https://music.apple.com/album/",
        SCHEME_TIDAL to "https://tidal.com/browse/album/",
        SCHEME_DEEZER to "https://www.deezer.com/album/",
        SCHEME_QOBUZ to "https://open.qobuz.com/album/",
        SCHEME_AMAZON_MUSIC to "https://music.amazon.com/albums/",
    )

private val YT_TOKEN_DELIMITER_REGEX = Regex("[\\s&?]+")
private val YT_VALID_TOKEN_REGEX = Regex("^[A-Za-z0-9_-]+$")
private const val MIN_SPOTIFY_URI_PARTS = 2
private const val SPOTIFY_USER_PLAYLIST_PARTS = 4
private const val SPOTIFY_PLAYLIST_ID_INDEX = 3
private const val MIN_IMDB_TOKEN_LENGTH = 4
private const val IMDB_PREFIX_LENGTH = 2

internal fun normalizeIdentifierScheme(rawScheme: String): String =
    rawScheme.trim().lowercase().replace('-', '_')

internal fun normalizeIdentifierValueRaw(
    kindWireValue: String,
    scheme: String,
    rawValue: String,
): String {
    val trimmed = rawValue.trim()
    if (trimmed.isEmpty()) return ""
    return when (normalizeIdentifierScheme(scheme)) {
        "url",
        "feed_url" -> canonicalizeSyncUrl(trimmed)
        "slug",
        "tag" -> trimmed.lowercase()
        SCHEME_SPOTIFY -> normalizeSpotifyIdentifier(kindWireValue, trimmed)
        "imdb" -> normalizeImdbIdentifier(trimmed)
        SCHEME_WIKIPEDIA -> normalizeWikipediaIdentifier(trimmed)
        SCHEME_ARXIV -> extractArxivIdentifier(trimmed) ?: trimmed
        else -> trimmed
    }
}

internal fun extractSpotifyEntityAndId(raw: String): Pair<String, String>? {
    val trimmed = raw.trim()
    return extractSpotifyFromColonUri(trimmed) ?: extractSpotifyFromWebUrl(trimmed)
}

private fun extractSpotifyFromColonUri(raw: String): Pair<String, String>? {
    if (!raw.startsWith(SPOTIFY_URI_PREFIX)) return null
    val parts = raw.removePrefix(SPOTIFY_URI_PREFIX).split(':').filter { it.isNotEmpty() }
    return when {
        parts.size >= SPOTIFY_USER_PLAYLIST_PARTS &&
            parts[0].equals("user", ignoreCase = true) &&
            parts[2].equals(SPOTIFY_PLAYLIST, ignoreCase = true) ->
            SPOTIFY_PLAYLIST to parts[SPOTIFY_PLAYLIST_ID_INDEX]
        parts.size >= MIN_SPOTIFY_URI_PARTS -> parts[0].lowercase() to parts[1]
        else -> null
    }
}

private fun extractSpotifyFromWebUrl(raw: String): Pair<String, String>? {
    val uri = parseLenientUri(raw) ?: return null
    val host = uri.host?.lowercase().orEmpty()
    if (host != "open.spotify.com" && host != "play.spotify.com") return null
    val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
    if (segments.size < MIN_SPOTIFY_URI_PARTS) return null
    val entity = segments[segments.size - 2].lowercase()
    val ident = segments.last()
    return if (entity in SPOTIFY_ENTITY_TYPES && ident.isNotEmpty()) {
        entity to ident
    } else {
        null
    }
}

private fun normalizeSpotifyIdentifier(kindWireValue: String, raw: String): String {
    val parsed = extractSpotifyEntityAndId(raw)
    return when {
        parsed != null -> "$SPOTIFY_URI_PREFIX${parsed.first}:${parsed.second}"
        raw.startsWith(SPOTIFY_URI_PREFIX) -> raw
        else -> formatBareSpotifyIdentifier(kindWireValue, raw)
    }
}

private fun formatBareSpotifyIdentifier(kindWireValue: String, raw: String): String {
    val defaultEntity =
        when (kindWireValue.trim().lowercase()) {
            ENTITY_ALBUM -> ENTITY_ALBUM
            "song" -> "track"
            ENTITY_ARTIST -> ENTITY_ARTIST
            "podcast_episode" -> "episode"
            "podcast" -> "show"
            else -> null
        }
    return if (defaultEntity != null && !raw.contains(':') && !raw.contains('/')) {
        "$SPOTIFY_URI_PREFIX$defaultEntity:$raw"
    } else {
        raw
    }
}

private fun normalizeImdbIdentifier(raw: String): String {
    val tokens = raw.split(Regex("[^A-Za-z0-9]+"))
    val match = tokens.firstOrNull { token ->
        token.length >= MIN_IMDB_TOKEN_LENGTH &&
            token.startsWith("tt", ignoreCase = true) &&
            token.substring(IMDB_PREFIX_LENGTH).all { it in '0'..'9' }
    }
    return if (match != null) "tt${match.substring(IMDB_PREFIX_LENGTH)}" else raw
}

private fun normalizeWikipediaIdentifier(raw: String): String {
    val wikiSlug =
        parseLenientUri(raw)
            ?.takeIf { it.host?.lowercase()?.endsWith("wikipedia.org") == true }
            ?.rawPath
            ?.takeIf { it.startsWith("/wiki/") }
            ?.removePrefix("/wiki/")
            ?.let(::decodePercentUtf8)
            ?.trim('/')
            ?.replace(' ', '_')
            ?.takeIf { it.isNotEmpty() }
    return wikiSlug ?: decodePercentUtf8(raw).replace(' ', '_')
}

internal fun extractArxivIdentifier(urlOrId: String): String? {
    val trimmed = urlOrId.trim()
    if (trimmed.isEmpty() || isHttpOrHttpsUrl(trimmed) && !isValidArxivHttpUrl(trimmed)) {
        return null
    }
    val strippedPrefix =
        trimmed
            .removePrefixIfPresent("oai:arXiv.org:")
            .removePrefixIfPresent("arXiv:")
            .removePrefixIfPresent("arxiv:")
    val afterMarker =
        when {
            ARXIV_ABS_MARKER in strippedPrefix -> strippedPrefix.substringAfter(ARXIV_ABS_MARKER)
            ARXIV_PDF_MARKER in strippedPrefix -> strippedPrefix.substringAfter(ARXIV_PDF_MARKER)
            else -> strippedPrefix
        }
    val withoutQuery = afterMarker.substringBefore('?').substringBefore('#')
    val cleaned = withoutQuery.removeSuffix(".pdf").trim('/')
    return cleaned.takeIf { it.isNotEmpty() }
}

private fun isValidArxivHttpUrl(raw: String): Boolean {
    val uri = parseLenientUri(raw) ?: return false
    val host = uri.host?.lowercase()
    val path = uri.rawPath.orEmpty()
    return host in ARXIV_HOSTS &&
        (path.startsWith(ARXIV_ABS_MARKER) || path.startsWith(ARXIV_PDF_MARKER))
}

private fun String.removePrefixIfPresent(prefix: String): String =
    if (startsWith(prefix)) removePrefix(prefix) else this

private fun parseLenientUri(raw: String): URI? = runCatching {
    URI(raw.trim().replace(" ", "%20"))
}
    .getOrNull()

internal fun buildYoutubeMusicAlbumUrl(rawIdOrUrl: String): String {
    val trimmed = rawIdOrUrl.trim()
    val http = sanitizeHttpUrl(trimmed)
    if (http != null) {
        return normalizeYoutubeMusicHttpUrl(http)
    }
    val normalizedId = sanitizeYoutubeMusicToken(trimmed)
    return if (normalizedId.startsWith(YT_MUSIC_BROWSE_PREFIX)) {
        "$YT_MUSIC_ORIGIN/browse/${encodeQueryValue(normalizedId)}"
    } else {
        "$YT_MUSIC_ORIGIN/playlist?list=${encodeQueryValue(normalizedId)}"
    }
}

private fun normalizeYoutubeMusicHttpUrl(httpUrl: String): String {
    val uri = runCatching { URI(httpUrl) }.getOrNull() ?: return httpUrl
    val browseSeg =
        uri.path
            ?.substringAfter("/browse/", missingDelimiterValue = "")
            ?.trim('/')
            ?.let(::sanitizeYoutubeMusicToken)
    val listParam = extractQueryParam(uri.rawQuery, "list")?.let(::sanitizeYoutubeMusicToken)
    return when {
        !browseSeg.isNullOrBlank() && browseSeg.startsWith(YT_MUSIC_BROWSE_PREFIX) ->
            "$YT_MUSIC_ORIGIN/browse/${encodeQueryValue(browseSeg)}"
        !listParam.isNullOrBlank() ->
            "$YT_MUSIC_ORIGIN/playlist?list=${encodeQueryValue(listParam)}"
        !browseSeg.isNullOrBlank() ->
            "$YT_MUSIC_ORIGIN/playlist?list=${encodeQueryValue(browseSeg)}"
        else -> httpUrl
    }
}

internal fun sanitizeYoutubeMusicToken(raw: String): String {
    val candidates =
        raw.trim()
            .split(YT_TOKEN_DELIMITER_REGEX)
            .map { stripYoutubePlaylistPrefix(it.trim()) }
            .filter { it.isNotEmpty() }
    val valid = candidates.firstOrNull { token ->
        token.matches(YT_VALID_TOKEN_REGEX) &&
            (token.startsWith(YT_MUSIC_BROWSE_PREFIX) ||
                token.startsWith(YT_OLAK_TOKEN_PREFIX) && token.length > YT_OLAK_PREFIX_LEN ||
                token.startsWith(YT_PL_TOKEN_PREFIX) && token.length > YT_PL_PREFIX_LEN)
    }
    return valid
        ?: stripYoutubePlaylistPrefix(raw.trim().substringBefore('&').substringBefore('?').trim())
}

internal fun stripYoutubePlaylistPrefix(id: String): String =
    if (id.startsWith("VLOLAK5uy_") || id.startsWith("VLPL")) id.removePrefix("VL") else id

internal fun externalIdentifierUrl(scheme: String, value: String): String? {
    val clean = value.trim()
    if (clean.isEmpty()) return null
    val http = sanitizeHttpUrl(clean)
    val normalizedScheme = normalizeIdentifierScheme(scheme)
    return when (normalizedScheme) {
        SCHEME_WIKIPEDIA ->
            http ?: "https://en.wikipedia.org/wiki/${clean.replace("#", "%23").replace("?", "%3F")}"
        SCHEME_SPOTIFY ->
            extractSpotifyEntityAndId(clean)?.let { (entity, id) ->
                "https://open.spotify.com/$entity/${encodeQueryValue(id)}"
            } ?: http ?: "https://open.spotify.com/album/${encodeQueryValue(clean)}"
        SCHEME_YOUTUBE_MUSIC -> buildYoutubeMusicAlbumUrl(clean)
        "imdb" -> http ?: "https://www.imdb.com/title/${encodePathSegmentOrKeep(clean)}/"
        else -> resolvePrefixOrHttpUrl(normalizedScheme, clean, http)
    }
}

private fun resolvePrefixOrHttpUrl(
    normalizedScheme: String,
    clean: String,
    http: String?,
): String? {
    val prefix = EXTERNAL_ID_URL_PREFIXES[normalizedScheme]
    return when {
        http != null -> http
        prefix != null -> "$prefix${encodePathSegmentOrKeep(clean)}"
        else -> null
    }
}

private fun encodePathSegmentOrKeep(raw: String): String =
    if ('/' in raw) raw else encodeQueryValue(raw)

/**
 * Strips trailing bracketed/parenthesized edition suffixes (`(Deluxe Edition)`, `[2019 Remaster]`)
 * using whole-word keyword matching while preserving `(Live)`, `(Cleaning)`, `(Subversion)`, and
 * `" - ..."` subtitles.
 */
internal fun stripAlbumEditionSuffix(rawTitle: String): String {
    var cur = rawTitle.trim()
    while (cur.isNotEmpty()) {
        val next = stripTrailingEditionGroupOnce(cur) ?: break
        cur = next
    }
    return cur
}

private fun stripTrailingEditionGroupOnce(cur: String): String? {
    val closeCh = cur.last()
    val openCh =
        when (closeCh) {
            ')' -> '('
            ']' -> '['
            else -> return null
        }
    val withoutClose = cur.substring(0, cur.length - 1)
    val openIdx = withoutClose.lastIndexOf(openCh)
    if (openIdx <= 0) return null
    val inner = withoutClose.substring(openIdx + 1)
    val hasNestedBracket = inner.any { it == '(' || it == ')' || it == '[' || it == ']' }
    val hasEditionWord =
        !hasNestedBracket &&
            inner.split(Regex("[^\\p{L}\\p{N}]+")).any { word ->
                word.isNotEmpty() && word.lowercase() in EDITION_KEYWORDS
            }
    val prefix = withoutClose.substring(0, openIdx).trimEnd()
    return if (hasEditionWord && prefix.isNotEmpty()) prefix else null
}

/** Selects a canonical deterministic UUID for a catalog entry across Rust and Kotlin. */
internal fun chooseDeterministicEntryIdRaw(
    explicitId: String?,
    kindWireValue: String,
    title: String,
    creators: List<String>,
    identifiers: List<Pair<String, String>>,
): String {
    val cleanExplicit = explicitId?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanExplicit != null) return cleanExplicit

    val preferredIdent = findPreferredIdentifierPair(identifiers)
    if (preferredIdent != null) {
        return deterministicCatalogEntryId(
            kindWireValue,
            preferredIdent.first,
            preferredIdent.second,
        )
    }

    val normalizedTitle =
        if (kindWireValue.equals(ENTITY_ALBUM, ignoreCase = true)) {
            stripAlbumEditionSuffix(title)
        } else {
            title.trim()
        }
    val primaryCreator = creators.map { it.trim() }.firstOrNull { it.isNotEmpty() }
    val titleKey =
        if (primaryCreator == null) {
            normalizedTitle.lowercase()
        } else {
            "${primaryCreator.lowercase()}\u0000${normalizedTitle.lowercase()}"
        }
    return deterministicTitleEntryId(kindWireValue, titleKey)
}

private fun findPreferredIdentifierPair(
    identifiers: List<Pair<String, String>>
): Pair<String, String>? {
    for (preferred in PREFERRED_IDENTIFIER_SCHEMES) {
        val match =
            identifiers
                .filter { it.first == preferred && it.second.trim().isNotEmpty() }
                .minByOrNull { it.second }
        if (match != null) return match
    }
    return identifiers
        .filter { it.first.trim().isNotEmpty() && it.second.trim().isNotEmpty() }
        .minWithOrNull(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
}

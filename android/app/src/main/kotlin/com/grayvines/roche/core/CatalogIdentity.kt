@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package com.grayvines.roche.core

import java.net.URI
import java.util.Locale

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
internal const val SCHEME_YOUTUBE = "youtube"

internal const val YT_MUSIC_ORIGIN = "https://music.youtube.com"
internal const val YT_MUSIC_BROWSE_PREFIX = "MPREb"

internal val WANT_TO_READ_BASE_SLUGS: Set<String> =
    setOf("read", "article", "articles", "paper", "papers")

private const val ROLE_LIST = "list"
private const val ROLE_WANT = "want"
private const val WANT_SLUG_PREFIX = "want:"
private const val DOI_PREFIX = "doi:"
private const val SCHEME_ARXIV = "arxiv"
private const val SCHEME_FEED_URL = "feed_url"
private const val ENTITY_ALBUM = "album"
private const val ENTITY_ARTIST = "artist"
private const val ENTITY_TRACK = "track"
private const val ENTITY_EPISODE = "episode"
private const val SPOTIFY_URI_PREFIX = "spotify:"
private const val SPOTIFY_PLAYLIST = "playlist"
private const val ARXIV_ABS_MARKER = "/abs/"
private const val ARXIV_PDF_MARKER = "/pdf/"
private const val YT_OLAK_TOKEN_PREFIX = "OLAK5uy_"
private const val YT_OLAK_PREFIX_LEN = 8
private const val YT_PL_TOKEN_PREFIX = "PL"
private const val YT_PL_PREFIX_LEN = 2

internal data class DerivedCollectionId(
    val slug: String,
    val collectionId: String,
)

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
        SCHEME_FEED_URL,
        "url",
    )

private val SPOTIFY_ENTITY_TYPES: Set<String> =
    setOf(ENTITY_ALBUM, ENTITY_TRACK, ENTITY_ARTIST, ENTITY_EPISODE, "show", SPOTIFY_PLAYLIST)

private val ARXIV_HOSTS: Set<String> =
    setOf("arxiv.org", "www.arxiv.org", "rss.arxiv.org", "export.arxiv.org")

private val HTTP_ONLY_EXTERNAL_SCHEMES: Set<String> =
    setOf(SCHEME_BANDCAMP, "rym", "url", SCHEME_FEED_URL)

private val DISALLOWED_URI_SCHEME_PREFIXES: List<String> =
    listOf("javascript:", "data:", "vbscript:", "file:", "ftp:")

private val NON_SLUG_REGEX = Regex("[^a-z0-9]+")

private val EXTERNAL_ID_URL_PREFIXES: Map<String, String> =
    mapOf(
        "tmdb_movie" to "https://www.themoviedb.org/movie/",
        "tmdb_tv" to "https://www.themoviedb.org/tv/",
        "letterboxd" to "https://letterboxd.com/film/",
        "isbn" to "https://openlibrary.org/isbn/",
        "openlibrary" to "https://openlibrary.org/works/",
        "goodreads" to "https://www.goodreads.com/book/show/",
        "steam" to "https://store.steampowered.com/app/",
        "igdb" to "https://www.igdb.com/games/",
        "discogs" to "https://www.discogs.com/release/",
        SCHEME_ARXIV to "https://arxiv.org/abs/",
        "wikidata" to "https://www.wikidata.org/wiki/",
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
        SCHEME_FEED_URL -> canonicalizeSyncUrl(trimmed)
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
            "song" -> ENTITY_TRACK
            ENTITY_ARTIST -> ENTITY_ARTIST
            "podcast_episode" -> ENTITY_EPISODE
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
    val valid = candidates.firstOrNull(::isValidYoutubeMusicToken)
    return valid
        ?: stripYoutubePlaylistPrefix(raw.trim().substringBefore('&').substringBefore('?').trim())
}

private fun isValidYoutubeMusicToken(token: String): Boolean {
    if (!token.matches(YT_VALID_TOKEN_REGEX)) return false
    val isOlak = token.startsWith(YT_OLAK_TOKEN_PREFIX) && token.length > YT_OLAK_PREFIX_LEN
    val isPl = token.startsWith(YT_PL_TOKEN_PREFIX) && token.length > YT_PL_PREFIX_LEN
    return token.startsWith(YT_MUSIC_BROWSE_PREFIX) || isOlak || isPl
}

internal fun stripYoutubePlaylistPrefix(id: String): String =
    if (id.startsWith("VLOLAK5uy_") || id.startsWith("VLPL")) id.removePrefix("VL") else id

internal fun slugifyTitle(raw: String): String =
    raw.trim().lowercase(Locale.ROOT).replace(NON_SLUG_REGEX, "_").trim('_')

internal fun deriveCollectionIdentity(
    title: String,
    roleWireValue: String,
): DerivedCollectionId? {
    val cleanTitle = title.trim()
    val normRole = roleWireValue.trim().lowercase(Locale.ROOT)
    val validRole = normRole == ROLE_LIST || normRole == ROLE_WANT
    val baseSlug =
        if (cleanTitle.isEmpty() || !validRole) {
            ""
        } else {
            val hasWantPrefix =
                normRole == ROLE_WANT && cleanTitle.startsWith(WANT_SLUG_PREFIX, ignoreCase = true)
            val slugSource =
                if (hasWantPrefix) cleanTitle.substring(WANT_SLUG_PREFIX.length) else cleanTitle
            slugifyTitle(slugSource)
        }
    if (baseSlug.isEmpty()) return null
    return if (normRole == ROLE_WANT) {
        val fullSlug =
            if (baseSlug in WANT_TO_READ_BASE_SLUGS) {
                WANT_TO_READ_SLUG
            } else {
                "$WANT_SLUG_PREFIX$baseSlug"
            }
        DerivedCollectionId(slug = fullSlug, collectionId = systemCollectionUuid(fullSlug))
    } else {
        DerivedCollectionId(
            slug = baseSlug,
            collectionId = systemCollectionUuid("custom:$baseSlug"),
        )
    }
}

internal fun externalIdentifierUrl(
    scheme: String,
    value: String,
    entryKind: String? = null,
): String? {
    val clean = value.trim()
    if (clean.isEmpty() || isDisallowedUriScheme(clean)) return null
    val http = sanitizeHttpUrl(clean)?.takeIf { it.substringAfter("://").isNotBlank() }
    val normalizedScheme = normalizeIdentifierScheme(scheme)
    return when (normalizedScheme) {
        SCHEME_WIKIPEDIA ->
            http ?: "https://en.wikipedia.org/wiki/${clean.replace("#", "%23").replace("?", "%3F")}"
        SCHEME_SPOTIFY -> resolveSpotifyExternalUrl(clean, http, entryKind)
        SCHEME_YOUTUBE_MUSIC -> buildYoutubeMusicAlbumUrl(clean)
        SCHEME_YOUTUBE -> resolveYoutubeExternalUrl(clean, http)
        "imdb" -> http ?: "https://www.imdb.com/title/${encodePathSegmentOrKeep(clean)}/"
        "doi" -> resolveDoiExternalUrl(clean, http)
        in HTTP_ONLY_EXTERNAL_SCHEMES -> http
        else -> resolvePrefixedExternalUrl(normalizedScheme, clean, http, entryKind)
    }
}

private fun isDisallowedUriScheme(raw: String): Boolean {
    val lower = raw.lowercase(Locale.ROOT)
    return DISALLOWED_URI_SCHEME_PREFIXES.any { lower.startsWith(it) }
}

private fun resolveSpotifyExternalUrl(
    clean: String,
    http: String?,
    entryKind: String?,
): String? {
    if (clean.startsWith(SPOTIFY_URI_PREFIX)) {
        val parsed = extractSpotifyFromColonUri(clean)
        return parsed?.let {
            "https://open.spotify.com/${it.first}/${encodeQueryValue(it.second)}"
        }
    }
    val webParsed = extractSpotifyFromWebUrl(clean)
    return when {
        webParsed != null ->
            "https://open.spotify.com/${webParsed.first}/${encodeQueryValue(webParsed.second)}"
        http != null -> http
        ':' in clean || '/' in clean || clean.any(Char::isWhitespace) -> null
        else ->
            "https://open.spotify.com/${spotifyDefaultEntity(entryKind)}/${encodeQueryValue(clean)}"
    }
}

private fun spotifyDefaultEntity(entryKind: String?): String =
    when (entryKind?.trim()?.lowercase(Locale.ROOT)) {
        "song" -> ENTITY_TRACK
        ENTITY_ARTIST -> ENTITY_ARTIST
        "podcast" -> "show"
        "podcast_episode" -> ENTITY_EPISODE
        "collection" -> SPOTIFY_PLAYLIST
        else -> ENTITY_ALBUM
    }

private fun resolveYoutubeExternalUrl(clean: String, http: String?): String =
    when {
        http != null -> http
        isYoutubeChannelId(clean) -> "https://www.youtube.com/channel/${encodeQueryValue(clean)}"
        else -> "https://www.youtube.com/watch?v=${encodeQueryValue(clean)}"
    }

private fun resolveDoiExternalUrl(clean: String, http: String?): String? {
    if (http != null) return http
    val stripped =
        if (clean.startsWith(DOI_PREFIX, ignoreCase = true)) {
            clean.substring(DOI_PREFIX.length).trim()
        } else {
            clean
        }
    return stripped
        .takeIf { it.isNotEmpty() }
        ?.let { "https://doi.org/${encodePathSegmentOrKeep(it)}" }
}

private fun resolvePrefixedExternalUrl(
    normalizedScheme: String,
    clean: String,
    http: String?,
    entryKind: String?,
): String? {
    val prefix =
        when (normalizedScheme) {
            "tmdb" -> tmdbPrefixForKind(entryKind)
            "musicbrainz" -> musicbrainzPrefixForKind(entryKind)
            else -> EXTERNAL_ID_URL_PREFIXES[normalizedScheme]
        } ?: return null
    return http ?: "$prefix${encodePathSegmentOrKeep(clean)}"
}

private fun tmdbPrefixForKind(entryKind: String?): String? =
    when (entryKind?.trim()?.lowercase(Locale.ROOT)) {
        "tv" -> "https://www.themoviedb.org/tv/"
        ENTITY_ARTIST -> "https://www.themoviedb.org/person/"
        "movie",
        null,
        "" -> "https://www.themoviedb.org/movie/"
        else -> null
    }

private fun musicbrainzPrefixForKind(entryKind: String?): String? =
    when (entryKind?.trim()?.lowercase(Locale.ROOT)) {
        ENTITY_ARTIST -> "https://musicbrainz.org/artist/"
        "song" -> "https://musicbrainz.org/recording/"
        ENTITY_ALBUM,
        null,
        "" -> "https://musicbrainz.org/release/"
        else -> null
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

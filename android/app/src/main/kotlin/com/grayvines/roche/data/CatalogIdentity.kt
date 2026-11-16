package com.grayvines.roche.data

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets

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

private const val SCHEME_SPOTIFY = "spotify"
private const val SCHEME_WIKIPEDIA = "wikipedia"
private const val SPOTIFY_URI_PREFIX = "spotify:"
private const val SPOTIFY_PLAYLIST = "playlist"
private const val HTTP_PREFIX = "http://"
private const val HTTPS_PREFIX = "https://"
private const val ARXIV_ABS_MARKER = "/abs/"
private const val ARXIV_PDF_MARKER = "/pdf/"

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
        "arxiv",
        SCHEME_WIKIPEDIA,
        "feed_url",
        "url",
    )

private val SPOTIFY_ENTITY_TYPES: Set<String> =
    setOf("album", "track", "artist", "episode", "show", SPOTIFY_PLAYLIST)

private val ARXIV_HOSTS: Set<String> = setOf("arxiv.org", "rss.arxiv.org", "export.arxiv.org")

private const val MIN_SPOTIFY_URI_PARTS = 2
private const val SPOTIFY_USER_PLAYLIST_PARTS = 4
private const val SPOTIFY_PLAYLIST_ID_INDEX = 3
private const val MIN_IMDB_TOKEN_LENGTH = 4
private const val IMDB_PREFIX_LENGTH = 2

internal fun normalizeIdentifierScheme(rawScheme: String): String =
    rawScheme.trim().lowercase().replace('-', '_')

internal fun normalizeIdentifierValue(
    kind: EntryKind,
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
        SCHEME_SPOTIFY -> normalizeSpotifyIdentifier(kind, trimmed)
        "imdb" -> normalizeImdbIdentifier(trimmed)
        SCHEME_WIKIPEDIA -> normalizeWikipediaIdentifier(trimmed)
        "arxiv" -> extractArxivIdentifier(trimmed) ?: trimmed
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

private fun normalizeSpotifyIdentifier(kind: EntryKind, raw: String): String {
    val parsed = extractSpotifyEntityAndId(raw)
    if (parsed != null) {
        return "$SPOTIFY_URI_PREFIX${parsed.first}:${parsed.second}"
    }
    if (raw.startsWith(SPOTIFY_URI_PREFIX)) {
        return raw
    }
    val defaultEntity =
        when (kind) {
            EntryKind.ALBUM -> "album"
            EntryKind.SONG -> "track"
            EntryKind.ARTIST -> "artist"
            EntryKind.PODCAST_EPISODE -> "episode"
            EntryKind.PODCAST -> "show"
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

internal fun decodePercentUtf8(input: String): String {
    if ('%' !in input) return input
    val rawBytes = input.toByteArray(StandardCharsets.UTF_8)
    val out = ByteArrayOutputStream(rawBytes.size)
    var i = 0
    while (i < rawBytes.size) {
        val hi =
            if (rawBytes[i] == '%'.code.toByte() && i + 2 < rawBytes.size) {
                Character.digit(rawBytes[i + 1].toInt().toChar(), HEX_RADIX)
            } else {
                -1
            }
        val lo = if (hi >= 0) Character.digit(rawBytes[i + 2].toInt().toChar(), HEX_RADIX) else -1
        if (hi >= 0 && lo >= 0) {
            out.write(hi shl HEX_NIBBLE_BITS or lo)
            i += PERCENT_TRIPLET_BYTES
        } else {
            out.write(rawBytes[i].toInt())
            i += 1
        }
    }
    return out.toByteArray().toString(StandardCharsets.UTF_8)
}

private const val PERCENT_TRIPLET_BYTES = 3
private const val HEX_NIBBLE_BITS = 4
private const val HEX_RADIX = 16

internal fun extractArxivIdentifier(urlOrId: String): String? {
    val trimmed = urlOrId.trim()
    if (trimmed.isEmpty()) return null
    val isHttp =
        trimmed.startsWith(HTTP_PREFIX, ignoreCase = true) ||
            trimmed.startsWith(HTTPS_PREFIX, ignoreCase = true)
    if (isHttp && !isValidArxivHttpUrl(trimmed)) {
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

internal fun externalIdentifierUrl(scheme: String, value: String): String? {
    val clean = value.trim()
    if (clean.isEmpty()) return null
    val isHttp = clean.startsWith(HTTP_PREFIX) || clean.startsWith(HTTPS_PREFIX)
    return when (normalizeIdentifierScheme(scheme)) {
        SCHEME_WIKIPEDIA ->
            if (isHttp) {
                clean
            } else {
                "https://en.wikipedia.org/wiki/${clean.replace("#", "%23").replace("?", "%3F")}"
            }
        SCHEME_SPOTIFY ->
            extractSpotifyEntityAndId(clean)?.let { (entity, id) ->
                "https://open.spotify.com/$entity/$id"
            } ?: if (isHttp) clean else "https://open.spotify.com/album/$clean"
        else -> clean.takeIf { isHttp }
    }
}

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
internal fun chooseDeterministicEntryId(
    explicitId: String?,
    kind: EntryKind,
    title: String,
    creators: List<String>,
    identifiers: List<EntryIdentifierDto>,
): String {
    val cleanExplicit = explicitId?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanExplicit != null) return cleanExplicit

    val preferredIdent = findPreferredIdentifier(identifiers)
    if (preferredIdent != null) {
        return deterministicCatalogEntryId(
            kind.wireValue,
            preferredIdent.scheme,
            preferredIdent.value,
        )
    }

    val normalizedTitle =
        if (kind == EntryKind.ALBUM) stripAlbumEditionSuffix(title) else title.trim()
    val primaryCreator = creators.map { it.trim() }.firstOrNull { it.isNotEmpty() }
    val titleKey =
        if (primaryCreator == null) {
            normalizedTitle.lowercase()
        } else {
            "${primaryCreator.lowercase()}\u0000${normalizedTitle.lowercase()}"
        }
    return deterministicTitleEntryId(kind.wireValue, titleKey)
}

private fun findPreferredIdentifier(identifiers: List<EntryIdentifierDto>): EntryIdentifierDto? {
    for (preferred in PREFERRED_IDENTIFIER_SCHEMES) {
        val match =
            identifiers
                .filter { it.scheme == preferred && it.value.trim().isNotEmpty() }
                .minByOrNull { it.value }
        if (match != null) return match
    }
    return identifiers
        .filter { it.scheme.trim().isNotEmpty() && it.value.trim().isNotEmpty() }
        .minWithOrNull(compareBy<EntryIdentifierDto> { it.scheme }.thenBy { it.value })
}

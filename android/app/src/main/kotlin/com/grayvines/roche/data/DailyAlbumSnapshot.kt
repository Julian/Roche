package com.grayvines.roche.data

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable

@Serializable
data class DailyAlbumSnapshot(
    val entryId: String = "",
    val name: String,
    val artist: String,
    val releaseDate: String = "",
    val imageUrl: String? = null,
    val thumbnailUrl: String? = null,
    val genres: List<String> = emptyList(),
    val albumNumber: Int = 0,
    val totalCount: Int = 0,
    val remainingCount: Int = 0,
    val youtubeMusicId: String? = null,
    val spotifyId: String? = null,
    val appleMusicId: String? = null,
    val tidalId: String? = null,
    val qobuzId: String? = null,
    val deezerId: String? = null,
    val amazonMusicId: String? = null,
    val wikipediaUrl: String? = null,
) {
    val youtubeMusicSearchUrl: String
        get() {
            val query = "$artist $name".trim().ifBlank { name }
            return "$YT_MUSIC_ORIGIN/search?q=${encodeQueryValue(query)}"
        }

    val youtubeMusicUrl: String
        get() {
            val cleanId = youtubeMusicId?.trim()?.takeIf { it.isNotBlank() }
            return if (cleanId == null) {
                youtubeMusicSearchUrl
            } else {
                buildYoutubeMusicAlbumUrl(cleanId)
            }
        }
}

internal val DailyAlbumSnapshot.hasYoutubePlaylistId: Boolean
    get() =
        !youtubeMusicId.isNullOrBlank() &&
            youtubeMusicUrl.startsWith("$YT_MUSIC_ORIGIN/playlist?list=")

internal fun DailyAlbumSnapshot.candidateStreamingUrls(): List<String> = buildList {
    if (!youtubeMusicId.isNullOrBlank()) {
        add(youtubeMusicUrl)
    }
    addSpotifyCandidateUrl(spotifyId)
    addDirectOrPrefixedUrl(appleMusicId, "https://music.apple.com/album/")
    addTidalCandidateUrls(tidalId)
    addDirectOrPrefixedUrl(qobuzId, "https://open.qobuz.com/album/")
    addDirectOrPrefixedUrl(deezerId, "https://www.deezer.com/album/")
    addDirectOrPrefixedUrl(amazonMusicId, "https://music.amazon.com/albums/")
}
    .distinct()

private fun MutableList<String>.addSpotifyCandidateUrl(rawSpotify: String?) {
    val raw = rawSpotify?.trim()?.takeIf(String::isNotEmpty) ?: return
    val parsed = extractSpotifyEntityAndId(raw)
    if (parsed != null) {
        add("https://open.spotify.com/${parsed.first}/${encodeQueryValue(parsed.second)}")
    } else {
        addDirectOrPrefixedUrl(raw, "https://open.spotify.com/album/")
    }
}

private fun MutableList<String>.addTidalCandidateUrls(rawTidal: String?) {
    val raw = rawTidal?.trim()?.takeIf(String::isNotEmpty) ?: return
    val http = sanitizeHttpUrl(raw)
    if (http != null) {
        add(http)
        val tail = http.substringAfter("/album/", missingDelimiterValue = "").substringBefore('?')
        if (tail.isNotEmpty()) {
            add("https://tidal.com/browse/album/${encodeQueryValue(tail)}")
            add("https://listen.tidal.com/album/${encodeQueryValue(tail)}")
        }
    } else {
        val encoded = encodeQueryValue(raw)
        add("https://tidal.com/browse/album/$encoded")
        add("https://listen.tidal.com/album/$encoded")
    }
}

private fun MutableList<String>.addDirectOrPrefixedUrl(rawValue: String?, prefix: String) {
    val clean = rawValue?.trim()?.takeIf(String::isNotEmpty) ?: return
    val http = sanitizeHttpUrl(clean)
    if (http != null) {
        add(http)
    } else {
        add("$prefix${encodeQueryValue(clean)}")
    }
}

internal fun buildYoutubeMusicAlbumUrl(rawIdOrUrl: String): String {
    val trimmed = rawIdOrUrl.trim()
    val http = sanitizeHttpUrl(trimmed)
    if (http != null) {
        return normalizeYoutubeMusicHttpUrl(http)
    }
    val normalizedId = stripYoutubePlaylistPrefix(trimmed)
    return if (normalizedId.startsWith(YT_MUSIC_BROWSE_PREFIX)) {
        "$YT_MUSIC_ORIGIN/browse/${encodeQueryValue(normalizedId)}"
    } else {
        "$YT_MUSIC_ORIGIN/playlist?list=${encodeQueryValue(normalizedId)}"
    }
}

private fun normalizeYoutubeMusicHttpUrl(httpUrl: String): String {
    val uri = runCatching { URI(httpUrl) }.getOrNull() ?: return httpUrl
    val listParam = extractQueryParam(uri.rawQuery, "list")?.let(::stripYoutubePlaylistPrefix)
    if (!listParam.isNullOrBlank()) {
        return "$YT_MUSIC_ORIGIN/playlist?list=${encodeQueryValue(listParam)}"
    }
    val browseSeg =
        uri.path
            ?.substringAfter("/browse/", missingDelimiterValue = "")
            ?.trim('/')
            ?.let(::stripYoutubePlaylistPrefix)
    return when {
        browseSeg.isNullOrBlank() -> httpUrl
        browseSeg.startsWith(YT_MUSIC_BROWSE_PREFIX) ->
            "$YT_MUSIC_ORIGIN/browse/${encodeQueryValue(browseSeg)}"
        else -> "$YT_MUSIC_ORIGIN/playlist?list=${encodeQueryValue(browseSeg)}"
    }
}

internal fun stripYoutubePlaylistPrefix(id: String): String =
    if (id.startsWith("VLOLAK5uy_") || id.startsWith("VLPL")) id.removePrefix("VL") else id

internal fun extractQueryParam(rawQuery: String?, key: String): String? {
    if (rawQuery.isNullOrBlank()) return null
    val prefix = "$key="
    return rawQuery
        .split('&')
        .firstOrNull { it.startsWith(prefix) }
        ?.removePrefix(prefix)
        ?.let(::decodePercentUtf8)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
}

internal fun sanitizeHttpUrl(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    val lower = trimmed.lowercase()
    return if (lower.startsWith("https://") || lower.startsWith("http://")) trimmed else null
}

internal fun encodeQueryValue(raw: String): String =
    URLEncoder.encode(raw, StandardCharsets.UTF_8.name())

private const val YT_MUSIC_ORIGIN = "https://music.youtube.com"
private const val YT_MUSIC_BROWSE_PREFIX = "MPREb"

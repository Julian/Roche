package com.grayvines.roche.data

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
            return when {
                cleanId == null -> youtubeMusicSearchUrl
                cleanId.startsWith(YT_MUSIC_BROWSE_PREFIX) ->
                    "$YT_MUSIC_ORIGIN/browse/${encodeQueryValue(cleanId)}"
                else -> "$YT_MUSIC_ORIGIN/playlist?list=${encodeQueryValue(cleanId)}"
            }
        }
}

internal fun DailyAlbumSnapshot.candidateStreamingUrls(): List<String> = buildList {
    if (!youtubeMusicId.isNullOrBlank()) {
        add(youtubeMusicUrl)
    }
    spotifyId?.trim()?.takeIf(String::isNotEmpty)?.let { id ->
        add("https://open.spotify.com/album/${encodeQueryValue(id)}")
    }
    appleMusicId?.trim()?.takeIf(String::isNotEmpty)?.let { id ->
        add("https://music.apple.com/album/${encodeQueryValue(id)}")
    }
    tidalId?.trim()?.takeIf(String::isNotEmpty)?.let { id ->
        add("https://tidal.com/browse/album/${encodeQueryValue(id)}")
    }
    qobuzId?.trim()?.takeIf(String::isNotEmpty)?.let { id ->
        add("https://open.qobuz.com/album/${encodeQueryValue(id)}")
    }
    deezerId?.trim()?.takeIf(String::isNotEmpty)?.let { id ->
        add("https://www.deezer.com/album/${encodeQueryValue(id)}")
    }
    amazonMusicId?.trim()?.takeIf(String::isNotEmpty)?.let { id ->
        add("https://music.amazon.com/albums/${encodeQueryValue(id)}")
    }
    add(youtubeMusicSearchUrl)
}
    .distinct()

internal fun sanitizeHttpUrl(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    val lower = trimmed.lowercase()
    return if (lower.startsWith("https://") || lower.startsWith("http://")) trimmed else null
}

internal fun encodeQueryValue(raw: String): String =
    URLEncoder.encode(raw, StandardCharsets.UTF_8.name())

private const val YT_MUSIC_ORIGIN = "https://music.youtube.com"
private const val YT_MUSIC_BROWSE_PREFIX = "MPREb"

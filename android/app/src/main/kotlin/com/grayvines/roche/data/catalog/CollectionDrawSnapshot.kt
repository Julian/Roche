package com.grayvines.roche.data.catalog

import com.grayvines.roche.core.SCHEME_AMAZON_MUSIC
import com.grayvines.roche.core.SCHEME_APPLE_MUSIC
import com.grayvines.roche.core.SCHEME_DEEZER
import com.grayvines.roche.core.SCHEME_QOBUZ
import com.grayvines.roche.core.SCHEME_SPOTIFY
import com.grayvines.roche.core.SCHEME_TIDAL
import com.grayvines.roche.core.YT_MUSIC_BROWSE_PREFIX
import com.grayvines.roche.core.YT_MUSIC_ORIGIN
import com.grayvines.roche.core.buildYoutubeMusicAlbumUrl
import com.grayvines.roche.core.encodeQueryValue
import com.grayvines.roche.core.externalIdentifierUrl
import com.grayvines.roche.core.sanitizeHttpUrl
import kotlinx.serialization.Serializable

@Serializable
data class CollectionDrawSnapshot(
    val collectionId: String = "",
    val collectionSlug: String = "",
    val collectionTitle: String = "",
    val entryId: String = "",
    val kind: EntryKind = EntryKind.ALBUM,
    val title: String,
    val creator: String = "",
    val releaseDate: String = "",
    val url: String? = null,
    val imageUrl: String? = null,
    val thumbnailUrl: String? = null,
    val tags: List<String> = emptyList(),
    val drawNumber: Int = 0,
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
    val isMusicKind: Boolean
        get() = kind == EntryKind.ALBUM || kind == EntryKind.SONG

    val youtubeMusicSearchUrl: String
        get() {
            val query = "$creator $title".trim().ifBlank { title }
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

    val primaryExternalUrl: String?
        get() =
            sanitizeHttpUrl(url)
                ?: sanitizeHttpUrl(wikipediaUrl)
                ?: candidateStreamingUrls().firstOrNull()
}

internal val CollectionDrawSnapshot.hasYoutubeAlbumBrowseId: Boolean
    get() =
        !youtubeMusicId.isNullOrBlank() &&
            youtubeMusicUrl.startsWith("$YT_MUSIC_ORIGIN/browse/$YT_MUSIC_BROWSE_PREFIX")

internal fun CollectionDrawSnapshot.candidateStreamingUrls(): List<String> = buildList {
    if (!youtubeMusicId.isNullOrBlank()) {
        add(youtubeMusicUrl)
    }
    spotifyId?.let { externalIdentifierUrl(SCHEME_SPOTIFY, it)?.let(::add) }
    appleMusicId?.let { externalIdentifierUrl(SCHEME_APPLE_MUSIC, it)?.let(::add) }
    addTidalCandidateUrls(tidalId)
    qobuzId?.let { externalIdentifierUrl(SCHEME_QOBUZ, it)?.let(::add) }
    deezerId?.let { externalIdentifierUrl(SCHEME_DEEZER, it)?.let(::add) }
    amazonMusicId?.let { externalIdentifierUrl(SCHEME_AMAZON_MUSIC, it)?.let(::add) }
}
    .distinct()

private fun MutableList<String>.addTidalCandidateUrls(rawTidal: String?) {
    val raw = rawTidal?.trim()?.takeIf(String::isNotEmpty) ?: return
    val primary = externalIdentifierUrl(SCHEME_TIDAL, raw) ?: return
    add(primary)
    val http = sanitizeHttpUrl(raw)
    if (http != null) {
        val tail = http.substringAfter("/album/", missingDelimiterValue = "").substringBefore('?')
        if (tail.isNotEmpty()) {
            val encoded = encodeQueryValue(tail)
            add("https://tidal.com/browse/album/$encoded")
            add("https://listen.tidal.com/album/$encoded")
        }
    } else {
        add("https://listen.tidal.com/album/${encodeQueryValue(raw)}")
    }
}

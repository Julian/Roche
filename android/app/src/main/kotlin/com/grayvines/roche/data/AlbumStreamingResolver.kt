@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data

import com.grayvines.roche.data.remote.HttpRequest
import com.grayvines.roche.data.remote.HttpTransport
import com.grayvines.roche.data.remote.rocheJson
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class AlbumStreamingResolver(private val transport: HttpTransport) {
    private val inFlightMutex = Mutex()
    private val inFlightByKey = HashMap<String, Deferred<Map<String, String>>>()

    suspend fun resolveStreamingIds(album: DailyAlbumSnapshot): Map<String, String> {
        val key = album.entryId.ifBlank { "${album.artist}|${album.name}|${album.spotifyId}" }
        val (deferred, isOwner) = acquireInFlight(key)
        if (!isOwner) {
            return deferred.await()
        }
        val result = runSuspendCatching { performResolution(album) }.getOrDefault(emptyMap())
        inFlightMutex.withLock { inFlightByKey.remove(key) }
        deferred.complete(result)
        return result
    }

    private suspend fun acquireInFlight(
        key: String
    ): Pair<CompletableDeferred<Map<String, String>>, Boolean> = inFlightMutex.withLock {
        val existing = inFlightByKey[key] as? CompletableDeferred<Map<String, String>>
        if (existing != null) {
            existing to false
        } else {
            val fresh = CompletableDeferred<Map<String, String>>()
            inFlightByKey[key] = fresh
            fresh to true
        }
    }

    private suspend fun performResolution(album: DailyAlbumSnapshot): Map<String, String> {
        val resolved = LinkedHashMap<String, String>()
        val seedUrl = album.candidateStreamingUrls().firstOrNull()
        if (seedUrl != null) {
            resolved.putAll(fetchOdesliPlatformIds(seedUrl))
        }
        val currentYt = resolved[SCHEME_YT_MUSIC] ?: album.youtubeMusicId
        if (!isPlaylistYoutubeId(currentYt)) {
            val upgradedFromBrowse =
                currentYt
                    ?.trim()
                    ?.takeIf { it.startsWith(YT_BROWSE_PREFIX) }
                    ?.let { fetchInnertubePlaylistId(it) }
            val resolvedYt = upgradedFromBrowse ?: searchInnertubeAlbumId(album)
            if (!resolvedYt.isNullOrBlank()) {
                resolved[SCHEME_YT_MUSIC] = resolvedYt
            }
        }
        return resolved
    }

    private suspend fun fetchOdesliPlatformIds(seedUrl: String): Map<String, String> {
        val endpoint = "$ODESLI_ENDPOINT?url=${encodeQueryValue(seedUrl)}"
        val response = runSuspendCatching {
            transport.execute(
                HttpRequest(
                    method = "GET",
                    url = endpoint,
                    headers = JSON_ACCEPT_HEADERS,
                    requirePublicIp = true,
                )
            )
        }
            .getOrNull()
        if (response == null || response.statusCode !in HTTP_OK_START..HTTP_OK_END) {
            return emptyMap()
        }
        return parseOdesliLinksPayload(response.body)
    }

    private suspend fun searchInnertubeAlbumId(album: DailyAlbumSnapshot): String? {
        val query = "${album.artist} ${album.name}".trim().ifBlank { album.name.trim() }
        if (query.isEmpty()) return null
        val payload = buildInnertubeSearchBody(query)
        val root = postInnertubeJson(INNERTUBE_SEARCH_URL, payload) ?: return null
        val candidates = ArrayList<InnertubeAlbumCandidate>()
        collectInnertubeCandidates(root, candidates)
        val best = selectBestAlbumCandidate(candidates, album) ?: return null
        return when {
            !best.playlistId.isNullOrBlank() -> stripYoutubePlaylistPrefix(best.playlistId)
            !best.browseId.isNullOrBlank() && best.browseId.startsWith(YT_BROWSE_PREFIX) ->
                fetchInnertubePlaylistId(best.browseId) ?: best.browseId
            !best.browseId.isNullOrBlank() -> stripYoutubePlaylistPrefix(best.browseId)
            else -> null
        }
    }

    private suspend fun fetchInnertubePlaylistId(browseId: String): String? {
        val payload = buildInnertubeBrowseBody(browseId)
        val root = postInnertubeJson(INNERTUBE_BROWSE_URL, payload) ?: return null
        return findFirstPlaylistId(root)
    }

    private suspend fun postInnertubeJson(url: String, body: String): JsonObject? {
        val response = runSuspendCatching {
            transport.execute(
                HttpRequest(
                    method = "POST",
                    url = url,
                    headers = INNERTUBE_POST_HEADERS,
                    body = body,
                    requirePublicIp = true,
                )
            )
        }
            .getOrNull()
        if (response == null || response.statusCode !in HTTP_OK_START..HTTP_OK_END) {
            return null
        }
        return runCatching { rocheJson.parseToJsonElement(response.body) as? JsonObject }
            .getOrNull()
    }
}

internal fun DailyAlbumSnapshot.withResolvedStreamingIds(
    resolved: Map<String, String>
): DailyAlbumSnapshot {
    if (resolved.isEmpty()) return this
    return copy(
        youtubeMusicId = resolved[SCHEME_YT_MUSIC] ?: youtubeMusicId,
        appleMusicId = appleMusicId ?: resolved[SCHEME_APPLE_MUSIC],
        tidalId = tidalId ?: resolved[SCHEME_TIDAL],
        qobuzId = qobuzId ?: resolved[SCHEME_QOBUZ],
        deezerId = deezerId ?: resolved[SCHEME_DEEZER],
        amazonMusicId = amazonMusicId ?: resolved[SCHEME_AMAZON_MUSIC],
    )
}

internal fun parseOdesliLinksPayload(rawJson: String): Map<String, String> {
    val root =
        runCatching { rocheJson.parseToJsonElement(rawJson) as? JsonObject }.getOrNull()
            ?: return emptyMap()
    val links = root["linksByPlatform"] as? JsonObject ?: return emptyMap()
    val entities = root["entitiesByUniqueId"] as? JsonObject
    val out = LinkedHashMap<String, String>()

    extractOdesliYoutubeId(links, entities)?.let { out[SCHEME_YT_MUSIC] = it }
    extractPlatformUrl(links, "appleMusic")?.let { out[SCHEME_APPLE_MUSIC] = it }
        ?: extractPlatformUrl(links, "itunes")?.let { out[SCHEME_APPLE_MUSIC] = it }
    extractPlatformUrl(links, SCHEME_TIDAL)?.let { out[SCHEME_TIDAL] = it }
    extractPlatformUrl(links, SCHEME_DEEZER)?.let { out[SCHEME_DEEZER] = it }
    extractPlatformUrl(links, "amazonMusic")?.let { out[SCHEME_AMAZON_MUSIC] = it }
    return out
}

private fun extractOdesliYoutubeId(
    links: JsonObject,
    entities: JsonObject?,
): String? {
    val ytMusicUrl = extractPlatformUrl(links, "youtubeMusic")
    val ytUrl = extractPlatformUrl(links, "youtube")
    val directPlaylist =
        extractYoutubeIdFromUrl(ytMusicUrl, requirePlaylist = true)
            ?: extractYoutubeIdFromUrl(ytUrl, requirePlaylist = true)
            ?: extractPlaylistFromEntities(entities)
    return directPlaylist ?: extractYoutubeIdFromUrl(ytMusicUrl, requirePlaylist = false)
}

private fun extractPlaylistFromEntities(entities: JsonObject?): String? {
    if (entities == null) return null
    return entities.values.firstNotNullOfOrNull { value ->
        val rawId = ((value as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.trim()
        rawId?.let(::stripYoutubePlaylistPrefix)?.takeIf(::isYoutubePlaylistToken)
    }
}

private fun extractYoutubeIdFromUrl(rawUrl: String?, requirePlaylist: Boolean): String? {
    val uri = rawUrl?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    val listParam = extractQueryParam(uri.rawQuery, "list")?.let(::stripYoutubePlaylistPrefix)
    if (!listParam.isNullOrBlank()) {
        return listParam
    }
    val browseId =
        uri.path
            ?.substringAfter("/browse/", missingDelimiterValue = "")
            ?.trim('/')
            ?.let(::stripYoutubePlaylistPrefix)
            .orEmpty()
    return when {
        browseId.isEmpty() -> null
        isYoutubePlaylistToken(browseId) -> browseId
        !requirePlaylist && browseId.startsWith(YT_BROWSE_PREFIX) -> browseId
        else -> null
    }
}

private fun extractPlatformUrl(links: JsonObject, key: String): String? =
    ((links[key] as? JsonObject)?.get("url") as? JsonPrimitive)
        ?.contentOrNull
        ?.let(::sanitizeHttpUrl)

internal data class InnertubeAlbumCandidate(
    val labelText: String,
    val playlistId: String?,
    val browseId: String?,
)

internal fun collectInnertubeCandidates(
    element: JsonElement,
    sink: MutableList<InnertubeAlbumCandidate>,
) {
    when (element) {
        is JsonObject -> {
            for (rendererKey in ALBUM_RENDERER_KEYS) {
                val renderer = element[rendererKey] as? JsonObject
                if (renderer != null) {
                    toAlbumCandidate(renderer)?.let(sink::add)
                }
            }
            for ((key, child) in element) {
                if (key !in ALBUM_RENDERER_KEYS) {
                    collectInnertubeCandidates(child, sink)
                }
            }
        }
        is JsonArray -> {
            element.forEach { collectInnertubeCandidates(it, sink) }
        }
        else -> {}
    }
}

private fun toAlbumCandidate(renderer: JsonObject): InnertubeAlbumCandidate? {
    val playlistId = findFirstPlaylistId(renderer)
    val browseId = findFirstAlbumBrowseId(renderer)
    if (playlistId == null && browseId == null) return null
    val labels = StringBuilder()
    collectRunsText(renderer, labels)
    return InnertubeAlbumCandidate(
        labelText = labels.toString(),
        playlistId = playlistId,
        browseId = browseId,
    )
}

internal fun selectBestAlbumCandidate(
    candidates: List<InnertubeAlbumCandidate>,
    album: DailyAlbumSnapshot,
): InnertubeAlbumCandidate? {
    if (candidates.isEmpty()) return null
    val normTitle = normalizeMatchTokens(album.name)
    val normArtist = normalizeMatchTokens(album.artist)
    return candidates.maxByOrNull { candidate ->
        scoreAlbumCandidate(candidate, normTitle, normArtist)
    }
}

private fun scoreAlbumCandidate(
    candidate: InnertubeAlbumCandidate,
    normTitle: String,
    normArtist: String,
): Int {
    val haystack = normalizeMatchTokens(candidate.labelText)
    var score = 0
    if (normTitle.isNotEmpty() && normTitle in haystack) score += TITLE_MATCH_SCORE
    if (normArtist.isNotEmpty() && normArtist in haystack) score += ARTIST_MATCH_SCORE
    if (!candidate.playlistId.isNullOrBlank()) score += PLAYLIST_BONUS_SCORE
    return score
}

private fun normalizeMatchTokens(raw: String): String =
    stripAlbumEditionSuffix(raw).lowercase().replace(NON_ALNUM_REGEX, " ").trim()

internal fun findFirstPlaylistId(element: JsonElement): String? =
    findFirstJsonToken(element, "playlistId", ::isYoutubePlaylistToken)

private fun findFirstAlbumBrowseId(element: JsonElement): String? =
    findFirstJsonToken(element, "browseId") { token ->
        token.startsWith(YT_BROWSE_PREFIX) || token.startsWith(YT_OLAK_PREFIX)
    }

private fun findFirstJsonToken(
    element: JsonElement,
    fieldName: String,
    predicate: (String) -> Boolean,
): String? =
    when (element) {
        is JsonObject -> {
            val direct =
                (element[fieldName] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.trim()
                    ?.let(::stripYoutubePlaylistPrefix)
                    ?.takeIf(predicate)
            direct
                ?: element.values.firstNotNullOfOrNull { child ->
                    findFirstJsonToken(child, fieldName, predicate)
                }
        }
        is JsonArray -> {
            element.firstNotNullOfOrNull { child ->
                findFirstJsonToken(child, fieldName, predicate)
            }
        }
        else -> {
            null
        }
    }

private fun collectRunsText(element: JsonElement, out: StringBuilder) {
    when (element) {
        is JsonObject -> {
            val runs = element["runs"] as? JsonArray
            runs?.forEach { item ->
                val text = ((item as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
                if (!text.isNullOrBlank()) {
                    if (out.isNotEmpty()) out.append(' ')
                    out.append(text)
                }
            }
            element.values.forEach { collectRunsText(it, out) }
        }
        is JsonArray -> {
            element.forEach { collectRunsText(it, out) }
        }
        else -> {}
    }
}

private fun isYoutubePlaylistToken(token: String): Boolean =
    token.startsWith(YT_OLAK_PREFIX) || token.startsWith("PL")

internal fun isPlaylistYoutubeId(rawId: String?): Boolean {
    val clean = rawId?.trim()?.takeIf(String::isNotEmpty) ?: return false
    return buildYoutubeMusicAlbumUrl(clean).startsWith("$YT_MUSIC_ORIGIN_URL/playlist?list=")
}

internal fun buildInnertubeSearchBody(query: String): String = buildJsonObject {
    putInnertubeContext()
    put("query", query)
    put("params", INNERTUBE_ALBUMS_FILTER_PARAMS)
}
    .toString()

internal fun buildInnertubeBrowseBody(browseId: String): String = buildJsonObject {
    putInnertubeContext()
    put("browseId", browseId)
}
    .toString()

private fun JsonObjectBuilder.putInnertubeContext() {
    putJsonObject("context") {
        putJsonObject("client") {
            put("clientName", "WEB_REMIX")
            put("clientVersion", "1.20250101.01.00")
            put("hl", "en")
            put("gl", "US")
        }
    }
}

private const val SCHEME_YT_MUSIC = "youtube_music"
private const val SCHEME_APPLE_MUSIC = "apple_music"
private const val SCHEME_TIDAL = "tidal"
private const val SCHEME_QOBUZ = "qobuz"
private const val SCHEME_DEEZER = "deezer"
private const val SCHEME_AMAZON_MUSIC = "amazon_music"
private const val YT_BROWSE_PREFIX = "MPREb"
private const val YT_OLAK_PREFIX = "OLAK5uy_"
private const val YT_MUSIC_ORIGIN_URL = "https://music.youtube.com"
private const val ODESLI_ENDPOINT = "https://api.song.link/v1-alpha.1/links"
private const val INNERTUBE_SEARCH_URL = "$YT_MUSIC_ORIGIN_URL/youtubei/v1/search?prettyPrint=false"
private const val INNERTUBE_BROWSE_URL = "$YT_MUSIC_ORIGIN_URL/youtubei/v1/browse?prettyPrint=false"
private const val INNERTUBE_ALBUMS_FILTER_PARAMS = "EgWKAQIgAWoKEAkQChADEAQQBQ=="
private const val HTTP_OK_START = 200
private const val HTTP_OK_END = 299
private const val TITLE_MATCH_SCORE = 10
private const val ARTIST_MATCH_SCORE = 6
private const val PLAYLIST_BONUS_SCORE = 2
private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")
private val ALBUM_RENDERER_KEYS =
    setOf("musicTwoRowItemRenderer", "musicCardShelfRenderer", "musicResponsiveListItemRenderer")
private const val MIME_APPLICATION_JSON = "application/json"
internal val JSON_ACCEPT_HEADERS = mapOf("Accept" to MIME_APPLICATION_JSON)
internal val INNERTUBE_POST_HEADERS =
    mapOf(
        "Content-Type" to MIME_APPLICATION_JSON,
        "Accept" to MIME_APPLICATION_JSON,
        "Origin" to YT_MUSIC_ORIGIN_URL,
    )

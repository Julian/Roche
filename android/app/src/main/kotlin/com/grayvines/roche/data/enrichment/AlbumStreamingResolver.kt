@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data.enrichment

import com.grayvines.roche.core.SCHEME_YOUTUBE
import com.grayvines.roche.core.buildYoutubeMusicAlbumUrl
import com.grayvines.roche.core.coreJson
import com.grayvines.roche.core.deterministicTitleEntryId
import com.grayvines.roche.core.encodeQueryValue
import com.grayvines.roche.core.externalIdentifierUrl
import com.grayvines.roche.core.extractQueryParam
import com.grayvines.roche.core.isAllowedHostForScheme
import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.core.sanitizeHttpUrl
import com.grayvines.roche.core.sanitizeYoutubeMusicToken
import com.grayvines.roche.core.stripAlbumEditionSuffix
import com.grayvines.roche.core.stripYoutubePlaylistPrefix
import com.grayvines.roche.core.validatedBrandedHttpsUrl
import com.grayvines.roche.data.catalog.CatalogStore
import com.grayvines.roche.data.catalog.CollectionDrawSnapshot
import com.grayvines.roche.data.catalog.attachEntryIdentifiers
import com.grayvines.roche.data.catalog.candidateStreamingUrls
import com.grayvines.roche.data.catalog.hasYoutubeAlbumBrowseId
import com.grayvines.roche.data.remote.HttpRequest
import com.grayvines.roche.data.remote.HttpTransport
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class AlbumStreamingResolver(
    private val transport: HttpTransport,
    private val backgroundScope: CoroutineScope? = null,
    private val catalogStore: CatalogStore? = null,
    private val onArtistIdsResolved: (suspend (String, Map<String, String>) -> Unit)? = null,
) {
    private val inFlightMutex = Mutex()
    private val inFlightByKey = HashMap<String, Deferred<Map<String, String>>>()
    private val completedArtistSchemes = HashSet<Pair<String, String>>()

    suspend fun resolveStreamingIds(
        album: CollectionDrawSnapshot,
        includeWikipedia: Boolean = false,
    ): Map<String, String> {
        val baseKey = album.entryId.ifBlank { "${album.creator}|${album.title}|${album.spotifyId}" }
        val key = "$baseKey|wiki=$includeWikipedia"
        val (deferred, isOwner) = acquireInFlight(key)
        if (!isOwner) {
            return deferred.await()
        }
        val result: Map<String, String>
        try {
            result =
                runSuspendCatching { performResolution(album, includeWikipedia) }
                    .getOrDefault(emptyMap())
            deferred.complete(result)
        } finally {
            try {
                withContext(NonCancellable) {
                    inFlightMutex.withLock { inFlightByKey.remove(key) }
                }
            } finally {
                deferred.complete(emptyMap())
            }
        }
        scheduleArtistEnrichment(album)
        return result
    }

    private suspend fun scheduleArtistEnrichment(album: CollectionDrawSnapshot) {
        if (catalogStore == null && onArtistIdsResolved == null) return
        if (backgroundScope != null) {
            backgroundScope.launch { enrichAlbumArtistBestEffort(album) }
        } else {
            enrichAlbumArtistBestEffort(album)
        }
    }

    private suspend fun enrichAlbumArtistBestEffort(album: CollectionDrawSnapshot) {
        val cleanArtist = album.creator.trim()
        if (cleanArtist.isEmpty() || cleanArtist.equals(VARIOUS_ARTISTS_NAME, ignoreCase = true)) {
            return
        }
        val plan = buildArtistEnrichmentPlan(cleanArtist, album.spotifyId) ?: return
        val artistIds =
            resolveArtistStreamingIds(
                artistName = cleanArtist,
                albumTitle = album.title,
                albumSpotifyId = album.spotifyId,
                needWikipedia = plan.needWikipedia,
                needSpotify = plan.needSpotify,
            )
        inFlightMutex.withLock {
            if (plan.needWikipedia) {
                completedArtistSchemes.add(plan.lowerArtist to SCHEME_WIKIPEDIA)
            }
            if (plan.needSpotify) {
                completedArtistSchemes.add(plan.lowerArtist to SCHEME_SPOTIFY)
            }
        }
        if (artistIds.isNotEmpty()) {
            runSuspendCatching {
                catalogStore?.attachEntryIdentifiers(plan.artistId, artistIds)
                onArtistIdsResolved?.invoke(plan.artistId, artistIds)
            }
        }
    }

    private suspend fun buildArtistEnrichmentPlan(
        cleanArtist: String,
        albumSpotifyId: String?,
    ): ArtistEnrichmentPlan? {
        val lowerArtist = cleanArtist.lowercase()
        val detId = deterministicTitleEntryId("artist", lowerArtist)
        val store = catalogStore
        val resolvedId = if (store != null) store.followMergedId(detId) ?: return null else detId
        val existingSchemes = store?.getEntryIdentifierSchemes(resolvedId).orEmpty()
        val (doneWiki, doneSpotify) =
            inFlightMutex.withLock {
                val wikiDone = lowerArtist to SCHEME_WIKIPEDIA in completedArtistSchemes
                val spDone = lowerArtist to SCHEME_SPOTIFY in completedArtistSchemes
                wikiDone to spDone
            }
        val needWiki = SCHEME_WIKIPEDIA !in existingSchemes && !doneWiki
        val needSpotify =
            SCHEME_SPOTIFY !in existingSchemes && !albumSpotifyId.isNullOrBlank() && !doneSpotify
        return if (needWiki || needSpotify) {
            ArtistEnrichmentPlan(resolvedId, lowerArtist, needWiki, needSpotify)
        } else {
            null
        }
    }

    @Suppress("LongParameterList")
    suspend fun resolveArtistStreamingIds(
        artistName: String,
        albumTitle: String = "",
        albumSpotifyId: String? = null,
        needWikipedia: Boolean = true,
        needSpotify: Boolean = true,
    ): Map<String, String> {
        val cleanArtist = artistName.trim()
        if (cleanArtist.isEmpty() || !needWikipedia && !needSpotify) {
            return emptyMap()
        }
        val key = "artist|${cleanArtist.lowercase()}|wiki=$needWikipedia|sp=$needSpotify"
        val (deferred, isOwner) = acquireInFlight(key)
        if (!isOwner) {
            return deferred.await()
        }
        return try {
            val result = runSuspendCatching {
                performArtistResolution(
                    artistName = cleanArtist,
                    albumTitle = albumTitle,
                    albumSpotifyId = albumSpotifyId,
                    needWikipedia = needWikipedia,
                    needSpotify = needSpotify,
                )
            }
                .getOrDefault(emptyMap())
            deferred.complete(result)
            result
        } finally {
            try {
                withContext(NonCancellable) {
                    inFlightMutex.withLock { inFlightByKey.remove(key) }
                }
            } finally {
                deferred.complete(emptyMap())
            }
        }
    }

    private suspend fun performArtistResolution(
        artistName: String,
        albumTitle: String,
        albumSpotifyId: String?,
        needWikipedia: Boolean,
        needSpotify: Boolean,
    ): Map<String, String> {
        val resolved = LinkedHashMap<String, String>()
        if (needWikipedia) {
            resolveArtistWikiSlug(transport, artistName, albumTitle)?.let {
                resolved[SCHEME_WIKIPEDIA] = it
            }
        }
        if (needSpotify && !albumSpotifyId.isNullOrBlank()) {
            resolveSpotifyArtistUri(transport, albumSpotifyId, artistName)?.let {
                resolved[SCHEME_SPOTIFY] = it
            }
        }
        return resolved
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

    private suspend fun performResolution(
        album: CollectionDrawSnapshot,
        includeWikipedia: Boolean,
    ): Map<String, String> {
        val resolved = LinkedHashMap<String, String>()
        if (!album.hasYoutubeAlbumBrowseId) {
            resolveStreamingPlatforms(album, resolved)
        }
        if (includeWikipedia && album.wikipediaUrl.isNullOrBlank()) {
            resolveAlbumWikipediaSlug(transport, album)?.let { resolved[SCHEME_WIKIPEDIA] = it }
        }
        return resolved
    }

    private suspend fun resolveStreamingPlatforms(
        album: CollectionDrawSnapshot,
        resolved: MutableMap<String, String>,
    ) {
        val seedUrl = odesliSeedUrl(album)
        if (seedUrl != null && shouldQueryOdesli(album)) {
            resolved.putAll(fetchOdesliPlatformIds(seedUrl))
        }
        val currentYt = resolved[SCHEME_YT_MUSIC] ?: album.youtubeMusicId
        if (!isAlbumBrowseYoutubeId(currentYt)) {
            val resolvedBrowse = searchInnertubeAlbumId(album)
            if (!resolvedBrowse.isNullOrBlank()) {
                resolved[SCHEME_YT_MUSIC] = resolvedBrowse
            }
        }
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

    private suspend fun searchInnertubeAlbumId(album: CollectionDrawSnapshot): String? {
        val query = "${album.creator} ${album.title}".trim().ifBlank { album.title.trim() }
        if (query.isEmpty()) return null
        val payload = buildInnertubeSearchBody(query)
        val root = postInnertubeJson(INNERTUBE_SEARCH_URL, payload) ?: return null
        val candidates = ArrayList<InnertubeAlbumCandidate>()
        collectInnertubeCandidates(root, candidates)
        val best = selectBestAlbumCandidate(candidates, album) ?: return null
        return when {
            !best.browseId.isNullOrBlank() -> sanitizeYoutubeMusicToken(best.browseId)
            !best.playlistId.isNullOrBlank() -> sanitizeYoutubeMusicToken(best.playlistId)
            else -> null
        }
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
        return runCatching { coreJson.parseToJsonElement(response.body) as? JsonObject }.getOrNull()
    }
}

internal fun CollectionDrawSnapshot.withResolvedStreamingIds(
    resolved: Map<String, String>
): CollectionDrawSnapshot {
    if (resolved.isEmpty()) return this
    return copy(
        youtubeMusicId = resolved[SCHEME_YT_MUSIC] ?: youtubeMusicId,
        appleMusicId = appleMusicId ?: resolved[SCHEME_APPLE_MUSIC],
        tidalId = tidalId ?: resolved[SCHEME_TIDAL],
        qobuzId = qobuzId ?: resolved[SCHEME_QOBUZ],
        deezerId = deezerId ?: resolved[SCHEME_DEEZER],
        amazonMusicId = amazonMusicId ?: resolved[SCHEME_AMAZON_MUSIC],
        wikipediaUrl =
            wikipediaUrl
                ?: resolved[SCHEME_WIKIPEDIA]
                    ?.let { externalIdentifierUrl(SCHEME_WIKIPEDIA, it) }
                    ?.let(::sanitizeHttpUrl),
    )
}

internal fun parseOdesliLinksPayload(rawJson: String): Map<String, String> {
    val root =
        runCatching { coreJson.parseToJsonElement(rawJson) as? JsonObject }.getOrNull()
            ?: return emptyMap()
    val links = root["linksByPlatform"] as? JsonObject ?: return emptyMap()
    val entities = root["entitiesByUniqueId"] as? JsonObject
    val out = LinkedHashMap<String, String>()

    extractOdesliYoutubeId(links, entities)?.let { out[SCHEME_YT_MUSIC] = it }
    extractPlatformUrl(links, "appleMusic", SCHEME_APPLE_MUSIC)?.let {
        out[SCHEME_APPLE_MUSIC] = it
    }
        ?: extractPlatformUrl(links, "itunes", SCHEME_APPLE_MUSIC)?.let {
            out[SCHEME_APPLE_MUSIC] = it
        }
    extractPlatformUrl(links, SCHEME_TIDAL, SCHEME_TIDAL)?.let { out[SCHEME_TIDAL] = it }
    extractPlatformUrl(links, SCHEME_DEEZER, SCHEME_DEEZER)?.let { out[SCHEME_DEEZER] = it }
    extractPlatformUrl(links, "amazonMusic", SCHEME_AMAZON_MUSIC)?.let {
        out[SCHEME_AMAZON_MUSIC] = it
    }
    return out
}

internal fun odesliSeedUrl(album: CollectionDrawSnapshot): String? {
    val candidates = album.candidateStreamingUrls()
    return candidates.firstOrNull { !it.contains("youtube.com") } ?: candidates.firstOrNull()
}

internal fun shouldQueryOdesli(album: CollectionDrawSnapshot): Boolean =
    album.appleMusicId.isNullOrBlank() ||
        album.tidalId.isNullOrBlank() ||
        album.deezerId.isNullOrBlank() ||
        album.amazonMusicId.isNullOrBlank()

private fun extractOdesliYoutubeId(
    links: JsonObject,
    entities: JsonObject?,
): String? {
    val ytMusicUrl = extractPlatformUrl(links, "youtubeMusic", SCHEME_YT_MUSIC)
    val ytUrl = extractPlatformUrl(links, "youtube", SCHEME_YOUTUBE)
    val directBrowse =
        extractYoutubeBrowseIdFromUrl(ytMusicUrl) ?: extractBrowseIdFromEntities(entities)
    return directBrowse
        ?: extractYoutubeIdFromUrl(ytMusicUrl)
        ?: extractYoutubeIdFromUrl(ytUrl)
        ?: extractPlaylistFromEntities(entities)
}

private fun extractBrowseIdFromEntities(entities: JsonObject?): String? {
    if (entities == null) return null
    return entities.values.firstNotNullOfOrNull { value ->
        val rawId = ((value as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.trim()
        rawId?.let(::sanitizeYoutubeMusicToken)?.takeIf { it.startsWith(YT_BROWSE_PREFIX) }
    }
}

private fun extractPlaylistFromEntities(entities: JsonObject?): String? {
    if (entities == null) return null
    return entities.values.firstNotNullOfOrNull { value ->
        val rawId = ((value as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.trim()
        rawId?.let(::sanitizeYoutubeMusicToken)?.takeIf(::isYoutubePlaylistToken)
    }
}

private fun extractYoutubeBrowseIdFromUrl(rawUrl: String?): String? {
    val uri = rawUrl?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    val browseId =
        uri.path
            ?.substringAfter("/browse/", missingDelimiterValue = "")
            ?.trim('/')
            ?.let(::sanitizeYoutubeMusicToken)
            .orEmpty()
    return browseId.takeIf { it.startsWith(YT_BROWSE_PREFIX) }
}

private fun extractYoutubeIdFromUrl(rawUrl: String?): String? {
    val uri = rawUrl?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    val browseId =
        uri.path
            ?.substringAfter("/browse/", missingDelimiterValue = "")
            ?.trim('/')
            ?.let(::sanitizeYoutubeMusicToken)
            .orEmpty()
    if (browseId.startsWith(YT_BROWSE_PREFIX) || isYoutubePlaylistToken(browseId)) {
        return browseId
    }
    val listParam = extractQueryParam(uri.rawQuery, "list")?.let(::sanitizeYoutubeMusicToken)
    return listParam?.takeIf { it.isNotEmpty() }
}

private fun extractPlatformUrl(
    links: JsonObject,
    key: String,
    allowedScheme: String,
): String? =
    ((links[key] as? JsonObject)?.get("url") as? JsonPrimitive)
        ?.contentOrNull
        ?.let(::sanitizeHttpUrl)
        ?.let { url ->
            validatedBrandedHttpsUrl(url) { host -> isAllowedHostForScheme(allowedScheme, host) }
        }

internal data class InnertubeAlbumCandidate(
    val primaryTitle: String,
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
    if (containsPageType(renderer, AUDIOBOOK_PAGE_TYPE)) return null
    val playlistId = findFirstPlaylistId(renderer)
    val browseId = findFirstAlbumBrowseId(renderer)
    if (playlistId == null && browseId == null) return null
    val labels = StringBuilder()
    collectRunsText(renderer, labels)
    return InnertubeAlbumCandidate(
        primaryTitle = findFirstRunText(renderer).orEmpty(),
        labelText = labels.toString(),
        playlistId = playlistId,
        browseId = browseId,
    )
}

internal fun selectBestAlbumCandidate(
    candidates: List<InnertubeAlbumCandidate>,
    album: CollectionDrawSnapshot,
): InnertubeAlbumCandidate? {
    if (candidates.isEmpty()) return null
    val normTitle = normalizeMatchTokens(album.title)
    val normArtist = normalizeMatchTokens(album.creator)
    val releaseYear = album.releaseDate.trim().take(YEAR_TOKEN_LEN)
    val scored = candidates.map { candidate ->
        candidate to scoreAlbumCandidate(candidate, normTitle, normArtist, releaseYear)
    }
    val best = scored.maxByOrNull { it.second } ?: return null
    val minRequired = if (normTitle.isNotEmpty()) TITLE_MATCH_SCORE else 1
    return best.first.takeIf { best.second >= minRequired }
}

private fun scoreAlbumCandidate(
    candidate: InnertubeAlbumCandidate,
    normTitle: String,
    normArtist: String,
    releaseYear: String,
): Int {
    val haystack = normalizeMatchTokens(candidate.labelText)
    val normPrimary = normalizeMatchTokens(candidate.primaryTitle)
    var score = 0
    if (normTitle.isNotEmpty()) {
        if (normPrimary == normTitle) {
            score += EXACT_TITLE_MATCH_SCORE
        } else if (normTitle in haystack) {
            score += TITLE_MATCH_SCORE
        }
    }
    if (normArtist.isNotEmpty() && normArtist in haystack) score += ARTIST_MATCH_SCORE
    if (releaseYear.length == YEAR_TOKEN_LEN && releaseYear in haystack) {
        score += YEAR_MATCH_SCORE
    }
    if (!candidate.browseId.isNullOrBlank()) score += BROWSE_ID_BONUS_SCORE
    return score
}

internal fun normalizeMatchTokens(raw: String): String =
    stripAlbumEditionSuffix(raw).lowercase().replace(NON_ALNUM_REGEX, " ").trim()

internal fun findFirstPlaylistId(element: JsonElement): String? =
    findFirstJsonToken(element, "playlistId", ::isYoutubePlaylistToken)

private fun findFirstAlbumBrowseId(element: JsonElement): String? =
    findFirstJsonToken(element, "browseId") { token -> token.startsWith(YT_BROWSE_PREFIX) }

private fun containsPageType(element: JsonElement, targetPageType: String): Boolean =
    findFirstJsonToken(element, "pageType") { it == targetPageType } != null

private fun findFirstRunText(element: JsonElement): String? =
    when (element) {
        is JsonObject -> {
            val runs = element["runs"] as? JsonArray
            val fromRuns = runs?.firstNotNullOfOrNull { item ->
                ((item as? JsonObject)?.get("text") as? JsonPrimitive)
                    ?.contentOrNull
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            }
            fromRuns ?: element.values.firstNotNullOfOrNull(::findFirstRunText)
        }
        is JsonArray -> {
            element.firstNotNullOfOrNull(::findFirstRunText)
        }
        else -> {
            null
        }
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

internal fun isAlbumBrowseYoutubeId(rawId: String?): Boolean {
    val clean = rawId?.trim()?.takeIf(String::isNotEmpty) ?: return false
    return buildYoutubeMusicAlbumUrl(clean)
        .startsWith("$YT_MUSIC_ORIGIN_URL/browse/$YT_BROWSE_PREFIX")
}

internal fun buildInnertubeSearchBody(query: String): String = buildJsonObject {
    putInnertubeContext()
    put("query", query)
    put("params", INNERTUBE_ALBUMS_FILTER_PARAMS)
}
    .toString()

private fun JsonObjectBuilder.putInnertubeContext() {
    putJsonObject("context") {
        putJsonObject("client") {
            put("clientName", "WEB_REMIX")
            put("clientVersion", INNERTUBE_WEB_REMIX_VERSION)
            put("hl", "en")
            put("gl", "US")
        }
    }
}

private data class ArtistEnrichmentPlan(
    val artistId: String,
    val lowerArtist: String,
    val needWikipedia: Boolean,
    val needSpotify: Boolean,
)

private const val SCHEME_YT_MUSIC = "youtube_music"
private const val SCHEME_APPLE_MUSIC = "apple_music"
private const val SCHEME_TIDAL = "tidal"
private const val SCHEME_QOBUZ = "qobuz"
private const val SCHEME_DEEZER = "deezer"
private const val SCHEME_AMAZON_MUSIC = "amazon_music"
private const val SCHEME_WIKIPEDIA = "wikipedia"
private const val SCHEME_SPOTIFY = "spotify"
private const val YT_BROWSE_PREFIX = "MPREb"
private const val YT_OLAK_PREFIX = "OLAK5uy_"
private const val YT_MUSIC_ORIGIN_URL = "https://music.youtube.com"
private const val ODESLI_ENDPOINT = "https://api.song.link/v1-alpha.1/links"
private const val INNERTUBE_SEARCH_URL = "$YT_MUSIC_ORIGIN_URL/youtubei/v1/search?prettyPrint=false"
private const val INNERTUBE_ALBUMS_FILTER_PARAMS = "EgWKAQIYAWoKEAkQChAFEAMQBA=="
private const val INNERTUBE_WEB_REMIX_VERSION = "1.20260707.12.00"
private const val AUDIOBOOK_PAGE_TYPE = "MUSIC_PAGE_TYPE_AUDIOBOOK"
private const val HTTP_OK_START = 200
private const val HTTP_OK_END = 299
private const val EXACT_TITLE_MATCH_SCORE = 20
private const val TITLE_MATCH_SCORE = 10
private const val ARTIST_MATCH_SCORE = 8
private const val YEAR_MATCH_SCORE = 4
private const val BROWSE_ID_BONUS_SCORE = 4
private const val YEAR_TOKEN_LEN = 4
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

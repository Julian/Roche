package com.grayvines.roche.data.remote

import com.grayvines.roche.data.runSuspendCatching
import com.grayvines.roche.data.settings.tailscaleDiscoveryUrls
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

@Suppress("TooManyFunctions")
class RocheApiClient(
    private val vpnCheck: VpnTransportCheck,
    private val transport: HttpTransport,
) {
    constructor(
        transport: HttpTransport = HttpUrlConnectionTransport()
    ) : this(
        vpnCheck = VpnTransportCheck { true },
        transport = transport,
    )

    private suspend fun executeServerRequest(
        serverUrl: String,
        request: HttpRequest,
    ): HttpResponse {
        PublicUrlValidator.ensureSafeServerEndpoint(serverUrl, vpnCheck::isActive)
        return transport.execute(request)
    }

    private suspend fun serverJson(
        serverUrl: String,
        method: String,
        path: String,
        token: String,
        body: String? = null,
    ): String {
        val base = serverUrl.trimEnd('/')
        val response =
            executeServerRequest(
                serverUrl = base,
                request =
                    HttpRequest(
                        method = method,
                        url = "$base$path",
                        headers = authHeaders(token),
                        body = body,
                    ),
            )
        ensureSuccess(response)
        return response.body
    }

    internal suspend fun probeServerHealth(candidateUrl: String): DiscoveredServerDto? =
        runSuspendCatching {
            val base = candidateUrl.trim().trimEnd('/')
            if (base.isBlank()) return null
            val response =
                executeServerRequest(
                    serverUrl = base,
                    request = HttpRequest(method = METHOD_GET, url = "$base/health"),
                )
            if (response.statusCode !in HTTP_OK_START..HTTP_OK_END) return null
            parseHealthDiscoveryJson(raw = response.body, probedBaseUrl = base)
        }
        .getOrNull()

    suspend fun discoverServer(
        tailnetDomains: List<String> = emptyList(),
        extraCandidates: List<String> = emptyList(),
    ): DiscoveredServerDto? {
        val candidates =
            (extraCandidates + tailscaleDiscoveryUrls(tailnetDomains = tailnetDomains))
                .map { it.trim().trimEnd('/') }
                .filter { it.isNotBlank() }
                .distinct()
        for (candidate in candidates) {
            val discovered = probeServerHealth(candidate)
            if (discovered != null) return discovered
        }
        return null
    }

    suspend fun pairDevice(serverUrl: String, code: String, deviceName: String): String {
        val body =
            serverJson(
                serverUrl = serverUrl,
                method = METHOD_POST,
                path = "/auth/pair",
                token = "",
                body = buildPairDevicePayload(code = code, deviceName = deviceName),
            )
        return extractTokenFromPairResponse(body)
    }

    suspend fun fetchFeeds(serverUrl: String, token: String): List<RemoteFeedDto> =
        parseFeedsJsonArray(serverJson(serverUrl, METHOD_GET, FEEDS_PATH, token))

    internal suspend fun createFeed(
        serverUrl: String,
        token: String,
        url: String,
        title: String? = null,
    ): RemoteFeedDto =
        parseFeedJson(
            serverJson(
                serverUrl = serverUrl,
                method = METHOD_POST,
                path = FEEDS_PATH,
                token = token,
                body = buildCreateFeedPayload(url = url, title = title),
            )
        )

    suspend fun refreshFeed(
        serverUrl: String,
        token: String,
        feedId: String,
    ): RemoteFeedDto =
        parseFeedJson(
            serverJson(
                serverUrl = serverUrl,
                method = METHOD_POST,
                path = "${feedPath(feedId)}/refresh",
                token = token,
            )
        )

    suspend fun deleteFeed(serverUrl: String, token: String, feedId: String) {
        serverJson(
            serverUrl = serverUrl,
            method = METHOD_DELETE,
            path = feedPath(feedId),
            token = token,
        )
    }

    internal suspend fun updateFeedTitle(
        serverUrl: String,
        bearerToken: String,
        feedId: String,
        title: String,
    ): RemoteFeedDto =
        updateFeed(serverUrl = serverUrl, token = bearerToken, feedId = feedId, title = title)

    internal suspend fun updateFeed(
        serverUrl: String,
        token: String,
        feedId: String,
        title: String? = null,
        pollInterval: Int? = null,
        isActive: Boolean? = null,
    ): RemoteFeedDto =
        parseFeedJson(
            serverJson(
                serverUrl = serverUrl,
                method = METHOD_PATCH,
                path = feedPath(feedId),
                token = token,
                body =
                    buildUpdateFeedPayload(
                        title = title,
                        pollInterval = pollInterval,
                        isActive = isActive,
                    ),
            )
        )

    suspend fun fetchItems(serverUrl: String, token: String): List<RemoteItemDto> {
        val baseUrl = serverUrl.trimEnd('/')
        PublicUrlValidator.ensureSafeServerEndpoint(baseUrl, vpnCheck::isActive)
        val collected = LinkedHashMap<String, RemoteItemDto>()
        for (page in 1..MAX_SYNC_PAGES) {
            val path =
                if (page == 1) {
                    "/api/v1/items?per_page=$ITEMS_PER_PAGE"
                } else {
                    "/api/v1/items?per_page=$ITEMS_PER_PAGE&page=$page"
                }
            val pageItems = parseItemsEnvelopeJson(serverJson(baseUrl, METHOD_GET, path, token))
            val beforeSize = collected.size
            pageItems.forEach { item ->
                if (item.id.isNotBlank() && item.id !in collected) {
                    collected[item.id] = item
                }
            }
            if (pageItems.size < ITEMS_PER_PAGE || collected.size == beforeSize) break
        }
        return collected.values.toList()
    }

    internal suspend fun fetchItemDetail(
        serverUrl: String,
        token: String,
        itemId: String,
    ): RemoteItemDto = parseItemJson(serverJson(serverUrl, METHOD_GET, itemPath(itemId), token))

    internal suspend fun fetchItemMedia(
        serverUrl: String,
        token: String,
        itemId: String,
    ): List<RemoteMediaDto> =
        parseMediaJsonArray(
            serverJson(
                serverUrl = serverUrl,
                method = METHOD_GET,
                path = "${itemPath(itemId)}/media",
                token = token,
            )
        )

    internal suspend fun fetchMediaBytes(
        serverUrl: String,
        token: String,
        mediaId: String,
    ): ByteArray? = runSuspendCatching {
        val encodedId = encodePathSegment(mediaId)
        val response =
            executeServerRequest(
                serverUrl = serverUrl,
                request =
                    HttpRequest(
                        method = METHOD_GET,
                        url = "${serverUrl.trimEnd('/')}/api/v1/media/$encodedId",
                        headers = authHeaders(token),
                        expectBinary = true,
                    ),
            )
        if (response.statusCode !in HTTP_OK_START..HTTP_OK_END) return null
        val bytes = response.bodyBytes ?: response.body.toByteArray(Charsets.UTF_8)
        bytes.takeIf { it.size in 1..MAX_MEDIA_BYTES }
    }
        .getOrNull()

    internal suspend fun streamMediaToFile(
        serverUrl: String,
        token: String,
        mediaId: String,
        targetFile: File,
        maxBytes: Long = MAX_MEDIA_BYTES.toLong(),
    ): Boolean = runSuspendCatching {
        PublicUrlValidator.ensureSafeServerEndpoint(serverUrl, vpnCheck::isActive)
        val mediaUrl = "${serverUrl.trimEnd('/')}/api/v1/media/${encodePathSegment(mediaId)}"
        val validScheme =
            mediaUrl.startsWith(HTTP_SCHEME_HTTPS, ignoreCase = true) ||
                mediaUrl.startsWith(HTTP_SCHEME_HTTP, ignoreCase = true)
        val headers = authHeaders(token)
        when {
            !validScheme -> false
            transport !is HttpUrlConnectionTransport ->
                streamTransportResponseToFile(transport, mediaUrl, targetFile, maxBytes, headers)
            else ->
                withContext(Dispatchers.IO) {
                    streamUrlConnectionToFile(
                        initialUrl = mediaUrl,
                        targetFile = targetFile,
                        maxBytes = maxBytes,
                        initialHeaders = headers,
                        requirePublicIp = false,
                        vpnCheck = vpnCheck,
                        job = coroutineContext[Job],
                    )
                }
        }
    }
        .getOrDefault(false)

    internal suspend fun fetchExternalImageBytes(imageUrl: String): ByteArray? =
        runSuspendCatching {
            val trimmed = imageUrl.trim()
            PublicUrlValidator.ensurePublicUrlSyntax(trimmed)
            val response =
                transport.execute(
                    HttpRequest(
                        method = METHOD_GET,
                        url = trimmed,
                        headers =
                            mapOf(
                                HEADER_USER_AGENT to EXTERNAL_IMAGE_USER_AGENT,
                                HEADER_ACCEPT to EXTERNAL_IMAGE_ACCEPT,
                            ),
                        expectBinary = true,
                        requirePublicIp = true,
                    )
                )
            if (response.statusCode !in HTTP_OK_START..HTTP_OK_END) return null
            val bytes = response.bodyBytes ?: response.body.toByteArray(Charsets.UTF_8)
            bytes.takeIf { it.size in 1..MAX_MEDIA_BYTES }
        }
        .getOrNull()

    internal suspend fun streamExternalBinaryToFile(
        fileUrl: String,
        targetFile: File,
        maxBytes: Long = MAX_AUDIO_ENCLOSURE_BYTES,
    ): Boolean = runSuspendCatching {
        val trimmed = fileUrl.trim()
        PublicUrlValidator.ensurePublicUrlSyntax(trimmed)
        if (transport !is HttpUrlConnectionTransport) {
            streamTransportResponseToFile(
                transport = transport,
                trimmedUrl = trimmed,
                targetFile = targetFile,
                maxBytes = maxBytes,
                headers = mapOf(HEADER_USER_AGENT to EXTERNAL_IMAGE_USER_AGENT),
                requirePublicIp = true,
            )
        } else {
            withContext(Dispatchers.IO) {
                streamUrlConnectionToFile(
                    initialUrl = trimmed,
                    targetFile = targetFile,
                    maxBytes = maxBytes,
                    initialHeaders = mapOf(HEADER_USER_AGENT to EXTERNAL_IMAGE_USER_AGENT),
                    requirePublicIp = true,
                    job = coroutineContext[Job],
                )
            }
        }
    }
        .getOrDefault(false)

    internal suspend fun fetchExternalFeedXml(feedUrl: String): Result<String> =
        runSuspendCatching {
            val trimmed = feedUrl.trim()
            PublicUrlValidator.ensurePublicUrlSyntax(trimmed)
            val response =
                transport.execute(
                    HttpRequest(
                        method = METHOD_GET,
                        url = trimmed,
                        headers =
                            mapOf(
                                HEADER_USER_AGENT to EXTERNAL_IMAGE_USER_AGENT,
                                HEADER_ACCEPT to EXTERNAL_FEED_ACCEPT,
                            ),
                        requirePublicIp = true,
                    )
                )
            ensureSuccess(response)
            response.body
        }

    internal suspend fun syncPeer(
        serverUrl: String,
        token: String,
        request: PeerSyncRequestDto,
    ): PeerSyncResponseDto? {
        val response =
            executeServerRequest(
                serverUrl = serverUrl,
                request =
                    HttpRequest(
                        method = METHOD_POST,
                        url = "${serverUrl.trimEnd('/')}/api/v1/sync/peer",
                        headers = authHeaders(token),
                        body = buildPeerSyncRequestPayload(request),
                    ),
            )
        if (
            response.statusCode == HTTP_NOT_FOUND_CODE ||
                response.statusCode == HTTP_METHOD_NOT_ALLOWED_CODE
        ) {
            return null
        }
        ensureSuccess(response)
        return parsePeerSyncResponseJson(response.body)
    }

    suspend fun saveItemUrl(
        serverUrl: String,
        token: String,
        url: String,
        title: String?,
        tags: List<String>,
    ): RemoteItemDto =
        parseItemJson(
            serverJson(
                serverUrl = serverUrl,
                method = METHOD_POST,
                path = "/api/v1/items/save",
                token = token,
                body = buildSaveItemPayload(url = url, title = title, tags = tags),
            )
        )

    suspend fun deleteItem(serverUrl: String, token: String, itemId: String) {
        serverJson(
            serverUrl = serverUrl,
            method = METHOD_DELETE,
            path = itemPath(itemId),
            token = token,
        )
    }

    internal suspend fun updateItem(
        serverUrl: String,
        token: String,
        itemId: String,
        status: String? = null,
        isFavorite: Boolean? = null,
        readProgress: Double? = null,
        readAt: String? = null,
    ): RemoteItemDto =
        parseItemJson(
            serverJson(
                serverUrl = serverUrl,
                method = METHOD_PATCH,
                path = itemPath(itemId),
                token = token,
                body =
                    buildUpdateItemPayload(
                        status = status,
                        isFavorite = isFavorite,
                        readProgress = readProgress,
                        readAt = readAt,
                    ),
            )
        )

    suspend fun fetchTags(serverUrl: String, token: String): List<RemoteTagDto> =
        parseTagsJsonArray(serverJson(serverUrl, METHOD_GET, "/api/v1/tags", token))

    internal suspend fun addTagToItem(
        serverUrl: String,
        token: String,
        itemId: String,
        tagName: String,
    ): List<RemoteTagDto> =
        parseTagsJsonArray(
            serverJson(
                serverUrl = serverUrl,
                method = METHOD_POST,
                path = "${itemPath(itemId)}/tags",
                token = token,
                body = buildAddItemTagPayload(tagName),
            )
        )

    internal suspend fun removeTagFromItem(
        serverUrl: String,
        token: String,
        itemId: String,
        tagId: String,
    ) {
        serverJson(
            serverUrl = serverUrl,
            method = METHOD_DELETE,
            path = "${itemPath(itemId)}/tags/${encodePathSegment(tagId)}",
            token = token,
        )
    }

    internal suspend fun archiveBatch(
        serverUrl: String,
        token: String,
        itemIds: List<String> = emptyList(),
        feedId: String? = null,
        allUnread: Boolean = false,
    ): Int =
        parseArchivedCountJson(
            serverJson(
                serverUrl = serverUrl,
                method = METHOD_POST,
                path = "/api/v1/items/archive-batch",
                token = token,
                body =
                    buildArchiveBatchPayload(
                        itemIds = itemIds,
                        feedId = feedId,
                        allUnread = allUnread,
                    ),
            )
        )

    internal suspend fun importOpml(
        serverUrl: String,
        token: String,
        opmlXml: String,
    ): Int =
        parseImportedCountJson(
            serverJson(
                serverUrl = serverUrl,
                method = METHOD_POST,
                path = "/api/v1/import/opml",
                token = token,
                body = buildImportOpmlPayload(opmlXml),
            )
        )

    internal suspend fun exportOpml(serverUrl: String, token: String): String =
        serverJson(serverUrl, METHOD_GET, "/api/v1/export/feeds", token)

    private fun feedPath(feedId: String): String = "$FEEDS_PATH/${encodePathSegment(feedId)}"

    private fun itemPath(itemId: String): String = "$ITEMS_PATH/${encodePathSegment(itemId)}"

    private companion object {
        private const val FEEDS_PATH = "/api/v1/feeds"
        private const val ITEMS_PATH = "/api/v1/items"
        private const val METHOD_GET = "GET"
        private const val METHOD_POST = "POST"
        private const val METHOD_PATCH = "PATCH"
        private const val METHOD_DELETE = "DELETE"
        private const val ITEMS_PER_PAGE = 200
        private const val MAX_SYNC_PAGES = 25
        private const val EXTERNAL_IMAGE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
        private const val EXTERNAL_IMAGE_ACCEPT =
            "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
        private const val EXTERNAL_FEED_ACCEPT =
            "application/rss+xml, application/atom+xml, application/xml, text/xml, */*;q=0.8"
        private const val HTTP_NOT_FOUND_CODE = 404
        private const val HTTP_METHOD_NOT_ALLOWED_CODE = 405
    }
}

package com.grayvines.roche.data.remote

import com.grayvines.roche.data.runSuspendCatching
import com.grayvines.roche.data.settings.tailscaleDiscoveryUrls
import java.io.File

class RocheApiClient(
    private val vpnCheck: VpnTransportCheck,
    private val transport: HttpTransport,
) {
    constructor(
        transport: HttpTransport
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
        if (!validScheme) {
            false
        } else {
            transport.streamToFile(
                request =
                    HttpRequest(
                        method = METHOD_GET,
                        url = mediaUrl,
                        headers = authHeaders(token),
                        expectBinary = true,
                        requirePublicIp = false,
                    ),
                targetFile = targetFile,
                maxBytes = maxBytes,
            )
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
        transport.streamToFile(
            request =
                HttpRequest(
                    method = METHOD_GET,
                    url = trimmed,
                    headers = mapOf(HEADER_USER_AGENT to EXTERNAL_IMAGE_USER_AGENT),
                    expectBinary = true,
                    requirePublicIp = true,
                ),
            targetFile = targetFile,
            maxBytes = maxBytes,
        )
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
            ensureExternalSuccess(response)
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
        private const val METHOD_DELETE = "DELETE"
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

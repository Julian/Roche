@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data

import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.data.local.deleteFeedPreservingHistory
import com.grayvines.roche.data.local.updateFeedActiveGraph
import com.grayvines.roche.data.local.updateFeedTitleGraph
import com.grayvines.roche.data.local.upsertFeed
import com.grayvines.roche.data.remote.ProblemDetailsException
import com.grayvines.roche.data.remote.RemoteFeedDto
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.data.settings.SettingsRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.first

private const val HTTP_NOT_FOUND = 404

internal suspend fun refreshFeedOnlineOrOnDevice(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    feedId: String,
    onSyncWithServer: suspend () -> Result<Int>,
): String {
    val currentSettings = settings.settings.first()
    val existingFeed = dao.getFeedById(feedId)
    val existingTitle = existingFeed?.title
    val hasServer =
        currentSettings.serverUrl.isNotBlank() && currentSettings.bearerToken.isNotBlank()
    val remoteRefreshed =
        if (hasServer && (existingFeed == null || isSyncablePublicUrl(existingFeed.url))) {
            runSuspendCatching {
                apiClient.refreshFeed(
                    serverUrl = currentSettings.serverUrl,
                    token = currentSettings.bearerToken,
                    feedId = feedId,
                )
            }
                .getOrNull()
        } else {
            null
        }
    return if (remoteRefreshed != null) {
        if (remoteRefreshed.id.isNotBlank()) {
            dao.upsertFeed(toRemoteFeedEntity(remoteRefreshed, existingFeed), markDirty = false)
        }
        onSyncWithServer()
        remoteRefreshed.title.ifBlank { existingTitle ?: feedId }
    } else {
        val localFeed =
            existingFeed
                ?: error(
                    if (hasServer) "Feed not found: $feedId" else "Configure Server URL in Settings"
                )
        LocalFeedPoller.pollFeed(
                dao = dao,
                apiClient = apiClient,
                feed = localFeed,
                localPeerId = currentSettings.peerId,
            )
            .getOrThrow()
        if (hasServer) {
            onSyncWithServer()
        }
        dao.getFeedById(localFeed.id)?.title?.ifBlank { existingTitle ?: feedId }
            ?: existingTitle
            ?: feedId
    }
}

@Suppress("LongParameterList")
internal suspend fun subscribeFeedBestEffort(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    url: String,
    titleOverride: String?,
    prefetchedBatch: ParsedFeedBatch? = null,
    discoveredSiteTitle: String? = null,
): String {
    val cleanTitleOverride = titleOverride?.trim()?.takeIf { it.isNotBlank() }
    val canonicalUrl = canonicalizeFeedUrl(url)
    val fallbackTitle =
        cleanTitleOverride
            ?: prefetchedBatch?.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: discoveredSiteTitle?.trim()?.takeIf { it.isNotEmpty() }
            ?: extractHostTitle(url)
    val currentSettings = settings.settings.first()
    val nodeId = currentSettings.peerId.ifBlank { DEFAULT_LOCAL_HLC_NODE }
    dao.deleteTombstone("feed", feedNaturalKey(canonicalUrl))

    val existingLocal =
        dao.getFeedByUrl(canonicalUrl) ?: dao.getFeedById(deterministicFeedId(canonicalUrl))
    if (existingLocal != null && !existingLocal.isActive) {
        val syncable = isSyncablePublicUrl(existingLocal.url)
        dao.updateFeedActiveGraph(
            feedId = existingLocal.id,
            isActive = true,
            markDirty = syncable,
            nodeId = nodeId,
        )
        if (cleanTitleOverride != null && cleanTitleOverride != existingLocal.title) {
            dao.updateFeedTitleGraph(
                feedId = existingLocal.id,
                cleanTitle = cleanTitleOverride,
                markDirty = syncable,
                nodeId = nodeId,
            )
        }
        return cleanTitleOverride ?: existingLocal.title
    }

    val spec =
        FeedCreationSpec(
            canonicalUrl = canonicalUrl,
            rawUrl = url,
            fallbackTitle = fallbackTitle,
            cleanTitleOverride = cleanTitleOverride,
        )
    val remoteFeed = tryCreateRemoteFeed(apiClient, currentSettings, spec)
    val isPublic = isSyncablePublicUrl(canonicalUrl)
    val entity = buildSubscribedFeedRow(remoteFeed, existingLocal, spec)
    val hlc = nextLocalHlc(dao, nodeId)
    dao.upsertFeed(
        feed = entity,
        markDirty = remoteFeed == null && isPublic,
        hlcOverride = hlc,
    )
    if (remoteFeed == null || prefetchedBatch != null) {
        LocalFeedPoller.pollFeed(
            dao = dao,
            apiClient = apiClient,
            feed = entity,
            localPeerId = currentSettings.peerId,
            prefetchedBatch = prefetchedBatch,
        )
    }
    return dao.getFeedById(entity.id)?.title ?: entity.title
}

private data class FeedCreationSpec(
    val canonicalUrl: String,
    val rawUrl: String,
    val fallbackTitle: String,
    val cleanTitleOverride: String?,
)

private fun buildSubscribedFeedRow(
    remoteFeed: RemoteFeedDto?,
    existingLocal: FeedRow?,
    spec: FeedCreationSpec,
): FeedRow {
    if (remoteFeed != null && remoteFeed.id.isNotBlank()) {
        return toRemoteFeedEntity(
            remoteFeed.copy(
                title = remoteFeed.title.ifBlank { spec.fallbackTitle },
                url = remoteFeed.url.ifBlank { spec.canonicalUrl },
            ),
            existingLocal,
        )
    }
    val nowIso = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
    return existingLocal?.copy(
        title = spec.cleanTitleOverride ?: existingLocal.title,
        isActive = true,
    )
        ?: FeedRow(
            id = deterministicFeedId(spec.canonicalUrl),
            title = spec.fallbackTitle,
            url = spec.canonicalUrl,
            siteUrl = null,
            feedType = inferFeedTypeWireValue(spec.rawUrl),
            lastPolled = nowIso,
        )
}

private suspend fun tryCreateRemoteFeed(
    apiClient: RocheApiClient,
    currentSettings: Settings,
    spec: FeedCreationSpec,
): RemoteFeedDto? = runSuspendCatching {
    if (
        isSyncablePublicUrl(spec.canonicalUrl) &&
            currentSettings.serverUrl.isNotBlank() &&
            currentSettings.bearerToken.isNotBlank()
    ) {
        apiClient.createFeed(
            serverUrl = currentSettings.serverUrl,
            token = currentSettings.bearerToken,
            url = spec.rawUrl,
            title = spec.cleanTitleOverride,
        )
    } else {
        null
    }
}
    .getOrNull()

internal suspend fun deleteFeedFromServerOrLocal(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    offlineCacheManager: OfflineCacheManager,
    feedId: String,
): String {
    val existing = dao.getFeedById(feedId)
    val nowIso = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
    val isLocalOnly = existing != null && !isSyncablePublicUrl(existing.url)
    val currentSettings = settings.settings.first()
    val nodeId = currentSettings.peerId.ifBlank { DEFAULT_LOCAL_HLC_NODE }
    if (
        !isLocalOnly &&
            currentSettings.serverUrl.isNotBlank() &&
            currentSettings.bearerToken.isNotBlank()
    ) {
        runSuspendCatching {
            apiClient.deleteFeed(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
                feedId = feedId,
            )
        }
            .onFailure { error ->
                val problem = error as? ProblemDetailsException
                if (problem != null && problem.statusCode != HTTP_NOT_FOUND) throw error
            }
    }
    val evictedIds = ArrayList<String>()
    dao.executeInTransaction {
        if (existing != null) {
            val hlc = nextLocalHlc(dao, nodeId)
            dao.upsertTombstone(
                TombstoneEntity(
                    entityType = "feed",
                    naturalKey = feedNaturalKey(existing.url),
                    deletedAt = nowIso,
                    hlc = hlc,
                    isLocalDirty = !isLocalOnly,
                )
            )
        }
        evictedIds.addAll(
            dao.deleteFeedPreservingHistory(
                feedId = feedId,
                recordTombstones = !isLocalOnly,
                nodeId = nodeId,
            )
        )
    }
    for (itemId in evictedIds) {
        if (dao.getEntryContentById(itemId) == null) {
            offlineCacheManager.evictCachedItemFiles(itemId)
        }
    }
    return existing?.title ?: feedId
}

internal fun buildLocalOpmlXml(feeds: List<FeedRow>): String = buildString {
    appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    appendLine("""<opml version="2.0">""")
    appendLine("  <head>")
    appendLine("    <title>Roche Feeds Export</title>")
    appendLine("  </head>")
    appendLine("  <body>")
    for (feed in feeds) {
        val titleAttr = escapeXmlAttr(feed.title)
        val xmlUrlAttr = escapeXmlAttr(feed.url)
        val htmlUrlPart =
            feed.siteUrl?.takeIf { it.isNotBlank() }?.let { """ htmlUrl="${escapeXmlAttr(it)}"""" }
                ?: ""
        appendLine(
            """    <outline type="rss" text="$titleAttr" title="$titleAttr" xmlUrl="$xmlUrlAttr"$htmlUrlPart/>"""
        )
    }
    appendLine("  </body>")
    appendLine("</opml>")
}

private fun escapeXmlAttr(raw: String): String =
    raw.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

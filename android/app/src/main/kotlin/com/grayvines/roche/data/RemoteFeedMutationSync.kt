@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data

import com.grayvines.roche.data.local.FeedEntity
import com.grayvines.roche.data.local.PendingEntityKind
import com.grayvines.roche.data.local.PendingMutationEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.data.remote.ProblemDetailsException
import com.grayvines.roche.data.remote.RemoteFeedDto
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.data.settings.SettingsRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.first

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
            dao.upsertFeed(toRemoteFeedEntity(remoteRefreshed, existingFeed))
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

internal suspend fun subscribeFeedBestEffort(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    url: String,
    titleOverride: String?,
): String {
    val cleanTitleOverride = titleOverride?.trim()?.takeIf { it.isNotBlank() }
    val canonicalUrl = canonicalizeFeedUrl(url)
    val fallbackTitle = cleanTitleOverride ?: extractHostTitle(url)
    val currentSettings = settings.settings.first()
    dao.deleteTombstone("feed", feedNaturalKey(canonicalUrl))

    val existingLocal =
        dao.getFeedByUrl(canonicalUrl) ?: dao.getFeedById(deterministicFeedId(canonicalUrl))
    if (existingLocal != null && !existingLocal.isActive) {
        return restoreArchivedLocalFeed(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            existingLocal = existingLocal,
            cleanTitleOverride = cleanTitleOverride,
        )
    }

    val spec =
        FeedCreationSpec(
            canonicalUrl = canonicalUrl,
            rawUrl = url,
            fallbackTitle = fallbackTitle,
            cleanTitleOverride = cleanTitleOverride,
        )
    val remoteFeed = tryCreateRemoteFeed(apiClient, currentSettings, spec)
    val entity =
        if (remoteFeed != null && remoteFeed.id.isNotBlank()) {
            toRemoteFeedEntity(
                remoteFeed.copy(
                    title = remoteFeed.title.ifBlank { fallbackTitle },
                    url = remoteFeed.url.ifBlank { canonicalUrl },
                ),
                existingLocal,
            )
        } else {
            createPendingLocalFeed(dao, existingLocal, spec)
        }
    dao.upsertFeed(entity)
    if (remoteFeed == null) {
        LocalFeedPoller.pollFeed(
            dao = dao,
            apiClient = apiClient,
            feed = entity,
            localPeerId = currentSettings.peerId,
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

private suspend fun createPendingLocalFeed(
    dao: RocheDao,
    existingLocal: FeedEntity?,
    spec: FeedCreationSpec,
): FeedEntity {
    val nowIso = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
    val localFeed =
        existingLocal?.copy(
            title = spec.cleanTitleOverride ?: existingLocal.title,
            isActive = true,
        )
            ?: FeedEntity(
                id = deterministicFeedId(spec.canonicalUrl),
                title = spec.fallbackTitle,
                url = spec.canonicalUrl,
                siteUrl = null,
                feedType = inferFeedTypeWireValue(spec.rawUrl),
                lastPolled = nowIso,
            )
    dao.upsertPendingMutation(
        PendingMutationEntity.localFeed(
            entityId = localFeed.id,
            titleOverride = spec.cleanTitleOverride,
        )
    )
    return localFeed
}

private suspend fun restoreArchivedLocalFeed(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    existingLocal: FeedEntity,
    cleanTitleOverride: String?,
): String {
    val restored =
        existingLocal.copy(
            title = cleanTitleOverride ?: existingLocal.title,
            isActive = true,
        )
    dao.upsertFeed(restored)
    syncFeedArchiveMutation(
        dao = dao,
        apiClient = apiClient,
        settings = settings,
        feedId = restored.id,
        isActive = true,
        titleOverride = cleanTitleOverride,
    )
    return restored.title
}

@Suppress("LongParameterList")
internal suspend fun syncFeedArchiveMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    feedId: String,
    isActive: Boolean,
    titleOverride: String? = null,
) {
    val existingFeed = dao.getFeedById(feedId)
    val existingMut = dao.getPendingMutation(feedId)
    if (
        existingMut?.kind == PendingEntityKind.LOCAL_FEED ||
            existingFeed != null && !isSyncablePublicUrl(existingFeed.url)
    ) {
        if (titleOverride != null && existingMut != null) {
            dao.upsertPendingMutation(existingMut.copy(titleOverride = titleOverride))
        }
        return
    }
    val effectiveTitle = titleOverride ?: existingMut?.titleOverride
    val currentSettings = settings.settings.first()
    val pushed =
        if (currentSettings.serverUrl.isNotBlank() && currentSettings.bearerToken.isNotBlank()) {
            runSuspendCatching {
                    apiClient.updateFeed(
                        serverUrl = currentSettings.serverUrl,
                        token = currentSettings.bearerToken,
                        feedId = feedId,
                        title = effectiveTitle,
                        isActive = isActive,
                    )
                    true
                }
                .getOrDefault(false)
        } else {
            false
        }
    if (pushed) {
        dao.deletePendingMutation(feedId)
    } else {
        dao.upsertPendingMutation(
            PendingMutationEntity(
                entityId = feedId,
                titleOverride = effectiveTitle,
                dirtyStatus =
                    if (isActive) FEED_ACTIVE_STATUS_MUTATION else FEED_ARCHIVED_STATUS_MUTATION,
            )
        )
    }
}

internal suspend fun syncFeedRenameMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    feedId: String,
    cleanTitle: String,
) {
    val existingFeed = dao.getFeedById(feedId)
    val existingMut = dao.getPendingMutation(feedId)
    val isLocalOnly =
        existingMut?.kind == PendingEntityKind.LOCAL_FEED ||
            existingFeed != null && !isSyncablePublicUrl(existingFeed.url)
    if (isLocalOnly) {
        if (existingMut != null) {
            dao.upsertPendingMutation(existingMut.copy(titleOverride = cleanTitle))
        }
        return
    }
    val currentSettings = settings.settings.first()
    val pushed =
        if (currentSettings.serverUrl.isNotBlank() && currentSettings.bearerToken.isNotBlank()) {
            runSuspendCatching {
                    apiClient.updateFeedTitle(
                        serverUrl = currentSettings.serverUrl,
                        bearerToken = currentSettings.bearerToken,
                        feedId = feedId,
                        title = cleanTitle,
                    )
                    true
                }
                .getOrDefault(false)
        } else {
            false
        }
    recordFeedRenameOutcome(
        dao = dao,
        feedId = feedId,
        cleanTitle = cleanTitle,
        existingMut = existingMut,
        pushed = pushed,
    )
}

private suspend fun recordFeedRenameOutcome(
    dao: RocheDao,
    feedId: String,
    cleanTitle: String,
    existingMut: PendingMutationEntity?,
    pushed: Boolean,
) {
    if (pushed) {
        if (existingMut?.dirtyFeedIsActive != null) {
            dao.upsertPendingMutation(existingMut.copy(titleOverride = null))
        } else {
            dao.deletePendingMutation(feedId)
        }
    } else {
        dao.upsertPendingMutation(
            PendingMutationEntity(
                entityId = feedId,
                titleOverride = cleanTitle,
                dirtyStatus =
                    if (existingMut?.dirtyFeedIsActive != null) {
                        existingMut.dirtyStatus
                    } else {
                        FEED_RENAME_STATUS_MUTATION
                    },
            )
        )
    }
}

internal suspend fun deleteFeedFromServerOrLocal(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    offlineCacheManager: OfflineCacheManager,
    feedId: String,
): String {
    val existing = dao.getFeedById(feedId)
    val nowIso = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
    val isLocalOnly =
        dao.getPendingMutation(feedId)?.kind == PendingEntityKind.LOCAL_FEED ||
            existing != null && !isSyncablePublicUrl(existing.url)
    val currentSettings = settings.settings.first()
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
    if (existing != null) {
        dao.upsertTombstone(
            TombstoneEntity(
                entityType = "feed",
                naturalKey = feedNaturalKey(existing.url),
                deletedAt = nowIso,
            )
        )
    }
    purgeFeedItemsLocally(dao = dao, offlineCacheManager = offlineCacheManager, feedId = feedId)
    dao.deletePendingMutation(feedId)
    dao.deleteFeedById(feedId)
    return existing?.title ?: feedId
}

internal suspend fun purgeFeedItemsLocally(
    dao: RocheDao,
    offlineCacheManager: OfflineCacheManager,
    feedId: String,
) {
    val feedItemIds = dao.getItemIdsForFeed(feedId)
    for (itemId in feedItemIds) {
        offlineCacheManager.evictCachedItemFiles(itemId)
        dao.deleteItemTagsForItem(itemId)
        dao.deletePendingMutation(itemId)
        dao.deleteItemById(itemId)
    }
}

internal suspend fun pushDeleteItemBestEffort(
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
): Boolean = runSuspendCatching {
    val currentSettings = settings.settings.first()
    if (currentSettings.serverUrl.isBlank() || currentSettings.bearerToken.isBlank()) {
        false
    } else {
        apiClient.deleteItem(
            serverUrl = currentSettings.serverUrl,
            token = currentSettings.bearerToken,
            itemId = itemId,
        )
        true
    }
}
    .recover { error ->
        val problem = error as? ProblemDetailsException
        problem?.statusCode == HTTP_NOT_FOUND
    }
    .getOrDefault(false)

internal fun buildLocalOpmlXml(feeds: List<FeedEntity>): String = buildString {
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

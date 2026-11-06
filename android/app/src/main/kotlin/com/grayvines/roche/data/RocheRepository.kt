package com.grayvines.roche.data

import com.grayvines.roche.data.local.ItemTagEntity
import com.grayvines.roche.data.local.PendingMutationEntity
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.TagEntity
import com.grayvines.roche.data.remote.RemoteItemDto
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.data.settings.normalizeServerUrl
import com.grayvines.roche.data.settings.parsePairingInput
import com.grayvines.roche.model.FeedItem
import com.grayvines.roche.model.InboxEntry
import com.grayvines.roche.model.InboxFilter
import com.grayvines.roche.model.ItemStatus
import com.grayvines.roche.model.TagItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

data class SaveUrlResult(val title: String, val isFeed: Boolean)

data class RepositorySnapshot(
    val entries: List<InboxEntry>,
    val feeds: List<FeedItem>,
    val tags: List<TagItem>,
)

class RocheRepository(
    private val database: RocheDatabase,
    private val apiClient: RocheApiClient,
    private val settings: SettingsRepository,
) {
    private val dao = database.dao()

    val snapshot: Flow<RepositorySnapshot> =
        combine(
            dao.observeItems(),
            dao.observeFeeds(),
            dao.observeTags(),
            dao.observeItemTags(),
        ) { items, feeds, tags, itemTags ->
            buildSnapshot(items = items, feeds = feeds, tags = tags, itemTags = itemTags)
        }

    suspend fun setArchiveState(itemId: String, archived: Boolean): ItemStatus? {
        val previous = dao.getItemById(itemId)?.let { ItemStatus.fromWireValue(it.status) }
        val newStatus = if (archived) ItemStatus.ARCHIVED else ItemStatus.UNREAD
        dao.updateItemStatus(itemId, newStatus.wireValue)
        syncStatusMutation(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            itemId = itemId,
            status = newStatus.wireValue,
        )
        return previous
    }

    suspend fun restoreStatus(itemId: String, status: ItemStatus) {
        dao.updateItemStatus(itemId, status.wireValue)
        syncStatusMutation(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            itemId = itemId,
            status = status.wireValue,
        )
    }

    suspend fun toggleStar(itemId: String): Boolean {
        val current = dao.getItemById(itemId) ?: return false
        val updated = !current.isFavorite
        dao.updateItemFavorite(itemId, updated)
        syncFavoriteMutation(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            itemId = itemId,
            isFavorite = updated,
        )
        return updated
    }

    suspend fun toggleOfflineCache(itemId: String): Boolean {
        val current = dao.getItemById(itemId) ?: return false
        val updated = !current.isCachedOffline
        dao.updateItemCachedOffline(itemId, updated)
        return updated
    }

    suspend fun updateReadProgress(itemId: String, progress: Double) {
        if (!progress.isFinite()) return
        val clamped = progress.coerceIn(MIN_READ_PROGRESS, MAX_READ_PROGRESS)
        dao.updateReadProgress(itemId, clamped)
        syncReadProgressMutation(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            itemId = itemId,
            progress = clamped,
        )
    }

    suspend fun toggleTagOnItem(itemId: String, tagName: String) {
        val normalized = normalizeTagName(tagName)
        if (normalized.isBlank()) return
        val existingTag =
            dao.getTagByName(normalized)
                ?: TagEntity(id = newTagId(), name = normalized).also { dao.upsertTag(it) }
        val alreadyAssigned = dao.hasItemTag(itemId = itemId, tagId = existingTag.id) > 0
        if (alreadyAssigned) {
            dao.deleteItemTag(itemId = itemId, tagId = existingTag.id)
            syncRemoveTagMutation(
                dao = dao,
                apiClient = apiClient,
                settings = settings,
                itemId = itemId,
                existingTag = existingTag,
                normalizedName = normalized,
            )
        } else {
            dao.insertItemTag(ItemTagEntity(itemId = itemId, tagId = existingTag.id))
            syncAddTagMutation(
                dao = dao,
                apiClient = apiClient,
                settings = settings,
                itemId = itemId,
                existingTag = existingTag,
                normalizedName = normalized,
            )
        }
    }

    suspend fun saveUrlOrFeed(
        inputUrl: String,
        titleOverride: String?,
        tagCsv: String,
    ): SaveUrlResult {
        val trimmedUrl = inputUrl.trim()
        if (isLikelyFeedUrl(trimmedUrl)) {
            val feedTitle =
                subscribeFeedBestEffort(
                    dao = dao,
                    apiClient = apiClient,
                    settings = settings,
                    url = trimmedUrl,
                    titleOverride = titleOverride,
                )
            return SaveUrlResult(title = feedTitle, isFeed = true)
        }
        val tags = parseTagCsv(tagCsv)
        val inferredType = inferContentType(trimmedUrl)
        val hostTitle = extractHostTitle(trimmedUrl)
        val cleanTitleOverride = titleOverride?.trim()?.takeIf { it.isNotBlank() }
        val resolvedTitle = cleanTitleOverride ?: "$hostTitle — Saved Entry"
        val localEntity = createSavedItemEntity(trimmedUrl, resolvedTitle, hostTitle, inferredType)

        dao.upsertItem(localEntity)
        attachTagsLocally(dao = dao, itemId = localEntity.id, tagNames = tags)
        dao.upsertPendingMutation(
            PendingMutationEntity(
                entityId = localEntity.id,
                isLocalItem = true,
                titleOverride = cleanTitleOverride,
            )
        )

        val remoteSaved = pushSavedUrlBestEffort(trimmedUrl, cleanTitleOverride, tags)
        val finalEntity =
            if (remoteSaved != null && remoteSaved.id.isNotBlank()) {
                finalizeOnlineSavedItem(
                    dao = dao,
                    apiClient = apiClient,
                    settings = settings,
                    localEntity = localEntity,
                    remoteSaved = remoteSaved,
                    tags = tags,
                )
            } else {
                localEntity
            }
        return SaveUrlResult(title = finalEntity.title, isFeed = false)
    }

    internal suspend fun discoverServer(
        tailnetDomains: List<String> = emptyList()
    ): Result<String> = runCatching {
        val discovered =
            apiClient.discoverServer(tailnetDomains = tailnetDomains)
                ?: error("No Roche server found on Tailnet (tried roche:8080 / svc:roche)")
        val preferredUrl = normalizeServerUrl(discovered.preferredUrl)
        check(preferredUrl.isNotBlank()) { "Discovered server returned an invalid URL" }
        settings.update { current ->
            val preservedToken = if (current.serverUrl == preferredUrl) current.bearerToken else ""
            current.copy(serverUrl = preferredUrl, bearerToken = preservedToken)
        }
        preferredUrl
    }

    suspend fun pairWithCode(
        serverUrl: String,
        pairingCode: String,
        deviceName: String,
    ): Result<String> = runCatching {
        val (parsedUrl, cleanCode) = parsePairingInput(serverUrl, pairingCode)
        check(cleanCode.isNotBlank()) { "Enter a valid pairing code" }
        val resolvedUrl =
            if (parsedUrl.isNotBlank()) {
                parsedUrl
            } else {
                val savedUrl = normalizeServerUrl(settings.settings.first().serverUrl)
                if (savedUrl.isNotBlank()) {
                    savedUrl
                } else {
                    apiClient
                        .discoverServer()
                        ?.let { normalizeServerUrl(it.preferredUrl) }
                        .orEmpty()
                }
            }
        check(resolvedUrl.isNotBlank()) {
            "Could not auto-discover Roche server on Tailnet; enter Server URL"
        }
        val token =
            apiClient.pairDevice(
                serverUrl = resolvedUrl,
                code = cleanCode,
                deviceName = deviceName.trim().ifBlank { "Roche Android" },
            )
        settings.update { it.copy(serverUrl = resolvedUrl, bearerToken = token) }
        token
    }

    suspend fun syncWithServer(): Result<Int> = runCatching {
        val currentSettings = settings.settings.first()
        check(currentSettings.serverUrl.isNotBlank()) { "Configure Server URL in Settings" }
        val pushOutcome =
            if (currentSettings.bearerToken.isNotBlank()) {
                pushPendingMutations(
                    dao = dao,
                    apiClient = apiClient,
                    serverUrl = currentSettings.serverUrl,
                    token = currentSettings.bearerToken,
                )
            } else {
                PushMutationsOutcome()
            }
        val remoteFeeds =
            apiClient.fetchFeeds(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
            )
        val remoteItems =
            apiClient.fetchItems(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
            )
        val remoteTags =
            apiClient.fetchTags(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
            )
        val mergedFeeds = mergePushedFeeds(remoteFeeds, pushOutcome.createdFeeds)
        val mergedItems = mergePushedItems(remoteItems, pushOutcome.savedItems)

        reconcileRemoteSnapshot(
            dao = dao,
            remoteFeeds = mergedFeeds,
            remoteItems = mergedItems,
            remoteTags = remoteTags,
            appliedMutations = pushOutcome.appliedMutations,
        )
        mergedItems.size
    }

    internal suspend fun pushSavedUrlBestEffort(
        url: String,
        titleOverride: String?,
        tags: List<String>,
    ): RemoteItemDto? = runCatching {
        val currentSettings = settings.settings.first()
        if (currentSettings.serverUrl.isBlank() || currentSettings.bearerToken.isBlank()) {
            null
        } else {
            apiClient.saveItemUrl(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
                url = url,
                title = titleOverride,
                tags = tags,
            )
        }
    }
        .getOrNull()

    companion object {
        fun filterEntries(
            entries: List<InboxEntry>,
            filter: InboxFilter,
            searchQuery: String = "",
            selectedTag: String? = null,
            selectedFeedId: String? = null,
        ): List<InboxEntry> =
            filterInboxEntries(
                entries = entries,
                filter = filter,
                searchQuery = searchQuery,
                selectedTag = selectedTag,
                selectedFeedId = selectedFeedId,
            )
    }
}

private const val MIN_READ_PROGRESS = 0.0
private const val MAX_READ_PROGRESS = 1.0

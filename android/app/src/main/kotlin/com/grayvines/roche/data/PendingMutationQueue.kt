package com.grayvines.roche.data

import com.grayvines.roche.data.local.FeedEntity
import com.grayvines.roche.data.local.ItemEntity
import com.grayvines.roche.data.local.PendingEntityKind
import com.grayvines.roche.data.local.PendingMutationEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.TagEntity
import com.grayvines.roche.data.remote.ProblemDetailsException
import com.grayvines.roche.data.remote.RemoteFeedDto
import com.grayvines.roche.data.remote.RemoteItemDto
import com.grayvines.roche.data.remote.RemoteTagDto
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.remote.UNREAD_READ_AT_TOMBSTONE

internal const val HTTP_NOT_FOUND = 404
internal const val ARCHIVED_STATUS_WIRE = "archived"
internal const val DELETED_STATUS_TOMBSTONE = "__deleted__"
internal const val FEED_ACTIVE_STATUS_MUTATION = "__feed_active__"
internal const val FEED_ARCHIVED_STATUS_MUTATION = "__feed_archived__"
internal const val FEED_RENAME_STATUS_MUTATION = "rename_feed"

internal object MutationAction {
    const val RENAME_FEED = FEED_RENAME_STATUS_MUTATION
}

internal val PendingMutationEntity.isDeletedItem: Boolean
    get() = dirtyStatus == DELETED_STATUS_TOMBSTONE

internal val PendingMutationEntity.dirtyFeedIsActive: Boolean?
    get() =
        when (dirtyStatus) {
            FEED_ACTIVE_STATUS_MUTATION -> true
            FEED_ARCHIVED_STATUS_MUTATION -> false
            else -> null
        }

internal val PendingMutationEntity.isRemoteFeedMutation: Boolean
    get() =
        kind == PendingEntityKind.REMOTE_ITEM &&
            (dirtyFeedIsActive != null || dirtyStatus == FEED_RENAME_STATUS_MUTATION)

internal fun PendingMutationEntity.resolveEffectiveReadAt(
    defaultReadAt: String?,
    statusAffectsDefault: Boolean = false,
): String? =
    when (dirtyReadAt) {
        null ->
            if (statusAffectsDefault && dirtyStatus != null) {
                if (dirtyStatus.equals(ARCHIVED_STATUS_WIRE, ignoreCase = true)) {
                    defaultReadAt
                } else {
                    null
                }
            } else {
                defaultReadAt
            }
        UNREAD_READ_AT_TOMBSTONE -> null
        else -> dirtyReadAt
    }

internal data class PushMutationsOutcome(
    val createdFeeds: List<RemoteFeedDto> = emptyList(),
    val savedItems: List<RemoteItemDto> = emptyList(),
    val appliedMutations: List<PendingMutationEntity> = emptyList(),
)

internal fun isCleanMutation(mutation: PendingMutationEntity): Boolean =
    mutation.kind == PendingEntityKind.REMOTE_ITEM &&
        mutation.dirtyStatus == null &&
        mutation.dirtyFavorite == null &&
        mutation.dirtyReadProgress == null &&
        mutation.dirtyReadAt == null &&
        mutation.addedTagsCsv.isBlank() &&
        mutation.removedTagsCsv.isBlank()

internal suspend fun upsertOrCleanPendingMutation(
    dao: RocheDao,
    mutation: PendingMutationEntity,
) {
    if (isCleanMutation(mutation)) {
        dao.deletePendingMutation(mutation.entityId)
    } else {
        dao.upsertPendingMutation(mutation)
    }
}

internal fun hasDirtyItemFields(mutation: PendingMutationEntity): Boolean =
    mutation.dirtyStatus != null ||
        mutation.dirtyFavorite != null ||
        mutation.dirtyReadProgress != null ||
        mutation.dirtyReadAt != null

internal suspend fun pushPendingMutations(
    dao: RocheDao,
    apiClient: RocheApiClient,
    serverUrl: String,
    token: String,
): PushMutationsOutcome {
    val mutations = dao.getAllPendingMutations()
    if (mutations.isEmpty()) return PushMutationsOutcome()

    val createdFeeds = mutableListOf<RemoteFeedDto>()
    val savedItems = mutableListOf<RemoteItemDto>()
    val appliedMutations = mutableListOf<PendingMutationEntity>()

    for (mutation in mutations) {
        when (mutation.kind) {
            PendingEntityKind.LOCAL_FEED -> {
                pushPendingLocalFeed(dao, apiClient, serverUrl, token, mutation)?.let {
                    createdFeeds.add(it)
                }
            }
            PendingEntityKind.LOCAL_ITEM -> {
                pushPendingLocalItem(dao, apiClient, serverUrl, token, mutation)?.let {
                    (savedItem, applied) ->
                    savedItems.add(savedItem)
                    appliedMutations.add(applied)
                }
            }
            PendingEntityKind.REMOTE_ITEM -> {
                if (mutation.isRemoteFeedMutation) {
                    pushPendingFeedMutation(dao, apiClient, serverUrl, token, mutation)
                    appliedMutations.add(mutation)
                } else {
                    appliedMutations.add(
                        pushPendingItemMutation(dao, apiClient, serverUrl, token, mutation)
                    )
                }
            }
        }
    }
    return PushMutationsOutcome(
        createdFeeds = createdFeeds,
        savedItems = savedItems,
        appliedMutations = appliedMutations,
    )
}

internal suspend fun pushPendingFeedMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    serverUrl: String,
    token: String,
    mutation: PendingMutationEntity,
): Boolean {
    val desiredActive = mutation.dirtyFeedIsActive
    val cleanTitle = mutation.titleOverride?.trim()?.takeIf { it.isNotBlank() }
    if (desiredActive == null && cleanTitle == null) {
        dao.deletePendingMutation(mutation.entityId)
        return false
    }
    val pushed = runSuspendCatching {
        if (desiredActive == null && cleanTitle != null) {
            apiClient.updateFeedTitle(
                serverUrl = serverUrl,
                bearerToken = token,
                feedId = mutation.entityId,
                title = cleanTitle,
            )
        } else {
            apiClient.updateFeed(
                serverUrl = serverUrl,
                token = token,
                feedId = mutation.entityId,
                title = cleanTitle,
                isActive = desiredActive,
            )
        }
        true
    }
        .recover { error ->
            (error as? ProblemDetailsException)?.statusCode == HTTP_NOT_FOUND
        }
        .getOrDefault(false)
    if (pushed) {
        dao.deletePendingMutation(mutation.entityId)
    }
    return pushed
}

internal suspend fun pushPendingLocalFeed(
    dao: RocheDao,
    apiClient: RocheApiClient,
    serverUrl: String,
    token: String,
    mutation: PendingMutationEntity,
): RemoteFeedDto? {
    val localFeed = dao.getFeedById(mutation.entityId)
    if (localFeed == null || !isSyncablePublicUrl(localFeed.url)) {
        if (localFeed == null) {
            dao.deletePendingMutation(mutation.entityId)
        }
        return null
    }
    val remoteFeed = runSuspendCatching {
        apiClient.createFeed(
            serverUrl = serverUrl,
            token = token,
            url = localFeed.url,
            title = mutation.titleOverride,
        )
    }
        .getOrNull()
    return if (remoteFeed != null && remoteFeed.id.isNotBlank()) {
        if (!localFeed.isActive) {
            runSuspendCatching {
                apiClient.updateFeed(
                    serverUrl = serverUrl,
                    token = token,
                    feedId = remoteFeed.id,
                    isActive = false,
                )
            }
        }
        val effectiveRemote = remoteFeed.copy(isActive = localFeed.isActive)
        dao.deleteFeedById(localFeed.id)
        dao.upsertFeed(
            FeedEntity(
                id = effectiveRemote.id,
                title = effectiveRemote.title.ifBlank { localFeed.title },
                url = effectiveRemote.url.ifBlank { localFeed.url },
                siteUrl = effectiveRemote.siteUrl,
                feedType =
                    if (localFeed.feedType.equals("podcast", ignoreCase = true)) {
                        "podcast"
                    } else {
                        effectiveRemote.feedType
                    },
                isActive = localFeed.isActive,
                autoDownloadEpisodes = localFeed.autoDownloadEpisodes,
                customPlaybackSpeed = localFeed.customPlaybackSpeed,
            )
        )
        dao.deletePendingMutation(mutation.entityId)
        effectiveRemote
    } else {
        null
    }
}

internal suspend fun pushPendingLocalItem(
    dao: RocheDao,
    apiClient: RocheApiClient,
    serverUrl: String,
    token: String,
    mutation: PendingMutationEntity,
): Pair<RemoteItemDto, PendingMutationEntity>? {
    val localItem = dao.getItemById(mutation.entityId)
    if (localItem == null) {
        dao.deletePendingMutation(mutation.entityId)
        return null
    }
    val localTags = dao.getTagNamesForItem(localItem.id)
    val remoteSaved = runSuspendCatching {
        apiClient.saveItemUrl(
            serverUrl = serverUrl,
            token = token,
            url = localItem.url,
            title = mutation.titleOverride,
            tags = localTags,
        )
    }
        .getOrNull()
    return if (remoteSaved != null && remoteSaved.id.isNotBlank()) {
        val fieldsPushed =
            pushDirtyItemFields(apiClient, serverUrl, token, remoteSaved.id, mutation).isSuccess
        commitPushedLocalItem(
            dao = dao,
            localItem = localItem,
            localTags = localTags,
            remoteSaved = remoteSaved,
            mutation = mutation,
            fieldsPushed = fieldsPushed,
        )
    } else {
        null
    }
}

internal suspend fun pushDirtyItemFields(
    apiClient: RocheApiClient,
    serverUrl: String,
    token: String,
    itemId: String,
    mutation: PendingMutationEntity,
): Result<RemoteItemDto?> =
    if (hasDirtyItemFields(mutation)) {
        runSuspendCatching {
            apiClient.updateItem(
                serverUrl = serverUrl,
                token = token,
                itemId = itemId,
                status = mutation.dirtyStatus,
                isFavorite = mutation.dirtyFavorite,
                readProgress = mutation.dirtyReadProgress,
                readAt = mutation.dirtyReadAt,
            )
        }
    } else {
        Result.success(null)
    }

@Suppress("LongParameterList")
internal suspend fun commitPushedLocalItem(
    dao: RocheDao,
    localItem: ItemEntity,
    localTags: List<String>,
    remoteSaved: RemoteItemDto,
    mutation: PendingMutationEntity,
    fieldsPushed: Boolean,
): Pair<RemoteItemDto, PendingMutationEntity> {
    val effectiveReadAt = mutation.resolveEffectiveReadAt(defaultReadAt = remoteSaved.readAt)
    val effectiveSaved =
        remoteSaved.copy(
            title = remoteSaved.title.ifBlank { localItem.title },
            status = mutation.dirtyStatus ?: remoteSaved.status,
            isFavorite = mutation.dirtyFavorite ?: remoteSaved.isFavorite,
            readProgress = mutation.dirtyReadProgress ?: remoteSaved.readProgress,
            readAt = effectiveReadAt,
            tagNames = (remoteSaved.tagNames + localTags).distinct(),
        )
    if (remoteSaved.id != localItem.id) {
        dao.deleteItemTagsForItem(localItem.id)
        dao.deleteItemById(localItem.id)
    }
    dao.upsertItem(
        toRemoteItemEntity(
            remote = effectiveSaved,
            feedTitlesById = emptyMap(),
            isCachedOffline = localItem.isCachedOffline,
            existing = localItem,
        )
    )
    attachTagsLocally(dao = dao, itemId = remoteSaved.id, tagNames = effectiveSaved.tagNames)
    dao.deletePendingMutation(mutation.entityId)
    if (!fieldsPushed) {
        dao.upsertPendingMutation(
            PendingMutationEntity(
                entityId = remoteSaved.id,
                isLocalItem = false,
                dirtyStatus = mutation.dirtyStatus,
                dirtyFavorite = mutation.dirtyFavorite,
                dirtyReadProgress = mutation.dirtyReadProgress,
                dirtyReadAt = mutation.dirtyReadAt,
            )
        )
    }
    val applied =
        mutation.copy(
            entityId = remoteSaved.id,
            isLocalItem = false,
            addedTagsCsv = localTags.joinToString(","),
            removedTagsCsv = "",
        )
    return effectiveSaved to applied
}

internal suspend fun pushPendingItemMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    serverUrl: String,
    token: String,
    mutation: PendingMutationEntity,
): PendingMutationEntity {
    if (mutation.isDeletedItem) {
        val deleted = runSuspendCatching {
            apiClient.deleteItem(
                serverUrl = serverUrl,
                token = token,
                itemId = mutation.entityId,
            )
            true
        }
            .recover { error ->
                (error as? ProblemDetailsException)?.statusCode == HTTP_NOT_FOUND
            }
            .getOrDefault(false)
        if (deleted) {
            dao.deletePendingMutation(mutation.entityId)
        }
        return mutation
    }
    val updateResult = pushDirtyItemFields(apiClient, serverUrl, token, mutation.entityId, mutation)
    val updateError = updateResult.exceptionOrNull() as? ProblemDetailsException
    if (updateError?.statusCode == HTTP_NOT_FOUND) {
        dao.deletePendingMutation(mutation.entityId)
        return mutation
    }
    val fieldsCleaned = updateResult.isSuccess

    val remainingAdded =
        pushPendingTagAdditions(
            dao = dao,
            apiClient = apiClient,
            serverUrl = serverUrl,
            token = token,
            itemId = mutation.entityId,
            addedTags = parseTagCsv(mutation.addedTagsCsv),
        )
    val remainingRemoved =
        pushPendingTagRemovals(
            dao = dao,
            apiClient = apiClient,
            serverUrl = serverUrl,
            token = token,
            itemId = mutation.entityId,
            removedTags = parseTagCsv(mutation.removedTagsCsv),
        )

    val updated =
        mutation.copy(
            dirtyStatus = if (fieldsCleaned) null else mutation.dirtyStatus,
            dirtyFavorite = if (fieldsCleaned) null else mutation.dirtyFavorite,
            dirtyReadProgress = if (fieldsCleaned) null else mutation.dirtyReadProgress,
            dirtyReadAt = if (fieldsCleaned) null else mutation.dirtyReadAt,
            addedTagsCsv = remainingAdded.joinToString(","),
            removedTagsCsv = remainingRemoved.joinToString(","),
        )
    upsertOrCleanPendingMutation(dao = dao, mutation = updated)
    return mutation
}

@Suppress("LongParameterList")
internal suspend fun pushPendingTagAdditions(
    dao: RocheDao,
    apiClient: RocheApiClient,
    serverUrl: String,
    token: String,
    itemId: String,
    addedTags: List<String>,
): List<String> = addedTags.filterNot { tagName ->
    runSuspendCatching {
        val remoteTags =
            apiClient.addTagToItem(
                serverUrl = serverUrl,
                token = token,
                itemId = itemId,
                tagName = tagName,
            )
        dao.getTagByName(tagName)?.let { localTag ->
            reconcileAddedRemoteTag(
                dao = dao,
                itemId = itemId,
                localTag = localTag,
                normalizedName = tagName,
                remoteTags = remoteTags,
            )
        }
    }
        .isSuccess
}

@Suppress("LongParameterList")
internal suspend fun pushPendingTagRemovals(
    dao: RocheDao,
    apiClient: RocheApiClient,
    serverUrl: String,
    token: String,
    itemId: String,
    removedTags: List<String>,
): List<String> {
    if (removedTags.isEmpty()) return emptyList()
    var cachedRemoteTags: List<RemoteTagDto>? = null
    return removedTags.filterNot { tagName ->
        runSuspendCatching {
            val existingTag =
                dao.getTagByName(tagName) ?: TagEntity(id = newTagId(), name = tagName)
            val remoteTags =
                if (isLocalTagId(existingTag.id)) {
                    cachedRemoteTags
                        ?: apiClient.fetchTags(serverUrl = serverUrl, token = token).also {
                            cachedRemoteTags = it
                        }
                } else {
                    emptyList()
                }
            val resolvedId =
                resolveRemoteTagIdForRemoval(
                    dao = dao,
                    existingTag = existingTag,
                    normalizedName = tagName,
                    remoteTags = remoteTags,
                )
            if (resolvedId != null) {
                apiClient.removeTagFromItem(
                    serverUrl = serverUrl,
                    token = token,
                    itemId = itemId,
                    tagId = resolvedId,
                )
            }
        }
            .isSuccess
    }
}

@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data

import com.grayvines.roche.data.local.FeedEntity
import com.grayvines.roche.data.local.ItemEntity
import com.grayvines.roche.data.local.ItemTagEntity
import com.grayvines.roche.data.local.PendingEntityKind
import com.grayvines.roche.data.local.PendingMutationEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.TagEntity
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.data.remote.ProblemDetailsException
import com.grayvines.roche.data.remote.RemoteFeedDto
import com.grayvines.roche.data.remote.RemoteItemDto
import com.grayvines.roche.data.remote.RemoteTagDto
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.remote.UNREAD_READ_AT_TOMBSTONE
import com.grayvines.roche.data.settings.Settings
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.model.ItemStatus
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class BackgroundMutationDispatcher(private val scope: CoroutineScope? = null) {
    private val itemMutexes = ConcurrentHashMap<String, Mutex>()

    val isAsync: Boolean
        get() = scope != null

    suspend fun dispatchOrRun(itemId: String, block: suspend () -> Unit) {
        val mutex = itemMutexes.computeIfAbsent(itemId) { Mutex() }
        if (scope == null) {
            mutex.withLock { block() }
        } else {
            scope.launch { mutex.withLock { block() } }
        }
    }
}

private const val HTTP_NOT_FOUND = 404
private const val ARCHIVED_STATUS_WIRE = "archived"
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

internal suspend fun finalizeOnlineSavedItem(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    localEntity: ItemEntity,
    remoteSaved: RemoteItemDto,
    tags: List<String>,
): ItemEntity {
    val inFlight = dao.getPendingMutation(localEntity.id)
    val currentLocal = dao.getItemById(localEntity.id) ?: localEntity
    val currentLocalTags = dao.getTagNamesForItem(localEntity.id)
    dao.deletePendingMutation(localEntity.id)
    val reconciled = reconcileSavedItemEntity(currentLocal, remoteSaved)
    if (remoteSaved.id != localEntity.id) {
        dao.deleteItemTagsForItem(localEntity.id)
        dao.deleteItemById(localEntity.id)
    }
    dao.upsertItem(reconciled)
    val effectiveTags =
        resolveFinalizedLocalTags(
            initialTags = tags,
            currentLocalTags = currentLocalTags,
            remoteTags = remoteSaved.tagNames,
            inFlight = inFlight,
        )
    attachTagsLocally(dao = dao, itemId = reconciled.id, tagNames = effectiveTags)
    if (inFlight != null) {
        val serverKnownTags = (tags + remoteSaved.tagNames).map { normalizeTagName(it) }.distinct()
        syncInFlightSavedItemMutations(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            reconciledItemId = reconciled.id,
            serverKnownTags = serverKnownTags,
            inFlight = inFlight,
        )
    }
    return reconciled
}

internal fun resolveFinalizedLocalTags(
    initialTags: List<String>,
    currentLocalTags: List<String>,
    remoteTags: List<String>,
    inFlight: PendingMutationEntity?,
): List<String> {
    val removedInFlight = parseTagCsv(inFlight?.removedTagsCsv.orEmpty()).toSet()
    val addedInFlight = parseTagCsv(inFlight?.addedTagsCsv.orEmpty())
    val base =
        if (currentLocalTags.isNotEmpty() || inFlight != null) currentLocalTags else initialTags
    return (base + remoteTags + addedInFlight)
        .map { normalizeTagName(it) }
        .distinct()
        .filter { it.isNotBlank() && it !in removedInFlight }
}

internal suspend fun syncInFlightSavedItemMutations(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    reconciledItemId: String,
    serverKnownTags: List<String>,
    inFlight: PendingMutationEntity,
) {
    val fieldsPushed =
        if (hasDirtyItemFields(inFlight)) {
            pushItemUpdateBestEffort(
                apiClient = apiClient,
                settings = settings,
                itemId = reconciledItemId,
                status = inFlight.dirtyStatus,
                isFavorite = inFlight.dirtyFavorite,
                readProgress = inFlight.dirtyReadProgress,
                readAt = inFlight.dirtyReadAt,
            )
        } else {
            true
        }
    val serverTagSet = serverKnownTags.map { normalizeTagName(it) }.toSet()
    val addedDelta = parseTagCsv(inFlight.addedTagsCsv).filter { it !in serverTagSet }
    val removedDelta = parseTagCsv(inFlight.removedTagsCsv).filter { it in serverTagSet }
    val currentSettings = settings.settings.first()
    val canPush = currentSettings.serverUrl.isNotBlank() && currentSettings.bearerToken.isNotBlank()
    val remainingAdded =
        if (canPush && addedDelta.isNotEmpty()) {
            pushPendingTagAdditions(
                dao = dao,
                apiClient = apiClient,
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
                itemId = reconciledItemId,
                addedTags = addedDelta,
            )
        } else {
            addedDelta
        }
    val remainingRemoved =
        if (canPush && removedDelta.isNotEmpty()) {
            pushPendingTagRemovals(
                dao = dao,
                apiClient = apiClient,
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
                itemId = reconciledItemId,
                removedTags = removedDelta,
            )
        } else {
            removedDelta
        }
    upsertOrCleanPendingMutation(
        dao = dao,
        mutation =
            buildRemainingInFlightMutation(
                reconciledItemId = reconciledItemId,
                inFlight = inFlight,
                fieldsPushed = fieldsPushed,
                remainingAdded = remainingAdded,
                remainingRemoved = remainingRemoved,
            ),
    )
}

private fun buildRemainingInFlightMutation(
    reconciledItemId: String,
    inFlight: PendingMutationEntity,
    fieldsPushed: Boolean,
    remainingAdded: List<String>,
    remainingRemoved: List<String>,
): PendingMutationEntity =
    PendingMutationEntity(
        entityId = reconciledItemId,
        isLocalItem = false,
        dirtyStatus = if (fieldsPushed) null else inFlight.dirtyStatus,
        dirtyFavorite = if (fieldsPushed) null else inFlight.dirtyFavorite,
        dirtyReadProgress = if (fieldsPushed) null else inFlight.dirtyReadProgress,
        dirtyReadAt = if (fieldsPushed) null else inFlight.dirtyReadAt,
        addedTagsCsv = remainingAdded.joinToString(","),
        removedTagsCsv = remainingRemoved.joinToString(","),
    )

internal suspend fun pushItemUpdateBestEffort(
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    status: String? = null,
    isFavorite: Boolean? = null,
    readProgress: Double? = null,
    readAt: String? = null,
): Boolean = runSuspendCatching {
    val currentSettings = settings.settings.first()
    if (currentSettings.serverUrl.isBlank() || currentSettings.bearerToken.isBlank()) {
        false
    } else {
        apiClient.updateItem(
            serverUrl = currentSettings.serverUrl,
            token = currentSettings.bearerToken,
            itemId = itemId,
            status = status,
            isFavorite = isFavorite,
            readProgress = readProgress,
            readAt = readAt,
        )
        true
    }
}
    .getOrDefault(false)

private suspend fun syncItemFieldMutation(
    dao: RocheDao,
    itemId: String,
    dispatcher: BackgroundMutationDispatcher = BackgroundMutationDispatcher(),
    applyDirty: (PendingMutationEntity) -> PendingMutationEntity,
    clearDirtyIfMatches: (PendingMutationEntity) -> PendingMutationEntity,
    pushRemote: suspend () -> Boolean,
) {
    val existing = dao.getPendingMutation(itemId)
    if (existing?.kind == PendingEntityKind.LOCAL_ITEM) {
        dao.upsertPendingMutation(applyDirty(existing))
        return
    }
    val base = existing ?: PendingMutationEntity(entityId = itemId)
    val dirty = applyDirty(base)
    if (!dispatcher.isAsync) {
        val pushed = pushRemote()
        if (!pushed) {
            upsertOrCleanPendingMutation(dao = dao, mutation = dirty)
        } else if (existing != null) {
            upsertOrCleanPendingMutation(dao = dao, mutation = clearDirtyIfMatches(dirty))
        }
        return
    }
    upsertOrCleanPendingMutation(dao = dao, mutation = dirty)
    dispatcher.dispatchOrRun(itemId) {
        val pushed = pushRemote()
        if (pushed) {
            val latest = dao.getPendingMutation(itemId)
            if (latest != null && latest.kind != PendingEntityKind.LOCAL_ITEM) {
                upsertOrCleanPendingMutation(dao = dao, mutation = clearDirtyIfMatches(latest))
            }
        }
    }
}

internal suspend fun syncStatusMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    status: String,
    explicitReadAt: String? = null,
    dispatcher: BackgroundMutationDispatcher = BackgroundMutationDispatcher(),
) {
    syncItemFieldMutation(
        dao = dao,
        itemId = itemId,
        dispatcher = dispatcher,
        applyDirty = { it.copy(dirtyStatus = status, dirtyReadAt = explicitReadAt) },
        clearDirtyIfMatches = { current ->
            if (current.dirtyStatus == status && current.dirtyReadAt == explicitReadAt) {
                current.copy(dirtyStatus = null, dirtyReadAt = null)
            } else if (current.dirtyStatus == status) {
                current.copy(dirtyStatus = null)
            } else {
                current
            }
        },
        pushRemote = {
            pushItemUpdateBestEffort(
                apiClient = apiClient,
                settings = settings,
                itemId = itemId,
                status = status,
                readAt = explicitReadAt,
            )
        },
    )
}

internal suspend fun syncFavoriteMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    isFavorite: Boolean,
    dispatcher: BackgroundMutationDispatcher = BackgroundMutationDispatcher(),
) {
    syncItemFieldMutation(
        dao = dao,
        itemId = itemId,
        dispatcher = dispatcher,
        applyDirty = { it.copy(dirtyFavorite = isFavorite) },
        clearDirtyIfMatches = { current ->
            if (current.dirtyFavorite == isFavorite) {
                current.copy(dirtyFavorite = null)
            } else {
                current
            }
        },
        pushRemote = {
            pushItemUpdateBestEffort(
                apiClient = apiClient,
                settings = settings,
                itemId = itemId,
                isFavorite = isFavorite,
            )
        },
    )
}

internal suspend fun syncReadProgressMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    progress: Double,
    dispatcher: BackgroundMutationDispatcher = BackgroundMutationDispatcher(),
) {
    syncItemFieldMutation(
        dao = dao,
        itemId = itemId,
        dispatcher = dispatcher,
        applyDirty = { it.copy(dirtyReadProgress = progress) },
        clearDirtyIfMatches = { current ->
            if (current.dirtyReadProgress == progress) {
                current.copy(dirtyReadProgress = null)
            } else {
                current
            }
        },
        pushRemote = {
            pushItemUpdateBestEffort(
                apiClient = apiClient,
                settings = settings,
                itemId = itemId,
                readProgress = progress,
            )
        },
    )
}

internal suspend fun syncReadAtMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    readAt: String?,
    dispatcher: BackgroundMutationDispatcher = BackgroundMutationDispatcher(),
) {
    val wireReadAt = readAt ?: UNREAD_READ_AT_TOMBSTONE
    syncItemFieldMutation(
        dao = dao,
        itemId = itemId,
        dispatcher = dispatcher,
        applyDirty = { it.copy(dirtyReadAt = wireReadAt) },
        clearDirtyIfMatches = { current ->
            if (current.dirtyReadAt == wireReadAt) {
                current.copy(dirtyReadAt = null)
            } else {
                current
            }
        },
        pushRemote = {
            pushItemUpdateBestEffort(
                apiClient = apiClient,
                settings = settings,
                itemId = itemId,
                readAt = wireReadAt,
            )
        },
    )
}

internal suspend fun resolveRemoteTagIdForRemoval(
    dao: RocheDao,
    existingTag: TagEntity,
    normalizedName: String,
    remoteTags: List<RemoteTagDto>,
): String? {
    if (!isLocalTagId(existingTag.id)) return existingTag.id
    val remoteMatch = remoteTags.firstOrNull {
        it.name.trim().equals(normalizedName, ignoreCase = true)
    }
    val resolvedId = remoteMatch?.id?.takeIf { it.isNotBlank() } ?: return null
    if (resolvedId != existingTag.id) {
        dao.migrateItemTagIds(oldTagId = existingTag.id, newTagId = resolvedId)
        dao.deleteTagById(id = existingTag.id)
        dao.upsertTag(TagEntity(id = resolvedId, name = normalizedName))
    }
    return resolvedId
}

internal suspend fun pushAddTagBestEffort(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    existingTag: TagEntity,
    normalizedName: String,
): Boolean = runSuspendCatching {
    val currentSettings = settings.settings.first()
    if (currentSettings.serverUrl.isBlank() || currentSettings.bearerToken.isBlank()) {
        false
    } else {
        val remoteTags =
            apiClient.addTagToItem(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
                itemId = itemId,
                tagName = normalizedName,
            )
        reconcileAddedRemoteTag(
            dao = dao,
            itemId = itemId,
            localTag = existingTag,
            normalizedName = normalizedName,
            remoteTags = remoteTags,
        )
        true
    }
}
    .getOrDefault(false)

internal suspend fun pushRemoveTagBestEffort(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    existingTag: TagEntity,
    normalizedName: String,
): Boolean = runSuspendCatching {
    val currentSettings = settings.settings.first()
    if (currentSettings.serverUrl.isBlank() || currentSettings.bearerToken.isBlank()) {
        false
    } else {
        val remoteTags =
            if (isLocalTagId(existingTag.id)) {
                apiClient.fetchTags(
                    serverUrl = currentSettings.serverUrl,
                    token = currentSettings.bearerToken,
                )
            } else {
                emptyList()
            }
        val resolvedTagId =
            resolveRemoteTagIdForRemoval(
                dao = dao,
                existingTag = existingTag,
                normalizedName = normalizedName,
                remoteTags = remoteTags,
            )
        if (resolvedTagId != null) {
            apiClient.removeTagFromItem(
                serverUrl = currentSettings.serverUrl,
                token = currentSettings.bearerToken,
                itemId = itemId,
                tagId = resolvedTagId,
            )
        }
        true
    }
}
    .getOrDefault(false)

internal suspend fun syncAddTagMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    existingTag: TagEntity,
    normalizedName: String,
) {
    val existing = dao.getPendingMutation(itemId)
    val base = existing ?: PendingMutationEntity(entityId = itemId)
    val added = parseTagCsv(base.addedTagsCsv).toMutableSet()
    val removed = parseTagCsv(base.removedTagsCsv).toMutableSet()
    if (existing?.kind == PendingEntityKind.LOCAL_ITEM) {
        added.add(normalizedName)
        removed.remove(normalizedName)
        dao.upsertPendingMutation(
            existing.copy(
                addedTagsCsv = added.joinToString(","),
                removedTagsCsv = removed.joinToString(","),
            )
        )
        return
    }
    val pushed =
        pushAddTagBestEffort(
            dao = dao,
            apiClient = apiClient,
            settings = settings,
            itemId = itemId,
            existingTag = existingTag,
            normalizedName = normalizedName,
        )
    if (pushed) {
        added.remove(normalizedName)
        removed.remove(normalizedName)
    } else {
        added.add(normalizedName)
        removed.remove(normalizedName)
    }
    upsertOrCleanPendingMutation(
        dao = dao,
        mutation =
            base.copy(
                addedTagsCsv = added.joinToString(","),
                removedTagsCsv = removed.joinToString(","),
            ),
    )
}

internal suspend fun syncRemoveTagMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    existingTag: TagEntity,
    normalizedName: String,
) {
    val existing = dao.getPendingMutation(itemId)
    val base = existing ?: PendingMutationEntity(entityId = itemId)
    val added = parseTagCsv(base.addedTagsCsv).toMutableSet()
    val removed = parseTagCsv(base.removedTagsCsv).toMutableSet()
    if (existing?.kind == PendingEntityKind.LOCAL_ITEM) {
        added.remove(normalizedName)
        removed.add(normalizedName)
        dao.upsertPendingMutation(
            existing.copy(
                addedTagsCsv = added.joinToString(","),
                removedTagsCsv = removed.joinToString(","),
            )
        )
        return
    }
    val wasOfflineLocalAdd = normalizedName in added && isLocalTagId(existingTag.id)
    val pushed =
        if (wasOfflineLocalAdd) {
            true
        } else {
            pushRemoveTagBestEffort(
                dao = dao,
                apiClient = apiClient,
                settings = settings,
                itemId = itemId,
                existingTag = existingTag,
                normalizedName = normalizedName,
            )
        }
    if (pushed) {
        added.remove(normalizedName)
        removed.remove(normalizedName)
    } else {
        added.remove(normalizedName)
        removed.add(normalizedName)
    }
    upsertOrCleanPendingMutation(
        dao = dao,
        mutation =
            base.copy(
                addedTagsCsv = added.joinToString(","),
                removedTagsCsv = removed.joinToString(","),
            ),
    )
}

internal suspend fun reconcileAddedRemoteTag(
    dao: RocheDao,
    itemId: String,
    localTag: TagEntity,
    normalizedName: String,
    remoteTags: List<RemoteTagDto>,
) {
    val remoteMatch =
        remoteTags.firstOrNull { it.name.trim().equals(normalizedName, ignoreCase = true) }
            ?: return
    if (remoteMatch.id.isNotBlank() && remoteMatch.id != localTag.id) {
        dao.upsertTag(TagEntity(id = remoteMatch.id, name = normalizedName))
        dao.migrateItemTagIds(oldTagId = localTag.id, newTagId = remoteMatch.id)
        dao.deleteItemTag(itemId = itemId, tagId = localTag.id)
        dao.deleteTagById(id = localTag.id)
        dao.insertItemTag(ItemTagEntity(itemId = itemId, tagId = remoteMatch.id))
    }
}

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
    val feedItems = dao.getAllItems().filter { it.feedId == feedId }
    for (item in feedItems) {
        offlineCacheManager.evictCachedItemFiles(item.id)
        dao.deleteItemTagsForItem(item.id)
        dao.deletePendingMutation(item.id)
        dao.deleteItemById(item.id)
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

internal suspend fun syncArchiveBatchMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemIds: List<String>,
    feedId: String? = null,
    allUnread: Boolean = false,
): List<ArchiveStateSnapshot> {
    val targetIds = itemIds.toSet()
    val unarchivedItems = dao.getUnarchivedItemStates().filter { it.id in targetIds }
    if (unarchivedItems.isEmpty()) return emptyList()

    val nowIso = Instant.now().toString()
    val snapshots = unarchivedItems.map { item ->
        ArchiveStateSnapshot(
            itemId = item.id,
            previousStatus = ItemStatus.fromWireValue(item.status),
            previousReadAt = item.readAt,
        )
    }
    for (item in unarchivedItems) {
        val nextReadAt = item.readAt?.takeIf { it.isNotBlank() } ?: nowIso
        dao.updateItemStatusAndReadAt(item.id, ARCHIVED_STATUS_WIRE, nextReadAt)
    }

    val unarchivedIds = unarchivedItems.map { it.id }
    val mutationsById = unarchivedIds.associateWith { dao.getPendingMutation(it) }
    val remoteIds = unarchivedIds.filter { mutationsById[it]?.kind != PendingEntityKind.LOCAL_ITEM }
    val remoteFeedId = feedId?.takeIf {
        dao.getPendingMutation(it)?.kind != PendingEntityKind.LOCAL_FEED
    }
    val currentSettings = settings.settings.first()
    val pushedToServer =
        if (
            remoteIds.isNotEmpty() &&
                currentSettings.serverUrl.isNotBlank() &&
                currentSettings.bearerToken.isNotBlank()
        ) {
            runSuspendCatching {
                    apiClient.archiveBatch(
                        serverUrl = currentSettings.serverUrl,
                        token = currentSettings.bearerToken,
                        itemIds = remoteIds,
                        feedId = remoteFeedId,
                        allUnread = allUnread && remoteIds.size == unarchivedIds.size,
                    )
                    true
                }
                .getOrDefault(false)
        } else {
            false
        }

    for (itemId in unarchivedIds) {
        recordBatchArchiveMutation(
            dao = dao,
            itemId = itemId,
            existing = mutationsById[itemId],
            pushedToServer = pushedToServer,
        )
    }
    return snapshots
}

private suspend fun recordBatchArchiveMutation(
    dao: RocheDao,
    itemId: String,
    existing: PendingMutationEntity?,
    pushedToServer: Boolean,
) {
    if (existing?.kind == PendingEntityKind.LOCAL_ITEM) {
        dao.upsertPendingMutation(
            existing.copy(dirtyStatus = ARCHIVED_STATUS_WIRE, dirtyReadAt = null)
        )
    } else {
        val base = existing ?: PendingMutationEntity(entityId = itemId)
        upsertOrCleanPendingMutation(
            dao = dao,
            mutation =
                base.copy(
                    dirtyStatus = if (pushedToServer) null else ARCHIVED_STATUS_WIRE,
                    dirtyReadAt = null,
                ),
        )
    }
}

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

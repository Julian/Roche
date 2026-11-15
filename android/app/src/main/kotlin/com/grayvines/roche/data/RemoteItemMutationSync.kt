@file:Suppress("TooManyFunctions")

package com.grayvines.roche.data

import com.grayvines.roche.data.local.ItemEntity
import com.grayvines.roche.data.local.ItemTagEntity
import com.grayvines.roche.data.local.PendingEntityKind
import com.grayvines.roche.data.local.PendingMutationEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.TagEntity
import com.grayvines.roche.data.remote.RemoteItemDto
import com.grayvines.roche.data.remote.RemoteTagDto
import com.grayvines.roche.data.remote.RocheApiClient
import com.grayvines.roche.data.remote.UNREAD_READ_AT_TOMBSTONE
import com.grayvines.roche.data.settings.SettingsRepository
import com.grayvines.roche.model.ItemStatus
import java.time.Instant
import kotlinx.coroutines.flow.first

@Suppress("LongParameterList")
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

@Suppress("LongParameterList")
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

@Suppress("LongParameterList")
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

@Suppress("LongParameterList")
private suspend fun syncItemFieldMutation(
    dao: RocheDao,
    itemId: String,
    dispatcher: BackgroundMutationDispatcher = BackgroundMutationDispatcher(),
    applyDirty: (PendingMutationEntity) -> PendingMutationEntity,
    clearDirtyIfMatches: (PendingMutationEntity) -> PendingMutationEntity,
    pushRemote: suspend () -> Boolean,
) {
    val handledLocally = stageFieldMutationBeforePush(dao, itemId, dispatcher.isAsync, applyDirty)
    if (handledLocally) return
    if (!dispatcher.isAsync) {
        val pushed = pushRemote()
        reconcileSyncFieldPushInTx(dao, itemId, pushed, applyDirty, clearDirtyIfMatches)
        return
    }
    dispatcher.dispatchOrRun(itemId) {
        if (pushRemote()) {
            reconcileAsyncFieldPushInTx(dao, itemId, clearDirtyIfMatches)
        }
    }
}

private suspend fun stageFieldMutationBeforePush(
    dao: RocheDao,
    itemId: String,
    isAsync: Boolean,
    applyDirty: (PendingMutationEntity) -> PendingMutationEntity,
): Boolean {
    var handledLocally = false
    dao.executeInTransaction {
        val existing = dao.getPendingMutation(itemId)
        if (existing?.kind == PendingEntityKind.LOCAL_ITEM) {
            dao.upsertPendingMutation(applyDirty(existing))
            handledLocally = true
        } else if (isAsync) {
            val base = existing ?: PendingMutationEntity(entityId = itemId)
            upsertOrCleanPendingMutation(dao = dao, mutation = applyDirty(base))
        }
    }
    return handledLocally
}

private suspend fun reconcileSyncFieldPushInTx(
    dao: RocheDao,
    itemId: String,
    pushed: Boolean,
    applyDirty: (PendingMutationEntity) -> PendingMutationEntity,
    clearDirtyIfMatches: (PendingMutationEntity) -> PendingMutationEntity,
) {
    dao.executeInTransaction {
        val latest = dao.getPendingMutation(itemId)
        if (latest?.kind == PendingEntityKind.LOCAL_ITEM) {
            dao.upsertPendingMutation(applyDirty(latest))
        } else if (!pushed) {
            val base = latest ?: PendingMutationEntity(entityId = itemId)
            upsertOrCleanPendingMutation(dao = dao, mutation = applyDirty(base))
        } else if (latest != null) {
            upsertOrCleanPendingMutation(dao = dao, mutation = clearDirtyIfMatches(latest))
        }
    }
}

private suspend fun reconcileAsyncFieldPushInTx(
    dao: RocheDao,
    itemId: String,
    clearDirtyIfMatches: (PendingMutationEntity) -> PendingMutationEntity,
) {
    dao.executeInTransaction {
        val latest = dao.getPendingMutation(itemId)
        if (latest != null && latest.kind != PendingEntityKind.LOCAL_ITEM) {
            upsertOrCleanPendingMutation(dao = dao, mutation = clearDirtyIfMatches(latest))
        }
    }
}

@Suppress("LongParameterList")
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

@Suppress("LongParameterList")
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

@Suppress("LongParameterList")
internal suspend fun syncReadProgressMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    progress: Double,
    explicitReadAt: String? = null,
    pushImmediately: Boolean = true,
    dispatcher: BackgroundMutationDispatcher = BackgroundMutationDispatcher(),
) {
    val applyDirty: (PendingMutationEntity) -> PendingMutationEntity = { current ->
        if (explicitReadAt != null) {
            current.copy(dirtyReadProgress = progress, dirtyReadAt = explicitReadAt)
        } else {
            current.copy(dirtyReadProgress = progress)
        }
    }
    if (!pushImmediately) {
        dao.executeInTransaction {
            val existing = dao.getPendingMutation(itemId)
            val base = existing ?: PendingMutationEntity(entityId = itemId)
            upsertOrCleanPendingMutation(dao = dao, mutation = applyDirty(base))
        }
        return
    }
    syncItemFieldMutation(
        dao = dao,
        itemId = itemId,
        dispatcher = dispatcher,
        applyDirty = applyDirty,
        clearDirtyIfMatches = { current ->
            val clearProgress =
                if (current.dirtyReadProgress == progress) null else current.dirtyReadProgress
            val clearReadAt =
                if (explicitReadAt != null && current.dirtyReadAt == explicitReadAt) {
                    null
                } else {
                    current.dirtyReadAt
                }
            current.copy(dirtyReadProgress = clearProgress, dirtyReadAt = clearReadAt)
        },
        pushRemote = {
            pushItemUpdateBestEffort(
                apiClient = apiClient,
                settings = settings,
                itemId = itemId,
                readProgress = progress,
                readAt = explicitReadAt,
            )
        },
    )
}

@Suppress("LongParameterList")
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

@Suppress("LongParameterList")
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

@Suppress("LongParameterList")
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

@Suppress("LongParameterList")
internal suspend fun syncAddTagMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    existingTag: TagEntity,
    normalizedName: String,
    dispatcher: BackgroundMutationDispatcher = BackgroundMutationDispatcher(),
) {
    var handledLocal = false
    dao.executeInTransaction {
        val existing = dao.getPendingMutation(itemId)
        if (existing?.kind == PendingEntityKind.LOCAL_ITEM) {
            dao.upsertPendingMutation(applyDirtyTagChange(existing, normalizedName, isAdd = true))
            handledLocal = true
        } else if (dispatcher.isAsync) {
            val base = existing ?: PendingMutationEntity(entityId = itemId)
            upsertOrCleanPendingMutation(
                dao,
                applyDirtyTagChange(base, normalizedName, isAdd = true),
            )
        }
    }
    if (handledLocal) return
    val pushAction: suspend () -> Unit = {
        val pushed =
            pushAddTagBestEffort(dao, apiClient, settings, itemId, existingTag, normalizedName)
        reconcileTagPushInTx(dao, itemId, normalizedName, isAdd = true, pushed = pushed)
    }
    if (dispatcher.isAsync) dispatcher.dispatchOrRun(itemId, pushAction) else pushAction()
}

@Suppress("LongParameterList")
internal suspend fun syncRemoveTagMutation(
    dao: RocheDao,
    apiClient: RocheApiClient,
    settings: SettingsRepository,
    itemId: String,
    existingTag: TagEntity,
    normalizedName: String,
    dispatcher: BackgroundMutationDispatcher = BackgroundMutationDispatcher(),
) {
    val (handledLocal, wasOfflineLocalAdd) =
        stageRemoveTagBeforePush(dao, itemId, existingTag.id, normalizedName, dispatcher.isAsync)
    if (handledLocal) return
    val pushAction: suspend () -> Unit = {
        val pushed =
            wasOfflineLocalAdd ||
                pushRemoveTagBestEffort(
                    dao,
                    apiClient,
                    settings,
                    itemId,
                    existingTag,
                    normalizedName,
                )
        reconcileTagPushInTx(dao, itemId, normalizedName, isAdd = false, pushed = pushed)
    }
    if (dispatcher.isAsync) dispatcher.dispatchOrRun(itemId, pushAction) else pushAction()
}

private suspend fun stageRemoveTagBeforePush(
    dao: RocheDao,
    itemId: String,
    tagId: String,
    normalizedName: String,
    isAsync: Boolean,
): Pair<Boolean, Boolean> {
    var handledLocal = false
    var wasOfflineLocalAdd = false
    dao.executeInTransaction {
        val existing = dao.getPendingMutation(itemId)
        val addedBefore = parseTagCsv(existing?.addedTagsCsv.orEmpty())
        wasOfflineLocalAdd = normalizedName in addedBefore && isLocalTagId(tagId)
        if (existing?.kind == PendingEntityKind.LOCAL_ITEM) {
            dao.upsertPendingMutation(applyDirtyTagChange(existing, normalizedName, isAdd = false))
            handledLocal = true
        } else if (isAsync) {
            val base = existing ?: PendingMutationEntity(entityId = itemId)
            val updated =
                if (wasOfflineLocalAdd) {
                    clearPushedTagChange(base, normalizedName)
                } else {
                    applyDirtyTagChange(base, normalizedName, isAdd = false)
                }
            upsertOrCleanPendingMutation(dao, updated)
            handledLocal = wasOfflineLocalAdd
        }
    }
    return handledLocal to wasOfflineLocalAdd
}

private suspend fun reconcileTagPushInTx(
    dao: RocheDao,
    itemId: String,
    normalizedName: String,
    isAdd: Boolean,
    pushed: Boolean,
) {
    dao.executeInTransaction {
        val latest = dao.getPendingMutation(itemId)
        if (latest?.kind == PendingEntityKind.LOCAL_ITEM) {
            dao.upsertPendingMutation(applyDirtyTagChange(latest, normalizedName, isAdd = isAdd))
        } else if (!pushed) {
            val base = latest ?: PendingMutationEntity(entityId = itemId)
            upsertOrCleanPendingMutation(
                dao,
                applyDirtyTagChange(base, normalizedName, isAdd = isAdd),
            )
        } else if (latest != null) {
            upsertOrCleanPendingMutation(dao, clearPushedTagChange(latest, normalizedName))
        }
    }
}

private fun applyDirtyTagChange(
    base: PendingMutationEntity,
    normalizedName: String,
    isAdd: Boolean,
): PendingMutationEntity {
    val added = parseTagCsv(base.addedTagsCsv).toMutableSet()
    val removed = parseTagCsv(base.removedTagsCsv).toMutableSet()
    if (isAdd) {
        added.add(normalizedName)
        removed.remove(normalizedName)
    } else {
        added.remove(normalizedName)
        removed.add(normalizedName)
    }
    return base.copy(
        addedTagsCsv = added.joinToString(","),
        removedTagsCsv = removed.joinToString(","),
    )
}

private fun clearPushedTagChange(
    base: PendingMutationEntity,
    normalizedName: String,
): PendingMutationEntity {
    val added = parseTagCsv(base.addedTagsCsv).toMutableSet().apply { remove(normalizedName) }
    val removed = parseTagCsv(base.removedTagsCsv).toMutableSet().apply { remove(normalizedName) }
    return base.copy(
        addedTagsCsv = added.joinToString(","),
        removedTagsCsv = removed.joinToString(","),
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

@Suppress("LongParameterList")
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

    dao.executeInTransaction {
        for (itemId in unarchivedIds) {
            recordBatchArchiveMutation(
                dao = dao,
                itemId = itemId,
                existing = dao.getPendingMutation(itemId),
                pushedToServer = pushedToServer,
            )
        }
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

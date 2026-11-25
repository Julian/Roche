package com.grayvines.roche.ui

import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.data.catalog.CollectionRankMeta
import com.grayvines.roche.data.catalog.ConsumptionStatus
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.UpdateCatalogEntryInput
import com.grayvines.roche.data.catalog.UpsertCatalogEntryInput
import com.grayvines.roche.ranking.EpRankingEngine
import com.grayvines.roche.ranking.RankClusterCandidate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val DEFAULT_QUEUE_LABEL = "Queue"

@Suppress("TooManyFunctions")
internal class MainCatalogActionsDelegate(private val host: MainDelegateHost) {
    private val catalogActionMutex = Mutex()
    private val rankDelegate = MainRankSessionDelegate(host)

    internal suspend fun execute(action: MainAction.CatalogMutation) {
        if (action is MainAction.RefreshCatalogEntryMetadata) {
            performRefreshCatalogMetadata(action.entryId)
            return
        }
        catalogActionMutex.withLock {
            if (!executeRankOrDraw(action) && !executeCatalogEntryAction(action)) {
                executeCatalogListOrWantAction(action)
            }
        }
    }

    private suspend fun executeRankOrDraw(action: MainAction.CatalogMutation): Boolean {
        when (action) {
            is MainAction.SetCollectionGeneratorEnabled ->
                performToggleGenerator(action.collectionIdOrSlug, action.enabled)
            is MainAction.ConfirmFinishDraw -> performFinishDraw(action.collectionId, action.notes)
            is MainAction.ConfirmAbandonDraw ->
                performAbandonDraw(action.collectionId, action.notes)
            is MainAction.StartRankingSession ->
                rankDelegate.startSession(action.collectionIdOrSlug, action.focusEntryId)
            is MainAction.SubmitRankCluster ->
                rankDelegate.submitCluster(action.ordering, action.excludedEntryIds)
            is MainAction.SetRankClusterSize ->
                rankDelegate.setClusterSize(
                    action.clusterSize,
                    action.reloadCluster,
                    action.manualExcludedIds,
                )
            is MainAction.SkipRankCluster ->
                rankDelegate.skipCluster(action.activeEntryIds, action.manualExcludedIds)
            MainAction.UndoLastRankRound -> rankDelegate.undoLastRound()
            MainAction.FinishRankEarly -> rankDelegate.finishEarly()
            MainAction.KeepRefiningRank -> rankDelegate.keepRefining()
            else -> return false
        }
        return true
    }

    private suspend fun executeCatalogEntryAction(action: MainAction.CatalogMutation): Boolean {
        when (action) {
            is MainAction.UpdateConsumptionLog -> performUpdateConsumptionLog(action)
            is MainAction.DeleteConsumptionLog -> performDeleteConsumptionLog(action.logId)
            is MainAction.ToggleCatalogStar -> performToggleCatalogStar(action.entryId)
            is MainAction.UpdateCatalogEntry ->
                performUpdateCatalogEntry(action.entryId, action.input)
            is MainAction.MarkFeedEntryFinished ->
                performMarkFeedEntryFinished(action.entryId, action.rankImmediately)
            is MainAction.UnfinishFeedEntry -> performUnfinishFeedEntry(action.entryId)
            is MainAction.SubmitQuickLog -> performSubmitQuickLog(action)
            else -> return false
        }
        return true
    }

    private suspend fun executeCatalogListOrWantAction(action: MainAction.CatalogMutation) {
        when (action) {
            is MainAction.ToggleCatalogWant -> performToggleCatalogWant(action.entryId)
            is MainAction.ToggleWantToRead -> performToggleWantToRead(action.entryId)
            is MainAction.SkipStartedEntry -> performSkipStartedEntry(action.entryId)
            is MainAction.AddCatalogEntryToList ->
                performAddCatalogEntryToList(action.collectionId, action.entryId)
            is MainAction.CreateCustomCatalogList -> performCreateCustomList(action.title)
            is MainAction.CompleteWantEntry ->
                performCompleteWantEntry(action.entryId, action.rankImmediately)
            is MainAction.RemoveWantEntry ->
                performRemoveWantEntry(action.collectionId, action.entryId)
            else -> Unit
        }
    }

    private suspend fun performToggleGenerator(
        collectionIdOrSlug: String,
        enabled: Boolean,
    ) {
        val meta =
            host.repository.catalog.resolveCollectionRankMeta(
                collectionIdOrSlug,
                includeMemberCount = false,
            )
        val title = meta?.title ?: "List"
        runSuspendCatching {
            host.repository.catalog.setCollectionGeneratorEnabled(collectionIdOrSlug, enabled)
        }
            .onSuccess { drawn ->
                val message =
                    when {
                        !enabled -> "Paused draw for $title"
                        drawn != null -> "$title draw active · ${drawn.title}"
                        else -> "$title pool complete"
                    }
                host.showStatusMessage(message)
            }
            .onFailure { err ->
                host.showStatusMessage("Draw error (${err.message ?: "error"})")
            }
    }

    private suspend fun performFinishDraw(
        collectionId: String,
        notes: String,
    ) {
        host.updateTransient { it.copy(drawNoteDialog = null) }
        runSuspendCatching { host.repository.catalog.finishActiveDraw(collectionId, notes) }
            .onSuccess { step ->
                val openedInList =
                    rankDelegate.openSession(
                        collectionIdOrSlug = step.collectionId,
                        focusEntryId = step.entryId,
                    )
                val openedRank =
                    openedInList ||
                        host.repository.catalog.resolveEntrySystemListSlug(step.entryId)?.let { slug
                            ->
                            rankDelegate.openSession(slug, step.entryId)
                        } == true
                if (!openedRank) {
                    val nextPart = step.nextDraw?.let { " · Next: ${it.title}" }.orEmpty()
                    host.showStatusMessage("Logged to Diary$nextPart")
                }
            }
            .onFailure { err ->
                host.showStatusMessage("Could not finish draw (${err.message ?: "error"})")
            }
    }

    private suspend fun performAbandonDraw(
        collectionId: String,
        notes: String,
    ) {
        host.updateTransient { it.copy(drawNoteDialog = null) }
        runSuspendCatching { host.repository.catalog.abandonActiveDraw(collectionId, notes) }
            .onSuccess { step ->
                val nextPart = step.nextDraw?.let { " · Next: ${it.title}" }.orEmpty()
                host.showStatusMessage("Skipped draw$nextPart")
            }
            .onFailure { err ->
                host.showStatusMessage("Could not skip draw (${err.message ?: "error"})")
            }
    }

    private suspend fun performUpdateConsumptionLog(action: MainAction.UpdateConsumptionLog) {
        runSuspendCatching {
            host.repository.catalog.updateConsumptionLog(
                logId = action.logId,
                status = action.status,
                notes = action.notes,
                clearNotes = action.clearNotes,
                tsIso = action.tsIso,
            )
        }
            .onSuccess { updated ->
                if (updated) host.showStatusMessage("Updated diary entry")
            }
            .onFailure { err ->
                host.showStatusMessage("Could not update diary (${err.message ?: "error"})")
            }
    }

    private suspend fun performDeleteConsumptionLog(logId: String) {
        runSuspendCatching { host.repository.catalog.deleteConsumptionLog(logId) }
            .onSuccess { deleted ->
                if (deleted) host.showStatusMessage("Deleted diary entry")
            }
            .onFailure { err ->
                host.showStatusMessage("Could not delete diary (${err.message ?: "error"})")
            }
    }

    private suspend fun performToggleCatalogStar(entryId: String) {
        val isStarred = host.repository.catalog.isEntryStarred(entryId)
        if (isStarred) {
            performRemoveWantEntry(STARRED_COLLECTION_ID, entryId)
            return
        }
        runSuspendCatching {
            host.repository.catalog.addCollectionEntry(STARRED_COLLECTION_ID, entryId)
        }
            .onSuccess { host.showStatusMessage("Added to Stars") }
            .onFailure { err ->
                host.showStatusMessage("Could not star (${err.message ?: "error"})")
            }
    }

    private suspend fun performToggleCatalogWant(entryId: String) {
        val existingWantCols = host.repository.catalog.findWantCollectionsForEntry(entryId)
        if (existingWantCols.isNotEmpty()) {
            removeFromWantQueuesWithUndo(existingWantCols, entryId, label = DEFAULT_QUEUE_LABEL)
            return
        }
        runSuspendCatching { host.repository.catalog.wantExistingEntry(entryId) }
            .onSuccess { pair ->
                val title = pair?.second ?: DEFAULT_QUEUE_LABEL
                host.showStatusMessage("Added to $title")
            }
            .onFailure { err ->
                host.showStatusMessage("Could not queue (${err.message ?: "error"})")
            }
    }

    private suspend fun performToggleWantToRead(entryId: String) {
        val existingWantCols = host.repository.catalog.findWantCollectionsForEntry(entryId)
        host.updateTransient { it.copy(contextSheetEntryId = null) }
        if (existingWantCols.isNotEmpty()) {
            removeFromWantQueuesWithUndo(existingWantCols, entryId, label = DEFAULT_QUEUE_LABEL)
            return
        }
        runSuspendCatching {
            host.repository.catalog.addCollectionEntry(WANT_TO_READ_COLLECTION_ID, entryId)
        }
            .onSuccess { host.showStatusMessage("Added to $DEFAULT_QUEUE_LABEL") }
            .onFailure { err ->
                host.showStatusMessage(
                    "Could not add to $DEFAULT_QUEUE_LABEL (${err.message ?: "error"})"
                )
            }
    }

    private suspend fun removeFromWantQueuesWithUndo(
        collectionIds: List<String>,
        entryId: String,
        label: String,
    ) {
        runSuspendCatching {
            host.repository.catalog.removeEntryFromCollections(collectionIds, entryId)
        }
            .onSuccess { removedCols ->
                if (removedCols.isNotEmpty()) {
                    host.showUndoAction(
                        UndoActionEvent(
                            message = "Removed from $label",
                            kind =
                                UndoActionKind.RestoreCollectionEntry(
                                    collectionId = removedCols.first(),
                                    entryId = entryId,
                                    label = label,
                                    collectionIds = removedCols,
                                ),
                        )
                    )
                }
            }
            .onFailure { err ->
                host.showStatusMessage("Could not remove from $label (${err.message ?: "error"})")
            }
    }

    private suspend fun performSkipStartedEntry(entryId: String) {
        runSuspendCatching {
            host.repository.catalog.logExistingEntry(entryId, ConsumptionStatus.ABANDONED, null)
        }
            .onSuccess { host.showStatusMessage("Marked skipped") }
            .onFailure { err ->
                host.showStatusMessage("Could not skip item (${err.message ?: "error"})")
            }
    }

    private suspend fun performAddCatalogEntryToList(collectionId: String, entryId: String) {
        val meta =
            host.repository.catalog.resolveCollectionRankMeta(
                collectionId,
                includeMemberCount = false,
            )
        val title = meta?.title ?: "list"
        runSuspendCatching { host.repository.catalog.addCollectionEntry(collectionId, entryId) }
            .onSuccess { host.showStatusMessage("Added to $title") }
            .onFailure { err ->
                host.showStatusMessage("Could not add to list (${err.message ?: "error"})")
            }
    }

    private suspend fun performUpdateCatalogEntry(
        entryId: String,
        input: UpdateCatalogEntryInput,
    ) {
        runSuspendCatching { host.repository.catalog.updateEntry(entryId, input) }
            .onSuccess { updated ->
                host.updateTransient { it.copy(editingCatalogEntryId = null) }
                host.showStatusMessage("Updated \"${updated.title.take(SNACKBAR_TITLE_LIMIT)}\"")
            }
            .onFailure { err ->
                host.showStatusMessage("Could not update entry (${err.message ?: "error"})")
            }
    }

    private suspend fun performRefreshCatalogMetadata(entryId: String) {
        val cleanId = entryId.trim()
        if (cleanId.isEmpty() || cleanId in host.currentTransientState().enrichingCatalogEntryIds) {
            return
        }
        host.updateTransient {
            it.copy(enrichingCatalogEntryIds = it.enrichingCatalogEntryIds + cleanId)
        }
        try {
            host.repository
                .enrichCatalogEntryOnline(cleanId)
                .onSuccess { enriched ->
                    val msg =
                        if (enriched) {
                            "Refreshed metadata"
                        } else {
                            "No additional metadata found"
                        }
                    host.showStatusMessage(msg)
                }
                .onFailure { err ->
                    host.showStatusMessage("Refresh failed (${err.message ?: "error"})")
                }
        } finally {
            host.updateTransient {
                it.copy(enrichingCatalogEntryIds = it.enrichingCatalogEntryIds - cleanId)
            }
        }
    }

    private suspend fun performMarkFeedEntryFinished(
        entryId: String,
        rankImmediately: Boolean,
    ) {
        val resolvedId = host.repository.markFeedItemFinished(entryId)
        host.cleanupRemovedEntries(setOf(entryId))
        if (resolvedId == null) {
            host.showStatusMessage("Entry not found")
            return
        }
        if (rankImmediately) {
            val pair = host.repository.prepareFeedItemForRank(entryId)
            val slug = (pair?.second ?: EntryKind.ARTICLE).systemListSlug
            val opened = rankDelegate.openSession(slug, resolvedId)
            if (!opened) {
                host.showStatusMessage(
                    "Logged to Diary (rank #1 — log at least 2 items to compare)"
                )
            }
        } else {
            host.showStatusMessage("Marked finished & logged to Diary")
        }
    }

    private suspend fun performUnfinishFeedEntry(entryId: String) {
        val updated = host.repository.unfinishFeedItem(entryId)
        host.updateTransient { it.copy(contextSheetEntryId = null) }
        if (updated) {
            host.showStatusMessage("Undid Finished · Marked new")
        } else {
            host.showStatusMessage("Entry not found")
        }
    }

    private suspend fun performSubmitQuickLog(action: MainAction.SubmitQuickLog) {
        val cleanTitle = action.title.trim()
        if (cleanTitle.isEmpty()) {
            host.showStatusMessage("Enter a title")
            return
        }
        val targetListId = action.targetCollectionId?.trim()?.takeIf { it.isNotEmpty() }
        runSuspendCatching { saveQuickLogEntry(action, cleanTitle, targetListId) }
            .onSuccess { entryId ->
                handleQuickLogSaved(action, cleanTitle, targetListId, entryId)
            }
            .onFailure { err ->
                host.showStatusMessage("Could not save (${err.message ?: "error"})")
            }
    }

    private suspend fun saveQuickLogEntry(
        action: MainAction.SubmitQuickLog,
        cleanTitle: String,
        targetListId: String?,
    ): String {
        val creators = action.creatorsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val input =
            UpsertCatalogEntryInput(
                id = action.existingEntryId?.trim()?.takeIf { it.isNotEmpty() },
                kind = action.kind,
                title = cleanTitle,
                creators = creators,
                releaseYear = action.releaseYear,
                thumbnailUrl = action.thumbnailUrl?.trim()?.takeIf { it.isNotEmpty() },
                identifiers = action.identifiers,
            )
        val savedId =
            when {
                action.addToListOnly -> host.repository.catalog.upsertEntry(input)
                action.addToWant -> host.repository.catalog.wantEntry(input)
                else -> host.repository.catalog.logEntry(input, action.status, action.notes)
            }
        if (targetListId != null) {
            host.repository.catalog.addCollectionEntry(targetListId, savedId)
        }
        return savedId
    }

    private suspend fun handleQuickLogSaved(
        action: MainAction.SubmitQuickLog,
        cleanTitle: String,
        targetListId: String?,
        entryId: String,
    ) {
        val listLabel = host.currentTransientState().quickLogTargetCollectionTitle
        host.updateTransient {
            it.copy(
                isQuickLogSheetOpen = false,
                quickLogTargetCollectionId = null,
                quickLogTargetCollectionTitle = null,
                quickLogPrefillKind = null,
                quickLogPrefillTitle = "",
                quickLogPrefillCreators = "",
                quickLogPrefillReleaseYear = null,
                quickLogExistingEntryId = null,
                optimisticFavorites = it.optimisticFavorites - entryId,
            )
        }
        if (!action.addToWant && !action.addToListOnly && action.rankAfterLog) {
            val rankTarget = targetListId ?: action.kind.systemListSlug
            if (!rankDelegate.openSession(rankTarget, entryId)) {
                host.showStatusMessage(
                    "Logged \"$cleanTitle\" (rank #1 — log at least 2 items to compare)"
                )
            }
        } else {
            val verb =
                when {
                    action.addToListOnly -> "Added to ${listLabel ?: "list"}"
                    action.addToWant && listLabel != null -> "Added to Want Queue & $listLabel"
                    action.addToWant -> "Added to Want Queue"
                    action.status == ConsumptionStatus.STARTED -> "Started (In Progress)"
                    else -> "Logged to Diary"
                }
            host.showStatusMessage("$verb · $cleanTitle")
        }
    }

    private suspend fun performCreateCustomList(rawTitle: String) {
        runSuspendCatching { host.repository.catalog.createCustomList(rawTitle) }
            .onSuccess { specId ->
                host.updateTransient {
                    it.copy(
                        companionSubTab = CompanionSubTab.LISTS,
                        selectedCatalogCollectionId = specId,
                    )
                }
                host.showStatusMessage("Created list \"${rawTitle.trim()}\"")
            }
            .onFailure { err ->
                host.showStatusMessage("Could not create list (${err.message ?: "error"})")
            }
    }

    private suspend fun performCompleteWantEntry(entryId: String, rankImmediately: Boolean) {
        val isLiveReaderItem = host.latestEntries().any { it.id == entryId }
        if (isLiveReaderItem) {
            performMarkFeedEntryFinished(entryId, rankImmediately)
            return
        }
        runSuspendCatching {
            host.repository.catalog.logExistingEntry(entryId, ConsumptionStatus.CONSUMED, null)
        }
            .onSuccess { resolvedId ->
                val targetId = resolvedId ?: entryId
                val listSlug = host.repository.catalog.resolveEntrySystemListSlug(targetId)
                if (rankImmediately && listSlug != null) {
                    val opened = rankDelegate.openSession(listSlug, targetId)
                    if (!opened) {
                        host.showStatusMessage(
                            "Completed & logged to Diary (rank #1 — log at least 2 items to compare)"
                        )
                    }
                } else {
                    host.showStatusMessage("Completed & logged to Diary")
                }
            }
            .onFailure { err ->
                host.showStatusMessage("Could not complete item (${err.message ?: "error"})")
            }
    }

    private suspend fun performRemoveWantEntry(collectionId: String, entryId: String) {
        val meta =
            host.repository.catalog.resolveCollectionRankMeta(
                collectionId,
                includeMemberCount = false,
            )
        val label =
            when {
                collectionId == STARRED_COLLECTION_ID -> "Stars"
                meta?.scopeCollectionId != null -> "Want Queue"
                else -> meta?.title ?: "list"
            }
        runSuspendCatching {
            host.repository.catalog.removeCollectionEntry(collectionId, entryId)
        }
            .onSuccess {
                host.updateTransient { cur ->
                    cur.copy(optimisticFavorites = cur.optimisticFavorites - entryId)
                }
                host.showUndoAction(
                    UndoActionEvent(
                        message = "Removed from $label",
                        kind = UndoActionKind.RestoreCollectionEntry(collectionId, entryId, label),
                    )
                )
            }
            .onFailure { err ->
                host.showStatusMessage("Could not remove (${err.message ?: "error"})")
            }
    }
}

@Suppress("TooManyFunctions")
internal class MainRankSessionDelegate(private val host: MainDelegateHost) {
    suspend fun startSession(collectionIdOrSlug: String, focusEntryId: String?) {
        if (!openSession(collectionIdOrSlug, focusEntryId)) {
            host.showStatusMessage("Need at least 2 entries in this list to compare")
        }
    }

    suspend fun openSession(collectionIdOrSlug: String, focusEntryId: String?): Boolean {
        val rankMeta = host.repository.catalog.resolveCollectionRankMeta(collectionIdOrSlug)
        val preferredK = readPreferredClusterSize()
        val cluster =
            host.repository.catalog.selectNextRankCluster(
                collectionIdOrSlug = collectionIdOrSlug,
                focusEntryId = focusEntryId,
                roundIndex = 0,
                maxK = preferredK,
            )
        val nextSession =
            buildInitialRankSession(
                collectionIdOrSlug = collectionIdOrSlug,
                focusEntryId = focusEntryId,
                rankMeta = rankMeta,
                preferredK = preferredK,
                cluster = cluster,
            )
        if (nextSession != null) {
            host.updateTransient {
                it.copy(rankSession = nextSession, catalogDetailStack = emptyList())
            }
        }
        return nextSession != null
    }

    private suspend fun buildInitialRankSession(
        collectionIdOrSlug: String,
        focusEntryId: String?,
        rankMeta: CollectionRankMeta?,
        preferredK: Int,
        cluster: List<RankClusterCandidate>,
    ): ActiveRankSessionUiState? {
        val title = rankMeta?.title ?: "Rankings"
        val scopeId = rankMeta?.scopeCollectionId
        val memberCount = (rankMeta?.memberCount ?: 0).coerceAtLeast(cluster.size)
        if (cluster.isNotEmpty()) {
            val provisionalRank = focusEntryId?.let {
                host.repository.catalog.resolveProvisionalFocusRank(
                    collectionIdOrSlug = collectionIdOrSlug,
                    focusEntryId = it,
                )
            }
            return ActiveRankSessionUiState(
                collectionIdOrSlug = collectionIdOrSlug,
                collectionTitle = title,
                scopeCollectionId = scopeId,
                focusEntryId = focusEntryId,
                roundIndex = 0,
                targetClusterSize = preferredK,
                loadedMaxK = preferredK,
                collectionMemberCount = memberCount,
                cluster = cluster,
                settlement = null,
                provisionalRank = provisionalRank,
            )
        }
        val settlement =
            focusEntryId
                ?.let { host.repository.catalog.resolveEntrySettlement(it, collectionIdOrSlug) }
                ?.takeIf { it.totalCount >= MIN_RANKABLE_COLLECTION_COUNT }
        return settlement?.let {
            ActiveRankSessionUiState(
                collectionIdOrSlug = collectionIdOrSlug,
                collectionTitle = title,
                scopeCollectionId = scopeId,
                focusEntryId = focusEntryId,
                roundIndex = 0,
                targetClusterSize = preferredK,
                loadedMaxK = preferredK,
                collectionMemberCount = it.totalCount.coerceAtLeast(memberCount),
                cluster = emptyList(),
                settlement = it,
            )
        }
    }

    suspend fun setClusterSize(
        clusterSize: Int,
        reloadCluster: Boolean,
        manualExcludedIds: Set<String> = emptySet(),
    ) {
        val clampedK = clusterSize.coerceIn(EpRankingEngine.MIN_K, EpRankingEngine.MAX_K_DEFAULT)
        var captured: ActiveRankSessionUiState? = null
        host.updateTransient { state ->
            val cur = state.rankSession
            if (cur == null) {
                state
            } else {
                val nextExcluded =
                    if (reloadCluster) {
                        cur.excludedOpponents + manualExcludedIds
                    } else {
                        cur.excludedOpponents
                    }
                val updated =
                    cur.copy(targetClusterSize = clampedK, excludedOpponents = nextExcluded)
                captured = updated
                state.copy(rankSession = updated)
            }
        }
        runSuspendCatching {
            host.settingsRepository.update { it.copy(rankClusterSize = clampedK) }
        }
        val session = captured ?: return
        if (!reloadCluster) return
        val initialReload =
            selectDeduplicatedNextCluster(
                session = session,
                nextRound = session.roundIndex,
                forceRandomNext = false,
                extraExcluded = session.skippedOpponents,
            )
        val reloaded =
            if (initialReload.isNotEmpty() || session.skippedOpponents.isEmpty()) {
                initialReload
            } else {
                selectDeduplicatedNextCluster(
                    session = session,
                    nextRound = session.roundIndex,
                    forceRandomNext = false,
                    extraExcluded = emptySet(),
                )
            }
        updateLiveRankSession(session) { cur ->
            cur.copy(
                targetClusterSize = clampedK,
                loadedMaxK = clampedK,
                cluster = reloaded.ifEmpty { cur.cluster },
                settlement = null,
            )
        }
    }

    suspend fun submitCluster(
        ordering: List<List<String>>,
        excludedEntryIds: Set<String> = emptySet(),
    ) {
        val session = host.currentTransientState().rankSession ?: return
        val compId = runSuspendCatching {
            host.repository.catalog.recordComparison(
                ordering = ordering,
                scopeCollectionId = session.scopeCollectionId,
            )
        }
            .getOrElse { err ->
                host.showStatusMessage("Invalid ordering (${err.message ?: "error"})")
                return
            }
        val judgedSet = ordering.flatten().toSet()
        val nextExcluded = session.excludedOpponents + excludedEntryIds - judgedSet
        val undoStep =
            RankRoundUndoStep(
                comparisonId = compId,
                previousRoundIndex = session.roundIndex,
                previousCluster = session.cluster,
                previousJudgedClusters = session.judgedClusters,
                previousExcludedOpponents = session.excludedOpponents,
                previousTargetClusterSize = session.targetClusterSize,
                previousLoadedMaxK = session.loadedMaxK,
            )
        advanceRankSession(
            session =
                session.copy(
                    judgedClusters = session.judgedClusters + listOf(judgedSet),
                    skippedOpponents = emptySet(),
                    excludedOpponents = nextExcluded,
                    history = session.history + undoStep,
                ),
            forceRandomNext = false,
        )
    }

    suspend fun skipCluster(
        activeEntryIds: List<String> = emptyList(),
        manualExcludedIds: Set<String> = emptySet(),
    ) {
        val session = host.currentTransientState().rankSession ?: return
        val currentSet = activeEntryIds.ifEmpty { session.cluster.map { it.entryId } }.toSet()
        val currentOpponents = currentSet.filterTo(HashSet()) { it != session.focusEntryId }
        val combinedSkipped = session.skippedOpponents + currentOpponents
        val nextExcluded = session.excludedOpponents + manualExcludedIds
        val attempts =
            listOf(
                    combinedSkipped + nextExcluded to combinedSkipped,
                    combinedSkipped to combinedSkipped,
                    currentOpponents to currentOpponents,
                    emptySet<String>() to emptySet(),
                )
                .distinctBy { it.first }
        val chosen = pickSkipClusterAttempt(session, currentSet, attempts)
        if (chosen == null) {
            host.showStatusMessage("No other combinations to compare right now")
            return
        }
        updateLiveRankSession(session) { cur ->
            cur.copy(
                loadedMaxK = cur.targetClusterSize,
                cluster = chosen.first,
                skippedOpponents = chosen.second,
                excludedOpponents = nextExcluded,
                settlement = null,
            )
        }
    }

    private suspend fun pickSkipClusterAttempt(
        session: ActiveRankSessionUiState,
        currentSet: Set<String>,
        attempts: List<Pair<Set<String>, Set<String>>>,
    ): Pair<List<RankClusterCandidate>, Set<String>>? {
        for ((excluded, nextSkipped) in attempts) {
            val cluster =
                host.repository.catalog.selectNextRankCluster(
                    collectionIdOrSlug = session.collectionIdOrSlug,
                    focusEntryId = session.focusEntryId,
                    roundIndex = session.roundIndex,
                    forceRandomNext = true,
                    maxK = session.targetClusterSize,
                    excludedOpponentIds = excluded,
                )
            if (cluster.isNotEmpty() && cluster.mapTo(HashSet()) { it.entryId } != currentSet) {
                return cluster to nextSkipped
            }
        }
        return null
    }

    suspend fun undoLastRound() {
        val session = host.currentTransientState().rankSession ?: return
        val lastStep = session.history.lastOrNull() ?: return
        runSuspendCatching { host.repository.catalog.deleteComparison(lastStep.comparisonId) }
            .onFailure { err ->
                host.showStatusMessage("Could not undo round (${err.message ?: "error"})")
                return
            }
        val restoredK =
            lastStep.previousTargetClusterSize.coerceIn(
                EpRankingEngine.MIN_K,
                EpRankingEngine.MAX_K_DEFAULT,
            )
        if (restoredK != session.targetClusterSize) {
            runSuspendCatching {
                host.settingsRepository.update { it.copy(rankClusterSize = restoredK) }
            }
        }
        val provisionalRank =
            session.focusEntryId?.let {
                host.repository.catalog.resolveProvisionalFocusRank(session.collectionIdOrSlug, it)
            }
        updateLiveRankSession(session) { cur ->
            cur.copy(
                roundIndex = lastStep.previousRoundIndex,
                targetClusterSize = restoredK,
                loadedMaxK = lastStep.previousLoadedMaxK,
                cluster = lastStep.previousCluster,
                judgedClusters = lastStep.previousJudgedClusters,
                skippedOpponents = emptySet(),
                excludedOpponents = lastStep.previousExcludedOpponents,
                history = cur.history.dropLast(1),
                settlement = null,
                provisionalRank = provisionalRank,
            )
        }
    }

    suspend fun finishEarly() {
        val session = host.currentTransientState().rankSession ?: return
        val settlement =
            session.focusEntryId?.let {
                host.repository.catalog.resolveEntrySettlement(it, session.collectionIdOrSlug)
            }
        if (settlement != null) {
            updateLiveRankSession(session) { cur ->
                cur.copy(cluster = emptyList(), settlement = settlement)
            }
        } else {
            host.updateTransient { it.copy(rankSession = null) }
        }
    }

    suspend fun keepRefining() {
        val session = host.currentTransientState().rankSession ?: return
        val extendedCap = session.roundIndex + MAX_FOCUSED_RANK_ROUNDS
        val nextCluster =
            selectClusterWithFallback(
                session = session,
                roundIndex = session.roundIndex,
                forceRandomNext = true,
            )
        if (nextCluster.isEmpty()) {
            host.showStatusMessage("No more entries available to compare")
            return
        }
        updateLiveRankSession(session) { cur ->
            cur.copy(
                roundCap = extendedCap,
                loadedMaxK = cur.targetClusterSize,
                cluster = nextCluster,
                judgedClusters = emptyList(),
                skippedOpponents = emptySet(),
                settlement = null,
            )
        }
    }

    private suspend fun advanceRankSession(
        session: ActiveRankSessionUiState,
        forceRandomNext: Boolean,
    ) {
        val nextRound = session.roundIndex + 1
        val nextCluster = selectDeduplicatedNextCluster(session, nextRound, forceRandomNext)
        if (nextCluster.isNotEmpty()) {
            val provisionalRank =
                session.focusEntryId?.let {
                    host.repository.catalog.resolveProvisionalFocusRank(
                        session.collectionIdOrSlug,
                        it,
                    )
                }
            updateLiveRankSession(session) {
                session.copy(
                    roundIndex = nextRound,
                    loadedMaxK = session.targetClusterSize,
                    cluster = nextCluster,
                    settlement = null,
                    provisionalRank = provisionalRank,
                )
            }
            return
        }
        val settlement =
            session.focusEntryId?.let {
                host.repository.catalog.resolveEntrySettlement(it, session.collectionIdOrSlug)
            }
        if (settlement != null) {
            updateLiveRankSession(session) {
                session.copy(
                    roundIndex = nextRound,
                    cluster = emptyList(),
                    settlement = settlement,
                )
            }
        } else {
            host.updateTransient { state ->
                if (state.rankSession == null) state else state.copy(rankSession = null)
            }
            host.showStatusMessage("Rankings settled for ${session.collectionTitle}")
        }
    }

    private suspend fun selectDeduplicatedNextCluster(
        session: ActiveRankSessionUiState,
        nextRound: Int,
        forceRandomNext: Boolean,
        extraExcluded: Set<String> = emptySet(),
    ): List<RankClusterCandidate> {
        if (session.focusEntryId != null && nextRound >= session.roundCap) {
            return emptyList()
        }
        val primaryPass =
            findUncoveredClusterPass(
                session = session,
                nextRound = nextRound,
                forceRandomNext = forceRandomNext,
                baseExcluded = session.excludedOpponents + extraExcluded,
            )
        if (primaryPass.isNotEmpty() || session.excludedOpponents.isEmpty()) {
            return primaryPass
        }
        return findUncoveredClusterPass(
            session = session,
            nextRound = nextRound,
            forceRandomNext = forceRandomNext,
            baseExcluded = extraExcluded,
        )
    }

    private suspend fun findUncoveredClusterPass(
        session: ActiveRankSessionUiState,
        nextRound: Int,
        forceRandomNext: Boolean,
        baseExcluded: Set<String>,
    ): List<RankClusterCandidate> {
        val primary =
            host.repository.catalog.selectNextRankCluster(
                collectionIdOrSlug = session.collectionIdOrSlug,
                focusEntryId = session.focusEntryId,
                roundIndex = nextRound,
                forceRandomNext = forceRandomNext,
                maxK = session.targetClusterSize,
                excludedOpponentIds = baseExcluded,
            )
        if (primary.isEmpty() || !isAlreadyCoveredCluster(primary, session.judgedClusters)) {
            return primary
        }
        return if (session.focusEntryId != null) {
            retryFocusedUncoveredCluster(session, nextRound, primary, baseExcluded)
        } else {
            retryGeneralUncoveredCluster(session, nextRound, baseExcluded)
        }
    }

    private suspend fun retryFocusedUncoveredCluster(
        session: ActiveRankSessionUiState,
        nextRound: Int,
        colliding: List<RankClusterCandidate>,
        baseExcluded: Set<String>,
    ): List<RankClusterCandidate> {
        val focusId = session.focusEntryId ?: return emptyList()
        val previouslyFaced =
            session.judgedClusters
                .filter { focusId in it }
                .flatten()
                .filterTo(HashSet()) { it != focusId }
        val collidingOpponents = colliding.mapTo(HashSet()) { it.entryId }.apply { remove(focusId) }
        val fresh =
            host.repository.catalog.selectNextRankCluster(
                collectionIdOrSlug = session.collectionIdOrSlug,
                focusEntryId = focusId,
                roundIndex = nextRound,
                forceRandomNext = false,
                maxK = session.targetClusterSize,
                excludedOpponentIds = baseExcluded + previouslyFaced + collidingOpponents,
            )
        return fresh
            .takeUnless {
                it.isEmpty() || isAlreadyCoveredCluster(it, session.judgedClusters)
            }
            .orEmpty()
    }

    private suspend fun retryGeneralUncoveredCluster(
        session: ActiveRankSessionUiState,
        nextRound: Int,
        baseExcluded: Set<String>,
    ): List<RankClusterCandidate> {
        for (attempt in 0 until MAX_NON_FOCUSED_COVERED_RETRIES) {
            val cand =
                host.repository.catalog.selectNextRankCluster(
                    collectionIdOrSlug = session.collectionIdOrSlug,
                    focusEntryId = null,
                    roundIndex = nextRound + attempt,
                    forceRandomNext = true,
                    maxK = session.targetClusterSize,
                    excludedOpponentIds = baseExcluded,
                )
            if (cand.isNotEmpty() && !isAlreadyCoveredCluster(cand, session.judgedClusters)) {
                return cand
            }
        }
        return emptyList()
    }

    private suspend fun selectClusterWithFallback(
        session: ActiveRankSessionUiState,
        roundIndex: Int,
        forceRandomNext: Boolean,
    ): List<RankClusterCandidate> {
        val primary =
            host.repository.catalog.selectNextRankCluster(
                collectionIdOrSlug = session.collectionIdOrSlug,
                focusEntryId = session.focusEntryId,
                roundIndex = roundIndex,
                forceRandomNext = forceRandomNext,
                maxK = session.targetClusterSize,
                excludedOpponentIds = session.excludedOpponents,
            )
        if (primary.isNotEmpty() || session.excludedOpponents.isEmpty()) {
            return primary
        }
        return host.repository.catalog.selectNextRankCluster(
            collectionIdOrSlug = session.collectionIdOrSlug,
            focusEntryId = session.focusEntryId,
            roundIndex = roundIndex,
            forceRandomNext = forceRandomNext,
            maxK = session.targetClusterSize,
            excludedOpponentIds = emptySet(),
        )
    }

    private fun updateLiveRankSession(
        expected: ActiveRankSessionUiState,
        transform: (ActiveRankSessionUiState) -> ActiveRankSessionUiState,
    ) {
        host.updateTransient { state ->
            val cur = state.rankSession
            if (
                cur != null &&
                    cur.collectionIdOrSlug == expected.collectionIdOrSlug &&
                    cur.roundIndex == expected.roundIndex
            ) {
                state.copy(rankSession = transform(cur))
            } else {
                state
            }
        }
    }

    private suspend fun readPreferredClusterSize(): Int {
        val current =
            host.settingsRepository.latestSettings
                ?: runSuspendCatching { host.settingsRepository.prewarm() }.getOrNull()
        return (current?.rankClusterSize ?: EpRankingEngine.MAX_K_DEFAULT).coerceIn(
            EpRankingEngine.MIN_K,
            EpRankingEngine.MAX_K_DEFAULT,
        )
    }

    private fun isAlreadyCoveredCluster(
        cluster: List<RankClusterCandidate>,
        judgedClusters: List<Set<String>>,
    ): Boolean {
        val ids = cluster.map { it.entryId }
        return ids.isNotEmpty() && judgedClusters.any { prev -> prev.containsAll(ids) }
    }

    private companion object {
        private const val MIN_RANKABLE_COLLECTION_COUNT = 2
        private const val MAX_NON_FOCUSED_COVERED_RETRIES = 4
    }
}

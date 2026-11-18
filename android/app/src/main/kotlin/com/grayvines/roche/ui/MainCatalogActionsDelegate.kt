package com.grayvines.roche.ui

import com.grayvines.roche.data.ConsumptionStatus
import com.grayvines.roche.data.EntryKind
import com.grayvines.roche.data.RankClusterCandidate
import com.grayvines.roche.data.UpsertCatalogEntryInput
import com.grayvines.roche.data.runSuspendCatching
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Suppress("TooManyFunctions")
internal class MainCatalogActionsDelegate(private val vm: MainViewModel) {
    private val catalogActionMutex = Mutex()

    internal suspend fun execute(action: MainAction.Mutation) = catalogActionMutex.withLock {
        when (action) {
            is MainAction.SetCollectionGeneratorEnabled ->
                performToggleGenerator(action.collectionIdOrSlug, action.enabled)
            is MainAction.ConfirmFinishDraw -> performFinishDraw(action.collectionId, action.notes)
            is MainAction.ConfirmAbandonDraw ->
                performAbandonDraw(action.collectionId, action.notes)
            is MainAction.StartRankingSession ->
                performStartRankingSession(action.collectionIdOrSlug, action.focusEntryId)
            is MainAction.SubmitRankCluster -> performSubmitRankCluster(action.ordering)
            MainAction.SkipRankCluster -> performSkipRankCluster()
            is MainAction.MarkFeedEntryFinished ->
                performMarkFeedEntryFinished(action.entryId, action.rankImmediately)
            is MainAction.SubmitQuickLog -> performSubmitQuickLog(action)
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
        val meta = vm.repository.catalog.resolveCollectionRankMeta(collectionIdOrSlug)
        val title = meta?.title ?: "List"
        runSuspendCatching {
            vm.repository.catalog.setCollectionGeneratorEnabled(collectionIdOrSlug, enabled)
        }
            .onSuccess { drawn ->
                val message =
                    when {
                        !enabled -> "Paused draw for $title"
                        drawn != null -> "$title draw active · ${drawn.title}"
                        else -> "$title pool complete"
                    }
                vm.showStatusMessage(message)
            }
            .onFailure { err ->
                vm.showStatusMessage("Draw error (${err.message ?: "error"})")
            }
    }

    private suspend fun performFinishDraw(
        collectionId: String,
        notes: String,
    ) {
        vm.transientState.update { it.copy(drawNoteDialog = null) }
        runSuspendCatching { vm.repository.catalog.finishActiveDraw(collectionId, notes) }
            .onSuccess { step ->
                val openedInList =
                    openRankSessionForCollection(
                        collectionIdOrSlug = step.collectionId,
                        focusEntryId = step.entryId,
                    )
                val openedRank =
                    openedInList ||
                        vm.repository.catalog.resolveEntrySystemListSlug(step.entryId)?.let { slug
                            ->
                            openRankSessionForCollection(slug, step.entryId)
                        } == true
                if (!openedRank) {
                    val nextPart = step.nextDraw?.let { " · Next: ${it.title}" }.orEmpty()
                    vm.showStatusMessage("Logged to Diary$nextPart")
                }
            }
            .onFailure { err ->
                vm.showStatusMessage("Could not finish draw (${err.message ?: "error"})")
            }
    }

    private suspend fun performAbandonDraw(
        collectionId: String,
        notes: String,
    ) {
        vm.transientState.update { it.copy(drawNoteDialog = null) }
        runSuspendCatching { vm.repository.catalog.abandonActiveDraw(collectionId, notes) }
            .onSuccess { step ->
                val nextPart = step.nextDraw?.let { " · Next: ${it.title}" }.orEmpty()
                vm.showStatusMessage("Abandoned draw$nextPart")
            }
            .onFailure { err ->
                vm.showStatusMessage("Could not abandon draw (${err.message ?: "error"})")
            }
    }

    private suspend fun performStartRankingSession(
        collectionIdOrSlug: String,
        focusEntryId: String?,
    ) {
        val opened =
            openRankSessionForCollection(
                collectionIdOrSlug = collectionIdOrSlug,
                focusEntryId = focusEntryId,
            )
        if (!opened) {
            vm.showStatusMessage("Need at least 2 entries in this list to compare")
        }
    }

    private suspend fun openRankSessionForCollection(
        collectionIdOrSlug: String,
        focusEntryId: String?,
    ): Boolean {
        val rankMeta = vm.repository.catalog.resolveCollectionRankMeta(collectionIdOrSlug)
        val title = rankMeta?.title ?: "Rankings"
        val scopeId = rankMeta?.scopeCollectionId
        val cluster =
            vm.repository.catalog.selectNextRankCluster(
                collectionIdOrSlug = collectionIdOrSlug,
                focusEntryId = focusEntryId,
                roundIndex = 0,
            )
        val nextSession =
            if (cluster.isNotEmpty()) {
                ActiveRankSessionUiState(
                    collectionIdOrSlug = collectionIdOrSlug,
                    collectionTitle = title,
                    scopeCollectionId = scopeId,
                    focusEntryId = focusEntryId,
                    roundIndex = 0,
                    cluster = cluster,
                    settlement = null,
                )
            } else {
                val settlement =
                    focusEntryId
                        ?.let {
                            vm.repository.catalog.resolveEntrySettlement(it, collectionIdOrSlug)
                        }
                        ?.takeIf { it.totalCount >= MIN_RANKABLE_COLLECTION_COUNT }
                settlement?.let {
                    ActiveRankSessionUiState(
                        collectionIdOrSlug = collectionIdOrSlug,
                        collectionTitle = title,
                        scopeCollectionId = scopeId,
                        focusEntryId = focusEntryId,
                        roundIndex = 0,
                        cluster = emptyList(),
                        settlement = it,
                    )
                }
            }
        if (nextSession != null) {
            vm.transientState.update { it.copy(rankSession = nextSession) }
        }
        return nextSession != null
    }

    private suspend fun performSubmitRankCluster(ordering: List<List<String>>) {
        val session = vm.transientState.value.rankSession ?: return
        runSuspendCatching {
            vm.repository.catalog.recordComparison(
                ordering = ordering,
                scopeCollectionId = session.scopeCollectionId,
            )
        }
            .onFailure { err ->
                vm.showStatusMessage("Invalid ordering (${err.message ?: "error"})")
                return
            }
        val judgedSet = session.cluster.map { it.entryId }.toSet()
        advanceRankSession(
            session = session.copy(judgedClusters = session.judgedClusters + listOf(judgedSet)),
            forceRandomNext = false,
        )
    }

    private suspend fun performSkipRankCluster() {
        val session = vm.transientState.value.rankSession ?: return
        advanceRankSession(session = session, forceRandomNext = true)
    }

    private suspend fun advanceRankSession(
        session: ActiveRankSessionUiState,
        forceRandomNext: Boolean,
    ) {
        val nextRound = session.roundIndex + 1
        val nextCluster = selectDeduplicatedNextCluster(session, nextRound, forceRandomNext)
        if (nextCluster.isNotEmpty()) {
            vm.transientState.update {
                it.copy(
                    rankSession =
                        session.copy(
                            roundIndex = nextRound,
                            cluster = nextCluster,
                            settlement = null,
                        )
                )
            }
            return
        }
        val settlement =
            session.focusEntryId?.let {
                vm.repository.catalog.resolveEntrySettlement(it, session.collectionIdOrSlug)
            }
        if (settlement != null) {
            vm.transientState.update {
                it.copy(
                    rankSession =
                        session.copy(
                            roundIndex = nextRound,
                            cluster = emptyList(),
                            settlement = settlement,
                        )
                )
            }
        } else {
            vm.transientState.update { it.copy(rankSession = null) }
            vm.showStatusMessage("Rankings settled for ${session.collectionTitle}")
        }
    }

    private suspend fun selectDeduplicatedNextCluster(
        session: ActiveRankSessionUiState,
        nextRound: Int,
        forceRandomNext: Boolean,
    ): List<RankClusterCandidate> {
        if (session.focusEntryId != null && nextRound >= MAX_FOCUSED_RANK_ROUNDS) {
            return emptyList()
        }
        val candidate =
            vm.repository.catalog.selectNextRankCluster(
                collectionIdOrSlug = session.collectionIdOrSlug,
                focusEntryId = session.focusEntryId,
                roundIndex = nextRound,
                forceRandomNext = forceRandomNext,
            )
        if (candidate.isEmpty() || !isAlreadyCoveredCluster(candidate, session.judgedClusters)) {
            return candidate
        }
        return if (session.focusEntryId == null) {
            vm.repository.catalog
                .selectNextRankCluster(
                    collectionIdOrSlug = session.collectionIdOrSlug,
                    focusEntryId = null,
                    roundIndex = nextRound,
                    forceRandomNext = true,
                )
                .takeUnless { isAlreadyCoveredCluster(it, session.judgedClusters) }
                .orEmpty()
        } else {
            emptyList()
        }
    }

    private fun isAlreadyCoveredCluster(
        cluster: List<RankClusterCandidate>,
        judgedClusters: List<Set<String>>,
    ): Boolean {
        val ids = cluster.map { it.entryId }
        return ids.isNotEmpty() && judgedClusters.any { prev -> prev.containsAll(ids) }
    }

    private suspend fun performMarkFeedEntryFinished(
        entryId: String,
        rankImmediately: Boolean,
    ) {
        val resolvedId = vm.repository.markFeedItemFinished(entryId)
        vm.cleanupRemovedEntries(setOf(entryId))
        if (resolvedId == null) {
            vm.showStatusMessage("Entry not found")
            return
        }
        if (rankImmediately) {
            val pair = vm.repository.prepareFeedItemForRank(entryId)
            val slug = (pair?.second ?: EntryKind.ARTICLE).systemListSlug
            val opened = openRankSessionForCollection(slug, resolvedId)
            if (!opened) {
                vm.showStatusMessage("Logged to Diary (rank #1 — log at least 2 items to compare)")
            }
        } else {
            vm.showStatusMessage("Marked finished & logged to Diary")
        }
    }

    private suspend fun performSubmitQuickLog(action: MainAction.SubmitQuickLog) {
        val cleanTitle = action.title.trim()
        if (cleanTitle.isEmpty()) {
            vm.showStatusMessage("Enter a title")
            return
        }
        val targetListId = action.targetCollectionId?.trim()?.takeIf { it.isNotEmpty() }
        runSuspendCatching { saveQuickLogEntry(action, cleanTitle, targetListId) }
            .onSuccess { entryId ->
                handleQuickLogSaved(action, cleanTitle, targetListId, entryId)
            }
            .onFailure { err ->
                vm.showStatusMessage("Could not save (${err.message ?: "error"})")
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
                kind = action.kind,
                title = cleanTitle,
                creators = creators,
                releaseYear = action.releaseYear,
            )
        val savedId =
            when {
                action.addToListOnly -> vm.repository.catalog.upsertEntry(input)
                action.addToWant -> vm.repository.catalog.wantEntry(input)
                else -> vm.repository.catalog.logEntry(input, action.status, action.notes)
            }
        if (targetListId != null) {
            vm.repository.catalog.addCollectionEntry(targetListId, savedId)
        }
        return savedId
    }

    private suspend fun handleQuickLogSaved(
        action: MainAction.SubmitQuickLog,
        cleanTitle: String,
        targetListId: String?,
        entryId: String,
    ) {
        val listLabel = vm.transientState.value.quickLogTargetCollectionTitle
        vm.transientState.update {
            it.copy(
                isQuickLogSheetOpen = false,
                quickLogTargetCollectionId = null,
                quickLogTargetCollectionTitle = null,
                optimisticFavorites = it.optimisticFavorites - entryId,
            )
        }
        if (!action.addToWant && !action.addToListOnly && action.rankAfterLog) {
            val rankTarget = targetListId ?: action.kind.systemListSlug
            if (!openRankSessionForCollection(rankTarget, entryId)) {
                vm.showStatusMessage(
                    "Logged \"$cleanTitle\" (rank #1 — log at least 2 items to compare)"
                )
            }
        } else {
            val verb =
                when {
                    action.addToListOnly -> "Added to ${listLabel ?: "list"}"
                    action.addToWant && listLabel != null -> "Added to Want Queue & $listLabel"
                    action.addToWant -> "Added to Want Queue"
                    else -> "Logged to Diary"
                }
            vm.showStatusMessage("$verb · $cleanTitle")
        }
    }

    private suspend fun performCreateCustomList(rawTitle: String) {
        runSuspendCatching { vm.repository.catalog.createCustomList(rawTitle) }
            .onSuccess { specId ->
                vm.transientState.update {
                    it.copy(
                        companionSubTab = CompanionSubTab.LISTS,
                        selectedCatalogCollectionId = specId,
                    )
                }
                vm.showStatusMessage("Created list \"${rawTitle.trim()}\"")
            }
            .onFailure { err ->
                vm.showStatusMessage("Could not create list (${err.message ?: "error"})")
            }
    }

    private suspend fun performCompleteWantEntry(entryId: String, rankImmediately: Boolean) {
        runSuspendCatching {
            vm.repository.catalog.logExistingEntry(entryId, ConsumptionStatus.CONSUMED, null)
        }
            .onSuccess { resolvedId ->
                val targetId = resolvedId ?: entryId
                val listSlug = vm.repository.catalog.resolveEntrySystemListSlug(targetId)
                if (rankImmediately && listSlug != null) {
                    val opened = openRankSessionForCollection(listSlug, targetId)
                    if (!opened) {
                        vm.showStatusMessage(
                            "Completed & logged to Diary (rank #1 — log at least 2 items to compare)"
                        )
                    }
                } else {
                    vm.showStatusMessage("Completed & logged to Diary")
                }
            }
            .onFailure { err ->
                vm.showStatusMessage("Could not complete item (${err.message ?: "error"})")
            }
    }

    private suspend fun performRemoveWantEntry(collectionId: String, entryId: String) {
        val meta = vm.repository.catalog.resolveCollectionRankMeta(collectionId)
        val label = if (meta?.scopeCollectionId != null) "Want Queue" else meta?.title ?: "list"
        runSuspendCatching {
            vm.repository.catalog.removeCollectionEntry(collectionId, entryId)
        }
            .onSuccess {
                vm.transientState.update { cur ->
                    cur.copy(optimisticFavorites = cur.optimisticFavorites - entryId)
                }
                vm.showStatusMessage("Removed from $label")
            }
            .onFailure { err ->
                vm.showStatusMessage("Could not remove (${err.message ?: "error"})")
            }
    }

    private companion object {
        private const val MAX_FOCUSED_RANK_ROUNDS = 8
        private const val MIN_RANKABLE_COLLECTION_COUNT = 2
    }
}

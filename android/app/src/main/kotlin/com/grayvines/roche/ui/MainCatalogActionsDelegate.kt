package com.grayvines.roche.ui

import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.runSuspendCatching
import com.grayvines.roche.data.catalog.ConsumptionStatus
import com.grayvines.roche.data.catalog.EntryKind
import com.grayvines.roche.data.catalog.UpsertCatalogEntryInput
import com.grayvines.roche.ranking.RankClusterCandidate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Suppress("TooManyFunctions")
internal class MainCatalogActionsDelegate(private val host: MainDelegateHost) {
    private val catalogActionMutex = Mutex()

    internal suspend fun execute(action: MainAction.CatalogMutation) = catalogActionMutex.withLock {
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
        }
    }

    private suspend fun performToggleGenerator(
        collectionIdOrSlug: String,
        enabled: Boolean,
    ) {
        val meta = host.repository.catalog.resolveCollectionRankMeta(collectionIdOrSlug)
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
                    openRankSessionForCollection(
                        collectionIdOrSlug = step.collectionId,
                        focusEntryId = step.entryId,
                    )
                val openedRank =
                    openedInList ||
                        host.repository.catalog.resolveEntrySystemListSlug(step.entryId)?.let { slug
                            ->
                            openRankSessionForCollection(slug, step.entryId)
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
                host.showStatusMessage("Abandoned draw$nextPart")
            }
            .onFailure { err ->
                host.showStatusMessage("Could not abandon draw (${err.message ?: "error"})")
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
            host.showStatusMessage("Need at least 2 entries in this list to compare")
        }
    }

    private suspend fun openRankSessionForCollection(
        collectionIdOrSlug: String,
        focusEntryId: String?,
    ): Boolean {
        val rankMeta = host.repository.catalog.resolveCollectionRankMeta(collectionIdOrSlug)
        val title = rankMeta?.title ?: "Rankings"
        val scopeId = rankMeta?.scopeCollectionId
        val cluster =
            host.repository.catalog.selectNextRankCluster(
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
                            host.repository.catalog.resolveEntrySettlement(it, collectionIdOrSlug)
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
            host.updateTransient {
                it.copy(rankSession = nextSession, catalogDetailStack = emptyList())
            }
        }
        return nextSession != null
    }

    private suspend fun performSubmitRankCluster(ordering: List<List<String>>) {
        val session = host.currentTransientState().rankSession ?: return
        runSuspendCatching {
            host.repository.catalog.recordComparison(
                ordering = ordering,
                scopeCollectionId = session.scopeCollectionId,
            )
        }
            .onFailure { err ->
                host.showStatusMessage("Invalid ordering (${err.message ?: "error"})")
                return
            }
        val judgedSet = session.cluster.map { it.entryId }.toSet()
        advanceRankSession(
            session = session.copy(judgedClusters = session.judgedClusters + listOf(judgedSet)),
            forceRandomNext = false,
        )
    }

    private suspend fun performSkipRankCluster() {
        val session = host.currentTransientState().rankSession ?: return
        advanceRankSession(session = session, forceRandomNext = true)
    }

    private suspend fun advanceRankSession(
        session: ActiveRankSessionUiState,
        forceRandomNext: Boolean,
    ) {
        val nextRound = session.roundIndex + 1
        val nextCluster = selectDeduplicatedNextCluster(session, nextRound, forceRandomNext)
        if (nextCluster.isNotEmpty()) {
            host.updateTransient {
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
                host.repository.catalog.resolveEntrySettlement(it, session.collectionIdOrSlug)
            }
        if (settlement != null) {
            host.updateTransient {
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
            host.updateTransient { it.copy(rankSession = null) }
            host.showStatusMessage("Rankings settled for ${session.collectionTitle}")
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
            host.repository.catalog.selectNextRankCluster(
                collectionIdOrSlug = session.collectionIdOrSlug,
                focusEntryId = session.focusEntryId,
                roundIndex = nextRound,
                forceRandomNext = forceRandomNext,
            )
        if (candidate.isEmpty() || !isAlreadyCoveredCluster(candidate, session.judgedClusters)) {
            return candidate
        }
        return if (session.focusEntryId == null) {
            host.repository.catalog
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
        val resolvedId = host.repository.markFeedItemFinished(entryId)
        host.cleanupRemovedEntries(setOf(entryId))
        if (resolvedId == null) {
            host.showStatusMessage("Entry not found")
            return
        }
        if (rankImmediately) {
            val pair = host.repository.prepareFeedItemForRank(entryId)
            val slug = (pair?.second ?: EntryKind.ARTICLE).systemListSlug
            val opened = openRankSessionForCollection(slug, resolvedId)
            if (!opened) {
                host.showStatusMessage(
                    "Logged to Diary (rank #1 — log at least 2 items to compare)"
                )
            }
        } else {
            host.showStatusMessage("Marked finished & logged to Diary")
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
                optimisticFavorites = it.optimisticFavorites - entryId,
            )
        }
        if (!action.addToWant && !action.addToListOnly && action.rankAfterLog) {
            val rankTarget = targetListId ?: action.kind.systemListSlug
            if (!openRankSessionForCollection(rankTarget, entryId)) {
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
        runSuspendCatching {
            host.repository.catalog.logExistingEntry(entryId, ConsumptionStatus.CONSUMED, null)
        }
            .onSuccess { resolvedId ->
                val targetId = resolvedId ?: entryId
                val listSlug = host.repository.catalog.resolveEntrySystemListSlug(targetId)
                if (rankImmediately && listSlug != null) {
                    val opened = openRankSessionForCollection(listSlug, targetId)
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
        val meta = host.repository.catalog.resolveCollectionRankMeta(collectionId)
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

    private companion object {
        private const val MAX_FOCUSED_RANK_ROUNDS = 8
        private const val MIN_RANKABLE_COLLECTION_COUNT = 2
    }
}

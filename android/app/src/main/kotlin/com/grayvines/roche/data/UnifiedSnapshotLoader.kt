package com.grayvines.roche.data

import com.grayvines.roche.data.boards.ProjectedBoardsSnapshot
import com.grayvines.roche.data.boards.RawBoardTables
import com.grayvines.roche.data.boards.projectBoardsSnapshot
import com.grayvines.roche.data.catalog.CatalogSnapshot
import com.grayvines.roche.data.catalog.CatalogStore
import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.InboxItemRow
import com.grayvines.roche.data.local.ItemTagRow
import com.grayvines.roche.data.local.RepositoryStampRow
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.TagRow
import com.grayvines.roche.data.local.toCatalogStamp
import com.grayvines.roche.data.remote.PeerNodeDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class UnifiedSnapshotLoader(
    private val dao: RocheDao,
    private val catalog: CatalogStore,
    private val entryCache: InboxEntryCache,
) {
    private val loadMutex = Mutex()
    private var cachedStamp: RepositoryStampRow? = null
    private var cachedInboxState: CachedInboxBase? = null
    private var cachedBoardTables: CachedBoardTablesState? = null
    private var cachedBoardsState: CachedBoardsBase? = null

    @Volatile
    internal var unifiedTxCount: Int = 0
        private set

    @Volatile
    internal var unifiedTableLoads: Int = 0
        private set

    @Volatile
    internal var entriesTableLoads: Int = 0
        private set

    @Volatile
    internal var inboxProjectionRebuilds: Int = 0
        private set

    suspend fun loadSnapshot(
        activePeers: List<PeerNodeDto>,
        observedStamp: RepositoryStampRow? = null,
    ): RepositorySnapshot = loadMutex.withLock {
        var resolvedStamp: RepositoryStampRow? = null
        var inboxBase: RepositorySnapshot? = null
        var boardsProjection: ProjectedBoardsSnapshot? = null

        val lastStamp = cachedStamp
        val canShortCircuit =
            observedStamp != null &&
                lastStamp != null &&
                observedStamp == lastStamp &&
                cachedInboxState != null &&
                cachedBoardsState != null
        if (canShortCircuit) {
            resolvedStamp = lastStamp
            inboxBase = checkNotNull(cachedInboxState).base
            boardsProjection = checkNotNull(cachedBoardsState).projected
        } else {
            dao.executeInTransaction {
                val currentStamp = dao.getRepositoryStamp()
                resolvedStamp = currentStamp
                if (currentStamp != lastStamp) {
                    unifiedTxCount += 1
                }
                inboxBase = resolveInboxBaseInTx(currentStamp, activePeers)
                boardsProjection = resolveBoardsProjectionInTx(currentStamp)
            }
            cachedStamp = resolvedStamp
        }
        val finalStamp = checkNotNull(resolvedStamp)
        val catalogSnap: CatalogSnapshot =
            catalog.resolveOverviewSnapshot(observedStamp = finalStamp.toCatalogStamp())
        val finalInbox = checkNotNull(inboxBase)
        val finalBoards = checkNotNull(boardsProjection)
        finalInbox.copy(
            activePeers = activePeers,
            catalog = catalogSnap,
            boards = finalBoards.boards,
            boardItems = finalBoards.boardItems,
            boardItemStates = finalBoards.boardItemStates,
            diaryRevisionStamp = "${finalStamp.logsStamp}|${finalStamp.entriesStamp}",
            catalogRevisionStamp = formatCatalogRevisionStamp(finalStamp),
        )
    }

    private suspend fun resolveInboxBaseInTx(
        stamp: RepositoryStampRow,
        activePeers: List<PeerNodeDto>,
    ): RepositorySnapshot {
        val inboxStamp = stamp.toInboxInputStamp()
        val existing = cachedInboxState
        if (existing != null && existing.stamp == inboxStamp) {
            return existing.base
        }
        inboxProjectionRebuilds += 1
        unifiedTableLoads += 1
        val projected =
            ProjectedInboxTables(
                items = dao.getAllPreviewItems(),
                feeds = dao.getAllFeeds(),
                tags = dao.getAllTags(),
                itemTags = dao.getAllItemTags(),
            )
        val base =
            buildSnapshot(
                items = projected.items,
                feeds = projected.feeds,
                tags = projected.tags,
                itemTags = projected.itemTags,
                activePeers = activePeers,
                entryCache = entryCache,
            )
        cachedInboxState = CachedInboxBase(stamp = inboxStamp, base = base)
        return base
    }

    private suspend fun resolveBoardsProjectionInTx(
        stamp: RepositoryStampRow
    ): ProjectedBoardsSnapshot {
        val boardsStamp = stamp.toBoardsInputStamp()
        val existing = cachedBoardsState
        if (existing != null && existing.stamp == boardsStamp) {
            return existing.projected
        }
        val prevTables = cachedBoardTables
        val prevStamp = prevTables?.stamp
        val boardTables =
            RawBoardTables(
                boards =
                    reloadSlice(
                        stamp.boardsStamp != prevStamp?.boardsStamp,
                        prevTables?.tables?.boards,
                    ) {
                        dao.getAllBoards()
                    },
                boardItems =
                    reloadSlice(
                        stamp.boardItemsStamp != prevStamp?.boardItemsStamp,
                        prevTables?.tables?.boardItems,
                    ) {
                        dao.getAllBoardItems()
                    },
                boardItemStates =
                    reloadSlice(
                        stamp.boardItemStatesStamp != prevStamp?.boardItemStatesStamp,
                        prevTables?.tables?.boardItemStates,
                    ) {
                        dao.getAllBoardItemStates()
                    },
            )
        cachedBoardTables = CachedBoardTablesState(stamp = stamp, tables = boardTables)
        val projected = projectBoardsSnapshot(dao, boardTables)
        cachedBoardsState = CachedBoardsBase(stamp = boardsStamp, projected = projected)
        return projected
    }

    private suspend fun <T> reloadSlice(
        changed: Boolean,
        previous: T?,
        loader: suspend () -> T,
    ): T =
        if (!changed && previous != null) {
            previous
        } else {
            unifiedTableLoads += 1
            loader()
        }
}

internal fun formatCatalogRevisionStamp(stamp: RepositoryStampRow): String =
    "${stamp.entriesStamp}|${stamp.identsStamp}|${stamp.specsStamp}|" +
        "${stamp.membersStamp}|${stamp.generatorsStamp}|${stamp.logsStamp}|${stamp.comparisonsStamp}"

@Suppress("LongParameterList")
internal data class InboxInputStamp(
    val entriesStamp: Long,
    val identsStamp: Long,
    val specsStamp: Long,
    val membersStamp: Long,
    val logsStamp: Long,
    val statesStamp: Long,
    val contentsStamp: Long,
    val feedsStamp: Long,
)

@Suppress("LongParameterList")
internal data class BoardsInputStamp(
    val boardsStamp: Long,
    val boardItemsStamp: Long,
    val boardItemStatesStamp: Long,
    val entriesStamp: Long,
    val identsStamp: Long,
    val specsStamp: Long,
    val membersStamp: Long,
    val logsStamp: Long,
)

internal data class CachedBoardTablesState(
    val stamp: RepositoryStampRow,
    val tables: RawBoardTables,
)

internal data class CachedBoardsBase(
    val stamp: BoardsInputStamp,
    val projected: ProjectedBoardsSnapshot,
)

internal data class CachedInboxBase(
    val stamp: InboxInputStamp,
    val base: RepositorySnapshot,
)

internal data class ProjectedInboxTables(
    val items: List<InboxItemRow>,
    val feeds: List<FeedRow>,
    val tags: List<TagRow>,
    val itemTags: List<ItemTagRow>,
)

internal fun RepositoryStampRow.toInboxInputStamp(): InboxInputStamp =
    InboxInputStamp(
        entriesStamp = entriesStamp,
        identsStamp = identsStamp,
        specsStamp = specsStamp,
        membersStamp = membersStamp,
        logsStamp = logsStamp,
        statesStamp = statesStamp,
        contentsStamp = contentsStamp,
        feedsStamp = feedsStamp,
    )

internal fun RepositoryStampRow.toBoardsInputStamp(): BoardsInputStamp =
    BoardsInputStamp(
        boardsStamp = boardsStamp,
        boardItemsStamp = boardItemsStamp,
        boardItemStatesStamp = boardItemStatesStamp,
        entriesStamp = entriesStamp,
        identsStamp = identsStamp,
        specsStamp = specsStamp,
        membersStamp = membersStamp,
        logsStamp = logsStamp,
    )

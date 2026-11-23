package com.grayvines.roche.data.boards

import com.grayvines.roche.data.remote.PublicWebFetcher

/**
 * Pluggable board source abstraction for ephemeral, snapshot-based external boards (Hacker News,
 * future GitHub Trending, arXiv daily, ephemeral RSS, etc.).
 */
interface BoardAdapter {
    val adapterType: BoardAdapterType

    fun orderMode(sourceKey: String, sourceConfigJson: String): BoardOrderMode

    fun defaultTitle(sourceKey: String, sourceConfigJson: String): String

    suspend fun fetchSnapshot(
        sourceKey: String,
        sourceConfigJson: String,
        limit: Int = BOARD_SNAPSHOT_BUFFER_LIMIT,
    ): Result<BoardPollBatch>

    suspend fun fetchPage(
        sourceKey: String,
        sourceConfigJson: String,
        offset: Int,
        limit: Int = BOARD_DEFAULT_PAGE_SIZE,
    ): Result<BoardPollBatch>

    suspend fun fetchSingleItem(
        sourceKey: String,
        sourceConfigJson: String,
        externalId: String,
    ): Result<BoardPollCandidate?>
}

/** Registry resolving [BoardAdapter] implementations by [BoardAdapterType] or wire string. */
class BoardAdapterRegistry(adapters: List<BoardAdapter>) {
    private val byType: Map<BoardAdapterType, BoardAdapter> = adapters.associateBy {
        it.adapterType
    }

    fun resolve(adapterType: BoardAdapterType): BoardAdapter? = byType[adapterType]

    fun resolve(wireValue: String?): BoardAdapter? =
        BoardAdapterType.fromWireValue(wireValue)?.let { byType[it] }

    companion object {
        fun default(publicWebFetcher: PublicWebFetcher): BoardAdapterRegistry =
            BoardAdapterRegistry(listOf(HackerNewsBoardAdapter(publicWebFetcher)))
    }
}

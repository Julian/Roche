package com.grayvines.roche.data

import com.grayvines.roche.core.PROTECTED_SYSTEM_COLLECTION_IDS
import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.core.decodeCreatorsJson
import com.grayvines.roche.data.catalog.CatalogStore
import com.grayvines.roche.data.catalog.RawCatalogTables
import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.EntryContentPreviewRow
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryStateEntity
import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.local.FeedSourceEntity
import com.grayvines.roche.data.local.InboxItemRow
import com.grayvines.roche.data.local.ItemTagRow
import com.grayvines.roche.data.local.LIST_PREVIEW_SUMMARY_CHARS
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
    private var cachedTablesState: CachedUnifiedTables? = null
    private var cachedInboxState: CachedInboxBase? = null

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
        val tablesState = refreshUnifiedTables(observedStamp)
        val catalogSnap =
            catalog.resolveFromPreloadedTables(
                stamp = tablesState.stamp.toCatalogStamp(),
                tables = tablesState.catalogTables,
            )
        val inboxBase = resolveInboxBase(tablesState, activePeers)
        inboxBase.copy(activePeers = activePeers, catalog = catalogSnap)
    }

    private suspend fun refreshUnifiedTables(
        observedStamp: RepositoryStampRow?
    ): CachedUnifiedTables {
        val existing = cachedTablesState
        if (observedStamp != null && existing != null && existing.stamp == observedStamp) {
            return existing
        }
        var loaded: CachedUnifiedTables? = null
        dao.executeInTransaction {
            val currentStamp = dao.getRepositoryStamp()
            if (existing != null && existing.stamp == currentStamp) {
                loaded = existing
            } else {
                unifiedTxCount += 1
                loaded = loadChangedUnifiedTables(currentStamp, existing)
            }
        }
        val resolved = checkNotNull(loaded)
        cachedTablesState = resolved
        return resolved
    }

    private suspend fun loadChangedUnifiedTables(
        stamp: RepositoryStampRow,
        previous: CachedUnifiedTables?,
    ): CachedUnifiedTables =
        CachedUnifiedTables(
            stamp = stamp,
            catalogTables = loadCatalogTablesSlice(stamp, previous),
            satellites = loadSatelliteTablesSlice(stamp, previous),
        )

    private suspend fun loadCatalogTablesSlice(
        stamp: RepositoryStampRow,
        previous: CachedUnifiedTables?,
    ): RawCatalogTables {
        val prevStamp = previous?.stamp
        val prevCat = previous?.catalogTables
        val entries =
            reloadTable(stamp.entriesStamp != prevStamp?.entriesStamp, prevCat?.entries) {
                entriesTableLoads += 1
                dao.getAllEntries()
            }
        return RawCatalogTables(
            entries = entries,
            identifiers =
                reloadTable(stamp.identsStamp != prevStamp?.identsStamp, prevCat?.identifiers) {
                    dao.getAllEntryIdentifiers()
                },
            specs =
                reloadTable(stamp.specsStamp != prevStamp?.specsStamp, prevCat?.specs) {
                    dao.getAllCollectionSpecs()
                },
            memberships =
                reloadTable(stamp.membersStamp != prevStamp?.membersStamp, prevCat?.memberships) {
                    dao.getAllCollectionEntries()
                },
            generators =
                reloadTable(
                    stamp.generatorsStamp != prevStamp?.generatorsStamp,
                    prevCat?.generators,
                ) {
                    dao.getAllCollectionGenerators()
                },
            logs =
                reloadTable(stamp.logsStamp != prevStamp?.logsStamp, prevCat?.logs) {
                    dao.getAllConsumptionLogs()
                },
            comparisons =
                reloadTable(
                    stamp.comparisonsStamp != prevStamp?.comparisonsStamp,
                    prevCat?.comparisons,
                ) {
                    dao.getAllComparisons()
                },
        )
    }

    private suspend fun loadSatelliteTablesSlice(
        stamp: RepositoryStampRow,
        previous: CachedUnifiedTables?,
    ): InboxSatelliteTables {
        val prevStamp = previous?.stamp
        val prevSat = previous?.satellites
        return InboxSatelliteTables(
            entryStates =
                reloadTable(stamp.statesStamp != prevStamp?.statesStamp, prevSat?.entryStates) {
                    dao.getAllEntryStates()
                },
            contentPreviews =
                reloadTable(
                    stamp.contentsStamp != prevStamp?.contentsStamp,
                    prevSat?.contentPreviews,
                ) {
                    dao.getAllEntryContentPreviews()
                },
            feedSources =
                reloadTable(stamp.feedsStamp != prevStamp?.feedsStamp, prevSat?.feedSources) {
                    dao.getAllFeedSources()
                },
        )
    }

    private suspend fun <T> reloadTable(
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

    private fun resolveInboxBase(
        tablesState: CachedUnifiedTables,
        activePeers: List<PeerNodeDto>,
    ): RepositorySnapshot {
        val inboxStamp = tablesState.stamp.toInboxInputStamp()
        val existing = cachedInboxState
        return if (existing != null && existing.stamp == inboxStamp) {
            existing.base
        } else {
            inboxProjectionRebuilds += 1
            val projected = projectInboxTables(tablesState.catalogTables, tablesState.satellites)
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
            base
        }
    }
}

internal data class InboxInputStamp(
    val entriesStamp: Long,
    val specsStamp: Long,
    val membersStamp: Long,
    val statesStamp: Long,
    val contentsStamp: Long,
    val feedsStamp: Long,
)

internal data class InboxSatelliteTables(
    val entryStates: List<EntryStateEntity>,
    val contentPreviews: List<EntryContentPreviewRow>,
    val feedSources: List<FeedSourceEntity>,
)

internal data class CachedUnifiedTables(
    val stamp: RepositoryStampRow,
    val catalogTables: RawCatalogTables,
    val satellites: InboxSatelliteTables,
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

internal data class InboxProjectionContext(
    val allEntriesById: Map<String, EntryEntity>,
    val specsById: Map<String, CollectionSpecEntity>,
    val feedSourcesById: Map<String, FeedSourceEntity>,
    val statesById: Map<String, EntryStateEntity>,
    val contentPreviewsById: Map<String, EntryContentPreviewRow>,
    val membershipsByEntry: Map<String, List<CollectionEntryEntity>>,
)

internal fun RepositoryStampRow.toInboxInputStamp(): InboxInputStamp =
    InboxInputStamp(
        entriesStamp = entriesStamp,
        specsStamp = specsStamp,
        membersStamp = membersStamp,
        statesStamp = statesStamp,
        contentsStamp = contentsStamp,
        feedsStamp = feedsStamp,
    )

internal fun projectInboxTables(
    catalogTables: RawCatalogTables,
    satellites: InboxSatelliteTables,
): ProjectedInboxTables {
    val allEntriesById = catalogTables.entries.associateBy { it.id }
    val activeEntries = catalogTables.entries.filter { it.mergedIntoId == null }
    val activeEntriesById = activeEntries.associateBy { it.id }
    val specsById = catalogTables.specs.associateBy { it.collectionId }
    val feedSourcesById = satellites.feedSources.associateBy { it.collectionId }
    val statesById = satellites.entryStates.associateBy { it.entryId }
    val contentPreviewsById = satellites.contentPreviews.associateBy { it.entryId }
    val membershipsByEntry =
        catalogTables.memberships
            .sortedWith(compareBy<CollectionEntryEntity> { it.addedAt }.thenBy { it.collectionId })
            .groupBy { it.entryId }
    val ctx =
        InboxProjectionContext(
            allEntriesById = allEntriesById,
            specsById = specsById,
            feedSourcesById = feedSourcesById,
            statesById = statesById,
            contentPreviewsById = contentPreviewsById,
            membershipsByEntry = membershipsByEntry,
        )
    val tags = projectTagRows(catalogTables.specs, activeEntriesById)
    val activeTagIds = tags.mapTo(HashSet(tags.size)) { it.id }
    val itemTags = projectItemTagRows(catalogTables.memberships, activeTagIds)
    val feeds = projectFeedRows(satellites.feedSources, activeEntriesById)
    val items = projectLiveInboxRows(activeEntries, ctx)
    return ProjectedInboxTables(items = items, feeds = feeds, tags = tags, itemTags = itemTags)
}

internal fun projectTagRows(
    specs: List<CollectionSpecEntity>,
    activeEntriesById: Map<String, EntryEntity>,
): List<TagRow> = specs.mapNotNull { spec ->
    if (spec.role == ROLE_TAG) {
        activeEntriesById[spec.collectionId]?.let { entry ->
            TagRow(id = entry.id, name = entry.title, color = spec.color)
        }
    } else {
        null
    }
}

internal fun projectItemTagRows(
    memberships: List<CollectionEntryEntity>,
    activeTagIds: Set<String>,
): List<ItemTagRow> = memberships.mapNotNull { mem ->
    if (mem.collectionId in activeTagIds) {
        ItemTagRow(itemId = mem.entryId, tagId = mem.collectionId)
    } else {
        null
    }
}

internal fun projectFeedRows(
    feedSources: List<FeedSourceEntity>,
    activeEntriesById: Map<String, EntryEntity>,
): List<FeedRow> = feedSources.mapNotNull { fs ->
    activeEntriesById[fs.collectionId]?.let { entry ->
        FeedRow(
            id = fs.collectionId,
            title = entry.title.ifEmpty { fs.feedTitle.orEmpty().ifEmpty { fs.feedUrl } },
            url = fs.feedUrl,
            siteUrl = fs.siteUrl,
            feedType = fs.sourceType,
            description = entry.summary,
            language = fs.language,
            pollInterval = fs.pollInterval,
            lastPolled = fs.lastPolledAt,
            lastPolledBy = fs.lastPolledBy,
            lastError = fs.lastError,
            lastErrorBy = fs.lastErrorBy,
            consecutiveFailures = fs.consecutiveFailures,
            isActive = fs.isActive,
            autoDownloadEpisodes = fs.autoDownloadEpisodes,
            customPlaybackSpeed = fs.customPlaybackSpeed,
            hlc = fs.hlc,
            isLocalDirty = fs.isLocalDirty || entry.isLocalDirty,
        )
    }
}

internal fun projectLiveInboxRows(
    activeEntries: List<EntryEntity>,
    ctx: InboxProjectionContext,
): List<InboxItemRow> =
    activeEntries
        .mapNotNull { entry ->
            val state = ctx.statesById[entry.id]
            val content = ctx.contentPreviewsById[entry.id]
            val members = ctx.membershipsByEntry[entry.id].orEmpty()
            if (isLiveInboxEntry(entry, state, content, members, ctx.specsById)) {
                buildProjectedInboxItem(entry, state, content, members, ctx)
            } else {
                null
            }
        }
        .sortedByDescending { it.publishedAt ?: it.createdAt }

internal fun isLiveInboxEntry(
    entry: EntryEntity,
    state: EntryStateEntity?,
    content: EntryContentPreviewRow?,
    memberships: List<CollectionEntryEntity>,
    specsById: Map<String, CollectionSpecEntity>,
): Boolean {
    val inboxState = state?.inboxState ?: INBOX_STATE_NONE
    val isDeletedOrMerged = entry.mergedIntoId != null || inboxState == INBOX_STATE_DELETED
    val inInboxOrContent =
        inboxState == STATE_UNREAD || inboxState == INBOX_STATE_DISMISSED || content != null
    val inReaderCollection =
        entry.kind in READER_ENTRY_KINDS &&
            memberships.any { mem ->
                val role = specsById[mem.collectionId]?.role
                role == ROLE_WANT || role == ROLE_LIST
            }
    return !isDeletedOrMerged && (inInboxOrContent || inReaderCollection)
}

internal fun buildProjectedInboxItem(
    entry: EntryEntity,
    state: EntryStateEntity?,
    content: EntryContentPreviewRow?,
    entryMembers: List<CollectionEntryEntity>,
    ctx: InboxProjectionContext,
): InboxItemRow {
    val inboxState = state?.inboxState ?: INBOX_STATE_NONE
    val inWantToRead = entryMembers.any { it.collectionId == WANT_TO_READ_COLLECTION_ID }
    val isFavorite = entryMembers.any { it.collectionId == STARRED_COLLECTION_ID }
    val feedUrl =
        entryMembers
            .firstOrNull { it.collectionId in ctx.feedSourcesById }
            ?.let { mem ->
                ctx.feedSourcesById[mem.collectionId]?.feedUrl
            }
    return InboxItemRow(
        id = entry.id,
        feedId = resolvePrimaryFeedId(entryMembers, ctx),
        feedTitle = resolvePrimaryFeedTitle(entryMembers, content, ctx),
        title = entry.title,
        url = entry.url.orEmpty(),
        author =
            decodeCreatorsJson(entry.creatorsJson).takeIf { it.isNotEmpty() }?.joinToString(", "),
        summary = entry.summary?.take(LIST_PREVIEW_SUMMARY_CHARS),
        contentText = content?.contentText,
        contentHtml = content?.contentHtml,
        contentType = content?.contentType ?: inferDefaultContentType(entry.kind),
        status = resolveItemStatus(inboxState, state?.readAt, inWantToRead),
        isFavorite = isFavorite,
        isCachedOffline = state?.isCachedOffline ?: false,
        publishedAt = entry.publishedAt,
        createdAt = entry.createdAt,
        readProgress = state?.progress ?: 0.0,
        thumbnailMediaId = content?.thumbnailMediaId,
        duration = content?.duration,
        readAt = state?.readAt,
        thumbnailUrl = entry.thumbnailUrl,
        feedUrl = feedUrl,
        interactionUpdatedAt = state?.interactionUpdatedAt,
        audioUrl = content?.audioUrl,
        inboxState = inboxState,
        entryKind = entry.kind,
    )
}

internal fun resolvePrimaryFeedId(
    entryMembers: List<CollectionEntryEntity>,
    ctx: InboxProjectionContext,
): String? =
    entryMembers
        .firstOrNull { mem ->
            val colId = mem.collectionId
            val spec = ctx.specsById[colId]
            val isProtected = colId in PROTECTED_SYSTEM_COLLECTION_IDS
            val isFeedSource = colId in ctx.feedSourcesById
            val isFeedSpec = spec?.role == ROLE_FEED
            val isImplicitFeed =
                spec == null &&
                    ctx.allEntriesById[colId]?.let { it.kind !in FEED_CONTAINER_KINDS } != true
            !isProtected && (isFeedSource || isFeedSpec || isImplicitFeed)
        }
        ?.collectionId

internal fun resolvePrimaryFeedTitle(
    entryMembers: List<CollectionEntryEntity>,
    content: EntryContentPreviewRow?,
    ctx: InboxProjectionContext,
): String {
    val feedMember = entryMembers.firstOrNull { mem ->
        mem.collectionId in ctx.feedSourcesById && mem.collectionId in ctx.allEntriesById
    }
    val resolvedFeedTitle = feedMember?.let { mem ->
        val fs = ctx.feedSourcesById.getValue(mem.collectionId)
        val fe = ctx.allEntriesById.getValue(mem.collectionId)
        fe.title.ifEmpty { fs.feedTitle.orEmpty().ifEmpty { fs.feedUrl } }
    }
    return resolvedFeedTitle
        ?: content?.fallbackFeedTitle?.takeIf { it.isNotEmpty() }
        ?: FALLBACK_FEED_TITLE
}

internal fun inferDefaultContentType(kind: String): String =
    when (kind) {
        KIND_VIDEO -> KIND_VIDEO
        KIND_PAPER -> KIND_PAPER
        KIND_PODCAST_EPISODE -> CONTENT_TYPE_AUDIO
        else -> KIND_ARTICLE
    }

internal fun resolveItemStatus(
    inboxState: String,
    readAt: String?,
    inWantToRead: Boolean,
): String =
    when {
        inboxState == INBOX_STATE_DISMISSED -> STATUS_ARCHIVED
        inboxState == INBOX_STATE_NONE && readAt != null && !inWantToRead -> STATUS_ARCHIVED
        else -> STATE_UNREAD
    }

private const val ROLE_TAG = "tag"
private const val ROLE_FEED = "feed"
private const val ROLE_WANT = "want"
private const val ROLE_LIST = "list"
private const val INBOX_STATE_NONE = "none"
private const val STATE_UNREAD = "unread"
private const val INBOX_STATE_DISMISSED = "dismissed"
private const val INBOX_STATE_DELETED = "deleted"
private const val STATUS_ARCHIVED = "archived"
private const val FALLBACK_FEED_TITLE = "Saved Link"
private const val KIND_ARTICLE = "article"
private const val KIND_PAPER = "paper"
private const val KIND_VIDEO = "video"
private const val KIND_PODCAST_EPISODE = "podcast_episode"
private const val CONTENT_TYPE_AUDIO = "audio"
internal val READER_ENTRY_KINDS = setOf(KIND_ARTICLE, KIND_PAPER, KIND_VIDEO, KIND_PODCAST_EPISODE)
internal val FEED_CONTAINER_KINDS = setOf("publication", "podcast", "channel", "collection")

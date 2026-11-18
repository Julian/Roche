package com.grayvines.roche.data

import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryIdentifierEntity
import com.grayvines.roche.data.local.LocalSyncFlagEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.deleteEntryCascade
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class CatalogSeeder(private val dao: RocheDao) {
    private val seedMutex = Mutex()
    @Volatile private var systemSeeded = false

    suspend fun ensureSystemSeeded() {
        if (systemSeeded) return
        seedMutex.withLock {
            if (!systemSeeded) {
                seedSystemSpecs()
                systemSeeded = true
            }
        }
    }

    private suspend fun seedSystemSpecs() {
        pruneLegacyPlaceholder()
        val existingSpecs = dao.getAllCollectionSpecs().associateBy { it.collectionId }
        val existingEntryIds = dao.getAllEntries().map { it.id }.toHashSet()
        val seedHlc = HlcTimestamp.fromIsoOrEpoch(SEED_EPOCH_ISO, DEFAULT_LOCAL_HLC_NODE).encode()

        val specsToInsert = ArrayList<CollectionSpecEntity>()
        val entriesToInsert = ArrayList<EntryEntity>()
        val identsToInsert = ArrayList<EntryIdentifierEntity>()

        collectManualSystemSpecs(
            existingSpecs = existingSpecs,
            existingEntryIds = existingEntryIds,
            seedHlc = seedHlc,
            specsToInsert = specsToInsert,
            entriesToInsert = entriesToInsert,
            identsToInsert = identsToInsert,
        )
        collectDynamicSystemSpecs(
            existingSpecs = existingSpecs,
            existingEntryIds = existingEntryIds,
            seedHlc = seedHlc,
            specsToInsert = specsToInsert,
            entriesToInsert = entriesToInsert,
            identsToInsert = identsToInsert,
        )

        val wasEmptyDatabase = existingEntryIds.isEmpty()
        dao.executeInTransaction {
            if (entriesToInsert.isNotEmpty()) {
                dao.upsertEntries(entriesToInsert)
            }
            if (specsToInsert.isNotEmpty()) {
                dao.upsertCollectionSpecs(specsToInsert)
            }
            if (identsToInsert.isNotEmpty()) {
                dao.insertEntryIdentifiersIgnore(identsToInsert)
            }
            if (wasEmptyDatabase) {
                dao.upsertLocalSyncFlag(LocalSyncFlagEntity(FLAG_MIGRATED_MANUAL_WANT_READ, "1"))
            }
        }
        if (!wasEmptyDatabase) {
            migrateManualInboxOnce()
        }
    }

    private suspend fun pruneLegacyPlaceholder() {
        val legacySpec = dao.getCollectionSpecById(LEGACY_PLACEHOLDER_COLLECTION_ID) ?: return
        val memberCount = dao.countCollectionEntries(LEGACY_PLACEHOLDER_COLLECTION_ID)
        val gen = dao.getCollectionGenerator(LEGACY_PLACEHOLDER_COLLECTION_ID)
        val isUnused =
            memberCount == 0 && (gen == null || !gen.enabled && gen.currentEntryId.isNullOrBlank())
        dao.executeInTransaction {
            if (isUnused) {
                dao.deleteEntryCascade(LEGACY_PLACEHOLDER_COLLECTION_ID)
            } else if (CollectionRole.fromWireValue(legacySpec.role) == CollectionRole.SYSTEM) {
                dao.upsertCollectionSpec(
                    legacySpec.copy(
                        role = CollectionRole.LIST.wireValue,
                        showInLists = true,
                    )
                )
            }
        }
    }

    @Suppress("LongParameterList")
    private fun collectManualSystemSpecs(
        existingSpecs: Map<String, CollectionSpecEntity>,
        existingEntryIds: Set<String>,
        seedHlc: String,
        specsToInsert: MutableList<CollectionSpecEntity>,
        entriesToInsert: MutableList<EntryEntity>,
        identsToInsert: MutableList<EntryIdentifierEntity>,
    ) {
        seedOrUpgradeStarredSpec(
            existingSpecs,
            existingEntryIds,
            seedHlc,
            specsToInsert,
            entriesToInsert,
            identsToInsert,
        )
        val staticManuals =
            listOf(
                Triple(UP_NEXT_COLLECTION_ID, "up_next", "Up Next") to CollectionRole.SYSTEM,
                Triple(WANT_TO_READ_COLLECTION_ID, WANT_TO_READ_SLUG, WANT_TO_READ_TITLE) to
                    CollectionRole.WANT,
            )
        for ((meta, role) in staticManuals) {
            val (id, slug, title) = meta
            if (id !in existingEntryIds) {
                entriesToInsert.add(buildSeededCollectionEntry(id, title, seedHlc))
            }
            if (id !in existingSpecs) {
                specsToInsert.add(
                    CollectionSpecEntity(
                        collectionId = id,
                        slug = slug,
                        mode = CollectionMode.MANUAL.wireValue,
                        role = role.wireValue,
                        showInLists = false,
                    )
                )
                identsToInsert.add(EntryIdentifierEntity("slug", slug, id))
            }
        }
    }

    @Suppress("LongParameterList")
    private fun seedOrUpgradeStarredSpec(
        existingSpecs: Map<String, CollectionSpecEntity>,
        existingEntryIds: Set<String>,
        seedHlc: String,
        specsToInsert: MutableList<CollectionSpecEntity>,
        entriesToInsert: MutableList<EntryEntity>,
        identsToInsert: MutableList<EntryIdentifierEntity>,
    ) {
        if (STARRED_COLLECTION_ID !in existingEntryIds) {
            entriesToInsert.add(
                buildSeededCollectionEntry(STARRED_COLLECTION_ID, "Starred", seedHlc)
            )
        }
        val existingStarred = existingSpecs[STARRED_COLLECTION_ID]
        if (
            existingStarred == null ||
                !existingStarred.showInLists ||
                existingStarred.role != CollectionRole.LIST.wireValue
        ) {
            specsToInsert.add(
                CollectionSpecEntity(
                    collectionId = STARRED_COLLECTION_ID,
                    slug = "starred",
                    mode = CollectionMode.MANUAL.wireValue,
                    role = CollectionRole.LIST.wireValue,
                    showInLists = true,
                    color = existingStarred?.color,
                )
            )
            identsToInsert.add(EntryIdentifierEntity("slug", "starred", STARRED_COLLECTION_ID))
        }
    }

    private suspend fun migrateManualInboxOnce() {
        if (dao.getLocalSyncFlag(FLAG_MIGRATED_MANUAL_WANT_READ) != null) return
        val manualStates = findLegacyManualUnreadStates()
        if (manualStates.isNotEmpty()) {
            promoteManualToWantRead(manualStates)
        }
        dao.upsertLocalSyncFlag(LocalSyncFlagEntity(FLAG_MIGRATED_MANUAL_WANT_READ, "1"))
    }

    private suspend fun findLegacyManualUnreadStates():
        List<com.grayvines.roche.data.local.EntryStateEntity> {
        val allStates = dao.getAllEntryStates().filter { it.inboxState == "unread" }
        if (allStates.isEmpty()) return emptyList()
        val feedCollectionIds =
            dao.getAllFeedSources()
                .mapTo(HashSet()) { it.collectionId }
                .apply {
                    dao.getAllCollectionSpecs()
                        .filter { it.role.equals("feed", ignoreCase = true) }
                        .forEach { add(it.collectionId) }
                }
        val specsById = dao.getAllCollectionSpecs().associateBy { it.collectionId }
        val membershipsByEntry = dao.getAllCollectionEntries().groupBy { it.entryId }
        return allStates.filter { state ->
            val entryMemberships = membershipsByEntry[state.entryId].orEmpty()
            entryMemberships.none { mem ->
                mem.collectionId in feedCollectionIds ||
                    isImplicitFeedMembership(mem.collectionId, specsById)
            }
        }
    }

    private fun isImplicitFeedMembership(
        collectionId: String,
        specsById: Map<String, CollectionSpecEntity>,
    ): Boolean = collectionId !in PROTECTED_SYSTEM_COLLECTION_IDS && specsById[collectionId] == null

    private suspend fun promoteManualToWantRead(
        manualStates: List<com.grayvines.roche.data.local.EntryStateEntity>
    ) {
        val nowIso = nowUtcIso()
        dao.executeInTransaction {
            val hlc = nextLocalHlc(dao)
            dao.getEntryById(WANT_TO_READ_COLLECTION_ID)?.let { wantCol ->
                dao.upsertEntry(wantCol.copy(updatedAt = nowIso, hlc = hlc, isLocalDirty = true))
            }
            for (state in manualStates) {
                if (dao.hasCollectionMembership(WANT_TO_READ_COLLECTION_ID, state.entryId) == 0) {
                    dao.upsertCollectionEntry(
                        CollectionEntryEntity(
                            collectionId = WANT_TO_READ_COLLECTION_ID,
                            entryId = state.entryId,
                            position = null,
                            addedAt = nowIso,
                            hlc = hlc,
                            isLocalDirty = true,
                        )
                    )
                }
                dao.upsertEntryState(
                    state.copy(
                        inboxState = "none",
                        updatedAt = nowIso,
                        hlc = hlc,
                        isLocalDirty = true,
                    )
                )
            }
        }
    }

    @Suppress("LongParameterList")
    private fun collectDynamicSystemSpecs(
        existingSpecs: Map<String, CollectionSpecEntity>,
        existingEntryIds: Set<String>,
        seedHlc: String,
        specsToInsert: MutableList<CollectionSpecEntity>,
        entriesToInsert: MutableList<EntryEntity>,
        identsToInsert: MutableList<EntryIdentifierEntity>,
    ) {
        for (kind in EntryKind.systemDynamicKinds) {
            val id = systemCollectionUuid(kind.systemListSlug)
            if (id !in existingEntryIds) {
                entriesToInsert.add(buildSeededCollectionEntry(id, kind.systemListTitle, seedHlc))
            }
            if (id !in existingSpecs) {
                specsToInsert.add(
                    CollectionSpecEntity(
                        collectionId = id,
                        slug = kind.systemListSlug,
                        mode = CollectionMode.DYNAMIC.wireValue,
                        role = CollectionRole.LIST.wireValue,
                        ruleJson = """{"kind":"${kind.wireValue}","consumed":true}""",
                        showInLists = true,
                    )
                )
                identsToInsert.add(EntryIdentifierEntity("slug", kind.systemListSlug, id))
            }
        }
    }

    private fun buildSeededCollectionEntry(
        id: String,
        title: String,
        seedHlc: String,
    ): EntryEntity =
        EntryEntity(
            id = id,
            kind = EntryKind.COLLECTION.wireValue,
            title = title,
            createdAt = SEED_EPOCH_ISO,
            updatedAt = SEED_EPOCH_ISO,
            hlc = seedHlc,
            isLocalDirty = false,
        )
}

private const val SEED_EPOCH_ISO = "1970-01-01T00:00:00Z"
private const val LEGACY_PLACEHOLDER_COLLECTION_ID = "9eb44de7-bdfb-5bea-85d1-be913c542d8b"
private const val FLAG_MIGRATED_MANUAL_WANT_READ = "migrated_manual_inbox_to_want_read_v1"

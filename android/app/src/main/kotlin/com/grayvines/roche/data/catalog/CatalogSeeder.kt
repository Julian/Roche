package com.grayvines.roche.data.catalog

import com.grayvines.roche.core.DEFAULT_LOCAL_HLC_NODE
import com.grayvines.roche.core.HlcTimestamp
import com.grayvines.roche.core.PROTECTED_SYSTEM_COLLECTION_IDS
import com.grayvines.roche.core.SEED_EPOCH_ISO
import com.grayvines.roche.core.STARRED_COLLECTION_ID
import com.grayvines.roche.core.STARRED_SLUG
import com.grayvines.roche.core.STARRED_TITLE
import com.grayvines.roche.core.UP_NEXT_COLLECTION_ID
import com.grayvines.roche.core.UP_NEXT_SLUG
import com.grayvines.roche.core.UP_NEXT_TITLE
import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.core.WANT_TO_READ_SLUG
import com.grayvines.roche.core.WANT_TO_READ_TITLE
import com.grayvines.roche.core.nowUtcIso
import com.grayvines.roche.core.systemCollectionUuid
import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.DbWriteLock
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryIdentifierEntity
import com.grayvines.roche.data.local.LocalSyncFlagEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.buildSeededCollectionEntry
import com.grayvines.roche.data.local.buildStarredCollectionSpec
import com.grayvines.roche.data.local.buildWantToReadCollectionSpec
import com.grayvines.roche.data.local.deleteEntryCascade
import com.grayvines.roche.data.local.nextLocalHlc

internal class CatalogSeeder(
    private val dao: RocheDao,
    private val writeLock: DbWriteLock = DbWriteLock(),
    private val localPeerIdProvider: suspend () -> String = { DEFAULT_LOCAL_HLC_NODE },
) {
    @Volatile private var systemSeeded = false

    suspend fun ensureSystemSeeded() {
        if (systemSeeded) return
        writeLock.withLock {
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
        if (UP_NEXT_COLLECTION_ID !in existingEntryIds) {
            entriesToInsert.add(
                buildSeededCollectionEntry(UP_NEXT_COLLECTION_ID, UP_NEXT_TITLE, seedHlc)
            )
        }
        if (UP_NEXT_COLLECTION_ID !in existingSpecs) {
            specsToInsert.add(
                CollectionSpecEntity(
                    collectionId = UP_NEXT_COLLECTION_ID,
                    slug = UP_NEXT_SLUG,
                    mode = CollectionMode.MANUAL.wireValue,
                    role = CollectionRole.SYSTEM.wireValue,
                    showInLists = false,
                )
            )
            identsToInsert.add(EntryIdentifierEntity("slug", UP_NEXT_SLUG, UP_NEXT_COLLECTION_ID))
        }
        if (WANT_TO_READ_COLLECTION_ID !in existingEntryIds) {
            entriesToInsert.add(
                buildSeededCollectionEntry(WANT_TO_READ_COLLECTION_ID, WANT_TO_READ_TITLE, seedHlc)
            )
        }
        if (WANT_TO_READ_COLLECTION_ID !in existingSpecs) {
            specsToInsert.add(buildWantToReadCollectionSpec())
            identsToInsert.add(
                EntryIdentifierEntity("slug", WANT_TO_READ_SLUG, WANT_TO_READ_COLLECTION_ID)
            )
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
                buildSeededCollectionEntry(STARRED_COLLECTION_ID, STARRED_TITLE, seedHlc)
            )
        }
        val existingStarred = existingSpecs[STARRED_COLLECTION_ID]
        if (
            existingStarred == null ||
                !existingStarred.showInLists ||
                existingStarred.role != CollectionRole.LIST.wireValue
        ) {
            specsToInsert.add(buildStarredCollectionSpec(existingStarred?.color))
            identsToInsert.add(EntryIdentifierEntity("slug", STARRED_SLUG, STARRED_COLLECTION_ID))
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
        val nodeId = localPeerIdProvider().ifBlank { DEFAULT_LOCAL_HLC_NODE }
        dao.executeInTransaction {
            val hlc = nextLocalHlc(dao, nodeId)
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
                val freshState = dao.getEntryStateById(state.entryId) ?: state
                dao.upsertEntryState(
                    freshState.copy(
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
}

private const val LEGACY_PLACEHOLDER_COLLECTION_ID = "9eb44de7-bdfb-5bea-85d1-be913c542d8b"
private const val FLAG_MIGRATED_MANUAL_WANT_READ = "migrated_manual_inbox_to_want_read_v1"

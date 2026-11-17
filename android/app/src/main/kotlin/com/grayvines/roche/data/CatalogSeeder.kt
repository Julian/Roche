package com.grayvines.roche.data

import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryIdentifierEntity
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
        val manualSystems =
            listOf(
                Triple(STARRED_COLLECTION_ID, "starred", "Starred"),
                Triple(UP_NEXT_COLLECTION_ID, "up_next", "Up Next"),
            )
        for ((id, slug, title) in manualSystems) {
            if (id !in existingEntryIds) {
                entriesToInsert.add(buildSeededCollectionEntry(id, title, seedHlc))
            }
            if (id !in existingSpecs) {
                specsToInsert.add(
                    CollectionSpecEntity(
                        collectionId = id,
                        slug = slug,
                        mode = CollectionMode.MANUAL.wireValue,
                        role = CollectionRole.SYSTEM.wireValue,
                        showInLists = false,
                    )
                )
                identsToInsert.add(EntryIdentifierEntity("slug", slug, id))
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

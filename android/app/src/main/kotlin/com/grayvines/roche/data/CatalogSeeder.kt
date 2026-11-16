package com.grayvines.roche.data

import com.grayvines.roche.data.local.CatalogEntryEntity
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.EntryIdentifierEntity
import com.grayvines.roche.data.local.RocheDao
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
        val existingEntryIds = dao.getAllCatalogEntries().map { it.id }.toHashSet()
        val specsToInsert = ArrayList<CollectionSpecEntity>()
        val identsToInsert = ArrayList<EntryIdentifierEntity>()

        collectManualSystemSpecs(existingSpecs, specsToInsert, identsToInsert)
        collectDynamicSystemSpecs(existingSpecs, specsToInsert, identsToInsert)
        val missingCollectionEntries =
            (existingSpecs.values + specsToInsert)
                .filter { it.collectionId !in existingEntryIds }
                .map(::buildCollectionCatalogEntry)

        dao.executeInTransaction {
            if (specsToInsert.isNotEmpty()) {
                dao.upsertCollectionSpecs(specsToInsert)
            }
            if (missingCollectionEntries.isNotEmpty()) {
                dao.upsertCatalogEntries(missingCollectionEntries)
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
                dao.deleteCollectionGenerator(LEGACY_PLACEHOLDER_COLLECTION_ID)
                dao.deleteCollectionSpec(LEGACY_PLACEHOLDER_COLLECTION_ID)
                dao.deleteIdentifiersForEntry(LEGACY_PLACEHOLDER_COLLECTION_ID)
                dao.deleteCatalogEntryById(LEGACY_PLACEHOLDER_COLLECTION_ID)
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

    private fun collectManualSystemSpecs(
        existingSpecs: Map<String, CollectionSpecEntity>,
        specsToInsert: MutableList<CollectionSpecEntity>,
        identsToInsert: MutableList<EntryIdentifierEntity>,
    ) {
        val manualSystems =
            listOf(
                Triple(STARRED_COLLECTION_ID, "starred", "Starred"),
                Triple(UP_NEXT_COLLECTION_ID, "up_next", "Up Next"),
            )
        for ((id, slug, title) in manualSystems) {
            if (id in existingSpecs) continue
            specsToInsert.add(
                CollectionSpecEntity(
                    collectionId = id,
                    title = title,
                    slug = slug,
                    mode = CollectionMode.MANUAL.wireValue,
                    role = CollectionRole.SYSTEM.wireValue,
                    showInLists = false,
                    createdAt = SEED_EPOCH_ISO,
                    updatedAt = SEED_EPOCH_ISO,
                    isLocalDirty = false,
                )
            )
            identsToInsert.add(EntryIdentifierEntity("slug", slug, id))
        }
    }

    private fun collectDynamicSystemSpecs(
        existingSpecs: Map<String, CollectionSpecEntity>,
        specsToInsert: MutableList<CollectionSpecEntity>,
        identsToInsert: MutableList<EntryIdentifierEntity>,
    ) {
        for (kind in EntryKind.systemDynamicKinds) {
            val id = systemCollectionUuid(kind.systemListSlug)
            if (id in existingSpecs) continue
            specsToInsert.add(
                CollectionSpecEntity(
                    collectionId = id,
                    title = kind.systemListTitle,
                    slug = kind.systemListSlug,
                    mode = CollectionMode.DYNAMIC.wireValue,
                    role = CollectionRole.LIST.wireValue,
                    ruleJson = """{"kind":"${kind.wireValue}","consumed":true}""",
                    showInLists = true,
                    createdAt = SEED_EPOCH_ISO,
                    updatedAt = SEED_EPOCH_ISO,
                    isLocalDirty = false,
                )
            )
            identsToInsert.add(EntryIdentifierEntity("slug", kind.systemListSlug, id))
        }
    }

    private fun buildCollectionCatalogEntry(spec: CollectionSpecEntity): CatalogEntryEntity =
        CatalogEntryEntity(
            id = spec.collectionId,
            kind = EntryKind.COLLECTION.wireValue,
            title = spec.title,
            creatorsJson = "[]",
            summary = spec.summary,
            metadataJson = "{}",
            createdAt = spec.createdAt,
            updatedAt = spec.updatedAt,
            isLocalDirty = spec.isLocalDirty,
        )
}

private const val SEED_EPOCH_ISO = "1970-01-01T00:00:00Z"
private const val LEGACY_PLACEHOLDER_COLLECTION_ID = "9eb44de7-bdfb-5bea-85d1-be913c542d8b"

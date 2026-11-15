package com.grayvines.roche.data

import com.grayvines.roche.data.local.CatalogEntryEntity
import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.CollectionGeneratorEntity
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.EntryIdentifierEntity
import com.grayvines.roche.data.local.RocheDao
import java.io.InputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal class CatalogSeeder(
    private val dao: RocheDao,
    private val poolStreamProvider: (() -> InputStream?)? = null,
) {
    private val seedMutex = Mutex()
    @Volatile private var systemSeeded = false
    @Volatile private var poolSeeded = false

    suspend fun ensureSystemSeeded() {
        if (systemSeeded) return
        seedMutex.withLock {
            if (!systemSeeded) {
                seedSystemSpecs()
                systemSeeded = true
            }
        }
    }

    suspend fun ensure1001PoolSeeded() {
        ensureSystemSeeded()
        if (poolSeeded) return
        seedMutex.withLock {
            if (!poolSeeded) {
                poolSeeded = seedBundled1001Pool()
            }
        }
    }

    private suspend fun seedSystemSpecs() {
        val existingSpecs = dao.getAllCollectionSpecs().associateBy { it.collectionId }
        val specsToInsert = ArrayList<CollectionSpecEntity>()
        val identsToInsert = ArrayList<EntryIdentifierEntity>()

        collectManualSystemSpecs(existingSpecs, specsToInsert, identsToInsert)
        collectDynamicSystemSpecs(existingSpecs, specsToInsert, identsToInsert)

        if (specsToInsert.isNotEmpty()) {
            dao.upsertCollectionSpecs(specsToInsert)
        }
        if (identsToInsert.isNotEmpty()) {
            dao.insertEntryIdentifiersIgnore(identsToInsert)
        }
        if (dao.getCollectionGenerator(ALBUMS_1001_COLLECTION_ID) == null) {
            dao.upsertCollectionGenerator(
                CollectionGeneratorEntity(
                    collectionId = ALBUMS_1001_COLLECTION_ID,
                    enabled = false,
                    currentEntryId = null,
                    drawnAt = null,
                    updatedAt = SEED_EPOCH_ISO,
                    isLocalDirty = false,
                )
            )
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
                Triple(ALBUMS_1001_COLLECTION_ID, "1001_albums", "1001 Albums"),
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

    private suspend fun seedBundled1001Pool(): Boolean {
        if (dao.countCollectionEntries(ALBUMS_1001_COLLECTION_ID) >= MIN_SEEDED_1001_COUNT) {
            return true
        }
        val bundled = loadBundled1001Albums(poolStreamProvider)
        if (bundled.isEmpty()) return false

        val existingById = dao.getAllCatalogEntries().associateBy { it.id }
        val entries = ArrayList<CatalogEntryEntity>(bundled.size)
        val idents = ArrayList<EntryIdentifierEntity>(bundled.size * 2)
        val memberships = ArrayList<CollectionEntryEntity>(bundled.size)

        bundled.forEachIndexed { idx, album ->
            if (album.id !in existingById) {
                entries.add(buildPoolAlbumEntity(album))
            }
            collectPoolAlbumIdentifiers(album, idents)
            memberships.add(
                CollectionEntryEntity(
                    collectionId = ALBUMS_1001_COLLECTION_ID,
                    entryId = album.id,
                    position = (idx + 1).toDouble(),
                    addedAt = SEED_EPOCH_ISO,
                    isLocalDirty = false,
                )
            )
        }

        entries.chunked(BATCH_CHUNK).forEach { dao.upsertCatalogEntries(it) }
        idents.chunked(BATCH_CHUNK).forEach { dao.insertEntryIdentifiersIgnore(it) }
        memberships.chunked(BATCH_CHUNK).forEach { dao.upsertCollectionEntries(it) }
        return true
    }

    private fun buildPoolAlbumEntity(album: BundledAlbumPoolItem): CatalogEntryEntity {
        val metadata = buildJsonObject {
            if (album.genres.isNotEmpty()) {
                put("genres", JsonArray(album.genres.map(::JsonPrimitive)))
            }
            if (album.styles.isNotEmpty()) {
                put("styles", JsonArray(album.styles.map(::JsonPrimitive)))
            }
        }
            .toString()
        return CatalogEntryEntity(
            id = album.id,
            kind = EntryKind.ALBUM.wireValue,
            title = album.title,
            creatorsJson = encodeCreatorsJson(listOf(album.artist)),
            releaseYear = album.year,
            thumbnailUrl = sanitizeHttpUrl(album.coverUrl),
            metadataJson = metadata,
            createdAt = SEED_EPOCH_ISO,
            updatedAt = SEED_EPOCH_ISO,
            isLocalDirty = false,
        )
    }

    private fun collectPoolAlbumIdentifiers(
        album: BundledAlbumPoolItem,
        idents: MutableList<EntryIdentifierEntity>,
    ) {
        if (album.slug.isNotBlank()) {
            idents.add(EntryIdentifierEntity("1001albums", album.slug.trim(), album.id))
        }
        if (album.spotifyId.isNotBlank()) {
            idents.add(EntryIdentifierEntity("spotify", album.spotifyId.trim(), album.id))
        }
    }
}

private const val SEED_EPOCH_ISO = "1970-01-01T00:00:00Z"
private const val MIN_SEEDED_1001_COUNT = 1_000
private const val BATCH_CHUNK = 400

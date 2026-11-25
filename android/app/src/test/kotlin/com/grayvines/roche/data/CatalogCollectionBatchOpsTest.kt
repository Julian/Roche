package com.grayvines.roche.data

import com.grayvines.roche.core.WANT_TO_READ_COLLECTION_ID
import com.grayvines.roche.data.catalog.CollectionRole
import com.grayvines.roche.data.local.EntryEntity
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CatalogCollectionBatchOpsTest {
    @TempDir lateinit var tempDir: File

    private lateinit var env: TestRocheEnv

    @BeforeEach
    fun setUp() {
        env = TestRocheEnv(tempDir, "catalog_batch_ops.preferences_pb")
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    @Test
    fun `addEntryToCollections deduplicates aliases and tolerates missing collections when configured`() =
        runTest {
            env.repository.catalog.ensureSystemSeeded()
            val watchQueueId =
                env.repository.catalog.createCustomList("Want to Watch", CollectionRole.WANT)
            env.database
                .dao()
                .upsertEntries(
                    listOf(
                        EntryEntity(
                            id = "batch-entry-1",
                            kind = "article",
                            title = "Batch Entry",
                            createdAt = "2026-05-01T00:00:00Z",
                            updatedAt = "2026-05-01T00:00:00Z",
                        )
                    )
                )

            val added =
                env.repository.catalog.addEntryToCollections(
                    collectionIds =
                        listOf(
                            WANT_TO_READ_COLLECTION_ID,
                            "want:read",
                            watchQueueId,
                            "deleted-collection-id",
                        ),
                    entryId = "batch-entry-1",
                    ignoreMissingCollections = true,
                )
            assertEquals(listOf(WANT_TO_READ_COLLECTION_ID, watchQueueId), added)
            assertEquals(
                setOf(WANT_TO_READ_COLLECTION_ID, watchQueueId),
                env.repository.catalog.findWantCollectionsForEntry("batch-entry-1").toSet(),
            )

            val noneAdded =
                env.repository.catalog.addEntryToCollections(
                    collectionIds = listOf("deleted-collection-id"),
                    entryId = "batch-entry-1",
                    ignoreMissingCollections = true,
                )
            assertTrue(noneAdded.isEmpty())
        }
}

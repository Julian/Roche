package com.grayvines.roche.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.grayvines.roche.core.canonicalOrderingJson
import com.grayvines.roche.core.deriveCollectionIdentity
import com.grayvines.roche.core.encodeCreatorsJson
import com.grayvines.roche.core.externalIdentifierUrl
import com.grayvines.roche.core.extractYoutubeVideoId
import com.grayvines.roche.core.inferContentTypeWireValue
import com.grayvines.roche.core.inferFeedTypeWireValue
import com.grayvines.roche.core.isArxivUrl
import com.grayvines.roche.core.isPublicIpAddress
import com.grayvines.roche.core.isSyncablePublicUrl
import com.grayvines.roche.core.isYoutubeFeedUrl
import com.grayvines.roche.data.catalog.CatalogStore
import com.grayvines.roche.data.catalog.RankClusterCandidate
import com.grayvines.roche.data.catalog.SyncCollectionEntryDto
import com.grayvines.roche.data.catalog.SyncCollectionGeneratorDto
import com.grayvines.roche.data.catalog.SyncComparisonDto
import com.grayvines.roche.data.catalog.SyncConsumptionLogDto
import com.grayvines.roche.data.catalog.SyncEntryRecordDto
import com.grayvines.roche.data.catalog.decodeComparisonOrdering
import com.grayvines.roche.data.enrichment.WikipediaSearchHit
import com.grayvines.roche.data.enrichment.extractSpotifyArtistUri
import com.grayvines.roche.data.enrichment.scoreArtistWikiCandidate
import com.grayvines.roche.data.enrichment.selectBestArtistWikiSlug
import com.grayvines.roche.data.local.CollectionEntryEntity
import com.grayvines.roche.data.local.CollectionGeneratorEntity
import com.grayvines.roche.data.local.CollectionSpecEntity
import com.grayvines.roche.data.local.ComparisonEntity
import com.grayvines.roche.data.local.ConsumptionLogEntity
import com.grayvines.roche.data.local.EntryEntity
import com.grayvines.roche.data.local.EntryStateEntity
import com.grayvines.roche.data.local.RocheDao
import com.grayvines.roche.data.local.RocheDatabase
import com.grayvines.roche.data.local.TombstoneEntity
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.remote.rocheJson
import com.grayvines.roche.data.sync.SyncTombstoneDto
import com.grayvines.roche.data.sync.reconcilePeerSyncResponse
import com.grayvines.roche.model.inferContentType
import com.grayvines.roche.ranking.RankBoardState
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Serializable
internal data class CustomCollectionIdVector(
    val title: String,
    val role: String,
    @SerialName("expected_slug") val expectedSlug: String? = null,
    @SerialName("expected_collection_id") val expectedCollectionId: String? = null,
)

@Serializable
internal data class UrlClassificationVector(
    val url: String,
    @SerialName("expected_content_type") val expectedContentType: String,
    @SerialName("expected_reader_class") val expectedReaderClass: String,
    @SerialName("expected_feed_type") val expectedFeedType: String,
    @SerialName("expected_is_youtube_feed") val expectedIsYoutubeFeed: Boolean,
    @SerialName("expected_youtube_video_id") val expectedYoutubeVideoId: String? = null,
    @SerialName("expected_is_arxiv") val expectedIsArxiv: Boolean,
)

@Serializable
internal data class IpValidationVector(
    val ip: String,
    @SerialName("expected_allowed") val expectedAllowed: Boolean,
)

@Serializable
internal data class UrlValidationVector(
    val url: String,
    @SerialName("expected_allowed") val expectedAllowed: Boolean,
)

@Serializable
internal data class SsrfValidationSuite(
    val ips: List<IpValidationVector>,
    val urls: List<UrlValidationVector>,
)

@Serializable
internal data class ExternalIdentifierUrlVector(
    val scheme: String,
    val value: String,
    val kind: String? = null,
    @SerialName("expected_url") val expectedUrl: String? = null,
)

@Serializable
internal data class RankBoardOpVector(
    val op: String,
    @SerialName("entry_id") val entryId: String? = null,
    @SerialName("target_slot") val targetSlot: Int? = null,
)

@Serializable
internal data class RankBoardScenarioVector(
    val name: String,
    @SerialName("initial_items") val initialItems: List<String>,
    val ops: List<RankBoardOpVector>,
    @SerialName("expected_ordering") val expectedOrdering: List<List<String>>,
    @SerialName("expected_display_ranks") val expectedDisplayRanks: List<Int>,
    @SerialName("expected_tied_with_above") val expectedTiedWithAbove: List<Boolean>,
)

@Serializable
internal data class WikiHitVector(
    val title: String,
    val snippet: String,
)

@Serializable
internal data class WikiScoringVector(
    val name: String,
    @SerialName("artist_name") val artistName: String,
    @SerialName("child_titles") val childTitles: List<String>,
    val hits: List<WikiHitVector>,
    @SerialName("expected_scores") val expectedScores: List<Int>,
    @SerialName("expected_winner") val expectedWinner: String? = null,
)

@Serializable
internal data class SpotifyHtmlVector(
    val name: String,
    @SerialName("artist_name") val artistName: String,
    @SerialName("allow_single_fallback") val allowSingleFallback: Boolean,
    val html: String,
    @SerialName("expected_uri") val expectedUri: String? = null,
)

@Serializable
internal data class ArtistEnrichmentSuite(
    @SerialName("wikipedia_scoring") val wikipediaScoring: List<WikiScoringVector>,
    @SerialName("spotify_html_extraction") val spotifyHtmlExtraction: List<SpotifyHtmlVector>,
)

@Serializable
internal data class InitialSyncVector(
    val entries: List<SyncEntryRecordDto> = emptyList(),
    @SerialName("collection_entries")
    val collectionEntries: List<SyncCollectionEntryDto> = emptyList(),
    @SerialName("consumption_log") val consumptionLog: List<SyncConsumptionLogDto> = emptyList(),
    val comparisons: List<SyncComparisonDto> = emptyList(),
    @SerialName("collection_generators")
    val collectionGenerators: List<SyncCollectionGeneratorDto> = emptyList(),
    val tombstones: List<SyncTombstoneDto> = emptyList(),
)

@Serializable
internal data class IncomingSyncVector(
    @SerialName("peer_id") val peerId: String,
    @SerialName("sync_timestamp") val syncTimestamp: String,
    val entries: List<SyncEntryRecordDto> = emptyList(),
    @SerialName("collection_entries")
    val collectionEntries: List<SyncCollectionEntryDto> = emptyList(),
    @SerialName("consumption_log") val consumptionLog: List<SyncConsumptionLogDto> = emptyList(),
    val comparisons: List<SyncComparisonDto> = emptyList(),
    @SerialName("collection_generators")
    val collectionGenerators: List<SyncCollectionGeneratorDto> = emptyList(),
    val tombstones: List<SyncTombstoneDto> = emptyList(),
)

@Serializable
internal data class ExpectedEntryStateVector(
    @SerialName("entry_id") val entryId: String,
    @SerialName("inbox_state") val inboxState: String,
    @SerialName("read_at_present") val readAtPresent: Boolean,
    val progress: Double,
    val hlc: String,
)

@Serializable
internal data class ExpectedEntryPresenceVector(
    @SerialName("entry_id") val entryId: String,
    val exists: Boolean,
    @SerialName("merged_into_id") val mergedIntoId: String? = null,
)

@Serializable
internal data class ExpectedCollectionMembersVector(
    @SerialName("collection_id") val collectionId: String,
    @SerialName("entry_ids") val entryIds: List<String>,
)

@Serializable
internal data class ExpectedLogCountVector(
    @SerialName("entry_id") val entryId: String,
    val count: Int,
)

@Serializable
internal data class ExpectedGeneratorVector(
    @SerialName("collection_id") val collectionId: String,
    val enabled: Boolean,
    @SerialName("current_entry_id") val currentEntryId: String? = null,
)

@Serializable
internal data class ExpectedTombstonePresenceVector(
    @SerialName("entity_type") val entityType: String,
    @SerialName("natural_key") val naturalKey: String,
    val exists: Boolean,
)

@Serializable
internal data class ExpectedSyncOutcomeVector(
    @SerialName("entry_states") val entryStates: List<ExpectedEntryStateVector> = emptyList(),
    @SerialName("entry_presence")
    val entryPresence: List<ExpectedEntryPresenceVector> = emptyList(),
    @SerialName("collection_members")
    val collectionMembers: List<ExpectedCollectionMembersVector> = emptyList(),
    @SerialName("consumption_log_counts")
    val consumptionLogCounts: List<ExpectedLogCountVector> = emptyList(),
    @SerialName("comparison_orderings")
    val comparisonOrderings: List<List<List<String>>> = emptyList(),
    val generators: List<ExpectedGeneratorVector> = emptyList(),
    @SerialName("tombstone_presence")
    val tombstonePresence: List<ExpectedTombstonePresenceVector> = emptyList(),
)

@Serializable
internal data class PeerSyncScenarioVector(
    val name: String,
    val initial: InitialSyncVector,
    val incoming: IncomingSyncVector,
    val expected: ExpectedSyncOutcomeVector,
)

@Serializable
internal data class ExpandedCrossStackVectors(
    @SerialName("custom_collection_ids") val customCollectionIds: List<CustomCollectionIdVector>,
    @SerialName("url_classification") val urlClassification: List<UrlClassificationVector>,
    @SerialName("ssrf_url_validation") val ssrfUrlValidation: SsrfValidationSuite,
    @SerialName("external_identifier_urls")
    val externalIdentifierUrls: List<ExternalIdentifierUrlVector>,
    @SerialName("rank_board_operations") val rankBoardOperations: List<RankBoardScenarioVector>,
    @SerialName("artist_wikipedia_scoring") val artistWikipediaScoring: ArtistEnrichmentSuite,
    @SerialName("peer_sync_merge_scenarios")
    val peerSyncMergeScenarios: List<PeerSyncScenarioVector>,
)

class CrossStackSyncAndDomainVectorsTest {
    @Test
    fun `custom collection IDs and want convergence match Rust golden vectors`() {
        val vectors = loadExpandedGoldenVectors().customCollectionIds
        assertTrue(vectors.size >= MIN_CUSTOM_COLLECTION_VECTORS)
        vectors.forEach { vector ->
            val actual = deriveCollectionIdentity(vector.title, vector.role)
            if (vector.expectedSlug == null) {
                assertNull(actual, "Expected rejection for ('${vector.title}', '${vector.role}')")
            } else {
                assertNotNull(actual, "Expected valid ID for ('${vector.title}', '${vector.role}')")
                assertEquals(vector.expectedSlug, actual?.slug, "slug for '${vector.title}'")
                assertEquals(
                    vector.expectedCollectionId,
                    actual?.collectionId,
                    "collection_id for '${vector.title}'",
                )
            }
        }
    }

    @Test
    fun `url classification feed type and spoof defenses match Rust golden vectors`() {
        val vectors = loadExpandedGoldenVectors().urlClassification
        assertTrue(vectors.size >= MIN_URL_CLASSIFICATION_VECTORS)
        vectors.forEach { vector ->
            assertEquals(
                vector.expectedContentType,
                inferContentTypeWireValue(vector.url),
                "content_type mismatch for ${vector.url}",
            )
            assertEquals(
                vector.expectedReaderClass,
                inferContentType(vector.url).wireValue,
                "reader_class mismatch for ${vector.url}",
            )
            assertEquals(
                vector.expectedFeedType,
                inferFeedTypeWireValue(vector.url),
                "feed_type mismatch for ${vector.url}",
            )
            assertEquals(
                vector.expectedIsYoutubeFeed,
                isYoutubeFeedUrl(vector.url),
                "is_youtube_feed mismatch for ${vector.url}",
            )
            assertEquals(
                vector.expectedYoutubeVideoId,
                extractYoutubeVideoId(vector.url),
                "youtube_video_id mismatch for ${vector.url}",
            )
            assertEquals(
                vector.expectedIsArxiv,
                isArxivUrl(vector.url),
                "is_arxiv mismatch for ${vector.url}",
            )
        }
    }

    @Test
    fun `SSRF IP and URL validation rules match Rust golden vectors`() {
        val suite = loadExpandedGoldenVectors().ssrfUrlValidation
        assertTrue(suite.ips.size >= MIN_SSRF_IP_VECTORS)
        assertTrue(suite.urls.size >= MIN_SSRF_URL_VECTORS)
        suite.ips.forEach { vector ->
            assertEquals(
                vector.expectedAllowed,
                isPublicIpAddress(vector.ip),
                "isPublicIpAddress mismatch for ${vector.ip}",
            )
        }
        suite.urls.forEach { vector ->
            assertEquals(
                vector.expectedAllowed,
                isSyncablePublicUrl(vector.url),
                "isSyncablePublicUrl mismatch for ${vector.url}",
            )
        }
    }

    @Test
    fun `external identifier URLs match Rust golden vectors across schemes and kinds`() {
        val vectors = loadExpandedGoldenVectors().externalIdentifierUrls
        assertTrue(vectors.size >= MIN_EXTERNAL_ID_URL_VECTORS)
        vectors.forEach { vector ->
            assertEquals(
                vector.expectedUrl,
                externalIdentifierUrl(vector.scheme, vector.value, vector.kind),
                "externalIdentifierUrl mismatch for (${vector.scheme}, '${vector.value}', ${vector.kind})",
            )
        }
    }

    @Test
    fun `RankBoardState multi-step operations match Rust golden vectors`() {
        val vectors = loadExpandedGoldenVectors().rankBoardOperations
        assertTrue(vectors.size >= MIN_RANK_BOARD_SCENARIOS)
        vectors.forEach { scenario ->
            var board =
                RankBoardState.fromCandidates(
                    scenario.initialItems.map { id ->
                        RankClusterCandidate(
                            entryId = id,
                            title = id.uppercase(),
                            subtitle = "",
                            thumbnailUrl = null,
                            mean = 0.0,
                            variance = 1.0,
                        )
                    }
                )
            scenario.ops.forEach { step -> board = applyRankBoardVectorOp(board, step) }
            assertEquals(
                scenario.expectedOrdering,
                board.toOrderingGroups(),
                "ordering mismatch for ${scenario.name}",
            )
            val rows = board.rows()
            assertEquals(
                scenario.expectedDisplayRanks,
                rows.map { it.displayRank },
                "display_ranks mismatch for ${scenario.name}",
            )
            assertEquals(
                scenario.expectedTiedWithAbove,
                rows.map { it.isTiedWithAbove },
                "tied_with_above mismatch for ${scenario.name}",
            )
        }
    }

    @Test
    fun `artist Wikipedia scoring and Spotify HTML extraction match Rust golden vectors`() {
        val suite = loadExpandedGoldenVectors().artistWikipediaScoring
        assertTrue(suite.wikipediaScoring.size >= MIN_WIKI_SCORING_SCENARIOS)
        assertTrue(suite.spotifyHtmlExtraction.size >= MIN_SPOTIFY_HTML_VECTORS)
        suite.wikipediaScoring.forEach { scenario ->
            val hits = scenario.hits.map { WikipediaSearchHit(it.title, it.snippet) }
            val actualScores = hits.map { hit ->
                scoreArtistWikiCandidate(hit, scenario.artistName, scenario.childTitles)
            }
            assertEquals(
                scenario.expectedScores,
                actualScores,
                "scores mismatch for ${scenario.name}",
            )
            assertEquals(
                scenario.expectedWinner,
                selectBestArtistWikiSlug(hits, scenario.artistName, scenario.childTitles),
                "winner mismatch for ${scenario.name}",
            )
        }
        suite.spotifyHtmlExtraction.forEach { vector ->
            assertEquals(
                vector.expectedUri,
                extractSpotifyArtistUri(vector.html, vector.artistName, vector.allowSingleFallback),
                "spotify html mismatch for ${vector.name}",
            )
        }
    }

    @Test
    fun `peer sync merge conflict scenarios converge identically to Rust`() = runTest {
        val scenarios = loadExpandedGoldenVectors().peerSyncMergeScenarios
        assertTrue(scenarios.size >= MIN_PEER_SYNC_SCENARIOS)
        for (scenario in scenarios) {
            val db =
                Room.inMemoryDatabaseBuilder<RocheDatabase>()
                    .setDriver(BundledSQLiteDriver())
                    .build()
            try {
                runPeerSyncScenario(db.dao(), scenario)
            } finally {
                db.close()
            }
        }
    }
}

internal const val MIN_CUSTOM_COLLECTION_VECTORS = 15
internal const val MIN_URL_CLASSIFICATION_VECTORS = 15
internal const val MIN_SSRF_IP_VECTORS = 25
internal const val MIN_SSRF_URL_VECTORS = 20
internal const val MIN_EXTERNAL_ID_URL_VECTORS = 30
internal const val MIN_RANK_BOARD_SCENARIOS = 4
internal const val MIN_WIKI_SCORING_SCENARIOS = 5
internal const val MIN_SPOTIFY_HTML_VECTORS = 6
internal const val MIN_PEER_SYNC_SCENARIOS = 4
internal const val SYNC_PROGRESS_EPSILON = 1e-9

internal fun loadExpandedGoldenVectors(): ExpandedCrossStackVectors {
    val vectorFile =
        listOf(
                File("../../testdata/cross_stack_vectors.json"),
                File("../testdata/cross_stack_vectors.json"),
                File("testdata/cross_stack_vectors.json"),
            )
            .firstOrNull { it.isFile }
            ?: error("Unable to locate testdata/cross_stack_vectors.json")
    return rocheJson.decodeFromString(
        ExpandedCrossStackVectors.serializer(),
        vectorFile.readText(Charsets.UTF_8),
    )
}

internal fun applyRankBoardVectorOp(
    board: RankBoardState,
    step: RankBoardOpVector,
): RankBoardState =
    when (step.op) {
        "move_up" -> board.moveUp(checkNotNull(step.entryId))
        "move_down" -> board.moveDown(checkNotNull(step.entryId))
        "toggle_tie_with_above" -> board.toggleTieWithAbove(checkNotNull(step.entryId))
        "move_to_rank_slot" ->
            board.moveToRankSlot(checkNotNull(step.entryId), checkNotNull(step.targetSlot))
        "toggle_tie_all" -> board.toggleTieAll()
        else -> error("Unknown RankBoard op: ${step.op}")
    }

internal suspend fun runPeerSyncScenario(dao: RocheDao, scenario: PeerSyncScenarioVector) {
    val catalog = CatalogStore(dao = dao, localPeerIdProvider = { "local-peer" })
    catalog.ensureSystemSeeded()
    seedInitialSyncStateInDao(dao, scenario.initial, scenario.incoming.syncTimestamp)

    val response =
        PeerSyncResponseDto(
            serverPeerId = scenario.incoming.peerId,
            syncTimestamp = scenario.incoming.syncTimestamp,
            hasMore = false,
            entries = scenario.incoming.entries,
            collectionEntries = scenario.incoming.collectionEntries,
            collectionGenerators = scenario.incoming.collectionGenerators,
            consumptionLog = scenario.incoming.consumptionLog,
            comparisons = scenario.incoming.comparisons,
            tombstones = scenario.incoming.tombstones,
        )
    reconcilePeerSyncResponse(
        dao = dao,
        response = response,
        localPeerId = "local-peer",
        catalogStore = catalog,
    )

    assertExpectedEntriesInDao(dao, scenario.name, scenario.expected)
    assertExpectedGraphInDao(dao, scenario.name, scenario.expected)
}

internal suspend fun seedInitialSyncStateInDao(
    dao: RocheDao,
    initial: InitialSyncVector,
    fallbackIso: String,
) {
    for (entry in initial.entries) {
        val created = entry.createdAt ?: fallbackIso
        val updated = entry.updatedAt ?: created
        dao.upsertEntry(
            EntryEntity(
                id = entry.id,
                kind = entry.kind,
                title = entry.title,
                creatorsJson = encodeCreatorsJson(entry.creators),
                url = entry.url,
                publishedAt = entry.publishedAt,
                releaseYear = entry.releaseYear,
                summary = entry.summary,
                thumbnailUrl = entry.thumbnailUrl,
                mergedIntoId = entry.mergedIntoId,
                createdAt = created,
                updatedAt = updated,
                hlc = entry.hlc,
                isLocalDirty = false,
            )
        )
        entry.collectionSpec?.let { spec ->
            dao.upsertCollectionSpec(
                CollectionSpecEntity(
                    collectionId = entry.id,
                    slug = spec.slug,
                    mode = spec.mode,
                    role = spec.role,
                    ruleJson = spec.ruleJson,
                    showInLists = spec.showInLists,
                    color = spec.color,
                )
            )
        }
        entry.state?.let { st ->
            dao.upsertEntryState(
                EntryStateEntity(
                    entryId = entry.id,
                    inboxState = st.inboxState,
                    readAt = st.readAt,
                    progress = st.progress,
                    interactionUpdatedAt = st.interactionUpdatedAt,
                    updatedAt = st.interactionUpdatedAt ?: updated,
                    hlc = st.hlc,
                    isLocalDirty = false,
                )
            )
        }
    }
    seedInitialSyncEdgesInDao(dao, initial)
}

internal suspend fun seedInitialSyncEdgesInDao(dao: RocheDao, initial: InitialSyncVector) {
    for (mem in initial.collectionEntries) {
        dao.upsertCollectionEntry(
            CollectionEntryEntity(
                collectionId = mem.collectionId,
                entryId = mem.entryId,
                position = mem.position,
                addedAt = mem.addedAt,
                hlc = mem.hlc,
                isLocalDirty = false,
            )
        )
    }
    for (log in initial.consumptionLog) {
        dao.insertConsumptionLogIgnore(
            ConsumptionLogEntity(
                id = log.id,
                entryId = log.entryId,
                ts = log.ts,
                status = log.status,
                notes = log.notes,
                createdAt = log.createdAt ?: log.ts,
                hlc = log.hlc,
                isLocalDirty = false,
            )
        )
    }
    for (comp in initial.comparisons) {
        dao.insertComparisonIgnore(
            ComparisonEntity(
                id = comp.id,
                ts = comp.ts,
                scopeCollectionId = comp.scopeCollectionId,
                orderingJson = canonicalOrderingJson(comp.ordering),
                createdAt = comp.createdAt ?: comp.ts,
                hlc = comp.hlc,
                isLocalDirty = false,
            )
        )
    }
    seedInitialSyncGeneratorsAndTombstones(dao, initial)
}

internal suspend fun seedInitialSyncGeneratorsAndTombstones(
    dao: RocheDao,
    initial: InitialSyncVector,
) {
    for (gen in initial.collectionGenerators) {
        dao.upsertCollectionGenerator(
            CollectionGeneratorEntity(
                collectionId = gen.collectionId,
                enabled = gen.enabled,
                currentEntryId = gen.currentEntryId,
                drawnAt = gen.drawnAt,
                updatedAt = gen.updatedAt,
                hlc = gen.hlc,
                isLocalDirty = false,
            )
        )
    }
    for (tomb in initial.tombstones) {
        dao.upsertTombstone(
            TombstoneEntity(
                entityType = tomb.entityType,
                naturalKey = tomb.naturalKey,
                deletedAt = tomb.deletedAt,
                hlc = tomb.hlc,
                isLocalDirty = false,
            )
        )
    }
}

internal suspend fun assertExpectedEntriesInDao(
    dao: RocheDao,
    name: String,
    expected: ExpectedSyncOutcomeVector,
) {
    for (expState in expected.entryStates) {
        val actual =
            checkNotNull(dao.getEntryStateById(expState.entryId)) {
                "$name: missing entry_state for ${expState.entryId}"
            }
        assertEquals(
            expState.inboxState,
            actual.inboxState,
            "$name: inbox_state for ${expState.entryId}",
        )
        assertEquals(
            expState.readAtPresent,
            !actual.readAt.isNullOrBlank(),
            "$name: read_at for ${expState.entryId}",
        )
        assertEquals(
            expState.progress,
            actual.progress,
            SYNC_PROGRESS_EPSILON,
            "$name: progress for ${expState.entryId}",
        )
        assertEquals(expState.hlc, actual.hlc, "$name: state hlc for ${expState.entryId}")
    }
    for (expEntry in expected.entryPresence) {
        val actual = dao.getEntryById(expEntry.entryId)
        assertEquals(expEntry.exists, actual != null, "$name: exists for ${expEntry.entryId}")
        if (actual != null) {
            assertEquals(
                expEntry.mergedIntoId,
                actual.mergedIntoId,
                "$name: merged_into_id for ${expEntry.entryId}",
            )
        }
    }
}

internal suspend fun assertExpectedGraphInDao(
    dao: RocheDao,
    name: String,
    expected: ExpectedSyncOutcomeVector,
) {
    for (expCol in expected.collectionMembers) {
        val actual = dao.getCollectionMemberships(expCol.collectionId).map { it.entryId }.sorted()
        assertEquals(expCol.entryIds, actual, "$name: members for ${expCol.collectionId}")
    }
    if (expected.consumptionLogCounts.isNotEmpty()) {
        val allLogs = dao.getAllConsumptionLogs()
        for (expLog in expected.consumptionLogCounts) {
            val count = allLogs.count { it.entryId == expLog.entryId }
            assertEquals(expLog.count, count, "$name: log count for ${expLog.entryId}")
        }
    }
    if (expected.comparisonOrderings.isNotEmpty()) {
        val actualOrderings =
            dao.getAllComparisons()
                .mapNotNull { decodeComparisonOrdering(it.orderingJson) }
                .sortedBy { canonicalOrderingJson(it) }
        val expectedSorted = expected.comparisonOrderings.sortedBy { canonicalOrderingJson(it) }
        assertEquals(expectedSorted, actualOrderings, "$name: comparisons")
    }
    for (expGen in expected.generators) {
        val actual =
            checkNotNull(dao.getCollectionGenerator(expGen.collectionId)) {
                "$name: missing generator for ${expGen.collectionId}"
            }
        assertEquals(
            expGen.enabled,
            actual.enabled,
            "$name: gen enabled for ${expGen.collectionId}",
        )
        assertEquals(
            expGen.currentEntryId,
            actual.currentEntryId,
            "$name: gen current_entry_id for ${expGen.collectionId}",
        )
    }
    for (expTomb in expected.tombstonePresence) {
        val found = dao.getTombstone(expTomb.entityType, expTomb.naturalKey) != null
        assertEquals(
            expTomb.exists,
            found,
            "$name: tombstone (${expTomb.entityType}, ${expTomb.naturalKey})",
        )
    }
}

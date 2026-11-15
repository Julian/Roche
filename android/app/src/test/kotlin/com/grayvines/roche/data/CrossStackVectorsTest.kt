package com.grayvines.roche.data

import com.grayvines.roche.data.remote.rocheJson
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Serializable
internal data class CanonicalUrlVector(
    val input: String,
    val expected: String,
)

@Serializable
internal data class EntityIdVector(
    @SerialName("feed_url") val feedUrl: String,
    @SerialName("item_url") val itemUrl: String,
    @SerialName("tag_name") val tagName: String,
    @SerialName("expected_feed_id") val expectedFeedId: String,
    @SerialName("expected_item_id") val expectedItemId: String,
    @SerialName("expected_tag_id") val expectedTagId: String,
)

@Serializable
internal data class HrwPeerVector(
    @SerialName("peer_id") val peerId: String,
    val weight: Double,
)

@Serializable
internal data class HrwElectionVector(
    @SerialName("feed_url") val feedUrl: String,
    val peers: List<HrwPeerVector>,
    @SerialName("expected_winner") val expectedWinner: String,
)

@Serializable
internal data class IdentifierNormalizationVector(
    val kind: String,
    val scheme: String,
    @SerialName("raw_value") val rawValue: String,
    @SerialName("expected_scheme") val expectedScheme: String,
    @SerialName("expected_value") val expectedValue: String,
)

@Serializable
internal data class AlbumEditionVector(
    val input: String,
    val expected: String,
)

@Serializable
internal data class CatalogEntryIdVector(
    val kind: String,
    val title: String,
    val creators: List<String>,
    val identifiers: List<EntryIdentifierDto>,
    @SerialName("expected_id") val expectedId: String,
)

@Serializable
internal data class AutoConsumptionLogIdVector(
    @SerialName("entry_id") val entryId: String,
    @SerialName("expected_log_id") val expectedLogId: String,
)

@Serializable
internal data class DynamicRuleEntryFixture(
    val kind: String,
    val creators: List<String>,
    @SerialName("release_year") val releaseYear: Int? = null,
    val statuses: List<String> = emptyList(),
)

@Serializable
internal data class DynamicRuleVector(
    @SerialName("rule_json") val ruleJson: String,
    val entry: DynamicRuleEntryFixture,
    @SerialName("expected_match") val expectedMatch: Boolean,
)

@Serializable
internal data class RankedEntryFixture(
    val id: String,
    val title: String,
)

@Serializable
internal data class RankedCollectionParityVector(
    val name: String,
    @SerialName("is_priority_scope") val isPriorityScope: Boolean,
    val entries: List<RankedEntryFixture>,
    val orderings: List<List<List<String>>>,
    @SerialName("expected_ids") val expectedIds: List<String>,
    @SerialName("expected_tiers") val expectedTiers: List<String?>,
)

@Serializable
internal data class CrossStackGoldenVectors(
    @SerialName("canonical_urls") val canonicalUrls: List<CanonicalUrlVector>,
    @SerialName("entity_ids") val entityIds: List<EntityIdVector>,
    @SerialName("hrw_elections") val hrwElections: List<HrwElectionVector>,
    @SerialName("preferred_identifier_schemes") val preferredIdentifierSchemes: List<String>,
    @SerialName("identifier_normalizations")
    val identifierNormalizations: List<IdentifierNormalizationVector>,
    @SerialName("album_edition_stripping") val albumEditionStripping: List<AlbumEditionVector>,
    @SerialName("catalog_entry_ids") val catalogEntryIds: List<CatalogEntryIdVector>,
    @SerialName("auto_consumption_log_ids")
    val autoConsumptionLogIds: List<AutoConsumptionLogIdVector>,
    @SerialName("dynamic_collection_rules") val dynamicCollectionRules: List<DynamicRuleVector>,
    @SerialName("ranked_collection_parity")
    val rankedCollectionParity: List<RankedCollectionParityVector>,
)

class CrossStackVectorsTest {
    @Test
    fun `canonicalizeSyncUrl matches Rust golden vectors`() {
        val vectors = loadGoldenVectors()
        assertTrue(vectors.canonicalUrls.size >= MIN_CANONICAL_URL_VECTORS)
        vectors.canonicalUrls.forEach { vector ->
            assertEquals(
                vector.expected,
                canonicalizeSyncUrl(vector.input),
                "canonical_urls mismatch for '${vector.input}'",
            )
        }
    }

    @Test
    fun `deterministic feed item and tag UUIDs match Rust golden vectors`() {
        val vectors = loadGoldenVectors()
        assertTrue(vectors.entityIds.size >= MIN_ENTITY_ID_VECTORS)
        vectors.entityIds.forEach { vector ->
            assertEquals(vector.expectedFeedId, deterministicFeedId(vector.feedUrl))
            assertEquals(
                vector.expectedItemId,
                deterministicItemId(vector.feedUrl, vector.itemUrl),
            )
            assertEquals(vector.expectedTagId, deterministicTagId(vector.tagName))
        }
    }

    @Test
    fun `HRW rendezvous feed elections match Rust golden vectors`() {
        val vectors = loadGoldenVectors()
        assertTrue(vectors.hrwElections.size >= MIN_HRW_ELECTION_VECTORS)
        vectors.hrwElections.forEach { vector ->
            val peers =
                vector.peers.map { peer ->
                    PeerNodeDto(
                        peerId = peer.peerId,
                        deviceName = peer.peerId,
                        networkTier = NetworkTier.WIFI.wireValue,
                        weight = peer.weight,
                        lastSeenAt = "1970-01-01T00:00:00Z",
                    )
                }
            val winner =
                selectFeedPoller(
                    activePeers = peers,
                    feedUrl = vector.feedUrl,
                    pollIntervalMinutes = DEFAULT_VECTOR_POLL_MINUTES,
                    nowEpochSeconds = 0L,
                    lastPolledBy = null,
                    lastError = null,
                    lastErrorBy = null,
                )
            assertEquals(vector.expectedWinner, winner)
        }
    }

    @Test
    fun `deterministic system collection IDs match Rust constants`() {
        assertEquals(STARRED_COLLECTION_ID, systemCollectionUuid("starred"))
        assertEquals(UP_NEXT_COLLECTION_ID, systemCollectionUuid("up_next"))
        assertEquals(ALBUMS_1001_COLLECTION_ID, systemCollectionUuid("1001_albums"))
    }

    @Test
    fun `preferred identifier schemes and normalizations match Rust golden vectors`() {
        val vectors = loadGoldenVectors()
        assertEquals(vectors.preferredIdentifierSchemes, PREFERRED_IDENTIFIER_SCHEMES)
        assertTrue(vectors.identifierNormalizations.isNotEmpty())
        vectors.identifierNormalizations.forEach { vector ->
            val kind = EntryKind.fromWireValue(vector.kind)
            val normScheme = normalizeIdentifierScheme(vector.scheme)
            val normValue = normalizeIdentifierValue(kind, vector.scheme, vector.rawValue)
            assertEquals(vector.expectedScheme, normScheme)
            assertEquals(
                vector.expectedValue,
                normValue,
                "identifier_normalizations mismatch for (${vector.kind}, ${vector.scheme}, '${vector.rawValue}')",
            )
        }
    }

    @Test
    fun `album edition suffix stripping matches Rust golden vectors`() {
        val vectors = loadGoldenVectors()
        assertTrue(vectors.albumEditionStripping.isNotEmpty())
        vectors.albumEditionStripping.forEach { vector ->
            assertEquals(
                vector.expected,
                stripAlbumEditionSuffix(vector.input),
                "album_edition_stripping mismatch for '${vector.input}'",
            )
        }
    }

    @Test
    fun `deterministic catalog entry IDs and auto consumption log IDs match Rust golden vectors`() {
        val vectors = loadGoldenVectors()
        assertTrue(vectors.catalogEntryIds.isNotEmpty())
        vectors.catalogEntryIds.forEach { vector ->
            val kind = EntryKind.fromWireValue(vector.kind)
            val actualId =
                chooseDeterministicEntryId(
                    explicitId = null,
                    kind = kind,
                    title = vector.title,
                    creators = vector.creators,
                    identifiers = vector.identifiers,
                )
            assertEquals(
                vector.expectedId,
                actualId,
                "catalog_entry_ids mismatch for '${vector.title}'",
            )
        }
        assertTrue(vectors.autoConsumptionLogIds.isNotEmpty())
        vectors.autoConsumptionLogIds.forEach { vector ->
            assertEquals(
                vector.expectedLogId,
                deterministicAutoLogId(vector.entryId),
            )
        }
    }

    @Test
    fun `dynamic collection rules and ranked collection parity match Rust golden vectors`() {
        val vectors = loadGoldenVectors()
        assertTrue(vectors.dynamicCollectionRules.isNotEmpty())
        vectors.dynamicCollectionRules.forEach { vector ->
            val rule = parseDynamicCollectionRule(vector.ruleJson)
            val kind = EntryKind.fromWireValue(vector.entry.kind)
            val statuses =
                vector.entry.statuses.mapNotNull(ConsumptionStatus::fromWireValueOrNull).toSet()
            assertEquals(
                vector.expectedMatch,
                matchesDynamicCollectionRule(
                    rule = rule,
                    kind = kind,
                    creators = vector.entry.creators,
                    releaseYear = vector.entry.releaseYear,
                    statuses = statuses,
                ),
                "dynamic_collection_rules mismatch for ${vector.ruleJson}",
            )
        }
        assertTrue(vectors.rankedCollectionParity.isNotEmpty())
        vectors.rankedCollectionParity.forEach { vector ->
            val items =
                vector.entries.map { fixture ->
                    CatalogEntryItem(
                        id = fixture.id,
                        kind = EntryKind.MOVIE,
                        title = fixture.title,
                        creators = emptyList(),
                        url = null,
                        publishedAt = null,
                        releaseYear = null,
                        summary = null,
                        thumbnailUrl = null,
                        identifiers = emptyList(),
                    )
                }
            val ranked =
                rankEntriesByOrderings(
                    entries = items,
                    isPriorityScoped = vector.isPriorityScope,
                    orderings = vector.orderings,
                )
            assertEquals(
                vector.expectedIds,
                ranked.map { it.entry.id },
                "ranked_collection_parity IDs mismatch for ${vector.name}",
            )
            assertEquals(
                vector.expectedTiers,
                ranked.map { it.tier?.toString() },
                "ranked_collection_parity tiers mismatch for ${vector.name}",
            )
        }
    }
}

internal const val MIN_CANONICAL_URL_VECTORS = 6
internal const val MIN_ENTITY_ID_VECTORS = 3
internal const val MIN_HRW_ELECTION_VECTORS = 3
internal const val DEFAULT_VECTOR_POLL_MINUTES = 30

internal fun loadGoldenVectors(): CrossStackGoldenVectors {
    val vectorFile =
        listOf(
                File("../../testdata/cross_stack_vectors.json"),
                File("../testdata/cross_stack_vectors.json"),
                File("testdata/cross_stack_vectors.json"),
            )
            .firstOrNull { it.isFile }
            ?: error("Unable to locate testdata/cross_stack_vectors.json")
    return rocheJson.decodeFromString(
        CrossStackGoldenVectors.serializer(),
        vectorFile.readText(Charsets.UTF_8),
    )
}

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
internal data class CrossStackGoldenVectors(
    @SerialName("canonical_urls") val canonicalUrls: List<CanonicalUrlVector>,
    @SerialName("entity_ids") val entityIds: List<EntityIdVector>,
    @SerialName("hrw_elections") val hrwElections: List<HrwElectionVector>,
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

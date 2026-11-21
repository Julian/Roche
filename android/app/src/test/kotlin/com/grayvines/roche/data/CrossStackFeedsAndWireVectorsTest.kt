package com.grayvines.roche.data

import com.grayvines.roche.core.deterministicItemId
import com.grayvines.roche.core.parseDurationToSecondsLong
import com.grayvines.roche.data.feeds.LocalFeedPoller
import com.grayvines.roche.data.local.FeedRow
import com.grayvines.roche.data.remote.PeerNodeDto
import com.grayvines.roche.data.remote.PeerSyncRequestDto
import com.grayvines.roche.data.remote.PeerSyncResponseDto
import com.grayvines.roche.data.remote.rocheJson
import com.grayvines.roche.data.sync.shouldPeerPollFeed
import java.io.File
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Serializable
internal data class PollDecisionPeerVector(
    @SerialName("peer_id") val peerId: String,
    @SerialName("network_tier") val networkTier: String,
    val weight: Double,
    @SerialName("weight_special") val weightSpecial: String? = null,
    @SerialName("last_seen_epoch_secs") val lastSeenEpochSecs: Long,
)

@Serializable
internal data class PollDecisionFeedVector(
    val url: String,
    @SerialName("poll_interval_mins") val pollIntervalMins: Int,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("last_polled_epoch_secs") val lastPolledEpochSecs: Long? = null,
    @SerialName("last_polled_by") val lastPolledBy: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("last_error_by") val lastErrorBy: String? = null,
    @SerialName("consecutive_failures") val consecutiveFailures: Int,
)

@Serializable
internal data class PollDecisionCaseVector(
    val name: String,
    @SerialName("local_peer_id") val localPeerId: String,
    @SerialName("now_epoch_secs") val nowEpochSecs: Long,
    val peers: List<PollDecisionPeerVector>,
    val feed: PollDecisionFeedVector,
    @SerialName("expected_should_poll") val expectedShouldPoll: Boolean,
)

@Serializable
internal data class FeedXmlExpectedItemVector(
    val title: String,
    val url: String,
    @SerialName("audio_url") val audioUrl: String? = null,
    @SerialName("duration_secs") val durationSecs: Long? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    val author: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("description_contains") val descriptionContains: String? = null,
    @SerialName("expected_item_id") val expectedItemId: String,
)

@Serializable
internal data class FeedXmlFixtureVector(
    val name: String,
    @SerialName("feed_url") val feedUrl: String,
    @SerialName("feed_type") val feedType: String,
    val xml: String,
    @SerialName("expected_feed_title") val expectedFeedTitle: String,
    @SerialName("expected_items") val expectedItems: List<FeedXmlExpectedItemVector>,
)

@Serializable
internal data class WireDtoRoundtripSuiteVector(
    @SerialName("peer_sync_request") val peerSyncRequest: JsonElement,
    @SerialName("peer_sync_response") val peerSyncResponse: JsonElement,
)

@Serializable
internal data class FeedsAndWireGoldenVectors(
    @SerialName("poll_decision_matrix") val pollDecisionMatrix: List<PollDecisionCaseVector>,
    @SerialName("feed_xml_fixtures") val feedXmlFixtures: List<FeedXmlFixtureVector>,
    @SerialName("wire_dto_roundtrip") val wireDtoRoundtrip: WireDtoRoundtripSuiteVector,
)

class CrossStackFeedsAndWireVectorsTest {
    @Test
    fun `cooperative poll decision matrix matches Rust golden vectors`() {
        val cases = loadFeedsAndWireGoldenVectors().pollDecisionMatrix
        assertTrue(cases.size >= MIN_POLL_DECISION_CASES)
        for (case in cases) {
            val peers =
                case.peers.map { peer ->
                    val effectiveWeight =
                        when (peer.weightSpecial) {
                            "nan" -> Double.NaN
                            "inf" -> Double.POSITIVE_INFINITY
                            else -> peer.weight
                        }
                    PeerNodeDto(
                        peerId = peer.peerId,
                        deviceName = peer.peerId,
                        networkTier = peer.networkTier,
                        weight = effectiveWeight,
                        lastSeenAt = Instant.ofEpochSecond(peer.lastSeenEpochSecs).toString(),
                    )
                }
            val feedRow =
                FeedRow(
                    id = "feed-${case.name}",
                    title = case.name,
                    url = case.feed.url,
                    pollInterval = case.feed.pollIntervalMins,
                    lastPolled =
                        case.feed.lastPolledEpochSecs?.let { Instant.ofEpochSecond(it).toString() },
                    lastPolledBy = case.feed.lastPolledBy,
                    lastError = case.feed.lastError,
                    lastErrorBy = case.feed.lastErrorBy,
                    consecutiveFailures = case.feed.consecutiveFailures,
                    isActive = case.feed.isActive,
                )
            val actual =
                shouldPeerPollFeed(
                    localPeerId = case.localPeerId,
                    activePeers = peers,
                    feed = feedRow,
                    nowInstant = Instant.ofEpochSecond(case.nowEpochSecs),
                )
            assertEquals(
                case.expectedShouldPoll,
                actual,
                "poll_decision_matrix mismatch for '${case.name}'",
            )
        }
    }

    @Test
    fun `feed XML fixtures parse with item URL audio duration art and UUID parity`() {
        val fixtures = loadFeedsAndWireGoldenVectors().feedXmlFixtures
        assertTrue(fixtures.size >= MIN_FEED_XML_FIXTURES)
        for (fixture in fixtures) {
            val parsed =
                LocalFeedPoller.parseFeedXml(
                    xml = fixture.xml,
                    feedUrl = fixture.feedUrl,
                    feedType = fixture.feedType,
                )
            assertEquals(fixture.expectedFeedTitle, parsed.title, fixture.name)
            assertEquals(fixture.expectedItems.size, parsed.items.size, fixture.name)
            for ((item, expected) in parsed.items.zip(fixture.expectedItems)) {
                assertEquals(expected.title, item.title, fixture.name)
                assertEquals(expected.url, item.url, fixture.name)
                assertEquals(
                    expected.expectedItemId,
                    deterministicItemId(fixture.feedUrl, item.url),
                    fixture.name,
                )
                assertEquals(expected.audioUrl, item.audioUrl, fixture.name)
                assertEquals(
                    expected.durationSecs,
                    parseDurationToSecondsLong(item.duration),
                    fixture.name,
                )
                if (expected.thumbnailUrl != null) {
                    assertEquals(expected.thumbnailUrl, item.thumbnailUrl, fixture.name)
                }
                assertEquals(expected.author, item.author, fixture.name)
                assertEquals(expected.publishedAt, item.publishedAt, fixture.name)
                expected.descriptionContains?.let { needle ->
                    assertTrue(
                        item.summary.contains(needle),
                        "${fixture.name}: expected '$needle' in '${item.summary}'",
                    )
                }
            }
        }
    }

    @Test
    fun `wire sync DTOs round-trip canonical Rust payloads identically`() {
        val suite = loadFeedsAndWireGoldenVectors().wireDtoRoundtrip

        val req =
            rocheJson.decodeFromJsonElement(PeerSyncRequestDto.serializer(), suite.peerSyncRequest)
        assertEquals("android-pixel-9", req.peerId)
        assertEquals("wifi", req.networkTier)
        assertEquals(EXPECTED_WIRE_REQ_SEQ, req.sinceSeq)
        assertEquals(EXPECTED_WIRE_REQ_ENTRIES, req.entries.size)
        assertEquals(EXPECTED_ARTICLE_DURATION_SECS, req.entries[0].content?.duration)
        assertEquals(EXPECTED_FEED_POLL_INTERVAL, req.entries[1].feedSource?.pollInterval)
        assertEquals(EXPECTED_ALBUM_YEAR, req.entries[2].releaseYear)
        assertEquals("#3b82f6", req.entries[3].collectionSpec?.color)
        assertEquals(EXPECTED_MEMBER_POSITION, req.collectionEntries[0].position)
        assertEquals("album-ok-computer", req.collectionGenerators[0].currentEntryId)
        assertEquals("consumed", req.consumptionLog[0].status)
        assertEquals(2, req.comparisons[0].ordering.size)
        assertEquals("collection_entry", req.tombstones[0].entityType)

        val reqEncoded = rocheJson.encodeToString(PeerSyncRequestDto.serializer(), req)
        val reqRoundTrip = rocheJson.decodeFromString(PeerSyncRequestDto.serializer(), reqEncoded)
        assertEquals(req, reqRoundTrip)

        val resp =
            rocheJson.decodeFromJsonElement(
                PeerSyncResponseDto.serializer(),
                suite.peerSyncResponse,
            )
        assertEquals("server", resp.serverPeerId)
        assertEquals(EXPECTED_WIRE_RESP_SEQ, resp.syncSeq)
        assertTrue(resp.hasMore)
        assertFalse(resp.resetRequired)
        assertEquals(2, resp.activePeers.size)
        assertEquals("wired", resp.activePeers[0].networkTier)
        assertEquals("audio", resp.entries[0].content?.contentType)
        assertEquals(EXPECTED_PODCAST_DURATION_SECS, resp.entries[0].content?.duration)
        assertEquals("comparison", resp.tombstones[0].entityType)

        val respEncoded = rocheJson.encodeToString(PeerSyncResponseDto.serializer(), resp)
        val respRoundTrip =
            rocheJson.decodeFromString(PeerSyncResponseDto.serializer(), respEncoded)
        assertEquals(resp, respRoundTrip)
    }
}

private const val MIN_POLL_DECISION_CASES = 12
private const val MIN_FEED_XML_FIXTURES = 4
private const val EXPECTED_WIRE_REQ_SEQ = 1042L
private const val EXPECTED_WIRE_REQ_ENTRIES = 4
private const val EXPECTED_ARTICLE_DURATION_SECS = 360L
private const val EXPECTED_FEED_POLL_INTERVAL = 30
private const val EXPECTED_ALBUM_YEAR = 1997
private const val EXPECTED_MEMBER_POSITION = 2.5
private const val EXPECTED_WIRE_RESP_SEQ = 2048L
private const val EXPECTED_PODCAST_DURATION_SECS = 3615L

internal fun loadFeedsAndWireGoldenVectors(): FeedsAndWireGoldenVectors {
    val vectorFile =
        listOf(
                File("../../testdata/cross_stack_vectors.json"),
                File("../testdata/cross_stack_vectors.json"),
                File("testdata/cross_stack_vectors.json"),
            )
            .firstOrNull { it.isFile }
            ?: error("Unable to locate testdata/cross_stack_vectors.json")
    return rocheJson.decodeFromString(
        FeedsAndWireGoldenVectors.serializer(),
        vectorFile.readText(Charsets.UTF_8),
    )
}

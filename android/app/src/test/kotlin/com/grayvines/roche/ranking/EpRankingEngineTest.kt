package com.grayvines.roche.ranking

import com.grayvines.roche.data.remote.rocheJson
import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Serializable
internal data class EpExpectedScore(
    val mean: Double = 0.0,
    val variance: Double = EpRankingEngine.COLD_START_VARIANCE,
)

@Serializable
internal data class EpVectorCase(
    val name: String,
    val items: List<String>,
    val orderings: List<List<List<String>>>,
    @SerialName("expected_scores") val expectedScores: Map<String, EpExpectedScore>,
    @SerialName("expected_quantiles") val expectedQuantiles: Map<String, Int>,
    @SerialName("expected_first_pair_confusability") val expectedFirstPairConfusability: Double,
)

@Serializable
internal data class EpGoldenVectorsFile(@SerialName("ep_cases") val epCases: List<EpVectorCase>)

class EpRankingEngineTest {
    @Test
    fun `EP inference quantiles and confusability match Great and Rust golden vectors`() {
        val vectorFile =
            listOf(
                    File("../../testdata/ep_ranking_vectors.json"),
                    File("../testdata/ep_ranking_vectors.json"),
                    File("testdata/ep_ranking_vectors.json"),
                )
                .firstOrNull { it.isFile }
                ?: error("Unable to locate testdata/ep_ranking_vectors.json")
        val parsed =
            rocheJson.decodeFromString(
                EpGoldenVectorsFile.serializer(),
                vectorFile.readText(Charsets.UTF_8),
            )
        assertTrue(parsed.epCases.size >= MIN_EXPECTED_CASES)

        for (case in parsed.epCases) {
            val scores = EpRankingEngine.inferScores(case.items, case.orderings)
            for ((id, expected) in case.expectedScores) {
                val actual = scores.getValue(id)
                assertTrue(
                    abs(actual.mean - expected.mean) <= TOLERANCE,
                    "case '${case.name}' item '$id' mean mismatch: actual=${actual.mean} expected=${expected.mean}",
                )
                assertTrue(
                    abs(actual.variance - expected.variance) <= TOLERANCE,
                    "case '${case.name}' item '$id' variance mismatch: actual=${actual.variance} expected=${expected.variance}",
                )
            }

            val quantiles = EpRankingEngine.rescaleToQuantiles(scores)
            assertEquals(case.expectedQuantiles, quantiles, "case '${case.name}' quantiles")

            val first = scores.getValue(case.items[0])
            val second = scores.getValue(case.items[1])
            val conf = EpRankingEngine.confusability(first, second)
            assertTrue(
                abs(conf - case.expectedFirstPairConfusability) <= TOLERANCE,
                "case '${case.name}' confusability mismatch: actual=$conf expected=${case.expectedFirstPairConfusability}",
            )
        }
    }

    @Test
    fun `selectCluster terminates focused ranking when seed has no confusable neighbors`() {
        val items = listOf("a", "b", "c")
        val scores =
            mapOf(
                "a" to EpRankingEngine.Score(mean = 5.0, variance = 0.1),
                "b" to EpRankingEngine.Score(mean = 0.0, variance = 0.1),
                "c" to EpRankingEngine.Score(mean = -5.0, variance = 0.1),
            )
        val cluster =
            EpRankingEngine.selectCluster(
                scores = scores,
                itemIds = items,
                maxK = 5,
                forceRandomSeed = false,
                seedPool = listOf("a"),
                random = Random(42),
            )
        assertEquals(listOf("a"), cluster)
    }

    @Test
    fun `validateOrdering rejects duplicates empty groups and singletons`() {
        assertTrue(EpRankingEngine.validateOrdering(listOf(listOf("a"), listOf("b"))).isSuccess)
        assertTrue(EpRankingEngine.validateOrdering(listOf(listOf("a", "b"))).isSuccess)
        assertTrue(EpRankingEngine.validateOrdering(listOf(listOf("a"))).isFailure)
        assertTrue(
            EpRankingEngine.validateOrdering(listOf(emptyList(), listOf("a", "b"))).isFailure
        )
        assertTrue(EpRankingEngine.validateOrdering(listOf(listOf("a"), listOf("a"))).isFailure)
    }
}

private const val TOLERANCE = 1e-6
private const val MIN_EXPECTED_CASES = 6

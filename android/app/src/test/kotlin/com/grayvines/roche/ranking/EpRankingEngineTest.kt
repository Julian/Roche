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
                    actual.mean.isFinite() && actual.variance.isFinite() && actual.variance >= 0.0,
                    "case '${case.name}' item '$id' produced non-finite or negative variance: $actual",
                )
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

    @Test
    fun `confusability treats zero-variance means within machine epsilon as tied`() {
        val base = EpRankingEngine.Score(mean = 1.0, variance = 0.0)
        val withinEps = EpRankingEngine.Score(mean = 1.0 + Math.ulp(1.0) * 0.5, variance = 0.0)
        val outsideEps = EpRankingEngine.Score(mean = 1.0 + 1e-12, variance = 0.0)
        assertEquals(0.5, EpRankingEngine.confusability(base, withinEps), 0.0)
        assertEquals(0.0, EpRankingEngine.confusability(base, outsideEps), 0.0)
    }

    @Test
    fun `selectClusterPrioritized preserves focus seed and descending confusability order`() {
        val items = listOf("focus", "hi", "near1", "near2", "far")
        val scores =
            mapOf(
                "focus" to EpRankingEngine.Score(mean = -0.4, variance = 0.8),
                "hi" to EpRankingEngine.Score(mean = 0.45, variance = 0.3),
                "near1" to EpRankingEngine.Score(mean = -0.35, variance = 0.4),
                "near2" to EpRankingEngine.Score(mean = 0.1, variance = 0.4),
                "far" to EpRankingEngine.Score(mean = 0.3, variance = 0.2),
            )
        val sel =
            EpRankingEngine.selectClusterPrioritized(
                scores = scores,
                itemIds = items,
                maxK = 5,
                forceRandomSeed = false,
                seedPool = listOf("focus"),
                random = Random(7),
            )
        assertEquals("hi", sel.orderedIds.first())
        assertEquals("focus", sel.orderedIds.last())
        assertEquals("focus", sel.priorityIds[0])
        assertEquals("near1", sel.priorityIds[1])
        assertEquals("near2", sel.priorityIds[2])
    }

    @Test
    fun `selectClusterPrioritized forceRandomSeed explores beyond k2`() {
        val items = (0 until 6).map { "i$it" }
        val scores =
            items.withIndex().associate { (idx, id) ->
                id to EpRankingEngine.Score(mean = 0.0, variance = 1.0 - idx * 0.05)
            }
        val random = Random(42)
        val seenSeeds = HashSet<String>()
        repeat(80) {
            val sel =
                EpRankingEngine.selectClusterPrioritized(
                    scores = scores,
                    itemIds = items,
                    maxK = 2,
                    forceRandomSeed = true,
                    seedPool = null,
                    random = random,
                )
            assertEquals(2, sel.orderedIds.size)
            seenSeeds.add(sel.priorityIds.first())
        }
        assertTrue(
            seenSeeds.size >= 3,
            "expected random seed pool >= MAX_K_DEFAULT even at maxK=2, saw $seenSeeds",
        )
    }
}

private const val TOLERANCE = 1e-6
private const val MIN_EXPECTED_CASES = 9

@file:Suppress("MagicNumber", "TooManyFunctions")

package com.grayvines.roche.ranking

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

internal object EpGaussianMath {
    private const val THRESHOLD: Double = 1e-6
    internal val SQRT2: Double = sqrt(2.0)
    private const val SQRT_2_OVER_PI: Double = 0.7978845608028654
    private const val SQRT_2PI: Double = 2.5066282746310002
    private val LN_2: Double = ln(2.0)

    private val LOG_PHI_CS =
        doubleArrayOf(
            0.00048204,
            -0.00142906,
            0.0013200243174,
            0.0009461589032,
            -0.0045563339802,
            0.00556964649138,
            0.00125993961762116,
            -0.01621575378835404,
            0.02629651521057465,
            -0.001829764677455021,
            2.0 * (1.0 - PI / 3.0),
            (4.0 - PI) / 3.0,
            1.0,
            1.0,
        )

    private val LOG_PHI_RS =
        doubleArrayOf(
            1.275366644729966,
            5.019049726784267,
            6.16020985310963,
            7.409740605964742,
            2.978865626393993,
        )

    private val LOG_PHI_QS =
        doubleArrayOf(
            2.260528520767327,
            9.396034016235054,
            12.048951927855129,
            17.081440747466004,
            9.608965327192788,
            3.369075206982753,
        )

    private val LOGIT_LAMBDAS =
        doubleArrayOf(
            SQRT2 * 0.44,
            SQRT2 * 0.41,
            SQRT2 * 0.40,
            SQRT2 * 0.39,
            SQRT2 * 0.36,
        )

    private val LOGIT_CS =
        doubleArrayOf(
            1.146480988574439e2,
            -1.508871030070582e3,
            2.676085036831241e3,
            -1.356294962039222e3,
            7.54328564211185e1,
        )

    private val ERFC_A =
        doubleArrayOf(
            3.1611237438705656,
            1.1386415415105016e2,
            3.77485237685302e2,
            3.2093775891384695e3,
            1.8577770618460315e-1,
        )

    private val ERFC_B =
        doubleArrayOf(
            2.360129095234412e1,
            2.4402463793444417e2,
            1.2826165260773723e3,
            2.844236833439171e3,
        )

    private val ERFC_C =
        doubleArrayOf(
            5.641884969886701e-1,
            8.883149794388376,
            6.611919063714163e1,
            2.986351381974001e2,
            8.81952221241769e2,
            1.7120476126340706e3,
            2.0510783778260715e3,
            1.2303393547979972e3,
            2.1531153547440385e-8,
        )

    private val ERFC_D =
        doubleArrayOf(
            1.5744926110709835e1,
            1.176939508913125e2,
            5.371811018620099e2,
            1.6213895745666902e3,
            3.2907992357334596e3,
            4.362619090143247e3,
            3.4393676741437216e3,
            1.2303393548037494e3,
        )

    private val ERFC_P =
        doubleArrayOf(
            3.0532663496123234e-1,
            3.6034489994980444e-1,
            1.2578172611122925e-1,
            1.608378514874228e-2,
            6.587491615298378e-4,
            1.6315387137302098e-2,
        )

    private val ERFC_Q =
        doubleArrayOf(
            2.568520192289822,
            1.8729528499234605,
            5.279051029514284e-1,
            6.051834131244132e-2,
            2.335204976268692e-3,
        )

    private class EpBuffers(nItems: Int, m: Int, alpha: Double) {
        val nItems: Int = nItems
        val mean = DoubleArray(nItems)
        var cov =
            DoubleArray(nItems * nItems).also { arr ->
                for (i in 0 until nItems) arr[i * nItems + i] = 1.0 / alpha
            }
        val tau = DoubleArray(m)
        val nu = DoubleArray(m)
        val prec = DoubleArray(nItems * nItems)
        val xs = DoubleArray(nItems)
        val diffCol = DoubleArray(nItems)
    }

    private val MACHINE_EPSILON: Double = Math.ulp(1.0)
    private const val ASYMPTOTIC_TAIL_SHORT_CIRCUIT: Double = 1e-15

    fun epPairwiseComponent(
        nItems: Int,
        pairs: List<Pair<Int, Int>>,
        alpha: Double,
        maxIter: Int,
    ): Pair<DoubleArray, DoubleArray>? {
        val state = EpBuffers(nItems, pairs.size, alpha)
        var result: Pair<DoubleArray, DoubleArray>? = null
        var aborted = false
        var iter = 0
        while (iter < maxIter && result == null && !aborted) {
            val maxDelta = runSingleEpIteration(state, pairs, nItems, alpha)
            if (maxDelta == null) {
                aborted = true
            } else if (maxDelta <= THRESHOLD) {
                val posterior = extractValidPosterior(state, nItems)
                result = posterior
                aborted = posterior == null
            }
            iter += 1
        }
        return result
    }

    private fun runSingleEpIteration(
        state: EpBuffers,
        pairs: List<Pair<Int, Int>>,
        nItems: Int,
        alpha: Double,
    ): Double? {
        val tauOld = state.tau.copyOf()
        val nuOld = state.nu.copyOf()
        if (!runEpSweep(state, pairs, tauOld, nuOld)) return null
        val nextCov = invertSystemMatrix(state.prec, nItems, alpha) ?: return null
        state.cov = nextCov
        matVecMul(state.cov, state.xs, state.mean, nItems)
        return maxFiniteSiteDelta(state.tau, tauOld, state.nu, nuOld)
    }

    private fun extractValidPosterior(
        state: EpBuffers,
        nItems: Int,
    ): Pair<DoubleArray, DoubleArray>? {
        val variances = DoubleArray(nItems) { k -> state.cov[k * nItems + k] }
        val valid = state.mean.all { it.isFinite() } && variances.all { it.isFinite() && it >= 0.0 }
        return if (valid) state.mean to variances else null
    }

    private fun runEpSweep(
        state: EpBuffers,
        pairs: List<Pair<Int, Int>>,
        tauOld: DoubleArray,
        nuOld: DoubleArray,
    ): Boolean = pairs.indices.all { i -> updateSingleFactor(state, i, pairs[i], tauOld, nuOld) }

    private fun updateSingleFactor(
        state: EpBuffers,
        index: Int,
        pair: Pair<Int, Int>,
        tauOld: DoubleArray,
        nuOld: DoubleArray,
    ): Boolean {
        val n = state.nItems
        val (a, b) = pair
        val fVar = state.cov[a * n + a] + state.cov[b * n + b] - 2.0 * state.cov[a * n + b]
        val tauCav = if (fVar > 0.0 && fVar.isFinite()) 1.0 / fVar - state.tau[index] else -1.0
        if (tauCav <= 0.0 || !tauCav.isFinite()) return false
        val fMean = state.mean[a] - state.mean[b]
        val nuCav = fMean / fVar - state.nu[index]
        val covCav = 1.0 / tauCav
        val (_, dlogpart, d2logpart) = matchMomentsLogit(covCav * nuCav, covCav)
        val denom = 1.0 + d2logpart / tauCav
        val validMoments =
            dlogpart.isFinite() &&
                d2logpart.isFinite() &&
                denom.isFinite() &&
                abs(denom) > MACHINE_EPSILON
        val nextTau = if (validMoments) -d2logpart / denom else Double.NaN
        val nextNu =
            if (validMoments) (dlogpart - nuCav / tauCav * d2logpart) / denom else Double.NaN
        val deltaTau = nextTau - tauOld[index]
        val deltaNu = nextNu - nuOld[index]
        return if (allFinite(nextTau, nextNu, deltaTau, deltaNu)) {
            state.tau[index] = nextTau
            state.nu[index] = nextNu
            applyFactorDelta(
                state = state,
                a = a,
                b = b,
                fVar = fVar,
                deltaTau = deltaTau,
                deltaNu = deltaNu,
            )
        } else {
            false
        }
    }

    private fun allFinite(a: Double, b: Double, c: Double, d: Double): Boolean =
        a.isFinite() && b.isFinite() && c.isFinite() && d.isFinite()

    @Suppress("LongParameterList")
    private fun applyFactorDelta(
        state: EpBuffers,
        a: Int,
        b: Int,
        fVar: Double,
        deltaTau: Double,
        deltaNu: Double,
    ): Boolean {
        val n = state.nItems
        state.prec[a * n + a] += deltaTau
        state.prec[a * n + b] -= deltaTau
        state.prec[b * n + a] -= deltaTau
        state.prec[b * n + b] += deltaTau
        state.xs[a] += deltaNu
        state.xs[b] -= deltaNu
        if (abs(deltaTau) > 0.0) {
            val scaleDenom = 1.0 / deltaTau + fVar
            if (!scaleDenom.isFinite() || abs(scaleDenom) <= MACHINE_EPSILON) return false
            rankOneUpdate(state.cov, state.diffCol, a, b, n, -1.0 / scaleDenom)
        }
        matVecMul(state.cov, state.xs, state.mean, n)
        return true
    }

    private fun invertSystemMatrix(prec: DoubleArray, nItems: Int, alpha: Double): DoubleArray? {
        val sysMat = prec.copyOf()
        for (d in 0 until nItems) {
            sysMat[d * nItems + d] += alpha
        }
        val low = choleskyDecompose(sysMat, nItems) ?: return null
        val invLow = invertLowerTriangular(low, nItems)
        return multiplyUpperByLower(invLow, nItems)
    }

    @Suppress("LongParameterList")
    private fun rankOneUpdate(
        cov: DoubleArray,
        diffCol: DoubleArray,
        a: Int,
        b: Int,
        nItems: Int,
        scale: Double,
    ) {
        for (k in 0 until nItems) {
            diffCol[k] = cov[a * nItems + k] - cov[b * nItems + k]
        }
        for (r in 0 until nItems) {
            val sr = scale * diffCol[r]
            val rowOffset = r * nItems
            for (c in 0 until nItems) {
                cov[rowOffset + c] += sr * diffCol[c]
            }
        }
    }

    internal fun maxFiniteSiteDelta(
        tau: DoubleArray,
        tauOld: DoubleArray,
        nu: DoubleArray,
        nuOld: DoubleArray,
    ): Double? {
        var maxDiff = 0.0
        var valid = true
        for (i in tau.indices) {
            val dt = abs(tau[i] - tauOld[i])
            val dn = abs(nu[i] - nuOld[i])
            val ok = allFinite(tau[i], tauOld[i], nu[i], nuOld[i]) && dt.isFinite() && dn.isFinite()
            if (!ok) {
                valid = false
                break
            }
            if (dt > maxDiff) maxDiff = dt
            if (dn > maxDiff) maxDiff = dn
        }
        return if (valid) maxDiff else null
    }

    private fun matVecMul(mat: DoubleArray, vecX: DoubleArray, out: DoubleArray, n: Int) {
        for (r in 0 until n) {
            val rowStart = r * n
            var sum = 0.0
            for (c in 0 until n) {
                sum += mat[rowStart + c] * vecX[c]
            }
            out[r] = sum
        }
    }

    private fun choleskyDecompose(mat: DoubleArray, n: Int): DoubleArray? {
        val low = DoubleArray(n * n)
        for (i in 0 until n) {
            if (!computeCholeskyRow(mat, low, n, i)) return null
        }
        return low
    }

    private fun computeCholeskyRow(mat: DoubleArray, low: DoubleArray, n: Int, i: Int): Boolean {
        for (j in 0..i) {
            var sum = mat[i * n + j]
            for (k in 0 until j) {
                sum -= low[i * n + k] * low[j * n + k]
            }
            if (i == j) {
                if (sum <= 0.0 || !sum.isFinite()) return false
                low[i * n + i] = sqrt(sum)
            } else {
                low[i * n + j] = sum / low[j * n + j]
            }
        }
        return true
    }

    private fun invertLowerTriangular(low: DoubleArray, n: Int): DoubleArray {
        val invLow = DoubleArray(n * n)
        for (col in 0 until n) {
            invLow[col * n + col] = 1.0 / low[col * n + col]
            for (row in col + 1 until n) {
                var sum = 0.0
                for (k in col until row) {
                    sum += low[row * n + k] * invLow[k * n + col]
                }
                invLow[row * n + col] = -sum / low[row * n + row]
            }
        }
        return invLow
    }

    private fun multiplyUpperByLower(invLow: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(n * n)
        for (i in 0 until n) {
            for (j in i until n) {
                var sum = 0.0
                for (k in j until n) {
                    sum += invLow[k * n + i] * invLow[k * n + j]
                }
                out[i * n + j] = sum
                out[j * n + i] = sum
            }
        }
        return out
    }

    private fun logPhi(z: Double): Pair<Double, Double> =
        when {
            z * z < 0.0492 -> {
                val coef = -z / SQRT_2PI
                var acc = 0.0
                for (c in LOG_PHI_CS) {
                    acc = coef * (c + acc)
                }
                val res = -2.0 * acc - LN_2
                val dres = exp(-z * z / 2.0 - res) / SQRT_2PI
                res to dres
            }
            z < -11.3137 -> {
                var num = 0.5641895835477551
                for (r in LOG_PHI_RS) {
                    num = -z * num / SQRT2 + r
                }
                var den = 1.0
                for (q in LOG_PHI_QS) {
                    den = -z * den / SQRT2 + q
                }
                val res = ln(num / (2.0 * den)) - z * z / 2.0
                val dres = abs(den / num) * SQRT_2_OVER_PI
                res to dres
            }
            else -> {
                val cdf = 0.5 * codyErfc(-z / SQRT2)
                val res = ln(cdf)
                val dres = exp(-z * z / 2.0 - res) / SQRT_2PI
                res to dres
            }
        }

    private fun matchMomentsProbit(
        meanCav: Double,
        covCav: Double,
    ): Triple<Double, Double, Double> {
        val s = sqrt(1.0 + covCav)
        val z = meanCav / s
        val (logpart, v) = logPhi(z)
        val dlogpart = v / s
        val d2logpart = -v * (z + v) / (1.0 + covCav)
        return Triple(logpart, dlogpart, d2logpart)
    }

    private fun evaluateProbitMixture(
        meanCav: Double,
        covCav: Double,
    ): Triple<Double, Double, Double>? {
        val arr1 = DoubleArray(5)
        val arr2 = DoubleArray(5)
        val arr3 = DoubleArray(5)
        var maxA1 = Double.NEGATIVE_INFINITY
        for (i in 0 until 5) {
            val lam = LOGIT_LAMBDAS[i]
            val (p1, p2, p3) = matchMomentsProbit(lam * meanCav, lam * lam * covCav)
            arr1[i] = p1
            arr2[i] = p2
            arr3[i] = p3
            if (p1 > maxA1) maxA1 = p1
        }
        var denom = 0.0
        var num1 = 0.0
        var num2 = 0.0
        for (i in 0 until 5) {
            val w = exp(arr1[i] - maxA1) * LOGIT_CS[i]
            val lam = LOGIT_LAMBDAS[i]
            denom += w
            num1 += w * arr2[i] * lam
            num2 += w * (arr2[i] * arr2[i] + arr3[i]) * lam * lam
        }
        if (!denom.isFinite() || denom <= 0.0) return null
        val logpart1 = maxA1 + ln(denom)
        val dlogpart1 = num1 / denom
        val d2logpart1 = num2 / denom - dlogpart1 * dlogpart1
        return Triple(logpart1, dlogpart1, d2logpart1)
    }

    fun matchMomentsLogit(meanCav: Double, covCav: Double): Triple<Double, Double, Double> {
        val exponent = -10.0 * (abs(meanCav) - 196.0 / 200.0 * covCav - 4.0)
        var lambd = 0.0
        var logpart2 = 0.0
        var dlogpart2 = 0.0
        if (exponent < 500.0) {
            lambd = 1.0 / (1.0 + exp(exponent))
            val lp2Raw = min(covCav / 2.0 - abs(meanCav), -0.1)
            if (meanCav > 0.0) {
                logpart2 = ln(1.0 - exp(lp2Raw))
            } else {
                logpart2 = lp2Raw
                dlogpart2 = 1.0
            }
        }
        if (1.0 - lambd < ASYMPTOTIC_TAIL_SHORT_CIRCUIT) {
            return Triple(logpart2, dlogpart2, 0.0)
        }
        val mixture = evaluateProbitMixture(meanCav, covCav)
        return when {
            mixture != null -> blendLogitMoments(lambd, mixture, logpart2, dlogpart2)
            lambd > 0.0 -> Triple(logpart2, dlogpart2, 0.0)
            else -> Triple(Double.NaN, Double.NaN, Double.NaN)
        }
    }

    private fun blendLogitMoments(
        lambd: Double,
        mixture: Triple<Double, Double, Double>,
        logpart2: Double,
        dlogpart2: Double,
    ): Triple<Double, Double, Double> {
        val (logpart1, dlogpart1, d2logpart1) = mixture
        return Triple(
            (1.0 - lambd) * logpart1 + lambd * logpart2,
            (1.0 - lambd) * dlogpart1 + lambd * dlogpart2,
            (1.0 - lambd) * d2logpart1,
        )
    }

    fun codyErfc(x: Double): Double {
        if (x.isNaN()) return Double.NaN
        val ax = abs(x)
        if (ax <= 0.46875) {
            return 1.0 - codyErfNearZero(x, ax)
        }
        val res =
            when {
                ax <= 4.0 -> codyErfcMid(ax)
                ax >= 26.55 -> 0.0
                else -> codyErfcTail(ax)
            }
        return if (x < 0.0) 2.0 - res else res
    }

    private fun codyErfNearZero(x: Double, ax: Double): Double {
        val ysq = ax * ax
        var xnum = ERFC_A[4] * ysq
        var xden = ysq
        for (i in 0 until 3) {
            xnum = (xnum + ERFC_A[i]) * ysq
            xden = (xden + ERFC_B[i]) * ysq
        }
        return x * (xnum + ERFC_A[3]) / (xden + ERFC_B[3])
    }

    private fun codyErfcMid(ax: Double): Double {
        var xnum = ERFC_C[8] * ax
        var xden = ax
        for (i in 0 until 7) {
            xnum = (xnum + ERFC_C[i]) * ax
            xden = (xden + ERFC_D[i]) * ax
        }
        val ratio = (xnum + ERFC_C[7]) / (xden + ERFC_D[7])
        val ysq = floor(ax * 16.0) / 16.0
        val del = (ax - ysq) * (ax + ysq)
        return exp(-ysq * ysq) * exp(-del) * ratio
    }

    private fun codyErfcTail(ax: Double): Double {
        val ysq = 1.0 / (ax * ax)
        var xnum = ERFC_P[5] * ysq
        var xden = ysq
        for (i in 0 until 4) {
            xnum = (xnum + ERFC_P[i]) * ysq
            xden = (xden + ERFC_Q[i]) * ysq
        }
        val ratio = ysq * (xnum + ERFC_P[4]) / (xden + ERFC_Q[4])
        val scaled = (0.5641895835477563 - ratio) / ax
        val ysqHi = floor(ax * 16.0) / 16.0
        val del = (ax - ysqHi) * (ax + ysqHi)
        return exp(-ysqHi * ysqHi) * exp(-del) * scaled
    }
}

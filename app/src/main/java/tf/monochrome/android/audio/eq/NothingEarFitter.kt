package tf.monochrome.android.audio.eq

import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Approximates an EQ with the Nothing Ear's 8 peaking bands, for export as a
 * Nothing X share code ([NothingEarShareCode]).
 *
 * The earbuds take exactly 8 bells and nothing else: no shelves, no preamp.
 * The source EQ is rendered to a curve with the same response model the graph
 * draws ([AutoEqEngine.ResponseGrid]), and the 8 bands are fitted to that
 * curve: greedy placement where the residual is largest, then
 * Levenberg-Marquardt refinement of all 24 parameters together. Error is
 * measured in dB on a log-spaced grid, so every octave counts the same.
 *
 * The earbuds' filters are modelled as RBJ cookbook bells at 48 kHz. That is
 * the usual firmware design, but an assumption: the matched (decramped) bells
 * Tryptify plays differ from it only near Nyquist, which is also the one place
 * the two could disagree.
 *
 * Pure JVM, so the fit is pinned by unit tests.
 */
object NothingEarFitter {

    const val NUM_BANDS = NothingEarShareCode.NUM_BANDS

    data class Limits(
        val minGainDb: Double,
        val maxGainDb: Double,
        val minQ: Double,
        val maxQ: Double,
        /** One {min, max} Hz per band; bands past the end use 20 Hz..20 kHz. */
        val bandFreqRange: List<ClosedFloatingPointRange<Double>>,
        val sampleRate: Double = 48000.0,
    ) {
        fun freqRange(band: Int): ClosedFloatingPointRange<Double> =
            bandFreqRange.getOrNull(band) ?: (20.0..20000.0)
    }

    /**
     * What Nothing X's own sliders allow. Q and band 1's range were read off
     * the app at both ends; ±6 dB is from the slider's geometry. The other
     * bands' frequency ranges are unmeasured, so they are left open.
     */
    val APP_LIMITS = Limits(
        minGainDb = -6.0, maxGainDb = 6.0,
        minQ = 0.1, maxQ = 10.0,
        bandFreqRange = listOf(20.0..99.0),
    )

    /**
     * The "volume hack": gain past ±6 dB. Nothing X does not clamp a share
     * code on import: a +9 dB band is stored and drawn in its graph as +9
     * (its slider shows 0, as it cannot draw past its end). ±12 dB matches
     * the most AutoEQ itself ever asks of a band.
     */
    val EXTENDED_LIMITS = APP_LIMITS.copy(minGainDb = -12.0, maxGainDb = 12.0)

    class Fit(
        /** The 8 bands, in the earbuds' band order. */
        val bands: List<EqBand>,
        /** RMS error against the source curve, dB. */
        val rmsErrorDb: Double,
        /** Worst single-point error against the source curve, dB. */
        val maxErrorDb: Double,
    )

    /** Fits 8 bands to the response of [source] (disabled bands contribute nothing). */
    fun fit(
        source: List<EqBand>,
        limits: Limits = EXTENDED_LIMITS,
        minHz: Double = 20.0,
        maxHz: Double = 20000.0,
        pointsPerOctave: Int = 24,
    ): Fit {
        val n = max(2, ceil(log2(maxHz / minHz) * pointsPerOctave).toInt() + 1)
        val freqs = DoubleArray(n) { i -> minHz * (maxHz / minHz).pow(i.toDouble() / (n - 1)) }
        val target = AutoEqEngine.ResponseGrid(FloatArray(n) { freqs[it].toFloat() }).sum(source)
        return fit(freqs, DoubleArray(n) { target[it].toDouble() }, limits)
    }

    /** Fits 8 bands to [target] dB at [freqs] Hz (ascending). */
    fun fit(freqs: DoubleArray, target: DoubleArray, limits: Limits, iterations: Int = 200): Fit =
        Fitter(freqs, target, limits).run(iterations)

    private class Bell(var freq: Double, var gain: Double, var q: Double)

    private class Fitter(
        private val freqs: DoubleArray,
        private val target: DoubleArray,
        private val lim: Limits,
    ) {
        private val m = freqs.size
        private val cosPhi = DoubleArray(m) { cos(2.0 * Math.PI * freqs[it] / lim.sampleRate) }
        private val cos2Phi = DoubleArray(m) { cos(4.0 * Math.PI * freqs[it] / lim.sampleRate) }

        fun run(iterations: Int): Fit {
            val bands = ArrayList<Bell>(NUM_BANDS)
            for (i in 0 until NUM_BANDS) {
                bands.add(initialBell(i, bands))
                refine(bands, 30)
            }
            refine(bands, iterations)

            val model = sum(bands)
            var sq = 0.0
            var worst = 0.0
            for (i in 0 until m) {
                val e = target[i] - model[i]
                sq += e * e
                worst = max(worst, abs(e))
            }
            return Fit(
                bands = bands.mapIndexed { i, b ->
                    EqBand(i, FilterType.PEAKING, b.freq.toFloat(), b.gain.toFloat(), b.q.toFloat(), true)
                },
                rmsErrorDb = sqrt(sq / m),
                maxErrorDb = worst,
            )
        }

        /** Adds one RBJ bell's dB response onto [out]. */
        private fun accumulate(b: Bell, out: DoubleArray) {
            if (b.gain == 0.0) return
            val w0 = 2.0 * Math.PI * b.freq / lim.sampleRate
            val alpha = sin(w0) / (2.0 * b.q)
            val a = 10.0.pow(b.gain / 40.0)
            val cw = cos(w0)
            val inv = 1.0 / (1.0 + alpha / a)
            val b0 = (1.0 + alpha * a) * inv
            val b1 = -2.0 * cw * inv
            val b2 = (1.0 - alpha * a) * inv
            val a1 = b1
            val a2 = (1.0 - alpha / a) * inv
            for (i in 0 until m) {
                val cp = cosPhi[i]
                val c2p = cos2Phi[i]
                val num = b0 * b0 + b1 * b1 + b2 * b2 + 2.0 * (b0 * b1 + b1 * b2) * cp + 2.0 * b0 * b2 * c2p
                val den = 1.0 + a1 * a1 + a2 * a2 + 2.0 * (a1 + a1 * a2) * cp + 2.0 * a2 * c2p
                out[i] += 10.0 * log10(num / den)
            }
        }

        private fun sum(bands: List<Bell>): DoubleArray =
            DoubleArray(m).also { out -> bands.forEach { accumulate(it, out) } }

        private fun cost(bands: List<Bell>): Double {
            val model = sum(bands)
            var s = 0.0
            for (i in 0 until m) {
                val e = target[i] - model[i]
                s += e * e
            }
            return s
        }

        private fun clamp(b: Bell, slot: Int) {
            val r = lim.freqRange(slot)
            b.freq = b.freq.coerceIn(r.start, r.endInclusive)
            b.gain = b.gain.coerceIn(lim.minGainDb, lim.maxGainDb)
            b.q = b.q.coerceIn(lim.minQ, lim.maxQ)
        }

        /**
         * A bell at the biggest residual inside its own frequency range, with
         * Q from the width of that bump at half its height.
         */
        private fun initialBell(slot: Int, placed: List<Bell>): Bell {
            val model = sum(placed)
            val r = DoubleArray(m) { target[it] - model[it] }
            val range = lim.freqRange(slot)
            var best = -1
            for (i in 0 until m) {
                if (freqs[i] !in range) continue
                if (best < 0 || abs(r[i]) > abs(r[best])) best = i
            }
            if (best < 0) {
                // Range falls outside the grid: park it flat mid-range.
                return Bell(sqrt(range.start * range.endInclusive), 0.0, 1.0).also { clamp(it, slot) }
            }
            val peak = r[best]
            fun stillIn(i: Int) = r[i] * peak > 0.0 && abs(r[i]) >= abs(peak) * 0.5
            var left = best
            var right = best
            while (left > 0 && stillIn(left - 1)) left--
            while (right + 1 < m && stillIn(right + 1)) right++
            val width = freqs[right] - freqs[left]
            val bell = Bell(freqs[best], peak, if (width > 0.0) freqs[best] / width else lim.maxQ)
            clamp(bell, slot)
            return bell
        }

        /**
         * Levenberg-Marquardt over log2(freq), gain and log2(Q) of every band,
         * with bounds enforced by clamping each trial step. Logs make a step
         * in freq or Q mean the same anywhere in the range, which the damping
         * assumes.
         */
        private fun refine(bands: MutableList<Bell>, iterations: Int) {
            val p = bands.size * 3
            if (p == 0 || m == 0) return
            val jac = Array(m) { DoubleArray(p) }
            val jtj = Array(p) { DoubleArray(p) }
            val jtr = DoubleArray(p)
            val one = DoubleArray(m)
            val moved = DoubleArray(m)
            var current = cost(bands)
            var lambda = 1e-2
            val h = 1e-5

            repeat(iterations) {
                val model = sum(bands)
                // Forward-difference Jacobian. A column moves one parameter of
                // one band, so only that band is recomputed. The moved band is
                // not clamped: at a bound the derivative is still the
                // derivative, and clamping would zero it.
                for ((bi, b) in bands.withIndex()) {
                    one.fill(0.0)
                    accumulate(b, one)
                    for (k in 0 until 3) {
                        val t = Bell(b.freq, b.gain, b.q)
                        when (k) {
                            0 -> t.freq = 2.0.pow(log2(t.freq) + h)
                            1 -> t.gain += h
                            else -> t.q = 2.0.pow(log2(t.q) + h)
                        }
                        moved.fill(0.0)
                        accumulate(t, moved)
                        for (i in 0 until m) jac[i][bi * 3 + k] = (moved[i] - one[i]) / h
                    }
                }
                for (row in jtj) row.fill(0.0)
                jtr.fill(0.0)
                for (i in 0 until m) {
                    val row = jac[i]
                    val r = target[i] - model[i]
                    for (j in 0 until p) {
                        jtr[j] += row[j] * r
                        for (k in j until p) jtj[j][k] += row[j] * row[k]
                    }
                }
                for (j in 0 until p) for (k in 0 until j) jtj[j][k] = jtj[k][j]

                var improved = false
                while (lambda < 1e10) {
                    val a = Array(p) { j -> jtj[j].copyOf() }
                    for (j in 0 until p) {
                        // A zero-gain band has no freq/Q derivative at all; the
                        // floor keeps the system solvable instead of singular.
                        a[j][j] += lambda * max(jtj[j][j], 1e-6)
                    }
                    val delta = solve(a, jtr.copyOf())
                    if (delta == null) {
                        lambda *= 4.0
                        continue
                    }
                    val trial = bands.mapIndexed { bi, b ->
                        Bell(
                            2.0.pow(log2(b.freq) + delta[bi * 3]),
                            b.gain + delta[bi * 3 + 1],
                            2.0.pow(log2(b.q) + delta[bi * 3 + 2]),
                        ).also { clamp(it, bi) }
                    }
                    val c = cost(trial)
                    if (c < current) {
                        for (bi in bands.indices) bands[bi] = trial[bi]
                        lambda = max(lambda / 3.0, 1e-9)
                        improved = c < current * (1.0 - 1e-9)
                        current = c
                        break
                    }
                    lambda *= 4.0
                }
                if (!improved) return
            }
        }

        /** Gaussian elimination with partial pivoting; null if singular. */
        private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            for (col in 0 until n) {
                var pivot = col
                for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
                if (abs(a[pivot][col]) < 1e-300) return null
                if (pivot != col) {
                    val t = a[col]; a[col] = a[pivot]; a[pivot] = t
                    val tb = b[col]; b[col] = b[pivot]; b[pivot] = tb
                }
                for (r in col + 1 until n) {
                    val f = a[r][col] / a[col][col]
                    if (f == 0.0) continue
                    for (k in col until n) a[r][k] -= f * a[col][k]
                    b[r] -= f * b[col]
                }
            }
            for (r in n - 1 downTo 0) {
                var s = b[r]
                for (k in r + 1 until n) s -= a[r][k] * b[k]
                b[r] = s / a[r][r]
            }
            return b
        }
    }
}

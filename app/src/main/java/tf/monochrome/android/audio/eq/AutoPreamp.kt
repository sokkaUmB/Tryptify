// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.eq

import kotlin.math.log10
import kotlin.math.pow
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.ToneControls

/**
 * The AutoEQ preamp, which sits in front of the correction AND the bass/treble
 * tone shelves, so it sets the level of both.
 *
 * One rule for every place that applies or shows it — the in-app processor, the
 * system-wide effect and the EQ screen — so a tone knob turned from the
 * player's Audio panel moves the automatic preamp exactly as one turned on the
 * EQ screen does, and the number the screen shows is the one playing.
 */
object AutoPreamp {

    /**
     * The preamp the audio applies, in dB.
     *
     * - With neither stage changing the sound (correction off, both shelves at
     *   0 dB) there is nothing in front of which to sit: 0, and the audio
     *   passes untouched, as with the EQ switched off.
     * - Automatic: sized to the peak of what is actually playing — the
     *   correction only while it is on, plus the shelves — so a bass boost
     *   with the correction off is pulled down by the boost, not by
     *   correction bands that are not playing.
     * - Manual: the slider's value, the shelves included.
     *
     * [correctionL] and [correctionR] are the saved correction for each ear (the
     * same list twice outside 2-channel mode).
     */
    fun applied(
        correctionOn: Boolean,
        automatic: Boolean,
        manualDb: Float,
        correctionL: List<EqBand>,
        correctionR: List<EqBand>,
        tone: ToneControls,
    ): Float {
        val toneBands = tone.toBands()
        val toneShaping = toneBands.any { it.enabled }
        if (!correctionOn && !toneShaping) return 0f
        if (!automatic) return manualDb
        val l = if (correctionOn) correctionL else emptyList()
        val r = if (correctionOn) correctionR else emptyList()
        return -maxOf(peakBoostDb(l + toneBands), peakBoostDb(r + toneBands))
    }

    /**
     * Peak of the summed filter magnitude response in dB, evaluated on a log-
     * frequency grid plus every filter's centre frequency (a peaking filter's
     * maximum sits exactly at its centre, so no narrow peak can fall between
     * grid points). Using the real response — the same RBJ biquad math the
     * audio path runs — means overlapping boosts are compensated by what they
     * actually sum to, and boosts at far-apart frequencies aren't over-
     * compensated the way naive per-stage max-gain addition would.
     * Never negative: a cut-only setup needs no preamp.
     */
    fun peakBoostDb(bands: List<EqBand>, sampleRate: Float = MODEL_SAMPLE_RATE): Float {
        val active = bands.filter { it.enabled }
        if (active.isEmpty()) return 0f
        val logMin = log10(20f)
        val logMax = log10(20_000f)
        val freqs = (0 until GRID_POINTS).map { i ->
            10.0.pow(logMin + i * (logMax - logMin) / (GRID_POINTS - 1.0)).toFloat()
        } + active.map { it.freq }
        var peak = 0f
        for (freq in freqs) {
            var sum = 0f
            for (band in active) {
                sum += AutoEqEngine.calculateBiquadResponse(freq, band, sampleRate)
            }
            if (sum > peak) peak = sum
        }
        return peak
    }

    private const val GRID_POINTS = 96

    /**
     * Canonical rate for the response model. With matched (decramped)
     * coefficients the modeled response is rate-invariant to within a few
     * tenths of a dB, so the preamp needs no stream rate.
     */
    const val MODEL_SAMPLE_RATE = 48000f
}

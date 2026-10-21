// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.data

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Tap to sync: the audio offset measured by the player tapping along to a
 * click track, for headphones — Bluetooth above all — that deliver sound a
 * few hundred milliseconds after the phone sends it.
 *
 * The track plays through the game's own transport, and each tap is placed on
 * the same clock a lane press is judged on, before any offset. So the distance
 * from a tap to its click is exactly what the game would judge as late, and
 * the median of those distances is the offset to set — not a correction to the
 * current one.
 *
 * Which click a tap belongs to: at [GlyphSyncTrack.BPM] the clicks are closer
 * together than Bluetooth can be late, so "the nearest click" would read a
 * 350 ms lateness as 80 ms early for the next one. Instead the first tap
 * anchors it. The track opens on silence, so the first tap answers the first
 * click: its distance from it, up to [LATEST_MS], is the first reading. Every
 * tap after belongs to the click nearest where the reading so far says it
 * should land — any lateness at all, so long as the taps keep within half a
 * beat of each other. The first [WARMUP] taps anchor but are not counted; a
 * second tap on one click is ignored.
 */
class GlyphTapSync(
    private val firstBeatSeconds: Float = GlyphSyncTrack.FIRST_BEAT_SECONDS,
    private val beatSeconds: Float = GlyphSyncTrack.BEAT_SECONDS,
) {
    private val deltasMs = ArrayList<Float>()
    private val allMs = ArrayList<Float>()
    private var lastBeat = Int.MIN_VALUE

    /** Taps counted so far, the warm-up left out. */
    val taps: Int get() = deltasMs.size

    val done: Boolean get() = taps >= NEEDED

    /**
     * A tap at [songSeconds] on the track's clock, before any offset. True when
     * it counted toward the reading.
     */
    fun tap(songSeconds: Float): Boolean {
        if (done) return false
        val since = songSeconds - firstBeatSeconds
        if (since * 1000f < -EARLIEST_MS) return false
        val beat = if (allMs.isEmpty()) {
            // The first tap answers the first click it can: the first click
            // unless it is later than any headphones are.
            var k = 0
            while ((since - k * beatSeconds) * 1000f > LATEST_MS) k += 1
            k
        } else {
            ((since - median(allMs) / 1000f) / beatSeconds).roundToInt()
        }
        if (beat <= lastBeat) return false
        lastBeat = beat
        val delta = (since - beat * beatSeconds) * 1000f
        allMs += delta
        if (allMs.size <= WARMUP) return false
        deltasMs += delta
        return true
    }

    /** The offset the taps say, in whole milliseconds; null until [MIN_TAPS] have counted. */
    val offsetMs: Int?
        get() = if (taps < MIN_TAPS) null else median(deltasMs).roundToInt()

    /**
     * How evenly the taps fell, as a standard deviation from the median
     * absolute deviation (so one stray tap does not swing it); null with
     * too few.
     */
    val spreadMs: Int?
        get() {
            if (taps < MIN_TAPS) return null
            val centre = median(deltasMs)
            return (median(deltasMs.map { abs(it - centre) }) * MAD_TO_SD).roundToInt()
        }

    /** Taps that wander more than this read as an uncertain offset. */
    val steady: Boolean get() = (spreadMs ?: Int.MAX_VALUE) <= STEADY_MS

    /**
     * A reading this late could also be a player who sat out the first click
     * and came in on the second: the one case the anchor cannot tell apart.
     */
    val couldBeAClickOff: Boolean
        get() = (offsetMs ?: 0) > beatSeconds * 1000f - EARLIEST_MS

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
    }

    companion object {
        const val NEEDED = 16
        const val MIN_TAPS = 8
        const val WARMUP = 2
        /** Nobody taps further ahead of a click they are waiting for. */
        const val EARLIEST_MS = 100
        /** No headphones are later than this; the offset's own limit. */
        const val LATEST_MS = GlyphCalibration.MAX_OFFSET_MS
        const val STEADY_MS = 35
        private const val MAD_TO_SD = 1.4826f
    }
}

/**
 * The click track tap to sync plays: a lead-in of silence, then a click on
 * every beat at [BPM], the first of each bar higher, as the metronome's. Made
 * here as a WAV rather than shipped, so it is exactly the beat [GlyphTapSync]
 * measures against.
 */
object GlyphSyncTrack {
    const val BPM = 140
    const val BEAT_SECONDS = 60f / BPM
    /** Silence before the first click: time to put the phone down and listen. */
    const val FIRST_BEAT_SECONDS = 2f
    const val BEATS = 48
    const val SAMPLE_RATE = 44_100

    /** Where the last click has sounded and rung out. */
    val endSeconds: Float get() = FIRST_BEAT_SECONDS + BEATS * BEAT_SECONDS + 0.5f

    fun wav(): ByteArray {
        val frames = (endSeconds * SAMPLE_RATE).toInt()
        val samples = ShortArray(frames)
        val clickFrames = (CLICK_SECONDS * SAMPLE_RATE).toInt()
        for (beat in 0 until BEATS) {
            val start = ((FIRST_BEAT_SECONDS + beat * BEAT_SECONDS) * SAMPLE_RATE).roundToInt()
            val hz = if (beat % 4 == 0) DOWNBEAT_HZ else BEAT_HZ
            for (i in 0 until clickFrames) {
                val t = i.toDouble() / SAMPLE_RATE
                val value = sin(2.0 * PI * hz * t) * exp(-t * DECAY) * AMPLITUDE
                samples[start + i] = (value * Short.MAX_VALUE).roundToInt().toShort()
            }
        }
        val bytes = ByteArrayOutputStream(44 + frames * 2)
        DataOutputStream(bytes).use { out ->
            fun intLe(v: Int) = out.writeInt(Integer.reverseBytes(v))
            fun shortLe(v: Int) = out.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
            out.writeBytes("RIFF")
            intLe(36 + frames * 2)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            intLe(16)
            shortLe(1)
            shortLe(1)
            intLe(SAMPLE_RATE)
            intLe(SAMPLE_RATE * 2)
            shortLe(2)
            shortLe(16)
            out.writeBytes("data")
            intLe(frames * 2)
            for (s in samples) shortLe(s.toInt())
        }
        return bytes.toByteArray()
    }

    private const val CLICK_SECONDS = 0.04
    private const val BEAT_HZ = 1_000.0
    private const val DOWNBEAT_HZ = 1_500.0
    private const val DECAY = 90.0
    private const val AMPLITUDE = 0.6
}

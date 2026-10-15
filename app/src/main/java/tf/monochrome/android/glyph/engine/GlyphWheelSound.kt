// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The music wheel's "shh": a short swish each time the wheel turns an item
 * into its centre, as StepMania's wheel sounds as it turns.
 *
 * One [AudioTrack] for the wheel's life, rewound and replayed rather than built
 * per swish: a fling turns a dozen items a second, and the metronome's track
 * per click would be a dozen tracks a second. Each swish restarts the last, so
 * a fast turn is "shh-shh-shh" rather than a wash. The track is driven from
 * one background thread, never the frame that moved the wheel.
 *
 * Sonification, not media, as the metronome is: it ducks under a call and is
 * never what a headset's buttons are aimed at. A track that cannot be made
 * leaves the wheel silent, never broken.
 */
class GlyphWheelSound {

    private val gate = GlyphWheelSwish.Gate()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GlyphWheelSound").apply { isDaemon = true }
    }

    // Touched only on [worker].
    private var track: AudioTrack? = null
    private var failed = false

    /** A swish now, unless one began too recently; [nowNanos] is the caller's clock. */
    fun swish(nowNanos: Long) {
        val rate = gate.admit(nowNanos) ?: return
        runCatching { worker.execute { play(rate) } }
    }

    fun release() {
        runCatching {
            worker.execute {
                track?.release()
                track = null
            }
            worker.shutdown()
        }
    }

    private fun play(rate: Float) {
        if (failed) return
        runCatching {
            val output = track ?: build().also { track = it }
            // A static track is rewound only once stopped.
            if (output.playState == AudioTrack.PLAYSTATE_PLAYING) output.stop()
            output.reloadStaticData()
            output.playbackRate = (GlyphWheelSwish.SAMPLE_RATE * rate).roundToInt()
            output.play()
        }.onFailure {
            failed = true
            track?.release()
            track = null
        }
    }

    private fun build(): AudioTrack {
        val samples = GlyphWheelSwish.render()
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(GlyphWheelSwish.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .also { it.write(samples, 0, samples.size) }
    }
}

/**
 * The swish itself, and when to play it: plain arithmetic, so it is tested on
 * the desktop.
 */
object GlyphWheelSwish {

    const val SAMPLE_RATE = 44_100

    /** Length of the swish; a wheel step is about this long at a brisk turn. */
    const val LENGTH_SECONDS = 0.1

    /**
     * Peak level, the attack's, just under the metronome's 0.35. The body after
     * it is as loud as the swish was before it had one.
     */
    const val AMPLITUDE = 0.34

    /** No more often than this: a fling would otherwise be a buzz. */
    const val MIN_GAP_NANOS = 55_000_000L

    /** Turning slower than one item in this long plays at the swish's own pitch. */
    const val SLOW_GAP_NANOS = 250_000_000L

    /** How far the pitch rises at the fastest turn. */
    const val FAST_RATE = 1.12f

    /**
     * White noise through two band-passes swept from 5.5 kHz down to 3.2 kHz —
     * the band a whispered "shh" sits in — with an attack on the front: a 1 ms
     * rise, a 6 ms transient over the body, and the filter starting 2.5 kHz
     * brighter and falling into the sweep within about 10 ms, the "t" of
     * "tsh". Then a fall that reaches silence at the end. The noise has a
     * fixed seed, so every swish is the same swish.
     */
    fun render(sampleRate: Int = SAMPLE_RATE): ShortArray {
        val frames = (sampleRate * LENGTH_SECONDS).roundToInt()
        val noise = Random(SEED)
        val first = Biquad()
        val second = Biquad()
        val out = DoubleArray(frames)
        for (i in 0 until frames) {
            val progress = i.toDouble() / frames
            val t = i.toDouble() / sampleRate
            val centre = SWEEP_FROM_HZ + (SWEEP_TO_HZ - SWEEP_FROM_HZ) * progress +
                BRIGHT_HZ * exp(-t / BRIGHT_SECONDS)
            first.bandPass(centre, Q, sampleRate)
            second.bandPass(centre, Q, sampleRate)
            out[i] = second.step(first.step(noise.nextDouble() * 2.0 - 1.0))
        }
        // The envelope after the filters, so their ring-up cannot click.
        for (i in 0 until frames) out[i] *= envelope(i.toDouble() / sampleRate, frames.toDouble() / sampleRate)
        val peak = out.maxOf { abs(it) }.takeIf { it > 0.0 } ?: 1.0
        return ShortArray(frames) { i -> (out[i] / peak * AMPLITUDE * Short.MAX_VALUE).roundToInt().toShort() }
    }

    /** Playback rate for a swish [gapNanos] after the last: 1 when slow, up to [FAST_RATE] when fast. */
    fun rateFor(gapNanos: Long): Float {
        if (gapNanos >= SLOW_GAP_NANOS) return 1f
        val speed = (SLOW_GAP_NANOS - gapNanos.coerceAtLeast(MIN_GAP_NANOS)).toFloat() /
            (SLOW_GAP_NANOS - MIN_GAP_NANOS)
        return 1f + (FAST_RATE - 1f) * speed
    }

    /** Lets a swish through no more often than [MIN_GAP_NANOS], with its rate. */
    class Gate {
        private var last = Long.MIN_VALUE

        fun admit(nowNanos: Long): Float? {
            if (last == Long.MIN_VALUE) {
                last = nowNanos
                return 1f
            }
            val gap = nowNanos - last
            if (gap < MIN_GAP_NANOS) return null
            last = nowNanos
            return rateFor(gap)
        }
    }

    private fun envelope(t: Double, length: Double): Double {
        val rise = (t / RISE_SECONDS).coerceAtMost(1.0)
        // The transient over the body: the attack, gone in a few milliseconds.
        val fall = BODY * exp(-t / DECAY_SECONDS) + PUNCH * exp(-t / PUNCH_SECONDS)
        // The last few milliseconds taper to nothing, so the cut is silent.
        val tail = ((length - t) / TAIL_SECONDS).coerceIn(0.0, 1.0)
        return rise * fall * tail
    }

    /** RBJ's band-pass, 0 dB at its centre; its coefficients move with the sweep. */
    private class Biquad {
        private var b0 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun bandPass(centreHz: Double, q: Double, sampleRate: Int) {
            val w0 = 2.0 * PI * centreHz / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            b0 = alpha / a0
            b2 = -alpha / a0
            a1 = -2.0 * cos(w0) / a0
            a2 = (1.0 - alpha) / a0
        }

        fun step(x: Double): Double {
            val y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }
    }

    private const val SEED = 0x5157L
    private const val SWEEP_FROM_HZ = 5_500.0
    private const val SWEEP_TO_HZ = 3_200.0
    private const val Q = 0.9
    private const val RISE_SECONDS = 0.001
    private const val DECAY_SECONDS = 0.028
    private const val BODY = 0.6
    private const val PUNCH = 0.5
    private const val PUNCH_SECONDS = 0.006
    private const val BRIGHT_HZ = 2_500.0
    private const val BRIGHT_SECONDS = 0.008
    private const val TAIL_SECONDS = 0.006
}

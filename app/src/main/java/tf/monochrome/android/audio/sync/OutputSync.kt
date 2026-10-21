package tf.monochrome.android.audio.sync

import kotlin.math.roundToInt
import tf.monochrome.android.audio.eq.OutputId
import tf.monochrome.android.audio.eq.OutputSlot

/**
 * How late each output plays, so everything that follows the music can wait
 * for it: the lyrics, the visuals and their music links, the visualizer and
 * Trypt Mania. Bluetooth is the reason (the codec and the headphones' own
 * buffer), but the delay is kept per output, as the EQ's presets are
 * ([tf.monochrome.android.audio.eq.OutputEq]).
 *
 * A device's own measurement wins over its slot's: a pair of earbuds and a car
 * play at different delays, and an unmeasured device takes what the slot has.
 * Milliseconds, positive when the sound reaches the ear late. Kept on this
 * phone only: another phone's Bluetooth stack is another delay.
 */
object OutputSync {
    const val MIN_MS = -500
    const val MAX_MS = 1000

    fun clamp(ms: Int): Int = ms.coerceIn(MIN_MS, MAX_MS)

    /** The delay [output] plays at: its own, its slot's, or none. */
    fun delayFor(output: OutputId?, delays: Map<String, Int>): Int {
        output ?: return 0
        return clamp(delays[output.key] ?: delays[output.generic.key] ?: 0)
    }

    /** [delays] with [output]'s set to [ms]: the device's own when it has a name. */
    fun set(delays: Map<String, Int>, output: OutputId, ms: Int): Map<String, Int> =
        delays + (output.key to clamp(ms))

    /**
     * The three delays the app had before this one, folded into it: Trypt
     * Mania's offset, measured by its tap test, and failing that the lyrics'
     * and then the visualizer's, both set by hand. They were made for
     * Bluetooth and applied to every output, so the value goes to the whole
     * Bluetooth slot; every other output starts at none.
     */
    fun migrate(tryptManiaMs: Int, lyricsMs: Float, visualizerMs: Int): Map<String, Int> {
        val ms = listOf(tryptManiaMs, lyricsMs.roundToInt(), visualizerMs).firstOrNull { it != 0 } ?: return emptyMap()
        return mapOf(OutputId(OutputSlot.BLUETOOTH).key to clamp(ms))
    }
}

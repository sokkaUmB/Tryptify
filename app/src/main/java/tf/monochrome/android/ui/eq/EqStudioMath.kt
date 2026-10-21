package tf.monochrome.android.ui.eq

import androidx.compose.ui.graphics.Color
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** The frequency range a filter may be set in, as the graph spans it. */
internal object StudioAxis {
    const val MIN_FREQ = EqLimits.MIN_FREQ_HZ
    const val MAX_FREQ = EqLimits.MAX_FREQ_HZ
}

/**
 * What Precision AutoEQ's filter editor does to a filter: removing one, and the
 * scrub pills' moves, rounded and kept in range.
 */
internal object StudioBands {
    const val MIN_Q = 0.1f
    const val MAX_Q = 10f

    fun without(bands: List<EqBand>, id: Int): List<EqBand> = bands.filterNot { it.id == id }

    /** A frequency rounded the way a person would type it: 3 significant figures. */
    fun roundFreq(freq: Float): Float {
        if (freq <= 0f) return freq
        val magnitude = 10f.pow(kotlin.math.floor(log10(freq)) - 2f)
        return (freq / magnitude).roundToInt() * magnitude
    }

    /** A drag of [octaves] along the axis from [start]. */
    fun scrubFreq(start: Float, octaves: Float): Float =
        (start * 2f.pow(octaves)).coerceIn(StudioAxis.MIN_FREQ, StudioAxis.MAX_FREQ)

    fun clampGain(gain: Float): Float =
        gain.coerceIn(-EqLimits.AUTOEQ_MAX_BAND_DB, EqLimits.AUTOEQ_MAX_BAND_DB)

    /** Q moves on a log scale, so a drag feels the same at 0.5 as at 5. */
    fun scrubQ(start: Float, octaves: Float): Float =
        (start * 2f.pow(octaves)).coerceIn(MIN_Q, MAX_Q)
}

/**
 * Each filter's colour. Keyed on the filter's id, not its position, so sorting
 * or deleting another filter never repaints the one being tuned. The hue steps
 * by the golden angle, which keeps neighbours far apart however many there are.
 */
internal fun studioFilterColor(id: Int): Color {
    val hue = ((id * 137.508f) % 360f + 360f) % 360f
    return Color.hsv(hue, 0.62f, 0.93f)
}

/** "105 Hz", "1.2 kHz", "16 kHz". */
internal fun formatStudioFreq(freq: Float): String = when {
    freq >= 10_000f -> "${(freq / 1000f).roundToInt()} kHz"
    freq >= 1000f -> String.format(java.util.Locale.US, "%.1f kHz", freq / 1000f)
    else -> "${freq.roundToInt()} Hz"
}

/** "+3.5 dB", "−2.0 dB", "0.0 dB". */
internal fun formatStudioGain(gain: Float): String {
    val rounded = (gain * 10f).roundToInt() / 10f
    return when {
        rounded > 0f -> String.format(java.util.Locale.US, "+%.1f dB", rounded)
        rounded < 0f -> String.format(java.util.Locale.US, "−%.1f dB", abs(rounded))
        else -> "0.0 dB"
    }
}

internal fun formatStudioQ(q: Float): String = String.format(java.util.Locale.US, "%.2f", q)

/** The short code peqdb, Equalizer APO and AutoEq all use for a filter type. */
internal fun FilterType.studioCode(): String = when (this) {
    FilterType.PEAKING -> "PK"
    FilterType.LOWSHELF -> "LSC"
    FilterType.HIGHSHELF -> "HSC"
}

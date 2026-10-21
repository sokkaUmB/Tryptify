// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * How fast the separation servers chart, learned from the songs they have
 * charted, and what each charges: what a batch's confirmation says before it
 * starts, for Fast (a GPU) and Slow (a CPU) alike.
 *
 * Learned, not asked: asking a server would wake it, and a woken GPU bills
 * for its idle minutes whether the batch is then started or not. A server's
 * price and idle time come back with every song it separates, and are kept
 * from there; one that does not say (Railway's) shows time without a cost.
 */
@Singleton
class ChartPace @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun snapshot(gpu: Boolean): Pace {
        val kind = kindOf(gpu)
        return Pace(
            ratio = prefs.getFloat("ratio_$kind", Float.NaN).toDouble().takeUnless { it.isNaN() },
            pricePerHour = prefs.getFloat("price_$kind", Float.NaN).toDouble().takeUnless { it.isNaN() },
            idleSeconds = prefs.getInt("idle_$kind", -1).takeIf { it >= 0 },
            parallel = prefs.getInt("parallel_$kind", -1).takeIf { it >= 1 },
        )
    }

    /** A song a server separated: its pace, and the server's terms as it gave them. */
    @Synchronized
    fun recordServer(run: RemoteRun) {
        val gpu = run.device == "cuda"
        val kind = kindOf(gpu)
        val edit = prefs.edit()
        run.batchSecondsPerAudioSecond?.let { edit.putFloat("ratio_$kind", blend(snapshot(gpu).ratio, it).toFloat()) }
        // A server that stopped saying leaves no stale price behind.
        if (run.pricePerHour != null) edit.putFloat("price_$kind", run.pricePerHour.toFloat()) else edit.remove("price_$kind")
        if (run.idleSeconds != null) edit.putInt("idle_$kind", run.idleSeconds) else edit.remove("idle_$kind")
        if (run.parallel != null) edit.putInt("parallel_$kind", run.parallel) else edit.remove("parallel_$kind")
        edit.apply()
    }

    /** What has been learned of one kind of server; null where nothing has been. */
    data class Pace(
        val ratio: Double? = null,
        val pricePerHour: Double? = null,
        val idleSeconds: Int? = null,
        /** How many songs the server separates at once, as it last said. */
        val parallel: Int? = null,
    )

    /** How long a batch should take and, on a server that says what it charges, what it should cost. */
    data class Estimate(val seconds: Long, val costDollars: Double?)

    companion object {
        private const val PREFS = "trypt_mania_chart_pace"

        private fun kindOf(gpu: Boolean) = if (gpu) "gpu" else "cpu"

        /**
         * Seconds a batch takes per second of audio before anything is learned,
         * as measured: 30 s of music in 3.8 s on Modal's L4 with htdemucs_ft,
         * and in 11 s on four CPU cores with htdemucs_6s as ONNX, each rounded
         * up for a phone's upload.
         */
        const val DEFAULT_GPU_RATIO = 0.15
        const val DEFAULT_CPU_RATIO = 0.40

        /** A sleeping server's start, loading its model: 15 to 20 s measured on both. */
        const val SERVER_START_SECONDS = 20L

        /** Each new song moves the learned pace this far towards itself. */
        private const val LEARN = 0.3

        internal fun blend(old: Double?, sample: Double): Double =
            if (old == null) sample else old + (sample - old) * LEARN

        /**
         * A batch of [songs] songs, [audioSeconds] of music, on a GPU or a CPU
         * server: the songs at the learned pace, shared among as many copies
         * of the server as it runs at once and the batch has songs for, plus a
         * start in case it is asleep. The cost, where the server says its
         * price, is every copy's time: the songs, and each copy's start and
         * the idle minutes it stays up after.
         */
        fun estimate(audioSeconds: Long, gpu: Boolean, pace: Pace, songs: Int = 1): Estimate {
            val ratio = pace.ratio ?: if (gpu) DEFAULT_GPU_RATIO else DEFAULT_CPU_RATIO
            val busy = audioSeconds * ratio
            val copies = (pace.parallel ?: 1).coerceAtMost(songs.coerceAtLeast(1)).coerceAtLeast(1)
            val seconds = (busy / copies).roundToLong() + SERVER_START_SECONDS
            val cost = pace.pricePerHour?.let { price ->
                price * (busy + copies * (SERVER_START_SECONDS + (pace.idleSeconds ?: 0))) / 3600.0
            }
            return Estimate(seconds, cost)
        }

        /** "under a minute", "about 6 minutes", "about 1 h 20 min". */
        fun describeDuration(seconds: Long): String = when {
            seconds < 60 -> "under a minute"
            seconds < 3600 -> ((seconds + 30) / 60).let { if (it == 1L) "about a minute" else "about $it minutes" }
            else -> {
                val minutes = (seconds + 30) / 60
                if (minutes % 60 == 0L) "about ${minutes / 60} h" else "about ${minutes / 60} h ${minutes % 60} min"
            }
        }

        /** "under 1¢", "about 7¢", "about $1.40". */
        fun describeCost(dollars: Double): String {
            val cents = dollars * 100
            return when {
                cents < 1 -> "under 1¢"
                cents < 99.5 -> "about ${cents.roundToLong()}¢"
                else -> "about $" + String.format(Locale.ROOT, "%.2f", dollars)
            }
        }
    }
}

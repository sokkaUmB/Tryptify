// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.scrobbling

import tf.monochrome.android.domain.model.Track

/**
 * When a play becomes a scrobble, by Last.fm's own rule: a track of at least
 * 30 seconds, heard for half its length or four minutes, whichever comes
 * first. Counted in time heard (paused time is not, and a faster speed hears
 * more of the song a second), and sent once per play, stamped with when the
 * play started.
 *
 * It used to be a scrobble only when the player reported the track ended.
 * A gapless hand-off, how one queued track runs into the next, never does,
 * and neither does a skip: in a queue nothing but its last track was ever
 * scrobbled, while "now playing", sent when each track starts, showed fine.
 */
class ScrobbleTracker {
    private var track: Track? = null
    private var startedAtUnix = 0L
    private var heardMs = 0.0
    private var sent = false

    /** A new play of [track], started at [nowUnix] (seconds). */
    fun start(track: Track, nowUnix: Long) {
        this.track = track
        startedAtUnix = nowUnix
        heardMs = 0.0
        sent = false
    }

    /** [elapsedMs] more of the current play heard, at [speed]. */
    fun heard(elapsedMs: Long, speed: Float = 1f) {
        if (track == null || sent || elapsedMs <= 0) return
        heardMs += elapsedMs * speed.coerceIn(0.25f, 4f)
    }

    /** The play to scrobble now, once: the track and when it started; else null. */
    fun due(): Pair<Track, Long>? {
        val t = track ?: return null
        if (sent) return null
        val lengthMs = t.duration * 1000L
        if (lengthMs in 1 until MIN_LENGTH_MS) return null
        val needed = if (lengthMs > 0) minOf(lengthMs / 2, FOUR_MINUTES_MS) else FOUR_MINUTES_MS
        if (heardMs < needed) return null
        sent = true
        return t to startedAtUnix
    }

    companion object {
        const val MIN_LENGTH_MS = 30_000L
        const val FOUR_MINUTES_MS = 240_000L
    }
}

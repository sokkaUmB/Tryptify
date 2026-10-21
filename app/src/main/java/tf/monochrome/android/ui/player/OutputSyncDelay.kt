package tf.monochrome.android.ui.player

import androidx.compose.runtime.compositionLocalOf

/**
 * How late the output playing now plays, in milliseconds
 * ([tf.monochrome.android.audio.sync.OutputSync]): what the lyrics and the
 * visual music links wait, so they land with the sound rather than ahead of
 * it. Provided once at the top of the app, from the output's own measurement.
 */
val LocalOutputSyncMs = compositionLocalOf { 0f }

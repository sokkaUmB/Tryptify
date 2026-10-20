package tf.monochrome.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Where the app's dynamic colours come from: the playing track's cover, unless
 * an open screen that picks songs of its own has taken its place.
 *
 * Trypt Mania is that screen. Its wheel picks a song the player is not playing
 * (the previews play through the game's own player), and while the mode
 * followed the picked song and everything else followed the playing track,
 * two covers coloured the screen at once: the tab bar and, with "Tint the
 * menus too", the ground from one, the accent from the other. There is one
 * palette now, built in `MainActivity` from [coverFor], so every surface that
 * follows the music follows the same song.
 */
object DynamicColorSource {

    /** A screen's stand-in cover, and the longest its colours may take to arrive. */
    data class Override(val owner: Any, val coverUrl: String?, val maxBlendMillis: Int)

    /** The stand-in in force, or null for the playing track. */
    var override by mutableStateOf<Override?>(null)
        internal set

    /** The cover the palette is built from: the stand-in's while there is one. */
    fun coverFor(playingCoverUrl: String?): String? =
        override.let { if (it != null) it.coverUrl else playingCoverUrl }

    /** The fade length for [blendMillis], capped by the stand-in while there is one. */
    fun blendFor(blendMillis: Int): Int =
        override?.let { blendMillis.coerceAtMost(it.maxBlendMillis) } ?: blendMillis
}

/**
 * Makes [coverUrl] the source of the app's dynamic colours for as long as the
 * caller is composed, fading at most [maxBlendMillis]. A cover of null is a
 * song with no art: the plain theme, not the playing track's colours. On
 * leaving, the playing track takes over again; a stand-in set by someone
 * else since is left alone.
 */
@Composable
fun OverrideDynamicColorSource(coverUrl: String?, maxBlendMillis: Int) {
    val owner = remember { Any() }
    // Equal values are not a change (structural equality), so recomposing
    // with the same song writes nothing and wakes no one.
    SideEffect {
        DynamicColorSource.override = DynamicColorSource.Override(owner, coverUrl, maxBlendMillis)
    }
    DisposableEffect(owner) {
        onDispose {
            if (DynamicColorSource.override?.owner === owner) DynamicColorSource.override = null
        }
    }
}

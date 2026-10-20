// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.party

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import tf.monochrome.android.domain.model.Track

/**
 * The listening party's mark: two listeners side by side, a note between
 * them, and one line under all three, the song they share. One colour, so it
 * takes the row's tint as any icon does, on the Account page and on the
 * party screen alike.
 */
val ListeningPartyIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ListeningParty",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        val ink = SolidColor(Color.Black)
        // The two listeners: a head and the shoulders under it, each side.
        addPath(pathData = addPathNodes(LEFT_HEAD + LEFT_BODY + RIGHT_HEAD + RIGHT_BODY), fill = ink)
        // The note: its head, its stem and its flag.
        addPath(pathData = addPathNodes(NOTE), fill = ink)
        // What they share.
        addPath(
            pathData = addPathNodes("M4 20.6 Q12 23.4 20 20.6"),
            stroke = ink,
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
        )
    }.build()
}

private const val LEFT_HEAD = "M7.6 9.6 A2.3 2.3 0 1 1 3.0 9.6 A2.3 2.3 0 1 1 7.6 9.6 Z "
private const val LEFT_BODY = "M1.4 18.2 Q1.4 13.4 5.3 13.4 Q9.2 13.4 9.2 18.2 Z "
private const val RIGHT_HEAD = "M21.0 9.6 A2.3 2.3 0 1 1 16.4 9.6 A2.3 2.3 0 1 1 21.0 9.6 Z "
private const val RIGHT_BODY = "M14.8 18.2 Q14.8 13.4 18.7 13.4 Q22.6 13.4 22.6 18.2 Z"
private const val NOTE =
    "M13.1 2.6 L13.1 12.6 A2.0 1.6 -20 1 1 11.8 11.2 L11.8 2.6 Q15.2 3.0 15.6 6.0 Q14.4 4.6 13.1 4.6 Z"

/**
 * "Add to party" from a song's menu, while a party is on: the song and its
 * file where the list knows one. Given at the app's root, as "Play in Trypt
 * Mania" is, so every list's menu offers it without wiring each one; null
 * when there is no party, and the menu then leaves the item out.
 */
val LocalAddToParty = compositionLocalOf<((Track, String?) -> Unit)?> { null }

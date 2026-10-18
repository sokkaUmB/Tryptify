package tf.monochrome.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * A Material 3 surface: a flat container fill in [shape]. What every list row,
 * card, tile and in-page pane stands on, in both of the app's looks.
 *
 * The app has two looks, liquid glass and Material 3 (Settings › System ›
 * Performance › Remove liquid glass). This used to be a third, the old
 * frosted pane (`liquidGlass`: a black tint, a gradient rim and a refraction
 * sheen, with Haze blur under chrome), which every row wore whenever liquid
 * glass was on, since rows never run the shader. It cost a clip, a border and
 * two fills per row on every scroll, and read as neither look. Glass is the
 * shader (GlassPanel, GlassMaterial, playerGlass); everything else is this.
 *
 * [tintAlpha] says how present the pane is meant to be (an idle mixer bus asks
 * for less than an active one), as the glass tint did, and picks the tone: a
 * quiet pane a container low, the default one between low and highest.
 */
fun Modifier.materialSurface(
    shape: Shape = MonoDimens.shapeMd,
    tintAlpha: Float = MonoDimens.glassAlpha,
) = composed {
    val scheme = MaterialTheme.colorScheme
    val emphasis = (tintAlpha / MonoDimens.glassAlpha / 2f).coerceIn(0f, 1f)
    this.background(lerp(scheme.surfaceContainerLow, scheme.surfaceContainerHighest, emphasis), shape)
}

/**
 * The ground for white text and icons drawn over artwork (the player hero's
 * badges and buttons, the visualizer's): a black scrim, whatever the theme.
 * A container fill there is light on a light theme, under white ink.
 */
fun Modifier.overArtScrim(shape: Shape, tintAlpha: Float = MonoDimens.glassAlpha): Modifier =
    background(Color.Black.copy(alpha = (tintAlpha * 1.4f).coerceAtMost(0.5f)), shape)

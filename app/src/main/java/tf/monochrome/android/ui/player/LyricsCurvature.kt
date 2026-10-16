// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.player

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.asin
import kotlin.math.sin

/**
 * The lyrics on a curved tube, as a CRT draws them (Lyrics FX › Curvature,
 * [tf.monochrome.android.domain.model.LyricsFxSettings.curvatureDeg]): the
 * flat lyrics wrapped over a glass dome that turns [degrees] away from the
 * viewer between its middle and the middle of each side, and seen from the
 * front. The middle swells toward the viewer, lines bow outward, a line's ends
 * stay at the edges, and the corners, past the dome's rim, round off.
 *
 * Put it innermost on the lyric surface, inside the copy of the letters the
 * backdrop takes for their shadow and their light, so the shadow and the
 * shafts bend with them and the glass bevels the bent letters.
 *
 * With [focusInRoot] (Lyrics FX › Curve: the sung line or the sung word) the
 * dome sits over that box alone, read in the draw phase so it follows the
 * scroll and hops from word to word, a little taller than the box so the
 * letters fit under it ([wide] adds room at its sides for a word). Outside it
 * the lyrics are flat, and at its rim the dome meets them exactly. No box (the
 * line off screen) leaves everything flat.
 *
 * Android 13 and later; flat below, and under [MIN_CURVATURE_DEG].
 */
internal fun Modifier.lyricCurvature(
    degrees: Float,
    focusInRoot: (() -> Rect?)? = null,
    wide: Boolean = false,
): Modifier =
    if (degrees < MIN_CURVATURE_DEG || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        this
    } else {
        this.composed {
            val shader = remember { compileCurvature() }
            val origin = remember { mutableStateOf(Offset.Zero) }
            if (shader == null) {
                Modifier
            } else {
                Modifier
                    .then(if (focusInRoot != null) Modifier.onGloballyPositioned { origin.value = it.positionInRoot() } else Modifier)
                    .curved(shader, degrees, focusInRoot, origin, wide)
            }
        }
    }

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Modifier.curved(
    shader: RuntimeShader,
    degrees: Float,
    focusInRoot: (() -> Rect?)?,
    origin: State<Offset>,
    wide: Boolean,
): Modifier =
    graphicsLayer {
        if (size.width <= 0f || size.height <= 0f) return@graphicsLayer
        val dome = if (focusInRoot == null) {
            Rect(0f, 0f, size.width, size.height)
        } else {
            val box = focusInRoot()?.translate(-origin.value)
            focusDome(box, size.width, size.height, wide)
        }
        if (dome == null) {
            renderEffect = null
            return@graphicsLayer
        }
        val theta = Math.toRadians(degrees.toDouble()).toFloat()
        shader.setFloatUniform("uSize", size.width, size.height)
        shader.setFloatUniform("uCenter", dome.center.x, dome.center.y)
        shader.setFloatUniform("uRadii", dome.width / 2f, dome.height / 2f)
        shader.setFloatUniform("uLocal", if (focusInRoot == null) 0f else 1f)
        shader.setFloatUniform("uTheta", theta)
        shader.setFloatUniform("uSin", sin(theta))
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }

/**
 * The dome over [box] (in the surface's own px), on a surface of [width]:
 * the box's sides kept to the surface (a line's run past it), its height
 * raised by [DOME_HEADROOM] so the letters fit under it, and [wide] adding
 * [WORD_SIDE_ROOM] at the sides for a single word. Null without a box, or
 * for one with no size.
 */
internal fun focusDome(box: Rect?, width: Float, height: Float, wide: Boolean): Rect? {
    if (box == null) return null
    val left = box.left.coerceIn(0f, width)
    val right = box.right.coerceIn(0f, width)
    if (right - left < 1f || box.height < 1f) return null
    val halfW = (right - left) / 2f * if (wide) WORD_SIDE_ROOM else 1f
    val halfH = box.height / 2f * DOME_HEADROOM
    val cx = (left + right) / 2f
    val cy = box.center.y
    return Rect(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
}

/** The dome over a line or a word stands this much taller than it, so no letter is cut at its rim. */
internal const val DOME_HEADROOM = 1.6f

/** And this much wider than a word, so its ends are not pressed against the rim. */
internal const val WORD_SIDE_ROOM = 1.35f

/**
 * Where on the flat lyrics a point of the curved tube shows, as a share of
 * the way out from the middle: a point [radius] of the way out on the screen
 * is the dome's surface at `asin(radius · sin θ)` from its middle, and that
 * much of the arc's θ is that much of the way across the lyrics. Null past
 * the dome's rim. The shader's sum, here to be checked.
 */
internal fun curvatureSourceRadius(radius: Float, degrees: Float): Float? {
    if (degrees < MIN_CURVATURE_DEG) return radius
    val theta = Math.toRadians(degrees.toDouble())
    val x = radius * sin(theta)
    if (x >= 1.0) return null
    return (asin(x) / theta).toFloat()
}

/** Below this the lyrics are drawn flat, with no layer at all. */
internal const val MIN_CURVATURE_DEG = 0.05f

private fun compileCurvature(): RuntimeShader? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return runCatching { RuntimeShader(CURVATURE_SHADER) }
        .onFailure { Log.w("LyricsCurvature", "curvature shader did not compile; lyrics drawn flat", it) }
        .getOrNull()
}

/**
 * [curvatureSourceRadius] for every pixel, out from the dome's middle in its
 * own proportions, so the dome fits what it covers whatever its shape. Over
 * the whole surface (uLocal 0) a read past the rim, or off the lyrics, is
 * clear: the tube's rounded corners. Over a line or a word (uLocal 1) every
 * pixel outside the dome is the flat lyrics, as the rim reads itself.
 */
private const val CURVATURE_SHADER = """
uniform shader content;
uniform float2 uSize;
uniform float2 uCenter;
uniform float2 uRadii;
uniform float uLocal;
uniform float uTheta;
uniform float uSin;

half4 main(float2 p) {
    float2 c = uCenter;
    float2 d = (p - c) / uRadii;
    float r = length(d);
    if (r < 0.0001 || (uLocal > 0.5 && r >= 1.0)) {
        return content.eval(p);
    }
    float x = r * uSin;
    if (x >= 1.0) {
        return half4(0.0);
    }
    float source = asin(x) / uTheta;
    float2 q = c + d * (source / r) * uRadii;
    if (q.x < 0.0 || q.y < 0.0 || q.x > uSize.x || q.y > uSize.y) {
        return half4(0.0);
    }
    return content.eval(q);
}
"""

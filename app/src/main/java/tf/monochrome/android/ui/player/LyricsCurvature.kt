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
import kotlin.math.abs
import kotlin.math.cos

/**
 * The lyrics on a curved tube, as a CRT draws them (Lyrics FX › Curvature,
 * [tf.monochrome.android.domain.model.LyricsFxSettings.curvatureDeg]): the
 * glass bowed [degrees] away from the viewer between its middle and its
 * edge, with the flat lyrics seen through it. The middle stays as it is;
 * further out each line bows outward and its ends draw in, more toward the
 * corners, which round off into nothing ([crtSource]). Not a fish-eye: it
 * swells nothing, the sides' middles stay at the sides, and a line through
 * the middle stays straight.
 *
 * Put it innermost on the lyric surface, inside the copy of the letters the
 * backdrop takes for their shadow and their light, so the shadow and the
 * shafts bend with them and the glass bevels the bent letters.
 *
 * With [focusInRoot] (Lyrics FX › Curve: the sung line or the sung word) the
 * tube is that box alone, read in the draw phase so it follows the scroll and
 * hops from word to word, a little taller than the box so the letters fit in
 * it ([wide] adds room at its sides for a word). Outside it, and where its
 * rounded corners leave room inside it, the lyrics are flat. No box (the line
 * off screen) leaves everything flat.
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
        shader.setFloatUniform("uSize", size.width, size.height)
        shader.setFloatUniform("uCenter", dome.center.x, dome.center.y)
        shader.setFloatUniform("uRadii", dome.width / 2f, dome.height / 2f)
        shader.setFloatUniform("uLocal", if (focusInRoot == null) 0f else 1f)
        shader.setFloatUniform("uWarp", crtWarp(degrees))
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }

/**
 * The tube over [box] (in the surface's own px), on a surface of [width]:
 * the box's sides kept to the surface (a line's run past it), its height
 * raised by [DOME_HEADROOM] so the letters fit in it, and [wide] adding
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

/** The tube over a line or a word stands this much taller than it, so no letter reaches its rim. */
internal const val DOME_HEADROOM = 1.6f

/** And this much wider than a word, so its ends are not pressed against the rim. */
internal const val WORD_SIDE_ROOM = 1.35f

/**
 * How far the tube bows at [degrees]: `1 / cos θ − 1`, how much further out
 * than its corner on the glass a corner of the flat lyrics lies. The CRT
 * shader's `warp`: about 1/32 at 14.1°, the classic tube's, 0.155 at 30°, 1
 * at 60°. 0 below [MIN_CURVATURE_DEG].
 */
internal fun crtWarp(degrees: Float): Float {
    if (degrees < MIN_CURVATURE_DEG) return 0f
    return (1.0 / cos(Math.toRadians(degrees.coerceAtMost(MAX_CURVATURE_DEG).toDouble())) - 1.0).toFloat()
}

/**
 * Where on the flat lyrics the point [x], [y] of the tube shows, both as
 * shares of the way out from its middle (−1 to 1 across it): each pulled
 * outward by the other's square, `x · (1 + y² · warp)`, so a point's way out
 * grows toward the corners and never along the middle lines. Null past the
 * tube's edge, its rounded corners. The shader's sum, here to be checked.
 */
internal fun crtSource(x: Float, y: Float, degrees: Float): Pair<Float, Float>? {
    val warp = crtWarp(degrees)
    val sx = x * (1f + y * y * warp)
    val sy = y * (1f + x * x * warp)
    if (abs(sx) > 1f || abs(sy) > 1f) return null
    return sx to sy
}

/** The slider's far end; [crtWarp] is held there, where the tube bows four times the corners away. */
private const val MAX_CURVATURE_DEG = 75f

/** Below this the lyrics are drawn flat, with no layer at all. */
internal const val MIN_CURVATURE_DEG = 0.05f

private fun compileCurvature(): RuntimeShader? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return runCatching { RuntimeShader(CURVATURE_SHADER) }
        .onFailure { Log.w("LyricsCurvature", "curvature shader did not compile; lyrics drawn flat", it) }
        .getOrNull()
}

/**
 * [crtSource] for every pixel, out from the tube's middle in its own
 * proportions, so the tube fits what it covers whatever its shape. Over the
 * whole surface (uLocal 0) a read past the tube's edge is clear: its rounded
 * corners. Over a line or a word (uLocal 1) every pixel outside the box, and
 * any whose read falls outside it, is the flat lyrics, so the box has no hole
 * and no edge of its own.
 */
private const val CURVATURE_SHADER = """
uniform shader content;
uniform float2 uSize;
uniform float2 uCenter;
uniform float2 uRadii;
uniform float uLocal;
uniform float uWarp;

half4 main(float2 p) {
    float2 d = (p - uCenter) / uRadii;
    bool local = uLocal > 0.5;
    if (local && (abs(d.x) >= 1.0 || abs(d.y) >= 1.0)) {
        return content.eval(p);
    }
    float2 s = d * float2(1.0 + d.y * d.y * uWarp, 1.0 + d.x * d.x * uWarp);
    if (abs(s.x) > 1.0 || abs(s.y) > 1.0) {
        if (local) {
            return content.eval(p);
        }
        return half4(0.0);
    }
    float2 q = uCenter + s * uRadii;
    if (q.x < 0.0 || q.y < 0.0 || q.x > uSize.x || q.y > uSize.y) {
        return half4(0.0);
    }
    return content.eval(q);
}
"""

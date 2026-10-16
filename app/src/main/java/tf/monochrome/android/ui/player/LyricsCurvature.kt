// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.player

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
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
 * Android 13 and later; flat below, and under [MIN_CURVATURE_DEG].
 */
internal fun Modifier.lyricCurvature(degrees: Float): Modifier =
    if (degrees < MIN_CURVATURE_DEG || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        this
    } else {
        this.composed {
            val shader = remember { compileCurvature() }
            if (shader == null) Modifier else Modifier.curved(shader, degrees)
        }
    }

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Modifier.curved(shader: RuntimeShader, degrees: Float): Modifier =
    graphicsLayer {
        if (size.width <= 0f || size.height <= 0f) return@graphicsLayer
        val theta = Math.toRadians(degrees.toDouble()).toFloat()
        shader.setFloatUniform("uSize", size.width, size.height)
        shader.setFloatUniform("uTheta", theta)
        shader.setFloatUniform("uSin", sin(theta))
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }

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
 * [curvatureSourceRadius] for every pixel, out from the surface's middle in
 * its own proportions, so the dome fits the surface whatever its shape. A
 * read past the dome's rim, or off the lyrics, is clear.
 */
private const val CURVATURE_SHADER = """
uniform shader content;
uniform float2 uSize;
uniform float uTheta;
uniform float uSin;

half4 main(float2 p) {
    float2 c = uSize * 0.5;
    float2 d = (p - c) / c;
    float r = length(d);
    if (r < 0.0001) {
        return content.eval(p);
    }
    float x = r * uSin;
    if (x >= 1.0) {
        return half4(0.0);
    }
    float source = asin(x) / uTheta;
    float2 q = c + d * (source / r) * c;
    if (q.x < 0.0 || q.y < 0.0 || q.x > uSize.x || q.y > uSize.y) {
        return half4(0.0);
    }
    return content.eval(q);
}
"""

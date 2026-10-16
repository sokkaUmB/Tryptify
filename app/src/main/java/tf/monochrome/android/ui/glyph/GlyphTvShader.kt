// SPDX-License-Identifier: GPL-3.0-or-later
//
// The shader programs below are ported from PCSX2's presentation shaders
// (PCSX2 Dev Team, GPL-3.0-or-later; the CRT program is Timothy Lottes'
// public-domain CRT shader as PCSX2 configures it).

package tf.monochrome.android.ui.glyph

import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import tf.monochrome.android.glyph.engine.GlyphGraphics
import tf.monochrome.android.glyph.engine.GlyphTvShader
import tf.monochrome.android.glyph.engine.GlyphTvTuning
import tf.monochrome.android.performance.LocalPerformanceProfile

/**
 * The game screen through an old TV: one of the [GlyphTvShader]s, drawn over
 * everything the layer holds, tuned by [GlyphGraphics.tv].
 *
 * Android 13 and later, where a layer can run a shader. Off where the app
 * turns its blur off — a low-end phone, or the "Remove liquid glass" switch —
 * since this is a full-screen pass every frame. A shader the phone's GPU
 * driver will not compile turns the effect off rather than failing the run.
 *
 * Every pattern is counted in TV pixels of a whole number of the phone's own
 * pixels ([GlyphTvTuning.pixelSize]), so each one lands on the screen's
 * pixels exactly. The old line grid, 640 x 480 stretched over the window, was
 * 1.99 phone pixels a column on a 1272-pixel screen; a pattern that does not
 * divide the screen beats against its pixels as moiré. Counted in phone
 * pixels, a smaller layer (Training Ground's playfield, the options' and the
 * settings' preview) draws the same size of pixel as the game.
 */
fun Modifier.glyphTvShader(graphics: GlyphGraphics): Modifier {
    val shader = graphics.tvShader
    return if (shader == GlyphTvShader.OFF || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        this
    } else {
        this.composed {
            val runtime = remember { compileTvShader() }
            val size = remember { mutableStateOf(IntSize.Zero) }
            val density = LocalDensity.current.density
            val window = LocalWindowInfo.current.containerSize
            val pixel = graphics.tv.pixelSize(shader, density, window.height)
            if (runtime == null || !LocalPerformanceProfile.current.allowHazeBlur) {
                Modifier
            } else {
                Modifier.onSizeChanged { size.value = it }.tvLayer(runtime, shader, graphics.tv, pixel, size)
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Modifier.tvLayer(
    runtime: RuntimeShader,
    shader: GlyphTvShader,
    tv: GlyphTvTuning,
    pixel: Int,
    size: MutableState<IntSize>,
): Modifier =
    graphicsLayer {
        // Read through state, so a rotation re-runs this with the new size.
        val now = size.value
        renderEffect = if (now.width == 0 || now.height == 0) {
            null
        } else {
            runtime.setFloatUniform("uSize", now.width.toFloat(), now.height.toFloat())
            runtime.setFloatUniform("uPixel", pixel.toFloat())
            runtime.setFloatUniform("uMode", shader.mode.toFloat())
            runtime.setFloatUniform("uStrength", tv.strength.coerceIn(0f, 1f))
            runtime.setFloatUniform("uBright", tv.brightness.coerceIn(GlyphTvTuning.MIN_BRIGHTNESS, GlyphTvTuning.MAX_BRIGHTNESS))
            runtime.setFloatUniform("uScan", tv.crtScan)
            runtime.setFloatUniform("uSharp", tv.crtSharpness)
            runtime.setFloatUniform("uMaskDark", tv.crtMaskDark)
            runtime.setFloatUniform("uMaskLight", tv.crtMaskLight)
            runtime.setFloatUniform("uBloom", tv.crtBloom)
            android.graphics.RenderEffect
                .createRuntimeShaderEffect(runtime, "content")
                .asComposeRenderEffect()
        }
    }

private fun compileTvShader(): RuntimeShader? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return runCatching { RuntimeShader(TV_SHADER) }
        .onFailure { Log.w("GlyphTvShader", "TV shader did not compile; drawing the screen plain", it) }
        .getOrNull()
}

/**
 * All five in one program, chosen by `uMode`. Each mode is the original's
 * arithmetic, with these changes for a layer on a phone: alpha is passed
 * through (the original also halves it, which a game window never shows but a
 * layer would); every grid is `uPixel` whole phone pixels a cell; a TV pixel
 * the layer's edge cuts is read inside the layer rather than as black; and
 * the sliders' `uStrength`, `uBright` and the CRT's constants are uniforms.
 */
private const val TV_SHADER = """
uniform shader content;
uniform float2 uSize;
uniform float uPixel;
uniform float uMode;
uniform float uStrength;
uniform float uBright;
uniform float uScan;
uniform float uSharp;
uniform float uMaskDark;
uniform float uMaskLight;
uniform float uBloom;

// ── the masks: a channel keeps its light, the others keep half ─────────
float3 maskOf(float i) {
    float3 m = float3(1.0, 1.0, 1.0);
    if (i < 0.5) { m = float3(1.0, 0.0, 0.0); }
    else if (i < 1.5) { m = float3(0.0, 1.0, 0.0); }
    else if (i < 2.5) { m = float3(0.0, 0.0, 1.0); }
    return clamp(m + 0.5, 0.0, 1.0);
}

// ── CRT ────────────────────────────────────────────────────────────────
// uScan, uSharp, uMaskDark, uMaskLight and uBloom are the original's
// ScanBrightness, FilterCRTAmount, MaskAmountDark, MaskAmountLight and
// BloomAmount, set by the sliders.
const float BloomPixel = -1.5;
const float BloomScanLine = -2.0;
const float Shape = 2.0;

float toLinear1(float c) { return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4); }
float3 toLinear(float3 c) { return float3(toLinear1(c.r), toLinear1(c.g), toLinear1(c.b)); }
float toSrgb1(float c) { return c < 0.0031308 ? c * 12.92 : 1.055 * pow(c, 0.41666) - 0.055; }
float3 toSrgb(float3 c) { return float3(toSrgb1(c.r), toSrgb1(c.g), toSrgb1(c.b)); }

// The TV pixel `off` away from the one under `g` (in TV pixels), read at its
// middle. One the layer's edge cuts in two is read just inside the edge, and
// past the edge the edge's own pixels are read again: a TV pixel there is
// never black, so the picture has no dark rim.
float3 fetch(float2 g, float2 off) {
    float2 cell = floor(g + off) + float2(0.5, 0.5);
    float2 at = clamp(cell * uPixel, float2(0.5, 0.5), uSize - float2(0.5, 0.5));
    return toLinear(float3(content.eval(at).rgb));
}

float2 dist(float2 g) { return -((g - floor(g)) - float2(0.5, 0.5)); }

float gaus(float pos, float scale) { return exp2(scale * pow(abs(pos), Shape)); }

float3 horz3(float2 g, float off) {
    float3 b = fetch(g, float2(-1.0, off));
    float3 c = fetch(g, float2(0.0, off));
    float3 d = fetch(g, float2(1.0, off));
    float dst = dist(g).x;
    float wb = gaus(dst - 1.0, uSharp);
    float wc = gaus(dst + 0.0, uSharp);
    float wd = gaus(dst + 1.0, uSharp);
    return (b * wb + c * wc + d * wd) / (wb + wc + wd);
}

float3 horz5(float2 g, float off) {
    float3 a = fetch(g, float2(-2.0, off));
    float3 b = fetch(g, float2(-1.0, off));
    float3 c = fetch(g, float2(0.0, off));
    float3 d = fetch(g, float2(1.0, off));
    float3 e = fetch(g, float2(2.0, off));
    float dst = dist(g).x;
    float wa = gaus(dst - 2.0, uSharp);
    float wb = gaus(dst - 1.0, uSharp);
    float wc = gaus(dst + 0.0, uSharp);
    float wd = gaus(dst + 1.0, uSharp);
    float we = gaus(dst + 2.0, uSharp);
    return (a * wa + b * wb + c * wc + d * wd + e * we) / (wa + wb + wc + wd + we);
}

float3 horz7(float2 g, float off) {
    float3 a = fetch(g, float2(-3.0, off));
    float3 b = fetch(g, float2(-2.0, off));
    float3 c = fetch(g, float2(-1.0, off));
    float3 d = fetch(g, float2(0.0, off));
    float3 e = fetch(g, float2(1.0, off));
    float3 f = fetch(g, float2(2.0, off));
    float3 h = fetch(g, float2(3.0, off));
    float dst = dist(g).x;
    float wa = gaus(dst - 3.0, BloomPixel);
    float wb = gaus(dst - 2.0, BloomPixel);
    float wc = gaus(dst - 1.0, BloomPixel);
    float wd = gaus(dst + 0.0, BloomPixel);
    float we = gaus(dst + 1.0, BloomPixel);
    float wf = gaus(dst + 2.0, BloomPixel);
    float wh = gaus(dst + 3.0, BloomPixel);
    return (a * wa + b * wb + c * wc + d * wd + e * we + f * wf + h * wh) / (wa + wb + wc + wd + we + wf + wh);
}

float scan(float2 g, float off) { return gaus(dist(g).y + off, uScan); }
float bloomScan(float2 g, float off) { return gaus(dist(g).y + off, BloomScanLine); }

float3 tri(float2 g) {
    float3 a = horz3(g, -1.0);
    float3 b = horz5(g, 0.0);
    float3 c = horz3(g, 1.0);
    return a * scan(g, -1.0) + b * scan(g, 0.0) + c * scan(g, 1.0);
}

float3 bloom(float2 g) {
    float3 a = horz5(g, -2.0);
    float3 b = horz7(g, -1.0);
    float3 c = horz7(g, 0.0);
    float3 d = horz7(g, 1.0);
    float3 e = horz5(g, 2.0);
    return a * bloomScan(g, -2.0) + b * bloomScan(g, -1.0) + c * bloomScan(g, 0.0)
        + d * bloomScan(g, 1.0) + e * bloomScan(g, 2.0);
}

// VGA-style shadow mask, its stripes two of `pos`'s units wide.
float3 shadowMask(float2 pos) {
    pos = floor(pos * float2(1.0, 0.5));
    pos.x += pos.y * 3.0;
    float3 mask = float3(uMaskDark, uMaskDark, uMaskDark);
    pos.x = fract(pos.x / 6.0);
    if (pos.x < 0.333) { mask.r = uMaskLight; }
    else if (pos.x < 0.666) { mask.g = uMaskLight; }
    else { mask.b = uMaskLight; }
    return mask;
}

half4 main(float2 fragCoord) {
    float4 here = float4(content.eval(fragCoord));
    float2 p = floor(fragCoord / uPixel);
    float3 tv;

    if (uMode < 1.5) {
        // Scanlines: every other line at half.
        float i = mod(p.y, 2.0);
        tv = here.rgb * (i < 0.5 ? 1.0 : 0.5) * uBright;
    } else if (uMode < 2.5) {
        // Diagonal: red, green, blue, stepping a pixel a row.
        float i = mod(p.x + mod(p.y, 3.0), 3.0);
        tv = here.rgb * maskOf(i) * uBright;
    } else if (uMode < 3.5) {
        // Triangular: two-pixel columns, shifted every second pair of rows.
        float i = mod(floor((p.x + mod(floor(p.y / 2.0), 2.0) * 3.0) / 2.0), 3.0);
        tv = here.rgb * maskOf(i) * uBright;
    } else if (uMode < 4.5) {
        // Wave: lines uPixel rows tall, each brightest at its middle. A line
        // is the mean of its rows: the original's picture has one row per
        // line, so it reads one; read here at one row of several, anything
        // thinner than a line would blink as it moved.
        float top = p.y * uPixel;
        float factor = 0.9 - 0.4 * cos(6.28318530718 * (fragCoord.y - top) / uPixel);
        float3 sum = float3(0.0, 0.0, 0.0);
        float rows = 0.0;
        for (int k = 0; k < 12; k++) {
            if (float(k) < uPixel) {
                float y = min(top + float(k) + 0.5, uSize.y - 0.5);
                sum += float3(content.eval(float2(fragCoord.x, y)).rgb);
                rows += 1.0;
            }
        }
        tv = sum / max(rows, 1.0) * factor * uBright;
    } else {
        // CRT, on a grid of TV pixels uPixel phone pixels square. The mask's
        // stripes are about a TV pixel wide, and whole phone pixels.
        float2 g = fragCoord / uPixel;
        float3 colour = tri(g) + bloom(g) * uBloom;
        colour *= shadowMask(fragCoord / max(1.0, floor(uPixel / 2.0)));
        tv = toSrgb(clamp(colour * uBright, 0.0, 1.0));
    }
    return half4(mix(here.rgb, tv, uStrength), here.a);
}
"""

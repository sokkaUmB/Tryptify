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
import kotlin.math.roundToInt
import tf.monochrome.android.glyph.engine.GlyphTvShader
import tf.monochrome.android.performance.LocalPerformanceProfile

/**
 * The game screen through an old TV: one of the [GlyphTvShader]s, drawn over
 * everything the layer holds.
 *
 * Android 13 and later, where a layer can run a shader. Off where the app
 * turns its blur off — a low-end phone, or the "Remove liquid glass" switch —
 * since this is a full-screen pass every frame. A shader the phone's GPU
 * driver will not compile turns the effect off rather than failing the run.
 *
 * The masks are drawn in "screen pixels" the size of a desktop monitor's,
 * about a hundredth of an inch: drawn in a phone's own pixels, four or five
 * times finer, a one-pixel scanline would vanish. The CRT still reads the
 * picture at every one of the phone's pixels, as the original reads every
 * output pixel.
 *
 * The line grid is the original's 640 x 480 over the window. A layer smaller
 * than the window (Training Ground's playfield, the options' preview) gets the
 * same share of it, so its lines are the game's lines, not finer ones that
 * shimmer.
 */
fun Modifier.glyphTvShader(shader: GlyphTvShader): Modifier =
    if (shader == GlyphTvShader.OFF || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        this
    } else {
        composed {
            val runtime = remember { compileTvShader() }
            val size = remember { mutableStateOf(IntSize.Zero) }
            val pixel = (LocalDensity.current.density * MONITOR_PIXEL_DP).roundToInt().coerceAtLeast(1).toFloat()
            val window = LocalWindowInfo.current.containerSize
            if (runtime == null || !LocalPerformanceProfile.current.allowHazeBlur) {
                Modifier
            } else {
                Modifier.onSizeChanged { size.value = it }.tvLayer(runtime, shader.mode, pixel, window, size)
            }
        }
    }

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Modifier.tvLayer(
    runtime: RuntimeShader,
    mode: Int,
    pixel: Float,
    window: IntSize,
    size: MutableState<IntSize>,
): Modifier =
    graphicsLayer {
        // Read through state, so a rotation re-runs this with the new size.
        val now = size.value
        renderEffect = if (now.width == 0 || now.height == 0) {
            null
        } else {
            // The layer's share of the window's 640 x 480; the whole grid when
            // the layer is the window, or when the window is not known.
            val shareX = if (window.width > 0) (now.width.toFloat() / window.width).coerceAtMost(1f) else 1f
            val shareY = if (window.height > 0) (now.height.toFloat() / window.height).coerceAtMost(1f) else 1f
            runtime.setFloatUniform("uSize", now.width.toFloat(), now.height.toFloat())
            runtime.setFloatUniform("uPixel", pixel)
            runtime.setFloatUniform("uGrid", GRID_COLUMNS * shareX, GRID_LINES * shareY)
            runtime.setFloatUniform("uMode", mode.toFloat())
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

/** The original's line grid over a whole screen. */
private const val GRID_COLUMNS = 640f
private const val GRID_LINES = 480f

/** A desktop monitor's pixel, in dp: a hundredth of an inch is 1.6dp. */
private const val MONITOR_PIXEL_DP = 1.6f

/**
 * All five in one program, chosen by `uMode`. Each mode is the original's
 * arithmetic, with three changes for a layer on a phone: alpha is passed
 * through (the original also halves it, which a game window never shows but
 * a layer would); the masks count [MONITOR_PIXEL_DP] screen pixels; and the
 * line grid is `uGrid`, the original's 640 x 480 scaled to the layer.
 */
private const val TV_SHADER = """
uniform shader content;
uniform float2 uSize;
uniform float uPixel;
uniform float2 uGrid;
uniform float uMode;

// ── the masks: a channel keeps its light, the others keep half ─────────
float3 maskOf(float i) {
    float3 m = float3(1.0, 1.0, 1.0);
    if (i < 0.5) { m = float3(1.0, 0.0, 0.0); }
    else if (i < 1.5) { m = float3(0.0, 1.0, 0.0); }
    else if (i < 2.5) { m = float3(0.0, 0.0, 1.0); }
    return clamp(m + 0.5, 0.0, 1.0);
}

// ── CRT ────────────────────────────────────────────────────────────────
const float ScanBrightness = -8.0;
const float FilterCRTAmount = -3.0;
const float MaskAmountDark = 0.5;
const float MaskAmountLight = 1.5;
const float BloomPixel = -1.5;
const float BloomScanLine = -2.0;
const float BloomAmount = 0.15;
const float Shape = 2.0;

float toLinear1(float c) { return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4); }
float3 toLinear(float3 c) { return float3(toLinear1(c.r), toLinear1(c.g), toLinear1(c.b)); }
float toSrgb1(float c) { return c < 0.0031308 ? c * 12.92 : 1.055 * pow(c, 0.41666) - 0.055; }
float3 toSrgb(float3 c) { return float3(toSrgb1(c.r), toSrgb1(c.g), toSrgb1(c.b)); }

// Every output pixel, as the original's u_target_size.
float3 fetch(float2 pos, float2 off) {
    float2 target = uSize;
    pos = (floor(pos * target + off) + float2(0.5, 0.5)) / target;
    if (max(abs(pos.x - 0.5), abs(pos.y - 0.5)) > 0.5) { return float3(0.0, 0.0, 0.0); }
    return toLinear(float3(content.eval(pos * uSize).rgb));
}

float2 dist(float2 pos) {
    pos = pos * uGrid;
    return -((pos - floor(pos)) - float2(0.5, 0.5));
}

float gaus(float pos, float scale) { return exp2(scale * pow(abs(pos), Shape)); }

float3 horz3(float2 pos, float off) {
    float3 b = fetch(pos, float2(-1.0, off));
    float3 c = fetch(pos, float2(0.0, off));
    float3 d = fetch(pos, float2(1.0, off));
    float dst = dist(pos).x;
    float wb = gaus(dst - 1.0, FilterCRTAmount);
    float wc = gaus(dst + 0.0, FilterCRTAmount);
    float wd = gaus(dst + 1.0, FilterCRTAmount);
    return (b * wb + c * wc + d * wd) / (wb + wc + wd);
}

float3 horz5(float2 pos, float off) {
    float3 a = fetch(pos, float2(-2.0, off));
    float3 b = fetch(pos, float2(-1.0, off));
    float3 c = fetch(pos, float2(0.0, off));
    float3 d = fetch(pos, float2(1.0, off));
    float3 e = fetch(pos, float2(2.0, off));
    float dst = dist(pos).x;
    float wa = gaus(dst - 2.0, FilterCRTAmount);
    float wb = gaus(dst - 1.0, FilterCRTAmount);
    float wc = gaus(dst + 0.0, FilterCRTAmount);
    float wd = gaus(dst + 1.0, FilterCRTAmount);
    float we = gaus(dst + 2.0, FilterCRTAmount);
    return (a * wa + b * wb + c * wc + d * wd + e * we) / (wa + wb + wc + wd + we);
}

float3 horz7(float2 pos, float off) {
    float3 a = fetch(pos, float2(-3.0, off));
    float3 b = fetch(pos, float2(-2.0, off));
    float3 c = fetch(pos, float2(-1.0, off));
    float3 d = fetch(pos, float2(0.0, off));
    float3 e = fetch(pos, float2(1.0, off));
    float3 f = fetch(pos, float2(2.0, off));
    float3 g = fetch(pos, float2(3.0, off));
    float dst = dist(pos).x;
    float wa = gaus(dst - 3.0, BloomPixel);
    float wb = gaus(dst - 2.0, BloomPixel);
    float wc = gaus(dst - 1.0, BloomPixel);
    float wd = gaus(dst + 0.0, BloomPixel);
    float we = gaus(dst + 1.0, BloomPixel);
    float wf = gaus(dst + 2.0, BloomPixel);
    float wg = gaus(dst + 3.0, BloomPixel);
    return (a * wa + b * wb + c * wc + d * wd + e * we + f * wf + g * wg) / (wa + wb + wc + wd + we + wf + wg);
}

float scan(float2 pos, float off) { return gaus(dist(pos).y + off, ScanBrightness); }
float bloomScan(float2 pos, float off) { return gaus(dist(pos).y + off, BloomScanLine); }

float3 tri(float2 pos) {
    float3 a = horz3(pos, -1.0);
    float3 b = horz5(pos, 0.0);
    float3 c = horz3(pos, 1.0);
    return a * scan(pos, -1.0) + b * scan(pos, 0.0) + c * scan(pos, 1.0);
}

float3 bloom(float2 pos) {
    float3 a = horz5(pos, -2.0);
    float3 b = horz7(pos, -1.0);
    float3 c = horz7(pos, 0.0);
    float3 d = horz7(pos, 1.0);
    float3 e = horz5(pos, 2.0);
    return a * bloomScan(pos, -2.0) + b * bloomScan(pos, -1.0) + c * bloomScan(pos, 0.0)
        + d * bloomScan(pos, 1.0) + e * bloomScan(pos, 2.0);
}

// VGA-style shadow mask.
float3 shadowMask(float2 pos) {
    pos = floor(pos * float2(1.0, 0.5));
    pos.x += pos.y * 3.0;
    float3 mask = float3(MaskAmountDark, MaskAmountDark, MaskAmountDark);
    pos.x = fract(pos.x / 6.0);
    if (pos.x < 0.333) { mask.r = MaskAmountLight; }
    else if (pos.x < 0.666) { mask.g = MaskAmountLight; }
    else { mask.b = MaskAmountLight; }
    return mask;
}

half4 main(float2 fragCoord) {
    float4 here = float4(content.eval(fragCoord));
    float2 p = floor(fragCoord / uPixel);

    if (uMode < 1.5) {
        // Scanlines: every other line at half.
        float i = mod(p.y, 2.0);
        return half4(here.rgb * (i < 0.5 ? 1.0 : 0.5), here.a);
    }
    if (uMode < 2.5) {
        // Diagonal: red, green, blue, stepping a pixel a row.
        float i = mod(p.x + mod(p.y, 3.0), 3.0);
        return half4(here.rgb * maskOf(i), here.a);
    }
    if (uMode < 3.5) {
        // Triangular: two-pixel columns, shifted every second pair of rows.
        float i = mod(floor((p.x + mod(floor(p.y / 2.0), 2.0) * 3.0) / 2.0), 3.0);
        return half4(here.rgb * maskOf(i), here.a);
    }
    if (uMode < 4.5) {
        // Wave: the picture in uGrid.y lines, each brightest at its middle.
        // A line is the mean of every screen row whose centre it covers: the
        // original's picture has one row per line, so it reads one; read here
        // at one row in five, anything thinner than a line would blink as it
        // moved. Eight rows covers a line on the tallest tablet.
        float2 uv = fragCoord / uSize;
        float factor = 0.9 - 0.4 * cos(6.28318530718 * uv.y * uGrid.y);
        float line = floor(uv.y * uGrid.y);
        float top = line / uGrid.y * uSize.y;
        float bottom = (line + 1.0) / uGrid.y * uSize.y;
        float first = floor(top);
        float4 sum = float4(0.0, 0.0, 0.0, 0.0);
        float rows = 0.0;
        for (int k = 0; k < 8; k++) {
            float centre = first + float(k) + 0.5;
            if (centre >= top && centre < bottom) {
                sum += float4(content.eval(float2(fragCoord.x, centre)));
                rows += 1.0;
            }
        }
        sum = sum / max(rows, 1.0);
        return half4(sum.rgb * factor, sum.a);
    }
    // CRT.
    float2 pos = fragCoord / uSize;
    float3 colour = tri(pos) + bloom(pos) * BloomAmount;
    colour *= shadowMask(fragCoord / uPixel);
    return half4(toSrgb(colour), here.a);
}
"""

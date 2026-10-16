// SPDX-License-Identifier: GPL-3.0-or-later
//
// The shader programs below are ported from PCSX2's presentation shaders
// (PCSX2 Dev Team, GPL-3.0-or-later; the CRT program is Timothy Lottes'
// public-domain CRT shader as PCSX2 configures it), and from crt-royale
// (Copyright (C) 2014 TroggleMonkey, GPL-2.0-or-later; its border uses an
// algorithm from cgwg's crt-geom, GPL), folded from its passes into one.

package tf.monochrome.android.ui.glyph

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
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
fun Modifier.glyphTvShader(graphics: GlyphGraphics): Modifier =
    if (graphics.tvShader == GlyphTvShader.OFF) {
        this
    } else {
        // Under the app-wide effect the game is already seen through a TV:
        // a second one on top would draw every scanline twice.
        this.composed { if (LocalAppTvShader.current) Modifier else Modifier.tvShader(graphics.tvShader, graphics.tv) }
    }

/**
 * Whether the whole app is drawn through a TV (Settings › Appearance ›
 * CRT effect, [appTvShaderRuns]), so a screen does not add its own on top.
 */
val LocalAppTvShader = staticCompositionLocalOf { false }

/**
 * The app-wide effect's sliders while one is being dragged (Settings ›
 * Appearance › CRT effect): read by the root layer as it draws, so the whole
 * app changes under the finger, and written to the store once, on release.
 * Null when nothing is being dragged; the root clears it when the store has
 * caught up.
 */
object AppTvLive {
    var tuning: GlyphTvTuning? by mutableStateOf(null)
}

/** Whether [shader] drawn over the whole app runs here: on, Android 13 or later, and blur allowed. */
@Composable
fun appTvShaderRuns(shader: GlyphTvShader): Boolean =
    shader != GlyphTvShader.OFF && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        LocalPerformanceProfile.current.allowHazeBlur

/**
 * Any layer through [shader] at [tv]'s settings: the game's playfield, or
 * the whole app from its root. Nothing below Android 13, where the shader off
 * on a phone that turns its blur off, or one whose GPU will not compile it.
 */
fun Modifier.tvShader(shader: GlyphTvShader, tv: GlyphTvTuning = GlyphTvTuning()): Modifier = tvShader(shader) { tv }

/**
 * Settings › Appearance › CRT effect drawn over the lyrics only: its look and
 * its sliders. Null when it is off, or drawn over the whole app.
 */
class LyricsTv(val shader: GlyphTvShader, val tuning: () -> GlyphTvTuning)

val LocalLyricsTv = compositionLocalOf<LyricsTv?> { null }

/**
 * The lyric surface through the CRT effect, when it is set to the lyrics
 * ([LocalLyricsTv]). A bowed tube's corners are clear here, not black: the
 * player shows through around the lyrics.
 */
fun Modifier.lyricsTv(): Modifier = composed {
    val tv = LocalLyricsTv.current
    if (tv == null) Modifier else Modifier.tvShader(tv.shader, clearRim = true, tv = tv.tuning)
}

/**
 * [tvShader] with its settings read as the layer draws, so a slider being
 * dragged ([AppTvLive]) moves the picture without recomposing what is in it.
 *
 * Chromatic shift and Curvature are a second pass over the first one's
 * picture, the glass in front of the pattern: the scanlines and the mask bow
 * with the tube, as they do on one. It runs only while either is above 0.
 */
fun Modifier.tvShader(shader: GlyphTvShader, clearRim: Boolean = false, tv: () -> GlyphTvTuning): Modifier {
    return if (shader == GlyphTvShader.OFF || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        this
    } else {
        this.composed {
            val royale = shader == GlyphTvShader.ROYALE
            val runtime = remember(royale) { if (royale) compileRoyaleShader() else compileTvShader() }
            val tube = remember { compileTubeShader() }
            val size = remember { mutableStateOf(IntSize.Zero) }
            val windowHeight = LocalWindowInfo.current.containerSize.height
            if (runtime == null || !LocalPerformanceProfile.current.allowHazeBlur) {
                Modifier
            } else {
                Modifier.onSizeChanged { size.value = it }.tvLayer(runtime, tube, shader, tv, windowHeight, size, clearRim)
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Modifier.tvLayer(
    runtime: RuntimeShader,
    tube: RuntimeShader?,
    shader: GlyphTvShader,
    tv: () -> GlyphTvTuning,
    windowHeight: Int,
    size: MutableState<IntSize>,
    clearRim: Boolean,
): Modifier =
    graphicsLayer {
        // Read through state, so a rotation re-runs this with the new size,
        // and a slider's move with the new value.
        val now = size.value
        val tuning = tv()
        renderEffect = if (now.width == 0 || now.height == 0) {
            null
        } else {
            val width = now.width.toFloat()
            val height = now.height.toFloat()
            runtime.setFloatUniform("uSize", width, height)
            runtime.setFloatUniform("uPixel", tuning.pixelSize(shader, density, windowHeight).toFloat())
            runtime.setFloatUniform("uStrength", tuning.strength.coerceIn(0f, 1f))
            runtime.setFloatUniform("uBright", tuning.brightness.coerceIn(GlyphTvTuning.MIN_BRIGHTNESS, GlyphTvTuning.MAX_BRIGHTNESS))
            if (shader == GlyphTvShader.ROYALE) {
                runtime.setFloatUniform("uMaskType", tuning.royaleMask.toFloat())
                runtime.setFloatUniform("uTriad", tuning.royaleTriadPx(density).toFloat())
                runtime.setFloatUniform("uMaskStrength", tuning.royaleMaskStrength)
                runtime.setFloatUniform("uMaxSigma", tuning.royaleMaxSigma)
                runtime.setFloatUniform("uMaxShape", tuning.royaleMaxShape)
                runtime.setFloatUniform("uHalation", tuning.royaleHalationWeight)
                runtime.setFloatUniform("uDiffusion", tuning.royaleDiffusionWeight)
                runtime.setFloatUniform("uBloom", tuning.royaleBloom)
                runtime.setFloatUniform("uBorder", tuning.royaleBorderSize)
            } else {
                runtime.setFloatUniform("uMode", shader.mode.toFloat())
                runtime.setFloatUniform("uScan", tuning.crtScan)
                runtime.setFloatUniform("uSharp", tuning.crtSharpness)
                runtime.setFloatUniform("uMaskDark", tuning.crtMaskDark)
                runtime.setFloatUniform("uMaskLight", tuning.crtMaskLight)
                runtime.setFloatUniform("uBloom", tuning.crtBloom)
            }
            val pattern = RenderEffect.createRuntimeShaderEffect(runtime, "content")
            if (tube == null || !tuning.hasTube) {
                pattern.asComposeRenderEffect()
            } else {
                tube.setFloatUniform("uSize", width, height)
                tube.setFloatUniform("uWarp", tuning.warpX, tuning.warpY)
                tube.setFloatUniform("uChroma", tuning.chromaticPx(density))
                // A flat picture keeps its edge pixels; a bowed one softens
                // its rim over a dp, rather than stepping into the dark.
                tube.setFloatUniform("uEdge", if (tuning.curved) TUBE_EDGE_DP * density else 0f)
                tube.setFloatUniform("uRim", if (clearRim) 0f else 1f)
                RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(tube, "content"), pattern)
                    .asComposeRenderEffect()
            }
        }
    }

/**
 * Whether this phone draws the tube over [shader]: the look's program and the
 * tube's both compile on its GPU. Asked once each, apart from any layer's own
 * copies. Taps are bent only where the picture is, never over a flat one.
 */
fun tubeDrawable(shader: GlyphTvShader): Boolean =
    tubeCompiles && if (shader == GlyphTvShader.ROYALE) royaleCompiles else tvCompiles

private val tvCompiles: Boolean by lazy { compileTvShader() != null }
private val royaleCompiles: Boolean by lazy { compileRoyaleShader() != null }
private val tubeCompiles: Boolean by lazy { compileTubeShader() != null }

/** The bowed tube's rim, softened over this much. */
private const val TUBE_EDGE_DP = 1f

private fun compileTvShader(): RuntimeShader? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return runCatching { RuntimeShader(TV_SHADER) }
        .onFailure { Log.w("GlyphTvShader", "TV shader did not compile; drawing the screen plain", it) }
        .getOrNull()
}

private fun compileRoyaleShader(): RuntimeShader? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return runCatching { RuntimeShader(ROYALE_SHADER) }
        .onFailure { Log.w("GlyphTvShader", "CRT-Royale did not compile; drawing the screen plain", it) }
        .getOrNull()
}

/** The glass pass; null leaves the picture flat and in line, with the pattern still on it. */
private fun compileTubeShader(): RuntimeShader? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return runCatching { RuntimeShader(TUBE_SHADER) }
        .onFailure { Log.w("GlyphTvShader", "tube shader did not compile; drawing the picture flat", it) }
        .getOrNull()
}

/**
 * [event] as the layer under a bowed tube should get it: each point (and each
 * point in its history) moved to the spot on the flat picture that is seen
 * there ([GlyphTvTuning.tubeSource]), in a layer at [left], [top] of [width]
 * by [height] in the event's coordinates. A new event, for the caller to
 * recycle.
 */
fun bentThroughTube(
    event: MotionEvent,
    tuning: GlyphTvTuning,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
): MotionEvent {
    val count = event.pointerCount
    val history = event.historySize
    val properties = Array(count) { i -> MotionEvent.PointerProperties().also { event.getPointerProperties(i, it) } }
    val at = FloatArray(2)
    // pos -1 is the event's own point, the rest its history, oldest first.
    fun coords(pos: Int): Array<MotionEvent.PointerCoords> = Array(count) { i ->
        MotionEvent.PointerCoords().also { c ->
            if (pos < 0) event.getPointerCoords(i, c) else event.getHistoricalPointerCoords(i, pos, c)
            tuning.tubeSource(c.x - left, c.y - top, width, height, at)
            c.x = at[0] + left
            c.y = at[1] + top
        }
    }
    val bent = MotionEvent.obtain(
        event.downTime,
        if (history > 0) event.getHistoricalEventTime(0) else event.eventTime,
        event.action,
        count,
        properties,
        coords(if (history > 0) 0 else -1),
        event.metaState,
        event.buttonState,
        event.xPrecision,
        event.yPrecision,
        event.deviceId,
        event.edgeFlags,
        event.source,
        event.flags,
    )
    for (k in 1 until history) bent.addBatch(event.getHistoricalEventTime(k), coords(k), event.metaState)
    if (history > 0) bent.addBatch(event.eventTime, coords(-1), event.metaState)
    return bent
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
        // The bloom is thirty-one of the forty-two reads: none at 0 Glow.
        float3 colour = tri(g);
        if (uBloom > 0.0) {
            colour += bloom(g) * uBloom;
        }
        colour *= shadowMask(fragCoord / max(1.0, floor(uPixel / 2.0)));
        tv = toSrgb(clamp(colour * uBright, 0.0, 1.0));
    }
    return half4(mix(here.rgb, tv, uStrength), here.a);
}
"""

/**
 * The glass over the pattern: [GlyphTvTuning.tubeSource] for every pixel,
 * the CRT shader's own warp, so the picture bows outward and its corners go
 * dark past the tube; then red read from one side and blue from the other,
 * `uChroma` pixels apart from green. A read is held inside the picture, so a
 * flat one in line is exactly the picture. `uEdge` softens the bowed rim.
 */
private const val TUBE_SHADER = """
uniform shader content;
uniform float2 uSize;
uniform float2 uWarp;
uniform float uChroma;
uniform float uEdge;
uniform float uRim;

half4 main(float2 p) {
    float2 c = p / uSize * 2.0 - 1.0;
    c = c * float2(1.0 + c.y * c.y * uWarp.x, 1.0 + c.x * c.x * uWarp.y);
    float2 q = (c * 0.5 + 0.5) * uSize;
    float fade = 1.0;
    if (uEdge > 0.0) {
        float2 room = min(q, uSize - q);
        fade = clamp(min(room.x, room.y) / uEdge, 0.0, 1.0);
        if (fade <= 0.0) {
            return half4(0.0, 0.0, 0.0, uRim);
        }
    }
    float2 lo = float2(0.5, 0.5);
    float2 hi = uSize - lo;
    float4 here = float4(content.eval(clamp(q, lo, hi)));
    if (uChroma > 0.0) {
        float2 apart = float2(uChroma, 0.0);
        here.r = float4(content.eval(clamp(q - apart, lo, hi))).r;
        here.b = float4(content.eval(clamp(q + apart, lo, hi))).b;
    }
    return half4(here.rgb * fade, mix(uRim, here.a, fade));
}
"""

/**
 * CRT-Royale, its passes folded into one. Each output pixel:
 *
 * - The beams: the source read along the three nearest scanlines (`uPixel`
 *   apart; the one past the nearer neighbour too, as the original's three),
 *   each softened sideways by its `beam_horiz_sigma`, in the CRT's gamma 2.5.
 *   Each line lights the pixel by its sampled generalized Gaussian
 *   (`scanline_generalized_gaussian_sampled_contrib`, three samples a pixel
 *   on lines under 3 px, one on taller ones, where the others are under a
 *   third of a pixel away): wider (`beam_max_sigma`, uMaxSigma) and flatter-topped
 *   (`beam_max_shape`, uMaxShape) as its colour brightens, and normalised, so
 *   a line gives its whole colour's light between the gaps.
 * - Halation: a wide blur of the source (the original's halation blur, here
 *   a ring of six reads 4.5 lines out, read only while halation or diffusion
 *   is on) mixed into the beams as grey (uHalation).
 * - The phosphor mask, drawn rather than read from the original's
 *   textures: an aperture grille's stripes, a slot mask's staggered slots or
 *   a shadow mask's delta dots, `uTriad` pixels a triad, lit 1 and dark 0.06,
 *   amplified by its mean so the light is kept.
 * - The original's "fake bloom" blend: where the mask could not show all of
 *   an area's light (`bloom_underestimate_levels` 0.8), the pixel moves
 *   toward the unmasked light, so bright parts glow through the mask and
 *   dark ones show it; uBloom is `bloom_excess`.
 * - Diffusion: the halation blur over the whole (uDiffusion), the border's
 *   dark rim (`border_size` uBorder of the short side, darkness 2, compress
 *   2.5), and back out in the screen's gamma 2.2.
 *
 * The sideways reads are at the phone's own pixels: a CRT's line has no
 * pixels along it, and the app's letters stay sharp. Interlacing, the
 * original's own geometry and its anti-aliasing are left out: the tube pass
 * after this one bows and shifts it.
 */
private const val ROYALE_SHADER = """
uniform shader content;
uniform float2 uSize;
uniform float uPixel;
uniform float uStrength;
uniform float uBright;
uniform float uMaskType;
uniform float uTriad;
uniform float uMaskStrength;
uniform float uMaxSigma;
uniform float uMaxShape;
uniform float uHalation;
uniform float uDiffusion;
uniform float uBloom;
uniform float uBorder;

const float CRT_GAMMA = 2.5;
const float LCD_GAMMA = 2.2;
const float MIN_SIGMA = 0.02;
const float SPOT_POWER = 0.33333333;
const float MIN_SHAPE = 2.0;
const float HORIZ_SIGMA = 0.35;
const float UNDERESTIMATE = 0.8;
const float BORDER_DARKNESS = 2.0;
const float BORDER_COMPRESS = 2.5;
const float MASK_FLOOR = 0.06;
const float LIT_HALF = 0.36;
const float LIT_SOFT = 0.10;
const float SLOT_GAP = 0.18;
const float SLOT_SOFT = 0.06;
const float DOT_RADIUS = 0.42;
const float DOT_SOFT = 0.10;
const float GRILLE_MEAN = 0.2859;
const float SLOT_MEAN = 0.2452;
const float SHADOW_MEAN = 0.2622;

// The CRT's gamma, 2.5, as c * c * sqrt(c): a pow a read is the costliest
// line here, and this one runs fourteen times a pixel.
float3 lin(float2 at) {
    float2 q = clamp(at, float2(0.5, 0.5), uSize - float2(0.5, 0.5));
    float3 c = clamp(float3(content.eval(q).rgb), 0.0, 1.0);
    return c * c * sqrt(c);
}

// One scanline's colour under x, softened sideways by the beam's spread: two
// reads half its sigma either side, which a three-read 1:2:1 averaged too.
float3 lineColour(float x, float row) {
    float y = (row + 0.5) * uPixel;
    float h = 0.5 * HORIZ_SIGMA * uPixel;
    return (lin(float2(x - h, y)) + lin(float2(x + h, y))) * 0.5;
}

// The gamma function for s in (0, 1]: Gamma(1 + s) / s, Gamma(1 + s) by
// Abramowitz and Stegun's 6.1.35 (within 5e-5), no log or exp.
float3 gammaOf(float3 s) {
    float3 g = 1.0 + s * (-0.5748646 + s * (0.9512363 + s * (-0.6998588 + s * (0.4245549 + s * -0.1010678))));
    return g / s;
}

// A line's light at d lines from it, for its colour. Three samples a pixel,
// as the original, only where a line is under three pixels tall: taller, the
// two beside the middle one are under a third of a pixel off it.
float3 beamOf(float d, float3 colour, float ph) {
    float3 sigma = MIN_SIGMA + (uMaxSigma - MIN_SIGMA) * pow(colour, float3(SPOT_POWER));
    float3 alpha = 1.41421356 * sigma;
    float3 beta = MIN_SHAPE + max(uMaxShape - MIN_SHAPE, 0.0) * sqrt(sqrt(colour));
    float3 scale = colour * beta * 0.5 / alpha / gammaOf(1.0 / beta);
    float3 w = exp(-pow(float3(abs(d)) / alpha, beta));
    if (uPixel < 3.0) {
        float o = ph / 3.0;
        w = (w + exp(-pow(float3(abs(d + o)) / alpha, beta)) + exp(-pow(float3(abs(d - o)) / alpha, beta))) / 3.0;
    }
    return scale * w;
}

float3 grilleLit(float x) {
    float u = fract(x / uTriad) * 3.0;
    return float3(
        1.0 - smoothstep(LIT_HALF - LIT_SOFT, LIT_HALF + LIT_SOFT, abs(u - 0.5)),
        1.0 - smoothstep(LIT_HALF - LIT_SOFT, LIT_HALF + LIT_SOFT, abs(u - 1.5)),
        1.0 - smoothstep(LIT_HALF - LIT_SOFT, LIT_HALF + LIT_SOFT, abs(u - 2.5)));
}

float3 slotLit(float2 p) {
    float column = floor(p.x / uTriad);
    float v = fract(p.y / uTriad + 0.5 * mod(column, 2.0));
    float g = SLOT_GAP * 0.5;
    float rows = smoothstep(g - SLOT_SOFT, g + SLOT_SOFT, v) * (1.0 - smoothstep(1.0 - g - SLOT_SOFT, 1.0 - g + SLOT_SOFT, v));
    return grilleLit(p.x) * rows;
}

float3 shadowLit(float2 p) {
    float d = uTriad * 0.5;
    float h = d * 0.8660254;
    float best = 1000000.0;
    float colour = 0.0;
    float r0 = floor(p.y / h);
    for (int dr = 0; dr < 2; dr++) {
        float r = r0 + float(dr);
        float off = 0.5 * mod(r, 2.0);
        float i0 = floor(p.x / d - off + 0.5);
        for (int di = -1; di < 2; di++) {
            float i = i0 + float(di);
            float gap = length(float2(p.x - (i + off) * d, p.y - r * h)) / d;
            if (gap < best) {
                best = gap;
                colour = mod(i + 2.0 * mod(r, 2.0), 3.0);
            }
        }
    }
    float spot = 1.0 - smoothstep(DOT_RADIUS - DOT_SOFT, DOT_RADIUS + DOT_SOFT, best);
    return float3(
        colour < 0.5 ? spot : 0.0,
        (colour > 0.5 && colour < 1.5) ? spot : 0.0,
        colour > 1.5 ? spot : 0.0);
}

half4 main(float2 p) {
    float4 here = float4(content.eval(p));
    float ph = 1.0 / uPixel;
    float g = p.y / uPixel - 0.5;
    float l2 = floor(g);
    float dist = g - l2;
    bool below = dist >= 0.5;
    float3 c2 = lineColour(p.x, l2);
    float3 c3 = lineColour(p.x, l2 + 1.0);
    float3 cOut = lineColour(p.x, below ? l2 + 2.0 : l2 - 1.0);
    float dOut = below ? 2.0 - dist : dist + 1.0;
    float3 beam = beamOf(dist, c2, ph) + beamOf(1.0 - dist, c3, ph) + beamOf(dOut, cOut, ph);

    // The glass's blur: one ring of six around the pixel, 4.5 lines out,
    // read only when halation or diffusion asks for it.
    float3 halo = mix(c2, c3, dist);
    if (uHalation > 0.0 || uDiffusion > 0.0) {
        float r = 4.5 * uPixel;
        float3 ring = lin(p + r * float2(1.0, 0.0)) + lin(p + r * float2(-1.0, 0.0))
            + lin(p + r * float2(0.5, 0.8660254)) + lin(p + r * float2(-0.5, 0.8660254))
            + lin(p + r * float2(0.5, -0.8660254)) + lin(p + r * float2(-0.5, -0.8660254));
        halo = (halo + ring) / 7.0;
    }
    float3 electron = mix(beam, float3(dot(halo, float3(0.33333333))), uHalation);

    float3 lit = float3(0.0);
    float maskMean = SLOT_MEAN;
    if (uMaskType < 0.5) {
        lit = grilleLit(p.x);
        maskMean = GRILLE_MEAN;
    } else if (uMaskType < 1.5) {
        lit = slotLit(p);
        maskMean = SLOT_MEAN;
    } else {
        lit = shadowLit(p);
        maskMean = SHADOW_MEAN;
    }
    float3 phosphor = mix(float3(1.0), MASK_FLOOR + (1.0 - MASK_FLOOR) * lit, uMaskStrength);
    float amplify = 1.0 / max(mix(1.0, maskMean, uMaskStrength), 0.05);
    float3 emission = electron * phosphor * amplify;

    float3 approx = mix(mix(c2, c3, dist), electron, 0.1) * 1.05;
    float3 under = approx * UNDERESTIMATE;
    float3 areaMax = under * amplify;
    float3 blend = clamp((areaMax - 1.0) / max(areaMax - under, float3(0.00001)), 0.0, 1.0);
    blend = mix(blend, float3(1.0), uBloom);
    float3 colour = mix(mix(emission, approx, blend), halo, uDiffusion);

    if (uBorder > 0.0) {
        float2 edge = min(p, uSize - p) / min(uSize.x, uSize.y);
        float2 inside = max(float2(uBorder) - edge, float2(0.0));
        float escape = max(1.0 - length(inside) / uBorder, 0.0);
        colour = colour * min(pow(escape, BORDER_DARKNESS) * BORDER_COMPRESS, 1.0);
    }
    float3 tv = pow(clamp(colour * uBright, 0.0, 1.0), float3(1.0 / LCD_GAMMA));
    return half4(mix(here.rgb, tv, uStrength), here.a);
}
"""

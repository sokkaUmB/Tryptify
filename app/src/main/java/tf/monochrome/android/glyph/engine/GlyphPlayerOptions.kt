// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.chart.GlyphChart
import kotlin.math.roundToInt

/**
 * StepMania's "Turn" options: one lane permutation for the whole chart.
 *
 * Left and Right turn the pad a quarter: under Left every Up becomes a Left,
 * every Left a Down, every Down a Right and every Right an Up, as if the
 * arrows had been rotated a quarter turn counter-clockwise. Right is the
 * other way. Shuffle is a permutation seeded by the chart, so the same chart
 * shuffles the same way every time and can be learned.
 */
enum class GlyphTurn(val id: String, val label: String) {
    OFF("off", "Off"),
    MIRROR("mirror", "Mirror"),
    LEFT("left", "Left"),
    RIGHT("right", "Right"),
    SHUFFLE("shuffle", "Shuffle"),
    ;

    /**
     * Where a note written in [lane] lands. [shuffleSeed] seeds Shuffle and
     * is ignored by the rest.
     */
    fun map(lane: GlyphLane, shuffleSeed: Long = 0L): GlyphLane = when (this) {
        OFF -> lane
        MIRROR -> GlyphLane.mirrorOf(lane)
        LEFT -> when (lane) {
            GlyphLane.UP -> GlyphLane.LEFT
            GlyphLane.LEFT -> GlyphLane.DOWN
            GlyphLane.DOWN -> GlyphLane.RIGHT
            GlyphLane.RIGHT -> GlyphLane.UP
        }
        RIGHT -> when (lane) {
            GlyphLane.UP -> GlyphLane.RIGHT
            GlyphLane.RIGHT -> GlyphLane.DOWN
            GlyphLane.DOWN -> GlyphLane.LEFT
            GlyphLane.LEFT -> GlyphLane.UP
        }
        SHUFFLE -> shuffleOrder(shuffleSeed)[lane.ordinal]
    }

    companion object {
        fun fromId(id: String?): GlyphTurn = entries.firstOrNull { it.id == id } ?: OFF

        /** One permutation of the lanes, the same for the same seed. */
        fun shuffleOrder(seed: Long): List<GlyphLane> {
            val order = GlyphLane.entries.toMutableList()
            val random = java.util.Random(seed)
            for (index in order.indices.reversed()) {
                val swap = random.nextInt(index + 1)
                order[index] = order[swap].also { order[swap] = order[index] }
            }
            return order
        }
    }
}

/**
 * [this] chart with [turn] applied to every note, once, as a chart transform:
 * a note that moved lane between scoring and drawing would be a note nobody
 * could hit. Shuffle is seeded by the chart's name, so it is learnable.
 */
fun GlyphChart.withTurn(turn: GlyphTurn): GlyphChart {
    if (turn == GlyphTurn.OFF) return this
    val seed = chartName.hashCode().toLong()
    return copy(notes = notes.map { it.copy(lane = turn.map(it.lane, seed)) })
}

/**
 * StepMania's "Appearance" options: where on the way down a note can be seen.
 *
 * Hidden fades notes out as they near the receptors, Sudden only shows them
 * once they are close, and Stealth hides them entirely. Looks only; the notes
 * are judged exactly as before.
 */
enum class GlyphAppearance(val id: String, val label: String) {
    VISIBLE("visible", "Visible"),
    HIDDEN("hidden", "Hidden"),
    SUDDEN("sudden", "Sudden"),
    STEALTH("stealth", "Stealth"),
    ;

    /**
     * How opaque a note is at [distance]: 0 on the receptor line, 1 at the
     * far edge of the playfield, past it beyond.
     */
    fun alphaAt(distance: Float): Float = when (this) {
        VISIBLE -> 1f
        // Gone by the last 25% of the way, fully there past 45%.
        HIDDEN -> ramp(distance, from = 0.25f, to = 0.45f)
        // Nothing until the last 60%, all there inside 40%.
        SUDDEN -> 1f - ramp(distance, from = 0.40f, to = 0.60f)
        STEALTH -> 0f
    }

    companion object {
        fun fromId(id: String?): GlyphAppearance = entries.firstOrNull { it.id == id } ?: VISIBLE

        private fun ramp(value: Float, from: Float, to: Float): Float =
            ((value - from) / (to - from)).coerceIn(0f, 1f)
    }
}

/**
 * StepMania's timing difficulty ("Judge"): every window scaled together.
 * Judge 4 is the standard set; a run under any other is marked on the high
 * score table.
 */
enum class GlyphJudge(val level: Int, val scale: Float) {
    J1(1, 1.50f),
    J2(2, 1.33f),
    J3(3, 1.16f),
    J4(4, 1.00f),
    J5(5, 0.84f),
    J6(6, 0.66f),
    J7(7, 0.50f),
    JUSTICE(8, 0.33f),
    ;

    val label: String get() = if (this == JUSTICE) "Justice" else "J$level"

    companion object {
        fun forScale(scale: Float): GlyphJudge =
            entries.minBy { kotlin.math.abs(it.scale - scale) }
    }
}

/**
 * Trypt Mania's graphics: which of the playfield's effects are drawn, for
 * taste or for a phone that struggles. None of them touches a judgement.
 * Reduced motion and the app's low-performance glass switch still turn the
 * effects off whatever is set here.
 */
data class GlyphGraphics(
    /** The god-ray burst on each hit. */
    val hitRays: Boolean = true,
    /** Samples per shaft for the rays, 1 to 4; the lyrics' quality scale. */
    val rayQuality: Int = 1,
    /** The flash's light caught by the notes and receptors around a hit. */
    val noteLighting: Boolean = true,
    /** The receptor explosion on each hit. */
    val explosions: Boolean = true,
    /** Receptors flashing on the beat. */
    val beatFlash: Boolean = true,
    /** The burst across the field every fifty combo. */
    val comboBurst: Boolean = true,
    /** The playfield seen through an old TV; [GlyphTvShader.OFF] by default. */
    val tvShader: GlyphTvShader = GlyphTvShader.OFF,
    /** The TV's sliders: its pixel size and how strongly each part shows. */
    val tv: GlyphTvTuning = GlyphTvTuning(),
) {
    companion object {
        val RAY_QUALITIES = listOf(1, 2, 3)

        fun rayQualityLabel(quality: Int): String = when (quality) {
            1 -> "Low"
            2 -> "Medium"
            else -> "High"
        }
    }
}

/**
 * The look of an old screen over the playfield. Each multiplies the picture by
 * a pattern, so none of them moves a note or touches a judgement.
 *
 * [mode] is the number the shader picks its pattern by; [id] is what is saved.
 */
enum class GlyphTvShader(val id: String, val label: String, val note: String, val mode: Int) {
    OFF("off", "Off", "The game as it is drawn.", 0),
    SCANLINES("scanlines", "Scanlines", "Every other line at half brightness.", 1),
    DIAGONAL("diagonal", "Diagonal", "A red, green and blue mask that steps one pixel each row.", 2),
    TRIANGULAR("triangular", "Triangular", "Two-pixel red, green and blue columns, shifted every second row pair.", 3),
    WAVE("wave", "Wave", "The picture in lines, each one brighter at its middle.", 4),
    CRT("crt", "CRT", "Soft scanlines, a shadow mask and a little bloom: a CRT monitor. The heaviest of the six.", 5),
    ;

    companion object {
        fun fromId(id: String?): GlyphTvShader = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/**
 * The TV shader's sliders.
 *
 * A TV pixel is a whole number of the phone's own pixels, never a fraction:
 * a grid that does not land on the screen's pixels beats against them, and
 * that beat (moiré) is what a line grid of 640 x 480 drew on a 1272-pixel
 * screen. [pixelSizes] is kept per look, since each has its own default: the
 * CRT at the phone's own resolution, the masks at a desktop monitor's pixel
 * (where a one-pixel line is still visible), Wave at the original's 480 lines.
 *
 * [strength] and [brightness] apply to every look, and so do [chromatic] and
 * [curvature], which bend the finished picture as the glass of a tube would;
 * the rest to the CRT only. The four CRT values run 0 to 1, and [crtScan] and
 * the rest turn them into the shader's own numbers; their defaults are the
 * original's. The tube's two start at 0, a flat picture in line.
 */
data class GlyphTvTuning(
    val pixelSizes: Map<GlyphTvShader, Int> = emptyMap(),
    /** How much of the effect: 0 is the plain picture. */
    val strength: Float = 1f,
    /** A multiplier on the result, 0.5 to 2, for looks that darken the picture. */
    val brightness: Float = 1f,
    val scanlines: Float = DEFAULT_SCANLINES,
    val mask: Float = DEFAULT_MASK,
    val glow: Float = DEFAULT_GLOW,
    val sharpness: Float = DEFAULT_SHARPNESS,
    /** Red and blue drawn apart sideways, as on a tube whose three beams are out of line: 0 is in line. */
    val chromatic: Float = 0f,
    /** The picture bowed over a curved tube, its corners rounded off into the dark: 0 is flat. */
    val curvature: Float = 0f,
) {
    /** Phone pixels to a TV pixel for [shader], on a screen of [density] and [windowHeightPx]. */
    fun pixelSize(shader: GlyphTvShader, density: Float, windowHeightPx: Int): Int =
        (pixelSizes[shader] ?: defaultPixelSize(shader, density, windowHeightPx)).coerceIn(pixelRange(shader))

    fun withPixelSize(shader: GlyphTvShader, size: Int): GlyphTvTuning =
        copy(pixelSizes = pixelSizes + (shader to size.coerceIn(pixelRange(shader))))

    /** How hard the gaps between scanlines are: the shader's `hardScan`, -8 by default. */
    val crtScan: Float get() = -(SCAN_SOFTEST + (SCAN_HARDEST - SCAN_SOFTEST) * scanlines)

    /** How sharp a pixel's edges are: the shader's `hardPix`, -3 by default. */
    val crtSharpness: Float get() = -(SHARP_SOFTEST + (SHARP_HARDEST - SHARP_SOFTEST) * sharpness)

    /** The shadow mask's dark and light channel levels: 0.5 and 1.5 by default. */
    val crtMaskDark: Float get() = 1f - mask
    val crtMaskLight: Float get() = 1f + mask

    /** The bloom added over the picture: 0.15 by default. */
    val crtBloom: Float get() = GLOW_MOST * glow

    /** How far red and blue each stand from green, in phone pixels on a screen of [density]. */
    fun chromaticPx(density: Float): Float = CHROMATIC_MOST_DP * density * chromatic

    /**
     * The tube's bow across and down: the CRT shader's `warp`, which at a
     * quarter of the slider is the original's 1/32 and 1/24.
     */
    val warpX: Float get() = WARP_MOST_X * curvature
    val warpY: Float get() = WARP_MOST_Y * curvature

    /** Whether the picture is bowed at all, so taps on it have to follow the bow. */
    val curved: Boolean get() = curvature >= TUBE_MIN

    /** Whether the tube's pass runs: the picture bowed, or its colours apart. */
    val hasTube: Boolean get() = curved || chromatic >= TUBE_MIN

    /**
     * Where on the flat picture the point [x], [y] of a [width] by [height]
     * bowed tube shows it: the tube shader's own sum, so a tap can be sent to
     * what is seen under it. Past the tube's edge (its dark corners) the
     * point is held to the picture's edge. [out] gets the two, x then y.
     */
    fun tubeSource(x: Float, y: Float, width: Float, height: Float, out: FloatArray) {
        if (width <= 0f || height <= 0f) {
            out[0] = x
            out[1] = y
            return
        }
        val cx = x / width * 2f - 1f
        val cy = y / height * 2f - 1f
        val sx = cx * (1f + cy * cy * warpX)
        val sy = cy * (1f + cx * cx * warpY)
        out[0] = ((sx * 0.5f + 0.5f) * width).coerceIn(0f, width)
        out[1] = ((sy * 0.5f + 0.5f) * height).coerceIn(0f, height)
    }

    /** Every value inside its slider's range, and every size inside its look's. */
    fun clamped(): GlyphTvTuning = GlyphTvTuning(
        pixelSizes = pixelSizes.mapValues { (shader, size) -> size.coerceIn(pixelRange(shader)) },
        strength = strength.unit(1f),
        brightness = (if (brightness.isFinite()) brightness else 1f).coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS),
        scanlines = scanlines.unit(DEFAULT_SCANLINES),
        mask = mask.unit(DEFAULT_MASK),
        glow = glow.unit(DEFAULT_GLOW),
        sharpness = sharpness.unit(DEFAULT_SHARPNESS),
        chromatic = chromatic.unit(0f),
        curvature = curvature.unit(0f),
    )

    /**
     * One line for a settings store: `name=value` pairs, `;` between them,
     * a look's pixel size as `px.<look>`. [decode] reads it back.
     */
    fun encode(): String = buildList {
        add("strength=$strength")
        add("brightness=$brightness")
        add("scanlines=$scanlines")
        add("mask=$mask")
        add("glow=$glow")
        add("sharpness=$sharpness")
        add("chromatic=$chromatic")
        add("curvature=$curvature")
        pixelSizes.forEach { (shader, size) -> add("px.${shader.id}=$size") }
    }.joinToString(";")

    companion object {
        const val DEFAULT_SCANLINES = 6f / 14f
        const val DEFAULT_MASK = 0.5f
        const val DEFAULT_GLOW = 0.3f
        const val DEFAULT_SHARPNESS = 0.4f
        const val MIN_BRIGHTNESS = 0.5f
        const val MAX_BRIGHTNESS = 2f

        private const val SCAN_SOFTEST = 2f
        private const val SCAN_HARDEST = 16f
        private const val SHARP_SOFTEST = 1f
        private const val SHARP_HARDEST = 6f
        private const val GLOW_MOST = 0.5f

        /** Red and blue's furthest from green, in dp: past it the letters read double. */
        const val CHROMATIC_MOST_DP = 3f

        /** The tube's bow at the slider's end: four times the original CRT's. */
        const val WARP_MOST_X = 0.125f
        const val WARP_MOST_Y = 1f / 6f

        /** Below this the tube's two are off, with no pass at all. */
        private const val TUBE_MIN = 0.001f

        private fun Float.unit(fallback: Float): Float = (if (isFinite()) this else fallback).coerceIn(0f, 1f)

        /**
         * [encode]'s line read back, each value held to its range; a name it
         * does not know, or a value it cannot read, keeps that default. Null
         * or blank is every default.
         */
        fun decode(line: String?): GlyphTvTuning {
            if (line.isNullOrBlank()) return GlyphTvTuning()
            val pairs = line.split(';').mapNotNull { part ->
                val name = part.substringBefore('=', "").trim()
                val value = part.substringAfter('=', "").trim()
                if (name.isEmpty() || value.isEmpty()) null else name to value
            }.toMap()
            fun float(name: String, default: Float) = pairs[name]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: default
            val defaults = GlyphTvTuning()
            return GlyphTvTuning(
                pixelSizes = GlyphTvShader.entries.mapNotNull { shader ->
                    pairs["px.${shader.id}"]?.toIntOrNull()?.takeIf { it > 0 }?.let { shader to it }
                }.toMap(),
                strength = float("strength", defaults.strength),
                brightness = float("brightness", defaults.brightness),
                scanlines = float("scanlines", defaults.scanlines),
                mask = float("mask", defaults.mask),
                glow = float("glow", defaults.glow),
                sharpness = float("sharpness", defaults.sharpness),
                chromatic = float("chromatic", defaults.chromatic),
                curvature = float("curvature", defaults.curvature),
            ).clamped()
        }

        /** A monitor's pixel, in dp: about a hundredth of an inch. */
        private const val MONITOR_PIXEL_DP = 1.6f

        /** The original's line count, for Wave's default. */
        private const val WAVE_LINES = 480

        fun pixelRange(shader: GlyphTvShader): IntRange =
            // Wave's line is its bright middle and two dim edges: three pixels at least.
            if (shader == GlyphTvShader.WAVE) 3..12 else 1..8

        fun defaultPixelSize(shader: GlyphTvShader, density: Float, windowHeightPx: Int): Int = when (shader) {
            GlyphTvShader.CRT -> 1
            GlyphTvShader.WAVE ->
                if (windowHeightPx > 0) (windowHeightPx.toFloat() / WAVE_LINES).roundToInt() else 6
            else -> (density * MONITOR_PIXEL_DP).roundToInt()
        }.coerceIn(pixelRange(shader))
    }
}

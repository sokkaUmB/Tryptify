package tf.monochrome.android.domain.model

import androidx.annotation.StringRes
import tf.monochrome.android.R
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The visual settings a music link may move, and how.
 *
 * Only settings that are drawn — read in a draw or layer block, every frame —
 * are here. Three kinds are deliberately left out, each for a reason a
 * listener can be told:
 *
 *  - Switches and choices (god rays on/off, the light source, the layer): a
 *    switch would flash on and off with the beat.
 *  - Anything multiplied by elapsed time — the orbit, the wave speed, the wave
 *    tightness: moving the rate moves the whole phase, so the light and the
 *    letters would jump instead of speeding up.
 *  - Anything that gates a clock or a sensor (glass surface motion, tilt), or
 *    lays the lyrics out (size, spacing, margins, lines): those are read in
 *    composition, and moving them every frame would rebuild the lyric list.
 *
 * [min] and [max] are each field's own `LyricsFxSettings.clamped()` bounds, so
 * a link can never ask the renderer for a value the setting itself refuses.
 */
data class VisualLinkTarget(
    val key: String,
    @StringRes val label: Int,
    val group: Group,
    val min: Float,
    val max: Float,
    /** What has to be on for this setting to show at all. */
    val needs: Need,
    val read: (LyricsFxSettings) -> Float,
    val format: (Float) -> String,
) {
    enum class Group { GOD_RAYS, GLOW, GLASS, WAVE }

    enum class Need { NOTHING, GOD_RAYS, BACKLIGHT, BASS_REACTION, LIQUID_GLASS, WAVE, SHADOW }

    /** The requirement [fx] does not meet, or null when the setting shows. */
    fun unmet(fx: LyricsFxSettings): Need? = when (needs) {
        Need.NOTHING -> null
        Need.GOD_RAYS -> if (fx.godRays) null else Need.GOD_RAYS
        Need.BACKLIGHT -> when {
            !fx.godRays -> Need.GOD_RAYS
            fx.godRaySource != LyricsFxSettings.GOD_RAYS_BACKLIGHT -> Need.BACKLIGHT
            else -> null
        }
        Need.BASS_REACTION -> if (fx.bassReact > 0.01f) null else Need.BASS_REACTION
        Need.LIQUID_GLASS -> if (fx.liquidGlass) null else Need.LIQUID_GLASS
        Need.WAVE -> if (fx.rotationDegrees > 0.05f) null else Need.WAVE
        Need.SHADOW -> if (fx.shadowDepth > 0.01f) null else Need.SHADOW
    }
}

object VisualLinkTargets {
    private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
    private fun deg(v: Float) = "${v.roundToInt()}°"
    private fun two(v: Float) = String.format(Locale.US, "%.2f", v)

    val ALL: List<VisualLinkTarget> = listOf(
        // God rays.
        VisualLinkTarget(
            "lyrics.godRayExposure", R.string.fx_god_ray_exposure, VisualLinkTarget.Group.GOD_RAYS,
            0f, 1.5f, VisualLinkTarget.Need.GOD_RAYS, { it.godRayExposure }, ::pct,
        ),
        VisualLinkTarget(
            "lyrics.godRayDensity", R.string.fx_god_ray_length, VisualLinkTarget.Group.GOD_RAYS,
            0.2f, 1f, VisualLinkTarget.Need.GOD_RAYS, { it.godRayDensity }, ::pct,
        ),
        VisualLinkTarget(
            "lyrics.godRayDecay", R.string.fx_god_ray_decay, VisualLinkTarget.Group.GOD_RAYS,
            0.85f, 1f, VisualLinkTarget.Need.GOD_RAYS, { it.godRayDecay },
            { String.format(Locale.US, "%.3f", it) },
        ),
        VisualLinkTarget(
            "lyrics.godRaySunSize", R.string.fx_god_ray_sun_size, VisualLinkTarget.Group.GOD_RAYS,
            0.03f, 0.3f, VisualLinkTarget.Need.BACKLIGHT, { it.godRaySunSize }, ::pct,
        ),
        VisualLinkTarget(
            "lyrics.godRayShimmer", R.string.fx_god_ray_dust, VisualLinkTarget.Group.GOD_RAYS,
            0f, 1f, VisualLinkTarget.Need.GOD_RAYS, { it.godRayShimmer }, ::pct,
        ),
        VisualLinkTarget(
            "lyrics.godRayBeat", R.string.fx_god_ray_beat, VisualLinkTarget.Group.GOD_RAYS,
            0f, 1f, VisualLinkTarget.Need.BASS_REACTION, { it.godRayBeat }, ::pct,
        ),
        VisualLinkTarget(
            "lyrics.godRaySway", R.string.fx_god_ray_sway, VisualLinkTarget.Group.GOD_RAYS,
            0f, 1f, VisualLinkTarget.Need.GOD_RAYS, { it.godRaySway }, ::pct,
        ),
        VisualLinkTarget(
            "lyrics.godRayAzimuthDeg", R.string.fx_god_ray_azimuth, VisualLinkTarget.Group.GOD_RAYS,
            0f, 360f, VisualLinkTarget.Need.GOD_RAYS, { it.godRayAzimuthDeg }, ::deg,
        ),
        VisualLinkTarget(
            "lyrics.godRayElevationDeg", R.string.fx_god_ray_elevation, VisualLinkTarget.Group.GOD_RAYS,
            0f, 90f, VisualLinkTarget.Need.GOD_RAYS, { it.godRayElevationDeg }, ::deg,
        ),
        VisualLinkTarget(
            "lyrics.glassRayCatch", R.string.fx_god_ray_glass_catch, VisualLinkTarget.Group.GOD_RAYS,
            0f, 1f, VisualLinkTarget.Need.LIQUID_GLASS, { it.glassRayCatch }, ::pct,
        ),
        // Glow and the beat's swell. Both are drawn by the bass engine.
        VisualLinkTarget(
            "lyrics.glowRadiusDp", R.string.settings_glow_radius, VisualLinkTarget.Group.GLOW,
            0f, 160f, VisualLinkTarget.Need.BASS_REACTION, { it.glowRadiusDp }, { "+${it.roundToInt()} dp" },
        ),
        VisualLinkTarget(
            "lyrics.glowBrightness", R.string.settings_glow_brightness, VisualLinkTarget.Group.GLOW,
            0f, 0.6f, VisualLinkTarget.Need.BASS_REACTION, { it.glowBrightness }, ::pct,
        ),
        VisualLinkTarget(
            "lyrics.pumpAmount", R.string.fx_pump_amount, VisualLinkTarget.Group.GLOW,
            0f, 0.25f, VisualLinkTarget.Need.BASS_REACTION, { it.pumpAmount }, { "+${(it * 100).roundToInt()}%" },
        ),
        // The glass letters.
        VisualLinkTarget(
            "lyrics.glassBodyOpacity", R.string.fx_glass_opacity, VisualLinkTarget.Group.GLASS,
            0.2f, 1f, VisualLinkTarget.Need.LIQUID_GLASS, { it.glassBodyOpacity }, ::pct,
        ),
        VisualLinkTarget(
            "lyrics.glassRefraction", R.string.fx_refraction, VisualLinkTarget.Group.GLASS,
            0f, 0.4f, VisualLinkTarget.Need.LIQUID_GLASS, { it.glassRefraction }, ::two,
        ),
        VisualLinkTarget(
            "lyrics.glassRimBrightness", R.string.fx_edge_highlight, VisualLinkTarget.Group.GLASS,
            0f, 2f, VisualLinkTarget.Need.LIQUID_GLASS, { it.glassRimBrightness }, ::pct,
        ),
        VisualLinkTarget(
            "lyrics.glassDispersion", R.string.fx_chromatic_aberration, VisualLinkTarget.Group.GLASS,
            0f, 2f, VisualLinkTarget.Need.LIQUID_GLASS, { it.glassDispersion }, ::pct,
        ),
        VisualLinkTarget(
            "lyrics.glassRoundness", R.string.fx_roundness, VisualLinkTarget.Group.GLASS,
            0.5f, 2f, VisualLinkTarget.Need.LIQUID_GLASS, { it.glassRoundness }, ::two,
        ),
        VisualLinkTarget(
            "lyrics.glassDepth", R.string.fx_depth_profondeur, VisualLinkTarget.Group.GLASS,
            0.5f, 2f, VisualLinkTarget.Need.LIQUID_GLASS, { it.glassDepth }, ::two,
        ),
        VisualLinkTarget(
            "lyrics.glassFrost", R.string.fx_frosted_blur, VisualLinkTarget.Group.GLASS,
            0f, 1f, VisualLinkTarget.Need.LIQUID_GLASS, { it.glassFrost }, ::pct,
        ),
        VisualLinkTarget(
            "lyrics.glassLightAngleDeg", R.string.fx_light_angle, VisualLinkTarget.Group.GLASS,
            0f, 360f, VisualLinkTarget.Need.LIQUID_GLASS, { it.glassLightAngleDeg }, ::deg,
        ),
        VisualLinkTarget(
            "lyrics.glassEdgeWidth", R.string.fx_edge_width, VisualLinkTarget.Group.GLASS,
            0f, 1f, VisualLinkTarget.Need.LIQUID_GLASS, { it.glassEdgeWidth }, ::pct,
        ),
        VisualLinkTarget(
            "lyrics.glassReflection", R.string.fx_reflection, VisualLinkTarget.Group.GLASS,
            0f, 2f, VisualLinkTarget.Need.LIQUID_GLASS, { it.glassReflection }, ::pct,
        ),
        VisualLinkTarget(
            "lyrics.glassGloss", R.string.fx_gloss, VisualLinkTarget.Group.GLASS,
            0f, 1f, VisualLinkTarget.Need.LIQUID_GLASS, { it.glassGloss }, ::pct,
        ),
        // The 3D wave and the shadow under it.
        VisualLinkTarget(
            "lyrics.rotationDegrees", R.string.fx_tilt, VisualLinkTarget.Group.WAVE,
            0f, 25f, VisualLinkTarget.Need.WAVE, { it.rotationDegrees }, ::deg,
        ),
        VisualLinkTarget(
            "lyrics.waveTravelDp", R.string.fx_wave_travel, VisualLinkTarget.Group.WAVE,
            0f, 8f, VisualLinkTarget.Need.WAVE, { it.waveTravelDp }, { String.format(Locale.US, "%.1f dp", it) },
        ),
        VisualLinkTarget(
            "lyrics.shadowDepth", R.string.fx_shadow_depth, VisualLinkTarget.Group.WAVE,
            0f, 1f, VisualLinkTarget.Need.SHADOW, { it.shadowDepth }, ::pct,
        ),
    )

    private val BY_KEY: Map<String, VisualLinkTarget> = ALL.associateBy { it.key }

    fun byKey(key: String): VisualLinkTarget? = BY_KEY[key]

    /**
     * [base] with every linked field replaced by its live value from [values]
     * (keyed like [VisualLinkTarget.key]), each kept inside its own range.
     * Returns [base] itself when nothing applies, so the common no-link frame
     * allocates nothing.
     */
    fun applyLyrics(base: LyricsFxSettings, values: Map<String, Float>): LyricsFxSettings {
        if (values.isEmpty()) return base
        fun v(key: String, current: Float): Float {
            val live = values[key] ?: return current
            val t = BY_KEY[key] ?: return current
            return if (live.isFinite()) live.coerceIn(t.min, t.max) else current
        }
        return base.copy(
            godRayExposure = v("lyrics.godRayExposure", base.godRayExposure),
            godRayDensity = v("lyrics.godRayDensity", base.godRayDensity),
            godRayDecay = v("lyrics.godRayDecay", base.godRayDecay),
            godRaySunSize = v("lyrics.godRaySunSize", base.godRaySunSize),
            godRayShimmer = v("lyrics.godRayShimmer", base.godRayShimmer),
            godRayBeat = v("lyrics.godRayBeat", base.godRayBeat),
            godRaySway = v("lyrics.godRaySway", base.godRaySway),
            godRayAzimuthDeg = v("lyrics.godRayAzimuthDeg", base.godRayAzimuthDeg),
            godRayElevationDeg = v("lyrics.godRayElevationDeg", base.godRayElevationDeg),
            glassRayCatch = v("lyrics.glassRayCatch", base.glassRayCatch),
            glowRadiusDp = v("lyrics.glowRadiusDp", base.glowRadiusDp),
            glowBrightness = v("lyrics.glowBrightness", base.glowBrightness),
            pumpAmount = v("lyrics.pumpAmount", base.pumpAmount),
            glassBodyOpacity = v("lyrics.glassBodyOpacity", base.glassBodyOpacity),
            glassRefraction = v("lyrics.glassRefraction", base.glassRefraction),
            glassRimBrightness = v("lyrics.glassRimBrightness", base.glassRimBrightness),
            glassDispersion = v("lyrics.glassDispersion", base.glassDispersion),
            glassRoundness = v("lyrics.glassRoundness", base.glassRoundness),
            glassDepth = v("lyrics.glassDepth", base.glassDepth),
            glassFrost = v("lyrics.glassFrost", base.glassFrost),
            glassLightAngleDeg = v("lyrics.glassLightAngleDeg", base.glassLightAngleDeg),
            glassEdgeWidth = v("lyrics.glassEdgeWidth", base.glassEdgeWidth),
            glassReflection = v("lyrics.glassReflection", base.glassReflection),
            glassGloss = v("lyrics.glassGloss", base.glassGloss),
            rotationDegrees = v("lyrics.rotationDegrees", base.rotationDegrees),
            waveTravelDp = v("lyrics.waveTravelDp", base.waveTravelDp),
            shadowDepth = v("lyrics.shadowDepth", base.shadowDepth),
        )
    }

    /** Whether any of [keys] keeps the god rays' clock running (their dust or sway). */
    fun movesRayClock(keys: Set<String>): Boolean =
        "lyrics.godRayShimmer" in keys || "lyrics.godRaySway" in keys
}

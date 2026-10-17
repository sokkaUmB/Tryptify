// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * A CRT look as one thing to pick, keep and pass on: which of the TV
 * shader's looks ([shader]) and every slider of it ([tuning]). The same
 * presets serve the app-wide CRT effect, the Lyrics FX tab and Trypt Mania's
 * Graphics, since all three draw [GlyphTvShader] with a [GlyphTvTuning].
 *
 * Pure: no Android in it, so the built-ins and the file format are tested
 * alone.
 */
data class CrtPreset(
    /** What the chip says. A built-in's name comes from its string resource instead. */
    val name: String,
    val shader: GlyphTvShader,
    val tuning: GlyphTvTuning,
    /** A built-in's id ([CrtPresets.BUILT_IN]); null for one the user saved. */
    val builtInId: String? = null,
) {
    /** Whether [shader] and [tuning] are what this preset sets, so its chip shows as picked. */
    fun matches(shader: GlyphTvShader, tuning: GlyphTvTuning): Boolean =
        shader == this.shader && tuning.clamped() == this.tuning.clamped()
}

/**
 * The four built-in looks, and the file a preset is exported as.
 *
 * The built-ins need the screen's height: a TV pixel is a whole number of
 * the phone's own, so "240 lines" is a different pixel size on every phone.
 */
object CrtPresets {

    const val ARCADE = "arcade"
    const val TRINITRON = "trinitron"
    const val LIVING_ROOM = "living_room"
    const val VHS = "vhs"

    /** The built-ins' ids, in the order the chips show them. */
    val BUILT_IN = listOf(ARCADE, TRINITRON, LIVING_ROOM, VHS)

    /** The built-in [id] on a screen [windowHeightPx] tall; null for an unknown id. */
    fun builtIn(id: String, windowHeightPx: Int): CrtPreset? = when (id) {
        // An arcade cabinet's monitor: hard scanlines, a strong shadow mask,
        // the phosphors blooming, and glass bowed toward the player.
        ARCADE -> CrtPreset(
            name = "Arcade",
            shader = GlyphTvShader.CRT,
            tuning = GlyphTvTuning(
                brightness = 1.25f,
                scanlines = 0.75f,
                mask = 0.7f,
                glow = 0.6f,
                sharpness = 0.55f,
                chromatic = 0.15f,
                curveTopDeg = 9f,
                curveSideDeg = 7f,
                corners = 0.25f,
                vignette = 0.3f,
            ).lines(GlyphTvShader.CRT, windowHeightPx, ARCADE_LINES),
            builtInId = ARCADE,
        )
        // A late, flat-faced Trinitron: an aperture grille, tight beams, the
        // glass flat and nothing bent.
        TRINITRON -> CrtPreset(
            name = "Trinitron",
            shader = GlyphTvShader.ROYALE,
            tuning = GlyphTvTuning(
                brightness = 1.3f,
                royaleMask = GlyphTvTuning.ROYALE_GRILLE,
                royaleMaskStrength = 0.6f,
                royaleBeamWidth = 0.25f,
                royaleBeamShape = 0.7f,
                royaleDiffusion = 0.15f,
                royaleBloom = 0.15f,
                royaleBorder = 0f,
                vignette = 0.1f,
            ).lines(GlyphTvShader.ROYALE, windowHeightPx, TRINITRON_LINES),
            builtInId = TRINITRON,
        )
        // The family TV seen from the sofa: the Old TV button's look.
        LIVING_ROOM -> CrtPreset(
            name = "Living room",
            shader = GlyphTvShader.ROYALE,
            tuning = GlyphTvTuning.oldTv(windowHeightPx),
            builtInId = LIVING_ROOM,
        )
        // A tape played back: soft lines, the colours out of line, the edges
        // dark and a little bent.
        VHS -> CrtPreset(
            name = "VHS",
            shader = GlyphTvShader.WAVE,
            tuning = GlyphTvTuning(
                strength = 0.7f,
                brightness = 1.15f,
                chromatic = 0.65f,
                curveTopDeg = 5f,
                curveSideDeg = 4f,
                corners = 0.15f,
                vignette = 0.35f,
            ).lines(GlyphTvShader.WAVE, windowHeightPx, VHS_LINES),
            builtInId = VHS,
        )
        else -> null
    }

    fun builtIns(windowHeightPx: Int): List<CrtPreset> = BUILT_IN.mapNotNull { builtIn(it, windowHeightPx) }

    /** [lines] down the screen as [shader]'s pixel size, when the screen's height is known. */
    private fun GlyphTvTuning.lines(shader: GlyphTvShader, windowHeightPx: Int, lines: Int): GlyphTvTuning =
        if (windowHeightPx > 0) withPixelSize(shader, (windowHeightPx / lines.toFloat()).roundToInt()) else this

    private const val ARCADE_LINES = 320
    private const val TRINITRON_LINES = 480
    private const val VHS_LINES = 240

    // ── the file ─────────────────────────────────────────────────────────

    /** What an exported preset says it is, so a file of anything else is refused. */
    const val FILE_TYPE = "tryptify-crt-preset"
    const val FILE_VERSION = 1

    /** A preset file's name: the preset's, made safe for any file system. */
    fun fileName(name: String): String {
        val safe = name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "").replace(' ', '_').take(40)
        return "${safe.ifBlank { "crt" }}.crt.json"
    }

    @Serializable
    private data class PresetFile(
        val type: String = "",
        val version: Int = 0,
        val name: String = "",
        val look: String = "",
        /** [GlyphTvTuning.encode]'s line: the format the app already saves the sliders in. */
        val tuning: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    /** [preset] as the text of a `.crt.json` file. */
    fun export(preset: CrtPreset): String = json.encodeToString(
        PresetFile.serializer(),
        PresetFile(
            type = FILE_TYPE,
            version = FILE_VERSION,
            name = preset.name,
            look = preset.shader.id,
            tuning = preset.tuning.clamped().encode(),
        ),
    )

    /**
     * A preset from a file's [text], held to every slider's range; or why
     * not. Always a preset of the user's own, whatever it was exported from.
     */
    fun import(text: String): Result<CrtPreset> = runCatching {
        val file = json.decodeFromString(PresetFile.serializer(), text)
        require(file.type == FILE_TYPE) { "Not a CRT preset" }
        require(file.version in 1..FILE_VERSION) { "Made by a newer Tryptify" }
        val shader = GlyphTvShader.entries.firstOrNull { it.id == file.look }
        requireNotNull(shader) { "Unknown look: ${file.look}" }
        CrtPreset(
            name = file.name.trim().take(MAX_NAME).ifBlank { "Imported" },
            shader = shader,
            tuning = GlyphTvTuning.decode(file.tuning).clamped(),
        )
    }

    /** A list of the user's presets, as kept on the phone. */
    fun encodeAll(presets: List<CrtPreset>): String =
        json.encodeToString(kotlinx.serialization.builtins.ListSerializer(PresetFile.serializer()), presets.map {
            PresetFile(FILE_TYPE, FILE_VERSION, it.name, it.shader.id, it.tuning.clamped().encode())
        })

    /** What [encodeAll] wrote; an unreadable store is an empty list, never a crash. */
    fun decodeAll(text: String?): List<CrtPreset> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(PresetFile.serializer()), text)
        }.getOrDefault(emptyList()).mapNotNull { file ->
            val shader = GlyphTvShader.entries.firstOrNull { it.id == file.look } ?: return@mapNotNull null
            CrtPreset(file.name.ifBlank { "Preset" }, shader, GlyphTvTuning.decode(file.tuning).clamped())
        }
    }

    /** The longest name a preset keeps. */
    const val MAX_NAME = 32
}

package tf.monochrome.android.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import tf.monochrome.android.R
import tf.monochrome.android.glyph.engine.GlyphTvShader
import tf.monochrome.android.glyph.engine.GlyphTvTuning
import tf.monochrome.android.ui.glyph.AppTvLive
import tf.monochrome.android.ui.glyph.appTvShaderRuns
import kotlin.math.roundToInt

/**
 * The app-wide CRT effect's settings (Settings › Appearance › CRT effect), as
 * whichever screen shows its controls has them: Settings and the Lyrics FX
 * Studio both read and write the same three preferences.
 */
interface CrtEffectPrefs {
    val appTvShader: StateFlow<GlyphTvShader>
    val appTvTuning: StateFlow<GlyphTvTuning>
    val appTvOnLyrics: StateFlow<Boolean>
    fun setAppTvShader(shader: GlyphTvShader)
    fun setAppTvTuning(tuning: GlyphTvTuning)
    fun setAppTvOnLyrics(lyrics: Boolean)
}

/**
 * Settings › Appearance, last: every screen through an old TV, in one of Trypt
 * Mania's looks (the CRT unless another is picked). Says so when this phone
 * cannot run it, rather than leaving a switch that does nothing. The Lyrics FX
 * Studio's Lyrics tab ends with the same controls, under its own [header] and
 * without the [indent]: one effect, set from either place.
 */
@Composable
internal fun CrtEffectSetting(
    prefs: CrtEffectPrefs,
    header: @Composable (String) -> Unit = { SettingsGroupHeader(it) },
    indent: Dp = 16.dp,
) {
    val shader by prefs.appTvShader.collectAsStateWithLifecycle()
    val on = shader != GlyphTvShader.OFF
    val runs = appTvShaderRuns(shader)
    var looks by remember { mutableStateOf(false) }
    header(stringResource(R.string.settings_crt_effect))
    SettingSwitchItem(
        title = stringResource(R.string.settings_crt_effect),
        subtitle = if (on && !runs) {
            stringResource(R.string.settings_crt_unavailable)
        } else {
            stringResource(R.string.settings_crt_effect_subtitle)
        },
        checked = on,
        onCheckedChange = {
            prefs.setAppTvShader(
                if (it) GlyphTvShader.CRT else GlyphTvShader.OFF,
            )
        },
    )
    if (on) {
        Box {
            SettingItem(
                title = stringResource(R.string.settings_crt_look),
                subtitle = shader.label,
                onClick = { looks = true },
            )
            DropdownMenu(expanded = looks, onDismissRequest = { looks = false }) {
                GlyphTvShader.entries
                    .filter { it != GlyphTvShader.OFF }
                    .forEach { look ->
                        DropdownMenuItem(
                            text = { Text(look.label) },
                            onClick = {
                                prefs.setAppTvShader(look)
                                looks = false
                            },
                        )
                    }
            }
        }
        CrtTarget(prefs)
        CrtSliders(prefs, shader, indent)
    }
}

/** What the CRT effect draws over: every screen, or the lyrics alone. */
@Composable
private fun CrtTarget(prefs: CrtEffectPrefs) {
    val onLyrics by prefs.appTvOnLyrics.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Box {
        SettingItem(
            title = stringResource(R.string.settings_crt_target),
            subtitle = stringResource(if (onLyrics) R.string.settings_crt_target_lyrics else R.string.settings_crt_target_app),
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(false to R.string.settings_crt_target_app, true to R.string.settings_crt_target_lyrics).forEach { (lyrics, name) ->
                DropdownMenuItem(
                    text = { Text(stringResource(name)) },
                    onClick = {
                        prefs.setAppTvOnLyrics(lyrics)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * The CRT effect's sliders: Trypt Mania's TV shader's own (its pixel size,
 * strength and brightness for every look, the CRT's four for the CRT), then
 * the tube's Chromatic shift and Curvature. The whole app is the preview: a
 * drag moves it live ([AppTvLive]) and the
 * store is written once, on release.
 */
@Composable
private fun CrtSliders(prefs: CrtEffectPrefs, shader: GlyphTvShader, indent: Dp) {
    val stored by prefs.appTvTuning.collectAsStateWithLifecycle()
    val live = AppTvLive
    val tv = live.tuning ?: stored
    val drag: (GlyphTvTuning) -> Unit = { live.tuning = it }
    val commit: () -> Unit = {
        val done = live.tuning
        if (done != null) {
            prefs.setAppTvTuning(done)
            // Nothing to wait for when the drag came back to where it was.
            if (done == stored) live.tuning = null
        }
    }
    val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val pixel = tv.pixelSize(shader, density, window.height)
    val range = GlyphTvTuning.pixelRange(shader)
    val wave = shader == GlyphTvShader.WAVE ||
        shader == GlyphTvShader.ROYALE
    fun percent(value: Float) = (value * 100f).roundToInt()
    Column(modifier = Modifier.padding(horizontal = indent)) {
        CrtSlider(
            label = stringResource(if (wave) R.string.settings_crt_line_height else R.string.settings_crt_pixel_size, pixel),
            subtitle = if (window.height <= 0) {
                null
            } else if (wave) {
                stringResource(R.string.settings_crt_lines_down, window.height / pixel)
            } else {
                stringResource(R.string.settings_crt_tv_pixels, window.width / pixel, window.height / pixel)
            },
            value = pixel.toFloat(),
            range = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
            onChange = { drag(tv.withPixelSize(shader, it.roundToInt())) },
            onDone = commit,
        )
        CrtSlider(
            label = stringResource(R.string.settings_crt_strength, percent(tv.strength)),
            subtitle = stringResource(R.string.settings_crt_strength_desc),
            value = tv.strength,
            onChange = { drag(tv.copy(strength = it)) },
            onDone = commit,
        )
        CrtSlider(
            label = stringResource(R.string.settings_crt_brightness, percent(tv.brightness)),
            subtitle = stringResource(R.string.settings_crt_brightness_desc),
            value = tv.brightness,
            range = GlyphTvTuning.MIN_BRIGHTNESS..
                GlyphTvTuning.MAX_BRIGHTNESS,
            onChange = { drag(tv.copy(brightness = it)) },
            onDone = commit,
        )
        if (shader == GlyphTvShader.CRT) {
            CrtSlider(
                label = stringResource(R.string.settings_crt_scanlines, percent(tv.scanlines)),
                subtitle = stringResource(R.string.settings_crt_scanlines_desc),
                value = tv.scanlines,
                onChange = { drag(tv.copy(scanlines = it)) },
                onDone = commit,
            )
            CrtSlider(
                label = stringResource(R.string.settings_crt_mask, percent(tv.mask)),
                subtitle = stringResource(R.string.settings_crt_mask_desc),
                value = tv.mask,
                onChange = { drag(tv.copy(mask = it)) },
                onDone = commit,
            )
            CrtSlider(
                label = stringResource(R.string.settings_crt_glow, percent(tv.glow)),
                subtitle = stringResource(R.string.settings_crt_glow_desc),
                value = tv.glow,
                onChange = { drag(tv.copy(glow = it)) },
                onDone = commit,
            )
            CrtSlider(
                label = stringResource(R.string.settings_crt_sharpness, percent(tv.sharpness)),
                subtitle = stringResource(R.string.settings_crt_sharpness_desc),
                value = tv.sharpness,
                onChange = { drag(tv.copy(sharpness = it)) },
                onDone = commit,
            )
        }
        CrtSlider(
            label = stringResource(R.string.settings_crt_chromatic, percent(tv.chromatic)),
            subtitle = stringResource(R.string.settings_crt_chromatic_desc),
            value = tv.chromatic,
            onChange = { drag(tv.copy(chromatic = it)) },
            onDone = commit,
        )
        if (shader == GlyphTvShader.ROYALE) {
            RoyaleSliders(tv, density, drag, commit)
        }
        CrtSlider(
            label = stringResource(R.string.settings_crt_curve_top, tv.curveTopDeg),
            subtitle = stringResource(R.string.settings_crt_curve_top_desc),
            value = tv.curveTopDeg,
            range = 0f..GlyphTvTuning.MAX_CURVE_DEG,
            onChange = { drag(tv.copy(curveTopDeg = it)) },
            onDone = commit,
        )
        CrtSlider(
            label = stringResource(R.string.settings_crt_curve_side, tv.curveSideDeg),
            subtitle = stringResource(R.string.settings_crt_curve_side_desc),
            value = tv.curveSideDeg,
            range = 0f..GlyphTvTuning.MAX_CURVE_DEG,
            onChange = { drag(tv.copy(curveSideDeg = it)) },
            onDone = commit,
        )
        val tilts = -GlyphTvTuning.MAX_TILT_DEG..
            GlyphTvTuning.MAX_TILT_DEG
        CrtSlider(
            label = stringResource(R.string.settings_crt_tilt_side, tv.tiltSideDeg),
            subtitle = stringResource(R.string.settings_crt_tilt_side_desc),
            value = tv.tiltSideDeg,
            range = tilts,
            onChange = { drag(tv.copy(tiltSideDeg = it)) },
            onDone = commit,
        )
        CrtSlider(
            label = stringResource(R.string.settings_crt_tilt_top, tv.tiltTopDeg),
            subtitle = stringResource(R.string.settings_crt_tilt_top_desc),
            value = tv.tiltTopDeg,
            range = tilts,
            onChange = { drag(tv.copy(tiltTopDeg = it)) },
            onDone = commit,
        )
        CrtSlider(
            label = stringResource(R.string.settings_crt_bezel, percent(tv.bezel)),
            subtitle = stringResource(R.string.settings_crt_bezel_desc),
            value = tv.bezel,
            onChange = { drag(tv.copy(bezel = it)) },
            onDone = commit,
        )
        CrtSlider(
            label = stringResource(R.string.settings_crt_corners, percent(tv.corners)),
            subtitle = null,
            value = tv.corners,
            onChange = { drag(tv.copy(corners = it)) },
            onDone = commit,
        )
        CrtSlider(
            label = stringResource(R.string.settings_crt_vignette, percent(tv.vignette)),
            subtitle = stringResource(R.string.settings_crt_vignette_desc),
            value = tv.vignette,
            onChange = { drag(tv.copy(vignette = it)) },
            onDone = commit,
        )
        CrtSlider(
            label = stringResource(R.string.settings_crt_glare, percent(tv.glare)),
            subtitle = stringResource(R.string.settings_crt_glare_desc),
            value = tv.glare,
            onChange = { drag(tv.copy(glare = it)) },
            onDone = commit,
        )
        Spacer(modifier = Modifier.height(8.dp))
        // One tap to an old TV filmed from beside it: CRT-Royale, bowed,
        // tilted and framed.
        androidx.compose.material3.Button(
            onClick = {
                live.tuning = null
                prefs.setAppTvShader(GlyphTvShader.ROYALE)
                prefs.setAppTvTuning(GlyphTvTuning.oldTv(window.height, keep = tv))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_crt_old_tv))
        }
        OutlinedButton(
            onClick = {
                live.tuning = null
                prefs.setAppTvTuning(tv.reset(shader))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_crt_reset, shader.label))
        }
    }
}

/**
 * CRT-Royale's own: its mask (a pick, saved at once), the mask's size and
 * strength, the beams, and the light in the glass.
 */
@Composable
private fun RoyaleSliders(
    tv: GlyphTvTuning,
    density: Float,
    drag: (GlyphTvTuning) -> Unit,
    commit: () -> Unit,
) {
    val masks = listOf(
        GlyphTvTuning.ROYALE_GRILLE to R.string.settings_crt_mask_grille,
        GlyphTvTuning.ROYALE_SLOT to R.string.settings_crt_mask_slot,
        GlyphTvTuning.ROYALE_SHADOW to R.string.settings_crt_mask_shadow,
    )
    var picking by remember { mutableStateOf(false) }
    fun percent(value: Float) = (value * 100f).roundToInt()
    Box {
        SettingItem(
            title = stringResource(R.string.settings_crt_mask_type),
            subtitle = stringResource(masks.firstOrNull { it.first == tv.royaleMask }?.second ?: masks[1].second),
            onClick = { picking = true },
        )
        DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
            masks.forEach { (mask, name) ->
                DropdownMenuItem(
                    text = { Text(stringResource(name)) },
                    onClick = {
                        drag(tv.copy(royaleMask = mask))
                        commit()
                        picking = false
                    },
                )
            }
        }
    }
    val triad = tv.royaleTriadPx(density)
    val triads = GlyphTvTuning.ROYALE_TRIADS
    CrtSlider(
        label = stringResource(R.string.settings_crt_mask_size, triad),
        subtitle = stringResource(R.string.settings_crt_mask_size_desc),
        value = triad.toFloat(),
        range = triads.first.toFloat()..triads.last.toFloat(),
        steps = triads.last - triads.first - 1,
        onChange = { drag(tv.copy(royaleTriad = it.roundToInt())) },
        onDone = commit,
    )
    CrtSlider(
        label = stringResource(R.string.settings_crt_mask_strength, percent(tv.royaleMaskStrength)),
        subtitle = stringResource(R.string.settings_crt_mask_strength_desc),
        value = tv.royaleMaskStrength,
        onChange = { drag(tv.copy(royaleMaskStrength = it)) },
        onDone = commit,
    )
    CrtSlider(
        label = stringResource(R.string.settings_crt_beam_width, percent(tv.royaleBeamWidth)),
        subtitle = stringResource(R.string.settings_crt_beam_width_desc),
        value = tv.royaleBeamWidth,
        onChange = { drag(tv.copy(royaleBeamWidth = it)) },
        onDone = commit,
    )
    CrtSlider(
        label = stringResource(R.string.settings_crt_beam_shape, percent(tv.royaleBeamShape)),
        subtitle = stringResource(R.string.settings_crt_beam_shape_desc),
        value = tv.royaleBeamShape,
        onChange = { drag(tv.copy(royaleBeamShape = it)) },
        onDone = commit,
    )
    CrtSlider(
        label = stringResource(R.string.settings_crt_halation, percent(tv.royaleHalation)),
        subtitle = stringResource(R.string.settings_crt_halation_desc),
        value = tv.royaleHalation,
        onChange = { drag(tv.copy(royaleHalation = it)) },
        onDone = commit,
    )
    CrtSlider(
        label = stringResource(R.string.settings_crt_diffusion, percent(tv.royaleDiffusion)),
        subtitle = stringResource(R.string.settings_crt_diffusion_desc),
        value = tv.royaleDiffusion,
        onChange = { drag(tv.copy(royaleDiffusion = it)) },
        onDone = commit,
    )
    CrtSlider(
        label = stringResource(R.string.settings_crt_bloom, percent(tv.royaleBloom)),
        subtitle = stringResource(R.string.settings_crt_bloom_desc),
        value = tv.royaleBloom,
        onChange = { drag(tv.copy(royaleBloom = it)) },
        onDone = commit,
    )
    CrtSlider(
        label = stringResource(R.string.settings_crt_edge, percent(tv.royaleBorder)),
        subtitle = stringResource(R.string.settings_crt_edge_desc),
        value = tv.royaleBorder,
        onChange = { drag(tv.copy(royaleBorder = it)) },
        onDone = commit,
    )
}

/** One of [CrtSliders]: its label, a line on what it does, and the slider, moving the app as it is dragged. */
@Composable
private fun CrtSlider(
    label: String,
    subtitle: String?,
    value: Float,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    if (subtitle != null) {
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(
        value = value.coerceIn(range),
        onValueChange = onChange,
        onValueChangeFinished = onDone,
        valueRange = range,
        steps = steps,
        modifier = Modifier.fillMaxWidth(),
    )
}

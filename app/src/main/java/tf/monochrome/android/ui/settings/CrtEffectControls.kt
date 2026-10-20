package tf.monochrome.android.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import tf.monochrome.android.R
import tf.monochrome.android.glyph.engine.GlyphTvShader
import tf.monochrome.android.glyph.engine.GlyphTvTuning
import tf.monochrome.android.ui.glyph.AppTvLive
import tf.monochrome.android.ui.glyph.appTvShaderRuns
import tf.monochrome.android.ui.musiclinks.LinkedTrackMarker
import tf.monochrome.android.ui.musiclinks.LocalMusicLinkHost
import tf.monochrome.android.ui.musiclinks.musicLinkColor
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
    // The presets whether the effect is on or not: picking one turns it on.
    val tuning by prefs.appTvTuning.collectAsStateWithLifecycle()
    tf.monochrome.android.ui.settings.crt.CrtPresetsPanel(
        shader = shader,
        tuning = tuning,
        onApply = { look, tv ->
            AppTvLive.tuning = null
            prefs.setAppTvTuning(tv)
            prefs.setAppTvShader(look)
        },
        modifier = Modifier.padding(vertical = 8.dp),
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
            default = TV_DEFAULTS.pixelSize(shader, density, window.height).toFloat(),
            range = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
            onChange = { drag(tv.withPixelSize(shader, it.roundToInt())) },
            onDone = commit,
        )
        CrtSlider(
            label = stringResource(R.string.settings_crt_strength, percent(tv.strength)),
            subtitle = stringResource(R.string.settings_crt_strength_desc),
            value = tv.strength,
            default = TV_DEFAULTS.strength,
            onChange = { drag(tv.copy(strength = it)) },
            linkKey = "tv.strength",
            onDone = commit,
        )
        CrtSlider(
            label = stringResource(R.string.settings_crt_brightness, percent(tv.brightness)),
            subtitle = stringResource(R.string.settings_crt_brightness_desc),
            value = tv.brightness,
            default = TV_DEFAULTS.brightness,
            range = GlyphTvTuning.MIN_BRIGHTNESS..
                GlyphTvTuning.MAX_BRIGHTNESS,
            onChange = { drag(tv.copy(brightness = it)) },
            linkKey = "tv.brightness",
            onDone = commit,
        )
        if (shader == GlyphTvShader.CRT) {
            CrtSlider(
                label = stringResource(R.string.settings_crt_scanlines, percent(tv.scanlines)),
                subtitle = stringResource(R.string.settings_crt_scanlines_desc),
                value = tv.scanlines,
                default = TV_DEFAULTS.scanlines,
                onChange = { drag(tv.copy(scanlines = it)) },
                linkKey = "tv.scanlines",
                onDone = commit,
            )
            CrtSlider(
                label = stringResource(R.string.settings_crt_mask, percent(tv.mask)),
                subtitle = stringResource(R.string.settings_crt_mask_desc),
                value = tv.mask,
                default = TV_DEFAULTS.mask,
                onChange = { drag(tv.copy(mask = it)) },
                linkKey = "tv.mask",
                onDone = commit,
            )
            CrtSlider(
                label = stringResource(R.string.settings_crt_glow, percent(tv.glow)),
                subtitle = stringResource(R.string.settings_crt_glow_desc),
                value = tv.glow,
                default = TV_DEFAULTS.glow,
                onChange = { drag(tv.copy(glow = it)) },
                linkKey = "tv.glow",
                onDone = commit,
            )
            CrtSlider(
                label = stringResource(R.string.settings_crt_sharpness, percent(tv.sharpness)),
                subtitle = stringResource(R.string.settings_crt_sharpness_desc),
                value = tv.sharpness,
                default = TV_DEFAULTS.sharpness,
                onChange = { drag(tv.copy(sharpness = it)) },
                linkKey = "tv.sharpness",
                onDone = commit,
            )
        }
        CrtSlider(
            label = stringResource(R.string.settings_crt_chromatic, percent(tv.chromatic)),
            subtitle = stringResource(R.string.settings_crt_chromatic_desc),
            value = tv.chromatic,
            default = TV_DEFAULTS.chromatic,
            onChange = { drag(tv.copy(chromatic = it)) },
            linkKey = "tv.chromatic",
            onDone = commit,
        )
        if (shader == GlyphTvShader.ROYALE) {
            RoyaleSliders(tv, density, drag, commit)
        }
        CrtSlider(
            label = stringResource(R.string.settings_crt_curve_top, tv.curveTopDeg),
            subtitle = stringResource(R.string.settings_crt_curve_top_desc),
            value = tv.curveTopDeg,
            default = TV_DEFAULTS.curveTopDeg,
            range = 0f..GlyphTvTuning.MAX_CURVE_DEG,
            onChange = { drag(tv.copy(curveTopDeg = it)) },
            linkKey = "tv.curveTopDeg",
            onDone = commit,
        )
        CrtSlider(
            label = stringResource(R.string.settings_crt_curve_side, tv.curveSideDeg),
            subtitle = stringResource(R.string.settings_crt_curve_side_desc),
            value = tv.curveSideDeg,
            default = TV_DEFAULTS.curveSideDeg,
            range = 0f..GlyphTvTuning.MAX_CURVE_DEG,
            onChange = { drag(tv.copy(curveSideDeg = it)) },
            linkKey = "tv.curveSideDeg",
            onDone = commit,
        )
        val tilts = -GlyphTvTuning.MAX_TILT_DEG..
            GlyphTvTuning.MAX_TILT_DEG
        CrtSlider(
            label = stringResource(R.string.settings_crt_tilt_side, tv.tiltSideDeg),
            subtitle = stringResource(R.string.settings_crt_tilt_side_desc),
            value = tv.tiltSideDeg,
            default = TV_DEFAULTS.tiltSideDeg,
            range = tilts,
            onChange = { drag(tv.copy(tiltSideDeg = it)) },
            linkKey = "tv.tiltSideDeg",
            onDone = commit,
        )
        CrtSlider(
            label = stringResource(R.string.settings_crt_tilt_top, tv.tiltTopDeg),
            subtitle = stringResource(R.string.settings_crt_tilt_top_desc),
            value = tv.tiltTopDeg,
            default = TV_DEFAULTS.tiltTopDeg,
            range = tilts,
            onChange = { drag(tv.copy(tiltTopDeg = it)) },
            linkKey = "tv.tiltTopDeg",
            onDone = commit,
        )
        CrtSlider(
            label = stringResource(R.string.settings_crt_bezel, percent(tv.bezel)),
            subtitle = stringResource(R.string.settings_crt_bezel_desc),
            value = tv.bezel,
            default = TV_DEFAULTS.bezel,
            onChange = { drag(tv.copy(bezel = it)) },
            linkKey = "tv.bezel",
            onDone = commit,
        )
        // No music link yet: a picture that grows and shrinks with the beat
        // has not been asked for (see VisualLinkTarget).
        CrtSlider(
            label = stringResource(R.string.settings_crt_zoom, percent(tv.zoom)),
            subtitle = stringResource(R.string.settings_crt_zoom_desc),
            value = tv.zoom,
            default = TV_DEFAULTS.zoom,
            range = GlyphTvTuning.ZOOM_MIN..GlyphTvTuning.ZOOM_MAX,
            onChange = { drag(tv.copy(zoom = it)) },
            onDone = commit,
        )
        CrtSlider(
            label = stringResource(R.string.settings_crt_corners, percent(tv.corners)),
            subtitle = null,
            value = tv.corners,
            default = TV_DEFAULTS.corners,
            onChange = { drag(tv.copy(corners = it)) },
            linkKey = "tv.corners",
            onDone = commit,
        )
        CrtSlider(
            label = stringResource(R.string.settings_crt_vignette, percent(tv.vignette)),
            subtitle = stringResource(R.string.settings_crt_vignette_desc),
            value = tv.vignette,
            default = TV_DEFAULTS.vignette,
            onChange = { drag(tv.copy(vignette = it)) },
            linkKey = "tv.vignette",
            onDone = commit,
        )
        CrtSlider(
            label = stringResource(R.string.settings_crt_glare, percent(tv.glare)),
            subtitle = stringResource(R.string.settings_crt_glare_desc),
            value = tv.glare,
            default = TV_DEFAULTS.glare,
            onChange = { drag(tv.copy(glare = it)) },
            linkKey = "tv.glare",
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
                prefs.setAppTvTuning(tv.reset(shader, tf.monochrome.android.glyph.engine.CrtPresets.DEFAULT.tuning))
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
        default = TV_DEFAULTS.royaleTriadPx(density).toFloat(),
        range = triads.first.toFloat()..triads.last.toFloat(),
        steps = triads.last - triads.first - 1,
        onChange = { drag(tv.copy(royaleTriad = it.roundToInt())) },
        onDone = commit,
    )
    CrtSlider(
        label = stringResource(R.string.settings_crt_mask_strength, percent(tv.royaleMaskStrength)),
        subtitle = stringResource(R.string.settings_crt_mask_strength_desc),
        value = tv.royaleMaskStrength,
        default = TV_DEFAULTS.royaleMaskStrength,
        onChange = { drag(tv.copy(royaleMaskStrength = it)) },
        linkKey = "tv.royaleMaskStrength",
        onDone = commit,
    )
    CrtSlider(
        label = stringResource(R.string.settings_crt_beam_width, percent(tv.royaleBeamWidth)),
        subtitle = stringResource(R.string.settings_crt_beam_width_desc),
        value = tv.royaleBeamWidth,
        default = TV_DEFAULTS.royaleBeamWidth,
        onChange = { drag(tv.copy(royaleBeamWidth = it)) },
        linkKey = "tv.royaleBeamWidth",
        onDone = commit,
    )
    CrtSlider(
        label = stringResource(R.string.settings_crt_beam_shape, percent(tv.royaleBeamShape)),
        subtitle = stringResource(R.string.settings_crt_beam_shape_desc),
        value = tv.royaleBeamShape,
        default = TV_DEFAULTS.royaleBeamShape,
        onChange = { drag(tv.copy(royaleBeamShape = it)) },
        linkKey = "tv.royaleBeamShape",
        onDone = commit,
    )
    CrtSlider(
        label = stringResource(R.string.settings_crt_halation, percent(tv.royaleHalation)),
        subtitle = stringResource(R.string.settings_crt_halation_desc),
        value = tv.royaleHalation,
        default = TV_DEFAULTS.royaleHalation,
        onChange = { drag(tv.copy(royaleHalation = it)) },
        linkKey = "tv.royaleHalation",
        onDone = commit,
    )
    CrtSlider(
        label = stringResource(R.string.settings_crt_diffusion, percent(tv.royaleDiffusion)),
        subtitle = stringResource(R.string.settings_crt_diffusion_desc),
        value = tv.royaleDiffusion,
        default = TV_DEFAULTS.royaleDiffusion,
        onChange = { drag(tv.copy(royaleDiffusion = it)) },
        linkKey = "tv.royaleDiffusion",
        onDone = commit,
    )
    CrtSlider(
        label = stringResource(R.string.settings_crt_bloom, percent(tv.royaleBloom)),
        subtitle = stringResource(R.string.settings_crt_bloom_desc),
        value = tv.royaleBloom,
        default = TV_DEFAULTS.royaleBloom,
        onChange = { drag(tv.copy(royaleBloom = it)) },
        linkKey = "tv.royaleBloom",
        onDone = commit,
    )
    CrtSlider(
        label = stringResource(R.string.settings_crt_edge, percent(tv.royaleBorder)),
        subtitle = stringResource(R.string.settings_crt_edge_desc),
        value = tv.royaleBorder,
        default = TV_DEFAULTS.royaleBorder,
        onChange = { drag(tv.copy(royaleBorder = it)) },
        linkKey = "tv.royaleBorder",
        onDone = commit,
    )
}

/** One of [CrtSliders]: its label, a line on what it does, and the slider, moving the app as it is dragged. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CrtSlider(
    label: String,
    subtitle: String?,
    value: Float,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    /** What the reset beside the label puts this slider back to. */
    default: Float? = null,
    /**
     * The music link key ("tv.strength"), as on the Studio's own sliders: a
     * note beside the label, green while linked, and a press and hold on the
     * label opens the link sheet. Only where a [LocalMusicLinkHost] is
     * provided: Settings and the Studio both do.
     */
    linkKey: String? = null,
) {
    val linkHost = if (linkKey != null) LocalMusicLinkHost.current else null
    val linked = linkHost != null && linkKey != null && linkHost.isLinked(linkKey)
    val moveLabel = stringResource(R.string.music_link_move_with_music)
    Spacer(modifier = Modifier.height(8.dp))
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .then(
                    if (linkHost != null && linkKey != null) {
                        Modifier
                            .combinedClickable(
                                onClick = {},
                                onLongClickLabel = moveLabel,
                                onLongClick = { linkHost.open(linkKey) },
                            )
                            .semantics {
                                customActions = listOf(CustomAccessibilityAction(moveLabel) { linkHost.open(linkKey); true })
                            }
                    } else {
                        Modifier
                    },
                ),
        )
        if (default != null) {
            tf.monochrome.android.ui.settings.crt.SliderReset(value, default, label) {
                onChange(default)
                onDone()
            }
        }
        if (linkHost != null && linkKey != null) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = moveLabel,
                tint = if (linked) musicLinkColor() else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier
                    .clickable(onClickLabel = moveLabel) { linkHost.open(linkKey) }
                    .padding(6.dp)
                    .size(18.dp),
            )
        }
    }
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
    if (linked && linkHost != null && linkKey != null) {
        // The link's reach along the track, and where the music has it now.
        LinkedTrackMarker(linkKey, linkHost, range.start, range.endInclusive)
    }
}

/** Every CRT slider's default, for its reset: the CRT effect's own default look. */
private val TV_DEFAULTS = tf.monochrome.android.glyph.engine.CrtPresets.DEFAULT.tuning

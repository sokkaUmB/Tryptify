package tf.monochrome.android.ui.mixer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tf.monochrome.android.R
import tf.monochrome.android.audio.dsp.DspEngineManager
import tf.monochrome.android.audio.dsp.model.KnobLink
import tf.monochrome.android.audio.dsp.model.KnobSource
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.domain.model.LinkShape
import tf.monochrome.android.ui.musiclinks.LinkTargetInfo
import tf.monochrome.android.ui.musiclinks.MusicLinkEditor
import tf.monochrome.android.ui.musiclinks.MusicLinkHost
import tf.monochrome.android.ui.musiclinks.MusicLinkOptionsSheet
import tf.monochrome.android.ui.musiclinks.followsText
import tf.monochrome.android.ui.settings.UndoBar

/** Where the music has each linked knob this frame. Read in draw blocks only. */
@Stable
class KnobLinkLive {
    var values: Map<String, Float> by mutableStateOf(emptyMap())
        internal set
    var strengths: Map<String, Float> by mutableStateOf(emptyMap())
        internal set
}

/** A knob link being made or changed. It plays while it is shaped; Cancel puts back [previous]. */
data class KnobDraft(
    val link: KnobLink,
    val isNew: Boolean,
    val previous: KnobLink?,
    val def: ParamDef,
)

/**
 * Making, changing and removing the mixer's knob links. A draft is live from
 * the first moment: the knob moves with the music while the listener shapes
 * the link, which is the only way to judge one by ear.
 */
class KnobLinkEditing(private val dsp: DspEngineManager) {
    val links: StateFlow<List<KnobLink>> = dsp.knobLinks
    val live = KnobLinkLive()

    private val _draft = MutableStateFlow<KnobDraft?>(null)
    val draft: StateFlow<KnobDraft?> = _draft.asStateFlow()

    /** The knob under [key] and its value now, or null when there is no such knob. */
    fun target(key: String): Pair<ParamDef, Float>? {
        val (bus, slot, param) = parse(key) ?: return null
        val plugin = dsp.buses.value.firstOrNull { it.index == bus }?.plugins?.getOrNull(slot) ?: return null
        val def = getParamDefs(plugin.type).getOrNull(param) ?: return null
        return def to (plugin.parameters[param] ?: def.default)
    }

    /** A knob that clicks between steps (a type, a mode, a switch) cannot glide with the music. */
    fun canLink(def: ParamDef): Boolean = def.steps == null

    fun linkFor(key: String): KnobLink? = links.value.firstOrNull { it.key == key }

    fun start(key: String) {
        val (bus, slot, param) = parse(key) ?: return
        val (def, current) = target(key) ?: return
        if (!canLink(def)) return
        val saved = linkFor(key)
        val draft = if (saved != null) {
            KnobDraft(saved, isNew = false, previous = saved, def = def)
        } else {
            val (lowest, highest) = LinkShape.startingRange(current, def.min, def.max)
            KnobDraft(
                KnobLink(dsp.newKnobLinkId(), bus, slot, param, KnobSource.SONG, LinkShape(lowest = lowest, highest = highest)),
                isNew = true,
                previous = null,
                def = def,
            )
        }
        _draft.value = draft
        dsp.setKnobLinkPreview(draft.link.key)
        dsp.putKnobLink(draft.link.copy(paused = false))
    }

    fun update(shape: LinkShape) {
        val d = _draft.value ?: return
        val next = d.copy(link = d.link.copy(shape = shape.clamped(d.def.min, d.def.max)))
        _draft.value = next
        dsp.putKnobLink(next.link.copy(paused = false))
    }

    fun setSource(source: KnobSource) {
        val d = _draft.value ?: return
        val next = d.copy(link = d.link.copy(source = source))
        _draft.value = next
        dsp.putKnobLink(next.link.copy(paused = false))
    }

    /** Keeps the draft, paused again if it was. */
    fun commit() {
        val d = _draft.value ?: return
        _draft.value = null
        dsp.setKnobLinkPreview(null)
        if (d.link.paused) dsp.putKnobLink(d.link)
    }

    fun cancel() {
        val d = _draft.value ?: return
        _draft.value = null
        dsp.setKnobLinkPreview(null)
        if (d.previous != null) dsp.putKnobLink(d.previous) else dsp.removeKnobLink(d.link.key)
    }

    /**
     * Removes [key]'s link and hands back what it was, for an undo: the saved
     * link, not the draft the editor was playing.
     */
    fun remove(key: String): KnobLink? {
        val d = _draft.value?.takeIf { it.link.key == key }
        if (d != null) {
            _draft.value = null
            dsp.setKnobLinkPreview(null)
        }
        val gone = dsp.removeKnobLink(key)
        return d?.previous ?: gone
    }

    fun restore(link: KnobLink) = dsp.putKnobLink(link)

    fun setPaused(key: String, paused: Boolean) = dsp.setKnobLinkPaused(key, paused)

    /** One poll of the knobs' values, with the meters: once a display frame. */
    fun poll() {
        if (links.value.isEmpty()) {
            if (live.values.isNotEmpty()) live.values = emptyMap()
            if (live.strengths.isNotEmpty()) live.strengths = emptyMap()
            return
        }
        val (values, strengths) = dsp.knobLinkValues()
        live.values = values
        live.strengths = strengths
    }

    private fun parse(key: String): Triple<Int, Int, Int>? {
        val parts = key.split(':').mapNotNull { it.toIntOrNull() }
        return if (parts.size == 3) Triple(parts[0], parts[1], parts[2]) else null
    }
}

/** The link host for the FX chain's knobs. */
@Composable
fun rememberKnobLinkHost(editing: KnobLinkEditing, onOpen: (String) -> Unit): MusicLinkHost {
    val links = editing.links.collectAsStateWithLifecycle()
    val keys = remember { derivedStateOf { links.value.map { it.key }.toSet() } }
    val ranges = remember { derivedStateOf { links.value.associate { it.key to (it.shape.lowest..it.shape.highest) } } }
    val open by rememberUpdatedState(onOpen)
    return remember(editing) { MusicLinkHost(keys, { editing.live.values }, ranges, { open(it) }) }
}

/** What the mixer's link overlay has open. */
@Stable
class KnobLinkUi {
    var sheetFor by mutableStateOf<String?>(null)
    var removed by mutableStateOf<KnobLink?>(null)
}

/**
 * The mixer's link sheet, editor and undo bar, drawn over the console in its
 * own window beside [hazeState]'s source (the mixer's backdrop).
 */
@Composable
fun BoxScope.KnobLinkOverlay(
    ui: KnobLinkUi,
    editing: KnobLinkEditing,
    tap: SpectrumAnalyzerTap,
    hazeState: HazeState,
) {
    val links by editing.links.collectAsStateWithLifecycle()
    val draft by editing.draft.collectAsStateWithLifecycle()

    val key = ui.sheetFor
    val target = key?.let { editing.target(it) }
    if (key != null && target != null && draft == null) {
        val (def, current) = target
        val saved = links.firstOrNull { it.key == key }
        MusicLinkOptionsSheet(
            title = def.name,
            valueText = formatParamValue(current, def),
            linked = saved != null,
            paused = saved?.paused == true,
            followsText = saved?.let { followsText(it.shape, it.paused) },
            cannotReason = if (editing.canLink(def)) null else stringResource(R.string.music_link_stepped),
            hazeState = hazeState,
            onMove = {
                editing.start(key)
                ui.sheetFor = null
            },
            onChange = {
                editing.start(key)
                ui.sheetFor = null
            },
            onTogglePause = {
                editing.setPaused(key, !(saved?.paused ?: false))
                ui.sheetFor = null
            },
            onRemove = {
                ui.removed = editing.remove(key)
                ui.sheetFor = null
            },
            onDismiss = { ui.sheetFor = null },
        )
    }

    val d = draft
    if (d != null) {
        // The song's spectrum, for the band picture; held while the editor is up.
        androidx.lifecycle.compose.LifecycleStartEffect(tap) {
            tap.acquire()
            onStopOrDispose { tap.release() }
        }
        val spectrum = tap.spectrumBins.collectAsStateWithLifecycle()
        val linkKey = d.link.key
        MusicLinkEditor(
            title = stringResource(R.string.music_link_editor_title, d.def.name),
            target = LinkTargetInfo(d.def.name, d.def.min, d.def.max) { formatParamValue(it, d.def) },
            shape = d.link.shape,
            onShapeChange = editing::update,
            spectrum = spectrum,
            strength = { editing.live.strengths[linkKey] ?: 0f },
            note = null,
            isNew = d.isNew,
            hazeState = hazeState,
            onDone = editing::commit,
            onCancel = editing::cancel,
            onRemove = if (d.isNew) null else ({ ui.removed = editing.remove(linkKey) }),
            listensTo = { SourcePicker(d.link.source, editing::setSource) },
        )
    }

    val removed = ui.removed
    if (removed != null && draft == null && ui.sheetFor == null) {
        LaunchedEffect(removed) {
            delay(6000)
            if (ui.removed == removed) ui.removed = null
        }
        val name = editing.target(removed.key)?.first?.name ?: ""
        UndoBar(
            text = stringResource(R.string.music_link_removed, name),
            hazeState = hazeState,
            onUndo = {
                editing.restore(removed)
                ui.removed = null
            },
        )
    }
}

@Composable
private fun SourcePicker(source: KnobSource, onSource: (KnobSource) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KnobSource.entries.forEach { s ->
                GlassChoiceChip(
                    label = stringResource(if (s == KnobSource.SONG) R.string.music_link_listens_song else R.string.music_link_listens_bus),
                    selected = source == s,
                    accent = accent,
                    height = 40.dp,
                    onClick = { onSource(s) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            stringResource(if (source == KnobSource.SONG) R.string.music_link_listens_song_desc else R.string.music_link_listens_bus_desc),
            style = MaterialTheme.typography.bodySmall,
            color = LocalContentColor.current.copy(alpha = 0.8f),
        )
    }
}

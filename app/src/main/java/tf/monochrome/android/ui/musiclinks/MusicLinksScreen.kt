package tf.monochrome.android.ui.musiclinks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import tf.monochrome.android.R
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.MusicLinksRepository
import tf.monochrome.android.domain.model.LinkArea
import tf.monochrome.android.domain.model.StudioLook
import tf.monochrome.android.domain.model.VisualLink
import tf.monochrome.android.domain.model.VisualLinkTargets
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.components.GlassPill
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.navigateTool
import tf.monochrome.android.ui.navigation.popBackStackSafe
import tf.monochrome.android.ui.player.rememberVisualLinkDriver
import tf.monochrome.android.ui.settings.StudioLinkUi
import tf.monochrome.android.ui.settings.UndoBar
import tf.monochrome.android.ui.settings.VisualLinkEditing
import tf.monochrome.android.ui.settings.fullLabel
import tf.monochrome.android.ui.settings.info
import tf.monochrome.android.ui.settings.needsText
import javax.inject.Inject

@HiltViewModel
class MusicLinksViewModel @Inject constructor(
    repository: MusicLinksRepository,
    preferences: PreferencesManager,
    val spectrumAnalyzer: SpectrumAnalyzerTap,
    private val dsp: tf.monochrome.android.audio.dsp.DspEngineManager,
) : ViewModel() {
    val editing = VisualLinkEditing(viewModelScope, repository)

    /** The mixer's knob links, each with the name of its effect, knob and bus. */
    val knobLinks: StateFlow<List<KnobLinkRowModel>> =
        kotlinx.coroutines.flow.combine(dsp.knobLinks, dsp.buses) { links, buses ->
            links.mapNotNull { link ->
                val bus = buses.firstOrNull { it.index == link.bus } ?: return@mapNotNull null
                val plugin = bus.plugins.getOrNull(link.slot) ?: return@mapNotNull null
                val def = tf.monochrome.android.ui.mixer.getParamDefs(plugin.type).getOrNull(link.param)
                    ?: return@mapNotNull null
                KnobLinkRowModel(link, "${plugin.displayName} · ${def.name}", bus.name, def)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setKnobPaused(key: String, paused: Boolean) = dsp.setKnobLinkPaused(key, paused)

    fun removeKnob(key: String): tf.monochrome.android.audio.dsp.model.KnobLink? = dsp.removeKnobLink(key)

    fun restoreKnob(link: tf.monochrome.android.audio.dsp.model.KnobLink) = dsp.putKnobLink(link)

    /** The listener's own look: where a new link rests, and what a setting needs to show. */
    val look: StateFlow<StudioLook> =
        kotlinx.coroutines.flow.combine(
            kotlinx.coroutines.flow.combine(
                preferences.lyricsFx,
                preferences.playerGlass,
                preferences.miniPlayerGlass,
                preferences.appTvTuning,
                preferences.appTvShader,
            ) { lyrics, player, panels, tv, tvLook -> StudioLook(lyrics, player, panels, tv, tvLook) },
            preferences.ambientVisualizer,
        ) { look, ambient -> look.copy(ambient = ambient) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StudioLook())
}

/** A knob link as the Music links page lists it. */
data class KnobLinkRowModel(
    val link: tf.monochrome.android.audio.dsp.model.KnobLink,
    val title: String,
    val busName: String,
    val def: tf.monochrome.android.ui.mixer.ParamDef,
)

/**
 * Every music link in one place: a master switch that pauses them all
 * without forgetting any, each link with what it follows and how far it
 * goes, and pause, change and remove on each. Links are made where their
 * setting lives — the Visual Studio, the mixer — so an empty page says where
 * to go rather than offering a list of settings out of context.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicLinksScreen(
    navController: NavController,
    viewModel: MusicLinksViewModel = hiltViewModel(),
) {
    val editing = viewModel.editing
    val links by editing.links.collectAsStateWithLifecycle()
    val allPaused by editing.allPaused.collectAsStateWithLifecycle()
    val draft by editing.draft.collectAsStateWithLifecycle()
    val look by viewModel.look.collectAsStateWithLifecycle()
    val knobs by viewModel.knobLinks.collectAsStateWithLifecycle()
    val ui = remember { StudioLinkUi() }
    // The knob link just removed and its name, while its undo is on offer.
    var removedKnob by remember { mutableStateOf<Pair<tf.monochrome.android.audio.dsp.model.KnobLink, String>?>(null) }

    // Only the link in the editor runs here: there is no preview on this
    // page, and the meter is all that needs it.
    val live = rememberVisualLinkDriver(
        tap = viewModel.spectrumAnalyzer,
        links = listOfNotNull(draft?.link),
        active = draft != null,
        delayMs = 0f,
    )

    val backdrop = rememberHazeState()
    val overlay = rememberHazeState()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(overlay)) {
            LinksBackdrop(Modifier.fillMaxSize().hazeSource(backdrop))
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.music_links_title)) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStackSafe() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp + LocalBottomChromeInset.current + navBar),
                ) {
                    item(key = "intro") {
                        Text(
                            stringResource(R.string.music_links_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                    item(key = "master") {
                        LinkCard(backdrop) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.music_links_on),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        stringResource(R.string.music_links_on_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = LocalContentColor.current.copy(alpha = 0.8f),
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Switch(checked = !allPaused, onCheckedChange = { editing.setAllPaused(!it) })
                            }
                        }
                    }
                    if (links.isEmpty() && knobs.isEmpty()) {
                        item(key = "empty") {
                            LinkCard(backdrop) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(
                                        Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = musicLinkColor(),
                                        modifier = Modifier.size(32.dp),
                                    )
                                    Text(
                                        stringResource(R.string.music_links_empty_title),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        stringResource(R.string.music_links_empty_desc),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = LocalContentColor.current.copy(alpha = 0.85f),
                                    )
                                    GlassPill(
                                        onClick = { navController.navigateTool(Screen.LyricsFxStudio) },
                                        hazeState = backdrop,
                                        height = 48.dp,
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    ) {
                                        Text(stringResource(R.string.music_links_open_studio), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                    // One group per part of the app, in the Studio's order.
                    for (area in LinkArea.entries) {
                        val inArea = links.filter { VisualLinkTargets.byKey(it.target)?.area == area }
                        if (inArea.isEmpty()) continue
                        item(key = "header:" + area.prefix) {
                            Text(
                                stringResource(if (area == LinkArea.LYRICS) R.string.music_links_group_lyrics else area.title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 2.dp),
                            )
                        }
                        items(inArea, key = { it.target }) { link ->
                            LinkRow(
                                link = link,
                                allPaused = allPaused,
                                hazeState = backdrop,
                                onPause = { editing.setPaused(link.target, !link.paused) },
                                onChange = {
                                    val target = VisualLinkTargets.byKey(link.target) ?: return@LinkRow
                                    editing.start(link.target, target.read(look))
                                },
                                onRemove = { ui.removed = editing.remove(link.target) },
                            )
                        }
                    }
                    if (knobs.isNotEmpty()) {
                        item(key = "mixer_header") {
                            Text(
                                stringResource(R.string.music_links_group_mixer),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 2.dp),
                            )
                        }
                        items(knobs, key = { "knob:" + it.link.key }) { row ->
                            KnobRow(
                                row = row,
                                allPaused = allPaused,
                                hazeState = backdrop,
                                onPause = { viewModel.setKnobPaused(row.link.key, !row.link.paused) },
                                onRemove = { viewModel.removeKnob(row.link.key)?.let { removedKnob = it to row.title } },
                            )
                        }
                    }
                    item(key = "mixer_note") {
                        Text(
                            stringResource(R.string.music_links_mixer_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        )
                    }
                }
            }
        }

        val d = draft
        val target = d?.let { VisualLinkTargets.byKey(it.link.target) }
        if (d != null && target != null) {
            val spectrum = viewModel.spectrumAnalyzer.spectrumBins.collectAsStateWithLifecycle()
            val key = d.link.target
            val info = target.info()
            MusicLinkEditor(
                title = stringResource(R.string.music_link_editor_title, info.label),
                target = info,
                shape = d.link.shape,
                onShapeChange = editing::update,
                spectrum = spectrum,
                strength = { live.strengths[key] ?: 0f },
                note = target.needsText(look),
                isNew = d.isNew,
                hazeState = overlay,
                onDone = editing::commit,
                onCancel = editing::cancel,
                onRemove = { ui.removed = editing.remove(key) },
                listensTo = { WhatYouHearRow() },
            )
        }

        val goneKnob = removedKnob
        if (goneKnob != null && d == null) {
            androidx.compose.runtime.LaunchedEffect(goneKnob) {
                kotlinx.coroutines.delay(6000)
                if (removedKnob == goneKnob) removedKnob = null
            }
            UndoBar(
                text = stringResource(R.string.music_link_removed, goneKnob.second),
                hazeState = overlay,
                onUndo = {
                    viewModel.restoreKnob(goneKnob.first)
                    removedKnob = null
                },
            )
        }

        val removed = ui.removed
        if (removed != null && d == null) {
            androidx.compose.runtime.LaunchedEffect(removed) {
                kotlinx.coroutines.delay(6000)
                if (ui.removed == removed) ui.removed = null
            }
            val label = VisualLinkTargets.byKey(removed.target)?.fullLabel() ?: removed.target
            UndoBar(
                text = stringResource(R.string.music_link_removed, label),
                hazeState = overlay,
                onUndo = {
                    editing.restore(removed)
                    ui.removed = null
                },
            )
        }
    }
}

@Composable
private fun LinkCard(hazeState: HazeState, content: @Composable () -> Unit) {
    GlassPanel(
        hazeState = hazeState,
        glass = LocalMiniPlayerGlass.current,
        avoidNavigationBar = false,
        // In a scrolling list: a swipe on the pane scrolls it.
        blockTouchesBelow = false,
    ) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) { content() }
    }
}

@Composable
private fun LinkRow(
    link: VisualLink,
    allPaused: Boolean,
    hazeState: HazeState,
    onPause: () -> Unit,
    onChange: () -> Unit,
    onRemove: () -> Unit,
) {
    val target = VisualLinkTargets.byKey(link.target) ?: return
    val green = musicLinkColor()
    LinkCard(hazeState) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = if (link.paused || allPaused) LocalContentColor.current.copy(alpha = 0.5f) else green,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(target.label), style = MaterialTheme.typography.titleMedium)
                    Text(
                        followsText(link.shape, link.paused) + " · " + stringResource(
                            R.string.music_link_range_value,
                            target.format(link.shape.lowest),
                            target.format(link.shape.highest),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.8f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(onClick = onChange, hazeState = hazeState, height = 44.dp, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.music_links_change), fontWeight = FontWeight.Bold)
                }
                GlassPill(
                    onClick = onPause,
                    hazeState = hazeState,
                    height = 44.dp,
                    modifier = Modifier.weight(1f),
                    onClickLabel = stringResource(if (link.paused) R.string.music_link_resume else R.string.music_link_pause),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (link.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(if (link.paused) R.string.music_link_resume else R.string.music_link_pause),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
                GlassPill(
                    onClick = onRemove,
                    hazeState = hazeState,
                    height = 44.dp,
                    onClickLabel = stringResource(R.string.music_link_remove),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.music_link_remove),
                        modifier = Modifier.padding(horizontal = 14.dp).size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun KnobRow(
    row: KnobLinkRowModel,
    allPaused: Boolean,
    hazeState: HazeState,
    onPause: () -> Unit,
    onRemove: () -> Unit,
) {
    val link = row.link
    val green = musicLinkColor()
    LinkCard(hazeState) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = if (link.paused || allPaused) LocalContentColor.current.copy(alpha = 0.5f) else green,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        row.busName + " · " + followsText(link.shape, link.paused) + " · " + stringResource(
                            R.string.music_link_range_value,
                            tf.monochrome.android.ui.mixer.formatParamValue(link.shape.lowest, row.def),
                            tf.monochrome.android.ui.mixer.formatParamValue(link.shape.highest, row.def),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.8f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(
                    onClick = onPause,
                    hazeState = hazeState,
                    height = 44.dp,
                    modifier = Modifier.weight(1f),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (link.paused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(if (link.paused) R.string.music_link_resume else R.string.music_link_pause),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
                GlassPill(onClick = onRemove, hazeState = hazeState, height = 44.dp, onClickLabel = stringResource(R.string.music_link_remove)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.music_link_remove),
                        modifier = Modifier.padding(horizontal = 14.dp).size(20.dp),
                    )
                }
            }
        }
    }
}

/** Two soft glows on the page colour: something real for the cards' glass to frost. */
@Composable
fun LinksBackdrop(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val green = musicLinkColor()
    Box(modifier.background(scheme.background)) {
        Box(
            Modifier
                .offset(x = (-80).dp, y = 60.dp)
                .size(360.dp)
                .background(Brush.radialGradient(listOf(scheme.primary.copy(alpha = 0.32f), Color.Transparent)), CircleShape),
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 90.dp, y = (-120).dp)
                .size(380.dp)
                .background(Brush.radialGradient(listOf(green.copy(alpha = 0.22f), Color.Transparent)), CircleShape),
        )
    }
}

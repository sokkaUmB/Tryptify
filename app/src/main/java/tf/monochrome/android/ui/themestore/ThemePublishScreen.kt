package tf.monochrome.android.ui.themestore

import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tf.monochrome.android.R
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.data.repository.StoreError
import tf.monochrome.android.data.repository.StorePack
import tf.monochrome.android.data.repository.ThemePackRepository
import tf.monochrome.android.data.repository.ThemeStoreRepository
import tf.monochrome.android.domain.model.ThemePack
import tf.monochrome.android.domain.model.ThemePart
import tf.monochrome.android.ui.components.GlassPill
import tf.monochrome.android.ui.components.LocalGlassBackdrop
import tf.monochrome.android.ui.mixer.GlassChoiceChip
import tf.monochrome.android.ui.musiclinks.LinksBackdrop
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.navigateSafe
import tf.monochrome.android.ui.navigation.popBackStackSafe
import javax.inject.Inject

data class PublishState(
    val look: ThemePack? = null,
    val keep: Set<ThemePart> = emptySet(),
    val busy: Boolean = false,
    val error: StoreError? = null,
    val published: StorePack? = null,
)

@HiltViewModel
class ThemePublishViewModel @Inject constructor(
    private val packs: ThemePackRepository,
    private val store: ThemeStoreRepository,
    val spectrumAnalyzer: SpectrumAnalyzerTap,
) : ViewModel() {
    private val _state = MutableStateFlow(PublishState())
    val state: StateFlow<PublishState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val look = packs.capture()
            _state.value = PublishState(look = look, keep = look.parts)
        }
    }

    fun toggle(part: ThemePart, on: Boolean) {
        val s = _state.value
        _state.value = s.copy(keep = if (on) s.keep + part else s.keep - part)
    }

    fun publish(name: String, description: String, listed: Boolean) {
        val s = _state.value
        val look = s.look ?: return
        if (s.busy || name.isBlank() || s.keep.isEmpty()) return
        _state.value = s.copy(busy = true, error = null)
        viewModelScope.launch {
            store.publish(look.only(s.keep), name, description, listed)
                .onSuccess { _state.value = _state.value.copy(busy = false, published = it) }
                .onFailure { e -> _state.value = _state.value.copy(busy = false, error = e as? StoreError ?: StoreError.Network(e)) }
        }
    }
}

/**
 * "Share my look": the look the app has right now, previewed, with a switch
 * for each part, a name, and whether it shows in the store or only opens by
 * its code. Nothing personal goes up — see ThemePackCodec.forSharing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemePublishScreen(
    navController: NavController,
    viewModel: ThemePublishViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var listed by rememberSaveable { mutableStateOf(true) }
    var face by rememberSaveable { mutableStateOf(PreviewFace.PANELS) }
    val backdrop = rememberHazeState()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val accent = MaterialTheme.colorScheme.primary
    val copiedText = stringResource(R.string.theme_pack_code_copied)

    Box(Modifier.fillMaxSize()) {
        LinksBackdrop(Modifier.fillMaxSize().hazeSource(backdrop))
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(R.string.theme_publish_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStackSafe() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
            val look = state.look
            val published = state.published
            when {
                look == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                published != null -> Column(Modifier.padding(top = 8.dp)) {
                    PackCard(backdrop) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = accent, modifier = Modifier.size(40.dp))
                            Text(stringResource(R.string.theme_publish_done), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                            Text(
                                stringResource(if (published.listed) R.string.theme_publish_done_listed else R.string.theme_publish_done_unlisted),
                                style = MaterialTheme.typography.bodyMedium,
                                color = LocalContentColor.current.copy(alpha = 0.85f),
                            )
                            Text(stringResource(R.string.theme_pack_code_label), style = MaterialTheme.typography.bodySmall)
                            Text(published.shareCode, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                            CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    GlassPill(
                                        onClick = {
                                            copyText(context, published.shareCode)
                                            Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                                        },
                                        hazeState = backdrop,
                                        height = 50.dp,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(stringResource(R.string.theme_pack_copy_code), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    GlassPill(
                                        onClick = {
                                            navController.popBackStackSafe()
                                            navController.navigateSafe(Screen.ThemePackDetail.createRoute(published.id))
                                        },
                                        hazeState = backdrop,
                                        height = 50.dp,
                                        glass = LocalMiniPlayerGlass.current.copy(tintColor = accent.toArgb()),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(stringResource(R.string.theme_publish_open), fontWeight = FontWeight.ExtraBold)
                                    }
                                }
                            }
                        }
                    }
                }
                else -> CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 32.dp + LocalBottomChromeInset.current + navBar),
                    ) {
                        item(key = "preview") {
                            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    stringResource(R.string.theme_publish_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PreviewFace.entries.forEach { f ->
                                        GlassChoiceChip(
                                            label = PreviewFaceLabel(f),
                                            selected = face == f,
                                            accent = accent,
                                            height = 40.dp,
                                            onClick = { face = f },
                                        )
                                    }
                                }
                                PackPreview(look, face, viewModel.spectrumAnalyzer)
                            }
                        }
                        item(key = "name") {
                            PackCard(backdrop) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = name,
                                        onValueChange = { name = it.take(ThemePack.MAX_NAME) },
                                        singleLine = true,
                                        label = { Text(stringResource(R.string.theme_publish_name)) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        value = description,
                                        onValueChange = { description = it.take(280) },
                                        label = { Text(stringResource(R.string.theme_publish_about)) },
                                        minLines = 2,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                        item(key = "parts") {
                            PackCard(backdrop) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(stringResource(R.string.theme_publish_include), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    ThemePart.entries.filter { it in look.parts }.forEach { part ->
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                                            Icon(partIcon(part), contentDescription = null, modifier = Modifier.size(22.dp))
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(partLabel(part), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                                Text(partDescription(part), style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.8f))
                                            }
                                            Switch(checked = part in state.keep, onCheckedChange = { viewModel.toggle(part, it) })
                                        }
                                    }
                                    if (ThemePart.FONT !in look.parts) {
                                        Text(
                                            stringResource(R.string.theme_publish_font_note),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = LocalContentColor.current.copy(alpha = 0.8f),
                                        )
                                    }
                                    Text(
                                        stringResource(R.string.theme_publish_private_note),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        item(key = "listed") {
                            PackCard(backdrop) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.theme_publish_listed), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                        Text(
                                            stringResource(R.string.theme_publish_listed_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = LocalContentColor.current.copy(alpha = 0.8f),
                                        )
                                    }
                                    Switch(checked = listed, onCheckedChange = { listed = it })
                                }
                            }
                        }
                        item(key = "go") {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.error?.let { e ->
                                    Text(errorText(e), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                                }
                                if (name.isBlank()) {
                                    Text(
                                        stringResource(R.string.theme_publish_needs_name),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                GlassPill(
                                    onClick = { viewModel.publish(name, description, listed) },
                                    hazeState = backdrop,
                                    height = 56.dp,
                                    enabled = name.isNotBlank() && state.keep.isNotEmpty() && !state.busy,
                                    glass = LocalMiniPlayerGlass.current.copy(tintColor = accent.toArgb()),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    if (state.busy) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(22.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text(stringResource(R.string.theme_publish_go), fontWeight = FontWeight.ExtraBold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

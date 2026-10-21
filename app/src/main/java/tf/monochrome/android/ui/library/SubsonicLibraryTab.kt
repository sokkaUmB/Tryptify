package tf.monochrome.android.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import tf.monochrome.android.R
import tf.monochrome.android.data.subsonic.SubsonicAlbumSort
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.ui.components.UnifiedTrackContextMenuHost
import tf.monochrome.android.ui.library.SubsonicLibraryViewModel.Load
import tf.monochrome.android.ui.library.SubsonicLibraryViewModel.Section
import tf.monochrome.android.ui.library.SubsonicLibraryViewModel.View
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.navigateTool
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.settings.settingsTabIndex
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * The "My server" Library page: albums and artists on the listener's
 * Subsonic server, and an album's songs.
 *
 * Built from the Local page's own lists ([AlbumGrid], [ArtistList],
 * [SongList]) so the two read as one library: Material rows at the shared row
 * height, never glass (see docs/ui-invariants.md, List rows).
 */
@Composable
fun SubsonicLibraryTab(
    viewModel: SubsonicLibraryViewModel,
    navController: NavController,
    playerViewModel: PlayerViewModel,
) {
    val serverLoad by viewModel.server.collectAsStateWithLifecycle()
    val server = (serverLoad as? Load.Loaded)?.value
    when {
        serverLoad is Load.Loading -> return
        server == null -> {
            NotConnected(onSetUp = {
                navController.navigateTool(Screen.Settings, Screen.Settings.createRoute(settingsTabIndex("Connections")))
            })
            return
        }
    }
    LaunchedEffect(server) { server?.let(viewModel::onServer) }

    var menuTrack by remember { mutableStateOf<UnifiedTrack?>(null) }
    UnifiedTrackContextMenuHost(
        track = menuTrack,
        onDismissRequest = { menuTrack = null },
        navController = navController,
        playerViewModel = playerViewModel,
    )

    val stack by viewModel.view.collectAsStateWithLifecycle()
    // Back leaves an album or artist before it leaves the page. Registered
    // only while this page is composed, so it never swallows Back elsewhere.
    BackHandler(enabled = stack.size > 1) { viewModel.back() }

    when (val top = stack.last()) {
        View.Index -> Index(viewModel, playerViewModel)
        is View.Artist -> {
            val albums by viewModel.artistAlbums.collectAsStateWithLifecycle()
            Column(Modifier.fillMaxSize()) {
                DetailHeader(title = top.artist.name, subtitle = null, onBack = { viewModel.back() })
                LoadContent(albums, onRetry = { viewModel.back(); viewModel.openArtist(top.artist) }) { list ->
                    AlbumGrid(albums = list, onAlbumClick = viewModel::openAlbum)
                }
            }
        }
        is View.Album -> {
            val tracks by viewModel.albumTracks.collectAsStateWithLifecycle()
            Column(Modifier.fillMaxSize()) {
                val loaded = (tracks as? Load.Loaded)?.value.orEmpty()
                DetailHeader(
                    title = top.album.title,
                    subtitle = top.album.artistName,
                    onBack = { viewModel.back() },
                ) {
                    if (loaded.isNotEmpty()) {
                        IconButton(onClick = { playerViewModel.playUnifiedTrack(loaded.first(), loaded) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.action_play))
                        }
                        IconButton(onClick = { playerViewModel.shufflePlayUnified(loaded) }) {
                            Icon(Icons.Default.Shuffle, contentDescription = stringResource(R.string.action_shuffle))
                        }
                    }
                }
                LoadContent(tracks, onRetry = { viewModel.back(); viewModel.openAlbum(top.album) }) { list ->
                    SongList(
                        tracks = list,
                        onTrackClick = { track, queue -> playerViewModel.playUnifiedTrack(track, queue) },
                        onMoreClick = { menuTrack = it },
                        navController = navController,
                    )
                }
            }
        }
    }
}

@Composable
private fun Index(viewModel: SubsonicLibraryViewModel, playerViewModel: PlayerViewModel) {
    val section by viewModel.section.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val sort by viewModel.albumSort.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MonoDimens.spacingLg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm),
        ) {
            for ((value, label) in listOf(Section.ALBUMS to R.string.filter_albums, Section.ARTISTS to R.string.filter_artists)) {
                tf.monochrome.android.ui.mixer.GlassChoiceChip(
                    label = stringResource(label),
                    selected = section == value,
                    accent = MaterialTheme.colorScheme.primary,
                    onClick = { viewModel.selectSection(value) },
                )
            }
            Spacer(Modifier.weight(1f))
            if (section == Section.ALBUMS) AlbumSortMenu(sort, viewModel::setAlbumSort)
            IconButton(onClick = { viewModel.shuffleAll(playerViewModel::shufflePlayUnified) }) {
                Icon(Icons.Default.Shuffle, contentDescription = stringResource(R.string.shuffle_all))
            }
            IconButton(onClick = viewModel::reload) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.subsonic_refresh))
            }
        }
        when (section) {
            Section.ALBUMS -> LoadContent(albums, onRetry = viewModel::reload) { list ->
                AlbumGrid(albums = list, onAlbumClick = viewModel::openAlbum)
            }
            Section.ARTISTS -> LoadContent(artists, onRetry = viewModel::reload) { list ->
                ArtistList(artists = list, onArtistClick = viewModel::openArtist)
            }
        }
    }
}

private val SubsonicAlbumSort.label: Int
    get() = when (this) {
        SubsonicAlbumSort.NAME -> R.string.sort_name
        SubsonicAlbumSort.ARTIST -> R.string.sort_artist
        SubsonicAlbumSort.NEWEST -> R.string.sort_newest
        SubsonicAlbumSort.RECENT -> R.string.recently_played
        SubsonicAlbumSort.FREQUENT -> R.string.subsonic_sort_most_played
        SubsonicAlbumSort.RANDOM -> R.string.subsonic_sort_random
    }

@Composable
private fun AlbumSortMenu(current: SubsonicAlbumSort, onChange: (SubsonicAlbumSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.action_sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SubsonicAlbumSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(stringResource(sort.label)) },
                    onClick = { onChange(sort); expanded = false },
                    trailingIcon = {
                        if (sort == current) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(MonoDimens.iconSm))
                    },
                )
            }
        }
    }
}

@Composable
private fun DetailHeader(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MonoDimens.spacingSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

/** A list that is loading, failed, empty, or there. */
@Composable
private fun <T> LoadContent(
    load: Load<List<T>>,
    onRetry: () -> Unit,
    content: @Composable (List<T>) -> Unit,
) {
    when (load) {
        Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is Load.Failed -> Message(
            text = stringResource(R.string.subsonic_load_failed),
            action = stringResource(R.string.action_retry),
            onAction = onRetry,
        )
        is Load.Loaded -> if (load.value.isEmpty()) {
            Message(text = stringResource(R.string.subsonic_library_empty))
        } else {
            content(load.value)
        }
    }
}

@Composable
private fun NotConnected(onSetUp: () -> Unit) {
    Message(
        text = stringResource(R.string.subsonic_not_connected),
        detail = stringResource(R.string.subsonic_not_connected_detail),
        action = stringResource(R.string.subsonic_set_up),
        onAction = onSetUp,
        filledAction = true,
    )
}

@Composable
private fun Message(
    text: String,
    detail: String? = null,
    action: String? = null,
    onAction: () -> Unit = {},
    filledAction: Boolean = false,
) {
    Box(Modifier.fillMaxSize().padding(MonoDimens.spacingLg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Dns,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (detail != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                if (filledAction) {
                    Button(onClick = onAction) { Text(action) }
                } else {
                    OutlinedButton(onClick = onAction) { Text(action) }
                }
            }
        }
    }
}

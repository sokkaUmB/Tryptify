// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.party

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import tf.monochrome.android.R
import tf.monochrome.android.domain.model.buildCoverUrl
import tf.monochrome.android.party.PartyClient
import tf.monochrome.android.party.PartyEnd
import tf.monochrome.android.party.PartyHearing
import tf.monochrome.android.party.PartyItem
import tf.monochrome.android.party.PartyLine
import tf.monochrome.android.party.PartyLineKind
import tf.monochrome.android.party.PartyMember
import tf.monochrome.android.party.PartyStatus
import tf.monochrome.android.party.PartyUi
import tf.monochrome.android.party.PartyView
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.components.GlassPill
import tf.monochrome.android.ui.components.LocalGlassBackdrop
import tf.monochrome.android.ui.components.PressableGlass
import tf.monochrome.android.ui.components.SearchOverlay
import tf.monochrome.android.ui.components.materialSurface
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * The listening party, direction A, "now playing first" (docs/plans/
 * listening-parties.md, docs/ui-invariants.md "A listening party is the
 * song first"). Before a party: start one or join one by its code. At one:
 * the top bar with the party's name, host and code and Invite; who is
 * listening, with how many on the web and whether this phone is in sync; the
 * song large, who added it, its progress; the host's Play/Pause for everyone
 * and Skip, everyone else's Vote skip; Up next with who added each song and
 * whether it is shared; and the chat as a sheet at the bottom, the latest
 * line and the field, opening to the whole chat. Colours, type and glass are
 * the listener's own, as on every screen.
 */
@Composable
fun PartyScreen(
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    viewModel: PartyViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val hasServer by viewModel.hasServer.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(Unit) {
        viewModel.screenShown(true)
        onDispose { viewModel.screenShown(false) }
    }

    // A pushed screen's own backdrop: the page under it, for the pills on it.
    val ground = rememberHazeState()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).hazeSource(ground))
        CompositionLocalProvider(LocalGlassBackdrop provides ground) {
            val party = ui.party
            when {
                adding && party != null -> AddSongPage(viewModel, onDone = { adding = false })
                party != null && ui.client.status != PartyStatus.ENDED -> PartyRoom(ui, viewModel, onBack, onAdd = { adding = true })
                else -> PartyLobby(ui, hasServer, viewModel, onBack, onSignIn)
            }
        }
    }
}

// Before a party.

@Composable
private fun PartyLobby(
    ui: PartyUi,
    hasServer: Boolean,
    viewModel: PartyViewModel,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
) {
    val client = ui.client
    Column(Modifier.fillMaxSize()) {
        PartyTopBar(title = stringResource(R.string.listening_party), subtitle = null, onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MonoDimens.spacingLg),
            verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd),
        ) {
            if (client.status == PartyStatus.ENDED) {
                Panel {
                    Text(
                        stringResource(
                            when (client.end) {
                                PartyEnd.KICKED -> R.string.party_kicked
                                PartyEnd.LEFT -> R.string.party_left
                                else -> R.string.party_ended
                            },
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    PillButton(stringResource(R.string.tm_mp_ok), onClick = viewModel::dismiss)
                }
            }
            if (client.status == PartyStatus.FAILED) {
                Panel {
                    Text(errorText(client.error), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    PillButton(stringResource(R.string.tm_mp_ok), onClick = viewModel::dismiss)
                }
            }
            when {
                !viewModel.signedIn() -> Panel {
                    Text(stringResource(R.string.party_sign_in), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    PillButton(stringResource(R.string.sign_in), primary = true, onClick = onSignIn)
                }
                !hasServer -> Panel {
                    Text(stringResource(R.string.tm_mp_no_server), style = MaterialTheme.typography.bodyMedium)
                }
                client.status == PartyStatus.CONNECTING -> Panel {
                    Text(stringResource(R.string.tm_mp_connecting, client.serverHost.orEmpty()), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    var name by rememberSaveable { mutableStateOf("") }
                    var code by rememberSaveable { mutableStateOf("") }
                    Panel {
                        Title(stringResource(R.string.party_start_title))
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(40) },
                            label = { Text(stringResource(R.string.party_name_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(MonoDimens.spacingSm))
                        PillButton(stringResource(R.string.party_start), primary = true, onClick = { viewModel.start(name.trim()) })
                    }
                    Panel {
                        Title(stringResource(R.string.party_join_title))
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.uppercase().filter(Char::isLetterOrDigit).take(4) },
                            label = { Text(stringResource(R.string.party_code_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { if (code.length == 4) viewModel.join(code) }),
                            textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(MonoDimens.spacingSm))
                        PillButton(stringResource(R.string.party_join), enabled = code.length == 4, onClick = { viewModel.join(code) })
                    }
                    ThisPhone(ui, viewModel)
                }
            }
            Spacer(Modifier.height(LocalBottomChromeInset.current + MonoDimens.spacingLg))
        }
    }
}

// At a party.

@Composable
private fun PartyRoom(ui: PartyUi, viewModel: PartyViewModel, onBack: () -> Unit, onAdd: () -> Unit) {
    val party = ui.party ?: return
    val context = LocalContext.current
    var chatOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = chatOpen) { chatOpen = false }
    var sheetHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    // The chat sheet frosts the page it floats over, a sibling of it.
    val page = rememberHazeState()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().hazeSource(page)) {
            val host = party.host
            PartyTopBar(
                title = party.name,
                subtitle = if (host != null) stringResource(R.string.party_hosted_by, host.name, party.code)
                else stringResource(R.string.party_no_host, party.code),
                onBack = onBack,
                action = {
                    PillButton(stringResource(R.string.party_invite)) {
                        val link = viewModel.inviteLink() ?: return@PillButton
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.party_invite_text, link, party.code))
                        context.startActivity(Intent.createChooser(send, context.getString(R.string.party_invite)))
                    }
                },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = MonoDimens.spacingLg),
                verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd),
            ) {
                Listeners(party, ui)
                if (ui.client.status == PartyStatus.RECONNECTING) {
                    Text(stringResource(R.string.party_reconnecting), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                }
                ui.client.error?.let { message ->
                    Panel {
                        Text(errorText(message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(MonoDimens.spacingSm))
                        PillButton(stringResource(R.string.tm_mp_ok), onClick = viewModel::clearError)
                    }
                }
                NowPlaying(ui, party, viewModel)
                UpNext(ui, party, viewModel, onAdd)
                ThisPhone(ui, viewModel)
                PillButton(stringResource(R.string.party_leave), danger = true, onClick = viewModel::leave)
                Spacer(Modifier.height(sheetHeight + LocalBottomChromeInset.current + MonoDimens.spacingLg))
            }
        }
        ChatSheet(
            lines = ui.client.lines,
            myId = ui.myId,
            open = chatOpen,
            onToggle = { chatOpen = !chatOpen },
            onSend = viewModel::chat,
            hazeState = page,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = LocalBottomChromeInset.current)
                .imePadding()
                .onSizeChanged { sheetHeight = with(density) { it.height.toDp() } },
        )
    }
}

@Composable
private fun Listeners(party: PartyView, ui: PartyUi) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Overlapping, each a little over the last, as the demo has them.
        val shown = party.members.filterNot { it.away }.take(5)
        Box(Modifier.width((30 + 22 * (shown.size - 1).coerceAtLeast(0)).dp).height(30.dp)) {
            shown.forEachIndexed { i, m -> Avatar(m, Modifier.offset(x = (22 * i).dp)) }
        }
        val here = party.listening
        val web = party.onTheWeb
        val count = pluralStringResource(R.plurals.party_listening, here, here)
        Text(
            if (web > 0) count + " · " + stringResource(R.string.party_on_web, web) else count,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        HearingDot(ui)
    }
}

@Composable
private fun Avatar(member: PartyMember, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    // Each listener a colour of the listener's own theme, picked by name.
    val tones = listOf(
        scheme.primary to scheme.onPrimary,
        scheme.secondary to scheme.onSecondary,
        scheme.tertiary to scheme.onTertiary,
        scheme.primaryContainer to scheme.onPrimaryContainer,
        scheme.tertiaryContainer to scheme.onTertiaryContainer,
    )
    val (fill, ink) = tones[(member.name.hashCode() and Int.MAX_VALUE) % tones.size]
    val label = stringResource(R.string.party_cd_avatar, member.name)
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(fill)
            .border(2.dp, scheme.background, CircleShape)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            member.name.firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = ink,
        )
    }
}

@Composable
private fun HearingDot(ui: PartyUi) {
    val scheme = MaterialTheme.colorScheme
    val good = ui.hearing == PartyHearing.IN_SYNC
    val text = when (ui.hearing) {
        PartyHearing.IN_SYNC -> stringResource(R.string.party_hearing_in_sync)
        PartyHearing.CATCHING_UP -> stringResource(R.string.party_hearing_catching_up)
        PartyHearing.LOADING -> ui.fetching?.let { stringResource(R.string.party_hearing_fetching, (it * 100).toInt()) }
            ?: stringResource(R.string.party_hearing_loading)
        PartyHearing.PAUSED -> stringResource(R.string.party_hearing_paused)
        PartyHearing.PAUSED_HERE -> stringResource(R.string.party_hearing_paused_here)
        PartyHearing.ELSEWHERE -> stringResource(R.string.party_hearing_elsewhere)
        PartyHearing.WAITING_FOR_UPLOAD -> stringResource(R.string.party_hearing_waiting)
        PartyHearing.CANT_PLAY -> stringResource(R.string.party_hearing_cant_play)
        PartyHearing.IDLE -> stringResource(R.string.party_hearing_idle)
    }
    val tint = if (good) scheme.primary else scheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (good) tint else tint.copy(alpha = 0.5f)))
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

@Composable
private fun NowPlaying(ui: PartyUi, party: PartyView, viewModel: PartyViewModel) {
    val now = party.now
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MonoDimens.spacingSm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Cover(now?.cover, Modifier.fillMaxWidth(0.72f).widthIn(max = 320.dp).aspectRatio(1f), RoundedCornerShape(24.dp), big = true)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                now?.title ?: stringResource(R.string.party_nothing_playing),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    now == null -> stringResource(R.string.party_add_first)
                    now.addedBy == ui.myId -> stringResource(R.string.party_added_by_you, now.artist)
                    else -> stringResource(R.string.party_added_by, now.artist, now.addedByName)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (now != null) Progress(ui, now, viewModel)
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (ui.isHost) {
                val paused = party.playback.paused
                PressableGlass(
                    onClick = viewModel::togglePlay,
                    enabled = now != null,
                    modifier = Modifier.size(64.dp),
                    shape = CircleShape,
                    hazeState = LocalGlassBackdrop.current,
                    onClickLabel = stringResource(if (paused) R.string.party_play_for_everyone else R.string.party_pause_for_everyone),
                ) {
                    Icon(
                        if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = stringResource(if (paused) R.string.party_play_for_everyone else R.string.party_pause_for_everyone),
                        modifier = Modifier.size(30.dp),
                    )
                }
                PillButton(stringResource(R.string.party_skip), icon = { Icon(Icons.Default.SkipNext, null, Modifier.size(18.dp)) }, enabled = now != null, onClick = viewModel::skip)
            } else {
                val voted = ui.myId in party.skip.votes
                PillButton(
                    stringResource(if (voted) R.string.party_voted_skip else R.string.party_vote_skip, party.skip.votes.size, party.skip.needed),
                    icon = { Icon(Icons.Default.SkipNext, null, Modifier.size(18.dp)) },
                    primary = voted,
                    enabled = now != null,
                    onClick = viewModel::vote,
                )
            }
            if (ui.hearing == PartyHearing.ELSEWHERE || ui.hearing == PartyHearing.PAUSED_HERE) {
                PillButton(stringResource(R.string.party_back_to_party), primary = true, onClick = viewModel::rejoinPlayback)
            }
        }
    }
}

@Composable
private fun Progress(ui: PartyUi, now: PartyItem, viewModel: PartyViewModel) {
    val position by viewModel.position.collectAsStateWithLifecycle()
    val length = now.durationMs.coerceAtLeast(1)
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth()) {
        if (ui.isHost) {
            // The host seeks for everyone.
            Slider(
                value = dragging ?: (position.toFloat() / length).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { viewModel.seek((it * length).toLong()) }
                    dragging = null
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(
                progress = { (position.toFloat() / length).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = dragging?.let { (it * length).toLong() } ?: position
            Text(time(shown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(time(now.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UpNext(ui: PartyUi, party: PartyView, viewModel: PartyViewModel, onAdd: () -> Unit) {
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.party_up_next).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            PillButton(stringResource(R.string.party_add_song), icon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) }, onClick = onAdd)
        }
        Spacer(Modifier.height(MonoDimens.spacingSm))
        if (party.queue.isEmpty()) {
            Text(stringResource(R.string.party_nothing_queued), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        party.queue.forEach { item -> QueueRow(item, ui, viewModel) }
    }
}

@Composable
private fun QueueRow(item: PartyItem, ui: PartyUi, viewModel: PartyViewModel) {
    val mine = item.addedBy == ui.myId
    val canRemove = ui.isHost || mine
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().height(MonoDimens.listRowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Cover(item.cover, Modifier.size(40.dp), RoundedCornerShape(10.dp), big = false)
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(item.artist, if (mine) stringResource(R.string.party_you) else item.addedByName).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item.shared?.let { shared ->
            Text(
                if (shared.ready) stringResource(R.string.party_shared) else stringResource(R.string.party_uploading, (shared.progress * 100).toInt()),
                style = MaterialTheme.typography.labelSmall,
                color = if (shared.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canRemove) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.party_cd_more, item.title))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (ui.isHost) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_play_next)) }, onClick = { menu = false; viewModel.playNext(item.id) })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_remove)) }, onClick = { menu = false; viewModel.remove(item.id) })
                }
            }
        }
    }
}

@Composable
private fun ThisPhone(ui: PartyUi, viewModel: PartyViewModel) {
    val notify by viewModel.notify.collectAsStateWithLifecycle()
    Panel {
        SwitchRow(stringResource(R.string.party_share_songs), stringResource(R.string.party_share_songs_desc), ui.shareSongs, viewModel::setShareSongs)
        Spacer(Modifier.height(MonoDimens.spacingSm))
        Text(stringResource(R.string.party_notify_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchRow(stringResource(R.string.party_notify_chat), null, notify.chat) { viewModel.setNotify(notify.copy(chat = it)) }
        SwitchRow(stringResource(R.string.party_notify_joins), null, notify.joins) { viewModel.setNotify(notify.copy(joins = it)) }
        SwitchRow(stringResource(R.string.party_notify_songs), null, notify.songs) { viewModel.setNotify(notify.copy(songs = it)) }
    }
}

@Composable
private fun SwitchRow(title: String, description: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onChange(!on) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = MonoDimens.spacingMd)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/**
 * The chat, as a sheet at the bottom of the screen: the latest line and the
 * field, and with a tap on it the whole chat. Glass over the page it floats
 * on, drawn in this window beside it, so it frosts what scrolls under it.
 */
@Composable
private fun ChatSheet(
    lines: List<PartyLine>,
    myId: String?,
    open: Boolean,
    onToggle: () -> Unit,
    onSend: (String) -> Unit,
    hazeState: dev.chrisbanes.haze.HazeState,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val send = {
        if (draft.isNotBlank()) {
            onSend(draft)
            draft = ""
        }
    }
    GlassPanel(
        hazeState = hazeState,
        glass = LocalMiniPlayerGlass.current,
        modifier = modifier.fillMaxWidth().padding(horizontal = MonoDimens.spacingSm),
        avoidNavigationBar = false,
        fallbackMinAlpha = 0.9f,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable(onClickLabel = stringResource(R.string.party_chat_title), onClick = onToggle)
                    .padding(vertical = 4.dp, horizontal = 24.dp),
            ) {
                Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)))
            }
            if (open) {
                val listState = rememberLazyListState()
                LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex) }
                if (lines.isEmpty()) {
                    Text(stringResource(R.string.party_chat_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(lines) { line -> ChatLine(line, myId) }
                }
            } else {
                val last = lines.lastOrNull()
                Box(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
                    if (last != null) ChatLine(last, myId, oneLine = true)
                    else Text(stringResource(R.string.party_chat_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(300) },
                    placeholder = { Text(stringResource(R.string.party_chat_hint)) },
                    singleLine = true,
                    shape = MonoDimens.shapePill,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, capitalization = KeyboardCapitalization.Sentences),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.weight(1f),
                )
                PressableGlass(
                    onClick = { send() },
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    hazeState = hazeState,
                    onClickLabel = stringResource(R.string.party_send),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.party_send), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun ChatLine(line: PartyLine, myId: String?, oneLine: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val maxLines = if (oneLine) 1 else Int.MAX_VALUE
    if (line.kind == PartyLineKind.SAY) {
        Row {
            Text(
                line.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (line.id == myId) scheme.tertiary else scheme.primary,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(line.text, style = MaterialTheme.typography.bodyMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Text(
            systemLine(line),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The server's own lines, said in the listener's language. */
@Composable
private fun systemLine(line: PartyLine): String = when (line.kind) {
    PartyLineKind.JOIN -> stringResource(if (line.text == "web") R.string.party_line_joined_web else R.string.party_line_joined, line.name)
    PartyLineKind.LEAVE -> stringResource(R.string.party_line_left, line.name)
    PartyLineKind.ADD -> stringResource(R.string.party_line_added, line.name, line.text)
    PartyLineKind.SKIP -> if (line.name.isBlank()) stringResource(R.string.party_line_skipped, line.text)
    else stringResource(R.string.party_line_host_skipped, line.name, line.text)
    PartyLineKind.SAY -> line.text
}

// Adding a song.

@Composable
private fun AddSongPage(viewModel: PartyViewModel, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    var query by rememberSaveable { mutableStateOf("") }
    val finds by viewModel.finds.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val added = stringResource(R.string.party_added_toast)
    SearchOverlay(
        open = true,
        query = query,
        onQueryChange = { query = it; viewModel.search(it) },
        placeholder = stringResource(R.string.party_search_hint),
        onClose = onDone,
        autoFocus = true,
    ) { topInset ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = topInset + MonoDimens.spacingSm,
                start = MonoDimens.spacingLg,
                end = MonoDimens.spacingLg,
                bottom = LocalBottomChromeInset.current + MonoDimens.spacingLg,
            ),
            verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingXs),
        ) {
            if (searching && finds.isEmpty()) {
                item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            } else if (finds.isEmpty() && query.trim().length >= 2) {
                item { Text(stringResource(R.string.party_search_none), modifier = Modifier.padding(MonoDimens.spacingLg), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(finds, key = { it.key }) { find ->
                val (title, subtitle, cover) = when (find) {
                    is PartyFind.Here -> Triple(find.track.title, find.track.artistName, find.track.artworkUri)
                    is PartyFind.Catalogue -> Triple(
                        find.track.title,
                        find.track.displayArtist,
                        find.track.album?.cover?.let { buildCoverUrl(it, 160) },
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MonoDimens.listRowHeight)
                        .materialSurface(shape = MonoDimens.shapeMd)
                        .clickable {
                            viewModel.add(find)
                            Toast.makeText(context, added, Toast.LENGTH_SHORT).show()
                            onDone()
                        }
                        .padding(horizontal = MonoDimens.spacingMd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd),
                ) {
                    Cover(cover, Modifier.size(44.dp), RoundedCornerShape(8.dp), big = false)
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// Pieces.

@Composable
private fun PartyTopBar(title: String, subtitle: String?, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = MonoDimens.spacingLg, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        action?.invoke()
    }
}

/** A Material 3 panel, as the rooms screen's are. */
@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .materialSurface(shape = MonoDimens.shapeLg)
            .padding(MonoDimens.spacingLg),
        content = content,
    )
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = MonoDimens.spacingSm))
}

/** A glass pill with a label, the panel recipe, as every glass button is. */
@Composable
private fun PillButton(
    text: String,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    GlassPill(
        onClick = onClick,
        hazeState = LocalGlassBackdrop.current,
        height = 44.dp,
        enabled = enabled,
        modifier = Modifier.alpha(if (enabled) 1f else 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            icon?.invoke()
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    danger -> scheme.error
                    primary -> scheme.primary
                    else -> androidx.compose.material3.LocalContentColor.current
                },
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Cover(url: String?, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape, big: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.clip(shape).background(scheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Default.MusicNote, contentDescription = null, tint = scheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(if (big) 72.dp else 20.dp))
        }
    }
}

@Composable
private fun errorText(message: String?): String = when (message) {
    null -> ""
    PartyClient.NO_SERVER -> stringResource(R.string.tm_mp_no_server)
    PartyClient.NOT_SUPPORTED -> stringResource(R.string.party_not_supported)
    "the party is full" -> stringResource(R.string.party_error_full)
    "no party with that code" -> stringResource(R.string.party_error_no_party)
    "slow down a little" -> stringResource(R.string.party_error_slow_down)
    "only the host can do that" -> stringResource(R.string.party_error_host_only)
    else -> message
}

private fun time(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

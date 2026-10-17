// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.multiplayer

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import tf.monochrome.android.data.api.ApiService
import tf.monochrome.android.data.preferences.PreferencesManager

enum class RoomStatus { OFF, CONNECTING, CONNECTED, FAILED }

/** Everything the rooms screen and the game read, as it stands. */
data class RoomSession(
    val status: RoomStatus = RoomStatus.OFF,
    val error: String? = null,
    val serverHost: String? = null,
    val myId: String? = null,
    /** The account the server took this phone for; null on a server that takes guests. */
    val user: String? = null,
    val rooms: List<RoomSummary> = emptyList(),
    val room: RoomView? = null,
    /** The picked chart with its notes, as the server handed it over. */
    val chart: RoomChart? = null,
    /** Everyone's score as it stands, by player id, while a song plays. */
    val frames: Map<String, RoomLive> = emptyMap(),
    val ranking: List<RoomPlacing>? = null,
    val chat: List<RoomChatLine> = emptyList(),
    /** The shortest round trip to the server, ms; null before the first pong. */
    val latencyMs: Long? = null,
    val offsetMs: Long? = null,
) {
    val me: RoomPlayer? get() = room?.players?.firstOrNull { it.id == myId }
    val isHost: Boolean get() = room != null && room.hostId == myId
}

/**
 * One phone's socket to the rooms server, kept while the rooms screen or a
 * room game is open: hello, a ping every two seconds for the clock, and
 * every message in and out. [session] is the state the screens draw;
 * [events] carries what happens once (the start, the results, a kick), for
 * the game to act on. Reconnects with backoff while wanted; a room left
 * by a dropped socket is gone, as the server forgets it.
 */
@Singleton
class RoomClient @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
    private val preferences: PreferencesManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _session = MutableStateFlow(RoomSession())
    val session: StateFlow<RoomSession> = _session.asStateFlow()
    private val _events = MutableSharedFlow<RoomMsg>(extraBufferCapacity = 64)
    val events: SharedFlow<RoomMsg> = _events.asSharedFlow()

    /** Whether any server under Settings › Connections hosts rooms. */
    val hasServer: Flow<Boolean> = preferences.apiServers.map { servers -> servers.any { ApiService.MULTIPLAYER in it.services } }

    private val clock = ClockSync()
    private var job: Job? = null

    @Volatile
    private var send: (suspend (String) -> Unit)? = null

    /** Why the server turned the hello down, when it did; null otherwise. */
    @Volatile
    private var refused: String? = null

    /**
     * Opens the socket to the first rooms server, as [name] with the signed-in
     * account's [token]; nothing when one is open. A server that checks
     * accounts refuses a hello without a good token, and that refusal is
     * final: it is shown, not retried.
     */
    fun connect(name: String, token: String) {
        if (job?.isActive == true) return
        refused = null
        job = scope.launch {
            val server = preferences.apiServers.first().firstOrNull { ApiService.MULTIPLAYER in it.services }
            if (server == null) {
                _session.update { it.copy(status = RoomStatus.FAILED, error = "no rooms server") }
                return@launch
            }
            val host = server.url.substringAfter("://").substringBefore('/')
            _session.update { RoomSession(status = RoomStatus.CONNECTING, serverHost = host) }
            var attempt = 0
            while (isActive) {
                val established = runCatching { runSession(wsUrl(server.url), name, token) }
                    .onFailure { Log.w(TAG, "rooms socket: ${it.message}") }
                    .getOrDefault(false)
                if (!isActive) break
                refused?.let { why ->
                    _session.update { it.copy(status = RoomStatus.FAILED, error = why) }
                    return@launch
                }
                if (established) attempt = 0 else attempt++
                if (attempt > MAX_ATTEMPTS) {
                    _session.update { it.copy(status = RoomStatus.FAILED, error = it.error ?: "could not reach $host") }
                    return@launch
                }
                _session.update { it.copy(status = RoomStatus.CONNECTING, room = null, frames = emptyMap()) }
                delay(BACKOFF_MS * (1L shl attempt.coerceAtMost(4)))
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        send = null
        refused = null
        _session.value = RoomSession()
    }

    private suspend fun runSession(url: String, name: String, token: String): Boolean {
        var established = false
        val ws = httpClient.webSocketSession(url)
        var pinger: Job? = null
        try {
            send = { text -> ws.send(Frame.Text(text)) }
            ws.send(Frame.Text(RoomProtocol.hello(name, CLIENT, token)))
            pinger = scope.launch {
                // Quick at first, so the clock is known before anyone starts.
                repeat(5) {
                    ws.send(Frame.Text(RoomProtocol.ping(System.currentTimeMillis())))
                    delay(300)
                }
                while (isActive) {
                    ws.send(Frame.Text(RoomProtocol.ping(System.currentTimeMillis())))
                    delay(PING_MS)
                }
            }
            ws.incoming.consumeEach { frame ->
                if (frame !is Frame.Text) return@consumeEach
                val msg = RoomProtocol.parse(json, frame.readText())
                if (msg is RoomMsg.Welcome) {
                    established = true
                    _session.update { it.copy(status = RoomStatus.CONNECTED, error = null, myId = msg.id, user = msg.user) }
                    ws.send(Frame.Text(RoomProtocol.rooms()))
                }
                // Turned away before the welcome: not signed in, as far as
                // the server can tell. Said once and not tried again.
                if (!established && msg is RoomMsg.Error) refused = msg.message
                apply(msg)
            }
            val closed = ws.closeReason.await()
            Log.i(TAG, "rooms socket closed: ${closed?.code} ${closed?.message}")
        } finally {
            send = null
            pinger?.cancel()
            runCatching { ws.close() }
        }
        return established
    }

    private fun apply(msg: RoomMsg) {
        when (msg) {
            is RoomMsg.Pong -> {
                clock.sample(msg.c, msg.s, System.currentTimeMillis())
                _session.update { it.copy(latencyMs = clock.rttMs, offsetMs = clock.offsetMs) }
            }
            is RoomMsg.Rooms -> _session.update { it.copy(rooms = msg.rooms) }
            is RoomMsg.Room -> _session.update { s ->
                val wasPlaying = s.room?.playing == true
                s.copy(
                    room = msg.room,
                    // A new song clears the last one's results and scores.
                    ranking = if (msg.room.playing && !wasPlaying) null else s.ranking,
                    frames = if (msg.room.playing) s.frames else emptyMap(),
                    chart = if (msg.room.chart == null) null else s.chart,
                )
            }
            is RoomMsg.Chart -> _session.update { it.copy(chart = msg.chart, ranking = null) }
            is RoomMsg.Frames -> _session.update { it.copy(frames = msg.players.toMap()) }
            is RoomMsg.Results -> _session.update { it.copy(ranking = msg.ranking) }
            is RoomMsg.Chat -> _session.update { it.copy(chat = (it.chat + RoomChatLine(msg.id, msg.name, msg.text)).takeLast(CHAT_LINES)) }
            is RoomMsg.Left -> _session.update { it.copy(room = null, chart = null, frames = emptyMap(), ranking = null, chat = emptyList()) }
            is RoomMsg.Error -> _session.update { it.copy(error = msg.message) }
            else -> Unit
        }
        if (msg !is RoomMsg.Pong) _events.tryEmit(msg)
    }

    private fun tell(text: String) {
        val out = send ?: return
        scope.launch { runCatching { out(text) }.onFailure { Log.w(TAG, "rooms send: ${it.message}") } }
    }

    fun refreshRooms() = tell(RoomProtocol.rooms())
    fun createRoom(name: String) = tell(RoomProtocol.create(name))
    fun joinRoom(code: String) = tell(RoomProtocol.join(code))
    fun leaveRoom() = tell(RoomProtocol.leave())
    fun pick(chart: RoomChart) = tell(RoomProtocol.pick(json, chart))
    fun have(audio: Boolean) = tell(RoomProtocol.have(audio))
    fun ready(ready: Boolean) = tell(RoomProtocol.ready(ready))
    fun start() = tell(RoomProtocol.start())
    fun kick(id: String) = tell(RoomProtocol.kick(id))
    fun frame(live: RoomLive) = tell(RoomProtocol.frame(json, live))
    fun finish(result: RoomFinished) = tell(RoomProtocol.finish(json, result))
    fun chat(text: String) = tell(RoomProtocol.chat(text))

    /** Clears the last error shown. */
    fun clearError() = _session.update { it.copy(error = null) }

    /** The local wall-clock ms at which the server's [serverMs] falls. */
    fun localMsFor(serverMs: Long): Long = clock.localMsFor(serverMs)

    companion object {
        private const val TAG = "RoomClient"
        private const val CLIENT = "tryptify-android"
        private const val PING_MS = 2_000L
        private const val BACKOFF_MS = 1_000L
        private const val MAX_ATTEMPTS = 5
        private const val CHAT_LINES = 60

        /** `https://host/secret` as the socket's `wss://host/secret/ws`. */
        fun wsUrl(server: String): String {
            val base = server.trimEnd('/')
            val ws = when {
                base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
                base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
                else -> base
            }
            return "$ws/ws"
        }
    }
}

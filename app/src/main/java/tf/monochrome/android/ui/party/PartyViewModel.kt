// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.party

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.data.local.repository.LocalMediaRepository
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.party.PartyClient
import tf.monochrome.android.party.PartyController
import tf.monochrome.android.party.PartyNotify
import tf.monochrome.android.party.PartyPrefs
import tf.monochrome.android.party.PartyUi

/** A song the Add song page found: a file on this phone, or one from the catalogue. */
sealed interface PartyFind {
    val key: String

    data class Here(val track: UnifiedTrack) : PartyFind {
        override val key get() = "here:${track.id}"
    }

    data class Catalogue(val track: Track) : PartyFind {
        override val key get() = "cat:${track.id}"
    }
}

/** The party screen's view of [PartyController], and the Add song page's search. */
@HiltViewModel
class PartyViewModel @Inject constructor(
    private val controller: PartyController,
    private val client: PartyClient,
    private val prefs: PartyPrefs,
    private val music: MusicRepository,
    private val local: LocalMediaRepository,
) : ViewModel() {
    val ui: StateFlow<PartyUi> = controller.state
    val notify: StateFlow<PartyNotify> = prefs.notify
    val hasServer: StateFlow<Boolean> = client.hasServer.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** The party's moment in its song, four times a second, for the progress bar. */
    val position: StateFlow<Long> = flow {
        while (true) {
            emit(controller.positionNow())
            delay(250)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), 0L)

    private val _finds = MutableStateFlow<List<PartyFind>>(emptyList())
    val finds: StateFlow<List<PartyFind>> = _finds.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    private var search: Job? = null

    fun signedIn(): Boolean = controller.signedIn()
    fun start(name: String) = controller.start(name)
    fun join(code: String) = controller.join(code)
    fun leave() = controller.leave()
    fun dismiss() = controller.dismiss()
    fun togglePlay() = controller.togglePlay()
    fun seek(ms: Long) = controller.seek(ms)
    fun skip() = controller.skip()
    fun vote() = controller.vote()
    fun remove(itemId: String) = controller.remove(itemId)
    fun playNext(itemId: String) = controller.playNext(itemId)
    fun chat(text: String) = controller.chat(text)
    fun kick(id: String) = controller.kick(id)
    fun passHost(id: String) = controller.passHost(id)
    fun endParty() = controller.endParty()
    fun clearError() = controller.clearError()
    fun rejoinPlayback() = controller.rejoinPlayback()
    fun inviteLink(): String? = controller.inviteLink()
    fun setShareSongs(on: Boolean) = controller.setShareSongs(on)
    fun setNotify(value: PartyNotify) = prefs.setNotify(value)
    fun screenShown(shown: Boolean) = controller.screenShown(shown)

    fun add(find: PartyFind) = when (find) {
        is PartyFind.Here -> controller.add(find.track)
        is PartyFind.Catalogue -> controller.add(find.track)
    }

    /** The Add song page's search: songs on this phone first, then the catalogue. */
    fun search(query: String) {
        search?.cancel()
        val q = query.trim()
        if (q.length < 2) {
            _finds.value = emptyList()
            _searching.value = false
            return
        }
        search = viewModelScope.launch {
            // Typing goes on; asked once it pauses.
            delay(350)
            _searching.value = true
            val here = runCatching { local.searchTracks(q).firstOrNull().orEmpty() }.getOrDefault(emptyList())
                .take(HERE_MAX).map { PartyFind.Here(it) }
            _finds.value = here
            val catalogue = music.searchTracks(q, limit = CATALOGUE_MAX).getOrNull().orEmpty().map { PartyFind.Catalogue(it) }
            _finds.value = here + catalogue
            _searching.value = false
        }
    }

    override fun onCleared() {
        controller.screenShown(false)
    }

    private companion object {
        const val HERE_MAX = 10
        const val CATALOGUE_MAX = 25
    }
}

package tf.monochrome.android.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.data.subsonic.SubsonicAlbumSort
import tf.monochrome.android.data.subsonic.SubsonicRepository
import tf.monochrome.android.data.subsonic.SubsonicServer
import tf.monochrome.android.domain.model.UnifiedAlbum
import tf.monochrome.android.domain.model.UnifiedArtist
import tf.monochrome.android.domain.model.UnifiedTrack
import javax.inject.Inject

/**
 * The "My server" Library page: the listener's Subsonic server, read live.
 *
 * The page keeps its own small stack (index → artist → album) rather than
 * pushing nav routes. It is one page of the pager, and Back pops this stack
 * first (see SubsonicLibraryTab's BackHandler), the way the Local page's
 * categories close before the page does.
 */
@HiltViewModel
class SubsonicLibraryViewModel @Inject constructor(
    private val repository: SubsonicRepository,
) : ViewModel() {

    enum class Section { ALBUMS, ARTISTS }

    sealed interface View {
        data object Index : View
        data class Artist(val artist: UnifiedArtist) : View
        data class Album(val album: UnifiedAlbum) : View
    }

    /** Something loading, loaded, or failed; [Failed] keeps the reason for the log. */
    sealed interface Load<out T> {
        data object Loading : Load<Nothing>
        data class Loaded<T>(val value: T) : Load<T>
        data class Failed(val reason: String?) : Load<Nothing>
    }

    /** Loading until the saved server has been read, so the page never flashes "not connected". */
    val server: StateFlow<Load<SubsonicServer?>> = repository.server
        .distinctUntilChanged()
        .map<SubsonicServer?, Load<SubsonicServer?>> { Load.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Load.Loading)

    private val _section = MutableStateFlow(Section.ALBUMS)
    val section: StateFlow<Section> = _section.asStateFlow()

    private val _albumSort = MutableStateFlow(SubsonicAlbumSort.NAME)
    val albumSort: StateFlow<SubsonicAlbumSort> = _albumSort.asStateFlow()

    private val _albums = MutableStateFlow<Load<List<UnifiedAlbum>>>(Load.Loading)
    val albums: StateFlow<Load<List<UnifiedAlbum>>> = _albums.asStateFlow()

    private val _artists = MutableStateFlow<Load<List<UnifiedArtist>>>(Load.Loading)
    val artists: StateFlow<Load<List<UnifiedArtist>>> = _artists.asStateFlow()

    private val _stack = MutableStateFlow<List<View>>(listOf(View.Index))
    val view: StateFlow<List<View>> = _stack.asStateFlow()

    private val _detailAlbums = MutableStateFlow<Load<List<UnifiedAlbum>>>(Load.Loading)
    val artistAlbums: StateFlow<Load<List<UnifiedAlbum>>> = _detailAlbums.asStateFlow()

    private val _detailTracks = MutableStateFlow<Load<List<UnifiedTrack>>>(Load.Loading)
    val albumTracks: StateFlow<Load<List<UnifiedTrack>>> = _detailTracks.asStateFlow()

    private var albumsJob: Job? = null
    private var loadedFor: SubsonicServer? = null

    /** Called when the page shows a connected server: loads once per server. */
    fun onServer(server: SubsonicServer) {
        if (server == loadedFor) return
        loadedFor = server
        _stack.value = listOf(View.Index)
        reload()
    }

    fun reload() {
        loadAlbums()
        loadArtists()
    }

    fun selectSection(section: Section) {
        _section.value = section
    }

    fun setAlbumSort(sort: SubsonicAlbumSort) {
        if (_albumSort.value == sort) return
        _albumSort.value = sort
        loadAlbums()
    }

    /**
     * Every album, a page at a time, showing each page as it lands so a big
     * library fills in rather than sitting on a spinner. getAlbumList2 caps a
     * page at 500.
     */
    private fun loadAlbums() {
        albumsJob?.cancel()
        _albums.value = Load.Loading
        albumsJob = viewModelScope.launch {
            val sort = _albumSort.value
            val all = mutableListOf<UnifiedAlbum>()
            try {
                while (all.size < MAX_ALBUMS) {
                    val page = repository.albums(sort, offset = all.size, size = ALBUM_PAGE)
                    all += page
                    _albums.value = Load.Loaded(all.distinctBy { it.id })
                    // A random list never ends; one page of it is the answer.
                    if (page.size < ALBUM_PAGE || sort == SubsonicAlbumSort.RANDOM) break
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (all.isEmpty()) _albums.value = Load.Failed(e.message)
            }
        }
    }

    private fun loadArtists() {
        _artists.value = Load.Loading
        viewModelScope.launch {
            _artists.value = runLoad { repository.artists().sortedBy { it.name.lowercase() } }
        }
    }

    fun openArtist(artist: UnifiedArtist) {
        _stack.value = _stack.value + View.Artist(artist)
        _detailAlbums.value = Load.Loading
        viewModelScope.launch { _detailAlbums.value = runLoad { repository.artistAlbums(artist.id) } }
    }

    fun openAlbum(album: UnifiedAlbum) {
        _stack.value = _stack.value + View.Album(album)
        _detailTracks.value = Load.Loading
        viewModelScope.launch { _detailTracks.value = runLoad { repository.albumTracks(album.id) } }
    }

    /** Back out of an album or artist. False when already at the index. */
    fun back(): Boolean {
        val stack = _stack.value
        if (stack.size <= 1) return false
        val next = stack.dropLast(1)
        _stack.value = next
        // Going back to an artist from one of its albums: its albums are still
        // what _detailAlbums holds, since an album page loads only tracks.
        return true
    }

    /** A random selection from the whole server, for "Shuffle all". */
    fun shuffleAll(play: (List<UnifiedTrack>) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.randomSongs() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let(play)
        }
    }

    private suspend fun <T> runLoad(block: suspend () -> T): Load<T> = try {
        Load.Loaded(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Load.Failed(e.message)
    }

    private companion object {
        const val ALBUM_PAGE = 500
        const val MAX_ALBUMS = 20_000
    }
}

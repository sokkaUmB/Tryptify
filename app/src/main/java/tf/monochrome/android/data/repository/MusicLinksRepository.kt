package tf.monochrome.android.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.VisualLink
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every edit to the visual music links goes through here, so the Studio, the
 * Music links page and a theme import cannot race each other: each edit reads
 * the stored list and writes it back under one lock.
 */
@Singleton
class MusicLinksRepository @Inject constructor(
    private val preferences: PreferencesManager,
) {
    private val lock = Mutex()

    val visualLinks: Flow<List<VisualLink>> = preferences.visualLinks

    /** The master switch: true pauses every music link. */
    val allPaused: Flow<Boolean> = preferences.musicLinksPaused

    /** The links that should run right now: none while everything is paused. */
    val runningVisualLinks: Flow<List<VisualLink>> =
        combine(visualLinks, allPaused) { links, paused -> if (paused) emptyList() else links }

    /**
     * The link open in an editor, if one is. It runs everywhere in place of its
     * saved version, so what the app itself draws (the CRT effect, the panels'
     * glass) moves with it while it is shaped, not only once it is saved.
     */
    val editing = MutableStateFlow<VisualLink?>(null)

    /** What the app runs: [runningVisualLinks] with the [editing] link in place of its saved version. */
    val previewVisualLinks: Flow<List<VisualLink>> =
        combine(visualLinks, allPaused, editing) { saved, paused, draft -> withDraft(saved, paused, draft) }

    /** Adds [link], or replaces the link on the same setting. */
    suspend fun put(link: VisualLink) = edit { links -> links.filterNot { it.target == link.target } + link }

    suspend fun remove(target: String) = edit { links -> links.filterNot { it.target == target } }

    suspend fun setPaused(target: String, paused: Boolean) =
        edit { links -> links.map { if (it.target == target) it.copy(paused = paused) else it } }

    /** Replaces every visual link at once (a theme pack, or undoing one). */
    suspend fun replaceAll(links: List<VisualLink>) = edit { links }

    suspend fun setAllPaused(paused: Boolean) = preferences.setMusicLinksPaused(paused)

    companion object {
        /**
         * The saved links — none while the master switch has them paused — with
         * [draft] in place of its saved version. The draft runs even then, or
         * the editor would have nothing to show.
         */
        fun withDraft(saved: List<VisualLink>, paused: Boolean, draft: VisualLink?): List<VisualLink> {
            val base = if (paused) emptyList() else saved
            return if (draft == null) base else base.filterNot { it.target == draft.target } + draft.copy(paused = false)
        }
    }

    private suspend fun edit(change: (List<VisualLink>) -> List<VisualLink>) = lock.withLock {
        preferences.setVisualLinks(change(preferences.visualLinks.first()))
    }
}

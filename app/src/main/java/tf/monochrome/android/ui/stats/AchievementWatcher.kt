package tf.monochrome.android.ui.stats

import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import tf.monochrome.android.audio.dsp.SnapinType
import tf.monochrome.android.data.achievements.AchievementStore
import tf.monochrome.android.data.achievements.FeatureLog
import tf.monochrome.android.data.db.dao.PlayEventDao
import tf.monochrome.android.ui.settings.settingsPageIds

/**
 * Where every achievement stands, for the whole app, and the ones just
 * completed, waiting for their unlocked pill.
 *
 * The one place an achievement is stamped complete: the Stats screen reads
 * [states] rather than working them out itself, so a goal finished while the
 * Stats screen is open is still announced, and one finished with no screen
 * watching is announced the next time the app is up ([pending] is kept until
 * the pill has shown it).
 *
 * Checked again on each play and on each entry in the achievement log (a
 * settings page opened, a plugin added, a country heard), off the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AchievementWatcher @Inject constructor(
    private val playEventDao: PlayEventDao,
    private val achievementStore: AchievementStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _pending = MutableStateFlow<List<Achievement>>(emptyList())

    /** Completed and not yet shown, oldest first; the pill takes them one at a time. */
    val pending: StateFlow<List<Achievement>> = _pending.asStateFlow()

    val states: StateFlow<List<AchievementState>> =
        combine(playEventDao.playCount().distinctUntilChanged(), achievementStore.log) { _, log -> log }
            .mapLatest { log -> evaluate(log) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The pill has shown [achievement]. */
    fun shown(achievement: Achievement) {
        _pending.update { it - achievement }
    }

    private suspend fun evaluate(log: FeatureLog): List<AchievementState> {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val month = StatsReportBuilder.window(StatsRange.Month, now, zone, null)
        val newArtists = playEventDao.artistFirstPlays().count { it.firstPlayed >= month.from }
        val since = month.today.minusDays(400).atStartOfDay(zone).toInstant().toEpochMilli()
        val days = playEventDao.momentsSince(since).mapTo(HashSet()) { Instant.ofEpochMilli(it.playedAt).atZone(zone).toLocalDate() }
        val facts = AchievementFacts(
            newArtistsLast30Days = newArtists,
            currentStreak = StatsReportBuilder.currentStreak({ it in days }, month.today),
            hiResSeconds = playEventDao.hiResSeconds(),
            playsAfterMidnight = playEventDao.playsAfterMidnight(),
            log = log,
        )
        val states = Achievements.evaluate(facts, settingsPageIds, SnapinType.availableTypes().map { it.name }, now)
        val fresh = Achievements.newlyUnlocked(states, log.unlockedAt)
        val announcing = achievementStore.announcing.first()
        if (fresh.isNotEmpty()) achievementStore.markUnlocked(fresh)
        if (!announcing) {
            // The first check since the pill arrived: what was already done
            // is stamped quietly, and only what is finished from now on shows.
            achievementStore.startAnnouncing()
        } else if (fresh.isNotEmpty()) {
            val done = Achievement.entries.filter { it.name in fresh }
            _pending.update { waiting -> waiting + done.filter { it !in waiting } }
        }
        return states
    }
}

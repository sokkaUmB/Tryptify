package tf.monochrome.android.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tf.monochrome.android.data.db.entity.PlayEventEntity
import tf.monochrome.android.ui.stats.QualityTier
import tf.monochrome.android.ui.stats.qualityTierOf

/** The achievement queries, held to the same rules as the stats the screen draws. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlayEventDaoTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MusicDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.playEventDao()

    @After
    fun close() = db.close()

    private fun at(hour: Int): Long =
        LocalDate.of(2026, 10, 20).atTime(hour, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `Golden Ears counts what the Quality card calls Hi-Res, and only that`() = runBlocking {
        val qualities = listOf("HI_RES_LOSSLESS", "hires", "HI-RES", "HI RES", "LOSSLESS", "FLAC", null, "")
        qualities.forEachIndexed { i, q ->
            dao.insert(PlayEventEntity(trackId = i.toLong(), title = "t$i", duration = 100, audioQuality = q))
        }
        val expected = qualities.count { qualityTierOf(it) == QualityTier.HI_RES } * 100L
        assertEquals(200L, expected)
        assertEquals(expected, dao.hiResSeconds())
    }

    @Test
    fun `Night Owl counts plays from midnight to the end of night`() = runBlocking {
        (0 until 24).forEach { h -> dao.insert(PlayEventEntity(trackId = h.toLong(), title = "t$h", playedAt = at(h))) }
        assertEquals(tf.monochrome.android.ui.stats.NIGHT_END_HOUR, dao.playsAfterMidnight())
    }
}

package tf.monochrome.android.audio.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import tf.monochrome.android.audio.eq.OutputId
import tf.monochrome.android.audio.eq.OutputSlot

class OutputSyncTest {

    private val earbuds = OutputId(OutputSlot.BLUETOOTH, "Galaxy Buds")
    private val car = OutputId(OutputSlot.BLUETOOTH, "Car Kit")
    private val speaker = OutputId(OutputSlot.SPEAKER)

    @Test
    fun aDeviceKeepsItsOwnDelayAndAnUnmeasuredOneTakesItsSlots() {
        var delays = OutputSync.set(emptyMap(), OutputId(OutputSlot.BLUETOOTH), 150)
        delays = OutputSync.set(delays, earbuds, 220)
        assertEquals(220, OutputSync.delayFor(earbuds, delays))
        assertEquals("a device never measured takes the slot's", 150, OutputSync.delayFor(car, delays))
        assertEquals("other outputs are untouched", 0, OutputSync.delayFor(speaker, delays))
        assertEquals("no output, no delay", 0, OutputSync.delayFor(null, delays))
    }

    @Test
    fun aDelayIsKeptInRange() {
        val delays = OutputSync.set(emptyMap(), earbuds, 5000)
        assertEquals(OutputSync.MAX_MS, OutputSync.delayFor(earbuds, delays))
        assertEquals(OutputSync.MIN_MS, OutputSync.delayFor(earbuds, mapOf(earbuds.key to -9000)))
    }

    @Test
    fun theOldDelaysCarryOverToBluetoothTheMeasuredOneFirst() {
        val bluetooth = OutputId(OutputSlot.BLUETOOTH).key
        assertEquals(mapOf(bluetooth to 180), OutputSync.migrate(tryptManiaMs = 180, lyricsMs = 300f, visualizerMs = 120))
        assertEquals(mapOf(bluetooth to 300), OutputSync.migrate(tryptManiaMs = 0, lyricsMs = 299.6f, visualizerMs = 120))
        assertEquals(mapOf(bluetooth to 120), OutputSync.migrate(tryptManiaMs = 0, lyricsMs = 0f, visualizerMs = 120))
        assertEquals("nothing set, nothing carried", emptyMap<String, Int>(), OutputSync.migrate(0, 0f, 0))
    }
}

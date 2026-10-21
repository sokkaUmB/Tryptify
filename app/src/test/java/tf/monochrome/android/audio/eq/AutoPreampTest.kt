// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.ToneControls

/** The AutoEQ preamp sits in front of the tone shelves too, so it sets their level. */
class AutoPreampTest {

    private val bassUp6 = ToneControls(bassGainDb = 6f)
    private val flatTone = ToneControls()
    private val boost9 = listOf(EqBand(id = 1, freq = 1_000f, gain = 9f, q = 1f))

    private fun applied(
        correctionOn: Boolean,
        automatic: Boolean,
        tone: ToneControls,
        manualDb: Float = -4f,
        l: List<EqBand> = boost9,
        r: List<EqBand> = l,
    ) = AutoPreamp.applied(correctionOn, automatic, manualDb, l, r, tone)

    @Test
    fun withTheCorrectionOffTheAutomaticPreampPullsDownTheBassBoost() {
        assertEquals(-6f, applied(correctionOn = false, automatic = true, tone = bassUp6), 0.1f)
    }

    @Test
    fun withTheCorrectionOffItsBandsAreNotCounted() {
        // The 9 dB correction band is not playing, so it must not cut the level.
        assertTrue(applied(correctionOn = false, automatic = true, tone = bassUp6) > -7f)
    }

    @Test
    fun withTheCorrectionOnTheAutomaticPreampCoversBoth() {
        assertTrue(applied(correctionOn = true, automatic = true, tone = bassUp6) <= -9f)
    }

    @Test
    fun aManualPreampSetsTheToneLevelWithTheCorrectionOff() {
        assertEquals(-4f, applied(correctionOn = false, automatic = false, tone = bassUp6), 0f)
    }

    @Test
    fun withNothingChangingTheSoundThereIsNoPreamp() {
        assertEquals(0f, applied(correctionOn = false, automatic = false, tone = flatTone), 0f)
        assertEquals(0f, applied(correctionOn = false, automatic = true, tone = flatTone), 0f)
        // A tone stage switched off is a bypass, whatever its knobs say.
        assertEquals(0f, applied(correctionOn = false, automatic = false, tone = bassUp6.copy(enabled = false)), 0f)
    }

    @Test
    fun theCorrectionAloneKeepsTheManualPreampAsBefore() {
        assertEquals(-4f, applied(correctionOn = true, automatic = false, tone = flatTone), 0f)
    }

    @Test
    fun cutsNeedNoPreamp() {
        val cut = ToneControls(bassGainDb = -6f, trebleGainDb = -3f)
        assertEquals(0f, applied(correctionOn = false, automatic = true, tone = cut), 0f)
    }

    @Test
    fun theHotterEarSetsIt() {
        val quiet = listOf(EqBand(id = 1, freq = 1_000f, gain = 2f))
        val hot = listOf(EqBand(id = 1, freq = 1_000f, gain = 8f))
        assertEquals(-8f, applied(correctionOn = true, automatic = true, tone = flatTone, l = quiet, r = hot), 0.1f)
    }
}

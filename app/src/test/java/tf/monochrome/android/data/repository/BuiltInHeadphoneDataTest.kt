package tf.monochrome.android.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.eq.EqDataParser
import tf.monochrome.android.domain.model.MeasurementRig

class BuiltInHeadphoneDataTest {

    @Test
    fun `built-in headphones include Nothing HeadPhone 1 and a on BK 5128`() {
        val headphones = BuiltInHeadphoneData.BUILT_IN_HEADPHONES
        val hp1 = headphones.find { it.id == "nothing_headphone_1" }
        assertNotNull("Nothing HeadPhone (1) should be present", hp1)
        assertEquals("Nothing HeadPhone (1)", hp1?.name)
        assertEquals("over-ear", hp1?.type)
        val meas1 = hp1?.measurements?.firstOrNull()
        assertNotNull(meas1)
        assertEquals("RTINGS", meas1?.source)
        assertEquals("builtin", meas1?.target)
        assertEquals(MeasurementRig.BK_5128, meas1?.rig)

        val hpa = headphones.find { it.id == "nothing_headphone_a" }
        assertNotNull("Nothing HeadPhone (a) should be present", hpa)
        assertEquals("Nothing HeadPhone (a)", hpa?.name)
        val measA = hpa?.measurements?.firstOrNull()
        assertNotNull(measA)
        assertEquals("RTINGS", measA?.source)
        assertEquals(MeasurementRig.BK_5128, measA?.rig)
    }

    @Test
    fun `raw measurement data parses correctly for Nothing HeadPhone 1 and a`() {
        val rawHp1 = BuiltInHeadphoneData.getMeasurementRawData("Nothing HeadPhone (1)")
        assertNotNull(rawHp1)
        val parsedHp1 = EqDataParser.parseRawData(rawHp1!!)
        assertTrue("Parsed curve should contain points", parsedHp1.size > 50)
        assertTrue("Curve should start at or below 20Hz", parsedHp1.first().freq <= 20.1f)
        assertTrue("Curve should reach high treble", parsedHp1.last().freq >= 15000f)

        val rawHpa = BuiltInHeadphoneData.getMeasurementRawData("Nothing HeadPhone (a)")
        assertNotNull(rawHpa)
        val parsedHpa = EqDataParser.parseRawData(rawHpa!!)
        assertTrue("Parsed curve should contain points", parsedHpa.size > 50)
    }

    @Test
    fun `built-in default presets contain 10-band RTINGS reference profiles`() {
        val presets = BuiltInHeadphoneData.DEFAULT_PRESETS
        assertEquals(2, presets.size)

        val hp1Preset = presets.find { it.id == "builtin_nothing_hp1_rtings_reference" }
        assertNotNull(hp1Preset)
        assertEquals("Nothing HeadPhone (1) - RTINGS Studio Reference", hp1Preset?.name)
        assertEquals(10, hp1Preset?.bands?.size)
        assertEquals(-3.5f, hp1Preset?.preamp ?: 0f, 0.001f)
        assertEquals(4800f, hp1Preset?.bands?.get(7)?.freq ?: 0f, 0.01f)
        assertEquals(3.5f, hp1Preset?.bands?.get(7)?.gain ?: 0f, 0.01f)

        val hpaPreset = presets.find { it.id == "builtin_nothing_hpa_rtings_reference" }
        assertNotNull(hpaPreset)
        assertEquals("Nothing HeadPhone (a) - RTINGS Studio Reference", hpaPreset?.name)
        assertEquals(10, hpaPreset?.bands?.size)
        assertEquals(-2.5f, hpaPreset?.preamp ?: 0f, 0.001f)
    }
}

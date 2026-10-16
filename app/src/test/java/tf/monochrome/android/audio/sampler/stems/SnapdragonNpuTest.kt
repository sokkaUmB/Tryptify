// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler.stems

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which Snapdragon the NPU is told it runs on. */
class SnapdragonNpuTest {

    @Test
    fun theEightEliteGenFiveIsSocModel87OnV81() {
        // The OnePlus 15 whose NPU would not start without it.
        assertEquals(SnapdragonNpu(87, 81), SnapdragonNpu.of("SM8850"))
        assertTrue(81 in SnapdragonNpu.ARCHS_THE_PROVIDER_TAKES)
    }

    @Test
    fun variantsAndCaseStillMatch() {
        assertEquals(SnapdragonNpu(87, 81), SnapdragonNpu.of(" sm8850-ac "))
    }

    @Test
    fun theEightEliteGivesItsSocModelButNotItsArch() {
        val npu = SnapdragonNpu.of("SM8750")!!
        assertEquals(69, npu.socModel)
        assertTrue(npu.htpArch !in SnapdragonNpu.ARCHS_THE_PROVIDER_TAKES)
    }

    @Test
    fun anUnknownChipIsLeftToTheDriver() {
        assertNull(SnapdragonNpu.of("SM9999"))
        assertNull(SnapdragonNpu.of(""))
        assertNull(SnapdragonNpu.of(null))
    }

    @Test
    fun onlyTheNewerNpusWidenTheirAddressSpace() {
        assertTrue(SnapdragonNpu.of("SM8850")!!.extendedUdma)
        assertTrue(SnapdragonNpu.of("SM8750")!!.extendedUdma)
        assertTrue(!SnapdragonNpu.of("SM8650")!!.extendedUdma)
    }
}

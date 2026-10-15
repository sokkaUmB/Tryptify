// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.glyph.engine.GlyphWheelSwish

/** The music wheel's "shh", and how often it may play. */
class GlyphWheelSwishTest {

    private val swish = GlyphWheelSwish.render()

    @Test
    fun everySwishIsTheSameSwish() {
        assertArrayEquals(swish, GlyphWheelSwish.render())
        assertEquals((GlyphWheelSwish.SAMPLE_RATE * GlyphWheelSwish.LENGTH_SECONDS).toInt(), swish.size)
    }

    @Test
    fun itPeaksAtItsLevelAndStartsAndEndsSilent() {
        val peak = swish.maxOf { abs(it.toInt()) }
        assertEquals(GlyphWheelSwish.AMPLITUDE * Short.MAX_VALUE, peak.toDouble(), 2.0)
        // Silent at both ends, so neither the start nor the cut clicks.
        assertTrue(abs(swish.first().toInt()) <= 2)
        assertTrue(abs(swish.last().toInt()) <= 2)
    }

    @Test
    fun itSwellsInOverItsAttack() {
        val perMs = GlyphWheelSwish.SAMPLE_RATE / 1_000
        val attackMs = (GlyphWheelSwish.ATTACK_SECONDS * 1_000).toInt()
        // Loudest at the end of the attack, not at its start.
        val peakAt = swish.indices.maxBy { abs(swish[it].toInt()) }
        assertTrue("peak at ${peakAt / perMs} ms", peakAt in (attackMs - 2) * perMs..(attackMs + 4) * perMs)
        // A straight swell: the first half of the attack carries a seventh of
        // the second half's energy (1/8 for a perfect ramp of noise).
        val energy = swish.map { it.toDouble() * it }
        val half = attackMs / 2 * perMs
        val ratio = energy.take(half).sum() / energy.subList(half, attackMs * perMs).sum()
        assertTrue("first/second half = $ratio", ratio in 0.08..0.25)
    }

    @Test
    fun itIsAHissNotATone() {
        // Goertzel power: the "shh" band carries the energy, the lows and the
        // very highs do not.
        val inBand = listOf(3_000.0, 4_000.0, 5_000.0).sumOf { power(it) }
        val low = listOf(300.0, 600.0, 900.0).sumOf { power(it) }
        val high = listOf(14_000.0, 16_000.0, 18_000.0).sumOf { power(it) }
        assertTrue("band=$inBand low=$low", inBand > low * 20)
        assertTrue("band=$inBand high=$high", inBand > high * 5)
    }

    @Test
    fun aFlingIsThinnedNotABuzz() {
        val gate = GlyphWheelSwish.Gate()
        val ms = 1_000_000L
        assertEquals(1f, gate.admit(0L))
        // Too soon after the last: dropped.
        assertNull(gate.admit(20 * ms))
        assertNull(gate.admit(54 * ms))
        // Far enough: through, and a little higher for the speed.
        val fast = gate.admit(60 * ms)!!
        assertTrue(fast > 1f && fast <= GlyphWheelSwish.FAST_RATE)
        // A slow turn plays at the swish's own pitch.
        assertEquals(1f, gate.admit(1_000 * ms))
    }

    @Test
    fun thePitchRisesSmoothlyWithSpeed() {
        val ms = 1_000_000L
        assertEquals(1f, GlyphWheelSwish.rateFor(400 * ms))
        assertEquals(GlyphWheelSwish.FAST_RATE, GlyphWheelSwish.rateFor(GlyphWheelSwish.MIN_GAP_NANOS), 1e-6f)
        var last = 0f
        for (gap in listOf(240, 200, 150, 100, 70, 55)) {
            val rate = GlyphWheelSwish.rateFor(gap * ms)
            assertTrue("gap $gap", rate >= last)
            last = rate
        }
    }

    private fun power(hz: Double): Double {
        val coefficient = 2.0 * cos(2.0 * PI * hz / GlyphWheelSwish.SAMPLE_RATE)
        var s1 = 0.0
        var s2 = 0.0
        for (sample in swish) {
            val s = sample + coefficient * s1 - s2
            s2 = s1
            s1 = s
        }
        return s1 * s1 + s2 * s2 - coefficient * s1 * s2
    }
}

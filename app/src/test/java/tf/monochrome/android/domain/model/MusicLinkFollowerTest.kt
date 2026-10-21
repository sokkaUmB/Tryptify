package tf.monochrome.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The follower's arithmetic: the band's level in, the setting's value out.
 * The native mixer runs the same steps, so these are also the numbers a knob
 * link has to reproduce.
 */
class MusicLinkFollowerTest {
    private val frame = 1f / 60f

    private fun MusicLinkFollower.run(db: Float, seconds: Float): Float {
        var v = 0f
        repeat((seconds / frame).toInt().coerceAtLeast(1)) { v = step(db, frame) }
        return v
    }

    @Test
    fun `silence rests at the lowest value`() {
        val f = MusicLinkFollower(LinkShape(lowest = 0.2f, highest = 0.8f))
        assertEquals(0.2f, f.run(-120f, 2f), 1e-4f)
    }

    @Test
    fun `going down rests at the highest value`() {
        val f = MusicLinkFollower(LinkShape(lowest = 0.2f, highest = 0.8f, way = LinkWay.DOWN))
        assertEquals(0.8f, f.run(-120f, 2f), 1e-4f)
    }

    @Test
    fun `a kick pushes it up and the release lets it fall back`() {
        val f = MusicLinkFollower(LinkShape(lowest = 0f, highest = 1f))
        f.run(-40f, 2f)
        val hit = f.run(-10f, 0.1f)
        assertTrue("a kick 30 dB over the floor should move it most of the way, got $hit", hit > 0.8f)
        val after = f.run(-40f, 2f)
        assertTrue("two seconds after the kick it should be back down, got $after", after < 0.05f)
    }

    @Test
    fun `a steady tone does not hold it up forever`() {
        // The floor rises under a held note, so a pad does not pin the setting.
        val f = MusicLinkFollower(LinkShape())
        f.run(-40f, 2f)
        f.run(-10f, 0.2f)
        val held = f.run(-10f, 30f)
        assertTrue("a note held for half a minute should have sunk into the floor, got $held", held < 0.2f)
    }

    @Test
    fun `snappy reacts faster than smooth`() {
        val snappy = MusicLinkFollower(LinkShape(feel = 0f, smoothing = LinkSmoothing.OFF))
        val smooth = MusicLinkFollower(LinkShape(feel = 1f, smoothing = LinkSmoothing.OFF))
        snappy.run(-40f, 2f)
        smooth.run(-40f, 2f)
        snappy.run(-10f, 0.02f)
        smooth.run(-10f, 0.02f)
        assertTrue(snappy.strength > smooth.strength)
    }

    @Test
    fun `feel sets attack and release together`() {
        assertEquals(1f, LinkShape.attackMsFor(0f), 1e-4f)
        assertEquals(60f, LinkShape.attackMsFor(1f), 1e-4f)
        assertEquals(40f, LinkShape.releaseMsFor(0f), 1e-3f)
        assertEquals(1200f, LinkShape.releaseMsFor(1f), 1e-2f)
    }

    @Test
    fun `anything under the quiet limit never moves it`() {
        val f = MusicLinkFollower(LinkShape(quietDb = -60f))
        f.run(-95f, 2f)
        repeat(20) {
            f.run(-70f, 0.1f)
            f.run(-95f, 0.1f)
        }
        assertEquals(0f, f.strength, 1e-6f)
    }

    @Test
    fun `a boost lifts a quiet band past the limit`() {
        val f = MusicLinkFollower(LinkShape(quietDb = -60f, boostDb = 20f))
        f.run(-95f, 2f)
        f.run(-70f, 0.1f)
        assertTrue(f.strength > 0.5f)
    }

    @Test
    fun `it goes to sleep after two seconds of silence and wakes on sound`() {
        val f = MusicLinkFollower(LinkShape())
        f.run(-120f, 2.2f)
        assertTrue(f.asleep)
        f.step(-10f, frame)
        assertFalse(f.asleep)
    }

    @Test
    fun `resting when silent can be switched off`() {
        val f = MusicLinkFollower(LinkShape(pauseWhenSilent = false))
        f.run(-120f, 5f)
        assertFalse(f.asleep)
    }

    @Test
    fun `whatever comes in, the value stays between lowest and highest`() {
        val rnd = Random(7)
        val inputs = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1e9f, -1e9f)
        for (way in LinkWay.entries) for (curve in LinkCurve.entries) for (formula in listOf("", "value + band * 10", "value - band * 99")) {
            val shape = LinkShape(lowest = 0.3f, highest = 0.6f, way = way, curve = curve, formula = formula)
            val f = MusicLinkFollower(shape)
            repeat(2000) { i ->
                val db = if (i % 97 == 0) inputs[(i / 97) % inputs.size] else rnd.nextFloat() * 120f - 100f
                val dt = if (i % 113 == 0) Float.NaN else rnd.nextFloat() * 0.2f
                val v = f.step(db, dt)
                assertTrue("$way $curve '$formula' gave $v", v.isFinite() && v in 0.3f..0.6f)
            }
        }
    }

    @Test
    fun `both ways rests in the middle`() {
        val f = MusicLinkFollower(LinkShape(lowest = 0f, highest = 1f, way = LinkWay.BOTH))
        // With the band at its own average there is no swing either way.
        assertEquals(0.5f, MusicLinkFollower.valueFor(0.4f, 0.4f, f.shape), 1e-5f)
        assertTrue(MusicLinkFollower.valueFor(0.9f, 0.4f, f.shape) > 0.9f)
        assertTrue(MusicLinkFollower.valueFor(0.0f, 0.4f, f.shape) < 0.1f)
    }

    @Test
    fun `curves bend the response as their names say`() {
        assertEquals(0.25f, MusicLinkFollower.curved(0.5f, LinkCurve.GENTLE), 1e-6f)
        assertEquals(0.5f, MusicLinkFollower.curved(0.5f, LinkCurve.EVEN), 1e-6f)
        assertEquals(0.70710677f, MusicLinkFollower.curved(0.5f, LinkCurve.STRONG), 1e-6f)
    }

    @Test
    fun `bins sit on the analyser's log axis`() {
        assertEquals(0, MusicLinkFollower.binFor(20f))
        assertEquals(255, MusicLinkFollower.binFor(20_000f))
        // The geometric middle of 20 Hz..20 kHz is the middle bin.
        assertEquals(127, MusicLinkFollower.binFor(632.5f))
    }

    @Test
    fun `a band's level is the mean of its own bins`() {
        val bins = FloatArray(256) { -30f }
        val a = MusicLinkFollower.binFor(LinkBand.KICK.loHz)
        val b = MusicLinkFollower.binFor(LinkBand.KICK.hiHz)
        for (i in a..b) bins[i] = 6f
        assertEquals(6f, MusicLinkFollower.bandLevel(bins, LinkBand.KICK.loHz, LinkBand.KICK.hiHz), 1e-4f)
        assertEquals(-30f, MusicLinkFollower.bandLevel(bins, LinkBand.HATS.loHz, LinkBand.HATS.hiHz), 1e-4f)
    }

    @Test
    fun `a new link rests at the current value and reaches toward the roomier end`() {
        val (a, b) = LinkShape.startingRange(0.2f, 0f, 1f)
        assertEquals(0.2f, a, 1e-5f)
        assertEquals(0.6f, b, 1e-5f)
        val (lo, hi) = LinkShape.startingRange(0.9f, 0f, 1f)
        assertEquals(0.45f, lo, 1e-5f)
        assertEquals(0.9f, hi, 1e-5f)
    }

    @Test
    fun `clamping repairs a hand-edited shape`() {
        val bad = LinkShape(
            band = LinkBand.CUSTOM, loHz = 5000f, hiHz = 100f, lowest = Float.NaN, highest = 9f,
            feel = 4f, quietDb = 20f, boostDb = Float.POSITIVE_INFINITY, formula = "x".repeat(500),
        ).clamped(0f, 1.5f)
        assertEquals(0f, bad.lowest, 0f)
        assertEquals(1.5f, bad.highest, 0f)
        assertEquals(1f, bad.feel, 0f)
        assertEquals(0f, bad.quietDb, 0f)
        assertEquals(0f, bad.boostDb, 0f)
        assertTrue(bad.hiHz >= bad.loHz * LinkShape.MIN_BAND_RATIO)
        assertEquals(LinkFormula.MAX_LENGTH, bad.formula.length)
    }
}

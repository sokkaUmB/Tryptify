package tf.monochrome.android.audio.dsp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.LinkBand
import tf.monochrome.android.domain.model.LinkShape

/**
 * The knob links beside the mix's state: carried with it, stripped before the
 * engine sees it, moved by chain edits as the engine moves its own copy, and
 * handed over in the exact layout `DspEngine::setModRoutes` reads.
 */
class KnobLinkCodecTest {
    private val state = """{"buses":[{"gain":0,"pan":0,"muted":false,"soloed":false,"inputEnabled":true,"plugins":[{"type":2,"bypassed":false,"dryWet":1,"os":1,"params":[0,1000,0.7,0,0]}]}]}"""

    private val cutoff = KnobLink(
        id = 3, bus = 0, slot = 0, param = 1,
        source = KnobSource.BUS,
        shape = LinkShape(band = LinkBand.CUSTOM, loHz = 200f, hiHz = 900f, lowest = 400f, highest = 4000f, formula = "value + band * 0.5"),
    )

    @Test
    fun `links ride along with the state and come off it whole`() {
        val withLinks = KnobLinkCodec.attach(state, listOf(cutoff))
        assertTrue(withLinks.contains("\"knobLinks\""))
        assertEquals(listOf(cutoff), KnobLinkCodec.read(withLinks))
        assertEquals(state, KnobLinkCodec.strip(withLinks))
    }

    @Test
    fun `they live beside the upmix key without either eating the other`() {
        val both = KnobLinkCodec.attach(MixUpmix.attach(state, true), listOf(cutoff))
        assertTrue(MixUpmix.isOn(both))
        assertEquals(listOf(cutoff), KnobLinkCodec.read(both))
        val engine = KnobLinkCodec.strip(MixUpmix.strip(both))
        assertEquals(state, engine)
        // Attaching again replaces rather than stacking.
        val again = KnobLinkCodec.attach(both, emptyList())
        assertFalse(again.contains("knobLinks"))
        assertTrue(MixUpmix.isOn(again))
    }

    @Test
    fun `a mix without links, or with a broken key, reads as none`() {
        assertEquals(emptyList<KnobLink>(), KnobLinkCodec.read(state))
        assertEquals(emptyList<KnobLink>(), KnobLinkCodec.read("""{"buses":[],"knobLinks":[{"id":"x"""))
        assertEquals(emptyList<KnobLink>(), KnobLinkCodec.read("""{"buses":[],"knobLinks":"no"}"""))
    }

    @Test
    fun `one link per knob and per id`() {
        val twice = listOf(cutoff, cutoff.copy(id = 4), cutoff.copy(param = 2), cutoff.copy(id = -1, param = 3))
        val clean = KnobLinkCodec.sanitize(twice)
        assertEquals(listOf(cutoff), clean)
    }

    @Test
    fun `chain edits move links as the engine does`() {
        val a = cutoff.copy(id = 1, slot = 0)
        val b = cutoff.copy(id = 2, slot = 2)
        val other = cutoff.copy(id = 9, bus = 1, slot = 0)
        val links = listOf(a, b, other)

        val inserted = KnobLinkCodec.afterInsert(links, bus = 0, slot = 1)
        assertEquals(listOf(0, 3, 0), inserted.map { it.slot })

        val removed = KnobLinkCodec.afterRemove(links, bus = 0, slot = 0)
        assertEquals(listOf(2 to 1, 9 to 0), removed.map { it.id to it.slot })

        // modroute::slotAfterMove, case by case.
        assertEquals(2, KnobLinkCodec.slotAfterMove(0, 0, 2))
        assertEquals(0, KnobLinkCodec.slotAfterMove(1, 0, 2))
        assertEquals(1, KnobLinkCodec.slotAfterMove(2, 0, 2))
        assertEquals(3, KnobLinkCodec.slotAfterMove(3, 0, 2))
        assertEquals(0, KnobLinkCodec.slotAfterMove(2, 2, 0))
        assertEquals(1, KnobLinkCodec.slotAfterMove(0, 2, 0))
        val moved = KnobLinkCodec.afterMove(links, bus = 0, from = 2, to = 0)
        assertEquals(listOf(1, 0, 0), moved.map { it.slot })

        val buses = listOf(cutoff.copy(id = 1, bus = 5), cutoff.copy(id = 2, bus = 6), cutoff.copy(id = 3, bus = 4))
        assertEquals(listOf(2 to 5, 3 to 4), KnobLinkCodec.afterRemoveBus(buses, 5).map { it.id to it.bus })
    }

    @Test
    fun `the engine gets its layout exactly`() {
        val paused = cutoff.copy(paused = true, shape = cutoff.shape.copy(pauseWhenSilent = false))
        val data = KnobLinkCodec.toNative(listOf(paused, cutoff.copy(id = 5, param = 2, shape = LinkShape())))
        // value + band * 0.5 compiles to five op pairs.
        val programLen = 10
        assertEquals(2 * KnobLinkCodec.NATIVE_HEADER + programLen, data.size)
        assertEquals(3f, data[0])
        assertEquals(1f, data[3])
        assertEquals(KnobSource.BUS.ordinal.toFloat(), data[4])
        assertEquals(200f, data[5])
        assertEquals(900f, data[6])
        assertEquals(400f, data[7])
        assertEquals(4000f, data[8])
        assertEquals(LinkShape().attackMs, data[10], 1e-4f)
        // Not resting when silent, and paused: flags 0b10.
        assertEquals(2f, data[16])
        assertEquals(programLen.toFloat(), data[17])
        val second = KnobLinkCodec.NATIVE_HEADER + programLen
        assertEquals(5f, data[second])
        // A preset band sends its own edges, whatever the stored ones say.
        assertEquals(LinkBand.KICK.loHz, data[second + 5])
        assertEquals(1f, data[second + 16])
        assertEquals(0f, data[second + 17])
    }

    @Test
    fun `a broken formula sends no program rather than a broken one`() {
        val data = KnobLinkCodec.toNative(listOf(cutoff.copy(shape = cutoff.shape.copy(formula = "value +"))))
        assertEquals(KnobLinkCodec.NATIVE_HEADER, data.size)
        assertEquals(0f, data[17])
    }
}

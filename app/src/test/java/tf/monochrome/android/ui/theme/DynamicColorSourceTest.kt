package tf.monochrome.android.ui.theme

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One source for the app's dynamic colours: the playing track, or a screen's picked song. */
class DynamicColorSourceTest {

    @After
    fun clear() {
        DynamicColorSource.override = null
    }

    @Test
    fun `with nothing standing in, the playing track colours the app`() {
        assertEquals("playing", DynamicColorSource.coverFor("playing"))
        assertEquals(3000, DynamicColorSource.blendFor(3000))
    }

    @Test
    fun `a picked song stands in for the playing track, and fast`() {
        DynamicColorSource.override = DynamicColorSource.Override(Any(), "picked", 500)
        // The picked song's colours, never the playing track's beside them.
        assertEquals("picked", DynamicColorSource.coverFor("playing"))
        // The listener's long blend is capped; a shorter one is kept.
        assertEquals(500, DynamicColorSource.blendFor(3000))
        assertEquals(250, DynamicColorSource.blendFor(250))
        assertEquals(0, DynamicColorSource.blendFor(0))
    }

    @Test
    fun `a picked song with no art is the plain theme, not the playing track`() {
        DynamicColorSource.override = DynamicColorSource.Override(Any(), null, 500)
        assertNull(DynamicColorSource.coverFor("playing"))
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The chart server a build ships with: added once, kept where the player puts it, gone when removed. */
class BuiltInServerTest {

    private val built = "https://parser.example.app/abc123"
    private val tidal = ApiServer(url = "https://tidal.example.org", services = setOf(ApiService.TIDAL))

    @Test
    fun aFreshInstallGetsItLastAsACpuStemServer() {
        val seeded = BuiltInServer.seed(listOf(tidal), built, seeded = null)!!
        assertEquals(listOf(tidal, BuiltInServer.entry(built)), seeded)
        assertEquals(setOf(ApiService.STEMS), seeded.last().services)
        assertEquals(false, seeded.last().separatesOnGpu)
        // Slow finds it before it has been asked anything.
        assertEquals(listOf(seeded.last()), tf.monochrome.android.audio.stepmania.RemoteStemAnalysis.planFor(seeded, tf.monochrome.android.audio.stepmania.ChartMode.SLOW))
    }

    @Test
    fun itIsAddedOnceAndARemovedOneStaysRemoved() {
        val once = BuiltInServer.seed(emptyList(), built, seeded = null)!!
        assertNull(BuiltInServer.seed(once, built, seeded = built))
        assertNull(BuiltInServer.seed(emptyList(), built, seeded = built))
    }

    @Test
    fun thePlayersOwnEntryForTheHostIsLeftAlone() {
        val own = ApiServer(url = "https://PARSER.example.app/their-secret", services = setOf(ApiService.STEMS), stemDevice = "cpu")
        assertNull(BuiltInServer.seed(listOf(own), built, seeded = null))
    }

    @Test
    fun aNewAddressMovesTheEntryItAddedInPlace() {
        val old = "https://parser.example.app/old"
        val list = listOf(BuiltInServer.entry(old).copy(checkedAt = 5L), tidal)
        val moved = BuiltInServer.seed(list, built, seeded = old)!!
        assertEquals(listOf(BuiltInServer.entry(built).copy(checkedAt = 5L), tidal), moved)
        // The player had removed the old one: the new one is not forced back.
        assertNull(BuiltInServer.seed(listOf(tidal), built, seeded = old))
    }

    @Test
    fun aBuildWithoutOneAddsNothing() {
        assertNull(BuiltInServer.seed(listOf(tidal), "", seeded = null))
        assertNull(BuiltInServer.seed(listOf(tidal), "not a url", seeded = null))
    }
}

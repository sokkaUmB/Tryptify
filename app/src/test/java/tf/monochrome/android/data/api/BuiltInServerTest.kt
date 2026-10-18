// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chart server a build ships with: always in the list, never removable, its secret never shown. */
class BuiltInServerTest {

    private val built = "https://parser.example.app/abc123"
    private val tidal = ApiServer(url = "https://tidal.example.org", services = setOf(ApiService.TIDAL))

    @Test
    fun itIsAlwaysThereLastAsACpuStemServer() {
        val list = BuiltInServer.ensure(listOf(tidal), built)!!
        assertEquals(listOf(tidal, BuiltInServer.entry(built)), list)
        assertEquals(false, list.last().separatesOnGpu)
        // It separates for charts and times lyrics, so both find it before it is probed.
        assertEquals(setOf(ApiService.STEMS, ApiService.LYRICS), list.last().services)
        // Slow finds it before it has been asked anything.
        assertEquals(
            listOf(list.last()),
            tf.monochrome.android.audio.stepmania.RemoteStemAnalysis.planFor(list, tf.monochrome.android.audio.stepmania.ChartMode.SLOW),
        )
        // There already: nothing to change.
        assertNull(BuiltInServer.ensure(list, built))
    }

    @Test
    fun removedItComesBack() {
        val list = BuiltInServer.ensure(emptyList(), built)!!
        val removed = list.filterNot { it.url == built }
        assertEquals(list, BuiltInServer.ensure(removed, built))
    }

    @Test
    fun anEntryForItsHostBecomesItInPlace() {
        val own = ApiServer(url = "https://PARSER.example.app/old", services = setOf(ApiService.STEMS), checkedAt = 5L, stemDevice = "cpu")
        val other = ApiServer(url = "https://parser.example.app", services = emptySet())
        val list = BuiltInServer.ensure(listOf(own, tidal, other), built)!!
        assertEquals(listOf(own.copy(url = built, services = setOf(ApiService.STEMS, ApiService.LYRICS)), tidal), list)
    }

    @Test
    fun itIsKnownAndShownWithoutItsSecret() {
        assertTrue(BuiltInServer.isBuiltIn(built, built))
        assertFalse(BuiltInServer.isBuiltIn(tidal.url, built))
        assertEquals("parser.example.app", BuiltInServer.shownAs(built))
    }

    @Test
    fun theRoomsServerShipsBesideIt() {
        val rooms = "https://rooms.example.app/def456"
        val list = BuiltInServer.ensureAll(listOf(tidal), built, rooms)!!
        assertEquals(listOf(tidal, BuiltInServer.entry(built), BuiltInServer.entry(rooms, setOf(ApiService.MULTIPLAYER))), list)
        // A rooms server separates nothing, so it says nothing of a device.
        assertNull(list.last().stemDevice)
        assertEquals(setOf(ApiService.MULTIPLAYER), list.last().services)
        // Both there: nothing to change.
        assertNull(BuiltInServer.ensureAll(list, built, rooms))
        assertTrue(BuiltInServer.isBuiltIn(rooms, built, rooms))
        // A build with only the chart server adds only it.
        assertEquals(listOf(tidal, BuiltInServer.entry(built)), BuiltInServer.ensureAll(listOf(tidal), built, ""))
    }

    @Test
    fun aBuildWithoutOneAddsNothing() {
        assertNull(BuiltInServer.ensure(listOf(tidal), ""))
        assertFalse(BuiltInServer.isBuiltIn(tidal.url, ""))
    }
}

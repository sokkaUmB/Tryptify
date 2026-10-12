package tf.monochrome.android.ui.discover.galaxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.LibraryAlbum
import tf.monochrome.android.domain.model.LibraryTrack
import kotlin.math.hypot

/**
 * An artist's system: albums in release order, the most played as planets
 * and the rest a belt, tracks as moons and the rest a ring — and a play
 * changing how a planet glows, never where it is.
 */
class LocalSystemsTest {

    private var nextId = 0L

    private fun album(title: String, year: Int?, tracks: Int, plays: Int = 0, recent: Int = 0) = LibraryAlbum(
        key = "${title.lowercase()}|artist|${year ?: 0}",
        title = title,
        year = year,
        artworkUri = null,
        tracks = (1..tracks).map { n ->
            LibraryTrack(
                id = nextId++,
                key = "/music/$title/$n.flac",
                title = "$title $n",
                discNumber = 1,
                trackNumber = n,
                durationSeconds = 120 + n * 10,
            )
        },
        plays = plays,
        recentPlays = recent,
    )

    private fun allTracks(sys: PlanetSystem) = (sys.planets + sys.belt).sumOf { it.moons.size + it.ringCount }

    @Test
    fun `no albums, no system`() {
        assertNull(LocalSystems.build("artist:x", emptyList()))
        assertNull(LocalSystems.build("artist:x", listOf(album("Empty", 2000, 0))))
    }

    @Test
    fun `the debut orbits nearest, each later record further out`() {
        val sys = LocalSystems.build(
            "artist:x",
            listOf(album("Third", 2010, 10), album("First", 1995, 12), album("Undated", null, 8), album("Second", 2001, 9)),
        )!!
        assertEquals(listOf("First", "Second", "Third", "Undated"), sys.planets.map { it.label })
        for (k in 1 until sys.planets.size) assertTrue(sys.planets[k].orbit > sys.planets[k - 1].orbit)
    }

    @Test
    fun `the most played albums are planets and the rest are the belt, everything still there`() {
        val albums = (1..12).map { album("Album $it", 1990 + it, 10, plays = if (it % 3 == 0) 100 else it) }
        val sys = LocalSystems.build("artist:x", albums)!!
        assertEquals(SystemLimits.LIBRARY.maxPlanets, sys.planets.size)
        assertEquals(12 - SystemLimits.LIBRARY.maxPlanets, sys.beltCount)
        // The four played a hundred times are all planets.
        assertTrue(listOf(3, 6, 9, 12).all { n -> sys.planets.any { it.label == "Album $n" } })
        assertEquals(12 * 10, allTracks(sys))
        // In orbit, still in release order.
        assertEquals(sys.planets.sortedBy { it.subtitle }.map { it.label }, sys.planets.map { it.label })
    }

    @Test
    fun `the most played tracks are moons in running order, the rest the ring`() {
        val a = album("Long", 2000, 14)
        val played = a.copy(tracks = a.tracks.mapIndexed { i, t -> if (i == 12 || i == 3) t.copy(plays = 50) else t })
        val planet = LocalSystems.build("artist:x", listOf(played))!!.planets.single()
        assertEquals(SystemLimits.LIBRARY.maxMoons, planet.moons.size)
        assertEquals(14 - SystemLimits.LIBRARY.maxMoons, planet.ringCount)
        val titles = planet.moons.map { it.title }
        assertTrue("Long 13" in titles && "Long 4" in titles)
        val numbers = titles.map { it.removePrefix("Long ").toInt() }
        assertEquals(numbers.sorted(), numbers)
        assertTrue(planet.moons.all { it.ref is MoonRef.Local })
    }

    @Test
    fun `a play changes how a planet glows, never where it is`() {
        val albums = listOf(album("A", 1990, 10), album("B", 1995, 10), album("C", 2000, 10))
        val cold = LocalSystems.build("artist:x", albums)!!
        val hot = LocalSystems.build("artist:x", albums.map { if (it.title == "B") it.copy(plays = 5, recentPlays = 5) else it })!!
        assertEquals(cold.planets.map { it.label to it.orbit }, hot.planets.map { it.label to it.orbit })
        assertEquals(0f, cold.planets[1].glow, 0f)
        assertEquals(1f, hot.planets[1].glow, 0f)
        assertEquals(0f, hot.planets[0].glow, 0f)
    }

    @Test
    fun `neighbouring planets' moons never cross, nor reach the belt`() {
        for (star in listOf(GalaxyScene.STAR_RADIUS_MIN, 3.5f, GalaxyScene.STAR_RADIUS_MAX)) {
            val albums = (1..12).map { album("Album $it", 1990 + it, 6 + it) }
            val sys = LocalSystems.build("artist:x", albums, starRadius = star)!!
            for (k in 1 until sys.planets.size) {
                val inner = sys.planets[k - 1]
                val outer = sys.planets[k]
                assertTrue("moons cross at star $star", inner.orbit + inner.reach < outer.orbit - outer.reach)
            }
            val last = sys.planets.last()
            assertTrue("the belt swallows a moon at star $star", last.orbit + last.reach < sys.beltInner)
            // The camera frames the whole system, belt and all, before it arrives.
            assertTrue(sys.reach <= PlanetSystem.reachFor(star, SystemLimits.LIBRARY) + 1e-3f)
        }
    }

    @Test
    fun `a ring sits between its planet and its first moon`() {
        val planet = LocalSystems.build("artist:x", listOf(album("Long", 2000, 20)))!!.planets.single()
        val firstMoon = planet.moons.minOf { it.orbit }
        assertTrue(planet.radius * RING_OUTER_RADII < firstMoon)
    }

    @Test
    fun `a longer album is a bigger planet, never bigger than its star allows`() {
        val sys = LocalSystems.build("artist:x", listOf(album("EP", 2000, 4), album("Double", 2001, 30)))!!
        val ep = sys.planets.first { it.label == "EP" }
        val double = sys.planets.first { it.label == "Double" }
        assertTrue(double.radius > ep.radius)
        assertTrue(double.radius <= sys.starRadius / 8f + 1e-4f)
    }

    @Test
    fun `a rescan leaves the system as it was`() {
        val a = listOf(album("A", 1990, 10), album("B", 1995, 7))
        nextId = 1000
        val b = listOf(album("A", 1990, 10), album("B", 1995, 7))
        val one = LocalSystems.build("artist:x", a)!!
        val two = LocalSystems.build("artist:x", b)!!
        for (p in one.planets.indices) {
            assertEquals(one.planets[p].phase, two.planets[p].phase, 0f)
            assertEquals(one.planets[p].hue, two.planets[p].hue, 0f)
            assertEquals(one.planets[p].moons.map { it.phase }, two.planets[p].moons.map { it.phase })
        }
        val spot = FloatArray(3)
        one.moonPosition(0, 0, 12.5f, 0f, 0f, 0f, spot)
        val again = FloatArray(3)
        two.moonPosition(0, 0, 12.5f, 0f, 0f, 0f, again)
        assertTrue(hypot(spot[0] - again[0], spot[2] - again[2]) < 1e-5f)
    }

    private companion object {
        /** The ring's outer edge, in its planet's radii: as the map draws it. */
        const val RING_OUTER_RADII = 1.9f
    }
}

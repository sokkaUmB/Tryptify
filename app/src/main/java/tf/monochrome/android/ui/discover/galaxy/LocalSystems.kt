package tf.monochrome.android.ui.discover.galaxy

import tf.monochrome.android.domain.model.LibraryAlbum
import tf.monochrome.android.domain.model.LibraryTrack
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * An artist's system: their albums as planets, each album's tracks as moons.
 *
 * **Release order.** The debut orbits nearest the star and each later record
 * further out, so a system reads as a career spiralling outwards, and
 * playing something never rearranges it. How much an album is being played
 * lately is its atmosphere's glow ([PlanetSystem.Planet.glow]), not its orbit.
 *
 * At most [SystemLimits.maxPlanets] albums are planets: the most played, ties
 * to the longest. The rest are the asteroid belt past the last orbit
 * ([PlanetSystem.belt]). On each planet, at most [SystemLimits.maxMoons]
 * tracks are moons — the most played, ties to the running order — laid out in
 * the album's own order; the rest are its ring ([PlanetSystem.Planet.ring]).
 * Belt and ring are still listed in full in the system's sheet.
 *
 * A planet's size is the album's length in tracks, a moon's its track's
 * running time. Everything round a body (phase, tilt, colour) comes from its
 * own key, so a rescan leaves the system as it was.
 */
object LocalSystems {

    fun build(
        starId: String,
        albums: List<LibraryAlbum>,
        starRadius: Float = PlanetSystem.DEFAULT_STAR_RADIUS,
        limits: SystemLimits = SystemLimits.LIBRARY,
    ): PlanetSystem? {
        val real = albums.filter { it.tracks.isNotEmpty() }
        if (real.isEmpty()) return null

        val chosen = real
            .sortedWith(compareByDescending<LibraryAlbum> { it.plays }.thenByDescending { it.tracks.size }.thenBy { it.key })
            .take(limits.maxPlanets)
        val inOrbit = chosen.sortedWith(releaseOrder)
        val belt = (real - chosen.toSet()).sortedWith(releaseOrder)

        val hottest = real.maxOf { it.recentPlays }.coerceAtLeast(0)
        fun glowOf(a: LibraryAlbum) = if (hottest == 0) 0f else a.recentPlays / hottest.toFloat()

        val first = PlanetSystem.firstOrbit(starRadius)
        val step = PlanetSystem.orbitSpacing(starRadius, limits)
        val planets = inOrbit.mapIndexed { k, album ->
            planet(album, orbit = first + k * step, first, starRadius, limits, glowOf(album))
        }
        val beltOrbit = PlanetSystem.beltInner(starRadius, limits)
        val belted = belt.map { planet(it, orbit = beltOrbit, first, starRadius, limits, glowOf(it)) }
        return PlanetSystem(starId, planets, starRadius, limits = limits, belt = belted)
    }

    /** Oldest first; undated last; then by title, then key, so the order is the same every time. */
    private val releaseOrder = compareBy<LibraryAlbum>({ it.year ?: Int.MAX_VALUE }, { it.title.lowercase() }, { it.key })

    /** How long an album has to be, in tracks, to be as big as a planet gets. */
    const val ALBUM_FULL = 24

    /** How big an album of [tracks] tracks is, 0..1, on a log scale up to [ALBUM_FULL]. */
    fun albumShare(tracks: Int): Float =
        (ln(1f + tracks.coerceAtLeast(0)) / ln(1f + ALBUM_FULL)).coerceIn(0f, 1f)

    private fun planet(
        album: LibraryAlbum,
        orbit: Float,
        firstOrbit: Float,
        starRadius: Float,
        limits: SystemLimits,
        glow: Float,
    ): PlanetSystem.Planet {
        val seed = PlanetSystem.stableHash(album.key)
        val radius = starRadius * (PlanetSystem.PLANET_MIN_SHARE + PlanetSystem.PLANET_SPAN_SHARE * albumShare(album.tracks.size))
        val running = compareBy<LibraryTrack>({ it.discNumber ?: 1 }, { it.trackNumber ?: Int.MAX_VALUE }, { it.title.lowercase() }, { it.key })
        val moonTracks = album.tracks
            .sortedWith(compareByDescending<LibraryTrack> { it.plays }.then(running))
            .take(limits.maxMoons)
            .sortedWith(running)
        val ring = (album.tracks - moonTracks.toSet()).sortedWith(running)
        val longest = moonTracks.maxOfOrNull { it.durationSeconds }?.coerceAtLeast(1) ?: 1
        return PlanetSystem.Planet(
            label = album.title,
            share = glow,
            releases = null,
            orbit = orbit,
            period = PlanetSystem.FIRST_PERIOD * (orbit / firstOrbit).pow(1.5f),
            phase = PlanetSystem.unit(seed) * PlanetSystem.TAU,
            tilt = (PlanetSystem.unit(seed ushr 8) - 0.5f) * 0.36f,
            node = PlanetSystem.unit(seed ushr 24) * PlanetSystem.TAU,
            radius = radius,
            hue = PlanetSystem.unit(seed ushr 16) * 360f,
            artworkUrl = album.artworkUri,
            moons = moonTracks.mapIndexed { m, track ->
                val moonSeed = PlanetSystem.stableHash(track.key)
                PlanetSystem.Moon(
                    ref = track.ref(),
                    orbit = PlanetSystem.moonOrbit(starRadius, m),
                    period = PlanetSystem.MOON_PERIOD * (1f + m * 0.55f),
                    phase = PlanetSystem.unit(moonSeed) * PlanetSystem.TAU,
                    tilt = (PlanetSystem.unit(moonSeed ushr 8) - 0.5f) * 1.1f,
                    node = PlanetSystem.unit(moonSeed ushr 24) * PlanetSystem.TAU,
                    radius = radius * (0.16f + 0.12f * sqrt(track.durationSeconds.coerceAtLeast(0) / longest.toFloat())),
                )
            },
            key = album.key,
            glow = glow,
            ring = ring.map { it.ref() },
            subtitle = album.year?.toString(),
        )
    }

    private fun LibraryTrack.ref() = MoonRef.Local(trackId = id, key = key, title = title)
}

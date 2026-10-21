package tf.monochrome.android.audio.dsp.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tf.monochrome.android.domain.model.LinkFormula
import tf.monochrome.android.domain.model.LinkShape

/** What a knob's link listens to. */
@Serializable
enum class KnobSource {
    /** The song as it comes into the mixer. */
    SONG,

    /** What reaches the knob's own bus, before its effects. */
    BUS,
}

/**
 * A mixer knob following the music: parameter [param] of the effect in slot
 * [slot] on bus [bus] (engine indices), moved by [shape] as a visual link is.
 * [id] is stable for the life of the link, so a change to it keeps its
 * follower running instead of starting over.
 */
@Serializable
data class KnobLink(
    val id: Int,
    val bus: Int,
    val slot: Int,
    val param: Int,
    val source: KnobSource = KnobSource.SONG,
    val shape: LinkShape = LinkShape(),
    val paused: Boolean = false,
) {
    /** The key the knob and its link host share. */
    val key: String get() = keyOf(bus, slot, param)

    companion object {
        fun keyOf(bus: Int, slot: Int, param: Int) = "$bus:$slot:$param"
    }
}

/**
 * The knob links, carried in the mix's state JSON as a top-level
 * `"knobLinks":[…]` after the bus list, as [MixUpmix] carries the upmix: so
 * they travel with the saved mix, presets, export and cloud sync, and a build
 * from before them ignores the key. Attached and stripped on the Kotlin side;
 * the engine gets them as plain numbers through [toNative].
 *
 * The edits that move effects (an insert, a removal, a move, a bus going) move
 * the links the same way the engine does (`dsp_engine.cpp`), so the two never
 * disagree about which knob a link is on.
 */
object KnobLinkCodec {
    const val KEY = "knobLinks"

    /** Floats before each link's program: DspEngine::MOD_HEADER. */
    const val NATIVE_HEADER = 18

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** The links [stateJson] carries; none when it has no key or a broken one. */
    fun read(stateJson: String): List<KnobLink> {
        val (start, end) = find(stateJson) ?: return emptyList()
        val body = stateJson.substring(stateJson.indexOf('[', start), end + 1)
        return runCatching { json.decodeFromString<List<KnobLink>>(body) }.getOrNull()
            ?.let(::sanitize)
            .orEmpty()
    }

    /** [stateJson] without the key, for the engine. */
    fun strip(stateJson: String): String {
        val (start, end) = find(stateJson) ?: return stateJson
        // Take the comma before the key with it.
        var from = start
        while (from > 0 && stateJson[from - 1].isWhitespace()) from--
        if (from > 0 && stateJson[from - 1] == ',') from--
        return stateJson.substring(0, from) + stateJson.substring(end + 1)
    }

    /** [stateJson] with the key set to [links] (absent when there are none). */
    fun attach(stateJson: String, links: List<KnobLink>): String {
        val bare = strip(stateJson).trimEnd()
        if (links.isEmpty() || !bare.endsWith("}")) return bare
        val body = bare.dropLast(1).trimEnd()
        val sep = if (body.endsWith("{")) "" else ","
        return "$body$sep\"$KEY\":${json.encodeToString(links)}}"
    }

    /** One link per knob and per id, ids and indices non-negative. */
    fun sanitize(links: List<KnobLink>): List<KnobLink> =
        links.filter { it.id >= 0 && it.bus >= 0 && it.slot >= 0 && it.param >= 0 }
            .distinctBy { it.id }
            .distinctBy { it.key }
            .take(MAX_LINKS)

    /** The flat form the engine reads (DspEngine::setModRoutes). */
    fun toNative(links: List<KnobLink>): FloatArray {
        val out = ArrayList<Float>(links.size * (NATIVE_HEADER + 8))
        for (link in links.take(MAX_LINKS)) {
            val s = link.shape
            val program = LinkFormula.compile(s.formula).program ?: FloatArray(0)
            val flags = (if (s.pauseWhenSilent) 1 else 0) or (if (link.paused) 2 else 0)
            out += link.id.toFloat()
            out += link.bus.toFloat()
            out += link.slot.toFloat()
            out += link.param.toFloat()
            out += link.source.ordinal.toFloat()
            out += s.effectiveLoHz
            out += s.effectiveHiHz
            out += s.lowest
            out += s.highest
            out += s.way.ordinal.toFloat()
            out += s.attackMs
            out += s.releaseMs
            out += s.curve.ordinal.toFloat()
            out += s.quietDb
            out += s.boostDb
            out += s.smoothing.cutoffHz
            out += flags.toFloat()
            out += program.size.toFloat()
            program.forEach { out += it }
        }
        return out.toFloatArray()
    }

    // ── The engine's chain edits, mirrored ─────────────────────────────

    /** An effect inserted at [slot] on [bus]: the links after it move along. */
    fun afterInsert(links: List<KnobLink>, bus: Int, slot: Int) =
        links.map { if (it.bus == bus && it.slot >= slot) it.copy(slot = it.slot + 1) else it }

    /** The effect at [slot] on [bus] removed: its links go, the ones after move up. */
    fun afterRemove(links: List<KnobLink>, bus: Int, slot: Int) =
        links.filterNot { it.bus == bus && it.slot == slot }
            .map { if (it.bus == bus && it.slot > slot) it.copy(slot = it.slot - 1) else it }

    /** An effect moved from [from] to [to] on [bus]: every link stays on its own effect. */
    fun afterMove(links: List<KnobLink>, bus: Int, from: Int, to: Int) =
        links.map { if (it.bus == bus) it.copy(slot = slotAfterMove(it.slot, from, to)) else it }

    /** Bus [bus] removed: its links go, the buses above move down one. */
    fun afterRemoveBus(links: List<KnobLink>, bus: Int) =
        links.filterNot { it.bus == bus }
            .map { if (it.bus > bus) it.copy(bus = it.bus - 1) else it }

    /** modroute::slotAfterMove: where [slot] lands when a chain moves [from] to [to]. */
    fun slotAfterMove(slot: Int, from: Int, to: Int): Int = when {
        slot == from -> to
        from < to && slot in (from + 1)..to -> slot - 1
        from > to && slot in to until from -> slot + 1
        else -> slot
    }

    /** modroute::MAX_ROUTES. */
    const val MAX_LINKS = 64

    /** The top-level `"knobLinks":[…]` span in [text], start of the key to the closing bracket. */
    private fun find(text: String): Pair<Int, Int>? {
        val key = "\"$KEY\""
        var at = text.indexOf(key)
        while (at >= 0) {
            var i = at + key.length
            while (i < text.length && text[i].isWhitespace()) i++
            if (i < text.length && text[i] == ':') {
                i++
                while (i < text.length && text[i].isWhitespace()) i++
                if (i < text.length && text[i] == '[') {
                    val end = matchingBracket(text, i)
                    if (end > 0) return at to end
                }
            }
            at = text.indexOf(key, at + 1)
        }
        return null
    }

    private fun matchingBracket(text: String, open: Int): Int {
        var depth = 0
        var inString = false
        var i = open
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '[', '{' -> depth++
                    ']', '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }
}

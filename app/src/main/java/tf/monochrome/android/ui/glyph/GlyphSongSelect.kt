// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.chart.GlyphChartStats
import tf.monochrome.android.glyph.chart.bpmRange
import tf.monochrome.android.glyph.data.GlyphWheel
import tf.monochrome.android.ui.components.CoverImage
import tf.monochrome.android.ui.theme.MonoDimens
import tf.monochrome.android.ui.components.liquidGlass

/**
 * What the music wheel's centre item is, above the wheel: its art and
 * names, DDR's Groove Radar for the chosen chart, the BPM meter, the meter
 * in feet and the density graph — every number moving to the next one as
 * the wheel turns or the difficulty changes, never jumping.
 *
 * A folder shows its own summary, and the radar and graph fall flat.
 */
@Composable
internal fun GlyphSongInfoCard(
    item: GlyphWheel.Item?,
    state: GlyphUiState,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val song = (item as? GlyphWheel.Item.Song)?.song
    // The chart is only read once the wheel has rested on the song (the
    // sample delay); until then the card shows the song's own facts.
    val loaded = song != null && state.selectedSong?.trackId == song.trackId
    val chart = if (loaded) state.chart else null
    val simfile = if (loaded) state.simfile else null
    val stats = remember(chart, simfile) {
        if (chart != null && simfile != null) GlyphChartStats.of(chart, simfile.timing) else GlyphChartStats.EMPTY
    }
    val range = simfile?.timing?.bpmRange()
    val bpm = range?.endInclusive ?: song?.bpm ?: (item as? GlyphWheel.Item.Folder)?.section?.songs?.mapNotNull { it.bpm }?.maxOrNull() ?: 0f
    val colour = chart?.difficulty?.let(::difficultyColour) ?: scheme.onSurfaceVariant
    val title = when (item) {
        is GlyphWheel.Item.Song -> item.song.title
        is GlyphWheel.Item.Folder -> item.section.name
        null -> ""
    }
    val subtitle = when (item) {
        is GlyphWheel.Item.Song -> listOfNotNull(item.song.artist.ifBlank { null }, item.section).joinToString(" · ")
        is GlyphWheel.Item.Folder -> "${item.section.songs.size} songs · ${item.section.charted} ready"
        null -> ""
    }

    Row(
        modifier = modifier
            .liquidGlass(shape = MonoDimens.shapeLg)
            .padding(MonoDimens.spacingMd),
        horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd),
    ) {
        GlyphGrooveRadar(
            values = stats.radar,
            colour = colour,
            reducedMotion = reducedMotion,
            modifier = Modifier.size(140.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val art = song?.artworkUri ?: (item as? GlyphWheel.Item.Folder)?.section?.songs?.firstNotNullOfOrNull { it.artworkUri }
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(sectionColour(item?.let { sectionOf(it) } ?: ""))) {
                    if (art != null) CoverImage(url = art, contentDescription = null, size = 44.dp, cornerRadius = 10.dp)
                }
                Spacer(Modifier.width(MonoDimens.spacingSm))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            GlyphBpmMeter(
                bpm = bpm,
                low = range?.start?.takeIf { it < (range.endInclusive - 0.5f) },
                colour = colour,
                reducedMotion = reducedMotion,
            )
            GlyphFeet(
                difficulty = chart?.difficulty,
                meter = chart?.meter ?: 0,
                colour = colour,
                reducedMotion = reducedMotion,
            )
        }
    }
    GlyphDensityGraph(
        stats = stats,
        colour = colour,
        reducedMotion = reducedMotion,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MonoDimens.spacingXs)
            .height(44.dp),
    )
}

private fun sectionOf(item: GlyphWheel.Item): String = when (item) {
    is GlyphWheel.Item.Song -> item.section
    is GlyphWheel.Item.Folder -> item.section.name
}

/** Every value eased to its next one, or set at once with reduced motion. */
@Composable
private fun animated(target: Float, reducedMotion: Boolean, label: String): State<Float> =
    animateFloatAsState(
        targetValue = target,
        animationSpec = if (reducedMotion) snap() else tween(MORPH_MILLIS, easing = FastOutSlowInEasing),
        label = label,
    )

/**
 * DDR's Groove Radar: Stream at the top, then Chaos, Freeze, Air and Voltage
 * clockwise, each 0 to 1 from the centre. The shape morphs from one chart's
 * to the next.
 */
@Composable
internal fun GlyphGrooveRadar(
    values: List<Float>,
    colour: Color,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val a0 by animated(values.getOrElse(0) { 0f }, reducedMotion, "radar0")
    val a1 by animated(values.getOrElse(1) { 0f }, reducedMotion, "radar1")
    val a2 by animated(values.getOrElse(2) { 0f }, reducedMotion, "radar2")
    val a3 by animated(values.getOrElse(3) { 0f }, reducedMotion, "radar3")
    val a4 by animated(values.getOrElse(4) { 0f }, reducedMotion, "radar4")
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 8.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, color = muted)
    val description = GlyphChartStats.AXES.zip(values).joinToString(", ") { (axis, v) -> "${axis.lowercase()} ${(v * 100).toInt()}" }

    Canvas(modifier.semantics { contentDescription = "Groove radar: $description" }) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f - 16.dp.toPx()
        fun point(axis: Int, value: Float): Offset {
            val angle = Math.toRadians((-90.0 + axis * 72.0))
            return Offset(centre.x + (cos(angle) * radius * value).toFloat(), centre.y + (sin(angle) * radius * value).toFloat())
        }
        fun pentagon(scale: Float) = Path().apply {
            for (axis in 0 until 5) {
                val p = point(axis, scale)
                if (axis == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        for (ring in listOf(1f / 3f, 2f / 3f, 1f)) drawPath(pentagon(ring), ink.copy(alpha = 0.16f), style = Stroke(1.dp.toPx()))
        for (axis in 0 until 5) drawLine(ink.copy(alpha = 0.12f), centre, point(axis, 1f), 1.dp.toPx())

        val shape = Path().apply {
            listOf(a0, a1, a2, a3, a4).forEachIndexed { axis, v ->
                val p = point(axis, v.coerceAtLeast(0.04f))
                if (axis == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        drawPath(shape, colour.copy(alpha = 0.32f))
        drawPath(shape, colour, style = Stroke(2.dp.toPx()))

        GlyphChartStats.AXES.forEachIndexed { axis, label ->
            val layout = measurer.measure(label, labelStyle)
            val at = point(axis, 1.18f)
            drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
        }
    }
}

/**
 * The BPM meter: the tempo rolling to the next song's, a gauge to 300, and a
 * light that blinks on the beat. A song whose tempo changes shows its range.
 */
@Composable
private fun GlyphBpmMeter(bpm: Float, low: Float?, colour: Color, reducedMotion: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val shown by animated(bpm, reducedMotion, "bpm")
    val gauge by animated((bpm / MAX_GAUGE_BPM).coerceIn(0f, 1f), reducedMotion, "bpmGauge")
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("SPEED", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, letterSpacing = 1.4.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (low != null) "${low.toInt()}–${shown.toInt()}" else shown.toInt().toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { contentDescription = "$bpm beats a minute" },
            )
            Spacer(Modifier.width(4.dp))
            Text("BPM", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            BeatLight(bpm = bpm, colour = colour, reducedMotion = reducedMotion)
        }
        Box(
            Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .height(5.dp)
                .clip(CircleShape)
                .background(scheme.onSurface.copy(alpha = 0.12f)),
        ) {
            Box(Modifier.fillMaxWidth(gauge).fillMaxHeight().clip(CircleShape).background(colour))
        }
    }
}

/** A light that flashes on each beat at [bpm]; still with reduced motion. */
@Composable
private fun BeatLight(bpm: Float, colour: Color, reducedMotion: Boolean) {
    if (reducedMotion || bpm <= 0f) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(colour.copy(alpha = 0.5f)))
        return
    }
    val beatMillis = (60_000f / bpm.coerceIn(30f, 400f)).toInt()
    val pulse by rememberInfiniteTransition(label = "beat").animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(beatMillis, easing = LinearEasing), RepeatMode.Restart),
        label = "beatPulse",
    )
    Box(
        Modifier
            .size(9.dp)
            .graphicsLayer {
                alpha = pulse
                val scale = 1f + (pulse - 0.3f) * 0.5f
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(colour),
    )
}

/** The meter as DDR's feet: one lit per level, to [FEET]; past that, the number says it. */
@Composable
private fun GlyphFeet(difficulty: StepManiaDifficulty?, meter: Int, colour: Color, reducedMotion: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val lit by animated(meter.toFloat(), reducedMotion, "feet")
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = difficulty?.sscName?.uppercase() ?: "—",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                letterSpacing = 1.4.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (difficulty != null) meter.toString() else "",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = colour,
            )
        }
        Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in 0 until FEET) {
                // A foot fills as the meter passes it, so a change of meter sweeps.
                val fill = (lit - i).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .size(width = 9.dp, height = 12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(scheme.onSurface.copy(alpha = 0.12f)),
                ) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(fill).align(Alignment.BottomCenter).background(colour))
                }
            }
        }
    }
}

/**
 * Notes a second across the chart, as ITGmania's themes draw it: a filled
 * area to a fixed 12 a second, so two charts compare at a glance. Each slice
 * eases to the next chart's.
 */
@Composable
internal fun GlyphDensityGraph(stats: GlyphChartStats, colour: Color, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val bins = List(GlyphChartStats.BINS) { i ->
        animated(stats.density.getOrElse(i) { 0f }, reducedMotion, "density$i")
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val label = if (stats.notes > 0) {
        "Density: ${"%.1f".format(stats.averageNps)} notes a second on average, ${"%.1f".format(stats.peakNps)} at the peak"
    } else {
        "No chart"
    }
    Column(modifier.semantics { contentDescription = label }) {
        Row {
            Text("DENSITY", style = MaterialTheme.typography.labelSmall, color = muted, letterSpacing = 1.4.sp)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (stats.notes > 0) "PEAK ${"%.1f".format(stats.peakNps)} NPS" else "",
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
        }
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 2.dp)) {
            if (bins.isEmpty()) return@Canvas
            val step = size.width / (bins.size - 1)
            fun y(v: Float) = size.height - (v / DENSITY_SCALE).coerceIn(0f, 1f) * (size.height - 2.dp.toPx())
            val line = Path().apply {
                bins.forEachIndexed { i, v -> if (i == 0) moveTo(0f, y(v.value)) else lineTo(i * step, y(v.value)) }
            }
            val area = Path().apply {
                addPath(line)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(area, colour.copy(alpha = 0.28f))
            drawPath(line, colour, style = Stroke(1.5.dp.toPx()))
        }
    }
}

/** Each difficulty's colour, apart in lightness as well as hue. */
internal fun difficultyColour(difficulty: StepManiaDifficulty): Color = when (difficulty) {
    StepManiaDifficulty.BEGINNER -> Color(0xFF5AC8FA)
    StepManiaDifficulty.EASY -> Color(0xFF7BD88F)
    StepManiaDifficulty.MEDIUM -> Color(0xFFF5C542)
    StepManiaDifficulty.HARD -> Color(0xFFFF6B6B)
    StepManiaDifficulty.CHALLENGE -> Color(0xFFB38CFF)
}

private const val MORPH_MILLIS = 520
private const val MAX_GAUGE_BPM = 300f
private const val FEET = 12
/** Notes a second at the top of the density graph. */
private const val DENSITY_SCALE = 12f

package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/** One of Material's fifteen text styles, as the Fonts page's typography table lists them. */
enum class TypeRole {
    DISPLAY_LARGE, DISPLAY_MEDIUM, DISPLAY_SMALL,
    HEADLINE_LARGE, HEADLINE_MEDIUM, HEADLINE_SMALL,
    TITLE_LARGE, TITLE_MEDIUM, TITLE_SMALL,
    BODY_LARGE, BODY_MEDIUM, BODY_SMALL,
    LABEL_LARGE, LABEL_MEDIUM, LABEL_SMALL,
}

/**
 * A style as the app draws it before the listener's table, at 100% text size.
 * [widthPercent] is how wide its letters are drawn: 100 as designed, less for
 * condensed, more for wide.
 */
data class TypeSpec(
    val weight: Int,
    val sizeSp: Float,
    val lineHeightSp: Float,
    val letterSpacingSp: Float,
    val widthPercent: Int = 100,
)

/**
 * The app's own type scale. Titles and row titles sit a size under Material's
 * (17, 15, 15 rather than 18, 16, 16) with Material's line heights, so rows
 * keep their height (ListRowHeightTest). A title is SemiBold and a description
 * Regular, two weights apart: at one weight a Settings row's name and the line
 * under it read as one, told apart only by size and colour.
 */
object TypeDefaults {
    fun of(role: TypeRole): TypeSpec = when (role) {
        TypeRole.DISPLAY_LARGE -> TypeSpec(700, 32f, 40f, -0.5f)
        TypeRole.DISPLAY_MEDIUM -> TypeSpec(700, 28f, 36f, 0f)
        TypeRole.DISPLAY_SMALL -> TypeSpec(400, 36f, 44f, 0f)
        TypeRole.HEADLINE_LARGE -> TypeSpec(600, 24f, 32f, 0f)
        TypeRole.HEADLINE_MEDIUM -> TypeSpec(600, 22f, 28f, 0f)
        TypeRole.HEADLINE_SMALL -> TypeSpec(600, 18f, 24f, 0f)
        TypeRole.TITLE_LARGE -> TypeSpec(600, 17f, 24f, 0f)
        TypeRole.TITLE_MEDIUM -> TypeSpec(600, 15f, 22f, 0.15f)
        TypeRole.TITLE_SMALL -> TypeSpec(600, 14f, 20f, 0.1f)
        TypeRole.BODY_LARGE -> TypeSpec(600, 15f, 24f, 0.5f)
        TypeRole.BODY_MEDIUM -> TypeSpec(400, 14f, 20f, 0.25f)
        TypeRole.BODY_SMALL -> TypeSpec(400, 12f, 16f, 0.4f)
        TypeRole.LABEL_LARGE -> TypeSpec(500, 14f, 20f, 0.1f)
        TypeRole.LABEL_MEDIUM -> TypeSpec(500, 12f, 16f, 0.5f)
        TypeRole.LABEL_SMALL -> TypeSpec(500, 10f, 14f, 0.5f)
    }
}

/**
 * The listener's change to one style. Each field left null keeps the app's own
 * value, so a row they never touched follows the main font and the defaults.
 */
@Serializable
data class TypeStyleOverride(
    /** A font id as the font library stores it; null for the main font. */
    val fontId: String? = null,
    /** 100 (Thin) to 900 (Black), in hundreds. */
    val weight: Int? = null,
    /** At 100% text size: the text-size setting still scales it. */
    val sizeSp: Float? = null,
    val letterSpacingSp: Float? = null,
    /** This style's own letter width, over the table's [TypographySettings.widthPercent]. */
    val widthPercent: Int? = null,
) {
    val isDefault: Boolean
        get() = fontId == null && weight == null && sizeSp == null && letterSpacingSp == null && widthPercent == null

    fun clamped(): TypeStyleOverride = copy(
        fontId = fontId?.takeIf { it.isNotBlank() },
        weight = weight?.let { (it / 100f).roundToInt().coerceIn(1, 9) * 100 },
        sizeSp = sizeSp?.takeIf { it.isFinite() }?.let { (it * 2).roundToInt() / 2f }?.coerceIn(MIN_SIZE_SP, MAX_SIZE_SP),
        letterSpacingSp = letterSpacingSp?.takeIf { it.isFinite() }
            ?.let { (it * 100).roundToInt() / 100f }?.coerceIn(MIN_SPACING_SP, MAX_SPACING_SP),
        widthPercent = widthPercent?.let(::clampWidth),
    )

    companion object {
        const val MIN_SIZE_SP = 8f
        const val MAX_SIZE_SP = 64f
        const val MIN_SPACING_SP = -1f
        const val MAX_SPACING_SP = 2f
        const val MIN_WIDTH = 70
        const val MAX_WIDTH = 130
        const val WIDTH_STEP = 5

        /** [percent] to the nearest step the table offers, within its range. */
        fun clampWidth(percent: Int): Int =
            ((percent / WIDTH_STEP.toFloat()).roundToInt() * WIDTH_STEP).coerceIn(MIN_WIDTH, MAX_WIDTH)
    }
}

/**
 * The Fonts page's typography table: per style, the font, weight, size and
 * letter spacing the listener chose. Stored by style name, so a style a later
 * version drops or adds costs nothing either way.
 */
@Serializable
data class TypographySettings(
    val styles: Map<String, TypeStyleOverride> = emptyMap(),
    /** How wide every style's letters are drawn unless the style says otherwise: 100 as designed. */
    val widthPercent: Int = 100,
) {
    /** Nothing changed from the app's own type. */
    val isDefault: Boolean get() = styles.isEmpty() && widthPercent == 100

    /** The table with every style's letters [percent] wide, unless the style says otherwise. */
    fun withWidth(percent: Int): TypographySettings = copy(widthPercent = TypeStyleOverride.clampWidth(percent))

    operator fun get(role: TypeRole): TypeStyleOverride = styles[role.name] ?: TypeStyleOverride()

    /** [role] set to [change]; a change back to every default removes the row. */
    fun with(role: TypeRole, change: TypeStyleOverride): TypographySettings {
        val clean = change.clamped()
        return copy(styles = if (clean.isDefault) styles - role.name else styles + (role.name to clean))
    }

    /** What [role] draws as: the app's value under each field the listener left alone. */
    fun resolved(role: TypeRole): TypeSpec {
        val base = TypeDefaults.of(role)
        val o = this[role].clamped()
        val size = o.sizeSp ?: base.sizeSp
        return TypeSpec(
            weight = o.weight ?: base.weight,
            sizeSp = size,
            // A bigger size takes a taller line in proportion, so nothing is clipped.
            lineHeightSp = base.lineHeightSp * size / base.sizeSp,
            letterSpacingSp = o.letterSpacingSp ?: base.letterSpacingSp,
            widthPercent = o.widthPercent ?: TypeStyleOverride.clampWidth(widthPercent),
        )
    }

    /** The widths the styles are drawn at, each with the font it is drawn in (null: the main font). */
    val fontWidths: Set<Pair<String?, Int>>
        get() = TypeRole.entries.map { this[it].fontId to resolved(it).widthPercent }.toSet()

    /** The fonts the table asks for besides the main one. */
    val fontIds: Set<String>
        get() = TypeRole.entries.mapNotNull { this[it].fontId }.toSet()

    companion object {
        val DEFAULT = TypographySettings()

        private val json = Json { ignoreUnknownKeys = true }

        fun encode(settings: TypographySettings): String = json.encodeToString(serializer(), settings)

        /** Stored text as settings; anything unreadable is no changes at all, never a crash. */
        fun decode(text: String?): TypographySettings {
            if (text.isNullOrBlank()) return DEFAULT
            val read = runCatching { json.decodeFromString(serializer(), text) }.getOrNull() ?: return DEFAULT
            var out = DEFAULT.withWidth(read.widthPercent)
            for (role in TypeRole.entries) read.styles[role.name]?.let { out = out.with(role, it) }
            return out
        }
    }
}

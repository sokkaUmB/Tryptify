package tf.monochrome.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import tf.monochrome.android.R
import tf.monochrome.android.domain.model.TypeRole
import tf.monochrome.android.domain.model.TypographySettings

val InterFontFamily = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)

/**
 * The full Material 3 Typography: the app's scale ([TypeDefaults]) under the
 * listener's typography table ([settings], Settings › Fonts), in [fontFamily]
 * unless a style names another font, which [fontFor] loads. [scale] is the
 * text-size setting and scales every size, the table's included.
 */
fun buildTypography(
    fontFamily: FontFamily,
    scale: Float,
    settings: TypographySettings = TypographySettings.DEFAULT,
    fontFor: (String) -> FontFamily? = { null },
): Typography {
    fun style(role: TypeRole): TextStyle {
        val spec = settings.resolved(role)
        return TextStyle(
            fontFamily = settings[role].fontId?.let(fontFor) ?: fontFamily,
            fontWeight = FontWeight(spec.weight),
            fontSize = (spec.sizeSp * scale).sp,
            lineHeight = (spec.lineHeightSp * scale).sp,
            letterSpacing = spec.letterSpacingSp.sp,
        )
    }
    return Typography(
        displayLarge = style(TypeRole.DISPLAY_LARGE),
        displayMedium = style(TypeRole.DISPLAY_MEDIUM),
        displaySmall = style(TypeRole.DISPLAY_SMALL),
        headlineLarge = style(TypeRole.HEADLINE_LARGE),
        headlineMedium = style(TypeRole.HEADLINE_MEDIUM),
        headlineSmall = style(TypeRole.HEADLINE_SMALL),
        titleLarge = style(TypeRole.TITLE_LARGE),
        titleMedium = style(TypeRole.TITLE_MEDIUM),
        titleSmall = style(TypeRole.TITLE_SMALL),
        bodyLarge = style(TypeRole.BODY_LARGE),
        bodyMedium = style(TypeRole.BODY_MEDIUM),
        bodySmall = style(TypeRole.BODY_SMALL),
        labelLarge = style(TypeRole.LABEL_LARGE),
        labelMedium = style(TypeRole.LABEL_MEDIUM),
        labelSmall = style(TypeRole.LABEL_SMALL),
    )
}

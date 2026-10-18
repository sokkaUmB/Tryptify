package tf.monochrome.android.ui.settings.fonts

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.R
import tf.monochrome.android.domain.model.TypeRole
import tf.monochrome.android.domain.model.TypeStyleOverride
import tf.monochrome.android.domain.model.TypographySettings
import tf.monochrome.android.ui.theme.INTER_FONT_ID
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** The table's field columns, shared by its header and its rows. */
private val WEIGHT_COL = 80.dp
private val SIZE_COL = 76.dp
private val WIDTH_COL = 72.dp
private val COL_GAP = 6.dp

/**
 * The Fonts page's typography table: Material's fifteen text styles, each
 * edited in place in its own row — its weight from a menu, its size and its
 * letter width stepped, its font and letter spacing from a menu on its name.
 * Each name is drawn in its style as the app now draws it (held to a row's
 * height), so a change shows where it is made. A value the listener set is in
 * the accent; one left alone follows the app's own scale, the main font and
 * the page's Width.
 */
@Composable
internal fun TypographyTable(
    settings: TypographySettings,
    /** The fonts the page lists: Inter, the ones that ship, the imports. */
    fonts: List<FontChoice>,
    /** The font in use, which a style left alone follows. */
    mainFont: FontChoice,
    onChange: (TypeRole, TypeStyleOverride) -> Unit,
    onResetAll: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 2.dp)) {
            Text(
                stringResource(R.string.typo_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onResetAll, enabled = settings.styles.isNotEmpty()) {
                Text(stringResource(R.string.typo_reset_all))
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(COL_GAP),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
        ) {
            ColumnLabel(stringResource(R.string.typo_style_col), Modifier.weight(1f))
            ColumnLabel(stringResource(R.string.typo_weight), Modifier.width(WEIGHT_COL))
            ColumnLabel(stringResource(R.string.typo_size), Modifier.width(SIZE_COL))
            ColumnLabel(stringResource(R.string.typo_width), Modifier.width(WIDTH_COL))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TypeRole.entries.forEachIndexed { index, role ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            StyleRow(role, settings, fonts, mainFont) { onChange(role, it) }
        }
        Text(
            stringResource(R.string.typo_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, top = 8.dp),
        )
    }
}

@Composable
private fun StyleRow(
    role: TypeRole,
    settings: TypographySettings,
    fonts: List<FontChoice>,
    mainFont: FontChoice,
    onChange: (TypeStyleOverride) -> Unit,
) {
    val saved = settings[role]
    val spec = settings.resolved(role)
    val scheme = MaterialTheme.colorScheme
    var styleMenu by remember { mutableStateOf(false) }
    var weightMenu by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(COL_GAP),
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 2.dp),
    ) {
        // The name, in its own style: a tap opens its font and spacing.
        Box(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = stringResource(R.string.typo_font)) { styleMenu = true },
            ) {
                Text(
                    roleName(role),
                    style = roleStyle(MaterialTheme.typography, role).heldToRow(),
                    color = if (saved.fontId != null || saved.letterSpacingSp != null) scheme.primary else scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${stringResource(info(role).hint)} · ${shortFontName(saved.fontId, fonts)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StyleMenu(
                expanded = styleMenu,
                onDismiss = { styleMenu = false },
                saved = saved,
                spacing = spec.letterSpacingSp,
                fonts = fonts,
                mainFont = mainFont,
                onChange = onChange,
            )
        }

        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .width(WEIGHT_COL)
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, scheme.outlineVariant, RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = stringResource(R.string.typo_weight)) { weightMenu = true }
                    .padding(start = 8.dp),
            ) {
                Text(
                    stringResource(weightName(spec.weight)),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (saved.weight != null) scheme.primary else scheme.onSurface,
                    fontWeight = if (saved.weight != null) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = weightMenu, onDismissRequest = { weightMenu = false }) {
                (1..9).forEach { step ->
                    val w = step * 100
                    DropdownMenuItem(
                        text = { Text(stringResource(weightName(w)), fontWeight = FontWeight(w)) },
                        trailingIcon = if (w == spec.weight) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
                        onClick = {
                            weightMenu = false
                            onChange(saved.copy(weight = w))
                        },
                    )
                }
            }
        }

        CompactStepper(
            value = number(spec.sizeSp),
            changed = saved.sizeSp != null,
            lessLabel = stringResource(R.string.typo_smaller),
            moreLabel = stringResource(R.string.typo_bigger),
            onLess = (spec.sizeSp - 1f).takeIf { it >= TypeStyleOverride.MIN_SIZE_SP }?.let { s -> { onChange(saved.copy(sizeSp = s)) } },
            onMore = (spec.sizeSp + 1f).takeIf { it <= TypeStyleOverride.MAX_SIZE_SP }?.let { s -> { onChange(saved.copy(sizeSp = s)) } },
            width = SIZE_COL,
        )
        CompactStepper(
            value = "${spec.widthPercent}",
            changed = saved.widthPercent != null,
            lessLabel = stringResource(R.string.typo_narrower),
            moreLabel = stringResource(R.string.typo_wider),
            onLess = (spec.widthPercent - TypeStyleOverride.WIDTH_STEP).takeIf { it >= TypeStyleOverride.MIN_WIDTH }
                ?.let { w -> { onChange(saved.copy(widthPercent = w)) } },
            onMore = (spec.widthPercent + TypeStyleOverride.WIDTH_STEP).takeIf { it <= TypeStyleOverride.MAX_WIDTH }
                ?.let { w -> { onChange(saved.copy(widthPercent = w)) } },
            width = WIDTH_COL,
        )
    }
}

/** A style's font, its letter spacing and its reset: what does not fit in its row. */
@Composable
private fun StyleMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    saved: TypeStyleOverride,
    spacing: Float,
    fonts: List<FontChoice>,
    mainFont: FontChoice,
    onChange: (TypeStyleOverride) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.typo_font),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        val check: @Composable () -> Unit = { Icon(Icons.Filled.Check, contentDescription = null) }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.typo_main_font, mainFont.name)) },
            trailingIcon = if (saved.fontId == null) check else null,
            onClick = {
                onDismiss()
                onChange(saved.copy(fontId = null))
            },
        )
        fonts.filter { it.usable }.forEach { choice ->
            val id = fontIdOf(choice)
            DropdownMenuItem(
                text = { Text(choice.name) },
                trailingIcon = if (saved.fontId == id) check else null,
                onClick = {
                    onDismiss()
                    onChange(saved.copy(fontId = id))
                },
            )
        }
        HorizontalDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text(
                stringResource(R.string.typo_spacing),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 12.dp),
            )
            CompactStepper(
                value = String.format(Locale.US, "%.2f", spacing),
                changed = saved.letterSpacingSp != null,
                lessLabel = stringResource(R.string.typo_tighter),
                moreLabel = stringResource(R.string.typo_looser),
                onLess = (spacing - 0.05f).takeIf { it >= TypeStyleOverride.MIN_SPACING_SP - 1e-4f }
                    ?.let { v -> { onChange(saved.copy(letterSpacingSp = v)) } },
                onMore = (spacing + 0.05f).takeIf { it <= TypeStyleOverride.MAX_SPACING_SP + 1e-4f }
                    ?.let { v -> { onChange(saved.copy(letterSpacingSp = v)) } },
                width = 96.dp,
            )
        }
        if (!saved.isDefault) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.typo_reset)) },
                onClick = {
                    onDismiss()
                    onChange(TypeStyleOverride())
                },
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}

/** A style as the app draws it now, held to a row: big styles drawn at 17 sp. */
private fun TextStyle.heldToRow(): TextStyle =
    if (fontSize.value > 17f) copy(fontSize = 17.sp, lineHeight = 22.sp) else copy(lineHeight = (fontSize.value + 5f).sp)

private fun roleStyle(type: Typography, role: TypeRole): TextStyle = when (role) {
    TypeRole.DISPLAY_LARGE -> type.displayLarge
    TypeRole.DISPLAY_MEDIUM -> type.displayMedium
    TypeRole.DISPLAY_SMALL -> type.displaySmall
    TypeRole.HEADLINE_LARGE -> type.headlineLarge
    TypeRole.HEADLINE_MEDIUM -> type.headlineMedium
    TypeRole.HEADLINE_SMALL -> type.headlineSmall
    TypeRole.TITLE_LARGE -> type.titleLarge
    TypeRole.TITLE_MEDIUM -> type.titleMedium
    TypeRole.TITLE_SMALL -> type.titleSmall
    TypeRole.BODY_LARGE -> type.bodyLarge
    TypeRole.BODY_MEDIUM -> type.bodyMedium
    TypeRole.BODY_SMALL -> type.bodySmall
    TypeRole.LABEL_LARGE -> type.labelLarge
    TypeRole.LABEL_MEDIUM -> type.labelMedium
    TypeRole.LABEL_SMALL -> type.labelSmall
}

/** What a style stores for a font picked from the page's list. */
private fun fontIdOf(choice: FontChoice): String =
    if (choice.origin == FontChoice.Origin.DEFAULT) INTER_FONT_ID else choice.key

/** A style's font as its row says it: "Main" for the main font. */
@Composable
private fun shortFontName(fontId: String?, fonts: List<FontChoice>): String = when (fontId) {
    null -> stringResource(R.string.typo_main_short)
    INTER_FONT_ID -> fonts.firstOrNull { it.origin == FontChoice.Origin.DEFAULT }?.name ?: "Inter"
    else -> fonts.firstOrNull { choice -> choice.key == fontId || choice.imported?.files?.any { it.absolutePath == fontId } == true }
        ?.name ?: File(fontId).nameWithoutExtension
}

private class RoleInfo(@StringRes val group: Int, val size: String, @StringRes val hint: Int)

private fun info(role: TypeRole): RoleInfo = when (role) {
    TypeRole.DISPLAY_LARGE -> RoleInfo(R.string.typo_display, "L", R.string.typo_hint_display_large)
    TypeRole.DISPLAY_MEDIUM -> RoleInfo(R.string.typo_display, "M", R.string.typo_hint_display_medium)
    TypeRole.DISPLAY_SMALL -> RoleInfo(R.string.typo_display, "S", R.string.typo_hint_display_small)
    TypeRole.HEADLINE_LARGE -> RoleInfo(R.string.typo_headline, "L", R.string.typo_hint_headline_large)
    TypeRole.HEADLINE_MEDIUM -> RoleInfo(R.string.typo_headline, "M", R.string.typo_hint_headline_medium)
    TypeRole.HEADLINE_SMALL -> RoleInfo(R.string.typo_headline, "S", R.string.typo_hint_headline_small)
    TypeRole.TITLE_LARGE -> RoleInfo(R.string.typo_title_group, "L", R.string.typo_hint_title_large)
    TypeRole.TITLE_MEDIUM -> RoleInfo(R.string.typo_title_group, "M", R.string.typo_hint_title_medium)
    TypeRole.TITLE_SMALL -> RoleInfo(R.string.typo_title_group, "S", R.string.typo_hint_title_small)
    TypeRole.BODY_LARGE -> RoleInfo(R.string.typo_body, "L", R.string.typo_hint_body_large)
    TypeRole.BODY_MEDIUM -> RoleInfo(R.string.typo_body, "M", R.string.typo_hint_body_medium)
    TypeRole.BODY_SMALL -> RoleInfo(R.string.typo_body, "S", R.string.typo_hint_body_small)
    TypeRole.LABEL_LARGE -> RoleInfo(R.string.typo_label, "L", R.string.typo_hint_label_large)
    TypeRole.LABEL_MEDIUM -> RoleInfo(R.string.typo_label, "M", R.string.typo_hint_label_medium)
    TypeRole.LABEL_SMALL -> RoleInfo(R.string.typo_label, "S", R.string.typo_hint_label_small)
}

@Composable
private fun roleName(role: TypeRole): String = info(role).let { "${stringResource(it.group)} ${it.size}" }

@StringRes
internal fun weightName(weight: Int): Int = when ((weight / 100f).roundToInt().coerceIn(1, 9)) {
    1 -> R.string.typo_weight_100
    2 -> R.string.typo_weight_200
    3 -> R.string.typo_weight_300
    4 -> R.string.typo_weight_400
    5 -> R.string.typo_weight_500
    6 -> R.string.typo_weight_600
    7 -> R.string.typo_weight_700
    8 -> R.string.typo_weight_800
    else -> R.string.typo_weight_900
}

/** "15" or "15.5": a size as the table shows it. */
private fun number(sp: Float): String =
    if (sp % 1f == 0f) sp.roundToInt().toString() else String.format(Locale.US, "%.1f", sp)

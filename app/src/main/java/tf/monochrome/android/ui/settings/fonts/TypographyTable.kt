package tf.monochrome.android.ui.settings.fonts

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.R
import tf.monochrome.android.domain.model.TypeRole
import tf.monochrome.android.domain.model.TypeStyleOverride
import tf.monochrome.android.domain.model.TypographySettings
import tf.monochrome.android.ui.components.materialSurface
import tf.monochrome.android.ui.theme.INTER_FONT_ID
import tf.monochrome.android.ui.theme.loadAppFontFamily
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Fonts page's typography table: Material's fifteen text styles, each with
 * its own font, weight (Thin to Black), size and letter spacing, over the app's
 * own scale ([tf.monochrome.android.domain.model.TypeDefaults]). A style left
 * alone follows the main font and the defaults; a changed one is marked.
 *
 * One style opens at a time, with a sample drawn the way it is being set. The
 * sliders write when the finger lifts, so the whole app re-lays its text once
 * per change rather than on every frame of a drag.
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
    var open by rememberSaveable { mutableStateOf<TypeRole?>(null) }
    val context = LocalContext.current
    val mainFamily = rememberFontFamily(context, mainFont)
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.typo_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onResetAll, enabled = settings.styles.isNotEmpty()) {
                Text(stringResource(R.string.typo_reset_all))
            }
        }
        Text(
            stringResource(R.string.typo_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.typo_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )
        TypeRole.entries.forEachIndexed { index, role ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            StyleRow(
                role = role,
                settings = settings,
                fonts = fonts,
                mainFont = mainFont,
                mainFamily = mainFamily,
                expanded = open == role,
                onToggle = { open = if (open == role) null else role },
                onChange = { onChange(role, it) },
            )
        }
    }
}

@Composable
private fun StyleRow(
    role: TypeRole,
    settings: TypographySettings,
    fonts: List<FontChoice>,
    mainFont: FontChoice,
    mainFamily: FontFamily,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChange: (TypeStyleOverride) -> Unit,
) {
    val saved = settings[role]
    // What the sliders show while a finger is on them; written on release.
    var draft by remember(role, saved) { mutableStateOf(saved) }
    val spec = settings.with(role, draft).resolved(role)
    val fontName = fontNameOf(draft.fontId, fonts, mainFont)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(roleName(role), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(info(role).hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "$fontName · ${stringResource(weightName(spec.weight))} · ${number(spec.sizeSp)} sp · ${spacing(spec.letterSpacingSp)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (saved.isDefault) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!saved.isDefault) {
                // Changed from the app's own value.
                Box(Modifier.padding(start = 8.dp).size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
        }
        AnimatedVisibility(visible = expanded) {
            StyleEditor(
                draft = draft,
                spec = spec,
                family = rememberRoleFamily(draft.fontId, mainFamily),
                fonts = fonts,
                mainFont = mainFont,
                fontName = fontName,
                onDraft = { draft = it },
                // Read when the finger lifts, so the last step of a drag is kept.
                onCommitDraft = { onChange(draft) },
                onCommit = { onChange(it) },
            )
        }
    }
}

@Composable
private fun StyleEditor(
    draft: TypeStyleOverride,
    spec: tf.monochrome.android.domain.model.TypeSpec,
    family: FontFamily,
    fonts: List<FontChoice>,
    mainFont: FontChoice,
    fontName: String,
    onDraft: (TypeStyleOverride) -> Unit,
    onCommitDraft: () -> Unit,
    onCommit: (TypeStyleOverride) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        // The style as it is being set, in its own font.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .materialSurface(shape = MaterialTheme.shapes.medium)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                stringResource(R.string.typo_sample),
                style = TextStyle(
                    fontFamily = family,
                    fontWeight = FontWeight(spec.weight),
                    fontSize = spec.sizeSp.sp,
                    lineHeight = spec.lineHeightSp.sp,
                    letterSpacing = spec.letterSpacingSp.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(8.dp))

        Text(stringResource(R.string.typo_font), style = MaterialTheme.typography.labelLarge)
        Box {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { menu = true }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(fontName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.typo_main_font, mainFont.name)) },
                    onClick = {
                        menu = false
                        onCommit(draft.copy(fontId = null))
                    },
                )
                fonts.filter { it.usable }.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choice.name) },
                        onClick = {
                            menu = false
                            onCommit(draft.copy(fontId = fontIdOf(choice)))
                        },
                    )
                }
            }
        }

        Text(
            "${stringResource(R.string.typo_weight)} · ${stringResource(weightName(spec.weight))}",
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = (spec.weight / 100).toFloat(),
            onValueChange = { onDraft(draft.copy(weight = it.roundToInt() * 100)) },
            onValueChangeFinished = onCommitDraft,
            valueRange = 1f..9f,
            steps = 7,
        )

        Text(
            "${stringResource(R.string.typo_size)} · ${number(spec.sizeSp)} sp",
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = spec.sizeSp,
            onValueChange = { onDraft(draft.copy(sizeSp = (it * 2).roundToInt() / 2f)) },
            onValueChangeFinished = onCommitDraft,
            valueRange = TypeStyleOverride.MIN_SIZE_SP..TypeStyleOverride.MAX_SIZE_SP,
        )

        Text(
            "${stringResource(R.string.typo_spacing)} · ${spacing(spec.letterSpacingSp)}",
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = spec.letterSpacingSp,
            onValueChange = { onDraft(draft.copy(letterSpacingSp = (it * 20).roundToInt() / 20f)) },
            onValueChangeFinished = onCommitDraft,
            valueRange = TypeStyleOverride.MIN_SPACING_SP..TypeStyleOverride.MAX_SPACING_SP,
        )

        if (!draft.isDefault) {
            TextButton(onClick = { onCommit(TypeStyleOverride()) }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.typo_reset))
            }
        }
    }
}

/** The font a style draws in: the one it names, else the main font. */
@Composable
private fun rememberRoleFamily(fontId: String?, mainFamily: FontFamily): FontFamily {
    val context = LocalContext.current
    return remember(fontId, mainFamily) {
        fontId?.let { loadAppFontFamily(context, it) } ?: mainFamily
    }
}

/** What a style stores for a font picked from the page's list. */
private fun fontIdOf(choice: FontChoice): String =
    if (choice.origin == FontChoice.Origin.DEFAULT) INTER_FONT_ID else choice.key

@Composable
private fun fontNameOf(fontId: String?, fonts: List<FontChoice>, mainFont: FontChoice): String = when (fontId) {
    null -> stringResource(R.string.typo_main_font, mainFont.name)
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
private fun weightName(weight: Int): Int = when ((weight / 100f).roundToInt().coerceIn(1, 9)) {
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

private fun spacing(sp: Float): String = String.format(Locale.US, "%.2f", sp)

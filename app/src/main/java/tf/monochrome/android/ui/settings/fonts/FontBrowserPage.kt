package tf.monochrome.android.ui.settings.fonts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import tf.monochrome.android.R
import tf.monochrome.android.data.fonts.FontImportReport
import tf.monochrome.android.data.fonts.FontSkipReason
import tf.monochrome.android.data.fonts.ImportedFont
import tf.monochrome.android.domain.model.TypeStyleOverride
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.components.materialSurface
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.ui.settings.LocalSettingsSearchInset
import tf.monochrome.android.ui.theme.BundledFonts
import tf.monochrome.android.ui.theme.InterFontFamily
import tf.monochrome.android.ui.theme.loadAppFontFamily
import kotlin.math.roundToInt

/** One entry in the browser: the default, a font that ships, or an import. */
internal data class FontChoice(
    val key: String,
    val name: String,
    val note: String?,
    val origin: Origin,
    val imported: ImportedFont? = null,
) {
    enum class Origin { DEFAULT, INCLUDED, IMPORTED }

    val usable: Boolean get() = imported?.usable ?: true
}

/**
 * Settings › Fonts: the font the app is drawn in, its size and letter width,
 * and the typography table, condensed so the whole page reads in a few
 * screens rather than a long scroll of large cards.
 *
 * Top to bottom: the font being tried, in a single row with its Use button;
 * the text panel, the size steps and the Width slider; the fonts as chips in
 * their own letters (a tap tries one, holding an imported one deletes it,
 * "+ Import" adds more); and the typography table, every style edited in its
 * own row. A page of the Settings pager, under its own chip, so it has no bar
 * of its own.
 *
 * Its glass is the app's, not this screen's: the panels are [GlassPanel]s cut
 * from the UI panels material that Visual Studio tunes ([LocalMiniPlayerGlass]),
 * and "Remove liquid glass" flattens them like everywhere else. The panels
 * frost a backdrop drawn for them underneath — the screen's own source, as a
 * sibling, never the app-wide one they are drawn inside.
 */
@Composable
fun FontBrowserPage(
    viewModel: FontBrowserViewModel = hiltViewModel(),
    /** The app's text size, in the text panel: Settings' own state, so passed in. */
    fontSize: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val activeKey by viewModel.activeKey.collectAsStateWithLifecycle()
    val previewKey by viewModel.previewKey.collectAsStateWithLifecycle()
    val imported by viewModel.imported.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()
    val typography by viewModel.typography.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.import(uris)
    }
    val launchPicker = { picker.launch(FONT_MIME_TYPES) }

    val defaultNote = stringResource(R.string.settings_the_default_neutral_ui_grotesque)
    val choices = remember(imported, defaultNote) {
        buildList {
            add(FontChoice(DEFAULT_FONT_KEY, "Inter", defaultNote, FontChoice.Origin.DEFAULT))
            BundledFonts.ALL.forEach { font ->
                add(FontChoice(BundledFonts.idOf(font), font.displayName, font.note, FontChoice.Origin.INCLUDED))
            }
            imported.forEach { font ->
                add(FontChoice(font.id, font.name, null, FontChoice.Origin.IMPORTED, font))
            }
        }
    }
    val previewChoice = choices.firstOrNull { it.key == previewKey } ?: choices.first()
    val previewFamily = rememberFontFamily(context, previewChoice)
    val mainFont = choices.firstOrNull { it.key == activeKey } ?: choices.first()

    pendingDelete?.let { font ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(R.string.fonts_delete_title, font.name)) },
            text = { Text(stringResource(R.string.fonts_delete_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    val haze = rememberHazeState()
    val panel: @Composable (@Composable () -> Unit) -> Unit = { content ->
        GlassPanel(
            hazeState = haze,
            glass = LocalMiniPlayerGlass.current,
            avoidNavigationBar = false,
            // In a scrolling list: a swipe on the pane scrolls it.
            blockTouchesBelow = false,
        ) { content() }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        FontBackdrop(
            family = previewFamily,
            modifier = Modifier.fillMaxSize().hazeSource(haze),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // The settings search bar floats over the pager; its height
            // comes down as top padding so the first panel starts clear of it.
            contentPadding = PaddingValues(
                top = LocalSettingsSearchInset.current,
                bottom = 96.dp + LocalBottomChromeInset.current,
            ),
        ) {
            item(key = "preview") {
                panel {
                    PreviewRow(
                        choice = previewChoice,
                        family = previewFamily,
                        inUse = previewChoice.key == activeKey,
                        onApply = viewModel::apply,
                    )
                }
            }
            item(key = "text") {
                panel {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        fontSize()
                        WidthRow(percent = typography.widthPercent, onCommit = viewModel::setTypographyWidth)
                    }
                }
            }
            item(key = "fonts") {
                FontChips(
                    choices = choices,
                    previewKey = previewKey,
                    activeKey = activeKey,
                    importing = importing,
                    onPreview = viewModel::preview,
                    onDelete = viewModel::requestDelete,
                    onImport = launchPicker,
                )
            }
            report?.let { r ->
                item(key = "report") {
                    panel { ImportReport(r, onDismiss = viewModel::dismissReport) }
                }
            }
            item(key = "typography") {
                panel {
                    TypographyTable(
                        settings = typography,
                        fonts = choices,
                        mainFont = mainFont,
                        onChange = viewModel::setTypographyStyle,
                        onResetAll = viewModel::resetTypography,
                    )
                }
            }
        }
    }
}

/** The font being tried, as one row: a sample in it, its name, and Use (or In use). */
@Composable
private fun PreviewRow(choice: FontChoice, family: FontFamily, inUse: Boolean, onApply: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.fonts_preview_track),
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = family),
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${stringResource(R.string.fonts_preview_artist)} · ${choice.name}",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = family),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (inUse) {
            InUseBadge()
        } else {
            FilledTonalButton(
                onClick = onApply,
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.heightIn(min = 36.dp),
            ) { Text(stringResource(R.string.typo_use_short)) }
        }
    }
}

/**
 * The page's Width: how wide every style's letters are drawn, 70% (condensed)
 * to 130% (wide), unless a style in the table says otherwise. A font with a
 * width axis draws it itself; any other is squeezed (loadWidthFont).
 */
@Composable
private fun WidthRow(percent: Int, onCommit: (Int) -> Unit) {
    var draft by remember(percent) { mutableFloatStateOf(percent.toFloat()) }
    val steps = (TypeStyleOverride.MAX_WIDTH - TypeStyleOverride.MIN_WIDTH) / TypeStyleOverride.WIDTH_STEP - 1
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.typo_width), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp))
        Column(modifier = Modifier.weight(1f)) {
            Slider(
                value = draft,
                onValueChange = { draft = it },
                // Written when the finger lifts: every screen re-lays its text.
                onValueChangeFinished = { onCommit(draft.roundToInt()) },
                valueRange = TypeStyleOverride.MIN_WIDTH.toFloat()..TypeStyleOverride.MAX_WIDTH.toFloat(),
                steps = steps,
                modifier = Modifier.height(32.dp),
            )
            Row {
                listOf(R.string.typo_condensed, R.string.typo_normal, R.string.typo_wide).forEachIndexed { i, label ->
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = when (i) { 0 -> TextAlign.Start; 1 -> TextAlign.Center; else -> TextAlign.End },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Text(
            "${TypeStyleOverride.clampWidth(draft.roundToInt())}%",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp),
        )
    }
}

/**
 * Every font as a chip in its own letters: a tap tries it in the row above,
 * the one in use carries a check, holding an imported one deletes it, and
 * "+ Import" adds more.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun FontChips(
    choices: List<FontChoice>,
    previewKey: String,
    activeKey: String,
    importing: Boolean,
    onPreview: (String) -> Unit,
    onDelete: (ImportedFont) -> Unit,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEach { choice ->
                val family = rememberFontFamily(context, choice)
                val selected = choice.key == previewKey
                val deleteLabel = choice.imported?.let { stringResource(R.string.settings_delete_font, choice.name) }
                Text(
                    choice.name + if (choice.key == activeKey) " ✓" else "",
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = family),
                    color = when {
                        !choice.usable -> scheme.error
                        selected -> scheme.onPrimary
                        else -> scheme.onSurface
                    },
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            if (selected) Modifier.background(scheme.primary)
                            else Modifier.materialSurface(shape = RoundedCornerShape(10.dp))
                                .border(1.dp, scheme.outlineVariant, RoundedCornerShape(10.dp)),
                        )
                        .combinedClickable(
                            onClick = { onPreview(choice.key) },
                            onLongClick = choice.imported?.let { font -> { onDelete(font) } },
                            onLongClickLabel = deleteLabel,
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            Text(
                if (importing) stringResource(R.string.fonts_checking) else "+ " + stringResource(R.string.fonts_import),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, scheme.primary, RoundedCornerShape(10.dp))
                    .clickable(enabled = !importing, onClick = onImport)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        if (choices.any { it.imported != null }) {
            Text(
                stringResource(R.string.typo_hold_to_delete),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp),
            )
        }
    }
}

/**
 * The family a choice is drawn in. An import that cannot be read is drawn in
 * the default rather than handed to the renderer, which would fail on it.
 */
@Composable
internal fun rememberFontFamily(context: android.content.Context, choice: FontChoice): FontFamily =
    remember(choice.key, choice.usable) {
        when {
            choice.origin == FontChoice.Origin.DEFAULT || !choice.usable -> InterFontFamily
            else -> loadAppFontFamily(context, choice.key) ?: InterFontFamily
        }
    }

/**
 * What the glass frosts: two soft fields of the theme's own colours and the
 * previewed font's letters, oversized. It changes with the preview, so the
 * panes over it shift as fonts are tried.
 */
@Composable
private fun FontBackdrop(family: FontFamily, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier = modifier.background(scheme.background)) {
        Box(
            modifier = Modifier
                .offset(x = (-60).dp, y = 40.dp)
                .size(340.dp)
                .background(Brush.radialGradient(listOf(scheme.primary.copy(alpha = 0.30f), Color.Transparent)), CircleShape),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 80.dp)
                .size(360.dp)
                .background(Brush.radialGradient(listOf(scheme.tertiary.copy(alpha = 0.26f), Color.Transparent)), CircleShape),
        )
        Text(
            text = "Aa",
            fontFamily = family,
            fontWeight = FontWeight.Bold,
            fontSize = 260.sp,
            color = scheme.onBackground.copy(alpha = 0.07f),
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 40.dp, y = 60.dp)
                .rotate(-8f),
        )
    }
}

@Composable
private fun InUseBadge() {
    Text(
        text = stringResource(R.string.fonts_in_use),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** What the last import did: the fonts it added and each file it turned away, and why. */
@Composable
private fun ImportReport(r: FontImportReport, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (r.added.isNotEmpty()) {
            Text(
                text = stringResource(R.string.fonts_added, r.added.joinToString(", ") { it.name }),
                style = type.bodyMedium,
                color = scheme.onSurface,
            )
        }
        r.skipped.forEach { skip ->
            Text(
                text = when (skip.reason) {
                    FontSkipReason.NOT_A_FONT -> stringResource(R.string.fonts_skip_not_a_font, skip.file)
                    FontSkipReason.WEB_FONT -> stringResource(R.string.fonts_skip_web_font, skip.file)
                    FontSkipReason.TYPE1 -> stringResource(R.string.fonts_skip_type1, skip.file)
                    FontSkipReason.EMPTY -> stringResource(R.string.fonts_skip_empty, skip.file)
                    FontSkipReason.TOO_LARGE -> stringResource(R.string.fonts_skip_too_large, skip.file)
                    FontSkipReason.ALREADY_THERE ->
                        stringResource(R.string.fonts_skip_duplicate, skip.file, skip.existing.orEmpty())
                    FontSkipReason.UNREADABLE -> stringResource(R.string.fonts_skip_unreadable, skip.file)
                },
                style = type.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.action_dismiss))
        }
    }
}

/**
 * What the picker offers. File managers label fonts inconsistently — some as
 * font/ttf, some as an application type, many as plain octet-stream — so the
 * list is wide, and the inspector is what actually decides.
 */
private val FONT_MIME_TYPES = arrayOf(
    "font/ttf", "font/otf", "font/sfnt", "font/collection",
    "application/x-font-ttf", "application/x-font-otf", "application/font-sfnt",
    "application/vnd.ms-opentype", "application/octet-stream",
)

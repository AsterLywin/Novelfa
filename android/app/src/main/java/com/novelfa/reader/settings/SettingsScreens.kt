package com.novelfa.reader.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.novelfa.reader.R
import com.novelfa.reader.data.BackupApplyResult
import com.novelfa.reader.data.BackupError
import com.novelfa.reader.data.BackupPreview
import com.novelfa.reader.data.BackupService
import com.novelfa.reader.data.FontStore
import com.novelfa.reader.data.InspectResult
import com.novelfa.reader.theme.AccentSheet
import com.novelfa.reader.theme.CharacterThemes
import com.novelfa.reader.theme.hexToColor
import com.novelfa.reader.theme.AppIcon
import com.novelfa.reader.theme.AppIconKind
import com.novelfa.reader.theme.CharacterSheet
import com.novelfa.reader.theme.FontPickerDialog
import com.novelfa.reader.theme.PrefsStoreRankPrefs
import com.novelfa.reader.theme.RankService
import com.novelfa.reader.theme.XpRank
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Route Routes.SETTINGS: app theme, accent ([AccentSheet]), character theme
 * ([CharacterSheet], locked gated), reader defaults, language fa/en switch,
 * support-reminder toggle, PIN setup/change/remove, rank display.
 * (YAGNI per brief: NO ProfileService avatar on mobile.)
 */
@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    fontStore: FontStore,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var supportOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (viewModel.support.shouldPrompt()) {
            viewModel.support.onPromptShown()
            supportOpen = true
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { ThemeSection(viewModel) }
            item { AccentSection(viewModel) }
            item { CharacterSection(viewModel) }
            item {
                ReaderDefaultsSection(
                    viewModel = viewModel,
                    fontStore = fontStore,
                    notify = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
                )
            }
            item { LanguageSection(viewModel) }
            item { SupportSection(viewModel) }
            item {
                PinSection(
                    viewModel = viewModel,
                    notify = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
                )
            }
            item { RankSection(viewModel) }
            item {
                BackupSection(
                    viewModel = viewModel,
                    notify = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
                )
            }
            item {
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }

    if (supportOpen) {
        SupportDialog(onDismiss = { supportOpen = false })
    }
}

/** Support prompt dialog (fa text from the Task 4 map). WPF parity:
 * the Windows SupportToast is a clickable pill opening
 * Daramet.com/NovelFa, so this dialog gets the same donate action
 * instead of thank-you text with no way to act on it. */
@Composable
fun SupportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.support_novelfa)) },
        text = { Text(stringResource(R.string.thank_you_from_the_bottom_of_our_hearts_for_helping_our)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok_2))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("http://Daramet.com/NovelFa"))
                )
                onDismiss()
            }) {
                Text(stringResource(R.string.link_donate))
            }
        }
    )
}

/** Split WPF-style "singular|plural" verbatim forms at runtime, like Loc.FN. */
@Composable
private fun fnPlural(id: Int, count: Int, vararg args: Any): String {
    val raw = stringResource(id)
    val parts = raw.split("|")
    val form = if (count == 1) parts.first() else parts.last()
    return form.format(*args)
}

@Composable
private fun SectionCard(title: String, icon: AppIconKind, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    AppIcon(
                        icon,
                        tint = MaterialTheme.colorScheme.primary,
                        size = 18.dp
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

/** Hairline divider between rows inside a section card. */
@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .height(1.dp)
            .background(
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            )
    )
}

private data class SegmentOption(val label: String, val icon: AppIconKind? = null)

/**
 * Two-option segmented control (theme mode, language): a soft track
 * with one filled pill for the current choice, replacing the pair of
 * bare outlined buttons the sections used before.
 */
@Composable
private fun SegmentedChoice(
    options: List<SegmentOption>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { index, option ->
            val isSel = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (isSel) MaterialTheme.colorScheme.primary
                        else Color.Transparent
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSel) {
                        AppIcon(
                            AppIconKind.Check,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            size = 13.dp
                        )
                        Spacer(Modifier.width(5.dp))
                    } else if (option.icon != null) {
                        AppIcon(
                            option.icon,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            size = 15.dp
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        option.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSel) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * A tappable settings row: label on the start side, an optional color
 * swatch / palette dots / value, and a direction-aware chevron — the
 * "opens a picker" affordance the old full-width outlined buttons
 * lacked.
 */
@Composable
private fun NavRow(
    label: String,
    value: String? = null,
    swatch: Color? = null,
    dots: List<Color> = emptyList(),
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (swatch != null) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(swatch)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        CircleShape
                    )
            )
            Spacer(Modifier.width(8.dp))
        }
        if (dots.isNotEmpty()) {
            Row {
                dots.forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(c)
                    )
                    Spacer(Modifier.width(4.dp))
                }
            }
            Spacer(Modifier.width(4.dp))
        }
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
        }
        AppIcon(
            if (isRtl) AppIconKind.ArrowForward else AppIconKind.ArrowBack,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 15.dp
        )
    }
}

/** Filled tonal action (create/restore, font actions). */
@Composable
private fun ActionButton(
    label: String,
    icon: AppIconKind?,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val bg = if (primary) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (primary) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    // The label is centered in the button, not just the icon+label
    // group: with a plain centered group the text sits half an icon
    // off-center (that is what the backup buttons showed). A balance
    // spacer mirroring the icon+gap on the other side puts the text's
    // own center on the button's center. fillMaxHeight keeps sibling
    // buttons in a row the same height if a label ever wraps.
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) bg else bg.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 11.dp)
            .fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                AppIcon(icon, tint = fg, size = 15.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = fg,
                textAlign = TextAlign.Center
            )
            if (icon != null) {
                Spacer(Modifier.width(6.dp))
                Spacer(Modifier.width(15.dp))
            }
        }
    }
}

@Composable
private fun ThemeSection(viewModel: SettingsViewModel) {
    val theme by viewModel.theme.collectAsState(initial = "dark")
    val scope = rememberCoroutineScope()
    SectionCard(title = stringResource(R.string.personalization), icon = AppIconKind.Moon) {
        SegmentedChoice(
            options = listOf(
                SegmentOption(stringResource(R.string.dark_mode), AppIconKind.Moon),
                SegmentOption(stringResource(R.string.light_mode), AppIconKind.Sun)
            ),
            selected = if (theme == "light") 1 else 0,
            onSelect = { index ->
                scope.launch {
                    viewModel.setTheme(if (index == 1) "light" else "dark")
                }
            }
        )
    }
}

@Composable
private fun AccentSection(viewModel: SettingsViewModel) {
    val accent by viewModel.accent.collectAsState(initial = "#7C3AED")
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    SectionCard(title = stringResource(R.string.app_color), icon = AppIconKind.Drop) {
        NavRow(
            label = accent,
            swatch = runCatching { hexToColor(accent) }
                .getOrElse { MaterialTheme.colorScheme.primary },
            onClick = { open = true }
        )
    }
    if (open) {
        AccentSheet(
            selectedHex = accent,
            onPick = { hex ->
                scope.launch { viewModel.setAccent(hex) }
                open = false
            },
            onDismiss = { open = false }
        )
    }
}

@Composable
private fun CharacterSection(viewModel: SettingsViewModel) {
    val charTheme by viewModel.charTheme.collectAsState(initial = "")
    val particles by viewModel.themeParticles.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val rank = remember { viewModel.rankService() }
    val isEnglishUi =
        LocalConfiguration.current.locales[0].language.startsWith("en")
    val themeObj = remember(charTheme) { CharacterThemes.find(charTheme) }
    val appTheme by viewModel.theme.collectAsState(initial = "dark")
    SectionCard(title = stringResource(R.string.special_themes), icon = AppIconKind.Sparkle) {
        NavRow(
            label = themeObj?.displayName(isEnglishUi)
                ?: stringResource(R.string.no_character_theme),
            dots = themeObj?.let {
                val pal = it.palette(light = appTheme == "light")
                listOf(
                    runCatching { hexToColor(pal.accent) }.getOrElse { Color.Unspecified },
                    runCatching { hexToColor(pal.grad1) }.getOrElse { Color.Unspecified },
                    runCatching { hexToColor(pal.grad2) }.getOrElse { Color.Unspecified }
                )
            } ?: emptyList(),
            onClick = { open = true }
        )
    }
    if (open) {
        CharacterSheet(
            activeId = charTheme.takeIf { it.isNotBlank() },
            isUnlocked = { rank.isUnlocked(it) },
            onPick = { id ->
                scope.launch { viewModel.setCharTheme(id) }
                open = false
            },
            onDismiss = { open = false },
            particlesEnabled = particles,
            onToggleParticles = { enabled ->
                scope.launch { viewModel.setThemeParticles(enabled) }
            },
            unlockInfoLoader = { theme -> viewModel.themeUnlockInfo(theme, isEnglishUi) }
        )
    }
}

@Composable
private fun ReaderDefaultsSection(
    viewModel: SettingsViewModel,
    fontStore: FontStore,
    notify: (String) -> Unit = {}
) {
    val fontSize by viewModel.fontSize.collectAsState(initial = 18)
    val fontFamily by viewModel.fontFamily.collectAsState(initial = "")
    val lineHeight by viewModel.readerLineHeight.collectAsState(initial = "1.8")
    val margin by viewModel.readerMargin.collectAsState(initial = "16")
    val spacing by viewModel.readerSpacing.collectAsState(initial = "0.6")
    val scope = rememberCoroutineScope()
    var fontDialog by remember { mutableStateOf(false) }
    var familyEdit by remember { mutableStateOf(false) }
    var familyText by remember { mutableStateOf(fontFamily) }
    val invalidFontMsg = stringResource(R.string.invalid_font_name)
    val fontMinMsg = stringResource(R.string.font_size_min, 10)
    val fontMaxMsg = stringResource(R.string.font_size_max, 72)

    SectionCard(title = stringResource(R.string.reading_page), icon = AppIconKind.Reading) {
        IntStepper(
            label = stringResource(R.string.font_size),
            value = fontSize,
            step = 1,
            range = 10..72,
            onSet = { scope.launch { viewModel.setFontSize(it) } },
            onMinLimit = { notify(fontMinMsg) },
            onMaxLimit = { notify(fontMaxMsg) }
        )
        RowDivider()
        Text(
            text = fontFamily.ifBlank { stringResource(R.string.str_default) },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row {
            ActionButton(
                label = stringResource(R.string.custom_fonts),
                icon = null,
                primary = false,
                modifier = Modifier.weight(1f)
            ) { familyText = fontFamily; familyEdit = true }
            Spacer(modifier = Modifier.width(8.dp))
            ActionButton(
                label = stringResource(R.string.add_custom_font),
                icon = AppIconKind.Plus,
                primary = true,
                modifier = Modifier.weight(1f)
            ) { fontDialog = true }
        }
        RowDivider()
        DoubleStepper(
            label = stringResource(R.string.line_spacing),
            value = lineHeight.toDoubleOrNull() ?: 1.8,
            step = 0.1,
            range = 1.0..3.0,
            onSet = { scope.launch { viewModel.setReaderLineHeight(it.toString()) } }
        )
        RowDivider()
        IntStepper(
            label = stringResource(R.string.side_margins),
            value = margin.toIntOrNull() ?: 16,
            step = 2,
            range = 0..64,
            onSet = { scope.launch { viewModel.setReaderMargin(it.toString()) } }
        )
        RowDivider()
        DoubleStepper(
            label = stringResource(R.string.paragraph_spacing),
            value = spacing.toDoubleOrNull() ?: 0.6,
            step = 0.1,
            range = 0.0..3.0,
            onSet = { scope.launch { viewModel.setReaderSpacing(it.toString()) } }
        )
    }

    if (familyEdit) {
        var familyError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { familyEdit = false },
            title = { Text(stringResource(R.string.custom_fonts)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = familyText,
                        onValueChange = { familyText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    familyError?.let {
                        Text(text = it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (viewModel.setFontFamily(familyText)) {
                            familyEdit = false
                        } else {
                            familyError = invalidFontMsg
                        }
                    }
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { familyEdit = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    if (fontDialog) {
        FontPickerDialog(fontStore = fontStore, onDismiss = { fontDialog = false })
    }
}

@Composable
private fun LanguageSection(viewModel: SettingsViewModel) {
    val lang by viewModel.lang.collectAsState(initial = "fa")
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    SectionCard(title = stringResource(R.string.language), icon = AppIconKind.Globe) {
        SegmentedChoice(
            options = listOf(
                SegmentOption("فارسی"),
                SegmentOption("English")
            ),
            selected = if (lang == "en") 1 else 0,
            onSelect = { index ->
                scope.launch {
                    viewModel.setLocale(context, if (index == 1) "en" else "fa")
                    (context as? Activity)?.recreate()
                }
            }
        )
    }
}

@Composable
private fun SupportSection(viewModel: SettingsViewModel) {
    val enabled by viewModel.supportEnabled.collectAsState(initial = true)
    val scope = rememberCoroutineScope()
    SectionCard(title = stringResource(R.string.support), icon = AppIconKind.Heart) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.support_novelfa),
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = enabled,
                onCheckedChange = { scope.launch { viewModel.setSupportEnabled(it) } }
            )
        }
    }
}

@Composable
private fun PinSection(viewModel: SettingsViewModel, notify: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var hasPin by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        hasPin = viewModel.pinManager.hasPin()
    }
    var setupOpen by remember { mutableStateOf(false) }
    var changeOpen by remember { mutableStateOf(false) }
    var removeConfirm by remember { mutableStateOf(false) }
    val createdMsg = stringResource(R.string.your_private_pin_was_created)
    val changedMsg = stringResource(R.string.your_private_pin_was_changed_successfully)

    SectionCard(title = stringResource(R.string.privacy), icon = AppIconKind.Lock) {
        if (!hasPin) {
            NavRow(
                label = stringResource(R.string.create_a_pin),
                onClick = { setupOpen = true }
            )
        } else {
            NavRow(
                label = stringResource(R.string.change_pin),
                onClick = { changeOpen = true }
            )
            RowDivider()
            NavRow(
                label = stringResource(R.string.remove_pin),
                danger = true,
                onClick = { removeConfirm = true }
            )
        }
    }

    if (setupOpen) {
        PinDialog(
            mode = PinMode.SETUP,
            manager = viewModel.pinManager,
            onSuccess = {
                setupOpen = false
                hasPin = true
                notify(createdMsg)
            },
            onDismiss = { setupOpen = false }
        )
    }
    if (changeOpen) {
        PinDialog(
            mode = PinMode.CHANGE,
            manager = viewModel.pinManager,
            onSuccess = {
                changeOpen = false
                notify(changedMsg)
            },
            onDismiss = { changeOpen = false }
        )
    }
    if (removeConfirm) {
        AlertDialog(
            onDismissRequest = { removeConfirm = false },
            title = { Text(stringResource(R.string.remove_pin)) },
            text = { Text(stringResource(R.string.remove_pin_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        viewModel.pinManager.clearPin()
                        PinSession.unlocked = false
                        hasPin = false
                        removeConfirm = false
                    }
                }) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { removeConfirm = false }) {
                    Text(stringResource(R.string.no))
                }
            }
        )
    }
}

@Composable
private fun RankSection(viewModel: SettingsViewModel) {
    var summary by remember { mutableStateOf<XpProgressSummary?>(null) }
    LaunchedEffect(Unit) {
        summary = viewModel.xpProgress()
    }
    val lang by viewModel.lang.collectAsState(initial = "fa")
    val isEnglishUi = lang.startsWith("en")
    SectionCard(title = stringResource(R.string.progress), icon = AppIconKind.CheckCircle) {
        val s = summary
        if (s == null) {
            Text(stringResource(R.string.str_0))
        } else {
            val level = s.level
            // Same XP level display as the home header (title + stars or
            // eternity marks): this is the fix for settings showing
            // "تازه‌وارد" while home showed "کتاب‌باز ★☆☆☆☆" for the same
            // user, because settings used the old finished-books rank.
            Text(
                text = level.displayName(isEnglishUi),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.xp_total_label, s.totalXp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.xp_title_of, level.titleIndex + 1),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (level.mark != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "✦ ${level.mark!!.displayName(isEnglishUi)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { level.progress.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )
            Spacer(modifier = Modifier.height(8.dp))
            val nextStepName = if (level.titleIndex < XpRank.Titles.size - 1) {
                XpRank.Titles[level.titleIndex + 1].displayName(isEnglishUi)
            } else {
                XpRank.eternityMarkFor(level.eternityMarks + 1).displayName(isEnglishUi)
            }
            Text(
                text = stringResource(R.string.xp_more_to, level.xpToNext, nextStepName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                fnPlural(
                    R.string.book_completed_books_completed,
                    s.finishedBooks,
                    s.finishedBooks.toString()
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.xp_legacy_rank, s.legacyRankName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val nextTheme = s.nextLockedTheme
            if (nextTheme != null) {
                val xpTitle = XpRank.Titles[nextTheme.xpTitle]
                val legacyRank = RankService.Ranks[nextTheme.requiredRank]
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.xp_next_theme, nextTheme.displayName(isEnglishUi)),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.theme_unlock_xp,
                        s.totalXp, xpTitle.minXp, xpTitle.displayName(isEnglishUi)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.theme_unlock_books,
                        s.finishedBooks, legacyRank.minBooks,
                        legacyRank.displayName(isEnglishUi)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BackupSection(viewModel: SettingsViewModel, notify: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lang by viewModel.lang.collectAsState(initial = "fa")
    val isEnglishUi = lang.startsWith("en")
    val rankPrefs = remember(viewModel.prefs) { PrefsStoreRankPrefs(viewModel.prefs) }
    val service = remember {
        BackupService(context.applicationContext, viewModel.prefs, viewModel.dao, rankPrefs)
    }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    var result by remember { mutableStateOf<BackupApplyResult?>(null) }

    // Hoisted strings: launcher/coroutine callbacks are not composable.
    val createdMsg = stringResource(R.string.backup_created)
    val exportFailedMsg = stringResource(R.string.backup_export_failed)
    val errNotBackup = stringResource(R.string.backup_err_not_backup)
    val errTooNew = stringResource(R.string.backup_err_too_new)
    val errCorrupt = stringResource(R.string.backup_err_corrupt)
    val errFont = stringResource(R.string.backup_err_font)
    val errImage = stringResource(R.string.backup_err_image)
    val errTooLarge = stringResource(R.string.backup_err_too_large)
    val errUnreadable = stringResource(R.string.backup_err_unreadable)
    fun errorText(e: BackupError): String = when (e) {
        BackupError.NOT_A_BACKUP -> errNotBackup
        BackupError.TOO_NEW -> errTooNew
        BackupError.CORRUPT -> errCorrupt
        BackupError.FONT_INVALID -> errFont
        BackupError.IMAGE_INVALID -> errImage
        BackupError.TOO_LARGE -> errTooLarge
        BackupError.UNREADABLE -> errUnreadable
    }

    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val ok = context.contentResolver.openOutputStream(uri)?.use { out ->
                    service.exportBackup(out)
                    true
                } ?: false
                notify(if (ok) createdMsg else exportFailedMsg)
            } catch (e: Exception) {
                notify(exportFailedMsg)
            } finally {
                busy = false
            }
        }
    }
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                when (val result = context.contentResolver.openInputStream(uri)
                    ?.use { service.inspectBackup(it) }) {
                    is InspectResult.Ok -> preview = result.preview
                    is InspectResult.Failed -> notify(errorText(result.error))
                    null -> notify(errUnreadable)
                }
            } catch (e: Exception) {
                notify(errUnreadable)
            } finally {
                busy = false
            }
        }
    }

    SectionCard(title = stringResource(R.string.backup_section_title), icon = AppIconKind.File) {
        Row {
            ActionButton(
                label = stringResource(R.string.backup_create),
                icon = AppIconKind.Plus,
                primary = true,
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) {
                val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                createLauncher.launch("novelfa-backup-$stamp.novelfabak")
            }
            Spacer(modifier = Modifier.width(8.dp))
            ActionButton(
                label = stringResource(R.string.backup_restore),
                icon = AppIconKind.File,
                primary = false,
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) { openLauncher.launch(arrayOf("*/*")) }
        }
        if (busy) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                stringResource(R.string.backup_working),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    preview?.let { p ->
        val rankName = RankService.Ranks
            .getOrNull(p.maxRank.coerceAtLeast(0))
            ?.displayName(isEnglishUi)
            ?: ""
        val platformLabel = when (p.platform) {
            "android" -> stringResource(R.string.platform_android)
            "windows" -> stringResource(R.string.platform_windows)
            else -> p.platform
        }
        AlertDialog(
            onDismissRequest = {
                service.discard(p)
                preview = null
            },
            title = { Text(stringResource(R.string.backup_summary_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.backup_summary_created, p.createdAt.take(19).replace('T', ' ')))
                    Text(stringResource(R.string.backup_summary_platform, platformLabel))
                    if (p.profileName.isNotBlank()) {
                        Text(p.profileName)
                    }
                    Text(stringResource(R.string.backup_summary_rank, rankName))
                    Text(stringResource(R.string.backup_summary_books, p.finishedBooks))
                    Text(stringResource(R.string.backup_summary_fonts, p.fonts.size))
                    if (p.records.isNotEmpty()) {
                        Text(stringResource(R.string.backup_summary_records, p.records.size))
                    }
                    Text(
                        stringResource(
                            if (p.avatar != null) R.string.backup_summary_avatar_yes
                            else R.string.backup_summary_avatar_no
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    preview = null
                    busy = true
                    scope.launch {
                        try {
                            result = service.applyBackup(p)
                        } catch (e: Exception) {
                            notify(errUnreadable)
                        } finally {
                            busy = false
                        }
                    }
                }) {
                    Text(stringResource(R.string.backup_restore_button))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    service.discard(p)
                    preview = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Post-restore announcement: what the merge produced (rank move,
    // summed finished-books total, fonts added).
    result?.let { r ->
        val rankBeforeName = RankService.Ranks
            .getOrNull(r.rankBefore.coerceAtLeast(0))
            ?.displayName(isEnglishUi)
            ?: ""
        val rankAfterName = RankService.Ranks
            .getOrNull(r.rankAfter.coerceAtLeast(0))
            ?.displayName(isEnglishUi)
            ?: ""
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text(stringResource(R.string.backup_result_title)) },
            text = {
                Column {
                    if (r.rankAfter > r.rankBefore) {
                        Text(
                            stringResource(
                                R.string.backup_result_rank_upgraded,
                                rankBeforeName,
                                rankAfterName
                            )
                        )
                    } else {
                        Text(stringResource(R.string.backup_result_rank, rankAfterName))
                    }
                    Text(stringResource(R.string.backup_result_books_total, r.finishedTotal))
                    if (r.fontsAdded > 0) {
                        Text(stringResource(R.string.backup_result_fonts_added, r.fontsAdded))
                    }
                    if (r.fontsSkipped > 0) {
                        Text(stringResource(R.string.backup_result_fonts_skipped, r.fontsSkipped))
                    }
                    if (r.fontsFailed > 0) {
                        Text(stringResource(R.string.backup_result_fonts_failed, r.fontsFailed))
                    }
                    if (r.recordsApplied > 0) {
                        Text(stringResource(R.string.backup_result_records_applied, r.recordsApplied))
                    }
                    if (r.recordsKept > 0) {
                        Text(stringResource(R.string.backup_result_records_kept, r.recordsKept))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { result = null }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}

@Composable
private fun IntStepper(
    label: String,
    value: Int,
    step: Int,
    range: IntRange,
    onSet: (Int) -> Unit,
    /**
     * Called instead of [onSet] when a press would pass a bound, so the
     * caller can tell the user the limit instead of silently staying
     * put (the font-size case: minus at 10 looked like a broken app).
     */
    onMinLimit: (() -> Unit)? = null,
    onMaxLimit: (() -> Unit)? = null
) {
    StepperShell(
        label = label,
        valueText = value.toString(),
        onMinus = {
            val next = value - step
            if (next < range.first) onMinLimit?.invoke() else onSet(next)
        },
        onPlus = {
            val next = value + step
            if (next > range.last) onMaxLimit?.invoke() else onSet(next)
        }
    )
}

@Composable
private fun DoubleStepper(
    label: String,
    value: Double,
    step: Double,
    range: ClosedRange<Double>,
    onSet: (Double) -> Unit
) {
    fun Double.round1(): Double = kotlin.math.round(this * 10) / 10.0
    StepperShell(
        label = label,
        valueText = if (value % 1.0 == 0.0) value.toInt().toString()
        else value.toString(),
        onMinus = { onSet((value - step).round1().coerceIn(range)) },
        onPlus = { onSet((value + step).round1().coerceIn(range)) }
    )
}

/**
 * One stepper row: the label, and a soft pill holding [−] value [+]
 * as a single grouped control (the old rows floated two big outlined
 * buttons next to a "label: value" text).
 */
@Composable
private fun StepperShell(
    label: String,
    valueText: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StepperKey("−", onMinus)
            Text(
                valueText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(46.dp)
            )
            StepperKey("+", onPlus)
        }
    }
}

@Composable
private fun StepperKey(glyph: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, style = MaterialTheme.typography.titleMedium)
    }
}

private fun Double.coerceIn(range: ClosedRange<Double>): Double =
    coerceAtLeast(range.start).coerceAtMost(range.endInclusive)

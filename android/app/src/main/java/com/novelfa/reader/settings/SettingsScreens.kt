package com.novelfa.reader.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.novelfa.reader.R
import com.novelfa.reader.data.BackupError
import com.novelfa.reader.data.BackupPreview
import com.novelfa.reader.data.BackupService
import com.novelfa.reader.data.FontStore
import com.novelfa.reader.data.InspectResult
import com.novelfa.reader.theme.AccentSheet
import com.novelfa.reader.theme.CharacterSheet
import com.novelfa.reader.theme.FontPickerDialog
import com.novelfa.reader.theme.PrefsStoreRankPrefs
import com.novelfa.reader.theme.RankService
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { ThemeSection(viewModel) }
            item { AccentSection(viewModel) }
            item { CharacterSection(viewModel) }
            item { ReaderDefaultsSection(viewModel, fontStore) }
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
                TextButton(onClick = onClose, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }

    if (supportOpen) {
        SupportDialog(onDismiss = { supportOpen = false })
    }
}

/** Support prompt dialog (fa text from the Task 4 map). */
@Composable
fun SupportDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.support_novelfa)) },
        text = { Text(stringResource(R.string.thank_you_from_the_bottom_of_our_hearts_for_helping_our)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok_2))
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
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ThemeSection(viewModel: SettingsViewModel) {
    val theme by viewModel.theme.collectAsState(initial = "dark")
    val scope = rememberCoroutineScope()
    SectionCard(title = stringResource(R.string.personalization)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { scope.launch { viewModel.setTheme("dark") } },
                modifier = Modifier.weight(1f)
            ) {
                Text((if (theme == "dark") "✓ " else "") + stringResource(R.string.dark_mode))
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = { scope.launch { viewModel.setTheme("light") } },
                modifier = Modifier.weight(1f)
            ) {
                Text((if (theme == "light") "✓ " else "") + stringResource(R.string.light_mode))
            }
        }
    }
}

@Composable
private fun AccentSection(viewModel: SettingsViewModel) {
    val accent by viewModel.accent.collectAsState(initial = "#7C3AED")
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    SectionCard(title = stringResource(R.string.app_color)) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.app_color) + ": " + accent)
        }
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
    SectionCard(title = stringResource(R.string.special_themes)) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (charTheme.isBlank()) stringResource(R.string.no_character_theme) else charTheme)
        }
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
            }
        )
    }
}

@Composable
private fun ReaderDefaultsSection(viewModel: SettingsViewModel, fontStore: FontStore) {
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

    SectionCard(title = stringResource(R.string.reading_page)) {
        IntStepper(
            label = stringResource(R.string.font_size),
            value = fontSize,
            step = 1,
            range = 10..72,
            onSet = { scope.launch { viewModel.setFontSize(it) } }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = fontFamily.ifBlank { stringResource(R.string.str_default) },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = { familyText = fontFamily; familyEdit = true }) {
                Text(stringResource(R.string.custom_fonts))
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = { fontDialog = true }) {
                Text(stringResource(R.string.add_custom_font))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        DoubleStepper(
            label = stringResource(R.string.line_spacing),
            value = lineHeight.toDoubleOrNull() ?: 1.8,
            step = 0.1,
            range = 1.0..3.0,
            onSet = { scope.launch { viewModel.setReaderLineHeight(it.toString()) } }
        )
        IntStepper(
            label = stringResource(R.string.side_margins),
            value = margin.toIntOrNull() ?: 16,
            step = 2,
            range = 0..64,
            onSet = { scope.launch { viewModel.setReaderMargin(it.toString()) } }
        )
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
    SectionCard(title = stringResource(R.string.language)) {
        Row {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        viewModel.setLocale(context, "fa")
                        (context as? Activity)?.recreate()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text((if (lang == "fa") "✓ " else "") + "فارسی")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        viewModel.setLocale(context, "en")
                        (context as? Activity)?.recreate()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text((if (lang == "en") "✓ " else "") + "English")
            }
        }
    }
}

@Composable
private fun SupportSection(viewModel: SettingsViewModel) {
    val enabled by viewModel.supportEnabled.collectAsState(initial = true)
    val scope = rememberCoroutineScope()
    SectionCard(title = stringResource(R.string.support)) {
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

    SectionCard(title = stringResource(R.string.privacy)) {
        if (!hasPin) {
            OutlinedButton(onClick = { setupOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.create_a_pin))
            }
        } else {
            Row {
                OutlinedButton(onClick = { changeOpen = true }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.change_pin))
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = { removeConfirm = true }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.remove_pin))
                }
            }
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
    var summary by remember { mutableStateOf<RankSummary?>(null) }
    LaunchedEffect(Unit) {
        summary = viewModel.currentRank()
    }
    SectionCard(title = stringResource(R.string.progress)) {
        val s = summary
        if (s == null) {
            Text(stringResource(R.string.str_0))
        } else {
            Text(stringResource(R.string.current_rank, s.currentName))
            Spacer(modifier = Modifier.height(4.dp))
            Text(fnPlural(R.string.book_completed_books_completed, s.completed, s.completed.toString()))
            if (s.nextName != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    fnPlural(
                        R.string.more_book_to_reach_more_books_to_reach,
                        s.remaining,
                        s.remaining.toString(),
                        s.nextName
                    )
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

    // Hoisted strings: launcher/coroutine callbacks are not composable.
    val createdMsg = stringResource(R.string.backup_created)
    val exportFailedMsg = stringResource(R.string.backup_export_failed)
    val resultOkMsg = stringResource(R.string.backup_result_ok)
    val addedFmt = stringResource(R.string.backup_result_fonts_added)
    val skippedFmt = stringResource(R.string.backup_result_fonts_skipped)
    val failedFmt = stringResource(R.string.backup_result_fonts_failed)
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

    SectionCard(title = stringResource(R.string.backup_section_title)) {
        Row {
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                    createLauncher.launch("novelfa-backup-$stamp.novelfabak")
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.backup_create))
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                enabled = !busy,
                onClick = { openLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.backup_restore))
            }
        }
        if (busy) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.backup_working))
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
                            val r = service.applyBackup(p)
                            val parts = mutableListOf(resultOkMsg)
                            if (r.fontsAdded > 0) parts += addedFmt.format(r.fontsAdded)
                            if (r.fontsSkipped > 0) parts += skippedFmt.format(r.fontsSkipped)
                            if (r.fontsFailed > 0) parts += failedFmt.format(r.fontsFailed)
                            notify(parts.joinToString(" — "))
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
}

@Composable
private fun IntStepper(
    label: String,
    value: Int,
    step: Int,
    range: IntRange,
    onSet: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "$label: $value", modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onSet((value - step).coerceIn(range)) }) {
            Text("−")
        }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(onClick = { onSet((value + step).coerceIn(range)) }) {
            Text("+")
        }
    }
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "$label: $value", modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onSet((value - step).round1().coerceIn(range)) }) {
            Text("−")
        }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(onClick = { onSet((value + step).round1().coerceIn(range)) }) {
            Text("+")
        }
    }
}

private fun Double.coerceIn(range: ClosedRange<Double>): Double =
    coerceAtLeast(range.start).coerceAtMost(range.endInclusive)

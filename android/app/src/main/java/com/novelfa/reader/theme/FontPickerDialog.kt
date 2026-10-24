package com.novelfa.reader.theme

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.novelfa.reader.R
import com.novelfa.reader.data.FontStore
import com.novelfa.reader.data.RecommendedFonts
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Display name for a font file: bundled fonts get proper names in BOTH
 * UI languages (fa/en data pair, like the accent/rank/theme name pairs —
 * never a single-language hardcoded label); everything else shows the
 * file name without its extension.
 */
fun fontDisplayName(fileName: String, isEnglish: Boolean = false): String {
    val base = fileName.substringAfterLast('/').substringAfterLast('\\')
    return when (base.lowercase()) {
        "vazirmatn-regular.ttf" -> if (isEnglish) "Vazirmatn Regular" else "وزیر متن عادی"
        "vazirmatn-bold.ttf" -> if (isEnglish) "Vazirmatn Bold" else "وزیر متن بولد"
        "b koodak bold_0.ttf" -> if (isEnglish) "B Koodak Bold" else "کودک بولد"
        else -> base.substringBeforeLast('.')
    }
}

/**
 * Custom-font picker: SAF .ttf/.otf import with a name field
 * (saved via FontStore.addFont), plus list + delete.
 */
@Composable
fun FontPickerDialog(
    fontStore: FontStore,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Disk I/O off the composition: load the font list once on IO.
    var fonts by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(fontStore) {
        fonts = withContext(Dispatchers.IO) { fontStore.list().map { it.name } }
    }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // WPF parity: font search box filtering the installed list.
    var fontQuery by remember { mutableStateOf("") }
    val isEnglishFonts = LocalConfiguration.current.locales[0].language.startsWith("en")
    val visibleFonts = remember(fonts, fontQuery, isEnglishFonts) {
        if (fontQuery.isBlank()) fonts
        else fonts.filter {
            it.contains(fontQuery, ignoreCase = true) ||
                fontDisplayName(it, isEnglishFonts).contains(fontQuery, ignoreCase = true)
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                importFont(context, uri, name, fontStore)
            }
            if (err != null) {
                error = err
            } else {
                name = ""
                error = null
                fonts = withContext(Dispatchers.IO) { fontStore.list().map { it.name } }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.custom_font),
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.font_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    Button(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Text(stringResource(R.string.pick_font_file))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.close))
                    }
                }
                error?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = fontQuery,
                    onValueChange = { fontQuery = it },
                    label = { Text(stringResource(R.string.search_font)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                LazyColumn {
                    items(visibleFonts) { fileName ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = fontDisplayName(fileName, isEnglishFonts),
                                modifier = Modifier.weight(1f),
                                // Font names are centered, never RTL/LTR aligned.
                                textAlign = TextAlign.Center
                            )
                            // Bundled recommended fonts are not user-deletable
                            // (the desktop client ships them too); a delete
                            // would just be undone on next launch anyway.
                            if (!RecommendedFonts.isBundled(context, fileName)) {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { fontStore.deleteFont(fileName) }
                                        fonts = withContext(Dispatchers.IO) {
                                            fontStore.list().map { it.name }
                                        }
                                    }
                                }) {
                                    Text(stringResource(R.string.delete))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun importFont(
    context: android.content.Context,
    uri: android.net.Uri,
    name: String,
    fontStore: FontStore
): String? {
    val resolver = context.contentResolver
    if (name.isBlank()) return context.getString(R.string.font_name_blank)
    // The name becomes a file name (FontStore) and a CSS font-family value:
    // reject path separators, traversal and markup to block self-XSS and
    // files escaping the fonts dir.
    if (name.contains('/') || name.contains('\\') || name.contains("..") ||
        name.contains('<') || name.contains('>')
    ) {
        return context.getString(R.string.font_name_invalid_chars)
    }
    val ext = uri.lastPathSegment
        ?.substringAfterLast('.', "")
        ?.lowercase()
        .orEmpty()
    if (ext != "ttf" && ext != "otf") {
        // Content URIs often lack extensions; accept and re-check below.
    }
    return try {
        val tmp = File.createTempFile("font-import", ".$ext")
        resolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        } ?: return context.getString(R.string.font_file_not_found)
        val bytes = tmp.length()
        if (bytes < 4) return context.getString(R.string.font_not_valid)
        val header = ByteArray(4)
        tmp.inputStream().use { it.read(header) }
        // Strict magic-byte check: 0x00010000 / 'true' / 'typ1' (TrueType),
        // 'OTTO' (OpenType). The extension alone is not trusted — a renamed
        // non-font file must not be imported and base64-embedded.
        val headerStr = String(header, Charsets.US_ASCII)
        val isTtf = (header[0] == 0x00.toByte() && header[1] == 0x01.toByte()) ||
            headerStr == "OTTO" || headerStr == "true" || headerStr == "typ1"
        if (!isTtf) {
            return context.getString(R.string.font_only_ttf_otf)
        }
        val destName = "$name.${if (ext == "otf") "otf" else "ttf"}"
        if (fontStore.list().any { it.name.equals(destName, ignoreCase = true) }) {
            return context.getString(R.string.font_already_added)
        }
        fontStore.addFont(destName, tmp)
        tmp.delete()
        null
    } catch (e: Exception) {
        context.getString(R.string.font_copy_failed)
    }
}

/**
 * Font-selection list: replaces the old manual family-name typing
 * (which accepted any string, typos included, with no feedback).
 * Rows: Default (no font selected) first, then every FontStore file
 * (bundled + user-added). Tapping a row selects it; the stored value
 * is the file's name without extension — the same value the reader
 * resolves by substring match — or "" for Default.
 */
@Composable
fun FontSelectDialog(
    fontStore: FontStore,
    currentFamily: String,
    error: String? = null,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Disk I/O off the composition: load the font list once on IO.
    var fonts by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(fontStore) {
        fonts = withContext(Dispatchers.IO) { fontStore.list().map { it.name } }
    }
    val isEnglishFonts = LocalConfiguration.current.locales[0].language.startsWith("en")
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.custom_fonts),
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn {
                    item {
                        FontSelectRow(
                            label = stringResource(R.string.str_default),
                            selected = currentFamily.isBlank(),
                            onClick = { onSelect("") }
                        )
                    }
                    items(fonts) { fileName ->
                        val value = fileName.substringBeforeLast('.')
                        FontSelectRow(
                            label = fontDisplayName(fileName, isEnglishFonts),
                            // Mirrors the reader's resolution (substring
                            // match): a stem family selects its files.
                            selected = currentFamily.isNotBlank() &&
                                value.contains(currentFamily, ignoreCase = true),
                            onClick = { onSelect(value) }
                        )
                    }
                }
                error?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun FontSelectRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            // Font names are centered, never RTL/LTR aligned.
            textAlign = TextAlign.Center
        )
        if (selected) {
            AppIcon(
                AppIconKind.Check,
                tint = MaterialTheme.colorScheme.primary,
                size = 18.dp
            )
        } else {
            Spacer(modifier = Modifier.width(18.dp))
        }
    }
}

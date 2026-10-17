package com.novelfa.reader.theme

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.novelfa.reader.data.FontStore
import com.novelfa.reader.data.RecommendedFonts
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val visibleFonts = remember(fonts, fontQuery) {
        if (fontQuery.isBlank()) fonts
        else fonts.filter { it.contains(fontQuery, ignoreCase = true) }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                importFont(context.contentResolver, uri, name, fontStore)
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
                    text = "فونت شخصی",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("اسم فونت") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    Button(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Text("انتخاب فایل فونت")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onDismiss) {
                        Text("بستن")
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
                    label = { Text("جست‌وجوی فونت") },
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
                                text = fileName,
                                modifier = Modifier.weight(1f)
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
                                    Text("حذف")
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
    resolver: android.content.ContentResolver,
    uri: android.net.Uri,
    name: String,
    fontStore: FontStore
): String? {
    if (name.isBlank()) return "اسم فونت نمی‌تونه خالی باشه."
    // The name becomes a file name (FontStore) and a CSS font-family value:
    // reject path separators, traversal and markup to block self-XSS and
    // files escaping the fonts dir.
    if (name.contains('/') || name.contains('\\') || name.contains("..") ||
        name.contains('<') || name.contains('>')
    ) {
        return "اسم فونت نباید شامل / \\ .. < > باشه."
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
        } ?: return "فایل فونت پیدا نشد."
        val bytes = tmp.length()
        if (bytes < 4) return "این فایل یک فونت معتبر نیست."
        val header = ByteArray(4)
        tmp.inputStream().use { it.read(header) }
        // Strict magic-byte check: 0x00010000 / 'true' / 'typ1' (TrueType),
        // 'OTTO' (OpenType). The extension alone is not trusted — a renamed
        // non-font file must not be imported and base64-embedded.
        val headerStr = String(header, Charsets.US_ASCII)
        val isTtf = (header[0] == 0x00.toByte() && header[1] == 0x01.toByte()) ||
            headerStr == "OTTO" || headerStr == "true" || headerStr == "typ1"
        if (!isTtf) {
            return "فقط فایل‌های ttf و otf پشتیبانی می‌شن."
        }
        val destName = "$name.${if (ext == "otf") "otf" else "ttf"}"
        if (fontStore.list().any { it.name.equals(destName, ignoreCase = true) }) {
            return "فونتی با این اسم قبلاً اضافه شده."
        }
        fontStore.addFont(destName, tmp)
        tmp.delete()
        null
    } catch (e: Exception) {
        "کپی کردن فایل فونت انجام نشد."
    }
}

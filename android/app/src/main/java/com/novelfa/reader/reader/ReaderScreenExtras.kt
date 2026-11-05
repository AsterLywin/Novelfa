package com.novelfa.reader.reader

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.novelfa.reader.R
import com.novelfa.reader.parser.BookFormat
import com.novelfa.reader.parser.CacheStamp
import com.novelfa.reader.parser.ConvertedBook
import com.novelfa.reader.parser.EpubParser
import com.novelfa.reader.parser.engine.ArchiveCandidate
import com.novelfa.reader.parser.engine.EngineRegistry
import com.novelfa.reader.parser.engine.EpubEngine
import com.novelfa.reader.parser.engine.ZipEngine
import com.novelfa.reader.theme.AppIcon
import com.novelfa.reader.theme.AppIconKind
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException

@Composable
internal fun SpacingDialog(
    lineHeight: Float,
    sideMargin: Int,
    paragraphSpacing: Float,
    onDismiss: () -> Unit,
    onApply: (Float, Int, Float) -> Unit,
    onReset: () -> Unit
) {
    var lh by remember { mutableFloatStateOf(lineHeight) }
    var margin by remember { mutableIntStateOf(sideMargin) }
    var para by remember { mutableFloatStateOf(paragraphSpacing) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onApply(lh, margin, para) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.reset_to_default))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
        title = { Text(stringResource(R.string.spacing_settings)) },
        text = {
            Column {
                Text(stringResource(R.string.line_spacing) + ": " + lh.toString())
                Slider(
                    value = lh,
                    onValueChange = { lh = it },
                    valueRange = 1.2f..3.0f,
                    steps = 17
                )
                Text(stringResource(R.string.side_margins) + ": " + margin.toString())
                Slider(
                    value = margin.toFloat(),
                    onValueChange = { margin = it.toInt() },
                    valueRange = 0f..160f,
                    steps = 159
                )
                Text(stringResource(R.string.paragraph_spacing) + ": " + para.toString())
                Slider(
                    value = para,
                    onValueChange = { para = it },
                    valueRange = 0f..2.5f,
                    steps = 24
                )
            }
        }
    )
}

/**
 * Opens a stored book into its chapter files. Each format is opened
 * through its own rendering engine (see parser/engine): EPUBs are
 * extracted (once per source change, guarded by the stamp) and parsed
 * from the tree; TXT / Markdown / PDF / DOCX are converted into
 * chapter files in the same cache directory, and a valid stamp lets the
 * next open rebuild the book from those files without re-converting.
 * The stamp ([CacheStamp]) covers mtime, length AND a first/last-bytes
 * fingerprint, so a replaced file with an unchanged mtime and the same
 * length still re-converts. TXT/Markdown caches are filled at import
 * time, so their first open is normally a cache hit; EPUB, DOCX and
 * book-carrying ZIPs are parsed at first open instead.
 */
@Throws(IOException::class)
internal suspend fun openParsed(
    context: Context,
    bookPath: String,
    bookId: String,
    /** Original file name, when known: title fallback instead of the "$uuid.<ext>" storage name. */
    titleFallback: String? = null,
    /** PDF conversion progress for the loading UI: (pages done, pages total). */
    onProgress: ((Int, Int) -> Unit)? = null,
    /** For a multi-book ZIP archive: the book the user chose from
     *  the archive-choice dialog. Null for every other format, and
     *  for single-book ZIPs (the engine picks the only candidate). */
    zipCandidate: ArchiveCandidate? = null
): com.novelfa.reader.model.ParsedBook {
    val book = File(bookPath)
    if (!book.exists()) {
        throw IOException("book missing: " + book.absolutePath)
    }
    val format = BookFormat.detect(book)
        ?: throw IOException("unsupported book format: " + book.name)
    // Converted/extracted chapters live in filesDir, NOT cacheDir:
    // Android may evict cacheDir under storage pressure, and every
    // eviction forced a full re-parse on the next open — for DOCX that
    // is the "render takes forever" users see on every open. filesDir
    // is never evicted by the system; removeBook + the startup prune
    // delete a book's dir, and backup rules exclude it (regenerable).
    val outDir = File(context.filesDir, "epub_open/" + bookId)
    outDir.mkdirs()
    val chapterFallback: (Int) -> String = { n ->
        context.getString(R.string.chapter_fallback, n)
    }
    val stampOk = CacheStamp.isValidStamp(outDir, book)
    if (stampOk) {
        if (format == BookFormat.EPUB) {
            return EpubParser.parse(outDir, titleFallback ?: book.name, chapterFallback)
        }
        val cached = ConvertedBook.read(outDir, titleFallback ?: book.name, chapterFallback)
        if (cached != null && isConvertedCacheValid(outDir, cached)) {
            return cached
        }
        // Cache present but empty, unreadable, or missing its images
        // (e.g. a crashed convert, or media files deleted by hand):
        // fall through to a fresh conversion below instead of
        // showing blank chapters / broken images silently.
    }
    // Stamp-miss rescue (converted formats only; EPUB re-unzips
    // cheaply and keeps its old path): a conversion killed AFTER it
    // wrote its chapters but BEFORE the stamp landed used to be
    // deleted here and converted from zero on the next open — for
    // a big PDF that is the whole slow conversion again. Chapters
    // are only written at the end of a conversion, so a cache that
    // passes the same validity check the stamp-hit path uses is a
    // finished conversion, not a partial one, and is safe to return;
    // the reader's background renderMissing still fills any missing
    // scanned pages afterwards.
    if (!stampOk && format != BookFormat.EPUB) {
        val rescued = ConvertedBook.read(outDir, titleFallback ?: book.name, chapterFallback)
        if (rescued != null && isConvertedCacheValid(outDir, rescued)) {
            return rescued
        }
    }
    outDir.deleteRecursively()
    outDir.mkdirs()
    // Rendering is engine-owned now: each format's engine parses
    // (or, for a book inside a ZIP, extracts + parses) through its
    // own path. The old code branched here on EPUB vs. one shared
    // dispatch for everything else; a format's rendering change
    // could no longer be reasoned about in isolation.
    // Capture the coroutine context once: currentCoroutineContext()
    // is itself suspend, so it cannot be called inside the plain
    // cancel-check lambda the engines receive.
    val openCtx = currentCoroutineContext()
    val cancelCheck = { openCtx.ensureActive() }
    val parsed = when {
        format == BookFormat.EPUB -> EpubEngine.open(
            book, outDir, chapterFallback, titleFallback, onProgress, cancelCheck
        )
        format == BookFormat.ZIP && zipCandidate != null -> ZipEngine.openCandidate(
            book, zipCandidate, outDir, chapterFallback, titleFallback, onProgress, cancelCheck
        )
        else -> EngineRegistry.engineFor(format).open(
            book, outDir, chapterFallback, titleFallback, onProgress, cancelCheck
        )
    }
    CacheStamp.writeStamp(outDir, book)
    return parsed
}

/**
 * Health check for a converted-book cache hit: every chapter file must
 * exist and be non-empty, and if any chapter references `media/` images
 * (DOCX / scanned PDF), that folder must actually hold files. A cache
 * that fails this is re-converted, never shown half-broken.
 */
private fun isConvertedCacheValid(
    outDir: File,
    parsed: com.novelfa.reader.model.ParsedBook
): Boolean {
    if (parsed.chapters.isEmpty()) return false
    var needsMedia = false
    for (chapter in parsed.chapters) {
        val file = chapter.file
        if (!file.isFile || file.length() == 0L) return false
        if (!needsMedia) {
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            if (text != null && text.contains("media/")) needsMedia = true
        }
    }
    if (needsMedia) {
        val media = File(outDir, "media").listFiles()
        if (media.isNullOrEmpty()) return false
    }
    return true
}

/**
 * Book-level reading direction from content: the majority script of the
 * first few chapters' plain text. Latin majority → LTR; anything else
 * (Persian/Arabic majority, or no letters at all) → RTL, matching the
 * app's Persian-first default. Only a sample is read (4 KB per chapter)
 * so opening a long book stays cheap.
 */
internal fun detectBookIsLtr(chapters: List<com.novelfa.reader.model.Chapter>): Boolean {
    // Sample the first TEXT-bearing chapters, not blindly the first
    // three: a book whose first chapters are cover / TOC / image-only
    // (scanned PDF, illustrated EPUB) used to be judged from an empty
    // sample and always paginated RTL. Empty chapters are skipped,
    // up to 10 chapters are consulted, and a book with no letters at
    // all still falls back to RTL (the Persian-first default) — for a
    // purely image-only book there is simply no content signal.
    val sample = StringBuilder()
    var consulted = 0
    for (chapter in chapters) {
        if (consulted >= 10) break
        val text = runCatching {
            chapter.file.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                val buf = CharArray(4096)
                val n = reader.read(buf)
                if (n > 0) String(buf, 0, n) else ""
            }
        }.getOrNull() ?: continue
        val plain = HtmlText.htmlToPlainText(text)
        if (plain.isBlank()) continue
        consulted++
        sample.append(plain).append(' ')
        if (sample.length >= 8192) break
    }
    return HtmlText.detectDirection(sample.toString()) == 1
}

/** Zip-bomb guardrails for EPUB extraction (generous: real books are far smaller). */

/**
 * One reading-mode option in the mode picker popup: the mode's drawn
 * icon (IcoStack pages for horizontal, IcoScroll for vertical-continuous)
 * with its label underneath, highlighted when selected.
 */
@Composable
internal fun ReadingModeChoice(
    icon: AppIconKind,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp)
            )
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent,
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        AppIcon(
            icon,
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            size = 38.dp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Numeric font-size box between −A and +A — port of WPF txtFontSize
 * (MainWindow.xaml:101 + ApplyFontSize/CommitFontSizeText).
 *
 * Shows the current size; tap to type a new one. Windows parity:
 * - digits only (MaxLength=2); Persian/Arabic digits are accepted and
 *   normalized to English digits on commit (NormalizeDigits parity);
 * - Done/Enter commits, focus loss commits (LostFocus parity);
 * - invalid/empty input reverts to the current size;
 * - A+/A− presses update the box via [fontSize].
 *
 * The raw typed value reaches [onCommit] unclamped: the caller reports
 * an out-of-range value (10..72) to the user, and the ViewModel clamps
 * as a safety net. Clamping here would hide the limit hit again.
 */
@Composable
internal fun FontSizeBox(
    fontSize: Int,
    enabled: Boolean,
    onCommit: (Int) -> Unit
) {
    val focusManager = LocalFocusManager.current
    // Reset to the live size whenever it changes externally (A+/A−);
    // while the user types, fontSize doesn't change so their input is kept.
    var text by remember(fontSize) { mutableStateOf(fontSize.toString()) }
    var wasFocused by remember { mutableStateOf(false) }

    fun commit() {
        // NormalizeDigits parity: keep every Unicode digit, map to 0-9.
        val digits = text.filter { it.isDigit() }
            .map { Character.getNumericValue(it) }
            .joinToString("") { it.toString() }
        val value = digits.toIntOrNull()
        if (value != null) {
            onCommit(value)
        }
        // Revert the box to the current size (the remember(fontSize)
        // above refreshes it once the ViewModel reports the new size).
        text = fontSize.toString()
    }

    BasicTextField(
        value = text,
        onValueChange = { input ->
            // PreviewTextInput parity: digits only, MaxLength=2.
            text = input.filter { it.isDigit() }.take(2)
        },
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.labelLarge.copy(
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                commit()
                focusManager.clearFocus()
            }
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .width(44.dp)
            .onFocusChanged { state ->
                if (wasFocused && !state.isFocused) {
                    commit()
                }
                wasFocused = state.isFocused
            }
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(vertical = 6.dp)
    )
}

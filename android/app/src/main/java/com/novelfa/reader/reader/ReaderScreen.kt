package com.novelfa.reader.reader

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.novelfa.reader.R
import com.novelfa.reader.data.BookDao
import com.novelfa.reader.data.FontStore
import com.novelfa.reader.parser.EpubParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import androidx.compose.material3.rememberDrawerState

/**
 * Route `reader/{bookId}`: top bar (dir-aware chapter title + position
 * text), WebView, bottom bar (prev/next, continuous toggle, font size +/-,
 * spacing dialog, chapter list drawer). Errors surface via snackbar with
 * R.string messages (fa "خطا در نمایش فصل:\n" equivalents, Task 4 map).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    dao: BookDao,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var vm by remember { mutableStateOf<ReaderViewModel?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var pageHtml by remember { mutableStateOf("") }
    var pageBase by remember { mutableStateOf<String?>(null) }
    var rev by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<android.webkit.WebView?>(null) }
    var spacingOpen by remember { mutableStateOf(false) }

    val chapterErr = stringResource(R.string.couldn_t_display_the_chapter)
    val openErr = stringResource(R.string.couldn_t_open_the_book)

    suspend fun showVmError(v: ReaderViewModel) {
        snackbar.showSnackbar(chapterErr + (v.lastError ?: ""))
    }

    val bridge = remember {
        ScrollBridge { t, h, c ->
            scope.launch {
                val v = vm ?: return@launch
                val last = v.contLast
                val first = v.contFirst
                v.onScroll(t, h, c)
                if (v.contLast != last && v.lastChunkKind == "append") {
                    webView?.evaluateJavascript(
                        HtmlBuilder.appendJs(v.docGen, v.lastChunk ?: ""), null
                    )
                } else if (v.contFirst != first && v.lastChunkKind == "prepend") {
                    webView?.evaluateJavascript(
                        HtmlBuilder.prependJs(v.docGen, v.lastChunk ?: "", v.lastPrependDivider),
                        null
                    )
                }
                rev++
            }
        }
    }

    LaunchedEffect(bookId) {
        try {
            val entity = withContext(Dispatchers.IO) { dao.getById(bookId) }
                ?: throw IOException("book not found")
            val parsed = withContext(Dispatchers.IO) { openParsed(context, entity.filePath, bookId) }
            val store = FontStore(context)
            val files = withContext(Dispatchers.IO) {
                store.list().filter {
                    it.nameWithoutExtension.contains(entity.fontFamily, ignoreCase = true)
                }
            }
            val v = ReaderViewModel(
                dao, bookId, entity.copy(totalChapters = parsed.chapters.size),
                parsed.chapters, fontFiles = files
            )
            val target = entity.lastChapterIndex.coerceIn(0, parsed.chapters.size - 1)
            if (!v.loadChapter(target)) {
                throw IOException(v.lastError ?: "")
            }
            vm = v
            pageHtml = v.html
            pageBase = v.baseUrl
        } catch (e: Exception) {
            loadError = true
            snackbar.showSnackbar(openErr)
        }
    }

    suspend fun gotoChapter(v: ReaderViewModel, i: Int) {
        if (v.loadChapter(i)) {
            pageHtml = v.html
            pageBase = v.baseUrl
            rev++
        } else {
            showVmError(v)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    stringResource(R.string.chapters),
                    modifier = Modifier.padding(16.dp)
                )
                val v = vm
                if (v == null) {
                    Text(
                        stringResource(R.string.no_chapter_selected),
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    @Suppress("UNUSED_EXPRESSION")
                    rev
                    LazyColumn {
                        itemsIndexed(v.chapters, key = { _, c -> c.file.absolutePath }) { i, c ->
                            NavigationDrawerItem(
                                label = { Text(c.title) },
                                selected = i == v.index,
                                onClick = {
                                    scope.launch {
                                        gotoChapter(v, i)
                                        drawerState.close()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                val v = vm
                @Suppress("UNUSED_EXPRESSION")
                rev
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = v?.chapterTitle ?: "",
                                textAlign = if (v != null &&
                                    HtmlText.detectDirection(v.chapterTitle) == 1
                                ) {
                                    TextAlign.Left
                                } else {
                                    TextAlign.Right
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(text = v?.positionText ?: "")
                        }
                    },
                    navigationIcon = {
                        TextButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text("☰")
                        }
                    },
                    actions = {
                        TextButton(onClick = onClose) { Text("✕") }
                    }
                )
            },
            bottomBar = {
                val v = vm
                BottomAppBar {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val vv = vm ?: return@launch
                                    gotoChapter(vv, vv.index - 1)
                                }
                            },
                            enabled = v != null
                        ) { Text(stringResource(R.string.previous_chapter)) }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val vv = vm ?: return@launch
                                    gotoChapter(vv, vv.index + 1)
                                }
                            },
                            enabled = v != null
                        ) { Text(stringResource(R.string.next_chapter)) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            @Suppress("UNUSED_EXPRESSION")
                            rev
                            Text(stringResource(R.string.continuous_mode))
                            Switch(
                                checked = v?.continuous == true,
                                onCheckedChange = {
                                    val vv = vm ?: return@Switch
                                    vv.continuous = it
                                    rev++
                                }
                            )
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val vv = vm ?: return@launch
                                    vv.changeFontSize(-2)
                                    pageHtml = vv.html
                                    rev++
                                }
                            },
                            enabled = v != null
                        ) { Text("−A") }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val vv = vm ?: return@launch
                                    vv.changeFontSize(2)
                                    pageHtml = vv.html
                                    rev++
                                }
                            },
                            enabled = v != null
                        ) { Text("+A") }
                        TextButton(
                            onClick = { spacingOpen = true },
                            enabled = v != null
                        ) { Text("Aa") }
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                when {
                    loadError && vm == null -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(openErr)
                            TextButton(onClick = onClose) { Text("✕") }
                        }
                    }
                    pageHtml.isEmpty() -> CircularProgressIndicator()
                    else -> ReaderWebView(
                        html = pageHtml,
                        baseUrl = pageBase,
                        bridge = bridge,
                        onError = { details ->
                            scope.launch { snackbar.showSnackbar(chapterErr + details) }
                        },
                        onWebView = { webView = it }
                    )
                }
            }
        }
    }

    if (spacingOpen) {
        val v = vm
        if (v != null) {
            @Suppress("UNUSED_EXPRESSION")
            rev
            SpacingDialog(
                lineHeight = v.lineHeight.toFloat(),
                sideMargin = v.sideMargin,
                paragraphSpacing = v.paragraphSpacing.toFloat(),
                onDismiss = { spacingOpen = false },
                onApply = { lh, margin, para ->
                    scope.launch {
                        v.applySpacing(lh.toDouble(), margin, para.toDouble())
                        webView?.updateSpacingJs(v.spacingCss())
                        pageHtml = v.html
                        rev++
                        spacingOpen = false
                    }
                },
                onReset = {
                    scope.launch {
                        v.applySpacing(1.8, 24, 0.0)
                        webView?.updateSpacingJs(v.spacingCss())
                        pageHtml = v.html
                        rev++
                        spacingOpen = false
                    }
                }
            )
        }
    }
}

@Composable
private fun SpacingDialog(
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

@Throws(IOException::class)
private fun openParsed(
    context: Context,
    epubPath: String,
    bookId: String
): com.novelfa.reader.model.ParsedBook {
    val epub = File(epubPath)
    if (!epub.exists()) {
        throw IOException("epub missing: " + epub.absolutePath)
    }
    val outDir = File(context.cacheDir, "epub_open/" + bookId)
    outDir.mkdirs()
    // Re-extract when the epub changed since the last open.
    val stamp = File(outDir, ".src_stamp")
    val stampOk = stamp.exists() && stamp.readText() == epub.lastModified().toString()
    if (!stampOk) {
        outDir.deleteRecursively()
        outDir.mkdirs()
        ZipFile(epub).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val out = File(outDir, entry.name)
                val canonicalOut = out.canonicalPath
                val canonicalDir = outDir.canonicalPath
                if (!canonicalOut.startsWith(canonicalDir)) {
                    throw IOException("Zip entry outside target dir: " + entry.name)
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
        stamp.writeText(epub.lastModified().toString())
    }
    return EpubParser.parse(outDir, epub.name)
}

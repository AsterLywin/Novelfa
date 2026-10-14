package com.novelfa.reader.reader

import com.novelfa.reader.data.BookDao
import com.novelfa.reader.data.BookEntity
import com.novelfa.reader.model.Chapter
import com.novelfa.reader.theme.ReaderTheme
import com.novelfa.reader.theme.Themes
import java.io.File
import java.time.Instant

/**
 * Reader state machine ported from MainWindow.xaml.cs: LoadChapterContent
 * (636-662), SaveReadingProgress (627-634), AppendNextChapter (2062-2098),
 * PrependPreviousChapter (2101-2155), ScrollCheckTimer_Tick thresholds
 * (1978-2014: near-bottom st+ch >= sh-300 appends, else st <= 300 prepends),
 * spacing clamp ranges (1461-1463: 1.2-3.0 / 0-160 / 0-2.5) and the
 * position text "{0} از {1}" (2042).
 *
 * Continuous appends/prepends keep the same [docGen] (like the WPF JS
 * insert path); only [loadChapter] bumps it. [appendNext]/[prependPrev]
 * take the gen they were issued against so stale scroll events are ignored.
 */
class ReaderViewModel(
    private val dao: BookDao,
    private val bookId: String,
    initialBook: BookEntity,
    val chapters: List<Chapter>,
    var theme: ReaderTheme = Themes.DARK,
    var isEnglish: Boolean = false,
    var fontFiles: List<File> = emptyList()
) {
    private var book: BookEntity = initialBook

    var fontFamily: String = initialBook.fontFamily.ifBlank { "Tahoma" }
        private set
    var fontSize: Int = initialBook.fontSize.takeIf { it in 10..72 } ?: 18
        private set
    var lineHeight: Double
        private set
    var sideMargin: Int
        private set
    var paragraphSpacing: Double
        private set

    var index: Int = initialBook.lastChapterIndex
        private set
    var continuous: Boolean = false
    var html: String = ""
        private set
    var baseUrl: String = ""
        private set
    var docGen: Int = 0
        private set
    var contFirst: Int = 0
        private set
    var contLast: Int = 0
        private set
    var isAppending: Boolean = false
        private set
    var lastError: String? = null
        private set

    /** Chunk + divider of the latest append/prepend for the WebView JS path. */
    var lastChunk: String? = null
        private set
    var lastChunkKind: String = "full"
        private set
    var lastPrependDivider: String = ""
        private set

    val positionText: String
        get() = "${index + 1} از ${chapters.size}"

    val chapterTitle: String
        get() = chapters.getOrNull(index)?.title ?: ""

    companion object {
        /**
         * Rendered-window cap for continuous mode (Task 9): unbounded appends
         * grow the WebView DOM until it ANRs on long books. Overflow drops the
         * far end and rebuilds [html] from the window with [lastChunkKind]
         * "full" so the WebView reloads instead of appending.
         */
        const val MAX_WINDOW_SPAN = 7
    }

    private var lastSaveMs: Long = 0L

    init {
        if (initialBook.lineHeight <= 0.0) {
            lineHeight = 1.8
            sideMargin = 24
            paragraphSpacing = 0.0
        } else {
            lineHeight = initialBook.lineHeight.coerceIn(1.2, 3.0)
            sideMargin = initialBook.sideMargin.coerceIn(0, 160)
            paragraphSpacing = initialBook.paragraphSpacing.coerceIn(0.0, 2.5)
        }
        if (chapters.isEmpty()) {
            index = 0
        } else {
            index = initialBook.lastChapterIndex.coerceIn(0, chapters.size - 1)
        }
        contFirst = index
        contLast = index
    }

    suspend fun loadChapter(i: Int): Boolean {
        if (i < 0 || i >= chapters.size) {
            return false
        }
        return try {
            val body = readPrepared(i)
            val wrapped = "<div class=\"rdr-chap\" data-idx=\"" + i + "\"" +
                HtmlText.chapterDirAttr(body) + ">" + body + "</div>"
            docGen++
            contFirst = i
            contLast = i
            index = i
            baseUrl = baseUrl(i)
            html = HtmlBuilder.build(
                baseUrl, wrapped, theme, fontFamily, fontSize,
                lineHeight, sideMargin, paragraphSpacing, isEnglish,
                HtmlBuilder.fontFaceCss(fontFamily, fontFiles), docGen
            )
            lastChunk = null
            lastChunkKind = "full"
            lastError = null
            saveProgress(i, force = true)
            true
        } catch (e: Exception) {
            lastError = e.message
            false
        }
    }

    suspend fun appendNext(expectedGen: Int = docGen): Boolean {
        if (isAppending) {
            return false
        }
        val next = contLast + 1
        if (next >= chapters.size) {
            return false
        }
        if (expectedGen != docGen) {
            return false
        }
        isAppending = true
        try {
            val body = readPrepared(next)
            val chunk = "<div class=\"rdr-chap\" data-idx=\"" + next + "\"" +
                HtmlText.chapterDirAttr(body) + ">" +
                ChapterDivider.divider(chapters[next].title, theme) + body + "</div>"
            html = html.replace("</body>", chunk + "</body>")
            contLast = next
            lastChunk = chunk
            lastChunkKind = "append"
            lastError = null
            trimWindow(keepTail = true)
            return true
        } catch (e: Exception) {
            lastError = e.message
            return false
        } finally {
            isAppending = false
        }
    }

    suspend fun prependPrev(expectedGen: Int = docGen): Boolean {
        if (isAppending) {
            return false
        }
        val prev = contFirst - 1
        if (prev < 0 || contFirst >= chapters.size) {
            return false
        }
        if (expectedGen != docGen) {
            return false
        }
        isAppending = true
        try {
            val body = readPrepared(prev)
            val chunk = "<div class=\"rdr-chap\" data-idx=\"" + prev + "\"" +
                HtmlText.chapterDirAttr(body) + ">" + body + "</div>"
            val divider = ChapterDivider.divider(chapters[contFirst].title, theme)
            html = html.replace("<body>", "<body>" + chunk + divider)
            contFirst = prev
            lastChunk = chunk
            lastPrependDivider = divider
            lastChunkKind = "prepend"
            lastError = null
            trimWindow(keepTail = false)
            return true
        } catch (e: Exception) {
            lastError = e.message
            return false
        } finally {
            isAppending = false
        }
    }

    /**
     * Caps the rendered window at [MAX_WINDOW_SPAN] chapters. [keepTail] is
     * true when appending (user scrolls down: drop from the top) and false
     * when prepending (drop from the bottom). Rebuilds [html] from the window
     * so the WebView reloads ([lastChunkKind] "full") instead of growing the
     * DOM forever. No-op while the span fits.
     */
    private fun trimWindow(keepTail: Boolean) {
        if (contLast - contFirst + 1 <= MAX_WINDOW_SPAN) return
        if (keepTail) {
            contFirst = contLast - MAX_WINDOW_SPAN + 1
        } else {
            contLast = contFirst + MAX_WINDOW_SPAN - 1
        }
        html = rebuildWindowHtml()
        lastChunk = null
        lastPrependDivider = ""
        lastChunkKind = "full"
    }

    /**
     * Rebuilds the full page from [contFirst]..[contLast] with the same
     * wrapping/divider conventions as [loadChapter]/[appendNext]: the first
     * window chapter is plain, later ones carry their own-title divider.
     */
    private fun rebuildWindowHtml(): String {
        val sb = StringBuilder()
        for (i in contFirst..contLast) {
            val body = readPrepared(i)
            val inner = if (i == contFirst) {
                body
            } else {
                ChapterDivider.divider(chapters[i].title, theme) + body
            }
            sb.append("<div class=\"rdr-chap\" data-idx=\"").append(i).append("\"")
                .append(HtmlText.chapterDirAttr(body)).append(">").append(inner).append("</div>")
        }
        return HtmlBuilder.build(
            baseUrl, sb.toString(), theme, fontFamily, fontSize,
            lineHeight, sideMargin, paragraphSpacing, isEnglish,
            HtmlBuilder.fontFaceCss(fontFamily, fontFiles), docGen
        )
    }

    /**
     * Scroll-bridge entry: near-bottom (top+client >= height-300) appends,
     * else top == 0 prepends. Only in continuous mode.
     */
    suspend fun onScroll(top: Int, height: Int, client: Int) {        if (!continuous || isAppending) {
            return
        }
        if (top + client >= height - 300) {
            appendNext()
        } else if (top == 0) {
            prependPrev()
        }
    }

    suspend fun saveProgress(i: Int, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveMs < 500) {
            return
        }
        lastSaveMs = now
        val target = if (i in chapters.indices) i else book.lastChapterIndex
        book = book.copy(lastChapterIndex = target, lastOpenedDate = Instant.now().toString())
        dao.update(book)
    }

    suspend fun applySpacing(lh: Double, margin: Int, para: Double) {
        lineHeight = lh.coerceIn(1.2, 3.0)
        sideMargin = margin.coerceIn(0, 160)
        paragraphSpacing = para.coerceIn(0.0, 2.5)
        book = book.copy(
            lineHeight = lineHeight,
            sideMargin = sideMargin,
            paragraphSpacing = paragraphSpacing
        )
        dao.update(book)
        refreshSpacing()
    }

    suspend fun changeFontSize(delta: Int) {
        val next = (fontSize + delta).coerceIn(10, 72)
        if (next == fontSize) {
            return
        }
        fontSize = next
        book = book.copy(fontSize = next)
        dao.update(book)
        if (html.isNotEmpty()) {
            html = Regex("font-size:\\d+px").replaceFirst(html, "font-size:" + next + "px")
        }
    }

    fun spacingCss(): String =
        HtmlBuilder.spacingCss(lineHeight, sideMargin, paragraphSpacing)

    private fun refreshSpacing() {
        if (html.isEmpty()) {
            return
        }
        html = Regex("<style id=\"readerSpacing\">[\\s\\S]*?</style>")
            .replace(html, "<style id=\"readerSpacing\">" + spacingCss() + "</style>")
    }

    private fun readPrepared(i: Int): String {
        val file = chapters[i].file
        var raw = file.inputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (raw.isNotEmpty() && raw[0] == 0xFEFF.toChar()) {
            raw = raw.substring(1)
        }
        return HtmlText.prepareChapterBody(raw, file.parentFile)
    }

    private fun baseUrl(i: Int): String =
        chapters[i].file.parentFile?.toURI()?.toString() ?: ""
}

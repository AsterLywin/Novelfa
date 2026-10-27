package com.novelfa.reader.reader

import com.novelfa.reader.data.BookDao
import com.novelfa.reader.data.BookEntity
import com.novelfa.reader.model.Chapter
import com.novelfa.reader.parser.HrefResolver
import com.novelfa.reader.theme.ReaderTheme
import com.novelfa.reader.theme.Themes
import com.novelfa.reader.theme.XpRank
import com.novelfa.reader.theme.XpSessionTracker
import com.novelfa.reader.theme.CharacterThemes
import com.novelfa.reader.theme.characterPages
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

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

/**
 * Reading layout mode. Replaces the old continuous on/off toggle:
 * - [PAGED_HORIZONTAL]: one chapter, horizontal page turns (the manga-reader
 *   style). Page-turn direction follows the book language (RTL books turn
 *   right-to-left, LTR books left-to-right).
 * - [CONTINUOUS_VERTICAL]: the classic stitched vertical scroll.
 */
enum class ReadingMode(val key: String) {
    PAGED_HORIZONTAL("paged"),
    CONTINUOUS_VERTICAL("continuous");

    companion object {
        fun fromKey(key: String?): ReadingMode =
            entries.firstOrNull { it.key == key } ?: PAGED_HORIZONTAL
    }
}

class ReaderViewModel(
    private val dao: BookDao,
    private val bookId: String,
    initialBook: BookEntity,
    val chapters: List<Chapter>,
    var theme: ReaderTheme = Themes.DARK,
    var isEnglish: Boolean = false,
    var fontFiles: List<File> = emptyList(),
    /**
     * Bundled fallback font files (Vazirmatn). Their faces are emitted
     * under [HtmlBuilder.FALLBACK_FAMILY] so the reader font stack never
     * falls through to the phone's own system font: a user-installed
     * phone font must not re-shape reader layout or glyphs.
     */
    var fallbackFontFiles: List<File> = emptyList(),
    /** XP clock (injectable for tests); default is wall-clock time. */
    private val xpClock: () -> Long = { System.currentTimeMillis() },
    /**
     * Called when a unit becomes XP-creditable (reader left it after
     * >= 5 s dwell, see XpSessionTracker): (unitIndex, wordCount).
     * Null = XP disabled (tests, or private books — the caller in
     * ReaderScreen passes null for private books). The callback owns
     * persistence via XpStore; failures there must never break reading.
     */
    private val onXpUnitFinished: (suspend (Int, Int) -> Unit)? = null
) {
    /** XP anti-cheat tracker: visited-only, >= 5 s dwell, once per unit. */
    private val xpTracker = XpSessionTracker(xpClock)

    /** Note entry into [unit]; credits the previous unit if it qualified. */
    private suspend fun xpNoteEnter(unit: Int) {
        val toCredit = xpTracker.enter(unit) ?: return
        xpCreditUnit(toCredit)
    }

    private suspend fun xpCreditUnit(unit: Int) {
        val cb = onXpUnitFinished ?: return
        if (unit !in chapters.indices) return
        val words = withContext(Dispatchers.IO) {
            runCatching { XpRank.wordsFromHtml(readPrepared(unit)) }.getOrDefault(0)
        }
        runCatching { cb(unit, words) }
    }

    /** Credit the current unit on reader close, if its dwell qualified. */
    suspend fun xpFlush() {
        val toCredit = xpTracker.flush() ?: return
        xpCreditUnit(toCredit)
    }
    private var book: BookEntity = initialBook

    /** In-memory cache of prepared chapter bodies (trimWindow rebuilds). */
    private val preparedCache = LinkedHashMap<Int, String>()

    // Tahoma is gone from the Android app (it does not exist on Android
    // and only ever fell through to the phone's system font): a blank
    // or legacy-Tahoma family renders with the bundled NovelfaFallback
    // face from the CSS stack instead.
    var fontFamily: String =
        initialBook.fontFamily.takeIf { it.isNotBlank() && !it.equals("Tahoma", ignoreCase = true) } ?: ""
        private set
    // Observable so the bottom-bar FontSizeBox recomposes when A+/A- or
    // the numeric box changes it (a plain var never notifies Compose).
    var fontSize by mutableIntStateOf(initialBook.fontSize.takeIf { it in 10..72 } ?: 18)
        private set
    var lineHeight: Double
        private set
    var sideMargin: Int
        private set
    var paragraphSpacing: Double
        private set

    /**
     * Cached @font-face CSS: fontFaceCss() does multi-MB disk reads + base64
     * on every call; the files don't change mid-session, so cache by
     * (family, file list). Invalidated when the font changes.
     */
    private var fontFaceCacheKey: Triple<String, List<File>, List<File>>? = null
    private var fontFaceCacheValue: String = ""

    private fun cachedFontFaceCss(): String {
        val key = Triple(fontFamily, fontFiles, fallbackFontFiles)
        if (key != fontFaceCacheKey) {
            fontFaceCacheValue = HtmlBuilder.fontFaceCss(fontFamily, fontFiles) +
                HtmlBuilder.fontFaceCss(HtmlBuilder.FALLBACK_FAMILY, fallbackFontFiles)
            fontFaceCacheKey = key
        }
        return fontFaceCacheValue
    }

    var index: Int = initialBook.lastChapterIndex
        private set
    /**
     * Paged-mode text-point anchor ("li:tok", see rdrCurrentAnchor)
     * for the chapter in [pagedAnchorChapter]. Together with
     * [BookEntity.lastAnchor] this is the ReadEra-XPath idea in the
     * pager's own coordinates: a stable text point, not a page
     * number, so reopening, a settings reload or a mode switch
     * lands on the same sentence even after repagination changes
     * every page boundary. Seeded from the entity at open; cleared
     * by [clearPagedAnchor] on a chapter change. Vertical mode does
     * not use it (its position is a scroll offset in a stitched
     * window, not a paged fragment).
     */
    var pagedAnchor: String? = null
    var pagedAnchorChapter: Int = -1
    /** Current reading layout (replaces the old continuous on/off flag). */
    var readingMode: ReadingMode = ReadingMode.PAGED_HORIZONTAL

    /** True when the page is laid out as horizontal pages. */
    fun isPaged(): Boolean = readingMode == ReadingMode.PAGED_HORIZONTAL
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
    // Observable (not a plain var) so the UI can show a loading bar
    // while a chapter is being stitched in.
    var isAppending by mutableStateOf(false)
        private set
    private val appendMutex = Mutex()
    var lastError: String? = null
        private set

    /** Chunk + divider of the latest append/prepend for the WebView JS path. */
    var lastChunk: String? = null
        private set
    var lastChunkKind: String = "full"
        private set
    var lastPrependDivider: String = ""
        private set
    /**
     * Index range dropped by the last [trimWindow] (null when nothing was
     * trimmed). The UI removes these divs from the WebView DOM via
     * [HtmlBuilder.dropJs] so no full reload (and no scroll jump) is needed.
     */
    var lastDropped: IntRange? = null
        private set

    fun clearDropped() {
        lastDropped = null
    }

    companion object {
        /**
         * Rendered-window cap for continuous mode (Task 9): unbounded appends
         * grow the WebView DOM until it ANRs on long books. Overflow drops the
         * far end and rebuilds [html] from the window with [lastChunkKind]
         * "full" so the WebView reloads instead of appending.
         */
        const val MAX_WINDOW_SPAN = 7

        /**
         * Find a reader theme by key: first in Themes.ALL (static), then in
         * characterPages() (rz.*). Port of MainWindow.FindTheme.
         */
        fun findTheme(key: String): ReaderTheme? {
            Themes.ALL.firstOrNull { it.key == key }?.let { return it }
            return characterPages().firstOrNull { it.key == key }
        }

        /**
         * Resolve the theme for a book, port of MainWindow.GetThemeForBook:
         * dark/white become the character's night/day page when a character
         * theme is active; rz.* without a character falls back to dark/white.
         */
        fun themeForBook(
            savedKey: String?,
            charThemeId: String?,
            isLight: Boolean
        ): ReaderTheme {
            var key = savedKey?.takeIf { it.isNotBlank() }
                ?: if (isLight) "white" else "dark"
            val ch = charThemeId?.takeIf { it.isNotBlank() }?.let { CharacterThemes.find(it) }
            if (ch != null) {
                if (key == "dark" || (key.startsWith("rz.") && key.endsWith(".night"))) {
                    key = ch.pageKeyNight
                } else if (key == "white" || (key.startsWith("rz.") && key.endsWith(".day"))) {
                    key = ch.pageKeyDay
                }
            } else if (key.startsWith("rz.")) {
                key = if (key.endsWith(".night")) "dark" else "white"
            }
            return findTheme(key) ?: Themes.DARK
        }

        /**
         * Display name for a reader theme in the UI language: static themes
         * use Themes.NAMES_FA / NAMES_EN; character pages get "شبِ X" /
         * "روزِ X" (fa) or "X Night" / "X Day" (en).
         */
        fun themeDisplayName(key: String, isEnglish: Boolean): String {
            (if (isEnglish) Themes.NAMES_EN[key] else Themes.NAMES_FA[key])
                ?.let { return it }
            if (key.startsWith("rz.")) {
                val id = key.removePrefix("rz.").substringBefore('.')
                val theme = CharacterThemes.find(id)
                val name = theme?.displayName(isEnglish) ?: id
                return if (isEnglish) {
                    if (key.endsWith(".night")) "$name Night" else "$name Day"
                } else {
                    val suffix = if (key.endsWith(".night")) "شب" else "روز"
                    "$suffixِ $name"
                }
            }
            return key
        }
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
            val body = withContext(Dispatchers.IO) { readPrepared(i) }
            val wrapped = "<div class=\"rdr-chap\" data-idx=\"" + i + "\"" +
                HtmlText.chapterDirAttr(body) + ">" + body + "</div>"
            docGen++
            contFirst = i
            contLast = i
            index = i
            // XP: entering a new unit may credit the previous one (dwell-gated).
            xpNoteEnter(i)
            baseUrl = baseUrl(i)
            html = HtmlBuilder.build(
                baseUrl, wrapped, theme, fontFamily, fontSize,
                lineHeight, sideMargin, paragraphSpacing, isEnglish,
                cachedFontFaceCss(), docGen,
                paged = isPaged()
            )
            lastChunk = null
            lastChunkKind = "full"
            lastError = null
            saveProgress(i, force = true)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e.message
            false
        }
    }

    suspend fun appendNext(expectedGen: Int = docGen): Boolean {
        // The JS scroll bridge fires on a WebView background thread; two rapid
        // scroll events could both pass a plain Boolean check and double-append.
        if (!appendMutex.tryLock()) {
            return false
        }
        // The mutex MUST be released on every path: the bounds/gen early
        // returns below used to skip the inner finally and leak the lock,
        // permanently killing continuous mode for the session.
        try {
            val next = contLast + 1
            if (next >= chapters.size) {
                return false
            }
            if (expectedGen != docGen) {
                return false
            }
            isAppending = true
            try {
                val body = withContext(Dispatchers.IO) { readPrepared(next) }
                // The gen may have changed while the chapter was read
                // (drawer jump / theme change bumps docGen and reloads):
                // applying the chunk to the new page would desync the
                // ViewModel window from the DOM.
                if (expectedGen != docGen) {
                    return false
                }
                val chunk = "<div class=\"rdr-chap\" data-idx=\"" + next + "\"" +
                    HtmlText.chapterDirAttr(body) + ">" +
                    ChapterDivider.divider(chapters[next].title, theme) + body + "</div>"
                html = html.replace("</body>", chunk + "</body>")
                contLast = next
                // NOTE: index/progress deliberately NOT touched here —
                // index is the chapter being READ (reported by the page
                // via onChapterVisible), not the newest stitched one.
                lastChunk = chunk
                lastChunkKind = "append"
                lastError = null
                trimWindow(keepTail = true)
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message
                return false
            } finally {
                isAppending = false
            }
        } finally {
            appendMutex.unlock()
        }
    }

    suspend fun prependPrev(expectedGen: Int = docGen): Boolean {
        // Same race as appendNext (see above): guard with the mutex, not a Boolean.
        if (!appendMutex.tryLock()) {
            return false
        }
        // See appendNext: the lock must be released on every path, including
        // the bounds/gen early returns.
        try {
            val prev = contFirst - 1
            if (prev < 0 || contFirst >= chapters.size) {
                return false
            }
            if (expectedGen != docGen) {
                return false
            }
            isAppending = true
            try {
                val body = withContext(Dispatchers.IO) { readPrepared(prev) }
                // Same post-IO gen re-check as appendNext.
                if (expectedGen != docGen) {
                    return false
                }
                val chunk = "<div class=\"rdr-chap\" data-idx=\"" + prev + "\"" +
                    HtmlText.chapterDirAttr(body) + ">" + body + "</div>"
                val divider = ChapterDivider.divider(chapters[contFirst].title, theme)
                html = html.replace("<body>", "<body>" + chunk + divider)
                contFirst = prev
                // index/progress untouched — see appendNext.
                lastChunk = chunk
                lastPrependDivider = divider
                lastChunkKind = "prepend"
                lastError = null
                trimWindow(keepTail = false)
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message
                return false
            } finally {
                isAppending = false
            }
        } finally {
            appendMutex.unlock()
        }
    }

    /**
     * Caps the rendered window at [MAX_WINDOW_SPAN] chapters. [keepTail] is
     * true when appending (user scrolls down: drop from the top) and false
     * when prepending (drop from the bottom). [html] is rebuilt from the
     * surviving window so it stays in sync with the DOM, and the dropped
     * index range is published in [lastDropped] for the UI to remove via
     * [HtmlBuilder.dropJs] — no full reload, no scroll jump. [lastChunkKind]
     * is intentionally left as "append"/"prepend" so the incremental JS path
     * (chunk + drop) runs. No-op while the span fits.
     */
    private suspend fun trimWindow(keepTail: Boolean) {
        lastDropped = null
        if (contLast - contFirst + 1 <= MAX_WINDOW_SPAN) return
        if (keepTail) {
            val newFirst = contLast - MAX_WINDOW_SPAN + 1
            lastDropped = contFirst..newFirst - 1
            contFirst = newFirst
        } else {
            val newLast = contFirst + MAX_WINDOW_SPAN - 1
            lastDropped = newLast + 1..contLast
            contLast = newLast
        }
        // Rebuilding re-reads up to MAX_WINDOW_SPAN chapter files: keep the
        // blocking disk reads off the caller's (UI) thread.
        html = withContext(Dispatchers.IO) { rebuildWindowHtml() }
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
            cachedFontFaceCss(), docGen,
            paged = false // rebuildWindowHtml only serves continuous mode
        )
    }

    /**
     * Scroll-bridge entry. Only in [ReadingMode.CONTINUOUS_VERTICAL];
     * horizontal paging never stitches.
     *
     * Deliberately NOT the WPF thresholds (300px / top == 0) anymore:
     * on Android the next chapter is read from disk and inserted via
     * JS after the trigger, so a 300px trigger meant the user reached
     * the end before the chapter existed — the "next page never
     * arrives" wall. Appending from 1200px out and prepending from
     * 300px in gives the stitch time to land before it is on screen.
     * The window cap ([MAX_WINDOW_SPAN]) still bounds how far this
     * prefetching can grow the document.
     */
    suspend fun onScroll(top: Int, height: Int, client: Int) {
        if (readingMode != ReadingMode.CONTINUOUS_VERTICAL || isAppending) {
            return
        }
        if (top + client >= height - 1200) {
            appendNext()
        } else if (top <= 300) {
            prependPrev()
        }
    }

    /**
     * The chapter at the viewport top, reported by the page's scroll
     * reporter (Android.onChapter). [index] is the chapter being READ —
     * the top bar, the chapter drawer and the saved progress all follow
     * it. Appends/prepends only stitch the window around the reading
     * position; they used to move [index] to the freshly stitched
     * chapter, so all three pointed one chapter ahead of (or behind)
     * what was actually on screen, and the book reopened at the wrong
     * chapter.
     */
    suspend fun onChapterVisible(ci: Int) {
        if (ci !in chapters.indices || ci == index) {
            return
        }
        index = ci
        saveProgress(ci)
        // XP: same dwell-gated credit as loadChapter (continuous mode).
        xpNoteEnter(ci)
    }

    suspend fun saveProgress(i: Int, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveMs < 500) {
            return
        }
        lastSaveMs = now
        val target = if (i in chapters.indices) i else book.lastChapterIndex
        // The anchor belongs to exactly one chapter: it is saved
        // only with that chapter's index, and cleared on any other
        // chapter — otherwise a stale anchor would land the reader
        // in the wrong chapter's text point.
        val anchor = if (target == pagedAnchorChapter) pagedAnchor else null
        book = book.copy(
            lastChapterIndex = target,
            lastOpenedDate = Instant.now().toString(),
            lastAnchor = anchor
        )
        dao.update(book)
    }

    /**
     * Records the current paged page's text anchor, throttled through
     * [saveProgress] (500ms): rapid turns save the first anchor and
     * the next one after the window, so the DB is never more than a
     * few pages stale, and a turn never costs a DB write each time.
     * Only meaningful in paged mode; vertical callers must not call
     * it (their chapter changes go through saveProgress, which
     * clears the anchor for the new chapter).
     */
    suspend fun notePagedAnchor(anchor: String) {
        if (readingMode != ReadingMode.PAGED_HORIZONTAL) return
        if (!anchor.matches(Regex("^\\d+:\\d+$"))) return
        pagedAnchor = anchor
        pagedAnchorChapter = index
        saveProgress(index)
    }

    /**
     * Forces the current paged anchor to the DB, bypassing the 500ms
     * throttle: called on close / dispose, so a turn followed
     * immediately by closing the reader no longer loses the last page.
     * A no-op outside paged mode or with no anchor recorded.
     */
    suspend fun flushPagedAnchor() {
        if (readingMode != ReadingMode.PAGED_HORIZONTAL) return
        if (pagedAnchor == null) return
        saveProgress(index, force = true)
    }

    /** Clears the in-memory anchor (chapter change): the next save writes null. */
    fun clearPagedAnchor() {
        pagedAnchor = null
        pagedAnchorChapter = -1
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
        setFontSize(fontSize + delta)
    }

    /**
     * Absolute font-size set — port of WPF ApplyFontSize: clamps to
     * 10..72, no-op when unchanged, live-patches the loaded HTML and
     * persists to the book row.
     */
    suspend fun setFontSize(size: Int) {
        val next = size.coerceIn(10, 72)
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

    /**
     * Per-book font family (reader top-bar picker). Persists to the entity
     * and rebuilds the current chapter so @font-face + family apply.
     */
    suspend fun changeFontFamily(family: String, files: List<File>): Boolean {
        val clean = family.trim()
        if (clean.isEmpty()) return false
        fontFamily = clean
        fontFiles = files
        book = book.copy(fontFamily = clean)
        dao.update(book)
        return if (html.isNotEmpty()) reloadForSettings() else true
    }

    /**
     * Rebuilds the current view after a theme/font change without losing
     * the reading position: in [ReadingMode.CONTINUOUS_VERTICAL] with a
     * multi-chapter window, the whole window is rebuilt; otherwise the
     * single current chapter is rebuilt. The caller must push [html] to the
     * WebView (full reload); [lastChunkKind] is "full" afterwards.
     */
    suspend fun reloadForSettings(): Boolean {
        if (html.isEmpty()) return true
        return try {
            docGen++
            html = withContext(Dispatchers.IO) {
                if (readingMode == ReadingMode.CONTINUOUS_VERTICAL && contLast > contFirst) {
                    rebuildWindowHtml()
                } else {
                    val body = readPrepared(index)
                    val wrapped = "<div class=\"rdr-chap\" data-idx=\"" + index + "\"" +
                        HtmlText.chapterDirAttr(body) + ">" + body + "</div>"
                    baseUrl = baseUrl(index)
                    HtmlBuilder.build(
                        baseUrl, wrapped, theme, fontFamily, fontSize,
                        lineHeight, sideMargin, paragraphSpacing, isEnglish,
                        HtmlBuilder.fontFaceCss(fontFamily, fontFiles), docGen,
                        paged = isPaged()
                    )
                }
            }
            lastChunk = null
            lastDropped = null
            lastChunkKind = "full"
            lastError = null
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e.message
            false
        }
    }

    /**
     * Reader theme switch (reader top-bar menu). Rebuilds the current
     * view so background/text/link colors apply (keeps the continuous
     * window when active).
     */
    suspend fun applyTheme(t: ReaderTheme): Boolean {
        theme = t
        return if (html.isNotEmpty()) reloadForSettings() else true
    }


    fun spacingCss(): String =
        HtmlBuilder.spacingCss(lineHeight, sideMargin, paragraphSpacing)

    /** Current paged-mode CSS (side margin on .rdr-page) for live swaps. */
    fun pagedCss(): String =
        HtmlBuilder.pagedCss(sideMargin)

    private fun refreshSpacing() {
        if (html.isEmpty()) {
            return
        }
        html = Regex("<style id=\"readerSpacing\">[\\s\\S]*?</style>")
            .replace(html, "<style id=\"readerSpacing\">" + spacingCss() + "</style>")
        // Paged mode bakes the side margin into #rdrPaged (on .rdr-chap, so
        // columns stay exactly 100vw); rebuild it too or the margin change
        // silently does nothing after the live #readerSpacing swap.
        if (html.contains("id=\"rdrPaged\"")) {
            html = Regex("<style id=\"rdrPaged\">[\\s\\S]*?</style>")
                .replace(html, "<style id=\"rdrPaged\">" + HtmlBuilder.pagedCss(sideMargin) + "</style>")
        }
    }

    private fun readPrepared(i: Int): String {
        preparedCache[i]?.let { return it }
        val file = chapters[i].file
        var raw = file.inputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (raw.isNotEmpty() && raw[0] == 0xFEFF.toChar()) {
            raw = raw.substring(1)
        }
        val prepared = HtmlText.prepareChapterBody(raw, file.parentFile)
        // LRU: keep the window + neighbors; drop oldest beyond 12.
        if (preparedCache.size >= 12) {
            val oldest = preparedCache.keys.firstOrNull { it < contFirst || it > contLast }
                ?: preparedCache.keys.firstOrNull()
            if (oldest != null) preparedCache.remove(oldest)
        }
        preparedCache[i] = prepared
        return prepared
    }

    /**
     * Resolves an in-book link href (exactly as authored in the chapter
     * HTML) to the spine chapter it points at, plus its fragment id.
     * EPUB hrefs are relative to the folder of the chapter CONTAINING
     * the link ([baseIdx], reported by the LINK_JS interceptor — in
     * continuous mode several chapters share one document). Returns
     * null when the target file is not one of the book's spine chapters
     * (external files, cover pages outside the spine, bad hrefs) — such
     * taps are ignored. The fragment is percent-decoded like file hrefs.
     */
    suspend fun resolveLink(href: String, baseIdx: Int): Pair<Int, String>? =
        withContext(Dispatchers.IO) {
            val filePart = href.substringBefore('#').substringBefore('?').trim()
            val fragRaw = href.substringAfter('#', "").trim()
            val frag = runCatching {
                java.net.URLDecoder.decode(fragRaw, "UTF-8")
            }.getOrDefault(fragRaw)
            // A fragment-only href ("#footnote-3", the usual footnote
            // and in-chapter anchor form) points at the chapter that
            // CONTAINS the link. The old code returned null here, so
            // every same-chapter anchor link was dead on tap.
            if (filePart.isEmpty()) {
                if (frag.isBlank()) return@withContext null
                val base = if (baseIdx in chapters.indices) baseIdx else index
                return@withContext base to frag
            }
            val base = if (baseIdx in chapters.indices) baseIdx else index
            val folder = chapters[base].file.parentFile
                ?: return@withContext null
            val target = runCatching {
                HrefResolver.resolve(folder, filePart).canonicalPath
            }.getOrNull() ?: return@withContext null
            val idx = chapters.indexOfFirst {
                runCatching { it.file.canonicalPath }.getOrNull() == target
            }
            if (idx < 0) null else idx to frag
        }

    private fun baseUrl(i: Int): String =
        chapters[i].file.parentFile?.toURI()?.toString() ?: ""
}

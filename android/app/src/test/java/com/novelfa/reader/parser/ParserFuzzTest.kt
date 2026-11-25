package com.novelfa.reader.parser

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * Proactive parser fuzz (standing rule, 2026-11-25): the parsers must
 * never crash the app on hostile input — no Throwable may escape
 * parseMarkdown/parseText, whatever the file looks like. Every case
 * is generated from a FIXED seed, so a failure reproduces exactly:
 * re-run with the printed seed + case index.
 *
 * The deep-nesting guard is the only sanctioned exception: an input
 * whose nesting is so deep the parse thread's stack overflows must
 * come back as IllegalStateException("...deeply nested..."), never
 * as a StackOverflowError escaping into the importer (which would
 * crash the app — the md copy crash of 2026-11-25). The guard may
 * only fire on genuinely deep input (tracked depth >= 50).
 */
class ParserFuzzTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun fallback(n: Int) = "Section $n"

    private val words = listOf(
        "داستان", "کتاب", "فصل", "متن", "پاراگراف", "رمان", "نویسنده",
        "story", "chapter", "book", "text", "paragraph", "novel", "author",
        "۱۲۳", "۴۵۶", "hello", "world", "؛", "،", "؟", "ـ"
    )

    private fun Random.word() = words.random(this)
    private fun Random.sentence(): String =
        (1..(3 + nextInt(8))).joinToString(" ") { word() } + "."

    private fun Random.paragraph(): String =
        (1..(1 + nextInt(4))).joinToString(" ") { sentence() }

    /**
     * Generates one Markdown document. Returns the text plus the
     * maximum nesting depth used (blockquote/list), so the test can
     * tell a legitimately guarded deep input from a guard misfire.
     */
    private fun Random.markdown(maxDepth: Int = 8): Pair<String, Int> {
        val sb = StringBuilder()
        var depth = 0
        if (nextInt(5) == 0) sb.append("\uFEFF")
        if (nextInt(4) == 0) sb.append("---\ntitle: fuzz\n---\n\n")
        val blocks = 3 + nextInt(12)
        repeat(blocks) {
            when (nextInt(12)) {
                0 -> sb.append("#".repeat(1 + nextInt(3))).append(' ').append(sentence()).append("\n\n")
                1 -> sb.append("#".repeat(1 + nextInt(6))).append("\n\n")
                2 -> sb.append(paragraph()).append("\n\n")
                3 -> {
                    // Nested blockquote.
                    val d = 1 + nextInt(maxDepth)
                    depth = maxOf(depth, d)
                    sb.append("> ".repeat(d)).append(paragraph()).append("\n\n")
                }
                4 -> {
                    // Nested list.
                    val d = 1 + nextInt(maxDepth)
                    depth = maxOf(depth, d)
                    repeat(1 + nextInt(4)) {
                        sb.append("  ".repeat(d - 1)).append("- ").append(sentence()).append('\n')
                    }
                    sb.append('\n')
                }
                5 -> {
                    sb.append("```\n").append(paragraph()).append('\n')
                    if (nextInt(4) != 0) sb.append("```\n")
                    sb.append('\n')
                }
                6 -> {
                    sb.append("| a | b |\n|---|---|\n| ").append(word())
                        .append(" | ").append(word()).append(" |\n\n")
                }
                7 -> sb.append("[").append(word()).append("](https://example.com) ")
                    .append(paragraph()).append("\n\n")
                8 -> sb.append("متن با پانویس[^1]\n\n[^1]: ").append(sentence()).append("\n\n")
                9 -> sb.append("---\n\n")
                10 -> sb.append("![alt](img.png)\n\n")
                else -> sb.append('\n')
            }
        }
        var text = sb.toString()
        if (nextInt(4) == 0) text = text.replace("\n", "\r\n")
        return text to depth
    }

    private fun Random.plainText(): String {
        val sb = StringBuilder()
        if (nextInt(5) == 0) sb.append("\uFEFF")
        repeat(3 + nextInt(20)) {
            when (nextInt(6)) {
                0 -> sb.append(sentence()).append("\n\n")
                1 -> sb.append('\n')
                2 -> sb.append(word()).append('\n')
                3 -> sb.append("فصل ").append((1 + nextInt(9)).toString()).append('\n').append(paragraph()).append("\n\n")
                4 -> sb.append("x".repeat(1000 + nextInt(5000))).append('\n')
                else -> sb.append(paragraph()).append('\n')
            }
        }
        var text = sb.toString()
        if (nextInt(4) == 0) text = text.replace("\n", "\r\n")
        return text
    }

    @Test
    fun markdown_fuzz_noThrowableEscapes() {
        val out = tempFolder.newFolder("fuzz-md")
        val random = Random(20261125)
        var guarded = 0
        // 200 random documents + 5 explicitly deep ones.
        val cases = (0 until 200).map { random.markdown() } +
            listOf(150, 300, 600, 1200, 2000).map { d ->
                ("> ".repeat(d) + "deep\n\n") to d
            }
        cases.forEachIndexed { index, (text, depth) ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            try {
                val book = TextBookParser.parseMarkdown(bytes, "fuzz.md", out, ::fallback)
                for (chapter in book.chapters) {
                    assertTrue(
                        "case $index: chapter file missing: ${chapter.file}",
                        chapter.file.isFile
                    )
                }
            } catch (t: IllegalStateException) {
                if (t.message?.contains("deeply nested") == true) {
                    if (depth < 50) {
                        fail("case $index: nesting guard fired on shallow input (depth=$depth)")
                    }
                    guarded++
                } else {
                    fail("case $index: unexpected IllegalStateException: ${t.message}\ninput:\n${text.take(2000)}")
                }
            } catch (t: Throwable) {
                fail("case $index: ${t::class.simpleName} escaped the parser: ${t.message}\ninput:\n${text.take(2000)}")
            }
        }
        // Only the 5 explicitly deep cases may trip the guard.
        assertTrue("guard fired $guarded times, expected at most 5", guarded <= 5)
    }

    @Test
    fun text_fuzz_noThrowableEscapes() {
        val out = tempFolder.newFolder("fuzz-txt")
        val random = Random(20261125)
        repeat(100) { index ->
            val text = random.plainText()
            try {
                val book = TextBookParser.parseText(
                    text.toByteArray(Charsets.UTF_8), "fuzz.txt", out, ::fallback
                )
                for (chapter in book.chapters) {
                    assertTrue(
                        "case $index: chapter file missing: ${chapter.file}",
                        chapter.file.isFile
                    )
                }
            } catch (t: Throwable) {
                fail("case $index: ${t::class.simpleName} escaped the parser: ${t.message}\ninput:\n${text.take(2000)}")
            }
        }
    }
}

package com.novelfa.reader.parser.engine

import com.novelfa.reader.model.ParsedBook
import com.novelfa.reader.parser.BookFormat
import com.novelfa.reader.parser.BookConverter
import com.novelfa.reader.parser.RtfParser
import java.io.File

/**
 * The RTF engine. Like TXT/Markdown, RTF is NOT made lazy at import,
 * deliberately: its parse is a single linear pass over the file, and
 * its output IS the reader's open cache — parsing at import is not
 * double work, it is the open cache being built once. The engine
 * separation is still real: import and open both go through this
 * engine, and no other format's code path can reach the RTF parser.
 *
 * An RTF carries no cover (embedded pictures are skipped by the
 * parser — see [com.novelfa.reader.parser.RtfParser]), so the
 * generated cover applies, like a plain-text book. Its title comes
 * from `{\info{\title ...}}` when the file declares one, else the
 * file name; the section count is only known after the (single-pass)
 * parse, which import performs through [open] into the open cache.
 */
object RtfEngine : BookEngine {
    override val format: BookFormat = BookFormat.RTF

    override fun metadata(
        source: File,
        displayName: String,
        scratchDir: File,
        cancelCheck: (() -> Unit)?
    ): EngineMetadata {
        // A best-effort title only, from the file's head: the
        // {\info} group lives in the RTF header, so 64 KiB is
        // ample and a huge RTF pays no full read here. The section
        // count is unknown until the parse below, which import
        // performs through [open]. Callers must not treat the null
        // count as zero chapters.
        val head = runCatching {
            // Bounded head read (readNBytes is API 33+; minSdk is 29).
            val buf = ByteArray(64 * 1024)
            source.inputStream().use { input ->
                var off = 0
                while (off < buf.size) {
                    val n = input.read(buf, off, buf.size - off)
                    if (n < 0) break
                    off += n
                }
                buf.copyOf(off)
            }
        }.getOrNull() ?: ByteArray(0)
        val title = runCatching {
            RtfParser.infoTitle(head)
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: File(displayName).nameWithoutExtension
                .takeIf { it.isNotBlank() } ?: displayName
        return EngineMetadata(title, null, null)
    }

    override suspend fun open(
        source: File,
        outDir: File,
        chapterFallback: (Int) -> String,
        displayName: String?,
        onProgress: ((Int, Int) -> Unit)?,
        cancelCheck: (() -> Unit)?
    ): ParsedBook {
        val detected = BookFormat.detect(source)
        require(detected == BookFormat.RTF) {
            "RtfEngine cannot open format: ${detected?.name}"
        }
        return BookConverter.parse(source, outDir, chapterFallback, displayName, onProgress)
    }
}

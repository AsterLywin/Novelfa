package com.novelfa.reader.parser.engine

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Shared, guarded ZIP extraction for the engines. The same caps and
 * path-traversal guard used to live in three places (importer, DOCX
 * parser, reader open); a single copy means a fix here protects every
 * engine that unzips anything.
 */
object ZipExtract {
    const val MAX_ENTRIES = 10_000
    const val MAX_BYTES = 500L * 1024 * 1024

    /**
     * Extracts every entry of [source] into [outDir], preserving
     * relative paths. Throws on a zip bomb (entry count or total
     * uncompressed bytes over the caps) or on an entry that would
     * escape [outDir].
     */
    fun extractAll(
        source: File,
        outDir: File,
        cancelCheck: (() -> Unit)? = null
    ) {
        ZipFile(source).use { zf ->
            val entries = zf.entries()
            var entryCount = 0
            var totalBytes = 0L
            while (entries.hasMoreElements()) {
                cancelCheck?.invoke()
                val entry = entries.nextElement()
                if (++entryCount > MAX_ENTRIES) {
                    throw IOException("Zip entry count exceeds limit: ${entry.name}")
                }
                val out = File(outDir, entry.name)
                val canonicalOut = out.canonicalPath
                val canonicalDir = outDir.canonicalPath
                if (!canonicalOut.startsWith(canonicalDir + File.separatorChar)) {
                    throw IOException("Zip entry outside target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input ->
                        out.outputStream().use { output ->
                            totalBytes += input.copyTo(output)
                            if (totalBytes > MAX_BYTES) {
                                throw IOException("Zip uncompressed size exceeds limit")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Extracts a single entry by name into [destFile]. Returns false
     * when the entry does not exist. The same traversal guard applies:
     * [destFile] must already be inside its intended directory — the
     * caller picks it, this function only refuses to write through a
     * directory entry.
     */
    fun extractOne(source: File, entryName: String, destFile: File): Boolean {
        ZipFile(source).use { zf ->
            val entry = zf.getEntry(entryName) ?: return false
            if (entry.isDirectory) return false
            destFile.parentFile?.mkdirs()
            zf.getInputStream(entry).use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return true
        }
    }

    /** Reads one entry fully, or null when it does not exist. */
    fun readEntry(source: File, entryName: String): ByteArray? {
        ZipFile(source).use { zf ->
            val entry = zf.getEntry(entryName) ?: return null
            return zf.getInputStream(entry).use { it.readBytes() }
        }
    }
}

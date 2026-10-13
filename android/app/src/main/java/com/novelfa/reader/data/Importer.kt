package com.novelfa.reader.data

import android.content.Context
import android.net.Uri
import com.novelfa.reader.parser.EpubParser
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImportException(
    message: String,
    val messageFa: String = "خطا در باز کردن کتاب",
    cause: Throwable? = null
) : Exception(message, cause)

class Importer(private val context: Context, private val db: ReaderDb) {

    suspend fun importEpub(sourceUri: Uri): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val booksDir = File(context.filesDir, "books").apply { mkdirs() }
        val dest = File(booksDir, "$id.epub")
        val tmpDir = File(context.cacheDir, "epub_tmp/$id")
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("Cannot open input stream: $sourceUri")
            tmpDir.mkdirs()
            unzip(dest, tmpDir)
            val parsed = EpubParser.parse(tmpDir, "$id.epub")
            val coverPath = parsed.coverFile
                ?.takeIf { it.exists() }
                ?.let { CoverStore(context).saveCover(it, dest.absolutePath).absolutePath }
            db.bookDao().insert(
                BookEntity(
                    id = id,
                    title = parsed.title,
                    filePath = dest.absolutePath,
                    coverPath = coverPath,
                    totalChapters = parsed.chapters.size,
                    dateAdded = Instant.now().toString()
                )
            )
            tmpDir.deleteRecursively()
            id
        } catch (e: ImportException) {
            tmpDir.deleteRecursively()
            dest.delete()
            throw e
        } catch (e: Exception) {
            tmpDir.deleteRecursively()
            dest.delete()
            throw ImportException(e.message ?: "import failed", cause = e)
        }
    }

    private fun unzip(zip: File, outDir: File) {
        ZipFile(zip).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val out = File(outDir, entry.name)
                val canonicalOut = out.canonicalPath
                val canonicalDir = outDir.canonicalPath
                if (!canonicalOut.startsWith(canonicalDir)) {
                    throw IOException("Zip entry outside target dir: ${entry.name}")
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
    }
}

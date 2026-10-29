package com.novelfa.reader.reader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One image the reader can save: either a file inside the book's
 * extracted cache, or inline data: bytes decoded from the chapter
 * HTML. Only one of [file] / [bytes] is set.
 */
class ImageSaveTarget(
    val file: File?,
    val bytes: ByteArray?,
    val displayName: String,
    val mime: String
)

/**
 * Saves EPUB chapter images to the user's gallery, without any
 * permission and without the user picking a location: Android 10+
 * (Novelfa's minSdk is 29) lets an app insert its own images into
 * MediaStore, so the image lands in Pictures/Novelfa and shows up in
 * the gallery app. No SAF picker, no storage permission, and the
 * book file itself is never touched — the saved copy is a new file.
 */
object ImageSaver {

    private const val FOLDER = "Pictures/Novelfa"

    fun mimeForExtension(ext: String): String = when (ext.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "avif" -> "image/avif"
        else -> "image/*"
    }

    fun extensionForMime(mime: String): String = when (mime.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/bmp" -> "bmp"
        "image/svg+xml" -> "svg"
        "image/avif" -> "avif"
        else -> "png"
    }

    /**
     * Resolves a tapped <img> src to something saveable, or null when
     * it cannot be saved. Absolute file: URLs must point inside the
     * app's own files dir (the book's extracted cache lives there);
     * anything outside it is refused, so a book can never trick a tap
     * into exporting an unrelated file. Relative srcs resolve against
     * [chapterFolder] (the chapter the image lives in). data: images
     * must be base64-encoded; other data: forms are refused.
     */
    fun resolveImageSource(
        context: Context,
        src: String,
        chapterFolder: File?
    ): ImageSaveTarget? {
        val trimmed = src.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("data:", ignoreCase = true)) {
            val headerEnd = trimmed.indexOf(',')
            if (headerEnd < 0) return null
            val header = trimmed.substring(5, headerEnd)
            val parts = header.split(';')
            val mime = parts.firstOrNull()?.takeIf { it.startsWith("image/") } ?: return null
            if (!parts.any { it.equals("base64", ignoreCase = true) }) return null
            val bytes = runCatching {
                Base64.decode(trimmed.substring(headerEnd + 1), Base64.DEFAULT)
            }.getOrNull() ?: return null
            if (bytes.isEmpty()) return null
            return ImageSaveTarget(
                file = null,
                bytes = bytes,
                displayName = "image." + extensionForMime(mime),
                mime = mime
            )
        }
        val file: File = when {
            trimmed.startsWith("file:", ignoreCase = true) -> {
                runCatching { File(java.net.URI(trimmed)) }.getOrNull() ?: return null
            }
            trimmed.startsWith("http:", ignoreCase = true) ||
                trimmed.startsWith("https:", ignoreCase = true) -> return null
            chapterFolder != null -> {
                val decoded = runCatching {
                    java.net.URLDecoder.decode(
                        trimmed.replace("+", "%2B"), "UTF-8"
                    )
                }.getOrNull() ?: trimmed
                File(chapterFolder, decoded)
            }
            else -> return null
        }
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (!canonical.isFile) return null
        val filesDir = runCatching { context.filesDir.canonicalFile }.getOrNull()
        if (filesDir != null && !canonical.path.startsWith(filesDir.path + File.separator)) {
            return null
        }
        val name = canonical.name.takeIf { it.isNotEmpty() } ?: return null
        return ImageSaveTarget(
            file = canonical,
            bytes = null,
            displayName = name,
            mime = mimeForExtension(canonical.extension)
        )
    }

    /** Saves [target] to Pictures/Novelfa. Returns the name actually used. */
    suspend fun saveToGallery(context: Context, target: ImageSaveTarget): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = freeName(context, target.displayName)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, target.mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri: Uri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: throw IllegalStateException("MediaStore insert returned null")
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        writeTo(out, target)
                    } ?: throw IllegalStateException("Could not open gallery output")
                } catch (e: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
                val done = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, done, null, null)
                name
            }
        }

    private fun writeTo(out: OutputStream, target: ImageSaveTarget) {
        if (target.bytes != null) {
            out.write(target.bytes)
            return
        }
        val file = target.file ?: throw IllegalStateException("No image source")
        file.inputStream().buffered().use { it.copyTo(out) }
    }

    /** First free name in the gallery folder: name, name (1), name (2), … */
    private fun freeName(context: Context, wanted: String): String {
        val existing = mutableSetOf<String>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                if (idx >= 0) {
                    while (cursor.moveToNext()) {
                        cursor.getString(idx)?.let { existing.add(it) }
                    }
                }
            }
        }
        if (wanted !in existing) return wanted
        val dot = wanted.lastIndexOf('.')
        val base = if (dot > 0) wanted.substring(0, dot) else wanted
        val ext = if (dot > 0) wanted.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (candidate !in existing) return candidate
            n++
        }
    }
}

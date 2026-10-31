package com.novelfa.reader.reader

import android.graphics.Bitmap

/**
 * AVIF decoding for the native comic viewer, backed by the
 * libavif + libaom native engine (android/app/src/main/cpp).
 *
 * Graceful degradation is the whole contract: if the native lib
 * is absent (a build without NDK, or a device ABI outside the
 * cmake ABI filter), every decode returns null and AVIF pages
 * take the viewer's normal failure placeholder — never a crash,
 * and every other format is unaffected. The JVM unit tests rely
 * on this: there is no .so on the test classpath, so
 * [decodeBounds] and [decode] must return null there without
 * ever touching native code (the [nativeLoaded] guard runs
 * before any external call).
 *
 * What is decoded: the first frame only (an animated AVIF shows
 * frame 0, same rule as still WebP). Rotation (irot), mirror
 * (imir) and the clean-aperture crop are applied natively (see
 * novelfa_avif_core.h); EXIF orientation inside an AVIF without
 * an irot box is not honored (same gap as libavif's own sample).
 */
object AvifDecoder {

    private val nativeLoaded: Boolean =
        runCatching { System.loadLibrary("novelfa_avif") }.isSuccess

    fun isAvailable(): Boolean = nativeLoaded

    /**
     * Pure-Kotlin AVIF brand check (the ftyp box): no native
     * call, safe on JVM tests. Major brand "avif"/"avis" at
     * offset 8, or either in the compatible-brand list starting
     * at offset 16, bounded by the box size. A positive here is
     * only a hint — the native decoder validates fully and
     * returns null for anything that is not really an AVIF.
     */
    fun isAvif(data: ByteArray): Boolean {
        if (data.size < 12) return false
        if (!matches(data, 4, "ftyp")) return false
        val boxSize =
            ((data[0].toInt() and 0xFF) shl 24) or
                ((data[1].toInt() and 0xFF) shl 16) or
                ((data[2].toInt() and 0xFF) shl 8) or
                (data[3].toInt() and 0xFF)
        val end = when {
            boxSize == 0 -> data.size
            boxSize == 1 -> data.size // largesize: brands still at 8/16
            else -> minOf(boxSize, data.size)
        }
        if (matches(data, 8, "avif") || matches(data, 8, "avis")) return true
        var off = 16
        while (off + 4 <= end) {
            if (matches(data, off, "avif") || matches(data, off, "avis")) {
                return true
            }
            off += 4
        }
        return false
    }

    private fun matches(data: ByteArray, off: Int, s: String): Boolean {
        if (off + 4 > data.size) return false
        for (i in 0 until 4) {
            if (data[off + i] != s[i].code.toByte()) return false
        }
        return true
    }

    /**
     * Display size of the first frame (after clap crop and irot
     * rotation), or null when the lib is absent, the bytes are
     * not an AVIF, or the file is corrupt. Parse only — no pixel
     * decode — so this is cheap enough for the dual-page split
     * decision.
     */
    fun decodeBounds(data: ByteArray): Pair<Int, Int>? {
        if (!nativeLoaded || !isAvif(data)) return null
        val out = IntArray(2)
        if (!decodeInfo(data, out)) return null
        if (out[0] <= 0 || out[1] <= 0) return null
        return out[0] to out[1]
    }

    /**
     * Decodes the first frame to an ARGB_8888 bitmap, or null on
     * any failure (lib absent, not an AVIF, corrupt file, OOM).
     * The caller owns the bitmap and recycles it as usual.
     */
    fun decode(data: ByteArray): Bitmap? {
        val (w, h) = decodeBounds(data) ?: return null
        val bitmap = runCatching {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        val ok = runCatching { decodeInto(data, bitmap) }.getOrDefault(false)
        if (!ok) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    private external fun decodeInfo(data: ByteArray, out: IntArray): Boolean
    private external fun decodeInto(data: ByteArray, bitmap: Bitmap): Boolean
}

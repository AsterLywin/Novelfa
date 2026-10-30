package com.novelfa.reader.reader

import android.graphics.Bitmap
import kotlin.math.floor

/**
 * Clean-room image enhancement for the native comic/PDF viewer,
 * learned behaviourally from ComicScreen's rendering pipeline
 * (its filter dialog: an upscale step with a selectable
 * interpolation — Linear / Cubic / Lanczos, Lanczos the default —
 * and an unsharp-mask sharpen step with a level slider).
 *
 * Nothing here is ComicScreen code: it uses no OpenCV and no
 * copied routine. The two operations are textbook algorithms,
 * reimplemented in pure Kotlin:
 *
 * - [upscaleBicubic]: separable Catmull-Rom bicubic resampling.
 *   ComicScreen upscales with Lanczos4; bicubic is the
 *   phone-cheap cousin (4 taps per axis instead of 8) and still
 *   visibly beats the GPU's bilinear stretch on line art, which
 *   is what a small page upscaled to screen size otherwise gets.
 *   It runs only when a page is SMALLER than its display size.
 * - [resizeBox]: area-average downsampling to an exact display
 *   size. This is the other half of "resize to display size":
 *   a big page shown fit-width used to be downscaled by the GPU
 *   with plain bilinear filtering and no mipmaps, which drops
 *   thin lines and shimmers on line art — and that display
 *   downscale is format-blind, so it was the remaining softness
 *   for PNG/WebP/GIF, whose decodes are already pixel-exact
 *   (the quality decode flag only changes JPEG decoding).
 *   Averaging each output pixel over its full source footprint
 *   preserves thin lines where bilinear sampling loses them.
 * - [sharpenUnsharp]: unsharp masking in the same form as
 *   ComicScreen's sharpen step — a blurred copy is subtracted
 *   from an amplified original: out = src * (1.4 + f) -
 *   blur * (0.4 + f), with f = level / 10. The blur here is a
 *   separable radius-2 box blur (a cheap stand-in for
 *   ComicScreen's 5x5 Gaussian); the weighting formula is what
 *   makes the strength scale match.
 *
 * Both functions return the input bitmap itself when there is
 * nothing to do (same size for upscale, level <= 0 for sharpen),
 * so callers can recycle with a simple identity check.
 */

/** Bicubic (Catmull-Rom) weights for a fractional position t. */
private fun cubicWeights(t: Float, out: FloatArray) {
    val t2 = t * t
    val t3 = t2 * t
    out[0] = -0.5f * t3 + t2 - 0.5f * t
    out[1] = 1.5f * t3 - 2.5f * t2 + 1f
    out[2] = -1.5f * t3 + 2f * t2 + 0.5f * t
    out[3] = 0.5f * t3 - 0.5f * t2
}

fun upscaleBicubic(src: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
    if (targetWidth <= 0 || targetHeight <= 0 ||
        (targetWidth == src.width && targetHeight == src.height)
    ) {
        return src
    }
    val sw = src.width
    val sh = src.height
    val srcPixels = IntArray(sw * sh)
    src.getPixels(srcPixels, 0, sw, 0, 0, sw, sh)

    // Horizontal pass: targetWidth x sh intermediate.
    val mid = IntArray(targetWidth * sh)
    val w = FloatArray(4)
    val xScale = sw.toFloat() / targetWidth
    for (x in 0 until targetWidth) {
        val srcX = (x + 0.5f) * xScale - 0.5f
        val base = floor(srcX).toInt()
        cubicWeights(srcX - base, w)
        for (y in 0 until sh) {
            var a = 0f
            var r = 0f
            var g = 0f
            var b = 0f
            for (k in 0 until 4) {
                val sx = (base + k - 1).coerceIn(0, sw - 1)
                val px = srcPixels[y * sw + sx]
                val weight = w[k]
                a += ((px ushr 24) and 0xFF) * weight
                r += ((px ushr 16) and 0xFF) * weight
                g += ((px ushr 8) and 0xFF) * weight
                b += (px and 0xFF) * weight
            }
            mid[y * targetWidth + x] =
                (a.toInt().coerceIn(0, 255) shl 24) or
                    (r.toInt().coerceIn(0, 255) shl 16) or
                    (g.toInt().coerceIn(0, 255) shl 8) or
                    b.toInt().coerceIn(0, 255)
        }
    }
    // Vertical pass: targetWidth x targetHeight result.
    val out = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    val outPixels = IntArray(targetWidth * targetHeight)
    val yScale = sh.toFloat() / targetHeight
    for (y in 0 until targetHeight) {
        val srcY = (y + 0.5f) * yScale - 0.5f
        val base = floor(srcY).toInt()
        cubicWeights(srcY - base, w)
        for (x in 0 until targetWidth) {
            var a = 0f
            var r = 0f
            var g = 0f
            var b = 0f
            for (k in 0 until 4) {
                val sy = (base + k - 1).coerceIn(0, sh - 1)
                val px = mid[sy * targetWidth + x]
                val weight = w[k]
                a += ((px ushr 24) and 0xFF) * weight
                r += ((px ushr 16) and 0xFF) * weight
                g += ((px ushr 8) and 0xFF) * weight
                b += (px and 0xFF) * weight
            }
            outPixels[y * targetWidth + x] =
                (a.toInt().coerceIn(0, 255) shl 24) or
                    (r.toInt().coerceIn(0, 255) shl 16) or
                    (g.toInt().coerceIn(0, 255) shl 8) or
                    b.toInt().coerceIn(0, 255)
        }
    }
    out.setPixels(outPixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
    return out
}

/**
 * Area-average resize to an exact size, separable: a horizontal
 * pass to (targetWidth x srcHeight), then a vertical pass to
 * (targetWidth x targetHeight). Each output pixel averages the
 * source pixels its footprint covers, with fractional weights on
 * the footprint edges — so a 1600px page downscaled to 1080px
 * keeps its thin lines instead of sampling every ~1.5th pixel.
 * Returns the input itself when the size already matches.
 */
fun resizeBox(src: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
    if (targetWidth <= 0 || targetHeight <= 0 ||
        (targetWidth == src.width && targetHeight == src.height)
    ) {
        return src
    }
    val sw = src.width
    val sh = src.height
    val srcPixels = IntArray(sw * sh)
    src.getPixels(srcPixels, 0, sw, 0, 0, sw, sh)

    // One axis of area-average resampling: out[o] averages
    // src pixels over [o * scale, (o + 1) * scale).
    fun resampleAxis(
        input: IntArray, inSize: Int, outSize: Int,
        rowCount: Int, rowStride: Int
    ): IntArray {
        val scale = inSize.toFloat() / outSize
        val out = IntArray(outSize * rowCount)
        for (row in 0 until rowCount) {
            for (o in 0 until outSize) {
                val start = o * scale
                val end = (o + 1) * scale
                val first = start.toInt()
                val last = (end - 0.0001f).toInt()
                var a = 0f
                var r = 0f
                var g = 0f
                var b = 0f
                var wSum = 0f
                for (p in first..last) {
                    val weight =
                        minOf((p + 1).toFloat(), end) - maxOf(p.toFloat(), start)
                    if (weight <= 0f) continue
                    val px = input[row * rowStride + p.coerceIn(0, inSize - 1)]
                    a += ((px ushr 24) and 0xFF) * weight
                    r += ((px ushr 16) and 0xFF) * weight
                    g += ((px ushr 8) and 0xFF) * weight
                    b += (px and 0xFF) * weight
                    wSum += weight
                }
                if (wSum > 0f) {
                    a /= wSum
                    r /= wSum
                    g /= wSum
                    b /= wSum
                }
                out[row * outSize + o] =
                    (a.toInt().coerceIn(0, 255) shl 24) or
                        (r.toInt().coerceIn(0, 255) shl 16) or
                        (g.toInt().coerceIn(0, 255) shl 8) or
                        b.toInt().coerceIn(0, 255)
            }
        }
        return out
    }

    // Horizontal pass: each source row becomes a row of
    // targetWidth. Vertical pass: each column of the
    // intermediate (targetWidth x sh) is averaged the same way,
    // walking the column directly.
    val mid = resampleAxis(srcPixels, sw, targetWidth, sh, sw)
    val outPixels = IntArray(targetWidth * targetHeight)
    val yScale = sh.toFloat() / targetHeight
    for (x in 0 until targetWidth) {
        for (o in 0 until targetHeight) {
            val start = o * yScale
            val end = (o + 1) * yScale
            val first = start.toInt()
            val last = (end - 0.0001f).toInt()
            var a = 0f
            var r = 0f
            var g = 0f
            var b = 0f
            var wSum = 0f
            for (p in first..last) {
                val weight =
                    minOf((p + 1).toFloat(), end) - maxOf(p.toFloat(), start)
                if (weight <= 0f) continue
                val px = mid[p.coerceIn(0, sh - 1) * targetWidth + x]
                a += ((px ushr 24) and 0xFF) * weight
                r += ((px ushr 16) and 0xFF) * weight
                g += ((px ushr 8) and 0xFF) * weight
                b += (px and 0xFF) * weight
                wSum += weight
            }
            if (wSum > 0f) {
                a /= wSum
                r /= wSum
                g /= wSum
                b /= wSum
            }
            outPixels[o * targetWidth + x] =
                (a.toInt().coerceIn(0, 255) shl 24) or
                    (r.toInt().coerceIn(0, 255) shl 16) or
                    (g.toInt().coerceIn(0, 255) shl 8) or
                    b.toInt().coerceIn(0, 255)
        }
    }
    val out = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    out.setPixels(outPixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
    return out
}

/** One separable radius-2 box-blur pass over packed ARGB pixels. */
private fun boxBlur(pixels: IntArray, width: Int, height: Int): IntArray {
    val radius = 2
    val tmp = IntArray(pixels.size)
    // Horizontal.
    for (y in 0 until height) {
        for (x in 0 until width) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            var n = 0
            for (k in -radius..radius) {
                val sx = (x + k).coerceIn(0, width - 1)
                val px = pixels[y * width + sx]
                a += (px ushr 24) and 0xFF
                r += (px ushr 16) and 0xFF
                g += (px ushr 8) and 0xFF
                b += px and 0xFF
                n++
            }
            tmp[y * width + x] =
                (a / n shl 24) or (r / n shl 16) or (g / n shl 8) or (b / n)
        }
    }
    // Vertical.
    val out = IntArray(pixels.size)
    for (y in 0 until height) {
        for (x in 0 until width) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            var n = 0
            for (k in -radius..radius) {
                val sy = (y + k).coerceIn(0, height - 1)
                val px = tmp[sy * width + x]
                a += (px ushr 24) and 0xFF
                r += (px ushr 16) and 0xFF
                g += (px ushr 8) and 0xFF
                b += px and 0xFF
                n++
            }
            out[y * width + x] =
                (a / n shl 24) or (r / n shl 16) or (g / n shl 8) or (b / n)
        }
    }
    return out
}

fun sharpenUnsharp(src: Bitmap, level: Int): Bitmap {
    if (level <= 0) return src
    val w = src.width
    val h = src.height
    val srcPixels = IntArray(w * h)
    src.getPixels(srcPixels, 0, w, 0, 0, w, h)
    val blurred = boxBlur(srcPixels, w, h)
    // out = src * (1.4 + f) - blur * (0.4 + f), f = level / 10.
    // Alpha is copied from the source, never sharpened.
    val f = level / 10f
    val srcWeight = 1.4f + f
    val blurWeight = 0.4f + f
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val outPixels = IntArray(w * h)
    for (i in srcPixels.indices) {
        val sp = srcPixels[i]
        val bp = blurred[i]
        val r = ((sp ushr 16) and 0xFF) * srcWeight -
            ((bp ushr 16) and 0xFF) * blurWeight
        val g = ((sp ushr 8) and 0xFF) * srcWeight -
            ((bp ushr 8) and 0xFF) * blurWeight
        val b = (sp and 0xFF) * srcWeight -
            (bp and 0xFF) * blurWeight
        outPixels[i] =
            (sp and -0x1000000) or
                (r.toInt().coerceIn(0, 255) shl 16) or
                (g.toInt().coerceIn(0, 255) shl 8) or
                b.toInt().coerceIn(0, 255)
    }
    out.setPixels(outPixels, 0, w, 0, 0, w, h)
    return out
}

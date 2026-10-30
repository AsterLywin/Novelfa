package com.novelfa.reader.reader

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Margin detection for the native comic viewer, re-implemented
 * clean-room from ComicScreen's OBSERVED behaviour (decompiled
 * 2309 for study only — no code, assets or libraries copied):
 *
 * - The crop is a display-time transform, per page, applied to
 *   the rendered bitmap; the file is never touched.
 * - Detection is EDGE-based, not uniform-colour-based: grayscale
 *   -> blur -> Canny-style edge map (thresholds 70/200) ->
 *   bounding box of the edge pixels. A page whose margin is
 *   uneven, textured or gradient-coloured still crops, and a
 *   page whose content touches a corner does not defeat it —
 *   both cases where a corner-colour scan fails.
 * - X bounds come from the edge map with the top and bottom
 *   1/17 bands blacked out (a full-width header line must not
 *   force the width to full); Y bounds come from the full edge
 *   map. This asymmetry is ComicScreen's, kept as observed.
 * - The box is then EXPANDED by the kept-margin percentages:
 *   padH% of max(left, right margins) on both sides, padV% of
 *   max(top, bottom margins) on top and bottom, clamped to the
 *   image. Defaults are 0: crop exactly to the detected box.
 * - No edges at all -> no crop (a blank page stays whole).
 *
 * Deliberate adaptations (same visible result, different
 * machinery — OpenCV is not a Novelfa dependency and its
 * multi-megabyte native library is not justified for one
 * bounding box):
 * - No OpenCV: grayscale + separable box blur + Sobel
 *   magnitude + two-threshold hysteresis (strong >= 200, weak
 *   >= 70 kept only beside a strong pixel — one dilation pass
 *   instead of Canny's edge tracking).
 * - Detection runs on a copy downsampled to max dimension 1000
 *   and the box is scaled back; bounds differ from full-res by
 *   a few pixels at most, and the per-page cost stays in the
 *   tens of milliseconds on a phone instead of seconds.
 * - ComicScreen's size-dependent blur kernels (7x3 / 9x3) are
 *   replaced by one 7x3-equivalent blur on the downsampled
 *   image, since the downsample already normalises the scale.
 *
 * Returns the crop box in the ORIGINAL bitmap's coordinates,
 * or null when the page must stay whole.
 */
fun detectContentRect(src: Bitmap, padHPercent: Int, padVPercent: Int): Rect? {
    val w = src.width
    val h = src.height
    if (w <= 2 || h <= 2) return null
    // Downsample for detection; the box is scaled back below.
    val scale = if (max(w, h) > 1000) 1000f / max(w, h) else 1f
    val dw = max(1, (w * scale).toInt())
    val dh = max(1, (h * scale).toInt())
    val small = if (scale < 1f) {
        Bitmap.createScaledBitmap(src, dw, dh, true)
    } else {
        src
    }
    try {
        val pixels = IntArray(dw * dh)
        small.getPixels(pixels, 0, dw, 0, 0, dw, dh)
        // Grayscale (luminance weights) + separable box blur
        // (radiusX 3, radiusY 1 — the 7x3 shape).
        val gray = IntArray(dw * dh)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = (((p shr 16) and 0xFF) * 77 +
                ((p shr 8) and 0xFF) * 150 +
                (p and 0xFF) * 29) shr 8
        }
        val blurred = IntArray(dw * dh)
        val tmp = IntArray(dw * dh)
        for (y in 0 until dh) {
            for (x in 0 until dw) {
                var sum = 0
                var count = 0
                for (kx in -3..3) {
                    val xx = (x + kx).coerceIn(0, dw - 1)
                    sum += gray[y * dw + xx]
                    count++
                }
                tmp[y * dw + x] = sum / count
            }
        }
        for (y in 0 until dh) {
            for (x in 0 until dw) {
                var sum = 0
                var count = 0
                for (ky in -1..1) {
                    val yy = (y + ky).coerceIn(0, dh - 1)
                    sum += tmp[yy * dw + x]
                    count++
                }
                blurred[y * dw + x] = sum / count
            }
        }
        // Sobel magnitude (|gx| + |gy|), then hysteresis.
        val strong = BooleanArray(dw * dh)
        val weak = BooleanArray(dw * dh)
        for (y in 1 until dh - 1) {
            for (x in 1 until dw - 1) {
                val i = y * dw + x
                val gx = -blurred[i - dw - 1] - 2 * blurred[i - 1] - blurred[i + dw - 1] +
                    blurred[i - dw + 1] + 2 * blurred[i + 1] + blurred[i + dw + 1]
                val gy = -blurred[i - dw - 1] - 2 * blurred[i - dw] - blurred[i - dw + 1] +
                    blurred[i + dw - 1] + 2 * blurred[i + dw] + blurred[i + dw + 1]
                val mag = abs(gx) + abs(gy)
                if (mag >= 200) {
                    strong[i] = true
                } else if (mag >= 70) {
                    weak[i] = true
                }
            }
        }
        fun isEdge(idx: Int): Boolean {
            if (strong[idx]) return true
            if (!weak[idx]) return false
            val x = idx % dw
            val y = idx / dw
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val xx = x + dx
                    val yy = y + dy
                    if (xx in 0 until dw && yy in 0 until dh && strong[yy * dw + xx]) {
                        return true
                    }
                }
            }
            return false
        }
        // Full bounds (Y comes from here) and band-cleaned
        // bounds (X comes from here): rows in the top/bottom
        // 1/17 bands are ignored for X only.
        val band = dh / 17
        var minX = dw
        var maxX = -1
        var minY = dh
        var maxY = -1
        var minXClean = dw
        var maxXClean = -1
        for (y in 0 until dh) {
            for (x in 0 until dw) {
                val idx = y * dw + x
                if (!isEdge(idx)) continue
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (y < band || y >= dh - band) continue
                if (x < minXClean) minXClean = x
                if (x > maxXClean) maxXClean = x
            }
        }
        if (maxY < 0) return null
        // If the bands ate every edge (a page whose only edges
        // sit in the top/bottom strips), fall back to the full
        // X bounds rather than cropping to nothing.
        if (maxXClean < 0) {
            for (y in 0 until dh) {
                for (x in 0 until dw) {
                    if (isEdge(y * dw + x)) {
                        if (x < minXClean) minXClean = x
                        if (x > maxXClean) maxXClean = x
                    }
                }
            }
        }
        minX = minXClean
        maxX = maxXClean
        // Expand by the kept-margin percentages, like
        // ComicScreen: each side grows by pad% of the LARGER
        // margin on that axis, then clamps to the image.
        val leftMargin = minX
        val rightMargin = dw - 1 - maxX
        val topMargin = minY
        val bottomMargin = dh - 1 - maxY
        val padX = max(leftMargin, rightMargin) * padHPercent / 100
        val padY = max(topMargin, bottomMargin) * padVPercent / 100
        val nx0 = max(0, minX - padX)
        val nx1 = min(dw - 1, maxX + padX)
        val ny0 = max(0, minY - padY)
        val ny1 = min(dh - 1, maxY + padY)
        if (nx1 - nx0 + 1 < dw / 10 || ny1 - ny0 + 1 < dh / 10) return null
        if (nx0 == 0 && ny0 == 0 && nx1 == dw - 1 && ny1 == dh - 1) return null
        // Scale back to the original bitmap's coordinates.
        val inv = 1f / scale
        return Rect(
            (nx0 * inv).toInt().coerceIn(0, w - 1),
            (ny0 * inv).toInt().coerceIn(0, h - 1),
            (nx1 * inv).toInt().coerceIn(0, w - 1),
            (ny1 * inv).toInt().coerceIn(0, h - 1)
        )
    } finally {
        if (small !== src) small.recycle()
    }
}

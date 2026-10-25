package com.novelfa.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.novelfa.reader.R
import java.io.File
import java.security.MessageDigest

/**
 * Typographic cover for books that arrive without cover art (plain-text
 * formats such as TXT/Markdown, and any EPUB whose package has no cover
 * image): a real cover file in [CoverStore], so the book looks like a
 * book everywhere a cover is shown instead of a blank grey box.
 *
 * The design is deliberately book-like, not app-like: a deep gradient,
 * a double frame rule, the title set large in the reader's own
 * Vazirmatn face, and a small line-diamond-line ornament underneath.
 * No author line (text formats carry no author metadata) and no app
 * branding on the cover itself.
 *
 * Deterministic: the palette is picked from the title hash, so
 * re-importing the same book regenerates the same cover. Persian text
 * is laid out with [StaticLayout] (which shapes Arabic-script text and
 * resolves bidi correctly) — never with raw `Canvas.drawText`, which
 * would render Persian letters disconnected.
 *
 * Generation never fails an import: [generate] returns null on any
 * error and the caller simply leaves the book coverless.
 */
object GeneratedCover {

    const val COVER_WIDTH = 600
    const val COVER_HEIGHT = 900

    /** One gradient + ornament accent. Colors are ARGB ints. */
    data class Palette(val top: Int, val bottom: Int, val accent: Int)

    val PALETTES = listOf(
        Palette(0xFF14524F.toInt(), 0xFF0A2E2D.toInt(), 0xFFC9A86A.toInt()),
        Palette(0xFF4A2440.toInt(), 0xFF2A1226.toInt(), 0xFFD4B483.toInt()),
        Palette(0xFF1E3A5F.toInt(), 0xFF0F1E33.toInt(), 0xFFC9A86A.toInt()),
        Palette(0xFF2D4A2B.toInt(), 0xFF16281A.toInt(), 0xFFD4B483.toInt()),
        Palette(0xFF5E2230.toInt(), 0xFF33101B.toInt(), 0xFFD4B483.toInt()),
        Palette(0xFF3A3A40.toInt(), 0xFF1C1C20.toInt(), 0xFFC9A86A.toInt())
    )

    /** Pure + deterministic: the same title always picks the same palette. */
    fun paletteFor(title: String): Palette {
        val idx = (title.hashCode() and Int.MAX_VALUE) % PALETTES.size
        return PALETTES[idx]
    }

    /**
     * Renders the cover for [title] into the cache dir (named from
     * [bookPath] so one book never overwrites another's file), or null
     * when the title is blank or rendering fails for any reason.
     */
    fun generate(context: Context, title: String, bookPath: String): File? {
        val display = title.trim().replace('_', ' ')
            .split("\\s+".toRegex()).joinToString(" ")
        if (display.isBlank()) return null
        return runCatching {
            val palette = paletteFor(display)
            val bitmap = Bitmap.createBitmap(
                COVER_WIDTH, COVER_HEIGHT, Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)

            // Deep gradient ground.
            val ground = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, COVER_HEIGHT.toFloat(),
                    palette.top, palette.bottom, Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(
                0f, 0f, COVER_WIDTH.toFloat(), COVER_HEIGHT.toFloat(), ground
            )

            // Double frame rule, cream at two alphas.
            val frameOuter = Paint().apply {
                color = Color.argb(140, 247, 241, 222)
                style = Paint.Style.STROKE
                strokeWidth = 3f
                isAntiAlias = true
            }
            val frameInner = Paint().apply {
                color = Color.argb(70, 247, 241, 222)
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                isAntiAlias = true
            }
            canvas.drawRect(28f, 28f, COVER_WIDTH - 28f, COVER_HEIGHT - 28f, frameOuter)
            canvas.drawRect(40f, 40f, COVER_WIDTH - 40f, COVER_HEIGHT - 40f, frameInner)

            // Title, shrunk until it fits (max 6 lines, max 560px tall).
            val face = runCatching {
                context.resources.getFont(R.font.vazirmatn_bold)
            }.getOrNull() ?: Typeface.DEFAULT_BOLD
            val textWidth = COVER_WIDTH - 2 * 72
            var textSize = 76f
            var layout: StaticLayout
            while (true) {
                val paint = TextPaint().apply {
                    typeface = face
                    this.textSize = textSize
                    color = Color.rgb(247, 241, 222)
                    isAntiAlias = true
                }
                layout = StaticLayout.Builder.obtain(display, 0, display.length, paint, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                    .setLineSpacing(0f, 1.25f)
                    .build()
                if ((layout.lineCount <= 6 && layout.height <= 560) || textSize <= 34f) break
                textSize -= 4f
            }

            // Title block + ornament, vertically centered as one unit.
            val ornamentGap = 56f
            val ornamentH = 20f
            val blockH = layout.height + ornamentGap + ornamentH
            val top = (COVER_HEIGHT - blockH) / 2f
            canvas.save()
            canvas.translate(72f, top)
            layout.draw(canvas)
            canvas.restore()

            // Line — diamond — line ornament under the title.
            val oy = top + layout.height + ornamentGap + ornamentH / 2f
            val cx = COVER_WIDTH / 2f
            val ornament = Paint().apply {
                color = palette.accent
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
                isAntiAlias = true
            }
            val fill = Paint().apply {
                color = palette.accent
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            canvas.drawLine(cx - 110f, oy, cx - 26f, oy, ornament)
            canvas.drawLine(cx + 26f, oy, cx + 110f, oy, ornament)
            canvas.save()
            canvas.translate(cx, oy)
            canvas.rotate(45f)
            canvas.drawRect(-7f, -7f, 7f, 7f, fill)
            canvas.restore()

            val digest = MessageDigest.getInstance("MD5")
            val key = digest.digest(bookPath.lowercase().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val out = File(context.cacheDir, "generated_cover_$key.png")
            out.outputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            bitmap.recycle()
            out
        }.getOrNull()
    }
}

package com.novelfa.reader.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app icon set: the Windows client's drawn icons (App.xaml `Ico*`
 * PathGeometry resources + the inline toolbar paths in LibraryWindow /
 * MainWindow), geometry copied verbatim into a 16-unit box and rendered
 * with the Windows `Ico` style (stroke 1.6, round caps and joins).
 *
 * UI icons are drawn paths here — never font glyphs (★ ☰ ⚙ ⛶ …),
 * whose weight, size and baseline depend on the device font and never
 * matched the desktop app.
 */
enum class AppIconKind(val data: String, val duo: Boolean = false) {
    All("M2,2.5 H5 V13.5 H2 Z M2,5.2 H5 M6.5,2.5 H9.5 V13.5 H6.5 Z M6.5,5.2 H9.5 M11,3.2 L13.6,2.6 L15,13 L12.4,13.6 Z", duo = true),
    ArrowBack("M13,8 H3 M7,4 L3,8 L7,12"),
    ArrowForward("M3,8 H13 M9,4 L13,8 L9,12"),
    Check("M2.5,8.5 L6,12 L13.5,4"),
    CheckCircle("M8,1.5 A6.5,6.5 0 1 1 7.99,1.5 Z M5.2,8.2 L7.2,10.2 L10.8,6", duo = true),
    Clock("M8,1.5 A6.5,6.5 0 1 1 7.99,1.5 Z M8,4.5 V8 L10.5,9.5", duo = true),
    Close("M4,4 L12,12 M12,4 L4,12"),
    Dots("M3,6.8 A1.2,1.2 0 1 1 2.99,6.8 Z M8,6.8 A1.2,1.2 0 1 1 7.99,6.8 Z M13,6.8 A1.2,1.2 0 1 1 12.99,6.8 Z"),
    Drop("M8,1.8 C6.5,3.6 3.6,6.6 3.6,9.6 A4.4,4.4 0 0 0 12.4,9.6 C12.4,6.6 9.5,3.6 8,1.8 Z", duo = true),
    Edit("M10.5,2.5 L13.5,5.5 L5.5,13.5 H2.5 V10.5 Z M9,4 L12,7", duo = true),
    ExitFullscreen("M6,2 V6 H2 M14,6 H10 V2 M10,14 V10 H14 M2,10 H6 V14"),
    File("M4,1.5 H9.5 L13,5 V14.5 H4 Z M9.5,1.5 V5 H13 M6.5,10 H10.5 M8.5,8 V12", duo = true),
    Font("M1.5,13 L5.5,3 L9.5,13 M3,9.5 H8 M11.2,13 V7.8 M12.9,8.7 A2.15,2.15 0 1 1 12.89,8.7 Z", duo = true),
    Fullscreen("M2,6 V2 H6 M10,2 H14 V6 M14,10 V14 H10 M6,14 H2 V10"),
    Grid("M2.5,2.5 H7 V7 H2.5 Z M9,2.5 H13.5 V7 H9 Z M2.5,9 H7 V13.5 H2.5 Z M9,9 H13.5 V13.5 H9 Z", duo = true),
    Heart("M8,13.8 C3.2,10.2 1.8,7.6 1.8,5.6 A3.2,3.2 0 0 1 8,4.3 A3.2,3.2 0 0 1 14.2,5.6 C14.2,7.6 12.8,10.2 8,13.8 Z", duo = true),
    Info("M8,1.5 A6.5,6.5 0 1 1 7.99,1.5 Z M8,7.9 V12.1 M8,4.35 A0.95,0.95 0 1 1 7.99,4.35 Z"),
    Key("M5.5,3 A3.5,3.5 0 1 1 5.49,3 Z M8,9 L14,15 M11.5,12.5 L13,11 M13,14 L14.5,12.5", duo = true),
    List("M2.5,4 H13.5 M2.5,8 H13.5 M2.5,12 H13.5"),
    Lock("M3.5,7 H12.5 V14 H3.5 Z M5.5,7 V5 A2.5,2.5 0 0 1 10.5,5 V7 M8,9.4 A0.95,0.95 0 1 1 7.99,9.4 Z M8,10.3 V12", duo = true),
    Moon("M13.5,10 A6,6 0 1 1 6,2.5 A4.8,4.8 0 0 0 13.5,10 Z", duo = true),
    Play("M5,3 L13,8 L5,13 Z", duo = true),
    Plus("M8,2.5 V13.5 M2.5,8 H13.5"),
    Reading("M8,4 C6,2.5 3,2.5 1.5,3 V13 C3,12.5 6,12.5 8,14 C10,12.5 13,12.5 14.5,13 V3 C13,2.5 10,2.5 8,4 Z M8,4 V14 M3.6,6.4 C4.8,5.9 6.2,5.9 7.3,6.3 M3.6,9.3 C4.8,8.8 6.2,8.8 7.3,9.2 M8.7,6.3 C9.8,5.9 11.2,5.9 12.4,6.4 M8.7,9.2 C9.8,8.8 11.2,8.8 12.4,9.3", duo = true),
    Scroll("M4,2 H12 V14 H4 Z M6,5 H10 M6,8 H10 M6,11 H9", duo = true),
    Search("M7,2 A5,5 0 1 1 6.99,2 Z M10.7,10.7 L14.5,14.5", duo = true),
    Spacing("M5,3 H14 M5,8 H14 M5,13 H14 M2.5,4.5 V11.5 M1,6 L2.5,4.5 L4,6 M1,10 L2.5,11.5 L4,10"),
    Sparkle("M8,1.5 L9.8,6.2 L14.5,8 L9.8,9.8 L8,14.5 L6.2,9.8 L1.5,8 L6.2,6.2 Z", duo = true),
    Stack("M2,5 L8,2 L14,5 L8,8 Z M2,8 L8,11 L14,8 M2,11 L8,14 L14,11", duo = true),
    Sun("M8,5 A3,3 0 1 1 7.99,5 Z M8,1 V2.5 M8,13.5 V15 M1,8 H2.5 M13.5,8 H15 M3,3 L4,4 M12,12 L13,13 M3,13 L4,12 M12,4 L13,3", duo = true),
    Trash("M2.5,4.5 H13.5 M6,4.5 V2.5 H10 V4.5 M4,4.5 L4.8,14 H11.2 L12,4.5 M6.5,7 V11.5 M9.5,7 V11.5", duo = true),

    // No WPF counterpart — drawn in the same 16-unit / 1.6-round style.
    Gear(
        "M6.34,3.98 L6.98,2.19 L9.02,2.19 L9.66,3.98 L11.38,3.17 L12.83,4.62 " +
            "L12.02,6.34 L13.81,6.98 L13.81,9.02 L12.02,9.66 L12.83,11.38 L11.38,12.83 " +
            "L9.66,12.02 L9.02,13.81 L6.98,13.81 L6.34,12.02 L4.62,12.83 L3.17,11.38 " +
            "L3.98,9.66 L2.19,9.02 L2.19,6.98 L3.98,6.34 L3.17,4.62 L4.62,3.17 Z " +
            "M8,5.9 A2.1,2.1 0 1 1 7.99,5.9 Z"
    , duo = true),
    Warning("M8,2.8 L14.5,13.5 H1.5 Z M8,6.2 V9.4 M8,11.35 A0.75,0.75 0 1 1 7.99,11.35 Z", duo = true)
}

private fun parseIconPath(data: String): Path =
    runCatching { IconPathParser.parse(data) }
        .getOrElse { Path() }

/**
 * Draws one of the [AppIconKind] paths. [filled] fills instead of
 * stroking (the Windows `IcoFill` style, e.g. the favorite heart).
 */
@Composable
fun AppIcon(
    kind: AppIconKind,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    filled: Boolean = false,
    strokeWidth: Float = 1.6f
) {
    val path = remember(kind) { parseIconPath(kind.data) }
    Canvas(modifier.size(size)) {
        val scale = this.size.width / 16f
        // Pivot must be explicit: DrawTransform.scale defaults to the
        // canvas CENTER, which flings the 16-unit glyph off the top-left
        // (small icons shift and clip, large ones vanish entirely).
        withTransform({ scale(scale, scale, Offset.Zero) }) {
            if (filled) {
                drawPath(path, color = tint)
            } else {
                // Duotone: a soft wash of the tint inside closed shapes
                // keeps the line icons from looking bare.
                if (kind.duo) {
                    drawPath(path, color = tint.copy(alpha = 0.20f))
                }
                drawPath(
                    path,
                    color = tint,
                    style = Stroke(
                        width = strokeWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
    }
}

/**
 * The reader-theme (contrast) icon: a stroked circle with a filled
 * half — the drawn form of the ◑ glyph the theme button used before.
 */
@Composable
fun ContrastIcon(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp
) {
    val circle = remember { parseIconPath("M8,1.5 A6.5,6.5 0 1 1 7.99,1.5 Z") }
    val half = remember { parseIconPath("M8,1.5 A6.5,6.5 0 0 1 8,14.5 Z") }
    Canvas(modifier.size(size)) {
        val scale = this.size.width / 16f
        withTransform({ scale(scale, scale, Offset.Zero) }) {
            drawPath(half, color = tint)
            drawPath(
                circle,
                color = tint,
                style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

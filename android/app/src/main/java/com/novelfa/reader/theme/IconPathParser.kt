package com.novelfa.reader.theme

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hand-rolled WPF/SVG path-geometry parser (M/L/H/V/C/S/Q/T/A/Z,
 * absolute and relative) building a Compose [Path] directly.
 *
 * Why not androidx.core.graphics.PathParser: on Aster's Redmi 9 its
 * output silently rendered as nothing (and flattened arcs) inside the
 * library/reader windows, while hand-parsed paths (see EmblemParser)
 * render fine — so the icon set uses this parser, whose arc conversion
 * (SVG F.6.5 endpoint parameterization) is verified against every
 * string in [AppIconKind].
 */
internal object IconPathParser {

    fun parse(data: String): Path {
        val path = Path()
        val tokens = tokenize(data)
        var i = 0
        var cmd: Char? = null
        var cx = 0f
        var cy = 0f
        var sx = 0f
        var sy = 0f
        var pc2x: Float? = null
        var pc2y: Float? = null
        var pqx: Float? = null
        var pqy: Float? = null
        var lastCmd = ' '

        fun num(): Float = tokens[i++].toFloat()
        fun hasNum(): Boolean = i < tokens.size && tokens[i][0].isDigit() ||
            i < tokens.size && (tokens[i][0] == '-' || tokens[i][0] == '.')

        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok.length == 1 && tok[0].isLetter()) {
                cmd = tok[0]
                i++
                if (cmd == 'Z' || cmd == 'z') {
                    path.close()
                    cx = sx
                    cy = sy
                    lastCmd = 'Z'
                    pc2x = null
                    pqx = null
                    cmd = null
                }
                continue
            }
            val c = cmd ?: break
            val rel = c.isLowerCase()
            fun x(v: Float) = if (rel) cx + v else v
            fun y(v: Float) = if (rel) cy + v else v
            when (c.uppercaseChar()) {
                'M' -> {
                    var first = true
                    while (hasNum()) {
                        val nx = x(num())
                        val ny = y(num())
                        if (first) {
                            path.moveTo(nx, ny)
                            sx = nx
                            sy = ny
                            first = false
                            lastCmd = 'M'
                        } else {
                            path.lineTo(nx, ny)
                            lastCmd = 'L'
                        }
                        cx = nx
                        cy = ny
                    }
                    pc2x = null
                    pqx = null
                    cmd = if (rel) 'l' else 'L'
                }
                'L' -> {
                    while (hasNum()) {
                        val nx = x(num())
                        val ny = y(num())
                        path.lineTo(nx, ny)
                        cx = nx
                        cy = ny
                    }
                    lastCmd = 'L'
                    pc2x = null
                    pqx = null
                    cmd = if (rel) 'l' else 'L'
                }
                'H' -> {
                    while (hasNum()) {
                        val nx = x(num())
                        path.lineTo(nx, cy)
                        cx = nx
                    }
                    lastCmd = 'L'
                    pc2x = null
                    pqx = null
                    cmd = if (rel) 'h' else 'H'
                }
                'V' -> {
                    while (hasNum()) {
                        val ny = y(num())
                        path.lineTo(cx, ny)
                        cy = ny
                    }
                    lastCmd = 'L'
                    pc2x = null
                    pqx = null
                    cmd = if (rel) 'v' else 'V'
                }
                'C' -> {
                    while (hasNum()) {
                        val x1 = x(num())
                        val y1 = y(num())
                        val x2 = x(num())
                        val y2 = y(num())
                        val nx = x(num())
                        val ny = y(num())
                        path.cubicTo(x1, y1, x2, y2, nx, ny)
                        pc2x = x2
                        pc2y = y2
                        cx = nx
                        cy = ny
                    }
                    lastCmd = 'C'
                    pqx = null
                    cmd = if (rel) 'c' else 'C'
                }
                'S' -> {
                    while (hasNum()) {
                        val x1: Float
                        val y1: Float
                        if ((lastCmd == 'C' || lastCmd == 'S') && pc2x != null && pc2y != null) {
                            x1 = 2 * cx - pc2x!!
                            y1 = 2 * cy - pc2y!!
                        } else {
                            x1 = cx
                            y1 = cy
                        }
                        val x2 = x(num())
                        val y2 = y(num())
                        val nx = x(num())
                        val ny = y(num())
                        path.cubicTo(x1, y1, x2, y2, nx, ny)
                        pc2x = x2
                        pc2y = y2
                        cx = nx
                        cy = ny
                        lastCmd = 'S'
                    }
                    pqx = null
                    cmd = if (rel) 's' else 'S'
                }
                'Q' -> {
                    while (hasNum()) {
                        val x1 = x(num())
                        val y1 = y(num())
                        val nx = x(num())
                        val ny = y(num())
                        path.quadraticTo(x1, y1, nx, ny)
                        pqx = x1
                        pqy = y1
                        cx = nx
                        cy = ny
                    }
                    lastCmd = 'Q'
                    pc2x = null
                    cmd = if (rel) 'q' else 'Q'
                }
                'T' -> {
                    while (hasNum()) {
                        val x1: Float
                        val y1: Float
                        if ((lastCmd == 'Q' || lastCmd == 'T') && pqx != null && pqy != null) {
                            x1 = 2 * cx - pqx!!
                            y1 = 2 * cy - pqy!!
                        } else {
                            x1 = cx
                            y1 = cy
                        }
                        val nx = x(num())
                        val ny = y(num())
                        path.quadraticTo(x1, y1, nx, ny)
                        pqx = x1
                        pqy = y1
                        cx = nx
                        cy = ny
                        lastCmd = 'T'
                    }
                    pc2x = null
                    cmd = if (rel) 't' else 'T'
                }
                'A' -> {
                    while (hasNum()) {
                        val rx = num()
                        val ry = num()
                        val rot = num()
                        val large = num() != 0f
                        val sweep = num() != 0f
                        val nx = x(num())
                        val ny = y(num())
                        if (rx == 0f || ry == 0f) {
                            path.lineTo(nx, ny)
                        } else if (cx != nx || cy != ny) {
                            val arc = arcCenter(cx, cy, rx, ry, rot, large, sweep, nx, ny)
                            path.arcTo(
                                Rect(
                                    arc.cx - arc.rx, arc.cy - arc.ry,
                                    arc.cx + arc.rx, arc.cy + arc.ry
                                ),
                                arc.startDeg,
                                arc.sweepDeg,
                                forceMoveTo = false
                            )
                        }
                        cx = nx
                        cy = ny
                    }
                    lastCmd = 'A'
                    pc2x = null
                    pqx = null
                    cmd = if (rel) 'a' else 'A'
                }
                else -> break
            }
        }
        return path
    }

    private class Arc(
        val cx: Float,
        val cy: Float,
        val rx: Float,
        val ry: Float,
        val startDeg: Float,
        val sweepDeg: Float
    )

    /** SVG spec F.6.5: endpoint parameterization -> center parameterization. */
    private fun arcCenter(
        x1: Float,
        y1: Float,
        rxIn: Float,
        ryIn: Float,
        rotDeg: Float,
        large: Boolean,
        sweep: Boolean,
        x2: Float,
        y2: Float
    ): Arc {
        val phi = Math.toRadians(rotDeg.toDouble())
        val cosF = cos(phi).toFloat()
        val sinF = sin(phi).toFloat()
        val dx = (x1 - x2) / 2f
        val dy = (y1 - y2) / 2f
        val x1p = cosF * dx + sinF * dy
        val y1p = -sinF * dx + cosF * dy
        var rx = abs(rxIn)
        var ry = abs(ryIn)
        val lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lam > 1f) {
            val s = sqrt(lam)
            rx *= s
            ry *= s
        }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var co = if (den != 0f) sqrt(maxOf(0f, num / den)) else 0f
        if (large == sweep) co = -co
        val cxp = co * rx * y1p / ry
        val cyp = -co * ry * x1p / rx
        val ccx = cosF * cxp - sinF * cyp + (x1 + x2) / 2f
        val ccy = sinF * cxp + cosF * cyp + (y1 + y2) / 2f

        fun angle(ux: Float, uy: Float, vx: Float, vy: Float): Float {
            val dot = ux * vx + uy * vy
            val len = hypot(ux, uy) * hypot(vx, vy)
            var a = Math.toDegrees(acos((dot / len).coerceIn(-1f, 1f).toDouble()))
            if (ux * vy - uy * vx < 0f) a = -a
            return a.toFloat()
        }

        val start = angle(1f, 0f, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var sweepA = angle(
            (x1p - cxp) / rx, (y1p - cyp) / ry,
            (-x1p - cxp) / rx, (-y1p - cyp) / ry
        )
        if (!sweep && sweepA > 0f) sweepA -= 360f
        if (sweep && sweepA < 0f) sweepA += 360f
        return Arc(ccx, ccy, rx, ry, start, sweepA)
    }

    private fun tokenize(data: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < data.length) {
            val ch = data[i]
            when {
                ch.isLetter() -> {
                    out.add(ch.toString())
                    i++
                }
                ch.isDigit() || ch == '-' || ch == '.' -> {
                    var j = i + 1
                    while (j < data.length && (data[j].isDigit() || data[j] == '.')) j++
                    out.add(data.substring(i, j))
                    i = j
                }
                else -> i++ // spaces and commas
            }
        }
        return out
    }
}

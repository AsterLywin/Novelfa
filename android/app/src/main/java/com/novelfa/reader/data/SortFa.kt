package com.novelfa.reader.data

import java.text.Collator
import java.util.Locale

object SortFa {
    private val collator: Collator = Collator.getInstance(Locale("fa", "IR"))

    fun compare(a: String, b: String): Int {
        val aChunks = split(a)
        val bChunks = split(b)
        val n = minOf(aChunks.size, bChunks.size)
        for (i in 0 until n) {
            val ca = aChunks[i]
            val cb = bChunks[i]
            val aNum = ca.all { it.isDigit() }
            val bNum = cb.all { it.isDigit() }
            val r = when {
                aNum && bNum -> compareNumeric(ca, cb)
                aNum -> -1
                bNum -> 1
                else -> collator.compare(ca, cb)
            }
            if (r != 0) return r
        }
        if (aChunks.size != bChunks.size) return aChunks.size - bChunks.size
        return collator.compare(a, b)
    }

    private fun compareNumeric(a: String, b: String): Int {
        val ta = a.trimStart('0')
        val tb = b.trimStart('0')
        if (ta.length != tb.length) return ta.length - tb.length
        val r = ta.compareTo(tb)
        if (r != 0) return r
        return a.length - b.length
    }

    private fun split(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var start = 0
        var digitRun = s[0].isDigit()
        for (i in 1 until s.length) {
            val d = s[i].isDigit()
            if (d != digitRun) {
                out += s.substring(start, i)
                start = i
                digitRun = d
            }
        }
        out += s.substring(start)
        return out
    }
}

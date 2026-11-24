package com.novelfa.reader.domain

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * XP guard, ported from EpubReader/Services/XpGuard.cs (Ali 2026-11-23).
 *
 * A separate file (`xpguard.dat`, with a `.bak`) next to the main prefs,
 * holding the highest total XP ever reached (`peak`), the highest title
 * index ever reached (`maxTitle`) and a copy of the ledger. Total XP in
 * this app never goes down, so when the main prefs are corrupt, rolled
 * back or half-written, XP and unlocked titles come back from here.
 * Every change and every detected drop is logged to `xpguard.log`
 * (capped at 200 KB) so anything odd stays traceable.
 *
 * The guard is inert until [init] is called with a directory (the app
 * calls it once in NovelfaApplication.onCreate with filesDir): before
 * that, [peak] reads 0 and [noteTotal]/[noteLedger]/[log] do nothing,
 * so unit tests using an in-memory RankPrefs never touch the disk.
 * [init] also resets the loaded state, so tests can point the guard at
 * a fresh temp dir per test.
 */
object XpGuard {
    private val gate = Any()
    private var dir: File? = null
    private var loaded = false
    private var peak = 0
    private var maxTitle = -1
    private var ledgerBackup = ""

    fun init(directory: File) {
        synchronized(gate) {
            dir = directory
            loaded = false
            peak = 0
            maxTitle = -1
            ledgerBackup = ""
        }
    }

    /**
     * Test-only: forget the directory and all loaded state, so a test
     * class that pointed the guard at a temp dir cannot leak a peak
     * into other test classes sharing the JVM.
     */
    fun resetForTest() {
        synchronized(gate) {
            dir = null
            loaded = false
            peak = 0
            maxTitle = -1
            ledgerBackup = ""
        }
    }

    private fun dataFile(): File? = dir?.let { File(it, "xpguard.dat") }
    private fun logFile(): File? = dir?.let { File(it, "xpguard.log") }

    private fun ensureLoaded() {
        synchronized(gate) {
            if (loaded) return
            loaded = true
            val data = dataFile() ?: return
            val bak = File(data.path + ".bak")
            for (path in listOf(data, bak)) {
                try {
                    if (!path.isFile) continue
                    for (line in path.readLines(Charsets.UTF_8)) {
                        val i = line.indexOf('=')
                        if (i <= 0) continue
                        val k = line.substring(0, i)
                        val v = line.substring(i + 1)
                        when (k) {
                            "peak" -> v.toIntOrNull()?.let { if (it > peak) peak = it }
                            "title" -> v.toIntOrNull()?.let { if (it > maxTitle) maxTitle = it }
                            "ledger" -> if (v.length > ledgerBackup.length) ledgerBackup = v
                        }
                    }
                } catch (_: Exception) {
                    // A corrupt guard file must never break the app;
                    // the .bak (or the prefs themselves) is the fallback.
                }
            }
        }
    }

    /** Highest total XP ever recorded (0 when the guard is not initialised). */
    val peakValue: Int
        get() {
            ensureLoaded()
            return synchronized(gate) { peak }
        }

    /** Highest XP title index ever reached (-1 when nothing recorded yet). */
    val maxTitleValue: Int
        get() {
            ensureLoaded()
            return synchronized(gate) { maxTitle }
        }

    /** Longest ledger copy the guard holds ("" when none). */
    val ledgerBackupValue: String
        get() {
            ensureLoaded()
            return synchronized(gate) { ledgerBackup }
        }

    private fun persistLocked() {
        val data = dataFile() ?: return
        try {
            val text = "peak=$peak\ntitle=$maxTitle\nledger=$ledgerBackup\n"
            val tmp = File(data.path + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            val bak = File(data.path + ".bak")
            if (data.isFile) {
                // Atomic-ish swap in the same directory: old -> .bak, tmp -> dat.
                if (bak.exists()) bak.delete()
                data.renameTo(bak)
            }
            tmp.renameTo(data)
        } catch (_: Exception) {
        }
    }

    /** A new total was recorded (only ever moves up). */
    fun noteTotal(total: Int) {
        if (dir == null) return
        ensureLoaded()
        synchronized(gate) {
            val title = XpRank.levelFor(total).titleIndex
            var changed = false
            if (total > peak) {
                peak = total
                changed = true
            }
            if (title > maxTitle) {
                maxTitle = title
                changed = true
            }
            if (changed) persistLocked()
        }
    }

    /**
     * A new ledger version was written. It replaces the backup only when
     * it differs and is at least half as long as the current backup — a
     * truncated or corrupt write must never overwrite a good backup.
     */
    fun noteLedger(serialized: String) {
        if (dir == null) return
        ensureLoaded()
        synchronized(gate) {
            if (serialized != ledgerBackup && serialized.length >= ledgerBackup.length / 2) {
                ledgerBackup = serialized
                persistLocked()
            }
        }
    }

    /** Append a timestamped line to xpguard.log (deleted past 200 KB). */
    fun log(text: String) {
        val file = logFile() ?: return
        synchronized(gate) {
            try {
                if (file.isFile && file.length() > 200_000) file.delete()
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file.appendText("$stamp  $text\n", Charsets.UTF_8)
            } catch (_: Exception) {
            }
        }
    }
}

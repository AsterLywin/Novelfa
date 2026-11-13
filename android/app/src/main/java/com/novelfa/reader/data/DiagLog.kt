package com.novelfa.reader.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local diagnostic log (stability item 1): Novelfa is offline with no
 * channel home, so without this there is no way to debug a user's
 * report at all. Errors and warnings — uncaught crashes, import
 * failures, parser fallbacks — are written both to logcat (as before)
 * and to a small capped file the user can share from Settings >
 * Diagnostics (share button, Telegram to the developer).
 *
 * Vital events only: only [w] and [e] write to the file — no debug or
 * info tracing, so the file never fills up with noise. The file is
 * capped at [MAX_BYTES]; when a write would pass it, the oldest lines
 * are dropped until it is back under [TRIM_TO_BYTES] (the header line
 * is pinned). A stack trace is capped at [MAX_TRACE_LINES] lines.
 *
 * Every call is safe from any thread and never throws: a logging
 * failure must never become a second crash. Before [init] (unit tests,
 * or a crash before Application.onCreate) the file half is silently
 * skipped and only logcat is written.
 *
 * Opt-in (Settings > Diagnostics, default OFF): [fileEnabled] gates
 * the file half — while it is off, [w]/[e] still go to logcat (normal
 * Android logging) but nothing is written to the file, so the app
 * collects no diagnostics until the user turns it on. Turning it off
 * deletes the log file, so the state is exactly a fresh install (no
 * file, no entries); turning it back on re-creates the file with its
 * header. The saved choice lives in PrefsStore; Application applies
 * it once at startup and Settings applies it on every toggle.
 */
object DiagLog {
    /** Hard cap for the log file; the trim target is below this. */
    const val MAX_BYTES = 256 * 1024L
    private const val TRIM_TO_BYTES = 192 * 1024L
    private const val MAX_TRACE_LINES = 50

    private val lock = Any()
    @Volatile
    private var logFile: File? = null

    /** File logging gate: false until the user opts in (default OFF). */
    @Volatile
    private var fileEnabled = false

    /** Application context (from [init]), kept to re-create the file
     * with its header when the user re-enables logging. */
    private var appContext: Context? = null

    /** App-side entry point: call once from Application.onCreate. */
    fun init(context: Context) {
        val file = File(File(context.filesDir, "diagnostics"), "novelfa-diag.log")
        synchronized(lock) {
            appContext = context.applicationContext
            val fresh = !file.isFile
            logFile = file
            runCatching {
                file.parentFile?.mkdirs()
                if (fresh) {
                    file.createNewFile()
                    appendLocked(headerLine(context))
                }
            }
        }
    }

    /**
     * Turns file logging on or off. Disabling deletes the log file
     * (fresh-install state); enabling re-creates it with its header
     * when it is missing. Never throws.
     */
    fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            fileEnabled = enabled
            val f = logFile ?: return
            runCatching {
                if (!enabled) {
                    f.delete()
                } else if (!f.isFile) {
                    f.parentFile?.mkdirs()
                    f.createNewFile()
                    val ctx = appContext
                    if (ctx != null) appendLocked(headerLine(ctx))
                }
            }
        }
    }

    /** Whether file logging is currently enabled. */
    fun isEnabled(): Boolean = fileEnabled

    /** Test seam: the app passes the real file; tests pass a temp file. */
    fun init(file: File) {
        synchronized(lock) {
            logFile = file
            runCatching {
                file.parentFile?.mkdirs()
                if (!file.isFile) file.createNewFile()
            }
        }
    }

    /**
     * Installs the uncaught-exception handler: the crash (with its full
     * stack trace) is written to the log file synchronously — the process
     * is dying, so the write must land before the previous handler kills
     * it — then the previous handler runs exactly as before, so the app
     * still crashes normally. Never swallows a crash.
     */
    fun installUncaughtHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                e("NovelfaCrash", "Uncaught exception on ${thread.name}", e)
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** The log file, or null before [init]. */
    fun logFile(): File? = logFile

    /** Current size in bytes (0 before init / when the file is gone). */
    fun sizeBytes(): Long =
        runCatching { logFile?.takeIf { it.isFile }?.length() ?: 0L }.getOrDefault(0L)

    /**
     * Share intent for the log file, or null when there is nothing to
     * share (no file yet, or an empty file). The caller wraps it in
     * Intent.createChooser and starts it.
     */
    fun shareIntent(context: Context): Intent? {
        val file = logFile?.takeIf { it.isFile && it.length() > 0 } ?: return null
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Novelfa diagnostic log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun w(tag: String, msg: String, e: Throwable? = null) {
        Log.w(tag, msg, e)
        append("W", tag, msg, e)
    }

    fun e(tag: String, msg: String, e: Throwable? = null) {
        Log.e(tag, msg, e)
        append("E", tag, msg, e)
    }

    @Suppress("DEPRECATION")
    private fun headerLine(context: Context): String {
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("?")
        return "=== Novelfa $version | Android ${Build.VERSION.RELEASE} " +
            "(SDK ${Build.VERSION.SDK_INT}) | ${Build.MANUFACTURER} ${Build.MODEL} ==="
    }

    private fun append(level: String, tag: String, msg: String, e: Throwable?) {
        // Opt-in gate, re-checked under the lock so a write racing the
        // disable moment cannot resurrect the deleted file.
        if (logFile == null) return
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = StringBuilder()
            .append(time).append(' ').append(level).append('/').append(tag)
            .append(": ").append(msg).append('\n')
        if (e != null) {
            entry.append(e.stackTraceToString().lines().take(MAX_TRACE_LINES).joinToString("\n"))
            if (!entry.endsWith("\n")) entry.append('\n')
        }
        synchronized(lock) {
            if (!fileEnabled) return
            runCatching {
                val f = logFile ?: return
                trimIfNeededLocked(f, entry.length)
                f.appendText(entry.toString())
            }
        }
    }

    private fun appendLocked(line: String) {
        val f = logFile ?: return
        runCatching { f.appendText(line + "\n") }
    }

    /** Drops the oldest lines (never the header) until the file + the
     * incoming entry fit under [TRIM_TO_BYTES]. */
    private fun trimIfNeededLocked(f: File, incomingBytes: Int) {
        if (!f.isFile) return
        if (f.length() + incomingBytes <= MAX_BYTES) return
        val lines = f.readLines()
        if (lines.size <= 1) return
        var bytes = f.length() + incomingBytes
        var drop = if (lines[0].startsWith("===")) 1 else 0
        while (drop < lines.size - 1 && bytes > TRIM_TO_BYTES) {
            bytes -= lines[drop].toByteArray(Charsets.UTF_8).size + 1
            drop++
        }
        if (drop > 0) {
            f.writeText(lines.drop(drop).joinToString("\n", postfix = "\n"))
        }
    }
}

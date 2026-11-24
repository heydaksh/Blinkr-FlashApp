package com.example.android_flashlight.flashalerts

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Logcat (tag FlashAlerts) + ring-buffer file. Never log notification content. */
object FlashDiagnostics : Diag {
    const val TAG = "FlashAlerts"
    private const val MAX_LINES = 500
    private const val TRIM_AT = 600

    private val lock = Any()
    private var file: File? = null
    private var lineCount = -1
    private var started = false
    private val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    fun init(ctx: Context) {
        synchronized(lock) {
            if (file == null) file = File(ctx.applicationContext.filesDir, "flash_alerts_log.txt")
            if (!started) {
                started = true
                log("PROCESS_START", "uptimeMs" to SystemClock.uptimeMillis())
            }
        }
    }

    override fun log(event: String, vararg fields: Pair<String, Any?>) {
        val line = buildString {
            synchronized(lock) { append(fmt.format(Date())) }
            append(" | ").append(event)
            if (fields.isNotEmpty()) append(" | ").append(fields.joinToString(" ") { "${it.first}=${it.second}" })
        }
        try { Log.i(TAG, line) } catch (_: Throwable) { }
        append(line)
    }

    private fun append(line: String) = synchronized(lock) {
        val f = file ?: return
        try {
            if (lineCount < 0) lineCount = if (f.exists()) f.readLines().size else 0
            f.appendText(line + "\n")
            lineCount++
            if (lineCount > TRIM_AT) {
                val keep = f.readLines().takeLast(MAX_LINES)
                f.writeText(keep.joinToString("\n", postfix = "\n"))
                lineCount = keep.size
            }
        } catch (_: Exception) {
        }
    }

    fun read(): String = synchronized(lock) {
        try { file?.takeIf { it.exists() }?.readText() ?: "" } catch (_: Exception) { "" }
    }

    fun clear() = synchronized(lock) {
        try { file?.writeText("") } catch (_: Exception) { }
        lineCount = 0
    }

    fun hasSince(event: String): Boolean = read().contains("| $event")
}

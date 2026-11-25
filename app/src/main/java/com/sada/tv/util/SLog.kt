package com.sada.tv.util

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Small ring log kept in memory and in files/sada-log.txt; exportable to Downloads. */
object SLog {
    private const val MAX_LINES = 1500
    private val lines = ArrayDeque<String>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "sada-log").apply { isDaemon = true } }
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "sada-log.txt")
        synchronized(lines) {
            if (f.exists()) runCatching { f.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) } }
        }
        file = f
        io.execute { rewrite() }
    }

    fun i(msg: String) {
        val line = synchronized(fmt) { fmt.format(Date()) } + "  " + msg
        android.util.Log.i("Sada", msg)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        val f = file ?: return
        io.execute {
            runCatching {
                f.appendText(line + "\n")
                if (f.length() > 600_000) rewrite()
            }
        }
    }

    fun e(msg: String, t: Throwable? = null) = i("ERROR $msg" + (t?.let { " : $it" } ?: ""))

    private fun rewrite() {
        val f = file ?: return
        val snapshot = synchronized(lines) { lines.toList() }
        runCatching { f.writeText(snapshot.joinToString("\n", postfix = "\n")) }
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }

    /** Writes the log to Downloads; returns the file name. */
    fun export(context: Context): String {
        val name = "sada-log-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".txt"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Downloads not available")
        context.contentResolver.openOutputStream(uri)!!.use { it.write(text().toByteArray()) }
        return name
    }
}

package com.sada.tv.adb

import android.content.Context
import com.sada.tv.util.Prefs
import com.sada.tv.util.SLog
import java.io.IOException

/**
 * Shell-user access to the receiver through its own adbd. Finds the port, keeps
 * one authenticated connection, reconnects when it drops.
 */
class ShellAccess(context: Context, private val prefs: Prefs) {

    private val client = AdbClient(context)
    @Volatile private var conn: AdbClient.Connection? = null

    /** Port that worked, or 0 if none yet. */
    val port get() = prefs.adbPort
    val connected get() = conn != null

    /** Connects (once) and returns a working connection. Throws with an Arabic reason. */
    @Synchronized
    fun connection(): AdbClient.Connection {
        conn?.let { return it }
        val tried = LinkedHashSet<Int>()
        if (prefs.adbPort > 0) tried += prefs.adbPort
        tried += 5555
        for (key in listOf("service.adb.tcp.port", "persist.adb.tcp.port")) {
            sysprop(key)?.toIntOrNull()?.takeIf { it > 0 }?.let { tried += it }
        }
        var last: Exception? = null
        for (p in tried) {
            try {
                val c = client.connect("127.0.0.1", p)
                val id = c.shell("id")
                SLog.i("adb on port $p: ${id.trim()}")
                if (!id.contains("uid=2000") && !id.contains("uid=0")) throw IOException("unexpected adb user: $id")
                prefs.adbPort = p
                conn = c
                return c
            } catch (e: Exception) {
                SLog.i("adb port $p: ${e.message}")
                last = e
            }
        }
        val reason = last?.message?.takeIf { it.any { ch -> ch in '؀'..'ۿ' } }
        throw IOException(
            reason ?: "لم يُعثر على ADB شبكي في الريسيفر. فعّل «تصحيح ADB عبر الشبكة» من خيارات المطوّر، " +
                "أو نفّذ مرة واحدة من الكمبيوتر: adb tcpip 5555",
        )
    }

    /** Runs a shell command, reconnecting once if the link dropped. */
    fun shell(command: String, timeoutMs: Int = 15_000): String {
        return try {
            connection().shell(command, timeoutMs)
        } catch (e: IOException) {
            drop()
            connection().shell(command, timeoutMs)
        }
    }

    @Synchronized
    fun drop() {
        conn?.close()
        conn = null
    }

    private fun sysprop(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, key) as String
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

package com.sada.tv.capture

import android.content.Context
import com.sada.tv.adb.ShellAccess
import com.sada.tv.util.SLog
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The receiver's real output, captured by [com.sada.tv.helper.CaptureHelper]
 * running as the shell user (started over the box's own adb). This reaches the
 * channel's sound even when the TV app plays it inside the audio HAL, where
 * normal app capture sees nothing.
 *
 * The capture is the final mix, so the station is in it too: [hearsStation].
 */
class HelperCaptureSource(
    private val context: Context,
    private val shell: ShellAccess,
    val mode: CaptureProtocol.Mode,
) : TvAudioSource {

    override val label get() = mode.label
    override val hearsStation = true

    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var client: Socket? = null
    private var worker: Thread? = null

    override fun start(sink: TvAudioSource.Sink, onFailure: (Throwable) -> Unit) {
        stop()
        running = true
        val srv = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        srv.soTimeout = 12_000
        server = srv
        val token = newToken()
        launch(srv.localPort, token)
        val sock = try {
            srv.accept()
        } catch (e: Exception) {
            stop()
            throw IOException("لم يبدأ مساعد الالتقاط (${mode.label})")
        }
        client = sock
        val input = DataInputStream(BufferedInputStream(sock.getInputStream(), 32 * 1024))
        sock.soTimeout = 10_000
        if (input.readUTF() != token) { stop(); throw IOException("capture peer failed the token check") }
        when (input.readInt()) {
            CaptureProtocol.MAGIC_ERR -> { val m = input.readUTF(); stop(); throw IOException("تعذّر الالتقاط: $m") }
            CaptureProtocol.MAGIC_PCM -> {}
            else -> { stop(); throw IOException("bad capture header") }
        }
        val rate = input.readInt()
        val channels = input.readInt()
        SLog.i("helper capture running: ${mode.label}")
        worker = thread(name = "sada-helper-cap", isDaemon = true) {
            try {
                var shorts = ShortArray(0)
                var bytes = ByteArray(0)
                while (running) {
                    val t = input.readLong()
                    if (t < 0) throw IOException(input.readUTF())
                    val n = input.readInt()
                    if (n <= 0 || n > 1_000_000) throw IOException("bad block $n")
                    if (bytes.size < n) { bytes = ByteArray(n); shorts = ShortArray(n / 2) }
                    input.readFully(bytes, 0, n)
                    var i = 0; var j = 0
                    while (i + 1 < n) {
                        shorts[j++] = ((bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)).toShort()
                        i += 2
                    }
                    sink.onBlock(shorts, j, channels, rate, t)
                }
            } catch (e: Exception) {
                if (running) { SLog.e("helper capture", e); onFailure(e) }
            }
        }
    }

    private fun launch(port: Int, token: String) {
        val apk = context.applicationInfo.sourceDir
        val cmd = "CLASSPATH=$apk setsid nohup app_process /system/bin " +
            "com.sada.tv.helper.CaptureHelper ${mode.id} $port $token >/dev/null 2>&1 &"
        shell.shell(cmd, 10_000)
    }

    override fun stop() {
        running = false
        client?.runCatching { close() }
        server?.runCatching { close() }
        worker?.join(1000)
        worker = null
        client = null
        server = null
    }

    private fun newToken(): String {
        val b = ByteArray(12)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * Runs [mode] for [ms] and returns the average level in dBFS, or throws.
         * Used by the receiver scan to find the method that carries the channel.
         */
        fun probe(context: Context, shell: ShellAccess, mode: CaptureProtocol.Mode, ms: Long): Double {
            val src = HelperCaptureSource(context, shell, mode)
            var sum = 0.0
            var count = 0L
            val start = System.currentTimeMillis()
            src.start({ pcm, n, _, _, _ ->
                if (System.currentTimeMillis() - start > 600) {
                    for (i in 0 until n) { val v = pcm[i] / 32768.0; sum += v * v }
                    count += n
                }
            }) { }
            try {
                Thread.sleep(ms)
            } finally {
                src.stop()
            }
            if (count == 0L) throw IOException("لم تصل عيّنات")
            val rms = sqrt(sum / count)
            return 20 * log10(rms + 1e-9)
        }
    }
}

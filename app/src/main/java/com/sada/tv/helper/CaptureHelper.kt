package com.sada.tv.helper

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.sada.tv.capture.CaptureProtocol
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket

/**
 * Runs as the shell user, started over adb:
 *
 *   CLASSPATH=<apk> app_process /system/bin com.sada.tv.helper.CaptureHelper <mode> <port> <token>
 *
 * Records what the receiver actually plays (the HAL's echo reference, or the
 * remote submix) and streams it to Sada over 127.0.0.1:<port>, proving itself
 * with the one-time <token>. Exits when Sada closes the connection.
 */
object CaptureHelper {

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = CaptureProtocol.Mode.byId(args.getOrNull(0)) ?: CaptureProtocol.Mode.ECHO_48_STEREO
        val port = args.getOrNull(1)?.toIntOrNull() ?: return
        val token = args.getOrNull(2) ?: return
        val socket = try {
            Socket(InetAddress.getByName("127.0.0.1"), port).apply { tcpNoDelay = true }
        } catch (e: Exception) {
            System.err.println("sada helper: cannot reach app: $e")
            return
        }
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 16 * 1024))
        try {
            out.writeUTF(token)
            val rec = try {
                build(mode)
            } catch (t: Throwable) {
                out.writeInt(CaptureProtocol.MAGIC_ERR)
                out.writeUTF("${mode.id}: ${t.javaClass.simpleName}: ${t.message}")
                out.flush()
                return
            }
            stream(rec, mode, out)
        } catch (_: Exception) {
            // app closed the socket
        } finally {
            runCatching { socket.close() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun build(mode: CaptureProtocol.Mode): AudioRecord {
        val ctx = HelperContext.create()
        val mask = if (mode.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.Builder()
            .setSampleRate(mode.rate)
            .setChannelMask(mask)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val attrsBuilder = AudioAttributes.Builder()
        AudioAttributes.Builder::class.java
            .getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
            .invoke(attrsBuilder, mode.preset)
        val attrs = attrsBuilder.build()
        val b = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(mode.rate * mode.channels * 2) // ~1 s
        AudioRecord.Builder::class.java
            .getMethod("setAudioAttributes", AudioAttributes::class.java)
            .invoke(b, attrs)
        if (Build.VERSION.SDK_INT >= 31) b.setContext(ctx)
        val rec = b.build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("not initialised")
        }
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            throw IllegalStateException("recording did not start")
        }
        return rec
    }

    private fun stream(rec: AudioRecord, mode: CaptureProtocol.Mode, out: DataOutputStream) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        out.writeInt(CaptureProtocol.MAGIC_PCM)
        out.writeInt(mode.rate)
        out.writeInt(mode.channels)
        out.flush()
        val frames = mode.rate / 50 // 20 ms
        val bytes = ByteArray(frames * mode.channels * 2)
        val blockMs = frames * 1000L / mode.rate
        val ts = AudioTimestamp()
        var pos = 0L
        var useTs: Boolean? = null
        try {
            while (true) {
                var off = 0
                while (off < bytes.size) {
                    val r = rec.read(bytes, off, bytes.size - off)
                    if (r <= 0) {
                        out.writeLong(-1L)
                        out.writeUTF("read $r")
                        out.flush()
                        return
                    }
                    off += r
                }
                if (useTs == null) useTs = rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS
                val first = if (useTs == true && rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                    (pos - ts.framePosition) * 1000 / mode.rate + ts.nanoTime / 1_000_000
                } else {
                    SystemClock.elapsedRealtime() - blockMs
                }
                out.writeLong(first)
                out.writeInt(bytes.size)
                out.write(bytes)
                out.flush()
                pos += frames
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }
}

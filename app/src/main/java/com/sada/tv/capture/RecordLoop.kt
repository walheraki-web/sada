package com.sada.tv.capture

import android.media.AudioRecord
import android.media.AudioTimestamp
import android.os.Process
import android.os.SystemClock
import java.io.IOException

/**
 * Reads an [AudioRecord] in fixed blocks and timestamps each block from the
 * record's own BOOTTIME clock (same clock as elapsedRealtime). If the platform
 * gives no timestamp, the first frame is taken as one block before "now" — the
 * moment the block finished — and that choice is kept for the whole session so
 * the timeline never jumps between the two methods.
 */
internal class RecordLoop(
    private val rec: AudioRecord,
    private val rate: Int,
    private val channels: Int,
    private val blockFrames: Int,
) {
    @Volatile var running = true

    fun run(sink: TvAudioSource.Sink) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val ts = AudioTimestamp()
        val buf = ShortArray(blockFrames * channels)
        val blockMs = blockFrames * 1000L / rate
        var framePos = 0L
        var useTimestamps: Boolean? = null
        while (running) {
            var off = 0
            while (off < buf.size && running) {
                val r = rec.read(buf, off, buf.size - off)
                if (r <= 0) throw IOException("AudioRecord read $r")
                off += r
            }
            if (!running) break
            if (useTimestamps == null) {
                useTimestamps = rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS
            }
            val first = if (useTimestamps == true &&
                rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS
            ) {
                (framePos - ts.framePosition) * 1000 / rate + ts.nanoTime / 1_000_000
            } else {
                SystemClock.elapsedRealtime() - blockMs
            }
            sink.onBlock(buf, buf.size, channels, rate, first)
            framePos += blockFrames
        }
    }
}

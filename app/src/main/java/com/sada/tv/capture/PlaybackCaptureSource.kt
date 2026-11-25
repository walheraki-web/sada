package com.sada.tv.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import com.sada.tv.util.SLog
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Captures the live channel's sound with Android's standard playback capture
 * (MediaProjection + AudioPlaybackCaptureConfiguration): no root, no ADB, only the
 * one-time consent. Sada's own uid is excluded, so the station never leaks into
 * the reference and can never be matched against itself.
 */
class PlaybackCaptureSource(private val projection: MediaProjection) : TvAudioSource {

    override val label = "صوت القناة (التقاط النظام) 16kHz"
    override val hearsStation = false

    private var record: AudioRecord? = null
    private var loop: RecordLoop? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    override fun start(sink: TvAudioSource.Sink, onFailure: (Throwable) -> Unit) {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(Process.myUid())
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, RATE * 2))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IOException("تعذّر فتح التقاط صوت القناة على هذا الجهاز")
        }
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            throw IOException("لم يبدأ التقاط صوت القناة")
        }
        record = rec
        val l = RecordLoop(rec, RATE, 1, RATE / 50)
        loop = l
        SLog.i("playback capture started")
        worker = thread(name = "sada-playcap", isDaemon = true) {
            try { l.run(sink) } catch (t: Throwable) { if (l.running) { SLog.e("playback capture", t); onFailure(t) } }
        }
    }

    override fun stop() {
        loop?.running = false
        worker?.join(1000)
        worker = null
        record?.runCatching { stop() }
        record?.release()
        record = null
        loop = null
    }

    private companion object { const val RATE = 16000 }
}

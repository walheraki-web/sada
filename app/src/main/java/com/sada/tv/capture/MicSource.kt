package com.sada.tv.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.sada.tv.util.SLog
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Hears the TV through a microphone (for boxes where playback capture is
 * refused). The station is audible to the mic too, so the engine silences it
 * while measuring ([hearsStation]).
 */
class MicSource : TvAudioSource {

    override val label = "الميكروفون 16kHz"
    override val hearsStation = true

    private var record: AudioRecord? = null
    private var loop: RecordLoop? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    override fun start(sink: TvAudioSource.Sink, onFailure: (Throwable) -> Unit) {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE * 2),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IOException("لا يوجد ميكروفون متاح")
        }
        rec.startRecording()
        record = rec
        val l = RecordLoop(rec, RATE, 1, RATE / 50)
        loop = l
        SLog.i("mic capture started")
        worker = thread(name = "sada-mic", isDaemon = true) {
            try { l.run(sink) } catch (t: Throwable) { if (l.running) { SLog.e("mic capture", t); onFailure(t) } }
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

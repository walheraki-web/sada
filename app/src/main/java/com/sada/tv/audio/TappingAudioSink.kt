package com.sada.tv.audio

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wraps the real sink and taps the station PCM as it is handed over, with the
 * elapsedRealtime moment it will be HEARD. The tap sits before the delay line, so
 * the line's current delay is added: measuring never needs to change the delay.
 */
@UnstableApi
class TappingAudioSink(
    sink: AudioSink,
    private val line: DelayLine,
    /** (pcm, count, channels, sampleRate, heardAtMs) */
    private val onTap: (ShortArray, Int, Int, Int, Double) -> Unit,
) : ForwardingAudioSink(sink) {

    @Volatile var tapping = false

    private var sampleRate = 0
    private var channels = 0
    private var bytesPerFrame = 0
    private var pcm16 = false
    private var positionInUnit = 0L
    private var lastPts = Long.MIN_VALUE

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        sampleRate = inputFormat.sampleRate
        channels = inputFormat.channelCount
        pcm16 = inputFormat.pcmEncoding == C.ENCODING_PCM_16BIT
        bytesPerFrame = channels * 2
        positionInUnit = 0
        lastPts = Long.MIN_VALUE
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (presentationTimeUs != lastPts) {
            lastPts = presentationTimeUs
            positionInUnit = 0
        }
        val startPos = buffer.position()
        val want = tapping && pcm16 && sampleRate > 0 && channels > 0
        val dup = if (want) buffer.duplicate() else null
        val wallNow = if (want) SystemClock.elapsedRealtime() else 0L
        val playedUs = if (want) getCurrentPositionUs(false) else C.TIME_UNSET

        val accepted = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val consumed = buffer.position() - startPos

        if (want && consumed >= bytesPerFrame && playedUs != C.TIME_UNSET && playedUs != Long.MIN_VALUE) {
            val aheadUs = positionInUnit / bytesPerFrame * 1_000_000 / sampleRate +
                presentationTimeUs - playedUs
            if (aheadUs in -150_000..5_000_000) {
                val d = dup!!
                d.position(startPos)
                d.limit(startPos + consumed)
                // PCM is in native (little-endian) order; a fresh buffer is BIG_ENDIAN,
                // so read through an explicitly native-ordered view.
                val shorts = ShortArray(consumed / 2)
                d.slice().order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
                onTap(shorts, shorts.size, channels, sampleRate, wallNow + aheadUs / 1000.0 + line.currentMs)
            }
        }
        positionInUnit += consumed
        return accepted
    }

    override fun flush() {
        positionInUnit = 0
        lastPts = Long.MIN_VALUE
        super.flush()
    }
}

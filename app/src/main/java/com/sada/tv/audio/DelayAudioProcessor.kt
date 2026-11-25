package com.sada.tv.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 wrapper around [DelayLine]: delays the station's PCM by [DelayLine.targetMs].
 * Formats other than 16-bit PCM pass through untouched (never fails playback).
 */
@UnstableApi
class DelayAudioProcessor(val line: DelayLine) : BaseAudioProcessor() {

    private var inShorts = ShortArray(0)
    private var outShorts = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        line.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val count = bytes / 2
        if (inShorts.size < count) { inShorts = ShortArray(count); outShorts = ShortArray(count) }
        inputBuffer.slice().order(ByteOrder.nativeOrder()).asShortBuffer().get(inShorts, 0, count)
        inputBuffer.position(inputBuffer.position() + count * 2)
        line.process(inShorts, outShorts, count)
        val out = replaceOutputBuffer(count * 2)
        out.asShortBuffer().put(outShorts, 0, count)
        out.position(0)
        out.limit(count * 2)
    }

    override fun onFlush() { line.reset() }

    override fun onReset() { line.release() }
}

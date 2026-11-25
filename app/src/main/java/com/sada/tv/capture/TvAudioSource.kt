package com.sada.tv.capture

/**
 * A source of the live channel's sound, delivered as timestamped PCM blocks.
 * The one hard requirement is the timestamp: the elapsedRealtime moment the first
 * frame of a block was played by the receiver.
 */
interface TvAudioSource {

    fun interface Sink {
        /** [pcm] holds [count] interleaved 16-bit samples; [firstFrameTimeMs] as above. */
        fun onBlock(pcm: ShortArray, count: Int, channels: Int, sampleRate: Int, firstFrameTimeMs: Long)
    }

    /** Short description for the UI and log. */
    val label: String

    /**
     * True when the station itself can leak into this source (a microphone), so
     * the station must be silenced while measuring.
     */
    val hearsStation: Boolean

    /** Starts delivery. Throws if the source cannot start. [onFailure] fires if it dies later. */
    fun start(sink: Sink, onFailure: (Throwable) -> Unit)

    /** Stops delivery and releases resources. Safe to call more than once. */
    fun stop()
}

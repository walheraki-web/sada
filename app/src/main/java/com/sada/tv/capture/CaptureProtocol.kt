package com.sada.tv.capture

/**
 * Wire format between the shell-user capture helper and Sada (TCP on 127.0.0.1;
 * app-owned unix sockets are not reachable from the shell domain under SELinux).
 *
 *   UTF token (one-time secret given to the helper on its command line)
 *   int magic (PCM or ERR); ERR: UTF message
 *   PCM: int sampleRate, int channels; then blocks of
 *        long firstFrameBootMs (<0 ends: UTF reason), int byteCount, byte[] pcm16le
 */
object CaptureProtocol {
    const val MAGIC_PCM = 0x53504331 // "SPC1"
    const val MAGIC_ERR = 0x53455231 // "SER1"

    /** Capture methods tried in order. Preset 1997 = ECHO_REFERENCE, 8 = REMOTE_SUBMIX. */
    enum class Mode(val id: String, val label: String, val preset: Int, val rate: Int, val channels: Int) {
        ECHO_48_STEREO("echo48s", "Echo 48k ستيريو", 1997, 48000, 2),
        ECHO_48_MONO("echo48m", "Echo 48k مونو", 1997, 48000, 1),
        ECHO_44_STEREO("echo44s", "Echo 44.1k ستيريو", 1997, 44100, 2),
        SUBMIX_48_STEREO("submix48s", "Remote submix 48k", 8, 48000, 2);

        companion object {
            fun byId(id: String?) = entries.firstOrNull { it.id == id }
        }
    }
}

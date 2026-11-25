package com.sada.tv.audio

/**
 * Frame-accurate delay line for interleaved 16-bit PCM (pure Kotlin, unit-tested).
 *
 * The read position advances one frame per frame written, so content is never
 * repeated:
 *  - growing the delay by X fades out, plays X frames of silence, fades back in
 *    (the station "waits" X), and
 *  - shrinking it by Y crossfades from the current position to Y frames ahead
 *    (the station "skips" Y),
 * both without clicks. After [reset] the line starts with the target delay of
 * silence. All calls come from one audio thread except [targetMs], which may be
 * set from any thread.
 */
class DelayLine {

    /** Requested delay in milliseconds; applied at the next [process] call. */
    @Volatile var targetMs: Int = 0

    /** Delay actually in force (ms), for timestamping what is heard. */
    @Volatile var currentMs: Double = 0.0
        private set

    private var rate = 0
    private var ch = 0
    private var fadeFrames = 0
    private var cap = 0               // ring capacity in frames
    private var ring = ShortArray(0)
    private var w = 0L                // absolute index of the next frame to write
    private var r = 0L                // absolute index of the next frame to read

    private enum class State { NORMAL, FADE_OUT, GAP, FADE_IN, XFADE }
    private var state = State.NORMAL
    private var k = 0                 // progress within a fade
    private var gapLeft = 0L
    private var skip = 0L

    val configured get() = rate > 0

    fun configure(sampleRate: Int, channels: Int) {
        rate = sampleRate
        ch = channels
        fadeFrames = maxOf(1, sampleRate / 50) // 20 ms
        reset()
    }

    /** Clears history; output restarts with [targetMs] of silence. */
    fun reset() {
        if (rate == 0) return
        val d = msToFrames(targetMs)
        ensureCapacity(d)
        java.util.Arrays.fill(ring, 0)
        w = 0
        r = -d
        state = State.NORMAL
        k = 0; gapLeft = 0; skip = 0
        currentMs = d * 1000.0 / rate
    }

    fun release() {
        ring = ShortArray(0); cap = 0; rate = 0; ch = 0
    }

    /**
     * Delays [count] interleaved samples from [input] into [output] (same count).
     * [count] must be a whole number of frames.
     */
    fun process(input: ShortArray, output: ShortArray, count: Int) {
        val frames = count / ch
        if (frames <= 0) return
        if (state == State.NORMAL) startChangeIfNeeded()
        ensureCapacity(delayFrames() + gapLeft + skip + frames + fadeFrames)

        var o = 0
        for (f in 0 until frames) {
            // write
            val base = slot(w) * ch
            for (c in 0 until ch) ring[base + c] = input[f * ch + c]
            w++
            // read
            when (state) {
                State.NORMAL -> {
                    for (c in 0 until ch) output[o + c] = sample(r, c)
                    r++
                }
                State.FADE_OUT -> {
                    val g = 1f - k.toFloat() / fadeFrames
                    for (c in 0 until ch) output[o + c] = (sample(r, c) * g).toInt().toShort()
                    r++; k++
                    if (k >= fadeFrames) { state = if (gapLeft > 0) State.GAP else State.FADE_IN; k = 0 }
                }
                State.GAP -> {
                    for (c in 0 until ch) output[o + c] = 0
                    gapLeft--
                    if (gapLeft <= 0) { state = State.FADE_IN; k = 0 }
                }
                State.FADE_IN -> {
                    val g = k.toFloat() / fadeFrames
                    for (c in 0 until ch) output[o + c] = (sample(r, c) * g).toInt().toShort()
                    r++; k++
                    if (k >= fadeFrames) { state = State.NORMAL; k = 0 }
                }
                State.XFADE -> {
                    val g = k.toFloat() / fadeFrames
                    for (c in 0 until ch) {
                        val a = sample(r, c); val b = sample(r + skip, c)
                        output[o + c] = (a * (1f - g) + b * g).toInt().toShort()
                    }
                    r++; k++
                    if (k >= fadeFrames) { r += skip; skip = 0; state = State.NORMAL; k = 0 }
                }
            }
            o += ch
        }
        currentMs = (delayFrames() + gapLeft - skip) * 1000.0 / rate
    }

    private fun startChangeIfNeeded() {
        val diff = msToFrames(targetMs) - delayFrames()
        when {
            diff > 0 -> { gapLeft = diff; state = State.FADE_OUT; k = 0 }
            diff < 0 -> { skip = -diff; state = State.XFADE; k = 0 }
        }
    }

    private fun delayFrames(): Long = w - r

    private fun msToFrames(ms: Int): Long = Math.round(ms.coerceAtLeast(0).toDouble() * rate / 1000.0)

    private fun slot(i: Long): Int = Math.floorMod(i, cap.toLong()).toInt()

    private fun sample(i: Long, c: Int): Short =
        if (i < 0 || i >= w || i < w - cap) 0 else ring[slot(i) * ch + c]

    private fun ensureCapacity(framesNeeded: Long) {
        val need = framesNeeded + 2L * fadeFrames + 1024
        if (need <= cap) return
        val newCap = maxOf(need, cap * 2L).coerceAtMost(Int.MAX_VALUE / maxOf(ch, 1).toLong()).toInt()
        val grown = ShortArray(newCap * ch)
        // keep the most recent frames at their absolute indices
        val keep = minOf(w, cap.toLong())
        var i = w - keep
        while (i < w) {
            val from = slot(i) * ch
            val to = Math.floorMod(i, newCap.toLong()).toInt() * ch
            for (c in 0 until ch) grown[to + c] = ring[from + c]
            i++
        }
        ring = grown
        cap = newCap
    }
}

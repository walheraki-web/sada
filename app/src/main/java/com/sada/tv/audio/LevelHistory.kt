package com.sada.tv.audio

import kotlin.math.log10
import kotlin.math.sqrt

/** Rolling audio level (dBFS) per block, stamped with when it is heard. For the live graph. */
class LevelHistory(private val capacity: Int = 1024) {
    private val times = LongArray(capacity)
    private val levels = FloatArray(capacity)
    private var head = 0
    private var size = 0

    @Synchronized
    fun push(pcm: ShortArray, count: Int, atMs: Long) {
        if (count <= 0) return
        var s = 0.0
        for (i in 0 until count) { val v = pcm[i] / 32768.0; s += v * v }
        val db = (20 * log10(sqrt(s / count) + 1e-9)).toFloat()
        times[head] = atMs
        levels[head] = db
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /** Levels (dBFS) of the blocks heard within [fromMs, toMs], oldest first, as (time, dB) pairs. */
    @Synchronized
    fun window(fromMs: Long, toMs: Long): List<Pair<Long, Float>> {
        val out = ArrayList<Pair<Long, Float>>()
        for (i in 0 until size) {
            val idx = (head - size + i + capacity) % capacity
            val t = times[idx]
            if (t in fromMs..toMs) out += t to levels[idx]
        }
        return out
    }

    @Synchronized
    fun clear() { size = 0; head = 0 }
}

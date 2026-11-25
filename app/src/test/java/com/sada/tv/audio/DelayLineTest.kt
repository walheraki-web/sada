package com.sada.tv.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The delay line must hold the station back by exactly the requested time, never
 * repeat content, and change delay without clicks.
 */
class DelayLineTest {

    private val rate = 1000 // 1 frame = 1 ms keeps the arithmetic readable
    private val block = 10

    /** Feeds a ramp 1,2,3... (mono) and returns the output stream. */
    private fun run(line: DelayLine, totalFrames: Int, onBlock: (Int) -> Unit = {}): ShortArray {
        val out = ShortArray(totalFrames)
        val inBuf = ShortArray(block); val outBuf = ShortArray(block)
        var n = 0
        while (n < totalFrames) {
            onBlock(n)
            for (i in 0 until block) inBuf[i] = ((n + i) % 30000 + 1).toShort()
            line.process(inBuf, outBuf, block)
            System.arraycopy(outBuf, 0, out, n, block)
            n += block
        }
        return out
    }

    @Test fun zeroDelayPassesThrough() {
        val l = DelayLine().apply { configure(rate, 1) }
        val out = run(l, 200)
        for (i in 0 until 200) assertEquals((i + 1).toShort(), out[i])
    }

    @Test fun initialDelayIsSilenceThenExactlyShifted() {
        val l = DelayLine().apply { targetMs = 120; configure(rate, 1) }
        val out = run(l, 400)
        for (i in 0 until 120) assertEquals(0.toShort(), out[i])
        for (i in 120 until 400) assertEquals((i - 120 + 1).toShort(), out[i])
        assertEquals(120.0, l.currentMs, 0.5)
    }

    @Test fun growingInsertsSilenceWithoutRepeating() {
        val l = DelayLine().apply { configure(rate, 1) }
        val out = run(l, 1000) { n -> if (n == 300) l.targetMs = 200 }
        // well after the change, the output is the input delayed by exactly 200
        for (i in 700 until 1000) assertEquals((i - 200 + 1).toShort(), out[i])
        // before the change it is untouched; nothing is replayed after it
        for (i in 0 until 300) assertEquals((i + 1).toShort(), out[i])
        assertEquals(200.0, l.currentMs, 0.5)
    }

    @Test fun shrinkingSkipsAheadExactly() {
        val l = DelayLine().apply { targetMs = 300; configure(rate, 1) }
        val out = run(l, 1500) { n -> if (n == 600) l.targetMs = 50 }
        for (i in 900 until 1500) assertEquals((i - 50 + 1).toShort(), out[i])
        assertEquals(50.0, l.currentMs, 0.5)
    }

    @Test fun changesAreClickFree() {
        // A loud constant signal: during a change no sample may jump by more than
        // one fade step (20 ms ramp = 1/20 of full scale per frame at 1 kHz).
        val l = DelayLine().apply { configure(rate, 1) }
        val inBuf = ShortArray(block) { 20000 }
        val outBuf = ShortArray(block)
        var prev: Short? = null
        var maxJump = 0
        for (b in 0 until 200) {
            if (b == 50) l.targetMs = 300
            if (b == 120) l.targetMs = 0
            l.process(inBuf, outBuf, block)
            for (v in outBuf) {
                prev?.let { maxJump = maxOf(maxJump, kotlin.math.abs(v - it)) }
                prev = v
            }
        }
        assertTrue("max jump $maxJump", maxJump <= 20000 / 20 + 1)
    }

    @Test fun stereoStaysFrameAligned() {
        val l = DelayLine().apply { targetMs = 7; configure(rate, 2) }
        val inBuf = ShortArray(20) { if (it % 2 == 0) (it / 2 + 1).toShort() else (-(it / 2 + 1)).toShort() }
        val outBuf = ShortArray(20)
        l.process(inBuf, outBuf, 20)
        for (f in 7 until 10) {
            assertEquals((f - 7 + 1).toShort(), outBuf[f * 2])
            assertEquals((-(f - 7 + 1)).toShort(), outBuf[f * 2 + 1])
        }
    }
}

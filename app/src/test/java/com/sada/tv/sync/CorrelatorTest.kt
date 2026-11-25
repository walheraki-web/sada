package com.sada.tv.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * Synthetic end-to-end check of the sync engine: the same "speech-like" content
 * is fed as a TV reference (48 kHz stereo, with noise and EQ) and as a station
 * (44.1 kHz mono, different gain, low-passed, timestamp jitter) shifted by a
 * known lag. The engine must recover the lag within 30 ms.
 */
class CorrelatorTest {

    /** Syllable-like bursts: random formant-ish tones gated at ~4-6 Hz. */
    private fun content(seconds: Double, seed: Long): (Double) -> Double {
        val rnd = Random(seed)
        val n = (seconds * 5).toInt() + 10
        val start = DoubleArray(n); val dur = DoubleArray(n)
        val f1 = DoubleArray(n); val f2 = DoubleArray(n); val amp = DoubleArray(n)
        var t = 0.0
        for (i in 0 until n) {
            t += 0.08 + rnd.nextDouble() * 0.25
            start[i] = t; dur[i] = 0.05 + rnd.nextDouble() * 0.18
            f1[i] = 200 + rnd.nextDouble() * 700; f2[i] = 900 + rnd.nextDouble() * 2400
            amp[i] = 0.2 + rnd.nextDouble() * 0.8
        }
        return { time ->
            var v = 0.0
            for (i in 0 until n) {
                val d = time - start[i]
                if (d >= 0 && d < dur[i]) {
                    val env = sin(PI * d / dur[i])
                    v += amp[i] * env * (sin(2 * PI * f1[i] * time) + 0.6 * sin(2 * PI * f2[i] * time))
                }
            }
            v * 0.25
        }
    }

    private fun feed(
        track: FeatureTrack, src: (Double) -> Double, rate: Int, ch: Int, gain: Double,
        noise: Double, lowpass: Boolean, startMs: Double, seconds: Double,
        contentOffsetS: Double, jitterMs: Double, seed: Long,
    ) {
        val rnd = Random(seed)
        val block = rate / 50
        var lp = 0.0
        var frame = 0L
        val total = (seconds * rate).toLong()
        while (frame < total) {
            val buf = ShortArray(block * ch)
            for (i in 0 until block) {
                val tt = (frame + i).toDouble() / rate + contentOffsetS
                var v = src(tt) * gain + rnd.nextGaussian() * noise
                if (lowpass) { lp += 0.35 * (v - lp); v = lp }
                val s = (v * 32767).coerceIn(-32768.0, 32767.0).toInt().toShort()
                for (c in 0 until ch) buf[i * ch + c] = s
            }
            val ts = startMs + frame * 1000.0 / rate + (rnd.nextDouble() - 0.5) * 2 * jitterMs
            track.push(buf, buf.size, ch, rate, ts)
            frame += block
        }
    }

    private fun measure(lagMs: Double): Correlator.Result {
        val src = content(80.0, 7)
        val tv = FeatureTrack()
        val st = FeatureTrack()
        val t0 = 1_000_000.0
        // TV: content time c heard at wall t0 + c
        feed(tv, src, 48000, 2, 1.0, 0.01, false, t0, 40.0, 0.0, 3.0, 1)
        // Station: content time c heard at wall t0 + c + lag
        feed(st, src, 44100, 1, 0.6, 0.02, true, t0 + lagMs - 15000, 60.0, -15.0, 3.0, 2)
        val k0 = (t0 / FeatureTrack.FRAME_MS).toLong()
        return Correlator.correlate(tv, st, k0 + 50, k0 + 50 + 600, -20000, 20000)!!
    }

    @Test fun findsPositiveLag() {
        val r = measure(3370.0)
        println(r)
        assertEquals(3370.0, r.lagMs, 30.0)
        assertTrue(r.z > 8 && r.margin > 2.5)
    }

    @Test fun findsNegativeLag() {
        val r = measure(-7810.0)
        println(r)
        assertEquals(-7810.0, r.lagMs, 30.0)
        assertTrue(r.z > 8 && r.margin > 2.5)
    }

    @Test fun unrelatedAudioHasNoConfidentMatch() {
        val tv = FeatureTrack(); val st = FeatureTrack()
        val t0 = 1_000_000.0
        feed(tv, content(60.0, 11), 48000, 2, 1.0, 0.01, false, t0, 40.0, 0.0, 2.0, 3)
        feed(st, content(60.0, 99), 44100, 1, 1.0, 0.01, false, t0 - 15000, 60.0, 0.0, 2.0, 4)
        val k0 = (t0 / FeatureTrack.FRAME_MS).toLong()
        val r = Correlator.correlate(tv, st, k0 + 50, k0 + 650, -20000, 20000)!!
        println("unrelated: $r")
        assertTrue(!SyncPolicy.accept(r))
    }

    /** Long lags (internet station far behind the satellite) inside the wide range. */
    @Test fun findsLongPositiveLagInWideRange() {
        val lag = 41_230.0
        val src = content(90.0, 21)
        val tv = FeatureTrack(); val st = FeatureTrack()
        val t0 = 2_000_000.0
        feed(tv, src, 16000, 1, 1.0, 0.01, false, t0, 45.0, 0.0, 3.0, 5)
        feed(st, src, 22050, 2, 0.7, 0.02, true, t0 + lag - 20000, 60.0, -20.0, 3.0, 6)
        val k0 = (t0 / FeatureTrack.FRAME_MS).toLong()
        val r = Correlator.correlate(tv, st, k0 + 1000, k0 + 1900,
            -SyncPolicy.MAX_NEGATIVE_LAG_MS, SyncPolicy.MAX_POSITIVE_LAG_MS)!!
        println("long: $r")
        assertEquals(lag, r.lagMs, 30.0)
        assertTrue(SyncPolicy.accept(r))
    }
}

package com.sada.tv.sync

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns timestamped PCM into a timeline of "onset" feature vectors, one every
 * [FRAME_MS] milliseconds, indexed by absolute time (frame k covers
 * k*FRAME_MS .. (k+1)*FRAME_MS on the elapsedRealtime clock).
 *
 * Pure Kotlin (no Android types) so it can be unit tested on the JVM.
 *
 * Each feature is the positive change of log band energy in [BANDS] bands
 * between 100 Hz and 3.8 kHz. Speech and commentary produce sharp, specific
 * onset patterns, which are robust to volume, EQ and codec differences between
 * the TV broadcast and the internet station.
 */
class FeatureTrack(private val capacityFrames: Int = 8192) {

    companion object {
        const val FRAME_MS = 20L
        const val BANDS = 24
        private const val RATE = 8000
        private const val WIN = 256
        private const val RESYNC_MS = 120.0    // timestamp jump that restarts the timeline

        private val HANN = FloatArray(WIN) { (0.5 - 0.5 * cos(2 * PI * it / WIN)).toFloat() }
        private val BAND_EDGES: IntArray = run {
            // log-spaced band edges in FFT bins (bin = 31.25 Hz at 8 kHz / 256)
            val lo = 100.0
            val hi = 3800.0
            IntArray(BANDS + 1) { i ->
                val f = lo * Math.pow(hi / lo, i.toDouble() / BANDS)
                max(1, min(WIN / 2, (f / (RATE.toDouble() / WIN)).roundToLong().toInt()))
            }.also { e -> for (i in 1 until e.size) if (e[i] <= e[i - 1]) e[i] = e[i - 1] + 1 }
        }
    }

    private val frames = Array(capacityFrames) { FloatArray(BANDS) }
    private val frameIdx = LongArray(capacityFrames) { Long.MIN_VALUE }
    private val energy = FloatArray(capacityFrames) // onset strength, for "is there sound" checks

    /** Highest frame index written, or Long.MIN_VALUE. */
    @Volatile var newest: Long = Long.MIN_VALUE
        private set

    // --- streaming state ---
    private var srcRate = 0
    private var baseMs = 0.0          // wall time of resampled sample 0
    private var outCount = 0L         // resampled samples produced since baseMs
    private var srcCount = 0L         // source frames consumed since baseMs
    private var acc = 0.0             // box filter accumulator
    private var accN = 0
    private var phase = 0.0           // fractional position in source frames for next output sample
    private val ring = FloatArray(WIN)
    private var ringPos = 0
    private var ringFill = 0
    private var nextK = Long.MIN_VALUE
    private val prevLog = FloatArray(BANDS)
    private var havePrev = false
    private val re = DoubleArray(WIN)
    private val im = DoubleArray(WIN)

    @Synchronized
    fun reset() {
        java.util.Arrays.fill(frameIdx, Long.MIN_VALUE)
        newest = Long.MIN_VALUE
        srcRate = 0
        havePrev = false
        ringFill = 0
    }

    /**
     * @param pcm interleaved 16-bit samples
     * @param channels 1 or 2
     * @param firstFrameTimeMs time (elapsedRealtime ms, may be fractional) when the
     *        first frame of this block is/was heard
     */
    @Synchronized
    fun push(pcm: ShortArray, count: Int, channels: Int, sampleRate: Int, firstFrameTimeMs: Double) {
        if (sampleRate <= 0 || channels < 1) return
        val frames = count / channels
        if (frames <= 0) return
        if (srcRate != sampleRate) {
            restart(sampleRate, firstFrameTimeMs)
        } else {
            val expected = baseMs + srcCount * 1000.0 / srcRate
            val err = firstFrameTimeMs - expected
            if (abs(err) > RESYNC_MS) restart(sampleRate, firstFrameTimeMs)
            else baseMs += err * 0.02 // slow clock tracking (jitter filter)
        }
        val step = srcRate.toDouble() / RATE
        for (f in 0 until frames) {
            var s = 0f
            for (c in 0 until channels) s += pcm[f * channels + c]
            s /= (channels * 32768f)
            // anti-alias low-pass (biquad, 3.6 kHz) before decimating to 8 kHz
            val y = lb0 * s + lz1
            lz1 = lb1 * s - la1 * y + lz2
            lz2 = lb2 * s - la2 * y
            acc += y; accN++
            srcCount++
            if (srcCount >= phase + step) {
                phase += step
                val v = (acc / accN).toFloat()
                acc = 0.0; accN = 0
                pushSample(v)
            }
        }
    }

    // low-pass state/coefficients (Direct Form II transposed)
    private var lb0 = 1.0; private var lb1 = 0.0; private var lb2 = 0.0
    private var la1 = 0.0; private var la2 = 0.0
    private var lz1 = 0.0; private var lz2 = 0.0

    private fun designLowPass(rate: Int) {
        val fc = min(3600.0, rate * 0.45)
        val w0 = 2 * PI * fc / rate
        val alpha = sin(w0) / (2 * 0.7071)
        val c = cos(w0)
        val a0 = 1 + alpha
        lb0 = (1 - c) / 2 / a0; lb1 = (1 - c) / a0; lb2 = lb0
        la1 = -2 * c / a0; la2 = (1 - alpha) / a0
        lz1 = 0.0; lz2 = 0.0
    }

    private fun restart(rate: Int, t0: Double) {
        if (rate != srcRate) designLowPass(rate)
        srcRate = rate
        baseMs = t0
        srcCount = 0
        outCount = 0
        phase = 0.0
        acc = 0.0; accN = 0
        ringFill = 0; ringPos = 0; nextK = Long.MIN_VALUE
        havePrev = false
    }

    private fun pushSample(v: Float) {
        ring[ringPos] = v
        ringPos = (ringPos + 1) % WIN
        if (ringFill < WIN) ringFill++
        outCount++
        if (ringFill < WIN) return
        // Window ends at outCount; analyse exactly when its center crosses the
        // absolute 20 ms grid, so both streams are sampled at the same instants.
        val centerMs = baseMs + (outCount - WIN / 2) * 1000.0 / RATE
        if (nextK == Long.MIN_VALUE) nextK = floor(centerMs / FRAME_MS).toLong() + 1
        if (centerMs >= nextK * FRAME_MS) {
            analyze(nextK)
            nextK = floor(centerMs / FRAME_MS).toLong() + 1
        }
    }

    private fun analyze(k: Long) {
        for (i in 0 until WIN) {
            re[i] = (ring[(ringPos + i) % WIN] * HANN[i]).toDouble()
            im[i] = 0.0
        }
        fft(re, im)
        val logE = FloatArray(BANDS)
        for (b in 0 until BANDS) {
            var e = 0.0
            for (bin in BAND_EDGES[b] until BAND_EDGES[b + 1]) e += re[bin] * re[bin] + im[bin] * im[bin]
            logE[b] = ln(e + 1e-9).toFloat()
        }
        if (!havePrev) {
            System.arraycopy(logE, 0, prevLog, 0, BANDS)
            havePrev = true
            return
        }
        val slot = Math.floorMod(k, capacityFrames.toLong()).toInt()
        val v = frames[slot]
        var strength = 0f
        for (b in 0 until BANDS) {
            val d = logE[b] - prevLog[b]
            v[b] = if (d > 0f) d else 0f
            strength += v[b]
            prevLog[b] = logE[b]
        }
        energy[slot] = strength
        frameIdx[slot] = k
        if (k > newest) newest = k
    }

    /** Copies frame [k] into [out]; false if that frame is not stored. */
    @Synchronized
    fun get(k: Long, out: FloatArray): Boolean {
        val slot = Math.floorMod(k, capacityFrames.toLong()).toInt()
        if (frameIdx[slot] != k) return false
        System.arraycopy(frames[slot], 0, out, 0, BANDS)
        return true
    }

    /** Fraction of frames in [from, to) that are stored and carry sound. */
    @Synchronized
    fun activity(from: Long, to: Long): Double {
        if (to <= from) return 0.0
        var n = 0
        var active = 0
        var k = from
        while (k < to) {
            val slot = Math.floorMod(k, capacityFrames.toLong()).toInt()
            if (frameIdx[slot] == k) {
                n++
                if (energy[slot] > 0.5f) active++
            }
            k++
        }
        return if (n == 0) 0.0 else active.toDouble() / (to - from)
    }

    private fun fft(xr: DoubleArray, xi: DoubleArray) {
        val n = xr.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = xr[i]; xr[i] = xr[j]; xr[j] = t
                t = xi[i]; xi[i] = xi[j]; xi[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = xr[i + k]; val ui = xi[i + k]
                    val vr = xr[i + k + len / 2] * cr - xi[i + k + len / 2] * ci
                    val vi = xr[i + k + len / 2] * ci + xi[i + k + len / 2] * cr
                    xr[i + k] = ur + vr; xi[i + k] = ui + vi
                    xr[i + k + len / 2] = ur - vr; xi[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

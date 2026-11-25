package com.sada.tv.sync

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds the time offset between the TV reference and the station.
 *
 * lag > 0  : the station is heard LATER than the TV (the picture must be delayed)
 * lag <= 0 : the station is heard EARLIER (delay the station audio by -lag)
 *
 * Score(lag) = mean over TV frames t of cosine(tvVec(t), stVec(t+lag)), using
 * per-band time-mean-removed vectors. Confidence is the robust z-score of the best
 * lag against the whole curve; "rival" is the best z-score at least
 * [RIVAL_GAP_FRAMES] away from the winner.
 */
object Correlator {

    data class Result(
        val lagMs: Double,
        val z: Double,
        val rivalZ: Double,
        val overlapSeconds: Double,
    ) {
        val margin get() = z - rivalZ
        override fun toString() =
            "lag %.0f ms z %.1f rival %.1f on %.1f s".format(lagMs, z, rivalZ, overlapSeconds)
    }

    private const val RIVAL_GAP_FRAMES = 6
    private const val B = FeatureTrack.BANDS

    /**
     * @param tvFrom..tvTo TV frame window (absolute frame indices)
     * @param minLagMs..maxLagMs search range
     */
    fun correlate(
        tv: FeatureTrack,
        st: FeatureTrack,
        tvFrom: Long,
        tvTo: Long,
        minLagMs: Long,
        maxLagMs: Long,
    ): Result? {
        val f = FeatureTrack.FRAME_MS
        val n = (tvTo - tvFrom).toInt()
        if (n < 100) return null
        val minLag = Math.floorDiv(minLagMs, f)
        val maxLag = Math.floorDiv(maxLagMs, f)
        val lags = (maxLag - minLag + 1).toInt()
        if (lags < 20) return null

        // Load TV window, normalised.
        val tvBuf = Array(n) { FloatArray(B) }
        val tvOk = BooleanArray(n)
        for (i in 0 until n) tvOk[i] = tv.get(tvFrom + i, tvBuf[i])
        if (!normalise(tvBuf, tvOk)) return null

        // Load the station span covering every lag, normalised over the same span.
        val stFrom = tvFrom + minLag
        val stLen = n + lags
        val stBuf = Array(stLen) { FloatArray(B) }
        val stOk = BooleanArray(stLen)
        for (i in 0 until stLen) stOk[i] = st.get(stFrom + i, stBuf[i])
        if (!normalise(stBuf, stOk)) return null

        // score[l] = sum_i <tv(i), st(i+l)>, computed for all lags at once in the
        // frequency domain (sum over bands of conj(TV_b) * ST_b, one inverse FFT).
        var size = 1
        while (size < stLen + n) size = size shl 1
        val accRe = DoubleArray(size); val accIm = DoubleArray(size)
        val aRe = DoubleArray(size); val aIm = DoubleArray(size)
        val bRe = DoubleArray(size); val bIm = DoubleArray(size)
        for (k in 0 until B) {
            java.util.Arrays.fill(aRe, 0.0); java.util.Arrays.fill(aIm, 0.0)
            java.util.Arrays.fill(bRe, 0.0); java.util.Arrays.fill(bIm, 0.0)
            for (i in 0 until n) if (tvOk[i]) aRe[i] = tvBuf[i][k].toDouble()
            for (j in 0 until stLen) if (stOk[j]) bRe[j] = stBuf[j][k].toDouble()
            fft(aRe, aIm, false); fft(bRe, bIm, false)
            for (q in 0 until size) {
                // conj(a) * b
                accRe[q] += aRe[q] * bRe[q] + aIm[q] * bIm[q]
                accIm[q] += aRe[q] * bIm[q] - aIm[q] * bRe[q]
            }
        }
        fft(accRe, accIm, true)
        // Pair counts per lag, the same way, from the validity masks.
        java.util.Arrays.fill(aRe, 0.0); java.util.Arrays.fill(aIm, 0.0)
        java.util.Arrays.fill(bRe, 0.0); java.util.Arrays.fill(bIm, 0.0)
        for (i in 0 until n) if (tvOk[i]) aRe[i] = 1.0
        for (j in 0 until stLen) if (stOk[j]) bRe[j] = 1.0
        fft(aRe, aIm, false); fft(bRe, bIm, false)
        val cRe = DoubleArray(size); val cIm = DoubleArray(size)
        for (q in 0 until size) {
            cRe[q] = aRe[q] * bRe[q] + aIm[q] * bIm[q]
            cIm[q] = aRe[q] * bIm[q] - aIm[q] * bRe[q]
        }
        fft(cRe, cIm, true)

        val score = DoubleArray(lags) { Double.NaN }
        val count = IntArray(lags)
        for (l in 0 until lags) {
            val c = Math.round(cRe[l]).toInt()
            count[l] = c
            if (c >= n / 3) score[l] = accRe[l] / sqrt(c.toDouble())
        }

        // Robust z-scores
        val valid = score.filter { !it.isNaN() }.sorted()
        if (valid.size < 20) return null
        val med = valid[valid.size / 2]
        val dev = valid.map { abs(it - med) }.sorted()
        val mad = dev[dev.size / 2] * 1.4826 + 1e-9

        var best = -1
        for (l in 0 until lags) if (!score[l].isNaN() && (best < 0 || score[l] > score[best])) best = l
        if (best < 0) return null
        var rival = Double.NEGATIVE_INFINITY
        for (l in 0 until lags) {
            if (score[l].isNaN() || abs(l - best) < RIVAL_GAP_FRAMES) continue
            rival = max(rival, score[l])
        }

        // Parabolic refinement around the peak for sub-frame precision.
        var offset = 0.0
        if (best in 1 until lags - 1 && !score[best - 1].isNaN() && !score[best + 1].isNaN()) {
            val y0 = score[best - 1]; val y1 = score[best]; val y2 = score[best + 1]
            val den = y0 - 2 * y1 + y2
            if (den < 0) offset = min(0.5, max(-0.5, 0.5 * (y0 - y2) / den))
        }
        val lagFrames = minLag + best + offset
        return Result(
            lagMs = lagFrames * f,
            z = (score[best] - med) / mad,
            rivalZ = if (rival.isInfinite()) 0.0 else (rival - med) / mad,
            overlapSeconds = count[best] * f / 1000.0,
        )
    }

    /** Removes the per-band mean over the window and scales each frame to unit length. */
    private fun normalise(buf: Array<FloatArray>, ok: BooleanArray): Boolean {
        val mean = DoubleArray(B)
        var n = 0
        for (i in buf.indices) if (ok[i]) { n++; for (k in 0 until B) mean[k] += buf[i][k] }
        if (n < 50) return false
        for (k in 0 until B) mean[k] /= n
        for (i in buf.indices) {
            if (!ok[i]) continue
            val v = buf[i]
            var s = 0f
            for (k in 0 until B) { v[k] = (v[k] - mean[k]).toFloat(); s += v[k] * v[k] }
            val norm = sqrt(s)
            if (norm < 1e-6f) { ok[i] = false; continue }
            for (k in 0 until B) v[k] /= norm
        }
        return true
    }

    /** In-place radix-2 FFT; [inverse] also scales by 1/n. */
    private fun fft(xr: DoubleArray, xi: DoubleArray, inverse: Boolean) {
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
            val ang = (if (inverse) 2 else -2) * Math.PI / len
            val wr = Math.cos(ang); val wi = Math.sin(ang)
            val half = len / 2
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until half) {
                    val p = i + k; val q = p + half
                    val vr = xr[q] * cr - xi[q] * ci
                    val vi = xr[q] * ci + xi[q] * cr
                    xr[q] = xr[p] - vr; xi[q] = xi[p] - vi
                    xr[p] += vr; xi[p] += vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { xr[i] /= n; xi[i] /= n }
    }
}

package com.sada.tv.sync

import android.os.SystemClock
import com.sada.tv.util.SLog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Measures and corrects the offset between the live channel and the station.
 *
 * Fixes over the Bilal engine:
 *  - the station delay is never zeroed: the tap already reports the moment the
 *    station is HEARD (delay included), so measuring is inaudible and a failed or
 *    cancelled sync leaves everything as it was;
 *  - the search range grows with the data (up to +60 s / -20 s), so short lags
 *    lock in seconds and long ones are still found;
 *  - a result counts only when confirmed by an independent window (>= 6 s apart);
 *  - corrections use the receiver's own timeshift (pause, wait exactly, resume),
 *    verified by checking that the channel really went silent, then a refine pass
 *    measures the residual and trims it;
 *  - while playing, a light check every few minutes and after every station stall
 *    keeps it in sync.
 *
 * All work runs on one background thread; [Host] hides Android/player details.
 */
class SyncEngine(
    private val host: Host,
    private val tv: FeatureTrack,
    private val station: FeatureTrack,
) {
    interface Host {
        val stationUrl: String?
        /** Station delay requested / actually in force (ms). */
        var audioDelayMs: Int
        val calibMs: Int
        /** True if the channel source can hear the station (mic). */
        val sourceHearsStation: Boolean
        val tvMuted: Boolean
        fun setTapping(on: Boolean)
        /** Station volume 0..1 (blocking hop to the player thread). */
        fun setStationVolume(v: Float)
        fun stationVolume(): Float
        fun muteTv(mute: Boolean): Boolean
        fun pauseTv(ms: Long): Boolean
        fun rememberDelay(ms: Int)
        fun addTimeshift(ms: Long)
        val muteAfterSync: Boolean
        fun status(text: String)
        fun result(text: String, ok: Boolean)
    }

    private val exec = Executors.newSingleThreadExecutor { Thread(it, "sada-sync").apply { isDaemon = true } }
    /** Bumped to cancel whatever is running. */
    private val generation = AtomicInteger(0)

    @Volatile var busy = false; private set
    @Volatile var synced = false; private set

    private class Cancelled : RuntimeException()

    fun cancel() { generation.incrementAndGet() }

    /** Full search + correction + refine. */
    fun syncNow() = submit("sync") { gen -> fullSync(gen) }

    /** Light verification around the current sync point. */
    fun check() { if (!busy && synced) submit("check") { gen -> checkSync(gen) } }

    /** The station paused for [ms]: it is now late by that much. */
    fun onStationStall(ms: Long) {
        if (!synced || busy) return
        submit("stall") { gen ->
            val cur = host.audioDelayMs
            if (cur >= ms) {
                host.audioDelayMs = (cur - ms).toInt()
                host.rememberDelay(host.audioDelayMs)
                SLog.i("stall $ms ms compensated: delay $cur -> ${host.audioDelayMs}")
                host.status("عوّض توقّف المحطة ${ms} م.ث")
            } else {
                host.status("توقّفت المحطة ${ms} م.ث • إعادة ضبط")
                correct(gen, ms.toDouble(), refine = true)
            }
        }
    }

    fun forgetSync() { synced = false }

    private fun submit(name: String, job: (Int) -> Unit) {
        val gen = generation.incrementAndGet()
        exec.execute {
            if (gen != generation.get()) return@execute
            busy = true
            try { job(gen) } catch (_: Cancelled) {
                SLog.i("$name cancelled")
            } catch (t: Throwable) {
                SLog.e(name, t)
                host.result("لم تنجح المزامنة: ${t.message}", false)
            } finally {
                busy = false
                host.setTapping(false)
            }
        }
    }

    private fun check(gen: Int) { if (gen != generation.get()) throw Cancelled() }

    // ---------------------------------------------------------------- full sync

    private fun fullSync(gen: Int) {
        host.status("يقيس الفرق بين القناة والمحطة…")
        val m = withMeasuringSetup(gen, allowUnmute = true) {
            measure(gen, -SyncPolicy.MAX_NEGATIVE_LAG_MS, SyncPolicy.MAX_POSITIVE_LAG_MS, deadlineMs = 120_000)
        }
        if (m == null) {
            host.result("تعذّر إيجاد تطابق موثوق. تأكد أن القناة والمحطة تبثّان المحتوى نفسه وأعد المحاولة", false)
            return
        }
        SLog.i("sync measured lag ${m.lagMs.toInt()} ms (${m.result})")
        correct(gen, m.lagMs, refine = true)
    }

    private fun checkSync(gen: Int) {
        val m = withMeasuringSetup(gen, allowUnmute = false) {
            measure(gen, -3000, 3000, deadlineMs = 40_000)
        } ?: return
        SLog.i("check: residual ${m.lagMs.toInt()} ms")
        if (abs(m.lagMs) >= 80) correct(gen, m.lagMs, refine = false)
    }

    /**
     * Applies the plan for [lagMs]; with [refine], re-measures once or twice
     * afterwards and trims what is left.
     */
    private fun correct(gen: Int, lagMs: Double, refine: Boolean) {
        var lag = lagMs
        for (round in 0..2) {
            check(gen)
            val plan = SyncPolicy.plan(lag, host.audioDelayMs)
            if (plan == null) {
                finish(lag)
                return
            }
            plan.error?.let { host.result(it, false); return }
            if (plan.pauseMs > 0) {
                host.status("يؤخّر الصورة %.2f ث…".format(plan.pauseMs / 1000.0))
                if (!pauseVerified(plan.pauseMs)) {
                    host.result("أمر الإيقاف لم يوقف القناة؛ غيّر زر الإيقاف من الإعدادات ثم أعد المحاولة", false)
                    return
                }
                host.addTimeshift(plan.pauseMs)
            }
            host.audioDelayMs = plan.newDelayMs
            host.rememberDelay(plan.newDelayMs)
            SLog.i("round $round: pause ${plan.pauseMs} ms, station delay ${plan.newDelayMs} ms")
            if (!refine || round == 2) { finish(null); return }
            // Let the receiver and the delay line settle, then measure the residual.
            host.status("يتحقق من النتيجة…")
            sleep(gen, 2500)
            val r = withMeasuringSetup(gen, allowUnmute = true) {
                measure(gen, -2500, 2500, deadlineMs = 45_000)
            }
            if (r == null) { finish(null); return }
            SLog.i("refine: residual ${r.lagMs.toInt()} ms")
            lag = r.lagMs
        }
    }

    private fun finish(residualMs: Double?) {
        synced = true
        if (host.muteAfterSync && !host.tvMuted) host.muteTv(true)
        val tail = residualMs?.let { " (فرق متبقٍّ %+d م.ث)".format(it.toInt()) } ?: ""
        host.result("تمت المزامنة ✓$tail", true)
    }

    /**
     * Pauses the channel and checks that it actually went quiet (its sound stops
     * while the receiver is paused). Short pauses are trusted without the check.
     */
    private fun pauseVerified(ms: Long): Boolean {
        val f = FeatureTrack.FRAME_MS
        val before0 = now() / f
        val activityBefore = tv.activity(before0 - 150, before0) // last 3 s
        val start = now()
        if (!host.pauseTv(ms)) return false
        if (ms < 1500 || activityBefore < 0.08) return true
        Thread.sleep(400)
        val from = (start + 500) / f
        val to = (start + ms - 300) / f
        val during = tv.activity(from, to)
        SLog.i("pause check: activity before %.2f during %.2f".format(activityBefore, during))
        return during < activityBefore * 0.35
    }

    // ---------------------------------------------------------------- measuring

    /**
     * Prepares a clean measurement and undoes it afterwards: station tapping on,
     * station silenced if the source can hear it, and the channel unmuted if it
     * is muted and its sound does not reach the capture.
     */
    private fun <T> withMeasuringSetup(gen: Int, allowUnmute: Boolean, block: () -> T?): T? {
        val silenceStation = host.sourceHearsStation
        val vol = if (silenceStation) host.stationVolume() else 1f
        var unmuted = false
        try {
            tv.reset(); station.reset()
            host.setTapping(true)
            if (silenceStation) host.setStationVolume(0f)
            if (host.tvMuted) {
                sleep(gen, 3000)
                val k = now() / FeatureTrack.FRAME_MS
                if (tv.activity(k - 140, k) < 0.05) {
                    if (!allowUnmute) { SLog.i("channel muted and silent in capture; skip check"); return null }
                    host.muteTv(false)
                    unmuted = true
                    host.status("فُتح صوت القناة مؤقتًا للقياس")
                    tv.reset()
                }
            }
            return block()
        } finally {
            if (silenceStation) host.setStationVolume(vol)
            if (unmuted && host.muteAfterSync) host.muteTv(true)
        }
    }

    /**
     * Correlates over a growing window until two independent windows agree.
     * Lag range [minLagMs, maxLagMs] is widened as data accumulates.
     */
    private fun measure(gen: Int, minLagMs: Long, maxLagMs: Long, deadlineMs: Long): SyncPolicy.Measured? {
        val f = FeatureTrack.FRAME_MS
        val start = now()
        val k0 = start / f
        waitForContent(gen, start)
        val confirmed = ArrayList<SyncPolicy.Measured>()
        while (now() - start < deadlineMs) {
            sleep(gen, 500)
            val tvNew = tv.newest
            val stNew = station.newest
            if (tvNew == Long.MIN_VALUE || stNew == Long.MIN_VALUE) continue
            val haveFrames = min(tvNew, stNew) - k0
            // Positive lags need station data after the TV window: grow the range
            // with what has arrived, keeping at least 6 s of window.
            val maxLagFrames = min(maxLagMs / f, stNew - k0 - 300)
            if (maxLagFrames < 0 && maxLagMs > 0) continue
            val posLag = max(0L, maxLagFrames)
            val to = min(tvNew, stNew - posLag)
            val from = max(k0, to - 900) // up to 18 s window
            if (to - from < 300 || haveFrames < 300) continue
            val lo = max(minLagMs, -(to - k0) * f) // no point looking before the start
            val hi = if (maxLagMs > 0) posLag * f else maxLagMs
            if (hi - lo < 20 * f) continue
            val r = Correlator.correlate(tv, station, from, to, lo, hi) ?: continue
            host.status("يطابق الصوت… (مدى البحث %d ث)".format(hi / 1000))
            if (!SyncPolicy.accept(r)) continue
            val m = SyncPolicy.Measured(r.lagMs + host.calibMs, to * f, r)
            if (confirmed.any { SyncPolicy.confirms(it, m) }) return m
            confirmed += m
        }
        return null
    }

    private fun waitForContent(gen: Int, start: Long) {
        while (now() - start < 25_000) {
            check(gen)
            val okTv = tv.newest != Long.MIN_VALUE
            val okSt = station.newest != Long.MIN_VALUE
            if (okTv && okSt) return
            if (now() - start > 2500) {
                host.status(
                    "بانتظار الصوت • " + (if (okTv) "القناة ✓" else "القناة…") + " " +
                        (if (okSt) "المحطة ✓" else "المحطة…"),
                )
            }
            Thread.sleep(200)
        }
        if (tv.newest == Long.MIN_VALUE) throw IllegalStateException("لا يصل صوت القناة. فعّل «التقاط صوت القناة» وتأكد أن القناة تعمل")
        throw IllegalStateException("لم يبدأ صوت المحطة. تأكد من الشبكة")
    }

    private fun sleep(gen: Int, ms: Long) {
        val end = now() + ms
        while (now() < end) {
            check(gen)
            Thread.sleep(min(200L, max(1L, end - now())))
        }
        check(gen)
    }

    private fun now() = SystemClock.elapsedRealtime()
}

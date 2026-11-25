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
 *  - The station delay is never zeroed to measure: the tap reports when the
 *    station is HEARD (delay included), so measuring is inaudible and a failed
 *    or cancelled sync leaves everything as it was.
 *  - The search range grows with the data (up to +60 s / -20 s); a result counts
 *    only when an independent window (>= 6 s apart) confirms it.
 *  - The picture is held back either by pausing the channel (timeshift) or by the
 *    receiver's own picture-delay menu; a pause is verified by the channel going
 *    silent, and a refine pass measures and trims what is left.
 *  - [check] re-verifies a locked sync; repeated misses drop the lock so the
 *    next round searches again. A station stall is compensated at once.
 *
 * All work runs on one background thread; [Host] hides Android/player details.
 */
class SyncEngine(
    private val host: Host,
    private val tv: FeatureTrack,
    private val station: FeatureTrack,
) {
    interface Host {
        /** Station delay requested (ms). */
        var audioDelayMs: Int
        val calibMs: Int
        /** True if the channel capture also contains the station (final mix / mic). */
        val sourceHearsStation: Boolean
        val tvMuted: Boolean
        /** "pause" or "menu". */
        val videoMethod: String
        val menuVideoDelayMs: Int
        fun setMenuVideoDelay(ms: Int)
        fun setStationVolume(v: Float)
        fun stationVolume(): Float
        fun muteTv(mute: Boolean): Boolean
        fun pauseTv(ms: Long): Boolean
        fun rememberDelay(ms: Int)
        fun addTimeshift(ms: Long)
        /** Mute the channel once synced (the user listens to the station). */
        val muteAfterSync: Boolean
        fun status(text: String)
        fun result(text: String, ok: Boolean)
    }

    enum class Phase(val label: String) {
        IDLE("المزامنة متوقفة"),
        LISTENING("يستمع…"),
        MATCHING("يطابق…"),
        APPLYING("يطبّق…"),
        LOCKED("متزامن ✓"),
        NO_AUDIO("لا صوت"),
    }

    private val exec = Executors.newSingleThreadExecutor { Thread(it, "sada-sync").apply { isDaemon = true } }
    private val generation = AtomicInteger(0)

    @Volatile var busy = false; private set
    @Volatile var synced = false; private set
    @Volatile var phase = Phase.IDLE; private set
    /** Last measured offset (ms; + = station late), for display. */
    @Volatile var lastLagMs: Double? = null; private set
    private var misses = 0

    private class Cancelled : RuntimeException()

    fun cancel() {
        generation.incrementAndGet()
        if (!synced) phase = Phase.IDLE
    }

    fun forgetSync() { synced = false; misses = 0; lastLagMs = null; phase = Phase.IDLE }

    /** Full search + correction + refine. */
    fun syncNow() = submit("sync") { gen -> fullSync(gen) }

    /** Light verification around the current sync point. */
    fun check() {
        if (busy || !synced) return
        // A capture of the final mix with the channel muted holds only the station;
        // checking would silence the station for nothing. Stall compensation and
        // the pinned playback speed keep the lock in the meantime.
        if (host.sourceHearsStation && host.tvMuted) return
        submit("check") { gen -> checkSync(gen) }
    }

    /** Applies a lag measured by the user (two-tap sync), then refines it. */
    fun applyManual(lagMs: Double) = submit("manual") { gen ->
        lastLagMs = lagMs
        SLog.i("manual lag ${lagMs.toInt()} ms")
        correct(gen, lagMs, refine = true)
    }

    /** The station paused for [ms]: it is now late by that much. */
    fun onStationStall(ms: Long) {
        if (!synced || busy) return
        submit("stall") { gen ->
            val cur = host.audioDelayMs
            if (cur >= ms) {
                host.audioDelayMs = (cur - ms).toInt()
                host.rememberDelay(host.audioDelayMs)
                SLog.i("stall $ms ms compensated: delay $cur -> ${host.audioDelayMs}")
                host.status("عوّض توقّف المحطة $ms م.ث")
            } else {
                host.status("توقّفت المحطة $ms م.ث • إعادة ضبط")
                correct(gen, ms.toDouble(), refine = true)
            }
        }
    }

    private fun submit(name: String, job: (Int) -> Unit) {
        val gen = generation.incrementAndGet()
        exec.execute {
            if (gen != generation.get()) return@execute
            busy = true
            try { job(gen) } catch (_: Cancelled) {
                SLog.i("$name cancelled")
            } catch (t: Throwable) {
                SLog.e(name, t)
                phase = if (synced) Phase.LOCKED else Phase.IDLE
                host.result("لم تنجح المزامنة: ${t.message}", false)
            } finally {
                busy = false
            }
        }
    }

    private fun check(gen: Int) { if (gen != generation.get()) throw Cancelled() }

    // ---------------------------------------------------------------- flows

    private fun fullSync(gen: Int) {
        phase = Phase.LISTENING
        host.status("يقيس الفرق بين القناة والمحطة…")
        val m = withMeasuringSetup(gen, allowUnmute = true) {
            measure(gen, -SyncPolicy.MAX_NEGATIVE_LAG_MS, SyncPolicy.MAX_POSITIVE_LAG_MS, deadlineMs = 120_000)
        }
        if (m == null) {
            phase = if (synced) Phase.LOCKED else Phase.IDLE
            host.result("تعذّر إيجاد تطابق موثوق. تأكد أن القناة والمحطة تبثّان المحتوى نفسه", false)
            return
        }
        SLog.i("sync measured lag ${m.lagMs.toInt()} ms (${m.result})")
        correct(gen, m.lagMs, refine = true)
    }

    private fun checkSync(gen: Int) {
        val m = withMeasuringSetup(gen, allowUnmute = false) {
            measure(gen, -3000, 3000, deadlineMs = 40_000)
        }
        if (m == null) {
            misses++
            SLog.i("check: no confirmation (miss $misses)")
            if (misses >= 3) {
                synced = false
                phase = Phase.IDLE
                host.status("فُقدت المطابقة، يعيد البحث…")
            } else {
                phase = Phase.LOCKED
            }
            return
        }
        misses = 0
        SLog.i("check: residual ${m.lagMs.toInt()} ms")
        if (abs(m.lagMs) >= 80) correct(gen, m.lagMs, refine = false) else phase = Phase.LOCKED
    }

    /** Applies the plan for [lagMs]; with [refine], re-measures and trims up to twice. */
    private fun correct(gen: Int, lagMs: Double, refine: Boolean) {
        var lag = lagMs
        for (round in 0..2) {
            check(gen)
            phase = Phase.APPLYING
            val changed = (if (host.videoMethod == "menu") applyMenu(lag) else applyPause(lag)) ?: return
            if (!changed) { finish(lag); return }
            if (!refine || round == 2) { finish(null); return }
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

    /** @return true if something changed, false if nothing was needed, null on error. */
    private fun applyPause(lag: Double): Boolean? {
        val plan = SyncPolicy.plan(lag, host.audioDelayMs) ?: return false
        if (plan.error != null) { host.result(plan.error, false); return null }
        if (plan.pauseMs > 0) {
            host.status("يؤخّر الصورة %.2f ث…".format(plan.pauseMs / 1000.0))
            if (!pauseVerified(plan.pauseMs)) {
                host.result("أمر الإيقاف لم يوقف القناة؛ غيّر زر الإيقاف أو طريقة إرسال الأزرار ثم أعد المحاولة", false)
                return null
            }
            host.addTimeshift(plan.pauseMs)
        } else {
            host.status("يؤخّر صوتك %.2f ث…".format(plan.newDelayMs / 1000.0))
        }
        host.audioDelayMs = plan.newDelayMs
        host.rememberDelay(plan.newDelayMs)
        SLog.i("pause ${plan.pauseMs} ms, station delay ${plan.newDelayMs} ms")
        return true
    }

    private fun applyMenu(lag: Double): Boolean? {
        val v0 = host.menuVideoDelayMs
        val plan = SyncPolicy.planMenu(lag, host.audioDelayMs, v0) ?: return false
        if (plan.error != null) { host.result(plan.error, false); return null }
        if (plan.videoDelayMs != v0) {
            host.status("تأخير الصورة %.1f ث (قائمة الريسيفر)…".format(plan.videoDelayMs / 1000.0))
            host.setMenuVideoDelay(plan.videoDelayMs)
        }
        host.audioDelayMs = plan.newDelayMs
        host.rememberDelay(plan.newDelayMs)
        SLog.i("menu video ${plan.videoDelayMs} ms, station delay ${plan.newDelayMs} ms")
        return true
    }

    private fun finish(residualMs: Double?) {
        synced = true
        misses = 0
        phase = Phase.LOCKED
        if (host.muteAfterSync && !host.tvMuted) host.muteTv(true)
        val tail = residualMs?.let { " (فرق %+d م.ث)".format(it.toInt()) } ?: ""
        host.result("متزامن ✓$tail", true)
    }

    /**
     * Pauses the channel and checks that it really went quiet. Short pauses, or a
     * channel that was already quiet, are trusted without the check.
     */
    private fun pauseVerified(ms: Long): Boolean {
        val f = FeatureTrack.FRAME_MS
        val k = now() / f
        val before = tv.activity(k - 150, k)
        val start = now()
        if (!host.pauseTv(ms)) return false
        if (ms < 1500 || before < 0.08) return true
        Thread.sleep(400)
        val during = tv.activity((start + 600) / f, (start + ms - 300) / f)
        SLog.i("pause check: activity before %.2f during %.2f".format(before, during))
        return during < before * 0.35
    }

    // ---------------------------------------------------------------- measuring

    private fun <T> withMeasuringSetup(gen: Int, allowUnmute: Boolean, block: () -> T?): T? {
        val silence = host.sourceHearsStation
        val vol = if (silence) host.stationVolume() else 1f
        var unmuted = false
        try {
            tv.reset(); station.reset()
            if (silence) host.setStationVolume(0f)
            if (host.tvMuted) {
                sleep(gen, 2500)
                val k = now() / FeatureTrack.FRAME_MS
                if (tv.activity(k - 100, k) < 0.05) {
                    if (!allowUnmute) { SLog.i("channel muted and silent in capture; skip"); return null }
                    host.muteTv(false)
                    unmuted = true
                    host.status("فُتح صوت القناة مؤقتًا للقياس")
                    tv.reset()
                }
            }
            return block()
        } finally {
            if (silence) host.setStationVolume(vol)
            if (unmuted && host.muteAfterSync) host.muteTv(true)
        }
    }

    private fun measure(gen: Int, minLagMs: Long, maxLagMs: Long, deadlineMs: Long): SyncPolicy.Measured? {
        val f = FeatureTrack.FRAME_MS
        val start = now()
        val k0 = start / f
        waitForContent(gen, start)
        phase = Phase.MATCHING
        val confirmed = ArrayList<SyncPolicy.Measured>()
        while (now() - start < deadlineMs) {
            sleep(gen, 500)
            val tvNew = tv.newest
            val stNew = station.newest
            if (tvNew == Long.MIN_VALUE || stNew == Long.MIN_VALUE) continue
            if (tv.activity(tvNew - 250, tvNew) < 0.02) { phase = Phase.NO_AUDIO; continue }
            phase = Phase.MATCHING
            val maxLagFrames = min(maxLagMs / f, stNew - k0 - 300)
            if (maxLagFrames < 0 && maxLagMs > 0) continue
            val posLag = max(0L, maxLagFrames)
            val to = min(tvNew, stNew - posLag)
            val from = max(k0, to - 900)
            if (to - from < 300) continue
            val lo = max(minLagMs, -(to - k0) * f)
            val hi = if (maxLagMs > 0) posLag * f else maxLagMs
            if (hi - lo < 20 * f) continue
            val r = Correlator.correlate(tv, station, from, to, lo, hi) ?: continue
            host.status("جارٍ مطابقة الصوتين… (مدى البحث %d ث)".format(hi / 1000))
            if (!SyncPolicy.accept(r)) continue
            val m = SyncPolicy.Measured(r.lagMs + host.calibMs, to * f, r)
            lastLagMs = m.lagMs
            if (confirmed.any { SyncPolicy.confirms(it, m) }) return m
            confirmed += m
            host.status("وجد فرقًا %+.2f ث، يتأكد…".format(m.lagMs / 1000))
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
                phase = Phase.NO_AUDIO
                host.status(
                    "بانتظار الصوت • " + (if (okTv) "القناة ✓" else "القناة…") + " " +
                        (if (okSt) "المحطة ✓" else "المحطة…"),
                )
            }
            Thread.sleep(200)
        }
        if (tv.newest == Long.MIN_VALUE) throw IllegalStateException("لا يصل صوت القناة. نفّذ «فحص الريسيفر» من صدى")
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

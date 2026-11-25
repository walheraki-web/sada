package com.sada.tv.sync

import kotlin.math.abs

/** Pure decision rules for the sync engine (JVM-testable). */
object SyncPolicy {
    /** Robust z-score the best match must reach. */
    const val MIN_Z = 7.0
    /** ... and how far it must stand above the best rival lag. */
    const val MIN_MARGIN = 2.5
    /** Two confirmations must agree within this. */
    const val AGREE_MS = 60.0
    /** Confirmations must come from windows at least this far apart (independent audio). */
    const val MIN_SEPARATION_MS = 6_000L
    /** Residual errors below this are left alone. */
    const val TOLERANCE_MS = 40.0
    /** After a pause, the picture is held this much past the target; the station absorbs it. */
    const val OVERSHOOT_MS = 150L

    /** Search range: the station is usually BEHIND the satellite picture. */
    const val MAX_POSITIVE_LAG_MS = 60_000L
    const val MAX_NEGATIVE_LAG_MS = 20_000L

    const val MAX_AUDIO_DELAY_MS = 120_000
    const val MAX_PAUSE_MS = 90_000L

    fun accept(r: Correlator.Result) = r.z >= MIN_Z && r.margin >= MIN_MARGIN

    /** A measurement is confirmed by an independent window that agrees with it. */
    fun confirms(a: Measured, b: Measured): Boolean =
        abs(a.windowEndMs - b.windowEndMs) >= MIN_SEPARATION_MS && abs(a.lagMs - b.lagMs) <= AGREE_MS

    data class Measured(val lagMs: Double, val windowEndMs: Long, val result: Correlator.Result)

    /**
     * What to do about a measured lag.
     *
     * @param lagMs station heard minus channel heard, with the current station delay
     *   already in force (positive = the station is late).
     * @param currentDelayMs station delay in force while measuring.
     * @return null when nothing needs to change.
     */
    fun plan(lagMs: Double, currentDelayMs: Int): Plan? {
        if (abs(lagMs) < TOLERANCE_MS) return null
        val lag = Math.round(lagMs)
        if (lag < 0) {
            // Station early: hold it back more.
            val d = currentDelayMs - lag
            return if (d <= MAX_AUDIO_DELAY_MS) Plan(0, d.toInt()) else Plan.tooFar(lagMs)
        }
        // Station late. Cheapest fix: take back some of its own delay.
        if (currentDelayMs >= lag) return Plan(0, (currentDelayMs - lag).toInt())
        // Otherwise hold the picture back by pausing the channel, slightly past the
        // target, and let the station delay absorb the overshoot.
        val pause = lag - currentDelayMs + OVERSHOOT_MS
        if (pause > MAX_PAUSE_MS) return Plan.tooFar(lagMs)
        return Plan(pause, OVERSHOOT_MS.toInt())
    }

    data class Plan(val pauseMs: Long, val newDelayMs: Int, val error: String? = null) {
        companion object {
            fun tooFar(lagMs: Double) =
                Plan(0, 0, "الفرق %.1f ث أكبر من الحد المسموح".format(lagMs / 1000))
        }
    }
}

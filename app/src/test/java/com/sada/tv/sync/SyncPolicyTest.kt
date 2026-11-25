package com.sada.tv.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPolicyTest {

    @Test fun smallResidualIsLeftAlone() {
        assertNull(SyncPolicy.plan(25.0, 300))
        assertNull(SyncPolicy.plan(-39.0, 0))
    }

    @Test fun earlyStationIsDelayedMore() {
        val p = SyncPolicy.plan(-2500.0, 400)!!
        assertEquals(0L, p.pauseMs)
        assertEquals(2900, p.newDelayMs)
    }

    @Test fun lateStationGivesBackItsOwnDelayFirst() {
        val p = SyncPolicy.plan(1200.0, 5000)!!
        assertEquals(0L, p.pauseMs)
        assertEquals(3800, p.newDelayMs)
    }

    @Test fun lateStationBeyondItsDelayPausesThePicture() {
        // 12 s late with 2 s of station delay: hold the picture 10 s + overshoot,
        // and keep exactly the overshoot as station delay.
        val p = SyncPolicy.plan(12_000.0, 2000)!!
        assertEquals(10_000L + SyncPolicy.OVERSHOOT_MS, p.pauseMs)
        assertEquals(SyncPolicy.OVERSHOOT_MS.toInt(), p.newDelayMs)
        // Net effect is zero: lag - pause + (newDelay - oldDelay)
        assertEquals(0.0, 12_000.0 - p.pauseMs + (p.newDelayMs - 2000), 0.5)
    }

    @Test fun absurdLagsAreRefused() {
        assertNotNull(SyncPolicy.plan(200_000.0, 0)!!.error)
        assertNotNull(SyncPolicy.plan(-130_000.0, 0)!!.error)
    }

    @Test fun confirmationNeedsIndependentWindows() {
        val r = Correlator.Result(1000.0, 9.0, 3.0, 18.0)
        val a = SyncPolicy.Measured(1000.0, 100_000, r)
        assertFalse(SyncPolicy.confirms(a, SyncPolicy.Measured(1010.0, 100_500, r))) // overlapping
        assertTrue(SyncPolicy.confirms(a, SyncPolicy.Measured(1030.0, 107_000, r)))
        assertFalse(SyncPolicy.confirms(a, SyncPolicy.Measured(1200.0, 107_000, r))) // disagree
    }
}

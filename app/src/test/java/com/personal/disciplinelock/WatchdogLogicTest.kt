package com.personal.disciplinelock

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class WatchdogLogicTest {

    private fun checkFailsafeTripped(
        crashHistory: List<Long>,
        newCrashTime: Long,
        threshold: Int = 3,
        windowMs: Long = TimeUnit.MINUTES.toMillis(10)
    ): Pair<Boolean, List<Long>> {
        val cutoff = newCrashTime - windowMs
        val validCrashes = crashHistory.filter { it >= cutoff }.toMutableList()
        validCrashes.add(newCrashTime)
        val tripped = validCrashes.size >= threshold
        return Pair(tripped, validCrashes)
    }

    @Test
    fun testCrashLoopThresholdTriggersAtThreeCrashes() {
        val now = 1000000L
        var history = emptyList<Long>()

        // 1st crash
        val res1 = checkFailsafeTripped(history, now)
        assertFalse(res1.first)
        assertEquals(1, res1.second.size)
        history = res1.second

        // 2nd crash after 1 minute
        val res2 = checkFailsafeTripped(history, now + 60000L)
        assertFalse(res2.first)
        assertEquals(2, res2.second.size)
        history = res2.second

        // 3rd crash after 3 minutes (total 4 min into 10 min window)
        val res3 = checkFailsafeTripped(history, now + 240000L)
        assertTrue("Failsafe should trip on 3rd crash within 10 minutes", res3.first)
        assertEquals(3, res3.second.size)
    }

    @Test
    fun testOldCrashesExpireOutsideWindow() {
        val now = 1000000L
        val windowMs = TimeUnit.MINUTES.toMillis(10)
        var history = emptyList<Long>()

        // 1st crash at t=0
        val res1 = checkFailsafeTripped(history, now, windowMs = windowMs)
        history = res1.second

        // 2nd crash at t=5 min
        val res2 = checkFailsafeTripped(history, now + TimeUnit.MINUTES.toMillis(5), windowMs = windowMs)
        history = res2.second

        // 3rd crash at t=12 min (1st crash has expired!)
        val res3 = checkFailsafeTripped(history, now + TimeUnit.MINUTES.toMillis(12), windowMs = windowMs)
        assertFalse("Should not trip because 1st crash expired from rolling 10m window", res3.first)
        assertEquals(2, res3.second.size)
    }
}

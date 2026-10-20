package com.nightowl.sleep_monitor.monitor

import org.junit.Assert.*
import org.junit.Test

class SessionScheduleTest {
    @Test fun intervalStartsAfterFinalizationInsteadOfRecordingStart() {
        val schedule = SessionSchedule(3_600_000L)
        // Camera startup takes ten seconds; recording must still get a full five minutes.
        schedule.beginPhase(10_000L, 300)
        assertFalse(schedule.phaseExpired(300_000L))
        assertTrue(schedule.phaseExpired(310_000L))
        // File finalization takes two more seconds. Cooldown starts only now.
        schedule.beginPhase(312_000L, 900)
        assertFalse(schedule.phaseExpired(1_211_999L))
        assertTrue(schedule.phaseExpired(1_212_000L))
    }

    @Test fun totalSessionDeadlineLimitsLongWaitingPhase() {
        val schedule = SessionSchedule(61_000L)
        schedule.beginPhase(2000L, 900)
        assertEquals(59, schedule.remainingSeconds(2000L, 2000L))
        assertFalse(schedule.sessionExpired(60_999L))
        assertTrue(schedule.sessionExpired(61_000L))
        assertEquals(0, schedule.remainingSeconds(62_000L, 62_000L))
    }

    @Test fun countdownRoundsUpAndAnUnsetPhaseCannotExpire() {
        val schedule = SessionSchedule(61_000L)
        assertFalse(schedule.phaseExpired(2000L))
        schedule.beginPhase(2000L, 1)
        assertEquals(1, schedule.remainingSeconds(2999L, 2999L))
        assertEquals(0, schedule.remainingSeconds(3000L, 3000L))
    }

    @Test fun reachingTheClockEndStopsEvenWhenTheRecordingPhaseHasTimeLeft() {
        val schedule = SessionSchedule(61_000L)
        schedule.beginPhase(1000L, 300)
        assertFalse(schedule.phaseExpired(2000L))
        assertTrue(schedule.sessionExpired(61_000L))
        assertEquals(0, schedule.remainingSeconds(2000L, 61_000L))
    }
}

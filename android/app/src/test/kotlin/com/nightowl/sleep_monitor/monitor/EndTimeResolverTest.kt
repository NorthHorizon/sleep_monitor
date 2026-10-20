package com.nightowl.sleep_monitor.monitor

import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class EndTimeResolverTest {
    private val zone = ZoneId.of("Asia/Hong_Kong")

    private fun clock(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun futureTimeUsesTodayAndBedtimeUsesTomorrow() {
        assertEquals(clock(2026, 10, 15, 7), EndTimeResolver.nextOccurrence(clock(2026, 10, 15, 6), 7, 0, zone))
        assertEquals(clock(2026, 10, 16, 7), EndTimeResolver.nextOccurrence(clock(2026, 10, 15, 23), 7, 0, zone))
    }

    @Test fun equalTimeAndYearBoundaryUseTheNextCalendarDay() {
        assertEquals(clock(2026, 10, 16, 7), EndTimeResolver.nextOccurrence(clock(2026, 10, 15, 7), 7, 0, zone))
        assertEquals(clock(2027, 1, 1, 0), EndTimeResolver.nextOccurrence(clock(2026, 12, 31, 23), 0, 0, zone))
    }

    @Test fun nextMorningUsesLocalClockTimeAcrossDaylightSaving() {
        val dstZone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 3, 7, 23, 0, 0, 0, dstZone)
        val expected = ZonedDateTime.of(2026, 3, 8, 7, 0, 0, 0, dstZone)
        val actual = EndTimeResolver.nextOccurrence(now.toInstant().toEpochMilli(), 7, 0, dstZone)
        assertEquals(expected.toInstant().toEpochMilli(), actual)
        assertEquals(7, Duration.between(now.toInstant(), expected.toInstant()).toHours().toInt())
    }
}

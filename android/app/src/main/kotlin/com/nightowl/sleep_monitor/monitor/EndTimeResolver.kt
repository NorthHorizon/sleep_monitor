package com.nightowl.sleep_monitor.monitor

import java.time.Instant
import java.time.ZoneId

/** Pick the next local clock occurrence, including across month/year and DST boundaries. */
object EndTimeResolver {
    fun nextOccurrence(
        nowEpochMs: Long,
        hour: Int,
        minute: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        require(hour in 0..23 && minute in 0..59) { "无效的自动结束时间" }
        val now = Instant.ofEpochMilli(nowEpochMs).atZone(zone)
        val today = now.toLocalDate().atTime(hour, minute).atZone(zone)
        val end = if (today.isAfter(now)) today else {
            now.toLocalDate().plusDays(1).atTime(hour, minute).atZone(zone)
        }
        return end.toInstant().toEpochMilli()
    }
}

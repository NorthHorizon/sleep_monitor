package com.nightowl.sleep_monitor.monitor

/** Deadlines use elapsedRealtime, never wall-clock time or UI timers. */
class SessionSchedule(
    sessionStart: Long,
    sessionSeconds: Int,
) {
    val sessionDeadline: Long = sessionStart + sessionSeconds * 1000L
    var phaseDeadline: Long? = null
        private set

    fun beginPhase(now: Long, seconds: Int) {
        phaseDeadline = now + seconds * 1000L
    }

    fun sessionExpired(now: Long): Boolean = now >= sessionDeadline
    fun phaseExpired(now: Long): Boolean = phaseDeadline?.let { now >= it } ?: false

    fun remainingSeconds(now: Long): Int {
        val deadline = minOf(phaseDeadline ?: sessionDeadline, sessionDeadline)
        return ((deadline - now).coerceAtLeast(0) + 999).div(1000).toInt()
    }
}

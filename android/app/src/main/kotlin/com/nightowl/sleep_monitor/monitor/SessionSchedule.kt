package com.nightowl.sleep_monitor.monitor

/** Session end is a fixed clock instant; clip/wait deadlines use elapsedRealtime. */
class SessionSchedule(val endsAtEpochMs: Long) {
    var phaseDeadline: Long? = null
        private set

    fun beginPhase(now: Long, seconds: Int) {
        phaseDeadline = now + seconds * 1000L
    }

    fun sessionExpired(wallNow: Long): Boolean = wallNow >= endsAtEpochMs
    fun phaseExpired(now: Long): Boolean = phaseDeadline?.let { now >= it } ?: false

    fun remainingSeconds(elapsedNow: Long, wallNow: Long): Int {
        val sessionRemaining = endsAtEpochMs - wallNow
        val phaseRemaining = phaseDeadline?.minus(elapsedNow) ?: sessionRemaining
        return (minOf(phaseRemaining, sessionRemaining).coerceAtLeast(0) + 999)
            .div(1000).toInt()
    }
}

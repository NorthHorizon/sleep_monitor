package com.nightowl.sleep_monitor.monitor

data class MonitorStatus(
    val phase: String = "idle",
    val sessionId: String? = null,
    val startedAt: Long? = null,
    val endsAt: Long? = null,
    val remainingSeconds: Int = 0,
    val completedClips: Int = 0,
    val message: String? = null,
) {
    val isActive: Boolean get() = phase != "idle" && phase != "error"

    fun toMap(): Map<String, Any?> = mapOf(
        "phase" to phase,
        "sessionId" to sessionId,
        "startedAt" to startedAt,
        "endsAt" to endsAt,
        "remainingSeconds" to remainingSeconds,
        "completedClips" to completedClips,
        "message" to message,
    )
}

/** Main-thread-only session state. The service outlives the Flutter activity. */
object MonitorRuntime {
    var status = MonitorStatus()
        private set
    private val listeners = mutableSetOf<(MonitorStatus) -> Unit>()

    fun publish(next: MonitorStatus) {
        status = next
        listeners.toList().forEach { it(next) }
    }

    fun subscribe(listener: (MonitorStatus) -> Unit) {
        listeners.add(listener)
        listener(status)
    }

    fun unsubscribe(listener: (MonitorStatus) -> Unit) {
        listeners.remove(listener)
    }
}

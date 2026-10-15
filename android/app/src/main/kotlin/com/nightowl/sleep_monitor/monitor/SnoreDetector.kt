package com.nightowl.sleep_monitor.monitor

/** Extension point for local AudioRecord + model inference. No fake detections. */
interface SnoreDetector {
    val available: Boolean
    fun start(onSnore: (score: Float) -> Unit)
    fun stop()
}

class UnavailableSnoreDetector : SnoreDetector {
    override val available = false
    override fun start(onSnore: (Float) -> Unit) {
        error("鼾声检测尚未启用，请先使用定时录制")
    }
    override fun stop() = Unit
}

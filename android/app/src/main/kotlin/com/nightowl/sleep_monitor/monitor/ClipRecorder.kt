package com.nightowl.sleep_monitor.monitor

import java.io.File

data class RecordedFile(val file: File, val durationMilliseconds: Long)

/** Camera implementation boundary; it does not own trigger or cooldown policy. */
interface ClipRecorder {
    val isRecording: Boolean
    fun start(
        config: MonitorConfig,
        file: File,
        onStarted: () -> Unit,
        onFinished: (Result<RecordedFile>) -> Unit,
    )
    fun stop()
    fun release()
}

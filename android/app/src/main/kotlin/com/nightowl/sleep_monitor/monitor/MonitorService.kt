package com.nightowl.sleep_monitor.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.nightowl.sleep_monitor.MainActivity
import com.nightowl.sleep_monitor.R
import java.util.UUID

class MonitorService : LifecycleService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var repository: MonitorRepository
    private lateinit var recorder: ClipRecorder
    private lateinit var diagnostics: SessionDiagnostics
    private lateinit var config: MonitorConfig
    private lateinit var schedule: SessionSchedule
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false
    private var stopping = false
    private var stopError: String? = null
    private var sessionId = ""
    private var clipId = ""
    private var clipStartedAt: Long? = null
    private var sessionStartedAt = 0L
    private var completedClips = 0
    private var autoEnded = false
    private var phase = "idle"
    private var clipPrepareElapsedMs = 0L
    private var clipFinalizeElapsedMs: Long? = null
    private var lastHeartbeatElapsedMs = 0L
    private var lastTickElapsedMs = 0L

    private val automaticStop = Runnable {
        if (running && !stopping) {
            if (schedule.sessionExpired(System.currentTimeMillis())) {
                requestStop()
            } else {
                // A manual clock correction may move the fixed end instant further away.
                armAutomaticStop()
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                tick()
            } catch (error: Exception) {
                logEvent("session_exception", mapOf("error" to error.stackTraceToString().take(4000)))
                requestStop(error.message ?: "监控运行失败")
            }
            if (running) handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = MonitorRepository(applicationContext)
        recorder = CameraXClipRecorder(this, this)
        diagnostics = SessionDiagnostics(this)
        val channel = NotificationChannel(CHANNEL, "夜间监控", NotificationManager.IMPORTANCE_LOW)
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.setShowBadge(false)
        requireNotNull(getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                if (running) requestStop() else stopSelf()
            }
            ACTION_START -> if (!running) {
                try {
                    config = MonitorConfig.fromJson(intent.getStringExtra("config") ?: error("缺少监控配置"))
                    check(config.mode == "timed") { "鼾声检测尚未启用，请先使用定时录制" }
                    sessionId = intent.getStringExtra("sessionId") ?: error("缺少监控会话")
                    sessionStartedAt = System.currentTimeMillis()
                    val endsAt = intent.getLongExtra("endsAt", 0L)
                    check(endsAt > sessionStartedAt) { "设置的结束时间已过，请重新开始监控" }
                    schedule = SessionSchedule(endsAt)
                    running = true
                    stopping = false
                    stopError = null
                    completedClips = 0
                    autoEnded = false
                    clipId = ""
                    phase = "preparing"
                    lastHeartbeatElapsedMs = SystemClock.elapsedRealtime()
                    lastTickElapsedMs = lastHeartbeatElapsedMs
                    repository.markSessionActive(true)
                    var types = 0
                    if (Build.VERSION.SDK_INT >= 29) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    if (Build.VERSION.SDK_INT >= 30) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("正在准备录制"), types)
                    wakeLock = requireNotNull(getSystemService(PowerManager::class.java))
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepMonitor:Session")
                        .apply { acquire((endsAt - sessionStartedAt) + 60_000L) }
                    logEvent("session_start", mapOf(
                        "settings" to config.toMap(), "endsAt" to endsAt,
                        "environment" to diagnostics.environment(),
                    ), includeDeviceState = true)
                    armAutomaticStop()
                    beginRecording()
                    if (running) handler.post(ticker)
                } catch (error: Exception) {
                    logEvent("session_exception", mapOf("error" to error.stackTraceToString().take(4000)))
                    finishSession(error.message ?: "无法启动监控")
                }
            }
            else -> stopSelf()
        }
        // Do not restart a camera session after process death without user action.
        return START_NOT_STICKY
    }

    private fun beginRecording() {
        if (!running || stopping) return
        if (schedule.sessionExpired(System.currentTimeMillis())) {
            requestStop()
            return
        }
        clipId = UUID.randomUUID().toString()
        clipStartedAt = null
        clipPrepareElapsedMs = SystemClock.elapsedRealtime()
        clipFinalizeElapsedMs = null
        val file = repository.newClipFile(clipId)
        check(repository.clipsDirectory.usableSpace > 256L * 1024 * 1024) {
            "剩余空间不足 256 MB，监控已停止；已有片段仍保留"
        }
        phase = "preparing"
        schedule.beginPhase(SystemClock.elapsedRealtime(), 30)
        logEvent("clip_prepare", mapOf("clipId" to clipId))
        publish("正在启动相机与麦克风")
        recorder.start(config, file,
            onStarted = {
                if (running) {
                    clipStartedAt = System.currentTimeMillis()
                    val startedElapsedMs = SystemClock.elapsedRealtime()
                    if (!stopping) {
                        phase = "recording"
                        schedule.beginPhase(startedElapsedMs, config.recordingSeconds)
                    }
                    logEvent("clip_started", mapOf(
                        "clipId" to clipId,
                        "startupMs" to (startedElapsedMs - clipPrepareElapsedMs),
                    ))
                    if (!stopping) {
                        publish("正在保存带声音的视频")
                    }
                }
            },
            onFinished = { result ->
                if (running) {
                    result.fold(
                        onSuccess = { recorded ->
                            try {
                                repository.addClip(SavedClip(
                                    id = clipId, sessionId = sessionId, mode = config.mode,
                                    startedAt = clipStartedAt ?: (System.currentTimeMillis() - recorded.durationMilliseconds),
                                    durationMilliseconds = recorded.durationMilliseconds,
                                    sizeBytes = recorded.file.length(), path = recorded.file.absolutePath,
                                    settingsSnapshot = config.toMap() + mapOf("endsAt" to schedule.endsAtEpochMs),
                                ))
                                completedClips++
                                logEvent("clip_saved", mapOf(
                                    "clipId" to clipId,
                                    "startedAt" to clipStartedAt,
                                    "durationMs" to recorded.durationMilliseconds,
                                    "sizeBytes" to recorded.file.length(),
                                    "finalizeMs" to clipFinalizeElapsedMs?.let { SystemClock.elapsedRealtime() - it },
                                ), includeDeviceState = true)
                                if (stopping || schedule.sessionExpired(System.currentTimeMillis())) {
                                    if (!stopping) autoEnded = true
                                    finishSession(stopError)
                                } else {
                                    // Only a finalized, successfully indexed clip starts the interval.
                                    phase = "waiting"
                                    schedule.beginPhase(SystemClock.elapsedRealtime(), config.intervalSeconds)
                                    logEvent("waiting", mapOf("intervalSeconds" to config.intervalSeconds))
                                    publish("上一段已保存，等待下一次录制")
                                }
                            } catch (error: Exception) {
                                logEvent("clip_index_error", mapOf(
                                    "clipId" to clipId, "error" to error.stackTraceToString().take(4000),
                                ))
                                finishSession("片段保存索引失败：${error.message}")
                            }
                        },
                        onFailure = {
                            logEvent("clip_failed", mapOf("clipId" to clipId, "error" to it.stackTraceToString().take(4000)))
                            finishSession(stopError ?: it.message ?: "录像失败")
                        },
                    )
                }
            },
        )
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTickElapsedMs > 15_000L) {
            logEvent("scheduler_gap", mapOf("gapMs" to (now - lastTickElapsedMs)), includeDeviceState = true)
        }
        lastTickElapsedMs = now
        if (now - lastHeartbeatElapsedMs >= 5 * 60_000L) {
            logEvent("heartbeat", includeDeviceState = true)
            lastHeartbeatElapsedMs = now
        }
        if (!stopping && schedule.sessionExpired(System.currentTimeMillis())) {
            requestStop()
            return
        }
        if (schedule.phaseExpired(now)) {
            when (phase) {
                "recording" -> {
                    phase = "finalizing"
                    schedule.beginPhase(now, 20)
                    publish("正在完成视频文件，请稍候")
                    stopRecorder()
                }
                "waiting" -> beginRecording()
                "preparing" -> requestStop("相机启动超时，请检查相机是否被其他应用占用")
                "finalizing", "stopping" -> finishSession(stopError ?: "视频保存超时，请检查片段文件")
            }
        }
        if (running) publish(MonitorRuntime.status.message)
    }

    private fun requestStop(error: String? = null) {
        if (!running || stopping) return
        stopping = true
        stopError = error
        if (error == null && schedule.sessionExpired(System.currentTimeMillis())) autoEnded = true
        logEvent("stop_requested", mapOf(
            "reason" to if (error != null) "error" else if (autoEnded) "auto_end" else "user",
            "error" to error?.take(4000),
        ), includeDeviceState = true)
        handler.removeCallbacks(automaticStop)
        if (recorder.isRecording) {
            phase = "stopping"
            schedule.beginPhase(SystemClock.elapsedRealtime(), 20)
            publish(error ?: if (autoEnded) "已到结束时间，正在保存最后一个片段" else "正在保存最后一个片段")
            stopRecorder()
        } else {
            finishSession(error)
        }
    }

    private fun armAutomaticStop() {
        handler.removeCallbacks(automaticStop)
        val delay = (schedule.endsAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0)
        handler.postDelayed(automaticStop, delay)
    }

    private fun stopRecorder() {
        if (clipFinalizeElapsedMs == null) {
            clipFinalizeElapsedMs = SystemClock.elapsedRealtime()
            logEvent("clip_stop_requested", mapOf("clipId" to clipId))
        }
        runCatching { recorder.stop() }.onFailure {
            logEvent("clip_stop_error", mapOf("clipId" to clipId, "error" to it.stackTraceToString().take(4000)))
            finishSession(stopError ?: it.message ?: "无法结束当前录像")
        }
    }

    private fun logEvent(
        event: String,
        details: Map<String, Any?> = emptyMap(),
        includeDeviceState: Boolean = false,
    ) {
        if (::diagnostics.isInitialized) diagnostics.record(
            event, sessionId.ifEmpty { null },
            mapOf("phase" to phase, "completedClips" to completedClips) + details,
            includeDeviceState,
        )
    }

    private fun publish(message: String?) {
        val next = MonitorStatus(
            phase = phase, sessionId = sessionId, startedAt = sessionStartedAt,
            endsAt = schedule.endsAtEpochMs,
            remainingSeconds = schedule.remainingSeconds(SystemClock.elapsedRealtime(), System.currentTimeMillis()),
            completedClips = completedClips, message = message,
        )
        val previous = MonitorRuntime.status
        MonitorRuntime.publish(next)
        if (previous.phase != phase || previous.completedClips != completedClips) {
            requireNotNull(getSystemService(NotificationManager::class.java))
                .notify(NOTIFICATION_ID, notification(message ?: "监控进行中"))
        }
    }

    private fun finishSession(error: String?) {
        running = false
        handler.removeCallbacksAndMessages(null)
        val releaseError = runCatching { recorder.release() }.exceptionOrNull()?.message
        val wakeError = runCatching { releaseWakeLock() }.exceptionOrNull()?.message
        val persistError = runCatching { repository.markSessionActive(false) }.exceptionOrNull()?.message
        val finalError = error ?: releaseError ?: wakeError ?: persistError
        logEvent("session_end", mapOf(
            "reason" to if (finalError != null) "error" else if (autoEnded) "auto_end" else "user",
            "error" to finalError?.take(4000),
        ), includeDeviceState = true)
        MonitorRuntime.publish(MonitorStatus(
            phase = if (finalError == null) "idle" else "error",
            sessionId = sessionId.ifEmpty { null },
            startedAt = sessionStartedAt.takeIf { it > 0 },
            endsAt = if (::schedule.isInitialized) schedule.endsAtEpochMs else null,
            completedClips = completedClips,
            message = finalError ?: if (autoEnded) "已到自动结束时间，已保存 $completedClips 段"
                else "本次监控已结束，已保存 $completedClips 段",
        ))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_monitor)
            .setContentTitle("夜间记录 · 监控进行中")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_monitor, "结束监控", stop)
            .build()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { recorder.release() }
        runCatching { releaseWakeLock() }
        if (running) {
            running = false
            logEvent("service_destroyed", includeDeviceState = true)
            runCatching { repository.markSessionActive(false) }
            MonitorRuntime.publish(MonitorRuntime.status.copy(
                phase = "error", remainingSeconds = 0,
                message = "监控服务已中断，已完成的片段仍保留",
            ))
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.nightowl.sleep_monitor.START"
        const val ACTION_STOP = "com.nightowl.sleep_monitor.STOP"
        private const val CHANNEL = "sleep_monitor_session"
        private const val NOTIFICATION_ID = 4201
    }
}

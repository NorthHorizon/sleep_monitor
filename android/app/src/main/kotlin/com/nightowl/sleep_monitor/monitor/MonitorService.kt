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
    private var phase = "idle"

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                tick()
            } catch (error: Exception) {
                requestStop(error.message ?: "监控运行失败")
            }
            if (running) handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = MonitorRepository(applicationContext)
        recorder = CameraXClipRecorder(this, this)
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
                    schedule = SessionSchedule(SystemClock.elapsedRealtime(), config.sessionSeconds)
                    running = true
                    stopping = false
                    stopError = null
                    completedClips = 0
                    repository.markSessionActive(true)
                    var types = 0
                    if (Build.VERSION.SDK_INT >= 29) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    if (Build.VERSION.SDK_INT >= 30) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("正在准备录制"), types)
                    wakeLock = requireNotNull(getSystemService(PowerManager::class.java))
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepMonitor:Session")
                        .apply { acquire(config.sessionSeconds * 1000L + 60_000L) }
                    beginRecording()
                    if (running) handler.post(ticker)
                } catch (error: Exception) {
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
        if (schedule.sessionExpired(SystemClock.elapsedRealtime())) {
            requestStop()
            return
        }
        clipId = UUID.randomUUID().toString()
        clipStartedAt = null
        val file = repository.newClipFile(clipId)
        check(repository.clipsDirectory.usableSpace > 256L * 1024 * 1024) {
            "剩余空间不足 256 MB，监控已停止；已有片段仍保留"
        }
        phase = "preparing"
        schedule.beginPhase(SystemClock.elapsedRealtime(), 30)
        publish("正在启动相机与麦克风")
        recorder.start(config, file,
            onStarted = {
                if (running) {
                    clipStartedAt = System.currentTimeMillis()
                    if (!stopping) {
                        phase = "recording"
                        schedule.beginPhase(SystemClock.elapsedRealtime(), config.recordingSeconds)
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
                                    config = config,
                                ))
                                completedClips++
                                if (stopping || schedule.sessionExpired(SystemClock.elapsedRealtime())) {
                                    finishSession(stopError)
                                } else {
                                    // Only a finalized, successfully indexed clip starts the interval.
                                    phase = "waiting"
                                    schedule.beginPhase(SystemClock.elapsedRealtime(), config.intervalSeconds)
                                    publish("上一段已保存，等待下一次录制")
                                }
                            } catch (error: Exception) {
                                finishSession("片段保存索引失败：${error.message}")
                            }
                        },
                        onFailure = { finishSession(stopError ?: it.message ?: "录像失败") },
                    )
                }
            },
        )
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (!stopping && schedule.sessionExpired(now)) {
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
        if (recorder.isRecording) {
            phase = "stopping"
            schedule.beginPhase(SystemClock.elapsedRealtime(), 20)
            publish(error ?: "正在保存最后一个片段")
            stopRecorder()
        } else {
            finishSession(error)
        }
    }

    private fun stopRecorder() {
        runCatching { recorder.stop() }.onFailure {
            finishSession(stopError ?: it.message ?: "无法结束当前录像")
        }
    }

    private fun publish(message: String?) {
        val next = MonitorStatus(
            phase = phase, sessionId = sessionId, startedAt = sessionStartedAt,
            remainingSeconds = schedule.remainingSeconds(SystemClock.elapsedRealtime()),
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
        MonitorRuntime.publish(MonitorStatus(
            phase = if (finalError == null) "idle" else "error",
            sessionId = sessionId.ifEmpty { null },
            startedAt = sessionStartedAt.takeIf { it > 0 },
            completedClips = completedClips,
            message = finalError ?: "本次监控已结束，已保存 $completedClips 段",
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

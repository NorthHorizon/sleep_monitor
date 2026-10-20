package com.nightowl.sleep_monitor.monitor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.UUID

class MonitorBridge(private val activity: Activity, messenger: BinaryMessenger) :
    MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private val repository = MonitorRepository(activity.applicationContext)
    private val diagnostics = SessionDiagnostics(activity.applicationContext)
    private val detector: SnoreDetector = UnavailableSnoreDetector()
    private val commands = MethodChannel(messenger, "com.nightowl.sleep_monitor/commands")
    private val events = EventChannel(messenger, "com.nightowl.sleep_monitor/events")
    private var listener: ((MonitorStatus) -> Unit)? = null
    private data class PendingStart(
        val result: MethodChannel.Result,
        val config: MonitorConfig,
        val endsAt: Long,
    )
    private var pendingStart: PendingStart? = null

    init {
        commands.setMethodCallHandler(this)
        events.setStreamHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "bootstrap" -> {
                    if (repository.sessionWasActive() && !MonitorRuntime.status.isActive) {
                        repository.markSessionActive(false)
                        diagnostics.record("previous_session_interrupted", details = mapOf(
                            "message" to "重开应用时发现上次会话未正常结束；此时间是发现时间，非中断时间",
                        ), includeDeviceState = true)
                        MonitorRuntime.publish(MonitorStatus(
                            phase = "error", message = "上次监控意外中断，请检查已有片段后重新开始",
                        ))
                    }
                    result.success(mapOf(
                        "settings" to repository.settings().toMap(),
                        "snapshot" to MonitorRuntime.status.toMap(),
                        "recordings" to repository.listClips().map { it.toMap() },
                        "capabilities" to mapOf(
                            "timedRecording" to true,
                            "snoreDetection" to detector.available,
                            "message" to "鼾声触发暂不可用，当前可使用定时录制。",
                        ),
                    ))
                }
                "saveSettings" -> {
                    check(!MonitorRuntime.status.isActive && pendingStart == null) {
                        "请先结束监控，再修改设置"
                    }
                    repository.saveSettings(MonitorConfig.fromMap(call.arguments as Map<*, *>))
                    result.success(null)
                }
                "startSession" -> requestStart(result)
                "stopSession" -> {
                    if (MonitorRuntime.status.isActive) {
                        activity.startService(Intent(activity, MonitorService::class.java)
                            .setAction(MonitorService.ACTION_STOP))
                    }
                    result.success(null)
                }
                "listRecordings" -> result.success(repository.listClips().map { it.toMap() })
                "shareDiagnostics" -> {
                    check(!MonitorRuntime.status.isActive && pendingStart == null) { "请先结束监控，再导出日志" }
                    val file = diagnostics.export()
                    val uri = FileProvider.getUriForFile(
                        activity, "${activity.packageName}.recordings", file,
                    )
                    val intent = Intent(Intent.ACTION_SEND).setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .apply { clipData = android.content.ClipData.newRawUri("运行日志", uri) }
                    activity.startActivity(Intent.createChooser(intent, "导出运行日志"))
                    result.success(null)
                }
                "openRecording", "shareRecording" -> {
                    val id = call.argument<String>("id") ?: error("缺少片段 ID")
                    val file = repository.clipFile(id)
                    val uri = FileProvider.getUriForFile(
                        activity, "${activity.packageName}.recordings", file,
                    )
                    val intent = if (call.method == "openRecording") {
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                    } else {
                        Intent(Intent.ACTION_SEND).setType("video/mp4")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .apply { clipData = android.content.ClipData.newRawUri("视频片段", uri) }
                    }
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    activity.startActivity(Intent.createChooser(intent, "视频片段"))
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        } catch (error: Exception) {
            result.error("MONITOR_ERROR", error.message ?: "操作失败", null)
        }
    }

    private fun requestStart(result: MethodChannel.Result) {
        check(pendingStart == null && !MonitorRuntime.status.isActive) { "监控已在启动或运行中" }
        val config = repository.settings()
        config.validate()
        check(config.mode == "timed" || detector.available) {
            "鼾声检测尚未启用，请先使用定时录制"
        }
        val required = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = required.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        pendingStart = PendingStart(result, config, EndTimeResolver.nextOccurrence(
            System.currentTimeMillis(), config.endHour, config.endMinute,
        ))
        if (missing.isEmpty()) {
            completeStart()
        } else {
            try {
                activity.requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
            } catch (error: Exception) {
                pendingStart = null
                throw error
            }
        }
    }

    fun onPermissionsResult(requestCode: Int) {
        if (requestCode == PERMISSION_REQUEST && pendingStart != null) completeStart()
    }

    private fun completeStart() {
        val pending = pendingStart ?: return
        val result = pending.result
        pendingStart = null
        try {
            val granted = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
                ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
            }
            check(granted) { "需要相机和麦克风权限才能录制，请在系统设置中授权后重试" }
            val config = pending.config
            check(pending.endsAt > System.currentTimeMillis()) {
                "设置的结束时间已过，请重新选择时间或重新开始监控"
            }
            val sessionId = UUID.randomUUID().toString()
            // Reserve the session before the service callback to reject duplicate starts.
            MonitorRuntime.publish(MonitorStatus(
                phase = "preparing", sessionId = sessionId,
                startedAt = System.currentTimeMillis(), endsAt = pending.endsAt,
                message = "正在启动监控服务",
            ))
            ContextCompat.startForegroundService(activity,
                Intent(activity, MonitorService::class.java)
                    .setAction(MonitorService.ACTION_START)
                    .putExtra("config", config.toJson())
                    .putExtra("endsAt", pending.endsAt)
                    .putExtra("sessionId", sessionId))
            result.success(null)
        } catch (error: Exception) {
            MonitorRuntime.publish(MonitorStatus(phase = "error", message = error.message))
            result.error("START_FAILED", error.message, null)
        }
    }

    override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
        listener?.let { MonitorRuntime.unsubscribe(it) }
        listener = { sink.success(it.toMap()) }
        MonitorRuntime.subscribe(listener!!)
    }

    override fun onCancel(arguments: Any?) {
        listener?.let { MonitorRuntime.unsubscribe(it) }
        listener = null
    }

    fun dispose() {
        onCancel(null)
        commands.setMethodCallHandler(null)
        events.setStreamHandler(null)
        pendingStart?.result?.error("ACTIVITY_CLOSED", "页面已关闭，请重新开始监控", null)
        pendingStart = null
    }

    private companion object {
        const val PERMISSION_REQUEST = 501
    }
}

package com.nightowl.sleep_monitor.monitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

class CameraXClipRecorder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) : ClipRecorder {
    private val executor = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var capture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var stopRequested = false
    private var generation = 0
    override val isRecording: Boolean get() = recording != null

    override fun start(
        config: MonitorConfig,
        file: File,
        onStarted: () -> Unit,
        onFinished: (Result<RecordedFile>) -> Unit,
    ) {
        check(recording == null) { "当前片段尚未结束" }
        stopRequested = false
        val token = ++generation
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (token != generation) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val selector = if (config.camera == "front") {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else CameraSelector.DEFAULT_BACK_CAMERA
                check(cameraProvider.hasCamera(selector)) { "找不到所选摄像头" }
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(
                        Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD),
                    ))
                    .build()
                val videoCapture = VideoCapture.withOutput(recorder)
                capture = videoCapture
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, videoCapture)
                check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) { "麦克风权限未授权" }
                recording = recorder.prepareRecording(context, FileOutputOptions.Builder(file).build())
                    .withAudioEnabled()
                    .start(executor) recordingEvents@ { event ->
                        if (token != generation) return@recordingEvents
                        when (event) {
                            is VideoRecordEvent.Start -> onStarted()
                            is VideoRecordEvent.Finalize -> {
                                recording = null
                                capture = null
                                val result = runCatching {
                                    cameraProvider.unbind(videoCapture)
                                    check(!event.hasError()) { "录像失败（错误码 ${event.error}）" }
                                    check(file.isFile && file.length() > 0) { "未生成有效视频" }
                                    val metadata = MediaMetadataRetriever()
                                    try {
                                        metadata.setDataSource(file.absolutePath)
                                        check(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                                            && metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") {
                                            "片段缺少音轨或视频轨道"
                                        }
                                    } finally {
                                        metadata.release()
                                    }
                                    RecordedFile(file, event.recordingStats.recordedDurationNanos / 1_000_000)
                                }
                                if (result.isFailure) file.delete()
                                onFinished(result)
                            }
                        }
                    }
            } catch (error: Exception) {
                runCatching { capture?.let { provider?.unbind(it) } }
                capture = null
                file.delete()
                onFinished(Result.failure(error))
            }
        }, executor)
    }

    override fun stop() {
        if (!stopRequested) {
            stopRequested = true
            recording?.stop()
        }
    }

    override fun release() {
        generation++
        val previousRecording = recording
        val previousCapture = capture
        recording = null
        capture = null
        try {
            previousRecording?.close()
        } finally {
            previousCapture?.let { provider?.unbind(it) }
        }
    }
}

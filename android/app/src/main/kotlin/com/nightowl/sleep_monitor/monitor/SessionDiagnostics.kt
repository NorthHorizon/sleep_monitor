package com.nightowl.sleep_monitor.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.TimeZone
import java.util.UUID

/** Metadata only: no audio, video, serial number or network transmission. */
class SessionDiagnostics(context: Context) {
    private val context = context.applicationContext
    private val store = DiagnosticLogStore(File(this.context.filesDir, "diagnostics"))

    fun record(
        event: String,
        sessionId: String? = null,
        details: Map<String, Any?> = emptyMap(),
        includeDeviceState: Boolean = false,
    ) {
        // Observability must never stop an otherwise healthy recording session.
        try {
            val now = System.currentTimeMillis()
            val value = JSONObject()
                .put("schemaVersion", 1)
                .put("timestampMs", now)
                .put("timestampUtc", Instant.ofEpochMilli(now).toString())
                .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
                .put("event", event)
                .put("sessionId", sessionId ?: JSONObject.NULL)
                .put("details", JSONObject(details))
            if (includeDeviceState) value.put("deviceState", JSONObject(deviceState()))
            store.append(value.toString())
        } catch (error: Exception) {
            Log.w("SleepDiagnostics", "运行日志写入失败", error)
        }
    }

    fun environment(): Map<String, Any?> {
        return mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "sdkInt" to Build.VERSION.SDK_INT,
            "androidVersion" to Build.VERSION.RELEASE,
            "buildDisplay" to Build.DISPLAY,
            "supportedAbis" to Build.SUPPORTED_ABIS.toList(),
            "appVersion" to runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull(),
            "debuggable" to ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0),
            "timeZone" to TimeZone.getDefault().id,
        )
    }

    fun export(): File {
        val file = File(context.cacheDir, "diagnostic_exports/sleep-diagnostics-${UUID.randomUUID()}.zip")
        store.exportZip(file)
        return file
    }

    private fun deviceState(): Map<String, Any?> {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val power = context.getSystemService(PowerManager::class.java)
        val storage = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        return mapOf(
            "batteryPercent" to if (level >= 0 && scale > 0) 100.0 * level / scale else null,
            "batteryTemperatureC" to battery?.let {
                if (it.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) {
                    it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
                } else null
            },
            "plugged" to if (plugged >= 0) (plugged != 0) else null,
            "batteryStatus" to battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
            "freeStorageBytes" to storage.usableSpace,
            "screenInteractive" to power?.isInteractive,
            "powerSaveMode" to power?.isPowerSaveMode,
            "deviceIdleMode" to power?.isDeviceIdleMode,
            "ignoringBatteryOptimizations" to power?.isIgnoringBatteryOptimizations(context.packageName),
        )
    }
}

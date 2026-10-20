package com.nightowl.sleep_monitor.monitor

import org.json.JSONObject

data class MonitorConfig(
    val mode: String = "timed",
    val recordingSeconds: Int = 300,
    val intervalSeconds: Int = 900,
    val endHour: Int = 7,
    val endMinute: Int = 0,
    val camera: String = "back",
) {
    fun validate() {
        require(mode in setOf("timed", "snore")) { "未知的监控模式" }
        require(recordingSeconds in 1..300) { "单段录制时长须为 1–300 秒" }
        require(intervalSeconds in 1..86_400) { "录制间隔须为 1 秒至 24 小时" }
        require(endHour in 0..23 && endMinute in 0..59) { "请选择有效的自动结束时间" }
        require(camera in setOf("front", "back")) { "未知的摄像头" }
    }

    fun toMap(): Map<String, Any> = mapOf(
        "mode" to mode,
        "recordingSeconds" to recordingSeconds,
        "intervalSeconds" to intervalSeconds,
        "endHour" to endHour,
        "endMinute" to endMinute,
        "camera" to camera,
    )

    fun toJson(): String = JSONObject(toMap()).toString()

    companion object {
        fun fromMap(map: Map<*, *>): MonitorConfig = MonitorConfig(
            mode = map["mode"] as String,
            recordingSeconds = (map["recordingSeconds"] as Number).toInt(),
            intervalSeconds = (map["intervalSeconds"] as Number).toInt(),
            endHour = (map["endHour"] as Number).toInt(),
            endMinute = (map["endMinute"] as Number).toInt(),
            camera = map["camera"] as String,
        ).also { it.validate() }

        fun fromJson(json: String): MonitorConfig {
            val value = JSONObject(json)
            return fromMap(mapOf(
                "mode" to value.getString("mode"),
                "recordingSeconds" to value.getInt("recordingSeconds"),
                "intervalSeconds" to value.getInt("intervalSeconds"),
                // Existing duration-based settings adopt the new 07:00 default.
                "endHour" to value.optInt("endHour", 7),
                "endMinute" to value.optInt("endMinute", 0),
                "camera" to value.getString("camera"),
            ))
        }
    }
}

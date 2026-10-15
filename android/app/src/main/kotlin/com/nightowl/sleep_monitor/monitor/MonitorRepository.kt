package com.nightowl.sleep_monitor.monitor

import android.content.Context
import android.os.Environment
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedClip(
    val id: String,
    val sessionId: String,
    val mode: String,
    val startedAt: Long,
    val durationMilliseconds: Long,
    val sizeBytes: Long,
    val path: String,
    val config: MonitorConfig,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id, "sessionId" to sessionId, "mode" to mode,
        "startedAt" to startedAt, "durationMilliseconds" to durationMilliseconds,
        "sizeBytes" to sizeBytes, "path" to path, "settings" to config.toMap(),
    )

    companion object {
        fun fromJson(value: JSONObject) = SavedClip(
            value.getString("id"), value.getString("sessionId"),
            value.getString("mode"), value.getLong("startedAt"),
            value.getLong("durationMilliseconds"), value.getLong("sizeBytes"),
            value.getString("path"), MonitorConfig.fromJson(value.getJSONObject("settings").toString()),
        )
    }
}

class MonitorRepository(context: Context) {
    private val preferences = context.getSharedPreferences("monitor", Context.MODE_PRIVATE)
    private val index = AtomicFile(File(context.filesDir, "recordings.json"))
    val clipsDirectory: File = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        ?.let { File(it, "clips") } ?: File(context.filesDir, "clips")

    fun settings(): MonitorConfig = preferences.getString("settings", null)
        ?.let { MonitorConfig.fromJson(it) } ?: MonitorConfig()

    fun saveSettings(settings: MonitorConfig) {
        settings.validate()
        check(preferences.edit().putString("settings", settings.toJson()).commit()) {
            "无法保存设置，请检查存储空间"
        }
    }

    fun sessionWasActive(): Boolean = preferences.getBoolean("activeSession", false)

    fun markSessionActive(active: Boolean) {
        check(preferences.edit().putBoolean("activeSession", active).commit()) {
            "无法保存监控状态"
        }
    }

    fun listClips(): List<SavedClip> {
        val bytes = try {
            index.openRead().use { it.readBytes() }
        } catch (_: java.io.FileNotFoundException) {
            return emptyList()
        }
        val values = JSONArray(bytes.toString(Charsets.UTF_8))
        return (0 until values.length())
            .map { SavedClip.fromJson(values.getJSONObject(it)) }
            .sortedByDescending { it.startedAt }
    }

    fun addClip(clip: SavedClip) {
        val clips = listClips() + clip
        val values = JSONArray()
        clips.forEach { values.put(JSONObject(it.toMap())) }
        val stream = index.startWrite()
        try {
            stream.write(values.toString().toByteArray(Charsets.UTF_8))
            index.finishWrite(stream)
        } catch (error: Exception) {
            index.failWrite(stream)
            throw error
        }
    }

    fun clipFile(id: String): File {
        val clip = listClips().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("找不到该片段")
        val file = File(clip.path)
        check(file.isFile) { "视频文件已不存在" }
        check(file.canonicalFile.parentFile == clipsDirectory.canonicalFile) {
            "视频文件路径无效"
        }
        return file
    }

    fun newClipFile(id: String): File {
        check(clipsDirectory.isDirectory || clipsDirectory.mkdirs()) { "无法创建视频目录" }
        return File(clipsDirectory, "$id.mp4")
    }
}

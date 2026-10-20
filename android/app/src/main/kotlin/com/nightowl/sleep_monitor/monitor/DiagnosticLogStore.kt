package com.nightowl.sleep_monitor.monitor

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bounded JSONL storage. All instances share a lock, including export snapshots. */
internal class DiagnosticLogStore(
    private val directory: File,
    private val maxFileBytes: Long = 256L * 1024,
    private val fileCount: Int = 4,
) {
    init {
        require(maxFileBytes > 0 && fileCount in 2..8)
    }

    fun append(line: String) = synchronized(lock) {
        require(!line.contains('\n') && !line.contains('\r'))
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxFileBytes) { "Diagnostic entry exceeds file limit" }
        check(directory.isDirectory || directory.mkdirs()) { "无法创建日志目录" }
        if (file(0).length() + bytes.size > maxFileBytes) {
            val oldest = file(fileCount - 1)
            if (oldest.exists() && !oldest.delete()) throw IOException("无法轮换旧日志")
            for (index in fileCount - 2 downTo 0) {
                val source = file(index)
                if (source.exists() && !source.renameTo(file(index + 1))) {
                    throw IOException("无法轮换运行日志")
                }
            }
        }
        file(0).appendBytes(bytes)
    }

    fun exportZip(destination: File) = synchronized(lock) {
        val sources = (fileCount - 1 downTo 0).map(::file).filter { it.isFile && it.length() > 0 }
        check(sources.isNotEmpty()) { "暂无运行日志，请先开始一次监控" }
        check(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "无法创建日志导出目录"
        }
        try {
            ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                sources.forEach { source ->
                    zip.putNextEntry(ZipEntry(source.name))
                    source.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    private fun file(index: Int) = File(directory, if (index == 0) "events.jsonl" else "events.$index.jsonl")

    private companion object {
        val lock = Any()
    }
}

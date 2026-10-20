package com.nightowl.sleep_monitor.monitor

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class DiagnosticLogStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rotationKeepsNewestEntriesAndCapsDiskUsage() {
        val directory = temporary.newFolder("logs")
        val store = DiagnosticLogStore(directory, maxFileBytes = 8, fileCount = 2)
        store.append("111111")
        store.append("222222")
        store.append("333333")
        assertEquals("333333\n", File(directory, "events.jsonl").readText())
        assertEquals("222222\n", File(directory, "events.1.jsonl").readText())
        assertEquals(2, directory.listFiles()!!.size)
        assertTrue(directory.listFiles()!!.all { it.length() <= 8 })
    }

    @Test fun limitCountsUtf8BytesRatherThanCharacters() {
        val directory = temporary.newFolder("logs")
        val store = DiagnosticLogStore(directory, maxFileBytes = 8, fileCount = 2)
        store.append("你好")
        store.append("世界")
        assertEquals(7L, File(directory, "events.jsonl").length())
        assertEquals("你好\n", File(directory, "events.1.jsonl").readText(Charsets.UTF_8))
    }

    @Test fun exportedSnapshotIsChronologicalAndDoesNotChangeAfterNewWrites() {
        val store = DiagnosticLogStore(temporary.newFolder("logs"), maxFileBytes = 8, fileCount = 2)
        store.append("111111")
        store.append("222222")
        val destination = File(temporary.root, "export/snapshot.zip")
        store.exportZip(destination)
        store.append("333333")
        ZipFile(destination).use { zip ->
            val entries = zip.entries().asSequence().toList()
            assertEquals(listOf("events.1.jsonl", "events.jsonl"), entries.map { it.name })
            assertEquals(listOf("111111\n", "222222\n"), entries.map { entry ->
                zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            })
        }
    }

    @Test fun malformedOrOversizedEntriesLeaveExistingLogsIntact() {
        val directory = temporary.newFolder("logs")
        val store = DiagnosticLogStore(directory, maxFileBytes = 8, fileCount = 2)
        store.append("valid")
        for (line in listOf("two\nlines", "carriage\rreturn", "12345678")) {
            assertTrue(runCatching { store.append(line) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals("valid\n", File(directory, "events.jsonl").readText())
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun emptyExportHasAnActionableErrorAndCreatesNoZip() {
        val store = DiagnosticLogStore(temporary.newFolder("logs"))
        val destination = File(temporary.root, "snapshot.zip")
        val error = runCatching { store.exportZip(destination) }.exceptionOrNull()
        assertEquals("暂无运行日志，请先开始一次监控", error?.message)
        assertFalse(destination.exists())
    }
}

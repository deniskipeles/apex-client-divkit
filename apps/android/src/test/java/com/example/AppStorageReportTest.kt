package com.example

import engine.fs.AppStorageReportBuilder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The storage report is what makes Android Settings' two figures add up: the buckets must be
 * disjoint and `totalBytes` must equal `packageBytes + dataBytes`.
 */
class AppStorageReportTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dataDir: File by lazy { File(temp.root, "data") }

    private fun file(path: String, bytes: Int): File =
        File(dataDir, path).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(bytes) { 7 })
        }

    @Test
    fun `partitions the data directory into non-overlapping buckets`() {
        file("files/workspaces/acme/media/logo.png", 1000)
        file("files/offline_cache/schema.json", 200)
        file("files/other.txt", 30)
        file("databases/workspace_acme.db", 400)
        file("databases/workspace_acme.db-wal", 600)
        file("databases/other.db", 50)
        file("cache/image_source/picked_1.jpg", 70)
        file("shared_prefs/prefs.xml", 10)
        // The installed package lives outside `dataDir`, which is why Settings reports it
        // separately from Data.
        val packageFile = File(temp.root, "package/base.apk").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(9000) { 7 })
        }

        val report = AppStorageReportBuilder.build(dataDir, listOf(packageFile))

        assertEquals(1000L, report.workspaceBytes)
        assertEquals(1000L, report.tenantDatabaseBytes)
        assertEquals(50L, report.otherDatabaseBytes)
        assertEquals(200L, report.offlineCacheBytes)
        assertEquals(30L, report.otherFileBytes)
        assertEquals(70L, report.cacheBytes)
        assertEquals(10L, report.otherDataBytes)
        assertEquals(9000L, report.packageBytes)
        assertEquals(2360L, report.dataBytes)
        assertEquals(11360L, report.totalBytes)
    }

    @Test
    fun `counts an unrecognised directory as other data instead of dropping it`() {
        file("app_webview/Default/Local Storage/x.bin", 64)
        file("code_cache/compiled.bin", 16)

        val report = AppStorageReportBuilder.build(dataDir, emptyList())

        assertEquals(80L, report.otherDataBytes)
        assertEquals(80L, report.dataBytes)
    }

    @Test
    fun `missing directories contribute zero rather than throwing`() {
        val report = AppStorageReportBuilder.build(dataDir, emptyList())

        assertEquals(0L, report.dataBytes)
        assertEquals(0L, report.totalBytes)
    }
}

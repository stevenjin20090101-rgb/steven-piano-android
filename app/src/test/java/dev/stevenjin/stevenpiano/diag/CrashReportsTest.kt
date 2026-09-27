// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset

/** The app's own crash reports (v1.4): one file a crash, what it holds, the last five kept. */
class CrashReportsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_790_000_000_000L
    private val dir get() = File(tmp.root, "diagnostics")
    private val facts = DiagnosticsText.Facts("1.4", 8, "release", "Google", "Pixel Tablet", "14", 34)

    private fun reports(tail: List<String> = emptyList()) =
        CrashReports(dir, header = { DiagnosticsText.header(facts) }, linkTail = { tail }, clock = { now }, zone = ZoneOffset.UTC)

    @Test
    fun `a report holds the version, the device, the thread and the stack`() {
        val reports = reports(tail = listOf("2026-09-21 14:13:19.000 Connected to Steven Piano"))
        reports.write(Thread("steven-piano-scheduler"), IllegalStateException("boom", IllegalArgumentException("cause")))
        val file = reports.reports().single()
        assertEquals("crash-$now.txt", file.name)
        val text = file.readText()
        assertTrue(text.startsWith("Steven Piano crash report\nTime: 2026-09-21 14:13:20.000 +00:00\n"))
        assertTrue("App: Steven Piano 1.4 (build 8, release)" in text)
        assertTrue("Device: Google Pixel Tablet" in text)
        assertTrue("Android: 14 (API 34)" in text)
        assertTrue("Thread: steven-piano-scheduler" in text)
        assertTrue("java.lang.IllegalStateException: boom" in text)
        assertTrue("Caused by: java.lang.IllegalArgumentException: cause" in text)
        assertTrue("\tat dev.stevenjin.stevenpiano.diag.CrashReportsTest" in text)
        assertTrue("Piano link, last 1 lines:\n2026-09-21 14:13:19.000 Connected to Steven Piano\n" in text)
        assertEquals(now, reports.latestAt())
    }

    @Test
    fun `only the last five are kept, newest first`() {
        val reports = reports()
        repeat(7) {
            now += 60_000
            reports.write(Thread.currentThread(), RuntimeException("crash $it"))
        }
        val kept = reports.reports()
        assertEquals(5, kept.size)
        assertEquals((6 downTo 2).map { "crash $it" }, kept.map { it.readText().substringAfter("RuntimeException: ").substringBefore('\n') })
        assertEquals(now, reports.latestAt())
        assertEquals(5, dir.list()!!.size)
    }

    @Test
    fun `two crashes in the same millisecond keep both`() {
        val reports = reports()
        reports.write(Thread.currentThread(), RuntimeException("first"))
        reports.write(Thread.currentThread(), RuntimeException("second"))
        assertEquals(setOf("crash-$now.txt", "crash-$now-1.txt"), dir.list()!!.toSet())
    }

    @Test
    fun `places a message names are taken out`() {
        val message = "Permission Denial: reading content://com.android.externalstorage.documents/document/primary%3AMusic%2FClair%20de%20lune.mid " +
            "and /storage/emulated/0/Music/Nocturne.mid via https://en.wikipedia.org/wiki/Clair_de_lune"
        reports().write(Thread.currentThread(), SecurityException(message))
        val text = reports().reports().single().readText()
        assertFalse("Clair" in text)
        assertFalse("Nocturne" in text)
        assertTrue("content://(removed)" in text)
        assertTrue("/storage/(removed)" in text)
        assertTrue("https://en.wikipedia.org/(removed)" in text)
    }

    @Test
    fun `a handler that cannot write is quiet, and no folder means no reports`() {
        assertNull(reports().latestAt())
        dir.parentFile!!.mkdirs()
        dir.writeText("a file where the folder should be")
        reports().write(Thread.currentThread(), RuntimeException("x"))
        assertEquals(emptyList<File>(), reports().reports())
        assertEquals(null, CrashReports.epochOf("notes.txt"))
        assertEquals(12L, CrashReports.epochOf("crash-12-3.txt"))
    }
}

// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.diag

import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.update.Manifests
import dev.stevenjin.stevenpiano.update.UpdateFailures
import dev.stevenjin.stevenpiano.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset
import java.util.zip.ZipFile

/** Share diagnostics' zip (v1.4): exactly about, settings, the link log and the crash reports; nothing from the library. */
class DiagnosticsExporterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_790_000_000_000L
    private val facts = DiagnosticsText.Facts("1.4", 8, "release", "Google", "Pixel Tablet", "14", 34)
    private val linkLog = LinkLog(clock = { now }, zone = ZoneOffset.UTC)
    private val crashes by lazy { CrashReports(File(tmp.root, "files/diagnostics"), { DiagnosticsText.header(facts) }, { linkLog.tail(50) }, { now }, ZoneOffset.UTC) }
    private val settings = PianoSettings(lastDeviceAddress = "C8:2E:18:00:11:22", lastDeviceName = "Steven Piano", transpose = -2, webEnabled = true, webPinSet = true)

    private fun exporter() = DiagnosticsExporter(
        File(tmp.root, "cache/diagnostics"),
        crashes,
        linkLog,
        about = {
            DiagnosticsText.about(facts, deviceOwner = true, update = UpdateState.Failed(UpdateFailures.UNREACHABLE, Manifests.manifest()), checkForUpdates = true,
                lastCheckedAt = "2026-09-21 14:10:00 +00:00", link = LinkState.Connected("Steven Piano", 247), exportedAt = "2026-09-21 14:13:20 +00:00")
        },
        settings = { DiagnosticsText.settings(settings) },
        clock = { now },
        zone = ZoneOffset.UTC,
    )

    @Test
    fun `the zip holds about, settings, the link log and the crash reports, and nothing else`() {
        linkLog.add("Connected to Steven Piano (C8:2E:18:00:11:22), MTU 247, with its console")
        crashes.write(Thread.currentThread(), IllegalStateException("first"))
        now += 1_000
        crashes.write(Thread.currentThread(), IllegalStateException("second"))
        val zip = exporter().export()
        assertEquals("steven-piano-diagnostics-2026-09-21-141321.zip", zip.name)
        val entries = ZipFile(zip).use { file -> file.entries().toList().associate { it.name to file.getInputStream(it).readBytes().toString(Charsets.UTF_8) } }
        assertEquals(listOf("about.txt", "settings.txt", "link.log", "crash-${now}.txt", "crash-${now - 1_000}.txt"), entries.keys.toList())

        val about = entries.getValue("about.txt")
        assertTrue("App: Steven Piano 1.4 (build 8, release)" in about)
        assertTrue("Device: Google Pixel Tablet" in about)
        assertTrue("Android: 14 (API 34)" in about)
        assertTrue("Device owner: yes" in about)
        assertTrue("Updates: failed: Couldn't reach the update server. (1.4 on offer)" in about)
        assertTrue("Piano link: connected (MTU 247)" in about)

        val prefs = entries.getValue("settings.txt")
        assertTrue("lastDeviceAddress = C8:2E:18:00:11:22\n" in prefs)
        assertTrue("transpose = -2\n" in prefs)
        assertTrue("checkForUpdates = true\n" in prefs)
        assertTrue("preRollMs = 2000\n" in prefs)
        assertTrue("channelVolumes = {}\n" in prefs)
        assertTrue("appearance = SYSTEM\ndisplayModeAfterMinute = false\nstandbyCanvas = BLACK\nstandbyShows = ART_AND_NOTES\n" in prefs)
        assertTrue("webEnabled = true\nwebGuests = false\nwebApproveFirst = true\nwebOnWifi = false\nwebHostName = (none)\nwebPinSet = true\n" in prefs)
        assertTrue("kioskEnabled = false\nkioskPinSet = false\n" in prefs)
        assertFalse("the PINs' hashes and salts never travel", "Pin" in prefs.replace("webPinSet", "").replace("kioskPinSet", ""))
        assertEquals(33, prefs.lines().count { it.isNotEmpty() })

        assertTrue(entries.getValue("link.log").endsWith("with its console\n"))
        assertTrue("IllegalStateException: second" in entries.getValue("crash-${now}.txt"))
    }

    @Test
    fun `nothing from the library travels, not even inside a crash's message`() {
        // A content URI as Android writes it, the file's name percent-encoded.
        crashes.write(Thread.currentThread(), SecurityException("No access to content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FClair%20de%20lune.mid"))
        val zip = exporter().export()
        val everything = ZipFile(zip).use { file -> file.entries().toList().joinToString("\n") { file.getInputStream(it).readBytes().toString(Charsets.UTF_8) } }
        assertFalse("Clair" in everything)
        assertFalse("lune" in everything)
        assertFalse("pieces/" in everything)
        assertTrue("content://(removed)" in everything)
    }

    @Test
    fun `each export replaces the one before, and an empty link log says so`() {
        val first = exporter().export()
        now += 60_000
        val second = exporter().export()
        assertFalse(first.exists())
        assertEquals(listOf(second.name), second.parentFile!!.list()!!.toList())
        val log = ZipFile(second).use { file -> file.getInputStream(file.getEntry("link.log")).readBytes().toString(Charsets.UTF_8) }
        assertEquals("No piano link lines since the app started.\n", log)
    }
}

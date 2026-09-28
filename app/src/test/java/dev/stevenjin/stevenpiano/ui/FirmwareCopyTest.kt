// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.firmware.FirmwareFailures
import dev.stevenjin.stevenpiano.firmware.FirmwareManifest
import dev.stevenjin.stevenpiano.firmware.FirmwareState
import dev.stevenjin.stevenpiano.firmware.OtaExample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** The Firmware page's words (DESIGN.md › v1.6 — M21). */
class FirmwareCopyTest {
    private val manifest = FirmwareManifest.parse(OtaExample.manifestJson())

    @Test
    fun `the piano's version reads as release and build`() {
        assertEquals("2.0.0 · a1b2c3d", FirmwareCopy.version("2.0.0+a1b2c3d"))
        assertEquals("a version that isn't semver shows as it came", "emulator", FirmwareCopy.version("emulator"))
        assertEquals("Unknown — this firmware has no version. Flash 2.0.0 over USB once.", FirmwareCopy.NO_VERSION)
    }

    @Test
    fun `the transfer's lines, with the time left once a rate is known`() {
        assertEquals("Downloading · 0.9 MB", FirmwareCopy.downloading(900_000, Locale.US))
        assertEquals("Downloading · 0,9 MB", FirmwareCopy.downloading(900_000, Locale.GERMANY))
        assertEquals("Sending · 0%", FirmwareCopy.sending(0, 991_232, 0))
        assertEquals("Sending · 38% · about 1 min left", FirmwareCopy.sending(376_669, 991_232, 10_000))
        assertEquals("Sending · 10% · about 2 min left", FirmwareCopy.sending(99_124, 991_232, 8_000))
        assertEquals("Sending · 0% · about 2 min left", FirmwareCopy.sending(0, 991_232, 7_000))
        assertEquals("Sending · 99% · less than a minute left", FirmwareCopy.sending(981_320, 991_232, 13_000))
        assertEquals("Sending · 100% · less than a minute left", FirmwareCopy.sending(991_232, 991_232, 13_000))
        assertNull(FirmwareCopy.timeLeft(1_000, 0))
    }

    @Test
    fun `the outcome and the check's line`() {
        assertEquals("Updated to 2.1.0", FirmwareCopy.done("2.1.0", confirming = false))
        assertEquals("Updated to 2.1.0 · confirming…", FirmwareCopy.done("2.1.0", confirming = true))
        assertEquals("Update the piano to 2.1.0", FirmwareCopy.update("2.1.0"))
        assertNull(FirmwareCopy.checkLine(FirmwareState.Idle))
        assertEquals("Checking for piano updates…", FirmwareCopy.checkLine(FirmwareState.Checking))
        assertEquals("The piano's firmware is up to date.", FirmwareCopy.checkLine(FirmwareState.UpToDate("2.1.0")))
        assertEquals("Version 2.1.0 is available.", FirmwareCopy.checkLine(FirmwareState.Available(manifest)))
        assertEquals(
            FirmwareFailures.OFFLINE,
            FirmwareCopy.checkLine(FirmwareState.Failed(FirmwareFailures.OFFLINE, retryable = true, manifest = manifest, check = true)),
        )
        assertNull("a transfer's failure is said below, not under the check", FirmwareCopy.checkLine(FirmwareState.Failed(FirmwareFailures.DIDNT_FINISH, true, manifest)))
    }

    @Test
    fun `the notification's line follows the transfer, and is gone once it is over`() {
        assertEquals("Downloading · 0.4 MB", FirmwareCopy.progress(FirmwareState.Downloading(manifest, 400_000, 991_232)))
        assertEquals("Checking the download…", FirmwareCopy.progress(FirmwareState.Verifying(manifest)))
        assertEquals("The piano is checking the update…", FirmwareCopy.progress(FirmwareState.PianoVerifying(manifest)))
        assertEquals("Restarting the piano…", FirmwareCopy.progress(FirmwareState.Restarting(manifest)))
        assertNull(FirmwareCopy.progress(FirmwareState.Done("2.1.0")))
        assertEquals(0.5f, FirmwareCopy.fraction(FirmwareState.Sending(manifest, 495_616, 991_232, 0))!!, 0.0001f)
        assertNull(FirmwareCopy.fraction(FirmwareState.Restarting(manifest)))
    }
}

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Firmware and status with the settings locked in kiosk: an update in progress stays in view and
 * its page opens without the PIN; how it ended stays in view; an offer, a check's failure and every
 * other page stay behind the PIN.
 */
class LockedFirmwareTest {
    private val manifest = FirmwareManifest.parse(OtaExample.manifestJson())
    private val inProgress = listOf(
        FirmwareState.Downloading(manifest, 0, 991_232),
        FirmwareState.Verifying(manifest),
        FirmwareState.Sending(manifest, 380_000, 991_232, 13_000),
        FirmwareState.PianoVerifying(manifest),
        FirmwareState.Restarting(manifest),
    )
    private val ended = listOf(
        FirmwareState.Done("2.1.0", confirming = true),
        FirmwareState.Done("2.1.0"),
        FirmwareState.Failed(FirmwareFailures.DIDNT_FINISH, retryable = true, manifest = manifest),
    )
    private val settings = listOf(
        FirmwareState.Idle,
        FirmwareState.Checking,
        FirmwareState.UpToDate("2.0.0"),
        FirmwareState.Available(manifest),
        FirmwareState.UsbOnly(manifest),
        FirmwareState.NeedsNewerApp(manifest),
        FirmwareState.Failed(FirmwareFailures.OFFLINE, retryable = true, manifest = manifest, check = true),
    )

    @Test
    fun `the locked page keeps an update in progress in view, and how it ended, but no offer or check`() {
        for (state in inProgress + ended) assertTrue(state.toString(), LockedFirmware.shows(state))
        for (state in settings) assertFalse(state.toString(), LockedFirmware.shows(state))
    }

    @Test
    fun `while an update runs Firmware and status opens without the PIN, and no other page does`() {
        for (state in inProgress) {
            assertTrue(state.toString(), LockedFirmware.opensUnlocked(SettingsPage.Firmware, state))
            for (page in SettingsPage.entries - SettingsPage.Firmware) assertFalse("$page, $state", LockedFirmware.opensUnlocked(page, state))
        }
        for (state in ended + settings) assertFalse("after it, the PIN again: $state", LockedFirmware.opensUnlocked(SettingsPage.Firmware, state))
    }
}

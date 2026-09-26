// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Piano tab's words for each reason a connection failed, and how the link's log spells Bluetooth's numbers and names. */
class LinkErrorCopyTest {
    private val notFound = "Can't find Steven Piano. Make sure it's powered on, within range, and that no iPad, phone or Mac is " +
        "connected to it — it hides while another device is connected."

    @Test
    fun `each new reason reads exactly as specified, with its number`() {
        assertEquals(notFound, LinkError.NotFound().message)
        assertEquals("$notFound On some devices, Bluetooth scanning also needs Location turned on.", LinkError.NotFound(locationOff = true).message)
        assertEquals(
            "This device is paired with Steven Piano in Bluetooth settings. Forget it there, then tap Retry.",
            LinkError.Paired.message,
        )
        assertEquals(
            "Found Steven Piano but the connection failed (code 133). Tap Retry; if it keeps failing, restart the piano.",
            LinkError.ConnectFailed(133).message,
        )
        assertEquals(
            "Found Steven Piano but the connection failed (timeout). Tap Retry; if it keeps failing, restart the piano.",
            LinkError.ConnectFailed(null).message,
        )
        assertEquals(
            "Bluetooth scanning isn't available right now (code 2). Turn Bluetooth off and on, then tap Retry.",
            LinkError.ScanFailed(2).message,
        )
    }

    @Test
    fun `the reasons kept from before read as they did`() {
        assertEquals("Bluetooth is off. Turn it on to connect to the piano.", LinkError.BluetoothOff.message)
        assertEquals("Steven Piano needs permission to find and connect to the piano.", LinkError.PermissionMissing.message)
        assertEquals("Location is off. This phone needs it on to find Bluetooth devices.", LinkError.LocationOff.message)
        assertEquals(
            "Another piano called Steven Piano is nearby, not the one this phone knows. Connect to it only if it's yours.",
            LinkError.OtherPiano.message,
        )
    }

    @Test
    fun `the error state carries the copy, and the other piano's address when there is one`() {
        assertEquals(LinkState.Error(LinkError.ScanFailed(6), LinkError.ScanFailed(6).message), LinkError.ScanFailed(6).toState())
        assertEquals("D4:D4:D4:00:00:01", LinkError.OtherPiano.toState("D4:D4:D4:00:00:01").otherAddress)
    }

    @Test
    fun `names from the air are made safe for one log line`() {
        assertEquals("(no name)", BleCodes.name(null))
        assertEquals("\"Steven Piano\"", BleCodes.name(PianoBluetooth.NAME))
        assertEquals("\"a?b?c?d\"", BleCodes.name("a\nb\u0000c d"))
        assertEquals("\"" + "x".repeat(40) + "\"", BleCodes.name("x".repeat(40)))
        assertEquals("\"" + "x".repeat(40) + "…\"", BleCodes.name("x".repeat(41)))
    }

    @Test
    fun `codes are spelled out for the log, unknown ones kept as numbers`() {
        assertEquals("133 (GATT_ERROR: Android's catch-all)", BleCodes.gattStatus(133))
        assertEquals("4242", BleCodes.gattStatus(4242))
        assertEquals("6 (scanning too frequently)", BleCodes.scanFailure(6))
        assertEquals("-1 (no scanner: Bluetooth is off)", BleCodes.scanFailure(PianoScanner.NO_SCANNER))
        assertEquals("99", BleCodes.scanFailure(99))
    }
}

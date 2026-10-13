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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the link's failures are put into words where something varies (a code, a device's name, Location), and how the
 * link's log spells Bluetooth's numbers and names (v1.24 — M57: the rules, not each reason's copy).
 */
class LinkErrorCopyTest {
    @Test
    fun `a reason carries its code, or the timeout, and Location only when it is off`() {
        assertTrue(LinkError.ConnectFailed(133).message.contains("(code 133)"))
        assertTrue(LinkError.ConnectFailed(null).message.contains("(timeout)"))
        assertTrue(LinkError.ScanFailed(2).message.contains("(code 2)"))
        val plain = LinkError.NotFound().message
        assertEquals("$plain On some devices, Bluetooth scanning also needs Location turned on.", LinkError.NotFound(locationOff = true).message)
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
        assertEquals("\"a?b?c?d\"", BleCodes.name("a\nb\u0000c\u2028d"))
        assertEquals("\"" + "x".repeat(40) + "\"", BleCodes.name("x".repeat(40)))
        assertEquals("\"" + "x".repeat(40) + "…\"", BleCodes.name("x".repeat(41)))
    }

    @Test
    fun `codes are spelled out for the log, unknown ones kept as numbers`() {
        assertTrue(BleCodes.gattStatus(133).startsWith("133 ("))
        assertEquals("4242", BleCodes.gattStatus(4242))
        assertTrue(BleCodes.scanFailure(6).startsWith("6 ("))
        assertEquals("99", BleCodes.scanFailure(99))
    }

    @Test
    fun `a MIDI piano's problems name it (v1_11 M29)`() {
        for (error in listOf(LinkError.InstrumentGone("FP-30X"), LinkError.InstrumentBusy("FP-30X"), LinkError.InstrumentPairing("FP-30X"))) {
            assertTrue(error.message, error.message.contains("FP-30X"))
        }
    }
}

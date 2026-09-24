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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The emulator's stand-in console answers the way BLE_SETTINGS.md says the firmware will. */
class EmulatedConsoleTest {
    private val console = EmulatedConsole()

    @Test
    fun `dump lists the facts, then every tunable, then end`() {
        val dump = console.handle("dump")
        assertEquals(
            listOf("!proto=1", "!fw=emulator", "!ble=1", "!boards=OK,OK,OK,OK,OK,OK,OK", "!i2cfails=0", "!pedalboard=absent", "!uptime=123"),
            dump.take(7),
        )
        assertEquals("end", dump.last())
        assertEquals(7 + 43 + 1, dump.size)
        assertTrue(dump.all { it == "end" || '=' in it })
    }

    @Test
    fun `a value set is what get reads back, and a number out of range changes nothing`() {
        assertEquals(listOf("  ledbright = 40"), console.handle("ledbright 40"))
        assertEquals(listOf("ledbright=40"), console.handle("get ledbright"))
        assertEquals(listOf("  ledbright out of range (0..255)"), console.handle("ledbright 300"))
        assertEquals("40", console.valueOf("ledbright"))
        assertEquals(listOf("  velcurve = 1.25"), console.handle("velcurve 1.25"))
        assertEquals("1.25", console.valueOf("velcurve"))
        assertEquals(listOf("!uptime=123"), console.handle("get !uptime"))
        assertEquals(listOf("  unknown setting"), console.handle("get nonsense"))
    }

    @Test
    fun `restrike takes 0 or 40 to 1000, and hold clamps instead of refusing`() {
        assertEquals(listOf("  restrike = 0"), console.handle("restrike 0"))
        assertTrue(console.handle("restrike 20").single().contains("out of range"))
        assertEquals(listOf("  restrike = 40"), console.handle("restrike 40"))
        console.handle("hold 20")
        assertEquals("50", console.valueOf("hold"))
    }

    @Test
    fun `bench commands are refused over Bluetooth, and nothing changes`() {
        for (line in listOf("fire 0 0 4095", "keyforce_white 1.5", "pedaltest 300", "reset")) {
            assertEquals(line, listOf("  refused over Bluetooth — use the USB console"), console.handle(line))
        }
        assertEquals("1.00", console.valueOf("keyforce_white"))
    }

    @Test
    fun `volume below 100 turns full power off, as the firmware does`() {
        assertEquals("1", console.valueOf("fullpower"))
        val reply = console.handle("volume 80")
        assertTrue(reply.first().startsWith("[vol] Full Power turned OFF"))
        assertEquals("0", console.valueOf("fullpower"))
    }

    @Test
    fun `a preset changes several values and saves`() {
        val reply = console.handle("soft")
        assertEquals("[settings] saved to NVS", reply.first())
        assertEquals("55", console.valueOf("volume"))
        assertEquals("0", console.valueOf("fullpower"))
    }

    @Test
    fun `status is text with a leading blank line, and unknown commands say so`() {
        val status = console.handle("status")
        assertEquals("", status.first())
        assertTrue(status.any { it.contains("boards:") })
        assertEquals(listOf("  unknown command — type 'help'"), console.handle("wibble"))
    }

    @Test
    fun `a console line is at most 79 characters and one line`() {
        assertEquals(80, PianoConsole.encode("x".repeat(79))!!.size)
        assertNull(PianoConsole.encode("x".repeat(80)))
        assertNull(PianoConsole.encode("ledbright 40\nsave"))
        assertEquals("get ledbright\n", String(PianoConsole.encode("get ledbright")!!))
    }
}

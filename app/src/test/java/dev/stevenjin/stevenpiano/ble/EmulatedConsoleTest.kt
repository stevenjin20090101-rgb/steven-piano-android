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
        assertEquals("M80: the LED colour's fact after the others", "!ledcolorproto=1", dump[7])
        assertEquals("M81: the strip at rest's after it", "!ledrestproto=1", dump[8])
        assertEquals("M83: the loudness steps' facts after that", listOf("!stepsproto=1", "!stepkeys=0"), dump.subList(9, 11))
        assertEquals("M85, 2.1.6: boardaddrproto and strays after those", listOf("!boardaddrproto=1", "!strays="), dump.subList(11, 13))
        assertEquals("M85: boardaddr the last row", "boardaddr=40,41,42,43,44,45,46", dump[dump.size - 2])
        assertEquals("2.1.4: soft release's three gone; 2.1.5: velsteps; 2.1.6: boardaddr", 13 + 47 + 1, dump.size)
        assertTrue(dump.none { it.startsWith("softrelease=") || it.startsWith("releasepwm=") || it.startsWith("releasems=") })
        assertEquals(listOf("  unknown setting"), console.handle("get softrelease"))
        assertEquals(listOf("  unknown — soft release was removed in 2.1.4 (every key-up is a plain cut)"), console.handle("softrelease 1"))
        assertTrue(dump.all { it == "end" || '=' in it })
        // M80, firmware 2.1.2: the strip's colour after rainspeed, read back lower case, refused in the firmware's words.
        val rainspeed = dump.indexOfFirst { it.startsWith("rainspeed=") }
        assertEquals(listOf("ledcolor=ffb066", "ledtint=off", "ledfade=1500", "ledambient=0", "ledrest=0"), dump.subList(rainspeed + 1, rainspeed + 6))
        assertEquals(listOf("  ledcolor = ff8800"), console.handle("ledcolor FF8800"))
        assertEquals(listOf("ledcolor=ff8800"), console.handle("get ledcolor"))
        assertEquals(listOf("  usage: ledcolor RRGGBB (6 hex digits)"), console.handle("ledcolor #ff8800"))
        assertEquals(listOf("  usage: ledtint RRGGBB|off"), console.handle("ledtint red"))
        assertEquals(listOf("  ledfade out of range (0..5000 ms)"), console.handle("ledfade 6000"))
        assertEquals(listOf("  ledambient out of range (0..64)"), console.handle("ledambient 65"))
        // M81, firmware 2.1.3: exactly 0 or 1, back to 0 when the link that set it drops.
        assertEquals(listOf("  usage: ledrest 0|1"), console.handle("ledrest on"))
        assertEquals(listOf("  LED rest = 1 (live only, never saved; ends with this Bluetooth link)"), console.handle("ledrest 1"))
        assertEquals(listOf("ledrest=1"), console.handle("get ledrest"))
        assertTrue(console.linkDropped())
        assertEquals(listOf("ledrest=0"), console.handle("get ledrest"))
        val older = EmulatedConsole(ledColour = false, boardAddr = false).handle("dump")
        assertEquals("2.1.1 and earlier: none of it (and no soft release: the emulator has 2.1.4's)", 7 + 40 + 1, older.size)
        val noRest = EmulatedConsole(ledRest = false, boardAddr = false)
        assertEquals("2.1.2: the colour without the rest", 8 + 44 + 1, noRest.handle("dump").size)
        assertEquals(listOf("  unknown command — type 'help'"), noRest.handle("ledrest 1"))
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

    @Test
    fun `a fact is got with or without its mark, and answered with it (v1_26 M60, as the firmware's get)`() {
        assertEquals(listOf("!boards=OK,OK,OK,OK,OK,OK,OK"), console.handle("get boards"))
        assertEquals(listOf("!boards=OK,OK,OK,OK,OK,OK,OK"), console.handle("get !boards"))
        assertEquals(listOf("!fw=emulator"), console.handle("get fw"))
        assertEquals("a setting stays a setting", listOf("ledbright=160"), console.handle("get ledbright"))
    }

    @Test
    fun `with the emulator's power plug the boards follow its relay (v1_26 M60)`() {
        var rail: String? = "MISSING,MISSING,MISSING,MISSING,MISSING,MISSING,MISSING"
        console.railBoards = { rail }
        assertEquals(listOf("!boards=$rail"), console.handle("get boards"))
        assertTrue(console.handle("dump").contains("!boards=$rail"))
        assertTrue(console.handle("status").any { it.contains("[0] (MIDI 24-35): MISSING") })
        rail = "OK,OK,OK,OK,MISSING,OK,OK"
        assertEquals(listOf("!boards=OK,OK,OK,OK,MISSING,OK,OK"), console.handle("get !boards"))
        rail = null   // no fake plug: the piano's own fact
        assertEquals(listOf("!boards=OK,OK,OK,OK,OK,OK,OK"), console.handle("get boards"))
    }

    @Test
    fun `firmware 2_1_5's loudness steps answer word for word, one calstrike every 300 ms (M83)`() {
        var clock = 10_000L
        val piano = EmulatedConsole(now = { clock })
        assertEquals(listOf("velsteps=5"), piano.handle("get velsteps"))
        val dump = piano.handle("dump")
        assertEquals("velsteps after velcurve", "velsteps=5", dump[dump.indexOfFirst { it.startsWith("velcurve=") } + 1])
        // velsteps: 0 or 2..8, in the firmware's words.
        assertEquals(listOf("  velsteps out of range (0 or 2..8)"), piano.handle("velsteps 1"))
        assertEquals(listOf("  velsteps out of range (0 or 2..8)"), piano.handle("velsteps 9"))
        assertEquals(listOf("  velsteps = 4: every note snaps to one of 4 fixed loudness steps (shows once fullpower is 0)"), piano.handle("velsteps 4"))
        assertEquals(listOf("  velsteps = 0: the smooth velocity curve"), piano.handle("velsteps 0"))
        piano.handle("fullpower 0")
        assertEquals(listOf("  velsteps = 6: every note snaps to one of 6 fixed loudness steps"), piano.handle("velsteps 6"))
        // calstrike: struck, then too soon within 300 ms whatever the key, then struck again.
        assertEquals(listOf("  calstrike 60 at 2400: struck for 100 ms"), piano.handle("calstrike 60 2400"))
        clock += 299
        assertEquals(listOf("  calstrike refused: too soon (one every 300 ms)"), piano.handle("calstrike 61 900"))
        clock += 1
        assertEquals(listOf("  calstrike 61 at 900: struck for 100 ms"), piano.handle("calstrike 61 900"))
        assertEquals(listOf("  usage: calstrike <note 0..127> <pwm 1..4096>"), piano.handle("calstrike 61 0"))
        assertEquals(listOf("  usage: calstrike <note 0..127> <pwm 1..4096>"), piano.handle("calstrike 61 900 1"))
        clock += 300
        assertEquals("off the piano's 84: no board", listOf("  calstrike refused: that key is busy or has no board"), piano.handle("calstrike 12 900"))
        piano.railBoards = { "MISSING,MISSING,MISSING,MISSING,MISSING,MISSING,MISSING" }
        assertEquals("the 24 V off", listOf("  calstrike refused: that key is busy or has no board"), piano.handle("calstrike 60 900"))
        piano.railBoards = { null }
        piano.updating = { true }
        assertEquals(listOf("  refused — updating firmware"), piano.handle("calstrike 60 900"))
        piano.updating = { false }
        // steptable: a row, read back, cleared; the usage for anything else.
        assertEquals(listOf("  steptable 60: not calibrated"), piano.handle("steptable 60"))
        assertEquals(listOf("  steptable 60 = 3 steps (saved by save or the auto-save)"), piano.handle("steptable 60 1200 1700 2600"))
        assertEquals(listOf("  steptable 60: 1200 1700 2600"), piano.handle("steptable 60"))
        assertEquals(listOf("!stepkeys=1"), piano.handle("get stepkeys"))
        val usage = listOf("  usage: steptable <note> [p1..pN rising 1..4095 | clear] | steptable clear")
        assertEquals("not rising", usage, piano.handle("steptable 61 1200 1200"))
        assertEquals("one step", usage, piano.handle("steptable 61 1200"))
        assertEquals("over 4095", usage, piano.handle("steptable 61 1200 4096"))
        assertEquals("nine steps", usage, piano.handle("steptable 61 1 2 3 4 5 6 7 8 9"))
        assertEquals(listOf("  steptable 60 cleared"), piano.handle("steptable 60 clear"))
        piano.handle("steptable 62 100 200")
        assertEquals(listOf("  steptable cleared: every key spreads its steps over min..max"), piano.handle("steptable clear"))
        assertEquals(listOf("!stepkeys=0"), piano.handle("get !stepkeys"))
        // 2.1.4: none of it.
        val older = EmulatedConsole(steps = false)
        assertTrue(older.handle("dump").none { it.startsWith("velsteps=") || it.startsWith("!stepsproto") || it.startsWith("!stepkeys") })
        assertEquals(listOf("  unknown command — type 'help'"), older.handle("calstrike 60 2400"))
    }
}

// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

import dev.stevenjin.stevenpiano.midi.MidiBatch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The console on the piano link: found at connect, switched on first, MIDI before it, lines both ways. */
@OptIn(ExperimentalCoroutinesApi::class)
class GattConsoleTest {
    private val executor = FakeLinkExecutor()
    private val radio = FakeRadio()
    private val logged = mutableListOf<String>()
    private val link = GattPianoLink(radio, executor, { _, _ -> }, { true }, log = { logged += it })
    private val address = "C8:2E:18:00:11:22"

    /** Connects to a piano with (or without) the console; the subscription is left in flight. */
    private fun connect(mtu: Int = 255, console: Boolean = true): FakeGatt {
        link.connect(null)
        executor.runDue()
        radio.find(address, PianoBluetooth.NAME)
        executor.runDue()
        val gatt = radio.connections.last()
        gatt.hasConsole = console
        gatt.connected()
        executor.runDue()
        gatt.mtu(mtu)
        executor.runDue()
        gatt.discovered()
        executor.runDue()
        return gatt
    }

    private fun connectAndSubscribe(mtu: Int = 255): FakeGatt = connect(mtu).also {
        it.subscribeDone()
        executor.runDue()
    }

    private fun notes(count: Int) = MidiBatch().apply { repeat(count) { add(0x90, 24 + it, 64) } }

    /** Completes each write as it goes out, letting the pace run, until nothing more is written. */
    private fun drain(gatt: FakeGatt) {
        repeat(200) {
            executor.advance(1)
            gatt.writeDone()
            executor.runDue()
        }
    }

    private fun TestScope.collectLines(): MutableList<String> {
        val lines = mutableListOf<String>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { link.console!!.lines.collect { lines += it } }
        return lines
    }

    @Test
    fun `a piano with the console has it once connected, and switching on its replies is the first write`() {
        val gatt = connect()
        assertEquals(LinkState.Connected(PianoBluetooth.NAME, 255), link.state.value)
        assertNotNull(link.console)
        assertEquals(listOf("subscribe"), gatt.ops)
        link.console!!.sendLine("dump")
        executor.runDue()
        assertEquals("nothing else while the subscription is in flight", listOf("subscribe"), gatt.ops)
        gatt.subscribeDone()
        executor.runDue()
        assertEquals(listOf("subscribe", "console"), gatt.ops)
        assertEquals(listOf("dump"), gatt.consoleLinesWritten)
    }

    @Test
    fun `a piano without the console has none, and MIDI flows as before`() {
        val gatt = connect(console = false)
        assertNull(link.console)
        link.send(notes(3), dropPending = false)
        executor.runDue()
        assertEquals(listOf("midi"), gatt.ops)
    }

    @Test
    fun `a line goes out with its newline, at most MTU - 3 bytes a write, one write at a time`() {
        val gatt = connectAndSubscribe(mtu = 23)
        val longest = "ledbright 40 ".repeat(7).take(ConsoleChannel.MAX_LINE)
        link.console!!.sendLine(longest)
        executor.runDue()
        assertEquals(1, gatt.consoleWrites.size)
        assertEquals(20, gatt.consoleWrites[0].size)
        executor.advance(50)
        assertEquals("the next piece waits for the write callback", 1, gatt.consoleWrites.size)
        drain(gatt)
        assertEquals(listOf(20, 20, 20, 20), gatt.consoleWrites.map { it.size })
        assertEquals(listOf(longest), gatt.consoleLinesWritten)
    }

    @Test
    fun `a line too long, or more than one line, is not sent`() {
        val gatt = connectAndSubscribe()
        link.console!!.sendLine("x".repeat(ConsoleChannel.MAX_LINE + 1))
        link.console!!.sendLine("ledbright 40\nfire 0 0 4095")
        executor.runDue()
        assertTrue(gatt.consoleWrites.isEmpty())
        assertEquals(2, logged.count { it.startsWith("Console line not sent") })
    }

    @Test
    fun `MIDI goes first, and a console line waits until no MIDI is queued`() {
        val gatt = connectAndSubscribe()
        link.console!!.sendLine("dump")
        link.send(notes(30), dropPending = false)
        executor.runDue()
        assertEquals(listOf("subscribe", "midi"), gatt.ops)
        gatt.writeDone()
        executor.runDue()
        assertEquals("ten notes still wait for the pace, so the line waits too", listOf("subscribe", "midi"), gatt.ops)
        drain(gatt)
        val midi = gatt.ops.count { it == "midi" }
        assertEquals("every MIDI write before the console's", listOf("subscribe") + List(midi) { "midi" } + "console", gatt.ops)
        assertEquals(30, gatt.writes.sumOf { (it.size - 1) / 4 })
    }

    @Test
    fun `a console write in flight holds up newly due MIDI by that one write only`() {
        val gatt = connectAndSubscribe(mtu = 23)
        link.console!!.sendLine("status is a line long enough for three writes")
        executor.runDue()
        assertEquals(listOf("subscribe", "console"), gatt.ops)
        link.send(notes(2), dropPending = false)
        executor.runDue()
        assertEquals("one write in flight, always", listOf("subscribe", "console"), gatt.ops)
        gatt.writeDone()
        executor.runDue()
        assertEquals("the MIDI goes ahead of the line's next piece", listOf("subscribe", "console", "midi"), gatt.ops)
        drain(gatt)
        assertEquals(listOf("subscribe", "console", "midi", "console", "console"), gatt.ops)
    }

    @Test
    fun `flush waits for MIDI only, not for console lines`() {
        val gatt = connectAndSubscribe()
        link.console!!.sendLine("status")
        executor.runDue()
        assertEquals(listOf("subscribe", "console"), gatt.ops)
        assertTrue("no MIDI queued or in flight", link.flush(0))
    }

    @Test
    fun `replies come back as lines, split notifications joined, the echo and carriage returns dropped`() = runTest {
        val gatt = connectAndSubscribe()
        val lines = collectLines()
        gatt.notifyChunk = 3   // splits the em dash's three bytes too
        gatt.notify("> ledbright 300\r\n  ledbright out of range (0..255)\r\nledbright=160\n  unknown command — type 'help'\n".toByteArray())
        executor.runDue()
        runCurrent()
        assertEquals(listOf("  ledbright out of range (0..255)", "ledbright=160", "  unknown command — type 'help'"), lines)
    }

    @Test
    fun `a scripted piano answers over the fake radio`() = runTest {
        val gatt = connectAndSubscribe(mtu = 23)
        val lines = collectLines()
        gatt.consoleScript = EmulatedConsole()::handle
        link.console!!.sendLine("ledbright 40")
        link.console!!.sendLine("get ledbright")
        link.console!!.sendLine("dump")
        drain(gatt)
        runCurrent()
        assertEquals(listOf("  ledbright = 40", "ledbright=40", "!proto=1"), lines.take(3))
        assertEquals("end", lines.last())
        assertTrue("ledbright=40" in lines.drop(2))
    }

    @Test
    fun `a drop forgets the console and whatever was queued for it`() {
        val gatt = connectAndSubscribe()
        link.send(notes(25), dropPending = false)
        link.console!!.sendLine("save")
        executor.runDue()
        gatt.dropped()
        executor.runDue()
        assertNull(link.console)
        val background = radio.connections.last()
        background.hasConsole = true
        background.connected()
        background.mtu(255)
        background.discovered()
        executor.runDue()
        assertNotNull(link.console)
        background.subscribeDone()
        drain(background)
        assertEquals(listOf("subscribe"), background.ops)
    }
}

// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** A keyboard's bytes (v1.11 — M29): what the parser makes of them, and what the holds make of that. */
class MidiStreamParserTest {
    /** What the parser said, in words: "on 0 60 100", "off 0 60", "cc 0 64 127", "all 0", "reset 0", "sensing", "system reset". */
    private class Heard : MidiStreamParser.Events {
        val said = mutableListOf<String>()

        override fun noteOn(channel: Int, key: Int, velocity: Int) {
            said += "on $channel $key $velocity"
        }

        override fun noteOff(channel: Int, key: Int) {
            said += "off $channel $key"
        }

        override fun control(channel: Int, controller: Int, value: Int) {
            said += "cc $channel $controller $value"
        }

        override fun allNotesOff(channel: Int) {
            said += "all $channel"
        }

        override fun resetControllers(channel: Int) {
            said += "reset $channel"
        }

        override fun activeSensing() {
            said += "sensing"
        }

        override fun systemReset() {
            said += "system reset"
        }
    }

    private val heard = Heard()
    private val parser = MidiStreamParser(heard)

    private fun feed(hex: String) {
        val bytes = hex.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
        parser.feed(bytes, 0, bytes.size)
    }

    @Test
    fun `running status lasts across buffers, and a Note On at velocity 0 is a Note Off`() {
        feed("90 3C")
        assertTrue("half a message waits", heard.said.isEmpty())
        feed("64 3E 50")
        feed("40 00")
        feed("3C")
        feed("00")
        assertEquals(listOf("on 0 60 100", "on 0 62 80", "off 0 64", "off 0 60"), heard.said)
        assertEquals(0L, parser.malformed)
    }

    @Test
    fun `Note Off, channels and the pedals with their values`() {
        feed("83 3C 40 B0 40 7F B5 42 01 B0 43 3F B0 40 00")
        assertEquals(listOf("off 3 60", "cc 0 64 127", "cc 5 66 1", "cc 0 67 63", "cc 0 64 0"), heard.said)
    }

    @Test
    fun `real-time bytes inside a message never disturb it`() {
        feed("90 F8 3C FE 64 FA FB FC")
        feed("F8 3E F9 50")
        assertEquals(listOf("sensing", "on 0 60 100", "on 0 62 80"), heard.said)
        assertEquals(0L, parser.malformed)
    }

    @Test
    fun `SysEx is dropped whole and clears running status`() {
        feed("90 3C 64 F0 7E 7F 09 01 F7 3E 50")
        assertEquals(listOf("on 0 60 100"), heard.said)
        assertEquals("the data after SysEx has no status", 2L, parser.malformed)
        feed("F0 43 10 4C 90 3C 64")   // a status ends SysEx even without F7
        assertEquals(listOf("on 0 60 100", "on 0 60 100"), heard.said)
    }

    @Test
    fun `system common messages clear running status and drop their own data`() {
        feed("90 3C 64 F2 01 02 F1 05 F3 07 F6")
        assertEquals(0L, parser.malformed)
        feed("3E 50")
        assertEquals(listOf("on 0 60 100"), heard.said)
        assertEquals(2L, parser.malformed)
    }

    @Test
    fun `all notes off for CC120 and CC123 to 127, CC121 resets the pedals, other controllers and messages are dropped`() {
        feed("B2 78 00 B2 7B 00 B2 7C 00 B2 7F 00 B2 79 00 B0 07 64 B0 0A 40 B0 7A 00")
        assertEquals(listOf("all 2", "all 2", "all 2", "all 2", "reset 2"), heard.said)
        heard.said.clear()
        feed("C0 05 06 D0 40 E0 00 40 A0 3C 20")
        assertEquals("program change, pressure, bend: dropped, and none is malformed", emptyList<String>(), heard.said)
        assertEquals(0L, parser.malformed)
        feed("90 3C 64")
        assertEquals(listOf("on 0 60 100"), heard.said)
    }

    @Test
    fun `a data byte with no status, or a message cut short, is malformed`() {
        feed("3C 64 90 3C 90 3E 50")
        assertEquals(listOf("on 0 62 80"), heard.said)
        assertEquals(3L, parser.malformed)
    }

    @Test
    fun `System Reset says so and forgets running status`() {
        feed("90 3C 64 FF 3E 50")
        assertEquals(listOf("on 0 60 100", "system reset"), heard.said)
        assertEquals(2L, parser.malformed)
    }

    @Test
    fun `offsets and counts past the buffer are held to it`() {
        val bytes = byteArrayOf(0x90.toByte(), 0x3C, 0x64)
        parser.feed(bytes, 1, 99)
        parser.feed(bytes, -5, 2)
        parser.feed(bytes, 7, 3)
        parser.feed(bytes, 0, -1)
        assertTrue(heard.said.isEmpty())
        parser.feed(bytes, 0, 3)
        assertEquals(listOf("on 0 60 100"), heard.said)
    }

    @Test
    fun `random bytes never throw, and every event's values stay in range`() {
        val random = Random(29)
        val holds = KeyboardHolds(KeyEvents())
        val checked = object : MidiStreamParser.Events {
            override fun noteOn(channel: Int, key: Int, velocity: Int) {
                assertTrue(channel in 0..15 && key in 0..127 && velocity in 1..127)
                holds.noteOn(channel, key, velocity)
            }

            override fun noteOff(channel: Int, key: Int) {
                assertTrue(channel in 0..15 && key in 0..127)
                holds.noteOff(channel, key)
            }

            override fun control(channel: Int, controller: Int, value: Int) {
                assertTrue(channel in 0..15 && controller in listOf(64, 66, 67) && value in 0..127)
                holds.control(channel, controller, value)
            }

            override fun allNotesOff(channel: Int) = holds.allNotesOff(channel)

            override fun resetControllers(channel: Int) = holds.resetControllers(channel)

            override fun systemReset() = holds.systemReset()
        }
        val fuzzed = MidiStreamParser(checked)
        repeat(200) {
            val bytes = ByteArray(random.nextInt(0, 400)) { random.nextInt(256).toByte() }
            fuzzed.feed(bytes, random.nextInt(-3, 5), random.nextInt(-3, bytes.size + 5))
        }
        holds.releaseAll()
        assertFalse(holds.anyDown)
        assertEquals(0, holds.held)
    }

    // ---- KeyboardHolds ----------------------------------------------------------------------

    private val events = KeyEvents()
    private val holds = KeyboardHolds(events)

    private fun said(): List<String> = (0 until events.size).map { i ->
        when (events.type(i)) {
            KeyEvents.DOWN -> "down ${events.key(i)} ${events.value(i)}"
            KeyEvents.UP -> "up ${events.key(i)}"
            else -> "pedal ${events.key(i)} ${events.value(i)}"
        }
    }

    @Test
    fun `a key two channels hold goes down once and up when the last lets go`() {
        holds.noteOn(0, 60, 90)
        holds.noteOn(1, 60, 70)   // a keyboard in layer mode: one press, two channels
        holds.noteOff(0, 60)
        assertTrue(holds.isDown(60))
        holds.noteOff(1, 60)
        holds.noteOff(1, 60)   // a second release changes nothing
        assertEquals(listOf("down 60 90", "up 60"), said())
        assertEquals(0, holds.held)
    }

    @Test
    fun `a key struck again on its own channel is a press again, a lost release`() {
        holds.noteOn(0, 60, 90)
        holds.noteOn(0, 60, 40)
        holds.noteOff(0, 60)
        assertEquals(listOf("down 60 90", "down 60 40", "up 60"), said())
    }

    @Test
    fun `each pedal is the highest value across the channels, told when it changes`() {
        holds.control(0, 64, 100)
        holds.control(1, 64, 127)
        holds.control(0, 64, 0)
        holds.control(1, 64, 20)
        holds.control(1, 64, 20)
        holds.control(0, 67, 50)
        assertEquals(listOf("pedal 64 100", "pedal 64 127", "pedal 64 20", "pedal 67 50"), said())
        assertEquals(20, holds.pedal(64))
        assertEquals(0, holds.pedal(66))
        assertEquals(0, holds.pedal(7))
    }

    @Test
    fun `All Notes Off lets go of its channel's keys, CC121 of its pedals, and letting go of everything of all`() {
        holds.noteOn(0, 60, 90)
        holds.noteOn(1, 62, 90)
        holds.noteOn(0, 64, 90)
        holds.noteOn(1, 64, 90)
        holds.control(1, 64, 127)
        events.clear()
        holds.allNotesOff(0)
        assertEquals("64 is still held on channel 2", listOf("up 60"), said())
        events.clear()
        holds.resetControllers(1)
        assertEquals(listOf("pedal 64 0"), said())
        holds.control(0, 66, 127)
        events.clear()
        holds.releaseAll()
        assertEquals(listOf("up 62", "up 64", "pedal 66 0"), said())
        assertFalse(holds.anyDown)
    }

    @Test
    fun `the events buffer grows past its first size and counts its Note Ons`() {
        for (key in 0..127) holds.noteOn(0, key, 64)
        assertEquals(128, events.size)
        assertEquals(128, events.downs())
        events.clear()
        assertTrue(events.isEmpty())
    }
}

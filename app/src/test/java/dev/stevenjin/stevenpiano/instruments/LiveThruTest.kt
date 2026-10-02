// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.instruments

import dev.stevenjin.stevenpiano.midi.KeyEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gate between the keyboard and the instrument (v1.11 — M29): when it opens, what goes through, the breaker. */
class LiveThruTest {
    /** What reached the player, in words: "down 60 100", "up 60", "pedal 64 127", "silence". */
    private val played = mutableListOf<String>()
    private val player = object : LivePlayer {
        override fun external(events: KeyEvents, arrivalNanos: Long) {
            for (i in 0 until events.size) {
                played += when (events.type(i)) {
                    KeyEvents.DOWN -> "down ${events.key(i)} ${events.value(i)}"
                    KeyEvents.UP -> "up ${events.key(i)}"
                    else -> "pedal ${events.key(i)} ${events.value(i)}"
                }
            }
        }

        override fun silenceExternal() {
            played += "silence"
        }
    }
    private val trips = mutableListOf<LiveTrip>()
    private val logged = mutableListOf<String>()
    private var nanos = 1_000_000_000L
    private val live = LiveThru(player, log = { logged += it }, nanoTime = { nanos }, onTrip = { trips += it })

    private fun keys(vararg events: Triple<Int, Int, Int>, at: Long = nanos) {
        val buffer = KeyEvents()
        for ((type, key, value) in events) buffer.add(type, key, value)
        live.onKeys(buffer, at, at)
    }

    private fun down(key: Int, velocity: Int = 100) = Triple(KeyEvents.DOWN, key, velocity)

    private fun up(key: Int) = Triple(KeyEvents.UP, key, 0)

    private fun pedal(value: Int, controller: Int = 64) = Triple(KeyEvents.PEDAL, controller, value)

    private fun openAll() {
        live.setKeyboard(true)
        live.setTarget(true)
        live.setOnScreen(true)
        live.setWanted(true)
    }

    @Test
    fun `the gate opens only with the switch on, the Keys tab on screen, the keyboard and something to play on`() {
        live.setWanted(true)
        live.setOnScreen(true)
        live.setKeyboard(true)
        keys(down(60))
        assertFalse(live.state.value.open)
        live.setTarget(true)
        assertTrue(live.state.value.open)
        keys(down(62))
        assertEquals(listOf("down 62 100"), played)
        for (close in listOf<() -> Unit>({ live.setOnScreen(false) }, { live.setTarget(false) }, { live.setKeyboard(false) }, { live.setWanted(false) })) {
            openAll()
            played.clear()
            close()
            assertFalse(live.state.value.open)
            assertEquals("whenever it closes, the keyboard's keys let go", listOf("silence"), played)
        }
    }

    @Test
    fun `a keyboard that is the instrument too never plays through, with its reason`() {
        openAll()
        live.setLooped(true)
        assertFalse(live.state.value.open)
        assertTrue(live.state.value.looped)
        assertTrue(live.state.value.wanted)
        keys(down(60))
        assertEquals(listOf("silence"), played)
    }

    @Test
    fun `only fresh presses go through, and a Note Off only for a Note On that went`() {
        live.setKeyboard(true)
        live.setTarget(true)
        live.setWanted(true)
        keys(down(60), pedal(127))   // held while the Keys tab was away
        live.setOnScreen(true)
        keys(up(60), down(64), pedal(100))
        assertEquals("the key and the pedal down before are not played", listOf("down 64 100"), played)
        keys(pedal(0))
        keys(pedal(127), down(60), up(64), up(60))
        assertEquals(listOf("down 64 100", "pedal 64 127", "down 60 100", "up 64", "up 60"), played)
    }

    @Test
    fun `more than 200 Note Ons in a second trip the breaker, which only the person re-arms`() {
        openAll()
        repeat(200) { i ->
            nanos += 4_000_000L   // 200 in 0.8 s
            keys(down(30 + i % 60), up(30 + i % 60))
        }
        assertTrue(live.state.value.open)
        nanos += 4_000_000L
        keys(down(70))
        assertFalse(live.state.value.open)
        assertEquals(LiveTrip.TooManyNotes, live.state.value.tripped)
        assertFalse("Live is off", live.state.value.wanted)
        assertEquals(listOf(LiveTrip.TooManyNotes), trips)
        assertEquals("silence", played.last())
        assertTrue(logged.last().startsWith("Live: off (the flood breaker: more than 200 notes in a second"))
        live.setOnScreen(false)
        live.setOnScreen(true)
        assertFalse("not by itself", live.state.value.open)
        live.setWanted(true)
        assertTrue(live.state.value.open)
        assertNull(live.state.value.tripped)
    }

    @Test
    fun `200 Note Ons spread over more than a second never trip it`() {
        openAll()
        repeat(600) { i ->
            nanos += 6_000_000L   // 166 a second
            keys(down(40 + i % 40), up(40 + i % 40))
        }
        assertTrue(live.state.value.open)
        assertTrue(trips.isEmpty())
    }

    @Test
    fun `32 keys held at once trip the breaker`() {
        openAll()
        for (key in 40 until 71) keys(down(key))
        assertTrue(live.state.value.open)
        keys(down(71))
        assertEquals(LiveTrip.TooManyKeys, live.state.value.tripped)
        assertEquals("silence", played.last())
    }

    @Test
    fun `a burst of malformed bytes trips the breaker`() {
        openAll()
        live.onMalformed(40)
        assertTrue(live.state.value.open)
        live.onMalformed(30)
        assertEquals(LiveTrip.Garbled, live.state.value.tripped)
        assertFalse(live.state.value.open)
    }

    @Test
    fun `the keyboard letting go lets go through the player too, and its keys' releases after that go nowhere`() {
        openAll()
        keys(down(60), pedal(127))
        live.onLetGo("the keyboard went away")
        assertEquals(listOf("down 60 100", "pedal 64 127", "silence"), played)
        keys(up(60))
        assertEquals("nothing was let through any more", "silence", played.last())
    }
}

// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.player

import dev.stevenjin.stevenpiano.ble.FakePianoLink
import dev.stevenjin.stevenpiano.midi.InstrumentProfile
import dev.stevenjin.stevenpiano.midi.KeyEvents
import dev.stevenjin.stevenpiano.midi.MidiBatch
import dev.stevenjin.stevenpiano.midi.MidiPiece
import dev.stevenjin.stevenpiano.midi.SmfBuilder
import dev.stevenjin.stevenpiano.midi.SmfParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keys from the Keys screen, through the engine's own router, on a virtual clock. */
class LiveInputTest {
    private val ms = 1_000_000L
    private var now = 0L
    private val link = FakePianoLink { now }
    private val engine = PlaybackEngine(link)
    private val router get() = engine.router

    /** One track, 1 tick = 1 ms. */
    private fun piece(block: SmfBuilder.Track.() -> Unit): MidiPiece =
        SmfParser.parse(SmfBuilder(format = 0, division = 1000).track { tempo(0, 1_000_000); block() }.build())

    private fun at(timeMs: Long) {
        now = timeMs * ms
    }

    /** Drives the engine like the scheduler does, up to [untilMs]. */
    private fun runUntil(untilMs: Long) {
        while (true) {
            val wake = engine.advance(now)
            if (wake > untilMs * ms) break
            now = wake
        }
        now = untilMs * ms
    }

    /** "time-in-ms message". */
    private fun sent() = link.sent.map { "${it.atNanos / ms} ${it.message}" }

    @Test
    fun `a key goes down and up the moment it is played`() {
        engine.liveNoteOn(60, 100, now)
        at(300)
        engine.liveNoteOff(60)
        assertEquals(listOf("0 90 3C 64", "300 80 3C 00"), sent())
        assertEquals(listOf(false, false), link.sent.map { it.dropPending })
        assertEquals("through the live lane (v1.11 — M29)", listOf(true, true), link.sent.map { it.live })
    }

    @Test
    fun `with nothing playing, a pedal change the router held back still goes when its turn comes`() {
        val out = MidiBatch()
        for (value in listOf(127, 0, 127, 0)) router.route(0xB0, 64, value, now / 1000, out)
        link.send(out, dropPending = false)
        assertEquals("three back to back, the fourth waits", listOf("0 B0 40 7F", "0 B0 40 00", "0 B0 40 7F"), sent())
        assertEquals(PlaybackStatus.Stopped, engine.status)
        assertEquals("the engine says when to look again", 50 * ms, engine.advance(now))
        at(49)
        assertEquals(50 * ms, engine.advance(now))
        assertEquals(3, link.messages.size)
        at(50)
        assertEquals("nothing more to wait for", Long.MAX_VALUE, engine.advance(now))
        assertEquals("50 B0 40 00", sent().last())
        assertEquals(true, link.sent.last().live)
        assertEquals(Long.MAX_VALUE, engine.advance(now))
        assertEquals(4, link.messages.size)
    }

    @Test
    fun `a new connection with nothing playing sends the stop sequence and keeps the piece where it was`() {
        engine.load(piece { noteOn(0, 64); noteOff(5_000, 64) }, now)
        engine.play(now)
        runUntil(200)
        engine.pause(now)
        link.clear()
        engine.connectedAnew(now)
        assertEquals(listOf("B0 40 00", "B0 7B 00"), link.messages)
        assertEquals(listOf(true, true), link.sent.map { it.dropPending })
        assertEquals(PlaybackStatus.Paused, engine.status)
        assertEquals(200_000L, engine.positionMicros(now))
    }

    @Test
    fun `the velocity percentage applies, transpose and fold do not`() {
        router.velocityPct = 50
        router.transpose = 7
        router.fold = false
        engine.liveNoteOn(60, 100, now)
        engine.liveNoteOn(107, 127, now)
        assertEquals(listOf("90 3C 32", "90 6B 40"), link.messages)
    }

    @Test
    fun `keys the piano does not have are ignored`() {
        engine.liveNoteOn(23, 100, now)
        engine.liveNoteOn(108, 100, now)
        engine.liveNoteOff(23)
        assertEquals(emptyList<String>(), link.messages)
    }

    @Test
    fun `a key the screen already holds is not struck again`() {
        engine.liveNoteOn(60, 100, now)
        at(200)
        engine.liveNoteOn(60, 90, now)
        engine.liveNoteOff(60)
        engine.liveNoteOff(60)
        assertEquals(listOf("0 90 3C 64", "200 80 3C 00"), sent())
    }

    @Test
    fun `sustain latches the pedal down, then up`() {
        engine.liveSustain(true)
        assertTrue(router.liveSustainDown)
        engine.liveSustain(true)
        engine.liveSustain(false)
        assertFalse(router.liveSustainDown)
        assertEquals(listOf("B0 40 7F", "B0 40 00"), link.messages)
    }

    @Test
    fun `silenceLive lets go of the screen's keys and pedal while the piece's keys stay down`() {
        engine.load(piece { noteOn(0, 64); noteOff(5_000, 64) }, now)
        engine.play(now)
        runUntil(200)
        engine.liveNoteOn(60, 100, now)
        engine.liveNoteOn(67, 90, now)
        engine.liveSustain(true)
        link.clear()
        engine.silenceLive()
        assertEquals(listOf("200 80 3C 00", "200 80 43 00", "200 B0 40 00"), sent())
        assertTrue(router.isSounding(64))
        assertFalse(router.isSounding(60))
        assertFalse(router.liveSustainDown)
        assertEquals((1L shl 40), router.activeLow)
        link.clear()
        runUntil(10_000)   // the piece plays on to its own release and end
        assertEquals(listOf("5000 80 40 00", "5000 B0 40 00", "5000 B0 7B 00"), sent())
    }

    @Test
    fun `silenceLive with nothing held sends nothing`() {
        engine.silenceLive()
        assertEquals(emptyList<String>(), link.messages)
    }

    @Test
    fun `a live key on a key the piece holds shares it`() {
        engine.load(piece { noteOn(0, 64); noteOff(1_000, 64) }, now)
        engine.play(now)
        runUntil(200)
        link.clear()
        engine.liveNoteOn(64, 100, now)   // already down: no re-strike
        at(300)
        engine.liveNoteOff(64)            // the piece still holds it
        assertEquals(emptyList<String>(), sent())
        assertTrue(router.isSounding(64))
        runUntil(1_000)
        assertEquals("1000 80 40 00", sent().first())
    }

    @Test
    fun `the piece letting go of a shared key leaves it down for the finger`() {
        engine.load(piece { noteOn(0, 64); noteOff(500, 64); noteOn(2_000, 72); noteOff(2_100, 72) }, now)
        engine.play(now)
        runUntil(100)
        engine.liveNoteOn(64, 100, now)
        link.clear()
        runUntil(600)
        assertEquals(emptyList<String>(), sent())
        assertTrue(router.isSounding(64))
        engine.liveNoteOff(64)
        assertEquals(listOf("600 80 40 00"), sent())
    }

    @Test
    fun `the 100 ms guard thins fast taps of one key`() {
        engine.liveNoteOn(60, 100, now)
        at(40)
        engine.liveNoteOff(60)
        at(80)
        engine.liveNoteOn(60, 100, now)   // 80 ms after the last strike: thinned
        at(90)
        engine.liveNoteOff(60)            // its release goes nowhere
        at(150)
        engine.liveNoteOn(60, 100, now)
        assertEquals(listOf("0 90 3C 64", "40 80 3C 00", "150 90 3C 64"), sent())
    }

    @Test
    fun `the guard counts the piece's strikes too`() {
        engine.load(piece { noteOn(0, 62); noteOff(50, 62) }, now)
        engine.play(now)
        runUntil(60)
        link.clear()
        engine.liveNoteOn(62, 100, now)
        at(120)
        engine.liveNoteOn(62, 100, now)
        assertEquals(listOf("120 90 3E 64"), sent())
    }

    @Test
    fun `a full stop lets go of the live keys and the sustain too`() {
        engine.liveNoteOn(60, 100, now)
        engine.liveSustain(true)
        link.clear()
        engine.stop(now)
        assertEquals(listOf("B0 40 00", "B0 7B 00"), link.messages)
        assertFalse(router.liveSustainDown)
        link.clear()
        engine.liveNoteOff(60)
        engine.silenceLive()
        assertEquals(emptyList<String>(), link.messages)
    }

    @Test
    fun `live keys light the keyboard strip`() {
        engine.liveNoteOn(60, 100, now)
        engine.liveNoteOn(107, 100, now)
        assertEquals(1L shl 36, router.activeLow)
        assertEquals(1L shl 19, router.activeHigh)
        engine.liveNoteOff(60)
        assertEquals(0L, router.activeLow)
    }

    // ---- A MIDI keyboard through the engine (v1.11 — M29) -------------------------------------------

    private fun keyboard(vararg events: Triple<Int, Int, Int>): IntArray {
        val buffer = KeyEvents()
        for ((type, key, value) in events) buffer.add(type, key, value)
        return buffer.toPacked()
    }

    @Test
    fun `a keyboard's buffer goes out as one batch through the live lane, its keys as played`() {
        router.transpose = 3
        val chord = keyboard(Triple(KeyEvents.DOWN, 60, 100), Triple(KeyEvents.DOWN, 64, 90), Triple(KeyEvents.PEDAL, 64, 127))
        engine.external(chord, chord.size, now, now)
        assertEquals(listOf("90 3C 64", "90 40 5A", "B0 40 7F"), link.messages)
        assertTrue(link.sent.all { it.live && !it.dropPending })
        assertEquals("one batch: one moment", 1, link.sent.map { it.atNanos }.distinct().size)
        link.clear()
        at(300)
        val release = keyboard(Triple(KeyEvents.UP, 60, 0), Triple(KeyEvents.UP, 64, 0), Triple(KeyEvents.PEDAL, 64, 0))
        engine.external(release, release.size, now, now)
        assertEquals(listOf("300 80 3C 00", "300 80 40 00", "300 B0 40 00"), sent())
    }

    @Test
    fun `letting go of the keyboard leaves the piece's keys and the screen's`() {
        engine.load(piece { noteOn(0, 64); noteOff(5_000, 64) }, now)
        engine.play(now)
        runUntil(200)
        engine.liveNoteOn(67, 100, now)
        val keys = keyboard(Triple(KeyEvents.DOWN, 60, 100), Triple(KeyEvents.DOWN, 64, 100), Triple(KeyEvents.DOWN, 67, 100))
        engine.external(keys, keys.size, now, now)
        link.clear()
        engine.silenceExternal()
        assertEquals(listOf("200 80 3C 00"), sent())
        assertTrue(router.isSounding(64))
        assertTrue(router.isSounding(67))
        assertEquals(PlaybackStatus.Playing, engine.status)
    }

    @Test
    fun `a Live session's times are kept for the trail when it ends`() {
        val keys = keyboard(Triple(KeyEvents.DOWN, 60, 100), Triple(KeyEvents.DOWN, 62, 100))
        engine.external(keys, keys.size, arrivalNanos = 0L, nowNanos = 3 * ms)
        val more = keyboard(Triple(KeyEvents.DOWN, 64, 100))
        engine.external(more, more.size, arrivalNanos = 10 * ms, nowNanos = 19 * ms)
        assertEquals(null, engine.liveTiming.take())
        engine.silenceExternal()
        assertEquals(LiveTiming.Run(notes = 3, medianMs = 3, worstMs = 9), engine.liveTiming.take())
        assertEquals("once", null, engine.liveTiming.take())
        assertEquals(
            "Live: 3 notes, 3 ms median and 9 ms at most from the keyboard to the piano's queue",
            Player.liveLine(LiveTiming.Run(3, 3, 9)),
        )
        assertEquals("Live: 1 note, 0 ms median and 0 ms at most from the keyboard to the piano's queue", Player.liveLine(LiveTiming.Run(1, 0, 0)))
    }

    @Test
    fun `another instrument silences the old one first, and a piece plays on with its pedal`() {
        engine.load(piece { cc(0, 64, 127); noteOn(0, 60); noteOff(5_000, 60) }, now)
        engine.play(now)
        runUntil(200)
        link.clear()
        engine.setProfile(InstrumentProfile.StandardPiano, now)
        assertEquals(listOf("B0 40 00", "B0 7B 00", "B0 40 7F"), link.messages)
        assertEquals(InstrumentProfile.StandardPiano, router.profile)
        link.clear()
        engine.stop(now)
        assertEquals("the new instrument's stop sequence", listOf("B0 40 00", "B0 42 00", "B0 43 00", "B0 7B 00"), link.messages)
    }
}

// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.record

import dev.stevenjin.stevenpiano.midi.KeyEvents
import dev.stevenjin.stevenpiano.midi.SmfWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recorder (v1.11 — M29): what a take holds, from where, and when it ends by itself. */
class RecorderTest {
    private val ms = 1_000_000L
    private var now = 1_000 * ms
    private val recorder = Recorder(nanoTime = { now })

    private fun at(timeMs: Long) {
        now = timeMs * ms
    }

    private fun keyboard(arrivalMs: Long, vararg events: Triple<Int, Int, Int>, stampMs: Long = arrivalMs) {
        val buffer = KeyEvents()
        for ((type, key, value) in events) buffer.add(type, key, value)
        recorder.onKeys(buffer, arrivalMs * ms, stampMs * ms)
    }

    private fun down(key: Int, velocity: Int) = Triple(KeyEvents.DOWN, key, velocity)

    private fun up(key: Int) = Triple(KeyEvents.UP, key, 0)

    private fun pedal(value: Int, controller: Int = 64) = Triple(KeyEvents.PEDAL, controller, value)

    private fun Take.described() = notes.map { "${it.onMicros / 1000}-${it.offMicros / 1000} ${it.key} ${it.velocity}" }

    @Test
    fun `a take holds the screen and the keyboard from its first event at 0, in time order, keys as played`() {
        assertTrue(recorder.start())
        at(2_000)
        recorder.screenKey(true, 60, 90)
        keyboard(2_500, down(21, 40), stampMs = 2_450)
        keyboard(2_600, down(108, 127))
        at(3_000)
        recorder.screenKey(false, 60, 0)
        keyboard(3_200, up(21), up(108))
        at(9_000)
        val take = recorder.stop(TakeEnd.Stopped)!!
        assertEquals(listOf("0-1000 60 90", "450-1200 21 40", "600-1200 108 127"), take.described())
        assertEquals("from the first event to the last", 1_200_000L, take.durationMicros)
        assertEquals(TakeEnd.Stopped, take.ended)
        assertFalse(recorder.recording)
    }

    @Test
    fun `the keyboard's own stamp counts within 250 ms before its arrival and never going backwards`() {
        recorder.start()
        keyboard(1_000, down(60, 80), stampMs = 990)    // its stamp: 990
        keyboard(1_400, up(60), stampMs = 1_100)        // 300 ms old: the arrival, 1,400
        keyboard(1_500, down(62, 80), stampMs = 1_300)  // before the last time taken: the arrival, 1,500
        keyboard(1_600, up(62), stampMs = 1_700)        // after its arrival: the arrival
        val take = recorder.stop(TakeEnd.Stopped)!!
        assertEquals(listOf("0-410 60 80", "510-610 62 80"), take.described())
    }

    @Test
    fun `a key held at Stop ends there, every pedal value is kept, and the pedals are lifted at the end`() {
        recorder.start()
        keyboard(1_000, pedal(40), down(60, 80))
        keyboard(1_100, pedal(100), pedal(127, 66), pedal(64, 67))
        at(1_200)
        recorder.screenSustain(true)
        at(1_500)
        val take = recorder.stop(TakeEnd.Stopped)!!
        assertEquals(listOf("0-500 60 80"), take.described())
        assertEquals(
            listOf(
                SmfWriter.Control(0, 64, 40),
                SmfWriter.Control(100_000, 64, 100),
                SmfWriter.Control(100_000, 66, 127),
                SmfWriter.Control(100_000, 67, 64),
                SmfWriter.Control(200_000, 64, 127),
                SmfWriter.Control(500_000, 64, 0),
                SmfWriter.Control(500_000, 66, 0),
                SmfWriter.Control(500_000, 67, 0),
            ),
            take.controls,
        )
        assertEquals(500_000L, take.durationMicros)
    }

    @Test
    fun `a key struck again while held ends its first note there`() {
        recorder.start()
        keyboard(1_000, down(60, 80))
        keyboard(1_300, down(60, 50))
        keyboard(1_400, up(60))
        assertEquals(listOf("0-300 60 80", "300-400 60 50"), recorder.stop(TakeEnd.Stopped)!!.described())
    }

    @Test
    fun `a take with no note is none, and nothing is kept outside a take`() {
        keyboard(1_000, down(60, 80))
        recorder.screenKey(true, 62, 80)
        assertNull("no take running", recorder.stop(TakeEnd.Stopped))
        recorder.start()
        keyboard(1_100, pedal(127), pedal(0))
        recorder.screenSustain(true)
        assertNull(recorder.stop(TakeEnd.Stopped))
        assertTrue(recorder.start())
        assertFalse("one take at a time", recorder.start())
        assertEquals(0, recorder.events)
    }

    @Test
    fun `a take ends by itself at an hour, at its most events, or after five minutes of silence`() {
        val small = Recorder(nanoTime = { now }, maxEvents = 4, maxNanos = 60_000 * ms, idleNanos = 5_000 * ms)
        small.start()
        at(5_999)
        assertNull(small.due())
        at(6_000)
        assertEquals(TakeEnd.Silence, small.due())
        small.screenKey(true, 60, 80)
        assertNull("something was played", small.due())
        small.screenKey(false, 60, 0)
        small.screenKey(true, 60, 80)
        small.screenKey(false, 60, 0)
        small.screenKey(true, 62, 80)   // past the cap: not kept
        assertEquals(TakeEnd.Fullest, small.due())
        assertEquals(4, small.events)
        assertEquals(listOf("0-0 60 80", "0-0 60 80"), small.stop(TakeEnd.Fullest)!!.described())
        small.start()
        at(66_000)
        small.screenKey(true, 60, 80)
        at(66_001)
        assertEquals(TakeEnd.Longest, small.due())
        assertEquals(60_001 * ms, small.elapsedNanos())
    }

    @Test
    fun `the caps are an hour, 200,000 events and five minutes`() {
        assertEquals(3_600_000_000_000L, Recorder.MAX_NANOS)
        assertEquals(200_000, Recorder.MAX_EVENTS)
        assertEquals(300_000_000_000L, Recorder.IDLE_NANOS)
    }
}

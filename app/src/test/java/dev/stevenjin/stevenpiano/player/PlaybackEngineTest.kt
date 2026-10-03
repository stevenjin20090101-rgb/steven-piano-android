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
import dev.stevenjin.stevenpiano.midi.MidiPiece
import dev.stevenjin.stevenpiano.midi.NoteRouter
import dev.stevenjin.stevenpiano.midi.SmfBuilder
import dev.stevenjin.stevenpiano.midi.SmfParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine on a virtual clock: every send time is exact. */
class PlaybackEngineTest {
    private val ms = 1_000_000L
    private var now = 0L
    private val link = FakePianoLink { now }
    private val engine = PlaybackEngine(link)

    /** One track, 1 tick = 1 ms (1000 ticks per quarter at 60 BPM). */
    private fun piece(block: SmfBuilder.Track.() -> Unit): MidiPiece =
        SmfParser.parse(SmfBuilder(format = 0, division = 1000).track { tempo(0, 1_000_000); block() }.build())

    /** Drives the engine like the scheduler does, jumping to each wake-up, and stops the clock at [untilMs]. */
    private fun runUntil(untilMs: Long) {
        while (true) {
            val wake = engine.advance(now)
            if (wake > untilMs * ms) break
            now = wake
        }
        now = untilMs * ms
    }

    private fun at(timeMs: Long) {
        now = timeMs * ms
    }

    /** "time-in-ms message", e.g. "500 90 3C 50". */
    private fun sent() = link.sent.map { "${it.atNanos / ms} ${it.message}" }

    @Test
    fun `events go out at their exact times, and the end silences and stops`() {
        engine.load(piece { noteOn(0, 60); noteOff(500, 60); noteOn(500, 62); noteOff(1000, 62) }, now)
        engine.play(now)
        assertEquals(500 * ms, engine.advance(now) - 0)   // nothing else is due before 500 ms
        runUntil(5_000)
        assertEquals(
            listOf("0 90 3C 50", "500 80 3C 00", "500 90 3E 50", "1000 80 3E 00", "1000 B0 40 00", "1000 B0 7B 00"),
            sent(),
        )
        assertEquals(PlaybackStatus.Stopped, engine.status)
        assertTrue(engine.ended)
        assertEquals(1_000_000L, engine.positionMicros(now))
    }

    @Test
    fun `each run's timing says how many events went out and how late the latest was, in real time`() {
        engine.load(piece { noteOn(0, 60); noteOff(500, 60); noteOn(500, 62); noteOff(1000, 62) }, now)
        engine.play(now)
        engine.advance(0)
        engine.advance(507 * ms)   // the two events at 500 ms, 7 ms late
        engine.setTempo(50, 507 * ms)
        engine.advance(1_496 * ms)   // at half speed the last event (1000 ms of music) was due at 1493 ms: 3 ms late
        assertEquals(PlaybackTiming.Run(events = 4, latestMicros = 7_000, latestAtMicros = 500_000), engine.timing.take())
        assertEquals("taken once", null, engine.timing.take())
        engine.setTempo(100, now)
        engine.play(now)
        engine.stop(now)
        assertEquals("a run that sent nothing has no figures", null, engine.timing.take())
        assertEquals("Timing: 4 events, the latest 7 ms after its time, at 0:00.5", Player.timingLine(PlaybackTiming.Run(4, 7_900, 500_000)))
        assertEquals("Timing: 3059 events, the latest 101 ms after its time, at 12:03.4", Player.timingLine(PlaybackTiming.Run(3059, 101_200, 723_456_789)))
    }

    @Test
    fun `nothing is sent before it is due`() {
        engine.load(piece { noteOn(500, 60); noteOff(700, 60) }, now)
        engine.play(now)
        assertEquals(500 * ms, engine.advance(0))
        assertEquals(500 * ms, engine.advance(500 * ms - 1))
        assertTrue(link.sent.isEmpty())
        assertEquals(700 * ms, engine.advance(500 * ms))
        assertEquals(listOf("90 3C 50"), link.messages)
    }

    @Test
    fun `tempo 50 percent doubles the spacing without a jump`() {
        engine.load(piece { noteOn(0, 60); noteOff(500, 60); noteOn(1000, 62); noteOff(1500, 62) }, now)
        engine.play(now)
        runUntil(250)
        assertEquals(250_000L, engine.positionMicros(now))
        engine.setTempo(50, now)
        assertEquals(250_000L, engine.positionMicros(now))
        runUntil(10_000)
        assertEquals(
            listOf("0 90 3C 50", "750 80 3C 00", "1750 90 3E 50", "2750 80 3E 00", "2750 B0 40 00", "2750 B0 7B 00"),
            sent(),
        )
    }

    @Test
    fun `seek silences first, restores the pedal, then resumes at the right event`() {
        engine.load(piece { noteOn(0, 60); cc(250, 64, 127); noteOff(500, 60); noteOn(1500, 64); noteOff(2000, 64) }, now)
        engine.play(now)
        runUntil(100)
        link.clear()
        engine.seek(1_000_000L, now)
        assertEquals(listOf(true, true, false), link.sent.map { it.dropPending })
        runUntil(10_000)
        assertEquals(
            listOf(
                "100 B0 40 00", "100 B0 7B 00", "100 B0 40 7F",
                "600 90 40 50", "1100 80 40 00", "1100 B0 40 00", "1100 B0 7B 00",
            ),
            sent(),
        )
    }

    @Test
    fun `pause silences, and play resumes where it stopped with the pedal back down`() {
        engine.load(piece { cc(0, 64, 127); noteOn(0, 60); noteOff(1000, 60); noteOn(1200, 62); noteOff(1300, 62) }, now)
        engine.play(now)
        runUntil(300)
        engine.pause(now)
        assertEquals(PlaybackStatus.Paused, engine.status)
        assertEquals(Long.MAX_VALUE, engine.advance(now))
        at(5_000)
        assertEquals(300_000L, engine.positionMicros(now))
        engine.play(now)
        runUntil(10_000)
        assertEquals(
            listOf(
                "0 B0 40 7F", "0 90 3C 50",
                "300 B0 40 00", "300 B0 7B 00",
                "5000 B0 40 7F",
                "5900 90 3E 50", "6000 80 3E 00", "6000 B0 40 00", "6000 B0 7B 00",
            ),
            sent(),
        )
    }

    @Test
    fun `a transpose change while a key is held releases the original key`() {
        engine.load(piece { noteOn(0, 60); noteOff(500, 60); noteOn(1000, 60); noteOff(1500, 60) }, now)
        engine.play(now)
        runUntil(100)
        engine.router.transpose = 5
        runUntil(10_000)
        assertEquals(
            listOf("0 90 3C 50", "500 80 3C 00", "1000 90 41 50", "1500 80 41 00", "1500 B0 40 00", "1500 B0 7B 00"),
            sent(),
        )
    }

    @Test
    fun `stop silences and rewinds, and play after the end starts over`() {
        engine.load(piece { noteOn(0, 60); noteOff(1000, 60) }, now)
        engine.play(now)
        runUntil(600)
        engine.stop(now)
        assertEquals(PlaybackStatus.Stopped, engine.status)
        assertEquals(0L, engine.positionMicros(now))
        assertEquals(listOf("0 90 3C 50", "600 B0 40 00", "600 B0 7B 00"), sent())
        link.clear()
        engine.play(now)
        runUntil(5_000)
        assertTrue(engine.ended)
        link.clear()
        engine.play(now)
        assertFalse(engine.ended)
        runUntil(5_100)
        assertEquals(listOf("5000 90 3C 50"), sent())
    }

    @Test
    fun `the re-strike guard is T and counts real time, so a fast tempo thins repeats (v1_16 M44)`() {
        engine.router.restrikeMicros = 110_000   // T 110 ms: a strike 100 ms after the last still goes, 60 ms after does not
        engine.load(
            piece {
                noteOn(0, 60); noteOff(50, 60)
                noteOn(120, 60); noteOff(170, 60)
                noteOn(240, 60); noteOff(290, 60)
                noteOn(360, 60); noteOff(410, 60)
            },
            now,
        )
        engine.setTempo(200, now)
        engine.play(now)
        runUntil(1_000)
        assertEquals(
            listOf("0 90 3C 50", "25 80 3C 00", "120 90 3C 50", "145 80 3C 00", "205 B0 40 00", "205 B0 7B 00"),
            sent(),
        )
    }

    @Test
    fun `a file whose pedal flaps every 10 ms moves the pedal at most 20 times a second, ending where the file does`() {
        engine.load(
            piece {
                noteOn(0, 60)
                for (t in 0L until 1_000L step 10) cc(t, 64, if ((t / 10) % 2 == 0L) 127 else 0)
                cc(1_000, 64, 90)
                noteOff(1_500, 60)
            },
            now,
        )
        engine.play(now)
        runUntil(1_400)
        val pedal = sent().filter { " B0 40 " in it }
        assertTrue("${pedal.size} pedal changes", pedal.size <= NoteRouter.PEDAL_BURST + 21)
        assertEquals("B0 40 5A", pedal.last().substringAfter(' '))   // the file's last value arrives
        val times = pedal.map { it.substringBefore(' ').toLong() }
        assertTrue(times.last() <= 1_050)   // at most 50 ms late
    }

    @Test
    fun `a wake time too far ahead saturates instead of wrapping negative`() {
        val wake = PlaybackEngine.Companion::wakeTime
        assertEquals(500 * ms, wake(500_000L, 0L, 0L, 100))
        assertEquals(1_000 * ms, wake(500_000L, 0L, 0L, 50))
        assertEquals(7L, wake(0L, 0L, 7L, 100))
        // About three years of song time at 100 %: the product overflows a Long.
        assertEquals(Long.MAX_VALUE, wake(100_000_000_000_000L, 0L, 0L, 100))
        assertEquals(Long.MAX_VALUE, wake(Long.MAX_VALUE, 0L, 0L, 25))
        // A near-limit clock: the sum overflows instead.
        assertEquals(Long.MAX_VALUE, wake(90_000_000_000_000L, 0L, Long.MAX_VALUE - 1_000L, 100))
        assertTrue(wake(86_400_000_000L, 0L, 5L, 25) > 0)   // a day at 25 %: well inside
    }

    @Test
    fun `seeking a stopped piece leaves it paused there`() {
        engine.load(piece { noteOn(0, 60); noteOff(1000, 60) }, now)
        engine.seek(400_000L, now)
        assertEquals(PlaybackStatus.Paused, engine.status)
        assertEquals(400_000L, engine.positionMicros(now))
        engine.seek(9_000_000L, now)
        assertEquals(1_000_000L, engine.positionMicros(now))
    }

    @Test
    fun `with a pause before the piece the first event leaves exactly 2 s after play, and nothing before`() {
        engine.load(piece { noteOn(0, 60); noteOff(500, 60) }, now)
        engine.play(now, preRollNanos = 2_000 * ms)
        runUntil(1_999)
        assertTrue(link.sent.isEmpty())
        runUntil(5_000)
        assertEquals(listOf("2000 90 3C 50", "2500 80 3C 00", "2500 B0 40 00", "2500 B0 7B 00"), sent())
    }

    @Test
    fun `during the pause the position runs below zero, and a tempo change scales what is left of it`() {
        engine.load(piece { noteOn(0, 60); noteOff(500, 60) }, now)
        engine.play(now, preRollNanos = 2_000 * ms)
        assertEquals(-2_000_000L, engine.positionMicros(now))
        assertEquals(-2_000_000L, engine.startMicros)
        at(1_000)
        assertEquals(-1_000_000L, engine.positionMicros(now))
        engine.setTempo(50, now)   // the second left of the pause now takes two
        assertEquals(-1_000_000L, engine.positionMicros(now))
        assertEquals(3_000 * ms, engine.advance(now))
        runUntil(10_000)
        assertEquals("3000 90 3C 50", sent().first())
    }

    @Test
    fun `a pause inside the pause before the piece holds its start, and play begins it at once`() {
        engine.load(piece { noteOn(0, 60); noteOff(500, 60) }, now)
        engine.play(now, preRollNanos = 2_000 * ms)
        runUntil(1_000)
        engine.pause(now)
        assertEquals(PlaybackStatus.Paused, engine.status)
        assertEquals(0L, engine.positionMicros(now))
        at(4_000)
        engine.play(now)
        runUntil(10_000)
        assertEquals(
            listOf("1000 B0 40 00", "1000 B0 7B 00", "4000 90 3C 50", "4500 80 3C 00", "4500 B0 40 00", "4500 B0 7B 00"),
            sent(),
        )
    }

    @Test
    fun `a seek during the pause before the piece plays from the target at once`() {
        engine.load(piece { noteOn(0, 60); noteOff(500, 60); noteOn(800, 62); noteOff(1_300, 62) }, now)
        engine.play(now, preRollNanos = 2_000 * ms)
        runUntil(500)
        engine.seek(700_000L, now)
        assertEquals(700_000L, engine.positionMicros(now))
        runUntil(10_000)
        assertEquals(
            listOf("500 B0 40 00", "500 B0 7B 00", "600 90 3E 50", "1100 80 3E 00", "1100 B0 40 00", "1100 B0 7B 00"),
            sent(),
        )
    }

    @Test
    fun `during the pause advance sends nothing and wakes at the anchor, when the first event is due`() {
        engine.load(piece { noteOn(0, 60); noteOff(500, 60) }, now)
        engine.play(now, preRollNanos = 2_000 * ms)
        assertEquals(2_000 * ms, engine.anchorNanos)
        assertEquals(engine.anchorNanos, engine.advance(now))
        at(1_234)
        assertEquals(engine.anchorNanos, engine.advance(now))
        assertTrue(link.sent.isEmpty())
    }
}

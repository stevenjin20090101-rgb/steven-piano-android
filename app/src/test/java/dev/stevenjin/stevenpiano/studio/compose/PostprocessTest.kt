// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio.compose

import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.midi.SmfParser
import dev.stevenjin.stevenpiano.midi.SmfWriter
import dev.stevenjin.stevenpiano.studio.StudioFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The composer's post-processor (v1.7 — M24): the light 1/16 grid at the chosen tempo, the piece moved
 * to start on a beat, folding into 24–107, one strike of a key per 120 ms, velocities by mood within
 * 20–110 with the closing fade, no pedal, and a MIDI file the app's parser reads back.
 */
class PostprocessTest {
    /** A Random whose every draw is 0: the velocities' unevenness always the same, the softest. */
    private val even = object : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }

    /** …and one whose every draw is as high as it goes (nextDouble just under 1): the loudest. */
    private val high = object : Random() {
        override fun nextBits(bitCount: Int): Int = if (bitCount >= 32) -1 else (1 shl bitCount) - 1
    }

    private fun note(time: Int, duration: Int, pitch: Int) = AmtEvent(time, duration, pitch)

    /** Random piano events and rests after a 15 s seed, [count] of them, in time order. */
    private fun randomEvents(random: Random, count: Int): List<AmtEvent> {
        var t = 1_500
        return List(count) {
            t += random.nextInt(0, 40)
            if (random.nextInt(10) == 0) AmtEvent.rest(t) else note(t, random.nextInt(0, 300), random.nextInt(0, 128))
        }
    }

    @Test
    fun `onsets move half-way to the 1-16 grid at the chosen tempo, all the way at full strength`() {
        // 100 bpm: a beat is 0.6 s, a sixteenth 0.15 s. 15.10 s -> 15.15, 15.83 -> 15.90, 16.40 -> 16.35 on the grid.
        val events = listOf(note(1_510, 20, 60), note(1_583, 20, 62), note(1_640, 20, 64))
        val light = Postprocess.compose(events, 100, Mood.Calm, even)
        assertEquals("half-way, then back to the beat at 15.0 s", listOf(125_000L, 865_000L, 1_375_000L), light.notes.map { it.onMicros })
        assertEquals("each keeps its length", listOf(325_000L, 1_065_000L, 1_575_000L), light.notes.map { it.offMicros })
        val full = Postprocess.compose(events, 100, Mood.Calm, even, strength = 1.0)
        assertEquals(listOf(150_000L, 900_000L, 1_350_000L), full.notes.map { it.onMicros })
        val none = Postprocess.compose(events, 100, Mood.Calm, even, strength = 0.0)
        assertEquals(listOf(100_000L, 830_000L, 1_400_000L), none.notes.map { it.onMicros })
        assertEquals(100, light.bpm)

        for (bpm in listOf(40, 72, 120, 137, 200)) {
            val step = 60_000_000.0 / bpm / Postprocess.STEPS_PER_BEAT
            val piece = Postprocess.compose(randomEvents(Random(bpm), 300), bpm, Mood.Bright, Random(1), strength = 1.0)
            for (n in piece.notes) {
                val lines = n.onMicros / step
                assertTrue("$bpm bpm: ${n.onMicros} is off the grid", abs(lines - Math.rint(lines)) * step <= 1.0)
            }
            assertTrue("starts within a beat", piece.notes.first().onMicros < 60_000_000L / bpm)
        }
    }

    @Test
    fun `velocities stay within 20 to 110, Calm softest, Wild loudest`() {
        val events = randomEvents(Random(5), 2_000)
        val means = Mood.entries.associateWith { mood ->
            val piece = Postprocess.compose(events, 96, mood, Random(9))
            assertTrue(piece.notes.all { it.velocity in Postprocess.MIN_VELOCITY..Postprocess.MAX_VELOCITY })
            piece.notes.map { it.velocity }.average()
        }
        assertTrue("$means", means.getValue(Mood.Calm) < means.getValue(Mood.Melancholy))
        assertTrue("$means", means.getValue(Mood.Melancholy) < means.getValue(Mood.Bright))
        assertTrue("$means", means.getValue(Mood.Bright) < means.getValue(Mood.Wild))
        // The extremes are held: Wild's top key at a swell's height with the hardest touch would be 111,
        // Calm's lowest key as a chord's bass at the fade's end with the softest touch 15.
        val wildBeat = 60.0 / 200
        val swellTop = (4 * 4 * wildBeat * 1e6 / 2).toLong()
        assertEquals(110, Postprocess.velocities(listOf(Postprocess.Timed(swellTop, swellTop + 100_000, 107)), wildBeat, Mood.Wild, high).single())
        val calmBeat = 60.0 / 40
        val phrase = (4 * 4 * calmBeat * 1e6).toLong()
        val ending = listOf(Postprocess.Timed(0, 100_000, 60), Postprocess.Timed(phrase, phrase + 100_000, 24), Postprocess.Timed(phrase, phrase + 100_000, 50))
        assertEquals(20, Postprocess.velocities(ending, calmBeat, Mood.Calm, even)[1])
    }

    @Test
    fun `the top note of a chord sings out, the bottom steps back`() {
        val chord = listOf(note(1_500, 100, 48), note(1_501, 100, 60), note(1_502, 100, 64), note(1_500, 100, 72))
        val piece = Postprocess.compose(chord + note(1_800, 100, 67), 120, Mood.Bright, even)
        val byKey = piece.notes.associate { it.key to it.velocity }
        assertTrue("$byKey", byKey.getValue(72) > byKey.getValue(64) && byKey.getValue(64) >= byKey.getValue(60) && byKey.getValue(60) > byKey.getValue(48))
    }

    @Test
    fun `keys off the piano fold by octaves into 24 to 107`() {
        val events = listOf(note(1_500, 50, 10), note(1_550, 50, 21), note(1_600, 50, 108), note(1_650, 50, 127), note(1_700, 50, 60))
        val piece = Postprocess.compose(events, 120, Mood.Calm, even, strength = 0.0)
        assertEquals(listOf(34, 33, 96, 103, 60), piece.notes.map { it.key })
        val many = Postprocess.compose(randomEvents(Random(3), 1_000), 120, Mood.Wild, Random(4))
        assertTrue(many.notes.all { it.key in KeyMap.LOWEST..KeyMap.HIGHEST })
    }

    @Test
    fun `a key is struck at most once in 120 ms, and let go before it is struck again`() {
        val events = listOf(
            note(1_500, 50, 60),    // 0-500 ms
            note(1_505, 20, 60),    // 50 ms later: too soon, joins the first
            note(1_502, 10, 64),    // another key: untouched
            note(1_530, 30, 60),    // 300 ms: a strike of its own; the first is let go here
            note(1_600, 5, 67),
            note(1_611, 5, 67),     // 110 ms: the piano could, but the margin says no; joins
            note(1_700, 5, 69),
            note(1_712, 5, 69),     // 120 ms: a strike of its own
        )
        val piece = Postprocess.compose(events, 120, Mood.Calm, even, strength = 0.0)
        assertEquals(
            listOf(
                Triple(0L, 300_000L, 60), Triple(20_000L, 120_000L, 64), Triple(300_000L, 600_000L, 60),
                Triple(1_000_000L, 1_170_000L, 67), Triple(2_000_000L, 2_060_000L, 69), Triple(2_120_000L, 2_180_000L, 69),
            ),
            piece.notes.map { Triple(it.onMicros, it.offMicros, it.key) },
        )
        // Written at the slowest tempo, where a tick is 3.1 ms, no two strikes of a key come under 100 ms in the file.
        val slow = Postprocess.compose(randomEvents(Random(40), 3_000), 40, Mood.Wild, Random(6))
        val file = SmfParser.parse(SmfWriter.write(slow.notes, tempoMicros = SmfWriter.tempoOf(40)))
        val starts = (0 until file.noteCount).groupBy({ file.notes.note(it) }, { file.notes.startMicros[it] })
        for ((key, times) in starts) for ((a, b) in times.sorted().zipWithNext()) assertTrue("key $key in the file: ${b - a} µs", b - a >= 100_000)
        // Keys folded onto one another count as one key.
        val folded = Postprocess.compose(listOf(note(1_500, 100, 12), note(1_504, 100, 24)), 120, Mood.Calm, even, strength = 0.0)
        assertEquals(1, folded.notes.size)
        // Whatever the model writes, and at any tempo.
        for (bpm in listOf(40, 120, 200)) {
            val piece2 = Postprocess.compose(randomEvents(Random(bpm + 1), 3_000), bpm, Mood.Wild, Random(6))
            for ((key, strikes) in piece2.notes.groupBy { it.key }) {
                for ((a, b) in strikes.sortedBy { it.onMicros }.zipWithNext()) {
                    assertTrue("key $key: ${a.onMicros} then ${b.onMicros}", b.onMicros - a.onMicros >= Postprocess.SAME_KEY_MICROS)
                    assertTrue("key $key held past its next strike", a.offMicros <= b.onMicros)
                }
            }
            assertTrue(piece2.notes.all { it.offMicros > it.onMicros })
            assertEquals(piece2.notes.sortedWith(compareBy({ it.onMicros }, { it.key })), piece2.notes)
        }
    }

    @Test
    fun `the last two bars fade to 45 percent`() {
        // A beat apart at 120 bpm, sixteen bars; the same notes with four bars more have no fade where these end.
        fun pulse(bars: Int) = List(bars * 4) { note(1_500 + 50 * it, 40, if (it % 2 == 0) 60 else 64) }
        val short = Postprocess.compose(pulse(16), 120, Mood.Bright, even)
        val long = Postprocess.compose(pulse(20), 120, Mood.Bright, even)
        val fadeStart = short.notes.size - 1 - 2 * 4   // the last note before the last two bars: 8 beats before the end
        for (i in 0 until short.notes.size) {
            val a = short.notes[i].velocity
            val b = long.notes[i].velocity
            if (i <= fadeStart) {
                assertEquals("note $i is before the fade", b, a)
            } else {
                val share = 1.0 - (1.0 - Postprocess.FADE_FLOOR) * (i - fadeStart) / 8.0
                assertEquals("note $i", b * share, a.toDouble(), 1.0)
            }
        }
        assertTrue(short.notes.last().velocity < short.notes[fadeStart].velocity * 0.6)
    }

    @Test
    fun `no pedal, and the MIDI writer's file reads back as the same notes`() {
        val piece = Postprocess.compose(randomEvents(Random(8), 400), 90, Mood.Melancholy, Random(8))
        val bytes = SmfWriter.write(piece.notes, title = "Composition", text = "Made in Studio")
        val parsed = SmfParser.parse(bytes)
        assertTrue("no controller at all", parsed.events.none { it.command == 0xB0 })
        assertEquals(piece.notes.size, parsed.noteCount)
        val tolerance = 1_000_000L / 960 / 2 + 1
        val back = (0 until parsed.noteCount).map { parsed.notes.startMicros[it] to parsed.notes.note(it) }
        for ((want, have) in piece.notes.zip(back.sortedWith(compareBy({ it.first }, { it.second })))) {
            assertEquals(want.key, have.second)
            assertTrue(abs(want.onMicros - have.first) <= tolerance)
        }
        assertEquals(piece.notes.maxOf { it.offMicros }, piece.durationMicros)
    }

    @Test
    fun `nothing but rests is no piece`() {
        try {
            Postprocess.compose(listOf(AmtEvent.rest(1_500), AmtEvent.rest(1_600)), 120, Mood.Calm, even)
            fail("a piece of rests")
        } catch (e: StudioFailure) {
            assertEquals(ComposeFailures.NO_MUSIC, e.message)
        }
    }
}

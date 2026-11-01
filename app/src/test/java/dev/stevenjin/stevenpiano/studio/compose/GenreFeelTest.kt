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
import dev.stevenjin.stevenpiano.midi.SmfWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * The genre styles' feel (v1.37 — M74): lo-fi's swing, house's grid, each genre's velocities and cap, cinematic's
 * arc, chill's thinning and ritardando, the pedal, and the piano's limits as they were.
 */
class GenreFeelTest {
    /** A Random whose every draw is 0: no laziness, the velocities' unevenness always the same. */
    private val even = object : Random() {
        override fun nextBits(bitCount: Int): Int = 0
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

    /** The most keys down at once (a key let go at the instant another is struck is not down with it). */
    private fun held(notes: List<SmfWriter.Note>): Int {
        val edges = notes.flatMap { listOf(it.onMicros to 1, it.offMicros to -1) }.sortedWith(compareBy({ it.first }, { it.second }))
        var down = 0
        var most = 0
        for ((_, step) in edges) {
            down += step
            most = maxOf(most, down)
        }
        return most
    }

    @Test
    fun `lo-fi swings the off-beat eighths later, leaves the beat, and keeps each key's next strike 120 ms away`() {
        // 100 bpm: a beat is 600 ms. On the beat, on the off-beat eighth, each its own key, 250 ms long.
        val notes = listOf(
            Postprocess.Timed(0, 250_000, 60),
            Postprocess.Timed(300_000, 550_000, 62),
            Postprocess.Timed(600_000, 850_000, 64),
            Postprocess.Timed(900_000, 1_150_000, 65),
        )
        val swung = GenreFeel.timing(notes, 0.6, Genre.LoFi, even)
        assertEquals("an off-beat eighth a tenth of a beat later (60 % swing)", listOf(0L, 360_000L, 600_000L, 960_000L), swung.map { it.on })
        assertEquals("ends where they were", notes.map { it.off }, swung.map { it.off })
        assertEquals("a sixteenth half as far", 30_000L, GenreFeel.swingDelay(150_000, 600_000.0))
        for (genre in listOf(Genre.House, Genre.Chill, Genre.Cinematic, null)) assertEquals(notes, GenreFeel.timing(notes, 0.6, genre, even))

        val lazy = GenreFeel.timing(notes, 0.6, Genre.LoFi, Random(3))
        for (i in notes.indices) assertTrue("lazy: up to 20 ms late, never early", lazy[i].on - swung[i].on in 0L..GenreFeel.LAZY_MICROS)

        // One key struck again 120 ms later: the first moves only as far as keeps them 120 ms apart.
        val again = GenreFeel.timing(listOf(Postprocess.Timed(300_000, 420_000, 60), Postprocess.Timed(420_000, 700_000, 60)), 0.6, Genre.LoFi, even)
        assertEquals(Postprocess.SAME_KEY_MICROS, again[1].on - again[0].on)
        assertTrue(again.all { it.off - it.on >= Postprocess.MIN_NOTE_MICROS })
    }

    @Test
    fun `house quantises to the sixteenths more firmly, straight and dry`() {
        val events = randomEvents(Random(4), 600)
        val step = 60_000_000.0 / 124 / Postprocess.STEPS_PER_BEAT
        fun offGrid(piece: Composition) = piece.notes.map { n -> (n.onMicros / step).let { abs(it - Math.rint(it)) * step } }.average()
        val plain = Postprocess.compose(events, 124, Mood.Bright, Random(1))
        val house = Postprocess.compose(events, 124, Mood.Bright, Random(1), genre = Genre.House)
        assertTrue("${offGrid(house)} vs ${offGrid(plain)}", offGrid(house) < offGrid(plain) * 0.75)
        assertTrue(house.pedals.isEmpty())
    }

    @Test
    fun `each genre plays under its cap, lo-fi and chill softer than the mood, house brighter`() {
        val events = randomEvents(Random(5), 2_000)
        for (genre in Genre.entries) {
            val loud = Postprocess.compose(events, 96, Mood.Wild, Random(9), genre = genre)
            assertTrue(genre.name, loud.notes.all { it.velocity in Postprocess.MIN_VELOCITY..GenreFeel.cap(genre) })
            assertTrue("gentle: under the piano's loudest", GenreFeel.cap(genre) < Postprocess.MAX_VELOCITY)
        }
        fun mean(genre: Genre?) = Postprocess.compose(events, 96, Mood.Calm, Random(9), genre = genre).notes.map { it.velocity }.average()
        assertTrue(mean(Genre.LoFi) < mean(null))
        assertTrue(mean(Genre.Chill) < mean(null))
        assertTrue(mean(Genre.House) > mean(null))
    }

    @Test
    fun `cinematic builds to its peak about two thirds through, then fades`() {
        assertEquals(GenreFeel.ARC_PEAK, GenreFeel.arc(2.0 / 3), 1e-9)
        assertTrue(GenreFeel.arc(0.0) < GenreFeel.arc(0.33) && GenreFeel.arc(0.33) < GenreFeel.arc(0.6))
        assertTrue(GenreFeel.arc(0.75) > GenreFeel.arc(0.9) && GenreFeel.arc(0.9) > GenreFeel.arc(1.0))
        // Two minutes of a steady stream, a note every 250 ms: the loudest tenth is the one around two thirds.
        val events = (0 until 480).map { note(1_500 + it * 25, 20, 60 + it % 12) }
        val piece = Postprocess.compose(events, 120, Mood.Melancholy, even, genre = Genre.Cinematic)
        val last = piece.notes.last().onMicros + 1
        val tenths = (0 until 10).map { k -> piece.notes.filter { (it.onMicros * 10 / last).toInt() == k }.map { it.velocity }.average() }
        assertTrue("$tenths", tenths.indices.maxBy { tenths[it] } in 5..7)
        assertTrue("soft at the start", tenths[0] < tenths[6] * 0.7)
    }

    @Test
    fun `every genre keeps the piano's limits - no note longer, no more keys down at once, every note and pedal ending`() {
        for (seed in 1..3) {
            val events = randomEvents(Random(seed), 1_500)
            for (genre in Genre.entries) {
                // The same grid without the feel: what the feel was given.
                val plain = Postprocess.compose(events, 84, Mood.Calm, Random(seed), strength = GenreFeel.strength(genre, Postprocess.STRENGTH))
                val felt = Postprocess.compose(events, 84, Mood.Calm, Random(seed), genre = genre)
                val what = "$genre, seed $seed"
                assertTrue(what, felt.notes.size <= plain.notes.size)
                assertTrue(what, felt.notes.all { it.offMicros > it.onMicros && it.key in KeyMap.LOWEST..KeyMap.HIGHEST })
                assertTrue(what, felt.notes.all { it.velocity in Postprocess.MIN_VELOCITY..Postprocess.MAX_VELOCITY })
                for ((_, strikes) in felt.notes.groupBy { it.key }) {
                    strikes.sortedBy { it.onMicros }.zipWithNext { a, b ->
                        assertTrue("$what: one strike at a time", b.onMicros - a.onMicros >= Postprocess.SAME_KEY_MICROS && a.offMicros <= b.onMicros)
                    }
                }
                assertTrue("$what: no more keys down at once", held(felt.notes) <= held(plain.notes))
                assertTrue("$what: no note longer", felt.notes.maxOf { it.offMicros - it.onMicros } <= plain.notes.maxOf { it.offMicros - it.onMicros })
                assertEquals(what, felt.notes.maxOf { it.offMicros }, felt.durationMicros)
                assertTrue("$what: every pedal goes up, by the end", felt.pedals.all { it.upMicros > it.downMicros && it.upMicros <= felt.durationMicros })
                felt.pedals.zipWithNext { a, b -> assertTrue("$what: a change between pedals", a.upMicros < b.downMicros) }
            }
        }
    }

    @Test
    fun `lo-fi changes the pedal every bar, chill thins its chords and slows at the end`() {
        val events = randomEvents(Random(11), 1_500)
        val bar = (1 * (Postprocess.BEATS_PER_BAR * (60.0 / 84) * 1e6)).roundToLong()
        val lofi = Postprocess.compose(events, 84, Mood.Calm, Random(2), genre = Genre.LoFi)
        assertTrue(lofi.pedals.size >= lofi.durationMicros / bar)
        assertTrue(lofi.pedals.all { (it.downMicros - GenreFeel.PEDAL_LAG_MICROS) % bar == 0L && it.upMicros - it.downMicros <= bar })

        val plain = Postprocess.compose(events, 84, Mood.Calm, Random(2))
        val chill = Postprocess.compose(events, 84, Mood.Calm, Random(2), genre = Genre.Chill)
        assertTrue("sparser", chill.notes.size < plain.notes.size)
        assertEquals("the start as it was", plain.notes.first().onMicros, chill.notes.first().onMicros)
        assertTrue("a ritardando at the end", chill.notes.maxOf { it.onMicros } > plain.notes.maxOf { it.onMicros })
        assertTrue("long pedal: two bars", chill.pedals.first().upMicros - chill.pedals.first().downMicros > bar)
    }
}

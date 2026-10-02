// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.midi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The MIDI writer (v1.7 — M23, Studio's transcriptions): what it writes, the app's own parser reads back
 * as the same notes, pedal, title and text, to within half a tick (about half a millisecond).
 */
class SmfWriterTest {
    /** Half a tick at 960 ticks a second, and a microsecond of the parser's rounding. */
    private val tolerance = 1_000_000L / 960 / 2 + 1

    @Test
    fun `notes, pedal, title and text round-trip through the app's parser`() {
        val notes = listOf(
            SmfWriter.Note(50_000, 900_000, 60, 64),
            SmfWriter.Note(1_000_000, 1_500_000, 64, 90),
            SmfWriter.Note(1_000_000, 1_250_000, 67, 30),
            SmfWriter.Note(2_345_678, 4_000_000, 21, 127),
            SmfWriter.Note(3_000_000, 3_010_000, 108, 1),
        )
        val pedals = listOf(SmfWriter.Pedal(40_000, 950_000), SmfWriter.Pedal(2_000_000, 3_900_000))
        val bytes = SmfWriter.write(notes, pedals, title = "Clair de lune — étude", text = "Made in Studio · 28 Sept 2026")
        val piece = SmfParser.parse(bytes)
        assertEquals(0, piece.format)
        assertEquals(SmfWriter.PPQ, piece.ppq)
        assertEquals("Clair de lune — étude", piece.sequenceName)
        assertEquals(listOf("Made in Studio · 28 Sept 2026"), piece.texts)
        assertEquals(emptyList<String>(), piece.warnings)
        assertEquals(notes.size, piece.noteCount)
        val sorted = notes.sortedWith(compareBy({ it.onMicros }, { it.key }))
        val got = (0 until piece.noteCount).map { i -> piece.notes.run { SmfWriter.Note(startMicros[i], endMicros[i], note(i), velocity(i)) } }
            .sortedWith(compareBy({ it.onMicros }, { it.key }))
        for ((want, have) in sorted.zip(got)) {
            assertTrue("$want vs $have", abs(want.onMicros - have.onMicros) <= tolerance && abs(want.offMicros - have.offMicros) <= tolerance)
            assertEquals(want.key, have.key)
            assertEquals(want.velocity, have.velocity)
        }
        val sustain = piece.events.filter { it.command == 0xB0 && it.data1 == 64 }
        assertEquals(listOf(127, 0, 127, 0), sustain.map { it.data2 })
        val times = listOf(40_000L, 950_000L, 2_000_000L, 3_900_000L)
        for ((want, have) in times.zip(sustain.map { it.atMicros })) assertTrue("pedal at $have, not $want", abs(want - have) <= tolerance)
        assertTrue(abs(piece.durationMicros - 4_000_000) <= tolerance)
    }

    @Test
    fun `a note lasts at least a tick, velocities stay 1 to 127, and a key struck again as it ends is let go first`() {
        val bytes = SmfWriter.write(
            listOf(
                SmfWriter.Note(1_000_000, 1_000_000, 60, 0),      // no length, velocity 0: a tick long, velocity 1
                SmfWriter.Note(2_000_000, 1_900_000, 62, 128),    // ends before it starts: a tick long, velocity 127
                SmfWriter.Note(3_000_000, 3_500_000, 64, 50),
                SmfWriter.Note(3_500_000, 4_000_000, 64, 70),     // the same key again at the tick the first ends
            ),
        )
        val piece = SmfParser.parse(bytes)
        assertEquals(4, piece.noteCount)
        val n = piece.notes
        assertEquals(listOf(60, 62, 64, 64), (0 until 4).map { n.note(it) })
        assertEquals(listOf(1, 127, 50, 70), (0 until 4).map { n.velocity(it) })
        for (i in 0 until 2) assertTrue("note $i lasts ${n.endMicros[i] - n.startMicros[i]} µs", n.endMicros[i] - n.startMicros[i] in 1_000L..1_100L)
        assertTrue(abs(n.endMicros[2] - 3_500_000) <= tolerance)
        assertTrue("the second strike of the key is its own note", abs(n.startMicros[3] - 3_500_000) <= tolerance)
        val at = piece.events.filter { abs(it.atMicros - 3_500_000) <= tolerance }.map { it.command }
        assertEquals("off before on at one tick", listOf(0x80, 0x90), at)
    }

    @Test
    fun `long silences, many notes and an empty piece are written as the parser expects`() {
        val random = Random(9)
        val many = List(3_000) {
            val on = random.nextLong(0, 20L * 60 * 1_000_000)
            SmfWriter.Note(on, on + random.nextLong(10_000, 3_000_000), random.nextInt(21, 109), random.nextInt(1, 128))
        }
        val piece = SmfParser.parse(SmfWriter.write(many, title = "Twenty minutes"))
        assertEquals(3_000, piece.noteCount)
        assertEquals(emptyList<String>(), piece.warnings)
        val gap = SmfParser.parse(SmfWriter.write(listOf(SmfWriter.Note(19L * 60 * 1_000_000, 19L * 60 * 1_000_000 + 500_000, 60, 80))))
        assertTrue(abs(gap.notes.startMicros[0] - 19L * 60 * 1_000_000) <= tolerance)
        val empty = SmfParser.parse(SmfWriter.write(emptyList()))
        assertEquals(0, empty.noteCount)
        assertEquals(0L, empty.durationMicros)
    }

    @Test
    fun `the header and track are laid out byte for byte`() {
        val bytes = SmfWriter.write(listOf(SmfWriter.Note(0, 500_000, 60, 100)))
        // MThd, a header of 6, format 0, one track, 480 ticks a quarter note.
        assertArrayEquals(byteArrayOf(0x4D, 0x54, 0x68, 0x64, 0, 0, 0, 6, 0, 0, 0, 1, 0x01, 0xE0.toByte()), bytes.copyOfRange(0, 14))
        assertEquals("MTrk", String(bytes, 14, 4, Charsets.US_ASCII))
        // Tempo 500,000 µs a beat, then 4/4, then the note: on at 0, off 480 ticks later (0x83 0x60), then End of Track.
        val track = bytes.copyOfRange(22, bytes.size)
        assertArrayEquals(
            byteArrayOf(
                0, 0xFF.toByte(), 0x51, 3, 0x07, 0xA1.toByte(), 0x20,
                0, 0xFF.toByte(), 0x58, 4, 4, 2, 24, 8,
                0, 0x90.toByte(), 60, 100,
                0x83.toByte(), 0x60, 0x80.toByte(), 60, 64,
                0, 0xFF.toByte(), 0x2F, 0,
            ),
            track,
        )
        assertEquals(480L, SmfWriter.ticks(500_000))
        assertEquals("rounded to the nearest tick", 1L, SmfWriter.ticks(600))
        assertEquals(0L, SmfWriter.ticks(-5))
    }

    @Test
    fun `a tempo of its own round-trips, its beats on the file's beats and bars`() {
        // 96 bpm (625,000 µs a quarter note): a sixteenth is 156,250 µs, 120 ticks.
        val tempo = SmfWriter.tempoOf(96)
        assertEquals(625_000, tempo)
        val sixteenth = 156_250L
        val notes = List(64) { i -> SmfWriter.Note(i * sixteenth, i * sixteenth + 2 * sixteenth, 48 + i % 36, 40 + i) }
        val bytes = SmfWriter.write(notes, title = "Composition", text = "Made in Studio", tempoMicros = tempo)
        val piece = SmfParser.parse(bytes)
        assertEquals(emptyList<String>(), piece.warnings)
        assertEquals(1, piece.tempoMap.size)
        assertEquals(tempo, piece.tempoMap.tempoAt(0))
        assertEquals(notes.size, piece.noteCount)
        val half = tempo.toLong() / SmfWriter.PPQ / 2 + 1   // half a tick at this tempo, and the parser's microsecond
        for (i in notes.indices) {
            assertTrue("note $i at ${piece.notes.startMicros[i]}", abs(piece.notes.startMicros[i] - notes[i].onMicros) <= half)
            assertEquals("note $i on the sixteenth's tick", i * 120L, piece.tempoMap.microsToTicks(piece.notes.startMicros[i]))
        }
        assertEquals("four beats a bar at 96 bpm: 2.5 s", 2_500_000L, piece.barStartsMicros[1])
        assertEquals("the last note ends a sixteenth into a fifth bar", listOf(0L, 2_500_000L, 5_000_000L, 7_500_000L, 10_000_000L), piece.barStartsMicros.toList())
        // The tempo's own three bytes, then 4/4.
        val track = bytes.copyOfRange(22, bytes.size)
        val at = (0 until track.size - 6).first { track[it] == 0xFF.toByte() && track[it + 1] == 0x51.toByte() }
        assertArrayEquals(byteArrayOf(3, 0x09, 0x89.toByte(), 0x68), track.copyOfRange(at + 2, at + 6))
        assertEquals("half a second at 96 bpm: 0.8 beats", 384L, SmfWriter.ticks(500_000, tempo))
        assertEquals("the default is 120 bpm", SmfWriter.ticks(123_456), SmfWriter.ticks(123_456, SmfWriter.TEMPO_MICROS))
        assertEquals(500_000, SmfWriter.tempoOf(120))
        assertEquals(1_500_000, SmfWriter.tempoOf(40))
        assertEquals(300_000, SmfWriter.tempoOf(200))
        assertEquals(437_956, SmfWriter.tempoOf(137))
    }

    @Test
    fun `a recording's controller values round-trip, half pedal and the other pedals too (v1_11 M29)`() {
        val controls = listOf(
            SmfWriter.Control(0, 64, 40),
            SmfWriter.Control(120_000, 64, 100),
            SmfWriter.Control(120_000, 66, 127),
            SmfWriter.Control(500_000, 67, 64),
            SmfWriter.Control(900_000, 64, 0),
            SmfWriter.Control(900_000, 66, 0),
            SmfWriter.Control(900_000, 67, 0),
        )
        val bytes = SmfWriter.write(listOf(SmfWriter.Note(100_000, 800_000, 60, 90)), controls = controls)
        val piece = SmfParser.parse(bytes)
        val read = piece.events.filter { it.command == 0xB0 }.map { Triple(it.data1, it.data2, it.atMicros) }
        assertEquals(controls.map { it.controller to it.value }, read.map { it.first to it.second })
        for ((want, have) in controls.zip(read)) assertTrue("at ${have.third}, not ${want.atMicros}", abs(want.atMicros - have.third) <= tolerance)
        assertEquals(1, piece.noteCount)
        val clamped = SmfParser.parse(SmfWriter.write(listOf(SmfWriter.Note(0, 10_000, 60, 90)), controls = listOf(SmfWriter.Control(0, 200, 300))))
        assertEquals(listOf(119 to 127), clamped.events.filter { it.command == 0xB0 }.map { it.data1 to it.data2 })
    }
}

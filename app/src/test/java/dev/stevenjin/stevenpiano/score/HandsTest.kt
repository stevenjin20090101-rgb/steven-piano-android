// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.score

import dev.stevenjin.stevenpiano.midi.MidiPiece
import dev.stevenjin.stevenpiano.midi.NoteList
import dev.stevenjin.stevenpiano.midi.SmfBuilder
import dev.stevenjin.stevenpiano.midi.SmfParser
import dev.stevenjin.stevenpiano.midi.TempoMap
import dev.stevenjin.stevenpiano.midi.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Test

class HandsTest {
    private val R = Hands.RIGHT
    private val L = Hands.LEFT

    /** A note at [tick] on [key], [length] ticks long (480 to the quarter, 120 BPM). */
    private data class N(val tick: Long, val key: Int, val length: Long)

    /** A format-1 file: a meta track, then one track per entry of [tracks] (its name, or none, and its notes). */
    private fun piece(vararg tracks: Pair<String?, List<N>>): MidiPiece {
        val builder = SmfBuilder(format = 1, division = 480).track { name(0, "Sonata") }
        for ((name, notes) in tracks) {
            builder.track {
                if (name != null) name(0, name)
                val events = notes.flatMap { listOf(Triple(it.tick, it.key, true), Triple(it.tick + it.length, it.key, false)) }
                    .sortedWith(compareBy({ it.first }, { it.third }))
                for ((tick, key, on) in events) if (on) noteOn(tick, key) else noteOff(tick, key)
            }
        }
        return SmfParser.parse(builder.build())
    }

    private fun hands(piece: MidiPiece) = Hands.assign(piece.notes, piece.trackNames, piece.tempoMap, piece.timeSignatures)

    /** The hand given to the note on [key] starting at [tick]. */
    private fun MidiPiece.handOf(hands: ByteArray, tick: Long, key: Int): Byte =
        hands[(0 until notes.size).first { notes.startMicros[it] == tempoMap.tickToMicros(tick) && notes.note(it) == key }]

    private fun scale(from: Int, count: Int, at: Long = 0L) = List(count) { N(at + it * 480L, from + it, 480) }

    @Test
    fun `track names read as hands, as whole words`() {
        val right = listOf("Piano right", "Piano right Main", "Accompaniment right", "upper:", "Upper", "uppera", "treble:comes", "RH", "r.h.", "RightHand", "PianoRH")
        val left = listOf("Piano left", "Piano left2", "lower:", "lowerb", "bass:dux", "LH", "L.H", "LeftHand", "Basso")
        val neither = listOf("", "Piano", "Pedale", "Fuga 1", "Triller", "Bassoon", "Copyright © 2003 by Bernd Krueger", "Flower", "one", "\\new:mel", "left and right")
        right.forEach { assertEquals(it, R.toInt(), Hands.classify(it)) }
        left.forEach { assertEquals(it, L.toInt(), Hands.classify(it)) }
        neither.forEach { assertEquals(it, Hands.UNKNOWN, Hands.classify(it)) }
    }

    @Test
    fun `named tracks decide, even against their pitches`() {
        // The "right" track plays low and the "left" track high: the names win.
        val crossed = piece("Piano right" to scale(48, 4), "Piano left" to scale(72, 4))
        val hands = hands(crossed)
        for (i in 0 until crossed.notes.size) {
            assertEquals(if (crossed.notes.note(i) < 60) R else L, hands[i])
        }
        assertEquals(listOf("Sonata", "Piano right", "Piano left"), crossed.trackNames)
    }

    @Test
    fun `one named track of two makes the other the other hand`() {
        val piece = piece("Upper" to scale(40, 4), null to scale(76, 4))
        val hands = hands(piece)
        for (i in 0 until piece.notes.size) assertEquals(if (piece.notes.note(i) < 60) R else L, hands[i])
    }

    @Test
    fun `an unnamed third track is split by pitch`() {
        val fugue = piece(
            "Piano right" to scale(72, 8),
            "Piano left" to scale(40, 8),
            "Fuga 1" to listOf(N(0, 79, 480), N(480, 43, 480)),
        )
        val hands = hands(fugue)
        assertEquals(R, fugue.handOf(hands, 0, 79))
        assertEquals(L, fugue.handOf(hands, 480, 43))
        assertEquals(R, fugue.handOf(hands, 480, 73))
        assertEquals(L, fugue.handOf(hands, 480, 41))
    }

    @Test
    fun `two unnamed tracks - the higher median is the right hand, whichever comes first`() {
        val upFirst = piece("one" to scale(67, 5), "two" to scale(50, 5))
        val downFirst = piece("up" to scale(50, 5), "down" to scale(67, 5))
        for (p in listOf(upFirst, downFirst)) {
            val hands = hands(p)
            for (i in 0 until p.notes.size) assertEquals(if (p.notes.note(i) >= 60) R else L, hands[i])
        }
        // Medians, not ranges: a track that dips once is still the upper one.
        val dip = piece(null to (scale(70, 6) + N(3_000, 30, 480)), null to scale(55, 7))
        val hands = hands(dip)
        assertEquals(R, dip.handOf(hands, 3_000, 30))
        assertEquals(L, dip.handOf(hands, 0, 55))
    }

    @Test
    fun `a name for one hand alone decides nothing`() {
        // A single track named "Piano right" that holds both hands: split by pitch.
        val both = piece("Piano right" to listOf(N(0, 36, 1920), N(0, 43, 1920), N(0, 76, 480), N(480, 79, 480), N(960, 84, 960)))
        val hands = hands(both)
        assertEquals(L, both.handOf(hands, 0, 36))
        assertEquals(L, both.handOf(hands, 0, 43))
        assertEquals(R, both.handOf(hands, 480, 79))
    }

    @Test
    fun `one track is split by pitch, the bass against the tune`() {
        val notes = mutableListOf<N>()
        for (bar in 0 until 4) {
            val at = bar * 1920L
            listOf(36, 43, 48).forEach { notes += N(at, it, 1920) }                      // the left hand's chord
            listOf(76, 74, 72, 71).forEachIndexed { k, key -> notes += N(at + k * 480, key, 480) }   // the tune
        }
        val single = piece(null to notes)
        val hands = hands(single)
        for (i in 0 until single.notes.size) assertEquals(if (single.notes.note(i) >= 60) R else L, hands[i])
    }

    @Test
    fun `notes within an octave are one hand, by middle C`() {
        val high = piece(null to scale(67, 8))                 // G4 up to D5, alone
        val low = piece(null to listOf(40, 43, 47, 45, 48, 52).mapIndexed { k, key -> N(k * 480L, key, 480) })
        assertEquals(List(8) { R }, hands(high).toList())
        assertEquals(List(6) { L }, hands(low).toList())
    }

    @Test
    fun `a beamed run keeps one hand where the split would cut it`() {
        // Held C2, E2, E3 below and C5, E5, G5 above; in beat 2 a run of sixteenths G3 B♭3 C4 E♭4.
        // The split falls at C4-and-a-half: alone, E♭4 would go to the right hand.
        val held = listOf(36, 40, 52, 72, 76, 79).map { N(0, it, 1920) }
        val run = listOf(55, 58, 60, 63).mapIndexed { k, key -> N(480 + k * 120L, key, 120) }
        val piece = piece(null to held + run)
        val hands = hands(piece)
        for (n in run) assertEquals("run note ${n.key}", L, piece.handOf(hands, n.tick, n.key))
        for (n in held) assertEquals(if (n.key > 60) R else L, piece.handOf(hands, n.tick, n.key))
        // The same E♭4 held for a beat is no part of a run: it keeps the split's hand.
        val alone = piece(null to held + run.dropLast(1) + N(840, 63, 480))
        assertEquals(R, alone.handOf(hands(alone), 840, 63))
    }

    @Test
    fun `runs take the hand of most of their notes, and stop at a rest, a leap or the beat`() {
        // Four sixteenths per beat, one track, notes given hands directly.
        val starts = LongArray(12) { it * 125_000L }
        val keys = intArrayOf(60, 62, 64, 65, 67, 69, 71, 72, 74, 76, 77, 79)
        val ends = LongArray(12) { starts[it] + 125_000L }
        ends[1] = starts[1] + 60_000L                    // a rest after the second note: the run ends there
        keys[9] = 90                                      // a leap of more than an octave: a new run
        val notes = NoteList(starts, ends, ByteArray(12) { keys[it].toByte() }, ByteArray(12) { 80 }, ByteArray(12))
        val given = byteArrayOf(L, L, R, R, L, L, R, L, R, R, L, L)
        val hands = given.copyOf()
        Hands.smoothRuns(notes, hands, TempoMap.constant(480), listOf(TimeSignature.Common)) { true }
        // Beat 1: the rest makes two runs (L L) and (R R), which stay; beat 2 (L L R L) → L;
        // beat 3: 8 alone, then 9 (the leap) alone, then 10 and 11 (L L).
        assertEquals(listOf(L, L, R, R, L, L, L, L, R, R, L, L), hands.toList())
    }
}

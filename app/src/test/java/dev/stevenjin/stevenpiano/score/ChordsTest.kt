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
import dev.stevenjin.stevenpiano.midi.SmfBuilder
import dev.stevenjin.stevenpiano.midi.SmfParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ChordsTest {
    /** A 4/4 file at 120 BPM (480 ticks a quarter): each entry of [bars] is one bar's chord, held for the bar. */
    private fun bars(vararg bars: List<Int>, sharps: Int? = null): MidiPiece = piece(sharps) {
        bars.forEachIndexed { b, keys -> keys.forEach { note(b * 1_920L, it, 1_920) } }
    }

    private class Notes {
        val events = mutableListOf<Triple<Long, Int, Boolean>>()

        fun note(tick: Long, key: Int, length: Long) {
            events += Triple(tick, key, true)
            events += Triple(tick + length, key, false)
        }
    }

    private fun piece(sharps: Int? = null, notes: Notes.() -> Unit): MidiPiece {
        val events = Notes().apply(notes).events.sortedWith(compareBy({ it.first }, { it.third }))
        return SmfParser.parse(
            SmfBuilder(format = 1, division = 480)
                .track { tempo(0, 500_000); if (sharps != null) keySignature(0, sharps) }
                .track { for ((tick, key, on) in events) if (on) noteOn(tick, key) else noteOff(tick, key) }
                .build(),
        )
    }

    private fun chords(piece: MidiPiece): ChordTrack =
        Chords.detect(piece.notes, piece.tempoMap, piece.barStartsMicros, piece.timeSignatures, piece.keySignatures)

    private fun names(piece: MidiPiece, transpose: Int = 0): List<String> = chords(piece).let { c -> List(c.size) { c.name(it, transpose) } }

    @Test
    fun `triads, sevenths and the diminished chord are named`() {
        assertEquals(listOf("C"), names(bars(listOf(60, 64, 67))))
        assertEquals(listOf("Am"), names(bars(listOf(57, 60, 64))))
        assertEquals(listOf("C7"), names(bars(listOf(60, 64, 67, 70))))
        assertEquals(listOf("Ddim"), names(bars(listOf(62, 65, 68))))
    }

    @Test
    fun `a bass that is not the root makes a slash chord`() {
        assertEquals(listOf("G/B"), names(bars(listOf(47, 55, 59, 62))))
        assertEquals(listOf("B♭/D"), names(bars(listOf(50, 58, 62, 65))))
        // The root in the bass: no slash.
        assertEquals(listOf("G"), names(bars(listOf(43, 59, 62, 67))))
    }

    @Test
    fun `chords are spelled in the key`() {
        assertEquals(listOf("E♭"), names(bars(listOf(51, 55, 58), sharps = -3)))
        // No key signature: E♭ major's notes suggest three flats.
        assertEquals(listOf("E♭", "A♭", "B♭7", "E♭"), names(bars(listOf(51, 55, 58), listOf(56, 60, 63), listOf(58, 62, 65, 68), listOf(51, 55, 58, 63))))
        // C major: a borrowed B♭ is a flat, a leading-tone diminished chord a sharp.
        val cMajor = bars(listOf(60, 64, 67), listOf(58, 62, 65), listOf(54, 57, 60), listOf(55, 59, 62), sharps = 0)
        assertEquals(listOf("C", "B♭", "F♯dim", "G"), names(cMajor))
        // Transposed up a tone, C major reads in D major.
        assertEquals(listOf("D", "C", "G♯dim", "A"), names(cMajor, transpose = 2))
    }

    @Test
    fun `sus4 and maj9, the reference's chords`() {
        assertEquals(listOf("Gsus4"), names(bars(listOf(43, 60, 62, 67))))
        assertEquals(listOf("Fmaj9"), names(bars(listOf(41, 57, 60, 64, 67))))
        assertEquals(listOf("Csus2"), names(bars(listOf(48, 62, 67, 72))))   // the same notes as Gsus4, over C
    }

    @Test
    fun `names come only at changes`() {
        val track = chords(bars(listOf(60, 64, 67), listOf(48, 64, 67, 72), listOf(55, 59, 65, 74), listOf(48, 60, 64, 67)))
        assertEquals(listOf("C", "G7", "C"), List(track.size) { track.name(it) })
        assertEquals(listOf(0L, 4_000_000L, 6_000_000L), track.startMicros.toList())
    }

    @Test
    fun `an ambiguous or empty stretch keeps the name before`() {
        // A bare fifth is D major or D minor, so neither; a single note is no chord; silence is none.
        val track = chords(
            piece {
                listOf(60, 64, 67).forEach { note(0, it, 1_920) }
                listOf(62, 69).forEach { note(1_920, it, 1_920) }
                note(3_840, 74, 1_920)
                listOf(55, 59, 62).forEach { note(7_680, it, 1_920) }
            },
        )
        assertEquals(listOf("C", "G"), List(track.size) { track.name(it) })
        assertEquals(7_680L * 1_000_000 / 960, track.startMicros[1])
    }

    @Test
    fun `an arpeggio is named from its first beat, its third arriving on the second`() {
        val arpeggio = piece {
            listOf(48, 55, 64, 67, 72).forEach { note(0, it, 1_920) }                      // bar 1: C
            // Bar 2: C in the bass, then D and A, and F only from the second beat: Dm7 over C.
            note(1_920, 48, 1_920)
            note(1_920, 50, 1_920)
            note(2_160, 57, 1_680)
            note(2_400, 62, 1_440)
            note(2_640, 65, 1_200)
        }
        val track = chords(arpeggio)
        assertEquals(listOf("C", "Dm7/C"), List(track.size) { track.name(it) })
        assertEquals(2_000_000L, track.startMicros[1])
    }

    @Test
    fun `a slash bass is spelled from its chord`() {
        assertEquals("B♭/D", Chords.name(10, Chords.MAJOR, 2, 0))
        assertEquals("F♯m7/E", Chords.name(6, Chords.M7, 4, 2))
        assertEquals("E♭/G", Chords.name(3, Chords.MAJOR, 7, -3))
        assertEquals("Caug/G♯", Chords.name(0, Chords.AUG, 8, 0))
        assertEquals("C/D", Chords.name(0, Chords.MAJOR, 2, 0))           // not a chord tone: spelled in the key
    }
}

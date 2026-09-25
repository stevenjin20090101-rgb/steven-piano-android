// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================


package dev.stevenjin.stevenpiano.score

import dev.stevenjin.stevenpiano.ui.components.StaffPitch
import org.junit.Assert.assertEquals
import org.junit.Test

class SpellingTest {
    private val E_FLAT = -3
    private val G = 1
    private val C = 0

    /** The accidentals a bar in [sharps] shows for [keys] played in turn, on the treble staff. */
    private fun bar(sharps: Int, vararg keys: Int): List<Int> {
        val bar = BarAccidentals().apply { reset(sharps) }
        return keys.map { bar.accidental(true, Spelling.step(it, sharps), Spelling.alteration(it, sharps)) }
    }

    @Test
    fun `E-flat major - B-flat is the key's own note, B shows a natural, E-flat nothing`() {
        assertEquals("B♭4", Spelling.name(70, E_FLAT))
        assertEquals(listOf(Accidental.NONE), bar(E_FLAT, 70))
        assertEquals("B♮4", Spelling.name(71, E_FLAT))
        assertEquals(listOf(Accidental.NATURAL), bar(E_FLAT, 71))
        assertEquals("E♭4", Spelling.name(63, E_FLAT))
        assertEquals(listOf(Accidental.NONE), bar(E_FLAT, 63))
        assertEquals(Spelling.step(70, E_FLAT), Spelling.step(71, E_FLAT))   // both on the B line
    }

    @Test
    fun `G major - F-sharp needs nothing, F shows a natural`() {
        assertEquals("F♯4", Spelling.name(66, G))
        assertEquals(listOf(Accidental.NONE), bar(G, 66))
        assertEquals(listOf(Accidental.NATURAL, Accidental.SHARP), bar(G, 65, 66))
    }

    @Test
    fun `C major - C-sharp shows its sharp once per bar`() {
        assertEquals("C♯4", Spelling.name(61, C))
        assertEquals(listOf(Accidental.SHARP, Accidental.NONE), bar(C, 61, 61))
        assertEquals(listOf(Accidental.SHARP, Accidental.NATURAL, Accidental.SHARP), bar(C, 61, 60, 61))
    }

    @Test
    fun `a return to the key's note after a natural shows the key's accidental again`() {
        assertEquals(listOf(Accidental.NATURAL, Accidental.NONE, Accidental.FLAT), bar(E_FLAT, 71, 71, 70))
    }

    @Test
    fun `accidentals hold for one bar, at one octave, on one staff`() {
        val bar = BarAccidentals().apply { reset(C) }
        assertEquals(Accidental.SHARP, bar.accidental(true, Spelling.step(61, C), 1))
        assertEquals(Accidental.SHARP, bar.accidental(true, Spelling.step(73, C), 1))    // C♯5: another octave
        assertEquals(Accidental.SHARP, bar.accidental(false, Spelling.step(61, C), 1))   // the bass staff keeps its own
        bar.reset(C)                                                                     // the next bar
        assertEquals(Accidental.SHARP, bar.accidental(true, Spelling.step(61, C), 1))
    }

    @Test
    fun `flat keys write chromatic notes as flats, sharp keys and no key as sharps`() {
        assertEquals("D♭4", Spelling.name(61, E_FLAT))
        assertEquals("G♭4", Spelling.name(66, E_FLAT))
        assertEquals(listOf(Accidental.FLAT), bar(E_FLAT, 66))
        assertEquals("A♯4", Spelling.name(70, G))
        assertEquals("A♯4", Spelling.name(70, C))
        assertEquals("D♯4", Spelling.name(63, C))
    }

    @Test
    fun `seven sharps and seven flats spell B-sharp and C-flat, naturals where the key alters`() {
        assertEquals("B♯3", Spelling.name(60, 7))
        assertEquals(StaffPitch.diatonic(59), Spelling.step(60, 7))    // on B3's space
        assertEquals("C♭4", Spelling.name(59, -7))
        assertEquals(StaffPitch.diatonic(60), Spelling.step(59, -7))   // on middle C's line
        assertEquals("D♮4", Spelling.name(62, 7))
        assertEquals("F♮4", Spelling.name(65, -7))
    }

    @Test
    fun `every key writes every pitch as a letter within a semitone of it`() {
        val naturals = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        for (sharps in -7..7) {
            for (key in 21..108) {
                val step = Spelling.step(key, sharps)
                val natural = Math.floorDiv(step, 7) * 12 + naturals[Math.floorMod(step, 7)]
                assertEquals("key $key in $sharps", key, natural + Spelling.alteration(key, sharps))
                assertEquals(Spelling.letter(key, sharps), Math.floorMod(step, 7))
            }
        }
    }

    @Test
    fun `without a key signature black keys are sharps on the step below, as the staff drew them`() {
        for (key in 24..107) {
            assertEquals("key $key", StaffPitch.diatonic(key), Spelling.step(key, 0))
            assertEquals("key $key", if (StaffPitch.isSharp(key)) 1 else 0, Spelling.alteration(key, 0))
        }
    }

    @Test
    fun `key signatures alter the right letters`() {
        assertEquals(listOf(1, 0, 0, 1, 0, 0, 0), (0..6).map { Spelling.keyAlteration(it, 2) })     // D major: F♯ C♯
        assertEquals(listOf(0, 0, -1, 0, 0, -1, -1), (0..6).map { Spelling.keyAlteration(it, -3) }) // E♭ major: B♭ E♭ A♭
        assertEquals(List(7) { 1 }, (0..6).map { Spelling.keyAlteration(it, 7) })
    }
}

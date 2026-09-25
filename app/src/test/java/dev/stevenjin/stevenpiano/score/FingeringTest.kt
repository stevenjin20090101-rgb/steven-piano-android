// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.score

import dev.stevenjin.stevenpiano.midi.NoteList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FingeringTest {
    /** Notes [ms] apart (each lasting as long), or struck together where a list holds several keys. */
    private fun notes(vararg onsets: List<Int>, ms: Long = 250): NoteList {
        val starts = ArrayList<Long>()
        val keys = ArrayList<Int>()
        onsets.forEachIndexed { k, chord -> chord.forEach { starts += k * ms * 1_000; keys += it } }
        val n = starts.size
        return NoteList(
            LongArray(n) { starts[it] },
            LongArray(n) { starts[it] + ms * 1_000 },
            ByteArray(n) { keys[it].toByte() },
            ByteArray(n) { 80 },
            ByteArray(n),
        )
    }

    private fun line(vararg keys: Int) = notes(*keys.map { listOf(it) }.toTypedArray())

    private fun fingers(notes: NoteList, hand: Byte, transpose: Int = 0): List<Int> =
        Fingering.assign(notes, ByteArray(notes.size) { hand }, transpose).map { it.toInt() }

    private val C_MAJOR_UP = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12)

    @Test
    fun `a C major scale in the right hand goes 1-2-3-1-2-3-4-5`() {
        val scale = line(*C_MAJOR_UP.map { 60 + it }.toIntArray())
        assertEquals(listOf(1, 2, 3, 1, 2, 3, 4, 5), fingers(scale, Hands.RIGHT))
    }

    @Test
    fun `a C major scale in the left hand goes 5-4-3-2-1-3-2-1`() {
        val scale = line(*C_MAJOR_UP.map { 48 + it }.toIntArray())
        assertEquals(listOf(5, 4, 3, 2, 1, 3, 2, 1), fingers(scale, Hands.LEFT))
    }

    @Test
    fun `an octave takes thumb and little finger in either hand`() {
        val octave = notes(listOf(60, 72))
        val right = fingers(octave, Hands.RIGHT)
        assertEquals(listOf(1, 5), right)
        val left = fingers(notes(listOf(36, 48)), Hands.LEFT)
        assertEquals(listOf(5, 1), left)      // C2 the little finger, C3 the thumb
        // Octaves in a row keep the shape.
        val octaves = notes(listOf(60, 72), listOf(62, 74), listOf(64, 76))
        assertEquals(listOf(1, 5, 1, 5, 1, 5), fingers(octaves, Hands.RIGHT))
    }

    @Test
    fun `a thumb on a black key is avoided when another finger will do`() {
        // F♯4 G♯4 A♯4: 1-2-3 would put the thumb on F♯.
        val blacks = fingers(line(66, 68, 70), Hands.RIGHT)
        assertNotEquals(1, blacks[0])
        assertTrue(blacks.none { it == 1 })
        // A black key alone is not a thumb's either.
        assertNotEquals(1, fingers(line(61), Hands.RIGHT)[0])
        // Transposed up a semitone, C D E becomes C♯ D♯ F: the thumb leaves C♯ too.
        assertNotEquals(1, fingers(line(60, 62, 64), Hands.RIGHT, transpose = 1)[0])
        assertEquals(1, fingers(line(60, 62, 64), Hands.RIGHT)[0])
    }

    @Test
    fun `repeated notes keep their finger`() {
        val repeats = fingers(line(64, 64, 64, 64, 65, 67), Hands.RIGHT)
        assertEquals(1, repeats.subList(0, 4).toSet().size)
        assertEquals(listOf(1, 1, 1, 1, 2, 3), repeats)
        val left = fingers(line(43, 43, 43), Hands.LEFT)
        assertEquals(1, left.toSet().size)
    }

    @Test
    fun `a five-note chord spreads from thumb to little finger`() {
        val chord = notes(listOf(60, 64, 67, 71, 74))
        assertEquals(listOf(1, 2, 3, 4, 5), fingers(chord, Hands.RIGHT))
        assertEquals(listOf(5, 4, 3, 2, 1), fingers(notes(listOf(36, 40, 43, 47, 50)), Hands.LEFT))
        // Six keys in one hand: the ends are thumb and little finger, and no finger is used twice.
        val six = fingers(notes(listOf(48, 52, 55, 60, 64, 67)), Hands.RIGHT)
        assertEquals(1, six.first())
        assertEquals(5, six.last())
        assertEquals(six.filter { it != 0 }.size, six.filter { it != 0 }.toSet().size)
    }

    @Test
    fun `a triad takes 1-3-5 and the Alberti bass 5-1-3-1`() {
        assertEquals(listOf(1, 3, 5), fingers(notes(listOf(60, 64, 67)), Hands.RIGHT))
        assertEquals(listOf(5, 1, 3, 1, 5, 1, 3, 1), fingers(line(48, 55, 52, 55, 48, 55, 52, 55), Hands.LEFT))
    }

    @Test
    fun `each hand is fingered on its own, and notes the piano can't play get none`() {
        val both = notes(listOf(48, 72), listOf(50, 74), listOf(52, 76))
        val hands = ByteArray(both.size) { if (both.note(it) < 60) Hands.LEFT else Hands.RIGHT }
        val f = Fingering.assign(both, hands).map { it.toInt() }
        for (i in 0 until both.size) assertTrue(f[i] in 1..5)
        val low = line(12, 60)
        assertEquals(listOf(0, 1), Fingering.assign(low, ByteArray(2) { Hands.RIGHT }, 0, fold = false).map { it.toInt() })
    }

    @Test
    fun `stretches cost more past their comfortable span, and only the thumb may cross`() {
        assertEquals(0f, Fingering.stretch(1, 2, 2), 0f)
        assertEquals(2f, Fingering.stretch(1, 5, 12), 0f)                    // two semitones past 1-5's relaxed ten
        assertTrue(Fingering.stretch(1, 5, 16) > Fingering.stretch(1, 5, 14) + 3f)
        assertEquals(Fingering.CROSS_OK, Fingering.stretch(1, 3, -1), 0f)    // the thumb passing under 3
        assertTrue(Fingering.stretch(2, 3, -1) >= Fingering.CROSS_BAD)        // 3 over 2
        assertTrue(Fingering.stretch(1, 5, -1) >= Fingering.CROSS_BAD)        // the little finger over the thumb
    }
}

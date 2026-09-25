// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import dev.stevenjin.stevenpiano.midi.NoteList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class RollCardTest {
    private class Note(val key: Int, val startMs: Long, val endMs: Long, val velocity: Int = 100)

    private fun notes(vararg notes: Note): NoteList {
        val sorted = notes.sortedBy { it.startMs }
        return NoteList(
            LongArray(sorted.size) { sorted[it].startMs * 1000 },
            LongArray(sorted.size) { sorted[it].endMs * 1000 },
            ByteArray(sorted.size) { sorted[it].key.toByte() },
            ByteArray(sorted.size) { sorted[it].velocity.toByte() },
            ByteArray(sorted.size),
        )
    }

    private fun ByteArray.at(x: Int, y: Int): Int = this[y * RollCard.SIZE + x].toInt() and 0xFF

    private val piece = arrayOf(
        Note(60, 0, 480), Note(64, 480, 960, 90), Note(67, 960, 1_440, 80), Note(72, 1_440, 3_000, 127),
        Note(36, 0, 3_000, 60), Note(43, 3_000, 6_000, 70), Note(107, 12_000, 12_050, 30), Note(24, 19_900, 25_000),
    )

    @Test
    fun `the same notes give the same bytes`() {
        val a = RollCard.render(notes(*piece))
        val b = RollCard.render(notes(*piece))
        assertEquals(RollCard.SIZE * RollCard.SIZE, a.size)
        assertArrayEquals(a, b)
        val digest = { bytes: ByteArray -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
        assertEquals(digest(a), digest(RollCard.render(notes(*piece.reversedArray()))))
    }

    @Test
    fun `a note in lane 0 marks the lane's columns from the bottom row up`() {
        val card = RollCard.render(notes(Note(24, 0, 1_000, 127)))
        // One second of twenty is 12.8 rows: the bottom row, 255, up to row 243.
        assertEquals(255, card.at(0, 255))
        assertEquals(255, card.at(1, 243))
        assertEquals(0, card.at(0, 242))          // above the note: paper
        assertEquals(0, card.at(2, 250))          // the lane's last column stays paper
        assertEquals(0, card.at(3, 250))          // lane 1 is empty
        assertEquals(2 * 13, card.count { it != 0.toByte() })
    }

    @Test
    fun `lanes run C1 to B7 across the card, and notes beyond fold in by octaves`() {
        assertEquals(0, RollCard.laneStart(0))
        assertEquals(RollCard.SIZE, RollCard.laneStart(84))
        val top = RollCard.render(notes(Note(107, 0, 1_000)))
        assertTrue(top.at(RollCard.laneStart(83), 255) > 0)
        assertArrayEquals(RollCard.render(notes(Note(24, 0, 500))), RollCard.render(notes(Note(12, 0, 500))))
    }

    @Test
    fun `the card starts at the first note and shows twenty seconds`() {
        val late = RollCard.render(notes(Note(60, 5_000, 5_500), Note(62, 26_000, 27_000)))
        val lane = RollCard.laneStart(60 - 24)
        assertTrue(late.at(lane, 255) > 0)                                   // the first note sits on the bottom row
        val onlyFirst = RollCard.render(notes(Note(60, 5_000, 5_500)))
        assertArrayEquals(onlyFirst, late)                                   // 21 s after it: off the card
        assertEquals(RollCard.SIZE - 1, RollCard.rowAt(0))
        assertEquals(0, RollCard.rowAt(RollCard.WINDOW_MICROS))
    }

    @Test
    fun `a short note still shows, and a soft note is fainter than a loud one`() {
        val card = RollCard.render(notes(Note(60, 0, 1, velocity = 0), Note(62, 0, 1, velocity = 127)))
        val soft = card.at(RollCard.laneStart(36), 255)
        val loud = card.at(RollCard.laneStart(38), 255)
        assertEquals(120, soft)
        assertEquals(255, loud)
        assertTrue(card.at(RollCard.laneStart(36), 254) > 0)   // two rows at least
    }

    @Test
    fun `no notes, blank paper`() {
        assertTrue(RollCard.render(NoteList.Empty).all { it == 0.toByte() })
    }
}

// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import dev.stevenjin.stevenpiano.midi.NoteList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What one frame of the roll or the score may look at (the v1.2 audit, F13). */
class CanvasBudgetTest {
    /** [count] notes, one every 10 ms, 5 ms long; the first held to the end ([heldToEnd]) as an unreleased note is. */
    private fun notes(count: Int, heldToEnd: Boolean): NoteList {
        val starts = LongArray(count) { it * 10_000L }
        val ends = LongArray(count) { starts[it] + 5_000L }
        if (heldToEnd) ends[0] = starts.last() + 5_000L
        return NoteList(starts, ends, ByteArray(count) { 60 }, ByteArray(count) { 80 }, ByteArray(count))
    }

    @Test
    fun `one unreleased note no longer makes every frame scan from the first note`() {
        val list = notes(1_000_000, heldToEnd = true)   // 10,000 s of notes; the first lasts all of it
        val windowStart = 9_000_000_000L
        val first = list.scanStart(windowStart)
        assertEquals(list.firstStartingAtOrAfter(windowStart - MAX_BACKOFF_MICROS), first)
        assertEquals("30 s before the window, not the first note", 897_000, first)
        assertTrue(list.maxDurationMicros > MAX_BACKOFF_MICROS)
    }

    @Test
    fun `short notes back off by the longest note, as before`() {
        val list = notes(1_000, heldToEnd = false)
        assertEquals(list.firstStartingAtOrAfter(5_000_000L - 5_000L), list.scanStart(5_000_000L))
    }

    @Test
    fun `a frame draws at most 4,000 notes`() {
        assertEquals(4_000, MAX_NOTE_DRAWS)
        assertEquals(30_000_000L, MAX_BACKOFF_MICROS)
    }

    @Test
    fun `a score page draws at most 4,000 rests, numerals, beams and ties a system`() {
        assertEquals(0 until 4_000, (0 until 5_000_000).capped())
        assertEquals(10 until 4_010, (10 until 20_000).capped())
        assertEquals(5 until 17, (5 until 17).capped())
        assertTrue((3 until 3).capped().isEmpty())
        assertTrue(IntRange.EMPTY.capped().isEmpty())
    }

    @Test
    fun `the waterfall draws chord names clear of each other, at most 64 a frame (the v1_3 delta audit, L3)`() {
        // 20,000 names a quarter of a millisecond apart, each backing 24 dp tall at 120 dp a second: 200 ms.
        val starts = LongArray(20_000) { it * 250L }
        val kept = clearNames(starts) { 200_000L }
        assertEquals((0 until 20_000 step 800).toList(), kept.toList())
        for (k in 1 until kept.size) assertTrue(starts[kept[k]] - starts[kept[k - 1]] >= 200_000L)
        // Names far enough apart are all kept, and the choice never depends on where drawing starts.
        assertEquals(listOf(0, 1, 2), clearNames(longArrayOf(0, 300_000, 600_000)) { 200_000L }.toList())
        assertEquals(listOf(0, 2), clearNames(longArrayOf(0, 150_000, 250_000)) { 200_000L }.toList())
        assertEquals(64, MAX_CHORD_DRAWS)
    }
}

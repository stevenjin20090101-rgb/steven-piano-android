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
}

// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistTest {
    @Test
    fun `a queue starts at the tapped piece and moves within bounds`() {
        val list = Playlist.startingAt(20, listOf(10, 20, 30))
        assertEquals(20L, list.current)
        assertEquals(30L, list.next().current)
        assertEquals(30L, list.next().next().current)
        assertEquals(10L, list.previous().current)
        assertEquals(10L, list.previous().previous().current)
        assertFalse(list.next().hasNext)
        assertFalse(list.previous().hasPrevious)
    }

    @Test
    fun `a piece outside the queue plays on its own`() {
        val list = Playlist.startingAt(99, listOf(10, 20))
        assertEquals(listOf(99L), list.pieceIds)
        assertFalse(list.hasNext)
    }

    @Test
    fun `previous restarts after 3 seconds or at the top of the queue`() {
        val list = Playlist.startingAt(20, listOf(10, 20))
        assertFalse(list.previousRestarts(3_000_000L))
        assertTrue(list.previousRestarts(3_000_001L))
        assertTrue(list.previous().previousRestarts(0L))
        assertEquals(null, Playlist.Empty.current)
    }
}

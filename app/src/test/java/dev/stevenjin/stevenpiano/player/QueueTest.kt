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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueTest {
    private val ids = listOf(1L, 2L, 3L, 4L, 5L, 6L)

    private val Queue.pieces: List<Long> get() = entries.map { it.pieceId }
    private val Queue.playing: Long? get() = current?.pieceId

    private fun Queue.uidOf(pieceId: Long): Long = entries.first { it.pieceId == pieceId }.uid

    // The three cases v1.1's PlaylistTest held, on the queue that replaced it.

    @Test
    fun `a queue starts at the tapped piece and moves within bounds`() {
        val list = Queue.startingAt(20, listOf(10, 20, 30))
        assertEquals(20L, list.playing)
        assertEquals(30L, list.next().playing)
        assertEquals(30L, list.next().next().playing)
        assertEquals(10L, list.previous().playing)
        assertEquals(10L, list.previous().previous().playing)
        assertFalse(list.next().hasNext)
        assertFalse(list.previous().hasPrevious)
    }

    @Test
    fun `a piece outside the queue plays on its own`() {
        val list = Queue.startingAt(99, listOf(10, 20))
        assertEquals(listOf(99L), list.pieces)
        assertFalse(list.hasNext)
    }

    @Test
    fun `previous restarts after 3 seconds or at the top of the queue`() {
        val list = Queue.startingAt(20, listOf(10, 20))
        assertFalse(list.previousRestarts(3_000_000L))
        assertTrue(list.previousRestarts(3_000_001L))
        assertTrue(list.previous().previousRestarts(0L))
        assertEquals(null, Queue.Empty.current)
    }

    // v1.2: repeat and Up next (shuffle was removed in v1.26 — M61: the order is always the one given).

    @Test
    fun `repeat all wraps both ways, the other modes stop at the ends`() {
        val last = Queue.startingAt(6, ids)
        assertFalse(last.hasNext)
        assertEquals(1L, last.withRepeat(RepeatMode.ALL).next().playing)
        assertEquals(6L, last.withRepeat(RepeatMode.ONE).next().playing)
        val first = Queue.startingAt(1, ids).withRepeat(RepeatMode.ALL)
        assertTrue(first.hasPrevious)
        assertEquals(6L, first.previous().playing)
        assertFalse(Queue.startingAt(1, listOf(1L)).withRepeat(RepeatMode.ALL).hasPrevious)
    }

    @Test
    fun `at the end of a piece - the next one, the top again, the same one, or nothing`() {
        val middle = Queue.startingAt(3, ids)
        assertEquals(4L, middle.afterEnd()?.playing)
        val last = Queue.startingAt(6, ids)
        assertNull(last.afterEnd())
        assertEquals(1L, last.withRepeat(RepeatMode.ALL).afterEnd()?.playing)
        val one = middle.withRepeat(RepeatMode.ONE)
        assertSame(one, one.afterEnd())
        assertNull(Queue.Empty.withRepeat(RepeatMode.ONE).afterEnd())
    }

    @Test
    fun `the repeat button cycles off, all, one`() {
        assertEquals(RepeatMode.ALL, RepeatMode.OFF.cycled())
        assertEquals(RepeatMode.ONE, RepeatMode.ALL.cycled())
        assertEquals(RepeatMode.OFF, RepeatMode.ONE.cycled())
    }

    @Test
    fun `play next goes right after the current piece, add to queue at the end`() {
        val q = Queue.startingAt(2, listOf(1L, 2L, 3L)).playNext(listOf(8L, 9L)).addToQueue(listOf(7L))
        assertEquals(listOf(1L, 2L, 8L, 9L, 3L, 7L), q.pieces)
        assertEquals(2L, q.playing)
        assertEquals(listOf(8L, 9L, 3L, 7L), q.upNext.map { it.pieceId })
    }

    @Test
    fun `every entry has its own uid, across queues too`() {
        val first = Queue.startingAt(1, listOf(1L, 1L, 2L)).addToQueue(listOf(1L))
        val second = Queue.startingAt(2, listOf(1L, 2L), first)
        val uids = (first.entries + second.entries).map { it.uid }
        assertEquals(uids.size, uids.toSet().size)
    }

    @Test
    fun `remove takes an entry out, but never the one playing`() {
        val q = Queue.startingAt(3, ids)
        val without4 = q.remove(q.uidOf(4))
        assertEquals(listOf(1L, 2L, 3L, 5L, 6L), without4.pieces)
        assertSame(q, q.remove(q.uidOf(3)))
        val without1 = q.remove(q.uidOf(1))
        assertEquals(1, without1.index)
        assertEquals(3L, without1.playing)
    }

    @Test
    fun `move rearranges up next only`() {
        val q = Queue.startingAt(2, listOf(1L, 2L, 3L, 4L, 5L))
        assertEquals(listOf(1L, 2L, 5L, 3L, 4L), q.move(q.uidOf(5), 0).pieces)
        assertEquals(listOf(1L, 2L, 4L, 5L, 3L), q.move(q.uidOf(3), 99).pieces)
        assertSame(q, q.move(q.uidOf(1), 0))   // behind the current piece: not up next
    }

    @Test
    fun `clear up next keeps the piece playing, and what was cleared never comes back`() {
        val q = Queue.startingAt(2, listOf(1L, 2L, 3L, 4L))
        val cleared = q.clearUpNext()
        assertEquals(listOf(1L, 2L), cleared.pieces)
        assertEquals(2L, cleared.playing)
        assertFalse(cleared.hasNext)
    }

    @Test
    fun `skipping to an entry keeps the ones skipped behind it`() {
        val q = Queue.startingAt(1, listOf(1L, 2L, 3L, 4L))
        val skipped = q.skipTo(q.uidOf(4))
        assertEquals(4L, skipped.playing)
        assertEquals(3L, skipped.previous().playing)
        assertSame(q, q.skipTo(12345L))
    }

    @Test
    fun `a new queue keeps the repeat mode and the order given, the piece tapped where it stands (v1_26 M61)`() {
        val modes = Queue.Empty.withRepeat(RepeatMode.ALL)
        val q = Queue.startingAt(4, ids, modes)
        assertEquals(4L, q.playing)
        assertEquals(3, q.index)
        assertEquals("the order given, never shuffled", ids, q.pieces)
        assertEquals(RepeatMode.ALL, q.repeat)
    }

    @Test
    fun `play all runs from the top in the order given, every time (v1_26 M61)`() {
        val inOrder = Queue.all(ids)
        assertEquals(ids, inOrder.pieces)
        assertEquals(1L, inOrder.playing)
        assertEquals(0, inOrder.index)
        assertEquals("again: the same order", ids, Queue.all(ids, inOrder).pieces)
        assertNull(Queue.all(emptyList()).current)
        // Up next as it was given, whatever is added and however it moves on.
        val moved = inOrder.next().next().addToQueue(listOf(9L)).playNext(listOf(8L))
        assertEquals(listOf(1L, 2L, 3L, 8L, 4L, 5L, 6L, 9L), moved.pieces)
        assertEquals(listOf(8L, 4L, 5L, 6L, 9L), moved.snapshot().upNextIds)
    }

    @Test
    fun `the snapshot agrees with the queue about what follows`() {
        val cases = listOf(Queue.Empty, Queue.startingAt(3, ids), Queue.startingAt(6, ids), Queue.startingAt(1, listOf(1L)))
        for (q in cases) {
            for (mode in RepeatMode.entries) {
                val withMode = q.withRepeat(mode)
                val snapshot = withMode.snapshot()
                assertEquals("$mode $q", withMode.afterEnd() != null, snapshot.advancesAtEnd)
                assertEquals("$mode $q", withMode.hasNext, snapshot.hasNext)
                assertEquals(withMode.upNext.map { it.pieceId }, snapshot.upNextIds)
            }
        }
    }

    @Test
    fun `the media session gets at most 50 entries, starting a few before the current one`() {
        fun window(size: Int, index: Int) = QueueSnapshot(List(size) { it.toLong() }, List(size) { it.toLong() }, index).window()
        assertEquals(55..104, window(120, 60))
        assertEquals(0..49, window(120, 0))
        assertEquals(70..119, window(120, 118))
        assertEquals(0..9, window(10, 4))
        assertTrue(window(0, -1).isEmpty())
    }
}

// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.midi.NoteList
import dev.stevenjin.stevenpiano.midi.SmfBuilder
import dev.stevenjin.stevenpiano.midi.SmfParser
import dev.stevenjin.stevenpiano.score.ScoreLayout
import dev.stevenjin.stevenpiano.score.ScoreLayoutEngine
import dev.stevenjin.stevenpiano.score.ScoreMetrics
import dev.stevenjin.stevenpiano.score.ScoreWidth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** The score panel's layout, apart from Compose (the v1.3 delta audit, H1 and P2). */
class ScoreLaidTest {
    private fun layoutOf(keys: List<Int>): Pair<NoteList, ScoreLayout> {
        val piece = SmfParser.parse(
            SmfBuilder(format = 0).track { keys.forEachIndexed { k, key -> noteOn(k * 480L, key); noteOff(k * 480L + 480, key) } }.build(),
        )
        val metrics = ScoreMetrics.forPanel(ScoreWidth.COMPACT, 758f, 800f, 2f, 14.16f, 32.88f)
        val layout = ScoreLayoutEngine.layout(
            piece.notes, IntArray(piece.notes.size) { KeyMap.map(piece.notes.note(it), 0, true) }, piece.tempoMap,
            piece.barStartsMicros, piece.keySignatures, metrics, piece.timeSignatures,
        )
        return piece.notes to layout
    }

    @Test
    fun `a layout is drawn only with the notes it was made for`() {
        val (longNotes, longLayout) = layoutOf(List(12) { 60 + it })
        val (shortNotes, _) = layoutOf(listOf(72, 74))
        // For a frame after the change the state still holds the long piece's layout: it is not shown
        // with the short piece's notes (its overlay would read heads 0..11 against two notes).
        val stale: Laid? = Laid(longNotes, longLayout)
        assertNull(stale.madeFor(shortNotes))
        assertSame(stale, stale.madeFor(longNotes))
        assertSame(longNotes, stale.madeFor(longNotes)!!.notes)
        assertNull((null as Laid?).madeFor(longNotes))
        // The same notes by value but another list is another piece.
        val (again, _) = layoutOf(List(12) { 60 + it })
        assertNull(stale.madeFor(again))
    }

    @Test
    fun `a layout that runs out of memory or fails leaves the panel saying the score is too large`() = runBlocking {
        val (notes, layout) = layoutOf(listOf(60, 64, 67))
        val fine = layOut(notes, Dispatchers.Default) { layout }
        assertSame(layout, fine.layout)
        val heap = layOut(notes, Dispatchers.Default) { throw OutOfMemoryError("Java heap space") }
        assertSame(notes, heap.notes)
        assertNull(heap.layout)
        val index = layOut(notes, Dispatchers.Default) { throw IndexOutOfBoundsException("Index 12 out of bounds for length 2") }
        assertNull(index.layout)
        assertEquals("This score is too large to show.", SCORE_TOO_LARGE)
    }

    @Test
    fun `a layout replaced by a newer one ends in cancellation, never in the too-large message`() = runBlocking {
        val (notes, _) = layoutOf(listOf(60))
        val running = CountDownLatch(1)
        var result: Laid? = null
        val job = launch(Dispatchers.Default) {
            result = layOut(notes, Dispatchers.Default) {
                running.countDown()
                Thread.sleep(200)   // the engine at work, not looking up
                throw OutOfMemoryError("Java heap space")
            }
        }
        running.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertNull(result)
    }

    @Test
    fun `a replaced layout stops at its next checkpoint (the v1_3 delta audit, L1)`() = runBlocking {
        val (notes, _) = layoutOf(listOf(60))
        val running = CountDownLatch(1)
        val passes = AtomicInteger()
        val job = launch(Dispatchers.Default) {
            layOut(notes, Dispatchers.Default) { checkpoint ->
                running.countDown()
                repeat(400) {
                    Thread.sleep(5)   // one of the engine's passes
                    passes.incrementAndGet()
                    checkpoint()
                }
                error("the layout ran to its end though it was replaced")
            }
        }
        running.await()
        job.cancelAndJoin()
        val stopped = passes.get()
        assertTrue("stopped after $stopped of 400 passes", stopped < 400)
        Thread.sleep(50)
        assertEquals(stopped, passes.get())
        assertEquals(150L, RELAYOUT_SETTLE_MS)
    }
}

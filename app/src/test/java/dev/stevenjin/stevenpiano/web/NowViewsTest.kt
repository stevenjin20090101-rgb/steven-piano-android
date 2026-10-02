// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import dev.stevenjin.stevenpiano.midi.MidiPiece
import dev.stevenjin.stevenpiano.score.ScoreFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The panel's views on the tablet (v1.13 — M32): one layout at a time and shared, sizes on a grid within bounds, the
 * 1.5 s wait then 202, the 8 s deadline, six fresh layouts a half minute, and a change of piece cancelling it all.
 */
class NowViewsTest {
    private val piece = ScoreFixtures.piece { for (k in 0 until 16) note(k * 480L, 60 + k % 12, 480) }
    private val other = ScoreFixtures.piece { for (k in 0 until 8) note(k * 480L, 72 - k, 240) }

    private fun source(p: MidiPiece) = NowSource(p.notes, p.tempoMap, p.barStartsMicros, p.keySignatures, p.timeSignatures, p.durationMicros, 0, true, null, null, null)

    @Volatile
    private var current: NowSource? = source(piece)
    private var now = 1_000_000L
    private val executor = Executors.newSingleThreadExecutor()
    private val calls = AtomicInteger()

    @Volatile
    private var gate: CountDownLatch? = null

    @Volatile
    private var endless = false
    private val opened = mutableListOf<NowViews>()

    @After
    fun tearDown() {
        gate?.countDown()
        endless = false
        opened.forEach { it.close() }
        executor.shutdownNow()
    }

    /** Views whose layouts can be held at a gate (checking their checkpoint meanwhile) or made to run on and on. */
    private fun views(waitMs: Long = NowViews.SCORE_WAIT_MS, deadlineMs: Long = NowViews.LAYOUT_DEADLINE_MS) =
        NowViews({ current }, executor.asCoroutineDispatcher(), { now }, waitMs, deadlineMs, seed = 100) { src, metrics, checkpoint ->
            calls.incrementAndGet()
            gate?.let { latch -> while (!latch.await(5, TimeUnit.MILLISECONDS)) checkpoint() }
            while (endless) {
                checkpoint()
                Thread.sleep(5)
            }
            NowViews.layoutFor(src, metrics, checkpoint)
        }.also { opened += it }

    private fun ready(answer: NowAnswer): ByteArray {
        assertTrue("ready: $answer", answer is NowAnswer.Ready)
        return (answer as NowAnswer.Ready).bytes
    }

    @Test
    fun `requests for one size share one layout, on a 16 px grid held to its bounds`() = runBlocking<Unit> {
        val v = views()
        gate = CountDownLatch(1)
        val both = listOf(async(Dispatchers.Default) { v.score(null, 803, 611) }, async(Dispatchers.Default) { v.score(null, 815, 620) })
        while (calls.get() == 0) Thread.sleep(5)
        gate!!.countDown()
        val (a, b) = both.awaitAll().map { ready(it) }
        assertEquals("one layout for both", 1, calls.get())
        assertTrue(a.contentEquals(b))
        val index = NowWire.index(a)
        assertEquals(800f, index.pageWidth)
        assertEquals(608f, index.pageHeight)
        ready(v.score(index.rev, 800, 608))
        assertEquals("the same size again: no new layout", 1, calls.get())
        assertEquals(280f, NowWire.index(ready(v.score(null, 100, 100))).pageWidth)
        assertEquals(2000f, NowWire.index(ready(v.score(null, 5000, 9000))).pageHeight)
        assertEquals(3, calls.get())
        // The layouts' pages come only from a layout made, and the oldest of three is gone.
        assertTrue(v.page(index.layoutId, 0) is NowAnswer.Stale)
        val last = NowWire.index(ready(v.score(null, 5000, 9000)))
        assertEquals(0, NowWire.page(ready(v.page(last.layoutId, 0))).page)
        assertTrue("past the last page", v.page(last.layoutId, 99) is NowAnswer.Stale)
    }

    @Test
    fun `a request waits a moment then answers 202, a layout past its deadline is too large and stays so`() = runBlocking<Unit> {
        val v = views(waitMs = 50)
        gate = CountDownLatch(1)
        assertTrue(v.score(null, 800, 600) is NowAnswer.Working)
        gate!!.countDown()
        var answer: NowAnswer = NowAnswer.Working(0)
        repeat(200) { if (answer !is NowAnswer.Ready) answer = v.score(null, 800, 600).also { Thread.sleep(10) } }
        ready(answer)
        assertEquals("the retry found the same layout", 1, calls.get())

        val slow = views(deadlineMs = 100)
        endless = true
        assertEquals(NowAnswer.TooLarge, slow.score(null, 640, 480))
        endless = false
        val before = calls.get()
        assertEquals("remembered: no second try", NowAnswer.TooLarge, slow.score(null, 640, 480))
        assertEquals(before, calls.get())
    }

    @Test
    fun `at most six fresh layouts every half minute`() = runBlocking<Unit> {
        val v = views()
        for (k in 0 until NowViews.MAX_FRESH) ready(v.score(null, 400 + 32 * k, 600))
        val busy = v.score(null, 1200, 600)
        assertTrue("the seventh waits: $busy", busy is NowAnswer.Busy && busy.retryAfterMs in 1..NowViews.RATE_WINDOW_MS)
        assertEquals(NowViews.MAX_FRESH, calls.get())
        now += NowViews.RATE_WINDOW_MS
        ready(v.score(null, 1200, 600))
    }

    @Test
    fun `a change of piece stops the layout running, moves the revision on, and turns old requests away`() = runBlocking<Unit> {
        val v = views()
        val first = v.revision()!!
        val notes = NowWire.notes(ready(v.notes(first)))
        assertEquals(first, notes.rev)
        assertEquals(piece.notes.size, notes.n)
        gate = CountDownLatch(1)
        val waiting = async(Dispatchers.Default) { v.score(first, 800, 600) }
        while (calls.get() == 0) Thread.sleep(5)
        current = source(other)
        assertEquals("the layout stopped at its checkpoint", NowAnswer.Stale, waiting.await())
        val second = v.revision()!!
        assertNotEquals(first, second)
        assertEquals(NowAnswer.Stale, v.notes(first))
        assertEquals(NowAnswer.Stale, v.score(first, 800, 600))
        assertEquals(other.notes.size, NowWire.notes(ready(v.notes(null))).n)
        gate!!.countDown()
        current = null
        assertEquals(NowAnswer.NoPiece, v.notes(null))
        assertEquals(NowAnswer.NoPiece, v.score(null, 800, 600))
    }
}

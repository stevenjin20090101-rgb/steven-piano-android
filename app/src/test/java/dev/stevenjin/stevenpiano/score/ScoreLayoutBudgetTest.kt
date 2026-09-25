// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.score

import dev.stevenjin.stevenpiano.midi.Bars
import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.midi.NoteList
import dev.stevenjin.stevenpiano.midi.SmfBuilder
import dev.stevenjin.stevenpiano.midi.SmfParser
import dev.stevenjin.stevenpiano.midi.TempoMap
import dev.stevenjin.stevenpiano.midi.TimeSignature
import dev.stevenjin.stevenpiano.score.ScoreFixtures.layout
import dev.stevenjin.stevenpiano.score.ScoreFixtures.piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a crafted file may cost the score's layout (the v1.3 delta audit, H1): every part bounded, never an exception. */
class ScoreLayoutBudgetTest {
    private val mb = 1024L * 1024

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        repeat(3) {
            System.gc()
            Thread.sleep(20)
        }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /**
     * [count] notes built straight into the score's inputs (a file this size takes a while to write
     * and parse), in 4/4 at 480 ticks a quarter: note k from [start] (k) for [length] (k) ticks on
     * [key] (k). Starts must rise with k.
     */
    private class Synthetic(count: Int, start: (Int) -> Long, length: (Int) -> Long, key: (Int) -> Int) {
        val tempo = TempoMap.constant(480)
        val notes = NoteList(
            LongArray(count) { tempo.tickToMicros(start(it)) },
            LongArray(count) { tempo.tickToMicros(start(it) + length(it)) },
            ByteArray(count) { key(it).toByte() },
            ByteArray(count) { 80 },
            ByteArray(count),
        )
        val bars = Bars.starts(tempo, listOf(TimeSignature.Common), notes.endMicros.max())

        fun layout(): ScoreLayout = ScoreLayoutEngine.layout(
            notes,
            IntArray(notes.size) { KeyMap.map(notes.note(it), 0, true) },
            tempo,
            bars,
            emptyList(),
            ScoreFixtures.metrics(),
            listOf(TimeSignature.Common),
        )
    }

    @Test
    fun `the audit's file of one-tick notes in bars of 255 whole notes stops writing rests at its budget`() {
        // 10,000 bars of 255/1 at 4 ticks a quarter (a tick is a sixteenth), eight one-tick notes a
        // bar, at 4 µs a quarter so that it lasts 41 s: it asked for 5.19 million rests (320 MB).
        val bytes = SmfBuilder(format = 1, division = 4).track {
            tempo(0, 4)
            timeSignature(0, 255, 1)
        }.track {
            for (bar in 0 until 10_000) {
                for (k in 0 until 8) {
                    val tick = bar * 4_080L + k * 510L
                    noteOn(tick, 60 + k)
                    noteOff(tick + 1, 60 + k)
                }
            }
        }.build()
        assertTrue("${bytes.size} bytes", bytes.size < 1_000_000)
        val piece = SmfParser.parse(bytes)
        assertEquals(80_000, piece.noteCount)
        assertEquals(10_000, piece.barStartsMicros.size)
        val before = usedHeap()
        val score = layout(piece)
        val grown = usedHeap() - before
        println("Budget: the audit's rest file lays out holding ${grown / mb} MB, ${score.rests.size} rests")
        assertTrue(score.quantized)
        assertEquals(160_000, ScoreLayoutEngine.restBudget(piece.noteCount))
        assertEquals(ScoreLayoutEngine.restBudget(piece.noteCount), score.rests.size)
        assertTrue("the layout holds ${grown / mb} MB", grown < 64 * mb)
        // What is written is whole: every rest on its system, in bar order, the notes all placed.
        for (k in 0 until score.rests.size) assertTrue(k in score.rests.inSystem(score.rests.system[k]))
        assertTrue((0 until score.noteCount).all { score.system[it] >= 0 })
        assertEquals(score.noteCount, score.headCount)
    }

    @Test
    fun `a piece of ten thousand tiny bars and one-tick notes lays out, with nothing written in bars too short to hold it`() {
        // 1/64 bars at 480 ticks a quarter: 30 ticks each, a quarter of a sixteenth.
        val tiny = piece(metas = { timeSignature(0, 1, 64) }) {
            for (k in 0 until 20) note(0, 36 + k, 299_990)           // held across every bar
            for (k in 0 until 2_500) note(k * 120L, 72, 1)            // one tick on every sixteenth
        }
        assertEquals(10_000, tiny.barStartsMicros.size)
        val started = System.nanoTime()
        val score = layout(tiny)
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue(score.quantized)
        assertTrue(score.rests.size <= ScoreLayoutEngine.restBudget(tiny.noteCount))
        assertTrue(score.headCount - score.noteCount <= ScoreLayoutEngine.tiedBudget(tiny.noteCount))
        assertTrue((0 until score.noteCount).all { score.system[it] >= 0 && score.x[it].isFinite() })
        assertTrue("laid out in %.2f s".format(seconds), seconds < 10.0)
    }

    @Test
    fun `a piece of more than a hundred thousand notes is laid out as performed`() {
        // 300,000 notes a sixteenth apart, each held for three bars: engraved, they would want two tied
        // heads each (a million held notes once made three million heads, 861 MB).
        val held = Synthetic(300_000, start = { it * 120L }, length = { 5_760L }, key = { 36 + it % 48 })
        val before = usedHeap()
        val score = held.layout()
        val grown = usedHeap() - before
        println("Budget: 300,000 held notes lay out as performed holding ${grown / mb} MB")
        assertFalse(score.quantized)
        assertEquals(score.noteCount, score.headCount)
        assertEquals(0, score.ties.size)
        assertEquals(0, score.rests.size)
        assertEquals(0, score.beams.size)
        assertTrue((0 until score.noteCount).all { score.head[it].toInt() == Head.BLACK && score.stemX[it].isNaN() })
        assertTrue((0 until score.noteCount).count { !score.durationEnd[it].isNaN() } > score.noteCount / 2)
        assertTrue("the layout holds ${grown / mb} MB", grown < 96 * mb)
    }

    @Test
    fun `a hundred thousand notes on the grid are engraved, one more and the piece is performed`() {
        fun sixteenths(count: Int) = Synthetic(count, start = { it * 120L }, length = { 120L }, key = { 60 + it % 24 })
        val engraved = sixteenths(ScoreLayoutEngine.MAX_ENGRAVED_NOTES).layout()
        assertTrue(engraved.quantized)
        assertTrue(engraved.beams.size > 0)
        val performed = sixteenths(ScoreLayoutEngine.MAX_ENGRAVED_NOTES + 1).layout()
        assertFalse(performed.quantized)
        assertEquals(0, performed.beams.size)
    }

    @Test
    fun `tied heads stop at a hundred thousand, the notes before the cap keeping theirs`() {
        // 60,000 notes, ten a bar, each held for three whole bars: two tied heads each, 120,000 in all.
        val held = Synthetic(60_000, start = { (it / 10) * 1_920L }, length = { 5_760L }, key = { 36 + (it % 10) * 5 })
        assertEquals(ScoreLayoutEngine.MAX_TIED_HEADS, ScoreLayoutEngine.tiedBudget(held.notes.size))
        val score = held.layout()
        assertTrue(score.quantized)
        assertEquals(ScoreLayoutEngine.MAX_TIED_HEADS, score.headCount - score.noteCount)
        assertEquals(2, score.tiedHeadCount(0))
        assertEquals(0, score.tiedHeadCount(score.noteCount - 1))
        assertTrue((0 until score.noteCount).all { score.tiedHeadCount(it) <= 2 })
    }

    @Test
    fun `a short piece keeps every tie of its long notes`() {
        // Ten notes held for twenty bars: 190 tied heads, far past twice the notes but within the floor.
        val long = piece { for (k in 0 until 10) note(0, 60 + k, 20 * 1_920L) }
        assertEquals(ScoreLayoutEngine.MIN_TIED_BUDGET, ScoreLayoutEngine.tiedBudget(10))
        val score = layout(long)
        assertEquals(190, score.headCount - score.noteCount)
        assertTrue((0 until score.noteCount).all { score.tiedHeadCount(it) == 19 })
    }
}

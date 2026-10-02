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
import dev.stevenjin.stevenpiano.score.ChordTrack
import dev.stevenjin.stevenpiano.score.ScoreDisplayList
import dev.stevenjin.stevenpiano.score.ScoreFixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The views' formats as the panel reads them (v1.13 — M32): what the tablet writes ([ScoreDisplayList]) comes back
 * whole through [NowWire], the Kotlin twin of wire.js, and bytes cut short, garbled or of another version are refused.
 */
class NowWireTest {
    private fun piece(): MidiPiece = ScoreFixtures.piece(metas = { timeSignature(0, 3, 4) }) {
        for (beat in 0 until 24) {
            note(beat * 480L, 60 + beat % 12, 480)
            note(beat * 480L, 36 + beat % 5, 960)
        }
        note(0, 10, 480)   // below the piano: unplayable without folding
    }

    private fun chords(piece: MidiPiece) = ChordTrack(
        LongArray(4) { piece.tempoMap.tickToMicros(it * 3 * 480L) },
        byteArrayOf(0, 7, 9, 5),
        byteArrayOf(0, 0, 1, 0),
        byteArrayOf(-1, -1, -1, 9),
        ByteArray(4),
    )

    private fun refused(what: String, read: () -> Unit) {
        try {
            read()
            fail("$what was read")
        } catch (e: NowWire.WireException) {
            // as it should be
        }
    }

    @Test
    fun `the notes come back whole, times, keys as played, hands, fingers and chord names`() {
        val piece = piece()
        val n = piece.notes.size
        val hands = ByteArray(n) { (it % 2).toByte() }
        val fingers = ByteArray(n) { (it % 5 + 1).toByte() }
        val chords = chords(piece)
        val bytes = ScoreDisplayList.notes(piece.notes, piece.durationMicros, 77, transpose = 2, fold = false, hands, fingers, chords)
        val read = NowWire.notes(bytes)
        assertEquals(77, read.rev)
        assertEquals(n, read.n)
        assertEquals(piece.durationMicros / 1000, read.durationMs)
        for (i in 0 until n) {
            assertEquals(piece.notes.startMicros[i] / 1000, read.start[i])
            assertEquals(piece.notes.endMicros[i] / 1000, read.end[i])
            val expected = piece.notes.note(i) + 2
            assertEquals("note $i", if (expected in 24..107) expected else ScoreDisplayList.NO_KEY, read.key[i])
        }
        assertTrue("the unplayable note is 255", read.key.contains(ScoreDisplayList.NO_KEY))
        assertArrayEquals(IntArray(n) { hands[it].toInt() }, read.hand)
        assertArrayEquals(IntArray(n) { fingers[it].toInt() }, read.finger)
        assertEquals(List(4) { chords.name(it, 2) }, read.chordNames)
        assertEquals("transposed: C up a tone is D", "D", read.chordNames[0])
        assertFalse(read.chordsCut)
        // Without hands, fingers or chords the sections are simply not there.
        val bare = NowWire.notes(ScoreDisplayList.notes(piece.notes, piece.durationMicros, 1, 0, true, null, null, null))
        assertEquals(null, bare.hand)
        assertEquals(null, bare.finger)
        assertEquals(0, bare.m)
    }

    @Test
    fun `the score's index and pages come back whole`() {
        val piece = piece()
        val metrics = ScoreDisplayList.metrics(400, 500, chords = true)
        val layout = ScoreFixtures.layout(piece, metrics)
        val index = NowWire.index(ScoreDisplayList.index(layout, rev = 5, layoutId = 9))
        assertEquals(5, index.rev)
        assertEquals(9, index.layoutId)
        assertEquals(layout.systems.size, index.systemCount)
        assertEquals(layout.pageCount, index.pageCount)
        assertEquals(metrics.pageWidth, index.pageWidth)
        assertEquals(metrics.space, index.space)
        assertTrue(index.engraved)
        for ((s, system) in layout.systems.withIndex()) assertEquals(layout.bars.startMicros[system.firstBar] / 1000, index.systemStart[s])
        val names = Array(4) { chords(piece).name(it) }
        for (p in 0 until layout.pageCount) {
            val page = NowWire.page(ScoreDisplayList.page(layout, 9, p, chords(piece), names)!!)
            assertEquals(p, page.page)
            assertEquals(layout.systemsOn(p).toList(), page.systems.map { it.index })
            for (system in page.systems) {
                for (b in system.barFrom until system.barFrom + system.barCount) {
                    val bar = system.firstBar + b - system.barFrom
                    assertEquals(layout.bars.startMicros[bar] / 1000, page.barStart(b))
                    for (k in 1 until NowWire.BAR_POINTS) {
                        assertTrue("the cursor moves on through bar $bar", page.pointMs(b, k) >= page.pointMs(b, k - 1) && page.pointX(b, k) >= page.pointX(b, k - 1))
                    }
                }
            }
            assertFalse(page.truncated)
        }
        assertEquals("a page the layout doesn't have", null, ScoreDisplayList.page(layout, 9, layout.pageCount))
    }

    @Test
    fun `bytes cut short, garbled or of another version are refused`() {
        val piece = piece()
        val layout = ScoreFixtures.layout(piece, ScoreDisplayList.metrics(400, 500, chords = false))
        val formats = listOf<Pair<ByteArray, (ByteArray) -> Unit>>(
            ScoreDisplayList.notes(piece.notes, piece.durationMicros, 1, 0, true, ByteArray(piece.notes.size), null, chords(piece)) to { b -> NowWire.notes(b) },
            ScoreDisplayList.index(layout, 1, 2) to { b -> NowWire.index(b) },
            ScoreDisplayList.page(layout, 2, 0)!! to { b -> NowWire.page(b) },
        )
        for ((bytes, read) in formats) {
            read(bytes)
            for (cut in listOf(0, 3, 7, 31, bytes.size / 3, bytes.size / 2, bytes.size - 5, bytes.size - 1)) {
                refused("${bytes.size} bytes cut to $cut") { read(bytes.copyOf(cut)) }
            }
            refused("a byte too many") { read(bytes + byteArrayOf(0)) }
            refused("another version") { read(bytes.copyOf().also { it[4] = 2 }) }
            refused("another format") { read(bytes.copyOf().also { it[0] = 'X'.code.toByte() }) }
        }
        // A count that claims more than is there, and a head pointing outside the ops.
        val notes = formats[0].first.copyOf().also { it[12] = (it[12] + 1).toByte() }
        refused("one note more than sent") { NowWire.notes(notes) }
        val page = formats[2].first
        val read = NowWire.page(page)
        val headsAt = 40 + read.systems.size * 40 + read.bars.size * 4
        val bad = page.copyOf().also { it[headsAt + 15] = 0x7F }   // the first head's end, far past the ops
        refused("a head outside the page") { NowWire.page(bad) }
    }
}

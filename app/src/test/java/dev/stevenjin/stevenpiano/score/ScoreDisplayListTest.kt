// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.score

import dev.stevenjin.stevenpiano.score.ScoreDisplayList.OP_CLIP
import dev.stevenjin.stevenpiano.score.ScoreDisplayList.OP_CURVE
import dev.stevenjin.stevenpiano.score.ScoreDisplayList.OP_END
import dev.stevenjin.stevenpiano.score.ScoreDisplayList.OP_GLYPH
import dev.stevenjin.stevenpiano.score.ScoreDisplayList.OP_QUAD
import dev.stevenjin.stevenpiano.score.ScoreDisplayList.OP_RECT
import dev.stevenjin.stevenpiano.score.ScoreDisplayList.OP_TEXT
import dev.stevenjin.stevenpiano.web.NowWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The score as the web panel's display list (v1.13 — M32): ops in the order the tablet's painter draws (staves, bar
 * lines, signs, the number, the marks, then the notes), each head's drawing contiguous so the overlay can replay it,
 * and the painter's caps.
 */
class ScoreDisplayListTest {
    private val heads = setOf(ScoreStyle.BLACK_HEAD, ScoreStyle.HALF_HEAD, ScoreStyle.WHOLE_HEAD)

    private fun op(word: IntArray) = word[0] and 0xFF

    private fun role(word: IntArray) = (word[0] ushr 8) and 0xFF

    /** A bar of eighths (beamed), a dotted half, a note tied across the bar line, rests in the bass. */
    private fun engraved() = ScoreFixtures.piece {
        for (k in 0 until 8) note(k * 240L, 72 + k % 4, 240)
        note(1920, 67, 3 * 480L)         // a dotted half
        note(3360, 74, 2 * 480L)         // across the bar line at 3840: tied
        note(0, 48, 2 * 480L)
    }

    @Test
    fun `an engraved page draws as the painter does, each system clipped, its staves first, its heads replayable`() {
        val piece = engraved()
        val layout = ScoreFixtures.layout(piece, ScoreDisplayList.metrics(400, 600, chords = false))
        assertTrue(layout.quantized)
        val page = NowWire.page(ScoreDisplayList.page(layout, 1, 0)!!)
        val ops = page.opList()
        // Each system: CLIP, ten staff lines, the opening line, then its bar lines (rects), its signs, its number.
        var k = 0
        for (system in page.systems) {
            assertEquals(OP_CLIP, op(ops[k]))
            for (line in 1..11) assertEquals("staff line $line", OP_RECT, op(ops[k + line]))
            assertTrue((1..11).all { role(ops[k + it]) == ScoreDisplayList.ROLE_LINE })
            var j = k + 12
            while (op(ops[j]) == OP_RECT) j++
            assertEquals("bar lines, then the signs", OP_GLYPH, op(ops[j]))
            while (op(ops[j]) == OP_GLYPH) j++
            assertEquals("then the bar number", OP_TEXT, op(ops[j]))
            assertEquals((system.firstBar + 1).toString(), page.strings[ops[j][1]])
            while (op(ops[j]) != OP_END) j++
            k = j + 1
        }
        assertEquals("every op belongs to a system", ops.size, k)
        assertTrue("beams", ops.any { op(it) == OP_QUAD })
        assertTrue("a tie", ops.any { op(it) == OP_CURVE })
        // Heads: every note's own head once, sorted by note, each one's ops a run ending with its head glyph (or its dot).
        val own = (0 until page.headCount).filter { page.heads[it * NowWire.HEAD_WORDS + 1] < 0 }.map { page.heads[it * NowWire.HEAD_WORDS] }
        assertEquals((0 until piece.notes.size).toList(), own)
        val tied = (0 until page.headCount).count { page.heads[it * NowWire.HEAD_WORDS + 1] >= 0 }
        assertTrue("the note across the bar line has a tied head", tied >= 1)
        for (h in 0 until page.headCount) {
            val o = h * NowWire.HEAD_WORDS
            val run = NowWire.Page(page.layoutId, page.page, false, page.systems, page.bars, IntArray(0), page.ops.copyOfRange(page.heads[o + 2], page.heads[o + 3]), page.strings).opList()
            val glyphs = run.filter { op(it) == OP_GLYPH }.map { it[1] }
            assertEquals("head $h holds one head glyph", 1, glyphs.count { it in heads })
            assertTrue(run.all { role(it) == ScoreDisplayList.ROLE_NOTE })
            assertTrue("its last glyph is its head or its dot", glyphs.last() in heads || glyphs.last() == ScoreStyle.DOT)
        }
    }

    @Test
    fun `a performance's heads carry their lengths, and a system draws no more than the painter's cap`() {
        // 5,000 notes off the sixteenth grid in two bars: a crafted file, performed, every head with its hairline.
        val piece = ScoreFixtures.piece {
            for (k in 0 until 5_000) note(k * 3L / 4 + 1, 60 + k % 24, 7)
        }
        val layout = ScoreFixtures.layout(piece, ScoreDisplayList.metrics(400, 1200, chords = false))
        assertTrue(!layout.quantized)
        val bytes = ScoreDisplayList.page(layout, 1, 0)!!
        val page = NowWire.page(bytes)
        val perSystem = IntArray(page.systems.size)
        for (h in 0 until page.headCount) perSystem[page.heads[h * NowWire.HEAD_WORDS + 4]]++
        assertTrue("at most ${ScoreStyle.MAX_NOTE_DRAWS} heads a system: ${perSystem.toList()}", perSystem.all { it <= ScoreStyle.MAX_NOTE_DRAWS })
        assertTrue("the first system is full", perSystem[0] == ScoreStyle.MAX_NOTE_DRAWS || page.truncated)
        assertTrue("under the page cap", bytes.size <= ScoreDisplayList.MAX_PAGE_BYTES)
        // A small cap: the notes stop, the page says so, and every system still closes.
        val small = NowWire.page(ScoreDisplayList.page(layout, 1, 0, cap = 40 * 1024)!!)
        assertTrue(small.truncated)
        assertTrue(small.headCount < page.headCount)
        assertEquals(small.systems.size, small.opList().count { op(it) == OP_END })
        // Performed heads within the staff: each run opens with its length's hairline, beside the head.
        val performed = ScoreFixtures.layout(
            ScoreFixtures.piece { for (k in 0 until 4) note(13L + k * 500, 72 + k, 400) },
            ScoreDisplayList.metrics(400, 600, chords = false),
        )
        val few = NowWire.page(ScoreDisplayList.page(performed, 2, 0)!!)
        assertEquals(4, few.headCount)
        for (h in 0 until few.headCount) {
            val o = h * NowWire.HEAD_WORDS
            val run = few.ops.copyOfRange(few.heads[o + 2], few.heads[o + 3])
            assertEquals("head $h opens with its hairline", OP_RECT, run[0] and 0xFF)
            assertEquals(performed.durationEnd[few.heads[o]] - performed.x[few.heads[o]] - performed.headWidth(few.heads[o]), Float.fromBits(run[3]), 0.001f)
        }
    }
}

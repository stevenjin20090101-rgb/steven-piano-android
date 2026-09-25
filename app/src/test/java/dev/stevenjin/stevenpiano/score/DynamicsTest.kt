// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================


package dev.stevenjin.stevenpiano.score

import dev.stevenjin.stevenpiano.score.ScoreFixtures.SPACE
import dev.stevenjin.stevenpiano.score.ScoreFixtures.at
import dev.stevenjin.stevenpiano.score.ScoreFixtures.layout
import dev.stevenjin.stevenpiano.score.ScoreFixtures.piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicsTest {
    private fun marks(means: List<Double>, performed: Boolean = false): List<Int> {
        val out = IntArray(means.size)
        Dynamics.marks(means.toDoubleArray(), performed, out)
        return out.toList()
    }

    @Test
    fun `mean velocities read in six bands`() {
        val bands = listOf(0.0, 31.9, 32.0, 47.9, 48.0, 63.9, 64.0, 79.9, 80.0, 95.9, 96.0, 127.0).map { Dynamics.band(it) }
        assertEquals(
            listOf(Dynamics.PP, Dynamics.PP, Dynamics.P, Dynamics.P, Dynamics.MP, Dynamics.MP, Dynamics.MF, Dynamics.MF, Dynamics.F, Dynamics.F, Dynamics.FF, Dynamics.FF),
            bands,
        )
        assertEquals(listOf("pp", "p", "mp", "mf", "f", "ff"), (Dynamics.PP..Dynamics.FF).map { Dynamics.name(it) })
        assertEquals("", Dynamics.glyphs(Dynamics.PP))
        assertEquals("", Dynamics.glyphs(Dynamics.P))
        assertEquals("", Dynamics.glyphs(Dynamics.MP))
        assertEquals("", Dynamics.glyphs(Dynamics.MF))
        assertEquals("", Dynamics.glyphs(Dynamics.F))
        assertEquals("", Dynamics.glyphs(Dynamics.FF))
    }

    @Test
    fun `the first bar with notes is marked, then only a change of band`() {
        val nan = Double.NaN
        assertEquals(listOf(-1, Dynamics.MP, -1, Dynamics.MF, -1, -1, Dynamics.PP), marks(listOf(nan, 50.0, 52.0, 70.0, 75.0, nan, 20.0)))
        // A silent bar neither marks nor resets: the band after it is compared with the last mark.
        assertEquals(listOf(Dynamics.MF, -1, -1), marks(listOf(70.0, nan, 72.0)))
    }

    @Test
    fun `a sequenced mean hovering on a band's edge does not flip the mark bar by bar`() {
        // Clair de lune's first bars as piano-midi.de shapes them, around p's floor of 32.
        val opening = listOf(38.3, 31.0, 35.8, 29.8, 35.0, 31.8, 35.7, 28.6, 31.4, 28.9, 33.8, 29.6, 36.9)
        assertEquals(
            listOf(Dynamics.P, -1, -1, -1, -1, -1, -1, Dynamics.PP, -1, -1, -1, -1, Dynamics.P),
            marks(opening),
        )
        // Three units past the edge marks the band beyond it; a jump over two edges marks the band it has cleared.
        assertEquals(listOf(Dynamics.PP, -1, Dynamics.P), marks(listOf(30.0, 34.0, 35.0)))
        assertEquals(listOf(Dynamics.MF, Dynamics.MP), marks(listOf(70.0, 46.0)))   // 46 is only 2 below mp's floor
        assertEquals(listOf(Dynamics.FF, Dynamics.F, Dynamics.FF), marks(listOf(120.0, 92.0, 99.0)))
    }

    @Test
    fun `a performance is marked only when the band moves two or more`() {
        val means = listOf(50.0, 70.0, 85.0, 90.0, 70.0, 40.0)   // mp mf f f mf p
        assertEquals(listOf(Dynamics.MP, -1, Dynamics.F, -1, -1, Dynamics.P), marks(means, performed = true))
        assertEquals(listOf(Dynamics.MP, Dynamics.MF, Dynamics.F, -1, Dynamics.MF, Dynamics.P), marks(means))
        // A slow drift up one band at a time is marked once it is two bands from the last mark.
        assertEquals(listOf(Dynamics.P, -1, Dynamics.MF, -1), marks(listOf(40.0, 50.0, 70.0, 85.0 - 10.0), performed = true))
    }

    @Test
    fun `in the layout a mark sits under the treble staff at the bar's first onset`() {
        val loud = piece {
            note(0, 72, 480, velocity = 50); note(480, 74, 480, velocity = 54)       // bar 1: mp
            note(1920, 72, 480, velocity = 56); note(2400, 74, 480, velocity = 60)   // bar 2: still mp
            note(3840 + 480, 60, 480, velocity = 110); note(3840 + 480, 48, 480, velocity = 100)   // bar 3: ff over both staves, from beat 2
        }
        val score = layout(loud)
        assertEquals(listOf(0, 2), score.dynamics.map { it.bar })
        assertEquals(listOf("mp", "ff"), score.dynamics.map { it.name })
        val first = score.dynamics[0]
        assertEquals(0, first.system)
        assertEquals(score.x[loud.at(0, 72)], first.x, 0.01f)
        assertEquals(score.systems[0].trebleBottom + (1.5f + 1.096f) * SPACE, first.y, 0.01f)
        val third = score.dynamics[1]
        assertEquals(1, third.system)
        assertEquals(minOf(score.x[loud.at(4320, 60)], score.x[loud.at(4320, 48)]), third.x, 0.01f)
        assertEquals("", third.glyphs)
    }

    @Test
    fun `a mark under a low treble note moves down, clear of it, and never into the bass staff`() {
        val low = layout(piece { note(0, 60, 1920) })          // middle C, a ledger line below the treble staff
        val mark = low.dynamics.single()
        val c = 0
        // Its top (the baseline less the p's and m's rise) is half a space under the head.
        assertEquals(low.y[c] + SPACE / 2 + 0.5f * SPACE + 1.096f * SPACE, mark.y, 0.01f)
        assertTrue(mark.y > low.systems[0].trebleBottom + (1.5f + 1.096f) * SPACE)
        assertTrue(mark.y + 0.608f * SPACE < low.systems[0].bassTop)
        val high = layout(piece { note(0, 72, 1920) })         // C5 stays up: the mark keeps its place
        assertEquals(high.systems[0].trebleBottom + (1.5f + 1.096f) * SPACE, high.dynamics.single().y, 0.01f)
        assertEquals(2 * 1.46f, Dynamics.width(Dynamics.PP), 0.001f)
        assertEquals(1.748f + 1.456f, Dynamics.width(Dynamics.MF), 0.001f)
    }

    @Test
    fun `the mean takes both staves`() {
        val score = layout(piece { note(0, 72, 480, velocity = 100); note(0, 48, 480, velocity = 20) })   // (100 + 20) / 2 = 60
        assertEquals(listOf("mp"), score.dynamics.map { it.name })
    }

    @Test
    fun `a performance's nuances are left unmarked`() {
        val random = java.util.Random(19)
        val played = piece(ppq = 384) {
            var tick = 0L
            repeat(200) {
                val bar = tick / (4 * 384)
                val base = if (bar < 4) 60 else 100   // mp, then ff from bar 5
                note(tick, 55 + random.nextInt(20), 60L + random.nextInt(40), velocity = base + random.nextInt(8) - 4)
                tick += 37 + random.nextInt(90)
            }
        }
        val score = layout(played)
        assertFalse(score.quantized)
        assertEquals(listOf("mp", "ff"), score.dynamics.map { it.name })
        assertTrue(score.dynamics[1].bar >= 4)
    }
}

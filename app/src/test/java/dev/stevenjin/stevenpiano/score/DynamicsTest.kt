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
        assertEquals(listOf(-1, Dynamics.MP, -1, Dynamics.MF, -1, -1, Dynamics.PP), marks(listOf(nan, 50.0, 52.0, 70.0, 75.0, nan, 30.0)))
        // A silent bar neither marks nor resets: the band after it is compared with the last mark.
        assertEquals(listOf(Dynamics.MF, -1, -1), marks(listOf(70.0, nan, 72.0)))
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
            note(3840 + 480, 60, 480, velocity = 100); note(3840 + 480, 48, 480, velocity = 96)   // bar 3: ff over both staves, from beat 2
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

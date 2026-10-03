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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Repeats that keep the rhythm (v1.16 — M44): every strike lands, faster repeats keep the pulse, nothing else moves. */
class RepeatsTest {
    private fun shaped(restrikeMs: Int, facts: PianoFacts = PianoFacts(), block: Score.() -> Unit) =
        Performance.shape(piece(block), null, RepeatsOnly.copy(restrikeMs = restrikeMs), facts).sounded()

    @Test
    fun `a note ends at least the release gap before its key's next onset, never shorter than 30 ms`() {
        // T 110 ms: G = 70 ms. Legato quarters on one key, each ending as the next starts.
        val legato = shaped(110) { for (k in 0L until 4L) note(k * 500, 60, 500) }
        assertEquals(listOf(0L, 500_000L, 1_000_000L, 1_500_000L), legato.map { it.on })
        for ((a, b) in legato.zipWithNext()) assertTrue("${a.off} against ${b.on}", a.off <= b.on - 70 * MS)
        assertEquals("the key's last note keeps its end", 2_000_000L, legato.last().off)
        assertEquals(430 * MS, legato.first().off)
        // T 60 ms: G = 60 ms; a strike 70 ms after another leaves its note 30 ms, not 10.
        val close = shaped(60) { note(0, 62, 65); note(70, 62, 65) }
        assertEquals(listOf(30 * MS, 135 * MS), close.map { it.off })
    }

    @Test
    fun `repeats faster than T keep the first and every n-th, each a touch louder for those it stands for, 18 at most`() {
        val notes = shaped(100) {
            for (k in 0L until 9L) note(k * 40, 60, 30, velocity = 80)   // every 40 ms: n = 3
            for (k in 0L until 11L) note(k * 20, 62, 15, velocity = 80)   // every 20 ms: n = 5
            for (k in 0L until 3L) note(k * 50, 64, 30, velocity = 125)   // every 50 ms: n = 2, and 127 at most
        }
        val sixty = notes.filter { it.key == 60 }
        assertEquals(listOf(0L, 120L, 240L).map { it * MS }, sixty.map { it.on })
        assertEquals("6 for each of the two it stands for", listOf(92, 92, 92), sixty.map { it.velocity })
        val sixtyTwo = notes.filter { it.key == 62 }
        assertEquals(listOf(0L, 100L, 200L).map { it * MS }, sixtyTwo.map { it.on })
        assertEquals("four stood for: 24, held to 18", listOf(98, 98, 80), sixtyTwo.map { it.velocity })
        val sixtyFour = notes.filter { it.key == 64 }
        assertEquals(listOf(0L, 100L).map { it * MS }, sixtyFour.map { it.on })
        assertEquals(listOf(127, 125), sixtyFour.map { it.velocity })
        // The key sounds through the run: the last kept note lasts as long as the last of those it stands for (320 + 30);
        // one before another lifts in time for it (100 less G, 60 ms).
        assertEquals(350 * MS, sixty.last().off)
        assertEquals(listOf(40 * MS, 130 * MS), sixtyFour.map { it.off })
    }

    @Test
    fun `nothing else changes, and a piece without anything to space comes back as it was`() {
        val notes = shaped(110) {
            for (k in 0L until 4L) note(k * 500, 72, 100, velocity = 70)
            for (k in 0L until 4L) note(k * 500 + 250, 48, 200, velocity = 50)
            cc(0, 64, 127)
            cc(1_900, 64, 0)
        }
        assertEquals((0L until 4L).map { Sounded(72, it * 500 * MS, (it * 500 + 100) * MS, 70) }, notes.filter { it.key == 72 })
        assertEquals((0L until 4L).map { Sounded(48, (it * 500 + 250) * MS, (it * 500 + 450) * MS, 50) }, notes.filter { it.key == 48 })
        val calm = piece { for (k in 0L until 4L) note(k * 500, 72, 100); cc(1_900, 64, 0) }
        assertSame(calm, Performance.shape(calm, null, RepeatsOnly.copy(restrikeMs = 110), PianoFacts()))
        val trill = piece { for (k in 0L until 8L) note(k * 50, 60, 40) }
        assertSame("a MIDI piano strikes again at will", trill, Performance.shape(trill, null, RepeatsOnly, FreeRepeats))
    }

    @Test
    fun `Auto takes the piano's repeat period when it reports one, else 100 ms`() {
        assertEquals(110, Performance.restrikeMs(PerformanceSettings(), PianoFacts(repeatMs = 110)))
        assertEquals(100, Performance.restrikeMs(PerformanceSettings(), PianoFacts()))
        assertEquals("the person's own time stands", 150, Performance.restrikeMs(PerformanceSettings(restrikeMs = 150), PianoFacts(repeatMs = 110)))
        assertEquals(PianoFacts(repeatMs = 40), PianoFacts.read(" 40"))
        assertEquals(PianoFacts(), PianoFacts.read(null))
        assertEquals(PianoFacts(), PianoFacts.read("soon"))
        // Every 50 ms: Snappy's 40 ms period plays them all; no piano (100 ms) keeps every second one.
        val snappy = shaped(Performance.AUTO, PianoFacts(repeatMs = 40)) { for (k in 0L until 6L) note(k * 50, 60, 30) }
        assertEquals(6, snappy.size)
        val unknown = shaped(Performance.AUTO) { for (k in 0L until 6L) note(k * 50, 60, 30) }
        assertEquals(listOf(0L, 100L, 200L).map { it * MS }, unknown.map { it.on })
    }
}

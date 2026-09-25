// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.imports

import dev.stevenjin.stevenpiano.data.imports.TitleHeuristics.Metadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleHeuristicsTest {
    private fun meta(file: String, row: IndexCsv.Row? = null, names: List<String> = emptyList()) =
        TitleHeuristics.metadata(file, row, names)

    @Test
    fun `Composer - Title splits at the first spaced dash, including Surname, I - title`() {
        assertEquals(Metadata("Vocalise1", "Abt, F", null), meta("Abt, F - Vocalise1.mid"))
        assertEquals(Metadata("Etude - La Campanella", "Liszt", null), meta("Liszt - Etude - La Campanella.MIDI"))
        assertEquals(Metadata("Nocturne Op. 9 No. 2", "", null), meta("Nocturne Op. 9 No. 2.mid"))
        assertEquals(Metadata("Rondo-Capriccioso", "", null), meta("Rondo-Capriccioso.mid"))
    }

    @Test
    fun `the split finds what the old regex found, without its quadratic time`() {
        val cases = listOf(
            "Abt, F - Vocalise1", "Liszt - Etude - La Campanella", "a - b", " - b", "a - ", "a -  - b", " -  - b", "x-y - z", "- - -",
            "Chopin -Nocturne", "Chopin- Nocturne", "a\u2028 - b", "a - b\nc", "a\u0085 - b", "", "a",
        )
        val regex = Regex("^(.+?) - (.+)$")
        for (name in cases) {
            val expected = regex.matchEntire(name)?.let { it.groupValues[1].trim() to it.groupValues[2].trim() }
            assertEquals(name, expected, TitleHeuristics.splitComposer(name))
        }
        val hostile = " - ".repeat(20_000) + "\u2028"   // 60,000 characters: once 10^9 regex steps
        val started = System.nanoTime()
        assertEquals(null, TitleHeuristics.splitComposer(hostile))
        TitleHeuristics.metadata(hostile + ".mid", null, emptyList())
        assertTrue((System.nanoTime() - started) / 1_000_000 < 500)
    }

    @Test
    fun `a file name is read to 255 characters`() {
        val name = "Chopin - " + "Nocturne ".repeat(100) + ".mid"
        val meta = TitleHeuristics.metadata(name, null, emptyList())
        assertEquals("Chopin", meta.composer)
        assertTrue(meta.title.length <= 255)
    }

    @Test
    fun `a stub title gives way to Track 0's name when that reads like a title`() {
        assertEquals("Klaviersonate Nr. 8 KV 311 1. Satz", meta("mz_311_1.mid", names = listOf("Klaviersonate Nr. 8 KV 311 1. Satz")).title)
        assertEquals("Die Jahreszeiten — April", meta("ty_april.mid", names = listOf("Die Jahreszeiten", "April")).title)
        assertEquals(
            "Petite Suite — 1. Im Kloster",
            meta("Borodin - bor_ps1_format4.mid", names = listOf("Petite Suite", "1. Im Kloster", "Piano")).title,
        )
        assertEquals("bwv_117a", meta("bwv_117a.mid", names = listOf("control track")).title)
        assertEquals("bk_xmas2", meta("bk_xmas2.mid", names = listOf("Weihnachtsfantasie")).title)
        assertEquals("BWV-117a", meta("BWV-117a.mid", names = listOf("Wer nur den lieben Gott")).title)
    }

    @Test
    fun `the INDEX csv row wins, and a stub title from it is replaced too`() {
        val mozart = IndexCsv.Row("piano-midi.de", "mozart", "mz_311_1", "piano-midi.de/mozart/mz_311_1.mid")
        assertEquals(
            Metadata("Klaviersonate Nr. 8 KV 311 1. Satz", "mozart", "piano-midi.de"),
            meta("mz_311_1.mid", mozart, listOf("Klaviersonate Nr. 8 KV 311 1. Satz")),
        )
        val maestro = IndexCsv.Row("maestro-classical-performances", "Georges Bizet Vladimir Horowitz", "Carmen Variations", "x.mid")
        assertEquals(
            Metadata("Carmen Variations", "Georges Bizet Vladimir Horowitz", "maestro-classical-performances"),
            meta("Carmen Variations.mid", maestro),
        )
        val mutopia = IndexCsv.Row("mutopia-public-domain", "", "BWV-117a", "mutopia-public-domain/BWV-117a.mid")
        assertEquals(Metadata("BWV-117a", "", "mutopia-public-domain"), meta("BWV-117a.mid", mutopia))
    }

    @Test
    fun `double-encoded names are repaired while real dashes survive`() {
        assertEquals(
            Metadata("España, Opus 165 (1890) — Malagueña", "Albeniz", null),
            meta("Albeniz - EspaÃ±a, Opus 165 (1890) — MalagueÃ±a.mid"),
        )
        assertEquals(
            "Études d'exécution transcendante (1851) — No. 8 - Wild Hunt",
            meta("Liszt - Ã\u0089tudes d'exÃ©cution transcendante (1851) — No. 8 - Wild Hunt.mid").title,
        )
        assertEquals("SÃO PAULO", TitleHeuristics.repairMojibake("SÃO PAULO"))
        assertEquals("Dvořák", TitleHeuristics.repairMojibake("Dvořák"))
    }

    @Test
    fun `text is composed and its spaces collapsed`() {
        assertEquals("Frédéric", TitleHeuristics.cleanText("Frédéric"))
        assertEquals("Chopin Prelude No. 1", TitleHeuristics.cleanText(" Chopin Prelude  No. 1 "))
    }

    @Test
    fun `generic track names are recognised`() {
        listOf("Piano", "Track 1", "control track", "Right Hand", "Untitled", "Acoustic Grand Piano", "  ").forEach {
            assertTrue(it, TitleHeuristics.isGeneric(it))
        }
        assertFalse(TitleHeuristics.isGeneric("Petite Suite"))
    }
}

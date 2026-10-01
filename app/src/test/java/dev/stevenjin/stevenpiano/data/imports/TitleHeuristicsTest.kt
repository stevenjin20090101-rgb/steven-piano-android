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
import dev.stevenjin.stevenpiano.data.imports.TitleHeuristics.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleHeuristicsTest {
    private fun meta(file: String, row: IndexCsv.Row? = null, names: List<String> = emptyList()) =
        TitleHeuristics.metadata(file, row, names)

    @Test
    fun `Composer - Title splits at the first spaced dash, including Surname, I - title`() {
        assertEquals(Metadata("Vocalise1", "Abt, F", null, Source.FILE_NAME), meta("Abt, F - Vocalise1.mid"))
        assertEquals(Metadata("Etude - La Campanella", "Liszt", null, Source.FILE_NAME), meta("Liszt - Etude - La Campanella.MIDI"))
        assertEquals(Metadata("Nocturne Op. 9 No. 2", "", null, Source.NONE), meta("Nocturne Op. 9 No. 2.mid"))
        assertEquals(Metadata("Rondo-Capriccioso", "", null, Source.NONE), meta("Rondo-Capriccioso.mid"))
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
            Metadata("Klaviersonate Nr. 8 KV 311 1. Satz", "mozart", "piano-midi.de", Source.INDEX),
            meta("mz_311_1.mid", mozart, listOf("Klaviersonate Nr. 8 KV 311 1. Satz")),
        )
        val maestro = IndexCsv.Row("maestro-classical-performances", "Georges Bizet Vladimir Horowitz", "Carmen Variations", "x.mid")
        assertEquals(
            Metadata("Carmen Variations", "Georges Bizet Vladimir Horowitz", "maestro-classical-performances", Source.INDEX),
            meta("Carmen Variations.mid", maestro),
        )
        val mutopia = IndexCsv.Row("mutopia-public-domain", "", "BWV-117a", "mutopia-public-domain/BWV-117a.mid")
        assertEquals(Metadata("BWV-117a", "", "mutopia-public-domain", Source.INDEX), meta("BWV-117a.mid", mutopia))
    }

    @Test
    fun `double-encoded names are repaired while real dashes survive`() {
        assertEquals(
            Metadata("España, Opus 165 (1890) — Malagueña", "Albeniz", null, Source.FILE_NAME),
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

    // v1.10.1 — M28, D1: where a piece's artist comes from without an INDEX.csv row.

    private fun read(file: String, folder: String? = null, artists: Set<String> = emptySet()) =
        TitleHeuristics.metadata(file, null, emptyList(), folder) { name -> name.takeIf { it in artists } }

    @Test
    fun `a file in an artist folder takes the folder as its artist`() {
        assertEquals(Metadata("Sparks", "Coldplay", null, Source.FOLDER), read("Sparks.mid", folder = "Coldplay"))
        assertEquals("the folder's name composed and its spaces collapsed", "Beyoncé", read("Halo.mid", folder = " Beyonce\u0301 ").composer)
        assertEquals(Metadata("Sparks", "", null, Source.NONE), read("Sparks.mid", folder = "  "))
        val index = IndexCsv.Row("Evening", "", "Sparks", "Coldplay/Sparks.mid")
        assertEquals("an INDEX row still wins, its blank composer kept", Metadata("Sparks", "", "Evening", Source.INDEX), TitleHeuristics.metadata("Sparks.mid", index, emptyList(), "Coldplay") { it })
    }

    @Test
    fun `a name whose right side alone is known reads the other way round`() {
        val zimmer = setOf("Hans Zimmer", "Coldplay")
        assertEquals(Metadata("Cornfield Chase", "Hans Zimmer", null, Source.REVERSED), read("Cornfield Chase - Hans Zimmer.mid", artists = zimmer))
        assertEquals(Metadata("Day One (Interstellar)", "Hans Zimmer", null, Source.REVERSED), read("Day One (Interstellar) - Hans Zimmer.mid", artists = zimmer))
        assertEquals(Metadata("Time (Inception)", "Hans Zimmer", null, Source.REVERSED), read("Time (Inception) - Hans Zimmer.mid", artists = zimmer))
        assertEquals("a canonical composer is known without a folder", Metadata("Clair de lune", "Debussy", null, Source.REVERSED), read("Clair de lune - Debussy.mid"))
        assertEquals("the reading beats the folder that holds it", Metadata("Clair de lune", "Debussy", null, Source.REVERSED), read("Clair de lune - Debussy.mid", folder = "Coldplay"))
    }

    @Test
    fun `a parenthetical at the end of the artist's side goes to the title`() {
        val zimmer = setOf("Hans Zimmer")
        assertEquals(Metadata("Cornfield Chase (version 2)", "Hans Zimmer", null, Source.REVERSED), read("Cornfield Chase - Hans Zimmer (version 2).mid", artists = zimmer))
        assertEquals(Metadata("Cornfield Chase (live) [2]", "Hans Zimmer", null, Source.REVERSED), read("Cornfield Chase - Hans Zimmer (live) [2].mid", artists = zimmer))
        assertEquals("Hans Zimmer" to "(version 2)", TitleHeuristics.trailingParentheticals("Hans Zimmer (version 2)"))
        assertEquals("(Interstellar)" to "", TitleHeuristics.trailingParentheticals("(Interstellar)"))
        assertEquals("Day One (Interstellar) x" to "", TitleHeuristics.trailingParentheticals("Day One (Interstellar) x"))
        assertEquals("not reversed, a parenthetical stays where it is", Metadata("Nocturne (version 2)", "Chopin", null, Source.FILE_NAME), read("Chopin - Nocturne (version 2).mid"))
    }

    @Test
    fun `a name with neither side known, or both, reads as it always has`() {
        assertEquals("neither", Metadata("Interstellar", "Stay", null, Source.FILE_NAME), read("Stay - Interstellar.mid", artists = setOf("Hans Zimmer")))
        assertEquals("both", Metadata("Debussy", "Chopin", null, Source.FILE_NAME), read("Chopin - Debussy.mid"))
        assertEquals("the left a folder, the right a title", Metadata("Perfect", "Ed Sheeran", null, Source.FILE_NAME), read("Ed Sheeran - Perfect.mid", folder = "Ed Sheeran", artists = setOf("Ed Sheeran")))
        assertEquals("a dash name beats the folder", Metadata("Nocturne", "Chopin", null, Source.FILE_NAME), read("Chopin - Nocturne.mid", folder = "Coldplay", artists = setOf("Coldplay")))
        assertEquals("a namesake of a canonical composer is not known", Metadata("Andrew Berg", "Lullaby", null, Source.FILE_NAME), read("Lullaby - Andrew Berg.mid"))
    }

    @Test
    fun `an import's root is the one top-level folder all its files share, and its artist folders lie below it`() {
        val zip = ImportFolders(listOf("MIDI/Coldplay/Sparks.mid", "MIDI/Hans Zimmer/Time.mid", "MIDI/Cornfield Chase - Hans Zimmer.mid", "MIDI/Pop/Adele/Hello.mid"))
        assertEquals("MIDI", zip.root)
        assertEquals("Coldplay", zip.artistFolderOf("MIDI/Coldplay/Sparks.mid"))
        assertEquals("the folder that holds the file", "Adele", zip.artistFolderOf("MIDI/Pop/Adele/Hello.mid"))
        assertEquals("a file in the root has none", null, zip.artistFolderOf("MIDI/Cornfield Chase - Hans Zimmer.mid"))
        assertTrue(zip.isArtist("Hans Zimmer"))
        assertTrue("compared by the folded whole name", zip.isArtist("hans  zimmer"))
        assertFalse("the root is no artist", zip.isArtist("MIDI"))
        assertFalse(zip.isArtist("Zimmer"))
        assertEquals("only a folder that holds a file is an artist's", setOf("coldplay", "hans zimmer", "adele"), zip.artistKeys)
        assertFalse(zip.isArtist("Pop"))
        assertEquals("Hans Zimmer", zip.artistNamed("hans  zimmer"))
        assertEquals("reversed against the import's own folders, the artist spelt as the folder is", Metadata("Cornfield Chase", "Hans Zimmer", null, Source.REVERSED),
            TitleHeuristics.metadata("Cornfield Chase - hans zimmer.mid", null, emptyList(), null, zip::artistNamed))

        val folder = ImportFolders(listOf("Coldplay/Sparks.mid", "Adele/Hello.mid", "Loose.mid"))
        assertEquals("a folder chosen with its artists inside it has no root of its own", null, folder.root)
        assertEquals("Coldplay", folder.artistFolderOf("Coldplay/Sparks.mid"))
        assertEquals(null, folder.artistFolderOf("Loose.mid"))
        assertEquals("one artist's zip: the folder is the root, its files have no artist", "Coldplay", ImportFolders(listOf("Coldplay/a.mid", "Coldplay/b.mid")).root)
        assertEquals(null, ImportFolders(listOf("Coldplay/a.mid", "Coldplay/b.mid")).artistFolderOf("Coldplay/a.mid"))
        assertEquals(null, ImportFolders(listOf("a.mid", "b.mid")).root)
        assertEquals(null, ImportFolders(emptyList()).root)
        assertEquals("Canonical folders key as the composer", setOf("debussy"), ImportFolders(listOf("MIDI/Claude Debussy/Clair de Lune.mid")).artistKeys)
    }

    @Test
    fun `what a Mac adds beside the music is skipped, wherever it is`() {
        assertTrue(isMacMetadata("__MACOSX/MIDI/._Sparks.mid"))
        assertTrue(isMacMetadata("__MACOSX/._MIDI"))
        assertTrue(isMacMetadata("MIDI/Coldplay/._Sparks.mid"))
        assertTrue(isMacMetadata("._Sparks.mid"))
        assertTrue(isMacMetadata("MIDI/__MACOSX/Sparks.mid"))
        assertFalse(isMacMetadata("MIDI/Coldplay/Sparks.mid"))
        assertFalse(isMacMetadata("MIDI/Coldplay/_Sparks.mid"))
        assertTrue("zips and folders never list them", isHiddenPath("MIDI/__MACOSX/Sparks.mid") && isHiddenPath("__MACOSX/MIDI/._Sparks.mid"))
    }

    @Test
    fun `generic track names are recognised`() {
        listOf("Piano", "Track 1", "control track", "Right Hand", "Untitled", "Acoustic Grand Piano", "  ").forEach {
            assertTrue(it, TitleHeuristics.isGeneric(it))
        }
        assertFalse(TitleHeuristics.isGeneric("Petite Suite"))
    }
}

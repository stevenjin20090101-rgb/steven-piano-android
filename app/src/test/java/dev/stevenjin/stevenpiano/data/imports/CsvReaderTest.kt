// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CsvReaderTest {
    @Test
    fun `quoted fields keep commas, doubled quotes and line breaks`() {
        val text = "collection,composer,title\r\n" +
            "maestro,Felix Mendelssohn,\"Fantasy in F-sharp Minor, Op. 28 (Complete)\"\r\n" +
            "x,\"The \"\"Best\"\" One\",\"two\nlines\"\r\n"
        assertEquals(
            listOf(
                listOf("collection", "composer", "title"),
                listOf("maestro", "Felix Mendelssohn", "Fantasy in F-sharp Minor, Op. 28 (Complete)"),
                listOf("x", "The \"Best\" One", "two\nlines"),
            ),
            CsvReader.parse(text),
        )
    }

    @Test
    fun `empty fields, a byte-order mark, a bare CR and no final newline`() {
        assertEquals(listOf(listOf("a", "", "c"), listOf("", "", ""), listOf("d")), CsvReader.parse("\uFEFFa,,c\n,,\rd"))
        assertEquals(listOf(listOf("")), CsvReader.parse("\"\""))
        assertEquals(emptyList<List<String>>(), CsvReader.parse(""))
    }

    @Test
    fun `a field keeps at most the cap, and the rest of its row still reads`() {
        val long = "x".repeat(10)
        val text = "$long,\"${"\"\"".repeat(10)}\",b\nc,d,e\n"
        assertEquals(listOf(listOf("xxxx", "\"\"\"\"", "b"), listOf("c", "d", "e")), CsvReader.parse(text, maxField = 4))
    }
}

class IndexCsvTest {
    private val sample = """
        collection,composer,title,size_kb,path
        maestro-classical-performances,Felix Mendelssohn,"Fantasy in F-sharp Minor, Op. 28 (Complete)",75.8,"maestro-classical-performances/Felix Mendelssohn/Fantasy in F-sharp Minor, Op. 28 (Complete).mid"
        piano-midi.de,mozart,mz_311_1,28.3,piano-midi.de/mozart/mz_311_1.mid
        mutopia-public-domain,,BWV-117a,2.1,mutopia-public-domain/BWV-117a.mid
    """.trimIndent()

    @Test
    fun `rows are found by path, with quoted commas and empty composers`() {
        val index = IndexCsv.parse(sample)
        assertEquals(3, index.size)
        val fantasy = "maestro-classical-performances/Felix Mendelssohn/Fantasy in F-sharp Minor, Op. 28 (Complete).mid"
        assertEquals("Fantasy in F-sharp Minor, Op. 28 (Complete)", index.lookup(fantasy)?.title)
        val mutopia = index.lookup("mutopia-public-domain/BWV-117a.mid")!!
        assertEquals("", mutopia.composer)
        assertEquals("mutopia-public-domain", mutopia.collection)
        assertEquals("mozart", index.lookup("./PIANO-MIDI.DE\\mozart\\mz_311_1.mid")?.composer)
        assertNull(index.lookup("piano-midi.de/mozart/mz_311_2.mid"))
    }

    @Test
    fun `an INDEX csv field is cut to 1 KB, so a megabyte title never reaches the importer`() {
        val title = "T".repeat(1_000_000)
        val collection = "C".repeat(5_000)
        val row = IndexCsv.parse("collection,composer,title,size_kb,path\n$collection,Bach,$title,1,a.mid\n").lookup("a.mid")!!
        assertEquals(1_024, row.title.length)
        assertEquals(1_024, row.collection.length)
        assertEquals("Bach", row.composer)
    }

    @Test
    fun `columns follow the header, or the standard order without one`() {
        val reordered = IndexCsv.parse("path,title,composer\nsongs/a.mid,Air,Bach")
        assertEquals(IndexCsv.Row("", "Bach", "Air", "songs/a.mid"), reordered.lookup("songs/a.mid"))
        val headless = IndexCsv.parse("col,Some One,Title,1.0,x/y.mid")
        assertEquals("Some One", headless.lookup("x/y.mid")?.composer)
    }
}

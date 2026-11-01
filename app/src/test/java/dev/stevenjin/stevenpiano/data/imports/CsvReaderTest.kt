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

    // v1.10 — M27: the optional `sha256` column Steven's library pack writes (each file's SHA-256), so the app
    // knows a pack's pieces before it reads them; an index without it reads as it always has.

    private val a = "005bb905439454713df12f46d63ac6b884f7a37fbdd4e77f962aac102e7d363c"
    private val b = "358cc000dd00edaf6c683517e3f4f0cfc935bdf98f2b7880126a9e30607c7e2f"

    @Test
    fun `an index without the column reads as before, with no hash`() {
        val index = IndexCsv.parse("collection,composer,title,size_kb,path\npiano-midi.de,chopin,Nocturne,7.9,piano-midi.de/chopin/noct.mid\n")
        val row = index.lookup("piano-midi.de/chopin/noct.mid")!!
        assertEquals(IndexCsv.Row("piano-midi.de", "chopin", "Nocturne", "piano-midi.de/chopin/noct.mid"), row)
        assertNull(row.sha256)
        assertEquals(emptySet<String>(), index.sha256s())
    }

    @Test
    fun `the pack's column gives each row its hash, kept lower-case`() {
        val index = IndexCsv.parse(
            "collection,composer,title,size_kb,path,sha256\n" +
                "maestro,Bizet,Carmen Variations,41.4,maestro/Bizet/Carmen Variations.mid,$a\n" +
                "\"piano-midi.de\",chopin,\"Nocturne, Op. 9\",7.9,piano-midi.de/chopin/noct.mid,${b.uppercase()}\n",
        )
        assertEquals(a, index.lookup("maestro/Bizet/Carmen Variations.mid")!!.sha256)
        assertEquals("the path's case never mattered", a, index.lookup("MAESTRO/bizet/carmen variations.mid")!!.sha256)
        assertEquals(b, index.lookup("piano-midi.de/chopin/noct.mid")!!.sha256)
        assertEquals("Nocturne, Op. 9", index.lookup("piano-midi.de/chopin/noct.mid")!!.title)
        assertEquals(setOf(a, b), index.sha256s())
    }

    @Test
    fun `a value that is not 64 hex digits is no hash, and the row still reads`() {
        val index = IndexCsv.parse(
            "path,sha256,title\n" +
                "one.mid,${a.dropLast(1)},One\n" +
                "two.mid,${a}0,Two\n" +
                "three.mid,${a.replaceFirst('0', 'g')},Three\n" +
                "four.mid,,Four\n" +
                "five.mid, $b ,Five\n",
        )
        for (name in listOf("one", "two", "three", "four")) assertNull(name, index.lookup("$name.mid")!!.sha256)
        assertEquals("One", index.lookup("one.mid")!!.title)
        assertEquals("found by its header name, wherever it stands; spaces trimmed", b, index.lookup("five.mid")!!.sha256)
        assertEquals(setOf(b), index.sha256s())
    }

    @Test
    fun `without a header the hash is the sixth column`() {
        val index = IndexCsv.parse("maestro,Bizet,Carmen,41.4,maestro/carmen.mid,$a\nmaestro,Bizet,Habanera,3.1,maestro/habanera.mid\n")
        assertEquals(a, index.lookup("maestro/carmen.mid")!!.sha256)
        assertNull(index.lookup("maestro/habanera.mid")!!.sha256)
        assertEquals(2, index.size)
    }

    // v1.41: the optional `replaces` column (the SHA-256s of earlier versions of a row's file, apart by spaces), so a
    // new version takes its old one's place in the library; an index without it reads as it always has.

    @Test
    fun `the replaces column gives a row its earlier versions' hashes, kept lower-case`() {
        val index = IndexCsv.parse(
            "collection,composer,title,size_kb,path,replaces\n" +
                "Pop,Coldplay,Viva la Vida,12.0,modern/Coldplay/Viva la Vida.mid,$a\n" +
                "Pop,Adele,\"Hello, Again\",9.5,modern/Adele/Hello.mid,${b.uppercase()} $a\n" +
                "Pop,Adele,Skyfall,8.1,modern/Adele/Skyfall.mid,\n",
        )
        assertEquals(setOf(a), index.lookup("modern/Coldplay/Viva la Vida.mid")!!.replaces)
        assertEquals("in the field's order", listOf(b, a), index.lookup("modern/Adele/Hello.mid")!!.replaces.toList())
        assertEquals("Hello, Again", index.lookup("modern/Adele/Hello.mid")!!.title)
        assertEquals("an empty field names none", emptySet<String>(), index.lookup("modern/Adele/Skyfall.mid")!!.replaces)
        assertNull("no hash of the row's own file", index.lookup("modern/Coldplay/Viva la Vida.mid")!!.sha256)
        assertEquals(emptySet<String>(), index.sha256s())
    }

    @Test
    fun `an index without the column reads exactly as before, naming no earlier versions`() {
        val row = IndexCsv.parse("collection,composer,title,size_kb,path\nPop,Coldplay,Yellow,4.2,modern/Coldplay/Yellow.mid\n")
            .lookup("modern/Coldplay/Yellow.mid")!!
        assertEquals(IndexCsv.Row("Pop", "Coldplay", "Yellow", "modern/Coldplay/Yellow.mid"), row)
        assertEquals(emptySet<String>(), row.replaces)
        assertEquals(IndexCsv.Row("", "", "", "a.mid", sha256 = a), IndexCsv.parse("path,sha256\na.mid,$a\n").lookup("a.mid"))
    }

    @Test
    fun `a value in replaces that is not 64 hex digits is left out, and the rest of the row reads`() {
        val index = IndexCsv.parse(
            "path,replaces,title\n" +
                "one.mid,${a.dropLast(1)},One\n" +
                "two.mid,${a}0 $b,Two\n" +
                "three.mid,${a.replaceFirst('0', 'g')},Three\n" +
                "four.mid,\"$a,$b\",Four\n" +
                "five.mid,  $a   $b ,Five\n" +
                "six.mid,\"$b\t$a\",Six\n" +
                "seven.mid,sha256:$a,Seven\n",
        )
        for (name in listOf("one", "three", "four", "seven")) assertEquals(name, emptySet<String>(), index.lookup("$name.mid")!!.replaces)
        assertEquals("One", index.lookup("one.mid")!!.title)
        assertEquals("the good value stays", setOf(b), index.lookup("two.mid")!!.replaces)
        assertEquals("spaces before, after and between", setOf(a, b), index.lookup("five.mid")!!.replaces)
        assertEquals("a tab parts them too", setOf(a, b), index.lookup("six.mid")!!.replaces)
    }

    @Test
    fun `without a header there is no replaces column`() {
        val row = IndexCsv.parse("Pop,Adele,Hello,9.5,modern/hello.mid,$a,$b\n").lookup("modern/hello.mid")!!
        assertEquals(a, row.sha256)
        assertEquals(emptySet<String>(), row.replaces)
    }
}

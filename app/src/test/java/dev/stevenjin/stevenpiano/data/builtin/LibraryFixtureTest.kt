// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.builtin

import dev.stevenjin.stevenpiano.data.imports.CsvReader
import dev.stevenjin.stevenpiano.data.imports.IndexCsv
import dev.stevenjin.stevenpiano.data.imports.TitleHeuristics
import dev.stevenjin.stevenpiano.data.imports.ZipSource
import dev.stevenjin.stevenpiano.midi.SmfParser
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The library fixtures are what the importer makes of Steven's library: `library_titles.csv`, for
 * every INDEX.csv row in order, the collection and composer it gives, the title after the
 * importer's rules (a file stub gives way to Track 0's name) and the file's note count and length;
 * `library_titles_all_songs.csv`, the same for `ALL-SONGS.zip`. Skipped unless Gradle runs with
 * `-Pcorpus` (the library is outside this repository), as `CorpusTest`.
 */
class LibraryFixtureTest {
    @Test
    fun `library_titles_csv is what the importer makes of the corpus`() {
        val root = System.getProperty("stevenpiano.corpus")
        assumeTrue("Run with -Pcorpus to check the fixture against the MIDI library", root != null)
        val dir = File(root!!)
        val indexText = File(dir, IndexCsv.FILE_NAME).readText()
        val index = IndexCsv.parse(indexText)
        val expected = CsvReader.parse(indexText).drop(1).filter { it.size >= 5 }.map { cells ->
            val file = File(dir, cells[4])
            val midi = SmfParser.parse(file.readBytes())
            val meta = TitleHeuristics.metadata(file.name, index.lookup(cells[4]), midi.sequenceNames)
            listOf(meta.collection.orEmpty(), meta.composer, meta.title, midi.noteCount.toString(), (midi.durationMicros / 1000).toString())
        }
        val fixture = LibraryFixture.rows("library_titles.csv").map { listOf(it["collection"], it["composer"], it["title"], it["notes"], it["durationMs"]) }
        assertEquals(expected.size, fixture.size)
        expected.zip(fixture).forEachIndexed { i, (made, kept) -> assertEquals("row ${i + 1}", made, kept) }
    }

    /**
     * `library_titles_all_songs.csv` is what the importer makes of `ALL-SONGS.zip`: its pieces in
     * the order of their paths, each file once (a copy of the same bytes is skipped, as on import),
     * named from its file name, with its note count and length.
     */
    @Test
    fun `library_titles_all_songs_csv is what the importer makes of ALL-SONGS_zip`() {
        val root = System.getProperty("stevenpiano.corpus")
        assumeTrue("Run with -Pcorpus to check the fixture against the MIDI library", root != null)
        val zip = File(root!!, "ALL-SONGS.zip")
        assumeTrue(zip.isFile)
        val seen = HashSet<String>()
        val expected = ZipSource(zip).use { source ->
            source.items().sortedBy { it.relativePath }.mapNotNull { item ->
                val bytes = item.open().use { it.readBytes() }
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                if (!seen.add(sha)) return@mapNotNull null
                val midi = SmfParser.parse(bytes)
                val meta = TitleHeuristics.metadata(item.name, null, midi.sequenceNames)
                listOf(meta.collection.orEmpty(), meta.composer, meta.title, midi.noteCount.toString(), (midi.durationMicros / 1000).toString())
            }
        }
        val fixture = LibraryFixture.rows("library_titles_all_songs.csv").map { listOf(it["collection"], it["composer"], it["title"], it["notes"], it["durationMs"]) }
        assertEquals(expected.size, fixture.size)
        expected.zip(fixture).forEachIndexed { i, (made, kept) -> assertEquals("row ${i + 1}", made, kept) }
    }
}

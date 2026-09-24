// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.midi

import dev.stevenjin.stevenpiano.data.imports.ComposerNames
import dev.stevenjin.stevenpiano.data.imports.CsvReader
import dev.stevenjin.stevenpiano.data.imports.IndexCsv
import dev.stevenjin.stevenpiano.data.imports.TitleHeuristics
import dev.stevenjin.stevenpiano.data.imports.ZipSource
import dev.stevenjin.stevenpiano.data.imports.isMidiName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Parses the whole MIDI library. Skipped unless Gradle runs with `-Pcorpus`
 * (the library is outside this repository and takes a few seconds).
 */
class CorpusTest {

    private fun corpus(): File {
        val root = System.getProperty("stevenpiano.corpus")
        assumeTrue("Run with -Pcorpus to parse the MIDI library", root != null)
        return File(root!!)
    }

    private fun midiFiles(root: File) = root.walk().filter { it.isFile && isMidiName(it.name) }.sortedBy { it.path }.toList()

    @Test
    fun `every file in the library parses, and only the damaged Borodin warns`() {
        val root = corpus()
        val files = midiFiles(root)
        assertTrue("No MIDI files under $root", files.isNotEmpty())

        val failed = mutableListOf<String>()
        val silent = mutableListOf<String>()
        val warned = mutableMapOf<File, List<String>>()
        for (file in files) {
            try {
                val piece = SmfParser.parse(file.readBytes())
                if (piece.warnings.isNotEmpty()) warned[file] = piece.warnings
                if (piece.noteCount == 0) silent += file.path
            } catch (e: SmfException) {
                failed += "${file.path}: ${e.message}"
            }
        }
        println("Corpus: ${files.size} files parsed, ${failed.size} failed, ${warned.size} with warnings")
        warned.forEach { (file, warnings) -> println("  warning ${file.path}: $warnings") }

        assertEquals(emptyList<String>(), failed)
        assertEquals(emptyList<String>(), silent)
        val truncated = File(root, "piano-midi.de/borodin/bor_ps5.mid")
        if (truncated.isFile) {
            assertTrue("bor_ps5.mid should warn", truncated in warned)
            val sha = truncated.sha256()
            warned.keys.forEach { assertEquals("Unexpected warnings in ${it.path}: ${warned[it]}", sha, it.sha256()) }
        }
    }

    @Test
    fun `INDEX csv lists every collection file, and the zip every piece`() {
        val root = corpus()
        val indexFile = File(root, IndexCsv.FILE_NAME)
        assumeTrue(indexFile.isFile)
        val text = indexFile.readText()
        val rows = CsvReader.parse(text).drop(1).filter { it.size >= 5 }
        assertEquals(rows.size, IndexCsv.parse(text).size)
        rows.forEach { assertTrue("INDEX.csv lists a missing file: ${it[4]}", File(root, it[4]).isFile) }
        val zipFile = File(root, "ALL-SONGS.zip")
        if (zipFile.isFile) {
            ZipSource(zipFile).use { zip ->
                val items = zip.items()
                val folder = File(root, "ALL SONGS").listFiles { f -> isMidiName(f.name) }.orEmpty()
                println("Corpus: INDEX.csv ${rows.size} rows; ALL-SONGS.zip ${items.size} pieces")
                assertEquals(folder.size, items.size)
                assertTrue(items.any { "Frédéric Chopin" in it.name })
            }
        }
    }

    @Test
    fun `the importer's names for the library come out clean`() {
        val root = corpus()
        val indexFile = File(root, IndexCsv.FILE_NAME)
        val index = if (indexFile.isFile) IndexCsv.parse(indexFile.readText()) else null
        var stubs = 0
        val mojibake = mutableListOf<String>()
        val keys = mutableSetOf<String>()
        val files = midiFiles(root)
        for (file in files) {
            val midi = SmfParser.parse(file.readBytes())
            val meta = TitleHeuristics.metadata(file.name, index?.lookup(file.relativeTo(root).path), midi.sequenceNames)
            val name = ComposerNames.normalize(meta.composer)
            if (TitleHeuristics.isStub(meta.title)) stubs++
            if ("Ã" in meta.title + name.display) mojibake += file.name
            keys += name.key
        }
        println("Corpus: ${files.size} titles, $stubs still stubs, ${keys.size} composer groups")
        assertEquals(emptyList<String>(), mojibake)
    }

    private fun File.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(readBytes()).joinToString("") { "%02x".format(it) }
}

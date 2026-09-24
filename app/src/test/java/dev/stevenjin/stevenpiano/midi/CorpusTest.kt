// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.midi

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

    @Test
    fun `every file in the library parses, and only the truncated Borodin warns`() {
        val root = System.getProperty("stevenpiano.corpus")
        assumeTrue("Run with -Pcorpus to parse the MIDI library", root != null)
        val files = File(root!!).walk()
            .filter { it.isFile && it.extension.lowercase() in setOf("mid", "midi") }
            .sortedBy { it.path }
            .toList()
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

    private fun File.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(readBytes()).joinToString("") { "%02x".format(it) }
}

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
import dev.stevenjin.stevenpiano.score.Fingering
import dev.stevenjin.stevenpiano.score.Hands
import dev.stevenjin.stevenpiano.score.ScoreLayoutEngine
import dev.stevenjin.stevenpiano.score.ScoreMetrics
import dev.stevenjin.stevenpiano.score.ScoreWidth
import dev.stevenjin.stevenpiano.score.TempoMarks
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

    /**
     * Every file's events, notes, length and warnings, hashed as the M12 run compared them and
     * combined over the whole library: the parser before M12, after M12 and after the v1.2 audit's
     * hardening (compact event arrays, caps) must read the library byte for byte alike.
     */
    @Test
    fun `every file parses exactly as the parser before M12 read it`() {
        val root = corpus()
        val files = midiFiles(root)
        val combined = MessageDigest.getInstance("SHA-256")
        for (file in files) {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = try {
                val p = SmfParser.parse(file.readBytes())
                for (e in p.events) md.update("${e.atMicros} ${e.status} ${e.data1} ${e.data2}\n".toByteArray())
                for (i in 0 until p.notes.size) md.update("${p.notes.startMicros[i]} ${p.notes.endMicros[i]} ${p.notes.note(i)}\n".toByteArray())
                md.update("${p.durationMicros} ${p.warnings}".toByteArray())
                md.digest().joinToString("") { "%02x".format(it) }
            } catch (e: SmfException) {
                "FAIL ${e.message}"
            }
            combined.update("${file.relativeTo(root).path} $digest\n".toByteArray())
        }
        val hex = combined.digest().joinToString("") { "%02x".format(it) }
        println("Corpus digest: ${files.size} files, $hex")
        if (files.size == CORPUS_FILES) assertEquals(CORPUS_DIGEST, hex)
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

    /**
     * Every file is analysed for the waterfall format (DESIGN.md › v1.3 › The waterfall format: hands,
     * suggested fingering) without an exception, each part timed on its own, and laid out as a score
     * with its hands and fingering on a phone's panel and a tablet's.
     */
    @Test
    fun `every file in the library is analysed and lays out as a score, on a phone and on a tablet`() {
        val root = corpus()
        val files = midiFiles(root)
        // A phone's score panel (411 x 600 dp, one page) and a tablet's on its side (1280 x 700 dp, two pages).
        val panels = listOf(
            panel(ScoreWidth.COMPACT, 411f, 600f, 2.625f),
            panel(ScoreWidth.EXPANDED, 1_280f, 700f, 2f),
        )
        assertEquals(listOf(1, 2), panels.map { it.pages })
        val quantized = mutableMapOf<String, Int>()
        val total = mutableMapOf<String, Int>()
        var notes = 0L
        var bars = 0L
        var tied = 0L
        var beams = 0L
        var rests = 0L
        var ties = 0L
        var tempoMarks = 0L
        var dynamics = 0L
        var right = 0L
        var fingered = 0L
        var numerals = 0L
        var handsNanos = 0L
        var fingersNanos = 0L
        var layoutNanos = 0L
        var mostNotes = 0
        val started = System.nanoTime()
        for (file in files) {
            val piece = SmfParser.parse(file.readBytes())
            val keys = IntArray(piece.notes.size) { KeyMap.map(piece.notes.note(it), 0, true) }
            val path = file.path
            var t0 = System.nanoTime()
            val hands = Hands.assign(piece.notes, piece.trackNames, piece.tempoMap, piece.timeSignatures)
            handsNanos += System.nanoTime() - t0
            t0 = System.nanoTime()
            val fingers = Fingering.assign(piece.notes, hands)
            fingersNanos += System.nanoTime() - t0
            assertEquals(path, piece.notes.size, hands.size)
            assertEquals(path, piece.notes.size, fingers.size)
            assertTrue(path, hands.all { it == Hands.RIGHT || it == Hands.LEFT })
            assertTrue(path, fingers.all { it in 0..5 })
            assertTrue(path, (0 until piece.notes.size).all { piece.notes.track(it) < piece.trackNames.size })
            right += hands.count { it == Hands.RIGHT }
            fingered += fingers.count { it > 0 }
            mostNotes = maxOf(mostNotes, piece.notes.size)
            val collection = file.relativeTo(root).path.substringBefore(File.separator)
            total.merge(collection, 1, Int::plus)
            t0 = System.nanoTime()
            for (metrics in panels) {
                val score = ScoreLayoutEngine.layout(
                    piece.notes, keys, piece.tempoMap, piece.barStartsMicros, piece.keySignatures, metrics, piece.timeSignatures, hands, fingers,
                )
                val where = "${file.path} on ${metrics.pages} page(s)"
                assertEquals(where, (piece.barStartsMicros.size + metrics.barsPerSystem - 1) / metrics.barsPerSystem, score.systems.size)
                for (i in 0 until score.noteCount) {
                    val system = score.systems[score.system[i]]
                    assertTrue(where, i in system.firstNote until system.noteEnd)
                    assertTrue(where, score.x[i] >= system.left && score.x[i] <= metrics.pageWidth)
                }
                // M13's engraving: tied heads in their systems' runs, and every mark finite and on its page.
                for (h in score.noteCount until score.headCount) {
                    val system = score.systems[score.system[h]]
                    assertTrue(where, h in system.firstTied until system.tiedEnd)
                    assertTrue(where, score.x[h] >= system.left && score.x[h] <= metrics.pageWidth)
                }
                fun onPage(x: Float) = x.isFinite() && x >= 0f && x <= metrics.pageWidth
                for (k in 0 until score.beams.size) assertTrue(where, onPage(score.beams.x1[k]) && onPage(score.beams.x2[k]) && score.beams.y1[k].isFinite() && score.beams.y2[k].isFinite())
                for (k in 0 until score.rests.size) assertTrue(where, onPage(score.rests.x[k]) && score.rests.y[k].isFinite())
                for (k in 0 until score.ties.size) assertTrue(where, onPage(score.ties.x1[k]) && onPage(score.ties.x2[k]) && score.ties.x2[k] >= score.ties.x1[k])
                assertEquals(where, 0, score.tempoMarks.firstOrNull()?.system)
                for (mark in score.tempoMarks) assertTrue(where, onPage(mark.x) && mark.bpm in 1..TempoMarks.MAX_BPM)
                for (mark in score.dynamics) assertTrue(where, onPage(mark.x) && mark.y.isFinite() && mark.system == mark.bar / metrics.barsPerSystem)
                for (k in 0 until score.fingers.size) {
                    assertTrue(where, onPage(score.fingers.x[k]) && score.fingers.baseline[k].isFinite() && score.fingers.finger[k] in 1..5)
                    assertEquals(where, score.system[score.fingers.note[k]], score.fingers.system[k])
                }
                if (metrics === panels[0]) {
                    if (score.quantized) quantized.merge(collection, 1, Int::plus)
                    notes += score.noteCount
                    bars += score.bars.count
                    tied += score.headCount - score.noteCount
                    beams += (0 until score.beams.size).count { score.beams.level[it].toInt() == 1 }
                    rests += score.rests.size
                    ties += score.ties.size
                    tempoMarks += score.tempoMarks.size
                    dynamics += score.dynamics.size
                    numerals += score.fingers.size
                }
            }
            layoutNanos += System.nanoTime() - t0
        }
        val seconds = (System.nanoTime() - started) / 1e9
        val sequenced = quantized.values.sum()
        println(
            "Corpus score: ${files.size} files ($notes notes, $bars bars) laid out on both panels in %.1f s; ".format(seconds) +
                "quantised (note values) %d = %.1f %%".format(sequenced, 100.0 * sequenced / files.size),
        )
        println(
            "Corpus engraving (phone panel): $tied tied heads, $ties ties, $beams beamed groups, $rests rests, " +
                "$tempoMarks tempo marks, $dynamics dynamics",
        )
        total.keys.sorted().forEach { println("  $it: ${quantized[it] ?: 0} of ${total[it]} quantised") }
        println(
            "Corpus analysis: hands %.1f s (%.1f %% right hand), fingering %.1f s (%.1f %% of notes fingered, %d numerals on the phone panel), layout %.1f s; largest file %d notes"
                .format(handsNanos / 1e9, 100.0 * right / notes, fingersNanos / 1e9, 100.0 * fingered / notes, numerals, layoutNanos / 1e9, mostNotes),
        )
    }

    private fun panel(width: ScoreWidth, widthDp: Float, heightDp: Float, density: Float) =
        ScoreMetrics.forPanel(width, widthDp * density, heightDp * density, density, 1.18f * 6 * density, 2.74f * 6 * density)

    private fun File.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(readBytes()).joinToString("") { "%02x".format(it) }

    private companion object {
        /** The library as measured in September 2026 (../midi): 3,454 files, and what the parser made of them. */
        const val CORPUS_FILES = 3_454
        const val CORPUS_DIGEST = "7192757eebfe20200e9350eb40ee3673778de916e8ee2583bcda4d855b870cc5"
    }
}

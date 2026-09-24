// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.imports

import dev.stevenjin.stevenpiano.data.PieceFiles
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.data.db.named
import dev.stevenjin.stevenpiano.midi.SmfBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class ImporterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val store = FakeStore()
    private val progress = MutableStateFlow(ImportProgress.Idle)
    private val logs = mutableListOf<String>()
    private val importer by lazy { Importer(store, PieceFiles(tmp.root), progress, clock = { 1_000L }, log = { logs += it }) }

    private fun midi(key: Int, trackName: String? = null) = SmfBuilder(format = 0).track {
        trackName?.let { name(0, it) }
        noteOn(0, key)
        noteOff(480, key)
    }.build()

    private fun item(path: String, bytes: ByteArray) = ImportItem(path.substringAfterLast('/'), path) { ByteArrayInputStream(bytes) }

    @Test
    fun `imports and names pieces, skips duplicates, counts failures`() = runTest {
        val index = IndexCsv.parse(
            "collection,composer,title,size_kb,path\npiano-midi.de,mozart,mz_311_1,1,piano-midi.de/mozart/mz_311_1.mid\n",
        )
        val mozart = midi(60, "Klaviersonate Nr. 8 KV 311 1. Satz")
        val source = OpenedSource(
            listOf(
                item("ALL SONGS/Mozart - mz_311_1.mid", mozart),   // the same bytes as the indexed copy below
                item("ALL SONGS/Chopin - Nocturne.mid", midi(62)),
                item("ALL SONGS/broken.mid", "not a midi file".toByteArray()),
                item("piano-midi.de/mozart/mz_311_1.mid", mozart),
                item("ALL SONGS/Chopin - Nocturne (copy).mid", midi(62)),
            ),
            index,
        )
        val result = importer.run(source)
        assertEquals(ImportProgress(done = 5, total = 5, imported = 2, duplicates = 2, failed = 1, finished = true), result)
        assertEquals(result, progress.value)

        val piece = store.pieces.single { it.composerKey == "mozart" }
        assertEquals("Klaviersonate Nr. 8 KV 311 1. Satz", piece.title)
        assertEquals("Wolfgang Amadeus Mozart", piece.composer)
        assertEquals("piano-midi.de", piece.collection)   // the INDEX.csv copy went first
        assertEquals("piano-midi.de/mozart/mz_311_1.mid", piece.sourceName)

        val chopin = store.pieces.single { it.composerKey == "chopin" }
        assertEquals("Nocturne", chopin.title)
        assertEquals("Frédéric Chopin", chopin.composer)
        assertEquals("Chopin", chopin.composerShort)
        assertEquals("nocturne frederic chopin", chopin.searchText)
        assertEquals("nocturne", chopin.titleKey)
        assertEquals(500L, chopin.durationMs)
        assertEquals(1, chopin.noteCount)
        assertEquals(1_000L, chopin.addedAt)
        assertTrue(File(tmp.root, "pieces/${chopin.sha256}.mid").isFile)
        assertTrue(logs.any { "broken.mid" in it })
    }

    @Test
    fun `pieces are saved 25 per transaction`() = runTest {
        val result = importer.run(OpenedSource((0 until 60).map { item("f$it.mid", midi(24 + it)) }))
        assertEquals(60, result.imported)
        assertEquals(listOf(25, 25, 10), store.batchSizes)
    }

    @Test
    fun `a duplicate fills in a missing composer`() = runTest {
        val bytes = midi(60)
        importer.run(OpenedSource(listOf(item("Nocturne.mid", bytes))))
        assertEquals("", store.pieces.single().composer)
        val again = importer.run(OpenedSource(listOf(item("Chopin - Nocturne.mid", bytes))))
        assertEquals(1, again.duplicates)
        assertEquals("Frédéric Chopin", store.pieces.single().composer)
        assertEquals("chopin", store.pieces.single().composerKey)
    }

    @Test
    fun `files over 8 MB are refused`() = runTest {
        val huge = object : InputStream() {
            var left = 8L * 1024 * 1024 + 1

            override fun read(): Int = if (left-- > 0) 0 else -1

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt()
                left -= n
                return n
            }
        }
        val result = importer.run(OpenedSource(listOf(ImportItem("huge.mid", "huge.mid") { huge })))
        assertEquals(1, result.failed)
        assertTrue(store.pieces.isEmpty())
    }

    private class FakeStore : ImportStore {
        val pieces = mutableListOf<PieceEntity>()
        val batchSizes = mutableListOf<Int>()

        override suspend fun findBySha(sha256: String): PieceEntity? = pieces.firstOrNull { it.sha256 == sha256 }

        override suspend fun fillComposer(piece: PieceEntity, composer: ComposerNames.Name) {
            pieces[pieces.indexOfFirst { it.id == piece.id }] = piece.named(piece.title, composer)
        }

        override suspend fun insertAll(pieces: List<PieceEntity>): Int {
            batchSizes += pieces.size
            pieces.forEach { this.pieces += it.copy(id = this.pieces.size + 1L) }
            return pieces.size
        }
    }
}

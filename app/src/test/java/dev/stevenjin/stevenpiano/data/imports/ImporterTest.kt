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

    @Test
    fun `a 1 MB track name imports as a 200-character title, with its keys made from the title kept`() = runTest {
        val bytes = midi(60, trackName = "Grand Sonata " + "x".repeat(1_000_000))
        val result = importer.run(OpenedSource(listOf(item("a.mid", bytes))))   // a stub name: Track 0's name wins
        assertEquals(1, result.imported)
        val piece = store.pieces.single()
        assertEquals(200, piece.title.length)
        assertTrue(piece.title.startsWith("Grand Sonata xxx"))
        assertEquals(piece.title.lowercase(), piece.titleKey)
        assertTrue(piece.searchText.startsWith(piece.titleKey) && piece.searchText.length <= 400)
    }

    @Test
    fun `an INDEX csv row and a path of any length are cut to their caps`() = runTest {
        val path = "deep/".repeat(150) + "piece.mid"   // 759 characters: within a CSV field, past the 512 kept
        val index = IndexCsv.parse(
            "collection,composer,title,size_kb,path\n${"C".repeat(3_000)},${"Q".repeat(3_000)},${"T".repeat(3_000)},1,$path\n",
        )
        importer.run(OpenedSource(listOf(item(path, midi(60))), index))
        val piece = store.pieces.single()
        assertEquals(200, piece.title.length)
        assertEquals(120, piece.composer.length)
        assertEquals(120, piece.collection!!.length)
        assertEquals(512, piece.sourceName.length)
        assertTrue(piece.composerKey.length <= 120 && piece.composerShort.length <= 120)
    }

    @Test
    fun `a file too large for the memory left counts as failed, and the import goes on`() = runTest {
        val source = OpenedSource(
            listOf(
                ImportItem("huge.mid", "huge.mid") { throw OutOfMemoryError("Java heap space") },
                item("Chopin - Nocturne.mid", midi(62)),
            ),
        )
        val result = importer.run(source)
        assertEquals(1, result.failed)
        assertEquals(1, result.imported)
        assertTrue(logs.any { it == "huge.mid: File too large to read" })
    }

    @Test
    fun `a sender or a database that throws fails that file only, and the import finishes`() = runTest {
        val source = OpenedSource(
            listOf(
                ImportItem("evil.mid", "evil.mid") { throw IllegalStateException("the provider died") },
                ImportItem("denied.mid", "denied.mid") { throw SecurityException("no access") },
                item("Chopin - Nocturne.mid", midi(62)),
            ),
        )
        val result = importer.run(source)
        assertEquals(ImportProgress(done = 3, total = 3, imported = 1, failed = 2, finished = true), result)

        val broken = object : ImportStore {
            override suspend fun findBySha(sha256: String): PieceEntity? = throw IllegalStateException("database disk image is malformed")
            override suspend fun fillComposer(piece: PieceEntity, composer: ComposerNames.Name) = Unit
            override suspend fun insertAll(pieces: List<PieceEntity>): Int = pieces.size
            override suspend fun hasComposerKey(composerKey: String): Boolean = false
        }
        val again = Importer(broken, PieceFiles(tmp.root), progress, clock = { 1_000L }, log = { logs += it })
            .run(OpenedSource(listOf(item("a.mid", midi(60)), item("b.mid", midi(61)))))
        assertEquals(2, again.failed)
        assertTrue(again.finished)
    }

    @Test
    fun `an opened source imports under the same lock and caps, and is closed at the end`() = runTest {
        var closed = 0
        val source = OpenedSource(listOf(item("Chopin - Nocturne.mid", midi(62)), item("broken.mid", "no".toByteArray())), release = { closed++ })
        val result = importer.importOpened(source)
        assertEquals(ImportProgress(done = 2, total = 2, imported = 1, failed = 1, finished = true), result)
        assertEquals(result, progress.value)
        assertEquals("the source is closed once read", 1, closed)
        assertEquals("Nocturne", store.pieces.single().title)
        val again = importer.importOpened(OpenedSource(listOf(item("Chopin - Nocturne.mid", midi(62))), release = { closed++ }))
        assertEquals("the same bytes again are a duplicate", 1, again.duplicates)
        assertEquals(2, closed)
    }

    @Test
    fun `an opened source whose listing throws still finishes, closed, with the rest counted as failed`() = runTest {
        var closed = false
        val bad = object : AbstractList<ImportItem>() {
            override val size: Int get() = 2

            override fun get(index: Int): ImportItem = throw IllegalStateException("a listing that breaks")
        }
        val result = importer.importOpened(OpenedSource(bad, release = { closed = true }))
        assertTrue(result.finished)
        assertTrue(closed)
    }

    @Test
    fun `a zip the web panel saved imports through importOpened and is deleted once read`() = runTest {
        val web = tmp.newFolder("cache", "web")
        val zip = File(web, "upload-1.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { out ->
            for ((name, key) in listOf("Satie - Gymnopedie 1.mid" to 60, "folder/Chopin - Waltz.mid" to 62)) {
                out.putNextEntry(java.util.zip.ZipEntry(name))
                out.write(midi(key))
                out.closeEntry()
            }
            out.putNextEntry(java.util.zip.ZipEntry("notes.txt"))
            out.write("not music".toByteArray())
            out.closeEntry()
        }
        val source = ZipSource(zip, deleteWhenClosed = true)
        val result = importer.importOpened(OpenedSource(source.items(), source.readIndex(), source.indexBase, source::close))
        assertEquals(2, result.imported)
        assertEquals(setOf("Gymnopedie 1", "Waltz"), store.pieces.map { it.title }.toSet())
        assertTrue("the upload's copy is gone once read", !zip.exists())
    }

    // v1.10.1 — M28, D1 and D3.

    @Test
    fun `a zip's artist folders name its pieces, a reversed name reads the right way, and a Mac's extras are not counted`() = runTest {
        val source = OpenedSource(
            listOf(
                item("MIDI/Coldplay/Sparks.mid", midi(60)),
                item("MIDI/Hans Zimmer/Time.mid", midi(61)),
                item("MIDI/Cornfield Chase - Hans Zimmer (version 2).mid", midi(62)),
                item("MIDI/Claude Debussy/Clair de Lune.mid", midi(63)),
                item("MIDI/Stay - Interstellar.mid", midi(64)),
                item("MIDI/Lady Gaga & Bradley Cooper/I'll Never Love Again.mid", midi(65)),
                item("__MACOSX/MIDI/Coldplay/._Sparks.mid", "a resource fork".toByteArray()),
                item("MIDI/Coldplay/._Sparks.mid", "a resource fork".toByteArray()),
            ),
        )
        val result = importer.run(source)
        assertEquals("the Mac's two files are neither pieces nor failures", ImportProgress(done = 6, total = 6, imported = 6, finished = true), result)
        fun piece(title: String) = store.pieces.single { it.title == title }
        assertEquals(listOf("Coldplay", "Coldplay", "coldplay"), piece("Sparks").let { listOf(it.composer, it.composerShort, it.composerKey) })
        assertEquals("sparks coldplay", piece("Sparks").searchText)
        assertEquals("hans zimmer", piece("Time").composerKey)
        assertEquals("reversed, the parenthetical in the title", "Hans Zimmer", piece("Cornfield Chase (version 2)").composer)
        assertEquals("hans zimmer", piece("Cornfield Chase (version 2)").composerKey)
        assertEquals("a canonical composer's folder joins the composer", "debussy", piece("Clair de Lune").composerKey)
        assertEquals("neither side known: as before", "Stay", piece("Interstellar").composer)
        assertEquals("Lady Gaga & Bradley Cooper", piece("I'll Never Love Again").composerShort)
        assertTrue(logs.none { "._Sparks" in it })
    }

    @Test
    fun `a composer read from a file name joins an artist the library already has by the whole name`() = runTest {
        importer.run(OpenedSource(listOf(item("MIDI/Ed Sheeran/Perfect.mid", midi(60)), item("MIDI/Coldplay/Yellow.mid", midi(64)))))
        importer.run(OpenedSource(listOf(item("Ed Sheeran - Shivers.mid", midi(61)), item("John Smith - Song.mid", midi(62)), item("Chopin - Nocturne.mid", midi(63)))))
        val shivers = store.pieces.single { it.title == "Shivers" }
        assertEquals(listOf("Ed Sheeran", "Ed Sheeran", "ed sheeran"), listOf(shivers.composer, shivers.composerShort, shivers.composerKey))
        assertEquals("no artist of that name: as before", "smith", store.pieces.single { it.title == "Song" }.composerKey)
        assertEquals("chopin", store.pieces.single { it.title == "Nocturne" }.composerKey)
        // The same import's folders count as the library: an Ed Sheeran folder beside a dash name.
        importer.run(OpenedSource(listOf(item("Pop/Adele/Hello.mid", midi(65)), item("Pop/Adele - Skyfall.mid", midi(66)))))
        assertEquals("adele", store.pieces.single { it.title == "Skyfall" }.composerKey)
    }

    @Test
    fun `a duplicate's blank composer is filled from its artist folder`() = runTest {
        val bytes = midi(60)
        importer.run(OpenedSource(listOf(item("Sparks.mid", bytes))))
        assertEquals("", store.pieces.single().composer)
        val again = importer.run(OpenedSource(listOf(item("MIDI/Coldplay/Sparks.mid", bytes), item("MIDI/Adele/Hello.mid", midi(61)))))
        assertEquals(1, again.duplicates)
        assertEquals(listOf("Coldplay", "coldplay"), store.pieces.first().let { listOf(it.composer, it.composerKey) })
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

        override suspend fun hasComposerKey(composerKey: String): Boolean = pieces.any { it.composerKey == composerKey }
    }
}

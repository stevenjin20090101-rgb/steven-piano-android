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
import dev.stevenjin.stevenpiano.update.VerifiedDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A zip the app saved itself (v1.10 — M27, `ImportSource.LocalZip`): Steven's library pack, read where it
 * lies with every zip's caps, its pieces less those an earlier pack offered, deleted when the import is
 * done; through the importer as the library's load runs it.
 */
class LocalZipTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun midi(key: Int) = SmfBuilder(format = 0).track {
        noteOn(0, key)
        noteOff(480, key)
    }.build()

    private fun sha(bytes: ByteArray) = VerifiedDownloader.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private val carmen = midi(60)
    private val nocturne = midi(62)
    private val gnossienne = midi(64)
    private val loose = midi(66)

    /** A pack as tools/publish_library.py writes it: INDEX.csv with its sha256 column, the files, the README and a licence. */
    private fun pack(name: String = "library-v2.zip", index: String? = null, extra: List<Pair<String, ByteArray>> = emptyList()): File {
        val file = File(tmp.newFolder(), name)
        val rows = index ?: (
            "collection,composer,title,size_kb,path,sha256\n" +
                "maestro,Georges Bizet,Carmen Variations,1,maestro/Bizet/Carmen Variations.mid,${sha(carmen)}\n" +
                "piano-midi.de,chopin,Nocturne,1,piano-midi.de/chopin/noct.mid,${sha(nocturne)}\n" +
                "mutopia,Satie,Gnossienne No. 1,1,mutopia/gnossienne.mid,${sha(gnossienne)}\n"
            )
        ZipOutputStream(file.outputStream()).use { out ->
            for ((path, bytes) in listOf(
                "INDEX.csv" to rows.toByteArray(),
                "README.md" to "# MIDI library".toByteArray(),
                "_maestro-metadata/LICENSE" to "Attribution-NonCommercial-ShareAlike 4.0 International".toByteArray(),
                "maestro/Bizet/Carmen Variations.mid" to carmen,
                "piano-midi.de/chopin/noct.mid" to nocturne,
                "mutopia/gnossienne.mid" to gnossienne,
            ) + extra) {
                out.putNextEntry(ZipEntry(path))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `the pack is read where it lies, its MIDI files and index, and deleted when closed`() {
        val file = pack()
        val opened = openLocalZip(ImportSource.LocalZip(file))
        assertEquals(listOf("maestro/Bizet/Carmen Variations.mid", "piano-midi.de/chopin/noct.mid", "mutopia/gnossienne.mid"), opened.items.map { it.relativePath })
        assertEquals("Carmen Variations", opened.rowFor(opened.items.first())!!.title)
        assertEquals(listOf(file.name), file.parentFile!!.list()!!.toList())   // never copied
        opened.close()
        assertFalse(file.exists())
    }

    @Test
    fun `pieces an earlier pack offered are left out, and a file with no hash in the index is always read`() {
        val file = pack(extra = listOf("extras/loose.mid" to loose))
        val opened = openLocalZip(ImportSource.LocalZip(file, skipShas = setOf(sha(carmen), sha(gnossienne), sha(loose))))
        assertEquals(
            "the loose file has no row, so no hash to skip it by: the importer's own hash keeps one copy of it",
            listOf("piano-midi.de/chopin/noct.mid", "extras/loose.mid"),
            opened.items.map { it.relativePath },
        )
        opened.close()
        assertFalse(file.exists())
    }

    @Test
    fun `an index without the column leaves nothing out`() {
        val file = pack(index = "collection,composer,title,size_kb,path\nmaestro,Bizet,Carmen,1,maestro/Bizet/Carmen Variations.mid\n")
        openLocalZip(ImportSource.LocalZip(file, skipShas = setOf(sha(carmen)))).use { assertEquals(3, it.items.size) }
    }

    @Test
    fun `a file that is not a zip is refused and left for the caller`() {
        val file = File(tmp.newFolder(), "library-v1.zip").apply { writeText("not a zip") }
        try {
            openLocalZip(ImportSource.LocalZip(file))
            fail("a file that is not a zip opened")
        } catch (e: IOException) {
            assertTrue(file.exists())
        }
    }

    @Test
    fun `the importer brings in the new pieces only, names them from the index, and the zip is gone after`() = runTest {
        val store = Store()
        val progress = MutableStateFlow(ImportProgress.Idle)
        val importer = Importer(store, PieceFiles(tmp.newFolder()), progress, clock = { 1_000L }, log = {})
        val first = importer.importOpened(openLocalZip(ImportSource.LocalZip(pack("library-v1.zip"))))
        assertEquals(ImportProgress(done = 3, total = 3, imported = 3, finished = true), first)
        assertEquals(setOf("Carmen Variations", "Nocturne", "Gnossienne No. 1"), store.pieces.map { it.title }.toSet())
        assertEquals(setOf("maestro", "piano-midi.de", "mutopia"), store.pieces.mapNotNull { it.collection }.toSet())

        // The person deletes the Nocturne; pack 2 adds a piece. Only the new one comes in; the Nocturne stays gone.
        store.pieces.removeAll { it.title == "Nocturne" }
        val offered = setOf(sha(carmen), sha(nocturne), sha(gnossienne))
        val v2 = pack(
            index = "collection,composer,title,size_kb,path,sha256\n" +
                "maestro,Georges Bizet,Carmen Variations,1,maestro/Bizet/Carmen Variations.mid,${sha(carmen)}\n" +
                "piano-midi.de,chopin,Nocturne,1,piano-midi.de/chopin/noct.mid,${sha(nocturne)}\n" +
                "mutopia,Satie,Gnossienne No. 1,1,mutopia/gnossienne.mid,${sha(gnossienne)}\n" +
                "mutopia,Satie,Gymnopedie No. 1,1,mutopia/gymnopedie.mid,${sha(loose)}\n",
            extra = listOf("mutopia/gymnopedie.mid" to loose),
        )
        val second = importer.importOpened(openLocalZip(ImportSource.LocalZip(v2, skipShas = offered)))
        assertEquals(ImportProgress(done = 1, total = 1, imported = 1, finished = true), second)
        assertEquals(setOf("Carmen Variations", "Gnossienne No. 1", "Gymnopedie No. 1"), store.pieces.map { it.title }.toSet())
        assertFalse(v2.exists())
    }

    private class Store : ImportStore {
        val pieces = mutableListOf<PieceEntity>()

        override suspend fun findBySha(sha256: String): PieceEntity? = pieces.firstOrNull { it.sha256 == sha256 }

        override suspend fun fillComposer(piece: PieceEntity, composer: ComposerNames.Name) {
            pieces[pieces.indexOfFirst { it.id == piece.id }] = piece.named(piece.title, composer)
        }

        override suspend fun insertAll(pieces: List<PieceEntity>): Int {
            pieces.forEach { this.pieces += it.copy(id = this.pieces.size + 1L) }
            return pieces.size
        }
    }
}

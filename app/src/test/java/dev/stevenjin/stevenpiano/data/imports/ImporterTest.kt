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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

class ImporterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val store = FakeStore()
    private val progress = MutableStateFlow(ImportProgress.Idle)
    private val logs = mutableListOf<String>()
    private val redrawn = mutableListOf<Long>()
    private val importer by lazy { Importer(store, PieceFiles(tmp.root), progress, clock = { 1_000L }, log = { logs += it }, replaced = { redrawn += it }) }

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
    fun `a cancelled import (Android's time for data sync ran out) finishes its progress where it stopped`() = runTest {
        lateinit var job: Job
        val items = (0 until 30).map { i ->
            ImportItem("f$i.mid", "f$i.mid") {
                if (i == 27) job.cancel()
                ByteArrayInputStream(midi(24 + i))
            }
        }
        job = launch { importer.importOpened(OpenedSource(items)) }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals("what was saved stays", listOf(25), store.batchSizes)
        assertEquals(ImportProgress(done = 28, total = 30, imported = 25, finished = true), progress.value)
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
            override suspend fun linkToPlaylist(name: String, imported: Boolean, shas: List<String>): ImportedPlaylist? = null
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
    fun `a quiet import leaves the shared progress at Idle and still returns its tally`() = runTest {
        val source = OpenedSource(listOf(item("Recording 2026-10-02 14-05-33.mid", midi(60)), item("broken.mid", "no".toByteArray())))
        val result = importer.importOpened(source, quiet = true)
        assertEquals(ImportProgress(done = 2, total = 2, imported = 1, failed = 1, finished = true), result)
        assertEquals("no bar, no panel line, no notification", ImportProgress.Idle, progress.value)
        assertEquals(1, store.pieces.size)
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

    // v1.10.1 — M28, D2: uploads become playlists.

    private fun titles(playlist: String) = store.playlists.getValue(playlist).map { id -> store.pieces.single { it.id == id }.title }

    @Test
    fun `a zip with a root folder becomes a playlist named after it, its pieces in path order`() = runTest {
        val source = OpenedSource(
            listOf(
                item("MIDI/Hans Zimmer/Time.mid", midi(60)),
                item("MIDI/Coldplay/Yellow.mid", midi(61)),
                item("MIDI/Cornfield Chase - Hans Zimmer.mid", midi(62)),
                item("MIDI/Coldplay/Clocks.mid", midi(63)),
                item("MIDI/adele/Hello.mid", midi(64)),
                item("MIDI/.DS_Store.mid", "not music".toByteArray()),
            ),
            batch = ImportBatch.zip("Steven's upload.zip"),
        )
        val result = importer.run(source)
        assertEquals(ImportedPlaylist(1, "MIDI"), result.playlist)
        assertEquals("folder by folder, then by title, case ignored", listOf("Hello", "Clocks", "Yellow", "Cornfield Chase", "Time"), titles("MIDI"))
        assertEquals(true, store.imported["MIDI"])
        assertTrue(logs.any { it.startsWith("Import: 5 new, 0 already there, 1 failed, in a playlist") })
    }

    @Test
    fun `path order goes folder by folder, numbers by their value, a name before the longer ones it begins`() {
        val paths = listOf(
            "MIDI/Erik Satie/Gymnopedie No. 10.mid", "MIDI/Coldplay/Fix You (Live).mid", "MIDI/Erik Satie/Gymnopedie No. 2.mid",
            "MIDI/Coldplay/Fix You.mid", "MIDI/Lady Gaga/Shallow.mid", "MIDI/Lady Gaga & Bradley Cooper/I'll Never Love Again.mid",
            "MIDI/Cornfield Chase - Hans Zimmer.mid", "MIDI/Émile/Étude.mid",
        )
        assertEquals(
            listOf(
                "MIDI/Coldplay/Fix You.mid", "MIDI/Coldplay/Fix You (Live).mid", "MIDI/Cornfield Chase - Hans Zimmer.mid", "MIDI/Émile/Étude.mid",
                "MIDI/Erik Satie/Gymnopedie No. 2.mid", "MIDI/Erik Satie/Gymnopedie No. 10.mid", "MIDI/Lady Gaga/Shallow.mid",
                "MIDI/Lady Gaga & Bradley Cooper/I'll Never Love Again.mid",
            ),
            paths.sortedWith(PathOrder),
        )
    }

    @Test
    fun `a zip without a root folder is named after the zip, and that name is cut to a playlist's`() = runTest {
        val mixed = OpenedSource(listOf(item("Coldplay/Yellow.mid", midi(61)), item("Adele/Hello.mid", midi(62)), item("Loose.mid", midi(63))), batch = ImportBatch.zip("Mixed Bag.ZIP"))
        assertEquals("Mixed Bag", importer.run(mixed).playlist?.name)
        assertEquals(listOf("Hello", "Yellow", "Loose"), titles("Mixed Bag"))
        val long = "L".repeat(300) + ".zip"
        assertEquals(120, importer.run(OpenedSource(listOf(item("x/a.mid", midi(64)), item("y/b.mid", midi(65))), batch = ImportBatch.zip(long))).playlist?.name?.length)
        assertEquals("the same zip again finds its playlist", "Mixed Bag", importer.run(OpenedSource(listOf(item("Adele/Skyfall.mid", midi(66)), item("Z/z.mid", midi(67))), batch = ImportBatch.zip("mixed bag.zip"))).playlist?.name)
        assertEquals(listOf("Hello", "Yellow", "Loose", "Skyfall", "z"), titles("Mixed Bag"))
    }

    @Test
    fun `pieces already there are linked too, their blank composers filled`() = runTest {
        val sparks = midi(60)
        importer.run(OpenedSource(listOf(item("Sparks.mid", sparks))))
        assertTrue("picked one by one: no playlist", store.playlists.isEmpty())
        val result = importer.run(OpenedSource(listOf(item("MIDI/Coldplay/Yellow.mid", midi(61)), item("MIDI/Coldplay/Sparks.mid", sparks), item("MIDI/Coldplay/Sparks (copy).mid", sparks)), batch = ImportBatch.zip("MIDI.zip")))
        assertEquals(ImportProgress(done = 3, total = 3, imported = 1, duplicates = 2, filled = 1, finished = true, playlist = ImportedPlaylist(1, "MIDI")), result)
        assertTrue(result.piecesChanged)
        assertEquals("each piece once, at its first place in path order", listOf("Sparks", "Yellow"), titles("MIDI"))
        assertEquals("coldplay", store.pieces.single { it.title == "Sparks" }.composerKey)
    }

    @Test
    fun `a piece an INDEX csv row places stays out of the zip's playlist, whatever copy of it the zip holds`() = runTest {
        val index = IndexCsv.parse("collection,composer,title,size_kb,path\npiano-midi.de,debussy,deb_clai,1,piano-midi.de/debussy/deb_clai.mid\n,Satie,Gnossienne 1,1,mutopia/gnossienne.mid\n")
        val clair = midi(60)
        val source = OpenedSource(
            listOf(
                item("ALL SONGS/Debussy - Clair de lune.mid", clair),
                item("piano-midi.de/debussy/deb_clai.mid", clair),
                item("ALL SONGS/Satie - Gymnopedie 1.mid", midi(61)),
                item("mutopia/gnossienne.mid", midi(62)),
            ),
            index,
            batch = ImportBatch.Named("midi"),
        )
        val result = importer.run(source)
        assertEquals(3, result.imported)
        assertEquals("piano-midi.de", store.pieces.single { it.composerKey == "debussy" }.collection)
        assertEquals("a row without a collection places nothing", listOf("Gymnopedie 1", "Gnossienne 1"), titles("midi"))
    }

    @Test
    fun `a loose file sent through the panel goes into Uploads, files picked one by one into no playlist`() = runTest {
        val first = importer.run(OpenedSource(listOf(item("Song.mid", midi(60))), batch = ImportBatch.Uploads))
        assertEquals(ImportedPlaylist(1, "Uploads"), first.playlist)
        assertEquals(false, store.imported["Uploads"])
        importer.run(OpenedSource(listOf(item("Another.mid", midi(61))), batch = ImportBatch.Uploads))
        assertEquals(listOf("Song", "Another"), titles("Uploads"))
        val picked = importer.run(OpenedSource(listOf(item("a.mid", midi(62)), item("b.mid", midi(63)))))
        assertEquals(null, picked.playlist)
        assertEquals(setOf("Uploads"), store.playlists.keys)
        val failed = importer.run(OpenedSource(listOf(item("broken.mid", "no".toByteArray())), batch = ImportBatch.Uploads))
        assertEquals("nothing arrived: no playlist", null, failed.playlist)
    }

    // v1.41: an INDEX csv row's `replaces` names earlier versions of its file; the new version takes the old one's place.

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun index(vararg rows: String) = IndexCsv.parse("collection,composer,title,size_kb,path,replaces\n" + rows.joinToString("\n") + "\n")

    /** Two notes over a second: longer and fuller than [midi]'s one note of 500 ms. */
    private val piano = SmfBuilder(format = 0).track {
        noteOn(0, 60)
        noteOn(0, 64)
        noteOff(960, 60)
        noteOff(960, 64)
    }.build()

    @Test
    fun `a new version takes its piece's place, keeping its id, favorite, plays and names, swapping its SHA and file`() = runTest {
        val band = midi(60)
        val path = "modern/Coldplay/Viva la Vida.mid"
        importer.run(OpenedSource(listOf(item(path, band)), index("Pop,Coldplay,Viva la Vida,1,$path,")))
        val before = store.pieces.single()
        store.pieces[0] = before.copy(favorite = true, playCount = 3, lastPlayedAt = 5_000L, title = "Viva la Vida (school choir)", genre = 1)
        val oldFile = File(tmp.root, "pieces/${sha(band)}.mid")
        assertTrue(oldFile.isFile)

        val result = importer.run(OpenedSource(listOf(item(path, piano)), index("Pop,Coldplay,Viva la Vida (piano),1,$path,${sha(band)}")))
        assertEquals(ImportProgress(done = 1, total = 1, replaced = 1, finished = true), result)
        assertTrue(result.piecesChanged)
        val after = store.pieces.single()
        assertEquals(before.id, after.id)
        assertEquals(listOf(true, 3, 5_000L), listOf(after.favorite, after.playCount, after.lastPlayedAt))
        assertEquals("the person's rename stays", "Viva la Vida (school choir)", after.title)
        assertEquals("a Classical/Modern move stays", 1, after.genre)
        assertEquals(before.addedAt, after.addedAt)
        assertEquals(sha(piano), after.sha256)
        assertEquals("${sha(piano)}.mid", after.fileName)
        assertEquals(piano.size.toLong(), after.sizeBytes)
        assertEquals("the new version's length and notes (the Calm channel's notes a second)", 1_000L, after.durationMs)
        assertEquals(2, after.noteCount)
        assertTrue(File(tmp.root, "pieces/${sha(piano)}.mid").readBytes().contentEquals(piano))
        assertFalse("the old version's file goes", oldFile.exists())
        assertEquals("it joins its row's playlist", listOf("Pop" to before.id), store.collectionLinks)
        assertEquals("its roll card is drawn again", listOf(before.id), redrawn)
        assertTrue(logs.any { it == "Import: 0 new, 1 replaced, 0 already there, 0 failed" })
    }

    @Test
    fun `replaces naming no piece the library holds imports a new piece`() = runTest {
        importer.run(OpenedSource(listOf(item("modern/Adele/Hello.mid", midi(61))), index("Pop,Adele,Hello,1,modern/Adele/Hello.mid,")))
        val result = importer.run(
            OpenedSource(listOf(item("modern/Adele/Skyfall.mid", midi(62))), index("Pop,Adele,Skyfall,1,modern/Adele/Skyfall.mid,${sha(midi(99))}")),
        )
        assertEquals(ImportProgress(done = 1, total = 1, imported = 1, finished = true), result)
        assertEquals(listOf("Hello", "Skyfall"), store.pieces.map { it.title })
        assertTrue(store.collectionLinks.isEmpty())
        assertTrue(redrawn.isEmpty())
    }

    @Test
    fun `an index without the column puts a new version beside the old one, as before`() = runTest {
        val band = midi(60)
        val old = IndexCsv.parse("collection,composer,title,size_kb,path\nPop,Coldplay,Yellow,1,modern/Coldplay/Yellow.mid\n")
        importer.run(OpenedSource(listOf(item("modern/Coldplay/Yellow.mid", band)), old))
        val result = importer.run(OpenedSource(listOf(item("modern/Coldplay/Yellow.mid", piano)), old))
        assertEquals(ImportProgress(done = 1, total = 1, imported = 1, finished = true), result)
        assertEquals(listOf(sha(band), sha(piano)), store.pieces.map { it.sha256 })
        assertTrue(File(tmp.root, "pieces/${sha(band)}.mid").isFile)
        assertEquals("Import: 1 new, 0 already there, 0 failed", logs.last())
    }

    @Test
    fun `a file the library has already stays a duplicate, whatever its row's replaces names`() = runTest {
        val yellow = midi(60)
        val clocks = midi(61)
        importer.run(
            OpenedSource(
                listOf(item("modern/Coldplay/Yellow.mid", yellow), item("modern/Coldplay/Clocks.mid", clocks)),
                index("Pop,Coldplay,Yellow,1,modern/Coldplay/Yellow.mid,", "Pop,Coldplay,Clocks,1,modern/Coldplay/Clocks.mid,"),
            ),
        )
        val before = store.pieces.toList()
        val result = importer.run(OpenedSource(listOf(item("modern/Coldplay/Yellow.mid", yellow)), index("Pop,Coldplay,Yellow,1,modern/Coldplay/Yellow.mid,${sha(clocks)}")))
        assertEquals(ImportProgress(done = 1, total = 1, duplicates = 1, finished = true), result)
        assertEquals("nothing changes", before, store.pieces)
        assertTrue(File(tmp.root, "pieces/${sha(clocks)}.mid").isFile)
        assertTrue(redrawn.isEmpty())
    }

    @Test
    fun `of several earlier versions the library holds, the first added takes the new file, and the rest stay`() = runTest {
        val first = midi(60)
        val second = midi(61)
        importer.run(OpenedSource(listOf(item("a/Hello.mid", first))))
        importer.run(OpenedSource(listOf(item("b/Hello.mid", second))))
        val (older, newer) = store.pieces.toList()
        val result = importer.run(OpenedSource(listOf(item("modern/Adele/Hello.mid", piano)), index("Pop,Adele,Hello,1,modern/Adele/Hello.mid,${sha(second)} ${sha(first)}")))
        assertEquals(1, result.replaced)
        assertEquals(sha(piano), store.pieces.single { it.id == older.id }.sha256)
        assertEquals("the later one stays as it was", newer, store.pieces.single { it.id == newer.id })
        assertTrue(File(tmp.root, "pieces/${sha(second)}.mid").isFile)
        assertFalse(File(tmp.root, "pieces/${sha(first)}.mid").exists())
    }

    @Test
    fun `a new version that is no MIDI file fails, and its piece stays as it was`() = runTest {
        val band = midi(60)
        importer.run(OpenedSource(listOf(item("modern/Adele/Hello.mid", band))))
        val before = store.pieces.single()
        val result = importer.run(OpenedSource(listOf(item("modern/Adele/Hello.mid", "not a midi file".toByteArray())), index("Pop,Adele,Hello,1,modern/Adele/Hello.mid,${sha(band)}")))
        assertEquals(ImportProgress(done = 1, total = 1, failed = 1, finished = true), result)
        assertEquals(before, store.pieces.single())
        assertTrue(File(tmp.root, "pieces/${sha(band)}.mid").isFile)
    }

    @Test
    fun `when the library refuses the new file (the piece went meanwhile), it is imported as a new piece`() = runTest {
        val band = midi(60)
        importer.run(OpenedSource(listOf(item("modern/Adele/Hello.mid", band))))
        val before = store.pieces.single()
        store.refuseReplace = true
        val result = importer.run(OpenedSource(listOf(item("modern/Adele/Hello.mid", piano)), index("Pop,Adele,Hello,1,modern/Adele/Hello.mid,${sha(band)}")))
        assertEquals(ImportProgress(done = 1, total = 1, imported = 1, finished = true), result)
        assertEquals(before, store.pieces.first())
        assertEquals(sha(piano), store.pieces.last().sha256)
        assertTrue("the old file stays with its piece", File(tmp.root, "pieces/${sha(band)}.mid").isFile)
        assertTrue(redrawn.isEmpty())
    }

    @Test
    fun `a zip's replaced piece goes into the zip's playlist, as a piece already there does`() = runTest {
        val band = midi(60)
        importer.run(OpenedSource(listOf(item("Hello.mid", band))))
        val source = OpenedSource(
            listOf(item("MIDI/Adele/Hello.mid", piano), item("MIDI/Adele/Skyfall.mid", midi(63))),
            IndexCsv.parse("path,replaces\nMIDI/Adele/Hello.mid,${sha(band)}\n"),
            batch = ImportBatch.zip("MIDI.zip"),
        )
        val result = importer.run(source)
        assertEquals(ImportProgress(done = 2, total = 2, imported = 1, replaced = 1, finished = true, playlist = ImportedPlaylist(1, "MIDI")), result)
        assertEquals(listOf("Hello", "Skyfall"), titles("MIDI"))
        assertTrue("a row without a collection names no playlist of its own", store.collectionLinks.isEmpty())
    }

    private class FakeStore : ImportStore {
        val pieces = mutableListOf<PieceEntity>()
        val batchSizes = mutableListOf<Int>()

        /** v1.41: the playlists a replaced piece was put in (its INDEX.csv row's), by name; [refuseReplace] as when the piece went. */
        val collectionLinks = mutableListOf<Pair<String, Long>>()
        var refuseReplace = false

        override suspend fun findBySha(sha256: String): PieceEntity? = pieces.firstOrNull { it.sha256 == sha256 }

        override suspend fun oldestBySha(shas: Set<String>): PieceEntity? = pieces.filter { it.sha256 in shas }.minByOrNull { it.id }

        /** The whole of [piece] is kept, so what the importer hands over is what is checked; the SHA-256 stays unique. */
        override suspend fun replaceFile(piece: PieceEntity, collection: String?): Boolean {
            val at = pieces.indexOfFirst { it.id == piece.id }
            if (refuseReplace || at < 0 || pieces.any { it.sha256 == piece.sha256 && it.id != piece.id }) return false
            pieces[at] = piece
            collection?.let { collectionLinks += it to piece.id }
            return true
        }

        override suspend fun fillComposer(piece: PieceEntity, composer: ComposerNames.Name) {
            pieces[pieces.indexOfFirst { it.id == piece.id }] = piece.named(piece.title, composer)
        }

        override suspend fun insertAll(pieces: List<PieceEntity>): Int {
            batchSizes += pieces.size
            pieces.forEach { this.pieces += it.copy(id = this.pieces.size + 1L) }
            return pieces.size
        }

        override suspend fun hasComposerKey(composerKey: String): Boolean = pieces.any { it.composerKey == composerKey }

        /** Playlists by name (names compare ignoring case, as the library's do): their pieces' ids in order. */
        val playlists = LinkedHashMap<String, MutableList<Long>>()
        val imported = HashMap<String, Boolean>()

        override suspend fun linkToPlaylist(name: String, imported: Boolean, shas: List<String>): ImportedPlaylist? {
            val ids = shas.mapNotNull { sha -> pieces.firstOrNull { it.sha256 == sha }?.id }
            if (ids.isEmpty()) return null
            val key = playlists.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: name.also { this.imported[it] = imported }
            val list = playlists.getOrPut(key) { mutableListOf() }
            ids.forEach { if (it !in list) list += it }
            return ImportedPlaylist(playlists.keys.indexOf(key) + 1L, key)
        }
    }
}

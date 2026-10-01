// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.db

import dev.stevenjin.stevenpiano.data.TextLimits
import dev.stevenjin.stevenpiano.data.imports.ComposerNames
import dev.stevenjin.stevenpiano.data.imports.ImportedPlaylist
import dev.stevenjin.stevenpiano.data.imports.PathOrder
import dev.stevenjin.stevenpiano.data.imports.TitleHeuristics
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-time repair of uploads imported before 1.10.1 (DESIGN.md › v1.10.1, D4), over the zip Steven
 * dropped on the school tablet's web panel: its 266 paths (`m28_zip_paths.txt`, the names alone, in the
 * zip's order and its own NFD spelling; the files are his and stay out of the repository), each named as
 * 1.10's importer named it, then repaired.
 */
class UploadRepairTest {
    private val paths = UploadRepairTest::class.java.getResource("/m28_zip_paths.txt")!!.readText().lines().filter { it.isNotBlank() }
    private val library = FakeLibrary()
    private val log = mutableListOf<String>()

    /** A piece as 1.10 named a file of an upload: a `Composer - Title` name read left to right, else the file's name as its title; no playlist. */
    private fun as110(id: Long, path: String, collection: String? = null): PieceEntity {
        val base = TitleHeuristics.baseName(path.substringAfterLast('/'))
        val (composer, title) = TitleHeuristics.splitComposer(base) ?: ("" to base)
        return PieceEntity(
            id = id, title = "", composer = "", composerKey = "", composerShort = "", collection = collection, sha256 = "sha-$id",
            fileName = "$id.mid", sourceName = path, sizeBytes = 1_000, durationMs = 60_000, noteCount = 100, addedAt = id,
            searchText = "", titleKey = "",
        ).named(TitleHeuristics.cleanText(title), ComposerNames.normalize(TextLimits.clip(TitleHeuristics.cleanText(composer), TextLimits.COMPOSER)))
    }

    private fun uploadAs110() = paths.forEachIndexed { i, path -> library.add(as110(100L + i, path)) }

    private fun piece(path: String) = library.pieces.values.single { it.sourceName == path }

    private fun names(path: String) = piece(path).let { listOf(it.title, it.composer, it.composerShort, it.composerKey) }

    @Test
    fun `the upload as 1_10 left it becomes the playlist MIDI, every piece with its artist, in path order`() = runTest {
        assertEquals(266, paths.size)
        uploadAs110()
        assertEquals("1.10 read a composer from the five names at the root alone", 5, library.pieces.values.count { it.composer.isNotEmpty() })

        val done = UploadRepair.run(library, log::add)

        assertEquals(listOf(UploadRepair.Done(ImportedPlaylist(1, "MIDI"), linked = 266, filled = 265)), done)
        assertEquals(listOf("Library: 266 pieces put in the playlist MIDI, 265 artists filled"), log)
        val midi = library.playlists.getValue(1)
        assertEquals("MIDI", midi.name)
        assertEquals("path order: folder by folder, then title", paths.sortedWith(PathOrder), midi.pieces.map { library.pieces.getValue(it).sourceName })
        assertEquals("Adele", piece(paths.sortedWith(PathOrder).first()).composer)

        assertEquals(listOf("Sparks", "Coldplay", "Coldplay", "coldplay"), names("MIDI/Coldplay/Sparks.mid"))
        assertEquals("sparks coldplay", piece("MIDI/Coldplay/Sparks.mid").searchText)
        assertEquals(listOf("Perfect", "Ed Sheeran", "Ed Sheeran", "ed sheeran"), names("MIDI/Ed Sheeran/Perfect.mid"))
        assertEquals("a canonical composer joins the classical group", listOf("Clair de Lune", "Claude Debussy", "Debussy", "debussy"), names("MIDI/Claude Debussy/Clair de Lune.mid"))
        assertEquals("satie", piece("MIDI/Erik Satie/Gymnopédie No. 1.mid").composerKey)
        assertEquals("the title composed as 1.10 kept it", "Gymnopédie No. 1", piece("MIDI/Erik Satie/Gymnopédie No. 1.mid").title)
        assertEquals(listOf("Halo", "Beyoncé", "Beyoncé", "beyonce"), names("MIDI/Beyoncé/Halo.mid"))
        assertEquals("louis armstrong", piece("MIDI/Louis Armstrong/What a Wonderful World.mid").composerKey)
        assertEquals("craig armstrong", piece("MIDI/Craig Armstrong/Glasgow Love Theme.mid").composerKey)
        assertEquals("Lady Gaga & Bradley Cooper", piece("MIDI/Lady Gaga & Bradley Cooper/I'll Never Love Again.mid").composerShort)
        assertEquals("lady gaga", piece("MIDI/Lady Gaga/Shallow.mid").composerKey)
        assertEquals("twenty one pilots", piece("MIDI/Twenty One Pilots/Ride.mid").composerKey)

        // The five at the root: four read the right way round now, against the root's artist folders; one has neither side known.
        assertEquals(listOf("Cornfield Chase", "Hans Zimmer", "Hans Zimmer", "hans zimmer"), names("MIDI/Cornfield Chase - Hans Zimmer.mid"))
        assertEquals(listOf("Cornfield Chase (version 2)", "Hans Zimmer", "Hans Zimmer", "hans zimmer"), names("MIDI/Cornfield Chase - Hans Zimmer (version 2).mid"))
        assertEquals("Day One (Interstellar)", piece("MIDI/Day One (Interstellar) - Hans Zimmer.mid").title)
        assertEquals("Time (Inception)", piece("MIDI/Time (Inception) - Hans Zimmer.mid").title)
        assertEquals("hans zimmer", piece("MIDI/Time (Inception) - Hans Zimmer.mid").composerKey)
        assertEquals("as 1.10 read it", as110(103, "MIDI/Stay - Interstellar.mid"), piece("MIDI/Stay - Interstellar.mid"))

        // 107 artist folders, Debussy and Satie among the classical keys, and "Stay".
        val keys = library.pieces.values.map { it.composerKey }.toSet()
        assertEquals(108, keys.size)
        assertTrue(keys.containsAll(listOf("debussy", "satie", "hans zimmer", "stay", "c418", "d4vd", "nintendo")))
        assertEquals("Hans Zimmer's seven and the four at the root", 11, library.pieces.values.count { it.composerKey == "hans zimmer" })
    }

    @Test
    fun `a second run changes nothing`() = runTest {
        uploadAs110()
        UploadRepair.run(library, log::add)
        val pieces = library.pieces.toMap()
        val playlists = library.playlists.mapValues { it.value.name to it.value.pieces.toList() }
        assertEquals(emptyList<UploadRepair.Done>(), UploadRepair.run(library, log::add))
        assertEquals(1, log.size)
        assertEquals(pieces, library.pieces.toMap())
        assertEquals(playlists, library.playlists.mapValues { it.value.name to it.value.pieces.toList() })
    }

    @Test
    fun `collections, playlists, Studio's pieces and pieces without a folder are never touched, and a root needs two pieces`() = runTest {
        val untouched = listOf(
            as110(1, "piano-midi.de/debussy/deb_clai.mid", collection = "piano-midi.de"),
            as110(2, "piano-midi.de/chopin/chpn_op23.mid", collection = "piano-midi.de"),
            as110(3, "Road trip/Song.mid"),
            as110(4, "Road trip/Coldplay/Yellow.mid"),
            as110(5, "Studio/Take 1.mid").named("Take 1", ComposerNames.normalize(ComposerNames.STUDIO)),
            as110(6, "Studio/Take 2.mid").named("Take 2", ComposerNames.normalize(ComposerNames.STUDIO)),
            as110(7, "Loose.mid"),
            as110(8, "Solo/Coldplay/One.mid"),
        )
        untouched.forEach(library::add)
        library.playlists[1] = FakeLibrary.Playlist("Road trip", mutableListOf(3, 4))
        uploadAs110()

        val done = UploadRepair.run(library, log::add)

        assertEquals("only the upload's root", listOf("MIDI"), done.map { it.playlist?.name })
        for (piece in untouched) assertEquals(piece.sourceName, piece, library.pieces.getValue(piece.id))
        assertEquals(mutableListOf(3L, 4L), library.playlists.getValue(1).pieces)
        assertEquals(setOf("Road trip", "MIDI"), library.playlists.values.map { it.name }.toSet())
    }

    @Test
    fun `a composer of the person's own stays, and so does a reversed name corrected by hand`() = runTest {
        library.add(as110(1, "MIDI/Coldplay/Yellow.mid").named("Yellow", ComposerNames.normalize("Chris Martin")))
        library.add(as110(2, "MIDI/Coldplay/Sparks.mid"))
        library.add(as110(3, "MIDI/Cornfield Chase - Hans Zimmer.mid").named("Cornfield Chase", ComposerNames.normalize("Hans Zimmer")))
        library.add(as110(4, "MIDI/Hans Zimmer/Time.mid"))
        val before = library.pieces.toMap()

        val done = UploadRepair.run(library, log::add).single()

        assertEquals(4, done.linked)
        assertEquals(2, done.filled)
        assertEquals(before.getValue(1), library.pieces.getValue(1))
        assertEquals(before.getValue(3), library.pieces.getValue(3))
        assertEquals("coldplay", library.pieces.getValue(2).composerKey)
        assertEquals("Coldplay's folder, the name at the root, Hans Zimmer's folder", listOf(2L, 1L, 3L, 4L), library.playlists.values.single().pieces)
    }

    @Test
    fun `an upload of names already read left to right keeps them and only becomes a playlist`() = runTest {
        library.add(as110(1, "ALL SONGS/Satie - Gymnopedie 1.mid"))
        library.add(as110(2, "ALL SONGS/Chopin - Nocturne.mid"))
        val before = library.pieces.toMap()
        assertEquals(listOf(UploadRepair.Done(ImportedPlaylist(1, "ALL SONGS"), linked = 2, filled = 0)), UploadRepair.run(library, log::add))
        assertEquals(before, library.pieces.toMap())
        assertEquals(listOf(2L, 1L), library.playlists.getValue(1).pieces)
    }

    @Test
    fun `at most 500 pieces a transaction`() = runTest {
        repeat(1_203) { i -> library.add(as110(i + 1L, "Big/Artist ${i % 7}/Piece $i.mid")) }
        val done = UploadRepair.run(library, log::add).single()
        assertEquals(listOf(500, 500, 203), library.chunks)
        assertEquals(1_203, done.linked)
        assertEquals(1_203, library.playlists.getValue(1).pieces.size)
        assertEquals("Piece 0", library.pieces.getValue(library.playlists.getValue(1).pieces.first()).title)
    }

    @Test
    fun `release builds leave the playlist's name out of the trail`() = runTest {
        uploadAs110()
        UploadRepair.run(library, log::add, named = { "" })
        assertEquals(listOf("Library: 266 pieces put in the playlist, 265 artists filled"), log)
    }

    /** The library as the repair sees it: pieces by id, playlists by id with their pieces in order. */
    private class FakeLibrary : UploadRepair.Store {
        class Playlist(val name: String, val pieces: MutableList<Long>)

        val pieces = LinkedHashMap<Long, PieceEntity>()
        val playlists = LinkedHashMap<Long, Playlist>()
        val chunks = mutableListOf<Int>()

        fun add(piece: PieceEntity) {
            pieces[piece.id] = piece
        }

        override suspend fun loosePieces(): List<PieceEntity> {
            val linked = playlists.values.flatMap { it.pieces }.toSet()
            return pieces.values.filter {
                it.collection.isNullOrBlank() && it.sourceName.indexOf('/') > 0 && it.composer != ComposerNames.STUDIO && it.id !in linked
            }
        }

        override suspend fun repairChunk(name: String, renamed: List<PieceEntity>, ids: List<Long>): ImportedPlaylist {
            chunks += ids.size
            renamed.forEach { pieces[it.id] = it }
            val id = playlists.entries.firstOrNull { it.value.name.equals(name, ignoreCase = true) }?.key
                ?: (playlists.keys.maxOrNull() ?: 0L).plus(1).also { playlists[it] = Playlist(name, mutableListOf()) }
            val playlist = playlists.getValue(id)
            ids.forEach { if (it !in playlist.pieces) playlist.pieces += it }
            return ImportedPlaylist(id, playlist.name)
        }
    }
}

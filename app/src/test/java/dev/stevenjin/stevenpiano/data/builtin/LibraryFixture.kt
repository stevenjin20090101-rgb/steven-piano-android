// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.builtin

import dev.stevenjin.stevenpiano.data.Genres
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.data.db.named
import dev.stevenjin.stevenpiano.data.imports.ComposerNames
import dev.stevenjin.stevenpiano.data.imports.CsvReader
import java.io.File

/**
 * Libraries for the matcher and channel tests, made as the importer makes them: a piece's
 * composer is normalized by [ComposerNames], its keys come from `PieceEntity.named` and its genre
 * from [Genres.of] (an empty library's artists), as on import.
 *
 * - [corpus]: `library_titles.csv`, Steven's MIDI library (`Player Piano/midi`, 1,727 files) as
 *   the importer names it: the collection and composer INDEX.csv gives, the title after the
 *   importer's rules (piano-midi.de's file stubs give way to their Track 0 names), and each
 *   file's note count and length. `LibraryFixtureTest` checks it against the files with
 *   `-Pcorpus`.
 * - [allSongs]: `library_titles_all_songs.csv`, the same library imported from `ALL-SONGS.zip`
 *   instead (README › Bring in the music): 1,726 files, named from their file names ("Composer -
 *   Title"; piano-midi.de's under their official names), with no playlists.
 * - [epicZip]: `epic_on_piano_index.csv`, the INDEX.csv of `Epic on piano.zip` (45 pieces), as a
 *   library made by importing that zip alone.
 */
object LibraryFixture {
    /** A piece as the importer would store it. */
    fun piece(
        id: Long,
        title: String,
        composer: String,
        collection: String? = null,
        notes: Int = 0,
        durationMs: Long = 0,
    ): PieceEntity = PieceEntity(
        id = id,
        title = "",
        composer = "",
        composerKey = "",
        composerShort = "",
        collection = collection,
        sha256 = "sha-$id",
        fileName = "$id.mid",
        sourceName = "$id.mid",
        sizeBytes = 0,
        durationMs = durationMs,
        noteCount = notes,
        addedAt = id,
        searchText = "",
        titleKey = "",
    ).named(title, ComposerNames.normalize(composer)).let { it.copy(genre = Genres.of(it.composerKey, it.collection, emptyMap())) }

    val corpus: List<PieceEntity> by lazy {
        rows("library_titles.csv").mapIndexed { i, r ->
            piece(i + 1L, r["title"]!!, r["composer"]!!, r["collection"]!!.ifEmpty { null }, r["notes"]!!.toInt(), r["durationMs"]!!.toLong())
        }
    }

    val allSongs: List<PieceEntity> by lazy {
        rows("library_titles_all_songs.csv").mapIndexed { i, r ->
            piece(30_001L + i, r["title"]!!, r["composer"]!!, r["collection"]!!.ifEmpty { null }, r["notes"]!!.toInt(), r["durationMs"]!!.toLong())
        }
    }

    val epicZip: List<PieceEntity> by lazy {
        rows("epic_on_piano_index.csv").mapIndexed { i, r -> piece(10_001L + i, r["title"]!!, r["composer"]!!, r["collection"]!!) }
    }

    /** A CSV test resource as rows by header name. */
    fun rows(resource: String): List<Map<String, String>> {
        val text = LibraryFixture::class.java.getResource("/$resource")?.readText() ?: error("Test resource $resource is missing")
        val table = CsvReader.parse(text)
        val header = table.first()
        return table.drop(1).filter { it.size == header.size }.map { cells -> header.zip(cells).toMap() }
    }

    /** `app/src/main/assets/<name>`, found from wherever the tests run. */
    fun asset(name: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, "src/main/assets/$name"), File(dir, "app/src/main/assets/$name"))) if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("The asset $name is missing")
    }
}

// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import dev.stevenjin.stevenpiano.AppGraph
import dev.stevenjin.stevenpiano.data.LibraryRepository
import dev.stevenjin.stevenpiano.data.PieceUnavailableException
import dev.stevenjin.stevenpiano.data.db.ArtworkEntity
import dev.stevenjin.stevenpiano.data.imports.ImportItem
import dev.stevenjin.stevenpiano.data.imports.IndexCsv
import dev.stevenjin.stevenpiano.data.imports.OpenedSource
import dev.stevenjin.stevenpiano.midi.SmfException
import dev.stevenjin.stevenpiano.studio.compose.SeedPiece
import dev.stevenjin.stevenpiano.update.VerifiedDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * [StudioLibrary] over the app: a new piece goes in through the importer's own path
 * ([dev.stevenjin.stevenpiano.data.imports.Importer.importOpened]: its caps, its parse, its lock), with
 * an INDEX.csv row that names its title and composer exactly; its sheet's line is an artwork row with no
 * source; Discard lets the player go of it (silenced first when it plays it), then deletes it and its
 * artwork, as the Library's Delete does.
 */
class AppStudioLibrary(private val graph: AppGraph) : StudioLibrary {
    override suspend fun add(fileName: String, bytes: ByteArray, title: String, composer: String): Long? {
        val index = IndexCsv.parse("collection,composer,title,size_kb,path\n,${csv(composer)},${csv(title)},,${csv(fileName)}\n")
        val source = OpenedSource(listOf(ImportItem(fileName, fileName) { bytes.inputStream() }), index, "")
        val result = graph.importer.importOpened(source)
        if (result.imported != 1) return null
        val sha = VerifiedDownloader.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        return graph.library.findBySha(sha)?.id
    }

    override suspend fun describe(pieceId: Long, description: String) {
        graph.artwork.describe(pieceId, description)
    }

    override suspend fun discard(pieceId: Long) {
        withContext(Dispatchers.Main) { graph.player.forget(pieceId) }
        graph.library.delete(pieceId)
        graph.artwork.forget(ArtworkEntity.forPiece(pieceId))
    }

    override suspend fun exists(pieceId: Long): Boolean = graph.library.piece(pieceId) != null

    override suspend fun setCover(pieceId: Long, png: ByteArray): Boolean = graph.artwork.setPieceCover(pieceId, png)

    override suspend fun shelve() {
        graph.refreshStudioShelf()
    }

    /** One CSV field, quoted when it holds a comma, a quote or a line break (RFC 4180). */
    private fun csv(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + field.replace("\"", "\"\"") + "\"" else field
}

/**
 * [SeedSource] over the library (v1.7 — M24): a seed is a piece of the library, read and parsed as the
 * player reads it; nothing from anywhere else, and no text of anyone's, reaches the composing model. A
 * piece that is gone, too large, or no longer parses (a file damaged on disk) is no seed (audit delta 2:
 * the parser's refusal used to escape to the compose sheet).
 */
class LibrarySeeds(private val library: LibraryRepository) : SeedSource {
    override suspend fun seed(pieceId: Long): SeedPiece? = try {
        val piece = library.load(pieceId)
        SeedPiece(piece.title, piece.composer.takeIf { it.isNotBlank() }, piece.midi)
    } catch (e: PieceUnavailableException) {
        null
    } catch (e: SmfException) {
        null
    }

    override suspend fun defaultPieceId(): Long? = library.seedPiece()?.id
}

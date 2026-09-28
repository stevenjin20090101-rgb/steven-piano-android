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
import dev.stevenjin.stevenpiano.data.db.ArtworkEntity
import dev.stevenjin.stevenpiano.data.imports.ImportItem
import dev.stevenjin.stevenpiano.data.imports.IndexCsv
import dev.stevenjin.stevenpiano.data.imports.OpenedSource
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

    /** One CSV field, quoted when it holds a comma, a quote or a line break (RFC 4180). */
    private fun csv(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + field.replace("\"", "\"\"") + "\"" else field
}

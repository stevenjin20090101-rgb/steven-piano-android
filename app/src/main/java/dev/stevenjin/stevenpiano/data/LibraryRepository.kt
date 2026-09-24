// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data

import androidx.room.withTransaction
import dev.stevenjin.stevenpiano.data.db.CollectionEntity
import dev.stevenjin.stevenpiano.data.db.CollectionPieceEntity
import dev.stevenjin.stevenpiano.data.db.CollectionSummary
import dev.stevenjin.stevenpiano.data.db.ComposerGroup
import dev.stevenjin.stevenpiano.data.db.PianoDatabase
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.data.db.named
import dev.stevenjin.stevenpiano.data.imports.ComposerNames
import dev.stevenjin.stevenpiano.data.imports.ImportStore
import dev.stevenjin.stevenpiano.midi.SmfParser
import dev.stevenjin.stevenpiano.player.PieceSource
import dev.stevenjin.stevenpiano.player.PlayablePiece
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.IOException

/** A piece the library can no longer play. The message is shown as is. */
class PieceUnavailableException(message: String) : Exception(message)

/**
 * The library: what the Library tab lists (all, search, collections, composers, favorites,
 * recent), what its menus change, where the player reads pieces from, and where imports land.
 */
class LibraryRepository(
    private val db: PianoDatabase,
    private val files: PieceFiles,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : PieceSource, ImportStore {
    private val pieces = db.pieces()
    private val collections = db.collections()

    /** Every piece, by title (accents ignored). */
    fun all(): Flow<List<PieceEntity>> = pieces.all()

    /** Title or composer contains [query], ignoring case and accents. */
    fun search(query: String): Flow<List<PieceEntity>> = pieces.search(likeEscape(TextKeys.fold(query.trim())))

    fun favorites(): Flow<List<PieceEntity>> = pieces.favorites()

    /** The last 100 played, or added when never played. */
    fun recent(): Flow<List<PieceEntity>> = pieces.recent()

    fun composers(): Flow<List<ComposerGroup>> = pieces.composers()

    fun byComposer(composerKey: String): Flow<List<PieceEntity>> = pieces.byComposer(composerKey)

    fun collections(): Flow<List<CollectionSummary>> = collections.summaries()

    fun inCollection(collectionId: Long): Flow<List<PieceEntity>> = pieces.inCollection(collectionId)

    fun collectionIdsOf(pieceId: Long): Flow<List<Long>> = collections.collectionIdsOf(pieceId)

    fun count(): Flow<Int> = pieces.count()

    suspend fun piece(id: Long): PieceEntity? = pieces.byId(id)

    suspend fun setFavorite(id: Long, favorite: Boolean) = pieces.setFavorite(id, favorite)

    /** New title and composer; the composer is normalized as on import. A blank title is ignored. */
    suspend fun rename(id: Long, title: String, composer: String) {
        val piece = pieces.byId(id) ?: return
        pieces.update(piece.named(title.trim().ifEmpty { piece.title }, ComposerNames.normalize(composer)))
    }

    /** Removes the piece, its collection links and its file. Irreversible. */
    suspend fun delete(id: Long) {
        val piece = pieces.byId(id) ?: return
        pieces.delete(id)
        withContext(io) { files.delete(piece.fileName) }
    }

    /** The collection called [name], made if needed. Returns its id. */
    suspend fun createCollection(name: String): Long = collectionId(name.trim(), imported = false)

    suspend fun renameCollection(id: Long, name: String) = collections.rename(id, name.trim())

    suspend fun deleteCollection(id: Long) = collections.delete(id)

    suspend fun addToCollection(collectionId: Long, pieceId: Long) =
        collections.addPiece(CollectionPieceEntity(collectionId, pieceId, clock()))

    suspend fun removeFromCollection(collectionId: Long, pieceId: Long) = collections.removePiece(collectionId, pieceId)

    override suspend fun load(pieceId: Long): PlayablePiece {
        val piece = pieces.byId(pieceId) ?: throw PieceUnavailableException("This piece is no longer in the library.")
        val bytes = try {
            withContext(io) { files.read(piece.fileName) }
        } catch (e: IOException) {
            throw PieceUnavailableException("This piece's file is missing. Delete it and import it again.")
        }
        return PlayablePiece(piece.id, piece.title, piece.composer, SmfParser.parse(bytes))
    }

    override suspend fun markPlayed(pieceId: Long) = pieces.markPlayed(pieceId, clock())

    override suspend fun findBySha(sha256: String): PieceEntity? = pieces.bySha(sha256)

    override suspend fun fillComposer(piece: PieceEntity, composer: ComposerNames.Name) =
        pieces.update(piece.named(piece.title, composer))

    override suspend fun insertAll(pieces: List<PieceEntity>): Int = db.withTransaction {
        var inserted = 0
        for (piece in pieces) {
            val id = this.pieces.insert(piece)
            if (id < 0) continue   // the same bytes arrived meanwhile
            inserted++
            piece.collection?.let { name ->
                collections.addPiece(CollectionPieceEntity(collectionId(name, imported = true), id, clock()))
            }
        }
        inserted
    }

    private suspend fun collectionId(name: String, imported: Boolean): Long =
        collections.byName(name)?.id ?: collections.insert(CollectionEntity(name = name, createdAt = clock(), imported = imported))

    private fun likeEscape(text: String): String =
        text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

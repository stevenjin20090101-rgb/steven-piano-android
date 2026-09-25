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
import dev.stevenjin.stevenpiano.data.db.ComposerGroup
import dev.stevenjin.stevenpiano.data.db.PianoDatabase
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.data.db.PiecePosition
import dev.stevenjin.stevenpiano.data.db.PieceSummary
import dev.stevenjin.stevenpiano.data.db.PlaylistEntity
import dev.stevenjin.stevenpiano.data.db.PlaylistPieceEntity
import dev.stevenjin.stevenpiano.data.db.PlaylistSummary
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
 * The library: what the Library tab lists (all, search, playlists, composers, favorites,
 * recent), what its menus change, where the player reads pieces from, and where imports land.
 * A playlist keeps its pieces in an order; every change to that order is one transaction.
 */
class LibraryRepository(
    private val db: PianoDatabase,
    private val files: PieceFiles,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : PieceSource, ImportStore {
    private val pieces = db.pieces()
    private val playlists = db.playlists()

    /** Every piece, by title (accents ignored). */
    fun all(): Flow<List<PieceEntity>> = pieces.all()

    /** Title or composer contains [query], ignoring case and accents. */
    fun search(query: String): Flow<List<PieceEntity>> = pieces.search(likeEscape(TextKeys.fold(query.trim())))

    fun favorites(): Flow<List<PieceEntity>> = pieces.favorites()

    /** The last 100 played, or added when never played. */
    fun recent(): Flow<List<PieceEntity>> = pieces.recent()

    fun composers(): Flow<List<ComposerGroup>> = pieces.composers()

    fun byComposer(composerKey: String): Flow<List<PieceEntity>> = pieces.byComposer(composerKey)

    /** A composer's first [limit] pieces by title (a composer's mosaic of roll cards). */
    suspend fun firstPieceIds(composerKey: String, limit: Int): List<Long> = pieces.idsByComposer(composerKey, limit)

    /** The composer of a playlist's first piece: its cover when it has no photo. Null while it is empty. */
    fun firstComposerKey(playlistId: Long): Flow<String?> = pieces.firstComposerKey(playlistId)

    /** Every playlist by name, with its size and total length. */
    fun playlists(): Flow<List<PlaylistSummary>> = playlists.summaries()

    /** A playlist's pieces in its order. */
    fun inPlaylist(playlistId: Long): Flow<List<PieceEntity>> = pieces.inPlaylist(playlistId)

    fun playlistIdsOf(pieceId: Long): Flow<List<Long>> = playlists.playlistIdsOf(pieceId)

    fun count(): Flow<Int> = pieces.count()

    suspend fun piece(id: Long): PieceEntity? = pieces.byId(id)

    suspend fun setFavorite(id: Long, favorite: Boolean) = pieces.setFavorite(id, favorite)

    /** New title and composer; the composer is normalized as on import. A blank title is ignored. */
    suspend fun rename(id: Long, title: String, composer: String) {
        val piece = pieces.byId(id) ?: return
        pieces.update(piece.named(title.trim().ifEmpty { piece.title }, ComposerNames.normalize(composer)))
    }

    /**
     * The pieces among [ids] still in the library, by id: what a queue needs to name its pieces.
     * Asked [SQL_CHUNK] at a time, under SQLite's limit on query variables.
     */
    suspend fun summaries(ids: Collection<Long>): Map<Long, PieceSummary> {
        val found = HashMap<Long, PieceSummary>(ids.size)
        for (chunk in ids.distinct().chunked(SQL_CHUNK)) pieces.summaries(chunk).forEach { found[it.id] = it }
        return found
    }

    /** Removes the piece, its playlist links and its file. Irreversible. */
    suspend fun delete(id: Long) {
        val piece = pieces.byId(id) ?: return
        pieces.delete(id)
        withContext(io) { files.delete(piece.fileName) }
    }

    /** The playlist called [name], made if needed. Returns its id. */
    suspend fun createPlaylist(name: String): Long = playlistId(name.trim(), imported = false)

    suspend fun renamePlaylist(id: Long, name: String) = playlists.rename(id, name.trim())

    /** Only the playlist goes; its pieces stay in the library. */
    suspend fun deletePlaylist(id: Long) = playlists.delete(id)

    /** Puts the piece at the end of the playlist (nothing changes when it is there already). */
    suspend fun addToPlaylist(playlistId: Long, pieceId: Long) {
        db.withTransaction { link(playlistId, pieceId) }
    }

    suspend fun removeFromPlaylist(playlistId: Long, pieceId: Long) = playlists.removePiece(playlistId, pieceId)

    /**
     * The pieces in [orderedIds] were dragged into that order (the list shown may be a search's
     * subset; see [PlaylistOrder.reordered]). Only positions that change are written.
     */
    suspend fun reorderPlaylist(playlistId: Long, orderedIds: List<Long>) {
        db.withTransaction {
            val current = playlists.positions(playlistId)
            writeOrder(playlistId, current, PlaylistOrder.reordered(current.map { it.pieceId }, orderedIds))
        }
    }

    /** Move up ([delta] -1) or down (+1) from the row menu, stopping at either end. */
    suspend fun movePiece(playlistId: Long, pieceId: Long, delta: Int) {
        db.withTransaction {
            val current = playlists.positions(playlistId)
            writeOrder(playlistId, current, PlaylistOrder.move(current.map { it.pieceId }, pieceId, delta))
        }
    }

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
            piece.collection?.let { name -> link(playlistId(name, imported = true), id) }
        }
        inserted
    }

    private suspend fun playlistId(name: String, imported: Boolean): Long =
        playlists.byName(name)?.id ?: playlists.insert(PlaylistEntity(name = name, createdAt = clock(), imported = imported))

    /** Inside a transaction: the piece goes after the playlist's last one. */
    private suspend fun link(playlistId: Long, pieceId: Long) {
        playlists.addPiece(PlaylistPieceEntity(playlistId, pieceId, clock(), playlists.nextPosition(playlistId)))
    }

    /** Inside a transaction: numbers [order] 0, 1, 2… writing only the positions that differ from [current]. */
    private suspend fun writeOrder(playlistId: Long, current: List<PiecePosition>, order: List<Long>) {
        val stored = current.associate { it.pieceId to it.position }
        order.forEachIndexed { position, pieceId ->
            if (stored[pieceId] != position) playlists.setPosition(playlistId, pieceId, position)
        }
    }

    private fun likeEscape(text: String): String =
        text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private companion object {
        /** Ids per query: under SQLite's 999-variable limit on older Android versions. */
        const val SQL_CHUNK = 500
    }
}

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
import dev.stevenjin.stevenpiano.data.builtin.BuiltInPlaylists
import dev.stevenjin.stevenpiano.data.builtin.BuiltInStore
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
import dev.stevenjin.stevenpiano.data.imports.ImportBatch
import dev.stevenjin.stevenpiano.data.imports.ImportStore
import dev.stevenjin.stevenpiano.data.imports.ImportedPlaylist
import dev.stevenjin.stevenpiano.midi.SmfParser
import dev.stevenjin.stevenpiano.player.PieceSource
import dev.stevenjin.stevenpiano.player.PlayablePiece
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.IOException

/** A piece the library can no longer play. The message is shown as is. */
class PieceUnavailableException(message: String) : Exception(message)

/**
 * The library: what the Library tab lists (all, search, playlists, composers, favorites,
 * recent), what its menus change, where the player reads pieces from, and where imports land.
 * A playlist keeps its pieces in an order; every change to that order is one transaction. The
 * built-in playlists (DESIGN.md › v1.5 — M17) are the app's: their pieces are set by
 * [BuiltInPlaylists.refresh] through [setPlaylistPieces], and renaming, deleting, reordering or
 * adding to or taking from one does nothing. A playlist the person names like a built-in one
 * takes the name, and the built-in one moves beside it ("Popular · built in").
 */
class LibraryRepository(
    private val db: PianoDatabase,
    private val files: PieceFiles,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : PieceSource, ImportStore, BuiltInStore {
    private val pieces = db.pieces()
    private val playlists = db.playlists()
    private val renamed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * A piece or a playlist was renamed, or a playlist deleted: what the built-in lists match may
     * have changed. AppGraph refreshes them two seconds after the last of a run of these.
     */
    val namesChanged: SharedFlow<Unit> = renamed.asSharedFlow()

    /** Every piece, by title (accents ignored). */
    fun all(): Flow<List<PieceEntity>> = pieces.all()

    /** Title or composer contains [query], ignoring case and accents. */
    fun search(query: String): Flow<List<PieceEntity>> = pieces.search(likeEscape(TextKeys.fold(query.trim())))

    fun favorites(): Flow<List<PieceEntity>> = pieces.favorites()

    /** The last 100 played, or added when never played. */
    fun recent(): Flow<List<PieceEntity>> = pieces.recent()

    /**
     * Where a composition starts when no piece is chosen (Studio, v1.7 — M24): the piece played last,
     * else the first by title, never one Studio made; null for a library without such a piece.
     */
    suspend fun seedPiece(): PieceEntity? = pieces.seedPiece(ComposerNames.STUDIO)

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

    /**
     * New title and composer; the composer is read as a file name's is on import ([ComposerNames.resolve]:
     * an artist the library already has by their whole name keeps that one key, v1.10.1 — M28). A blank
     * title is ignored. Both are cut as on import.
     */
    suspend fun rename(id: Long, title: String, composer: String) {
        val piece = pieces.byId(id) ?: return
        val name = ComposerNames.resolve(TextLimits.clip(composer, TextLimits.COMPOSER)) { key -> pieces.hasComposerKey(key) }
        pieces.update(piece.named(title.trim().ifEmpty { piece.title }, name))
        renamed.tryEmit(Unit)
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

    /** The pieces among [ids] still in the library, whole, by id; asked [SQL_CHUNK] at a time. */
    suspend fun pieces(ids: Collection<Long>): Map<Long, PieceEntity> {
        val found = HashMap<Long, PieceEntity>(ids.size)
        for (chunk in ids.distinct().chunked(SQL_CHUNK)) pieces.byIds(chunk).forEach { found[it.id] = it }
        return found
    }

    /** Removes the piece, its playlist links and its file. Irreversible. */
    suspend fun delete(id: Long) {
        val piece = pieces.byId(id) ?: return
        pieces.delete(id)
        withContext(io) { files.delete(piece.fileName) }
    }

    /** The playlist called [name] (cut to [TextLimits.COLLECTION]), made if needed. Returns its id. */
    suspend fun createPlaylist(name: String): Long = db.withTransaction { playlistId(playlistName(name), imported = false) }

    /**
     * A built-in playlist keeps its name (the app gives it). Another may take a built-in list's
     * name: the built-in one moves beside it ("Popular · built in"), as when a playlist is made.
     */
    suspend fun renamePlaylist(id: Long, name: String) {
        val done = db.withTransaction {
            if (isBuiltIn(id)) return@withTransaction false
            val wanted = playlistName(name)
            moveBuiltInAside(wanted)
            playlists.rename(id, wanted)
            true
        }
        if (done) renamed.tryEmit(Unit)
    }

    /** Only the playlist goes; its pieces stay in the library. A built-in playlist is never deleted. */
    suspend fun deletePlaylist(id: Long) {
        if (isBuiltIn(id)) return
        playlists.delete(id)
        renamed.tryEmit(Unit)   // a built-in list that stood aside for this one's name takes it back
    }

    /** Puts the piece at the end of the playlist (nothing changes when it is there already, or the playlist is built in). */
    suspend fun addToPlaylist(playlistId: Long, pieceId: Long) {
        db.withTransaction {
            if (isBuiltIn(playlistId)) return@withTransaction
            link(playlistId, pieceId)
        }
    }

    suspend fun removeFromPlaylist(playlistId: Long, pieceId: Long) {
        if (isBuiltIn(playlistId)) return
        playlists.removePiece(playlistId, pieceId)
    }

    /**
     * The pieces in [orderedIds] were dragged into that order (the list shown may be a search's
     * subset; see [PlaylistOrder.reordered]). Only positions that change are written. A built-in
     * playlist keeps the order the app gives it.
     */
    suspend fun reorderPlaylist(playlistId: Long, orderedIds: List<Long>) {
        db.withTransaction {
            if (isBuiltIn(playlistId)) return@withTransaction
            val current = playlists.positions(playlistId)
            writeOrder(playlistId, current, PlaylistOrder.reordered(current.map { it.pieceId }, orderedIds))
        }
    }

    /** Move up ([delta] -1) or down (+1) from the row menu, stopping at either end. Not in a built-in playlist. */
    suspend fun movePiece(playlistId: Long, pieceId: Long, delta: Int) {
        db.withTransaction {
            if (isBuiltIn(playlistId)) return@withTransaction
            val current = playlists.positions(playlistId)
            writeOrder(playlistId, current, PlaylistOrder.move(current.map { it.pieceId }, pieceId, delta))
        }
    }

    override suspend fun allPieces(): List<PieceEntity> = pieces.list()

    override suspend fun builtInId(key: String): Long? = playlists.byBuiltInKey(key)?.id

    /**
     * The built-in playlist [key] (get or create by its key, never by name), called [name], or
     * beside it when the person has a playlist of that name ("Popular · built in"); it takes its
     * own name back once that is free. Returns its id.
     */
    override suspend fun ensureBuiltIn(key: String, name: String): Long = db.withTransaction {
        val existing = playlists.byBuiltInKey(key)
        val wanted = BuiltInPlaylists.builtInName(playlistName(name)) { candidate ->
            playlists.byName(candidate)?.let { it.id != existing?.id } == true
        }
        when {
            existing == null -> playlists.insert(PlaylistEntity(name = wanted, createdAt = clock(), imported = false, builtIn = true, builtInKey = key))
            existing.name != wanted -> existing.id.also { playlists.rename(it, wanted) }
            else -> existing.id
        }
    }

    /**
     * Playlist [id] holds exactly the pieces [orderedIds] that are still in the library, in that
     * order, in one transaction; links that are already right are left alone.
     */
    override suspend fun setPlaylistPieces(id: Long, orderedIds: List<Long>) {
        db.withTransaction {
            val present = HashSet<Long>(orderedIds.size)
            for (chunk in orderedIds.distinct().chunked(SQL_CHUNK)) present += pieces.existing(chunk)
            val current = playlists.positions(id).associate { it.pieceId to it.position }
            val changes = BuiltInPlaylists.linkChanges(id, current, orderedIds.filter { it in present }, clock())
            changes.removed.forEach { playlists.removePiece(id, it) }
            changes.moved.forEach { (pieceId, position) -> playlists.setPosition(id, pieceId, position) }
            changes.added.forEach { playlists.addPiece(it) }
        }
    }

    private suspend fun isBuiltIn(playlistId: Long): Boolean = playlists.byId(playlistId)?.builtIn == true

    /** Reads and parses the piece. A file too large for the memory left says so instead of taking the app down. */
    override suspend fun load(pieceId: Long): PlayablePiece {
        val piece = pieces.byId(pieceId) ?: throw PieceUnavailableException("This piece is no longer in the library.")
        val bytes = try {
            withContext(io) { files.read(piece.fileName) }
        } catch (e: IOException) {
            throw PieceUnavailableException("This piece's file is missing. Delete it and import it again.")
        }
        val midi = try {
            SmfParser.parse(bytes)
        } catch (e: OutOfMemoryError) {
            throw PieceUnavailableException(TOO_LARGE)
        }
        return PlayablePiece(piece.id, piece.title, piece.composer, midi, piece.composerKey)
    }

    override suspend fun markPlayed(pieceId: Long) = pieces.markPlayed(pieceId, clock())

    override suspend fun findBySha(sha256: String): PieceEntity? = pieces.bySha(sha256)

    override suspend fun fillComposer(piece: PieceEntity, composer: ComposerNames.Name) =
        pieces.update(piece.named(piece.title, composer))

    override suspend fun hasComposerKey(composerKey: String): Boolean = pieces.hasComposerKey(composerKey)

    /**
     * An import's pieces into its playlist (v1.10.1 — M28, D2): the pieces among [shas] the library holds,
     * in that order, after the playlist's last one; the playlist called [name] (as names compare, a
     * built-in one stepping aside) found or made, in the first transaction that has a piece for it; at most
     * [SQL_CHUNK] pieces a transaction. A piece in it already keeps its place.
     */
    override suspend fun linkToPlaylist(name: String, imported: Boolean, shas: List<String>): ImportedPlaylist? {
        var target: PlaylistEntity? = null
        for (chunk in shas.distinct().chunked(SQL_CHUNK)) {
            db.withTransaction {
                val ids = pieces.idsBySha(chunk).associate { it.sha256 to it.id }
                val ordered = chunk.mapNotNull { ids[it] }
                if (ordered.isEmpty()) return@withTransaction
                val playlist = target ?: playlists.byId(playlistId(playlistName(name).ifEmpty { ImportBatch.UPLOADS }, imported))
                    ?: return@withTransaction
                target = playlist
                ordered.forEach { link(playlist.id, it) }
            }
        }
        return target?.let { ImportedPlaylist(it.id, it.name) }
    }

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

    /**
     * Inside a transaction: the person's (or an INDEX.csv's) playlist [name], made if needed. When
     * a built-in list has that name, the built-in one moves beside it ("Popular · built in") and
     * this one takes it.
     */
    private suspend fun playlistId(name: String, imported: Boolean): Long {
        val existing = playlists.byName(name)
        if (existing != null && !existing.builtIn) return existing.id
        moveBuiltInAside(name)
        return playlists.insert(PlaylistEntity(name = name, createdAt = clock(), imported = imported))
    }

    /**
     * Inside a transaction: when a built-in list is called [name] (whatever the case, as names
     * compare), it moves beside it ("Popular · built in", then "… 2") so another playlist can take
     * the name. The built-in one takes its name back at the next refresh once it is free.
     */
    private suspend fun moveBuiltInAside(name: String) {
        val holder = playlists.byName(name)?.takeIf { it.builtIn } ?: return
        val aside = BuiltInPlaylists.builtInName(holder.name) { candidate ->
            candidate.equals(name, ignoreCase = true) || playlists.byName(candidate)?.let { it.id != holder.id } == true
        }
        playlists.rename(holder.id, aside)
    }

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

    private fun playlistName(name: String): String = TextLimits.clip(name.trim(), TextLimits.COLLECTION)

    private companion object {
        /** Ids per query: under SQLite's 999-variable limit on older Android versions. */
        const val SQL_CHUNK = 500
        const val TOO_LARGE = "This piece is too large to play."
    }
}

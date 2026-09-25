// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import dev.stevenjin.stevenpiano.data.LibraryRepository
import dev.stevenjin.stevenpiano.data.db.ArtworkDao
import dev.stevenjin.stevenpiano.data.db.ArtworkEntity
import dev.stevenjin.stevenpiano.data.db.ArtworkStatus
import dev.stevenjin.stevenpiano.net.NetworkMonitor
import dev.stevenjin.stevenpiano.net.WikiApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Artwork for the whole app, one per process in `AppGraph`: composers' portraits and blurbs and
 * pieces' notes from Wikipedia (fetched by one [ArtworkWorker], strictly one request at a time and
 * at most four a second, nothing at all while offline), the person's own playlist photos, and the
 * roll cards drawn from a piece's own notes when there is no portrait. Screens read rows with
 * [artwork] and pictures with [bitmap] and [rollCard]; everything that can fail fails quietly,
 * and the fallback art shows.
 */
class ArtworkRepository(
    private val context: Context,
    private val dao: ArtworkDao,
    private val library: LibraryRepository,
    private val files: ArtFiles,
    api: WikiApi,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val bitmaps = BitmapCache()

    /** Each composer's mosaic pieces, forgotten whenever the library's pieces change. */
    private val mosaics = ConcurrentHashMap<String, List<Long>>()

    private val worker = ArtworkWorker(
        store = dao,
        fetcher = ArtworkFetcher(PacedWikiApi(api, RequestPacer(SystemClock::elapsedRealtime))),
        images = ImageStore { key, bytes -> withContext(io) { if (BitmapCache.isImage(bytes)) files.write(key, bytes) else null } },
        online = network::isOnline,
        scope = scope,
        clock = System::currentTimeMillis,
        log = { Log.i(TAG, it) },
    )

    /** The background run's progress: the Library's hairline row and the fetch notification. */
    val progress: StateFlow<ArtworkProgress> get() = worker.progress

    /** Whether the device is online now (the piece sheet says notes need a connection when it is not). */
    val online: StateFlow<Boolean> get() = network.online

    /** Every row by key, shared: one query per change to the table, however many rows and tiles watch. */
    private val rows: StateFlow<Map<String, ArtworkEntity>?> = dao.observeAll()
        .map { list -> list.associateBy { it.key } }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        scope.launch { library.count().collect { mosaics.clear() } }
    }

    /** The artwork row for [key] ([ArtworkEntity.forComposer] and the like) as it changes; null while there is none. */
    fun artwork(key: String): Flow<ArtworkEntity?> = rows.filterNotNull().map { it[key] }.distinctUntilChanged()

    /** The row for [key] as last read, without waiting: a first frame without a flash of fallback art. */
    fun peek(key: String): ArtworkEntity? = rows.value?.get(key)

    /** Fetches [key] when it is due: first in line when [priority] (a sheet just opened), otherwise last. */
    fun request(key: ArtKey, priority: Boolean = false, force: Boolean = false) = worker.request(key, priority, force)

    /** Every composer in the library that is due (all but the found ones when [force]). Returns how many were queued. */
    suspend fun requestComposers(force: Boolean): Int = worker.requestAll(composerKeys(), force)

    /** Whether any composer has never been looked up, or failed a day ago or more: worth a fetch when the app opens. */
    suspend fun composersDue(): Boolean {
        val now = System.currentTimeMillis()
        return composerKeys().any { ArtworkPolicy.shouldFetch(dao.get(it.storageKey), now, force = false) }
    }

    /** Stops the background run after the fetch under way (the fetch service ran out of time); nothing is recorded for the rest. */
    fun cancelBackground() = worker.cancelBackground()

    /** [artwork]'s picture decoded for [size]; null when it has none or it cannot be read. */
    suspend fun bitmap(artwork: ArtworkEntity, size: ArtSize): Bitmap? {
        val path = artwork.imagePath ?: return null
        return withContext(io) { bitmaps.load(files.file(path), size, artwork.fetchedAt) }
    }

    suspend fun bitmap(key: String, size: ArtSize): Bitmap? = dao.get(key)?.let { bitmap(it, size) }

    /** [bitmap], only if it is already decoded in memory. */
    fun cached(artwork: ArtworkEntity, size: ArtSize): Bitmap? {
        val path = artwork.imagePath ?: return null
        return bitmaps.peek(files.file(path), size, artwork.fetchedAt)
    }

    /**
     * The photo at [uri] (from the photo picker, whose grant does not last) becomes the playlist's
     * cover: copied now, turned upright and re-encoded as a JPEG at most 1024 px on its longer side.
     * False when it could not be read.
     */
    suspend fun setPlaylistPhoto(playlistId: Long, uri: Uri): Boolean {
        val key = ArtworkEntity.forPlaylist(playlistId)
        val path = withContext(io) {
            val jpeg = PhotoImport.jpeg(context.contentResolver, uri, PhotoImport.MAX_PX) ?: return@withContext null
            files.write(key, jpeg)
        } ?: return false
        dao.upsert(ArtworkEntity(key, imagePath = path, fetchedAt = System.currentTimeMillis(), status = ArtworkStatus.OK))
        return true
    }

    /**
     * The piece's roll card: its first 20 seconds as perforations ([RollCard]), an alpha-only
     * bitmap the caller tints with the theme's colours. Null when the piece cannot be read.
     */
    suspend fun rollCard(pieceId: Long): Bitmap? {
        val key = rollKey(pieceId)
        bitmaps.get(key)?.let { return it }
        val alpha = withContext(Dispatchers.Default) {
            try {
                RollCard.render(library.load(pieceId).midi.notes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {   // the file is gone or unreadable: no card, the next fallback shows
                null
            }
        } ?: return null
        return Bitmap.createBitmap(RollCard.SIZE, RollCard.SIZE, Bitmap.Config.ALPHA_8)
            .apply { copyPixelsFromBuffer(ByteBuffer.wrap(alpha)) }
            .also { bitmaps.put(key, it) }
    }

    /** [rollCard], only if it is already drawn. */
    fun cachedRollCard(pieceId: Long): Bitmap? = bitmaps.get(rollKey(pieceId))

    /** The pieces whose roll cards make a composer's mosaic: their first four by title. */
    suspend fun mosaicPieces(composerKey: String): List<Long> =
        mosaics[composerKey] ?: library.firstPieceIds(composerKey, MOSAIC_PIECES).also { mosaics[composerKey] = it }

    /** [mosaicPieces], only if already known. */
    fun peekMosaic(composerKey: String): List<Long>? = mosaics[composerKey]

    /** The composer of a playlist's first piece, for its cover when it has no photo. */
    fun firstComposerKey(playlistId: Long): Flow<String?> = library.firstComposerKey(playlistId)

    /** A playlist or a piece is gone: its artwork row goes, and its file with it. */
    fun forget(key: String) {
        scope.launch {
            val row = dao.get(key) ?: return@launch
            row.imagePath?.let { path -> withContext(io) { files.delete(path) } }
            dao.delete(key)
        }
    }

    private fun rollKey(pieceId: Long) = "roll:$pieceId"

    private suspend fun composerKeys(): List<ArtKey.Composer> =
        library.composers().first().map { ArtKey.Composer(it.composerKey, it.name) }

    private companion object {
        const val TAG = "Artwork"
        const val STOP_TIMEOUT_MS = 5_000L
        const val MOSAIC_PIECES = 4
    }
}

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
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.createBitmap
import dev.stevenjin.stevenpiano.data.Genres
import dev.stevenjin.stevenpiano.data.LibraryRepository
import dev.stevenjin.stevenpiano.data.db.ArtworkDao
import dev.stevenjin.stevenpiano.data.db.ArtworkEntity
import dev.stevenjin.stevenpiano.data.db.ArtworkStatus
import dev.stevenjin.stevenpiano.net.AppleCatalogApi
import dev.stevenjin.stevenpiano.net.NetworkMonitor
import dev.stevenjin.stevenpiano.net.WikiApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Artwork for the whole app, one per process in `AppGraph`: composers' portraits and blurbs and
 * pieces' notes from Wikipedia (fetched by one [ArtworkWorker], strictly one request at a time and
 * at most four a second, nothing at all while offline), pieces' album covers from Apple's catalogue
 * through the same worker while [coversWanted] (v1.15 — M40: searches 3.5 s apart), the person's own
 * playlist photos and piece covers, and the roll cards drawn from a piece's own notes when there is
 * no portrait. Screens read rows with [artwork] and pictures with [bitmap] and [rollCard]; everything
 * that can fail fails quietly, and the fallback art shows. Roll cards are drawn one at a time (each
 * parses a whole file) and kept on disk ([RollCardFiles]), so scrolling a grid never parses many files at once.
 */
class ArtworkRepository(
    private val context: Context,
    private val dao: ArtworkDao,
    private val library: LibraryRepository,
    private val files: ArtFiles,
    api: WikiApi,
    catalog: AppleCatalogApi,
    /** Whether album covers may be looked up: Fetch artwork automatically and Album covers both on. */
    private val coversWanted: suspend () -> Boolean,
    /** When the wider album-cover match began (v1.17 — M45), 0 unset: a cover's lookup that found nothing before it is due once more. */
    private val coverRuleSince: suspend () -> Long,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val bitmaps = BitmapCache()
    private val rollCardFiles = RollCardFiles(File(context.cacheDir, ROLL_CARD_DIR))

    /** One roll card drawn at a time: each parses its piece's whole file. */
    private val drawing = Semaphore(1)

    /** A piece's row is read and written whole: its line and its cover never undo each other. */
    private val writing = Mutex()

    /** Each composer's mosaic pieces, forgotten whenever the library's pieces change. */
    private val mosaics = ConcurrentHashMap<String, List<Long>>()

    /** The backdrop's pictures already made (v1.18 — M49), with their dimming, by picture ([pictureKey]). */
    private val backdrops = LruCache<String, BackdropPicture>(BACKDROPS)

    /** What album covers need of this repository (v1.15 — M40): the settings, the piece's row, and keeping a found cover. */
    private val coverStore = object : CoverStore {
        override suspend fun wanted(): Boolean = coversWanted()

        override suspend fun hasCover(pieceId: Long): Boolean = dao.get(ArtworkEntity.forPiece(pieceId))?.imagePath != null

        override suspend fun keep(pieceId: Long, image: ByteArray, sourceUrl: String?, sourceTitle: String): Fetched {
            if (!withContext(io) { BitmapCache.isImage(image) }) return Fetched.NotFound
            val record = ArtworkEntity(ArtworkEntity.forCover(pieceId), sourceUrl = sourceUrl, sourceTitle = sourceTitle, fetchedAt = 0, status = ArtworkStatus.OK)
            return when (keepCover(pieceId, image, record, unlessCovered = true)) {
                true -> Fetched.Saved
                null -> Fetched.Skipped
                false -> Fetched.Failed("The cover couldn't be kept")
            }
        }
    }

    /** Album covers from Apple's catalogue (v1.15 — M40): its searches 3.5 s apart, its images 1 s apart. */
    private val covers = CoverFetcher(
        PacedAppleCatalog(
            catalog,
            searches = RequestPacer(SystemClock::elapsedRealtime, CoverFetcher.SEARCH_GAP_MS),
            images = RequestPacer(SystemClock::elapsedRealtime, CoverFetcher.IMAGE_GAP_MS),
        ),
        coverStore,
        SystemClock::elapsedRealtime,
    )

    private val worker = ArtworkWorker(
        store = dao,
        fetcher = ArtworkFetcher(PacedWikiApi(api, RequestPacer(SystemClock::elapsedRealtime)), covers = covers),
        images = ImageStore { key, bytes -> withContext(io) { if (BitmapCache.isImage(bytes)) files.write(key, bytes) else null } },
        online = network::isOnline,
        scope = scope,
        clock = System::currentTimeMillis,
        log = { Log.i(TAG, it) },
        writing = writing,
        coverRuleSince = coverRuleSince,
    )

    /** The background run's progress: the Library's hairline row and the fetch notification. */
    val progress: StateFlow<ArtworkProgress> get() = worker.progress

    /** Whether the device is online now (the piece sheet says notes need a connection when it is not). */
    val online: StateFlow<Boolean> get() = network.online

    /** Every row by key, shared: one query per change to the table, however many rows and tiles watch. Unreadable: none. */
    private val rows: StateFlow<Map<String, ArtworkEntity>?> = dao.observeAll()
        .map { list -> list.associateBy { it.key } }
        .catch { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "Artwork couldn't be read", e)
            emit(emptyMap())
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        scope.launch { library.count().catch { e -> if (e is CancellationException) throw e }.collect { mosaics.clear() } }
    }

    /** The artwork row for [key] ([ArtworkEntity.forComposer] and the like) as it changes; null while there is none. */
    fun artwork(key: String): Flow<ArtworkEntity?> = rows.filterNotNull().map { it[key] }.distinctUntilChanged()

    /** The row for [key] as last read, without waiting: a first frame without a flash of fallback art. */
    fun peek(key: String): ArtworkEntity? = rows.value?.get(key)

    /**
     * The composers (by `composerKey`) whose portrait the app holds, as the table reads now, with when each was kept (the
     * web panel's art version, v1.17 — M45): the panel shows those portraits and every other piece's roll card. None when
     * it can't be read.
     */
    suspend fun portraitComposers(): Map<String, Long> = readOr(emptyMap()) {
        rows.filterNotNull().first().values.asSequence()
            .filter { it.status == ArtworkStatus.OK && it.imagePath != null && it.key.startsWith(COMPOSER_PREFIX) }
            .associate { it.key.removePrefix(COMPOSER_PREFIX) to it.fetchedAt }
    }

    /** Fetches [key] when it is due: first in line when [priority] (a sheet just opened), otherwise last. */
    fun request(key: ArtKey, priority: Boolean = false, force: Boolean = false) = worker.request(key, priority, force)

    /**
     * Every composer in the library that is due (all but the found ones when [force]), then, with album covers on, every
     * piece's cover that is due (v1.15 — M40: a piece without a cover of its own and not made here, Modern first, newest
     * first; a lookup that found nothing is never forced again, though one from before the wider match is asked once more,
     * v1.17 — M45). Returns how many were queued; 0 when the library can't be read.
     */
    suspend fun requestDue(force: Boolean): Int = readOr(0) {
        val composers = worker.requestAll(composerKeys(), force)
        composers + if (coversWanted()) worker.requestAll(coverKeys(coverRuleSince()), force = false) else 0
    }

    /**
     * Whether any composer, or (album covers on, v1.15 — M40) any piece's cover, has never been looked up, or failed a day
     * ago or more, or (a cover) found nothing before the wider match (v1.17 — M45): worth a fetch when the app opens.
     * Covers are not, while Apple's lookups wait out a 403 or 429.
     */
    suspend fun due(): Boolean = readOr(false) {
        val now = System.currentTimeMillis()
        composerKeys().any { ArtworkPolicy.shouldFetch(dao.get(it.storageKey), now, force = false) } ||
            (covers.blockedFor() == null && coversWanted() && coversDue(now, coverRuleSince()))
    }

    private suspend fun coversDue(now: Long, since: Long): Boolean =
        coverKeys(since).any { ArtworkPolicy.shouldFetch(dao.get(it.storageKey), now, force = false, coverRuleSince = since) }

    /**
     * Piece [pieceId]'s album cover, first in line (v1.15 — M40: the piece playing, from the resting screen and Now
     * playing): only with album covers on, and for a piece of Classical or Modern without a cover of its own.
     */
    fun requestCover(pieceId: Long) {
        scope.launch {
            readOr(Unit) {
                if (!coversWanted()) return@readOr
                val piece = library.piece(pieceId) ?: return@readOr
                if (piece.genre != Genres.CLASSICAL && piece.genre != Genres.MODERN) return@readOr   // made here: never looked up
                if (dao.get(ArtworkEntity.forPiece(pieceId))?.imagePath != null) return@readOr
                worker.request(ArtKey.Cover(piece.id, piece.title, piece.composerShort, piece.genre == Genres.CLASSICAL), priority = true, force = false)
            }
        }
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
     * The backdrop's picture of [artwork]'s picture (v1.18 — M49): its row-size decode (128 px) cropped to the centre
     * square and scaled to 48 × 48, then blurred and lifted ([BackdropRules.prepare]) with the black it needs under light
     * words ([BackdropRules.dimFor]), made off the main thread once a picture and kept by its path and version (a
     * replaced picture is made again); null when it has none or it can't be read (asked again next time).
     */
    suspend fun backdrop(artwork: ArtworkEntity): BackdropPicture? {
        val key = pictureKey(artwork) ?: return null
        backdrops.get(key)?.let { return it }
        val bitmap = bitmap(artwork, ArtSize.Row) ?: return null
        val made = withContext(Dispatchers.Default) {
            try {
                backdropOf(bitmap)
            } catch (e: RuntimeException) {   // a picture that can't be read (recycled, not in memory): no backdrop
                null
            } catch (e: OutOfMemoryError) {
                null
            }
        } ?: return null
        backdrops.put(key, made)
        return made
    }

    /** [backdrop], only if it was made already: the first frame has it. Cheap: safe on the main thread. */
    fun cachedBackdrop(artwork: ArtworkEntity): BackdropPicture? = pictureKey(artwork)?.let { backdrops.get(it) }

    /** A picture's path and version (`imagePath@fetchedAt`, as [BitmapCache] keys its decodes); null when there is none. */
    private fun pictureKey(artwork: ArtworkEntity): String? = artwork.imagePath?.let { "$it@${artwork.fetchedAt}" }

    /** [bitmap]'s centre square at [BackdropRules.SIDE] (filtered), its pixels prepared, with their dimming. */
    private fun backdropOf(bitmap: Bitmap): BackdropPicture {
        val side = BackdropRules.SIDE
        val small = createBitmap(side, side)
        val shorter = minOf(bitmap.width, bitmap.height)
        val left = (bitmap.width - shorter) / 2
        val top = (bitmap.height - shorter) / 2
        Canvas(small).drawBitmap(bitmap, Rect(left, top, left + shorter, top + shorter), Rect(0, 0, side, side), Paint(Paint.FILTER_BITMAP_FLAG))
        val pixels = IntArray(side * side)
        small.getPixels(pixels, 0, side, 0, 0, side, side)
        BackdropRules.prepare(pixels, side, side)
        small.setPixels(pixels, 0, side, 0, 0, side, side)
        return BackdropPicture(small, BackdropRules.dimFor(pixels, side, side))
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
            try {
                files.write(key, jpeg)
            } catch (e: IOException) {   // a full disk: the cover stays as it was
                null
            }
        } ?: return false
        return readOr(false) {
            dao.upsert(ArtworkEntity(key, imagePath = path, fetchedAt = System.currentTimeMillis(), status = ArtworkStatus.OK))
            true
        }
    }

    /**
     * The piece's own notes are [description], from the app and not from Wikipedia (v1.7 — M23: a piece
     * made in Studio reads "Made in Studio · 28 Sept 2026"): a found row with no source, so the piece
     * sheet shows the line without "From Wikipedia" or Wikipedia's credit, and nothing is ever fetched
     * for it. False when it could not be written.
     */
    suspend fun describe(pieceId: Long, description: String): Boolean = readOr(false) {
        // v1.12 (M30): merged into the row, so a Studio piece's cover stays (it used to be replaced whole).
        writing.withLock {
            val key = ArtworkEntity.forPiece(pieceId)
            val row = dao.get(key)
            dao.upsert(
                ArtworkEntity(
                    key, imagePath = row?.imagePath, description = description,
                    fetchedAt = row?.fetchedAt ?: System.currentTimeMillis(), status = ArtworkStatus.OK,
                ),
            )
        }
        true
    }

    /**
     * [image] becomes piece [pieceId]'s own cover (v1.12 — M30: a Studio piece's, drawn from its music; v1.14 — M37: a
     * recording's): [keepCover], with no lookup recorded. False when it could not be kept.
     */
    suspend fun setPieceCover(pieceId: Long, image: ByteArray): Boolean = keepCover(pieceId, image, record = null) == true

    /**
     * The photo at [uri] (the photo picker's, whose grant does not last) becomes piece [pieceId]'s own cover ("Change
     * cover", v1.15 — M40): copied now, turned upright and re-encoded as a JPEG at most 1024 px on its longer side, as a
     * playlist's photo is; its lookup recorded as found and chosen here ([ArtworkEntity.CHOSEN_HERE]), so no lookup ever
     * replaces it. False when it could not be read or kept.
     */
    suspend fun setPieceCoverFromUri(pieceId: Long, uri: Uri): Boolean {
        val jpeg = withContext(io) { PhotoImport.jpeg(context.contentResolver, uri, PhotoImport.MAX_PX) } ?: return false
        val record = ArtworkEntity(ArtworkEntity.forCover(pieceId), sourceTitle = ArtworkEntity.CHOSEN_HERE, fetchedAt = 0, status = ArtworkStatus.OK)
        return keepCover(pieceId, jpeg, record) == true
    }

    /**
     * [image] becomes piece [pieceId]'s own cover, in one step under the write lock: kept under `files/art/` and merged
     * into the piece's row (its notes, their source and their status kept, so a Wikipedia credit stays; a new `fetchedAt`,
     * so every screen and the web panel read it afresh), the old file gone when it had another name, and [record] (the
     * lookup's row, `cover:<id>`, v1.15 — M40) written with it. [unlessCovered] (a lookup): nothing is kept when the piece
     * has a cover of its own by now. True when kept; null when [unlessCovered] found one; false when it could not be kept.
     */
    private suspend fun keepCover(pieceId: Long, image: ByteArray, record: ArtworkEntity?, unlessCovered: Boolean = false): Boolean? = readOr(false) {
        writing.withLock {
            val key = ArtworkEntity.forPiece(pieceId)
            val row = dao.get(key)
            if (unlessCovered && row?.imagePath != null) return@withLock null
            val path = withContext(io) {
                try {
                    files.write(key, image)
                } catch (e: IOException) {   // a full disk: the cover stays as it was
                    null
                }
            } ?: return@withLock false
            val old = row?.imagePath
            if (old != null && old != path) withContext(io) { files.delete(old) }
            val now = System.currentTimeMillis()
            dao.upsert((row ?: ArtworkEntity(key, fetchedAt = now, status = ArtworkStatus.OK)).copy(imagePath = path, fetchedAt = now))
            record?.let { dao.upsert(it.copy(fetchedAt = now)) }
            true
        }
    }

    /**
     * The pieces with a cover of their own (Studio's, the recordings', album covers and covers chosen by hand), by id, with
     * when it was kept: the web panel's art kind and version.
     */
    suspend fun pieceCovers(): Map<Long, Long> = readOr(emptyMap()) {
        rows.filterNotNull().first().values.asSequence()
            .filter { it.imagePath != null && it.key.startsWith(PIECE_PREFIX) }
            .mapNotNull { row -> row.key.removePrefix(PIECE_PREFIX).toLongOrNull()?.let { it to row.fetchedAt } }
            .toMap()
    }

    /**
     * The piece's roll card: its first 20 seconds as perforations ([RollCard]), an alpha-only
     * bitmap the caller tints with the theme's colours. From memory, else from disk, else drawn
     * (one at a time, then kept on disk). Null when the piece cannot be read, or is too large to
     * read in the memory left.
     */
    suspend fun rollCard(pieceId: Long): Bitmap? {
        val key = rollKey(pieceId)
        bitmaps.get(key)?.let { return it }
        val alpha = withContext(io) { rollCardFiles.read(pieceId) } ?: drawing.withPermit {
            bitmaps.get(key)?.let { return it }   // drawn while this one waited its turn
            withContext(io) { rollCardFiles.read(pieceId) } ?: drawRollCard(pieceId)?.also { withContext(io) { rollCardFiles.write(pieceId, it) } }
        } ?: return null
        return Bitmap.createBitmap(RollCard.SIZE, RollCard.SIZE, Bitmap.Config.ALPHA_8)
            .apply { copyPixelsFromBuffer(ByteBuffer.wrap(alpha)) }
            .also { bitmaps.put(key, it) }
    }

    /** Parses the piece and draws its card; null when the file is gone, unreadable or too large for the memory left. */
    private suspend fun drawRollCard(pieceId: Long): ByteArray? = withContext(Dispatchers.Default) {
        try {
            RollCard.render(library.load(pieceId).midi.notes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {   // the file is gone or unreadable: no card, the next fallback shows
            null
        } catch (e: OutOfMemoryError) {   // the parse's arrays are garbage again once this returns
            null
        }
    }

    /** [rollCard], only if it is already drawn. */
    fun cachedRollCard(pieceId: Long): Bitmap? = bitmaps.get(rollKey(pieceId))

    /** The pieces whose roll cards make a composer's mosaic: their first four by title (none when the library can't be read). */
    suspend fun mosaicPieces(composerKey: String): List<Long> =
        mosaics[composerKey] ?: readOr(emptyList()) { library.firstPieceIds(composerKey, MOSAIC_PIECES) }.also { mosaics[composerKey] = it }

    /** [mosaicPieces], only if already known. */
    fun peekMosaic(composerKey: String): List<Long>? = mosaics[composerKey]

    /** A playlist or a piece is gone: its artwork row goes, and its file with it (and a piece's roll card and its cover's lookup). */
    fun forget(key: String) {
        scope.launch {
            readOr(Unit) {
                pieceIdOf(key)?.let { id ->
                    withContext(io) { rollCardFiles.delete(id) }
                    dao.delete(ArtworkEntity.forCover(id))   // v1.15 — M40
                }
                val row = dao.get(key) ?: return@readOr
                row.imagePath?.let { path -> withContext(io) { files.delete(path) } }
                dao.delete(key)
            }
        }
    }

    /** [read], or [fallback] when the database can't be read: artwork is never worth a crash. */
    private suspend inline fun <T> readOr(fallback: T, read: () -> T): T = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        Log.w(TAG, "Artwork: the library couldn't be read", e)
        fallback
    }

    private fun pieceIdOf(key: String): Long? = key.takeIf { it.startsWith(PIECE_PREFIX) }?.removePrefix(PIECE_PREFIX)?.toLongOrNull()

    private fun rollKey(pieceId: Long) = "roll:$pieceId"

    private suspend fun composerKeys(): List<ArtKey.Composer> =
        library.composers().first().map { ArtKey.Composer(it.composerKey, it.name) }

    /** The pieces whose album cover may be looked up (v1.15 — M40), as keys, in [LibraryRepository.coverCandidates]' order. */
    private suspend fun coverKeys(since: Long): List<ArtKey.Cover> =
        library.coverCandidates(since).map { ArtKey.Cover(it.id, it.title, it.composerShort, it.genre == Genres.CLASSICAL) }

    private companion object {
        const val TAG = "Artwork"
        const val STOP_TIMEOUT_MS = 5_000L
        const val MOSAIC_PIECES = 4
        const val BACKDROPS = 32
        const val ROLL_CARD_DIR = "rollcards"
        val PIECE_PREFIX = ArtworkEntity.forPiece(0).removeSuffix("0")
        val COMPOSER_PREFIX = ArtworkEntity.forComposer("")
    }
}

/**
 * The backdrop's picture (v1.18 — M49): the art, [BackdropRules.SIDE] square, soft and lifted ([bitmap], opaque), and
 * the black laid over it so light words read ([dim], 0.18 or more). One per picture, shared: never recycled.
 */
class BackdropPicture(val bitmap: Bitmap, val dim: Float)

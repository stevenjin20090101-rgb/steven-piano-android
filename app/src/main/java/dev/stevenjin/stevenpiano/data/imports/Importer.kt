// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.imports

import android.content.Context
import android.util.Log
import dev.stevenjin.stevenpiano.BuildConfig
import dev.stevenjin.stevenpiano.data.Genres
import dev.stevenjin.stevenpiano.data.PieceFiles
import dev.stevenjin.stevenpiano.data.TextLimits
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.data.db.named
import dev.stevenjin.stevenpiano.midi.SmfException
import dev.stevenjin.stevenpiano.midi.SmfParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest

/** What the importer needs from the library. */
interface ImportStore {
    suspend fun findBySha(sha256: String): PieceEntity?

    suspend fun fillComposer(piece: PieceEntity, composer: ComposerNames.Name)

    /** Inserts in one transaction, adding each piece to the playlist its INDEX.csv row names (at the end); returns how many were new. */
    suspend fun insertAll(pieces: List<PieceEntity>): Int

    /** Whether some piece of the library is grouped under [composerKey] (D3: a composer read from a file name asks first). */
    suspend fun hasComposerKey(composerKey: String): Boolean

    /**
     * The pieces with [shas] (those the library holds), in that order, at the end of the playlist called
     * [name], found by name or made ([imported]: an import named it), ≤ 500 to a transaction; a piece in it
     * already stays where it is. Returns the playlist, or null when none of the pieces is in the library.
     */
    suspend fun linkToPlaylist(name: String, imported: Boolean, shas: List<String>): ImportedPlaylist?

    /** Each artist's genre by key, the one most of their pieces have (v1.14 — M37: [Genres.of]'s rule 2). */
    suspend fun artistGenres(): Map<String, Int> = emptyMap()
}

/**
 * Brings MIDI files into the library. Per file: read (8 MB cap), SHA-256, skip duplicates
 * (filling in a blank composer when this copy knows one), parse, name (INDEX.csv row, else
 * the file name, read the other way round when only its right side is a known artist, else the
 * artist folder that holds it, else Track 0's name for stub titles: [TitleHeuristics], DESIGN.md ›
 * v1.10.1), sort (Classical or Modern: [Genres.of], v1.14 — M37), save, then insert 25 per
 * transaction. What a Mac adds beside the music (`__MACOSX/`, `._name`) is skipped before
 * anything is counted. Files listed in INDEX.csv go first, so its names win over copies elsewhere
 * in the tree.
 * Text is cut to [TextLimits] before it is stored; a file too large to read in the memory left
 * (an [OutOfMemoryError]) counts as failed, and so does a file whose sender or database throws
 * anything else: one bad file never ends the import, nor the app. One import runs at a time;
 * [progress] follows it, and always finishes; a quiet one ([importOpened]) keeps a progress of its own.
 */
class Importer(
    private val store: ImportStore,
    private val files: PieceFiles,
    private val progress: MutableStateFlow<ImportProgress>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {
    private val running = Mutex()

    suspend fun import(context: Context, source: ImportSource): ImportProgress = running.withLock {
        withContext(io) {
            progress.value = ImportProgress(finished = false)
            val job = currentCoroutineContext()[Job]
            val opened = try {
                openSource(context, source) { job?.isActive == false }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {   // unreadable zip, revoked grant, vanished folder, a provider that throws
                log("Couldn't open the import" + detail(e))
                return@withContext ImportProgress(done = 1, total = 1, failed = 1).also { progress.value = it }
            } catch (e: OutOfMemoryError) {   // a listing too large for the memory left
                log("Couldn't open the import: $TOO_LARGE")
                return@withContext ImportProgress(done = 1, total = 1, failed = 1).also { progress.value = it }
            }
            runOpened(opened, progress)
        }
    }

    /**
     * Imports a source already opened (the web panel's upload: one MIDI file in memory, or a zip
     * saved in the cache), as [import] does once it has opened its own: one import at a time, the
     * same caps, [progress] kept, and [source] closed at the end whatever happens. [quiet] (v1.14 —
     * M37): what the tablet makes itself (a recording, a take a crash left, Studio's pieces) follows a
     * progress of its own, so no import bar, panel import line or notification tells of it.
     */
    suspend fun importOpened(source: OpenedSource, quiet: Boolean = false): ImportProgress = running.withLock {
        withContext(io) {
            val sink = if (quiet) MutableStateFlow(ImportProgress(finished = false)) else progress.apply { value = ImportProgress(finished = false) }
            runOpened(source, sink)
        }
    }

    /** [run] over [opened], then closes it; a failure that is no file's fault counts the rest as failed. */
    private suspend fun runOpened(opened: OpenedSource, sink: MutableStateFlow<ImportProgress>): ImportProgress = try {
        opened.use { run(it, sink) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {   // not a file's fault (each is caught on its own): the rest count as failed
        log("The import stopped" + detail(e))
        val last = sink.value
        last.copy(done = last.total, failed = last.failed + (last.total - last.done), current = null, finished = true)
            .also { sink.value = it }
    }

    /** Imports everything [source] lists and returns the final tally; [sink] follows it. */
    suspend fun run(source: OpenedSource, sink: MutableStateFlow<ImportProgress> = progress): ImportProgress {
        source.limited?.let(log)
        // What a Mac adds beside the music is neither a piece nor a failure: it is not counted at all (D1).
        val listed = source.items.filterNot { isMacMetadata(it.relativePath) }
        val items = listed.sortedByDescending { source.rowFor(it) != null }
        val folders = ImportFolders(listed.map { it.relativePath })
        val names = BatchNames(store, folders)
        val genres = BatchGenres(store.artistGenres())
        var state = ImportProgress(total = items.size, finished = false)
        sink.value = state
        val seen = HashSet<String>()
        val batch = ArrayList<PieceEntity>(BATCH_SIZE)
        // D2: every piece of a zip, a folder or a loose upload goes into its playlist, those already there too,
        // unless an INDEX.csv row of the batch places it (in its own collection, as before).
        val found = ArrayList<Pair<String, String>>()   // (path, SHA-256)
        val placed = HashSet<String>()

        suspend fun flush() {
            if (batch.isEmpty()) return
            state = try {
                val inserted = store.insertAll(batch.toList())
                state.copy(imported = state.imported + inserted, duplicates = state.duplicates + batch.size - inserted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                log("Couldn't save ${batch.size} pieces" + detail(e))
                state.copy(failed = state.failed + batch.size)
            }
            batch.clear()
        }

        for (item in items) {
            currentCoroutineContext().ensureActive()
            state = state.copy(current = item.name)
            sink.value = state
            val outcome = try {
                prepare(item, source.rowFor(item), seen, names, genres)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {   // a sender's provider or the database threw: this file fails, the rest go on
                logFile(item, "couldn't be read" + detail(e))
                Outcome.Failed
            } catch (e: OutOfMemoryError) {   // the parse's arrays are garbage again; the next file may fit
                logFile(item, TOO_LARGE)
                Outcome.Failed
            }
            when (outcome) {
                is Outcome.Ready -> batch += outcome.piece
                is Outcome.Duplicate -> state = state.copy(duplicates = state.duplicates + 1, filled = state.filled + if (outcome.filled) 1 else 0)
                Outcome.Failed -> state = state.copy(failed = state.failed + 1)
            }
            outcome.sha?.let { sha ->
                if (source.rowFor(item)?.collection.isNullOrBlank()) found += item.relativePath to sha else placed += sha
            }
            state = state.copy(done = state.done + 1)
            if (batch.size == BATCH_SIZE) flush()
            sink.value = state
        }
        flush()
        val playlist = link(source.batch, folders.root, found, placed)
        state = state.copy(current = null, finished = true, playlist = playlist)
        sink.value = state
        log("Import: ${state.imported} new, ${state.duplicates} already there, ${state.failed} failed" + (playlist?.let { ", in a playlist" + named(it.name) } ?: ""))
        return state
    }

    /**
     * The batch's pieces ([found], less those an INDEX row of the batch [placed]) at the end of its
     * playlist (DESIGN.md › v1.10.1, D2), in path order ([PathOrder]): a zip's or a folder's, named after
     * its [root] folder, else the zip or the folder itself; a loose upload's, Uploads. The name is cut to
     * [TextLimits.COLLECTION]; a playlist of that name is found or made ([ImportStore.linkToPlaylist]).
     * Null when the batch has no playlist or nothing to put in it, or when the library refused.
     */
    private suspend fun link(batch: ImportBatch, root: String?, found: List<Pair<String, String>>, placed: Set<String>): ImportedPlaylist? {
        val (name, imported) = when (batch) {
            ImportBatch.None -> return null
            is ImportBatch.Named -> (root ?: batch.name) to true
            ImportBatch.Uploads -> ImportBatch.UPLOADS to false
        }
        val shas = found.filter { it.second !in placed }.sortedWith(compareBy(PathOrder) { it.first }).map { it.second }.distinct()
        if (shas.isEmpty()) return null
        val kept = TextLimits.clip(TitleHeuristics.cleanText(name), TextLimits.COLLECTION).ifEmpty { ImportBatch.UPLOADS }
        return try {
            store.linkToPlaylist(kept, imported, shas)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {   // the pieces stay in the library; only their playlist is missing
            log("Couldn't put the pieces in their playlist" + detail(e))
            null
        }
    }

    /** " called <name>" in debug builds; nothing in release builds, whose log never names the person's folders. */
    private fun named(name: String): String = if (BuildConfig.DEBUG) " called $name" else ""

    /**
     * A file's trouble in the log. Debug builds name the file; release builds (whose warnings stay
     * in the log) do not: paths come from the person's own folders and other apps' files.
     */
    private fun logFile(item: ImportItem, problem: String) {
        log(if (BuildConfig.DEBUG) "${item.relativePath}: $problem" else "A file: $problem")
    }

    /** An exception's message for the log, in debug builds only: it may name a file or a URI. */
    private fun detail(e: Throwable): String = if (BuildConfig.DEBUG) ": ${e.message}" else ""

    private sealed interface Outcome {
        /** The piece's SHA-256, when its bytes were read. */
        val sha: String?

        class Ready(val piece: PieceEntity) : Outcome {
            override val sha: String get() = piece.sha256
        }

        /** Already in the library, or earlier in this batch; [filled]: its blank composer was filled in. */
        class Duplicate(override val sha: String, val filled: Boolean = false) : Outcome

        data object Failed : Outcome {
            override val sha: String? get() = null
        }
    }

    private suspend fun prepare(item: ImportItem, row: IndexCsv.Row?, seen: MutableSet<String>, names: BatchNames, genres: BatchGenres): Outcome {
        val bytes = try {
            item.open().use { ImportLimits.readCapped(it, MAX_BYTES) }
        } catch (e: IOException) {
            logFile(item, "couldn't be read" + detail(e))
            return Outcome.Failed
        } ?: run {
            logFile(item, "larger than 8 MB, skipped")
            return Outcome.Failed
        }
        val sha = sha256(bytes)
        if (!seen.add(sha)) return Outcome.Duplicate(sha)
        store.findBySha(sha)?.let { existing ->
            if (existing.composer.isBlank()) {
                val meta = names.metadata(item, row, emptyList())
                if (meta.composer.isNotEmpty()) {
                    val name = names.composer(meta)   // sorted again by its new name (v1.14 — M37)
                    store.fillComposer(existing.copy(genre = genres.of(TextLimits.clip(name.key, TextLimits.COMPOSER), existing.collection)), name)
                    return Outcome.Duplicate(sha, filled = true)
                }
            }
            return Outcome.Duplicate(sha)
        }
        val midi = try {
            SmfParser.parse(bytes)
        } catch (e: SmfException) {
            logFile(item, e.message.orEmpty())   // the parser's own plain-English reasons
            return Outcome.Failed
        }
        midi.warnings.forEach { logFile(item, it) }
        val fileName = try {
            files.write(sha, bytes)
        } catch (e: IOException) {
            logFile(item, "couldn't be saved" + detail(e))
            return Outcome.Failed
        }
        val meta = names.metadata(item, row, midi.sequenceNames)
        val piece = PieceEntity(
            title = meta.title,
            composer = "",
            composerKey = "",
            composerShort = "",
            collection = meta.collection?.let { TextLimits.clip(it, TextLimits.COLLECTION) },
            sha256 = sha,
            fileName = fileName,
            sourceName = TextLimits.clip(item.relativePath, TextLimits.SOURCE_NAME),
            sizeBytes = bytes.size.toLong(),
            durationMs = midi.durationMicros / 1000,
            noteCount = midi.noteCount,
            addedAt = clock(),
            searchText = "",
            titleKey = "",
        ).named(meta.title, names.composer(meta))   // named() cuts the title
        return Outcome.Ready(piece.copy(genre = genres.of(piece.composerKey, piece.collection)))
    }

    /**
     * How one import reads its pieces' names (DESIGN.md › v1.10.1, D1 and D3): its own [folders] (the
     * artist folders below its root, and so the names a reversed file name may end with), and what the
     * library says about a composer's whole-name key, asked once each.
     */
    private class BatchNames(private val store: ImportStore, private val folders: ImportFolders) {
        private val keys = HashMap<String, Boolean>()

        fun metadata(item: ImportItem, row: IndexCsv.Row?, sequenceNames: List<String>): TitleHeuristics.Metadata =
            TitleHeuristics.metadata(item.name, row, sequenceNames, folders.artistFolderOf(item.relativePath), folders::artistNamed)

        /** [meta]'s composer, read as its source asks (cut to [TextLimits.COMPOSER] first). */
        suspend fun composer(meta: TitleHeuristics.Metadata): ComposerNames.Name {
            val raw = TextLimits.clip(meta.composer, TextLimits.COMPOSER)
            return when (meta.source) {
                TitleHeuristics.Source.INDEX, TitleHeuristics.Source.NONE -> ComposerNames.normalize(raw)
                TitleHeuristics.Source.REVERSED, TitleHeuristics.Source.FOLDER -> ComposerNames.artist(raw)
                TitleHeuristics.Source.FILE_NAME -> ComposerNames.resolve(raw) { key ->
                    key in folders.artistKeys || keys.getOrPut(key) { store.hasComposerKey(key) }
                }
            }
        }
    }

    /**
     * How one import sorts its pieces (v1.14 — M37, [Genres.of]): by the library's [artists] (each key's majority, read
     * once a run), and by what the run itself learns: a key a strong rule made Classical (a pack collection's
     * "Anonymous") makes the run's later pieces by it Classical too, as the upgrade's second pass does.
     */
    private class BatchGenres(artists: Map<String, Int>) {
        private val known = HashMap(artists)

        fun of(composerKey: String, collection: String?): Int {
            val genre = Genres.of(composerKey, collection, known)
            if (genre == Genres.CLASSICAL && composerKey.isNotBlank() && composerKey !in known) known[composerKey] = genre
            return genre
        }
    }

    private companion object {
        const val TAG = "Importer"
        const val BATCH_SIZE = 25
        const val MAX_BYTES = 8 * 1024 * 1024
        const val TOO_LARGE = "File too large to read"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

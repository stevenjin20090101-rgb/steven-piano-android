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
}

/**
 * Brings MIDI files into the library. Per file: read (8 MB cap), SHA-256, skip duplicates
 * (filling in a blank composer when this copy knows one), parse, name (INDEX.csv row, else
 * the file name, else Track 0's name for stub titles), save, then insert 25 per transaction.
 * Files listed in INDEX.csv go first, so its names win over copies elsewhere in the tree.
 * Text is cut to [TextLimits] before it is stored; a file too large to read in the memory left
 * (an [OutOfMemoryError]) counts as failed, and so does a file whose sender or database throws
 * anything else: one bad file never ends the import, nor the app. One import runs at a time;
 * [progress] follows it, and always finishes.
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
            runOpened(opened)
        }
    }

    /**
     * Imports a source already opened (the web panel's upload: one MIDI file in memory, or a zip
     * saved in the cache), as [import] does once it has opened its own: one import at a time, the
     * same caps, [progress] kept, and [source] closed at the end whatever happens.
     */
    suspend fun importOpened(source: OpenedSource): ImportProgress = running.withLock {
        withContext(io) {
            progress.value = ImportProgress(finished = false)
            runOpened(source)
        }
    }

    /** [run] over [opened], then closes it; a failure that is no file's fault counts the rest as failed. */
    private suspend fun runOpened(opened: OpenedSource): ImportProgress = try {
        opened.use { run(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {   // not a file's fault (each is caught on its own): the rest count as failed
        log("The import stopped" + detail(e))
        val last = progress.value
        last.copy(done = last.total, failed = last.failed + (last.total - last.done), current = null, finished = true)
            .also { progress.value = it }
    }

    /** Imports everything [source] lists and returns the final tally. */
    suspend fun run(source: OpenedSource): ImportProgress {
        source.limited?.let(log)
        val items = source.items.sortedByDescending { source.rowFor(it) != null }
        var state = ImportProgress(total = items.size, finished = false)
        progress.value = state
        val seen = HashSet<String>()
        val batch = ArrayList<PieceEntity>(BATCH_SIZE)

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
            progress.value = state
            val outcome = try {
                prepare(item, source.rowFor(item), seen)
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
                Outcome.Duplicate -> state = state.copy(duplicates = state.duplicates + 1)
                Outcome.Failed -> state = state.copy(failed = state.failed + 1)
            }
            state = state.copy(done = state.done + 1)
            if (batch.size == BATCH_SIZE) flush()
            progress.value = state
        }
        flush()
        state = state.copy(current = null, finished = true)
        progress.value = state
        log("Import: ${state.imported} new, ${state.duplicates} already there, ${state.failed} failed")
        return state
    }

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
        class Ready(val piece: PieceEntity) : Outcome
        data object Duplicate : Outcome
        data object Failed : Outcome
    }

    private suspend fun prepare(item: ImportItem, row: IndexCsv.Row?, seen: MutableSet<String>): Outcome {
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
        if (!seen.add(sha)) return Outcome.Duplicate
        store.findBySha(sha)?.let { existing ->
            if (existing.composer.isBlank()) {
                val composer = TextLimits.clip(TitleHeuristics.metadata(item.name, row, emptyList()).composer, TextLimits.COMPOSER)
                if (composer.isNotEmpty()) store.fillComposer(existing, ComposerNames.normalize(composer))
            }
            return Outcome.Duplicate
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
        val meta = TitleHeuristics.metadata(item.name, row, midi.sequenceNames)
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
        ).named(meta.title, ComposerNames.normalize(TextLimits.clip(meta.composer, TextLimits.COMPOSER)))   // named() cuts the title
        return Outcome.Ready(piece)
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

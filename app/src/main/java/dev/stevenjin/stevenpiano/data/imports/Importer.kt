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
import dev.stevenjin.stevenpiano.data.PieceFiles
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.data.db.named
import dev.stevenjin.stevenpiano.midi.SmfException
import dev.stevenjin.stevenpiano.midi.SmfParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
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
 * One import runs at a time; [progress] follows it.
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
            val opened = try {
                openSource(context, source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {   // unreadable zip, revoked grant, vanished folder
                log("Couldn't open the import: ${e.message}")
                return@withContext ImportProgress(done = 1, total = 1, failed = 1).also { progress.value = it }
            }
            opened.use { run(it) }
        }
    }

    /** Imports everything [source] lists and returns the final tally. */
    suspend fun run(source: OpenedSource): ImportProgress {
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
                log("Couldn't save ${batch.size} pieces: ${e.message}")
                state.copy(failed = state.failed + batch.size)
            }
            batch.clear()
        }

        for (item in items) {
            currentCoroutineContext().ensureActive()
            state = state.copy(current = item.name)
            progress.value = state
            when (val outcome = prepare(item, source.rowFor(item), seen)) {
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

    private sealed interface Outcome {
        class Ready(val piece: PieceEntity) : Outcome
        data object Duplicate : Outcome
        data object Failed : Outcome
    }

    private suspend fun prepare(item: ImportItem, row: IndexCsv.Row?, seen: MutableSet<String>): Outcome {
        val bytes = try {
            item.open().use { readCapped(it) }
        } catch (e: IOException) {
            log("${item.relativePath}: ${e.message}")
            return Outcome.Failed
        } ?: run {
            log("${item.relativePath}: larger than 8 MB, skipped")
            return Outcome.Failed
        }
        val sha = sha256(bytes)
        if (!seen.add(sha)) return Outcome.Duplicate
        store.findBySha(sha)?.let { existing ->
            if (existing.composer.isBlank()) {
                val composer = TitleHeuristics.metadata(item.name, row, emptyList()).composer
                if (composer.isNotEmpty()) store.fillComposer(existing, ComposerNames.normalize(composer))
            }
            return Outcome.Duplicate
        }
        val midi = try {
            SmfParser.parse(bytes)
        } catch (e: SmfException) {
            log("${item.relativePath}: ${e.message}")
            return Outcome.Failed
        }
        midi.warnings.forEach { log("${item.relativePath}: $it") }
        val fileName = try {
            files.write(sha, bytes)
        } catch (e: IOException) {
            log("${item.relativePath}: ${e.message}")
            return Outcome.Failed
        }
        val meta = TitleHeuristics.metadata(item.name, row, midi.sequenceNames)
        val piece = PieceEntity(
            title = meta.title,
            composer = "",
            composerKey = "",
            composerShort = "",
            collection = meta.collection,
            sha256 = sha,
            fileName = fileName,
            sourceName = item.relativePath,
            sizeBytes = bytes.size.toLong(),
            durationMs = midi.durationMicros / 1000,
            noteCount = midi.noteCount,
            addedAt = clock(),
            searchText = "",
            titleKey = "",
        ).named(meta.title, ComposerNames.normalize(meta.composer))
        return Outcome.Ready(piece)
    }

    private companion object {
        const val TAG = "Importer"
        const val BATCH_SIZE = 25
        const val MAX_BYTES = 8 * 1024 * 1024

        /** The whole stream, or null when it is larger than [MAX_BYTES]. */
        fun readCapped(input: InputStream): ByteArray? {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return out.toByteArray()
                if (out.size() + n > MAX_BYTES) return null
                out.write(buffer, 0, n)
            }
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

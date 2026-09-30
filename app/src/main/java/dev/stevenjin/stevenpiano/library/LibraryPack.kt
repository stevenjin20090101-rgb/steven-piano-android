// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.library

import dev.stevenjin.stevenpiano.data.imports.ImportLimits
import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import dev.stevenjin.stevenpiano.data.imports.ImportSource
import dev.stevenjin.stevenpiano.data.imports.ZipSource
import dev.stevenjin.stevenpiano.update.DownloadFailure
import dev.stevenjin.stevenpiano.update.DownloadProblem
import dev.stevenjin.stevenpiano.update.UpdateServer
import dev.stevenjin.stevenpiano.update.UpdateSource
import dev.stevenjin.stevenpiano.update.VerifiedDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** What Steven's library pack is doing (v1.10 — M27). */
sealed interface PackState {
    /** Whether a load is under way: a check never replaces it, and a second load waits for it to end. */
    val busy: Boolean get() = this == Checking || this is Downloading || this == Importing

    /** Nothing under way, and no pack newer than the one loaded is known. */
    data object Idle : PackState

    /** A load is asking for the pack's manifest. */
    data object Checking : PackState

    /**
     * A pack newer than the one loaded is on offer: its [version], its [pieces], the [sizeBytes] to
     * download, and [newPieces], how many of its pieces no earlier pack offered this tablet (all of them
     * before the first load).
     */
    data class Offered(val version: Int, val pieces: Int, val sizeBytes: Long, val newPieces: Int) : PackState

    /** The pack's zip arriving: [done] of [total] bytes. */
    data class Downloading(val done: Long, val total: Long) : PackState

    /** The pack's pieces going into the library (the import's own progress says how far). */
    data object Importing : PackState

    /** A load finished: [added] pieces came into the library. */
    data class Done(val added: Int) : PackState

    /** A load stopped: [line] says why, in words. */
    data class Failed(val line: String) : PackState
}

/** What the library pack says when a load goes wrong (one line, in words, no red; DESIGN.md › v1.10 — M27). */
object LibraryFailures {
    const val OFFLINE = "Loading Steven's library needs an internet connection."
    const val UNREACHABLE = "Couldn't reach the download server."
    const val UNREADABLE = "The library's list couldn't be read."
    const val MISMATCH = "The download didn't match the library; try again."
    const val STOPPED = "The download stopped; try again."
    const val NO_ROOM = "There isn't enough free space for the library."
    const val NOT_READ = "The library's pieces couldn't be read."
    const val NOT_STARTED = "The library couldn't start loading; try again."

    fun of(problem: DownloadProblem): String = when (problem) {
        DownloadProblem.Mismatch -> MISMATCH
        DownloadProblem.Stopped -> STOPPED
        DownloadProblem.Unreachable -> UNREACHABLE
        DownloadProblem.NoRoom -> NO_ROOM
    }
}

/**
 * Which pieces each pack offered this tablet: `offered-v<n>.txt` in [dir] (`filesDir/library`), pack
 * `n`'s SHA-256s one per line (its INDEX.csv's `sha256` column), written once its import has finished.
 * An update brings only the pieces no file here names, so a piece the person deleted never comes back
 * by itself, and nothing is ever deleted. A file over [MAX_FILE_BYTES] is not read. Blocking I/O.
 */
class OfferedPacks(private val dir: File) {
    /** Every SHA-256 a pack has offered. */
    fun all(): Set<String> {
        val out = HashSet<String>()
        for (file in dir.listFiles { f -> NAME.matches(f.name) }.orEmpty()) {
            if (!file.isFile || file.length() > MAX_FILE_BYTES) continue
            try {
                file.useLines { lines -> lines.map { it.trim() }.filter { SHA256.matches(it) }.forEach { out += it.lowercase() } }
            } catch (e: IOException) {
                continue
            }
        }
        return out
    }

    /** Records [shas] as what pack [version] offered: written to a part file, then renamed over the old one. */
    fun record(version: Int, shas: Collection<String>) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("The library's folder can't be made")
        val file = File(dir, "offered-v$version.txt")
        val part = File(dir, file.name + VerifiedDownloader.PART)
        try {
            part.writeText(shas.toSortedSet().joinToString("") { "$it\n" })
            if (!part.renameTo(file)) throw IOException("The pack's pieces can't be recorded")
        } finally {
            part.delete()
        }
    }

    companion object {
        /** 20,000 pieces (the zip's entry cap) take 1.3 MB. */
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
        private val NAME = Regex("offered-v[0-9]{1,5}\\.txt")
        private val SHA256 = Regex("[0-9a-fA-F]{64}")
    }
}

/**
 * Steven's library from GitHub (v1.10 — M27): the pack's manifest from [source] (`releases/library.json`
 * on `main`, [LibraryManifest]), its zip downloaded to the manifest's size and SHA-256
 * ([VerifiedDownloader]: at most [UpdateSource.MAX_LIBRARY_BYTES], the part file hashed as it arrives
 * and deleted on any failure or cancel) into [cacheDir] (`cacheDir/library`), then imported through the
 * app's importer ([import], `ImportSource.LocalZip`: the importer's caps, the zip deleted after), then
 * recorded: [offered] gets the pack's pieces and [recordLoaded] its version (`settings.libraryPackVersion`).
 *
 * - [check] asks for the manifest (the daily [runSchedule] while the app is open and online with Check
 *   for updates on, and on demand when the Library shows the offer): a pack newer than [loadedVersion]
 *   is [PackState.Offered], with how many of its pieces are new to this tablet.
 * - [load] starts a load ([start]: `LibraryService`, which calls [run]); the Library's buttons and the
 *   console's `library.load` (M26) call it. [state] follows it; [newerAvailable] says whether a pack
 *   newer than the one loaded is known.
 * - An update imports only the pieces no earlier pack offered ([OfferedPacks]) and never deletes one. A
 *   load that stops (cancelled, the network gone, the process ended) records nothing: the pack stays on
 *   offer, and the next load brings what is still missing (what came in stays; the importer's own hash
 *   skips it).
 */
class LibraryPack(
    private val source: UpdateSource,
    private val server: UpdateServer,
    private val downloader: VerifiedDownloader,
    private val offered: OfferedPacks,
    private val cacheDir: File,
    /** The version loaded, as the settings keep it (read from them, so never the default before they are known). */
    private val loadedVersion: Flow<Int>,
    private val recordLoaded: suspend (Int) -> Unit,
    private val import: suspend (ImportSource.LocalZip) -> ImportProgress,
    private val online: StateFlow<Boolean>,
    scope: CoroutineScope,
    /** Starts [run] (`LibraryService`, or the app's process when Android refuses the service); [run]'s argument. */
    private val start: (everything: Boolean) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<PackState>(PackState.Idle)
    val state: StateFlow<PackState> = _state.asStateFlow()

    /** The newest pack's manifest a check or a load has read; null before the first. */
    private val latest = MutableStateFlow<LibraryManifest?>(null)

    /** Whether a pack newer than the one loaded is known (a fresh tablet: any pack at all, once asked). */
    val newerAvailable: StateFlow<Boolean> = combine(latest, loadedVersion) { manifest, loaded -> manifest != null && manifest.version > loaded }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _offer = MutableStateFlow<PackState.Offered?>(null)

    /**
     * The pack on offer, newer than the one loaded, as the last check or load found it (the rows' numbers
     * whatever the load is doing); null when none is known. A load that stops goes back to it.
     */
    val offer: StateFlow<PackState.Offered?> = _offer.asStateFlow()

    private val checks = Mutex()
    private val runs = Mutex()

    /** When the manifest was last asked for (a check or a load), by [now]; null before the first time in this process. */
    @Volatile
    var lastCheckedAt: Long? = null
        private set

    /**
     * Asks for the manifest, unless a load is under way (it asks for its own), the device is offline, or
     * [maxAgeMs] has not passed since the last time it was asked (0: ask now). A pack newer than the one
     * loaded becomes [PackState.Offered]; none, [PackState.Idle]. A failure changes nothing on screen (it
     * is logged): the next check asks again.
     */
    suspend fun check(maxAgeMs: Long = 0) = checks.withLock {
        if (_state.value.busy || !online.value) return@withLock
        val last = lastCheckedAt
        val at = now()
        if (last != null && at >= last && at - last < maxAgeMs) return@withLock
        lastCheckedAt = at
        val manifest = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Library check failed: ${e.javaClass.simpleName}")
            return@withLock
        }
        val next = offerFor(manifest)
        _offer.value = next as? PackState.Offered
        _state.update { current -> if (current.busy) current else next }
    }

    /**
     * Automatic checks, for as long as the caller runs this (the activity, while started): whenever
     * [enabled] (Check for updates) is on and the device online, a check when one is due, then one every
     * [INTERVAL_MS]. A check skipped (a load under way) tries again after [RETRY_MS].
     */
    suspend fun runSchedule(enabled: Flow<Boolean>) {
        combine(enabled, online) { on, connected -> on && connected }
            .distinctUntilChanged()
            .collectLatest { ready ->
                if (!ready) return@collectLatest
                while (true) {
                    delay(untilDue())
                    check()
                    if (untilDue() == 0L) delay(RETRY_MS)
                }
            }
    }

    /** How long until an automatic check is due: none yet in this process, or the clock went back, is now. */
    fun untilDue(): Long {
        val last = lastCheckedAt ?: return 0L
        val at = now()
        if (at < last) return 0L
        return (last + INTERVAL_MS - at).coerceAtLeast(0L)
    }

    /**
     * Loads the newest pack: [start] runs [run] (`LibraryService` holds the foreground for it; when Android
     * refuses a foreground service, as from the background, it runs in the app's process without the
     * notification). [everything]: every piece of the pack, even one an earlier pack offered that the person
     * has deleted since, and even when the pack is the one loaded already (the empty Library's button);
     * otherwise a pack newer than the one loaded, and only its pieces no earlier pack offered. False when a
     * load is under way already (or could not start: [state] then says so).
     */
    fun load(everything: Boolean = false): Boolean {
        while (true) {
            val current = _state.value
            if (current.busy) return false
            if (_state.compareAndSet(current, PackState.Checking)) break
        }
        return try {
            start(everything)
            true
        } catch (e: RuntimeException) {
            log("The library's load couldn't start: ${e.javaClass.simpleName}")
            _state.value = PackState.Failed(LibraryFailures.NOT_STARTED)
            false
        }
    }

    /** A failure's line (or a finished load's) was dismissed: the offer comes back, if there is one. */
    fun dismiss() {
        _state.update { current -> if (current is PackState.Failed || current is PackState.Done) _offer.value ?: PackState.Idle else current }
    }

    /** The load itself (see [load]), one at a time; [state] follows it and ends [PackState.Done] or [PackState.Failed]. */
    suspend fun run(everything: Boolean) = runs.withLock {
        _state.value = PackState.Checking
        val outcome = try {
            loadNewest(everything)
        } catch (e: CancellationException) {
            _state.value = _offer.value ?: PackState.Idle
            throw e
        } catch (e: Exception) {   // anything the steps below do not word themselves
            log("The library's load stopped: ${e.javaClass.simpleName}")
            PackState.Failed(LibraryFailures.NOT_READ)
        }
        if (outcome is PackState.Done) _offer.value = null
        _state.value = outcome
    }

    private suspend fun loadNewest(everything: Boolean): PackState {
        if (!online.value) return PackState.Failed(LibraryFailures.OFFLINE)
        lastCheckedAt = now()
        val manifest = try {
            fetch()
        } catch (e: InvalidLibraryManifest) {
            log("Library: ${e.message}")
            return PackState.Failed(LibraryFailures.UNREADABLE)
        } catch (e: IOException) {
            log("Library: the manifest couldn't be fetched (${e.javaClass.simpleName})")
            return PackState.Failed(LibraryFailures.UNREACHABLE)
        }
        _offer.value = offerFor(manifest) as? PackState.Offered
        val loaded = loadedVersion.first()
        if (!everything && manifest.version <= loaded) return PackState.Idle   // up to date
        val skip = if (everything) emptySet() else withContext(io) { offered.all() }
        _state.value = PackState.Downloading(0, manifest.sizeBytes)
        val target = VerifiedDownloader.Target(
            url = manifest.url,
            dir = cacheDir,
            name = manifest.file,
            sizeBytes = manifest.sizeBytes,
            sha256 = manifest.sha256,
            cap = UpdateSource.MAX_LIBRARY_BYTES,
            freeMargin = freeMargin(manifest.sizeBytes),
            progressEveryBytes = PROGRESS_EVERY,
        )
        val file = try {
            downloader.download(target) { done, total -> _state.value = PackState.Downloading(done, total) }
        } catch (e: DownloadFailure) {
            log("Library: the download failed (${e.problem})")
            return PackState.Failed(LibraryFailures.of(e.problem))
        }
        try {
            _state.value = PackState.Importing
            // The pack's pieces, from its own index, before the import reads (and then deletes) the zip.
            val shas = withContext(io) { ZipSource(file).use { it.readIndex()?.sha256s() } }.orEmpty()
            val result = import(ImportSource.LocalZip(file, skip))
            if (result.total > 0 && result.imported + result.duplicates == 0) return PackState.Failed(LibraryFailures.NOT_READ)
            withContext(io) { offered.record(manifest.version, shas) }
            recordLoaded(maxOf(loaded, manifest.version))
            log("Library: version ${manifest.version} loaded, ${result.imported} pieces added, ${result.duplicates} there already, ${result.failed} failed")
            return PackState.Done(result.imported)
        } finally {
            withContext(NonCancellable + io) { file.delete() }   // the import deletes it; this is for a stop before it opened it
        }
    }

    private suspend fun fetch(): LibraryManifest = LibraryManifest.parse(server.manifest(), source).also { latest.value = it }

    /** [manifest] as an offer, when it is newer than the pack loaded: how many of its pieces no pack has offered yet. */
    private suspend fun offerFor(manifest: LibraryManifest): PackState {
        if (manifest.version <= loadedVersion.first()) return PackState.Idle
        val known = withContext(io) { offered.all().size }
        return PackState.Offered(manifest.version, manifest.pieces, manifest.sizeBytes, (manifest.pieces - known).coerceAtLeast(0))
    }

    companion object {
        /** One automatic check a day while the app is open. */
        const val INTERVAL_MS = 24L * 60 * 60 * 1000

        /** A scheduled check that was skipped tries again this much later. */
        const val RETRY_MS = 60_000L

        /** How often the download reports its progress. */
        const val PROGRESS_EVERY = 256L * 1024

        /**
         * Free space kept beside the zip: room for its pieces once unpacked (version 1: 92.9 MB of MIDI from a
         * 61.2 MB zip) and the margin every import keeps ([ImportLimits.SPACE_MARGIN_BYTES]).
         */
        fun freeMargin(sizeBytes: Long): Long = 2 * sizeBytes + ImportLimits.SPACE_MARGIN_BYTES

        /** The pack's download folder, `cacheDir/library`. */
        const val CACHE_DIR = "library"

        /** Where the offered pieces are kept, `filesDir/library`. */
        const val FILES_DIR = "library"

        /** What a stopped load left in [dir] (`cacheDir/library`: a zip, a part file), last written before [before]. Blocking. */
        fun sweep(dir: File, before: Long): Int = dir.listFiles().orEmpty().count { it.isFile && it.lastModified() < before && it.delete() }
    }
}

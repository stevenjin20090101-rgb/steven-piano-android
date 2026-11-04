// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano

import android.app.Application
import android.util.Log
import dev.stevenjin.stevenpiano.AppGraph.Companion.TAG
import dev.stevenjin.stevenpiano.AppGraph.Companion.debugLog
import dev.stevenjin.stevenpiano.data.LibraryRepository
import dev.stevenjin.stevenpiano.data.PieceFiles
import dev.stevenjin.stevenpiano.data.art.ArtFiles
import dev.stevenjin.stevenpiano.data.art.ArtworkRepository
import dev.stevenjin.stevenpiano.data.builtin.BuiltInCatalogue
import dev.stevenjin.stevenpiano.data.builtin.BuiltInPlaylists
import dev.stevenjin.stevenpiano.data.builtin.GenrePlaylists
import dev.stevenjin.stevenpiano.data.builtin.StudioPlaylist
import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import dev.stevenjin.stevenpiano.data.imports.ImportSource
import dev.stevenjin.stevenpiano.data.imports.Importer
import dev.stevenjin.stevenpiano.library.LibraryPack
import dev.stevenjin.stevenpiano.library.OfferedPacks
import dev.stevenjin.stevenpiano.net.AppleCatalog
import dev.stevenjin.stevenpiano.net.WikipediaClient
import dev.stevenjin.stevenpiano.service.ArtworkService
import dev.stevenjin.stevenpiano.service.LibraryService
import dev.stevenjin.stevenpiano.studio.Studio
import dev.stevenjin.stevenpiano.update.HttpUpdateServer
import dev.stevenjin.stevenpiano.update.UpdateSource
import dev.stevenjin.stevenpiano.update.VerifiedDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The library's part of [AppGraph]: the pieces, the built-in playlists, imports, artwork and Steven's library. The graph
 * makes it once and everything else reaches it through the graph's members; what it holds is lazy or asked for.
 */
internal class LibraryWiring(private val graph: AppGraph, private val app: Application) {
    private val appScope = graph.appScope

    private val pieceFiles = PieceFiles(app.filesDir)
    val library: LibraryRepository by lazy { LibraryRepository(graph.database, pieceFiles) }

    /** The built-in playlists (Popular, Recognisable, Epic on piano), read from the app's assets the first time, off the main thread. */
    val builtIns: BuiltInPlaylists by lazy { BuiltInCatalogue.load(app) }

    /** Sets the built-in playlists' pieces from the library as it is now. A failure is logged and changes nothing: the next refresh tries again. */
    suspend fun refreshBuiltIns() = withContext(Dispatchers.IO) {
        try {
            builtIns.refresh(library)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The built-in playlists couldn't be refreshed", e)
        }
        refreshGenrePlaylists()
        refreshStudioShelf()
    }

    /** The genre playlists (v1.37 — M73) from the pieces' Apple genres: with the built-in lists, and as genres arrive. */
    suspend fun refreshGenrePlaylists(): Unit = withContext(Dispatchers.IO) {
        try {
            GenrePlaylists.refresh(library)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The genre playlists couldn't be refreshed", e)
        }
        Unit
    }

    /** "Made in Studio" (v1.12 — M30) from Studio's history: after each piece Studio saves, and with the built-in lists. */
    suspend fun refreshStudioShelf() = withContext(Dispatchers.IO) {
        try {
            StudioPlaylist.refresh(library, graph.studioHistory.madeHere())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Made in Studio couldn't be refreshed", e)
        }
    }

    private val importState = MutableStateFlow(ImportProgress.Idle)
    val importProgress: StateFlow<ImportProgress> = importState.asStateFlow()
    /**
     * v1.41: a piece that takes a new version's file in place has its roll card drawn again. v1.43: a cover a zip's
     * INDEX.csv names is kept as a found album cover ([ArtworkRepository.keepZipCover]).
     */
    val importer: Importer by lazy {
        Importer(
            library, pieceFiles, importState,
            replaced = { artwork.forgetRollCard(it) },
            covers = { id, image, album -> artwork.keepZipCover(id, image, album) },
        )
    }

    /**
     * Portraits, notes, album covers, playlist photos and roll cards (their network use: Wikipedia's two hosts, and with
     * Album covers on Apple's catalogue, `itunes.apple.com` and `*.mzstatic.com`; v1.15 — M40).
     */
    val artwork: ArtworkRepository by lazy {
        ArtworkRepository(
            app, graph.database.artwork(), library, ArtFiles(app.filesDir), WikipediaClient(), AppleCatalog(),
            coversWanted = { graph.settingsRepository.settings.first().let { it.fetchArtworkAutomatically && it.albumCovers } },
            coverRuleSince = { graph.settingsRepository.coverRuleSince(System.currentTimeMillis()) },
            network = graph.network,
            scope = appScope,
        )
    }

    /**
     * Steven's library from GitHub (v1.10 — M27): the pack's manifest and zip from this repository's release
     * `library` (on the emulator in debug builds, a server on the Mac: [Emulator.librarySource]), its pieces through the
     * importer, the version loaded kept in the settings. The Library's buttons and the console's
     * `library.load` call [LibraryPack.load]; [LibraryService] holds the foreground while it runs.
     */
    val libraryPack: LibraryPack by lazy {
        val source = Emulator.librarySource() ?: UpdateSource.library
        val server = HttpUpdateServer(source, log = debugLog(LIBRARY_TAG), fileAccept = HttpUpdateServer.BINARY_ACCEPT)
        LibraryPack(
            source = source,
            server = server,
            downloader = VerifiedDownloader(server),
            offered = OfferedPacks(File(app.filesDir, LibraryPack.FILES_DIR)),
            cacheDir = File(app.cacheDir, LibraryPack.CACHE_DIR),
            loadedVersion = graph.settingsRepository.settings.map { it.libraryPackVersion }.distinctUntilChanged(),
            recordLoaded = graph.settingsRepository::setLibraryPackVersion,
            import = ::importLibraryPack,
            online = graph.network.online,
            scope = appScope,
            start = { everything -> LibraryService.start(app, everything) },
            log = { Log.i(LIBRARY_TAG, it) },
        )
    }

    /** The pack's pieces through the importer; then, as after the app's own imports, the built-in playlists and artwork. */
    private suspend fun importLibraryPack(source: ImportSource.LocalZip): ImportProgress {
        val result = importer.import(app, source)
        if (result.piecesChanged) {
            refreshBuiltIns()
            if (graph.settingsRepository.settings.first().fetchArtworkAutomatically) ArtworkService.start(app, force = false)
        }
        return result
    }

    /**
     * [count] files another app sent could not be read (no access was given with them): the
     * Library's import bar says so, unless an import is running, whose progress it keeps showing.
     */
    fun reportUnreadableShare(count: Int) {
        if (!importState.value.finished) {
            Log.w(TAG, "$count shared files couldn't be read (an import is running)")
            return
        }
        importState.value = ImportProgress(done = count, total = count, failed = count, unreadable = true)
    }

    /**
     * A file sent from the web panel could not even be opened (a zip that is not one, or holds past
     * its entry cap): the Library's import bar and the panel's tally count it as one failed file,
     * unless an import is running, whose progress they keep showing.
     */
    fun reportFailedImport() {
        if (!importState.value.finished) return
        importState.value = ImportProgress(done = 1, total = 1, failed = 1)
    }

    /**
     * The app came to the foreground, where a foreground service may start: if the person lets
     * artwork arrive by itself, the device is online, and some composer (or, with Album covers on,
     * some piece's cover; v1.15 — M40) was never looked up (a library from 1.1, an import made
     * offline) or failed a day ago or more, the fetch starts.
     */
    fun fetchArtworkIfDue() {
        appScope.launch {
            if (!graph.settingsRepository.settings.first().fetchArtworkAutomatically || !graph.network.isOnline()) return@launch
            if (artwork.due()) ArtworkService.start(app, force = false)
        }
    }

    private companion object {
        const val LIBRARY_TAG = "Library"
    }
}

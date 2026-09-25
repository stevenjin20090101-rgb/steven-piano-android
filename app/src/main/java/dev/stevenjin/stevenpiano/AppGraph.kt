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
import dev.stevenjin.stevenpiano.ble.BlePermissions
import dev.stevenjin.stevenpiano.ble.GattPianoLink
import dev.stevenjin.stevenpiano.ble.LoggingPianoLink
import dev.stevenjin.stevenpiano.ble.PianoLink
import dev.stevenjin.stevenpiano.data.LibraryRepository
import dev.stevenjin.stevenpiano.data.PieceFiles
import dev.stevenjin.stevenpiano.data.art.ArtFiles
import dev.stevenjin.stevenpiano.data.art.ArtworkRepository
import dev.stevenjin.stevenpiano.data.db.PianoDatabase
import dev.stevenjin.stevenpiano.data.db.TextRepair
import dev.stevenjin.stevenpiano.data.imports.ImportLimits
import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import dev.stevenjin.stevenpiano.data.imports.Importer
import dev.stevenjin.stevenpiano.net.NetworkMonitor
import dev.stevenjin.stevenpiano.net.WikipediaClient
import dev.stevenjin.stevenpiano.piano.PianoSettingsRepository
import dev.stevenjin.stevenpiano.player.Player
import dev.stevenjin.stevenpiano.service.ArtworkService
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.settings.SettingsRepository
import dev.stevenjin.stevenpiano.settings.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The app's objects, one per process, wired by hand. The [player] and the [pianoLink] are
 * process singletons: screens and services read them here rather than binding to a service.
 */
class AppGraph(private val app: Application) {
    /** Lives as long as the process, on the main thread: the player's state has one owner. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsRepository = SettingsRepository(app.settingsDataStore)

    /** The latest settings, for callers that cannot suspend. */
    val settings: StateFlow<PianoSettings> =
        settingsRepository.settings.stateIn(appScope, SharingStarted.Eagerly, PianoSettings())

    /** Opening it runs the one-off repair of text imported before 1.2 capped it ([TextRepair]), before any query. */
    private val database: PianoDatabase by lazy {
        PianoDatabase.open(app) { db ->
            TextRepair.runOnce(
                db,
                due = { runBlocking { !settingsRepository.textRepairDone() } },
                done = { runBlocking { settingsRepository.markTextRepairDone() } },
                log = { Log.w(TAG, it) },
            )
        }
    }
    private val pieceFiles = PieceFiles(app.filesDir)
    val library: LibraryRepository by lazy { LibraryRepository(database, pieceFiles) }

    private val importState = MutableStateFlow(ImportProgress.Idle)
    val importProgress: StateFlow<ImportProgress> = importState.asStateFlow()
    val importer: Importer by lazy { Importer(library, pieceFiles, importState) }

    /** Whether the device is online; artwork is only ever fetched when it is. */
    val network: NetworkMonitor by lazy { NetworkMonitor(app) }

    /** Portraits, notes, playlist photos and roll cards (the only network use: two Wikimedia hosts). */
    val artwork: ArtworkRepository by lazy {
        ArtworkRepository(app, database.artwork(), library, ArtFiles(app.filesDir), WikipediaClient(), network, appScope)
    }

    private val link = lazy {
        if (LoggingPianoLink.isWanted()) {
            LoggingPianoLink()
        } else {
            GattPianoLink.create(
                app,
                onConnected = { address, name -> appScope.launch { settingsRepository.rememberDevice(address, name) } },
                shouldReconnect = { settings.value.autoConnect },
            )
        }
    }

    /** The Bluetooth link; on an emulator in debug builds, a stand-in that logs what it would send. */
    val pianoLink: PianoLink by link

    /** The link if something has made it already, else null: the crash handler's view, which must never make one. */
    fun pianoLinkIfMade(): PianoLink? = if (link.isInitialized()) link.value else null

    val player: Player by lazy { Player(pianoLink, library, appScope) }

    /** The piano's own settings over its console, read on every connection. */
    val pianoSettings: PianoSettingsRepository by lazy { PianoSettingsRepository(pianoLink, appScope) }

    /**
     * From [App.onCreate]: settings flow into the player; the queue's shuffle and repeat start as
     * they were left and are remembered whenever they change; the piano is reached if the person
     * allows it; files a crashed import left behind are swept away.
     */
    fun start() {
        val startedAt = System.currentTimeMillis()
        appScope.launch(Dispatchers.IO) {
            runCatching { ImportLimits.sweepStale(app.cacheDir, app.filesDir, before = startedAt) }
        }
        pianoSettings.start()
        appScope.launch {
            val saved = settingsRepository.settings.first()
            player.setShuffle(saved.shuffle)
            player.setRepeat(saved.repeat)
            player.state.map { it.queue.shuffle to it.queue.repeat }.distinctUntilChanged().collect { (shuffle, repeat) ->
                val stored = settingsRepository.settings.first()
                if (stored.shuffle != shuffle) settingsRepository.setShuffle(shuffle)
                if (stored.repeat != repeat) settingsRepository.setRepeat(repeat)
            }
        }
        appScope.launch {
            settingsRepository.settings.collect { s ->
                player.setDefaultTempo(s.defaultTempoPct)
                player.setTranspose(s.transpose)
                player.setVelocity(s.velocityPct)
                player.setFold(s.foldOutOfRange)
                player.setSkipDrums(s.skipDrumChannel)
            }
        }
        appScope.launch {
            val s = settingsRepository.settings.first()
            // Permission is only ever asked for on the Piano tab; without it, launch stays quiet.
            if (s.autoConnect && BlePermissions.missing(app).isEmpty()) pianoLink.connect(s.lastDeviceAddress)
        }
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
     * The app came to the foreground, where a foreground service may start: if the person lets
     * artwork arrive by itself, the device is online, and some composer was never looked up (a
     * library from 1.1, an import made offline) or failed a day ago or more, the fetch starts.
     */
    fun fetchArtworkIfDue() {
        appScope.launch {
            if (!settingsRepository.settings.first().fetchArtworkAutomatically || !network.isOnline()) return@launch
            if (artwork.composersDue()) ArtworkService.start(app, force = false)
        }
    }

    /** Disconnect from the Piano tab: the player pauses first, so the piano is silenced, then the link drops. */
    fun disconnectPiano() {
        appScope.launch {
            player.pauseAndFlush(DISCONNECT_FLUSH_MS)
            pianoLink.disconnect()
        }
    }

    private companion object {
        const val TAG = "AppGraph"
        const val DISCONNECT_FLUSH_MS = 300L
    }
}

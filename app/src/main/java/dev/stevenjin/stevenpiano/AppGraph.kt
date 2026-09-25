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
import dev.stevenjin.stevenpiano.ble.BlePermissions
import dev.stevenjin.stevenpiano.ble.GattPianoLink
import dev.stevenjin.stevenpiano.ble.LoggingPianoLink
import dev.stevenjin.stevenpiano.ble.PianoLink
import dev.stevenjin.stevenpiano.data.LibraryRepository
import dev.stevenjin.stevenpiano.data.PieceFiles
import dev.stevenjin.stevenpiano.data.db.PianoDatabase
import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import dev.stevenjin.stevenpiano.data.imports.Importer
import dev.stevenjin.stevenpiano.piano.PianoSettingsRepository
import dev.stevenjin.stevenpiano.player.Player
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    private val database: PianoDatabase by lazy { PianoDatabase.open(app) }
    private val pieceFiles = PieceFiles(app.filesDir)
    val library: LibraryRepository by lazy { LibraryRepository(database, pieceFiles) }

    private val importState = MutableStateFlow(ImportProgress.Idle)
    val importProgress: StateFlow<ImportProgress> = importState.asStateFlow()
    val importer: Importer by lazy { Importer(library, pieceFiles, importState) }

    /** The Bluetooth link; on an emulator in debug builds, a stand-in that logs what it would send. */
    val pianoLink: PianoLink by lazy {
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

    val player: Player by lazy { Player(pianoLink, library, appScope) }

    /** The piano's own settings over its console, read on every connection. */
    val pianoSettings: PianoSettingsRepository by lazy { PianoSettingsRepository(pianoLink, appScope) }

    /** From [App.onCreate]: settings flow into the player; the piano is reached if the person allows it. */
    fun start() {
        pianoSettings.start()
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

    /** Disconnect from the Piano tab: the player pauses first, so the piano is silenced, then the link drops. */
    fun disconnectPiano() {
        appScope.launch {
            player.pauseAndFlush(DISCONNECT_FLUSH_MS)
            pianoLink.disconnect()
        }
    }

    private companion object {
        const val DISCONNECT_FLUSH_MS = 300L
    }
}

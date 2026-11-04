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
import android.content.Context
import android.os.Build
import android.os.SystemClock
import dev.stevenjin.stevenpiano.AppGraph.Companion.debugLog
import dev.stevenjin.stevenpiano.diag.LinkLog
import dev.stevenjin.stevenpiano.firmware.FirmwareKeys
import dev.stevenjin.stevenpiano.firmware.FirmwarePlayer
import dev.stevenjin.stevenpiano.firmware.FirmwareUpdater
import dev.stevenjin.stevenpiano.firmware.batteryState
import dev.stevenjin.stevenpiano.update.HttpUpdateServer
import dev.stevenjin.stevenpiano.update.UpdateChecker
import dev.stevenjin.stevenpiano.update.UpdateDownloader
import dev.stevenjin.stevenpiano.update.UpdateInstaller
import dev.stevenjin.stevenpiano.update.UpdateSource
import dev.stevenjin.stevenpiano.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The updates' part of [AppGraph]: the app's own, and the piano's firmware over its link. The graph makes it once and
 * everything else reaches it through the graph's members; what it holds is lazy or asked for.
 */
internal class UpdateWiring(private val graph: AppGraph, private val app: Application) {
    private val appScope = graph.appScope

    /** Where updates come from: the app's GitHub repository; on the emulator in debug builds, a test server ([Emulator.updateSource]). */
    private val updateSource: UpdateSource by lazy { Emulator.updateSource() ?: UpdateSource.production }
    private val updateServer by lazy { HttpUpdateServer(updateSource, log = debugLog(UPDATES_TAG)) }

    /** Whether a newer release exists, and the updater's state, which the Piano tab shows. */
    val updateChecker: UpdateChecker by lazy {
        UpdateChecker(BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT, updateSource, updateServer, graph.network.online, log = debugLog(UPDATES_TAG) ?: {})
    }

    /** Downloads land in `cacheDir/updates`, the one folder the installer is shown. */
    val updateDownloader: UpdateDownloader by lazy { UpdateDownloader(File(app.cacheDir, UPDATES_DIR), updateServer) }

    /** Before a silent install replaces the running app, the piano is silenced: live keys let go, playback pauses. */
    val updateInstaller: UpdateInstaller by lazy {
        UpdateInstaller(app) {
            withContext(Dispatchers.Main) {
                graph.player.silenceLive()
                graph.player.pauseAndFlush(INSTALL_FLUSH_MS)
            }
        }
    }

    /** The Update button's work: download, check, hand to Android. */
    val updater: Updater by lazy { Updater(updateChecker, updateDownloader, updateInstaller) }

    /**
     * Automatic update checks, run by the activity while it is started (after its first frame): at
     * once, then daily, while the switch is on and the device online. The switch is read from
     * DataStore itself, so a check never runs on the default before the saved value is known. The
     * piano's firmware is looked for on the same switch (v1.6 — M21), while a piano that can be
     * updated is connected, and a newer library pack (v1.10 — M27), once a day.
     */
    suspend fun runUpdateSchedule() = coroutineScope {
        val switch = graph.settingsRepository.settings.map { it.checkForUpdates }.distinctUntilChanged()
        launch { firmwareUpdater.runSchedule(switch) }
        launch { graph.libraryPack.runSchedule(switch) }   // v1.10 — M27: a newer library pack, on the same switch
        updateChecker.runSchedule(switch)
    }

    /**
     * The piano's firmware updates over the Bluetooth link (v1.6 — M21): its signed releases from
     * the firmware repository, checked against the author's key; on the emulator with a fake
     * scenario, the scenario's release and the test key ([Emulator.firmware]).
     */
    val firmwareUpdater: FirmwareUpdater by lazy {
        val fake = Emulator.firmware()
        FirmwareUpdater(
            link = graph.stevenLink,
            pianoState = graph.pianoSettings.state,
            readFact = graph.pianoSettings::readFact,
            player = object : FirmwarePlayer {
                override fun lock(reason: String) = graph.player.lock(reason)

                override fun unlock() = graph.player.unlock()

                override suspend fun stopForUpdate(timeoutMs: Long): Boolean = graph.player.stopQuietly(timeoutMs)
            },
            server = fake?.server ?: HttpUpdateServer(UpdateSource.firmware, log = debugLog(FIRMWARE_TAG)),
            publicKey = fake?.publicKey ?: FirmwareKeys.author,
            appVersionCode = BuildConfig.VERSION_CODE,
            platformEd25519 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,   // asked first where it has Ed25519; Android 14 has none
            online = graph.network.online,
            power = { batteryState(app) },
            scope = appScope,
            now = SystemClock::elapsedRealtime,
            log = LinkLog::warn,
            wake = graph.powerWiring::wakeForUpdate,   // v1.39 — M76
        )
    }

    /** Restart after a silent update: the piano is silenced first, then the new code starts. */
    fun restartForUpdate(from: Context) = updateInstaller.restart(from) {
        graph.player.silenceLive()
        graph.player.stopAndFlush(INSTALL_FLUSH_MS)
    }

    private companion object {
        const val UPDATES_TAG = "Updates"
        const val FIRMWARE_TAG = "Firmware"
        const val UPDATES_DIR = "updates"
        const val INSTALL_FLUSH_MS = 300L
    }
}

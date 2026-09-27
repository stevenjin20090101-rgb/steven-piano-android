// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.stevenjin.stevenpiano.AppGraph
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.piano.PianoAction
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.settings.NoteDisplay
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.settings.SettingsRepository
import dev.stevenjin.stevenpiano.settings.WideLayout
import dev.stevenjin.stevenpiano.service.UpdateService
import dev.stevenjin.stevenpiano.update.UpdateState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Piano tab: the connection, the piano's own settings, the app's updates and preferences. The
 * piano's settings go through the app-wide [AppGraph.pianoSettings], which reads them on every
 * connection; [leave] saves them on the piano when the tab goes. Preferences are written in the
 * app's scope so leaving the tab never drops one; the player picks them up from the settings
 * flow. Update, Check now and Restart act through [AppGraph.updater].
 */
class PianoViewModel(private val graph: AppGraph) : ViewModel(), PianoSettingsActions {
    val link: StateFlow<LinkState> = graph.pianoLink.state
    val settings: StateFlow<PianoSettings> = graph.settings
    val playing: StateFlow<Boolean> = graph.player.state
        .map { it.status == PlaybackStatus.Playing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** What the piano reports of its own settings. */
    val piano: StateFlow<PianoState> = graph.pianoSettings.state
    val statusText: StateFlow<String?> = graph.pianoSettings.statusText
    val statusReading: StateFlow<Boolean> = graph.pianoSettings.statusReading

    /** The updater: a release on offer, its download, its install, or what the last check found. */
    val update: StateFlow<UpdateState> = graph.updater.state

    fun connect() = graph.pianoLink.connect(graph.settings.value.lastDeviceAddress)

    /** The person chose the other "Steven Piano" a scan found: it becomes the piano this phone connects to. */
    fun connectTo(address: String) = graph.pianoLink.connect(address)

    /** Stops looking for the piano. Nothing is sounding yet, so there is nothing to silence. */
    fun cancel() = graph.pianoLink.disconnect()

    /** Pauses first, so the piano is silenced, then drops the link. */
    fun disconnect() = graph.disconnectPiano()

    override fun setPianoValue(name: String, value: String) = graph.pianoSettings.set(name, value)

    override fun applyPreset(command: String) = graph.pianoSettings.preset(command)

    override fun runPianoAction(action: PianoAction, key: Int) = graph.pianoSettings.action(action, key)

    override fun dismissPianoError() = graph.pianoSettings.dismissError()

    /** The tab left the foreground: changes still waiting go now, and the piano saves them. */
    fun leave() = graph.pianoSettings.leave()

    fun setAutoConnect(on: Boolean) = edit { setAutoConnect(on) }

    fun setNoteDisplay(display: NoteDisplay) = edit { setNoteDisplay(display) }

    fun setWideLayout(layout: WideLayout) = edit { setWideLayout(layout) }

    fun setDefaultTempo(pct: Int) = edit { setDefaultTempo(pct) }

    fun setTranspose(semitones: Int) = edit { setTranspose(semitones) }

    fun setVelocity(pct: Int) = edit { setVelocity(pct) }

    fun setFold(on: Boolean) = edit { setFoldOutOfRange(on) }

    fun setSkipDrums(on: Boolean) = edit { setSkipDrumChannel(on) }

    fun setArtworkMonochrome(on: Boolean) = edit { setArtworkMonochrome(on) }

    fun setFetchArtworkAutomatically(on: Boolean) = edit { setFetchArtworkAutomatically(on) }

    fun setFingering(on: Boolean) = edit { setFingering(on) }

    fun setChordNames(on: Boolean) = edit { setChordNames(on) }

    fun setHandColours(on: Boolean) = edit { setHandColours(on) }

    fun setCheckForUpdates(on: Boolean) = edit { setCheckForUpdates(on) }

    /** Check now: asks the server whatever the switch says, in the app's scope so leaving the tab does not stop it. */
    fun checkNow() {
        graph.appScope.launch { graph.updateChecker.checkNow() }
    }

    /**
     * Update: a release on offer is downloaded (the service shows the progress, then Android's
     * installer opens, or the device owner installs it); a download already verified goes straight
     * to the installer.
     */
    fun update(context: Context) {
        val app = context.applicationContext
        when (val state = graph.updater.state.value) {
            is UpdateState.ReadyToInstall -> graph.appScope.launch { graph.updater.install(state.manifest, state.file, app) }
            is UpdateState.Available, is UpdateState.Failed -> if (state.manifest != null) UpdateService.start(app)
            else -> Unit
        }
    }

    /** After a silent update: the piano silenced, the app starts again on the new version. */
    fun restart(context: Context) = graph.restartForUpdate(context)

    /** Whether Android lets this app open its installer; always so for the device owner. */
    fun canInstall(): Boolean = graph.updateInstaller.canRequestInstalls()

    fun installPermissionSettings(): Intent = graph.updateInstaller.installPermissionSettings()

    private fun edit(change: suspend SettingsRepository.() -> Unit) {
        graph.appScope.launch { graph.settingsRepository.change() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

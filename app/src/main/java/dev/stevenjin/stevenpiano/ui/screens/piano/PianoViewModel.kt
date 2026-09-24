// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.stevenjin.stevenpiano.AppGraph
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.settings.NoteDisplay
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.settings.SettingsRepository
import dev.stevenjin.stevenpiano.settings.WideLayout
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Piano tab: the connection and the preferences. Preferences are written in the app's
 * scope so leaving the tab never drops one; the player picks them up from the settings flow.
 */
class PianoViewModel(private val graph: AppGraph) : ViewModel() {
    val link: StateFlow<LinkState> = graph.pianoLink.state
    val settings: StateFlow<PianoSettings> = graph.settings
    val playing: StateFlow<Boolean> = graph.player.state
        .map { it.status == PlaybackStatus.Playing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    fun connect() = graph.pianoLink.connect(graph.settings.value.lastDeviceAddress)

    /** Stops looking for the piano. Nothing is sounding yet, so there is nothing to silence. */
    fun cancel() = graph.pianoLink.disconnect()

    /** Pauses first, so the piano is silenced, then drops the link. */
    fun disconnect() = graph.disconnectPiano()

    fun setAutoConnect(on: Boolean) = edit { setAutoConnect(on) }

    fun setNoteDisplay(display: NoteDisplay) = edit { setNoteDisplay(display) }

    fun setWideLayout(layout: WideLayout) = edit { setWideLayout(layout) }

    fun setDefaultTempo(pct: Int) = edit { setDefaultTempo(pct) }

    fun setTranspose(semitones: Int) = edit { setTranspose(semitones) }

    fun setVelocity(pct: Int) = edit { setVelocity(pct) }

    fun setFold(on: Boolean) = edit { setFoldOutOfRange(on) }

    fun setSkipDrums(on: Boolean) = edit { setSkipDrumChannel(on) }

    private fun edit(change: suspend SettingsRepository.() -> Unit) {
        graph.appScope.launch { graph.settingsRepository.change() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

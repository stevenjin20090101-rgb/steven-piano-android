// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.keys

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.stevenjin.stevenpiano.AppGraph
import dev.stevenjin.stevenpiano.audio.TabletSoundState
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.instruments.KeyboardState
import dev.stevenjin.stevenpiano.instruments.LiveState
import dev.stevenjin.stevenpiano.instruments.MidiKeyboard
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The Keys screen: keys pressed on the playable keyboard go to the piano through the player's
 * live input (the same router as a piece, so they share its safety), the latching sustain, the
 * VELOCITY readout, and which part of the keyboard is shown. While the piano isn't connected the
 * keys still invert on screen but nothing is sent. [letGo] releases every key and the pedal: on
 * leaving the screen, on the app going to the background, and when the view model goes.
 */
class KeysViewModel(private val graph: AppGraph) : ViewModel(), KeyTouches.Sink {
    private val player = graph.player
    val link: StateFlow<LinkState> = graph.pianoLink.state

    /** The tablet's piano sound (v1.8 — M25): whether it plays these keys while the piano isn't connected. */
    val tabletSound: StateFlow<TabletSoundState> = graph.tabletSound.state

    /** The MIDI keyboard (v1.11 — M29): its state for the eyebrow, and what it holds for the keys to show. */
    val keyboard: StateFlow<KeyboardState> = graph.keyboard.state
    val external: MidiKeyboard = graph.keyboard

    /** Live (v1.11 — M29): whether the keyboard plays the instrument now, the switch, and why it is off. */
    val live: StateFlow<LiveState> = graph.liveThru.state

    /** The Live pill: the gate follows at once, and the switch is remembered. Free in kiosk mode, as the Keys tab is. */
    fun setLive(on: Boolean) {
        graph.liveThru.setWanted(on)
        graph.appScope.launch { graph.settingsRepository.setLiveToPiano(on) }
    }

    /** The Keys tab is on screen with the app in the foreground: Live may play. */
    fun onScreen(on: Boolean) = graph.liveThru.setOnScreen(on)

    /** The sustain as the piano was last told; a stop elsewhere lifts it. */
    val sustain: StateFlow<Boolean> = player.liveSustain

    /** A piece is playing (the live dot breathes). */
    val playing: StateFlow<Boolean> = player.state
        .map { it.status == PlaybackStatus.Playing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** The velocity of the last key struck, for a second after it; null otherwise. */
    var velocity by mutableStateOf<Int?>(null)
        private set
    private var velocityJob: Job? = null

    /**
     * The leftmost white key shown (0 = C1), fractional while the mini-map is dragged. It is kept
     * as chosen and clamped only when read, so a rotation to a wider keyboard shows as much as
     * fits from the same key, and rotating back returns to exactly where the keyboard was.
     */
    private var first by mutableFloatStateOf(KeyboardGeometry.whiteIndexOf(graph.settings.value.keysViewportStart).toFloat())

    /** The leftmost white key shown when [visibleWhites] fit across the screen: [first], clamped to that width. */
    fun firstWhite(visibleWhites: Int): Float = KeyboardGeometry.clampFirst(first, visibleWhites)

    /** Sent while the piano is connected, or while the tablet plays the piano sound itself (v1.8 — M25). */
    override fun noteOn(key: Int, velocity: Int) {
        if (link.value is LinkState.Connected || graph.tabletSound.active) player.liveNoteOn(key, velocity)
        showVelocity(velocity)
    }

    /** Always sent: letting go of a key the piano never got is harmless. */
    override fun noteOff(key: Int) = player.liveNoteOff(key)

    fun setSustain(down: Boolean) = player.liveSustain(down)

    /** Every key the screen holds lets go, and the pedal comes up. */
    fun letGo() = player.silenceLive()

    /** The mini-map is being dragged: [firstWhite] follows the finger. */
    fun moveTo(firstWhite: Float, visibleWhites: Int) {
        first = KeyboardGeometry.clampFirst(firstWhite, visibleWhites)
    }

    /** The drag ended: settle on a whole white key and remember it. */
    fun settle(visibleWhites: Int) {
        first = KeyboardGeometry.clampFirst(first.roundToInt().toFloat(), visibleWhites)
        persist()
    }

    /** ‹ or ›: an octave down ([octaves] -1) or up (+1). */
    fun shiftOctave(octaves: Int, visibleWhites: Int) {
        val from = KeyboardGeometry.clampFirst(first, visibleWhites).roundToInt()
        first = KeyboardGeometry.clampFirst((from + octaves * WHITES_PER_OCTAVE).toFloat(), visibleWhites)
        persist()
    }

    private fun persist() {
        val key = KeyboardGeometry.whiteKey(first.roundToInt())
        graph.appScope.launch { graph.settingsRepository.setKeysViewportStart(key) }
    }

    private fun showVelocity(value: Int) {
        velocity = value
        velocityJob?.cancel()
        velocityJob = viewModelScope.launch {
            delay(VELOCITY_SHOWN_MS)
            velocity = null
        }
    }

    override fun onCleared() {
        player.silenceLive()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val VELOCITY_SHOWN_MS = 1_000L
        const val WHITES_PER_OCTAVE = 7
    }
}

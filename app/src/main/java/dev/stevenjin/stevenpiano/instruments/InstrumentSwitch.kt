// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.instruments

import dev.stevenjin.stevenpiano.ble.ConsoleChannel
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.ble.OtaChannel
import dev.stevenjin.stevenpiano.ble.PianoLink
import dev.stevenjin.stevenpiano.midi.InstrumentProfile
import dev.stevenjin.stevenpiano.midi.MidiBatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Which instrument plays (v1.11 — M29). */
enum class InstrumentKind {
    /** The school piano, over its own Bluetooth link. */
    StevenPiano,

    /** Any other MIDI piano, through Android's MIDI service. */
    MidiPiano,
    ;

    val profile: InstrumentProfile get() = if (this == StevenPiano) InstrumentProfile.StevenPiano else InstrumentProfile.StandardPiano
}

/**
 * The instrument the app plays (v1.11 — M29): a [PianoLink] that hands everything to the link of the instrument
 * chosen ([select]): Steven Piano's own Bluetooth link ([steven]), or a MIDI piano's ([midi]). So the player,
 * the Keys screen, the tablet's sound, the web panel and the crash handler, which all read the app's one link,
 * follow the instrument without knowing which it is. Its [state] is the chosen link's; the console, the firmware
 * version and the update service are Steven Piano's alone (none while a MIDI piano plays; the piano's settings
 * and its firmware updater read [steven] itself). The instrument is changed by the app (AppGraph's
 * `chooseInstrument`: the old one silenced and let go first), never while the player is locked for a firmware
 * update.
 */
class InstrumentSwitch(
    val steven: PianoLink,
    val midi: MidiPortLink,
    scope: CoroutineScope,
    initial: InstrumentKind = InstrumentKind.StevenPiano,
) : PianoLink {
    private val _kind = MutableStateFlow(initial)

    /** The instrument chosen. */
    val kind: StateFlow<InstrumentKind> = _kind.asStateFlow()

    /** What it can take: Steven Piano's rules, or any MIDI piano's. */
    val profile: StateFlow<InstrumentProfile> = _kind.map { it.profile }.stateIn(scope, SharingStarted.Eagerly, initial.profile)

    override val state: StateFlow<LinkState> = combine(_kind, steven.state, midi.state) { kind, s, m -> if (kind == InstrumentKind.StevenPiano) s else m }
        .stateIn(scope, SharingStarted.Eagerly, if (initial == InstrumentKind.StevenPiano) steven.state.value else midi.state.value)

    /** The link of the instrument chosen; read on whatever thread sends. */
    private val active: PianoLink get() = if (_kind.value == InstrumentKind.StevenPiano) steven else midi

    /** From now on [kind] plays (the caller has silenced and let go of the other first). */
    fun select(kind: InstrumentKind) {
        _kind.value = kind
    }

    override val console: ConsoleChannel? get() = if (_kind.value == InstrumentKind.StevenPiano) steven.console else null

    override val firmwareVersion: StateFlow<String?> get() = steven.firmwareVersion

    override val ota: OtaChannel? get() = if (_kind.value == InstrumentKind.StevenPiano) steven.ota else null

    override fun connect(address: String?) = active.connect(address)

    override fun disconnect() = active.disconnect()

    override fun send(batch: MidiBatch, dropPending: Boolean) = active.send(batch, dropPending)

    override fun sendLive(batch: MidiBatch) = active.sendLive(batch)

    override fun flush(timeoutMs: Long): Boolean = active.flush(timeoutMs)

    override fun emergencySilence(timeoutMs: Long): Boolean = active.emergencySilence(timeoutMs)

    override fun expectRestart(withinMs: Long) = steven.expectRestart(withinMs)
}

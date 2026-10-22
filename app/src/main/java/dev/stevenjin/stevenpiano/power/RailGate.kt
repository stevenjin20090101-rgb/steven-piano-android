// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import dev.stevenjin.stevenpiano.ble.ConsoleChannel
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.ble.LockGate
import dev.stevenjin.stevenpiano.ble.OtaChannel
import dev.stevenjin.stevenpiano.ble.PianoLink
import dev.stevenjin.stevenpiano.midi.MidiBatch
import kotlinx.coroutines.flow.StateFlow

/**
 * Steven Piano's link with the 24 V rail's hold on top (v1.26 — M60), as [LockGate] holds notes for the piano's Bluetooth
 * lock: while [held] (the plug switches the rail and it isn't up with its baseline, [RailKeeper.notesHeld]) only what
 * releases goes ([LockGate.releasing]: Note Off, CC64 below 64, CC120, CC121, CC123), so no note goes to boards that are
 * unpowered or still waking. The stop sequence and the crash handler's [emergencySilence] always go. Everything else is
 * [link]'s own, its [state] included: a piece that starts while the rail comes up is held by pausing it ([RailHold]), not by
 * the link looking dropped (which would end Test every key and close Live). Not held, it is [link] itself. Thread-safe.
 */
class RailGate(private val link: PianoLink, private val held: StateFlow<Boolean>) : PianoLink {
    override val state: StateFlow<LinkState> get() = link.state

    override val console: ConsoleChannel? get() = link.console

    override val firmwareVersion: StateFlow<String?> get() = link.firmwareVersion

    override val ota: OtaChannel? get() = link.ota

    override val address: String? get() = link.address

    override fun connect(address: String?) = link.connect(address)

    override fun disconnect() = link.disconnect()

    override fun send(batch: MidiBatch, dropPending: Boolean) {
        if (!held.value) return link.send(batch, dropPending)
        LockGate.releasing(batch)?.let { link.send(it, dropPending) }
    }

    override fun sendLive(batch: MidiBatch) {
        if (!held.value) return link.sendLive(batch)
        LockGate.releasing(batch)?.let(link::sendLive)
    }

    override fun flush(timeoutMs: Long): Boolean = link.flush(timeoutMs)

    override fun emergencySilence(timeoutMs: Long): Boolean = link.emergencySilence(timeoutMs)

    override fun expectRestart(withinMs: Long) = link.expectRestart(withinMs)
}

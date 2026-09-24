// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

import dev.stevenjin.stevenpiano.midi.MidiBatch
import dev.stevenjin.stevenpiano.midi.hex
import dev.stevenjin.stevenpiano.player.NanoClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A piano link that records every message with the time it was sent. Thread-safe. */
class FakePianoLink(private val clock: NanoClock = NanoClock.System) : PianoLink {
    data class Sent(val atNanos: Long, val message: String, val dropPending: Boolean)

    private val _state = MutableStateFlow<LinkState>(LinkState.Connected("Steven Piano", 255))
    override val state: StateFlow<LinkState> = _state
    private val log = mutableListOf<Sent>()

    val sent: List<Sent> get() = synchronized(log) { log.toList() }
    val messages: List<String> get() = sent.map { it.message }

    override fun connect(address: String?) {
        _state.value = LinkState.Connected("Steven Piano", 255)
    }

    override fun disconnect() {
        _state.value = LinkState.Disconnected
    }

    /** The piano went away on its own. */
    fun drop() {
        _state.value = LinkState.Reconnecting(1)
    }

    override fun send(batch: MidiBatch, dropPending: Boolean) {
        val at = clock.nanoTime()
        synchronized(log) { batch.hex().forEach { log += Sent(at, it, dropPending) } }
    }

    override fun flush(timeoutMs: Long): Boolean = true

    fun clear() = synchronized(log) { log.clear() }
}

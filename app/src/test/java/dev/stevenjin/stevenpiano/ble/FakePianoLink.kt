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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A piano link that records every message with the time it was sent. Thread-safe. [console] is
 * null (a piano without one) until a test gives it a [FakeConsole].
 */
class FakePianoLink(private val clock: NanoClock = NanoClock.System) : PianoLink {
    data class Sent(val atNanos: Long, val message: String, val dropPending: Boolean)

    private val _state = MutableStateFlow<LinkState>(LinkState.Connected("Steven Piano", 255))
    override val state: StateFlow<LinkState> = _state
    private val log = mutableListOf<Sent>()

    /** The console the piano offers on its next connection; null for older firmware. */
    var consoleOnConnect: FakeConsole? = null

    @Volatile
    override var console: ConsoleChannel? = null
        private set

    val sent: List<Sent> get() = synchronized(log) { log.toList() }
    val messages: List<String> get() = sent.map { it.message }

    override fun connect(address: String?) {
        console = consoleOnConnect
        _state.value = LinkState.Connected("Steven Piano", 255)
    }

    override fun disconnect() {
        console = null
        _state.value = LinkState.Disconnected
    }

    /** The piano went away on its own. */
    fun drop() {
        console = null
        _state.value = LinkState.Reconnecting(1)
    }

    /** Connected from the start, with [with] as its console: a link already up when a test begins. */
    fun connectedWith(with: FakeConsole?) {
        consoleOnConnect = with
        console = with
        _state.value = LinkState.Connected("Steven Piano", 255)
    }

    override fun send(batch: MidiBatch, dropPending: Boolean) {
        val at = clock.nanoTime()
        synchronized(log) { batch.hex().forEach { log += Sent(at, it, dropPending) } }
    }

    override fun flush(timeoutMs: Long): Boolean = true

    fun clear() = synchronized(log) { log.clear() }
}

/**
 * A console the test scripts: every line sent is recorded in [sent], and [reply] answers it at
 * once on [lines]. [emit] pushes a line the piano sends by itself. By default it answers like the
 * firmware ([EmulatedConsole]).
 */
class FakeConsole(var reply: (String) -> List<String> = EmulatedConsole()::handle) : ConsoleChannel {
    private val out = MutableSharedFlow<String>(extraBufferCapacity = 4096)
    override val lines: SharedFlow<String> = out
    private val record = mutableListOf<String>()

    val sent: List<String> get() = synchronized(record) { record.toList() }

    override fun sendLine(text: String) {
        synchronized(record) { record += text }
        reply(text).forEach { out.tryEmit(it) }
    }

    fun emit(line: String) {
        out.tryEmit(line)
    }

    fun clearSent() = synchronized(record) { record.clear() }
}

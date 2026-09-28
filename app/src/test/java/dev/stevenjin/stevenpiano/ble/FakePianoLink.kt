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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow

/**
 * A piano link that records every message with the time it was sent. Thread-safe. [console] is
 * null (a piano without one) until a test gives it a [FakeConsole]; [firmwareVersion] and [ota]
 * likewise (firmware older than 2.0.0) until a test sets [firmwareVersionOnConnect] and
 * [otaOnConnect] (a [FakeOtaChannel]).
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

    /** The version the piano reports on its next connection (0x2A26); null for firmware older than 2.0.0. */
    var firmwareVersionOnConnect: String? = null

    /** The update service the piano offers on its next connection; null for firmware older than 2.0.0. */
    var otaOnConnect: FakeOtaChannel? = null

    private val version = MutableStateFlow<String?>(null)
    override val firmwareVersion: StateFlow<String?> = version

    @Volatile
    override var ota: OtaChannel? = null
        private set

    /** Every [expectRestart] window asked for, in order. */
    val restartsExpected = mutableListOf<Long>()

    override fun expectRestart(withinMs: Long) {
        synchronized(restartsExpected) { restartsExpected += withinMs }
    }

    val sent: List<Sent> get() = synchronized(log) { log.toList() }
    val messages: List<String> get() = sent.map { it.message }

    override fun connect(address: String?) {
        console = consoleOnConnect
        ota = otaOnConnect
        version.value = firmwareVersionOnConnect
        _state.value = LinkState.Connected("Steven Piano", 255)
    }

    override fun disconnect() {
        console = null
        ota = null
        version.value = null
        _state.value = LinkState.Disconnected
    }

    /** The piano went away on its own. */
    fun drop() {
        console = null
        ota = null
        version.value = null
        _state.value = LinkState.Reconnecting(1)
    }

    /**
     * A drop and a reconnection too quick for anyone watching the state to see: only the epoch of
     * the connection tells (as the real link's does, also after it lost a packet).
     */
    fun reconnectQuietly() {
        val connected = _state.value as LinkState.Connected
        _state.value = connected.copy(epoch = connected.epoch + 1)
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

    private val emergencies = mutableListOf<Long>()

    /** The timeouts [emergencySilence] was called with, in order. */
    val emergencySilences: List<Long> get() = synchronized(emergencies) { emergencies.toList() }

    /** Records the call and does nothing else. */
    override fun emergencySilence(timeoutMs: Long): Boolean {
        synchronized(emergencies) { emergencies += timeoutMs }
        return true
    }

    fun clear() = synchronized(log) { log.clear() }
}

/**
 * The piano's update service for tests: every frame the app writes is recorded ([control], [data],
 * with [beginAt] from [now]), and an [OtaPiano] with [script] answers as BLE_OTA.md says the
 * firmware does: at once, or with [answerScope], each answer [answerDelayMs] later (so a test can
 * step in between windows). Its [OtaPiano.Out.Drop] drops [link]; after OK, [onRestart] runs (the
 * test decides how the piano comes back). Not thread-safe: tests call it from one thread. Reads a
 * session's `isClosedForSend` (a delicate API, hence the opt-in) as [EmulatedOta] does.
 */
@OptIn(DelicateCoroutinesApi::class)
class FakeOtaChannel(
    private val link: FakePianoLink,
    var script: OtaPiano.Script = OtaPiano.Script(),
    override val maxChunk: Int = OtaFrames.MAX_CHUNK,
    private val now: () -> Long = { 0L },
    private val answerScope: CoroutineScope? = null,
    private val answerDelayMs: Long = 0L,
) : OtaChannel {
    override val window: Int = OtaFrames.WINDOW

    /** Every Control frame written, in order. */
    val control = mutableListOf<ByteArray>()

    /** Every Data frame written, in order. */
    val data = mutableListOf<ByteArray>()

    /** When the last BEGIN was written, by [now]; null before any. */
    var beginAt: Long? = null
        private set

    /** Sessions begun. */
    var sessions = 0
        private set

    /** After OK: how the piano restarts. Nothing by default. */
    var onRestart: (inMs: Int) -> Unit = {}

    /** As BEGIN is written (a test looks at the player then). */
    var onBegin: () -> Unit = {}

    private var piano = OtaPiano(maxChunk + OtaFrames.ATT_HEADER + OtaFrames.DATA_HEADER, script)
    private var out: SendChannel<OtaEvent>? = null
    private var ended = false

    /** The last Control frame's opcode names, for assertions: "BEGIN", "END", "ABORT". */
    val controlNames: List<String>
        get() = control.map {
            when (it[0].toInt() and 0xFF) {
                OtaFrames.BEGIN -> "BEGIN"
                OtaFrames.END -> "END"
                OtaFrames.ABORT -> "ABORT"
                else -> "?"
            }
        }

    override fun begin(header: OtaBegin): Flow<OtaEvent> = callbackFlow {
        val session = channel
        piano = OtaPiano(maxChunk + OtaFrames.ATT_HEADER + OtaFrames.DATA_HEADER, script)
        out = session
        ended = false
        sessions++
        beginAt = now()
        onBegin()
        val frame = OtaFrames.begin(header)
        control += frame
        deliver(piano.control(frame))
        awaitClose {
            if (out === session) {
                if (!ended && !session.isClosedForSend) {
                    control += OtaFrames.abort()
                    piano.control(OtaFrames.abort())
                }
                out = null
            }
        }
    }

    override fun write(seq: Int, payload: ByteArray): Boolean {
        if (out?.isClosedForSend != false || ended || payload.isEmpty() || payload.size > maxChunk) return false
        val frame = OtaFrames.data(seq, payload)
        data += frame
        deliver(piano.data(frame))
        return true
    }

    override fun end() {
        if (out?.isClosedForSend != false || ended) return
        ended = true
        control += OtaFrames.end()
        deliver(piano.control(OtaFrames.end()))
    }

    override fun abort() {
        if (out?.isClosedForSend != false || ended) return
        control += OtaFrames.abort()
        deliver(piano.control(OtaFrames.abort()))
    }

    /** The bytes of the image the Data frames carried, in order. */
    fun image(): ByteArray = data.fold(ByteArray(0)) { acc, frame -> acc + frame.copyOfRange(OtaFrames.DATA_HEADER, frame.size) }

    private fun deliver(outs: List<OtaPiano.Out>) {
        val session = out ?: return
        val scope = answerScope
        if (scope == null) {
            deliverNow(session, outs)
        } else {
            scope.launch {
                delay(answerDelayMs)
                deliverNow(session, outs)
            }
        }
    }

    private fun deliverNow(session: SendChannel<OtaEvent>, outs: List<OtaPiano.Out>) {
        for (o in outs) {
            when (o) {
                is OtaPiano.Out.Notify -> if (!session.isClosedForSend) {
                    OtaFrames.parse(o.bytes)?.let { event ->
                        session.trySend(event)
                        if (event.ends) session.close()
                    }
                }
                OtaPiano.Out.Drop -> {
                    session.trySend(OtaEvent.Lost("the connection to the piano ended"))
                    session.close()
                    link.drop()
                }
                is OtaPiano.Out.Restart -> onRestart(o.inMs)   // the piano restarts whether or not anyone listens
            }
        }
    }
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

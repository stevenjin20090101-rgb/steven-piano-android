// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

import android.content.Context
import android.os.HandlerThread
import android.util.Log
import dev.stevenjin.stevenpiano.midi.MidiBatch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The piano over the app's own BluetoothGatt client (not MidiManager): MTU, connection
 * priority, write-without-response, pacing and reconnection are all in our hands.
 *
 * Connect: a filtered scan, stopped before connecting; connectGatt(autoConnect = false);
 * requestMtu(255), keeping whatever onMtuChanged grants; discoverServices, retried once;
 * connection priority HIGH; Connected, and the address is remembered.
 * The console: when discovery also finds the Nordic UART Service, the first operation on the
 * connection switches on its notifications (the CCCD write), and [console] carries lines both ways.
 * Writes: one GATT operation in flight, always: the next waits for its callback. MIDI goes first;
 * a console line goes only while no MIDI is waiting, so it delays a MIDI packet by one write at most.
 * A drop: close() the gatt, then Reconnecting: a background connectGatt(autoConnect = true) on
 * the same piano, plus filtered scans with backoff 1, 2, 4, 8, 15 s starting after 20 s.
 * Every radio call and every callback runs on [executor]'s thread, which never blocks, except
 * [emergencySilence]: the crash handler's direct write of the stop sequence, from whatever thread.
 */
class GattPianoLink(
    private val radio: BleRadio,
    private val executor: LinkExecutor,
    private val onConnected: (address: String, name: String) -> Unit,
    private val shouldReconnect: () -> Boolean,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : PianoLink {
    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    // Shared with the scheduler thread (send) and waiting threads (flush).
    private val writer = PacedWriter()
    @Volatile
    private var ready = false
    @Volatile
    private var writing = false

    /** The connection while Ready, for [emergencySilence] on any thread; null otherwise. */
    @Volatile
    private var readyConnection: GattConnection? = null
    private val pumpQueued = AtomicBoolean(false)
    private val drainLock = ReentrantLock()
    private val drained = drainLock.newCondition()

    // The console: lines queued from any thread, written from the link's thread.
    @Volatile
    private var consoleReady = false
    private val consoleQueue = ConcurrentLinkedQueue<ByteArray>()
    private val consoleLines = MutableSharedFlow<String>(extraBufferCapacity = CONSOLE_BUFFER_LINES, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val consoleChannel = object : ConsoleChannel {
        override val lines: SharedFlow<String> = consoleLines.asSharedFlow()

        override fun sendLine(text: String) = queueLine(text)
    }

    override val console: ConsoleChannel?
        get() = if (consoleReady) consoleChannel else null

    // Owned by the executor's thread.
    private enum class Phase { None, Connecting, Negotiating, Ready }

    private var phase = Phase.None
    private var wanted = false
    private var scanning = false
    private var reconnecting = false
    private var attempt = 0
    private var error: LinkError? = null
    private var gatt: GattConnection? = null
    private var piano: FoundPiano? = null
    private var preferredAddress: String? = null
    private var mtu = DEFAULT_MTU
    private var discoveryRetried = false
    private var connectRetried = false
    private var inFlight = false
    private var retryOp: Op? = null
    private var writeRetries = 0
    private var subscribePending = false
    private var consoleOffset = 0   // bytes of the head console line already written
    private val assembler = ConsoleLineAssembler()
    private var reconnectStartedMs = 0L
    private var backoffStep = 0
    private val throttle = ScanThrottle()

    // One Runnable per timer, so each can be cancelled.
    private val pump = Runnable { pumpNow() }
    private val startScan = Runnable { beginScan() }
    private val scanTimeout = Runnable { onScanTimeout() }
    private val connectTimeout = Runnable { onConnectFailed() }
    private val retryConnect = Runnable { piano?.let(::connectDirect) }
    private val retryInBackground = Runnable { connectInBackground() }
    private val mtuTimeout = Runnable { discover() }
    private val discoveryTimeout = Runnable { gatt?.let { onDiscovered(it, success = false) } }
    private val writeTimeout = Runnable { gatt?.let(::onWritten) }
    private val timers = listOf(pump, startScan, scanTimeout, connectTimeout, retryConnect, retryInBackground, mtuTimeout, discoveryTimeout, writeTimeout)

    private val events = object : GattEvents {
        override fun onConnectionChanged(connection: GattConnection, connected: Boolean, status: Int) =
            executor.execute { if (connected) onGattConnected(connection) else onGattLost(connection, status) }

        override fun onMtuChanged(connection: GattConnection, mtu: Int, success: Boolean) =
            executor.execute { onMtu(connection, mtu, success) }

        override fun onServicesDiscovered(connection: GattConnection, success: Boolean) =
            executor.execute { onDiscovered(connection, success) }

        override fun onWriteDone(connection: GattConnection, success: Boolean) =
            executor.execute { onWritten(connection) }

        override fun onConsoleSubscribed(connection: GattConnection, success: Boolean) = executor.execute {
            if (!success && connection === gatt) log("The piano did not switch on its console replies")
            onWritten(connection)
        }

        override fun onConsoleData(connection: GattConnection, data: ByteArray) =
            executor.execute { onConsoleNotified(connection, data) }
    }

    init {
        radio.watchAdapter { on -> executor.execute { onAdapter(on) } }
    }

    override fun connect(address: String?) = executor.execute { startConnect(address) }

    override fun disconnect() = executor.execute { stop() }

    override fun send(batch: MidiBatch, dropPending: Boolean) {
        if (!ready) return
        writer.enqueue(batch, dropPending)
        if (pumpQueued.compareAndSet(false, true)) executor.execute(pump)
    }

    /**
     * The crash handler's stop: CC64 = 0 then CC123, in one packet, written directly on the
     * connection (the link's thread may be the one crashing, and the queue may be long), retried
     * every few milliseconds while the stack is busy, for at most [timeoutMs]. False when not
     * connected or not written in time. Racing a write from the link's own thread is accepted:
     * the process is about to end, and the piano silences itself on the disconnect that follows.
     */
    override fun emergencySilence(timeoutMs: Long): Boolean {
        val connection = readyConnection ?: return false
        val packet = BleMidiFramer.frame(STOP_SEQUENCE, 0, STOP_SEQUENCE.size, executor.nanoTime() / NANOS_PER_MS)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val written = try {
                connection.write(packet) == WriteResult.Sent
            } catch (e: RuntimeException) {   // a revoked permission, a closed gatt
                return false
            }
            if (written) return true
            if (System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(EMERGENCY_RETRY_MS) > deadline) return false
            try {
                Thread.sleep(EMERGENCY_RETRY_MS)
            } catch (e: InterruptedException) {
                return false
            }
        }
    }

    /** Returns once the queue is written, the link is gone, or [timeoutMs] passes (false). Not on the link thread. */
    override fun flush(timeoutMs: Long): Boolean {
        var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        drainLock.withLock {
            while (ready && (writer.pending > 0 || writing)) {
                if (nanos <= 0) return false
                nanos = drained.awaitNanos(nanos)
            }
        }
        return true
    }

    // ---- Connecting ----------------------------------------------------------------------

    private fun startConnect(address: String?) {
        if (phase == Phase.Ready) return
        if (!reconnecting && (scanning || phase != Phase.None)) return   // already on its way
        halt()
        wanted = true
        error = null
        address?.let { preferredAddress = it }
        beginScan()
    }

    /** A filtered scan, kept under Android's silent limit of 5 scans in 30 s. */
    private fun beginScan() {
        radio.blocker()?.let { return fail(it) }
        scanning = true
        val wait = throttle.delayBeforeNextScan(nowMs())
        if (wait > 0) {
            executor.schedule(wait, startScan)
        } else {
            throttle.record(nowMs())
            guard {
                radio.startScan(
                    onFound = { found -> executor.execute { onFound(found) } },
                    onFailed = { code -> executor.execute { onScanFailed(code) } },
                )
            }
            executor.schedule(PianoScanner.SCAN_TIMEOUT_MS, scanTimeout)
        }
        publish()
    }

    private fun onFound(found: FoundPiano) {
        if (!scanning || !isPiano(found)) return
        stopScanning()
        connectDirect(found)
    }

    private fun isPiano(found: FoundPiano): Boolean =
        found.name == PianoBluetooth.NAME || found.address.equals(preferredAddress, ignoreCase = true)

    private fun onScanTimeout() {
        stopScanning()
        if (reconnecting) scheduleNextScan() else fail(LinkError.NotFound)
    }

    private fun onScanFailed(code: Int) {
        if (!scanning) return
        log("Scan failed with code $code")
        stopScanning()
        if (reconnecting) scheduleNextScan() else fail(LinkError.Failed)
    }

    /** Connects straight away to a piano the scan has just seen. */
    private fun connectDirect(found: FoundPiano) {
        piano = found
        closeGatt()
        discoveryRetried = false
        val connection = guard { radio.connect(found.address, autoConnect = false, events = events) }
            ?: return onConnectFailed()
        gatt = connection
        phase = Phase.Connecting
        executor.schedule(CONNECT_TIMEOUT_MS, connectTimeout)
        publish()
    }

    /** While reconnecting: a background connection that completes whenever the piano is back. */
    private fun connectInBackground() {
        val address = piano?.address ?: preferredAddress ?: return
        closeGatt()
        discoveryRetried = false
        gatt = guard { radio.connect(address, autoConnect = true, events = events) }
        phase = if (gatt == null) Phase.None else Phase.Connecting
        if (gatt == null) executor.schedule(BACKGROUND_RETRY_MS, retryInBackground)
        publish()
    }

    private fun onConnectFailed() {
        executor.cancel(connectTimeout)
        closeGatt()
        phase = Phase.None
        when {
            reconnecting -> {
                executor.schedule(BACKGROUND_RETRY_MS, retryInBackground)
                if (!scanning) scheduleNextScan()
            }
            !connectRetried && piano != null -> {   // a first failure (often status 133) earns one retry
                connectRetried = true
                phase = Phase.Connecting
                executor.schedule(RETRY_CONNECT_MS, retryConnect)
            }
            else -> return fail(LinkError.NotFound)
        }
        publish()
    }

    private fun onGattConnected(connection: GattConnection) {
        if (connection !== gatt) return
        executor.cancel(connectTimeout)
        stopScanning()
        phase = Phase.Negotiating
        mtu = DEFAULT_MTU
        if (guard { connection.requestMtu(REQUESTED_MTU) } == true) executor.schedule(MTU_TIMEOUT_MS, mtuTimeout) else discover()
        publish()
    }

    private fun onMtu(connection: GattConnection, granted: Int, success: Boolean) {
        if (connection !== gatt || phase != Phase.Negotiating) return
        executor.cancel(mtuTimeout)
        if (success) mtu = granted
        discover()
    }

    private fun discover() {
        executor.cancel(mtuTimeout)
        val connection = gatt ?: return
        if (guard { connection.discoverServices() } == true) {
            executor.schedule(DISCOVERY_TIMEOUT_MS, discoveryTimeout)
        } else {
            onDiscovered(connection, success = false)
        }
    }

    private fun onDiscovered(connection: GattConnection, success: Boolean) {
        if (connection !== gatt || phase != Phase.Negotiating) return
        executor.cancel(discoveryTimeout)
        when {
            success && guard { connection.hasMidiCharacteristic() } == true -> becomeReady(connection)
            !discoveryRetried -> {
                discoveryRetried = true
                discover()
            }
            else -> {
                log("No BLE-MIDI characteristic after two discoveries")
                guard { connection.disconnect() }
                closeGatt()
                phase = Phase.None
                if (reconnecting) connectInBackground() else fail(LinkError.Failed)
            }
        }
    }

    private fun becomeReady(connection: GattConnection) {
        guard { connection.requestHighPriority() }
        val hasConsole = guard { connection.hasConsole() } == true
        phase = Phase.Ready
        reconnecting = false
        attempt = 0
        error = null
        connectRetried = false
        executor.cancel(startScan)
        executor.cancel(retryInBackground)
        writer.clear()   // anything that slipped in while the last connection was going down
        consoleQueue.clear()
        consoleOffset = 0
        assembler.clear()
        subscribePending = hasConsole   // the connection's first write, so no reply is lost
        consoleReady = hasConsole
        ready = true
        readyConnection = connection
        val name = piano?.name ?: PianoBluetooth.NAME
        val address = piano?.address ?: preferredAddress
        publish()
        log("Connected to $name, MTU $mtu" + if (hasConsole) ", with its console" else ", no console")
        address?.let {
            preferredAddress = it
            onConnected(it, name)
        }
        pumpNow()
    }

    // ---- Losing the connection -----------------------------------------------------------

    private fun onGattLost(connection: GattConnection, status: Int) {
        if (connection !== gatt) {
            guard { connection.close() }   // a late callback from a replaced connection
            return
        }
        log("Connection lost, status $status")
        val wasReady = phase == Phase.Ready
        closeGatt()
        phase = Phase.None
        when {
            !wanted -> publish()
            wasReady -> startReconnecting()
            reconnecting -> {
                executor.schedule(BACKGROUND_RETRY_MS, retryInBackground)
                publish()
            }
            else -> onConnectFailed()
        }
    }

    private fun startReconnecting() {
        if (!shouldReconnect()) {
            wanted = false
            publish()
            return
        }
        reconnecting = true
        attempt = 1
        backoffStep = 0
        reconnectStartedMs = nowMs()
        connectInBackground()
        executor.schedule(RECONNECT_SCAN_AFTER_MS, startScan)
        publish()
    }

    /** The next reconnect scan, backing off; after a while only the background connection waits on. */
    private fun scheduleNextScan() {
        if (nowMs() - reconnectStartedMs > STOP_SCANNING_AFTER_MS) return
        attempt++
        executor.schedule(BACKOFF_MS[minOf(backoffStep++, BACKOFF_MS.lastIndex)], startScan)
        publish()
    }

    private fun onAdapter(on: Boolean) {
        if (!on) {
            val keep = wanted
            halt()
            wanted = keep
            error = if (keep) LinkError.BluetoothOff else null
            publish()
        } else if (wanted && phase == Phase.None && !scanning && !reconnecting) {
            error = null
            if (piano != null) startReconnecting() else beginScan()
        }
    }

    /** The person pressed Disconnect. */
    private fun stop() {
        wanted = false
        halt()
        error = null
        publish()
    }

    private fun fail(reason: LinkError) {
        log("Not connected: ${reason.name}")
        wanted = false
        halt()
        error = reason
        publish()
    }

    /** Stops scanning, timers and reconnection, and closes the connection. Leaves [wanted] alone. */
    private fun halt() {
        stopScanning()
        timers.forEach(executor::cancel)
        gatt?.let { guard { it.disconnect() } }
        closeGatt()
        phase = Phase.None
        reconnecting = false
        attempt = 0
        connectRetried = false
    }

    private fun stopScanning() {
        executor.cancel(scanTimeout)
        executor.cancel(startScan)
        if (!scanning) return
        scanning = false
        guard { radio.stopScan() }
    }

    /** close() always, before any new connectGatt: Android leaks a client per unclosed gatt. */
    private fun closeGatt() {
        val connection = gatt ?: return
        gatt = null
        ready = false
        readyConnection = null
        consoleReady = false
        writer.clear()
        consoleQueue.clear()
        consoleOffset = 0
        subscribePending = false
        assembler.clear()
        executor.cancel(writeTimeout)
        inFlight = false
        retryOp = null
        writeRetries = 0
        setWriting(false)
        guard { connection.close() }
    }

    // ---- Writing -------------------------------------------------------------------------

    /** One GATT operation: a MIDI packet, a piece of a console line, or switching on the console's replies. */
    private sealed interface Op {
        class Midi(val packet: ByteArray) : Op

        class Console(val chunk: ByteArray, val endsLine: Boolean) : Op

        data object Subscribe : Op
    }

    private fun pumpNow() {
        pumpQueued.set(false)
        executor.cancel(pump)
        val connection = gatt
        if (phase != Phase.Ready || inFlight || connection == null) return
        val now = executor.nanoTime()
        val op = retryOp ?: nextOp(now)
        if (op == null) {
            val wait = writer.nanosUntilReady(now)
            if (wait == Long.MAX_VALUE) setWriting(false) else executor.schedule((wait + NANOS_PER_MS - 1) / NANOS_PER_MS, pump)
            return
        }
        setWriting(op is Op.Midi || writer.pending > 0)   // flush() waits for MIDI only
        val result = guard {
            when (op) {
                is Op.Midi -> connection.write(op.packet)
                is Op.Console -> connection.writeConsole(op.chunk)
                Op.Subscribe -> connection.subscribeConsole()
            }
        } ?: WriteResult.Failed
        when (result) {
            WriteResult.Sent -> {
                inFlight = true
                retryOp = null
                writeRetries = 0
                done(op)
                executor.schedule(WRITE_TIMEOUT_MS, writeTimeout)
            }
            WriteResult.Busy, WriteResult.Failed -> {
                retryOp = op
                if (++writeRetries > MAX_WRITE_RETRIES) {
                    log(if (op is Op.Midi) "Dropped a packet the stack would not take" else "Dropped a console write the stack would not take")
                    retryOp = null
                    writeRetries = 0
                    done(op, dropped = true)
                }
                executor.schedule(WRITE_RETRY_MS, pump)
            }
        }
    }

    /**
     * What goes next: the console subscription (once, first), then MIDI. A console line waits
     * while any MIDI is queued, even MIDI still waiting for the pace, so it never holds up a due
     * packet by more than the one write already in flight.
     */
    private fun nextOp(now: Long): Op? {
        if (subscribePending) return Op.Subscribe
        writer.nextPacket(now, mtu, now / NANOS_PER_MS)?.let { return Op.Midi(it) }
        if (writer.pending > 0) return null
        val line = consoleQueue.peek() ?: return null
        val end = minOf(line.size, consoleOffset + (mtu - ATT_HEADER).coerceAtLeast(1))
        return Op.Console(line.copyOfRange(consoleOffset, end), endsLine = end == line.size)
    }

    /** An operation went out (or was given up on): move past it. A console line given up on is dropped whole. */
    private fun done(op: Op, dropped: Boolean = false) {
        when (op) {
            is Op.Midi -> Unit
            Op.Subscribe -> subscribePending = false
            is Op.Console -> if (op.endsLine || dropped) {
                consoleQueue.poll()
                consoleOffset = 0
            } else {
                consoleOffset += op.chunk.size
            }
        }
    }

    /** From any thread: a console line joins the queue, behind MIDI. */
    private fun queueLine(text: String) {
        val bytes = PianoConsole.encode(text) ?: return log("Console line not sent: longer than ${ConsoleChannel.MAX_LINE} characters, or more than one line")
        if (!consoleReady) return
        consoleQueue.add(bytes)
        if (pumpQueued.compareAndSet(false, true)) executor.execute(pump)
    }

    private fun onConsoleNotified(connection: GattConnection, data: ByteArray) {
        if (connection !== gatt || !consoleReady) return
        assembler.feed(data) { consoleLines.tryEmit(it) }
    }

    private fun onWritten(connection: GattConnection) {
        if (connection !== gatt) return
        executor.cancel(writeTimeout)
        inFlight = false
        pumpNow()
    }

    private fun setWriting(value: Boolean) {
        writing = value
        if (!value) drainLock.withLock { drained.signalAll() }
    }

    // ---- Helpers -------------------------------------------------------------------------

    private fun publish() {
        _state.value = when {
            phase == Phase.Ready -> LinkState.Connected(piano?.name ?: PianoBluetooth.NAME, mtu)
            reconnecting -> LinkState.Reconnecting(attempt)
            scanning -> LinkState.Scanning
            phase != Phase.None -> LinkState.Connecting
            else -> error?.toState() ?: LinkState.Disconnected
        }
    }

    private fun nowMs(): Long = executor.nanoTime() / NANOS_PER_MS

    /** Runs a radio call. A revoked permission surfaces as SecurityException: the link then stops with an error. */
    private inline fun <T> guard(block: () -> T): T? = try {
        block()
    } catch (e: SecurityException) {
        log("Bluetooth permission missing: ${e.message}")
        executor.execute { if (wanted || phase != Phase.None) fail(LinkError.PermissionMissing) }
        null
    }

    companion object {
        private const val TAG = "PianoLink"
        private const val DEFAULT_MTU = 23
        private const val REQUESTED_MTU = 255
        private const val NANOS_PER_MS = 1_000_000L
        private const val ATT_HEADER = 3
        private const val CONSOLE_BUFFER_LINES = 512
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val RETRY_CONNECT_MS = 500L
        private const val BACKGROUND_RETRY_MS = 1_000L
        private const val MTU_TIMEOUT_MS = 3_000L
        private const val DISCOVERY_TIMEOUT_MS = 10_000L
        private const val WRITE_TIMEOUT_MS = 1_000L
        private const val WRITE_RETRY_MS = 5L
        private const val MAX_WRITE_RETRIES = 40
        private const val EMERGENCY_RETRY_MS = 5L

        /** Pedal up, then All Notes Off: the stop sequence, as packed messages. */
        private val STOP_SEQUENCE = intArrayOf(MidiBatch.pack(0xB0, 64, 0), MidiBatch.pack(0xB0, 123, 0))
        private const val RECONNECT_SCAN_AFTER_MS = 20_000L
        private val BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)

        /** After this long, reconnect scans stop to spare the battery; the background connection keeps waiting. */
        private const val STOP_SCANNING_AFTER_MS = 10 * 60_000L

        /** The real link: Android Bluetooth, on its own "steven-piano-ble" thread. */
        fun create(context: Context, onConnected: (String, String) -> Unit, shouldReconnect: () -> Boolean): GattPianoLink {
            val thread = HandlerThread("steven-piano-ble").apply { start() }
            return GattPianoLink(AndroidBleRadio(context.applicationContext), HandlerLinkExecutor(thread.looper), onConnected, shouldReconnect)
        }
    }
}

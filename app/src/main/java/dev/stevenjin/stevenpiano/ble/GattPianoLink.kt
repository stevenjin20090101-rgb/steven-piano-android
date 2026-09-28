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
import dev.stevenjin.stevenpiano.diag.LinkLog
import dev.stevenjin.stevenpiano.midi.MidiBatch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The piano over the app's own BluetoothGatt client (not MidiManager): MTU, connection
 * priority, write-without-response, pacing and reconnection are all in our hands.
 *
 * Connect: a filtered scan, stopped before connecting; connectGatt(autoConnect = false);
 * requestMtu(255), keeping whatever onMtuChanged grants; discoverServices, retried once;
 * connection priority HIGH; Connected, and the address is remembered. The remembered address is
 * pinned: another BLE-MIDI device advertising as "Steven Piano" is never connected to by itself;
 * a scan that finds only such a one offers it ([LinkError.OtherPiano]) for the person to choose,
 * and reconnecting ignores it. Each connection has a new [LinkState.Connected.epoch].
 * Before each scan: the piano does not advertise while connected, so when another app on this
 * device holds it (the remembered address, or with none remembered a device named Steven Piano),
 * it is connected to directly, as Android lets a second app do. A piano this device is paired with
 * is not connected to ([LinkError.Paired]): it refuses encryption, so the bond would fail it.
 * With no piano remembered, the name may be missing (a hardware scan filter can drop the scan
 * response that carries it): a nameless BLE-MIDI device is a candidate, connected to when no named
 * Steven Piano has answered within a second, and it becomes the piano only if its GAP Device Name
 * (0x1800/0x2A00) reads "Steven Piano"; otherwise it is let go, before any MIDI, and the scan goes
 * on without it.
 * The console: when discovery also finds the Nordic UART Service, the first operation on the
 * connection switches on its notifications (the CCCD write), and [console] carries lines both ways.
 * Writes: one GATT operation in flight, always: the next waits for its callback. MIDI goes first;
 * a console line goes only while no MIDI is waiting, so it delays a MIDI packet by one write at most.
 * A packet the stack keeps refusing is retried, and one that lets a key or the pedal go is never
 * given up while connected; any other given up on is followed by the stop sequence and a new
 * epoch, so the player re-syncs the piano.
 * A drop: close() the gatt, then Reconnecting: a background connectGatt(autoConnect = true) on
 * the same piano, plus filtered scans with backoff 1, 2, 4, 8, 15 s starting after 20 s.
 * Every radio call and every callback runs on [executor]'s thread, which never blocks, except
 * [emergencySilence]: the crash handler's direct write of the stop sequence, from whatever thread.
 * A radio call that throws (SecurityException for a revoked permission, IllegalStateException while
 * Bluetooth turns off) never crashes the app.
 * Why a connection failed is kept apart, for the Piano tab: a search that found nothing
 * ([LinkError.NotFound], mentioning Location when it is off), a piano found that would not connect
 * ([LinkError.ConnectFailed], with the last GATT status or the timeout), a scan Android refused
 * ([LinkError.ScanFailed], with its code). After "Bluetooth is off", Bluetooth coming back on clears
 * the error and, with auto-connect on, looks for the piano again.
 * The firmware (v1.6 — M21, BLE_OTA.md): on firmware 2.0.0 and later, discovery also finds Device
 * Information, whose Firmware Revision String is read before Connected ([firmwareVersion]), and the
 * update service ([ota]). An update session switches Control's notifications on (once a
 * connection), writes BEGIN, END and ABORT with response and the image's Data frames without, all
 * through the same one-operation queue: MIDI first, then the update's frames in order, then console
 * lines. A Data frame is never dropped for a busy stack (a gap would cost the whole transfer); one
 * the stack keeps refusing ends the session instead. Any end of the connection ends a session
 * ([OtaEvent.Lost]), and stale frames never outlive their session. [expectRestart]: after an
 * update's OK the piano restarts on purpose, so for that window a drop is reconnected whatever
 * auto-connect says, with the first scan after [RESTART_SCAN_AFTER_MS] rather than 20 s.
 * [log] is the link's trail, kept in release builds (`Log.w`, tag "PianoLink": R8 strips only
 * v, d and i): each scan with its filter, each device a scan sees (once per address per scan: its
 * address, name, RSSI, whether it advertised the MIDI service, and what the link did about it), each
 * connectGatt, connections made and lost with their status, the MTU, the services found and each
 * failure with its reason, so `adb logcat -s PianoLink:W` shows why a connection failed. It holds
 * Bluetooth addresses and advertised names only. The real link ([create]) writes it through
 * [LinkLog.warn], which also keeps the last 500 lines in memory for the diagnostics share.
 */
class GattPianoLink(
    private val radio: BleRadio,
    private val executor: LinkExecutor,
    private val onConnected: (address: String, name: String) -> Unit,
    private val shouldReconnect: () -> Boolean,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
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

    // The firmware: its version (read before Connected) and its update service.
    private val _firmwareVersion = MutableStateFlow<String?>(null)
    override val firmwareVersion: StateFlow<String?> = _firmwareVersion.asStateFlow()

    @Volatile
    private var otaReady = false

    /** The largest Data payload at this connection's MTU, set as it becomes ready. */
    @Volatile
    private var otaChunk = 0

    /** The update's frames in order (the subscription, BEGIN, Data, END, ABORT), written from the link's thread. */
    private val otaQueue = ConcurrentLinkedQueue<Op>()
    private val otaSession = AtomicReference<OtaSession?>(null)
    private val otaChannel = object : OtaChannel {
        override val maxChunk: Int get() = otaChunk
        override val window: Int get() = OtaFrames.WINDOW

        override fun begin(header: OtaBegin): Flow<OtaEvent> = openSession(header)

        override fun write(seq: Int, payload: ByteArray): Boolean = queueData(seq, payload)

        override fun end() = queueEnd()

        override fun abort() = executor.execute { abortSession() }
    }

    override val ota: OtaChannel?
        get() = if (otaReady) otaChannel else null

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

    /** Another "Steven Piano" a scan found while looking for the pinned one; offered if that one never shows. */
    private var otherPiano: FoundPiano? = null
    private var errorOther: String? = null

    /** Bumped for each connection and each packet given up on; see [LinkState.Connected.epoch]. */
    private var epoch = 0L
    private var mtu = DEFAULT_MTU
    private var discoveryRetried = false
    private var connectRetried = false
    private var inFlight = false
    private var retryOp: Op? = null
    private var writeRetries = 0
    private var subscribePending = false
    private var consoleOffset = 0   // bytes of the head console line already written

    /** The operation written and not yet answered, so a failed Control write can end its session. */
    private var inFlightOp: Op? = null

    /** Control's notifications are on for this connection. */
    private var otaSubscribed = false

    /** The Firmware Revision String is being read (before Ready). */
    private var readingVersion = false

    /** Until when (by [nowMs]) a drop is an expected restart ([expectRestart]); 0 when none is. */
    private var restartUntilMs = 0L
    private val assembler = ConsoleLineAssembler()
    private var reconnectStartedMs = 0L
    private var backoffStep = 0
    private val throttle = ScanThrottle()

    /** Addresses the current scan has seen (upper case), so each is logged once a scan. */
    private val seen = HashSet<String>()

    /** How the last connection attempt ended: its GATT status, [NO_CONNECTION], or null for the timeout. */
    private var connectStatus: Int? = null

    /** Whether Bluetooth was last reported on, so each change is logged once (off comes as "turning off", then "off"). */
    private var bluetoothOn: Boolean? = null

    /** "The piano fell behind" is logged once a connection: a file too dense to play would log it with every batch. */
    private val behindLogged = AtomicBoolean(false)

    /** With no piano remembered: a nameless BLE-MIDI device the scan saw, which may be the piano without its scan response. */
    private var candidate: FoundPiano? = null

    /** The connection under way is to a nameless candidate: its GAP Device Name decides whether it is the piano. */
    private var verifyName = false

    /** Candidates whose GAP Device Name was not Steven Piano (upper case), passed over until the next Connect. */
    private val rejected = HashSet<String>()

    /** The stop sequence, queued after a packet was given up on. Only ever read. */
    private val silence = MidiBatch().apply {
        add(0xB0, 64, 0)
        add(0xB0, 123, 0)
    }

    // One Runnable per timer, so each can be cancelled.
    private val pump = Runnable { pumpNow() }
    private val startScan = Runnable { beginScan() }
    private val scanTimeout = Runnable { onScanTimeout() }
    private val connectTimeout = Runnable { onConnectTimeout() }
    private val retryConnect = Runnable { piano?.let(::connectDirect) }
    private val retryInBackground = Runnable { connectInBackground() }
    private val mtuTimeout = Runnable { onMtuTimeout() }
    private val discoveryTimeout = Runnable { gatt?.let { onDiscovered(it, success = false, failure = "had no answer within ${DISCOVERY_TIMEOUT_MS / 1000} s") } }
    private val writeTimeout = Runnable { gatt?.let { onWritten(it) } }
    private val offerOther = Runnable { onOtherPianoOnly() }
    private val connectCandidate = Runnable { onCandidateWaited() }
    private val nameTimeout = Runnable { gatt?.let { onNameRead(it, null, "no answer within ${NAME_TIMEOUT_MS / 1000} s") } }
    private val versionTimeout = Runnable { gatt?.let { onVersionRead(it, null, "no answer within ${VERSION_TIMEOUT_MS / 1000} s") } }
    private val restartWindowEnd = Runnable { onRestartWindowEnded() }
    private val timers = listOf(
        pump, startScan, scanTimeout, connectTimeout, retryConnect, retryInBackground, mtuTimeout, discoveryTimeout, writeTimeout,
        offerOther, connectCandidate, nameTimeout, versionTimeout,
    )

    private val events = object : GattEvents {
        override fun onConnectionChanged(connection: GattConnection, connected: Boolean, status: Int) =
            executor.execute { if (connected) onGattConnected(connection, status) else onGattLost(connection, status) }

        override fun onMtuChanged(connection: GattConnection, mtu: Int, success: Boolean) =
            executor.execute { onMtu(connection, mtu, success) }

        override fun onServicesDiscovered(connection: GattConnection, success: Boolean) =
            executor.execute { onDiscovered(connection, success) }

        override fun onWriteDone(connection: GattConnection, success: Boolean) =
            executor.execute { onWritten(connection, success) }

        override fun onConsoleSubscribed(connection: GattConnection, success: Boolean) = executor.execute {
            if (!success && connection === gatt) log("The piano did not switch on its console replies")
            onWritten(connection)
        }

        override fun onConsoleData(connection: GattConnection, data: ByteArray) =
            executor.execute { onConsoleNotified(connection, data) }

        override fun onDeviceName(connection: GattConnection, name: String?, status: Int) =
            executor.execute { onNameRead(connection, if (status == 0) name else null, "status ${BleCodes.gattStatus(status)}") }

        override fun onFirmwareVersion(connection: GattConnection, value: ByteArray?, status: Int) =
            executor.execute { onVersionRead(connection, if (status == 0) PianoOta.version(value) else null, "status ${BleCodes.gattStatus(status)}") }

        override fun onOtaSubscribed(connection: GattConnection, success: Boolean) = executor.execute { onOtaSubscribeDone(connection, success) }

        override fun onOtaNotified(connection: GattConnection, data: ByteArray) = executor.execute { onOtaAnswer(connection, data) }
    }

    init {
        radio.watchAdapter { on -> executor.execute { onAdapter(on) } }
    }

    override fun connect(address: String?) = executor.execute { startConnect(address) }

    override fun disconnect() = executor.execute { stop() }

    override fun expectRestart(withinMs: Long) = executor.execute {
        restartUntilMs = nowMs() + withinMs
        executor.schedule(withinMs, restartWindowEnd)
        log("The piano restarts after its update: a drop in the next ${withinMs / 1000} s is reconnected")
    }

    override fun send(batch: MidiBatch, dropPending: Boolean) {
        if (!ready) return
        val dropped = writer.enqueue(batch, dropPending)
        if (dropped > 0 && behindLogged.compareAndSet(false, true)) log("The piano fell behind: $dropped waiting notes dropped (logged once a connection)")
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
        errorOther = null
        address?.let { preferredAddress = it }
        rejected.clear()
        log("Connect: " + (preferredAddress?.let { "the remembered piano is $it" } ?: "no piano remembered yet"))
        beginScan()
    }

    /** A filtered scan, kept under Android's silent limit of 5 scans in 30 s; first, the piano if another app holds it. */
    private fun beginScan() {
        radio.blocker()?.let { return fail(it) }
        heldPiano()?.let { held ->
            log("${held.address} ${BleCodes.name(held.name)} is connected to this device already (another app holds it): connecting to it directly")
            return connectFound(held)
        }
        scanning = true
        val wait = throttle.delayBeforeNextScan(nowMs())
        if (wait > 0) {
            log("Scan waits $wait ms: Android allows 5 scans in 30 s")
            executor.schedule(wait, startScan)
        } else {
            throttle.record(nowMs())
            seen.clear()
            log(
                "Scan started (filter: MIDI service ${PianoBluetooth.SERVICE_UUID.toString().uppercase()}, mode: ${PianoScanner.MODE}), " +
                    "looking for " + (preferredAddress ?: "any Steven Piano"),
            )
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

    /** A scan result. The first of each address in a scan is logged with what the link does about it. */
    private fun onFound(found: FoundPiano) {
        if (!scanning) return
        val pinned = preferredAddress
        val first = seen.add(found.address.uppercase())
        fun sighted(decision: String) {
            if (!first) return
            log(
                "Seen ${found.address} ${BleCodes.name(found.name)}, RSSI ${found.rssi?.let { "$it dBm" } ?: "unknown"}, " +
                    "MIDI service ${yesNo(found.advertisesMidi)}: $decision",
            )
        }
        when {
            pinned != null && found.address.equals(pinned, ignoreCase = true) -> {
                sighted("the remembered piano, connecting")
                connectFound(found)
            }
            pinned == null && found.name == null && found.advertisesMidi -> sighted(considerCandidate(found))
            found.name != PianoBluetooth.NAME -> {
                sighted(if (found.name == null) "ignored: no name" else "ignored: name is not Steven Piano")
                if (found.name != null && candidate?.address.equals(found.address, ignoreCase = true)) {
                    log("${found.address} is no candidate after all: its name is ${BleCodes.name(found.name)}")
                    executor.cancel(connectCandidate)
                    candidate = null
                }
            }
            pinned == null -> {   // no piano known yet: the first Steven Piano
                sighted("connecting")
                connectFound(found)
            }
            reconnecting -> sighted("ignored: another Steven Piano (a reconnection never switches pianos)")
            otherPiano == null -> {   // the person decides, unless the known piano answers first
                sighted("another Steven Piano: offered in ${OTHER_PIANO_GRACE_MS / 1000} s unless the remembered one answers")
                otherPiano = found
                executor.schedule(OTHER_PIANO_GRACE_MS, offerOther)
            }
            else -> sighted("ignored: another Steven Piano")
        }
    }

    /**
     * With no piano remembered, a nameless BLE-MIDI device: perhaps the piano, its scan response lost.
     * The first becomes the candidate, connected to after [NAMELESS_GRACE_MS] unless a named Steven
     * Piano answers first. Returns what was decided, for the log.
     */
    private fun considerCandidate(found: FoundPiano): String {
        val waiting = candidate
        return when {
            found.address.uppercase() in rejected -> "ignored: its GAP Device Name was not Steven Piano"
            waiting == null -> {
                candidate = found
                executor.schedule(NAMELESS_GRACE_MS, connectCandidate)
                "no name: a candidate, connected to in ${NAMELESS_GRACE_MS / 1000} s to read its name unless a named Steven Piano answers"
            }
            waiting.address.equals(found.address, ignoreCase = true) -> "no name: the candidate"
            else -> "ignored for now: no name, and another candidate is waiting"
        }
    }

    /** No named Steven Piano in time: the candidate is connected to, and its GAP Device Name will decide. */
    private fun onCandidateWaited() {
        val found = candidate ?: return
        if (!scanning) return
        log("No named Steven Piano within ${NAMELESS_GRACE_MS / 1000} s: connecting to ${found.address} to read its name")
        connectFound(found, verify = true)
    }

    /**
     * Connects to [found], found by a scan or held by another app, unless this device is paired with it.
     * [verify]: it is a nameless candidate, taken only if its GAP Device Name is Steven Piano.
     */
    private fun connectFound(found: FoundPiano, verify: Boolean = false) {
        executor.cancel(offerOther)
        otherPiano = null
        executor.cancel(connectCandidate)
        candidate = null
        stopScanning()
        if (guard { radio.isBonded(found.address) } == true) {
            log("${found.address} is paired with this device in Bluetooth settings (bonded): the piano refuses encryption, so connecting would fail")
            return fail(LinkError.Paired)
        }
        verifyName = verify
        connectRetried = false
        connectDirect(found)
    }

    /**
     * The piano, when another app on this device holds a connection to it: it does not advertise while
     * connected, so no scan would find it. With a piano remembered only its address counts (never
     * another "Steven Piano"); with none, the name does.
     */
    private fun heldPiano(): FoundPiano? {
        val pinned = preferredAddress
        return guard { radio.connectedDevices() }.orEmpty().firstOrNull { device ->
            if (pinned != null) {
                device.address.equals(pinned, ignoreCase = true)
            } else {
                device.name == PianoBluetooth.NAME && device.address.uppercase() !in rejected
            }
        }
    }

    /** Only another "Steven Piano" answered: offered, never connected to. */
    private fun onOtherPianoOnly() {
        val other = otherPiano ?: return
        if (!scanning || reconnecting) return
        stopScanning()
        fail(LinkError.OtherPiano, other.address, "the remembered piano did not answer within ${OTHER_PIANO_GRACE_MS / 1000} s")
    }

    /** The only way to [LinkError.NotFound]: a search that ended without the piano. */
    private fun onScanTimeout() {
        stopScanning()
        val saw = "${seen.size} BLE-MIDI device" + (if (seen.size == 1) "" else "s") + " seen"
        when {
            reconnecting -> {
                log("Reconnect scan ended without the piano ($saw)")
                scheduleNextScan()
            }
            candidate != null -> {
                val found = candidate ?: return
                log("Scan ended ($saw) with only a nameless candidate: connecting to ${found.address} to read its name")
                connectFound(found, verify = true)
            }
            otherPiano != null -> fail(LinkError.OtherPiano, otherPiano?.address, "only another Steven Piano answered")
            else -> {
                val locationOff = guard { radio.locationServicesOn() } == false
                val detail = "scan ended after ${PianoScanner.SCAN_TIMEOUT_MS / 1000} s, $saw" + if (locationOff) ", Location is off" else ""
                fail(LinkError.NotFound(locationOff), detail = detail)
            }
        }
    }

    private fun onScanFailed(code: Int) {
        if (!scanning) return
        log("Scan failed: code ${BleCodes.scanFailure(code)}")
        stopScanning()
        when {
            reconnecting -> scheduleNextScan()
            code == PianoScanner.NO_SCANNER -> fail(LinkError.BluetoothOff)   // no scanner while Bluetooth is off
            else -> fail(LinkError.ScanFailed(code))
        }
    }

    /** Connects straight away to a piano the scan has just seen. */
    private fun connectDirect(found: FoundPiano) {
        piano = found
        closeGatt()
        discoveryRetried = false
        log("connectGatt ${found.address}, autoConnect=false" + if (connectRetried) " (the one retry)" else "")
        val connection = guard { radio.connect(found.address, autoConnect = false, events = events) }
        if (connection == null) {
            log("connectGatt ${found.address} gave no connection")
            connectStatus = NO_CONNECTION
            return onConnectFailed()
        }
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
        log("connectGatt $address, autoConnect=true (in the background, until the piano is back)")
        gatt = guard { radio.connect(address, autoConnect = true, events = events) }
        phase = if (gatt == null) Phase.None else Phase.Connecting
        if (gatt == null) {
            log("connectGatt $address gave no connection: again in ${BACKGROUND_RETRY_MS / 1000} s")
            executor.schedule(BACKGROUND_RETRY_MS, retryInBackground)
        }
        publish()
    }

    private fun onConnectTimeout() {
        log("No connection to ${piano?.address ?: "the piano"} within ${CONNECT_TIMEOUT_MS / 1000} s")
        connectStatus = null
        onConnectFailed()
    }

    /** A connection attempt ended without a connection; [connectStatus] says how. */
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
                log("Connecting to ${piano?.address} failed: ${connectOutcome()}; one retry in $RETRY_CONNECT_MS ms")
                connectRetried = true
                phase = Phase.Connecting
                executor.schedule(RETRY_CONNECT_MS, retryConnect)
            }
            else -> return fail(LinkError.ConnectFailed(connectStatus))
        }
        publish()
    }

    /** How the last attempt ended, for the log. */
    private fun connectOutcome(): String = when (val status = connectStatus) {
        null -> "timeout"
        NO_CONNECTION -> "connectGatt gave no connection"
        else -> "status ${BleCodes.gattStatus(status)}"
    }

    private fun onGattConnected(connection: GattConnection, status: Int) {
        if (connection !== gatt) return
        log("GATT connected: ${connection.address}, status ${BleCodes.gattStatus(status)}")
        executor.cancel(connectTimeout)
        stopScanning()
        phase = Phase.Negotiating
        mtu = DEFAULT_MTU
        if (guard { connection.requestMtu(REQUESTED_MTU) } == true) {
            executor.schedule(MTU_TIMEOUT_MS, mtuTimeout)
        } else {
            log("MTU request not sent: using $DEFAULT_MTU")
            discover()
        }
        publish()
    }

    private fun onMtu(connection: GattConnection, granted: Int, success: Boolean) {
        if (connection !== gatt || phase != Phase.Negotiating) return
        executor.cancel(mtuTimeout)
        if (success) mtu = granted
        log(if (success) "MTU $granted (asked for $REQUESTED_MTU)" else "MTU request failed: using $mtu")
        discover()
    }

    private fun onMtuTimeout() {
        if (phase != Phase.Negotiating) return
        log("MTU: no answer within ${MTU_TIMEOUT_MS / 1000} s, using $mtu")
        discover()
    }

    private fun discover() {
        executor.cancel(mtuTimeout)
        val connection = gatt ?: return
        if (guard { connection.discoverServices() } == true) {
            executor.schedule(DISCOVERY_TIMEOUT_MS, discoveryTimeout)
        } else {
            onDiscovered(connection, success = false, failure = "could not start")
        }
    }

    private fun onDiscovered(connection: GattConnection, success: Boolean, failure: String = "failed") {
        if (connection !== gatt || phase != Phase.Negotiating) return
        executor.cancel(discoveryTimeout)
        val midi = success && guard { connection.hasMidiCharacteristic() } == true
        if (success) {
            log("Services discovered: MIDI ${yesNo(midi)}, console ${yesNo(guard { connection.hasConsole() } == true)}")
        } else {
            log("Service discovery $failure")
        }
        when {
            midi && verifyName -> readName(connection)
            midi -> finishSetup(connection)
            !discoveryRetried -> {
                discoveryRetried = true
                log("Discovering the services once more")
                discover()
            }
            else -> {
                log("No BLE-MIDI characteristic after two discoveries")
                val found = piano
                if (verifyName && found != null) {
                    log("${found.address} is not the piano: disconnecting, and the scan goes on without it")
                    return passOver(connection, found)
                }
                guard { connection.disconnect() }
                closeGatt()
                phase = Phase.None
                if (reconnecting) connectInBackground() else fail(LinkError.Failed)
            }
        }
    }

    /** A nameless candidate, connected and discovered: its GAP Device Name decides whether it is the piano. */
    private fun readName(connection: GattConnection) {
        log("Reading the GAP Device Name of ${connection.address}")
        if (guard { connection.readDeviceName() } == true) {
            executor.schedule(NAME_TIMEOUT_MS, nameTimeout)
        } else {
            onNameRead(connection, null, "the device has none")
        }
    }

    /**
     * The candidate's GAP Device Name: "Steven Piano" makes it the piano; anything else, or no answer,
     * lets it go (no MIDI has been sent) and the scan goes on without it until the next Connect.
     */
    private fun onNameRead(connection: GattConnection, name: String?, failure: String) {
        if (connection !== gatt || phase != Phase.Negotiating || !verifyName) return
        executor.cancel(nameTimeout)
        val found = piano ?: return
        if (name == PianoBluetooth.NAME) {
            log("GAP Device Name ${BleCodes.name(name)}: it is the piano")
            verifyName = false
            piano = FoundPiano(found.address, PianoBluetooth.NAME, found.rssi, found.advertisesMidi)
            finishSetup(connection)
            return
        }
        log(
            (if (name != null) "GAP Device Name ${BleCodes.name(name)} is not Steven Piano: " else "GAP Device Name not read: $failure. Not taken for the piano; ") +
                "disconnecting, and the scan goes on without ${found.address}",
        )
        passOver(connection, found)
    }

    /** A candidate that is not the piano, or cannot be told to be: let go before any MIDI, and passed over until the next Connect. */
    private fun passOver(connection: GattConnection, found: FoundPiano) {
        rejected += found.address.uppercase()
        guard { connection.disconnect() }
        closeGatt()
        phase = Phase.None
        verifyName = false
        piano = null
        beginScan()
    }

    /**
     * The last step before Ready: on firmware that has Device Information (2.0.0 and later) its
     * Firmware Revision String is read first ([VERSION_TIMEOUT_MS] at most), so [firmwareVersion] is
     * known as the link turns Connected. Older firmware goes straight on.
     */
    private fun finishSetup(connection: GattConnection) {
        if (guard { connection.hasFirmwareVersion() } == true && guard { connection.readFirmwareVersion() } == true) {
            readingVersion = true
            executor.schedule(VERSION_TIMEOUT_MS, versionTimeout)
        } else {
            becomeReady(connection, version = null)
        }
    }

    private fun onVersionRead(connection: GattConnection, version: String?, failure: String) {
        if (connection !== gatt || phase != Phase.Negotiating || !readingVersion) return
        readingVersion = false
        executor.cancel(versionTimeout)
        becomeReady(connection, version, versionFailure = if (version == null) failure else null)
    }

    private fun becomeReady(connection: GattConnection, version: String?, versionFailure: String? = null) {
        guard { connection.requestHighPriority() }
        val hasConsole = guard { connection.hasConsole() } == true
        val hasOta = guard { connection.hasOta() } == true
        if (version != null || versionFailure != null || hasOta) {
            log(
                (if (version != null) "Firmware ${BleCodes.name(version)}" else "Firmware version not read: ${versionFailure ?: "no Device Information"}") +
                    ", update service ${yesNo(hasOta)}",
            )
        }
        otaQueue.clear()
        otaSubscribed = false
        otaChunk = OtaFrames.maxChunk(mtu)
        otaReady = hasOta
        _firmwareVersion.value = version
        restartUntilMs = 0L
        executor.cancel(restartWindowEnd)
        phase = Phase.Ready
        reconnecting = false
        attempt = 0
        error = null
        errorOther = null
        epoch++
        connectRetried = false
        behindLogged.set(false)
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
        log("Connected to $name (${connection.address}), MTU $mtu, " + if (hasConsole) "with its console" else "no console")
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
        log("GATT disconnected: ${connection.address}, status ${BleCodes.gattStatus(status)}")
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
            else -> {
                connectStatus = status
                onConnectFailed()
            }
        }
    }

    private fun startReconnecting() {
        val restarting = restartExpected()
        if (!shouldReconnect() && !restarting) {
            log("Auto-connect is off: not reconnecting")
            wanted = false
            publish()
            return
        }
        val scanAfter = if (restarting) RESTART_SCAN_AFTER_MS else RECONNECT_SCAN_AFTER_MS
        log(
            (if (restarting) "The piano is restarting: reconnecting" else "Reconnecting") +
                ": in the background at once, and with scans from ${scanAfter / 1000} s",
        )
        reconnecting = true
        attempt = 1
        backoffStep = 0
        reconnectStartedMs = nowMs()
        connectInBackground()
        executor.schedule(scanAfter, startScan)
        publish()
    }

    /** Whether a drop now is the restart [expectRestart] announced. */
    private fun restartExpected(): Boolean = restartUntilMs != 0L && nowMs() < restartUntilMs

    /** The restart's window is over without the piano: with auto-connect off, the link stops looking. */
    private fun onRestartWindowEnded() {
        restartUntilMs = 0L
        if (reconnecting && !shouldReconnect()) {
            log("The piano did not come back within the restart's window; auto-connect is off: not reconnecting")
            wanted = false
            halt()
            publish()
        }
    }

    /** The next reconnect scan, backing off; after a while only the background connection waits on. */
    private fun scheduleNextScan() {
        if (nowMs() - reconnectStartedMs > STOP_SCANNING_AFTER_MS) {
            log("No more reconnect scans after ${STOP_SCANNING_AFTER_MS / 60_000} minutes; the background connection waits on")
            return
        }
        attempt++
        executor.schedule(BACKOFF_MS[minOf(backoffStep++, BACKOFF_MS.lastIndex)], startScan)
        publish()
    }

    private fun onAdapter(on: Boolean) {
        if (bluetoothOn != on) log(if (on) "Bluetooth turned on" else "Bluetooth turned off")
        bluetoothOn = on
        if (!on) {
            val keep = wanted
            halt()
            wanted = keep
            error = if (keep) LinkError.BluetoothOff else null
            publish()
        } else if (wanted && phase == Phase.None && !scanning && !reconnecting) {
            error = null
            if (piano != null) startReconnecting() else beginScan()
        } else if (!wanted && error == LinkError.BluetoothOff) {
            // Connect (or the app's start) found Bluetooth off: the error goes, and with auto-connect on the piano is looked for.
            error = null
            if (shouldReconnect()) startConnect(null) else publish()
        }
    }

    /** The person pressed Disconnect (or Cancel). */
    private fun stop() {
        log("Disconnect")
        wanted = false
        halt()
        error = null
        publish()
    }

    /** Stops with [reason] for the Piano tab; [other] is another piano offered, [detail] says more in the log. */
    private fun fail(reason: LinkError, other: String? = null, detail: String? = null) {
        log("Not connected: ${describe(reason)}" + (other?.let { ", the other piano is $it" } ?: "") + (detail?.let { " ($it)" } ?: ""))
        wanted = false
        halt()
        error = reason
        errorOther = other
        publish()
    }

    /** Stops scanning, timers and reconnection, and closes the connection. Leaves [wanted] alone. */
    private fun halt() {
        stopScanning()
        timers.forEach(executor::cancel)
        otherPiano = null
        candidate = null
        verifyName = false
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
        otaReady = false
        otaSubscribed = false
        otaQueue.clear()
        otaSession.get()?.deliver(OtaEvent.Lost("the connection to the piano ended"))
        readingVersion = false
        _firmwareVersion.value = null
        executor.cancel(writeTimeout)
        inFlight = false
        inFlightOp = null
        retryOp = null
        writeRetries = 0
        setWriting(false)
        guard { connection.close() }
    }

    // ---- Writing -------------------------------------------------------------------------

    /** One GATT operation: a MIDI packet, a piece of a console line, or switching on the console's replies. */
    private sealed interface Op {
        /** [mustArrive]: it lets a key or the pedal go (see [BleMidiFramer.mustArrive]). */
        class Midi(val packet: ByteArray, val mustArrive: Boolean) : Op

        class Console(val chunk: ByteArray, val endsLine: Boolean) : Op

        data object Subscribe : Op

        /** Control's CCCD, once a connection, before a session's first frame. */
        class OtaSubscribe(val session: OtaSession) : Op

        /** BEGIN, END or ABORT, written with response. */
        class OtaControl(val frame: ByteArray, val session: OtaSession, val name: String) : Op

        /** One Data frame, written without response. */
        class OtaData(val frame: ByteArray, val session: OtaSession) : Op

        /** An update frame: never dropped for a busy stack. */
        fun isOta(): Boolean = this is OtaSubscribe || this is OtaControl || this is OtaData
    }

    private fun pumpNow() {
        pumpQueued.set(false)
        executor.cancel(pump)
        val connection = gatt
        if (phase != Phase.Ready || inFlight || connection == null) return
        val now = executor.nanoTime()
        if (retryOp?.let(::staleOta) == true) retryOp = null
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
                is Op.OtaSubscribe -> connection.subscribeOta()
                is Op.OtaControl -> connection.writeOtaControl(op.frame)
                is Op.OtaData -> connection.writeOtaData(op.frame)
            }
        } ?: WriteResult.Failed
        when (result) {
            WriteResult.Sent -> {
                inFlight = true
                inFlightOp = op
                retryOp = null
                writeRetries = 0
                done(op)
                executor.schedule(WRITE_TIMEOUT_MS, writeTimeout)
            }
            WriteResult.Busy, WriteResult.Failed -> {
                retryOp = op
                if (op.isOta() && writeRetries >= MAX_OTA_WRITE_RETRIES) {
                    // Never dropped (a gap costs the whole transfer): a stack that keeps refusing ends the session.
                    log("The stack would not take the update's frames: the update session ends")
                    retryOp = null
                    writeRetries = 0
                    otaSession.get()?.deliver(OtaEvent.Lost("Bluetooth would not take the update's frames"))
                    executor.schedule(WRITE_RETRY_MS, pump)
                    return
                }
                if (++writeRetries > MAX_WRITE_RETRIES) {
                    if (op.isOta()) {
                        executor.schedule(WRITE_RETRY_SLOW_MS, pump)
                        return
                    }
                    if (op is Op.Midi && op.mustArrive) {
                        // Never given up while connected: the piano would hold a key or the pedal.
                        if (writeRetries == MAX_WRITE_RETRIES + 1) log("A packet that lets keys go waits on a busy stack")
                        executor.schedule(WRITE_RETRY_SLOW_MS, pump)
                        return
                    }
                    log(if (op is Op.Midi) "Dropped a packet the stack would not take" else "Dropped a console write the stack would not take")
                    retryOp = null
                    writeRetries = 0
                    done(op, dropped = true)
                    if (op is Op.Midi) lostPacket()
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
        writer.nextPacket(now, mtu, now / NANOS_PER_MS)?.let { return Op.Midi(it, BleMidiFramer.mustArrive(it)) }
        if (writer.pending > 0) return null
        nextOta()?.let { return it }
        val line = consoleQueue.peek() ?: return null
        val end = minOf(line.size, consoleOffset + (mtu - ATT_HEADER).coerceAtLeast(1))
        return Op.Console(line.copyOfRange(consoleOffset, end), endsLine = end == line.size)
    }

    /** An operation went out (or was given up on): move past it. A console line given up on is dropped whole. */
    private fun done(op: Op, dropped: Boolean = false) {
        when (op) {
            is Op.Midi -> Unit
            is Op.OtaSubscribe, is Op.OtaControl, is Op.OtaData -> otaQueue.remove(op)
            Op.Subscribe -> subscribePending = false
            is Op.Console -> if (op.endsLine || dropped) {
                consoleQueue.poll()
                consoleOffset = 0
            } else {
                consoleOffset += op.chunk.size
            }
        }
    }

    /**
     * A MIDI packet was given up on (Note Ons only: see [BleMidiFramer.mustArrive]), so the piano and
     * the player may disagree about what is down. The stop sequence replaces whatever waits, and a
     * new epoch asks the player to re-sync: silence, then the pedal where the music is.
     */
    private fun lostPacket() {
        writer.enqueue(silence, dropPending = true)
        epoch++
        publish()
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

    private fun onWritten(connection: GattConnection, success: Boolean = true) {
        if (connection !== gatt) return
        executor.cancel(writeTimeout)
        val op = inFlightOp
        inFlight = false
        inFlightOp = null
        if (!success && op is Op.OtaControl) {
            log("The piano did not take the update's ${op.name}")
            op.session.deliver(OtaEvent.Lost("the piano did not take the ${op.name} frame"))
        }
        pumpNow()
    }

    // ---- Firmware updates ----------------------------------------------------------------

    /**
     * One update session's answers, from BEGIN to OK, ABORTED, an ERR or the end of the connection.
     * [deliver] runs on the link's thread; [ended] once END is queued (ABORT is pointless after it).
     */
    private inner class OtaSession(private val out: SendChannel<OtaEvent>) {
        @Volatile
        var ended = false

        @Volatile
        var finished = false
            private set

        fun deliver(event: OtaEvent) {
            if (finished) return
            out.trySend(event)
            if (event.ends) finish()
        }

        fun finish() {
            if (finished) return
            finished = true
            otaSession.compareAndSet(this, null)
            otaQueue.removeAll { it is Op.OtaData && it.session === this }
            if ((retryOp as? Op.OtaData)?.session === this) retryOp = null
            out.close()
        }
    }

    private fun openSession(header: OtaBegin): Flow<OtaEvent> = callbackFlow {
        val session = OtaSession(channel)
        if (!otaSession.compareAndSet(null, session)) {
            trySend(OtaEvent.Lost("an update session is already under way"))
            close()
        } else {
            val frame = OtaFrames.begin(header)
            executor.execute { startSession(session, frame, header) }
        }
        awaitClose { executor.execute { leaveSession(session) } }
    }

    private fun startSession(session: OtaSession, frame: ByteArray, header: OtaBegin) {
        if (session.finished) return
        val connection = gatt
        if (phase != Phase.Ready || !otaReady || connection == null) return session.deliver(OtaEvent.Lost("not connected to the piano"))
        guard { connection.requestHighPriority() }
        otaQueue.removeAll { it is Op.OtaData }
        if (!otaSubscribed) otaQueue.add(Op.OtaSubscribe(session))
        otaQueue.add(Op.OtaControl(frame, session, "BEGIN"))
        log("Update: BEGIN for ${BleCodes.name(header.version)}, ${header.size} bytes, window ${header.window}, MTU $mtu")
        pumpNow()
    }

    /** The session's collector went away: before END, ABORT goes (the piano discards the slot). */
    private fun leaveSession(session: OtaSession) {
        if (!session.finished && !session.ended && otaSession.get() === session && otaReady) {
            log("Update: ABORT (the session was left)")
            otaQueue.add(Op.OtaControl(OtaFrames.abort(), session, "ABORT"))
            pumpNow()
        }
        session.finish()
    }

    /** From any thread: Data frame [seq], queued behind the session's frames before it. */
    private fun queueData(seq: Int, payload: ByteArray): Boolean {
        val session = otaSession.get() ?: return false
        if (session.finished || session.ended || payload.isEmpty() || payload.size > otaChunk) return false
        if (otaQueue.count { it is Op.OtaData } >= MAX_OTA_FRAMES_WAITING) return false
        otaQueue.add(Op.OtaData(OtaFrames.data(seq, payload), session))
        if (pumpQueued.compareAndSet(false, true)) executor.execute(pump)
        return true
    }

    /** From any thread: END, after every Data frame queued. */
    private fun queueEnd() {
        val session = otaSession.get() ?: return
        if (session.finished || session.ended) return
        session.ended = true
        otaQueue.add(Op.OtaControl(OtaFrames.end(), session, "END"))
        if (pumpQueued.compareAndSet(false, true)) executor.execute(pump)
    }

    /** ABORT, ahead of the Data frames still waiting, which are dropped. Nothing once END is out: the piano no longer hears it. */
    private fun abortSession() {
        val session = otaSession.get() ?: return
        if (session.finished || session.ended) return
        otaQueue.removeAll { it is Op.OtaData }
        if (retryOp is Op.OtaData) retryOp = null
        otaQueue.add(Op.OtaControl(OtaFrames.abort(), session, "ABORT"))
        log("Update: ABORT")
        pumpNow()
    }

    /** The next update frame of the current session; frames of a session that has ended are dropped here. */
    private fun nextOta(): Op? {
        while (true) {
            val op = otaQueue.peek() ?: return null
            if (staleOta(op) || (op is Op.OtaSubscribe && otaSubscribed)) {
                otaQueue.remove(op)
                continue
            }
            return op
        }
    }

    /** An update frame whose session has ended: never written (but an ABORT, which ends one, still goes). */
    private fun staleOta(op: Op): Boolean = when (op) {
        is Op.OtaSubscribe -> op.session.finished
        is Op.OtaControl -> op.session.finished && op.name != "ABORT"
        is Op.OtaData -> op.session.finished
        else -> false
    }

    private fun onOtaSubscribeDone(connection: GattConnection, success: Boolean) {
        if (connection !== gatt) return
        otaSubscribed = success
        if (!success) {
            log("The piano did not switch on its update replies")
            otaSession.get()?.deliver(OtaEvent.Lost("the piano's update replies could not be switched on"))
        }
        onWritten(connection)
    }

    private fun onOtaAnswer(connection: GattConnection, data: ByteArray) {
        if (connection !== gatt) return
        val event = OtaFrames.parse(data)
        if (event == null) {
            log("Update: an answer this app doesn't know (${OtaFrames.hex(data.copyOf(minOf(data.size, 8)))}) ignored")
            return
        }
        when (event) {
            is OtaEvent.Ack -> Unit   // one a window: not logged
            is OtaEvent.Error -> log("Update: ERR ${event.code} (${OtaFrames.errorName(event.code)})")
            else -> log("Update: $event")
        }
        otaSession.get()?.deliver(event)
    }

    private fun setWriting(value: Boolean) {
        writing = value
        if (!value) drainLock.withLock { drained.signalAll() }
    }

    // ---- Helpers -------------------------------------------------------------------------

    private fun publish() {
        _state.value = when {
            phase == Phase.Ready -> LinkState.Connected(piano?.name ?: PianoBluetooth.NAME, mtu, epoch)
            reconnecting -> LinkState.Reconnecting(attempt)
            scanning -> LinkState.Scanning
            phase != Phase.None -> LinkState.Connecting
            else -> error?.toState(errorOther) ?: LinkState.Disconnected
        }
    }

    private fun nowMs(): Long = executor.nanoTime() / NANOS_PER_MS

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

    /** [reason] for the log, with the GATT status or the scan's code spelled out. */
    private fun describe(reason: LinkError): String = when (reason) {
        is LinkError.ConnectFailed -> "ConnectFailed, " + (reason.status?.let { "status ${BleCodes.gattStatus(it)}" } ?: "timeout")
        is LinkError.ScanFailed -> "ScanFailed, code ${BleCodes.scanFailure(reason.code)}"
        is LinkError.NotFound -> "NotFound"
        else -> reason.toString()
    }

    /**
     * Runs a radio call, which never crashes the app. A revoked permission surfaces as SecurityException:
     * the link then stops with an error. Bluetooth turning off mid-call surfaces as IllegalStateException:
     * it is logged, and the adapter's broadcast that follows stops the link ("Bluetooth is off").
     */
    private inline fun <T> guard(block: () -> T): T? = try {
        block()
    } catch (e: SecurityException) {
        log("Bluetooth permission missing: ${e.message}")
        executor.execute { if (wanted || phase != Phase.None) fail(LinkError.PermissionMissing) }
        null
    } catch (e: IllegalStateException) {
        log("Bluetooth refused a call (${e.message}): it is probably turning off")
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

        /** [connectStatus] when connectGatt gave no connection at all (there is no GATT status to report). */
        private const val NO_CONNECTION = -1
        private const val RETRY_CONNECT_MS = 500L
        private const val BACKGROUND_RETRY_MS = 1_000L
        private const val MTU_TIMEOUT_MS = 3_000L
        private const val DISCOVERY_TIMEOUT_MS = 10_000L
        private const val WRITE_TIMEOUT_MS = 1_000L
        private const val WRITE_RETRY_MS = 5L
        private const val MAX_WRITE_RETRIES = 40
        private const val WRITE_RETRY_SLOW_MS = 20L
        private const val EMERGENCY_RETRY_MS = 5L

        /** How long a scan keeps looking for the known piano after another "Steven Piano" answered. */
        private const val OTHER_PIANO_GRACE_MS = 3_000L

        /** How long a nameless candidate waits for a named Steven Piano to answer before it is connected to. */
        private const val NAMELESS_GRACE_MS = 1_000L

        /** How long the candidate's GAP Device Name may take to read. */
        private const val NAME_TIMEOUT_MS = 3_000L

        /** How long the Firmware Revision String may take to read before the link goes on without it. */
        private const val VERSION_TIMEOUT_MS = 3_000L

        /** After an update's OK the piano is back in a few seconds: the first scan comes this soon. */
        const val RESTART_SCAN_AFTER_MS = 3_000L

        /** Data frames that may wait at once (a window is at most 32). */
        private const val MAX_OTA_FRAMES_WAITING = 64

        /** Retries of an update frame on a busy stack (40 quick, then every 20 ms) before the session ends: about 2 s. */
        private const val MAX_OTA_WRITE_RETRIES = 140

        /** Pedal up, then All Notes Off: the stop sequence, as packed messages. */
        private val STOP_SEQUENCE = intArrayOf(MidiBatch.pack(0xB0, 64, 0), MidiBatch.pack(0xB0, 123, 0))
        private const val RECONNECT_SCAN_AFTER_MS = 20_000L
        private val BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)

        /** After this long, reconnect scans stop to spare the battery; the background connection keeps waiting. */
        private const val STOP_SCANNING_AFTER_MS = 10 * 60_000L

        /** The real link: Android Bluetooth, on its own "steven-piano-ble" thread; its trail goes to the log and to [LinkLog]. */
        fun create(context: Context, onConnected: (String, String) -> Unit, shouldReconnect: () -> Boolean): GattPianoLink {
            val thread = HandlerThread("steven-piano-ble").apply { start() }
            return GattPianoLink(AndroidBleRadio(context.applicationContext), HandlerLinkExecutor(thread.looper), onConnected, shouldReconnect, LinkLog::warn)
        }
    }
}

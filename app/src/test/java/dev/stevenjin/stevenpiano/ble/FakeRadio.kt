// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

/** A [LinkExecutor] on virtual time: nothing runs until the test says so. */
class FakeLinkExecutor : LinkExecutor {
    var nowMs = 0L
        private set

    private class Task(val dueMs: Long, val order: Long, val action: Runnable)

    private val tasks = mutableListOf<Task>()
    private var counter = 0L

    override fun execute(action: Runnable) {
        tasks += Task(nowMs, counter++, action)
    }

    override fun schedule(delayMs: Long, action: Runnable) {
        cancel(action)
        tasks += Task(nowMs + delayMs, counter++, action)
    }

    override fun cancel(action: Runnable) {
        tasks.removeAll { it.action === action }
    }

    override fun nanoTime(): Long = nowMs * 1_000_000L

    /** Runs everything due now, including what that queues for now. */
    fun runDue() = advance(0)

    fun advance(ms: Long) {
        val end = nowMs + ms
        while (true) {
            val next = tasks.filter { it.dueMs <= end }.minWithOrNull(compareBy({ it.dueMs }, { it.order })) ?: break
            tasks.remove(next)
            nowMs = maxOf(nowMs, next.dueMs)
            next.action.run()
        }
        nowMs = end
    }
}

/** A radio the test drives: it records what the link asks for and fires callbacks on demand. */
class FakeRadio : BleRadio {
    var blocker: LinkError? = null
    var scanning = false
        private set
    var scans = 0
        private set
    val connections = mutableListOf<FakeGatt>()

    /** Location Services, as [locationServicesOn] reports them. */
    var locationServices = true

    /** Thrown by every scan, stop and connect call while set, as Android throws while Bluetooth turns off. */
    var failure: RuntimeException? = null
    private var onFound: ((FoundPiano) -> Unit)? = null
    private var onFailed: ((Int) -> Unit)? = null
    private var adapterListener: ((Boolean) -> Unit)? = null

    override fun blocker(): LinkError? = blocker

    override fun startScan(onFound: (FoundPiano) -> Unit, onFailed: (errorCode: Int) -> Unit) {
        failure?.let { throw it }
        scanning = true
        scans++
        this.onFound = onFound
        this.onFailed = onFailed
    }

    override fun stopScan() {
        failure?.let { throw it }
        scanning = false
    }

    override fun connect(address: String, autoConnect: Boolean, events: GattEvents): GattConnection {
        failure?.let { throw it }
        return FakeGatt(address, autoConnect, events).also { connections += it }
    }

    override fun watchAdapter(onChange: (on: Boolean) -> Unit) {
        adapterListener = onChange
    }

    override fun locationServicesOn(): Boolean = locationServices

    /** A scan result; [midi]: its advertisement carried the BLE-MIDI service (the piano's always does). */
    fun find(address: String, name: String?, rssi: Int = -60, midi: Boolean = true) =
        onFound?.invoke(FoundPiano(address, name, rssi, midi))

    /** Android gave up on the scan with [code]. */
    fun scanFailed(code: Int) = onFailed?.invoke(code)

    fun adapter(on: Boolean) = adapterListener?.invoke(on)
}

/**
 * One connection the test drives. With [hasConsole] the piano also has its console (Nordic UART):
 * the link's writes are logged in [ops] in the order they went out, and [consoleScript] answers
 * each whole line written to RX with notifications, [notifyChunk] bytes at a time (so lines arrive
 * split, as the firmware's MTU-sized notifications split them).
 */
class FakeGatt(override val address: String, val autoConnect: Boolean, private val events: GattEvents) : GattConnection {
    var requestedMtu = 0
    var discoveries = 0
    var highPriority = false
    var disconnected = false
    var closed = false
    var hasMidi = true
    var hasConsole = false
    var nextWrite = WriteResult.Sent
    var revoked = false
    val writes = mutableListOf<ByteArray>()
    val consoleWrites = mutableListOf<ByteArray>()
    val ops = mutableListOf<String>()
    var consoleScript: ((String) -> List<String>)? = null
    var notifyChunk = 20
    private val rx = StringBuilder()

    /** Every line written to the console so far, split on "\n". */
    val consoleLinesWritten: List<String>
        get() = consoleWrites.joinToString("") { String(it, Charsets.UTF_8) }.split('\n').dropLast(1)

    override fun requestMtu(mtu: Int): Boolean {
        requestedMtu = mtu
        return true
    }

    override fun discoverServices(): Boolean {
        discoveries++
        return true
    }

    override fun hasMidiCharacteristic(): Boolean = hasMidi

    override fun hasConsole(): Boolean = hasConsole

    override fun requestHighPriority(): Boolean {
        highPriority = true
        return true
    }

    override fun write(packet: ByteArray): WriteResult {
        if (revoked) throw SecurityException("BLUETOOTH_CONNECT revoked")
        if (nextWrite == WriteResult.Sent) {
            writes += packet
            ops += "midi"
        }
        return nextWrite
    }

    override fun subscribeConsole(): WriteResult {
        if (nextWrite == WriteResult.Sent) ops += "subscribe"
        return nextWrite
    }

    override fun writeConsole(chunk: ByteArray): WriteResult {
        if (nextWrite != WriteResult.Sent) return nextWrite
        consoleWrites += chunk
        ops += "console"
        rx.append(String(chunk, Charsets.UTF_8))
        while (true) {
            val end = rx.indexOf("\n")
            if (end < 0) break
            val line = rx.substring(0, end)
            rx.delete(0, end + 1)
            consoleScript?.invoke(line)?.let { replies -> notify(("> $line\n" + replies.joinToString("") { "$it\n" }).toByteArray()) }
        }
        return WriteResult.Sent
    }

    override fun disconnect() {
        disconnected = true
    }

    override fun close() {
        closed = true
    }

    fun connected() = events.onConnectionChanged(this, connected = true, status = 0)

    fun dropped(status: Int = 8) = events.onConnectionChanged(this, connected = false, status = status)

    fun mtu(value: Int) = events.onMtuChanged(this, value, success = true)

    fun discovered(success: Boolean = true) = events.onServicesDiscovered(this, success)

    fun writeDone() = events.onWriteDone(this, success = true)

    fun subscribeDone(success: Boolean = true) = events.onConsoleSubscribed(this, success)

    /** The piano notifies [bytes] on TX, [notifyChunk] bytes per notification. */
    fun notify(bytes: ByteArray) {
        var at = 0
        while (at < bytes.size) {
            val end = minOf(bytes.size, at + notifyChunk)
            events.onConsoleData(this, bytes.copyOfRange(at, end))
            at = end
        }
    }
}

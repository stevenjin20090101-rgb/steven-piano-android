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
    private var onFound: ((FoundPiano) -> Unit)? = null
    private var adapterListener: ((Boolean) -> Unit)? = null

    override fun blocker(): LinkError? = blocker

    override fun startScan(onFound: (FoundPiano) -> Unit, onFailed: (errorCode: Int) -> Unit) {
        scanning = true
        scans++
        this.onFound = onFound
    }

    override fun stopScan() {
        scanning = false
    }

    override fun connect(address: String, autoConnect: Boolean, events: GattEvents): GattConnection =
        FakeGatt(address, autoConnect, events).also { connections += it }

    override fun watchAdapter(onChange: (on: Boolean) -> Unit) {
        adapterListener = onChange
    }

    fun find(address: String, name: String?) = onFound?.invoke(FoundPiano(address, name))

    fun adapter(on: Boolean) = adapterListener?.invoke(on)
}

class FakeGatt(val address: String, val autoConnect: Boolean, private val events: GattEvents) : GattConnection {
    var requestedMtu = 0
    var discoveries = 0
    var highPriority = false
    var disconnected = false
    var closed = false
    var hasMidi = true
    var nextWrite = WriteResult.Sent
    var revoked = false
    val writes = mutableListOf<ByteArray>()

    override fun requestMtu(mtu: Int): Boolean {
        requestedMtu = mtu
        return true
    }

    override fun discoverServices(): Boolean {
        discoveries++
        return true
    }

    override fun hasMidiCharacteristic(): Boolean = hasMidi

    override fun requestHighPriority(): Boolean {
        highPriority = true
        return true
    }

    override fun write(packet: ByteArray): WriteResult {
        if (revoked) throw SecurityException("BLUETOOTH_CONNECT revoked")
        if (nextWrite == WriteResult.Sent) writes += packet
        return nextWrite
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
}

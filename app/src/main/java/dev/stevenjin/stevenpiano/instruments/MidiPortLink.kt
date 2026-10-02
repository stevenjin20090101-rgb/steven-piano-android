// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.instruments

import dev.stevenjin.stevenpiano.ble.BleCodes
import dev.stevenjin.stevenpiano.ble.ConsoleChannel
import dev.stevenjin.stevenpiano.ble.LinkError
import dev.stevenjin.stevenpiano.ble.LinkExecutor
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.ble.PacedWriter
import dev.stevenjin.stevenpiano.ble.PianoLink
import dev.stevenjin.stevenpiano.midi.MidiBatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Any MIDI piano as the app's output (v1.11 — M29): a [PianoLink] over Android's MIDI service, so the player,
 * the Keys screen and the tablet's sound treat it as they treat Steven Piano. It sends to the device's input
 * port 0 (another app holding it is [LinkError.InstrumentBusy]) whole three-byte messages, never running
 * status, at the piano link's pace: its own [PacedWriter] (a burst of 20, then a message a millisecond: a DIN
 * MIDI cable's own rate, which every digital piano takes), the live lane in front, on its own thread at audio
 * priority ("steven-piano-midi-out"), because a port's send may block.
 *
 * The device is the one [choose] names, remembered by what identifies it (a Bluetooth address; a USB
 * manufacturer, product and serial). Unplugged or out of range, the link is Reconnecting: a USB piano comes
 * back when Android lists it again, a Bluetooth one is opened again with the piano link's backoff. A Bluetooth
 * piano that fails to open while not paired is asked to pair once ([LinkError.InstrumentPairing]); Steven Piano
 * is never opened here, nor paired with. Every new connection is a new [LinkState.Connected.epoch], so the
 * player sends its stop sequence first. The keys sent and not let go are tracked: [disconnect] and
 * [emergencySilence] send a Note Off for each, every pedal at 0, All Sound Off and All Notes Off.
 * State on [executor]'s thread ("steven-piano-midi").
 */
class MidiPortLink(
    private val devices: MidiDevices,
    private val bluetooth: MidiBluetooth,
    private val executor: LinkExecutor,
    private val log: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    /** Starts the writer's thread; tests run it by hand ([drain]). */
    startThread: (Runnable) -> Unit = { body -> Thread(body, "steven-piano-midi-out").apply { isDaemon = true; start() } },
) : PianoLink {
    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    /** No console, no firmware: that is Steven Piano's. */
    override val console: ConsoleChannel? = null

    private val writer = PacedWriter()

    @Volatile
    private var out: MidiOut? = null

    /** Keys 0-63 and 64-127 a Note On went to and no Note Off yet. */
    private val sentLow = AtomicLong()
    private val sentHigh = AtomicLong()

    private val lock = ReentrantLock()
    private val work = lock.newCondition()
    private val drained = lock.newCondition()

    @Volatile
    private var writing = false

    /** Something was queued since the writer last looked (under [lock]): it never sleeps through a wake-up. */
    private var signalled = false
    private val scratch = IntArray(BURST)
    private val bytes = ByteArray(BURST * 3)

    // Owned by the executor's thread.
    private var chosen: MidiChoice? = null
    private var wanted = false
    private var lease: MidiDevices.Lease? = null
    private var epoch = 0L
    private var pairAsked = false
    private var attempt = 0
    private var lostAt = 0L
    private val retry = Runnable { open() }

    init {
        devices.onDeviceAdded { device -> if (device.key == chosen?.key && wanted && lease == null) open() }
        bluetooth.watchBonds { address -> executor.execute { if (address.equals(chosen?.address, ignoreCase = true) && wanted && lease == null) open() } }
        startThread(Runnable { pump() })
    }

    /**
     * The MIDI piano to play on from now on (null: none); the link reconnects to it on the next [connect]. Steven Piano
     * is never one: such a choice is refused (its address never claimed, which would keep the piano's own link from
     * it). Any thread.
     */
    fun choose(choice: MidiChoice?) {
        if (choice != null && devices.isPiano(choice.address, choice.name)) {
            log("MIDI piano: ${BleCodes.name(choice.name)} is Steven Piano, which plays through its own link: not chosen")
            return
        }
        devices.claim(choice?.address)
        executor.execute {
            if (chosen?.key == choice?.key) {
                devices.unclaim(choice?.address)   // claimed twice
                return@execute
            }
            stop("another instrument was chosen")
            chosen?.let { devices.unclaim(it.address) }
            chosen = choice
            pairAsked = false
        }
    }

    /** The chosen MIDI piano, as it was chosen. */
    val choice: MidiChoice? get() = chosen

    /** Connects to the chosen MIDI piano ([address] is Steven Piano's and is ignored here). */
    override fun connect(address: String?) = executor.execute {
        wanted = true
        attempt = 0
        if (lease == null) open()
    }

    /** Lets go of the keys and pedals it sent, then closes the device; no reconnecting. */
    override fun disconnect() = executor.execute {
        if (lease != null || _state.value !is LinkState.Disconnected) log("MIDI piano: disconnect")
        stop("disconnect")
        _state.value = LinkState.Disconnected
    }

    override fun send(batch: MidiBatch, dropPending: Boolean) {
        if (out == null) return
        writer.enqueue(batch, dropPending)
        wake()
    }

    override fun sendLive(batch: MidiBatch) {
        if (out == null) return
        writer.enqueueLive(batch)
        wake()
    }

    /** Returns once everything queued is written, the device is gone, or [timeoutMs] passes (false). */
    override fun flush(timeoutMs: Long): Boolean {
        var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        lock.withLock {
            while (out != null && (writer.pending > 0 || writing)) {
                if (nanos <= 0) return false
                nanos = drained.awaitNanos(nanos)
            }
        }
        return true
    }

    /**
     * The crash handler's stop, straight to the port from whatever thread: a Note Off for every key sent and
     * not let go, every pedal at 0, All Sound Off, All Notes Off. True when it was written.
     */
    override fun emergencySilence(timeoutMs: Long): Boolean {
        val port = out ?: return false
        return try {
            val stop = stopBytes()
            port.send(stop, 0, stop.size)
            true
        } catch (e: IOException) {
            false
        } catch (e: RuntimeException) {
            false
        }
    }

    // ---- Connecting ------------------------------------------------------------------------

    private fun open() {
        executor.cancel(retry)
        val target = chosen
        if (!wanted || lease != null) return
        if (target == null) return fail(LinkError.InstrumentGone("The MIDI piano"))
        if (!devices.available) return fail(LinkError.NoMidi)
        if (devices.isPiano(target.address, target.name)) {
            log("MIDI piano: ${BleCodes.name(target.name)} is Steven Piano, which plays through its own link: not opened")
            return fail(LinkError.InstrumentGone(target.name))
        }
        val listed = devices.listed(target.key)
        val choice = when {
            listed != null -> MidiChoice.of(listed)
            target.transport == MidiTransport.BLUETOOTH && target.address != null -> target
            else -> {
                // A USB piano not plugged in: said so, and connected the moment Android lists it.
                _state.value = LinkError.InstrumentGone(target.name).toState()
                return
            }
        }
        _state.value = if (attempt > 0) LinkState.Reconnecting(attempt) else LinkState.Connecting
        lease = devices.open(choice, onOpened = { opened(it, target) }, onLost = { lost(target) })
    }

    private fun opened(device: OpenMidiDevice?, target: MidiChoice) {
        if (device == null) {
            lease?.close()
            lease = null
            val address = target.address
            if (target.transport == MidiTransport.BLUETOOTH && address != null && !pairAsked && !devices.isPiano(address, target.name) && !bluetooth.isBonded(address)) {
                pairAsked = true
                val asked = runCatching { bluetooth.createBond(address) }.getOrDefault(false)
                log("MIDI piano: not paired; " + if (asked) "Android asks the person to pair" else "pairing could not be asked for")
                _state.value = LinkError.InstrumentPairing(target.name).toState()   // still wanted: once paired, it is opened again
                return
            }
            return waitFor(target)
        }
        val port = device.sender(0)
        if (port == null) {
            log("MIDI piano: ${BleCodes.name(device.device.name)}'s input is held by another app")
            lease?.close()
            lease = null
            return fail(LinkError.InstrumentBusy(target.name))
        }
        writer.clear()
        sentLow.set(0L)
        sentHigh.set(0L)
        out = port
        attempt = 0
        epoch++
        log("MIDI piano: ${BleCodes.name(device.device.name)} connected (${device.device.transport.label})")
        _state.value = LinkState.Connected(target.name, 0, epoch)
    }

    private fun lost(target: MidiChoice) {
        log("MIDI piano: ${BleCodes.name(target.name)} lost")
        closePort()
        lease = null
        lostAt = nanoTime()
        waitFor(target)
    }

    /** Not here now: a USB piano waits for Android to list it again; a Bluetooth one is tried again with backoff. */
    private fun waitFor(target: MidiChoice) {
        if (!wanted) return
        attempt++
        _state.value = if (target.transport == MidiTransport.BLUETOOTH) LinkState.Reconnecting(attempt) else LinkError.InstrumentGone(target.name).toState()
        if (target.transport != MidiTransport.BLUETOOTH) return
        val wait = if (attempt - 1 < BACKOFF_MS.size) BACKOFF_MS[attempt - 1] else if (nanoTime() - lostAt > LONG_RETRY_AFTER_NANOS) LONG_RETRY_MS else BACKOFF_MS.last()
        executor.schedule(wait, retry)
    }

    private fun fail(error: LinkError) {
        wanted = false
        executor.cancel(retry)
        _state.value = error.toState()
    }

    /**
     * The keys and pedals it sent let go, the port and the device closed; on the executor's thread. The writer is
     * stopped first (under the lock it takes messages under) and a batch already on its way finishes before the
     * stop goes, so nothing it sent lands after the stop.
     */
    private fun stop(reason: String) {
        wanted = false
        executor.cancel(retry)
        val port = lock.withLock {
            val port = out
            out = null
            writer.clear()
            var nanos = IN_FLIGHT_NANOS
            while (writing && nanos > 0) nanos = drained.awaitNanos(nanos)
            port
        }
        if (port != null) {
            try {
                val stop = stopBytes()
                port.send(stop, 0, stop.size)
            } catch (e: IOException) {
                // gone already
            } catch (e: RuntimeException) {
                // gone already
            }
            runCatching { port.close() }
            lock.withLock { drained.signalAll() }
        }
        lease?.close()
        lease = null
        attempt = 0
        if (reason != "disconnect") _state.value = LinkState.Disconnected
    }

    /** The device went: the port is dropped without a word to it. */
    private fun closePort() {
        val port = lock.withLock {
            val port = out
            out = null
            writer.clear()
            drained.signalAll()
            port
        } ?: return
        runCatching { port.close() }
    }

    // ---- Writing ---------------------------------------------------------------------------

    private fun wake() = lock.withLock {
        signalled = true
        work.signal()
    }

    /** The writer's thread: messages as they may go, whole, to the port. */
    private fun pump() {
        while (true) {
            val wait = drain()
            lock.withLock {
                if (wait > 0 && !signalled) work.awaitNanos(wait)
                signalled = false
            }
        }
    }

    /**
     * Writes what may go now; returns how long to wait before the next (nanoseconds; 0: look again at once).
     * Messages are taken, and marked in flight, under [lock], the lock [stop] closes the port under. Visible for tests.
     */
    internal fun drain(): Long {
        val port: MidiOut
        val n: Int
        lock.withLock {
            port = out ?: return IDLE_NANOS
            val now = nanoTime()
            n = writer.nextMessages(now, scratch, BURST)
            if (n == 0) {
                drained.signalAll()
                val ready = writer.nanosUntilReady(now)
                return if (ready == Long.MAX_VALUE) IDLE_NANOS else ready.coerceAtLeast(1L)
            }
            writing = true
        }
        for (i in 0 until n) {
            val m = scratch[i]
            bytes[i * 3] = (m ushr 16).toByte()
            bytes[i * 3 + 1] = (m ushr 8).toByte()
            bytes[i * 3 + 2] = m.toByte()
            track(m)
        }
        try {
            port.send(bytes, 0, n * 3)
        } catch (e: IOException) {
            executor.execute { chosen?.let { if (out === port) lost(it) } }
        } catch (e: RuntimeException) {
            executor.execute { chosen?.let { if (out === port) lost(it) } }
        } finally {
            lock.withLock {
                writing = false
                drained.signalAll()
            }
        }
        return 0L
    }

    /** Keeps [sentLow] / [sentHigh] in step with what went. */
    private fun track(message: Int) {
        val status = (message ushr 16) and 0xF0
        val key = (message ushr 8) and 0x7F
        val value = message and 0x7F
        when {
            status == 0x90 && value > 0 -> setSent(key, true)
            status == 0x80 || status == 0x90 -> setSent(key, false)
            status == 0xB0 && (key == ALL_SOUND_OFF || key == ALL_NOTES_OFF) -> {
                sentLow.set(0L)
                sentHigh.set(0L)
            }
        }
    }

    private fun setSent(key: Int, on: Boolean) {
        val word = if (key < 64) sentLow else sentHigh
        val bit = 1L shl (key % 64)
        while (true) {
            val was = word.get()
            val now = if (on) was or bit else was and bit.inv()
            if (word.compareAndSet(was, now)) return
        }
    }

    /** A Note Off for every key sent and not let go, every pedal at 0, All Sound Off, All Notes Off. */
    private fun stopBytes(): ByteArray {
        val keys = ArrayList<Int>()
        for (key in 0..127) if ((if (key < 64) sentLow.get() else sentHigh.get()) and (1L shl (key % 64)) != 0L) keys += key
        val stop = ByteArray((keys.size + PEDALS.size + 2) * 3)
        var at = 0
        fun put(status: Int, data1: Int, data2: Int) {
            stop[at++] = status.toByte()
            stop[at++] = data1.toByte()
            stop[at++] = data2.toByte()
        }
        for (key in keys) put(0x80, key, 0)
        for (pedal in PEDALS) put(0xB0, pedal, 0)
        put(0xB0, ALL_SOUND_OFF, 0)
        put(0xB0, ALL_NOTES_OFF, 0)
        sentLow.set(0L)
        sentHigh.set(0L)
        return stop
    }

    companion object {
        private const val BURST = 20
        private const val ALL_SOUND_OFF = 120
        private const val ALL_NOTES_OFF = 123
        private val PEDALS = intArrayOf(64, 66, 67)
        private const val IDLE_NANOS = 50_000_000L

        /** How long a stop waits for a batch already on its way to the port. */
        private const val IN_FLIGHT_NANOS = 100_000_000L
        private val BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
        private const val LONG_RETRY_AFTER_NANOS = 600_000_000_000L
        private const val LONG_RETRY_MS = 60_000L
    }
}

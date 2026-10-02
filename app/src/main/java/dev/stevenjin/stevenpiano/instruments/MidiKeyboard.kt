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
import dev.stevenjin.stevenpiano.ble.LinkExecutor
import dev.stevenjin.stevenpiano.midi.KeyEvents
import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.midi.KeyboardHolds
import dev.stevenjin.stevenpiano.midi.MidiStreamParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** The keyboard as the Piano tab and the Keys tab show it (v1.11 — M29). */
data class KeyboardState(val chosen: Chosen? = null, val phase: Phase = Phase.None) {
    /** The keyboard the person chose: what is remembered of it. */
    data class Chosen(val key: String, val name: String, val transport: MidiTransport) {
        val address: String? get() = MidiNames.addressOf(key)

        companion object {
            /** The keyboard the settings remember ([id], [name]), or null for none or one this version can't read. */
            fun saved(id: String?, name: String?): Chosen? {
                val transport = MidiNames.transportOf(id) ?: return null
                return Chosen(id ?: return null, MidiNames.clean(name).ifEmpty { transport.label + " keyboard" }, transport)
            }
        }
    }

    enum class Phase {
        /** None chosen. */
        None,

        /** Chosen, not here (unplugged, switched off, out of range). */
        NotConnected,
        Connecting,
        Connected,

        /** A Bluetooth keyboard that asks to pair: the person accepts Android's request, or pairs it in Bluetooth settings. */
        NeedsPairing,

        /** This tablet has no MIDI (Android's MIDI service is missing). */
        Unavailable,
    }

    val connected: Boolean get() = phase == Phase.Connected
}

/** A keyboard's events as they come (v1.11 — M29): the gate to the piano, the recorder. */
interface KeyboardListener {
    /**
     * One buffer's events, in order, on the port's own thread under the keyboard's lock: [arrivalNanos] when
     * it came (`System.nanoTime`), [stampNanos] the sender's own stamp on the same clock. Copy what is kept.
     */
    fun onKeys(events: KeyEvents, arrivalNanos: Long, stampNanos: Long)

    /** Everything the keyboard held was let go ([reason] for the log): whatever was passed on from it must go too. */
    fun onLetGo(reason: String) {}

    /** [count] malformed bytes came in one buffer (the flood breaker counts them). */
    fun onMalformed(count: Long) {}
}

/**
 * The MIDI keyboard (v1.11 — M29): the one the person chose, connected and reconnected; its bytes parsed
 * ([MidiStreamParser], one per output port, every port heard) into what it holds ([KeyboardHolds]); what
 * it holds for the Keys tab to draw ([isHeld], folded into the screen's 84 keys, with [changes] bumped);
 * and its events fanned out to [KeyboardListener]s, as they come, on the port's thread: no main-thread hop,
 * nothing allocated per buffer.
 *
 * Everything it holds is let go ([KeyboardListener.onLetGo]) when the device goes (unplugged, out of range),
 * when it was sending Active Sensing and stops for [SENSING_GAP_NANOS], when keys or a pedal stay down with
 * no byte for [SILENT_HOLD_NANOS], when it is forgotten or another is chosen, and when [letGo] is asked.
 * A USB keyboard comes back when Android lists it again; a Bluetooth one is opened again with the piano
 * link's backoff (1, 2, 4, 8, 15 s, then every minute after ten minutes). A Bluetooth keyboard that fails to
 * open, or goes within [PAIRING_WINDOW_NANOS] of opening, while not paired, is asked to pair once
 * ([KeyboardState.Phase.NeedsPairing]); once paired it is opened again.
 *
 * State and timers on [executor]'s thread ("steven-piano-midi"); [remember] saves the choice.
 */
class MidiKeyboard(
    private val devices: MidiDevices,
    private val bluetooth: MidiBluetooth,
    private val executor: LinkExecutor,
    private val log: (String) -> Unit,
    private val remember: (KeyboardState.Chosen?) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val _state = MutableStateFlow(KeyboardState())
    val state: StateFlow<KeyboardState> = _state.asStateFlow()

    private val heldLow = AtomicLong()
    private val heldHigh = AtomicLong()

    /** Bumped whenever what the keyboard holds changes: the Keys tab redraws when it moves. */
    val changes = AtomicInteger()

    /** Whether the keyboard holds a key that sounds as [key] on the screen's keyboard (24-107; others fold in by octaves). */
    fun isHeld(key: Int): Boolean {
        val bit = key - KeyMap.LOWEST
        return when (bit) {
            in 0 until 64 -> heldLow.get() and (1L shl bit) != 0L
            in 64 until KeyMap.KEY_COUNT -> heldHigh.get() and (1L shl (bit - 64)) != 0L
            else -> false
        }
    }

    private val listeners = CopyOnWriteArrayList<KeyboardListener>()

    /** [listener] hears the keyboard from now on, until the handle is closed. */
    fun listen(listener: KeyboardListener): Closeable {
        listeners += listener
        return Closeable { listeners -= listener }
    }

    // The receiving side: one lock, held by a port's thread for each buffer and by the executor's for its checks.
    private val lock = Any()
    private val events = KeyEvents()
    private val holds = KeyboardHolds(events)
    private val sink = object : MidiStreamParser.Events by holds {
        override fun activeSensing() {
            sensingSeen = true
            sensingAt = nanoTime()
        }
    }
    private var parsers: Array<MidiStreamParser> = emptyArray()
    private var hearing = false
    private var lastBytesAt = 0L
    private var sensingSeen = false
    private var sensingAt = 0L
    private var malformedPending = 0L

    // Owned by the executor's thread.
    private var chosen: KeyboardState.Chosen? = null
    private var lease: MidiDevices.Lease? = null
    private var heard: List<Closeable> = emptyList()
    private var openedAt = 0L
    private var pairAsked = false
    private var retries = 0
    private var lostAt = 0L
    private var malformedLoggedAt = -MALFORMED_LOG_NANOS
    private val retry = Runnable { connect() }
    private val watchdog = Runnable { check() }

    init {
        devices.onDeviceAdded { device -> if (device.key == chosen?.key && needsConnecting()) connect() }
        bluetooth.watchBonds { address -> executor.execute { onBonded(address) } }
    }

    /** At start: the keyboard remembered, if any. Its address is foreign to the piano's link at once. */
    fun restore(saved: KeyboardState.Chosen?) {
        devices.claim(saved?.address)
        executor.execute {
            if (saved == null || chosen != null) {
                if (saved != null) devices.unclaim(saved.address)
                return@execute
            }
            chosen = saved
            connect()
        }
    }

    /**
     * The person chose [choice] in the picker: the keyboard before it lets go, this one is remembered and
     * connected. Steven Piano is never a keyboard (the picker never offers it; this refuses it all the same).
     */
    fun choose(choice: MidiChoice) {
        if (devices.isPiano(choice.address, choice.name)) {
            log("Keyboard: ${BleCodes.name(choice.name)} is Steven Piano, never a keyboard: not chosen")
            return
        }
        val picked = KeyboardState.Chosen(choice.key, MidiNames.clean(choice.name), choice.transport)
        devices.claim(picked.address)
        executor.execute {
            disconnect("another keyboard was chosen")
            chosen?.let { devices.unclaim(it.address) }
            chosen = picked
            pairAsked = false
            remember(picked)
            log("Keyboard: ${BleCodes.name(picked.name)} chosen (${picked.transport.label})")
            connect(choice)
        }
    }

    /** Forget: everything it holds lets go, and no keyboard is remembered. */
    fun forget() = executor.execute {
        val was = chosen ?: return@execute
        disconnect("forgotten")
        devices.unclaim(was.address)
        chosen = null
        remember(null)
        log("Keyboard: forgotten")
        publish(KeyboardState.Phase.None)
    }

    /** Try again now (the Keyboard page's state, or a person who just plugged it in). */
    fun reconnect() = executor.execute {
        if (needsConnecting()) {
            retries = 0
            connect()
        }
    }

    /** Lets go of everything the keyboard holds now ([reason] for the log); it stays connected. Any thread. */
    fun letGo(reason: String) {
        synchronized(lock) { releaseLocked(reason) }
    }

    // ---- Connecting ------------------------------------------------------------------------

    private fun needsConnecting(): Boolean = chosen != null && lease == null

    private fun connect(choice: MidiChoice? = null) {
        executor.cancel(retry)
        val keyboard = chosen ?: return
        if (lease != null) return
        if (devices.isPiano(keyboard.address, keyboard.name)) return forgetPiano(keyboard)
        if (!devices.available) return publish(KeyboardState.Phase.Unavailable)
        val listed = devices.listed(keyboard.key)
        val target = when {
            listed != null -> MidiChoice.of(listed)
            choice != null -> choice
            keyboard.transport == MidiTransport.BLUETOOTH && keyboard.address != null ->
                MidiChoice(keyboard.key, keyboard.name, MidiTransport.BLUETOOTH, keyboard.address)
            else -> return publish(KeyboardState.Phase.NotConnected)   // USB: until Android lists it again
        }
        publish(KeyboardState.Phase.Connecting)
        lease = devices.open(target, onOpened = ::opened, onLost = ::lost)
    }

    private fun opened(device: OpenMidiDevice?) {
        val keyboard = chosen ?: return
        if (device == null) return failed(keyboard)
        val ports = device.device.outputs.coerceIn(0, MAX_PORTS)
        synchronized(lock) {
            parsers = Array(ports) { MidiStreamParser(sink) }
            hearing = true
            lastBytesAt = nanoTime()
            sensingSeen = false
        }
        heard = (0 until ports).mapNotNull { port -> device.receive(port) { data, offset, count, stamp -> onBytes(port, data, offset, count, stamp) } }
        if (heard.isEmpty()) {
            log("Keyboard: ${BleCodes.name(keyboard.name)} has no port to hear")
            disconnect("no port")
            return publish(KeyboardState.Phase.NotConnected)
        }
        openedAt = nanoTime()
        retries = 0
        log("Keyboard: ${BleCodes.name(keyboard.name)} connected (${keyboard.transport.label}, ${heard.size} port" + (if (heard.size == 1) ")" else "s)"))
        publish(KeyboardState.Phase.Connected)
        executor.schedule(WATCHDOG_MS, watchdog)
    }

    /** The open failed: a Bluetooth keyboard not paired is asked to pair once; otherwise it is tried again later. */
    private fun failed(keyboard: KeyboardState.Chosen) {
        lease?.close()
        lease = null
        if (devices.isPiano(keyboard.address, keyboard.name)) return forgetPiano(keyboard)
        val address = keyboard.address
        if (keyboard.transport == MidiTransport.BLUETOOTH && address != null && askToPair(address)) return
        publish(KeyboardState.Phase.NotConnected)
        scheduleRetry(keyboard)
    }

    /** The device went away. Everything it held lets go first. */
    private fun lost() {
        val keyboard = chosen ?: return
        val soon = nanoTime() - openedAt < PAIRING_WINDOW_NANOS
        disconnect("the keyboard went away")
        log("Keyboard: ${BleCodes.name(keyboard.name)} lost")
        lostAt = nanoTime()
        val address = keyboard.address
        if (keyboard.transport == MidiTransport.BLUETOOTH && address != null && soon && askToPair(address)) return
        publish(KeyboardState.Phase.NotConnected)
        scheduleRetry(keyboard)
    }

    /**
     * Asks Android to pair with [address] once a choice, unless it is paired already, or is (or may be) Steven
     * Piano, which must never be paired with: true when it now waits for the person.
     */
    private fun askToPair(address: String): Boolean {
        if (pairAsked || devices.isPiano(address, chosen?.name) || bluetooth.isBonded(address)) return false
        pairAsked = true
        val asked = runCatching { bluetooth.createBond(address) }.getOrDefault(false)
        log("Keyboard: not paired; " + if (asked) "Android asks the person to pair" else "pairing could not be asked for")
        publish(KeyboardState.Phase.NeedsPairing)
        return true
    }

    private fun onBonded(address: String) {
        val keyboard = chosen ?: return
        if (!address.equals(keyboard.address, ignoreCase = true)) return
        log("Keyboard: paired")
        if (needsConnecting()) {
            retries = 0
            connect()
        }
    }

    /**
     * The keyboard turned out to be Steven Piano (named so once open, or now the remembered piano's address): it is
     * forgotten, never opened again through Android's MIDI service nor paired with, which would take the piano from
     * its own link.
     */
    private fun forgetPiano(keyboard: KeyboardState.Chosen) {
        log("Keyboard: ${BleCodes.name(keyboard.name)} is Steven Piano, which plays through its own link: forgotten as a keyboard")
        disconnect("it is Steven Piano")
        devices.unclaim(keyboard.address)
        chosen = null
        remember(null)
        publish(KeyboardState.Phase.None)
    }

    /** Bluetooth: opened again with the piano link's backoff. USB waits for Android to list it again. */
    private fun scheduleRetry(keyboard: KeyboardState.Chosen) {
        if (keyboard.transport != MidiTransport.BLUETOOTH) return
        val wait = if (retries < BACKOFF_MS.size) BACKOFF_MS[retries] else if (nanoTime() - lostAt > LONG_RETRY_AFTER_NANOS) LONG_RETRY_MS else BACKOFF_MS.last()
        retries++
        executor.schedule(wait, retry)
    }

    /** Lets go of everything held and closes the device; the keyboard stays chosen. */
    private fun disconnect(reason: String) {
        executor.cancel(retry)
        executor.cancel(watchdog)
        for (handle in heard) runCatching { handle.close() }
        heard = emptyList()
        synchronized(lock) {
            releaseLocked(reason)
            hearing = false
            parsers = emptyArray()
        }
        lease?.close()
        lease = null
    }

    // ---- Receiving -------------------------------------------------------------------------

    /** One buffer from output port [port], on its own thread. */
    private fun onBytes(port: Int, data: ByteArray, offset: Int, count: Int, stamp: Long) {
        val now = nanoTime()
        synchronized(lock) {
            if (!hearing || port !in parsers.indices) return
            lastBytesAt = now
            events.clear()
            val parser = parsers[port]
            val before = parser.malformed
            parser.feed(data, offset, count)
            val malformed = parser.malformed - before
            if (malformed > 0) {
                malformedPending += malformed
                for (listener in listeners) listener.onMalformed(malformed)
            }
            if (events.isEmpty()) return
            publishHeld()
            for (listener in listeners) listener.onKeys(events, now, stamp)
        }
    }

    /** Every key and pedal up, told to the listeners; under [lock]. */
    private fun releaseLocked(reason: String) {
        events.clear()
        holds.releaseAll()
        if (!events.isEmpty()) {
            log("Keyboard: let go of everything it held ($reason)")
            publishHeld()
            val now = nanoTime()
            for (listener in listeners) listener.onKeys(events, now, now)
        }
        for (listener in listeners) listener.onLetGo(reason)
        events.clear()
    }

    /** The screen's view of what is held: every key down, folded into 24-107. Under [lock]. */
    private fun publishHeld() {
        var low = 0L
        var high = 0L
        for (key in 0..127) {
            if (!holds.isDown(key)) continue
            val bit = KeyMap.map(key, 0, true) - KeyMap.LOWEST
            if (bit < 64) low = low or (1L shl bit) else high = high or (1L shl (bit - 64))
        }
        if (low != heldLow.get() || high != heldHigh.get()) {
            heldLow.set(low)
            heldHigh.set(high)
            changes.incrementAndGet()
        }
    }

    /** Every quarter second while connected: a keyboard gone silent lets go; malformed bytes are logged now and then. */
    private fun check() {
        if (lease == null) return
        val now = nanoTime()
        var pending = 0L
        synchronized(lock) {
            val sensingStopped = sensingSeen && now - sensingAt > SENSING_GAP_NANOS
            val silentHold = holds.anyDown && now - lastBytesAt > SILENT_HOLD_NANOS
            if (sensingStopped) {
                sensingSeen = false
                releaseLocked("its Active Sensing stopped")
            } else if (silentHold) {
                releaseLocked("keys or a pedal down with no byte for ${SILENT_HOLD_NANOS / 1_000_000_000} s")
            }
            if (malformedPending > 0 && now - malformedLoggedAt > MALFORMED_LOG_NANOS) {
                pending = malformedPending
                malformedPending = 0
                malformedLoggedAt = now
            }
        }
        if (pending > 0) log("Keyboard: $pending malformed byte" + (if (pending == 1L) "" else "s") + " dropped")
        executor.schedule(WATCHDOG_MS, watchdog)
    }

    private fun publish(phase: KeyboardState.Phase) {
        _state.value = KeyboardState(chosen, if (chosen == null) KeyboardState.Phase.None else phase)
    }

    companion object {
        /** A keyboard that sent Active Sensing and stops for this long has gone: everything it held lets go. */
        const val SENSING_GAP_NANOS = 1_000_000_000L

        /** Keys or a pedal down with no byte at all for this long: let go. */
        const val SILENT_HOLD_NANOS = 60_000_000_000L

        /** A Bluetooth keyboard gone this soon after opening, while not paired, is asked to pair. */
        const val PAIRING_WINDOW_NANOS = 5_000_000_000L

        /** The ports heard at most. */
        const val MAX_PORTS = 16

        private const val WATCHDOG_MS = 250L
        private const val MALFORMED_LOG_NANOS = 10_000_000_000L
        private val BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
        private const val LONG_RETRY_AFTER_NANOS = 600_000_000_000L
        private const val LONG_RETRY_MS = 60_000L
    }
}

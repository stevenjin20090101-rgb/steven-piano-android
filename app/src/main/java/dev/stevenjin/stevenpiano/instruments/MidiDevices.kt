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
import dev.stevenjin.stevenpiano.ble.FoundPiano
import dev.stevenjin.stevenpiano.ble.LinkError
import dev.stevenjin.stevenpiano.ble.LinkExecutor
import dev.stevenjin.stevenpiano.ble.PianoBluetooth
import dev.stevenjin.stevenpiano.ble.PianoScanner
import dev.stevenjin.stevenpiano.ble.ScanThrottle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** The picker's search for Bluetooth MIDI devices: where it is, what it found, and why it can't look. */
data class MidiScan(
    val phase: Phase = Phase.Idle,
    val found: List<FoundMidi> = emptyList(),
    /** While [Phase.Waiting]: how long until Android allows the next search. */
    val waitMs: Long = 0L,
    /** While [Phase.Blocked]: why (Bluetooth off, a permission missing, no Bluetooth). */
    val problem: LinkError? = null,
) {
    enum class Phase { Idle, Looking, Waiting, Done, Blocked }
}

/** A Bluetooth MIDI device the picker's search found, with the name it advertised (cleaned). */
data class FoundMidi(val address: String, val name: String)

/**
 * A device to open, as the picker offers it (v1.11 — M29): one Android's MIDI service lists ([listed]), or a
 * Bluetooth device a search found (by its [address]), which Android's Bluetooth MIDI service opens.
 */
data class MidiChoice(val key: String, val name: String, val transport: MidiTransport, val address: String? = null, val listed: MidiDeviceRef? = null) {
    companion object {
        fun of(device: MidiDeviceRef) = MidiChoice(device.key, device.name, device.transport, device.address, device)

        fun bluetooth(found: FoundMidi) = MidiChoice(MidiNames.bluetoothKey(found.address), found.name, MidiTransport.BLUETOOTH, found.address)
    }
}

/**
 * The app's MIDI devices other than Steven Piano (v1.11 — M29): what Android's MIDI service lists
 * ([devices]), the picker's Bluetooth search ([scan]: 12 s, on the app's shared scan budget), which
 * Bluetooth addresses belong to MIDI devices and never to the piano ([isForeign], which the piano's link
 * asks), and the devices open, each opened once however many hold it ([open]: a digital piano can be the
 * keyboard and the instrument at once).
 *
 * Foreign addresses: the chosen keyboard's and instrument's ([claim]), every device open or opening, every
 * Bluetooth MIDI device Android lists, and every one the search saw named anything but Steven Piano. The
 * reverse rule: the picker never offers a device named Steven Piano, a nameless one (the piano puts its name
 * in its scan response, which a hardware filter may drop), or the remembered piano's address
 * ([pianoAddress]); a device that turns out to be called Steven Piano once open is closed at once.
 *
 * Everything but [isForeign] and [claim] runs on [executor]'s thread ("steven-piano-midi"); callbacks to the
 * keyboard and the instrument come on it too.
 */
class MidiDevices(
    private val ports: MidiPorts,
    private val bluetooth: MidiBluetooth,
    private val executor: LinkExecutor,
    private val throttle: ScanThrottle,
    private val log: (String) -> Unit,
    /** The clock the shared [throttle] counts on (`SystemClock.elapsedRealtime`, as the piano's link). */
    private val nowMs: () -> Long = { executor.nanoTime() / 1_000_000L },
    /** The remembered piano's address, never offered. */
    private val pianoAddress: () -> String? = { null },
) {
    private val _devices = MutableStateFlow<List<MidiDeviceRef>>(emptyList())

    /** What Android's MIDI service lists now (USB, Bluetooth devices open, and in debug builds test ports). */
    val devices: StateFlow<List<MidiDeviceRef>> = _devices.asStateFlow()

    private val _scan = MutableStateFlow(MidiScan())
    val scan: StateFlow<MidiScan> = _scan.asStateFlow()

    /** Android's MIDI service is here. */
    val available: Boolean get() = ports.available

    /** Upper-case addresses that are MIDI devices, never the piano; read from the piano link's thread. */
    private val foreign = ConcurrentHashMap.newKeySet<String>()

    /** Addresses held by name ([claim]) or by an open device, with how many hold each. */
    private val holders = ConcurrentHashMap<String, Int>()

    private val added = CopyOnWriteArrayList<(MidiDeviceRef) -> Unit>()

    // Owned by the executor's thread.
    private var watching: Closeable? = null
    private var scanning = false
    private val found = LinkedHashMap<String, FoundMidi>()
    private val shared = HashMap<String, Shared>()
    private val scanTimeout = Runnable { onScanTimeout() }
    private val startScanLater = Runnable { beginScan() }

    /** Starts listening to Android's MIDI service. */
    fun start() = executor.execute {
        if (watching != null) return@execute
        watching = ports.watch(object : MidiPorts.Listener {
            override fun added(device: MidiDeviceRef) = executor.execute { onAdded(device) }

            override fun removed(device: MidiDeviceRef) = executor.execute { onRemoved(device) }
        })
        refresh()
    }

    /**
     * Whether [address] is a MIDI device the app uses or has seen, never the piano. The remembered piano's own address,
     * and one found to be Steven Piano, never are, whatever was claimed: the piano's link always reaches its piano. Any
     * thread.
     */
    fun isForeign(address: String): Boolean {
        val a = address.trim().uppercase()
        if (a in pianos || a == pianoAddress()?.trim()?.uppercase()) return false
        return a in foreign
    }

    /** [address] belongs to the chosen keyboard or instrument: foreign from now on, until [unclaim]. Any thread, at once. */
    fun claim(address: String?) {
        val a = address?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return
        holders.merge(a, 1, Int::plus)
        foreign += a
    }

    /** One hold on [address] let go. It stays foreign: the app has seen it as a MIDI device. */
    fun unclaim(address: String?) {
        val a = address?.trim()?.uppercase() ?: return
        holders.computeIfPresent(a) { _, n -> if (n <= 1) null else n - 1 }
    }

    /** Addresses that turned out to be Steven Piano once open (upper case): never opened, never paired with. */
    private val pianos = ConcurrentHashMap.newKeySet<String>()

    /**
     * Whether a device with [address] or [name] is Steven Piano, as far as the app can tell: named so, at the
     * remembered piano's address, or found to be the piano once open. The keyboard and the instrument never
     * open it, and never ask Android to pair with it (a bond would break the piano's own link). Any thread.
     */
    fun isPiano(address: String?, name: String?): Boolean {
        if (isPianoName(name)) return true
        val a = address?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return false
        return a in pianos || a == pianoAddress()?.trim()?.uppercase()
    }

    /** Called with each device Android's MIDI service adds (on the executor's thread): the keyboard waits for its own. */
    fun onDeviceAdded(listener: (MidiDeviceRef) -> Unit): Closeable {
        added += listener
        return Closeable { added -= listener }
    }

    /** The device Android lists under [key] now, if any (asked afresh: a device may have come since the last change). */
    fun listed(key: String): MidiDeviceRef? {
        refresh()
        return _devices.value.firstOrNull { it.key == key }
    }

    // ---- The picker's search ---------------------------------------------------------------

    /** Looks for Bluetooth MIDI devices for 12 s (a search under way goes on); the picker calls it as it opens and on Look again. */
    fun startScan() = executor.execute {
        if (scanning) return@execute
        executor.cancel(startScanLater)
        found.clear()
        beginScan()
    }

    /** The picker closed: the search ends. */
    fun stopScan() = executor.execute {
        executor.cancel(startScanLater)
        endScan(MidiScan.Phase.Idle)
    }

    private fun beginScan() {
        bluetooth.blocker()?.let { problem ->
            _scan.value = MidiScan(MidiScan.Phase.Blocked, found.values.toList(), problem = problem)
            return
        }
        val wait = throttle.acquire(nowMs())
        if (wait > 0L) {
            log("MIDI: the search waits $wait ms (Android allows 5 Bluetooth scans in 30 s, the piano's included)")
            _scan.value = MidiScan(MidiScan.Phase.Waiting, found.values.toList(), waitMs = wait)
            executor.schedule(wait, startScanLater)
            return
        }
        scanning = true
        guard {
            bluetooth.startScan(
                onFound = { result -> executor.execute { onFound(result) } },
                onFailed = { code -> executor.execute { onScanFailed(code) } },
            )
        }
        executor.schedule(PianoScanner.SCAN_TIMEOUT_MS, scanTimeout)
        _scan.value = MidiScan(MidiScan.Phase.Looking, found.values.toList())
    }

    private fun onFound(result: FoundPiano) {
        if (!scanning) return
        val address = result.address.trim().uppercase()
        val name = MidiNames.clean(result.name)
        if (name.isEmpty()) return   // nameless: perhaps the piano, its scan response lost
        if (isPianoName(name) || address.equals(pianoAddress()?.trim(), ignoreCase = true)) return
        foreign += address
        if (found[address]?.name == name) return
        found[address] = FoundMidi(address, name)
        _scan.value = _scan.value.copy(found = found.values.toList())
    }

    private fun onScanFailed(code: Int) {
        if (!scanning) return
        log("MIDI: the Bluetooth search failed: code ${BleCodes.scanFailure(code)}")
        endScan(MidiScan.Phase.Done)
    }

    private fun onScanTimeout() = endScan(MidiScan.Phase.Done)

    private fun endScan(phase: MidiScan.Phase) {
        executor.cancel(scanTimeout)
        if (scanning) guard { bluetooth.stopScan() }
        scanning = false
        _scan.value = MidiScan(phase, found.values.toList())
    }

    // ---- Opening, shared -------------------------------------------------------------------

    /**
     * One holder's hold on an open device (the keyboard's, the instrument's). [onOpened] hears it open (or
     * null: it could not be), [onLost] that it went away (unplugged, out of range) after it opened. [close]
     * lets go; the device closes when nobody holds it. Executor's thread.
     */
    inner class Lease internal constructor(
        val key: String,
        private val shared: Shared,
        private val onOpened: (OpenMidiDevice?) -> Unit,
        private val onLost: () -> Unit,
    ) : Closeable {
        internal var closed = false
            private set

        internal fun opened(device: OpenMidiDevice?) {
            if (!closed) onOpened(device)
        }

        internal fun lost() {
            if (!closed) onLost()
        }

        override fun close() = executor.execute { release(this) }

        internal fun markClosed() {
            closed = true
        }
    }

    /** A device open (or opening) for its holders. */
    inner class Shared internal constructor(val key: String, val address: String?) {
        internal var device: OpenMidiDevice? = null
        internal var opening = false
        internal val leases = mutableListOf<Lease>()
        internal val timeout = Runnable { onOpenTimeout(this) }
    }

    /** Opens [choice] for one holder, or joins the open device another holder already has (executor's thread). */
    fun open(choice: MidiChoice, onOpened: (OpenMidiDevice?) -> Unit, onLost: () -> Unit): Lease {
        val held = shared.getOrPut(choice.key) { Shared(choice.key, choice.address ?: choice.listed?.address) }
        val lease = Lease(choice.key, held, onOpened, onLost)
        held.leases += lease
        val device = held.device
        when {
            device != null -> executor.execute { lease.opened(device) }
            !held.opening -> startOpen(held, choice)
        }
        return lease
    }

    private fun startOpen(held: Shared, choice: MidiChoice) {
        held.opening = true
        claim(held.address)   // before Android's Bluetooth MIDI service connects: the piano's link never takes it
        log("MIDI: opening ${BleCodes.name(choice.name)} (${choice.transport.label})")
        executor.schedule(OPEN_TIMEOUT_MS, held.timeout)
        val answer: (OpenMidiDevice?) -> Unit = { opened -> executor.execute { onOpened(held, opened) } }
        val listed = choice.listed ?: listed(choice.key)
        when {
            listed != null -> guard { ports.open(listed, answer) } ?: answer(null)
            choice.address != null -> guard { ports.openBluetooth(choice.address, choice.name, answer) } ?: answer(null)
            else -> answer(null)
        }
    }

    private fun onOpened(held: Shared, opened: OpenMidiDevice?) {
        if (!held.opening || shared[held.key] !== held) {   // late, after a timeout or every holder left
            opened?.let { runCatching { it.close() } }
            return
        }
        executor.cancel(held.timeout)
        held.opening = false
        if (opened != null && isPiano(opened.device.address ?: held.address, opened.device.name)) {
            log("MIDI: ${BleCodes.name(opened.device.name)} is Steven Piano: closed at once (the piano keeps its own link)")
            (opened.device.address ?: held.address)?.let { pianos += it.trim().uppercase() }
            runCatching { opened.close() }
            return failOpen(held)
        }
        if (opened == null) {
            log("MIDI: ${held.key.substringBefore(':')} device could not be opened")
            return failOpen(held)
        }
        held.device = opened
        log("MIDI: ${BleCodes.name(opened.device.name)} open (${opened.device.transport.label}, ${opened.device.inputs} in, ${opened.device.outputs} out)")
        for (lease in held.leases.toList()) lease.opened(opened)
    }

    private fun onOpenTimeout(held: Shared) {
        if (!held.opening) return
        log("MIDI: no answer from the device within ${OPEN_TIMEOUT_MS / 1000} s")
        held.opening = false
        failOpen(held)
    }

    /** The device could not be opened: each holder hears so once, and it is forgotten (each may try again). */
    private fun failOpen(held: Shared) {
        shared.remove(held.key)
        unclaim(held.address)
        val leases = held.leases.toList()
        held.leases.clear()
        for (lease in leases) {
            lease.opened(null)
            lease.markClosed()
        }
    }

    private fun release(lease: Lease) {
        if (lease.closed) return
        lease.markClosed()
        val held = shared[lease.key] ?: return
        held.leases -= lease
        if (held.leases.isNotEmpty()) return
        shared.remove(held.key)
        executor.cancel(held.timeout)
        held.opening = false
        held.device?.let { device ->
            log("MIDI: ${BleCodes.name(device.device.name)} closed")
            runCatching { device.close() }
        }
        held.device = null
        unclaim(held.address)
    }

    // ---- What Android lists ----------------------------------------------------------------

    private fun onAdded(device: MidiDeviceRef) {
        refresh()
        log("MIDI: ${BleCodes.name(device.name)} added (${device.transport.label})")
        for (listener in added) listener(device)
    }

    private fun onRemoved(device: MidiDeviceRef) {
        refresh()
        val held = shared[device.key] ?: return
        log("MIDI: ${BleCodes.name(device.name)} removed (${device.transport.label})")
        shared.remove(device.key)
        executor.cancel(held.timeout)
        held.device?.let { runCatching { it.close() } }
        held.device = null
        held.opening = false
        unclaim(held.address)
        for (lease in held.leases.toList()) {
            lease.lost()
            lease.markClosed()
        }
    }

    private fun refresh() {
        val listed = guard { ports.devices() }.orEmpty()
        for (device in listed) device.address?.let { foreign += it.trim().uppercase() }   // Bluetooth MIDI devices open: never the piano
        _devices.value = listed
    }

    /** A call into the seam never takes the registry down. */
    private inline fun <T> guard(block: () -> T): T? = try {
        block()
    } catch (e: RuntimeException) {
        log("MIDI: Android refused a call (${e.javaClass.simpleName})")
        null
    }

    companion object {
        /** How long an open may take: a Bluetooth keyboard's pairing request waits on the person. */
        const val OPEN_TIMEOUT_MS = 15_000L

        /** Whether [name] is the piano's, which the picker never offers. */
        fun isPianoName(name: String?): Boolean = name?.trim().equals(PianoBluetooth.NAME, ignoreCase = true)
    }
}

// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.piano

import android.util.Log
import dev.stevenjin.stevenpiano.ble.ConsoleChannel
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.ble.LinkTrust
import dev.stevenjin.stevenpiano.ble.PianoLink
import dev.stevenjin.stevenpiano.ble.Trust
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the app knows of the piano's own settings. The piano is the source of truth; nothing is kept between connections. */
sealed interface PianoState {
    /** Not connected, or connected and not read yet. */
    data object Unknown : PianoState

    /** Connected, but the piano offers no settings over Bluetooth: no console, or `dump` unanswered (`!proto=1 … end`) within 2 s. */
    data object Unsupported : PianoState

    /**
     * The piano has answered: [values] by setting name, [facts] (its "!" lines) without the "!".
     * [lastError] is the piano's last refusal word for word, until a later write goes through;
     * [errorAbout] is the setting or command it concerns, so the screen shows it in that section.
     * [lock] (v1.25 — M58) is the piano's Bluetooth lock as this tablet stands with it: while it isn't
     * [PianoLock.writable] the settings are shown and not changed.
     */
    data class Ready(
        val values: Map<String, String>,
        val facts: Map<String, String>,
        val lastError: String? = null,
        val errorAbout: String? = null,
        val lock: PianoLock = PianoLock.NONE,
    ) : PianoState
}

/**
 * The piano's settings over its console (`firmware/docs/BLE_SETTINGS.md`). On every connection
 * with a console it sends `dump` and reads `name=value` lines into values and `!name=value` into
 * facts until `end`. A change waits 150 ms after the last change to the same setting, then goes
 * as `name value` and is read back with `get name`, so the screen shows what the piano holds.
 * A reply saying "out of range", "usage:", "unknown" or "refused" becomes the last error, cleared
 * by the next write the piano accepts. A preset is followed by a fresh `dump`. [leave] saves on
 * the piano when a change has gone through since the last save. All calls on [scope]'s thread.
 *
 * The piano's Bluetooth lock (v1.25 — M58, firmware 2.1.0; BLE_LOCK.md) is this conversation's too: the dump's
 * `!lockproto`, `!lock`, `!auth`, `!nonce` and `!guest` start the claim or the proof ([LockHandshake]) on each
 * connection, and [lock] and [trust] say where the tablet stands. Until the piano accepts this tablet (3 s at most)
 * every setting write waits, in order, the Full-power restore with them, and goes once it does; reads wait behind a
 * write that waits. Read only (locked to another device, or not accepted), writes are not sent, reads still are.
 * `off` is never held: the stop goes out whatever the lock says. Without `!lockproto` (firmware 2.0.x) nothing of
 * this changes anything. The lock's own commands ([openPairing], [unlock], [guestPlay]) go from a trusted connection.
 */
class PianoSettingsRepository(
    private val link: PianoLink,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    /** Whether a firmware update runs now (v1.18 — M46): [refreshFacts] asks the piano nothing meanwhile. */
    private val updating: () -> Boolean = { false },
    /** The wall clock, for [factsAt]. */
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Whether a person switched Full power off (v1.22, fire safety): until one does, Full power stays on. PWM drive can
     * break the I²C link to the power boards and leave a key on, so the app turns Full power back on whenever the piano
     * reports it off without that choice (a preset, a lower volume, another device), refuses a volume under 100 % while
     * it is on, and keeps it on through a preset.
     */
    private val fullPowerChosenOff: () -> Boolean = { false },
    /** Records a person's Full power switch: on true, off false. */
    private val chooseFullPower: (Boolean) -> Unit = {},
    /** The keys this tablet keeps for the pianos it claimed (v1.25 — M58); none, and none kept, by default. */
    private val lockKeys: LockKeys = NoLockKeys,
    /** A monotonic clock in ms: the lock's one attempt a second and guest play's time left (the app passes elapsed real time). */
    private val elapsed: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    /** Where the lock's key pairs are made: off the main thread. */
    private val crypto: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow<PianoState>(PianoState.Unknown)
    val state: StateFlow<PianoState> = _state.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)

    /** The piano's `status` report from the last Read status, as it wrote it; null before one, "" when it didn't answer. */
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    private val _statusReading = MutableStateFlow(false)
    val statusReading: StateFlow<Boolean> = _statusReading.asStateFlow()

    private val _factsAt = MutableStateFlow<Long?>(null)

    /** When a fact last came in on this connection (epoch ms; v1.18 — M46, the System page's `factsAt`); null before one. */
    val factsAt: StateFlow<Long?> = _factsAt.asStateFlow()

    private val _lock = MutableStateFlow(PianoLock.NONE)

    /** The piano's Bluetooth lock as this tablet stands with it on this connection ([PianoLock.NONE] when not connected). */
    val lock: StateFlow<PianoLock> = _lock.asStateFlow()

    private val _trust = MutableStateFlow(LinkTrust())

    /** The lock's word for the player's gate ([dev.stevenjin.stevenpiano.ble.LockGate]), with the connection it is about. */
    val trust: StateFlow<LinkTrust> = _trust.asStateFlow()

    private var session: Session? = null
    private var started = false

    /** Follows the link from now on: a read on every connection (each one its own: [LinkState.Connected.connection]), Unknown when it goes. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            link.state.map { (it as? LinkState.Connected)?.connection }.distinctUntilChanged().collect { connection ->
                session?.close()
                session = null
                _state.value = PianoState.Unknown
                _statusText.value = null
                _statusReading.value = false
                _factsAt.value = null
                _lock.value = PianoLock.NONE
                _trust.value = LinkTrust()
                if (connection != null) {
                    val console = link.console
                    if (console == null) {
                        log("No console on this piano: its settings stay at the piano")
                        _state.value = PianoState.Unsupported
                        _trust.value = LinkTrust(connection, Trust.Open)   // no console, no lock to prove: as before the lock
                    } else {
                        session = Session(console, connection, link.address).also { it.open() }
                    }
                }
            }
        }
    }

    /** Changes setting [name] to [value] (its wire form). Shown at once; sent 150 ms after the last change to it. */
    fun set(name: String, value: String) {
        session?.set(name, value)
    }

    fun set(name: String, value: Int) = set(name, PianoSettings.wire(value))

    fun set(name: String, on: Boolean) = set(name, PianoSettings.wire(on))

    fun set(name: String, value: Float) = set(name, PianoSettings.wire(value))

    /**
     * Holds setting [name] at [value] for a while (a channel's volume; DESIGN.md › v1.5 — M17): sent
     * as [set] sends it, but never counted for a save, and while anything is held a save [leave]
     * asks for waits until [releaseTemporary], so the piano never stores the held value.
     */
    fun holdTemporarily(name: String, value: Int) {
        session?.set(name, PianoSettings.wire(value), temporary = true)
    }

    /**
     * Ends [holdTemporarily]: [value] (what the setting held before) goes back, unless the person
     * has set [name] since, whose choice stands; then a save that waited goes. Also after the link
     * dropped and came back meanwhile (the piano kept the held value): the value goes back then too.
     */
    fun releaseTemporary(name: String, value: Int) {
        session?.release(name, PianoSettings.wire(value))
    }

    /** Applies a feel preset on the piano, then reads everything again: a preset changes several settings. */
    fun preset(command: String) {
        session?.preset(command)
    }

    /** All keys off, save, a test on [key], or reading the status. */
    fun action(action: PianoAction, key: Int = PianoSettings.TEST_KEY_DEFAULT) {
        session?.action(action, key)
    }

    /**
     * Asks the piano for fact [name] again (`get !name`; v1.6 — M21 reads `!ota` while a new
     * firmware confirms itself): the answer updates [state]'s facts. Nothing before the piano has
     * answered its dump, or for a name that is not a fact's.
     */
    fun readFact(name: String) {
        session?.readFact(name)
    }

    /**
     * Asks the piano for its live facts again (v1.18 — M46, the web panel's System page; `firmware/docs/BLE_DIAG.md`):
     * `get !<name>` for [PianoSettings.liveFacts] and [PianoSettings.diagFacts], each only when the piano listed it in its
     * dump (2.0.0 answers a name it lacks "unknown setting", which would read as a refusal). Nothing before the piano has
     * answered its dump, nor while a firmware update runs. True when it asked. The caller keeps it to once in 10 s.
     */
    fun refreshFacts(): Boolean {
        if (updating()) return false
        return session?.refreshFacts() ?: false
    }

    /** The screen has shown the last error; it goes until the next one. */
    fun dismissError() {
        session?.dismissError()
    }

    /** The Piano screen left the foreground: changes waiting out their 150 ms go now, then `save` if anything changed since the last. */
    fun leave() {
        session?.leave()
    }

    /** Let another device pair (5 min): `lock pairwindow`, from a trusted connection (v1.25 — M58). */
    fun openPairing() {
        session?.lockCommand(LockHandshake.PAIR_WINDOW)
    }

    /** Unlock the piano: `lock forget` (every key goes; the tablet claims it again at its next connection). */
    fun unlock() {
        session?.lockCommand(LockHandshake.FORGET)
    }

    /** Let anyone play for [minutes] (`lock guest N`, 1–240), or end guest play with 0. */
    fun guestPlay(minutes: Int) {
        session?.lockCommand(LockHandshake.guest(minutes))
    }

    /** `!lock` and `!guest` read again: the piano says nothing when its screen opens a window or guest play ends. */
    fun refreshLock() {
        session?.refreshLock()
    }

    /** One connection's conversation with the piano ([connection], the piano at [address]). Everything here dies with the connection. */
    private inner class Session(private val console: ConsoleChannel, private val connection: Long, address: String?) {
        private val job = SupervisorJob(scope.coroutineContext[Job])
        private val here = CoroutineScope(scope.coroutineContext + job)

        private val values = HashMap<String, String>()
        private val facts = HashMap<String, String>()
        private var ready = false
        private var lastError: String? = null
        private var errorAbout: String? = null
        private var errors = 0   // error lines seen, to tell whether one came after a write

        /** A dump being read: true at `end`, false when the piano doesn't know the command. */
        private var dump: CompletableDeferred<Boolean>? = null

        /** `get`s sent and not answered yet, oldest first; the one that reads a write back carries it. */
        private val awaiting = ArrayDeque<Awaited>()

        private val latest = HashMap<String, String>()   // the value each debounced setting will be written as
        private val debounce = HashMap<String, Job>()
        private var sentWrites = 0
        private var savedThrough = 0   // writes up to this one are covered by a save
        private var acceptedThrough = 0   // the latest write the piano accepted

        /** Settings held for now ([holdTemporarily]); a save waits while any is. */
        private val held = HashSet<String>()

        /** Settings whose next write is a temporary one, never counted for a save. */
        private val temporaryWrites = HashSet<String>()

        /** A save [leave] asked for while something was held: it goes once nothing is. */
        private var saveWhenReleased = false

        /** Settings the person changed on this connection: a release never overrides them. */
        private val setByPerson = HashSet<String>()

        private var lastCommand: String? = null
        private var statusLines: MutableList<String>? = null
        private var statusEnd: Job? = null

        /** Lines that wait for the piano to accept this tablet (v1.25 — M58), in order. */
        private val queued = ArrayList<String>()

        /** The lock's claim or proof on this connection. */
        private val handshake = LockHandshake(
            address = address,
            keys = lockKeys,
            scope = here,
            crypto = crypto,
            now = elapsed,
            send = console::sendLine,
            ask = ::askNow,
            log = log,
            changed = ::lockChanged,
            trusted = ::releaseQueued,
            refused = ::dropQueued,
        )

        init {
            lockChanged(handshake.lock)
        }

        fun open() {
            here.launch(start = CoroutineStart.UNDISPATCHED) { console.lines.collect(::onLine) }
            here.launch {
                val read = readAll()
                val answered = withTimeoutOrNull(READ_TIMEOUT_MS) { read.await() } == true
                if (answered && facts["proto"] == PianoSettings.PROTOCOL) {
                    ready = true
                    publish()
                    handshake.begin(facts.toMap())
                } else {
                    log(if (answered) "The console speaks protocol ${facts["proto"]}, not ${PianoSettings.PROTOCOL}" else "No dump from the console within 2 s")
                    _state.value = PianoState.Unsupported
                    handshake.none()
                    close()
                }
            }
        }

        /** A lock command, from a trusted connection only (the lock refuses it otherwise). */
        fun lockCommand(line: String) {
            if (ready) handshake.command(line)
        }

        fun refreshLock() {
            if (ready) handshake.refresh()
        }

        /** The lock changed: the gate hears it at once, the screens with the settings (never halfway through a dump). */
        private fun lockChanged(now: PianoLock) {
            _lock.value = now
            _trust.value = LinkTrust(connection, now.phase.trust)
            if (ready && dump == null) publish()
        }

        /** `get !name` now, ahead of anything held (the lock's own reads: a held write may be waiting for them). */
        private fun askNow(name: String) {
            if (!FACT_NAME.matches(name)) return
            awaiting += Awaited("!$name", null)
            console.sendLine("get !$name")
        }

        /** The piano accepts this tablet (or has no lock): what waited goes, in order. */
        private fun releaseQueued() {
            if (queued.isEmpty()) return
            val lines = queued.toList()
            queued.clear()
            log("The piano accepts this tablet: ${lines.size} waiting line" + (if (lines.size == 1) "" else "s") + " sent")
            lines.forEach(console::sendLine)
        }

        /** Read only: the writes that waited are not sent; the reads among them still are (their answers are awaited). */
        private fun dropQueued() {
            if (queued.isEmpty()) return
            val lines = queued.toList()
            queued.clear()
            val dropped = lines.count { !isRead(it) }
            if (dropped > 0) log("The piano doesn't accept this tablet: $dropped waiting change" + (if (dropped == 1) "" else "s") + " not sent")
            lines.filter(::isRead).forEach(console::sendLine)
        }

        fun close() = job.cancel()

        fun set(name: String, value: String, temporary: Boolean = false) {
            val setting = PianoSettings.named(name)
            if (!ready || setting == null || setting.readOnly || !WIRE_VALUE.matches(value)) {
                log("Not sent: $name $value")
                return
            }
            if (name == FULL_POWER && !temporary) chooseFullPower(value.trim() != "0")
            if (name == VOLUME && keepsFullPower() && (value.toFloatOrNull() ?: FULL_VOLUME) < FULL_VOLUME) {
                log("Not sent: $name $value (Full power stays on until it is switched off)")
                publish()
                return
            }
            if (temporary) {
                held += name
                temporaryWrites += name
            } else {
                held -= name   // the person's own value: it stands, and is saved as any other
                temporaryWrites -= name
                setByPerson += name
            }
            latest[name] = value
            values[name] = value   // shown at once; the piano's answer confirms or corrects it
            publish()
            debounce.remove(name)?.cancel()
            debounce[name] = here.launch {
                delay(DEBOUNCE_MS)
                debounce.remove(name)
                write(name)
            }
        }

        fun preset(command: String) {
            if (!ready || PianoSettings.presets.none { it.command == command }) return
            val keep = keepsFullPower()
            flushPending()   // a change waiting out its 150 ms came first, so it goes first
            send(command)
            if (keep) {
                // Soft, Cinematic and Expressive turn Full power off on the piano: it stays on (v1.22), and is saved so.
                send("$VOLUME ${FULL_VOLUME.toInt()}")
                send("$FULL_POWER 1")
                send(PianoAction.Save.command)
            }
            readAll()
        }

        /** Whether Full power is on and must stay on: on now, and no person has switched it off. */
        private fun keepsFullPower(): Boolean = values[FULL_POWER]?.trim() != "0" && !fullPowerChosenOff()

        /** Set while the app turns Full power back on, so it asks once until the piano answers. */
        private var restoring = false

        /**
         * Full power off without a person's choice (a preset, a lower volume, another device, a new connection to a piano
         * where it was off): turned back on and saved (v1.22, the default Steven chose: on until switched off).
         */
        private fun restoreFullPower() {
            if (!ready || dump != null || handshake.lock.phase.readOnly) return
            if (values[FULL_POWER]?.trim() != "0") {
                restoring = false
                return
            }
            if (restoring || fullPowerChosenOff()) return
            restoring = true
            log("Full power was off without being switched off: turned back on")
            latest[FULL_POWER] = "1"
            debounce.remove(FULL_POWER)?.cancel()
            write(FULL_POWER)
            save()
        }

        fun action(action: PianoAction, key: Int) {
            if (!ready) return
            when (action) {
                PianoAction.Status -> readStatus()
                PianoAction.Save -> save()
                PianoAction.AllKeysOff -> send(action.command)
                PianoAction.LedTest, PianoAction.TestMin, PianoAction.TestMax ->
                    send("${action.command} ${key.coerceIn(PianoSettings.testKeys)}")
            }
        }

        fun readFact(name: String) {
            if (!ready || !FACT_NAME.matches(name)) return
            awaiting += Awaited("!$name", null)
            send("get !$name")
        }

        fun dismissError() {
            if (lastError == null) return
            lastError = null
            errorAbout = null
            if (ready) publish()
        }

        fun leave() {
            if (!ready) return
            flushPending()
            val unconfirmed = awaiting.any { it.write?.let { w -> w.persist && w.number > savedThrough } == true }
            if (acceptedThrough > savedThrough || unconfirmed) {
                if (held.isEmpty()) save() else saveWhenReleased = true
            }
        }

        /**
         * Ends a hold on [name]: [value] goes back unless the person set it since (on this
         * connection; a hold made before a reconnection is put back too); then a save that waited goes.
         */
        fun release(name: String, value: String) {
            if (name in held || name !in setByPerson) {
                set(name, value, temporary = true)
                held -= name
            }
            if (held.isEmpty() && saveWhenReleased) {
                saveWhenReleased = false
                save()
            }
        }

        private fun save() {
            flushPending()
            send(PianoAction.Save.command)
            savedThrough = sentWrites
        }

        /** Sends every debounced change now. */
        private fun flushPending() {
            for (name in debounce.keys.toList()) {
                debounce.remove(name)?.cancel()
                write(name)
            }
        }

        /**
         * `name value`, then `get name` (and whatever that setting changes too) to read back what the piano holds; a
         * fact only when the piano reported it (an earlier firmware has no `!repeatms`, and would call it unknown).
         */
        private fun write(name: String) {
            val value = latest.remove(name) ?: return
            val write = Write(++sentWrites, errors, persist = temporaryWrites.remove(name).not())
            send("$name $value")
            for (read in listOf(name) + PianoSettings.alsoRead[name].orEmpty()) {
                if (read.startsWith("!") && read.removePrefix("!") !in facts) continue
                awaiting += Awaited(read, if (read == name) write else null)
                send("get $read")
            }
        }

        /** Sends `dump`; values and facts fill in as its lines come, and show together at `end`. */
        private fun readAll(): CompletableDeferred<Boolean> {
            dump?.complete(false)
            val read = CompletableDeferred<Boolean>()
            dump = read
            send("dump")
            if (ready) {
                here.launch {   // a dump that never ends must not hold the screen back for good
                    if (withTimeoutOrNull(READ_TIMEOUT_MS) { read.await() } == null) endDump(answered = false)
                }
            }
            return read
        }

        private fun readStatus() {
            statusEnd?.cancel()
            statusLines = mutableListOf()
            _statusReading.value = true
            send(PianoAction.Status.command)
            for (fact in PianoSettings.liveFacts) {
                awaiting += Awaited("!$fact", null)
                send("get !$fact")
            }
            statusEnd = here.launch {
                delay(STATUS_FIRST_LINE_MS)
                endStatus()
            }
        }

        /** [readStatus]'s facts again, and the System page's, without the status report: only names the dump listed. */
        fun refreshFacts(): Boolean {
            if (!ready) return false
            val names = (PianoSettings.liveFacts + PianoSettings.diagFacts).distinct().filter { it in facts }
            for (fact in names) {
                awaiting += Awaited("!$fact", null)
                send("get !$fact")
            }
            return names.isNotEmpty()
        }

        /**
         * Every line the settings send (v1.25 — M58: past the lock). `off` goes at once, always. Writable (no lock, or the
         * piano accepts this tablet): at once, after anything that waited. Read only: reads go, writes don't. Otherwise
         * (the dump or the proof under way): a read goes at once unless a write waits ahead of it; everything else waits.
         */
        private fun send(line: String) {
            if (!line.startsWith("get ") && line != "dump") lastCommand = line.substringBefore(' ')
            val phase = handshake.lock.phase
            when {
                line == PianoAction.AllKeysOff.command -> console.sendLine(line)
                phase.writable -> {
                    releaseQueued()
                    console.sendLine(line)
                }
                phase.readOnly -> if (isRead(line)) console.sendLine(line) else log("Not sent (the piano doesn't accept this tablet): ${line.substringBefore(' ')}")
                queued.isEmpty() && isRead(line) -> console.sendLine(line)
                else -> queued += line
            }
        }

        private fun onLine(line: String) {
            if (handshake.onLine(line)) return   // the lock's own replies are not the settings'
            statusLines?.let { collecting ->
                if (!VALUE_LINE.matches(line)) collecting += line
                statusEnd?.cancel()
                statusEnd = here.launch {
                    delay(STATUS_SILENCE_MS)
                    endStatus()
                }
            }
            val value = VALUE_LINE.matchEntire(line)
            when {
                value != null -> onValue(value.groupValues[1], value.groupValues[2])
                line.trim() == END && dump != null -> endDump(answered = true)
                !ready && dump != null && line.contains(UNKNOWN_COMMAND) -> endDump(answered = false)
                ERROR_WORDS.any { line.contains(it, ignoreCase = true) } -> onError(line.trim())
            }
        }

        private fun onValue(wireName: String, text: String) {
            val i = awaiting.indexOfFirst { it.name == wireName }
            val answered = if (i >= 0) awaiting.removeAt(i) else null
            if (wireName.startsWith("!")) {
                val name = wireName.removePrefix("!")
                facts[name] = text
                _factsAt.value = clock()
                handshake.onFact(name, text)
            } else {
                // A newer value the person is still choosing, or already sent, keeps showing until its own answer.
                val newer = wireName in debounce || awaiting.any { it.name == wireName }
                if (!newer) values[wireName] = text
            }
            answered?.write?.let { write ->
                if (errors == write.errorsBefore) {   // nothing refused since this write went out: it went through
                    lastError = null
                    errorAbout = null
                    if (write.persist) acceptedThrough = maxOf(acceptedThrough, write.number)   // a held value is never saved
                }
            }
            if (ready && dump == null) publish()
        }

        private fun endDump(answered: Boolean) {
            val read = dump ?: return
            dump = null
            read.complete(answered)
            if (ready) publish()
        }

        private fun onError(message: String) {
            errors++
            lastError = message
            errorAbout = aboutWhat(message) ?: lastCommand
            if (ready) publish()
        }

        private fun endStatus() {
            val lines = statusLines ?: return
            statusLines = null
            statusEnd = null
            _statusText.value = lines.joinToString("\n").trimIndent().trim('\n')
            _statusReading.value = false
        }

        private fun publish() {
            _state.value = PianoState.Ready(values.toMap(), facts.filterKeys { it !in HIDDEN_FACTS }, lastError, errorAbout, handshake.lock)
            restoreFullPower()
        }
    }

    /** A `get` waiting for its answer; [write] when it reads back a change. */
    private class Awaited(val name: String, val write: Write?)

    /**
     * The [number]th change written this connection, and how many errors had been seen before it
     * went; [persist] false for a held value ([holdTemporarily]), which never calls for a save.
     */
    private class Write(val number: Int, val errorsBefore: Int, val persist: Boolean = true)

    private companion object {
        const val TAG = "PianoSettings"

        /** Full power, and the volume that turns it off on the piano below 100 % (v1.22). */
        const val FULL_POWER = "fullpower"
        const val VOLUME = "volume"
        const val FULL_VOLUME = 100f
        const val DEBOUNCE_MS = 150L
        const val READ_TIMEOUT_MS = 2_000L
        const val STATUS_SILENCE_MS = 300L
        const val STATUS_FIRST_LINE_MS = 2_000L
        const val END = "end"
        const val UNKNOWN_COMMAND = "unknown command"

        /** `name=value` and `!name=value`, as `dump` and `get` print them; status lines are indented and never match. */
        val VALUE_LINE = Regex("""^(!?[a-z][a-z0-9_]*)=(.*)$""")

        /** One plain number, whole or with decimals: nothing else is ever sent as a value. */
        val WIRE_VALUE = Regex("""^-?[0-9]+(\.[0-9]+)?$""")

        /** A fact's name as `dump` prints it, without its "!". */
        val FACT_NAME = Regex("""^[a-z][a-z0-9_]{0,31}$""")

        val ERROR_WORDS = listOf("out of range", "usage:", "unknown", "refused")

        const val NANOS_PER_MS = 1_000_000L

        /** Facts the lock reads and no screen shows (v1.25 — M58): the connection's one-use challenge. */
        val HIDDEN_FACTS = setOf(LockHandshake.NONCE)

        /** Lines that only read: they go whatever the lock says (the piano answers them untrusted). */
        fun isRead(line: String): Boolean = line == "dump" || line.startsWith("get ") || line == PianoAction.Status.command || line == "help"

        /** The setting or command a refusal names ("ledbright out of range", "usage: ledtest …"), when it names one. */
        fun aboutWhat(message: String): String? {
            val words = message.removePrefix("usage:").trim().split(' ', '|')
            val first = words.firstOrNull().orEmpty().let { if (it == "minwhite") "min" else it }
            val known = PianoSettings.named(first) != null ||
                PianoAction.entries.any { it.command == first } ||
                PianoSettings.presets.any { it.command == first }
            return first.takeIf { known }
        }
    }
}

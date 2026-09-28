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
import dev.stevenjin.stevenpiano.ble.PianoLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
     */
    data class Ready(
        val values: Map<String, String>,
        val facts: Map<String, String>,
        val lastError: String? = null,
        val errorAbout: String? = null,
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
 */
class PianoSettingsRepository(
    private val link: PianoLink,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private val _state = MutableStateFlow<PianoState>(PianoState.Unknown)
    val state: StateFlow<PianoState> = _state.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)

    /** The piano's `status` report from the last Read status, as it wrote it; null before one, "" when it didn't answer. */
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    private val _statusReading = MutableStateFlow(false)
    val statusReading: StateFlow<Boolean> = _statusReading.asStateFlow()

    private var session: Session? = null
    private var started = false

    /** Follows the link from now on: a read on every connection, Unknown when it goes. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            link.state.map { it is LinkState.Connected }.distinctUntilChanged().collect { connected ->
                session?.close()
                session = null
                _state.value = PianoState.Unknown
                _statusText.value = null
                _statusReading.value = false
                if (connected) {
                    val console = link.console
                    if (console == null) {
                        log("No console on this piano: its settings stay at the piano")
                        _state.value = PianoState.Unsupported
                    } else {
                        session = Session(console).also { it.open() }
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

    /** The screen has shown the last error; it goes until the next one. */
    fun dismissError() {
        session?.dismissError()
    }

    /** The Piano screen left the foreground: changes waiting out their 150 ms go now, then `save` if anything changed since the last. */
    fun leave() {
        session?.leave()
    }

    /** One connection's conversation with the piano. Everything here dies with the connection. */
    private inner class Session(private val console: ConsoleChannel) {
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

        fun open() {
            here.launch(start = CoroutineStart.UNDISPATCHED) { console.lines.collect(::onLine) }
            here.launch {
                val read = readAll()
                val answered = withTimeoutOrNull(READ_TIMEOUT_MS) { read.await() } == true
                if (answered && facts["proto"] == PianoSettings.PROTOCOL) {
                    ready = true
                    publish()
                } else {
                    log(if (answered) "The console speaks protocol ${facts["proto"]}, not ${PianoSettings.PROTOCOL}" else "No dump from the console within 2 s")
                    _state.value = PianoState.Unsupported
                    close()
                }
            }
        }

        fun close() = job.cancel()

        fun set(name: String, value: String, temporary: Boolean = false) {
            val setting = PianoSettings.named(name)
            if (!ready || setting == null || setting.readOnly || !WIRE_VALUE.matches(value)) {
                log("Not sent: $name $value")
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
            flushPending()   // a change waiting out its 150 ms came first, so it goes first
            send(command)
            readAll()
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

        /** `name value`, then `get name` (and whatever that setting changes too) to read back what the piano holds. */
        private fun write(name: String) {
            val value = latest.remove(name) ?: return
            val write = Write(++sentWrites, errors, persist = temporaryWrites.remove(name).not())
            send("$name $value")
            for (read in listOf(name) + PianoSettings.alsoRead[name].orEmpty()) {
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

        private fun send(line: String) {
            if (!line.startsWith("get ") && line != "dump") lastCommand = line.substringBefore(' ')
            console.sendLine(line)
        }

        private fun onLine(line: String) {
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
                facts[wireName.removePrefix("!")] = text
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
            _state.value = PianoState.Ready(values.toMap(), facts.toMap(), lastError, errorAbout)
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

        val ERROR_WORDS = listOf("out of range", "usage:", "unknown", "refused")

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

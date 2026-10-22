// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import dev.stevenjin.stevenpiano.piano.PianoSettings
import dev.stevenjin.stevenpiano.ui.ListenCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The key test now (v1.23 — M55): [running], the [key] being struck (MIDI; null before the first and after the last), how
 * many keys have been struck ([done]) of [total], the [results] of the last test that ran to its end (null while one runs,
 * before any, or when it stopped early; kept in memory until the next), and why the last one [stopped] early.
 */
data class KeyTestState(
    val running: Boolean = false,
    val key: Int? = null,
    val done: Int = 0,
    val total: Int = KeyTest.TOTAL,
    val results: List<KeyResult>? = null,
    val stopped: String? = null,
)

/**
 * Test every key (v1.23 — M55; DESIGN.md › v1.23 — M55): the piano strikes its 84 keys ([KEYS], 24 to 107) in turn, low to
 * high, one every [EVERY_MS], each [HOLD_MS] long at velocity [VELOCITY], through the player's own path and its safety
 * (the router's re-strike guard and reference counts, the lock during a firmware update: [Piano]); the microphone hears
 * throughout ([Ears]: the panel that started it hears it live) and [KeyCheck] says, key by key, which didn't sound. The
 * tablet's own piano sound is held silent meanwhile: it would fake a result. About 39 s.
 *
 * Refused, with its reason: the piano not connected; a piece playing ("Stop the music first."); a quiet time not lifted;
 * a firmware update running; the microphone not allowed (for a panel's test, Listening from the panel off; the tablet's
 * own, and the safety check's, need Android's permission alone: v1.26 — M61); another test running; and the
 * microphone's own refusals (the app not in the foreground). [stop] ends it at once with the player's stop sequence; it
 * ends by itself, saying why, when the piano disconnects, a piece starts, a quiet time begins, an update starts or the
 * microphone stops. [before] (v1.26 — M61: the 24 V rail with the power plug, connected by the integrator) is awaited
 * before the first strike, whoever started the test; false (the rail not up within its time) ends it before any key is
 * struck, as no key could sound. Main thread ([scope]'s).
 */
class KeyTest(
    private val scope: CoroutineScope,
    private val piano: Piano,
    private val ears: Ears,
    private val nanos: () -> Long = System::nanoTime,
    private val before: suspend () -> Boolean = { true },
) {
    /** The piano as the test strikes it, through the player (AppGraph's; a stand-in in the tests). Main thread. */
    interface Piano {
        /** The instrument is connected. */
        val connected: Boolean

        /** A piece plays, or loads to play. */
        val playing: Boolean

        /** While a quiet time holds the piano and nobody lifted it, its words ("Quiet until 9:30. Use Play anyway."); else null. */
        fun quiet(): String?

        /** The piano's firmware is being updated (the player locked). */
        val updating: Boolean

        /** A key down, through the player's live path. */
        fun noteOn(key: Int, velocity: Int)

        fun noteOff(key: Int)

        /** The player's stop sequence, at once. */
        fun stopSequence()

        /** The tablet's own piano sound held silent ([on]), or let be again. */
        fun muteTablet(on: Boolean)
    }

    private val _state = MutableStateFlow(KeyTestState())
    val state: StateFlow<KeyTestState> = _state.asStateFlow()

    /** Whether a test runs now. */
    val running: Boolean get() = _state.value.running

    private var job: Job? = null

    /** The key down now, if any: let go whatever ends the test. */
    private var down: Int? = null

    /**
     * Test every key, asked by the session [token] (null: the tablet itself, which hears nothing live): refused with its
     * reason, else it begins (the results of the last test go).
     */
    fun start(token: String?): ListenAnswer {
        refusal()?.let { return it }
        if (!ears.allowed(token)) return ListenAnswer.Refused(ListenAnswer.OFF, if (token == null) ListenCopy.NO_PERMISSION else ListenCopy.OFF)
        if (running) return ListenAnswer.Refused(ListenAnswer.TESTING, ListenCopy.TESTING)
        val take = KeyTake()
        val heard = ears.tap(token, take::add) { end(ListenCopy.STOPPED_MICROPHONE, sequence = true) }
        if (heard is ListenAnswer.Refused) return heard
        piano.muteTablet(true)
        _state.value = KeyTestState(running = true)
        job = scope.launch { sweep(take) }
        return ListenAnswer.Started
    }

    /** Stop: the test ends at once with the player's stop sequence; no results. */
    fun stop() {
        end(ListenCopy.STOPPED, sequence = true)
    }

    private fun refusal(): ListenAnswer.Refused? {
        if (!piano.connected) return ListenAnswer.Refused(ListenAnswer.NOT_CONNECTED, ListenCopy.NOT_CONNECTED)
        if (piano.playing) return ListenAnswer.Refused(ListenAnswer.PLAYING, ListenCopy.PLAYING)
        piano.quiet()?.let { return ListenAnswer.Refused(ListenAnswer.QUIET, it) }
        if (piano.updating) return ListenAnswer.Refused(ListenAnswer.UPDATING, ListenCopy.UPDATING)
        return null
    }

    private suspend fun sweep(take: KeyTake) {
        val ready = try {
            before()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!ready) {
            end(ListenCopy.STOPPED_RAIL, sequence = true)
            return
        }
        delay(LEAD_MS)   // the room before the first key, for its baseline
        for ((i, key) in KEYS.withIndex()) {
            interruption()?.let { (why, sequence) ->
                end(why, sequence)
                return
            }
            _state.update { it.copy(key = key, done = i + 1) }
            take.strike(key, nanos())
            down = key
            piano.noteOn(key, VELOCITY)
            delay(HOLD_MS)
            piano.noteOff(key)
            down = null
            delay(EVERY_MS - HOLD_MS)
        }
        _state.update { it.copy(key = null) }
        delay(TAIL_MS)   // the last key's sound reaches the microphone
        val results = take.finish()
        if (results == null) {
            end(ListenCopy.STOPPED_MICROPHONE, sequence = false)
            return
        }
        job = null
        close()
        _state.value = KeyTestState(results = results)
    }

    /** Why the test can't go on, and whether the stop sequence follows; null while it may. */
    private fun interruption(): Pair<String, Boolean>? = when {
        !piano.connected -> ListenCopy.STOPPED_DISCONNECTED to false   // the piano let go by itself
        piano.playing -> ListenCopy.STOPPED_PLAYING to false   // a piece plays: its keys are not the test's to silence
        piano.quiet() != null -> ListenCopy.STOPPED_QUIET to true
        piano.updating -> ListenCopy.STOPPED_UPDATING to true
        else -> null
    }

    /** The test ends before its end, saying [why]: the key down let go, and with [sequence] the player's stop sequence. */
    private fun end(why: String, sequence: Boolean) {
        if (!running) return
        job?.cancel()
        job = null
        down?.let(piano::noteOff)
        down = null
        if (sequence) piano.stopSequence()
        close()
        _state.value = KeyTestState(stopped = why)
    }

    private fun close() {
        ears.untap()
        piano.muteTablet(false)
    }

    companion object {
        /** The piano's 84 keys, low to high. */
        val KEYS: IntRange = PianoSettings.testKeys
        val TOTAL: Int = KEYS.count()

        /** One strike every 450 ms, 150 ms long, at velocity 100. */
        const val EVERY_MS = 450L
        const val HOLD_MS = 150L
        const val VELOCITY = 100

        /** The room heard before the first strike, and after the last one's window for its sound to arrive. */
        const val LEAD_MS = 600L
        const val TAIL_MS = 500L
    }
}

/**
 * The key test's sound, checked key by key as it comes (v1.23 — M55): frames in from the microphone's thread ([add]),
 * strikes in from the main thread ([strike], when each key went down, on `System.nanoTime`'s clock, which the frames carry
 * too), and as soon as a key's window has been heard (120 ms before its strike to 330 ms after) it is checked
 * ([KeyCheck.heard]) and every frame no key still needs is let go: about a second of the room is held at a time, and none
 * once the test is over. [finish] checks what is left with what came; null when no sound came at all. Thread-safe.
 */
class KeyTake(private val rate: Int = KeyCheck.RATE) {
    private val frames = ArrayDeque<MicFrame>()
    private val waiting = ArrayDeque<Pair<Int, Long>>()
    private val results = ArrayList<KeyResult>()
    private var any = false

    private val before = KeyCheck.BEFORE_MS * rate / 1000
    private val after = KeyCheck.TO_MS * rate / 1000

    /** Key [key] went down at [atNanos]. */
    @Synchronized
    fun strike(key: Int, atNanos: Long) {
        waiting.addLast(key to atNanos)
        ready(force = false)
    }

    /** A frame heard (the microphone's thread). */
    @Synchronized
    fun add(frame: MicFrame) {
        any = true
        frames.addLast(frame)
        ready(force = false)
        forget()
    }

    /** Every key checked, those still waiting with what was heard; null when nothing was heard at all. */
    @Synchronized
    fun finish(): List<KeyResult>? {
        if (!any) return null
        ready(force = true)
        frames.clear()
        return results.toList()
    }

    /** The keys whose window has been heard (all of them when [force]), checked in turn. */
    private fun ready(force: Boolean) {
        while (waiting.isNotEmpty()) {
            val (key, nanos) = waiting.first()
            val at = indexOf(nanos) ?: return
            if (!force && heardTo() < at + after) return
            val from = at - before
            results += KeyResult(key, KeyCheck.heard(window(from, at + after), before, key, rate))
            waiting.removeFirst()
        }
    }

    /** The hearing's sample number at [nanos], from the latest frame's own time; null before the first. */
    private fun indexOf(nanos: Long): Long? {
        val frame = frames.lastOrNull() ?: return null
        return frame.index + (nanos - frame.startNanos) * rate / NANOS_PER_SECOND
    }

    private fun heardTo(): Long = frames.lastOrNull()?.let { it.index + it.samples.size } ?: 0L

    /** The samples [from] to [to] of the hearing; silence where none was heard. */
    private fun window(from: Long, to: Long): ShortArray {
        val out = ShortArray((to - from).toInt().coerceAtLeast(0))
        for (frame in frames) {
            val start = maxOf(from, frame.index)
            val end = minOf(to, frame.index + frame.samples.size)
            if (start >= end) continue
            System.arraycopy(frame.samples, (start - frame.index).toInt(), out, (start - from).toInt(), (end - start).toInt())
        }
        return out
    }

    /** Lets go of the frames no key needs: before the next waiting key's window, or older than a second. */
    private fun forget() {
        val next = waiting.firstOrNull()?.let { indexOf(it.second) }
        val keepFrom = minOf(next?.let { it - before } ?: Long.MAX_VALUE, heardTo() - rate)
        while (frames.size > 1) {
            val first = frames.first()
            if (first.index + first.samples.size > keepFrom) break
            frames.removeFirst()
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

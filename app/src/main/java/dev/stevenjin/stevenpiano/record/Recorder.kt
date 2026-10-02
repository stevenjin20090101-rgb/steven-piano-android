// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.record

import dev.stevenjin.stevenpiano.instruments.KeyboardListener
import dev.stevenjin.stevenpiano.midi.KeyEvents
import dev.stevenjin.stevenpiano.midi.SmfWriter

/** Why a take ended. */
enum class TakeEnd {
    /** The person pressed Stop. */
    Stopped,

    /** It reached [Recorder.MAX_NANOS] (an hour). */
    Longest,

    /** It reached [Recorder.MAX_EVENTS] events. */
    Fullest,

    /** Nothing was played for [Recorder.IDLE_NANOS] (five minutes). */
    Silence,

    /** The app left the foreground. */
    AppLeft,
}

/**
 * A finished take (v1.11 — M29): what was played, from its first event at time 0: its [notes] as pairs
 * (keys 0-127 and raw velocities, as played, before any routing: no transpose, fold or velocity percentage),
 * its pedals' every value ([controls]: CC64, CC66, CC67, lifted at the end), how long it lasts, why it ended.
 */
class Take(
    val notes: List<SmfWriter.Note>,
    val controls: List<SmfWriter.Control>,
    val durationMicros: Long,
    val ended: TakeEnd,
)

/**
 * The recorder (v1.11 — M29): what is played, captured before the router, so a take holds the music and
 * the instrument that plays it back routes it as it routes anything. Two sources: the Keys screen's keys and
 * sustain ([screenKey], [screenSustain]; times from the clock), and the MIDI keyboard, Live on or off (a
 * [KeyboardListener]; its sender's own stamp when that is within [STAMP_WINDOW_NANOS] before the bytes came and
 * never goes backwards, else the arrival). A piece playing is never captured.
 *
 * Caps: a take ends at [MAX_NANOS] (an hour) or [MAX_EVENTS] events, or after [IDLE_NANOS] with nothing played
 * ([due] says which; the session stops it then and saves). On [stop]: the events in time order, a key held
 * closed there, the pedals lifted, everything moved so the first event is at 0; a take without a note is none.
 * Events go into two parallel arrays that double as they fill, under one short lock: the port's thread and
 * the main thread both write.
 */
class Recorder(
    private val nanoTime: () -> Long = System::nanoTime,
    private val maxEvents: Int = MAX_EVENTS,
    private val maxNanos: Long = MAX_NANOS,
    private val idleNanos: Long = IDLE_NANOS,
) : KeyboardListener {
    private val lock = Any()
    private var times = LongArray(INITIAL)
    private var packed = IntArray(INITIAL)
    private var count = 0
    private var startedAt = 0L
    private var lastEventAt = 0L
    private var lastStamp = Long.MIN_VALUE

    /** A take is running. */
    @Volatile
    var recording = false
        private set

    /** Events captured in the take running (0 when none). */
    val events: Int get() = synchronized(lock) { count }

    /** Starts a take (nothing when one runs); false when one was running. */
    fun start(): Boolean {
        synchronized(lock) {
            if (recording) return false
            count = 0
            startedAt = nanoTime()
            lastEventAt = startedAt
            lastStamp = Long.MIN_VALUE
            recording = true
            return true
        }
    }

    /** A key of the Keys screen down ([velocity] as touched) or up. */
    fun screenKey(down: Boolean, key: Int, velocity: Int) {
        if (!recording) return
        synchronized(lock) { add(nanoTime(), SCREEN, if (down) KeyEvents.DOWN else KeyEvents.UP, key, if (down) velocity else 0) }
    }

    /** The Keys screen's latching sustain: CC64 127 or 0. */
    fun screenSustain(down: Boolean) {
        if (!recording) return
        synchronized(lock) { add(nanoTime(), SCREEN, KeyEvents.PEDAL, SUSTAIN, if (down) 127 else 0) }
    }

    override fun onKeys(events: KeyEvents, arrivalNanos: Long, stampNanos: Long) {
        if (!recording) return
        synchronized(lock) {
            if (!recording) return
            val stamped = stampNanos in (arrivalNanos - STAMP_WINDOW_NANOS)..arrivalNanos && stampNanos >= lastStamp
            val at = if (stamped) stampNanos else arrivalNanos
            lastStamp = maxOf(lastStamp, at)
            for (i in 0 until events.size) add(at, KEYBOARD, events.type(i), events.key(i), events.value(i))
        }
    }

    /** Whether the take should end at [now]: its length, its events, or a silence; null while it goes on. */
    fun due(now: Long = nanoTime()): TakeEnd? = synchronized(lock) {
        when {
            !recording -> null
            count >= maxEvents -> TakeEnd.Fullest
            now - startedAt >= maxNanos -> TakeEnd.Longest
            now - lastEventAt >= idleNanos -> TakeEnd.Silence
            else -> null
        }
    }

    /** How long the take has run at [now], for the Record control; 0 when none runs. */
    fun elapsedNanos(now: Long = nanoTime()): Long = synchronized(lock) { if (recording) (now - startedAt).coerceAtLeast(0L) else 0L }

    /** Ends the take ([ended] says why): its notes and pedals from time 0, or null when it holds no note. */
    fun stop(ended: TakeEnd): Take? {
        val stoppedAt = nanoTime()
        val (t, p, n) = synchronized(lock) {
            if (!recording) return null
            recording = false
            Triple(times.copyOf(count), packed.copyOf(count), count)
        }
        return build(t, p, n, stoppedAt, ended)
    }

    /** Under [lock]: one event, unless the take is full. */
    private fun add(at: Long, source: Int, type: Int, key: Int, value: Int) {
        if (count >= maxEvents) return
        if (count == times.size) {
            times = times.copyOf(count * 2)
            packed = packed.copyOf(count * 2)
        }
        times[count] = at
        packed[count] = (source shl 24) or ((type and 0xFF) shl 16) or ((key and 0x7F) shl 8) or (value and 0x7F)
        count++
        if (at > lastEventAt) lastEventAt = at
    }

    private fun build(times: LongArray, packed: IntArray, count: Int, stoppedAt: Long, ended: TakeEnd): Take? {
        val order = (0 until count).sortedBy { times[it] }   // stable: the two sources' events keep their order at one time
        val first = order.firstOrNull()?.let { times[it] } ?: return null
        val notes = ArrayList<SmfWriter.Note>()
        val controls = ArrayList<SmfWriter.Control>()
        val openAt = LongArray(SOURCES * 128) { NOT_OPEN }
        val openVelocity = IntArray(SOURCES * 128)
        val pedals = IntArray(128)
        var last = first
        for (i in order) {
            val at = times[i] - first
            last = maxOf(last, times[i])
            val event = packed[i]
            val source = event ushr 24
            val key = (event ushr 8) and 0x7F
            val value = event and 0x7F
            val slot = source * 128 + key
            when ((event ushr 16) and 0xFF) {
                KeyEvents.DOWN -> {
                    if (openAt[slot] != NOT_OPEN) notes += SmfWriter.Note(openAt[slot] / 1000, at / 1000, key, openVelocity[slot])   // struck again
                    openAt[slot] = at
                    openVelocity[slot] = value.coerceIn(1, 127)
                }
                KeyEvents.UP -> if (openAt[slot] != NOT_OPEN) {
                    notes += SmfWriter.Note(openAt[slot] / 1000, at / 1000, key, openVelocity[slot])
                    openAt[slot] = NOT_OPEN
                }
                KeyEvents.PEDAL -> {
                    controls += SmfWriter.Control(at / 1000, key, value)
                    pedals[key] = value
                }
            }
        }
        val anyOpen = openAt.any { it != NOT_OPEN } || pedals.any { it > 0 }
        val end = (if (anyOpen) maxOf(stoppedAt, last) else last) - first
        for (slot in openAt.indices) {
            if (openAt[slot] != NOT_OPEN) notes += SmfWriter.Note(openAt[slot] / 1000, end / 1000, slot % 128, openVelocity[slot])
        }
        for (controller in pedals.indices) if (pedals[controller] > 0) controls += SmfWriter.Control(end / 1000, controller, 0)
        if (notes.isEmpty()) return null
        notes.sortWith(compareBy({ it.onMicros }, { it.key }))
        return Take(notes, controls, end / 1000, ended)
    }

    companion object {
        /** The longest take: an hour. */
        const val MAX_NANOS = 60 * 60 * 1_000_000_000L

        /** The most events a take holds (about a megabyte of MIDI file). */
        const val MAX_EVENTS = 200_000

        /** Nothing played for five minutes ends a take. */
        const val IDLE_NANOS = 5 * 60 * 1_000_000_000L

        /** A keyboard's own stamp counts when it is at most this much older than the bytes' arrival. */
        const val STAMP_WINDOW_NANOS = 250_000_000L

        private const val SCREEN = 0
        private const val KEYBOARD = 1
        private const val SOURCES = 2
        private const val SUSTAIN = 64
        private const val INITIAL = 1_024
        private const val NOT_OPEN = Long.MIN_VALUE
    }
}

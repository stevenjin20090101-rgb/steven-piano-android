// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.instruments

import dev.stevenjin.stevenpiano.midi.KeyEvents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the keyboard's keys go to the instrument through (v1.11 — M29): the player, or a recorder in tests. */
interface LivePlayer {
    /** One buffer's events, to play now. Any thread. */
    fun external(events: KeyEvents, arrivalNanos: Long)

    /** Lets go of every key the keyboard holds through the player, and puts its pedal back. Any thread. */
    fun silenceExternal()
}

/** Why Live switched itself off: the flood breaker. */
enum class LiveTrip {
    /** More than [LiveThru.MAX_NOTES_PER_SECOND] Note Ons in a second. */
    TooManyNotes,

    /** [LiveThru.MAX_HELD] keys held at once. */
    TooManyKeys,

    /** More than [LiveThru.MAX_MALFORMED_PER_SECOND] malformed bytes in a second. */
    Garbled,
}

/** Live as the Keys tab shows it. */
data class LiveState(
    /** The Live switch, as remembered. */
    val wanted: Boolean = false,
    /** The keyboard's keys go to the instrument now. */
    val open: Boolean = false,
    /** Why Live switched itself off, until the person turns it on again. */
    val tripped: LiveTrip? = null,
    /** The keyboard is the instrument too: Live stays off, or every key would play twice (or loop). */
    val looped: Boolean = false,
)

/**
 * The gate between the MIDI keyboard and the instrument (v1.11 — M29). It is open only while all of these
 * hold: the Live switch is on ([setWanted]); the Keys tab is on screen with the app in the foreground
 * ([setOnScreen]); the keyboard is connected ([setKeyboard]); something can play (the instrument
 * connected, or the tablet's own piano sound; [setTarget]); the keyboard is not the instrument too
 * ([setLooped]); the breaker has not tripped. Whenever it closes, everything the keyboard holds through the
 * player lets go ([LivePlayer.silenceExternal]).
 *
 * Fresh presses only: keys and pedals already down when it opens are ignored until pressed again, and a
 * Note Off goes only for a Note On it let through. The flood breaker: more than [MAX_NOTES_PER_SECOND] Note
 * Ons in a second, [MAX_HELD] keys held at once, or more than [MAX_MALFORMED_PER_SECOND] malformed bytes in a
 * second let go of everything, switch Live off ([onTrip] saves that) and say why ([LiveState.tripped]); it is
 * re-armed by hand, when the person turns Live on again.
 *
 * Runs on the keyboard's port thread (under the keyboard's lock, then its own) and the main thread; it never
 * calls back into the keyboard, so the two locks are always taken in that order.
 */
class LiveThru(
    private val player: LivePlayer,
    private val log: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onTrip: (LiveTrip) -> Unit = {},
) : KeyboardListener {
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private val lock = Any()
    private var wanted = false
    private var onScreen = false
    private var target = false
    private var keyboard = false
    private var looped = false
    private var tripped: LiveTrip? = null
    private var open = false

    /** Keys let through and not yet let go, by note. */
    private val forwarded = BooleanArray(128)
    private var held = 0

    /** The keyboard's pedals as it last said (CC64, CC66, CC67), whether the gate was open or not. */
    private val pedalNow = IntArray(128)

    /** Per pedal: let through. A pedal down when the gate opened is not, until it has come up. */
    private val armed = BooleanArray(128)

    /** The times of the last Note Ons, a ring of [MAX_NOTES_PER_SECOND]. */
    private val noteTimes = LongArray(MAX_NOTES_PER_SECOND)
    private var noteNext = 0
    private var notesSeen = 0
    private var malformedSince = 0L
    private var malformedCount = 0L
    private val out = KeyEvents()

    /** The Live switch: the person's (the pill, or the setting at start). Turning it on re-arms a tripped breaker. */
    fun setWanted(on: Boolean) = change {
        wanted = on
        if (on) tripped = null
    }

    /** The Keys tab on screen, with the app in the foreground. */
    fun setOnScreen(on: Boolean) = change { onScreen = on }

    /** Something can play the keys: the instrument connected, or the tablet's own piano sound on. */
    fun setTarget(ready: Boolean) = change { target = ready }

    /** The keyboard is connected. */
    fun setKeyboard(connected: Boolean) = change { keyboard = connected }

    /** The keyboard is the instrument too. */
    fun setLooped(same: Boolean) = change { looped = same }

    private inline fun change(block: () -> Unit) {
        synchronized(lock) {
            block()
            settle()
        }
    }

    /** Opens or closes the gate as the conditions now say; under [lock]. */
    private fun settle() {
        val should = wanted && onScreen && target && keyboard && !looped && tripped == null
        if (should && !open) {
            open = true
            forwarded.fill(false)
            held = 0
            for (controller in PEDALS) armed[controller] = pedalNow[controller] == 0
            notesSeen = 0
            malformedCount = 0
            log("Live: on")
        } else if (!should && open) {
            close(
                when {
                    !wanted -> "switched off"
                    !onScreen -> "the Keys tab left the screen"
                    !keyboard -> "the keyboard went"
                    !target -> "nothing to play on"
                    else -> "the keyboard is the instrument"
                },
            )
        }
        _state.value = LiveState(wanted, open, tripped, looped)
    }

    private fun close(reason: String) {
        open = false
        forwarded.fill(false)
        held = 0
        player.silenceExternal()
        log("Live: off ($reason)")
    }

    override fun onKeys(events: KeyEvents, arrivalNanos: Long, stampNanos: Long) {
        synchronized(lock) { pass(events, arrivalNanos) }
    }

    /** What of [events] goes through; under [lock]. */
    private fun pass(events: KeyEvents, arrivalNanos: Long) {
        for (i in 0 until events.size) {
            if (events.type(i) == KeyEvents.PEDAL) pedalNow[events.key(i)] = events.value(i)
        }
        if (!open) return
        out.clear()
        for (i in 0 until events.size) {
            val key = events.key(i)
            val value = events.value(i)
            when (events.type(i)) {
                KeyEvents.DOWN -> {
                    if (floodOfNotes(arrivalNanos)) return trip(LiveTrip.TooManyNotes)
                    if (!forwarded[key]) {
                        forwarded[key] = true
                        if (++held >= MAX_HELD) return trip(LiveTrip.TooManyKeys)
                    }
                    out.add(KeyEvents.DOWN, key, value)
                }
                KeyEvents.UP -> if (forwarded[key]) {
                    forwarded[key] = false
                    held--
                    out.add(KeyEvents.UP, key, 0)
                }
                KeyEvents.PEDAL -> when {
                    armed[key] -> out.add(KeyEvents.PEDAL, key, value)
                    value == 0 -> armed[key] = true   // up at last: its next change goes
                }
            }
        }
        if (!out.isEmpty()) player.external(out, arrivalNanos)
    }

    override fun onLetGo(reason: String) {
        synchronized(lock) {
            pedalNow.fill(0)
            if (!open) return
            forwarded.fill(false)
            held = 0
            player.silenceExternal()
        }
    }

    /** [count] malformed bytes in one buffer: a burst of them trips the breaker. */
    override fun onMalformed(count: Long) {
        synchronized(lock) {
            if (!open || count <= 0) return
            val now = nanoTime()
            if (now - malformedSince > NANOS_PER_SECOND) {
                malformedSince = now
                malformedCount = 0
            }
            malformedCount += count
            if (malformedCount > MAX_MALFORMED_PER_SECOND) trip(LiveTrip.Garbled)
        }
    }

    /** One more Note On at [now]: whether it is past [MAX_NOTES_PER_SECOND] in a second. */
    private fun floodOfNotes(now: Long): Boolean {
        val oldest = noteTimes[noteNext]
        noteTimes[noteNext] = now
        noteNext = (noteNext + 1) % MAX_NOTES_PER_SECOND
        notesSeen++
        return notesSeen > MAX_NOTES_PER_SECOND && now - oldest < NANOS_PER_SECOND
    }

    /** The breaker: everything lets go, Live goes off and says why, until the person turns it on again. Under [lock]. */
    private fun trip(reason: LiveTrip) {
        tripped = reason
        wanted = false
        close("the flood breaker: " + when (reason) {
            LiveTrip.TooManyNotes -> "more than $MAX_NOTES_PER_SECOND notes in a second"
            LiveTrip.TooManyKeys -> "$MAX_HELD keys held at once"
            LiveTrip.Garbled -> "more than $MAX_MALFORMED_PER_SECOND malformed bytes in a second"
        })
        _state.value = LiveState(wanted, open, tripped, looped)
        onTrip(reason)
    }

    companion object {
        /** Note Ons a second past which the breaker trips (to be tuned on the piano). */
        const val MAX_NOTES_PER_SECOND = 200

        /** Keys held at once at which the breaker trips (to be tuned on the piano). */
        const val MAX_HELD = 32

        /** Malformed bytes a second past which the breaker trips. */
        const val MAX_MALFORMED_PER_SECOND = 64L

        private const val NANOS_PER_SECOND = 1_000_000_000L
        private val PEDALS = intArrayOf(64, 66, 67)
    }
}

// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio.compose

import dev.stevenjin.stevenpiano.midi.MidiPiece
import dev.stevenjin.stevenpiano.midi.SmfWriter
import kotlin.math.abs
import kotlin.math.min

/**
 * The Anticipatory Music Transformer's vocabulary (v1.7 — M24): the `anticipation` package at af37397
 * (`vocab.py`, `config.py`), as `tools/studio/fixtures/composer_seed.json` records it
 * (docs/STUDIO_SPIKE.md › The contract for M23 and M24). Music is a run of events of three tokens each,
 * in arrival-time order: a time, a duration and a note. Times count 10 ms ticks from the context's
 * origin, durations 10 ms ticks, and a note is instrument × 128 + pitch (the piano is instrument 0,
 * drums 128). The control block (anticipated events) and the special tokens are never generated; a
 * sequence without controls starts with [AUTOREGRESS].
 */
object Amt {
    const val TIME_OFFSET = 0
    const val DUR_OFFSET = 10_000
    const val NOTE_OFFSET = 11_000

    /** The note of a padding event (time, 0, REST): the package puts one wherever a second passes without an event. */
    const val REST = 27_512
    const val CONTROL_OFFSET = 27_513
    const val SPECIAL_OFFSET = 55_025
    const val SEPARATOR = 55_025
    const val AUTOREGRESS = 55_026
    const val ANTICIPATE = 55_027
    const val VOCAB_SIZE = 55_028

    /** Time tokens 0–9,999: 100 s from the context's origin. */
    const val MAX_TIME = 10_000

    /** Durations 0–999: up to 9.99 s; a longer note is cut to that. */
    const val MAX_DUR = 1_000
    const val MAX_PITCH = 128
    const val MAX_INSTR = 129
    const val MAX_NOTE = MAX_PITCH * MAX_INSTR
    const val TICKS_PER_SECOND = 100

    /** The model's positions: the flag and 341 events. */
    const val CONTEXT = 1_024
    const val EVENT_TOKENS = 3

    /** The piano's notes, instrument 0: 11,000–11,127. */
    const val PIANO_FIRST = NOTE_OFFSET
    const val PIANO_LAST = NOTE_OFFSET + MAX_PITCH - 1

    /** A rest as an [AmtEvent]'s note. */
    const val REST_NOTE = REST - NOTE_OFFSET

    /** A note whose end the file never gives lasts 250 ms, as the package times it. */
    const val UNKNOWN_DURATION = TICKS_PER_SECOND / 4

    fun isTime(token: Int): Boolean = token in TIME_OFFSET until TIME_OFFSET + MAX_TIME

    fun isDuration(token: Int): Boolean = token in DUR_OFFSET until DUR_OFFSET + MAX_DUR

    /** A note of any instrument, or [REST]. */
    fun isNote(token: Int): Boolean = token in NOTE_OFFSET..REST
}

/**
 * One arrival-time event: an onset [time] and a [duration] in 10 ms ticks, and its [note] =
 * instrument × 128 + pitch, or [Amt.REST_NOTE] for a rest (the package's padding, duration 0).
 */
data class AmtEvent(val time: Int, val duration: Int, val note: Int) {
    val isRest: Boolean get() = note == Amt.REST_NOTE
    val pitch: Int get() = note % Amt.MAX_PITCH
    val instrument: Int get() = note / Amt.MAX_PITCH

    companion object {
        fun rest(time: Int) = AmtEvent(time, 0, Amt.REST_NOTE)
    }
}

/**
 * A note as the `anticipation` package reads it from a MIDI file: its [on] and [off] in seconds, as
 * mido's floating-point sums give them (see [AmtTokenizer.notes]), and its MIDI [key].
 */
data class SeedNote(val on: Double, val off: Double, val key: Int)

/**
 * MIDI into the model's tokens and back (v1.7 — M24): a port of the `anticipation` package's
 * `midi_to_events` (`convert.py`: `midi_to_compound` then `compound_to_events`) and of the `ops.py`
 * helpers a prompt needs (`clip`, `pad`, `translate`, `min_time`, `max_time`), plus decoding. Pure.
 * The seed of `tools/studio/fixtures/bach_bwv846.mid` comes out token for token as the fixture has it.
 */
object AmtTokenizer {
    /** MIDI channel 10, the drums: the player leaves it out (NoteRouter), so a seed does too. */
    const val DRUM_CHANNEL = 9

    /** How far a gap's time may differ from the parser's before it counts as a tempo change (µs). */
    private const val SAME_STEP_MICROS = 2.0

    /**
     * The notes of [piece] starting within [seconds] of its first note (all of them by default),
     * every channel but the drums', timed as the package times them. The package reads a file through
     * mido, which gives each message's delta in seconds (`delta ticks × (tempo × 1e-6 / ppq)`) and adds
     * them up in floating point; `round()` of 100 × that sum is the tick, a note exactly half-way between
     * two ticks going whichever way the sum lands (bach_bwv846.mid's key 62 at 7.075 s gives 707, not
     * 708). So the time is summed the same way, over the piece's events in order. Where a gap's sum
     * disagrees with the parser's own microseconds, a tempo change fell between the two events (mido
     * splits the gap there): that gap takes the parser's time instead. A note starts at its Note On and
     * ends at its channel and key's next Note Off, or at the key's next strike (the parser's pairing);
     * one never ended lasts [Amt.UNKNOWN_DURATION], as in the package. What can still differ from the
     * package: a half-way note after a tempo change, or next to a message the parser doesn't keep
     * (a program change or text alone at its tick), can land one tick (10 ms) away; overlapping
     * strikes of one key pair as the piano plays them, not first-in first-out.
     */
    fun notes(piece: MidiPiece, seconds: Double = Double.POSITIVE_INFINITY): List<SeedNote> {
        val events = piece.events
        val tempo = piece.tempoMap
        val ons = ArrayList<Double>()
        val offs = ArrayList<Double>()
        val keys = ArrayList<Int>()
        val open = IntArray(16 * 128) { -1 }
        var openCount = 0
        var clock = 0.0
        var lastTick = 0L
        var lastMicros = 0L
        var first = Double.NaN
        var past = false
        for (i in 0 until events.size) {
            val at = events.atMicros(i)
            val tick = tempo.microsToTicks(at)
            if (tick > lastTick) {
                val step = (tick - lastTick) * (tempo.tempoAt(lastTick) * 1e-6 / tempo.ppq)
                val parser = (at - lastMicros).toDouble()
                clock += if (abs(step * 1e6 - parser) <= SAME_STEP_MICROS) step else parser / 1e6
                lastTick = tick
                lastMicros = at
            }
            val command = events.command(i)
            if (command != 0x90 && command != 0x80) continue
            val channel = events.channel(i)
            if (channel == DRUM_CHANNEL) continue
            val source = channel * 128 + events.data1(i)
            if (open[source] >= 0) {
                offs[open[source]] = clock
                open[source] = -1
                openCount--
            }
            if (command == 0x90) {
                if (first.isNaN()) first = clock
                if (clock - first <= seconds) {
                    open[source] = ons.size
                    openCount++
                    ons += clock
                    offs += Double.NaN
                    keys += events.data1(i)
                } else {
                    past = true
                }
            }
            if (past && openCount == 0) break
        }
        return List(ons.size) { i ->
            val off = offs[i]
            SeedNote(ons[i], if (off.isNaN()) ons[i] + Amt.UNKNOWN_DURATION.toDouble() / Amt.TICKS_PER_SECOND else off, keys[i])
        }
    }

    /**
     * [notes] as events, the package's `compound_to_events`: the onset (less [origin], times [scale])
     * and the duration (times [scale]) rounded to 10 ms ticks as Python rounds, halves to even; a
     * duration past 9.99 s cut to 999 ticks; every note the piano's (instrument 0). With [origin] 0
     * and [scale] 1 the arithmetic is the package's own.
     */
    fun events(notes: List<SeedNote>, origin: Double = 0.0, scale: Double = 1.0): List<AmtEvent> = notes.map { n ->
        val duration = min(ticks((n.off - n.on) * scale), Amt.MAX_DUR - 1).coerceAtLeast(0)
        AmtEvent(ticks((n.on - origin) * scale), duration, n.key)
    }

    /** [seconds] as 10 ms ticks, rounded as Python's `round()` rounds: halves to even. */
    fun ticks(seconds: Double): Int = Math.rint(seconds * Amt.TICKS_PER_SECOND).toInt()

    /** The events from [start] to [end] ticks, both included; durations as they were (`ops.clip`, `clip_duration=False`). */
    fun clip(events: List<AmtEvent>, start: Int, end: Int): List<AmtEvent> = events.filter { it.time in start..end }

    /**
     * `ops.pad`: a rest wherever more than [density] ticks (a second) pass without an event, and after
     * the last one up to [end], so the model never meets a longer silence than it was trained on.
     */
    fun pad(events: List<AmtEvent>, end: Int = maxTime(events), density: Int = Amt.TICKS_PER_SECOND): List<AmtEvent> {
        val out = ArrayList<AmtEvent>(events.size + 8)
        var previous = 0
        for (e in events) {
            while (e.time > previous + density) {
                previous += density
                out += AmtEvent.rest(previous)
            }
            out += e
            previous = e.time
        }
        while (end > previous + density) {
            previous += density
            out += AmtEvent.rest(previous)
        }
        return out
    }

    /** The events without their rests (`ops.unpad`). */
    fun unpad(events: List<AmtEvent>): List<AmtEvent> = events.filterNot { it.isRest }

    /** Every event [dt] ticks later (earlier when negative); none may move before 0. */
    fun translate(events: List<AmtEvent>, dt: Int): List<AmtEvent> = events.map {
        require(it.time + dt >= 0) { "an event would move before 0" }
        it.copy(time = it.time + dt)
    }

    /** The latest onset; 0 for none (`ops.max_time`). */
    fun maxTime(events: List<AmtEvent>): Int = events.maxOfOrNull { it.time } ?: 0

    /** The earliest onset; 0 for none (`ops.min_time`). */
    fun minTime(events: List<AmtEvent>): Int = events.minOfOrNull { it.time } ?: 0

    /** The events as tokens, three each; every time within 0–9,999, duration within 0–999, note a note or a rest. */
    fun tokens(events: List<AmtEvent>): IntArray {
        val out = IntArray(events.size * Amt.EVENT_TOKENS)
        for ((i, e) in events.withIndex()) {
            require(e.time in 0 until Amt.MAX_TIME) { "time ${e.time} is outside the vocabulary" }
            require(e.duration in 0 until Amt.MAX_DUR) { "duration ${e.duration} is outside the vocabulary" }
            require(e.note in 0..Amt.REST_NOTE) { "note ${e.note} is outside the vocabulary" }
            out[3 * i] = Amt.TIME_OFFSET + e.time
            out[3 * i + 1] = Amt.DUR_OFFSET + e.duration
            out[3 * i + 2] = Amt.NOTE_OFFSET + e.note
        }
        return out
    }

    /** A prompt: [Amt.AUTOREGRESS], then the events' tokens. */
    fun prompt(events: List<AmtEvent>): IntArray = intArrayOf(Amt.AUTOREGRESS) + tokens(events)

    /**
     * [tokens] back into events: a leading [Amt.AUTOREGRESS] is skipped, then whole triples of a time,
     * a duration and a note (or rest). Anything else (a control, a special token, a partial event) is
     * refused.
     */
    fun decode(tokens: IntArray): List<AmtEvent> {
        val start = if (tokens.isNotEmpty() && tokens[0] == Amt.AUTOREGRESS) 1 else 0
        require((tokens.size - start) % Amt.EVENT_TOKENS == 0) { "${tokens.size - start} tokens are not whole events" }
        val out = ArrayList<AmtEvent>((tokens.size - start) / Amt.EVENT_TOKENS)
        var i = start
        while (i < tokens.size) {
            val (t, d, n) = Triple(tokens[i], tokens[i + 1], tokens[i + 2])
            require(Amt.isTime(t)) { "token $i ($t) is not a time" }
            require(Amt.isDuration(d)) { "token ${i + 1} ($d) is not a duration" }
            require(Amt.isNote(n)) { "token ${i + 2} ($n) is not a note" }
            out += AmtEvent(t - Amt.TIME_OFFSET, d - Amt.DUR_OFFSET, n - Amt.NOTE_OFFSET)
            i += Amt.EVENT_TOKENS
        }
        return out
    }

    /**
     * The events' notes for [SmfWriter], rests and drums left out: onsets and ends to the microsecond
     * (10,000 per tick), keys as the model gave them, every one at [velocity] until [Postprocess]
     * shapes them (72 is the package's own `events_to_compound` default).
     */
    fun toNotes(events: List<AmtEvent>, velocity: Int = DEFAULT_VELOCITY): List<SmfWriter.Note> =
        events.filter { !it.isRest && it.instrument < Amt.MAX_INSTR - 1 }.map {
            SmfWriter.Note(it.time * MICROS_PER_TICK, (it.time + it.duration) * MICROS_PER_TICK, it.pitch, velocity)
        }

    const val DEFAULT_VELOCITY = 72
    const val MICROS_PER_TICK = 10_000L
}

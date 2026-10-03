// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.player

import dev.stevenjin.stevenpiano.midi.EventList
import dev.stevenjin.stevenpiano.midi.MidiBatch
import dev.stevenjin.stevenpiano.midi.MidiPiece
import dev.stevenjin.stevenpiano.midi.NoteRouter

/** How far soft and loud notes spread apart (Piano › Playback › Dynamic range, v1.16 — M44): [spread] times their distance from the mean. */
enum class DynamicRange(val spread: Double) {
    NARROW(0.7),
    NATURAL(1.0),
    WIDE(1.3),
}

/** How much a piece is shaped as a pianist would (Piano › Playback › Expression, v1.16 — M44): Light at first, Full twice as much. */
enum class ExpressionLevel {
    OFF,
    LIGHT,
    FULL,
}

/**
 * The four Playback settings that shape how a piece is played (v1.16 — M44), as the player takes them; changed while a
 * piece plays, they shape the next one. [velocityFloor] 1–60; [restrikeMs] [Performance.AUTO], or 60–250 ms.
 */
data class PerformanceSettings(
    val dynamicRange: DynamicRange = DynamicRange.NATURAL,
    val velocityFloor: Int = Performance.DEFAULT_FLOOR,
    val expression: ExpressionLevel = ExpressionLevel.LIGHT,
    val restrikeMs: Int = Performance.AUTO,
)

/**
 * What the pre-pass is told of the instrument (v1.16 — M44): [repeatMs], the piano's own steady repeat period (its fact
 * `!repeatms`, `minstrike + gap + 20 ms`: 110 ms at its defaults, 40 ms with Snappy) while a piano that reports one is
 * connected; [freeRepeats], an instrument that strikes a key again whenever it is asked (a MIDI piano), whose repeats
 * stay as the file has them.
 */
data class PianoFacts(val repeatMs: Int? = null, val freeRepeats: Boolean = false) {
    companion object {
        /** The facts as the piano reports `!repeatms` ([text], null with no piano or no such fact); anything unreadable is none. */
        fun read(text: String?): PianoFacts = PianoFacts(text?.trim()?.toIntOrNull()?.takeIf { it in 1..MAX_REPEAT_MS })

        /** Longer than any the firmware can report (500 + 300 + 20 ms). */
        private const val MAX_REPEAT_MS = 2_000
    }
}

/**
 * How a piece is played (DESIGN.md › v1.16 — M44): one pure pre-pass over the events a piece sends to the instrument,
 * run once as the piece loads, after its hands are known. In order: (a) [Expression], (b) [Dynamics], (c) [Repeats]. It
 * shapes what sounds alone: the notes the roll and the score draw, the hands, the fingering and the chords stay the
 * file's, and so do the pedal's times and the piece's length. Only a piece's notes: the Keys screen and keyboards go
 * through the router as before. A note on the drum channel is left as it is.
 */
object Performance {
    /** Re-strike time Auto: the piano's own repeat period, else [DEFAULT_RESTRIKE_MS]. */
    const val AUTO = 0

    /** Quietest note, at first. */
    const val DEFAULT_FLOOR = 20

    /** The re-strike time with no piano, or none that reports its repeat period. */
    const val DEFAULT_RESTRIKE_MS = 100

    /** The re-strike time T in ms: the setting, or Auto, the piano's own repeat period when it reports one, else 100. */
    fun restrikeMs(settings: PerformanceSettings, facts: PianoFacts): Int =
        if (settings.restrikeMs > 0) settings.restrikeMs else facts.repeatMs ?: DEFAULT_RESTRIKE_MS

    /**
     * [piece] as it is to be played with [settings] on the instrument [facts] describe: the same piece, its events
     * shaped (the piece itself when nothing changes). [hands]: one per note of `piece.notes` (`score.Hands`), or none.
     * [checkpoint] is called between the parts; it throws to stop the work (a newer piece replacing this one).
     */
    fun shape(
        piece: MidiPiece,
        hands: ByteArray?,
        settings: PerformanceSettings,
        facts: PianoFacts,
        checkpoint: () -> Unit = {},
    ): MidiPiece {
        val notes = NoteTable.read(piece.events) ?: return piece
        val restrike = restrikeMs(settings, facts) * MICROS_PER_MS
        checkpoint()
        Expression.shape(notes, hands, piece, settings.expression, Repeats.releaseGapMicros(restrike))
        checkpoint()
        Dynamics.shape(notes, settings.dynamicRange, settings.velocityFloor)
        if (!facts.freeRepeats) Repeats.shape(notes, restrike)
        checkpoint()
        return if (notes.changed()) piece.withEvents(notes.events()) else piece
    }

    private const val MICROS_PER_MS = 1_000L
}

/**
 * A piece's notes as the pre-pass shapes them (v1.16 — M44). Note i is the i-th Note On of the events, ended as
 * `SmfParser.pairNotes` ends it (its Note Off, a strike of its channel and key again, else the last event), so the hands
 * (one per note of `MidiPiece.notes`) line up with it. The parts change [onset], [end], [velocity] and [kept] in place,
 * beside the file's own [fileOnset], [fileEnd] and [fileVelocity]; [events] writes them back.
 */
internal class NoteTable private constructor(
    private val source: EventList,
    val fileOnset: LongArray,
    val fileEnd: LongArray,
    val key: IntArray,
    val channel: IntArray,
    val fileVelocity: IntArray,
) {
    val size: Int get() = fileOnset.size
    val onset: LongArray = fileOnset.copyOf()
    val end: LongArray = fileEnd.copyOf()
    val velocity: IntArray = fileVelocity.copyOf()

    /** False for a note [Repeats] leaves out. */
    val kept = BooleanArray(size) { true }

    /** When the piece's last event sounds: no note ends past it, so the piece lasts as long as it did. */
    val lastMicros: Long = source.lastMicros

    /** Whether note [i] is one the parts shape: a strike, off the drum channel. */
    fun shapes(i: Int): Boolean = fileVelocity[i] > 0 && channel[i] != NoteRouter.DRUM_CHANNEL

    /** Whether any part changed anything. */
    fun changed(): Boolean =
        !(onset.contentEquals(fileOnset) && end.contentEquals(fileEnd) && velocity.contentEquals(fileVelocity) && kept.all { it })

    /**
     * The notes kept, each with its key and channel, grouped by key and each key's in the order they now start (the
     * table's order where two start together): what [Repeats] walks, and how [Expression] finds a key's next onset.
     */
    fun keyGroups(): KeyGroups {
        val count = IntArray(KEYS + 1)
        for (i in 0 until size) if (kept[i] && shapes(i)) count[key[i] + 1]++
        for (k in 1..KEYS) count[k] += count[k - 1]
        val notes = IntArray(count[KEYS])
        val fill = count.copyOf()
        for (i in 0 until size) if (kept[i] && shapes(i)) notes[fill[key[i]]++] = i
        for (k in 0 until KEYS) {
            // Insertion sort, stable: the notes are in time order already but for shifts of a few milliseconds.
            for (j in count[k] + 1 until count[k + 1]) {
                val note = notes[j]
                var at = j
                while (at > count[k] && onset[notes[at - 1]] > onset[note]) {
                    notes[at] = notes[at - 1]
                    at--
                }
                notes[at] = note
            }
        }
        return KeyGroups(notes, count)
    }

    /**
     * The events, the notes as they now are: each kept note's Note On at its onset with its velocity and its Note Off
     * at its end, the controllers (the pedal) at their own times, sorted as the parser sorts them (a controller, then
     * a Note Off, then a Note On at one time; otherwise in the file's order). A note the file ended by striking its key
     * again, or never ended, gets a Note Off of its own; a Note Off that ended nothing goes. Should every note now end
     * before the piece's last event, a Note Off of a key nothing holds then marks the end, so the piece lasts as long.
     */
    fun events(): EventList {
        val capacity = source.size + size + 1
        val times = LongArray(capacity)
        val words = IntArray(capacity)
        val order = LongArray(capacity)
        var count = 0
        fun emit(at: Long, rank: Int, word: Int) {
            times[count] = at
            words[count] = word
            order[count] = (at shl TIME_SHIFT) or (rank.toLong() shl RANK_SHIFT) or count.toLong()
            count++
        }
        fun release(note: Int) {
            if (kept[note]) emit(end[note], RANK_OFF, MidiBatch.pack(NOTE_OFF or channel[note], key[note], 0))
        }
        val open = IntArray(SOURCES) { NONE }
        var note = 0
        for (e in 0 until source.size) {
            val status = source.status(e)
            val held = (status and 0x0F) * KEYS + source.data1(e)
            when (status and 0xF0) {
                NOTE_ON -> {
                    if (open[held] != NONE) release(open[held])
                    if (kept[note]) emit(onset[note], RANK_ON, MidiBatch.pack(status, key[note], velocity[note]))
                    open[held] = note++
                }
                NOTE_OFF -> if (open[held] != NONE) {
                    release(open[held])
                    open[held] = NONE
                }
                else -> emit(source.atMicros(e), RANK_CONTROL, MidiBatch.pack(status, source.data1(e), source.data2(e)))
            }
        }
        open.filter { it != NONE }.sorted().forEach(::release)   // never ended: they end with the piece
        var latest = Long.MIN_VALUE
        for (k in 0 until count) latest = maxOf(latest, times[k])
        if (count > 0 && latest < lastMicros) emit(lastMicros, RANK_OFF, MidiBatch.pack(NOTE_OFF or channel[0], key[0], 0))
        order.sort(0, count)
        val micros = LongArray(count)
        val packed = IntArray(count)
        for (k in 0 until count) {
            val i = (order[k] and INDEX_MASK).toInt()
            micros[k] = times[i]
            packed[k] = words[i]
        }
        return EventList(micros, packed, count)
    }

    /** [notes] key by key: key k's from [starts] k until [starts] k + 1. */
    class KeyGroups(val notes: IntArray, val starts: IntArray)

    companion object {
        const val KEYS = 128
        private const val SOURCES = 16 * KEYS
        private const val NONE = -1
        private const val NOTE_ON = 0x90
        private const val NOTE_OFF = 0x80
        private const val RANK_CONTROL = 0
        private const val RANK_OFF = 1
        private const val RANK_ON = 2

        /** An event's sort key: its time (under a day: 37 bits), its rank (2), its place as written (23: four million and more). */
        private const val TIME_SHIFT = 25
        private const val RANK_SHIFT = 23
        private const val INDEX_MASK = (1L shl RANK_SHIFT) - 1

        /** The notes of [events], paired as the parser pairs them; null when there is none. */
        fun read(events: EventList): NoteTable? {
            var count = 0
            for (e in 0 until events.size) if (events.command(e) == NOTE_ON) count++
            if (count == 0) return null
            val starts = LongArray(count)
            val ends = LongArray(count)
            val keys = IntArray(count)
            val channels = IntArray(count)
            val velocities = IntArray(count)
            val open = IntArray(SOURCES) { NONE }
            var n = 0
            for (e in 0 until events.size) {
                val at = events.atMicros(e)
                val held = events.channel(e) * KEYS + events.data1(e)
                when (events.command(e)) {
                    NOTE_ON -> {
                        if (open[held] != NONE) ends[open[held]] = at
                        starts[n] = at
                        ends[n] = -1L
                        keys[n] = events.data1(e)
                        channels[n] = events.channel(e)
                        velocities[n] = events.data2(e)
                        open[held] = n++
                    }
                    NOTE_OFF -> if (open[held] != NONE) {
                        ends[open[held]] = at
                        open[held] = NONE
                    }
                }
            }
            for (i in 0 until n) if (ends[i] < 0) ends[i] = events.lastMicros
            return NoteTable(events, starts, ends, keys, channels, velocities)
        }
    }
}

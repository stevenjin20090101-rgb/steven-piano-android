// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.midi

/**
 * A parsed Standard MIDI File, flattened onto one microsecond timeline. The score's view of time
 * travels beside it: the [tempoMap] back to ticks and beats, the time and key signatures (kept
 * apart from [events], which carry only what the piano plays), and where each bar starts.
 */
class MidiPiece(
    val format: Int,
    val ppq: Int,
    /** Every track-name meta (FF 03) of Track 0, in file order. */
    val sequenceNames: List<String>,
    /** Text metas (FF 01) of Track 0. */
    val texts: List<String>,
    val copyright: String?,
    /** Time of the last event: when the music, pedal included, is over. */
    val durationMicros: Long,
    /** Note On (velocity > 0), Note Off and Control Change, sorted; off before on at equal times. */
    val events: EventList,
    val notes: NoteList,
    /** Damage the parser worked around, in plain English. Empty for a healthy file. */
    val warnings: List<String>,
    /** Ticks and microseconds as the parser timed them, for the score's bars and beats. */
    val tempoMap: TempoMap,
    /** Time signatures in time order, never empty: 4/4 from the start until the file says otherwise. */
    val timeSignatures: List<TimeSignature>,
    /** Key signatures in time order; empty when the file has none (the score then spells in sharps). */
    val keySignatures: List<KeySignature>,
    /** Where each bar starts, in microseconds: bar 1 at 0 (see [Bars]). */
    val barStartsMicros: LongArray,
    /**
     * Each track's name, one per track read (0-based, in file order, as [NoteList.track] counts
     * them): its first track-name meta (FF 03), or "" when it has none. The hands are read from
     * these ("Piano right", "Piano left") when a file names them.
     */
    val trackNames: List<String> = emptyList(),
) {
    val sequenceName: String? get() = sequenceNames.firstOrNull()
    val noteCount: Int get() = notes.size

    /**
     * The same piece sounding [events] instead (v1.16 — M44: the player's pre-pass, `player.Performance`): its notes,
     * which the roll and the score draw, its time and its length stay the file's.
     */
    fun withEvents(events: EventList): MidiPiece = MidiPiece(
        format, ppq, sequenceNames, texts, copyright, durationMicros, events, notes, warnings, tempoMap,
        timeSignatures, keySignatures, barStartsMicros, trackNames,
    )
}

/** One channel message at an absolute song time. [status] keeps its channel nibble. */
data class TimedEvent(val atMicros: Long, val status: Int, val data1: Int, val data2: Int) {
    val command: Int get() = status and 0xF0
    val channel: Int get() = status and 0x0F
}

/**
 * A piece's events in time order, kept as two parallel primitive arrays: 12 bytes an event and
 * no object per event, so the densest file the parser accepts ([SmfParser] stops at about two
 * million events) costs tens of megabytes, not hundreds. The player reads it by index without
 * allocating ([atMicros], [status], [data1], [data2]); as a [List] it hands out [TimedEvent]s,
 * one allocated per read, for tests and tools. [micros] and [packed] may be longer than [size].
 */
class EventList internal constructor(
    private val micros: LongArray,
    /** `status << 16 | data1 << 8 | data2`, as [MidiBatch.pack] packs a message. */
    private val packed: IntArray,
    override val size: Int,
) : AbstractList<TimedEvent>() {
    init {
        require(size >= 0 && size <= micros.size && size <= packed.size) { "$size events in arrays of ${micros.size} and ${packed.size}" }
    }

    fun atMicros(i: Int): Long = micros[i]

    fun status(i: Int): Int = packed[i] ushr 16

    fun data1(i: Int): Int = (packed[i] ushr 8) and 0xFF

    fun data2(i: Int): Int = packed[i] and 0xFF

    fun command(i: Int): Int = status(i) and 0xF0

    fun channel(i: Int): Int = status(i) and 0x0F

    /** When the last event sounds: the piece's length. 0 for no events. */
    val lastMicros: Long get() = if (size == 0) 0L else micros[size - 1]

    override fun get(index: Int): TimedEvent {
        if (index !in 0 until size) throw IndexOutOfBoundsException("Event $index of $size")
        return TimedEvent(micros[index], status(index), data1(index), data2(index))
    }

    /** Index of the first event at or after [at] ([size] when there is none). */
    fun firstAtOrAfter(at: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (micros[mid] < at) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        val Empty = EventList(LongArray(0), IntArray(0), 0)

        /** [events] as an [EventList]: for tests, and for callers that already hold objects. */
        fun of(events: List<TimedEvent>): EventList = EventList(
            LongArray(events.size) { events[it].atMicros },
            IntArray(events.size) { MidiBatch.pack(events[it].status, events[it].data1, events[it].data2) },
            events.size,
        )
    }
}

/**
 * Notes as parallel arrays sorted by start time, for drawing without allocation.
 * Note numbers are the file's own; the canvas maps them through [KeyMap] at draw time.
 * [tracks] holds the track each note came from (0-based, in file order; see
 * [MidiPiece.trackNames]): not part of what the piano plays, only of who plays it.
 */
class NoteList(
    val startMicros: LongArray,
    val endMicros: LongArray,
    private val keys: ByteArray,
    private val velocities: ByteArray,
    private val channels: ByteArray,
    private val tracks: ShortArray = ShortArray(startMicros.size),
) {
    val size: Int get() = startMicros.size

    /** The longest note, so a visible window can back off far enough to catch it. */
    val maxDurationMicros: Long = (0 until size).maxOfOrNull { endMicros[it] - startMicros[it] } ?: 0L

    fun note(i: Int): Int = keys[i].toInt()
    fun velocity(i: Int): Int = velocities[i].toInt()
    fun channel(i: Int): Int = channels[i].toInt()

    /** The track note [i] came from: 0 for the first track read. */
    fun track(i: Int): Int = tracks[i].toInt()

    /** Index of the first note starting at or after [micros] ([size] when there is none). */
    fun firstStartingAtOrAfter(micros: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (startMicros[mid] < micros) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        val Empty = NoteList(LongArray(0), LongArray(0), ByteArray(0), ByteArray(0), ByteArray(0))
    }
}

/** A file the app cannot play. The message is shown to people as is. */
class SmfException(message: String) : Exception(message)

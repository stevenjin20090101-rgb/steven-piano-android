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
    val events: List<TimedEvent>,
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
) {
    val sequenceName: String? get() = sequenceNames.firstOrNull()
    val noteCount: Int get() = notes.size
}

/** One channel message at an absolute song time. [status] keeps its channel nibble. */
data class TimedEvent(val atMicros: Long, val status: Int, val data1: Int, val data2: Int) {
    val command: Int get() = status and 0xF0
    val channel: Int get() = status and 0x0F
}

/**
 * Notes as parallel arrays sorted by start time, for drawing without allocation.
 * Note numbers are the file's own; the canvas maps them through [KeyMap] at draw time.
 */
class NoteList(
    val startMicros: LongArray,
    val endMicros: LongArray,
    private val keys: ByteArray,
    private val velocities: ByteArray,
    private val channels: ByteArray,
) {
    val size: Int get() = startMicros.size

    /** The longest note, so a visible window can back off far enough to catch it. */
    val maxDurationMicros: Long = (0 until size).maxOfOrNull { endMicros[it] - startMicros[it] } ?: 0L

    fun note(i: Int): Int = keys[i].toInt()
    fun velocity(i: Int): Int = velocities[i].toInt()
    fun channel(i: Int): Int = channels[i].toInt()

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

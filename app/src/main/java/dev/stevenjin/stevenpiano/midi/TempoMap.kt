// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.midi

import kotlin.math.roundToLong

/**
 * A file's tempo map: where each tempo takes over, in ticks and in microseconds, and the tempo
 * (microseconds per quarter note) from there on. It converts exactly as [SmfParser] times events,
 * `micros = segmentMicros + (tick - segmentTick) * tempo / ppq` rounded down, so the microseconds
 * of any event come back to its own tick through [microsToTicks]. The score lays notes out in
 * ticks and beats through it (tempo changes never bend the bars), and follows the song position
 * with it. Immutable, and safe to read from any thread.
 */
class TempoMap private constructor(
    /** Ticks per quarter note. */
    val ppq: Int,
    private val ticks: LongArray,
    private val micros: LongArray,
    private val tempos: IntArray,
) {
    /** Segments: 1 for a file that never changes tempo (or sets it only at its start). */
    val size: Int get() = ticks.size

    /** When [tick] sounds, in microseconds from the start; 0 for tick 0 and before. */
    fun tickToMicros(tick: Long): Long {
        if (tick <= 0L) return 0L
        val s = segmentAtTick(tick)
        return micros[s] + (tick - ticks[s]) * tempos[s] / ppq
    }

    /** The tick at [micros], to the nearest tick: an event's microseconds give back its tick. */
    fun microsToTicks(micros: Long): Long = ticksAt(micros).roundToLong()

    /** Quarter notes from the start to [micros], with the fraction: where the music is, as the score counts. */
    fun microsToBeats(micros: Long): Double = ticksAt(micros) / ppq

    /** Microseconds per quarter note at [tick]. */
    fun tempoAt(tick: Long): Int = tempos[segmentAtTick(tick.coerceAtLeast(0L))]

    /** Fractional ticks at [micros]; past the last change the last tempo carries on. */
    fun ticksAt(micros: Long): Double {
        if (micros <= 0L) return 0.0
        val s = segmentAtMicros(micros)
        return ticks[s] + (micros - this.micros[s]).toDouble() * ppq / tempos[s]
    }

    /** The last segment starting at or before [tick]. */
    private fun segmentAtTick(tick: Long): Int {
        var lo = 0
        var hi = ticks.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (ticks[mid] <= tick) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** The last segment starting at or before [at] microseconds. */
    private fun segmentAtMicros(at: Long): Int {
        var lo = 0
        var hi = micros.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (micros[mid] <= at) lo = mid else hi = mid - 1
        }
        return lo
    }

    /**
     * Builds a map from the file's tempo changes, given in time order, timing ticks along the way:
     * [micros] is when a tick sounds under the tempos seen so far, which is how the parser times
     * each event as it merges the tracks. A change at the same tick as the one before replaces it.
     */
    class Builder(private val ppq: Int) {
        private var ticks = LongArray(8)
        private var micros = LongArray(8)
        private var tempos = IntArray(8).also { it[0] = DEFAULT_TEMPO }
        private var size = 1

        /** When [tick] sounds; ticks must not go backwards between calls. */
        fun micros(tick: Long): Long {
            val last = size - 1
            return micros[last] + (tick - ticks[last]) * tempos[last] / ppq
        }

        fun change(tick: Long, tempo: Int) {
            require(tempo > 0) { "A tempo must be positive" }
            val last = size - 1
            if (ticks[last] == tick) {
                tempos[last] = tempo   // at the same instant the later change wins; the time is the same
                return
            }
            val at = micros(tick)
            if (size == ticks.size) {
                ticks = ticks.copyOf(size * 2)
                micros = micros.copyOf(size * 2)
                tempos = tempos.copyOf(size * 2)
            }
            ticks[size] = tick
            micros[size] = at
            tempos[size] = tempo
            size++
        }

        fun build(): TempoMap = TempoMap(ppq, ticks.copyOf(size), micros.copyOf(size), tempos.copyOf(size))
    }

    companion object {
        /** 120 BPM: the tempo a file has until it says otherwise. */
        const val DEFAULT_TEMPO = 500_000

        /** One tempo throughout. */
        fun constant(ppq: Int, tempo: Int = DEFAULT_TEMPO): TempoMap =
            Builder(ppq).apply { change(0L, tempo) }.build()
    }
}

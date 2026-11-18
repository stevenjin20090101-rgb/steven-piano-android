// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.sort

import dev.stevenjin.stevenpiano.midi.NoteList
import kotlin.math.abs

/**
 * What a piece's notes say about how hard it is to play (M82: Steven's "difficulty and how difficult the song sounds and
 * most keys played together"), kept on the piece (`PieceEntity.busiestPerSecond` …) for the Library's sorts. Pure: [of]
 * reads a parsed piece's notes; the drum channel's (MIDI channel 10) are not the piano's and are left out.
 *
 * - [busiestPerSecond]: the most note-ons in any 10 s window, per second (a piece shorter than that: over its own span,
 *   a second at least).
 * - [mostKeys]: the peak count of keys held down together, from note-on to note-off (the pedal ignored; a key struck
 *   again while held is one key), and [mostKeysMoments], how many separate moments reach it.
 * - [leapsPerSecond]: in the busiest window, the semitones jumped between consecutive onsets more than 7 semitones apart,
 *   per second. Onsets within [CHORD_MICROS] of each other are one moment (a chord), and the jump between two moments is
 *   the shortest from any key of one to any key of the next: how far a hand had to travel at least.
 * - [keyRange]: the highest key minus the lowest.
 */
data class PieceStats(
    val busiestPerSecond: Double,
    val mostKeys: Int,
    val mostKeysMoments: Int,
    val leapsPerSecond: Double,
    val keyRange: Int,
) {
    companion object {
        /** What a piece with no notes (or one that can no longer be read) keeps: it is measured, and asked for no more. */
        val None = PieceStats(0.0, 0, 0, 0.0, 0)

        /** The window the busiest stretch is found in. */
        const val WINDOW_MICROS = 10_000_000L

        /** A jump of more semitones than this between consecutive onsets is a leap. */
        const val LEAP_SEMITONES = 7

        /** Onsets this close are one moment: a chord as a file plays it, its notes a few milliseconds apart. */
        const val CHORD_MICROS = 30_000L

        private const val MICROS_PER_SECOND = 1_000_000.0
        private const val DRUM_CHANNEL = 9
        private const val KEYS = 128

        fun of(notes: NoteList): PieceStats {
            val kept = (0 until notes.size).filter { notes.channel(it) != DRUM_CHANNEL }
            if (kept.isEmpty()) return None
            val starts = LongArray(kept.size) { notes.startMicros[kept[it]] }
            val ends = LongArray(kept.size) { notes.endMicros[kept[it]] }
            val keys = IntArray(kept.size) { notes.note(kept[it]) and 0x7F }
            val (peak, moments) = mostKeys(starts, ends, keys)
            val window = busiest(starts)
            return PieceStats(
                busiestPerSecond = window.count / window.seconds,
                mostKeys = peak,
                mostKeysMoments = moments,
                leapsPerSecond = leaps(starts, keys, window) / window.seconds,
                keyRange = keys.max() - keys.min(),
            )
        }

        /**
         * The peak count of keys down at once, and the moments that reach it. Every note-on and note-off in time order, the
         * offs before the ons at the same instant (a key let go as the next is struck is never two), the count read once
         * all of an instant's changes are in; a note of no length is held a microsecond.
         */
        private fun mostKeys(starts: LongArray, ends: LongArray, keys: IntArray): Pair<Int, Int> {
            // Each change as one Long: its time, then 1 for an on (so an off sorts first), then the key.
            val changes = LongArray(starts.size * 2)
            for (i in starts.indices) {
                val end = maxOf(ends[i], starts[i] + 1)
                changes[2 * i] = (starts[i] shl 8) or (1L shl 7) or keys[i].toLong()
                changes[2 * i + 1] = (end shl 8) or keys[i].toLong()
            }
            changes.sort()
            val down = IntArray(KEYS)
            var held = 0
            var peak = 0
            var moments = 0
            var atPeak = false
            var i = 0
            while (i < changes.size) {
                val at = changes[i] shr 8
                while (i < changes.size && changes[i] shr 8 == at) {
                    val key = (changes[i] and 0x7F).toInt()
                    if (changes[i] and (1L shl 7) != 0L) {
                        if (down[key]++ == 0) held++
                    } else if (down[key] > 0 && --down[key] == 0) {
                        held--
                    }
                    i++
                }
                when {
                    held > peak -> {
                        peak = held
                        moments = 1
                        atPeak = true
                    }
                    held == peak && held > 0 -> if (!atPeak) {
                        moments++
                        atPeak = true
                    }
                    else -> atPeak = false
                }
            }
            return peak to moments
        }

        /** The busiest window: where it starts, how many onsets it holds, and how many seconds it spans. */
        private class Window(val from: Long, val until: Long, val count: Int, val seconds: Double)

        /** The [WINDOW_MICROS] window, starting at an onset, that holds the most onsets ([starts] in time order). */
        private fun busiest(starts: LongArray): Window {
            var best = 0
            var bestFrom = starts[0]
            var j = 0
            for (i in starts.indices) {
                if (j < i) j = i
                while (j < starts.size && starts[j] < starts[i] + WINDOW_MICROS) j++
                if (j - i > best) {
                    best = j - i
                    bestFrom = starts[i]
                }
            }
            val span = starts.last() - starts.first()
            val seconds = if (span >= WINDOW_MICROS) WINDOW_MICROS / MICROS_PER_SECOND else maxOf(span / MICROS_PER_SECOND, 1.0)
            return Window(bestFrom, bestFrom + WINDOW_MICROS, best, seconds)
        }

        /** The semitones leapt into the moments that start inside [window], from the moment before each. */
        private fun leaps(starts: LongArray, keys: IntArray, window: Window): Double {
            var total = 0
            var previous: IntArray? = null
            var i = 0
            while (i < starts.size) {
                val first = starts[i]
                var j = i
                while (j < starts.size && starts[j] - first < CHORD_MICROS) j++
                val moment = IntArray(j - i) { keys[i + it] }
                val last = previous
                if (last != null && first >= window.from && first < window.until) {
                    val jump = nearest(last, moment)
                    if (jump > LEAP_SEMITONES) total += jump
                }
                previous = moment
                i = j
                if (first >= window.until) break
            }
            return total.toDouble()
        }

        /** The fewest semitones between any key of [a] and any key of [b]. */
        private fun nearest(a: IntArray, b: IntArray): Int {
            var best = Int.MAX_VALUE
            for (x in a) for (y in b) best = minOf(best, abs(x - y))
            return best
        }
    }
}

// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.player

import dev.stevenjin.stevenpiano.midi.MidiPiece
import dev.stevenjin.stevenpiano.score.Hands
import dev.stevenjin.stevenpiano.score.Quantize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Expression (DESIGN.md › v1.16 — M44), part (a) of [Performance]: a piece's loudness and timing shaped as a pianist
 * would, the same way every time (no randomness). The figures are Light's; Full doubles every deviation from 1 and every
 * millisecond (a 15 ms lead, a 25 ms roll and breath), the caps as stated. Off leaves the piece as it is.
 *
 * - **Clusters**: notes whose onsets lie within [CLUSTER_MICROS] of the cluster's first. **Roles**: the highest
 *   right-hand note of a cluster is the melody (with no hands known, its highest note), the lowest left-hand note the
 *   bass (with no hands, the lowest of two or more), the rest inner: × 1.08, × 0.97, × 0.92.
 * - **Phrases**: the melody notes in time order, split where the rest before one is longer than a beat and
 *   [PHRASE_GAP_MICROS]. A phrase of three notes or more swells, × (1 + 0.12 sin πt), t from 0 at its first onset to 1 at
 *   its last, and follows its contour, × (1 + 0.03 an octave above its mean pitch), within ± 0.06.
 * - **Metric accents**, only on the grid ([Quantize.onGrid]): a downbeat × 1.06, another beat × 1.02, off the beat
 *   × 0.98; within [ON_BEAT_MICROS] counts as on.
 * - **Repeated chords**: of identical chords in a row, the second (the fourth …) × 0.96, then back.
 * - **The file's own dynamics**: the factors' full effect on a flat file (velocities' standard deviation under 6), 30 %
 *   of it from 20 up, in proportion between.
 * - **Timing, never accumulating**: a cluster's melody note starts 8 ms before the cluster's time, the others stay; a
 *   cluster of three or more notes within 10 ms rolls from the bottom over 12 ms, its top note on the beat (the roll
 *   takes the lead's place); the first note of a phrase after a rest starts 15 ms late (at most half its length) and ends
 *   where it ended; the last note of a phrase holds up to 20 % longer, never past its key's next onset less the release
 *   gap of [Repeats], nor past the piece's last event.
 * - **Bounds**: velocities 1–127; no note added or taken away; no onset more than [MAX_SHIFT_MICROS] from the file's,
 *   none before the note before it on its channel and key lets go, none past its own end; the pedal's times and the
 *   piece's length unchanged.
 */
internal object Expression {
    const val CLUSTER_MICROS = 30_000L
    const val MELODY = 0.08
    const val BASS = 0.03
    const val INNER = 0.08
    const val PHRASE_GAP_MICROS = 600_000L
    const val MIN_PHRASE = 3
    const val ARC = 0.12
    const val CONTOUR = 0.03
    const val CONTOUR_CAP = 0.06
    const val DOWNBEAT = 0.06
    const val BEAT = 0.02
    const val OFF_BEAT = 0.02
    const val ON_BEAT_MICROS = 20_000L
    const val REPEATED_CHORD = 0.04

    /** The file's velocities' standard deviation under which the effect is full, and from which it is [DYNAMIC_SHARE]. */
    const val FLAT = 6.0
    const val DYNAMIC = 20.0
    const val DYNAMIC_SHARE = 0.3

    const val LEAD_MICROS = 8_000L
    const val ROLL_MICROS = 12_000L
    const val BREATH_MICROS = 15_000L
    const val FULL_LEAD_MICROS = 15_000L
    const val FULL_ROLL_MICROS = 25_000L
    const val FULL_BREATH_MICROS = 25_000L
    const val ROLL_NOTES = 3
    const val ROLL_WITHIN_MICROS = 10_000L
    const val HOLD = 0.2
    const val MAX_SHIFT_MICROS = 25_000L

    /** Shapes [t] in place at [level]; [hands] one per note, or none; [releaseGapMicros] is what a key must be up before it strikes again. */
    fun shape(t: NoteTable, hands: ByteArray?, piece: MidiPiece, level: ExpressionLevel, releaseGapMicros: Long) {
        if (level == ExpressionLevel.OFF) return
        val full = level == ExpressionLevel.FULL
        val scale = if (full) 2.0 else 1.0
        // The notes shaped, in time order (the table's order is the events').
        var shaped = 0
        for (i in 0 until t.size) if (t.shapes(i)) shaped++
        if (shaped == 0) return
        val order = IntArray(shaped)
        shaped = 0
        for (i in 0 until t.size) if (t.shapes(i)) order[shaped++] = i
        val starts = clusters(t, order)
        val clusters = starts.size - 1
        val split = hands != null && hands.size == t.size
        val factor = DoubleArray(t.size) { 1.0 }

        // Roles.
        val melodyOf = IntArray(clusters) { NONE }
        for (c in 0 until clusters) {
            var melody = NONE
            var bass = NONE
            for (p in starts[c] until starts[c + 1]) {
                val i = order[p]
                if ((!split || hands!![i] == Hands.RIGHT) && (melody == NONE || t.key[i] > t.key[melody])) melody = i
                if ((!split || hands!![i] == Hands.LEFT) && (bass == NONE || t.key[i] < t.key[bass])) bass = i
            }
            if (bass == melody) bass = NONE   // a note alone is the melody
            melodyOf[c] = melody
            for (p in starts[c] until starts[c + 1]) {
                val i = order[p]
                factor[i] *= 1 + scale * when (i) {
                    melody -> MELODY
                    bass -> -BASS
                    else -> -INNER
                }
            }
        }

        // Phrases: the melody, cluster by cluster (its onsets rise: clusters start more than 30 ms apart).
        val melody = IntArray(melodyOf.count { it != NONE })
        var sung = 0
        for (c in 0 until clusters) if (melodyOf[c] != NONE) melody[sung++] = melodyOf[c]
        val breath = ArrayList<Int>()
        val hold = ArrayList<Int>()
        var a = 0
        while (a < melody.size) {
            var b = a + 1
            while (b < melody.size && !restBefore(t, piece, melody[b - 1], melody[b])) b++
            if (b - a >= MIN_PHRASE) {
                val first = t.fileOnset[melody[a]]
                val span = (t.fileOnset[melody[b - 1]] - first).toDouble()
                var pitch = 0.0
                for (j in a until b) pitch += t.key[melody[j]]
                val mean = pitch / (b - a)
                for (j in a until b) {
                    val i = melody[j]
                    val at = if (span > 0) (t.fileOnset[i] - first) / span else 0.0
                    factor[i] *= 1 + scale * ARC * sin(PI * at)
                    factor[i] *= 1 + (scale * CONTOUR * (t.key[i] - mean) / OCTAVE).coerceIn(-CONTOUR_CAP, CONTOUR_CAP)
                }
            }
            if (a > 0) breath += melody[a]   // after a rest; the first phrase follows none
            hold += melody[b - 1]
            a = b
        }

        // Metric accents, on the grid alone.
        if (Quantize.onGrid(t.fileOnset, piece.tempoMap)) {
            for (i in order) factor[i] *= 1 + scale * metric(piece, t.fileOnset[i])
        }

        // Repeated chords: the second of two identical in a row, then back.
        var lowBefore = 0L
        var highBefore = 0L
        var run = 0
        for (c in 0 until clusters) {
            var low = 0L
            var high = 0L
            for (p in starts[c] until starts[c + 1]) {
                val key = t.key[order[p]]
                if (key < 64) low = low or (1L shl key) else high = high or (1L shl (key - 64))
            }
            val chord = java.lang.Long.bitCount(low) + java.lang.Long.bitCount(high) >= 2
            run = if (chord && c > 0 && low == lowBefore && high == highBefore) run + 1 else 0
            if (run % 2 == 1) for (p in starts[c] until starts[c + 1]) factor[order[p]] *= 1 - scale * REPEATED_CHORD
            lowBefore = low
            highBefore = high
        }

        // Velocities, as much of the effect as the file's own dynamics leave room for.
        val weight = weight(deviation(t, order))
        for (i in order) t.velocity[i] = (t.velocity[i] * (1 + weight * (factor[i] - 1))).roundToInt().coerceIn(1, 127)

        // Timing: each note's shift from the file's onset, never carried on to the next.
        val shift = LongArray(t.size)
        val lead = if (full) FULL_LEAD_MICROS else LEAD_MICROS
        val roll = if (full) FULL_ROLL_MICROS else ROLL_MICROS
        for (c in 0 until clusters) {
            val from = starts[c]
            val to = starts[c + 1]
            val first = t.fileOnset[order[from]]
            if (to - from >= ROLL_NOTES && t.fileOnset[order[to - 1]] - first <= ROLL_WITHIN_MICROS) {
                val chord = byPitch(t, order, from, to)
                val beat = t.fileOnset[chord.last()]
                for ((r, i) in chord.withIndex()) shift[i] = beat - roll * (chord.size - 1 - r) / (chord.size - 1) - t.fileOnset[i]
            } else if (melodyOf[c] != NONE) {
                shift[melodyOf[c]] = first - lead - t.fileOnset[melodyOf[c]]
            }
        }
        val late = if (full) FULL_BREATH_MICROS else BREATH_MICROS
        for (i in breath) shift[i] = minOf(late, (t.fileEnd[i] - t.fileOnset[i]) / 2).coerceAtLeast(0L)
        val letGo = LongArray(NoteTable.KEYS * CHANNELS)   // per channel and key, when its last note so far ends in the file
        for (i in 0 until t.size) {
            val held = t.channel[i] * NoteTable.KEYS + t.key[i]
            if (shift[i] != 0L && t.shapes(i)) {
                val latest = if (t.fileEnd[i] > t.fileOnset[i]) t.fileEnd[i] - 1 else t.fileOnset[i]
                t.onset[i] = (t.fileOnset[i] + shift[i].coerceIn(-MAX_SHIFT_MICROS, MAX_SHIFT_MICROS)).coerceIn(letGo[held], latest)
            }
            letGo[held] = t.fileEnd[i]
        }

        // The last note of each phrase holds on, up to its key's next onset less the release gap.
        val groups = t.keyGroups()
        val next = IntArray(t.size) { NONE }
        for (k in 0 until NoteTable.KEYS) for (j in groups.starts[k] until groups.starts[k + 1] - 1) next[groups.notes[j]] = groups.notes[j + 1]
        val longer = if (full) 2 * HOLD else HOLD
        for (i in hold) {
            val length = t.fileEnd[i] - t.fileOnset[i]
            if (length <= 0L) continue
            var until = minOf(t.fileEnd[i] + (length * longer).roundToLong(), t.lastMicros)
            if (next[i] != NONE) until = minOf(until, t.onset[next[i]] - releaseGapMicros)
            if (until > t.end[i]) t.end[i] = until
        }
    }

    /** Where each cluster starts, as positions in [order]; the last entry is the end of the last. */
    private fun clusters(t: NoteTable, order: IntArray): IntArray {
        val starts = ArrayList<Int>()
        var p = 0
        while (p < order.size) {
            starts += p
            val first = t.fileOnset[order[p]]
            p++
            while (p < order.size && t.fileOnset[order[p]] - first <= CLUSTER_MICROS) p++
        }
        starts += order.size
        return starts.toIntArray()
    }

    /** Whether a phrase ends between melody notes [before] and [after]: the rest between them is longer than a beat and 600 ms. */
    private fun restBefore(t: NoteTable, piece: MidiPiece, before: Int, after: Int): Boolean {
        val rest = t.fileOnset[after] - t.fileEnd[before]
        val tempo = piece.tempoMap
        val beat = tempo.tempoAt(tempo.microsToTicks(t.fileOnset[after])).toLong()
        return rest > maxOf(beat, PHRASE_GAP_MICROS)
    }

    /** A note's metric accent at [at]: on a bar's first beat, on another beat, or off the beat. */
    private fun metric(piece: MidiPiece, at: Long): Double {
        if (distanceToNearest(piece.barStartsMicros, at) <= ON_BEAT_MICROS) return DOWNBEAT
        val tempo = piece.tempoMap
        val beat = Math.rint(tempo.microsToBeats(at))
        val beatAt = tempo.tickToMicros((beat * tempo.ppq).roundToLong())
        return if (abs(at - beatAt) <= ON_BEAT_MICROS) BEAT else -OFF_BEAT
    }

    /** How far [at] is from the nearest of [sorted]. */
    private fun distanceToNearest(sorted: LongArray, at: Long): Long {
        if (sorted.isEmpty()) return Long.MAX_VALUE
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < at) lo = mid + 1 else hi = mid
        }
        var best = Long.MAX_VALUE
        if (lo < sorted.size) best = sorted[lo] - at
        if (lo > 0) best = minOf(best, at - sorted[lo - 1])
        return best
    }

    /** The cluster [from] until [to] of [order], lowest first (two notes on one key in the table's order). */
    private fun byPitch(t: NoteTable, order: IntArray, from: Int, to: Int): IntArray {
        val chord = IntArray(to - from) { order[from + it] }
        for (j in 1 until chord.size) {
            val note = chord[j]
            var at = j
            while (at > 0 && t.key[chord[at - 1]] > t.key[note]) {
                chord[at] = chord[at - 1]
                at--
            }
            chord[at] = note
        }
        return chord
    }

    /** The standard deviation of the file's velocities over [order]. */
    private fun deviation(t: NoteTable, order: IntArray): Double {
        var sum = 0.0
        for (i in order) sum += t.fileVelocity[i]
        val mean = sum / order.size
        var squares = 0.0
        for (i in order) squares += (t.fileVelocity[i] - mean) * (t.fileVelocity[i] - mean)
        return sqrt(squares / order.size)
    }

    /** How much of the effect a file whose velocities deviate by [deviation] takes. */
    fun weight(deviation: Double): Double = when {
        deviation < FLAT -> 1.0
        deviation >= DYNAMIC -> DYNAMIC_SHARE
        else -> 1.0 - (1.0 - DYNAMIC_SHARE) * (deviation - FLAT) / (DYNAMIC - FLAT)
    }

    private const val NONE = -1
    private const val CHANNELS = 16
    private const val OCTAVE = 12.0
}

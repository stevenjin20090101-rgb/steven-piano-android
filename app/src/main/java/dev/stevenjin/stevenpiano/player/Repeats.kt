// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.player

/**
 * Repeats that keep the rhythm (DESIGN.md › v1.16 — M44), part (c) of [Performance], always on for Steven Piano. T is the
 * re-strike time ([Performance.restrikeMs]); G, the release gap, is the longer of [MIN_RELEASE_GAP_MICROS] and T less
 * [RELEASE_SHORTER_MICROS]: a key must be up that long before it strikes again (the piano's `gap` 30 ms, its
 * `releasems` 22 and its 10 ms tick). For each key's notes in onset order:
 *
 * 1. **Repeats faster than T**: the first is kept, then each that comes at least T after the last kept (a millisecond's
 *    rounding allowed): of a steady run that is the first and every n-th, n = ⌈T / period⌉. A kept note gains
 *    [BONUS] velocity for each note it stands for, [MAX_BONUS] at most (127 at most): the pulse stays and nothing piles
 *    up at the piano. It sounds as long as the longest of them.
 * 2. **Each strike lands**: a kept note ends at the earliest of its own end and the next kept onset of its key less G,
 *    so the key is up in time; never shorter than [MIN_NOTE_MICROS] for that (the next strike then waits at the piano
 *    for G anyway), never past that onset.
 *
 * Nothing else changes: no onset moves. The router's guard ([dev.stevenjin.stevenpiano.midi.NoteRouter.restrikeMicros])
 * is set from the same T, the last line of defence for what this pass does not see (the Keys screen, a keyboard, a
 * faster tempo).
 */
internal object Repeats {
    const val MIN_RELEASE_GAP_MICROS = 60_000L
    const val RELEASE_SHORTER_MICROS = 40_000L
    const val MIN_NOTE_MICROS = 30_000L
    const val BONUS = 6
    const val MAX_BONUS = 18

    /** A repeat this much sooner than T still counts as T: the file's ticks round to whole microseconds. */
    const val ROUNDING_MICROS = 1_000L

    /** G for a re-strike time of [restrikeMicros]. */
    fun releaseGapMicros(restrikeMicros: Long): Long = maxOf(MIN_RELEASE_GAP_MICROS, restrikeMicros - RELEASE_SHORTER_MICROS)

    /** Shapes [t] in place for a re-strike time of [restrikeMicros]. */
    fun shape(t: NoteTable, restrikeMicros: Long) {
        if (restrikeMicros <= 0L) return
        val gap = releaseGapMicros(restrikeMicros)
        val soonest = restrikeMicros - ROUNDING_MICROS
        val groups = t.keyGroups()
        val standsFor = IntArray(t.size)
        val reach = LongArray(t.size)
        for (k in 0 until NoteTable.KEYS) {
            val from = groups.starts[k]
            val to = groups.starts[k + 1]
            var last = NONE
            for (j in from until to) {
                val i = groups.notes[j]
                if (last == NONE || t.onset[i] - t.onset[last] >= soonest) {
                    last = i
                    reach[i] = t.end[i]
                } else {
                    t.kept[i] = false
                    standsFor[last]++
                    reach[last] = maxOf(reach[last], t.end[i])
                }
            }
            var before = NONE
            for (j in from until to) {
                val i = groups.notes[j]
                if (!t.kept[i]) continue
                if (before != NONE) {
                    val lift = maxOf(t.onset[i] - gap, t.onset[before] + MIN_NOTE_MICROS)
                    t.end[before] = minOf(reach[before], lift, t.onset[i])
                }
                before = i
            }
            if (before != NONE) t.end[before] = minOf(reach[before], t.lastMicros)
        }
        for (i in 0 until t.size) {
            if (standsFor[i] > 0) t.velocity[i] = (t.velocity[i] + minOf(BONUS * standsFor[i], MAX_BONUS)).coerceAtMost(127)
        }
    }

    private const val NONE = -1
}

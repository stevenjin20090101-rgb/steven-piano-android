// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio.compose

import dev.stevenjin.stevenpiano.midi.KeyMap

/**
 * The notes a composition has written so far (v1.12 — M30), for the card's preview roll: [size] notes, each its
 * onset and duration in ticks (10 ms) from the seed's end and its key folded into the piano's 24–107. Rests are
 * skipped. A snapshot never changes: the builder only ever writes past the sizes it has handed out, and a full
 * array is copied, never written in place. It is not the piece (no post-processing: that would spend the job's
 * random numbers); it is what the model wrote, as it wrote it.
 */
class PreviewRoll internal constructor(
    private val onsets: IntArray,
    private val durations: IntArray,
    private val keys: IntArray,
    val size: Int,
    /** The written music's length in ticks so far (the latest onset). */
    val lengthTicks: Int,
) {
    fun onset(i: Int): Int = onsets[i]

    fun duration(i: Int): Int = durations[i]

    fun key(i: Int): Int = keys[i]

    /** Appends events as the sampler writes them; [snapshot] hands out what is written. One writer. */
    class Builder(private val origin: Int, private val cap: Int = MAX_NOTES) {
        private var onsets = IntArray(INITIAL)
        private var durations = IntArray(INITIAL)
        private var keys = IntArray(INITIAL)
        private var size = 0
        private var latest = 0

        fun add(event: AmtEvent) {
            latest = maxOf(latest, event.time - origin)
            if (event.isRest || event.instrument != 0 || size >= cap) return
            if (size == onsets.size) {
                val grown = minOf(cap, onsets.size * 2)
                onsets = onsets.copyOf(grown)
                durations = durations.copyOf(grown)
                keys = keys.copyOf(grown)
            }
            onsets[size] = event.time - origin
            durations[size] = event.duration
            keys[size] = KeyMap.map(event.pitch, 0, fold = true)
            size++
        }

        fun snapshot(): PreviewRoll = PreviewRoll(onsets, durations, keys, size, latest)
    }

    companion object {
        /** Five dense minutes at most: past this the roll stops growing (the piece goes on). */
        const val MAX_NOTES = 8_192
        private const val INITIAL = 256
    }
}

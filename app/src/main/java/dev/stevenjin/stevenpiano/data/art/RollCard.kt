// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.midi.NoteList

/**
 * A piece's own art when there is no portrait: its first 20 seconds as perforations on a paper
 * roll, 84 lanes (C1–B7) across and time running up from the bottom, as the roll on Now playing
 * brings notes down to the tracker bar. The result is only coverage: a [SIZE] × [SIZE] alpha map
 * (row-major, 0 is paper), so the picture takes the theme's colours when it is drawn and no colour
 * lives here. Louder notes are denser. Pure and deterministic: the same notes give the same bytes.
 */
object RollCard {
    const val SIZE = 256
    const val WINDOW_MICROS = 20_000_000L
    private const val LANES = KeyMap.KEY_COUNT

    /** The faintest perforation (the softest note) and the densest (the loudest). */
    private const val ALPHA_SOFT = 120
    private const val ALPHA_LOUD = 255

    /** No perforation is shorter than this, so a staccato note still shows. */
    private const val MIN_HEIGHT = 2

    /** The 20 s after the first note, or a blank card for a piece without notes. */
    fun render(notes: NoteList): ByteArray {
        val alpha = ByteArray(SIZE * SIZE)
        if (notes.size == 0) return alpha
        val start = notes.startMicros[0]
        val end = start + WINDOW_MICROS
        for (i in 0 until notes.size) {
            val noteStart = notes.startMicros[i]
            if (noteStart >= end) break   // sorted by start
            val lane = KeyMap.map(notes.note(i), 0, fold = true) - KeyMap.LOWEST
            val left = laneStart(lane)
            val right = laneStart(lane + 1) - 2   // the lane's last column stays paper, between perforations
            val bottom = rowAt(noteStart - start)
            var top = rowAt(minOf(notes.endMicros[i], end) - start)
            if (bottom - top < MIN_HEIGHT - 1) top = maxOf(0, bottom - (MIN_HEIGHT - 1))
            val value = shade(notes.velocity(i))
            for (y in top..bottom) {
                val row = y * SIZE
                for (x in left..right) {
                    val at = row + x
                    if ((alpha[at].toInt() and 0xFF) < value) alpha[at] = value.toByte()
                }
            }
        }
        return alpha
    }

    /** The first pixel column of [lane]; lanes are 3 or 4 pixels wide. */
    fun laneStart(lane: Int): Int = lane * SIZE / LANES

    /** The pixel row [micros] into the window: the start at the bottom row, the window's end at the top. */
    fun rowAt(micros: Long): Int = (SIZE - 1 - (micros.coerceIn(0L, WINDOW_MICROS) * SIZE / WINDOW_MICROS).toInt()).coerceIn(0, SIZE - 1)

    private fun shade(velocity: Int): Int = ALPHA_SOFT + velocity.coerceIn(0, 127) * (ALPHA_LOUD - ALPHA_SOFT) / 127
}

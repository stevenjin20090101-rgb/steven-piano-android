// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

/**
 * The power boards as one CHECK read them (v1.26 — M60): the piano's `!boards=OK,OK,MISSING,…` (BLE_SETTINGS.md › 4), one
 * entry per board, the one for MIDI 24–35 first; [ok] is true for a board that reads OK. Seven on the school's piano.
 */
data class Boards(val ok: List<Boolean>) {
    /** How many boards answered. */
    val answered: Int get() = ok.count { it }

    /** How many boards the piano reports. */
    val total: Int get() = ok.size

    /** Every board answered. */
    val all: Boolean get() = ok.isNotEmpty() && ok.all { it }

    /** The boards that answered, by index: a baseline. */
    val okSet: Set<Int> get() = ok.indices.filter { ok[it] }.toSet()

    /** Whether every board of [baseline] answered here. */
    fun holds(baseline: Set<Int>): Boolean = baseline.all { it in ok.indices && ok[it] }

    companion object {
        /** The piano's boards (kNumPowerBoards in the firmware). */
        const val TOTAL = 7

        /** The value of a `!boards=` line ("OK,MISSING,…"), or null when it is empty; a word other than OK reads as missing. */
        fun parse(value: String?): Boards? {
            val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return Boards(text.split(',').map { it.trim().equals("OK", ignoreCase = true) })
        }
    }
}

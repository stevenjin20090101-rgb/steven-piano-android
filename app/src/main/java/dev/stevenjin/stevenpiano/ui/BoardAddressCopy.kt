// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.piano.BoardAddress

/** The words of a power board moved on this piano (M85, Piano › Firmware and status, under the power boards). */
object BoardAddressCopy {
    /** "C6 answers at 0x4D, not 0x45": the tick that's off ([lookingAt]: where the piano looks for it now). */
    fun offer(board: Int, address: Int, lookingAt: Int): String =
        "${BoardAddress.name(board)} answers at ${BoardAddress.hex(address)}, not ${BoardAddress.hex(lookingAt)}"

    const val OFFER_NOTE = "Use it there on this piano only. The piano keeps it; another piano never gets it"

    /** "C6 at 0x4D on this piano": the tick that's on. */
    fun moved(board: Int, address: Int): String = "${BoardAddress.name(board)} at ${BoardAddress.hex(address)} on this piano"

    /** Under it: what unticking does. */
    fun movedNote(board: Int): String =
        "Kept in this piano for a stuck address switch, through updates and a reset. Untick to look for it at ${BoardAddress.hex(BoardAddress.default(board))} again"

    const val NO_ANSWER = "The piano didn't answer. Try again."
    const val NOTHING_THERE = "Saved, but no board answers at that address."
}

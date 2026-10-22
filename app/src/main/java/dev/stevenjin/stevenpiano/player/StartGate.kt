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
 * What may hold a person's start back (v1.26 — M61: the safety check, `safety.SafetyRunner`), asked by every start
 * path of the [Player] (play, Play all, Play, Next, Previous, an Up next entry, what joins an empty queue) and by a
 * channel's start. Main thread.
 */
interface StartGate {
    /** A start may go now, with nothing to wait for or refuse: no check due, running, or failed. */
    val open: Boolean

    /**
     * A person's start of [pieceId] (null when not known yet): true, it goes now. False, the gate has kept it (it calls
     * [start] again once the safety check has passed, and the start asks again) or refused it (Play is locked, or the
     * check can't run now); either way the start does nothing now.
     */
    fun admit(pieceId: Long?, start: () -> Unit): Boolean

    companion object {
        /** No gate: everything starts (the tests, and the player before the safety check existed). */
        val Open: StartGate = object : StartGate {
            override val open: Boolean = true

            override fun admit(pieceId: Long?, start: () -> Unit): Boolean = true
        }
    }
}

/**
 * A piece that sounded (v1.26 — M61; the safety check counts them): it played to its end ([ended]), or was stopped or
 * replaced by another after it had begun to play.
 */
data class Played(val pieceId: Long, val ended: Boolean)

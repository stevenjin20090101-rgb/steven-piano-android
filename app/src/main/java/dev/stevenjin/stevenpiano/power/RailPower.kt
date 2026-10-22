// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import kotlinx.coroutines.flow.StateFlow

/**
 * The piano's 24 V solenoid rail as other features see it (v1.26 — M60; `firmware/docs/SMART_PLUG.md`): the solenoid
 * supply hangs on a Bluetooth smart plug that switches itself off 30 s after the tablet last told it to stay on, and the
 * tablet tells it so only while something should play and the piano answers with its power boards. [RailKeeper] is the
 * one implementation; the safety check (M61) takes these as plain lambdas, which the integrator connects.
 *
 * With no plug paired, or the plug's switch off, the rail is not the app's: [available] is false, [repower] answers
 * false at once, and [cutNow] and [wanted] do nothing, so the app behaves exactly as before the plug.
 */
interface RailPower {
    /** Whether the plug switches the rail: a plug paired and its switch on (Piano › Power plug). */
    val available: StateFlow<Boolean>

    /**
     * The rail off and on again: CUT, 3 s, then the rail-up (a good CHECK, ARM, then the CHECK every 500 ms until every
     * board reads OK, 10 s at most). True when every board of the last baseline (all seven when there was none) answers
     * after it; false when it could not (no plug, the piano silent, the plug lost, a board missing). Suspends for the
     * whole of it.
     */
    suspend fun repower(): Boolean

    /** The stop sequence (CC64 = 0, CC123) first, then CUT at once: a fire risk seen (a coil still on). */
    suspend fun cutNow()

    /**
     * Whether the feature [reason] names wants the rail up ([on]) or no longer does: the safety check holds it with
     * `wanted("safety", true)` while it runs. The rail comes up (or stays up) while any reason wants it, under the
     * routine's checks, and goes off, after the stop sequence and a second, once none does.
     */
    fun wanted(reason: String, on: Boolean)
}

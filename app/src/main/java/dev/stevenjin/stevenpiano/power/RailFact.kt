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
 * The 24 V plug and its rail in one fact (v1.26 — M60), for the web panel's System page and its Play capsule, which the
 * integrator adds after the merge (`AppGraph.railFact`; the JSON it needs is in BUILD_SPEC.md › v1.26 — M60):
 *
 * - [paired] a plug is paired; [on] its switch (the plug switches the rail);
 * - [rail] where the rail stands: "off", "waking", "on" (kept by the CHECK), "kept" (the update or guest play),
 *   "stopping"; [waking] while it comes up ("Waking the piano…");
 * - [output], [watts]: the plug's switch and the supply's draw at its last reading (null before one);
 * - [timerEndsAt]: when the plug's timer switches it off by itself (wall clock, ms; null with none known);
 * - [answered] of [total]: the power boards at the last good CHECK (null before one);
 * - [message]: what the tablet says now, in its words; [attention] when it needs it (amber) rather than passing;
 * - [welded]: after a CUT the plug still reads on, or the supply still draws;
 * - [guestEndsAt]: guest play this tablet keeps the rail on for ends then (wall clock, ms; null with none).
 */
data class RailFact(
    val paired: Boolean = false,
    val on: Boolean = false,
    val rail: String = "off",
    val waking: Boolean = false,
    val output: Boolean? = null,
    val watts: Double? = null,
    val timerEndsAt: Long? = null,
    val answered: Int? = null,
    val total: Int? = null,
    val message: String? = null,
    val attention: Boolean = false,
    val welded: Boolean = false,
    val guestEndsAt: Long? = null,
) {
    companion object {
        /** [phase] as [rail] says it. */
        fun word(phase: RailPhase): String = when (phase) {
            RailPhase.Off -> "off"
            RailPhase.Waking -> "waking"
            RailPhase.Up -> "on"
            RailPhase.Kept -> "kept"
            RailPhase.Stopping -> "stopping"
        }
    }
}

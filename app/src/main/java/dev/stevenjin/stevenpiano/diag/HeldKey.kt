// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.diag

/**
 * A key held on (v1.23 — M55; DESIGN.md › v1.23 — M55): a key the piano still holds down when nothing plays can overheat
 * its solenoid, so it comes first of everything that needs attention ([Attention], the panel's `attention()`). The piano
 * says how many keys it holds (`!active`, firmware 2.0.1, [PianoDiag.active]); that is a key held on when the facts that
 * say it came in [AFTER_MS] or more after anything last played. Pure.
 */
object HeldKey {
    /** Nothing has played for 5 s. */
    const val AFTER_MS = 5_000L

    /**
     * Whether a key is held on: the piano said [active] keys are held (more than none), in facts that came in at [factsAt]
     * (epoch ms), when nothing had played since [quietSince] (epoch ms; null while something plays) for [AFTER_MS] by then.
     */
    fun held(active: Long?, factsAt: Long?, quietSince: Long?): Boolean =
        active != null && active > 0 && factsAt != null && quietSince != null && factsAt - quietSince >= AFTER_MS
}

/**
 * Since when nothing has played (v1.23 — M55: a piece, the key test, a keyboard through Live), for [HeldKey]: [see] is
 * told whenever that changes; [since] is the moment it last went quiet, null while something plays. Thread-safe.
 */
class QuietSince(start: Long) {
    @Volatile
    var since: Long? = start
        private set

    /** Something plays ([playing]) or not, as of [now] (epoch ms). */
    @Synchronized
    fun see(playing: Boolean, now: Long) {
        since = if (playing) null else since ?: now
    }
}

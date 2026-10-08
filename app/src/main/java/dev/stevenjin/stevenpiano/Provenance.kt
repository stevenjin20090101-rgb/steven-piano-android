// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano

import androidx.annotation.Keep

/**
 * Authorship compiled into the app. R8 keeps this object (see proguard-rules.pro), so [TAG] is
 * in every release DEX: `strings classes.dex | grep STEVEN-PIANO-PROVENANCE`. The About row on
 * the Piano tab shows [text]; every tab's header shows [byline] (which names the app, so [text]
 * no longer does: the name appears once per screen), and the resting screen [restingByline]; the
 * manifest carries [TAG] as meta-data too.
 */
@Keep
object Provenance {
    const val TAG = "STEVEN-PIANO-PROVENANCE Made by Steven Jin <stevenjin20090101@gmail.com> Ed25519 fp eab16a502f679465"
    val text = "Made by Steven Jin · v1.21 · eab16a502f679465"

    /** Under every tab's title, in the eyebrow style (which sets it in capitals). */
    val byline = "Player piano · by Steven Jin"

    /** The resting screen's byline (DESIGN.md › v1.7.1): these two lines at its top right, in the eyebrow style. */
    val restingByline = listOf("Player piano", "Made by Steven Jin")
}

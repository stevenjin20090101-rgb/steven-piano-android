// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.sort

import dev.stevenjin.stevenpiano.player.Played

/**
 * Which plays this piano counts (M82: what Popular weighs, `PieceEntity.playCount`): a piece that played to its end, or
 * sounded [MIN_SOUNDED_MS] (pauses left out), as the player tells it ([Played]). A start, a restart or a skip after a few
 * seconds is no play; when it started is still kept (`lastPlayedAt`: Recent and Recently played).
 */
object Plays {
    const val MIN_SOUNDED_MS = 30_000L

    fun counts(played: Played): Boolean = played.ended || played.soundedMs >= MIN_SOUNDED_MS
}

// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.db

import androidx.room.DatabaseView

/** One row per composer: pieces grouped by folded surname, named by the first full name. */
@DatabaseView(
    viewName = "composer_groups",
    value = "SELECT composerKey, MIN(composer) AS name, MIN(composerShort) AS shortName, COUNT(*) AS pieceCount " +
        "FROM pieces GROUP BY composerKey",
)
data class ComposerGroup(val composerKey: String, val name: String, val shortName: String, val pieceCount: Int)

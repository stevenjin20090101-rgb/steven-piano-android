// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import androidx.annotation.DrawableRes
import dev.stevenjin.stevenpiano.R

/**
 * The four destinations, in bar and rail order. They navigate; they never act. Library keeps the
 * books, Now playing the roll, Keys takes the keyboard, and Piano (the connection and settings)
 * the sliders.
 */
enum class Route(val path: String, val label: String, @param:DrawableRes val icon: Int) {
    Library("library", "Library", R.drawable.ic_tab_library),
    NowPlaying("now-playing", "Now playing", R.drawable.ic_stat_piano),
    Keys("keys", "Keys", R.drawable.ic_tab_keys),
    Piano("piano", "Piano", R.drawable.ic_tab_piano),
    ;

    companion object {
        fun of(path: String?): Route? = entries.firstOrNull { it.path == path }
    }
}

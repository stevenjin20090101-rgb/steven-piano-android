// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.stevenjin.stevenpiano.ui.components.KeyLayout

/**
 * What the window's width class decides (DESIGN.md › v1.1 › Adaptive layout). Nothing else
 * changes with size. Compact (under 600 dp: phones upright) keeps v1.0's bottom bar; Medium
 * (600-840 dp: small tablets, phones on their side) and Expanded (840 dp and up: tablets on
 * their side) move the four destinations to a rail on the left. Never a rail and a bar at once.
 */
@Immutable
class AppFrame(val widthClass: WindowWidthSizeClass) {
    /** The navigation rail on the left instead of the bottom bar. */
    val rail: Boolean get() = widthClass != WindowWidthSizeClass.Compact

    /**
     * White keys the Keys screen shows at once: two octaves and the C that closes them (Compact),
     * about four octaves (Medium), or all 49 (Expanded), which needs no scrolling.
     */
    val keysVisibleWhites: Int
        get() = when (widthClass) {
            WindowWidthSizeClass.Compact -> COMPACT_WHITES
            WindowWidthSizeClass.Medium -> MEDIUM_WHITES
            else -> KeyLayout.WHITE_KEYS
        }

    /** Whether the Keys screen can scroll, and so shows the mini-map and the octave buttons. */
    val keysScroll: Boolean get() = keysVisibleWhites < KeyLayout.WHITE_KEYS

    override fun equals(other: Any?): Boolean = other is AppFrame && other.widthClass == widthClass

    override fun hashCode(): Int = widthClass.hashCode()

    override fun toString(): String = "AppFrame($widthClass)"

    companion object {
        const val COMPACT_WHITES = 15
        const val MEDIUM_WHITES = 29
    }
}

/** The frame the screens are in, provided by the nav host. */
val LocalAppFrame = staticCompositionLocalOf { AppFrame(WindowWidthSizeClass.Compact) }

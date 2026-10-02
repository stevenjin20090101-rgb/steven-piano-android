// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// THE AURA (DESIGN.md › v1.12 — Studio as a tab): colour's one new meaning, "the tablet is writing music".
// Four flowing stops, blue, violet, rose and amber, and this file is the only place they exist: read only
// through LocalAuraStops by the one Aura component (ui/components/Aura.kt). Red stays "live", yellow stays
// "sounding note"; the aura is never behind text without a solid scrim, and never the only sign of status.

val AuraDark = listOf(Color(0xFF4E7CFF), Color(0xFF9B6BFF), Color(0xFFFF6FA8), Color(0xFFFFB86B))
val AuraLight = listOf(Color(0xFF3F6BF0), Color(0xFF8A58F0), Color(0xFFF0558F), Color(0xFFF0A050))

/** The aura's stops for this appearance; PianoTheme provides them. */
val LocalAuraStops = staticCompositionLocalOf { AuraDark }

/** The aura's measures and pace: rest 30 % (a turn in 12 s), focused 60 %, working 100 % (a turn in 4 s). */
object AuraTokens {
    const val RestAlpha = 0.30f
    const val FocusedAlpha = 0.60f
    const val WorkingAlpha = 1f
    const val RestTurnMs = 12_000
    const val WorkingTurnMs = 4_000
    val Ring = 2.dp
    val Glow = 6.dp
    val GlowRoom = 10.dp
    val Dot = 6.dp
}

// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// One typeface: the platform's Roboto (FontFamily.Default). Hierarchy comes from
// size, weight, tracking and colour — never from a second font, and never from Light
// or Thin weights (DESIGN.md › Type).
private val Sans = FontFamily.Default

val PianoTypography = Typography(
    // Display mode's title on wide screens (a tablet on the piano, read from a step away): the
    // Display style a third larger, as its eyebrow goes from 12 to 16 sp (DESIGN.md › v1.5 — M17).
    displayLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = (-0.5).sp,
    ),
    // Piece title on Now Playing.
    displayMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp,
    ),
    // Screen and sheet titles.
    titleLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp,
    ),
    // Rows, settings, copy. 17 sp is the HIG default body size.
    bodyLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = 0.sp,
    ),
    // Buttons and chips.
    labelLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    // THE EYEBROW — the engraved-camera-label style. Callers apply .uppercase() and
    // colour it contentTertiary. 12 sp clears the 11 sp minimum.
    labelSmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.4.sp,
    ),
)

// Tabular figures for every timer, counter and percentage, so digits don't jitter
// as they change. Apply on top of any style: style.merge(Tabular).
val Tabular = TextStyle(fontFeatureSettings = "tnum")

// The eyebrow a third larger, beside displayLarge in display mode on wide screens: 16 sp, its
// line height and tracking in proportion (+1.4 sp at 12 sp is +1.87 sp at 16).
val EyebrowLarge = PianoTypography.labelSmall.copy(fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = 1.87.sp)

// Now playing's large cover (DESIGN.md › v1.18 — M49): the title beside or under the cover, bold and tight; the
// score's strip's smaller one; and the composer under either, semibold, sentence case.
val NowPlayingTitle = TextStyle(
    fontFamily = Sans, fontWeight = FontWeight.Bold,
    fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1).sp,
)
val NowPlayingStripTitle = NowPlayingTitle.copy(fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp)
val NowPlayingComposer = TextStyle(
    fontFamily = Sans, fontWeight = FontWeight.SemiBold,
    fontSize = 19.sp, lineHeight = 25.sp, letterSpacing = 0.sp,
)

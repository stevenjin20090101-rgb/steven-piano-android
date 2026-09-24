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

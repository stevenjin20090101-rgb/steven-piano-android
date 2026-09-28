// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.theme

import androidx.compose.ui.graphics.Color

// Steven Piano palette. Two appearances, one accent. See DESIGN.md › Colour for the
// contrast figures; every text token clears 4.5:1 on its surface and the primary /
// secondary pair clears 7:1 in dark.
//
// RED HAS EXACTLY ONE MEANING: the piano is live. It is used by LiveDot and nothing
// else. Never a button fill, never text, never a highlight, never an error colour.

// ---- Dark: "the camera body" (default) ------------------------------------------
val InkSurface       = Color(0xFF0E0E0E)   // app background
val InkElevated      = Color(0xFF1A1A1A)   // cards, sheets, nav bar
val InkHairline      = Color(0xFF2A2A2A)   // dividers, outlines
val InkDisabledGlyph = Color(0xFF6B6B6B)   // disabled icons only — never text (3.5:1)
val SilverPrimary    = Color(0xFFF2F2F2)   // 16.9:1 — softened white, not #FFFFFF
val SilverSecondary  = Color(0xFFA3A3A3)   //  7.4:1
val SilverTertiary   = Color(0xFF8A8A8A)   //  5.4:1 — eyebrows, timestamps
val LiveRedDark      = Color(0xFFD9232E)   // the dot (non-text; always paired with a word)

// ---- Light: "the paper roll" ---------------------------------------------------
val PaperSurface     = Color(0xFFF4F1EA)   // warm paper
val PaperElevated    = Color(0xFFFBF9F4)
val PaperHairline    = Color(0xFFD8D3C8)
val PaperDisabledGlyph = Color(0xFFB8B2A6)
val CarbonPrimary    = Color(0xFF141414)   // 15.6:1
val CarbonSecondary  = Color(0xFF5C5851)   //  6.1:1
val CarbonTertiary   = Color(0xFF6E6A62)   //  4.7:1
val LiveRedLight     = Color(0xFFC81E28)   //  5.8:1

// ---- The two hands (DESIGN.md › v1.3 › The waterfall format) -------------------
// Colour enters the interface here only when the person turns on Piano › Hand colours, and
// only on the waterfall's bars and the keyboard strip's highlights (read through
// LocalHandTones by NoteCanvas and KeyboardStrip, nowhere else): the left hand a muted green,
// the right a muted blue, each pair at one lightness so neither hand outweighs the other.
// Red keeps its single meaning. Contrast, WCAG, against surface / surfaceElevated
// (ColorTokensTest recomputes it; the floor is 3:1, for graphics):
val HandLeftDark     = Color(0xFF6AA080)   // 6.4:1 / 5.8:1 on InkSurface / InkElevated
val HandRightDark    = Color(0xFF7A97B8)   // 6.4:1 / 5.8:1
val HandLeftLight    = Color(0xFF3D6C50)   // 5.4:1 / 5.8:1 on PaperSurface / PaperElevated
val HandRightLight   = Color(0xFF3E6189)   // 5.7:1 / 6.1:1

// ---- The sounding note on the score (DESIGN.md › v1.5 — M16) ------------------
// Steven's yellow: a note head, its stem, flags, ledger lines and accidental turn this warm yellow
// while the note sounds, then settle back to the secondary grey. Only the score's overlay reads it
// (ScorePainter.overlay, through the theme's sounding-note local); the roll, the falling notes and the keyboard
// strip stay monochrome, and red keeps its single meaning. WCAG contrast on surface /
// surfaceElevated (the score panel) of its own appearance (ColorTokensTest; the floor is 3:1):
val NoteSoundingDark  = Color(0xFFF2C94C)   // 12.2:1 / 11.0:1 on InkSurface / InkElevated
val NoteSoundingLight = Color(0xFF9C7A00)   //  3.6:1 /  3.8:1 on PaperSurface / PaperElevated

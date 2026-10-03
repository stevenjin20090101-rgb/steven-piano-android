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
import androidx.compose.ui.unit.dp

// THE BACKDROP MADE OF THE COVER (DESIGN.md › v1.18 — M49): the art the piece shows, small and blurred
// (data/art/BackdropRules.kt), drawn three times turning slowly behind Now playing, the now-playing panel and the
// resting screen, under the black its brightest part needs for light words; and what stands on it, immersive: the ink
// scheme's primary for every word and glyph, bars and capsules of black glass. This file is the only place its values
// exist. Colour still means nothing here: the backdrop is the art's, never a status.

object Backdrop {
    /**
     * The three pictures' sizes, as shares of the node's diagonal (the square that covers it at any turn). The third
     * does not cover it: it is drawn from the picture's soft copy, whose edge fades to nothing.
     */
    val Scales = floatArrayOf(1.05f, 1.5f, 0.95f)

    /** Their opacities: the first covers, the others lie over it. */
    val Alphas = floatArrayOf(1f, 0.6f, 0.38f)

    /** How long each takes to turn once, and which way: the second the other way. */
    val PeriodsMs = intArrayOf(60_000, 84_000, 48_000)
    val Directions = floatArrayOf(1f, -1f, 1f)

    /** Where each stands on its turn at first, in degrees. */
    val StartDegrees = floatArrayOf(8f, 150f, -30f)

    /** Each one's centre off the node's, as shares of its width and height: the second (12 %, −6 %), the third towards the top right. */
    val OffsetsX = floatArrayOf(0f, 0.12f, 0.27f)
    val OffsetsY = floatArrayOf(0f, -0.06f, -0.24f)

    /** The black over them, from the picture's dim less this at the top to its dim and this at the foot. */
    const val TopLighter = 0.06f
    const val FootDeeper = 0.10f

    /** The resting screen's black is deeper by this, so a still screen stays gentle. */
    const val RestingDeeper = 0.10f

    /** The black the dimming and the immersive glass are made of. */
    val Shade = Color.Black

    /** Every word and glyph on the backdrop, in both appearances: the ink scheme's primary. */
    val Words = SilverPrimary

    /** Bars and capsules over it (the headers, the tab bar or the rail, the foot's capsules): black at 26 %, no blur... */
    const val GlassAlpha = 0.26f
    val Glass = Shade.copy(alpha = GlassAlpha)

    /** ...with an edge of the words at 14 %. */
    val GlassEdge = Words.copy(alpha = 0.14f)

    /** The chosen tab's pill on the rail or the tab bar over it. */
    val Pill = Words.copy(alpha = 0.18f)

    /** Hairlines on it (the scrubber's track, the divider's line, the art's frame): the words at 22 %. */
    val Hairline = Words.copy(alpha = 0.22f)

    /** The roll's notes on it, light: the right hand's (or every note's) at 92 %, the left hand's at 50 %... */
    const val RightHandAlpha = 0.92f
    const val LeftHandAlpha = 0.5f

    /** ...and the light line where they land, at 75 %. */
    val Landing = Words.copy(alpha = 0.75f)

    /** The keyboard strip keeps its keys: white keys light, black keys dark, a sounding key the sounding yellow. */
    val KeyWhite = SilverPrimary
    val KeyBlack = CarbonPrimary
    val KeyLine = SilverTertiary
    val KeySounding = NoteSoundingDark

    /** With Hand colours on, the light keys take the paper's hand colours, mixed toward [KeyBlack] as they sound. */
    val KeyHands = HandTones(HandLeftLight, HandRightLight)

    /** The live dot's light ring, so the red reads on a red cover. */
    val LiveRing = 1.5.dp
    val LiveRingColour = Words.copy(alpha = 0.9f)
}

/**
 * The ink scheme as the backdrop's words wear it (v1.18 — M49): every word and glyph the primary, the secondary and
 * tertiary roles too (nothing secondary or tertiary stands on the backdrop); the play circle is filled in the primary
 * with its glyph in the ink surface.
 */
internal val ImmersiveScheme = DarkScheme.copy(
    onSurfaceVariant = Backdrop.Words,
    secondary = Backdrop.Words,
    tertiary = Backdrop.Words,
)

/**
 * HSL to an opaque ARGB colour (hue in degrees), each channel rounded to 8 bits. Deterministic (`StrictMath`): Studio's
 * covers (`data/art/StudioCover.kt`) draw with it, the same pixels on every device.
 */
fun hsl(hue: Double, saturation: Double, lightness: Double): Int {
    val l = lightness.coerceIn(0.0, 1.0)
    val s = saturation.coerceIn(0.0, 1.0)
    val c = (1 - StrictMath.abs(2 * l - 1)) * s
    val h = ((hue % 360.0) + 360.0) % 360.0 / 60.0
    val x = c * (1 - StrictMath.abs(h % 2 - 1))
    val (r, g, b) = when {
        h < 1 -> Triple(c, x, 0.0)
        h < 2 -> Triple(x, c, 0.0)
        h < 3 -> Triple(0.0, c, x)
        h < 4 -> Triple(0.0, x, c)
        h < 5 -> Triple(x, 0.0, c)
        else -> Triple(c, 0.0, x)
    }
    val m = l - c / 2
    fun channel(v: Double) = ((v + m) * 255 + 0.5).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}

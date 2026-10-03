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

// THE ALBUM-COLOUR BACKDROP (DESIGN.md › v1.15 — M41): the playing piece's art colours as four soft discs drifting
// behind the player, Now playing, the now-playing panel and the resting screen. This file is the only place its colour
// values exist: the art gives each disc a hue and a saturation (data/art/ArtPalette.kt), the appearance its lightness,
// and a veil of the surface over the discs keeps the words in the primary colour at 4.5:1 or more over any hue
// (BackdropContrastTest). Colour still means nothing here: the backdrop is the art's, never a status.

object Backdrop {
    /** The discs' HSL lightness on paper, and on ink (the resting screen's black too). */
    const val PaperLightness = 0.45f
    const val InkLightness = 0.32f

    /** The surface over the discs: paper 55 %, ink 62 % (5.7:1 and 8.6:1 for the primary colour over the worst hue). */
    const val PaperVeil = 0.55f
    const val InkVeil = 0.62f

    /** The resting screen's black canvas: black at 35 % over the words' side only (5.1:1 over the worst hue). */
    const val BlackWordsVeil = 0.35f

    /** A disc's radius, as a share of the pane's shorter side; it fades from its colour to nothing at its edge. */
    const val Radius = 0.6f

    /** How long each disc takes round its path, one each. */
    val PeriodsMs = intArrayOf(20_000, 22_500, 24_000, 26_000)

    /** The veil for the appearance. */
    fun veil(dark: Boolean): Float = if (dark) InkVeil else PaperVeil
}

/** A disc's colour: [hue] (degrees) and [saturation] (0 to 1) from the art, the lightness the appearance's ([dark]: ink). */
fun discColour(hue: Float, saturation: Float, dark: Boolean): Color =
    Color(hsl(hue.toDouble(), saturation.toDouble(), (if (dark) Backdrop.InkLightness else Backdrop.PaperLightness).toDouble()))

/**
 * HSL to an opaque ARGB colour (hue in degrees), each channel rounded to 8 bits. Deterministic (`StrictMath`): Studio's
 * covers (`data/art/StudioCover.kt`) draw with it too, the same pixels on every device.
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

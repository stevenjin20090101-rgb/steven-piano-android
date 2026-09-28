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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Contrast as WCAG 2 defines it (relative luminance of sRGB, then (L1 + 0.05) / (L2 + 0.05)), and
 * the blends Android draws (source-over in sRGB, channel by channel): shared by the palette's tests
 * ([ColorTokensTest]) and the glass's ([dev.stevenjin.stevenpiano.ui.components.GlassTokensTest]).
 */
internal object Wcag {
    private fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(color: Color): Double = 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** [top] at [alpha] drawn over the opaque [under]: what the eye sees (source-over, per sRGB channel). */
    fun over(top: Color, alpha: Float, under: Color): Color = Color(
        red = top.red * alpha + under.red * (1 - alpha),
        green = top.green * alpha + under.green * (1 - alpha),
        blue = top.blue * alpha + under.blue * (1 - alpha),
    )

    /** The hue in degrees, 0 until 360 (red 0, yellow 60, green 120), as HSV has it. */
    fun hue(color: Color): Float {
        val r = color.red
        val g = color.green
        val b = color.blue
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val delta = hi - lo
        if (delta == 0f) return 0f
        val h = when (hi) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return if (h < 0f) h + 360f else h
    }
}

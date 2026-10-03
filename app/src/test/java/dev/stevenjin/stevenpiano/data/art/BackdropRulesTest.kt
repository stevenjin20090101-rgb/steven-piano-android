// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import androidx.compose.ui.graphics.Color
import dev.stevenjin.stevenpiano.ui.theme.Backdrop
import dev.stevenjin.stevenpiano.ui.theme.SilverPrimary
import dev.stevenjin.stevenpiano.ui.theme.Wcag
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backdrop's proof (DESIGN.md › v1.18 — M49): over the cover's picture, as it is prepared (blurred, its saturation
 * raised), the black [BackdropRules.dimFor] gives keeps the light words (the ink scheme's primary) at 4.5:1 over the
 * picture's brightest block, whatever the art: white, yellow, grey, red, dark, or half white and half black ([Wcag]: the
 * blend Android draws, then WCAG 2's contrast). The black is never under 0.18, and the same picture always gives the same.
 */
class BackdropRulesTest {
    private val side = BackdropRules.SIDE

    /** A [side]-square picture, every pixel [argb]. */
    private fun plain(argb: Long) = IntArray(side * side) { argb.toInt() }

    /** The pictures the rule must hold for, by name. */
    private val pictures = mapOf(
        "white" to plain(0xFFFFFFFF),
        "pure yellow" to plain(0xFFFFFF00),
        "mid grey" to plain(0xFF808080),
        "saturated red" to plain(0xFFFF0000),
        "dark" to plain(0xFF1A1E24),
        "half white, half black" to IntArray(side * side) { i -> if (i % side < side / 2) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() },
    )

    /** [pixels] as the repository makes them, with their black. */
    private fun prepared(pixels: IntArray): Pair<IntArray, Float> {
        val copy = pixels.copyOf()
        BackdropRules.prepare(copy, side, side)
        return copy to BackdropRules.dimFor(copy, side, side)
    }

    @Test
    fun `the light words keep 4,5 to 1 over the dimmed brightest block of any picture`() {
        for ((name, pixels) in pictures) {
            val (picture, dim) = prepared(pixels)
            val block = Color(BackdropRules.brightestBlock(picture, side, side))
            val under = Wcag.over(Color.Black, dim, block)
            val contrast = Wcag.contrast(SilverPrimary, under)
            assertTrue("$name: $contrast at dim $dim", contrast >= 4.5)
            // And at the foot, where the black is deeper still, all the more.
            assertTrue(name, Wcag.contrast(SilverPrimary, Wcag.over(Color.Black, (dim + Backdrop.FootDeeper).coerceAtMost(1f), block)) >= contrast)
        }
    }

    @Test
    fun `the dimming is never under 0,18, and only as deep as the brightest part needs`() {
        val dims = pictures.mapValues { (_, pixels) -> prepared(pixels).second }
        for ((name, dim) in dims) assertTrue("$name: $dim", dim >= BackdropRules.MIN_DIM)
        assertEquals(0.18f, BackdropRules.MIN_DIM)
        assertEquals("a dark cover takes the floor", BackdropRules.MIN_DIM, dims.getValue("dark"))
        assertEquals("a mid grey needs no more than the floor", BackdropRules.MIN_DIM, dims.getValue("mid grey"))
        // White needs the most: 1 − 0.14^(1/2.2); half white is as bright where it is white.
        assertEquals(0.591f, dims.getValue("white"), 0.001f)
        assertEquals(dims.getValue("white"), dims.getValue("half white, half black"), 0.001f)
        assertTrue(dims.getValue("white") > dims.getValue("pure yellow"))
        assertTrue(dims.getValue("pure yellow") > dims.getValue("saturated red"))
    }

    @Test
    fun `the same picture always gives the same picture and the same black`() {
        for ((name, pixels) in pictures) {
            val (first, firstDim) = prepared(pixels)
            val (second, secondDim) = prepared(pixels.copyOf())
            assertArrayEquals(name, first, second)
            assertEquals(name, firstDim, secondDim)
        }
        // The blur softens an edge, and the lift keeps a pure colour pure.
        val (half, _) = prepared(pictures.getValue("half white, half black"))
        val edge = half[side * (side / 2) + side / 2 - 1]
        assertTrue("softened: ${Integer.toHexString(edge)}", (edge and 0xFF) in 1..254)
        assertEquals(0xFFFF0000.toInt(), prepared(pictures.getValue("saturated red")).first[0])
    }

    @Test
    fun `the soft copy is whole at its centre and nothing at its edge`() {
        val side = BackdropRules.SIDE
        val red = 0xFFCC2030.toInt()
        val soft = BackdropRules.softened(IntArray(side * side) { red }, side, side)
        val alphaAt = { x: Int, y: Int -> soft[y * side + x] ushr 24 }
        assertEquals(255, alphaAt(side / 2, side / 2))
        assertEquals(0, alphaAt(0, 0))
        assertTrue(alphaAt(side - 1, side / 2) <= 8)
        assertTrue(alphaAt(side / 2 + side / 3, side / 2) in 1..254)
        assertEquals(red and 0xFFFFFF, soft[side / 2 * side + side / 2] and 0xFFFFFF)
    }
}

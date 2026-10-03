// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The album-colour backdrop's palette (v1.15 — M41): the art's own colours, four of them, or none for grey art. */
class ArtPaletteTest {
    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** A picture made of [parts]: each colour for its count of pixels, in order. */
    private fun picture(vararg parts: Pair<Int, Int>): IntArray = parts.flatMap { (colour, count) -> List(count) { colour } }.toIntArray()

    private fun palette(pixels: IntArray): ArtPalette? = artPalette(pixels, pixels.size, 1)

    @Test
    fun `a two-colour picture gives its two hues, padded to four from the first`() {
        val red = rgb(200, 40, 40)
        val blue = rgb(40, 60, 200)
        val palette = notNull(palette(picture(red to 6_000, blue to 4_000)))
        assertEquals(4, palette.hues.size)
        assertEquals(4, palette.saturations.size)
        assertEquals("the larger colour first", 0f, palette.hues[0], 0.5f)
        assertEquals(232.5f, palette.hues[1], 0.5f)
        assertEquals("the rest turned 30° each way from the first", listOf(30f, 330f), palette.hues.drop(2).map { Math.round(it).toFloat() })
        assertEquals(palette.saturations[0], palette.saturations[2])
        assertEquals(palette.saturations[0], palette.saturations[3])
    }

    @Test
    fun `grey art gives no backdrop - an engraving, a black-and-white photograph, a grey page with a little colour`() {
        val greys = IntArray(128 * 128) { i -> (i % 200 + 20).let { rgb(it, it, it) } }
        assertNull(artPalette(greys, 128, 128))
        val sepia = IntArray(1_000) { rgb(140, 130, 120) }
        assertNull("its best bin's chroma is under 0.12", palette(sepia))
        assertNull("the best bin decides", palette(picture(rgb(128, 128, 128) to 9_000, rgb(220, 30, 30) to 1_000)))
        assertNull("only black and white: nothing to read", palette(picture(rgb(0, 0, 0) to 500, rgb(255, 255, 255) to 500)))
        assertNull(artPalette(IntArray(0), 0, 0))
    }

    @Test
    fun `the colours kept stand 25 degrees apart in hue or a quarter apart in saturation, and black and white are skipped`() {
        val red = rgb(220, 30, 30)            // 0°
        val nearRed = rgb(220, 60, 30)        // 9°, as strong: dropped
        val paleRed = rgb(170, 110, 110)      // 0°, far less saturated: kept
        val green = rgb(40, 180, 60)
        val blue = rgb(30, 60, 220)
        val pixels = picture(
            rgb(0, 0, 0) to 20_000, rgb(255, 255, 255) to 20_000,   // skipped, however many
            red to 5_000, nearRed to 4_000, paleRed to 3_000, green to 2_000, blue to 1_000,
        )
        val palette = notNull(palette(pixels))
        assertEquals("red, green, the pale red, blue", listOf(0, 129, 0, 231), palette.hues.map { Math.round(it) })
        for (i in 0 until 4) for (j in i + 1 until 4) {
            val hues = hueDistance(palette.hues[i], palette.hues[j])
            val saturations = kotlin.math.abs(palette.saturations[i] - palette.saturations[j])
            assertTrue("$i and $j: $hues°, $saturations", hues >= ArtPaletteRules.HUE_APART || saturations >= ArtPaletteRules.SATURATION_APART)
        }
        assertFalse("the near red is not kept", palette.hues.any { Math.round(it) == 9 })
    }

    @Test
    fun `saturation is lifted by 1,35 and never past 1`() {
        // Saturation 0.75 (HSL): lifted past 1, held at 1. Saturation 0.4: lifted to 0.54.
        val strong = notNull(palette(picture(rgb(224, 32, 32) to 100)))
        assertEquals(1f, strong.saturations[0], 0f)
        val soft = notNull(palette(picture(rgb(179, 77, 77) to 100)))
        assertEquals(0.4f * 1.35f, soft.saturations[0], 0.01f)
        assertTrue(soft.saturations.all { it in 0f..1f })
    }

    @Test
    fun `the same picture gives the same palette, whatever the order of its pixels`() {
        val random = Random(41)
        val pixels = IntArray(128 * 128) { rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        val first = artPalette(pixels, 128, 128)
        assertEquals(first, artPalette(pixels.copyOf(), 128, 128))
        assertEquals("shuffled", first, artPalette(pixels.toList().shuffled(Random(7)).toIntArray(), 128, 128))
        assertEquals(4, notNull(first).hues.size)
    }

    private fun <T : Any> notNull(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}

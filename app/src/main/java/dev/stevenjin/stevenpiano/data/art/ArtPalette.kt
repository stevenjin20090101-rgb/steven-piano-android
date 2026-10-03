// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The album-colour backdrop's colours (v1.15 — M41): exactly four [hues] (degrees, 0 until 360) and their
 * [saturations] (0 to 1), strongest first. Hue and saturation only: the lightness is the appearance's
 * (`ui/theme/Backdrop.kt`), so the words over the backdrop read whatever the art.
 */
data class ArtPalette(val hues: List<Float>, val saturations: List<Float>)

/**
 * The backdrop's [ArtPalette] of a picture given as ARGB [pixels], [width] × [height] (the art's row-size decode, 128 px
 * on its shorter side): a 4-bit RGB histogram (4,096 bins, each read as the average of its pixels), near-black and
 * near-white skipped (HSL lightness outside 0.08–0.92, and anything mostly transparent); each bin scored by its count ×
 * (0.3 + its saturation); the best bins taken greedily, each at least 25° from every one taken in hue or 0.25 in
 * saturation, four at most; every saturation × 1.35, at most 1; fewer than four padded by the first hue turned 30° each
 * way (then 60°). Only colourful bins count (chroma 0.12 or more): null when there is none, or when together they hold
 * under 5 % of the pixels read, so grey art (an engraving, a black-and-white photograph, a grey page with a speck of
 * colour) gives no backdrop, while a painting over a pale ground gives its paint. Pure and deterministic: the pixels'
 * order does not matter.
 */
fun artPalette(pixels: IntArray, width: Int, height: Int): ArtPalette? {
    val total = min(pixels.size.toLong(), width.toLong().coerceAtLeast(0) * height.toLong().coerceAtLeast(0)).toInt()
    val counts = IntArray(ArtPaletteRules.BINS)
    val red = LongArray(ArtPaletteRules.BINS)
    val green = LongArray(ArtPaletteRules.BINS)
    val blue = LongArray(ArtPaletteRules.BINS)
    var read = 0
    for (i in 0 until total) {
        val argb = pixels[i]
        if ((argb ushr 24) < ArtPaletteRules.OPAQUE) continue
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val lightness = (max(r, max(g, b)) + min(r, min(g, b))) / 510f
        if (lightness < ArtPaletteRules.DARKEST || lightness > ArtPaletteRules.LIGHTEST) continue
        val bin = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
        read++
        counts[bin]++
        red[bin] += r.toLong()
        green[bin] += g.toLong()
        blue[bin] += b.toLong()
    }
    val bins = (0 until ArtPaletteRules.BINS).filter { counts[it] > 0 }.map { bin ->
        val n = counts[bin]
        Bin(bin, n, red[bin].toFloat() / n / 255f, green[bin].toFloat() / n / 255f, blue[bin].toFloat() / n / 255f)
    }.filter { it.chroma >= ArtPaletteRules.MIN_CHROMA }.sortedWith(compareByDescending<Bin> { it.score }.thenBy { it.index })
    if (bins.isEmpty() || bins.sumOf { it.count } < read * ArtPaletteRules.MIN_COLOUR_SHARE) return null
    val picks = ArrayList<Bin>(ArtPaletteRules.COLOURS)
    for (bin in bins) {
        if (picks.size == ArtPaletteRules.COLOURS) break
        if (picks.all { apart(it, bin) }) picks.add(bin)
    }
    val hues = picks.mapTo(ArrayList(ArtPaletteRules.COLOURS)) { it.hue }
    val saturations = picks.mapTo(ArrayList(ArtPaletteRules.COLOURS)) { boosted(it.saturation) }
    var turn = 0
    while (hues.size < ArtPaletteRules.COLOURS) {
        hues.add(wrap(hues[0] + ArtPaletteRules.PAD_TURNS[turn++]))
        saturations.add(saturations[0])
    }
    return ArtPalette(hues, saturations)
}

/** The rules [artPalette] works by, in one place for its test. */
object ArtPaletteRules {
    /** 16 levels a channel: 4,096 bins. */
    const val BINS = 4_096

    /** Exactly this many colours, as the backdrop has discs. */
    const val COLOURS = 4

    /** Pixels darker or lighter than these (HSL lightness) say nothing of the art's colour. */
    const val DARKEST = 0.08f
    const val LIGHTEST = 0.92f

    /** A pixel at least this opaque (of 255) counts. */
    const val OPAQUE = 128

    /** Each bin's score: its count × ([SCORE_FLOOR] + its saturation). */
    const val SCORE_FLOOR = 0.3f

    /** Two colours kept are at least this far apart in hue (degrees), or [SATURATION_APART] in saturation. */
    const val HUE_APART = 25f
    const val SATURATION_APART = 0.25f

    /** Every saturation lifted by this, never past 1. */
    const val BOOST = 1.35f

    /** Under this chroma (max − min of its channels, 0 to 1) a bin is grey and does not count. */
    const val MIN_CHROMA = 0.12f

    /** Colourful bins holding under this share of the pixels read: grey art with a speck of colour, no backdrop. */
    const val MIN_COLOUR_SHARE = 0.05f

    /** Fewer than four colours: the first's hue turned by these, in turn. */
    val PAD_TURNS = floatArrayOf(30f, -30f, 60f)
}

/** A histogram bin: its [index], how many pixels fell in it, and their average colour (0 to 1 a channel). */
private class Bin(val index: Int, val count: Int, r: Float, g: Float, b: Float) {
    val chroma: Float
    val hue: Float
    val saturation: Float
    val score: Float

    init {
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        chroma = hi - lo
        val lightness = (hi + lo) / 2f
        saturation = if (chroma == 0f) 0f else (chroma / (1f - abs(2f * lightness - 1f))).coerceIn(0f, 1f)
        hue = when {
            chroma == 0f -> 0f
            hi == r -> wrap(60f * ((g - b) / chroma))
            hi == g -> wrap(60f * ((b - r) / chroma + 2f))
            else -> wrap(60f * ((r - g) / chroma + 4f))
        }
        score = count * (ArtPaletteRules.SCORE_FLOOR + saturation)
    }
}

/** Whether [a] and [b] stand far enough apart to both be kept: in hue, or in saturation. */
private fun apart(a: Bin, b: Bin): Boolean =
    hueDistance(a.hue, b.hue) >= ArtPaletteRules.HUE_APART || abs(a.saturation - b.saturation) >= ArtPaletteRules.SATURATION_APART

/** The shorter way round the circle between two hues, in degrees (0 to 180). */
internal fun hueDistance(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}

private fun boosted(saturation: Float): Float = (saturation * ArtPaletteRules.BOOST).coerceAtMost(1f)

private fun wrap(hue: Float): Float = ((hue % 360f) + 360f) % 360f

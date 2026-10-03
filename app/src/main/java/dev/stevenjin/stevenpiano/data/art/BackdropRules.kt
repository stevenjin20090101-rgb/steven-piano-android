// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import kotlin.math.max
import kotlin.math.min

/**
 * The backdrop's picture (v1.18 — M49): the art the piece shows (its own cover, else its composer's portrait), brought
 * to [SIDE] × [SIDE], made soft and lifted ([prepare]), and the black it needs under light words ([dimFor]). Pure and
 * deterministic, on ARGB pixels; the repository does the scaling (`ArtworkRepository.backdrop`).
 */
object BackdropRules {
    /** The picture's side: the art's row-size decode (128 px) scaled to this square. */
    const val SIDE = 48

    /** The blur: [BLUR_PASSES] passes of a box blur [BLUR_RADIUS] pixels either side, near a Gaussian. */
    const val BLUR_RADIUS = 3
    const val BLUR_PASSES = 3

    /** The saturation, raised this much (a colour matrix, as Android's `ColorMatrix.setSaturation` builds it). */
    const val SATURATION = 1.6f

    /** The picture is read in blocks this many pixels square: sixteen on [SIDE] × [SIDE]. */
    const val BLOCK = 12

    /** The black brings the brightest block to at most this relative luminance (light words at 4.5:1 over it, with room)... */
    const val TARGET_LUMINANCE = 0.14

    /** ...and is never lighter than this, so even a dark cover sits a little back from the words. */
    const val MIN_DIM = 0.18f

    /** A pixel's channels weigh this much in the saturation's grey (Android's `ColorMatrix.setSaturation`). */
    private const val RED_WEIGHT = 0.213f
    private const val GREEN_WEIGHT = 0.715f
    private const val BLUE_WEIGHT = 0.072f

    /** [pixels] ([width] × [height], ARGB) made the backdrop's picture, in place: opaque, blurred, then its saturation raised. */
    fun prepare(pixels: IntArray, width: Int, height: Int) {
        opaque(pixels)
        repeat(BLUR_PASSES) { blur(pixels, width, height, BLUR_RADIUS) }
        saturate(pixels, SATURATION)
    }

    /**
     * The black laid over the picture so light words keep 4.5:1 over its brightest part: of [pixels]' blocks ([BLOCK]
     * square, the means of their channels), the brightest one's relative luminance L gives `1 − (0.14 / L)^(1 / 2.2)`,
     * never under [MIN_DIM].
     */
    fun dimFor(pixels: IntArray, width: Int, height: Int): Float {
        val brightest = luminance(brightestBlock(pixels, width, height))
        if (brightest <= TARGET_LUMINANCE) return MIN_DIM
        val dim = 1.0 - StrictMath.pow(TARGET_LUMINANCE / brightest, 1.0 / 2.2)
        return max(MIN_DIM, dim.toFloat())
    }

    /** The mean colour (opaque ARGB) of [pixels]' brightest block, by relative luminance; the first of equals. */
    fun brightestBlock(pixels: IntArray, width: Int, height: Int): Int {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return OPAQUE_BLACK
        var best = OPAQUE_BLACK
        var bestLuminance = -1.0
        for (top in 0 until height step BLOCK) {
            for (left in 0 until width step BLOCK) {
                val mean = blockMean(pixels, width, left, top, min(left + BLOCK, width), min(top + BLOCK, height))
                val l = luminance(mean)
                if (l > bestLuminance) {
                    best = mean
                    bestLuminance = l
                }
            }
        }
        return best
    }

    /** WCAG 2's relative luminance of an ARGB colour's sRGB channels (its alpha ignored). */
    fun luminance(argb: Int): Double =
        0.2126 * linear((argb shr 16) and 0xFF) + 0.7152 * linear((argb shr 8) and 0xFF) + 0.0722 * linear(argb and 0xFF)

    private fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else StrictMath.pow((c + 0.055) / 1.055, 2.4)
    }

    /** The mean of the block from ([left], [top]) until ([right], [bottom]), channel by channel, rounded. */
    private fun blockMean(pixels: IntArray, width: Int, left: Int, top: Int, right: Int, bottom: Int): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        for (y in top until bottom) {
            for (x in left until right) {
                val p = pixels[y * width + x]
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
        }
        val n = ((right - left) * (bottom - top)).toLong()
        return argb(((r + n / 2) / n).toInt(), ((g + n / 2) / n).toInt(), ((b + n / 2) / n).toInt())
    }

    /** Every pixel made opaque, laid over black by its alpha (a transparent picture's clear parts go dark). */
    private fun opaque(pixels: IntArray) {
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24
            if (a == 0xFF) continue
            pixels[i] = argb(((p shr 16) and 0xFF) * a / 255, ((p shr 8) and 0xFF) * a / 255, (p and 0xFF) * a / 255)
        }
    }

    /**
     * One pass of a box blur [radius] wide either side, across then down, each channel the rounded mean of the 2r + 1
     * pixels about it; past an edge the edge pixel stands in. In place.
     */
    internal fun blur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        if (width <= 0 || height <= 0 || radius <= 0) return
        val line = IntArray(max(width, height))
        for (y in 0 until height) boxLine(pixels, y * width, 1, width, radius, line)
        for (x in 0 until width) boxLine(pixels, x, width, height, radius, line)
    }

    /** The box over one line of [count] pixels from [start], [stride] apart, through [scratch]: a running sum per channel. */
    private fun boxLine(pixels: IntArray, start: Int, stride: Int, count: Int, radius: Int, scratch: IntArray) {
        for (i in 0 until count) scratch[i] = pixels[start + i * stride]
        val span = 2 * radius + 1
        var r = 0
        var g = 0
        var b = 0
        for (k in -radius..radius) {
            val p = scratch[k.coerceIn(0, count - 1)]
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        for (i in 0 until count) {
            pixels[start + i * stride] = argb((r + span / 2) / span, (g + span / 2) / span, (b + span / 2) / span)
            val out = scratch[(i - radius).coerceIn(0, count - 1)]
            val into = scratch[(i + radius + 1).coerceIn(0, count - 1)]
            r += ((into shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            g += ((into shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            b += (into and 0xFF) - (out and 0xFF)
        }
    }

    /** Each pixel's saturation times [factor] about its grey (Android's saturation matrix), each channel held to 0–255. */
    internal fun saturate(pixels: IntArray, factor: Float) {
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val grey = RED_WEIGHT * r + GREEN_WEIGHT * g + BLUE_WEIGHT * b
            pixels[i] = argb(lifted(grey, r, factor), lifted(grey, g, factor), lifted(grey, b, factor))
        }
    }

    private fun lifted(grey: Float, channel: Int, factor: Float): Int = (grey + factor * (channel - grey) + 0.5f).toInt().coerceIn(0, 255)

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private const val OPAQUE_BLACK = 0xFF shl 24
}

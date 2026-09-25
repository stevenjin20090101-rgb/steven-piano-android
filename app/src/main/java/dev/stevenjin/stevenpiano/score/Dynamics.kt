// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================


package dev.stevenjin.stevenpiano.score

/**
 * A dynamic under the treble staff of [system]: [band] ([Dynamics.PP] … [Dynamics.FF]) at the first
 * onset of [bar], its glyphs' left edge at [x] and their SMuFL baseline at [y] (page coordinates).
 */
data class DynamicMark(val system: Int, val bar: Int, val x: Float, val y: Float, val band: Int) {
    /** Bravura's dynamics glyphs for the band ("mp" is m then p). */
    val glyphs: String get() = Dynamics.glyphs(band)

    /** "pp", "p", "mp", "mf", "f" or "ff". */
    val name: String get() = Dynamics.name(band)
}

/**
 * Dynamics (DESIGN.md › v1.3 › Score fidelity): each bar's loudness is the mean velocity of the
 * notes struck in it, on both staves, read in bands: pp below 32, p below 48, mp below 64, mf below
 * 80, f below 96, ff from 96. The first bar with notes is marked, and after that a bar whose band is
 * not the last marked one: in a performance only when it is two bands or more away, so a nuance does
 * not litter the score. Bars without notes neither mark nor reset. Pure.
 */
object Dynamics {
    const val PP = 0
    const val P = 1
    const val MP = 2
    const val MF = 3
    const val F = 4
    const val FF = 5

    /** In a performance a band must move this far from the last mark to be marked. */
    const val PERFORMED_CHANGE = 2

    /** The lowest velocity of each band from p up. */
    private val FLOORS = doubleArrayOf(32.0, 48.0, 64.0, 80.0, 96.0)
    private val NAMES = arrayOf("pp", "p", "mp", "mf", "f", "ff")

    /** Bravura: p U+E520, m U+E521, f U+E522; a band's letters in order. */
    private val GLYPHS = Array(NAMES.size) { band ->
        NAMES[band].map { letter ->
            when (letter) {
                'p' -> ''
                'm' -> ''
                else -> ''
            }
        }.joinToString("")
    }

    /** The band a mean velocity of [velocity] reads as. */
    fun band(velocity: Double): Int {
        var band = PP
        while (band < FLOORS.size && velocity >= FLOORS[band]) band++
        return band
    }

    fun name(band: Int): String = NAMES[band.coerceIn(PP, FF)]

    fun glyphs(band: Int): String = GLYPHS[band.coerceIn(PP, FF)]

    /**
     * For each bar's mean velocity in [means] (NaN: no notes in it), the band to mark there in
     * [out], or -1 for none: the first bar with notes, then each bar whose band differs from the last
     * marked one ([performed]: by [PERFORMED_CHANGE] bands or more). Returns how many are marked.
     */
    fun marks(means: DoubleArray, performed: Boolean, out: IntArray): Int {
        val change = if (performed) PERFORMED_CHANGE else 1
        var last = -1
        var count = 0
        for (b in means.indices) {
            out[b] = -1
            if (means[b].isNaN()) continue
            val band = band(means[b])
            if (last < 0 || kotlin.math.abs(band - last) >= change) {
                out[b] = band
                last = band
                count++
            }
        }
        return count
    }
}

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
 * How the score is drawn, as numbers (v1.13 — M32, moved out of `ui.components.ScorePages`): sizes in dp, which the
 * tablet's painter turns into pixels and the web panel's display list ([ScoreDisplayList]) draws as CSS pixels, the
 * Bravura (SMuFL) glyphs it sets, the tempo mark's proportions, and the per-system and per-frame budgets. One table,
 * so the two painters cannot drift apart on a size. Pure.
 */
object ScoreStyle {
    /** Staff line spacing: the score's unit. SMuFL fonts are drawn at four spaces to the em. */
    const val LINE_GAP_DP = 6f
    const val CURSOR_WIDTH_DP = 2f

    /** Every line the score draws but the final bar line's thick stroke: the app's hairline. */
    const val HAIRLINE_DP = 1f

    /** A ledger line runs this far past each side of its head. */
    const val LEDGER_OVERHANG_DP = 2f

    /** A final bar line's thick stroke, and the gap before it. */
    const val FINAL_STROKE_DP = 3f
    const val FINAL_GAP_DP = 2f

    /** The score's fingering numerals follow the font scale up to this, then keep their size with the heads. */
    const val NUMERAL_MAX_SCALE = 1.3f

    /** A chord name keeps this far above the bar number, and from the name before it. */
    const val CHORD_GAP_DP = 2f
    const val CHORD_SPACING_DP = 6f

    /** A horizontal drag this long turns the page. */
    const val SWIPE_DP = 40f

    /** Bravura's G clef rises this many spaces above the treble staff's top line; a bar number sits clear of it. */
    const val G_CLEF_RISE = 1.4f
    const val NUMBER_CLEARANCE_DP = 1f

    /** Bravura (SMuFL) glyphs, as code points. */
    const val G_CLEF = 0xE050
    const val F_CLEF = 0xE062
    const val SHARP = 0xE262
    const val FLAT = 0xE260
    const val NATURAL = 0xE261
    const val WHOLE_HEAD = 0xE0A2
    const val HALF_HEAD = 0xE0A3
    const val BLACK_HEAD = 0xE0A4
    const val FLAG_8TH_UP = 0xE240
    const val FLAG_8TH_DOWN = 0xE241
    const val FLAG_16TH_UP = 0xE242
    const val FLAG_16TH_DOWN = 0xE243
    const val DOT = 0xE1E7
    const val TIME_DIGIT_0 = 0xE080
    const val REST_WHOLE = 0xE4E3
    const val REST_HALF = 0xE4E4
    const val REST_QUARTER = 0xE4E5
    const val REST_8TH = 0xE4E6
    const val REST_16TH = 0xE4E7

    /** The tempo mark's note: Bravura's quarter note, stem up (U+E1D5), dotted with U+E1E7 in a compound metre. */
    const val TEMPO_NOTE = 0xE1D5

    /**
     * The tempo mark's note is set in Bravura at this multiple of the bar number's (eyebrow) size, so it
     * grows with the text; its head sits on the text's baseline (the head reaches 0.564 of its space
     * below its own baseline), and the glyph is 1.328 spaces wide, the dot 0.4.
     */
    const val TEMPO_NOTE_SCALE = 1.5f
    const val TEMPO_HEAD_BELOW = 0.564f
    const val TEMPO_NOTE_RISE = 3.5f
    const val TEMPO_NOTE_WIDTH = 1.328f
    const val TEMPO_DOT_GAP = 0.3f
    const val TEMPO_DOT_WIDTH = 0.4f
    const val TEMPO_TEXT_GAP = 0.6f

    /**
     * A tempo mark (and a chord name) keeps this far (in staff spaces) above the notes under it: stems,
     * flags, beams, heads and their accidentals, as the layout's skyline has them.
     */
    const val TEMPO_CLEARANCE = 0.5f

    /** A tie rises this share of its length, held to 0.3 to 0.9 of a staff space. */
    const val TIE_RISE = 0.15f
    const val TIE_LOWEST = 0.3f
    const val TIE_HIGHEST = 0.9f

    /** Notes drawn per frame at most, however dense the file: the roll and the score's overlay alike. */
    const val MAX_NOTE_DRAWS = 4_000

    /** A system's run of rests, numerals, beams or ties as the score's page draws it: its first [MAX_NOTE_DRAWS] only. */
    fun capped(range: IntRange): IntRange =
        if (range.last - range.first + 1 > MAX_NOTE_DRAWS) range.first until range.first + MAX_NOTE_DRAWS else range

    /** From the treble's top line up to a bar number's baseline, in pixels at [space] and [density]: over the clef's top, never on the staff. */
    fun numberLift(space: Float, density: Float): Float = G_CLEF_RISE * space + NUMBER_CLEARANCE_DP * density

    /** A tie's control point's offset for a tie [length] long at [space] (up when [above]): a quadratic's middle reaches half of it. */
    fun tieBow(length: Float, space: Float, above: Boolean): Float {
        val rise = (length * TIE_RISE).coerceIn(TIE_LOWEST * space, TIE_HIGHEST * space)
        return if (above) -2 * rise else 2 * rise
    }

    /** The glyph of key-signature or time-signature sign [kind] ([Sign]). */
    fun sign(kind: Int): Int = when (kind) {
        Sign.G_CLEF -> G_CLEF
        Sign.F_CLEF -> F_CLEF
        Sign.SHARP -> SHARP
        Sign.FLAT -> FLAT
        Sign.NATURAL -> NATURAL
        else -> TIME_DIGIT_0 + (kind - Sign.DIGIT).coerceIn(0, 9)
    }

    /** The glyph of accidental [kind] ([Accidental]). */
    fun accidental(kind: Int): Int = when (kind) {
        Accidental.SHARP -> SHARP
        Accidental.FLAT -> FLAT
        else -> NATURAL
    }

    /** The glyph of head [kind] ([Head]). */
    fun head(kind: Int): Int = when (kind) {
        Head.WHOLE -> WHOLE_HEAD
        Head.HALF -> HALF_HEAD
        else -> BLACK_HEAD
    }

    /** The flag for [count] flags (1 an eighth, 2 a sixteenth) on a stem [up] or down. */
    fun flag(count: Int, up: Boolean): Int = when {
        count >= 2 -> if (up) FLAG_16TH_UP else FLAG_16TH_DOWN
        else -> if (up) FLAG_8TH_UP else FLAG_8TH_DOWN
    }

    /** The rest of [value] sixteenths ([Rests]). */
    fun rest(value: Int): Int = when {
        value >= Rests.WHOLE -> REST_WHOLE
        value >= Rests.HALF -> REST_HALF
        value >= Rests.QUARTER -> REST_QUARTER
        value >= Rests.EIGHTH -> REST_8TH
        else -> REST_16TH
    }

    /** A code point as text. */
    fun text(codePoint: Int): String = String(Character.toChars(codePoint))
}

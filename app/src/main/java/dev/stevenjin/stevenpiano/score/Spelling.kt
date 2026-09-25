// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================


package dev.stevenjin.stevenpiano.score

/** The accidental a note head shows. */
object Accidental {
    const val NONE = 0
    const val SHARP = 1
    const val FLAT = 2
    const val NATURAL = 3

    /** The accidental that writes [alteration] (+1, -1 or 0). */
    fun of(alteration: Int): Int = when (alteration) {
        1 -> SHARP
        -1 -> FLAT
        else -> NATURAL
    }
}

/**
 * How a key is written in a key signature of [sharps] sharps (negative: flats), as engraving does
 * it. A note that belongs to the key takes the key's own spelling: in E♭ major MIDI 70 is B♭, in
 * C♯ major MIDI 60 is B♯. A note outside it is, in order of preference, the natural of a letter the
 * key alters (B♮ in E♭ major), a flat in a flat key (D♭ in E♭ major), or a sharp otherwise, which
 * is also how music without a key signature reads (C major: sharps, as the staff always drew).
 *
 * A written note is a letter at a diatonic step (C-1 is 0, C4 is 35, as `StaffPitch.diatonic`
 * counts) and an alteration: +1 sharp, -1 flat, 0 natural. Which of those needs an accidental
 * drawn depends on the bar so far: see [BarAccidentals]. Pure and allocation-free.
 */
object Spelling {
    /** Pitch class of each letter's natural: C D E F G A B. */
    private val NATURAL = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    /** The letters sharps are added in (F C G D A E B) and flats (B E A D G C F); C = 0 … B = 6. */
    private val SHARP_ORDER = intArrayOf(3, 0, 4, 1, 5, 2, 6)
    private val FLAT_ORDER = intArrayOf(6, 2, 5, 1, 4, 0, 3)

    /** For each key (-7..7, offset by 7) and pitch class: the letter times 4 plus the alteration + 1. */
    private val TABLE = Array(15) { spellings(it - 7) }

    /** The alteration the key of [sharps] gives [letter] (0 = C … 6 = B). */
    fun keyAlteration(letter: Int, sharps: Int): Int {
        val s = sharps.coerceIn(-7, 7)
        return when {
            s > 0 -> if (SHARP_ORDER.indexOf(letter) in 0 until s) 1 else 0
            s < 0 -> if (FLAT_ORDER.indexOf(letter) in 0 until -s) -1 else 0
            else -> 0
        }
    }

    /** The letter (0 = C … 6 = B) [key] is written with in the key of [sharps]. */
    fun letter(key: Int, sharps: Int): Int = entry(key, sharps) shr 2

    /** The alteration [key] is written with in the key of [sharps]: +1 sharp, -1 flat, 0 natural. */
    fun alteration(key: Int, sharps: Int): Int = (entry(key, sharps) and 3) - 1

    /** The diatonic step [key] is written at in the key of [sharps]: its letter in the octave that letter's natural is in. */
    fun step(key: Int, sharps: Int): Int {
        val natural = key - alteration(key, sharps)
        return Math.floorDiv(natural, 12) * 7 + letter(key, sharps)
    }

    /** "B♭4", "C♯4", "B♮3": the written note, for tests and descriptions. */
    fun name(key: Int, sharps: Int): String {
        val step = step(key, sharps)
        val sign = when (alteration(key, sharps)) {
            1 -> "♯"
            -1 -> "♭"
            else -> if (keyAlteration(letter(key, sharps), sharps) != 0) "♮" else ""
        }
        return "${"CDEFGAB"[Math.floorMod(step, 7)]}$sign${Math.floorDiv(step, 7) - 1}"
    }

    private fun entry(key: Int, sharps: Int): Int = TABLE[sharps.coerceIn(-7, 7) + 7][Math.floorMod(key, 12)]

    private fun spellings(sharps: Int): IntArray {
        val altered = IntArray(7) { keyAlteration(it, sharps) }
        return IntArray(12) { pc ->
            // In the key: the one letter whose altered pitch is this one.
            var letter = (0..6).firstOrNull { Math.floorMod(NATURAL[it] + altered[it], 12) == pc }
            var alteration = letter?.let { altered[it] } ?: 0
            if (letter == null) {
                // The natural of a letter the key alters, then a flat (flat keys) or a sharp.
                letter = (0..6).firstOrNull { altered[it] != 0 && NATURAL[it] == pc }
                alteration = 0
            }
            if (letter == null) {
                alteration = if (sharps < 0) -1 else 1
                letter = (0..6).first { Math.floorMod(NATURAL[it] + alteration, 12) == pc }
            }
            letter * 4 + alteration + 1
        }
    }
}

/**
 * Which accidentals a bar needs, as engraving writes them: an accidental holds for the rest of the
 * bar at its own staff position (line or space, octave and staff), so a repeated altered note
 * shows it once, and a return to the key's own note shows a natural (or the key's sharp or flat).
 * [reset] at every bar line and key change. Allocation-free.
 */
class BarAccidentals {
    private val inForce = IntArray(2 * STEPS)
    private val stamp = IntArray(2 * STEPS)
    private var generation = 1
    private var sharps = 0

    /** A new bar, in the key of [sharps]: only the key signature is in force. */
    fun reset(sharps: Int) {
        this.sharps = sharps
        generation++
    }

    /**
     * The [Accidental] a note at diatonic [step] with [alteration] shows on the [treble] or bass
     * staff: [Accidental.NONE] when the bar already reads it so; otherwise its sign, which then
     * holds for the rest of the bar.
     */
    fun accidental(treble: Boolean, step: Int, alteration: Int): Int {
        val slot = (if (treble) 0 else STEPS) + (step + STEP_OFFSET).coerceIn(0, STEPS - 1)
        val current = if (stamp[slot] == generation) inForce[slot] else Spelling.keyAlteration(Math.floorMod(step, 7), sharps)
        if (current == alteration) return Accidental.NONE
        stamp[slot] = generation
        inForce[slot] = alteration
        return Accidental.of(alteration)
    }

    private companion object {
        /** Steps from B♯ below C-1 (-1) to past G9 (74), with room. */
        const val STEP_OFFSET = 2
        const val STEPS = 80
    }
}

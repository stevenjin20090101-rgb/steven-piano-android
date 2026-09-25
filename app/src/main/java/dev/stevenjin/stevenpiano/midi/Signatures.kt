// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================


package dev.stevenjin.stevenpiano.midi

/**
 * A time signature (meta FF 58): [numerator] beats of the [denominator] note (a power of two) per
 * bar, from [tick] ([atMicros]) on. The file's clocks-per-click and 32nds-per-quarter bytes mean
 * nothing to the score and are not kept.
 */
data class TimeSignature(val tick: Long, val atMicros: Long, val numerator: Int, val denominator: Int) {
    /** Usable for bars: at least one beat, of a whole note down to a 64th. */
    val valid: Boolean get() = numerator in 1..MAX_NUMERATOR && denominator in DENOMINATORS

    /** The same metre as [other], wherever each one starts. */
    fun sameAs(other: TimeSignature): Boolean = numerator == other.numerator && denominator == other.denominator

    /** Ticks in [bars] whole bars at [ppq] ticks per quarter note (rounded down, so bar lines never drift). */
    fun ticksIn(bars: Long, ppq: Int): Long = bars * numerator * 4L * ppq / denominator

    override fun toString(): String = "$numerator/$denominator@$tick"

    companion object {
        const val MAX_NUMERATOR = 255
        private val DENOMINATORS = setOf(1, 2, 4, 8, 16, 32, 64)

        /** 4/4 from the start: what a file has until it says otherwise. */
        val Common = TimeSignature(0L, 0L, 4, 4)
    }
}

/**
 * A key signature (meta FF 59): [sharps] sharps, or flats when negative (-7..7), in the major or
 * [minor] mode, from [tick] ([atMicros]) on. The score draws it at every system and spells notes
 * in it.
 */
data class KeySignature(val tick: Long, val atMicros: Long, val sharps: Int, val minor: Boolean) {
    /** The same signature as [other], wherever each one starts. */
    fun sameAs(other: KeySignature): Boolean = sharps == other.sharps && minor == other.minor

    /**
     * The key [semitones] higher (lower when negative), as the transposed music reads: each semitone
     * adds seven sharps, folded into the plainest spelling (at most five flats or six sharps).
     * Unchanged at 0, so a file's own seven sharps or flats stay as written.
     */
    fun transposed(semitones: Int): KeySignature {
        if (semitones == 0) return this
        var shifted = Math.floorMod(sharps + 7 * semitones, 12)
        if (shifted > 6) shifted -= 12
        return copy(sharps = shifted)
    }

    override fun toString(): String = "${if (sharps < 0) "${-sharps}b" else "$sharps#"}${if (minor) "m" else ""}@$tick"

    companion object {
        /** A key signature byte pair the score can use: -7..7 accidentals, major (0) or minor (1). */
        fun isValid(sharps: Int, mode: Int): Boolean = sharps in -7..7 && (mode == 0 || mode == 1)
    }
}

/**
 * The file's signatures as the score uses them. The tracks' metas arrive in reading order; they are
 * put in time order (stably), a signature at the same tick as another replaces it (the later one
 * wins, as with tempo), unusable ones are dropped, and one that only repeats the signature already
 * in force is removed, so a sequencer's repeated 4/4 never starts a bar of its own.
 */
internal object SignatureLists {
    /** A signature meta as read: its tick and its two leading bytes. */
    class Raw(val tick: Long, val first: Int, val second: Int)

    /** Never empty: 4/4 from the start until the file's first time signature. */
    fun times(raw: List<Raw>, tempo: TempoMap): List<TimeSignature> {
        val read = raw.sortedBy { it.tick }
            .map { TimeSignature(it.tick, tempo.tickToMicros(it.tick), it.first, 1 shl it.second.coerceAtMost(30)) }
            .filter { it.valid }
        val atStart = if (read.firstOrNull()?.tick == 0L) read else listOf(TimeSignature.Common) + read
        return collapse(atStart, { it.tick }) { a, b -> a.sameAs(b) }
    }

    fun keys(raw: List<Raw>, tempo: TempoMap): List<KeySignature> =
        collapse(
            raw.sortedBy { it.tick }
                .filter { KeySignature.isValid(it.first, it.second) }
                .map { KeySignature(it.tick, tempo.tickToMicros(it.tick), it.first, it.second == 1) },
            { it.tick },
        ) { a, b -> a.sameAs(b) }

    /** One signature per tick (the last), and none that repeats the one before it. */
    private fun <T> collapse(sorted: List<T>, tick: (T) -> Long, same: (T, T) -> Boolean): List<T> {
        val perTick = ArrayList<T>(sorted.size)
        for (s in sorted) {
            if (perTick.isNotEmpty() && tick(perTick.last()) == tick(s)) perTick[perTick.size - 1] = s else perTick += s
        }
        val out = ArrayList<T>(perTick.size)
        for (s in perTick) if (out.isEmpty() || !same(out.last(), s)) out += s
        return out
    }
}

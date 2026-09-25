// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.score

import dev.stevenjin.stevenpiano.midi.KeySignature
import dev.stevenjin.stevenpiano.midi.NoteList
import dev.stevenjin.stevenpiano.midi.TempoMap
import dev.stevenjin.stevenpiano.midi.TimeSignature
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A piece's chord names (DESIGN.md › v1.3 › The waterfall format): the chord that begins at each
 * [startMicros], as a [root] pitch class (0 = C), a [quality] ([Chords.MAJOR] …), the [bass] pitch
 * class when it is not the root (-1 when it is), and the key it is spelled in ([sharps], negative
 * for flats), all in the file's own pitches; [name] spells one, transposed. In time order.
 */
class ChordTrack internal constructor(
    val startMicros: LongArray,
    private val root: ByteArray,
    private val quality: ByteArray,
    private val bass: ByteArray,
    private val sharps: ByteArray,
) {
    val size: Int get() = startMicros.size

    fun root(i: Int): Int = root[i].toInt()

    fun quality(i: Int): Int = quality[i].toInt()

    /** The bass's pitch class when it is not the root, else -1. */
    fun bass(i: Int): Int = bass[i].toInt()

    /** Chord [i]'s name, as the music reads [transpose] semitones up: "B♭/D", "F♯m7", "Gsus4". */
    fun name(i: Int, transpose: Int = 0): String {
        val key = if (transpose == 0) sharps[i].toInt() else KeySignature(0, 0, sharps[i].toInt(), false).transposed(transpose).sharps
        val b = bass[i].toInt()
        return Chords.name(
            Math.floorMod(root[i] + transpose, 12),
            quality[i].toInt(),
            if (b < 0) -1 else Math.floorMod(b + transpose, 12),
            key,
        )
    }

    /** The first chord starting at or after [micros] ([size] when there is none). */
    fun firstAtOrAfter(micros: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (startMicros[mid] < micros) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        val Empty = ChordTrack(LongArray(0), ByteArray(0), ByteArray(0), ByteArray(0), ByteArray(0))
    }
}

/**
 * Chord names (DESIGN.md › v1.3 › The waterfall format), found per beat: each window (a beat of the
 * time signature; a dotted quarter in 6/8, 9/8 and 12/8; half a bar in x/2; the whole bar in 3/8)
 * weighs the pitch classes sounding in it by how much of it they fill, twice for a note struck in
 * it; a note merely running on from before for less than [TAIL] of the window does not count. Each
 * of the 144 chords (twelve roots, [SUFFIX]'s twelve kinds) scores the weight of its tones present,
 * less 0.6 times the weight of the others and 0.2 for each tone missing, plus [DOUBLED] for each
 * extra key on its root (up to two) and [ON_BASS] when its root is the bass, less [EXTENSION] for
 * each tone past the triad. The bass is the lowest key struck in the window's first half or held
 * from before.
 *
 * Changes are found over the whole piece at once (a Viterbi over the windows, a change costing
 * [CHANGE]), so a beat that could be two chords follows its neighbours: an arpeggio whose third comes
 * on the second beat is named from its first, and a passing note does not flip the name back and
 * forth. A stretch is named only when it is confident: at least two pitch classes, more than one line
 * sounding ([MIN_VOICES]), and its chord ahead
 * of every chord of other notes by [MARGIN] (C6 and Am7 are the same notes, told apart by the bass;
 * a bare fifth is major or minor, and so neither). Otherwise, and in silence, the name before holds.
 * Names come only at changes, at most one a window, where the chord's first tone sounds in it.
 *
 * Spelled in the key: the key signature in force, or, for a file without one, the key its notes
 * suggest (Krumhansl and Kessler's profiles). A root in the key takes the key's spelling; else the
 * natural of a letter the key alters; else a sharp for a diminished chord (a leading tone: F♯dim in
 * C) and a flat for the rest (B♭ in C major, E♭ in G major, as borrowed chords are written). A slash
 * bass that is a chord tone is spelled from the root (B♭/D, not B♭/E♭♭ or A♯/D).
 *
 * Pure: it runs once per piece off the main thread.
 */
object Chords {
    const val MAJOR = 0
    const val MINOR = 1
    const val DIM = 2
    const val AUG = 3
    const val SUS2 = 4
    const val SUS4 = 5
    const val SIX = 6
    const val SEVEN = 7
    const val MAJ7 = 8
    const val M7 = 9
    const val ADD9 = 10
    const val MAJ9 = 11

    /** What follows the root in a chord's name, by quality. */
    val SUFFIX = arrayOf("", "m", "dim", "aug", "sus2", "sus4", "6", "7", "maj7", "m7", "add9", "maj9")

    /** Each quality's tones, in semitones above the root (the ninth folded into the octave). */
    private val TONES = arrayOf(
        intArrayOf(0, 4, 7), intArrayOf(0, 3, 7), intArrayOf(0, 3, 6), intArrayOf(0, 4, 8),
        intArrayOf(0, 2, 7), intArrayOf(0, 5, 7), intArrayOf(0, 4, 7, 9), intArrayOf(0, 4, 7, 10),
        intArrayOf(0, 4, 7, 11), intArrayOf(0, 3, 7, 10), intArrayOf(0, 2, 4, 7), intArrayOf(0, 2, 4, 7, 11),
    )
    private const val QUALITIES = 12
    private const val CHORDS = 12 * QUALITIES

    /** The weight of the tones outside a chord counts against it this much... */
    const val OUTSIDE = 0.6f

    /** ...and each of its tones not sounding this much. */
    const val MISSING = 0.2f

    /** A chord gains this for each extra key on its root (two at most)... */
    const val DOUBLED = 0.1f

    /** ...and this when its root is the bass: enough to tell C6 from Am7, never to outweigh a missing tone. */
    const val ON_BASS = 0.15f

    /**
     * A change of chord costs this much evidence (in weight: a struck note filling a window weighs 2):
     * a chord of one beat must outweigh its neighbours by twice this. At 0.6 Bach's C major prelude
     * flipped between two diminished triads within its bar 12; at 1.2 it reads a chord a bar, as
     * written, and piano-midi.de's pieces carry a sixth fewer names (flips of that kind).
     */
    const val CHANGE = 1.2f

    /** A stretch's chord must beat every chord of other notes by this much to be named. */
    const val MARGIN = 0.1f

    /** A note running on from before counts in a window only when it fills this share of it. */
    const val TAIL = 0.2

    /**
     * A window is evidence of harmony only when its notes together sound for more than this many
     * windows' length: more than one line. A melody alone (a fugue's subject, a run in octaves' absence)
     * names no chords, where each beat's two or three notes in turn read as sus chords.
     */
    const val MIN_VOICES = 1.25

    /**
     * Each tone past the triad (the sixth, the seventh, the ninth) costs this much, so a passing note
     * in a run is not taken for an added tone: with it, maj9 is 6.7 % of piano-midi.de's chord names
     * instead of 18.7 % (MAESTRO's performances: 5.5 % instead of 22.8 %), and Bach's C major prelude
     * reads as written (without it, C6 in bar 4 and Cmaj9 in bar 34).
     */
    const val EXTENSION = 0.4f

    /** Pieces are named up to this many windows (about three hours at a beat a half second); past it the last name holds. */
    const val MAX_WINDOWS = 20_000

    /** Each quality's tones as a 12-bit mask for each root. */
    private val MASK = IntArray(CHORDS).also { mask ->
        for (r in 0 until 12) for (q in 0 until QUALITIES) {
            var bits = 0
            for (t in TONES[q]) bits = bits or (1 shl ((r + t) % 12))
            mask[r * QUALITIES + q] = bits
        }
    }

    // --- Names ------------------------------------------------------------------------------------

    /** Letters' natural pitch classes, C D E F G A B. */
    private val NATURAL = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private const val LETTERS = "CDEFGAB"

    /** Letters from a root to each interval's tone (the second, the thirds, the fourth, the fifths, the sixth, the sevenths). */
    private val STEPS = intArrayOf(0, 1, 1, 2, 2, 3, 4, 4, 4, 5, 6, 6)

    /** A chord's name: [root] and [bass] pitch classes (bass -1: the root), [quality], spelled in the key of [sharps]. */
    fun name(root: Int, quality: Int, bass: Int, sharps: Int): String {
        val (letter, alteration) = spellRoot(root, quality, sharps)
        val out = StringBuilder(10)
        out.append(LETTERS[letter]).append(sign(alteration)).append(SUFFIX[quality.coerceIn(0, QUALITIES - 1)])
        if (bass >= 0 && bass != root) {
            val interval = Math.floorMod(bass - root, 12)
            val chordTone = (MASK[root * QUALITIES + quality.coerceIn(0, QUALITIES - 1)] shr bass) and 1 == 1
            var bassLetter = -1
            var bassAlteration = 0
            if (chordTone) {
                bassLetter = (letter + STEPS[interval]) % 7
                bassAlteration = wrap(bass - NATURAL[bassLetter])
                if (bassAlteration !in -1..1) bassLetter = -1
            }
            if (bassLetter < 0) {
                val spelled = spellRoot(bass, MAJOR, sharps)
                bassLetter = spelled.first
                bassAlteration = spelled.second
            }
            out.append('/').append(LETTERS[bassLetter]).append(sign(bassAlteration))
        }
        return out.toString()
    }

    private fun sign(alteration: Int): String = when (alteration) {
        1 -> "♯"
        -1 -> "♭"
        else -> ""
    }

    /** -6..5: a pitch-class difference as the nearest alteration. */
    private fun wrap(d: Int): Int {
        val m = Math.floorMod(d, 12)
        return if (m > 6) m - 12 else m
    }

    /** The letter (0 = C) and alteration a chord root of pitch class [pc] is written with in the key of [sharps]. */
    private fun spellRoot(pc: Int, quality: Int, sharps: Int): Pair<Int, Int> {
        val key = 60 + pc
        val letter = Spelling.letter(key, sharps)
        val alteration = Spelling.alteration(key, sharps)
        // In the key (the key's own spelling), or the natural of a letter the key alters: as Spelling writes it.
        if (alteration == Spelling.keyAlteration(letter, sharps) || (alteration == 0 && Spelling.keyAlteration(letter, sharps) != 0)) {
            return letter to alteration
        }
        // Outside the key: a diminished chord's root is a leading tone (a sharp); the others are written flat.
        return if (quality == DIM) {
            val below = (0..6).first { Math.floorMod(NATURAL[it] + 1, 12) == pc }
            below to 1
        } else {
            val above = (0..6).first { Math.floorMod(NATURAL[it] - 1, 12) == pc }
            above to -1
        }
    }

    // --- Detection -----------------------------------------------------------------------------------

    /**
     * The chords of [notes] (drums, on channel 10, left out) in the bars starting at [bars] (as
     * `MidiPiece.barStartsMicros`), with the file's [timeSignatures] and [keySignatures].
     */
    fun detect(
        notes: NoteList,
        tempo: TempoMap,
        bars: LongArray,
        timeSignatures: List<TimeSignature> = listOf(TimeSignature.Common),
        keySignatures: List<KeySignature> = emptyList(),
    ): ChordTrack {
        if (notes.size == 0) return ChordTrack.Empty
        val windows = Windows(notes, tempo, bars, timeSignatures)
        val count = windows.count
        if (count == 0) return ChordTrack.Empty
        windows.weigh()
        val path = decode(windows)
        val keys = keySignatures.sortedBy { it.atMicros }
        val guessed = if (keys.isEmpty()) guessKey(notes) else 0
        val out = Labels()
        var last = -1
        var k = 0
        while (k < count) {
            val chord = path[k]
            var end = k + 1
            while (end < count && path[end] == chord) end++
            // A change the Viterbi could place anywhere in a stretch without evidence (a single note,
            // silence) is placed where the new chord's evidence begins.
            var first = k
            while (first < end && !windows.evident(first)) first++
            if (chord >= 0 && chord != last && first < end && confident(windows, first, end, chord)) {
                val root = chord / QUALITIES
                val quality = chord % QUALITIES
                val bass = windows.bass[first]
                val start = windows.firstTone(first, MASK[chord])
                out.add(start, root, quality, if (bass < 0 || bass == root) -1 else bass, sharpsAt(keys, start, guessed))
                last = chord
            }
            k = end
        }
        return out.build()
    }

    /** The key in force at [micros] as sharps (negative: flats); [guessed] when the file has none. */
    private fun sharpsAt(keys: List<KeySignature>, micros: Long, guessed: Int): Int {
        if (keys.isEmpty()) return guessed
        var sharps = 0
        for (key in keys) if (key.atMicros <= micros) sharps = key.sharps else break
        return sharps.coerceIn(-7, 7)
    }

    /**
     * The best chord for each window given its neighbours: a Viterbi over the windows with their
     * chord scores as the evidence (none in a window with fewer than two pitch classes) and [CHANGE]
     * for every change. -1 for a window that is best left unnamed.
     */
    private fun decode(windows: Windows): IntArray {
        val count = windows.count
        val none = CHORDS
        val states = CHORDS + 1
        val words = (states + 63) / 64
        var score = FloatArray(states)
        var next = FloatArray(states)
        val emission = FloatArray(CHORDS)
        val changed = LongArray(count * words)
        val bestBefore = IntArray(count)
        for (k in 0 until count) {
            var best = 0
            for (s in 1..none) if (score[s] > score[best]) best = s
            bestBefore[k] = best
            val change = score[best] - CHANGE
            val evidence = windows.score(k, emission)
            for (s in 0..none) {
                val e = if (s == none || !evidence) 0f else emission[s]
                val stay = score[s]
                if (stay >= change) {
                    next[s] = stay + e
                } else {
                    next[s] = change + e
                    changed[k * words + s / 64] = changed[k * words + s / 64] or (1L shl (s % 64))
                }
            }
            val t = score
            score = next
            next = t
        }
        var s = 0
        for (c in 1..none) if (score[c] > score[s]) s = c
        val path = IntArray(count)
        for (k in count - 1 downTo 0) {
            path[k] = if (s == none) -1 else s
            if ((changed[k * words + s / 64] ushr (s % 64)) and 1L == 1L) s = bestBefore[k]
        }
        return path
    }

    /** The stretch of windows [from] until [until] names [chord] with confidence: it beats every chord of other notes by [MARGIN]. */
    private fun confident(windows: Windows, from: Int, until: Int, chord: Int): Boolean {
        val sum = FloatArray(CHORDS)
        val emission = FloatArray(CHORDS)
        var any = false
        for (k in from until until) {
            if (!windows.score(k, emission)) continue
            any = true
            for (c in 0 until CHORDS) sum[c] += emission[c]
        }
        if (!any) return false
        val notes = MASK[chord]
        var other = Float.NEGATIVE_INFINITY
        for (c in 0 until CHORDS) if (MASK[c] != notes && sum[c] > other) other = sum[c]
        return sum[chord] - other >= MARGIN
    }

    /** The piece's key from its notes (duration-weighted pitch classes against Krumhansl and Kessler's profiles), as sharps. */
    internal fun guessKey(notes: NoteList): Int {
        val weight = DoubleArray(12)
        for (i in 0 until notes.size) {
            if (notes.channel(i) == DRUMS) continue
            weight[Math.floorMod(notes.note(i), 12)] += min(notes.endMicros[i] - notes.startMicros[i], 4_000_000L).toDouble()
        }
        var best = Double.NEGATIVE_INFINITY
        var bestMajorTonic = 0
        for (tonic in 0 until 12) {
            for (minor in booleanArrayOf(false, true)) {
                val profile = if (minor) MINOR_PROFILE else MAJOR_PROFILE
                val r = correlation(weight, profile, tonic)
                if (r > best) {
                    best = r
                    bestMajorTonic = if (minor) (tonic + 3) % 12 else tonic
                }
            }
        }
        val sharps = Math.floorMod(bestMajorTonic * 7, 12)
        return if (sharps > 6) sharps - 12 else sharps
    }

    private fun correlation(weight: DoubleArray, profile: DoubleArray, tonic: Int): Double {
        var mw = 0.0
        var mp = 0.0
        for (k in 0 until 12) {
            mw += weight[k]
            mp += profile[k]
        }
        mw /= 12
        mp /= 12
        var num = 0.0
        var dw = 0.0
        var dp = 0.0
        for (k in 0 until 12) {
            val a = weight[(k + tonic) % 12] - mw
            val b = profile[k] - mp
            num += a * b
            dw += a * a
            dp += b * b
        }
        return if (dw == 0.0 || dp == 0.0) 0.0 else num / sqrt(dw * dp)
    }

    private val MAJOR_PROFILE = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR_PROFILE = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    /** MIDI channel 10, the drums, as the parser numbers channels (0-based). */
    private const val DRUMS = 9

    /**
     * The beat windows over the bars, and each window's weights, keys on each pitch class, and bass.
     * A window's evidence is its [CHORDS] chord scores ([score]), when two pitch classes or more sound
     * in it.
     */
    private class Windows(private val notes: NoteList, tempo: TempoMap, bars: LongArray, signatures: List<TimeSignature>) {
        val start: LongArray
        val end: LongArray
        val count: Int
        val bass: IntArray
        private lateinit var weight: FloatArray
        private lateinit var keyCount: ByteArray
        private lateinit var present: IntArray
        private lateinit var harmony: BooleanArray

        init {
            val times = signatures.filter { it.valid }.sortedBy { it.tick }.ifEmpty { listOf(TimeSignature.Common) }
            val ppq = tempo.ppq
            val starts = ArrayList<Long>()
            val ends = ArrayList<Long>()
            val barList = if (bars.isEmpty()) longArrayOf(0L) else bars
            val last = if (notes.size == 0) 0L else notes.endMicros.max()
            var signature = 0
            for (b in barList.indices) {
                if (starts.size >= MAX_WINDOWS) break
                val from = tempo.microsToTicks(barList[b])
                while (signature + 1 < times.size && times[signature + 1].tick <= from) signature++
                val time = times[signature]
                val barTicks = max(1L, time.ticksIn(1, ppq))
                val to = if (b + 1 < barList.size) tempo.microsToTicks(barList[b + 1]) else from + barTicks
                val length = when {
                    Beams.compound(time) -> 3L * ppq / 2
                    time.denominator == 2 -> barTicks / 2
                    time.numerator == 3 && time.denominator == 8 -> barTicks
                    else -> 4L * ppq / time.denominator
                }.coerceAtLeast(ppq / 2L).coerceAtLeast(1L).coerceAtLeast((to - from) / MAX_PER_BAR)
                var at = from
                while (at < to && starts.size < MAX_WINDOWS) {
                    val stop = min(at + length, to)
                    val a = tempo.tickToMicros(at)
                    val z = tempo.tickToMicros(stop)
                    if (z > a) {
                        starts += a
                        ends += z
                    }
                    at = stop
                }
                if (b + 1 == barList.size && ends.isNotEmpty() && ends.last() < last && starts.size < MAX_WINDOWS) {
                    // The piece rings on past its last bar line's bar: one more window to its end.
                    starts += ends.last()
                    ends += last
                }
            }
            count = starts.size
            start = LongArray(count) { starts[it] }
            end = LongArray(count) { ends[it] }
            bass = IntArray(count) { -1 }
        }

        /** Each window's weights by pitch class, keys on each, which are present, and its bass. */
        fun weigh() {
            weight = FloatArray(count * 12)
            keyCount = ByteArray(count * 12)
            present = IntArray(count)
            harmony = BooleanArray(count)
            val w = FloatArray(12)
            val keys = IntArray(12)
            var seenLow = 0L
            var seenHigh = 0L
            val active = IntArray(ACTIVE_LIMIT)
            var activeSize = 0
            var next = 0
            val n = notes.size
            for (k in 0 until count) {
                val ws = start[k]
                val we = end[k]
                val length = (we - ws).toDouble()
                // Notes that may sound in the window: those started before its end and not ended by its start.
                var kept = 0
                for (j in 0 until activeSize) if (notes.endMicros[active[j]] > ws) active[kept++] = active[j]
                activeSize = kept
                while (next < n && notes.startMicros[next] < we) {
                    if (notes.endMicros[next] > ws && notes.channel(next) != DRUMS && activeSize < ACTIVE_LIMIT) active[activeSize++] = next
                    next++
                }
                w.fill(0f)
                keys.fill(0)
                seenLow = 0L
                seenHigh = 0L
                var low = Int.MAX_VALUE
                var voices = 0.0
                for (j in 0 until activeSize) {
                    val i = active[j]
                    val s = notes.startMicros[i]
                    val e = notes.endMicros[i]
                    val onset = s >= ws
                    val overlap = (min(e, we) - max(s, ws)) / length
                    if (!onset && overlap < TAIL) continue
                    voices += overlap
                    val key = notes.note(i).coerceIn(0, 127)
                    val pc = key % 12
                    w[pc] += (overlap * if (onset) 2.0 else 1.0).toFloat()
                    // Each key once: an Alberti bass's repeated G is not a doubled G.
                    val bit = 1L shl (key and 63)
                    if (key < 64) {
                        if (seenLow and bit == 0L) keys[pc]++
                        seenLow = seenLow or bit
                    } else {
                        if (seenHigh and bit == 0L) keys[pc]++
                        seenHigh = seenHigh or bit
                    }
                    // The bass: struck in the window's first half, or held from before past the tail.
                    val bassing = if (onset) s < ws + (we - ws) / 2 else e >= ws + (TAIL * (we - ws)).toLong()
                    if (bassing && key < low) low = key
                }
                bass[k] = if (low == Int.MAX_VALUE) -1 else low % 12
                var mask = 0
                for (pc in 0 until 12) {
                    if (w[pc] > 0f) mask = mask or (1 shl pc)
                    weight[k * 12 + pc] = w[pc]
                    keyCount[k * 12 + pc] = keys[pc].coerceAtMost(3).toByte()
                }
                present[k] = mask
                harmony[k] = voices > MIN_VOICES
            }
        }

        /** Two pitch classes or more sound in window [k], more than one line at once: it is evidence for a chord. */
        fun evident(k: Int): Boolean = harmony[k] && Integer.bitCount(present[k]) >= 2

        /**
         * Window [k]'s chord scores into [out] (by root * 12 + quality), from the triads' sums; false,
         * with nothing written, when fewer than two pitch classes sound in it.
         */
        fun score(k: Int, out: FloatArray): Boolean {
            val mask = present[k]
            if (!evident(k)) return false
            val at = k * 12
            var total = 0f
            for (pc in 0 until 12) total += weight[at + pc]
            val absent = mask.inv() and 0xFFF
            val bass = bass[k]
            for (r in 0 until 12) {
                val w0 = weight[at + r]
                val w2 = weight[at + (r + 2) % 12]
                val w3 = weight[at + (r + 3) % 12]
                val w4 = weight[at + (r + 4) % 12]
                val w5 = weight[at + (r + 5) % 12]
                val w6 = weight[at + (r + 6) % 12]
                val w7 = weight[at + (r + 7) % 12]
                val w8 = weight[at + (r + 8) % 12]
                val w9 = weight[at + (r + 9) % 12]
                val w10 = weight[at + (r + 10) % 12]
                val w11 = weight[at + (r + 11) % 12]
                val bonus = DOUBLED * min(2, max(0, keyCount[at + r] - 1)) + if (bass == r) ON_BASS else 0f
                val base = r * QUALITIES
                for (q in 0 until QUALITIES) {
                    val inChord = when (q) {
                        MAJOR -> w0 + w4 + w7
                        MINOR -> w0 + w3 + w7
                        DIM -> w0 + w3 + w6
                        AUG -> w0 + w4 + w8
                        SUS2 -> w0 + w2 + w7
                        SUS4 -> w0 + w5 + w7
                        SIX -> w0 + w4 + w7 + w9
                        SEVEN -> w0 + w4 + w7 + w10
                        MAJ7 -> w0 + w4 + w7 + w11
                        M7 -> w0 + w3 + w7 + w10
                        ADD9 -> w0 + w2 + w4 + w7
                        else -> w0 + w2 + w4 + w7 + w11
                    }
                    val missing = Integer.bitCount(MASK[base + q] and absent)
                    val extra = if (q >= SIX) (if (q == MAJ9) 2 else 1) else 0
                    out[base + q] = inChord - OUTSIDE * (total - inChord) - MISSING * missing + bonus - EXTENSION * extra
                }
            }
            return true
        }

        /** Where a chord of tones [mask] begins in window [k]: its first tone struck in it, else the window's start. */
        fun firstTone(k: Int, mask: Int): Long {
            val ws = start[k]
            val we = end[k]
            var i = notes.firstStartingAtOrAfter(ws)
            while (i < notes.size && notes.startMicros[i] < we) {
                if (notes.channel(i) != DRUMS && (mask shr (Math.floorMod(notes.note(i), 12))) and 1 == 1) return notes.startMicros[i]
                i++
            }
            return ws
        }

        private companion object {
            /** Notes weighed in one window at most (a cluster of a hundred is noise, not harmony). */
            const val ACTIVE_LIMIT = 512

            /** A bar is cut into this many windows at most, whatever its metre says. */
            const val MAX_PER_BAR = 16L
        }
    }

    /** Chord labels as they are found, in time order. */
    private class Labels {
        private val start = ArrayList<Long>()
        private val bytes = ArrayList<Int>()

        fun add(at: Long, root: Int, quality: Int, bass: Int, sharps: Int) {
            start += at
            bytes += (root and 0xFF) or ((quality and 0xFF) shl 8) or ((bass and 0xFF) shl 16) or ((sharps and 0xFF) shl 24)
        }

        fun build(): ChordTrack {
            val n = start.size
            return ChordTrack(
                LongArray(n) { start[it] },
                ByteArray(n) { bytes[it].toByte() },
                ByteArray(n) { (bytes[it] shr 8).toByte() },
                ByteArray(n) { (bytes[it] shr 16).toByte() },
                ByteArray(n) { (bytes[it] shr 24).toByte() },
            )
        }
    }
}

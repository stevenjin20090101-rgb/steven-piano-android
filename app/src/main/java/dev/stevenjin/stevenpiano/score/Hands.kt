// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.score

import dev.stevenjin.stevenpiano.midi.NoteList
import dev.stevenjin.stevenpiano.midi.TempoMap
import dev.stevenjin.stevenpiano.midi.TimeSignature
import kotlin.math.abs

/**
 * Which hand plays each note (DESIGN.md › v1.3 › The waterfall format): [RIGHT] or [LEFT], read in
 * this order of rules.
 *
 * 1. **Names.** A track named for a hand decides for its notes: "right", "rh", "r.h", "treble",
 *    "upper" against "left", "lh", "l.h", "bass", "lower", as whole words in any case ("Piano right",
 *    "Piano left2", "upper:", "treble:comes", "RightHand"; not "Bassoon" or "Copyright"). With two
 *    tracks carrying notes and only one of them named, the other is the other hand; notes of any
 *    other unnamed track are split by pitch (rule 3). Names that give every note to one hand alone
 *    decide nothing.
 * 2. **Two tracks.** Otherwise, a file with exactly two tracks carrying notes (piano-midi.de's,
 *    Mutopia's "up"/"down") gives the right hand to the track with the higher median pitch.
 * 3. **Pitch split.** Otherwise each note is compared with the split point of the notes sounding
 *    within [WINDOW_MICROS] of its start: the pitch that best separates them into a lower and a
 *    higher group (Otsu's threshold: the two groups' means as far apart as their sizes allow; with
 *    the 324 piano-midi.de files that name both hands merged into one track, it gives 90.5 % of the
 *    notes the hand their track names, where the window's median gives 86.3 %). Notes all within an
 *    octave are one hand, by middle C (ties right from middle C up). A melodic run of short notes
 *    within one beat group (a beamed figure: single notes in turn, each within an octave of the last,
 *    no rest between) then takes the hand most of its notes have, so a figure is not split between
 *    the staves.
 *
 * Pure, and linear in the notes but for a heap: it runs once per piece off the main thread, and calls
 * its caller's `checkpoint` every [CHECK_EVERY] notes, which throws to stop it (a newer piece
 * replacing this one; the v1.3 delta audit, L1).
 */
object Hands {
    const val RIGHT: Byte = 0
    const val LEFT: Byte = 1

    /** A track name that says neither hand. */
    const val UNKNOWN = -1

    /** The pitch split looks this far either side of a note's start. */
    const val WINDOW_MICROS = 1_000_000L

    /** Notes of a window spanning this many semitones or fewer are one hand (a melody alone, a chord). */
    const val ONE_HAND_SPAN = 12

    /** Notes starting this close together are struck together (a chord), for the runs. */
    const val TOGETHER_MICROS = 30_000L

    /** Middle C: the fixed split for one hand alone, and the tie-break. */
    private const val MIDDLE_C = 60

    /** Ticks per quarter when no tempo map is given (a constant 120 BPM). */
    private const val DEFAULT_PPQ = 480

    /** The checkpoint is called this often, in notes (a power of two). */
    const val CHECK_EVERY = 4_096

    private val RIGHT_WORDS = setOf("right", "rh", "r.h", "treble", "upper", "righthand")
    private val LEFT_WORDS = setOf("left", "lh", "l.h", "bass", "lower", "lefthand")

    /**
     * What a track's [name] says: [RIGHT], [LEFT] or [UNKNOWN]. Words are split at anything but
     * letters and dots and where lower case turns to upper ("RightHand"); a word counts when it is
     * one of the hand words, or one of them with a single letter after it (LilyPond's "uppera",
     * "lowerb"). A name saying both hands says neither.
     */
    fun classify(name: String): Int {
        var right = false
        var left = false
        val spaced = StringBuilder(name.length + 4)
        for (k in name.indices) {
            val c = name[k]
            if (k > 0 && c.isUpperCase() && name[k - 1].isLowerCase()) spaced.append(' ')
            spaced.append(if (c.isLetter() || c == '.') c.lowercaseChar() else ' ')
        }
        for (raw in spaced.split(' ')) {
            val word = raw.trim('.')
            if (word.isEmpty()) continue
            if (says(word, RIGHT_WORDS)) right = true
            if (says(word, LEFT_WORDS)) left = true
        }
        return when {
            right && !left -> RIGHT.toInt()
            left && !right -> LEFT.toInt()
            else -> UNKNOWN
        }
    }

    /** [word] is one of [words], or one of the longer ones (four letters or more) with one more letter ("uppera"). */
    private fun says(word: String, words: Set<String>): Boolean {
        if (word in words) return true
        val stem = word.dropLast(1)
        return stem.length >= 4 && stem in words
    }

    /**
     * The hand of each of [notes] ([RIGHT] or [LEFT]), from the tracks' [trackNames] (one per track,
     * as `MidiPiece.trackNames`), else the tracks, else the pitches. [tempo] and [timeSignatures]
     * give the beat groups the pitch split's runs keep within. [checkpoint] is called every
     * [CHECK_EVERY] notes; it throws to stop the work.
     */
    fun assign(
        notes: NoteList,
        trackNames: List<String>,
        tempo: TempoMap = TempoMap.constant(DEFAULT_PPQ),
        timeSignatures: List<TimeSignature> = listOf(TimeSignature.Common),
        checkpoint: () -> Unit = {},
    ): ByteArray {
        val n = notes.size
        val out = ByteArray(n)
        if (n == 0) return out
        checkpoint()
        var trackCount = trackNames.size
        for (i in 0 until n) trackCount = maxOf(trackCount, notes.track(i) + 1)
        val perTrack = IntArray(trackCount)
        for (i in 0 until n) perTrack[notes.track(i)]++
        val carrying = (0 until trackCount).filter { perTrack[it] > 0 }
        val hand = IntArray(trackCount) { if (perTrack[it] > 0) classify(trackNames.getOrElse(it) { "" }) else UNKNOWN }

        // 1. Names.
        val named = carrying.filter { hand[it] != UNKNOWN }
        if (named.isNotEmpty()) {
            if (carrying.size == 2 && named.size == 1) {
                val other = carrying.first { it != named[0] }
                hand[other] = if (hand[named[0]] == RIGHT.toInt()) LEFT.toInt() else RIGHT.toInt()
            }
            val hands = carrying.map { hand[it] }.filter { it != UNKNOWN }.toSet()
            val unnamed = carrying.any { hand[it] == UNKNOWN }
            if (hands.size == 2 || unnamed) {
                val split = if (unnamed) pitchSplit(notes, tempo, timeSignatures, checkpoint) { hand[notes.track(it)] == UNKNOWN } else null
                for (i in 0 until n) {
                    val h = hand[notes.track(i)]
                    out[i] = if (h != UNKNOWN) h.toByte() else split!![i]
                }
                return out
            }
        }

        // 2. Two tracks: the higher median is the right hand.
        if (carrying.size == 2) {
            val a = carrying[0]
            val b = carrying[1]
            val right = higherTrack(notes, a, b)
            for (i in 0 until n) out[i] = if (notes.track(i) == right) RIGHT else LEFT
            return out
        }

        // 3. The pitch split.
        return pitchSplit(notes, tempo, timeSignatures, checkpoint) { true }
    }

    /** Of tracks [a] and [b], the one with the higher median pitch (then the higher mean; then [a]). */
    private fun higherTrack(notes: NoteList, a: Int, b: Int): Int {
        val histA = IntArray(128)
        val histB = IntArray(128)
        var sumA = 0L
        var sumB = 0L
        for (i in 0 until notes.size) {
            val p = notes.note(i).coerceIn(0, 127)
            when (notes.track(i)) {
                a -> { histA[p]++; sumA += p }
                b -> { histB[p]++; sumB += p }
            }
        }
        val countA = histA.sum()
        val countB = histB.sum()
        val medianA = median(histA, countA)
        val medianB = median(histB, countB)
        return when {
            medianA != medianB -> if (medianA > medianB) a else b
            sumA * countB != sumB * countA -> if (sumA * countB > sumB * countA) a else b
            else -> a
        }
    }

    /** The lower median of [count] pitches in [hist]. */
    private fun median(hist: IntArray, count: Int): Int {
        var seen = 0
        val target = (count - 1) / 2
        for (p in hist.indices) {
            seen += hist[p]
            if (seen > target) return p
        }
        return 0
    }

    /**
     * Rule 3 for the notes [included] says: each against the split point of the notes (all of them,
     * so the other hand's named notes still count) sounding within [WINDOW_MICROS] of its start,
     * then smoothed over melodic runs within a beat group. Notes not included get [RIGHT].
     */
    internal fun pitchSplit(
        notes: NoteList,
        tempo: TempoMap,
        timeSignatures: List<TimeSignature>,
        checkpoint: () -> Unit = {},
        included: (Int) -> Boolean,
    ): ByteArray {
        val n = notes.size
        val out = ByteArray(n)
        val starts = notes.startMicros
        val ends = notes.endMicros
        val window = Window()
        val heap = EndHeap(ends)
        var next = 0
        var lastStart = Long.MIN_VALUE
        for (i in 0 until n) {
            if (i and (CHECK_EVERY - 1) == 0) checkpoint()
            val s = starts[i]
            if (s != lastStart) {
                while (next < n && starts[next] <= s + WINDOW_MICROS) {
                    window.add(notes.note(next))
                    heap.push(next)
                    next++
                }
                while (heap.size > 0 && ends[heap.peek()] < s - WINDOW_MICROS) window.remove(notes.note(heap.pop()))
                window.split()
                lastStart = s
            }
            if (!included(i)) continue
            val p = notes.note(i)
            val threshold = window.threshold
            out[i] = when {
                window.span <= ONE_HAND_SPAN -> if (p >= MIDDLE_C) RIGHT else LEFT
                p > threshold -> RIGHT
                p < threshold -> LEFT
                else -> if (p >= MIDDLE_C) RIGHT else LEFT
            }
        }
        smoothRuns(notes, out, tempo, timeSignatures, checkpoint, included)
        return out
    }

    /**
     * Melodic runs keep one hand: consecutive onsets each holding exactly one short note (shorter
     * than its beat group; a longer note struck with it doesn't break the run), in one beat group,
     * each starting no more than an eighth of the beat after the last one ends, within an octave of
     * it. A run of two or more takes the hand most of its notes have (the first note's on a tie).
     * Only [included] notes take part.
     */
    internal fun smoothRuns(
        notes: NoteList,
        hands: ByteArray,
        tempo: TempoMap,
        timeSignatures: List<TimeSignature>,
        checkpoint: () -> Unit = {},
        included: (Int) -> Boolean,
    ) {
        val n = notes.size
        val grid = BeatGrid(tempo, timeSignatures)
        val run = IntArray(n.coerceAtMost(MAX_RUN))
        var runSize = 0
        var runGroup = Long.MIN_VALUE
        fun flush() {
            if (runSize >= 2) {
                var right = 0
                for (k in 0 until runSize) if (hands[run[k]] == RIGHT) right++
                val left = runSize - right
                val h = when {
                    right > left -> RIGHT
                    left > right -> LEFT
                    else -> hands[run[0]]
                }
                for (k in 0 until runSize) hands[run[k]] = h
            }
            runSize = 0
        }
        var i = 0
        var checked = 0
        while (i < n) {
            if (i - checked >= CHECK_EVERY) {
                checkpoint()
                checked = i
            }
            // One onset: the notes starting within TOGETHER_MICROS of this one.
            var end = i + 1
            while (end < n && notes.startMicros[end] - notes.startMicros[i] <= TOGETHER_MICROS) end++
            val group = grid.group(notes.startMicros[i])
            val beat = grid.beatMicros(notes.startMicros[i])
            var short = -1
            var shorts = 0
            for (k in i until end) {
                if (!included(k)) continue
                if (notes.endMicros[k] - notes.startMicros[k] < beat) {
                    short = k
                    shorts++
                }
            }
            if (shorts != 1) {
                flush()
            } else {
                if (runSize > 0) {
                    val last = run[runSize - 1]
                    val joined = group == runGroup &&
                        notes.startMicros[short] - notes.endMicros[last] <= beat / 8 &&
                        abs(notes.note(short) - notes.note(last)) <= 12 &&
                        runSize < run.size
                    if (!joined) flush()
                }
                if (runSize == 0) runGroup = group
                run[runSize++] = short
            }
            i = end
        }
        flush()
    }

    /** A run is cut after this many notes (a single line for minutes on end is not a beamed figure). */
    private const val MAX_RUN = 64

    /**
     * Beat groups as the score beams them ([Beams.beatSixteenths]: a quarter, a dotted quarter in 6/8,
     * 9/8, 12/8, a half in 2/2), counted from where each time signature takes over. Notes are asked
     * for in time order, so the signature in force is found by walking forward.
     */
    private class BeatGrid(private val tempo: TempoMap, signatures: List<TimeSignature>) {
        private val times = signatures.filter { it.valid }.sortedBy { it.tick }.ifEmpty { listOf(TimeSignature.Common) }
        private var at = 0

        private fun signatureAt(tick: Long): Int {
            if (at > 0 && times[at].tick > tick) at = 0
            while (at + 1 < times.size && times[at + 1].tick <= tick) at++
            return at
        }

        private fun beatTicks(s: Int): Long = maxOf(1L, Beams.beatSixteenths(times[s]).toLong() * tempo.ppq / 4)

        /** The beat group holding [micros]: unique across signatures. */
        fun group(micros: Long): Long {
            val tick = tempo.microsToTicks(micros)
            val s = signatureAt(tick)
            val from = if (tick < times[s].tick) 0L else times[s].tick
            return (s.toLong() shl 40) + Math.floorDiv(tick - from, beatTicks(s))
        }

        /** How long the beat group at [micros] lasts, in microseconds. */
        fun beatMicros(micros: Long): Long {
            val tick = tempo.microsToTicks(micros)
            val length = beatTicks(signatureAt(tick))
            return maxOf(1L, tempo.tickToMicros(tick + length) - tempo.tickToMicros(tick))
        }
    }

    /**
     * The pitches sounding in the window: a histogram, its count and sum, and which pitches are
     * present as two 64-bit words, so the split visits only the pitches there are (a window holds
     * a few dozen at most). [split] finds Otsu's threshold: the cut between a lower and a higher
     * group that maximises `n0 · n1 · (mean0 − mean1)²`, as a half pitch ([threshold]); [span] is
     * the highest pitch present less the lowest.
     */
    private class Window {
        private val hist = IntArray(128)
        private var present0 = 0L   // pitches 0..63
        private var present1 = 0L   // pitches 64..127
        private var count = 0L
        private var sum = 0L

        var threshold = MIDDLE_C - 0.5
            private set
        var span = 0
            private set

        fun add(pitch: Int) {
            val p = pitch.coerceIn(0, 127)
            if (hist[p]++ == 0) if (p < 64) present0 = present0 or (1L shl p) else present1 = present1 or (1L shl (p - 64))
            count++
            sum += p
        }

        fun remove(pitch: Int) {
            val p = pitch.coerceIn(0, 127)
            if (--hist[p] == 0) if (p < 64) present0 = present0 and (1L shl p).inv() else present1 = present1 and (1L shl (p - 64)).inv()
            count--
            sum -= p
        }

        fun split() {
            if (count == 0L) {
                span = 0
                threshold = MIDDLE_C - 0.5
                return
            }
            val lo = if (present0 != 0L) java.lang.Long.numberOfTrailingZeros(present0) else 64 + java.lang.Long.numberOfTrailingZeros(present1)
            val hi = if (present1 != 0L) 127 - java.lang.Long.numberOfLeadingZeros(present1) else 63 - java.lang.Long.numberOfLeadingZeros(present0)
            span = hi - lo
            if (span == 0) {
                threshold = lo + 0.5
                return
            }
            var n0 = 0L
            var s0 = 0L
            var best = -1.0
            var cut = lo
            // Every present pitch below the highest, in order: each is a cut with it and all below in the lower group.
            for (half in 0..1) {
                var bits = if (half == 0) present0 else present1
                while (bits != 0L) {
                    val p = java.lang.Long.numberOfTrailingZeros(bits) + 64 * half
                    bits = bits and (bits - 1)
                    if (p >= hi) break
                    val c = hist[p]
                    n0 += c
                    s0 += c.toLong() * p
                    val n1 = count - n0
                    val d = s0.toDouble() / n0 - (sum - s0).toDouble() / n1
                    val v = n0.toDouble() * n1 * d * d
                    if (v > best) {
                        best = v
                        cut = p
                    }
                }
            }
            threshold = cut + 0.5
        }
    }

    /** A binary min-heap of note indices ordered by their [ends]: the notes still in the window. */
    private class EndHeap(private val ends: LongArray) {
        private var items = IntArray(64)
        var size = 0
            private set

        fun push(i: Int) {
            if (size == items.size) items = items.copyOf(size * 2)
            var k = size++
            items[k] = i
            while (k > 0) {
                val parent = (k - 1) ushr 1
                if (ends[items[parent]] <= ends[items[k]]) break
                val t = items[parent]
                items[parent] = items[k]
                items[k] = t
                k = parent
            }
        }

        fun peek(): Int = items[0]

        fun pop(): Int {
            val top = items[0]
            items[0] = items[--size]
            var k = 0
            while (true) {
                val l = 2 * k + 1
                if (l >= size) break
                val r = l + 1
                val c = if (r < size && ends[items[r]] < ends[items[l]]) r else l
                if (ends[items[k]] <= ends[items[c]]) break
                val t = items[c]
                items[c] = items[k]
                items[k] = t
                k = c
            }
            return top
        }
    }
}

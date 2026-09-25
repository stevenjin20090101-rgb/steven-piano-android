// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.score

import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.midi.NoteList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Suggested fingering (DESIGN.md › v1.3 › The waterfall format): a finger, 1 (thumb) to 5 (little
 * finger), for each note, chosen per hand by dynamic programming (Viterbi) over the hand's events
 * (a single note, or the notes struck within [CHORD_MICROS] as one chord), minimising a cost in the
 * manner of Parncutt et al. (1997), "An ergonomic model of keyboard fingering":
 *
 * - **Stretch** between two fingers, from each pair's spans in semitones (the higher finger on the
 *   higher key; the left hand mirrored): free within the relaxed span, 1 a semitone out to the
 *   comfortable span for pairs with the thumb (2 without), and 2 more a semitone beyond it (1–2
 *   relaxed to 5, comfortable to 7; 1–3: 7 and 10; 1–4: 9 and 12; 1–5: 10 and 14; 2–3, 3–4, 4–5:
 *   2 and 3); a span below the relaxed one costs 1 a semitone (2 with the thumb).
 * - **Crossings:** only the thumb passing under 2, 3 or 4 (or those crossing over it) is allowed,
 *   for [CROSS_OK]; any other crossing costs [CROSS_BAD].
 * - The thumb on a black key costs [THUMB_ON_BLACK]; the weak fourth finger [WEAK_FOURTH].
 * - The same finger on a different key costs [SAME_FINGER]; a repeated key keeps its finger for
 *   nothing. A finger still holding a note of the event before (for [HELD_MICROS] past the next
 *   onset) costs [HELD] to reuse.
 * - A move of the hand costs [POSITION_STEP] for each white key the thumb's implied place shifts
 *   (each finger over its own white key: a finger on a key puts the thumb that many keys away), and
 *   [POSITION_CHANGE] more for a shift of three white keys or more, and again past an octave.
 * - Chords take their fingers in pitch order, with the stretch of every pair; five notes or more go
 *   thumb to little finger by their spread (past five, the notes between share no finger).
 *
 * The result is a suggestion computed from the notes, not an editor's fingering. Pure; a piece's
 * first [MAX_NOTES] notes are fingered, the rest are left without. The caller's `checkpoint` is
 * called every [CHECK_EVERY] notes and events; it throws to stop the work (a newer piece or
 * transpose replacing this one; the v1.3 delta audit, L1).
 */
object Fingering {
    /** No finger suggested. */
    const val NONE: Byte = 0

    /** Notes of one hand starting this close together are one chord. */
    const val CHORD_MICROS = 30_000L

    /** A note still held this long after the next event starts keeps its finger busy. */
    const val HELD_MICROS = 100_000L

    /** Notes fingered in a piece at most (about 25 minutes of dense music); the rest get [NONE]. */
    const val MAX_NOTES = 250_000

    /** The checkpoint is called this often, in notes or events (a power of two). */
    const val CHECK_EVERY = 4_096

    const val CROSS_OK = 3f
    const val CROSS_BAD = 12f
    const val THUMB_ON_BLACK = 1f
    const val WEAK_FOURTH = 0.5f
    const val SAME_FINGER = 4f
    const val HELD = 6f
    const val POSITION_CHANGE = 1f
    const val POSITION_STEP = 0.25f

    /** Chords of up to this many distinct keys get every finger order tried; more are spread. */
    private const val TRIED = 4

    private val BLACK_KEY = booleanArrayOf(false, true, false, true, false, false, true, false, true, false, true, false)

    /**
     * Spans between fingers a < b (index a * 6 + b), in semitones from a's key up to b's: the
     * smallest comfortable, the relaxed range, and the largest comfortable. A negative smallest span
     * lets the thumb pass under.
     */
    private val MIN_COMF = IntArray(36)
    private val MIN_REL = IntArray(36)
    private val MAX_REL = IntArray(36)
    private val MAX_COMF = IntArray(36)

    private fun span(a: Int, b: Int, minComf: Int, minRel: Int, maxRel: Int, maxComf: Int) {
        val k = a * 6 + b
        MIN_COMF[k] = minComf
        MIN_REL[k] = minRel
        MAX_REL[k] = maxRel
        MAX_COMF[k] = maxComf
    }

    init {
        span(1, 2, -3, 1, 5, 7)
        span(1, 3, -2, 3, 7, 10)
        span(1, 4, -1, 5, 9, 12)
        span(1, 5, 1, 7, 10, 14)
        span(2, 3, 1, 1, 2, 3)
        span(2, 4, 1, 3, 4, 5)
        span(2, 5, 2, 5, 6, 8)
        span(3, 4, 1, 1, 2, 3)
        span(3, 5, 1, 3, 4, 5)
        span(4, 5, 1, 1, 2, 3)
    }

    /** From the thumb to each finger in a five-finger position, in half white keys: a finger to a white key. */
    private val OFFSET = intArrayOf(0, 0, 2, 4, 6, 8)

    /** Each MIDI key's place along the keyboard in half white keys: white keys even, black keys between them. */
    private val PLACE = IntArray(128).also { place ->
        var whites = 0
        for (key in 0 until 128) {
            val black = BLACK_KEY[key % 12]
            place[key] = if (black) 2 * whites - 1 else 2 * whites
            if (!black) whites++
        }
    }

    /**
     * The stretch cost of fingers [a] < [b] a span of [d] semitones apart (b's key less a's, the left
     * hand mirrored), with the crossing cost when [d] is negative.
     */
    internal fun stretch(a: Int, b: Int, d: Int): Float {
        val k = a * 6 + b
        val minComf = MIN_COMF[k]
        val minRel = MIN_REL[k]
        val maxRel = MAX_REL[k]
        val maxComf = MAX_COMF[k]
        val thumb = a == 1
        var cost = 0f
        if (d < 0) cost += if (thumb && b <= 4) CROSS_OK else CROSS_BAD
        if (d < minComf) cost += 2f * (minComf - d)
        if (d in 0 until minRel) cost += (if (thumb) 2f else 1f) * (minRel - d)
        if (d > maxRel) cost += (if (thumb) 1f else 2f) * (min(d, maxComf) - maxRel)
        if (d > maxComf) cost += 2f * (d - maxComf)
        return cost
    }

    /**
     * The fingers' part of moving from key [q1] with finger [f1] to key [q2] with finger [f2]
     * (semitones, mirrored for the left hand): the stretch or crossing between them, and the same
     * finger on a new key ([melodic] only: a chord's outer finger moving with its shape is no fault).
     */
    internal fun reach(f1: Int, q1: Int, f2: Int, q2: Int, melodic: Boolean): Float = when {
        f1 == f2 -> if (q1 == q2 || !melodic) 0f else SAME_FINGER
        f1 < f2 -> stretch(f1, f2, q2 - q1)
        else -> stretch(f2, f1, q1 - q2)
    }

    /** The hand's move from finger [f1] at place [x1] to finger [f2] at [x2] (half white keys, mirrored for the left hand). */
    internal fun shift(f1: Int, x1: Int, f2: Int, x2: Int): Float {
        val halves = abs((x2 - OFFSET[f2]) - (x1 - OFFSET[f1]))
        var cost = POSITION_STEP * halves / 2
        if (halves > 4) cost += POSITION_CHANGE
        if (halves > 14) cost += POSITION_CHANGE
        return cost
    }

    // Tables: the costs above looked up by fingers and distance, so a piece of twenty thousand notes
    // takes milliseconds. Distances beyond them fall back to the functions.

    /** Semitones and half white keys the tables cover, either way. */
    private const val SPAN = 48
    private const val PLACES = 64
    private const val WIDE = 2 * SPAN + 1
    private const val WIDE_PLACES = 2 * PLACES + 1

    /** [reach] between single notes (melodic), and between chords' outer keys (not), by finger pair and semitones. */
    private val MELODIC = FloatArray(36 * WIDE)
    private val SHAPE = FloatArray(36 * WIDE)

    /** [stretch] by finger pair (a < b) and semitones. */
    private val STRETCH = FloatArray(36 * WIDE)

    /** [shift] by finger pair and half white keys. */
    private val SHIFT = FloatArray(36 * WIDE_PLACES)

    init {
        for (f1 in 1..5) for (f2 in 1..5) {
            val k = f1 * 6 + f2
            for (d in -SPAN..SPAN) {
                MELODIC[k * WIDE + d + SPAN] = reach(f1, 0, f2, d, melodic = true)
                SHAPE[k * WIDE + d + SPAN] = reach(f1, 0, f2, d, melodic = false)
                if (f1 < f2) STRETCH[k * WIDE + d + SPAN] = stretch(f1, f2, d)
            }
            for (dx in -PLACES..PLACES) SHIFT[k * WIDE_PLACES + dx + PLACES] = shift(f1, 0, f2, dx)
        }
    }

    private fun reachOf(f1: Int, q1: Int, f2: Int, q2: Int, melodic: Boolean): Float {
        val d = q2 - q1
        if (d < -SPAN || d > SPAN) return reach(f1, q1, f2, q2, melodic)
        return (if (melodic) MELODIC else SHAPE)[(f1 * 6 + f2) * WIDE + d + SPAN]
    }

    private fun stretchOf(a: Int, b: Int, d: Int): Float =
        if (d < -SPAN || d > SPAN) stretch(a, b, d) else STRETCH[(a * 6 + b) * WIDE + d + SPAN]

    private fun shiftOf(f1: Int, x1: Int, f2: Int, x2: Int): Float {
        val dx = x2 - x1
        if (dx < -PLACES || dx > PLACES) return shift(f1, x1, f2, x2)
        return SHIFT[(f1 * 6 + f2) * WIDE_PLACES + dx + PLACES]
    }

    /** Every way of putting fingers in rising order on k keys (k = 1..[TRIED]), as rows of fingers. */
    private val ORDERS: Array<Array<IntArray>> = Array(TRIED + 1) { k ->
        if (k == 0) emptyArray() else combinations(k).toTypedArray()
    }

    /** The finger each order puts on its lowest and highest key, by chord size and order. */
    private val LOW_FINGER: Array<IntArray> = Array(TRIED + 1) { k -> IntArray(ORDERS[k].size) { ORDERS[k][it][0] } }
    private val HIGH_FINGER: Array<IntArray> = Array(TRIED + 1) { k -> IntArray(ORDERS[k].size) { ORDERS[k][it][k - 1] } }

    private fun combinations(k: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        fun pick(from: Int, chosen: IntArray, depth: Int) {
            if (depth == k) {
                out += chosen.copyOf()
                return
            }
            for (f in from..5) {
                chosen[depth] = f
                pick(f + 1, chosen, depth + 1)
            }
        }
        pick(1, IntArray(k), 0)
        return out
    }

    /**
     * A finger (1–5, or [NONE]) for each of [notes], per hand as [hands] gives them (`Hands`), on the
     * keys the piano plays them on ([KeyMap] with [transpose] and [fold]; a note it can't play gets
     * none). [checkpoint] is called every [CHECK_EVERY] notes and events; it throws to stop the work.
     */
    fun assign(notes: NoteList, hands: ByteArray, transpose: Int = 0, fold: Boolean = true, checkpoint: () -> Unit = {}): ByteArray {
        require(hands.size == notes.size) { "One hand per note" }
        val out = ByteArray(notes.size)
        val limit = min(notes.size, MAX_NOTES)
        for (hand in byteArrayOf(Hands.RIGHT, Hands.LEFT)) {
            checkpoint()
            var count = 0
            for (i in 0 until limit) if (hands[i] == hand && KeyMap.map(notes.note(i), transpose, fold) != KeyMap.UNPLAYABLE) count++
            if (count == 0) continue
            val index = IntArray(count)
            var c = 0
            for (i in 0 until limit) if (hands[i] == hand && KeyMap.map(notes.note(i), transpose, fold) != KeyMap.UNPLAYABLE) index[c++] = i
            HandPass(notes, index, hand == Hands.LEFT, transpose, fold, out, checkpoint).run()
        }
        return out
    }

    /** One hand's notes ([index], in start order) fingered into [out]. */
    private class HandPass(
        private val notes: NoteList,
        private val index: IntArray,
        private val left: Boolean,
        private val transpose: Int,
        private val fold: Boolean,
        private val out: ByteArray,
        private val checkpoint: () -> Unit,
    ) {
        private val n = index.size

        // Events: notes eventFrom[e] until eventFrom[e + 1] of [index]; their distinct keys keyFrom[e]
        // until keyFrom[e + 1] of the key arrays, in rising order of q (the key, mirrored for the left hand).
        private val eventFrom = IntArray(n + 1)
        private val keyFrom = IntArray(n + 1)
        private val q = IntArray(n)
        private val x = IntArray(n)
        private val black = BooleanArray(n)
        private val heldUntil = LongArray(n)
        private val eventStart = LongArray(n)
        private var events = 0

        private fun keyOf(i: Int): Int = KeyMap.map(notes.note(i), transpose, fold)

        private fun mirror(key: Int): Int = if (left) -key else key

        fun run() {
            group()
            val finger = decode()
            write(finger)
        }

        /** Events and their distinct keys, rising in q. */
        private fun group() {
            var k = 0
            var e = 0
            var a = 0
            while (a < n) {
                if (e and (CHECK_EVERY - 1) == 0) checkpoint()
                val start = notes.startMicros[index[a]]
                var b = a + 1
                while (b < n && notes.startMicros[index[b]] - start <= CHORD_MICROS) b++
                eventFrom[e] = a
                keyFrom[e] = k
                eventStart[e] = start
                for (j in a until b) {
                    val key = keyOf(index[j])
                    val qj = mirror(key)
                    val end = notes.endMicros[index[j]]
                    var at = keyFrom[e]
                    while (at < k && q[at] < qj) at++
                    if (at < k && q[at] == qj) {
                        heldUntil[at] = max(heldUntil[at], end)   // the same key twice: one key
                        continue
                    }
                    for (m in k downTo at + 1) {
                        q[m] = q[m - 1]
                        x[m] = x[m - 1]
                        black[m] = black[m - 1]
                        heldUntil[m] = heldUntil[m - 1]
                    }
                    q[at] = qj
                    x[at] = if (left) -PLACE[key] else PLACE[key]
                    black[at] = BLACK_KEY[key % 12]
                    heldUntil[at] = end
                    k++
                }
                e++
                a = b
            }
            events = e
            eventFrom[e] = n
            keyFrom[e] = k
        }

        private fun size(e: Int): Int = keyFrom[e + 1] - keyFrom[e]

        /** The states of event [e]: its finger orders (one, spread, for five keys or more). */
        private fun states(e: Int): Int = if (size(e) <= TRIED) ORDERS[size(e)].size else 1

        /** The finger state [s] of event [e] puts on its key [k] (0-based, rising). */
        private fun fingerOf(e: Int, s: Int, k: Int): Int {
            val size = size(e)
            if (size <= TRIED) return ORDERS[size][s][k]
            return spread(e, k)
        }

        /**
         * Five keys: thumb to little finger in order. More: the lowest the thumb, the highest the
         * little finger, and each key between the finger its place in the chord's spread gives, or
         * the next free one; past the fourth finger a key gets none.
         */
        private fun spread(e: Int, k: Int): Int {
            val from = keyFrom[e]
            val size = size(e)
            if (size == 5) return k + 1
            if (k == size - 1) return 5
            val low = q[from]
            val high = q[from + size - 1]
            var previous = 0
            var finger = 0
            for (j in 0..k) {
                val raw = if (high == low) 1 else 1 + (4.0 * (q[from + j] - low) / (high - low)).roundToInt()
                finger = max(raw, previous + 1)
                if (finger > 4) return NONE.toInt()
                previous = finger
            }
            return finger
        }

        /** The cost of state [s] on event [e] alone: every pair's stretch, thumbs on black keys, fourth fingers. */
        private fun own(e: Int, s: Int): Float {
            val from = keyFrom[e]
            val size = size(e)
            var cost = 0f
            for (a in 0 until size) {
                val fa = fingerOf(e, s, a)
                if (fa == 0) continue
                if (fa == 1 && black[from + a]) cost += THUMB_ON_BLACK
                if (fa == 4) cost += WEAK_FOURTH
                for (b in a + 1 until size) {
                    val fb = fingerOf(e, s, b)
                    if (fb == 0 || fb <= fa) continue
                    cost += stretchOf(fa, fb, q[from + b] - q[from + a])
                }
            }
            return cost
        }

        /** Moving from state [s1] of event [e1] to state [s2] of the next event. */
        private fun move(e1: Int, s1: Int, s2: Int): Float {
            val e2 = e1 + 1
            val a = keyFrom[e1]
            val b = keyFrom[e2]
            val size1 = size(e1)
            val size2 = size(e2)
            if (size1 == 1 && size2 == 1) {
                val f1 = fingerOf(e1, s1, 0)
                val f2 = fingerOf(e2, s2, 0)
                val held = f1 == f2 && q[a] != q[b] && heldUntil[a] >= eventStart[e2] + HELD_MICROS
                return reachOf(f1, q[a], f2, q[b], melodic = true) + shiftOf(f1, x[a], f2, x[b]) + if (held) HELD else 0f
            }
            // A chord on either side: its outer keys move (thumb side to thumb side, little-finger side to
            // little-finger side), as a shape does; the hand moves as its thumb side does.
            val low1 = a + firstKey(e1, s1)
            val low2 = b + firstKey(e2, s2)
            val high1 = a + lastKey(e1, s1)
            val high2 = b + lastKey(e2, s2)
            val lowFinger1 = firstFinger(e1, s1)
            val lowFinger2 = firstFinger(e2, s2)
            var cost = reachOf(lowFinger1, q[low1], lowFinger2, q[low2], melodic = false) +
                reachOf(lastFinger(e1, s1), q[high1], lastFinger(e2, s2), q[high2], melodic = false) +
                shiftOf(lowFinger1, x[low1], lowFinger2, x[low2])
            // A finger still holding a key of the event before is not free for another key.
            for (j in 0 until size1) {
                if (heldUntil[a + j] < eventStart[e2] + HELD_MICROS) continue
                val f = fingerOf(e1, s1, j)
                if (f == 0) continue
                for (m in 0 until size2) if (fingerOf(e2, s2, m) == f && q[b + m] != q[a + j]) cost += HELD
            }
            return cost
        }

        private fun firstKey(e: Int, s: Int): Int {
            for (k in 0 until size(e)) if (fingerOf(e, s, k) != 0) return k
            return 0
        }

        private fun lastKey(e: Int, s: Int): Int {
            for (k in size(e) - 1 downTo 0) if (fingerOf(e, s, k) != 0) return k
            return size(e) - 1
        }

        private fun firstFinger(e: Int, s: Int): Int = fingerOf(e, s, firstKey(e, s)).coerceAtLeast(1)

        private fun lastFinger(e: Int, s: Int): Int = fingerOf(e, s, lastKey(e, s)).coerceAtLeast(1)

        /** Viterbi over the events: the cheapest state for each, as a state index per event. */
        private fun decode(): IntArray {
            val most = ORDERS.maxOf { it.size }
            var cost = FloatArray(most)
            var next = FloatArray(most)
            val back = ByteArray(events * most)
            for (s in 0 until states(0)) cost[s] = own(0, s)
            for (e in 1 until events) {
                if (e and (CHECK_EVERY - 1) == 0) checkpoint()
                if (singleStep(e, cost, next, back, most) || chordStep(e, cost, next, back, most)) {
                    val t = cost
                    cost = next
                    next = t
                    continue
                }
                val before = states(e - 1)
                for (s in 0 until states(e)) {
                    var best = Float.MAX_VALUE
                    var from = 0
                    for (p in 0 until before) {
                        val c = cost[p] + move(e - 1, p, s)
                        if (c < best) {
                            best = c
                            from = p
                        }
                    }
                    next[s] = best + own(e, s)
                    back[e * most + s] = from.toByte()
                }
                val t = cost
                cost = next
                next = t
            }
            var s = 0
            for (k in 1 until states(events - 1)) if (cost[k] < cost[s]) s = k
            val chosen = IntArray(events)
            for (e in events - 1 downTo 0) {
                chosen[e] = s
                if (e > 0) s = back[e * most + s].toInt()
            }
            return chosen
        }

        /**
         * Single note to single note, the common case, in one tight loop over the tables: [next] and
         * the back pointers for event [e] from [cost] for the note before. False (nothing done) when
         * either is a chord or the step is beyond the tables.
         */
        private fun singleStep(e: Int, cost: FloatArray, next: FloatArray, back: ByteArray, most: Int): Boolean {
            if (size(e - 1) != 1 || size(e) != 1) return false
            val a = keyFrom[e - 1]
            val b = keyFrom[e]
            val d = q[b] - q[a]
            val dx = x[b] - x[a]
            if (d < -SPAN || d > SPAN || dx < -PLACES || dx > PLACES) return false
            val held = q[a] != q[b] && heldUntil[a] >= eventStart[e] + HELD_MICROS
            val thumbOnBlack = if (black[b]) THUMB_ON_BLACK else 0f
            for (f2 in 1..5) {
                var best = Float.MAX_VALUE
                var from = 0
                for (f1 in 1..5) {
                    val k = f1 * 6 + f2
                    var c = cost[f1 - 1] + MELODIC[k * WIDE + d + SPAN] + SHIFT[k * WIDE_PLACES + dx + PLACES]
                    if (held && f1 == f2) c += HELD
                    if (c < best) {
                        best = c
                        from = f1 - 1
                    }
                }
                next[f2 - 1] = best + when (f2) {
                    1 -> thumbOnBlack
                    4 -> WEAK_FOURTH
                    else -> 0f
                }
                back[e * most + f2 - 1] = from.toByte()
            }
            return true
        }

        // The chord before's states, gathered by their outer fingers (the only fingers a move to or from a
        // chord looks at): the cheapest state for each pair of them.
        private val pairLow = IntArray(ORDERS.maxOf { it.size })
        private val pairHigh = IntArray(pairLow.size)
        private val pairCost = FloatArray(pairLow.size)
        private val pairState = IntArray(pairLow.size)
        private val pairOf = IntArray(36) { -1 }

        // With a held key: for each state before, the fingers holding held keys (a bit each) and those
        // keys; for the state after, the fingers it uses and their keys.
        private val heldMask = IntArray(pairLow.size)
        private val heldKey = IntArray(pairLow.size * 6)
        private val usedKey = IntArray(6)

        /**
         * To or from a chord: the thumb-side and little-finger-side keys move with the shape, looked up
         * in the tables (a finger still holding a key of the chord before is checked only when one is).
         * False (nothing done) when a step is beyond the tables.
         */
        private fun chordStep(e: Int, cost: FloatArray, next: FloatArray, back: ByteArray, most: Int): Boolean {
            val p = e - 1
            val lowA = keyFrom[p]
            val highA = keyFrom[e] - 1
            val lowB = keyFrom[e]
            val highB = keyFrom[e + 1] - 1
            val dLow = q[lowB] - q[lowA] + SPAN
            val dHigh = q[highB] - q[highA] + SPAN
            val dxLow = x[lowB] - x[lowA] + PLACES
            if (dLow !in 0 until WIDE || dHigh !in 0 until WIDE || dxLow !in 0 until WIDE_PLACES) return false
            var anyHeld = false
            for (k in lowA..highA) if (heldUntil[k] >= eventStart[e] + HELD_MICROS) anyHeld = true
            val size1 = size(p)
            val size2 = size(e)
            val states1 = states(p)
            if (anyHeld) {
                for (s1 in 0 until states1) {
                    var mask = 0
                    for (j in 0 until size1) {
                        if (heldUntil[lowA + j] < eventStart[e] + HELD_MICROS) continue
                        val f = fingerOf(p, s1, j)
                        if (f == 0) continue
                        mask = mask or (1 shl f)
                        heldKey[s1 * 6 + f] = q[lowA + j]
                    }
                    heldMask[s1] = mask
                }
            }
            // Without a held key the chord before's states differ only by their outer fingers.
            var pairs = 0
            if (!anyHeld) {
                for (s1 in 0 until states1) {
                    val low1 = outerFinger(size1, s1, first = true)
                    val high1 = outerFinger(size1, s1, first = false)
                    val key = low1 * 6 + high1
                    val j = pairOf[key]
                    if (j < 0) {
                        pairOf[key] = pairs
                        pairLow[pairs] = low1
                        pairHigh[pairs] = high1
                        pairCost[pairs] = cost[s1]
                        pairState[pairs] = s1
                        pairs++
                    } else if (cost[s1] < pairCost[j]) {
                        pairCost[j] = cost[s1]
                        pairState[j] = s1
                    }
                }
                for (j in 0 until pairs) pairOf[pairLow[j] * 6 + pairHigh[j]] = -1
            }
            for (s2 in 0 until states(e)) {
                val low2 = outerFinger(size2, s2, first = true)
                val high2 = outerFinger(size2, s2, first = false)
                var best = Float.MAX_VALUE
                var from = 0
                if (!anyHeld) {
                    for (j in 0 until pairs) {
                        val kLow = pairLow[j] * 6 + low2
                        val c = pairCost[j] + SHAPE[kLow * WIDE + dLow] + SHAPE[(pairHigh[j] * 6 + high2) * WIDE + dHigh] +
                            SHIFT[kLow * WIDE_PLACES + dxLow]
                        if (c < best) {
                            best = c
                            from = pairState[j]
                        }
                    }
                } else {
                    var used = 0
                    for (m in 0 until size2) {
                        val f = fingerOf(e, s2, m)
                        if (f == 0) continue
                        used = used or (1 shl f)
                        usedKey[f] = q[lowB + m]
                    }
                    for (s1 in 0 until states1) {
                        val low1 = outerFinger(size1, s1, first = true)
                        val kLow = low1 * 6 + low2
                        var c = cost[s1] + SHAPE[kLow * WIDE + dLow] + SHAPE[(outerFinger(size1, s1, first = false) * 6 + high2) * WIDE + dHigh] +
                            SHIFT[kLow * WIDE_PLACES + dxLow]
                        var clash = heldMask[s1] and used
                        while (clash != 0) {
                            val f = Integer.numberOfTrailingZeros(clash)
                            if (heldKey[s1 * 6 + f] != usedKey[f]) c += HELD
                            clash = clash and (clash - 1)
                        }
                        if (c < best) {
                            best = c
                            from = s1
                        }
                    }
                }
                next[s2] = best + own(e, s2)
                back[e * most + s2] = from.toByte()
            }
            return true
        }

        /** The finger on a chord's lowest ([first]) or highest key in state [s]: a spread chord's are the thumb and the little finger. */
        private fun outerFinger(size: Int, s: Int, first: Boolean): Int {
            if (size > TRIED) return if (first) 1 else 5
            return if (first) LOW_FINGER[size][s] else HIGH_FINGER[size][s]
        }

        /** Each note gets its key's finger in its event's chosen state. */
        private fun write(chosen: IntArray) {
            for (e in 0 until events) {
                val from = keyFrom[e]
                for (j in eventFrom[e] until eventFrom[e + 1]) {
                    val i = index[j]
                    val qi = mirror(keyOf(i))
                    var k = 0
                    while (from + k < keyFrom[e + 1] - 1 && q[from + k] != qi) k++
                    out[i] = fingerOf(e, chosen[e], k).toByte()
                }
            }
        }
    }
}

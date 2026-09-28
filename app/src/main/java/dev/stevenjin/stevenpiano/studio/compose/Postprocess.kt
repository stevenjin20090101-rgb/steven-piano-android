// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio.compose

import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.midi.SmfWriter
import dev.stevenjin.stevenpiano.studio.StudioFailure
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random

/**
 * A composition ready for [SmfWriter]: its [notes] in onset order (no pedal), the tempo its grid used
 * ([bpm]), and how long it lasts ([durationMicros]: the last note's end).
 */
class Composition(val notes: List<SmfWriter.Note>, val bpm: Int, val durationMicros: Long)

/**
 * What the model wrote into what the piano plays (v1.7 — M24). Pure but for [compose]'s `random` (the
 * velocities' touch of unevenness).
 *
 * 1. Rests go; each note keeps its time and length (at least [MIN_NOTE_MICROS]).
 * 2. **Light quantisation** to a 1/16 grid at the chosen tempo, from the prompt's origin (the seed's
 *    first note): each onset moves [STRENGTH] of the way to its nearest grid line, its end with it.
 * 3. The piece starts at its first note, moved back by whole beats so the grid stays on the beat.
 * 4. **Folding** into the piano's 24–107 with [KeyMap] (by octaves).
 * 5. **One key, one strike at a time**: the piano needs 100 ms between two onsets of a key, so a strike
 *    sooner than [SAME_KEY_MICROS] (120 ms, a margin over it) joins the note before it (which lasts to
 *    the later end), and a note still held when its key is struck again ends there.
 * 6. **Velocities by mood** ([Mood.velocity], [Mood.spread]): the top note of each chord sings out,
 *    the bottom one and the inner voices step back, higher notes a touch brighter, a four-bar swell,
 *    a little unevenness; then a **closing fade** over the last [FADE_BARS] bars (4/4 at the tempo) to
 *    [FADE_FLOOR] of the level; never under [MIN_VELOCITY] or over [MAX_VELOCITY].
 *
 * No pedal: the pieces are written dry, as the model has no pedal.
 */
object Postprocess {
    /** Grid lines a beat: sixteenths. */
    const val STEPS_PER_BEAT = 4

    /** Light: half-way to the grid. */
    const val STRENGTH = 0.5
    const val MIN_VELOCITY = 20
    const val MAX_VELOCITY = 110

    /**
     * Two onsets of one key at least 120 ms apart: the piano needs 100 ms, and the player's guard
     * ([dev.stevenjin.stevenpiano.midi.NoteRouter]) thins a strike that comes sooner by the clock it is
     * sent at; the 20 ms more keep the file's ticks (up to 3 ms at 40 bpm) and the player's timing from
     * ever bringing a composition's two strikes under it (measured: a 100 ms gap was 99 ms in the file).
     */
    const val SAME_KEY_MICROS = 120_000L
    const val MIN_NOTE_MICROS = 60_000L
    const val FADE_BARS = 2
    const val BEATS_PER_BAR = 4

    /** The last note plays at this share of its shaped velocity. */
    const val FADE_FLOOR = 0.45

    /** Onsets this close sound as one chord (for the voicing). */
    const val CHORD_MICROS = 30_000L

    /** The swell's length, in bars. */
    private const val PHRASE_BARS = 4

    /**
     * [events] (times from the prompt's origin; the seed's too if they are to be heard) as a piece at
     * [bpm] in [mood]. [strength] 1 puts every onset on the grid, 0 leaves it. A piece without a note
     * is refused ([ComposeFailures.NO_MUSIC]).
     */
    fun compose(events: List<AmtEvent>, bpm: Int, mood: Mood, random: Random, strength: Double = STRENGTH): Composition {
        require(bpm > 0) { "bpm $bpm" }
        require(strength in 0.0..1.0) { "strength $strength" }
        val beat = 60.0 / bpm
        val step = beat / STEPS_PER_BEAT
        val placed = events.filter { !it.isRest && it.instrument == 0 }.map { e ->
            val on = e.time.toDouble() / Amt.TICKS_PER_SECOND
            val length = maxOf(e.duration.toDouble() / Amt.TICKS_PER_SECOND, MIN_NOTE_MICROS / 1e6)
            val moved = on + strength * (Math.rint(on / step) * step - on)
            Placed(moved, moved + length, KeyMap.map(e.pitch, 0, fold = true))
        }
        if (placed.isEmpty()) throw StudioFailure(ComposeFailures.NO_MUSIC)
        val start = floor(placed.minOf { it.on } / beat + 1e-9) * beat
        val notes = oneStrikeAtATime(
            placed.map { Timed((it.on - start).micros(), (it.off - start).micros(), it.key) },
        )
        val velocities = velocities(notes, beat, mood, random)
        val out = notes.mapIndexed { i, n -> SmfWriter.Note(n.on, n.off, n.key, velocities[i]) }
        return Composition(out, bpm, out.maxOf { it.offMicros })
    }

    /**
     * Per key, in onset order: a strike less than [SAME_KEY_MICROS] after the one before joins it (which
     * then lasts to the later of their ends); a note still held at its key's next strike ends there.
     * The result in onset order (then by key).
     */
    internal fun oneStrikeAtATime(notes: List<Timed>): List<Timed> {
        val out = ArrayList<Timed>(notes.size)
        for ((_, byKey) in notes.groupBy { it.key }) {
            var held: Timed? = null
            for (n in byKey.sortedWith(compareBy<Timed>({ it.on }, { -it.off }))) {
                val before = held
                held = when {
                    before == null -> n
                    n.on - before.on < SAME_KEY_MICROS -> before.copy(off = maxOf(before.off, n.off))
                    else -> {
                        out += if (before.off > n.on) before.copy(off = n.on) else before
                        n
                    }
                }
            }
            held?.let { out += it }
        }
        out.sortWith(compareBy<Timed>({ it.on }, { it.key }))
        return out
    }

    /** Each note's velocity, as [compose] describes; [notes] in onset order. */
    internal fun velocities(notes: List<Timed>, beat: Double, mood: Mood, random: Random): IntArray {
        val spread = mood.spread.toDouble()
        val voice = DoubleArray(notes.size)
        var i = 0
        while (i < notes.size) {
            var j = i
            while (j + 1 < notes.size && notes[j + 1].on - notes[i].on <= CHORD_MICROS) j++
            val chord = i..j
            val top = chord.maxBy { notes[it].key }
            val bottom = chord.minBy { notes[it].key }
            for (k in chord) {
                voice[k] = when {
                    k == top -> MELODY
                    chord.count() > 1 && k == bottom -> BASS
                    else -> INNER
                } * spread
            }
            i = j + 1
        }
        val barMicros = BEATS_PER_BAR * beat * 1e6
        val lastOn = notes.last().on
        val fadeFrom = maxOf(notes.first().on.toDouble(), lastOn - FADE_BARS * barMicros)
        val phrase = PHRASE_BARS * barMicros
        return IntArray(notes.size) { k ->
            val n = notes[k]
            val register = (n.key - MIDDLE_KEY) / 24.0 * REGISTER * spread
            val swell = (sin(PI * ((n.on / phrase) % 1.0)) - 0.5) * SWELL * spread
            val touch = (random.nextDouble() * 2 - 1) * TOUCH * spread
            val level = mood.velocity + voice[k] + register + swell + touch
            val fade = if (n.on <= fadeFrom || lastOn <= fadeFrom) 1.0 else 1.0 - (1.0 - FADE_FLOOR) * ((n.on - fadeFrom) / (lastOn - fadeFrom))
            (level * fade).roundToInt().coerceIn(MIN_VELOCITY, MAX_VELOCITY)
        }
    }

    /** A note in seconds, before it is placed on the timeline. */
    private class Placed(val on: Double, val off: Double, val key: Int)

    /** A note on the piece's timeline: [on] and [off] in microseconds, the piano's [key]. */
    internal data class Timed(val on: Long, val off: Long, val key: Int)

    private fun Double.micros(): Long = (this * 1e6).roundToLong().coerceAtLeast(0L)

    private const val MIDDLE_KEY = 64
    private const val MELODY = 0.5
    private const val BASS = -0.3
    private const val INNER = -0.15
    private const val REGISTER = 0.4
    private const val SWELL = 0.6
    private const val TOUCH = 0.3
}

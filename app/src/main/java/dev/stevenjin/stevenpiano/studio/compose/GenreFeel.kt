// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio.compose

import dev.stevenjin.stevenpiano.midi.SmfWriter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Studio's genre styles (v1.37 — M74): a piece composed in the manner of Steven's own songs of a genre, with the
 * genre's feel on top ([GenreFeel]). Each sets defaults the person can still change: a [mood], a tempo in the
 * gentle half of [lowBpm]..[highBpm] ([bpmFor]), and for lo-fi a minor key ([minor]: the seed's relative minor,
 * which moves no note). [key] is how the history and the web panel name it; [label] how the screen does.
 */
enum class Genre(val key: String, val label: String, val lowBpm: Int, val highBpm: Int, val mood: Mood, val minor: Boolean) {
    LoFi("lofi", "Lo-fi", 70, 85, Mood.Calm, minor = true),
    House("house", "House", 120, 126, Mood.Bright, minor = false),
    Chill("chill", "Chill", 60, 75, Mood.Calm, minor = false),
    Cinematic("cinematic", "Cinematic", 60, 80, Mood.Melancholy, minor = false),
    ;

    /** How a sentence says it: "lo-fi", "house". */
    val word: String get() = label.lowercase(Locale.ROOT)

    /** The gentle half of the genre's tempo range (Steven likes it soft): from its slowest to its middle. */
    val gentleBpm: IntRange get() = lowBpm..(lowBpm + highBpm) / 2

    /** The tempo when none is asked: the seed's own, held in [gentleBpm]. */
    fun bpmFor(seedBpm: Int): Int = seedBpm.coerceIn(gentleBpm)

    companion object {
        fun of(key: String?): Genre? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A genre's feel on what the model wrote (v1.37 — M74), called by [Postprocess] after its own steps; pure but for
 * lo-fi's lazy timing, drawn from the job's random. Without a genre every step gives back what it was given.
 *
 * | Genre | Timing | Velocities | Pedal |
 * |---|---|---|---|
 * | lo-fi | eighths swung to [SWING] (an off-beat moves a tenth of a beat later), each onset 0–[LAZY_MICROS] late | × [SOFTEN], at most 80 | changed every bar |
 * | house | the 1/16 grid at [HOUSE_STRENGTH] | × [BRIGHTER]; the left hand evened over a bar, the beats accented | none |
 * | chill | a ritardando over the last [RIT_BARS] bars | × [SOFTEN], at most 70; chord notes under the softest fifth dropped | changed every two bars |
 * | cinematic | — | the [arc]: soft, a peak at two thirds, then fading; at most 88 | changed every two bars |
 *
 * The piano's safe limits are [Postprocess]'s and stay as they are: keys 24–107, a key struck again no sooner than
 * [Postprocess.SAME_KEY_MICROS], velocities never under [Postprocess.MIN_VELOCITY] (each genre's cap is under
 * [Postprocess.MAX_VELOCITY]). Every step that moves a note only shortens how long it sounds or keeps it: an onset
 * only ever moves later with its end where it was ([timing]), the ritardando keeps each note's length and only
 * widens the gaps between onsets ([ending]), and thinning only takes notes away. So no note sounds longer than the
 * model's, no more keys are down at once than before, and every note still ends.
 */
object GenreFeel {
    /** Lo-fi's swing: the off-beat eighth falls at 60 % of the beat. */
    const val SWING = 0.6

    /** Lo-fi's laziness: each onset up to 20 ms late (never early: an earlier onset could hold a key longer). */
    const val LAZY_MICROS = 20_000L

    /** House's grid: firmer than [Postprocess.STRENGTH], still light. */
    const val HOUSE_STRENGTH = 0.7

    /** Lo-fi and chill play softer than the mood, house brighter. */
    const val SOFTEN = 0.9
    const val BRIGHTER = 1.08

    /** House's beat in the left hand: a beat this much louder than its bar's level, the notes between this much softer. */
    const val ACCENT = 8
    const val BETWEEN = 4

    /** Keys under this are the left hand (middle C). */
    const val LEFT_HAND_BELOW = 60

    /** Chill drops chord notes (never a chord's top, never a lone note) at or under this share's velocity. */
    const val THIN_SHARE = 0.2

    /** Chill's ritardando: over the last two bars the tempo eases to 1 / (1 + [RIT]) of its own. */
    const val RIT_BARS = 2
    const val RIT = 0.35

    /** Cinematic's arc: its level at the start, at the peak ([PEAK] of the way through) and at the end. */
    const val PEAK = 2.0 / 3.0
    const val ARC_START = 0.65
    const val ARC_PEAK = 1.25
    const val ARC_END = 0.6

    /** A pedal goes down this long after its bar line, once the bar's first notes have struck. */
    const val PEDAL_LAG_MICROS = 60_000L

    /** The loudest a genre plays: every one on the gentle side, none near Full power's. */
    fun cap(genre: Genre): Int = when (genre) {
        Genre.LoFi -> 80
        Genre.House -> 92
        Genre.Chill -> 70
        Genre.Cinematic -> 88
    }

    /** The grid's strength for [genre]: house's firmer one, else [strength] as it was. */
    fun strength(genre: Genre?, strength: Double): Double = if (genre == Genre.House) maxOf(strength, HOUSE_STRENGTH) else strength

    /** How many bars a pedal holds for [genre] (0: no pedal). */
    fun pedalBars(genre: Genre?): Int = when (genre) {
        Genre.LoFi -> 1
        Genre.Chill, Genre.Cinematic -> 2
        Genre.House, null -> 0
    }

    /** Lo-fi's timing on [notes] (one strike of a key at a time, in onset order): swung, then lazy, each onset only later ([later]). */
    internal fun timing(notes: List<Postprocess.Timed>, beat: Double, genre: Genre?, random: Random): List<Postprocess.Timed> {
        if (genre != Genre.LoFi || notes.isEmpty()) return notes
        val beatMicros = beat * 1e6
        val wanted = LongArray(notes.size) { swingDelay(notes[it].on, beatMicros) + (random.nextDouble() * LAZY_MICROS).toLong() }
        return later(notes, wanted)
    }

    /**
     * How much later the swing puts an onset at [on] (from a beat; [beatMicros] a beat): the beat's first half
     * stretched to [SWING] of it and its second half pressed into the rest, so an off-beat eighth moves a tenth of a
     * beat later, a sixteenth half that, and a note on the beat stays.
     */
    fun swingDelay(on: Long, beatMicros: Double): Long {
        val phase = (on / beatMicros) % 1.0
        val swung = if (phase < 0.5) phase * SWING / 0.5 else SWING + (phase - 0.5) * (1 - SWING) / 0.5
        return ((swung - phase) * beatMicros).roundToLong().coerceAtLeast(0L)
    }

    /**
     * Each note's onset [wanted] later, its end where it was: never so late that it lasts under
     * [Postprocess.MIN_NOTE_MICROS] (a note already that short stays put), nor within [Postprocess.SAME_KEY_MICROS]
     * of its key's next strike (taken from the last back, as moved). So every note sounds within its own time before.
     */
    internal fun later(notes: List<Postprocess.Timed>, wanted: LongArray): List<Postprocess.Timed> {
        val out = arrayOfNulls<Postprocess.Timed>(notes.size)
        for ((_, indices) in notes.indices.groupBy { notes[it].key }) {
            var next = Long.MAX_VALUE
            for (i in indices.sortedByDescending { notes[it].on }) {
                val n = notes[i]
                val limit = minOf(n.off - Postprocess.MIN_NOTE_MICROS, if (next == Long.MAX_VALUE) Long.MAX_VALUE else next - Postprocess.SAME_KEY_MICROS)
                val on = maxOf(n.on, minOf(n.on + wanted[i].coerceAtLeast(0L), limit))
                out[i] = n.copy(on = on)
                next = on
            }
        }
        return out.map { it!! }.sortedWith(compareBy<Postprocess.Timed>({ it.on }, { it.key }))
    }

    /** [velocities] (the mood's, for [notes] in onset order) in [genre]'s feel, each within [Postprocess.MIN_VELOCITY]..[cap]. */
    internal fun velocities(notes: List<Postprocess.Timed>, velocities: IntArray, beat: Double, genre: Genre?): IntArray {
        if (genre == null || notes.isEmpty()) return velocities
        val level: DoubleArray = when (genre) {
            Genre.LoFi, Genre.Chill -> DoubleArray(notes.size) { velocities[it] * SOFTEN }
            Genre.House -> house(notes, velocities, beat * 1e6)
            Genre.Cinematic -> {
                val first = notes.first().on
                val span = (notes.last().on - first).toDouble()
                DoubleArray(notes.size) { velocities[it] * arc(if (span <= 0) 0.0 else (notes[it].on - first) / span) }
            }
        }
        val top = cap(genre)
        return IntArray(notes.size) { level[it].roundToInt().coerceIn(Postprocess.MIN_VELOCITY, top) }
    }

    /**
     * House: brighter; the left hand an even pulse, each of its notes at its bar's level (the left hand's mean within
     * half a bar each side, so the swell and the closing fade still carry), a beat [ACCENT] above it, the rest [BETWEEN] under.
     */
    private fun house(notes: List<Postprocess.Timed>, velocities: IntArray, beatMicros: Double): DoubleArray {
        val out = DoubleArray(notes.size) { velocities[it] * BRIGHTER }
        val left = notes.indices.filter { notes[it].key < LEFT_HAND_BELOW }
        val half = Postprocess.BEATS_PER_BAR * beatMicros / 2
        var from = 0
        var until = 0
        var sum = 0.0
        for (i in left) {
            val at = notes[i].on
            while (until < left.size && notes[left[until]].on <= at + half) sum += out[left[until++]]
            while (notes[left[from]].on < at - half) sum -= out[left[from++]]
            val bar = sum / (until - from)
            val phase = (at / beatMicros) % 1.0
            out[i] = bar + if (phase < 0.125 || phase > 0.875) ACCENT else -BETWEEN
        }
        return out
    }

    /**
     * Cinematic's dynamics at [t] (0 the first onset, 1 the last): from [ARC_START] it builds, eased, to [ARC_PEAK]
     * two thirds of the way through ([PEAK]), then falls away to [ARC_END].
     */
    fun arc(t: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        return if (x <= PEAK) ARC_START + (ARC_PEAK - ARC_START) * ease(x / PEAK) else ARC_PEAK + (ARC_END - ARC_PEAK) * ease((x - PEAK) / (1 - PEAK))
    }

    private fun ease(u: Double): Double = u * u * (3 - 2 * u)

    /**
     * Which of [notes] stay (in onset order, with their [velocities]): all of them but in chill, which drops the notes
     * of a chord (onsets within [Postprocess.CHORD_MICROS]) at or under the softest fifth's velocity, never the chord's
     * top (its melody), never a lone note.
     */
    internal fun keep(notes: List<Postprocess.Timed>, velocities: IntArray, genre: Genre?): BooleanArray {
        val keep = BooleanArray(notes.size) { true }
        if (genre != Genre.Chill || notes.size < 2) return keep
        val threshold = velocities.sorted()[(notes.size * THIN_SHARE).toInt()]
        var i = 0
        while (i < notes.size) {
            var j = i
            while (j + 1 < notes.size && notes[j + 1].on - notes[i].on <= Postprocess.CHORD_MICROS) j++
            if (j > i) {
                val top = (i..j).maxBy { notes[it].key }
                for (k in i..j) if (k != top && velocities[k] <= threshold) keep[k] = false
            }
            i = j + 1
        }
        return keep
    }

    /** A piece's notes and its pedal once the genre has ended it. */
    class Ending(val notes: List<SmfWriter.Note>, val pedals: List<SmfWriter.Pedal>)

    /**
     * The end of [notes] (in onset order, from a beat) in [genre]'s feel: chill's ritardando, which moves each onset
     * later by a stretch that grows over the last [RIT_BARS] bars (each note keeps its length, so only the gaps grow);
     * then the pedal, down [PEDAL_LAG_MICROS] after every [pedalBars] bars' line and up at the next, the line moved
     * with the ritardando, never past the last note's end. None without a genre (the pieces stay dry).
     */
    fun ending(notes: List<SmfWriter.Note>, beat: Double, genre: Genre?): Ending {
        if (genre == null || notes.isEmpty()) return Ending(notes, emptyList())
        val bar = Postprocess.BEATS_PER_BAR * beat * 1e6
        val lastOn = notes.maxOf { it.onMicros }
        val from = maxOf(notes.minOf { it.onMicros }.toDouble(), lastOn - RIT_BARS * bar)
        val length = lastOn - from
        val slows = genre == Genre.Chill && length > 0
        val move: (Long) -> Long = { t -> if (!slows || t <= from) t else (t + RIT * (t - from) * (t - from) / (2 * length)).roundToLong() }
        val moved = if (!slows) notes else notes.map { n -> move(n.onMicros).let { on -> n.copy(onMicros = on, offMicros = on + (n.offMicros - n.onMicros)) } }
            .sortedWith(compareBy<SmfWriter.Note>({ it.onMicros }, { it.key }))
        val bars = pedalBars(genre)
        if (bars == 0) return Ending(moved, emptyList())
        val span = (bars * bar).roundToLong()
        val end = moved.maxOf { it.offMicros }
        val lastLine = notes.maxOf { it.offMicros }
        val pedals = ArrayList<SmfWriter.Pedal>()
        var line = 0L
        while (line < lastLine) {
            val down = move(line) + PEDAL_LAG_MICROS
            val up = minOf(move(line + span), end)
            if (up > down) pedals += SmfWriter.Pedal(down, up)
            line += span
        }
        return Ending(moved, pedals)
    }
}

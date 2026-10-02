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
import dev.stevenjin.stevenpiano.midi.MidiPiece
import dev.stevenjin.stevenpiano.studio.StudioFailure
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * How the sampler picks each token: logits divided by [temperature], then top-p ([topP]: the most
 * likely tokens whose probabilities first reach it, the rest dropped). A temperature of 0 is greedy,
 * the most likely token every time (the fixture's check). [allowEnd] lets the model end the piece
 * itself: SEPARATOR, which the package never samples, is allowed where a time goes and stops it there.
 */
data class SamplingSettings(val temperature: Double, val topP: Double, val allowEnd: Boolean = false) {
    init {
        require(temperature >= 0.0 && temperature.isFinite()) { "temperature $temperature" }
        require(topP > 0.0 && topP <= 1.0) { "top-p $topP" }
    }

    val greedy: Boolean get() = temperature == 0.0

    companion object {
        /** The most likely token every time, as the spike's fixture was made. */
        val Greedy = SamplingSettings(0.0, 1.0)
    }
}

/**
 * The mood chips (v1.7 — M24), in the sheet's order: how freely the model samples ([temperature],
 * [topP]) and how the piano plays it ([velocity], the middle of the notes' velocities, and [spread],
 * how far they move around it; [Postprocess]). Calm plays softest.
 */
enum class Mood(val label: String, val temperature: Double, val topP: Double, val velocity: Int, val spread: Int) {
    Calm("Calm", 0.8, 0.9, 46, 8),
    Bright("Bright", 1.0, 0.95, 66, 12),
    Wild("Wild", 1.15, 0.98, 78, 18),
    Melancholy("Melancholy", 0.85, 0.9, 52, 10),
    ;

    val sampling: SamplingSettings get() = SamplingSettings(temperature, topP)
}

/** A key: its [tonic] pitch class (0 = C … 11 = B), major or [minor]. */
data class MusicKey(val tonic: Int, val minor: Boolean) {
    init {
        require(tonic in 0..11) { "tonic $tonic" }
    }

    /** The major key with the same signature: itself, or a minor key's relative major (A minor: C). */
    val relativeMajor: Int get() = if (minor) (tonic + 3) % 12 else tonic

    /** The minor key with the same signature: itself, or a major key's relative minor (C major: A minor). */
    val relativeMinor: MusicKey get() = if (minor) this else MusicKey((tonic + 9) % 12, true)

    /** "C", "F♯", "B♭": the tonic as a key of this mode is usually written. */
    val tonicName: String get() = (if (minor) MINOR_NAMES else MAJOR_NAMES)[tonic]

    /** "C major", "F♯ minor". */
    val label: String get() = "$tonicName ${if (minor) "minor" else "major"}"

    companion object {
        val C = MusicKey(0, false)
        private val MAJOR_NAMES = arrayOf("C", "D♭", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
        private val MINOR_NAMES = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "G♯", "A", "B♭", "B")

        /** The sheet's chips: C to B, major then minor. */
        val all: List<MusicKey> = (0..11).map { MusicKey(it, false) } + (0..11).map { MusicKey(it, true) }
    }
}

/** The piece a composition grows from: its [title] and [composer] ("In the manner of"), and its parsed [midi]. */
class SeedPiece(val title: String, val composer: String?, val midi: MidiPiece)

/**
 * What a seed brings before anything is chosen, the compose sheet's defaults: its [key], estimated
 * from its first 15 s (between the file's own key signature's two keys when it has one), and its tempo
 * over them, [exactBpm] as the file has it (quarter notes a minute) and [bpm], rounded into the
 * stepper's 40–200.
 */
data class SeedFacts(val key: MusicKey, val bpm: Int, val exactBpm: Double)

/** What the compose sheet asks for: a [mood], a [key] and a tempo in [bpm] (null: the seed's own), and a length in [minutes] (1–5). */
data class ComposeRequest(val mood: Mood = Mood.Calm, val key: MusicKey? = null, val bpm: Int? = null, val minutes: Int = 2)

/**
 * A composition's prompt, ready for the [Sampler]. [events] are the seed as the model reads it (its
 * first 15 s from its first note, transposed by [transpose] semitones and folded into 24–107, times
 * multiplied by [timeScale], padded with rests; times from 0), [currentTime] its last event's time. The
 * sampler writes at most [budget] tokens and stops at [endTime] (ticks: the seed's 15 s and the
 * length), with [sampling] from the [mood]. [key] and [bpm] are what was chosen (the grid and the
 * velocities follow them); [mannerOf] is the seed's "<title> (<composer>)", the sheet's eyebrow.
 */
class Prompt(
    val events: List<AmtEvent>,
    val currentTime: Int,
    val endTime: Int,
    val budget: Int,
    val sampling: SamplingSettings,
    val mood: Mood,
    val key: MusicKey,
    val bpm: Int,
    val transpose: Int,
    val timeScale: Double,
    val mannerOf: String,
) {
    /** AUTOREGRESS, then the seed's tokens. */
    val tokens: IntArray get() = AmtTokenizer.prompt(events)
}

/**
 * The compose sheet's choices into a [Prompt] (v1.7 — M24). The seed is the first 15 s of the chosen
 * library piece from its first note, tokenised as the package builds a prompt (`clip(0, 15 s)`, `pad`,
 * after AUTOREGRESS; [AmtTokenizer]); time-scaled to the chosen tempo; transposed to the chosen key and
 * folded into the piano's 24–107. The seed's own key and tempo ([facts]) leave it untouched, so the
 * Bach fixture's seed comes out as the spike's 214 tokens. A mood sets the sampling; a length of 1–5
 * minutes sets the token budget (45 tokens a second of music) and where the piece ends. Pure.
 */
object PromptBuilder {
    const val SEED_SECONDS = 15
    const val SEED_TICKS = SEED_SECONDS * Amt.TICKS_PER_SECOND
    /**
     * The token budget per second of music asked for (1.7): 45, so a dense piece reaches its length (at M24's
     * 30 a one-minute Wild piece once stopped at 42 s). Since 1.12 (M30) the budget follows the length all the
     * way ([MAX_TOKENS], 13,500 for five minutes; 9,000 before, which stopped long pieces short): memory does not
     * grow with it (the cache is bounded by the 1,024-token context), only time does, and a budget stop is said
     * plainly on the card.
     */
    const val TOKENS_PER_SECOND = 45
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 5
    const val MAX_TOKENS = MAX_MINUTES * 60 * TOKENS_PER_SECOND
    const val MIN_BPM = 40
    const val MAX_BPM = 200

    /** The piano's middle, where a transposition that could go either way leans. */
    private const val MIDDLE = (KeyMap.LOWEST + KeyMap.HIGHEST) / 2.0

    /** Tokens for [minutes] of music: 2,700 a minute, 13,500 for five ([MAX_TOKENS]). */
    fun budget(minutes: Int): Int = min(minutes.coerceIn(MIN_MINUTES, MAX_MINUTES) * 60 * TOKENS_PER_SECOND, MAX_TOKENS)

    /** The seed's key and tempo: the compose sheet's defaults. */
    fun facts(midi: MidiPiece): SeedFacts = facts(midi, AmtTokenizer.notes(midi, SEED_SECONDS.toDouble()))

    private fun facts(midi: MidiPiece, notes: List<SeedNote>): SeedFacts {
        val exact = bpmOf(midi, notes)
        return SeedFacts(keyOf(notes, signatureAt(midi, notes)), exact.roundToInt().coerceIn(MIN_BPM, MAX_BPM), exact)
    }

    /**
     * The key signature in force where the seed starts (its sharps, flats negative), or null when the file
     * has none there. Its count of sharps or flats is what files get right; their major or minor flag,
     * often not (Für Elise's files say C major), so only the count is kept.
     */
    private fun signatureAt(midi: MidiPiece, notes: List<SeedNote>): Int? {
        val start = notes.firstOrNull()?.let { (it.on * 1e6).roundToLong() } ?: 0L
        return midi.keySignatures.lastOrNull { it.atMicros <= start }?.sharps
    }

    /** The key the sheet suggests for [mood]: the seed's own, or for Melancholy its minor (the relative minor of a major seed). */
    fun suggestedKey(mood: Mood, seed: MusicKey): MusicKey = if (mood == Mood.Melancholy) seed.relativeMinor else seed

    /** "Clair de lune (Claude Debussy)", or the title alone when the composer isn't known. */
    fun mannerOf(title: String, composer: String?): String =
        if (composer.isNullOrBlank()) title.trim() else "${title.trim()} (${composer.trim()})"

    /** The prompt for [request] from [seed]; a seed without a note is refused ([ComposeFailures.NO_SEED]). */
    fun build(seed: SeedPiece, request: ComposeRequest): Prompt {
        val facts = facts(seed.midi)
        val bpm = (request.bpm ?: facts.bpm).coerceIn(MIN_BPM, MAX_BPM)
        // The seed's own tempo leaves its times alone (exactly: the fixture's arithmetic); another scales them.
        val scale = if (bpm == facts.bpm) 1.0 else facts.exactBpm / bpm
        val notes = AmtTokenizer.notes(seed.midi, SEED_SECONDS / scale + 1.0)
        if (notes.isEmpty()) throw StudioFailure(ComposeFailures.NO_SEED)
        val clipped = AmtTokenizer.clip(AmtTokenizer.events(notes, origin = notes.first().on, scale = scale), 0, SEED_TICKS)
        val key = request.key ?: facts.key
        val shift = transposition(facts.key, key, clipped.map { it.pitch })
        val seen = HashSet<Int>()
        val moved = clipped.mapNotNull { e ->
            val note = KeyMap.map(e.pitch, shift, fold = true)
            if (seen.add(e.time * Amt.MAX_PITCH + note)) e.copy(note = note) else null   // folding may land two on one key
        }
        val events = AmtTokenizer.pad(moved, SEED_TICKS)
        val minutes = request.minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
        return Prompt(
            events = events,
            currentTime = AmtTokenizer.maxTime(events),
            endTime = SEED_TICKS + minutes * 60 * Amt.TICKS_PER_SECOND,
            budget = budget(minutes),
            sampling = request.mood.sampling,
            mood = request.mood,
            key = key,
            bpm = bpm,
            transpose = shift,
            timeScale = scale,
            mannerOf = mannerOf(seed.title, seed.composer),
        )
    }

    /**
     * The semitones that take music in [from] to [to]. A key goes by its signature (a minor key by its
     * relative major: transposing can't change a mode, so C major to A minor is no move, C major to E
     * minor a move to G), up or down: whichever leaves fewer of [pitches] outside the piano's 24–107,
     * then the smaller move, then the one nearer the keyboard's middle.
     */
    fun transposition(from: MusicKey, to: MusicKey, pitches: List<Int>): Int {
        val up = Math.floorMod(to.relativeMajor - from.relativeMajor, 12)
        if (up == 0) return 0
        val mean = if (pitches.isEmpty()) MIDDLE else pitches.average()
        return listOf(up, up - 12).minWith(
            compareBy<Int>({ shift -> pitches.count { it + shift !in KeyMap.LOWEST..KeyMap.HIGHEST } }, { abs(it) }, { abs(mean + it - MIDDLE) }),
        )
    }

    /**
     * The key of [notes] by Krumhansl and Kessler's key profiles: the pitch classes weighed by how long
     * they sound (each note 50 ms to 2 s), set against each key's profile; the best correlation wins (a
     * major key on a tie). With the file's key signature ([sharps], flats negative) only its two keys are
     * weighed, its major and that major's relative minor (Clair de lune's five flats: D♭ major or B♭
     * minor); without one, all 24. C major when there is nothing to go on (or the signature's major).
     */
    fun keyOf(notes: List<SeedNote>, sharps: Int? = null): MusicKey {
        val signed = sharps?.let { Math.floorMod(7 * it, 12) }
        val weight = DoubleArray(12)
        for (n in notes) weight[Math.floorMod(n.key, 12)] += (n.off - n.on).coerceIn(0.05, 2.0)
        if (weight.all { it == 0.0 }) return MusicKey(signed ?: 0, false)
        var best = MusicKey(signed ?: 0, false)
        var bestScore = Double.NEGATIVE_INFINITY
        for (minor in listOf(false, true)) {
            val profile = if (minor) MINOR_PROFILE else MAJOR_PROFILE
            for (tonic in 0..11) {
                if (signed != null && tonic != (if (minor) (signed + 9) % 12 else signed)) continue
                val score = correlation(weight) { pc -> profile[Math.floorMod(pc - tonic, 12)] }
                if (score > bestScore + 1e-12) {
                    bestScore = score
                    best = MusicKey(tonic, minor)
                }
            }
        }
        return best
    }

    /**
     * The seed's tempo in quarter notes a minute: the beats the tempo map counts over its first 15 s
     * (from its first note), or the tempo at the start when it has no note.
     */
    private fun bpmOf(midi: MidiPiece, notes: List<SeedNote>): Double {
        val map = midi.tempoMap
        val first = notes.firstOrNull() ?: return 60_000_000.0 / map.tempoAt(0L)
        val start = (first.on * 1e6).roundToLong()
        val end = start + SEED_SECONDS * 1_000_000L
        val beats = map.microsToBeats(end) - map.microsToBeats(start)
        return beats * 60.0 / SEED_SECONDS
    }

    private fun correlation(x: DoubleArray, y: (Int) -> Double): Double {
        val mx = x.average()
        val ys = DoubleArray(12) { y(it) }
        val my = ys.average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in 0..11) {
            sxy += (x[i] - mx) * (ys[i] - my)
            sxx += (x[i] - mx) * (x[i] - mx)
            syy += (ys[i] - my) * (ys[i] - my)
        }
        return if (sxx == 0.0 || syy == 0.0) 0.0 else sxy / sqrt(sxx * syy)
    }

    /** Krumhansl and Kessler (1982): how well each scale degree fits a major key, the tonic first. */
    private val MAJOR_PROFILE = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)

    /** …and a minor key. */
    private val MINOR_PROFILE = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
}

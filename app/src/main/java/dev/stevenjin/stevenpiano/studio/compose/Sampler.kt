// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio.compose

import dev.stevenjin.stevenpiano.studio.StudioFailure
import kotlinx.coroutines.CancellationException
import kotlin.math.exp
import kotlin.math.min
import kotlin.random.Random

/** Why the sampler stopped: its token budget spent, the piece's end time reached, or the model ended it (SEPARATOR). */
enum class Stop { Budget, EndTime, Separator }

/**
 * What the sampler wrote: the new [events] (whole ones; rests included, the seed's not; times from the
 * prompt's origin), every token it sampled in order ([tokens], a last partial event included; each time
 * as ticks from the prompt's origin, so past 100 s of music they are no longer the model's own time
 * tokens), how often the window [slides], and why it [stop]ped.
 */
class Generation(val events: List<AmtEvent>, val tokens: IntArray, val slides: Int, val stop: Stop)

/**
 * The composer's decoding loop (v1.7 — M24): the model continues a seed a token at a time through its
 * key-value cache ([ComposerModel]), each token picked under the `anticipation` package's rules
 * (`sample.py`: `safe_logits`, `future_logits`), then temperature and top-p ([SamplingSettings]).
 *
 * - **Masks.** Never a control or a special token; a time where a time goes (none before the current
 *   time: `future_logits`), a duration where a duration goes, and where a note goes the piano's notes
 *   (11,000–11,127) or a rest. `safe_logits` leaves REST open in every slot; here it is only a note (a
 *   rest in a time or duration slot would make a malformed event, which the package's `generate()`
 *   takes for the end). [SamplingSettings.allowEnd] opens SEPARATOR where a time goes, and it stops the
 *   piece there. NaN and infinite logits count as masked.
 * - **Repetition guard.** No more than [MAX_REPEATS] identical note tokens in a row (a rest counts):
 *   after four the fifth must differ.
 * - **Window.** [Amt.CONTEXT] positions: before an event that wouldn't fit, or once the current time is
 *   more than [MAX_REL_TIME] ticks past the window's origin (times are only 0–9,999), it slides: the
 *   last [keepEvents] events (none more than [KEEP_SPAN] ticks before the current time) are kept, their
 *   times made relative to the earliest of them, and prefilled again after AUTOREGRESS. A seed is
 *   prefilled the same way, its last 340 events at most, so the first event fits.
 * - **Hooks.** [cancelled] is asked before every token (a CancellationException); [memoryHolds] every
 *   [PROGRESS_EVERY] tokens and before a slide ([ComposeFailures.RAN_OUT]); [generate]'s progress hears
 *   every [PROGRESS_EVERY] tokens the tokens made and how far along it is (the larger of the budget's
 *   share spent and the music's share written, from the seed's time to the end time), then 1 at the end.
 * - **Stops** at the budget (a hard stop, even mid-event), at the end time (an event at or past it is
 *   not written, as the package's `generate()`), or at SEPARATOR when allowed.
 *
 * Greedy settings take the first most likely token (numpy's `argmax`): from the Bach seed the INT8
 * model gives the fixture's 64 tokens. Blocking: run it on the job's own thread, one run at a time
 * (its work arrays are its own).
 */
class Sampler(
    private val model: ComposerModel,
    private val random: Random = Random.Default,
    private val cancelled: () -> Boolean = { false },
    private val memoryHolds: () -> Boolean = { true },
    private val keepEvents: Int = KEEP_EVENTS,
) {
    init {
        require(keepEvents in 1..MAX_PREFILL_EVENTS) { "keep 1 to $MAX_PREFILL_EVENTS events, not $keepEvents" }
    }

    // Work arrays, reused token after token: the candidates of a slot, their weights, their order.
    private val candidates = IntArray(Amt.MAX_TIME + 1)
    private val weights = DoubleArray(Amt.MAX_TIME + 1)
    private val order = LongArray(Amt.MAX_TIME + 1)

    /** Continues [prompt]'s seed with its sampling, budget and end time. */
    fun generate(prompt: Prompt, progress: (made: Int, fraction: Float) -> Unit = { _, _ -> }): Generation =
        generate(prompt.events, prompt.sampling, prompt.budget, prompt.endTime, progress)

    /**
     * Continues [seed] (events in time order, times from 0) with [settings]: at most [budget] tokens,
     * no event at or after [endTime] ticks. [progress] hears (tokens made, how far along: 0–1).
     */
    fun generate(
        seed: List<AmtEvent>,
        settings: SamplingSettings,
        budget: Int,
        endTime: Int = Int.MAX_VALUE,
        progress: (made: Int, fraction: Float) -> Unit = { _, _ -> },
    ): Generation {
        require(budget >= 0) { "budget $budget" }
        val history = ArrayList<AmtEvent>(seed.size + budget / Amt.EVENT_TOKENS + 1)
        history += seed
        val tokens = IntArray(budget)
        var made = 0
        var slides = 0
        var origin = 0
        var positions = 0
        var current = AmtTokenizer.maxTime(seed)
        val start = current
        var runNote = -1
        var runLength = 0
        for (e in seed) {
            if (e.note == runNote) {
                runLength++
            } else {
                runNote = e.note
                runLength = 1
            }
        }

        /** Prefills AUTOREGRESS and [kept], their times from the earliest of them. */
        fun window(kept: List<AmtEvent>): FloatArray {
            origin = AmtTokenizer.minTime(kept)
            val prompt = IntArray(1 + Amt.EVENT_TOKENS * kept.size)
            prompt[0] = Amt.AUTOREGRESS
            for ((i, e) in kept.withIndex()) {
                prompt[1 + 3 * i] = Amt.TIME_OFFSET + e.time - origin
                prompt[2 + 3 * i] = Amt.DUR_OFFSET + e.duration
                prompt[3 + 3 * i] = Amt.NOTE_OFFSET + e.note
            }
            positions = prompt.size
            return model.prefill(prompt)
        }

        fun checkCancelled() {
            if (cancelled()) throw CancellationException("Composing was cancelled")
        }

        fun checkMemory() {
            if (!memoryHolds()) throw StudioFailure(ComposeFailures.RAN_OUT)
        }

        /** The larger of the budget's share spent and the music's share written. */
        fun fraction(): Float {
            val spent = if (budget == 0) 1.0 else made.toDouble() / budget
            val written = if (endTime == Int.MAX_VALUE || endTime <= start) 0.0 else (current - start).toDouble() / (endTime - start)
            return maxOf(spent, written).coerceIn(0.0, 1.0).toFloat()
        }

        fun emit(token: Int) {
            tokens[made++] = token
            if (made % PROGRESS_EVERY == 0) {
                checkMemory()
                progress(made, fraction())
            }
        }

        checkCancelled()
        var logits = window(recent(history, current, MAX_PREFILL_EVENTS))
        var needSlide = false
        var stop = Stop.Budget
        while (made < budget) {
            if (needSlide || positions + Amt.EVENT_TOKENS > Amt.CONTEXT || current - origin > MAX_REL_TIME) {
                checkCancelled()
                checkMemory()
                logits = window(recent(history, current, keepEvents))
                slides++
                needSlide = false
            }

            checkCancelled()
            val timeToken = pick(logits, candidates, timeCandidates(current - origin, settings.allowEnd), settings)
            if (timeToken == Amt.SEPARATOR) {
                stop = Stop.Separator
                break
            }
            val time = origin + timeToken - Amt.TIME_OFFSET
            if (time >= endTime) {
                stop = Stop.EndTime
                break
            }
            emit(Amt.TIME_OFFSET + time)
            if (made == budget) break
            logits = model.step(timeToken)
            positions++

            checkCancelled()
            val durationToken = pick(logits, candidates, durationCandidates(), settings)
            emit(durationToken)
            if (made == budget) break
            logits = model.step(durationToken)
            positions++

            checkCancelled()
            val banned = if (runLength >= MAX_REPEATS) Amt.NOTE_OFFSET + runNote else NONE
            val noteToken = pick(logits, candidates, noteCandidates(banned), settings)
            emit(noteToken)
            val event = AmtEvent(time, durationToken - Amt.DUR_OFFSET, noteToken - Amt.NOTE_OFFSET)
            history += event
            current = time
            if (event.note == runNote) {
                runLength++
            } else {
                runNote = event.note
                runLength = 1
            }
            if (made == budget) break
            // The window is full, or its times run out: the slide reads this note, so the step isn't needed.
            if (positions + 1 + Amt.EVENT_TOKENS > Amt.CONTEXT || current - origin > MAX_REL_TIME) {
                needSlide = true
            } else {
                logits = model.step(noteToken)
                positions++
            }
        }
        progress(made, 1f)
        return Generation(history.subList(seed.size, history.size).toList(), tokens.copyOf(made), slides, stop)
    }

    /** The last [count] events of [history] at most, none more than [KEEP_SPAN] ticks before [current] (the last always). */
    private fun recent(history: List<AmtEvent>, current: Int, count: Int): List<AmtEvent> {
        var from = maxOf(0, history.size - count)
        while (from < history.size - 1 && history[from].time < current - KEEP_SPAN) from++
        return history.subList(from, history.size)
    }

    private fun timeCandidates(earliest: Int, allowEnd: Boolean): Int {
        var n = 0
        for (t in earliest.coerceAtLeast(0) until Amt.MAX_TIME) candidates[n++] = Amt.TIME_OFFSET + t
        if (allowEnd) candidates[n++] = Amt.SEPARATOR
        return n
    }

    private fun durationCandidates(): Int {
        for (d in 0 until Amt.MAX_DUR) candidates[d] = Amt.DUR_OFFSET + d
        return Amt.MAX_DUR
    }

    private fun noteCandidates(banned: Int): Int {
        var n = 0
        for (token in Amt.PIANO_FIRST..Amt.PIANO_LAST) if (token != banned) candidates[n++] = token
        if (banned != Amt.REST) candidates[n++] = Amt.REST
        return n
    }

    /**
     * The token among the first [count] of [tokens] (in ascending order) from [logits]. Greedy: the first
     * of the highest. Otherwise each candidate weighs exp((logit − max) / temperature); top-p keeps the
     * heaviest while the weight before each is at most topP of the whole (Hugging Face's rule, which
     * the package uses: the token that crosses the line stays), and one is drawn from those in
     * proportion. Weights under 1e-12 of the heaviest are left out of the sort but not of the whole: they
     * can't be in a nucleus of top-p ≤ [NUCLEUS_MAX] (together they weigh under 1e-8).
     */
    internal fun pick(logits: FloatArray, tokens: IntArray, count: Int, settings: SamplingSettings): Int {
        var max = Double.NEGATIVE_INFINITY
        var best = NONE
        for (k in 0 until count) {
            val l = logits[tokens[k]].toDouble()
            if (l.isFinite() && l > max) {
                max = l
                best = tokens[k]
            }
        }
        check(best != NONE) { "the model gave no usable token" }
        if (settings.greedy) return best
        var total = 0.0
        for (k in 0 until count) {
            val l = logits[tokens[k]].toDouble()
            val w = if (l.isFinite()) exp((l - max) / settings.temperature) else 0.0
            weights[k] = w
            total += w
        }
        if (settings.topP > NUCLEUS_MAX) {
            var r = random.nextDouble() * total
            for (k in 0 until count) {
                r -= weights[k]
                if (r < 0.0) return tokens[k]
            }
            return best
        }
        var n = 0
        for (k in 0 until count) {
            // Positive floats sort as their bits: the weight in the high half, the candidate in the low.
            if (weights[k] >= PRUNE) order[n++] = (java.lang.Float.floatToIntBits(weights[k].toFloat()).toLong() shl 32) or k.toLong()
        }
        java.util.Arrays.sort(order, 0, n)
        val limit = settings.topP * total
        var before = 0.0
        var kept = 0
        for (j in n - 1 downTo 0) {
            if (before > limit) break
            before += weights[(order[j] and LOW).toInt()]
            kept++
        }
        var r = random.nextDouble() * before
        for (j in n - 1 downTo n - kept) {
            val k = (order[j] and LOW).toInt()
            r -= weights[k]
            if (r < 0.0) return tokens[k]
        }
        return tokens[(order[n - kept] and LOW).toInt()]
    }

    companion object {
        /** The events a slide keeps: 510 tokens, prefilled in about 130 ms on the emulator (the spike's). */
        const val KEEP_EVENTS = 170

        /** The most events a prefill may hold and still have room for one more: (1,024 − 1) / 3 − 1. */
        const val MAX_PREFILL_EVENTS = (Amt.CONTEXT - 1) / Amt.EVENT_TOKENS - 1

        /** Past this many ticks after the window's origin (90 s), the window slides before the next event. */
        const val MAX_REL_TIME = 9_000

        /** A slide keeps no event more than this many ticks (50 s) before the current time. */
        const val KEEP_SPAN = 5_000

        /** At most this many identical note tokens in a row. */
        const val MAX_REPEATS = 4

        /** Progress (and the memory check) every this many tokens. */
        const val PROGRESS_EVERY = 100

        /** Top-p above this samples from every candidate. */
        const val NUCLEUS_MAX = 0.999_999

        private const val PRUNE = 1e-12
        private const val LOW = 0xFFFF_FFFFL
        private const val NONE = -1
    }
}

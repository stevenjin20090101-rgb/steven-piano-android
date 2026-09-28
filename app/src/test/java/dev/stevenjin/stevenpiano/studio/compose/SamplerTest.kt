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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.ln
import kotlin.random.Random

/**
 * The composer's sampler (v1.7 — M24) against a scripted model: the package's masks with the note slot
 * held to the piano and REST, top-p and temperature as Hugging Face's rule has them, the repetition
 * guards (a note four times, a rest once, a key within 100 ms, eleven notes at one instant), the window's slide (the last 170 events, times re-based, never past 1,024 positions or time
 * 9,999), the stops, progress, cancellation and the memory guard.
 */
class SamplerTest {
    /**
     * A model whose logits [script] decides from the tokens it has read since its last prefill
     * (AUTOREGRESS first), counting positions as the real one does: it refuses to read past 1,024, and
     * checks every token read where a time goes is a time (0–9,999).
     */
    private class ScriptedModel(val script: (context: List<Int>) -> FloatArray) : ComposerModel {
        val context = ArrayList<Int>()
        val prefills = ArrayList<IntArray>()
        var steps = 0

        /** New events read so far (each one's duration is always read back), and that count at each prefill. */
        var events = 0
        val eventsAtPrefill = ArrayList<Int>()

        override fun prefill(tokens: IntArray): FloatArray {
            prefills += tokens.copyOf()
            eventsAtPrefill += events
            context.clear()
            tokens.forEach { read(it) }
            return script(context)
        }

        override fun step(token: Int): FloatArray {
            steps++
            if (slot(context) == 1) events++
            read(token)
            return script(context)
        }

        private fun read(token: Int) {
            context += token
            check(context.size <= Amt.CONTEXT) { "read past the context: ${context.size}" }
            if (context.size > 1 && (context.size - 2) % 3 == 0) check(Amt.isTime(token)) { "a time slot read $token" }
        }

        override fun reset() = context.clear()

        override fun close() = Unit

        companion object {
            /** The slot of the next token: 0 time, 1 duration, 2 note (AUTOREGRESS is position 0). */
            fun slot(context: List<Int>): Int = (context.size - 1) % 3

            /** The last time read (relative to the window), or 0. */
            fun lastTime(context: List<Int>): Int = if (context.size < 4) 0 else context[1 + 3 * ((context.size - 2) / 3)]
        }
    }

    private fun logits(fill: Float = Float.NEGATIVE_INFINITY) = FloatArray(Amt.VOCAB_SIZE) { fill }

    /** A model that goes on at [gap] ticks, [duration] long, a note from [notes] chosen by the context's length. */
    private fun steady(gap: Int = 25, duration: Int = 20, notes: Int = 7) = ScriptedModel { context ->
        logits().apply {
            when (ScriptedModel.slot(context)) {
                0 -> this[ScriptedModel.lastTime(context) + gap] = 0f
                1 -> this[Amt.DUR_OFFSET + duration] = 0f
                else -> this[11_050 + context.size % notes] = 0f
            }
        }
    }

    /** Ten events, times 0–360 (the current time 360), keys 60–69. */
    private val seed = List(10) { AmtEvent(it * 40, 30, 60 + it) }

    @Test
    fun `greedy keeps to the masks, times from now on, durations, piano notes or a rest`() {
        // Everything it must not have is likelier: controls, specials, other instruments, the past, a rest out of its slot.
        val model = ScriptedModel { _ ->
            logits(0f).apply {
                fill(100f, Amt.CONTROL_OFFSET, Amt.VOCAB_SIZE)
                fill(50f, Amt.PIANO_LAST + 1, Amt.REST)
                fill(60f, 0, 360)
                this[Amt.REST] = 30f
                this[11_060] = 35f
            }
        }
        val out = Sampler(model).generate(seed, SamplingSettings.Greedy, budget = 30)
        assertEquals(30, out.tokens.size)
        assertEquals(Stop.Budget, out.stop)
        assertTrue("the first time allowed: now", out.events.all { it.time == 360 })
        assertTrue("REST is no duration", out.events.all { it.duration == 0 })
        val rest = Amt.REST_NOTE
        // 60, the likeliest piano note; not again at the same instant (100 ms), so REST, the next best; not two rests in a
        // row, so the first of the other keys (all equally likely), then REST again, and so on.
        assertEquals(listOf(60, rest, 0, rest, 1, rest, 2, rest, 3, rest), out.events.map { it.note })
    }

    @Test
    fun `sampling keeps to the masks too, whatever the model wants`() {
        for (settings in listOf(SamplingSettings(1.0, 1.0), SamplingSettings(1.15, 0.98), SamplingSettings(0.8, 0.9))) {
            val model = ScriptedModel { context ->
                logits(0f).apply {
                    fill(100f, Amt.CONTROL_OFFSET, Amt.VOCAB_SIZE)
                    fill(50f, Amt.PIANO_LAST + 1, Amt.REST)
                    this[Amt.REST] = if (ScriptedModel.slot(context) == 2) 1f else 80f
                    fill(60f, 0, ScriptedModel.lastTime(context))
                }
            }
            val out = Sampler(model, Random(3)).generate(seed, settings, budget = 3_000)
            assertEquals(1_000, out.events.size)
            var current = 360
            for ((i, token) in out.tokens.withIndex()) {
                val event = out.events[i / 3]
                when (i % 3) {
                    0 -> {
                        assertTrue("time $token before $current", token >= current)
                        assertEquals(event.time, token)
                        current = token
                    }
                    1 -> assertEquals(Amt.DUR_OFFSET + event.duration, token)
                    else -> {
                        assertTrue("note $token", token in Amt.PIANO_FIRST..Amt.PIANO_LAST || token == Amt.REST)
                        assertEquals(Amt.NOTE_OFFSET + event.note, token)
                    }
                }
            }
            assertTrue(out.slides > 0)
        }
    }

    @Test
    fun `SEPARATOR ends the piece only when the settings allow it`() {
        val script: (List<Int>) -> FloatArray = { context ->
            logits(0f).apply { if (ScriptedModel.slot(context) == 0 && context.size >= 40) this[Amt.SEPARATOR] = 50f }
        }
        val kept = Sampler(ScriptedModel(script)).generate(seed, SamplingSettings.Greedy, budget = 90)
        assertEquals(Stop.Budget, kept.stop)
        assertEquals(90, kept.tokens.size)
        val ended = Sampler(ScriptedModel(script)).generate(seed, SamplingSettings(0.0, 1.0, allowEnd = true), budget = 90)
        assertEquals(Stop.Separator, ended.stop)
        assertEquals("the seed filled 31 positions; after three events the model ends it", 3, ended.events.size)
        assertEquals(9, ended.tokens.size)
    }

    @Test
    fun `top-p keeps the likeliest tokens up to the one that crosses the line, and draws in proportion`() {
        val sampler = Sampler(ScriptedModel { logits() }, Random(11))
        val tokens = intArrayOf(11_060, 11_062, 11_064, 11_065, 11_067)
        val p = doubleArrayOf(0.5, 0.25, 0.15, 0.07, 0.03)
        val l = logits().apply { for ((k, t) in tokens.withIndex()) this[t] = ln(p[k]).toFloat() + 3f }
        fun counts(settings: SamplingSettings, n: Int = 40_000): Map<Int, Int> =
            List(n) { sampler.pick(l, tokens, tokens.size, settings) }.groupingBy { it }.eachCount()

        // Top-p 0.8: before 11,064 lie 0.75 (not past 0.8: it stays, crossing the line); before 11,065, 0.90: dropped.
        val nucleus = counts(SamplingSettings(1.0, 0.8))
        assertEquals(setOf(11_060, 11_062, 11_064), nucleus.keys)
        for (k in 0..2) assertEquals(p[k] / 0.9, nucleus.getValue(tokens[k]) / 40_000.0, 0.01)
        assertEquals("top-p 0.45: the first alone", setOf(11_060), counts(SamplingSettings(1.0, 0.45), 2_000).keys)
        assertEquals("top-p 0.95: four", tokens.take(4).toSet(), counts(SamplingSettings(1.0, 0.95), 20_000).keys)
        assertEquals("top-p 1: all", tokens.toSet(), counts(SamplingSettings(1.0, 1.0)).keys)

        // Temperature 0.5 squares the odds.
        val sharp = counts(SamplingSettings(0.5, 1.0))
        val squares = p.map { it * it }
        for ((k, t) in tokens.withIndex()) assertEquals(squares[k] / squares.sum(), (sharp[t] ?: 0) / 40_000.0, 0.01)

        // Greedy takes the first of equals; NaN and infinities are never taken; nothing usable is an error.
        val tie = logits().apply {
            this[11_062] = 2f
            this[11_064] = 2f
            this[11_060] = Float.NaN
            this[11_067] = Float.POSITIVE_INFINITY
        }
        assertEquals(11_062, sampler.pick(tie, tokens, tokens.size, SamplingSettings.Greedy))
        assertEquals(setOf(11_062, 11_064), List(2_000) { sampler.pick(tie, tokens, tokens.size, SamplingSettings(1.0, 0.9)) }.toSet())
        try {
            sampler.pick(logits(), tokens, tokens.size, SamplingSettings(1.0, 0.9))
            fail("picked from nothing")
        } catch (e: IllegalStateException) {
            // the model gave no usable token
        }
    }

    @Test
    fun `no more than four identical notes in a row, counting the seed's`() {
        val model = ScriptedModel { context ->
            logits().apply {
                when (ScriptedModel.slot(context)) {
                    0 -> this[ScriptedModel.lastTime(context) + 15] = 0f   // 150 ms: clear of the same-key rule
                    1 -> this[Amt.DUR_OFFSET + 20] = 0f
                    else -> {
                        this[11_060] = 5f
                        this[11_064] = 1f
                    }
                }
            }
        }
        val threeSixties = listOf(AmtEvent(0, 20, 60), AmtEvent(10, 20, 60), AmtEvent(20, 20, 60))
        val out = Sampler(model).generate(threeSixties, SamplingSettings.Greedy, budget = 36)
        assertEquals(listOf(60, 64, 60, 60, 60, 60, 64, 60, 60, 60, 60, 64), out.events.map { it.pitch })
        assertEquals((1..12).map { 20 + 15 * it }, out.events.map { it.time })
    }

    @Test
    fun `a key waits 100 ms before it is struck again, a rest comes once at a time, and ten notes at most start together`() {
        // A model that wants to stay at the same instant, on the same few keys, and to rest.
        val model = ScriptedModel { context ->
            logits().apply {
                when (ScriptedModel.slot(context)) {
                    0 -> {
                        val t = ScriptedModel.lastTime(context)
                        this[t] = 5f
                        this[t + 3] = 4f
                        this[t + 12] = 3f
                    }
                    1 -> this[Amt.DUR_OFFSET + 30] = 0f
                    else -> {
                        fill(-5f, Amt.PIANO_FIRST, Amt.PIANO_LAST + 1)   // a real model's every note is possible
                        for (k in 0 until 16) this[11_060 + k] = 5f - k * 0.2f
                        this[Amt.REST] = 4.5f
                    }
                }
            }
        }
        val out = Sampler(model).generate(listOf(AmtEvent(0, 30, 60)), SamplingSettings.Greedy, budget = 900)
        val events = out.events
        assertEquals(300, events.size)
        val struck = HashMap<Int, Int>()
        for ((e, previous) in events.zip(listOf(AmtEvent(0, 30, 60)) + events)) {
            if (!e.isRest) {
                struck[e.pitch]?.let { assertTrue("key ${e.pitch} again after ${e.time - it} ticks", e.time - it >= Sampler.SAME_KEY_TICKS) }
                struck[e.pitch] = e.time
            }
            assertTrue("two rests in a row at ${e.time}", !(e.isRest && previous.isRest))
        }
        for ((time, together) in events.filter { !it.isRest }.groupBy { it.time }) {
            assertTrue("${together.size} notes at $time", together.size <= Sampler.MAX_CHORD)
        }
        val notes = events.count { !it.isRest }
        val instants = events.filter { !it.isRest }.map { it.time }.distinct().size
        assertTrue("$notes notes over $instants instants: the time moves on once ten have started", instants * Sampler.MAX_CHORD >= notes && instants > 10)
        assertEquals(Stop.Budget, out.stop)
    }

    @Test
    fun `the window slides before it is full, to the last 170 events with their times from the earliest`() {
        val model = steady(gap = 7, duration = 30, notes = 12)
        val seedEvents = List(10) { AmtEvent(1_000 + 7 * it, 30, 60) }
        val out = Sampler(model).generate(seedEvents, SamplingSettings.Greedy, budget = 3_000)
        assertEquals(1_000, out.events.size)
        assertEquals("times carry on across every slide", (1..1_000).map { 1_063 + 7 * it }, out.events.map { it.time })
        assertArrayEquals("the seed, from its earliest time", AmtTokenizer.prompt(AmtTokenizer.translate(seedEvents, -1_000)), model.prefills.first())
        assertTrue(out.slides >= 4)
        assertEquals(out.slides + 1, model.prefills.size)
        val all = seedEvents + out.events
        for (i in 1 until model.prefills.size) {
            // The last 170 events so far, as they were, each moved back by the first one's time.
            val end = seedEvents.size + model.eventsAtPrefill[i]
            val last = all.subList(end - Sampler.KEEP_EVENTS, end)
            assertEquals(last.map { it.copy(time = it.time - last.first().time) }, AmtTokenizer.decode(model.prefills[i]))
            assertTrue("each slide comes later", model.eventsAtPrefill[i] > model.eventsAtPrefill[i - 1])
        }
    }

    @Test
    fun `sparse music slides before its times pass 9,999, keeping the last 50 s`() {
        val model = steady(gap = 900, duration = 100, notes = 5)
        val out = Sampler(model).generate(listOf(AmtEvent(0, 100, 60)), SamplingSettings.Greedy, budget = 90)
        assertEquals(30, out.events.size)
        assertEquals((1..30).map { 900 * it }, out.events.map { it.time })
        assertTrue(out.slides >= 2)
        for (prefill in model.prefills.drop(1)) {
            val kept = AmtTokenizer.decode(prefill)
            assertTrue("kept ${kept.size} events over ${kept.last().time} ticks", kept.last().time <= Sampler.KEEP_SPAN && kept.size == 6)
        }
    }

    @Test
    fun `the budget, the end time, progress every 100 tokens, a cancel and the memory guard`() {
        val heard = ArrayList<Pair<Int, Float>>()
        val partial = Sampler(steady()).generate(seed, SamplingSettings.Greedy, budget = 250) { made, fraction -> heard += made to fraction }
        assertEquals(250, partial.tokens.size)
        assertEquals("a last partial event isn't one", 83, partial.events.size)
        assertEquals("the budget's share, then done", listOf(100 to 0.4f, 200 to 0.8f, 250 to 1f), heard)
        assertEquals(Stop.Budget, partial.stop)

        // With an end time, the music's share written counts when it is the larger: 25 ticks an event from 360 to 5,360.
        val far = ArrayList<Pair<Int, Float>>()
        val timed = Sampler(steady()).generate(seed, SamplingSettings.Greedy, budget = 3_000, endTime = 5_360) { made, fraction -> far += made to fraction }
        assertEquals(Stop.EndTime, timed.stop)
        assertEquals(listOf(100, 200, 300, 400, 500, 597), far.map { it.first })
        // At token 300 the 100th event's note is being written: the 99th event's time is the latest (2,835).
        val expected = listOf(0.165f, 0.33f, 0.495f, 0.665f, 0.83f, 1f)
        for ((got, want) in far.map { it.second }.zip(expected)) assertEquals(want, got, 1e-6f)

        val ended = Sampler(steady()).generate(seed, SamplingSettings.Greedy, budget = 3_000, endTime = 1_000)
        assertEquals(Stop.EndTime, ended.stop)
        assertEquals("nothing at or past the end", (1..25).map { 360 + 25 * it }, ended.events.map { it.time })
        assertEquals(75, ended.tokens.size)

        var asked = 0
        val model = steady()
        try {
            Sampler(model, cancelled = { ++asked > 50 }).generate(seed, SamplingSettings.Greedy, budget = 3_000)
            fail("not cancelled")
        } catch (e: CancellationException) {
            assertEquals("asked before the prefill and before each token", 51, asked)
            assertEquals("49 tokens, each read back", 49, model.steps)
        }
        var checks = 0
        try {
            Sampler(steady(), memoryHolds = { ++checks < 3 }).generate(seed, SamplingSettings.Greedy, budget = 3_000)
            fail("not stopped")
        } catch (e: StudioFailure) {
            assertEquals(ComposeFailures.RAN_OUT, e.message)
            assertEquals("at 100, 200 and 300 tokens", 3, checks)
        }
    }

    @Test
    fun `a prompt carries its seed, sampling, budget and end time`() {
        val prompt = Prompt(seed, 360, 700, 60, SamplingSettings.Greedy, Mood.Calm, MusicKey.C, 120, 0, 1.0, "Test")
        val model = steady(gap = 50)
        val out = Sampler(model).generate(prompt)
        assertArrayEquals(prompt.tokens, model.prefills.single())
        assertEquals(listOf(410, 460, 510, 560, 610, 660), out.events.map { it.time })
        assertEquals(Stop.EndTime, out.stop)
        assertEquals(18, out.tokens.size)
    }
}

// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.random.Random

/**
 * The loudness steps' choices (M83) from made-up curves: a narrow window, a wide one, noisy points, a curve that isn't
 * monotonic, a key never heard; each key's steps strictly rising, 2 to 8 of them, at least 4 dB apart on the fit.
 */
class StepPickerTest {
    private val room = -60.0

    /**
     * The strikes the calibration would make of a key whose loudness at a PWM is [curve] (null: silent, the room's level),
     * each with [noise] dB of scatter: the coarse look once each, then the finer look at its floor and its top twice.
     */
    private fun sweep(curve: (Int) -> Double?, noise: Double = 0.0, seed: Int = 1): List<StrikeLevel> {
        val random = Random(seed)
        fun heard(pwm: Int) = StrikeLevel(pwm, (curve(pwm) ?: room) + (random.nextDouble() * 2 - 1) * noise)
        val coarse = StepPicker.COARSE.map(::heard)
        val clear = StepPicker.firstClear(coarse.map { it.db }, room) ?: return coarse
        val fineFloor = StepPicker.fineFloor(clear).map(::heard)
        val knee = StepPicker.knee(coarse, clear)!!
        return coarse + fineFloor + StepPicker.fineTop(knee).map(::heard)
    }

    /** A key silent below [floor], rising [rise] dB (quickly, then levelling off) from [soft] dB up to [top], flat past it. */
    private fun curve(floor: Int, top: Int, soft: Double, rise: Double, past: Double = 0.0): (Int) -> Double? = { pwm ->
        if (pwm < floor) {
            null
        } else {
            val x = ((pwm - floor).toDouble() / (top - floor)).coerceIn(0.0, 1.0)
            soft + rise * (1 - (1 - x).pow(2)) - if (pwm > top) (pwm - top) / 1000.0 * past else 0.0
        }
    }

    /** The checks every calibrated key must pass, against its [fit]'s levels. */
    private fun assertSteps(steps: KeySteps, strikes: List<StrikeLevel>) {
        val pwms = steps.pwms!!
        assertTrue("2 to 8 steps: $pwms", pwms.size in 2..8)
        assertTrue("strictly rising: $pwms", pwms.zipWithNext().all { (a, b) -> b > a })
        assertTrue("within 1..4095: $pwms", pwms.all { it in 1..4095 })
        val fit = StepPicker.fit(strikes.filter { it.pwm >= pwms.first() })!!
        val levels = pwms.map { pwm -> level(fit, pwm) }
        assertTrue("neighbours at least 4 dB apart on the fit: $levels", levels.zipWithNext().all { (a, b) -> b - a >= StepPicker.GAP_DB - 0.05 })
    }

    /** The fit's level at [pwm], linearly between its points. */
    private fun level(fit: List<Pair<Int, Double>>, pwm: Int): Double {
        val j = fit.indexOfFirst { it.first >= pwm }
        if (j <= 0) return fit.first().second
        val (p0, l0) = fit[j - 1]
        val (p1, l1) = fit[j]
        return l0 + (l1 - l0) * (pwm - p0) / (p1 - p0)
    }

    @Test
    fun `a wide window holds the most steps, 8, from the floor to where it stops getting louder`() {
        val strikes = sweep(curve(floor = 1_000, top = 3_000, soft = -48.0, rise = 36.0))
        val steps = StepPicker.pick(60, strikes, room)
        assertSteps(steps, strikes)
        assertEquals(8, steps.pwms!!.size)
        // The floor: the softest PWM whose every strike clears the room by 10 dB (the curve clears it from 1,000).
        assertTrue("the floor near 1,000: ${steps.pwms}", steps.pwms!!.first() in 1_000..1_300)
        // The top: within 1 dB of the loudest, so before 3,000.
        assertTrue("the top before the flat: ${steps.pwms}", steps.pwms!!.last() in 2_300..3_000)
    }

    @Test
    fun `a narrow window holds two steps, and one narrower than a step's gap none`() {
        val two = sweep(curve(floor = 1_200, top = 2_200, soft = -48.0, rise = 6.0))
        val steps = StepPicker.pick(61, two, room)
        assertSteps(steps, two)
        assertEquals(2, steps.pwms!!.size)
        val narrow = StepPicker.pick(62, sweep(curve(floor = 1_200, top = 2_200, soft = -48.0, rise = 3.0)), room)
        assertNull(narrow.pwms)
        assertEquals(Unheard.NARROW, narrow.why)
    }

    @Test
    fun `noisy points still give steps strictly rising and 4 dB apart, near the noiseless ones`() {
        val shape = curve(floor = 1_100, top = 3_200, soft = -46.0, rise = 24.0)
        val clean = StepPicker.pick(64, sweep(shape), room).pwms!!
        for (seed in 1..20) {
            val strikes = sweep(shape, noise = 1.5, seed = seed)
            val steps = StepPicker.pick(64, strikes, room)
            assertSteps(steps, strikes)
            assertTrue("about as many steps: ${steps.pwms} vs $clean", kotlin.math.abs(steps.pwms!!.size - clean.size) <= 1)
        }
    }

    @Test
    fun `a curve that falls past its top is fitted rising, and its steps stop before the fall`() {
        val falling = curve(floor = 900, top = 2_600, soft = -47.0, rise = 20.0, past = 6.0)
        val strikes = sweep(falling)
        assertTrue("made up to fall", strikes.first { it.pwm == 4095 }.db < strikes.first { it.pwm == 2_520 }.db)
        val fit = StepPicker.fit(strikes)!!
        assertTrue("the fit never goes down", fit.zipWithNext().all { (a, b) -> b.second >= a.second })
        val steps = StepPicker.pick(65, strikes, room)
        assertSteps(steps, strikes)
        assertTrue("the top before the fall: ${steps.pwms}", steps.pwms!!.last() <= 2_700)
    }

    @Test
    fun `a stray loud strike below the floor doesn't make the floor`() {
        val shape = curve(floor = 1_600, top = 3_400, soft = -48.0, rise = 20.0)
        val strikes = sweep(shape) + StrikeLevel(920, -30.0)   // a cough at 920
        assertEquals("one level alone is not the first clear", 1_880, StepPicker.firstClear(StepPicker.COARSE.map { pwm -> if (pwm == 920) -30.0 else shape(pwm) ?: room }, room))
        val steps = StepPicker.pick(66, strikes, room)
        assertTrue("the floor at the curve's, not the cough's: ${steps.pwms}", steps.pwms!!.first() >= 1_600)
    }

    @Test
    fun `a key never heard is left uncalibrated, and gets no finer look`() {
        val levels = StepPicker.COARSE.map { room + 2.0 }
        assertNull(StepPicker.firstClear(levels, room))
        assertTrue(StepPicker.neverClears(levels, room))
        assertTrue("not while a level is still to come", !StepPicker.neverClears(levels.dropLast(1) + null, room))
        val steps = StepPicker.pick(30, StepPicker.COARSE.map { StrikeLevel(it, room + 2.0) }, room)
        assertNull(steps.pwms)
        assertEquals(Unheard.NOT_HEARD, steps.why)
    }

    @Test
    fun `the sweep is about 600 to 4095 in steps of about 300, then 100 apart twice each`() {
        assertEquals(12, StepPicker.COARSE.size)
        assertEquals(600, StepPicker.COARSE.first())
        assertEquals(4095, StepPicker.COARSE.last())
        assertTrue(StepPicker.COARSE.zipWithNext().all { (a, b) -> b - a in 280..330 })
        assertEquals(listOf(1_040, 1_040, 1_140, 1_140, 1_240), StepPicker.fineFloor(1_240))
        assertEquals(listOf(3_895, 3_895, 3_995, 3_995, 4_095), StepPicker.fineTop(4_095))
        assertEquals("never below 1", listOf(400, 400, 500, 500, 600), StepPicker.fineFloor(600))
        assertEquals(22, StepsCalibration.PER_KEY)
    }
}

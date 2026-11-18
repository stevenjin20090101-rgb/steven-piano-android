// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import dev.stevenjin.stevenpiano.piano.LoudnessSteps
import kotlin.math.floor
import kotlin.math.roundToInt

/** One `calstrike` heard: the PWM it struck at and how loud it was, dB (full scale 0). */
data class StrikeLevel(val pwm: Int, val db: Double)

/** One key's outcome: its [pwms] (2..8, strictly rising), or none ([why] says why) when its window holds fewer than 2 steps. */
data class KeySteps(val key: Int, val pwms: List<Int>?, val why: Unheard? = null)

/** Why a key was left uncalibrated. */
enum class Unheard {
    /** No strike cleared the room by the margin: the microphone never heard it sound. */
    NOT_HEARD,

    /** Its window, floor to top, is under one step's gap: fewer than 2 steps fit. */
    NARROW,
}

/**
 * The loudness steps' choices (M83; Steven: fixed steps, as many clearly different ones as each key's window holds, at
 * least 4 dB apart), pure:
 *
 * - **The sweep**: a coarse look ([COARSE], one strike per level, about 600 to 4095 in steps of about 300), then a finer one
 *   (steps of 100, two strikes each) below where it first clears the room ([firstClear], [fineFloor]) and below where it
 *   stops getting louder ([knee], [fineTop]).
 * - **The fit**: every strike at or above the floor, averaged per PWM, then made non-decreasing ([fit], pool-adjacent
 *   violators): noise and a curve that falls at the top never make a step go backwards.
 * - **The floor**: the softest PWM where every strike, there and above, clears the room by [MARGIN_DB].
 * - **The top**: the softest PWM whose fitted loudness is within [TOP_DB] of the key's loudest.
 * - **The steps**: N evenly spaced in dB from the floor's loudness to the top's, N the most that keeps neighbours [GAP_DB]
 *   apart, 2 to 8; each one's PWM read off the fit (linearly between its points), strictly rising.
 */
object StepPicker {
    /** The coarse look: 600 to 3,800 every 320, then full on (4095): 12 levels. */
    val COARSE: List<Int> = (0 until 11).map { 600 + it * 320 } + LoudnessSteps.PWM_MOST

    /** The finer look's step, and its strikes at each level. */
    const val FINE = 100
    const val FINE_STRIKES = 2

    /** A strike clears the room by this much (Steven's brief: a safe margin). */
    const val MARGIN_DB = 10.0

    /** The top: within this much of the key's loudest. */
    const val TOP_DB = 1.0

    /** Neighbouring steps at least this far apart (Steven's multiple-choice answer). */
    const val GAP_DB = 4.0

    /**
     * The coarse level where the key first clears the room ([room] dB) by the margin, and the next one does too (one stray
     * sound is not a floor); null while [levels] (the coarse look's, in [COARSE]'s order, null where not heard yet) can't say
     * yet, or when it never clears ([neverClears] tells the two apart).
     */
    fun firstClear(levels: List<Double?>, room: Double): Int? {
        for (i in levels.indices) {
            val level = levels[i] ?: return null
            if (level < room + MARGIN_DB) continue
            val next = levels.getOrNull(i + 1)
            if (i + 1 < levels.size && next == null) return null
            if (next == null || next >= room + MARGIN_DB) return COARSE.getOrNull(i)
        }
        return null
    }

    /** Whether [levels] (the coarse look's) is all heard and never clears: the key is not heard, and gets no finer look. */
    fun neverClears(levels: List<Double?>, room: Double): Boolean =
        levels.size == COARSE.size && levels.all { it != null } && firstClear(levels, room) == null

    /** The finer look at the floor: 200 and 100 below [clear], twice each, then [clear] a second time; all within 1..4095. */
    fun fineFloor(clear: Int): List<Int> = fineBelow(clear)

    /** The finer look at the top: the same below [knee]. */
    fun fineTop(knee: Int): List<Int> = fineBelow(knee)

    private fun fineBelow(at: Int): List<Int> = buildList {
        for (back in listOf(2 * FINE, FINE)) {
            val pwm = (at - back).coerceIn(LoudnessSteps.PWM_LEAST, LoudnessSteps.PWM_MOST)
            repeat(FINE_STRIKES) { add(pwm) }
        }
        add(at)
    }.filter { it in LoudnessSteps.PWM_LEAST..LoudnessSteps.PWM_MOST }

    /** Where the coarse look stops getting louder: the first PWM from [from] up whose fitted level is within [TOP_DB] of the loudest. */
    fun knee(strikes: List<StrikeLevel>, from: Int): Int? {
        val points = fit(strikes.filter { it.pwm >= from }) ?: return null
        val loudest = points.last().second
        return points.first { it.second >= loudest - TOP_DB }.first
    }

    /**
     * Key [key]'s steps from every strike heard of it ([strikes]), against the room's level [room] (dB): see the object's
     * notes. Uncalibrated, saying why, when no strike clears the room or fewer than 2 steps fit.
     */
    fun pick(key: Int, strikes: List<StrikeLevel>, room: Double): KeySteps {
        val floorPwm = floorOf(strikes, room) ?: return KeySteps(key, null, Unheard.NOT_HEARD)
        val points = fit(strikes.filter { it.pwm >= floorPwm }) ?: return KeySteps(key, null, Unheard.NOT_HEARD)
        val low = points.first().second
        val loudest = points.last().second
        val top = points.first { it.second >= loudest - TOP_DB }
        val window = top.second - low
        val n = minOf(LoudnessSteps.MOST, floor(window / GAP_DB).toInt() + 1)
        if (n < LoudnessSteps.LEAST) return KeySteps(key, null, Unheard.NARROW)
        val pwms = rising((0 until n).map { i -> pwmAt(points, low + window * i / (n - 1)) }) ?: return KeySteps(key, null, Unheard.NARROW)
        return KeySteps(key, pwms)
    }

    /** The softest PWM at which every strike, there and at every PWM above, clears the room by the margin; null for none. */
    private fun floorOf(strikes: List<StrikeLevel>, room: Double): Int? {
        val byPwm = strikes.groupBy { it.pwm }.toSortedMap()
        var floor: Int? = null
        for ((pwm, heard) in byPwm.entries.reversed()) {
            if (heard.all { it.db >= room + MARGIN_DB }) floor = pwm else break
        }
        return floor
    }

    /**
     * The monotonic fit of [strikes]: the mean dB at each PWM, rising PWM, pooled with its neighbours wherever it would fall
     * (weighted by strikes), so the fit never goes down. Null for no strikes.
     */
    fun fit(strikes: List<StrikeLevel>): List<Pair<Int, Double>>? {
        if (strikes.isEmpty()) return null
        val byPwm = strikes.groupBy { it.pwm }.toSortedMap()
        val pwms = byPwm.keys.toList()
        // Blocks of pooled points: their first index, weight and mean.
        val first = ArrayList<Int>()
        val weight = ArrayList<Double>()
        val mean = ArrayList<Double>()
        for ((i, heard) in byPwm.values.withIndex()) {
            first += i
            weight += heard.size.toDouble()
            mean += heard.sumOf { it.db } / heard.size
            while (mean.size > 1 && mean[mean.size - 2] > mean[mean.size - 1]) {
                val w = weight[weight.size - 2] + weight[weight.size - 1]
                val m = (mean[mean.size - 2] * weight[weight.size - 2] + mean[mean.size - 1] * weight[weight.size - 1]) / w
                first.removeAt(first.size - 1)
                weight.removeAt(weight.size - 1)
                mean.removeAt(mean.size - 1)
                weight[weight.size - 1] = w
                mean[mean.size - 1] = m
            }
        }
        val fitted = DoubleArray(pwms.size)
        for (b in first.indices) {
            val to = if (b + 1 < first.size) first[b + 1] else pwms.size
            for (i in first[b] until to) fitted[i] = mean[b]
        }
        return pwms.mapIndexed { i, pwm -> pwm to fitted[i] }
    }

    /** The PWM where the fit [points] first reaches [db], linearly between its points; its first for anything at or below it. */
    private fun pwmAt(points: List<Pair<Int, Double>>, db: Double): Double {
        val j = points.indexOfFirst { it.second >= db - EPSILON }
        if (j <= 0) return points.first().first.toDouble()
        val (p0, l0) = points[j - 1]
        val (p1, l1) = points[j]
        return p0 + (db - l0) / (l1 - l0) * (p1 - p0)
    }

    /** [pwms] rounded, kept within 1..4095 and strictly rising; null when they can't be (no room left at the top). */
    private fun rising(pwms: List<Double>): List<Int>? {
        val out = ArrayList<Int>(pwms.size)
        for (raw in pwms) {
            var pwm = raw.roundToInt().coerceIn(LoudnessSteps.PWM_LEAST, LoudnessSteps.PWM_MOST)
            if (out.isNotEmpty() && pwm <= out.last()) pwm = out.last() + 1
            if (pwm > LoudnessSteps.PWM_MOST) return null
            out += pwm
        }
        return out
    }

    private const val EPSILON = 1e-9
}

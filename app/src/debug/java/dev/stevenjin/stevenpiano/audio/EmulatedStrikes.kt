// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.pow
import kotlin.random.Random

/**
 * The emulated piano's `calstrike`s (debug builds only, M83): the emulated link says each one the console struck ([struck]),
 * and the emulator's microphone ([FakeMicrophone]) sounds it ([take]) at the loudness a made-up curve of that key's gives
 * ([loudness]), so the whole loudness steps' calibration runs on the emulator. Each key has its own window: silent below a
 * floor between 900 and 1,600, rising quickly and then levelling off up to a top between 2,000 and 4,000, a little quieter
 * past it (not monotonic, as a real plunger can be), with half a dB of scatter on every strike. Two keys are made to fail:
 * C♯1 (25) never sounds, and A♯6 (94) has a window of 3 dB, too narrow for two steps. Thread-safe.
 */
object EmulatedStrikes {
    /** One strike: the key, its PWM, and when it struck (`System.nanoTime`). */
    class Strike(val key: Int, val pwm: Int, val atNanos: Long)

    private val queue = ConcurrentLinkedQueue<Strike>()
    private val scatter = Random(SCATTER_SEED)

    /** The emulated console struck [key] at [pwm], now. */
    fun struck(key: Int, pwm: Int) {
        queue += Strike(key, pwm, System.nanoTime())
        while (queue.size > KEPT) queue.poll()
    }

    /** The strikes since the last look, oldest first. */
    fun take(): List<Strike> = buildList { while (true) add(queue.poll() ?: break) }

    /** Strikes from before the microphone started are none of its business. */
    fun clear() = queue.clear()

    /** How loud [key] sounds struck at [pwm], dB of full scale for its loudest moment; null below its floor (no sound). */
    @Synchronized
    fun loudness(key: Int, pwm: Int): Double? {
        if (key == SILENT_KEY) return null
        val shape = Random(SHAPE_SEED + key)
        val floor = 900 + 700 * shape.nextDouble()
        val top = floor + 1_100 + (4_000 - floor - 1_100).coerceAtLeast(0.0) * shape.nextDouble()
        val soft = LOWEST_DB + 4 * shape.nextDouble()
        val loud = if (key == NARROW_KEY) soft + NARROW_DB else LOUDEST_DB - 8 * shape.nextDouble()
        if (pwm < floor) return null
        val x = ((pwm - floor) / (top - floor)).coerceIn(0.0, 1.0)
        val rising = soft + (loud - soft) * (1 - (1 - x).pow(2))
        val past = if (pwm > top) (pwm - top) / 1_000.0 * PAST_TOP_DB_PER_1000 else 0.0
        return rising - past + (scatter.nextDouble() - 0.5) * SCATTER_DB * 2
    }

    private const val KEPT = 64
    private const val SHAPE_SEED = 1_000
    private const val SCATTER_SEED = 83
    private const val SILENT_KEY = 25
    private const val NARROW_KEY = 94
    private const val NARROW_DB = 3.0
    private const val LOWEST_DB = -44.0
    private const val LOUDEST_DB = -16.0
    private const val PAST_TOP_DB_PER_1000 = 0.8
    private const val SCATTER_DB = 0.5
}

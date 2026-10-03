// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.diag

import kotlin.math.roundToInt

/**
 * One minute of the System page's day (v1.18 — M46): when ([at], epoch ms), the battery's charge and temperature (tenths
 * of °C), the memory available (% of the total), Android's thermal status (0–6, [SystemReading.THERMAL_WORDS]) and the
 * piano's temperature (tenths of °C) when its facts hold one; each null when it wasn't known.
 */
data class SystemSample(
    val at: Long,
    val batteryPct: Int? = null,
    val batteryTenthsC: Int? = null,
    val memAvailPct: Int? = null,
    val thermal: Int? = null,
    val pianoTenthsC: Int? = null,
)

/**
 * The System page's day (v1.18 — M46): the last [capacity] samples (a day of one a minute), oldest first. The app's
 * scope adds one every [EVERY_MS] for as long as the process lives (`AppGraph.start`); nothing is written to disk, so a
 * restart starts an empty day. Pure, thread-safe.
 */
class SystemHistory(private val capacity: Int = CAPACITY) {
    private val ring = arrayOfNulls<SystemSample>(capacity)
    private var next = 0
    private var size = 0

    init {
        require(capacity > 0) { "A history holds at least one sample" }
    }

    /** Adds [sample] as the newest; the oldest goes once [capacity] are held. */
    @Synchronized
    fun add(sample: SystemSample) {
        ring[next] = sample
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    /** Every sample held, oldest first. */
    @Synchronized
    fun snapshot(): List<SystemSample> = List(size) { i -> ring[(next - size + i + capacity) % capacity]!! }

    companion object {
        /** A day of minutes. */
        const val CAPACITY = 1_440

        /** One sample a minute. */
        const val EVERY_MS = 60_000L

        /**
         * The piano's temperature goes into a sample only when its facts were read this recently: the app reads them only
         * while the System page is open (firmware/docs/BLE_DIAG.md), and a value kept from an hour ago is no reading now.
         */
        const val PIANO_FRESH_MS = 2 * EVERY_MS

        /**
         * The sample at [at] of [reading], with the piano's `!temp` fact ([pianoTemp], °C as the piano writes it) when it
         * was read at [factsAt] no more than [PIANO_FRESH_MS] before.
         */
        fun sampleOf(at: Long, reading: SystemReading, pianoTemp: String?, factsAt: Long?): SystemSample = SystemSample(
            at = at,
            batteryPct = reading.battery.percent,
            batteryTenthsC = tenths(reading.battery.tempC),
            memAvailPct = reading.memoryAvailablePct,
            thermal = reading.thermal.status,
            pianoTenthsC = if (factsAt != null && at - factsAt in 0..PIANO_FRESH_MS) tenths(pianoTemp?.trim()?.toDoubleOrNull()) else null,
        )

        /** [celsius] in tenths of a degree; null when unknown or not a number. */
        fun tenths(celsius: Double?): Int? = celsius?.takeIf { it.isFinite() && it in -1_000.0..1_000.0 }?.let { (it * 10).roundToInt() }
    }
}

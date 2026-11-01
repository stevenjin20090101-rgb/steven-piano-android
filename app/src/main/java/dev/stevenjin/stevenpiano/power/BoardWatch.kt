// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

/**
 * The stuck-board watch (v1.39 — M76; piano safety). A real failure is a whole board: a PCA9685 that loses I²C latches its
 * 16 outputs high, 16 coils at about 12 W, 150–190 W more than the music explains (the supply may limit it). Pure, one per
 * rail-up beside [PowerWatch]; the keeper ([RailKeeper]) reads the plug's adverts about once a second and acts. Its
 * primary partner while playing is the piano's own `!i2cfails`, read every 5 s; this is the draw's side.
 *
 * - **Idle** ([idleReading]: the rail up, nothing playing, no note for 2 s, no key held, the pedal up). The idle draw is
 *   the median of the last [IDLE_KEPT] idle readings (the rail-up's first). [BOARD_IDLE_WATTS] or more above it on two
 *   readings at least [BOARD_APART_MS] apart is a board on ([PowerVerdict.BoardOn]); [COIL_IDLE_WATTS] or more above it
 *   for [COIL_IDLE_MS] is a coil on ([PowerVerdict.CoilOn]; not a coil's 6 W for 16 s, which tripped falsely with the
 *   plug's adverts). A reading above either is never learned as idle. The keeper gives it fresh adverts only.
 * - **While playing** ([playing]): total draw never trips by itself, however high: a dense passage drawing 500 W is fine
 *   when the notes explain it. Explained = idle + the busiest second's keys ([PowerSample.heldMost]) × [PowerWatch.COIL_WATTS]
 *   (at Full power a coil draws that; in PWM drive its note's duty of it, less, so the music is never under-explained)
 *   + [PEDAL_WATTS] while the pedal is down, the busiest second taken over the [LAG_MS] before the advert (an advert can
 *   trail the switch by seconds: a passage starting or ending never looks unexplained). The unexplained part [UNEXPLAINED_WATTS] or more on every reading for
 *   [UNEXPLAINED_MS] is a board on: a stuck board is constant, dense playing isn't, and the run holds through the quietest
 *   second of that time too (each reading's own quietest second explains more, never less, so it is there as well).
 */
class BoardWatch(railUpWatts: Double?) {
    private val idles = ArrayDeque<Double>(IDLE_KEPT)

    init {
        railUpWatts?.takeIf { it.isFinite() && it >= RailKeeper.WELDED_WATTS }?.let(idles::addLast)
    }

    /** The idle draw: the median of the last [IDLE_KEPT] idle readings, W; null before one. */
    val idle: Double?
        get() {
            if (idles.isEmpty()) return null
            val sorted = idles.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }

    /** The idle watch: the first reading a board's worth above idle, and since when one is a coil's worth above it. */
    private var boardSeenAt: Long? = null
    private var coilSince: Long? = null

    /** The play watch's run: each reading's time, its unexplained part, and its quietest second's. */
    private val runAt = ArrayList<Long>()
    private val runBusy = ArrayList<Double>()
    private val runQuiet = ArrayList<Double>()
    private val runFewest = ArrayList<Double>()

    /** One idle reading of [watts] at [at] (ms). [PowerVerdict.BoardOn] or [PowerVerdict.Stuck] when it trips. */
    fun idleReading(at: Long, watts: Double, @Suppress("UNUSED_PARAMETER") fullPower: Boolean): PowerVerdict {
        clearRun()
        if (!watts.isFinite()) return PowerVerdict.Fine
        val base = idle ?: return PowerVerdict.Fine.also { learn(watts) }
        val above = watts - base
        if (above >= BOARD_IDLE_WATTS) {
            val first = boardSeenAt
            if (first == null) {
                boardSeenAt = at
            } else if (at - first >= BOARD_APART_MS) {
                return PowerVerdict.BoardOn(above, base, "idle: ${PowerWatch.watts(above)} W above the idle ${PowerWatch.watts(base)} W on two readings ${at - first} ms apart")
            }
        } else {
            boardSeenAt = null
        }
        if (above >= COIL_IDLE_WATTS) {
            val since = coilSince ?: at.also { coilSince = it }
            if (at - since >= COIL_IDLE_MS) {
                return PowerVerdict.CoilOn(above, base, at - since, "idle: ${PowerWatch.watts(above)} W above the idle ${PowerWatch.watts(base)} W for ${(at - since) / 1_000} s")
            }
            return PowerVerdict.Fine
        }
        coilSince = null
        learn(watts)
        return PowerVerdict.Fine
    }

    /** Not idle now (a note, a key held, the pedal down, or the rail held): the idle watch's runs start again. */
    fun notIdle() {
        boardSeenAt = null
        coilSince = null
    }

    /** One reading while a piece plays. [PowerVerdict.BoardOn] once more than the music explains has held for 5 s. */
    fun playing(sample: PowerSample): PowerVerdict {
        notIdle()
        val base = idle
        if (base == null || !sample.watts.isFinite()) return PowerVerdict.Fine.also { clearRun() }
        val pedal = if (sample.pedalDown) PEDAL_WATTS else 0.0
        val busy = sample.watts - base - sample.heldMost * PowerWatch.COIL_WATTS - pedal
        if (busy < UNEXPLAINED_WATTS) return PowerVerdict.Fine.also { clearRun() }
        runAt += sample.at
        runBusy += busy
        runQuiet += sample.watts - base - sample.heldLeast * PowerWatch.COIL_WATTS - pedal
        runFewest += sample.heldLeast
        val span = sample.at - runAt.first()
        if (span < UNEXPLAINED_MS) return PowerVerdict.Fine
        val quietest = runFewest.indices.minBy { runFewest[it] }
        return PowerVerdict.BoardOn(
            runBusy.min(),
            null,
            "playing: ${runAt.size} readings over ${span / 1_000.0} s, ${PowerWatch.watts(runBusy.min())}–${PowerWatch.watts(runBusy.max())} W " +
                "more than the notes explain (idle ${PowerWatch.watts(base)} W), ${PowerWatch.watts(runQuiet[quietest])} W in the quietest " +
                "second (${PowerWatch.watts(runFewest[quietest])} keys)",
        )
    }

    /** The piece paused or ended, or the rail came back: the play watch's run starts again. */
    fun pause() = clearRun()

    /** Whether [watts] sits within the idle band (it was learned, or would be): fit for the usual idle (M68). */
    fun learned(watts: Double): Boolean {
        val base = idle ?: return false
        return watts >= RailKeeper.WELDED_WATTS && watts - base < COIL_IDLE_WATTS
    }

    private fun learn(watts: Double) {
        if (watts < RailKeeper.WELDED_WATTS) return   // a supply drawing nothing isn't powered: no idle
        if (idles.size == IDLE_KEPT) idles.removeFirst()
        idles.addLast(watts)
    }

    private fun clearRun() {
        runAt.clear()
        runBusy.clear()
        runQuiet.clear()
        runFewest.clear()
    }

    companion object {
        /** The idle draw is the median of this many idle readings. */
        const val IDLE_KEPT = 20

        /** Idle: this much above idle on two readings this far apart is a board on (one board: about 150–190 W). */
        const val BOARD_IDLE_WATTS = 40.0
        const val BOARD_APART_MS = 500L

        /** Idle: this much above idle for this long is a coil on (v1.39 — M76, after a false trip at 6 W / 16 s). */
        const val COIL_IDLE_WATTS = 10.0
        const val COIL_IDLE_MS = 30_000L

        /**
         * While playing: this much more than the notes explain, for this long, is a board on. Just under one board's
         * 144 W (Steven, after two rounds; 100 W on 2026-11-01): a dense passage at Full power with every coil drawing a
         * watt more than [PowerWatch.COIL_WATTS] still stays under it with all 88 keys down.
         */
        const val UNEXPLAINED_WATTS = 100.0
        const val UNEXPLAINED_MS = 5_000L

        /** The pedal's motor while down: 10–30 W, the most taken. */
        const val PEDAL_WATTS = 30.0

        /** How often the keeper reads the plug's adverts for this watch while the rail is up. */
        const val READ_EVERY_MS = 1_000L

        /** While playing, the notes' busiest second is looked for over this long before the advert (it can trail by seconds). */
        const val LAG_MS = 3_000L
    }
}

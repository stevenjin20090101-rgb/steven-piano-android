// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import dev.stevenjin.stevenpiano.midi.KeyMap
import java.util.Locale

/** One `apower` reading as the floor watch weighs it (v1.30 — M67; [PowerWatch.sample] makes it). */
data class PowerSample(
    /** When it was asked ([RailKeeper]'s clock, ms). */
    val at: Long,
    /** The 24 V supply's draw, W. */
    val watts: Double,
    /** The most keys the router held, time-averaged over any second the reading may cover ([HeldKeys.held]). */
    val heldMost: Double,
    /** The fewest, over the same seconds. */
    val heldLeast: Double,
    /** The router's sustain pedal down at any moment the reading may cover: its motor draws 10–30 W then. */
    val pedalDown: Boolean,
    /** The piano's Full power; in PWM drive each coil draws its note's duty of [PowerWatch.COIL_WATTS], unknown here. */
    val fullPower: Boolean = true,
)

/** What the power watch makes of its readings (v1.30 — M67). */
sealed interface PowerVerdict {
    data object Fine : PowerVerdict

    /** A coil on that the music isn't holding (the floor watch), or left on after the piece: [why], for the log. */
    data class Stuck(val why: String) : PowerVerdict

    /** The supply draws [watts] at rail-up with nothing sent: a channel may be shorted. */
    data class Overdraw(val watts: Double) : PowerVerdict
}

/**
 * The power watch (v1.30 — M67; `firmware/docs/SMART_PLUG.md` › rule 11): a key stuck on (an I²C hiccup that lost a
 * release, the firmware none the wiser) seen in the 24 V supply's draw at the wall, the plug's `apower`, which owes
 * nothing to the link that failed. Pure, one per rail-up: the keeper ([RailKeeper]) reads the plug and the piano and acts.
 *
 * - **Idle** (the pedal up): the reading at rail-up, every channel verified off and nothing sent; then the lowest reading
 *   with no key held and the pedal up. Above [CAP_WATTS] at rail-up, a channel may be shorted ([railUp]).
 * - **The floor watch** ([reading]): residual = reading − (idle + the keys held × [COIL_WATTS]). Which second `apower`
 *   averaged is the plug's own (up to [LAG_MS] before it was asked), so each reading has its residual at the most and at
 *   the fewest keys held over those seconds: over [STUCK_WATTS] even at the most, it counts; at or under it even at the
 *   fewest, the run starts again; between, the music's timing leaves it undecided and it waits. [RUN_READINGS] readings
 *   counted in a row, spanning [RUN_MS], are a coil on that the music isn't holding: long legato and big chords never do it,
 *   their keys being subtracted whichever second the plug measured. A reading with the pedal down is skipped and the run
 *   starts again; so in PWM drive (Full power off), whose coils draw their notes' duty, unknown here.
 * - **After the piece** ([afterPiece]): both readings more than [threshold] above idle, `!active` above 0, or more
 *   `!i2cfails` than at the rail-up ([failsAtRailUp]).
 */
class PowerWatch(railUpWatts: Double?) {
    /** The supply's draw with no key held and the pedal up, W; null before a reading (a supply drawing none isn't powered). */
    var idle: Double? = railUpWatts?.takeIf { it.isFinite() && it >= RailKeeper.WELDED_WATTS }
        private set

    /** The piano's `!i2cfails` at this rail-up; null when it didn't say. */
    var failsAtRailUp: Long? = null

    /** The floor watch's readings weighed, and the highest residual among them at the most keys held (W): for the log. */
    var readings = 0
        private set
    var highest: Double? = null
        private set

    /** The readings counted in a row: when each was asked, and its residual at the most keys held. */
    private val runAt = ArrayList<Long>()
    private val runResidual = ArrayList<Double>()

    /** The floor watch: one reading during a piece. [PowerVerdict.Stuck] once the run is long enough. */
    fun reading(sample: PowerSample): PowerVerdict {
        if (sample.pedalDown || !sample.watts.isFinite()) return restart()
        if (sample.heldMost <= 0.0 && sample.watts >= RailKeeper.WELDED_WATTS) idle = idle?.let { minOf(it, sample.watts) } ?: sample.watts
        if (!sample.fullPower) return restart()
        val base = idle ?: return restart()
        val low = sample.watts - base - sample.heldMost * COIL_WATTS   // the residual had the plug measured the busiest second
        val high = sample.watts - base - sample.heldLeast * COIL_WATTS   // and the quietest
        readings++
        highest = maxOf(highest ?: low, low)
        if (high <= STUCK_WATTS) return restart()
        if (low <= STUCK_WATTS) return PowerVerdict.Fine   // undecided: it waits
        runAt += sample.at
        runResidual += low
        val span = sample.at - runAt.first()
        if (runAt.size < RUN_READINGS || span < RUN_MS) return PowerVerdict.Fine
        return PowerVerdict.Stuck(
            "${runAt.size} readings over ${seconds(span)} s with the pedal up, ${watts(runResidual.min())}–" +
                "${watts(runResidual.max())} W above the music (idle ${watts(base)} W, T ${watts(STUCK_WATTS)} W)",
        )
    }

    /** No piece plays: the floor watch's run starts again with the next. */
    fun pause() {
        restart()
    }

    /**
     * The check after the piece: [first] and [second] (`apower` a second apart after the stop sequence, null unread), the
     * piano's `!active` and `!i2cfails` (null unread). A key stayed on: both readings more than [threshold] above idle,
     * `!active` above 0, or more I²C failures than at the rail-up (fewer: the controller restarted).
     */
    fun afterPiece(first: Double?, second: Double?, active: Int?, fails: Long?, fullPower: Boolean): PowerVerdict {
        val t = threshold(fullPower)
        val base = idle
        val why = ArrayList<String>(3)
        if (base != null && first != null && second != null && first > base + t && second > base + t) {
            why += "${watts(first)} W and ${watts(second)} W, more than ${watts(t)} W above idle ${watts(base)} W"
        }
        if (active != null && active > 0) why += "!active=$active"
        val before = failsAtRailUp
        if (fails != null && before != null && fails > before) why += "!i2cfails $before → $fails"
        return if (why.isEmpty()) PowerVerdict.Fine else PowerVerdict.Stuck(why.joinToString("; "))
    }

    private fun restart(): PowerVerdict {
        runAt.clear()
        runResidual.clear()
        return PowerVerdict.Fine
    }

    companion object {
        /** One coil at Full power: 0.5 A at 24 V. In PWM drive about its duty times this. */
        const val COIL_WATTS = 12.0

        /**
         * T at Full power: half a coil. The bench refines it (SMART_PLUG.md › check 12: a 47 Ω / 25 W dummy load on the
         * 24 V is the 12 W step of one coil).
         */
        const val STUCK_WATTS = 6.0

        /** T in PWM drive: half a coil at its softest strike's duty (`min` 1500 of 4095: 2.2 W) is below this floor. */
        const val STUCK_FLOOR_WATTS = 3.0

        /**
         * The rail-up's sanity cap: the supply idles at 5–15 W and the pedal's motor draws a few watts up, so more than
         * this with nothing sent is a channel on (shorted, or held from before). The bench refines it.
         */
        const val CAP_WATTS = 30.0

        /** The floor watch trips on this many readings counted in a row, spanning at least [RUN_MS]. */
        const val RUN_READINGS = 8
        const val RUN_MS = 16_000L

        /**
         * `apower` is about a second's mean ([HELD_MS]), refreshed about once a second, so the second it averaged ended up
         * to this long before it was asked: the keys held are weighed over each second ending that far back, a quarter
         * second apart ([LAG_STEP_MS]).
         */
        const val LAG_MS = 1_000L
        const val LAG_STEP_MS = 250L
        const val HELD_MS = 1_000L

        /** The pedal is looked at from this long before a reading was asked: the seconds it may cover, and the motor's move. */
        const val PEDAL_MS = 2_500L

        /**
         * The floor watch looks every 1.5–2.5 s (2 s on average), stepping by the golden ratio through the spread so music
         * with a steady beat can't keep in step with it.
         */
        fun lookIn(n: Int): Long = LOOK_LEAST_MS + Math.floorMod(n.toLong() * LOOK_STRIDE, LOOK_SPREAD)

        private const val LOOK_LEAST_MS = 1_500L
        private const val LOOK_SPREAD = 1_001L
        private const val LOOK_STRIDE = 619L

        /** T, above which a draw is a coil on: [STUCK_WATTS] at Full power, [STUCK_FLOOR_WATTS] in PWM drive. */
        fun threshold(fullPower: Boolean): Double = if (fullPower) STUCK_WATTS else STUCK_FLOOR_WATTS

        /** The rail-up's reading, nothing sent yet: above [CAP_WATTS], [PowerVerdict.Overdraw]. */
        fun railUp(watts: Double?): PowerVerdict = if (watts != null && watts > CAP_WATTS) PowerVerdict.Overdraw(watts) else PowerVerdict.Fine

        /** The floor watch's sample of [watts], asked at [asked] and answered at [answered] (ms), with the router's keys and pedal. */
        fun sample(
            asked: Long,
            answered: Long,
            watts: Double,
            held: (from: Long, to: Long) -> Double,
            pedalDown: (from: Long, to: Long) -> Boolean,
            fullPower: Boolean,
        ): PowerSample {
            var most = 0.0
            var least = Double.MAX_VALUE
            var back = 0L
            while (back <= LAG_MS) {
                val keys = held(asked - back - HELD_MS, asked - back)
                most = maxOf(most, keys)
                least = minOf(least, keys)
                back += LAG_STEP_MS
            }
            return PowerSample(asked, watts, most, least, pedalDown(asked - PEDAL_MS, answered), fullPower)
        }

        /** "9.1", or "—" for none: a draw in the log. */
        fun watts(watts: Double?): String = watts?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"

        private fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.1f", ms / 1_000.0)
    }
}

/**
 * The keys the router holds, over time (v1.30 — M67): what the power watch weighs the supply's draw against. The player
 * tells it after each step that changed them ([record], the scheduler's thread); the rail's keeper reads it ([held],
 * [pedalDown], [struckSince]; the main thread). A key counts from its strike for at least [MIN_ON_MS] (the firmware's
 * shortest strike, `minstrike`) and at most the piano's longest hold: its hold watchdog lets every coil go then, whatever
 * the music holds, so a coil on for longer is the fault itself. [now] is the keeper's clock, ms. Thread-safe; nothing is
 * allocated as it goes.
 */
class HeldKeys(private val now: () -> Long) {
    /** When each of the 84 keys went down; [NONE] while it is up. */
    private val downAt = LongArray(KeyMap.KEY_COUNT) { NONE }

    /** The strokes that ended, oldest first (a ring): when each key went down, and up. */
    private val strokeFrom = LongArray(STROKES)
    private val strokeTo = LongArray(STROKES)
    private var strokeFirst = 0
    private var strokes = 0

    /** The pedal's stretches down that ended (a ring), and since when it is down now ([NONE]: up). */
    private val pedalFrom = LongArray(PEDALS)
    private val pedalTo = LongArray(PEDALS)
    private var pedalFirst = 0
    private var pedals = 0
    private var pedalSince = NONE

    private var low = 0L
    private var high = 0L
    private var lastStrike = NONE

    /** The router's keys now ([low]: keys 24–87 as bits `key − 24`; [high]: 88–107 as bits `key − 88`) and its pedal. */
    @Synchronized
    fun record(low: Long, high: Long, pedalDown: Boolean) {
        val t = now()
        flip(this.low xor low, low, 0, t)
        flip(this.high xor high, high, Long.SIZE_BITS, t)
        this.low = low
        this.high = high
        if (pedalDown && pedalSince == NONE) pedalSince = t
        if (!pedalDown && pedalSince != NONE) {
            if (pedals == PEDALS) drop(pedal = true)
            val j = (pedalFirst + pedals++) % PEDALS
            pedalFrom[j] = pedalSince
            pedalTo[j] = t
            pedalSince = NONE
        }
        while (strokes > 0 && strokeTo[strokeFirst] + MIN_ON_MS < t - KEPT_MS) drop(pedal = false)
        while (pedals > 0 && pedalTo[pedalFirst] < t - KEPT_MS) drop(pedal = true)
    }

    /** The keys held over [from]..[to], time-averaged: each from its strike, [MIN_ON_MS] at least, [longestMs] at most. */
    @Synchronized
    fun held(from: Long, to: Long, longestMs: Long): Double {
        if (to <= from) return 0.0
        var ms = 0L
        for (i in 0 until strokes) {
            val j = (strokeFirst + i) % STROKES
            val down = strokeFrom[j]
            ms += overlap(down, minOf(maxOf(strokeTo[j], down + MIN_ON_MS), down + longestMs), from, to)
        }
        for (down in downAt) if (down != NONE) ms += overlap(down, down + longestMs, from, to)
        return ms.toDouble() / (to - from)
    }

    /** Whether the pedal was down at any moment of [from]..[to]. */
    @Synchronized
    fun pedalDown(from: Long, to: Long): Boolean {
        if (pedalSince != NONE && pedalSince <= to) return true
        for (i in 0 until pedals) {
            val j = (pedalFirst + i) % PEDALS
            if (pedalFrom[j] <= to && pedalTo[j] >= from) return true
        }
        return false
    }

    /** Whether a key was struck at or after [at]. */
    @Synchronized
    fun struckSince(at: Long): Boolean = lastStrike != NONE && lastStrike >= at

    private fun flip(changed: Long, bits: Long, first: Int, t: Long) {
        var left = changed
        while (left != 0L) {
            val bit = java.lang.Long.numberOfTrailingZeros(left)
            left = left and (left - 1)
            val key = first + bit
            if (key >= downAt.size) continue
            if (bits and (1L shl bit) != 0L) {
                downAt[key] = t
                lastStrike = t
            } else if (downAt[key] != NONE) {
                if (strokes == STROKES) drop(pedal = false)
                val j = (strokeFirst + strokes++) % STROKES
                strokeFrom[j] = downAt[key]
                strokeTo[j] = t
                downAt[key] = NONE
            }
        }
    }

    /** The oldest stroke, or pedal stretch, forgotten. */
    private fun drop(pedal: Boolean) {
        if (pedal) {
            pedalFirst = (pedalFirst + 1) % PEDALS
            pedals--
        } else {
            strokeFirst = (strokeFirst + 1) % STROKES
            strokes--
        }
    }

    private fun overlap(a: Long, b: Long, from: Long, to: Long): Long = (minOf(b, to) - maxOf(a, from)).coerceAtLeast(0L)

    companion object {
        /** The firmware keeps a coil on at least this long (`minstrike`, its default). */
        const val MIN_ON_MS = 60L

        /** The longest hold the firmware allows (`hold` is clamped to it): the hold taken when the piano doesn't say. */
        const val HOLD_CEILING_MS = 4_000L

        /** What is kept: the last 10 s (the watch looks about 2.5 s back), 1024 strokes and 64 pedal stretches at most. */
        private const val KEPT_MS = 10_000L
        private const val STROKES = 1024
        private const val PEDALS = 64
        private const val NONE = Long.MIN_VALUE
    }
}

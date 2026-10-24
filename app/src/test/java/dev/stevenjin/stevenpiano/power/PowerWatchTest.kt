// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The power watch (v1.30 — M67; SMART_PLUG.md › rule 11), piano safety, on synthetic traces. A piece is played as the
 * router plays it: its keys and pedal go into [HeldKeys] as the player tells them. The 24 V supply is drawn as the plug
 * would measure it: 12 W idle (the supply, the pedal's motor up), each coil as the firmware drives it (on at least 60 ms,
 * a 22 ms cushion at 27 % after it, and off by the hold watchdog after 2 s whatever the music holds), the motor at 20 W
 * while the pedal is down and 25 W as it moves, a stuck coil's 12 W when there is one, and ±0.3 W of noise. Each reading
 * is the mean of the second that ended a lag before it was asked: 0, ½ or 1 s, since the plug's own timing is the bench's
 * to find. The floor watch reads as the keeper does ([PowerWatch.lookIn], [PowerWatch.sample]).
 */
class PowerWatchTest {
    /** A piece as the router plays it, and the supply behind it. */
    private class Trace(val ms: Long) {
        private val notes = ArrayList<LongArray>()
        val pedal = ArrayList<LongArray>()
        var stuckFrom: Long? = null

        fun note(key: Int, on: Long, off: Long) {
            notes += longArrayOf(key.toLong(), on, off)
        }

        /** One stroke per key while any note holds it: the router's reference count. */
        private fun strokes(): List<LongArray> {
            val byKey = LinkedHashMap<Long, MutableList<LongArray>>()
            for (n in notes) byKey.getOrPut(n[0]) { ArrayList() } += longArrayOf(n[1], n[2])
            val out = ArrayList<LongArray>()
            for ((key, held) in byKey) {
                held.sortWith(compareBy<LongArray>({ it[0] }, { it[1] }))
                var on = -1L
                var off = -1L
                for (h in held) {
                    if (on >= 0 && h[0] <= off) {
                        off = maxOf(off, h[1])
                    } else {
                        if (on >= 0) out += longArrayOf(key, on, off)
                        on = h[0]
                        off = h[1]
                    }
                }
                if (on >= 0) out += longArrayOf(key, on, off)
            }
            return out
        }

        /** The floor watch over the whole trace, the plug's readings [lag] ms late: its watch, and when it found a key stuck. */
        fun run(lag: Long, fullPower: Boolean = true, seed: Long = 1L): Pair<PowerWatch, Long?> {
            val noise = Random(seed)
            val end = ms + 3_000
            val n = ((end - START) / STEP_MS).toInt()
            val draw = DoubleArray(n) { IDLE }
            fun add(from: Long, to: Long, watts: Double) {
                val first = maxOf(0L, (from - START) / STEP_MS).toInt()
                val last = minOf(n.toLong(), (to - START) / STEP_MS).toInt()
                for (i in first until last) draw[i] += watts
            }
            val strokes = strokes()
            for (s in strokes) {
                val off = minOf(maxOf(s[2], s[1] + HeldKeys.MIN_ON_MS), s[1] + HOLD_MS)
                add(s[1], off, PowerWatch.COIL_WATTS)
                add(off, off + CUSHION_MS, PowerWatch.COIL_WATTS * CUSHION_DUTY)
            }
            for (p in pedal) {
                add(p[0], p[1], PEDAL_DOWN)
                add(p[0], p[0] + MOVE_MS, MOVING)
                add(p[1], p[1] + MOVE_MS, MOVING + PEDAL_DOWN)
            }
            stuckFrom?.let { add(it, end, PowerWatch.COIL_WATTS) }
            val sum = DoubleArray(n + 1)
            for (i in 0 until n) sum[i + 1] = sum[i] + draw[i]
            fun apower(asked: Long): Double {
                val to = ((asked - lag - START) / STEP_MS).toInt()
                val from = ((asked - lag - 1_000 - START) / STEP_MS).toInt()
                return (sum[to] - sum[from]) / (to - from) + NOISE * noise.nextGaussian()
            }
            // The router's events in order: a key up before one down at the same moment, the pedal last.
            val events = ArrayList<LongArray>()   // at, kind (0 up, 1 down, 2 pedal), key or pedal down (1/0)
            for (s in strokes) {
                events += longArrayOf(s[2], 0, s[0])
                events += longArrayOf(s[1], 1, s[0])
            }
            for (p in pedal) {
                events += longArrayOf(p[0], 2, 1)
                events += longArrayOf(p[1], 2, 0)
            }
            events.sortWith(compareBy<LongArray>({ it[0] }, { it[1] }))
            var clock = 0L
            val keys = HeldKeys { clock }
            var low = 0L
            var high = 0L
            var pedalDown = false
            val watch = PowerWatch(apower(0))
            var next = 0
            var asked = 0L
            var looks = 0
            var stuckAt: Long? = null
            while (true) {
                asked += PowerWatch.lookIn(looks++)
                if (asked > ms) break
                val answered = asked + RPC_MS
                while (next < events.size && events[next][0] <= answered) {
                    val e = events[next++]
                    clock = e[0]
                    when (e[1]) {
                        2L -> pedalDown = e[2] == 1L
                        else -> {
                            val key = e[2].toInt()
                            val on = e[1] == 1L
                            if (key < 88) {
                                val bit = 1L shl (key - 24)
                                low = if (on) low or bit else low and bit.inv()
                            } else {
                                val bit = 1L shl (key - 88)
                                high = if (on) high or bit else high and bit.inv()
                            }
                        }
                    }
                    keys.record(low, high, pedalDown)
                }
                clock = answered
                val sample = PowerWatch.sample(asked, answered, apower(asked), { f, t -> keys.held(f, t, HOLD_MS) }, keys::pedalDown, fullPower)
                if (watch.reading(sample) is PowerVerdict.Stuck && stuckAt == null) stuckAt = asked
            }
            return watch to stuckAt
        }
    }

    private fun scale(i: Int): Int = 48 + 12 * ((i / 7) % 2) + SCALE[i % 7]

    /** A melody walking two octaves, a note every 350 ms held 420 ms: a key is always held. */
    private fun legato(ms: Long) = Trace(ms).apply {
        var t = 1_000L
        var i = 0
        while (t < ms) {
            note(scale(i++), t, t + 420)
            t += 350
        }
    }

    /** One bass key held from 1 s to the end under a melody of 450 ms notes. */
    private fun longKey(ms: Long) = Trace(ms).apply {
        note(36, 1_000, ms)
        var t = 1_000L
        var i = 0
        while (t < ms) {
            note(60 + scale(i++) % 12, t, t + 450)
            t += 500
        }
    }

    /** Eight-key chords every [every] ms, each held [hold] ms. */
    private fun chords(every: Long, hold: Long, ms: Long) = Trace(ms).apply {
        var t = 1_000L
        var c = 0
        while (t < ms) {
            val base = 48 + (c++ % 3) * 2
            for (k in intArrayOf(0, 4, 7, 12, 16, 19, 24, 28)) note(base + k, t, t + hold)
            t += every
        }
    }

    /** Two hands of sixteenths, ten a second each, every note [each] ms long. */
    private fun fast(each: Long, ms: Long) = Trace(ms).apply {
        var t = 1_000L
        var i = 0
        while (t < ms) {
            note(scale(i) + 12, t, t + each)
            note(scale(i + 3) - 12, t, t + each)
            t += 100
            i++
        }
    }

    /** A piece made up from [seed]: chords of 3–8 keys up to 4 s long, melody notes, rests; with the pedal now and then. */
    private fun piece(seed: Long, ms: Long, pedal: Boolean) = Trace(ms).apply {
        val r = Random(seed * 31 + 7)
        var t = 1_000L
        while (t < ms) {
            val x = r.nextInt(10)
            when {
                x < 3 -> {
                    val n = 3 + r.nextInt(6)
                    val base = 36 + r.nextInt(25)
                    val hold = CHORD_MS[r.nextInt(CHORD_MS.size)]
                    repeat(n) { note(base + r.nextInt(25), t, t + hold) }
                    t += CHORD_STEP_MS[r.nextInt(CHORD_STEP_MS.size)]
                }
                x < 8 -> {
                    val hold = NOTE_MS[r.nextInt(NOTE_MS.size)]
                    note(48 + r.nextInt(49), t, t + hold)
                    t += NOTE_STEP_MS[r.nextInt(NOTE_STEP_MS.size)]
                }
                else -> t += REST_MS[r.nextInt(REST_MS.size)]
            }
        }
        if (pedal) {
            var a = 1_000L
            while (a < ms) {
                val down = 1_000L + r.nextInt(5_001)
                this.pedal += longArrayOf(a, a + down)
                a += down + 300 + r.nextInt(3_701)
            }
        }
    }

    // ---- Normal play: no trip ---------------------------------------------------------------------------------------

    @Test
    fun `normal play never trips - legato with a key always held for 30 s, 8-key chords, fast passages`() {
        val pieces = listOf(
            "legato" to { legato(30_000) },
            "a key held 30 s under a melody" to { longKey(30_000) },
            "8-key chords every second" to { chords(1_000, 900, 30_000) },
            "8-key chords every 2 s" to { chords(2_000, 1_200, 30_000) },
            "fast staccato, two hands" to { fast(50, 30_000) },
            "fast legato, two hands" to { fast(100, 30_000) },
        )
        for ((name, make) in pieces) {
            for (lag in LAGS) {
                val (watch, stuck) = make().run(lag)
                assertNull("$name, the plug $lag ms late: no trip", stuck)
                assertTrue("$name, $lag ms late: it read", watch.readings >= 12)
                assertTrue("$name, $lag ms late: under T even at the busiest second (${watch.highest} W)", watch.highest!! < PowerWatch.STUCK_WATTS)
            }
        }
    }

    @Test
    fun `thirty made-up pieces with chords, rests and the pedal never trip, whatever the plug's timing`() {
        for (seed in 1L..30L) {
            for (lag in LAGS) assertNull("piece $seed, $lag ms late", piece(seed, 120_000, pedal = seed % 3 == 0L).run(lag).second)
        }
    }

    // ---- A stuck coil trips -------------------------------------------------------------------------------------------

    @Test
    fun `a constant 12 W from 5 s in trips within 20 s, under legato and under chords`() {
        for (lag in LAGS) {
            for ((name, trace) in listOf("legato" to legato(40_000), "chords" to chords(1_000, 900, 40_000))) {
                trace.stuckFrom = 5_000
                val at = trace.run(lag).second
                assertNotNull("$name, $lag ms late: tripped", at)
                assertTrue("$name, $lag ms late: within 20 s of the coil ($at ms)", at!! - 5_000 <= 20_000)
                assertTrue("$name, $lag ms late: not before 16 s of readings", at - 5_000 >= PowerWatch.RUN_MS)
            }
        }
    }

    @Test
    fun `a stuck coil under long pedal-down stretches trips only once enough pedal-up readings exist`() {
        for (lag in LAGS) {
            val down = legato(80_000).apply {
                stuckFrom = 5_000
                pedal += longArrayOf(500, 40_000)
            }
            val at = down.run(lag).second
            assertNotNull(at)
            assertTrue("$lag ms late: 16 s of pedal-up readings after the pedal came up at 40 s ($at ms)", at!! >= 40_000 + PowerWatch.RUN_MS && at <= 40_000 + 22_000)
            val pedalling = legato(80_000).apply {
                stuckFrom = 5_000
                for (a in 500L until 72_000L step 12_000L) pedal += longArrayOf(a, a + 8_000)
            }
            assertNull("$lag ms late: 8 s down, 4 s up - never 8 pedal-up readings in a row", pedalling.run(lag).second)
        }
    }

    @Test
    fun `stuck coils are found in most made-up pieces too, and PWM drive skips the floor watch`() {
        var found = 0
        for (seed in 1L..30L) {
            val trace = piece(seed, 120_000, pedal = seed % 3 == 0L).apply { stuckFrom = 20_000 }
            if (trace.run(lag = 500).second != null) found++
        }
        assertTrue("found in $found of 30", found >= 15)
        val pwm = legato(40_000).apply { stuckFrom = 5_000 }
        assertNull("Full power off: the coils draw their notes' duty, unknown here; the check after the piece covers it", pwm.run(500, fullPower = false).second)
    }

    @Test
    fun `quiet readings refine the idle downwards only, and an unpowered reading is not one`() {
        val watch = PowerWatch(14.0)
        watch.reading(PowerSample(2_000, 12.0, heldMost = 0.0, heldLeast = 0.0, pedalDown = false))
        assertEquals(12.0, watch.idle!!, 1e-9)
        watch.reading(PowerSample(4_000, 13.0, heldMost = 0.0, heldLeast = 0.0, pedalDown = false))
        watch.reading(PowerSample(6_000, 10.0, heldMost = 0.0, heldLeast = 0.0, pedalDown = true))
        watch.reading(PowerSample(8_000, 1.0, heldMost = 0.0, heldLeast = 0.0, pedalDown = false))
        assertEquals("never up, nor from the pedal down or a supply drawing nothing", 12.0, watch.idle!!, 1e-9)
        assertNull("a rail-up reading of nothing isn't the idle", PowerWatch(0.5).idle)
    }

    @Test
    fun `a reading the plug's timing leaves undecided neither counts nor starts the run again`() {
        val watch = PowerWatch(12.0)
        var t = 0L
        fun read(watts: Double, most: Double, least: Double): PowerVerdict {
            t += 2_000
            return watch.reading(PowerSample(t, watts, most, least, pedalDown = false))
        }
        repeat(7) { assertEquals(PowerVerdict.Fine, read(24.0, 0.0, 0.0)) }
        assertEquals("undecided: 4 W to 16 W above the music", PowerVerdict.Fine, read(36.0, 2.0, 1.0))
        assertTrue("the eighth over T, 16 s after the first", read(24.0, 0.0, 0.0) is PowerVerdict.Stuck)
        val again = PowerWatch(12.0)
        t = 0L
        repeat(7) { again.reading(PowerSample(t.also { t += 2_000 }, 24.0, 0.0, 0.0, false)) }
        again.reading(PowerSample(t.also { t += 2_000 }, 24.0, 1.0, 0.5, false))   // under T even at the quietest: starts again
        repeat(7) { assertEquals(PowerVerdict.Fine, again.reading(PowerSample(t.also { t += 2_000 }, 24.0, 0.0, 0.0, false))) }
    }

    // ---- After the piece ------------------------------------------------------------------------------------------------

    @Test
    fun `after the piece - back to idle passes, 12 W up in both readings is stuck, one high then idle passes`() {
        val watch = PowerWatch(12.0).apply { failsAtRailUp = 3 }
        assertEquals(PowerVerdict.Fine, watch.afterPiece(12.4, 12.1, active = 0, fails = 3, fullPower = true))
        assertTrue(watch.afterPiece(24.2, 24.0, active = 0, fails = 3, fullPower = true) is PowerVerdict.Stuck)
        assertEquals("the music's tail in the first", PowerVerdict.Fine, watch.afterPiece(25.0, 12.3, active = 0, fails = 3, fullPower = true))
        assertEquals("the plug unread: the piano's facts decide", PowerVerdict.Fine, watch.afterPiece(null, null, active = 0, fails = 3, fullPower = true))
    }

    @Test
    fun `after the piece - a coil the piano believes on is stuck, and more I2C failures than at the rail-up`() {
        val watch = PowerWatch(12.0).apply { failsAtRailUp = 3 }
        assertTrue(watch.afterPiece(12.2, 12.1, active = 1, fails = 3, fullPower = true) is PowerVerdict.Stuck)
        assertTrue(watch.afterPiece(12.2, 12.1, active = 0, fails = 4, fullPower = true) is PowerVerdict.Stuck)
        assertEquals("fewer: the controller restarted", PowerVerdict.Fine, watch.afterPiece(12.2, 12.1, active = 0, fails = 0, fullPower = true))
        assertEquals("unread", PowerVerdict.Fine, watch.afterPiece(12.2, 12.1, active = null, fails = null, fullPower = true))
    }

    @Test
    fun `after the piece - T is 6 W at Full power, 3 W in PWM drive, above the refined idle`() {
        val watch = PowerWatch(14.0)
        assertEquals(PowerVerdict.Fine, watch.afterPiece(18.0, 18.0, null, null, fullPower = true))
        assertTrue(watch.afterPiece(18.0, 18.0, null, null, fullPower = false) is PowerVerdict.Stuck)
        watch.reading(PowerSample(2_000, 11.5, 0.0, 0.0, pedalDown = false))   // a quiet second during the piece
        assertTrue("6.5 W above the idle of 11.5 W", watch.afterPiece(18.0, 18.0, null, null, fullPower = true) is PowerVerdict.Stuck)
    }

    // ---- The rail-up cap ----------------------------------------------------------------------------------------------

    @Test
    fun `the rail-up cap - above 30 W with nothing sent is a fault`() {
        assertEquals(PowerVerdict.Overdraw(31.0), PowerWatch.railUp(31.0))
        assertEquals(PowerVerdict.Fine, PowerWatch.railUp(30.0))
        assertEquals(PowerVerdict.Fine, PowerWatch.railUp(12.0))
        assertEquals(PowerVerdict.Fine, PowerWatch.railUp(null))
    }

    // ---- The keys held ----------------------------------------------------------------------------------------------

    @Test
    fun `the keys held - each from its strike, 60 ms at least and the piano's longest hold at most, and the pedal`() {
        var clock = 0L
        val keys = HeldKeys { clock }
        keys.record(1L, 0L, false)   // key 24 down at 0
        clock = 10
        keys.record(1L or 2L, 0L, false)   // key 25 down at 10
        clock = 20
        keys.record(1L, 0L, false)   // key 25 up at 20: a 10 ms tap
        clock = 30_000
        assertEquals("the tap counts the firmware's 60 ms", 0.06, keys.held(0, 1_000, 2_000) - 1.0, 1e-9)
        assertEquals("key 24 held 30 s counts its first 2 s only", 0.0, keys.held(10_000, 11_000, 2_000), 1e-9)
        assertEquals(0.5, keys.held(1_000, 3_000, 2_000), 1e-9)
        keys.record(0L, 1L shl 19, true)   // key 24 up; key 107 down, the pedal down
        clock = 31_000
        keys.record(0L, 0L, false)
        assertTrue(keys.pedalDown(30_500, 30_600))
        assertFalse(keys.pedalDown(31_001, 32_000))
        assertTrue(keys.struckSince(30_000))
        assertFalse(keys.struckSince(30_001))
        assertEquals("key 107 held a second", 1.0, keys.held(30_000, 31_000, 2_000), 1e-9)
    }

    private companion object {
        val LAGS = longArrayOf(0, 500, 1_000)
        val SCALE = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        val CHORD_MS = longArrayOf(250, 500, 900, 1_500, 2_500, 4_000)
        val CHORD_STEP_MS = longArrayOf(250, 500, 1_000, 1_500, 2_000)
        val NOTE_MS = longArrayOf(60, 100, 200, 350, 500, 800, 1_200)
        val NOTE_STEP_MS = longArrayOf(100, 125, 250, 350, 500)
        val REST_MS = longArrayOf(250, 500, 1_000, 2_000)
        const val START = -5_000L
        const val STEP_MS = 10L
        const val IDLE = 12.0
        const val HOLD_MS = 2_000L
        const val CUSHION_MS = 22L
        const val CUSHION_DUTY = 0.27
        const val PEDAL_DOWN = 17.0
        const val MOVING = 5.0
        const val MOVE_MS = 200L
        const val NOISE = 0.3
        const val RPC_MS = 150L
    }
}

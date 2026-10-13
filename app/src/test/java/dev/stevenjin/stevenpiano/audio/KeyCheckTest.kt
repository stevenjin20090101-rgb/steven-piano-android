// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import dev.stevenjin.stevenpiano.ui.ListenCopy
import dev.stevenjin.stevenpiano.ui.screens.keys.KeyboardGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.roundToInt

/**
 * Whether each key sounded, by ear (v1.23 — M55): a sweep of the piano's 84 keys made up of synthetic notes over a room's
 * noise, as the key test strikes them (one every 450 ms after 600 ms of the room).
 */
class KeyCheckTest {
    private val keys = KeyTest.KEYS.toList()

    /** The sweep heard: every key but [dead] sounding (none with [silent]), over noise of [noise] (full scale 1). */
    private fun sweep(dead: Set<Int> = emptySet(), silent: Boolean = false, noise: Double = 0.001): Pair<ShortArray, List<Strike>> {
        val rate = Tones.RATE
        val strikes = keys.mapIndexed { i, key -> Strike(key, ((KeyTest.LEAD_MS + i * KeyTest.EVERY_MS) * rate / 1000).toInt()) }
        val room = DoubleArray(((KeyTest.LEAD_MS + keys.size * KeyTest.EVERY_MS + KeyTest.TAIL_MS) * rate / 1000).toInt())
        val random = Random(55)
        for (n in room.indices) room[n] = random.nextGaussian() * noise
        if (!silent) {
            for (strike in strikes) {
                if (strike.key in dead) continue
                val heard = (Tones.HEARD_S * rate).roundToInt()
                for (n in strike.at until minOf(room.size, strike.at + heard)) room[n] += Tones.note(strike.key, (n - strike.at).toDouble() / rate)
            }
        }
        return Tones.pcm(room) to strikes
    }

    @Test
    fun `every key sounding is heard, low C to high B`() {
        val (samples, strikes) = sweep()
        val results = KeyCheck.check(samples, strikes)
        assertEquals(keys, results.map { it.key })
        assertEquals("not heard: ${results.filterNot { it.heard }}", emptyList<KeyResult>(), results.filterNot { it.heard })
        assertEquals("All 84 keys sounded", ListenCopy.result(results))
    }

    @Test
    fun `three keys that didn't sound are those three, named`() {
        val dead = setOf(36, 66, 93)   // C2, F♯4, A6
        val (samples, strikes) = sweep(dead)
        val results = KeyCheck.check(samples, strikes)
        assertEquals(listOf(36, 66, 93), results.filterNot { it.heard }.map { it.key })
        assertEquals("3 keys didn't sound: C2, F♯4, A6", ListenCopy.result(results))
        assertEquals("1 key didn't sound: C2", ListenCopy.result(KeyCheck.check(sweep(setOf(36)).first, strikes)))
    }

    @Test
    fun `a quiet room is no key, nor is a loud one, nor digital silence`() {
        for (noise in listOf(0.001, 0.02)) {
            val (samples, strikes) = sweep(silent = true, noise = noise)
            assertTrue("noise $noise: heard ${KeyCheck.check(samples, strikes).filter { it.heard }}", KeyCheck.check(samples, strikes).none { it.heard })
        }
        val (silence, strikes) = sweep(silent = true, noise = 0.0)
        assertTrue(KeyCheck.check(silence, strikes).none { it.heard })
    }

    @Test
    fun `a steady sine reads its amplitude squared over four, whatever the window`() {
        val rate = Tones.RATE
        val sine = Tones.pcm(DoubleArray(rate) { 0.5 * kotlin.math.sin(2 * Math.PI * 440.0 * it / rate) })
        for (length in listOf(1_920, 4_800)) assertEquals(0.0625, KeyCheck.power(sine, 1_000, 1_000 + length, 440.0, rate), 0.002)
        assertEquals("A4", KeyboardGeometry.name(69))
        assertEquals("C1", KeyboardGeometry.name(24))
        assertEquals("B7", KeyboardGeometry.name(107))
        assertEquals(440.0, KeyCheck.frequency(69), 1e-9)
    }
}

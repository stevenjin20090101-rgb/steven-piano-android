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
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Whether each key sounded, by ear (v1.32 — M69: any new sound right after its strike): sweeps of the piano's 84 keys made
 * up of synthetic notes over a room's noise, as the key test strikes them (one every 450 ms after 600 ms of the room), on a
 * piano in tune and out of tune, quiet, or ringing on; and the rooms in which no key may pass for heard.
 */
class KeyCheckTest {
    /** How the made-up piano sounds: its notes' [level], each key's [cents] off, its overtones [stretched], [undamped] keys. */
    private data class Piano(
        val level: Double = 0.1,
        val cents: Map<Int, Double> = emptyMap(),
        val stretched: Boolean = false,
        val undamped: IntRange = IntRange.EMPTY,
    )

    private val keys = KeyTest.KEYS.toList()
    private val rate = Tones.RATE

    /** Where the key test strikes each key. */
    private val strikes = keys.mapIndexed { i, key -> Strike(key, ((KeyTest.LEAD_MS + i * KeyTest.EVERY_MS) * rate / 1000).toInt()) }

    /** The room through the sweep and 500 ms after: noise of [noise] (full scale 1). */
    private fun room(noise: Double): DoubleArray {
        val room = DoubleArray(((KeyTest.LEAD_MS + keys.size * KeyTest.EVERY_MS + KeyTest.TAIL_MS) * rate / 1000).toInt())
        val random = Random(55)
        for (n in room.indices) room[n] = random.nextGaussian() * noise
        return room
    }

    /**
     * [room] with every key but [dead] sounding as [piano] has it, the undamped ringing on (τ 1 s); a [held] key is down
     * from 2 s before its turn (a coil stuck on), still ringing (τ 2 s), and its strike is no new blow.
     */
    private fun play(room: DoubleArray, piano: Piano = Piano(), dead: Set<Int> = emptySet(), held: Set<Int> = emptySet()): DoubleArray {
        for (strike in strikes) {
            val key = strike.key
            if (key in dead) continue
            val start = if (key in held) strike.at - 2 * rate else strike.at
            val ring = when (key) {
                in held -> 2.0
                in piano.undamped -> 1.0
                else -> 0.0
            }
            val cents = piano.cents[key] ?: 0.0
            val stretch = if (piano.stretched) Tones.stretch(key) else 0.0
            for (n in maxOf(0, start) until minOf(room.size, start + (Tones.heardFor(ring) * rate).roundToInt())) {
                room[n] += Tones.note(key, (n - start).toDouble() / rate, piano.level, cents, stretch, ring)
            }
        }
        return room
    }

    /** The sweep heard: [play] over a room of [noise]. */
    private fun sweep(piano: Piano = Piano(), dead: Set<Int> = emptySet(), held: Set<Int> = emptySet(), noise: Double = 0.001): ShortArray =
        Tones.pcm(play(room(noise), piano, dead, held))

    private fun notHeard(samples: ShortArray): List<Int> = KeyCheck.check(samples, strikes).filterNot { it.heard }.map { it.key }

    private fun heard(samples: ShortArray): List<Int> = KeyCheck.check(samples, strikes).filter { it.heard }.map { it.key }

    @Test
    fun `every key sounding is heard, low C to high B, in tune, up to 50 cents out, or its overtones stretched`() {
        val pianos = listOf(
            "in tune" to Piano(),
            "up to 50 cents out" to Piano(cents = Tones.detune(50.0, 69)),
            "stretched" to Piano(stretched = true),
        )
        for ((name, piano) in pianos) {
            val results = KeyCheck.check(sweep(piano), strikes)
            assertEquals(keys, results.map { it.key })
            assertEquals("$name: not heard", emptyList<Int>(), results.filterNot { it.heard }.map { it.key })
            assertEquals("All 84 keys sounded", ListenCopy.result(results))
        }
        assertEquals(440.0, KeyCheck.frequency(69), 1e-9)
        assertEquals(listOf("C1", "A4", "B7"), listOf(24, 69, 107).map(KeyboardGeometry::name))
    }

    @Test
    fun `quiet notes in a noisy room are heard, out of tune`() {
        // Notes about −41 dBFS, the room's noise less than 10 dB below them, each key up to 35 cents off.
        assertEquals(emptyList<Int>(), notHeard(sweep(Piano(level = 0.01, cents = Tones.detune(35.0, 35)), noise = 0.003)))
    }

    @Test
    fun `each key is heard while the keys before it still ring, the top ones having no dampers`() {
        // From G6 each strike comes while the undamped keys before it still sound (τ 1 s): F♯6, a key before, about 4 dB down.
        assertEquals(emptyList<Int>(), notHeard(sweep(Piano(undamped = 90..107))))
        assertEquals(emptyList<Int>(), notHeard(sweep(Piano(cents = Tones.detune(20.0, 90), stretched = true, undamped = 90..107))))
    }

    @Test
    fun `three keys that didn't sound are those three, named, on a piano out of tune`() {
        val dead = setOf(36, 66, 93)   // C2, F♯4, A6
        val piano = Piano(cents = Tones.detune(50.0, 69))
        val results = KeyCheck.check(sweep(piano, dead), strikes)
        assertEquals(listOf(36, 66, 93), results.filterNot { it.heard }.map { it.key })
        assertEquals("3 keys didn't sound: C2, F♯4, A6", ListenCopy.result(results))
        assertEquals("1 key didn't sound: C2", ListenCopy.result(KeyCheck.check(sweep(piano, setOf(36)), strikes)))
        assertEquals("A6 silent among keys ringing on", listOf(36, 66, 93), notHeard(sweep(Piano(undamped = 90..107), dead)))
    }

    @Test
    fun `a key already held down is not heard, its neighbours are`() {
        // A coil stuck on: the key went down 2 s before its turn and still rings, and the test's strike is no new blow.
        // (The keys struck just after it went down, a few below it and no louder, can be lost under it: C2 and C♯2 under
        // E2's blow here, in a sweep that fails on E2 anyway.)
        val held = setOf(40, 60, 100)
        val missing = notHeard(sweep(held = held))
        assertEquals(listOf(40, 60, 100), missing.filter { key -> held.any { it - 1 <= key && key <= it + 1 } })
    }

    @Test
    fun `no key in a room's noise, quiet or loud, its hum, or digital silence`() {
        for (noise in listOf(0.001, 0.02)) assertEquals("noise $noise", emptyList<Int>(), heard(Tones.pcm(room(noise))))
        val hum = room(0.001)
        for (n in hum.indices) {
            val t = n.toDouble() / rate
            for ((h, weight) in listOf(1.0, 0.5, 0.3, 0.2).withIndex()) hum[n] += 0.05 * weight * sin(2 * PI * 60 * (h + 1) * t + h)
        }
        assertEquals("a 60 Hz hum and its harmonics", emptyList<Int>(), heard(Tones.pcm(hum)))
        assertEquals("digital silence", emptyList<Int>(), heard(Tones.pcm(room(0.0))))
    }

    @Test
    fun `audio missing before a strike can't make its key heard`() {
        // Gaps KeyTake filled with zeros before each strike (all of the 120 ms, or its first half), then the room again.
        val before = KeyCheck.BEFORE_MS * rate / 1000
        fun gapped(room: DoubleArray): ShortArray {
            for ((i, strike) in strikes.withIndex()) {
                for (n in strike.at - before until strike.at - (if (i % 2 == 0) 0 else before / 2)) room[n] = 0.0
            }
            return Tones.pcm(room)
        }
        assertEquals("the room after zeros", emptyList<Int>(), heard(gapped(room(0.003))))
        assertEquals("even a note after zeros", emptyList<Int>(), heard(gapped(play(room(0.003)))))
    }
}

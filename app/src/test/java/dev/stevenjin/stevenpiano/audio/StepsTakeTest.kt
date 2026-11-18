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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * The loudness steps' ear in each key's own bands (M83b; the firmware chat's advice): a solenoid's click alone is not a
 * strike, a key out of tune by 50 cents is heard as well as one in tune, and the room is measured per band.
 */
class StepsTakeTest {
    /** 2 s of room, then a strike of [key] answered at [REPLIED_S]: what [extra] adds at each sample (s), heard by a take. */
    private fun take(key: Int = 60, noise: Double = NOISE, extra: (Double) -> Double): StepsTake {
        val random = java.util.Random(11)
        val take = StepsTake()
        val total = (END_S * RATE).toInt()
        var index = 0L
        while (index < total) {
            val samples = DoubleArray(Microphone.FRAME) { n -> random.nextGaussian() * noise + extra((index + n).toDouble() / RATE) }
            take.add(MicFrame(Tones.pcm(samples), index, index * NANOS_PER_SECOND / RATE))
            index += Microphone.FRAME
            if (index >= (REPLIED_S + 0.01) * RATE && take.unmeasured() == 0 && take.levels().isEmpty()) take.strike(key, 2_000, nanos(REPLIED_S))
        }
        return take
    }

    private fun nanos(s: Double): Long = (s * NANOS_PER_SECOND).toLong()

    private fun level(take: StepsTake): Double = take.levels().single().db

    private fun rooms(take: StepsTake, keys: IntRange = 24..107): Map<Int, Double> = take.rooms(0, nanos(ROOM_S), keys)!!

    /** Key [key]'s note at [db] (its loudest), [cents] off its pitch, struck when its line went, 30 ms before the reply. */
    private fun note(key: Int, db: Double, cents: Double = 0.0): (Double) -> Double = { t ->
        val since = t - (REPLIED_S - 0.03)
        if (since < 0 || t >= ROOM_S + 0.9) 0.0 else Tones.note(key, since, amplitude = 10.0.pow(db / 20.0) / NOTE_RMS, cents = cents)
    }

    @Test
    fun `a solenoid's click alone is not a strike, however loud, though a broadband peak would have heard it`() {
        // A 3 ms broadband burst 25 ms after the reply, inside the window, far above the room.
        val random = java.util.Random(3)
        val clickFrom = REPLIED_S + 0.025
        val take = take { t -> if (t >= clickFrom && t < clickFrom + 0.003) (random.nextDouble() * 2 - 1) * 0.5 else 0.0 }
        val room = rooms(take).getValue(60)
        val broadbandRoom = take.room(0, nanos(ROOM_S))!!
        val broadbandClick = take.room(nanos(REPLIED_S), nanos(REPLIED_S + 0.1))!!
        assertTrue("broadband, the click is far over the room ($broadbandClick vs $broadbandRoom)", broadbandClick > broadbandRoom + 30)
        assertTrue("in key 60's bands it never clears the room by the margin (${level(take)} vs $room)", level(take) < room + StepPicker.MARGIN_DB)
        // A strike that only clicks is never a floor: its key's coarse levels stay "not heard".
        val levels = StepPicker.COARSE.map { level(take) }
        assertTrue(StepPicker.neverClears(levels, room))
    }

    @Test
    fun `a key 50 cents off its pitch is heard as one in tune is, bass to treble`() {
        for (key in listOf(24, 36, 48, 60, 72, 84, 96, 107)) {
            val tuned = take(key, extra = note(key, -50.0))
            val inTune = level(tuned)
            val room = rooms(tuned).getValue(key)
            assertTrue("key $key in tune: heard ($inTune vs room $room)", inTune >= room + StepPicker.MARGIN_DB)
            for (cents in listOf(-50.0, 50.0)) {
                val off = level(take(key, extra = note(key, -50.0, cents)))
                assertTrue("key $key, $cents cents: heard ($off vs room $room)", off >= room + StepPicker.MARGIN_DB)
                assertTrue("key $key, $cents cents: about as loud as in tune ($off vs $inTune)", abs(off - inTune) <= 3.0)
            }
        }
    }

    @Test
    fun `the room is measured in each key's bands - a hum loud in one key's is that key's alone`() {
        val hz = KeyCheck.frequency(70)
        val take = take(extra = { t -> 0.01 * sin(2 * Math.PI * hz * t) })
        val rooms = rooms(take)
        assertTrue("A♯4's room holds the hum (${rooms[70]})", rooms.getValue(70) > rooms.getValue(60) + 20)
        assertTrue("a key sharing no band is quiet (${rooms[63]})", rooms.getValue(63) < rooms.getValue(70) - 20)
        assertTrue("an octave below hears it in its 2nd harmonic's band (${rooms[58]})", rooms.getValue(58) > rooms.getValue(60) + 20)
        assertTrue("the hum is not loud broadband: the outer stop passes", take.room(0, nanos(ROOM_S))!! < StepsCalibration.ROOM_TOO_LOUD_DB)
        assertEquals(84, rooms.size)
    }

    private companion object {
        const val RATE = Tones.RATE
        const val ROOM_S = 2.0
        const val REPLIED_S = 2.2
        const val END_S = 2.5
        const val NOISE = 0.0003
        const val NANOS_PER_SECOND = 1_000_000_000L

        /** [Tones.note]'s RMS for an amplitude of 1 (six harmonics falling as 1/n). */
        const val NOTE_RMS = 0.87
    }
}

// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import dev.stevenjin.stevenpiano.ui.PowerCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Piano › Power use's meter (v1.36 — M72), pure: the advert readings integrated as trapezoids, a gap over 10 s no data,
 * the peaks with their times, the day's energy split at midnight, and the store's text read back as it was written.
 */
class EnergyMeterTest {
    private val zone = ZoneId.of("Europe/London")

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `a constant 10 W for an hour is 10 Wh, and a gap isn't counted as the last value`() {
        val meter = EnergyMeter(zone)
        val start = at("2026-11-02T10:00:00")
        for (i in 0..1_800) meter.add(start + i * 2_000L, 10.0)   // an advert every 2 s for an hour
        assertEquals(10.0, meter.totalWh, 1e-9)
        assertEquals(10.0, meter.day(start).wh, 1e-9)
        assertEquals("0.010 kWh", PowerCopy.kwh(meter.day(start).wh))

        // Unseen for 5 minutes, then 30 s more at 10 W: only the 30 s count, not the gap.
        val back = start + 3_600_000L + 300_000L
        for (i in 0..15) meter.add(back + i * 2_000L, 10.0)
        assertEquals(10.0 + 10.0 * 30 / 3_600, meter.totalWh, 1e-9)

        // A ramp: the trapezoid, 0 W to 20 W over 10 s, is 10 W for 10 s.
        val ramp = EnergyMeter(zone)
        ramp.add(start, 0.0)
        ramp.add(start + 10_000L, 20.0)
        assertEquals(10.0 * 10 / 3_600, ramp.totalWh, 1e-12)
        ramp.add(start + 10_000L + EnergyMeter.GAP_MS + 1, 20.0)
        assertEquals("just over 10 s: no data", 10.0 * 10 / 3_600, ramp.totalWh, 1e-12)
    }

    @Test
    fun `the peak - today's and since pairing, each with its time`() {
        val meter = EnergyMeter(zone)
        assertNull(meter.peak)
        meter.add(at("2026-11-02T20:00:00"), 6.4)
        meter.add(at("2026-11-02T20:00:02"), 30.5)
        meter.add(at("2026-11-02T20:00:04"), 18.0)
        meter.add(at("2026-11-03T09:00:00"), 12.0)
        meter.add(at("2026-11-03T09:00:02"), 14.2)
        assertEquals(Peak(30.5, at("2026-11-02T20:00:02")), meter.peak)
        assertEquals(Peak(14.2, at("2026-11-03T09:00:02")), meter.day(at("2026-11-03T12:00:00")).peak)
        assertEquals(Peak(30.5, at("2026-11-02T20:00:02")), meter.day(at("2026-11-02T08:00:00")).peak)
        val now = at("2026-11-03T12:00:00")
        assertEquals("14.2 W · 9:00", PowerCopy.peak(meter.day(now).peak, now, zone))
        assertEquals("30.5 W · 2 Nov 20:00", PowerCopy.peak(meter.peak, now, zone))
    }

    @Test
    fun `the day rolls over at midnight - a span across it split between the two, the month summed, two months kept`() {
        val meter = EnergyMeter(zone)
        meter.add(at("2026-11-30T23:59:56"), 12.0)
        meter.add(at("2026-12-01T00:00:04"), 12.0)   // 8 s at 12 W, half on each side
        assertEquals(12.0 * 4 / 3_600, meter.day(at("2026-11-30T12:00:00")).wh, 1e-12)
        assertEquals(12.0 * 4 / 3_600, meter.day(at("2026-12-01T12:00:00")).wh, 1e-12)
        assertEquals(12.0 * 8 / 3_600, meter.totalWh, 1e-12)
        assertEquals("November's half", 12.0 * 4 / 3_600, meter.monthWh(at("2026-11-15T12:00:00")), 1e-12)
        assertEquals("December's half", 12.0 * 4 / 3_600, meter.monthWh(at("2026-12-01T00:00:05")), 1e-12)
        assertEquals("the new day's peak, from its first reading", Peak(12.0, at("2026-12-01T00:00:04")), meter.day(at("2026-12-01T09:00:00")).peak)

        meter.add(at("2027-03-01T10:00:00"), 5.0)
        assertEquals("the last 62 days kept: November and December went", 1, meter.days().size)
        assertEquals("the total stays", 12.0 * 8 / 3_600, meter.totalWh, 1e-12)
    }

    @Test
    fun `the store's text reads back as it was, and text that doesn't read is an empty meter`() {
        val meter = EnergyMeter(zone)
        val start = at("2026-11-02T10:00:00")
        for (i in 0..100) meter.add(start + i * 2_000L, 8.0 + i % 5)
        val back = EnergyMeter.decode(meter.encode(), zone)
        assertEquals(meter.totalWh, back.totalWh, 1e-6)
        assertEquals(meter.peak, back.peak)
        assertEquals(meter.days().keys, back.days().keys)
        assertEquals(meter.day(start).wh, back.day(start).wh, 1e-6)
        assertEquals("the line isn't kept", emptyList<Reading>(), back.line(start + 200_000L))
        assertEquals(0.0, EnergyMeter.decode("garbage", zone).totalWh, 0.0)
        assertEquals(0.0, EnergyMeter.decode(null, zone).totalWh, 0.0)
    }
}

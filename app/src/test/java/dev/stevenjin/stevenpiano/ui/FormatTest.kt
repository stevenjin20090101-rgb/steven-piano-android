// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

class FormatTest {
    @Test
    fun `clock reads m-ss, with hours when there are any`() {
        assertEquals("0:00", Format.clock(0))
        assertEquals("0:59", Format.clock(59))
        assertEquals("4:31", Format.clock(271))
        assertEquals("1:02:03", Format.clock(3_723))
        assertEquals("0:00", Format.clock(-5))
        assertEquals("1:16", Format.clockMillis(76_999L))
    }

    @Test
    fun `a time of day is H-mm, or HH-mm where asked (v1_24 M57)`() {
        assertEquals("9:30", Format.timeOfDay(570))
        assertEquals("0:05", Format.timeOfDay(5))
        assertEquals("23:59", Format.timeOfDay(1_439))
        assertEquals("09:30", Format.timeOfDay(570, twoDigitHour = true))
        val at = ZonedDateTime.of(2026, 10, 5, 8, 40, 0, 0, ZoneId.of("Europe/London"))
        assertEquals("8:40", Format.timeOfDay(at))
        assertEquals("07:40", Format.timeOfDay(at.toInstant().toEpochMilli(), ZoneOffset.UTC, twoDigitHour = true))
    }

    @Test
    fun `sizes are whole megabytes, and one decimal of gigabytes from a gigabyte (v1_24 M57)`() {
        assertEquals("42 of 125 MB", Format.megabytes(42_300_000, 124_511_036, Locale.US))
        assertEquals("0 of 14 MB", Format.megabytes(0, 13_700_000, Locale.US))
        assertEquals("0.4 of 1.2 GB", Format.megabytes(420_000_000, 1_234_000_000, Locale.US))
        assertEquals("0,4 of 1,2 GB", Format.megabytes(420_000_000, 1_234_000_000, Locale.GERMANY))
        assertEquals("57 MB", Format.size(57_000_000, Locale.US))
        assertEquals("1.0 GB", Format.size(1_000_000_000, Locale.US))
    }

    @Test
    fun `counts group their digits and agree with their noun`() {
        assertEquals("1,204", Format.count(1_204, Locale.US))
        assertEquals("1 piece", Format.count(1, "piece", "pieces", Locale.US))
        assertEquals("0 pieces", Format.count(0, "piece", "pieces", Locale.US))
        assertEquals("1,727 pieces", Format.count(1_727, "piece", "pieces", Locale.US))
    }

    @Test
    fun `a playlist reads as its size and total length`() {
        assertEquals("12 pieces · 41:20", Format.piecesAndLength(12, 2_480_000L, Locale.US))
        assertEquals("1 piece · 4:31", Format.piecesAndLength(1, 271_999L, Locale.US))
        assertEquals("1,727 pieces · 81:22:05", Format.piecesAndLength(1_727, 292_925_000L, Locale.US))
        assertEquals("0 pieces", Format.piecesAndLength(0, 0L, Locale.US))
    }

    @Test
    fun `a monogram is the first letter or digit, in capitals`() {
        assertEquals("E", Format.initial("evening"))
        assertEquals("É", Format.initial("“études”"))
        assertEquals("2", Format.initial("20th century"))
        assertEquals("–", Format.initial("· · ·"))
        assertEquals("–", Format.initial(""))
    }

    @Test
    fun `semitones carry their sign, with a true minus`() {
        assertEquals("+2", Format.semitones(2))
        assertEquals("0", Format.semitones(0))
        assertEquals("−12", Format.semitones(-12))
        assertEquals("100%", Format.percent(100))
    }

    @Test
    fun `a pause reads in seconds, halves shown`() {
        assertEquals("0 s", Format.seconds(0))
        assertEquals("0.5 s", Format.seconds(500))
        assertEquals("1 s", Format.seconds(1_000))
        assertEquals("2 s", Format.seconds(2_000))
        assertEquals("2.5 s", Format.seconds(2_500))
        assertEquals("5 s", Format.seconds(5_000))
    }
}

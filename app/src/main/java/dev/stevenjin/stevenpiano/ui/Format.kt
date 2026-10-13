// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.roundToInt

/**
 * How numbers read in the app, one formatter each (v1.24 — M57 gathered the copies here). Wherever one is shown it is
 * also set in tabular figures.
 */
object Format {
    /** "0:00", "4:31", "1:02:03": a length in whole seconds. */
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0L)
        val hours = s / 3600
        val minutes = s / 60 % 60
        val secs = s % 60
        return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, secs) else "%d:%02d".format(Locale.ROOT, minutes, secs)
    }

    fun clockMillis(millis: Long): String = clock(millis / 1_000L)

    /**
     * A time of day [minute] minutes after midnight, on the 24-hour clock: "9:30", "13:05", "0:40", the hour without a
     * leading zero, as quiet times are written everywhere; with [twoDigitHour], "09:30" (the System page's chart and
     * Running now, and the web panel's quiet times on the wire).
     */
    fun timeOfDay(minute: Int, twoDigitHour: Boolean = false): String =
        String.format(Locale.ROOT, if (twoDigitHour) "%02d:%02d" else "%d:%02d", minute / MINUTES_PER_HOUR, minute % MINUTES_PER_HOUR)

    /** [at]'s time of day, as [timeOfDay] writes it. */
    fun timeOfDay(at: ZonedDateTime, twoDigitHour: Boolean = false): String = timeOfDay(at.hour * MINUTES_PER_HOUR + at.minute, twoDigitHour)

    /** The time of day of [epochMs] in [zone], as [timeOfDay] writes it. */
    fun timeOfDay(epochMs: Long, zone: ZoneId, twoDigitHour: Boolean = false): String =
        timeOfDay(Instant.ofEpochMilli(epochMs).atZone(zone), twoDigitHour)

    /**
     * A download's progress, "42 of 125 MB": whole decimal megabytes, as Android counts them; from a gigabyte on, one
     * decimal of gigabytes, "0.4 of 1.2 GB".
     */
    fun megabytes(bytes: Long, total: Long, locale: Locale = Locale.getDefault()): String =
        if (total >= GIGABYTE) "${gigabytes(bytes, locale)} of ${gigabytes(total, locale)} GB" else "${megabytesOf(bytes, locale)} of ${megabytesOf(total, locale)} MB"

    /** A download's size as [megabytes] says it: "125 MB", "1.2 GB". */
    fun size(bytes: Long, locale: Locale = Locale.getDefault()): String =
        if (bytes >= GIGABYTE) "${gigabytes(bytes, locale)} GB" else "${megabytesOf(bytes, locale)} MB"

    private fun megabytesOf(bytes: Long, locale: Locale): String = String.format(locale, "%d", (bytes.coerceAtLeast(0) / MEGABYTE).roundToInt())

    private fun gigabytes(bytes: Long, locale: Locale): String = String.format(locale, "%.1f", bytes.coerceAtLeast(0) / GIGABYTE.toDouble())

    /** "1,204", grouped the way the phone's language groups digits. */
    fun count(n: Int, locale: Locale = Locale.getDefault()): String = "%,d".format(locale, n)

    /** "1 piece", "1,204 pieces". */
    fun count(n: Int, one: String, many: String, locale: Locale = Locale.getDefault()): String =
        "${count(n, locale)} ${if (n == 1) one else many}"

    /** A playlist's size and length together: "12 pieces · 41:20"; an empty one is just "0 pieces". */
    fun piecesAndLength(count: Int, totalMillis: Long, locale: Locale = Locale.getDefault()): String {
        val pieces = count(count, "piece", "pieces", locale)
        return if (count == 0) pieces else "$pieces · ${clockMillis(totalMillis)}"
    }

    /** The letter a monogram tile shows for [name]: its first letter or digit, in capitals; a dash when it has none. */
    fun initial(name: String): String = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "–"

    fun percent(pct: Int): String = "$pct%"

    /** A short span of milliseconds in seconds, halves shown: "0 s", "0.5 s", "1 s", "2.5 s". */
    fun seconds(millis: Int): String {
        val tenths = (millis.coerceAtLeast(0) + 50) / 100
        return if (tenths % 10 == 0) "${tenths / 10} s" else "${tenths / 10}.${tenths % 10} s"
    }

    /** Semitones with their sign: "+2", "0", "−3" (a true minus sign). */
    fun semitones(n: Int): String = when {
        n > 0 -> "+$n"
        n < 0 -> "−${-n}"
        else -> "0"
    }

    private const val MINUTES_PER_HOUR = 60
    private const val MEGABYTE = 1_000_000.0
    private const val GIGABYTE = 1_000_000_000L
}

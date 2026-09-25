// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import java.util.Locale

/** How numbers read in the app. Wherever one is shown it is also set in tabular figures. */
object Format {
    /** "0:00", "4:31", "1:02:03". */
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0L)
        val hours = s / 3600
        val minutes = s / 60 % 60
        val secs = s % 60
        return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, secs) else "%d:%02d".format(Locale.ROOT, minutes, secs)
    }

    fun clockMicros(micros: Long): String = clock(micros / 1_000_000L)

    fun clockMillis(millis: Long): String = clock(millis / 1_000L)

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

    /** Semitones with their sign: "+2", "0", "−3" (a true minus sign). */
    fun semitones(n: Int): String = when {
        n > 0 -> "+$n"
        n < 0 -> "−${-n}"
        else -> "0"
    }
}

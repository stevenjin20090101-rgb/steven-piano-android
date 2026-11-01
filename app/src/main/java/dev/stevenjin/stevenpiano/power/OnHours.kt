// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import java.time.ZonedDateTime
import java.util.Locale

/**
 * Piano › Power plug › **On hours** (v1.39 — M76; Steven: "off hours is 10:00–7:00 but can bypass if a person is where the
 * tablet is"): from [start] to [end] (minutes of the day, every day) the rail is wanted all along ([RailKeeper.HOURS]), so
 * the piano stays on and connected and Play starts at once; outside them the rail is off, a person at the tablet still
 * wakes it, and the panel, the relay and the guests can't. [on] false: no hours at all, the plug as in 1.38. [end] before
 * [start] runs past midnight; the two equal is the whole day. Kept with the plug ([PlugStore]); 7:00–22:00 by default.
 */
data class OnHours(val on: Boolean = true, val start: Int = DEFAULT_START, val end: Int = DEFAULT_END) {
    /** Whether [minute] of the day is within the hours, the setting aside. */
    fun inside(minute: Int): Boolean = when {
        start == end -> true
        start < end -> minute in start until end
        else -> minute >= start || minute < end
    }

    /** Whether [at] is within the hours, the setting aside. */
    fun inside(at: ZonedDateTime): Boolean = inside(minuteOf(at))

    /** The standing want at [at]: the hours on, and [at] within them. */
    fun wanted(at: ZonedDateTime): Boolean = on && inside(at)

    /** Outside the hours at [at], with the hours on: only a person at the tablet wakes the piano. */
    fun outside(at: ZonedDateTime): Boolean = on && !inside(at)

    /**
     * Whether a start from afar (the web panel, the relay) is refused at [at]: outside the hours with the piano [resting]
     * (the rail off). Awake (a person at the tablet woke it), or within the hours, it may go.
     */
    fun refusesRemote(at: ZonedDateTime, resting: Boolean): Boolean = outside(at) && resting

    /** "7:00": when the hours start again. */
    val startText: String get() = time(start)

    companion object {
        const val DEFAULT_START = 7 * 60
        const val DEFAULT_END = 22 * 60
        const val MINUTES_PER_DAY = 24 * 60

        fun minuteOf(at: ZonedDateTime): Int = at.hour * 60 + at.minute

        /** A minute of the day as "7:00", "22:30". */
        fun time(minute: Int): String = String.format(Locale.ROOT, "%d:%02d", minute / 60, minute % 60)
    }
}

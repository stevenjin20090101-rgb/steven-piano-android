// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import java.util.TreeMap

/** The supply's draw at a moment: [at] (the wall clock, ms) and [watts]. */
data class Reading(val at: Long, val watts: Double)

/** A peak: [watts], and when ([at], the wall clock, ms). */
data class Peak(val watts: Double, val at: Long)

/** One day's use: its energy ([wh], watt-hours) and its [peak] (null before a reading). */
data class DayUse(val wh: Double = 0.0, val peak: Peak? = null)

/**
 * The piano's power use (v1.36 — M72, Piano › Power use), pure: the plug's advert readings ([add]) integrated over time, a
 * trapezoid between each two (split at midnight, [zone]'s), with a gap over [GAP_MS] counted as no data, not as the last
 * value. Kept: each day's energy and peak for the last [DAYS_KEPT] days, the peak and the energy since pairing, and the last
 * [LINE_MS] of readings for the line (not kept across a restart). [encode] and [decode] are the plug store's text.
 */
class EnergyMeter(private val zone: ZoneId) {
    /** Each day's use, by its epoch day. */
    private val days = TreeMap<Long, DayUse>()

    /** The energy since pairing, Wh. */
    var totalWh: Double = 0.0
        private set

    /** The highest reading since pairing; null before one. */
    var peak: Peak? = null
        private set

    private var last: Reading? = null
    private val line = ArrayDeque<Reading>()

    /** A reading of [watts] at [at] (the wall clock, ms). A reading that doesn't read, or comes before the last, starts again. */
    fun add(at: Long, watts: Double) {
        if (!watts.isFinite() || watts < 0.0) return
        val before = last
        val now = Reading(at, watts)
        last = now
        if (before != null && at <= before.at) return   // the clock went back: no span to count
        change(epochDay(at)) { it.copy(peak = higher(it.peak, now)) }
        peak = higher(peak, now)
        if (line.isEmpty() || at - line.last().at >= LINE_STEP_MS) line.addLast(now)
        while (line.isNotEmpty() && line.first().at < at - LINE_MS) line.removeFirst()
        if (before != null && at - before.at <= GAP_MS) integrate(before, now)
        prune(epochDay(at))
    }

    /** The day of [at]'s use (none: empty). */
    fun day(at: Long): DayUse = days[epochDay(at)] ?: DayUse()

    /** The energy of [at]'s month so far, Wh. */
    fun monthWh(at: Long): Double {
        val month = YearMonth.from(date(at))
        val first = month.atDay(1).toEpochDay()
        val end = month.plusMonths(1).atDay(1).toEpochDay()
        return days.subMap(first, end).values.sumOf { it.wh }
    }

    /** The readings of the last [LINE_MS] before [at], oldest first, one a second at most. */
    fun line(at: Long): List<Reading> = line.filter { it.at >= at - LINE_MS && it.at <= at }

    /** The days kept, by epoch day (for the store and tests). */
    fun days(): Map<Long, DayUse> = days.toMap()

    /** The text the plug's store keeps: "1|total|peak|day:wh:peak;…", a peak as "W@ms" or "-". */
    fun encode(): String = buildString {
        append(VERSION).append('|').append(number(totalWh)).append('|').append(peakText(peak)).append('|')
        days.entries.joinTo(this, ";") { (day, use) -> "$day:${number(use.wh)}:${peakText(use.peak)}" }
    }

    /** The span [a]..[b] (at most [GAP_MS]) into the days it covers, the draw linear between them. */
    private fun integrate(a: Reading, b: Reading) {
        var from = a
        while (true) {
            val day = epochDay(from.at)
            val midnight = LocalDate.ofEpochDay(day + 1).atStartOfDay(zone).toInstant().toEpochMilli()
            if (b.at <= midnight) {
                count(day, from, b)
                return
            }
            val share = (midnight - from.at).toDouble() / (b.at - from.at)
            val split = Reading(midnight, from.watts + (b.watts - from.watts) * share)
            count(day, from, split)
            from = split
        }
    }

    /** The trapezoid [a]..[b], within [day]. */
    private fun count(day: Long, a: Reading, b: Reading) {
        val wh = (a.watts + b.watts) / 2.0 * (b.at - a.at) / MS_PER_HOUR
        totalWh += wh
        change(day) { it.copy(wh = it.wh + wh) }
    }

    private inline fun change(day: Long, how: (DayUse) -> DayUse) {
        days[day] = how(days[day] ?: DayUse())
    }

    private fun prune(today: Long) {
        while (days.isNotEmpty() && days.firstKey() <= today - DAYS_KEPT) days.remove(days.firstKey())
    }

    private fun date(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    private fun epochDay(at: Long): Long = date(at).toEpochDay()

    companion object {
        /** A gap longer than this between readings is no data (the plug unseen), not its last value held. */
        const val GAP_MS = 10_000L

        /** The days kept (about two months). */
        const val DAYS_KEPT = 62

        /** The line: the last 10 minutes, a point a second at most. */
        const val LINE_MS = 10 * 60_000L
        const val LINE_STEP_MS = 1_000L

        private const val VERSION = 1
        private const val MS_PER_HOUR = 3_600_000.0

        private fun higher(peak: Peak?, reading: Reading): Peak = if (peak == null || reading.watts > peak.watts) Peak(reading.watts, reading.at) else peak

        private fun number(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

        private fun peakText(peak: Peak?): String = peak?.let { "${number(it.watts)}@${it.at}" } ?: "-"

        private fun peakOf(text: String): Peak? {
            if (text == "-") return null
            val watts = text.substringBefore('@').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
            val at = text.substringAfter('@', "").toLongOrNull() ?: return null
            return Peak(watts, at)
        }

        /** The meter the store's [text] kept, in [zone]; an empty one when it doesn't read. */
        fun decode(text: String?, zone: ZoneId): EnergyMeter {
            val meter = EnergyMeter(zone)
            val parts = text?.split('|') ?: return meter
            if (parts.size != 4 || parts[0] != VERSION.toString()) return meter
            meter.totalWh = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: return EnergyMeter(zone)
            meter.peak = peakOf(parts[2])
            for (entry in parts[3].split(';')) {
                if (entry.isBlank()) continue
                val fields = entry.split(':')
                if (fields.size != 3) continue
                val day = fields[0].toLongOrNull() ?: continue
                val wh = fields[1].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: continue
                meter.days[day] = DayUse(wh, peakOf(fields[2]))
            }
            if (meter.days.isNotEmpty()) meter.prune(meter.days.lastKey())
            return meter
        }
    }
}

/** What Piano › Power use shows of the meter (v1.36 — M72): a copy, read on the main thread. */
data class PowerUseNow(
    val days: Map<Long, DayUse> = emptyMap(),
    val monthWh: Double = 0.0,
    val todayWh: Double = 0.0,
    val todayPeak: Peak? = null,
    val totalWh: Double = 0.0,
    val peak: Peak? = null,
    val line: List<Reading> = emptyList(),
)

/**
 * Piano › Power use's meter as the app keeps it (v1.36 — M72): each advert's draw ([reading]) into the [EnergyMeter] of
 * the plug [load] named, its text to the plug's store ([keep]) at most once every [SAVE_EVERY_MS], and at once on a CUT and
 * as the app stops ([save]). [clear] for a plug forgotten, a new pairing or the migration. [wall] is the wall clock (ms),
 * [zone] the tablet's time zone. Any thread.
 */
class PowerUse(
    private val scope: CoroutineScope,
    private val wall: () -> Long,
    private val zone: () -> ZoneId,
    /** The meter's text to the store, for the plug at the address: kept only while that plug is still the paired one. */
    private val keep: suspend (String, String) -> Unit,
) {
    private var meter = EnergyMeter(zone())
    private var owner: String? = null
    private var savedAt: Long? = null
    private var shownAt: Long? = null
    private var dirty = false

    private val _state = MutableStateFlow(PowerUseNow())
    val state: StateFlow<PowerUseNow> = _state.asStateFlow()

    /** The meter of the plug at [address], from what the store kept ([text]; null: none); null [address]: no plug. */
    @Synchronized
    fun load(address: String?, text: String?) {
        owner = address
        meter = if (address == null) EnergyMeter(zone()) else EnergyMeter.decode(text, zone())
        dirty = false
        savedAt = null
        publish(wall())
    }

    /** A reading of the paired plug's adverts: integrated, shown about once a second, kept at most once a minute. */
    @Synchronized
    fun reading(watts: Double) {
        if (owner == null) return
        val t = wall()
        meter.add(t, watts)
        dirty = true
        if (shownAt?.let { t - it < SHOW_EVERY_MS } != true) publish(t)
        if (savedAt?.let { t - it < SAVE_EVERY_MS } != true) save()
    }

    /** Kept now, if anything changed since (a CUT, the app stopping). */
    @Synchronized
    fun save() {
        val address = owner ?: return
        if (!dirty) return
        dirty = false
        savedAt = wall()
        val text = meter.encode()
        scope.launch { keep(address, text) }
    }

    /** No plug, or a new one: everything starts from nothing (the store's is cleared by its own forget, pair or migration). */
    @Synchronized
    fun clear(address: String? = null) {
        load(address, null)
    }

    @Synchronized
    private fun publish(t: Long) {
        shownAt = t
        val today = meter.day(t)
        _state.value = PowerUseNow(
            days = meter.days(),
            monthWh = meter.monthWh(t),
            todayWh = today.wh,
            todayPeak = today.peak,
            totalWh = meter.totalWh,
            peak = meter.peak,
            line = meter.line(t),
        )
    }

    companion object {
        const val SAVE_EVERY_MS = 60_000L
        const val SHOW_EVERY_MS = 1_000L
    }
}

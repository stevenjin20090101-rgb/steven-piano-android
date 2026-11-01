// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * The paired plug (v1.26 — M60; the SwitchBot Plug Mini since v1.36 — M72): its Bluetooth [address] (what the tablet
 * connects to) and the [mac] its adverts carry (what the advert watch looks for; null: as its address).
 */
data class PlugPairing(val address: String, val mac: String? = null)

/** One rail-up's idle reading (v1.31 — M68): when ([at], the wall clock, ms), and the 24 V supply's draw with nothing sent. */
data class IdleReading(val at: Long, val watts: Double)

/**
 * The plug's own settings: the [pairing] (null: none), its switch ([on]: the plug switches the rail; on from the end of
 * pairing until a person turns it off), and guest play this tablet started, [guestUntil] (the wall clock, ms), kept so a
 * restart of the app keeps arming for it. The power watch's usual idle (v1.31 — M68): the last rail-up readings that didn't
 * trip ([idles], oldest first), and whether a rail-up tripped above them since ([aboveUsual]); a pairing starts them empty.
 * [shellyCleared] (v1.36 — M72): a Shelly plug's pairing was cleared by the update, until a SwitchBot is paired.
 */
data class PlugSettings(
    val pairing: PlugPairing? = null,
    val on: Boolean = false,
    val guestUntil: Long? = null,
    val idles: List<IdleReading> = emptyList(),
    val aboveUsual: Boolean = false,
    val shellyCleared: Boolean = false,
    /** v1.39 — M76: the On hours (7:00–22:00 every day, on, until changed); kept through a new pairing or Forget. */
    val hours: OnHours = OnHours(),
) {
    /** The plug switches the rail: paired, and its switch on. */
    val switches: Boolean get() = pairing != null && on
}

/** The plug's own small store ("power_plug"), apart from the app's settings, which another run shares. */
private val Context.plugDataStore: DataStore<Preferences> by preferencesDataStore(name = "power_plug")

/** Where the Pair screen keeps what it paired with ([PlugStore]; a fake in tests). */
interface PairingStore {
    /** Paired with [pairing]; its switch [on]. */
    suspend fun pair(pairing: PlugPairing, on: Boolean = true)

    suspend fun setOn(on: Boolean)
}

/**
 * [PlugSettings] in the "power_plug" DataStore. A store that can't be read reads as no plug: the app as before it. A
 * pairing kept before 1.36 is a Shelly plug's (v1.36 — M72): it reads as none, [shellyCleared], until [migrate] clears it.
 */
class PlugStore(private val store: DataStore<Preferences>) : PairingStore, CoolStore {
    constructor(context: Context) : this(context.applicationContext.plugDataStore)

    val settings: Flow<PlugSettings> = store.data
        .catch { e -> if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e }
        .map { prefs ->
            val address = prefs[ADDRESS]?.takeIf { it.isNotBlank() }
            val hours = hoursOf(prefs)
            when {
                address != null && prefs[KIND] != SWITCHBOT -> PlugSettings(shellyCleared = true, hours = hours)   // a Shelly's, not yet cleared
                address == null -> PlugSettings(shellyCleared = prefs[SHELLY_CLEARED] == true, hours = hours)
                else -> PlugSettings(
                    pairing = PlugPairing(address, prefs[MAC]?.takeIf { it.isNotBlank() }),
                    on = prefs[ON] ?: false,
                    guestUntil = prefs[GUEST_UNTIL],
                    idles = idlesOf(prefs[IDLES]),
                    aboveUsual = prefs[ABOVE_USUAL] ?: false,
                    hours = hours,
                )
            }
        }

    /**
     * The update to the SwitchBot (v1.36 — M72), at the first read: a Shelly plug's pairing kept before 1.36 (its address
     * without the SwitchBot's mark: its id, model, firmware, bond, Matter, switch and guest play) is cleared, and with it
     * the usual idle and the power use (a different plug idles differently); Piano › Power plug says to pair the SwitchBot.
     * True when it cleared one (for the log, once); a second run finds nothing and changes nothing.
     */
    suspend fun migrate(): Boolean {
        var cleared = false
        tryEdit { prefs ->
            if (prefs[ADDRESS].isNullOrBlank() || prefs[KIND] == SWITCHBOT) return@tryEdit
            prefs.clear()
            prefs[SHELLY_CLEARED] = true
            cleared = true
        }
        return cleared
    }

    /** Paired with [pairing]; its switch [on]. The usual idle and the power use start empty (M68, M72). */
    override suspend fun pair(pairing: PlugPairing, on: Boolean) {
        store.edit { prefs ->
            clearKeepingHours(prefs)
            prefs[ADDRESS] = pairing.address
            prefs[KIND] = SWITCHBOT
            pairing.mac?.let { prefs[MAC] = it }
            prefs[ON] = on
        }
    }

    /** The power use's meter ([EnergyMeter.encode]) of the plug at [address]; null with none kept, or another plug paired. */
    suspend fun energy(address: String): String? = try {
        store.data.first().let { prefs -> prefs[ENERGY]?.takeIf { prefs[ADDRESS] == address && prefs[KIND] == SWITCHBOT } }
    } catch (e: IOException) {
        null
    }

    /** The power use's meter of the plug at [address] kept (v1.36 — M72): only while that plug is still the paired one. */
    suspend fun keepEnergy(address: String, text: String) {
        tryEdit { prefs -> if (prefs[ADDRESS] == address && prefs[KIND] == SWITCHBOT) prefs[ENERGY] = text }
    }

    override suspend fun setOn(on: Boolean) {
        store.edit { it[ON] = on }
    }

    suspend fun setGuestUntil(wallMs: Long?) {
        store.edit { prefs -> if (wallMs == null) prefs.remove(GUEST_UNTIL) else prefs[GUEST_UNTIL] = wallMs }
    }

    /** Forget the plug: everything goes, its usual idle and its power use too (v1.39 — M76: the On hours stay). */
    suspend fun forget() {
        store.edit { clearKeepingHours(it) }
    }

    /**
     * v1.39 — M76: the On hours. A store from before 1.39 has none of their keys and reads as the default (7:00–22:00
     * every day, on): the migration is the new keys with their defaults.
     */
    suspend fun setHours(hours: OnHours) {
        store.edit { prefs ->
            prefs[HOURS_ON] = hours.on
            prefs[HOURS_START] = hours.start.coerceIn(0, OnHours.MINUTES_PER_DAY - 1)
            prefs[HOURS_END] = hours.end.coerceIn(0, OnHours.MINUTES_PER_DAY - 1)
        }
    }

    /**
     * v1.40 — M78: the cool-down after a fault CUT, its end on the wall clock; kept across restarts, and through a new
     * pairing or Forget (pairing again never ends it). Unreadable: none (the safety check the fault made due still holds).
     */
    override suspend fun coolUntil(): Long? = try {
        store.data.first()[COOL_UNTIL]
    } catch (e: IOException) {
        null
    }

    override suspend fun setCoolUntil(wallMs: Long?) {
        tryEdit { prefs -> if (wallMs == null) prefs.remove(COOL_UNTIL) else prefs[COOL_UNTIL] = wallMs }
    }

    private fun clearKeepingHours(prefs: MutablePreferences) {
        val on = prefs[HOURS_ON]
        val start = prefs[HOURS_START]
        val end = prefs[HOURS_END]
        val cool = prefs[COOL_UNTIL]
        prefs.clear()
        on?.let { prefs[HOURS_ON] = it }
        start?.let { prefs[HOURS_START] = it }
        end?.let { prefs[HOURS_END] = it }
        cool?.let { prefs[COOL_UNTIL] = it }
    }

    // ---- The power watch's usual idle (v1.31 — M68): a write that fails only loses a reading, never the rail's safety ----

    /** A rail-up that didn't trip read [reading]: kept, the last [PowerWatch.USUAL_KEPT]. Nothing without a plug paired. */
    suspend fun keepIdle(reading: IdleReading) {
        tryEdit { prefs ->
            if (prefs[ADDRESS].isNullOrBlank()) return@tryEdit
            prefs[IDLES] = (idlesOf(prefs[IDLES]) + reading).takeLast(PowerWatch.USUAL_KEPT).joinToString(",") { "${it.at}:${it.watts}" }
        }
    }

    /** A rail-up tripped above the usual idle: the next safety check that hears every key starts it again ([heardAll]). */
    suspend fun aboveUsual() {
        tryEdit { prefs -> if (!prefs[ADDRESS].isNullOrBlank()) prefs[ABOVE_USUAL] = true }
    }

    /**
     * A safety check passed with its sweep, every key heard: after a trip ([aboveUsual]) the usual idle starts again, a new
     * normal (a different supply); with none since, it stays. True when it started again.
     */
    suspend fun heardAll(): Boolean {
        var again = false
        tryEdit { prefs ->
            if (prefs[ABOVE_USUAL] != true) return@tryEdit
            prefs.remove(IDLES)
            prefs.remove(ABOVE_USUAL)
            again = true
        }
        return again
    }

    private suspend fun tryEdit(transform: suspend (MutablePreferences) -> Unit) {
        try {
            store.edit(transform)
        } catch (e: IOException) {
            // Unwritten: the reading is lost, or the trip repeats at the next rail-up; both safe.
        }
    }

    private companion object {
        val ADDRESS = stringPreferencesKey("address")
        val ON = booleanPreferencesKey("on")
        val GUEST_UNTIL = longPreferencesKey("guestUntil")

        /** v1.36 — M72: the pairing's kind ([SWITCHBOT]; absent: a Shelly's, kept before 1.36), the advert MAC, the Shelly cleared. */
        val KIND = stringPreferencesKey("kind")
        val MAC = stringPreferencesKey("mac")
        val SHELLY_CLEARED = booleanPreferencesKey("shellyCleared")
        const val SWITCHBOT = "switchbot"

        /** The power use's meter (v1.36 — M72): [EnergyMeter.encode]'s text. */
        val ENERGY = stringPreferencesKey("energy")

        /** The usual idle (v1.31 — M68): "1761300000000:9.8,…", oldest first, and a trip above it since. */
        val IDLES = stringPreferencesKey("idles")
        val ABOVE_USUAL = booleanPreferencesKey("aboveUsual")

        /** v1.39 — M76: the On hours: on, and their start and end (minutes of the day); absent, the defaults. */
        val HOURS_ON = booleanPreferencesKey("hoursOn")
        val HOURS_START = intPreferencesKey("hoursStart")
        val HOURS_END = intPreferencesKey("hoursEnd")

        /** v1.40 — M78: the cool-down's end (the wall clock, ms). */
        val COOL_UNTIL = longPreferencesKey("coolUntil")

        fun hoursOf(prefs: Preferences): OnHours {
            val start = prefs[HOURS_START]?.takeIf { it in 0 until OnHours.MINUTES_PER_DAY } ?: OnHours.DEFAULT_START
            val end = prefs[HOURS_END]?.takeIf { it in 0 until OnHours.MINUTES_PER_DAY } ?: OnHours.DEFAULT_END
            return OnHours(prefs[HOURS_ON] ?: true, start, end)
        }

        /** The kept readings, oldest first; what doesn't read is left out. */
        fun idlesOf(text: String?): List<IdleReading> = text.orEmpty().split(',').mapNotNull { entry ->
            val at = entry.substringBefore(':').trim().toLongOrNull()
            val watts = entry.substringAfter(':', "").trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            if (at != null && watts != null) IdleReading(at, watts) else null
        }.takeLast(PowerWatch.USUAL_KEPT)
    }
}

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
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** The paired plug (v1.26 — M60): its Bluetooth [address], and what `Shelly.GetDeviceInfo` said of it when it was paired. */
data class PlugPairing(
    val address: String,
    val id: String? = null,
    val model: String? = null,
    val firmware: String? = null,
    /** This tablet as the plug's `BLE.ListPairedDevices` named it after the bond (what Forget asks it to delete); null if unknown. */
    val bondEntry: String? = null,
    /**
     * Locked to this tablet by a bond (v1.29 — M66). False for a plug used as it is on firmware older than 2.0.0: no bond,
     * any phone nearby could switch it. Pairings kept before 1.29 read as locked.
     */
    val locked: Boolean = true,
)

/** One rail-up's idle reading (v1.31 — M68): when ([at], the wall clock, ms), and the 24 V supply's draw with nothing sent. */
data class IdleReading(val at: Long, val watts: Double)

/**
 * The plug's own settings: the [pairing] (null: none), its switch ([on]: the plug switches the rail; on from the end of
 * pairing until a person turns it off), and guest play this tablet started, [guestUntil] (the wall clock, ms), kept so a
 * restart of the app keeps arming for it. The power watch's usual idle (v1.31 — M68): the last rail-up readings that didn't
 * trip ([idles], oldest first), and whether a rail-up tripped above them since ([aboveUsual]); a pairing starts them empty.
 */
data class PlugSettings(
    val pairing: PlugPairing? = null,
    val on: Boolean = false,
    val guestUntil: Long? = null,
    val idles: List<IdleReading> = emptyList(),
    val aboveUsual: Boolean = false,
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

/** [PlugSettings] in the "power_plug" DataStore. A store that can't be read reads as no plug: the app as before it. */
class PlugStore(private val store: DataStore<Preferences>) : PairingStore {
    constructor(context: Context) : this(context.applicationContext.plugDataStore)

    val settings: Flow<PlugSettings> = store.data
        .catch { e -> if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e }
        .map { prefs ->
            val address = prefs[ADDRESS]?.takeIf { it.isNotBlank() }
            PlugSettings(
                pairing = address?.let { PlugPairing(it, prefs[ID], prefs[MODEL], prefs[FIRMWARE], prefs[BOND_ENTRY], locked = prefs[LOCKED] ?: true) },
                on = prefs[ON] ?: false,
                guestUntil = prefs[GUEST_UNTIL],
                idles = idlesOf(prefs[IDLES]),
                aboveUsual = prefs[ABOVE_USUAL] ?: false,
            )
        }

    /** Paired with [pairing]; its switch [on] (off until the Pair screen's last step). The usual idle starts empty (M68). */
    override suspend fun pair(pairing: PlugPairing, on: Boolean) {
        store.edit { prefs ->
            prefs[ADDRESS] = pairing.address
            pairing.id?.let { prefs[ID] = it } ?: prefs.remove(ID)
            pairing.model?.let { prefs[MODEL] = it } ?: prefs.remove(MODEL)
            pairing.firmware?.let { prefs[FIRMWARE] = it } ?: prefs.remove(FIRMWARE)
            pairing.bondEntry?.let { prefs[BOND_ENTRY] = it } ?: prefs.remove(BOND_ENTRY)
            prefs[LOCKED] = pairing.locked
            prefs[ON] = on
            prefs.remove(IDLES)
            prefs.remove(ABOVE_USUAL)
        }
    }

    override suspend fun setOn(on: Boolean) {
        store.edit { it[ON] = on }
    }

    suspend fun setGuestUntil(wallMs: Long?) {
        store.edit { prefs -> if (wallMs == null) prefs.remove(GUEST_UNTIL) else prefs[GUEST_UNTIL] = wallMs }
    }

    /** Forget the plug: everything goes, its usual idle too. */
    suspend fun forget() {
        store.edit { it.clear() }
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
        val ID = stringPreferencesKey("id")
        val MODEL = stringPreferencesKey("model")
        val FIRMWARE = stringPreferencesKey("firmware")
        val BOND_ENTRY = stringPreferencesKey("bondEntry")
        val ON = booleanPreferencesKey("on")
        val LOCKED = booleanPreferencesKey("locked")
        val GUEST_UNTIL = longPreferencesKey("guestUntil")

        /** The usual idle (v1.31 — M68): "1761300000000:9.8,…", oldest first, and a trip above it since. */
        val IDLES = stringPreferencesKey("idles")
        val ABOVE_USUAL = booleanPreferencesKey("aboveUsual")

        /** The kept readings, oldest first; what doesn't read is left out. */
        fun idlesOf(text: String?): List<IdleReading> = text.orEmpty().split(',').mapNotNull { entry ->
            val at = entry.substringBefore(':').trim().toLongOrNull()
            val watts = entry.substringAfter(':', "").trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            if (at != null && watts != null) IdleReading(at, watts) else null
        }.takeLast(PowerWatch.USUAL_KEPT)
    }
}

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
)

/**
 * The plug's own settings: the [pairing] (null: none), its switch ([on]: the plug switches the rail; on from the end of
 * pairing until a person turns it off), and guest play this tablet started, [guestUntil] (the wall clock, ms), kept so a
 * restart of the app keeps arming for it.
 */
data class PlugSettings(val pairing: PlugPairing? = null, val on: Boolean = false, val guestUntil: Long? = null) {
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
                pairing = address?.let { PlugPairing(it, prefs[ID], prefs[MODEL], prefs[FIRMWARE], prefs[BOND_ENTRY]) },
                on = prefs[ON] ?: false,
                guestUntil = prefs[GUEST_UNTIL],
            )
        }

    /** Paired with [pairing]; its switch [on] (off until the Pair screen's last step). */
    override suspend fun pair(pairing: PlugPairing, on: Boolean) {
        store.edit { prefs ->
            prefs[ADDRESS] = pairing.address
            pairing.id?.let { prefs[ID] = it } ?: prefs.remove(ID)
            pairing.model?.let { prefs[MODEL] = it } ?: prefs.remove(MODEL)
            pairing.firmware?.let { prefs[FIRMWARE] = it } ?: prefs.remove(FIRMWARE)
            pairing.bondEntry?.let { prefs[BOND_ENTRY] = it } ?: prefs.remove(BOND_ENTRY)
            prefs[ON] = on
        }
    }

    override suspend fun setOn(on: Boolean) {
        store.edit { it[ON] = on }
    }

    suspend fun setGuestUntil(wallMs: Long?) {
        store.edit { prefs -> if (wallMs == null) prefs.remove(GUEST_UNTIL) else prefs[GUEST_UNTIL] = wallMs }
    }

    /** Forget the plug: everything goes. */
    suspend fun forget() {
        store.edit { it.clear() }
    }

    private companion object {
        val ADDRESS = stringPreferencesKey("address")
        val ID = stringPreferencesKey("id")
        val MODEL = stringPreferencesKey("model")
        val FIRMWARE = stringPreferencesKey("firmware")
        val BOND_ENTRY = stringPreferencesKey("bondEntry")
        val ON = booleanPreferencesKey("on")
        val GUEST_UNTIL = longPreferencesKey("guestUntil")
    }
}

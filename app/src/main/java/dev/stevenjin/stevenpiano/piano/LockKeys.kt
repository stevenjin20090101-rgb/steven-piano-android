// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.piano

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.stevenjin.stevenpiano.web.relay.KeystoreSealer
import dev.stevenjin.stevenpiano.web.relay.SecretSealer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

/**
 * The keys this tablet keeps for the pianos it has claimed (v1.25 — M58; BLE_LOCK.md): K, 32 bytes, by the piano's
 * Bluetooth address. [key] is null when none is kept (or it can't be opened); [canKeep] is asked before a claim, so
 * the tablet never locks a piano to a key it then can't keep; [keep] says whether the key was kept.
 */
interface LockKeys {
    suspend fun key(address: String): ByteArray?

    suspend fun canKeep(): Boolean

    suspend fun keep(address: String, key: ByteArray): Boolean

    suspend fun forget(address: String)
}

/** No key, and none can be kept: tests and links that need none. The tablet then claims nothing. */
object NoLockKeys : LockKeys {
    override suspend fun key(address: String): ByteArray? = null

    override suspend fun canKeep(): Boolean = false

    override suspend fun keep(address: String, key: ByteArray): Boolean = false

    override suspend fun forget(address: String) = Unit
}

/** The lock's own small store, apart from the app's settings (which the web panel's run shares). */
private val Context.lockStore: DataStore<Preferences> by preferencesDataStore(name = "piano_lock")

/**
 * [LockKeys] in the app's "piano_lock" DataStore, each K sealed by the Android Keystore first ([KeystoreSealer] under
 * its own alias, [ALIAS], never the relay's): what is on disk is the sealed text, never K, and the app's backups are
 * off (the manifest), so the keys stay on this tablet. A Keystore that has lost its key opens nothing: the tablet then
 * keeps no key, and a piano locked to it reads as locked to another device until its pairing window lets the tablet
 * claim it again. The sealing runs on [io].
 */
class StoredLockKeys(
    private val store: DataStore<Preferences>,
    private val sealer: SecretSealer,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LockKeys {
    constructor(context: Context) : this(context.applicationContext.lockStore, KeystoreSealer(ALIAS))

    override suspend fun key(address: String): ByteArray? = withContext(io) {
        val sealed = try {
            store.data.first()[keyOf(address)]
        } catch (e: IOException) {
            null
        } ?: return@withContext null
        sealer.open(sealed)?.let { LockCrypto.unhex(it, LockCrypto.KEY_BYTES) }
    }

    override suspend fun canKeep(): Boolean = withContext(io) {
        sealer.seal(PROBE)?.let(sealer::open) == PROBE
    }

    override suspend fun keep(address: String, key: ByteArray): Boolean = withContext(io) {
        val text = LockCrypto.hex(key)
        val sealed = sealer.seal(text)?.takeIf { sealer.open(it) == text } ?: return@withContext false
        try {
            store.edit { it[keyOf(address)] = sealed }
            true
        } catch (e: IOException) {
            false
        }
    }

    override suspend fun forget(address: String) {
        withContext(io) {
            try {
                store.edit { it.remove(keyOf(address)) }
            } catch (e: IOException) {
                // Not forgotten: the key stays, and the piano (open now) is claimed afresh at the next connection anyway.
            }
        }
    }

    companion object {
        /** The Keystore key that seals the pianos' keys. */
        const val ALIAS = "steven-piano-lock"
        private const val PROBE = "steven-piano-lock-probe"

        /** One piano's entry: its address in capitals ("AA:BB:CC:DD:EE:FF"). */
        private fun keyOf(address: String) = stringPreferencesKey("key." + address.uppercase(Locale.ROOT))
    }
}

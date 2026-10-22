// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.safety

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.stevenjin.stevenpiano.ui.SafetyCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The safety check's record in its own DataStore (v1.26 — M61), piano safety: the count and the lock outlive the app. */
class SafetyStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** A store over [file] as one run of the app opens it; [block] runs, then the run ends. */
    private fun <T> session(file: File, block: suspend (DataStoreSafetyStore, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> T): T = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val data = PreferenceDataStoreFactory.create(scope = scope) { file }
            block(DataStoreSafetyStore(data), data)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `nothing kept reads as no check yet, and what is written comes back after a restart`() {
        val file = File(tmp.root, "safety_check.preferences_pb")
        assertEquals(SafetyRecord(), session(file) { store, _ -> store.read() })
        val kept = SafetyRecord(
            count = 4,
            pending = true,
            lock = SafetyLock.KeysSilent(listOf(36, 66)),
            last = LastCheck(at = 1_760_000_000_000L, passed = false, keys = 82, silent = listOf(36, 66), boardsOk = 7, boardsOf = 7, recovered = true, restarted = false, repowered = true),
        )
        session(file) { store, _ -> store.write(kept) }
        assertEquals("the app started again", kept, session(file) { store, _ -> store.read() })
        val coil = SafetyRecord(lock = SafetyLock.CoilOn, last = LastCheck(at = 1L, passed = false, coilOn = true))
        session(file) { store, _ -> store.write(coil) }
        assertEquals(coil, session(file) { store, _ -> store.read() })
        val passed = SafetyRecord(last = LastCheck(at = 2L, passed = true, keys = null))
        session(file) { store, _ -> store.write(passed) }
        assertEquals("unlocked, no sweep (the microphone off)", passed, session(file) { store, _ -> store.read() })
    }

    @Test
    fun `a last check kept before 1_27 reads as it did, and a newer one says which recovery was done (v1_27 M63)`() {
        val file = File(tmp.root, "older.preferences_pb")
        session(file) { _, data ->
            data.edit {
                it[longPreferencesKey("lastAt")] = 5L
                it[booleanPreferencesKey("lastPassed")] = true
                it[intPreferencesKey("lastKeys")] = 84
                it[booleanPreferencesKey("lastRecovered")] = true
            }
        }
        val older = session(file) { store, _ -> store.read() }.last!!
        assertEquals(LastCheck(at = 5L, passed = true, keys = 84, recovered = true), older)
        assertTrue(SafetyCopy.last(older, now = 5L).endsWith(" · all 84 keys after a restart"))
        val repowered = older.copy(restarted = false, repowered = true)
        assertTrue(SafetyCopy.last(repowered, now = 5L).endsWith(" · all 84 keys after a power off and on"))
        assertTrue(SafetyCopy.last(older.copy(restarted = false, repowered = false), now = 5L).endsWith(" · all 84 keys"))
    }

    @Test
    fun `a lock kept in a form it doesn't know still locks`() {
        val file = File(tmp.root, "odd.preferences_pb")
        session(file) { _, data -> data.edit { it[stringPreferencesKey("lock")] = "something newer" } }
        assertEquals(SafetyLock.KeysSilent(emptyList()), session(file) { store, _ -> store.read() }.lock)
        assertEquals(listOf(36, 61), DataStoreSafetyStore.keysOf("61, 36,x,200,36"))
    }

    @Test
    fun `a record that can't be read means a check before the next piece`() {
        val file = File(tmp.root, "broken.preferences_pb")
        file.writeText("not a preferences file")
        assertEquals(SafetyRecord(pending = true), session(file) { store, _ -> store.read() })
    }
}

// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.safety

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
import kotlinx.coroutines.flow.first
import java.io.IOException

/** Why Play is locked (v1.26 — M61), until a safety check run at the tablet passes. */
sealed interface SafetyLock {
    /** A coil was still on after the release: the 24 V was cut. */
    data object CoilOn : SafetyLock

    /** These keys (MIDI numbers) did not sound, after the restart and the repower too. */
    data class KeysSilent(val keys: List<Int>) : SafetyLock
}

/**
 * The last check that came to a result (passed, or locked Play), at [at] (epoch ms): whether it [passed]; the keys heard
 * of those struck ([keys]; null with no sweep, the microphone off); those not heard ([silent]); whether a coil was on;
 * the boards answering ([boardsOk] of [boardsOf], null where not read); whether the restart and repower were needed.
 */
data class LastCheck(
    val at: Long,
    val passed: Boolean,
    val keys: Int? = null,
    val silent: List<Int> = emptyList(),
    val coilOn: Boolean = false,
    val boardsOk: Int? = null,
    val boardsOf: Int? = null,
    val recovered: Boolean = false,
)

/**
 * What the safety check keeps across restarts of the app (v1.26 — M61): the pieces played since the last check that
 * passed ([count]), whether one is due before the next Play ([pending]: it couldn't run when it was due, or was under way
 * when the app went), why Play is locked ([lock]), and the [last] check.
 */
data class SafetyRecord(
    val count: Int = 0,
    val pending: Boolean = false,
    val lock: SafetyLock? = null,
    val last: LastCheck? = null,
)

/** Where the [SafetyRecord] is kept: its own DataStore ([DataStoreSafetyStore]), or memory in the tests. */
interface SafetyStore {
    suspend fun read(): SafetyRecord

    suspend fun write(record: SafetyRecord)
}

val Context.safetyDataStore: DataStore<Preferences> by preferencesDataStore(name = "safety_check")

/**
 * The record in its own small DataStore (`safety_check`), apart from the settings. A record that can't be read is taken
 * as a check due before the next Play: whatever it held, the piano is checked before it plays again. A write that fails
 * is logged; the runner's own copy holds until the app ends.
 */
class DataStoreSafetyStore(private val store: DataStore<Preferences>, private val log: (String) -> Unit = {}) : SafetyStore {
    override suspend fun read(): SafetyRecord = try {
        store.data.first().toRecord()
    } catch (e: IOException) {
        log("Safety check: its record couldn't be read; a check runs before the next piece")
        SafetyRecord(pending = true)
    }

    override suspend fun write(record: SafetyRecord) {
        try {
            store.edit { it.fill(record) }
        } catch (e: IOException) {
            log("Safety check: its record couldn't be saved")
        }
    }

    private fun Preferences.toRecord(): SafetyRecord {
        val lastAt = this[LAST_AT]
        return SafetyRecord(
            count = (this[COUNT] ?: 0).coerceAtLeast(0),
            pending = this[PENDING] ?: false,
            lock = lockOf(this[LOCK]),
            last = lastAt?.let { at ->
                LastCheck(
                    at = at,
                    passed = this[LAST_PASSED] ?: false,
                    keys = this[LAST_KEYS],
                    silent = keysOf(this[LAST_SILENT]),
                    coilOn = this[LAST_COIL] ?: false,
                    boardsOk = this[LAST_BOARDS_OK],
                    boardsOf = this[LAST_BOARDS_OF],
                    recovered = this[LAST_RECOVERED] ?: false,
                )
            },
        )
    }

    private fun MutablePreferences.fill(record: SafetyRecord) {
        this[COUNT] = record.count
        this[PENDING] = record.pending
        when (val lock = record.lock) {
            null -> remove(LOCK)
            SafetyLock.CoilOn -> this[LOCK] = COIL
            is SafetyLock.KeysSilent -> this[LOCK] = KEYS_PREFIX + lock.keys.joinToString(",")
        }
        val last = record.last
        if (last == null) {
            for (key in listOf(LAST_AT, LAST_PASSED, LAST_KEYS, LAST_SILENT, LAST_COIL, LAST_BOARDS_OK, LAST_BOARDS_OF, LAST_RECOVERED)) remove(key)
            return
        }
        this[LAST_AT] = last.at
        this[LAST_PASSED] = last.passed
        if (last.keys == null) remove(LAST_KEYS) else this[LAST_KEYS] = last.keys
        this[LAST_SILENT] = last.silent.joinToString(",")
        this[LAST_COIL] = last.coilOn
        if (last.boardsOk == null) remove(LAST_BOARDS_OK) else this[LAST_BOARDS_OK] = last.boardsOk
        if (last.boardsOf == null) remove(LAST_BOARDS_OF) else this[LAST_BOARDS_OF] = last.boardsOf
        this[LAST_RECOVERED] = last.recovered
    }

    companion object {
        private val COUNT = intPreferencesKey("count")
        private val PENDING = booleanPreferencesKey("pending")
        private val LOCK = stringPreferencesKey("lock")
        private val LAST_AT = longPreferencesKey("lastAt")
        private val LAST_PASSED = booleanPreferencesKey("lastPassed")
        private val LAST_KEYS = intPreferencesKey("lastKeys")
        private val LAST_SILENT = stringPreferencesKey("lastSilent")
        private val LAST_COIL = booleanPreferencesKey("lastCoil")
        private val LAST_BOARDS_OK = intPreferencesKey("lastBoardsOk")
        private val LAST_BOARDS_OF = intPreferencesKey("lastBoardsOf")
        private val LAST_RECOVERED = booleanPreferencesKey("lastRecovered")

        private const val COIL = "coil"
        private const val KEYS_PREFIX = "keys:"

        /** The lock as kept: none, a coil on, or the keys; anything else kept there still locks (the keys unknown). */
        fun lockOf(text: String?): SafetyLock? = when {
            text.isNullOrBlank() -> null
            text == COIL -> SafetyLock.CoilOn
            text.startsWith(KEYS_PREFIX) -> SafetyLock.KeysSilent(keysOf(text.removePrefix(KEYS_PREFIX)))
            else -> SafetyLock.KeysSilent(emptyList())
        }

        /** "36,61" as keys, in order; what isn't a key of the piano is left out. */
        fun keysOf(text: String?): List<Int> =
            text.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in KEY_RANGE }.distinct().sorted()

        private val KEY_RANGE = 0..127
    }
}

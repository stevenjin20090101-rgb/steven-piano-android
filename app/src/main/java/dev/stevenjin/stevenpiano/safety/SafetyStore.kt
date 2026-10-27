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

/**
 * Why Play is locked (v1.26 — M61), until a safety check run at the tablet passes. Since v1.33 — M71 only what the app
 * can trust locks: a coil still on, or power boards missing. Keys the microphone didn't hear only warn; the `keys:…`
 * lock 1.26 to 1.32 kept is cleared at the first read ([DataStoreSafetyStore]).
 */
sealed interface SafetyLock {
    /** A coil was still on after the release: the 24 V was cut. Kept as "coil". */
    data object CoilOn : SafetyLock

    /**
     * Only [answered] of [of] power boards answered, after the restart and the repower too (v1.33 — M71). Kept as
     * "boards:6/7"; [of] 0 when what was kept can't be read (it still locks).
     */
    data class BoardsMissing(val answered: Int, val of: Int) : SafetyLock
}

/**
 * The last check that came to a result (passed, or locked Play), at [at] (epoch ms): whether it [passed]; the keys
 * struck ([keys]; null with no sweep, the microphone off); those not heard ([silent]: since v1.33 — M71 a pass's
 * warning; before, a lock's keys, with [keys] then the keys heard); whether a coil was on;
 * the boards answering ([boardsOk] of [boardsOf], null where not read); whether the restart and repower were needed
 * ([recovered]), and which of them was done (v1.27 — M63: [restarted], the controller started again; [repowered], the
 * 24 V off and on; null in a record kept before them).
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
    val restarted: Boolean? = null,
    val repowered: Boolean? = null,
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
 *
 * **v1.33 — M71's migration**: a `keys:…` lock (keys not heard, 1.26 to 1.32) is cleared at the first read, logged once,
 * and a check made due before the next Play instead, so the piano unlocks by itself and is still checked first (its
 * boards too, which that lock may have hidden). A `coil` lock stays. Idempotent: once cleared there is nothing left to
 * clear; if the write fails, each read still clears it for the runner until one succeeds.
 */
class DataStoreSafetyStore(private val store: DataStore<Preferences>, private val log: (String) -> Unit = {}) : SafetyStore {
    override suspend fun read(): SafetyRecord = try {
        val prefs = store.data.first()
        val record = prefs.toRecord()
        val kept = prefs[LOCK]
        if (kept != null && kept.startsWith(KEYS_PREFIX)) {
            clearKeysLock(kept)
            record.copy(lock = null, pending = true)
        } else {
            record
        }
    } catch (e: IOException) {
        log("Safety check: its record couldn't be read; a check runs before the next piece")
        SafetyRecord(pending = true)
    }

    /** M71's migration: the old `keys:…` lock removed (only if it is still there), a check due instead. */
    private suspend fun clearKeysLock(kept: String) {
        try {
            store.edit {
                if (it[LOCK]?.startsWith(KEYS_PREFIX) == true) {
                    it.remove(LOCK)
                    it[PENDING] = true
                }
            }
            log("Safety check: the lock left by the sound test (${kept.removePrefix(KEYS_PREFIX).ifBlank { "keys unknown" }}) is cleared; keys not heard only warn now, and a check runs before the next piece")
        } catch (e: IOException) {
            log("Safety check: the lock left by the sound test couldn't be cleared in its record; it is ignored")
        }
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
                    restarted = this[LAST_RESTARTED],
                    repowered = this[LAST_REPOWERED],
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
            is SafetyLock.BoardsMissing -> this[LOCK] = BOARDS_PREFIX + lock.answered + "/" + lock.of
        }
        val last = record.last
        if (last == null) {
            for (key in listOf(LAST_AT, LAST_PASSED, LAST_KEYS, LAST_SILENT, LAST_COIL, LAST_BOARDS_OK, LAST_BOARDS_OF, LAST_RECOVERED, LAST_RESTARTED, LAST_REPOWERED)) remove(key)
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
        if (last.restarted == null) remove(LAST_RESTARTED) else this[LAST_RESTARTED] = last.restarted
        if (last.repowered == null) remove(LAST_REPOWERED) else this[LAST_REPOWERED] = last.repowered
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

        /** Which recovery step was done (v1.27 — M63): new keys, so a record kept before them reads as it did. */
        private val LAST_RESTARTED = booleanPreferencesKey("lastRestarted")
        private val LAST_REPOWERED = booleanPreferencesKey("lastRepowered")

        private const val COIL = "coil"
        /** The keys not heard, as 1.26 to 1.32 kept them: no longer a lock (v1.33 — M71). */
        private const val KEYS_PREFIX = "keys:"
        private const val BOARDS_PREFIX = "boards:"

        /**
         * The lock as kept: none, a coil on, or the boards missing ("boards:6/7"). An old `keys:…` lock is none (v1.33 —
         * M71); anything else kept there still locks, as boards missing with the figures unknown.
         */
        fun lockOf(text: String?): SafetyLock? = when {
            text.isNullOrBlank() -> null
            text == COIL -> SafetyLock.CoilOn
            text.startsWith(KEYS_PREFIX) -> null
            text.startsWith(BOARDS_PREFIX) -> boardsOf(text.removePrefix(BOARDS_PREFIX))
            else -> SafetyLock.BoardsMissing(0, 0)
        }

        /** "6/7" as the boards answering of those there are; what can't be read still locks, its figures unknown. */
        private fun boardsOf(text: String): SafetyLock.BoardsMissing {
            val parts = text.split('/').map { it.trim().toIntOrNull() }
            val answered = parts.getOrNull(0)
            val of = parts.getOrNull(1)
            if (parts.size != 2 || answered == null || of == null || answered < 0 || of <= 0 || answered > of) return SafetyLock.BoardsMissing(0, 0)
            return SafetyLock.BoardsMissing(answered, of)
        }

        /** "36,61" as keys, in order; what isn't a key of the piano is left out. */
        fun keysOf(text: String?): List<Int> =
            text.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in KEY_RANGE }.distinct().sorted()

        private val KEY_RANGE = 0..127
    }
}

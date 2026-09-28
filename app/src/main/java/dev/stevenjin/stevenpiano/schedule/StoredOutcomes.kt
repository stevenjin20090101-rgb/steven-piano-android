// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.schedule

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** The schedules' own small store, apart from the app's settings: what the last schedule did. */
private val Context.scheduleStore: DataStore<Preferences> by preferencesDataStore(name = "schedules")

/**
 * The Schedule page's "Last" line kept on the device ("Last: Wednesday 12:30, Calm channel", or
 * "Missed: Wednesday 12:30 (piano not connected)"), so a start missed while nobody looked is still
 * there when someone opens the page, whatever became of the app's process meanwhile. Not in Share
 * diagnostics' settings: the link's trail carries the missed starts, without names.
 */
class StoredOutcomes(context: Context) : ScheduleOutcomes {
    private val store = context.applicationContext.scheduleStore

    override val last: Flow<String?> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[LAST] }

    override suspend fun record(line: String) {
        try {
            store.edit { it[LAST] = line }
        } catch (e: IOException) {
            // The line is lost; the link's trail still has what happened.
        }
    }

    private companion object {
        val LAST = stringPreferencesKey("last")
    }
}

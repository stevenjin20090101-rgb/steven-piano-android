// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.util.Locale

/**
 * The set of pieces waiting for Keep or Discard as the "studio" DataStore holds it: since 1.12 the tablet's recordings,
 * Studio's own pieces being their turns'.
 */
interface WaitingSet {
    /** The set, or null when it can't be read (nothing is changed then). */
    suspend fun read(): Set<Long>?

    /** [change] applied to the set; false when it could not be written. */
    suspend fun edit(change: (Set<Long>) -> Set<Long>): Boolean
}

/**
 * [ReviewStore] for 1.12 on (v1.12 — M30): a Studio piece waits while its turn in [generations] reads "made"
 * (the history is the one record of its Keep or Discard); any other piece (a recording) waits in [legacy], the
 * DataStore's set. A piece is either a turn's or not, so the two never speak of the same piece. (Apps 1.12 to 1.23
 * also imported 1.11's set once at their first start; v1.24 — M57 dropped that.)
 */
class RoomReview(private val generations: Generations, private val legacy: WaitingSet) : ReviewStore {
    override suspend fun undecided(): Set<Long> = generations.undecided().toSet() + (legacy.read() ?: emptySet())

    override suspend fun waiting(pieceId: Long) {
        if (generations.forPiece(pieceId) == null) legacy.edit { it + pieceId }
    }

    override suspend fun decided(pieceId: Long, kept: Boolean) {
        generations.decide(pieceId, kept)
        legacy.edit { it - pieceId }
    }

    override suspend fun gone(ids: Set<Long>) {
        legacy.edit { it - ids }
    }
}

/** Studio's own small store, apart from the app's settings. */
private val Context.studioStore: DataStore<Preferences> by preferencesDataStore(name = "studio")

/** [WaitingSet] in the "studio" DataStore: the key "undecided" kept from 1.7. */
class StoredWaiting(context: Context) : WaitingSet {
    private val store = context.applicationContext.studioStore

    override suspend fun read(): Set<Long>? = try {
        store.data.first()[UNDECIDED].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    } catch (e: IOException) {
        null
    }

    override suspend fun edit(change: (Set<Long>) -> Set<Long>): Boolean = try {
        store.edit { prefs ->
            val now = prefs[UNDECIDED].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
            prefs[UNDECIDED] = change(now).map { it.toString() }.toSet()
        }
        true
    } catch (e: IOException) {
        false   // not kept: the piece simply isn't asked about after a restart
    }

    private companion object {
        val UNDECIDED = stringSetPreferencesKey("undecided")
    }
}

/** This tablet's pace (v1.12 — M30): milliseconds a token over its last compositions, for the first seconds' "time left". */
interface StudioCalibration {
    suspend fun msPerToken(): Double?

    suspend fun record(msPerToken: Double)
}

/** No pace known: time left shows once the job has measured its own. */
object NoCalibration : StudioCalibration {
    override suspend fun msPerToken(): Double? = null

    override suspend fun record(msPerToken: Double) = Unit
}

/** [StudioCalibration] in the "studio" DataStore: the last [KEEP] jobs' figures; their median. */
class StoredCalibration(context: Context) : StudioCalibration {
    private val store = context.applicationContext.studioStore

    override suspend fun msPerToken(): Double? = try {
        median(parse(store.data.first()[PACE]))
    } catch (e: IOException) {
        null
    }

    override suspend fun record(msPerToken: Double) {
        if (!msPerToken.isFinite() || msPerToken <= 0) return
        try {
            store.edit { prefs -> prefs[PACE] = (parse(prefs[PACE]) + msPerToken).takeLast(KEEP).joinToString(",") { String.format(Locale.ROOT, "%.2f", it) } }
        } catch (e: IOException) {
            // Not kept: the next job measures its own.
        } catch (e: CancellationException) {
            throw e
        }
    }

    private companion object {
        const val KEEP = 5
        val PACE = stringPreferencesKey("msPerToken")

        fun parse(text: String?): List<Double> = text?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.filter { it.isFinite() && it > 0 }.orEmpty()

        fun median(values: List<Double>): Double? = values.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
    }
}

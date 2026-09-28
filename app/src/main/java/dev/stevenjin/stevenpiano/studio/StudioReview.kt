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
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** The pieces Studio made that wait for Keep or Discard, kept across restarts. */
interface ReviewStore {
    val undecided: Flow<Set<Long>>

    suspend fun save(ids: Set<Long>)
}

/** What the review watches of the player: what is loaded and playing, and where it is. */
interface ReviewPlayer {
    val state: StateFlow<PlayerState>

    fun positionMicrosNow(): Long
}

/**
 * Keep or Discard, after a first listen (v1.7 — M23). A piece Studio has just made is **undecided**
 * ([made]); the question comes once it has been heard: while it is the piece loaded, 15 seconds of it
 * have played ([HEARD_MICROS], or all of it when it is shorter). Until Keep or Discard it asks whenever
 * it is loaded again, after a restart too (the undecided pieces are stored). Keep leaves it in the
 * library as it is; Discard deletes it (the player lets go of it first, [StudioLibrary.discard]).
 * [asking] is the piece Now playing's banner asks about, or null.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StudioReview(
    private val store: ReviewStore,
    private val player: ReviewPlayer,
    private val library: StudioLibrary,
    private val scope: CoroutineScope,
) {
    private val open = MutableStateFlow<Set<Long>>(emptySet())

    /** The pieces waiting for Keep or Discard (read from the store at [start], kept there on every change). */
    val undecided: StateFlow<Set<Long>> = open.asStateFlow()

    private val heardNow = MutableStateFlow<Set<Long>>(emptySet())

    /** The undecided pieces heard in this process. */
    val heard: StateFlow<Set<Long>> = heardNow.asStateFlow()

    private val discarded = MutableStateFlow<Set<Long>>(emptySet())

    /** The pieces discarded in this process (the Studio page's jobs read "Discarded"). */
    val discardedNow: StateFlow<Set<Long>> = discarded.asStateFlow()

    private val saving = Mutex()

    /** The piece Now playing asks about: loaded, undecided and heard. */
    val asking: StateFlow<Long?> = combine(player.state.map { it.piece?.pieceId }.distinctUntilChanged(), open, heardNow) { id, waiting, heard ->
        id?.takeIf { it in waiting && it in heard }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Reads the undecided pieces back (less those deleted from the library meanwhile, through its menu),
     * then watches the player: an undecided piece loaded and playing is listened to until it counts as
     * heard.
     */
    fun start() {
        scope.launch {
            val stored = store.undecided.first()
            val gone = stored.filterNot { library.exists(it) }.toSet()
            open.update { it + (stored - gone) }
            if (gone.isNotEmpty()) change { it - gone }
        }
        scope.launch {
            combine(player.state, open) { state, waiting ->
                val piece = state.piece
                if (piece != null && piece.pieceId in waiting && state.status == PlaybackStatus.Playing) piece.pieceId to piece.durationMicros else null
            }.distinctUntilChanged().flatMapLatest { listening ->
                if (listening == null) flowOf(Unit) else flow<Unit> { listen(listening.first, listening.second) }
            }.collect { }
        }
    }

    /**
     * Polls the position until [enough] of [pieceId] has played. A position counts only once one under
     * [enough] has been read: the player's state names a new piece a moment before its clock leaves the
     * last one, whose position must not count for it (seen on the emulator: the question came as the
     * next piece loaded).
     */
    private suspend fun listen(pieceId: Long, durationMicros: Long) {
        val enough = minOf(HEARD_MICROS, (durationMicros - END_SLACK_MICROS).coerceAtLeast(0L))
        var fromItsStart = false
        while (pieceId !in heardNow.value) {
            val at = player.positionMicrosNow()
            if (at < enough) fromItsStart = true else if (fromItsStart) heardNow.update { it + pieceId }
            delay(POLL_MS)
        }
    }

    /** Studio has just made [pieceId]: it waits for Keep or Discard. */
    suspend fun made(pieceId: Long) = change { it + pieceId }

    /** Keep: it stays in the library, and is not asked about again. */
    suspend fun keep(pieceId: Long) = change { it - pieceId }

    /** Discard: it leaves the library (and the player, silenced first if it plays it). */
    suspend fun discard(pieceId: Long) {
        discarded.update { it + pieceId }
        change { it - pieceId }
        library.discard(pieceId)
    }

    /** The undecided pieces changed: at once in memory, then in the store (a store that fails loses nothing now). */
    private suspend fun change(edit: (Set<Long>) -> Set<Long>) {
        open.update(edit)
        saving.withLock { store.save(open.value) }   // the latest set, whichever change came last
    }

    companion object {
        /** A piece counts as heard after 15 seconds of it. */
        const val HEARD_MICROS = 15_000_000L

        /** A piece shorter than that is heard at its end (a frame before it, as the clock may stop just short). */
        private const val END_SLACK_MICROS = 50_000L
        private const val POLL_MS = 250L
    }
}

/** Studio's own small store, apart from the app's settings: the pieces waiting for Keep or Discard. */
private val Context.studioStore: DataStore<Preferences> by preferencesDataStore(name = "studio")

/** [ReviewStore] in the "studio" DataStore. A store that can't be read holds nothing: no piece is ever asked about wrongly. */
class StoredReview(context: Context) : ReviewStore {
    private val store = context.applicationContext.studioStore

    override val undecided: Flow<Set<Long>> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[UNDECIDED].orEmpty().mapNotNull { it.toLongOrNull() }.toSet() }
        .distinctUntilChanged()

    override suspend fun save(ids: Set<Long>) {
        try {
            store.edit { it[UNDECIDED] = ids.map { id -> id.toString() }.toSet() }
        } catch (e: IOException) {
            // Not kept: the piece simply isn't asked about after a restart.
        }
    }

    private companion object {
        val UNDECIDED = stringSetPreferencesKey("undecided")
    }
}

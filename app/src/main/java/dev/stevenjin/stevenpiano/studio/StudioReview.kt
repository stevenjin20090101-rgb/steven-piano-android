// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The pieces waiting for Keep or Discard, kept across restarts (v1.12 — M30: a Studio piece's state is its turn in
 * Studio's history, [RoomReview]; a recording's, the "studio" DataStore's set, as before).
 */
interface ReviewStore {
    /** The pieces waiting, read once as the review starts (the one-off import of the old set runs here). */
    suspend fun undecided(): Set<Long>

    /** [pieceId] waits for Keep or Discard. */
    suspend fun waiting(pieceId: Long)

    /** [pieceId] was kept, or discarded. */
    suspend fun decided(pieceId: Long, kept: Boolean)

    /** [ids] left the library meanwhile (deleted from its menu). */
    suspend fun gone(ids: Set<Long>)
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
            val stored = store.undecided()
            val gone = stored.filterNot { library.exists(it) }.toSet()
            open.update { it + (stored - gone) }
            if (gone.isNotEmpty()) saving.withLock { store.gone(gone) }
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

    /** Studio (or a recording) has just made [pieceId]: it waits for Keep or Discard. */
    suspend fun made(pieceId: Long) {
        open.update { it + pieceId }
        saving.withLock { store.waiting(pieceId) }
    }

    /** Keep: it stays in the library, and is not asked about again. */
    suspend fun keep(pieceId: Long) {
        open.update { it - pieceId }
        saving.withLock { store.decided(pieceId, kept = true) }
    }

    /** Discard: it leaves the library (and the player, silenced first if it plays it). */
    suspend fun discard(pieceId: Long) {
        discarded.update { it + pieceId }
        open.update { it - pieceId }
        saving.withLock { store.decided(pieceId, kept = false) }
        library.discard(pieceId)
    }

    companion object {
        /** A piece counts as heard after 15 seconds of it. */
        const val HEARD_MICROS = 15_000_000L

        /** A piece shorter than that is heard at its end (a frame before it, as the clock may stop just short). */
        private const val END_SLACK_MICROS = 50_000L
        private const val POLL_MS = 250L
    }
}

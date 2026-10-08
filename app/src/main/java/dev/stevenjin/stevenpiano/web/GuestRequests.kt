// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Guests' requests (DESIGN.md › v1.5.1 — M18), in memory only. A guest asks for one piece of the
 * catalogue at a time from the request page; each of their keys (the `sp_guest` cookie and the
 * phone's address) may ask once every [windowMs] (five minutes), and a key that asked since must
 * wait ([Outcome.Wait]). With Approve requests first a request waits in [pending] (at most
 * [maxPending]) until the person approves or dismisses it, on the panel or on the tablet's
 * Library; without it the piece joins Up next at once, and while [maxPending] guests' pieces are
 * still waiting there the list is full. A request never starts playback (v1.20 — M54): with nothing
 * playing it waits in Up next for someone to press Play (`Player.queueWaiting`), and during a quiet
 * time it still goes in. [requested] holds the queue entries guests asked for, so
 * Up next can mark them. No free text ever comes in: a request is a piece's id, checked against
 * the catalogue by the caller. Thread-safe.
 */
class GuestRequests(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxPending: Int = MAX_PENDING,
    private val windowMs: Long = WINDOW_MS,
    private val maxKeys: Int = MAX_KEYS,
) {
    /** A request waiting for approval: [id] is its own, [at] when it came. */
    data class Request(val id: Long, val pieceId: Long, val title: String, val composer: String, val at: Long)

    sealed interface Outcome {
        /** The guest asked less than five minutes ago: [retryAfterMs] until they may again. */
        data class Wait(val retryAfterMs: Long) : Outcome

        /** Fifty requests already wait. */
        data object Full : Outcome

        /** It waits for approval. */
        data class Pending(val request: Request) : Outcome

        /** It goes into Up next now, never starting playback (the caller adds it, then [noteQueued]). */
        data object Queue : Outcome
    }

    private val lastAsked = LinkedHashMap<String, Long>(16, 0.75f, true)
    private val _pending = MutableStateFlow<List<Request>>(emptyList())
    private val _requested = MutableStateFlow<Set<Long>>(emptySet())
    private var nextId = 1L

    /** Requests waiting for approval, oldest first. */
    val pending: StateFlow<List<Request>> = _pending.asStateFlow()

    /** Queue entries (uids) guests asked for, still in the queue as far as [retain] last heard. */
    val requested: StateFlow<Set<Long>> = _requested.asStateFlow()

    /**
     * A guest (by all of [keys]) asks for [piece]. Refused while any of the keys asked within the
     * window; otherwise it waits for approval ([approveFirst]) or goes to the queue, and the keys
     * count as having asked.
     */
    @Synchronized
    fun submit(piece: WebPiece, keys: List<String>, approveFirst: Boolean): Outcome {
        val now = clock()
        prune(now)
        val wait = keys.maxOfOrNull { key -> lastAsked[key]?.let { windowMs - (now - it) } ?: 0L } ?: 0L
        if (wait > 0) return Outcome.Wait(wait)
        val outcome = if (approveFirst) {
            if (_pending.value.size >= maxPending) return Outcome.Full
            val request = Request(nextId++, piece.id, piece.title, piece.composerShort, now)
            _pending.value = _pending.value + request
            Outcome.Pending(request)
        } else {
            if (_requested.value.size >= maxPending) return Outcome.Full
            Outcome.Queue
        }
        for (key in keys) lastAsked[key] = now
        while (lastAsked.size > maxKeys) lastAsked.remove(lastAsked.keys.first())
        return outcome
    }

    /** The queue entries a guest's piece became. */
    @Synchronized
    fun noteQueued(uids: Collection<Long>) {
        if (uids.isNotEmpty()) _requested.value = _requested.value + uids
    }

    /** Approve: request [id] leaves the list and is returned for the caller to queue; null when it is gone already. */
    @Synchronized
    fun take(id: Long): Request? {
        val request = _pending.value.firstOrNull { it.id == id } ?: return null
        _pending.value = _pending.value - request
        return request
    }

    /** Dismiss: request [id] leaves the list, nothing plays. False when it is gone already. */
    @Synchronized
    fun dismiss(id: Long): Boolean = take(id) != null

    /** The queue now holds [uids]: guests' entries that left it (played, removed, cleared) are forgotten. */
    @Synchronized
    fun retain(uids: Collection<Long>) {
        val kept = _requested.value.filter { it in uids }.toSet()
        if (kept.size != _requested.value.size) _requested.value = kept
    }

    private fun prune(now: Long) {
        lastAsked.entries.removeAll { now - it.value >= windowMs || now < it.value }
    }

    companion object {
        const val MAX_PENDING = 50
        const val WINDOW_MS = 5 * 60_000L
        const val MAX_KEYS = 1_024
    }
}

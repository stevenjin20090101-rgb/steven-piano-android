// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.schedule

import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.ble.PianoLink
import dev.stevenjin.stevenpiano.channels.LoudnessHold
import dev.stevenjin.stevenpiano.data.db.ScheduleEntity
import dev.stevenjin.stevenpiano.data.db.ScheduleKind
import dev.stevenjin.stevenpiano.diag.LinkLog
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What a schedule plays through: the player and the channels (the app's own; a fake in tests). Main thread. */
interface ScheduleDeck {
    val state: StateFlow<PlayerState>

    /** Channel [key] from a fresh shuffle, at [volumePct] or its own volume. False when it can't play (a pool too small, or not known in time). */
    suspend fun playChannel(key: String, volumePct: Int?): Boolean

    /** The pieces playlist [id] holds, in its order: none when it is empty or gone. */
    suspend fun playlistPieces(id: Long): List<Long>

    suspend fun hasPiece(id: Long): Boolean

    /** What a schedule's target is called (a channel's name, a playlist's, a piece's title); null when it is gone. */
    suspend fun nameOf(kind: ScheduleKind, target: String): String?

    /** A playlist's pieces from the top, in order: they replace whatever played. */
    fun playAll(ids: List<Long>)

    /** One piece: it replaces whatever played. */
    fun play(id: Long)

    fun stop()
}

/** What the last schedule did, the Schedule page's "Last" line: kept on the device, so it outlives the app's process. */
interface ScheduleOutcomes {
    val last: Flow<String?>

    suspend fun record(line: String)
}

/**
 * Plays a schedule when its alarm goes off (DESIGN.md › v1.5.2 — M19). The alarm's receiver calls
 * [prepare] first: the playback service comes up at once, while Android still allows a start from
 * the background (for a few seconds after an exact alarm), and stays in the foreground while
 * [starting] is true, holding the tablet awake. Then [fire]:
 *
 * 1. the piano: when the link is not connected, it is asked to connect to the last piano, and the
 *    schedule waits [connectWaitMs] (20 s) at most; after that the start is missed, and says so in
 *    the link's trail and on the Schedule page: "Missed: Wednesday 12:30 (piano not connected)";
 * 2. the piano's settings are read on every connection: the schedule waits for them a moment
 *    ([settingsWaitMs]), so its volume goes to the piano's own volume where there is one;
 * 3. it plays, replacing whatever played: a channel through the channels (at the schedule's volume,
 *    else the channel's own), a playlist from its top, or one piece; a playlist's or a piece's volume
 *    is held as a channel's is ([LoudnessHold]: never saved on the piano, put back after).
 *
 * The schedule is then in charge of what plays until it ends ([end], its end alarm: the player
 * stops, the channel ends, the volume comes back) or until something else takes over (the person
 * plays something, a channel starts, another schedule starts, the playlist runs out): then what it
 * started is no longer its to stop, and a volume it held comes back at once. Call on [scope]'s
 * thread (the main thread), after [start].
 */
class ScheduleRunner(
    private val link: PianoLink,
    private val lastAddress: () -> String?,
    private val piano: StateFlow<PianoState>,
    private val deck: ScheduleDeck,
    private val loudness: LoudnessHold,
    private val startService: () -> Unit,
    private val outcomes: ScheduleOutcomes,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = LinkLog::warn,
    private val connectWaitMs: Long = CONNECT_WAIT_MS,
    private val settingsWaitMs: Long = SETTINGS_WAIT_MS,
) {
    private val _starting = MutableStateFlow(false)

    /** A schedule is on its way to playing (waiting for the piano): the playback service stays in the foreground meanwhile. */
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

    /** What a schedule started, while it is in charge of what plays. */
    private var run: Run? = null
    private var idleCheck: Job? = null
    private var started = false

    /** Follows the player from now on: a schedule's run ends when something else takes over. */
    fun start() {
        if (started) return
        started = true
        scope.launch { deck.state.collect(::onState) }
    }

    /** A start's alarm went off: the playback service comes up now, before anything is read. */
    fun prepare() {
        _starting.value = true
        startService()
    }

    /** Nothing starts after all (the schedule was deleted or turned off meanwhile): the service may go. */
    fun unprepare() {
        _starting.value = false
    }

    /** Starts [entry] for its [occurrence]: the piano first, then its settings, then the play. */
    suspend fun fire(occurrence: Occurrence, entry: ScheduleEntity) {
        _starting.value = true
        try {
            if (!connected()) return missed(occurrence, ScheduleCopy.NO_PIANO)
            withTimeoutOrNull(settingsWaitMs) { piano.first { it !is PianoState.Unknown } }
            val name = deck.nameOf(entry.kind, entry.target)
            val previous = run
            play(entry)?.let { reason -> return missed(occurrence, reason) }
            // What an earlier schedule started is no longer its to end; the volume it held comes back,
            // unless this one holds the loudness now (it then keeps the first "what comes back").
            if (previous != null && previous !== run) loudness.release(previous)
            outcomes.record(ScheduleCopy.played(occurrence.at, entry.kind, name ?: fallbackName(entry)))
            log("Schedule started: ${ScheduleCopy.moment(occurrence.at)} (${describe(entry)})")
        } finally {
            _starting.value = false
        }
    }

    /** [occurrence] is a schedule's end: what it started stops, if it still plays, and its volume comes back. */
    fun end(occurrence: Occurrence) {
        val current = run ?: return
        if (current.scheduleId != occurrence.scheduleId) return
        if (current.inCharge(deck.state.value)) deck.stop()
        finish(current)
        log("Schedule ended: ${ScheduleCopy.moment(occurrence.at)} (schedule ${current.scheduleId})")
    }

    /** Another schedule starting at the same minute as one that plays: it is left out, and says so in the trail. */
    fun skipped(occurrence: Occurrence) {
        log("Schedule ${occurrence.scheduleId} skipped: another starts at ${ScheduleCopy.moment(occurrence.at)}")
    }

    private suspend fun connected(): Boolean {
        if (link.state.value is LinkState.Connected) return true
        link.connect(lastAddress())
        return withTimeoutOrNull(connectWaitMs) { link.state.first { it is LinkState.Connected } } != null
    }

    /** Plays [entry]'s target; null when it plays, else why it could not. */
    private suspend fun play(entry: ScheduleEntity): String? = when (entry.kind) {
        ScheduleKind.CHANNEL -> if (deck.playChannel(entry.target, entry.volumePct)) {
            run = Run(entry.id, channel = entry.target)
            null
        } else {
            "the channel needs more pieces"
        }
        ScheduleKind.PLAYLIST -> {
            val ids = entry.target.toLongOrNull()?.let { deck.playlistPieces(it) }.orEmpty()
            if (ids.isEmpty()) {
                "the playlist has no pieces"
            } else {
                deck.playAll(ids)
                queued(entry)
                null
            }
        }
        ScheduleKind.PIECE -> {
            val id = entry.target.toLongOrNull()
            if (id == null || !deck.hasPiece(id)) {
                "the piece was deleted"
            } else {
                deck.play(id)
                queued(entry)
                null
            }
        }
    }

    /** The queue [entry] just started is its run; its volume is held while that run is in charge. */
    private fun queued(entry: ScheduleEntity) {
        val current = Run(entry.id, channel = null, uids = deck.state.value.queue.uids.toSet())
        run = current
        entry.volumePct?.let { loudness.hold(current, it) }
    }

    private suspend fun missed(occurrence: Occurrence, reason: String) {
        val line = ScheduleCopy.missed(occurrence.at, reason)
        log(line)
        outcomes.record(line)
    }

    /**
     * The player moved: a run that is no longer in charge ends at once; one that stands stopped with
     * nothing loading (its list ended, or someone stopped it) ends when it is still so after
     * [IDLE_GRACE_MS], since between two pieces the player is stopped for the gap too.
     */
    private fun onState(state: PlayerState) {
        val current = run ?: return
        if (!current.inCharge(state)) return finish(current)
        if (!current.idle(state)) {
            idleCheck?.cancel()
            return
        }
        if (idleCheck?.isActive == true) return
        idleCheck = scope.launch {
            delay(IDLE_GRACE_MS)
            val now = deck.state.value
            if (run === current && (!current.inCharge(now) || current.idle(now))) finish(current)
        }
    }

    private fun finish(current: Run) {
        idleCheck?.cancel()
        loudness.release(current)
        if (run === current) run = null
    }

    /** For the link's trail: a channel's key or an id, never a title (LinkLog keeps no names from the library). */
    private fun describe(entry: ScheduleEntity): String = "${entry.kind.name.lowercase()} ${entry.target}, schedule ${entry.id}"

    private fun fallbackName(entry: ScheduleEntity): String = when (entry.kind) {
        ScheduleKind.CHANNEL -> ScheduleCopy.channelFallback(entry.target)
        ScheduleKind.PLAYLIST -> ScheduleCopy.GONE_PLAYLIST
        ScheduleKind.PIECE -> ScheduleCopy.GONE_PIECE
    }

    /**
     * What a schedule started: a channel ([channel]), in charge while the player's channel is it; or
     * a queue ([uids], its entries), in charge while one of them is the player's current entry, no
     * channel has taken over, and it has not failed to load.
     */
    private class Run(val scheduleId: Long, val channel: String?, val uids: Set<Long> = emptySet()) {
        /** It has played: a stop after this is an end (before it, the player has only not started yet). */
        private var played = false

        fun inCharge(state: PlayerState): Boolean {
            if (channel != null) return state.channel == channel
            if (state.channel != null || state.queue.currentUid !in uids) return false
            if (state.status == PlaybackStatus.Playing) played = true
            return state.loading || state.piece != null || state.problem == null
        }

        /** Stopped with nothing loading, after it has played: its list ended, or someone stopped it (or the gap between two pieces). */
        fun idle(state: PlayerState): Boolean = channel == null && played && !state.loading && state.status == PlaybackStatus.Stopped
    }

    companion object {
        /** How long a schedule waits for the piano to connect before its start is missed. */
        const val CONNECT_WAIT_MS = 20_000L

        /** How long it waits for the piano's settings after connecting (the piano answers within 2 s), so its volume reaches the piano. */
        const val SETTINGS_WAIT_MS = 3_000L

        /** A run stopped this long with nothing loading has ended (the gap between two pieces is 1.5 s to 5 s). */
        const val IDLE_GRACE_MS = 8_000L
    }
}

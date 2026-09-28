// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.schedule

import android.app.Application
import android.os.PowerManager
import android.util.Log
import dev.stevenjin.stevenpiano.AppGraph
import dev.stevenjin.stevenpiano.BuildConfig
import dev.stevenjin.stevenpiano.data.db.ScheduleDao
import dev.stevenjin.stevenpiano.data.db.ScheduleEntity
import dev.stevenjin.stevenpiano.data.db.ScheduleKind
import dev.stevenjin.stevenpiano.diag.LinkLog
import dev.stevenjin.stevenpiano.player.PlayerState
import dev.stevenjin.stevenpiano.service.PlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** A schedule and what its target is called now ("Calm", a playlist's name, a piece's title; a stand-in once it is gone). */
data class ScheduleRow(val entry: ScheduleEntity, val name: String) {
    /** "Weekdays 12:30". */
    val whenLine: String get() = ScheduleCopy.whenLine(entry.days, entry.startMinute)

    /** "Calm channel · until 13:15 · 70%". */
    val whatLine: String get() = ScheduleCopy.whatLine(entry.kind, name, entry.endMinute, entry.volumePct)
}

/** The next start ([occurrence]) and what it plays ([name]): "Next: Wednesday 12:30, Calm", and the hub's "Next Wed 12:30". */
data class NextSchedule(val occurrence: Occurrence, val name: String) {
    val line: String get() = ScheduleCopy.next(occurrence.at, name)

    val hub: String get() = ScheduleCopy.hub(occurrence.at)
}

/**
 * Timed play (DESIGN.md › v1.5.2 — M19), one per process ([AppGraph.schedules]): the schedules
 * ([repository]), the one exact alarm that keeps the next of them ([planner]), what an alarm does
 * when it goes off ([onAlarm], through the [runner]), and what the Schedule page, the hub, Now
 * playing and the web panel show of them ([rows], [next], [last]). Everything runs in the app's
 * scope, on the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Schedules(private val app: Application, private val graph: AppGraph, dao: ScheduleDao) {
    val repository = ScheduleRepository(dao)

    val planner = SchedulePlanner(repository::list, AndroidAlarmScheduler(app), log = ::debug)

    private val outcomes: ScheduleOutcomes = StoredOutcomes(app)

    val runner: ScheduleRunner by lazy {
        ScheduleRunner(
            link = graph.pianoLink,
            lastAddress = { graph.settings.value.lastDeviceAddress },
            piano = graph.pianoSettings.state,
            deck = AppDeck(),
            loudness = graph.channelPlayer.loudness,
            startService = ::startPlayback,
            outcomes = outcomes,
            scope = graph.appScope,
        )
    }

    /** Every schedule, by start time, as it changes; null until first read. */
    val entries: StateFlow<List<ScheduleEntity>?> = repository.all.stateIn(graph.appScope, SharingStarted.Eagerly, null)

    /** Every schedule with its target's name, as the Schedule page lists them; null until read. */
    val rows: StateFlow<List<ScheduleRow>?> =
        combine(repository.all, graph.channelPools.summaries, graph.library.playlists()) { list, _, _ -> list }
            .mapLatest { list -> rowsOf(list) }
            .stateIn(graph.appScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    /** The next start and what it plays, worked out again each minute while something shows it. */
    val next: StateFlow<NextSchedule?> =
        combine(repository.all, minutes(), graph.channelPools.summaries) { list, now, _ -> list to now }
            .mapLatest { (list, now) -> nextOf(list, now) }
            .stateIn(graph.appScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    /** What the last schedule did: "Last: Wednesday 12:30, Calm channel" or "Missed: … (piano not connected)"; null before any. */
    val last: StateFlow<String?> = outcomes.last.stateIn(graph.appScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    /** Whether Android lets the schedules set their exact alarm; the Schedule page asks for it when not. */
    val exactAllowed: StateFlow<Boolean> get() = planner.exactAllowed

    private var started = false
    private var alarmJob: Job? = null

    /** Keeps the tablet awake from a start's alarm until it plays or is missed, whatever the playback service is doing meanwhile. */
    private val startLock: PowerManager.WakeLock by lazy {
        app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply { setReferenceCounted(false) }
    }

    /** From [AppGraph.start]: the runner follows the player, and the alarm is planned now and after every change to the schedules. */
    fun start() {
        if (started) return
        started = true
        runner.start()
        graph.appScope.launch { repository.all.collect { replan() } }
    }

    /** Saves a schedule made or edited on the Schedule page or in the web panel (the table's change plans the alarm again). */
    suspend fun save(draft: ScheduleDraft): SaveResult = repository.save(draft)

    suspend fun setEnabled(id: Long, enabled: Boolean) = repository.setEnabled(id, enabled)

    suspend fun delete(id: Long) = repository.delete(id)

    /** The Schedule page came back into view (perhaps from Android's Alarms & reminders): exact alarms are asked about again. */
    fun recheckExact() {
        graph.appScope.launch { runCatching { planner.recheckExact() } }
    }

    /**
     * The alarm went off at [atMillis] for a schedule's [edge]. A start brings the playback service
     * up at once, while Android allows it; then the schedules are read, the alarm is planned again
     * from this minute, the broadcast is answered ([done]), and what is due runs: the ends first,
     * then the first start (another starting at the same minute is skipped, and says so), which may
     * wait up to 20 s for the piano under the playback service.
     */
    fun onAlarm(atMillis: Long, edge: Edge, done: () -> Unit) {
        if (edge == Edge.START) {
            startLock.acquire(START_WAKE_MS)
            runner.prepare()
        }
        alarmJob?.cancel()   // alarms are a minute apart at least; a start still waiting for the piano gives way
        alarmJob = graph.appScope.launch {
            var start: Pair<Occurrence, ScheduleEntity>? = null
            try {
                val list = repository.list()
                val due = if (atMillis > 0) Occurrences.dueAt(list, ZonedDateTime.ofInstant(Instant.ofEpochMilli(atMillis), ZoneId.systemDefault())) else emptyList()
                debug("Alarm at $atMillis (${edge.name.lowercase()}): due ${due.map { "${it.scheduleId} ${it.edge.name.lowercase()}" }}")
                replan(afterMillis = atMillis.takeIf { it > 0 })
                due.filter { it.edge == Edge.END }.forEach(runner::end)
                val starts = due.filter { it.edge == Edge.START }
                starts.drop(1).forEach(runner::skipped)
                start = starts.firstOrNull()?.let { first -> list.firstOrNull { it.id == first.scheduleId }?.let { first to it } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "A schedule's alarm couldn't be handled", e)
            } finally {
                done()
            }
            try {
                val (occurrence, entry) = start ?: return@launch runner.unprepare()
                runner.fire(occurrence, entry)
            } finally {
                if (startLock.isHeld) startLock.release()
            }
        }
    }

    /** The tablet restarted, the clock or the zone changed, the app was updated, or exact alarms were allowed: the alarm is planned again. */
    fun replanFor(action: String, done: () -> Unit) {
        graph.appScope.launch {
            try {
                debug("Planning again after $action")
                replan()
            } finally {
                done()
            }
        }
    }

    /** What a schedule's target is called now; null once it is gone. */
    suspend fun nameOf(kind: ScheduleKind, target: String): String? = when (kind) {
        ScheduleKind.CHANNEL -> graph.channelPools.summary(target)?.name
        ScheduleKind.PLAYLIST -> target.toLongOrNull()?.let { id -> graph.library.playlists().first().firstOrNull { it.id == id }?.name }
        ScheduleKind.PIECE -> target.toLongOrNull()?.let { id -> graph.library.piece(id)?.title }
    }

    /** Every schedule with its target's name now (the web panel's list). */
    suspend fun rowsNow(): List<ScheduleRow> = rowsOf(repository.list())

    /** The next start now (the web panel's "Next:" line), from the schedules as last read. */
    suspend fun nextNow(): NextSchedule? = nextOf(entries.value ?: repository.list(), ZonedDateTime.now())

    /** What the last schedule did, as kept on the device (the web panel's page). */
    suspend fun lastNow(): String? = outcomes.last.first()

    /** A number that changes whenever the schedules do (the web panel reads its Schedule page again). */
    val revision: Int get() = entries.value?.hashCode() ?: 0

    private suspend fun rowsOf(list: List<ScheduleEntity>): List<ScheduleRow> {
        val playlists = if (list.any { it.kind == ScheduleKind.PLAYLIST }) graph.library.playlists().first().associate { it.id to it.name } else emptyMap()
        val pieceIds = list.filter { it.kind == ScheduleKind.PIECE }.mapNotNull { it.target.toLongOrNull() }
        val pieces = if (pieceIds.isEmpty()) emptyMap() else graph.library.summaries(pieceIds)
        return list.map { entry ->
            val name = when (entry.kind) {
                ScheduleKind.CHANNEL -> graph.channelPools.summary(entry.target)?.name ?: ScheduleCopy.channelFallback(entry.target)
                ScheduleKind.PLAYLIST -> entry.target.toLongOrNull()?.let(playlists::get) ?: ScheduleCopy.GONE_PLAYLIST
                ScheduleKind.PIECE -> entry.target.toLongOrNull()?.let(pieces::get)?.title ?: ScheduleCopy.GONE_PIECE
            }
            ScheduleRow(entry, name)
        }
    }

    private suspend fun nextOf(list: List<ScheduleEntity>, now: ZonedDateTime): NextSchedule? {
        val occurrence = Occurrences.nextStart(list, now) ?: return null
        val entry = list.first { it.id == occurrence.scheduleId }
        val name = nameOf(entry.kind, entry.target) ?: rowsOf(listOf(entry)).single().name
        return NextSchedule(occurrence, name)
    }

    /** The time now, and again just after each minute turns: the "Next" line moves on when a start has passed. */
    private fun minutes(): Flow<ZonedDateTime> = flow {
        while (true) {
            val now = ZonedDateTime.now()
            emit(now)
            delay(MINUTE_MS - (now.second * 1_000L + now.nano / 1_000_000L) + TICK_SLACK_MS)
        }
    }

    /** Plans the alarm; a failure (the table unreadable) is logged and leaves the last alarm as it was. */
    private suspend fun replan(afterMillis: Long? = null) {
        try {
            planner.replan(afterMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The schedules' alarm couldn't be planned", e)
        }
    }

    /** The playback service, in the foreground; Android may refuse it outside an alarm's grace, and playback then goes on without its notification. */
    private fun startPlayback() {
        try {
            PlaybackService.start(app)
        } catch (e: IllegalStateException) {
            LinkLog.warn("Schedule: Android refused the playback service; playing without its notification")
        } catch (e: SecurityException) {
            LinkLog.warn("Schedule: Android refused the playback service; playing without its notification")
        }
    }

    private fun debug(line: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, line)
    }

    /** The player and the channels, as a schedule plays them. */
    private inner class AppDeck : ScheduleDeck {
        override val state: StateFlow<PlayerState> get() = graph.player.state

        override val locked: Boolean get() = graph.player.locked

        override suspend fun playChannel(key: String, volumePct: Int?): Boolean {
            // Started by an alarm, the app may have only just opened: its channels' pools take a moment.
            withTimeoutOrNull(POOLS_WAIT_MS) { graph.channelPools.summaries.first { it != null } }
            return graph.channelPlayer.play(key, volumePct)
        }

        override suspend fun playlistPieces(id: Long): List<Long> = graph.library.inPlaylist(id).first().map { it.id }

        override suspend fun hasPiece(id: Long): Boolean = graph.library.piece(id) != null

        override suspend fun nameOf(kind: ScheduleKind, target: String): String? = this@Schedules.nameOf(kind, target)

        override fun playAll(ids: List<Long>) = graph.player.playAll(ids, shuffle = false)

        override fun play(id: Long) = graph.player.play(id)

        override fun stop() = graph.player.stop()
    }

    private companion object {
        const val TAG = "Schedule"
        const val STOP_MS = 5_000L
        const val MINUTE_MS = 60_000L
        const val TICK_SLACK_MS = 50L
        const val POOLS_WAIT_MS = 5_000L
        const val WAKE_LOCK_TAG = "StevenPiano:schedule"

        /** The longest a start can take: the piano's 20 s, its settings' 3 s, the channels' pools, and some room. */
        const val START_WAKE_MS = 45_000L
    }
}

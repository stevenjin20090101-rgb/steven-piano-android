// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.player

import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.ble.PianoLink
import dev.stevenjin.stevenpiano.midi.MidiPiece
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** Where the player gets pieces: the library, or a fake in tests. */
interface PieceSource {
    /** Reads and parses a piece; throws with a plain-English message when it can't be played. */
    suspend fun load(pieceId: Long): PlayablePiece

    suspend fun markPlayed(pieceId: Long)
}

class PlayablePiece(val id: Long, val title: String, val composer: String, val midi: MidiPiece)

/**
 * Playback for the whole app: a process singleton in AppGraph. Call it on [scope]'s thread
 * (the main thread). The engine runs on the [Scheduler] thread and reports back through
 * [state], plus allocation-free [positionMicrosAt] and active-key bitsets for per-frame drawing.
 * When the link drops mid-piece the player pauses; when it comes back, Play resumes.
 */
class Player(
    private val link: PianoLink,
    private val source: PieceSource,
    private val scope: CoroutineScope,
    private val clock: NanoClock = NanoClock.System,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    prepareThread: () -> Unit = Scheduler.UrgentAudio,
) {
    private val engine = PlaybackEngine(link)
    private val scheduler = Scheduler(engine, clock, ::publish, prepareThread)
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    @Volatile
    private var position = PositionClock.Zero
    private val activeLow = AtomicLong()
    private val activeHigh = AtomicLong()

    // Owned by the scope's thread.
    private var playlist = Playlist.Empty
    private var defaultTempoPct = 100
    private var loadJob: Job? = null
    private var advanceJob: Job? = null
    private var linkConnected = false

    // Owned by the scheduler thread: what was last published.
    private var shownStatus = PlaybackStatus.Stopped
    private var shownTempo = 100
    private var shownEnded = false

    init {
        scheduler.start()
        scope.launch { link.state.collect(::onLinkState) }
    }

    /** The song position at [nanos] (a `System.nanoTime()` value such as a frame time), within the piece. */
    fun positionMicrosAt(nanos: Long): Long = position.at(nanos)

    fun positionMicrosNow(): Long = position.at(clock.nanoTime())

    /** Keys the piano is playing, as bits `key - 24`: keys 24..87. */
    val activeKeysLow: Long get() = activeLow.get()

    /** Keys the piano is playing, as bits `key - 88`: keys 88..107. */
    val activeKeysHigh: Long get() = activeHigh.get()

    /** Plays [pieceId]; Next, Previous and auto-advance then move through [queue]. */
    fun play(pieceId: Long, queue: List<Long> = listOf(pieceId)) {
        playlist = Playlist.startingAt(pieceId, queue)
        startCurrent()
    }

    fun togglePlayPause() {
        if (state.value.status == PlaybackStatus.Playing) pause() else resume()
    }

    fun resume() = command { engine.play(it) }

    fun pause() = command { engine.pause(it) }

    fun stop() {
        loadJob?.cancel()
        command { engine.stop(it) }
    }

    fun seek(micros: Long) = command { engine.seek(micros, it) }

    fun next() {
        if (!playlist.hasNext) return
        playlist = playlist.next()
        startCurrent()
    }

    /** Restarts the piece when more than 3 s in (or first in the queue), else plays the one before. */
    fun previous() {
        if (playlist.previousRestarts(positionMicrosNow())) {
            seek(0L)
        } else {
            playlist = playlist.previous()
            startCurrent()
        }
    }

    /** Live tempo, 25-200 %. */
    fun setTempo(pct: Int) = scheduler.submit { engine.setTempo(pct, it) }

    /** The tempo each new piece starts at. */
    fun setDefaultTempo(pct: Int) {
        defaultTempoPct = pct.coerceIn(PlaybackEngine.MIN_TEMPO_PCT, PlaybackEngine.MAX_TEMPO_PCT)
    }

    fun setTranspose(semitones: Int) = configure { copy(transpose = semitones.coerceIn(-12, 12)) }

    fun setVelocity(pct: Int) = configure { copy(velocityPct = pct.coerceIn(50, 150)) }

    fun setFold(fold: Boolean) = configure { copy(fold = fold) }

    fun setSkipDrums(skip: Boolean) = configure { copy(skipDrums = skip) }

    /** Stops, then waits until the stop sequence has been written: for service teardown. Blocks up to [timeoutMs]. */
    fun stopAndFlush(timeoutMs: Long): Boolean = settleAndFlush(timeoutMs) { engine.stop(it) }

    /** Pauses, then waits until the stop sequence has been written: before a user disconnect. Blocks up to [timeoutMs]. */
    fun pauseAndFlush(timeoutMs: Long): Boolean = settleAndFlush(timeoutMs) { engine.pause(it) }

    private fun settleAndFlush(timeoutMs: Long, action: (Long) -> Unit): Boolean {
        advanceJob?.cancel()
        loadJob?.cancel()
        val deadline = clock.nanoTime() + timeoutMs * NANOS_PER_MS
        if (!scheduler.submitAndWait(timeoutMs, action)) return false
        return link.flush(((deadline - clock.nanoTime()) / NANOS_PER_MS).coerceAtLeast(0L))
    }

    private fun command(action: (Long) -> Unit) {
        advanceJob?.cancel()
        scheduler.submit(action)
    }

    private fun configure(change: PlayerState.() -> PlayerState) {
        val s = _state.updateAndGet(change)
        scheduler.submit {
            engine.router.transpose = s.transpose
            engine.router.velocityPct = s.velocityPct
            engine.router.fold = s.fold
            engine.router.skipDrums = s.skipDrums
        }
    }

    private fun startCurrent() {
        advanceJob?.cancel()
        loadJob?.cancel()
        val list = playlist
        val id = list.current ?: return
        _state.update { it.copy(loading = true, problem = null, queueIndex = list.index, queueSize = list.pieceIds.size) }
        loadJob = scope.launch {
            val playable = try {
                withContext(io) { source.load(id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scheduler.submit { engine.eject() }
                _state.update { it.copy(loading = false, piece = null, problem = e.message ?: CANT_PLAY) }
                return@launch
            }
            val midi = playable.midi
            val tempo = defaultTempoPct
            _state.update {
                it.copy(loading = false, piece = NowPlaying(playable.id, playable.title, playable.composer, midi.durationMicros, midi.notes))
            }
            scheduler.submit { now ->
                engine.load(midi, now)
                engine.setTempo(tempo, now)
                engine.play(now)
            }
            withContext(io) { source.markPlayed(id) }
        }
    }

    /** After a piece ends: the next one in the queue, 1.5 s later. */
    private fun autoAdvance() {
        if (!playlist.hasNext) return
        advanceJob?.cancel()
        advanceJob = scope.launch {
            delay(AUTO_ADVANCE_DELAY_MS)
            playlist = playlist.next()
            startCurrent()
        }
    }

    private fun onLinkState(linkState: LinkState) {
        val connected = linkState is LinkState.Connected
        if (connected != linkConnected && state.value.status == PlaybackStatus.Playing) {
            if (connected) scheduler.submit { engine.resync(it) } else pause()   // the piano silences itself on a drop
        }
        linkConnected = connected
    }

    /** Scheduler thread, after every step: mirrors the engine into what the UI reads. */
    private fun publish() {
        activeLow.set(engine.router.activeLow)
        activeHigh.set(engine.router.activeHigh)
        val status = engine.status
        val running = status == PlaybackStatus.Playing
        val duration = engine.piece?.durationMicros ?: 0L
        val shown = position
        if (shown.anchorSongMicros != engine.anchorSongMicros || shown.anchorNanos != engine.anchorNanos ||
            shown.tempoPct != engine.tempoPct || shown.running != running || shown.durationMicros != duration
        ) {
            position = PositionClock(engine.anchorSongMicros, engine.anchorNanos, engine.tempoPct, running, duration)
        }
        if (status != shownStatus || engine.tempoPct != shownTempo) {
            shownStatus = status
            shownTempo = engine.tempoPct
            _state.update { it.copy(status = status, tempoPct = engine.tempoPct) }
        }
        if (engine.ended != shownEnded) {
            shownEnded = engine.ended
            if (shownEnded) scope.launch { autoAdvance() }
        }
    }

    /** The engine's timeline as last published, readable from any thread without allocating. */
    private class PositionClock(
        val anchorSongMicros: Long,
        val anchorNanos: Long,
        val tempoPct: Int,
        val running: Boolean,
        val durationMicros: Long,
    ) {
        fun at(nanos: Long): Long {
            val micros = if (running) anchorSongMicros + (nanos - anchorNanos) * tempoPct / 100_000L else anchorSongMicros
            return micros.coerceIn(0L, durationMicros)
        }

        companion object {
            val Zero = PositionClock(0L, 0L, 100, false, 0L)
        }
    }

    private companion object {
        const val AUTO_ADVANCE_DELAY_MS = 1_500L
        const val NANOS_PER_MS = 1_000_000L
        const val CANT_PLAY = "This piece can't be played."
    }
}

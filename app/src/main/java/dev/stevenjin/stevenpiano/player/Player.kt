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
import dev.stevenjin.stevenpiano.channels.ChannelDeck
import dev.stevenjin.stevenpiano.midi.MidiPiece
import dev.stevenjin.stevenpiano.midi.NoteList
import dev.stevenjin.stevenpiano.score.ChordTrack
import dev.stevenjin.stevenpiano.score.Chords
import dev.stevenjin.stevenpiano.score.Fingering
import dev.stevenjin.stevenpiano.score.Hands
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Where the player gets pieces: the library, or a fake in tests. */
interface PieceSource {
    /** Reads and parses a piece; throws with a plain-English message when it can't be played. */
    suspend fun load(pieceId: Long): PlayablePiece

    suspend fun markPlayed(pieceId: Long)
}

/** A piece read for playing; [composerKey] (`PieceEntity.composerKey`, "" when unknown) finds the composer's art. */
class PlayablePiece(val id: Long, val title: String, val composer: String, val midi: MidiPiece, val composerKey: String = "")

/**
 * Playback for the whole app: a process singleton in AppGraph. Call it on [scope]'s thread
 * (the main thread). The engine runs on the [Scheduler] thread and reports back through
 * [state], plus allocation-free [positionMicrosAt] and active-key bitsets for per-frame drawing.
 * When the link drops mid-piece the player pauses; when it comes back, Play resumes.
 * Pieces play from a [Queue]: Next, Previous and the end of a piece move through it, Up next
 * edits it, and its shuffle and repeat modes live with it ([random] shuffles; tests pass their own).
 * The Keys screen plays through here too ([liveNoteOn] and friends): its keys join the queue of
 * scheduler commands and go out through the engine's router, so they share the piece's
 * reference counts, 100 ms guard and silence. A dropped link lets go of them.
 * Each piece's hands, suggested fingering and chord names are worked out on [compute] as it loads,
 * before it is shown and played; the fingering again when transpose or folding changes the keys played.
 * A piece or a fingering replaced while it is worked out stops at the analyses' next checkpoint, and a
 * start that replaces one still running waits [SETTLE_MS] first, so a burst of Next taps or transpose
 * steps reads and works out only the first and the last (the v1.3 delta audit, L1).
 * Every piece that starts (a tap, the end of the one before, Repeat one) begins with the pause
 * before each piece ([setPreRoll]; DESIGN.md › v1.5 — M16): the position runs below zero and the
 * piano stays silent until it ends. Resuming never pauses first, and a seek ends the pause.
 * A channel (DESIGN.md › v1.5 — M17) is a queue [playAll] started with its key: [PlayerState.channel]
 * says so while Next, Previous, the end of a piece, Play next and Add to queue move through it (what
 * the person adds joins the channel), and the channel's own [addToQueue] tops it up. It ends
 * (null) when a piece is played from a list ([play]), a list is played without a channel, the person
 * skips to an entry the channel did not deal, or playback stops ([stop], [stopAndFlush],
 * [leaveChannel]: the notification's dismiss).
 * While the piano's firmware is updated (v1.6.1 — M21) the player is [lock]ed: nothing starts,
 * resumes, seeks or sounds from the Keys screen until [unlock], and the reason shows as the
 * [PlayerState.problem]; [stopQuietly] stops and waits for the stop sequence to be written first.
 */
class Player(
    private val link: PianoLink,
    private val source: PieceSource,
    private val scope: CoroutineScope,
    private val clock: NanoClock = NanoClock.System,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    prepareThread: () -> Unit = Scheduler.UrgentAudio,
    private val random: Random = Random.Default,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ChannelDeck {
    private val engine = PlaybackEngine(link)
    private val scheduler = Scheduler(engine, clock, ::publish, prepareThread)
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    @Volatile
    private var position = PositionClock.Zero
    private val activeLow = AtomicLong()
    private val activeHigh = AtomicLong()
    private val _liveSustain = MutableStateFlow(false)

    /** Whether the Keys screen's sustain is down, as the engine last left it (a full silence lifts it). */
    val liveSustain: StateFlow<Boolean> = _liveSustain.asStateFlow()

    // Owned by the scope's thread.
    private var queue = Queue.Empty
    private var defaultTempoPct = 100
    private var preRollMs = 0L
    private var loadJob: Job? = null
    private var advanceJob: Job? = null
    private var fingerJob: Job? = null
    private var linkConnected = false
    private var linkEpoch: Long? = null

    /** The queue entries the playing channel dealt (its first pieces and its top-ups); empty without a channel. */
    private var channelUids: Set<Long> = emptySet()

    /** Why nothing may play now ([lock]), or null. */
    @Volatile
    private var lockedFor: String? = null

    /** Whether the player is [lock]ed. */
    val locked: Boolean get() = lockedFor != null

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

    /** Plays [pieceId]; Next, Previous and auto-advance then move through [queue] (shuffled when shuffle is on). A channel ends. */
    fun play(pieceId: Long, queue: List<Long> = listOf(pieceId)) {
        if (locked) return
        setQueue(Queue.startingAt(pieceId, queue, this.queue, random), channel = null)
        startCurrent()
    }

    /**
     * A playlist's Play (in order) or Shuffle (random order, and shuffle stays on) button; or with
     * [channel], a channel's first pieces (dealt in the order given): the channel plays until
     * something else takes over.
     */
    override fun playAll(pieceIds: List<Long>, shuffle: Boolean, channel: String?) {
        if (pieceIds.isEmpty() || locked) return
        val all = Queue.all(pieceIds, shuffle, queue, random)
        channelUids = if (channel == null) emptySet() else all.entries.mapTo(HashSet()) { it.uid }
        // The queue and its channel in one update: a watcher never sees the new queue without its channel.
        setQueue(all, channel)
        startCurrent()
    }

    /** Queues [pieceIds] right after the current piece (a channel plays on around them). With nothing in the queue yet they start playing: true then. */
    fun playNext(pieceIds: List<Long>): Boolean = enqueue(pieceIds) { it.playNext(pieceIds) }

    /**
     * Queues [pieceIds] at the end (a channel plays on after them). With nothing in the queue yet
     * they start playing: true then. [channel] marks them as that channel's own top-up while it plays.
     */
    override fun addToQueue(pieceIds: List<Long>, channel: String?): Boolean {
        val before = queue.nextUid
        val started = enqueue(pieceIds) { it.addToQueue(pieceIds) }
        if (!started && channel != null && channel == _state.value.channel) channelUids = channelUids + (before until queue.nextUid)
        return started
    }

    /** The notification was dismissed while paused: the channel is over (the queue stays as it was). */
    fun leaveChannel() = setChannel(null)

    /** Takes an up-next entry out of the queue. */
    fun removeFromQueue(uid: Long) = setQueue(queue.remove(uid))

    /** Up next's drag: entry [uid] to place [toIndex] among the pieces up next. */
    fun moveInQueue(uid: Long, toIndex: Int) = setQueue(queue.move(uid, toIndex))

    /** Up next's Clear: the current piece plays on, nothing follows it. */
    fun clearUpNext() = setQueue(queue.clearUpNext())

    /** Plays queue entry [uid] now; the entries skipped stay behind it. An entry the playing channel did not deal ends the channel. */
    fun skipToQueueEntry(uid: Long) {
        if (locked) return
        val skipped = queue.skipTo(uid)
        if (skipped === queue) return
        setQueue(skipped, channel = if (uid in channelUids) _state.value.channel else null)
        startCurrent()
    }

    /** Shuffle on: the current piece plays on and the rest follow in random order. Off: the order comes back. */
    fun setShuffle(on: Boolean) = setQueue(queue.withShuffle(on, random))

    fun setRepeat(mode: RepeatMode) = setQueue(queue.withRepeat(mode))

    fun togglePlayPause() {
        if (state.value.status == PlaybackStatus.Playing) pause() else resume()
    }

    fun resume() {
        if (locked) return
        command { engine.play(it) }
    }

    fun pause() = command { engine.pause(it) }

    override fun stop() {
        setChannel(null)
        loadJob?.cancel()
        command { engine.stop(it) }
    }

    fun seek(micros: Long) {
        if (locked) return
        command { engine.seek(micros, it) }
    }

    fun next() {
        if (!queue.hasNext || locked) return
        setQueue(queue.next())
        startCurrent()
    }

    /** Restarts the piece when more than 3 s in (or first in the queue), else plays the one before. */
    fun previous() {
        if (locked) return
        if (queue.previousRestarts(positionMicrosNow())) {
            seek(0L)
        } else {
            setQueue(queue.previous())
            startCurrent()
        }
    }

    /** A key pressed on the Keys screen ([key] 24-107): sent at once, with the velocity percentage applied. */
    fun liveNoteOn(key: Int, velocity: Int) {
        if (locked) return
        scheduler.submit { engine.liveNoteOn(key, velocity, it) }
    }

    /** A key let go on the Keys screen. */
    fun liveNoteOff(key: Int) = scheduler.submit { engine.liveNoteOff(key) }

    /** The Keys screen's latching sustain: CC64 127 or 0. */
    fun liveSustain(down: Boolean) {
        if (down && locked) return
        scheduler.submit { engine.liveSustain(down) }
    }

    /** Lets go of every key the Keys screen holds, and its sustain; a playing piece keeps its keys. */
    fun silenceLive() = scheduler.submit { engine.silenceLive() }

    /** Live tempo, 25-200 %. */
    fun setTempo(pct: Int) = scheduler.submit { engine.setTempo(pct, it) }

    /** The tempo each new piece starts at. */
    fun setDefaultTempo(pct: Int) {
        defaultTempoPct = pct.coerceIn(PlaybackLimits.TempoPct)
    }

    /**
     * The silence before each piece starts, in milliseconds (0-5000; Piano › Playback). It is part of
     * the gap between two pieces, which is the longer of it and [AUTO_ADVANCE_DELAY_MS].
     */
    fun setPreRoll(ms: Int) {
        preRollMs = ms.coerceIn(PlaybackLimits.PreRollMs).toLong()
    }

    fun setTranspose(semitones: Int) = configure { copy(transpose = semitones.coerceIn(PlaybackLimits.Transpose)) }

    override fun setVelocity(pct: Int) = configure { copy(velocityPct = pct.coerceIn(PlaybackLimits.VelocityPct)) }

    fun setFold(fold: Boolean) = configure { copy(fold = fold) }

    fun setSkipDrums(skip: Boolean) = configure { copy(skipDrums = skip) }

    /** Stops, then waits until the stop sequence has been written. Blocks up to [timeoutMs]: for service teardown. A channel ends. */
    fun stopAndFlush(timeoutMs: Long): Boolean {
        setChannel(null)
        advanceJob?.cancel()
        loadJob?.cancel()
        return settle(timeoutMs) { engine.stop(it) }
    }

    /**
     * Nothing plays from now until [unlock] (a firmware update, v1.6.1 — M21): pieces, resume,
     * seek, Next and Previous, channels, the web panel and the Keys screen's keys are turned away,
     * and [reason] shows as the state's problem, where Now playing shows playback's problems. It
     * does not stop what plays: [stopQuietly] does.
     */
    fun lock(reason: String) {
        lockedFor = reason
        _state.update { it.copy(problem = reason) }
    }

    /** The lock is over; its problem line goes (a later problem stays). */
    fun unlock() {
        val reason = lockedFor ?: return
        lockedFor = null
        _state.update { if (it.problem == reason) it.copy(problem = null) else it }
    }

    /**
     * Stops (a channel ends, the Keys screen's keys let go), then waits off the main thread until
     * the stop sequence has been written to the piano, [timeoutMs] at most: before a firmware update.
     */
    suspend fun stopQuietly(timeoutMs: Long): Boolean {
        setChannel(null)
        advanceJob?.cancel()
        loadJob?.cancel()
        return withContext(io) { settle(timeoutMs) { engine.stop(it) } }
    }

    /** Pauses, then waits (off the main thread) until the stop sequence has been written: before a user disconnect. */
    suspend fun pauseAndFlush(timeoutMs: Long): Boolean {
        advanceJob?.cancel()
        return withContext(io) { settle(timeoutMs) { engine.pause(it) } }
    }

    private fun settle(timeoutMs: Long, action: (Long) -> Unit): Boolean {
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
        refinger()
    }

    /**
     * The fingering follows the keys played: when transpose or folding no longer match the ones the
     * piece's fingering was worked out for, it is worked out again on [compute] (meanwhile none shows).
     */
    private fun refinger() {
        val s = _state.value
        val piece = s.piece ?: return
        if (piece.fingersTranspose == s.transpose && piece.fingersFold == s.fold) return
        val hands = piece.handsOrNull ?: return
        val transpose = s.transpose
        val fold = s.fold
        val superseding = fingerJob?.isActive == true
        fingerJob?.cancel()
        fingerJob = scope.launch {
            if (superseding) delay(SETTLE_MS)   // the steps keep coming: work out only the last
            val fingers = withContext(compute) { fingersOf(piece.notes, hands, transpose, fold, checkpoint()) }
            _state.update { now ->
                val shown = now.piece
                if (shown == null || shown.notes !== piece.notes) now
                else now.copy(piece = shown.copy(fingers = fingers, fingersTranspose = transpose, fingersFold = fold))
            }
        }
    }

    private fun enqueue(pieceIds: List<Long>, change: (Queue) -> Queue): Boolean {
        if (pieceIds.isEmpty()) return false
        if (queue.current == null) {
            if (locked) return false
            play(pieceIds.first(), pieceIds)
            return true
        }
        setQueue(change(queue))
        return false
    }

    /** The channel playing, or none: seen at once. */
    private fun setChannel(channel: String?) {
        if (channel == null) channelUids = emptySet()
        _state.update { if (it.channel == channel) it else it.copy(channel = channel) }
    }

    /** The queue changed (and with [channel], the channel with it): the UI, the service and the media session see it at once. */
    private fun setQueue(changed: Queue, channel: String? = _state.value.channel) {
        queue = changed
        if (channel == null) channelUids = emptySet()
        val snapshot = changed.snapshot()
        _state.update { if (it.queue == snapshot && it.channel == channel) it else it.copy(queue = snapshot, channel = channel) }
    }

    private fun startCurrent() {
        if (locked) return
        advanceJob?.cancel()
        val superseding = loadJob?.isActive == true
        loadJob?.cancel()
        val id = queue.current?.pieceId ?: return
        _state.update { it.copy(loading = true, problem = null, queue = queue.snapshot()) }
        loadJob = scope.launch {
            if (superseding) delay(SETTLE_MS)   // the taps keep coming: read and work out only the last
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
            val transpose = _state.value.transpose
            val fold = _state.value.fold
            // The chords while the hands and then the fingering are worked out: a long performance takes
            // tens of milliseconds for each on a phone.
            val (hands, fingers, chords) = withContext(compute) {
                coroutineScope {
                    val chords = async { chordsOf(midi, checkpoint()) }
                    val hands = handsOf(midi, checkpoint())
                    Triple(hands, fingersOf(midi.notes, hands, transpose, fold, checkpoint()), chords.await())
                }
            }
            _state.update {
                it.copy(
                    loading = false,
                    piece = NowPlaying(
                        pieceId = playable.id,
                        title = playable.title,
                        composer = playable.composer,
                        composerKey = playable.composerKey,
                        durationMicros = midi.durationMicros,
                        notes = midi.notes,
                        tempoMap = midi.tempoMap,
                        barStartsMicros = midi.barStartsMicros,
                        keySignatures = midi.keySignatures,
                        timeSignatures = midi.timeSignatures,
                        hands = hands,
                        fingers = fingers,
                        fingersTranspose = transpose,
                        fingersFold = fold,
                        chords = chords,
                    ),
                )
            }
            refinger()   // transpose or folding changed while it loaded
            val preRoll = preRollMs * NANOS_PER_MS
            scheduler.submit { now ->
                engine.load(midi, now)
                engine.setTempo(tempo, now)
                engine.play(now, preRoll)
            }
            withContext(io) { source.markPlayed(id) }
        }
    }

    /**
     * The piece's hands; none if working them out fails (the score then splits the staves at middle C
     * and the waterfall fills every bar): a suggestion must never stop a piece from playing. Stops (by
     * [CancellationException]) at [checkpoint] once the piece is replaced.
     */
    private fun handsOf(midi: MidiPiece, checkpoint: () -> Unit): ByteArray = try {
        Hands.assign(midi.notes, midi.trackNames, midi.tempoMap, midi.timeSignatures, checkpoint)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ByteArray(0)
    } catch (e: OutOfMemoryError) {
        ByteArray(0)
    }

    /** The suggested fingering on the keys played at [transpose] and [fold]; none without hands or if it fails. */
    private fun fingersOf(notes: NoteList, hands: ByteArray, transpose: Int, fold: Boolean, checkpoint: () -> Unit): ByteArray {
        if (hands.size != notes.size || hands.isEmpty()) return ByteArray(0)
        return try {
            Fingering.assign(notes, hands, transpose, fold, checkpoint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ByteArray(0)
        } catch (e: OutOfMemoryError) {
            ByteArray(0)
        }
    }

    /** The piece's chord names; none if finding them fails. */
    private fun chordsOf(midi: MidiPiece, checkpoint: () -> Unit): ChordTrack = try {
        Chords.detect(midi.notes, midi.tempoMap, midi.barStartsMicros, midi.timeSignatures, midi.keySignatures, checkpoint)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ChordTrack.Empty
    } catch (e: OutOfMemoryError) {
        ChordTrack.Empty
    }

    /** A checkpoint for the analyses running in this scope: it throws once the scope's job is cancelled. */
    private fun CoroutineScope.checkpoint(): () -> Unit {
        val context = coroutineContext
        return { context.ensureActive() }
    }

    /**
     * After a piece ends, whatever the queue says follows it (asked again then, as Up next or the
     * modes may have changed meanwhile): the next piece, or with Repeat one the same piece again
     * from the top, already loaded, without reading it again. The pause before each piece is part
     * of the gap, so the wait here is what is left of 1.5 s after it: the gap is the longer of the
     * two (2 s with the default pause, never 3.5 s).
     */
    private fun autoAdvance() {
        if (queue.afterEnd() == null) return
        advanceJob?.cancel()
        advanceJob = scope.launch {
            delay((AUTO_ADVANCE_DELAY_MS - preRollMs).coerceAtLeast(0L))
            val ended = queue.current ?: return@launch
            val after = queue.afterEnd() ?: return@launch
            setQueue(after)
            if (after.current?.uid == ended.uid) restartCurrent(ended.pieceId) else startCurrent()
        }
    }

    /** The piece that just ended plays again from its start, after the pause before each piece: the engine still has it. */
    private fun restartCurrent(pieceId: Long) {
        if (locked) return
        val preRoll = preRollMs * NANOS_PER_MS
        scheduler.submit { now ->
            engine.seek(0L, now)
            engine.play(now, preRoll)
        }
        scope.launch { withContext(io) { source.markPlayed(pieceId) } }
    }

    /**
     * A drop pauses a piece (the piano silences itself); coming back leaves Play to the person. A new
     * connection seen while still connected (a drop and a reconnection too quick to see), or the link
     * losing a packet, shows as a new epoch: playing, the piano is re-synced (silence, then the pedal).
     */
    private fun onLinkState(linkState: LinkState) {
        val connected = linkState is LinkState.Connected
        val epoch = (linkState as? LinkState.Connected)?.epoch
        if (connected != linkConnected) {
            if (!connected) silenceLive()   // the piano lets go on a drop; forget the Keys screen's keys too
            if (state.value.status == PlaybackStatus.Playing) {
                if (connected) scheduler.submit { engine.resync(it) } else pause()   // the piano silences itself on a drop
            }
        } else if (connected && epoch != linkEpoch && state.value.status == PlaybackStatus.Playing) {
            scheduler.submit { engine.resync(it) }
        }
        linkConnected = connected
        if (connected) linkEpoch = epoch
    }

    /** Scheduler thread, after every step: mirrors the engine into what the UI reads. */
    private fun publish() {
        activeLow.set(engine.router.activeLow)
        activeHigh.set(engine.router.activeHigh)
        _liveSustain.value = engine.router.liveSustainDown
        val status = engine.status
        val running = status == PlaybackStatus.Playing
        val duration = engine.piece?.durationMicros ?: 0L
        val shown = position
        if (shown.anchorSongMicros != engine.anchorSongMicros || shown.anchorNanos != engine.anchorNanos ||
            shown.tempoPct != engine.tempoPct || shown.running != running || shown.durationMicros != duration ||
            shown.startMicros != engine.startMicros
        ) {
            position = PositionClock(engine.anchorSongMicros, engine.anchorNanos, engine.tempoPct, running, duration, engine.startMicros)
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

    /**
     * The engine's timeline as last published, readable from any thread without allocating. While
     * playing, a frame reads between where this run began ([startMicros]: below zero during the pause
     * before a piece, down to minus the pause at the tempo) and the piece's end, so a frame timed a
     * moment before the anchor never shows a position the piece was not at; otherwise the held
     * position, never below zero.
     */
    private class PositionClock(
        val anchorSongMicros: Long,
        val anchorNanos: Long,
        val tempoPct: Int,
        val running: Boolean,
        val durationMicros: Long,
        val startMicros: Long,
    ) {
        fun at(nanos: Long): Long {
            if (!running) return anchorSongMicros.coerceIn(0L, durationMicros)
            val micros = anchorSongMicros + (nanos - anchorNanos) * tempoPct / 100_000L
            return maxOf(minOf(micros, durationMicros), minOf(startMicros, durationMicros))
        }

        companion object {
            val Zero = PositionClock(0L, 0L, 100, false, 0L, 0L)
        }
    }

    private companion object {
        const val AUTO_ADVANCE_DELAY_MS = 1_500L
        const val NANOS_PER_MS = 1_000_000L
        const val CANT_PLAY = "This piece can't be played."

        /** A start that replaces one still running waits this long first: a burst of taps settles on its last. */
        const val SETTLE_MS = 150L
    }
}

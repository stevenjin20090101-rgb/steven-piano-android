// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.player

import dev.stevenjin.stevenpiano.midi.InstrumentProfile
import dev.stevenjin.stevenpiano.midi.KeyEvents
import dev.stevenjin.stevenpiano.midi.MidiBatch
import dev.stevenjin.stevenpiano.midi.MidiPiece
import dev.stevenjin.stevenpiano.midi.MidiSink
import dev.stevenjin.stevenpiano.midi.NoteRouter

/**
 * The pure core of playback. The song position comes from the caller's clock:
 * `song = anchorSong + (now - anchorNanos) * tempo / 100`, re-anchored on play and on every
 * tempo change so nothing jumps. [advance] sends everything due, as one batch, and says when
 * the next event is due. Pause, seek and stop silence the piano first; play and seek then
 * re-send the pedal in effect. [play] may start with a pause before the piece (DESIGN.md ›
 * v1.5 — M16): the anchor lies that far ahead, so the position runs below zero until it and the
 * first event leaves exactly then; a pause inside it resumes from the start, a seek ends it. Keys played on the Keys screen go out at once through the same
 * [router] ([liveNoteOn] and friends), so a piece and the keys share its bookkeeping; a full
 * silence lets go of both. A pedal change the router holds back (it paces the pedal) is due by
 * the time [advance] returns, also with nothing playing (v1.11 — M29: a keyboard's pedal moves while
 * the piece is stopped, and its last value must not be stranded). Live keys go out through the sink's
 * live lane ([MidiSink.sendLive]), ahead of a piece's backlog. Not thread-safe: one thread (the
 * scheduler's) owns it.
 */
class PlaybackEngine(private val sink: MidiSink, val router: NoteRouter = NoteRouter()) {
    var status: PlaybackStatus = PlaybackStatus.Stopped
        private set
    var piece: MidiPiece? = null
        private set
    var tempoPct: Int = 100
        private set
    var anchorSongMicros: Long = 0L
        private set
    var anchorNanos: Long = 0L
        private set

    /** True once the piece has played to its end, until the next load, play or seek. */
    var ended: Boolean = false
        private set

    /**
     * Where this run of playback began: the lowest position it reports. Below zero while a pause
     * before the piece runs (by as much song time as the pause lasts at the tempo), otherwise the
     * position it was anchored at; the player never shows a frame before it.
     */
    var startMicros: Long = 0L
        private set

    private var cursor = 0
    private val batch = MidiBatch()

    /** How late this run's events went out (v1.7 — M23): the player writes each run's figures to the link's trail. */
    val timing = PlaybackTiming()

    /** How long a keyboard's keys took from arriving to leaving for the instrument (v1.11 — M29), one run per Live session. */
    val liveTiming = LiveTiming()

    /** The song position at [nowNanos]; below zero while the pause before a piece runs. */
    fun positionMicros(nowNanos: Long): Long =
        if (status == PlaybackStatus.Playing) anchorSongMicros + (nowNanos - anchorNanos) * tempoPct / NANOS_PER_MICRO_PCT
        else anchorSongMicros

    /** Makes [midi] current, stopped at its start. */
    fun load(midi: MidiPiece, nowNanos: Long) {
        if (status != PlaybackStatus.Stopped) silence()
        timing.close()
        piece = midi
        status = PlaybackStatus.Stopped
        anchorSongMicros = 0L
        startMicros = 0L
        cursor = 0
        ended = false
    }

    /** Forgets the piece, silencing the piano first if it was sounding. */
    fun eject() {
        if (status != PlaybackStatus.Stopped) silence()
        timing.close()
        piece = null
        status = PlaybackStatus.Stopped
        anchorSongMicros = 0L
        startMicros = 0L
        cursor = 0
        ended = false
    }

    /**
     * Plays from where the piece is. With [preRollNanos] (a new piece, or one starting again) the
     * piano stays silent that long first: the anchor is set that far ahead, the position runs
     * below zero until it, and the first event is due exactly at `now + preRoll`.
     */
    fun play(nowNanos: Long, preRollNanos: Long = 0L) {
        val midi = piece ?: return
        if (status == PlaybackStatus.Playing) return
        if (cursor >= midi.events.size) {   // over: play again from the top
            anchorSongMicros = 0L
            cursor = 0
        }
        ended = false
        anchorNanos = nowNanos + preRollNanos.coerceAtLeast(0L)
        status = PlaybackStatus.Playing
        startMicros = positionMicros(nowNanos)
        restorePedal(nowNanos)
    }

    /** Silences and holds the position; a pause inside the pause before a piece holds its start, so Play begins it at once. */
    fun pause(nowNanos: Long) {
        if (status != PlaybackStatus.Playing) return
        anchorSongMicros = positionMicros(nowNanos).coerceAtLeast(0L)
        startMicros = anchorSongMicros
        status = PlaybackStatus.Paused
        silence()
    }

    /**
     * The piano was connected anew, or the link lost a packet (a new epoch): playing, the piano is
     * re-synced ([resync]: silence, then the pedal where the music is); otherwise the stop sequence goes
     * at once (v1.11 — M29), so nothing the piano kept from before (a stored pedal, a key) outlives the
     * old connection, and a piece loaded stays where it was.
     */
    fun connectedAnew(nowNanos: Long) {
        if (status == PlaybackStatus.Playing) resync(nowNanos) else silence()
    }

    /**
     * All keys off (v1.11 — M29, the Instrument page): the instrument's stop sequence at once, every key forgotten; a
     * piece playing goes on from where it is, its next notes sounding as they come.
     */
    fun allKeysOff() = silence()

    /** Always silences, even when idle: it is also the app's panic button. */
    fun stop(nowNanos: Long) {
        timing.close()
        status = PlaybackStatus.Stopped
        anchorSongMicros = 0L
        startMicros = 0L
        cursor = 0
        ended = false
        silence()
    }

    /**
     * Silences, then continues from [targetMicros]; a stopped piece becomes paused there. A seek
     * ends the pause before a piece: seeking does not pause, it plays from the target at once.
     */
    fun seek(targetMicros: Long, nowNanos: Long) {
        val midi = piece ?: return
        silence()
        anchorSongMicros = targetMicros.coerceIn(0L, midi.durationMicros)
        startMicros = anchorSongMicros
        anchorNanos = nowNanos
        cursor = midi.events.firstAtOrAfter(anchorSongMicros)
        ended = false
        if (status == PlaybackStatus.Stopped) status = PlaybackStatus.Paused
        if (status == PlaybackStatus.Playing) restorePedal(nowNanos)
    }

    /**
     * Silences and re-sends the pedal where playback is, e.g. after the link reconnects. Inside the
     * pause before a piece nothing has sounded yet: the piano is silenced and the pause runs on.
     */
    fun resync(nowNanos: Long) {
        val position = positionMicros(nowNanos)
        if (status == PlaybackStatus.Playing && position < 0L) {
            silence()
            return
        }
        seek(position, nowNanos)
    }

    /** Re-anchors at the position now, so nothing jumps; inside the pause before a piece it scales what is left of it. */
    fun setTempo(pct: Int, nowNanos: Long) {
        anchorSongMicros = positionMicros(nowNanos)
        anchorNanos = nowNanos
        startMicros = anchorSongMicros
        tempoPct = pct.coerceIn(PlaybackLimits.TempoPct)
    }

    /** A key pressed on the Keys screen, sent now. */
    fun liveNoteOn(key: Int, velocity: Int, nowNanos: Long) = live { router.liveNoteOn(key, velocity, nowNanos / 1000, it) }

    /** A key let go on the Keys screen, sent now. */
    fun liveNoteOff(key: Int) = live { router.liveNoteOff(key, it) }

    /** The Keys screen's sustain, sent now. */
    fun liveSustain(down: Boolean) = live { router.liveSustain(down, it) }

    /** Lets go of the Keys screen's keys and its sustain; the piece's keys stay down. */
    fun silenceLive() = live { router.silenceLive(it) }

    /**
     * One buffer of a MIDI keyboard's events (v1.11 — M29), packed as [KeyEvents] packs them, [count] of
     * them, sent now as one batch through the live lane: keys and pedals as the keyboard played them.
     * [arrivalNanos]: when the buffer arrived, for [liveTiming].
     */
    fun external(events: IntArray, count: Int, arrivalNanos: Long, nowNanos: Long) {
        val nowMicros = nowNanos / 1000
        var downs = 0
        live { out ->
            for (i in 0 until count) {
                val event = events[i]
                val key = (event ushr 8) and 0xFF
                val value = event and 0xFF
                when (event ushr 16) {
                    KeyEvents.DOWN -> {
                        downs++
                        router.externalNoteOn(key, value, nowMicros, out)
                    }
                    KeyEvents.UP -> router.externalNoteOff(key, out)
                    KeyEvents.PEDAL -> router.externalPedal(key, value, nowMicros, out)
                }
            }
        }
        if (downs > 0) liveTiming.record(downs, nowNanos - arrivalNanos)
    }

    /** Lets go of the keyboard's keys and puts its pedal back (v1.11 — M29); the piece's and the screen's stay. Ends the Live run. */
    fun silenceExternal() {
        live { router.silenceExternal(it) }
        liveTiming.close()
    }

    /**
     * Another instrument plays from now on (v1.11 — M29): the old one is silenced first, as its profile
     * says, then the router takes [profile]; a piece playing goes on, re-synced (its pedal again).
     */
    fun setProfile(profile: InstrumentProfile, nowNanos: Long) {
        if (router.profile === profile) return
        silence()
        router.profile = profile
        if (status == PlaybackStatus.Playing) restorePedal(nowNanos)
    }

    private inline fun live(route: (MidiBatch) -> Unit) {
        batch.clear()
        route(batch)
        if (!batch.isEmpty()) sink.sendLive(batch)
    }

    /**
     * Sends every event due at [nowNanos]; returns when the next one is due ([Long.MAX_VALUE]: nothing to wait for).
     * With nothing playing, a pedal change the router held back still goes when its turn comes.
     */
    fun advance(nowNanos: Long): Long {
        val midi = piece
        if (status != PlaybackStatus.Playing || midi == null) return flushIdlePedal(nowNanos)
        val position = positionMicros(nowNanos)
        val events = midi.events
        val nowMicros = nowNanos / 1000
        batch.clear()
        router.flushPedal(nowMicros, batch)   // a pedal change the router held back, if its turn has come
        val first = cursor
        while (cursor < events.size && events.atMicros(cursor) <= position) {
            router.route(events.status(cursor), events.data1(cursor), events.data2(cursor), nowMicros, batch)
            cursor++
        }
        // The batch's first event was due earliest: how late it goes is how late the batch is (in real time).
        if (cursor > first) timing.record(cursor - first, (position - events.atMicros(first)) * 100 / tempoPct, events.atMicros(first))
        if (cursor < events.size) {
            send(dropPending = false)
            return minOf(wakeTimeFor(events.atMicros(cursor)), pedalWakeTime())
        }
        router.silence(batch)   // the end: last releases, then the stop sequence
        send(dropPending = false)
        timing.close()
        anchorSongMicros = midi.durationMicros
        status = PlaybackStatus.Stopped
        ended = true
        return Long.MAX_VALUE
    }

    private fun wakeTimeFor(songMicros: Long): Long = wakeTime(songMicros, anchorSongMicros, anchorNanos, tempoPct)

    /** When a pedal change the router is holding back may go ([Long.MAX_VALUE]: none is). */
    private fun pedalWakeTime(): Long = router.pedalDueMicros.let { if (it == Long.MAX_VALUE) it else it * 1000 }

    /** Nothing playing: a pedal change the router holds back goes once its turn has come; returns when to look again. */
    private fun flushIdlePedal(nowNanos: Long): Long {
        if (router.pedalDueMicros == Long.MAX_VALUE) return Long.MAX_VALUE
        batch.clear()
        router.flushPedal(nowNanos / 1000, batch)
        if (!batch.isEmpty()) sink.sendLive(batch)
        return pedalWakeTime()
    }

    private fun silence() {
        batch.clear()
        router.silence(batch)
        send(dropPending = true)
    }

    /** Re-sends the last value before the cursor of each pedal the instrument takes (Steven Piano: CC64), if any. */
    private fun restorePedal(nowNanos: Long) {
        val events = piece?.events ?: return
        batch.clear()
        for (controller in router.profile.pedals) {
            for (i in cursor - 1 downTo 0) {
                if (events.command(i) == 0xB0 && events.data1(i) == controller && router.accepts(events.channel(i))) {
                    router.route(events.status(i), events.data1(i), events.data2(i), nowNanos / 1000, batch)
                    break
                }
            }
        }
        send(dropPending = false)
    }

    private fun send(dropPending: Boolean) {
        if (!batch.isEmpty()) sink.send(batch, dropPending)
    }

    internal companion object {
        const val NANOS_PER_MICRO_PCT = 100_000L   // 1000 ns per µs, times 100 %

        /**
         * When an event at [songMicros] is due on the clock: rounded up, so it is due by then. A time
         * too far ahead for the arithmetic (years away; the parser refuses files over a day) is
         * [Long.MAX_VALUE], never a wrapped, negative time the scheduler would spin on.
         */
        fun wakeTime(songMicros: Long, anchorSongMicros: Long, anchorNanos: Long, tempoPct: Int): Long {
            val ahead = songMicros - anchorSongMicros
            if (ahead <= 0L) return anchorNanos
            if (ahead > (Long.MAX_VALUE - tempoPct) / NANOS_PER_MICRO_PCT) return Long.MAX_VALUE
            val nanos = (ahead * NANOS_PER_MICRO_PCT + tempoPct - 1) / tempoPct
            return if (anchorNanos > Long.MAX_VALUE - nanos) Long.MAX_VALUE else anchorNanos + nanos
        }
    }
}

/**
 * How late a run of playback sent its events against their time (v1.7 — M23, so Studio's work beside
 * the player can be measured): per batch, the lateness of its earliest event, in real microseconds.
 * A run ends at the piece's end, a stop, or another piece ([close]); its figures wait in [take].
 * Owned by the scheduler thread, as the engine is.
 */
class PlaybackTiming {
    /** One run: [events] sent, the latest [latestMicros] after its time, that batch due at [latestAtMicros] of the piece. */
    data class Run(val events: Int, val latestMicros: Long, val latestAtMicros: Long = 0L)

    private var events = 0
    private var latest = 0L
    private var latestAt = 0L
    private var finished: Run? = null

    fun record(count: Int, lateMicros: Long, atMicros: Long = 0L) {
        events += count
        if (lateMicros > latest) {
            latest = lateMicros
            latestAt = atMicros
        }
    }

    /** The run is over: its figures are kept for [take] (none when it sent nothing). */
    fun close() {
        if (events > 0) finished = Run(events, latest, latestAt)
        events = 0
        latest = 0L
        latestAt = 0L
    }

    /** The last run's figures, once. */
    fun take(): Run? = finished.also { finished = null }
}

/**
 * How long a MIDI keyboard's keys took through the app (v1.11 — M29): from the buffer arriving on the port's
 * thread to its batch leaving for the instrument's live lane, per buffer, in whole milliseconds (the
 * Bluetooth write after that is the link's). A run is one Live session; [close] keeps its figures for [take]:
 * the notes, the median and the worst. No allocation per buffer. Owned by the scheduler thread.
 */
class LiveTiming {
    /** One Live session: [notes] Note Ons, the [medianMs] and [worstMs] of their buffers' times through the app. */
    data class Run(val notes: Int, val medianMs: Int, val worstMs: Int)

    private val buckets = IntArray(BUCKETS)
    private var notes = 0
    private var worstNanos = 0L
    private var finished: Run? = null

    /** [count] Note Ons whose buffer took [nanos] from arriving to leaving. */
    fun record(count: Int, nanos: Long) {
        val ms = (nanos.coerceAtLeast(0L) / 1_000_000L).coerceAtMost((BUCKETS - 1).toLong()).toInt()
        buckets[ms] += count
        notes += count
        if (nanos > worstNanos) worstNanos = nanos
    }

    /** The session is over: its figures are kept for [take] (none when nothing was played). */
    fun close() {
        if (notes > 0) {
            var seen = 0
            var median = 0
            for (ms in 0 until BUCKETS) {
                seen += buckets[ms]
                if (seen * 2 >= notes) {
                    median = ms
                    break
                }
            }
            finished = Run(notes, median, (worstNanos / 1_000_000L).toInt())
        }
        buckets.fill(0)
        notes = 0
        worstNanos = 0L
    }

    /** The last session's figures, once. */
    fun take(): Run? = finished.also { finished = null }

    private companion object {
        /** Whole milliseconds counted one by one; slower buffers fall in the last. */
        const val BUCKETS = 1_000
    }
}

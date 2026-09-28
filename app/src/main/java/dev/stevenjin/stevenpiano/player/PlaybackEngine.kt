// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.player

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
 * the time [advance] returns. Not thread-safe: one thread (the scheduler's) owns it.
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

    /** The song position at [nowNanos]; below zero while the pause before a piece runs. */
    fun positionMicros(nowNanos: Long): Long =
        if (status == PlaybackStatus.Playing) anchorSongMicros + (nowNanos - anchorNanos) * tempoPct / NANOS_PER_MICRO_PCT
        else anchorSongMicros

    /** Makes [midi] current, stopped at its start. */
    fun load(midi: MidiPiece, nowNanos: Long) {
        if (status != PlaybackStatus.Stopped) silence()
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

    /** Always silences, even when idle: it is also the app's panic button. */
    fun stop(nowNanos: Long) {
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

    private inline fun live(route: (MidiBatch) -> Unit) {
        batch.clear()
        route(batch)
        send(dropPending = false)
    }

    /** Sends every event due at [nowNanos]; returns when the next one is due ([Long.MAX_VALUE]: nothing to wait for). */
    fun advance(nowNanos: Long): Long {
        val midi = piece
        if (status != PlaybackStatus.Playing || midi == null) return Long.MAX_VALUE
        val position = positionMicros(nowNanos)
        val events = midi.events
        val nowMicros = nowNanos / 1000
        batch.clear()
        router.flushPedal(nowMicros, batch)   // a pedal change the router held back, if its turn has come
        while (cursor < events.size && events.atMicros(cursor) <= position) {
            router.route(events.status(cursor), events.data1(cursor), events.data2(cursor), nowMicros, batch)
            cursor++
        }
        if (cursor < events.size) {
            send(dropPending = false)
            return minOf(wakeTimeFor(events.atMicros(cursor)), pedalWakeTime())
        }
        router.silence(batch)   // the end: last releases, then the stop sequence
        send(dropPending = false)
        anchorSongMicros = midi.durationMicros
        status = PlaybackStatus.Stopped
        ended = true
        return Long.MAX_VALUE
    }

    private fun wakeTimeFor(songMicros: Long): Long = wakeTime(songMicros, anchorSongMicros, anchorNanos, tempoPct)

    /** When a pedal change the router is holding back may go ([Long.MAX_VALUE]: none is). */
    private fun pedalWakeTime(): Long = router.pedalDueMicros.let { if (it == Long.MAX_VALUE) it else it * 1000 }

    private fun silence() {
        batch.clear()
        router.silence(batch)
        send(dropPending = true)
    }

    /** Re-sends the last pedal value before the cursor, if any. */
    private fun restorePedal(nowNanos: Long) {
        val events = piece?.events ?: return
        batch.clear()
        for (i in cursor - 1 downTo 0) {
            if (events.command(i) == 0xB0 && events.data1(i) == NoteRouter.SUSTAIN && router.accepts(events.channel(i))) {
                router.route(events.status(i), events.data1(i), events.data2(i), nowNanos / 1000, batch)
                break
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

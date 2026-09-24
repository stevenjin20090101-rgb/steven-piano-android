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
 * re-send the pedal in effect. Not thread-safe: one thread (the scheduler's) owns it.
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

    private var cursor = 0
    private val batch = MidiBatch()

    fun positionMicros(nowNanos: Long): Long =
        if (status == PlaybackStatus.Playing) anchorSongMicros + (nowNanos - anchorNanos) * tempoPct / NANOS_PER_MICRO_PCT
        else anchorSongMicros

    /** Makes [midi] current, stopped at its start. */
    fun load(midi: MidiPiece, nowNanos: Long) {
        if (status != PlaybackStatus.Stopped) silence()
        piece = midi
        status = PlaybackStatus.Stopped
        anchorSongMicros = 0L
        cursor = 0
        ended = false
    }

    /** Forgets the piece, silencing the piano first if it was sounding. */
    fun eject() {
        if (status != PlaybackStatus.Stopped) silence()
        piece = null
        status = PlaybackStatus.Stopped
        anchorSongMicros = 0L
        cursor = 0
        ended = false
    }

    fun play(nowNanos: Long) {
        val midi = piece ?: return
        if (status == PlaybackStatus.Playing) return
        if (cursor >= midi.events.size) {   // over: play again from the top
            anchorSongMicros = 0L
            cursor = 0
        }
        ended = false
        anchorNanos = nowNanos
        status = PlaybackStatus.Playing
        restorePedal(nowNanos)
    }

    fun pause(nowNanos: Long) {
        if (status != PlaybackStatus.Playing) return
        anchorSongMicros = positionMicros(nowNanos)
        status = PlaybackStatus.Paused
        silence()
    }

    /** Always silences, even when idle: it is also the app's panic button. */
    fun stop(nowNanos: Long) {
        status = PlaybackStatus.Stopped
        anchorSongMicros = 0L
        cursor = 0
        ended = false
        silence()
    }

    /** Silences, then continues from [targetMicros]; a stopped piece becomes paused there. */
    fun seek(targetMicros: Long, nowNanos: Long) {
        val midi = piece ?: return
        silence()
        anchorSongMicros = targetMicros.coerceIn(0L, midi.durationMicros)
        anchorNanos = nowNanos
        cursor = firstEventAtOrAfter(midi, anchorSongMicros)
        ended = false
        if (status == PlaybackStatus.Stopped) status = PlaybackStatus.Paused
        if (status == PlaybackStatus.Playing) restorePedal(nowNanos)
    }

    /** Silences and re-sends the pedal where playback is, e.g. after the link reconnects. */
    fun resync(nowNanos: Long) = seek(positionMicros(nowNanos), nowNanos)

    fun setTempo(pct: Int, nowNanos: Long) {
        anchorSongMicros = positionMicros(nowNanos)
        anchorNanos = nowNanos
        tempoPct = pct.coerceIn(MIN_TEMPO_PCT, MAX_TEMPO_PCT)
    }

    /** Sends every event due at [nowNanos]; returns when the next one is due ([Long.MAX_VALUE]: nothing to wait for). */
    fun advance(nowNanos: Long): Long {
        val midi = piece
        if (status != PlaybackStatus.Playing || midi == null) return Long.MAX_VALUE
        val position = positionMicros(nowNanos)
        val events = midi.events
        val nowMicros = nowNanos / 1000
        batch.clear()
        while (cursor < events.size && events[cursor].atMicros <= position) {
            val e = events[cursor++]
            router.route(e.status, e.data1, e.data2, nowMicros, batch)
        }
        if (cursor < events.size) {
            send(dropPending = false)
            return wakeTimeFor(events[cursor].atMicros)
        }
        router.silence(batch)   // the end: last releases, then the stop sequence
        send(dropPending = false)
        anchorSongMicros = midi.durationMicros
        status = PlaybackStatus.Stopped
        ended = true
        return Long.MAX_VALUE
    }

    private fun wakeTimeFor(songMicros: Long): Long {
        val scaled = (songMicros - anchorSongMicros) * NANOS_PER_MICRO_PCT
        return anchorNanos + (scaled + tempoPct - 1) / tempoPct   // rounded up, so the event is due by then
    }

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
            val e = events[i]
            if (e.command == 0xB0 && e.data1 == NoteRouter.SUSTAIN && router.accepts(e.channel)) {
                router.route(e.status, e.data1, e.data2, nowNanos / 1000, batch)
                break
            }
        }
        send(dropPending = false)
    }

    private fun send(dropPending: Boolean) {
        if (!batch.isEmpty()) sink.send(batch, dropPending)
    }

    private fun firstEventAtOrAfter(midi: MidiPiece, micros: Long): Int {
        var lo = 0
        var hi = midi.events.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (midi.events[mid].atMicros < micros) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        const val MIN_TEMPO_PCT = 25
        const val MAX_TEMPO_PCT = 200
        private const val NANOS_PER_MICRO_PCT = 100_000L   // 1000 ns per µs, times 100 %
    }
}

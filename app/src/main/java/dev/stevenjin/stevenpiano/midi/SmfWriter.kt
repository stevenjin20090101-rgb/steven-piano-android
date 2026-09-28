// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.midi

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Writes a Standard MIDI File (v1.7 — M23, Studio's pieces): format 0, one track, [PPQ] ticks a quarter
 * note at one tempo, 120 bpm unless one is given (960 ticks a second, about a millisecond each; a
 * composition is written at the tempo it was composed at, v1.7 — M24, so its beats fall on the file's
 * and the score's bars follow them), 4/4. The track holds the title as its name (FF 03) and an optional
 * text (FF 01), the tempo and time signature, then the notes as Note On / Note Off on channel 1 and the
 * sustain pedal as CC64 127 / 0, then End of Track. Events at one tick go in [SmfParser]'s order
 * (controllers, then note-offs, then note-ons), so what is written is what the parser reads back.
 * Every note lasts at least a tick, velocities are held to 1–127 (a Note On of 0 would be a Note Off),
 * keys to 0–127.
 */
object SmfWriter {
    const val PPQ = 480
    const val TEMPO_MICROS = 500_000
    const val SUSTAIN = 64

    /** A note from [onMicros] to [offMicros], MIDI [key], [velocity]. */
    data class Note(val onMicros: Long, val offMicros: Long, val key: Int, val velocity: Int)

    /** The sustain pedal down from [downMicros] to [upMicros]. */
    data class Pedal(val downMicros: Long, val upMicros: Long)

    /**
     * The file's bytes. [title] names the track; [text] is a text event beside it; [tempoMicros] is the
     * file's one tempo, microseconds a quarter note ([tempoOf] a bpm), 120 bpm by default.
     */
    fun write(
        notes: List<Note>,
        pedals: List<Pedal> = emptyList(),
        title: String? = null,
        text: String? = null,
        tempoMicros: Int = TEMPO_MICROS,
    ): ByteArray {
        require(tempoMicros in 1..MAX_TEMPO_MICROS) { "tempo $tempoMicros" }
        val events = ArrayList<Event>(notes.size * 2 + pedals.size * 2)
        for (note in notes) {
            val on = ticks(note.onMicros, tempoMicros)
            val off = maxOf(ticks(note.offMicros, tempoMicros), on + 1)
            val key = note.key.coerceIn(0, 127)
            events += Event(on, RANK_NOTE_ON, byteArrayOf(0x90.toByte(), key.toByte(), note.velocity.coerceIn(1, 127).toByte()))
            events += Event(off, RANK_NOTE_OFF, byteArrayOf(0x80.toByte(), key.toByte(), 64))
        }
        for (pedal in pedals) {
            val down = ticks(pedal.downMicros, tempoMicros)
            val up = maxOf(ticks(pedal.upMicros, tempoMicros), down + 1)
            events += Event(down, RANK_CONTROL, byteArrayOf(0xB0.toByte(), SUSTAIN.toByte(), 127))
            events += Event(up, RANK_CONTROL, byteArrayOf(0xB0.toByte(), SUSTAIN.toByte(), 0))
        }
        events.sortWith(compareBy<Event>({ it.tick }, { it.rank }))   // stable: pedal down before up at one tick

        val track = ByteArrayOutputStream()
        title?.takeIf { it.isNotEmpty() }?.let { meta(track, 0x03, it.toByteArray(Charsets.UTF_8)) }
        text?.takeIf { it.isNotEmpty() }?.let { meta(track, 0x01, it.toByteArray(Charsets.UTF_8)) }
        meta(track, 0x51, byteArrayOf((tempoMicros shr 16).toByte(), (tempoMicros shr 8).toByte(), tempoMicros.toByte()))
        meta(track, 0x58, byteArrayOf(4, 2, 24, 8))
        var last = 0L
        for (event in events) {
            varLength(track, event.tick - last)
            track.write(event.bytes)
            last = event.tick
        }
        varLength(track, 0)
        track.write(byteArrayOf(0xFF.toByte(), 0x2F, 0))

        val body = track.toByteArray()
        val out = ByteArrayOutputStream(14 + 8 + body.size)
        out.write("MThd".toByteArray(Charsets.US_ASCII))
        out.write(int32(6))
        out.write(byteArrayOf(0, 0, 0, 1, (PPQ shr 8).toByte(), PPQ.toByte()))
        out.write("MTrk".toByteArray(Charsets.US_ASCII))
        out.write(int32(body.size))
        out.write(body)
        return out.toByteArray()
    }

    /** Microseconds as ticks at [tempoMicros] a quarter note (120 bpm by default), rounded to the nearest; never before 0. */
    fun ticks(micros: Long, tempoMicros: Int = TEMPO_MICROS): Long =
        (micros.coerceAtLeast(0) * PPQ * 2 + tempoMicros) / (2L * tempoMicros)

    /** A tempo of [bpm] quarter notes a minute as MIDI keeps it: whole microseconds a quarter note. */
    fun tempoOf(bpm: Int): Int {
        require(bpm > 0) { "bpm $bpm" }
        return (60_000_000.0 / bpm).roundToInt().coerceIn(1, MAX_TEMPO_MICROS)
    }

    /** The slowest tempo a file can hold: three bytes of microseconds a quarter note. */
    const val MAX_TEMPO_MICROS = 0xFF_FFFF

    private class Event(val tick: Long, val rank: Int, val bytes: ByteArray)

    private const val RANK_CONTROL = 1
    private const val RANK_NOTE_OFF = 2
    private const val RANK_NOTE_ON = 3

    /** A meta event at delta 0: FF [type], its length, [data]. */
    private fun meta(out: ByteArrayOutputStream, type: Int, data: ByteArray) {
        varLength(out, 0)
        out.write(0xFF)
        out.write(type)
        varLength(out, data.size.toLong())
        out.write(data)
    }

    /** A variable-length quantity: seven bits a byte, the high bit set on all but the last. */
    private fun varLength(out: ByteArrayOutputStream, value: Long) {
        require(value in 0..0x0FFF_FFFFL) { "delta $value" }
        var buffer = value and 0x7F
        var rest = value shr 7
        while (rest > 0) {
            buffer = (buffer shl 8) or 0x80 or (rest and 0x7F)
            rest = rest shr 7
        }
        while (true) {
            out.write((buffer and 0xFF).toInt())
            if (buffer and 0x80 == 0L) break
            buffer = buffer shr 8
        }
    }

    private fun int32(value: Int) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
}

// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import dev.stevenjin.stevenpiano.midi.MidiBatch
import dev.stevenjin.stevenpiano.midi.MidiSink
import java.util.Arrays

/**
 * The tablet's piano (v1.8 — M25), the [Sampler]'s face to the rest of the app, safe to call from any
 * thread: the player's scheduler (a piece's notes, the Keys screen's), the main thread (the mode, the
 * volume). [noteOn], [noteOff], [sustain], [allOff] and [silence] are queued, in order, and reach the
 * sampler when its owner, the audio thread, next calls [drain] or [render]; [volume] is read there too;
 * [load] swaps the font (null: none, and nothing sounds). A call never blocks for longer than it takes
 * to add one number to the queue, so the player's thread is never held up. [onPost] hears every event
 * queued, so the audio output can wake (it opens only once something sounds). A queue that fills while
 * nothing takes from it (the output refused) starts again from [silence]: nothing it held could be
 * heard any more.
 */
class PianoVoice(val outputRate: Int, private val polyphony: Int = Sampler.POLYPHONY) {
    @Volatile
    private var sampler: Sampler? = null

    @Volatile
    private var volumePct = Sampler.DEFAULT_VOLUME

    /** Called after every event is queued, on the caller's thread: it must not block. */
    @Volatile
    var onPost: (() -> Unit)? = null

    private val lock = Any()
    private val queue = IntArray(QUEUE)
    private var head = 0
    private var count = 0
    private val taken = IntArray(QUEUE)

    /** Whether a font is loaded. */
    val loaded: Boolean get() = sampler != null

    /** The font playing, or null. */
    @Volatile
    var font: SoundFont? = null
        private set

    fun noteOn(key: Int, velocity: Int) = post(NOTE_ON, key, velocity)

    fun noteOff(key: Int) = post(NOTE_OFF, key, 0)

    fun sustain(on: Boolean) = post(SUSTAIN, if (on) 1 else 0, 0)

    /** Every key released and the pedal up, as MIDI's All Notes Off. */
    fun allOff() = post(ALL_OFF, 0, 0)

    /** Every voice faded out within [Sampler.FADE_MS]. */
    fun silence() = post(SILENCE, 0, 0)

    /** The volume, 0–100 %; the sampler follows at its next block. */
    fun volume(pct: Int) {
        volumePct = pct.coerceIn(0, 100)
    }

    val volume: Int get() = volumePct

    /** Plays [soundFont] from now on (the voices of the one before stop at once); null plays nothing. */
    fun load(soundFont: SoundFont?) {
        font = soundFont
        sampler = soundFont?.let { Sampler(it, outputRate, polyphony) }
        onPost?.invoke()
    }

    /** Whether events wait to be applied. */
    fun pending(): Boolean = synchronized(lock) { count > 0 }

    /** The audio thread: applies what was queued to the sampler, in order. */
    fun drain() {
        val n: Int
        synchronized(lock) {
            n = count
            for (i in 0 until n) taken[i] = queue[(head + i) % QUEUE]
            head = 0
            count = 0
        }
        val s = sampler ?: return
        for (i in 0 until n) apply(s, taken[i])
    }

    /** The audio thread: whether any voice sounds. */
    fun sounding(): Boolean = (sampler?.activeVoices ?: 0) > 0

    /** The audio thread: how many voices sound (fading ones included). */
    fun voices(): Int = sampler?.activeVoices ?: 0

    /**
     * The audio thread: applies what was queued, then mixes the next [frames] mono frames into [out]
     * (from index 0; silence without a font). True while any voice sounds.
     */
    fun render(out: FloatArray, frames: Int): Boolean {
        drain()
        val s = sampler
        if (s == null) {
            Arrays.fill(out, 0, frames, 0f)
            return false
        }
        s.volume(volumePct)
        s.render(out, frames)
        return s.activeVoices > 0
    }

    private fun post(type: Int, a: Int, b: Int) {
        synchronized(lock) {
            if (count == QUEUE) {
                // Nothing has taken from the queue for a long while: start again from silence.
                head = 0
                count = 0
                queue[count++] = pack(SILENCE, 0, 0)
            }
            queue[(head + count) % QUEUE] = pack(type, a, b)
            count++
        }
        onPost?.invoke()
    }

    private fun apply(s: Sampler, event: Int) {
        val a = (event ushr 8) and 0xFF
        val b = event and 0xFF
        when (event ushr 16) {
            NOTE_ON -> s.noteOn(a, b)
            NOTE_OFF -> s.noteOff(a)
            SUSTAIN -> s.sustain(a != 0)
            ALL_OFF -> s.allOff()
            SILENCE -> s.silence()
        }
    }

    /**
     * What the player sends the piano, played here too (v1.8 — M25): Note On (velocity 0 a release), Note
     * Off, CC64 (down at 64 and above) and the stop sequence's CC123, All Notes Off ([allOff]); CC120, All
     * Sound Off, is [silence]. Everything else is ignored, as the piano ignores it. [active] says whether
     * the tablet sounds now; while it doesn't, nothing is queued.
     */
    fun sink(active: () -> Boolean): MidiSink = MidiSink { batch, _ -> if (active()) play(batch) }

    /** Queues [batch]'s messages as [sink] reads them. */
    fun play(batch: MidiBatch) {
        for (i in 0 until batch.size) {
            val d1 = batch.data1(i)
            val d2 = batch.data2(i)
            when (batch.status(i) and 0xF0) {
                0x90 -> if (d2 == 0) noteOff(d1) else noteOn(d1, d2)
                0x80 -> noteOff(d1)
                0xB0 -> when (d1) {
                    SUSTAIN_CC -> sustain(d2 >= 64)
                    ALL_NOTES_OFF_CC -> allOff()
                    ALL_SOUND_OFF_CC -> silence()
                }
            }
        }
    }

    private companion object {
        const val QUEUE = 4_096
        const val NOTE_ON = 1
        const val NOTE_OFF = 2
        const val SUSTAIN = 3
        const val ALL_OFF = 4
        const val SILENCE = 5
        const val SUSTAIN_CC = 64
        const val ALL_SOUND_OFF_CC = 120
        const val ALL_NOTES_OFF_CC = 123

        fun pack(type: Int, a: Int, b: Int): Int = (type shl 16) or ((a and 0xFF) shl 8) or (b and 0xFF)
    }
}

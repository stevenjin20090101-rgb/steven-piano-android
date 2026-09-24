// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.midi

/**
 * Turns file events into what the piano can safely play. The firmware has no per-key
 * reference counting, cannot re-strike a held key, needs ~100 ms between strikes of one
 * key, and treats CC120/121/123 as "everything off". So this router:
 *  - remembers the key each source note (channel x note) was actually sent as, so a
 *    transpose change mid-note still releases the right key;
 *  - reference-counts sent keys: one Note On when a key goes 0 -> 1, one Note Off at 1 -> 0;
 *  - thins a strike that comes < 100 ms after the previous strike of an idle key;
 *  - forwards CC64 only; every other controller, program change, pitch bend and
 *    aftertouch is dropped.
 * Everything goes out on channel 1: the piano listens in omni mode.
 * Times are wall-clock microseconds, because the 100 ms guard protects the solenoids.
 */
class NoteRouter {
    var transpose = 0
    var fold = true
    var velocityPct = 100
    var skipDrums = true

    private val sentKey = IntArray(16 * 128) { KeyMap.UNPLAYABLE }
    private val refCount = IntArray(128)
    private val lastOnsetMicros = LongArray(128) { NEVER }
    private var pedal = UNKNOWN

    /** Sounding keys as bits `key - 24`: keys 24..87 here. */
    var activeLow = 0L
        private set

    /** Sounding keys 88..107 as bits `key - 88`. */
    var activeHigh = 0L
        private set

    fun accepts(channel: Int): Boolean = !(skipDrums && channel == DRUM_CHANNEL)

    fun isSounding(key: Int): Boolean = key in 0..127 && refCount[key] > 0

    /** Releases always go through, so changing a setting mid-note never strands a held key. */
    fun route(status: Int, data1: Int, data2: Int, nowMicros: Long, out: MidiBatch) {
        val channel = status and 0x0F
        val source = channel * 128 + data1
        when (status and 0xF0) {
            0x90 -> if (data2 == 0) release(source, out)
            else if (accepts(channel)) noteOn(source, data1, data2, nowMicros, out)
            0x80 -> release(source, out)
            0xB0 -> if (data1 == SUSTAIN && data2 != pedal && accepts(channel)) {
                pedal = data2
                out.add(0xB0, SUSTAIN, data2)
            }
        }
    }

    /** The stop sequence, in this order: pedal up, then All Notes Off. Forgets every held key. */
    fun silence(out: MidiBatch) {
        out.add(0xB0, SUSTAIN, 0)
        out.add(0xB0, ALL_NOTES_OFF, 0)
        sentKey.fill(KeyMap.UNPLAYABLE)
        refCount.fill(0)
        pedal = 0
        activeLow = 0L
        activeHigh = 0L
    }

    private fun noteOn(source: Int, note: Int, velocity: Int, nowMicros: Long, out: MidiBatch) {
        release(source, out)   // a source struck again while sounding is released first
        val key = KeyMap.map(note, transpose, fold)
        if (key == KeyMap.UNPLAYABLE) return
        if (refCount[key] == 0) {
            if (nowMicros - lastOnsetMicros[key] < MIN_ONSET_GAP_MICROS) return   // thinned
            lastOnsetMicros[key] = nowMicros
            out.add(0x90, key, ((velocity * velocityPct + 50) / 100).coerceIn(1, 127))
            setActive(key, true)
        }
        refCount[key]++
        sentKey[source] = key
    }

    private fun release(source: Int, out: MidiBatch) {
        val key = sentKey[source]
        if (key == KeyMap.UNPLAYABLE) return
        sentKey[source] = KeyMap.UNPLAYABLE
        if (--refCount[key] == 0) {
            out.add(0x80, key, 0)
            setActive(key, false)
        }
    }

    private fun setActive(key: Int, on: Boolean) {
        val bit = key - KeyMap.LOWEST
        if (bit < 64) {
            activeLow = if (on) activeLow or (1L shl bit) else activeLow and (1L shl bit).inv()
        } else {
            activeHigh = if (on) activeHigh or (1L shl (bit - 64)) else activeHigh and (1L shl (bit - 64)).inv()
        }
    }

    companion object {
        const val DRUM_CHANNEL = 9   // "channel 10"
        const val SUSTAIN = 64
        const val ALL_NOTES_OFF = 123
        const val MIN_ONSET_GAP_MICROS = 100_000L
        private const val NEVER = Long.MIN_VALUE / 2
        private const val UNKNOWN = -1
    }
}

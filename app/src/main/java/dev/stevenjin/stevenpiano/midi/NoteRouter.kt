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
 * Turns file events, and keys played on the Keys screen, into what the piano can safely play.
 * The firmware has no per-key reference counting, cannot re-strike a held key, needs ~100 ms
 * between strikes of one key, and treats CC120/121/123 as "everything off". So this router:
 *  - remembers the key each source note (channel x note, or a live key) was actually sent as,
 *    so a transpose change mid-note still releases the right key;
 *  - reference-counts sent keys: one Note On when a key goes 0 -> 1, one Note Off at 1 -> 0,
 *    so a live key and a piece's note on the same key share it;
 *  - thins a strike that comes < 100 ms after the previous strike of an idle key, taps included;
 *  - forwards CC64 only; every other controller, program change, pitch bend and
 *    aftertouch is dropped. A file's pedal changes go out at most 20 a second after a burst of
 *    three (the actuator moves a real pedal): one that comes sooner waits, a newer one takes its
 *    place, and [flushPedal] sends it when its turn comes ([pedalDueMicros]). The stop sequence
 *    and the Keys screen's sustain are never held back.
 * Live keys are sources of their own, apart from the piece's: [silenceLive] lets go of them
 * (and of the Keys screen's sustain) while the piece's keys stay down.
 * Everything goes out on channel 1: the piano listens in omni mode.
 * Times are wall-clock microseconds, because the 100 ms guard protects the solenoids.
 */
class NoteRouter {
    var transpose = 0
    var fold = true
    var velocityPct = 100
    var skipDrums = true

    /** Per source, the key it sounds: 16 channels x 128 notes from files, then 128 live keys. */
    private val sentKey = IntArray(SOURCES) { KeyMap.UNPLAYABLE }
    private val refCount = IntArray(128)
    private val lastOnsetMicros = LongArray(128) { NEVER }
    private var pedal = UNKNOWN

    /** A file's pedal change that came too soon, waiting its turn ([NONE]: none). */
    private var pendingPedal = NONE

    /** The pedal's token bucket, as microseconds of credit; a change costs [PEDAL_GAP_MICROS]. */
    private var pedalCredit = PEDAL_CREDIT_MAX
    private var pedalCreditAt = NEVER

    /** When the waiting pedal change may go, in the same wall-clock microseconds as [route]'s; [Long.MAX_VALUE] when none waits. */
    val pedalDueMicros: Long
        get() = if (pendingPedal == NONE) Long.MAX_VALUE else pedalCreditAt + (PEDAL_GAP_MICROS - pedalCredit)

    /** Whether the Keys screen's sustain is latched down. */
    var liveSustainDown = false
        private set

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
            0xB0 -> if (data1 == SUSTAIN && accepts(channel)) filePedal(data2, nowMicros, out)
        }
    }

    /** Sends the pedal change that was waiting, once its turn has come ([pedalDueMicros]). */
    fun flushPedal(nowMicros: Long, out: MidiBatch) {
        if (pendingPedal == NONE) return
        refillPedal(nowMicros)
        if (pedalCredit < PEDAL_GAP_MICROS) return
        val value = pendingPedal
        pendingPedal = NONE
        spendPedal(value, out)
    }

    /**
     * A key pressed on the Keys screen. Keys there are already the piano's (24-107), so there is
     * no transpose or fold, but the velocity percentage applies. A key the screen already holds
     * is not struck again; a key a piece holds is shared, not re-struck; a strike within 100 ms
     * of the key's last one is thinned like any other.
     */
    fun liveNoteOn(key: Int, velocity: Int, nowMicros: Long, out: MidiBatch) {
        if (key !in KeyMap.LOWEST..KeyMap.HIGHEST) return
        val source = LIVE + key
        if (sentKey[source] != KeyMap.UNPLAYABLE) return
        strike(source, key, velocity, nowMicros, out)
    }

    /** The Keys screen let go of [key]. */
    fun liveNoteOff(key: Int, out: MidiBatch) {
        if (key in KeyMap.LOWEST..KeyMap.HIGHEST) release(LIVE + key, out)
    }

    /** The Keys screen's latching sustain: CC64 = 127 when [down], 0 when up. The piano has one pedal; the last change wins. */
    fun liveSustain(down: Boolean, out: MidiBatch) {
        liveSustainDown = down
        pendingPedal = NONE   // the latest change wins, and this is it
        setPedal(if (down) PEDAL_DOWN else 0, out)
    }

    /**
     * Lets go of everything the Keys screen holds: a Note Off for each of its keys (unless a piece
     * still holds that key too), then the pedal if the screen's sustain was down. A piece's keys
     * stay down.
     */
    fun silenceLive(out: MidiBatch) {
        for (key in KeyMap.LOWEST..KeyMap.HIGHEST) release(LIVE + key, out)
        if (liveSustainDown) liveSustain(false, out)
    }

    /** The stop sequence, in this order: pedal up, then All Notes Off. Forgets every held key, live ones too. */
    fun silence(out: MidiBatch) {
        out.add(0xB0, SUSTAIN, 0)
        out.add(0xB0, ALL_NOTES_OFF, 0)
        sentKey.fill(KeyMap.UNPLAYABLE)
        refCount.fill(0)
        pedal = 0
        pendingPedal = NONE
        pedalCredit = PEDAL_CREDIT_MAX   // what follows a silence (a resumed piece's pedal) goes at once
        pedalCreditAt = NEVER
        liveSustainDown = false
        activeLow = 0L
        activeHigh = 0L
    }

    private fun noteOn(source: Int, note: Int, velocity: Int, nowMicros: Long, out: MidiBatch) {
        release(source, out)   // a source struck again while sounding is released first
        val key = KeyMap.map(note, transpose, fold)
        if (key == KeyMap.UNPLAYABLE) return
        strike(source, key, velocity, nowMicros, out)
    }

    /** [source] now sounds [key]: a Note On only when the key goes 0 -> 1 and is not thinned. */
    private fun strike(source: Int, key: Int, velocity: Int, nowMicros: Long, out: MidiBatch) {
        if (refCount[key] == 0) {
            if (nowMicros - lastOnsetMicros[key] < MIN_ONSET_GAP_MICROS) return   // thinned
            lastOnsetMicros[key] = nowMicros
            out.add(0x90, key, ((velocity * velocityPct + 50) / 100).coerceIn(1, 127))
            setActive(key, true)
        }
        refCount[key]++
        sentKey[source] = key
    }

    private fun setPedal(value: Int, out: MidiBatch) {
        if (value == pedal) return
        pedal = value
        out.add(0xB0, SUSTAIN, value)
    }

    /** A file's pedal change: now if the bucket allows, else it waits (replacing any change already waiting). */
    private fun filePedal(value: Int, nowMicros: Long, out: MidiBatch) {
        refillPedal(nowMicros)
        if (pedalCredit >= PEDAL_GAP_MICROS) {
            pendingPedal = NONE
            spendPedal(value, out)
        } else {
            pendingPedal = if (value == pedal) NONE else value   // back where it is: nothing to send
        }
    }

    private fun spendPedal(value: Int, out: MidiBatch) {
        if (value == pedal) return
        pedalCredit -= PEDAL_GAP_MICROS
        setPedal(value, out)
    }

    private fun refillPedal(nowMicros: Long) {
        if (pedalCreditAt != NEVER) pedalCredit = minOf(PEDAL_CREDIT_MAX, pedalCredit + (nowMicros - pedalCreditAt).coerceAtLeast(0L))
        pedalCreditAt = nowMicros
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
        private const val PEDAL_DOWN = 127

        /** The first live source: after the 16 x 128 file sources. */
        private const val LIVE = 16 * 128
        private const val SOURCES = LIVE + 128
        private const val NEVER = Long.MIN_VALUE / 2
        private const val UNKNOWN = -1
        private const val NONE = -1

        /** One pedal change per 50 ms (20 a second) once the burst is spent. */
        const val PEDAL_GAP_MICROS = 50_000L

        /** Pedal changes that may go back to back after a quiet spell. */
        const val PEDAL_BURST = 3
        private const val PEDAL_CREDIT_MAX = PEDAL_BURST * PEDAL_GAP_MICROS
    }
}

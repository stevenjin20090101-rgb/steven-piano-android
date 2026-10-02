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
 * Turns file events, keys played on the Keys screen, and keys played on a MIDI keyboard (v1.11 — M29) into
 * what the instrument can safely play, as its [profile] says ([InstrumentProfile]: Steven Piano, or any MIDI
 * piano). Steven Piano has no per-key reference counting, cannot re-strike a held key, needs ~100 ms
 * between strikes of one key, and treats CC120/121/123 as "everything off". So this router:
 *  - remembers the key each source note (channel x note, a key of the screen, a key of the keyboard) was
 *    actually sent as, so a transpose change mid-note still releases the right key;
 *  - reference-counts sent keys: one Note On when a key goes 0 -> 1, one Note Off at 1 -> 0, so a live key
 *    and a piece's note on the same key share it (a MIDI piano, which can, strikes a shared key again);
 *  - thins a strike that comes < 100 ms after the previous strike of an idle key, taps included (Steven
 *    Piano only);
 *  - forwards the pedals the profile takes (CC64 on Steven Piano; CC64, CC66, CC67 on a MIDI piano), each
 *    once per change; every other controller, program change, pitch bend and aftertouch is dropped. A file's
 *    CC64 on Steven Piano goes out at most 20 a second after a burst of three (the actuator moves a real
 *    pedal): one that comes sooner waits, a newer one takes its place, and [flushPedal] sends it when its
 *    turn comes ([pedalDueMicros]). The keyboard's pedal goes at once when it crosses 64 and is paced the
 *    same way between. The stop sequence and the Keys screen's sustain are never held back.
 * Live keys are sources of their own, apart from the piece's: [silenceLive] lets go of the Keys screen's
 * (and its sustain), [silenceExternal] of the keyboard's (and puts the pedal back where the piece and the
 * screen's sustain want it), while the piece's keys stay down. A keyboard's note keeps its pitch (no
 * transpose); one outside the instrument's keys folds in by octaves, or is dropped, as [fold] says.
 * Everything goes out on channel 1: the piano listens in omni mode.
 * Times are wall-clock microseconds, because the 100 ms guard protects the solenoids.
 */
class NoteRouter {
    var transpose = 0
    var fold = true
    var velocityPct = 100
    var skipDrums = true

    /**
     * The instrument (v1.11 — M29). Change it only with nothing sounding (after [silence]): the keys held
     * are the old instrument's.
     */
    var profile: InstrumentProfile = InstrumentProfile.StevenPiano

    /** Per source, the key it sounds: 16 channels x 128 notes from files, then 128 screen keys, then 128 keyboard notes. */
    private val sentKey = IntArray(SOURCES) { KeyMap.UNPLAYABLE }
    private val refCount = IntArray(128)
    private val lastOnsetMicros = LongArray(128) { NEVER }

    /** The value last sent for each controller (CC64, CC66, CC67), or [UNKNOWN]. */
    private val sentValue = IntArray(128) { UNKNOWN }

    /** The piece's own value for each pedal, last routed: where the pedal goes back to when the keyboard lets go. */
    private val fileValue = IntArray(128)

    /** The keyboard's value for each pedal, last routed; [UNTOUCHED] while the keyboard has not moved it. */
    private val externalValue = IntArray(128) { UNTOUCHED }

    /** A paced CC64 change that came too soon, waiting its turn ([NONE]: none). */
    private var pendingPedal = NONE

    /** The pedal's token bucket, as microseconds of credit; a change costs [PEDAL_GAP_MICROS]. */
    private var pedalCredit = PEDAL_CREDIT_MAX
    private var pedalCreditAt = NEVER

    /** Keys sounding per key of the screen's 84 (a MIDI piano's A0-B0 and C8 drawn an octave in). */
    private val displayCount = IntArray(KeyMap.KEY_COUNT)

    /** When the waiting pedal change may go, in the same wall-clock microseconds as [route]'s; [Long.MAX_VALUE] when none waits. */
    val pedalDueMicros: Long
        get() = if (pendingPedal == NONE) Long.MAX_VALUE else pedalCreditAt + (PEDAL_GAP_MICROS - pedalCredit)

    /** Whether the Keys screen's sustain is latched down. */
    var liveSustainDown = false
        private set

    /** Sounding keys as bits `key - 24`: keys 24..87 here (a MIDI piano's 21-23 drawn as 33-35). */
    var activeLow = 0L
        private set

    /** Sounding keys 88..107 as bits `key - 88` (a MIDI piano's 108 drawn as 96). */
    var activeHigh = 0L
        private set

    fun accepts(channel: Int): Boolean = !(skipDrums && channel == DRUM_CHANNEL)

    fun isSounding(key: Int): Boolean = key in 0..127 && refCount[key] > 0

    /** Whether any key the keyboard played still sounds through this router. */
    val externalHeld: Boolean
        get() {
            for (note in 0..127) if (sentKey[EXT + note] != KeyMap.UNPLAYABLE) return true
            return false
        }

    /** Releases always go through, so changing a setting mid-note never strands a held key. */
    fun route(status: Int, data1: Int, data2: Int, nowMicros: Long, out: MidiBatch) {
        val channel = status and 0x0F
        val source = channel * 128 + (data1 and 0x7F)
        when (status and 0xF0) {
            0x90 -> if (data2 == 0) release(source, out)
            else if (accepts(channel)) noteOn(source, data1, data2, nowMicros, out)
            0x80 -> release(source, out)
            0xB0 -> if (profile.forwards(data1) && accepts(channel)) filePedal(data1, data2, nowMicros, out)
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
     * is not struck again; a key a piece holds is shared, not re-struck (a MIDI piano strikes it again);
     * a strike within 100 ms of the key's last one is thinned like any other.
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
        setController(SUSTAIN, if (down) PEDAL_DOWN else 0, out)
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

    /**
     * A key played on a MIDI keyboard (v1.11 — M29), [note] as played: no transpose; outside the
     * instrument's keys it folds in by octaves, or is dropped ([fold]); the velocity percentage applies.
     * Pressed again while this router holds it (a release the keyboard lost): a MIDI piano strikes it again;
     * Steven Piano lets go and strikes again only when nothing else holds the key and its 100 ms allow,
     * else it keeps holding.
     */
    fun externalNoteOn(note: Int, velocity: Int, nowMicros: Long, out: MidiBatch) {
        val n = note and 0x7F
        val key = KeyMap.map(n, 0, fold, profile.lowest, profile.highest)
        if (key == KeyMap.UNPLAYABLE) return
        val source = EXT + n
        val held = sentKey[source]
        if (held != KeyMap.UNPLAYABLE) {
            val free = refCount[held] == 1 && nowMicros - lastOnsetMicros[held] >= profile.minOnsetGapMicros
            if (!profile.restrike && !free) return   // keep holding
            release(source, out)
        }
        strike(source, key, velocity, nowMicros, out)
    }

    /** The keyboard let go of [note]. */
    fun externalNoteOff(note: Int, out: MidiBatch) = release(EXT + (note and 0x7F), out)

    /**
     * The keyboard's pedal [controller] (64, 66, 67) at [value], if the instrument takes it. The last change
     * wins among the piece, the screen's sustain and the keyboard. On Steven Piano a change that crosses 64
     * goes at once and the ones between are paced as a piece's; on a MIDI piano each goes at once.
     */
    fun externalPedal(controller: Int, value: Int, nowMicros: Long, out: MidiBatch) {
        if (!profile.forwards(controller)) return
        val v = value and 0x7F
        externalValue[controller] = v
        if (controller == SUSTAIN && profile.pacedPedal) {
            val last = sentValue[SUSTAIN]
            if (last == UNKNOWN || (v >= HALF) != (last >= HALF)) {
                pendingPedal = NONE
                setController(SUSTAIN, v, out)
            } else {
                pacePedal(v, nowMicros, out)
            }
        } else {
            setController(controller, v, out)
        }
    }

    /**
     * Lets go of everything the keyboard holds (v1.11 — M29): a Note Off for each of its keys (unless
     * another source still holds the key), and each pedal it moved (up or down: the last change won) goes
     * back to where the piece and the screen's sustain want it (the higher of the two). The piece's keys
     * stay down.
     */
    fun silenceExternal(out: MidiBatch) {
        for (note in 0..127) release(EXT + note, out)
        for (controller in profile.pedals) {
            if (externalValue[controller] == UNTOUCHED) continue   // the keyboard never moved it: nothing to put back
            externalValue[controller] = UNTOUCHED
            val wanted = maxOf(fileValue[controller], if (controller == SUSTAIN && liveSustainDown) PEDAL_DOWN else 0)
            if (controller == SUSTAIN) pendingPedal = NONE
            setController(controller, wanted, out)
        }
    }

    /**
     * The stop sequence, as the instrument needs it, never held back: on Steven Piano the pedal up, then
     * All Notes Off; on a MIDI piano a Note Off for every key held first (many ignore All Notes Off), then
     * every pedal it takes at 0, then All Notes Off. Forgets every held key, live ones too.
     */
    fun silence(out: MidiBatch) {
        if (profile.explicitOffs) {
            for (key in 0..127) if (refCount[key] > 0) out.add(0x80, key, 0)
            for (controller in profile.pedals) out.add(0xB0, controller, 0)
        } else {
            out.add(0xB0, SUSTAIN, 0)
        }
        out.add(0xB0, ALL_NOTES_OFF, 0)
        sentKey.fill(KeyMap.UNPLAYABLE)
        refCount.fill(0)
        displayCount.fill(0)
        sentValue.fill(UNKNOWN)
        for (controller in profile.pedals) sentValue[controller] = 0
        fileValue.fill(0)
        externalValue.fill(UNTOUCHED)
        pendingPedal = NONE
        pedalCredit = PEDAL_CREDIT_MAX   // what follows a silence (a resumed piece's pedal) goes at once
        pedalCreditAt = NEVER
        liveSustainDown = false
        activeLow = 0L
        activeHigh = 0L
    }

    private fun noteOn(source: Int, note: Int, velocity: Int, nowMicros: Long, out: MidiBatch) {
        release(source, out)   // a source struck again while sounding is released first
        val key = KeyMap.map(note, transpose, fold, profile.lowest, profile.highest)
        if (key == KeyMap.UNPLAYABLE) return
        strike(source, key, velocity, nowMicros, out)
    }

    /**
     * [source] now sounds [key]: a Note On when the key goes 0 -> 1 and is not thinned; on an instrument that
     * can, also when another source holds it already (a Note Off, then the Note On).
     */
    private fun strike(source: Int, key: Int, velocity: Int, nowMicros: Long, out: MidiBatch) {
        val v = ((velocity * velocityPct + 50) / 100).coerceIn(1, 127)
        if (refCount[key] == 0) {
            if (nowMicros - lastOnsetMicros[key] < profile.minOnsetGapMicros) return   // thinned
            lastOnsetMicros[key] = nowMicros
            out.add(0x90, key, v)
            setActive(key, true)
        } else if (profile.restrike) {
            lastOnsetMicros[key] = nowMicros
            out.add(0x80, key, 0)
            out.add(0x90, key, v)
        }
        refCount[key]++
        sentKey[source] = key
    }

    /** A controller's new value, once per change. */
    private fun setController(controller: Int, value: Int, out: MidiBatch) {
        if (value == sentValue[controller]) return
        sentValue[controller] = value
        out.add(0xB0, controller, value)
    }

    /** A piece's pedal: on Steven Piano CC64 is paced; anything else goes now. */
    private fun filePedal(controller: Int, value: Int, nowMicros: Long, out: MidiBatch) {
        fileValue[controller] = value
        if (controller == SUSTAIN && profile.pacedPedal) pacePedal(value, nowMicros, out) else setController(controller, value, out)
    }

    /** CC64 now if the bucket allows, else it waits (replacing any change already waiting). */
    private fun pacePedal(value: Int, nowMicros: Long, out: MidiBatch) {
        refillPedal(nowMicros)
        if (pedalCredit >= PEDAL_GAP_MICROS) {
            pendingPedal = NONE
            spendPedal(value, out)
        } else {
            pendingPedal = if (value == sentValue[SUSTAIN]) NONE else value   // back where it is: nothing to send
        }
    }

    private fun spendPedal(value: Int, out: MidiBatch) {
        if (value == sentValue[SUSTAIN]) return
        pedalCredit -= PEDAL_GAP_MICROS
        setController(SUSTAIN, value, out)
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

    /** The screen's view of [key] sounding: a key outside its 84 shows an octave in, counted, so two keys on one place share it. */
    private fun setActive(key: Int, on: Boolean) {
        val shown = KeyMap.map(key, 0, true) - KeyMap.LOWEST
        val count = (displayCount[shown] + if (on) 1 else -1).coerceAtLeast(0)
        displayCount[shown] = count
        val lit = count > 0
        if (shown < 64) {
            activeLow = if (lit) activeLow or (1L shl shown) else activeLow and (1L shl shown).inv()
        } else {
            activeHigh = if (lit) activeHigh or (1L shl (shown - 64)) else activeHigh and (1L shl (shown - 64)).inv()
        }
    }

    companion object {
        const val DRUM_CHANNEL = 9   // "channel 10"
        const val SUSTAIN = 64
        const val SOSTENUTO = 66
        const val SOFT = 67
        const val ALL_NOTES_OFF = 123
        const val MIN_ONSET_GAP_MICROS = 100_000L
        private const val PEDAL_DOWN = 127

        /** Where a pedal goes from up to down (the firmware's switch point). */
        private const val HALF = 64

        /** The first live source: after the 16 x 128 file sources. */
        private const val LIVE = 16 * 128

        /** The first keyboard source (v1.11 — M29), by the note as played. */
        private const val EXT = LIVE + 128
        private const val SOURCES = EXT + 128
        private const val NEVER = Long.MIN_VALUE / 2
        private const val UNKNOWN = -1
        private const val NONE = -1
        private const val UNTOUCHED = -1

        /** One pedal change per 50 ms (20 a second) once the burst is spent. */
        const val PEDAL_GAP_MICROS = 50_000L

        /** Pedal changes that may go back to back after a quiet spell. */
        const val PEDAL_BURST = 3
        private const val PEDAL_CREDIT_MAX = PEDAL_BURST * PEDAL_GAP_MICROS
    }
}

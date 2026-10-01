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
 * A MIDI keyboard's raw bytes, as Android's MIDI service hands them over (v1.11 — M29), turned into the
 * few events the app uses. A device is untrusted input: the parser keeps three small fields, allocates
 * nothing from what arrives, masks every value, and never throws.
 *
 * | Byte | Rule |
 * |---|---|
 * | `F8`–`FF` | Real-time: may fall inside a message and never disturbs it. Ignored, but for `FE` (Active Sensing, [Events.activeSensing]) and `FF` (System Reset, [Events.systemReset], which lets go of everything). |
 * | `F0` | SysEx: every byte up to `F7` or the next status is dropped; running status is cleared. |
 * | `F1`–`F7` | System common: clears running status; its data bytes (one for `F1` and `F3`, two for `F2`) are dropped. |
 * | `80`–`EF` | Becomes running status; `C0` and `D0` take one data byte, the rest two. Running status lasts across callbacks. |
 * | Data byte with a status | Completes a message. |
 * | Data byte with none, or a message cut short by the next status | Dropped and counted in [malformed]. |
 *
 * What comes out ([Events]): Note On, Note Off (a Note On at velocity 0 is one), CC64 / CC66 / CC67 with
 * their values, All Notes Off for CC120 and CC123–127 (that channel's keys), and CC121 (that channel's
 * pedals to 0). Everything else (other controllers, program changes, bends, pressure, clocks) is dropped.
 * One parser per port, on that port's thread.
 */
class MidiStreamParser(private val out: Events) {
    /** Where events go: [KeyboardHolds], or a recorder in tests. Channels are 0-15, keys and values 0-127. */
    interface Events {
        fun noteOn(channel: Int, key: Int, velocity: Int)

        fun noteOff(channel: Int, key: Int)

        /** CC64 (sustain), CC66 (sostenuto) or CC67 (soft) on [channel] at [value]. */
        fun control(channel: Int, controller: Int, value: Int)

        /** CC120 or CC123–127: every key [channel] holds goes up. */
        fun allNotesOff(channel: Int)

        /** CC121: [channel]'s pedals go to 0. */
        fun resetControllers(channel: Int)

        /** `FE`: the device says it is alive. */
        fun activeSensing() {}

        /** `FF`: everything goes up. */
        fun systemReset()
    }

    /** Bytes dropped as malformed since the parser was made (a data byte with no status, a message cut short). */
    var malformed = 0L
        private set

    /** The running status (`80`–`EF`), or 0 for none. */
    private var status = 0

    /** The first data byte of a two-byte message, or -1. */
    private var first = -1

    /** Data bytes of a system common message still to drop. */
    private var skip = 0
    private var sysex = false

    /** Every byte of [data] from [offset], [count] of them (held to the array's bounds). */
    fun feed(data: ByteArray, offset: Int, count: Int) {
        val from = offset.coerceIn(0, data.size)
        val end = (from.toLong() + count.coerceAtLeast(0)).coerceAtMost(data.size.toLong()).toInt()
        for (i in from until end) byte(data[i].toInt() and 0xFF)
    }

    /** One byte, 0-255. */
    fun byte(value: Int) {
        val b = value and 0xFF
        if (b >= 0xF8) {   // real-time: never disturbs the message it falls in
            when (b) {
                0xFE -> out.activeSensing()
                0xFF -> {
                    clear()
                    out.systemReset()
                }
            }
            return
        }
        if (b >= 0x80) {
            if (first >= 0) malformed++   // a message cut short
            first = -1
            sysex = b == 0xF0
            status = if (b < 0xF0) b else 0
            skip = if (b in 0xF1..0xF7) SYSTEM_COMMON_DATA[b - 0xF0] else 0
            return
        }
        when {
            sysex -> Unit
            skip > 0 -> skip--
            status == 0 -> malformed++
            status and 0xF0 == 0xC0 || status and 0xF0 == 0xD0 -> Unit   // one data byte, and nothing the app uses
            first < 0 -> first = b
            else -> {
                val data1 = first
                first = -1
                message(status, data1, b)
            }
        }
    }

    /** Back to the start: no running status, nothing half read (a System Reset, or a port opened again). */
    fun clear() {
        status = 0
        first = -1
        skip = 0
        sysex = false
    }

    private fun message(status: Int, data1: Int, data2: Int) {
        val channel = status and 0x0F
        when (status and 0xF0) {
            0x90 -> if (data2 == 0) out.noteOff(channel, data1) else out.noteOn(channel, data1, data2)
            0x80 -> out.noteOff(channel, data1)
            0xB0 -> when (data1) {
                SUSTAIN, SOSTENUTO, SOFT -> out.control(channel, data1, data2)
                ALL_SOUND_OFF, in ALL_NOTES_OFF..POLY_ON -> out.allNotesOff(channel)
                RESET_CONTROLLERS -> out.resetControllers(channel)
            }
        }
    }

    companion object {
        const val SUSTAIN = 64
        const val SOSTENUTO = 66
        const val SOFT = 67
        const val ALL_SOUND_OFF = 120
        const val RESET_CONTROLLERS = 121
        const val ALL_NOTES_OFF = 123
        const val POLY_ON = 127

        /** Data bytes after `F0`…`F7`: `F1` (a time code quarter frame) one, `F2` (song position) two, `F3` (song select) one. */
        private val SYSTEM_COMMON_DATA = intArrayOf(0, 1, 2, 1, 0, 0, 0, 0)
    }
}

/**
 * What a keyboard holds down (v1.11 — M29): per key, the channels that hold it (a 16-bit mask), so a key
 * goes down when its first channel takes it and up when its last lets go, and a keyboard in dual or layer
 * mode (one key, two channels) is one press; per pedal (CC64, CC66, CC67) the highest value across the
 * channels. What changes goes to [out] as [KeyEvents]: a key down (also a key struck again on a channel
 * already holding it: a lost release, which the router may re-strike), a key up, a pedal's new value.
 * [releaseAll] lets go of everything (the device lost or silent, the flood breaker). Not thread-safe: the
 * keyboard calls it under its own lock.
 */
class KeyboardHolds(private val out: KeyEvents) : MidiStreamParser.Events {
    private val masks = IntArray(128)
    private val pedals = Array(PEDALS.size) { IntArray(16) }
    private val pedalSent = IntArray(PEDALS.size)

    /** Keys held now. */
    var held = 0
        private set

    /** Whether any key or pedal is down. */
    val anyDown: Boolean get() = held > 0 || pedalSent.any { it > 0 }

    fun isDown(key: Int): Boolean = key in 0..127 && masks[key] != 0

    /** The value pedal [controller] (64, 66, 67) is at; 0 for any other. */
    fun pedal(controller: Int): Int = PEDALS.indexOf(controller).let { if (it < 0) 0 else pedalSent[it] }

    override fun noteOn(channel: Int, key: Int, velocity: Int) {
        val k = key and 0x7F
        val bit = 1 shl (channel and 0x0F)
        val was = masks[k]
        masks[k] = was or bit
        when {
            was == 0 -> {
                held++
                out.add(KeyEvents.DOWN, k, velocity and 0x7F)
            }
            was and bit != 0 -> out.add(KeyEvents.DOWN, k, velocity and 0x7F)   // struck again on its own channel: a release was lost
        }
    }

    override fun noteOff(channel: Int, key: Int) = lift(key and 0x7F, 1 shl (channel and 0x0F))

    override fun control(channel: Int, controller: Int, value: Int) {
        val p = PEDALS.indexOf(controller)
        if (p < 0) return
        pedals[p][channel and 0x0F] = value and 0x7F
        sendPedal(p)
    }

    override fun allNotesOff(channel: Int) {
        val bit = 1 shl (channel and 0x0F)
        for (k in 0..127) if (masks[k] and bit != 0) lift(k, bit)
    }

    override fun resetControllers(channel: Int) {
        for (p in PEDALS.indices) {
            pedals[p][channel and 0x0F] = 0
            sendPedal(p)
        }
    }

    override fun systemReset() = releaseAll()

    /** Every key up and every pedal at 0, each change told. */
    fun releaseAll() {
        for (k in 0..127) if (masks[k] != 0) lift(k, masks[k])
        for (p in PEDALS.indices) {
            pedals[p].fill(0)
            sendPedal(p)
        }
    }

    private fun lift(key: Int, bits: Int) {
        val was = masks[key]
        if (was and bits == 0) return
        masks[key] = was and bits.inv()
        if (masks[key] == 0) {
            held--
            out.add(KeyEvents.UP, key, 0)
        }
    }

    private fun sendPedal(p: Int) {
        val highest = pedals[p].max()
        if (highest == pedalSent[p]) return
        pedalSent[p] = highest
        out.add(KeyEvents.PEDAL, PEDALS[p], highest)
    }

    companion object {
        /** The pedals a keyboard may move: sustain, sostenuto, soft. */
        val PEDALS = intArrayOf(MidiStreamParser.SUSTAIN, MidiStreamParser.SOSTENUTO, MidiStreamParser.SOFT)
    }
}

/**
 * A keyboard's events from one buffer of bytes (v1.11 — M29), in order: what [KeyboardHolds] decided, as
 * packed ints, reused buffer after buffer (it grows only past the most a buffer ever held). Readers copy
 * what they keep before returning.
 */
class KeyEvents {
    private var packed = IntArray(64)

    var size: Int = 0
        private set

    fun isEmpty(): Boolean = size == 0

    fun add(type: Int, key: Int, value: Int) {
        if (size == packed.size) packed = packed.copyOf(size * 2)
        packed[size++] = (type shl 16) or ((key and 0x7F) shl 8) or (value and 0x7F)
    }

    /** [DOWN], [UP] or [PEDAL]. */
    fun type(i: Int): Int = packed[i] ushr 16

    /** The key, or the pedal's controller number. */
    fun key(i: Int): Int = (packed[i] ushr 8) and 0xFF

    /** A key's velocity, or a pedal's value. */
    fun value(i: Int): Int = packed[i] and 0xFF

    /** Note Ons in the buffer. */
    fun downs(): Int {
        var n = 0
        for (i in 0 until size) if (type(i) == DOWN) n++
        return n
    }

    fun clear() {
        size = 0
    }

    companion object {
        const val DOWN = 1
        const val UP = 2
        const val PEDAL = 3
    }
}

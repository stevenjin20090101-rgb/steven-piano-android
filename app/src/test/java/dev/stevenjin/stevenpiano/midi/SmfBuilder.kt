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
import java.nio.charset.Charset

/** Writes Standard MIDI Files for tests. Events take absolute ticks; deltas are computed. */
class SmfBuilder(
    private val format: Int = 1,
    private val division: Int = 480,
    private val headerExtra: ByteArray = ByteArray(0),
    private val declaredTracks: Int? = null,
) {
    private val chunks = ByteArrayOutputStream()
    private var tracks = 0

    fun track(block: Track.() -> Unit): SmfBuilder = apply {
        writeChunk("MTrk", Track().apply(block).body())
        tracks++
    }

    fun unknownChunk(id: String, body: ByteArray): SmfBuilder = apply { writeChunk(id, body) }

    fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray())
        out.u32(6 + headerExtra.size)
        out.u16(format)
        out.u16(declaredTracks ?: tracks)
        out.u16(division)
        out.write(headerExtra)
        out.write(chunks.toByteArray())
        return out.toByteArray()
    }

    private fun writeChunk(id: String, body: ByteArray) {
        chunks.write(id.toByteArray())
        chunks.u32(body.size)
        chunks.write(body)
    }

    class Track {
        private val out = ByteArrayOutputStream()
        private var lastTick = 0L
        private var ended = false

        fun noteOn(tick: Long, key: Int, velocity: Int = 80, channel: Int = 0) =
            raw(tick, 0x90 or channel, key, velocity)

        fun noteOff(tick: Long, key: Int, channel: Int = 0) = raw(tick, 0x80 or channel, key, 64)

        fun cc(tick: Long, controller: Int, value: Int, channel: Int = 0) =
            raw(tick, 0xB0 or channel, controller, value)

        fun tempo(tick: Long, microsPerQuarter: Int) =
            meta(tick, 0x51, byteArrayOf((microsPerQuarter shr 16).toByte(), (microsPerQuarter shr 8).toByte(), microsPerQuarter.toByte()))

        fun name(tick: Long, text: String, charset: Charset = Charsets.UTF_8) = meta(tick, 0x03, text.toByteArray(charset))

        fun meta(tick: Long, type: Int, data: ByteArray) {
            raw(tick, 0xFF, type, *varLen(data.size))
            out.write(data)
        }

        fun end(tick: Long) {
            raw(tick, 0xFF, 0x2F, 0x00)
            ended = true
        }

        /** A delta time, then [bytes] exactly as given: for running status and damage. */
        fun raw(tick: Long, vararg bytes: Int) {
            require(tick >= lastTick) { "Events must be added in time order" }
            out.write(varLen((tick - lastTick).toInt()).map { it.toByte() }.toByteArray())
            bytes.forEach { out.write(it) }
            lastTick = tick
        }

        fun body(): ByteArray {
            if (!ended) end(lastTick)
            return out.toByteArray()
        }
    }

    companion object {
        fun varLen(value: Int): IntArray {
            var v = value
            val groups = mutableListOf(v and 0x7F)
            v = v ushr 7
            while (v > 0) {
                groups.add(0, (v and 0x7F) or 0x80)
                v = v ushr 7
            }
            return groups.toIntArray()
        }

        private fun ByteArrayOutputStream.u16(v: Int) {
            write(v ushr 8)
            write(v)
        }

        private fun ByteArrayOutputStream.u32(v: Int) {
            u16(v ushr 16)
            u16(v and 0xFFFF)
        }
    }
}

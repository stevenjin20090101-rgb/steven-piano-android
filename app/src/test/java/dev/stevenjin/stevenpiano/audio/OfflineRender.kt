// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import dev.stevenjin.stevenpiano.midi.MidiPiece
import dev.stevenjin.stevenpiano.player.PlaybackEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The tablet's sound without a tablet (v1.8 — M25): a piece played by the app's own [PlaybackEngine]
 * (its router: folding, the velocity percentage, the pedal's pacing and the stop sequence) on a clock
 * the test turns, into a [PianoVoice] as the player feeds it, rendered frame for frame between the
 * engine's events. What comes out is what the tablet would play, at [rate], mono.
 */
object OfflineRender {
    /** [piece] from its start to its end and [tailSeconds] after, at [volume] %. */
    fun piece(font: SoundFont, piece: MidiPiece, rate: Int = 48_000, volume: Int = Sampler.DEFAULT_VOLUME, tailSeconds: Double = 3.0): FloatArray {
        maxVoices = 0
        val voice = PianoVoice(rate)
        voice.load(font)
        voice.volume(volume)
        val engine = PlaybackEngine(voice.sink { true })
        engine.load(piece, 0L)
        engine.play(0L)
        val out = Growing()
        var nanos = 0L
        while (true) {
            val next = engine.advance(nanos)
            val until = if (next == Long.MAX_VALUE) nanos else next
            out.renderTo(voice, frameAt(until, rate))
            if (next == Long.MAX_VALUE) break
            nanos = next
        }
        out.renderTo(voice, out.size + (tailSeconds * rate).toInt())
        return out.array()
    }

    private fun frameAt(nanos: Long, rate: Int): Int = (nanos * rate / 1_000_000_000L).toInt()

    /** The most voices that sounded at once in the last [piece] rendered. */
    @Volatile
    var maxVoices = 0
        private set

    /** A mono buffer that grows as frames are rendered into it. */
    class Growing {
        private var data = FloatArray(1 shl 16)
        var size = 0
            private set
        private val block = FloatArray(Sampler.BLOCK * 16)

        fun renderTo(voice: PianoVoice, frame: Int) {
            while (size < frame) {
                val n = minOf(block.size, frame - size)
                voice.render(block, n)
                maxVoices = maxOf(maxVoices, voice.voices())
                if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
                System.arraycopy(block, 0, data, size, n)
                size += n
            }
        }

        fun array(): FloatArray = data.copyOf(size)
    }

    /** [mono] as a 16-bit PCM WAV of two identical channels at [rate]: what the tablet's output writes. */
    fun wav(mono: FloatArray, rate: Int): ByteArray {
        val channels = 2
        val body = ByteBuffer.allocate(mono.size * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in mono) {
            val s = (x.coerceIn(-1f, 1f) * 32_767).roundToInt().toShort()
            repeat(channels) { body.putShort(s) }
        }
        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + body.capacity()).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        header.put("data".toByteArray()).putInt(body.capacity())
        out.write(header.array())
        out.write(body.array())
        return out.toByteArray()
    }

    fun writeWav(file: File, mono: FloatArray, rate: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(wav(mono, rate))
    }

    fun peak(x: FloatArray): Float = x.maxOfOrNull { kotlin.math.abs(it) } ?: 0f

    fun rms(x: FloatArray): Float = if (x.isEmpty()) 0f else sqrt(x.sumOf { it.toDouble() * it } / x.size).toFloat()
}

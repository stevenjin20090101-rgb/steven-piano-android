// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import kotlinx.coroutines.CancellationException
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** What a recording may be, and how Studio's audio is held (v1.7 — M23). */
object AudioLimits {
    /** The largest file Studio reads: 200 MB. */
    const val MAX_FILE_BYTES = 200L * 1024 * 1024

    /** The longest recording: 20 minutes. */
    const val MAX_SECONDS = 20 * 60

    /** The transcription model's input: 16 kHz, mono. */
    const val RATE = Resample.MODEL_RATE
}

/** A recording that can't be used, for the reason the person is told in [message]. */
class AudioFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    companion object {
        const val UNREADABLE = "This file isn't a recording the tablet can read."
        const val TOO_LONG = "A recording can be 20 minutes long at most."
        const val TOO_LARGE = "A recording can be 200 MB at most."
        const val EMPTY = "The recording is empty."

        /** The system's audio picker offers MIDI files too (they are audio/midi): they need no transcribing. */
        const val MIDI = "That's a MIDI file already. Add it with Add files."
    }
}

/**
 * A recording decoded for the model: [samples] up to [size], 16 kHz mono, in [-1, 1) (int16 / 32768
 * for 16-bit sources, as the spike's fixtures read them); [sourceRate] and [sourceChannels] as the
 * file had them. [samples] may be longer than [size] (its spare room is never read).
 */
class DecodedAudio(val samples: FloatArray, val size: Int, val sourceRate: Int, val sourceChannels: Int) {
    val seconds: Double get() = size.toDouble() / AudioLimits.RATE
}

/**
 * Interleaved frames of any rate and channel count, made 16 kHz mono as they arrive: the channels
 * averaged, then [Resampler] (none at 16 kHz). At most [AudioLimits.MAX_SECONDS] of the source's
 * frames; more throws [AudioFailure.TOO_LONG]. [estimatedFrames] sizes the output once, so a long
 * recording is not copied as it grows. Pure.
 */
class MonoTo16k(private val rate: Int, private val channels: Int, estimatedFrames: Long = 0) {
    init {
        if (rate !in MIN_RATE..MAX_RATE || channels !in 1..MAX_CHANNELS) throw AudioFailure(AudioFailure.UNREADABLE)
    }

    private val resampler = if (rate == AudioLimits.RATE) null else Resampler(rate, AudioLimits.RATE)
    private val out = FloatBuilder(
        estimatedFrames.coerceIn(0L, AudioLimits.MAX_SECONDS.toLong() * rate).let { frames ->
            (if (resampler == null) frames else resampler.outputLength(frames)).toInt() + ESTIMATE_SLACK
        },
    )
    private val mono = FloatArray(BLOCK)
    private var frames = 0L
    private val maxFrames = AudioLimits.MAX_SECONDS.toLong() * rate

    /** [count] frames of [interleaved] samples, [channels] to a frame. */
    fun frames(interleaved: FloatArray, count: Int) {
        if (frames + count > maxFrames) throw AudioFailure(AudioFailure.TOO_LONG)
        frames += count
        var done = 0
        while (done < count) {
            val n = minOf(BLOCK, count - done)
            if (channels == 1) {
                System.arraycopy(interleaved, done, mono, 0, n)
            } else {
                var at = done * channels
                for (i in 0 until n) {
                    var sum = 0f
                    for (c in 0 until channels) sum += interleaved[at + c]
                    mono[i] = sum / channels
                    at += channels
                }
            }
            if (resampler == null) out.write(mono, n) else resampler.push(mono, n, out)
            done += n
        }
    }

    /** The recording, done. [AudioFailure.EMPTY] when no frame came. */
    fun finish(): DecodedAudio {
        if (frames == 0L) throw AudioFailure(AudioFailure.EMPTY)
        resampler?.finish(out)
        return DecodedAudio(out.array, out.size, rate, channels)
    }

    companion object {
        const val MIN_RATE = 1_000
        const val MAX_RATE = 768_000
        const val MAX_CHANNELS = 16
        private const val BLOCK = 4096
        private const val ESTIMATE_SLACK = 64
    }
}

/**
 * WAV files (RIFF/WAVE), read without Android so the whole path from file to model input is tested on
 * the JVM: PCM of 8, 16, 24 or 32 bits, IEEE float of 32 or 64 bits, and WAVE_FORMAT_EXTENSIBLE of
 * those, any rate from 1 kHz to 768 kHz and 1 to 16 channels, made 16 kHz mono by [MonoTo16k] as it is
 * read. A `data` chunk whose size is 0 or 0xFFFFFFFF (a recorder that never went back to write it)
 * runs to the end of the file. The announced length is checked before anything is decoded (20
 * minutes at most). Everything else is [AudioFailure.UNREADABLE].
 */
object WavReader {
    /** Whether [header] (the file's first 12 bytes) starts a WAV file. */
    fun isWav(header: ByteArray): Boolean =
        header.size >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE"

    /** Whether [header] (the file's first 12 bytes) starts a MIDI file: a standard one ("MThd") or RIFF's RMID. */
    fun isMidi(header: ByteArray): Boolean =
        header.size >= 12 && (
            String(header, 0, 4, Charsets.US_ASCII) == "MThd" ||
                (String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "RMID")
            )

    /** Decodes the WAV file [input] (its first byte next); [cancelled] is asked between blocks. Blocking. */
    fun decode(input: InputStream, cancelled: () -> Boolean = { false }): DecodedAudio {
        try {
            val header = ByteArray(12)
            readFully(input, header)
            if (!isWav(header)) throw AudioFailure(AudioFailure.UNREADABLE)
            var format: Format? = null
            while (true) {
                val chunk = ByteArray(8)
                if (!readChunkHeader(input, chunk)) throw AudioFailure(if (format == null) AudioFailure.UNREADABLE else AudioFailure.EMPTY)
                val id = String(chunk, 0, 4, Charsets.US_ASCII)
                val size = u32(chunk, 4)
                when (id) {
                    "fmt " -> {
                        if (size < 16 || size > 1024) throw AudioFailure(AudioFailure.UNREADABLE)
                        val body = ByteArray(size.toInt())
                        readFully(input, body)
                        if (size % 2 == 1L) skip(input, 1)
                        format = Format.parse(body)
                    }
                    "data" -> {
                        val fmt = format ?: throw AudioFailure(AudioFailure.UNREADABLE)
                        val toEnd = size == 0L || size == 0xFFFF_FFFFL
                        val frames = if (toEnd) -1L else size / fmt.blockAlign
                        if (frames > AudioLimits.MAX_SECONDS.toLong() * fmt.rate) throw AudioFailure(AudioFailure.TOO_LONG)
                        return decodeData(input, fmt, frames, cancelled)
                    }
                    else -> skip(input, size + (size and 1L))
                }
            }
        } catch (e: EOFException) {
            throw AudioFailure(AudioFailure.UNREADABLE, e)
        } catch (e: IOException) {
            throw AudioFailure(AudioFailure.UNREADABLE, e)
        }
    }

    /** The sample format of a `fmt ` chunk. */
    private class Format(val encoding: Int, val channels: Int, val rate: Int, val blockAlign: Int, val bits: Int) {
        val bytes: Int get() = bits / 8

        companion object {
            const val PCM = 1
            const val FLOAT = 3
            const val EXTENSIBLE = 0xFFFE

            fun parse(body: ByteArray): Format {
                var encoding = u16(body, 0)
                val channels = u16(body, 2)
                val rate = u32(body, 4)
                val blockAlign = u16(body, 12)
                val bits = u16(body, 14)
                if (encoding == EXTENSIBLE) {
                    if (body.size < 40) throw AudioFailure(AudioFailure.UNREADABLE)
                    encoding = u16(body, 24)   // the sub-format GUID's first two bytes
                }
                val ok = when (encoding) {
                    PCM -> bits in setOf(8, 16, 24, 32)
                    FLOAT -> bits == 32 || bits == 64
                    else -> false
                }
                if (!ok || channels !in 1..MonoTo16k.MAX_CHANNELS || rate !in MonoTo16k.MIN_RATE..MonoTo16k.MAX_RATE) {
                    throw AudioFailure(AudioFailure.UNREADABLE)
                }
                if (blockAlign != channels * (bits / 8)) throw AudioFailure(AudioFailure.UNREADABLE)
                return Format(encoding, channels, rate.toInt(), blockAlign, bits)
            }
        }
    }

    private fun decodeData(input: InputStream, fmt: Format, frames: Long, cancelled: () -> Boolean): DecodedAudio {
        val sink = MonoTo16k(fmt.rate, fmt.channels, if (frames > 0) frames else fmt.rate.toLong() * 60)
        val block = ByteArray(BLOCK_FRAMES * fmt.blockAlign)
        val samples = FloatArray(BLOCK_FRAMES * fmt.channels)
        var left = if (frames < 0) Long.MAX_VALUE else frames
        while (left > 0) {
            if (cancelled()) throw CancellationException("The recording's reading was cancelled")
            val want = minOf(left, BLOCK_FRAMES.toLong()).toInt() * fmt.blockAlign
            val got = readUpTo(input, block, want)
            val whole = got / fmt.blockAlign
            if (whole == 0) break
            convert(block, whole * fmt.channels, fmt, samples)
            sink.frames(samples, whole)
            left -= whole
            if (got < want) break   // the file ended before its data chunk said: what came is kept
        }
        return sink.finish()
    }

    /** [count] samples of [bytes] (little-endian, [fmt]'s encoding) as floats in [-1, 1). */
    private fun convert(bytes: ByteArray, count: Int, fmt: Format, out: FloatArray) {
        var at = 0
        for (i in 0 until count) {
            out[i] = when {
                fmt.encoding == Format.FLOAT && fmt.bits == 32 -> Float.fromBits(s32(bytes, at))
                fmt.encoding == Format.FLOAT -> Double.fromBits(s64(bytes, at)).toFloat()
                fmt.bits == 8 -> ((bytes[at].toInt() and 0xFF) - 128) / 128f
                fmt.bits == 16 -> ((bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)).toShort() / 32768f
                fmt.bits == 24 -> ((bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or (bytes[at + 2].toInt() shl 16)) / 8_388_608f
                else -> s32(bytes, at) / 2_147_483_648f
            }
            at += fmt.bytes
        }
    }

    private const val BLOCK_FRAMES = 4096

    private fun readChunkHeader(input: InputStream, into: ByteArray): Boolean {
        val got = readUpTo(input, into, into.size)
        if (got == 0) return false
        if (got < into.size) throw EOFException("A chunk header cut short")
        return true
    }

    private fun readFully(input: InputStream, into: ByteArray) {
        if (readUpTo(input, into, into.size) < into.size) throw EOFException("The file ended early")
    }

    /** Reads until [want] bytes or the end; how many came. */
    private fun readUpTo(input: InputStream, into: ByteArray, want: Int): Int {
        var got = 0
        while (got < want) {
            val n = input.read(into, got, want - got)
            if (n < 0) break
            got += n
        }
        return got
    }

    private fun skip(input: InputStream, count: Long) {
        var left = count
        val scratch = ByteArray(8192)
        while (left > 0) {
            val n = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (n < 0) throw EOFException("A chunk cut short")
            left -= n
        }
    }

    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int): Long = s32(b, at).toLong() and 0xFFFF_FFFFL

    private fun s32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or (b[at + 3].toInt() shl 24)

    private fun s64(b: ByteArray, at: Int): Long = (s32(b, at).toLong() and 0xFFFF_FFFFL) or (s32(b, at + 4).toLong() shl 32)
}

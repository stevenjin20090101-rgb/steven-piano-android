// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The Studio spike's fixtures (`tools/studio/fixtures`, M22), found from the test's working folder
 * upwards, and small helpers the Studio tests share: WAV files written in memory and a one-bin DFT.
 */
object StudioFixtures {
    /** A fixture file by name. */
    fun file(name: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "tools/studio/fixtures/$name")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("tools/studio/fixtures/$name not found above ${System.getProperty("user.dir")}")
    }

    /** `transcription_window.json`: the INT8 model's raw outputs for the fixture window, and what the package made of them. */
    val window: JSONObject by lazy { JSONObject(file("transcription_window.json").readText()) }

    /** The fixture window's audio as the spike read it: int16 / 32768, 160,000 samples. */
    fun windowSamples(): FloatArray {
        val bytes = file("transcription_window.wav").readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val data = 44   // the spike wrote a plain 44-byte header (soundfile, PCM_16)
        val n = (bytes.size - data) / 2
        return FloatArray(n) { buffer.getShort(data + 2 * it) / 32768f }
    }

    /** A WAV file of [frames] frames of [channels] channels at [rate], each sample from [sample] as a float in [-1, 1]. */
    fun wav(
        rate: Int,
        channels: Int,
        frames: Int,
        bits: Int = 16,
        float: Boolean = false,
        extensible: Boolean = false,
        dataSize: Long? = null,
        extraChunk: Boolean = false,
        sample: (frame: Int, channel: Int) -> Double,
    ): ByteArray {
        val bytesPer = bits / 8
        val data = ByteArrayOutputStream()
        val one = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val v = sample(f, c).coerceIn(-1.0, 1.0)
                one.clear()
                when {
                    float && bits == 32 -> one.putFloat(v.toFloat())
                    float -> one.putDouble(v)
                    bits == 8 -> one.put(((v * 127).toInt() + 128).toByte())
                    bits == 16 -> one.putShort((v * 32767).toInt().toShort())
                    bits == 24 -> (v * 8_388_607).toInt().let { one.put(it.toByte()).put((it shr 8).toByte()).put((it shr 16).toByte()) }
                    else -> one.putInt((v * 2_147_483_647.0).toLong().toInt())
                }
                data.write(one.array(), 0, bytesPer)
            }
        }
        val body = data.toByteArray()
        val fmtSize = if (extensible) 40 else 16
        val out = ByteBuffer.allocate(12 + 8 + fmtSize + (if (extraChunk) 8 + 5 + 1 else 0) + 8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(out.capacity() - 8).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(fmtSize)
        val tag = if (float) 3 else 1
        out.putShort((if (extensible) 0xFFFE else tag).toShort()).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * bytesPer).putShort((channels * bytesPer).toShort()).putShort(bits.toShort())
        if (extensible) {
            out.putShort(22).putShort(bits.toShort()).putInt(0)
            out.putShort(tag.toShort()).put(ByteArray(14))   // the sub-format GUID: its first two bytes are the format
        }
        if (extraChunk) out.put("LIST".toByteArray()).putInt(5).put("abcde".toByteArray()).put(0)   // odd size: one pad byte
        out.put("data".toByteArray()).putInt((dataSize ?: body.size.toLong()).toInt()).put(body)
        return out.array()
    }

    /** The amplitude of [frequency] Hz in [samples] (at [rate]) from [from] to [to]: one bin of a DFT, times two over the length. */
    fun amplitude(samples: FloatArray, rate: Int, frequency: Double, from: Int = 0, to: Int = samples.size): Double {
        var re = 0.0
        var im = 0.0
        for (i in from until to) {
            val phase = 2 * PI * frequency * i / rate
            re += samples[i] * cos(phase)
            im -= samples[i] * sin(phase)
        }
        return 2 * hypot(re, im) / (to - from)
    }

    /** A sine of [frequency] Hz at [rate], [length] samples, amplitude [amplitude]. */
    fun sine(rate: Int, frequency: Double, length: Int, amplitude: Double = 0.5): FloatArray =
        FloatArray(length) { (amplitude * sin(2 * PI * frequency * it / rate)).toFloat() }
}

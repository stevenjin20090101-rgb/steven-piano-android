// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import dev.stevenjin.stevenpiano.studio.StudioFixtures.amplitude
import dev.stevenjin.stevenpiano.studio.StudioFixtures.wav
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.sin

/**
 * A recording into the model's input (v1.7 — M23), the WAV path the JVM can run: the spike's fixture
 * window reads back exactly as the spike fed it (int16 / 32768), other rates, channels and sample
 * formats come out 16 kHz mono, and the caps refuse before anything is decoded.
 */
class AudioDecoderTest {
    private fun decode(bytes: ByteArray, cancelled: () -> Boolean = { false }) = WavReader.decode(ByteArrayInputStream(bytes), cancelled)

    @Test
    fun `the fixture window reads back as the spike fed it to the model`() {
        val file = StudioFixtures.file("transcription_window.wav")
        val audio = file.inputStream().buffered().use { WavReader.decode(it) }
        assertEquals(16_000, audio.sourceRate)
        assertEquals(1, audio.sourceChannels)
        assertEquals(160_000, audio.size)
        assertEquals(10.0, audio.seconds, 0.0)
        val expected = StudioFixtures.windowSamples()
        for (i in 0 until audio.size) assertEquals("sample $i", expected[i], audio.samples[i], 0f)
        assertTrue(WavReader.isWav(file.readBytes().copyOf(12)))
    }

    @Test
    fun `a 44_1 kHz stereo file comes out 16 kHz mono, the channels averaged`() {
        val bytes = wav(44_100, 2, 44_100) { f, c -> if (c == 0) 0.6 * sin(2 * PI * 1_000 * f / 44_100) else 0.0 }
        val audio = decode(bytes)
        assertEquals(44_100, audio.sourceRate)
        assertEquals(2, audio.sourceChannels)
        assertEquals(16_000, audio.size)
        val samples = audio.samples.copyOf(audio.size)
        assertEquals("the left channel's 0.6 halved", 0.3, amplitude(samples, 16_000, 1_000.0, 2_000, 14_000), 0.002)
    }

    @Test
    fun `8, 24 and 32-bit PCM, float and extensible files all read`() {
        val tone = { f: Int, _: Int -> 0.5 * sin(2 * PI * 440 * f / 16_000) }
        for ((label, bytes) in listOf(
            "8-bit" to wav(16_000, 1, 16_000, bits = 8, sample = tone),
            "24-bit" to wav(16_000, 1, 16_000, bits = 24, sample = tone),
            "32-bit" to wav(16_000, 1, 16_000, bits = 32, sample = tone),
            "float" to wav(16_000, 1, 16_000, bits = 32, float = true, sample = tone),
            "double" to wav(16_000, 1, 16_000, bits = 64, float = true, sample = tone),
            "extensible 24-bit" to wav(16_000, 1, 16_000, bits = 24, extensible = true, sample = tone),
            "extensible float, 6 channels" to wav(16_000, 6, 16_000, bits = 32, float = true, extensible = true, sample = tone),
            "a LIST chunk of odd size before the data" to wav(16_000, 1, 16_000, extraChunk = true, sample = tone),
        )) {
            val audio = decode(bytes)
            assertEquals(label, 16_000, audio.size)
            val tolerance = if (label == "8-bit") 0.01 else 0.0005
            assertEquals(label, 0.5, amplitude(audio.samples, 16_000, 440.0, 0, 16_000), tolerance)
        }
    }

    @Test
    fun `a data chunk whose size was never written runs to the end of the file`() {
        for (unknown in listOf(0L, 0xFFFF_FFFFL)) {
            val audio = decode(wav(16_000, 1, 8_000, dataSize = unknown) { f, _ -> 0.25 * sin(f / 10.0) })
            assertEquals(8_000, audio.size)
        }
        val cut = wav(16_000, 1, 8_000) { f, _ -> 0.25 * sin(f / 10.0) }.let { it.copyOf(it.size - 3_000) }
        assertEquals("a file cut short keeps what came", 8_000 - 1_500, decode(cut).size)
    }

    @Test
    fun `the length is refused before a sample is decoded, and so is anything past 20 minutes`() {
        // The header announces 20 minutes and a second at 16 kHz mono: refused without the data being there.
        val announced = wav(16_000, 1, 10, dataSize = (20L * 60 + 1) * 16_000 * 2) { _, _ -> 0.0 }
        failsWith(AudioFailure.TOO_LONG) { decode(announced) }
        val exactly = wav(16_000, 1, 10, dataSize = 20L * 60 * 16_000 * 2) { _, _ -> 0.0 }
        assertEquals("20 minutes exactly is allowed (what came is kept)", 10, decode(exactly).size)
        // A stream that never said how long it is: cut off at the 20 minutes' frame.
        val sink = MonoTo16k(16_000, 1)
        val second = FloatArray(16_000)
        repeat(20 * 60) { sink.frames(second, second.size) }
        failsWith(AudioFailure.TOO_LONG) { sink.frames(FloatArray(1), 1) }
        assertEquals(20 * 60 * 1_000_000L, AudioLimits.MAX_SECONDS * 1_000_000L)
        assertEquals(200L * 1024 * 1024, AudioLimits.MAX_FILE_BYTES)
    }

    @Test
    fun `what isn't a WAV file it can read is refused in words`() {
        failsWith(AudioFailure.UNREADABLE) { decode("not a recording".toByteArray()) }
        failsWith(AudioFailure.UNREADABLE) { decode(ByteArray(0)) }
        val mp3ish = byteArrayOf(0x49, 0x44, 0x33, 3, 0, 0, 0, 0, 0, 0, 0, 0)
        failsWith(AudioFailure.UNREADABLE) { decode(mp3ish) }
        val good = wav(16_000, 1, 100) { _, _ -> 0.1 }
        failsWith(AudioFailure.UNREADABLE) { decode(good.copyOf(30)) }
        val adpcm = good.copyOf().also { it[20] = 2 }   // format tag 2: MS ADPCM
        failsWith(AudioFailure.UNREADABLE) { decode(adpcm) }
        val twelveBit = good.copyOf().also { it[34] = 12 }
        failsWith(AudioFailure.UNREADABLE) { decode(twelveBit) }
        failsWith(AudioFailure.EMPTY) { decode(wav(16_000, 1, 0) { _, _ -> 0.0 }) }
        assertEquals(false, WavReader.isWav("RIFF....AVI ".toByteArray()))
    }

    @Test
    fun `reading stops when the job is cancelled`() {
        var asked = 0
        val big = wav(16_000, 1, 100_000) { _, _ -> 0.1 }
        try {
            decode(big) { ++asked > 3 }
            fail("not cancelled")
        } catch (e: CancellationException) {
            assertEquals(4, asked)
        }
    }

    @Test
    fun `a slow stream that hands over a few bytes at a time reads the same`() {
        val bytes = wav(22_050, 2, 22_050) { f, c -> (if (c == 0) 0.4 else -0.2) * sin(2 * PI * 300 * f / 22_050) }
        val trickle = object : InputStream() {
            var at = 0

            override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xFF else -1

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (at >= bytes.size) return -1
                val n = minOf(len, 7, bytes.size - at)
                System.arraycopy(bytes, at, b, off, n)
                at += n
                return n
            }
        }
        val slow = WavReader.decode(trickle)
        val fast = decode(bytes)
        assertEquals(fast.size, slow.size)
        for (i in 0 until fast.size) assertEquals(fast.samples[i], slow.samples[i], 0f)
    }

    private fun failsWith(message: String, block: () -> Unit) {
        try {
            block()
            fail("no failure; expected \"$message\"")
        } catch (e: AudioFailure) {
            assertEquals(message, e.message)
        }
    }
}

// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import dev.stevenjin.stevenpiano.update.VerifiedDownloader
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * The transcriber (v1.7 — M23): windows of 160,000 samples every 80,000, zero-padded; the frames
 * stitched as the package's `deframe`; cancellation and the memory guard between windows. With the real
 * `transcription-v1.onnx` on disk (`-PstudioModels=<dir>`, `$STUDIO_WORK/exports` or
 * `~/studio-work/exports`), ONNX Runtime 1.28.0 for the JVM runs the fixture window and must give the
 * Mac's raw outputs within 1e-2, and the fixture's notes; without it that case is skipped.
 */
class TranscriberTest {
    /** A model that remembers each window it was given and answers with [outputs] of that window. */
    private class FakeModel(val outputs: (Int) -> WindowOutputs = { blank() }) : WindowModel {
        val windows = mutableListOf<FloatArray>()

        override fun run(window: FloatArray): WindowOutputs {
            windows += window.copyOf()
            return outputs(windows.size - 1)
        }

        override fun close() = Unit

        companion object {
            fun blank() = WindowOutputs(
                FloatArray(1001 * 88), FloatArray(1001 * 88), FloatArray(1001 * 88), FloatArray(1001 * 88),
                FloatArray(1001), FloatArray(1001), FloatArray(1001),
            )
        }
    }

    private fun audio(samples: Int) = DecodedAudio(FloatArray(samples) { (it % 1000) / 1000f + 0.001f }, samples, 16_000, 1)

    @Test
    fun `windows start every 80,000 samples over the audio zero-padded to whole windows`() {
        assertEquals(1, Transcriber.windowsFor(1))
        assertEquals(1, Transcriber.windowsFor(160_000))
        assertEquals("padded to 320,000: three windows", 3, Transcriber.windowsFor(160_001))
        assertEquals("three minutes, as the spike's bench", 35, Transcriber.windowsFor(180 * 16_000))
        assertEquals("twenty minutes", 239, Transcriber.windowsFor(20 * 60 * 16_000))

        val model = FakeModel()
        val input = audio(250_000)
        val seen = mutableListOf<Pair<Int, Int>>()
        Transcriber().transcribe(input, model) { done, of -> seen += done to of }
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), seen)
        assertEquals(3, model.windows.size)
        for ((w, window) in model.windows.withIndex()) {
            val start = w * 80_000
            for (i in 0 until 160_000) {
                val expected = if (start + i < 250_000) input.samples[start + i] else 0f
                if (window[i] != expected) fail("window $w sample $i: ${window[i]}, not $expected")
            }
        }
    }

    @Test
    fun `the frames reach the post-processor exactly as the package's deframe stitches them`() {
        for (windows in listOf(1, 3, 5, 7)) {
            val random = Random(windows)
            val per = List(windows) {
                WindowOutputs(
                    noise(random, 1001 * 88), noise(random, 1001 * 88), noise(random, 1001 * 88), noise(random, 1001 * 88),
                    noise(random, 1001), noise(random, 1001), noise(random, 1001),
                )
            }
            val samples = (windows + 1) / 2 * 160_000
            val result = Transcriber().transcribe(audio(samples), FakeModel { per[it] })
            // The package's deframe, written out: all of a single window; else [0,750) of the first,
            // [250,750) of the middle ones, [250,1000) of the last, concatenated.
            val direct = NotePostProcessor()
            for ((w, out) in per.withIndex()) {
                val (from, until) = when {
                    windows == 1 -> 0 to 1001
                    w == 0 -> 0 to 750
                    w == windows - 1 -> 250 to 1000
                    else -> 250 to 750
                }
                direct.append(out.onset, out.offset, out.frame, out.velocity, out.pedalOffset, out.pedalFrame, from, until)
            }
            val expected = direct.finish()
            assertEquals("$windows windows", expected.notes, result.notes)
            assertEquals("$windows windows", expected.pedals, result.pedals)
            assertTrue(expected.notes.isNotEmpty())
        }
    }

    @Test
    fun `a cancel or a memory that no longer holds stops it between windows`() {
        var asked = 0
        val model = FakeModel()
        try {
            Transcriber(cancelled = { ++asked > 2 }).transcribe(audio(400_000), model)
            fail("not cancelled")
        } catch (e: CancellationException) {
            assertEquals(2, model.windows.size)
        }
        var checks = 0
        try {
            Transcriber(memoryHolds = { ++checks < 4 }).transcribe(audio(400_000), FakeModel())
            fail("not stopped")
        } catch (e: StudioFailure) {
            assertEquals(StudioFailures.RAN_OUT, e.message)
            assertEquals(4, checks)
        }
    }

    @Test
    fun `the real model on the JVM gives the Mac's outputs for the fixture window, and its notes`() {
        val file = modelFile()
        assumeTrue("transcription-v1.onnx not found (-PstudioModels=<dir>): skipped", file != null)
        assertEquals("the pinned file", ModelCatalogue.transcription.sha256, VerifiedDownloader.sha256Of(file!!))
        val audio = StudioFixtures.file("transcription_window.wav").inputStream().buffered().use { WavReader.decode(it) }
        val fixture = StudioFixtures.window.getJSONObject("outputs")
        OrtWindowModel(file).use { model ->
            val out = model.run(audio.samples.copyOf(Transcriber.WINDOW))
            var worst = 0.0
            for ((name, got) in listOf(
                "reg_onset_output" to out.onset, "reg_offset_output" to out.offset, "frame_output" to out.frame,
                "velocity_output" to out.velocity, "reg_pedal_onset_output" to out.pedalOnset,
                "reg_pedal_offset_output" to out.pedalOffset, "pedal_frame_output" to out.pedalFrame,
            )) {
                val o = fixture.getJSONObject(name)
                val values = o.getJSONArray("value")
                if (o.getString("encoding") == "sparse") {
                    val index = o.getJSONArray("index")
                    for (i in 0 until index.length()) worst = max(worst, abs(got[index.getInt(i)] - values.getDouble(i)))
                } else {
                    for (i in 0 until values.length()) worst = max(worst, abs(got[i] - values.getDouble(i)))
                }
            }
            println("TranscriberTest: max abs diff vs the Mac's INT8 outputs $worst")
            assertTrue("max abs diff $worst", worst < 1e-2)

            val result = Transcriber().transcribe(audio, model)
            val expected = StudioFixtures.window.getJSONArray("expected_notes")
            assertEquals(expected.length(), result.notes.size)
            for (i in 0 until expected.length()) {
                val n = expected.getJSONArray(i)
                val got = result.notes[i]
                assertEquals("note $i pitch", n.getInt(2), got.pitch)
                assertEquals("note $i onset", n.getDouble(0), got.onset.toDouble(), 1e-2)
                assertEquals("note $i offset", n.getDouble(1), got.offset.toDouble(), 1e-2)
                assertEquals("note $i velocity", n.getInt(3).toDouble(), got.velocity.toDouble(), 1.0)
            }
            assertEquals(1, result.pedals.size)
        }
    }

    private fun noise(random: Random, size: Int) = FloatArray(size) { random.nextFloat().let { v -> if (random.nextInt(7) == 0) v else v * v * v } }

    /** `transcription-v1.onnx` where the spike's tools leave it, if it is on this machine. */
    private fun modelFile(): File? {
        val dirs = listOfNotNull(
            System.getProperty("stevenpiano.studio.models"),
            System.getenv("STUDIO_WORK")?.let { "$it/exports" },
            System.getProperty("user.home")?.let { "$it/studio-work/exports" },
        )
        return dirs.map { File(it, ModelCatalogue.transcription.file) }.firstOrNull { it.isFile }
    }
}

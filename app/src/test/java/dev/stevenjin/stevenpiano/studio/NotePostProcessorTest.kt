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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * The port of `RegressionPostProcessor` (v1.7 — M23): from the fixture window's raw outputs it gives
 * exactly the package's 84 notes and one pedal (`tools/studio/fixtures/transcription_window.json`), in
 * the package's order; the same answer whatever pieces the frames come in; and the rules at their
 * edges (consecutive onsets, the 600-frame cut, the last frame, frame 0, the pedal's ten frames).
 */
class NotePostProcessorTest {
    /** The seven outputs of one window, rebuilt from the fixture: zeros where it is sparse. */
    class Outputs(val frames: Int, val arrays: Map<String, FloatArray>) {
        operator fun get(name: String): FloatArray = arrays.getValue(name)

        fun into(pp: NotePostProcessor, from: Int = 0, until: Int = frames) = pp.append(
            this["reg_onset_output"], this["reg_offset_output"], this["frame_output"], this["velocity_output"],
            this["reg_pedal_offset_output"], this["pedal_frame_output"], from, until,
        )

        companion object {
            val NAMES = listOf(
                "reg_onset_output", "reg_offset_output", "frame_output", "velocity_output",
                "reg_pedal_onset_output", "reg_pedal_offset_output", "pedal_frame_output",
            )

            fun of(fixture: JSONObject): Outputs {
                val outputs = fixture.getJSONObject("outputs")
                val arrays = NAMES.associateWith { name ->
                    val o = outputs.getJSONObject(name)
                    val shape = o.getJSONArray("shape")
                    val size = shape.getInt(0) * shape.getInt(1)
                    val values = o.getJSONArray("value")
                    val array = FloatArray(size)
                    if (o.getString("encoding") == "sparse") {
                        val index = o.getJSONArray("index")
                        for (i in 0 until index.length()) array[index.getInt(i)] = values.getDouble(i).toFloat()
                    } else {
                        for (i in 0 until values.length()) array[i] = values.getDouble(i).toFloat()
                    }
                    array
                }
                return Outputs(fixture.getInt("frames"), arrays)
            }
        }
    }

    private val fixture = StudioFixtures.window
    private val outputs = Outputs.of(fixture)

    private fun expectedNotes(): List<TranscribedNote> {
        val list = fixture.getJSONArray("expected_notes")
        return List(list.length()) { i ->
            val n = list.getJSONArray(i)
            TranscribedNote(n.getDouble(0).toFloat(), n.getDouble(1).toFloat(), n.getInt(2), n.getInt(3))
        }
    }

    private fun expectedPedals(): List<PedalEvent> {
        val list = fixture.getJSONArray("expected_pedals")
        return List(list.length()) { i -> list.getJSONArray(i).let { PedalEvent(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) } }
    }

    private fun assertSame(expected: Transcription, actual: Transcription, message: String = "") {
        assertEquals("$message notes", expected.notes, actual.notes)
        assertEquals("$message pedals", expected.pedals, actual.pedals)
    }

    @Test
    fun `the fixture window gives the package's 84 notes and its pedal, in its order`() {
        assertEquals(1001, outputs.frames)
        assertEquals(0.3, fixture.getJSONObject("thresholds").getDouble("onset"), 0.0)
        val pp = NotePostProcessor()
        outputs.into(pp)
        val result = pp.finish()
        val expected = expectedNotes()
        assertEquals(84, expected.size)
        assertEquals(84, result.notes.size)
        for ((i, pair) in expected.zip(result.notes).withIndex()) {
            val (want, got) = pair
            // The fixture rounds the package's float32 times to six decimals.
            assertEquals("note $i onset", want.onset, got.onset, 1e-6f)
            assertEquals("note $i offset", want.offset, got.offset, 1e-6f)
            assertEquals("note $i pitch", want.pitch, got.pitch)
            assertEquals("note $i velocity", want.velocity, got.velocity)
        }
        assertEquals(listOf(PedalEvent(0.05f, 0.09f)), expectedPedals())
        assertEquals(1, result.pedals.size)
        assertEquals(0.05f, result.pedals.single().onset, 1e-6f)
        assertEquals(0.09f, result.pedals.single().offset, 1e-6f)
    }

    @Test
    fun `the answer is the same whatever pieces the frames come in`() {
        val whole = NotePostProcessor().also { outputs.into(it) }.finish()
        for (piece in listOf(1, 3, 250, 500, 1000)) {
            val pp = NotePostProcessor()
            var at = 0
            while (at < outputs.frames) {
                val until = minOf(outputs.frames, at + piece)
                outputs.into(pp, at, until)
                at = until
            }
            assertSame(whole, pp.finish(), "pieces of $piece")
        }
        // Random outputs, many notes and pedals, in random pieces.
        val random = Random(46)
        val frames = 3_000
        val noisy = Outputs(frames, Outputs.NAMES.associateWith { name ->
            val width = if (name.contains("pedal")) 1 else 88
            FloatArray(frames * width) { (random.nextFloat() * random.nextFloat()).let { v -> if (random.nextInt(9) == 0) v + 0.5f else v } }
        })
        val once = NotePostProcessor().also { noisy.into(it) }.finish()
        val pp = NotePostProcessor()
        var at = 0
        while (at < frames) {
            val until = minOf(frames, at + 1 + random.nextInt(700))
            noisy.into(pp, at, until)
            at = until
        }
        assertSame(once, pp.finish(), "random pieces")
        assertTrue("${once.notes.size} notes, ${once.pedals.size} pedals", once.notes.size > 100 && once.pedals.isNotEmpty())
    }

    /** One key's outputs, frame by frame, and a pedal that stays up unless given. */
    private fun single(
        frames: Int,
        key: Int = 39,
        onsets: Map<Int, Float> = emptyMap(),
        offsets: Map<Int, Float> = emptyMap(),
        frame: (Int) -> Float = { 0f },
        velocity: Float = 0.5f,
        pedalFrame: (Int) -> Float = { 0f },
        pedalOffsets: Map<Int, Float> = emptyMap(),
    ): Transcription {
        val onset = FloatArray(frames * 88)
        val offset = FloatArray(frames * 88)
        val frameOut = FloatArray(frames * 88)
        val vel = FloatArray(frames * 88)
        for ((f, v) in onsets) onset[f * 88 + key] = v
        for ((f, v) in offsets) offset[f * 88 + key] = v
        for (f in 0 until frames) {
            frameOut[f * 88 + key] = frame(f)
            vel[f * 88 + key] = velocity
        }
        val pedalOffset = FloatArray(frames).also { a -> pedalOffsets.forEach { (f, v) -> a[f] = v } }
        val pedal = FloatArray(frames) { pedalFrame(it) }
        return NotePostProcessor().apply { append(onset, offset, frameOut, vel, pedalOffset, pedal, 0, frames) }.finish()
    }

    @Test
    fun `a note ends where its frame falls, or at an offset peak past halfway, or at the next onset`() {
        // Onset peak at 10 (symmetric, no shift); the frame stays up until 40.
        val falls = single(100, onsets = mapOf(10 to 0.9f), frame = { if (it in 11..39) 0.8f else 0f })
        assertEquals(listOf(TranscribedNote(0.1f, 0.4f, 60, 64)), falls.notes)
        // An offset peak at 35 is past halfway from 10 to 40: the note ends there.
        val peak = single(100, onsets = mapOf(10 to 0.9f), offsets = mapOf(35 to 0.9f), frame = { if (it in 11..39) 0.8f else 0f })
        assertEquals(0.35f, peak.notes.single().offset, 0f)
        // One at 20 is not: the fall wins.
        val early = single(100, onsets = mapOf(10 to 0.9f), offsets = mapOf(20 to 0.9f), frame = { if (it in 11..39) 0.8f else 0f })
        assertEquals(0.4f, early.notes.single().offset, 0f)
        // A second onset while the first still sounds ends it the frame before, with no shift.
        val again = single(100, onsets = mapOf(10 to 0.9f, 30 to 0.9f), frame = { if (it in 11..59) 0.8f else 0f })
        assertEquals(listOf(0.1f to 0.29f, 0.3f to 0.6f), again.notes.map { it.onset to it.offset })
    }

    @Test
    fun `a note is cut after 600 frames, or at the last frame`() {
        val long = single(1_000, onsets = mapOf(10 to 0.9f), frame = { if (it > 10) 0.8f else 0f })
        assertEquals(6.1f, long.notes.single().offset, 0f)
        val atEnd = single(200, onsets = mapOf(10 to 0.9f), frame = { if (it > 10) 0.8f else 0f })
        assertEquals(1.99f, atEnd.notes.single().offset, 0f)
    }

    @Test
    fun `peaks need falling neighbours, never sit at the ends, and shift toward the larger neighbour`() {
        // 0.5 then 0.9 then 0.7: the peak at 11 leans back, by (0.7 - 0.5) / (0.9 - 0.5) / 2 = 0.25 frame.
        val leaning = single(100, onsets = mapOf(10 to 0.5f, 11 to 0.9f, 12 to 0.7f), frame = { if (it in 12..30) 0.8f else 0f })
        assertEquals(((11.0 + ((0.7f - 0.5f) / (0.9f - 0.5f) / 2f)) / 100).toFloat(), leaning.notes.single().onset, 0f)
        // A value that rises again two frames on is no peak, and neither is the higher one after a dip.
        val rising = single(100, onsets = mapOf(10 to 0.9f, 11 to 0.5f, 12 to 0.95f), frame = { if (it in 11..30) 0.8f else 0f })
        assertEquals(emptyList<TranscribedNote>(), rising.notes)
        // Frames 0 and 1 can't be onset peaks (two neighbours needed), nor the last two.
        assertEquals(emptyList<TranscribedNote>(), single(50, onsets = mapOf(1 to 0.9f, 48 to 0.9f), frame = { 0.8f }).notes)
        // Velocity is int(v × 128): 0.999 → 127, 1.0 → 128 (the writer clamps it).
        assertEquals(128, single(50, onsets = mapOf(10 to 0.9f), velocity = 1f, frame = { if (it in 11..20) 0.8f else 0f }).notes.single().velocity)
    }

    @Test
    fun `the pedal goes down on a rising frame, up at the next offset peak or ten frames after it falls`() {
        val byPeak = single(200, pedalFrame = { if (it in 20..59) 0.9f else 0.1f }, pedalOffsets = mapOf(50 to 0.9f))
        assertEquals(listOf(PedalEvent(0.2f, 0.5f)), byPeak.pedals)
        val byFall = single(200, pedalFrame = { if (it in 20..59) 0.9f else 0.1f })
        assertEquals(listOf(PedalEvent(0.2f, 0.6f)), byFall.pedals)
        assertEquals("still down at the end: not reported (the package's rule)", emptyList<PedalEvent>(), single(100, pedalFrame = { if (it >= 20) 0.9f else 0.1f }).pedals)
        assertEquals("down from the very first frame: never rising, never reported", emptyList<PedalEvent>(), single(100, pedalFrame = { 0.9f }).pedals)
    }

    @Test
    fun `a level onset above the threshold is a peak at every frame, and a recording's notes stop at 200,000 (audit delta 2)`() {
        // The package's rule: a peak's neighbours need only not rise, so a level onset output of 0.9 (a
        // saturated model) is a peak at every frame, and a note at every frame of every key: 8,800 a second.
        val frames = 1_001
        val level = FloatArray(frames * CLASSES) { 0.9f }
        val sounding = FloatArray(frames * CLASSES) { 0.8f }
        val quiet = FloatArray(frames)
        fun feed(pp: NotePostProcessor) = pp.append(level, FloatArray(frames * CLASSES), sounding, level, quiet, quiet, 0, frames)

        val every = NotePostProcessor().apply { feed(this) }.finish()
        assertEquals("every frame but the two at each end, on every key", (frames - 4) * CLASSES, every.notes.size)

        // So a recording's notes are counted, and past the cap it is refused in words rather than held.
        try {
            NotePostProcessor(maxNotes = 10_000).apply { feed(this) }.finish()
            fail("not refused")
        } catch (e: StudioFailure) {
            assertEquals(StudioFailures.TOO_MANY_NOTES, e.message)
        }
        assertEquals(200_000, NotePostProcessor.MAX_NOTES)
        // At the default cap three windows of that are refused (the densest audio measured made 78 notes a
        // second, 94,000 in twenty minutes, well under it).
        val pp = NotePostProcessor()
        try {
            repeat(3) { feed(pp) }
            pp.finish()
            fail("not refused")
        } catch (e: StudioFailure) {
            assertEquals(StudioFailures.TOO_MANY_NOTES, e.message)
        }
    }

    private companion object {
        const val CLASSES = NotePostProcessor.CLASSES
    }
}

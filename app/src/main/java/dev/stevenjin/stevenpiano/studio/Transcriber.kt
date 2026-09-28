// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.FloatBuffer

/**
 * One 10 s window through the model: its seven outputs, the note outputs `[1001][88]` and the pedal
 * ones `[1001]`, as flat arrays (docs/STUDIO_SPIKE.md › Transcription). A model may hand the same
 * arrays back for its next window: read them before asking for another.
 */
class WindowOutputs(
    val onset: FloatArray,
    val offset: FloatArray,
    val frame: FloatArray,
    val velocity: FloatArray,
    val pedalOnset: FloatArray,
    val pedalOffset: FloatArray,
    val pedalFrame: FloatArray,
)

/** The transcription model: 160,000 samples of 16 kHz mono in, a window's outputs back. */
interface WindowModel : AutoCloseable {
    fun run(window: FloatArray): WindowOutputs
}

/**
 * `transcription-v1.onnx` through ONNX Runtime 1.28.0, with the spike's session options: the CPU
 * execution provider only (no NNAPI), 4 intra-op threads and 1 inter-op, sequential execution, every
 * graph optimisation, and **memory-pattern optimisation off** (ORT's planner would reserve one block
 * for the whole graph and put the process at about 1 GB; off, it peaks near 0.7 GB, 0–4 % slower).
 * The session is made on the calling thread, so ORT's own threads take that thread's priority.
 */
class OrtWindowModel(file: File, threads: Int = THREADS) : WindowModel {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setInterOpNumThreads(1)
        setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setMemoryPatternOptimization(false)
    }
    private val session: OrtSession = try {
        env.createSession(file.path, options)
    } catch (e: Exception) {
        options.close()
        throw e
    }
    private val outputs = WindowOutputs(
        FloatArray(FRAMES * CLASSES), FloatArray(FRAMES * CLASSES), FloatArray(FRAMES * CLASSES), FloatArray(FRAMES * CLASSES),
        FloatArray(FRAMES), FloatArray(FRAMES), FloatArray(FRAMES),
    )

    override fun run(window: FloatArray): WindowOutputs {
        require(window.size == Transcriber.WINDOW) { "a window is ${Transcriber.WINDOW} samples" }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(window), longArrayOf(1, Transcriber.WINDOW.toLong())).use { input ->
            session.run(mapOf(INPUT to input)).use { result ->
                fun read(name: String, into: FloatArray) {
                    val buffer = (result.get(name).get() as OnnxTensor).floatBuffer
                    check(buffer.remaining() == into.size) { "$name has ${buffer.remaining()} values, not ${into.size}" }
                    buffer.get(into)
                }
                read("reg_onset_output", outputs.onset)
                read("reg_offset_output", outputs.offset)
                read("frame_output", outputs.frame)
                read("velocity_output", outputs.velocity)
                read("reg_pedal_onset_output", outputs.pedalOnset)
                read("reg_pedal_offset_output", outputs.pedalOffset)
                read("pedal_frame_output", outputs.pedalFrame)
            }
        }
        return outputs
    }

    override fun close() {
        session.close()
        options.close()
    }

    companion object {
        const val INPUT = "audio"
        const val FRAMES = 1001
        const val CLASSES = NotePostProcessor.CLASSES
        const val THREADS = 4
    }
}

/**
 * A recording into notes (v1.7 — M23), the package's `transcribe` as the app runs it: the 16 kHz audio
 * zero-padded to a whole number of 160,000-sample windows, a window starting every 80,000 samples
 * (N = 2 × padded / 160,000 − 1), each through the model in turn; the frames stitched as the package's
 * `deframe` does (the extra frame 1,000 of each window dropped; frames 0–749 of the first window, 250–749
 * of the middle ones, 250–999 of the last; all 1,001 when there is one) and handed to
 * [NotePostProcessor] as they come, so only one window's outputs are ever held. Before every window it
 * asks whether the job was [cancelled] (a CancellationException) and whether the memory still holds
 * ([memoryHolds]: the peak-memory guard, [StudioFailures.RAN_OUT] when it doesn't); [progress] hears
 * each window done. Blocking: run it on the job's own thread.
 */
class Transcriber(
    private val memoryHolds: () -> Boolean = { true },
    private val cancelled: () -> Boolean = { false },
) {
    fun transcribe(audio: DecodedAudio, model: WindowModel, progress: (done: Int, windows: Int) -> Unit = { _, _ -> }): Transcription {
        val windows = windowsFor(audio.size)
        val post = NotePostProcessor()
        val window = FloatArray(WINDOW)
        for (w in 0 until windows) {
            if (cancelled()) throw CancellationException("The transcription was cancelled")
            if (!memoryHolds()) throw StudioFailure(StudioFailures.RAN_OUT)
            val start = w * HOP
            val available = (audio.size - start).coerceIn(0, WINDOW)
            System.arraycopy(audio.samples, start, window, 0, available)
            window.fill(0f, available, WINDOW)
            val out = model.run(window)
            val (from, until) = rowsOf(w, windows)
            post.append(out.onset, out.offset, out.frame, out.velocity, out.pedalOffset, out.pedalFrame, from, until)
            progress(w + 1, windows)
        }
        return post.finish()
    }

    companion object {
        /** One window: 10 s at 16 kHz. */
        const val WINDOW = 160_000

        /** A window starts every 5 s. */
        const val HOP = 80_000

        /** The windows [samples] of audio take: N = 2 × padded / 160,000 − 1, one at least. */
        fun windowsFor(samples: Int): Int {
            val padded = ((samples.coerceAtLeast(1) + WINDOW - 1) / WINDOW).toLong() * WINDOW
            return (2 * padded / WINDOW - 1).toInt()
        }

        /** The rows of window [w] (of [windows]) the stitched frames keep: the package's `deframe`. */
        fun rowsOf(w: Int, windows: Int): Pair<Int, Int> = when {
            windows == 1 -> 0 to OrtWindowModel.FRAMES
            w == 0 -> 0 to 750
            w == windows - 1 -> 250 to 1000
            else -> 250 to 750
        }
    }
}

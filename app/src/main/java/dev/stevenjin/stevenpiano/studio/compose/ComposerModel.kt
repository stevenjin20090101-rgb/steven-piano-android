// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio.compose

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * The composing model as the [Sampler] drives it (v1.7 — M24): a context of at most [Amt.CONTEXT]
 * tokens, read at once ([prefill]) and then a token at a time ([step]) through its key-value cache.
 * Each call gives the next token's logits, [Amt.VOCAB_SIZE] of them, in an array the model may reuse on
 * its next call: read them before asking again. Not thread-safe: one job drives it.
 */
interface ComposerModel : AutoCloseable {
    /** Forgets any context, then reads [tokens]: the logits for the token after the last of them. */
    fun prefill(tokens: IntArray): FloatArray

    /** Reads [token] after the context: the logits for the token after it. */
    fun step(token: Int): FloatArray

    /** Forgets the context and lets go of its cache. */
    fun reset()
}

/**
 * `composer-v1.onnx` through ONNX Runtime 1.28.0 (docs/STUDIO_SPIKE.md › The contract for M23 and M24),
 * with the session options transcription uses: the CPU execution provider only, 4 intra-op threads and
 * 1 inter-op, sequential, every graph optimisation, memory-pattern optimisation off. Each call feeds
 * `input_ids` `[1, n]`, `attention_mask` `[1, p + n]` (all ones) and `position_ids` `[1, n]` (p … p + n − 1)
 * with the 24 `past_key_values.{0..11}.key/.value` `[1, 12, p, 64]` (empty at first); the result's
 * `present.*` go straight back in as the next call's past (the result that owns them is closed once the
 * next run has read them), and `logits` `[1, 1, 55 028]` land in one buffer of the model's own, pinned
 * for every run, so a step allocates no 220 kB of logits. The session is made on the calling thread, so
 * ORT's threads take that thread's priority.
 */
class OrtComposerModel(file: File, threads: Int = THREADS) : ComposerModel {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setInterOpNumThreads(1)
        setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setMemoryPatternOptimization(false)
    }
    private val session: OrtSession
    private val empty: OnnxTensor
    private val logitsBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * Amt.VOCAB_SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val logitsTensor: OnnxTensor
    private val logits = FloatArray(Amt.VOCAB_SIZE)

    /** The cache from the last run (null: none yet), owned by [held]. */
    private var past: List<OnnxTensor>? = null
    private var held: OrtSession.Result? = null

    /** Tokens in the context. */
    var positions = 0
        private set

    init {
        val made = ArrayList<AutoCloseable>(3)
        try {
            val opened = env.createSession(file.path, options).also { made += it }
            val inputs = opened.inputNames
            val outputs = opened.outputNames
            val missing = (listOf(INPUT_IDS, ATTENTION_MASK, POSITION_IDS) + PAST).filterNot { it in inputs } +
                (listOf(LOGITS) + PRESENT).filterNot { it in outputs }
            require(missing.isEmpty()) { "${file.name} is not the composer: no ${missing.joinToString()}" }
            val none = OnnxTensor.createTensor(
                env,
                ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer(),
                longArrayOf(1, HEADS.toLong(), 0, HEAD_SIZE.toLong()),
            ).also { made += it }
            val pinned = OnnxTensor.createTensor(env, logitsBuffer, longArrayOf(1, 1, Amt.VOCAB_SIZE.toLong())).also { made += it }
            session = opened
            empty = none
            logitsTensor = pinned
        } catch (e: Throwable) {
            for (it in made.asReversed()) runCatching { it.close() }
            options.close()
            throw e
        }
    }

    override fun prefill(tokens: IntArray): FloatArray {
        reset()
        return feed(tokens)
    }

    override fun step(token: Int): FloatArray = feed(intArrayOf(token))

    override fun reset() {
        held?.close()
        held = null
        past = null
        positions = 0
    }

    private fun feed(ids: IntArray): FloatArray {
        val n = ids.size
        require(n > 0) { "nothing to read" }
        require(positions + n <= Amt.CONTEXT) { "the context holds ${Amt.CONTEXT} tokens: $positions + $n" }
        val p = positions.toLong()
        OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { ids[it].toLong() }), longArrayOf(1, n.toLong())).use { idsTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(positions + n) { 1L }), longArrayOf(1, p + n)).use { maskTensor ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { p + it }), longArrayOf(1, n.toLong())).use { positionTensor ->
                    val inputs = HashMap<String, OnnxTensor>(32)
                    inputs[INPUT_IDS] = idsTensor
                    inputs[ATTENTION_MASK] = maskTensor
                    inputs[POSITION_IDS] = positionTensor
                    val cache = past
                    for ((i, name) in PAST.withIndex()) inputs[name] = cache?.get(i) ?: empty
                    val result = session.run(inputs, PRESENT_SET, mapOf(LOGITS to logitsTensor))
                    held?.close()   // it owned the cache just read
                    held = result
                    past = PRESENT.map { result.get(it).get() as OnnxTensor }
                    positions += n
                }
            }
        }
        logitsBuffer.rewind()
        logitsBuffer.get(logits)
        return logits
    }

    override fun close() {
        reset()
        logitsTensor.close()
        empty.close()
        session.close()
        options.close()
    }

    companion object {
        const val THREADS = 4
        const val LAYERS = 12
        const val HEADS = 12
        const val HEAD_SIZE = 64
        const val INPUT_IDS = "input_ids"
        const val ATTENTION_MASK = "attention_mask"
        const val POSITION_IDS = "position_ids"
        const val LOGITS = "logits"

        /** `past_key_values.0.key`, `past_key_values.0.value`, … `past_key_values.11.value`. */
        val PAST: List<String> = (0 until LAYERS).flatMap { listOf("past_key_values.$it.key", "past_key_values.$it.value") }

        /** The outputs that come back as [PAST], in the same order: `present.0.key` … */
        val PRESENT: List<String> = PAST.map { it.replace("past_key_values", "present") }
        private val PRESENT_SET: Set<String> = PRESENT.toSet()
    }
}

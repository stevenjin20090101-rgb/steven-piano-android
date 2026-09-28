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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random
import kotlin.math.exp
import kotlin.math.max

/**
 * M22 Studio spike, temporary and debug-only: times the two ONNX models with onnxruntime-android
 * on this device (CPU execution provider, NNAPI off, 4 intra-op threads) and checks them against
 * the Mac's fixtures (tools/studio/fixtures). Everything is read from filesDir: models/<file>,
 * studio/<wav or json>. Results go to logcat (tag StudioBench) and filesDir/studio/bench-<label>.json.
 */
class StudioBench(
    private val filesDir: File,
    private val memPattern: Boolean = true,
    private val arena: Boolean = true,
    private val log: (String) -> Unit,
) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment().also {
        // onnxruntime-android 1.29+ carries Microsoft's 1DS telemetry; off (1.27/1.28 have none)
        runCatching { it.setTelemetry(false) }
    }

    private fun session(model: String, threads: Int): Pair<OrtSession, Long> {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setMemoryPatternOptimization(memPattern)
            setCPUArenaAllocator(arena)
        }
        val t0 = System.nanoTime()
        val s = env.createSession(File(filesDir, "models/$model").path, opts)
        return s to (System.nanoTime() - t0) / 1_000_000
    }

    // ---------------------------------------------------------------- transcription

    fun transcription(model: String, wav: String, threads: Int, fixture: String?): JSONObject {
        val out = JSONObject().put("bench", "transcription").put("model", model).put("wav", wav)
            .put("threads", threads).put("mem_pattern", memPattern).put("arena", arena)
            .put("rss_before_kb", Proc.status("VmRSS"))
        val t0 = System.nanoTime()
        val (session, loadMs) = session(model, threads)
        out.put("session_create_ms", loadMs).put("rss_after_load_kb", Proc.status("VmRSS"))
        session.use {
            if (fixture != null) out.put("fixture_check", transcriptionFixture(session, fixture))
            val audio = Wav.read16kMono(File(filesDir, "studio/$wav"))
            val n = audio.size
            val padded = ((n + SEGMENT - 1) / SEGMENT) * SEGMENT
            val x = audio.copyOf(padded)
            val starts = generateSequence(0) { it + HOP }.takeWhile { it + SEGMENT <= padded }.toList()
            val windows = starts.size
            val frames = if (windows == 1) FRAMES else 500 * windows + 500
            val stitched = OUTPUTS.associateWith { FloatArray(frames * (if (it.startsWith("reg_pedal") || it == "pedal_frame_output") 1 else 88)) }
            val perWindow = ArrayList<Long>()
            val tInfer = System.nanoTime()
            for ((w, start) in starts.withIndex()) {
                val ts = System.nanoTime()
                val buf = FloatBuffer.wrap(x, start, SEGMENT).slice()
                OnnxTensor.createTensor(env, buf, longArrayOf(1, SEGMENT.toLong())).use { input ->
                    session.run(mapOf("audio" to input)).use { res ->
                        for (name in OUTPUTS) {
                            val t = res.get(name).get() as OnnxTensor
                            val c = t.info.shape[2].toInt()
                            val fb = t.floatBuffer
                            val arr = FloatArray(fb.remaining()).also { fb.get(it) }
                            // the package's deframe: drop the last frame; keep [0,750) of the first
                            // window, [250,750) of the middle ones, [250,1000) of the last
                            val (from, to) = when {
                                windows == 1 -> 0 to FRAMES
                                w == 0 -> 0 to 750
                                w == windows - 1 -> 250 to 1000
                                else -> 250 to 750
                            }
                            val dst = if (windows == 1 || w == 0) 0 else 750 + (w - 1) * 500
                            System.arraycopy(arr, from * c, stitched.getValue(name), dst * c, (to - from) * c)
                        }
                    }
                }
                perWindow.add((System.nanoTime() - ts) / 1_000_000)
                if (w % 5 == 0) log("transcription window ${w + 1}/$windows ${perWindow.last()} ms rss ${Proc.status("VmRSS")} kB")
            }
            val inferMs = (System.nanoTime() - tInfer) / 1_000_000
            val sorted = perWindow.sorted()
            out.put("audio_seconds", n / 16000.0).put("windows", windows).put("frames", frames)
                .put("inference_ms", inferMs).put("window_ms_median", sorted[sorted.size / 2])
                .put("window_ms_min", sorted.first()).put("window_ms_max", sorted.last())
                .put("total_ms_including_load", (System.nanoTime() - t0) / 1_000_000)
                .put("realtime_factor", inferMs / 1000.0 / (n / 16000.0))
                .put("frame_output_checksum", stitched.getValue("frame_output").sum().toDouble())
        }
        out.put("vm_hwm_kb", Proc.status("VmHWM")).put("rss_end_kb", Proc.status("VmRSS"))
        return out
    }

    /** The fixture window through this device's runtime vs the Mac's INT8 outputs. */
    private fun transcriptionFixture(session: OrtSession, name: String): JSONObject {
        val fx = JSONObject(File(filesDir, "studio/$name").readText())
        val audio = Wav.read16kMono(File(filesDir, "studio/" + fx.getJSONObject("input").getString("file")))
        val res = JSONObject()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(audio), longArrayOf(1, SEGMENT.toLong())).use { input ->
            session.run(mapOf("audio" to input)).use { r ->
                var worst = 0.0
                for (outName in OUTPUTS) {
                    val fb = (r.get(outName).get() as OnnxTensor).floatBuffer
                    val got = FloatArray(fb.remaining()).also { fb.get(it) }
                    val o = fx.getJSONObject("outputs").getJSONObject(outName)
                    val values = o.getJSONArray("value")
                    var maxDiff = 0.0
                    if (o.getString("encoding") == "sparse") {
                        val idx = o.getJSONArray("index")
                        for (i in 0 until idx.length()) maxDiff = max(maxDiff, kotlin.math.abs(got[idx.getInt(i)] - values.getDouble(i)))
                    } else {
                        for (i in 0 until values.length()) maxDiff = max(maxDiff, kotlin.math.abs(got[i] - values.getDouble(i)))
                    }
                    res.put(outName, maxDiff)
                    worst = max(worst, maxDiff)
                }
                res.put("max_abs_diff_vs_mac", worst)
            }
        }
        return res
    }

    // ---------------------------------------------------------------- composer

    fun composer(model: String, tokens: Int, threads: Int, keepEvents: Int, fixture: String?, seed: Long): JSONObject {
        val out = JSONObject().put("bench", "composer").put("model", model).put("threads", threads)
            .put("tokens", tokens).put("keep_events_on_slide", keepEvents)
            .put("mem_pattern", memPattern).put("arena", arena)
            .put("rss_before_kb", Proc.status("VmRSS"))
        val t0 = System.nanoTime()
        val (session, loadMs) = session(model, threads)
        out.put("session_create_ms", loadMs).put("rss_after_load_kb", Proc.status("VmRSS"))
        session.use {
            val lm = KvLm(env, session)
            val fx = fixture?.let { JSONObject(File(filesDir, "studio/$it").readText()) }
            val seedTokens = fx?.getJSONArray("input_tokens")?.let { a -> IntArray(a.length()) { a.getInt(it) } }
                ?: intArrayOf(AUTOREGRESS)
            if (fx != null) {
                val t = System.nanoTime()
                val greedy = Amt.greedy(lm, seedTokens, 64)
                val ref = fx.getJSONArray("expected_continuation").let { a -> IntArray(a.length()) { a.getInt(it) } }
                val mac = fx.getJSONArray("onnx_int8_continuation").let { a -> IntArray(a.length()) { a.getInt(it) } }
                out.put("fixture_check", JSONObject()
                    .put("greedy_ms", (System.nanoTime() - t) / 1_000_000)
                    .put("tokens", JSONArray(greedy.toList()))
                    .put("same_as_mac_int8", greedy.contentEquals(mac))
                    .put("first_difference_vs_mac_int8", firstDiff(greedy, mac))
                    .put("first_difference_vs_torch", firstDiff(greedy, ref)))
                log("composer fixture: same as the Mac's INT8 ${greedy.contentEquals(mac)}, first diff vs torch ${firstDiff(greedy, ref)}")
            }
            val gen = Amt.generate(lm, seedTokens, tokens, keepEvents, Random(seed), log)
            out.put("generation", gen)
        }
        out.put("total_ms_including_load", (System.nanoTime() - t0) / 1_000_000)
        out.put("vm_hwm_kb", Proc.status("VmHWM")).put("rss_end_kb", Proc.status("VmRSS"))
        return out
    }

    private fun firstDiff(a: IntArray, b: IntArray): Int = a.indices.firstOrNull { it >= b.size || a[it] != b[it] } ?: -1

    companion object {
        const val SEGMENT = 160_000
        const val HOP = 80_000
        const val FRAMES = 1001
        val OUTPUTS = listOf(
            "reg_onset_output", "reg_offset_output", "frame_output", "velocity_output",
            "reg_pedal_onset_output", "reg_pedal_offset_output", "pedal_frame_output",
        )
        const val AUTOREGRESS = 55026
    }
}

/** music-small-800k through the KV cache: prefill, then one token per call. */
class KvLm(private val env: OrtEnvironment, private val session: OrtSession) {
    private val pastNames = (0 until 12).flatMap { listOf("past_key_values.$it.key", "past_key_values.$it.value") }
    private var past: Map<String, OnnxTensor> = emptyPast()
    private var held: OrtSession.Result? = null
    private var ownsPast = true
    var positions = 0
        private set

    private fun emptyPast(): Map<String, OnnxTensor> = pastNames.associateWith {
        OnnxTensor.createTensor(env, ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer(), longArrayOf(1, 12, 0, 64))
    }

    fun reset() {
        held?.close()
        held = null
        if (ownsPast) past.values.forEach { it.close() }
        past = emptyPast()
        ownsPast = true
        positions = 0
    }

    /** Feeds [ids] after the cache; returns the next token's logits. */
    fun feed(ids: IntArray): FloatArray {
        val n = ids.size
        val inputs = HashMap<String, OnnxTensor>(32)
        val idsT = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { ids[it].toLong() }), longArrayOf(1, n.toLong()))
        val maskT = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(positions + n) { 1L }), longArrayOf(1, (positions + n).toLong()))
        val posT = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { (positions + it).toLong() }), longArrayOf(1, n.toLong()))
        inputs["input_ids"] = idsT
        inputs["attention_mask"] = maskT
        inputs["position_ids"] = posT
        inputs.putAll(past)
        val res = session.run(inputs)
        idsT.close(); maskT.close(); posT.close()
        if (ownsPast) past.values.forEach { it.close() }
        held?.close()                 // the previous result owned the tensors just used as past
        held = res
        ownsPast = false
        past = pastNames.associateWith { res.get(it.replace("past_key_values", "present")).get() as OnnxTensor }
        positions += n
        val fb = (res.get("logits").get() as OnnxTensor).floatBuffer
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }
}

/** The anticipation package's arrival-time vocabulary and sampling rules (sample.py). */
object Amt {
    const val TIME_OFFSET = 0
    const val DUR_OFFSET = 10_000
    const val NOTE_OFFSET = 11_000
    const val REST = 27_512
    const val CONTROL_OFFSET = 27_513
    const val MAX_TIME = 10_000
    const val MAX_DUR = 1_000
    const val MAX_NOTE = 16_512
    const val CONTEXT = 1024

    /** safe_logits + future_logits (package rules: every instrument, REST allowed), argmax. */
    private fun maskedArgmax(l: FloatArray, slot: Int, curRel: Int): Int {
        var best = -1
        var bestV = Float.NEGATIVE_INFINITY
        fun consider(from: Int, to: Int) {
            for (i in from until to) if (l[i] > bestV) { bestV = l[i]; best = i }
        }
        when (slot) {
            0 -> consider(TIME_OFFSET + max(curRel, 0), TIME_OFFSET + MAX_TIME)
            1 -> consider(DUR_OFFSET, DUR_OFFSET + MAX_DUR)
            else -> consider(NOTE_OFFSET, NOTE_OFFSET + MAX_NOTE)
        }
        consider(REST, REST + 1)
        return best
    }

    /** The fixture's check: greedy decoding from the seed, through the cache. */
    fun greedy(lm: KvLm, seed: IntArray, n: Int): IntArray {
        lm.reset()
        var logits = lm.feed(seed)
        var cur = maxTime(seed.drop(1))
        val out = IntArray(n)
        for (i in 0 until n) {
            val slot = i % 3
            val tok = maskedArgmax(logits, slot, if (slot == 0) cur else 0)
            out[i] = tok
            if (slot == 0 && tok < MAX_TIME) cur = tok - TIME_OFFSET
            if (i < n - 1) logits = lm.feed(intArrayOf(tok))
        }
        return out
    }

    private fun maxTime(tokens: List<Int>): Int {
        var m = 0
        var i = 0
        while (i + 2 < tokens.size) { m = max(m, tokens[i] - TIME_OFFSET); i += 3 }
        return m
    }

    /**
     * The timing run: [n] tokens sampled (temperature 1, top-p 0.98, piano only: pitches of
     * instrument 0 or REST in the note slot), through the cache. When the next event would not
     * fit in the 1 024 positions, the window slides: the last [keepEvents] events are kept, their
     * times made relative to the earliest of them (the package's relativisation), and prefilled
     * again after the AUTOREGRESS flag.
     */
    fun generate(lm: KvLm, seed: IntArray, n: Int, keepEvents: Int, rng: Random, log: (String) -> Unit): JSONObject {
        val history = ArrayList<Int>(seed.size + n)       // absolute times
        for (i in 1 until seed.size) history.add(seed[i])
        var offset = 0
        val stepNs = ArrayList<Long>(n)
        val prefillMs = ArrayList<Long>()
        val t0 = System.nanoTime()
        var t = System.nanoTime()
        lm.reset()
        var logits = lm.feed(seed)
        val seedPrefillMs = (System.nanoTime() - t) / 1_000_000
        var cur = maxTime(history)
        var made = 0
        while (made < n) {
            if (lm.positions + 3 > CONTEXT) {
                val keep = history.subList(history.size - 3 * keepEvents, history.size).toMutableList()
                offset = (keep.indices step 3).minOf { keep[it] }
                for (i in keep.indices step 3) keep[i] -= offset
                t = System.nanoTime()
                lm.reset()
                logits = lm.feed(intArrayOf(StudioBench.AUTOREGRESS) + keep.toIntArray())
                prefillMs.add((System.nanoTime() - t) / 1_000_000)
            }
            for (slot in 0 until 3) {
                val tok = sample(logits, slot, cur - offset, rng)
                val abs = if (slot == 0) tok + offset else tok
                history.add(abs)
                if (slot == 0) cur = abs
                made++
                if (made == n) break
                t = System.nanoTime()
                logits = lm.feed(intArrayOf(tok))
                stepNs.add(System.nanoTime() - t)
            }
            if (made % 600 == 0) log("composer $made/$n tokens, positions ${lm.positions}, rss ${Proc.status("VmRSS")} kB")
        }
        val totalMs = (System.nanoTime() - t0) / 1_000_000
        val steps = stepNs.sorted()
        return JSONObject()
            .put("seed_tokens", seed.size).put("seed_prefill_ms", seedPrefillMs)
            .put("generated_tokens", made).put("total_ms", totalMs)
            .put("ms_per_token", totalMs.toDouble() / made)
            .put("step_ms_median", steps[steps.size / 2] / 1e6).put("step_ms_p90", steps[steps.size * 9 / 10] / 1e6)
            .put("step_ms_max", steps.last() / 1e6)
            .put("slides", prefillMs.size).put("slide_prefill_ms", JSONArray(prefillMs))
            .put("music_seconds_generated", (cur - maxTime(seed.drop(1))) / 100.0)
    }

    private fun sample(l: FloatArray, slot: Int, curRel: Int, rng: Random): Int {
        val (from, to) = when (slot) {
            0 -> (TIME_OFFSET + max(curRel, 0)) to (TIME_OFFSET + MAX_TIME)
            1 -> DUR_OFFSET to (DUR_OFFSET + MAX_DUR)
            else -> NOTE_OFFSET to (NOTE_OFFSET + 128)
        }
        val extra = if (slot == 2) 1 else 0
        val count = to - from + extra
        val idx = IntArray(count) { if (it < to - from) from + it else REST }
        var m = Float.NEGATIVE_INFINITY
        for (i in idx) m = max(m, l[i])
        val p = DoubleArray(count) { exp((l[idx[it]] - m).toDouble()) }
        val sum = p.sum()
        // nucleus over the candidates holding almost all the mass (top-p 0.98)
        val cand = (0 until count).filter { p[it] / sum > 1e-7 }.sortedByDescending { p[it] }
        var acc = 0.0
        var cut = cand.size
        for ((k, c) in cand.withIndex()) { acc += p[c] / sum; if (acc >= 0.98) { cut = k + 1; break } }
        val kept = cand.subList(0, cut)
        val z = kept.sumOf { p[it] }
        var r = rng.nextDouble() * z
        for (c in kept) { r -= p[c]; if (r <= 0) return idx[c] }
        return idx[kept.last()]
    }
}

object Wav {
    /** A 16 kHz mono PCM-16 WAV as floats (int16 / 32768), the fixtures' convention. */
    fun read16kMono(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(String(b.array(), 0, 4) == "RIFF" && String(b.array(), 8, 4) == "WAVE") { "not a WAV" }
        var pos = 12
        var data = -1
        var size = 0
        while (pos + 8 <= b.limit()) {
            val id = String(b.array(), pos, 4)
            val len = b.getInt(pos + 4)
            if (id == "fmt ") {
                require(b.getShort(pos + 8).toInt() == 1 && b.getShort(pos + 10).toInt() == 1 &&
                    b.getInt(pos + 12) == 16000 && b.getShort(pos + 22).toInt() == 16) { "need 16 kHz mono PCM-16" }
            }
            if (id == "data") { data = pos + 8; size = len; break }
            pos += 8 + len + (len and 1)
        }
        require(data > 0) { "no data chunk" }
        val n = size / 2
        return FloatArray(n) { b.getShort(data + 2 * it) / 32768f }
    }
}

object Proc {
    /** A /proc/self/status field in kB (VmRSS now, VmHWM = the peak resident set). */
    fun status(field: String): Long =
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("$field:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: -1
}

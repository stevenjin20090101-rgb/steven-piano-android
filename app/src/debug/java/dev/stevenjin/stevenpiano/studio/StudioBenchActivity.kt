// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/**
 * M22 Studio spike, temporary and debug-only. Two ways in, one code path ([StudioBenchRunner]):
 *
 * An activity (models and inputs pushed to filesDir/models and filesDir/studio first):
 *   adb shell am start -n dev.stevenjin.stevenpiano/.studio.StudioBenchActivity \
 *     --es bench transcription --es model transcription-v1.onnx --es wav bench_3min.wav \
 *     --es fixture transcription_window.json --es threads 4 --es mempattern false --es label tr-v1
 *   adb shell am start -n dev.stevenjin.stevenpiano/.studio.StudioBenchActivity \
 *     --es bench composer --es model composer-v1.onnx --es tokens 3600 --es keep 170 \
 *     --es fixture composer_seed.json --es threads 4 --es label comp-v1
 *
 * Or a debug property, read when the app's process starts ([StudioBenchTrigger]):
 *   adb shell setprop debug.stevenpiano.studiobench "transcription label=tablet-tr"
 *   (then open Steven Piano; clear it with adb shell "setprop debug.stevenpiano.studiobench ''")
 *
 * Keys: bench, model, wav (bench_3min.wav), fixture (the standard one when present), threads (4),
 * mempattern (false: the setting the app should use), arena (true), tokens (3600), keep (170),
 * seed (22), label (the bench's name). Writes filesDir/studio/bench-<label>.json and logs "StudioBench"
 * lines; the last one is "DONE <label>".
 */
class StudioBenchActivity : Activity() {
    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { setPadding(32, 32, 32, 32); textSize = 12f }
        setContentView(ScrollView(this).apply { addView(text) })
        val extras = intent.extras ?: Bundle()
        val args = extras.keySet().associateWith { extras.get(it).toString() }
        thread(name = "studio-bench") {
            StudioBenchRunner.run(filesDir, args) { s -> runOnUiThread { text.append(s + "\n") } }
        }
    }
}

/** Runs the bench named by the debug property when the process starts (debug builds only). */
class StudioBenchTrigger : ContentProvider() {
    override fun onCreate(): Boolean {
        val value = runCatching {
            ProcessBuilder("getprop", PROPERTY).start().inputStream.bufferedReader().use { it.readText().trim() }
        }.getOrDefault("")
        val filesDir = context?.filesDir
        if (value.isNotEmpty() && filesDir != null) {
            // "composer" or "transcription", then optional key=value pairs (a property holds 91 bytes)
            val args = value.split(Regex("\\s+")).mapNotNull { kv ->
                if ('=' in kv) kv.split("=", limit = 2).let { it[0] to it[1] } else "bench" to kv
            }.toMap()
            thread(name = "studio-bench-prop") { StudioBenchRunner.run(filesDir, args) {} }
        }
        return true
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object { const val PROPERTY = "debug.stevenpiano.studiobench" }
}

object StudioBenchRunner {
    const val TAG = "StudioBench"

    fun run(filesDir: File, args: Map<String, String>, show: (String) -> Unit) {
        val say = { s: String -> s.lines().forEach { Log.i(TAG, it) }; show(s) }
        val bench = args["bench"] ?: "transcription"
        val label = args["label"] ?: bench
        val threads = args["threads"]?.toIntOrNull() ?: 4
        val sb = StudioBench(filesDir, args["mempattern"]?.toBooleanStrictOrNull() ?: false,
            args["arena"]?.toBooleanStrictOrNull() ?: true, say)
        fun fixture(default: String) = args["fixture"] ?: default.takeIf { File(filesDir, "studio/$it").isFile }
        val result = runCatching {
            when (bench) {
                "transcription" -> sb.transcription(
                    args["model"] ?: "transcription-v1.onnx", args["wav"] ?: "bench_3min.wav", threads,
                    fixture("transcription_window.json"),
                )
                "composer" -> sb.composer(
                    args["model"] ?: "composer-v1.onnx", args["tokens"]?.toIntOrNull() ?: 3600, threads,
                    args["keep"]?.toIntOrNull() ?: 170, fixture("composer_seed.json"), args["seed"]?.toLongOrNull() ?: 22L,
                )
                else -> error("unknown bench $bench")
            }
        }
        val json = result.fold({ it.put("label", label).toString(2) }, { "{\"label\":\"$label\",\"error\":\"$it\"}" })
        File(filesDir, "studio").mkdirs()
        File(filesDir, "studio/bench-$label.json").writeText(json)
        result.exceptionOrNull()?.let { Log.e(TAG, "bench failed", it) }
        say(json)
        say("DONE $label")
    }
}

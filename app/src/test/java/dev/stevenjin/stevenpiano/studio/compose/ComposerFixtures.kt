// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio.compose

import dev.stevenjin.stevenpiano.midi.MidiPiece
import dev.stevenjin.stevenpiano.midi.SmfParser
import dev.stevenjin.stevenpiano.studio.ModelCatalogue
import dev.stevenjin.stevenpiano.studio.StudioFixtures
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The composer's fixtures from the Studio spike (M22, `tools/studio/fixtures`): `composer_seed.json`
 * (the vocabulary, the 214-token seed of `bach_bwv846.mid`'s first 15 s, PyTorch's 64 greedy tokens
 * and the INT8 file's own 64) and the MIDI file itself; and where the real `composer-v1.onnx` is, if
 * it is on this machine.
 */
object ComposerFixtures {
    val seed: JSONObject by lazy { JSONObject(StudioFixtures.file("composer_seed.json").readText()) }

    /** AUTOREGRESS, then the 71 events of the seed. */
    val inputTokens: IntArray by lazy { ints(seed.getJSONArray("input_tokens")) }

    /** PyTorch's 64 greedy tokens after the seed. */
    val torchContinuation: IntArray by lazy { ints(seed.getJSONArray("expected_continuation")) }

    /** The INT8 file's own 64 greedy tokens (the Mac's and the emulator's alike). */
    val int8Continuation: IntArray by lazy { ints(seed.getJSONArray("onnx_int8_continuation")) }

    val vocabulary: JSONObject by lazy { seed.getJSONObject("vocabulary") }

    /** `bach_bwv846.mid` through the app's own parser. */
    val bach: MidiPiece by lazy { SmfParser.parse(StudioFixtures.file("bach_bwv846.mid").readBytes()) }

    fun ints(array: JSONArray): IntArray = IntArray(array.length()) { array.getInt(it) }

    /**
     * `composer-v1.onnx` where the spike's tools leave it, if it is on this machine: the folder
     * `-PstudioModels=<dir>` names (the system property `stevenpiano.studio.models`), the environment
     * variable `STEVENPIANO_COMPOSER` (the file itself), `$STUDIO_WORK/exports`, or `~/studio-work/exports`.
     */
    fun modelFile(): File? {
        val file = ModelCatalogue.composer.file
        val candidates = listOfNotNull(
            System.getProperty("stevenpiano.studio.models")?.let { File(it, file) },
            System.getenv("STEVENPIANO_COMPOSER")?.let { File(it) },
            System.getenv("STUDIO_WORK")?.let { File("$it/exports", file) },
            System.getProperty("user.home")?.let { File("$it/studio-work/exports", file) },
        )
        return candidates.firstOrNull { it.isFile }
    }
}

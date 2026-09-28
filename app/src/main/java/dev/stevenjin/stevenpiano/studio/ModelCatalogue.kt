// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import dev.stevenjin.stevenpiano.update.UpdateSource

/**
 * One of Studio's models as this build knows it: [name] and [version] (the file is
 * `<name>-v<version>.onnx`, [file]), where it is published ([url], an asset of the release tagged
 * `models`), its exact [sizeBytes] and [sha256], pinned here in the source, its [licence] (SPDX) and
 * the one-line [attribution] About shows. [title] and [licenceLabel] are what the Studio page calls
 * it ("Transcription", "CC BY 4.0"); [use] says what it is for.
 */
data class ModelEntry(
    val name: String,
    val version: Int,
    val file: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val licence: String,
    val attribution: String,
    val title: String,
    val licenceLabel: String,
    val use: String,
)

/**
 * The models this build downloads and trusts (docs/STUDIO_SPIKE.md › What exists now): each file's
 * size and SHA-256 are pinned here, so a models' list that names another hash for a version this
 * build knows is refused whole ([ModelManifest]), and a download is kept only when it hashes to the
 * pin ([VerifiedDownloader]). A new model or version comes with a new build.
 */
object ModelCatalogue {
    /** ByteDance's high-resolution piano transcription (Kong et al.), INT8 ONNX: recordings into notes. */
    val transcription = ModelEntry(
        name = "transcription",
        version = 1,
        file = "transcription-v1.onnx",
        url = "https://github.com/${UpdateSource.REPOSITORY}/releases/download/${UpdateSource.MODELS_TAG}/transcription-v1.onnx",
        sizeBytes = 124_511_036L,
        sha256 = "f5db051a0af4a3601c18b3ecf679be3150912d8d535e3a03554c9662c8525383",
        licence = "CC-BY-4.0",
        attribution = "Piano transcription model — Kong et al., ByteDance, CC BY 4.0, Zenodo 4034264",
        title = "Transcription",
        licenceLabel = "CC BY 4.0",
        use = "Turns a piano recording into a piece.",
    )

    /** The Anticipatory Music Transformer (Thickstun et al., Stanford CRFM), INT8 ONNX: composing, from the next update. */
    val composer = ModelEntry(
        name = "composer",
        version = 1,
        file = "composer-v1.onnx",
        url = "https://github.com/${UpdateSource.REPOSITORY}/releases/download/${UpdateSource.MODELS_TAG}/composer-v1.onnx",
        sizeBytes = 173_193_820L,
        sha256 = "86ddb19c7afce2bab6be13706cb0a0f44cd7a4271c021706d02394c10cbda7b1",
        licence = "Apache-2.0",
        attribution = "Anticipatory Music Transformer — Thickstun et al., Stanford CRFM, Apache 2.0, Hugging Face stanford-crfm/music-small-800k",
        title = "Composing",
        licenceLabel = "Apache 2.0",
        use = "For composing, which comes in the next update.",
    )

    /** In the Studio page's order. */
    val all: List<ModelEntry> = listOf(transcription, composer)

    fun named(name: String): ModelEntry? = all.firstOrNull { it.name == name }
}

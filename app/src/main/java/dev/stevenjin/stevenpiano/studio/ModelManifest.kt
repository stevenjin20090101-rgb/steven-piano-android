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
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * The models' list, `releases/models.json` (written by `tools/studio/publish_models.py`, published on
 * `main` and as an asset of the release `models`):
 *
 * ```
 * {"models": [{"name": "transcription", "version": 1, "file": "transcription-v1.onnx",
 *   "url": "https://github.com/stevenjin20090101-rgb/steven-piano-android/releases/download/models/transcription-v1.onnx",
 *   "sizeBytes": 124511036, "sha256": "<64 hex digits>", "licence": "CC-BY-4.0", "attribution": "…",
 *   "source": "…", "inputs": […], "outputs": […]}, …]}
 * ```
 *
 * Read with `org.json`; fields the app does not use (`source`, `inputs`, `outputs`, anything later)
 * are ignored. Every entry is checked: `name` `[a-z][a-z0-9-]{0,31}`, `version` 1..1,000, `file`
 * exactly `<name>-v<version>.onnx`, `url` a file the [UpdateSource] allows ([UpdateSource.allowsModel])
 * whose last segment is `file`, `sizeBytes` 1 byte to [UpdateSource.MAX_MODEL_BYTES], `sha256` 64 hex
 * digits (kept lower-case), `licence` an SPDX-like word, `attribution` plain text of at most
 * [MAX_ATTRIBUTION]; at most [MAX_MODELS] entries, no name and version twice. An entry for a version
 * this build knows ([ModelCatalogue]) must carry its pinned size and SHA-256, or the whole list is
 * refused: it is not what this build was made to trust. Anything wrong throws [InvalidModelManifest].
 */
class ModelManifest private constructor(val entries: List<Entry>) {
    data class Entry(
        val name: String,
        val version: Int,
        val file: String,
        val url: String,
        val sizeBytes: Long,
        val sha256: String,
        val licence: String,
        val attribution: String,
    )

    /** The list's entry for [model] (its name and version), or null when it offers none. */
    fun entryFor(model: ModelEntry): Entry? = entries.firstOrNull { it.name == model.name && it.version == model.version }

    companion object {
        /** The list is about 14 KB (the models' input and output shapes); anything past this is not one. */
        const val MAX_BYTES = 64 * 1024
        const val MAX_MODELS = 32
        const val MAX_ATTRIBUTION = 300
        private const val MAX_VERSION = 1_000L
        private val NAME = Regex("[a-z][a-z0-9-]{0,31}")
        private val SHA256 = Regex("[0-9a-fA-F]{64}")
        private val LICENCE = Regex("[A-Za-z0-9.+-]{1,40}")

        /** The list in [text], each entry checked against [source] and the [pinned] models. */
        fun parse(text: String, source: UpdateSource, pinned: List<ModelEntry> = ModelCatalogue.all): ModelManifest {
            val json = try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw InvalidModelManifest("not a JSON object")
            } catch (e: StackOverflowError) {
                throw InvalidModelManifest("nested too deeply")
            }
            val list = json.opt("models") as? JSONArray ?: throw InvalidModelManifest("models")
            if (list.length() > MAX_MODELS) throw InvalidModelManifest("models")
            val entries = (0 until list.length()).map { i ->
                val item = list.opt(i) as? JSONObject ?: throw InvalidModelManifest("models[$i]")
                entry(item, source)
            }
            if (entries.map { it.name to it.version }.toSet().size != entries.size) throw InvalidModelManifest("the same model twice")
            for (entry in entries) {
                val pin = pinned.firstOrNull { it.name == entry.name && it.version == entry.version } ?: continue
                if (entry.sha256 != pin.sha256) throw InvalidModelManifest("${entry.file}: sha256 is not the pinned one")
                if (entry.sizeBytes != pin.sizeBytes) throw InvalidModelManifest("${entry.file}: sizeBytes is not the pinned one")
            }
            return ModelManifest(entries)
        }

        private fun entry(json: JSONObject, source: UpdateSource): Entry {
            val name = string(json, "name")
            if (!NAME.matches(name)) throw InvalidModelManifest("name")
            val version = integer(json, "version", 1L..MAX_VERSION).toInt()
            val file = string(json, "file")
            if (file != "$name-v$version.onnx") throw InvalidModelManifest("file")
            val url = string(json, "url")
            if (!source.allowsModel(url) || url.substringAfterLast('/') != file) throw InvalidModelManifest("url")
            val sizeBytes = integer(json, "sizeBytes", 1L..UpdateSource.MAX_MODEL_BYTES)
            val sha256 = string(json, "sha256")
            if (!SHA256.matches(sha256)) throw InvalidModelManifest("sha256")
            val licence = string(json, "licence")
            if (!LICENCE.matches(licence)) throw InvalidModelManifest("licence")
            val attribution = string(json, "attribution").filter { !it.isISOControl() }.trim()
            if (attribution.isEmpty() || attribution.length > MAX_ATTRIBUTION) throw InvalidModelManifest("attribution")
            return Entry(name, version, file, url, sizeBytes, sha256.lowercase(Locale.ROOT), licence, attribution)
        }

        /** A whole number in [range]; a JSON number only (a string of digits is refused). */
        private fun integer(json: JSONObject, name: String, range: LongRange): Long {
            val value = json.opt(name) as? Number ?: throw InvalidModelManifest(name)
            val whole = value.toLong()
            if (whole.toDouble() != value.toDouble() || whole !in range) throw InvalidModelManifest(name)
            return whole
        }

        private fun string(json: JSONObject, name: String): String {
            val value = json.opt(name) as? String ?: throw InvalidModelManifest(name)
            if (value.isBlank()) throw InvalidModelManifest(name)
            return value
        }
    }
}

/** A models' list that is not one, or has a field missing or wrong ([message] names it). */
class InvalidModelManifest(field: String) : Exception("Invalid models list: $field")

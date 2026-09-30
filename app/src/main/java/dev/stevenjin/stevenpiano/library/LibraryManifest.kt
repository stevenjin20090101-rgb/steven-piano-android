// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.library

import dev.stevenjin.stevenpiano.data.imports.ImportLimits
import dev.stevenjin.stevenpiano.update.UpdateSource
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * Steven's library pack as `releases/library.json` on `main` describes it (v1.10 — M27; written by
 * `tools/publish_library.py`, also an asset of the release `library`):
 *
 * ```
 * {"version": 1, "file": "library-v1.zip",
 *  "url": "https://github.com/stevenjin20090101-rgb/steven-piano-android/releases/download/library/library-v1.zip",
 *  "sizeBytes": 61237277, "sha256": "<64 hex digits>", "pieces": 1726, "notes": "…",
 *  "licences": ["MAESTRO CC BY-NC-SA 4.0", "piano-midi.de CC BY-SA 3.0 DE", "Mutopia public domain"]}
 * ```
 *
 * Read with `org.json`, at most [MAX_BYTES] of it; every field is checked: `version` 1..[MAX_VERSION],
 * `file` exactly `library-v<version>.zip`, `url` a zip the [UpdateSource] allows
 * ([UpdateSource.allowsLibrary]) whose last segment is `file`, `sizeBytes` 1 byte to
 * [UpdateSource.MAX_LIBRARY_BYTES], `sha256` 64 hex digits (kept lower-case), `pieces` 1 to
 * [ImportLimits.ZIP_ENTRIES]; `notes` (plain text, at most [MAX_NOTES]) and `licences` (at most
 * [MAX_LICENCES] lines of plain text, each at most [MAX_LICENCE]) may be left out. Fields the app does
 * not use are ignored. Anything wrong throws [InvalidLibraryManifest]. The SHA-256 is what the zip must
 * hash to before a byte of it is read; the trust is the manifest's address (this repository's `main`),
 * as for the app's own updates.
 */
data class LibraryManifest(
    val version: Int,
    val file: String,
    val url: String,
    val sizeBytes: Long,
    /** 64 lower-case hex digits. */
    val sha256: String,
    /** The pieces in the pack: each file once. */
    val pieces: Int,
    val notes: String = "",
    val licences: List<String> = emptyList(),
) {
    companion object {
        /** The manifest is about 600 bytes; anything past this is not one. */
        const val MAX_BYTES = 4 * 1024
        const val MAX_VERSION = 10_000
        const val MAX_NOTES = 1_000
        const val MAX_LICENCES = 8
        const val MAX_LICENCE = 120
        private val SHA256 = Regex("[0-9a-fA-F]{64}")

        /** The manifest in [text], checked field by field against [source]. */
        fun parse(text: String, source: UpdateSource): LibraryManifest {
            if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw InvalidLibraryManifest("larger than $MAX_BYTES bytes")
            val json = try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw InvalidLibraryManifest("not a JSON object")
            } catch (e: StackOverflowError) {
                throw InvalidLibraryManifest("nested too deeply")
            }
            val version = integer(json, "version", 1L..MAX_VERSION).toInt()
            val file = string(json, "file")
            if (file != fileName(version)) throw InvalidLibraryManifest("file")
            val url = string(json, "url")
            if (!source.allowsLibrary(url) || url.substringAfterLast('/') != file) throw InvalidLibraryManifest("url")
            val sizeBytes = integer(json, "sizeBytes", 1L..UpdateSource.MAX_LIBRARY_BYTES)
            val sha256 = string(json, "sha256")
            if (!SHA256.matches(sha256)) throw InvalidLibraryManifest("sha256")
            val pieces = integer(json, "pieces", 1L..ImportLimits.ZIP_ENTRIES.toLong()).toInt()
            val notes = if (json.has("notes")) plain(string(json, "notes", allowEmpty = true), MAX_NOTES) else ""
            val licences = when (val raw = json.opt("licences")) {
                null -> emptyList()
                is JSONArray -> {
                    if (raw.length() > MAX_LICENCES) throw InvalidLibraryManifest("licences")
                    (0 until raw.length()).map { i ->
                        val line = (raw.opt(i) as? String)?.let { plain(it, Int.MAX_VALUE) } ?: throw InvalidLibraryManifest("licences[$i]")
                        if (line.isEmpty() || line.length > MAX_LICENCE) throw InvalidLibraryManifest("licences[$i]")
                        line
                    }
                }
                else -> throw InvalidLibraryManifest("licences")
            }
            return LibraryManifest(version, file, url, sizeBytes, sha256.lowercase(Locale.ROOT), pieces, notes, licences)
        }

        /** The pack's file for [version]: `library-v<version>.zip`. */
        fun fileName(version: Int): String = "library-v$version${UpdateSource.LIBRARY_EXTENSION}"

        /** A whole number in [range]; a JSON number only (a string of digits is refused). */
        private fun integer(json: JSONObject, name: String, range: LongRange): Long {
            val value = json.opt(name) as? Number ?: throw InvalidLibraryManifest(name)
            val whole = value.toLong()
            if (whole.toDouble() != value.toDouble() || whole !in range) throw InvalidLibraryManifest(name)
            return whole
        }

        private fun string(json: JSONObject, name: String, allowEmpty: Boolean = false): String {
            val value = json.opt(name) as? String ?: throw InvalidLibraryManifest(name)
            if (!allowEmpty && value.isBlank()) throw InvalidLibraryManifest(name)
            return value
        }

        /** Plain text: line breaks kept, other control characters dropped, cut to [max] on a code point. */
        private fun plain(text: String, max: Int): String {
            val kept = text.filter { it == '\n' || !it.isISOControl() }.trim()
            if (kept.length <= max) return kept
            val end = if (Character.isHighSurrogate(kept[max - 1])) max - 1 else max
            return kept.substring(0, end)
        }
    }
}

/** A library manifest that is not one, or has a field missing or wrong ([message] names it). */
class InvalidLibraryManifest(field: String) : Exception("Invalid library manifest: $field")

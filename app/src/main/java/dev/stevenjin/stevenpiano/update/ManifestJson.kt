// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.update

import org.json.JSONObject

/**
 * The manifests' fields, read one way (v1.24 — M57): the app's update ([UpdateManifest]), the piano's firmware, Studio's
 * models and the library pack each hand theirs to one of these. A field missing or wrong throws what [invalid] makes of
 * its name, so each manifest keeps its own error.
 */
class ManifestJson(private val invalid: (String) -> Exception) {
    /** A whole number in [range]; a JSON number only (a string of digits is refused). */
    fun integer(json: JSONObject, name: String, range: LongRange): Long {
        val value = json.opt(name) as? Number ?: throw invalid(name)
        val whole = value.toLong()
        if (whole.toDouble() != value.toDouble() || whole !in range) throw invalid(name)
        return whole
    }

    /** A string, not blank unless [allowEmpty]. */
    fun string(json: JSONObject, name: String, allowEmpty: Boolean = false): String {
        val value = json.opt(name) as? String ?: throw invalid(name)
        if (!allowEmpty && value.isBlank()) throw invalid(name)
        return value
    }

    companion object {
        /** Notes as plain text: line breaks kept, other control characters dropped, trimmed, cut to [max] on a code point. */
        fun plain(text: String, max: Int): String {
            val kept = text.filter { it == '\n' || !it.isISOControl() }.trim()
            if (kept.length <= max) return kept
            val end = if (Character.isHighSurrogate(kept[max - 1])) max - 1 else max
            return kept.substring(0, end)
        }
    }
}

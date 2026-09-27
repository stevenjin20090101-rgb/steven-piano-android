// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.update

import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * One release, as `releases/latest.json` in the app's repository describes it (written by
 * `tools/publish-release.sh`):
 *
 * ```
 * {"versionCode": 8, "versionName": "1.4", "notes": "…",
 *  "apkUrl": "https://github.com/stevenjin20090101-rgb/steven-piano-android/releases/download/v1.4/steven-piano-1.4.apk",
 *  "sha256": "<64 hex digits>", "sizeBytes": 2400000, "minSdk": 26}
 * ```
 *
 * The SHA-256 is what the downloaded file must hash to before anything else happens with it; the
 * release key's signature is what Android checks when it installs it. Unknown fields are ignored,
 * so a later manifest may carry more.
 */
data class UpdateManifest(
    val versionCode: Int,
    /** Plain letters, digits, dots, dashes and underscores (it names the downloaded file). */
    val versionName: String,
    /** The release notes: plain text, at most [MAX_NOTES] characters. */
    val notes: String,
    val apkUrl: String,
    /** 64 lower-case hex digits. */
    val sha256: String,
    val sizeBytes: Long,
    /** The oldest Android (API level) the release runs on; 0 when the manifest does not say. */
    val minSdk: Int,
) {
    companion object {
        /** A manifest is a few hundred bytes; anything past this is not one. */
        const val MAX_MANIFEST_BYTES = 64 * 1024

        /** The largest file the updater downloads (the app is about 2.4 MB). */
        const val MAX_APK_BYTES = 50L * 1024 * 1024
        const val MAX_NOTES = 1_000
        private const val MAX_VERSION_NAME = 32

        /** Android's own ceiling on versionCode. */
        private const val MAX_VERSION_CODE = 2_100_000_000
        private const val MAX_SDK = 1_000
        private val VERSION_NAME = Regex("[0-9A-Za-z][0-9A-Za-z._-]*")
        private val SHA256 = Regex("[0-9a-fA-F]{64}")

        /**
         * The manifest in [text], checked field by field against [source] (the file must be a
         * release asset of the app's repository): throws [InvalidManifest] naming the first field
         * that is missing or wrong.
         */
        fun parse(text: String, source: UpdateSource): UpdateManifest {
            val json = try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw InvalidManifest("not a JSON object")
            } catch (e: StackOverflowError) {
                throw InvalidManifest("nested too deeply")
            }
            val versionCode = integer(json, "versionCode", 1L..MAX_VERSION_CODE).toInt()
            val versionName = string(json, "versionName")
            if (versionName.length > MAX_VERSION_NAME || !VERSION_NAME.matches(versionName)) throw InvalidManifest("versionName")
            val notes = if (json.has("notes")) string(json, "notes", allowEmpty = true) else ""
            val apkUrl = string(json, "apkUrl")
            if (!source.allowsApk(apkUrl)) throw InvalidManifest("apkUrl")
            val sha256 = string(json, "sha256")
            if (!SHA256.matches(sha256)) throw InvalidManifest("sha256")
            val sizeBytes = integer(json, "sizeBytes", 1L..MAX_APK_BYTES)
            val minSdk = if (json.has("minSdk")) integer(json, "minSdk", 1L..MAX_SDK.toLong()).toInt() else 0
            return UpdateManifest(versionCode, versionName, cleanNotes(notes), apkUrl, sha256.lowercase(Locale.ROOT), sizeBytes, minSdk)
        }

        /** A whole number in [range]; a JSON number only (a string of digits is refused). */
        private fun integer(json: JSONObject, name: String, range: LongRange): Long {
            val value = json.opt(name) as? Number ?: throw InvalidManifest(name)
            val whole = value.toLong()
            if (whole.toDouble() != value.toDouble() || whole !in range) throw InvalidManifest(name)
            return whole
        }

        private fun string(json: JSONObject, name: String, allowEmpty: Boolean = false): String {
            val value = json.opt(name) as? String ?: throw InvalidManifest(name)
            if (!allowEmpty && value.isBlank()) throw InvalidManifest(name)
            return value
        }

        /** Notes as plain text: line breaks kept, other control characters dropped, cut to [MAX_NOTES] on a code point. */
        private fun cleanNotes(notes: String): String {
            val kept = notes.filter { it == '\n' || !it.isISOControl() }.trim()
            if (kept.length <= MAX_NOTES) return kept
            val end = if (Character.isHighSurrogate(kept[MAX_NOTES - 1])) MAX_NOTES - 1 else MAX_NOTES
            return kept.substring(0, end)
        }
    }
}

/** A manifest that is not one, or has a field missing or wrong ([message] names it). */
class InvalidManifest(field: String) : Exception("Invalid update manifest: $field")

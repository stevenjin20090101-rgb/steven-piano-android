// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.firmware

import dev.stevenjin.stevenpiano.update.UpdateSource
import org.json.JSONException
import org.json.JSONObject
import java.util.Base64
import java.util.Locale

/**
 * One piano firmware release, as the firmware repository's `releases/latest.json` describes it
 * (BLE_OTA.md › 10; written by `firmware/tools/publish-firmware.sh`):
 *
 * ```
 * {"version": "2.1.0", "build": "a1b2c3d",
 *  "binUrl": "https://github.com/stevenjin20090101-rgb/Steven-Jin-Player-Piano/releases/download/fw-v2.1.0/firmware-2.1.0.bin",
 *  "sizeBytes": 991232, "sha256": "<64 hex digits>", "sig": "<88 base64 characters>",
 *  "minAppVersionCode": 11, "notes": "…", "usbOnly": false}
 * ```
 *
 * [sig] is Ed25519 over the 32 raw bytes of [sha256], made with the author's key ([verify]); the
 * downloaded image must hash to [sha256], and the piano checks both again before it boots anything.
 * Unknown fields are ignored. Read with [parse], which checks every field and throws
 * [InvalidFirmwareManifest] naming the first that is missing or wrong.
 */
data class FirmwareManifest(
    /** `FW_VERSION`, "MAJOR.MINOR.PATCH" (a "-pre" allowed), at most 16 bytes: BEGIN's version field. */
    val version: String,
    /** The git hash the image was built from: the piano then reports "version+build". */
    val build: String,
    /** A `.bin` release asset of the firmware's repository ([UpdateSource.allowsFirmwareFile]). */
    val binUrl: String,
    val sizeBytes: Long,
    /** 64 lower-case hex digits. */
    val sha256: String,
    /** Base64 of the 64-byte Ed25519 signature, 88 characters. */
    val sig: String,
    /** The lowest app versionCode that may install it. */
    val minAppVersionCode: Int,
    /** Plain text, at most [MAX_NOTES] characters. */
    val notes: String,
    /** The release changes what an app-slot write can't carry (the partition table, the bootloader): USB only. */
    val usbOnly: Boolean,
) {
    /** [version], parsed; [parse] made sure it parses. */
    val parsedVersion: FirmwareVersion get() = requireNotNull(FirmwareVersion.parse(version))

    /** The 32 raw bytes of [sha256]. */
    fun digest(): ByteArray = ByteArray(DIGEST_BYTES) { sha256.substring(it * 2, it * 2 + 2).toInt(HEX).toByte() }

    /** The 64 bytes of [sig]. */
    fun signature(): ByteArray = Base64.getDecoder().decode(sig)

    /**
     * Whether [sig] is [publicKey]'s Ed25519 signature of the 32 raw bytes of [sha256] (BLE_OTA.md
     * › 8): the release is the author's. [platformFirst]: Android 13 and newer ([Ed25519]).
     */
    fun verify(publicKey: ByteArray, platformFirst: Boolean = false): Boolean = check(publicKey, platformFirst).valid

    /** [verify], with the engine that answered (the platform's or the library's), for the log. */
    fun check(publicKey: ByteArray, platformFirst: Boolean = false): Ed25519.Answer =
        Ed25519.check(publicKey, digest(), signature(), platformFirst)

    /** Whether this release needs a newer app than the one whose versionCode is [appVersionCode]. */
    fun needsNewerApp(appVersionCode: Int): Boolean = minAppVersionCode > appVersionCode

    companion object {
        /** A manifest is a few hundred bytes; the HTTP client reads 64 KB at most. */
        const val MAX_NOTES = 1_000

        /** BEGIN carries the version in 16 bytes (BLE_OTA.md › 5). */
        const val MAX_VERSION_BYTES = 16
        const val DIGEST_BYTES = 32
        private const val SIGNATURE_CHARS = 88
        private const val HEX = 16
        private const val MAX_VERSION_CODE = 2_100_000_000L
        private val VERSION = Regex("""[0-9]{1,5}\.[0-9]{1,5}\.[0-9]{1,5}(-[0-9A-Za-z.-]+)?""")
        private val BUILD = Regex("[0-9a-f]{7,40}")
        private val SHA256 = Regex("[0-9a-fA-F]{64}")

        /**
         * The manifest in [text], checked field by field: throws [InvalidFirmwareManifest] naming the
         * first field that is missing or wrong. The signature is not checked here ([verify] is).
         */
        fun parse(text: String): FirmwareManifest {
            val json = try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw InvalidFirmwareManifest("not a JSON object")
            } catch (e: StackOverflowError) {
                throw InvalidFirmwareManifest("nested too deeply")
            }
            val version = string(json, "version")
            if (!VERSION.matches(version) || version.toByteArray(Charsets.UTF_8).size > MAX_VERSION_BYTES || FirmwareVersion.parse(version) == null) {
                throw InvalidFirmwareManifest("version")
            }
            val build = string(json, "build")
            if (!BUILD.matches(build)) throw InvalidFirmwareManifest("build")
            val binUrl = string(json, "binUrl")
            if (!UpdateSource.allowsFirmwareFile(binUrl)) throw InvalidFirmwareManifest("binUrl")
            val sizeBytes = integer(json, "sizeBytes", 1L..UpdateSource.MAX_FIRMWARE_BYTES)
            val sha256 = string(json, "sha256")
            if (!SHA256.matches(sha256)) throw InvalidFirmwareManifest("sha256")
            val sig = string(json, "sig")
            if (sig.length != SIGNATURE_CHARS || decoded(sig)?.size != Ed25519.SIGNATURE_BYTES) throw InvalidFirmwareManifest("sig")
            val minApp = integer(json, "minAppVersionCode", 1L..MAX_VERSION_CODE).toInt()
            val notes = if (json.has("notes")) string(json, "notes", allowEmpty = true) else ""
            val usbOnly = json.opt("usbOnly") as? Boolean ?: throw InvalidFirmwareManifest("usbOnly")
            return FirmwareManifest(
                version = version,
                build = build,
                binUrl = binUrl,
                sizeBytes = sizeBytes,
                sha256 = sha256.lowercase(Locale.ROOT),
                sig = sig,
                minAppVersionCode = minApp,
                notes = cleanNotes(notes),
                usbOnly = usbOnly,
            )
        }

        private fun decoded(base64: String): ByteArray? = try {
            Base64.getDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            null
        }

        /** A whole number in [range]; a JSON number only (a string of digits is refused). */
        private fun integer(json: JSONObject, name: String, range: LongRange): Long {
            val value = json.opt(name) as? Number ?: throw InvalidFirmwareManifest(name)
            val whole = value.toLong()
            if (whole.toDouble() != value.toDouble() || whole !in range) throw InvalidFirmwareManifest(name)
            return whole
        }

        private fun string(json: JSONObject, name: String, allowEmpty: Boolean = false): String {
            val value = json.opt(name) as? String ?: throw InvalidFirmwareManifest(name)
            if (!allowEmpty && value.isBlank()) throw InvalidFirmwareManifest(name)
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

/** A firmware manifest that is not one, or has a field missing or wrong ([message] names it). */
class InvalidFirmwareManifest(field: String) : Exception("Invalid firmware manifest: $field")

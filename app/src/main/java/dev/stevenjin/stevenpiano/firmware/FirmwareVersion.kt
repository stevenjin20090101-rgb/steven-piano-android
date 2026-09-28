// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.firmware

/**
 * A piano firmware version: as the piano reports it, "2.0.0+a1b2c3d" (`FW_VERSION` "+" `FW_BUILD`,
 * BLE_OTA.md › 2: Device Information 0x2A26 and the settings dump's `!fw`), or as a release
 * manifest names it, "2.1.0". Releases compare as semver ([compareTo]): the numbers, then a
 * pre-release below its release; the [build] never counts.
 */
data class FirmwareVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** "rc1" in "2.1.0-rc1"; null for a release. */
    val preRelease: String? = null,
    /** The git hash it was built from ("a1b2c3d", "a1b2c3d-dirty", "nogit"); null when not said. */
    val build: String? = null,
) : Comparable<FirmwareVersion> {
    /** "2.0.0" (or "2.1.0-rc1"): the release, without the build. */
    val release: String get() = "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")

    /** "2.0.0 · a1b2c3d": how the Firmware page shows the piano's version. */
    val shown: String get() = if (build.isNullOrEmpty()) release else "$release · $build"

    override fun compareTo(other: FirmwareVersion): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        val mine = preRelease
        val theirs = other.preRelease
        return when {
            mine == null && theirs == null -> 0
            mine == null -> 1   // a release is above its own pre-releases
            theirs == null -> -1
            else -> comparePreRelease(mine, theirs)
        }
    }

    /** The same release as [other], whatever either was built from. */
    fun sameRelease(other: FirmwareVersion): Boolean = compareTo(other) == 0

    companion object {
        private val PATTERN = Regex("""^(\d{1,9})\.(\d{1,9})\.(\d{1,9})(?:-([0-9A-Za-z.-]+))?(?:\+([0-9A-Za-z.-]+))?$""")

        /**
         * The version in [text] (NULs and spaces around it ignored: 0x2A26 may be padded), or null
         * when it is not "MAJOR.MINOR.PATCH" with an optional "-pre" and "+build".
         */
        fun parse(text: String?): FirmwareVersion? {
            val trimmed = text?.trim { it <= ' ' || it == '\u0000' } ?: return null
            val match = PATTERN.matchEntire(trimmed) ?: return null
            val (major, minor, patch, pre, build) = match.destructured
            return FirmwareVersion(
                major.toIntOrNull() ?: return null,
                minor.toIntOrNull() ?: return null,
                patch.toIntOrNull() ?: return null,
                pre.ifEmpty { null },
                build.ifEmpty { null },
            )
        }

        /** Semver's order of pre-release identifiers: numbers numerically and below words; more identifiers above fewer. */
        private fun comparePreRelease(a: String, b: String): Int {
            val left = a.split('.')
            val right = b.split('.')
            for (i in 0 until minOf(left.size, right.size)) {
                val x = left[i]
                val y = right[i]
                val xn = x.toLongOrNull()
                val yn = y.toLongOrNull()
                val c = when {
                    xn != null && yn != null -> xn.compareTo(yn)
                    xn != null -> -1
                    yn != null -> 1
                    else -> x.compareTo(y)
                }
                if (c != 0) return c
            }
            return left.size.compareTo(right.size)
        }
    }
}
